package com.awaki.settings.model

import kotlinx.serialization.Serializable

/**
 * User preferences stored in DataStore
 */
@Serializable
data class UserPreferences(
    val autoUpdateEnabled: Boolean = true,
    val lastUpdateCheck: Long = 0,
    val lastUpdateVersionCode: Int = 0,
    /** Master switch for running after the screen is off: service + wake lock. */
    val allowBackgroundExecution: Boolean = true,
    /** Keep the CPU awake for compute-bound work. Off lets the device sleep. */
    val backgroundWakeLock: Boolean = true,
    /** Timestamp of the one-time notification permission ask, so it is not nagged. */
    val notificationsAskedAt: Long = 0,
    /**
     * Alerts the user may silence. Each gates exactly one notification the app really
     * posts; the ongoing foreground-service notice is not among them because Android
     * requires it while a foreground service is up, and hiding it is not this app's
     * choice to make.
     */
    /** A turn is parked on an approval the user has not answered. */
    val alertOnApprovalRequested: Boolean = true,
    /** Work died with an earlier process, and nothing will restart it by itself. */
    val alertOnInterruptedWork: Boolean = true,
    /** An update APK finished downloading while the app was off screen. */
    val alertOnUpdateReady: Boolean = true
)
