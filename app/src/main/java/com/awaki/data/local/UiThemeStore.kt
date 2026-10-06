package com.awaki.data.local

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Durable UI theme selection, stored as the theme's key rather than its colours: the
 * key resolves against the app's own catalogue, so a palette edit reaches the device
 * without touching this file, and a key left behind by a newer build falls back to the
 * default instead of painting a theme that no longer exists.
 *
 * Degrades to in-memory operation when [context] is null (tests / previews).
 */
class UiThemeStore(private val context: Context? = null) {

  private val dir: File? = context?.getDir("awaki", Context.MODE_PRIVATE)
  private val configFile: File? get() = dir?.resolve("ui_theme.json")

  private var cached: String? = null
  private var loaded = false

  /** The theme the user picked, or null while nobody has picked one. */
  @Synchronized
  fun get(): String? {
    if (!loaded) {
      cached = configFile?.takeIf { it.isFile }?.let { file ->
        runCatching { JSONObject(file.readText()).optString(KEY).takeIf { it.isNotEmpty() } }
          .getOrNull()
      }
      loaded = true
    }
    return cached
  }

  @Synchronized
  fun setKey(key: String) {
    cached = key
    loaded = true
    runCatching {
      val file = configFile ?: return@runCatching
      file.writeText(JSONObject().put(KEY, key).toString(2))
    }
  }

  companion object {
    const val KEY = "themeKey"
  }
}
