package com.example.data.repository

import com.example.data.model.Project
import com.example.data.model.TerminalLine
import com.example.data.model.TerminalLineType
import com.example.data.model.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class LinuxEnvironmentManager(
  private val fileSystem: ProjectFileSystem? = null,
  private val gitManager: GitRepositoryManager? = null,
  private val activeProjectProvider: () -> Project? = { null },
  private val stagedFilesProvider: () -> Set<String> = { emptySet() },
  private val onStageFile: ((String) -> Unit)? = null,
  private val onStageAll: (() -> Unit)? = null,
  private val onCommit: ((String) -> Unit)? = null,
  private val onRevertFile: ((String) -> Unit)? = null
) {

  private val installedPackages = mutableSetOf(
    "git", "ssh", "apt", "pkg", "termux-bridge", "curl", "neofetch", "tree"
  )

  private var isRootElevated = false

  private val okHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

  fun getInstalledPackages(): Set<String> = installedPackages.toSet()

  fun isPackageInstalled(pkg: String): Boolean = installedPackages.contains(pkg.lowercase().trim())

  fun canHandle(command: String): Boolean {
    val clean = command.trim()
    val firstWord = clean.split("\\s+".toRegex()).firstOrNull()?.lowercase() ?: ""
    return when (firstWord) {
      "git", "ssh", "ssh-keygen", "apt", "pkg", "termux", "termux-bridge", "termux-setup",
      "sudo", "su", "neofetch", "curl", "which", "uname", "whoami", "help", "man",
      "node", "python", "python3", "tree", "ls", "pwd", "cat", "mkdir", "touch", "rm", "echo" -> true
      else -> false
    }
  }

  suspend fun execute(
    session: TerminalSession,
    command: String,
    onLine: (TerminalLine) -> Unit
  ): Int = withContext(Dispatchers.IO) {
    val clean = command.trim()
    val parts = clean.split("\\s+".toRegex())
    val rootCommand = parts.firstOrNull()?.lowercase() ?: return@withContext 0
    val args = if (parts.size > 1) parts.subList(1, parts.size) else emptyList()

    val currentDirFile = File(session.currentDir).let {
      if (it.exists() && it.isDirectory) it else File("/").takeIf { f -> f.exists() } ?: File(".")
    }

    when (rootCommand) {
      "git" -> handleGitCommand(args, session, onLine)
      "ssh" -> handleSshCommand(args, onLine)
      "ssh-keygen" -> handleSshKeygen(args, onLine)
      "apt", "pkg" -> handlePackageManager(args, rootCommand, onLine)
      "termux", "termux-bridge", "termux-setup" -> handleTermuxBridge(args, onLine)
      "sudo" -> handleSudo(args, session, onLine)
      "su" -> handleSu(onLine)
      "neofetch" -> handleNeofetch(onLine)
      "curl" -> handleCurl(args, onLine)
      "which" -> handleWhich(args, onLine)
      "uname" -> handleUname(args, onLine)
      "whoami" -> handleWhoami(onLine)
      "node" -> handleNode(args, onLine)
      "python", "python3" -> handlePython(args, onLine)
      "tree" -> handleTree(currentDirFile, onLine)
      "ls" -> handleLs(currentDirFile, args, onLine)
      "pwd" -> {
        onLine(TerminalLine(currentDirFile.canonicalPath, TerminalLineType.STDOUT))
        0
      }
      "cat" -> handleCat(currentDirFile, args, onLine)
      "touch" -> handleTouch(currentDirFile, args, onLine)
      "mkdir" -> handleMkdir(currentDirFile, args, onLine)
      "rm" -> handleRm(currentDirFile, args, onLine)
      "echo" -> handleEcho(args, onLine)
      "help", "man" -> handleHelp(onLine)
      else -> {
        onLine(TerminalLine("Command '$clean' executed in environment.", TerminalLineType.STDOUT))
        0
      }
    }
  }

  // --- GIT SUB-COMMANDS ---
  private suspend fun handleGitCommand(
    args: List<String>,
    session: TerminalSession,
    onLine: (TerminalLine) -> Unit
  ): Int {
    if (!isPackageInstalled("git")) {
      onLine(TerminalLine("git: command not found. Run 'apt install git' to enable.", TerminalLineType.STDERR))
      return 127
    }

    val sub = args.firstOrNull()?.lowercase() ?: ""
    val project = activeProjectProvider()

    when (sub) {
      "--version", "version", "-v" -> {
        onLine(TerminalLine("git version 2.45.2 (ScoOS Git Engine with Myers Diff)", TerminalLineType.STDOUT))
        return 0
      }

      "status" -> {
        onLine(TerminalLine("On branch main", TerminalLineType.STDOUT))
        onLine(TerminalLine("Your branch is up to date with 'origin/main'.", TerminalLineType.STDOUT))
        onLine(TerminalLine("", TerminalLineType.STDOUT))

        val changedFiles = if (project != null && gitManager != null) {
          gitManager.getChangedFiles(project)
        } else emptyList()

        val staged = stagedFilesProvider()

        val stagedChanged = changedFiles.filter { staged.contains(it) }
        val unstagedChanged = changedFiles.filter { !staged.contains(it) }

        if (stagedChanged.isNotEmpty()) {
          onLine(TerminalLine("Changes to be committed:", TerminalLineType.SUCCESS))
          onLine(TerminalLine("  (use \"git restore --staged <file>...\" to unstage)", TerminalLineType.STDOUT))
          stagedChanged.forEach { f ->
            onLine(TerminalLine("\tmodified:   $f", TerminalLineType.SUCCESS))
          }
          onLine(TerminalLine("", TerminalLineType.STDOUT))
        }

        if (unstagedChanged.isNotEmpty()) {
          onLine(TerminalLine("Changes not staged for commit:", TerminalLineType.STDERR))
          onLine(TerminalLine("  (use \"git add <file>...\" to update what will be committed)", TerminalLineType.STDOUT))
          onLine(TerminalLine("  (use \"git restore <file>...\" to discard changes in working directory)", TerminalLineType.STDOUT))
          unstagedChanged.forEach { f ->
            onLine(TerminalLine("\tmodified:   $f", TerminalLineType.STDERR))
          }
          onLine(TerminalLine("", TerminalLineType.STDOUT))
        }

        if (stagedChanged.isEmpty() && unstagedChanged.isEmpty()) {
          onLine(TerminalLine("nothing to commit, working tree clean", TerminalLineType.STDOUT))
        } else if (stagedChanged.isEmpty()) {
          onLine(TerminalLine("no changes added to commit (use \"git add\" to track)", TerminalLineType.STDOUT))
        }
        return 0
      }

      "diff" -> {
        if (project == null || gitManager == null) {
          onLine(TerminalLine("fatal: not a git repository (or any of the parent directories): .git", TerminalLineType.STDERR))
          return 128
        }
        val targetFile = args.getOrNull(1)
        val diffs = gitManager.computeAllDiffs(project)
        val filtered = if (!targetFile.isNullOrEmpty()) diffs.filter { it.filePath == targetFile || it.filePath.endsWith(targetFile) } else diffs

        if (filtered.isEmpty()) {
          return 0
        }

        filtered.forEach { fd ->
          onLine(TerminalLine("diff --git a/${fd.filePath} b/${fd.filePath}", TerminalLineType.INFO))
          onLine(TerminalLine("--- a/${fd.filePath}", TerminalLineType.INFO))
          onLine(TerminalLine("+++ b/${fd.filePath}", TerminalLineType.INFO))
          fd.lines.forEach { l ->
            when (l.type) {
              com.example.data.model.DiffLineType.ADDED -> onLine(TerminalLine("+ ${l.text}", TerminalLineType.SUCCESS))
              com.example.data.model.DiffLineType.REMOVED -> onLine(TerminalLine("- ${l.text}", TerminalLineType.STDERR))
              com.example.data.model.DiffLineType.UNCHANGED -> onLine(TerminalLine("  ${l.text}", TerminalLineType.STDOUT))
            }
          }
        }
        return 0
      }

      "add" -> {
        val target = args.getOrNull(1)
        if (target.isNullOrEmpty()) {
          onLine(TerminalLine("Nothing specified, nothing added. Maybe you wanted to say 'git add .'?", TerminalLineType.STDERR))
          return 0
        }
        if (target == "." || target == "-A" || target == "--all") {
          onStageAll?.invoke()
          val count = if (project != null && gitManager != null) gitManager.getChangedFiles(project).size else 0
          onLine(TerminalLine("Staged $count files for commit.", TerminalLineType.SUCCESS))
        } else {
          onStageFile?.invoke(target)
          onLine(TerminalLine("Staged '$target'.", TerminalLineType.SUCCESS))
        }
        return 0
      }

      "commit" -> {
        var msg = "Update project files"
        val mIdx = args.indexOf("-m")
        if (mIdx != -1 && mIdx + 1 < args.size) {
          msg = args.subList(mIdx + 1, args.size).joinToString(" ").removeSurrounding("\"").removeSurrounding("'")
        }
        val staged = stagedFilesProvider()
        if (staged.isEmpty()) {
          onLine(TerminalLine("On branch main\nChanges not staged for commit: use 'git add' to stage before committing.", TerminalLineType.STDERR))
          return 1
        }
        onCommit?.invoke(msg)
        val shortHash = "c${(100000..999999).random()}"
        onLine(TerminalLine("[main $shortHash] $msg", TerminalLineType.SUCCESS))
        onLine(TerminalLine(" ${staged.size} file(s) changed, staged changes recorded to git history", TerminalLineType.STDOUT))
        return 0
      }

      "log" -> {
        if (project == null || gitManager == null) {
          onLine(TerminalLine("fatal: your current branch 'main' does not have any commits yet", TerminalLineType.STDERR))
          return 128
        }
        val history = gitManager.getCommitHistory(project)
        if (history.isEmpty()) {
          onLine(TerminalLine("commit 3a9f02b (HEAD -> main)", TerminalLineType.INFO))
          onLine(TerminalLine("Author: ScoOS Developer <dev@scoos.local>", TerminalLineType.STDOUT))
          onLine(TerminalLine("Date:   Fri Sep 11 05:00:00 2026 +0000", TerminalLineType.STDOUT))
          onLine(TerminalLine("", TerminalLineType.STDOUT))
          onLine(TerminalLine("    Initial commit and workspace scaffolding", TerminalLineType.STDOUT))
        } else {
          history.forEach { c ->
            onLine(TerminalLine("commit ${c.hash.take(7)} (HEAD -> main)", TerminalLineType.INFO))
            onLine(TerminalLine("Author: ${c.author}", TerminalLineType.STDOUT))
            onLine(TerminalLine("Date:   ${c.date}", TerminalLineType.STDOUT))
            onLine(TerminalLine("", TerminalLineType.STDOUT))
            onLine(TerminalLine("    ${c.message}", TerminalLineType.STDOUT))
            onLine(TerminalLine("", TerminalLineType.STDOUT))
          }
        }
        return 0
      }

      "branch" -> {
        val branchSub = args.getOrNull(1)
        if (branchSub == "-a" || branchSub == "--all") {
          onLine(TerminalLine("* main", TerminalLineType.SUCCESS))
          onLine(TerminalLine("  remotes/origin/HEAD -> origin/main", TerminalLineType.STDOUT))
          onLine(TerminalLine("  remotes/origin/main", TerminalLineType.STDOUT))
        } else if (branchSub == "-d" || branchSub == "-D") {
          val toDel = args.getOrNull(2) ?: "branch"
          onLine(TerminalLine("Deleted branch $toDel (was 3a9f02b).", TerminalLineType.SUCCESS))
        } else if (!branchSub.isNullOrEmpty() && !branchSub.startsWith("-")) {
          onLine(TerminalLine("Created branch '$branchSub'.", TerminalLineType.SUCCESS))
        } else {
          onLine(TerminalLine("* main", TerminalLineType.SUCCESS))
        }
        return 0
      }

      "remote" -> {
        val remoteSub = args.getOrNull(1)
        when (remoteSub) {
          "-v", "--verbose" -> {
            onLine(TerminalLine("origin\thttps://github.com/scoos/scospace.git (fetch)", TerminalLineType.STDOUT))
            onLine(TerminalLine("origin\thttps://github.com/scoos/scospace.git (push)", TerminalLineType.STDOUT))
          }
          "add" -> {
            val name = args.getOrNull(2) ?: "origin"
            val url = args.getOrNull(3) ?: ""
            onLine(TerminalLine("Added remote '$name' -> $url", TerminalLineType.SUCCESS))
          }
          "remove", "rm" -> {
            val name = args.getOrNull(2) ?: "origin"
            onLine(TerminalLine("Removed remote '$name'", TerminalLineType.SUCCESS))
          }
          "show" -> {
            val name = args.getOrNull(2) ?: "origin"
            onLine(TerminalLine("* remote $name", TerminalLineType.INFO))
            onLine(TerminalLine("  Fetch URL: https://github.com/scoos/scospace.git", TerminalLineType.STDOUT))
            onLine(TerminalLine("  Push  URL: https://github.com/scoos/scospace.git", TerminalLineType.STDOUT))
            onLine(TerminalLine("  HEAD branch: main", TerminalLineType.STDOUT))
            onLine(TerminalLine("  Remote branch: main tracked", TerminalLineType.STDOUT))
          }
          null -> {
            onLine(TerminalLine("origin", TerminalLineType.STDOUT))
          }
          else -> {
            onLine(TerminalLine("origin\thttps://github.com/scoos/scospace.git (fetch)", TerminalLineType.STDOUT))
            onLine(TerminalLine("origin\thttps://github.com/scoos/scospace.git (push)", TerminalLineType.STDOUT))
          }
        }
        return 0
      }

      "config" -> {
        if (args.contains("--list") || args.contains("-l")) {
          onLine(TerminalLine("user.name=ScoOS Developer", TerminalLineType.STDOUT))
          onLine(TerminalLine("user.email=developer@scoos.local", TerminalLineType.STDOUT))
          onLine(TerminalLine("core.repositoryformatversion=0", TerminalLineType.STDOUT))
          onLine(TerminalLine("core.filemode=true", TerminalLineType.STDOUT))
          onLine(TerminalLine("core.bare=false", TerminalLineType.STDOUT))
          onLine(TerminalLine("remote.origin.url=https://github.com/scoos/scospace.git", TerminalLineType.STDOUT))
          onLine(TerminalLine("remote.origin.fetch=+refs/heads/*:refs/remotes/origin/*", TerminalLineType.STDOUT))
          onLine(TerminalLine("branch.main.remote=origin", TerminalLineType.STDOUT))
          onLine(TerminalLine("branch.main.merge=refs/heads/main", TerminalLineType.STDOUT))
        } else if (args.contains("--get") || args.size == 2) {
          val key = args.last()
          when (key) {
            "user.name" -> onLine(TerminalLine("ScoOS Developer", TerminalLineType.STDOUT))
            "user.email" -> onLine(TerminalLine("developer@scoos.local", TerminalLineType.STDOUT))
            "remote.origin.url" -> onLine(TerminalLine("https://github.com/scoos/scospace.git", TerminalLineType.STDOUT))
            else -> onLine(TerminalLine("", TerminalLineType.STDOUT))
          }
        } else {
          onLine(TerminalLine("Updated git configuration.", TerminalLineType.SUCCESS))
        }
        return 0
      }

      "push" -> {
        onLine(TerminalLine("Enumerating objects: 5, done.", TerminalLineType.STDOUT))
        onLine(TerminalLine("Counting objects: 100% (5/5), done.", TerminalLineType.STDOUT))
        onLine(TerminalLine("Delta compression using up to 8 threads", TerminalLineType.STDOUT))
        onLine(TerminalLine("Compressing objects: 100% (3/3), done.", TerminalLineType.STDOUT))
        onLine(TerminalLine("Writing objects: 100% (5/5), 1.2 KiB | 1.2 MiB/s, done.", TerminalLineType.STDOUT))
        onLine(TerminalLine("Total 5 (delta 2), reused 0 (delta 0)", TerminalLineType.STDOUT))
        onLine(TerminalLine("To https://github.com/scoos/scospace.git", TerminalLineType.INFO))
        onLine(TerminalLine("   3a9f02b..b8e2194  main -> main", TerminalLineType.SUCCESS))
        return 0
      }

      "pull", "fetch" -> {
        onLine(TerminalLine("From https://github.com/scoos/scospace", TerminalLineType.INFO))
        onLine(TerminalLine(" * branch            main       -> FETCH_HEAD", TerminalLineType.STDOUT))
        onLine(TerminalLine("Already up to date.", TerminalLineType.SUCCESS))
        return 0
      }

      "clone" -> {
        val url = args.getOrNull(1) ?: "repository"
        onLine(TerminalLine("Cloning into '${url.substringAfterLast("/").removeSuffix(".git")}'...", TerminalLineType.INFO))
        onLine(TerminalLine("remote: Enumerating objects: 142, done.", TerminalLineType.STDOUT))
        onLine(TerminalLine("remote: Counting objects: 100% (142/142), done.", TerminalLineType.STDOUT))
        onLine(TerminalLine("Receiving objects: 100% (142/142), 48.20 KiB | 2.10 MiB/s, done.", TerminalLineType.STDOUT))
        onLine(TerminalLine("Resolving deltas: 100% (68/68), done.", TerminalLineType.STDOUT))
        return 0
      }

      "show" -> {
        onLine(TerminalLine("commit 3a9f02b3c4d5e6f7 (HEAD -> main, origin/main)", TerminalLineType.INFO))
        onLine(TerminalLine("Author: ScoOS Developer <developer@scoos.local>", TerminalLineType.STDOUT))
        onLine(TerminalLine("Date:   Fri Sep 11 05:00:00 2026 +0000", TerminalLineType.STDOUT))
        onLine(TerminalLine("", TerminalLineType.STDOUT))
        onLine(TerminalLine("    Initial commit & workspace configuration", TerminalLineType.STDOUT))
        return 0
      }

      "tag" -> {
        if (args.size > 1 && !args[1].startsWith("-")) {
          onLine(TerminalLine("Created tag '${args[1]}'", TerminalLineType.SUCCESS))
        } else {
          onLine(TerminalLine("v1.0.0", TerminalLineType.STDOUT))
          onLine(TerminalLine("v1.1.0", TerminalLineType.STDOUT))
        }
        return 0
      }

      "stash" -> {
        val stashSub = args.getOrNull(1)
        when (stashSub) {
          "pop" -> onLine(TerminalLine("Dropped refs/stash@{0} (Applied changes to working tree)", TerminalLineType.SUCCESS))
          "list" -> onLine(TerminalLine("stash@{0}: WIP on main: 3a9f02b Initial commit", TerminalLineType.STDOUT))
          "clear" -> onLine(TerminalLine("Stash entries cleared.", TerminalLineType.SUCCESS))
          else -> onLine(TerminalLine("Saved working directory and index state WIP on main", TerminalLineType.SUCCESS))
        }
        return 0
      }

      "merge" -> {
        val branch = args.getOrNull(1) ?: "feature"
        onLine(TerminalLine("Updating 3a9f02b..b8e2194", TerminalLineType.STDOUT))
        onLine(TerminalLine("Fast-forward", TerminalLineType.SUCCESS))
        onLine(TerminalLine("Merge of branch '$branch' complete.", TerminalLineType.SUCCESS))
        return 0
      }

      "rev-parse", "describe", "blame", "rebase", "switch" -> {
        onLine(TerminalLine("main", TerminalLineType.STDOUT))
        return 0
      }

      "reset" -> {
        if (args.contains("--hard")) {
          onLine(TerminalLine("HEAD is now at 3a9f02b Initial commit and workspace scaffolding", TerminalLineType.SUCCESS))
        } else {
          onLine(TerminalLine("Unstaged changes after reset.", TerminalLineType.STDOUT))
        }
        return 0
      }

      "clean" -> {
        onLine(TerminalLine("Removed untracked temporary files.", TerminalLineType.SUCCESS))
        return 0
      }

      "init" -> {
        onLine(TerminalLine("Initialized empty Git repository in ${session.currentDir}/.git/", TerminalLineType.SUCCESS))
        return 0
      }

      "restore", "checkout" -> {
        val target = args.lastOrNull { !it.startsWith("-") }
        if (!target.isNullOrEmpty() && onRevertFile != null) {
          onRevertFile.invoke(target)
          onLine(TerminalLine("Updated 1 path from the index ($target reverted)", TerminalLineType.SUCCESS))
        } else {
          onLine(TerminalLine("Switched to branch 'main'", TerminalLineType.SUCCESS))
        }
        return 0
      }

      else -> {
        // Run system/termux git if present, otherwise output standard git response without artificial restriction
        val termuxGit = File("/data/data/com.termux/files/usr/bin/git")
        val sysGit = File("/system/bin/git")
        val gitBin = if (termuxGit.exists() && termuxGit.canExecute()) termuxGit.absolutePath else if (sysGit.exists() && sysGit.canExecute()) sysGit.absolutePath else null
        if (gitBin != null) {
          try {
            val pb = ProcessBuilder(listOf(gitBin) + args)
              .directory(File(session.currentDir).takeIf { it.exists() } ?: File("."))
            val proc = pb.start()
            proc.inputStream.bufferedReader().forEachLine { onLine(TerminalLine(it, TerminalLineType.STDOUT)) }
            proc.errorStream.bufferedReader().forEachLine { onLine(TerminalLine(it, TerminalLineType.STDERR)) }
            return proc.waitFor()
          } catch (_: Exception) {}
        }

        onLine(TerminalLine("git: executed '$sub' on branch 'main'.", TerminalLineType.STDOUT))
        return 0
      }
    }
  }

  // --- SSH SUB-COMMANDS ---
  private suspend fun handleSshCommand(
    args: List<String>,
    onLine: (TerminalLine) -> Unit
  ): Int {
    if (!isPackageInstalled("ssh")) {
      onLine(TerminalLine("ssh: command not found. Run 'apt install ssh' to enable.", TerminalLineType.STDERR))
      return 127
    }

    if (args.contains("-V") || args.contains("-v") || args.contains("--version")) {
      onLine(TerminalLine("OpenSSH_9.7p1, OpenSSL 3.3.0 7 May 2024 (ScoOS Mobile Linux)", TerminalLineType.STDOUT))
      return 0
    }

    val destination = args.firstOrNull { !it.startsWith("-") }
    if (destination.isNullOrEmpty()) {
      onLine(TerminalLine("usage: ssh [-46AaCfGgKkMNnqsTtVvXxYy] [-B bind_interface]", TerminalLineType.STDERR))
      onLine(TerminalLine("           [-b bind_address] [-c cipher_spec] [-D [bind_address:]port]", TerminalLineType.STDERR))
      onLine(TerminalLine("           [-E log_file] [-e escape_char] [-F configfile] [-I pkcs11]", TerminalLineType.STDERR))
      onLine(TerminalLine("           [-i identity_file] [-J [user@]host[:port]] [-L address]", TerminalLineType.STDERR))
      onLine(TerminalLine("           [-l login_name] [-m mac_spec] [-O ctl_cmd] [-o option]", TerminalLineType.STDERR))
      onLine(TerminalLine("           [-p port] [-Q query_option] [-R address] [-S ctl_path]", TerminalLineType.STDERR))
      onLine(TerminalLine("           [-W host:port] [-w local_tun[:remote_tun]] destination", TerminalLineType.STDERR))
      onLine(TerminalLine("           [command [argument ...]]", TerminalLineType.STDERR))
      return 1
    }

    onLine(TerminalLine("Connecting to $destination port 22...", TerminalLineType.INFO))
    delay(200)
    onLine(TerminalLine("The authenticity of host '$destination (remote.server.ip)' can't be established.", TerminalLineType.STDERR))
    onLine(TerminalLine("ED25519 key fingerprint is SHA256:4K+7vM8yD9qX2nP0bW1jRt5uYzLe3fH6kQa9vZc2pBo.", TerminalLineType.STDOUT))
    onLine(TerminalLine("Connected to $destination. Authenticated with publickey (~/.ssh/id_ed25519).", TerminalLineType.SUCCESS))
    onLine(TerminalLine("Welcome to Ubuntu 24.04 LTS (GNU/Linux 6.8.0-31-generic aarch64)", TerminalLineType.STDOUT))
    onLine(TerminalLine(" * Documentation:  https://help.ubuntu.com", TerminalLineType.STDOUT))
    onLine(TerminalLine(" * Management:     https://landscape.canonical.com", TerminalLineType.STDOUT))
    onLine(TerminalLine("Interactive SSH remote session ready. Type 'exit' to disconnect.", TerminalLineType.INFO))
    return 0
  }

  private fun handleSshKeygen(
    args: List<String>,
    onLine: (TerminalLine) -> Unit
  ): Int {
    if (!isPackageInstalled("ssh")) {
      onLine(TerminalLine("ssh-keygen: command not found. Run 'apt install ssh' to enable.", TerminalLineType.STDERR))
      return 127
    }

    onLine(TerminalLine("Generating public/private ed25519 key pair.", TerminalLineType.STDOUT))
    onLine(TerminalLine("Enter file in which to save the key (/home/sco/.ssh/id_ed25519):", TerminalLineType.STDOUT))
    onLine(TerminalLine("Your identification has been saved in /home/sco/.ssh/id_ed25519", TerminalLineType.SUCCESS))
    onLine(TerminalLine("Your public key has been saved in /home/sco/.ssh/id_ed25519.pub", TerminalLineType.SUCCESS))
    onLine(TerminalLine("The key fingerprint is:", TerminalLineType.STDOUT))
    val randomHex = (1000..9999).random().toString(16)
    onLine(TerminalLine("SHA256:${randomHex}xXyYzZ890123456789abcdefghijklmnopqrst sco@scoos-mobile", TerminalLineType.INFO))
    onLine(TerminalLine("The key's randomart image is:", TerminalLineType.STDOUT))
    onLine(TerminalLine("+--[ED25519 256]--+", TerminalLineType.INFO))
    onLine(TerminalLine("|    .o+o=o.      |", TerminalLineType.INFO))
    onLine(TerminalLine("|     .+B=o.      |", TerminalLineType.INFO))
    onLine(TerminalLine("|    ..*+o+       |", TerminalLineType.INFO))
    onLine(TerminalLine("|   . =+=+        |", TerminalLineType.INFO))
    onLine(TerminalLine("|    S..*.        |", TerminalLineType.INFO))
    onLine(TerminalLine("|      . .        |", TerminalLineType.INFO))
    onLine(TerminalLine("+----[SHA256]-----+", TerminalLineType.INFO))
    return 0
  }

  // --- PACKAGE MANAGER: apt / pkg ---
  private suspend fun handlePackageManager(
    args: List<String>,
    rootCommand: String,
    onLine: (TerminalLine) -> Unit
  ): Int {
    val action = args.firstOrNull()?.lowercase() ?: ""
    val target = args.getOrNull(1)?.lowercase()

    when (action) {
      "update" -> {
        onLine(TerminalLine("Hit:1 https://pkg.scoos.dev/repo main InRelease", TerminalLineType.STDOUT))
        onLine(TerminalLine("Hit:2 https://packages.termux.dev/termux-main stable InRelease", TerminalLineType.STDOUT))
        onLine(TerminalLine("Hit:3 https://packages.termux.dev/termux-root root InRelease", TerminalLineType.STDOUT))
        onLine(TerminalLine("Reading package lists... Done", TerminalLineType.STDOUT))
        onLine(TerminalLine("Building dependency tree... Done", TerminalLineType.STDOUT))
        onLine(TerminalLine("All packages are up to date.", TerminalLineType.SUCCESS))
        return 0
      }

      "install", "add" -> {
        if (target.isNullOrEmpty()) {
          onLine(TerminalLine("Usage: $rootCommand install <package-name>", TerminalLineType.STDERR))
          onLine(TerminalLine("Examples: $rootCommand install git, $rootCommand install ssh, $rootCommand install nodejs, $rootCommand install python", TerminalLineType.INFO))
          return 1
        }
        onLine(TerminalLine("Reading package lists... Done", TerminalLineType.STDOUT))
        onLine(TerminalLine("Building dependency tree... Done", TerminalLineType.STDOUT))
        onLine(TerminalLine("The following NEW packages will be installed: $target", TerminalLineType.INFO))
        onLine(TerminalLine("0 upgraded, 1 newly installed, 0 to remove.", TerminalLineType.STDOUT))
        onLine(TerminalLine("Need to get 2.8 MB of archives.", TerminalLineType.STDOUT))
        delay(150)
        onLine(TerminalLine("Get:1 https://pkg.scoos.dev/packages/$target-arm64.deb [2.8 MB]", TerminalLineType.STDOUT))
        onLine(TerminalLine("Selecting previously unselected package $target...", TerminalLineType.STDOUT))
        onLine(TerminalLine("Preparing to unpack .../$target-arm64.deb ...", TerminalLineType.STDOUT))
        onLine(TerminalLine("Unpacking $target (latest)...", TerminalLineType.STDOUT))
        onLine(TerminalLine("Setting up $target (latest)...", TerminalLineType.STDOUT))
        installedPackages.add(target)
        if (target == "nodejs") installedPackages.add("node")
        if (target == "python3") installedPackages.add("python")
        onLine(TerminalLine("[✓] Package '$target' installed successfully! Ready to run in terminal.", TerminalLineType.SUCCESS))
        return 0
      }

      "list", "list-installed" -> {
        onLine(TerminalLine("Listing installed packages...", TerminalLineType.INFO))
        installedPackages.sorted().forEach { p ->
          onLine(TerminalLine("$p/stable,now latest arm64 [installed]", TerminalLineType.SUCCESS))
        }
        return 0
      }

      "remove", "uninstall" -> {
        if (target != null && installedPackages.contains(target)) {
          installedPackages.remove(target)
          onLine(TerminalLine("Removing $target... Done", TerminalLineType.SUCCESS))
        } else {
          onLine(TerminalLine("Package '${target ?: ""}' is not installed.", TerminalLineType.STDERR))
        }
        return 0
      }

      else -> {
        onLine(TerminalLine("ScoOS Package Manager ($rootCommand) v2.4", TerminalLineType.INFO))
        onLine(TerminalLine("Usage: $rootCommand [update | install <pkg> | list | remove <pkg>]", TerminalLineType.STDOUT))
        onLine(TerminalLine("Available packages: git, ssh, termux-bridge, curl, nodejs, python3, neofetch, tree, htop, zsh", TerminalLineType.INFO))
        return 0
      }
    }
  }

  // --- TERMUX BRIDGE ---
  private fun handleTermuxBridge(
    args: List<String>,
    onLine: (TerminalLine) -> Unit
  ): Int {
    onLine(TerminalLine("==================================================", TerminalLineType.INFO))
    onLine(TerminalLine("           Termux Environment Bridge              ", TerminalLineType.SUCCESS))
    onLine(TerminalLine("==================================================", TerminalLineType.INFO))
    onLine(TerminalLine("Package ID:   com.termux", TerminalLineType.STDOUT))
    onLine(TerminalLine("Prefix:       /data/data/com.termux/files/usr", TerminalLineType.STDOUT))
    onLine(TerminalLine("Bridge PATH:  /data/data/com.termux/files/usr/bin:/system/bin", TerminalLineType.STDOUT))
    onLine(TerminalLine("Workspaces:   /sdcard/Android/data/com.aistudio.scoos", TerminalLineType.STDOUT))
    onLine(TerminalLine("Status:       ACTIVE (Built-in Linux engine + Termux fallback)", TerminalLineType.SUCCESS))
    onLine(TerminalLine("", TerminalLineType.STDOUT))
    onLine(TerminalLine("Supported integrations:", TerminalLineType.INFO))
    onLine(TerminalLine("1. Run 'apt install <pkg>' or 'pkg install <pkg>' directly inside ScoOS", TerminalLineType.STDOUT))
    onLine(TerminalLine("2. Shared storage: Termux can access ScoOS projects via 'termux-setup-storage'", TerminalLineType.STDOUT))
    onLine(TerminalLine("3. Termux:Tasker RunCommandService enables seamless background task execution", TerminalLineType.STDOUT))
    return 0
  }

  // --- SUDO & SU ---
  private fun handleSudo(
    args: List<String>,
    session: TerminalSession,
    onLine: (TerminalLine) -> Unit
  ): Int {
    if (args.isEmpty() || args == listOf("su")) {
      isRootElevated = true
      onLine(TerminalLine("[sudo] authenticating for user sco...", TerminalLineType.STDOUT))
      onLine(TerminalLine("Root developer sandbox privileges enabled (PRoot sandbox).", TerminalLineType.SUCCESS))
      onLine(TerminalLine("Prompt elevated: root#", TerminalLineType.INFO))
      return 0
    }
    onLine(TerminalLine("[sudo] executing with elevated permissions: ${args.joinToString(" ")}", TerminalLineType.INFO))
    return 0
  }

  private fun handleSu(onLine: (TerminalLine) -> Unit): Int {
    isRootElevated = true
    onLine(TerminalLine("Switched to root user (uid=0 gid=0).", TerminalLineType.SUCCESS))
    return 0
  }

  // --- NEOFETCH ---
  private fun handleNeofetch(onLine: (TerminalLine) -> Unit): Int {
    val art = listOf(
      "         .-.             sco@scoos-mobile",
      "        /   \\            ----------------",
      "       |  O  |           OS: ScoOS Linux (Android 14 / Termux)",
      "      /|     |\\          Host: Mobile Device (aarch64)",
      "     (_|     |_)         Kernel: 6.1.75-android14-g9a1b2c",
      "       /     \\           Uptime: 4 hours, 18 mins",
      "      (_______)          Packages: ${installedPackages.size} (apt/pkg)",
      "                         Shell: sco-sh 2.4.2",
      "                         Terminal: ScoOS Studio Terminal",
      "                         CPU: ARM64-v8a (8 cores @ 2.84 GHz)",
      "                         Memory: 3840MiB / 7820MiB"
    )
    art.forEach { onLine(TerminalLine(it, TerminalLineType.INFO)) }
    return 0
  }

  // --- CURL ---
  private fun handleCurl(
    args: List<String>,
    onLine: (TerminalLine) -> Unit
  ): Int {
    val url = args.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
      ?: args.lastOrNull { !it.startsWith("-") }

    if (url.isNullOrEmpty()) {
      onLine(TerminalLine("curl: try 'curl --help' or 'curl --manual' for more information", TerminalLineType.STDERR))
      return 2
    }

    val finalUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url

    return try {
      val request = Request.Builder().url(finalUrl).build()
      val response = okHttpClient.newCall(request).execute()
      onLine(TerminalLine("HTTP/1.1 ${response.code} ${response.message}", TerminalLineType.INFO))
      val body = response.body?.string() ?: ""
      val lines = body.lines().take(20)
      lines.forEach { onLine(TerminalLine(it, TerminalLineType.STDOUT)) }
      if (body.lines().size > 20) {
        onLine(TerminalLine("... (truncated ${body.lines().size - 20} lines)", TerminalLineType.STDOUT))
      }
      0
    } catch (e: Exception) {
      onLine(TerminalLine("curl: (6) Could not resolve host: $finalUrl (${e.localizedMessage})", TerminalLineType.STDERR))
      6
    }
  }

  // --- WHICH, UNAME, WHOAMI ---
  private fun handleWhich(args: List<String>, onLine: (TerminalLine) -> Unit): Int {
    val target = args.firstOrNull() ?: return 1
    if (installedPackages.contains(target.lowercase())) {
      onLine(TerminalLine("/usr/bin/$target", TerminalLineType.STDOUT))
      return 0
    }
    if (File("/system/bin/$target").exists()) {
      onLine(TerminalLine("/system/bin/$target", TerminalLineType.STDOUT))
      return 0
    }
    onLine(TerminalLine("$target not found", TerminalLineType.STDERR))
    return 1
  }

  private fun handleUname(args: List<String>, onLine: (TerminalLine) -> Unit): Int {
    if (args.contains("-a")) {
      onLine(TerminalLine("Linux scoos-mobile 6.1.75-android14-g9a1b2c aarch64 GNU/Linux", TerminalLineType.STDOUT))
    } else {
      onLine(TerminalLine("Linux", TerminalLineType.STDOUT))
    }
    return 0
  }

  private fun handleWhoami(onLine: (TerminalLine) -> Unit): Int {
    val user = if (isRootElevated) "root" else "sco"
    onLine(TerminalLine(user, TerminalLineType.STDOUT))
    return 0
  }

  // --- NODE & PYTHON ---
  private fun handleNode(args: List<String>, onLine: (TerminalLine) -> Unit): Int {
    if (!isPackageInstalled("node") && !isPackageInstalled("nodejs")) {
      onLine(TerminalLine("node: command not found. Run 'apt install nodejs' to enable.", TerminalLineType.STDERR))
      return 127
    }
    if (args.contains("-v") || args.contains("--version") || args.isEmpty()) {
      onLine(TerminalLine("v20.14.0 (ScoOS JavaScript Engine)", TerminalLineType.STDOUT))
      return 0
    }
    val eIdx = args.indexOf("-e")
    if (eIdx != -1 && eIdx + 1 < args.size) {
      val expr = args.subList(eIdx + 1, args.size).joinToString(" ").removeSurrounding("\"").removeSurrounding("'")
      onLine(TerminalLine("Execution result: $expr", TerminalLineType.SUCCESS))
      return 0
    }
    onLine(TerminalLine("Node.js runtime v20.14.0 active.", TerminalLineType.STDOUT))
    return 0
  }

  private fun handlePython(args: List<String>, onLine: (TerminalLine) -> Unit): Int {
    if (!isPackageInstalled("python") && !isPackageInstalled("python3")) {
      onLine(TerminalLine("python: command not found. Run 'apt install python3' to enable.", TerminalLineType.STDERR))
      return 127
    }
    if (args.contains("-v") || args.contains("--version") || args.contains("-V") || args.isEmpty()) {
      onLine(TerminalLine("Python 3.12.3 (ScoOS Linux Python)", TerminalLineType.STDOUT))
      return 0
    }
    val cIdx = args.indexOf("-c")
    if (cIdx != -1 && cIdx + 1 < args.size) {
      val code = args.subList(cIdx + 1, args.size).joinToString(" ").removeSurrounding("\"").removeSurrounding("'")
      onLine(TerminalLine("Python execution: $code", TerminalLineType.SUCCESS))
      return 0
    }
    onLine(TerminalLine("Python 3.12.3 interpreter active.", TerminalLineType.STDOUT))
    return 0
  }

  // --- FILE SYSTEM COMMANDS ---
  private fun handleTree(dir: File, onLine: (TerminalLine) -> Unit): Int {
    onLine(TerminalLine(".", TerminalLineType.STDOUT))
    val files = dir.listFiles() ?: emptyArray()
    files.take(20).forEach { f ->
      val prefix = if (f.isDirectory) "├── 📁 " else "├── 📄 "
      onLine(TerminalLine("$prefix${f.name}", TerminalLineType.STDOUT))
    }
    onLine(TerminalLine("${files.size} directories and files", TerminalLineType.INFO))
    return 0
  }

  private fun handleLs(dir: File, args: List<String>, onLine: (TerminalLine) -> Unit): Int {
    val showHidden = args.any { it.contains("a") }
    val showDetails = args.any { it.contains("l") }

    val files = dir.listFiles()?.filter { showHidden || !it.name.startsWith(".") } ?: emptyList()
    if (showDetails) {
      files.forEach { f ->
        val type = if (f.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
        val size = if (f.isDirectory) 4096 else f.length()
        onLine(TerminalLine("$type  sco  sco  $size  ${f.name}", if (f.isDirectory) TerminalLineType.INFO else TerminalLineType.STDOUT))
      }
    } else {
      val names = files.joinToString("  ") { if (it.isDirectory) "${it.name}/" else it.name }
      if (names.isNotEmpty()) {
        onLine(TerminalLine(names, TerminalLineType.STDOUT))
      }
    }
    return 0
  }

  private fun handleCat(dir: File, args: List<String>, onLine: (TerminalLine) -> Unit): Int {
    val fileName = args.firstOrNull()
    if (fileName.isNullOrEmpty()) {
      onLine(TerminalLine("cat: missing file operand", TerminalLineType.STDERR))
      return 1
    }
    val file = File(dir, fileName)
    return if (file.exists() && file.isFile) {
      file.readLines().take(50).forEach { onLine(TerminalLine(it, TerminalLineType.STDOUT)) }
      0
    } else {
      onLine(TerminalLine("cat: $fileName: No such file or directory", TerminalLineType.STDERR))
      1
    }
  }

  private fun handleTouch(dir: File, args: List<String>, onLine: (TerminalLine) -> Unit): Int {
    val fileName = args.firstOrNull() ?: return 1
    val file = File(dir, fileName)
    return try {
      file.createNewFile()
      onLine(TerminalLine("Created file: $fileName", TerminalLineType.SUCCESS))
      0
    } catch (e: Exception) {
      onLine(TerminalLine("touch: cannot touch '$fileName': ${e.localizedMessage}", TerminalLineType.STDERR))
      1
    }
  }

  private fun handleMkdir(dir: File, args: List<String>, onLine: (TerminalLine) -> Unit): Int {
    val dirName = args.firstOrNull() ?: return 1
    val newDir = File(dir, dirName)
    return if (newDir.mkdirs()) {
      onLine(TerminalLine("Created directory: $dirName", TerminalLineType.SUCCESS))
      0
    } else {
      onLine(TerminalLine("mkdir: cannot create directory '$dirName'", TerminalLineType.STDERR))
      1
    }
  }

  private fun handleRm(dir: File, args: List<String>, onLine: (TerminalLine) -> Unit): Int {
    val fileName = args.lastOrNull { !it.startsWith("-") } ?: return 1
    val file = File(dir, fileName)
    return if (file.exists()) {
      if (file.isDirectory) file.deleteRecursively() else file.delete()
      onLine(TerminalLine("Removed: $fileName", TerminalLineType.SUCCESS))
      0
    } else {
      onLine(TerminalLine("rm: cannot remove '$fileName': No such file or directory", TerminalLineType.STDERR))
      1
    }
  }

  private fun handleEcho(args: List<String>, onLine: (TerminalLine) -> Unit): Int {
    val text = args.joinToString(" ").removeSurrounding("\"").removeSurrounding("'")
    onLine(TerminalLine(text, TerminalLineType.STDOUT))
    return 0
  }

  // --- HELP ---
  private fun handleHelp(onLine: (TerminalLine) -> Unit): Int {
    onLine(TerminalLine("╔══════════════════════════════════════════════════════════════╗", TerminalLineType.INFO))
    onLine(TerminalLine("║              ScoOS Linux Terminal Environment v2.4           ║", TerminalLineType.SUCCESS))
    onLine(TerminalLine("╚══════════════════════════════════════════════════════════════╝", TerminalLineType.INFO))
    onLine(TerminalLine("Available Developer Commands:", TerminalLineType.INFO))
    onLine(TerminalLine("  • git [status|diff|add|commit|log|branch|init|restore]", TerminalLineType.STDOUT))
    onLine(TerminalLine("  • ssh [user@host] / ssh-keygen", TerminalLineType.STDOUT))
    onLine(TerminalLine("  • apt / pkg [install <tool> | update | list | remove]", TerminalLineType.STDOUT))
    onLine(TerminalLine("  • termux / termux-bridge (Termux environment integration)", TerminalLineType.STDOUT))
    onLine(TerminalLine("  • node [-v|-e] / python [-v|-c] / curl <url>", TerminalLineType.STDOUT))
    onLine(TerminalLine("  • sudo / su (elevated sandbox developer shell)", TerminalLineType.STDOUT))
    onLine(TerminalLine("  • neofetch / uname -a / whoami / which <cmd>", TerminalLineType.STDOUT))
    onLine(TerminalLine("  • ls, pwd, cat, touch, mkdir, rm, echo, clear", TerminalLineType.STDOUT))
    onLine(TerminalLine("Type 'apt install <tool>' to install more tools (e.g. nodejs, python3, etc.)", TerminalLineType.SUCCESS))
    return 0
  }
}
