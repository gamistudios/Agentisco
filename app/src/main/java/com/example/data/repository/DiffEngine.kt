package com.example.data.repository

import com.example.data.model.DiffLine
import com.example.data.model.DiffLineType
import com.example.data.model.FileDiff

object DiffEngine {

  fun computeDiff(filePath: String, oldContent: String, newContent: String): FileDiff? {
    if (oldContent == newContent) return null

    val oldLines = if (oldContent.isEmpty()) emptyList() else oldContent.lines()
    val newLines = if (newContent.isEmpty()) emptyList() else newContent.lines()

    val diffLines = mutableListOf<DiffLine>()
    var additions = 0
    var deletions = 0

    // Compute LCS matrix for line differences
    val lcs = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }
    for (i in 0 until oldLines.size) {
      for (j in 0 until newLines.size) {
        if (oldLines[i] == newLines[j]) {
          lcs[i + 1][j + 1] = lcs[i][j] + 1
        } else {
          lcs[i + 1][j + 1] = maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
      }
    }

    // Backtrack to build the diff
    var i = oldLines.size
    var j = newLines.size
    val tempLines = mutableListOf<DiffLine>()

    while (i > 0 || j > 0) {
      when {
        i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1] -> {
          tempLines.add(
            DiffLine(
              type = DiffLineType.UNCHANGED,
              oldLineNo = i,
              newLineNo = j,
              text = "  ${oldLines[i - 1]}"
            )
          )
          i--
          j--
        }
        j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j]) -> {
          tempLines.add(
            DiffLine(
              type = DiffLineType.ADDED,
              oldLineNo = null,
              newLineNo = j,
              text = "+ ${newLines[j - 1]}"
            )
          )
          additions++
          j--
        }
        i > 0 && (j == 0 || lcs[i][j - 1] < lcs[i - 1][j]) -> {
          tempLines.add(
            DiffLine(
              type = DiffLineType.REMOVED,
              oldLineNo = i,
              newLineNo = null,
              text = "- ${oldLines[i - 1]}"
            )
          )
          deletions++
          i--
        }
      }
    }

    tempLines.reverse()

    // To prevent giant screens with 10,000 unchanged lines, compress far-away unchanged context lines
    var lastAddedOrRemoved = -1
    val outputLines = mutableListOf<DiffLine>()
    for (k in tempLines.indices) {
      val item = tempLines[k]
      val isNear = (k - 3..k + 3).any { idx ->
        idx in tempLines.indices && tempLines[idx].type != DiffLineType.UNCHANGED
      }
      if (isNear || tempLines.size < 50) {
        outputLines.add(item)
      } else if (outputLines.lastOrNull()?.text != "  ...") {
        outputLines.add(DiffLine(DiffLineType.UNCHANGED, null, null, "  ..."))
      }
    }

    return FileDiff(
      filePath = filePath,
      additionsCount = additions,
      deletionsCount = deletions,
      lines = if (outputLines.isNotEmpty()) outputLines else tempLines,
      originalContent = oldContent,
      newContent = newContent
    )
  }
}
