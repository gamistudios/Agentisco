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

  suspend fun executeCommand(
    session: TerminalSession,
    command: String,
    onLine: (TerminalLine) -> Unit,
    projectDir: File? = null
  ): Int = withContext(Dispatchers.IO) {
    val clean = command.trim()
    if (clean.isEmpty()) return@withContext 0
    if (clean == "clear") return@withContext 0

    val argsBuilder = argsBuilderProvider()
    if (argsBuilder == null) {
      onLine(TerminalLine("Linux environment is not ready yet. Open the Terminal tab once to bootstrap Debian.", TerminalLineType.STDERR))
      return@withContext 1
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
      "/bin/bash", "-c", "cd $workingDirInsideRootfs 2>/dev/null; $clean"
    )

    val (argv, hostEnv) = if (hasWorkspace) {
      argsBuilder.buildCommand(guestCommand, workingDir = workingDirInsideRootfs, bindHostDir = projectDir)
    } else {
      argsBuilder.buildCommand(guestCommand, workingDir = workingDirInsideRootfs)
    }

    try {
      val processBuilder = ProcessBuilder(argv).redirectErrorStream(false)
      processBuilder.environment().putAll(hostEnv)
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
}
