package com.agentisco

import android.app.Application
import android.os.Build
import com.agentisco.data.local.ProviderConfigStore
import com.agentisco.data.repository.UpdateRepository
import com.agentisco.settings.store.UserPreferencesStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class AgentiscoApplication : Application() {

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

  /** Everything Agentisco is busy on right now, whoever launched it. */
  val workRegistry: com.agentisco.background.WorkRegistry by lazy {
    com.agentisco.background.WorkRegistry()
  }

  /** Foreground service, wake lock, permission checklist and recovery for [workRegistry]. */
  val backgroundExecution: com.agentisco.background.BackgroundExecution by lazy {
    com.agentisco.background.BackgroundExecution(
      appContext = this,
      prefs = userPreferencesStore,
      registry = workRegistry,
      journal = com.agentisco.background.WorkJournal(this),
      processToken = processToken
    )
  }

  override fun onCreate() {
    super.onCreate()
    installCrashCapture()
    backgroundExecution.start()
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
   * Writes every uncaught exception to files/scoos-last-crash.txt before the
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
    const val CRASH_FILE = "scoos-last-crash.txt"
  }
}
