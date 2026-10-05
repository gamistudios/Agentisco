package com.awaki.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the user sees when Awaki keeps working out of sight: the two channels,
 * the silent ongoing service notice that lists the live work, the alert that only
 * fires when an answer is needed, and the two one-shot notices.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkNotificationsTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun manager(): NotificationManager =
    context.getSystemService(NotificationManager::class.java)

  private fun text(notification: Notification, key: String): String? =
    notification.extras.getCharSequence(key)?.toString()

  private fun work(
    id: String,
    kind: WorkKind,
    startedAt: Long = 1_000L,
    label: String = id,
    detail: String = "",
    needsAttention: Boolean = false,
    cancellable: Boolean = false
  ) = ActiveWork(
    id = id,
    kind = kind,
    label = label,
    detail = detail,
    startedAt = startedAt,
    needsAttention = needsAttention,
    cancellable = cancellable
  )

  // ——— channels ———

  @Test
  fun `the work channel is quiet and the attention channel is not`() {
    WorkNotifications.ensureChannels(context)

    val serviceChannel = manager().getNotificationChannel(WorkNotifications.CHANNEL_WORK)
    assertNotNull("the service cannot start foreground without its channel", serviceChannel)
    assertEquals(NotificationManager.IMPORTANCE_LOW, serviceChannel!!.importance)
    assertFalse("a progress bar must never light the badge", serviceChannel.canShowBadge())
    assertEquals(
      "the task list stays off a locked screen",
      Notification.VISIBILITY_SECRET,
      serviceChannel.lockscreenVisibility
    )

    val attention = manager().getNotificationChannel(WorkNotifications.CHANNEL_ATTENTION)!!
    assertEquals(NotificationManager.IMPORTANCE_HIGH, attention.importance)
    assertTrue("an answer that is owed should be seen", attention.canShowBadge())
  }

  @Test
  fun `ensuring channels is idempotent`() {
    WorkNotifications.ensureChannels(context)
    WorkNotifications.ensureChannels(context)

    val shadow = shadowOf(manager())
    assertEquals(
      WorkNotifications.CHANNEL_WORK,
      manager().getNotificationChannel(WorkNotifications.CHANNEL_WORK)?.id
    )
    assertEquals(
      WorkNotifications.CHANNEL_ATTENTION,
      manager().getNotificationChannel(WorkNotifications.CHANNEL_ATTENTION)?.id
    )
    // Building any notice re-ensures them, so a service that starts before the
    // Application does is still allowed to raise one.
    WorkNotifications.buildWorkNotification(context, listOf(work("a", WorkKind.AGENT_TURN)), false)
    assertEquals(2, shadow.notificationChannels.size)
  }

  // ——— the service notification ———

  @Test
  fun `the service notice is ongoing silent and on its own channel`() {
    val notification = WorkNotifications.buildWorkNotification(
      context,
      active = listOf(work("a", WorkKind.AGENT_TURN, label = "Agent is working")),
      wakeLockHeld = false
    )

    assertEquals(WorkNotifications.CHANNEL_WORK, notification.channelId)
    assertTrue(
      "an ongoing task must not be swipeable away",
      notification.flags and Notification.FLAG_ONGOING_EVENT != 0
    )
    assertTrue(
      "the service notification must not alert",
      notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
    )
    assertEquals("Agent is working", text(notification, Notification.EXTRA_TITLE))
    assertNotNull("a tap has to bring the user back", notification.contentIntent)
  }

  @Test
  fun `the service notice counts the live work and names the cpu state`() {
    val active = listOf(
      work("a", WorkKind.AGENT_TURN, startedAt = 1_000L, detail = "reading files"),
      work("b", WorkKind.UPDATE_DOWNLOAD, startedAt = 2_000L, detail = "40% of the APK")
    )
    val holding = WorkNotifications.buildWorkNotification(context, active, wakeLockHeld = true)
    val idle = WorkNotifications.buildWorkNotification(context, active, wakeLockHeld = false)

    assertEquals("2 tasks running", text(holding, Notification.EXTRA_TITLE))
    assertEquals("40% of the APK (+1 more)", text(holding, Notification.EXTRA_TEXT))
    assertEquals("2 tasks running · CPU kept awake", text(holding, Notification.EXTRA_SUB_TEXT))
    assertEquals(
      "the awake clause only appears when the lock is actually held",
      "2 tasks running",
      text(idle, Notification.EXTRA_SUB_TEXT)
    )
  }

  @Test
  fun `stop is offered only for work the app can actually stop`() {
    val active = listOf(
      work("a", WorkKind.AGENT_TURN, cancellable = true),
      work("b", WorkKind.TERMINAL, cancellable = true),
      work("c", WorkKind.BOOTSTRAP, cancellable = false)
    )
    val notification = WorkNotifications.buildWorkNotification(context, active, wakeLockHeld = false)

    assertEquals(
      "two stoppable records, two buttons",
      2,
      notification.actions.size
    )
    notification.actions.forEach { action ->
      assertEquals("Stop", action.title.toString())
      assertNotNull("each action needs its own intent", action.actionIntent)
    }

    val nothing = WorkNotifications.buildWorkNotification(
      context,
      listOf(work("c", WorkKind.BOOTSTRAP)),
      wakeLockHeld = false
    )
    assertEquals(
      "no false promises: nothing cancellable, no Stop button",
      0,
      nothing.actions?.size ?: 0
    )
  }

  // ——— the alerting notifications ———

  @Test
  fun `work waiting on an answer gets its own alert`() {
    val notification = WorkNotifications.buildAttentionNotification(
      context,
      work(
        "a",
        WorkKind.AGENT_TURN,
        label = "Agent is working",
        detail = "Allow git push --force?"
      )
    )
    assertEquals(WorkNotifications.CHANNEL_ATTENTION, notification.channelId)
    assertEquals("Awaki needs your answer", text(notification, Notification.EXTRA_TITLE))
    assertEquals("Allow git push --force?", text(notification, Notification.EXTRA_TEXT))
    assertFalse(
      "an answer request disappears once tapped",
      notification.flags and Notification.FLAG_ONGOING_EVENT != 0
    )
    assertNotNull(notification.contentIntent)
  }

  @Test
  fun `interrupted work is announced with the task that was lost`() {
    val notification = WorkNotifications.buildRecoveryNotification(
      context,
      listOf(
        JournalEntry("a", WorkKind.AGENT_TURN, "Fix the flaky test", 1_000L, "old"),
        JournalEntry("b", WorkKind.TERMINAL, "gradle build", 2_000L, "old")
      )
    )
    assertEquals(WorkNotifications.CHANNEL_ATTENTION, notification.channelId)
    assertEquals("Task was interrupted", text(notification, Notification.EXTRA_TITLE))
    assertEquals("Open Awaki to run it again", text(notification, Notification.EXTRA_TEXT))
    assertNotNull(notification.contentIntent)
  }

  @Test
  fun `a finished download offers the installer rather than opening it`() {
    val install = Intent(Intent.ACTION_VIEW).setType("application/vnd.android.package-archive")
    val notification = WorkNotifications.buildInstallReadyNotification(context, install)

    assertEquals(WorkNotifications.CHANNEL_ATTENTION, notification.channelId)
    assertEquals("Update is ready to install", text(notification, Notification.EXTRA_TITLE))
    assertEquals("Tap to open the installer", text(notification, Notification.EXTRA_TEXT))
    assertNotNull("the tap is what earns the activity start", notification.contentIntent)
  }

  @Test
  fun `an install intent nothing can resolve still produces a tappable notice`() {
    // No package-archive viewer exists under Robolectric, so this exercises the
    // fallback: the notice must stay deliverable instead of wrapping a dead intent.
    val install = Intent("com.awaki.no.such.action")
    val notification = WorkNotifications.buildInstallReadyNotification(context, install)
    assertNotNull(notification.contentIntent)
  }

  @Test
  fun `notifications are grouped so they fold together in the shade`() {
    val service = WorkNotifications.buildWorkNotification(
      context,
      listOf(work("a", WorkKind.AGENT_TURN)),
      wakeLockHeld = false
    )
    val attention = WorkNotifications.buildAttentionNotification(
      context,
      work("a", WorkKind.AGENT_TURN, needsAttention = true)
    )
    assertEquals(service.group, attention.group)
    assertNotNull(service.group)
  }
}
