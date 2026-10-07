package com.awaki

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.awaki.agent.tool.OnDeviceTools
import com.awaki.core.model.AppDestination
import com.awaki.data.local.ProviderConfigStore
import com.awaki.data.repository.LocalModelInstallState
import com.awaki.data.repository.WorkspaceRepository
import com.awaki.local.FakeEngine
import com.awaki.local.LocalAiRuntime
import com.awaki.local.ggufBytes
import com.awaki.local.model.LocalGenerationSettings
import com.awaki.local.model.LocalModel
import com.awaki.local.model.LocalModelConfiguration
import com.awaki.local.model.LocalModelInstallStatus
import com.awaki.local.model.LocalModelProgress
import com.awaki.local.model.LocalRuntimeSettings
import com.awaki.local.repositoryWithInstalled
import com.awaki.local.runtime.LocalInferenceEngine
import com.awaki.settings.model.AIModel
import com.awaki.settings.model.AIProvider
import com.awaki.settings.model.LLMProtocol
import com.awaki.settings.model.ModelCapabilities
import com.awaki.ui.WorkspaceViewModel
import com.awaki.ui.components.AIProvidersSection
import com.awaki.ui.components.LocalModelCard
import com.awaki.ui.components.LocalModelsSection
import com.awaki.ui.components.LocalSettingsInput
import com.awaki.ui.components.formatDuration
import com.awaki.ui.components.formatModelBytes
import com.awaki.ui.screens.AiProvidersScreen
import com.awaki.ui.screens.LocalModelsScreen
import com.awaki.ui.theme.AwakiTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The management screen's own promises: what a row says about a model, which action a
 * state offers, and which numbers the settings form is willing to write into a model load.
 *
 * Nothing here touches the engine or the network — the seam that turns an installed file
 * into a selectable provider is covered by LocalAiRuntimeTest and LocalProviderIntegrationTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class LocalModelsUiTest {

  @get:Rule
  val compose = createComposeRule()

  private lateinit var context: Context
  private val payload = ggufBytes(4096)
  private var runtime: LocalAiRuntime? = null

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    context = ApplicationProvider.getApplicationContext()
    File(context.filesDir, "local-models").deleteRecursively()
  }

  @After
  fun tearDown() {
    held.closeAll()
    runtime?.shutdown()
    runtime = null
    Dispatchers.resetMain()
  }

  private val held = HeldWork()

  // ---- settings form ----

  private fun input(
    context: String = "2048",
    threads: String = "0",
    batch: String = "128",
    maxTokens: String = "200",
    temperature: String = "0.1",
    topK: String = "50",
    topP: String = "1.0",
    repeatPenalty: String = "1.05"
  ) = LocalSettingsInput(context, threads, batch, maxTokens, temperature, topK, topP, repeatPenalty)

  @Test
  fun `a valid form writes exactly the settings the engine reads`() {
    val parsed = input().parse(null)
    assertEquals(
      LocalModelConfiguration(
        runtime = LocalRuntimeSettings(contextSize = 2048, threadCount = 0, batchSize = 128),
        generation = LocalGenerationSettings(
          maxOutputTokens = 200,
          temperature = 0.1,
          topK = 50,
          topP = 1.0,
          repeatPenalty = 1.05
        )
      ),
      parsed.getOrThrow()
    )
  }

  @Test
  fun `the form round-trips a stored configuration`() {
    val configuration = LocalModelConfiguration(
      runtime = LocalRuntimeSettings(contextSize = 1024, threadCount = 4, batchSize = 64),
      generation = LocalGenerationSettings(
        maxOutputTokens = 512,
        temperature = 0.7,
        topK = 40,
        topP = 0.9,
        repeatPenalty = 1.2
      )
    )
    assertEquals(configuration, LocalSettingsInput.of(configuration).parse(null).getOrThrow())
  }

  /** The tools are chosen on the model's own page; a settings save must not undo that. */
  @Test
  fun `the form carries the tools choice it was opened with`() {
    val configuration = LocalModelConfiguration(
      runtime = LocalRuntimeSettings(contextSize = 2048),
      allowedTools = setOf("read_file", "web_search")
    )
    assertEquals(
      configuration,
      LocalSettingsInput.of(configuration).parse(null).getOrThrow()
    )
    // Resetting the form to defaults is the one case where the choice goes back too.
    assertNull(LocalSettingsInput.of(LocalModelConfiguration.Defaults).parse(null).getOrThrow().allowedTools)
  }

  @Test
  fun `a context longer than the weights hold is refused with the limit named`() {
    val refusal = input(context = "8192").parse(2048).exceptionOrNull()
    assertEquals(
      "Context cannot exceed the 2048 tokens this model was trained for",
      refusal?.message
    )
  }

  @Test
  fun `with nothing to read the limit from, the form still caps the window it offers`() {
    assertEquals("Context cannot exceed 8192", input(context = "16384").parse(null).exceptionOrNull()?.message)
    assertNull(input(context = "8192").parse(null).exceptionOrNull())
  }

  @Test
  fun `a field that is not a number is named rather than silently dropped`() {
    assertEquals("Temperature must be a number — \"abc\" is not", input(temperature = "abc").parse(null).exceptionOrNull()?.message)
    assertEquals("Context must be a number", input(context = "").parse(null).exceptionOrNull()?.message)
  }

  @Test
  fun `only values the runtime honours are accepted`() {
    assertTrue(input(threads = "-1").parse(null).isFailure)
    assertNull(input(threads = "0").parse(null).exceptionOrNull())
    assertEquals("Top-P must be above 0 and at most 1", input(topP = "0").parse(null).exceptionOrNull()?.message)
    assertEquals("Repeat penalty must be 1 to 2", input(repeatPenalty = "0.5").parse(null).exceptionOrNull()?.message)
    assertEquals("Context must be at least 128", input(context = "64").parse(null).exceptionOrNull()?.message)
  }

  @Test
  fun `sizes and remaining time are said in the units a download is judged by`() {
    assertEquals("142 MB", formatModelBytes(149_080_928L))
    assertEquals("1.0 GB", formatModelBytes(1_073_741_824L))
    assertEquals("512 B", formatModelBytes(512L))
    assertEquals("45s", formatDuration(45L))
    assertEquals("2 min 5 s", formatDuration(125L))
    assertEquals("1 h 9 min", formatDuration(4140L))
  }

  // ---- the row a model is judged by ----

  private fun model(
    installed: Boolean,
    configuration: LocalModelConfiguration = LocalModelConfiguration.Defaults
  ) = LocalModel(
    id = "lfm2",
    name = "LFM2.5-230M",
    sourceUrl = "https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF",
    downloadUrl = "https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF/resolve/main/LFM2.5-230M-Q4_0.gguf",
    quantization = "Q4_0",
    description = "Small local language model for offline AI.",
    sizeBytes = 149_080_928L,
    builtIn = true,
    installed = installed,
    localPath = if (installed) "/data/local-models/lfm2.gguf" else null,
    configuration = configuration
  )

  private fun renderCard(
    state: LocalModelInstallState?,
    installed: Boolean = state?.status == LocalModelInstallStatus.INSTALLED,
    configuration: LocalModelConfiguration = LocalModelConfiguration.Defaults,
    selected: Boolean = false,
    resident: Boolean = false,
    loading: Boolean = false,
    loadError: String? = null
  ) {
    val shown = model(installed || state?.status == LocalModelInstallStatus.INSTALLED, configuration)
    compose.setContent {
      AwakiTheme {
        LocalModelCard(
          model = shown,
          state = state,
          selected = selected,
          resident = resident,
          loading = loading,
          loadError = loadError,
          onInstall = {}, onCancel = {}, onSettings = {}, onInfo = {}, onLoad = {}, onRedownload = {}, onDelete = {}, onForget = {}
        )
      }
    }
  }

  @Test
  fun `a model that is not downloaded offers a download and never looks installed`() {
    renderCard(null)
    compose.onNodeWithText("Not installed").assertExists()
    compose.onNodeWithText("Download").assertExists()
    compose.onNodeWithText("Settings").assertDoesNotExist()
    compose.onNodeWithText("142 MB · built in").assertExists()
  }

  @Test
  fun `a half-finished transfer says how far it got and offers to stop`() {
    renderCard(
      LocalModelInstallState(
        LocalModelInstallStatus.DOWNLOADING,
        progress = LocalModelProgress(bytesTransferred = 74_540_464L, totalBytes = 149_080_928L, speedBytesPerSec = 1_048_576L),
        resumableBytes = 74_540_464L
      )
    )
    compose.onNodeWithText("Downloading").assertExists()
    compose.onNodeWithText("50% · 1 MB/s · 1 min 11 s").assertExists()
    compose.onNodeWithText("Cancel").assertExists()
    compose.onNodeWithText("Download").assertDoesNotExist()
  }

  @Test
  fun `bytes already on disk make the next attempt a resume`() {
    renderCard(LocalModelInstallState(LocalModelInstallStatus.NOT_INSTALLED, resumableBytes = 4_000_000L))
    compose.onNodeWithText("Resume").assertExists()
    compose.onNodeWithText("142 MB · part downloaded · built in").assertExists()
  }

  @Test
  fun `verifying is its own state, not a download that never ended`() {
    renderCard(LocalModelInstallState(LocalModelInstallStatus.INSTALLING))
    compose.onNodeWithText("Verifying").assertExists()
    compose.onNodeWithText("Cancel").assertExists()
  }

  @Test
  fun `a failed install keeps its reason where the user can read it`() {
    renderCard(LocalModelInstallState(LocalModelInstallStatus.FAILED, error = "Digest mismatch: the file is not the model it claims to be"))
    compose.onNodeWithText("Failed").assertExists()
    compose.onNodeWithText("Digest mismatch: the file is not the model it claims to be").assertExists()
    compose.onNodeWithText("Download").assertExists()
  }

  @Test
  fun `newer bytes at the source offer an update instead of a reinstall`() {
    renderCard(LocalModelInstallState(LocalModelInstallStatus.UPDATE_AVAILABLE))
    compose.onNodeWithText("Update available").assertExists()
    compose.onNodeWithText("Update").assertExists()
  }

  @Test
  fun `an installed model offers its settings, its facts and a load`() {
    renderCard(null, installed = true)
    compose.onNodeWithText("Installed").assertExists()
    compose.onNodeWithText("Load").assertExists()
    compose.onNodeWithText("Settings").assertExists()
    compose.onNodeWithText("Info").assertExists()
    compose.onNodeWithText("Delete").assertExists()
    compose.onNodeWithText("142 MB · ctx ${LocalRuntimeSettings.DEFAULT_CONTEXT} · max 200 · built in").assertExists()
  }

  @Test
  fun `the model in use offers a fresh copy of its bytes, not a second load`() {
    renderCard(null, installed = true, selected = true)
    compose.onNodeWithText("Installed · in use").assertExists()
    compose.onNodeWithText("Re-download").assertExists()
    compose.onNodeWithText("Load").assertDoesNotExist()
  }

  @Test
  fun `a model already in memory says so and offers no second load`() {
    renderCard(null, installed = true, resident = true)
    compose.onNodeWithText("Installed · in memory").assertExists()
    compose.onNodeWithText("Load").assertDoesNotExist()
  }

  @Test
  fun `a load in flight shows the wait instead of a button that would start another`() {
    renderCard(null, installed = true, loading = true)
    compose.onNodeWithText("Loading…").assertExists()
    compose.onNodeWithText("Load").assertDoesNotExist()
  }

  @Test
  fun `a load that failed says why on the row it failed on`() {
    renderCard(null, installed = true, loadError = "The model could not start on this device")
    compose.onNodeWithText("The model could not start on this device").assertExists()
    compose.onNodeWithText("Load").assertExists()
  }

  @Test
  fun `the settings a user changed are the settings the row shows`() {
    renderCard(
      null,
      installed = true,
      configuration = LocalModelConfiguration(
        runtime = LocalRuntimeSettings(contextSize = 8192),
        generation = LocalGenerationSettings(maxOutputTokens = 320)
      )
    )
    compose.onNodeWithText("142 MB · ctx 8192 · max 320 · built in").assertExists()
  }

  @Test
  fun `a model added by URL is not labelled built in`() {
    val added = model(installed = false).copy(builtIn = false, quantization = "")
    renderCardFor(added)
    compose.onNodeWithText("142 MB · added by URL").assertExists()
    compose.onNodeWithText("Remove").assertExists()
  }

  private fun renderCardFor(
    shown: LocalModel,
    state: LocalModelInstallState? = null,
    selected: Boolean = false
  ) {
    compose.setContent {
      AwakiTheme {
        LocalModelCard(
          model = shown,
          state = state,
          selected = selected,
          resident = false,
          loading = false,
          loadError = null,
          onInstall = {}, onCancel = {}, onSettings = {}, onInfo = {}, onLoad = {}, onRedownload = {}, onDelete = {}, onForget = {}
        )
      }
    }
  }

  // ---- a model that was copied off the device, not downloaded ----

  private fun imported(installed: Boolean) =
    model(installed).copy(builtIn = false, sourceUrl = "", downloadUrl = "", quantization = "")

  @Test
  fun `a model copied from this device says where it came from and has nothing to fetch`() {
    renderCardFor(imported(installed = false))

    compose.onNodeWithText("142 MB · from this device").assertExists()
    compose.onNodeWithText("Download").assertDoesNotExist()
    compose.onNodeWithText("Resume").assertDoesNotExist()
    compose.onNodeWithText("Remove").assertExists()
  }

  @Test
  fun `an imported model in use cannot be re-downloaded because nobody publishes it`() {
    renderCardFor(imported(installed = true), selected = true)

    compose.onNodeWithText("Installed · in use").assertExists()
    compose.onNodeWithText("Re-download").assertDoesNotExist()
    // Everything the file itself can still do stays on the row.
    compose.onNodeWithText("Settings").assertExists()
    compose.onNodeWithText("Info").assertExists()
    compose.onNodeWithText("Delete").assertExists()
  }

  @Test
  fun `an imported model is never offered an update`() {
    renderCardFor(imported(installed = true), LocalModelInstallState(LocalModelInstallStatus.UPDATE_AVAILABLE))

    compose.onNodeWithText("Update").assertDoesNotExist()
    compose.onNodeWithText("Load").assertExists()
  }

  @Test
  fun `an import in flight names the wait and offers to stop it`() {
    renderCardFor(imported(installed = false), LocalModelInstallState(LocalModelInstallStatus.IMPORTING))

    compose.onNodeWithText("Importing").assertExists()
    compose.onNodeWithText("Cancel").assertExists()
    compose.onNodeWithText("Download").assertDoesNotExist()
  }

  @Test
  fun `copying a file in is offered beside adding one by URL`() = runTest {
    val viewModel = viewModelWith("lfm2")
    compose.setContent {
      AwakiTheme { LocalModelsSection(viewModel) }
    }

    compose.onNodeWithTag("btn_import_local_model").assertIsDisplayed()
    compose.onNodeWithTag("btn_add_local_model").assertIsDisplayed()
  }

  // ---- the section over a real install ----

  /** A runtime serving [ids] on this device, plus the ViewModel the screen reads. */
  private suspend fun viewModelWith(vararg ids: String): WorkspaceViewModel {
    val repository = repositoryWithInstalled(context, payload, *ids)
    val local = LocalAiRuntime(
      repository,
      LocalInferenceEngine(repository, FakeEngine(), Dispatchers.Unconfined),
      dispatcher = Dispatchers.Unconfined
    ).also { runtime = it }
    // A cloud model is configured and selected, so the on-device row starts out
    // installed-but-not-in-use and the Load action below is the thing changing it.
    val store = ProviderConfigStore().apply {
      upsertProvider(
        AIProvider("cloud", "Cloud AI", "https://cloud.test/v1", LLMProtocol.OPENAI_CHAT_COMPLETIONS, hasApiKey = true),
        "sk-cloud-key"
      )
      upsertModel(
        AIModel("cloud-model", "cloud", "big-model", "Big Model", capabilities = ModelCapabilities(tools = true))
      )
      selectModel("cloud-model")
    }
    return held.hold(WorkspaceViewModel(WorkspaceRepository(context = null, providerStore = store, localAi = local)))
  }

  @Test
  fun `the section lists what this device holds and selecting a model moves the agent`() = runTest {
    val viewModel = viewModelWith("lfm2")
    compose.setContent {
      AwakiTheme { LocalModelsSection(viewModel) }
    }

    compose.onNodeWithText("On-device models").assertIsDisplayed()
    // The built-in catalog is always offered, installed or not, so a device holding one
    // user-installed model lists both and counts only the bytes it actually has.
    compose.onNodeWithText("1 of 2 installed · 4 KB on disk").assertIsDisplayed()
    compose.onNodeWithText("Lfm2").assertIsDisplayed()
    compose.onNodeWithText("Installed").assertIsDisplayed()
    compose.onNodeWithText("LFM2.5-230M").assertExists()
    compose.onNodeWithText("Not installed").assertExists()
    compose.onNodeWithText("Download").assertExists()

    compose.onNodeWithText("Load").performClick()
    assertEquals(
      LocalAiRuntime.recordId("lfm2"),
      viewModel.selectedModel.value?.id
    )
    // The row admits what it now is: the model the agent will use and the one its
    // engine is holding, with no second Load action to tap by accident.
    compose.onNodeWithText("Installed · in use · in memory").assertIsDisplayed()
    compose.onNodeWithText("Load").assertDoesNotExist()
  }

  /** The page Settings opens: the section under a header that returns where it came from. */
  @Test
  fun `the local models page carries the section and returns to settings`() = runTest {
    val viewModel = viewModelWith("lfm2")
    var destination: AppDestination? = null
    compose.setContent {
      AwakiTheme { LocalModelsScreen(viewModel, onNavigate = { destination = it }) }
    }

    compose.onNodeWithText("Local Models").assertIsDisplayed()
    compose.onNodeWithText("On-device models").assertIsDisplayed()
    compose.onNodeWithText("Lfm2").assertIsDisplayed()

    compose.onNodeWithTag("btn_local_models_back").performClick()
    assertEquals(AppDestination.SETTINGS, destination)
  }

  @Test
  fun `the provider page no longer manages model files`() = runTest {
    val viewModel = viewModelWith("lfm2")
    compose.setContent {
      AwakiTheme { AiProvidersScreen(viewModel, onNavigate = {}) }
    }

    compose.onNodeWithText("Cloud AI").assertIsDisplayed()
    compose.onNodeWithText("On-device models").assertDoesNotExist()
  }

  @Test
  fun `the on-device provider appears among the others but is not editable like they are`() = runTest {
    val viewModel = viewModelWith("lfm2")
    compose.setContent {
      AwakiTheme { AIProvidersSection(viewModel) }
    }

    compose.onNodeWithText("Cloud AI").assertIsDisplayed()
    compose.onNodeWithText(LocalAiRuntime.PROVIDER_NAME).assertIsDisplayed()
    // Its card says what it is instead of reporting a key it does not have.
    compose.onNodeWithText("On this device").assertIsDisplayed()
    compose.onNodeWithText("Key set").assertExists()
    // Only the stored provider can be edited or deleted; the derived one has no form.
    compose.onAllNodesWithText("Edit").assertCountEquals(1)
    compose.onAllNodesWithText("Delete").assertCountEquals(1)
  }

  @Test
  fun `a repository that owns no files has no models to manage`() {
    val viewModel = held.hold(
      WorkspaceViewModel(
        WorkspaceRepository(context = null, providerStore = ProviderConfigStore())
      )
    )
    compose.setContent {
      AwakiTheme { LocalModelsSection(viewModel) }
    }

    compose.onNodeWithText("None available to add").assertIsDisplayed()
    compose.onNodeWithText("Installed").assertDoesNotExist()
  }

  // ---- choosing the tools an on-device model is offered ----

  /** The picker appears for the model the agent is actually using, not for a cloud one. */
  @Test
  fun `the tools a model is offered are chosen on its page`() = runTest {
    val viewModel = viewModelWith("lfm2")
    compose.setContent {
      AwakiTheme { LocalModelsScreen(viewModel, onNavigate = {}) }
    }

    compose.onNodeWithText("Tools offered").assertDoesNotExist()

    compose.onNodeWithText("Load").performClick()
    compose.onNodeWithText("Tools offered").assertIsDisplayed()
    compose.onNodeWithTag("chip_local_tool_read_file").assertIsDisplayed()
    // Every tool is on the list, including the ones a phone may not be able to afford.
    compose.onNodeWithTag("chip_local_tool_task_plan").assertExists()
    compose.onNodeWithTag("chip_local_tool_delegate").assertDoesNotExist()
    compose.onNodeWithText("Saved").assertIsDisplayed()
  }

  @Test
  fun `unchecking a tool and saving changes what the model will be offered`() = runTest {
    val viewModel = viewModelWith("lfm2")
    compose.setContent {
      AwakiTheme { LocalModelsScreen(viewModel, onNavigate = {}) }
    }
    compose.onNodeWithText("Load").performClick()

    compose.onNodeWithTag("chip_local_tool_read_file").performClick()
    compose.onNodeWithText("Not saved").assertIsDisplayed()
    compose.onNodeWithTag("btn_local_tools_save").performClick()
    assertEquals(
      OnDeviceTools.DEFAULT - "read_file",
      viewModel.localModels.value.first { it.id == "lfm2" }.configuration.allowedTools
    )

    // Choosing the default again stores no list at all, so a later, better default
    // still reaches this model instead of being frozen by the choice made here.
    compose.onNodeWithTag("btn_local_tools_default").performClick()
    compose.onNodeWithTag("btn_local_tools_save").performClick()
    assertNull(viewModel.localModels.value.first { it.id == "lfm2" }.configuration.allowedTools)
  }
}
