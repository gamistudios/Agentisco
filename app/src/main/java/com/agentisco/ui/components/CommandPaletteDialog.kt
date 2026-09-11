package com.agentisco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.window.Dialog
import com.agentisco.core.model.AppDestination
import com.agentisco.ui.theme.*

data class PaletteAction(
  val id: String,
  val title: String,
  val category: String,
  val destination: AppDestination? = null,
  val onExecute: (() -> Unit)? = null
)

@Composable
fun CommandPaletteDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  onNavigate: (AppDestination) -> Unit,
  onOpenModelSheet: () -> Unit,
  onStartAgentTask: (String) -> Unit
) {
  if (!isOpen) return

  var query by remember { mutableStateOf("") }

  val allActions = remember {
    listOf(
      PaletteAction("agent_fix", "Start Agent: Fix chat loading issue", "Agent", AppDestination.AGENT) {
        onStartAgentTask("Fix the chat loading issue and message lifecycle")
      },
      PaletteAction("agent_feature", "Start Agent: Build feature with tests", "Agent", AppDestination.AGENT) {
        onStartAgentTask("Build auth flow and verify with test suite")
      },
      PaletteAction("open_file", "Open file: Chat.tsx", "Files", AppDestination.EDITOR),
      PaletteAction("open_store", "Open file: chatStore.ts", "Files", AppDestination.EDITOR),
      PaletteAction("search", "Search project files", "Search", AppDestination.FILES),
      PaletteAction("terminal_new", "New terminal session", "Terminal", AppDestination.TERMINAL),
      PaletteAction("git_status", "Git: Review staged changes & commit", "Git", AppDestination.GIT),
      PaletteAction("git_diff", "Diff: Review active file changes", "Diff", AppDestination.DIFF),
      PaletteAction("build_preview", "Build & Run: Open preview server", "Run", AppDestination.BUILD_RUN),
      PaletteAction("switch_model", "Switch AI Model / Provider", "Settings", null) {
        onOpenModelSheet()
      },
      PaletteAction("settings", "Open IDE & Agent permissions settings", "Settings", AppDestination.SETTINGS)
    )
  }

  val filteredActions = remember(query) {
    if (query.isBlank()) allActions
    else allActions.filter {
      it.title.contains(query, ignoreCase = true) || it.category.contains(query, ignoreCase = true)
    }
  }

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .border(1.dp, DarkBorder, RoundedCornerShape(16.dp)),
      color = DarkSurface,
      tonalElevation = 8.dp
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp)
      ) {
        Text(
          text = "What do you want to do?",
          color = TextPrimary,
          fontSize = 14.sp,
          fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Search input
        TextField(
          value = query,
          onValueChange = { query = it },
          placeholder = {
            Text("> Type a command or file...", color = TextMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
          },
          leadingIcon = {
            Text(">", color = CyanAccent, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 12.dp))
          },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DarkBackground)
            .border(1.dp, DarkBorderSubtle, RoundedCornerShape(8.dp))
            .testTag("cmd_palette_input"),
          colors = TextFieldDefaults.colors(
            focusedContainerColor = DarkBackground,
            unfocusedContainerColor = DarkBackground,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary
          )
        )

        Spacer(modifier = Modifier.height(12.dp))

        HorizontalDivider(color = DarkBorderSubtle, thickness = 1.dp)

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          items(filteredActions) { action ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                  onDismiss()
                  if (action.destination != null) {
                    onNavigate(action.destination)
                  }
                  action.onExecute?.invoke()
                }
                .padding(horizontal = 10.dp, vertical = 10.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = action.title,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
              )
              Box(
                modifier = Modifier
                  .clip(RoundedCornerShape(4.dp))
                  .background(DarkSurfaceElevated)
                  .padding(horizontal = 6.dp, vertical = 2.dp)
              ) {
                Text(
                  text = action.category,
                  color = TextMuted,
                  fontSize = 10.sp,
                  fontFamily = FontFamily.Monospace
                )
              }
            }
          }
        }
      }
    }
  }
}
