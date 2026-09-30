package com.agentisco.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentisco.core.model.AppDestination
import com.agentisco.data.local.chat.AgentSessionEntity
import com.agentisco.data.model.Project
import com.agentisco.data.model.ProjectKind
import com.agentisco.data.model.WorkspaceStorageInfo
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.theme.*
import com.agentisco.ui.util.rememberProjectIcon
import com.agentisco.workspace.filesystem.WorkspaceStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Secondary per-project actions that live behind the card's overflow menu. */
enum class ProjectOverflowAction {
  OPEN_IN_AGENT,
  CHATS,
  FILES,
  TERMINAL,
  CHANGES,
  SAVE_TO_ORIGINAL,
  REMOVE
}

/**
 * Stateful entry point used by `MainActivity`: owns the dialogs and the
 * clipboard, and renders [ProjectsScreenContent] with live data.
 */
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
  val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

  // Dialog mode: null = closed, otherwise the tab the dialog opens on.
  var dialogMode by remember { mutableStateOf<String?>(null) }
  var selectedProjectForOverview by remember { mutableStateOf<Project?>(null) }
  var sessionsProject by remember { mutableStateOf<Project?>(null) }
  var pendingRemoval by remember { mutableStateOf<Project?>(null) }

  // Re-validate root folders when the screen is shown (moved/deleted dirs).
  LaunchedEffect(Unit) { viewModel.refreshProjects() }

  // Free/total space is read from disk, so it never blocks the first frame.
  val storage by produceState<WorkspaceStorageInfo?>(initialValue = null) {
    value = withContext(Dispatchers.IO) { WorkspaceStorage.read(viewModel.repository.projectsRoot) }
  }

  val workspacePath = viewModel.repository.guestPathFor(viewModel.repository.projectsRoot.absolutePath)

  ProjectsScreenContent(
    projects = projects,
    activeProject = activeProject,
    workspacePath = workspacePath,
    storage = storage,
    onNewProject = { dialogMode = "create" },
    onOpenFolder = { dialogMode = "import" },
    onProjectClick = { project ->
      viewModel.selectProject(project)
      selectedProjectForOverview = project
    },
    onCopyPath = { path -> clipboard.setText(androidx.compose.ui.text.AnnotatedString(path)) },
    onProjectAction = { project, action ->
      when (action) {
        ProjectOverflowAction.OPEN_IN_AGENT -> {
          viewModel.selectProject(project)
          onNavigate(AppDestination.AGENT)
        }
        ProjectOverflowAction.CHATS -> {
          viewModel.selectProject(project)
          sessionsProject = project
        }
        ProjectOverflowAction.FILES -> {
          viewModel.selectProject(project)
          onNavigate(AppDestination.FILES)
        }
        ProjectOverflowAction.TERMINAL -> {
          viewModel.selectProject(project)
          onNavigate(AppDestination.TERMINAL)
        }
        ProjectOverflowAction.CHANGES -> {
          viewModel.selectProject(project)
          onNavigate(AppDestination.DIFF)
        }
        ProjectOverflowAction.SAVE_TO_ORIGINAL -> viewModel.syncProjectToSource(project)
        ProjectOverflowAction.REMOVE -> pendingRemoval = project
      }
    },
    modifier = modifier
  )

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
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                proj.path,
                color = if (proj.isMissing) DangerRed else TextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
              )
              Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = "Copy project path",
                tint = TextMuted,
                modifier = Modifier
                  .padding(start = 4.dp)
                  .size(11.dp)
                  .clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(proj.path)) }
              )
            }
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
          if (proj.sourcePath.isNotBlank()) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DarkBackground)
                .border(1.dp, DarkBorderSubtle, RoundedCornerShape(8.dp))
                .padding(10.dp)
            ) {
              Text(
                "Original folder",
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
              )
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                  proj.sourcePath,
                  color = TextMuted,
                  fontSize = 10.sp,
                  fontFamily = FontFamily.Monospace,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f, fill = false)
                )
                Icon(
                  Icons.Outlined.ContentCopy,
                  contentDescription = "Copy original folder path",
                  tint = TextMuted,
                  modifier = Modifier
                    .padding(start = 4.dp)
                    .size(11.dp)
                    .clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(proj.sourcePath)) }
                )
              }
              Spacer(modifier = Modifier.height(6.dp))
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(
                    "Auto-save changes there",
                    color = TextSecondary,
                    fontSize = 11.sp
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Switch(
                    checked = proj.autoSyncToSource,
                    onCheckedChange = { viewModel.setProjectAutoSync(proj.id, it) },
                    modifier = Modifier.testTag("switch_auto_sync")
                  )
                }
                TextButton(onClick = { viewModel.syncProjectToSource(proj) }) {
                  Text("Save to folder", color = ElectricBlueGlow, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
              }
            }
          }

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
              icon = Icons.AutoMirrored.Outlined.Chat,
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
                pendingRemoval = proj
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

  // Delete-project confirmation: removal is permanent, so it never happens
  // without an explicit yes.
  pendingRemoval?.let { proj ->
    AlertDialog(
      onDismissRequest = { pendingRemoval = null },
      containerColor = DarkSurface,
      title = {
        Text("Remove project?", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
      },
      text = {
        Column(modifier = Modifier.fillMaxWidth()) {
          Text(
            "\"${proj.name}\" and all of its files will be permanently deleted from " +
              "~/projects/${File(proj.path).name}, along with this project's agent conversations.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp
          )
          if (proj.sourcePath.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              "The original folder you imported it from is kept: ${proj.sourcePath}",
              color = TextMuted,
              fontSize = 11.sp
            )
          }
          Spacer(modifier = Modifier.height(8.dp))
          Text("This cannot be undone.", color = DangerRed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.removeProject(proj)
            pendingRemoval = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
        ) { Text("Remove", fontSize = 12.sp) }
      },
      dismissButton = {
        TextButton(onClick = { pendingRemoval = null }) {
          Text("Cancel", color = TextMuted, fontSize = 12.sp)
        }
      }
    )
  }

  // New Project / Open Folder Dialog
  dialogMode?.let { mode ->
    NewOrImportProjectDialog(
      initialMode = mode,
      onDismiss = { dialogMode = null },
      onCreate = { name, desc, _ ->
        val created = viewModel.createProject(name, desc)
        dialogMode = null
        if (created != null) onNavigate(AppDestination.AGENT)
      },
      onImport = { path, name ->
        // Copying runs in the background; navigate once the import finishes so
        // a huge folder can never block the dialog closing.
        dialogMode = null
        viewModel.importProject(path, name) { imported ->
          if (imported != null) onNavigate(AppDestination.AGENT)
        }
      },
      onImportZip = { uri, name ->
        viewModel.importZipProject(uri, name) { imported ->
          dialogMode = null
          if (imported != null) onNavigate(AppDestination.AGENT)
        }
      }
    )
  }
}

/**
 * Stateless workspace screen: everything it needs arrives as parameters, so it
 * can be rendered from tests with any [Project] list (see
 * `ProjectsScreenScreenshotTest`).
 */
@Composable
fun ProjectsScreenContent(
  projects: List<Project>,
  activeProject: Project,
  workspacePath: String,
  storage: WorkspaceStorageInfo?,
  onNewProject: () -> Unit,
  onOpenFolder: () -> Unit,
  onProjectClick: (Project) -> Unit,
  onCopyPath: (String) -> Unit,
  onProjectAction: (Project, ProjectOverflowAction) -> Unit,
  modifier: Modifier = Modifier
) {
  var isGridView by rememberSaveable { mutableStateOf(false) }

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
      .padding(horizontal = 14.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    item {
      Spacer(modifier = Modifier.height(6.dp))
      WorkspaceOverview(
        projectCount = projects.size,
        workspacePath = workspacePath,
        storage = storage
      )
    }

    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Button(
          onClick = onNewProject,
          modifier = Modifier
            .weight(1f)
            .height(36.dp)
            .testTag("btn_new_project"),
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
          shape = RoundedCornerShape(8.dp)
        ) {
          Icon(imageVector = Icons.Default.Add, contentDescription = "New", modifier = Modifier.size(15.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("New Project", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
          onClick = onOpenFolder,
          modifier = Modifier
            .weight(1f)
            .height(36.dp)
            .testTag("btn_import_folder"),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
          border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
          shape = RoundedCornerShape(8.dp)
        ) {
          Icon(imageVector = Icons.Outlined.FolderOpen, contentDescription = "Import", modifier = Modifier.size(15.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Open Folder", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
      }
    }

    if (projects.isEmpty()) {
      item { EmptyWorkspaceHint() }
    } else {
      item {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "PROJECTS",
            color = TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
          )
          Spacer(modifier = Modifier.width(6.dp))
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(4.dp))
              .background(DarkSurfaceElevated)
              .padding(horizontal = 5.dp, vertical = 1.dp)
          ) {
            Text(
              text = "${projects.size}",
              color = TextSecondary,
              fontSize = 9.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Medium
            )
          }
          Spacer(modifier = Modifier.width(8.dp))
          HorizontalDivider(color = DarkBorderSubtle, modifier = Modifier.weight(1f))
          Spacer(modifier = Modifier.width(8.dp))

          // View toggle: Linear vs Grid
          Row(
            modifier = Modifier
              .clip(RoundedCornerShape(7.dp))
              .background(DarkSurface)
              .border(1.dp, DarkBorderSubtle, RoundedCornerShape(7.dp))
              .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
              modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (!isGridView) ElectricBlue.copy(alpha = 0.22f) else Color.Transparent)
                .clickable { isGridView = false }
                .testTag("btn_linear_view"),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Outlined.ViewList,
                contentDescription = "List view",
                tint = if (!isGridView) ElectricBlueGlow else TextMuted,
                modifier = Modifier.size(14.dp)
              )
            }
            Box(
              modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (isGridView) ElectricBlue.copy(alpha = 0.22f) else Color.Transparent)
                .clickable { isGridView = true }
                .testTag("btn_grid_view"),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Outlined.GridView,
                contentDescription = "Grid view",
                tint = if (isGridView) ElectricBlueGlow else TextMuted,
                modifier = Modifier.size(13.dp)
              )
            }
          }
        }
      }

      if (isGridView) {
        val pairs = projects.chunked(2)
        items(pairs, key = { row -> row.joinToString("_") { it.id } }) { row ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            for (proj in row) {
              Box(modifier = Modifier.weight(1f)) {
                ProjectGridCard(
                  project = proj,
                  isActive = proj.id == activeProject.id,
                  onClick = { onProjectClick(proj) },
                  onAction = { action -> onProjectAction(proj, action) }
                )
              }
            }
            if (row.size == 1) {
              Spacer(modifier = Modifier.weight(1f))
            }
          }
        }
      } else {
        items(projects, key = { it.id }) { project ->
          ProjectCard(
            project = project,
            isActive = project.id == activeProject.id,
            onClick = { onProjectClick(project) },
            onCopyPath = onCopyPath,
            onAction = { action -> onProjectAction(project, action) }
          )
        }
      }
    }

    item {
      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

/**
 * Compact "Your Workspace" summary: reduced height, cute glowing folder badge,
 * path, and compact storage indicator.
 */
@Composable
private fun WorkspaceOverview(
  projectCount: Int,
  workspacePath: String,
  storage: WorkspaceStorageInfo?
) {
  val shape = RoundedCornerShape(10.dp)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(DarkSurface)
      .border(1.dp, DarkBorderSubtle, shape)
      .padding(horizontal = 10.dp, vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(
      modifier = Modifier
        .size(28.dp)
        .clip(RoundedCornerShape(7.dp))
        .background(TerminalGreen.copy(alpha = 0.12f))
        .border(1.dp, TerminalGreen.copy(alpha = 0.3f), RoundedCornerShape(7.dp)),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = Icons.Outlined.Folder,
        contentDescription = null,
        tint = TerminalGreen,
        modifier = Modifier.size(15.dp)
      )
    }

    Spacer(modifier = Modifier.width(9.dp))

    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "Your Workspace",
          color = TextPrimary,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(DarkSurfaceHighlight)
            .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
          Text(
            text = "$projectCount project${if (projectCount == 1) "" else "s"}",
            color = TextSecondary,
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
          )
        }
      }
      Spacer(modifier = Modifier.height(1.dp))
      Text(
        text = workspacePath,
        color = TextMuted,
        fontSize = 9.5.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }

    Spacer(modifier = Modifier.width(8.dp))

    if (storage != null) {
      Column(horizontalAlignment = Alignment.End) {
        Text(
          text = "${formatBytes(storage.usedBytes)} of ${formatBytes(storage.totalBytes)}",
          color = when {
            storage.usedFraction > 0.9f -> DangerRed
            storage.usedFraction > 0.75f -> WarningAmber
            else -> TerminalGreen
          },
          fontSize = 9.5.sp,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(1.dp))
        Text(
          text = "${formatBytes(storage.freeBytes)} free",
          color = TextMuted,
          fontSize = 8.5.sp,
          fontFamily = FontFamily.Monospace,
          maxLines = 1
        )
        Spacer(modifier = Modifier.height(3.dp))
        Box(
          modifier = Modifier
            .width(52.dp)
            .height(2.5.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(DarkSurfaceHighlight)
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth(storage.usedFraction)
              .fillMaxHeight()
              .background(
                if (storage.usedFraction > 0.9f) DangerRed else ElectricBlue
              )
          )
        }
      }
    } else {
      Text(
        text = "—",
        color = TextMuted,
        fontSize = 9.5.sp,
        fontFamily = FontFamily.Monospace
      )
    }
  }
}

@Composable
private fun EmptyWorkspaceHint() {
  val shape = RoundedCornerShape(10.dp)
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(DarkSurface)
      .border(1.dp, DarkBorderSubtle, shape)
      .padding(horizontal = 14.dp, vertical = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Icon(
      imageVector = Icons.Outlined.Terminal,
      contentDescription = null,
      tint = TextMuted,
      modifier = Modifier.size(22.dp)
    )
    Spacer(modifier = Modifier.height(7.dp))
    Text(
      text = "No projects yet",
      color = TextPrimary,
      fontSize = 12.5.sp,
      fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(3.dp))
    Text(
      text = "Create a project or open an existing folder — it is copied into the Linux workspace so git, builds and terminals behave like on a desktop.",
      color = TextMuted,
      fontSize = 9.5.sp,
      lineHeight = 13.5.sp
    )
  }
}

/**
 * Compact linear project row: minimal wasted space, crisp icon, subtle badges,
 * clean path, and aligned action buttons.
 */
@Composable
private fun ProjectCard(
  project: Project,
  isActive: Boolean,
  onClick: () -> Unit,
  onCopyPath: (String) -> Unit,
  onAction: (ProjectOverflowAction) -> Unit
) {
  var menuOpen by remember { mutableStateOf(false) }
  val shape = RoundedCornerShape(10.dp)

  val container = when {
    isActive -> lerp(DarkSurface, ElectricBlue, 0.08f)
    project.isMissing -> lerp(DarkSurface, DangerRed, 0.05f)
    else -> DarkSurface
  }
  val borderColor = when {
    project.isMissing -> DangerRed.copy(alpha = 0.5f)
    isActive -> ElectricBlue.copy(alpha = 0.75f)
    else -> DarkBorderSubtle
  }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .border(if (isActive) 1.5.dp else 1.dp, borderColor, shape)
      .clickable(onClick = onClick)
      .testTag("project_card_${project.id}"),
    colors = CardDefaults.cardColors(containerColor = container),
    shape = shape,
    elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 1.dp else 0.dp)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 9.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      ProjectIcon(project = project, size = 32.dp)

      Spacer(modifier = Modifier.width(9.dp))

      Column(modifier = Modifier.weight(1f)) {
        // Line 1: Name, Status badges, and subtle KindChip
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = project.name,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
          )
          if (isActive) {
            Spacer(modifier = Modifier.width(5.dp))
            StatusBadge("CURRENT", ElectricBlueGlow, ElectricBlue.copy(alpha = 0.2f))
          }
          if (project.isMissing) {
            Spacer(modifier = Modifier.width(5.dp))
            StatusBadge("MISSING", DangerRed, DangerRed.copy(alpha = 0.18f))
          } else if (project.isImported) {
            Spacer(modifier = Modifier.width(5.dp))
            StatusBadge("IMPORTED", TextMuted, DarkSurfaceElevated)
          }
          if (project.changedFilesCount > 0) {
            Spacer(modifier = Modifier.width(5.dp))
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(WarningAmber.copy(alpha = 0.14f))
                .border(1.dp, WarningAmber.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
              Text(
                text = "+${project.changedFilesCount}",
                color = WarningAmber,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
              )
            }
          }
          if (!project.isMissing && project.kind != ProjectKind.UNKNOWN) {
            Spacer(modifier = Modifier.width(5.dp))
            KindChip(project.kind)
          }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Line 2: Path · Size · Modified
        val sizeLabel = if (project.sizeBytes >= 0) formatBytes(project.sizeBytes) else null
        val modifiedLabel = if (project.lastModified > 0) relativeModified(project.lastModified) else null
        val facts = listOfNotNull(sizeLabel, modifiedLabel)

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = project.path,
            color = if (project.isMissing) DangerRed else TextMuted,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
          )
          if (facts.isNotEmpty()) {
            MetaSeparator()
            Text(
              text = facts.joinToString(" · "),
              color = TextSecondary,
              fontSize = 9.sp,
              fontFamily = FontFamily.Monospace,
              maxLines = 1
            )
          }
        }
      }

      Spacer(modifier = Modifier.width(6.dp))

      // Compact right actions
      CardAction(
        icon = Icons.Outlined.ContentCopy,
        contentDescription = "Copy project path",
        tint = TextSecondary,
        size = 26.dp,
        iconSize = 13.dp,
        onClick = { onCopyPath(project.path) }
      )
      Box {
        CardAction(
          icon = Icons.Outlined.MoreVert,
          contentDescription = "More actions for ${project.name}",
          tint = TextSecondary,
          size = 26.dp,
          iconSize = 15.dp,
          testTag = "project_overflow_${project.id}",
          onClick = { menuOpen = true }
        )
        ProjectOverflowMenu(
          project = project,
          expanded = menuOpen,
          onDismiss = { menuOpen = false },
          onAction = onAction
        )
      }
    }
  }
}

/**
 * Compact 2-column project grid card for modern developer aesthetic.
 */
@Composable
private fun ProjectGridCard(
  project: Project,
  isActive: Boolean,
  onClick: () -> Unit,
  onAction: (ProjectOverflowAction) -> Unit
) {
  var menuOpen by remember { mutableStateOf(false) }
  val shape = RoundedCornerShape(10.dp)
  val container = when {
    isActive -> lerp(DarkSurface, ElectricBlue, 0.08f)
    project.isMissing -> lerp(DarkSurface, DangerRed, 0.06f)
    else -> DarkSurface
  }
  val borderColor = when {
    project.isMissing -> DangerRed.copy(alpha = 0.5f)
    isActive -> ElectricBlue.copy(alpha = 0.75f)
    else -> DarkBorderSubtle
  }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .border(if (isActive) 1.5.dp else 1.dp, borderColor, shape)
      .clickable(onClick = onClick)
      .testTag("project_card_${project.id}"),
    colors = CardDefaults.cardColors(containerColor = container),
    shape = shape,
    elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 1.dp else 0.dp)
  ) {
    Column(modifier = Modifier.padding(9.dp)) {
      // Top row: Project icon + Kind + Overflow button
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        ProjectIcon(project = project, size = 28.dp)
        Spacer(modifier = Modifier.width(6.dp))
        if (!project.isMissing && project.kind != ProjectKind.UNKNOWN) {
          KindChip(project.kind)
        }
        Spacer(modifier = Modifier.weight(1f))
        Box {
          CardAction(
            icon = Icons.Outlined.MoreVert,
            contentDescription = "More actions for ${project.name}",
            tint = TextSecondary,
            size = 24.dp,
            iconSize = 14.dp,
            testTag = "project_overflow_${project.id}",
            onClick = { menuOpen = true }
          )
          ProjectOverflowMenu(
            project = project,
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            onAction = onAction
          )
        }
      }

      Spacer(modifier = Modifier.height(6.dp))

      // Title & Status
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = project.name,
          color = TextPrimary,
          fontSize = 12.5.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false)
        )
        if (isActive) {
          Spacer(modifier = Modifier.width(4.dp))
          StatusBadge("CURRENT", ElectricBlueGlow, ElectricBlue.copy(alpha = 0.2f))
        }
        if (project.isMissing) {
          Spacer(modifier = Modifier.width(4.dp))
          StatusBadge("MISSING", DangerRed, DangerRed.copy(alpha = 0.18f))
        }
      }

      Spacer(modifier = Modifier.height(2.dp))

      // Monospace Path
      Text(
        text = project.path,
        color = if (project.isMissing) DangerRed else TextMuted,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )

      Spacer(modifier = Modifier.height(5.dp))

      // Facts & Changes
      val sizeLabel = if (project.sizeBytes >= 0) formatBytes(project.sizeBytes) else null
      val modifiedLabel = if (project.lastModified > 0) relativeModified(project.lastModified) else null
      val facts = listOfNotNull(sizeLabel, modifiedLabel)

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = facts.joinToString(" · ").ifBlank { "—" },
          color = TextSecondary,
          fontSize = 8.5.sp,
          fontFamily = FontFamily.Monospace,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
        if (project.changedFilesCount > 0) {
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(3.dp))
              .background(WarningAmber.copy(alpha = 0.15f))
              .border(1.dp, WarningAmber.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
              .padding(horizontal = 3.5.dp, vertical = 0.5.dp)
          ) {
            Text(
              text = "+${project.changedFilesCount}",
              color = WarningAmber,
              fontSize = 8.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold
            )
          }
        }
      }
    }
  }
}

/** Shared overflow menu for projects. */
@Composable
private fun ProjectOverflowMenu(
  project: Project,
  expanded: Boolean,
  onDismiss: () -> Unit,
  onAction: (ProjectOverflowAction) -> Unit
) {
  DropdownMenu(
    expanded = expanded,
    onDismissRequest = onDismiss,
    containerColor = DarkSurfaceElevated
  ) {
    val disabled = project.isMissing
    OverflowItem("Open in Agent", Icons.Outlined.AutoAwesome, enabled = !disabled) {
      onDismiss(); onAction(ProjectOverflowAction.OPEN_IN_AGENT)
    }
    OverflowItem("Chats", Icons.AutoMirrored.Outlined.Chat, enabled = !disabled) {
      onDismiss(); onAction(ProjectOverflowAction.CHATS)
    }
    OverflowItem("Files", Icons.Outlined.Folder, enabled = !disabled) {
      onDismiss(); onAction(ProjectOverflowAction.FILES)
    }
    OverflowItem("Terminal", Icons.Outlined.Terminal, enabled = !disabled) {
      onDismiss(); onAction(ProjectOverflowAction.TERMINAL)
    }
    OverflowItem("Changes", Icons.Outlined.Difference, enabled = !disabled) {
      onDismiss(); onAction(ProjectOverflowAction.CHANGES)
    }
    if (project.sourcePath.isNotBlank()) {
      OverflowItem("Save to original folder", Icons.Outlined.SaveAlt, enabled = !disabled) {
        onDismiss(); onAction(ProjectOverflowAction.SAVE_TO_ORIGINAL)
      }
    }
    HorizontalDivider(color = DarkBorderSubtle)
    OverflowItem("Remove project", Icons.Outlined.Delete, tint = DangerRed) {
      onDismiss(); onAction(ProjectOverflowAction.REMOVE)
    }
  }
}

/**
 * Card action button with configurable size and icon size.
 */
@Composable
private fun CardAction(
  icon: ImageVector,
  contentDescription: String,
  tint: Color,
  onClick: () -> Unit,
  size: Dp = 26.dp,
  iconSize: Dp = 14.dp,
  testTag: String? = null
) {
  Box(
    modifier = Modifier
      .size(size)
      .clip(RoundedCornerShape(6.dp))
      .clickable(onClick = onClick)
      .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    contentAlignment = Alignment.Center
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = tint,
      modifier = Modifier.size(iconSize)
    )
  }
}

@Composable
private fun MetaValue(text: String) {
  Text(
    text = text,
    color = TextSecondary,
    fontSize = 9.5.sp,
    fontFamily = FontFamily.Monospace
  )
}

@Composable
private fun OverflowItem(
  label: String,
  icon: ImageVector,
  enabled: Boolean = true,
  tint: Color = TextPrimary,
  onClick: () -> Unit
) {
  DropdownMenuItem(
    text = {
      Text(
        text = label,
        fontSize = 12.sp,
        color = if (enabled) tint else TextMuted
      )
    },
    leadingIcon = {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (enabled) tint.copy(alpha = 0.75f) else TextMuted,
        modifier = Modifier.size(15.dp)
      )
    },
    onClick = onClick,
    enabled = enabled
  )
}

/**
 * Project icon: displays favicon / logo when present in the project folder,
 * otherwise a stylish monogram derived from the project name so the project icon
 * remains the primary visual identifier.
 */
@Composable
private fun ProjectIcon(project: Project, size: Dp) {
  val bitmap = rememberProjectIcon(project.iconPath)
  val shape = RoundedCornerShape(size / 3.5f)
  val accent = accentForName(project.name)

  Box(
    modifier = Modifier
      .size(size)
      .clip(shape)
      .background(if (bitmap != null) DarkSurfaceHighlight else accent.copy(alpha = 0.16f))
      .border(1.dp, if (bitmap != null) DarkBorder else accent.copy(alpha = 0.35f), shape),
    contentAlignment = Alignment.Center
  ) {
    if (bitmap != null) {
      Image(
        bitmap = bitmap,
        contentDescription = "${project.name} icon",
        contentScale = ContentScale.Fit,
        modifier = Modifier
          .fillMaxSize()
          .padding(2.5.dp)
      )
    } else {
      Text(
        text = project.name.trim().take(1).uppercase().ifBlank { "·" },
        color = accent,
        fontSize = (size.value * 0.44f).sp,
        fontWeight = FontWeight.ExtraBold
      )
    }
  }
}

/**
 * Subtle project type chip (e.g. Node, Python, Android) with a mini colored dot,
 * keeping the type informative yet subtle so the project icon remains the hero.
 */
@Composable
private fun KindChip(kind: ProjectKind) {
  val dotColor = accentForKind(kind)
  Row(
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(DarkSurfaceHighlight.copy(alpha = 0.75f))
      .border(1.dp, DarkBorderSubtle, RoundedCornerShape(4.dp))
      .padding(horizontal = 4.5.dp, vertical = 1.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(
      modifier = Modifier
        .size(4.dp)
        .clip(CircleShape)
        .background(dotColor)
    )
    Spacer(modifier = Modifier.width(3.5.dp))
    Text(
      text = kind.label,
      color = TextSecondary,
      fontSize = 8.5.sp,
      fontWeight = FontWeight.Medium
    )
  }
}

@Composable
private fun MetaSeparator() {
  Text(
    text = "·",
    color = TextMuted,
    fontSize = 9.sp,
    modifier = Modifier.padding(horizontal = 4.dp)
  )
}

@Composable
private fun StatusBadge(text: String, color: Color, background: Color) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(background)
      .padding(horizontal = 4.5.dp, vertical = 1.dp)
  ) {
    Text(text = text, color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold)
  }
}

// ---- icon / formatting helpers ----

private fun iconForKind(kind: ProjectKind): ImageVector = when (kind) {
  ProjectKind.ANDROID -> Icons.Outlined.Smartphone
  ProjectKind.GRADLE -> Icons.Outlined.Build
  ProjectKind.NODE -> Icons.Outlined.Javascript
  ProjectKind.FLUTTER -> Icons.Outlined.Widgets
  ProjectKind.RUST -> Icons.Outlined.Memory
  ProjectKind.GO -> Icons.Outlined.Code
  ProjectKind.PYTHON -> Icons.Outlined.Terminal
  ProjectKind.MAVEN -> Icons.Outlined.Inventory2
  ProjectKind.DOTNET -> Icons.Outlined.DesktopWindows
  ProjectKind.RUBY -> Icons.Outlined.Diamond
  ProjectKind.PHP -> Icons.Outlined.Language
  ProjectKind.CPP -> Icons.Outlined.Memory
  ProjectKind.GIT_REPO -> Icons.Outlined.AccountTree
  ProjectKind.UNKNOWN -> Icons.Outlined.Folder
}

private fun accentForKind(kind: ProjectKind): Color = when (kind) {
  ProjectKind.ANDROID -> TerminalGreen
  ProjectKind.NODE -> TerminalGreen
  ProjectKind.GRADLE -> CyanAccent
  ProjectKind.FLUTTER -> CyanAccent
  ProjectKind.GO -> CyanAccent
  ProjectKind.RUST -> WarningAmber
  ProjectKind.MAVEN -> WarningAmber
  ProjectKind.PYTHON -> ElectricBlueGlow
  ProjectKind.CPP -> ElectricBlueGlow
  ProjectKind.DOTNET -> IndigoAccent
  ProjectKind.PHP -> IndigoAccent
  ProjectKind.RUBY -> DangerRed
  ProjectKind.GIT_REPO -> TextSecondary
  ProjectKind.UNKNOWN -> TextMuted
}

/** Deterministic per-name accent so the same project always looks the same. */
private fun accentForName(name: String): Color {
  val palette = listOf(ElectricBlueGlow, CyanAccent, IndigoAccent, TerminalGreen, WarningAmber, DangerRed)
  val index = (name.fold(7) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }) % palette.size
  return palette[index]
}

/** "1.4 MB", "812 KB" … or a placeholder when the folder has not been measured. */
private fun formatBytes(bytes: Long): String {
  if (bytes < 0) return "—"
  if (bytes < 1024) return "$bytes B"
  val units = listOf("KB", "MB", "GB", "TB")
  var value = bytes.toDouble() / 1024
  var unit = 0
  while (value >= 1024 && unit < units.lastIndex) {
    value /= 1024
    unit++
  }
  return if (value >= 100) "${value.toInt()} ${units[unit]}"
  else String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
}

/** Project recency from the measured newest mtime; "—" when never measured. */
private fun relativeModified(timestamp: Long): String {
  if (timestamp <= 0) return "—"
  val minutes = (System.currentTimeMillis() - timestamp) / 60000
  return when {
    minutes < 1 -> "just now"
    minutes < 60 -> "${minutes}m ago"
    minutes < 60 * 24 -> "${minutes / 60}h ago"
    minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)}d ago"
    else -> "${minutes / (60 * 24 * 30)}mo ago"
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
  onImport: (path: String, name: String?) -> Unit,
  onImportZip: (uri: android.net.Uri, name: String?) -> Unit,
  initialMode: String = "create"
) {
  var mode by remember { mutableStateOf(initialMode) } // create | import | zip
  var name by remember { mutableStateOf("") }
  var desc by remember { mutableStateOf("") }
  var location by remember { mutableStateOf("") }
  var importPath by remember { mutableStateOf("") }
  var importName by remember { mutableStateOf("") }
  var zipUri by remember { mutableStateOf<android.net.Uri?>(null) }
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
            Text("Folder", color = if (mode == "import") ElectricBlueGlow else TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
          }
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(6.dp))
              .background(if (mode == "zip") ElectricBlue.copy(alpha = 0.25f) else Color.Transparent)
              .clickable { mode = "zip" }
              .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(".zip", color = if (mode == "zip") ElectricBlueGlow else TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
          Text(
            "The project lives in the app's Linux workspace (~/projects/<name>), giving tools like git full Linux compatibility. To work on an existing folder elsewhere, use the Import tab — changes can be synced back to it.",
            color = TextMuted,
            fontSize = 9.sp,
            lineHeight = 13.sp
          )
        } else if (mode == "import") {
          val context = LocalContext.current
          val folderPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
          ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            com.agentisco.ui.util.FolderPathResolver.takePersistablePermission(context, uri)
            val resolved = com.agentisco.ui.util.FolderPathResolver.resolve(context, uri)
            if (resolved != null) {
              importPath = resolved
              error = null
            } else {
              error = "That location can't be used as a project folder. Pick a folder on internal storage or the SD card."
            }
          }
          Button(
            onClick = { folderPicker.launch(null) },
            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
            modifier = Modifier.fillMaxWidth().testTag("btn_pick_folder")
          ) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Choose folder…", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
          }
          if (importPath.isNotBlank()) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(DarkBackground)
                .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
              Text(
                importPath,
                color = TerminalGreen,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
              )
            }
          }
          OutlinedTextField(
            value = importName,
            onValueChange = { importName = it },
            label = { Text("Display name (optional)", fontSize = 11.sp) },
            placeholder = { Text("Defaults to the folder name", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
          Text(
            "Browse the phone's storage and pick the project folder. Its contents are copied into the app's Linux workspace (~/projects) so git, builds and terminals work with full Linux compatibility. Changes can be saved back to the original folder — manually or automatically.",
            color = TextMuted,
            fontSize = 9.sp,
            lineHeight = 13.sp
          )
        } else if (mode == "zip") {
          val context = LocalContext.current
          val zipPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
          ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            zipUri = uri
            error = null
          }
          Button(
            onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
            modifier = Modifier.fillMaxWidth().testTag("btn_pick_zip")
          ) {
            Icon(Icons.Outlined.FolderZip, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Choose .zip file…", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
          }
          if (zipUri != null) {
            Text(
              "Archive selected — give it a name and import.",
              color = TerminalGreen,
              fontSize = 10.sp
            )
          }
          OutlinedTextField(
            value = importName,
            onValueChange = { importName = it },
            label = { Text("Project name (optional)", fontSize = 11.sp) },
            placeholder = { Text("Defaults to the archive/root folder name", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
          Text(
            "The archive is extracted in the app's Linux workspace. If it contains a single top-level folder, that becomes the project root; otherwise the archive root is used.",
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
          when (mode) {
            "create" -> {
              if (name.isBlank()) {
                error = "Project name is required."
              } else {
                onCreate(name.trim(), desc.trim(), null)
              }
            }
            "zip" -> {
              val uri = zipUri
              if (uri == null) {
                error = "Choose a .zip file first."
              } else {
                onImportZip(uri, importName.trim().takeIf { it.isNotBlank() })
              }
            }
            else -> {
              if (importPath.isBlank()) {
                error = "Choose a folder first."
              } else {
                onImport(importPath.trim(), importName.trim().takeIf { it.isNotBlank() })
              }
            }
          }
        },
        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
      ) {
        Text(
          when (mode) {
            "create" -> "Create & Open"
            "zip" -> "Import Zip"
            else -> "Open Folder"
          },
          fontSize = 12.sp
        )
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
