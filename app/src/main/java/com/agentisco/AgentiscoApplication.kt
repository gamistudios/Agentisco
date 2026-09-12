package com.agentisco

import android.app.Application
import android.os.Build
import com.agentisco.data.local.ProviderConfigStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class AgentiscoApplication : Application() {

  /** Durable provider/model configuration + credential storage, created once per process. */
  val providerStore: ProviderConfigStore by lazy { ProviderConfigStore(this) }

  override fun onCreate() {
    super.onCreate()
    installCrashCapture()
  }

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
