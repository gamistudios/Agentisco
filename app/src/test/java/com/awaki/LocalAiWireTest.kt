package com.awaki

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.awaki.agent.llm.LlmErrorKind
import com.awaki.agent.llm.LlmException
import com.awaki.agent.llm.LlmFinishReason
import com.awaki.agent.llm.LlmMessage
import com.awaki.agent.llm.LlmRequest
import com.awaki.agent.llm.LlmRole
import com.awaki.agent.llm.LlmService
import com.awaki.agent.llm.LlmStreamEvent
import com.awaki.agent.llm.LlmToolSpec
import com.awaki.local.FakeEngine
import com.awaki.local.ggufBytes
import com.awaki.local.repositoryWithInstalled
import com.awaki.local.runtime.LocalEngineException
import com.awaki.local.runtime.LocalInferenceEngine
import com.awaki.local.runtime.LocalAnswerDelta
import com.awaki.local.runtime.LocalPromptTooLongException
import com.awaki.local.runtime.LocalToolCall
import com.awaki.local.server.LocalAiApi
import com.awaki.local.server.LocalAiServer
import com.awaki.settings.model.AIModel
import com.awaki.settings.model.AIProvider
import com.awaki.settings.model.LLMProtocol
import com.awaki.settings.model.ModelCapabilities
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The loopback server driven by the client the agent actually uses.
 *
 * [com.awaki.LocalAiServerTest] reads raw bytes off a socket, which is the right way
 * to see framing, and the wrong way to prove the app can read it: an HTTP client that
 * waits for a `Content-Length` that never comes, or a stream that never terminates, looks
 * correct to a test that only counts bytes. So this goes through [LlmService] and OkHttp —
 * the same two objects that sit between the agent and a cloud provider — against a real
 * socket on 127.0.0.1.
 *
 * A hung request is the failure these tests exist to catch, so the client carries a real
 * call timeout: a stall becomes a failed test in seconds rather than a suite that never
 * finishes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalAiWireTest {

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

  /** Starts the real server on a real port and hands back what a caller needs to reach it. */
  private class Trio(
    val provider: AIProvider,
    val model: AIModel,
    val apiKey: String,
    val engine: FakeEngine
  )

  private fun serve(script: FakeEngine.() -> Unit = {}): Trio {
    val repository = runBlocking { repositoryWithInstalled(context, payload, "alpha") }
    val fake = FakeEngine().apply(script)
    val engine = LocalInferenceEngine(repository, fake, Dispatchers.Unconfined)
    val endpoint = LocalAiServer(LocalAiApi(engine, repository)).also { server = it }.start()
    val provider = AIProvider(
      id = "local-ai",
      name = "On-device",
      baseUrl = endpoint.baseUrl,
      protocol = LLMProtocol.OPENAI_CHAT_COMPLETIONS,
      hasApiKey = true
    )
    val model = AIModel(
      id = "local:alpha",
      providerId = "local-ai",
      modelId = "alpha",
      displayName = "Alpha",
      capabilities = ModelCapabilities(tools = true, streaming = true)
    )
    return Trio(provider, model, endpoint.apiKey, fake)
  }

  private fun client(): LlmService = LlmService(
    OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
      .callTimeout(30, TimeUnit.SECONDS).build()
  )

  private fun userTurn(text: String) = LlmRequest(messages = listOf(LlmMessage(LlmRole.USER, text)))

  /**
   * One round trip. A provider that refuses a request answers with a status code, and
   * [LlmService] throws that as [LlmException] instead of reporting it as a stream event —
   * which is what the agent loop catches, so a test has to hold on to it too.
   */
  private class Exchange(val events: List<LlmStreamEvent>, val error: LlmException?)

  private fun ask(trio: Trio, request: LlmRequest): Exchange {
    val events = mutableListOf<LlmStreamEvent>()
    val error = runBlocking {
      runCatching { client().streamChat(trio.provider, trio.model, trio.apiKey, request) { events += it } }
        .exceptionOrNull()
    }
    assertTrue("only an LlmException may escape the client: $error", error == null || error is LlmException)
    return Exchange(events, error as LlmException?)
  }

  // ---- the path an agent turn takes ----

  @Test
  fun `a local answer arrives as streamed text the way a cloud answer does`() {
    val trio = serve {
      sessionScript = { session ->
        session.reply = listOf("Sure", " — no", " problem.")
      }
    }

    val events = ask(trio, userTurn("Hello")).events

    assertTrue("the stream must start: $events", events.first() is LlmStreamEvent.Started)
    assertEquals(
      listOf("Sure", " — no", " problem."),
      events.filterIsInstance<LlmStreamEvent.Token>().map { it.text }
    )
    val completed = events.filterIsInstance<LlmStreamEvent.Completed>().single()
    assertEquals("Sure — no problem.", completed.message.content)
    assertEquals(LlmRole.ASSISTANT, completed.message.role)
  }

  /**
   * What a caller gets for a local tool call is the object it gets for a cloud one: the
   * arguments assembled, the reason set, and no prose where the call should be. The
   * fragments in between are a Chat Completions detail this client assembles rather than
   * reports, so a streamed call event is not part of the contract being checked here.
   */
  @Test
  fun `a local tool call arrives as a call with its arguments, not as prose`() {
    val arguments = """{"path":"app/build.gradle.kts"}"""
    val trio = serve {
      sessionScript = { session ->
        session.reply = listOf(arguments)
        // The runtime already read the answer as a call: name, id and arguments come across
        // the seam separated, so no layer above has to guess from prose.
        session.deltas = { piece ->
          listOf(LocalAnswerDelta(toolCallIndex = 0, toolCall = LocalToolCall("call_1", "read_file", piece)))
        }
      }
    }

    val events = ask(
      trio,
      userTurn("Read the build file").copy(
        tools = listOf(LlmToolSpec("read_file", "Read one file.", """{"type":"object","properties":{"path":{"type":"string"}}}"""))
      )
    ).events

    val completed = events.filterIsInstance<LlmStreamEvent.Completed>().single()
    assertEquals(listOf("read_file"), completed.message.toolCalls.map { it.name })
    assertEquals(arguments, completed.message.toolCalls.single().argumentsJson)
    assertEquals(LlmFinishReason.TOOL_CALLS, completed.message.finishReason)
    assertTrue("the call must not arrive as text: $events", events.filterIsInstance<LlmStreamEvent.Token>().isEmpty())
  }

  @Test
  fun `the agent's whole tool set and playbook survive the round trip`() {
    val playbook = "Rules:\n" + "read before you edit\n".repeat(200)
    val tools = (1..24).map { index ->
      LlmToolSpec("tool_$index", "Description number $index.", """{"type":"object","properties":{"a$index":{"type":"string"}}}""")
    }
    val trio = serve { sessionScript = { it.reply = listOf("done") } }

    val events = ask(
      trio,
      LlmRequest(
        messages = listOf(LlmMessage(LlmRole.SYSTEM, playbook), LlmMessage(LlmRole.USER, "Do the work")),
        tools = tools
      )
    ).events

    assertEquals("done", events.filterIsInstance<LlmStreamEvent.Token>().joinToString("") { it.text })
    // What the model was actually offered, read back off the request the server received.
    val inputs = trio.engine.sessions.single().requests.single().inputs
    assertEquals(24, inputs.tools.size)
    assertEquals(2, inputs.messages.size)
    assertEquals(playbook, inputs.messages.first().content)
  }

  @Test
  fun `a model that produces no text ends the stream instead of hanging it`() {
    val trio = serve { sessionScript = { it.reply = emptyList() } }

    val events = ask(trio, userTurn("Hello")).events

    // Nothing was said, so nothing may pretend to have been said: the run finishes and the
    // caller gets an empty answer rather than a stream waiting for text that never comes.
    // Turning an empty answer into something the user can read is the layer above's job.
    val completed = events.filterIsInstance<LlmStreamEvent.Completed>().single()
    assertEquals("", completed.message.content)
    assertTrue(events.filterIsInstance<LlmStreamEvent.Token>().isEmpty())
  }

  @Test
  fun `an engine that cannot answer is a retryable server fault`() {
    val trio = serve { sessionScript = { it.failure = LocalEngineException("Not enough memory to load this model") } }

    val error = ask(trio, userTurn("Hello")).error

    assertEquals(LlmErrorKind.SERVER, error?.kind)
    assertTrue("the engine's reason must reach the caller: ${error?.message}", error?.message?.contains("Not enough memory") == true)
  }

  /**
   * The stall these tests were written for. An agent prompt — playbook and the whole list
   * of tools, rendered before the user's message — longer than the window the model was
   * opened with used to come back as a retryable server error, so the loop retried into the
   * same wall while the screen said "Contacting On-device".
   */
  @Test
  fun `a prompt too long for the context is a terminal error naming both numbers`() {
    val trio = serve {
      sessionScript = {
        it.failure = LocalPromptTooLongException(
          "This request needs 2900 tokens but the context for this model holds 2048. Raise the context size, or shorten the conversation."
        )
      }
    }

    val error = ask(trio, userTurn("Do the work")).error

    // Not SERVER: retrying the same prompt fails the same way, and the loop has to know that.
    assertEquals(LlmErrorKind.INVALID_RESPONSE, error?.kind)
    assertTrue(error!!.message!!.contains("2900"))
    assertTrue(error.message!!.contains("2048"))
  }
}
