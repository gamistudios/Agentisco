package com.agentisco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.agentisco.core.model.AppDestination
import com.agentisco.ui.theme.*

data class PaletteAction(
  val id: String,
  val title: String,
  val category: String,
  val icon: ImageVector? = null,
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

  val baseActions = remember {
    listOf(
      // ━━━ GENERIC & HELPFUL AGENT TASKS ━━━
      PaletteAction(
        id = "agent_analyze",
        title = "Start Agent: Analyze codebase and project structure",
        category = "Agent",
        icon = Icons.Outlined.AutoAwesome,
        destination = AppDestination.AGENT
      ) {
        onStartAgentTask("Analyze the codebase structure, entry points, and project architecture")
      },
      PaletteAction(
        id = "agent_bugs",
        title = "Start Agent: Find and fix bugs in current files",
        category = "Agent",
        icon = Icons.Outlined.BugReport,
        destination = AppDestination.AGENT
      ) {
        onStartAgentTask("Review the active codebase, identify bugs or potential issues, and suggest fixes")
      },
      PaletteAction(
        id = "agent_tests",
        title = "Start Agent: Write unit tests for recent changes",
        category = "Agent",
        icon = Icons.Outlined.CheckCircleOutline,
        destination = AppDestination.AGENT
      ) {
        onStartAgentTask("Write unit tests to verify recent changes and cover critical edge cases")
      },
      PaletteAction(
        id = "agent_refactor",
        title = "Start Agent: Refactor code & optimize performance",
        category = "Agent",
        icon = Icons.Outlined.Tune,
        destination = AppDestination.AGENT
      ) {
        onStartAgentTask("Refactor code for cleanliness, maintainability, and optimal performance")
      },
      PaletteAction(
        id = "agent_explain",
        title = "Start Agent: Explain project setup & dependencies",
        category = "Agent",
        icon = Icons.AutoMirrored.Outlined.HelpOutline,
        destination = AppDestination.AGENT
      ) {
        onStartAgentTask("Explain this project's setup, dependencies, build steps, and architecture")
      },
      PaletteAction(
        id = "agent_review_diff",
        title = "Start Agent: Review recent git changes & diffs",
        category = "Agent",
        icon = Icons.AutoMirrored.Outlined.CompareArrows,
        destination = AppDestination.AGENT
      ) {
        onStartAgentTask("Inspect recent git changes and provide a comprehensive code review with recommendations")
      },
      PaletteAction(
        id = "agent_readme",
        title = "Start Agent: Generate README / project documentation",
        category = "Agent",
        icon = Icons.Outlined.Description,
        destination = AppDestination.AGENT
      ) {
        onStartAgentTask("Generate a comprehensive README.md documenting project setup, architecture, and usage")
      },

      // ━━━ NAVIGATION & CORE WORKSPACE DESTINATIONS ━━━
      PaletteAction(
        id = "nav_editor",
        title = "Open Code Editor",
        category = "Editor",
        icon = Icons.Outlined.Code,
        destination = AppDestination.EDITOR
      ),
      PaletteAction(
        id = "nav_changes",
        title = "View Changes & Source Control (Diff)",
        category = "Changes",
        icon = Icons.AutoMirrored.Outlined.CompareArrows,
        destination = AppDestination.DIFF
      ),
      PaletteAction(
        id = "nav_git",
        title = "Open Git Log & Version History",
        category = "Git",
        icon = Icons.AutoMirrored.Outlined.CallSplit,
        destination = AppDestination.GIT
      ),
      PaletteAction(
        id = "nav_terminal",
        title = "Open Terminal Session (Debian / Bash)",
        category = "Terminal",
        icon = Icons.Outlined.Terminal,
        destination = AppDestination.TERMINAL
      ),
      PaletteAction(
        id = "nav_files",
        title = "Project Files Explorer",
        category = "Files",
        icon = Icons.Outlined.Folder,
        destination = AppDestination.FILES
      ),
      PaletteAction(
        id = "nav_build_run",
        title = "Build, Run & Preview Server",
        category = "Run",
        icon = Icons.Outlined.PlayArrow,
        destination = AppDestination.BUILD_RUN
      ),
      PaletteAction(
        id = "nav_projects",
        title = "Switch Projects / Open Workspace",
        category = "Projects",
        icon = Icons.Outlined.FolderSpecial,
        destination = AppDestination.PROJECTS
      ),
      PaletteAction(
        id = "nav_agent",
        title = "Open AI Agent Chat & Assistant",
        category = "Agent",
        icon = Icons.AutoMirrored.Outlined.Chat,
        destination = AppDestination.AGENT
      ),
      PaletteAction(
        id = "nav_settings",
        title = "Open IDE & Agent Permissions Settings",
        category = "Settings",
        icon = Icons.Outlined.Settings,
        destination = AppDestination.SETTINGS
      ),

      // ━━━ GIT & SOURCE CONTROL QUICK ACTIONS ━━━
      PaletteAction(
        id = "git_stage_all",
        title = "Git: Review & stage all modified files",
        category = "Git",
        icon = Icons.Outlined.Add,
        destination = AppDestination.DIFF
      ),
      PaletteAction(
        id = "git_commit",
        title = "Git: Review and commit staged changes",
        category = "Git",
        icon = Icons.Outlined.Check,
        destination = AppDestination.DIFF
      ),
      PaletteAction(
        id = "git_discard",
        title = "Git: Discard or revert working changes",
        category = "Git",
        icon = Icons.AutoMirrored.Outlined.Undo,
        destination = AppDestination.DIFF
      ),
      PaletteAction(
        id = "git_switch_branch",
        title = "Git: Switch or checkout branch",
        category = "Git",
        icon = Icons.AutoMirrored.Outlined.CallSplit,
        destination = AppDestination.GIT
      ),
      PaletteAction(
        id = "git_create_branch",
        title = "Git: Create new branch",
        category = "Git",
        icon = Icons.Outlined.Add,
        destination = AppDestination.GIT
      ),
      PaletteAction(
        id = "git_pull",
        title = "Git: Pull latest changes from remote",
        category = "Git",
        icon = Icons.Outlined.CloudDownload,
        destination = AppDestination.GIT
      ),
      PaletteAction(
        id = "git_push",
        title = "Git: Push commits to remote",
        category = "Git",
        icon = Icons.Outlined.CloudUpload,
        destination = AppDestination.GIT
      ),
      PaletteAction(
        id = "git_fetch",
        title = "Git: Fetch from remote",
        category = "Git",
        icon = Icons.Outlined.Sync,
        destination = AppDestination.GIT
      ),
      PaletteAction(
        id = "git_sync",
        title = "Git: Sync with remote (Pull & Push)",
        category = "Git",
        icon = Icons.Outlined.Sync,
        destination = AppDestination.GIT
      ),
      PaletteAction(
        id = "git_stash",
        title = "Git: Stash working changes",
        category = "Git",
        icon = Icons.Outlined.Inventory2,
        destination = AppDestination.GIT
      ),
      PaletteAction(
        id = "git_tags",
        title = "Git: Manage tags & releases",
        category = "Git",
        icon = Icons.Outlined.BookmarkBorder,
        destination = AppDestination.GIT
      ),

      // ━━━ WORKSPACE & SYSTEM SHORTCUTS ━━━
      PaletteAction(
        id = "search_files",
        title = "Search project files by name",
        category = "Search",
        icon = Icons.Outlined.Search,
        destination = AppDestination.FILES
      ),
      PaletteAction(
        id = "terminal_new",
        title = "New terminal session / bash shell",
        category = "Terminal",
        icon = Icons.Outlined.Terminal,
        destination = AppDestination.TERMINAL
      ),
      PaletteAction(
        id = "build_preview",
        title = "Open live preview / web dev server",
        category = "Run",
        icon = Icons.Outlined.PlayArrow,
        destination = AppDestination.BUILD_RUN
      ),
      PaletteAction(
        id = "switch_model",
        title = "Switch AI Model or Provider",
        category = "Settings",
        icon = Icons.Outlined.Psychology,
        destination = null
      ) {
        onOpenModelSheet()
      },
      PaletteAction(
        id = "settings_permissions",
        title = "Configure Agent Permissions & Tool Guards",
        category = "Settings",
        icon = Icons.Outlined.Security,
        destination = AppDestination.SETTINGS
      )
    )
  }

  val filteredActions = remember(query, baseActions) {
    val trimmed = query.trim()
    val matches = if (trimmed.isBlank()) {
      baseActions
    } else {
      baseActions.filter {
        it.title.contains(trimmed, ignoreCase = true) || it.category.contains(trimmed, ignoreCase = true)
      }
    }

    if (trimmed.isNotBlank()) {
      // Allow dynamic custom prompt execution directly from command palette input
      listOf(
        PaletteAction(
          id = "custom_prompt",
          title = "Ask Agent: \"$trimmed\"",
          category = "Agent",
          icon = Icons.Outlined.AutoAwesome,
          destination = AppDestination.AGENT
        ) {
          onStartAgentTask(trimmed)
        }
      ) + matches
    } else {
      matches
    }
  }

  val executeAction: (PaletteAction) -> Unit = { action ->
    onDismiss()
    if (action.destination != null) {
      onNavigate(action.destination)
    }
    action.onExecute?.invoke()
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
            Text("> Type a command, action, or prompt...", color = TextMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
          },
          leadingIcon = {
            Text(">", color = CyanAccent, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 12.dp))
          },
          singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
          keyboardActions = KeyboardActions(
            onGo = {
              filteredActions.firstOrNull()?.let { executeAction(it) }
            }
          ),
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
            .heightIn(max = 340.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          items(filteredActions, key = { it.id }) { action ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { executeAction(action) }
                .padding(horizontal = 10.dp, vertical = 9.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              if (action.icon != null) {
                Icon(
                  imageVector = action.icon,
                  contentDescription = null,
                  tint = when (action.category) {
                    "Agent" -> CyanAccent
                    "Changes", "Git" -> TerminalGreen
                    "Editor" -> ElectricBlue
                    "Terminal" -> WarningAmber
                    "Settings" -> TextSecondary
                    else -> TextSecondary
                  },
                  modifier = Modifier.size(16.dp)
                )
              }

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
