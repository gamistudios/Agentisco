package com.agentisco.local.py

import com.agentisco.local.LocalModelPaths
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Python environment the on-device models run in.
 *
 * It is a virtualenv inside the model directory, which the Linux guest mounts as its own
 * `~/local-models`: one install covers the model bytes and the interpreter that reads them,
 * a rootfs reinstall does not throw away the compiled wheels, and nothing is written to the
 * app's private storage that the user cannot see from a terminal.
 *
 * Setup is the slow part of this feature — `llama-cpp-python` has no wheel for Android's
 * arm64 Linux, so the phone compiles llama.cpp from source — which is why every step is a
 * separate command, the whole log is kept for the screen to show, and re-running costs
 * almost nothing on an environment that is already there.
 */
class PythonEnvironment(
  private val filesDir: File,
  /**
   * Runs one shell command inside the Linux environment and returns its exit code. Injected
   * because the environment belongs to the terminal layer, and because a setup sequence is
   * only testable when the commands it sends can be read back.
   */
  private val execute: suspend (command: String, onLine: (String) -> Unit) -> Int,
  /** Stops whatever command [execute] is currently blocked on. */
  private val interrupt: () -> Unit = {}
) {

  sealed interface State {
    /** What the guest printed, so every state can be shown the same way. */
    val log: List<String>

    /** Nothing on disk: the model directory holds no virtualenv. */
    data object Missing : State {
      override val log: List<String> get() = emptyList()
    }

    /**
     * The virtualenv is on disk. Whether the packages still import is only known by asking
     * the guest, which [setup] does in a second — this state is the cheap host-side answer.
     */
    data class Installed(override val log: List<String> = emptyList()) : State

    /** A step is running; [step] names it and [log] carries what the guest printed. */
    data class Installing(val step: String, override val log: List<String>) : State

    /** A step failed. The log is the reason, and the label is where to look in it. */
    data class Failed(val step: String, val reason: String, override val log: List<String>) : State
  }

  private val _state = MutableStateFlow<State>(if (venvOnDisk()) State.Installed() else State.Missing)
  val state: StateFlow<State> = _state.asStateFlow()

  private val lock = Mutex()

  @Volatile
  private var cancelled = false

  /** True once [setup] has finished in this process — the only readiness claim that is verified. */
  fun isReady(): Boolean = verified

  private var verified = false

  /**
   * Creates the venv and installs what the model server imports, or brings an existing one
   * back up to date. Returns true when the guest itself confirmed the packages import.
   * [onStep] hears each label before it runs, for a notification that has to say what the
   * phone is busy with for the next few minutes.
   */
  suspend fun setup(onStep: (String) -> Unit = {}): Boolean = lock.withLock {
    cancelled = false
    val log = mutableListOf<String>()
    for (step in steps()) {
      if (cancelled) return@withLock fail(log, step.label, "Cancelled")
      publishInstalling(step.label, log)
      onStep(step.label)
      val exit = runStep(step, log)
      if (cancelled) return@withLock fail(log, step.label, "Cancelled")
      if (exit != 0) return@withLock fail(log, step.label, "${step.label} failed (exit $exit)")
    }
    verified = true
    _state.value = State.Installed(log.takeLast(LOG_LINES))
    true
  }

  /** Records why setup cannot even start, in the same shape as a failed step. */
  fun markUnavailable(reason: String) {
    verified = false
    _state.value = State.Failed("Linux environment", reason, listOf(reason))
  }

  /** Gives up on the running step: the flag stops the next one, [interrupt] stops this one. */
  fun cancel() {
    cancelled = true
    interrupt()
  }

  /** Back to a state the screen can act on without losing the log the user was reading. */
  private fun fail(log: List<String>, step: String, reason: String): Boolean {
    verified = false
    _state.value = State.Failed(step, reason, log.takeLast(LOG_LINES))
    return false
  }

  private fun publishInstalling(step: String, log: List<String>) {
    _state.value = State.Installing(step, log.takeLast(LOG_LINES))
  }

  /** Runs one step, appending every line it prints so the screen can show live progress. */
  private suspend fun runStep(step: Step, log: MutableList<String>): Int {
    log += "\$ ${step.label}"
    return execute(step.command) { line ->
      log += line
      if (log.size > LOG_LINES * 2) log.subList(0, log.size - LOG_LINES).clear()
      publishInstalling(step.label, log)
    }.also { log += "-> exit $it" }
  }

  private fun modelsHostDir(): File = LocalModelPaths.hostDir(filesDir)

  /**
   * The venv's own marker files. `bin/python` is a symlink to an absolute *guest* path, so
   * from the host it dangles and reports as missing; `pyvenv.cfg` and `bin/pip` are real
   * files, and a directory holding both is a virtualenv whatever else changed underneath it.
   */
  private fun venvOnDisk(): Boolean {
    val venv = File(modelsHostDir(), ".venv")
    return File(venv, "pyvenv.cfg").isFile && File(venv, "bin/pip").isFile
  }

  /** One labelled guest command. Each is idempotent, so a rerun repairs rather than repeats. */
  private class Step(val label: String, val command: String)

  private fun steps(): List<Step> {
    val python = LocalModelPaths.VENV_PYTHON
    val venv = LocalModelPaths.VENV_DIR
    return listOf(
      Step("Model directory", "mkdir -p ${LocalModelPaths.GUEST_DIR}"),
      // Ubuntu ships python3 without the venv module, and the interpreter cannot create an
      // environment it has no module for; installing both in one transaction costs one apt run.
      // The probe has to cover the compiler inputs too: a machine with a working venv module but
      // no Python.h would otherwise skip apt and fail the llama-cpp-python build minutes later.
      Step(
        "Python",
        "command -v python3 >/dev/null 2>&1 && python3 -c 'import venv, ensurepip' >/dev/null 2>&1 " +
          "&& ls /usr/include/python3*/Python.h >/dev/null 2>&1 && command -v cmake >/dev/null 2>&1 || " +
          "{ apt-get update -qq && apt-get install -y --no-install-recommends " +
          "python3 python3-dev python3-venv python3-pip build-essential cmake; }"
      ),
      Step("Virtual environment", "python3 -m venv $venv"),
      // The bundled pip is whatever version the image shipped; llama-cpp-python's source
      // build needs a resolver new enough to honour its build requirements.
      Step("pip", "$python -m pip install --no-cache-dir --upgrade pip"),
      // No wheel for this platform, so this is the step that compiles llama.cpp on the phone.
      Step(
        "llama-cpp-python",
        "$python -c 'import llama_cpp, psutil' >/dev/null 2>&1 || " +
          "$python -m pip install --no-cache-dir llama-cpp-python psutil"
      ),
      // The guest answers: proof the interpreter, the package and its native library all work.
      Step(
        "Verification",
        "$python -c 'import llama_cpp, psutil; print(\"llama-cpp-python\", llama_cpp.__version__)'"
      )
    )
  }

  companion object {
    /** Lines of guest output the screen keeps. The tail is what matters when a step fails. */
    const val LOG_LINES = 60
  }
}
