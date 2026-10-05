package com.awaki.background

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.awaki.AwakiApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The service's own promises: every start command is answered with startForeground,
 * because Android crashes the app over a missing one, whatever the registry looks
 * like by the time the service gets there; it says what the live work is; and a Stop
 * button reaches exactly the task it names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentWorkServiceTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private lateinit var registry: WorkRegistry

  @Before
  fun setUp() {
    val app = context.applicationContext as AwakiApplication
    registry = app.workRegistry
    // Anything an earlier test left registered would keep this service alive.
    registry.active.value.forEach { registry.end(it.id) }
  }

  private fun startWith(intent: Intent = Intent(context, AgentWorkService::class.java)): Service =
    Robolectric.buildService(AgentWorkService::class.java, intent)
      .create()
      .startCommand(0, 1)
      .get()

  private fun foregroundNotification(service: Service) = shadowOf(service).lastForegroundNotification

  @Test
  fun `live work puts the service in the foreground with a notice that names it`() {
    registry.begin("turn-1", WorkKind.AGENT_TURN, "Fix the flaky test", detail = "running 40 tests")

    val service = startWith()
    val notification = foregroundNotification(service)

    assertNotNull("work without a foreground service is work the system kills", notification)
    assertEquals(WorkNotifications.CHANNEL_WORK, notification!!.channelId)
    assertEquals(
      "Agent is working",
      notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
    )
    assertEquals(
      "running 40 tests",
      notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
    )
  }

  @Test
  fun `a stop action cancels the work it names and no other`() {
    var stoppedA = 0
    var stoppedB = 0
    registry.begin("a", WorkKind.AGENT_TURN, "task a", canceller = { stoppedA++ })
    registry.begin("b", WorkKind.TERMINAL, "task b", canceller = { stoppedB++ })

    val intent = Intent(context, AgentWorkService::class.java).apply {
      action = WorkNotifications.ACTION_STOP
      putExtra(WorkNotifications.EXTRA_WORK_ID, "a")
    }
    startWith(intent)

    assertEquals(1, stoppedA)
    assertEquals("the other task is nobody's to stop", 0, stoppedB)
    assertEquals(
      "the record stays until its owner closes it, so the notice cannot lie",
      2,
      registry.active.value.size
    )
  }

  @Test
  fun `a stop action for work that cannot be stopped changes nothing`() {
    registry.begin("bootstrap", WorkKind.BOOTSTRAP, "Setting up the Linux environment")
    val intent = Intent(context, AgentWorkService::class.java).apply {
      action = WorkNotifications.ACTION_STOP
      putExtra(WorkNotifications.EXTRA_WORK_ID, "bootstrap")
    }

    startWith(intent)

    assertEquals(
      "un-cancellable work must not be hidden by a tap",
      listOf("bootstrap"),
      registry.active.value.map { it.id }
    )
    assertNotNull(foregroundNotification(startWith()))
  }

  @Test
  fun `a start that arrives after its work has gone still answers the platform contract`() {
    // Android gives a service reached through startForegroundService() about ten
    // seconds to call startForeground(), and tells the service nothing about which of
    // its starts were delivered that way. Skipping the call is what a quick Linux
    // command used to cause by finishing before the service was even created, and the
    // system answers minutes later with ForegroundServiceDidNotStartInTimeException on
    // the main thread.
    val service = startWith()
    val shadow = shadowOf(service)

    assertEquals(
      "startForeground is the only thing that records this id, and a start without it " +
        "is a crash - even a start that found nothing left to protect",
      WorkNotifications.WORK_NOTIFICATION_ID,
      shadow.lastForegroundNotificationId
    )
    assertTrue("the service that found no work stops itself", shadow.isStoppedBySelf)
    assertNull(
      "and the notice raised only to answer the contract goes with it",
      shadow.lastForegroundNotification
    )
  }

  @Test
  fun `a second start re-raises the service instead of quietly updating the notice`() {
    // A repeat start is not a notification update: the platform arms its deadline for
    // every start it delivers this way, so each one has to be answered with
    // startForeground again. Two commands go to the same instance here, which is the
    // only way to tell that call from the quiet notify() an ordinary update takes.
    registry.begin("a", WorkKind.AGENT_TURN, "first task")
    val controller = Robolectric.buildService(
      AgentWorkService::class.java,
      Intent(context, AgentWorkService::class.java)
    ).create()
    val service = controller.startCommand(0, 1).get()

    registry.begin("b", WorkKind.TERMINAL, "second task")
    controller.startCommand(0, 2)

    assertEquals(
      "each start is answered in full",
      "2 tasks running",
      foregroundNotification(service)?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
    )
  }

  @Test
  fun `work waiting on an answer posts its own alerting notice`() {
    registry.begin("turn-1", WorkKind.AGENT_TURN, "Agent task", detail = "reading files")
    registry.setAttention("turn-1", needed = true, reason = "Allow git push?")

    startWith()

    val posted = shadowOf(notificationManager()).allNotifications
      .firstOrNull { it.channelId == WorkNotifications.CHANNEL_ATTENTION }
    assertNotNull("a turn parked on an approval has to reach the user", posted)
    assertEquals(
      "Allow git push?",
      posted!!.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
    )
  }

  private fun notificationManager(): android.app.NotificationManager =
    context.getSystemService(android.app.NotificationManager::class.java)
}
