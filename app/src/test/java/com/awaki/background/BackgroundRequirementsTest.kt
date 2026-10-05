package com.awaki.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android-version matrix behind the Settings checklist: which rows a device
 * of a given API level can even have, what each one says when the user has or
 * has not granted it, and that a declined feature never reads as an error.
 */
class BackgroundRequirementsTest {

  private fun snapshot(
    notifications: Boolean = true,
    battery: Boolean = true,
    data: Boolean = false,
    service: Boolean = true
  ) = BackgroundPermissionSnapshot(
    notificationsEnabled = notifications,
    batteryExempt = battery,
    backgroundDataRestricted = data,
    foregroundServiceAllowed = service
  )

  private fun List<BackgroundRequirement>.row(key: RequirementKey): BackgroundRequirement =
    first { it.key == key }

  private fun keys(sdk: Int, snap: BackgroundPermissionSnapshot = snapshot()): List<BackgroundRequirement> =
    buildBackgroundRequirements(sdk, snap, allowBackgroundExecution = true)

  @Test
  fun `every device gets the same four rows in the same order`() {
    listOf(21, 22, 23, 25, 27, 28, 30, 33, 34, 36).forEach { sdk ->
      val rows = keys(sdk)
      assertEquals(
        "android $sdk must show the whole checklist",
        listOf(
          RequirementKey.NOTIFICATIONS,
          RequirementKey.FOREGROUND_SERVICE,
          RequirementKey.BATTERY_EXEMPTION,
          RequirementKey.BACKGROUND_DATA
        ),
        rows.map { it.key }
      )
    }
  }

  @Test
  fun `a fully granted device shows nothing that needs doing`() {
    val rows = keys(34)
    rows.forEach { row ->
      assertEquals(
        "${row.key} should be satisfied",
        RequirementStatus.GRANTED,
        row.status
      )
      assertFalse("${row.key} must not be reported as blocking", row.blocking)
    }
    assertEquals(
      "a running service needs no button; the rest stay reachable for review",
      listOf(
        RequirementAction.OPEN_NOTIFICATION_SETTINGS,
        RequirementAction.NONE,
        RequirementAction.OPEN_BATTERY_SETTINGS,
        RequirementAction.OPEN_BACKGROUND_DATA_SETTINGS
      ),
      rows.map { it.action }
    )
  }

  @Test
  fun `android 13 asks for the notification permission while older android opens settings`() {
    val denied = snapshot(notifications = false)

    val modern = keys(33, denied).row(RequirementKey.NOTIFICATIONS)
    assertEquals(RequirementStatus.ACTION_REQUIRED, modern.status)
    assertEquals(
      "only 13 and up can be asked in place",
      RequirementAction.REQUEST_NOTIFICATIONS,
      modern.action
    )
    assertTrue("a hidden service notification is a real gap", modern.blocking)

    val legacy = keys(30, denied).row(RequirementKey.NOTIFICATIONS)
    assertEquals(RequirementStatus.ACTION_REQUIRED, legacy.status)
    assertEquals(RequirementAction.OPEN_NOTIFICATION_SETTINGS, legacy.action)
    assertTrue(legacy.blocking)
  }

  @Test
  fun `an allowed notification setting still links to the channel`() {
    val row = keys(34).row(RequirementKey.NOTIFICATIONS)
    assertEquals(RequirementAction.OPEN_NOTIFICATION_SETTINGS, row.action)
  }

  // ——— battery ———

  @Test
  fun `pre-marshmallow android has no battery optimization to report`() {
    val row = keys(22, snapshot(battery = false)).row(RequirementKey.BATTERY_EXEMPTION)
    assertEquals(
      "nothing to ask on a version that never optimized",
      RequirementStatus.NOT_SUPPORTED,
      row.status
    )
    assertEquals(RequirementAction.NONE, row.action)
    assertFalse("an absent restriction must not block", row.blocking)
  }

  @Test
  fun `a non exempt app is asked once and can fall back to the list`() {
    val row = keys(23, snapshot(battery = false)).row(RequirementKey.BATTERY_EXEMPTION)
    assertEquals(RequirementStatus.ACTION_REQUIRED, row.status)
    assertEquals(RequirementAction.REQUEST_BATTERY_EXEMPTION, row.action)
    assertTrue(row.blocking)

    val exempt = keys(23).row(RequirementKey.BATTERY_EXEMPTION)
    assertEquals(RequirementAction.OPEN_BATTERY_SETTINGS, exempt.action)
  }

  // ——— background data ———

  @Test
  fun `background data is only a row worth checking from android 7 up`() {
    val old = keys(23, snapshot(data = true)).row(RequirementKey.BACKGROUND_DATA)
    assertEquals(RequirementStatus.NOT_SUPPORTED, old.status)
    assertEquals(RequirementAction.NONE, old.action)

    val restricted = keys(24, snapshot(data = true)).row(RequirementKey.BACKGROUND_DATA)
    assertEquals(RequirementStatus.ACTION_REQUIRED, restricted.status)
    assertEquals(RequirementAction.OPEN_BACKGROUND_DATA_SETTINGS, restricted.action)
    assertTrue(restricted.blocking)
  }

  @Test
  fun `an unrestricted app can still reach the screen`() {
    val row = keys(34).row(RequirementKey.BACKGROUND_DATA)
    assertEquals(RequirementStatus.GRANTED, row.status)
    assertEquals(RequirementAction.OPEN_BACKGROUND_DATA_SETTINGS, row.action)
  }

  // ——— the foreground service ———

  @Test
  fun `a refused foreground start is reported as the blocking gap it is`() {
    val row = keys(34, snapshot(service = false)).row(RequirementKey.FOREGROUND_SERVICE)
    assertEquals(RequirementStatus.ACTION_REQUIRED, row.status)
    assertEquals(RequirementAction.OPEN_APP_SETTINGS, row.action)
    assertTrue(row.blocking)
  }

  @Test
  fun `a granted service needs no action`() {
    val row = keys(34).row(RequirementKey.FOREGROUND_SERVICE)
    assertEquals(RequirementStatus.GRANTED, row.status)
    assertEquals(RequirementAction.NONE, row.action)
  }

  // ——— the master switch ———

  @Test
  fun `turning the feature off turns every ask off with it`() {
    val rows = buildBackgroundRequirements(
      sdkInt = 34,
      snapshot = snapshot(notifications = false, battery = false, data = true, service = false),
      allowBackgroundExecution = false
    )
    rows.forEach { row ->
      assertEquals(
        "${row.key} is declined on purpose, not broken",
        RequirementStatus.DISABLED_BY_USER,
        row.status
      )
      assertEquals("${row.key} must offer no button", RequirementAction.NONE, row.action)
      assertFalse("${row.key} must not be blocking", row.blocking)
    }
  }

  @Test
  fun `each row explains itself in one line the user can act on`() {
    val rows = keys(34, snapshot(notifications = false, battery = false, data = true, service = false))
    rows.forEach { row ->
      assertNotNull(row.title)
      assertTrue(
        "${row.key} needs a description long enough to explain itself",
        row.description.length > 20
      )
    }
  }
}
