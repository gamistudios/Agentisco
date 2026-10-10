package com.awaki

import android.Manifest
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.awaki.BuildConfig
import com.awaki.background.WorkNotifications
import com.awaki.core.model.AppDestination
import com.awaki.data.repository.UpdateRepository
import com.awaki.data.repository.WorkspaceRepository
import com.awaki.storage.StorageAccess
import com.awaki.ui.UpdateViewModel
import com.awaki.ui.WorkspaceViewModel
import com.awaki.ui.components.*
import com.awaki.ui.screens.*
import com.awaki.ui.screens.settings.SettingsScreen
import com.awaki.ui.theme.AwakiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    recordWorkNotificationEntry()
    setContent {
      val app = LocalContext.current.applicationContext as AwakiApplication
      val uiTheme by app.uiTheme.collectAsState()
      // The window is painted before Compose draws anything, and the system bars keep
      // their own ink: both are the theme's business but outside its composition.
      LaunchedEffect(uiTheme) { applyWindowChrome(uiTheme) }
      AwakiTheme(palette = uiTheme) {
        AgentIDEApp()
      }
    }
  }

  /**
   * Dress the parts of the screen the activity owns: the cold-start window colour and
   * whether the status and navigation bars draw dark ink (a light theme) or light ink.
   */
  private fun applyWindowChrome(palette: com.awaki.ui.theme.UiPalette) {
    window.setBackgroundDrawable(ColorDrawable(palette.background.toArgb()))
    val bars = WindowInsetsControllerCompat(window, window.decorView)
    bars.isAppearanceLightStatusBars = !palette.dark
    bars.isAppearanceLightNavigationBars = !palette.dark
  }

  // singleTop: a notification tap while the app is alive arrives here rather than
  // creating a second instance.
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    recordWorkNotificationEntry()
  }

  private fun recordWorkNotificationEntry() {
    if (intent?.action == WorkNotifications.ACTION_OPEN) {
      (application as AwakiApplication).backgroundExecution.markOpenedFromNotification()
    }
  }
}

@Composable
fun AgentIDEApp(
  viewModel: WorkspaceViewModel = run {
    val app = LocalContext.current.applicationContext as AwakiApplication
    viewModel(
      factory = viewModelFactory {
        initializer {
          WorkspaceViewModel(
            WorkspaceRepository(
              context = app,
              providerStore = app.providerStore,
              localAi = app.localAi
            )
          )
        }
      }
    )
  },
  updateViewModel: UpdateViewModel = run {
    val app = LocalContext.current.applicationContext as AwakiApplication
    viewModel(
      factory = viewModelFactory {
        initializer {
          UpdateViewModel(
            updateRepository = app.updateRepository,
            preferencesStore = app.userPreferencesStore,
            background = app.backgroundExecution
          )
        }
      }
    )
  }
) {
  val currentDestination by viewModel.currentDestination.collectAsState()
  val activeProject by viewModel.activeProject.collectAsState()
  val gitBranches by viewModel.branches.collectAsState()
  val selectedModel by viewModel.selectedModel.collectAsState()
  val aiModels by viewModel.aiModels.collectAsState()
  val providers by viewModel.providers.collectAsState()
  val isAgentWorking by viewModel.isAgentWorking.collectAsState()
  val pendingApproval by viewModel.pendingApproval.collectAsState()
  val approvalDeferred by viewModel.approvalDeferred.collectAsState()
  val isCommandPaletteOpen by viewModel.isCommandPaletteOpen.collectAsState()
  val isModelSheetOpen by viewModel.isModelSheetOpen.collectAsState()

  // Auto-update state
  val updateUiState by updateViewModel.uiState.collectAsState()

  // Debug builds surface the previous run's captured crash trace once per
  // cold start, so a hard crash can be inspected without logcat.
  var isCrashLogVisible by remember { mutableStateOf(BuildConfig.DEBUG) }

  // The header bug button appears on every screen once a crash has been
  // captured, so the trace is reachable from wherever the crash happened.
  val app = LocalContext.current.applicationContext as AwakiApplication
  var hasCrashLog by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    hasCrashLog = withContext(Dispatchers.IO) { app.readLastCrashLog() != null }
  }

  // Kick off a background update check when the auto-update toggle allows it.
  LaunchedEffect(Unit) {
    updateViewModel.checkForUpdates(isAuto = true)
  }

  // Android 13+ hides the "still working" notification until the user grants it,
  // but a permission dialog on app start is rude. BackgroundExecution therefore
  // raises this flag only once real work is running; the ask happens then, and
  // never again after a refusal.
  val notificationsPromptRequested by viewModel.notificationsPromptRequested.collectAsState()
  val requestNotifications = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) viewModel.dismissNotificationsPrompt() else viewModel.markNotificationsAsked()
  }
  LaunchedEffect(notificationsPromptRequested) {
    if (!notificationsPromptRequested) return@LaunchedEffect
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      viewModel.markNotificationsAsked()
    }
  }

  // Storage access is the opposite case: Android never raises its own prompt for it,
  // and without it every read of /storage/emulated/0 simply fails. Ask once per
  // install on a cold start — the "All files access" page on Android 11+, the
  // runtime dialog below — and record a refusal so it cannot interrupt again;
  // the Battery & permissions checklist row is the way back.
  val preferencesStore = app.userPreferencesStore
  val recordStorageOutcome = {
    if (StorageAccess.hasAccess(app)) {
      if (preferencesStore.preferences.value.storageAskedAt != 0L) {
        preferencesStore.updatePreferences { it.copy(storageAskedAt = 0L) }
      }
    } else if (preferencesStore.preferences.value.storageAskedAt == 0L) {
      preferencesStore.updatePreferences { it.copy(storageAskedAt = System.currentTimeMillis()) }
    }
  }
  val requestStorageSettings = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { recordStorageOutcome() }
  val requestStorageLegacy = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { recordStorageOutcome() }
  LaunchedEffect(Unit) {
    if (StorageAccess.hasAccess(app)) return@LaunchedEffect
    if (preferencesStore.preferences.value.storageAskedAt > 0L) return@LaunchedEffect
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      requestStorageSettings.launch(StorageAccess.accessIntent(app))
    } else {
      requestStorageLegacy.launch(StorageAccess.missingLegacyPermissions(app))
    }
  }

  // Entered from a work notification: show the settings card that explains the
  // interruption and its remedies, which is also where the notice is dismissed.
  val openedFromNotification by viewModel.openedFromNotification.collectAsState()
  LaunchedEffect(openedFromNotification) {
    if (openedFromNotification) {
      viewModel.navigateTo(AppDestination.SETTINGS)
      viewModel.acknowledgeOpenedFromNotification()
    }
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
      currentDestination == AppDestination.AI_PROVIDERS -> viewModel.navigateTo(AppDestination.SETTINGS)
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
    containerColor = MaterialTheme.colorScheme.background,
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
        updateChecking = updateUiState.updateState == UpdateRepository.UpdateState.CHECKING,
        branches = gitBranches,
        onCheckoutBranch = { name -> viewModel.checkoutBranch(name) },
        onUpdateClick = {
          // A known update (even mid-download) reopens the dialog so the user can
          // peek at progress / install / cancel at will. With nothing known yet the
          // button is simply "check again" — including while a check is already in
          // flight, because a check no longer takes the screen away and the tap is
          // the only feedback that anything is happening at all.
          if (updateUiState.availableUpdate != null) updateViewModel.showDialog()
          else updateViewModel.checkForUpdates(isAuto = false)
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
        .background(MaterialTheme.colorScheme.background)
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
          AppDestination.AI_PROVIDERS -> AiProvidersScreen(
            viewModel = viewModel,
            onNavigate = { viewModel.navigateTo(it) }
          )
          AppDestination.LOCAL_MODELS -> LocalModelsScreen(
            viewModel = viewModel,
            onNavigate = { viewModel.navigateTo(it) }
          )
        }
      }
    }

    // ——— Update overlays ———
    // A check announces itself where it was asked for — the spinner in the update button,
    // the status line in Settings — and never as a window over the app. The update
    // service can take tens of seconds to wake up, and a modal "Checking…" used to leave
    // the whole UI untouchable for exactly that long, with no way to dismiss it.

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

    // Modal overlays. The approval dialog is a shortcut to the decision, not the
    // decision itself: while a request sits deferred in the chat it stays closed,
    // and the user can re-open it from the request's card.
    if (!approvalDeferred) pendingApproval?.let { approval ->
      ApprovalDialog(
        approval = approval,
        onResolve = { outcome, answer, rationale ->
          when (outcome) {
            ApprovalOutcome.ALLOW -> viewModel.resolveApproval(true, answer)
            ApprovalOutcome.DENY -> viewModel.denyWithReason(rationale.orEmpty())
            ApprovalOutcome.DEFER -> viewModel.deferApproval()
          }
        }
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

