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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentisco.core.model.AppDestination
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

  var showNewProjectDialog by remember { mutableStateOf(false) }
  var selectedProjectForOverview by remember { mutableStateOf<Project?>(null) }

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
            text = "~/projects · 3 active repositories",
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

    // New Project Button
    item {
      Button(
        onClick = { showNewProjectDialog = true },
        modifier = Modifier
          .fillMaxWidth()
          .height(48.dp)
          .testTag("btn_new_project"),
        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
        shape = RoundedCornerShape(10.dp)
      ) {
        Icon(imageVector = Icons.Default.Add, contentDescription = "New", modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("New Project", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
      }
    }

    // Section: Recent Projects
    item {
      Text(
        text = "Recent Projects",
        color = TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp)
      )
    }

    items(projects) { project ->
      val isActive = project.id == activeProject.id

      Card(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .border(
            1.dp,
            if (isActive) ElectricBlue.copy(alpha = 0.6f) else DarkBorder,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(
                modifier = Modifier
                  .size(10.dp)
                  .clip(CircleShape)
                  .background(if (project.isDirty) WarningAmber else TerminalGreen)
              )
              Spacer(modifier = Modifier.width(10.dp))
              Text(
                text = project.name,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
              )
            }

            if (isActive) {
              Box(
                modifier = Modifier
                  .clip(RoundedCornerShape(4.dp))
                  .background(ElectricBlue.copy(alpha = 0.2f))
                  .padding(horizontal = 6.dp, vertical = 2.dp)
              ) {
                Text(
                  text = "CURRENT",
                  color = ElectricBlueGlow,
                  fontSize = 9.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(4.dp))

          Text(
            text = project.description,
            color = TextSecondary,
            fontSize = 12.sp,
            maxLines = 1
          )

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
                text = project.branch,
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
              )
              Text("·", color = TextMuted, fontSize = 11.sp)
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

          if (project.activeSessionText != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(DarkBackground)
                .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
              Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "Active Agent",
                tint = ElectricBlueGlow,
                modifier = Modifier.size(12.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "Agent active: ${project.activeSessionText}",
                color = ElectricBlueGlow,
                fontSize = 11.sp
              )
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
          Column {
            Text(proj.name, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Branch: ${proj.branch}", color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
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
          // 4 quick navigation tiles (Section 4)
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            ProjectActionTile(
              label = "Agent",
              icon = Icons.Outlined.AutoAwesome,
              modifier = Modifier.weight(1f),
              onClick = {
                selectedProjectForOverview = null
                onNavigate(AppDestination.AGENT)
              }
            )
            ProjectActionTile(
              label = "Files",
              icon = Icons.Outlined.Folder,
              modifier = Modifier.weight(1f),
              onClick = {
                selectedProjectForOverview = null
                onNavigate(AppDestination.FILES)
              }
            )
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            ProjectActionTile(
              label = "Terminal",
              icon = Icons.Outlined.Terminal,
              modifier = Modifier.weight(1f),
              onClick = {
                selectedProjectForOverview = null
                onNavigate(AppDestination.TERMINAL)
              }
            )
            ProjectActionTile(
              label = "Changes",
              icon = Icons.Outlined.Difference,
              modifier = Modifier.weight(1f),
              onClick = {
                selectedProjectForOverview = null
                onNavigate(AppDestination.DIFF)
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
            Text("● Agent fixed chat loading — 14 min ago", color = TextPrimary, fontSize = 11.sp)
            Text("● Modified Chat.tsx — 16 min ago", color = TextMuted, fontSize = 11.sp)
            Text("● Automated tests passed — 18 min ago", color = TerminalGreen, fontSize = 11.sp)
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            selectedProjectForOverview = null
            onNavigate(AppDestination.AGENT)
          },
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

  // New Project Dialog
  if (showNewProjectDialog) {
    var newName by remember { mutableStateOf("") }
    var newDesc by remember { mutableStateOf("") }

    AlertDialog(
      onDismissRequest = { showNewProjectDialog = false },
      containerColor = DarkSurface,
      title = {
        Text("Create New Project", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
      },
      text = {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            label = { Text("Project Name", fontSize = 11.sp) },
            placeholder = { Text("e.g. cloud-sync-app", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
          OutlinedTextField(
            value = newDesc,
            onValueChange = { newDesc = it },
            label = { Text("Description", fontSize = 11.sp) },
            placeholder = { Text("Short description of what the project does", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth()
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (newName.isNotBlank()) {
              viewModel.createProject(newName, newDesc)
              showNewProjectDialog = false
              onNavigate(AppDestination.AGENT)
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
        ) {
          Text("Create & Open", fontSize = 12.sp)
        }
      },
      dismissButton = {
        TextButton(onClick = { showNewProjectDialog = false }) {
          Text("Cancel", color = TextMuted, fontSize = 12.sp)
        }
      }
    )
  }
}

@Composable
private fun ProjectActionTile(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  modifier: Modifier = Modifier,
  onClick: () -> Unit
) {
  Box(
    modifier = modifier
      .height(54.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(DarkSurfaceElevated)
      .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
      .clickable(onClick = onClick)
      .padding(8.dp),
    contentAlignment = Alignment.Center
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = ElectricBlueGlow,
        modifier = Modifier.size(16.dp)
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = label,
        color = TextPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium
      )
    }
  }
}
