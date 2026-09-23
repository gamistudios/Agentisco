package com.agentisco

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.agentisco.BuildConfig
import com.agentisco.core.model.AppDestination
import com.agentisco.data.repository.UpdateRepository
import com.agentisco.data.repository.WorkspaceRepository
import com.agentisco.ui.UpdateViewModel
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.components.*
import com.agentisco.ui.screens.*
import com.agentisco.ui.theme.DarkBackground
import com.agentisco.ui.theme.AgentiscoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      AgentiscoTheme {
        AgentIDEApp()
      }
    }
  }
}

@Composable
fun AgentIDEApp(
  viewModel: WorkspaceViewModel = run {
    val app = LocalContext.current.applicationContext as AgentiscoApplication
    viewModel(
      factory = viewModelFactory {
        initializer {
          WorkspaceViewModel(WorkspaceRepository(context = app, providerStore = app.providerStore))
        }
      }
    )
  },
  updateViewModel: UpdateViewModel = run {
    val app = LocalContext.current.applicationContext as AgentiscoApplication
    viewModel(
      factory = viewModelFactory {
        initializer {
          UpdateViewModel(app.updateRepository, app.userPreferencesStore)
        }
      }
    )
  }
) {
  val currentDestination by viewModel.currentDestination.collectAsState()
  val activeProject by viewModel.activeProject.collectAsState()
  val selectedModel by viewModel.selectedModel.collectAsState()
  val aiModels by viewModel.aiModels.collectAsState()
  val providers by viewModel.providers.collectAsState()
  val isAgentWorking by viewModel.isAgentWorking.collectAsState()
  val pendingApproval by viewModel.pendingApproval.collectAsState()
  val isCommandPaletteOpen by viewModel.isCommandPaletteOpen.collectAsState()
  val isModelSheetOpen by viewModel.isModelSheetOpen.collectAsState()

  // Auto-update state
  val updateUiState by updateViewModel.uiState.collectAsState()

  // Debug builds surface the previous run's captured crash trace once per
  // cold start, so a hard crash can be inspected without logcat.
  var isCrashLogVisible by remember { mutableStateOf(BuildConfig.DEBUG) }

  // The header bug button appears on every screen once a crash has been
  // captured, so the trace is reachable from wherever the crash happened.
  val app = LocalContext.current.applicationContext as AgentiscoApplication
  var hasCrashLog by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    hasCrashLog = withContext(Dispatchers.IO) { app.readLastCrashLog() != null }
  }

  // Kick off a background update check when the auto-update toggle allows it.
  LaunchedEffect(Unit) {
    updateViewModel.checkForUpdates(isAuto = true)
  }

  // Handle system back navigation
  androidx.activity.compose.BackHandler(
    enabled = isCommandPaletteOpen || isModelSheetOpen || currentDestination != AppDestination.AGENT
  ) {
    when {
      isCommandPaletteOpen -> viewModel.toggleCommandPalette(false)
      isModelSheetOpen -> viewModel.toggleModelSheet(false)
      currentDestination == AppDestination.EDITOR -> viewModel.navigateTo(AppDestination.FILES)
      currentDestination == AppDestination.DIFF -> viewModel.navigateTo(AppDestination.AGENT)
      else -> viewModel.navigateTo(AppDestination.AGENT)
    }
  }

  // While the soft keyboard is open, drop the bottom nav bar and stop reserving
  // the navigation-bar inset. Otherwise the Scaffold reserves the bar's height
  // above the keyboard, leaving a large gap between the IME and screens' dev keybars.
  val density = LocalDensity.current
  val isImeVisible = WindowInsets.ime.getBottom(density) > 0

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = DarkBackground,
    contentWindowInsets = if (isImeVisible) {
      ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.navigationBars)
    } else {
      ScaffoldDefaults.contentWindowInsets
    },
    topBar = {
      AgentIDETopAppBar(
        activeProject = activeProject,
        currentDestination = currentDestination,
        onNavigate = { dest -> viewModel.navigateTo(dest) },
        onOpenModelSheet = { viewModel.toggleModelSheet(true) },
        onOpenCommandPalette = { viewModel.toggleCommandPalette(true) },
        updateState = updateUiState.updateState,
        updateProgress = updateUiState.downloadProgress,
        hasNewUpdate = updateUiState.availableUpdate != null,
        onUpdateClick = {
          when {
            // A known update (even mid-download) reopens the dialog so the
            // user can peek at progress / install / cancel at will.
            updateUiState.availableUpdate != null -> updateViewModel.showDialog()
            updateUiState.updateState == UpdateRepository.UpdateState.CHECKING -> Unit
            else -> updateViewModel.checkForUpdates(isAuto = false)
          }
        },
        onShowCrashLog = if (BuildConfig.DEBUG && hasCrashLog) {
          { isCrashLogVisible = true }
        } else null
      )
    },
    bottomBar = {
      if (!isImeVisible) {
        AgentIDEBottomBar(
          currentDestination = currentDestination,
          isAgentWorking = isAgentWorking,
          onNavigate = { dest -> viewModel.navigateTo(dest) }
        )
      }
    }
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(DarkBackground)
        .padding(innerPadding)
    ) {
      Crossfade(targetState = currentDestination, label = "ScreenTransition") { destination ->
        when (destination) {
          AppDestination.AGENT -> AgentScreen(
            viewModel = viewModel,
            onNavigate = { viewModel.navigateTo(it) }
          )
          AppDestination.PROJECTS -> ProjectsScreen(
            viewModel = viewModel,
            onNavigate = { viewModel.navigateTo(it) }
          )
          AppDestination.TERMINAL -> TerminalScreen(
            viewModel = viewModel,
            onNavigate = { viewModel.navigateTo(it) }
          )
          AppDestination.FILES -> FilesScreen(
            viewModel = viewModel,
            onNavigate = { viewModel.navigateTo(it) }
          )
          AppDestination.EDITOR -> EditorScreen(
            viewModel = viewModel,
            onNavigate = { viewModel.navigateTo(it) }
          )
          AppDestination.DIFF -> DiffScreen(
            viewModel = viewModel,
            onNavigate = { viewModel.navigateTo(it) }
          )
          AppDestination.GIT -> GitScreen(
            viewModel = viewModel,
            onNavigate = { viewModel.navigateTo(it) }
          )
          AppDestination.BUILD_RUN -> BuildRunScreen(
            viewModel = viewModel,
            onNavigate = { viewModel.navigateTo(it) }
          )
          AppDestination.SETTINGS -> SettingsScreen(
            viewModel = viewModel,
            updateViewModel = updateViewModel,
            onNavigate = { viewModel.navigateTo(it) },
            onShowCrashLog = { isCrashLogVisible = true }
          )
        }
      }
    }

    // ——— Update overlays ———
    if (updateUiState.updateState == UpdateRepository.UpdateState.CHECKING &&
        updateUiState.availableUpdate == null
    ) {
      UpdateCheckingDialog()
    }

    updateUiState.availableUpdate?.let { update ->
      if (
        updateUiState.showUpdateDialog &&
        updateUiState.updateState != UpdateRepository.UpdateState.CHECKING
      ) {
        UpdateDialog(
          availableVersion = update.versionName,
          releaseNotes = update.releaseNotes,
          updateState = updateUiState.updateState,
          progress = updateUiState.downloadProgress,
          error = updateUiState.error,
          onDownload = { updateViewModel.startDownload() },
          onInstall = { updateViewModel.installUpdate() },
          onCancelDownload = { updateViewModel.cancelDownload() },
          onDismiss = { updateViewModel.dismissDialog() }
        )
      }
    }

    // Modal Overlays
    pendingApproval?.let { approval ->
      ApprovalDialog(
        approval = approval,
        onResolve = { allowed -> viewModel.resolveApproval(allowed) }
      )
    }

    CommandPaletteDialog(
      isOpen = isCommandPaletteOpen,
      onDismiss = { viewModel.toggleCommandPalette(false) },
      onNavigate = { dest -> viewModel.navigateTo(dest) },
      onOpenModelSheet = { viewModel.toggleModelSheet(true) },
      onStartAgentTask = { task -> viewModel.runAgentTask(task) }
    )

    ModelSelectorSheet(
      isOpen = isModelSheetOpen,
      currentModel = selectedModel,
      providers = providers,
      models = aiModels,
      onSelectModel = { model ->
        viewModel.selectModel(model)
        viewModel.toggleModelSheet(false)
      },
      onDismiss = { viewModel.toggleModelSheet(false) }
    )

    // Debug crash inspector (no-op in release builds).
    CrashLogDialog(
      visible = isCrashLogVisible,
      onDismiss = { isCrashLogVisible = false }
    )
  }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  androidx.compose.material3.Text(text = "Hello $name!", modifier = modifier)
}

