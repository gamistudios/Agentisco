package com.agentisco.settings.model

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
    val notificationsAskedAt: Long = 0
)
