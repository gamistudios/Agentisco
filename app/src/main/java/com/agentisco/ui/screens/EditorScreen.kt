package com.agentisco.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import com.agentisco.core.model.AppDestination
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.components.DevKeyboardBar
import com.agentisco.ui.theme.*

@Composable
fun EditorScreen(
  viewModel: WorkspaceViewModel,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  val activeFile by viewModel.activeFile.collectAsState()
  val content by viewModel.editorContent.collectAsState()
  val isEditorDirty by viewModel.isEditorDirty.collectAsState()
  val isAgentWorking by viewModel.isAgentWorking.collectAsState()

  var showAgentSplitPane by remember { mutableStateOf(true) }
  var isEditMode by remember { mutableStateOf(false) }
  var editedText by remember(content) { mutableStateOf(content) }

  val lines = remember(editedText) { editedText.lines() }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
      // Keep the editor content and dev keybar above the soft keyboard.
      .imePadding()
  ) {
    // Editor Top Bar
    Surface(
      modifier = Modifier.fillMaxWidth(),
      color = DarkSurface,
      border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle)
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(
            onClick = { onNavigate(AppDestination.FILES) },
            modifier = Modifier.size(30.dp)
          ) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "Files", tint = TextMuted)
          }

          Spacer(modifier = Modifier.width(4.dp))

          // Tab active file
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(6.dp))
              .background(DarkSurfaceElevated)
              .border(1.dp, DarkBorder, RoundedCornerShape(6.dp))
              .padding(horizontal = 10.dp, vertical = 5.dp)
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Outlined.Code,
                contentDescription = "Code",
                tint = CyanAccent,
                modifier = Modifier.size(14.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = activeFile.name,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
              )
              if (isEditorDirty || editedText != activeFile.content) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                  modifier = Modifier
                    .size(7.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(WarningAmber)
                )
              }
            }
          }
        }

        // Action controls: Edit toggle, Agent Split toggle, Diff, Save
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          IconButton(
            onClick = { isEditMode = !isEditMode },
            modifier = Modifier
              .size(32.dp)
              .clip(RoundedCornerShape(6.dp))
              .background(if (isEditMode) ElectricBlue.copy(alpha = 0.2f) else DarkSurfaceElevated)
              .testTag("btn_toggle_edit_mode")
          ) {
            Icon(
              imageVector = if (isEditMode) Icons.Outlined.Edit else Icons.Outlined.Visibility,
              contentDescription = "Edit Mode",
              tint = if (isEditMode) ElectricBlueGlow else TextSecondary,
              modifier = Modifier.size(16.dp)
            )
          }

          IconButton(
            onClick = { showAgentSplitPane = !showAgentSplitPane },
            modifier = Modifier
              .size(32.dp)
              .clip(RoundedCornerShape(6.dp))
              .background(if (showAgentSplitPane) ElectricBlue.copy(alpha = 0.2f) else DarkSurfaceElevated)
              .testTag("btn_toggle_agent_split")
          ) {
            Icon(
              imageVector = Icons.Outlined.AutoAwesome,
              contentDescription = "Agent Split",
              tint = if (showAgentSplitPane) ElectricBlueGlow else TextSecondary,
              modifier = Modifier.size(16.dp)
            )
          }

          IconButton(
            onClick = { onNavigate(AppDestination.DIFF) },
            modifier = Modifier
              .size(32.dp)
              .clip(RoundedCornerShape(6.dp))
              .background(DarkSurfaceElevated)
              .testTag("btn_editor_diff")
          ) {
            Icon(
              imageVector = Icons.Outlined.Difference,
              contentDescription = "Diff",
              tint = CyanAccent,
              modifier = Modifier.size(16.dp)
            )
          }

          Button(
            onClick = {
              viewModel.updateEditorContent(editedText)
              viewModel.saveActiveFile()
            },
            colors = ButtonDefaults.buttonColors(
              containerColor = if (isEditorDirty || editedText != activeFile.content) ElectricBlue else DarkSurfaceElevated
            ),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.height(30.dp).testTag("btn_save_file")
          ) {
            Text(
              "Save",
              fontSize = 11.sp,
              fontWeight = FontWeight.SemiBold,
              color = if (isEditorDirty || editedText != activeFile.content) Color.White else TextSecondary
            )
          }
        }
      }
    }

    // Code Content Area
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .background(DarkBackground)
    ) {
      if (isEditMode) {
        // Editable textfield mode
        TextField(
          value = editedText,
          onValueChange = { editedText = it },
          modifier = Modifier
            .fillMaxSize()
            .testTag("editor_textarea"),
          colors = TextFieldDefaults.colors(
            focusedContainerColor = DarkBackground,
            unfocusedContainerColor = DarkBackground,
            focusedTextColor = TextCode,
            unfocusedTextColor = TextCode,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
          ),
          textStyle = LocalTextStyle.current.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp
          )
        )
      } else {
        // Formatted code viewer with line numbers and token coloring
        LazyColumn(
          modifier = Modifier
            .fillMaxSize()
            .clickable { isEditMode = true }
            .padding(vertical = 6.dp)
        ) {
          itemsIndexed(lines) { index, line ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              // Line Number
              Text(
                text = "${index + 1}".padStart(3, ' '),
                color = DarkBorder,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                  .width(36.dp)
                  .padding(end = 8.dp)
              )

              // Code line with basic syntax highlight
              HighlightedCodeLine(line = line)
            }
          }
        }
      }
    }

    // Agent Split Pane (Section 11)
    AnimatedVisibility(visible = showAgentSplitPane) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DarkSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
      ) {
        Column(modifier = Modifier.padding(10.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "Agent",
                tint = ElectricBlueGlow,
                modifier = Modifier.size(14.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "Agent changed lines 42-45 in ${activeFile.name}",
                color = ElectricBlueGlow,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
              )
            }

            IconButton(
              onClick = { showAgentSplitPane = false },
              modifier = Modifier.size(20.dp)
            ) {
              Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted, modifier = Modifier.size(14.dp))
            }
          }

          Spacer(modifier = Modifier.height(4.dp))

          Text(
            text = "Retained loaded message cache during conversationId updates. Added cleanup listener to prevent state recreation.",
            color = TextSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp
          )

          Spacer(modifier = Modifier.height(6.dp))

          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            OutlinedButton(
              onClick = { onNavigate(AppDestination.DIFF) },
              modifier = Modifier.height(28.dp),
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
              border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.5f))
            ) {
              Text("Review Changes", color = ElectricBlueGlow, fontSize = 11.sp)
            }

            TextButton(
              onClick = { onNavigate(AppDestination.AGENT) },
              modifier = Modifier.height(28.dp)
            ) {
              Text("Ask Agent to explain", color = CyanAccent, fontSize = 11.sp)
            }
          }
        }
      }
    }

    // Developer Keyboard Toolbar (Accessory Bar)
    DevKeyboardBar(
      onInsertSymbol = { sym ->
        isEditMode = true
        editedText = "$editedText$sym"
      },
      onAction = { act ->
        when (act) {
          "// Comment" -> {
            isEditMode = true
            editedText = "// $editedText"
          }
          "Undo" -> {
            editedText = content
          }
          else -> {}
        }
      }
    )
  }
}

@Composable
private fun HighlightedCodeLine(line: String) {
  val trimmed = line.trimStart()
  val textColor = when {
    trimmed.startsWith("//") -> SyntaxComment
    trimmed.startsWith("import ") || trimmed.startsWith("export ") || trimmed.startsWith("const ") || trimmed.startsWith("return ") || trimmed.startsWith("interface ") -> SyntaxKeyword
    trimmed.startsWith("function ") || trimmed.contains("async ()") -> SyntaxFunction
    trimmed.contains("\"") || trimmed.contains("'") -> SyntaxString
    trimmed.contains("useState") || trimmed.contains("useEffect") || trimmed.contains("useChatStore") -> SyntaxType
    else -> TextCode
  }

  Text(
    text = line,
    color = textColor,
    fontSize = 11.sp,
    fontFamily = FontFamily.Monospace,
    modifier = Modifier.horizontalScroll(rememberScrollState())
  )
}
