package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

enum class DevKeyMode {
  SYMBOLS,
  ACTIONS,
  NAVIGATION
}

@Composable
fun DevKeyboardBar(
  onInsertSymbol: (String) -> Unit,
  onAction: (String) -> Unit = {},
  modifier: Modifier = Modifier
) {
  var currentMode by remember { mutableStateOf(DevKeyMode.SYMBOLS) }

  Surface(
    modifier = modifier.fillMaxWidth(),
    color = DarkSurface,
    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle)
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      // Mode selector tabs
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        ModeTab("Symbols", currentMode == DevKeyMode.SYMBOLS) { currentMode = DevKeyMode.SYMBOLS }
        ModeTab("Actions", currentMode == DevKeyMode.ACTIONS) { currentMode = DevKeyMode.ACTIONS }
        ModeTab("Navigation", currentMode == DevKeyMode.NAVIGATION) { currentMode = DevKeyMode.NAVIGATION }
      }

      HorizontalDivider(color = DarkBorderSubtle, thickness = 0.5.dp)

      // Horizontal keys row
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        when (currentMode) {
          DevKeyMode.SYMBOLS -> {
            val symbols = listOf(
              "Tab", "{ }", "( )", "[ ]", "=>", ";", "/", "|", "<", ">", "_", "\"", "'", ":", "=", "+", "-", "!", "?", "&", "$", "`", "#", "@"
            )
            for (sym in symbols) {
              DevKeyButton(
                text = sym,
                onClick = {
                  when (sym) {
                    "Tab" -> onInsertSymbol("  ")
                    "{ }" -> onInsertSymbol("{\n  \n}")
                    "( )" -> onInsertSymbol("()")
                    "[ ]" -> onInsertSymbol("[]")
                    else -> onInsertSymbol(sym)
                  }
                },
                testTag = "key_$sym"
              )
            }
          }
          DevKeyMode.ACTIONS -> {
            val actions = listOf(
              "// Comment", "Indent", "Outdent", "Format", "Duplicate", "Delete Line", "Undo", "Redo"
            )
            for (act in actions) {
              DevKeyButton(
                text = act,
                isAction = true,
                onClick = { onAction(act) },
                testTag = "key_$act"
              )
            }
          }
          DevKeyMode.NAVIGATION -> {
            val navKeys = listOf(
              "◀", "▶", "▲", "▼", "Home", "End", "PageUp", "PageDn", "Ctrl", "Alt", "Shift", "Esc"
            )
            for (k in navKeys) {
              DevKeyButton(
                text = k,
                onClick = { onAction(k) },
                testTag = "key_$k"
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ModeTab(
  name: String,
  isSelected: Boolean,
  onClick: () -> Unit
) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(if (isSelected) DarkSurfaceHighlight else DarkSurface)
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 2.dp)
  ) {
    Text(
      text = name,
      fontSize = 11.sp,
      fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
      color = if (isSelected) ElectricBlueGlow else TextMuted
    )
  }
}

@Composable
private fun DevKeyButton(
  text: String,
  isAction: Boolean = false,
  onClick: () -> Unit,
  testTag: String
) {
  Box(
    modifier = Modifier
      .height(36.dp)
      .widthIn(min = 36.dp)
      .clip(RoundedCornerShape(6.dp))
      .background(if (isAction) DarkSurfaceElevated else DarkSurfaceHighlight)
      .border(1.dp, DarkBorder, RoundedCornerShape(6.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp)
      .testTag(testTag),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = text,
      color = if (isAction) CyanAccent else TextCode,
      fontSize = 13.sp,
      fontWeight = FontWeight.Medium,
      fontFamily = FontFamily.Monospace
    )
  }
}
