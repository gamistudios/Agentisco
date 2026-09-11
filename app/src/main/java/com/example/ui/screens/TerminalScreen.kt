package com.example.ui.screens

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
  val listState = rememberLazyListState()

  val currentSession = remember(sessions, activeSessionId) {
    sessions.find { it.id == activeSessionId } ?: sessions.first()
  }

  LaunchedEffect(currentSession.lines.size) {
    if (currentSession.lines.isNotEmpty()) {
      listState.animateScrollToItem(currentSession.lines.size - 1)
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
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
            .padding(horizontal = 14.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(TerminalGreen)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Terminal Environment",
              color = TextPrimary,
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = currentSession.currentDir,
              color = TextMuted,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace
            )
          }

          IconButton(
            onClick = { viewModel.createTerminalSession("bash-${sessions.size + 1}") },
            modifier = Modifier
              .size(28.dp)
              .clip(RoundedCornerShape(6.dp))
              .background(DarkSurfaceElevated)
              .testTag("btn_new_terminal_session")
          ) {
            Icon(Icons.Default.Add, contentDescription = "New Session", tint = ElectricBlueGlow, modifier = Modifier.size(16.dp))
          }
        }

        // Sessions Tab Row
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          sessions.forEach { s ->
            val isSelected = s.id == activeSessionId
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (isSelected) DarkSurfaceHighlight else DarkBackground)
                .border(1.dp, if (isSelected) ElectricBlue else DarkBorderSubtle, RoundedCornerShape(6.dp))
                .clickable { viewModel.selectTerminalSession(s.id) }
                .padding(horizontal = 10.dp, vertical = 5.dp)
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
              }
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

    // Quick Command Bar (npm test, git status, npm run dev, clear)
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .background(DarkSurfaceElevated)
        .padding(horizontal = 8.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      QuickCommandChip("git status") { viewModel.executeTerminalCommand("git status") }
      QuickCommandChip("npm test") { viewModel.executeTerminalCommand("npm test") }
      QuickCommandChip("npm run dev") { viewModel.executeTerminalCommand("npm run dev") }
      QuickCommandChip("ls") { viewModel.executeTerminalCommand("ls") }
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
          placeholder = { Text("type command...", color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
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
                "↓" -> inputCommand = "npm test"
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
