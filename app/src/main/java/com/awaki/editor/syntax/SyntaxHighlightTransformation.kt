package com.awaki.editor.syntax

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.awaki.ui.theme.DarkOnWarning
import com.awaki.ui.theme.DarkWarning

/**
 * VisualTransformation that applies real-time programming-language-aware
 * syntax highlighting and search match highlighting to BasicTextField during typing and editing.
 */
class SyntaxHighlightTransformation(
  private val language: Language,
  private val theme: SyntaxTheme,
  private val findMatches: List<IntRange> = emptyList(),
  private val activeMatchIndex: Int = -1
) : VisualTransformation {

  override fun filter(text: AnnotatedString): TransformedText {
    if (text.isEmpty()) {
      return TransformedText(text, OffsetMapping.Identity)
    }

    val highlighted = if (language == Language.PLAIN_TEXT) {
      text
    } else {
      SyntaxHighlighter.highlightCode(text.text, language, theme)
    }

    if (findMatches.isEmpty()) {
      return TransformedText(highlighted, OffsetMapping.Identity)
    }

    // Apply search highlight overlays on top of syntax highlighting
    val builder = AnnotatedString.Builder(highlighted)
    val textLen = text.length

    for ((idx, range) in findMatches.withIndex()) {
      val start = range.first.coerceIn(0, textLen)
      val end = (range.last + 1).coerceIn(0, textLen)
      if (start < end) {
        val isCurrent = idx == activeMatchIndex
        val matchBg = if (isCurrent) DarkWarning else DarkWarning.copy(alpha = 0.4f)
        val matchFg = if (isCurrent) DarkOnWarning else Color.White
        builder.addStyle(
          SpanStyle(
            background = matchBg,
            color = matchFg,
            fontWeight = FontWeight.Bold
          ),
          start,
          end
        )
      }
    }

    return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
  }
}
