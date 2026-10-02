package com.agentisco

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.agentisco.data.local.ProviderConfigStore
import com.agentisco.data.repository.LocalModelInstallState
import com.agentisco.data.repository.WorkspaceRepository
import com.agentisco.local.FakeEngine
import com.agentisco.local.LocalAiRuntime
import com.agentisco.local.ggufBytes
import com.agentisco.local.model.LocalModel
import com.agentisco.local.model.LocalModelInstallStatus
import com.agentisco.local.model.LocalModelProgress
import com.agentisco.local.repositoryWithInstalled
import com.agentisco.local.runtime.LocalInferenceEngine
import com.agentisco.settings.model.AIModel
import com.agentisco.settings.model.AIProvider
import com.agentisco.settings.model.LLMProtocol
import com.agentisco.settings.model.ModelCapabilities
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.components.LocalModelCard
import com.agentisco.ui.screens.LocalModelsScreen
import com.agentisco.ui.screens.SettingsNavigationCard
import com.agentisco.ui.theme.AgentiscoTheme
import com.agentisco.ui.theme.DarkBackground
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * What the on-device model rows actually look like on a phone-sized screen.
 *
 * Density here is the point: a model row has to carry a name, a quantization, a size, a
 * state and the actions that state allows without turning into a card the user has to
 * scroll past to reach the next model.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class LocalModelsScreenshotTest {

  @get:Rule
  val compose = createComposeRule()

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

  private fun model(installed: Boolean) = LocalModel(
    id = "lfm2",
    name = "LFM2.5-230M",
    sourceUrl = "https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF",
    downloadUrl = "https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF/resolve/main/LFM2.5-230M-Q4_0.gguf",
    quantization = "Q4_0",
    description = "Small local language model for offline AI.",
    sizeBytes = 149_080_928L,
    checksum = "430fbec5b1b355e9bb12cd0638c9f2a8f21fedd6eafb4103e42c7e88887daa73",
    version = "main",
    builtIn = true,
    installed = installed,
    localPath = if (installed) "/data/user/0/com.agentisco/files/local-models/lfm2.gguf" else null
  )

  @Test
  fun `each state a model can be in fits one compact row`() {
    compose.setContent {
      AgentiscoTheme {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(14.dp)
        ) {
          LocalModelCard(
            model = model(false),
            state = null,
            selected = false,
            onInstall = {}, onCancel = {}, onSettings = {}, onInfo = {}, onUse = {},
            onRedownload = {}, onDelete = {}, onForget = {}
          )
          Spacer(modifier = Modifier.height(12.dp))
          LocalModelCard(
            model = model(false),
            state = LocalModelInstallState(
              LocalModelInstallStatus.DOWNLOADING,
              progress = LocalModelProgress(
                bytesTransferred = 63_134_720L,
                totalBytes = 149_080_928L,
                speedBytesPerSec = 4_718_592L
              )
            ),
            selected = false,
            onInstall = {}, onCancel = {}, onSettings = {}, onInfo = {}, onUse = {},
            onRedownload = {}, onDelete = {}, onForget = {}
          )
          Spacer(modifier = Modifier.height(12.dp))
          LocalModelCard(
            model = model(true),
            state = LocalModelInstallState(LocalModelInstallStatus.INSTALLED),
            selected = true,
            onInstall = {}, onCancel = {}, onSettings = {}, onInfo = {}, onUse = {},
            onRedownload = {}, onDelete = {}, onForget = {}
          )
          Spacer(modifier = Modifier.height(12.dp))
          LocalModelCard(
            model = model(true),
            state = LocalModelInstallState(
              LocalModelInstallStatus.UPDATE_AVAILABLE
            ),
            selected = false,
            onInstall = {}, onCancel = {}, onSettings = {}, onInfo = {}, onUse = {},
            onRedownload = {}, onDelete = {}, onForget = {}
          )
        }
      }
    }

    compose.onNodeWithText("Not installed").assertExists()
    compose.onNodeWithText("Downloading").assertExists()
    compose.onNodeWithText("Installed · in use").assertExists()
    compose.onNodeWithText("Update available").assertExists()
    compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/local_model_states.png")
  }

  /** The page itself, over a device that has one model installed. */
  @Test
  fun `the local models page frames the list inside one card`() = runBlocking {
    val repository = repositoryWithInstalled(context, payload, "lfm2")
    val local = LocalAiRuntime(
      repository,
      LocalInferenceEngine(repository, FakeEngine(), Dispatchers.Unconfined),
      dispatcher = Dispatchers.Unconfined
    ).also { runtime = it }
    val store = ProviderConfigStore().apply {
      upsertProvider(
        AIProvider("cloud", "Cloud AI", "https://cloud.test/v1", LLMProtocol.OPENAI_CHAT_COMPLETIONS, hasApiKey = true),
        "sk-cloud-key"
      )
      upsertModel(AIModel("cloud-model", "cloud", "big-model", "Big Model", capabilities = ModelCapabilities(tools = true)))
      selectModel("cloud-model")
    }
    val viewModel = WorkspaceViewModel(WorkspaceRepository(context = null, providerStore = store, localAi = local))

    compose.setContent {
      AgentiscoTheme {
        LocalModelsScreen(viewModel, onNavigate = {})
      }
    }

    compose.onNodeWithText("Local Models").assertIsDisplayed()
    compose.onNodeWithText("On-device models").assertIsDisplayed()
    compose.onNodeWithText("Lfm2").assertIsDisplayed()
    compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/local_models_section.png")
  }

  /** Choosing an on-device model's tools is a trade against numbers the page shows. */
  @Test
  fun `the page shows what each tool costs the model in use`() = runBlocking {
    val repository = repositoryWithInstalled(context, payload, "lfm2")
    val local = LocalAiRuntime(
      repository,
      LocalInferenceEngine(repository, FakeEngine(), Dispatchers.Unconfined),
      dispatcher = Dispatchers.Unconfined
    ).also { runtime = it }
    val store = ProviderConfigStore().apply {
      upsertProvider(
        AIProvider("cloud", "Cloud AI", "https://cloud.test/v1", LLMProtocol.OPENAI_CHAT_COMPLETIONS, hasApiKey = true),
        "sk-cloud-key"
      )
      upsertModel(AIModel("cloud-model", "cloud", "big-model", "Big Model", capabilities = ModelCapabilities(tools = true)))
    }
    val viewModel = WorkspaceViewModel(WorkspaceRepository(context = null, providerStore = store, localAi = local))
    viewModel.localModelSelectable("lfm2")?.let(viewModel::selectModel)

    compose.setContent {
      AgentiscoTheme { LocalModelsScreen(viewModel, onNavigate = {}) }
    }

    compose.onNodeWithText("Tools offered").assertIsDisplayed()
    compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/local_model_tools.png")
  }

  /** The two doorways Settings offers for AI configuration, as the page shows them. */
  @Test
  fun `the AI doorways in settings are one card each and read as a pair`() {
    compose.setContent {
      AgentiscoTheme {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          SettingsNavigationCard(
            icon = Icons.Outlined.Psychology,
            title = "AI Providers",
            summary = "3 providers · 12 models",
            onClick = {},
            tag = "card_ai_providers"
          )
          SettingsNavigationCard(
            icon = Icons.Outlined.Memory,
            title = "Local Models",
            summary = "1 installed · 142 MB on disk",
            onClick = {},
            tag = "card_local_models"
          )
        }
      }
    }

    compose.onNodeWithText("AI Providers").assertIsDisplayed()
    compose.onNodeWithText("Local Models").assertIsDisplayed()
    compose.onNodeWithText("1 installed · 142 MB on disk").assertIsDisplayed()
    compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/settings_ai_doorways.png")
  }
}
