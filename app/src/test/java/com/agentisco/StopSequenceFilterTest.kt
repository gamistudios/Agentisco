package com.agentisco

import com.agentisco.local.server.StopSequenceFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps a model's markup out of the conversation, tested on the boundaries
 * a real decode produces: a marker whole inside one piece, a marker split across two, and
 * a tail that never resolves because the generation simply ended.
 */
class StopSequenceFilterTest {

  private fun run(filter: StopSequenceFilter, pieces: List<String>): Pair<String, List<String>> {
    val emitted = mutableListOf<String>()
    for (piece in pieces) {
      emitted += filter.feed(piece)
      if (filter.triggered != null) break
    }
    if (filter.triggered == null) emitted += filter.flush()
    return emitted.joinToString("") to emitted.filter { it.isNotEmpty() }
  }

  @Test
  fun `text with no stop sequence in it is delivered whole`() {
    val filter = StopSequenceFilter(listOf("|stop|"))
    val (all, pieces) = run(filter, listOf("Hello ", "wor", "ld"))

    assertEquals("Hello world", all)
    assertNull(filter.triggered)
    // Nothing is held back unless it could still turn out to be a marker.
    assertEquals(listOf("Hello ", "wor", "ld"), pieces)
  }

  @Test
  fun `a marker inside one piece ends the turn and nothing after it is shown`() {
    val filter = StopSequenceFilter(listOf("|stop|"))
    val (all, _) = run(filter, listOf("the answer|stop|and then it kept talking"))

    assertEquals("the answer", all)
    assertEquals("|stop|", filter.triggered)
  }

  @Test
  fun `a marker split across two pieces is still caught`() {
    val filter = StopSequenceFilter(listOf("|stop|"))
    val (all, pieces) = run(filter, listOf("answer |st", "op| more"))

    assertEquals("answer ", all)
    assertEquals("|stop|", filter.triggered)
    // The tail that could have been a marker was held back rather than shown and unshown.
    assertEquals(listOf("answer "), pieces)
  }

  @Test
  fun `a tail that only looked like a marker is real text when the answer ends`() {
    val filter = StopSequenceFilter(listOf("|stop|"))
    val (all, _) = run(filter, listOf("done |st"))

    // Held back while the model might still have been writing a marker, then released:
    // the alternative is a truncated answer for no reason.
    assertEquals("done |st", all)
    assertNull(filter.triggered)
  }

  @Test
  fun `the earliest of several stops wins`() {
    val filter = StopSequenceFilter(listOf("|late|", "|first|"))
    val (all, _) = run(filter, listOf("a|first|b|late|c"))

    assertEquals("a", all)
    assertEquals("|first|", filter.triggered)
  }

  @Test
  fun `a stop that arrives at the very start emits nothing`() {
    val filter = StopSequenceFilter(listOf("|stop|"))
    val (all, pieces) = run(filter, listOf("|stop|tail"))

    assertEquals("", all)
    assertTrue(pieces.isEmpty())
    assertEquals("|stop|", filter.triggered)
  }

  @Test
  fun `an empty stop sequence stops nothing`() {
    val filter = StopSequenceFilter(listOf(""))
    val (all, _) = run(filter, listOf("anything at all"))

    assertEquals("anything at all", all)
    assertNull(filter.triggered)
  }

  @Test
  fun `once a turn has ended, later pieces stay silent`() {
    val filter = StopSequenceFilter(listOf("|stop|"))
    run(filter, listOf("a|stop|"))

    assertEquals("", filter.feed("more"))
    assertEquals("", filter.flush())
  }
}
