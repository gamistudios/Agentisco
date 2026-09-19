package com.agentisco.ui.components

enum class DiffKind { ADDED, REMOVED, CONTEXT, ELIDED }

/** One rendered diff line; numbers are 1-based positions on each side. */
data class DiffLine(
  val kind: DiffKind,
  val text: String,
  val oldNo: Int?,
  val newNo: Int?
)

/** Added and removed line counts. */
fun diffStats(lines: List<DiffLine>): Pair<Int, Int> {
  var added = 0
  var removed = 0
  lines.forEach {
    when (it.kind) {
      DiffKind.ADDED -> added++
      DiffKind.REMOVED -> removed++
      else -> {}
    }
  }
  return added to removed
}

/**
 * Line diff in unified-diff shape: changed runs surrounded by a little
 * context, unchanged runs beyond that collapsed into an ELIDED marker.
 * Trims the common prefix/suffix and runs LCS on the middle; oversized
 * middles fall back to a whole-block replacement to bound the cost.
 * Returns an empty list when the two texts are identical.
 */
fun computeLineDiff(old: String, new: String, contextLines: Int = 2): List<DiffLine> {
  // An empty side is a zero-line file, not one blank line.
  val oldLines = if (old.isEmpty()) emptyList() else old.split("\n")
  val newLines = if (new.isEmpty()) emptyList() else new.split("\n")

  var prefix = 0
  while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) prefix++
  var suffix = 0
  while (
    suffix < oldLines.size - prefix &&
    suffix < newLines.size - prefix &&
    oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
  ) suffix++

  val midOld = oldLines.subList(prefix, oldLines.size - suffix)
  val midNew = newLines.subList(prefix, newLines.size - suffix)
  if (midOld.isEmpty() && midNew.isEmpty()) return emptyList()

  // (isDelete, isAdd, text) for the trimmed middle only.
  val ops: List<Triple<Boolean, Boolean, String>> =
    if (midOld.size.toLong() * midNew.size > LCS_CELL_BUDGET) {
      midOld.map { Triple(true, false, it) } + midNew.map { Triple(false, true, it) }
    } else lcsOps(midOld, midNew)

  val full = mutableListOf<DiffLine>()
  var o = 0
  var n = 0
  repeat(prefix) {
    full.add(DiffLine(DiffKind.CONTEXT, oldLines[o], o + 1, n + 1))
    o++; n++
  }
  ops.forEach { (del, add, text) ->
    when {
      del -> { full.add(DiffLine(DiffKind.REMOVED, text, o + 1, null)); o++ }
      add -> { full.add(DiffLine(DiffKind.ADDED, text, null, n + 1)); n++ }
      else -> { full.add(DiffLine(DiffKind.CONTEXT, text, o + 1, n + 1)); o++; n++ }
    }
  }
  repeat(suffix) {
    full.add(DiffLine(DiffKind.CONTEXT, oldLines[o], o + 1, n + 1))
    o++; n++
  }

  return collapseContextRuns(full, contextLines)
}

/** Keep `contextLines` around each change; replace the rest with one marker. */
private fun collapseContextRuns(lines: List<DiffLine>, contextLines: Int): List<DiffLine> {
  if (lines.none { it.kind == DiffKind.ADDED || it.kind == DiffKind.REMOVED }) return emptyList()
  val out = mutableListOf<DiffLine>()
  var i = 0
  while (i < lines.size) {
    val line = lines[i]
    if (line.kind != DiffKind.CONTEXT) {
      out.add(line)
      i++
      continue
    }
    var j = i
    while (j < lines.size && lines[j].kind == DiffKind.CONTEXT) j++
    val run = lines.subList(i, j)
    if (run.size > contextLines * 2) {
      out.addAll(run.take(contextLines))
      val hidden = run.size - contextLines * 2
      out.add(DiffLine(DiffKind.ELIDED, "$hidden unchanged ${if (hidden == 1) "line" else "lines"}", null, null))
      out.addAll(run.takeLast(contextLines))
    } else {
      out.addAll(run)
    }
    i = j
  }
  return out
}

private const val LCS_CELL_BUDGET = 600L * 600L

/** Classic LCS backtrack over the trimmed middle. */
private fun lcsOps(
  a: List<String>,
  b: List<String>
): List<Triple<Boolean, Boolean, String>> {
  val n = a.size
  val m = b.size
  val dp = Array(n + 1) { IntArray(m + 1) }
  for (i in n - 1 downTo 0) {
    for (j in m - 1 downTo 0) {
      dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
      else maxOf(dp[i + 1][j], dp[i][j + 1])
    }
  }
  val ops = mutableListOf<Triple<Boolean, Boolean, String>>()
  var i = 0
  var j = 0
  while (i < n && j < m) {
    when {
      a[i] == b[j] -> { ops.add(Triple(false, false, a[i])); i++; j++ }
      dp[i + 1][j] >= dp[i][j + 1] -> { ops.add(Triple(true, false, a[i])); i++ }
      else -> { ops.add(Triple(false, true, b[j])); j++ }
    }
  }
  while (i < n) { ops.add(Triple(true, false, a[i])); i++ }
  while (j < m) { ops.add(Triple(false, true, b[j])); j++ }
  return ops
}
