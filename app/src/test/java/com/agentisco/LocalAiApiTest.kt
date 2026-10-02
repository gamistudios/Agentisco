package com.agentisco

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agentisco.local.FakeEngine
import com.agentisco.local.ggufBytes
import com.agentisco.local.model.LocalGenerationSettings
import com.agentisco.local.model.LocalRuntimeSettings
import com.agentisco.local.repositoryWithInstalled
import com.agentisco.local.runtime.LocalEngineException
import com.agentisco.local.runtime.LocalInferenceEngine
import com.agentisco.local.runtime.LocalParseDelta
import com.agentisco.local.runtime.LocalPromptTooLongException
import com.agentisco.local.runtime.LocalTemplateCapabilities
import com.agentisco.local.runtime.LocalToolCall
import com.agentisco.local.server.LocalAiApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The OpenAI-compatible surface, tested against the wire format it promises.
 *
 * These assert on the JSON a client actually receives — chunk order, `finish_reason`,
 * which fields appear when a turn ends on the model's own marker — because that format is
 * the whole contract: get it wrong and a local model becomes a broken provider that looks
 * like a network fault.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalAiApiTest {

    private lateinit var context: Context
    private val payload = ggufBytes(4096)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "local-models").deleteRecursively()
    }

    // ---- harness ----

    private suspend fun apiFor(vararg ids: String): Pair<LocalAiApi, FakeEngine> {
        val repository = repositoryWithInstalled(context, payload, *ids)
        val fake = FakeEngine()
        return LocalAiApi(LocalInferenceEngine(repository, fake, Dispatchers.Unconfined), repository) to fake
    }

    private suspend fun call(
        api: LocalAiApi,
        method: String,
        path: String,
        body: String = "",
        chunks: MutableList<String> = mutableListOf()
    ): LocalAiApi.Reply = api.handle(method, path, body) { chunks += it; true }

    private fun bodyOf(reply: LocalAiApi.Reply): JSONObject = JSONObject((reply as LocalAiApi.Reply.Body).json)

    /** What a chunk carries to a client: a role, a piece of the answer, or nothing but the end. */
    private fun JSONObject.chunkText(): String? {
        val delta = getJSONArray("choices").getJSONObject(0).optJSONObject("delta") ?: return null
        return delta.optString("role").ifEmpty { delta.optString("content") }.takeIf { it.isNotEmpty() }
    }

    private suspend fun complete(api: LocalAiApi, request: String): LocalAiApi.Reply =
        call(api, "POST", "/v1/chat/completions", request)

    private suspend fun statusOf(api: LocalAiApi, request: String) = complete(api, request).status

    private suspend fun errorMessageOf(api: LocalAiApi, request: String): String =
        bodyOf(complete(api, request)).getJSONObject("error").getString("message")

    private val oneTurn = """{"model":"alpha","messages":[{"role":"user","content":"hi"}]}"""

    // ---- listing ----

    @Test
    fun `installed models are listed the way a router lists them`() = runTest {
        val (api, _) = apiFor("alpha")
        val reply = call(api, "GET", "/v1/models")
        val entry = bodyOf(reply).getJSONArray("data").getJSONObject(0)

        assertEquals(200, reply.status)
        assertEquals("list", bodyOf(reply).getString("object"))
        assertEquals("alpha", entry.getString("id"))
        assertEquals("model", entry.getString("object"))
        // The limits a caller is told about are the ones the engine will actually create.
        assertEquals(LocalRuntimeSettings.DEFAULT_CONTEXT, entry.getInt("context_length"))
        assertEquals(LocalGenerationSettings.DEFAULT_MAX_TOKENS, entry.getInt("max_tokens"))
        assertTrue(entry.getJSONArray("supported_parameters").toString().contains("tools"))
    }

    @Test
    fun `a model that is not installed is not listed and not runnable`() = runTest {
        val (api, _) = apiFor("alpha")

        assertEquals(404, call(api, "GET", "/v1/models/beta").status)
        assertEquals(404, complete(api, """{"model":"beta","messages":[{"role":"user","content":"hi"}]}""").status)
        assertTrue(bodyOf(call(api, "GET", "/v1/models/beta")).getJSONObject("error").getString("message").contains("beta"))
    }

    @Test
    fun `an endpoint this server does not have says so`() = runTest {
        val (api, _) = apiFor("alpha")
        val reply = call(api, "GET", "/v1/completions")

        assertEquals(404, reply.status)
        assertEquals("invalid_request_error", bodyOf(reply).getJSONObject("error").getString("type"))
    }

    // ---- completion ----

    @Test
    fun `a chat request renders the transcript and returns the answer`() = runTest {
        val (api, fake) = apiFor("alpha")
        val reply = complete(
            api,
            """{"model":"alpha","messages":[{"role":"system","content":"be brief"},{"role":"user","content":"hi"}]}"""
        )
        val session = fake.sessions.single()
        val json = bodyOf(reply)
        val choice = json.getJSONArray("choices").getJSONObject(0)

        assertEquals(200, reply.status)
        assertEquals("chat.completion", json.getString("object"))
        assertEquals("alpha", json.getString("model"))
        assertNotNull(json.getString("id"))
        assertEquals("hello", choice.getJSONObject("message").getString("content"))
        assertEquals("stop", choice.getString("finish_reason"))
        // The engine is handed the rendered prompt, which carries every message.
        assertEquals("system: be brief\nuser: hi", session.requests.single().prompt)
        // The turn that rendered the prompt is closed as soon as the answer is in.
        assertEquals(1, session.turns.single().closes)
    }

    @Test
    fun `a request that says nothing gets the model's saved settings`() = runTest {
        val (api, fake) = apiFor("alpha")
        complete(api, oneTurn)

        val settings = fake.sessions.single().requests.single().settings
        assertEquals(LocalGenerationSettings.DEFAULT_TEMPERATURE, settings.temperature, 1e-9)
        assertEquals(LocalGenerationSettings.DEFAULT_MAX_TOKENS, settings.maxOutputTokens)
    }

    @Test
    fun `a request that says something overrides them for that request only`() = runTest {
        val (api, fake) = apiFor("alpha")
        complete(
            api,
            """{"model":"alpha","messages":[{"role":"user","content":"hi"}],""" +
                """"temperature":0.9,"max_completion_tokens":64,"top_p":0.7,"seed":12345}"""
        )

        val request = fake.sessions.single().requests.single()
        assertEquals(0.9, request.settings.temperature, 1e-9)
        assertEquals(64, request.settings.maxOutputTokens)
        assertEquals(0.7, request.settings.topP, 1e-9)
        assertEquals(12345L, request.seed)
    }

    @Test
    fun `a tool transcript reaches the template intact`() = runTest {
        val (api, fake) = apiFor("alpha")
        val request = JSONObject()
            .put("model", "alpha")
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "assistant").put("content", "")
                            .put(
                                "tool_calls",
                                JSONArray().put(
                                    JSONObject()
                                        .put("id", "call_1").put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject().put("name", "read_file")
                                                .put("arguments", """{"path":"a.txt"}""")
                                        )
                                )
                            )
                    )
                    .put(
                        JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "10 bytes")
                    )
            )
            .put(
                "tools",
                JSONArray().put(
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject().put("name", "read_file").put("description", "Read a file")
                            .put("parameters", JSONObject().put("type", "object"))
                    )
                )
            )

        complete(api, request.toString())

        val inputs = fake.sessions.single().turns.single().inputs
        val call = inputs.messages.first { it.role == "assistant" }.toolCalls.single()
        assertEquals("call_1", call.id)
        assertEquals("read_file", call.name)
        assertEquals("""{"path":"a.txt"}""", call.argumentsJson)
        assertEquals("10 bytes", inputs.messages.first { it.role == "tool" }.content)
        assertEquals("call_1", inputs.messages.first { it.role == "tool" }.toolCallId)
        assertEquals("read_file", inputs.tools.single().name)
        assertEquals("object", JSONObject(inputs.tools.single().parametersJsonSchema).getString("type"))
        assertEquals("auto", inputs.toolChoice)
    }

    /** A normal agent-loop request: one question, one function on offer. */
    private fun toolsRequest(model: String = "alpha") = JSONObject()
        .put("model", model)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "read a.txt")))
        .put(
            "tools",
            JSONArray().put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", "read_file").put("description", "Read a file")
                        .put("parameters", JSONObject().put("type", "object"))
                )
            )
        )
        .toString()

    @Test
    fun `tools offered to a template that cannot answer in them are refused, not dropped`() = runTest {
        val (api, fake) = apiFor("alpha")
        fake.capabilities = LocalTemplateCapabilities(
          available = true,
          usesOwnTemplate = true,
          supportsTools = false,
          supportsParallelToolCalls = false,
          supportsThinking = false,
          supportsSystemMessage = true,
          supportsTypedContent = false
        )

        val reply = complete(api, toolsRequest())

        // Silence here is the dangerous case: a model that was never told about the tools
        // answers as if it had, and the user sees a confident reply with no call in it.
        assertEquals(400, reply.status)
        assertTrue(bodyOf(reply).getJSONObject("error").getString("message").contains("tool-calling template"))
        // Nothing was decoded.
        assertEquals(0, fake.sessions.single().consumed)
    }

    // ---- streaming ----

    @Test
    fun `a streamed answer arrives as chunks and ends with the sentinel`() = runTest {
        val (api, _) = apiFor("alpha")
        val chunks = mutableListOf<String>()
        val reply = call(
            api, "POST", "/v1/chat/completions",
            """{"model":"alpha","stream":true,"messages":[{"role":"user","content":"hi"}]}""", chunks
        )

        assertEquals(200, reply.status)
        assertEquals(
            listOf("assistant", "hello", null, "[DONE]"),
            chunks.map { if (it == "[DONE]") it else JSONObject(it).chunkText() }
        )
        assertEquals("chat.completion.chunk", JSONObject(chunks[1]).getString("object"))
        assertEquals("stop", JSONObject(chunks[2]).getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
        // Every chunk of one answer shares its id, which is how a client stitches them.
        assertEquals(JSONObject(chunks[0]).getString("id"), JSONObject(chunks[2]).getString("id"))
    }

    @Test
    fun `a model's own end-of-turn marker never reaches the client`() = runTest {
        val (api, fake) = apiFor("alpha")
        fake.sessionScript = { session ->
            session.stopSequences = listOf("|stop|")
            // The marker arrives with text after it, and the run has more to say.
            session.reply = listOf("The answer is ", "42.", "|stop|", "text nobody asked for")
        }
        val chunks = mutableListOf<String>()
        call(
            api, "POST", "/v1/chat/completions",
            """{"model":"alpha","stream":true,"messages":[{"role":"user","content":"hi"}]}""", chunks
        )

        assertEquals(
            listOf("assistant", "The answer is ", "42.", null, "[DONE]"),
            chunks.map { if (it == "[DONE]") it else JSONObject(it).chunkText() }
        )
        assertEquals("stop", JSONObject(chunks[3]).getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
        assertEquals(3, fake.sessions.single().consumed)

        // The same rule, seen from a client that asked for the answer in one piece.
        val whole = complete(api, """{"model":"alpha","messages":[{"role":"user","content":"hi"}]}""")
        val choice = bodyOf(whole).getJSONArray("choices").getJSONObject(0)
        assertEquals("The answer is 42.", choice.getJSONObject("message").getString("content"))
        assertEquals("stop", choice.getString("finish_reason"))
    }

    @Test
    fun `a marker split across two pieces still ends the turn`() = runTest {
        val (api, fake) = apiFor("alpha")
        fake.sessionScript = { session ->
            session.stopSequences = listOf("|stop|")
            // Neither piece holds the marker whole: the tail of the first is only the
            // beginning of one, and it has to be held back until the second decides.
            session.reply = listOf("done|", "stop|ignored", "more")
        }

        val choice = bodyOf(complete(api, oneTurn)).getJSONArray("choices").getJSONObject(0)

        assertEquals("done", choice.getJSONObject("message").getString("content"))
        assertEquals("stop", choice.getString("finish_reason"))
        assertEquals(2, fake.sessions.single().consumed)
    }

    // ---- tools and thinking ----

    @Test
    fun `a tool call streams as fragments and finishes as tool_calls`() = runTest {
        val (api, fake) = apiFor("alpha")
        fake.sessionScript = { session ->
            session.stopSequences = listOf("|stop|")
            session.reply = listOf("""{"path":"a""", """","file":"b"}|stop|""")
            session.deltas = { piece -> listOf(LocalParseDelta("", "", 0, LocalToolCall("", "read_file", piece))) }
            session.calls = listOf(LocalToolCall("call_1", "read_file", """{"path":"a","file":"b"}"""))
        }
        val chunks = mutableListOf<String>()
        call(
            api, "POST", "/v1/chat/completions",
            """{"model":"alpha","stream":true,"messages":[{"role":"user","content":"hi"}]}""", chunks
        )

        val fragments = chunks.mapNotNull { chunk ->
            if (chunk == "[DONE]") return@mapNotNull null
            JSONObject(chunk).getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
                .optJSONArray("tool_calls")?.getJSONObject(0)
        }
        assertEquals(2, fragments.size)
        assertEquals("read_file", fragments.first().getJSONObject("function").getString("name"))
        assertEquals("""{"path":"a""", fragments.first().getJSONObject("function").getString("arguments"))
        assertEquals(0, fragments.first().getInt("index"))
        val finish = JSONObject(chunks[chunks.size - 2]).getJSONArray("choices").getJSONObject(0)
        assertEquals("tool_calls", finish.getString("finish_reason"))

        val message = bodyOf(complete(api, oneTurn)).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val assembled = message.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_1", assembled.getString("id"))
        assertEquals("function", assembled.getString("type"))
        assertEquals("""{"path":"a","file":"b"}""", assembled.getJSONObject("function").getString("arguments"))
    }

    @Test
    fun `thinking streams in its own field instead of the answer`() = runTest {
        val (api, fake) = apiFor("alpha")
        fake.sessionScript = { session ->
            // No marker to watch for: this test is about which field thinking lands in,
            // and a held-back tail would move letters between chunks.
            session.stopSequences = emptyList()
            session.deltas = { piece -> listOf(LocalParseDelta("", "weighing $piece", -1, null)) }
            session.reply = listOf("twice", "three times")
        }
        val chunks = mutableListOf<String>()
        call(
            api, "POST", "/v1/chat/completions",
            """{"model":"alpha","stream":true,"messages":[{"role":"user","content":"hi"}]}""", chunks
        )

        val reasoning = chunks.filter { it != "[DONE]" }
            .map { JSONObject(it).getJSONArray("choices").getJSONObject(0).getJSONObject("delta").optString("reasoning_content") }
        assertEquals(listOf("", "weighing twice", "weighing three times", ""), reasoning)
        assertTrue(chunks.none { it.contains("\"content\":") })
    }

    @Test
    fun `a caller that asks for no thinking is passed to the template`() = runTest {
        val (api, fake) = apiFor("alpha")
        complete(api, """{"model":"alpha","messages":[{"role":"user","content":"hi"}],"enable_thinking":false}""")
        assertFalse(fake.sessions.single().turns.single().inputs.enableThinking)

        complete(api, """{"model":"alpha","messages":[{"role":"user","content":"hi"}],"reasoning_effort":"none"}""")
        assertFalse(fake.sessions.single().turns.last().inputs.enableThinking)
    }

    // ---- a client that goes away, and an engine that fails ----

    @Test
    fun `a client that hung up stops the decode instead of finishing the answer`() = runTest {
        val (api, fake) = apiFor("alpha")
        fake.sessionScript = { session -> session.reply = listOf("one ", "two ", "three ") }
        val written = mutableListOf<String>()
        val reply = api.handle(
            "POST", "/v1/chat/completions",
            """{"model":"alpha","stream":true,"messages":[{"role":"user","content":"hi"}]}"""
        ) { written += it; false } // every write fails: the socket closed before the first chunk

        assertEquals(200, reply.status)
        assertTrue(reply is LocalAiApi.Reply.Streamed)
        assertEquals(1, fake.sessions.single().consumed)
    }

    @Test
    fun `a failure mid-stream is reported inside the stream`() = runTest {
        val (api, fake) = apiFor("alpha")
        fake.sessionScript = { session ->
            session.reply = listOf("part of ", "an answer")
            // Text already reached the client, so the stream is the only place left to
            // say the rest is not coming.
            session.failsAfter = 1
            session.failure = LocalEngineException("Failed to evaluate generated token")
        }
        val chunks = mutableListOf<String>()
        val reply = call(
            api, "POST", "/v1/chat/completions",
            """{"model":"alpha","stream":true,"messages":[{"role":"user","content":"hi"}]}""", chunks
        )

        assertEquals(200, reply.status)
        assertEquals(4, chunks.size)
        assertEquals(listOf("assistant", "part of "), chunks.take(2).map { JSONObject(it).chunkText() })
        assertEquals(
            "Failed to evaluate generated token",
            JSONObject(chunks[2]).getJSONObject("error").getString("message")
        )
        assertEquals("[DONE]", chunks.last())
    }

    @Test
    fun `a failure before the first chunk is an ordinary status code`() = runTest {
        val (api, fake) = apiFor("alpha")
        fake.sessionScript = { session -> session.failure = LocalEngineException("Failed to evaluate generated token") }

        // Streamed or not: nothing had been promised to the client, so the answer is a
        // status code rather than a half-finished event stream.
        val chunks = mutableListOf<String>()
        val streamed = call(
            api, "POST", "/v1/chat/completions",
            """{"model":"alpha","stream":true,"messages":[{"role":"user","content":"hi"}]}""", chunks
        )
        assertEquals(500, streamed.status)
        assertTrue(chunks.isEmpty())

        val reply = complete(api, oneTurn)

        assertEquals(500, reply.status)
        assertEquals("server_error", bodyOf(reply).getJSONObject("error").getString("type"))
        assertEquals("Failed to evaluate generated token", bodyOf(reply).getJSONObject("error").getString("message"))
    }

    /**
     * The stalled-turn case: an agent prompt — playbook, tool list, transcript — longer
     * than the window the model was opened with. It has to come back as a request error
     * the loop will not retry, and the model that refused it has to stay loaded, because
     * nothing about the engine is wrong.
     */
    @Test
    fun `a prompt that does not fit the context is refused as a request error`() = runTest {
        val (api, fake) = apiFor("alpha")
        fake.sessionScript = { session ->
            session.failure = LocalPromptTooLongException("This request needs 2900 tokens but the context for this model holds 2048.")
        }
        val chunks = mutableListOf<String>()

        val reply = call(
            api, "POST", "/v1/chat/completions",
            """{"model":"alpha","stream":true,"messages":[{"role":"user","content":"hi"}]}""", chunks
        )

        assertEquals(400, reply.status)
        val error = bodyOf(reply).getJSONObject("error")
        assertEquals("invalid_request_error", error.getString("type"))
        assertTrue(error.getString("message").contains("2900"))
        assertTrue(chunks.isEmpty())
        assertEquals(0, fake.sessions.single().closes)
    }

    @Test
    fun `an answer with nothing in it still opens and closes its stream`() = runTest {
        val (api, fake) = apiFor("alpha")
        fake.sessionScript = { session -> session.reply = emptyList() }
        val chunks = mutableListOf<String>()

        call(
            api, "POST", "/v1/chat/completions",
            """{"model":"alpha","stream":true,"messages":[{"role":"user","content":"hi"}]}""", chunks
        )

        assertEquals(listOf("assistant", null, "[DONE]"), chunks.map { if (it == "[DONE]") it else JSONObject(it).chunkText() })
    }

    @Test
    fun `a model whose template cannot render chat is refused as a retryable fault`() = runTest {
        val (api, fake) = apiFor("alpha")
        fake.capabilities = LocalTemplateCapabilities(
            available = false,
            usesOwnTemplate = false,
            supportsTools = false,
            supportsParallelToolCalls = false,
            supportsThinking = false,
            supportsSystemMessage = false,
            supportsTypedContent = false,
            reason = "The model ships no usable chat template"
        )

        val reply = complete(api, oneTurn)

        assertEquals(503, reply.status)
        assertTrue(bodyOf(reply).getJSONObject("error").getString("message").contains("chat template"))
    }

    // ---- requests this server will not take ----

    @Test
    fun `a request that does not fit the format is rejected, not answered`() = runTest {
        val (api, fake) = apiFor("alpha")

        assertEquals(400, statusOf(api, "this is not json"))
        assertEquals(400, statusOf(api, """{"messages":[{"role":"user","content":"hi"}]}"""))
        assertEquals(400, statusOf(api, """{"model":"alpha","messages":[]}"""))
        assertEquals(400, statusOf(api, """{"model":"alpha","messages":[{"content":"no role"}]}"""))
        assertEquals(400, statusOf(api, """{"model":"alpha","messages":[{"role":"developer","content":"hi"}]}"""))
        assertTrue(
            errorMessageOf(api, """{"model":"alpha","messages":[{"role":"developer","content":"hi"}]}""")
                .contains("developer")
        )
        // Nothing above this line may reach the engine: a refused request costs no battery.
        assertTrue(fake.sessions.isEmpty())
    }

    @Test
    fun `a picture in the transcript is refused instead of answered from text alone`() = runTest {
        val (api, fake) = apiFor("alpha")

        val reply = complete(
            api,
            """{"model":"alpha","messages":[{"role":"user","content":[{"type":"text","text":"what is this"},""" +
                """{"type":"image_url","image_url":{"url":"https://example.test/a.png"}}]}]}"""
        )

        assertEquals(400, reply.status)
        assertTrue(bodyOf(reply).getJSONObject("error").getString("message").contains("text-only"))
        assertTrue(fake.sessions.isEmpty())
    }

    @Test
    fun `a call forced to one named function is refused rather than ignored`() = runTest {
        val (api, fake) = apiFor("alpha")

        val reply = complete(
            api,
            """{"model":"alpha","messages":[{"role":"user","content":"hi"}],""" +
                """"tool_choice":{"type":"function","function":{"name":"read_file"}}}"""
        )

        assertEquals(400, reply.status)
        assertTrue(bodyOf(reply).getJSONObject("error").getString("message").contains("forced"))
        assertTrue(fake.sessions.isEmpty())
    }

    @Test
    fun `text parts of a content list are joined, not dropped`() = runTest {
        val (api, fake) = apiFor("alpha")
        complete(
            api,
            """{"model":"alpha","messages":[{"role":"user","content":[{"type":"text","text":"one "},""" +
                """{"type":"text","text":"two"}]}]}"""
        )

        assertEquals("user: one two", fake.sessions.single().requests.single().prompt)
    }
}


