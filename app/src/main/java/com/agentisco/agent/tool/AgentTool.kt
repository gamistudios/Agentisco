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
  val required: Boolean = true,
  /**
   * True when an explicitly supplied empty string is meaningful (e.g.
   * edit_file's `new_string: ""` deletes the snippet). Required parameters are
   * otherwise rejected when blank, because models emit `""` for "I don't know".
   */
  val allowBlank: Boolean = false
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
      if (v == null || v == JSONObject.NULL) {
        throw ToolArgumentError("Missing required argument \"${p.name}\": ${p.description}")
      }
      // A blank string is only a mistake when the argument has no empty meaning.
      if (v is String && v.isBlank() && !p.allowBlank) {
        throw ToolArgumentError("Missing required argument \"${p.name}\": ${p.description}")
      }
    }
    return obj
  }
}

/** Thrown when the model supplies arguments that fail schema validation. */
class ToolArgumentError(message: String) : Exception(message)

/** Maps a tool name to the UI tool category used in activity streams. */
private val EDITING_TOOLS = setOf(
  "write_file", "create_file", "edit_file", "edit_files", "move_file", "copy_file",
  "delete_file", "create_directory"
)
private val SEARCHING_TOOLS = setOf("search_files", "regex_search", "glob_files", "list_files", "directory_tree", "file_info")

fun toolTypeFor(name: String): ToolType = when {
  name.startsWith("git_") -> ToolType.GIT
  name == "run_command" || name == "build" || name == "test" || name == "run" ||
    name == "interrupt_terminal" || name == "write_terminal_input" ||
    name.startsWith("terminal") -> ToolType.TERMINAL
  name in EDITING_TOOLS -> ToolType.EDIT_FILE
  name in SEARCHING_TOOLS -> ToolType.SEARCH
  name == "task_plan" -> ToolType.BUILD
  name == "web_fetch" || name == "web_search" -> ToolType.WEB
  name == "ask_user" -> ToolType.QUESTION
  else -> ToolType.READ_FILE
}

/**
 * Execution context handed to tools. Tools never touch managers directly
 * beyond what the context exposes, and never bypass the permission policy:
 * protected operations must call [requestApproval] and honor the answer.
 */
class ToolContext(
  val project: Project,
  val permissions: () -> AgentPermissions,
  val terminalSession: TerminalSession,
  val requestApproval: suspend (PendingApproval) -> Boolean,
  val activeSessions: () -> List<TerminalSession>,
  /** The model's id for the call being executed (used for per-call cancellation). */
  val toolCallId: String = "",
  /**
   * Asks the user a question and waits for the answer. Returns the answer text,
   * or null when the user dismissed the question. Only [ask_user] needs it; the
   * default keeps hand-built contexts (tests, tools that never ask) working.
   */
  val askUser: suspend (PendingApproval) -> String? = { approval ->
    if (requestApproval(approval)) approval.options.firstOrNull() else null
  },
  /**
   * The runtime installs this so a stopped turn is distinguishable from a
   * refusal: [requestApprovalDecision] reads it, and a tool that reports the
   * outcome to the model must not describe a request it never got an answer to
   * as "the user said no". Null everywhere but the live agent run, so tests and
   * every other context treat a plain "no" as the refusal it is.
   */
  val onApprovalTerminated: ((String) -> Boolean)? = null
) {
  /**
   * The decision a tool needs when "not approved" is ambiguous: the runtime can
   * distinguish a refusal from a turn the user stopped mid-request, and a tool
   * has no other channel to learn it. Tools that only need yes/no keep using
   * [requestApproval]; the ones whose message to the model changes use this.
   *
   * The runtime distinguishes the two and reports the termination through this
   * hook; anywhere else, a "no" is a plain refusal because nothing ended the turn.
   */
  suspend fun requestApprovalDecision(approval: PendingApproval): ApprovalDecision {
    val approved = requestApproval(approval)
    val terminated = onApprovalTerminated?.invoke(approval.id) == true
    return ApprovalDecision(approved, refused = !approved && !terminated, terminated = terminated)
  }
}

/**
 * What the user actually decided about a protected operation.
 *
 * The distinction that matters: a refusal is a choice the user made, a
 * termination is the turn ending before they made one. Collapsing them makes a
 * parked request read back as a denial the user never issued.
 */
data class ApprovalDecision(
  val approved: Boolean,
  /** True only when the user explicitly chose to refuse. */
  val refused: Boolean,
  /** True when the turn was stopped before the user decided. */
  val terminated: Boolean
) {
  /** The tool never ran, and the transcript must not report a refusal. */
  val neverExecuted: Boolean get() = !approved && !refused

  /**
   * One message for the model, phrased by what actually happened: a refusal says
   * the user said no, a terminated request says nothing ran. [subject] is the
   * tool's own noun ("File not modified"); [detail] adds what was blocked.
   */
  fun describeNotExecuted(subject: String, detail: String = ""): String = buildString {
    append(subject)
    append(": ")
    if (terminated) {
      append("the turn was stopped before the user decided")
      if (detail.isNotBlank()) append(" on ").append(detail)
      append(". Nothing was run. Ask again if the task still needs it.")
    } else {
      append("the user denied permission")
      if (detail.isNotBlank()) append(" to run ").append(detail)
      append(".")
    }
  }
}
