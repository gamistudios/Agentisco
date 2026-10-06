package com.awaki

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.awaki.agent.llm.LlmService
import com.awaki.agent.llm.ModelListing
import com.awaki.data.local.ProviderConfigStore
import com.awaki.data.repository.WorkspaceRepository
import com.awaki.local.FakeEngine
import com.awaki.local.LocalAiRuntime
import com.awaki.local.ggufBytes
import com.awaki.local.repositoryWithInstalled
import com.awaki.local.runtime.LocalInferenceEngine
import com.awaki.settings.model.AIProvider
import com.awaki.settings.model.LLMProtocol
import com.awaki.settings.model.ModelCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The provider list the user and the agent read, with an on-device model in it.
 *
 * The claim being tested is the one the whole design rests on: nothing here knows a local
 * model exists. The stored providers and the derived ones merge into one list, a selection
 * pointing at a derived record survives a restart even though the store cannot see it, and
 * the connection test dials a base URL and a key like any other provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalProviderIntegrationTest {

  private lateinit var context: Context
  private val payload = ggufBytes(4096)
  private var runtime: LocalAiRuntime? = null

  /** Records what the repository hands the protocol client instead of calling a provider. */
  private class RecordingLlm : LlmService() {
    var listing: Pair<AIProvider, String>? = null

    override suspend fun listModels(provider: AIProvider, apiKey: String): ModelListing {
      listing = provider to apiKey
      return ModelListing(true, "Connected")
    }
  }

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    context = ApplicationProvider.getApplicationContext()
    File(context.filesDir, "local-models").deleteRecursively()
    File(context.getDir("awaki", Context.MODE_PRIVATE), "providers.json").delete()
    File(context.getDir("awaki", Context.MODE_PRIVATE), "credentials.json").delete()
  }

  @After
  fun tearDown() {
    held.closeAll()
    runtime?.shutdown()
    runtime = null
    Dispatchers.resetMain()
  }

  private val held = HeldWork()


  // ---- harness ----

  private suspend fun localRuntime(vararg ids: String): LocalAiRuntime {
    runtime?.shutdown()
    val repository = repositoryWithInstalled(context, payload, *ids)
    return LocalAiRuntime(
      repository,
      LocalInferenceEngine(repository, FakeEngine(), Dispatchers.Unconfined),
      dispatcher = Dispatchers.Unconfined
    ).also { runtime = it }
  }

  private fun storedStore() = ProviderConfigStore().apply {
    upsertProvider(
      AIProvider("cloud", "Cloud AI", "https://cloud.test/v1", LLMProtocol.OPENAI_CHAT_COMPLETIONS, hasApiKey = true),
      "sk-cloud-key"
    )
    upsertModel(
      com.awaki.settings.model.AIModel(
        "cloud-model", "cloud", "big-model", "Big Model",
        contextWindow = 128_000, capabilities = ModelCapabilities(tools = true)
      )
    )
    selectModel("cloud-model")
  }

  @Test
  fun `stored providers and on-device models arrive as one selectable list`() = runTest {
    val repo = held.hold(WorkspaceRepository(
      context = null,
      providerStore = storedStore(),
      llmService = RecordingLlm(),
      localAi = localRuntime("lfm2")
    ))

    assertEquals(listOf("cloud", LocalAiRuntime.PROVIDER_ID), repo.providers.value.map { it.id })
    assertEquals(listOf("cloud-model", "local:lfm2"), repo.aiModels.value.map { it.id })
    // The stored selection is still the selection: a local model never steals it.
    assertEquals("cloud-model", repo.selectedModel.value?.id)
  }

  @Test
  fun `the on-device provider is asked for its models with this run's address and token`() = runTest {
    val local = localRuntime("lfm2")
    val llm = RecordingLlm()
    val repo = held.hold(WorkspaceRepository(context = null, providerStore = storedStore(), llmService = llm, localAi = local))

    // The catalog gate refuses to ask a provider that has no key; passing it with a
    // derived token is what makes the on-device provider an ordinary one.
    repo.loadModelCatalog(LocalAiRuntime.PROVIDER_ID)

    val listing = requireNotNull(llm.listing) { "the repository never asked this provider for its model list" }
    assertEquals(local.endpoint.value?.baseUrl, listing.first.baseUrl)
    assertEquals(local.endpoint.value?.apiKey, listing.second)
    assertEquals(LLMProtocol.OPENAI_CHAT_COMPLETIONS, listing.first.protocol)
    assertTrue(
      repo.modelCatalogs.value[LocalAiRuntime.PROVIDER_ID] is WorkspaceRepository.ModelCatalogState.Available
    )
  }

  @Test
  fun `an on-device model is not editable through the cloud provider forms`() = runTest {
    val local = localRuntime("lfm2")
    val repo = held.hold(WorkspaceRepository(context = null, providerStore = storedStore(), llmService = RecordingLlm(), localAi = local))

    assertNull(repo.saveModel(LocalAiRuntime.PROVIDER_ID, "extra", "Extra", 1024, 64, ModelCapabilities(), null))
    repo.deleteProvider(LocalAiRuntime.PROVIDER_ID)
    repo.deleteModel("local:lfm2")

    // Still exactly one on-device provider and one on-device record.
    assertEquals(1, repo.providers.value.count { it.id == LocalAiRuntime.PROVIDER_ID })
    assertEquals(listOf("local:lfm2"), repo.aiModels.value.filter { it.providerId == LocalAiRuntime.PROVIDER_ID }.map { it.id })
    // Nothing was written to the durable store that would outlive this run's port.
    assertTrue(repo.providerStore?.getModels()?.none { it.id.startsWith("local:") } == true)
  }

  @Test
  fun `a selected on-device model is still selected after a restart`() = runTest {
    val store = ProviderConfigStore(context)
    val first = held.hold(WorkspaceRepository(
      context = null,
      providerStore = store,
      llmService = RecordingLlm(),
      localAi = localRuntime("lfm2")
    ))
    first.selectModel("local:lfm2")
    assertEquals("local:lfm2", first.selectedModel.value?.id)
    assertEquals("local:lfm2", store.getSelectedModelId())

    // A new process, so a new server run: the record id is the model's own, which is what
    // lets a selection made against one port be answered by the next one.
    val restartedRuntime = localRuntime("lfm2")
    val restarted = held.hold(WorkspaceRepository(
      context = null,
      providerStore = ProviderConfigStore(context),
      llmService = RecordingLlm(),
      localAi = restartedRuntime
    ))
    assertEquals("local:lfm2", restarted.selectedModel.value?.id)
    // The record points at the server that is live now, not at whatever port was remembered.
    assertEquals(
      restartedRuntime.endpoint.value?.baseUrl,
      restarted.providers.value.first { it.id == LocalAiRuntime.PROVIDER_ID }.baseUrl
    )
  }

  @Test
  fun `a session that settles for a running model does not rewrite the remembered choice`() = runTest {
    val store = storedStore().apply { selectModel("local:lfm2") { true } }

    // Nothing installed and no server yet: what the app looks like for the first
    // seconds of a cold start, when the only selectable record is the cloud one. The
    // session may run on that, but paying for it with the user's stored choice is how
    // an on-device selection used to come back as a cloud model after a restart.
    val repo = held.hold(WorkspaceRepository(
      context = null,
      providerStore = store,
      llmService = RecordingLlm(),
      localAi = localRuntime()
    ))

    assertEquals("cloud-model", repo.selectedModel.value?.id)
    assertEquals("local:lfm2", store.getSelectedModelId())
  }

  @Test
  fun `deleting the last cloud provider leaves the on-device model selectable`() = runTest {
    val repo = held.hold(WorkspaceRepository(
      context = null,
      providerStore = storedStore(),
      llmService = RecordingLlm(),
      localAi = localRuntime("lfm2")
    ))

    repo.deleteProvider("cloud")

    assertEquals(listOf(LocalAiRuntime.PROVIDER_ID), repo.providers.value.map { it.id })
    assertEquals("local:lfm2", repo.selectedModel.value?.id)
  }

  @Test
  fun `with nothing installed the list is exactly what the store holds`() = runTest {
    val repo = held.hold(WorkspaceRepository(
      context = null,
      providerStore = storedStore(),
      llmService = RecordingLlm(),
      localAi = localRuntime()
    ))

    assertEquals(listOf("cloud"), repo.providers.value.map { it.id })
    assertEquals(listOf("cloud-model"), repo.aiModels.value.map { it.id })
  }
}
