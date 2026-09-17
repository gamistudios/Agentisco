package com.agentisco.settings.store

import android.content.Context
import com.agentisco.settings.model.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent store for user preferences (including auto-update toggle), backed
 * by a small JSON file in app-private storage.
 */
class UserPreferencesStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val preferencesFile = File(context.filesDir, "user_preferences.json")

    private val _preferences = MutableStateFlow(UserPreferences())
    val preferences: StateFlow<UserPreferences> = _preferences.asStateFlow()

    init {
        loadPreferences()
    }

    private fun loadPreferences() {
        if (preferencesFile.exists()) {
            try {
                val content = preferencesFile.readText()
                _preferences.value = json.decodeFromString<UserPreferences>(content)
            } catch (e: Exception) {
                e.printStackTrace()
                _preferences.value = UserPreferences()
            }
        }
    }

    fun updatePreferences(transform: (UserPreferences) -> UserPreferences) {
        val newPrefs = transform(_preferences.value)
        _preferences.value = newPrefs
        try {
            preferencesFile.writeText(json.encodeToString(newPrefs))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
