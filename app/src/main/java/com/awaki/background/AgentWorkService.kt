package com.awaki.background

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.awaki.AwakiApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps Awaki's process - and therefore the agent turn, the Linux commands it
 * spawns and any download it owns - alive once the app leaves the screen.
 *
 * The service covers the stretch when [WorkRegistry] holds work and no Awaki
 * screen is up: [BackgroundExecution] starts it as the app leaves the screen, and this
 * stops itself when the last record closes, so an idle app never carries a foreground
 * service and a visible one never needs one.
 *
 * Every command it receives is answered with [ServiceCompat.startForeground] before
 * anything else is decided, because Android cannot be asked how a start was delivered
 * and punishes the guess wrong with a crash.
 *
 * Recovery after a kill is deliberately not this service's job: a restarted process
 * cannot rebuild a coroutine, a socket or a child process that died with it, so
 * [START_NOT_STICKY] avoids a foreground service whose notification would promise
 * work that no longer exists. [BackgroundExecution] offers interrupted work back to
 * the user instead.
 */
class AgentWorkService : Service() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var watcher: Job? = null
  private var wakeLock: PowerManager.WakeLock? = null

  /**
   * Whether this instance has been raised into the foreground. Tracked here rather
   * than asked of the system: the platform exposes no such query, and starting
   * foreground twice would be harmless but sloppy.
   */
  private var foregrounded = false

  /** When the current holding streak began; the battery budget is measured from here. */
  private var lockEpisodeStartedAt = 0L

  private val app: AwakiApplication?
    get() = application as? AwakiApplication

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val live = app?.workRegistry
    if (intent?.action == WorkNotifications.ACTION_STOP) {
      val id = intent.getStringExtra(WorkNotifications.EXTRA_WORK_ID)
      // The owner of the record stops the real job and closes the record. Without a
      // registered canceller the action is ignored, because dropping the record here
      // would hide work that is still running.
      if (id != null) live?.cancel(id)
    }
    val work = live?.active?.value.orEmpty()
    // A plain start cannot be told apart from a foreground start, and the platform
    // offers no way to ask, so every command answers with startForeground() before it
    // decides anything else. Skipping that is the
    // ForegroundServiceDidNotStartInTimeException crash, and it is reachable for real:
    // a quick Linux command ends while this service is still being created, so the
    // start arrives to find nothing left to protect.
    publish(work, fromStartCommand = true)
    if (work.isEmpty()) {
      // The idle notice exists only to satisfy that contract, and leaves with the
      // service in the same instant it appeared.
      stopForegroundCompat()
      stopSelf()
      return START_NOT_STICKY
    }
    watch(live!!)
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    releaseWakeLock()
    super.onDestroy()
  }

  /**
   * Follows the registry until it empties. Changes are reacted to as they happen
   * and nothing is polled: a periodic wake-up would be exactly the battery cost
   * this service is here to avoid.
   */
  private fun watch(live: WorkRegistry) {
    if (watcher?.isActive == true) return
    watcher = scope.launch {
      live.active.collect { work ->
        if (work.isEmpty()) {
          stopForegroundCompat()
          stopSelf()
        } else {
          publish(work, fromStartCommand = false)
        }
      }
    }
  }

  /**
   * Puts the current work on screen. A start command re-raises the service, because
   * the platform arms its deadline for each one; a change that only arrives through
   * the collector updates the notification the service is already standing on.
   */
  private fun publish(work: List<ActiveWork>, fromStartCommand: Boolean) {
    val holds = holdWakeLockIfNeeded(work)
    if (foregrounded && !fromStartCommand) {
      notificationManager()?.notify(
        WorkNotifications.WORK_NOTIFICATION_ID,
        WorkNotifications.buildWorkNotification(this, work, holds)
      )
    } else {
      enterForeground(work, holds)
    }
    postAttentionNotification(work)
  }

  /**
   * An approval the user cannot see is a turn that never ends, so a work record
   * flagged as waiting gets its own alerting notification - the service one stays
   * silent and ongoing, because nobody wants a pocket-lighting-up progress bar.
   */
  private fun postAttentionNotification(work: List<ActiveWork>) {
    val manager = notificationManager() ?: return
    val attention = work.firstOrNull { it.needsAttention }
    val wanted = app?.backgroundExecution?.alerts?.value?.approvalRequested ?: true
    if (attention != null && wanted) {
      manager.notify(
        WorkNotifications.ATTENTION_NOTIFICATION_ID,
        WorkNotifications.buildAttentionNotification(this, attention)
      )
    } else {
      manager.cancel(WorkNotifications.ATTENTION_NOTIFICATION_ID)
    }
  }

  private fun enterForeground(work: List<ActiveWork>, wakeLockHeld: Boolean) {
    WorkNotifications.ensureChannels(this)
    val started = runCatching {
      ServiceCompat.startForeground(
        this,
        WorkNotifications.WORK_NOTIFICATION_ID,
        WorkNotifications.buildWorkNotification(this, work, wakeLockHeld),
        // Android 14 is where the type constant exists; below it the platform does
        // not consult the type of an app that targets SDK 28.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
          0
        }
      )
    }.isSuccess
    if (started) {
      foregrounded = true
    } else if (!foregrounded) {
      app?.backgroundExecution?.reportForegroundServiceAllowed(false)
    }
  }

  /**
   * The lock is taken once per holding streak for the time the budget still allows.
   * The system releases it at that deadline even if this process dies, and a run that
   * outlives the budget goes back to letting the device sleep - an unbounded wake
   * lock is the failure mode this whole feature has to stay clear of.
   */
  private fun holdWakeLockIfNeeded(work: List<ActiveWork>): Boolean {
    val execution = app?.backgroundExecution
    if (!BackgroundPolicy.shouldHoldWakeLock(
        active = work,
        allowBackgroundExecution = execution?.allowBackgroundExecution?.value == true,
        wakeLockEnabled = execution?.wakeLockEnabled?.value == true
      )
    ) {
      releaseWakeLock()
      return false
    }
    val manager = ContextCompat.getSystemService(this, PowerManager::class.java) ?: return false
    val lock = wakeLock ?: manager
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
      .apply { setReferenceCounted(false) }
      .also { wakeLock = it }
    if (lock.isHeld) return true
    val now = System.currentTimeMillis()
    if (lockEpisodeStartedAt == 0L) lockEpisodeStartedAt = now
    val ms = BackgroundPolicy.wakeLockRenewalMs(lockEpisodeStartedAt, now)
    if (ms <= 0L) return false
    if (runCatching { lock.acquire(ms) }.isFailure) return false
    return lock.isHeld
  }

  private fun releaseWakeLock() {
    runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
    lockEpisodeStartedAt = 0L
  }

  private fun stopForegroundCompat() {
    releaseWakeLock()
    foregrounded = false
    runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
    runCatching { notificationManager()?.cancel(WorkNotifications.ATTENTION_NOTIFICATION_ID) }
  }

  private fun notificationManager(): NotificationManager? =
    ContextCompat.getSystemService(this, NotificationManager::class.java)

  private companion object {
    const val WAKE_LOCK_TAG = "awaki::work"
  }
}
