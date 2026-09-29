package com.agentisco.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The battery and service policy behind background execution: who gets a
 * foreground service, who gets the CPU kept awake, for how long, and when the
 * user may be asked for a permission.
 */
class BackgroundPolicyTest {

  private fun work(
    id: String,
    kind: WorkKind,
    startedAt: Long = 1_000L,
    label: String = id,
    detail: String = "",
    needsAttention: Boolean = false
  ) = ActiveWork(
    id = id,
    kind = kind,
    label = label,
    detail = detail,
    startedAt = startedAt,
    needsAttention = needsAttention
  )

  // ——— the foreground service ———

  @Test
  fun `the service only runs while work is live and the user allowed background work`() {
    val live = listOf(work("a", WorkKind.AGENT_TURN))
    assertTrue(
      "live work must raise the service",
      BackgroundPolicy.shouldRunForegroundService(live, allowBackgroundExecution = true)
    )
    assertFalse(
      "an empty registry must not leave a service running",
      BackgroundPolicy.shouldRunForegroundService(emptyList(), allowBackgroundExecution = true)
    )
    assertFalse(
      "the master switch wins over everything",
      BackgroundPolicy.shouldRunForegroundService(live, allowBackgroundExecution = false)
    )
  }

  // ——— the wake lock ———

  @Test
  fun `cpu bound work holds the wake lock`() {
    val cpuKinds = listOf(
      WorkKind.AGENT_TURN,
      WorkKind.TERMINAL,
      WorkKind.BOOTSTRAP,
      WorkKind.TERMINAL_HOLD
    )
    cpuKinds.forEach { kind ->
      assertTrue(
        "$kind burns CPU and must keep it",
        BackgroundPolicy.shouldHoldWakeLock(
          active = listOf(work("x", kind)),
          allowBackgroundExecution = true,
          wakeLockEnabled = true
        )
      )
    }
  }

  @Test
  fun `a download never holds the wake lock`() {
    // It waits on a socket; Doze costs it seconds and the battery pays for hours.
    assertFalse(
      "a download must not keep the CPU awake",
      BackgroundPolicy.shouldHoldWakeLock(
        active = listOf(work("d", WorkKind.UPDATE_DOWNLOAD)),
        allowBackgroundExecution = true,
        wakeLockEnabled = true
      )
    )
  }

  @Test
  fun `the wake lock honours both user switches`() {
    val live = listOf(work("a", WorkKind.AGENT_TURN))
    assertFalse(
      "no wake lock when the user turned it off",
      BackgroundPolicy.shouldHoldWakeLock(live, allowBackgroundExecution = true, wakeLockEnabled = false)
    )
    assertFalse(
      "no wake lock when background execution itself is off",
      BackgroundPolicy.shouldHoldWakeLock(live, allowBackgroundExecution = false, wakeLockEnabled = true)
    )
  }

  @Test
  fun `one cpu bound record among downloads is enough to hold the lock`() {
    val mixed = listOf(
      work("d", WorkKind.UPDATE_DOWNLOAD),
      work("t", WorkKind.TERMINAL)
    )
    assertTrue(
      "the build in the mix needs the CPU",
      BackgroundPolicy.shouldHoldWakeLock(mixed, allowBackgroundExecution = true, wakeLockEnabled = true)
    )
  }

  @Test
  fun `a lock slice is never longer than the slice itself`() {
    assertEquals(
      "a fresh run is owed one whole slice",
      BackgroundPolicy.WAKE_LOCK_SLICE_MS,
      BackgroundPolicy.wakeLockRenewalMs(workStartedAt = 0L, now = 0L)
    )
  }

  @Test
  fun `the last slice is clipped to what the budget still allows`() {
    val almostSpent = BackgroundPolicy.WAKE_LOCK_BUDGET_MS - 30_000L
    assertEquals(
      "only the remaining 30s may be taken",
      30_000L,
      BackgroundPolicy.wakeLockRenewalMs(workStartedAt = 0L, now = almostSpent)
    )
  }

  @Test
  fun `a spent budget takes no lock at all`() {
    assertTrue(
      "past six hours the device is allowed to sleep again",
      BackgroundPolicy.wakeLockRenewalMs(
        workStartedAt = 0L,
        now = BackgroundPolicy.WAKE_LOCK_BUDGET_MS
      ) <= 0L
    )
  }

  // ——— what the notification says ———

  @Test
  fun `newest work is shown first`() {
    val ordered = BackgroundPolicy.notificationOrder(
      listOf(
        work("old", WorkKind.AGENT_TURN, startedAt = 1_000L),
        work("new", WorkKind.TERMINAL, startedAt = 9_000L),
        work("mid", WorkKind.BOOTSTRAP, startedAt = 5_000L)
      )
    )
    assertEquals(listOf("new", "mid", "old"), ordered.map { it.id })
  }

  @Test
  fun `a single task is titled by what it is`() {
    assertEquals(
      "Agent is working",
      BackgroundPolicy.summaryTitle(listOf(work("a", WorkKind.AGENT_TURN)))
    )
    assertEquals(
      "Downloading an update",
      BackgroundPolicy.summaryTitle(listOf(work("d", WorkKind.UPDATE_DOWNLOAD)))
    )
    assertEquals(
      "Setting up the Linux environment",
      BackgroundPolicy.summaryTitle(listOf(work("b", WorkKind.BOOTSTRAP)))
    )
    assertEquals(
      "a running command shows its own label",
      "pnpm dev",
      BackgroundPolicy.summaryTitle(
        listOf(work("t", WorkKind.TERMINAL, label = "pnpm dev"))
      )
    )
  }

  @Test
  fun `several tasks are counted rather than titled`() {
    val many = listOf(
      work("a", WorkKind.AGENT_TURN),
      work("d", WorkKind.UPDATE_DOWNLOAD)
    )
    assertEquals("2 tasks running", BackgroundPolicy.summaryTitle(many))
  }

  @Test
  fun `an empty registry says so without a crash`() {
    assertEquals("Agentisco is idle", BackgroundPolicy.summaryTitle(emptyList()))
    assertEquals("Nothing is running", BackgroundPolicy.summaryText(emptyList()))
  }

  @Test
  fun `work waiting on the user outranks the progress line`() {
    val active = listOf(
      work("turn", WorkKind.AGENT_TURN, startedAt = 1_000L, detail = "running 12 tests"),
      work(
        "blocked",
        WorkKind.AGENT_TURN,
        startedAt = 9_000L,
        detail = "Allow git push?",
        needsAttention = true
      )
    )
    assertTrue(BackgroundPolicy.needsAttention(active))
    assertEquals("Allow git push?", BackgroundPolicy.summaryText(active))
  }

  @Test
  fun `the second line folds the rest of the work into a count`() {
    val active = listOf(
      work("a", WorkKind.TERMINAL, startedAt = 1_000L, label = "apt install"),
      work("b", WorkKind.AGENT_TURN, startedAt = 2_000L, detail = "reading 4 files"),
      work("c", WorkKind.UPDATE_DOWNLOAD, startedAt = 3_000L, detail = "61% of the APK")
    )
    assertEquals("61% of the APK (+2 more)", BackgroundPolicy.summaryText(active))
  }

  @Test
  fun `a record with no detail falls back to its label`() {
    val text = BackgroundPolicy.summaryText(
      listOf(work("a", WorkKind.TERMINAL, label = "gradle build"))
    )
    assertEquals("gradle build", text)
  }

  @Test
  fun `attention without a reason still reads as a question`() {
    val text = BackgroundPolicy.summaryText(
      listOf(work("a", WorkKind.AGENT_TURN, needsAttention = true))
    )
    assertEquals("Agentisco needs your answer", text)
  }

  // ——— when the user may be asked ———

  @Test
  fun `only android 13 and newer gate notifications`() {
    assertFalse(BackgroundPolicy.requiresNotificationPermission(32))
    assertTrue(BackgroundPolicy.requiresNotificationPermission(33))
    assertTrue(BackgroundPolicy.requiresNotificationPermission(36))
  }

  @Test
  fun `only android 6 and newer have battery optimization to opt out of`() {
    assertFalse(BackgroundPolicy.supportsBatteryExemptionRequest(22))
    assertTrue(BackgroundPolicy.supportsBatteryExemptionRequest(23))
  }

  @Test
  fun `android 9 and newer need the foreground service permission`() {
    assertFalse(BackgroundPolicy.requiresForegroundServicePermission(27))
    assertTrue(BackgroundPolicy.requiresForegroundServicePermission(28))
  }

  @Test
  fun `the notification ask happens once, only on android 13 and only with work`() {
    assertTrue(
      "live work on a device that hides notifications must ask",
      BackgroundPolicy.shouldPromptForNotifications(
        sdkInt = 34,
        notificationsGranted = false,
        askedBefore = false,
        hasActiveWork = true
      )
    )
    assertFalse(
      "an already granted permission needs no ask",
      BackgroundPolicy.shouldPromptForNotifications(34, true, false, true)
    )
    assertFalse(
      "a refusal is never re-asked",
      BackgroundPolicy.shouldPromptForNotifications(34, false, true, true)
    )
    assertFalse(
      "nothing is asked before there is work to protect",
      BackgroundPolicy.shouldPromptForNotifications(34, false, false, false)
    )
    assertFalse(
      "older android has no such prompt",
      BackgroundPolicy.shouldPromptForNotifications(30, false, false, true)
    )
  }

  // ——— recovery window ———

  @Test
  fun `interrupted work is offered back only inside the recovery window`() {
    val now = 100_000_000L
    assertTrue(BackgroundPolicy.isRecoverable(now - 60_000L, now))
    assertTrue(
      "the edge of the window still counts",
      BackgroundPolicy.isRecoverable(now - BackgroundPolicy.RECOVERY_WINDOW_MS, now)
    )
    assertFalse(
      "yesterday's build is not worth a notification",
      BackgroundPolicy.isRecoverable(now - BackgroundPolicy.RECOVERY_WINDOW_MS - 1L, now)
    )
    assertFalse(
      "a clock that moved backwards is not a recoverable record",
      BackgroundPolicy.isRecoverable(now + 1L, now)
    )
  }
}
