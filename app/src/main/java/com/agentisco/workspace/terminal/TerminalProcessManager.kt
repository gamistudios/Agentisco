package com.agentisco.workspace.terminal

import com.agentisco.data.model.TerminalLine
import com.agentisco.data.model.TerminalLineType
import com.agentisco.data.model.TerminalSession
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

  private val activeProcesses = ConcurrentHashMap<String, Process>()
  private val backgrounds = ConcurrentHashMap<String, BackgroundRun>()

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
      activeProcesses[session.id] = process

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
      activeProcesses.remove(session.id)
      return@withContext exitCode
    } catch (e: Exception) {
      activeProcesses.remove(session.id)
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
      activeProcesses[runId] = process
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
        activeProcesses.remove(runId)
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
    val running = activeProcesses[id]?.isAlive ?: (exit == null)
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
    val process = activeProcesses[sessionId]
    return if (process != null && process.isAlive) {
      process.destroyForcibly()
      activeProcesses.remove(sessionId)
      true
    } else false
  }

  fun writeInput(sessionId: String, input: String): Boolean {
    val process = activeProcesses[sessionId] ?: return false
    if (!process.isAlive) return false
    return try {
      process.outputStream.use { stream ->
        stream.write((input + "\n").toByteArray())
        stream.flush()
      }
      true
    } catch (_: Exception) {
      false
    }
  }

  fun isRunning(sessionId: String): Boolean {
    val p = activeProcesses[sessionId]
    return p != null && p.isAlive
  }

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
