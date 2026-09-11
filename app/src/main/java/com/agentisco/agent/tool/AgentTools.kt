package com.agentisco.agent.tool

import com.agentisco.agent.model.PendingApproval
import com.agentisco.agent.model.PermissionMode
import com.agentisco.agent.model.ToolExecution
import com.agentisco.agent.model.ToolType
import com.agentisco.agent.permission.DestructiveCommandGuard
import com.agentisco.data.model.DiffLineType
import com.agentisco.data.model.ProjectFile
import com.agentisco.data.model.TerminalSession
import com.agentisco.workspace.filesystem.ProjectFileSystem
import com.agentisco.workspace.git.GitRepositoryManager
import com.agentisco.workspace.terminal.TerminalProcessManager
import java.io.File

/**
 * Real workspace tools. The model may only request these; every execution goes
 * through [AgentTool.execute] where arguments are validated and permission
 * policy is enforced (approval is awaited before protected operations).
 */
class AgentToolRegistry(
  private val fileSystem: ProjectFileSystem,
  private val gitManager: GitRepositoryManager,
  private val terminalManager: TerminalProcessManager,
  private val stagedFilesProvider: () -> Set<String>,
  private val onStageFile: (String) -> Unit,
  private val onStageAll: () -> Unit,
  private val onUnstageAll: () -> Unit
) {

  val tools: List<AgentTool> = listOf(
    ListFilesTool(fileSystem), ReadFileTool(fileSystem),
    WriteFileTool(fileSystem, ::checkFileWrite), CreateFileTool(fileSystem, ::checkFileWrite),
    DeleteFileTool(fileSystem, ::checkFileDelete), MoveFileTool(fileSystem, ::checkFileWrite),
    SearchFilesTool(fileSystem),
    RunCommandTool(terminalManager), WriteTerminalInputTool(terminalManager),
    InterruptTerminalTool(terminalManager),
    GitStatusTool(gitManager), GitDiffTool(gitManager), GitStageTool(onStageFile, onStageAll),
    GitUnstageTool(onUnstageAll), GitCommitTool(gitManager, stagedFilesProvider),
    BuildTool(terminalManager), TestTool(terminalManager), RunTool(terminalManager)
  )

  private val byName = tools.associateBy { it.name }

  fun specs() = tools.map { com.agentisco.agent.llm.LlmToolSpec(it.name, it.description, it.parametersJsonSchema) }

  fun get(name: String): AgentTool? = byName[name]

  fun recordExecution(name: String, argsSummary: String, result: ToolResult, ctx: ToolContext) {
    ctx.onToolExecuted(
      ToolExecution(
        id = "tool-${System.currentTimeMillis()}-$name",
        type = toolTypeFor(name),
        title = "$name $argsSummary".trim().take(120),
        subtitle = if (result.success) "Completed" else "Failed",
        exitCode = result.exitCode,
        output = (result.output + (result.error?.let { "\n$it" } ?: "")).take(4000)
      )
    )
  }

  private fun toolTypeFor(name: String): ToolType = when {
    name.startsWith("git_") -> ToolType.GIT
    name == "run_command" || name == "build" || name == "test" || name == "run" ||
      name.startsWith("terminal") -> ToolType.TERMINAL
    name == "write_file" || name == "create_file" || name == "move_file" -> ToolType.EDIT_FILE
    else -> ToolType.READ_FILE
  }

  // ---- Permission gates shared by tools ----

  internal suspend fun checkFileWrite(ctx: ToolContext, path: String): ToolResult? {
    if (ctx.permissions.fileEditing == PermissionMode.NEVER_ALLOW) {
      return ToolResult(success = false, error = "Blocked by permission policy: file editing is never allowed.")
    }
    if (ctx.permissions.fileEditing == PermissionMode.ALWAYS_ASK) {
      val ok = ctx.requestApproval(
        PendingApproval(
          id = "tool-appr-${System.currentTimeMillis()}",
          command = "write $path",
          title = "Agent wants to modify a file",
          impactDescription = "This will change $path inside ${ctx.project.name}.",
          isDestructive = false
        )
      )
      if (!ok) return ToolResult(success = false, error = "User rejected the file modification.")
    }
    return null
  }

  internal suspend fun checkFileDelete(ctx: ToolContext, path: String): ToolResult? {
    if (!ctx.permissions.deleteFiles) {
      return ToolResult(success = false, error = "Blocked by permission policy: file deletion is disabled.")
    }
    val ok = ctx.requestApproval(
      PendingApproval(
        id = "tool-appr-${System.currentTimeMillis()}",
        command = "delete $path",
        title = "Agent wants to delete a file",
        impactDescription = "This permanently removes $path from ${ctx.project.name}.",
        isDestructive = true
      )
    )
    if (!ok) return ToolResult(success = false, error = "User rejected the file deletion.")
    return null
  }
}

// ================= Filesystem tools =================

private fun schema(vararg props: Pair<String, String>, required: List<String> = emptyList()): String {
  val obj = org.json.JSONObject()
  val p = org.json.JSONObject()
  props.forEach { (k, desc) -> p.put(k, org.json.JSONObject().put("type", "string").put("description", desc)) }
  obj.put("type", "object")
  obj.put("properties", p)
  if (required.isNotEmpty()) obj.put("required", org.json.JSONArray(required))
  return obj.toString()
}

internal typealias WriteGate = suspend (ToolContext, String) -> ToolResult?

class ListFilesTool(private val fs: ProjectFileSystem) : AgentTool {
  override val name = "list_files"
  override val description = "List files in the active project, optionally under a subdirectory."
  override val parametersJsonSchema = schema("path" to "Optional subdirectory to list (relative to project root)")

  override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
    val path = parseToolArgs(argsJson).optStringOrNull("path")
    val tree = fs.getFileTree(ctx.project)
    val target = path?.trim('/')?.takeIf { it.isNotEmpty() }
    val items = if (target == null) tree else findDir(tree, target)?.children
      ?: return ToolResult(success = false, error = "Directory not found: $target")
    val lines = items.map { (if (it.isDirectory) "[dir] " else "") + it.path }
    return ToolResult(success = true, output = lines.joinToString("\n").ifBlank { "(empty directory)" }, metadata = mapOf("count" to items.size.toString()))
  }

  private fun findDir(items: List<ProjectFile>, path: String): ProjectFile? {
    for (item in items) {
      if (item.path == path) return item
      findDir(item.children, path)?.let { return it }
    }
    return null
  }
}

class ReadFileTool(private val fs: ProjectFileSystem) : AgentTool {
  override val name = "read_file"
  override val description = "Read the full contents of a file in the active project."
  override val parametersJsonSchema = schema("path" to "File path relative to project root", required = listOf("path"))

  override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
    val path = parseToolArgs(argsJson).optStringOrNull("path") ?: return ToolResult(false, error = "Missing required argument: path")
    if (!fs.exists(ctx.project, path)) return ToolResult(false, error = "File not found: $path")
    val content = fs.readFile(ctx.project, path)
    return ToolResult(success = true, output = content.take(32000), metadata = mapOf("lines" to content.lines().size.toString()))
  }
}

class WriteFileTool(private val fs: ProjectFileSystem, private val gate: WriteGate) : AgentTool {
  override val name = "write_file"
  override val description = "Create or overwrite a file with the given full content."
  override val parametersJsonSchema = schema(
    "path" to "File path relative to project root",
    "content" to "Full new file content",
    required = listOf("path", "content")
  )

  override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
    val args = parseToolArgs(argsJson)
    val path = args.optStringOrNull("path") ?: return ToolResult(false, error = "Missing required argument: path")
    val content = args.optStringOrNull("content") ?: return ToolResult(false, error = "Missing required argument: content")
    gate(ctx, path)?.let { return it }
    val ok = fs.writeFile(ctx.project, path, content)
    return if (ok) ToolResult(true, output = "Wrote ${content.lines().size} lines to $path", metadata = mapOf("file" to path))
    else ToolResult(false, error = "Failed to write $path")
  }
}

class CreateFileTool(private val fs: ProjectFileSystem, private val gate: WriteGate) : AgentTool {
  override val name = "create_file"
  override val description = "Create a new file (fails if it already exists)."
  override val parametersJsonSchema = schema(
    "path" to "New file path relative to project root",
    "content" to "Initial file content",
    required = listOf("path")
  )

  override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
    val args = parseToolArgs(argsJson)
    val path = args.optStringOrNull("path") ?: return ToolResult(false, error = "Missing required argument: path")
    val content = args.optStringOrNull("content") ?: ""
    if (fs.exists(ctx.project, path)) return ToolResult(false, error = "File already exists: $path (use write_file to overwrite)")
    gate(ctx, path)?.let { return it }
    return if (fs.createFile(ctx.project, path, content)) ToolResult(true, output = "Created $path", metadata = mapOf("file" to path))
    else ToolResult(false, error = "Failed to create $path")
  }
}

class DeleteFileTool(private val fs: ProjectFileSystem, private val gate: suspend (ToolContext, String) -> ToolResult?) : AgentTool {
  override val name = "delete_file"
  override val description = "Delete a file from the active project. Requires delete permission and user approval."
  override val parametersJsonSchema = schema("path" to "File path relative to project root", required = listOf("path"))

  override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
    val path = parseToolArgs(argsJson).optStringOrNull("path") ?: return ToolResult(false, error = "Missing required argument: path")
    gate(ctx, path)?.let { return it }
    return if (fs.deleteFile(ctx.project, path)) ToolResult(true, output = "Deleted $path", metadata = mapOf("file" to path))
    else ToolResult(false, error = "Failed to delete $path")
  }
}

class MoveFileTool(private val fs: ProjectFileSystem, private val gate: WriteGate) : AgentTool {
  override val name = "move_file"
  override val description = "Move/rename a file to a new path inside the project."
  override val parametersJsonSchema = schema(
    "path" to "Existing file path relative to project root",
    "new_path" to "Destination path relative to project root",
    required = listOf("path", "new_path")
  )

  override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
    val args = parseToolArgs(argsJson)
    val path = args.optStringOrNull("path") ?: return ToolResult(false, error = "Missing required argument: path")
    val newPath = args.optStringOrNull("new_path") ?: return ToolResult(false, error = "Missing required argument: new_path")
    if (!fs.exists(ctx.project, path)) return ToolResult(false, error = "File not found: $path")
    gate(ctx, newPath)?.let { return it }

    // Same directory: simple rename. Cross-directory: copy content then delete source.
    val sameDir = path.substringBeforeLast('/', missingDelimiterValue = "") ==
      newPath.substringBeforeLast('/', missingDelimiterValue = "")
    val ok = if (sameDir) {
      fs.renameFile(ctx.project, path, newPath.substringAfterLast('/'))
    } else {
      fs.createFile(ctx.project, newPath, fs.readFile(ctx.project, path)) &&
        fs.deleteFile(ctx.project, path)
    }
    return if (ok) ToolResult(true, output = "Moved $path to $newPath", metadata = mapOf("file" to newPath))
    else ToolResult(false, error = "Failed to move $path to $newPath")
  }
}

class SearchFilesTool(private val fs: ProjectFileSystem) : AgentTool {
  override val name = "search_files"
  override val description = "Search file contents across the project for a text query."
  override val parametersJsonSchema = schema("query" to "Text to search for", required = listOf("query"))

  override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
    val query = parseToolArgs(argsJson).optStringOrNull("query") ?: return ToolResult(false, error = "Missing required argument: query")
    val matches = fs.searchInProject(ctx.project, query)
    val output = matches.take(50).joinToString("\n") { "${it.filePath}:${it.lineNumber}: ${it.lineText}" }
    return ToolResult(
      success = true,
      output = output.ifBlank { "No matches for \"$query\"" },
      metadata = mapOf("matches" to matches.size.toString())
    )
  }
}

// ================= Terminal tools =================

class RunCommandTool(private val tm: TerminalProcessManager) : AgentTool {
  override val name = "run_command"
  override val description = "Run a shell command in the active terminal session. Destructive commands always require explicit user approval."
  override val parametersJsonSchema = schema("command" to "Shell command to execute", required = listOf("command"))

  override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
    val command = parseToolArgs(argsJson).optStringOrNull("command")?.trim() ?: return ToolResult(false, error = "Missing required argument: command")
    if (command.isEmpty()) return ToolResult(false, error = "Command is empty")

    val guard = DestructiveCommandGuard.assess(command)
    if (guard != null) {
      val ok = ctx.requestApproval(
        PendingApproval(
          id = "tool-appr-${System.currentTimeMillis()}",
          command = command, title = guard.title, impactDescription = guard.reason, isDestructive = true
        )
      )
      if (!ok) return ToolResult(false, error = "User rejected destructive command: $command", exitCode = -1)
    } else {
      val needsApproval = when (ctx.permissions.terminalCommands) {
        PermissionMode.ALWAYS_ASK -> true
        PermissionMode.NEVER_ALLOW -> return ToolResult(false, error = "Blocked by permission policy: terminal commands are not allowed.", exitCode = -1)
        PermissionMode.ALLOW_ALL -> false
        else -> !DestructiveCommandGuard.isSafeCommand(command)
      }
      if (needsApproval) {
        val ok = ctx.requestApproval(
          PendingApproval(
            id = "tool-appr-${System.currentTimeMillis()}",
            command = command, title = "Agent wants to run a command",
            impactDescription = "Runs in terminal session \"${ctx.terminalSession.name}\": $command",
            isDestructive = false
          )
        )
        if (!ok) return ToolResult(false, error = "User rejected command execution.", exitCode = -1)
      }
    }

    val out = StringBuilder()
    val exitCode = tm.executeCommand(ctx.terminalSession, command) { line -> out.appendLine(line.text) }
    return ToolResult(success = exitCode == 0, output = out.toString().trim().take(8000), exitCode = exitCode)
  }
}

class WriteTerminalInputTool(private val tm: TerminalProcessManager) : AgentTool {
  override val name = "write_terminal_input"
  override val description = "Write raw input (e.g. answers to interactive prompts) into a running terminal session."
  override val parametersJsonSchema = schema(
    "input" to "Raw input to send",
    "session_id" to "Optional terminal session id (defaults to active session)",
    required = listOf("input")
  )

  override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
    val args = parseToolArgs(argsJson)
    val input = args.optStringOrNull("input") ?: return ToolResult(false, error = "Missing required argument: input")
    val sessionId = args.optStringOrNull("session_id") ?: ctx.terminalSession.id
    return if (tm.writeInput(sessionId, input)) ToolResult(true, output = "Input sent to session $sessionId")
    else ToolResult(false, error = "Could not write to session $sessionId (no running process)")
  }
}

class InterruptTerminalTool(private val tm: TerminalProcessManager) : AgentTool {
  override val name = "interrupt_terminal"
  override val description = "Interrupt (SIGINT-style) a running terminal session."
  override val parametersJsonSchema = schema("session_id" to "Optional terminal session id (defaults to active session)")

  override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
    val sessionId = parseToolArgs(argsJson).optStringOrNull("session_id") ?: ctx.terminalSession.id
    return if (tm.interrupt(sessionId)) ToolResult(true, output = "Interrupted session $sessionId")
    else ToolResult(false, error = "Session $sessionId is not running")
  }
}

// ================= Development tools =================

private abstract class ScriptTool(
  private val tm: TerminalProcessManager,
  toolName: String,
  private val script: String,
  private val label: String
) : AgentTool {
  override val name = toolName
  override val description = "$label in the active project (via npm scripts when package.json exists)."
  override val parametersJsonSchema = schema("args" to "Optional extra arguments")

  override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
    val extra = parseToolArgs(argsJson).optStringOrNull("args")?.trim().orEmpty()
    val hasNpm = File(ctx.project.path, "package.json").exists()
    val command = if (hasNpm) "npm run $script${if (extra.isBlank()) "" else " -- $extra"}" else "echo 'No package.json in ${ctx.project.name}'"
    val out = StringBuilder()
    val exitCode = tm.executeCommand(ctx.terminalSession, command) { line -> out.appendLine(line.text) }
    return ToolResult(success = exitCode == 0, output = out.toString().trim().take(8000), exitCode = exitCode, metadata = mapOf("command" to command))
  }
}

class BuildTool(tm: TerminalProcessManager) : ScriptTool(tm, "build", "build", "Build the project")
class TestTool(tm: TerminalProcessManager) : ScriptTool(tm, "test", "test", "Run the project's tests")
class RunTool(tm: TerminalProcessManager) : ScriptTool(tm, "run", "dev", "Start the project dev server")

// ================= Git tools =================

class GitStatusTool(private val git: GitRepositoryManager) : AgentTool {
  override val name = "git_status"
  override val description = "Show changed files and dirty state of the active project."
  override val parametersJsonSchema = "{}"
  override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
    val changed = git.getChangedFiles(ctx.project)
    return ToolResult(
      success = true,
      output = if (changed.isEmpty()) "Working tree clean" else changed.joinToString("\n") { "M $it" },
      metadata = mapOf("changed" to changed.size.toString())
    )
  }
}

class GitDiffTool(private val git: GitRepositoryManager) : AgentTool {
  override val name = "git_diff"
  override val description = "Show the current diff (added/removed lines) for the project or one file."
  override val parametersJsonSchema = schema("path" to "Optional file path to diff")

  override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
    val path = parseToolArgs(argsJson).optStringOrNull("path")
    val diffs = git.computeAllDiffs(ctx.project)
    val selected = if (path != null) diffs.filter { it.filePath == path } else diffs
    if (selected.isEmpty()) return ToolResult(true, output = "No changes${if (path != null) " in $path" else ""}")
    val out = selected.joinToString("\n\n") { d ->
      "== ${d.filePath} (+${d.additionsCount}/-${d.deletionsCount}) ==\n" +
        d.lines.joinToString("\n") { l ->
          (when (l.type) {
            DiffLineType.ADDED -> "+"
            DiffLineType.REMOVED -> "-"
            else -> " "
          }) + l.text
        }
    }
    return ToolResult(true, output = out.take(8000), metadata = mapOf("files" to selected.size.toString()))
  }
}

class GitStageTool(private val onStageFile: (String) -> Unit, private val onStageAll: () -> Unit) : AgentTool {
  override val name = "git_stage"
  override val description = "Stage a file (or all changes) for commit."
  override val parametersJsonSchema = schema("path" to "File to stage; omit or pass 'all' to stage everything")

  override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
    val path = parseToolArgs(argsJson).optStringOrNull("path")?.takeIf { it.isNotBlank() && it != "all" }
    return if (path == null) {
      onStageAll(); ToolResult(true, output = "Staged all changed files")
    } else {
      onStageFile(path); ToolResult(true, output = "Staged $path")
    }
  }
}

class GitUnstageTool(private val onUnstageAll: () -> Unit) : AgentTool {
  override val name = "git_unstage"
  override val description = "Unstage all currently staged changes."
  override val parametersJsonSchema = "{}"
  override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
    onUnstageAll(); return ToolResult(true, output = "Unstaged all files")
  }
}

class GitCommitTool(private val git: GitRepositoryManager, private val stagedFilesProvider: () -> Set<String>) : AgentTool {
  override val name = "git_commit"
  override val description = "Commit staged changes with the given message."
  override val parametersJsonSchema = schema("message" to "Commit message", required = listOf("message"))

  override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
    val message = parseToolArgs(argsJson).optStringOrNull("message")?.trim() ?: return ToolResult(false, error = "Missing required argument: message")
    if (message.isEmpty()) return ToolResult(false, error = "Commit message is empty")
    if (!ctx.permissions.gitCommit) return ToolResult(false, error = "Blocked by permission policy: git commits are not allowed.")
    val staged = stagedFilesProvider()
    if (staged.isEmpty()) return ToolResult(false, error = "Nothing is staged. Stage changes with git_stage first.")
    val commit = git.commit(ctx.project, staged, message)
    return if (commit != null) ToolResult(true, output = "Committed ${staged.size} file(s): ${commit.hash} ${commit.message}", metadata = mapOf("hash" to commit.hash))
    else ToolResult(false, error = "Commit failed")
  }
}
