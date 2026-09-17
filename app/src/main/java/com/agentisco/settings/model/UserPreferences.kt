package com.agentisco.settings.model

import kotlinx.serialization.Serializable

/**
 * User preferences stored in DataStore
 */
@Serializable
data class UserPreferences(
    val autoUpdateEnabled: Boolean = true,
    val lastUpdateCheck: Long = 0,
    val lastUpdateVersionCode: Int = 0
)
