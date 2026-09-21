package com.agentisco.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentisco.core.model.AppDestination
import com.agentisco.data.model.FileDiff
import com.agentisco.data.model.ProjectFile
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.theme.*
import com.agentisco.workspace.git.GitRepoStatus

/**
 * Git working-tree status for files.
 */
enum class FileGitState(val label: String, val badgeColor: Color, val bgColor: Color) {
  NONE("", Color.Transparent, Color.Transparent),
  MODIFIED("M", WarningAmber, WarningAmberBg),
  STAGED("S", TerminalGreen, TerminalGreenBg),
  UNTRACKED("U", CyanAccent, Color(0xFF083344)),
  CONFLICT("!", DangerRed, DangerRedBg)
}

/**
 * Sorting orders for the files explorer.
 */
enum class FileSortOrder(val title: String) {
  NAME_ASC("Name (A-Z)"),
  NAME_DESC("Name (Z-A)"),
  SIZE_DESC("Size (Largest)"),
  DATE_DESC("Recently Modified")
}

/**
 * Quick category filters for rapid file exploration.
 */
enum class FileCategoryFilter(val label: String) {
  ALL("All"),
  CODE("Code"),
  CONFIG("Config"),
  DOCS("Docs"),
  CHANGED("Git Changed")
}

@Composable
fun FilesScreen(
  viewModel: WorkspaceViewModel,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  val activeProject by viewModel.activeProject.collectAsState()
  val rootFiles by viewModel.projectFiles.collectAsState()
  val dirChildren by viewModel.dirChildren.collectAsState()
  val isFilesLoading by viewModel.isFilesLoading.collectAsState()
  val searchResults by viewModel.nameSearchResults.collectAsState()
  val fileDiffs by viewModel.fileDiffs.collectAsState()
  val stagedFiles by viewModel.stagedFiles.collectAsState()
  val repoStatus by viewModel.repoStatus.collectAsState()

  val clipboardManager = LocalClipboardManager.current
  val snackbarHostState = remember { SnackbarHostState() }

  // Dialog & Sheet States
  var selectedFileForMenu by remember { mutableStateOf<ProjectFile?>(null) }
  var showFileOptionsDialog by remember { mutableStateOf(false) }
  var showFolderOptionsDialog by remember { mutableStateOf(false) }
  var showAskAgentDialog by remember { mutableStateOf(false) }
  var showDeleteConfirmDialog by remember { mutableStateOf(false) }
  var showRenameDialog by remember { mutableStateOf(false) }
  var showNewFileDialog by remember { mutableStateOf(false) }
  var showNewFolderDialog by remember { mutableStateOf(false) }

  var targetFolderForCreation by remember { mutableStateOf("") }
  var newFileNameInput by remember { mutableStateOf("") }
  var newFolderNameInput by remember { mutableStateOf("") }
  var renameInput by remember { mutableStateOf("") }

  // Search & Filter state
  var searchQuery by remember { mutableStateOf("") }
  var isSearchActive by remember { mutableStateOf(false) }
  var activeCategory by remember { mutableStateOf(FileCategoryFilter.ALL) }
  var sortOrder by remember { mutableStateOf(FileSortOrder.NAME_ASC) }
  var showSortMenu by remember { mutableStateOf(false) }
  var showHiddenFiles by remember { mutableStateOf(false) }

  // Expanded folders in the tree
  var expandedDirs by remember(activeProject.id) { mutableStateOf(setOf<String>()) }

  LaunchedEffect(searchQuery) {
    viewModel.searchFileNames(searchQuery)
  }

  // Helper to toggle expand
  val toggleExpand: (ProjectFile) -> Unit = { file ->
    if (expandedDirs.contains(file.path)) {
      expandedDirs = expandedDirs - file.path
    } else {
      expandedDirs = expandedDirs + file.path
      viewModel.loadChildren(file.path)
    }
  }

  // Expand all / collapse all
  val expandAll: () -> Unit = {
    val allDirs = mutableSetOf<String>()
    fun collectDirs(list: List<ProjectFile>) {
      for (f in list) {
        if (f.isDirectory) {
          allDirs.add(f.path)
          viewModel.loadChildren(f.path)
          dirChildren[f.path]?.let { collectDirs(it) }
        }
      }
    }
    collectDirs(rootFiles)
    expandedDirs = allDirs
  }

  val collapseAll: () -> Unit = {
    expandedDirs = emptySet()
  }

  // Resolve Git status for any file path
  fun resolveGitState(path: String): Pair<FileGitState, Pair<Int, Int>?> {
    if (repoStatus.conflictedFiles.any { it.path == path }) {
      return FileGitState.CONFLICT to null
    }
    val isStaged = stagedFiles.contains(path)
    val diff = fileDiffs.find { it.filePath == path }
    val counts = if (diff != null) Pair(diff.additionsCount, diff.deletionsCount) else null
    if (isStaged) {
      return FileGitState.STAGED to counts
    }
    if (diff != null || repoStatus.unstagedFiles.any { it.path == path }) {
      return FileGitState.MODIFIED to counts
    }
    if (repoStatus.untrackedFiles.any { it.path == path }) {
      return FileGitState.UNTRACKED to null
    }
    return FileGitState.NONE to null
  }

  // Filter checker
  fun matchesCategory(file: ProjectFile): Boolean {
    if (file.isDirectory) return true
    val ext = file.name.substringAfterLast(".", "").lowercase()
    return when (activeCategory) {
      FileCategoryFilter.ALL -> true
      FileCategoryFilter.CODE -> ext in setOf("ts", "tsx", "js", "jsx", "kt", "kts", "java", "py", "rs", "go", "c", "cpp", "h", "sh")
      FileCategoryFilter.CONFIG -> ext in setOf("json", "yaml", "yml", "toml", "xml", "properties", "gradle", "env", "lock")
      FileCategoryFilter.DOCS -> ext in setOf("md", "txt", "rst", "adoc")
      FileCategoryFilter.CHANGED -> resolveGitState(file.path).first != FileGitState.NONE
    }
  }

  // Sorter
  fun sortFiles(list: List<ProjectFile>): List<ProjectFile> {
    val filtered = if (showHiddenFiles) list else list.filter { !it.name.startsWith(".") || it.name == ".gitignore" || it.name == ".env" }
    return when (sortOrder) {
      FileSortOrder.NAME_ASC -> filtered.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
      FileSortOrder.NAME_DESC -> filtered.sortedWith(compareBy<ProjectFile> { !it.isDirectory }.thenByDescending { it.name.lowercase() })
      FileSortOrder.SIZE_DESC -> filtered.sortedWith(compareBy<ProjectFile> { !it.isDirectory }.thenByDescending { it.sizeBytes })
      FileSortOrder.DATE_DESC -> filtered.sortedWith(compareBy<ProjectFile> { !it.isDirectory }.thenByDescending { it.lastModified })
    }
  }

  // Flattened tree of visible entries
  val visibleTreeRows = remember(rootFiles, dirChildren, expandedDirs, activeCategory, sortOrder, showHiddenFiles) {
    val result = mutableListOf<Pair<ProjectFile, Int>>()
    fun addEntries(files: List<ProjectFile>, depth: Int) {
      val sorted = sortFiles(files)
      for (f in sorted) {
        if (f.isDirectory) {
          result.add(f to depth)
          if (expandedDirs.contains(f.path)) {
            val children = dirChildren[f.path] ?: emptyList()
            addEntries(children, depth + 1)
          }
        } else if (matchesCategory(f)) {
          result.add(f to depth)
        }
      }
    }
    addEntries(rootFiles, 0)
    result
  }

  // Count total stats
  val totalFilesCount = remember(rootFiles, dirChildren) {
    var count = 0
    fun countAll(list: List<ProjectFile>) {
      for (f in list) {
        if (!f.isDirectory) count++
        dirChildren[f.path]?.let { countAll(it) }
      }
    }
    countAll(rootFiles)
    count
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = DarkBackground,
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DarkSurface,
        border = BorderStroke(1.dp, DarkBorderSubtle)
      ) {
        Column {
          // Main Top App Bar
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .statusBarsPadding()
              .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.weight(1f)
            ) {
              IconButton(
                onClick = { onNavigate(AppDestination.EDITOR) },
                modifier = Modifier.size(36.dp)
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                  contentDescription = "Back to Editor",
                  tint = TextSecondary,
                  modifier = Modifier.size(20.dp)
                )
              }

              Spacer(modifier = Modifier.width(6.dp))

              Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(
                    text = activeProject.name.ifBlank { "Workspace" },
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  if (activeProject.branch.isNotBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                      color = DarkSurfaceElevated,
                      shape = RoundedCornerShape(4.dp),
                      border = BorderStroke(1.dp, DarkBorderSubtle)
                    ) {
                      Row(
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                      ) {
                        Icon(
                          imageVector = Icons.Outlined.ForkRight,
                          contentDescription = "Branch",
                          tint = CyanAccent,
                          modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                          text = activeProject.branch,
                          color = CyanAccent,
                          fontSize = 10.sp,
                          fontFamily = FontFamily.Monospace
                        )
                      }
                    }
                  }
                }

                Text(
                  text = "$totalFilesCount files • ${if (repoStatus.totalChangedFiles > 0) "${repoStatus.totalChangedFiles} modified" else "working tree clean"}",
                  color = if (repoStatus.totalChangedFiles > 0) WarningAmber else TextMuted,
                  fontSize = 11.sp
                )
              }
            }

            // Quick Actions in Top Bar
            Row(
              horizontalArrangement = Arrangement.spacedBy(4.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              // Refresh Button
              IconButton(
                onClick = { viewModel.refreshFiles(showLoading = true) },
                modifier = Modifier
                  .size(34.dp)
                  .clip(RoundedCornerShape(6.dp))
                  .background(DarkSurfaceElevated)
                  .testTag("btn_refresh_files")
              ) {
                Icon(
                  imageVector = Icons.Default.Refresh,
                  contentDescription = "Refresh files",
                  tint = if (isFilesLoading) ElectricBlueGlow else TextSecondary,
                  modifier = Modifier.size(16.dp)
                )
              }

              // Search Toggle Button
              IconButton(
                onClick = {
                  isSearchActive = !isSearchActive
                  if (!isSearchActive) searchQuery = ""
                },
                modifier = Modifier
                  .size(34.dp)
                  .clip(RoundedCornerShape(6.dp))
                  .background(if (isSearchActive) DarkSurfaceHighlight else DarkSurfaceElevated)
                  .testTag("btn_search_files")
              ) {
                Icon(
                  imageVector = Icons.Default.Search,
                  contentDescription = "Search files",
                  tint = if (isSearchActive) ElectricBlueGlow else TextSecondary,
                  modifier = Modifier.size(16.dp)
                )
              }

              // Expand / Collapse All Toggle
              IconButton(
                onClick = {
                  if (expandedDirs.isEmpty()) expandAll() else collapseAll()
                },
                modifier = Modifier
                  .size(34.dp)
                  .clip(RoundedCornerShape(6.dp))
                  .background(DarkSurfaceElevated)
                  .testTag("btn_collapse_all")
              ) {
                Icon(
                  imageVector = if (expandedDirs.isEmpty()) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                  contentDescription = if (expandedDirs.isEmpty()) "Expand all" else "Collapse all",
                  tint = TextSecondary,
                  modifier = Modifier.size(16.dp)
                )
              }

              // Sort Menu Button
              Box {
                IconButton(
                  onClick = { showSortMenu = true },
                  modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(DarkSurfaceElevated)
                ) {
                  Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = "Sort and filter options",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                  )
                }

                DropdownMenu(
                  expanded = showSortMenu,
                  onDismissRequest = { showSortMenu = false },
                  modifier = Modifier.background(DarkSurface)
                ) {
                  Text(
                    text = "SORT FILES BY",
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                  )
                  FileSortOrder.values().forEach { order ->
                    DropdownMenuItem(
                      text = {
                        Text(
                          text = order.title,
                          color = if (sortOrder == order) ElectricBlueGlow else TextPrimary,
                          fontSize = 12.sp,
                          fontWeight = if (sortOrder == order) FontWeight.Bold else FontWeight.Normal
                        )
                      },
                      leadingIcon = {
                        if (sortOrder == order) {
                          Icon(Icons.Outlined.Check, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(14.dp))
                        } else {
                          Spacer(modifier = Modifier.size(14.dp))
                        }
                      },
                      onClick = {
                        sortOrder = order
                        showSortMenu = false
                      }
                    )
                  }
                  HorizontalDivider(color = DarkBorderSubtle)
                  DropdownMenuItem(
                    text = {
                      Text(
                        text = if (showHiddenFiles) "Hide dotfiles" else "Show dotfiles (.env, .git)",
                        color = TextSecondary,
                        fontSize = 12.sp
                      )
                    },
                    leadingIcon = {
                      Icon(
                        imageVector = if (showHiddenFiles) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(14.dp)
                      )
                    },
                    onClick = {
                      showHiddenFiles = !showHiddenFiles
                      showSortMenu = false
                    }
                  )
                }
              }
            }
          }

          // Search Bar & Filter Chips (Animated)
          AnimatedVisibility(visible = isSearchActive) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface)
                .padding(bottom = 8.dp)
            ) {
              // Search Input Row
              Surface(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 12.dp, vertical = 4.dp),
                color = DarkBackground,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, if (searchQuery.isNotEmpty()) ElectricBlue else DarkBorder)
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search files by name (e.g. Chat.tsx, package.json)...", color = TextMuted, fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier
                      .weight(1f)
                      .testTag("file_search_input"),
                    colors = TextFieldDefaults.colors(
                      focusedContainerColor = Color.Transparent,
                      unfocusedContainerColor = Color.Transparent,
                      focusedTextColor = TextPrimary,
                      unfocusedTextColor = TextPrimary,
                      focusedIndicatorColor = Color.Transparent,
                      unfocusedIndicatorColor = Color.Transparent
                    )
                  )
                  if (searchQuery.isNotEmpty()) {
                    IconButton(
                      onClick = { searchQuery = "" },
                      modifier = Modifier.size(28.dp)
                    ) {
                      Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(16.dp))
                    }
                  }
                }
              }

              // Filter category chips row
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .horizontalScroll(rememberScrollState())
                  .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                FileCategoryFilter.values().forEach { category ->
                  val isSelected = activeCategory == category
                  Surface(
                    onClick = { activeCategory = category },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) ElectricBlue else DarkSurfaceElevated,
                    border = BorderStroke(1.dp, if (isSelected) ElectricBlueGlow else DarkBorderSubtle),
                    modifier = Modifier.height(28.dp)
                  ) {
                    Box(
                      contentAlignment = Alignment.Center,
                      modifier = Modifier.padding(horizontal = 10.dp)
                    ) {
                      Text(
                        text = category.label,
                        color = if (isSelected) Color.White else TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                      )
                    }
                  }
                }
              }
            }
          }

          // Non-blocking sleek progress bar
          if (isFilesLoading) {
            LinearProgressIndicator(
              modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
              color = ElectricBlueGlow,
              trackColor = DarkSurfaceElevated
            )
          }
        }
      }
    },
    bottomBar = {
      // Bottom Action Toolbar: Quick Create
      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DarkSurface,
        border = BorderStroke(1.dp, DarkBorderSubtle)
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // New File Button
          Button(
            onClick = {
              targetFolderForCreation = ""
              newFileNameInput = ""
              showNewFileDialog = true
            },
            modifier = Modifier
              .weight(1f)
              .height(42.dp)
              .testTag("btn_add_file"),
            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
            shape = RoundedCornerShape(8.dp)
          ) {
            Icon(Icons.Default.Add, contentDescription = "Add File", modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("New File", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
          }

          // New Folder Button
          OutlinedButton(
            onClick = {
              targetFolderForCreation = ""
              newFolderNameInput = ""
              showNewFolderDialog = true
            },
            modifier = Modifier
              .weight(1f)
              .height(42.dp)
              .testTag("btn_add_folder"),
            border = BorderStroke(1.dp, DarkBorder),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = DarkSurfaceElevated),
            shape = RoundedCornerShape(8.dp)
          ) {
            Icon(Icons.Outlined.CreateNewFolder, contentDescription = "Add Folder", tint = TextPrimary, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("New Folder", fontSize = 13.sp, color = TextPrimary)
          }
        }
      }
    }
  ) { paddingValues ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues)
    ) {
      when {
        // Initial Empty Workspace Loading Skeleton
        rootFiles.isEmpty() && isFilesLoading -> {
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator(color = ElectricBlueGlow, modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Indexing workspace files…", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("Building file tree structure and checking Git status", color = TextMuted, fontSize = 11.sp)
          }
        }

        // Empty Workspace
        rootFiles.isEmpty() -> {
          EmptyWorkspaceView(
            onCreateReadme = {
              viewModel.createFile("README.md", "# ${activeProject.name}\n\nWorkspace project.\n")
            },
            onCreateFile = {
              targetFolderForCreation = ""
              newFileNameInput = "index.ts"
              showNewFileDialog = true
            },
            onOpenTerminal = {
              onNavigate(AppDestination.TERMINAL)
            }
          )
        }

        // Search Active & No Matches
        searchQuery.isNotBlank() && searchResults.isEmpty() -> {
          EmptySearchView(query = searchQuery, onClear = { searchQuery = "" })
        }

        // Search Results List
        searchQuery.isNotBlank() -> {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
          ) {
            item {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text(
                  text = "SEARCH RESULTS (${searchResults.size})",
                  color = TextMuted,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold
                )
                Text(
                  text = "Matching \"$searchQuery\"",
                  color = ElectricBlueGlow,
                  fontSize = 11.sp
                )
              }
            }

            items(searchResults, key = { it.path }) { file ->
              val (gitState, diffCounts) = resolveGitState(file.path)
              FileTreeItemRow(
                file = file,
                depth = 0,
                isExpanded = false,
                gitState = gitState,
                diffCounts = diffCounts,
                showFullPath = true,
                onClick = {
                  if (file.isDirectory) {
                    toggleExpand(file)
                  } else {
                    viewModel.openFile(file)
                    onNavigate(AppDestination.EDITOR)
                  }
                },
                onMoreClick = {
                  selectedFileForMenu = file
                  if (file.isDirectory) showFolderOptionsDialog = true else showFileOptionsDialog = true
                },
                onAskAgent = {
                  selectedFileForMenu = file
                  showAskAgentDialog = true
                }
              )
            }
          }
        }

        // Standard Hierarchy Tree View
        else -> {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
          ) {
            items(visibleTreeRows, key = { "${it.first.path}_${it.second}" }) { (file, depth) ->
              val (gitState, diffCounts) = resolveGitState(file.path)
              FileTreeItemRow(
                file = file,
                depth = depth,
                isExpanded = expandedDirs.contains(file.path),
                gitState = gitState,
                diffCounts = diffCounts,
                showFullPath = false,
                onClick = {
                  if (file.isDirectory) {
                    toggleExpand(file)
                  } else {
                    viewModel.openFile(file)
                    onNavigate(AppDestination.EDITOR)
                  }
                },
                onMoreClick = {
                  selectedFileForMenu = file
                  if (file.isDirectory) showFolderOptionsDialog = true else showFileOptionsDialog = true
                },
                onAskAgent = {
                  selectedFileForMenu = file
                  showAskAgentDialog = true
                }
              )
            }
          }
        }
      }
    }
  }

  // ==========================================
  // DIALOGS & ACTION SHEETS
  // ==========================================

  // File Options Dialog
  if (showFileOptionsDialog && selectedFileForMenu != null) {
    val file = selectedFileForMenu!!
    val (gitState, _) = resolveGitState(file.path)
    val isStaged = stagedFiles.contains(file.path)

    AlertDialog(
      onDismissRequest = { showFileOptionsDialog = false },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp),
      title = {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth()
        ) {
          val (icon, color) = getFileIconAndColor(file.name, isDirectory = false, isExpanded = false)
          Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = file.name,
              color = TextPrimary,
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold,
              fontFamily = FontFamily.Monospace,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = "${formatFileSize(file.sizeBytes)} • ${file.path}",
              color = TextMuted,
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          // Open in Editor
          FileOptionRowItem(
            icon = Icons.Outlined.Edit,
            title = "Open in Editor",
            subtitle = "View and edit source code in active tab",
            tint = ElectricBlueGlow
          ) {
            showFileOptionsDialog = false
            viewModel.openFile(file)
            onNavigate(AppDestination.EDITOR)
          }

          // Ask AI Agent
          FileOptionRowItem(
            icon = Icons.Default.AutoAwesome,
            title = "Ask AI Agent",
            subtitle = "Explain, refactor, find bugs, or write tests",
            tint = CyanAccent
          ) {
            showFileOptionsDialog = false
            showAskAgentDialog = true
          }

          // View Git Diff (if modified)
          if (gitState != FileGitState.NONE) {
            FileOptionRowItem(
              icon = Icons.Outlined.Difference,
              title = "View Git Changes & Diff",
              subtitle = "Inspect additions, deletions, and working tree changes",
              tint = WarningAmber
            ) {
              showFileOptionsDialog = false
              onNavigate(AppDestination.DIFF)
            }

            // Stage / Unstage
            FileOptionRowItem(
              icon = if (isStaged) Icons.Outlined.Undo else Icons.Outlined.CheckCircleOutline,
              title = if (isStaged) "Unstage File" else "Stage File with Git",
              subtitle = if (isStaged) "Remove from staging area" else "Stage changes for next commit",
              tint = TerminalGreen
            ) {
              showFileOptionsDialog = false
              if (isStaged) viewModel.unstageFile(file.path) else viewModel.stageFile(file.path)
            }
          }

          // Copy Path
          FileOptionRowItem(
            icon = Icons.Outlined.ContentCopy,
            title = "Copy Relative Path",
            subtitle = file.path,
            tint = TextSecondary
          ) {
            showFileOptionsDialog = false
            clipboardManager.setText(AnnotatedString(file.path))
          }

          // Duplicate File
          FileOptionRowItem(
            icon = Icons.Outlined.FolderSpecial,
            title = "Duplicate File",
            subtitle = "Create a copy with identical content",
            tint = TextSecondary
          ) {
            showFileOptionsDialog = false
            viewModel.duplicateFile(file.path)
          }

          // Rename
          FileOptionRowItem(
            icon = Icons.Outlined.DriveFileRenameOutline,
            title = "Rename File",
            subtitle = "Change file name or extension",
            tint = TextSecondary
          ) {
            showFileOptionsDialog = false
            renameInput = file.name
            showRenameDialog = true
          }

          // Delete
          FileOptionRowItem(
            icon = Icons.Outlined.Delete,
            title = "Delete File",
            subtitle = "Permanently remove from workspace",
            tint = DangerRed
          ) {
            showFileOptionsDialog = false
            showDeleteConfirmDialog = true
          }
        }
      },
      confirmButton = {},
      dismissButton = {
        TextButton(onClick = { showFileOptionsDialog = false }) {
          Text("Close", color = TextMuted)
        }
      }
    )
  }

  // Folder Options Dialog
  if (showFolderOptionsDialog && selectedFileForMenu != null) {
    val folder = selectedFileForMenu!!
    AlertDialog(
      onDismissRequest = { showFolderOptionsDialog = false },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp),
      title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Filled.Folder, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(20.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Column {
            Text(
              text = folder.name,
              color = TextPrimary,
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = folder.path,
              color = TextMuted,
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace
            )
          }
        }
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          FileOptionRowItem(
            icon = Icons.Default.Add,
            title = "New File in this folder",
            subtitle = "Create file under ${folder.path}/",
            tint = ElectricBlueGlow
          ) {
            showFolderOptionsDialog = false
            targetFolderForCreation = folder.path
            newFileNameInput = ""
            showNewFileDialog = true
          }

          FileOptionRowItem(
            icon = Icons.Outlined.CreateNewFolder,
            title = "New Subfolder",
            subtitle = "Create subfolder under ${folder.path}/",
            tint = CyanAccent
          ) {
            showFolderOptionsDialog = false
            targetFolderForCreation = folder.path
            newFolderNameInput = ""
            showNewFolderDialog = true
          }

          FileOptionRowItem(
            icon = Icons.Outlined.ContentCopy,
            title = "Copy Folder Path",
            subtitle = folder.path,
            tint = TextSecondary
          ) {
            showFolderOptionsDialog = false
            clipboardManager.setText(AnnotatedString(folder.path))
          }

          FileOptionRowItem(
            icon = Icons.Outlined.DriveFileRenameOutline,
            title = "Rename Folder",
            subtitle = "Change directory name",
            tint = TextSecondary
          ) {
            showFolderOptionsDialog = false
            renameInput = folder.name
            showRenameDialog = true
          }

          FileOptionRowItem(
            icon = Icons.Outlined.Delete,
            title = "Delete Folder",
            subtitle = "Permanently delete folder and all contents",
            tint = DangerRed
          ) {
            showFolderOptionsDialog = false
            showDeleteConfirmDialog = true
          }
        }
      },
      confirmButton = {},
      dismissButton = {
        TextButton(onClick = { showFolderOptionsDialog = false }) {
          Text("Close", color = TextMuted)
        }
      }
    )
  }

  // Safe Delete Confirmation Dialog
  if (showDeleteConfirmDialog && selectedFileForMenu != null) {
    val file = selectedFileForMenu!!
    AlertDialog(
      onDismissRequest = { showDeleteConfirmDialog = false },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp),
      icon = {
        Icon(
          imageVector = Icons.Outlined.Warning,
          contentDescription = "Warning",
          tint = DangerRed,
          modifier = Modifier.size(32.dp)
        )
      },
      title = {
        Text(
          text = if (file.isDirectory) "Delete Folder?" else "Delete File?",
          color = TextPrimary,
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold
        )
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = if (file.isDirectory) {
              "Are you sure you want to permanently delete folder \"${file.name}\" and ALL of its contents?"
            } else {
              "Are you sure you want to permanently delete \"${file.name}\"?"
            },
            color = TextSecondary,
            fontSize = 13.sp
          )
          Surface(
            color = DarkBackground,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(
              text = file.path,
              color = DangerRed,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace,
              modifier = Modifier.padding(8.dp)
            )
          }
          Text(
            text = "This action is permanent and cannot be undone.",
            color = TextMuted,
            fontSize = 11.sp
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.deleteFile(file.path)
            showDeleteConfirmDialog = false
          },
          colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
        ) {
          Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Delete Permanently")
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteConfirmDialog = false }) {
          Text("Cancel", color = TextSecondary)
        }
      }
    )
  }

  // Create New File Dialog
  if (showNewFileDialog) {
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    AlertDialog(
      onDismissRequest = { showNewFileDialog = false },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp),
      title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Outlined.Article, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("Create New File", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (targetFolderForCreation.isNotBlank()) {
            Text(
              text = "Target directory: $targetFolderForCreation/",
              color = CyanAccent,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace
            )
          }
          Text("Enter file name or relative path:", color = TextSecondary, fontSize = 12.sp)
          TextField(
            value = newFileNameInput,
            onValueChange = {
              newFileNameInput = it
              hasError = false
            },
            placeholder = { Text("e.g. utils.ts, components/Button.tsx", color = TextMuted, fontSize = 12.sp) },
            singleLine = true,
            isError = hasError,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("input_new_file"),
            colors = TextFieldDefaults.colors(
              focusedContainerColor = DarkBackground,
              unfocusedContainerColor = DarkBackground,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary,
              focusedIndicatorColor = ElectricBlue,
              unfocusedIndicatorColor = DarkBorder
            )
          )
          if (hasError) {
            Text(errorMessage, color = DangerRed, fontSize = 11.sp)
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val input = newFileNameInput.trim()
            if (input.isBlank()) {
              hasError = true
              errorMessage = "File name cannot be blank"
              return@Button
            }
            val finalPath = if (targetFolderForCreation.isNotBlank()) "$targetFolderForCreation/$input" else input
            val success = viewModel.createFile(finalPath, "")
            if (success) {
              showNewFileDialog = false
              onNavigate(AppDestination.EDITOR)
            } else {
              hasError = true
              errorMessage = "Failed to create file (already exists or invalid path)"
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
        ) {
          Text("Create & Open")
        }
      },
      dismissButton = {
        TextButton(onClick = { showNewFileDialog = false }) {
          Text("Cancel", color = TextMuted)
        }
      }
    )
  }

  // Create New Folder Dialog
  if (showNewFolderDialog) {
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    AlertDialog(
      onDismissRequest = { showNewFolderDialog = false },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp),
      title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("Create New Folder", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (targetFolderForCreation.isNotBlank()) {
            Text(
              text = "Parent directory: $targetFolderForCreation/",
              color = CyanAccent,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace
            )
          }
          Text("Enter folder name or path:", color = TextSecondary, fontSize = 12.sp)
          TextField(
            value = newFolderNameInput,
            onValueChange = {
              newFolderNameInput = it
              hasError = false
            },
            placeholder = { Text("e.g. services, hooks, assets", color = TextMuted, fontSize = 12.sp) },
            singleLine = true,
            isError = hasError,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("input_new_folder"),
            colors = TextFieldDefaults.colors(
              focusedContainerColor = DarkBackground,
              unfocusedContainerColor = DarkBackground,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary,
              focusedIndicatorColor = CyanAccent,
              unfocusedIndicatorColor = DarkBorder
            )
          )
          if (hasError) {
            Text(errorMessage, color = DangerRed, fontSize = 11.sp)
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val input = newFolderNameInput.trim()
            if (input.isBlank()) {
              hasError = true
              errorMessage = "Folder name cannot be blank"
              return@Button
            }
            val finalPath = if (targetFolderForCreation.isNotBlank()) "$targetFolderForCreation/$input" else input
            val success = viewModel.createDirectory(finalPath)
            if (success) {
              showNewFolderDialog = false
            } else {
              hasError = true
              errorMessage = "Failed to create folder"
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
        ) {
          Text("Create Folder")
        }
      },
      dismissButton = {
        TextButton(onClick = { showNewFolderDialog = false }) {
          Text("Cancel", color = TextMuted)
        }
      }
    )
  }

  // Rename Dialog
  if (showRenameDialog && selectedFileForMenu != null) {
    val file = selectedFileForMenu!!
    AlertDialog(
      onDismissRequest = { showRenameDialog = false },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp),
      title = {
        Text(
          text = "Rename ${if (file.isDirectory) "Folder" else "File"}",
          color = TextPrimary,
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold
        )
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Current: ${file.name}", color = TextMuted, fontSize = 11.sp)
          TextField(
            value = renameInput,
            onValueChange = { renameInput = it },
            singleLine = true,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("input_rename_file"),
            colors = TextFieldDefaults.colors(
              focusedContainerColor = DarkBackground,
              unfocusedContainerColor = DarkBackground,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary,
              focusedIndicatorColor = ElectricBlue,
              unfocusedIndicatorColor = DarkBorder
            )
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val clean = renameInput.trim()
            if (clean.isNotBlank() && clean != file.name) {
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

  // Ask AI Agent Dialog
  if (showAskAgentDialog && selectedFileForMenu != null) {
    val file = selectedFileForMenu!!
    AlertDialog(
      onDismissRequest = { showAskAgentDialog = false },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp),
      title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.AutoAwesome, contentDescription = "Agent", tint = ElectricBlueGlow, modifier = Modifier.size(20.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Column {
            Text("Ask Agent on ${file.name}", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Select an automated task to launch with AI", color = TextMuted, fontSize = 11.sp)
          }
        }
      },
      text = {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          AgentTaskPromptItem("Explain this file", "Deconstruct component logic, interfaces, and architecture") {
            showAskAgentDialog = false
            viewModel.runAgentTaskInNewSession("Explain the structure, functions, state flow, and edge cases in ${file.name}")
            onNavigate(AppDestination.AGENT)
          }

          AgentTaskPromptItem("Find bugs & vulnerability scan", "Static analysis for race conditions, leaks, and unhandled errors") {
            showAskAgentDialog = false
            viewModel.runAgentTaskInNewSession("Inspect ${file.name} for subtle bugs, memory leaks, security issues, and race conditions")
            onNavigate(AppDestination.AGENT)
          }

          AgentTaskPromptItem("Refactor & Clean code", "Modernize syntax, improve typing, and remove redundancies") {
            showAskAgentDialog = false
            viewModel.runAgentTaskInNewSession("Refactor ${file.name} for optimal readability, maintainability, and clean architecture")
            onNavigate(AppDestination.AGENT)
          }

          AgentTaskPromptItem("Write unit test suite", "Generate comprehensive unit tests covering happy and error paths") {
            showAskAgentDialog = false
            viewModel.runAgentTaskInNewSession("Write comprehensive unit tests with edge-case tests for ${file.name}")
            onNavigate(AppDestination.AGENT)
          }

          AgentTaskPromptItem("Optimize performance", "Enhance execution speed, reduce allocations, and optimize renders") {
            showAskAgentDialog = false
            viewModel.runAgentTaskInNewSession("Analyze and optimize runtime performance, memoization, and rendering in ${file.name}")
            onNavigate(AppDestination.AGENT)
          }

          AgentTaskPromptItem("Add doc comments & types", "Generate complete inline documentation and precise typings") {
            showAskAgentDialog = false
            viewModel.runAgentTaskInNewSession("Add complete JSDoc/KDoc comments and refine type definitions for all exports in ${file.name}")
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

// ==========================================
// TREE ROW COMPONENT
// ==========================================

@Composable
private fun FileTreeItemRow(
  file: ProjectFile,
  depth: Int,
  isExpanded: Boolean,
  gitState: FileGitState,
  diffCounts: Pair<Int, Int>?,
  showFullPath: Boolean,
  onClick: () -> Unit,
  onMoreClick: () -> Unit,
  onAskAgent: () -> Unit
) {
  val (icon, iconColor) = getFileIconAndColor(file.name, file.isDirectory, isExpanded)

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .clickable(onClick = onClick),
    color = if (file.isDirectory) DarkSurfaceElevated else DarkSurface,
    border = BorderStroke(1.dp, if (file.isDirectory) DarkBorderSubtle else Color.Transparent)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(
          start = if (showFullPath) 10.dp else (8 + depth * 14).dp,
          top = 8.dp,
          bottom = 8.dp,
          end = 8.dp
        ),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f)
      ) {
        // Expand/Collapse Chevron for directories
        if (file.isDirectory) {
          Icon(
            imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            tint = TextMuted,
            modifier = Modifier.size(16.dp)
          )
          Spacer(modifier = Modifier.width(3.dp))
        } else if (!showFullPath && depth > 0) {
          Spacer(modifier = Modifier.width(6.dp))
        }

        // File/Folder Icon
        Icon(
          imageVector = icon,
          contentDescription = file.name,
          tint = iconColor,
          modifier = Modifier.size(18.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // File Name & Path
        Column(modifier = Modifier.weight(1f)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text(
              text = file.name,
              color = if (gitState != FileGitState.NONE) gitState.badgeColor else TextPrimary,
              fontSize = 13.sp,
              fontWeight = if (file.isDirectory) FontWeight.SemiBold else FontWeight.Medium,
              fontFamily = if (!file.isDirectory) FontFamily.Monospace else FontFamily.Default,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )

            // Git Status Badge (M, S, U, !)
            if (gitState != FileGitState.NONE) {
              Surface(
                color = gitState.bgColor,
                shape = RoundedCornerShape(3.dp),
                border = BorderStroke(0.5.dp, gitState.badgeColor)
              ) {
                Text(
                  text = gitState.label,
                  color = gitState.badgeColor,
                  fontSize = 9.sp,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
              }
            }

            // Diff line counts (+3 -1)
            if (diffCounts != null && (diffCounts.first > 0 || diffCounts.second > 0)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                if (diffCounts.first > 0) {
                  Text("+${diffCounts.first}", color = TerminalGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                if (diffCounts.second > 0) {
                  Spacer(modifier = Modifier.width(3.dp))
                  Text("-${diffCounts.second}", color = DangerRed, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
              }
            }
          }

          // Full path caption (when searching or displaying long names)
          if (showFullPath) {
            Text(
              text = file.path,
              color = TextMuted,
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }

      // Trailing actions & metadata
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
      ) {
        if (!file.isDirectory && file.sizeBytes > 0) {
          Text(
            text = formatFileSize(file.sizeBytes),
            color = TextMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(end = 4.dp)
          )
        }

        if (!file.isDirectory) {
          IconButton(
            onClick = onAskAgent,
            modifier = Modifier.size(28.dp)
          ) {
            Icon(
              imageVector = Icons.Default.AutoAwesome,
              contentDescription = "Ask Agent",
              tint = CyanAccent,
              modifier = Modifier.size(15.dp)
            )
          }
        }

        IconButton(
          onClick = onMoreClick,
          modifier = Modifier.size(28.dp)
        ) {
          Icon(
            imageVector = Icons.Outlined.MoreVert,
            contentDescription = "Options",
            tint = TextMuted,
            modifier = Modifier.size(16.dp)
          )
        }
      }
    }
  }
}

// ==========================================
// HELPER UI COMPONENTS
// ==========================================

@Composable
private fun FileOptionRowItem(
  icon: ImageVector,
  title: String,
  subtitle: String,
  tint: Color,
  onClick: () -> Unit
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .clickable(onClick = onClick),
    color = DarkBackground,
    border = BorderStroke(1.dp, DarkBorderSubtle)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(32.dp)
          .clip(CircleShape)
          .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
      ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
      }
      Spacer(modifier = Modifier.width(10.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(text = title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(text = subtitle, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

@Composable
private fun AgentTaskPromptItem(
  title: String,
  desc: String,
  onClick: () -> Unit
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .clickable(onClick = onClick),
    color = DarkBackground,
    border = BorderStroke(1.dp, DarkBorderSubtle)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 10.dp, vertical = 9.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        imageVector = Icons.Default.AutoAwesome,
        contentDescription = null,
        tint = CyanAccent,
        modifier = Modifier.size(16.dp)
      )
      Spacer(modifier = Modifier.width(10.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(text = title, color = ElectricBlueGlow, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(text = desc, color = TextMuted, fontSize = 11.sp)
      }
    }
  }
}

@Composable
private fun EmptyWorkspaceView(
  onCreateReadme: () -> Unit,
  onCreateFile: () -> Unit,
  onOpenTerminal: () -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    Icon(
      imageVector = Icons.Outlined.FolderOpen,
      contentDescription = null,
      tint = TextMuted,
      modifier = Modifier.size(56.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text("Empty Workspace", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      "No project files found in this workspace directory yet.",
      color = TextSecondary,
      fontSize = 12.sp,
      textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
    Spacer(modifier = Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        onClick = onCreateReadme,
        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
        shape = RoundedCornerShape(8.dp)
      ) {
        Text("Create README.md", fontSize = 12.sp)
      }
      OutlinedButton(
        onClick = onCreateFile,
        border = BorderStroke(1.dp, DarkBorder),
        shape = RoundedCornerShape(8.dp)
      ) {
        Text("Create index.ts", color = TextPrimary, fontSize = 12.sp)
      }
    }
    Spacer(modifier = Modifier.height(10.dp))
    TextButton(onClick = onOpenTerminal) {
      Icon(Icons.Outlined.Terminal, contentDescription = null, tint = TerminalGreen, modifier = Modifier.size(16.dp))
      Spacer(modifier = Modifier.width(6.dp))
      Text("Open Linux Terminal", color = TerminalGreen, fontSize = 12.sp)
    }
  }
}

@Composable
private fun EmptySearchView(query: String, onClear: () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    Icon(
      imageVector = Icons.Default.Search,
      contentDescription = null,
      tint = TextMuted,
      modifier = Modifier.size(48.dp)
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text("No matching files", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(4.dp))
    Text("No files or folders found matching \"$query\"", color = TextMuted, fontSize = 12.sp)
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedButton(
      onClick = onClear,
      border = BorderStroke(1.dp, DarkBorder),
      shape = RoundedCornerShape(8.dp)
    ) {
      Text("Clear Search", color = TextPrimary, fontSize = 12.sp)
    }
  }
}

// ==========================================
// UTILITY FUNCTIONS
// ==========================================

private fun formatFileSize(bytes: Long): String = when {
  bytes <= 0L -> "0 B"
  bytes < 1024 -> "$bytes B"
  bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
  else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

private fun getFileIconAndColor(name: String, isDirectory: Boolean, isExpanded: Boolean): Pair<ImageVector, Color> {
  if (isDirectory) {
    return if (isExpanded) Icons.Filled.FolderOpen to ElectricBlueGlow else Icons.Filled.Folder to ElectricBlue
  }
  val ext = name.substringAfterLast('.', "").lowercase()
  return when (ext) {
    "ts", "js" -> Icons.Outlined.Code to CyanAccent
    "tsx", "jsx" -> Icons.Outlined.Code to CyanAccent
    "kt", "kts", "java" -> Icons.Outlined.Code to IndigoAccent
    "py" -> Icons.Outlined.Code to WarningAmber
    "rs", "go", "c", "cpp", "h", "hpp" -> Icons.Outlined.Code to SyntaxKeyword
    "json" -> Icons.Outlined.DataObject to WarningAmber
    "yaml", "yml", "toml" -> Icons.Outlined.DataObject to WarningAmber
    "md", "txt", "rst" -> Icons.AutoMirrored.Outlined.Article to ElectricBlueGlow
    "html", "htm", "xml" -> Icons.Outlined.Language to SyntaxFunction
    "css", "scss", "sass", "less" -> Icons.Outlined.Language to SyntaxType
    "sh", "bash", "zsh" -> Icons.Outlined.Terminal to TerminalGreen
    "png", "jpg", "jpeg", "svg", "webp", "gif", "ico" -> Icons.Outlined.Image to TerminalGreen
    "gradle", "properties", "env", "lock" -> Icons.Outlined.Settings to TextSecondary
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile to TextSecondary
  }
}
