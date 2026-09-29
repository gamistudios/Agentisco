package com.agentisco.agent.tool

import com.agentisco.agent.model.AgentPermissions
import com.agentisco.agent.model.PermissionMode
import com.agentisco.data.model.Project
import org.json.JSONObject

/**
 * What a delegated run needs to report back. [summary] is the only text the
 * delegating agent receives, which is the entire point of the tool: the subagent
 * can read forty files and the parent's transcript gains a report, not forty
 * tool results.
 */
data class SubagentOutcome(
  val success: Boolean,
  val summary: String = "",
  val error: String? = null
)

/**
 * Runs one delegated task. Implemented by the runtime, which owns the LLM and
 * the tools; the registry only hands the tool a way to reach it, so a build
 * without a runtime simply has no `delegate` tool instead of a broken one.
 */
fun interface SubagentLauncher {
  suspend fun launch(description: String, prompt: String, project: Project, terminal: com.agentisco.data.model.TerminalSession): SubagentOutcome
}

/**
 * Delegates a self-contained question to a fresh agent over the same workspace.
 *
 * The child runs with plan mode on, so the same gate that implements plan mode
 * for the user is what keeps a subagent from editing files — and `delegate` is
 * not in the child's tool list, which is what keeps it from delegating further.
 */
class SubagentTool(private val launcher: SubagentLauncher) : AgentTool {
  override val name = "delegate"
  override val description =
    "Delegate a self-contained research question to a fresh sub-agent working in this workspace but seeing none of this conversation. " +
      "It can read, search, inspect git, run read-only shell commands and use the web, and it cannot change anything: its final report is the only thing you get back. " +
      "Use it when answering would mean reading many files you do not otherwise need in your own context - 'find every place the old auth helper is referenced and list them', 'summarise how orders flow from route to repository'. " +
      "Do not use it for a change you have already decided to make, or for what two searches can answer yourself. " +
      "The prompt must carry everything the sub-agent needs: the goal, the paths or names to start from, and exactly what the report should contain."
  override val params = listOf(
    ToolParam("description", "A few words naming the delegation, e.g. \"trace auth references\"."),
    ToolParam("prompt", "The complete brief for the sub-agent. It sees no conversation history and cannot ask you or the user anything.")
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val description = args.str("description")
    val prompt = args.str("prompt")
    val outcome = launcher.launch(description, prompt, ctx.project, ctx.terminalSession)
    if (!outcome.success) {
      return ToolResult(
        success = false,
        error = (outcome.error ?: "The sub-agent could not finish.") +
          " Do the work yourself with the read and search tools instead of retrying the delegation."
      )
    }
    val report = outcome.summary
    return ToolResult(
      success = true,
      output = buildString {
        appendLine("Delegated task (${description.ifBlank { "research" }}) finished. Report:")
        append(ToolOutput.limit(report, ToolOutput.READ_CHARS, "ask for a narrower follow-up delegation for the rest"))
      },
      metadata = mapOf("delegation" to description.ifBlank { "research" })
    )
  }

  /** The brief every delegated run starts from: a report, not a conversation. */
  companion object {
    const val MAX_CHILD_ITERATIONS = 24

    fun brief(prompt: String): String = """
$prompt

You are a sub-agent working inside one delegated task. You cannot change anything:
every tool that would modify the workspace or run a non-read-only command is refused,
and no user is watching you - the agent that delegated this reads only your final
message. So finish with a report it can act on without opening anything: the answer,
then the workspace-relative file paths (with line numbers where they matter) that
support it, then anything you could not determine. Keep it under about 400 words.
Do not narrate your steps and do not ask questions.
    """.trimIndent()

    /** The policy a delegated run executes under: research, never change. */
    fun childPermissions(parent: AgentPermissions): AgentPermissions = parent.copy(
      planMode = true,
      fileEditing = PermissionMode.NEVER_ALLOW,
      terminalCommands = if (parent.terminalCommands == PermissionMode.NEVER_ALLOW) PermissionMode.NEVER_ALLOW else PermissionMode.ALLOW_SAFE,
      deleteFiles = false,
      maxToolIterations = MAX_CHILD_ITERATIONS
    )
  }
}
