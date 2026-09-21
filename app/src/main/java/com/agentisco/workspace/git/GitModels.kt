package com.agentisco.workspace.git

import com.agentisco.data.model.DiffLine

/** Status of a file in the git working tree or index. */
enum class GitStatusCode(val code: String, val label: String) {
  MODIFIED("M", "Modified"),
  ADDED("A", "Added"),
  DELETED("D", "Deleted"),
  RENAMED("R", "Renamed"),
  UNTRACKED("U", "Untracked"),
  CONFLICTED("C", "Conflicted"),
  COPIED("C", "Copied"),
  TYPE_CHANGED("T", "Type changed"),
  IGNORED("!", "Ignored"),
  UNKNOWN("?", "Unknown");

  companion object {
    fun fromPorcelainChar(c: Char): GitStatusCode = when (c) {
      'M' -> MODIFIED
      'A' -> ADDED
      'D' -> DELETED
      'R' -> RENAMED
      'C' -> COPIED
      'T' -> TYPE_CHANGED
      'U' -> CONFLICTED
      '?' -> UNTRACKED
      '!' -> IGNORED
      else -> UNKNOWN
    }
  }
}

/** Information about a changed file in the repository. */
data class GitFileStatus(
  val path: String,
  val fileName: String = path.substringAfterLast('/'),
  val directory: String = if (path.contains('/')) path.substringBeforeLast('/') else "",
  val status: GitStatusCode = GitStatusCode.MODIFIED,
  val isStaged: Boolean = false,
  val additions: Int = 0,
  val deletions: Int = 0,
  val oldPath: String? = null,
  val isConflicted: Boolean = false,
  val isDeleted: Boolean = status == GitStatusCode.DELETED,
  val isUntracked: Boolean = status == GitStatusCode.UNTRACKED
)

/** Active git operation currently underway. */
enum class GitActiveOperation(val label: String) {
  NONE("None"),
  MERGE("Merge in progress"),
  REBASE("Rebase in progress"),
  CHERRY_PICK("Cherry-pick in progress"),
  REVERT("Revert in progress")
}

/** Remote repository configuration. */
data class GitRemote(
  val name: String,
  val fetchUrl: String,
  val pushUrl: String
)

/** Branch representation with ahead/behind metrics. */
data class GitBranch(
  val name: String,
  val isCurrent: Boolean,
  val isRemote: Boolean,
  val upstream: String? = null,
  val ahead: Int = 0,
  val behind: Int = 0,
  val lastCommitHash: String? = null,
  val lastCommitMessage: String? = null
)

/** Saved stash entry. */
data class GitStash(
  val index: Int,
  val ref: String, // stash@{0}
  val branch: String,
  val message: String,
  val date: String = ""
)

/** Git tag entry. */
data class GitTag(
  val name: String,
  val commitHash: String = "",
  val message: String = ""
)

/** Detailed view of a commit. */
data class GitCommitDetail(
  val hash: String,
  val fullHash: String,
  val author: String,
  val authorEmail: String,
  val date: String,
  val relativeDate: String,
  val subject: String,
  val body: String = "",
  val parents: List<String> = emptyList(),
  val filesChanged: List<GitCommitFileChange> = emptyList(),
  val diff: String = "",
  val refs: List<String> = emptyList(),
  val totalAdditions: Int = 0,
  val totalDeletions: Int = 0
)

/** File change inside a commit. */
data class GitCommitFileChange(
  val path: String,
  val status: GitStatusCode,
  val additions: Int,
  val deletions: Int,
  val oldPath: String? = null
)

/** Diff hunk with header and lines. */
data class GitDiffHunk(
  val header: String,
  val oldStart: Int,
  val oldCount: Int,
  val newStart: Int,
  val newCount: Int,
  val lines: List<DiffLine>
)

/** Complete parsed diff for one file. */
data class GitFileDiff(
  val filePath: String,
  val oldPath: String? = null,
  val status: GitStatusCode = GitStatusCode.MODIFIED,
  val isBinary: Boolean = false,
  val hunks: List<GitDiffHunk> = emptyList(),
  val rawDiff: String = "",
  val additions: Int = 0,
  val deletions: Int = 0,
  val isStaged: Boolean = false
)

/** Overall repository status. */
data class GitRepoStatus(
  val isRepo: Boolean = false,
  val currentBranch: String = "main",
  val upstreamBranch: String? = null,
  val aheadCount: Int = 0,
  val behindCount: Int = 0,
  val isClean: Boolean = true,
  val isDetachedHead: Boolean = false,
  val headCommitHash: String? = null,
  val headCommitMessage: String? = null,
  val activeOperation: GitActiveOperation = GitActiveOperation.NONE,
  val conflictedFiles: List<GitFileStatus> = emptyList(),
  val stagedFiles: List<GitFileStatus> = emptyList(),
  val unstagedFiles: List<GitFileStatus> = emptyList(),
  val untrackedFiles: List<GitFileStatus> = emptyList(),
  val totalChangedFiles: Int = 0,
  val remotes: List<GitRemote> = emptyList(),
  val tags: List<String> = emptyList()
)

/** Modes for undoing the last commit. */
enum class UndoCommitMode {
  KEEP_STAGED,    // git reset --soft HEAD~1
  KEEP_UNSTAGED,  // git reset HEAD~1
  REVERT_COMMIT   // git revert HEAD
}

/** Modes for resetting to a specific commit. */
enum class ResetMode {
  SOFT,   // --soft
  MIXED,  // --mixed
  HARD    // --hard
}

/** Types for copying diff text. */
enum class DiffCopyType(val label: String) {
  ALL("All Changes"),
  STAGED("Staged Changes"),
  UNSTAGED("Unstaged Changes"),
  FILE("File Diff")
}

