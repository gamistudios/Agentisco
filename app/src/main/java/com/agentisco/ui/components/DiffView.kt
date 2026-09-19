package com.agentisco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.agentisco.ui.theme.DarkBackground
import com.agentisco.ui.theme.DangerRed
import com.agentisco.ui.theme.TerminalGreen
import com.agentisco.ui.theme.TextMuted
import com.agentisco.ui.theme.TextSecondary

/**
 * Git-style diff table: fixed columns for old/new line numbers and a +/-
 * sign, colored per line, monospace, no wrapping (scrolls horizontally).
 */
@Composable
fun DiffTable(lines: List<DiffLine>, modifier: Modifier = Modifier) {
  val annotated = remember(lines) { buildDiffAnnotated(lines) }
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(6.dp))
      .background(DarkBackground)
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

private fun buildDiffAnnotated(lines: List<DiffLine>) = buildAnnotatedString {
  lines.forEachIndexed { index, line ->
    val (fg, bg, sign) = when (line.kind) {
      DiffKind.ADDED -> Triple(TerminalGreen, TerminalGreen.copy(alpha = 0.12f), "+")
      DiffKind.REMOVED -> Triple(DangerRed, DangerRed.copy(alpha = 0.12f), "-")
      DiffKind.CONTEXT -> Triple(TextSecondary, Color.Transparent, " ")
      DiffKind.ELIDED -> Triple(TextMuted, Color.Transparent, "")
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
