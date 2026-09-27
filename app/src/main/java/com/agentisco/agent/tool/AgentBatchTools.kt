package com.agentisco.agent.tool

import com.agentisco.data.model.Project
import com.agentisco.workspace.filesystem.ProjectFileSystem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Batched & workspace-inspection tools. They exist so the model can gather a
 * lot of context in one round trip instead of many sequential requests.
 */

/** Batch read: several files, one call, one combined result. */
class ReadFilesTool : AgentTool {
  override val name = "read_files"
  override val description =
    "Read MULTIPLE files in one batched call. Strongly preferred over repeated read_file calls — request every file you need here at once. Each file is reported with its line count, and any part that is not shown is named."
  override val params = listOf(
    ToolParam("paths", "JSON array of relative file paths to read, e.g. [\"src/app.ts\", \"src/lib/util.ts\"].", type = "array"),
    ToolParam(
      "max_lines",
      "Lines returned per file (default ${ReadFileTool.DEFAULT_MAX_LINES}). Omit to read whole files.",
      type = "integer", required = false
    )
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val arr: JSONArray = args.optJSONArray("paths")
      ?: return ToolResult(false, error = "paths must be a JSON array of file path strings.")
    if (arr.length() == 0) return ToolResult(false, error = "paths is empty; list at least one file to read.")
    val maxLines = args.optInt("max_lines", ReadFileTool.DEFAULT_MAX_LINES).coerceIn(1, ReadFileTool.MAX_LINES)
    val sb = StringBuilder()
    var found = 0
    var failed = 0
    for (i in 0 until arr.length()) {
      val raw = arr.optString(i).orEmpty()
      if (raw.isBlank()) continue
      val path = WorkspacePath.resolve(ctx.project, raw)
      if (path == null) {
        sb.appendLine("===== $raw =====")
        sb.appendLine("(outside the workspace or malformed — refused)")
        sb.appendLine()
        failed++
        continue
      }
      sb.appendLine("===== $path =====")
      val slice = readTextLines(ctx.project, path, LineSlice(1, maxLines))
      if (slice.success) found++ else failed++
      sb.appendLine(slice.output.ifBlank { slice.error.orEmpty() })
      sb.appendLine()
    }
    if (found == 0) {
      val requested = (0 until arr.length()).joinToString(", ") { arr.optString(it) }
      return ToolResult(false, error = "None of the requested files could be read: $requested")
    }
    return ToolResult(
      success = true,
      output = ToolOutput.limit(sb.toString().trim(), ToolOutput.READ_CHARS, "request fewer files per call or lower max_lines"),
      metadata = mapOf("files" to found.toString(), "unreadable" to failed.toString())
    )
  }
}

/** Find files by glob pattern (e.g. a Kotlin glob, or test-file globs). */
class GlobFilesTool : AgentTool {
  override val name = "glob_files"
  override val description =
    "Find files by glob pattern (e.g. **/*.kt, src/**/*.test.ts, *.json). Returns matching relative paths without reading contents."
  override val params = listOf(
    ToolParam("pattern", "Glob pattern relative to the workspace root. ** matches any path segments, * matches within one segment, {a,b} alternates."),
    ToolParam("limit", "Maximum number of matches to return (default 200).", type = "integer", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val pattern = args.str("pattern").trim()
    if (pattern.isBlank()) return ToolResult(false, error = "pattern must be a non-empty glob string.")
    val limit = args.optInt("limit", 200).coerceIn(1, 1000)
    val regex = GlobPattern.toRegex(pattern)
    val root = File(ctx.project.path)
    if (!root.isDirectory) return ToolResult(false, error = "Workspace folder is not available.")
    var totalMatches = 0
    val matches = root.walkTopDown()
      .onEnter { !ProjectFileSystem.isIgnoredDir(it) }
      .filter { it.isFile }
      .mapNotNull { file ->
        val rel = WorkspacePath.relativeTo(root, file)
        if (regex.matches(rel) || (!pattern.contains('/') && regex.matches(rel.substringAfterLast('/')))) {
          totalMatches++
          if (totalMatches <= limit) rel else null
        } else null
      }
      .toList()
    val footer = if (totalMatches > matches.size) {
      "\n[…$totalMatches files match, showing the first ${matches.size} — narrow the pattern or raise limit]"
    } else ""
    return ToolResult(
      success = true,
      output = if (matches.isEmpty()) "No files match \"$pattern\""
      else ToolOutput.limit(matches.joinToString("\n") + footer, ToolOutput.LIST_CHARS, "narrow the pattern"),
      metadata = mapOf("matches" to totalMatches.toString())
    )
  }
}

/** Metadata about one file or folder. */
class FileInfoTool : AgentTool {
  override val name = "file_info"
  override val description =
    "Inspect a file or folder: whether it exists, its size, last-modified time, line ending, and (for text files) line count."
  override val params = listOf(ToolParam("path", "Relative path of the file or folder to inspect."))

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val path = WorkspacePath.resolve(ctx.project, args.str("path"))
      ?: return ToolResult(false, error = "Invalid path: nothing outside the workspace can be inspected.")
    val file = File(ctx.project.path, path)
    if (!file.exists()) return ToolResult(false, error = "Not found: $path")
    val modified = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
      .format(java.util.Date(file.lastModified()))
    val info = if (file.isDirectory) {
      val children = file.listFiles()?.size ?: 0
      "path: $path\ntype: directory\nentries: $children\nlastModified: $modified"
    } else {
      val read = WorkspaceText.read(ctx.project, path)
      buildString {
        append("path: $path")
        append("\ntype: file")
        append("\nsizeBytes: ${file.length()}")
        append("\nlastModified: $modified")
        when (read) {
          is TextRead.Ok -> {
            append("\ntext: yes")
            append("\nlineEndings: ${if (read.lineEnding == "\r\n") "CRLF" else "LF"}")
            append("\nlines: ${WorkspaceText.lineCount(read.content)}")
          }
          is TextRead.Binary -> append("\ntext: no (binary)")
          is TextRead.TooLarge -> append("\ntext: no (too large to read as text)")
          else -> {}
        }
      }
    }
    return ToolResult(success = true, output = info)
  }
}

/** Structure view of a folder, up to a depth. */
class DirectoryTreeTool : AgentTool {
  override val name = "directory_tree"
  override val description =
    "Show the folder structure of the workspace (or a subfolder) as an indented tree, up to the given depth. Use it to understand project layout before reading files."
  override val params = listOf(
    ToolParam("path", "Relative folder path; omit or use \".\" for the workspace root.", required = false, allowBlank = true),
    ToolParam("depth", "Maximum depth to show (default 2, max 4).", type = "integer", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val rawPath = args.str("path").trim()
    val relPath = if (rawPath.isEmpty() || rawPath == ".") ""
    else WorkspacePath.resolve(ctx.project, rawPath) ?: return ToolResult(false, error = "Invalid path: only folders inside the workspace can be listed.")
    val depth = args.optInt("depth", 2).coerceIn(1, 4)
    val root = if (relPath.isEmpty()) File(ctx.project.path) else File(ctx.project.path, relPath)
    if (!root.isDirectory) return ToolResult(false, error = "Not a folder: ${if (relPath.isEmpty()) ctx.project.name else relPath}")
    val sb = StringBuilder()
    var entries = 0
    var hidden = 0
    fun walk(dir: File, level: Int) {
      if (level > depth) return
      val children = dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name }) ?: return
      for (child in children) {
        if (child.name.startsWith(".") || ProjectFileSystem.isIgnoredDir(child)) {
          hidden++
          continue
        }
        if (entries >= MAX_ENTRIES) {
          hidden++
          continue
        }
        entries++
        sb.appendLine("${"  ".repeat(level)}${child.name}${if (child.isDirectory) "/" else ""}")
        if (child.isDirectory) walk(child, level + 1)
      }
    }
    walk(root, 0)
    if (hidden > 0) sb.appendLine("[…$hidden further entries hidden by the depth/entry limits or ignored folders]")
    return ToolResult(
      success = true,
      output = ToolOutput.limit(
        sb.toString().trim().ifBlank { "(empty folder)" },
        ToolOutput.LIST_CHARS,
        "lower depth or point path at a subfolder"
      )
    )
  }

  private companion object {
    const val MAX_ENTRIES = 400
  }
}

/** Lets the model record a visible plan/checklist for the current task. */
class TaskPlanTool : AgentTool {
  override val name = "task_plan"
  override val description =
    "Publish or update the step-by-step plan for the current task. Show it once before starting multi-step work and update it as steps finish, so the user can follow progress. Each step is a short string, or an object {\"content\": \"…\", \"status\": \"pending|in_progress|done\"}."
  override val params = listOf(
    ToolParam("steps", "JSON array of step strings or {content, status} objects, e.g. [\"inspect config\", {\"content\": \"apply fix\", \"status\": \"done\"}].", type = "array"),
    ToolParam("note", "Optional status note, e.g. \"step 2 of 4\".", required = false, allowBlank = true)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val arr: JSONArray = args.optJSONArray("steps")
      ?: return ToolResult(false, error = "steps must be a JSON array of strings or {content, status} objects.")
    if (arr.length() == 0) return ToolResult(false, error = "steps is empty; a plan needs at least one step.")
    val note = args.str("note")
    var done = 0
    val sb = StringBuilder()
    for (i in 0 until arr.length()) {
      val raw = arr.opt(i)
      val content: String
      val status: String
      if (raw is JSONObject) {
        content = raw.optString("content").ifBlank { raw.optString("description") }
        status = raw.optString("status", "pending").trim().lowercase()
      } else {
        content = raw?.toString().orEmpty()
        status = "pending"
      }
      if (content.isBlank()) continue
      if (status == "done" || status == "completed") done++
      val marker = when (status) {
        "done", "completed" -> "[x]"
        "in_progress", "active" -> "[>]"
        "cancelled", "skipped" -> "[-]"
        else -> "[ ]"
      }
      sb.appendLine("${i + 1}. $marker $content")
    }
    if (sb.isEmpty()) return ToolResult(false, error = "steps contains no usable step text.")
    if (note.isNotBlank()) sb.appendLine(note)
    return ToolResult(
      success = true,
      output = sb.toString().trim(),
      metadata = mapOf("steps" to arr.length().toString(), "done" to done.toString())
    )
  }
}

/** Regex-aware content search across the workspace. */
class RegexSearchTool : AgentTool {
  override val name = "regex_search"
  override val description =
    "Search file contents with a regular expression (more powerful than search_files). Returns file:line matches and says when the search was capped. Skips ignored folders, hidden files and binary files."
  override val params = listOf(
    ToolParam("pattern", "Regular expression, e.g. \"fun \\\\w+\\\\(\" or \"TODO:.*\"."),
    ToolParam("glob", "Optional glob filter for file paths, e.g. \"**/*.kt\".", required = false, allowBlank = true),
    ToolParam("ignore_case", "Match case-insensitively (default false).", type = "boolean", required = false),
    ToolParam("max_matches", "Maximum matches to return (default ${ContentSearch.DEFAULT_MAX_MATCHES}).", type = "integer", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val pattern = args.str("pattern")
    if (pattern.isBlank()) return ToolResult(false, error = "pattern must be a non-empty regular expression.")
    val regex = try {
      if (args.optBoolean("ignore_case", false)) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
    } catch (e: Exception) {
      return ToolResult(false, error = "Invalid regular expression: ${e.message}")
    }
    return ContentSearch.run(
      project = ctx.project,
      matches = { line -> regex.containsMatchIn(line) },
      glob = args.str("glob"),
      maxMatches = args.optInt("max_matches", ContentSearch.DEFAULT_MAX_MATCHES).coerceIn(1, ContentSearch.HARD_MAX_MATCHES),
      label = "/$pattern/"
    )
  }
}
