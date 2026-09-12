package com.agentisco.workspace.git

import com.agentisco.data.model.FileDiff
import com.agentisco.data.model.GitCommit
import com.agentisco.data.model.Project
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Result of one `git` invocation inside the project workspace. */
data class GitRunResult(val exitCode: Int, val output: String) {
  val success: Boolean get() = exitCode == 0
}

/**
 * Real git, executed inside the Debian rootfs with the project folder mounted
 * at the workspace path. No simulated baselines or in-memory commits — status,
 * staging, diffs, history, and reverts all come from actual `git` commands, so
 * the Git tab behaves like a real VCS (including the "not a repository" state).
 */
class GitRepositoryManager(
  private val fileSystem: com.agentisco.workspace.filesystem.ProjectFileSystem,
  /** Runs a git command with the project as working directory; provided by the repository. */
  private val runGit: suspend (projectPath: String, args: String) -> GitRunResult
) {

  private var gitEnvConfigured = false

  /** One-time identity/safety config inside the rootfs (git needs it to commit). */
  private suspend fun ensureGitEnv(projectPath: String) {
    if (gitEnvConfigured) return
    runGit(
      projectPath,
      "git config --global --add safe.directory '*' 2>/dev/null; " +
        "git config --global user.name 'Agentisco Developer' 2>/dev/null; " +
        "git config --global user.email 'agentisco@localhost' 2>/dev/null; true"
    )
    gitEnvConfigured = true
  }

  private suspend fun git(project: Project, args: String): GitRunResult {
    ensureGitEnv(project.path)
    return runGit(project.path, args)
  }

  suspend fun isGitRepository(project: Project): Boolean {
    if (project.path.isBlank() || !File(project.path).isDirectory) return false
    return git(project, "git rev-parse --is-inside-work-tree 2>/dev/null").output.trim() == "true"
  }

  /** Creates a real repository, like VS Code's "Initialize Repository". */
  suspend fun initRepository(project: Project): Boolean {
    ensureGitEnv(project.path)
    return git(project, "git init").success
  }

  /** `git status --porcelain` paths, parsed (staged + unstaged + untracked). */
  suspend fun getChangedFiles(project: Project): List<String> =
    parseStatusPaths(git(project, "git status --porcelain").output).map { it.second }

  /** Paths currently in the index (staged), parsed from the porcelain X column. */
  suspend fun getStagedFiles(project: Project): List<String> =
    parseStatusPaths(git(project, "git status --porcelain").output)
      .filter { it.first != ' ' && it.first != '?' }
      .map { it.second }

  /** (statusX, path) pairs from porcelain output; untracked files have X='?'. */
  private fun parseStatusPaths(output: String): List<Pair<Char, String>> {
    val result = mutableListOf<Pair<Char, String>>()
    for (line in output.lines()) {
      if (line.length < 4) continue
      val x = line[0]
      var path = line.substring(3).trim()
      if (path.isEmpty()) continue
      if (path.startsWith("\"") && path.endsWith("\"")) {
        path = path.drop(1).dropLast(1)
          .replace("\\\\", "\\").replace("\\\"", "\"")
      }
      // Rename entries come as "old -> new"; track the new path.
      if (path.contains(" -> ")) path = path.substringAfterLast(" -> ")
      result.add(x to path)
    }
    return result.distinctBy { it.second }
  }

  /** Raw staged diff (`git diff --cached`) — used for commit-message generation. */
  suspend fun stagedDiff(project: Project): String =
    git(project, "git diff --cached").output

  /** Diffs of working-tree changes vs HEAD, computed with the real file contents. */
  suspend fun computeAllDiffs(project: Project): List<FileDiff> {
    if (!isGitRepository(project)) return emptyList()
    val entries = parseStatusPaths(git(project, "git status --porcelain").output)
    val diffs = mutableListOf<FileDiff>()
    for ((status, path) in entries) {
      val currentFile = File(project.path, path)
      if (currentFile.length() > 400_000) continue
      val oldContent = if (status == '?') "" else {
        val shown = git(project, "git show HEAD:\"$path\" 2>/dev/null")
        if (shown.success) shown.output else ""
      }
      val newContent = if (currentFile.isFile) fileSystem.readFile(project, path) else ""
      val diff = DiffEngine.computeDiff(path, oldContent, newContent)
      if (diff != null) diffs.add(diff)
    }
    return diffs
  }

  /** Reverts one file: tracked changes are restored, untracked files are deleted. */
  suspend fun revertFile(project: Project, relativePath: String): Boolean {
    val tracked = git(project, "git ls-files -- \"$relativePath\"").output.isNotBlank()
    return if (tracked) {
      git(project, "git checkout HEAD -- \"$relativePath\" 2>/dev/null").success ||
        git(project, "git restore -- \"$relativePath\" 2>/dev/null").success
    } else {
      fileSystem.deleteFile(project, relativePath)
    }
  }

  suspend fun revertAllFiles(project: Project): Boolean {
    var allOk = true
    for ((_, path) in parseStatusPaths(git(project, "git status --porcelain").output)) {
      if (!revertFile(project, path)) allOk = false
    }
    return allOk
  }

  /** Stages the given files and creates a real commit. Supports multiline messages. */
  suspend fun commit(project: Project, stagedFiles: Set<String>, message: String): GitCommit? {
    if (message.isBlank()) return null
    if (stagedFiles.isNotEmpty()) {
      val paths = stagedFiles.joinToString(" ") { "\"$it\"" }
      git(project, "git add -- $paths")
    }
    val lines = message.trim().lines()
    val subject = lines.firstOrNull { it.isNotBlank() }?.take(72) ?: return null
    val body = lines.dropWhile { it.isBlank() }.drop(1)
      .dropWhile { it.isBlank() }.joinToString("\n").trim()
    val commitResult = if (body.isNotBlank()) {
      git(project, "git commit -m ${shellQuote(subject)} -m ${shellQuote(body)}")
    } else {
      git(project, "git commit -m ${shellQuote(subject)}")
    }
    if (!commitResult.success && !commitResult.output.contains("nothing to commit")) return null

    val hash = git(project, "git rev-parse --short HEAD").output.trim().ifBlank { "unknown" }
    return GitCommit(
      hash = hash,
      message = subject,
      author = "Agentisco Developer",
      date = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date()),
      filesChanged = stagedFiles.toList()
    )
  }

  /** Real commit history via `git log`. */
  suspend fun getCommitHistory(project: Project): List<GitCommit> {
    if (!isGitRepository(project)) return emptyList()
    val out = git(project, "git log --pretty=format:%h|%an|%ci|%s -n 30").output
    return out.lines().filter { it.contains("|") }.map { line ->
      val parts = line.split("|", limit = 4)
      GitCommit(
        hash = parts.getOrNull(0)?.trim() ?: "unknown",
        message = parts.getOrNull(3)?.trim() ?: "",
        author = parts.getOrNull(1)?.trim() ?: "",
        date = parts.getOrNull(2)?.trim()?.take(16)?.replace("T", " ") ?: "",
        filesChanged = emptyList()
      )
    }.filter { it.message.isNotBlank() }
  }

  private fun shellQuote(text: String): String =
    "'" + text.replace("'", "'\\''") + "'"
}
