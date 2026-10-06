package com.awaki.local.py

import com.awaki.local.LocalModelPaths
import com.awaki.local.runtime.LocalEngineException
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The model server process, and the client that talks to it.
 *
 * The server is started lazily and kept alive between turns: loading a GGUF takes seconds, and
 * a process that exits after each reply would pay that on every message. It is one process per
 * app, holding one model, on a port chosen here and passed on the command line — the app never
 * scrapes a port out of the guest's output.
 *
 * Two things end it. [stop] closes the pipe the process watches and then kills it, which is the
 * orderly path. If the *app* dies, the pipe closes on its own and the script exits by itself,
 * because a Python process still holding a model's weights after the app that launched it is
 * gone is a few hundred megabytes nobody can reclaim without a reboot.
 */
class PythonModelServer(
  private val filesDir: File,
  /** The script as the current build ships it, read from the app's assets. */
  private val readScript: () -> ByteArray,
  /**
   * Turns a port and a token into the host command line that starts the server, or null when
   * the Linux environment cannot run yet. Kept this narrow so the process handling — the part
   * with no second chance on a phone — is testable without proot.
   */
  private val commandFor: (port: Int, token: String) -> Pair<List<String>, Map<String, String>>?,
  private val start: (command: List<String>, environment: Map<String, String>) -> Process,
  private val portPicker: () -> Int = { freePort() },
  private val startupTimeoutMs: Long = 120_000
) {

  @Volatile
  private var process: Process? = null

  @Volatile
  private var client: PythonModelClient? = null

  private val log = ArrayDeque<String>()

  /** What the guest has printed, newest last — the only witness when a start fails. */
  fun logSnapshot(): List<String> = synchronized(log) { log.toList() }

  val running: Boolean get() = process?.isAlive == true

  /** The client for the running server, or null when nothing is up. */
  fun currentClient(): PythonModelClient? = client?.takeIf { running }

  /**
   * Returns a server that has answered `/health`, starting one if necessary.
   *
   * Blocking, because the caller is a load or a decode that cannot proceed without it. A server
   * that is already up and already holding the asked-for model is reused as it stands: the model
   * stays resident and the turn starts without a reload.
   */
  fun ensureRunning(): PythonModelClient {
    val scriptChanged = ServerScript.install(readScript, LocalModelPaths.hostScript(filesDir))
    if (scriptChanged) stop()
    currentClient()?.let { existing ->
      if (existing.health() != null) return existing
      // The process is alive but not answering: it is stuck or dying, and a second client
      // against the same port would only hide that.
      stop()
    }

    val port = portPicker()
    val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    val command = commandFor(port, token)
      ?: throw LocalEngineException(
        "The Linux environment is not set up yet, so the model cannot run from Python. " +
          "Set it up from the Terminal screen first."
      )
    val launched = try {
      start(command.first, command.second)
    } catch (e: Exception) { // noqa - a failed exec is a user-facing reason, not a stack trace
      throw LocalEngineException("The model server could not be started: ${e.message}", e)
    }
    process = launched
    // Read from the first line: everything the guest says while the interpreter imports is
    // gone by the time a start fails, and it is the only reason the user gets told.
    drainOutput(launched)
    val waiting = PythonModelClient(port, token)
    if (!waiting.awaitHealthy(startupTimeoutMs, alive = { launched.isAlive })) {
      val reason = synchronized(log) { log.toList() }.takeLast(6).joinToString(" / ")
      stop()
      throw LocalEngineException(
        "The model server did not start." + (if (reason.isBlank()) "" else " Last it said: $reason")
      )
    }
    client = waiting
    return waiting
  }

  /** Ends the server: the pipe first so it can shut itself down, the process if it does not. */
  fun stop() {
    client = null
    val launched = process
    process = null
    if (launched == null) return
    runCatching { launched.outputStream.close() }
    if (!launched.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
      launched.destroy()
      if (!launched.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) launched.destroyForcibly()
    }
  }

  private fun drainOutput(launched: Process) {
    val reader = Thread({
      runCatching {
        BufferedReader(InputStreamReader(launched.inputStream, StandardCharsets.UTF_8)).useLines { lines ->
          lines.forEach { line ->
            if (line.isBlank()) return@forEach
            synchronized(log) {
              log.addLast(line)
              while (log.size > LOG_LINES) log.removeFirst()
            }
          }
        }
      }
    }, "model-server-log")
    reader.isDaemon = true
    reader.start()
  }

  companion object {
    /** Lines of guest output kept for the screen and for a failure message. */
    const val LOG_LINES = 60

    /**
     * The guest command line: an empty environment, the bundled runtime's own interpreter, the
     * script the app just wrote, and the port and token this process will answer on.
     *
     * `env -i` is what makes it reproducible — the server sees exactly the variables listed
     * here, so a guest shell full of terminal settings cannot change how a model loads. The
     * library path is not optional: the runtime carries shared libraries the Ubuntu base image
     * does not ship, and an interpreter that cannot find them fails before it prints a reason.
     */
    fun guestCommand(port: Int, token: String, guestEnv: Map<String, String>): List<String> =
      listOf("/usr/bin/env", "-i") +
        guestEnv.entries.map { (key, value) -> "$key=$value" } +
        listOf(
          LocalModelPaths.RUNTIME_PYTHON,
          LocalModelPaths.SERVER_SCRIPT,
          "--port", port.toString(),
          "--token", token
        )

    /** An unused port, asked of the kernel rather than guessed at. */
    fun freePort(): Int = ServerSocket(0).use { it.localPort }
  }
}
