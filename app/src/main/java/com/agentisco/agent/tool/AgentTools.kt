package com.agentisco.agent.tool

import com.agentisco.agent.model.PendingApproval
import com.agentisco.agent.model.PermissionMode
import com.agentisco.data.model.DiffLineType
import com.agentisco.workspace.filesystem.ProjectFileSystem
import com.agentisco.workspace.git.GitRepositoryManager
import com.agentisco.workspace.terminal.TerminalProcessManager
import org.json.JSONObject
import java.io.File

internal typealias WriteGate = suspend (ToolContext, String) -> ToolResult?

internal fun JSONObject.str(key: String): String {
  val v = opt(key)
  return if (v == null || v == JSONObject.NULL) "" else v.toString()
}

/**
 * Real workspace tools, described so the model never has to guess purpose.
 * The model may only request these; every execution validates arguments and
 * enforces permission policy (approval is awaited before protected operations).
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
    ListFilesTool(fileSystem),
    ReadFileTool(fileSystem),
    ReadFilesTool(fileSystem),
    SearchFilesTool(fileSystem),
    RegexSearchTool(fileSystem),
    GlobFilesTool(fileSystem),
    FileInfoTool(fileSystem),
    DirectoryTreeTool(fileSystem),
    TaskPlanTool(),
    WriteFileTool(fileSystem, ::checkFileWrite),
    EditFileTool(fileSystem, ::checkFileWrite),
    CreateFileTool(fileSystem, ::checkFileWrite),
    DeleteFileTool(fileSystem, ::checkFileDelete),
    MoveFileTool(fileSystem, ::checkFileWrite),
    RunCommandTool(terminalManager),
    InterruptTerminalTool(terminalManager),
    GitStatusTool(gitManager),
    GitDiffTool(gitManager),
    GitStageTool(onStageFile, onStageAll),
    GitCommitTool(gitManager, stagedFilesProvider),
    BuildTool(terminalManager),
    TestTool(terminalManager)
  )

  private val byName = tools.associateBy { it.name }

  fun specs() = tools.map {
    com.agentisco.agent.llm.LlmToolSpec(it.name, it.description, it.parametersJsonSchema())
  }

  fun get(name: String): AgentTool? = byName[name]

  // ---- Permission gates shared by tools ----

  internal suspend fun checkFileWrite(ctx: ToolContext, path: String): ToolResult? {
    if (ctx.permissions().fileEditing == PermissionMode.NEVER_ALLOW) {
      return ToolResult(success = false, error = "Blocked by permission policy: file editing is never allowed.")
    }
    if (ctx.permissions().fileEditing == PermissionMode.ALWAYS_ASK) {
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
    if (!ctx.permissions().deleteFiles) {
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

class ListFilesTool(private val fs: ProjectFileSystem) : AgentTool {
  override val name = "list_files"
  override val description =
    "Inspect the directory structure of the workspace. Lists files and folders at the project root or under an optional subdirectory. Use this first when exploring the codebase."
  override val params = listOf(
    ToolParam("path", "Optional subdirectory to list, relative to the project root. Omit for the root.", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val path = args.str("path").trim('/')
    val items = fs.listChildren(ctx.project, path)
      ?: return ToolResult(success = false, error = "Directory not found: $path")
    val lines = items.map { (if (it.isDirectory) "[dir] " else "") + it.path }
    return ToolResult(success = true, output = lines.joinToString("\n").ifBlank { "(empty directory)" }, metadata = mapOf("count" to items.size.toString()))
  }
}

class ReadFileTool(private val fs: ProjectFileSystem) : AgentTool {
  override val name = "read_file"
  override val description =
    "Read the exact contents of one file in the workspace. Use this when you need to inspect existing source code, configuration, or documentation. Requires the file path from list_files."
  override val params = listOf(
    ToolParam("path", "File path relative to the project root, e.g. \"src/App.tsx\".")
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val path = args.str("path")
    if (!fs.exists(ctx.project, path)) return ToolResult(false, error = "File not found: $path. Use list_files to see available files.")
    val content = fs.readFile(ctx.project, path)
    return ToolResult(success = true, output = content.take(32000), metadata = mapOf("lines" to content.lines().size.toString()))
  }
}

class SearchFilesTool(private val fs: ProjectFileSystem) : AgentTool {
  override val name = "search_files"
  override val description =
    "Find where a piece of text, symbol, or error message appears across all files in the workspace. Returns matching file paths, line numbers, and line contents."
  override val params = listOf(
    ToolParam("query", "Text to search for, e.g. a class name, function name, or message string.")
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val query = args.str("query")
    val matches = fs.searchInProject(ctx.project, query)
    val output = matches.take(50).joinToString("\n") { "${it.filePath}:${it.lineNumber}: ${it.lineText}" }
    return ToolResult(
      success = true,
      output = output.ifBlank { "No matches for \"$query\"" },
      metadata = mapOf("matches" to matches.size.toString())
    )
  }
}

class WriteFileTool(private val fs: ProjectFileSystem, private val gate: WriteGate) : AgentTool {
  override val name = "write_file"
  override val description =
    "Create a new file or completely replace an existing file's content. Use edit_file instead when you only need a targeted modification inside a larger file."
  override val params = listOf(
    ToolParam("path", "File path relative to the project root."),
    ToolParam("content", "The complete new file content (replaces any existing content).")
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val path = args.str("path")
    val content = args.str("content")
    gate(ctx, path)?.let { return it }
    val ok = fs.writeFile(ctx.project, path, content)
    return if (ok) ToolResult(true, output = "Wrote ${content.lines().size} lines to $path", metadata = mapOf("file" to path))
    else ToolResult(false, error = "Failed to write $path")
  }
}

class EditFileTool(private val fs: ProjectFileSystem, private val gate: WriteGate) : AgentTool {
  override val name = "edit_file"
  override val description =
    "Apply a targeted modification to an existing file by replacing an exact snippet. Provide old_string exactly as it appears in the file (include enough surrounding context to be unique) and new_string as its replacement."
  override val params = listOf(
    ToolParam("path", "Existing file path relative to the project root."),
    ToolParam("old_string", "Exact text to replace; must appear exactly once in the file."),
    ToolParam("new_string", "Replacement text (may be empty to delete the snippet).", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val path = args.str("path")
    val oldString = args.str("old_string")
    val newString = args.str("new_string")
    if (oldString.isEmpty()) return ToolResult(false, error = "old_string must not be empty; it is the exact snippet to replace.")
    if (!fs.exists(ctx.project, path)) return ToolResult(false, error = "File not found: $path. Use list_files to see available files.")

    val original = fs.readFile(ctx.project, path)
    val occurrences = original.split(oldString).size - 1
    if (occurrences == 0) return ToolResult(false, error = "old_string not found in $path. Read the file and copy the snippet exactly.")
    if (occurrences > 1) return ToolResult(false, error = "old_string appears $occurrences times in $path; include more surrounding context to make it unique.")

    gate(ctx, path)?.let { return it }
    val updated = original.replace(oldString, newString)
    val ok = fs.writeFile(ctx.project, path, updated)
    return if (ok) {
      val delta = updated.lines().size - original.lines().size
      val deltaText = if (delta == 0) "" else " (${if (delta > 0) "+" else ""}$delta lines)"
      ToolResult(true, output = "Edited $path$deltaText", metadata = mapOf("file" to path))
    } else ToolResult(false, error = "Failed to write $path")
  }
}

class CreateFileTool(private val fs: ProjectFileSystem, private val gate: WriteGate) : AgentTool {
  override val name = "create_file"
  override val description =
    "Create a brand-new file at the given path. Fails if the file already exists (use write_file or edit_file for existing files)."
  override val params = listOf(
    ToolParam("path", "New file path relative to the project root."),
    ToolParam("content", "Initial file content.", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val path = args.str("path")
    val content = args.str("content")
    if (fs.exists(ctx.project, path)) return ToolResult(false, error = "File already exists: $path (use write_file to overwrite)")
    gate(ctx, path)?.let { return it }
    return if (fs.createFile(ctx.project, path, content)) ToolResult(true, output = "Created $path", metadata = mapOf("file" to path))
    else ToolResult(false, error = "Failed to create $path")
  }
}

class DeleteFileTool(private val fs: ProjectFileSystem, private val gate: suspend (ToolContext, String) -> ToolResult?) : AgentTool {
  override val name = "delete_file"
  override val description =
    "Permanently delete one file from the workspace. Requires delete permission and explicit user approval."
  override val params = listOf(
    ToolParam("path", "File path relative to the project root.")
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val path = args.str("path")
    gate(ctx, path)?.let { return it }
    return if (fs.deleteFile(ctx.project, path)) ToolResult(true, output = "Deleted $path", metadata = mapOf("file" to path))
    else ToolResult(false, error = "Failed to delete $path")
  }
}

class MoveFileTool(private val fs: ProjectFileSystem, private val gate: WriteGate) : AgentTool {
  override val name = "move_file"
  override val description =
    "Move or rename a file to a new path inside the same workspace."
  override val params = listOf(
    ToolParam("path", "Existing file path relative to the project root."),
    ToolParam("new_path", "Destination path relative to the project root.")
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val path = args.str("path")
    val newPath = args.str("new_path")
    if (!fs.exists(ctx.project, path)) return ToolResult(false, error = "File not found: $path")
    gate(ctx, newPath)?.let { return it }

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

// ================= Terminal tools =================

class RunCommandTool(private val tm: TerminalProcessManager) : AgentTool {
  override val name = "run_command"
  override val description =
    "Execute a shell command in the workspace terminal — for builds, tests, git operations, package installation, or inspection. Destructive commands always require explicit user approval."
  override val params = listOf(
    ToolParam("command", "The shell command to execute, e.g. \"npm test\" or \"git log --oneline\".")
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val command = args.str("command").trim()
    if (command.isEmpty()) return ToolResult(false, error = "command must be a non-empty shell command string.")

    val guard = com.agentisco.agent.permission.DestructiveCommandGuard.assess(command)
    if (guard != null) {
      val ok = ctx.requestApproval(
        PendingApproval(
          id = "tool-appr-${System.currentTimeMillis()}",
          command = command, title = guard.title, impactDescription = guard.reason, isDestructive = true
        )
      )
      if (!ok) return ToolResult(false, error = "User rejected destructive command: $command", exitCode = -1)
    } else {
      val needsApproval = when (ctx.permissions().terminalCommands) {
        PermissionMode.ALWAYS_ASK -> true
        PermissionMode.NEVER_ALLOW -> return ToolResult(false, error = "Blocked by permission policy: terminal commands are not allowed.", exitCode = -1)
        PermissionMode.ALLOW_ALL -> false
        else -> !com.agentisco.agent.permission.DestructiveCommandGuard.isSafeCommand(command)
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
    // Unique process id per call so batched parallel commands never overwrite
    // each other's entry in the process registry, and so the UI can SIGKILL
    // this exact command.
    val runnerSession = ctx.terminalSession.copy(
      id = ctx.terminalSession.id + "-run-" + java.util.UUID.randomUUID().toString().take(8)
    )
    val killKey = ctx.toolCallId.ifBlank { runnerSession.id }
    ToolCancellation.register(killKey) { tm.interrupt(runnerSession.id) }
    val exitCode = try {
      tm.executeCommand(
        runnerSession, command,
        { line -> out.appendLine(line.text) },
        projectDir = File(ctx.project.path).takeIf { it.isDirectory }
      )
    } finally {
      ToolCancellation.unregister(killKey)
    }
    return ToolResult(success = exitCode == 0, output = out.toString().trim().take(8000), exitCode = exitCode)
  }
}

class InterruptTerminalTool(private val tm: TerminalProcessManager) : AgentTool {
  override val name = "interrupt_terminal"
  override val description =
    "Interrupt (SIGINT-style) a command that is currently running in the terminal — for example to stop a hung build or dev server."
  override val params = emptyList<ToolParam>()

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val sessionId = ctx.terminalSession.id
    return if (tm.interrupt(sessionId)) ToolResult(true, output = "Interrupted session $sessionId")
    else ToolResult(false, error = "Session $sessionId is not running")
  }
}

// ================= Development tools =================

abstract class ScriptTool(
  private val tm: TerminalProcessManager,
  toolName: String,
  private val script: String,
  private val label: String
) : AgentTool {
  override val name = toolName
  override val description = "$label the project using its package manager scripts (npm) when package.json exists."
  override val params = listOf(
    ToolParam("args", "Optional extra arguments passed to the $label command.", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val extra = args.str("args").trim()
    val hasNpm = File(ctx.project.path, "package.json").exists()
    val command = if (hasNpm) "npm run $script${if (extra.isBlank()) "" else " -- $extra"}" else "echo 'No package.json in ${ctx.project.name}'"
    val out = StringBuilder()
    val runnerSession = ctx.terminalSession.copy(
      id = ctx.terminalSession.id + "-run-" + java.util.UUID.randomUUID().toString().take(8)
    )
    val killKey = ctx.toolCallId.ifBlank { runnerSession.id }
    ToolCancellation.register(killKey) { tm.interrupt(runnerSession.id) }
    val exitCode = try {
      tm.executeCommand(
        runnerSession, command,
        { line -> out.appendLine(line.text) },
        projectDir = File(ctx.project.path).takeIf { it.isDirectory }
      )
    } finally {
      ToolCancellation.unregister(killKey)
    }
    return ToolResult(success = exitCode == 0, output = out.toString().trim().take(8000), exitCode = exitCode, metadata = mapOf("command" to command))
  }
}

class BuildTool(tm: TerminalProcessManager) : ScriptTool(tm, "build", "build", "Build")
class TestTool(tm: TerminalProcessManager) : ScriptTool(tm, "test", "test", "Run tests for")

// ================= Git tools =================

class GitStatusTool(private val git: GitRepositoryManager) : AgentTool {
  override val name = "git_status"
  override val description =
    "Show which files have been modified in the workspace and whether the working tree is dirty."
  override val params = emptyList<ToolParam>()

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
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
  override val description =
    "Inspect the current uncommitted modifications as a diff (added/removed lines), for the whole project or one file."
  override val params = listOf(
    ToolParam("path", "Optional file path to diff. Omit to diff all changed files.", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val path = args.str("path").takeIf { it.isNotBlank() }
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
  override val description =
    "Stage a modified file (or all changes) so it can be committed."
  override val params = listOf(
    ToolParam("path", "File path to stage. Pass \"all\" or omit to stage every change.", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val path = args.str("path").takeIf { it.isNotBlank() && it != "all" }
    return if (path == null) {
      onStageAll(); ToolResult(true, output = "Staged all changed files")
    } else {
      onStageFile(path); ToolResult(true, output = "Staged $path")
    }
  }
}

class GitCommitTool(private val git: GitRepositoryManager, private val stagedFilesProvider: () -> Set<String>) : AgentTool {
  override val name = "git_commit"
  override val description =
    "Commit the currently staged changes with a concise message. Nothing is committed when the staging area is empty."
  override val params = listOf(
    ToolParam("message", "Commit message describing the change.")
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val message = args.str("message").trim()
    if (message.isEmpty()) return ToolResult(false, error = "message must be a non-empty commit message.")
    if (!ctx.permissions().gitCommit) return ToolResult(false, error = "Blocked by permission policy: git commits are not allowed.")
    val staged = stagedFilesProvider()
    if (staged.isEmpty()) return ToolResult(false, error = "Nothing is staged. Stage changes with git_stage first.")
    val commit = git.commit(ctx.project, staged, message)
    return if (commit != null) ToolResult(true, output = "Committed ${staged.size} file(s): ${commit.hash} ${commit.message}", metadata = mapOf("hash" to commit.hash))
    else ToolResult(false, error = "Commit failed")
  }
}
