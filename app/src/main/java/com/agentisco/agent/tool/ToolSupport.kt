package com.agentisco.agent.tool

import com.agentisco.agent.model.PendingApproval
import com.agentisco.agent.model.PermissionMode
import com.agentisco.data.model.Project
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared primitives every workspace tool builds on. They exist because the
 * model's arguments cannot be trusted to be well-formed, byte-exact or
 * in-bounds, and because a tool result that silently drops content makes the
 * model rewrite files from a partial picture.
 *
 * Three invariants hold for every tool in this package:
 *  - a path never leaves the workspace ([WorkspacePath]);
 *  - a text read never decodes binary and never guesses ([WorkspaceText]);
 *  - an output never shrinks without saying so ([ToolOutput]).
 */

/** Caps for tool output, in characters. One place so every tool is consistent. */
object ToolOutput {
  const val READ_CHARS = 40_000
  const val LIST_CHARS = 20_000
  const val COMMAND_CHARS = 12_000
  const val FETCH_CHARS = 40_000
  const val ERROR_CHARS = 600

  /**
   * Truncates [text] to [maxChars] and appends a marker naming what was lost and
   * how to get the rest. Content within budget is returned untouched.
   */
  fun limit(text: String, maxChars: Int, hint: String = ""): String {
    if (text.length <= maxChars) return text
    val marker = buildString {
      append("\n\n[…truncated: showing ")
      append(maxChars)
      append(" of ")
      append(text.length)
      append(" characters")
      if (hint.isNotBlank()) append(" — ").append(hint)
      append("]")
    }
    return text.take(maxChars) + marker
  }
}

/**
 * Relative-path handling with a hard containment rule: the model can name any
 * path, but only a file inside the workspace may be reached.
 */
object WorkspacePath {

  /**
   * Canonicalises a model-supplied relative path: separators to `/`, `.\` and
   * duplicate slashes collapsed, leading `./` and `/` removed. Returns null for
   * absolute paths and for anything that walks out of the workspace.
   */
  fun normalize(rawPath: String): String? {
    val trimmed = rawPath.trim().trim('"', '\'')
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("~") || trimmed.startsWith("//") || trimmed.startsWith("\\\\")) return null
    // A drive letter (C:\…, C:/…) is absolute by definition.
    if (trimmed.length >= 2 && trimmed[1] == ':' && trimmed[0].isLetterOrDigit()) return null
    if (trimmed.contains(":")) return null
    val unified = trimmed.replace('\\', '/').replace(Regex("/+"), "/")
    if (unified.startsWith("/")) return null
    val segments = mutableListOf<String>()
    for (segment in unified.split("/")) {
      when {
        segment.isEmpty() || segment == "." -> {}
        segment == ".." -> {
          if (segments.isEmpty()) return null
          segments.removeAt(segments.lastIndex)
        }
        else -> segments.add(segment)
      }
    }
    if (segments.isEmpty()) return null
    return segments.joinToString("/")
  }

  /** The workspace-relative path, or null when it is malformed or escapes the project. */
  fun resolve(project: Project, rawPath: String): String? {
    val normalized = normalize(rawPath) ?: return null
    val root = File(project.path)
    val target = File(root, normalized)
    val rootCanonical = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
    val targetCanonical = runCatching { target.canonicalPath }.getOrDefault(target.absolutePath)
    if (targetCanonical == rootCanonical) return null
    if (!targetCanonical.startsWith(rootCanonical + File.separator)) return null
    return normalized
  }

  /** `/`-separated path of [file] inside [root], for matching against glob patterns. */
  fun relativeTo(root: File, file: File): String =
    file.toRelativeString(root).replace('\\', '/')
}

/** A text file read by a tool: either usable content, or the reason it is not. */
sealed class TextRead {
  data class Ok(val file: File, val content: String, val lineEnding: String) : TextRead()
  data class Missing(val path: String) : TextRead()
  data class Directory(val path: String) : TextRead()
  data class Binary(val path: String, val sizeBytes: Long) : TextRead()
  data class TooLarge(val path: String, val sizeBytes: Long) : TextRead()
  data class Unreadable(val path: String, val reason: String) : TextRead()

  /** The message a tool returns instead of handing the model garbage or "". */
  fun errorMessage(): String = when (this) {
    is Ok -> error("a successful read has no error message")
    is Missing -> "Not found: $path"
    is Directory -> "$path is a folder, not a file."
    is Binary -> "$path is a binary file ($sizeBytes bytes) — it cannot be read or edited as text."
    is TooLarge -> "$path is too large to read as text ($sizeBytes bytes). Read a slice with start_line/max_lines, or search inside it."
    is Unreadable -> "Cannot read $path: $reason"
  }
}

/**
 * Byte-exact text access for tools. Deliberately separate from the editor's
 * preview reader: a preview may return an empty string for an asset, a tool must
 * never do that, because the model would read the silence as an empty file and
 * overwrite it.
 */
object WorkspaceText {
  const val MAX_TEXT_BYTES = 2_000_000L

  fun read(project: Project, relPath: String): TextRead {
    val file = File(project.path, relPath)
    if (!file.exists()) return TextRead.Missing(relPath)
    if (file.isDirectory) return TextRead.Directory(relPath)
    val size = file.length()
    if (size > MAX_TEXT_BYTES) return TextRead.TooLarge(relPath, size)
    val bytes = runCatching { file.readBytes() }
      .getOrElse { return TextRead.Unreadable(relPath, it.message ?: it.javaClass.simpleName) }
    if (com.agentisco.editor.model.FileViewer.looksBinary(bytes.take(4096).toByteArray())) {
      return TextRead.Binary(relPath, size)
    }
    val content = String(bytes, Charsets.UTF_8)
    return TextRead.Ok(file, content, detectLineEnding(content))
  }

  fun detectLineEnding(content: String): String = if (content.contains("\r\n")) "\r\n" else "\n"

  /** Rewrites [text] so its line endings match the file being edited. */
  fun withLineEndings(text: String, lineEnding: String): String {
    val unified = text.replace("\r\n", "\n").replace("\r", "\n")
    return if (lineEnding == "\n") unified else unified.replace("\n", lineEnding)
  }

  /** Writes and then re-reads, so a failure can never be reported as success. */
  fun write(file: File, content: String): String? {
    return try {
      file.parentFile?.mkdirs()
      file.writeText(content)
      val written = runCatching { file.readText() }.getOrNull()
      when {
        written == null -> "wrote the file but could not read it back"
        written != content -> "content verification failed after writing (${written.length} of ${content.length} characters came back)"
        else -> null
      }
    } catch (e: Exception) {
      e.message ?: e.javaClass.simpleName
    }
  }

  /** Number of lines, counting a trailing newline as ending the last line. */
  fun lineCount(content: String): Int = when {
    content.isEmpty() -> 0
    content.endsWith("\n") -> content.count { it == '\n' }
    else -> content.count { it == '\n' } + 1
  }
}

/**
 * Batched tool calls run concurrently, and models routinely issue two edits for
 * the same file in one response. Without serialization the second write is built
 * from the pre-first-edit contents and silently reverts the first — the classic
 * "the agent undid its own change" mangling. One mutex per normalized path, held
 * across read-modify-write.
 */
object FileChangeLock {
  private val locks = ConcurrentHashMap<String, Mutex>()

  suspend fun <T> withLock(projectPath: String, relPath: String, block: suspend () -> T): T =
    withLocks(projectPath, listOf(relPath), block)

  /**
   * Holds every listed path's lock for [block]. Paths are taken in sorted order
   * so two batches touching overlapping files cannot deadlock each other.
   */
  suspend fun <T> withLocks(projectPath: String, relPaths: List<String>, block: suspend () -> T): T {
    val sorted = relPaths.distinct().sorted()
    suspend fun acquire(index: Int): T =
      if (index == sorted.size) block()
      else locks.getOrPut("$projectPath\u0000${sorted[index]}") { Mutex() }.withLock { acquire(index + 1) }
    return acquire(0)
  }
}

/**
 * Correct glob compiler. `**` crosses directories, `*` and `?` stay inside one
 * segment, `{a,b}` alternates. Hand-rolled `String.replace` chains corrupt the
 * emitted groups, so every glob user shares this one.
 */
object GlobPattern {

  fun toRegex(pattern: String): Regex {
    val normalized = pattern.trim().replace('\\', '/').removePrefix("./").removePrefix("/")
    val out = StringBuilder()
    var i = 0
    while (i < normalized.length) {
      val c = normalized[i]
      when {
        c == '*' && normalized.startsWith("**", i) -> {
          if (normalized.getOrNull(i + 2) == '/') {
            // `a/**/b` also matches `a/b`: whole segments are optional here.
            out.append("(?:[^/]*/)*")
            i += 3
          } else {
            out.append(".*")
            i += 2
          }
        }
        c == '*' -> { out.append("[^/]*"); i++ }
        c == '?' -> { out.append("[^/]"); i++ }
        c == '{' -> {
          val close = normalized.indexOf('}', i + 1)
          if (close < 0) { out.append(Regex.escape("{")); i++ } else {
            val options = normalized.substring(i + 1, close).split(",")
            out.append("(?:").append(options.joinToString("|") { Regex.escape(it.trim()) }).append(")")
            i = close + 1
          }
        }
        else -> { out.append(Regex.escape(c.toString())); i++ }
      }
    }
    return Regex(out.toString())
  }

  fun matches(pattern: String, relativePath: String): Boolean {
    val path = relativePath.replace('\\', '/')
    if (toRegex(pattern).matches(path)) return true
    // `*.kt` with no directory part is what models mean by "any Kotlin file".
    return !pattern.contains('/') && !pattern.contains('\\') &&
      toRegex(pattern).matches(path.substringAfterLast('/'))
  }
}

/**
 * One workspace walk shared by every scan tool, with the exclusions the user
 * configured. Yields `relative path -> file`, files only, in stable walk order.
 */
object WorkspaceScan {
  const val MAX_SCANNED_FILE_BYTES = 2_000_000L

  class Item(val relativePath: String, val file: File)

  fun files(project: Project): Sequence<Item> {
    val root = File(project.path)
    if (!root.isDirectory) return emptySequence()
    return root.walkTopDown()
      .onEnter { !ProjectFileSystemIgnore.isIgnored(it) }
      .filter { it.isFile && !it.name.startsWith(".") }
      .map { Item(WorkspacePath.relativeTo(root, it), it) }
  }

  /** Text content of an item, or null when it is oversized or binary. */
  fun readText(item: Item): String? {
    if (item.file.length() > MAX_SCANNED_FILE_BYTES) return null
    return runCatching {
      val bytes = item.file.readBytes()
      if (com.agentisco.editor.model.FileViewer.looksBinary(bytes.take(4096).toByteArray())) null
      else String(bytes, Charsets.UTF_8)
    }.getOrNull()
  }
}

/** Thin indirection so scan tools honour the filesystem's ignored-folder rules. */
private object ProjectFileSystemIgnore {
  fun isIgnored(dir: File): Boolean = com.agentisco.workspace.filesystem.ProjectFileSystem.isIgnoredDir(dir)
}

/**
 * Id for a user-facing approval/question. Must be unique per request: batched
 * tools can ask in the same millisecond, and a shared id would resolve one
 * request with the other's answer.
 */
internal fun newApprovalId(): String = "tool-appr-${java.util.UUID.randomUUID()}"

/**
 * Marks a terminal id as a command the agent started itself. Every command tool
 * mints its runner id with this marker, and the tools that stop or feed a command
 * accept only such ids — otherwise a model could SIGKILL or type into the user's
 * own interactive terminal session.
 */
internal const val AGENT_RUN_MARKER = "-run-"

internal fun newAgentRunnerId(sessionId: String): String =
  sessionId + AGENT_RUN_MARKER + java.util.UUID.randomUUID().toString().take(8)

internal fun isAgentRunner(id: String): Boolean = id.contains(AGENT_RUN_MARKER)

/**
 * The permission gates every mutating tool runs through. They live here, rather
 * than inside each tool, because a tool must never be able to write by forgetting
 * to ask: the registry wires them into every file-mutating tool as its [WriteGate]
 * equivalent, and tests exercise the same pair the app uses.
 */
object PermissionGates {
  /** Returns a failure result when the write is blocked or rejected, null to proceed. */
  suspend fun fileWrite(ctx: ToolContext, path: String): ToolResult? {
    if (ctx.permissions().fileEditing == PermissionMode.NEVER_ALLOW) {
      return ToolResult(false, error = "Blocked by permission policy: file editing is never allowed.")
    }
    if (ctx.permissions().fileEditing == PermissionMode.ALWAYS_ASK) {
      val decision = ctx.requestApprovalDecision(
        PendingApproval(
          id = newApprovalId(),
          command = "write $path",
          title = "Agent wants to modify a file",
          impactDescription = "This will change $path inside ${ctx.project.name}.",
          isDestructive = false
        )
      )
      if (!decision.approved) return ToolResult(false, error = decision.describeNotExecuted("File not modified"))
    }
    return null
  }

  suspend fun fileDelete(ctx: ToolContext, path: String): ToolResult? {
    if (!ctx.permissions().deleteFiles) {
      return ToolResult(false, error = "Blocked by permission policy: file deletion is disabled.")
    }
    val decision = ctx.requestApprovalDecision(
      PendingApproval(
        id = newApprovalId(),
        command = "delete $path",
        title = "Agent wants to delete a file",
        impactDescription = "This permanently removes $path from ${ctx.project.name}.",
        isDestructive = true
      )
    )
    if (!decision.approved) return ToolResult(false, error = decision.describeNotExecuted("File not deleted"))
    return null
  }
}

/**
 * Plan mode: the user asked what should be done, not for it to be done.
 *
 * The gate is an allowlist and it runs in the runtime, before any tool body
 * executes. Both choices are deliberate: a tool that is not listed here can only
 * ever be over-refused while planning, never silently allowed, and a check inside
 * each tool would be a check each new tool can forget.
 */
object PlanMode {

  /** The tools that only look, and therefore stay usable while planning. */
  private val READ_ONLY_TOOLS = setOf(
    "read_file", "read_files", "list_files", "directory_tree", "file_info",
    "glob_files", "search_files", "regex_search",
    "git_status", "git_diff", "git_log", "git_show",
    "web_fetch", "web_search", "ask_user", "task_plan",
    "terminal_output", "interrupt_terminal"
  )

  /**
   * Shell binaries that read. Anything that can install, write, or run other code
   * (`npm`, `node`, `sed`, `awk`, `tee`, `xargs`, `sh`) is absent, whatever its
   * arguments look like.
   */
  private val READ_ONLY_BINARIES = setOf(
    "ls", "ll", "cat", "tac", "head", "tail", "nl", "od", "wc", "pwd", "echo", "file",
    "stat", "du", "df", "tree", "which", "whereis", "sort", "uniq", "cut", "tr",
    "diff", "cmp", "basename", "dirname", "realpath", "readlink", "md5sum", "sha1sum",
    "sha256sum", "printenv", "date", "whoami", "id", "uname", "jq", "grep", "egrep",
    "fgrep", "rg", "find"
  )

  /**
   * Git is read-only only for these subcommands: the rest move the index, the
   * working tree or a ref, which is exactly what planning must not do.
   */
  private val READ_ONLY_GIT_SUBCOMMANDS = setOf(
    "status", "diff", "log", "show", "rev-parse", "ls-files", "ls-tree", "blame",
    "describe", "cat-file", "shortlog", "merge-base"
  )

  /**
   * Flags that turn an otherwise read-only binary into a writer or an executor:
   * `-o` / `--output` write a file, `-delete` / `-exec` remove or run (find),
   * `--pre` pipes every file through another program (rg).
   */
  private val UNSAFE_FLAGS =
    Regex("""(^|\s)-{1,2}(delete|exec|execdir|ok|ok-cmd|o|output|pre|post-filter)(\s|=|$)""")

  /** Redirection and substitution can write or execute anything, whatever the binary is. */
  private val WRITE_SYNTAX = Regex(">>?|\\$\\(|`|<\\(")

  private val STAGE_SEPARATOR = Regex("""&&|\|\||[;\n]|\|""")

  private val WHITESPACE = Regex("\\s+")

  /**
   * Whether a command can only look at the workspace. Deliberately narrow: an
   * unrecognised shape is refused, and the refusal names the read tools instead.
   */
  fun isReadOnlyCommand(command: String): Boolean {
    val text = command.trim()
    if (text.isEmpty()) return false
    if (WRITE_SYNTAX.containsMatchIn(text)) return false
    if (UNSAFE_FLAGS.containsMatchIn(text)) return false
    return text.split(STAGE_SEPARATOR).all { stage ->
      val tokens = stage.trim().split(WHITESPACE)
      val binary = tokens.firstOrNull()?.substringAfterLast('/').orEmpty()
      when (binary) {
        "git" -> tokens.getOrElse(1) { "" } in READ_ONLY_GIT_SUBCOMMANDS
        else -> binary in READ_ONLY_BINARIES
      }
    }
  }

  /**
   * The refusal to hand back to the model when this call would change something
   * while planning, or null when the call may proceed.
   */
  fun evaluate(
    toolName: String,
    args: org.json.JSONObject,
    planMode: Boolean
  ): ToolResult? {
    if (!planMode) return null
    if (toolName == "run_command") {
      val command = args.optString("command")
      return if (isReadOnlyCommand(command)) null else ToolResult(
        success = false,
        error = "Plan mode is on, so only read-only shell commands run (ls, cat, head, grep, find, wc, …). " +
          "\"${command.take(80)}\" could change something. Inspect with read_file, search_files, glob_files " +
          "or file_info, and ask the user to switch plan mode off before building, testing or installing."
      )
    }
    if (toolName in READ_ONLY_TOOLS) return null
    return ToolResult(
      success = false,
      error = "Plan mode is on: $toolName would change the workspace, and planning changes nothing. " +
        "Keep researching with the read-only tools, then present the plan with task_plan. Use ask_user for a " +
        "decision that belongs to the user; otherwise end the turn with the plan and note that switching " +
        "plan mode off is what lets you implement it."
    )
  }
}

/** A `[..]`-style bounded slice of lines: 1-based, inclusive, with overflow reported. */
internal class LineSlice(val startLine: Int, val maxLines: Int) {
  /** Returns the selected lines and how many were left out. */
  fun take(lines: List<String>): Pair<List<String>, Int> {
    val from = (startLine - 1).coerceIn(0, lines.size)
    val to = (from + maxLines).coerceAtMost(lines.size)
    return lines.subList(from, to) to (lines.size - to)
  }
}

/**
 * One content-search engine behind `search_files` and `regex_search`, so both
 * report the same honest counters: how many matches existed, how many are shown,
 * and whether the cap or a glob hid the rest. A silently capped search makes the
 * model believe a symbol is unused.
 */
object ContentSearch {
  const val DEFAULT_MAX_MATCHES = 80
  const val HARD_MAX_MATCHES = 400
  private const val MAX_SCANNED_FILES = 4_000
  private const val MAX_LINE_WIDTH = 240

  fun run(
    project: Project,
    matches: (String) -> Boolean,
    glob: String,
    maxMatches: Int,
    label: String
  ): ToolResult {
    val root = File(project.path)
    if (!root.isDirectory) return ToolResult(false, error = "Workspace folder is not available.")
    val pattern = glob.trim()

    val out = StringBuilder()
    var total = 0
    var shown = 0
    var filesWithMatches = 0
    var scannedFiles = 0
    var globbedFiles = 0
    var stopped = false
    val iterator = WorkspaceScan.files(project).iterator()
    while (iterator.hasNext() && !stopped) {
      val item = iterator.next()
      if (pattern.isNotEmpty() && !GlobPattern.matches(pattern, item.relativePath)) continue
      globbedFiles++
      val text = WorkspaceScan.readText(item)
      if (text == null) continue
      scannedFiles++
      var hitsInFile = 0
      var lineNumber = 0
      text.lineSequence().forEach { line ->
        lineNumber++
        if (matches(line)) {
          total++
          hitsInFile++
          if (shown < maxMatches) {
            out.appendLine("${item.relativePath}:$lineNumber: ${line.trim().take(MAX_LINE_WIDTH)}")
            shown++
          }
        }
      }
      if (hitsInFile > 0) filesWithMatches++
      if (total >= maxMatches && iterator.hasNext()) stopped = true
      if (scannedFiles >= MAX_SCANNED_FILES && iterator.hasNext()) stopped = true
    }

    if (total == 0) {
      val scope = if (pattern.isNotEmpty()) " under $pattern" else ""
      return ToolResult(
        success = true,
        output = when {
          globbedFiles == 0 && pattern.isNotEmpty() -> "No files match the glob \"$pattern\" — check the path with glob_files."
          scannedFiles == 0 -> "No text files to search$scope."
          else -> "No matches for $label$scope (searched $scannedFiles files)."
        },
        metadata = mapOf("matches" to "0")
      )
    }
    val footer = when {
      total > shown -> "\n[…showing $shown of $total matches found so far — narrow with glob or raise max_matches]"
      stopped -> "\n[…search stopped after $scannedFiles files — narrow with glob or raise max_matches]"
      else -> ""
    }
    return ToolResult(
      success = true,
      output = ToolOutput.limit(out.toString().trimEnd() + footer, ToolOutput.LIST_CHARS, "narrow the query or glob"),
      metadata = mapOf("matches" to total.toString(), "files" to filesWithMatches.toString())
    )
  }
}
