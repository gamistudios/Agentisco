package com.agentisco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentisco.core.model.AppDestination
import com.agentisco.data.model.Project
import com.agentisco.data.repository.UpdateRepository
import com.agentisco.ui.theme.*
import com.agentisco.workspace.git.GitBranch

/**
 * Modern compact IDE header. Row 1 is the brand/workspace header (status dot +
 * project + compact branch selector + sleek action icons); row 2 is the 5-tool
 * workspace toolbar (Files, Editor, Changes, Git, Build). Projects and Agent
 * live in the primary bottom navigation.
 */
@Composable
fun AgentIDETopAppBar(
  activeProject: Project,
  currentDestination: AppDestination,
  onNavigate: (AppDestination) -> Unit,
  onOpenModelSheet: () -> Unit,
  onOpenCommandPalette: () -> Unit,
  modifier: Modifier = Modifier,
  updateState: UpdateRepository.UpdateState? = null,
  updateProgress: Float = 0f,
  hasNewUpdate: Boolean = false,
  onUpdateClick: (() -> Unit)? = null,
  /** Local branches of [activeProject]; empty until Git has scanned the repo. */
  branches: List<GitBranch> = emptyList(),
  onCheckoutBranch: (String) -> Unit = {},
  /** Debug builds only: opens the last-run crash trace. Null hides the button. */
  onShowCrashLog: (() -> Unit)? = null
) {
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .statusBarsPadding(),
    color = DarkBackground,
    border = androidx.compose.foundation.BorderStroke(0.dp, DarkBorderSubtle)
  ) {
    Box(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.fillMaxWidth()) {
        // ——— Row 1: compact project / Git header & utility icons ———
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .padding(horizontal = 14.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Left: Status dot + Project name + Branch pill
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
          ) {
            val statusColor = when {
              activeProject.isMissing -> DangerRed
              activeProject.isDirty -> WarningAmber
              else -> TerminalGreen
            }
            Box(
              modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(statusColor)
            )
            Spacer(modifier = Modifier.width(8.dp))

            Text(
              text = activeProject.name.ifBlank { "Agentisco" },
              color = TextPrimary,
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.width(10.dp))

            // Branch pill: with more than one local branch it is a switcher,
            // otherwise there is nothing to pick and it stays a Projects shortcut.
            val localBranches = remember(branches) { branches.filter { !it.isRemote } }
            val canSwitchBranch = localBranches.size > 1
            val branch = activeProject.branch.ifBlank { "main" }
            var branchMenuOpen by remember { mutableStateOf(false) }
            Box {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                  .widthIn(max = 150.dp)
                  .clip(RoundedCornerShape(8.dp))
                  .background(DarkSurfaceElevated)
                  .border(1.dp, DarkBorderSubtle, RoundedCornerShape(8.dp))
                  .clickable {
                    if (canSwitchBranch) branchMenuOpen = true
                    else onNavigate(AppDestination.PROJECTS)
                  }
                  .padding(horizontal = 8.dp, vertical = 4.dp)
                  .testTag(if (canSwitchBranch) "top_branch_switcher" else "top_project_selector")
              ) {
                Icon(
                  imageVector = Icons.Outlined.AccountTree,
                  contentDescription = null,
                  tint = TextMuted,
                  modifier = Modifier.size(11.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                  text = branch,
                  color = TextSecondary,
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = FontWeight.Medium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                  imageVector = Icons.Default.ArrowDropDown,
                  contentDescription = if (canSwitchBranch) "Switch branch" else "Switch project",
                  tint = TextMuted,
                  modifier = Modifier.size(14.dp)
                )
              }

              DropdownMenu(
                expanded = branchMenuOpen,
                onDismissRequest = { branchMenuOpen = false },
                containerColor = DarkSurfaceElevated
              ) {
                localBranches.forEach { item ->
                  val isCurrent = item.name == branch
                  DropdownMenuItem(
                    text = {
                      Text(
                        text = item.name,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isCurrent) TerminalGreen else TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                      )
                    },
                    leadingIcon = {
                      Icon(
                        imageVector = if (isCurrent) Icons.Default.Check else Icons.Outlined.AccountTree,
                        contentDescription = null,
                        tint = if (isCurrent) TerminalGreen else TextMuted,
                        modifier = Modifier.size(15.dp)
                      )
                    },
                    trailingIcon = {
                      if (item.ahead > 0 || item.behind > 0) {
                        Text(
                          text = buildString {
                            if (item.ahead > 0) append("↑${item.ahead}")
                            if (item.ahead > 0 && item.behind > 0) append(" ")
                            if (item.behind > 0) append("↓${item.behind}")
                          },
                          color = TextMuted,
                          fontSize = 10.sp,
                          fontFamily = FontFamily.Monospace
                        )
                      }
                    },
                    onClick = {
                      branchMenuOpen = false
                      if (!isCurrent) onCheckoutBranch(item.name)
                    },
                    modifier = Modifier.testTag("branch_option_${item.name}")
                  )
                }
              }
            }
          }

          // Right: Sleek action icons (Download, Search, Settings, CrashLog)
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            if (onShowCrashLog != null) {
              IconButton(
                onClick = onShowCrashLog,
                modifier = Modifier
                  .size(34.dp)
                  .testTag("top_crash_log")
              ) {
                Icon(
                  imageVector = Icons.Outlined.BugReport,
                  contentDescription = "Crash log available",
                  tint = DangerRed,
                  modifier = Modifier.size(18.dp)
                )
              }
            }

            if (onUpdateClick != null) {
              IconButton(
                onClick = onUpdateClick,
                modifier = Modifier
                  .size(34.dp)
                  .testTag("top_update")
              ) {
                Box {
                  Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    contentDescription = if (hasNewUpdate) "Update available" else "Check for updates",
                    tint = if (hasNewUpdate) WarningAmber else TextSecondary,
                    modifier = Modifier.size(19.dp)
                  )
                  if (hasNewUpdate) {
                    Box(
                      modifier = Modifier
                        .size(6.dp)
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(WarningAmber)
                    )
                  }
                }
              }
            }

            IconButton(
              onClick = onOpenCommandPalette,
              modifier = Modifier
                .size(34.dp)
                .testTag("top_cmd_palette")
            ) {
              Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = "Command palette — search",
                tint = TextSecondary,
                modifier = Modifier.size(19.dp)
              )
            }

            IconButton(
              onClick = { onNavigate(AppDestination.SETTINGS) },
              modifier = Modifier
                .size(34.dp)
                .testTag("top_settings")
            ) {
              Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "Settings",
                tint = TextSecondary,
                modifier = Modifier.size(19.dp)
              )
            }
          }
        }

        // ——— Row 2: IDE workspace toolbar (5 primary tools: Files, Editor, Changes, Git, Build) ———
        IdeToolbar(
          selected = currentDestination,
          changedFilesCount = activeProject.changedFilesCount,
          onNavigate = onNavigate
        )

        HorizontalDivider(color = DarkBorderSubtle, thickness = 1.dp)
      }

      DownloadProgressIndicator(
        progress = updateProgress,
        visible = updateState == UpdateRepository.UpdateState.DOWNLOADING,
        modifier = Modifier
          .fillMaxWidth()
          .align(Alignment.TopCenter)
      )
    }
  }
}

/** One entry in the IDE workspace toolbar. */
private data class IdeTool(
  val label: String,
  val icon: ImageVector,
  val destination: AppDestination,
  val testTag: String
)

/**
 * 5 primary workspace tools: Files, Editor, Changes, Git, Build.
 * Projects and Agent are kept exclusively in the bottom navigation.
 */
@Composable
private fun IdeToolbar(
  selected: AppDestination,
  changedFilesCount: Int,
  onNavigate: (AppDestination) -> Unit
) {
  val tools = listOf(
    IdeTool("Files", Icons.Outlined.Folder, AppDestination.FILES, "tool_files"),
    IdeTool("Editor", Icons.Outlined.Code, AppDestination.EDITOR, "tool_editor"),
    IdeTool("Changes", Icons.Outlined.Description, AppDestination.DIFF, "tool_changes"),
    IdeTool("Git", Icons.Outlined.AccountTree, AppDestination.GIT, "tool_git"),
    IdeTool("Build", Icons.Outlined.Inventory2, AppDestination.BUILD_RUN, "tool_build")
  )

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 4.dp)
      .testTag("ide_toolbar"),
    horizontalArrangement = Arrangement.SpaceAround,
    verticalAlignment = Alignment.CenterVertically
  ) {
    tools.forEach { tool ->
      val isSelected = selected == tool.destination
      IdeToolTab(
        tool = tool,
        selected = isSelected,
        badge = if (tool.destination == AppDestination.DIFF && changedFilesCount > 0) {
          changedFilesCount.toString()
        } else {
          null
        },
        modifier = Modifier.weight(1f),
        onClick = { onNavigate(tool.destination) }
      )
    }
  }
}

@Composable
private fun IdeToolTab(
  tool: IdeTool,
  selected: Boolean,
  badge: String?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val contentColor = if (selected) ElectricBlueGlow else TextSecondary

  Column(
    modifier = modifier
      .clickable(onClick = onClick)
      .padding(vertical = 4.dp)
      .testTag(tool.testTag),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
      modifier = Modifier.padding(horizontal = 2.dp, vertical = 3.dp)
    ) {
      Box {
        Icon(
          imageVector = tool.icon,
          contentDescription = null,
          tint = contentColor,
          modifier = Modifier.size(16.dp)
        )
        if (badge != null) {
          ToolCountBadge(
            count = badge,
            modifier = Modifier
              .align(Alignment.TopEnd)
              .offset(x = 6.dp, y = (-4).dp)
          )
        }
      }

      Spacer(modifier = Modifier.width(5.dp))

      Text(
        text = tool.label,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        color = contentColor,
        maxLines = 1
      )
    }

    Spacer(modifier = Modifier.height(3.dp))

    // Active bottom indicator line
    Box(
      modifier = Modifier
        .fillMaxWidth(0.85f)
        .height(2.dp)
        .background(
          if (selected) ElectricBlue else Color.Transparent,
          RoundedCornerShape(1.dp)
        )
    )
  }
}

/** Changed-files count on the Changes tool — solid amber badge. */
@Composable
private fun ToolCountBadge(count: String, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .defaultMinSize(minWidth = 12.dp, minHeight = 12.dp)
      .clip(RoundedCornerShape(6.dp))
      .background(WarningAmber)
      .padding(horizontal = 3.dp),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = count,
      fontSize = 8.sp,
      lineHeight = 9.sp,
      fontWeight = FontWeight.Bold,
      color = DarkBackground,
      maxLines = 1
    )
  }
}
