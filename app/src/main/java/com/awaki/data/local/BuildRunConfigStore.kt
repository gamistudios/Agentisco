package com.awaki.data.local

import android.content.Context
import com.awaki.workspace.buildrun.BuildRunConfig
import com.awaki.workspace.buildrun.BuildRunConfigSource
import com.awaki.workspace.buildrun.BuildStageKind
import org.json.JSONObject
import java.io.File

/**
 * Durable per-project Run & Build configuration (install/build/test/run
 * commands and provenance), keyed by the project's root path. Stored as JSON
 * under the app-private dir; degrades to in-memory operation when [context] is
 * null (unit tests / previews), mirroring [ProjectRegistryStore].
 */
class BuildRunConfigStore(private val context: Context? = null) {

  private val dir: File? = context?.getDir("awaki", Context.MODE_PRIVATE)
  private val configFile: File? get() = dir?.resolve("build_run.json")

  private val entries = mutableMapOf<String, BuildRunConfig>()

  init {
    load()
  }

  @Synchronized
  fun get(projectPath: String): BuildRunConfig? = entries[projectPath]

  @Synchronized
  fun save(config: BuildRunConfig) {
    if (config.projectPath.isBlank()) return
    entries[config.projectPath] = config
    persist()
  }

  @Synchronized
  fun remove(projectPath: String) {
    entries.remove(projectPath)
    persist()
  }

  private fun load() {
    val raw = configFile?.takeIf { it.exists() }?.readText() ?: return
    runCatching {
      val projects = JSONObject(raw).optJSONObject("projects") ?: return
      projects.keys().forEach { path ->
        val entry = projects.optJSONObject(path) ?: return@forEach
        entries[path] = decode(path, entry)
      }
    }
  }

  private fun decode(path: String, obj: JSONObject): BuildRunConfig = BuildRunConfig(
    projectPath = path,
    commands = commandsOf(obj, "commands"),
    runPort = obj.optInt("runPort", -1).takeIf { it in 1..65535 },
    source = enumByName<BuildRunConfigSource>(obj.optString("source")) ?: BuildRunConfigSource.MANUAL,
    detectedCommands = commandsOf(obj, "detectedCommands").takeIf { it.isNotEmpty() },
    detectedRunPort = obj.optInt("detectedRunPort", -1).takeIf { it in 1..65535 },
    detectedSource = enumByName<BuildRunConfigSource>(obj.optString("detectedSource")),
    updatedAt = obj.optLong("updatedAt")
  )

  private fun commandsOf(obj: JSONObject, name: String): Map<BuildStageKind, String> {
    val raw = obj.optJSONObject(name) ?: return emptyMap()
    val map = LinkedHashMap<BuildStageKind, String>()
    BuildStageKind.entries.forEach { kind ->
      val command = raw.optString(kind.name)
      if (command.isNotBlank()) map[kind] = command
    }
    return map
  }

  private fun persist() {
    val file = configFile ?: return
    runCatching {
      val projects = JSONObject()
      entries.forEach { (path, config) -> projects.put(path, encode(config)) }
      file.writeText(JSONObject().put("projects", projects).toString(2))
    }
  }

  private fun encode(config: BuildRunConfig): JSONObject {
    fun commandsJson(commands: Map<BuildStageKind, String>?): JSONObject? {
      if (commands == null) return null
      val obj = JSONObject()
      commands.forEach { (kind, command) -> if (command.isNotBlank()) obj.put(kind.name, command) }
      return obj
    }
    return JSONObject().apply {
      commandsJson(config.commands)?.let { put("commands", it) }
      config.runPort?.let { put("runPort", it) }
      put("source", config.source.name)
      commandsJson(config.detectedCommands)?.let { put("detectedCommands", it) }
      config.detectedRunPort?.let { put("detectedRunPort", it) }
      config.detectedSource?.let { put("detectedSource", it.name) }
      put("updatedAt", config.updatedAt)
    }
  }

  private inline fun <reified T : Enum<T>> enumByName(name: String): T? =
    enumValues<T>().firstOrNull { it.name == name }
}
