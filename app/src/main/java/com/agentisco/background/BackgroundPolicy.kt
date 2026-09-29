package com.agentisco.background

/**
 * The decisions behind background execution, kept free of Android types so the
 * battery/service policy is testable on its own.
 *
 * Two knobs of the user's matter here: whether Agentisco may keep working at all
 * once the screen is off, and whether it may hold the CPU awake to do it.
 */
object BackgroundPolicy {

  /**
   * Wake locks are only taken for work that actually burns CPU. A download waits
   * on a socket, so Doze deferring the radio costs it seconds, while a build or an
   * agent tool command stops dead without the CPU.
   */
  val WAKE_LOCK_KINDS: Set<WorkKind> = setOf(
    WorkKind.AGENT_TURN,
    WorkKind.TERMINAL,
    WorkKind.BOOTSTRAP,
    WorkKind.TERMINAL_HOLD
  )

  /**
   * The lock is taken in slices rather than for "as long as work lasts", so a
   * record that never gets closed (a crashed owner, a lost callback) cannot keep
   * a phone warm until the battery dies.
   */
  const val WAKE_LOCK_SLICE_MS = 10 * 60_000L

  /** Hard ceiling on how long one uninterrupted run may hold the CPU. */
  const val WAKE_LOCK_BUDGET_MS = 6 * 60 * 60_000L

  /**
   * Whether the foreground service is owed right now.
   *
   * A service earns its ongoing notification only once the app has left the screen:
   * while an activity is visible the process already sits at the top of the priority
   * list, and Agentisco runs a short Linux command every few seconds. Raising and
   * tearing down a service for each of those makes the notification flicker, and it
   * opens the window in which a command ends before the service has even been created
   * - which Android answers with ForegroundServiceDidNotStartInTimeException.
   *
   * [ServiceDecision.NONE] deliberately leaves a service that is already running
   * alone, so a turn that was protected when the app went off screen stays protected
   * when the user glances at it again.
   */
  fun foregroundServiceDecision(
    active: List<ActiveWork>,
    allowBackgroundExecution: Boolean,
    appVisible: Boolean
  ): ServiceDecision {
    if (!allowBackgroundExecution || active.isEmpty()) return ServiceDecision.STOP
    if (!appVisible) return ServiceDecision.START
    // The manual hold is a promise about the CPU rather than incidental work, so it
    // is kept whatever the screen is doing.
    return if (active.any { it.kind == WorkKind.TERMINAL_HOLD }) ServiceDecision.START else ServiceDecision.NONE
  }

  fun shouldHoldWakeLock(
    active: List<ActiveWork>,
    allowBackgroundExecution: Boolean,
    wakeLockEnabled: Boolean
  ): Boolean =
    allowBackgroundExecution && wakeLockEnabled && active.any { it.kind in WAKE_LOCK_KINDS }

  fun needsAttention(active: List<ActiveWork>): Boolean = active.any { it.needsAttention }

  /**
   * Milliseconds of CPU still owed to this run: the smaller of one slice and what
   * is left of the budget. Zero or less means the budget is spent and the lock
   * must stay released even while work is live.
   */
  fun wakeLockRenewalMs(workStartedAt: Long, now: Long): Long {
    val spent = now - workStartedAt
    val remaining = WAKE_LOCK_BUDGET_MS - spent
    if (remaining <= 0L) return 0L
    return minOf(WAKE_LOCK_SLICE_MS, remaining)
  }

  /** Newest-first ordering for the notification: the user sees the live thing first. */
  fun notificationOrder(active: List<ActiveWork>): List<ActiveWork> = active.sortedByDescending { it.startedAt }

  fun summaryTitle(active: List<ActiveWork>): String {
    val first = notificationOrder(active).firstOrNull() ?: return "Agentisco is idle"
    return if (active.size == 1) singleTitle(first) else "${active.size} tasks running"
  }

  private fun singleTitle(work: ActiveWork): String = when (work.kind) {
    WorkKind.AGENT_TURN -> "Agent is working"
    WorkKind.TERMINAL -> work.label.ifBlank { "Linux command is running" }
    WorkKind.BOOTSTRAP -> "Setting up the Linux environment"
    WorkKind.UPDATE_DOWNLOAD -> "Downloading an update"
    WorkKind.TERMINAL_HOLD -> "Keeping the terminal awake"
  }

  fun summaryText(active: List<ActiveWork>): String {
    if (active.isEmpty()) return "Nothing is running"
    val attention = active.firstOrNull { it.needsAttention }
    if (attention != null) return attention.detail.ifBlank { "Agentisco needs your answer" }
    val ordered = notificationOrder(active)
    val head = ordered.first().let { it.detail.ifBlank { it.label } }
    val extra = ordered.size - 1
    return if (extra > 0) "$head (+$extra more)" else head
  }

  /** Android 13+ gates every notification, including the foreground service one. */
  fun requiresNotificationPermission(sdkInt: Int): Boolean = sdkInt >= 33

  /** Below Marshmallow there is no battery optimization to opt out of. */
  fun supportsBatteryExemptionRequest(sdkInt: Int): Boolean = sdkInt >= 23

  /** Android 9+ requires the FOREGROUND_SERVICE permission, and 12+ a service type. */
  fun requiresForegroundServicePermission(sdkInt: Int): Boolean = sdkInt >= 28

  /**
   * The notification prompt is asked once per install, and only when there is
   * work to protect: badgering for a permission the user already refused is
   * worse than a service whose notification stays hidden.
   */
  fun shouldPromptForNotifications(
    sdkInt: Int,
    notificationsGranted: Boolean,
    askedBefore: Boolean,
    hasActiveWork: Boolean
  ): Boolean =
    requiresNotificationPermission(sdkInt) && !notificationsGranted && !askedBefore && hasActiveWork

  /** How long an interrupted record stays worth offering: past that, drop it. */
  const val RECOVERY_WINDOW_MS = 12 * 60 * 60_000L

  fun isRecoverable(startedAt: Long, now: Long): Boolean = now - startedAt in 0L..RECOVERY_WINDOW_MS
}

/** Whether [AgentWorkService] should be started, stopped, or left as it is. */
enum class ServiceDecision { START, STOP, NONE }
