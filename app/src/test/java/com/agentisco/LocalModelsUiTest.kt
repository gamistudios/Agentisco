package com.agentisco

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.agentisco.core.model.AppDestination
import com.agentisco.data.local.ProviderConfigStore
import com.agentisco.data.repository.LocalModelInstallState
import com.agentisco.data.repository.WorkspaceRepository
import com.agentisco.local.FakeEngine
import com.agentisco.local.LocalAiRuntime
import com.agentisco.local.ggufBytes
import com.agentisco.local.model.LocalGenerationSettings
import com.agentisco.local.model.LocalModel
import com.agentisco.local.model.LocalModelConfiguration
import com.agentisco.local.model.LocalModelInstallStatus
import com.agentisco.local.model.LocalModelProgress
import com.agentisco.local.model.LocalRuntimeSettings
import com.agentisco.local.repositoryWithInstalled
import com.agentisco.local.runtime.LocalInferenceEngine
import com.agentisco.settings.model.AIModel
import com.agentisco.settings.model.AIProvider
import com.agentisco.settings.model.LLMProtocol
import com.agentisco.settings.model.ModelCapabilities
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.components.AIProvidersSection
import com.agentisco.ui.components.LocalModelCard
import com.agentisco.ui.components.LocalModelsSection
import com.agentisco.ui.components.LocalSettingsInput
import com.agentisco.ui.components.formatDuration
import com.agentisco.ui.components.formatModelBytes
import com.agentisco.ui.screens.AiProvidersScreen
import com.agentisco.ui.screens.LocalModelsScreen
import com.agentisco.ui.theme.AgentiscoTheme
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
    runtime?.shutdown()
    runtime = null
    Dispatchers.resetMain()
  }

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
    selected: Boolean = false
  ) {
    val shown = model(installed || state?.status == LocalModelInstallStatus.INSTALLED, configuration)
    compose.setContent {
      AgentiscoTheme {
        LocalModelCard(
          model = shown,
          state = state,
          selected = selected,
          onInstall = {}, onCancel = {}, onSettings = {}, onInfo = {}, onUse = {}, onRedownload = {}, onDelete = {}, onForget = {}
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
  fun `an installed model offers the settings and the file's own facts`() {
    renderCard(null, installed = true)
    compose.onNodeWithText("Installed").assertExists()
    compose.onNodeWithText("Select").assertExists()
    compose.onNodeWithText("Settings").assertExists()
    compose.onNodeWithText("Info").assertExists()
    compose.onNodeWithText("Delete").assertExists()
    compose.onNodeWithText("142 MB · ctx ${LocalRuntimeSettings.DEFAULT_CONTEXT} · max 200 · built in").assertExists()
  }

  @Test
  fun `the model in use offers a fresh copy of its bytes, not a second select`() {
    renderCard(null, installed = true, selected = true)
    compose.onNodeWithText("Installed · in use").assertExists()
    compose.onNodeWithText("Re-download").assertExists()
    compose.onNodeWithText("Select").assertDoesNotExist()
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
  fun `a model added by URL is not labelled built in`() {    val added = model(installed = false).copy(builtIn = false, quantization = "")
    compose.setContent {
      AgentiscoTheme {
        LocalModelCard(
          model = added,
          state = null,
          selected = false,
          onInstall = {}, onCancel = {}, onSettings = {}, onInfo = {}, onUse = {}, onRedownload = {}, onDelete = {}, onForget = {}
        )
      }
    }
    compose.onNodeWithText("142 MB · added by URL").assertExists()
    compose.onNodeWithText("Remove").assertExists()
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
    // installed-but-not-in-use and the Select action below is the thing changing it.
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
    return WorkspaceViewModel(WorkspaceRepository(context = null, providerStore = store, localAi = local))
  }

  @Test
  fun `the section lists what this device holds and selecting a model moves the agent`() = runTest {
    val viewModel = viewModelWith("lfm2")
    compose.setContent {
      AgentiscoTheme { LocalModelsSection(viewModel) }
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

    compose.onNodeWithText("Select").performClick()
    assertEquals(
      LocalAiRuntime.recordId("lfm2"),
      viewModel.selectedModel.value?.id
    )
    // The row admits what it now is: the model the agent will use, with no second
    // Select action to tap by accident.
    compose.onNodeWithText("Installed · in use").assertIsDisplayed()
    compose.onNodeWithText("Select").assertDoesNotExist()
  }

  /** The page Settings opens: the section under a header that returns where it came from. */
  @Test
  fun `the local models page carries the section and returns to settings`() = runTest {
    val viewModel = viewModelWith("lfm2")
    var destination: AppDestination? = null
    compose.setContent {
      AgentiscoTheme { LocalModelsScreen(viewModel, onNavigate = { destination = it }) }
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
      AgentiscoTheme { AiProvidersScreen(viewModel, onNavigate = {}) }
    }

    compose.onNodeWithText("Cloud AI").assertIsDisplayed()
    compose.onNodeWithText("On-device models").assertDoesNotExist()
  }

  @Test
  fun `the on-device provider appears among the others but is not editable like they are`() = runTest {
    val viewModel = viewModelWith("lfm2")
    compose.setContent {
      AgentiscoTheme { AIProvidersSection(viewModel) }
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
    val viewModel = WorkspaceViewModel(
      WorkspaceRepository(context = null, providerStore = ProviderConfigStore())
    )
    compose.setContent {
      AgentiscoTheme { LocalModelsSection(viewModel) }
    }

    compose.onNodeWithText("None available to add").assertIsDisplayed()
    compose.onNodeWithText("Installed").assertDoesNotExist()
  }
}
