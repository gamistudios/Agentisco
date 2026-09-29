package com.agentisco.background

/**
 * The permission checklist behind reliable background execution, expressed as
 * plain data so the Android version matrix can be tested without a device.
 *
 * Nothing here talks to the framework; [BackgroundPermissions] fills in the
 * snapshot from real system state and runs the requests.
 */
enum class RequirementStatus {
  /** Already satisfied - nothing to ask. */
  GRANTED,

  /** The user can fix this, and Agentisco cannot. */
  ACTION_REQUIRED,

  /** This Android version has no such restriction. */
  NOT_SUPPORTED,

  /** The user chose not to use it; Agentisco will not ask again. */
  DISABLED_BY_USER
}

enum class RequirementAction {
  NONE,
  REQUEST_NOTIFICATIONS,
  OPEN_NOTIFICATION_SETTINGS,
  REQUEST_BATTERY_EXEMPTION,
  OPEN_BATTERY_SETTINGS,
  OPEN_BACKGROUND_DATA_SETTINGS,
  OPEN_APP_SETTINGS
}

enum class RequirementKey { NOTIFICATIONS, FOREGROUND_SERVICE, BATTERY_EXEMPTION, BACKGROUND_DATA }

data class BackgroundRequirement(
  val key: RequirementKey,
  val title: String,
  val description: String,
  val status: RequirementStatus,
  val action: RequirementAction,
  /** A blocking gap means background work will be killed or stalled without it. */
  val blocking: Boolean
)

/** Real system state, read by [BackgroundPermissions]. */
data class BackgroundPermissionSnapshot(
  val notificationsEnabled: Boolean,
  val batteryExempt: Boolean,
  val backgroundDataRestricted: Boolean,
  val foregroundServiceAllowed: Boolean
)

/**
 * Builds the checklist for one device.
 *
 * [allowBackgroundExecution] is the user's master switch: when it is off the
 * whole feature is declined on purpose, so no row may read as an error.
 */
fun buildBackgroundRequirements(
  sdkInt: Int,
  snapshot: BackgroundPermissionSnapshot,
  allowBackgroundExecution: Boolean
): List<BackgroundRequirement> {
  val notificationRow = if (!allowBackgroundExecution) {
    BackgroundRequirement(
      key = RequirementKey.NOTIFICATIONS,
      title = "Notifications",
      description = "Background execution is off, so no work notification is posted.",
      status = RequirementStatus.DISABLED_BY_USER,
      action = RequirementAction.NONE,
      blocking = false
    )
  } else if (BackgroundPolicy.requiresNotificationPermission(sdkInt)) {
    BackgroundRequirement(
      key = RequirementKey.NOTIFICATIONS,
      title = "Notifications",
      description = if (snapshot.notificationsEnabled) {
        "Allowed. The ongoing task notification shows what Agentisco is doing while you are away."
      } else {
        "Blocked. Android 13 and newer hide every notification, including the one the foreground service needs to stay listed as active."
      },
      status = if (snapshot.notificationsEnabled) RequirementStatus.GRANTED else RequirementStatus.ACTION_REQUIRED,
      action = if (snapshot.notificationsEnabled) {
        RequirementAction.OPEN_NOTIFICATION_SETTINGS
      } else {
        RequirementAction.REQUEST_NOTIFICATIONS
      },
      blocking = !snapshot.notificationsEnabled
    )
  } else {
    BackgroundRequirement(
      key = RequirementKey.NOTIFICATIONS,
      title = "Notifications",
      description = if (snapshot.notificationsEnabled) {
        "Allowed by the system notification setting."
      } else {
        "Muted in the system app settings, so the task notification stays hidden."
      },
      status = if (snapshot.notificationsEnabled) RequirementStatus.GRANTED else RequirementStatus.ACTION_REQUIRED,
      action = RequirementAction.OPEN_NOTIFICATION_SETTINGS,
      blocking = !snapshot.notificationsEnabled
    )
  }

  val serviceRow = if (!allowBackgroundExecution) {
    BackgroundRequirement(
      key = RequirementKey.FOREGROUND_SERVICE,
      title = "Foreground service",
      description = "Background execution is off, so the service never starts.",
      status = RequirementStatus.DISABLED_BY_USER,
      action = RequirementAction.NONE,
      blocking = false
    )
  } else {
    BackgroundRequirement(
      key = RequirementKey.FOREGROUND_SERVICE,
      title = "Foreground service",
      description = if (snapshot.foregroundServiceAllowed) {
        if (BackgroundPolicy.requiresForegroundServicePermission(sdkInt)) {
          "Granted at install. Agentisco raises it only while a task, build or download is live."
        } else {
          "Available. Agentisco raises it only while a task, build or download is live."
        }
      } else {
        "The system is refusing to keep the service in the foreground; the app may still be killed as soon as it leaves the screen."
      },
      status = if (snapshot.foregroundServiceAllowed) RequirementStatus.GRANTED else RequirementStatus.ACTION_REQUIRED,
      action = if (snapshot.foregroundServiceAllowed) RequirementAction.NONE else RequirementAction.OPEN_APP_SETTINGS,
      blocking = !snapshot.foregroundServiceAllowed
    )
  }

  val batteryRow = when {
    !allowBackgroundExecution -> BackgroundRequirement(
      key = RequirementKey.BATTERY_EXEMPTION,
      title = "Unrestricted battery",
      description = "Background execution is off, so battery optimization is irrelevant.",
      status = RequirementStatus.DISABLED_BY_USER,
      action = RequirementAction.NONE,
      blocking = false
    )
    !BackgroundPolicy.supportsBatteryExemptionRequest(sdkInt) -> BackgroundRequirement(
      key = RequirementKey.BATTERY_EXEMPTION,
      title = "Unrestricted battery",
      description = "This Android version has no battery optimization to opt out of.",
      status = RequirementStatus.NOT_SUPPORTED,
      action = RequirementAction.NONE,
      blocking = false
    )
    else -> BackgroundRequirement(
      key = RequirementKey.BATTERY_EXEMPTION,
      title = "Unrestricted battery",
      description = if (snapshot.batteryExempt) {
        "Allowed. Doze can no longer freeze Agentisco's network and CPU while a long task runs."
      } else {
        "Not allowed. In deep idle Android defers network access and wakes, which stalls a turn that is waiting on the model or a download."
      },
      status = if (snapshot.batteryExempt) RequirementStatus.GRANTED else RequirementStatus.ACTION_REQUIRED,
      action = if (snapshot.batteryExempt) {
        RequirementAction.OPEN_BATTERY_SETTINGS
      } else {
        RequirementAction.REQUEST_BATTERY_EXEMPTION
      },
      blocking = !snapshot.batteryExempt
    )
  }

  val dataRow = if (!allowBackgroundExecution) {
    BackgroundRequirement(
      key = RequirementKey.BACKGROUND_DATA,
      title = "Background data",
      description = "Background execution is off, so background data is irrelevant.",
      status = RequirementStatus.DISABLED_BY_USER,
      action = RequirementAction.NONE,
      blocking = false
    )
  } else if (sdkInt < 24) {
    BackgroundRequirement(
      key = RequirementKey.BACKGROUND_DATA,
      title = "Background data",
      description = "This Android version has no per-app background data restriction.",
      status = RequirementStatus.NOT_SUPPORTED,
      action = RequirementAction.NONE,
      blocking = false
    )
  } else {
    BackgroundRequirement(
      key = RequirementKey.BACKGROUND_DATA,
      title = "Background data",
      description = if (!snapshot.backgroundDataRestricted) {
        "Allowed. Agentisco keeps streaming from the model provider while it is not on screen."
      } else {
        "Restricted. With background data turned off for the app, a turn that is mid-response fails as soon as the screen is off."
      },
      status = if (!snapshot.backgroundDataRestricted) RequirementStatus.GRANTED else RequirementStatus.ACTION_REQUIRED,
      action = if (!snapshot.backgroundDataRestricted) {
        RequirementAction.OPEN_BACKGROUND_DATA_SETTINGS
      } else {
        RequirementAction.OPEN_BACKGROUND_DATA_SETTINGS
      },
      blocking = snapshot.backgroundDataRestricted
    )
  }

  return listOf(notificationRow, serviceRow, batteryRow, dataRow)
}
