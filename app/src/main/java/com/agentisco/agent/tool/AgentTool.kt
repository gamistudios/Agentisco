package com.agentisco.agent.tool

import com.agentisco.agent.model.AgentPermissions
import com.agentisco.agent.model.PendingApproval
import com.agentisco.agent.model.ToolType
import com.agentisco.data.model.Project
import com.agentisco.data.model.TerminalSession
import org.json.JSONArray
import org.json.JSONObject

/** Structured result returned to the model after a tool executes. */
data class ToolResult(
  val success: Boolean,
  val output: String = "",
  val error: String? = null,
  val exitCode: Int? = null,
  val metadata: Map<String, String> = emptyMap()
)

/**
 * Canonical internal tool schema. Every tool declares its parameters once,
 * here; provider-specific wire formats (OpenAI function JSON Schema, Anthropic
 * input_schema) are generated from this single representation.
 */
data class ToolParam(
  val name: String,
  val description: String,
  val type: String = "string",
  val required: Boolean = true
)

/**
 * A tool the model may request. The model only supplies arguments; execution
 * and permission enforcement belong to the application. Arguments arrive as a
 * JSON string (possibly streamed in fragments by the provider), are parsed and
 * validated exactly once against [params] via [parseAndValidate], then handed
 * to [execute].
 */
interface AgentTool {
  val name: String
  val description: String
  val params: List<ToolParam>
  suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult

  /** OpenAI/Anthropic-compatible JSON Schema for this tool's parameters. */
  fun parametersJsonSchema(): String {
    val schema = JSONObject()
    schema.put("type", "object")
    val props = JSONObject()
    params.forEach { p ->
      props.put(p.name, JSONObject().put("type", p.type).put("description", p.description))
    }
    schema.put("properties", props)
    val required = params.filter { it.required }.map { it.name }
    if (required.isNotEmpty()) schema.put("required", JSONArray(required))
    schema.put("additionalProperties", false)
    return schema.toString()
  }

  /**
   * Defensive argument handling: never lets malformed, empty, truncated or
   * non-object arguments reach [execute]. Throws [ToolArgumentError] with a
   * model-correctable message on any violation.
   */
  fun parseAndValidate(argsJson: String): JSONObject {
    val text = argsJson.trim()
    val required = params.filter { it.required }
    if (text.isEmpty() || text == "null") {
      if (required.isEmpty()) return JSONObject()
      throw ToolArgumentError(
        "Missing arguments. Respond with a JSON object: {${required.joinToString(", ") { "\"${it.name}\": ..." }}} — ${required.joinToString("; ") { "${it.name}: ${it.description}" }}"
      )
    }
    val obj = try {
      JSONObject(text)
    } catch (e: Exception) {
      throw ToolArgumentError(
        "Arguments must be one valid JSON object (got \"${text.take(80)}\"). Example: {${(params.firstOrNull()?.name ?: "arg")}: \"...\"}"
      )
    }
    required.forEach { p ->
      val v = obj.opt(p.name)
      if (v == null || v == JSONObject.NULL || (v is String && v.isBlank())) {
        throw ToolArgumentError("Missing required argument \"${p.name}\": ${p.description}")
      }
    }
    return obj
  }
}

/** Thrown when the model supplies arguments that fail schema validation. */
class ToolArgumentError(message: String) : Exception(message)

/** Maps a tool name to the UI tool category used in activity streams. */
fun toolTypeFor(name: String): ToolType = when {
  name.startsWith("git_") -> ToolType.GIT
  name == "run_command" || name == "build" || name == "test" || name == "run" ||
    name.startsWith("terminal") -> ToolType.TERMINAL
  name == "write_file" || name == "create_file" || name == "edit_file" || name == "move_file" || name == "delete_file" -> ToolType.EDIT_FILE
  name == "search_files" -> ToolType.SEARCH
  else -> ToolType.READ_FILE
}

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
