package com.awaki

import android.app.Application
import android.os.Build
import com.awaki.data.local.ProviderConfigStore
import com.awaki.data.repository.UpdateRepository
import com.awaki.settings.store.UserPreferencesStore
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class AwakiApplication : Application() {

  /** Durable provider/model configuration + credential storage, created once per process. */
  val providerStore: ProviderConfigStore by lazy { ProviderConfigStore(this) }

  /** User preferences (auto-update toggle etc.), persisted to internal storage. */
  val userPreferencesStore: UserPreferencesStore by lazy { UserPreferencesStore(this) }

  /** Update-API backed checker/downloader (resumable, verified against the asset digest). */
  val updateRepository: UpdateRepository by lazy { UpdateRepository(this) }

  /**
   * Identifies this process start. The work journal records it with every running
   * task, so the next start can tell "still going over here" from "died with the
   * last process" - only the latter is worth interrupting the user about.
   */
  val processToken: String = java.util.UUID.randomUUID().toString()

  /** Everything Awaki is busy on right now, whoever launched it. */
  val workRegistry: com.awaki.background.WorkRegistry by lazy {
    com.awaki.background.WorkRegistry()
  }

  /** Foreground service, wake lock, permission checklist and recovery for [workRegistry]. */
  val backgroundExecution: com.awaki.background.BackgroundExecution by lazy {
    com.awaki.background.BackgroundExecution(
      appContext = this,
      prefs = userPreferencesStore,
      registry = workRegistry,
      journal = com.awaki.background.WorkJournal(this),
      processToken = processToken
    )
  }

  private val localAiDelegate = lazy {
    val repository = com.awaki.data.repository.LocalModelRepository(this)
    com.awaki.local.LocalAiRuntime(
      repository,
      com.awaki.local.runtime.LocalInferenceEngine(repository, pythonModelRuntime())
    )
  }

  /**
   * The Python environment the models run in, as the engine contract the runtime layer knows.
   *
   * Built here rather than in [com.awaki.data.repository.WorkspaceRepository] because a
   * model answers whoever asks, including a conversation started before any workspace is open.
   * The two pieces it needs from the Linux environment are the proot command line and the model
   * directory's guest twin, and both are cheap to name from the app's own files.
   */
  private fun pythonModelRuntime(): com.awaki.local.py.PythonEngine {
    val binaries = com.awaki.workspace.terminal.NativeBinaries(this)
    val bootstrap = com.awaki.workspace.terminal.DebianBootstrap(this, binaries)
    val modelsDir = com.awaki.local.LocalModelPaths.hostDir(filesDir).apply { mkdirs() }
    val modelBind = listOf(modelsDir.absolutePath to com.awaki.local.LocalModelPaths.GUEST_DIR)
    val server = com.awaki.local.py.PythonModelServer(
      filesDir = filesDir,
      readScript = { assets.open(com.awaki.local.py.ServerScript.ASSET_PATH).use { it.readBytes() } },
      commandFor = { port, token ->
        if (!binaries.isComplete() || !bootstrap.isBootstrapped()) null
        else {
          val args = com.awaki.workspace.terminal.ProotArgsBuilder(binaries, bootstrap.rootfsDir, modelBind)
          args.buildCommand(
            com.awaki.local.py.PythonModelServer.guestCommand(port, token, args.guestEnv())
          )
        }
      },
      // The pipe this process keeps open is what the server watches: if the app dies, the
      // pipe closes and the guest interpreter exits with the model's memory.
      start = { argv, environment ->
        ProcessBuilder(argv)
          .redirectErrorStream(true)
          .apply { this.environment().putAll(environment) }
          .start()
      }
    )
    return com.awaki.local.py.PythonEngine(
      filesDir = filesDir,
      server = server,
      environmentReady = { com.awaki.local.py.PythonEnvironment.venvPresent(filesDir) }
    )
  }

  /**
   * The models installed on this device, behind the loopback OpenAI endpoint they are
   * served through. Created lazily: a phone with no installed model never opens a port,
   * and a device whose Python environment is missing never looks like it can answer.
   */
  val localAi: com.awaki.local.LocalAiRuntime by localAiDelegate

  private val memoryScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
  )

  override fun onCreate() {
    super.onCreate()
    installCrashCapture()
    backgroundExecution.start()
  }

  /**
   * Weights and the KV cache are by far the largest thing this process holds, so when the
   * system says memory is critically low they go back — the next request pays one reload,
   * which beats the whole app being killed mid-conversation. Releasing is serialized
   * against the decode, so a turn in flight finishes before the model leaves.
   */
  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (!localAiDelegate.isInitialized()) return
    if (level == TRIM_MEMORY_RUNNING_CRITICAL || level == TRIM_MEMORY_COMPLETE) {
      memoryScope.launch { runCatching { localAi.releaseModel() } }
    }
  }

  /** Returns the previous run's captured crash log, or null if there was none. */
  fun readLastCrashLog(): String? = runCatching {
    val file = File(filesDir, CRASH_FILE)
    if (!file.exists() || file.length() == 0L) null else file.readText()
  }.getOrNull()

  /** Deletes the captured crash log so it stops surfacing on every launch. */
  fun clearLastCrashLog(): Boolean = runCatching {
    File(filesDir, CRASH_FILE).delete()
  }.getOrDefault(false)

  /**
   * Writes every uncaught exception to files/awaki-last-crash.txt before the
   * process dies, so a terminal crash can be diagnosed on-device (the file is
   * displayed on the terminal screen on the next launch).
   */
  private fun installCrashCapture() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      runCatching {
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        File(filesDir, CRASH_FILE).writeText(
          buildString {
            appendLine("Crash at ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            appendLine("Thread: ${thread.name}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT}, targetSdk 28)")
            appendLine()
            append(stack)
          }
        )
      }
      previous?.uncaughtException(thread, throwable)
    }
  }

  companion object {
    const val CRASH_FILE = "awaki-last-crash.txt"
  }
}
