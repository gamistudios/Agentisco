package com.example.data.model

data class Project(
  val id: String,
  val name: String,
  val branch: String,
  val lastActivity: String,
  val changedFilesCount: Int = 0,
  val isDirty: Boolean = false,
  val activeSessionText: String? = null,
  val description: String = "",
  val path: String = "~/projects"
)

data class ProjectFile(
  val path: String,
  val name: String,
  val isDirectory: Boolean,
  val content: String = "",
  val language: String = "typescript",
  val children: List<ProjectFile> = emptyList()
)

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

enum class DiffLineType {
  ADDED,
  REMOVED,
  UNCHANGED
}

data class DiffLine(
  val type: DiffLineType,
  val oldLineNo: Int?,
  val newLineNo: Int?,
  val text: String
)

data class FileDiff(
  val filePath: String,
  val additionsCount: Int,
  val deletionsCount: Int,
  val lines: List<DiffLine>
)

enum class TerminalLineType {
  COMMAND,
  STDOUT,
  STDERR,
  SUCCESS,
  INFO
}

data class TerminalLine(
  val text: String,
  val type: TerminalLineType = TerminalLineType.STDOUT
)

data class TerminalSession(
  val id: String,
  val name: String,
  val currentDir: String,
  val lines: List<TerminalLine> = emptyList()
)

data class AIModel(
  val id: String,
  val name: String,
  val providerId: String,
  val contextWindow: String = "128k",
  val hasTools: Boolean = true,
  val hasStreaming: Boolean = true,
  val hasVision: Boolean = false,
  val hasReasoning: Boolean = false,
  val isFree: Boolean = false
)

data class AIProvider(
  val id: String,
  val name: String,
  val baseUrl: String,
  val apiKey: String,
  val isConnected: Boolean,
  val models: List<AIModel>
)

enum class PermissionMode {
  ALWAYS_ASK,
  AUTO_APPROVE_PROJECT,
  ALLOW_SAFE,
  ALLOW_ALL,
  NEVER_ALLOW
}

data class AgentPermissions(
  val fileEditing: PermissionMode = PermissionMode.ALWAYS_ASK,
  val terminalCommands: PermissionMode = PermissionMode.ALWAYS_ASK,
  val networkAccess: Boolean = true,
  val maxToolIterations: Int = 10,
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

enum class AppDestination {
  PROJECTS,
  AGENT,
  TERMINAL,
  FILES,
  EDITOR,
  DIFF,
  GIT,
  BUILD_RUN,
  SETTINGS
}

data class CommandPaletteItem(
  val id: String,
  val title: String,
  val subtitle: String,
  val category: String,
  val destination: AppDestination? = null
)
