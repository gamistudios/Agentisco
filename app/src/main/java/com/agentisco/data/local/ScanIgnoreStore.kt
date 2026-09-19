package com.agentisco.data.local

import android.content.Context
import com.agentisco.workspace.filesystem.ProjectFileSystem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * How the scan-exclusion list is composed.
 *
 * - `useCustomListOnly = false`: the built-in defaults apply, minus any
 *   entries the user removed, plus any entries the user added.
 * - `useCustomListOnly = true`: ONLY [extraDirs] are ignored — the built-in
 *   defaults are fully overridden.
 */
data class ScanIgnoreSettings(
  val useCustomListOnly: Boolean = false,
  val extraDirs: List<String> = emptyList(),
  val removedDefaults: List<String> = emptyList()
)

/**
 * Durable scan-exclusion settings, stored as JSON under the app-private dir.
 * Controls which folder names are skipped by project scans, searches and
 * imports (see [ProjectFileSystem.ignoredDirs]). Degrades to in-memory
 * operation when [context] is null (unit tests / previews).
 */
class ScanIgnoreStore(private val context: Context? = null) {

  private val dir: File? = context?.getDir("agentisco", Context.MODE_PRIVATE)
  private val configFile: File? get() = dir?.resolve("scan_ignore.json")

  private var cached: ScanIgnoreSettings? = null

  @Synchronized
  fun get(): ScanIgnoreSettings {
    cached?.let { return it }
    val loaded = configFile?.takeIf { it.isFile }?.let { file ->
      runCatching {
        val obj = JSONObject(file.readText())
        ScanIgnoreSettings(
          useCustomListOnly = obj.optBoolean("useCustomListOnly", false),
          extraDirs = stringList(obj.optJSONArray("extraDirs")),
          removedDefaults = stringList(obj.optJSONArray("removedDefaults"))
        )
      }.getOrNull()
    } ?: ScanIgnoreSettings()
    cached = loaded
    return loaded
  }

  @Synchronized
  fun set(settings: ScanIgnoreSettings) {
    val normalized = normalize(settings)
    cached = normalized
    runCatching {
      val file = configFile ?: return
      val obj = JSONObject()
        .put("useCustomListOnly", normalized.useCustomListOnly)
        .put("extraDirs", JSONArray(normalized.extraDirs))
        .put("removedDefaults", JSONArray(normalized.removedDefaults))
      file.writeText(obj.toString(2))
    }
  }

  /** Update the persisted settings, apply them, and return the new state. */
  @Synchronized
  fun update(transform: (ScanIgnoreSettings) -> ScanIgnoreSettings): ScanIgnoreSettings {
    set(transform(get()))
    val settings = get()
    ProjectFileSystem.ignoredDirs = effectiveDirs(settings)
    return settings
  }

  /** Restore the pristine built-in list and apply it. */
  @Synchronized
  fun reset(): ScanIgnoreSettings {
    set(ScanIgnoreSettings())
    ProjectFileSystem.ignoredDirs = ProjectFileSystem.DEFAULT_IGNORED_DIRS
    return get()
  }

  companion object {
    /** Folder names are single path segments — anything else sanitizes to "". */
    fun sanitizeName(raw: String): String {
      val name = raw.trim().trimEnd('/').lowercase()
      val segment = name.substringAfterLast('/')
      return if (segment == name && segment.isNotBlank() && segment != "." && segment != "..") segment else ""
    }

    fun normalize(settings: ScanIgnoreSettings): ScanIgnoreSettings = ScanIgnoreSettings(
      useCustomListOnly = settings.useCustomListOnly,
      extraDirs = settings.extraDirs.map { sanitizeName(it) }.filter { it.isNotBlank() }.distinct(),
      removedDefaults = settings.removedDefaults.map { sanitizeName(it) }.filter { it.isNotBlank() }.distinct()
    )

    /** The folder names actually skipped for the given settings. */
    fun effectiveDirs(settings: ScanIgnoreSettings): Set<String> = when {
      settings.useCustomListOnly -> settings.extraDirs.toSet()
      else -> (ProjectFileSystem.DEFAULT_IGNORED_DIRS - settings.removedDefaults.toSet()) +
        settings.extraDirs.toSet()
    }

    private fun stringList(arr: JSONArray?): List<String> {
      if (arr == null) return emptyList()
      return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }
  }
}
