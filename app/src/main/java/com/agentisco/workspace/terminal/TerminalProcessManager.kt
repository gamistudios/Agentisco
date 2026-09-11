package com.agentisco.workspace.terminal

import com.agentisco.data.model.TerminalLine
import com.agentisco.data.model.TerminalLineType
import com.agentisco.data.model.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

class TerminalProcessManager(
  val linuxEnv: LinuxEnvironmentManager = LinuxEnvironmentManager()
) {

  private val activeProcesses = ConcurrentHashMap<String, Process>()

  suspend fun executeCommand(
    session: TerminalSession,
    command: String,
    onLine: (TerminalLine) -> Unit
  ): Int = withContext(Dispatchers.IO) {
    val clean = command.trim()
    if (clean.isEmpty()) return@withContext 0

    // Handle 'clear' command
    if (clean == "clear") {
      return@withContext 0
    }

    // Check if LinuxEnvironmentManager handles this command directly
    if (linuxEnv.canHandle(clean)) {
      return@withContext linuxEnv.execute(session, clean, onLine)
    }

    val workingDirFile = File(session.currentDir).let {
      if (it.exists() && it.isDirectory) it else File("/").takeIf { f -> f.exists() } ?: File(".")
    }

    // Handle 'cd' locally to change session directory state
    if (clean.startsWith("cd ") || clean == "cd") {
      val targetPath = clean.removePrefix("cd").trim()
      val newDir = when {
        targetPath.isEmpty() || targetPath == "~" -> File(System.getProperty("user.home") ?: workingDirFile.path)
        targetPath.startsWith("/") -> File(targetPath)
        else -> File(workingDirFile, targetPath)
      }
      return@withContext if (newDir.exists() && newDir.isDirectory) {
        onLine(TerminalLine(newDir.canonicalPath, TerminalLineType.STDOUT))
        0
      } else {
        onLine(TerminalLine("cd: no such file or directory: $targetPath", TerminalLineType.STDERR))
        1
      }
    }

    // Try executing using system shell
    val shBinary = when {
      File("/system/bin/sh").exists() -> "/system/bin/sh"
      File("/bin/sh").exists() -> "/bin/sh"
      else -> "sh"
    }

    try {
      val processBuilder = ProcessBuilder(shBinary, "-c", clean)
        .directory(workingDirFile)
        .redirectErrorStream(false)

      val env = processBuilder.environment()
      env["TERM"] = "xterm-256color"
      env["PATH"] = (env["PATH"] ?: "") + ":/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin:/vendor/bin:/bin:/usr/bin"

      val process = processBuilder.start()
      activeProcesses[session.id] = process

      // Read stdout
      val stdoutThread = Thread {
        try {
          BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
              line?.let { onLine(TerminalLine(it, TerminalLineType.STDOUT)) }
            }
          }
        } catch (_: Exception) {}
      }

      // Read stderr
      val stderrThread = Thread {
        try {
          BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
              line?.let { onLine(TerminalLine(it, TerminalLineType.STDERR)) }
            }
          }
        } catch (_: Exception) {}
      }

      stdoutThread.start()
      stderrThread.start()

      val exitCode = process.waitFor()
      stdoutThread.join(500)
      stderrThread.join(500)

      activeProcesses.remove(session.id)

      if (exitCode == 0) {
        onLine(TerminalLine("Process completed with exit code 0", TerminalLineType.SUCCESS))
      } else if (exitCode == 127) {
        val cmdName = clean.split("\\s+".toRegex()).firstOrNull() ?: clean
        onLine(TerminalLine("sh: $cmdName: command not found", TerminalLineType.STDERR))
        onLine(TerminalLine("Hint: Install it using 'apt install $cmdName' or type 'help' for available tools.", TerminalLineType.INFO))
      } else {
        onLine(TerminalLine("Process exited with code $exitCode", TerminalLineType.STDERR))
      }
      return@withContext exitCode
    } catch (e: Exception) {
      activeProcesses.remove(session.id)
      val cmdName = clean.split("\\s+".toRegex()).firstOrNull() ?: clean
      onLine(TerminalLine("sh: $cmdName: inaccessible or not found (${e.localizedMessage})", TerminalLineType.STDERR))
      onLine(TerminalLine("Hint: Try 'apt install $cmdName' or 'help' for built-in tools.", TerminalLineType.INFO))
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

  fun isRunning(sessionId: String): Boolean {
    val p = activeProcesses[sessionId]
    return p != null && p.isAlive
  }
}
