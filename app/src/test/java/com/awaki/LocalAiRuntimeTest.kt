package com.awaki

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.awaki.agent.llm.LlmRequest
import com.awaki.agent.llm.LlmRole
import com.awaki.agent.llm.LlmFinishReason
import com.awaki.agent.llm.LlmMessage
import com.awaki.agent.llm.LlmService
import com.awaki.agent.llm.LlmStreamEvent
import com.awaki.data.local.LocalModelStore
import com.awaki.data.repository.LocalModelRepository
import com.awaki.local.FakeEngine
import com.awaki.local.NoRemoteAssets
import com.awaki.local.ServingSource
import com.awaki.local.LocalAiRuntime
import com.awaki.local.ggufBytes
import com.awaki.local.modelRecord
import com.awaki.local.repositoryWithInstalled
import com.awaki.local.sha256Hex
import com.awaki.local.model.LocalRuntimeSettings
import com.awaki.local.runtime.LocalInferenceEngine
import com.awaki.local.runtime.LocalTemplateCapabilities
import com.awaki.settings.model.AIModel
import com.awaki.settings.model.LLMProtocol
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
 * Awaki's own protocol client can hold a conversation with it without knowing it is
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
    assertEquals(LocalRuntimeSettings.DEFAULT_CONTEXT, model.contextWindow)
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
  fun `a device without the model runtime offers no on-device provider`() = runTest {
    val local = serving("lfm2", engine = FakeEngine(available = false))

    assertNull(local.provider.value)
    assertNull(local.endpoint.value)
    assertTrue(local.unavailableReason!!.contains("model runtime"))
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
    // The transcript reaches the runtime as messages: rendering them into the shape this model
    // was trained on is the engine's job, next door to the file that says what that is.
    val inputs = engine.sessions.single().requests.single().inputs
    assertEquals(listOf("user: what is six times seven?"), inputs.messages.map { "${it.role}: ${it.content}" })
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

  /**
   * Whether a model's template can carry tools is only read from the file once it is in memory,
   * and an unloaded model is listed as tool-capable rather than costing a load to find out. So
   * the record has to change when residency does: a run decides whether to offer tools from
   * [AIModel.capabilities], and a flag frozen at install time is a run that offers tools to a
   * template that cannot render them.
   */
  @Test
  fun `a loaded model tells the record what its template can really do`() = runTest {
    val local = serving("lfm2", engine = FakeEngine().apply {
      capabilities = LocalTemplateCapabilities(
        available = true,
        usesOwnTemplate = true,
        supportsTools = false,
        supportsParallelToolCalls = false,
        supportsThinking = false,
        supportsSystemMessage = true
      )
    })

    assertTrue(local.models.value.single().capabilities.tools)

    local.loadModel("lfm2")
    assertFalse(local.models.value.single().capabilities.tools)

    // Unloaded, the file is unknown again — and an unknown template is listed as capable, which
    // is the honest answer until a turn or a Load reads it.
    local.releaseModel()
    assertTrue(local.models.value.single().capabilities.tools)
  }

  // A client that hangs up is not tested over a real socket: the write that notices is
  // timing-dependent, and LocalAiApiTest and LocalAiServerTest already pin that behaviour
  // down where it can be driven deterministically.
}
