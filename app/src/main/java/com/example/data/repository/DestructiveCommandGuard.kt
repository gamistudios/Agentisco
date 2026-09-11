package com.example.data.repository

data class DestructiveAssessment(
  val isDestructive: Boolean,
  val command: String,
  val title: String,
  val reason: String,
  val severity: DestructiveSeverity = DestructiveSeverity.HIGH
)

enum class DestructiveSeverity {
  MEDIUM,
  HIGH,
  CRITICAL
}

object DestructiveCommandGuard {

  /**
   * Assesses whether a given command line contains destructive actions.
   * Returns a DestructiveAssessment if dangerous, or null if safe to proceed freely.
   */
  fun assess(command: String): DestructiveAssessment? {
    val clean = command.trim()
    if (clean.isEmpty()) return null

    // Split pipeline/chained commands (;, &&, ||, |) to inspect every sub-segment
    val segments = clean.split("[;&|]+".toRegex()).map { it.trim() }.filter { it.isNotEmpty() }
    for (seg in segments) {
      val directCheck = checkSingleCommand(seg, clean)
      if (directCheck != null) return directCheck
    }
    return null
  }

  private fun checkSingleCommand(segment: String, fullCommand: String): DestructiveAssessment? {
    val parts = segment.split("\\s+".toRegex())
    val cmd = parts.firstOrNull()?.lowercase() ?: return null
    val args = if (parts.size > 1) parts.subList(1, parts.size) else emptyList()

    // 1. Filesystem destructive removal (rm, unlink)
    if (cmd == "rm" || cmd == "unlink") {
      val hasRecursive = args.any { it.matches("-[a-zA-Z]*[rR][a-zA-Z]*".toRegex()) || it == "--recursive" }
      val hasForce = args.any { it.matches("-[a-zA-Z]*[fF][a-zA-Z]*".toRegex()) || it == "--force" }
      val targets = args.filter { !it.startsWith("-") }

      val targetsRoot = targets.any { it == "/" || it == "/*" || it == "/root" || it == "/system" || it == "/data" }
      val targetsWildcard = targets.any { it == "*" || it == ".*" || it == "./*" }
      val targetsHomeOrCurrent = targets.any { it == "~" || it == "." || it == ".." || it == "\$HOME" }

      if (hasRecursive && (targetsRoot || targetsWildcard || targetsHomeOrCurrent)) {
        return DestructiveAssessment(
          isDestructive = true,
          command = fullCommand,
          title = "Destructive Deletion Guard",
          reason = "This command ($segment) recursively deletes the root filesystem, home directory, or current workspace contents permanently.",
          severity = DestructiveSeverity.CRITICAL
        )
      }

      if (hasRecursive || (hasForce && targets.isNotEmpty())) {
        return DestructiveAssessment(
          isDestructive = true,
          command = fullCommand,
          title = "Recursive Removal Guard",
          reason = "This command will recursively and permanently delete directories and files: ${targets.joinToString(", ")}.",
          severity = DestructiveSeverity.HIGH
        )
      }
    }

    // 2. Git destructive operations
    if (cmd == "git") {
      val sub = args.firstOrNull()?.lowercase() ?: ""
      if (sub == "reset" && args.any { it == "--hard" }) {
        return DestructiveAssessment(
          isDestructive = true,
          command = fullCommand,
          title = "Git Hard Reset Guard",
          reason = "Running 'git reset --hard' will permanently discard all uncommitted changes and uncommitted files in your workspace.",
          severity = DestructiveSeverity.HIGH
        )
      }

      if (sub == "clean" && args.any { it.contains("f") }) {
        return DestructiveAssessment(
          isDestructive = true,
          command = fullCommand,
          title = "Git Clean Guard",
          reason = "Running 'git clean -f' will permanently wipe untracked files and build artifacts from your repository.",
          severity = DestructiveSeverity.HIGH
        )
      }

      if (sub == "push" && args.any { it == "--force" || it == "-f" || it == "--force-with-lease" }) {
        return DestructiveAssessment(
          isDestructive = true,
          command = fullCommand,
          title = "Force Push Guard",
          reason = "Force pushing ('git push -f') overwrites the remote Git history and may cause data loss for other contributors.",
          severity = DestructiveSeverity.HIGH
        )
      }

      if (sub == "branch" && args.any { it == "-D" }) {
        val branchName = args.lastOrNull { !it.startsWith("-") } ?: "branch"
        return DestructiveAssessment(
          isDestructive = true,
          command = fullCommand,
          title = "Force Delete Branch Guard",
          reason = "Force-deleting branch '$branchName' ('git branch -D') permanently removes unmerged commits.",
          severity = DestructiveSeverity.MEDIUM
        )
      }

      if (sub == "restore" && args.any { it == "." || it == "--all" }) {
        return DestructiveAssessment(
          isDestructive = true,
          command = fullCommand,
          title = "Git Restore All Guard",
          reason = "Running 'git restore .' will discard all unstaged local file changes in the workspace.",
          severity = DestructiveSeverity.MEDIUM
        )
      }
    }

    // 3. Raw Disk / Low-Level Formatting
    if (cmd == "dd" && args.any { it.startsWith("of=/dev/") }) {
      return DestructiveAssessment(
        isDestructive = true,
        command = fullCommand,
        title = "Raw Disk Write Guard",
        reason = "Writing raw data to a block device ('$segment') can destroy partition tables and filesystems.",
        severity = DestructiveSeverity.CRITICAL
      )
    }

    if (cmd == "mkfs" || cmd.startsWith("mkfs.") || cmd == "fdisk" || cmd == "parted") {
      return DestructiveAssessment(
        isDestructive = true,
        command = fullCommand,
        title = "Disk Partition / Format Guard",
        reason = "Disk formatting or partitioning commands can erase storage drives.",
        severity = DestructiveSeverity.CRITICAL
      )
    }

    // 4. Permission destruction
    if (cmd == "chmod") {
      val hasRecursive = args.any { it == "-R" || it == "--recursive" }
      val targetsRoot = args.any { it == "/" || it == "/*" || it == "/system" || it == "/data" }
      if (hasRecursive && targetsRoot) {
        return DestructiveAssessment(
          isDestructive = true,
          command = fullCommand,
          title = "System Permission Wipe Guard",
          reason = "Applying recursive chmod on root or system directories will break Android OS security and system services.",
          severity = DestructiveSeverity.CRITICAL
        )
      }
    }

    // 5. Fork Bomb
    if (segment.contains(":(){ :|:& };:") || segment.contains(":(){:|:&};:")) {
      return DestructiveAssessment(
        isDestructive = true,
        command = fullCommand,
        title = "Fork Bomb Attack Guard",
        reason = "A shell fork bomb exhausts system process limits and crashes the runtime device.",
        severity = DestructiveSeverity.CRITICAL
      )
    }

    // 6. Database destruction
    val upper = segment.uppercase()
    if (upper.contains("DROP DATABASE") || upper.contains("DROP TABLE") || upper.contains("TRUNCATE TABLE")) {
      return DestructiveAssessment(
        isDestructive = true,
        command = fullCommand,
        title = "Database Destruction Guard",
        reason = "This SQL command drops database tables or wipes database schemas.",
        severity = DestructiveSeverity.HIGH
      )
    }

    return null
  }
}
