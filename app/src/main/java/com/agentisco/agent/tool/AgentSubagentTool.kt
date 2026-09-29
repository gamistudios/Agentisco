package com.agentisco.agent.tool

import com.agentisco.agent.model.AgentPermissions
import com.agentisco.agent.model.AgentRole
import com.agentisco.agent.model.AgentRoles
import com.agentisco.agent.model.PermissionMode
import com.agentisco.data.model.Project
import com.agentisco.data.model.TerminalSession
import org.json.JSONObject

/**
 * What a delegated run reports back. [summary] is the only text the delegating
 * agent receives, which is the point of the tool: a specialist can read forty
 * files and run a test suite, and the parent's transcript gains a report rather
 * than forty tool results. [modifiedFiles] is what the runtime counted, not what
 * the sub-agent claims it touched.
 */
data class SubagentOutcome(
  val success: Boolean,
  val summary: String = "",
  val error: String? = null,
  val modifiedFiles: List<String> = emptyList()
)

/**
 * Runs one delegated task. Implemented by the runtime, which owns the LLM and
 * the tools; the registry only hands the tool a way to reach it, so a build
 * without a runtime simply has no `delegate` tool instead of a broken one.
 */
fun interface SubagentLauncher {
  suspend fun launch(
    role: AgentRole,
    description: String,
    prompt: String,
    project: Project,
    terminal: TerminalSession
  ): SubagentOutcome
}

/**
 * Hands a slice of work to another agent on the team.
 *
 * The role decides everything that differs between them: its responsibility, its
 * lane, its standards, and whether the runtime lets it write at all. Only research
 * is read-only - it runs under the same plan-mode gate that protects a planning
 * turn, and its tool list holds nothing that could change the workspace. Every
 * other role is a full engineer scoped by what its prompt tells it to stay out of.
 * No delegated run gets `delegate`, so one request can never fan out into an
 * unbounded number of model calls.
 */
class SubagentTool(
  private val launcher: SubagentLauncher,
  /** Live roster, so a custom agent the user defines is immediately delegable. */
  private val roster: () -> List<AgentRole> = { AgentRoles.builtIn }
) : AgentTool {
  override val name = "delegate"
  override val description =
    "Put a slice of work in another agent's hands and get its report back. Each role is the same engine with a different responsibility: explore is read-only research, every other role can change files, run commands and test its own work. " +
      "Use it when a task would cost too many files in this conversation to be worth doing here, or when the work belongs in a specialist's lane (interface, backend, tests, security, a specific bug). " +
      "Roles may run beside each other, so give each one a slice that does not overlap another agent's files, and say in the brief which files are yours. " +
      "The brief has to carry everything: a delegated agent sees none of this conversation, cannot delegate further, and cannot ask the user - a decision that belongs to the user goes in its report as a hand-off, never as a question. " +
      "It works in the same workspace, so tell it what is already done. " +
      rosterText()

  override val params = listOf(
    ToolParam(
      "role",
      "Which agent to run this on: ${roster().joinToString(", ") { it.id }}. " +
        "Pick the narrowest lane that covers the work; general when none does."
    ),
    ToolParam("description", "A few words naming the delegation, e.g. \"trace auth references\"."),
    ToolParam(
      "prompt",
      "The complete brief: the goal, the files or names to start from, what is already done, and exactly what the report must contain."
    )
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val requestedRole = args.str("role")
    val role = AgentRoles.resolve(roster(), requestedRole)
      ?: return ToolResult(
        success = false,
        error = "There is no agent role \"$requestedRole\" on this team. ${rosterText()}"
      )
    val description = args.str("description")
    val prompt = args.str("prompt")

    val outcome = launcher.launch(role, description, prompt, ctx.project, ctx.terminalSession)
    if (!outcome.success) {
      return ToolResult(
        success = false,
        error = (outcome.error ?: "The ${role.name} could not finish.") +
          " Do the work yourself with your own tools instead of retrying the delegation."
      )
    }
    val report = outcome.summary
    val label = description.ifBlank { role.name }
    return ToolResult(
      success = true,
      output = buildString {
        appendLine("Report from the ${role.name} (${label}):")
        append(ToolOutput.limit(report, ToolOutput.READ_CHARS, "ask for a narrower follow-up delegation for the rest"))
        if (outcome.modifiedFiles.isNotEmpty()) {
          appendLine()
          appendLine()
          // Counted by the runtime, so the parent knows which files are now dirty
          // without the sub-agent having to describe its own diff.
          append("Files it changed: ${outcome.modifiedFiles.joinToString(", ")}")
        }
      },
      metadata = mapOf(
        "delegation" to label,
        "role" to role.id,
        "files" to outcome.modifiedFiles.size.toString()
      )
    )
  }

  private fun rosterText(): String =
    "Roles on this team:\n" + roster().joinToString("\n") { role ->
      // The scope matters to the one choosing: it says where this agent works, so
      // two delegations can be given slices that do not collide.
      "  ${role.id}: ${role.purpose}" + if (role.scope.isNotBlank()) " (works in: ${role.scope})" else ""
    }

  companion object {
    /** The policy a delegated run executes under, per role. */
    fun childPermissions(role: AgentRole, parent: AgentPermissions): AgentPermissions =
      if (role.readOnly) {
        // Research: the gate does the refusing, so it cannot be talked around.
        parent.copy(
          planMode = true,
          fileEditing = PermissionMode.NEVER_ALLOW,
          terminalCommands =
            if (parent.terminalCommands == PermissionMode.NEVER_ALLOW) PermissionMode.NEVER_ALLOW
            else PermissionMode.ALLOW_SAFE,
          deleteFiles = false,
          gitPush = false,
          maxToolIterations = role.maxToolIterations
        )
      } else {
        // A specialist does real work, under exactly the user's own limits: it
        // never gets more permission than the agent that delegated to it.
        parent.copy(
          planMode = false,
          gitPush = false,
          maxToolIterations = role.maxToolIterations
        )
      }
  }
}
