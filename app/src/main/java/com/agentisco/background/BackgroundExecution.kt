package com.agentisco.background

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.agentisco.settings.store.UserPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns everything that keeps Agentisco running when it is not on screen.
 *
 * It sits on the process, not on a screen or a service: work registers with the
 * [registry], this decides whether the foreground service and the wake lock are owed,
 * and the journal records what was live so the next start can say what was lost.
 * Long work that must outlive the view model that launched it (the update download)
 * runs on [workScope] for the same reason.
 */
class BackgroundExecution(
  private val appContext: Context,
  private val prefs: UserPreferencesStore,
  val registry: WorkRegistry,
  private val journal: WorkJournal,
  val processToken: String
) : Application.ActivityLifecycleCallbacks {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /**
   * Launched work that has no screen to belong to. Kept on the process so closing an
   * activity cannot cancel a download the user started minutes ago.
   */
  val workScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _allowBackgroundExecution = MutableStateFlow(prefs.preferences.value.allowBackgroundExecution)

  /** The master switch: off means Agentisco never raises a service or a wake lock. */
  val allowBackgroundExecution: StateFlow<Boolean> = _allowBackgroundExecution.asStateFlow()

  private val _wakeLockEnabled = MutableStateFlow(prefs.preferences.value.backgroundWakeLock)
  val wakeLockEnabled: StateFlow<Boolean> = _wakeLockEnabled.asStateFlow()

  private val _isAppForeground = MutableStateFlow(false)

  /** Whether an Agentisco activity is visible; decides who may start a screen. */
  val isAppForeground: StateFlow<Boolean> = _isAppForeground.asStateFlow()

  private val _foregroundServiceAllowed = MutableStateFlow(true)

  /** False once the system refused a foreground start: work runs, but unprotected. */
  val foregroundServiceAllowed: StateFlow<Boolean> = _foregroundServiceAllowed.asStateFlow()

  private val _requirements = MutableStateFlow<List<BackgroundRequirement>>(emptyList())

  /** The live checklist shown in Settings, refreshed whenever the app is looked at. */
  val requirements: StateFlow<List<BackgroundRequirement>> = _requirements.asStateFlow()

  private val _interruptedWork = MutableStateFlow<List<JournalEntry>>(emptyList())

  /** Work a previous process was doing when it died, offered back once. */
  val interruptedWork: StateFlow<List<JournalEntry>> = _interruptedWork.asStateFlow()

  private var visibleActivities = 0

  private val _terminalHeld = MutableStateFlow(false)

  /** Whether the manual "keep the terminal awake" switch is on. */
  val terminalHeld: StateFlow<Boolean> = _terminalHeld.asStateFlow()

  private val _notificationsPromptRequested = MutableStateFlow(false)

  /**
   * Set when there is live work but the OS would hide its notification: the screen
   * that can ask is the one the user is looking at, so this waits for the UI.
   */
  val notificationsPromptRequested: StateFlow<Boolean> = _notificationsPromptRequested.asStateFlow()

  private val _openedFromNotification = MutableStateFlow(false)

  /**
   * Set when a work notification pulled the user back into the app, so the screen
   * explaining that work can open itself. It lives here rather than in the activity
   * because the tap may arrive before the activity exists, or after it was recreated.
   */
  val openedFromNotification: StateFlow<Boolean> = _openedFromNotification.asStateFlow()

  fun markOpenedFromNotification() {
    _openedFromNotification.value = true
  }

  /** The UI has acted on the tap; the next identical tap must still register. */
  fun acknowledgeOpenedFromNotification() {
    _openedFromNotification.value = false
  }

  /** Call once from Application.onCreate. */
  fun start() {
    (appContext as? Application)?.registerActivityLifecycleCallbacks(this)
    scope.launch {
      // Whatever an earlier process was doing is gone; say so once, then let the
      // user decide. Nothing is silently restarted: a resume spends their data and
      // their API credits, which is not this coordinator's to spend.
      //
      // This read comes first because the collector below republishes the registry
      // the moment it subscribes - and an empty registry clears the journal.
      val lost = journal.takeInterrupted(processToken, System.currentTimeMillis())
      if (lost.isNotEmpty()) {
        _interruptedWork.value = lost
        notificationManager()?.notify(
          WorkNotifications.RECOVERY_NOTIFICATION_ID,
          WorkNotifications.buildRecoveryNotification(appContext, lost)
        )
      } else {
        clearRecoveryNotification()
      }

      registry.active.collect { active ->
        syncService(active)
        journal.write(active.map(::entry))
      }
    }
    scope.launch {
      // Service owed depends on who is looking at the screen, so leaving it has to
      // re-decide on its own: a turn started in the foreground and then minimised
      // would otherwise run with nothing holding the process down.
      _isAppForeground.collect { syncService(registry.active.value) }
    }
    scope.launch {
      prefs.preferences.collect { values ->
        _allowBackgroundExecution.value = values.allowBackgroundExecution
        _wakeLockEnabled.value = values.backgroundWakeLock
        syncService(registry.active.value)
        refreshPermissions()
      }
    }
    scope.launch {
      refreshPermissions()
    }
  }

  // Counting started activities rather than resumed ones keeps a configuration
  // change (rotation) from looking like the app leaving the screen.
  override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

  override fun onActivityStarted(activity: Activity) {
    visibleActivities++
    _isAppForeground.value = true
    refreshPermissions()
  }

  override fun onActivityResumed(activity: Activity) = Unit

  override fun onActivityPaused(activity: Activity) = Unit

  override fun onActivityStopped(activity: Activity) {
    visibleActivities = (visibleActivities - 1).coerceAtLeast(0)
    if (visibleActivities == 0) _isAppForeground.value = false
  }

  override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

  override fun onActivityDestroyed(activity: Activity) = Unit

  // ---- User settings ----

  fun setAllowBackgroundExecution(enabled: Boolean) {
    prefs.updatePreferences { it.copy(allowBackgroundExecution = enabled) }
  }

  fun setWakeLockEnabled(enabled: Boolean) {
    prefs.updatePreferences { it.copy(backgroundWakeLock = enabled) }
  }

  /**
   * The manual "keep the CPU awake" switch for an interactive terminal session: the
   * only honest way to give a hand-started dev server the same treatment as a
   * scripted one, instead of holding a wake lock whenever a shell happens to be open.
   */
  fun setTerminalHold(enabled: Boolean) {
    if (enabled) {
      registry.begin(
        id = TERMINAL_HOLD_ID,
        kind = WorkKind.TERMINAL_HOLD,
        label = "Terminal kept awake",
        detail = "The CPU stays awake so a hand-started server keeps serving",
        canceller = { setTerminalHold(false) }
      )
      _terminalHeld.value = true
    } else {
      registry.end(TERMINAL_HOLD_ID)
      _terminalHeld.value = false
    }
  }

  fun isTerminalHeld(): Boolean = registry.has(TERMINAL_HOLD_ID)

  fun markNotificationsAsked() {
    prefs.updatePreferences { it.copy(notificationsAskedAt = System.currentTimeMillis()) }
    _notificationsPromptRequested.value = false
  }

  /**
   * Decides whether the UI should ask for notification permission: only on Android
   * 13+, only while there is work that needs to be visible, and only once per
   * install. A refusal is honoured - it is never re-asked.
   */
  fun evaluateNotificationsPrompt() {
    _notificationsPromptRequested.value = BackgroundPolicy.shouldPromptForNotifications(
      sdkInt = android.os.Build.VERSION.SDK_INT,
      notificationsGranted = BackgroundPermissions.notificationsEnabled(appContext),
      askedBefore = hasAskedForNotifications(),
      hasActiveWork = registry.active.value.isNotEmpty()
    )
  }

  /** The UI has taken the ask (shown it or dismissed it); nothing is nagged twice. */
  fun resolveNotificationsPrompt() {
    _notificationsPromptRequested.value = false
  }

  fun hasAskedForNotifications(): Boolean = prefs.preferences.value.notificationsAskedAt > 0L

  /** True when the OS still hides every notification, so background work runs unseen. */
  fun notificationsAllowed(): Boolean = BackgroundPermissions.notificationsEnabled(appContext)

  fun refreshPermissions() {
    _requirements.value = BackgroundPermissions.requirements(
      context = appContext,
      allowBackgroundExecution = _allowBackgroundExecution.value,
      foregroundServiceAllowed = _foregroundServiceAllowed.value
    )
  }

  /** Reported by [AgentWorkService] after it tries to raise itself. */
  fun reportForegroundServiceAllowed(allowed: Boolean) {
    if (_foregroundServiceAllowed.value == allowed) return
    _foregroundServiceAllowed.value = allowed
    refreshPermissions()
  }

  /** Asks the owner of a work record to stop; the notification's Stop action. */
  fun cancelWork(id: String): Boolean = registry.cancel(id)

  /** Clears the "interrupted" notice once the user has seen it in the app. */
  fun consumeInterruptedWork() {
    _interruptedWork.value = emptyList()
    clearRecoveryNotification()
  }

  /**
   * Announces a finished download the only way a backgrounded app may: by handing the
   * installer over as a tap target instead of trying to open it. Null means the APK
   * failed its final verification, so there is nothing to offer.
   */
  fun showInstallReadyNotification(installIntent: Intent?) {
    if (installIntent == null) return
    notificationManager()?.notify(
      WorkNotifications.INSTALL_READY_NOTIFICATION_ID,
      WorkNotifications.buildInstallReadyNotification(appContext, installIntent)
    )
  }

  fun dismissInstallReadyNotification() {
    runCatching { notificationManager()?.cancel(WorkNotifications.INSTALL_READY_NOTIFICATION_ID) }
  }

  private fun syncService(active: List<ActiveWork>) {
    when (
      BackgroundPolicy.foregroundServiceDecision(
        active = active,
        allowBackgroundExecution = _allowBackgroundExecution.value,
        appVisible = _isAppForeground.value
      )
    ) {
      // The start is repeated for work that is already protected rather than tracked
      // here, because the registry is the only honest source of what is live and a
      // process cannot query the platform for what it asked for last.
      ServiceDecision.START ->
        runCatching { ContextCompat.startForegroundService(appContext, serviceIntent()) }
          .onFailure { reportForegroundServiceAllowed(false) }

      ServiceDecision.STOP -> runCatching { appContext.stopService(serviceIntent()) }
      ServiceDecision.NONE -> Unit
    }
  }

  private fun serviceIntent(): Intent = Intent(appContext, AgentWorkService::class.java)

  private fun entry(work: ActiveWork): JournalEntry = JournalEntry(
    id = work.id,
    kind = work.kind,
    label = work.label,
    startedAt = work.startedAt,
    processToken = processToken
  )

  private fun clearRecoveryNotification() {
    runCatching { notificationManager()?.cancel(WorkNotifications.RECOVERY_NOTIFICATION_ID) }
  }

  private fun notificationManager(): NotificationManager? =
    ContextCompat.getSystemService(appContext, NotificationManager::class.java)

  private companion object {
    const val TERMINAL_HOLD_ID = "terminal-hold"
  }
}
