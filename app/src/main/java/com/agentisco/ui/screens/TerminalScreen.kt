package com.agentisco.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentisco.core.model.AppDestination
import com.agentisco.data.model.TerminalLine
import com.agentisco.data.model.TerminalLineType
import com.agentisco.data.model.TerminalSession
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.theme.*
import kotlinx.coroutines.launch

// Termux-style extra keys row: two fixed rows of six keys.
// ↑/↓ navigate command history, ←/→/HOME/END move the cursor, PGUP/PGDN scroll the console.
private val TermuxKeyRows = listOf(
  listOf("ESC", "/", "HOME", "↑", "END", "PGUP"),
  listOf("TAB", "-", "←", "↓", "→", "PGDN")
)

@Composable
fun TerminalScreen(
  viewModel: WorkspaceViewModel,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  val sessions by viewModel.terminalSessions.collectAsState()
  val activeSessionId by viewModel.activeTerminalSessionId.collectAsState()
  val history by viewModel.terminalCommandHistory.collectAsState()

  var showNewSessionDialog by remember { mutableStateOf(false) }
  var showToolsDialog by remember { mutableStateOf(false) }
  var newSessionNameInput by remember { mutableStateOf("") }

  // Inline prompt buffer: the live command being typed at the bottom of the scrollback.
  var inputTf by remember { mutableStateOf(TextFieldValue("")) }
  var inputFocused by remember { mutableStateOf(false) }
  // History navigation: null = live prompt (not browsing), otherwise index into history.
  var historyIndex by remember { mutableStateOf<Int?>(null) }
  var draftInput by remember { mutableStateOf("") }

  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val focusRequester = remember { FocusRequester() }

  val currentSession = remember(sessions, activeSessionId) {
    sessions.find { it.id == activeSessionId } ?: sessions.first()
  }
  // The prompt row is replaced by a status row while a command runs, so the focus
  // requester may be detached — read running state through rememberUpdatedState.
  val sessionRunning by rememberUpdatedState(currentSession.isRunning)

  val runningCommand = currentSession.lines.lastOrNull { it.type == TerminalLineType.COMMAND }
    ?.text?.removePrefix("$ ") ?: ""

  fun setInputText(text: String) {
    inputTf = TextFieldValue(text, TextRange(text.length))
  }

  fun submitCommand(raw: String) {
    val cmd = raw.trim()
    inputTf = TextFieldValue("")
    historyIndex = null
    draftInput = ""
    if (cmd.isNotEmpty()) viewModel.executeTerminalCommand(cmd)
  }

  fun onInputValue(value: TextFieldValue) {
    val nl = value.text.indexOf('\n')
    if (nl < 0) {
      inputTf = value
      return
    }
    // Enter submits the line up to the first newline; anything after it
    // (e.g. from a paste) stays in the buffer as the next draft.
    val before = value.text.substring(0, nl).trimEnd()
    val rest = value.text.substring(nl + 1)
    val nextNl = rest.indexOf('\n')
    val remainder = if (nextNl >= 0) rest.substring(0, nextNl) else rest
    submitCommand(before)
    if (remainder.isNotEmpty()) setInputText(remainder)
  }

  fun insertAtCursor(insertion: String) {
    val sel = inputTf.selection
    val newText = buildString {
      append(inputTf.text, 0, sel.min)
      append(insertion)
      append(inputTf.text, sel.max, inputTf.text.length)
    }
    inputTf = TextFieldValue(newText, TextRange(sel.min + insertion.length))
  }

  fun moveCursor(delta: Int) {
    val pos = (inputTf.selection.min + delta).coerceIn(0, inputTf.text.length)
    inputTf = TextFieldValue(inputTf.text, TextRange(pos))
  }

  fun setCursor(pos: Int) {
    inputTf = TextFieldValue(inputTf.text, TextRange(pos.coerceIn(0, inputTf.text.length)))
  }

  fun navigateHistory(up: Boolean) {
    if (history.isEmpty()) return
    if (up) {
      val idx = ((historyIndex ?: history.size) - 1).coerceAtLeast(0)
      if (idx != historyIndex) {
        if (historyIndex == null) draftInput = inputTf.text
        historyIndex = idx
        setInputText(history[idx])
      }
    } else {
      val idx = historyIndex ?: return
      if (idx >= history.lastIndex) {
        historyIndex = null
        setInputText(draftInput)
      } else {
        historyIndex = idx + 1
        setInputText(history[idx + 1])
      }
    }
  }

  fun scrollPage(up: Boolean) {
    val viewport = listState.layoutInfo.viewportSize.height
    if (viewport <= 0) return
    // Reverse layout: scrolling forward (positive) moves toward older lines, visually up.
    val page = viewport * 0.75f
    scope.launch { listState.animateScrollBy(if (up) page else -page) }
  }

  fun onExtraKey(key: String) {
    when (key) {
      "ESC" -> { inputTf = TextFieldValue(""); historyIndex = null }
      "TAB" -> insertAtCursor("  ")
      "/", "-" -> insertAtCursor(key)
      "HOME" -> setCursor(0)
      "END" -> setCursor(inputTf.text.length)
      "↑" -> navigateHistory(up = true)
      "↓" -> navigateHistory(up = false)
      "←" -> moveCursor(-1)
      "→" -> moveCursor(1)
      "PGUP" -> scrollPage(up = true)
      "PGDN" -> scrollPage(up = false)
    }
  }

  if (showNewSessionDialog) {
    AlertDialog(
      onDismissRequest = { showNewSessionDialog = false },
      title = {
        Text("New Terminal Session", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("Select an environment preset or type a custom name:", color = TextSecondary, fontSize = 12.sp)
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            listOf("bash", "git", "node", "dev-server", "termux").forEach { preset ->
              Surface(
                onClick = { newSessionNameInput = preset },
                shape = RoundedCornerShape(6.dp),
                color = if (newSessionNameInput == preset) ElectricBlue.copy(alpha = 0.2f) else DarkSurfaceElevated,
                border = BorderStroke(1.dp, if (newSessionNameInput == preset) ElectricBlue else DarkBorderSubtle)
              ) {
                Text(
                  text = preset,
                  color = if (newSessionNameInput == preset) ElectricBlueGlow else TextPrimary,
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Monospace,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
              }
            }
          }
          OutlinedTextField(
            value = newSessionNameInput,
            onValueChange = { newSessionNameInput = it },
            placeholder = { Text("e.g. bash-2, build, test", color = TextMuted, fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("input_new_session_name"),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = ElectricBlue,
              unfocusedBorderColor = DarkBorderSubtle,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary
            )
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val name = newSessionNameInput.trim().ifEmpty { "bash-${sessions.size + 1}" }
            viewModel.createTerminalSession(name)
            newSessionNameInput = ""
            showNewSessionDialog = false
          },
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
          modifier = Modifier.testTag("btn_confirm_new_session")
        ) {
          Text("Create Session", color = Color.White)
        }
      },
      dismissButton = {
        TextButton(onClick = { showNewSessionDialog = false }) {
          Text("Cancel", color = TextMuted)
        }
      },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp)
    )
  }

  if (showToolsDialog) {
    AlertDialog(
      onDismissRequest = { showToolsDialog = false },
      title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.Extension, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("Linux Tools & Termux Bridge", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
      },
      text = {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Text(
            "Agentisco provides a built-in Linux subsystem supporting packages, Git, SSH, and Termux integration.",
            color = TextSecondary,
            fontSize = 12.sp
          )

          // Tool cards
          listOf(
            Triple("Git & Diff Engine", "v2.45.2 (Myers Diff)", true),
            Triple("OpenSSH & ssh-keygen", "v9.7p1 (Keygen + Client)", true),
            Triple("Agentisco Package Manager", "apt / pkg v2.4", true),
            Triple("Termux Environment Bridge", "com.termux integration", true),
            Triple("Node.js Runtime", "v20.14.0 (JavaScript)", true),
            Triple("Python 3 Interpreter", "v3.12.3 (Python CLI)", true)
          ).forEach { (title, subtitle, installed) ->
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = DarkSurfaceElevated,
              border = BorderStroke(1.dp, DarkBorderSubtle),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                  Text(subtitle, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                Surface(
                  shape = RoundedCornerShape(4.dp),
                  color = TerminalGreen.copy(alpha = 0.15f),
                  border = BorderStroke(1.dp, TerminalGreen.copy(alpha = 0.5f))
                ) {
                  Text(
                    text = "Active",
                    color = TerminalGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                  )
                }
              }
            }
          }

          Text("Quick Actions (Click to Run):", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            listOf("git remote -v", "git status", "neofetch", "apt update", "ssh-keygen", "termux-bridge").forEach { actionCmd ->
              Surface(
                onClick = {
                  viewModel.executeTerminalCommand(actionCmd)
                  showToolsDialog = false
                },
                shape = RoundedCornerShape(6.dp),
                color = DarkSurfaceHighlight,
                border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f))
              ) {
                Text(
                  text = actionCmd,
                  color = CyanAccent,
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Monospace,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
              }
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = { showToolsDialog = false },
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
        ) {
          Text("Done", color = Color.White)
        }
      },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp)
    )
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
      // Push the console and extra keys row above the soft keyboard.
      .imePadding()
  ) {
    // Terminal Top Header & Session Switcher Tabs (Section 15)
    Surface(
      modifier = Modifier.fillMaxWidth(),
      color = DarkSurface,
      border = BorderStroke(1.dp, DarkBorderSubtle)
    ) {
      Column {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f, fill = false)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(
                modifier = Modifier
                  .size(8.dp)
                  .clip(CircleShape)
                  .background(TerminalGreen)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "Terminal Environment",
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
              )
            }
            Text(
              text = currentSession.currentDir,
              color = TextMuted,
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }

          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Linux Tools & Termux Button
            Surface(
              onClick = { showToolsDialog = true },
              shape = RoundedCornerShape(6.dp),
              color = DarkSurfaceHighlight,
              border = BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.5f))
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(Icons.Default.Extension, contentDescription = "Linux Tools", tint = ElectricBlueGlow, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Linux Tools", color = ElectricBlueGlow, fontSize = 11.sp, fontWeight = FontWeight.Medium)
              }
            }

            // New Session Button
            IconButton(
              onClick = { showNewSessionDialog = true },
              modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(DarkSurfaceElevated)
                .testTag("btn_new_terminal_session")
            ) {
              Icon(Icons.Default.Add, contentDescription = "New Session", tint = ElectricBlueGlow, modifier = Modifier.size(16.dp))
            }
          }
        }

        // Sessions Tab Row with close buttons and New Tab chip
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          sessions.forEach { s ->
            val isSelected = s.id == activeSessionId
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (isSelected) DarkSurfaceHighlight else DarkBackground)
                .border(1.dp, if (isSelected) ElectricBlue else DarkBorderSubtle, RoundedCornerShape(6.dp))
                .clickable { viewModel.selectTerminalSession(s.id) }
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("tab_session_${s.name}")
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                  modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) TerminalGreen else TextMuted)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = s.name,
                  color = if (isSelected) TextPrimary else TextSecondary,
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
                Spacer(modifier = Modifier.width(6.dp))
                // Close Tab Button
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "Close session",
                  tint = if (isSelected) TextSecondary else TextMuted,
                  modifier = Modifier
                    .size(13.dp)
                    .clip(CircleShape)
                    .clickable { viewModel.closeTerminalSession(s.id) }
                    .testTag("btn_close_session_${s.name}")
                )
              }
            }
          }

          // "+" New Tab Chip
          Surface(
            onClick = { showNewSessionDialog = true },
            shape = RoundedCornerShape(6.dp),
            color = DarkSurfaceElevated,
            border = BorderStroke(1.dp, DarkBorderSubtle)
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(Icons.Default.Add, contentDescription = "New Tab", tint = TextSecondary, modifier = Modifier.size(12.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("New Tab", color = TextSecondary, fontSize = 11.sp)
            }
          }
        }
      }
    }

    // Terminal console. Reverse layout keeps the live prompt line anchored at the
    // bottom of the scrollback (like a real terminal): newest output appears right
    // above it, the view follows output while at the bottom, and scrolling up to
    // read older lines stays put as new output arrives.
    val lines = currentSession.lines
    LazyColumn(
      state = listState,
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .background(DarkBackground)
        // Tapping anywhere in the console focuses the prompt, like Termux.
        .pointerInput(Unit) {
          detectTapGestures(onTap = { if (!sessionRunning) focusRequester.requestFocus() })
        },
      reverseLayout = true,
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
      // Item index 0 of a reverse list renders at the BOTTOM: the live prompt line.
      item(key = "prompt") {
        if (currentSession.isRunning) {
          TerminalRunningRow(
            commandText = runningCommand,
            onInterrupt = { viewModel.interruptTerminal(currentSession.id) }
          )
        } else {
          TerminalPromptRow(
            currentDir = currentSession.currentDir,
            value = inputTf,
            onValueChange = ::onInputValue,
            focused = inputFocused,
            onFocusChanged = { inputFocused = it },
            focusRequester = focusRequester
          )
        }
      }
      // Older output grows upward above the prompt; keys are the stable line index
      // so the anchor survives as new lines stream in.
      itemsIndexed(lines.asReversed(), key = { i, _ -> lines.size - 1 - i }) { _, line ->
        TerminalLineView(line = line)
      }
    }

    // Termux-style extra keys row above the keyboard
    TerminalExtraKeysGrid(onKey = ::onExtraKey)
  }
}

@Composable
private fun TerminalPromptRow(
  currentDir: String,
  value: TextFieldValue,
  onValueChange: (TextFieldValue) -> Unit,
  focused: Boolean,
  onFocusChanged: (Boolean) -> Unit,
  focusRequester: FocusRequester,
  modifier: Modifier = Modifier
) {
  Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    Text(
      text = "$currentDir ",
      color = TerminalGreen,
      fontSize = 13.sp,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      lineHeight = 18.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.widthIn(max = 180.dp)
    )
    Text(
      text = "$ ",
      color = ElectricBlueGlow,
      fontSize = 13.sp,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      lineHeight = 18.sp
    )
    Box(modifier = Modifier.weight(1f)) {
      if (value.text.isEmpty() && !focused) {
        Text(
          text = "tap to type a command…",
          color = TextMuted,
          fontSize = 12.sp,
          fontFamily = FontFamily.Monospace,
          lineHeight = 18.sp
        )
      }
      BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(
          fontFamily = FontFamily.Monospace,
          fontSize = 13.sp,
          lineHeight = 18.sp,
          color = TextCode
        ),
        cursorBrush = SolidColor(TerminalGreen),
        keyboardOptions = KeyboardOptions(
          capitalization = KeyboardCapitalization.None,
          autoCorrectEnabled = false,
          keyboardType = KeyboardType.Ascii
        ),
        modifier = Modifier
          .fillMaxWidth()
          .focusRequester(focusRequester)
          .onFocusChanged { onFocusChanged(it.isFocused) }
          .testTag("terminal_input")
      )
    }
  }
}

@Composable
private fun TerminalRunningRow(
  commandText: String,
  onInterrupt: () -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Box(
      modifier = Modifier
        .size(8.dp)
        .clip(CircleShape)
        .background(WarningAmber)
    )
    Text(
      text = "running $commandText",
      color = TextSecondary,
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f)
    )
    Surface(
      onClick = onInterrupt,
      shape = RoundedCornerShape(6.dp),
      color = DangerRed.copy(alpha = 0.15f),
      border = BorderStroke(1.dp, DangerRed),
      modifier = Modifier.testTag("btn_interrupt_terminal")
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        Icon(Icons.Default.Stop, contentDescription = null, tint = DangerRed, modifier = Modifier.size(13.dp))
        Text("STOP", color = DangerRed, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
      }
    }
  }
}

@Composable
private fun TerminalExtraKeysGrid(
  onKey: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(DarkSurface)
      .padding(horizontal = 4.dp, vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    TermuxKeyRows.forEach { row ->
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        row.forEach { key ->
          Box(
            modifier = Modifier
              .weight(1f)
              .fillMaxHeight()
              .clip(RoundedCornerShape(6.dp))
              .background(DarkBackground)
              .border(1.dp, DarkBorderSubtle, RoundedCornerShape(6.dp))
              .clickable { onKey(key) }
              .testTag("term_key_$key"),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = key,
              color = TextCode,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Medium
            )
          }
        }
      }
    }
  }
}

@Composable
private fun TerminalLineView(line: TerminalLine) {
  val color = when (line.type) {
    TerminalLineType.COMMAND -> ElectricBlueGlow
    TerminalLineType.SUCCESS -> TerminalGreen
    TerminalLineType.STDERR -> DangerRed
    TerminalLineType.INFO -> CyanAccent
    TerminalLineType.STDOUT -> TextCode
  }

  Text(
    text = line.text,
    color = color,
    fontSize = 12.sp,
    fontFamily = FontFamily.Monospace,
    lineHeight = 17.sp
  )
}
