package com.awaki.data.local

import android.content.Context
import com.awaki.agent.model.AgentPermissions
import com.awaki.agent.model.PermissionMode
import org.json.JSONObject
import java.io.File

/**
 * Durable agent permissions, stored as JSON under the app-private dir.
 *
 * Every one of these seventeen values used to live only in a `MutableStateFlow`, so
 * the whole page of them — which tools the agent may use, how often it asks, how long
 * it may loop — reverted to the shipped defaults the moment the process died. That is
 * what "my permission changes don't stick" was: the settings rows wrote to a flow that
 * nothing remembered.
 *
 * A permission record is one decision, not seventeen: it is loaded, saved and read
 * back as a whole [AgentPermissions], so a value added to the data class has to be
 * listed here too and cannot quietly become the one that never persists.
 *
 * Degrades to in-memory operation when [context] is null (tests / previews).
 */
class PermissionsStore(private val context: Context? = null) {

  private val dir: File? = context?.getDir("awaki", Context.MODE_PRIVATE)
  private val configFile: File? get() = dir?.resolve("permissions.json")

  private var cached: AgentPermissions? = null

  @Synchronized
  fun get(): AgentPermissions {
    cached?.let { return it }
    val loaded = configFile?.takeIf { it.isFile }?.let { file ->
      runCatching {
        val obj = JSONObject(file.readText())
        val defaults = AgentPermissions()
        AgentPermissions(
          fileEditing = mode(obj, "fileEditing", defaults.fileEditing),
          terminalCommands = mode(obj, "terminalCommands", defaults.terminalCommands),
          networkAccess = obj.optBoolean("networkAccess", defaults.networkAccess),
          maxToolIterations = iterations(obj, defaults.maxToolIterations),
          readFiles = obj.optBoolean("readFiles", defaults.readFiles),
          createFiles = obj.optBoolean("createFiles", defaults.createFiles),
          modifyFiles = obj.optBoolean("modifyFiles", defaults.modifyFiles),
          deleteFiles = obj.optBoolean("deleteFiles", defaults.deleteFiles),
          runCommands = obj.optBoolean("runCommands", defaults.runCommands),
          installPackages = obj.optBoolean("installPackages", defaults.installPackages),
          networkCommands = obj.optBoolean("networkCommands", defaults.networkCommands),
          gitStatus = obj.optBoolean("gitStatus", defaults.gitStatus),
          gitDiff = obj.optBoolean("gitDiff", defaults.gitDiff),
          gitCommit = obj.optBoolean("gitCommit", defaults.gitCommit),
          gitPush = obj.optBoolean("gitPush", defaults.gitPush),
          alwaysAskDangerous = obj.optBoolean("alwaysAskDangerous", defaults.alwaysAskDangerous),
          planMode = obj.optBoolean("planMode", defaults.planMode)
        )
      }.getOrNull()
    } ?: AgentPermissions()
    cached = loaded
    return loaded
  }

  @Synchronized
  fun update(transform: (AgentPermissions) -> AgentPermissions): AgentPermissions {
    val next = transform(get())
    cached = next
    runCatching {
      val file = configFile ?: return@runCatching
      file.writeText(
        JSONObject()
          .put("fileEditing", next.fileEditing.name)
          .put("terminalCommands", next.terminalCommands.name)
          .put("networkAccess", next.networkAccess)
          .put("maxToolIterations", next.maxToolIterations)
          .put("readFiles", next.readFiles)
          .put("createFiles", next.createFiles)
          .put("modifyFiles", next.modifyFiles)
          .put("deleteFiles", next.deleteFiles)
          .put("runCommands", next.runCommands)
          .put("installPackages", next.installPackages)
          .put("networkCommands", next.networkCommands)
          .put("gitStatus", next.gitStatus)
          .put("gitDiff", next.gitDiff)
          .put("gitCommit", next.gitCommit)
          .put("gitPush", next.gitPush)
          .put("alwaysAskDangerous", next.alwaysAskDangerous)
          .put("planMode", next.planMode)
          .toString(2)
      )
    }
    return next
  }

  /** A mode written by a future build, or a typo in the file, falls back to default. */
  private fun mode(obj: JSONObject, key: String, default: PermissionMode): PermissionMode =
    obj.optString(key, default.name).let { name ->
      PermissionMode.entries.firstOrNull { it.name == name } ?: default
    }

  /** The unlimited sentinel is a real value; a budget that is not positive is junk. */
  private fun iterations(obj: JSONObject, default: Int): Int =
    if (!obj.has("maxToolIterations")) default
    else obj.optInt("maxToolIterations", default).takeIf { it > 0 } ?: default
}
