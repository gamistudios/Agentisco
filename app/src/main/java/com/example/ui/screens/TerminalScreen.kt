package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppDestination
import com.example.data.model.TerminalLine
import com.example.data.model.TerminalLineType
import com.example.data.model.TerminalSession
import com.example.ui.WorkspaceViewModel
import com.example.ui.theme.*

@Composable
fun TerminalScreen(
  viewModel: WorkspaceViewModel,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  val sessions by viewModel.terminalSessions.collectAsState()
  val activeSessionId by viewModel.activeTerminalSessionId.collectAsState()
  val activeProject by viewModel.activeProject.collectAsState()

  var inputCommand by remember { mutableStateOf("") }
  var showNewSessionDialog by remember { mutableStateOf(false) }
  var showToolsDialog by remember { mutableStateOf(false) }
  var newSessionNameInput by remember { mutableStateOf("") }

  val listState = rememberLazyListState()

  val currentSession = remember(sessions, activeSessionId) {
    sessions.find { it.id == activeSessionId } ?: sessions.first()
  }

  LaunchedEffect(currentSession.lines.size) {
    if (currentSession.lines.isNotEmpty()) {
      listState.animateScrollToItem(currentSession.lines.size - 1)
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
            "ScoOS provides a built-in Linux subsystem supporting packages, Git, SSH, and Termux integration.",
            color = TextSecondary,
            fontSize = 12.sp
          )

          // Tool cards
          listOf(
            Triple("Git & Diff Engine", "v2.45.2 (Myers Diff)", true),
            Triple("OpenSSH & ssh-keygen", "v9.7p1 (Keygen + Client)", true),
            Triple("ScoOS Package Manager", "apt / pkg v2.4", true),
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
      // Push the input row and dev keybar up above the soft keyboard instead of
      // letting the keyboard overlap/hide them at the bottom of the screen.
      .imePadding()
  ) {
    // Terminal Top Header & Session Switcher Tabs (Section 15)
    Surface(
      modifier = Modifier.fillMaxWidth(),
      color = DarkSurface,
      border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle)
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

    // Terminal Output Console
    LazyColumn(
      state = listState,
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .background(DarkBackground)
        .padding(horizontal = 14.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
      items(currentSession.lines) { line ->
        TerminalLineView(line = line)
      }
    }

    // Quick Command Bar
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .background(DarkSurfaceElevated)
        .padding(horizontal = 8.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      QuickCommandChip("git remote -v") { viewModel.executeTerminalCommand("git remote -v") }
      QuickCommandChip("git status") { viewModel.executeTerminalCommand("git status") }
      QuickCommandChip("neofetch") { viewModel.executeTerminalCommand("neofetch") }
      QuickCommandChip("apt update") { viewModel.executeTerminalCommand("apt update") }
      QuickCommandChip("termux") { viewModel.executeTerminalCommand("termux-bridge") }
      QuickCommandChip("ls -la") { viewModel.executeTerminalCommand("ls -la") }
      QuickCommandChip("help") { viewModel.executeTerminalCommand("help") }
      QuickCommandChip("clear") { viewModel.executeTerminalCommand("clear") }
    }

    // Command Prompt Input Line
    Surface(
      modifier = Modifier.fillMaxWidth(),
      color = DarkSurface,
      border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle)
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "$ ",
          color = TerminalGreen,
          fontSize = 14.sp,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold
        )

        TextField(
          value = inputCommand,
          onValueChange = { inputCommand = it },
          placeholder = { Text("type command (e.g. git status, apt install...)", color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
          singleLine = true,
          modifier = Modifier
            .weight(1f)
            .testTag("terminal_input"),
          colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedTextColor = TextCode,
            unfocusedTextColor = TextCode,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
          ),
          textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        )

        if (currentSession.isRunning) {
          IconButton(
            onClick = { viewModel.interruptTerminal(currentSession.id) },
            modifier = Modifier
              .size(34.dp)
              .clip(CircleShape)
              .background(DangerRed)
              .testTag("btn_interrupt_terminal")
          ) {
            Icon(Icons.Default.Stop, contentDescription = "Interrupt", tint = Color.White, modifier = Modifier.size(16.dp))
          }
        } else {
          IconButton(
            onClick = {
              if (inputCommand.isNotBlank()) {
                viewModel.executeTerminalCommand(inputCommand)
                inputCommand = ""
              }
            },
            modifier = Modifier
              .size(34.dp)
              .clip(CircleShape)
              .background(ElectricBlue)
              .testTag("btn_run_terminal_command")
          ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run", tint = Color.White, modifier = Modifier.size(14.dp))
          }
        }
      }
    }

    // Terminal Developer Keybar (Section 14: Ctrl, Tab, ↑, ↓, |, /, ~)
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .background(DarkSurfaceHighlight)
        .padding(horizontal = 6.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      val termKeys = listOf("Ctrl", "Tab", "↑", "↓", "|", "/", "~", "Esc", "-", "_", "&", "C", "L")
      for (k in termKeys) {
        Box(
          modifier = Modifier
            .height(30.dp)
            .widthIn(min = 32.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(DarkSurfaceElevated)
            .border(1.dp, DarkBorderSubtle, RoundedCornerShape(4.dp))
            .clickable {
              when (k) {
                "Tab" -> inputCommand = "$inputCommand\t"
                "↑" -> inputCommand = "git status"
                "↓" -> inputCommand = "neofetch"
                "L" -> viewModel.executeTerminalCommand("clear")
                else -> inputCommand = "$inputCommand$k"
              }
            }
            .padding(horizontal = 8.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(text = k, color = TextCode, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
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
    fontSize = 11.sp,
    fontFamily = FontFamily.Monospace,
    lineHeight = 16.sp
  )
}

@Composable
private fun QuickCommandChip(
  cmd: String,
  onClick: () -> Unit
) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(DarkSurface)
      .border(1.dp, DarkBorderSubtle, RoundedCornerShape(4.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 3.dp)
  ) {
    Text(
      text = cmd,
      color = CyanAccent,
      fontSize = 11.sp,
      fontFamily = FontFamily.Monospace
    )
  }
}
