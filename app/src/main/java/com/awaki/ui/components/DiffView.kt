package com.awaki.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awaki.ui.theme.AwakiTheme

/**
 * Git-style diff table: fixed columns for old/new line numbers and a +/-
 * sign, colored per line, monospace, no wrapping (scrolls horizontally).
 */
@Composable
fun DiffTable(lines: List<DiffLine>, modifier: Modifier = Modifier) {
  val ink = diffInk()
  val annotated = remember(lines, ink) { buildDiffAnnotated(lines, ink) }
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(6.dp))
      .background(MaterialTheme.colorScheme.background)
      .horizontalScroll(rememberScrollState())
      .padding(vertical = 6.dp, horizontal = 8.dp)
  ) {
    Text(
      text = annotated,
      fontFamily = FontFamily.Monospace,
      fontSize = 10.sp,
      softWrap = false,
      overflow = TextOverflow.Visible
    )
  }
}

/** The four inks a diff is painted with, read from whatever theme is showing. */
@Immutable
private data class DiffInk(
  val added: Color,
  val removed: Color,
  val context: Color,
  val muted: Color
)

@Composable
private fun diffInk() = DiffInk(
  added = AwakiTheme.extra.success,
  removed = MaterialTheme.colorScheme.error,
  context = MaterialTheme.colorScheme.onSurfaceVariant,
  muted = AwakiTheme.extra.textMuted
)

private fun buildDiffAnnotated(lines: List<DiffLine>, ink: DiffInk) = buildAnnotatedString {
  lines.forEachIndexed { index, line ->
    val (fg, bg, sign) = when (line.kind) {
      DiffKind.ADDED -> Triple(ink.added, ink.added.copy(alpha = 0.12f), "+")
      DiffKind.REMOVED -> Triple(ink.removed, ink.removed.copy(alpha = 0.12f), "-")
      DiffKind.CONTEXT -> Triple(ink.context, Color.Transparent, " ")
      DiffKind.ELIDED -> Triple(ink.muted, Color.Transparent, "")
    }
    withStyle(SpanStyle(color = fg, background = bg)) {
      if (line.kind == DiffKind.ELIDED) {
        append("... ${line.text}")
      } else {
        append(line.oldNo?.toString()?.padStart(4) ?: "    ")
        append(' ')
        append(line.newNo?.toString()?.padStart(4) ?: "    ")
        append(" $sign ${line.text}")
      }
    }
    if (index != lines.lastIndex) append('\n')
  }
}
