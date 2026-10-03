package com.agentisco

import com.agentisco.local.py.PythonEnvironment
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app asks the Linux environment to do, and what it believes afterwards.
 *
 * The commands are the product here: a virtualenv built in a directory the guest cannot read,
 * or a five-minute compile repeated because a step was not idempotent, is exactly the kind of
 * defect that only shows up on a phone hours later. So the sequence is asserted as text, and
 * the state machine around it is driven without proot.
 */
class LocalModelEnvironmentTest {

  private class Guest(val failOn: String? = null, val linesPerStep: Int = 1) {
    val commands = mutableListOf<String>()
    var interrupts = 0
    var onFirst: (() -> Unit)? = null

    suspend fun execute(command: String, onLine: (String) -> Unit): Int {
      commands += command
      if (commands.size == 1) onFirst?.invoke()
      repeat(linesPerStep) { onLine("guest line $it for ${command.take(12)}") }
      return if (failOn != null && command.contains(failOn)) 1 else 0
    }
  }

  private fun filesDir(): File = Files.createTempDirectory("scoos-env").toFile()

  /** The model directory the guest mounts, as the host sees it. */
  private fun modelsDir(filesDir: File): File = File(filesDir, "local-models")

  private fun venvOnDisk(filesDir: File, vararg relative: String) {
    val venv = File(modelsDir(filesDir), ".venv")
    relative.forEach { venv.resolve(it).apply { parentFile?.mkdirs(); writeText("x") } }
  }

  @Test
  fun `setup builds the virtualenv inside the directory the guest already sees`() {
    val files = filesDir()
    val guest = Guest()
    val env = PythonEnvironment(files, guest::execute, { guest.interrupts++ })

    assertTrue(runBlocking { env.setup() })

    val venv = commands(guest).single { it.contains("python3 -m venv") }
    assertTrue(venv, venv.contains("/root/local-models/.venv"))
    // Nothing reaches into the rootfs: one mounted directory holds the weights and the venv.
    commands(guest).forEach { assertFalse("$it writes outside the model directory", it.contains("linux-rootfs")) }
    assertTrue(env.isReady())
    assertTrue(env.state.value is PythonEnvironment.State.Installed)
  }

  @Test
  fun `setup upgrades pip and installs both packages the server imports`() {
    val guest = Guest()
    runBlocking { PythonEnvironment(filesDir(), guest::execute).setup() }

    val pip = commands(guest).single { it.contains("--upgrade pip") }
    assertTrue(pip, pip.contains("/root/local-models/.venv/bin/python -m pip"))
    val install = commands(guest).single { it.contains("install --no-cache-dir llama-cpp-python") }
    assertTrue(install, install.contains("psutil"))
    assertTrue(install, install.contains("--no-cache-dir"))
  }

  /** apt is a cost the phone only pays when its python genuinely cannot build an extension. */
  @Test
  fun `python is installed only when the interpreter cannot already build a venv`() {
    val guest = Guest()
    runBlocking { PythonEnvironment(filesDir(), guest::execute).setup() }

    val python = commands(guest).single { it.contains("apt-get") }
    assertTrue(python, python.contains("command -v python3"))
    assertTrue(python, python.contains("import venv, ensurepip"))
    // A device with the venv module but no headers would otherwise reach the compile and fail.
    assertTrue(python, python.contains("/usr/include/python3*/Python.h"))
    assertTrue(python, python.contains("command -v cmake"))
    assertTrue(python, python.contains("||"))
    assertTrue(python, python.contains("--no-install-recommends"))
    // What llama-cpp-python's source build needs that a slim rootfs lacks.
    assertTrue(python, python.contains("python3-dev"))
    assertTrue(python, python.contains("build-essential"))
    assertTrue(python, python.contains(" cmake;"))
  }

  /** The last word is the guest's: proof the interpreter, the package and its library load. */
  @Test
  fun `the sequence ends by asking the guest whether the packages import`() {
    val guest = Guest()
    runBlocking { PythonEnvironment(filesDir(), guest::execute).setup() }

    val last = commands(guest).last()
    assertTrue(last, last.contains("import llama_cpp, psutil"))
    assertTrue(last, last.contains("print"))
  }

  @Test
  fun `a step that fails stops the sequence and says which one`() {
    val guest = Guest(failOn = "python3 -m venv")
    val env = PythonEnvironment(filesDir(), guest::execute)

    assertFalse(runBlocking { env.setup() })

    val failed = env.state.value as PythonEnvironment.State.Failed
    assertEquals("Virtual environment", failed.step)
    assertTrue(failed.reason, failed.reason.contains("exit 1"))
    assertFalse("an unverified venv is not ready", env.isReady())
    assertEquals("pip was never attempted", 3, guest.commands.size)
  }

  @Test
  fun `a venv already on disk is known without asking the guest anything`() {
    val files = filesDir()
    venvOnDisk(files, "pyvenv.cfg", "bin/pip")
    val guest = Guest()

    val env = PythonEnvironment(files, guest::execute)

    assertTrue(env.state.value is PythonEnvironment.State.Installed)
    assertEquals("reading the page must not start proot", 0, guest.commands.size)
    assertFalse("on disk is not the same as verified", env.isReady())
  }

  /**
   * `bin/python` in a venv is a symlink to an absolute path *inside the guest*, so from the
   * host it dangles and reports as absent. The marker files are real, and they are what the
   * probe reads — without both, the directory is a half-built environment and gets treated
   * as one.
   */
  @Test
  fun `a half built venv is not reported as installed`() {
    val files = filesDir()
    venvOnDisk(files, "pyvenv.cfg")

    assertEquals(PythonEnvironment.State.Missing, PythonEnvironment(files, Guest()::execute).state.value)
  }

  @Test
  fun `a cancel ends the sequence and kills the command that was running`() {
    val guest = Guest()
    val env = PythonEnvironment(filesDir(), guest::execute, { guest.interrupts++ })
    guest.onFirst = { env.cancel() }

    assertFalse(runBlocking { env.setup() })

    assertEquals("nothing after the cancelled step ran", 1, guest.commands.size)
    val failed = env.state.value as PythonEnvironment.State.Failed
    assertTrue(failed.reason, failed.reason.contains("Cancelled"))
    assertEquals("the running command was interrupted", 1, guest.interrupts)
  }

  @Test
  fun `a missing linux environment is explained instead of silently failing`() {
    val guest = Guest()
    val env = PythonEnvironment(filesDir(), guest::execute)

    env.markUnavailable("The Linux environment is not set up yet")

    val failed = env.state.value as PythonEnvironment.State.Failed
    assertEquals("Linux environment", failed.step)
    assertTrue(failed.reason, failed.reason.contains("not set up"))
    assertEquals("no command was attempted", 0, guest.commands.size)
  }

  /** Minutes of pip output would otherwise bury the step the user is waiting on. */
  @Test
  fun `the log keeps the tail of what the guest printed`() {
    val guest = Guest(linesPerStep = 400)
    val env = PythonEnvironment(filesDir(), guest::execute)

    runBlocking { env.setup() }

    val log = (env.state.value as PythonEnvironment.State.Installed).log
    assertTrue("${log.size} lines kept", log.size in 1..PythonEnvironment.LOG_LINES)
    assertEquals("the newest output is the last line", "-> exit 0", log.last())
    assertFalse("the first step scrolled out of the tail", log.any { it.startsWith("\$ Model directory") })
  }

  private fun commands(guest: Guest): List<String> = guest.commands
}
