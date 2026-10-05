package com.awaki.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.awaki.MainActivity
import com.awaki.R

/**
 * The notifications behind the foreground service.
 *
 * There are two, on purpose. One is the low-priority ongoing notice the system
 * requires of a foreground service - it must never make a sound, because the user
 * put the app in their pocket to be rid of it. The other is an alert, only posted
 * when work is genuinely stuck waiting on a decision, which is the one moment a
 * backgrounded agent turn must reach the user.
 */
object WorkNotifications {

  const val CHANNEL_WORK = "awaki_work"
  const val CHANNEL_ATTENTION = "awaki_attention"

  /** The foreground service notification keeps its own fixed id. */
  const val WORK_NOTIFICATION_ID = 1001

  /** The separate "your answer is needed" alert. */
  const val ATTENTION_NOTIFICATION_ID = 1002

  /** One-shot notice for work a killed process could not carry over. */
  const val RECOVERY_NOTIFICATION_ID = 1003

  /** The APK finished downloading while the app was not on screen. */
  const val INSTALL_READY_NOTIFICATION_ID = 1004

  const val ACTION_STOP = "com.awaki.background.ACTION_STOP"
  const val ACTION_OPEN = "com.awaki.background.ACTION_OPEN"
  const val EXTRA_WORK_ID = "work_id"

  private const val GROUP = "com.awaki.background.WORK"

  /** Distinct request code, so the installer tap never collides with the open-app one. */
  private const val INSTALL_REQUEST_CODE = 4211

  /**
   * Channels are created once per process and never mutated: an existing
   * channel keeps whatever importance the user chose, which is the point of the
   * notification settings.
   */
  fun ensureChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL_WORK) == null) {
      manager.createNotificationChannel(
        NotificationChannel(
          CHANNEL_WORK,
          "Awaki tasks",
          NotificationManager.IMPORTANCE_LOW
        ).apply {
          description = "Shows what Awaki is doing while it runs in the background."
          setShowBadge(false)
          lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
      )
    }
    if (manager.getNotificationChannel(CHANNEL_ATTENTION) == null) {
      manager.createNotificationChannel(
        NotificationChannel(
          CHANNEL_ATTENTION,
          "Awaki needs an answer",
          NotificationManager.IMPORTANCE_HIGH
        ).apply {
          description = "Alerts you when a background task is waiting for your decision."
          setShowBadge(true)
        }
      )
    }
  }

  fun buildWorkNotification(
    context: Context,
    active: List<ActiveWork>,
    wakeLockHeld: Boolean
  ): Notification {
    ensureChannels(context)
    val ordered = BackgroundPolicy.notificationOrder(active)
    val builder = NotificationCompat.Builder(context, CHANNEL_WORK)
      .setSmallIcon(R.drawable.ic_stat_awaki_work)
      .setContentTitle(BackgroundPolicy.summaryTitle(ordered))
      .setContentText(BackgroundPolicy.summaryText(ordered))
      .setStyle(
        NotificationCompat.InboxStyle().also { style ->
          ordered.take(4).forEach { style.addLine(describe(it)) }
        }
      )
      .setContentIntent(openIntent(context))
      .setSilent(true)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setShowWhen(true)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .setGroup(GROUP)

    // An indeterminate bar reads as "still going"; the count of live commands
    // is what a user actually looks for when the phone is warm in their pocket.
    if (ordered.isNotEmpty()) {
      builder.setProgress(0, 0, true)
    }
    val sub = buildString {
      append(ordered.size)
      append(if (ordered.size == 1) " task running" else " tasks running")
      if (wakeLockHeld) append(" · CPU kept awake")
    }
    builder.setSubText(sub)

    ordered.filter { it.cancellable }.forEach { work ->
      builder.addAction(
        NotificationCompat.Action(
          0,
          "Stop",
          stopIntent(context, work.id)
        )
      )
    }
    return builder.build()
  }

  /**
   * Posted only while some work is blocked on the user. It is not the service
   * notification: that one must stay silent and ongoing, and an answer that is
   * needed deserves to make a noise.
   */
  fun buildAttentionNotification(context: Context, work: ActiveWork): Notification {
    ensureChannels(context)
    return NotificationCompat.Builder(context, CHANNEL_ATTENTION)
      .setSmallIcon(R.drawable.ic_stat_awaki_work)
      .setContentTitle("Awaki needs your answer")
      .setContentText(work.detail.ifBlank { work.label })
      .setStyle(
        NotificationCompat.BigTextStyle()
          .bigText(work.detail.ifBlank { "A task is paused until you allow or deny the pending request." })
      )
      .setContentIntent(openIntent(context))
      .setAutoCancel(true)
      .setGroup(GROUP)
      .build()
  }

  /**
   * Posted once on the start after a process death: the work is gone and no
   * service can bring it back, so the honest thing is to say so and hand the user
   * a way back into the conversation to retry it.
   */
  fun buildRecoveryNotification(context: Context, entries: List<JournalEntry>): Notification {
    ensureChannels(context)
    val head = entries.first().label.ifBlank { "Background work" }
    val more = entries.size - 1
    val body = if (more > 0) "$head (+$more more)" else head
    return NotificationCompat.Builder(context, CHANNEL_ATTENTION)
      .setSmallIcon(R.drawable.ic_stat_awaki_work)
      .setContentTitle("Task was interrupted")
      .setContentText("Open Awaki to run it again")
      .setStyle(NotificationCompat.BigTextStyle().bigText("$body\nOpen Awaki to run it again."))
      .setContentIntent(openIntent(context))
      .setAutoCancel(true)
      .setGroup(GROUP)
      .build()
  }

  /**
   * The update APK is ready and Awaki is not on screen, so it may not open the
   * installer itself - Android 10 blocks a backgrounded app from putting a screen in
   * front of the user. The tap is what earns that start.
   */
  fun buildInstallReadyNotification(context: Context, installIntent: Intent): Notification {
    ensureChannels(context)
    return NotificationCompat.Builder(context, CHANNEL_ATTENTION)
      .setSmallIcon(R.drawable.ic_stat_awaki_work)
      .setContentTitle("Update is ready to install")
      .setContentText("Tap to open the installer")
      .setContentIntent(installPendingIntent(context, installIntent))
      .setAutoCancel(true)
      .setGroup(GROUP)
      .build()
  }

  /**
   * The installer intent resolved to a component before it is wrapped: a PendingIntent
   * around an implicit intent is not deliverable on newer Android versions, and the
   * FileProvider grant travels with the resolved intent.
   */
  @Suppress("DEPRECATION") // resolveActivity(Intent, Int) has no pre-33 replacement
  private fun installPendingIntent(context: Context, installIntent: Intent): PendingIntent {
    val target = installIntent.clone() as Intent
    val resolved = runCatching {
      context.packageManager.resolveActivity(target, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
    }.getOrNull()?.activityInfo
    return if (resolved == null) {
      openIntent(context)
    } else {
      target.component = android.content.ComponentName(resolved.packageName, resolved.name)
      PendingIntent.getActivity(
        context,
        INSTALL_REQUEST_CODE,
        target,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      )
    }
  }

  fun openIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
      action = ACTION_OPEN
    }
    return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
  }

  /**
   * The request code is derived from the work id: two Stop actions that differ
   * only in their extras would otherwise collapse into one PendingIntent, and the
   * service would cancel the wrong task.
   */
  fun stopIntent(context: Context, workId: String): PendingIntent {
    val intent = Intent(context, AgentWorkService::class.java).apply {
      action = ACTION_STOP
      putExtra(EXTRA_WORK_ID, workId)
    }
    return PendingIntent.getService(context, workId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
  }

  private fun describe(work: ActiveWork): String {
    val minutes = work.elapsedSeconds / 60
    val age = if (minutes < 1) "just now" else "${minutes}m"
    return "${work.label.ifBlank { BackgroundPolicy.summaryTitle(listOf(work)) }} · $age"
  }
}
