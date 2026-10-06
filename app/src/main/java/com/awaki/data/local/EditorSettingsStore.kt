package com.awaki.data.local

import android.content.Context
import com.awaki.editor.model.EditorSettings
import org.json.JSONObject
import java.io.File

/**
 * Durable editor preferences stored as JSON under the app-private dir.
 *
 * The editor had been configured from a plain `MutableStateFlow`, so every setting
 * reverted to its default when the process died — which is invisible while the only
 * way to change one is a panel inside the editor you are already looking at, and
 * obvious as soon as Settings offers the same knobs app-wide.
 *
 * Degrades to in-memory operation when [context] is null (tests / previews).
 */
class EditorSettingsStore(private val context: Context? = null) {

  private val dir: File? = context?.getDir("awaki", Context.MODE_PRIVATE)
  private val configFile: File? get() = dir?.resolve("editor_settings.json")

  private var cached: EditorSettings? = null

  @Synchronized
  fun get(): EditorSettings {
    cached?.let { return it }
    val loaded = configFile?.takeIf { it.isFile }?.let { file ->
      runCatching {
        val obj = JSONObject(file.readText())
        val defaults = EditorSettings()
        EditorSettings(
          fontSize = obj.optInt("fontSize", defaults.fontSize),
          lineHeightMultiplier = obj.optDouble("lineHeightMultiplier", defaults.lineHeightMultiplier.toDouble())
            .toFloat(),
          tabSize = obj.optInt("tabSize", defaults.tabSize),
          useSpaces = obj.optBoolean("useSpaces", defaults.useSpaces),
          wordWrap = obj.optBoolean("wordWrap", defaults.wordWrap),
          showLineNumbers = obj.optBoolean("showLineNumbers", defaults.showLineNumbers),
          highlightActiveLine = obj.optBoolean("highlightActiveLine", defaults.highlightActiveLine),
          bracketMatching = obj.optBoolean("bracketMatching", defaults.bracketMatching),
          codeFolding = obj.optBoolean("codeFolding", defaults.codeFolding),
          syntaxThemeName = obj.optString("syntaxThemeName", defaults.syntaxThemeName),
          autoSave = obj.optBoolean("autoSave", defaults.autoSave),
          formatOnSave = obj.optBoolean("formatOnSave", defaults.formatOnSave),
          showMinimap = obj.optBoolean("showMinimap", defaults.showMinimap),
          touchShortcutsExpanded = obj.optBoolean("touchShortcutsExpanded", defaults.touchShortcutsExpanded)
        )
      }.getOrNull()
    } ?: EditorSettings()
    cached = loaded
    return loaded
  }

  @Synchronized
  fun update(transform: (EditorSettings) -> EditorSettings): EditorSettings {
    val next = transform(get())
    cached = next
    runCatching {
      val file = configFile ?: return@runCatching
      file.writeText(
        JSONObject()
          .put("fontSize", next.fontSize)
          .put("lineHeightMultiplier", next.lineHeightMultiplier.toDouble())
          .put("tabSize", next.tabSize)
          .put("useSpaces", next.useSpaces)
          .put("wordWrap", next.wordWrap)
          .put("showLineNumbers", next.showLineNumbers)
          .put("highlightActiveLine", next.highlightActiveLine)
          .put("bracketMatching", next.bracketMatching)
          .put("codeFolding", next.codeFolding)
          .put("syntaxThemeName", next.syntaxThemeName)
          .put("autoSave", next.autoSave)
          .put("formatOnSave", next.formatOnSave)
          .put("showMinimap", next.showMinimap)
          .put("touchShortcutsExpanded", next.touchShortcutsExpanded)
          .toString(2)
      )
    }
    return next
  }
}
