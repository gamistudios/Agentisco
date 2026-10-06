package com.awaki

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.awaki.agent.llm.LlmService
import com.awaki.agent.llm.LlmMessage
import com.awaki.agent.llm.LlmRole
import com.awaki.agent.llm.LlmRequest
import com.awaki.agent.llm.LlmStreamEvent
import com.awaki.data.local.ProviderConfigStore
import com.awaki.data.repository.WorkspaceRepository
import com.awaki.local.FakeEngine
import com.awaki.local.LocalAiRuntime
import com.awaki.local.ggufBytes
import com.awaki.local.repositoryWithInstalled
import com.awaki.local.runtime.LocalInferenceEngine
import com.awaki.settings.model.AIModel
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Which model a background chore runs on.
 *
 * A session title, a commit message and a diff explanation are generated while the user
 * waits for something else. When the only model configured is one that runs on the phone's
 * CPU, that is the model they would land on — and a 12k-character diff is minutes of work
 * there. The claim tested here is that they never run on it: a chore is handed to another
 * cloud model if one exists, and refused with a reason if none does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalTaskModelTest {

  private lateinit var context: Context
  private var runtime: LocalAiRuntime? = null

  /** Records every request the repository sends and answers with one token. */
  private class RecordingLlm : LlmService() {
    val requests = mutableListOf<Triple<AIProvider, AIModel, LlmRequest>>()

    override suspend fun streamChat(
      provider: AIProvider,
      model: AIModel,
      apiKey: String,
      request: LlmRequest,
      onEvent: (LlmStreamEvent) -> Unit
    ) {
      requests += Triple(provider, model, request)
      onEvent(LlmStreamEvent.Token("Title"))
      onEvent(LlmStreamEvent.Completed(LlmMessage(LlmRole.ASSISTANT, "Title")))
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
    val repository = repositoryWithInstalled(context, ggufBytes(4096), *ids)
    return LocalAiRuntime(
      repository,
      LocalInferenceEngine(repository, FakeEngine(), Dispatchers.Unconfined),
      dispatcher = Dispatchers.Unconfined
    ).also { runtime = it }
  }

  private fun cloudProvider() = AIProvider(
    id = "cloud",
    name = "Cloud AI",
    baseUrl = "https://cloud.test/v1",
    protocol = LLMProtocol.OPENAI_CHAT_COMPLETIONS,
    hasApiKey = true
  )

  private fun cloudModel(id: String, providerId: String = "cloud") = AIModel(
    id = id,
    providerId = providerId,
    modelId = id,
    displayName = id,
    contextWindow = 128_000,
    capabilities = ModelCapabilities(tools = true)
  )

  private suspend fun repoWith(vararg cloud: AIModel, apiKey: String = "sk-cloud-key"): Pair<WorkspaceRepository, RecordingLlm> {
    val store = ProviderConfigStore().apply {
      upsertProvider(cloudProvider(), apiKey.takeIf { apiKey.isNotEmpty() })
      cloud.forEach { upsertModel(it) }
      cloud.firstOrNull()?.let { selectModel(it.id) }
    }
    val llm = RecordingLlm()
    val repo = held.hold(WorkspaceRepository(
      context = null,
      providerStore = store,
      llmService = llm,
      localAi = localRuntime("lfm2")
    ))
    return repo to llm
  }

  // ---- tests ----

  /** The chore whose cost is being argued about: a diff explanation. */
  private suspend fun WorkspaceRepository.explain() =
    explainChangesWithAgent("diff --git a/src/App.tsx b/src/App.tsx\n+export const value = 2\n")

  @Test
  fun `a background chore runs on the cloud model, never the on-device one`() = runTest {
    val (repo, llm) = repoWith(cloudModel("cloud-model"))
    repo.selectModel("local:lfm2")

    assertEquals("Title", repo.explain())

    assertEquals(listOf("cloud-model"), llm.requests.map { it.second.id })
  }

  @Test
  fun `a stored task model that runs on the device is skipped for one that does not`() = runTest {
    val (repo, llm) = repoWith(cloudModel("cloud-model"))
    repo.setDefaultTaskModel("local:lfm2")
    repo.selectModel("local:lfm2")

    assertEquals("Title", repo.explain())

    assertEquals(listOf("cloud-model"), llm.requests.map { it.second.id })
  }

  @Test
  fun `the first model whose provider has a key answers when the selected one does not`() = runTest {
    val store = ProviderConfigStore().apply {
      // "cloud" is configured but never given a key, so its model can be selected and
      // still cannot carry a chore.
      upsertProvider(cloudProvider(), null)
      upsertModel(cloudModel("cloud-model"))
      upsertProvider(AIProvider("backup", "Backup AI", "https://backup.test/v1", LLMProtocol.OPENAI_CHAT_COMPLETIONS, hasApiKey = true), "sk-backup")
      upsertModel(cloudModel("backup-model", "backup"))
      selectModel("cloud-model")
    }
    val llm = RecordingLlm()
    val repo = held.hold(WorkspaceRepository(
      context = null,
      providerStore = store,
      llmService = llm,
      localAi = localRuntime("lfm2")
    ))

    assertEquals("Title", repo.explain())

    assertEquals(listOf("backup-model"), llm.requests.map { it.second.id })
  }

  @Test
  fun `a chore is refused when the only model is on-device`() = runTest {
    val store = ProviderConfigStore().apply { upsertProvider(cloudProvider(), "sk-cloud-key") }
    val llm = RecordingLlm()
    val repo = held.hold(WorkspaceRepository(
      context = null,
      providerStore = store,
      llmService = llm,
      localAi = localRuntime("lfm2")
    ))
    repo.selectModel("local:lfm2")

    val answer = repo.explain()

    assertTrue(answer.toString(), answer?.contains("on-device") == true)
    assertTrue(llm.requests.isEmpty())
  }
}
