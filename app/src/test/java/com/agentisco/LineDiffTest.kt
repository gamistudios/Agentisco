package com.agentisco

import com.agentisco.ui.components.DiffKind
import com.agentisco.ui.components.MAX_DIFF_CHARS
import com.agentisco.ui.components.MAX_DIFF_LINES
import com.agentisco.ui.components.computeLineDiff
import com.agentisco.ui.components.diffStats
import com.agentisco.ui.components.isDiffable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineDiffTest {

  @Test
  fun `identical texts produce no diff`() {
    assertEquals(emptyList<Any>(), computeLineDiff("a\nb\nc", "a\nb\nc"))
  }

  @Test
  fun `single line change is localized with line numbers`() {
    val diff = computeLineDiff("one\ntwo\nthree", "one\ntwo changed\nthree")
    assertEquals(
      listOf(
        DiffKind.CONTEXT to 1,
        DiffKind.REMOVED to 2,
        DiffKind.ADDED to 2,
        DiffKind.CONTEXT to 3
      ),
      diff.map { it.kind to (it.oldNo ?: it.newNo) }
    )
  }

  @Test
  fun `pure addition shows every line as added`() {
    val diff = computeLineDiff("", "alpha\nbeta")
    assertEquals(listOf(DiffKind.ADDED, DiffKind.ADDED), diff.map { it.kind })
    assertEquals(listOf(1, 2), diff.map { it.newNo })
    assertEquals(listOf("alpha", "beta"), diff.map { it.text })
  }

  @Test
  fun `long unchanged runs collapse to an elided marker`() {
    val common = (1..30).joinToString("\n") { "line $it" }
    val diff = computeLineDiff(common, common.replace("line 2", "line 2 edited"))
    val elided = diff.filter { it.kind == DiffKind.ELIDED }
    assertEquals(1, elided.size)
    // Context around the change is preserved (2 before + 2 after).
    val changedIdx = diff.indexOfFirst { it.kind == DiffKind.ADDED }
    assertTrue(changedIdx >= 2)
    assertTrue(diff.take(changedIdx).count { it.kind == DiffKind.CONTEXT } <= 2)
  }

  @Test
  fun `stats count added and removed lines`() {
    val diff = computeLineDiff("a\nb", "a\nc\nd")
    assertEquals(2 to 1, diffStats(diff))
  }

  @Test
  fun `replacement in the middle keeps prefix and suffix context`() {
    val old = "head\n" + (1..20).joinToString("\n") { "mid $it" } + "\ntail"
    val new = "head\n" + (1..20).joinToString("\n") { if (it == 10) "MIDDLE" else "mid $it" } + "\ntail"
    val diff = computeLineDiff(old, new)
    assertEquals(1, diff.count { it.kind == DiffKind.ADDED })
    assertEquals(1, diff.count { it.kind == DiffKind.REMOVED })
    // Two context lines, elision, context around the change, elision, two trailing.
    assertEquals(
      listOf(
        DiffKind.CONTEXT, DiffKind.CONTEXT, DiffKind.ELIDED,
        DiffKind.CONTEXT, DiffKind.CONTEXT,
        DiffKind.REMOVED, DiffKind.ADDED,
        DiffKind.CONTEXT, DiffKind.CONTEXT, DiffKind.ELIDED,
        DiffKind.CONTEXT, DiffKind.CONTEXT
      ),
      diff.map { it.kind }
    )
  }

  @Test
  fun `a pair over the ceilings is refused instead of exhausting the heap`() {
    val huge = "x".repeat(MAX_DIFF_CHARS + 1)
    assertFalse(isDiffable(huge, "x"))
    assertFalse(isDiffable("x", huge))
    assertEquals(emptyList<Any>(), computeLineDiff(huge, "x"))
    assertEquals(emptyList<Any>(), computeLineDiff("x", huge))
  }

  @Test
  fun `a pure addition of every line under the ceiling is still diffed`() {
    // The shape that crashed: one side empty, so every line of the other is added.
    val added = (1..MAX_DIFF_LINES).joinToString("\n") { "line $it" }
    assertTrue(isDiffable("", added))
    val diff = computeLineDiff("", added)
    assertEquals(MAX_DIFF_LINES, diff.size)
    assertEquals(listOf(DiffKind.ADDED), diff.map { it.kind }.distinct())
    assertEquals(listOf(1, 2, MAX_DIFF_LINES), listOf(diff.first().newNo, diff[1].newNo, diff.last().newNo))
  }

  @Test
  fun `one line above the ceiling is not diffed`() {
    val added = (1..MAX_DIFF_LINES + 1).joinToString("\n") { "line $it" }
    assertFalse(isDiffable("", added))
    assertEquals(emptyList<Any>(), computeLineDiff("", added))
  }
}
