package com.agentisco.ui.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentisco.core.model.AppDestination
import com.agentisco.data.local.chat.AgentSessionEntity
import com.agentisco.data.model.Project
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.theme.*

@Composable
fun ProjectsScreen(
  viewModel: WorkspaceViewModel,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  val projects by viewModel.projects.collectAsState()
  val activeProject by viewModel.activeProject.collectAsState()
  val recentActivity by viewModel.recentActivity.collectAsState()
  val previewSessions by viewModel.projectSessionsPreview.collectAsState()

  var showNewProjectDialog by remember { mutableStateOf(false) }
  var selectedProjectForOverview by remember { mutableStateOf<Project?>(null) }
  var sessionsProject by remember { mutableStateOf<Project?>(null) }

  // Re-validate root folders when the screen is shown (moved/deleted dirs).
  LaunchedEffect(Unit) { viewModel.refreshProjects() }

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
      .padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    item {
      Spacer(modifier = Modifier.height(12.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text(
            text = "Your Workspace",
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp
          )
          Text(
            text = if (projects.isEmpty()) "No projects yet — create or import a folder"
            else "~/projects · ${projects.size} project${if (projects.size == 1) "" else "s"}",
            color = TextMuted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
          )
        }

        IconButton(
          onClick = { onNavigate(AppDestination.SETTINGS) },
          modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurfaceElevated)
            .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
        ) {
          Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = "Settings",
            tint = TextSecondary,
            modifier = Modifier.size(18.dp)
          )
        }
      }
    }

    // New / Import buttons
    item {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = { showNewProjectDialog = true },
          modifier = Modifier
            .weight(1f)
            .height(48.dp)
            .testTag("btn_new_project"),
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
          shape = RoundedCornerShape(10.dp)
        ) {
          Icon(imageVector = Icons.Default.Add, contentDescription = "New", modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("New Project", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
          onClick = { showNewProjectDialog = true },
          modifier = Modifier
            .weight(1f)
            .height(48.dp)
            .testTag("btn_import_folder"),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
          border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
          shape = RoundedCornerShape(10.dp)
        ) {
          Icon(imageVector = Icons.Outlined.FolderOpen, contentDescription = "Import", modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("Open Folder", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
      }
    }

    // Section: Recent Projects
    if (projects.isNotEmpty()) {
      item {
        Text(
          text = "Projects",
          color = TextSecondary,
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(top = 4.dp)
        )
      }
    }

    items(projects, key = { it.id }) { project ->
      val isActive = project.id == activeProject.id

      Card(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .border(
            1.dp,
            when {
              project.isMissing -> DangerRed.copy(alpha = 0.5f)
              isActive -> ElectricBlue.copy(alpha = 0.6f)
              else -> DarkBorder
            },
            RoundedCornerShape(12.dp)
          )
          .clickable {
            viewModel.selectProject(project)
            selectedProjectForOverview = project
          }
          .testTag("project_card_${project.id}"),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
              Box(
                modifier = Modifier
                  .size(10.dp)
                  .clip(CircleShape)
                  .background(
                    when {
                      project.isMissing -> DangerRed
                      project.isDirty -> WarningAmber
                      else -> TerminalGreen
                    }
                  )
              )
              Spacer(modifier = Modifier.width(10.dp))
              Text(
                text = project.name,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              if (project.isImported) {
                BadgeChip("IMPORTED", TextMuted, DarkSurfaceElevated)
              }
              if (isActive) {
                BadgeChip("CURRENT", ElectricBlueGlow, ElectricBlue.copy(alpha = 0.2f))
              }
            }
          }

          Spacer(modifier = Modifier.height(4.dp))

          // The real folder this project lives in — the project IS the folder.
          Text(
            text = if (project.isMissing) "Folder missing: ${project.path}" else project.path,
            color = if (project.isMissing) DangerRed else TextMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )

          if (project.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = project.description,
              color = TextSecondary,
              fontSize = 12.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }

          Spacer(modifier = Modifier.height(10.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = project.lastActivity,
                color = TextMuted,
                fontSize = 11.sp
              )
            }

            if (project.changedFilesCount > 0) {
              Box(
                modifier = Modifier
                  .clip(RoundedCornerShape(4.dp))
                  .background(DarkSurfaceElevated)
                  .padding(horizontal = 6.dp, vertical = 2.dp)
              ) {
                Text(
                  text = "${project.changedFilesCount} changed files",
                  color = WarningAmber,
                  fontSize = 10.sp,
                  fontFamily = FontFamily.Monospace
                )
              }
            }
          }
        }
      }
    }

    item {
      Spacer(modifier = Modifier.height(24.dp))
    }
  }

  // Project Overview Dialog
  selectedProjectForOverview?.let { proj ->
    AlertDialog(
      onDismissRequest = { selectedProjectForOverview = null },
      containerColor = DarkSurface,
      title = {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(proj.name, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
              proj.path,
              color = if (proj.isMissing) DangerRed else TextMuted,
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
          if (proj.changedFilesCount > 0) {
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(DarkSurfaceElevated)
                .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
              Text("${proj.changedFilesCount} changes", color = WarningAmber, fontSize = 11.sp)
            }
          }
        }
      },
      text = {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          if (proj.isMissing) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DangerRed.copy(alpha = 0.1f))
                .border(1.dp, DangerRed.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(10.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(Icons.Outlined.Warning, contentDescription = null, tint = DangerRed, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                "This project's folder is missing or was moved. Recreate it, or remove the project from the list.",
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
              )
            }
          }

          // Quick navigation tiles
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            ProjectActionTile(
              label = "Agent",
              icon = Icons.Outlined.AutoAwesome,
              modifier = Modifier.weight(1f),
              enabled = !proj.isMissing,
              onClick = {
                viewModel.selectProject(proj)
                selectedProjectForOverview = null
                onNavigate(AppDestination.AGENT)
              }
            )
            ProjectActionTile(
              label = "Chats",
              icon = Icons.Outlined.Chat,
              modifier = Modifier.weight(1f),
              enabled = !proj.isMissing,
              onClick = {
                selectedProjectForOverview = null
                viewModel.selectProject(proj)
                sessionsProject = proj
              }
            )
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            ProjectActionTile(
              label = "Files",
              icon = Icons.Outlined.Folder,
              modifier = Modifier.weight(1f),
              enabled = !proj.isMissing,
              onClick = {
                viewModel.selectProject(proj)
                selectedProjectForOverview = null
                onNavigate(AppDestination.FILES)
              }
            )
            ProjectActionTile(
              label = "Terminal",
              icon = Icons.Outlined.Terminal,
              modifier = Modifier.weight(1f),
              enabled = !proj.isMissing,
              onClick = {
                viewModel.selectProject(proj)
                selectedProjectForOverview = null
                onNavigate(AppDestination.TERMINAL)
              }
            )
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            ProjectActionTile(
              label = "Changes",
              icon = Icons.Outlined.Difference,
              modifier = Modifier.weight(1f),
              enabled = !proj.isMissing,
              onClick = {
                viewModel.selectProject(proj)
                selectedProjectForOverview = null
                onNavigate(AppDestination.DIFF)
              }
            )
            ProjectActionTile(
              label = "Remove",
              icon = Icons.Outlined.Delete,
              modifier = Modifier.weight(1f),
              tint = DangerRed,
              onClick = {
                viewModel.removeProject(proj)
                selectedProjectForOverview = null
              }
            )
          }

          Spacer(modifier = Modifier.height(6.dp))
          Text("Recent Activity", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

          Column(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(DarkBackground)
              .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            if (recentActivity.isEmpty()) {
              Text(
                "No agent activity yet — open the Agent and give it a task.",
                color = TextMuted,
                fontSize = 11.sp
              )
            } else {
              recentActivity.forEach { block ->
                val color = when {
                  block.status == "failed" -> DangerRed
                  block.status == "success" -> TerminalGreen
                  else -> TextMuted
                }
                Text(
                  text = "● ${activityLabel(block.name, block.summary)} — ${relativeActivityTime(block.createdAt)}",
                  color = color,
                  fontSize = 11.sp,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.selectProject(proj)
            selectedProjectForOverview = null
            onNavigate(AppDestination.AGENT)
          },
          enabled = !proj.isMissing,
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
        ) {
          Text("Open in Agent", fontSize = 12.sp)
        }
      },
      dismissButton = {
        TextButton(onClick = { selectedProjectForOverview = null }) {
          Text("Close", color = TextMuted, fontSize = 12.sp)
        }
      }
    )
  }

  // Chats / Agent Sessions dialog for a specific project.
  sessionsProject?.let { proj ->
    LaunchedEffect(proj.id) { viewModel.previewSessionsFor(proj.path) }
    val sessions = previewSessions
    AlertDialog(
      onDismissRequest = {
        sessionsProject = null
        viewModel.previewSessionsFor(null)
      },
      containerColor = DarkSurface,
      title = {
        Column {
          Text("Agent Chats", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
          Text(proj.name, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      },
      text = {
        Column(modifier = Modifier.fillMaxWidth()) {
          if (sessions.isEmpty()) {
            Text(
              "No conversations yet for this project. Start one with the Agent.",
              color = TextMuted,
              fontSize = 12.sp,
              modifier = Modifier.padding(vertical = 12.dp)
            )
          } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              sessions.take(8).forEach { session ->
                SessionRow(
                  session = session,
                  onContinue = {
                    viewModel.continueSession(proj, session.id)
                    sessionsProject = null
                  },
                  onArchive = { viewModel.archiveChatSession(session.id) },
                  onDelete = { viewModel.deleteChatSession(session.id) }
                )
              }
              if (sessions.size > 8) {
                Text("…and ${sessions.size - 8} more", color = TextMuted, fontSize = 10.sp)
              }
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.selectProject(proj)
            viewModel.createChatSession()
            sessionsProject = null
            onNavigate(AppDestination.AGENT)
          },
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
        ) { Text("New Chat", fontSize = 12.sp) }
      },
      dismissButton = {
        TextButton(onClick = {
          sessionsProject = null
          viewModel.previewSessionsFor(null)
        }) { Text("Close", color = TextMuted, fontSize = 12.sp) }
      }
    )
  }

  // New Project / Open Folder Dialog
  if (showNewProjectDialog) {
    NewOrImportProjectDialog(
      onDismiss = { showNewProjectDialog = false },
      onCreate = { name, desc, location ->
        val created = viewModel.createProject(name, desc, location)
        showNewProjectDialog = false
        if (created != null) onNavigate(AppDestination.AGENT)
      },
      onImport = { path, name ->
        val imported = viewModel.importProject(path, name)
        showNewProjectDialog = false
        if (imported != null) onNavigate(AppDestination.AGENT)
      }
    )
  }
}

@Composable
private fun BadgeChip(text: String, color: Color, background: Color) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(background)
      .padding(horizontal = 6.dp, vertical = 2.dp)
  ) {
    Text(text, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
  }
}


@Composable
private fun SessionRow(
  session: AgentSessionEntity,
  onContinue: () -> Unit,
  onArchive: () -> Unit,
  onDelete: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(DarkBackground)
      .border(1.dp, DarkBorderSubtle, RoundedCornerShape(8.dp))
      .clickable(onClick = onContinue)
      .padding(horizontal = 10.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        session.title,
        color = TextPrimary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        "${relativeActivityTime(session.updatedAt)} · ${session.status}",
        color = TextMuted,
        fontSize = 9.sp
      )
    }
    TextButton(onClick = onContinue, contentPadding = PaddingValues(horizontal = 8.dp)) {
      Text("Continue", color = ElectricBlueGlow, fontSize = 11.sp)
    }
    IconButton(onClick = onArchive, modifier = Modifier.size(26.dp)) {
      Icon(Icons.Outlined.Inventory2, contentDescription = "Archive", tint = TextMuted, modifier = Modifier.size(13.dp))
    }
    IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) {
      Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = DangerRed.copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
    }
  }
}

@Composable
private fun NewOrImportProjectDialog(
  onDismiss: () -> Unit,
  onCreate: (name: String, desc: String, location: String?) -> Unit,
  onImport: (path: String, name: String?) -> Unit
) {
  var mode by remember { mutableStateOf("create") } // create | import
  var name by remember { mutableStateOf("") }
  var desc by remember { mutableStateOf("") }
  var location by remember { mutableStateOf("") }
  var importPath by remember { mutableStateOf("") }
  var importName by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = DarkSurface,
    title = {
      Text(
        if (mode == "create") "Create New Project" else "Open Existing Folder",
        color = TextPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold
      )
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        // Mode switch
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DarkBackground)
            .padding(4.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(6.dp))
              .background(if (mode == "create") ElectricBlue.copy(alpha = 0.25f) else Color.Transparent)
              .clickable { mode = "create" }
              .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
          ) {
            Text("Create new", color = if (mode == "create") ElectricBlueGlow else TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
          }
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(6.dp))
              .background(if (mode == "import") ElectricBlue.copy(alpha = 0.25f) else Color.Transparent)
              .clickable { mode = "import" }
              .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
          ) {
            Text("Import folder", color = if (mode == "import") ElectricBlueGlow else TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
          }
        }

        if (mode == "create") {
          OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Project Name", fontSize = 11.sp) },
            placeholder = { Text("e.g. cloud-sync-app", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
          OutlinedTextField(
            value = desc,
            onValueChange = { desc = it },
            label = { Text("Description", fontSize = 11.sp) },
            placeholder = { Text("Short description of what the project does", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
          OutlinedTextField(
            value = location,
            onValueChange = { location = it },
            label = { Text("Folder location (optional)", fontSize = 11.sp) },
            placeholder = { Text("Leave empty for ~/projects/<name>", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
          Text(
            "The project is a real folder on this device. Default: internal app storage under ~/projects. You can point to another accessible directory (e.g. /sdcard/MyProjects). `~` expands to the projects root.",
            color = TextMuted,
            fontSize = 9.sp,
            lineHeight = 13.sp
          )
        } else {
          OutlinedTextField(
            value = importPath,
            onValueChange = { importPath = it },
            label = { Text("Folder path", fontSize = 11.sp) },
            placeholder = { Text("/sdcard/MyProjects/my-app", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
          OutlinedTextField(
            value = importName,
            onValueChange = { importName = it },
            label = { Text("Display name (optional)", fontSize = 11.sp) },
            placeholder = { Text("Defaults to the folder name", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
          Text(
            "The folder must be readable by the app. Nothing is moved or modified — Agentisco just opens it in place.",
            color = TextMuted,
            fontSize = 9.sp,
            lineHeight = 13.sp
          )
        }

        error?.let {
          Text(it, color = DangerRed, fontSize = 11.sp)
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (mode == "create") {
            if (name.isBlank()) {
              error = "Project name is required."
            } else {
              onCreate(name.trim(), desc.trim(), location.trim().takeIf { it.isNotBlank() })
            }
          } else {
            if (importPath.isBlank()) {
              error = "Folder path is required."
            } else {
              onImport(importPath.trim(), importName.trim().takeIf { it.isNotBlank() })
            }
          }
        },
        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
      ) {
        Text(if (mode == "create") "Create & Open" else "Open Folder", fontSize = 12.sp)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel", color = TextMuted, fontSize = 12.sp)
      }
    }
  )
}

@Composable
private fun ProjectActionTile(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  tint: Color = ElectricBlueGlow,
  onClick: () -> Unit
) {
  Box(
    modifier = modifier
      .height(54.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(DarkSurfaceElevated)
      .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
      .clickable(enabled = enabled, onClick = onClick)
      .padding(8.dp),
    contentAlignment = Alignment.Center
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = tint,
        modifier = Modifier.size(16.dp)
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = label,
        color = if (enabled) TextPrimary else TextMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium
      )
    }
  }
}

// ---- persisted-activity helpers ----

private fun activityLabel(toolName: String, summary: String): String {
  val detail = summary.take(40)
  return when (toolName) {
    "read_file" -> "Read a file"
    "write_file", "create_file" -> "Modified a file"
    "edit_file" -> "Edited a file"
    "delete_file" -> "Deleted a file"
    "move_file" -> "Moved a file"
    "search_files" -> "Searched files"
    "run_command" -> "Ran a command"
    "build" -> "Ran a build"
    "test" -> "Ran tests"
    "run" -> "Started dev server"
    "git_status" -> "Checked git status"
    "git_diff" -> "Reviewed git diff"
    "git_stage" -> "Staged changes"
    "git_commit" -> "Committed changes"
    else -> if (toolName.isNotBlank()) "Used $toolName" else detail.ifBlank { "Agent activity" }
  }
}

private fun relativeActivityTime(timestamp: Long): String {
  if (timestamp <= 0) return "unknown"
  val minutes = (System.currentTimeMillis() - timestamp) / 60000
  return when {
    minutes < 1 -> "just now"
    minutes < 60 -> "$minutes min ago"
    minutes < 60 * 24 -> "${minutes / 60}h ago"
    else -> "${minutes / (60 * 24)}d ago"
  }
}
