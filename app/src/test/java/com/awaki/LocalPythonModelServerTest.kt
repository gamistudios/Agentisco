package com.awaki

import com.awaki.local.FakePythonServer
import com.awaki.local.LocalModelPaths
import com.awaki.local.PythonResponse
import com.awaki.local.py.PythonModelServer
import com.awaki.local.runtime.LocalEngineException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Starting, reusing and stopping the Python model server, without proot or a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalPythonModelServerTest {

  private class FakeProcess(private val exitsWhenThePipeCloses: Boolean = true) : Process() {
    val stdinClosed = AtomicBoolean(false)
    val kills = AtomicInteger(0)
    private val alive = AtomicBoolean(true)

    private val stdin = object : OutputStream() {
      override fun write(b: Int) = Unit
      override fun close() {
        stdinClosed.set(true)
        if (exitsWhenThePipeCloses) alive.set(false)
      }
    }

    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream() =
      ByteArrayInputStream("importing llama_cpp\nlistening for the app".toByteArray())

    override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(): Int = 0
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive.get()
    override fun exitValue(): Int = 0
    override fun isAlive(): Boolean = alive.get()
    override fun destroy() {
      kills.incrementAndGet()
      alive.set(false)
    }

    override fun destroyForcibly(): Process {
      destroy()
      return this
    }
  }

  /** The host side of one test: a temp files dir, the processes it was asked to start, a fake server. */
  private class Harness(script: String) {
    val filesDir: File = Files.createTempDirectory("awaki-server").toFile()
    val started = mutableListOf<FakeProcess>()
    var command: List<String> = emptyList()
    var environment: Map<String, String> = emptyMap()

    private var content = script
    var healthAnswer = """{"ok":true,"protocol":1,"loaded":false,"busy":false,"pid":1,"stats":{"rss_mb":40.0}}"""
    var healthStatus = 200
    var exitsCleanly = true

    /** Rewrites the "asset" the way an app update would, and tells the server it changed. */
    fun replaceScript(next: String) {
      content = next
    }

    val server = FakePythonServer { call ->
      if (call.path == "/health") PythonResponse.json(healthAnswer, healthStatus) else PythonResponse.json("{}")
    }

    fun newServer(
      commandFor: ((Int, String) -> Pair<List<String>, Map<String, String>>?)? = null
    ) =
      PythonModelServer(
        filesDir = filesDir,
        readScript = { content.toByteArray() },
        commandFor = commandFor ?: { port, token ->
          PythonModelServer.guestCommand(port, token, mapOf("HOME" to "/root", "PATH" to "/usr/bin")) to
            mapOf("PROOT_LOADER" to "/x/loader")
        },
        start = { argv, env ->
          command = argv
          environment = env
          FakeProcess(exitsCleanly).also { started += it }
        },
        portPicker = { server.port },
        startupTimeoutMs = 1_500
      )

    fun startServer() = server.apply { start() }
  }

  @Test
  fun `the server script is written where the guest can already see it`() {
    val host = Harness("serve v1")
    host.startServer()
    try {
      host.newServer().ensureRunning()
      assertEquals("serve v1", LocalModelPaths.hostScript(host.filesDir).readText())
      // The command names the guest path, never the host path the file was written to: those
      // are the same directory only from the app's side of the bind.
      assertTrue(host.command.contains(LocalModelPaths.SERVER_SCRIPT))
      assertFalse(host.command.any { it.contains(host.filesDir.absolutePath) })
    } finally {
      host.server.stop()
    }
  }

  @Test
  fun `the guest is started with an empty environment, the venv interpreter and this port`() {
    val host = Harness("s")
    host.startServer()
    try {
      val client = host.newServer().ensureRunning()
      assertTrue("the launcher only returns once the server answers", client.health() != null)
      assertEquals("/usr/bin/env", host.command.first())
      assertEquals("-i", host.command[1])
      assertTrue(host.command.contains("HOME=/root"))
      assertEquals(
        LocalModelPaths.VENV_PYTHON,
        host.command[host.command.indexOf(LocalModelPaths.SERVER_SCRIPT) - 1]
      )
      assertEquals(host.server.port.toString(), host.command[host.command.indexOf("--port") + 1])
      val token = host.command[host.command.indexOf("--token") + 1]
      assertTrue("the app chose a secret, not a placeholder: $token", token.length >= 32)
      // The client the launcher returns is the one holding that token: a mismatch would be a
      // server that answers nobody can ask anything of.
      assertEquals("Bearer $token", host.server.calls.last().token)
      assertEquals(mapOf("PROOT_LOADER" to "/x/loader"), host.environment)
    } finally {
      host.server.stop()
    }
  }

  @Test
  fun `one server answers every turn`() {
    val host = Harness("s")
    host.startServer()
    try {
      val server = host.newServer()
      val first = server.ensureRunning()
      assertEquals(1, host.started.size)
      assertSame(first, server.ensureRunning())
      assertEquals(1, host.started.size)
      assertSame(first, server.currentClient())
      server.stop()
      assertNull(server.currentClient())
    } finally {
      host.server.stop()
    }
  }

  @Test
  fun `a script the app has replaced takes its server down with it`() {
    val host = Harness("v1")
    host.startServer()
    try {
      val server = host.newServer()
      server.ensureRunning()
      assertTrue(File(host.filesDir, "local-models/serve.py").readText() == "v1")
      host.replaceScript("v2")
      server.ensureRunning()
      assertEquals("v2", File(host.filesDir, "local-models/serve.py").readText())
      assertTrue("the old process was ended", host.started[0].stdinClosed.get())
      assertEquals("a second server runs the new script", 2, host.started.size)
    } finally {
      host.server.stop()
    }
  }

  @Test
  fun `nothing starts over an environment that is not there`() {
    val host = Harness("s")
    val server = host.newServer(commandFor = { _, _ -> null })
    val failure = runCatching { server.ensureRunning() }.exceptionOrNull()
    assertTrue(failure is LocalEngineException)
    assertTrue(failure!!.message!!.contains("Terminal"))
    assertEquals(0, host.started.size)
  }

  @Test
  fun `a server that never answers is reported with what it managed to say`() {
    val host = Harness("s")
    host.healthAnswer = """{"error":{"message":"not yet"}}"""
    host.healthStatus = 503
    host.startServer()
    try {
      val server = host.newServer()
      val failure = runCatching { server.ensureRunning() }.exceptionOrNull()
      assertTrue(failure is LocalEngineException)
      assertTrue(failure!!.message!!.contains("did not start"))
      assertTrue(failure.message!!.contains("importing llama_cpp"))
      assertTrue("the process was given up on", host.started.single().stdinClosed.get())
      assertNull(server.currentClient())
    } finally {
      host.server.stop()
    }
  }

  @Test
  fun `stopping closes the pipe first and only kills a process that ignores it`() {
    val orderly = FakeProcess(exitsWhenThePipeCloses = true)
    orderly.outputStream.close()
    assertTrue(orderly.stdinClosed.get())
    assertFalse(orderly.isAlive())
    assertEquals(0, orderly.kills.get())

    val host = Harness("s")
    host.exitsCleanly = false
    host.startServer()
    try {
      val server = host.newServer()
      server.ensureRunning()
      server.stop()
      assertTrue("a process that ignores the pipe is killed", host.started.single().kills.get() > 0)
    } finally {
      host.server.stop()
    }
  }
}
