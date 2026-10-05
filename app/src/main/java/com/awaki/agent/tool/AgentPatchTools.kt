package com.awaki.agent.tool

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Snippet editing shared by `edit_file` and `edit_files`.
 *
 * One implementation, one set of failure messages: a model that gets an error
 * from a batched edit must be able to fix it with the same knowledge it uses for
 * a single edit. Every edit is matched against the file's current bytes, its
 * line endings are reconciled rather than rewritten, and nothing is written
 * before the whole batch has been checked.
 */

internal class SnippetEdit(
  val old: String,
  val new: String,
  val replaceAll: Boolean
)

internal sealed class EditOutcome {
  class Applied(val content: String, val occurrences: Int, val atLine: Int, val lineDelta: Int) : EditOutcome()
  class Rejected(val reason: String) : EditOutcome()
}

/**
 * Applies [edit] to [content] (read with [lineEnding] endings). Returns the new
 * content, or the reason the edit cannot be applied — phrased so the model can
 * correct the call instead of guessing.
 */
internal fun applySnippetEdit(content: String, lineEnding: String, edit: SnippetEdit): EditOutcome {
  if (edit.old.isBlank()) return EditOutcome.Rejected("old_string must contain the exact snippet to replace.")
  val old = WorkspaceText.withLineEndings(edit.old, lineEnding)
  val new = WorkspaceText.withLineEndings(edit.new, lineEnding)
  if (old == new) return EditOutcome.Rejected("new_string is identical to old_string — nothing to change.")
  val occurrences = countOccurrences(content, old)
  if (occurrences == 0) return EditOutcome.Rejected("old_string not found. ${describeMiss(content, old)}")
  if (occurrences > 1 && !edit.replaceAll) {
    return EditOutcome.Rejected(
      "old_string appears $occurrences times (first at line ${lineIndexOf(content, content.indexOf(old))}). " +
        "Add surrounding context to make it unique, or set replace_all true."
    )
  }
  val atLine = lineIndexOf(content, content.indexOf(old))
  val updated = if (edit.replaceAll) content.replace(old, new) else singleReplace(content, old, new)
  val delta = WorkspaceText.lineCount(updated) - WorkspaceText.lineCount(content)
  return EditOutcome.Applied(updated, occurrences, atLine, delta)
}

/**
 * Apply many exact-snippet edits — possibly across several files — in a single
 * call. This is the safe way to make a related set of changes: everything is
 * checked against the current files first, edits to the same file compose in the
 * given order, and a single bad edit aborts the whole batch with no writes, so
 * the workspace is never left half-changed.
 */
class EditFilesTool(private val gate: WriteGate) : AgentTool {
  override val name = "edit_files"
  override val description =
    "Apply SEVERAL exact-snippet edits in one call, across one or more files — the preferred way to make a related set of changes. Each edit is {\"path\": …, \"old_string\": …, \"new_string\": …, \"replace_all\": false}. All edits are validated against the current files before anything is written; if one fails, none are applied and the error names the failing edit."
  override val params = listOf(
    ToolParam("edits", "JSON array of {path, old_string, new_string, replace_all?} objects, applied in order.", type = "array"),
    ToolParam("note", "Optional one-line reason shown with the result.", required = false, allowBlank = true)
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val arr: JSONArray = args.optJSONArray("edits")
      ?: return ToolResult(false, error = "edits must be a JSON array of {path, old_string, new_string} objects.")
    if (arr.length() == 0) return ToolResult(false, error = "edits is empty; there is nothing to apply.")
    val note = args.str("note")

    // 1. Parse the batch.
    val requests = mutableListOf<Request>()
    for (i in 0 until arr.length()) {
      val obj = arr.opt(i) as? JSONObject
        ?: return ToolResult(false, error = "edit #${i + 1} is not an object: ${arr.opt(i)}")
      val rawPath = obj.optString("path")
      val path = WorkspacePath.resolve(ctx.project, rawPath)
        ?: return ToolResult(false, error = "edit #${i + 1} has an invalid path: \"$rawPath\" (must be inside the workspace).")
      if (!obj.has("old_string")) {
        return ToolResult(false, error = "edit #${i + 1} for $path is missing old_string.")
      }
      if (!obj.has("new_string")) {
        return ToolResult(false, error = "edit #${i + 1} for $path is missing new_string. Pass \"\" to delete the snippet.")
      }
      requests += Request(
        index = i + 1,
        path = path,
        edit = SnippetEdit(
          old = obj.optString("old_string"),
          new = obj.optString("new_string"),
          replaceAll = obj.optBoolean("replace_all", false)
        )
      )
    }

    // 2. Validate every edit against current contents, composing same-file edits.
    val working = LinkedHashMap<String, Working>()
    for (request in requests) {
      val state = working[request.path] ?: run {
        val read = WorkspaceText.read(ctx.project, request.path)
        if (read !is TextRead.Ok) {
          return ToolResult(false, error = "edit #${request.index} for ${request.path}: ${read.errorMessage()}")
        }
        Working(read.file, read.content, read.lineEnding).also { working[request.path] = it }
      }
      when (val outcome = applySnippetEdit(state.content, state.lineEnding, request.edit)) {
        is EditOutcome.Rejected -> return ToolResult(false, error = "edit #${request.index} for ${request.path}: ${outcome.reason}")
        is EditOutcome.Applied -> {
          state.content = outcome.content
          state.applied += AppliedEdit(request.path, request.index, outcome)
        }
      }
    }

    // 3. One approval per touched file; a rejection stops the batch untouched.
    for ((path, _) in working) {
      gate(ctx, path)?.let { return it }
    }

    // 4. Write under per-file locks (sorted so concurrent batches can't
    // deadlock); if one write fails, everything this batch touched goes back.
    return FileChangeLock.withLocks(ctx.project.path, working.keys.toList()) {
      val written = mutableListOf<Working>()
      for ((path, state) in working) {
        val failure = WorkspaceText.write(state.file, state.content)
        if (failure != null) {
          val restored = rollback(written + state)
          val count = written.size
          return@withLocks ToolResult(
            false,
            error = "$path could not be written ($failure)." +
              if (restored) " This batch left the workspace untouched (all ${count + 1} file(s) are as they were)."
              else " WARNING: $count file(s) had already been rewritten and could not all be restored — read them again before continuing."
          )
        }
        written += state
      }
      val report = StringBuilder()
      for (state in written) {
        for (edit in state.applied) {
          report.appendLine(
            "${edit.path}:${edit.outcome.atLine} — edit #${edit.index}" +
              (if (edit.outcome.lineDelta != 0) " (${if (edit.outcome.lineDelta > 0) "+" else ""}${edit.outcome.lineDelta} lines)" else "") +
              (if (edit.outcome.occurrences > 1) " [${edit.outcome.occurrences} occurrences]" else "")
          )
        }
      }
      val summary = "Applied ${requests.size} edit(s) in ${written.size} file(s): ${working.keys.joinToString(", ")}"
      ToolResult(
        success = true,
        output = buildString {
          appendLine(summary)
          append(report.toString().trimEnd())
          if (note.isNotBlank()) {
            appendLine()
            append(note)
          }
        },
        metadata = mapOf("files" to working.size.toString(), "edited" to requests.size.toString())
      )
    }
  }

  /** Restores every file this batch wrote; false when any restore failed. */
  private fun rollback(states: List<Working>): Boolean =
    states.all { WorkspaceText.write(it.file, it.original) == null }

  private class Request(val index: Int, val path: String, val edit: SnippetEdit)

  /** One file in the batch: its bytes as they were, and the content being built. */
  private class Working(val file: File, var content: String, val lineEnding: String) {
    val original: String = content
    val applied = mutableListOf<AppliedEdit>()
  }

  private class AppliedEdit(val path: String, val index: Int, val outcome: EditOutcome.Applied)
}

/**
 * Create a folder inside the workspace — the model needs it before writing files
 * into a new directory layout, and `run_command` may not be permitted.
 */
class CreateDirectoryTool : AgentTool {
  override val name = "create_directory"
  override val description = "Create a folder (and any missing parents) inside the workspace."
  override val params = listOf(ToolParam("path", "Folder path relative to the project root."))

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val path = WorkspacePath.resolve(ctx.project, args.str("path"))
      ?: return ToolResult(false, error = "Invalid path: folders can only be created inside the workspace.")
    val dir = File(ctx.project.path, path)
    if (dir.isDirectory) return ToolResult(true, output = "Already exists: $path")
    if (dir.exists()) return ToolResult(false, error = "$path is a file, not a folder")
    val created = runCatching { dir.mkdirs() && dir.isDirectory }.getOrDefault(false)
    return if (created) ToolResult(true, output = "Created folder $path")
    else ToolResult(false, error = "Failed to create folder $path")
  }
}
