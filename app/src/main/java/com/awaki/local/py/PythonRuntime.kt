package com.awaki.local.py

import com.awaki.local.LocalModelPaths
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.util.zip.GZIPInputStream

/** Why this build cannot install a runtime, phrased for a screen rather than the log. */
sealed interface BundleResolution {
  /** The catalog has an entry for this device and its archive belongs to this build. */
  data class Found(val bundle: RuntimeBundle) : BundleResolution

  /** No catalog, or a catalog that names nothing usable — a development build of the app. */
  data object NoCatalog : BundleResolution

  /** The catalog is here but carries no bundle for this device's ABI list. */
  data class UnsupportedAbi(val abis: List<String>) : BundleResolution
}

/**
 * The Python environment the on-device models run in.
 *
 * It is a prebuilt tree — interpreter, standard library and every package `serve.py` imports —
 * assembled on an ARM64 Linux machine by `tools/local-runtime/build-awaki-runtime.sh` and
 * shipped inside the APK. Setting it up on a phone is therefore a checksum and an unpack: no
 * pip, no compiler, no network. A `llama.cpp` build needs more RAM than a phone that also has
 * to hold a model can spare, which is why the tree arrives already built instead of being
 * built where it runs.
 *
 * It lives inside the model directory, which the guest mounts as its own `~/local-models`, so
 * one install covers the weights and the interpreter that reads them, and a rootfs reinstall
 * throws neither away.
 */
class PythonRuntime(
  private val filesDir: File,
  /** The bundle this device should run, resolved from the shipped catalog and its ABI list. */
  private val resolve: () -> BundleResolution,
  /** Opens the bundle's archive out of the app's assets, or null when this build lacks it. */
  private val openArchive: (RuntimeBundle) -> InputStream?,
  /**
   * Runs one shell command inside the Linux environment and returns its exit code. Injected
   * because only the guest can say whether an unpacked interpreter actually imports, and
   * because an install sequence is only testable when the commands it sends can be read back.
   */
  private val execute: suspend (command: String, onLine: (String) -> Unit) -> Int,
  /** Stops whatever command [execute] is currently blocked on. */
  private val interrupt: () -> Unit = {}
) {

  sealed interface State {
    /** What the guest printed, so every state can be shown the same way. */
    val log: List<String>

    /** Nothing usable on disk, and no install has run in this process yet. */
    data object Missing : State {
      override val log: List<String> get() = emptyList()
    }

    /**
     * The runtime is unpacked, digest-checked and importable by the guest.
     * [detail] names the interpreter and the inference library inside it.
     */
    data class Installed(
      val detail: String,
      override val log: List<String> = emptyList()
    ) : State

    /** A step is running; [step] names it and [percent] is how far the archive has been read. */
    data class Installing(val step: String, val percent: Int, override val log: List<String>) : State

    /** A step failed. The log is the reason, and the label is where to look in it. */
    data class Failed(val step: String, val reason: String, override val log: List<String>) : State
  }

  private val _state = MutableStateFlow<State>(if (isCurrent()) installed() else State.Missing)
  val state: StateFlow<State> = _state.asStateFlow()

  private val lock = Mutex()

  @Volatile
  private var cancelled = false

  /**
   * Whether the runtime on disk is the one this build ships.
   *
   * Three things have to agree: the digest the ready marker recorded, the digest the catalog
   * names for this device, and an interpreter file at the promised path. The marker is written
   * only once every step has passed, so a tree left behind by an interrupted install never
   * counts — which is what makes a half-written runtime repair itself on the next open instead
   * of waiting for a button nobody presses.
   */
  fun isCurrent(): Boolean = isCurrent(filesDir, currentBundle())

  /** The bundle this build says the device should be running, or why it cannot say. */
  fun bundle(): RuntimeBundle? = currentBundle()

  private fun currentBundle(): RuntimeBundle? = (resolve() as? BundleResolution.Found)?.bundle

  /** The state a current runtime is in, for the screen and for [ensure]'s fast path. */
  fun installed(): State = State.Installed(detail = currentBundle()?.detail ?: "Model runtime ready")

  /**
   * Publishes what is on disk without touching it, for a screen that only wants to know.
   *
   * A running install is left alone: its own progress is the more useful thing to show, and an
   * open screen refresh would otherwise blank it mid-unpack.
   */
  fun refresh() {
    if (_state.value is State.Installing) return
    _state.value = if (isCurrent()) installed() else State.Missing
  }

  /**
   * Installs the runtime unless the one on disk is already the one this build ships.
   *
   * Every open of the on-device models screen calls this: a device that has never installed
   * anything pays the unpack once, a device whose directory was deleted from a terminal gets it
   * back, and a device that is already current costs one file check.
   */
  suspend fun ensure(onStep: (String) -> Unit = {}): Boolean = lock.withLock {
    cancelled = false
    if (isCurrent()) {
      _state.value = installed()
      return@withLock true
    }
    install(onStep)
  }

  /** Throws the runtime away and unpacks it again, whatever the disk and the marker say. */
  suspend fun reinstall(onStep: (String) -> Unit = {}): Boolean = lock.withLock {
    cancelled = false
    clearRuntime()
    install(onStep)
  }

  /** Records why installation cannot even start, in the same shape as a failed step. */
  fun markUnavailable(reason: String) {
    _state.value = State.Failed("Linux environment", reason, listOf(reason))
  }

  /** Gives up on the running step: the flag stops the next one, [interrupt] stops this one. */
  fun cancel() {
    cancelled = true
    interrupt()
  }

  // ---- installation -----------------------------------------------------------

  private suspend fun install(onStep: (String) -> Unit): Boolean {
    val log = mutableListOf<String>()
    val bundle = when (val resolution = resolve()) {
      is BundleResolution.Found -> resolution.bundle
      is BundleResolution.NoCatalog ->
        return fail(log, "Runtime catalog", "This build carries no model runtime catalog.")
      is BundleResolution.UnsupportedAbi ->
        return fail(
          log,
          "Runtime bundle",
          "No model runtime is built for this device (${resolution.abis.joinToString()})."
        )
    }

    val archive = openArchive(bundle)
      ?: return fail(log, "Runtime archive", "The runtime archive is missing from this build.")

    publish(UNPACK_STEP, 0, log)
    onStep(UNPACK_STEP)
    val outcome = try {
      unpack(archive, bundle, log)
    } catch (e: Exception) { // noqa - a failed install is a reason to show, not a stack trace
      log += "! ${e.javaClass.simpleName}: ${e.message}"
      Outcome.Error("Unpacking", e.message ?: e.javaClass.simpleName)
    } finally {
      runCatching { archive.close() }
    }

    when (outcome) {
      is Outcome.Cancelled -> {
        clearStaging()
        return fail(log, "Install", "Cancelled")
      }
      is Outcome.Error -> {
        clearStaging()
        return fail(log, outcome.step, outcome.reason)
      }
      is Outcome.Mismatch -> {
        clearStaging()
        return fail(
          log,
          "Verifying",
          "The runtime archive does not match this build (expected ${bundle.sha256.take(12)}…, " +
            "got ${outcome.actual.take(12)}…)."
        )
      }
      is Outcome.Ok -> Unit
    }

    if (!promote()) {
      clearStaging()
      return fail(log, "Install", "The previous runtime could not be replaced.")
    }
    clearLegacyVenv()

    // The guest answers: proof the interpreter runs, its native library loads and every package
    // the model server imports is present. Nothing is marked done before it says so.
    publish(CHECK_STEP, 100, log)
    onStep(CHECK_STEP)
    val verified = checkRuntime(log)
    if (cancelled) {
      clearRuntime()
      return fail(log, "Install", "Cancelled")
    }
    if (!verified) {
      // An unpacked tree the guest cannot run is worse than no tree: it looks installed to
      // every later check while failing at the model. It goes, and the next open retries.
      clearRuntime()
      return fail(
        log,
        CHECK_STEP,
        "The runtime unpacked but the Linux environment could not run it."
      )
    }

    writeMarker(bundle.sha256)
    log += "-> installed ${bundle.bundleId}"
    _state.value = State.Installed(bundle.detail, log.takeLast(LOG_LINES))
    return true
  }

  /** Streams, hashes and unpacks the archive in one pass, so 50 MB is read once, not twice. */
  private suspend fun unpack(archive: InputStream, bundle: RuntimeBundle, log: MutableList<String>): Outcome {
    val digest = MessageDigest.getInstance("SHA-256")
    val total = bundle.sizeBytes.takeIf { it > 0 }
    var read = 0L
    var lastReported = 0L
    var entries = 0
    val staged = stagingDir()

    val counted = object : FilterInputStream(archive) {
      override fun read(data: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(data, offset, length)
        if (count > 0) {
          digest.update(data, offset, count)
          read += count
        }
        return count
      }
    }

    clearStaging()
    if (!staged.mkdirs() && !staged.isDirectory) {
      return Outcome.Error("Unpacking", "The staging directory could not be created.")
    }

    GZIPInputStream(counted.buffered(BUFFER_BYTES)).use { gzip ->
      TarArchiveInputStream(gzip).use { tar ->
        while (true) {
          if (cancelled) {
            log += "-> cancelled after $entries entries"
            return Outcome.Cancelled
          }
          val entry = tar.nextEntry as? TarArchiveEntry ?: break
          val name = entry.name.removePrefix("./")
          if (name.isBlank()) continue
          val target = File(staged, name)
          if (!target.canonicalPath.startsWith(staged.canonicalPath + File.separator)) {
            log += "! refused path outside the runtime: ${entry.name}"
            return Outcome.Error("Unpacking", "The archive names a path outside the runtime directory.")
          }
          writeEntry(tar, entry, target, log)
          entries++
          if (total != null && read - lastReported >= PROGRESS_STRIDE) {
            lastReported = read
            publish(UNPACK_STEP, ((read * 100) / total).toInt().coerceIn(0, 99), log)
          }
        }
      }
    }

    log += "-> unpacked $entries entries from $read bytes"
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    return if (actual == bundle.sha256.lowercase()) Outcome.Ok else Outcome.Mismatch(actual)
  }

  private fun writeEntry(tar: TarArchiveInputStream, entry: TarArchiveEntry, target: File, log: MutableList<String>) {
    when {
      entry.isDirectory -> target.mkdirs()
      entry.isSymbolicLink -> {
        target.parentFile?.mkdirs()
        target.delete()
        runCatching { android.system.Os.symlink(entry.linkName, target.absolutePath) }
          .onFailure { log += "! symlink skipped: ${entry.name}" }
      }
      entry.isLink -> {
        target.parentFile?.mkdirs()
        val hard = File(target.parentFile, entry.linkName.removePrefix("./"))
        if (hard.isFile) {
          target.delete()
          runCatching { android.system.Os.link(hard.absolutePath, target.absolutePath) }
            .onFailure { runCatching { target.writeBytes(hard.readBytes()) } }
        }
      }
      else -> {
        target.parentFile?.mkdirs()
        target.outputStream().use { out -> tar.copyTo(out) }
      }
    }
    if (!entry.isDirectory) {
      // The modes come from the archive: this is what makes bin/python executable, and a
      // runtime whose interpreter cannot be exec'd fails the guest check with no useful log.
      runCatching { android.system.Os.chmod(target.absolutePath, entry.mode.toInt()) }
    }
  }

  /**
   * Puts the unpacked tree where the guest reads it. The old runtime goes first because a
   * rename cannot merge directories, and a stale library left beside a new one is a worse
   * failure than reading the archive twice.
   */
  private fun promote(): Boolean {
    val runtime = LocalModelPaths.hostRuntimeDir(filesDir)
    runtime.deleteRecursively()
    return stagingDir().renameTo(runtime)
  }

  /** Asks the guest to import what `serve.py` imports: one proot launch, and the only real proof. */
  private suspend fun checkRuntime(log: MutableList<String>): Boolean {
    val imports = "import llama_cpp, numpy, psutil, jinja2, diskcache, typing_extensions; " +
      "print(\"llama-cpp-python\", llama_cpp.__version__)"
    val command = "LD_LIBRARY_PATH=${LocalModelPaths.RUNTIME_GUEST_LIB} " +
      "${LocalModelPaths.RUNTIME_PYTHON} -I -c '$imports'"
    log += "\$ $command"
    val exit = execute(command) { line ->
      log += line
      if (log.size > LOG_LINES * 2) log.subList(0, log.size - LOG_LINES).clear()
    }
    log += "-> exit $exit"
    return exit == 0 && !cancelled
  }

  // ---- disk ------------------------------------------------------------------

  private fun stagingDir(): File = File(LocalModelPaths.hostDir(filesDir), RUNTIME_STAGING_NAME)

  private fun clearStaging() {
    stagingDir().deleteRecursively()
  }

  private fun interpreterOnDisk(): File = interpreterFile(filesDir)

  private fun markerDigest(): String? = markerDigest(filesDir)

  private fun writeMarker(digest: String) {
    runCatching { LocalModelPaths.hostRuntimeReady(filesDir).writeText(digest) }
  }

  private fun clearRuntime() {
    LocalModelPaths.hostRuntimeDir(filesDir).deleteRecursively()
    clearStaging()
  }

  /**
   * Removes the virtualenv an older build compiled in this directory. The app made it, the app
   * owns the parent, and a gigabyte of compiler output nothing reads is the user's storage.
   */
  private fun clearLegacyVenv() {
    File(LocalModelPaths.hostDir(filesDir), LocalModelPaths.LEGACY_VENV_DIR_NAME).deleteRecursively()
  }

  private fun fail(log: List<String>, step: String, reason: String): Boolean {
    _state.value = State.Failed(step, reason, log.takeLast(LOG_LINES))
    return false
  }

  private fun publish(step: String, percent: Int, log: List<String>) {
    _state.value = State.Installing(step, percent, log.takeLast(LOG_LINES))
  }

  private sealed interface Outcome {
    data object Ok : Outcome
    data object Cancelled : Outcome
    data class Error(val step: String, val reason: String) : Outcome
    data class Mismatch(val actual: String) : Outcome
  }

  companion object {
    /** Lines of guest output the screen keeps. The tail is what matters when a step fails. */
    const val LOG_LINES = 60

    /** Directory the archive is unpacked into before it becomes the live runtime. */
    const val RUNTIME_STAGING_NAME = "runtime.installing"

    const val UNPACK_STEP = "Unpacking the model runtime"
    const val CHECK_STEP = "Checking the model runtime"

    private const val BUFFER_BYTES = 256 * 1024

    /** How much archive triggers a progress repaint, so the screen moves without flooding it. */
    private const val PROGRESS_STRIDE = 2L * 1024 * 1024

    fun interpreterFile(filesDir: File): File =
      File(LocalModelPaths.hostRuntimeDir(filesDir), "bin/python")

    /** The digest the last complete install recorded, or null when nothing finished. */
    fun markerDigest(filesDir: File): String? =
      runCatching { LocalModelPaths.hostRuntimeReady(filesDir).readText().trim() }.getOrNull()

    /**
     * Whether the runtime on disk is the one [bundle] describes, as the cheap host-side answer
     * to "can a model run". Reading the catalog costs one small asset open, and asking the
     * guest costs a proot launch — this is the check that decides whether asking is worth it.
     */
    fun isCurrent(filesDir: File, bundle: RuntimeBundle?): Boolean =
      bundle != null && markerDigest(filesDir) == bundle.sha256 && interpreterFile(filesDir).isFile
  }
}
