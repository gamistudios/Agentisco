package com.agentisco.background

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agentisco.settings.store.UserPreferencesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The manual "keep the terminal awake" switch. It is deliberately not a special
 * case in the service: it is an ordinary registry record, so the same policy that
 * decides wake locks and notifications decides it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalHoldTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun execution(registry: WorkRegistry) = BackgroundExecution(
    appContext = context,
    prefs = UserPreferencesStore(context),
    registry = registry,
    journal = WorkJournal(context),
    processToken = "this-process"
  )

  @Test
  fun `holding the terminal registers cpu bound work the service will protect`() {
    val registry = WorkRegistry()
    val execution = execution(registry)

    execution.setTerminalHold(true)
    val record = registry.active.value.single()
    assertEquals(WorkKind.TERMINAL_HOLD, record.kind)
    assertTrue("the hold is CPU bound work", record.kind in BackgroundPolicy.WAKE_LOCK_KINDS)
    assertTrue(
      "a hold the user can turn off from the notice",
      BackgroundPolicy.shouldHoldWakeLock(
        active = registry.active.value,
        allowBackgroundExecution = true,
        wakeLockEnabled = true
      )
    )
    assertTrue(execution.isTerminalHeld())

    execution.setTerminalHold(false)
    assertFalse("switching it off must leave nothing behind", execution.isTerminalHeld())
    assertTrue(registry.active.value.isEmpty())
  }

  @Test
  fun `the notice's stop action clears the switch it came from`() {
    val registry = WorkRegistry()
    val execution = execution(registry)
    execution.setTerminalHold(true)
    val id = registry.active.value.first().id

    assertTrue(registry.cancel(id))
    assertFalse("a switch that looks on while nothing is held is a lie", execution.isTerminalHeld())
    assertTrue(registry.active.value.isEmpty())
  }

  @Test
  fun `holding twice is still one record`() {
    val registry = WorkRegistry()
    val execution = execution(registry)
    execution.setTerminalHold(true)
    execution.setTerminalHold(true)
    assertEquals(1, registry.active.value.size)
    assertTrue(execution.isTerminalHeld())
  }
}
