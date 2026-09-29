package com.agentisco.background

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The journal is what lets a process that was killed say what it lost, so two
 * properties matter: it must survive being read (consume once, report once), and a
 * record from the process that is still alive must never be reported as a loss.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkJournalTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  // The app's own coordinator republishes the registry into the real journal file as
  // soon as the process starts, so a test that wants to read and write that file
  // needs one of its own.
  private val journalFile = File.createTempFile("scoos-journal", ".json", context.cacheDir)

  private fun newJournal() = WorkJournal(context, journalFile)

  private fun entry(id: String, startedAt: Long, token: String = "old-process") = JournalEntry(
    id = id,
    kind = WorkKind.AGENT_TURN,
    label = "task $id",
    startedAt = startedAt,
    processToken = token
  )

  @Test
  fun `writing live work leaves a file behind and emptying it removes the file`() {
    val journal = newJournal()
    journal.write(listOf(entry("a", 1_000L)))
    assertTrue("the journal has to survive a process death", journalFile.exists())

    journal.write(emptyList())
    assertFalse("idle must not leave a stale record", journalFile.exists())
  }

  @Test
  fun `an earlier process's work is reported newest first and consumed`() {
    val journal = newJournal()
    val now = 1_000_000L
    journal.write(
      listOf(
        entry("older", now - 60_000L),
        entry("newest", now - 1_000L),
        entry("middle", now - 30_000L)
      )
    )

    val lost = journal.takeInterrupted(currentProcessToken = "this-process", now = now)
    assertEquals(listOf("newest", "middle", "older"), lost.map { it.id })
    assertEquals("WorkKind is carried over for the label", WorkKind.AGENT_TURN, lost.first().kind)

    assertFalse("the same interruption is never reported twice", journalFile.exists())
    assertTrue(journal.takeInterrupted("this-process", now).isEmpty())
  }

  @Test
  fun `the current process's own records are running work, not a loss`() {
    val journal = newJournal()
    journal.write(listOf(entry("live", 1_000L, token = "this-process")))
    assertTrue(
      "a live record must not be mistaken for an interruption",
      journal.takeInterrupted("this-process", 2_000L).isEmpty()
    )
  }

  @Test
  fun `work older than the recovery window is dropped instead of offered`() {
    val journal = newJournal()
    val now = 1_000_000_000L
    journal.write(
      listOf(
        entry("recent", now - 60_000L),
        entry("stale", now - BackgroundPolicy.RECOVERY_WINDOW_MS - 60_000L)
      )
    )

    val lost = journal.takeInterrupted("this-process", now)
    assertEquals(listOf("recent"), lost.map { it.id })
  }

  @Test
  fun `a missing or unreadable journal costs a start nothing`() {
    val journal = newJournal()
    assertTrue(journal.takeInterrupted("this-process", 1_000L).isEmpty())

    journalFile.writeText("{ not json at all")
    assertTrue(
      "a corrupt journal must not crash the app",
      journal.takeInterrupted("this-process", 1_000L).isEmpty()
    )
  }

  @Test
  fun `entries written by the registry round-trip through the file`() {
    val journal = newJournal()
    val registry = WorkRegistry()
    registry.begin("turn", WorkKind.AGENT_TURN, "Fix the login flow")
    registry.begin("dl", WorkKind.UPDATE_DOWNLOAD, "Downloading an update")

    journal.write(
      registry.active.value.map {
        JournalEntry(it.id, it.kind, it.label, it.startedAt, "old-process")
      }
    )
    val lost = journal.takeInterrupted("this-process", System.currentTimeMillis())
    assertEquals(setOf("turn", "dl"), lost.map { it.id }.toSet())
    assertEquals("Fix the login flow", lost.first { it.id == "turn" }.label)
  }
}
