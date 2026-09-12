package com.agentisco.agent.model

enum class AgentStepStatus {
  PENDING,
  RUNNING,
  COMPLETED,
  FAILED
}

data class AgentTaskStep(
  val id: String,
  val title: String,
  val status: AgentStepStatus,
  val details: String = "",
  val filesInspected: List<String> = emptyList(),
  val finding: String? = null
)

enum class ToolType {
  READ_FILE,
  SEARCH,
  TERMINAL,
  EDIT_FILE,
  GIT,
  BUILD
}

data class ToolExecution(
  val id: String,
  val type: ToolType,
  val title: String,
  val subtitle: String,
  val details: String = "",
  val exitCode: Int? = null,
  val output: String = "",
  val timestamp: String = "Just now"
)

data class PendingApproval(
  val id: String,
  val command: String,
  val title: String = "Agent wants to run",
  val impactDescription: String = "This will modify project dependencies and lockfiles.",
  val isDestructive: Boolean = false
)

enum class PermissionMode {
  ALWAYS_ASK,
  AUTO_APPROVE_PROJECT,
  ALLOW_SAFE,
  ALLOW_ALL,
  NEVER_ALLOW
}

/** Sentinel for an unlimited agent tool loop. */
const val UNLIMITED_ITERATIONS = Int.MAX_VALUE

data class AgentPermissions(
  val fileEditing: PermissionMode = PermissionMode.ALWAYS_ASK,
  val terminalCommands: PermissionMode = PermissionMode.ALWAYS_ASK,
  val networkAccess: Boolean = true,
  /** Tool-loop budget; [UNLIMITED_ITERATIONS] means the agent works until the task completes. */
  val maxToolIterations: Int = UNLIMITED_ITERATIONS,
  val readFiles: Boolean = true,
  val createFiles: Boolean = true,
  val modifyFiles: Boolean = true,
  val deleteFiles: Boolean = false,
  val runCommands: Boolean = true,
  val installPackages: Boolean = false,
  val networkCommands: Boolean = false,
  val gitStatus: Boolean = true,
  val gitDiff: Boolean = true,
  val gitCommit: Boolean = true,
  val gitPush: Boolean = false,
  val alwaysAskDangerous: Boolean = true
)
