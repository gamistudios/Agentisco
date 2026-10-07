package com.awaki

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.awaki.local.FakeEngine
import com.awaki.local.ggufBytes
import com.awaki.local.repositoryWithInstalled
import com.awaki.local.server.LocalAiApi
import com.awaki.local.server.LocalAiEndpoint
import com.awaki.local.server.LocalAiServer
import com.awaki.local.runtime.LocalAnswerDelta
import com.awaki.local.runtime.LocalInferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * The transport, driven the way a client drives it: bytes on a socket.
 *
 * Framing is where a hand-written HTTP server fails in the field rather than in review —
 * a missing blank line, a body counted in characters instead of bytes, a stream that never
 * ends — so these tests read the raw response instead of going through a client that would
 * hide all three.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalAiServerTest {

    private lateinit var context: Context
    private val payload = ggufBytes(4096)
    private var server: LocalAiServer? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "local-models").deleteRecursively()
    }

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    // ---- harness ----

    private suspend fun start(vararg ids: String): LocalAiEndpoint = start(ids.toList())

    /**
     * Starts the transport over [engine], with the stream's clock shortened.
     *
     * The shipped waits are long enough to be invisible on a phone and far too long for a test to
     * sit through, so the tests hand the server its own numbers and the behaviour they gate — a
     * stream that says it is alive while the model works — is the one being checked.
     */
    private suspend fun start(
        ids: List<String>,
        engine: FakeEngine = FakeEngine(),
        streamOpenAfterMs: Long = 120,
        keepAliveMs: Long = 60
    ): LocalAiEndpoint {
        val repository = repositoryWithInstalled(context, payload, *ids.toTypedArray())
        val api = LocalAiApi(LocalInferenceEngine(repository, engine, Dispatchers.Unconfined), repository)
        return LocalAiServer(api, streamOpenAfterMs, keepAliveMs).also { server = it }.start()
    }

    /** An engine that says nothing for [millis] before its first piece, the way a loading model does. */
    private fun slowEngine(millis: Long): FakeEngine = FakeEngine().apply {
        sessionScript = { session ->
            session.deltas = { piece ->
                Thread.sleep(millis)
                listOf(LocalAnswerDelta(content = piece))
            }
        }
    }

    /** Sends raw request bytes and reads the whole reply, which ends when the server closes. */
    private fun send(endpoint: LocalAiEndpoint, request: String, body: String = "", authorized: Boolean = true): String {
        val headers = buildString {
            append(request.trim()).append("\r\n")
            append("Host: 127.0.0.1\r\n")
            if (authorized) append("Authorization: Bearer ${endpoint.token}\r\n")
            if (body.isNotEmpty()) append("Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n")
            append("\r\n")
        }
        return Socket(InetAddress.getByName("127.0.0.1"), endpoint.port()).use { socket ->
            socket.soTimeout = 20_000
            socket.getOutputStream().apply {
                write((headers + body).toByteArray(StandardCharsets.UTF_8))
                flush()
            }
            socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }
    }

    private fun chat(endpoint: LocalAiEndpoint, request: String, authorized: Boolean = true) =
        send(endpoint, "POST /v1/chat/completions HTTP/1.1", request, authorized)

    private fun LocalAiEndpoint.port(): Int =
        baseUrl.removePrefix("http://127.0.0.1:").substringBefore('/').toInt()

    private fun statusOf(response: String): Int = response.substringAfter("HTTP/1.1 ").take(3).trim().toInt()

    private fun bodyOf(response: String): String = response.substringAfter("\r\n\r\n")

    // ---- requests ----

    @Test
    fun `a models request over the network comes back as json`() = runTest {
        val endpoint = start("alpha")

        val response = send(endpoint, "GET /v1/models HTTP/1.1")

        assertEquals(200, statusOf(response))
        assertTrue(response.contains("Content-Type: application/json"))
        assertTrue(response.contains("Content-Length: "))
        assertEquals("alpha", JSONObject(bodyOf(response)).getJSONArray("data").getJSONObject(0).getString("id"))
    }

    @Test
    fun `a request without this server's token is refused before anything runs`() = runTest {
        val endpoint = start("alpha")

        val response = send(endpoint, "GET /v1/models HTTP/1.1", authorized = false)

        assertEquals(401, statusOf(response))
        assertEquals("authentication_error", JSONObject(bodyOf(response)).getJSONObject("error").getString("type"))
    }

    @Test
    fun `a streamed completion is framed as server-sent events`() = runTest {
        val endpoint = start("alpha")

        val response = chat(endpoint, """{"model":"alpha","stream":true,"messages":[{"role":"user","content":"hi"}]}""")

        assertEquals(200, statusOf(response))
        assertTrue(response.contains("Content-Type: text/event-stream"))
        // No Content-Length on a stream: the reply is a sequence of frames, and the last
        // one is the sentinel every OpenAI client waits for.
        val frames = bodyOf(response).split("\r\n\r\n").filter { it.isNotBlank() }
        assertTrue(frames.first().startsWith("data: {"))
        assertEquals("data: [DONE]", frames.last())
        val contents = frames.dropLast(1).mapNotNull { frame ->
            JSONObject(frame.removePrefix("data: ")).getJSONArray("choices").getJSONObject(0)
                .optJSONObject("delta")?.optString("content")?.takeIf { it.isNotEmpty() }
        }
        assertEquals(listOf("hello"), contents)
    }

    /**
     * A stream waiting on a model has to look like a stream while it waits. OkHttp no longer caps
     * the read, but a third-party client on the same port, or anything between the two that drops
     * an idle connection, still reads silence as failure — and a comment frame is what SSE has for
     * saying "still here" without putting anything in anyone's answer.
     */
    @Test
    fun `a stream that has nothing to say yet says it is alive`() = runTest {
        val endpoint = start(listOf("alpha"), slowEngine(600))

        val response = chat(endpoint, """{"model":"alpha","stream":true,"messages":[{"role":"user","content":"hi"}]}""")

        assertEquals(200, statusOf(response))
        assertTrue(response.contains("Content-Type: text/event-stream"))
        val frames = bodyOf(response).split("\r\n\r\n").filter { it.isNotBlank() }
        val keepAlives = frames.filter { it.startsWith(": ") }
        assertTrue("a silent stream must produce keep-alive frames, saw: $frames", keepAlives.isNotEmpty())
        // The point of the frame is that no client reads it as an answer.
        assertTrue(keepAlives.none { it.contains("data:") })
        assertEquals("data: [DONE]", frames.last { !it.startsWith(": ") })
        // The first frame is the head, which carries a role and no text; the answer is the
        // content a client assembles out of the deltas that follow it.
        assertEquals(
            listOf("hello"),
            frames.filter { it.startsWith("data: {") }.mapNotNull { frame ->
                JSONObject(frame.removePrefix("data: ")).getJSONArray("choices").getJSONObject(0)
                    .optJSONObject("delta")?.optString("content")?.takeIf { it.isNotEmpty() }
            }
        )
    }

    /**
     * The clock belongs to streams alone. A caller that asked for one counted body must still get a
     * plain 400 for a prompt too long for the context however long the model took to be read, which
     * is why the server writes no headers before it has run every check that could answer as a body.
     */
    @Test
    fun `a request that did not ask for a stream is never opened as one`() = runTest {
        val endpoint = start(listOf("alpha"), slowEngine(600))

        val response = chat(endpoint, """{"model":"alpha","messages":[{"role":"user","content":"hi"}]}""")

        assertEquals(200, statusOf(response))
        assertTrue(response.contains("Content-Type: application/json"))
        assertTrue(response.contains("Content-Length: "))
        assertFalse(response.contains("text/event-stream"))
        assertEquals("hello", JSONObject(bodyOf(response)).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content"))
    }

    @Test
    fun `a completion that is not streamed comes back as one counted body`() = runTest {
        val endpoint = start("alpha")

        val response = chat(endpoint, """{"model":"alpha","messages":[{"role":"user","content":"hi"}]}""")

        assertEquals(200, statusOf(response))
        val json = JSONObject(bodyOf(response))
        assertEquals("chat.completion", json.getString("object"))
        assertEquals("hello", json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        val declared = response.lines().first { it.startsWith("Content-Length:") }.substringAfter(':').trim().toLong()
        assertEquals(declared, bodyOf(response).toByteArray(StandardCharsets.UTF_8).size.toLong())
    }

    @Test
    fun `a body sent in chunks is reassembled before it is read`() = runTest {
        val endpoint = start("alpha")
        val request = """{"model":"alpha","messages":[{"role":"user","content":"hi"}]}"""
        val first = request.substring(0, 20)
        val rest = request.substring(20)
        val body = first.toByteArray(StandardCharsets.UTF_8).size.toString(16) + "\r\n" + first + "\r\n" +
            rest.toByteArray(StandardCharsets.UTF_8).size.toString(16) + "\r\n" + rest + "\r\n0\r\n\r\n"

        val response = Socket(InetAddress.getByName("127.0.0.1"), endpoint.port()).use { socket ->
            socket.soTimeout = 20_000
            socket.getOutputStream().apply {
                write(
                    ("POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                        "Authorization: Bearer ${endpoint.token}\r\nTransfer-Encoding: chunked\r\n\r\n" + body)
                        .toByteArray(StandardCharsets.UTF_8)
                )
                flush()
            }
            socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }

        assertEquals(200, statusOf(response))
        assertEquals(
            "hello",
            JSONObject(bodyOf(response)).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        )
    }

    @Test
    fun `the server answers on loopback and stops when it is told to`() = runTest {
        val endpoint = start("alpha")
        assertTrue(endpoint.baseUrl.startsWith("http://127.0.0.1:"))
        assertEquals(200, statusOf(send(endpoint, "GET /v1/models HTTP/1.1")))
        // Starting again hands back the live endpoint rather than binding a second socket
        // the app would have no way to close.
        assertEquals(endpoint.baseUrl, server!!.start().baseUrl)

        server!!.stop()

        val failure = runCatching { Socket(InetAddress.getByName("127.0.0.1"), endpoint.port()).close() }.exceptionOrNull()
        assertTrue("a stopped server must refuse connections, saw: $failure", failure is ConnectException)
    }
}
