package com.awaki.agent.tool

import com.awaki.agent.model.PendingApproval
import com.awaki.agent.model.PermissionMode
import com.awaki.data.model.DiffLineType
import com.awaki.data.model.Project
import com.awaki.workspace.filesystem.ProjectFileSystem
import com.awaki.workspace.git.GitRepositoryManager
import com.awaki.workspace.terminal.TerminalProcessManager
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
  private val onUnstageAll: () -> Unit,
  /** Extra tools appended to the built-in set (used by tests). */
  private val extraTools: List<AgentTool> = emptyList(),
  /**
   * The web layer the two web tools read through: the providers chosen in Settings, the URL itself after.
   * Production passes the repository's instance so a Settings change lands on the same
   * request budget the agent is spending; a test that never calls a web tool may leave
   * the default, which is an unconfigured pool.
   */
  private val webGateway: com.awaki.agent.web.WebGateway = com.awaki.agent.web.WebGateway(),
  /** Set when a runtime exists to run delegated work; without it there is no `delegate` tool. */
  private val subagentLauncher: SubagentLauncher? = null,
  /** The team the delegate tool offers, including any custom agent the user defined. */
  private val subagentRoles: () -> List<com.awaki.agent.model.AgentRole> =
    { com.awaki.agent.model.AgentRoles.builtIn },
  /** Reads the `SKILL.md` folders; tests can point it at a scratch workspace. */
  private val skillStore: com.awaki.agent.skill.SkillStore =
    com.awaki.agent.skill.SkillStore(),
  /** When non-null, only these tools are offered - the list a research run gets. */
  private val restrictTo: Set<String>? = null
) {

  private val offered: List<AgentTool> = listOf(
    ListFilesTool(fileSystem),
    ReadFileTool(),
    ReadFilesTool(),
    SearchFilesTool(),
    RegexSearchTool(),
    GlobFilesTool(),
    FileInfoTool(),
    DirectoryTreeTool(),
    TaskPlanTool(),
    WriteFileTool(PermissionGates::fileWrite),
    EditFileTool(PermissionGates::fileWrite),
    EditFilesTool(PermissionGates::fileWrite),
    CreateFileTool(PermissionGates::fileWrite),
    CreateDirectoryTool(),
    DeleteFileTool(PermissionGates::fileDelete),
    MoveFileTool(PermissionGates::fileWrite),
    CopyFileTool(PermissionGates::fileWrite),
    RunCommandTool(terminalManager),
    TerminalOutputTool(terminalManager),
    WriteTerminalInputTool(terminalManager),
    InterruptTerminalTool(terminalManager),
    GitStatusTool(gitManager),
    GitDiffTool(gitManager),
    GitLogTool(gitManager),
    GitShowTool(gitManager),
    GitStageTool(onStageFile, onStageAll),
    GitCommitTool(gitManager, stagedFilesProvider),
    BuildTool(terminalManager),
    TestTool(terminalManager),
    WebFetchTool(webGateway),
    WebSearchTool(webGateway),
    AskUserTool(),
    UseSkillTool(skillStore)
  ) + extraTools + listOfNotNull(subagentLauncher?.let { SubagentTool(it, subagentRoles) })

  /**
   * A delegated run is offered only its own tool set, so a sub-agent cannot even
   * name a tool it is not allowed to use.
   */
  val tools: List<AgentTool> =
    restrictTo?.let { names -> offered.filter { tool -> tool.name in names } } ?: offered

  private val byName = tools.associateBy { it.name }

  fun specs() = tools.map {
    com.awaki.agent.llm.LlmToolSpec(it.name, it.description, it.parametersJsonSchema())
  }

  fun get(name: String): AgentTool? = byName[name]

  /**
   * The same collaborators offering what one role may use: a research role gets
   * only the planning set, every other role gets the working set minus
   * `delegate` - a sub-agent that could delegate would multiply one request into
   * an unbounded number of model calls. A role with its own [toolNames], which is
   * how a user-defined specialist arrives, gets that list narrowed to the same
   * reach, never widened by it.
   */
  fun forDelegation(role: com.awaki.agent.model.AgentRole): AgentToolRegistry = AgentToolRegistry(
    fileSystem = fileSystem,
    gitManager = gitManager,
    terminalManager = terminalManager,
    stagedFilesProvider = stagedFilesProvider,
    onStageFile = onStageFile,
    onStageAll = onStageAll,
    onUnstageAll = onUnstageAll,
    webGateway = webGateway,
    restrictTo = delegatedToolsFor(role)
  )

  /**
   * The reach of one delegated run: research is limited to what the planning gate
   * lets through, anything else gets the working set minus `delegate`. The
   * custom-agent editor offers exactly this list, so what a user can check and
   * what a run can hold never diverge.
   */
  fun delegableToolNames(readOnly: Boolean): Set<String> =
    if (readOnly) PlanMode.delegatedToolNames else offered.map { it.name }.toSet() - "delegate"

  /**
   * What the role is actually given. A role that names its own tools - a
   * user-defined specialist - only ever narrows [delegableToolNames]: a name that
   * does not exist, or that a sub-agent may not have, buys nothing.
   */
  private fun delegatedToolsFor(role: com.awaki.agent.model.AgentRole): Set<String> {
    val reachable = delegableToolNames(role.readOnly)
    val chosen = role.toolNames.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    return if (chosen.isEmpty()) reachable else reachable intersect chosen
  }

  /**
   * The same collaborators offering exactly [names] - how an on-device model gets a
   * prompt small enough to prefill on a phone. Narrowing rather than re-deriving is
   * the point: a name that does not exist buys nothing, and dropping the launcher
   * means a run with a hand-picked set can never delegate, so one short request
   * cannot still fan out into a tree of model calls.
   */
  fun narrowedTo(names: Set<String>): AgentToolRegistry = AgentToolRegistry(
    fileSystem = fileSystem,
    gitManager = gitManager,
    terminalManager = terminalManager,
    stagedFilesProvider = stagedFilesProvider,
    onStageFile = onStageFile,
    onStageAll = onStageAll,
    onUnstageAll = onUnstageAll,
    extraTools = extraTools,
    webGateway = webGateway,
    skillStore = skillStore,
    restrictTo = names
  )

  /**
   * Every tool a run may be handed, with what its description and JSON schema cost
   * the model in tokens. The on-device editor lists these so the choice is made
   * against a number instead of a guess.
   */
  fun offerableTools(): List<ToolOffering> =
    offered.filterNot { it.name == "delegate" }.map {
      ToolOffering(it.name, charsOf(it))
    }

  private fun charsOf(tool: AgentTool): Int =
    tool.name.length + tool.description.length + tool.parametersJsonSchema().length
}

/** One tool as the tool picker sees it: its name and its prompt cost in characters. */
data class ToolOffering(val name: String, val charCost: Int) {
  val estimatedTokens: Int get() = com.awaki.agent.compact.charsToTokens(charCost)
}

/**
 * What an on-device model is offered until the user chooses otherwise.
 *
 * Every tool the runtime knows costs its description plus its JSON schema in the
 * prompt, and a phone has to prefill all of it before the first word appears. So
 * the default is the shortest set that still completes a work loop - find, read,
 * change, run, ask - rather than the whole registry. `task_plan`, git and the web
 * tools are deliberately absent: each is useful, none is needed for the turn to
 * finish, and the picker lets a user trade tokens for them per model.
 */
object OnDeviceTools {
  val DEFAULT: Set<String> = setOf(
    "read_file",
    "list_files",
    "search_files",
    "edit_file",
    "write_file",
    "run_command",
    "ask_user"
  )

  /** Names the user typed or an old record stored that no longer exist are dropped. */
  fun resolve(chosen: Set<String>, offerable: Set<String>): Set<String> =
    (chosen intersect offerable).ifEmpty { emptySet() }
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

class ReadFileTool : AgentTool {
  override val name = "read_file"
  override val description =
    "Read a file in the workspace as text. Returns the whole file when it fits; for long files it returns a slice and says exactly which lines are missing, so request start_line/max_lines only when you need a later part. Never use this on binary files."
  override val params = listOf(
    ToolParam("path", "File path relative to the project root, e.g. \"src/App.tsx\"."),
    ToolParam(
      "start_line",
      "1-based line to start reading from (default 1). Use it to continue after a truncated read.",
      type = "integer", required = false
    ),
    ToolParam(
      "max_lines",
      "Maximum number of lines to return (default $DEFAULT_MAX_LINES, max $MAX_LINES).",
      type = "integer", required = false
    )
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val path = WorkspacePath.resolve(ctx.project, args.str("path"))
      ?: return ToolResult(false, error = "Invalid path: the workspace can only be read through a relative path inside it.")
    val startLine = args.optInt("start_line", 1).coerceAtLeast(1)
    val maxLines = args.optInt("max_lines", DEFAULT_MAX_LINES).coerceIn(1, MAX_LINES)
    return readTextLines(ctx.project, path, LineSlice(startLine, maxLines))
  }

  companion object {
    const val DEFAULT_MAX_LINES = 1200
    const val MAX_LINES = 5000
  }
}

/** Slice of a text file with honest reporting of what was left out. */
internal fun readTextLines(project: Project, path: String, slice: LineSlice): ToolResult {
  val read = WorkspaceText.read(project, path)
  if (read !is TextRead.Ok) return ToolResult(false, error = read.errorMessage())
  val raw = read.content.lines()
  val lines = if (raw.size > 1 && raw.last().isEmpty()) raw.dropLast(1) else raw
  val (selected, omitted) = slice.take(lines)
  val header = buildString {
    append(path)
    append(" — ")
    append(lines.size)
    append(" lines")
    if (read.lineEnding == "\r\n") append(" (CRLF line endings)")
    if (slice.startLine > 1) append(", from line ${slice.startLine}")
  }
  val body = selected.joinToString("\n")
  val notes = mutableListOf<String>()
  if (omitted > 0) {
    notes += "${slice.startLine - 1 + selected.size + 1}..${lines.size} (${omitted} lines) not shown — read again with start_line=${slice.startLine + selected.size}"
  }
  val bounded = ToolOutput.limit(body, ToolOutput.READ_CHARS, "lower max_lines or request a start_line to see more")
  if (bounded !== body) notes += "output hit the character budget"
  return ToolResult(
    success = true,
    output = buildString {
      appendLine(header)
      append(bounded)
      if (notes.isNotEmpty()) {
        appendLine()
        appendLine("[…${notes.joinToString("; ")}]")
      }
    },
    metadata = mapOf("path" to path, "lines" to lines.size.toString())
  )
}

class SearchFilesTool : AgentTool {
  override val name = "search_files"
  override val description =
    "Find where a piece of text, symbol, or error message appears across the workspace. Returns file:line:content matches. Fast fixed-string search; use regex_search for patterns and glob_files for names."
  override val params = listOf(
    ToolParam("query", "Text to search for, e.g. a class name, function name, or message string."),
    ToolParam("glob", "Optional glob filter for file paths, e.g. \"**/*.kt\" or \"src/**/*.ts\".", required = false),
    ToolParam("case_sensitive", "Match case exactly (default false).", type = "boolean", required = false),
    ToolParam("max_matches", "Maximum matches to return (default ${ContentSearch.DEFAULT_MAX_MATCHES}).", type = "integer", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val query = args.str("query")
    if (query.isEmpty()) return ToolResult(false, error = "query must be a non-empty search string.")
    return ContentSearch.run(
      project = ctx.project,
      matches = { line -> line.contains(query, ignoreCase = !args.optBoolean("case_sensitive", false)) },
      glob = args.str("glob"),
      maxMatches = args.optInt("max_matches", ContentSearch.DEFAULT_MAX_MATCHES).coerceIn(1, ContentSearch.HARD_MAX_MATCHES),
      label = "\"$query\""
    )
  }
}

class WriteFileTool(private val gate: WriteGate) : AgentTool {
  override val name = "write_file"
  override val description =
    "Create a file or replace an existing file's ENTIRE content. You must supply the complete final file: partial content silently destroys the rest. For a targeted change inside a larger file use edit_file (or edit_files for several changes at once)."
  override val params = listOf(
    ToolParam("path", "File path relative to the project root."),
    ToolParam("content", "The complete new file content (replaces any existing content). Pass \"\" only for a file you are deliberately emptying.", allowBlank = true)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val path = WorkspacePath.resolve(ctx.project, args.str("path"))
      ?: return ToolResult(false, error = "Invalid path: files can only be written inside the workspace.")
    val content = args.str("content")
    return FileChangeLock.withLock(ctx.project.path, path) {
      val existing = WorkspaceText.read(ctx.project, path)
      if (existing is TextRead.Ok && existing.content.isNotBlank() && content.isBlank()) {
        return@withLock ToolResult(
          false,
          error = "Refused: $path has ${existing.content.lines().size} lines of content and the new content is empty. " +
            "Use edit_file to remove parts of a file, or delete_file to remove the file."
        )
      }
      if (existing !is TextRead.Missing && existing !is TextRead.Ok) {
        return@withLock ToolResult(false, error = existing.errorMessage())
      }
      // Replacing a file keeps its own line-ending convention.
      val finalContent = if (existing is TextRead.Ok) {
        WorkspaceText.withLineEndings(content, existing.lineEnding)
      } else content
      gate(ctx, path)?.let { return@withLock it }
      val failure = WorkspaceText.write(File(ctx.project.path, path), finalContent)
      if (failure != null) return@withLock ToolResult(false, error = "Failed to write $path: $failure")
      val result = if (existing is TextRead.Ok) {
        "Replaced ${WorkspaceText.lineCount(existing.content)} lines with ${WorkspaceText.lineCount(finalContent)} in $path"
      } else "Wrote ${WorkspaceText.lineCount(finalContent)} lines to $path"
      ToolResult(
        success = true,
        output = result,
        metadata = mapOf("file" to path)
      )
    }
  }
}

class EditFileTool(private val gate: WriteGate) : AgentTool {
  override val name = "edit_file"
  override val description =
    "Apply one targeted modification to an existing file by replacing an exact snippet. Copy old_string from read_file output verbatim (including indentation) — line-ending differences are handled for you. Include enough context to make it unique, or set replace_all. For several changes at once use edit_files."
  override val params = listOf(
    ToolParam("path", "Existing file path relative to the project root."),
    ToolParam("old_string", "Exact text to replace; must appear once unless replace_all is true."),
    ToolParam("new_string", "Replacement text. Pass \"\" to delete the matched snippet.", allowBlank = true),
    ToolParam("replace_all", "Replace every occurrence instead of requiring a unique one (default false).", type = "boolean", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val path = WorkspacePath.resolve(ctx.project, args.str("path"))
      ?: return ToolResult(false, error = "Invalid path: files can only be edited inside the workspace.")
    if (!args.has("new_string")) {
      return ToolResult(false, error = "new_string is required. Pass the replacement text, or \"\" to delete the snippet.")
    }
    val edit = SnippetEdit(
      old = args.str("old_string"),
      new = args.str("new_string"),
      replaceAll = args.optBoolean("replace_all", false)
    )
    return FileChangeLock.withLock(ctx.project.path, path) {
      val read = WorkspaceText.read(ctx.project, path)
      if (read !is TextRead.Ok) return@withLock ToolResult(false, error = read.errorMessage())
      when (val outcome = applySnippetEdit(read.content, read.lineEnding, edit)) {
        is EditOutcome.Rejected -> return@withLock ToolResult(false, error = "In $path: ${outcome.reason}")
        is EditOutcome.Applied -> {
          gate(ctx, path)?.let { return@withLock it }
          val failure = WorkspaceText.write(read.file, outcome.content)
          if (failure != null) return@withLock ToolResult(false, error = "Failed to write $path: $failure")
          val deltaText = if (outcome.lineDelta == 0) "" else " (${if (outcome.lineDelta > 0) "+" else ""}${outcome.lineDelta} lines)"
          val scopeText = if (outcome.occurrences > 1) ", replaced ${outcome.occurrences} occurrences" else ""
          ToolResult(
            success = true,
            output = "Edited $path at line ${outcome.atLine}$deltaText$scopeText",
            metadata = mapOf("file" to path)
          )
        }
      }
    }
  }
}

/** How many times [needle] occurs in [haystack] (non-overlapping). */
internal fun countOccurrences(haystack: String, needle: String): Int =
  if (needle.isEmpty()) 0 else haystack.split(needle).size - 1

/** Replaces only the first occurrence, without regex or replace-all semantics. */
internal fun singleReplace(haystack: String, needle: String, replacement: String): String {
  val at = haystack.indexOf(needle)
  if (at < 0) return haystack
  return haystack.substring(0, at) + replacement + haystack.substring(at + needle.length)
}

/** 1-based line number containing [offset] characters. */
internal fun lineIndexOf(content: String, offset: Int): Int =
  content.take(maxOf(0, offset)).count { it == '\n' } + 1

/**
 * Tells the model *why* its snippet missed. The frequent causes are reindented
 * code and copied line-number/gutter text, so say which one it is instead of
 * returning a bare "not found" that invites a full-file rewrite.
 */
internal fun describeMiss(content: String, snippet: String): String {
  val fileLines = content.lines()
  val snippetLines = snippet.lines().filter { it.isNotBlank() }
  if (snippetLines.isEmpty()) return "old_string is blank."
  val normalizedFile = fileLines.map { it.trim() }
  val normalizedSnippet = snippetLines.map { it.trim() }
  val firstLineHits = normalizedFile.withIndex()
    .filter { it.value == normalizedSnippet.first() }
    .map { it.index + 1 }
  if (firstLineHits.isEmpty()) {
    val present = normalizedSnippet.count { line -> line in normalizedFile }
    return if (present == 0) {
      "Its first line \"${snippetLines.first().trim().take(60)}\" does not occur in the file at all. Read the file again and copy the snippet exactly."
    } else {
      "$present of ${normalizedSnippet.size} snippet lines occur in the file but not as a contiguous block — the surrounding lines differ. Re-read the region and copy it verbatim."
    }
  }
  if (normalizedSnippet.size > 1) {
    val aligned = firstLineHits.firstOrNull { start ->
      val from = start - 1
      from + normalizedSnippet.size <= normalizedFile.size &&
        normalizedFile.subList(from, from + normalizedSnippet.size) == normalizedSnippet
    }
    if (aligned != null) {
      return "The snippet matches the file at line $aligned once whitespace is ignored: the indentation or line endings differ. Re-read lines around $aligned and copy them exactly."
    }
  }
  return "Its first line occurs at line ${firstLineHits.first()} but the rest of the snippet differs there. Re-read around line ${firstLineHits.first()} and copy exactly."
}

class CreateFileTool(private val gate: WriteGate) : AgentTool {
  override val name = "create_file"
  override val description =
    "Create a brand-new file at the given path (parent folders are created). Fails if the file already exists — use write_file or edit_file for existing files."
  override val params = listOf(
    ToolParam("path", "New file path relative to the project root."),
    ToolParam("content", "Initial file content.", required = false, allowBlank = true)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val path = WorkspacePath.resolve(ctx.project, args.str("path"))
      ?: return ToolResult(false, error = "Invalid path: files can only be created inside the workspace.")
    val content = args.str("content")
    return FileChangeLock.withLock(ctx.project.path, path) {
      if (File(ctx.project.path, path).exists()) {
        return@withLock ToolResult(false, error = "Already exists: $path (use write_file to replace it or edit_file to modify it)")
      }
      gate(ctx, path)?.let { return@withLock it }
      val failure = WorkspaceText.write(File(ctx.project.path, path), content)
      if (failure != null) return@withLock ToolResult(false, error = "Failed to create $path: $failure")
      ToolResult(
        success = true,
        output = "Created $path (${WorkspaceText.lineCount(content)} lines)",
        metadata = mapOf("file" to path)
      )
    }
  }
}

class DeleteFileTool(private val gate: suspend (ToolContext, String) -> ToolResult?) : AgentTool {
  override val name = "delete_file"
  override val description =
    "Permanently delete one file from the workspace. Deleting a folder needs recursive true and is refused for the workspace root. Requires delete permission and explicit user approval."
  override val params = listOf(
    ToolParam("path", "File path relative to the project root."),
    ToolParam("recursive", "Allow deleting a folder and everything inside it (default false).", type = "boolean", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val path = WorkspacePath.resolve(ctx.project, args.str("path"))
      ?: return ToolResult(false, error = "Invalid path: only files inside the workspace can be deleted.")
    val recursive = args.optBoolean("recursive", false)
    val file = File(ctx.project.path, path)
    return FileChangeLock.withLock(ctx.project.path, path) {
      if (!file.exists()) return@withLock ToolResult(false, error = "Not found: $path")
      if (file.isDirectory && !recursive) {
        return@withLock ToolResult(false, error = "$path is a folder. Pass recursive true to delete it with its contents.")
      }
      gate(ctx, if (file.isDirectory) "$path/ (recursively)" else path)?.let { return@withLock it }
      val removed = if (file.isDirectory) file.deleteRecursively() else file.delete()
      if (!removed && file.exists()) return@withLock ToolResult(false, error = "Failed to delete $path (it still exists)")
      ToolResult(success = true, output = "Deleted $path", metadata = mapOf("file" to path))
    }
  }
}

class MoveFileTool(private val gate: WriteGate) : AgentTool {
  override val name = "move_file"
  override val description =
    "Move or rename a file inside the workspace. The bytes are moved as-is, so binary files and large files survive untouched."
  override val params = listOf(
    ToolParam("path", "Existing file path relative to the project root."),
    ToolParam("new_path", "Destination path relative to the project root.")
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult =
    moveOrCopy(ctx, args, gate, deleteSource = true)
}

class CopyFileTool(private val gate: WriteGate) : AgentTool {
  override val name = "copy_file"
  override val description =
    "Copy a file to a new path inside the workspace, byte for byte. Fails if the destination already exists."
  override val params = listOf(
    ToolParam("path", "Existing file path relative to the project root."),
    ToolParam("new_path", "Destination path relative to the project root.")
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult =
    moveOrCopy(ctx, args, gate, deleteSource = false)
}

private suspend fun moveOrCopy(
  ctx: ToolContext,
  args: JSONObject,
  gate: WriteGate,
  deleteSource: Boolean
): ToolResult {
  val action = if (deleteSource) "move_file" else "copy_file"
  val from = WorkspacePath.resolve(ctx.project, args.str("path"))
    ?: return ToolResult(false, error = "Invalid source path for $action.")
  val to = WorkspacePath.resolve(ctx.project, args.str("new_path"))
    ?: return ToolResult(false, error = "Invalid destination path for $action.")
  val source = File(ctx.project.path, from)
  val target = File(ctx.project.path, to)
  val paths = if (deleteSource) listOf(from, to) else listOf(to)
  return FileChangeLock.withLocks(ctx.project.path, paths) {
    if (!source.exists()) return@withLocks ToolResult(false, error = "Not found: $from")
    if (source.isDirectory) return@withLocks ToolResult(false, error = "$from is a folder — $action works on files only.")
    if (from == to) return@withLocks ToolResult(false, error = "Source and destination are the same file: $from")
    if (target.exists()) return@withLocks ToolResult(false, error = "Destination already exists: $to")
    gate(ctx, to)?.let { return@withLocks it }
    val moved = runCatching {
      target.parentFile?.mkdirs()
      if (deleteSource) {
        java.nio.file.Files.move(source.toPath(), target.toPath())
      } else {
        java.nio.file.Files.copy(source.toPath(), target.toPath())
      }
      true
    }.getOrDefault(false)
    if (!moved) return@withLocks ToolResult(false, error = "Failed to $action $from -> $to")
    if (deleteSource && source.exists()) {
      return@withLocks ToolResult(false, error = "Copied to $to but the original $from is still there")
    }
    val verb = if (deleteSource) "Moved" else "Copied"
    ToolResult(
      success = true,
      output = "$verb $from to $to (${target.length()} bytes)",
      metadata = mapOf("file" to to)
    )
  }
}

// ================= Terminal tools =================

class RunCommandTool(private val tm: TerminalProcessManager) : AgentTool {
  override val name = "run_command"
  override val description =
    "Execute a shell command in the project workspace (Linux environment) — builds, tests, git, package installation, inspection. It is killed after timeout_seconds unless it finishes first, so it can never hang the turn; for commands meant to keep running (dev server, watch, long test suite) pass run_in_background true and read them with terminal_output. Destructive commands always require explicit user approval."
  override val params = listOf(
    ToolParam("command", "The shell command to execute, e.g. \"npm test\" or \"git log --oneline\"."),
    ToolParam(
      "timeout_seconds",
      "Stop the command after this many seconds (default $DEFAULT_TIMEOUT_SECONDS, max $MAX_TIMEOUT_SECONDS). Ignored for background runs.",
      type = "integer", required = false
    ),
    ToolParam(
      "run_in_background",
      "Start the command and return immediately with a runner id, keeping it alive while you continue. Read it later with terminal_output.",
      type = "boolean", required = false
    )
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val command = args.str("command").trim()
    if (command.isEmpty()) return ToolResult(false, error = "command must be a non-empty shell command string.")

    val guard = com.awaki.agent.permission.DestructiveCommandGuard.assess(command)
    if (guard != null) {
      val decision = ctx.requestApprovalDecision(
        PendingApproval(
          id = newApprovalId(),
          command = command, title = guard.title, impactDescription = guard.reason, isDestructive = true
        )
      )
      if (!decision.approved) return ToolResult(false, error = decision.describeNotExecuted("Command not executed", command), exitCode = -1)
    } else {
      val needsApproval = when (ctx.permissions().terminalCommands) {
        PermissionMode.ALWAYS_ASK -> true
        PermissionMode.NEVER_ALLOW -> return ToolResult(false, error = "Blocked by permission policy: terminal commands are not allowed.", exitCode = -1)
        PermissionMode.ALLOW_ALL -> false
        else -> !com.awaki.agent.permission.DestructiveCommandGuard.isSafeCommand(command)
      }
      if (needsApproval) {
        val decision = ctx.requestApprovalDecision(
          PendingApproval(
            id = newApprovalId(),
            command = command, title = "Agent wants to run a command",
            impactDescription = "Runs in terminal session \"${ctx.terminalSession.name}\": $command",
            isDestructive = false
          )
        )
        if (!decision.approved) return ToolResult(false, error = decision.describeNotExecuted("Command not executed", command), exitCode = -1)
      }
    }

    val projectDir = File(ctx.project.path).takeIf { it.isDirectory }
    // Unique process id per call so batched parallel commands never overwrite
    // each other's entry in the process registry, and so the UI can SIGKILL
    // this exact command.
    val runnerSession = ctx.terminalSession.copy(id = newAgentRunnerId(ctx.terminalSession.id))

    if (args.optBoolean("run_in_background", false)) {
      val runId = tm.startBackground(runnerSession, command, projectDir)
        ?: return ToolResult(
          false,
          error = "The Linux environment is not ready, so the command could not start. Open the Terminal tab once to bootstrap it.",
          exitCode = -1
        )
      return ToolResult(
        success = true,
        output = "Started in background as \"$runId\": $command\n" +
          "It keeps running while you work. Check it with terminal_output(runner_id=\"$runId\").",
        metadata = mapOf("runner" to runId)
      )
    }

    val out = StringBuilder()
    val timeoutSeconds = args.optInt("timeout_seconds", DEFAULT_TIMEOUT_SECONDS).coerceIn(1, MAX_TIMEOUT_SECONDS)
    val killKey = ctx.toolCallId.ifBlank { runnerSession.id }
    ToolCancellation.register(killKey) { tm.interrupt(runnerSession.id) }
    val outcome = try {
      kotlinx.coroutines.withTimeoutOrNull(timeoutSeconds * 1000L) {
        try {
          tm.executeCommand(
            runnerSession, command,
            { line -> out.appendLine(line.text) },
            projectDir = projectDir
          )
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        }
      }
    } finally {
      ToolCancellation.unregister(killKey)
    }
    if (outcome == null) {
      // The coroutine is only cancelled once the process itself is gone: a
      // blocking waitFor() ignores cancellation until then.
      tm.interrupt(runnerSession.id)
      return ToolResult(
        success = false,
        error = "Command was stopped after ${timeoutSeconds}s without finishing. " +
          "If it is meant to keep running, re-run it with run_in_background true.",
        output = ToolOutput.limit(out.toString().trim(), ToolOutput.COMMAND_CHARS, "the command was stopped before it finished"),
        exitCode = -1
      )
    }
    val exitCode = outcome
    return ToolResult(
      success = exitCode == 0,
      output = ToolOutput.limit(out.toString().trim(), ToolOutput.COMMAND_CHARS, "rerun with a narrower command or pipe through tail"),
      exitCode = exitCode
    )
  }

  companion object {
    const val DEFAULT_TIMEOUT_SECONDS = 120
    const val MAX_TIMEOUT_SECONDS = 1800
  }
}

/**
 * Reads a command started in the background, or lists the ones that exist.
 * Without this the agent has no way to see a dev server's log and would resort
 * to re-running blocking commands.
 */
class TerminalOutputTool(private val tm: TerminalProcessManager) : AgentTool {
  override val name = "terminal_output"
  override val description =
    "Read the output and status of a command started with run_command(run_in_background=true): whether it is still running, its exit code once finished, and its recent output. Omit runner_id to list every background command."
  override val params = listOf(
    ToolParam("runner_id", "The id reported when the command was started.", required = false, allowBlank = true),
    ToolParam("tail_lines", "How many trailing lines to return (default $DEFAULT_TAIL).", type = "integer", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val runnerId = args.str("runner_id").trim()
    val tailLines = args.optInt("tail_lines", DEFAULT_TAIL).coerceIn(1, MAX_TAIL)
    if (runnerId.isEmpty()) {
      val runs = tm.listBackgroundRuns()
      if (runs.isEmpty()) return ToolResult(true, output = "No background commands are running.")
      return ToolResult(
        success = true,
        output = runs.joinToString("\n") { run ->
          "${run.id}  ${if (run.running) "running" else "exit ${run.exitCode ?: "?"}"}  ${run.startedSecondsAgo}s  ${run.command.take(120)}"
        },
        metadata = mapOf("runners" to runs.size.toString())
      )
    }
    val run = tm.readBackground(runnerId, tailLines)
      ?: return ToolResult(false, error = "Unknown runner_id \"$runnerId\". Call terminal_output with no runner_id to list them.")
    val state = if (run.running) "running (started ${run.startedSecondsAgo}s ago)" else "finished with exit code ${run.exitCode}"
    val body = run.output.ifBlank { "(no output yet)" }
    val hidden = if (run.droppedLines > 0) "\n[…${run.droppedLines} earlier line(s) dropped from the output buffer]" else ""
    return ToolResult(
      success = true,
      output = ToolOutput.limit("$runnerId — $run.command\n$state\n\n$body$hidden", ToolOutput.COMMAND_CHARS, "lower tail_lines"),
      exitCode = run.exitCode,
      metadata = mapOf("runner" to runnerId, "running" to run.running.toString())
    )
  }

  companion object {
    const val DEFAULT_TAIL = 200
    const val MAX_TAIL = 2_000
  }
}

class InterruptTerminalTool(private val tm: TerminalProcessManager) : AgentTool {
  override val name = "interrupt_terminal"
  override val description =
    "Stop a command the agent started — pass the runner_id from run_command(run_in_background=true), or omit it to stop the newest command that is still running. Use it for a hung build or a server you no longer need."
  override val params = listOf(
    ToolParam("runner_id", "Background command to stop; omit for the newest running one.", required = false, allowBlank = true)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val requested = args.str("runner_id").trim()
    val target = when {
      requested.isNotEmpty() -> requested
      else -> tm.listBackgroundRuns().firstOrNull { it.running }?.id ?: ""
    }
    if (target.isEmpty()) {
      return ToolResult(
        success = false,
        error = "Nothing is running that you started. Start a command with run_command, " +
          "or pass the runner_id of a background command."
      )
    }
    // Only the agent's own commands: never the terminal session the user is typing in.
    if (!isAgentRunner(target)) {
      return ToolResult(
        success = false,
        error = "\"$target\" is not a command the agent started, so it will not be stopped. " +
          "Stop your own terminal from the Terminal tab."
      )
    }
    return if (tm.interrupt(target)) {
      ToolResult(true, output = "Stopped $target", metadata = mapOf("runner" to target))
    } else {
      val running = tm.listBackgroundRuns().filter { it.running }.map { it.id }
      ToolResult(
        success = false,
        error = if (running.isEmpty()) "Nothing is running: $target"
        else "$target is not running. Still running: ${running.joinToString(", ")}"
      )
    }
  }
}

/**
 * Answers a prompt a running command is waiting on: an installer asking y/n, a
 * generator asking for a name, a REPL. Without it the agent can only start such a
 * command and watch it block until stdin closes.
 */
class WriteTerminalInputTool(private val tm: TerminalProcessManager) : AgentTool {
  override val name = "write_terminal_input"
  override val description =
    "Send one line of input to a command that is waiting for an answer (a y/n prompt, a name, a REPL expression). Pass the runner_id from run_command, or omit it for the newest command that is still running; an empty input presses Enter. Read the reply with terminal_output."
  override val params = listOf(
    ToolParam("input", "The line to send to the command's stdin. \"\" presses Enter.", allowBlank = true),
    ToolParam("runner_id", "Command to send it to; omit for the newest running one.", required = false, allowBlank = true)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val input = args.str("input")
    val target = args.str("runner_id").trim().ifEmpty {
      tm.listBackgroundRuns().firstOrNull { it.running }?.id.orEmpty()
    }
    if (target.isEmpty()) {
      return ToolResult(
        success = false,
        error = "No command is running, so there is nothing to send input to. Start one with run_command (run_in_background true)."
      )
    }
    if (!isAgentRunner(target)) {
      return ToolResult(
        success = false,
        error = "\"$target\" is not a command the agent started, so nothing is typed into it. " +
          "Start the command with run_command and use the runner_id it reports."
      )
    }
    if (!tm.isRunning(target)) {
      val running = tm.listBackgroundRuns().filter { it.running }.map { it.id }
      return ToolResult(
        success = false,
        error = if (running.isEmpty()) "$target has already finished — read its output with terminal_output."
        else "$target is not running. Still waiting for input: ${running.joinToString(", ")}"
      )
    }
    if (!tm.writeInput(target, input)) {
      return ToolResult(
        false,
        error = "Could not write to $target: its input is closed. Check terminal_output, then interrupt_terminal and re-run with the answer piped in."
      )
    }
    return ToolResult(
      success = true,
      output = "Sent ${if (input.isEmpty()) "an empty line (Enter)" else "\"" + input.take(200) + "\""} to $target. Read the reply with terminal_output.",
      metadata = mapOf("runner" to target)
    )
  }
}

// ================= Development tools =================

/**
 * `npm run <script>` with a friendly name. The command itself goes through
 * [RunCommandTool] so the terminal permission policy, the destructive guard, the
 * timeout and background execution behave identically for every way the model can
 * ask for a command — a scripted tool must not be a way around the policy.
 */
abstract class ScriptTool(
  tm: TerminalProcessManager,
  toolName: String,
  private val script: String,
  private val label: String
) : AgentTool {
  private val runner = RunCommandTool(tm)

  override val name = toolName
  override val description =
    "$label the project with its package.json script (npm run $script). Output, timeout and background behaviour work exactly like run_command; for a non-npm project use run_command with its own tool."
  override val params = listOf(
    ToolParam("args", "Optional extra arguments passed to the $label command.", required = false, allowBlank = true),
    ToolParam(
      "timeout_seconds",
      "Stop the command after this many seconds (default ${RunCommandTool.DEFAULT_TIMEOUT_SECONDS}).",
      type = "integer", required = false
    ),
    ToolParam("run_in_background", "Start it and return immediately with a runner id.", type = "boolean", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    if (!File(ctx.project.path, "package.json").isFile) {
      return ToolResult(
        success = false,
        error = "No package.json in ${ctx.project.name}, so there is no npm script \"$script\". Use run_command with the project's own build command."
      )
    }
    val extra = args.str("args").trim()
    val forward = JSONObject().put("command", "npm run $script${if (extra.isBlank()) "" else " -- $extra"}")
    if (args.has("timeout_seconds")) forward.put("timeout_seconds", args.get("timeout_seconds"))
    if (args.has("run_in_background")) forward.put("run_in_background", args.get("run_in_background"))
    return runner.execute(forward, ctx)
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
      output = if (changed.isEmpty()) "Working tree clean"
      else ToolOutput.limit(
        "${changed.size} changed file(s):\n" + changed.joinToString("\n") { "M $it" },
        ToolOutput.LIST_CHARS,
        "the change list was too long; inspect a folder with git_diff path=…"
      ),
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
    return ToolResult(
      success = true,
      output = ToolOutput.limit(out, ToolOutput.COMMAND_CHARS, "ask for one file with path, or read the diff in chunks"),
      metadata = mapOf("files" to selected.size.toString())
    )
  }
}

/** Commit history, so the agent can see what already happened before changing it. */
class GitLogTool(private val git: GitRepositoryManager) : AgentTool {
  override val name = "git_log"
  override val description =
    "List recent commits (hash, author, date, subject) on the current branch. Read-only — use it before reverting, resetting or amending anything."
  override val params = listOf(
    ToolParam("limit", "How many commits to return (default 20, max 100).", type = "integer", required = false),
    ToolParam("offset", "Skip this many commits, for paging further back.", type = "integer", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val limit = args.optInt("limit", 20).coerceIn(1, 100)
    val offset = args.optInt("offset", 0).coerceAtLeast(0)
    val commits = git.getCommitHistory(ctx.project, limit, offset)
      ?: return ToolResult(false, error = "No commit history: ${ctx.project.name} is not a git repository (or git is unavailable).")
    if (commits.isEmpty()) return ToolResult(true, output = "No commits yet.")
    val lines = commits.joinToString("\n") { commit ->
      buildString {
        append(commit.hash.padEnd(9))
        append(commit.date.take(10).padEnd(12))
        append(commit.author.take(18).padEnd(19))
        append(commit.message.take(90))
        if (commit.refs.isNotEmpty()) append("  (").append(commit.refs.joinToString(", ")).append(")")
      }
    }
    val more = if (commits.size >= limit) "\n[…$limit commits shown from offset $offset — call again with offset ${offset + limit} for older ones]" else ""
    return ToolResult(
      success = true,
      output = ToolOutput.limit(lines + more, ToolOutput.LIST_CHARS, "lower limit or page with offset"),
      metadata = mapOf("commits" to commits.size.toString())
    )
  }
}

/** One commit in detail: message, files, and optionally the patch. */
class GitShowTool(private val git: GitRepositoryManager) : AgentTool {
  override val name = "git_show"
  override val description =
    "Inspect one commit: message, author, date, the files it changed with +/- counts, and its diff when include_diff is true. Read-only."
  override val params = listOf(
    ToolParam("hash", "Commit hash (short or full), e.g. from git_log."),
    ToolParam("include_diff", "Also return the patch text (default false — the file list is always returned).", type = "boolean", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val hash = args.str("hash").trim()
    if (hash.isEmpty()) return ToolResult(false, error = "hash must name a commit, e.g. \"f2a1c9d\".")
    if (hash.contains(";") || hash.contains("|") || hash.contains("&")) {
      return ToolResult(false, error = "hash must be a commit hash, not a shell expression.")
    }
    val detail = git.getCommitDetail(ctx.project, hash)
      ?: return ToolResult(false, error = "No such commit: $hash")
    val header = buildString {
      appendLine("commit ${detail.fullHash.ifBlank { detail.hash }}")
      appendLine("author: ${detail.author} <${detail.authorEmail}>")
      appendLine("date: ${detail.date.ifBlank { detail.relativeDate }}")
      appendLine("subject: ${detail.subject}")
      if (detail.body.isNotBlank()) appendLine("body: ${detail.body.take(1000)}")
      append("files: ${detail.filesChanged.size} (+${detail.totalAdditions}/-${detail.totalDeletions})")
      for (change in detail.filesChanged.take(200)) {
        appendLine()
        append("  ${change.status.code} ${change.path} +${change.additions}/-${change.deletions}")
      }
    }
    val withDiff = if (args.optBoolean("include_diff", false) && detail.diff.isNotBlank()) {
      "\n\ndiff:\n${detail.diff}"
    } else ""
    return ToolResult(
      success = true,
      output = ToolOutput.limit(header + withDiff, ToolOutput.COMMAND_CHARS, "the patch is longer than the budget; read the files instead"),
      metadata = mapOf("hash" to detail.hash)
    )
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
    val result = git.commit(ctx.project, staged, message)
    val commit = result.commit
    return if (commit != null) ToolResult(true, output = "Committed ${staged.size} file(s): ${commit.hash} ${commit.message}", metadata = mapOf("hash" to commit.hash))
    else ToolResult(false, error = "Commit failed: ${result.errorOutput ?: "unknown error"}")
  }
}
