package com.agentisco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentisco.ui.theme.*
import kotlinx.coroutines.launch

// ---- lightweight markdown parsing ----

private sealed class MdBlock {
  data class Paragraph(val lines: List<String>) : MdBlock()
  data class Heading(val level: Int, val text: String) : MdBlock()
  data class Code(val language: String, val code: String) : MdBlock()
  data class ListItems(val ordered: Boolean, val items: List<String>) : MdBlock()
  data class Quote(val lines: List<String>) : MdBlock()
  data object Rule : MdBlock()
}

private fun parseMarkdown(text: String): List<MdBlock> {
  val blocks = mutableListOf<MdBlock>()
  val lines = text.lines()
  var i = 0
  while (i < lines.size) {
    val line = lines[i]
    when {
      line.trimStart().startsWith("```") -> {
        val lang = line.trim().removePrefix("```").trim()
        val code = StringBuilder()
        i++
        while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
          code.appendLine(lines[i])
          i++
        }
        i++ // closing fence
        blocks.add(MdBlock.Code(lang, code.toString().trimEnd('\n')))
      }
      line.isBlank() -> i++
      line.trim() == "---" || line.trim() == "***" -> {
        blocks.add(MdBlock.Rule); i++
      }
      line.startsWith("#") -> {
        val level = line.takeWhile { it == '#' }.length.coerceAtMost(3)
        blocks.add(MdBlock.Heading(level, line.dropWhile { it == '#' }.trim()))
        i++
      }
      Regex("^\\s*([-*+])\\s+").containsMatchIn(line) || Regex("^\\s*\\d+[.)]\\s+").containsMatchIn(line) -> {
        val ordered = Regex("^\\s*\\d+[.)]\\s+").containsMatchIn(line.trimStart().take(4))
        val items = mutableListOf<String>()
        while (i < lines.size) {
          val m = Regex("^\\s*(?:[-*+]|\\d+[.)])\\s+(.*)$").find(lines[i])
          if (m != null) {
            items.add(m.groupValues[1])
            i++
          } else if (items.isNotEmpty() && lines[i].startsWith("  ") && lines[i].isNotBlank()) {
            items[items.size - 1] += " " + lines[i].trim()
            i++
          } else break
        }
        blocks.add(MdBlock.ListItems(ordered, items))
      }
      line.trimStart().startsWith(">") -> {
        val quoted = mutableListOf<String>()
        while (i < lines.size && lines[i].trimStart().startsWith(">")) {
          quoted.add(lines[i].trimStart().removePrefix(">").trim())
          i++
        }
        blocks.add(MdBlock.Quote(quoted))
      }
      else -> {
        val para = mutableListOf<String>()
        while (i < lines.size && lines[i].isNotBlank() &&
          !lines[i].trimStart().startsWith("```") && !lines[i].startsWith("#") &&
          !Regex("^\\s*([-*+]|\\d+[.)])\\s+").containsMatchIn(lines[i])
        ) {
          para.add(lines[i]); i++
        }
        blocks.add(MdBlock.Paragraph(para))
      }
    }
  }
  return blocks
}

private inline fun AnnotatedString.Builder.forEachInline() = Unit

/**
 * Minimal markdown renderer for agent chat: fenced code blocks with copy,
 * headings, lists, quotes, rules, and inline bold/italic/code/links.
 */
@Composable
fun MarkdownText(
  text: String,
  modifier: Modifier = Modifier,
  textColor: androidx.compose.ui.graphics.Color = TextPrimary,
  streaming: Boolean = false
) {
  val blocks = remember(text) { parseMarkdown(text) }
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    blocks.forEach { block ->
      when (block) {
        is MdBlock.Code -> CodeBlockView(block)
        is MdBlock.Heading -> Text(
          text = inlineMarkdown(block.text),
          color = TextPrimary,
          fontSize = when (block.level) {
            1 -> 17.sp; 2 -> 15.sp; else -> 14.sp
          },
          fontWeight = FontWeight.Bold,
          lineHeight = 20.sp
        )
        is MdBlock.Rule -> Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(DarkBorderSubtle)
        )
        is MdBlock.ListItems -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
          block.items.forEachIndexed { index, item ->
            Row {
              Text(
                text = if (block.ordered) "${index + 1}." else "•",
                color = ElectricBlueGlow,
                fontSize = 13.sp,
                modifier = Modifier.width(18.dp)
              )
              Text(
                text = inlineMarkdown(item),
                color = textColor,
                fontSize = 13.sp,
                lineHeight = 18.sp
              )
            }
          }
        }
        is MdBlock.Quote -> Row {
          Box(
            modifier = Modifier
              .width(2.dp)
              .heightIn(min = 8.dp)
              .background(DarkBorder)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = inlineMarkdown(block.lines.joinToString("\n")),
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp
          )
        }
        is MdBlock.Paragraph -> Text(
          text = inlineMarkdown(block.lines.joinToString("\n") + if (streaming) "▍" else ""),
          color = textColor,
          fontSize = 13.sp,
          lineHeight = 18.sp
        )
      }
    }
  }
}

@Composable
private fun CodeBlockView(block: MdBlock.Code) {
  val clipboard = LocalClipboardManager.current
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(DarkBackground)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = block.language.ifBlank { "code" },
        color = TextMuted,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.weight(1f)
      )
      IconButton(
        onClick = {
          clipboard.setText(AnnotatedString(block.code))
        },
        modifier = Modifier.size(24.dp)
      ) {
        Icon(
          Icons.Outlined.ContentCopy,
          contentDescription = "Copy code",
          tint = TextMuted,
          modifier = Modifier.size(13.dp)
        )
      }
    }
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 8.dp, start = 8.dp, end = 8.dp)
        .horizontalScroll(rememberScrollState())
    ) {
      Text(
        text = block.code,
        color = TextCode,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontFamily = FontFamily.Monospace
      )
    }
  }
}

// ---- inline formatting: `code`, **bold**, *italic*, ~~strike~~, [text](url) ----

private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
  var i = 0
  while (i < text.length) {
    when {
      text.startsWith("`", i) -> {
        val end = text.indexOf('`', i + 1)
        if (end > i) {
          withStyle(
            SpanStyle(
              fontFamily = FontFamily.Monospace,
              color = CyanAccent,
              background = DarkBackground
            )
          ) { append(text.substring(i + 1, end)) }
          i = end + 1
        } else {
          append(text[i]); i++
        }
      }
      text.startsWith("**", i) -> {
        val end = text.indexOf("**", i + 2)
        if (end > i) {
          withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary)) {
            append(text.substring(i + 2, end))
          }
          i = end + 2
        } else {
          append(text[i]); i++
        }
      }
      text.startsWith("~~", i) -> {
        val end = text.indexOf("~~", i + 2)
        if (end > i) {
          withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) {
            append(text.substring(i + 2, end))
          }
          i = end + 2
        } else {
          append(text[i]); i++
        }
      }
      text.startsWith("[", i) -> {
        val close = text.indexOf(']', i + 1)
        if (close > i && text.startsWith("(", close + 1)) {
          val urlEnd = text.indexOf(')', close + 2)
          if (urlEnd > close) {
            withStyle(SpanStyle(color = ElectricBlueGlow, fontWeight = FontWeight.Medium)) {
              append(text.substring(i + 1, close))
            }
            i = urlEnd + 1
          } else {
            append(text[i]); i++
          }
        } else {
          append(text[i]); i++
        }
      }
      text.startsWith("*", i) -> {
        val end = text.indexOf('*', i + 1)
        if (end > i + 1 && !text.substring(i, end).contains('\n')) {
          withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            append(text.substring(i + 1, end))
          }
          i = end + 1
        } else {
          append(text[i]); i++
        }
      }
      else -> {
        append(text[i]); i++
      }
    }
  }
}
