package com.agentisco.ui.components

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
import com.agentisco.ui.theme.*

enum class DevKeyMode {
  SYMBOLS,
  ACTIONS,
  NAVIGATION
}

@Composable
fun DevKeyboardBar(
  onInsertSymbol: (String) -> Unit,
  onAction: (String) -> Unit = {},
  onCtrlKey: (String) -> Unit = {},
  modifier: Modifier = Modifier
) {
  var currentMode by remember { mutableStateOf(DevKeyMode.SYMBOLS) }

  // CTRL is a modifier, not a character: like a desktop keyboard it never
  // inserts anything on its own — it arms, modifies the NEXT key press, then
  // releases. Sticky (tap once) because touch can't hold a key.
  var ctrlArmed by remember { mutableStateOf(false) }

  // A modifier lingering across a mode switch would be a trap: the next key in
  // the new mode would silently consume it. Drop it on navigation instead.
  LaunchedEffect(currentMode) {
    if (ctrlArmed) ctrlArmed = false
  }

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
        ModeTab("Symbols", currentMode == DevKeyMode.SYMBOLS, "tab_symbols") { currentMode = DevKeyMode.SYMBOLS }
        ModeTab("Actions", currentMode == DevKeyMode.ACTIONS, "tab_actions") { currentMode = DevKeyMode.ACTIONS }
        ModeTab("Navigation", currentMode == DevKeyMode.NAVIGATION, "tab_navigation") { currentMode = DevKeyMode.NAVIGATION }
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
            // CTRL leads the row: it's a modifier that qualifies the next key.
            DevKeyButton(
              text = if (ctrlArmed) "CTRL ●" else "CTRL",
              isModifier = true,
              isArmed = ctrlArmed,
              onClick = { ctrlArmed = !ctrlArmed },
              testTag = "key_ctrl"
            )

            val symbols = listOf(
              "Tab", "{ }", "( )", "[ ]", "=>", ";", "/", "|", "<", ">", "_", "\"", "'", ":", "=", "+", "!", "?", "&", "$", "`", "#", "@",
              "Z", "Y", "S", "F", "A", "C", "V", "X", "D"
            )

            for (sym in symbols) {
              DevKeyButton(
                text = if (ctrlArmed && sym in listOf("Z", "Y", "S", "F", "A", "C", "V", "X", "D")) "$sym" else sym,
                onClick = {
                  val insert = when (sym) {
                    "Tab" -> "  "
                    "{ }" -> "{\n  \n}"
                    "( )" -> "()"
                    "[ ]" -> "[]"
                    else -> sym
                  }
                  if (ctrlArmed) {
                    ctrlArmed = false
                    onCtrlKey(sym)
                  } else {
                    onInsertSymbol(insert)
                  }
                },
                testTag = "key_$sym"
              )
            }
          }
          DevKeyMode.ACTIONS -> {
            val actions = listOf(
              "// Comment", "Indent", "Outdent", "Format", "Duplicate", "Delete Line", "Undo", "Redo", "Save", "Find", "Select All"
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
              if (k == "Ctrl") {
                DevKeyButton(
                  text = if (ctrlArmed) "Ctrl ●" else "Ctrl",
                  isModifier = true,
                  isArmed = ctrlArmed,
                  onClick = { ctrlArmed = !ctrlArmed },
                  testTag = "key_Ctrl"
                )
              } else {
                DevKeyButton(
                  text = k,
                  onClick = {
                    if (ctrlArmed) {
                      ctrlArmed = false
                      onAction("Ctrl+$k")
                    } else {
                      onAction(k)
                    }
                  },
                  testTag = "key_$k"
                )
              }
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
  testTag: String,
  onClick: () -> Unit
) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(if (isSelected) DarkSurfaceHighlight else DarkSurface)
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 2.dp)
      .testTag(testTag)
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
  isModifier: Boolean = false,
  isArmed: Boolean = false,
  onClick: () -> Unit,
  testTag: String
) {
  Box(
    modifier = Modifier
      .height(36.dp)
      .widthIn(min = 36.dp)
      .clip(RoundedCornerShape(6.dp))
      .background(
        when {
          isArmed -> ElectricBlue.copy(alpha = 0.25f)
          isModifier -> DarkSurfaceElevated
          isAction -> DarkSurfaceElevated
          else -> DarkSurfaceHighlight
        }
      )
      .border(
        1.dp,
        if (isArmed) ElectricBlue else DarkBorder,
        RoundedCornerShape(6.dp)
      )
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp)
      .testTag(testTag),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = text,
      color = if (isArmed) ElectricBlueGlow else if (isAction || isModifier) CyanAccent else TextCode,
      fontSize = 13.sp,
      fontWeight = if (isArmed) FontWeight.Bold else FontWeight.Medium,
      fontFamily = FontFamily.Monospace
    )
  }
}
