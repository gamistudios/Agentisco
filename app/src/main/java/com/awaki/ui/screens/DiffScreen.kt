package com.awaki.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awaki.core.model.AppDestination
import com.awaki.data.model.DiffLine
import com.awaki.data.model.DiffLineType
import com.awaki.data.model.FileDiff
import com.awaki.data.model.ProjectFile
import com.awaki.data.repository.WorkspaceRepository
import com.awaki.ui.WorkspaceViewModel
import com.awaki.ui.theme.*
import com.awaki.ui.theme.AwakiTheme
import com.awaki.workspace.git.DiffCopyType
import com.awaki.workspace.git.GitFileStatus
import com.awaki.workspace.git.GitRepoStatus
import com.awaki.workspace.git.GitStatusCode
import kotlinx.coroutines.launch

internal enum class ChangesViewMode {
  FILES_LIST,
  DIFF_VIEWER
}

/**
 * The Changes page header: back, title, branch chip and the file metrics under
 * them, then the actions that switch view mode, search, refresh and jump to Git.
 *
 * It has to survive a narrow phone with a long branch name. The actions keep
 * their width, so the title side is the one that gives way — it scrolls
 * horizontally and its text is capped at one ellipsized line, because text that
 * wraps here grows the header downward and eats the file list.
 */
@Composable
internal fun ChangesHeader(
  branch: String,
  filesChanged: Int,
  additions: Int,
  deletions: Int,
  viewMode: ChangesViewMode,
  canShowDiff: Boolean,
  isSearchOpen: Boolean,
  onToggleSearch: () -> Unit,
  onViewModeChange: (ChangesViewMode) -> Unit,
  onRefresh: () -> Unit,
  onBack: () -> Unit,
  onOpenGit: () -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 14.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.weight(1f, fill = false)
    ) {
      IconButton(
        onClick = onBack,
        modifier = Modifier
          .size(32.dp)
          .testTag("btn_changes_back")
      ) {
        Icon(Icons.Default.ChevronLeft, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
      }
      Spacer(modifier = Modifier.width(6.dp))
      Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = "Changes",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
          )
          Spacer(modifier = Modifier.width(8.dp))
          // Branch chip
          Surface(
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = Icons.Outlined.Commit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(11.dp)
              )
              Spacer(modifier = Modifier.width(3.dp))
              Text(
                text = branch.ifBlank { "main" },
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp).testTag("changes_branch")
              )
            }
          }
        }
        // Subtitle metrics
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = "$filesChanged files changed",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            maxLines = 1
          )
          if (additions > 0 || deletions > 0) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "+$additions",
              color = AwakiTheme.extra.success,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              fontFamily = FontFamily.Monospace,
              maxLines = 1
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = "-$deletions",
              color = MaterialTheme.colorScheme.error,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              fontFamily = FontFamily.Monospace,
              maxLines = 1
            )
          }
        }
      }
    }

    // Header action buttons
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      // Refresh
      IconButton(
        onClick = onRefresh,
        modifier = Modifier
          .size(32.dp)
          .testTag("btn_refresh_changes")
      ) {
        Icon(Icons.Default.Refresh, contentDescription = "Refresh changes", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
      }

      // Search toggle
      IconButton(
        onClick = onToggleSearch,
        modifier = Modifier.size(32.dp)
      ) {
        Icon(
          if (isSearchOpen) Icons.Default.Close else Icons.Default.Search,
          contentDescription = "Search files",
          tint = if (isSearchOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(18.dp)
        )
      }

      // View mode toggle (Files List vs Diff Viewer)
      Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.padding(start = 2.dp)
      ) {
        Row(modifier = Modifier.padding(2.dp)) {
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(4.dp))
              .background(if (viewMode == ChangesViewMode.FILES_LIST) MaterialTheme.colorScheme.primary else Color.Transparent)
              .clickable { onViewModeChange(ChangesViewMode.FILES_LIST) }
              .padding(horizontal = 7.dp, vertical = 4.dp)
          ) {
            Icon(
              Icons.AutoMirrored.Outlined.FormatListBulleted,
              contentDescription = "Files List",
              tint = if (viewMode == ChangesViewMode.FILES_LIST) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(15.dp)
            )
          }
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(4.dp))
              .background(if (viewMode == ChangesViewMode.DIFF_VIEWER) MaterialTheme.colorScheme.primary else Color.Transparent)
              .clickable {
                if (canShowDiff) {
                  onViewModeChange(ChangesViewMode.DIFF_VIEWER)
                }
              }
              .padding(horizontal = 7.dp, vertical = 4.dp)
          ) {
            Icon(
              Icons.Outlined.Difference,
              contentDescription = "Diff Viewer",
              tint = if (viewMode == ChangesViewMode.DIFF_VIEWER) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(15.dp)
            )
          }
        }
      }

      // Link to Git page
      OutlinedButton(
        onClick = onOpenGit,
        modifier = Modifier
          .height(30.dp)
          .testTag("btn_goto_git"),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
      ) {
        Icon(Icons.Outlined.ForkRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
        Spacer(modifier = Modifier.width(3.dp))
        Text("Git", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Medium)
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffScreen(
  viewModel: WorkspaceViewModel,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  val repoStatus by viewModel.repoStatus.collectAsState()
  val fileDiffs by viewModel.fileDiffs.collectAsState()
  val stagedFilePaths by viewModel.stagedFiles.collectAsState()
  val commitMessage by viewModel.commitMessage.collectAsState()
  val commitGenState by viewModel.commitGenState.collectAsState()
  val activeGitOp by viewModel.activeGitOperationText.collectAsState()
  val gitFeedback by viewModel.gitOperationFeedback.collectAsState()
  val gitError by viewModel.gitError.collectAsState()
  val activeProject by viewModel.activeProject.collectAsState()

  val clipboard = LocalClipboardManager.current
  val coroutineScope = rememberCoroutineScope()
  val listState = rememberLazyListState()

  var viewMode by remember { mutableStateOf(ChangesViewMode.FILES_LIST) }
  var selectedFileIndex by remember { mutableIntStateOf(0) }
  var searchQuery by remember { mutableStateOf("") }
  var isSearchOpen by remember { mutableStateOf(false) }

  // Multi-selection state
  var isMultiSelectMode by remember { mutableStateOf(false) }
  val selectedFiles = remember { mutableStateListOf<String>() }

  // Confirmation dialog states
  var fileToDiscard by remember { mutableStateOf<String?>(null) }
  var fileToDeleteUntracked by remember { mutableStateOf<String?>(null) }
  var showDiscardAllConfirm by remember { mutableStateOf(false) }

  // AI analysis modal bottom sheet states
  var aiAnalysisTitle by remember { mutableStateOf<String?>(null) }
  var aiAnalysisContent by remember { mutableStateOf<String?>(null) }
  var isAiAnalysisLoading by remember { mutableStateOf(false) }

  // Dropdown menus
  var showCommitDropdown by remember { mutableStateOf(false) }
  var showCopyDiffDropdown by remember { mutableStateOf(false) }

  // Total metrics
  val totalAdditions = remember(fileDiffs) { fileDiffs.sumOf { it.additionsCount } }
  val totalDeletions = remember(fileDiffs) { fileDiffs.sumOf { it.deletionsCount } }

  // Split changes based on repoStatus or fallback to fileDiffs
  val stagedList = remember(repoStatus, stagedFilePaths, fileDiffs) {
    if (repoStatus.stagedFiles.isNotEmpty()) {
      repoStatus.stagedFiles
    } else {
      fileDiffs.filter { stagedFilePaths.contains(it.filePath) }.map {
        val st = if (it.originalContent.isEmpty() && it.newContent.isNotEmpty()) {
          GitStatusCode.ADDED
        } else if (it.newContent.isEmpty() && it.originalContent.isNotEmpty()) {
          GitStatusCode.DELETED
        } else {
          GitStatusCode.MODIFIED
        }
        GitFileStatus(
          path = it.filePath,
          status = st,
          isStaged = true,
          additions = it.additionsCount,
          deletions = it.deletionsCount
        )
      }
    }
  }

  val unstagedList = remember(repoStatus, stagedFilePaths, fileDiffs) {
    if (repoStatus.unstagedFiles.isNotEmpty()) {
      repoStatus.unstagedFiles
    } else {
      fileDiffs.filter { !stagedFilePaths.contains(it.filePath) }.map {
        val st = if (it.originalContent.isEmpty() && it.newContent.isNotEmpty()) {
          GitStatusCode.ADDED
        } else if (it.newContent.isEmpty() && it.originalContent.isNotEmpty()) {
          GitStatusCode.DELETED
        } else {
          GitStatusCode.MODIFIED
        }
        GitFileStatus(
          path = it.filePath,
          status = st,
          isStaged = false,
          additions = it.additionsCount,
          deletions = it.deletionsCount
        )
      }
    }
  }

  val untrackedList = remember(repoStatus) { repoStatus.untrackedFiles }
  val conflictedList = remember(repoStatus) { repoStatus.conflictedFiles }

  // Filtered lists
  fun filterFiles(list: List<GitFileStatus>): List<GitFileStatus> {
    if (searchQuery.isBlank()) return list
    return list.filter { it.path.contains(searchQuery.trim(), ignoreCase = true) }
  }

  val filteredStaged = remember(stagedList, searchQuery) { filterFiles(stagedList) }
  val filteredUnstaged = remember(unstagedList, searchQuery) { filterFiles(unstagedList) }
  val filteredUntracked = remember(untrackedList, searchQuery) { filterFiles(untrackedList) }
  val filteredConflicted = remember(conflictedList, searchQuery) { filterFiles(conflictedList) }

  val totalFilesCount = stagedList.size + unstagedList.size + untrackedList.size + conflictedList.size

  // Clamped index for diff viewer
  val currentDiff = remember(fileDiffs, selectedFileIndex) {
    if (fileDiffs.isNotEmpty()) {
      fileDiffs.getOrNull(selectedFileIndex.coerceIn(0, fileDiffs.lastIndex)) ?: fileDiffs.first()
    } else null
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
  ) {
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // TOP HEADER
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    Surface(
      modifier = Modifier.fillMaxWidth(),
      color = MaterialTheme.colorScheme.surface,
      border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
      Column {
        ChangesHeader(
          branch = activeProject.branch,
          filesChanged = totalFilesCount,
          additions = totalAdditions,
          deletions = totalDeletions,
          viewMode = viewMode,
          canShowDiff = fileDiffs.isNotEmpty(),
          isSearchOpen = isSearchOpen,
          onToggleSearch = {
            isSearchOpen = !isSearchOpen
            if (!isSearchOpen) searchQuery = ""
          },
          onViewModeChange = { viewMode = it },
          onRefresh = { viewModel.refreshDiffsAndGit() },
          onBack = { onNavigate(AppDestination.AGENT) },
          onOpenGit = { onNavigate(AppDestination.GIT) }
        )

        // Inline Search Bar
        AnimatedVisibility(
          visible = isSearchOpen,
          enter = expandVertically() + fadeIn(),
          exit = shrinkVertically() + fadeOut()
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(MaterialTheme.colorScheme.surfaceContainer)
              .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = AwakiTheme.extra.textMuted, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
              value = searchQuery,
              onValueChange = { searchQuery = it },
              placeholder = { Text("Filter changed files...", color = AwakiTheme.extra.textMuted, fontSize = 12.sp) },
              singleLine = true,
              modifier = Modifier
                .weight(1f)
                .height(44.dp),
              colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
              ),
              textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
            )
            if (searchQuery.isNotEmpty()) {
              IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = AwakiTheme.extra.textMuted, modifier = Modifier.size(15.dp))
              }
            }
          }
        }
      }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // ACTIVE OPERATION / FEEDBACK / ERROR BANNERS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    if (activeGitOp != null) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 7.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
          )
          Spacer(modifier = Modifier.width(10.dp))
          Text(
            text = activeGitOp!!,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
          )
        }
      }
    }

    if (gitFeedback != null) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AwakiTheme.extra.successContainer.copy(alpha = 0.25f),
        border = androidx.compose.foundation.BorderStroke(1.dp, AwakiTheme.extra.success.copy(alpha = 0.4f))
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 7.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = AwakiTheme.extra.success, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = gitFeedback!!,
              color = AwakiTheme.extra.success,
              fontSize = 12.sp
            )
          }
          IconButton(
            onClick = { viewModel.clearGitOperationFeedback() },
            modifier = Modifier.size(24.dp)
          ) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = AwakiTheme.extra.textMuted, modifier = Modifier.size(14.dp))
          }
        }
      }
    }

    if (gitError != null) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = gitError!!,
            color = MaterialTheme.colorScheme.error,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.weight(1f)
          )
          if (gitError!!.contains("index.lock", ignoreCase = true)) {
            TextButton(onClick = { viewModel.clearGitIndexLock() }) {
              Text("Remove lock", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
          }
          IconButton(
            onClick = { clipboard.setText(AnnotatedString(gitError!!)) },
            modifier = Modifier.size(26.dp)
          ) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy error", tint = AwakiTheme.extra.textMuted, modifier = Modifier.size(13.dp))
          }
          IconButton(
            onClick = { viewModel.dismissGitError() },
            modifier = Modifier.size(26.dp)
          ) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = AwakiTheme.extra.textMuted, modifier = Modifier.size(14.dp))
          }
        }
      }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // MAIN CONTENT (Files List or Diff Viewer)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    if (viewMode == ChangesViewMode.FILES_LIST) {
      LazyColumn(
        state = listState,
        modifier = Modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 32.dp)
      ) {
        // ── 1. QUICK COMMIT BOX (VS Code style) ──
        item {
          QuickCommitCard(
            commitMessage = commitMessage,
            stagedCount = stagedList.size,
            commitGenState = commitGenState,
            onMessageChange = { viewModel.updateCommitMessage(it) },
            onGenerateMessage = { viewModel.generateCommitMessageWithAgent() },
            onCommit = { viewModel.commitStagedChanges() },
            onCommitAndPush = { viewModel.commitAndPush() },
            onAmend = { viewModel.commitStagedChanges(amend = true) },
            onStageAllAndCommit = {
              viewModel.stageAll()
              viewModel.commitStagedChanges()
            }
          )
        }

        // ── 2. MULTI-SELECTION TOOLBAR (if active) ──
        if (isMultiSelectMode) {
          item {
            MultiSelectActionBar(
              selectedCount = selectedFiles.size,
              onSelectAll = {
                selectedFiles.clear()
                val allPaths = (stagedList + unstagedList + untrackedList).map { it.path }
                selectedFiles.addAll(allPaths)
              },
              onDeselectAll = { selectedFiles.clear() },
              onStageSelected = {
                viewModel.repository.setFilesStaged(selectedFiles.toList(), true)
                selectedFiles.clear()
                isMultiSelectMode = false
              },
              onUnstageSelected = {
                viewModel.repository.setFilesStaged(selectedFiles.toList(), false)
                selectedFiles.clear()
                isMultiSelectMode = false
              },
              onCancel = {
                selectedFiles.clear()
                isMultiSelectMode = false
              }
            )
          }
        }

        // Empty state
        if (totalFilesCount == 0) {
          item {
            EmptyChangesView(onNavigate = onNavigate)
          }
        } else {
          // Multi-select toggle bar
          item {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
              horizontalArrangement = Arrangement.End,
              verticalAlignment = Alignment.CenterVertically
            ) {
              TextButton(
                onClick = {
                  isMultiSelectMode = !isMultiSelectMode
                  if (!isMultiSelectMode) selectedFiles.clear()
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
              ) {
                Icon(
                  if (isMultiSelectMode) Icons.Default.Close else Icons.Outlined.Checklist,
                  contentDescription = null,
                  tint = if (isMultiSelectMode) MaterialTheme.colorScheme.primary else AwakiTheme.extra.textMuted,
                  modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  if (isMultiSelectMode) "Exit Multi-select" else "Select Files",
                  color = if (isMultiSelectMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                  fontSize = 11.sp
                )
              }
            }
          }

          // ── CONFLICTED FILES ──
          if (filteredConflicted.isNotEmpty()) {
            item {
              ChangesSectionHeader(
                title = "CONFLICTED FILES",
                count = filteredConflicted.size,
                badgeColor = MaterialTheme.colorScheme.error,
                actionLabel = null,
                onAction = null
              )
            }
            items(filteredConflicted, key = { "conflicted_${it.path}" }) { file ->
              ChangeFileRow(
                file = file,
                isMultiSelect = isMultiSelectMode,
                isSelected = selectedFiles.contains(file.path),
                onToggleSelect = {
                  if (selectedFiles.contains(file.path)) selectedFiles.remove(file.path) else selectedFiles.add(file.path)
                },
                onPrimaryAction = {
                  // Conflicted: open in editor
                  val projFile = ProjectFile(path = file.path, name = file.fileName, isDirectory = false)
                  viewModel.openFile(projFile)
                  onNavigate(AppDestination.EDITOR)
                },
                primaryIcon = Icons.Outlined.Edit,
                primaryTooltip = "Resolve in Editor",
                onSecondaryAction = {
                  val diffIdx = fileDiffs.indexOfFirst { it.filePath == file.path }
                  if (diffIdx >= 0) selectedFileIndex = diffIdx
                  viewMode = ChangesViewMode.DIFF_VIEWER
                },
                secondaryIcon = Icons.Outlined.Difference,
                secondaryTooltip = "View Diff",
                onRevert = null,
                onOpenEditor = {
                  val projFile = ProjectFile(path = file.path, name = file.fileName, isDirectory = false)
                  viewModel.openFile(projFile)
                  onNavigate(AppDestination.EDITOR)
                }
              )
            }
          }

          // ── STAGED CHANGES ──
          if (filteredStaged.isNotEmpty()) {
            item {
              ChangesSectionHeader(
                title = "STAGED CHANGES",
                count = filteredStaged.size,
                badgeColor = AwakiTheme.extra.success,
                actionLabel = "Unstage All",
                onAction = { viewModel.unstageAll() }
              )
            }
            items(filteredStaged, key = { "staged_${it.path}" }) { file ->
              ChangeFileRow(
                file = file,
                isMultiSelect = isMultiSelectMode,
                isSelected = selectedFiles.contains(file.path),
                onToggleSelect = {
                  if (selectedFiles.contains(file.path)) selectedFiles.remove(file.path) else selectedFiles.add(file.path)
                },
                onPrimaryAction = { viewModel.unstageFile(file.path) },
                primaryIcon = Icons.Default.Remove,
                primaryTooltip = "Unstage",
                onSecondaryAction = {
                  val diffIdx = fileDiffs.indexOfFirst { it.filePath == file.path }
                  if (diffIdx >= 0) selectedFileIndex = diffIdx
                  viewMode = ChangesViewMode.DIFF_VIEWER
                },
                secondaryIcon = Icons.Outlined.Difference,
                secondaryTooltip = "View Diff",
                onRevert = null,
                onOpenEditor = {
                  val projFile = ProjectFile(path = file.path, name = file.fileName, isDirectory = false)
                  viewModel.openFile(projFile)
                  onNavigate(AppDestination.EDITOR)
                }
              )
            }
          }

          // ── UNSTAGED CHANGES ──
          if (filteredUnstaged.isNotEmpty()) {
            item {
              ChangesSectionHeader(
                title = "CHANGES",
                count = filteredUnstaged.size,
                badgeColor = AwakiTheme.extra.warning,
                actionLabel = "Stage All",
                onAction = { viewModel.stageAll() },
                secondaryActionLabel = "Discard All",
                onSecondaryAction = { showDiscardAllConfirm = true }
              )
            }
            items(filteredUnstaged, key = { "unstaged_${it.path}" }) { file ->
              ChangeFileRow(
                file = file,
                isMultiSelect = isMultiSelectMode,
                isSelected = selectedFiles.contains(file.path),
                onToggleSelect = {
                  if (selectedFiles.contains(file.path)) selectedFiles.remove(file.path) else selectedFiles.add(file.path)
                },
                onPrimaryAction = { viewModel.stageFile(file.path) },
                primaryIcon = Icons.Default.Add,
                primaryTooltip = "Stage",
                onSecondaryAction = {
                  val diffIdx = fileDiffs.indexOfFirst { it.filePath == file.path }
                  if (diffIdx >= 0) selectedFileIndex = diffIdx
                  viewMode = ChangesViewMode.DIFF_VIEWER
                },
                secondaryIcon = Icons.Outlined.Difference,
                secondaryTooltip = "View Diff",
                onRevert = { fileToDiscard = file.path },
                onOpenEditor = {
                  val projFile = ProjectFile(path = file.path, name = file.fileName, isDirectory = false)
                  viewModel.openFile(projFile)
                  onNavigate(AppDestination.EDITOR)
                }
              )
            }
          }

          // ── UNTRACKED FILES ──
          if (filteredUntracked.isNotEmpty()) {
            item {
              ChangesSectionHeader(
                title = "UNTRACKED FILES",
                count = filteredUntracked.size,
                badgeColor = MaterialTheme.colorScheme.secondary,
                actionLabel = "Stage All",
                onAction = { viewModel.stageAll() }
              )
            }
            items(filteredUntracked, key = { "untracked_${it.path}" }) { file ->
              ChangeFileRow(
                file = file,
                isMultiSelect = isMultiSelectMode,
                isSelected = selectedFiles.contains(file.path),
                onToggleSelect = {
                  if (selectedFiles.contains(file.path)) selectedFiles.remove(file.path) else selectedFiles.add(file.path)
                },
                onPrimaryAction = { viewModel.stageFile(file.path) },
                primaryIcon = Icons.Default.Add,
                primaryTooltip = "Track / Stage",
                onSecondaryAction = {
                  val diffIdx = fileDiffs.indexOfFirst { it.filePath == file.path }
                  if (diffIdx >= 0) selectedFileIndex = diffIdx
                  viewMode = ChangesViewMode.DIFF_VIEWER
                },
                secondaryIcon = Icons.Outlined.Difference,
                secondaryTooltip = "View Diff",
                onRevert = { fileToDeleteUntracked = file.path },
                onOpenEditor = {
                  val projFile = ProjectFile(path = file.path, name = file.fileName, isDirectory = false)
                  viewModel.openFile(projFile)
                  onNavigate(AppDestination.EDITOR)
                },
                isUntracked = true
              )
            }
          }
        }
      }
    } else {
      // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
      // DIFF VIEWER MODE
      // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
      DiffViewerContent(
        diffs = fileDiffs,
        selectedIndex = selectedFileIndex,
        onSelectIndex = { selectedFileIndex = it },
        stagedPaths = stagedFilePaths,
        onToggleStage = { path ->
          if (stagedFilePaths.contains(path)) viewModel.unstageFile(path) else viewModel.stageFile(path)
        },
        onRevert = { path -> fileToDiscard = path },
        onOpenEditor = { path ->
          val projFile = ProjectFile(path = path, name = path.substringAfterLast('/'), isDirectory = false)
          viewModel.openFile(projFile)
          onNavigate(AppDestination.EDITOR)
        },
        onExplainAI = { diff ->
          coroutineScope.launch {
            aiAnalysisTitle = "AI Explanation — ${diff.filePath.substringAfterLast('/')}"
            isAiAnalysisLoading = true
            aiAnalysisContent = null
            val rawDiff = diff.lines.joinToString("\n") { it.text }
            val explanation = viewModel.explainChangesWithAgent(rawDiff)
            aiAnalysisContent = explanation
            isAiAnalysisLoading = false
          }
        },
        onReviewAI = { diff ->
          coroutineScope.launch {
            aiAnalysisTitle = "AI Code Review — ${diff.filePath.substringAfterLast('/')}"
            isAiAnalysisLoading = true
            aiAnalysisContent = null
            val rawDiff = diff.lines.joinToString("\n") { it.text }
            val review = viewModel.reviewChangesWithAgent(rawDiff)
            aiAnalysisContent = review
            isAiAnalysisLoading = false
          }
        },
        onCopyDiff = { diff ->
          val rawDiff = diff.lines.joinToString("\n") { it.text }
          clipboard.setText(AnnotatedString(rawDiff))
        },
        onBackToList = { viewMode = ChangesViewMode.FILES_LIST }
      )
    }
  }

  // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  // SAFETY CONFIRMATION DIALOGS
  // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  // 1. Discard single file changes
  if (fileToDiscard != null) {
    AlertDialog(
      onDismissRequest = { fileToDiscard = null },
      icon = { Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
      title = { Text("Discard Changes?", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
      text = {
        Text(
          "Are you sure you want to discard all working-tree changes to \"${fileToDiscard}\"?\n\nThis cannot be undone.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontSize = 13.sp
        )
      },
      confirmButton = {
        Button(
          onClick = {
            val path = fileToDiscard!!
            fileToDiscard = null
            viewModel.rejectDiff(path)
          },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
          Text("Discard Changes", color = MaterialTheme.colorScheme.onError)
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { fileToDiscard = null }) {
          Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      },
      containerColor = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(12.dp)
    )
  }

  // 2. Delete untracked file
  if (fileToDeleteUntracked != null) {
    AlertDialog(
      onDismissRequest = { fileToDeleteUntracked = null },
      icon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
      title = { Text("Delete Untracked File?", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
      text = {
        Text(
          "Are you sure you want to permanently delete \"${fileToDeleteUntracked}\"?\n\nThe file will be deleted from your storage.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontSize = 13.sp
        )
      },
      confirmButton = {
        Button(
          onClick = {
            val path = fileToDeleteUntracked!!
            fileToDeleteUntracked = null
            viewModel.deleteUntrackedFile(path)
          },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
          Text("Delete File", color = MaterialTheme.colorScheme.onError)
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { fileToDeleteUntracked = null }) {
          Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      },
      containerColor = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(12.dp)
    )
  }

  // 3. Discard all changes
  if (showDiscardAllConfirm) {
    AlertDialog(
      onDismissRequest = { showDiscardAllConfirm = false },
      icon = { Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
      title = { Text("Discard ALL Changes?", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
      text = {
        Text(
          "This will revert ALL unstaged changes across your entire project back to the last commit.\n\nAll uncommitted work will be permanently lost.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontSize = 13.sp
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showDiscardAllConfirm = false
            viewModel.rejectAllDiffs()
          },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
          Text("Discard All Changes", color = MaterialTheme.colorScheme.onError)
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { showDiscardAllConfirm = false }) {
          Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      },
      containerColor = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(12.dp)
    )
  }

  // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  // AI ANALYSIS BOTTOM SHEET
  // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  if (aiAnalysisTitle != null) {
    ModalBottomSheet(
      onDismissRequest = {
        aiAnalysisTitle = null
        aiAnalysisContent = null
      },
      containerColor = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 12.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = aiAnalysisTitle!!,
              color = MaterialTheme.colorScheme.onSurface,
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold
            )
          }
          if (aiAnalysisContent != null) {
            IconButton(
              onClick = { clipboard.setText(AnnotatedString(aiAnalysisContent!!)) },
              modifier = Modifier.size(30.dp)
            ) {
              Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (isAiAnalysisLoading) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(140.dp),
            contentAlignment = Alignment.Center
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
              Spacer(modifier = Modifier.height(12.dp))
              Text("Analyzing diff with AI…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
          }
        } else if (aiAnalysisContent != null) {
          Surface(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 420.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
          ) {
            LazyColumn(modifier = Modifier.padding(14.dp)) {
              item {
                Text(
                  text = aiAnalysisContent!!,
                  color = MaterialTheme.colorScheme.onSurface,
                  fontSize = 13.sp,
                  lineHeight = 19.sp
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
          onClick = {
            aiAnalysisTitle = null
            aiAnalysisContent = null
          },
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
          Text("Close", color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(modifier = Modifier.height(12.dp))
      }
    }
  }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// QUICK COMMIT CARD COMPONENT
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
private fun QuickCommitCard(
  commitMessage: String,
  stagedCount: Int,
  commitGenState: WorkspaceRepository.CommitGenState,
  onMessageChange: (String) -> Unit,
  onGenerateMessage: () -> Unit,
  onCommit: () -> Unit,
  onCommitAndPush: () -> Unit,
  onAmend: () -> Unit,
  onStageAllAndCommit: () -> Unit
) {
  var showDropdown by remember { mutableStateOf(false) }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 14.dp, vertical = 8.dp),
    shape = RoundedCornerShape(10.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      // Input Row
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
      ) {
        OutlinedTextField(
          value = commitMessage,
          onValueChange = onMessageChange,
          placeholder = {
            Text(
              "Commit message (type or generate with ✨)…",
              color = AwakiTheme.extra.textMuted,
              fontSize = 12.sp
            )
          },
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 52.dp, max = 100.dp)
            .testTag("input_commit_message"),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
          ),
          textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
          maxLines = 3
        )

        Spacer(modifier = Modifier.width(8.dp))

        // AI Generate Message Button (✨)
        IconButton(
          onClick = onGenerateMessage,
          enabled = commitGenState !is WorkspaceRepository.CommitGenState.Generating,
          modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .testTag("btn_ai_generate_commit")
        ) {
          if (commitGenState is WorkspaceRepository.CommitGenState.Generating) {
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.tertiary
            )
          } else {
            Icon(
              Icons.Outlined.AutoAwesome,
              contentDescription = "AI Generate Commit Message",
              tint = MaterialTheme.colorScheme.tertiary,
              modifier = Modifier.size(18.dp)
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Action Row: Commit Buttons + Staged Status
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Staged files count pill
        Row(verticalAlignment = Alignment.CenterVertically) {
          Surface(
            shape = RoundedCornerShape(4.dp),
            color = if (stagedCount > 0) AwakiTheme.extra.successContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceContainerHigh
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Box(
                modifier = Modifier
                  .size(6.dp)
                  .clip(CircleShape)
                  .background(if (stagedCount > 0) AwakiTheme.extra.success else AwakiTheme.extra.textMuted)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = if (stagedCount > 0) "$stagedCount staged" else "0 staged",
                color = if (stagedCount > 0) AwakiTheme.extra.success else AwakiTheme.extra.textMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
              )
            }
          }
        }

        // Commit Buttons
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          // Main Commit Button
          val canCommit = commitMessage.isNotBlank() && stagedCount > 0
          Button(
            onClick = {
              if (stagedCount > 0) {
                onCommit()
              } else {
                onStageAllAndCommit()
              }
            },
            enabled = commitMessage.isNotBlank(),
            modifier = Modifier
              .height(32.dp)
              .testTag("btn_commit"),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = if (stagedCount > 0) MaterialTheme.colorScheme.primary else AwakiTheme.extra.warning,
              disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
          ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(13.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = if (stagedCount > 0) "Commit" else "Stage & Commit",
              fontSize = 11.sp,
              fontWeight = FontWeight.SemiBold
            )
          }

          // Options Dropdown Button
          Box {
            OutlinedButton(
              onClick = { showDropdown = true },
              modifier = Modifier.size(32.dp),
              contentPadding = PaddingValues(0.dp),
              border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
              Icon(Icons.Default.KeyboardArrowDown, contentDescription = "More commit options", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }

            DropdownMenu(
              expanded = showDropdown,
              onDismissRequest = { showDropdown = false },
              modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
            ) {
              DropdownMenuItem(
                text = {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Upload, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Commit & Push", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                  }
                },
                onClick = {
                  showDropdown = false
                  onCommitAndPush()
                }
              )
              DropdownMenuItem(
                text = {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, tint = AwakiTheme.extra.warning, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Amend Previous Commit", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                  }
                },
                onClick = {
                  showDropdown = false
                  onAmend()
                }
              )
              DropdownMenuItem(
                text = {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = AwakiTheme.extra.success, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stage All & Commit", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                  }
                },
                onClick = {
                  showDropdown = false
                  onStageAllAndCommit()
                }
              )
            }
          }
        }
      }
    }
  }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// MULTI-SELECT ACTION BAR
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
private fun MultiSelectActionBar(
  selectedCount: Int,
  onSelectAll: () -> Unit,
  onDeselectAll: () -> Unit,
  onStageSelected: () -> Unit,
  onUnstageSelected: () -> Unit,
  onCancel: () -> Unit
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 14.dp, vertical = 4.dp),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "$selectedCount selected",
          color = MaterialTheme.colorScheme.onSurface,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(
          onClick = onSelectAll,
          contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
          modifier = Modifier.height(26.dp)
        ) {
          Text("All", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
        }
        TextButton(
          onClick = onDeselectAll,
          contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
          modifier = Modifier.height(26.dp)
        ) {
          Text("None", color = AwakiTheme.extra.textMuted, fontSize = 11.sp)
        }
      }

      Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Button(
          onClick = onStageSelected,
          enabled = selectedCount > 0,
          modifier = Modifier.height(28.dp),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = AwakiTheme.extra.success,
            contentColor = AwakiTheme.extra.onSuccess
          )
        ) {
          Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(12.dp))
          Spacer(modifier = Modifier.width(3.dp))
          Text("Stage", fontSize = 11.sp)
        }

        OutlinedButton(
          onClick = onUnstageSelected,
          enabled = selectedCount > 0,
          modifier = Modifier.height(28.dp),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
          border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
          Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
          Spacer(modifier = Modifier.width(3.dp))
          Text("Unstage", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
          Icon(Icons.Default.Close, contentDescription = "Cancel", tint = AwakiTheme.extra.textMuted, modifier = Modifier.size(15.dp))
        }
      }
    }
  }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// SECTION HEADER COMPONENT
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
private fun ChangesSectionHeader(
  title: String,
  count: Int,
  badgeColor: Color,
  actionLabel: String?,
  onAction: (() -> Unit)?,
  secondaryActionLabel: String? = null,
  onSecondaryAction: (() -> Unit)? = null
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 14.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = title,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp
      )
      Spacer(modifier = Modifier.width(6.dp))
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = badgeColor.copy(alpha = 0.2f)
      ) {
        Text(
          text = count.toString(),
          color = badgeColor,
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
      }
    }

    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (secondaryActionLabel != null && onSecondaryAction != null) {
        TextButton(
          onClick = onSecondaryAction,
          contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
          modifier = Modifier.height(24.dp)
        ) {
          Text(secondaryActionLabel, color = MaterialTheme.colorScheme.error, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
      }

      if (actionLabel != null && onAction != null) {
        TextButton(
          onClick = onAction,
          contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
          modifier = Modifier.height(24.dp)
        ) {
          Text(actionLabel, color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
      }
    }
  }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// CHANGE FILE ROW COMPONENT
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
private fun ChangeFileRow(
  file: GitFileStatus,
  isMultiSelect: Boolean,
  isSelected: Boolean,
  onToggleSelect: () -> Unit,
  onPrimaryAction: () -> Unit,
  primaryIcon: androidx.compose.ui.graphics.vector.ImageVector,
  primaryTooltip: String,
  onSecondaryAction: () -> Unit,
  secondaryIcon: androidx.compose.ui.graphics.vector.ImageVector,
  secondaryTooltip: String,
  onRevert: (() -> Unit)?,
  onOpenEditor: () -> Unit,
  isUntracked: Boolean = false
) {
  val statusCode = file.status
  val statusColor = when (statusCode) {
    GitStatusCode.MODIFIED -> AwakiTheme.extra.warning
    GitStatusCode.ADDED -> AwakiTheme.extra.success
    GitStatusCode.DELETED -> MaterialTheme.colorScheme.error
    GitStatusCode.UNTRACKED -> MaterialTheme.colorScheme.secondary
    GitStatusCode.CONFLICTED -> MaterialTheme.colorScheme.error
    GitStatusCode.RENAMED -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 14.dp, vertical = 2.dp),
    shape = RoundedCornerShape(8.dp),
    color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
    border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { if (isMultiSelect) onToggleSelect() else onSecondaryAction() }
        .padding(horizontal = 10.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (isMultiSelect) {
        Checkbox(
          checked = isSelected,
          onCheckedChange = { onToggleSelect() },
          modifier = Modifier.size(24.dp),
          colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
        )
        Spacer(modifier = Modifier.width(6.dp))
      }

      // Status letter badge (M, A, D, U, C, R)
      Surface(
        shape = RoundedCornerShape(4.dp),
        color = statusColor.copy(alpha = 0.2f),
        modifier = Modifier.size(22.dp)
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text(
            text = statusCode.code,
            color = statusColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      // File path details
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = file.fileName,
          color = MaterialTheme.colorScheme.onSurface,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          fontFamily = FontFamily.Monospace,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        if (file.directory.isNotBlank()) {
          Text(
            text = file.directory,
            color = AwakiTheme.extra.textMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      // Additions & deletions pills
      if (file.additions > 0 || file.deletions > 0) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(horizontal = 4.dp)
        ) {
          if (file.additions > 0) {
            Text(
              text = "+${file.additions}",
              color = AwakiTheme.extra.success,
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold
            )
          }
          if (file.deletions > 0) {
            Spacer(modifier = Modifier.width(3.dp))
            Text(
              text = "-${file.deletions}",
              color = MaterialTheme.colorScheme.error,
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold
            )
          }
        }
      }

      // Row Actions
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
      ) {
        // Primary Action: Stage / Unstage
        IconButton(
          onClick = onPrimaryAction,
          modifier = Modifier.size(28.dp)
        ) {
          Icon(primaryIcon, contentDescription = primaryTooltip, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
        }

        // Revert / Discard Action
        if (onRevert != null) {
          IconButton(
            onClick = onRevert,
            modifier = Modifier.size(28.dp)
          ) {
            Icon(
              if (isUntracked) Icons.Outlined.Delete else Icons.AutoMirrored.Outlined.Undo,
              contentDescription = if (isUntracked) "Delete" else "Discard",
              tint = if (isUntracked) MaterialTheme.colorScheme.error.copy(alpha = 0.8f) else AwakiTheme.extra.textMuted,
              modifier = Modifier.size(14.dp)
            )
          }
        }

        // Open in Editor Action
        IconButton(
          onClick = onOpenEditor,
          modifier = Modifier.size(28.dp)
        ) {
          Icon(Icons.Outlined.Edit, contentDescription = "Open in Editor", tint = AwakiTheme.extra.textMuted, modifier = Modifier.size(14.dp))
        }
      }
    }
  }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// EMPTY STATE COMPONENT
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
private fun EmptyChangesView(onNavigate: (AppDestination) -> Unit) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 24.dp, vertical = 60.dp),
    contentAlignment = Alignment.Center
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Surface(
        shape = CircleShape,
        color = AwakiTheme.extra.successContainer.copy(alpha = 0.25f),
        modifier = Modifier.size(64.dp)
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Outlined.CheckCircleOutline,
            contentDescription = null,
            tint = AwakiTheme.extra.success,
            modifier = Modifier.size(36.dp)
          )
        }
      }
      Spacer(modifier = Modifier.height(16.dp))
      Text(
        text = "Working Tree Clean",
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = "No changes detected. Files are synchronized with the git index.",
        color = AwakiTheme.extra.textMuted,
        fontSize = 12.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
      )
      Spacer(modifier = Modifier.height(20.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
          onClick = { onNavigate(AppDestination.EDITOR) },
          border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
          Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
          Spacer(modifier = Modifier.width(6.dp))
          Text("Open Editor", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Button(
          onClick = { onNavigate(AppDestination.GIT) },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
          Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Git Log & Branches", fontSize = 12.sp)
        }
      }
    }
  }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// DIFF VIEWER CONTENT COMPONENT
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
private fun DiffViewerContent(
  diffs: List<FileDiff>,
  selectedIndex: Int,
  onSelectIndex: (Int) -> Unit,
  stagedPaths: Set<String>,
  onToggleStage: (String) -> Unit,
  onRevert: (String) -> Unit,
  onOpenEditor: (String) -> Unit,
  onExplainAI: (FileDiff) -> Unit,
  onReviewAI: (FileDiff) -> Unit,
  onCopyDiff: (FileDiff) -> Unit,
  onBackToList: () -> Unit
) {
  if (diffs.isEmpty()) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(24.dp),
      contentAlignment = Alignment.Center
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No diffs available to inspect.", color = AwakiTheme.extra.textMuted, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onBackToList) { Text("Back to Changes List") }
      }
    }
    return
  }

  val currentDiff = diffs.getOrNull(selectedIndex.coerceIn(0, diffs.lastIndex)) ?: diffs.first()
  val isStaged = stagedPaths.contains(currentDiff.filePath)

  Column(modifier = Modifier.fillMaxSize()) {
    // 1. File selector strip (chips)
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .padding(horizontal = 10.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      diffs.forEachIndexed { index, diff ->
        val isSelected = index == selectedIndex
        val fileName = diff.filePath.substringAfterLast('/')

        Surface(
          shape = RoundedCornerShape(6.dp),
          color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
          border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.clickable { onSelectIndex(index) }
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = fileName,
              color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
              text = "+${diff.additionsCount}",
              color = AwakiTheme.extra.success,
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
              text = "-${diff.deletionsCount}",
              color = MaterialTheme.colorScheme.error,
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace
            )
          }
        }
      }
    }

    // 2. File Action Toolbar
    Surface(
      modifier = Modifier.fillMaxWidth(),
      color = MaterialTheme.colorScheme.surface,
      border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = currentDiff.filePath,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }

        Row(
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // AI Explain
          IconButton(
            onClick = { onExplainAI(currentDiff) },
            modifier = Modifier.size(30.dp)
          ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = "Explain with AI", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(15.dp))
          }

          // AI Review
          IconButton(
            onClick = { onReviewAI(currentDiff) },
            modifier = Modifier.size(30.dp)
          ) {
            Icon(Icons.Outlined.Psychology, contentDescription = "Review with AI", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
          }

          // Copy Diff
          IconButton(
            onClick = { onCopyDiff(currentDiff) },
            modifier = Modifier.size(30.dp)
          ) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy diff", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
          }

          // Stage / Unstage toggle
          OutlinedButton(
            onClick = { onToggleStage(currentDiff.filePath) },
            modifier = Modifier.height(28.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (isStaged) AwakiTheme.extra.success else MaterialTheme.colorScheme.outline)
          ) {
            Text(
              if (isStaged) "Unstage" else "Stage",
              fontSize = 11.sp,
              color = if (isStaged) AwakiTheme.extra.success else MaterialTheme.colorScheme.onSurfaceVariant
            )
          }

          // Revert File
          OutlinedButton(
            onClick = { onRevert(currentDiff.filePath) },
            modifier = Modifier.height(28.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
          ) {
            Text("Revert", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
          }

          // Open in Editor
          IconButton(
            onClick = { onOpenEditor(currentDiff.filePath) },
            modifier = Modifier.size(30.dp)
          ) {
            Icon(Icons.Outlined.Edit, contentDescription = "Open editor", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
          }
        }
      }
    }

    // 3. Diff Lines List
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
    ) {
      items(currentDiff.lines) { line ->
        DiffViewerLineRow(line = line)
      }

      item {
        Spacer(modifier = Modifier.height(48.dp))
      }
    }
  }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// DIFF VIEWER LINE ROW
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
private fun DiffViewerLineRow(line: DiffLine) {
  val isHunkHeader = line.text.startsWith("@@")

  val bgColor = when {
    isHunkHeader -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f)
    line.type == DiffLineType.ADDED -> AwakiTheme.extra.successContainer.copy(alpha = 0.35f)
    line.type == DiffLineType.REMOVED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    else -> Color.Transparent
  }

  val textColor = when {
    isHunkHeader -> MaterialTheme.colorScheme.secondary
    line.type == DiffLineType.ADDED -> AwakiTheme.extra.success
    line.type == DiffLineType.REMOVED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(bgColor)
      .padding(vertical = 1.5.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    if (isHunkHeader) {
      // Hunk header full width
      Text(
        text = line.text,
        color = MaterialTheme.colorScheme.secondary,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 2.dp)
      )
    } else {
      // Old line number
      Text(
        text = line.oldLineNo?.toString() ?: "",
        color = AwakiTheme.extra.textMuted,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.width(30.dp)
      )

      // New line number
      Text(
        text = line.newLineNo?.toString() ?: "",
        color = AwakiTheme.extra.textMuted,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.width(30.dp)
      )

      // Prefix sign (+ / -)
      Text(
        text = when (line.type) {
          DiffLineType.ADDED -> "+"
          DiffLineType.REMOVED -> "-"
          DiffLineType.UNCHANGED -> " "
        },
        color = textColor,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.width(14.dp)
      )

      // Line content with horizontal scroll for long lines
      Text(
        text = line.text,
        color = textColor,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
          .weight(1f)
          .horizontalScroll(rememberScrollState())
      )
    }
  }
}
