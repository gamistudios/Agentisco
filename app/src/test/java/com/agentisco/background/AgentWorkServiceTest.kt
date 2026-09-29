package com.agentisco.background

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.agentisco.AgentiscoApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The service's own promises: it only goes foreground when there is live work, it
 * says what that work is, and a Stop button reaches exactly the task it names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentWorkServiceTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private lateinit var registry: WorkRegistry

  @Before
  fun setUp() {
    val app = context.applicationContext as AgentiscoApplication
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
  fun `an empty registry raises no foreground notification`() {
    val service = startWith()
    assertNull("no work, no service notice", foregroundNotification(service))
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
