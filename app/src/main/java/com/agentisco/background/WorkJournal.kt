package com.agentisco.background

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A record of work that was live when a process ended.
 *
 * [processToken] is per process start, so an entry written by this same process is
 * still-running work rather than an interruption - only a token from an earlier
 * process means something died.
 */
@Serializable
data class JournalEntry(
  val id: String,
  val kind: WorkKind,
  val label: String,
  val startedAt: Long,
  val processToken: String
)

/**
 * A one-file journal of live work, written whenever the registry changes.
 *
 * It exists because Android can kill a backgrounded process at any moment, and
 * without a trace of what it was doing the app comes back with no idea that a task
 * was lost. Nothing here pretends the work can be continued in-process: it is what
 * lets the next start offer it back.
 */
class WorkJournal(context: Context, private val file: File = File(context.filesDir, FILE)) {

  private val json = Json { ignoreUnknownKeys = true }

  fun write(entries: List<JournalEntry>) {
    runCatching {
      if (entries.isEmpty()) {
        file.delete()
        return@runCatching
      }
      file.writeText(json.encodeToString(entries))
    }
  }

  /**
   * Consumes the journal: entries left by an earlier process, still inside the
   * recovery window, newest first. Anything older is dropped rather than offered,
   * and the file is cleared so the same interruption is never reported twice.
   */
  fun takeInterrupted(currentProcessToken: String, now: Long): List<JournalEntry> {
    val entries = runCatching {
      if (!file.exists()) emptyList() else json.decodeFromString<List<JournalEntry>>(file.readText())
    }.getOrDefault(emptyList())
    if (entries.isEmpty()) return emptyList()
    file.delete()
    return entries
      .filter { it.processToken != currentProcessToken }
      .filter { BackgroundPolicy.isRecoverable(it.startedAt, now) }
      .sortedByDescending { it.startedAt }
  }

  private companion object {
    const val FILE = "background_work_journal.json"
  }
}
