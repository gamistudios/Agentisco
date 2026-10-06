package com.awaki

import com.awaki.local.LocalModelPaths
import com.awaki.local.py.BundleResolution
import com.awaki.local.py.PythonRuntime
import com.awaki.local.py.RuntimeBundle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPOutputStream

/**
 * Installing the model runtime: what the app writes, what it asks the guest, and what it
 * believes afterwards.
 *
 * The runtime is a tar.gz inside the APK, so the whole of a setup is a hash, an unpack and one
 * command — and every way that can go wrong leaves the phone unable to run a model. The archive
 * here is a real one, built the way the build script builds it, because a test that mocks the
 * unpacker proves nothing about a tar entry it never read.
 */
class LocalRuntimeInstallTest {

  /** A guest that answers, and remembers what it was asked. */
  private class Guest(private val exit: Int = 0, private val lines: Int = 1) {
    val commands = mutableListOf<String>()
    var interrupts = 0
    var onRun: (() -> Unit)? = null

    suspend fun execute(command: String, onLine: (String) -> Unit): Int {
      commands += command
      repeat(lines) { onLine("guest line $it") }
      onRun?.invoke()
      return exit
    }
  }

  private fun filesDir(): File = Files.createTempDirectory("awaki-runtime").toFile()
  private fun modelsDir(filesDir: File): File = LocalModelPaths.hostDir(filesDir)
  private fun stagedDir(filesDir: File): File = File(modelsDir(filesDir), PythonRuntime.RUNTIME_STAGING_NAME)

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  /** The archive the build script produces: one tree, rooted at `./`, with the modes kept. */
  private fun archive(vararg files: Pair<String, String>, extra: (TarArchiveOutputStream) -> Unit = {}): ByteArray {
    val out = ByteArrayOutputStream()
    TarArchiveOutputStream(GZIPOutputStream(out)).use { tar ->
      files.forEach { (path, content) ->
        val payload = content.toByteArray()
        tar.putArchiveEntry(
          TarArchiveEntry("./$path").apply {
            size = payload.size.toLong()
            mode = if (path.endsWith("bin/python")) 0b111101101 else 0b110100100
          }
        )
        tar.write(payload)
        tar.closeArchiveEntry()
      }
      extra(tar)
      tar.finish()
    }
    return out.toByteArray()
  }

  private fun bundle(bytes: ByteArray, sha: String = sha256(bytes)) = RuntimeBundle(
    abi = "arm64-v8a",
    arch = "aarch64",
    asset = "local-runtime/runtime-aarch64.tar.gz",
    sha256 = sha,
    sizeBytes = bytes.size.toLong(),
    python = "3.12.15",
    llamaCpp = "0.3.36",
    bundleId = "20261003-a"
  )

  private val healthy = archive(
    "bin/python" to "interpreter",
    "lib/libgomp.so.1" to "omp",
    "lib/python3.12/os.py" to "os",
    "lib/python3.12/site-packages/llama_cpp/__init__.py" to "llama",
    "lib/python3.12/site-packages/psutil/__init__.py" to "psutil"
  )

  private fun installer(
    files: File,
    bytes: ByteArray = healthy,
    resolution: BundleResolution = BundleResolution.Found(bundle(healthy)),
    guest: Guest = Guest(),
    open: (RuntimeBundle) -> InputStream? = { ByteArrayInputStream(bytes) }
  ): PythonRuntime = PythonRuntime(files, { resolution }, open, guest::execute, { guest.interrupts++ })

  // ---- the happy path ----------------------------------------------------------

  @Test
  fun `the archive lands in the runtime directory the guest already mounts`() {
    val files = filesDir()
    val installer = installer(files)

    assertTrue(runBlocking { installer.ensure() })

    val root = LocalModelPaths.hostRuntimeDir(files)
    assertTrue(File(root, "bin/python").isFile)
    assertTrue(File(root, "lib/libgomp.so.1").isFile)
    assertTrue(File(root, "lib/python3.12/site-packages/llama_cpp/__init__.py").isFile)
    assertFalse("the staging directory is not a second runtime", stagedDir(files).exists())
    assertTrue(installer.state.value is PythonRuntime.State.Installed)
  }

  /** The marker is written last and holds the digest, which is how a later open knows. */
  @Test
  fun `a verified runtime is remembered by the digest it was installed from`() {
    val files = filesDir()

    runBlocking { installer(files).ensure() }

    assertEquals(sha256(healthy), PythonRuntime.markerDigest(files))
    assertEquals(sha256(healthy), LocalModelPaths.hostRuntimeReady(files).readText().trim())
    assertTrue(PythonRuntime.isCurrent(files, bundle(healthy)))
  }

  /** The guest's answer is the only proof the interpreter, its libraries and every import work. */
  @Test
  fun `the install ends by asking the guest to import what the model server needs`() {
    val guest = Guest()

    runBlocking { installer(filesDir(), guest = guest).ensure() }

    // One command for the whole setup: an unpack needs no shell, and nothing compiles here.
    val command = guest.commands.single()
    assertTrue(command, command.contains("LD_LIBRARY_PATH=${LocalModelPaths.RUNTIME_GUEST_LIB}"))
    assertTrue(command, command.contains("${LocalModelPaths.RUNTIME_PYTHON} -I -c"))
    listOf("llama_cpp", "numpy", "psutil", "jinja2", "diskcache", "typing_extensions").forEach {
      assertTrue("imports $it", command.contains(it))
    }
  }

  @Test
  fun `progress is published while the archive is being read`() {
    val observed = mutableListOf<PythonRuntime.State>()
    val holder = arrayOfNulls<PythonRuntime>(1)
    // A synchronous unpack can only be watched from inside the stream it reads.
    val stream = object : FilterInputStream(ByteArrayInputStream(healthy)) {
      override fun read(data: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(data, offset, length)
        holder[0]?.let { running -> if (count > 0) observed += running.state.value }
        return count
      }
    }
    holder[0] = installer(filesDir(), open = { stream })

    assertTrue(runBlocking { holder[0]!!.ensure() })

    val installing = observed.filterIsInstance<PythonRuntime.State.Installing>().first()
    assertEquals(PythonRuntime.UNPACK_STEP, installing.step)
  }

  /** An older build compiled a virtualenv here and left it behind; nothing reads it now. */
  @Test
  fun `a successful install clears the virtualenv it replaced`() {
    val files = filesDir()
    val venv = File(modelsDir(files), LocalModelPaths.LEGACY_VENV_DIR_NAME)
    File(venv, "bin").mkdirs()
    File(venv, "pyvenv.cfg").writeText("home = /usr/bin")

    runBlocking { installer(files).ensure() }

    assertFalse(venv.exists())
  }

  // ---- what stops the install --------------------------------------------------

  /** A tree the guest cannot run looks installed to every later check while failing at the model. */
  @Test
  fun `a runtime the guest cannot run is removed rather than remembered`() {
    val files = filesDir()
    val installer = installer(files, guest = Guest(exit = 1))

    assertFalse(runBlocking { installer.ensure() })

    assertEquals(PythonRuntime.CHECK_STEP, (installer.state.value as PythonRuntime.State.Failed).step)
    assertFalse("a broken tree is not left in place", LocalModelPaths.hostRuntimeDir(files).exists())
    assertFalse(PythonRuntime.isCurrent(files, bundle(healthy)))
  }

  @Test
  fun `a digest that does not match the catalog never reaches the guest`() {
    val files = filesDir()
    val guest = Guest()
    val installer = installer(files, resolution = BundleResolution.Found(bundle(healthy, sha = "f".repeat(64))), guest = guest)

    assertFalse(runBlocking { installer.ensure() })

    val failed = installer.state.value as PythonRuntime.State.Failed
    assertEquals("Verifying", failed.step)
    assertTrue(failed.reason, failed.reason.contains(sha256(healthy).take(12)))
    assertEquals("nothing was run in a tree that failed its hash", 0, guest.commands.size)
    assertFalse(LocalModelPaths.hostRuntimeDir(files).exists())
    assertFalse(stagedDir(files).exists())
  }

  @Test
  fun `an archive naming a path outside the runtime is refused`() {
    val escape = archive("bin/python" to "x", extra = { tar ->
      tar.putArchiveEntry(TarArchiveEntry("../../evil.py").apply { size = 1; mode = 0b110100100 })
      tar.write("x".toByteArray())
      tar.closeArchiveEntry()
    })
    val files = filesDir()
    val installer = installer(files, bytes = escape, resolution = BundleResolution.Found(bundle(escape)))

    assertFalse(runBlocking { installer.ensure() })

    assertEquals("Unpacking", (installer.state.value as PythonRuntime.State.Failed).step)
    assertFalse("nothing is written outside the runtime", File(files, "evil.py").exists())
    assertFalse(stagedDir(files).exists())
  }

  @Test
  fun `a build with no archive says so instead of failing silently`() {
    val installer = installer(filesDir(), open = { null })

    assertFalse(runBlocking { installer.ensure() })

    val failed = installer.state.value as PythonRuntime.State.Failed
    assertEquals("Runtime archive", failed.step)
    assertTrue(failed.reason, failed.reason.contains("missing from this build"))
  }

  @Test
  fun `no catalog and no bundle for this device are different answers`() {
    val empty = installer(filesDir(), resolution = BundleResolution.NoCatalog)
    assertFalse(runBlocking { empty.ensure() })
    assertEquals("Runtime catalog", (empty.state.value as PythonRuntime.State.Failed).step)

    val other = installer(filesDir(), resolution = BundleResolution.UnsupportedAbi(listOf("x86")))
    assertFalse(runBlocking { other.ensure() })
    val failed = other.state.value as PythonRuntime.State.Failed
    assertEquals("Runtime bundle", failed.step)
    assertTrue(failed.reason, failed.reason.contains("x86"))
  }

  @Test
  fun `a linux environment that is not there is explained, not attempted`() {
    val guest = Guest()
    val installer = installer(filesDir(), guest = guest)

    installer.markUnavailable("The Linux environment is not set up yet")

    assertEquals("Linux environment", (installer.state.value as PythonRuntime.State.Failed).step)
    assertEquals("no command was attempted", 0, guest.commands.size)
  }

  // ---- cancel ------------------------------------------------------------------

  @Test
  fun `a cancel during the check discards the runtime it just unpacked`() {
    val files = filesDir()
    val guest = Guest()
    val installer = installer(files, guest = guest)
    guest.onRun = { installer.cancel() }

    assertFalse(runBlocking { installer.ensure() })

    assertTrue((installer.state.value as PythonRuntime.State.Failed).reason.contains("Cancelled"))
    assertFalse(LocalModelPaths.hostRuntimeDir(files).exists())
    assertEquals("the running command was interrupted", 1, guest.interrupts)
  }

  @Test
  fun `a cancel during the unpack throws away the staged tree`() {
    val files = filesDir()
    val guest = Guest()
    val holder = arrayOfNulls<PythonRuntime>(1)
    var asked = false
    val stream = object : FilterInputStream(ByteArrayInputStream(healthy)) {
      override fun read(data: ByteArray, offset: Int, length: Int): Int {
        if (!asked) {
          asked = true
          holder[0]?.cancel()
        }
        return super.read(data, offset, length)
      }
    }
    holder[0] = installer(files, guest = guest, open = { stream })

    assertFalse(runBlocking { holder[0]!!.ensure() })

    assertFalse(stagedDir(files).exists())
    assertFalse(LocalModelPaths.hostRuntimeDir(files).exists())
    assertEquals("the unpack stopped before the guest was asked", 0, guest.commands.size)
    assertEquals(1, guest.interrupts)
  }

  // ---- why this is automatic ---------------------------------------------------

  /** Opening the screen calls this every time, so the cheap answer has to be the common one. */
  @Test
  fun `a device that already has this build's runtime unpacks nothing`() {
    val files = filesDir()
    runBlocking { installer(files).ensure() }
    val interpreter = File(LocalModelPaths.hostRuntimeDir(files), "bin/python").apply { writeText("installed") }
    val guest = Guest()

    assertTrue(runBlocking { installer(files, guest = guest).ensure() })

    assertEquals("no second proot launch", 0, guest.commands.size)
    assertEquals("the runtime on disk was left alone", "installed", interpreter.readText())
  }

  /** An app update that carries a different archive replaces the runtime without being asked. */
  @Test
  fun `a runtime from another build is replaced`() {
    val files = filesDir()
    runBlocking { installer(files).ensure() }
    val newer = archive("bin/python" to "new build", "lib/libgomp.so.1" to "omp")

    assertTrue(runBlocking { installer(files, bytes = newer, resolution = BundleResolution.Found(bundle(newer))).ensure() })

    assertEquals(sha256(newer), PythonRuntime.markerDigest(files))
    assertEquals(
      "the new interpreter is the one on disk",
      "new build",
      File(LocalModelPaths.hostRuntimeDir(files), "bin/python").readText()
    )
  }

  /** A half-written tree from an interrupted install repairs itself on the next open. */
  @Test
  fun `a runtime directory with no marker is not trusted`() {
    val files = filesDir()
    File(LocalModelPaths.hostRuntimeDir(files), "bin").mkdirs()
    File(LocalModelPaths.hostRuntimeDir(files), "bin/python").writeText("partial")
    val guest = Guest()
    val installer = installer(files, guest = guest)

    assertEquals(PythonRuntime.State.Missing, installer.state.value)
    assertFalse(PythonRuntime.isCurrent(files, bundle(healthy)))
    assertTrue(runBlocking { installer.ensure() })
    assertEquals("the tree was unpacked over the partial one", 1, guest.commands.size)
  }

  @Test
  fun `reading the state does not ask the guest anything`() {
    val files = filesDir()
    runBlocking { installer(files).ensure() }
    val guest = Guest()
    val reopened = installer(files, guest = guest)

    reopened.refresh()

    assertTrue(reopened.state.value is PythonRuntime.State.Installed)
    assertEquals("reading the page must not start proot", 0, guest.commands.size)
  }

  /** Hundreds of interpreter lines would otherwise bury the step the user is waiting on. */
  @Test
  fun `the log keeps the tail of what the guest printed`() {
    val installer = installer(filesDir(), guest = Guest(lines = 400))

    runBlocking { installer.ensure() }

    val log = (installer.state.value as PythonRuntime.State.Installed).log
    assertTrue("${log.size} lines kept", log.size in 1..PythonRuntime.LOG_LINES)
    assertEquals("the newest line is the last", "-> installed ${bundle(healthy).bundleId}", log.last())
    assertTrue("the guest's answer is still in the tail", log.contains("-> exit 0"))
    assertFalse("the beginning of the output scrolled out", log.contains("guest line 0"))
  }
}
