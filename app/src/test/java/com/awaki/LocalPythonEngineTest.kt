package com.awaki

import com.awaki.local.FakePythonServer
import com.awaki.local.PythonResponse
import com.awaki.local.model.LocalRuntimeSettings
import com.awaki.local.py.PythonEngine
import com.awaki.local.py.PythonModelServer
import com.awaki.local.runtime.LocalEngineException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The production engine: the seam between [com.awaki.local.runtime.LocalModelEngine] and the
 * model server the guest runs.
 *
 * What is checked here is the two things this layer actually decides — which path the guest is
 * told to open, and how many cores it decodes on — plus the failure a phone hits most often: the
 * environment was never set up, and nothing may be started before it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalPythonEngineTest {

  private class FakeGuestProcess : Process() {
    val stdinClosed = AtomicBoolean(false)
    private val alive = AtomicBoolean(true)
    private val stdin = object : OutputStream() {
      override fun write(b: Int) = Unit
      override fun close() {
        stdinClosed.set(true)
        alive.set(false)
      }
    }

    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
    override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(): Int = 0
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive.get()
    override fun exitValue(): Int = 0
    override fun isAlive(): Boolean = alive.get()
    override fun destroy() {
      alive.set(false)
    }

    override fun destroyForcibly(): Process {
      destroy()
      return this
    }
  }

  private val loadedModel =
    """{"ok":true,"model":{"path":"/root/local-models/x.gguf","n_ctx":4000,"n_vocab":32000,""" +
      """"architecture":"lfm2","name":"LFM2.5-230M","chat":{"usesOwnTemplate":true,""" +
      """"supportsTools":true,"supportsParallelToolCalls":false,"supportsThinking":false,""" +
      """"supportsSystemMessage":true}}}"""

  /** A files dir, a guest process, and a model server answering over a real socket. */
  private class Harness(private val answer: String) {
    val filesDir: File = Files.createTempDirectory("awaki-engine").toFile()
    val server = FakePythonServer { call ->
      when (call.path) {
        "/health" -> PythonResponse.json(
          """{"ok":true,"protocol":2,"loaded":false,"busy":false,"pid":1,"stats":{"rss_mb":40.0}}"""
        )

        "/v1/load" -> PythonResponse.json(answer)
        else -> PythonResponse.json("""{"ok":true}""")
      }
    }

    var startedProcesses = 0
    val processes = mutableListOf<FakeGuestProcess>()

    val modelServer = PythonModelServer(
      filesDir = filesDir,
      readScript = { "serve".toByteArray() },
      commandFor = { port, token -> PythonModelServer.guestCommand(port, token, emptyMap()) to emptyMap() },
      start = { _, _ ->
        startedProcesses++
        FakeGuestProcess().also { processes += it }
      },
      portPicker = { server.port },
      startupTimeoutMs = 1_500
    )

    fun begin() {
      server.start()
    }

    fun end() {
      modelServer.stop()
      server.stop()
      filesDir.deleteRecursively()
    }

    fun engine(ready: () -> Boolean = { true }): PythonEngine = PythonEngine(filesDir, modelServer, ready)

    fun loadBody(): JSONObject = JSONObject(server.calls.last { it.path == "/v1/load" }.body)
  }

  private fun withEngine(block: Harness.() -> Unit) {
    val host = Harness(loadedModel)
    host.begin()
    try {
      host.block()
    } finally {
      host.end()
    }
  }

  private fun modelIn(host: Harness, file: String = "x.gguf") = File(host.filesDir, "local-models/$file")

  @Test
  fun `the guest opens the model at the path it can actually read`() = withEngine {
    val session = engine().load(modelIn(this).absolutePath, LocalRuntimeSettings(contextSize = 4000))

    // The host path is where the app wrote the bytes; the bind is what makes them the same file.
    assertEquals("/root/local-models/x.gguf", loadBody().getString("model_path"))
    assertEquals(4000, loadBody().getInt("n_ctx"))
    assertEquals("LFM2.5-230M", session.info.publishedName)
    assertTrue(session.capabilities().supportsTools)
    assertFalse(session.capabilities().supportsParallelToolCalls)
  }

  /** "Use every core" has to become a real number here: the library's own default is a small one. */
  @Test
  fun `a thread count of zero means the cores this device has`() = withEngine {
    engine().load(modelIn(this).absolutePath, LocalRuntimeSettings(threadCount = 0))

    assertEquals(Runtime.getRuntime().availableProcessors().coerceAtLeast(1), loadBody().getInt("n_threads"))
  }

  @Test
  fun `a thread count the user set is the one the model decodes with`() = withEngine {
    engine().load(modelIn(this).absolutePath, LocalRuntimeSettings(threadCount = 2))

    assertEquals(2, loadBody().getInt("n_threads"))
  }

  @Test
  fun `a device without the model runtime starts nothing`() {
    val host = Harness(loadedModel)
    host.begin()
    try {
      val engine = host.engine(ready = { false })

      assertFalse(engine.isAvailable)
      assertTrue(engine.unavailableReason.contains("model runtime"))
      val failure = runCatching { engine.load("/data/x.gguf", LocalRuntimeSettings()) }.exceptionOrNull()
      assertTrue(failure is LocalEngineException)
      // The runtime is in the app, so the message points at the screen that unpacks it.
      assertTrue(failure!!.message!!.contains("on-device models screen"))
      assertEquals(0, host.startedProcesses)
    } finally {
      host.end()
    }
  }

  /** Closing a session is not closing the server: a reload costs seconds, a start costs more. */
  @Test
  fun `giving the weights back leaves the server standing`() = withEngine {
    val engine = engine()
    val session = engine.load(modelIn(this).absolutePath, LocalRuntimeSettings())
    session.close()

    assertEquals("/v1/unload", server.calls.last().path)
    assertEquals(1, startedProcesses)
    assertTrue("unloading the weights leaves the guest running", modelServer.running)
    assertFalse(processes.single().stdinClosed.get())

    engine.shutdown()
    assertFalse(modelServer.running)
    assertTrue("the guest is given the pipe to close on", processes.single().stdinClosed.get())
  }

  @Test
  fun `stopping a turn reaches the model that is decoding`() = withEngine {
    val session = engine().load(modelIn(this).absolutePath, LocalRuntimeSettings())
    session.abort()

    assertEquals("/abort", server.calls.last().path)
  }
}
