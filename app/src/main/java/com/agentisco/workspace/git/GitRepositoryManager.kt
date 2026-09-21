package com.agentisco.workspace.git

import com.agentisco.data.model.DiffLine
import com.agentisco.data.model.DiffLineType
import com.agentisco.data.model.FileDiff
import com.agentisco.data.model.GitCommit
import com.agentisco.data.model.Project
import com.agentisco.ui.components.computeLineDiff
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Result of one `git` invocation inside the project workspace. */
data class GitRunResult(val exitCode: Int, val output: String) {
  val success: Boolean get() = exitCode == 0
}

/**
 * Real git manager, executed in the workspace context.
 * Provides complete VS Code-grade Source Control and Git operations.
 */
class GitRepositoryManager(
  private val fileSystem: com.agentisco.workspace.filesystem.ProjectFileSystem,
  /** Runs a git command with the project as working directory. */
  private val runGit: suspend (projectPath: String, args: String) -> GitRunResult
) {

  private var gitEnvConfigured = false

  /** One-time identity/safety config inside the git environment. */
  private suspend fun ensureGitEnv(projectPath: String) {
    if (gitEnvConfigured) return
    val result = runGit(
      projectPath,
      "git config --global --add safe.directory '*' 2>/dev/null; " +
        "git config --global user.name 'Agentisco Developer' 2>/dev/null; " +
        "git config --global user.email 'agentisco@localhost' 2>/dev/null; true"
    )
    if (result.exitCode == 0) gitEnvConfigured = true
  }

  suspend fun git(project: Project, args: String): GitRunResult {
    ensureGitEnv(project.path)
    return runGit(project.path, args)
  }

  suspend fun isGitRepository(project: Project): Boolean {
    if (project.path.isBlank() || !File(project.path).isDirectory) return false
    val gitDir = File(project.path, ".git")
    if (gitDir.exists() && (gitDir.isDirectory || gitDir.isFile)) {
      return true
    }
    return git(project, "git rev-parse --is-inside-work-tree 2>/dev/null").output.trim() == "true"
  }

  /** Creates a real git repository (VS Code-style Initialize Repository). */
  suspend fun initRepository(project: Project): Boolean {
    ensureGitEnv(project.path)
    val res = git(project, "git init")
    if (res.success) {
      git(project, "git config core.fileMode false")
    }
    return res.success
  }

  /** Comprehensive repository overview: branch, upstream, ahead/behind, operations, changed files. */
  suspend fun getRepoStatus(project: Project): GitRepoStatus {
    if (!isGitRepository(project)) {
      return GitRepoStatus(isRepo = false)
    }

    val projectDir = File(project.path)

    // Current Branch & Detached HEAD detection
    val branchOut = git(project, "git branch --show-current 2>/dev/null").output.trim()
    val isDetached = branchOut.isBlank()
    val currentBranch = if (isDetached) {
      val headSha = git(project, "git rev-parse --short HEAD 2>/dev/null").output.trim()
      if (headSha.isNotBlank()) "detached ($headSha)" else "main"
    } else {
      branchOut
    }

    // Upstream branch & Ahead/Behind
    val upstream = git(project, "git rev-parse --abbrev-ref --symbolic-full-name @{u} 2>/dev/null").output.trim().ifBlank { null }
    var ahead = 0
    var behind = 0
    if (upstream != null) {
      val counts = git(project, "git rev-list --left-right --count HEAD...@{u} 2>/dev/null").output.trim()
      val parts = counts.split(Regex("\\s+"))
      ahead = parts.getOrNull(0)?.toIntOrNull() ?: 0
      behind = parts.getOrNull(1)?.toIntOrNull() ?: 0
    }

    // Active Git Operation
    val dotGit = File(projectDir, ".git")
    val activeOp = when {
      File(dotGit, "MERGE_HEAD").exists() -> GitActiveOperation.MERGE
      File(dotGit, "rebase-apply").exists() || File(dotGit, "rebase-merge").exists() -> GitActiveOperation.REBASE
      File(dotGit, "CHERRY_PICK_HEAD").exists() -> GitActiveOperation.CHERRY_PICK
      File(dotGit, "REVERT_HEAD").exists() -> GitActiveOperation.REVERT
      else -> GitActiveOperation.NONE
    }

    // HEAD commit info
    val headLog = git(project, "git log -1 --pretty=format:%h|%s 2>/dev/null").output.trim()
    val headParts = if (headLog.contains("|")) headLog.split("|", limit = 2) else emptyList()
    val headSha = headParts.getOrNull(0)
    val headMsg = headParts.getOrNull(1)

    // Status porcelain (staged, unstaged, untracked, conflicted)
    val statusOutput = git(project, "git status --porcelain=v1 -uall").output
    val parsedStatuses = parsePorcelain(statusOutput)

    val staged = mutableListOf<GitFileStatus>()
    val unstaged = mutableListOf<GitFileStatus>()
    val untracked = mutableListOf<GitFileStatus>()
    val conflicts = mutableListOf<GitFileStatus>()

    for (item in parsedStatuses) {
      if (item.isConflicted) {
        conflicts.add(item)
      } else if (item.isUntracked) {
        untracked.add(item)
      } else {
        if (item.isStaged) staged.add(item)
        else unstaged.add(item)
      }
    }

    val remotes = getRemotes(project)
    val tags = getTags(project)

    return GitRepoStatus(
      isRepo = true,
      currentBranch = currentBranch,
      upstreamBranch = upstream,
      aheadCount = ahead,
      behindCount = behind,
      isClean = statusOutput.isBlank(),
      isDetachedHead = isDetached,
      headCommitHash = headSha,
      headCommitMessage = headMsg,
      activeOperation = activeOp,
      conflictedFiles = conflicts,
      stagedFiles = staged,
      unstagedFiles = unstaged,
      untrackedFiles = untracked,
      totalChangedFiles = parsedStatuses.map { it.path }.distinct().size,
      remotes = remotes,
      tags = tags
    )
  }

  /** Parses git status --porcelain=v1 into rich GitFileStatus objects. */
  private fun parsePorcelain(output: String): List<GitFileStatus> {
    val result = mutableListOf<GitFileStatus>()
    for (line in output.lines()) {
      if (line.length < 3) continue
      val x = line[0]
      val y = line[1]
      var path = line.substring(3).trim()
      if (path.isEmpty()) continue

      var oldPath: String? = null
      if (path.contains(" -> ")) {
        val parts = path.split(" -> ")
        oldPath = cleanPath(parts[0])
        path = cleanPath(parts[1])
      } else {
        path = cleanPath(path)
      }

      val isConflict = x == 'U' || y == 'U' || (x == 'A' && y == 'A') || (x == 'D' && y == 'D')
      val isUntracked = x == '?' && y == '?'

      if (isConflict) {
        result.add(
          GitFileStatus(
            path = path,
            status = GitStatusCode.CONFLICTED,
            isStaged = false,
            isConflicted = true,
            oldPath = oldPath
          )
        )
        continue
      }

      if (isUntracked) {
        result.add(
          GitFileStatus(
            path = path,
            status = GitStatusCode.UNTRACKED,
            isStaged = false,
            isUntracked = true,
            oldPath = oldPath
          )
        )
        continue
      }

      // Staged entry (X != ' ' and X != '?')
      if (x != ' ') {
        result.add(
          GitFileStatus(
            path = path,
            status = GitStatusCode.fromPorcelainChar(x),
            isStaged = true,
            oldPath = oldPath
          )
        )
      }

      // Unstaged entry (Y != ' ' and Y != '?')
      if (y != ' ') {
        result.add(
          GitFileStatus(
            path = path,
            status = GitStatusCode.fromPorcelainChar(y),
            isStaged = false,
            oldPath = oldPath
          )
        )
      }
    }
    return result
  }

  private fun cleanPath(raw: String): String {
    var p = raw.trim()
    if (p.startsWith("\"") && p.endsWith("\"")) {
      p = p.drop(1).dropLast(1).replace("\\\\", "\\").replace("\\\"", "\"")
    }
    return p
  }

  /** Paths currently in the index (staged). */
  suspend fun getStagedFiles(project: Project): List<String> =
    parsePorcelain(git(project, "git status --porcelain=v1 -uall").output)
      .filter { it.isStaged }
      .map { it.path }
      .distinct()

  /** All changed paths (staged + unstaged + untracked + conflicts). */
  suspend fun getChangedFiles(project: Project): List<String> =
    parsePorcelain(git(project, "git status --porcelain=v1 -uall").output)
      .map { it.path }
      .distinct()

  /** Raw staged diff (`git diff --cached`). */
  suspend fun stagedDiff(project: Project): String =
    git(project, "git diff --cached").output

  /** Raw unstaged diff (`git diff`). */
  suspend fun unstagedDiff(project: Project): String =
    git(project, "git diff").output

  /** Full working-tree diff against HEAD, or combination of diffs. */
  suspend fun fullDiff(project: Project): String {
    val headCheck = git(project, "git rev-parse --verify HEAD 2>/dev/null")
    return if (headCheck.success) {
      git(project, "git diff HEAD").output
    } else {
      val staged = stagedDiff(project)
      val unstaged = unstagedDiff(project)
      when {
        staged.isNotBlank() && unstaged.isNotBlank() -> "$staged\n$unstaged"
        staged.isNotBlank() -> staged
        else -> unstaged
      }
    }
  }

  /** Diff for one specific file. */
  suspend fun fileDiff(project: Project, relativePath: String, stagedOnly: Boolean? = null): String {
    return when (stagedOnly) {
      true -> git(project, "git diff --cached -- ${shellQuote(relativePath)}").output
      false -> git(project, "git diff -- ${shellQuote(relativePath)}").output
      null -> {
        val headCheck = git(project, "git rev-parse --verify HEAD 2>/dev/null")
        if (headCheck.success) {
          git(project, "git diff HEAD -- ${shellQuote(relativePath)}").output
        } else {
          val s = git(project, "git diff --cached -- ${shellQuote(relativePath)}").output
          val u = git(project, "git diff -- ${shellQuote(relativePath)}").output
          if (s.isNotBlank() && u.isNotBlank()) "$s\n$u" else s.ifBlank { u }
        }
      }
    }
  }

  /** Unified structured diffs computed from git and disk contents. */
  suspend fun computeAllDiffs(project: Project, stagedOnly: Boolean? = null): List<FileDiff> {
    if (!isGitRepository(project)) return emptyList()

    val porcelain = parsePorcelain(git(project, "git status --porcelain=v1 -uall").output)
    val filtered = when (stagedOnly) {
      true -> porcelain.filter { it.isStaged }
      false -> porcelain.filter { !it.isStaged }
      null -> porcelain
    }

    val diffs = mutableListOf<FileDiff>()
    val processedPaths = mutableSetOf<String>()

    for (item in filtered) {
      if (processedPaths.contains(item.path)) continue
      processedPaths.add(item.path)

      val currentFile = File(project.path, item.path)
      if (currentFile.length() > 600_000) continue

      val oldContent = if (item.isUntracked) {
        ""
      } else {
        val shown = if (item.isStaged) {
          git(project, "git show HEAD:${shellQuote(item.path)} 2>/dev/null")
        } else {
          git(project, "git show :${shellQuote(item.path)} 2>/dev/null")
            .takeIf { it.success } ?: git(project, "git show HEAD:${shellQuote(item.path)} 2>/dev/null")
        }
        if (shown.success) shown.output else ""
      }

      val newContent = if (item.isStaged) {
        val indexContent = git(project, "git show :${shellQuote(item.path)} 2>/dev/null")
        if (indexContent.success) indexContent.output
        else if (currentFile.isFile) fileSystem.readFile(project, item.path)
        else ""
      } else {
        if (currentFile.isFile) fileSystem.readFile(project, item.path) else ""
      }

      val diffLines = computeLineDiff(oldContent, newContent)
      val adds = diffLines.count { it.kind == com.agentisco.ui.components.DiffKind.ADDED }
      val dels = diffLines.count { it.kind == com.agentisco.ui.components.DiffKind.REMOVED }

      val convertedLines = diffLines.map { dl ->
        DiffLine(
          type = when (dl.kind) {
            com.agentisco.ui.components.DiffKind.ADDED -> DiffLineType.ADDED
            com.agentisco.ui.components.DiffKind.REMOVED -> DiffLineType.REMOVED
            com.agentisco.ui.components.DiffKind.CONTEXT -> DiffLineType.UNCHANGED
            com.agentisco.ui.components.DiffKind.ELIDED -> DiffLineType.UNCHANGED
          },
          oldLineNo = dl.oldNo,
          newLineNo = dl.newNo,
          text = dl.text
        )
      }

      diffs.add(
        FileDiff(
          filePath = item.path,
          additionsCount = adds,
          deletionsCount = dels,
          lines = convertedLines,
          originalContent = oldContent,
          newContent = newContent
        )
      )
    }
    return diffs
  }

  // ---- Staging Operations ----

  suspend fun stageFile(project: Project, relativePath: String): Boolean =
    git(project, "git add -A -- ${shellQuote(relativePath)}").success

  suspend fun unstageFile(project: Project, relativePath: String): Boolean =
    git(project, "git restore --staged -- ${shellQuote(relativePath)} 2>/dev/null").success ||
      git(project, "git reset HEAD -- ${shellQuote(relativePath)} 2>/dev/null").success

  suspend fun stageAll(project: Project): Boolean =
    git(project, "git add -A").success

  suspend fun unstageAll(project: Project): Boolean =
    git(project, "git reset").success

  suspend fun stageFiles(project: Project, paths: Collection<String>): Boolean {
    if (paths.isEmpty()) return true
    val quoted = paths.joinToString(" ") { shellQuote(it) }
    return git(project, "git add -A -- $quoted").success
  }

  // ---- Discard Operations ----

  suspend fun revertFile(project: Project, relativePath: String): Boolean {
    val tracked = git(project, "git ls-files -- ${shellQuote(relativePath)}").output.isNotBlank()
    return if (tracked) {
      git(project, "git restore -- ${shellQuote(relativePath)} 2>/dev/null").success ||
        git(project, "git checkout HEAD -- ${shellQuote(relativePath)} 2>/dev/null").success
    } else {
      fileSystem.deleteFile(project, relativePath)
    }
  }

  suspend fun revertAllFiles(project: Project): Boolean {
    val r1 = git(project, "git restore . 2>/dev/null").success || git(project, "git checkout HEAD -- . 2>/dev/null").success
    val r2 = git(project, "git clean -fd").success
    return r1 && r2
  }

  suspend fun deleteUntrackedFile(project: Project, relativePath: String): Boolean {
    val file = File(project.path, relativePath)
    return if (file.exists()) file.deleteRecursively() else true
  }

  // ---- Committing & Undo ----

  suspend fun commit(
    project: Project,
    stagedFiles: Set<String>,
    message: String,
    amend: Boolean = false
  ): GitCommit? {
    if (message.isBlank()) return null
    if (stagedFiles.isNotEmpty()) {
      stageFiles(project, stagedFiles)
    }

    val lines = message.trim().lines()
    val subject = lines.firstOrNull { it.isNotBlank() }?.take(72) ?: return null
    val body = lines.dropWhile { it.isBlank() }.drop(1)
      .dropWhile { it.isBlank() }.joinToString("\n").trim()

    val amendFlag = if (amend) "--amend" else ""
    val commitResult = if (body.isNotBlank()) {
      git(project, "git commit $amendFlag -m ${shellQuote(subject)} -m ${shellQuote(body)}")
    } else {
      git(project, "git commit $amendFlag -m ${shellQuote(subject)}")
    }

    if (!commitResult.success && !commitResult.output.contains("nothing to commit")) return null

    val hash = git(project, "git rev-parse --short HEAD").output.trim().ifBlank { "unknown" }
    val fullHash = git(project, "git rev-parse HEAD").output.trim()

    return GitCommit(
      hash = hash,
      message = subject,
      author = "Agentisco Developer",
      date = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date()),
      filesChanged = stagedFiles.toList(),
      relativeDate = "just now",
      fullHash = fullHash,
      body = body
    )
  }

  suspend fun undoLastCommit(project: Project, mode: UndoCommitMode): GitRunResult {
    return when (mode) {
      UndoCommitMode.KEEP_STAGED -> git(project, "git reset --soft HEAD~1")
      UndoCommitMode.KEEP_UNSTAGED -> git(project, "git reset HEAD~1")
      UndoCommitMode.REVERT_COMMIT -> git(project, "git revert --no-edit HEAD")
    }
  }

  // ---- Commit History & Details ----

  suspend fun getCommitHistory(project: Project, limit: Int = 30, offset: Int = 0): List<GitCommit> {
    if (!isGitRepository(project)) return emptyList()

    val checkHead = git(project, "git rev-parse --verify HEAD 2>/dev/null")
    if (!checkHead.success) return emptyList()

    // Log format: %h | %H | %an | %ae | %ci | %cr | %d | %s
    val logCmd = "git log --pretty=format:%h|%H|%an|%ae|%ci|%cr|%d|%s -n $limit --skip $offset"
    val out = git(project, logCmd).output
    if (out.isBlank()) return emptyList()

    return out.lines().filter { it.contains("|") }.map { line ->
      val parts = line.split("|", limit = 8)
      val hash = parts.getOrNull(0)?.trim() ?: "unknown"
      val fullHash = parts.getOrNull(1)?.trim() ?: ""
      val author = parts.getOrNull(2)?.trim() ?: ""
      val email = parts.getOrNull(3)?.trim() ?: ""
      val date = parts.getOrNull(4)?.trim()?.take(16)?.replace("T", " ") ?: ""
      val relativeDate = parts.getOrNull(5)?.trim() ?: ""
      val rawRefs = parts.getOrNull(6)?.trim()?.removePrefix("(")?.removeSuffix(")") ?: ""
      val refs = if (rawRefs.isNotBlank()) rawRefs.split(",").map { it.trim() } else emptyList()
      val subject = parts.getOrNull(7)?.trim() ?: ""

      GitCommit(
        hash = hash,
        message = subject,
        author = author,
        date = date,
        filesChanged = emptyList(),
        relativeDate = relativeDate,
        fullHash = fullHash,
        authorEmail = email,
        refs = refs
      )
    }.filter { it.message.isNotBlank() }
  }

  suspend fun getCommitDetail(project: Project, hash: String): GitCommitDetail? {
    if (!isGitRepository(project)) return null

    // Get metadata and body
    val meta = git(
      project,
      "git show -s --pretty=format:\"%h|%H|%an|%ae|%ci|%cr|%P|%s%n%b---END-COMMIT-BODY---\" $hash"
    ).output

    if (meta.isBlank() || !meta.contains("|")) return null

    val headerLine = meta.lines().firstOrNull() ?: return null
    val headerParts = headerLine.split("|", limit = 8)
    val shortHash = headerParts.getOrNull(0)?.trim() ?: hash
    val fullHash = headerParts.getOrNull(1)?.trim() ?: hash
    val author = headerParts.getOrNull(2)?.trim() ?: ""
    val email = headerParts.getOrNull(3)?.trim() ?: ""
    val date = headerParts.getOrNull(4)?.trim() ?: ""
    val relativeDate = headerParts.getOrNull(5)?.trim() ?: ""
    val parents = headerParts.getOrNull(6)?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()
    val subject = headerParts.getOrNull(7)?.trim() ?: ""

    val bodyContent = meta.substringAfter("\n").substringBefore("---END-COMMIT-BODY---").trim()

    // Numstat of changed files
    val statOut = git(project, "git show --numstat --format=\"\" $hash").output
    val changedFiles = mutableListOf<GitCommitFileChange>()
    var totalAdds = 0
    var totalDels = 0

    for (line in statOut.lines()) {
      val p = line.split(Regex("\\t+"))
      if (p.size >= 3) {
        val adds = p[0].toIntOrNull() ?: 0
        val dels = p[1].toIntOrNull() ?: 0
        val path = p[2].trim()
        totalAdds += adds
        totalDels += dels
        changedFiles.add(
          GitCommitFileChange(
            path = path,
            status = if (adds > 0 && dels == 0) GitStatusCode.ADDED else if (adds == 0 && dels > 0) GitStatusCode.DELETED else GitStatusCode.MODIFIED,
            additions = adds,
            deletions = dels
          )
        )
      }
    }

    // Full commit diff
    val diffOut = git(project, "git show --format=\"\" $hash").output

    return GitCommitDetail(
      hash = shortHash,
      fullHash = fullHash,
      author = author,
      authorEmail = email,
      date = date,
      relativeDate = relativeDate,
      subject = subject,
      body = bodyContent,
      parents = parents,
      filesChanged = changedFiles,
      diff = diffOut,
      totalAdditions = totalAdds,
      totalDeletions = totalDels
    )
  }

  suspend fun revertCommit(project: Project, hash: String): GitRunResult =
    git(project, "git revert --no-edit $hash")

  suspend fun cherryPick(project: Project, hash: String): GitRunResult =
    git(project, "git cherry-pick $hash")

  suspend fun abortCherryPick(project: Project): GitRunResult =
    git(project, "git cherry-pick --abort")

  suspend fun continueCherryPick(project: Project): GitRunResult =
    git(project, "git cherry-pick --continue")

  suspend fun resetToCommit(project: Project, hash: String, mode: ResetMode): GitRunResult {
    val flag = when (mode) {
      ResetMode.SOFT -> "--soft"
      ResetMode.MIXED -> "--mixed"
      ResetMode.HARD -> "--hard"
    }
    return git(project, "git reset $flag $hash")
  }

  suspend fun compareCommits(project: Project, baseHash: String, targetHash: String): String =
    git(project, "git diff $baseHash..$targetHash").output

  // ---- Branches ----

  suspend fun getBranches(project: Project): List<GitBranch> {
    if (!isGitRepository(project)) return emptyList()
    val out = git(project, "git branch -a -vv --sort=-committerdate").output
    val branches = mutableListOf<GitBranch>()

    for (line in out.lines()) {
      val trimmed = line.trim()
      if (trimmed.isBlank()) continue
      val isCurrent = line.startsWith("*")
      val withoutPrefix = trimmed.removePrefix("*").trim()

      val name = withoutPrefix.substringBefore(" ")
      if (name.contains("->")) continue // skip origin/HEAD -> origin/main symref

      val isRemote = name.startsWith("remotes/") || name.startsWith("origin/")

      // Extract upstream and ahead/behind: e.g. [origin/main: ahead 1, behind 2]
      var upstream: String? = null
      var ahead = 0
      var behind = 0
      if (withoutPrefix.contains("[") && withoutPrefix.contains("]")) {
        val tracking = withoutPrefix.substringAfter("[").substringBefore("]")
        upstream = tracking.substringBefore(":").trim()
        if (tracking.contains("ahead ")) {
          ahead = tracking.substringAfter("ahead ").substringBefore(",").substringBefore("]").trim().toIntOrNull() ?: 0
        }
        if (tracking.contains("behind ")) {
          behind = tracking.substringAfter("behind ").substringBefore(",").substringBefore("]").trim().toIntOrNull() ?: 0
        }
      }

      val cleanName = name.removePrefix("remotes/")
      branches.add(
        GitBranch(
          name = cleanName,
          isCurrent = isCurrent,
          isRemote = isRemote,
          upstream = upstream,
          ahead = ahead,
          behind = behind
        )
      )
    }
    return branches.distinctBy { it.name }
  }

  suspend fun createBranch(project: Project, branchName: String, checkout: Boolean = true): GitRunResult {
    val clean = branchName.trim()
    return if (checkout) {
      git(project, "git checkout -b ${shellQuote(clean)} 2>/dev/null || git switch -c ${shellQuote(clean)}")
    } else {
      git(project, "git branch ${shellQuote(clean)}")
    }
  }

  suspend fun checkoutBranch(project: Project, branchName: String): GitRunResult {
    val clean = branchName.trim()
    return git(project, "git checkout ${shellQuote(clean)} 2>/dev/null || git switch ${shellQuote(clean)}")
  }

  suspend fun renameBranch(project: Project, oldName: String, newName: String): GitRunResult =
    git(project, "git branch -m ${shellQuote(oldName.trim())} ${shellQuote(newName.trim())}")

  suspend fun deleteBranch(project: Project, branchName: String, force: Boolean = false): GitRunResult {
    val flag = if (force) "-D" else "-d"
    return git(project, "git branch $flag ${shellQuote(branchName.trim())}")
  }

  suspend fun mergeBranch(project: Project, branchName: String): GitRunResult =
    git(project, "git merge ${shellQuote(branchName.trim())}")

  suspend fun abortMerge(project: Project): GitRunResult =
    git(project, "git merge --abort")

  suspend fun continueMerge(project: Project): GitRunResult =
    git(project, "git merge --continue")

  suspend fun rebaseBranch(project: Project, branchName: String): GitRunResult =
    git(project, "git rebase ${shellQuote(branchName.trim())}")

  suspend fun abortRebase(project: Project): GitRunResult =
    git(project, "git rebase --abort")

  suspend fun continueRebase(project: Project): GitRunResult =
    git(project, "git rebase --continue")

  // ---- Remotes & Sync ----

  suspend fun fetch(project: Project, remote: String = "origin", prune: Boolean = false): GitRunResult {
    val pruneFlag = if (prune) "--prune" else ""
    return git(project, "git fetch $pruneFlag $remote")
  }

  suspend fun pull(
    project: Project,
    remote: String = "origin",
    branch: String? = null,
    rebase: Boolean = false
  ): GitRunResult {
    val rebaseFlag = if (rebase) "--rebase" else ""
    val branchArg = if (branch.isNullOrBlank()) "" else shellQuote(branch)
    return git(project, "git pull $rebaseFlag $remote $branchArg")
  }

  suspend fun push(
    project: Project,
    remote: String = "origin",
    branch: String? = null,
    setUpstream: Boolean = false,
    force: Boolean = false
  ): GitRunResult {
    val uFlag = if (setUpstream) "-u" else ""
    val forceFlag = if (force) "--force" else ""
    val branchArg = if (branch.isNullOrBlank()) "" else shellQuote(branch)
    return git(project, "git push $uFlag $forceFlag $remote $branchArg")
  }

  suspend fun getRemotes(project: Project): List<GitRemote> {
    if (!isGitRepository(project)) return emptyList()
    val out = git(project, "git remote -v").output
    val map = mutableMapOf<String, Pair<String, String>>() // name -> (fetch, push)

    for (line in out.lines()) {
      val parts = line.split(Regex("\\s+"))
      if (parts.size >= 3) {
        val name = parts[0]
        val url = parts[1]
        val type = parts[2] // (fetch) or (push)
        val current = map[name] ?: ("" to "")
        if (type.contains("fetch")) {
          map[name] = url to current.second
        } else if (type.contains("push")) {
          map[name] = current.first to url
        }
      }
    }

    return map.map { (name, urls) ->
      GitRemote(name, urls.first.ifBlank { urls.second }, urls.second.ifBlank { urls.first })
    }
  }

  suspend fun addRemote(project: Project, name: String, url: String): GitRunResult =
    git(project, "git remote add ${shellQuote(name.trim())} ${shellQuote(url.trim())}")

  suspend fun removeRemote(project: Project, name: String): GitRunResult =
    git(project, "git remote remove ${shellQuote(name.trim())}")

  suspend fun setRemoteUrl(project: Project, name: String, url: String): GitRunResult =
    git(project, "git remote set-url ${shellQuote(name.trim())} ${shellQuote(url.trim())}")

  suspend fun pruneRemotes(project: Project, remote: String = "origin"): GitRunResult =
    git(project, "git remote prune $remote")

  // ---- Tags ----

  suspend fun getTags(project: Project): List<String> {
    if (!isGitRepository(project)) return emptyList()
    val out = git(project, "git tag -l --sort=-creatordate").output
    return out.lines().map { it.trim() }.filter { it.isNotBlank() }
  }

  suspend fun createTag(
    project: Project,
    tagName: String,
    message: String = "",
    commitHash: String? = null
  ): GitRunResult {
    val clean = tagName.trim()
    val target = if (commitHash.isNullOrBlank()) "" else commitHash
    return if (message.isNotBlank()) {
      git(project, "git tag -a ${shellQuote(clean)} -m ${shellQuote(message)} $target")
    } else {
      git(project, "git tag ${shellQuote(clean)} $target")
    }
  }

  suspend fun deleteTag(project: Project, tagName: String): GitRunResult =
    git(project, "git tag -d ${shellQuote(tagName.trim())}")

  suspend fun pushTags(project: Project, remote: String = "origin"): GitRunResult =
    git(project, "git push $remote --tags")

  // ---- Stashes ----

  suspend fun getStashes(project: Project): List<GitStash> {
    if (!isGitRepository(project)) return emptyList()
    val out = git(project, "git stash list --pretty=format:\"%gd|%cr|%gs\"").output
    return out.lines().filter { it.contains("|") }.mapIndexed { idx, line ->
      val parts = line.split("|", limit = 3)
      val ref = parts.getOrNull(0)?.trim() ?: "stash@{$idx}"
      val date = parts.getOrNull(1)?.trim() ?: ""
      val desc = parts.getOrNull(2)?.trim() ?: ""
      val branch = if (desc.contains(":")) desc.substringBefore(":").removePrefix("WIP on ").removePrefix("On ").trim() else "main"
      val msg = if (desc.contains(":")) desc.substringAfter(":").trim() else desc

      GitStash(
        index = idx,
        ref = ref,
        branch = branch,
        message = msg.ifBlank { "Stash #$idx" },
        date = date
      )
    }
  }

  suspend fun stashChanges(
    project: Project,
    message: String = "",
    includeUntracked: Boolean = false
  ): GitRunResult {
    val uFlag = if (includeUntracked) "-u" else ""
    val mArg = if (message.isNotBlank()) "-m ${shellQuote(message.trim())}" else ""
    return git(project, "git stash push $uFlag $mArg")
  }

  suspend fun stashApply(project: Project, index: Int = 0): GitRunResult =
    git(project, "git stash apply stash@{$index}")

  suspend fun stashPop(project: Project, index: Int = 0): GitRunResult =
    git(project, "git stash pop stash@{$index}")

  suspend fun stashDrop(project: Project, index: Int = 0): GitRunResult =
    git(project, "git stash drop stash@{$index}")

  private fun shellQuote(text: String): String =
    "'" + text.replace("'", "'\\''") + "'"
}
