package com.agentisco.agent.tool

import com.agentisco.agent.model.AgentPermissions
import com.agentisco.agent.model.PendingApproval
import com.agentisco.data.model.Project
import com.agentisco.data.model.TerminalSession
import kotlinx.coroutines.flow.StateFlow

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
  val onToolExecuted: (com.agentisco.agent.model.ToolExecution) -> Unit,
  val activeSessions: () -> List<TerminalSession>
)

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
