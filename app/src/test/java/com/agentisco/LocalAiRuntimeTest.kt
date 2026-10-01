package com.agentisco

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agentisco.agent.llm.LlmRequest
import com.agentisco.agent.llm.LlmRole
import com.agentisco.agent.llm.LlmFinishReason
import com.agentisco.agent.llm.LlmMessage
import com.agentisco.agent.llm.LlmService
import com.agentisco.agent.llm.LlmStreamEvent
import com.agentisco.data.local.LocalModelStore
import com.agentisco.data.repository.LocalModelRepository
import com.agentisco.local.FakeEngine
import com.agentisco.local.NoRemoteAssets
import com.agentisco.local.ServingSource
import com.agentisco.local.LocalAiRuntime
import com.agentisco.local.ggufBytes
import com.agentisco.local.modelRecord
import com.agentisco.local.repositoryWithInstalled
import com.agentisco.local.sha256Hex
import com.agentisco.local.runtime.LocalInferenceEngine
import com.agentisco.settings.model.AIModel
import com.agentisco.settings.model.LLMProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The on-device models as the provider layer sees them.
 *
 * Nothing below reads a GGUF file or runs a token: what is checked here is the seam —
 * that an installed model becomes an ordinary selectable record with an ordinary base URL
 * and key, that the port exists exactly as long as something is there to serve, and that
 * Agentisco's own protocol client can hold a conversation with it without knowing it is
 * talking to the phone it is running on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalAiRuntimeTest {

  private lateinit var context: Context
  private val payload = ggufBytes(4096)
  private var runtime: LocalAiRuntime? = null

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    File(context.filesDir, "local-models").deleteRecursively()
  }

  @After
  fun tearDown() {
    runtime?.shutdown()
    runtime = null
  }

  // ---- harness ----

  /** A runtime over [ids] installed models, answering from a scripted engine. */
  private suspend fun serving(vararg ids: String, engine: FakeEngine = FakeEngine()): LocalAiRuntime {
    val repository = repositoryWithInstalled(context, payload, *ids)
    return LocalAiRuntime(
      repository,
      LocalInferenceEngine(repository, engine, Dispatchers.Unconfined),
      dispatcher = Dispatchers.Unconfined
    ).also { runtime = it }
  }

  private suspend fun repositoryOnly(): LocalModelRepository =
    LocalModelRepository(context, LocalModelStore(context), ServingSource(payload), NoRemoteAssets, 0L)

  private suspend fun install(repository: LocalModelRepository, id: String): Boolean =
    repository.install(modelRecord(id).copy(sizeBytes = payload.size.toLong(), checksum = sha256Hex(payload)))

  @Test
  fun `an installed model is one selectable record under a loopback provider`() = runTest {
    val local = serving("lfm2")
    val endpoint = local.endpoint.value
    val provider = local.provider.value
    val model = local.models.value.single()

    assertNotNull(endpoint)
    assertEquals(LocalAiRuntime.PROVIDER_ID, provider?.id)
    assertEquals(LLMProtocol.OPENAI_CHAT_COMPLETIONS, provider?.protocol)
    // The address the agent dials is the one the server actually bound, and it is loopback.
    assertEquals(endpoint?.baseUrl, provider?.baseUrl)
    assertTrue(provider!!.baseUrl.startsWith("http://127.0.0.1:"))
    assertTrue(provider.hasApiKey)

    // A stable record id: the port belongs to this run, the model does not.
    assertEquals("local:lfm2", model.id)
    assertEquals("lfm2", model.modelId)
    assertEquals(LocalAiRuntime.PROVIDER_ID, model.providerId)
    // The limits the engine will really allocate, from the model's saved configuration.
    assertEquals(2048, model.contextWindow)
    assertEquals(200, model.maxOutputTokens)
    assertTrue(model.capabilities.tools)
    assertTrue(model.capabilities.streaming)
    // A text-only runtime must never be offered pictures.
    assertFalse(model.capabilities.images)
  }

  @Test
  fun `a device with nothing installed opens no port`() = runTest {
    val repository = repositoryOnly()
    val local = LocalAiRuntime(
      repository,
      LocalInferenceEngine(repository, FakeEngine(), Dispatchers.Unconfined),
      dispatcher = Dispatchers.Unconfined
    ).also { runtime = it }

    assertNull(local.endpoint.value)
    assertNull(local.provider.value)
    assertTrue(local.models.value.isEmpty())
  }

  @Test
  fun `a build with no inference engine offers no on-device provider`() = runTest {
    val local = serving("lfm2", engine = FakeEngine(available = false))

    assertNull(local.provider.value)
    assertNull(local.endpoint.value)
    assertTrue(local.unavailableReason!!.contains("inference engine"))
  }

  @Test
  fun `a second installed model joins the provider that is already serving`() = runTest {
    val local = serving("lfm2")
    val endpoint = local.endpoint.value

    install(local.repository, "second")

    assertEquals(listOf("local:lfm2", "local:second"), local.models.value.map { it.id })
    // One port serves every model on the device; a second install must not bind a second.
    assertEquals(endpoint, local.endpoint.value)
  }

  @Test
  fun `removing the last model closes the port and the selection list`() = runTest {
    val local = serving("lfm2")

    local.repository.remove("lfm2")

    assertNull(local.endpoint.value)
    assertNull(local.provider.value)
    assertTrue(local.models.value.isEmpty())
  }

  @Test
  fun `a local record answers with this run's address and token`() = runTest {
    val local = serving("lfm2")
    val model = local.models.value.single()

    val connection = local.connectionFor(model)
    assertEquals(local.provider.value, connection?.first)
    assertEquals(local.endpoint.value?.apiKey, connection?.second)

    // Anything that is not an on-device record is not this runtime's business.
    val cloud = AIModel("m1", "other-provider", "gpt-4o", "GPT-4o")
    assertNull(local.connectionFor(cloud))
    // A record whose bytes are gone stops resolving, so the agent reports it instead of
    // sending a request to a port with nothing behind it.
    local.repository.remove("lfm2")
    assertNull(local.connectionFor(model))
  }

  @Test
  fun `the agent's own protocol client holds a conversation with the model`() = runTest {
    val engine = FakeEngine()
    engine.sessionScript = { it.reply = listOf("The answer is ", "42.") }
    val local = serving("lfm2", engine = engine)

    val streamed = StringBuilder()
    var completed: LlmMessage? = null
    LlmService().streamChat(
      provider = local.provider.value!!,
      model = local.models.value.single(),
      apiKey = local.endpoint.value!!.apiKey,
      request = LlmRequest(messages = listOf(LlmMessage(LlmRole.USER, "what is six times seven?")))
    ) { event ->
      when (event) {
        is LlmStreamEvent.Token -> streamed.append(event.text)
        is LlmStreamEvent.Completed -> completed = event.message
        is LlmStreamEvent.Failed -> fail(event.error.message ?: "the local stream failed")
        else -> Unit
      }
    }

    assertEquals("The answer is 42.", streamed.toString())
    assertEquals("The answer is 42.", completed?.content)
    assertEquals(LlmFinishReason.STOP, completed?.finishReason)
    // The transcript reached the template in the shape the model was trained on.
    assertEquals("user: what is six times seven?", engine.sessions.single().turns.single().prompt)
  }

  @Test
  fun `the connection test a provider screen runs passes for the on-device provider`() = runTest {
    val local = serving("lfm2")

    val (ok, message) = LlmService().testConnection(
      provider = local.provider.value!!,
      model = local.models.value.single(),
      apiKey = local.endpoint.value!!.apiKey
    )

    // Stage one of that test is a free model listing — it has to come back from the phone.
    assertTrue(message, ok)
    // The name the record carries is the installed file's own: its quantization was read
    // off the bytes, not typed by anyone.
    assertEquals("Connected · Lfm2 (Q4_0)", message)
  }

  // A client that hangs up is not tested over a real socket: the write that notices is
  // timing-dependent, and LocalAiApiTest and LocalAiServerTest already pin that behaviour
  // down where it can be driven deterministically.
}
