package com.awaki.workspace.terminal

import com.awaki.data.model.TerminalLine
import com.awaki.data.model.TerminalLineType
import com.awaki.data.model.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Executes commands for the agent tooling and scripted flows: non-interactive
 * (pipes, no PTY) but inside the real Debian-based rootfs via proot, so apt,
 * git, compilers etc. behave exactly like on a Linux box. The interactive
 * human-facing terminal uses [ProotSessionManager] instead.
 */
class TerminalProcessManager(
  private val argsBuilderProvider: () -> ProotArgsBuilder?
) {

  private val activeProcesses = ConcurrentHashMap<String, Running>()
  private val backgrounds = ConcurrentHashMap<String, BackgroundRun>()

  /**
   * Called whenever the set of live commands changes, with the commands themselves.
   * The app keeps a foreground service up while any of them runs, so a build the
   * agent started does not get killed the moment the screen goes off.
   */
  var onRunningChanged: ((List<RunningCommand>) -> Unit)? = null

  /** One live command: the id [interrupt] accepts, and the command line it runs. */
  data class RunningCommand(val id: String, val command: String)

  /** A child process of this app plus the command it is running. */
  private class Running(val process: Process, val label: String)

  private fun track(key: String, process: Process, label: String) {
    activeProcesses[key] = Running(process, label)
    notifyRunning()
  }

  private fun untrack(key: String) {
    if (activeProcesses.remove(key) != null) notifyRunning()
  }

  private fun notifyRunning() {
    val callback = onRunningChanged ?: return
    callback(activeProcesses.map { (key, running) -> RunningCommand(key, running.label) })
  }

  /** A command the agent started and did not wait for. */
  private class BackgroundRun(val command: String) {
    val lines = ArrayDeque<String>()
    var droppedLines = 0
    var exitCode: Int? = null
    val startedAt = System.currentTimeMillis()

    fun append(line: String) = synchronized(this) {
      if (lines.size >= MAX_BUFFERED_LINES) {
        lines.removeFirst()
        droppedLines++
      }
      lines.addLast(line)
    }

    fun tail(maxLines: Int): Pair<List<String>, Int> = synchronized(this) {
      val from = (lines.size - maxLines).coerceAtLeast(0)
      lines.toList().subList(from, lines.size) to (lines.size - from)
    }
  }

  /** Output and state of a background command, for the agent's `terminal_output`. */
  data class BackgroundStatus(
    val id: String,
    val command: String,
    val running: Boolean,
    val exitCode: Int?,
    val output: String,
    val droppedLines: Int,
    val startedSecondsAgo: Long
  )

  suspend fun executeCommand(
    session: TerminalSession,
    command: String,
    onLine: (TerminalLine) -> Unit,
    projectDir: File? = null
  ): Int = withContext(Dispatchers.IO) {
    val clean = command.trim()
    if (clean.isEmpty()) return@withContext 0
    if (clean == "clear") return@withContext 0

    val launched = launch(clean, projectDir) { message ->
      onLine(TerminalLine(message, TerminalLineType.STDERR))
    } ?: return@withContext 1

    try {
      val processBuilder = ProcessBuilder(launched.argv).redirectErrorStream(false)
      processBuilder.environment().putAll(launched.env)
      val process = processBuilder.start()
      track(session.id, process, clean)

      val stdoutThread = Thread {
        try {
          process.inputStream.bufferedReader().forEachLine { line ->
            onLine(TerminalLine(line, TerminalLineType.STDOUT))
          }
        } catch (_: Exception) {}
      }
      val stderrThread = Thread {
        try {
          process.errorStream.bufferedReader().forEachLine { line ->
            onLine(TerminalLine(line, TerminalLineType.STDERR))
          }
        } catch (_: Exception) {}
      }
      stdoutThread.start()
      stderrThread.start()

      val exitCode = process.waitFor()
      stdoutThread.join(1000)
      stderrThread.join(1000)
      untrack(session.id)
      return@withContext exitCode
    } catch (e: Exception) {
      untrack(session.id)
      onLine(TerminalLine("Failed to run command in Linux environment: ${e.localizedMessage}", TerminalLineType.STDERR))
      return@withContext -1
    }
  }

  /**
   * Starts a command and returns immediately with its id, so a long-running one
   * (dev server, watch build, test suite) never wedges the agent's turn. Output is
   * buffered; the id can be passed to [readBackground] and [interrupt].
   * Returns null when the Linux environment is not available.
   */
  suspend fun startBackground(
    session: TerminalSession,
    command: String,
    projectDir: File? = null
  ): String? = withContext(Dispatchers.IO) {
    val clean = command.trim()
    if (clean.isEmpty()) return@withContext null
    // launch() returns null after calling onError, which the null result below reports.
    val launched = launch(clean, projectDir) { } ?: return@withContext null
    val runId = session.id + "-bg-" + java.util.UUID.randomUUID().toString().take(8)
    val run = BackgroundRun(clean)
    backgrounds[runId] = run
    try {
      val processBuilder = ProcessBuilder(launched.argv).redirectErrorStream(false)
      processBuilder.environment().putAll(launched.env)
      val process = processBuilder.start()
      track(runId, process, clean)
      val collect = { stream: java.io.InputStream ->
        Thread {
          try {
            stream.bufferedReader().forEachLine { run.append(it) }
          } catch (_: Exception) {}
        }.start()
      }
      collect(process.inputStream)
      collect(process.errorStream)
      Thread {
        val code = try {
          process.waitFor()
        } catch (_: Exception) {
          null
        }
        synchronized(run) { run.exitCode = code }
        untrack(runId)
      }.start()
      return@withContext runId
    } catch (e: Exception) {
      backgrounds.remove(runId)
      return@withContext null
    }
  }

  /** Recent output of a background command, or null when the id is unknown. */
  fun readBackground(id: String, maxLines: Int = 200): BackgroundStatus? {
    val run = backgrounds[id] ?: return null
    val (tail, furtherHidden) = run.tail(maxLines)
    val exit = synchronized(run) { run.exitCode }
    val running = activeProcesses[id]?.process?.isAlive ?: (exit == null)
    return BackgroundStatus(
      id = id,
      command = run.command,
      running = running,
      exitCode = exit,
      output = tail.joinToString("\n"),
      droppedLines = run.droppedLines + furtherHidden,
      startedSecondsAgo = (System.currentTimeMillis() - run.startedAt) / 1000
    )
  }

  /** Every background command started in this process, newest first. */
  fun listBackgroundRuns(): List<BackgroundStatus> =
    backgrounds.keys.sortedByDescending { backgrounds[it]?.startedAt ?: 0L }
      .mapNotNull { readBackground(it, 0) }

  fun interrupt(sessionId: String): Boolean {
    val running = activeProcesses[sessionId]
    return if (running != null && running.process.isAlive) {
      running.process.destroyForcibly()
      untrack(sessionId)
      true
    } else false
  }

  /**
   * Sends one line to a running command's stdin. The stream is deliberately left
   * open: closing it means end-of-input, so a command waiting for a "y" would see
   * EOF and quit instead of reading the answer.
   */
  fun writeInput(sessionId: String, input: String): Boolean {
    val running = activeProcesses[sessionId] ?: return false
    if (!running.process.isAlive) return false
    return writeLine(running.process.outputStream, input)
  }

  internal fun writeLine(stdin: java.io.OutputStream, input: String): Boolean =
    runCatching {
      stdin.write((input + "\n").toByteArray())
      stdin.flush()
      true
    }.getOrDefault(false)

  fun isRunning(sessionId: String): Boolean =
    activeProcesses[sessionId]?.process?.isAlive == true

  /** Builds the proot argv + environment both execution paths need. */
  private fun launch(command: String, projectDir: File?, onError: (String) -> Unit): Launched? {
    val argsBuilder = argsBuilderProvider()
    if (argsBuilder == null) {
      onError("Linux environment is not ready yet. Open the Terminal tab once to bootstrap Debian.")
      return null
    }
    // Run inside the project's real folder when one is provided (bind-mounted
    // at a fixed guest path); fall back to the rootfs home otherwise.
    val hasWorkspace = projectDir != null && projectDir.isDirectory
    val workingDirInsideRootfs =
      if (hasWorkspace) ProotArgsBuilder.WORKSPACE_GUEST_PATH else "/root"

    val guestCommand = listOf(
      "/usr/bin/env", "-i",
      "HOME=/root",
      "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
      "TERM=dumb",
      "LANG=C.UTF-8",
      "DEBIAN_FRONTEND=noninteractive",
      "/bin/bash", "-c", "cd $workingDirInsideRootfs 2>/dev/null; $command"
    )

    val (argv, env) = if (hasWorkspace) {
      argsBuilder.buildCommand(guestCommand, workingDir = workingDirInsideRootfs, bindHostDir = projectDir)
    } else {
      argsBuilder.buildCommand(guestCommand, workingDir = workingDirInsideRootfs)
    }
    return Launched(argv, env)
  }

  private class Launched(val argv: List<String>, val env: Map<String, String>)

  private companion object {
    const val MAX_BUFFERED_LINES = 2_000
  }
}
