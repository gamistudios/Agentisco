package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import com.example.data.model.*
import com.example.ui.WorkspaceViewModel
import com.example.ui.theme.*

@Composable
fun AgentScreen(
  viewModel: WorkspaceViewModel,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  val activeProject by viewModel.activeProject.collectAsState()
  val selectedModel by viewModel.selectedModel.collectAsState()
  val isWorking by viewModel.isAgentWorking.collectAsState()
  val statusText by viewModel.agentStatusText.collectAsState()
  val steps by viewModel.agentSteps.collectAsState()
  val tools by viewModel.toolExecutions.collectAsState()

  var promptText by remember { mutableStateOf("") }
  var expandedStepId by remember { mutableStateOf<String?>("s4") }
  var showTaskTimeline by remember { mutableStateOf(true) }

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
      .padding(horizontal = 14.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    // Header & Command Center Prompt Box
    item {
      Spacer(modifier = Modifier.height(10.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text(
            text = "What are we building?",
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp
          )
          Text(
            text = "Describe a task or let the agent navigate ${activeProject.name}",
            color = TextMuted,
            fontSize = 12.sp
          )
        }

        // Split view toggle button
        IconButton(
          onClick = { onNavigate(AppDestination.EDITOR) },
          modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurfaceElevated)
            .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
            .testTag("btn_split_editor")
        ) {
          Icon(
            imageVector = Icons.Outlined.VerticalSplit,
            contentDescription = "Open Editor",
            tint = CyanAccent,
            modifier = Modifier.size(18.dp)
          )
        }
      }
    }

    // Command Center Input Box
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(14.dp))
          .border(1.dp, if (isWorking) ElectricBlue else DarkBorder, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          TextField(
            value = promptText,
            onValueChange = { promptText = it },
            placeholder = {
              Text(
                "Describe a task, e.g. 'Fix the chat loading issue'...",
                color = TextMuted,
                fontSize = 13.sp
              )
            },
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 80.dp)
              .testTag("agent_prompt_input"),
            colors = TextFieldDefaults.colors(
              focusedContainerColor = Color.Transparent,
              unfocusedContainerColor = Color.Transparent,
              focusedIndicatorColor = Color.Transparent,
              unfocusedIndicatorColor = Color.Transparent,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary
            )
          )

          Spacer(modifier = Modifier.height(8.dp))

          // Accessory attachment tags & Send button
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              PromptTagChip("+ Files") {
                promptText = if (promptText.contains("@files")) promptText else "$promptText @files"
              }
              PromptTagChip("+ Images") {
                promptText = "$promptText @ui-mockup"
              }
              PromptTagChip("@") {
                promptText = "$promptText @Chat.tsx"
              }
            }

            IconButton(
              onClick = {
                val task = promptText.ifBlank { "Fix the chat loading issue" }
                viewModel.runAgentTask(task)
                promptText = ""
              },
              enabled = !isWorking,
              modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (isWorking) DarkSurfaceHighlight else ElectricBlue)
                .testTag("btn_send_agent_task")
            ) {
              Icon(
                imageVector = Icons.Filled.Send,
                contentDescription = "Send Task",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
              )
            }
          }
        }
      }
    }

    // Quick Command Chips
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        QuickPromptCard(
          title = "Fix bug",
          subtitle = "Chat loading state",
          icon = Icons.Outlined.BugReport,
          modifier = Modifier.weight(1f),
          onClick = {
            viewModel.runAgentTask("Fix the chat loading and message persistence bug")
          }
        )
        QuickPromptCard(
          title = "Build feature",
          subtitle = "Add group invite",
          icon = Icons.Outlined.AddCircleOutline,
          modifier = Modifier.weight(1f),
          onClick = {
            viewModel.runAgentTask("Build group invitations flow and route")
          }
        )
      }
      Spacer(modifier = Modifier.height(6.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        QuickPromptCard(
          title = "Explain code",
          subtitle = "Chat.tsx & Zustand",
          icon = Icons.Outlined.Psychology,
          modifier = Modifier.weight(1f),
          onClick = {
            viewModel.runAgentTask("Explain how message caching and listeners work in Chat.tsx")
          }
        )
        QuickPromptCard(
          title = "Review changes",
          subtitle = "3 files modified",
          icon = Icons.Outlined.Difference,
          modifier = Modifier.weight(1f),
          onClick = {
            onNavigate(AppDestination.DIFF)
          }
        )
      }
    }

    // Working State Banner / Status
    item {
      AnimatedVisibility(visible = isWorking) {
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, ElectricBlue.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
          colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.weight(1f)
            ) {
              CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = ElectricBlueGlow,
                strokeWidth = 2.5.dp
              )
              Spacer(modifier = Modifier.width(12.dp))
              Column {
                Text(
                  text = "Agent Working",
                  color = ElectricBlueGlow,
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Bold
                )
                Text(
                  text = statusText,
                  color = TextSecondary,
                  fontSize = 11.sp,
                  maxLines = 1
                )
              }
            }

            TextButton(
              onClick = { viewModel.requestSampleApproval() },
              modifier = Modifier.testTag("btn_trigger_sample_approval")
            ) {
              Text("Test Approval", fontSize = 10.sp, color = WarningAmber)
            }
          }
        }
      }
    }

    // Section: Agent Task Progress Timeline
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = "Task Progress",
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
          )
          Spacer(modifier = Modifier.width(6.dp))
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(4.dp))
              .background(DarkSurfaceElevated)
              .padding(horizontal = 6.dp, vertical = 2.dp)
          ) {
            Text(
              text = "${steps.count { it.status == AgentStepStatus.COMPLETED }}/${steps.size}",
              color = TextSecondary,
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace
            )
          }
        }

        IconButton(
          onClick = { showTaskTimeline = !showTaskTimeline },
          modifier = Modifier.size(28.dp)
        ) {
          Icon(
            imageVector = if (showTaskTimeline) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = "Toggle Timeline",
            tint = TextMuted
          )
        }
      }
    }

    if (showTaskTimeline) {
      item {
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
          colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
          Column(modifier = Modifier.padding(12.dp)) {
            steps.forEachIndexed { idx, step ->
              val isExpanded = expandedStepId == step.id

              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(6.dp))
                  .clickable {
                    expandedStepId = if (isExpanded) null else step.id
                  }
                  .padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top
              ) {
                // Step status icon
                Box(
                  modifier = Modifier
                    .padding(top = 2.dp)
                    .size(16.dp),
                  contentAlignment = Alignment.Center
                ) {
                  when (step.status) {
                    AgentStepStatus.COMPLETED -> Icon(
                      imageVector = Icons.Default.CheckCircle,
                      contentDescription = "Completed",
                      tint = TerminalGreen,
                      modifier = Modifier.size(16.dp)
                    )
                    AgentStepStatus.RUNNING -> CircularProgressIndicator(
                      modifier = Modifier.size(14.dp),
                      color = ElectricBlueGlow,
                      strokeWidth = 2.dp
                    )
                    AgentStepStatus.PENDING -> Box(
                      modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, DarkBorder, CircleShape)
                    )
                    AgentStepStatus.FAILED -> Icon(
                      imageVector = Icons.Default.Cancel,
                      contentDescription = "Failed",
                      tint = DangerRed,
                      modifier = Modifier.size(16.dp)
                    )
                  }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text(
                      text = step.title,
                      color = when (step.status) {
                        AgentStepStatus.RUNNING -> ElectricBlueGlow
                        AgentStepStatus.COMPLETED -> TextPrimary
                        else -> TextMuted
                      },
                      fontSize = 13.sp,
                      fontWeight = if (step.status == AgentStepStatus.RUNNING) FontWeight.SemiBold else FontWeight.Medium
                    )

                    Icon(
                      imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                      contentDescription = "Expand",
                      tint = TextMuted,
                      modifier = Modifier.size(16.dp)
                    )
                  }

                  if (isExpanded) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(
                      modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkBackground)
                        .padding(10.dp)
                    ) {
                      if (step.filesInspected.isNotEmpty()) {
                        Text(
                          text = "${step.filesInspected.size} files inspected:",
                          color = TextMuted,
                          fontSize = 11.sp
                        )
                        step.filesInspected.forEach { f ->
                          Text(
                            text = "• $f",
                            color = CyanAccent,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                          )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                      }

                      if (step.finding != null) {
                        Text(
                          text = "Finding:",
                          color = WarningAmber,
                          fontSize = 11.sp,
                          fontWeight = FontWeight.Bold
                        )
                        Text(
                          text = step.finding,
                          color = TextPrimary,
                          fontSize = 11.sp,
                          lineHeight = 16.sp
                        )
                      } else {
                        Text(
                          text = step.details,
                          color = TextSecondary,
                          fontSize = 11.sp
                        )
                      }
                    }
                  }
                }
              }

              if (idx < steps.size - 1) {
                HorizontalDivider(
                  color = DarkBorderSubtle,
                  thickness = 0.5.dp,
                  modifier = Modifier.padding(start = 26.dp, top = 2.dp, bottom = 2.dp)
                )
              }
            }
          }
        }
      }
    }

    // Section: Tool Execution Cards
    item {
      Text(
        text = "Tool Executions",
        color = TextPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold
      )
    }

    items(tools) { tool ->
      ToolExecutionCard(
        tool = tool,
        onInspect = {
          when (tool.type) {
            ToolType.EDIT_FILE -> onNavigate(AppDestination.DIFF)
            ToolType.TERMINAL -> onNavigate(AppDestination.TERMINAL)
            ToolType.READ_FILE -> onNavigate(AppDestination.EDITOR)
            ToolType.SEARCH -> onNavigate(AppDestination.FILES)
            else -> {}
          }
        }
      )
    }

    item {
      Spacer(modifier = Modifier.height(24.dp))
    }
  }
}

@Composable
private fun PromptTagChip(text: String, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(DarkSurfaceElevated)
      .border(1.dp, DarkBorderSubtle, RoundedCornerShape(6.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 4.dp)
  ) {
    Text(
      text = text,
      color = TextSecondary,
      fontSize = 11.sp,
      fontFamily = FontFamily.Monospace
    )
  }
}

@Composable
private fun QuickPromptCard(
  title: String,
  subtitle: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  modifier: Modifier = Modifier,
  onClick: () -> Unit
) {
  Card(
    modifier = modifier
      .clip(RoundedCornerShape(10.dp))
      .border(1.dp, DarkBorderSubtle, RoundedCornerShape(10.dp))
      .clickable(onClick = onClick),
    colors = CardDefaults.cardColors(containerColor = DarkSurface)
  ) {
    Row(
      modifier = Modifier.padding(10.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(32.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(DarkSurfaceElevated),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = icon,
          contentDescription = title,
          tint = ElectricBlueGlow,
          modifier = Modifier.size(16.dp)
        )
      }
      Spacer(modifier = Modifier.width(8.dp))
      Column {
        Text(
          text = title,
          color = TextPrimary,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold
        )
        Text(
          text = subtitle,
          color = TextMuted,
          fontSize = 10.sp,
          maxLines = 1
        )
      }
    }
  }
}

@Composable
private fun ToolExecutionCard(
  tool: ToolExecution,
  onInspect: () -> Unit
) {
  var isExpanded by remember { mutableStateOf(false) }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .border(1.dp, DarkBorder, RoundedCornerShape(10.dp))
      .clickable { isExpanded = !isExpanded },
    colors = CardDefaults.cardColors(containerColor = DarkSurface)
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f)
        ) {
          // Tool Type Icon
          val icon = when (tool.type) {
            ToolType.READ_FILE -> Icons.Outlined.Description
            ToolType.SEARCH -> Icons.Outlined.Search
            ToolType.TERMINAL -> Icons.Outlined.Terminal
            ToolType.EDIT_FILE -> Icons.Outlined.Edit
            ToolType.GIT -> Icons.Outlined.Commit
            ToolType.BUILD -> Icons.Outlined.Build
          }
          val iconColor = when (tool.type) {
            ToolType.READ_FILE -> CyanAccent
            ToolType.SEARCH -> WarningAmber
            ToolType.TERMINAL -> TerminalGreen
            ToolType.EDIT_FILE -> ElectricBlueGlow
            ToolType.GIT -> IndigoAccent
            ToolType.BUILD -> WarningAmber
          }

          Icon(
            imageVector = icon,
            contentDescription = tool.title,
            tint = iconColor,
            modifier = Modifier.size(18.dp)
          )

          Spacer(modifier = Modifier.width(10.dp))

          Column {
            Text(
              text = tool.title,
              color = TextPrimary,
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold,
              fontFamily = if (tool.type == ToolType.TERMINAL) FontFamily.Monospace else FontFamily.Default
            )
            Text(
              text = tool.subtitle,
              color = TextSecondary,
              fontSize = 11.sp
            )
          }
        }

        IconButton(onClick = onInspect, modifier = Modifier.size(28.dp)) {
          Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = "Inspect",
            tint = TextMuted,
            modifier = Modifier.size(18.dp)
          )
        }
      }

      if (isExpanded && (tool.details.isNotBlank() || tool.output.isNotBlank())) {
        Spacer(modifier = Modifier.height(8.dp))
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DarkBackground)
            .padding(10.dp)
        ) {
          Column {
            if (tool.details.isNotBlank()) {
              Text(
                text = tool.details,
                color = TextSecondary,
                fontSize = 11.sp
              )
            }
            if (tool.output.isNotBlank()) {
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = tool.output,
                color = TerminalGreen,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
              )
            }
          }
        }
      }
    }
  }
}
