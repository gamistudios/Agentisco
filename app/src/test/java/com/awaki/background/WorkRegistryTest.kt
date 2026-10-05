package com.awaki.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry is the one list every background decision reads, so its bookkeeping
 * matters: a record that outlives its work leaves a foreground service on the user's
 * phone, and a canceller that outlives its record is a Stop button that does nothing.
 */
class WorkRegistryTest {

  @Test
  fun `begun work appears in the list with what it is doing`() {
    val registry = WorkRegistry()
    registry.begin(id = "turn-1", kind = WorkKind.AGENT_TURN, label = "Fix the flaky test")

    val live = registry.active.value
    assertEquals(1, live.size)
    assertEquals("turn-1", live[0].id)
    assertEquals(WorkKind.AGENT_TURN, live[0].kind)
    assertEquals("Fix the flaky test", live[0].label)
    assertFalse("a fresh record is not waiting on anyone", live[0].needsAttention)
    assertFalse("nobody offered a way to stop it", live[0].cancellable)
  }

  @Test
  fun `ending the last record empties the registry`() {
    val registry = WorkRegistry()
    registry.begin("a", WorkKind.AGENT_TURN, "a")
    registry.begin("b", WorkKind.TERMINAL, "b")
    registry.end("a")
    assertEquals(listOf("b"), registry.active.value.map { it.id })
    registry.end("b")
    assertTrue("idle must be observably idle", registry.active.value.isEmpty())
    assertFalse(registry.has("b"))
  }

  @Test
  fun `beginning the same id twice replaces rather than doubles`() {
    val registry = WorkRegistry()
    registry.begin("run", WorkKind.TERMINAL, "first command")
    registry.begin("run", WorkKind.TERMINAL, "second command")
    val live = registry.active.value
    assertEquals("one pipeline, one record", 1, live.size)
    assertEquals("second command", live[0].label)
  }

  @Test
  fun `ending an unknown id is a no-op`() {
    val registry = WorkRegistry()
    registry.begin("a", WorkKind.AGENT_TURN, "a")
    registry.end("never-begun")
    assertEquals(1, registry.active.value.size)
  }

  @Test
  fun `progress keeps the label it was given and updates the detail`() {
    val registry = WorkRegistry()
    registry.begin("a", WorkKind.AGENT_TURN, "Agent task")
    registry.setProgress("a", detail = "reading 12 files")
    assertEquals("reading 12 files", registry.active.value[0].detail)
    assertEquals("an empty label must not erase the real one", "Agent task", registry.active.value[0].label)

    registry.setProgress("a", label = "writing a patch", detail = "src/App.kt")
    assertEquals("writing a patch", registry.active.value[0].label)
    assertEquals("src/App.kt", registry.active.value[0].detail)
  }

  @Test
  fun `progress on finished work is dropped instead of resurrected`() {
    val registry = WorkRegistry()
    registry.begin("a", WorkKind.AGENT_TURN, "Agent task")
    registry.end("a")
    registry.setProgress("a", detail = "late event")
    assertTrue(registry.active.value.isEmpty())
  }

  @Test
  fun `attention can be raised and cleared without blanking the line`() {
    val registry = WorkRegistry()
    registry.begin("a", WorkKind.AGENT_TURN, "Agent task", detail = "running tests")
    registry.setAttention("a", needed = true, reason = "Allow rm -rf?")

    val waiting = registry.active.value[0]
    assertTrue(waiting.needsAttention)
    assertEquals("Allow rm -rf?", waiting.detail)

    registry.setAttention("a", needed = false)
    val resumed = registry.active.value[0]
    assertFalse(resumed.needsAttention)
    assertEquals(
      "no reason given keeps the last line rather than showing nothing",
      "Allow rm -rf?",
      resumed.detail
    )

    registry.setProgress("a", detail = "running tests")
    assertEquals("the next status line replaces it", "running tests", registry.active.value[0].detail)
  }

  @Test
  fun `cancel calls the owner that registered and reports whether anything was there`() {
    val registry = WorkRegistry()
    var stopped = 0
    registry.begin("a", WorkKind.AGENT_TURN, "Agent task", canceller = { stopped++ })

    assertTrue(registry.active.value[0].cancellable)
    assertTrue(registry.cancel("a"))
    assertEquals("the record stays until its owner closes it", 1, registry.active.value.size)
    assertEquals(1, stopped)

    registry.end("a")
    assertFalse("nothing left to cancel", registry.cancel("a"))
    assertEquals(1, stopped)
  }

  @Test
  fun `work with no canceller is never offered as stoppable`() {
    val registry = WorkRegistry()
    registry.begin("boot", WorkKind.BOOTSTRAP, "Setting up Linux")
    assertFalse(registry.cancel("boot"))
    assertEquals("the bootstrap keeps running", 1, registry.active.value.size)
  }

  @Test
  fun `a throwing canceller cannot take the notification tap down with it`() {
    val registry = WorkRegistry()
    registry.begin("a", WorkKind.AGENT_TURN, "Agent task", canceller = { error("owner is gone") })
    assertTrue(registry.cancel("a"))
  }

  @Test
  fun `ending work forgets its canceller`() {
    val registry = WorkRegistry()
    var calls = 0
    registry.begin("a", WorkKind.AGENT_TURN, "Agent task", canceller = { calls++ })
    registry.end("a")
    registry.begin("a", WorkKind.AGENT_TURN, "Agent task")

    assertFalse(
      "a re-begun id with no canceller must not inherit the old one",
      registry.cancel("a")
    )
    assertEquals(0, calls)
  }
}
