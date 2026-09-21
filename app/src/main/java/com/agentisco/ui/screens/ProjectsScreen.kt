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
  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
      .padding(horizontal = 14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      Spacer(modifier = Modifier.height(10.dp))
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
            .height(42.dp)
            .testTag("btn_new_project"),
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
          contentPadding = PaddingValues(horizontal = 12.dp),
          shape = RoundedCornerShape(10.dp)
        ) {
          Icon(imageVector = Icons.Default.Add, contentDescription = "New", modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(7.dp))
          Text("New Project", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
          onClick = onOpenFolder,
          modifier = Modifier
            .weight(1f)
            .height(42.dp)
            .testTag("btn_import_folder"),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
          border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
          contentPadding = PaddingValues(horizontal = 12.dp),
          shape = RoundedCornerShape(10.dp)
        ) {
          Icon(imageVector = Icons.Outlined.FolderOpen, contentDescription = "Import", modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(7.dp))
          Text("Open Folder", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
            .padding(top = 2.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "PROJECTS",
            color = TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
          )
          Spacer(modifier = Modifier.width(8.dp))
          HorizontalDivider(color = DarkBorderSubtle, modifier = Modifier.weight(1f))
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "${projects.size}",
            color = TextSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
          )
        }
      }
    }

    items(projects, key = { it.id }) { project ->
      ProjectCard(
        project = project,
        isActive = project.id == activeProject.id,
        onClick = { onProjectClick(project) },
        onCopyPath = onCopyPath,
        onAction = { action -> onProjectAction(project, action) }
      )
    }

    item {
      Spacer(modifier = Modifier.height(20.dp))
    }
  }
}

/**
 * "Your Workspace" summary: where the workspace lives, how many projects it
 * holds and how much room is left. One compact row — deliberately not a
 * dashboard card.
 */
@Composable
private fun WorkspaceOverview(
  projectCount: Int,
  workspacePath: String,
  storage: WorkspaceStorageInfo?
) {
  val shape = RoundedCornerShape(12.dp)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(DarkSurface)
      .border(1.dp, DarkBorderSubtle, shape)
      .padding(horizontal = 11.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(
      modifier = Modifier
        .size(32.dp)
        .clip(RoundedCornerShape(9.dp))
        .background(TerminalGreen.copy(alpha = 0.12f))
        .border(1.dp, TerminalGreen.copy(alpha = 0.3f), RoundedCornerShape(9.dp)),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = Icons.Outlined.Folder,
        contentDescription = null,
        tint = TerminalGreen,
        modifier = Modifier.size(17.dp)
      )
    }

    Spacer(modifier = Modifier.width(10.dp))

    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "Your Workspace",
          color = TextPrimary,
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
          text = "$projectCount project${if (projectCount == 1) "" else "s"}",
          color = TextMuted,
          fontSize = 10.sp,
          maxLines = 1
        )
      }
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = workspacePath,
        color = TextSecondary,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }

    Spacer(modifier = Modifier.width(10.dp))

    Column(horizontalAlignment = Alignment.End) {
      Text(
        text = storage?.let { "${formatBytes(it.freeBytes)} free" } ?: "—",
        color = when {
          storage == null -> TextMuted
          storage.usedFraction > 0.9f -> DangerRed
          storage.usedFraction > 0.75f -> WarningAmber
          else -> TerminalGreen
        },
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = storage?.let { "of ${formatBytes(it.totalBytes)}" } ?: "storage unavailable",
        color = TextMuted,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace
      )
      if (storage != null) {
        Spacer(modifier = Modifier.height(5.dp))
        Box(
          modifier = Modifier
            .width(64.dp)
            .height(3.dp)
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
    }
  }
}

@Composable
private fun EmptyWorkspaceHint() {
  val shape = RoundedCornerShape(12.dp)
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(DarkSurface)
      .border(1.dp, DarkBorderSubtle, shape)
      .padding(horizontal = 14.dp, vertical = 18.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Icon(
      imageVector = Icons.Outlined.Terminal,
      contentDescription = null,
      tint = TextMuted,
      modifier = Modifier.size(22.dp)
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = "No projects yet",
      color = TextPrimary,
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(3.dp))
    Text(
      text = "Create a project or open an existing folder — it is copied into the Linux workspace so git, builds and terminals behave like on a desktop.",
      color = TextMuted,
      fontSize = 10.sp,
      lineHeight = 14.sp
    )
  }
}

/**
 * One project, as a compact developer card: icon, name, status, path, kind,
 * size and recency, with the secondary actions behind an overflow menu so the
 * row itself stays clickable (opens the project overview).
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
  val shape = RoundedCornerShape(12.dp)

  val container = when {
    isActive -> lerp(DarkSurface, ElectricBlue, 0.07f)
    project.isMissing -> lerp(DarkSurface, DangerRed, 0.05f)
    else -> DarkSurface
  }
  val borderColor = when {
    project.isMissing -> DangerRed.copy(alpha = 0.5f)
    isActive -> ElectricBlue.copy(alpha = 0.75f)
    else -> DarkBorder
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
    elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 2.dp else 0.dp)
  ) {
    Column(modifier = Modifier.padding(start = 11.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        ProjectIcon(project = project, size = 36.dp)

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = project.name,
              color = TextPrimary,
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false)
            )
            if (isActive) {
              Spacer(modifier = Modifier.width(6.dp))
              StatusBadge("CURRENT", ElectricBlueGlow, ElectricBlue.copy(alpha = 0.2f))
            }
            if (project.isMissing) {
              Spacer(modifier = Modifier.width(6.dp))
              StatusBadge("MISSING", DangerRed, DangerRed.copy(alpha = 0.18f))
            } else if (project.isImported) {
              Spacer(modifier = Modifier.width(6.dp))
              StatusBadge("IMPORTED", TextMuted, DarkSurfaceElevated)
            }
          }

          Spacer(modifier = Modifier.height(3.dp))

          // The path is the densest thing on the card, so it spans the full row
          // width and gets a readable (not muted) token. Red only when the folder
          // is gone — the MISSING badge and the meta row carry the same story.
          Text(
            text = project.path,
            color = if (project.isMissing) DangerRed else TextSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
          )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Both card actions live on the same right-aligned column, so the card's
        // right edge reads as one straight line instead of two ragged ones.
        CardAction(
          icon = Icons.Outlined.ContentCopy,
          contentDescription = "Copy project path",
          tint = TextSecondary,
          onClick = { onCopyPath(project.path) }
        )
        Box {
          CardAction(
            icon = Icons.Outlined.MoreVert,
            contentDescription = "More actions for ${project.name}",
            tint = TextSecondary,
            testTag = "project_overflow_${project.id}",
            onClick = { menuOpen = true }
          )

          DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = DarkSurfaceElevated
          ) {
            val disabled = project.isMissing
            OverflowItem("Open in Agent", Icons.Outlined.AutoAwesome, enabled = !disabled) {
              menuOpen = false; onAction(ProjectOverflowAction.OPEN_IN_AGENT)
            }
            OverflowItem("Chats", Icons.AutoMirrored.Outlined.Chat, enabled = !disabled) {
              menuOpen = false; onAction(ProjectOverflowAction.CHATS)
            }
            OverflowItem("Files", Icons.Outlined.Folder, enabled = !disabled) {
              menuOpen = false; onAction(ProjectOverflowAction.FILES)
            }
            OverflowItem("Terminal", Icons.Outlined.Terminal, enabled = !disabled) {
              menuOpen = false; onAction(ProjectOverflowAction.TERMINAL)
            }
            OverflowItem("Changes", Icons.Outlined.Difference, enabled = !disabled) {
              menuOpen = false; onAction(ProjectOverflowAction.CHANGES)
            }
            if (project.sourcePath.isNotBlank()) {
              OverflowItem("Save to original folder", Icons.Outlined.SaveAlt, enabled = !disabled) {
                menuOpen = false; onAction(ProjectOverflowAction.SAVE_TO_ORIGINAL)
              }
            }
            HorizontalDivider(color = DarkBorderSubtle)
            OverflowItem("Remove project", Icons.Outlined.Delete, tint = DangerRed) {
              menuOpen = false; onAction(ProjectOverflowAction.REMOVE)
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Meta row. Built from the facts that actually exist, so a missing folder
      // (or a project the scanner has not reached yet) never renders bare "— · —"
      // separators — the placeholder values are never printed at all.
      val sizeLabel = if (project.sizeBytes >= 0) formatBytes(project.sizeBytes) else null
      val modifiedLabel = if (project.lastModified > 0) relativeModified(project.lastModified) else null
      val showKind = project.kind != ProjectKind.UNKNOWN
      val facts = listOfNotNull(sizeLabel, modifiedLabel)

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        if (project.isMissing) {
          Icon(
            Icons.Outlined.Warning,
            contentDescription = null,
            tint = DangerRed,
            modifier = Modifier.size(11.dp)
          )
          Spacer(modifier = Modifier.width(5.dp))
          Text(
            text = "Folder not found",
            color = DangerRed,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
          )
        } else {
          if (showKind) KindChip(project.kind)
          facts.forEachIndexed { index, fact ->
            if (showKind || index > 0) MetaSeparator()
            MetaValue(fact)
          }
          if (!showKind && facts.isEmpty()) {
            // Folder exists but the scanner has not measured it yet. Saying so is
            // better than printing the "—" placeholders.
            Text(text = "Not scanned yet", color = TextMuted, fontSize = 10.sp)
          }
        }

        Spacer(modifier = Modifier.weight(1f))

        if (project.changedFilesCount > 0) {
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(5.dp))
              .background(WarningAmber.copy(alpha = 0.14f))
              .border(1.dp, WarningAmber.copy(alpha = 0.35f), RoundedCornerShape(5.dp))
              .padding(horizontal = 5.dp, vertical = 2.dp)
          ) {
            Text(
              text = "${project.changedFilesCount} changed",
              color = WarningAmber,
              fontSize = 9.sp,
              fontFamily = FontFamily.Monospace
            )
          }
        }
      }
    }
  }
}

/**
 * A card-level icon action. Both the copy-path and overflow actions use it so
 * they sit on the same right-aligned edge with the same 30dp footprint and the
 * same 16dp glyph — the row reads as one straight line. 30dp matches the box the
 * overflow button already occupied; the copy affordance previously had only a
 * 12dp target, so this is wider than what it replaces.
 */
@Composable
private fun CardAction(
  icon: ImageVector,
  contentDescription: String,
  tint: Color,
  onClick: () -> Unit,
  testTag: String? = null
) {
  Box(
    modifier = Modifier
      .size(30.dp)
      .clip(RoundedCornerShape(8.dp))
      .clickable(onClick = onClick)
      .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    contentAlignment = Alignment.Center
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = tint,
      modifier = Modifier.size(16.dp)
    )
  }
}

/** One measured fact in the card's meta row. */
@Composable
private fun MetaValue(text: String) {
  Text(
    text = text,
    color = TextSecondary,
    fontSize = 10.sp,
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
 * Real project icon when one exists in the folder, otherwise a type icon, and
 * otherwise an initial derived from the project name. Never an emoji.
 */
@Composable
private fun ProjectIcon(project: Project, size: Dp) {
  val bitmap = rememberProjectIcon(project.iconPath)
  val shape = RoundedCornerShape(size / 4)
  // Known kinds get a stable type colour; everything else is derived from the
  // project name so the same project always looks the same.
  val accent = if (project.kind != ProjectKind.UNKNOWN) {
    accentForKind(project.kind)
  } else {
    accentForName(project.name)
  }

  Box(
    modifier = Modifier
      .size(size)
      .clip(shape)
      .background(if (bitmap != null) DarkSurfaceHighlight else accent.copy(alpha = 0.14f))
      .border(1.dp, if (bitmap != null) DarkBorder else accent.copy(alpha = 0.35f), shape),
    contentAlignment = Alignment.Center
  ) {
    when {
      bitmap != null -> Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
          .fillMaxSize()
          .padding(3.dp)
      )
      project.kind != ProjectKind.UNKNOWN -> Icon(
        imageVector = iconForKind(project.kind),
        contentDescription = project.kind.label,
        tint = accent,
        modifier = Modifier.size(size * 0.5f)
      )
      else -> Text(
        text = project.name.trim().take(1).uppercase().ifBlank { "·" },
        color = accent,
        fontSize = (size.value * 0.42f).sp,
        fontWeight = FontWeight.Bold
      )
    }
  }
}

@Composable
private fun KindChip(kind: ProjectKind) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(DarkSurfaceHighlight)
      .border(1.dp, DarkBorderSubtle, RoundedCornerShape(4.dp))
      .padding(horizontal = 5.dp, vertical = 1.dp)
  ) {
    Text(
      text = kind.label,
      color = TextSecondary,
      fontSize = 9.sp,
      fontWeight = FontWeight.Medium
    )
  }
}

@Composable
private fun MetaSeparator() {
  Text(
    text = "·",
    color = TextMuted,
    fontSize = 10.sp,
    modifier = Modifier.padding(horizontal = 5.dp)
  )
}

@Composable
private fun StatusBadge(text: String, color: Color, background: Color) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(background)
      .padding(horizontal = 5.dp, vertical = 1.dp)
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
