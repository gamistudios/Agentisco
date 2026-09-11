package com.agentisco.agent.tool

import com.agentisco.agent.model.AgentPermissions
import com.agentisco.agent.model.PendingApproval
import com.agentisco.agent.model.ToolType
import com.agentisco.data.model.Project
import com.agentisco.data.model.TerminalSession

/** Structured result returned to the model after a tool executes. */
data class ToolResult(
  val success: Boolean,
  val output: String = "",
  val error: String? = null,
  val exitCode: Int? = null,
  val metadata: Map<String, String> = emptyMap()
)

/**
 * Execution context handed to tools. Tools never touch managers directly
 * beyond what the context exposes, and never bypass the permission policy:
 * protected operations must call [requestApproval] and honor the answer.
 */
class ToolContext(
  val project: Project,
  val permissions: AgentPermissions,
  val terminalSession: TerminalSession,
  val requestApproval: suspend (PendingApproval) -> Boolean,
  val activeSessions: () -> List<TerminalSession>
)

/** Maps a tool name to the UI tool category used in activity streams. */
fun toolTypeFor(name: String): ToolType = when {
  name.startsWith("git_") -> ToolType.GIT
  name == "run_command" || name == "build" || name == "test" || name == "run" ||
    name.startsWith("terminal") -> ToolType.TERMINAL
  name == "write_file" || name == "create_file" || name == "move_file" || name == "delete_file" -> ToolType.EDIT_FILE
  else -> ToolType.READ_FILE
}

/**
 * A tool the model may request. Execution belongs to the application: the
 * model only supplies arguments, which are validated here.
 */
interface AgentTool {
  val name: String
  val description: String
  /** JSON Schema object describing accepted arguments. */
  val parametersJsonSchema: String
  suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult
}

/** Thrown when the model supplies malformed arguments for a tool. */
class ToolArgumentError(message: String) : Exception(message)

fun parseToolArgs(argsJson: String): org.json.JSONObject = try {
  if (argsJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(argsJson)
} catch (e: Exception) {
  throw ToolArgumentError("Invalid JSON arguments: ${e.message}")
}

fun org.json.JSONObject.optStringOrNull(key: String): String? =
  if (has(key) && !isNull(key)) optString(key) else null
