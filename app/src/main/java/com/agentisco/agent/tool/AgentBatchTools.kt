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
class ReadFilesTool(private val fs: ProjectFileSystem) : AgentTool {
  override val name = "read_files"
  override val description =
    "Read MULTIPLE files in one batched call. Strongly preferred over repeated read_file calls — request every file you need here at once."
  override val params = listOf(
    ToolParam("paths", "JSON array of relative file paths to read, e.g. [\"src/app.ts\", \"src/lib/util.ts\"].", type = "array")
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val arr = args.optJSONArray("paths")
      ?: return ToolResult(false, error = "paths must be a JSON array of file path strings.")
    val sb = StringBuilder()
    var found = 0
    for (i in 0 until arr.length()) {
      val path = arr.optString(i).orEmpty()
      if (path.isBlank()) continue
      sb.appendLine("===== $path =====")
      if (fs.exists(ctx.project, path)) {
        sb.appendLine(fs.readFile(ctx.project, path).take(40_000))
        found++
      } else {
        sb.appendLine("(not found)")
      }
      sb.appendLine()
    }
    if (found == 0 && arr.length() > 0) {
      val requested = (0 until arr.length()).joinToString(", ") { i -> arr.optString(i) }
      return ToolResult(false, error = "None of the requested files exist: $requested")
    }
    return ToolResult(
      success = true,
      output = sb.toString().trim().take(60_000),
      metadata = mapOf("files" to found.toString())
    )
  }
}

/** Find files by glob pattern (e.g. a Kotlin glob, or test-file globs). */
class GlobFilesTool(private val fs: ProjectFileSystem) : AgentTool {
  override val name = "glob_files"
  override val description =
    "Find files by glob pattern (e.g. **/*.kt, src/**/*.test.ts, *.json). Returns matching relative paths without reading contents."
  override val params = listOf(
    ToolParam("pattern", "Glob pattern relative to the workspace root. ** matches any path segments, * matches within one segment."),
    ToolParam("limit", "Maximum number of matches to return (default 200).", type = "integer", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val pattern = args.str("pattern").trim()
    if (pattern.isBlank()) return ToolResult(false, error = "pattern must be a non-empty glob string.")
    val limit = args.optInt("limit", 200).coerceIn(1, 1000)
    val regex = globToRegex(pattern)
    val root = File(ctx.project.path)
    if (!root.isDirectory) return ToolResult(false, error = "Workspace folder is not available.")
    val matches = root.walkTopDown()
      .filter { it.isFile }
      .mapNotNull { file ->
        val rel = file.toRelativeString(root).replace('\\', '/')
        if (regex.matches(rel)) rel else null
      }
      .take(limit)
      .toList()
    return ToolResult(
      success = true,
      output = if (matches.isEmpty()) "No files match \"$pattern\"" else matches.joinToString("\n"),
      metadata = mapOf("matches" to matches.size.toString())
    )
  }

  private fun globToRegex(glob: String): Regex {
    val sb = StringBuilder()
    var i = 0
    while (i < glob.length) {
      val c = glob[i]
      when {
        c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
          // '**/' matches zero or more path segments; '**' matches anything.
          if (i + 2 < glob.length && glob[i + 2] == '/') {
            sb.append("(?:.*/)?")
            i += 3
            continue
          }
          sb.append(".*")
          i += 2
          continue
        }
        c == '*' -> sb.append("[^/]*")
        c == '?' -> sb.append("[^/]")
        else -> sb.append(Regex.escape(c.toString()))
      }
      i++
    }
    return Regex(sb.toString())
  }
}

/** Metadata about one file or folder. */
class FileInfoTool(private val fs: ProjectFileSystem) : AgentTool {
  override val name = "file_info"
  override val description =
    "Inspect a file or folder: whether it exists, its size, last-modified time, and (for small text files) line count."
  override val params = listOf(ToolParam("path", "Relative path of the file or folder to inspect."))

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val path = args.str("path")
    val file = File(ctx.project.path, path)
    if (!file.exists()) return ToolResult(false, error = "Not found: $path")
    val info = if (file.isDirectory) {
      val children = file.listFiles()?.size ?: 0
      "path: $path\ntype: directory\nentries: $children"
    } else {
      val lines = if (file.length() < 200_000) {
        runCatching { file.readText().lineSequence().count() }.getOrDefault(0)
      } else 0
      buildString {
        append("path: $path")
        append("\ntype: file")
        append("\nsizeBytes: ${file.length()}")
        append("\nlastModified: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(file.lastModified()))}")
        if (lines > 0) append("\nlines: $lines")
      }
    }
    return ToolResult(success = true, output = info)
  }
}

/** Structure view of a folder, up to a depth. */
class DirectoryTreeTool(private val fs: ProjectFileSystem) : AgentTool {
  override val name = "directory_tree"
  override val description =
    "Show the folder structure of the workspace (or a subfolder) as an indented tree, up to the given depth. Use it to understand project layout before reading files."
  override val params = listOf(
    ToolParam("path", "Relative folder path; omit or use \".\" for the workspace root.", required = false),
    ToolParam("depth", "Maximum depth to show (default 2, max 4).", type = "integer", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val relPath = args.str("path").ifBlank { "." }
    val depth = args.optInt("depth", 2).coerceIn(1, 4)
    val root = File(ctx.project.path, relPath)
    if (!root.isDirectory) return ToolResult(false, error = "Not a folder: $relPath")
    val sb = StringBuilder()
    var entries = 0
    fun walk(dir: File, level: Int) {
      if (level > depth || entries > 400) return
      val children = dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name }) ?: return
      for (child in children) {
        if (child.name.startsWith(".")) continue
        if (entries >= 400) {
          sb.appendLine("…")
          return
        }
        entries++
        sb.appendLine("${"  ".repeat(level)}${child.name}${if (child.isDirectory) "/" else ""}")
        if (child.isDirectory) walk(child, level + 1)
      }
    }
    walk(root, 0)
    return ToolResult(success = true, output = sb.toString().trim().ifBlank { "(empty folder)" })
  }
}

/** Lets the model record a visible plan/checklist for the current task. */
class TaskPlanTool : AgentTool {
  override val name = "task_plan"
  override val description =
    "Publish a short step-by-step plan for the current task (or update it). Show the plan once before starting multi-step work; steps are displayed to the user."
  override val params = listOf(
    ToolParam("steps", "JSON array of short step descriptions, e.g. [\"inspect config\", \"apply fix\", \"run tests\"].", type = "array"),
    ToolParam("note", "Optional status note, e.g. \"step 2 of 4\".", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val arr = args.optJSONArray("steps")
      ?: return ToolResult(false, error = "steps must be a JSON array of strings.")
    val note = args.str("note")
    val sb = StringBuilder()
    for (i in 0 until arr.length()) {
      sb.appendLine("${i + 1}. ${arr.optString(i)}")
    }
    if (note.isNotBlank()) sb.appendLine(note)
    return ToolResult(
      success = true,
      output = sb.toString().trim().ifBlank { "(empty plan)" },
      metadata = mapOf("steps" to arr.length().toString())
    )
  }
}

/** Regex-aware content search across the workspace. */
class RegexSearchTool(private val fs: ProjectFileSystem) : AgentTool {
  override val name = "regex_search"
  override val description =
    "Search file contents with a regular expression (more powerful than search_files). Returns file:line matches. Skips dotfolders and very large files."
  override val params = listOf(
    ToolParam("pattern", "Regular expression, e.g. \"fun \\\\w+\\(\" or \"TODO:.*\"."),
    ToolParam("glob", "Optional glob filter for file paths, e.g. \"**/*.kt\".", required = false)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val pattern = args.str("pattern")
    if (pattern.isBlank()) return ToolResult(false, error = "pattern must be a non-empty regular expression.")
    val regex = try {
      Regex(pattern)
    } catch (e: Exception) {
      return ToolResult(false, error = "Invalid regular expression: ${e.message}")
    }
    val glob = args.str("glob").trim()
    val globRegex = if (glob.isBlank()) null else Regex(
      glob.replace("**/", "(?:.*/)?").replace("**", ".*").replace("*", "[^/]*")
    )
    val root = File(ctx.project.path)
    if (!root.isDirectory) return ToolResult(false, error = "Workspace folder is not available.")
    val sb = StringBuilder()
    var matchCount = 0
    root.walkTopDown()
      .filter { it.isFile && !it.name.startsWith(".") && it.length() < 200_000 }
      .forEach { file ->
        if (matchCount >= 100) return@forEach
        val rel = file.toRelativeString(root).replace('\\', '/')
        if (globRegex?.matches(rel) == false) return@forEach
        val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
        for ((index, line) in text.lines().withIndex()) {
          if (regex.containsMatchIn(line)) {
            sb.appendLine("$rel:${index + 1}: ${line.take(200)}")
            matchCount++
            if (matchCount >= 100) break
          }
        }
      }
    return ToolResult(
      success = true,
      output = sb.toString().trim().ifBlank { "No matches for /$pattern/" },
      metadata = mapOf("matches" to matchCount.toString())
    )
  }
}
