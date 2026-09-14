package com.agentisco.data.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One registered workspace/project backed by a real folder on the device. */
data class ProjectRegistryEntry(
  val id: String,
  val name: String,
  val description: String,
  val rootPath: String,
  val createdAt: Long,
  val lastOpenedAt: Long,
  val imported: Boolean,
  /** The folder the project was imported from ("" for app-created projects). */
  val sourcePath: String = "",
  /** Mirror workspace changes back to [sourcePath] automatically. */
  val autoSync: Boolean = true
)

/**
 * Durable registry of projects and their real root folders. The folder path is
 * the source of truth — everything else (files, terminal, git, agent sessions)
 * is keyed to it. Stored as JSON under the app-private dir; degrades to
 * in-memory operation when [context] is null (unit tests / previews).
 */
class ProjectRegistryStore(private val context: Context? = null) {

  private data class RegistryState(
    val entries: MutableList<ProjectRegistryEntry> = mutableListOf(),
    val recentLocations: MutableList<String> = mutableListOf()
  )

  private val dir: File? = context?.getDir("agentisco", Context.MODE_PRIVATE)
  private val configFile: File? get() = dir?.resolve("projects.json")

  private val state = RegistryState()

  val hasPersistence: Boolean get() = context != null

  init {
    load()
  }

  @Synchronized
  fun all(): List<ProjectRegistryEntry> = state.entries.toList()

  @Synchronized
  fun byPath(rootPath: String): ProjectRegistryEntry? =
    state.entries.firstOrNull { it.rootPath == rootPath }

  @Synchronized
  fun recentLocations(): List<String> = state.recentLocations.toList()

  @Synchronized
  fun upsert(entry: ProjectRegistryEntry) {
    state.entries.removeAll { it.id == entry.id || it.rootPath == entry.rootPath }
    state.entries.add(entry)
    rememberLocation(File(entry.rootPath).parent ?: entry.rootPath)
    save()
  }

  @Synchronized
  fun remove(id: String) {
    state.entries.removeAll { it.id == id }
    save()
  }

  @Synchronized
  fun touch(id: String) {
    val index = state.entries.indexOfFirst { it.id == id }
    if (index >= 0) {
      state.entries[index] = state.entries[index].copy(lastOpenedAt = System.currentTimeMillis())
      save()
    }
  }

  @Synchronized
  fun rename(id: String, name: String, description: String? = null) {
    val index = state.entries.indexOfFirst { it.id == id }
    if (index >= 0) {
      val current = state.entries[index]
      state.entries[index] = current.copy(
        name = name.ifBlank { current.name },
        description = description ?: current.description
      )
      save()
    }
  }

  @Synchronized
  fun setAutoSync(id: String, enabled: Boolean) {
    val index = state.entries.indexOfFirst { it.id == id }
    if (index >= 0) {
      state.entries[index] = state.entries[index].copy(autoSync = enabled)
      save()
    }
  }

  @Synchronized
  fun setSourcePath(id: String, sourcePath: String) {
    val index = state.entries.indexOfFirst { it.id == id }
    if (index >= 0) {
      state.entries[index] = state.entries[index].copy(sourcePath = sourcePath)
      save()
    }
  }

  @Synchronized
  fun rememberLocation(location: String) {
    if (location.isBlank()) return
    state.recentLocations.removeAll { it == location }
    state.recentLocations.add(0, location)
    while (state.recentLocations.size > 5) state.recentLocations.removeAt(state.recentLocations.size - 1)
    save()
  }

  private fun load() {
    val raw = configFile?.takeIf { it.exists() }?.readText() ?: return
    runCatching {
      val obj = JSONObject(raw)
      val entries = mutableListOf<ProjectRegistryEntry>()
      val arr = obj.getJSONArray("projects")
      for (i in 0 until arr.length()) {
        val e = arr.getJSONObject(i)
        entries.add(
          ProjectRegistryEntry(
            id = e.getString("id"),
            name = e.getString("name"),
            description = e.optString("description"),
            rootPath = e.getString("rootPath"),
            createdAt = e.optLong("createdAt"),
            lastOpenedAt = e.optLong("lastOpenedAt"),
            imported = e.optBoolean("imported", false),
            sourcePath = e.optString("sourcePath"),
            autoSync = e.optBoolean("autoSync", true)
          )
        )
      }
      state.entries.clear()
      state.entries.addAll(entries)
      val locations = mutableListOf<String>()
      val locArr = obj.optJSONArray("recentLocations")
      if (locArr != null) {
        for (i in 0 until locArr.length()) locations.add(locArr.getString(i))
      }
      state.recentLocations.clear()
      state.recentLocations.addAll(locations)
    }
  }

  private fun save() {
    val file = configFile ?: return
    runCatching {
      val obj = JSONObject()
      val arr = JSONArray()
      state.entries.forEach { entry ->
        arr.put(
          JSONObject()
            .put("id", entry.id)
            .put("name", entry.name)
            .put("description", entry.description)
            .put("rootPath", entry.rootPath)
            .put("createdAt", entry.createdAt)
            .put("lastOpenedAt", entry.lastOpenedAt)
            .put("imported", entry.imported)
            .put("sourcePath", entry.sourcePath)
            .put("autoSync", entry.autoSync)
        )
      }
      obj.put("projects", arr)
      obj.put("recentLocations", JSONArray(state.recentLocations))
      file.writeText(obj.toString(2))
    }
  }
}
