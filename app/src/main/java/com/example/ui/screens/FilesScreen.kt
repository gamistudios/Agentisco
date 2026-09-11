package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
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
import com.example.data.model.AppDestination
import com.example.data.model.ProjectFile
import com.example.ui.WorkspaceViewModel
import com.example.ui.theme.*

@Composable
fun FilesScreen(
  viewModel: WorkspaceViewModel,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  val activeProject by viewModel.activeProject.collectAsState()
  val rootFiles by viewModel.projectFiles.collectAsState()

  var selectedFileForMenu by remember { mutableStateOf<ProjectFile?>(null) }
  var showAskAgentDialog by remember { mutableStateOf(false) }
  var showFileOptionsDialog by remember { mutableStateOf(false) }
  var showNewFileDialog by remember { mutableStateOf(false) }
  var newFileNameInput by remember { mutableStateOf("") }
  var showNewFolderDialog by remember { mutableStateOf(false) }
  var newFolderNameInput by remember { mutableStateOf("") }
  var showRenameDialog by remember { mutableStateOf(false) }
  var renameInput by remember { mutableStateOf("") }
  var searchQuery by remember { mutableStateOf("") }
  var isSearchActive by remember { mutableStateOf(false) }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
  ) {
    // Header
    Surface(
      modifier = Modifier.fillMaxWidth(),
      color = DarkSurface,
      border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle)
    ) {
      Column {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
              onClick = { onNavigate(AppDestination.AGENT) },
              modifier = Modifier.size(30.dp)
            ) {
              Icon(Icons.Default.ChevronLeft, contentDescription = "Back", tint = TextMuted)
            }
            Spacer(modifier = Modifier.width(4.dp))
            Column {
              Text(
                text = activeProject.name,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = "Files Explorer",
                color = TextMuted,
                fontSize = 11.sp
              )
            }
          }

          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButton(
              onClick = { viewModel.refreshFiles() },
              modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(DarkSurfaceElevated)
                .testTag("btn_refresh_files")
            ) {
              Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh",
                tint = TextSecondary,
                modifier = Modifier.size(16.dp)
              )
            }

            IconButton(
              onClick = { isSearchActive = !isSearchActive },
              modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(DarkSurfaceElevated)
                .testTag("btn_search_files")
            ) {
              Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = if (isSearchActive) ElectricBlueGlow else TextSecondary,
                modifier = Modifier.size(16.dp)
              )
            }
          }
        }

        if (isSearchActive) {
          TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Filter files by name...", color = TextMuted, fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 6.dp)
              .testTag("file_search_input"),
            colors = TextFieldDefaults.colors(
              focusedContainerColor = DarkBackground,
              unfocusedContainerColor = DarkBackground,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary,
              focusedIndicatorColor = ElectricBlue,
              unfocusedIndicatorColor = DarkBorderSubtle
            )
          )
        }
      }
    }

    // Flat file list or recursive tree
    val flattened = remember(rootFiles, searchQuery) {
      val result = mutableListOf<ProjectFile>()
      fun flatten(files: List<ProjectFile>) {
        for (f in files) {
          if (searchQuery.isBlank() || f.name.contains(searchQuery, ignoreCase = true)) {
            result.add(f)
          }
          if (f.isDirectory) {
            flatten(f.children)
          }
        }
      }
      flatten(rootFiles)
      result
    }

    LazyColumn(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      items(flattened) { file ->
        FileTreeRow(
          file = file,
          onClick = {
            if (!file.isDirectory) {
              viewModel.openFile(file)
            }
          },
          onLongClick = {
            selectedFileForMenu = file
            showFileOptionsDialog = true
          },
          onAskAgent = {
            selectedFileForMenu = file
            showAskAgentDialog = true
          }
        )
      }
    }

    // Bottom Action Bar: New File / New Folder
    Surface(
      modifier = Modifier.fillMaxWidth(),
      color = DarkSurface,
      border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle)
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        OutlinedButton(
          onClick = {
            newFileNameInput = "src/"
            showNewFileDialog = true
          },
          modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .testTag("btn_add_file"),
          border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
          shape = RoundedCornerShape(8.dp)
        ) {
          Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("New File", fontSize = 12.sp, color = TextPrimary)
        }

        OutlinedButton(
          onClick = {
            newFolderNameInput = "src/"
            showNewFolderDialog = true
          },
          modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .testTag("btn_add_folder"),
          border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
          shape = RoundedCornerShape(8.dp)
        ) {
          Icon(Icons.Outlined.CreateNewFolder, contentDescription = "Add Folder", modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("New Folder", fontSize = 12.sp, color = TextPrimary)
        }
      }
    }
  }

  // Dialog: New File
  if (showNewFileDialog) {
    AlertDialog(
      onDismissRequest = { showNewFileDialog = false },
      containerColor = DarkSurface,
      title = { Text("Create New File", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Enter relative file path:", color = TextSecondary, fontSize = 12.sp)
          TextField(
            value = newFileNameInput,
            onValueChange = { newFileNameInput = it },
            placeholder = { Text("e.g. src/utils.ts", color = TextMuted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("input_new_file"),
            colors = TextFieldDefaults.colors(
              focusedContainerColor = DarkBackground,
              unfocusedContainerColor = DarkBackground,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary
            )
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val clean = newFileNameInput.trim()
            if (clean.isNotBlank()) {
              viewModel.createFile(clean, "// Created in ScoOS\n")
              showNewFileDialog = false
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
        ) {
          Text("Create")
        }
      },
      dismissButton = {
        TextButton(onClick = { showNewFileDialog = false }) {
          Text("Cancel", color = TextMuted)
        }
      }
    )
  }

  // Dialog: New Folder
  if (showNewFolderDialog) {
    AlertDialog(
      onDismissRequest = { showNewFolderDialog = false },
      containerColor = DarkSurface,
      title = { Text("Create New Folder", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Enter relative folder path:", color = TextSecondary, fontSize = 12.sp)
          TextField(
            value = newFolderNameInput,
            onValueChange = { newFolderNameInput = it },
            placeholder = { Text("e.g. src/services", color = TextMuted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("input_new_folder"),
            colors = TextFieldDefaults.colors(
              focusedContainerColor = DarkBackground,
              unfocusedContainerColor = DarkBackground,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary
            )
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val clean = newFolderNameInput.trim()
            if (clean.isNotBlank()) {
              viewModel.createDirectory(clean)
              showNewFolderDialog = false
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
        ) {
          Text("Create")
        }
      },
      dismissButton = {
        TextButton(onClick = { showNewFolderDialog = false }) {
          Text("Cancel", color = TextMuted)
        }
      }
    )
  }

  // Dialog: File Context Options
  if (showFileOptionsDialog && selectedFileForMenu != null) {
    val file = selectedFileForMenu!!
    AlertDialog(
      onDismissRequest = { showFileOptionsDialog = false },
      containerColor = DarkSurface,
      title = { Text(file.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          if (!file.isDirectory) {
            AskAgentOptionItem("Open in Editor", "View and edit file content") {
              showFileOptionsDialog = false
              viewModel.openFile(file)
            }
          }
          AskAgentOptionItem("Ask Agent", "Analyze, refactor, or test this file") {
            showFileOptionsDialog = false
            showAskAgentDialog = true
          }
          AskAgentOptionItem("Rename", "Change file or folder name") {
            showFileOptionsDialog = false
            renameInput = file.name
            showRenameDialog = true
          }
          AskAgentOptionItem("Delete", "Remove from project directory") {
            showFileOptionsDialog = false
            viewModel.deleteFile(file.path)
          }
        }
      },
      confirmButton = {},
      dismissButton = {
        TextButton(onClick = { showFileOptionsDialog = false }) {
          Text("Cancel", color = TextMuted)
        }
      }
    )
  }

  // Dialog: Rename
  if (showRenameDialog && selectedFileForMenu != null) {
    val file = selectedFileForMenu!!
    AlertDialog(
      onDismissRequest = { showRenameDialog = false },
      containerColor = DarkSurface,
      title = { Text("Rename ${file.name}", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
      text = {
        TextField(
          value = renameInput,
          onValueChange = { renameInput = it },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("input_rename_file"),
          colors = TextFieldDefaults.colors(
            focusedContainerColor = DarkBackground,
            unfocusedContainerColor = DarkBackground,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary
          )
        )
      },
      confirmButton = {
        Button(
          onClick = {
            val clean = renameInput.trim()
            if (clean.isNotBlank()) {
              viewModel.renameFile(file.path, clean)
              showRenameDialog = false
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
        ) {
          Text("Rename")
        }
      },
      dismissButton = {
        TextButton(onClick = { showRenameDialog = false }) {
          Text("Cancel", color = TextMuted)
        }
      }
    )
  }

  // Ask Agent Dialog / Context Menu (Section 13)
  if (showAskAgentDialog && selectedFileForMenu != null) {
    val file = selectedFileForMenu!!
    AlertDialog(
      onDismissRequest = { showAskAgentDialog = false },
      containerColor = DarkSurface,
      title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.AutoAwesome, contentDescription = "Agent", tint = ElectricBlueGlow, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("Ask Agent on ${file.name}", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
      },
      text = {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          AskAgentOptionItem("Explain this file", "Break down functionality & data flow") {
            showAskAgentDialog = false
            viewModel.runAgentTask("Explain the structure, logic, and state flow in ${file.name}")
            onNavigate(AppDestination.AGENT)
          }
          AskAgentOptionItem("Find bugs", "Static analysis and edge-case inspection") {
            showAskAgentDialog = false
            viewModel.runAgentTask("Inspect ${file.name} for subtle bugs, memory leaks, and race conditions")
            onNavigate(AppDestination.AGENT)
          }
          AskAgentOptionItem("Refactor", "Clean code, simplify types and imports") {
            showAskAgentDialog = false
            viewModel.runAgentTask("Refactor ${file.name} to modern idiomatic patterns")
            onNavigate(AppDestination.AGENT)
          }
          AskAgentOptionItem("Write tests", "Generate unit and integration test suite") {
            showAskAgentDialog = false
            viewModel.runAgentTask("Write comprehensive Vitest unit tests for ${file.name}")
            onNavigate(AppDestination.AGENT)
          }
          AskAgentOptionItem("Optimize", "Enhance performance and memoization") {
            showAskAgentDialog = false
            viewModel.runAgentTask("Optimize rendering performance and listeners in ${file.name}")
            onNavigate(AppDestination.AGENT)
          }
        }
      },
      confirmButton = {},
      dismissButton = {
        TextButton(onClick = { showAskAgentDialog = false }) {
          Text("Cancel", color = TextMuted)
        }
      }
    )
  }
}

@Composable
private fun FileTreeRow(
  file: ProjectFile,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  onAskAgent: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(DarkSurfaceElevated)
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.weight(1f)
    ) {
      val icon = when {
        file.isDirectory -> Icons.Filled.Folder
        file.name.endsWith(".json") -> Icons.Outlined.DataObject
        file.name.endsWith(".md") -> Icons.AutoMirrored.Outlined.Article
        file.name.endsWith(".tsx") || file.name.endsWith(".ts") -> Icons.Outlined.Code
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
      }
      val iconColor = when {
        file.isDirectory -> ElectricBlueGlow
        file.name.endsWith(".json") -> WarningAmber
        file.name.endsWith(".tsx") -> CyanAccent
        else -> TextSecondary
      }

      Icon(
        imageVector = icon,
        contentDescription = file.name,
        tint = iconColor,
        modifier = Modifier.size(18.dp)
      )

      Spacer(modifier = Modifier.width(10.dp))

      Column {
        Text(
          text = file.name,
          color = TextPrimary,
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium,
          fontFamily = if (!file.isDirectory) FontFamily.Monospace else FontFamily.Default
        )
        Text(
          text = file.path,
          color = TextMuted,
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace
        )
      }
    }

    if (!file.isDirectory) {
      IconButton(
        onClick = onAskAgent,
        modifier = Modifier.size(30.dp)
      ) {
        Icon(
          imageVector = Icons.Outlined.AutoAwesome,
          contentDescription = "Ask Agent",
          tint = ElectricBlueGlow,
          modifier = Modifier.size(16.dp)
        )
      }
    }
  }
}

@Composable
private fun AskAgentOptionItem(
  title: String,
  desc: String,
  onClick: () -> Unit
) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(DarkBackground)
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 8.dp)
  ) {
    Column {
      Text(text = title, color = ElectricBlueGlow, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
      Text(text = desc, color = TextMuted, fontSize = 11.sp)
    }
  }
}
