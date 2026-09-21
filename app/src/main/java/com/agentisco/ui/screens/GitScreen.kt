package com.agentisco.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
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
import com.agentisco.core.model.AppDestination
import com.agentisco.data.model.GitCommit
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.theme.*
import com.agentisco.workspace.git.*
import kotlinx.coroutines.launch

private enum class GitTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
  OVERVIEW("Overview", Icons.Outlined.Dashboard),
  BRANCHES("Branches", Icons.Outlined.ForkRight),
  COMMITS("History", Icons.Outlined.History),
  STASHES_TAGS("Stashes & Tags", Icons.Outlined.Bookmarks)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(
  viewModel: WorkspaceViewModel,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  val repoStatus by viewModel.repoStatus.collectAsState()
  val branches by viewModel.branches.collectAsState()
  val commitHistory by viewModel.commitHistory.collectAsState()
  val stashes by viewModel.stashes.collectAsState()
  val remotes by viewModel.remotes.collectAsState()
  val tags by viewModel.tags.collectAsState()
  val isGitRepo by viewModel.isGitRepository.collectAsState()
  val activeGitOp by viewModel.activeGitOperationText.collectAsState()
  val gitFeedback by viewModel.gitOperationFeedback.collectAsState()
  val gitError by viewModel.gitError.collectAsState()
  val activeProject by viewModel.activeProject.collectAsState()

  val clipboard = LocalClipboardManager.current
  val coroutineScope = rememberCoroutineScope()

  var currentTab by remember { mutableStateOf(GitTab.OVERVIEW) }

  // Dialog States
  var showCreateBranchDialog by remember { mutableStateOf(false) }
  var branchToDelete by remember { mutableStateOf<GitBranch?>(null) }
  var branchToRename by remember { mutableStateOf<GitBranch?>(null) }
  var branchToMerge by remember { mutableStateOf<GitBranch?>(null) }
  var branchToRebase by remember { mutableStateOf<GitBranch?>(null) }

  var showCreateStashDialog by remember { mutableStateOf(false) }
  var stashToDrop by remember { mutableStateOf<GitStash?>(null) }

  var showCreateTagDialog by remember { mutableStateOf(false) }
  var tagToDelete by remember { mutableStateOf<String?>(null) }

  var showAddRemoteDialog by remember { mutableStateOf(false) }

  // Commit Detail Sheet State
  var selectedCommitDetail by remember { mutableStateOf<GitCommitDetail?>(null) }
  var selectedCommitDiff by remember { mutableStateOf<String?>(null) }
  var selectedCommitForAction by remember { mutableStateOf<GitCommit?>(null) }
  var isCommitDetailLoading by remember { mutableStateOf(false) }

  // AI Explain Commit State
  var aiCommitExplanation by remember { mutableStateOf<String?>(null) }
  var isAiCommitLoading by remember { mutableStateOf(false) }

  // Reset Commit Dialog State
  var commitToReset by remember { mutableStateOf<GitCommit?>(null) }

  val isRepo = isGitRepo == true || repoStatus.isRepo

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
  ) {
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // TOP HEADER
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
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
              modifier = Modifier
                .size(32.dp)
                .testTag("btn_git_back")
            ) {
              Icon(Icons.Default.ChevronLeft, contentDescription = "Back", tint = TextPrimary)
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column {
              Text(
                text = "Source Control",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
              )
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Commit, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(11.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                  text = repoStatus.currentBranch.ifBlank { activeProject.branch.ifBlank { "main" } },
                  color = ElectricBlueGlow,
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = FontWeight.SemiBold
                )
                if (repoStatus.aheadCount > 0 || repoStatus.behindCount > 0) {
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                    text = "↑${repoStatus.aheadCount}  ↓${repoStatus.behindCount}",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                  )
                }
              }
            }
          }

          // Header Quick Actions
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            IconButton(
              onClick = { viewModel.refreshDiffsAndGit() },
              modifier = Modifier
                .size(32.dp)
                .testTag("btn_refresh_git")
            ) {
              Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }

            // Shortcut to Changes / Diff Page
            OutlinedButton(
              onClick = { onNavigate(AppDestination.DIFF) },
              modifier = Modifier
                .height(30.dp)
                .testTag("btn_goto_changes"),
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
              border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
              Icon(Icons.Outlined.Difference, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(13.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Changes", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
          }
        }

        // Sub-tabs Navigation Bar
        if (isRepo) {
          ScrollableTabRow(
            selectedTabIndex = currentTab.ordinal,
            containerColor = DarkSurface,
            contentColor = ElectricBlue,
            edgePadding = 12.dp,
            divider = { HorizontalDivider(color = DarkBorderSubtle) },
            modifier = Modifier.fillMaxWidth()
          ) {
            GitTab.values().forEach { tab ->
              val isSelected = currentTab == tab
              Tab(
                selected = isSelected,
                onClick = { currentTab = tab },
                text = {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(tab.icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = if (isSelected) ElectricBlue else TextMuted)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                      text = tab.title,
                      fontSize = 12.sp,
                      fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                      color = if (isSelected) TextPrimary else TextSecondary
                    )
                  }
                },
                modifier = Modifier.height(42.dp)
              )
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
        color = ElectricBlue.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.4f))
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = ElectricBlueGlow)
          Spacer(modifier = Modifier.width(10.dp))
          Text(text = activeGitOp!!, color = ElectricBlueGlow, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
      }
    }

    if (gitFeedback != null) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TerminalGreenBg.copy(alpha = 0.25f),
        border = androidx.compose.foundation.BorderStroke(1.dp, TerminalGreen.copy(alpha = 0.4f))
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 7.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = TerminalGreen, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = gitFeedback!!, color = TerminalGreen, fontSize = 12.sp)
          }
          IconButton(onClick = { viewModel.clearGitOperationFeedback() }, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = TextMuted, modifier = Modifier.size(14.dp))
          }
        }
      }
    }

    if (gitError != null) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DangerRedBg.copy(alpha = 0.25f),
        border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f))
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(Icons.Outlined.Warning, contentDescription = null, tint = DangerRed, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text(text = gitError!!, color = DangerRed, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f))
          IconButton(onClick = { clipboard.setText(AnnotatedString(gitError!!)) }, modifier = Modifier.size(26.dp)) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", tint = TextMuted, modifier = Modifier.size(13.dp))
          }
          IconButton(onClick = { viewModel.dismissGitError() }, modifier = Modifier.size(26.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = TextMuted, modifier = Modifier.size(14.dp))
          }
        }
      }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // NOT A GIT REPO HERO BANNER
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    if (!isRepo) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(20.dp),
        contentAlignment = Alignment.Center
      ) {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = DarkSurface),
          border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle)
        ) {
          Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Surface(
              shape = CircleShape,
              color = IndigoAccent.copy(alpha = 0.2f),
              modifier = Modifier.size(56.dp)
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ForkRight, contentDescription = null, tint = IndigoAccent, modifier = Modifier.size(32.dp))
              }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Initialize Git Repository", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              "Track changes, stage edits, create branches, and sync with GitHub directly in your workspace.",
              color = TextSecondary,
              fontSize = 13.sp,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
              lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
              onClick = { viewModel.initGitRepository() },
              modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .testTag("btn_init_git_repo"),
              colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
            ) {
              Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Initialize Repository", fontWeight = FontWeight.SemiBold)
            }
          }
        }
      }
      return
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // TAB CONTENTS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    when (currentTab) {
      GitTab.OVERVIEW -> {
        GitOverviewTab(
          repoStatus = repoStatus,
          branchesCount = branches.size,
          stashesCount = stashes.size,
          remotesCount = remotes.size,
          tagsCount = tags.size,
          stashes = stashes,
          remotes = remotes,
          onSync = { viewModel.sync() },
          onFetch = { viewModel.fetch() },
          onPull = { viewModel.pull() },
          onPush = { viewModel.push() },
          onAbortMerge = { viewModel.abortMerge() },
          onContinueMerge = { viewModel.continueMerge() },
          onAbortRebase = { viewModel.abortRebase() },
          onContinueRebase = { viewModel.continueRebase() },
          onAbortCherryPick = { viewModel.abortCherryPick() },
          onContinueCherryPick = { viewModel.continueCherryPick() },
          onNavigateToTab = { currentTab = it },
          onNavigateToChanges = { onNavigate(AppDestination.DIFF) },
          onPopStash = { viewModel.popStash(it) },
          onAddRemote = { showAddRemoteDialog = true }
        )
      }

      GitTab.BRANCHES -> {
        GitBranchesTab(
          branches = branches,
          currentBranch = repoStatus.currentBranch,
          onCheckout = { viewModel.checkoutBranch(it) },
          onCreateBranch = { showCreateBranchDialog = true },
          onDeleteBranch = { branchToDelete = it },
          onRenameBranch = { branchToRename = it },
          onMergeBranch = { branchToMerge = it },
          onRebaseBranch = { branchToRebase = it }
        )
      }

      GitTab.COMMITS -> {
        GitCommitsTab(
          commits = commitHistory,
          onLoadMore = { viewModel.loadMoreCommitHistory() },
          onSelectCommit = { commit ->
            selectedCommitForAction = commit
            isCommitDetailLoading = true
            coroutineScope.launch {
              selectedCommitDetail = viewModel.getCommitDetail(commit.hash)
              selectedCommitDiff = viewModel.getCommitDiff(commit.hash)
              isCommitDetailLoading = false
            }
          }
        )
      }

      GitTab.STASHES_TAGS -> {
        GitStashesAndTagsTab(
          stashes = stashes,
          tags = tags,
          onCreateStash = { showCreateStashDialog = true },
          onApplyStash = { viewModel.applyStash(it) },
          onPopStash = { viewModel.popStash(it) },
          onDropStash = { stashToDrop = it },
          onCreateTag = { showCreateTagDialog = true },
          onDeleteTag = { tagToDelete = it },
          onPushTags = { viewModel.push(setUpstream = false, force = false) }
        )
      }
    }
  }

  // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  // DIALOGS & BOTTOM SHEETS
  // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  // 1. Create Branch Dialog
  if (showCreateBranchDialog) {
    var branchName by remember { mutableStateOf("") }
    var checkoutImmediately by remember { mutableStateOf(true) }

    AlertDialog(
      onDismissRequest = { showCreateBranchDialog = false },
      title = { Text("Create New Branch", color = TextPrimary, fontWeight = FontWeight.Bold) },
      text = {
        Column {
          Text("Branch name:", color = TextSecondary, fontSize = 12.sp)
          Spacer(modifier = Modifier.height(6.dp))
          OutlinedTextField(
            value = branchName,
            onValueChange = { branchName = it.replace(Regex("[^a-zA-Z0-9._/-]"), "") },
            placeholder = { Text("feature/my-feature", color = TextMuted, fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = ElectricBlue,
              unfocusedBorderColor = DarkBorder,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
          )
          Spacer(modifier = Modifier.height(10.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
              checked = checkoutImmediately,
              onCheckedChange = { checkoutImmediately = it },
              colors = CheckboxDefaults.colors(checkedColor = ElectricBlue)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Switch to this branch after creation", color = TextSecondary, fontSize = 12.sp)
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (branchName.isNotBlank()) {
              showCreateBranchDialog = false
              viewModel.createBranch(branchName.trim(), checkout = checkoutImmediately)
            }
          },
          enabled = branchName.isNotBlank(),
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
        ) {
          Text("Create Branch")
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { showCreateBranchDialog = false }) {
          Text("Cancel", color = TextSecondary)
        }
      },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp)
    )
  }

  // 2. Delete Branch Confirmation Dialog
  if (branchToDelete != null) {
    var forceDelete by remember { mutableStateOf(false) }

    AlertDialog(
      onDismissRequest = { branchToDelete = null },
      icon = { Icon(Icons.Outlined.Warning, contentDescription = null, tint = DangerRed) },
      title = { Text("Delete Branch?", color = TextPrimary, fontWeight = FontWeight.Bold) },
      text = {
        Column {
          Text(
            "Are you sure you want to delete branch \"${branchToDelete!!.name}\"?",
            color = TextSecondary,
            fontSize = 13.sp
          )
          Spacer(modifier = Modifier.height(12.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
              checked = forceDelete,
              onCheckedChange = { forceDelete = it },
              colors = CheckboxDefaults.colors(checkedColor = DangerRed)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Force delete (-D) even if not fully merged", color = DangerRed, fontSize = 11.sp)
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val branch = branchToDelete!!
            branchToDelete = null
            viewModel.deleteBranch(branch.name, force = forceDelete)
          },
          colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
        ) {
          Text("Delete Branch", color = Color.White)
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { branchToDelete = null }) {
          Text("Cancel", color = TextSecondary)
        }
      },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp)
    )
  }

  // 3. Rename Branch Dialog
  if (branchToRename != null) {
    var newBranchName by remember { mutableStateOf(branchToRename!!.name) }

    AlertDialog(
      onDismissRequest = { branchToRename = null },
      title = { Text("Rename Branch", color = TextPrimary, fontWeight = FontWeight.Bold) },
      text = {
        Column {
          Text("Rename \"${branchToRename!!.name}\" to:", color = TextSecondary, fontSize = 12.sp)
          Spacer(modifier = Modifier.height(6.dp))
          OutlinedTextField(
            value = newBranchName,
            onValueChange = { newBranchName = it.replace(Regex("[^a-zA-Z0-9._/-]"), "") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = ElectricBlue,
              unfocusedBorderColor = DarkBorder,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val old = branchToRename!!.name
            branchToRename = null
            viewModel.renameBranch(old, newBranchName.trim())
          },
          enabled = newBranchName.isNotBlank() && newBranchName != branchToRename!!.name,
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
        ) {
          Text("Rename")
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { branchToRename = null }) {
          Text("Cancel", color = TextSecondary)
        }
      },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp)
    )
  }

  // 4. Merge Branch Dialog
  if (branchToMerge != null) {
    AlertDialog(
      onDismissRequest = { branchToMerge = null },
      title = { Text("Merge Branch?", color = TextPrimary, fontWeight = FontWeight.Bold) },
      text = {
        Text(
          "Merge branch \"${branchToMerge!!.name}\" into current branch \"${repoStatus.currentBranch}\"?",
          color = TextSecondary,
          fontSize = 13.sp
        )
      },
      confirmButton = {
        Button(
          onClick = {
            val branch = branchToMerge!!
            branchToMerge = null
            viewModel.mergeBranch(branch.name)
          },
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
        ) {
          Text("Merge")
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { branchToMerge = null }) {
          Text("Cancel", color = TextSecondary)
        }
      },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp)
    )
  }

  // 5. Rebase Branch Dialog
  if (branchToRebase != null) {
    AlertDialog(
      onDismissRequest = { branchToRebase = null },
      title = { Text("Rebase Branch?", color = TextPrimary, fontWeight = FontWeight.Bold) },
      text = {
        Text(
          "Rebase current branch \"${repoStatus.currentBranch}\" onto \"${branchToRebase!!.name}\"?\n\nIf conflicts occur, you will be able to resolve or abort the rebase.",
          color = TextSecondary,
          fontSize = 13.sp
        )
      },
      confirmButton = {
        Button(
          onClick = {
            val branch = branchToRebase!!
            branchToRebase = null
            viewModel.rebaseBranch(branch.name)
          },
          colors = ButtonDefaults.buttonColors(containerColor = WarningAmber)
        ) {
          Text("Rebase")
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { branchToRebase = null }) {
          Text("Cancel", color = TextSecondary)
        }
      },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp)
    )
  }

  // 6. Create Stash Dialog
  if (showCreateStashDialog) {
    var stashMsg by remember { mutableStateOf("") }
    var includeUntracked by remember { mutableStateOf(false) }

    AlertDialog(
      onDismissRequest = { showCreateStashDialog = false },
      title = { Text("Save Changes to Stash", color = TextPrimary, fontWeight = FontWeight.Bold) },
      text = {
        Column {
          Text("Optional stash message:", color = TextSecondary, fontSize = 12.sp)
          Spacer(modifier = Modifier.height(6.dp))
          OutlinedTextField(
            value = stashMsg,
            onValueChange = { stashMsg = it },
            placeholder = { Text("WIP on feature...", color = TextMuted, fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = ElectricBlue,
              unfocusedBorderColor = DarkBorder,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary
            )
          )
          Spacer(modifier = Modifier.height(10.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
              checked = includeUntracked,
              onCheckedChange = { includeUntracked = it },
              colors = CheckboxDefaults.colors(checkedColor = ElectricBlue)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Include untracked files (-u)", color = TextSecondary, fontSize = 12.sp)
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            showCreateStashDialog = false
            viewModel.saveStash(stashMsg.trim(), includeUntracked)
          },
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
        ) {
          Text("Save Stash")
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { showCreateStashDialog = false }) {
          Text("Cancel", color = TextSecondary)
        }
      },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp)
    )
  }

  // 7. Drop Stash Confirmation Dialog
  if (stashToDrop != null) {
    AlertDialog(
      onDismissRequest = { stashToDrop = null },
      icon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = DangerRed) },
      title = { Text("Drop Stash?", color = TextPrimary, fontWeight = FontWeight.Bold) },
      text = {
        Text(
          "Are you sure you want to permanently delete stash@{" + stashToDrop!!.index + "} (\"" + stashToDrop!!.message + "\")?",
          color = TextSecondary,
          fontSize = 13.sp
        )
      },
      confirmButton = {
        Button(
          onClick = {
            val idx = stashToDrop!!.index
            stashToDrop = null
            viewModel.dropStash(idx)
          },
          colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
        ) {
          Text("Drop Stash", color = Color.White)
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { stashToDrop = null }) {
          Text("Cancel", color = TextSecondary)
        }
      },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp)
    )
  }

  // 8. Create Tag Dialog
  if (showCreateTagDialog) {
    var tagName by remember { mutableStateOf("") }
    var tagMsg by remember { mutableStateOf("") }

    AlertDialog(
      onDismissRequest = { showCreateTagDialog = false },
      title = { Text("Create Git Tag", color = TextPrimary, fontWeight = FontWeight.Bold) },
      text = {
        Column {
          Text("Tag name (e.g. v1.0.0):", color = TextSecondary, fontSize = 12.sp)
          Spacer(modifier = Modifier.height(6.dp))
          OutlinedTextField(
            value = tagName,
            onValueChange = { tagName = it.trim() },
            placeholder = { Text("v1.0.0", color = TextMuted, fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = ElectricBlue,
              unfocusedBorderColor = DarkBorder,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
          )
          Spacer(modifier = Modifier.height(10.dp))
          Text("Optional annotation message:", color = TextSecondary, fontSize = 12.sp)
          Spacer(modifier = Modifier.height(6.dp))
          OutlinedTextField(
            value = tagMsg,
            onValueChange = { tagMsg = it },
            placeholder = { Text("Release notes...", color = TextMuted, fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = ElectricBlue,
              unfocusedBorderColor = DarkBorder,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary
            )
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (tagName.isNotBlank()) {
              showCreateTagDialog = false
              viewModel.createTag(tagName.trim(), tagMsg.trim())
            }
          },
          enabled = tagName.isNotBlank(),
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
        ) {
          Text("Create Tag")
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { showCreateTagDialog = false }) {
          Text("Cancel", color = TextSecondary)
        }
      },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp)
    )
  }

  // 9. Add Remote Dialog
  if (showAddRemoteDialog) {
    var remoteName by remember { mutableStateOf("origin") }
    var remoteUrl by remember { mutableStateOf("") }

    AlertDialog(
      onDismissRequest = { showAddRemoteDialog = false },
      title = { Text("Add Remote Repository", color = TextPrimary, fontWeight = FontWeight.Bold) },
      text = {
        Column {
          Text("Remote name:", color = TextSecondary, fontSize = 12.sp)
          Spacer(modifier = Modifier.height(6.dp))
          OutlinedTextField(
            value = remoteName,
            onValueChange = { remoteName = it.trim() },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = ElectricBlue,
              unfocusedBorderColor = DarkBorder,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary
            )
          )
          Spacer(modifier = Modifier.height(10.dp))
          Text("Remote URL (HTTPS or SSH):", color = TextSecondary, fontSize = 12.sp)
          Spacer(modifier = Modifier.height(6.dp))
          OutlinedTextField(
            value = remoteUrl,
            onValueChange = { remoteUrl = it.trim() },
            placeholder = { Text("https://github.com/user/repo.git", color = TextMuted, fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = ElectricBlue,
              unfocusedBorderColor = DarkBorder,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (remoteName.isNotBlank() && remoteUrl.isNotBlank()) {
              showAddRemoteDialog = false
              viewModel.addRemote(remoteName.trim(), remoteUrl.trim())
            }
          },
          enabled = remoteName.isNotBlank() && remoteUrl.isNotBlank(),
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
        ) {
          Text("Add Remote")
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { showAddRemoteDialog = false }) {
          Text("Cancel", color = TextSecondary)
        }
      },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp)
    )
  }

  // 10. Commit Detail Modal Bottom Sheet
  if (selectedCommitForAction != null) {
    val commit = selectedCommitForAction!!
    ModalBottomSheet(
      onDismissRequest = {
        selectedCommitForAction = null
        selectedCommitDetail = null
        selectedCommitDiff = null
        aiCommitExplanation = null
      },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 12.dp)
      ) {
        // Commit Header
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.Top
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = commit.message,
              color = TextPrimary,
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
              Surface(
                shape = RoundedCornerShape(4.dp),
                color = DarkSurfaceHighlight
              ) {
                Text(
                  text = commit.hash.take(7),
                  color = ElectricBlueGlow,
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
              }
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "${commit.author} · ${commit.date}",
                color = TextSecondary,
                fontSize = 11.sp
              )
            }
          }

          IconButton(
            onClick = { clipboard.setText(AnnotatedString(commit.hash)) },
            modifier = Modifier.size(32.dp)
          ) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy hash", tint = TextSecondary, modifier = Modifier.size(16.dp))
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Actions Row on Commit
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          // Cherry Pick
          OutlinedButton(
            onClick = {
              val h = commit.hash
              selectedCommitForAction = null
              viewModel.cherryPickCommit(h)
            },
            modifier = Modifier.weight(1f).height(34.dp),
            contentPadding = PaddingValues(0.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
          ) {
            Text("Cherry-pick", fontSize = 11.sp, color = TextPrimary)
          }

          // Revert Commit
          OutlinedButton(
            onClick = {
              val h = commit.hash
              selectedCommitForAction = null
              viewModel.revertCommit(h)
            },
            modifier = Modifier.weight(1f).height(34.dp),
            contentPadding = PaddingValues(0.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
          ) {
            Text("Revert", fontSize = 11.sp, color = DangerRed)
          }

          // Reset to here
          OutlinedButton(
            onClick = {
              commitToReset = commit
              selectedCommitForAction = null
            },
            modifier = Modifier.weight(1f).height(34.dp),
            contentPadding = PaddingValues(0.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f))
          ) {
            Text("Reset…", fontSize = 11.sp, color = DangerRed)
          }

          // Explain with AI
          Button(
            onClick = {
              isAiCommitLoading = true
              coroutineScope.launch {
                aiCommitExplanation = viewModel.explainCommitWithAgent(commit)
                isAiCommitLoading = false
              }
            },
            modifier = Modifier.height(34.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = IndigoAccent)
          ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(13.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("AI Explain", fontSize = 11.sp)
          }
        }

        // AI Explanation box if generated
        if (isAiCommitLoading) {
          Spacer(modifier = Modifier.height(12.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = IndigoAccent)
            Spacer(modifier = Modifier.width(8.dp))
            Text("AI is explaining commit changes…", color = TextMuted, fontSize = 12.sp)
          }
        } else if (aiCommitExplanation != null) {
          Spacer(modifier = Modifier.height(12.dp))
          Surface(
            modifier = Modifier.fillMaxWidth(),
            color = DarkSurfaceElevated,
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, IndigoAccent.copy(alpha = 0.4f))
          ) {
            Text(
              text = aiCommitExplanation!!,
              color = TextPrimary,
              fontSize = 12.sp,
              lineHeight = 17.sp,
              modifier = Modifier.padding(12.dp)
            )
          }
        }

        // Commit File Changes & Diff Preview
        Spacer(modifier = Modifier.height(14.dp))
        Text("Files Changed in this Commit:", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(6.dp))

        if (isCommitDetailLoading) {
          Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = ElectricBlue)
          }
        } else if (selectedCommitDetail != null && selectedCommitDetail!!.filesChanged.isNotEmpty()) {
          Surface(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 240.dp),
            shape = RoundedCornerShape(8.dp),
            color = DarkSurfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle)
          ) {
            LazyColumn(modifier = Modifier.padding(8.dp)) {
              items(selectedCommitDetail!!.filesChanged) { file ->
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp, horizontal = 6.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    text = file.path,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  if (file.additions > 0 || file.deletions > 0) {
                    Row {
                      if (file.additions > 0) {
                        Text("+$file.additions", color = TerminalGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                      }
                      if (file.deletions > 0) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("-$file.deletions", color = DangerRed, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                      }
                    }
                  }
                }
              }
            }
          }
        } else if (!selectedCommitDiff.isNullOrBlank()) {
          // Fallback raw diff preview
          Surface(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 240.dp),
            shape = RoundedCornerShape(8.dp),
            color = DarkSurfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle)
          ) {
            LazyColumn(modifier = Modifier.padding(10.dp)) {
              item {
                Text(
                  text = selectedCommitDiff!!,
                  color = TextCode,
                  fontSize = 10.sp,
                  fontFamily = FontFamily.Monospace,
                  lineHeight = 14.sp
                )
              }
            }
          }
        } else {
          Text("No file details available.", color = TextMuted, fontSize = 11.sp)
        }

        Spacer(modifier = Modifier.height(20.dp))
      }
    }
  }

  // 11. Reset to Commit Safety Dialog (Soft / Mixed / Hard)
  if (commitToReset != null) {
    val commit = commitToReset!!
    var resetMode by remember { mutableStateOf(ResetMode.SOFT) }

    AlertDialog(
      onDismissRequest = { commitToReset = null },
      icon = { Icon(Icons.Outlined.Warning, contentDescription = null, tint = DangerRed) },
      title = { Text("Reset Current Branch?", color = TextPrimary, fontWeight = FontWeight.Bold) },
      text = {
        Column {
          Text(
            "Reset branch \"${repoStatus.currentBranch}\" HEAD to commit ${commit.hash.take(7)} (\"${commit.message}\")?",
            color = TextSecondary,
            fontSize = 13.sp
          )
          Spacer(modifier = Modifier.height(14.dp))
          Text("Select reset mode:", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
          Spacer(modifier = Modifier.height(8.dp))

          // Mode 1: Soft
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(6.dp))
              .background(if (resetMode == ResetMode.SOFT) DarkSurfaceHighlight else Color.Transparent)
              .clickable { resetMode = ResetMode.SOFT }
              .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            RadioButton(selected = resetMode == ResetMode.SOFT, onClick = { resetMode = ResetMode.SOFT })
            Spacer(modifier = Modifier.width(6.dp))
            Column {
              Text("Soft (--soft)", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
              Text("Keeps all changes staged in your index", color = TextMuted, fontSize = 10.sp)
            }
          }

          // Mode 2: Mixed
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(6.dp))
              .background(if (resetMode == ResetMode.MIXED) DarkSurfaceHighlight else Color.Transparent)
              .clickable { resetMode = ResetMode.MIXED }
              .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            RadioButton(selected = resetMode == ResetMode.MIXED, onClick = { resetMode = ResetMode.MIXED })
            Spacer(modifier = Modifier.width(6.dp))
            Column {
              Text("Mixed (--mixed)", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
              Text("Keeps changes in working tree unstaged", color = TextMuted, fontSize = 10.sp)
            }
          }

          // Mode 3: Hard
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(6.dp))
              .background(if (resetMode == ResetMode.HARD) DarkSurfaceHighlight else Color.Transparent)
              .clickable { resetMode = ResetMode.HARD }
              .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            RadioButton(
              selected = resetMode == ResetMode.HARD,
              onClick = { resetMode = ResetMode.HARD },
              colors = RadioButtonDefaults.colors(selectedColor = DangerRed)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column {
              Text("Hard (--hard) [DESTRUCTIVE]", color = DangerRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
              Text("Permanently discards all working tree modifications", color = DangerRed.copy(alpha = 0.8f), fontSize = 10.sp)
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val h = commit.hash
            val m = resetMode
            commitToReset = null
            viewModel.resetToCommit(h, m)
          },
          colors = ButtonDefaults.buttonColors(containerColor = if (resetMode == ResetMode.HARD) DangerRed else WarningAmber)
        ) {
          Text("Reset Branch", color = Color.White)
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { commitToReset = null }) {
          Text("Cancel", color = TextSecondary)
        }
      },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp)
    )
  }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// TAB 1: OVERVIEW & SYNC
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
private fun GitOverviewTab(
  repoStatus: GitRepoStatus,
  branchesCount: Int,
  stashesCount: Int,
  remotesCount: Int,
  tagsCount: Int,
  stashes: List<GitStash>,
  remotes: List<GitRemote>,
  onSync: () -> Unit,
  onFetch: () -> Unit,
  onPull: () -> Unit,
  onPush: () -> Unit,
  onAbortMerge: () -> Unit,
  onContinueMerge: () -> Unit,
  onAbortRebase: () -> Unit,
  onContinueRebase: () -> Unit,
  onAbortCherryPick: () -> Unit,
  onContinueCherryPick: () -> Unit,
  onNavigateToTab: (GitTab) -> Unit,
  onNavigateToChanges: () -> Unit,
  onPopStash: (Int) -> Unit,
  onAddRemote: () -> Unit
) {
  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 14.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
    contentPadding = PaddingValues(top = 14.dp, bottom = 32.dp)
  ) {
    // 1. Active In-Progress Operation Banner (Merge/Rebase/Cherry-pick)
    if (repoStatus.activeOperation != GitActiveOperation.NONE) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(10.dp),
          colors = CardDefaults.cardColors(containerColor = DangerRedBg.copy(alpha = 0.3f)),
          border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed.copy(alpha = 0.6f))
        ) {
          Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Outlined.Warning, contentDescription = null, tint = DangerRed, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = repoStatus.activeOperation.label,
                color = DangerRed,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
              )
            }
            if (repoStatus.conflictedFiles.isNotEmpty()) {
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "${repoStatus.conflictedFiles.size} conflicted files need resolution in Changes tab.",
                color = TextPrimary,
                fontSize = 11.sp
              )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(
                onClick = {
                  when (repoStatus.activeOperation) {
                    GitActiveOperation.MERGE -> onContinueMerge()
                    GitActiveOperation.REBASE -> onContinueRebase()
                    GitActiveOperation.CHERRY_PICK -> onContinueCherryPick()
                    else -> {}
                  }
                },
                colors = ButtonDefaults.buttonColors(containerColor = TerminalGreen),
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
              ) {
                Text("Continue", fontSize = 11.sp)
              }

              OutlinedButton(
                onClick = {
                  when (repoStatus.activeOperation) {
                    GitActiveOperation.MERGE -> onAbortMerge()
                    GitActiveOperation.REBASE -> onAbortRebase()
                    GitActiveOperation.CHERRY_PICK -> onAbortCherryPick()
                    else -> {}
                  }
                },
                border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed),
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
              ) {
                Text("Abort", fontSize = 11.sp, color = DangerRed)
              }
            }
          }
        }
      }
    }

    // 2. Repository Status Overview Hero Card
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle)
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text("CURRENT BRANCH", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
              Spacer(modifier = Modifier.height(4.dp))
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ForkRight, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = repoStatus.currentBranch,
                  color = TextPrimary,
                  fontSize = 16.sp,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = FontWeight.Bold
                )
              }
            }

            // Clean vs Dirty working tree indicator
            Surface(
              shape = RoundedCornerShape(20.dp),
              color = if (repoStatus.isClean) TerminalGreenBg.copy(alpha = 0.3f) else WarningAmberBg.copy(alpha = 0.3f)
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Box(
                  modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (repoStatus.isClean) TerminalGreen else WarningAmber)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = if (repoStatus.isClean) "Clean Tree" else "${repoStatus.totalChangedFiles} uncommitted",
                  color = if (repoStatus.isClean) TerminalGreen else WarningAmber,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.SemiBold
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(14.dp))
          HorizontalDivider(color = DarkBorderSubtle)
          Spacer(modifier = Modifier.height(12.dp))

          // Upstream tracking & Ahead/Behind
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text("TRACKING UPSTREAM", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = repoStatus.upstreamBranch ?: "None configured",
                color = if (repoStatus.upstreamBranch != null) TextCode else TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
              )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              Surface(shape = RoundedCornerShape(6.dp), color = DarkSurfaceElevated) {
                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Outlined.ArrowUpward, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(12.dp))
                  Spacer(modifier = Modifier.width(3.dp))
                  Text("${repoStatus.aheadCount} ahead", color = ElectricBlueGlow, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
              }
              Surface(shape = RoundedCornerShape(6.dp), color = DarkSurfaceElevated) {
                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Outlined.ArrowDownward, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(12.dp))
                  Spacer(modifier = Modifier.width(3.dp))
                  Text("${repoStatus.behindCount} behind", color = WarningAmber, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(16.dp))

          // Remote Sync Action Buttons
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Button(
              onClick = onSync,
              modifier = Modifier
                .weight(1.2f)
                .height(36.dp)
                .testTag("btn_git_sync"),
              colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
              Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Sync", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
              onClick = onFetch,
              modifier = Modifier
                .weight(1f)
                .height(36.dp),
              contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
              border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
              Text("Fetch", fontSize = 11.sp, color = TextPrimary)
            }

            OutlinedButton(
              onClick = onPull,
              modifier = Modifier
                .weight(1f)
                .height(36.dp),
              contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
              border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
              Text("Pull", fontSize = 11.sp, color = TextPrimary)
            }

            OutlinedButton(
              onClick = onPush,
              modifier = Modifier
                .weight(1f)
                .height(36.dp),
              contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
              border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
              Text("Push", fontSize = 11.sp, color = TextPrimary)
            }
          }
        }
      }
    }

    // 3. Quick Navigation Metric Cards Grid
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        // Branches Card
        OverviewMetricCard(
          modifier = Modifier.weight(1f),
          title = "Branches",
          count = branchesCount.toString(),
          icon = Icons.Outlined.ForkRight,
          iconTint = ElectricBlueGlow,
          onClick = { onNavigateToTab(GitTab.BRANCHES) }
        )

        // Commits Card
        OverviewMetricCard(
          modifier = Modifier.weight(1f),
          title = "Commits",
          count = repoStatus.headCommitHash?.take(7) ?: "Log",
          icon = Icons.Outlined.History,
          iconTint = IndigoAccent,
          onClick = { onNavigateToTab(GitTab.COMMITS) }
        )

        // Stashes Card
        OverviewMetricCard(
          modifier = Modifier.weight(1f),
          title = "Stashes",
          count = stashesCount.toString(),
          icon = Icons.Outlined.Bookmarks,
          iconTint = WarningAmber,
          onClick = { onNavigateToTab(GitTab.STASHES_TAGS) }
        )

        // Changes Card
        OverviewMetricCard(
          modifier = Modifier.weight(1f),
          title = "Changes",
          count = repoStatus.totalChangedFiles.toString(),
          icon = Icons.Outlined.Difference,
          iconTint = TerminalGreen,
          onClick = onNavigateToChanges
        )
      }
    }

    // 4. Remotes Section
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle)
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Outlined.Cloud, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("CONFIGURED REMOTES", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            }
            TextButton(
              onClick = onAddRemote,
              contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
              modifier = Modifier.height(26.dp)
            ) {
              Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(13.dp), tint = ElectricBlueGlow)
              Spacer(modifier = Modifier.width(3.dp))
              Text("Add Remote", color = ElectricBlueGlow, fontSize = 11.sp)
            }
          }

          if (remotes.isEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text("No git remotes configured yet.", color = TextMuted, fontSize = 11.sp)
          } else {
            remotes.forEach { r ->
              Spacer(modifier = Modifier.height(8.dp))
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = DarkSurfaceElevated,
                modifier = Modifier.fillMaxWidth()
              ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                  Text(r.name, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                  Spacer(modifier = Modifier.height(2.dp))
                  Text(r.fetchUrl, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
              }
            }
          }
        }
      }
    }

    // 5. Recent Stash Preview (if any)
    if (stashes.isNotEmpty()) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(10.dp),
          colors = CardDefaults.cardColors(containerColor = DarkSurface),
          border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle)
        ) {
          Column(modifier = Modifier.padding(14.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text("LATEST STASH", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
              TextButton(
                onClick = { onNavigateToTab(GitTab.STASHES_TAGS) },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(24.dp)
              ) {
                Text("View All (${stashes.size})", color = ElectricBlueGlow, fontSize = 11.sp)
              }
            }
            Spacer(modifier = Modifier.height(8.dp))
            val topStash = stashes.first()
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(topStash.message.ifBlank { "WIP on ${topStash.branch}" }, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text("stash@{${topStash.index}} · ${topStash.date}", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
              }
              Button(
                onClick = { onPopStash(topStash.index) },
                modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceHighlight)
              ) {
                Text("Pop", fontSize = 11.sp, color = TextPrimary)
              }
            }
          }
        }
      }
    }
  }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// TAB 2: BRANCHES
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
private fun GitBranchesTab(
  branches: List<GitBranch>,
  currentBranch: String,
  onCheckout: (String) -> Unit,
  onCreateBranch: () -> Unit,
  onDeleteBranch: (GitBranch) -> Unit,
  onRenameBranch: (GitBranch) -> Unit,
  onMergeBranch: (GitBranch) -> Unit,
  onRebaseBranch: (GitBranch) -> Unit
) {
  var searchQuery by remember { mutableStateOf("") }
  var showRemoteBranches by remember { mutableStateOf(false) }

  val filteredBranches = remember(branches, searchQuery, showRemoteBranches) {
    branches.filter { b ->
      (showRemoteBranches || !b.isRemote) &&
        (searchQuery.isBlank() || b.name.contains(searchQuery.trim(), ignoreCase = true))
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 14.dp, vertical = 10.dp)
  ) {
    // Toolbar: Search + Create Button + Local/Remote Filter
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = { Text("Search branches...", color = TextMuted, fontSize = 12.sp) },
        singleLine = true,
        modifier = Modifier
          .weight(1f)
          .height(44.dp),
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = ElectricBlue,
          unfocusedBorderColor = DarkBorder,
          focusedTextColor = TextPrimary,
          unfocusedTextColor = TextPrimary,
          focusedContainerColor = DarkSurface,
          unfocusedContainerColor = DarkSurface
        ),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
      )

      Button(
        onClick = onCreateBranch,
        modifier = Modifier.height(44.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
      ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("New", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Local vs Remote Filter Toggle
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = "${filteredBranches.size} branches",
        color = TextMuted,
        fontSize = 11.sp
      )

      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
          checked = showRemoteBranches,
          onCheckedChange = { showRemoteBranches = it },
          colors = CheckboxDefaults.colors(checkedColor = ElectricBlue),
          modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text("Show remote branches", color = TextSecondary, fontSize = 11.sp)
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Branches List
    if (filteredBranches.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No branches match your query.", color = TextMuted, fontSize = 12.sp)
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
      ) {
        items(filteredBranches, key = { "${it.name}_${it.isRemote}" }) { branch ->
          BranchRowItem(
            branch = branch,
            isCurrent = branch.isCurrent || branch.name == currentBranch,
            onCheckout = { onCheckout(branch.name) },
            onDelete = { onDeleteBranch(branch) },
            onRename = { onRenameBranch(branch) },
            onMerge = { onMergeBranch(branch) },
            onRebase = { onRebaseBranch(branch) }
          )
        }
      }
    }
  }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// BRANCH ROW ITEM
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
private fun BranchRowItem(
  branch: GitBranch,
  isCurrent: Boolean,
  onCheckout: () -> Unit,
  onDelete: () -> Unit,
  onRename: () -> Unit,
  onMerge: () -> Unit,
  onRebase: () -> Unit
) {
  var showMenu by remember { mutableStateOf(false) }

  Surface(
    shape = RoundedCornerShape(8.dp),
    color = if (isCurrent) DarkSurfaceHighlight else DarkSurface,
    border = androidx.compose.foundation.BorderStroke(1.dp, if (isCurrent) ElectricBlue else DarkBorderSubtle),
    modifier = Modifier.fillMaxWidth()
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { if (!isCurrent) onCheckout() }
        .padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
        Icon(
          imageVector = if (branch.isRemote) Icons.Outlined.Cloud else Icons.Outlined.ForkRight,
          contentDescription = null,
          tint = if (isCurrent) ElectricBlueGlow else if (branch.isRemote) CyanAccent else TextMuted,
          modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = branch.name,
              color = if (isCurrent) ElectricBlueGlow else TextPrimary,
              fontSize = 13.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            if (isCurrent) {
              Spacer(modifier = Modifier.width(6.dp))
              Surface(
                shape = RoundedCornerShape(4.dp),
                color = ElectricBlue.copy(alpha = 0.2f)
              ) {
                Text(
                  text = "CURRENT",
                  color = ElectricBlueGlow,
                  fontSize = 9.sp,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
              }
            }
          }

          if (branch.upstream != null || branch.ahead > 0 || branch.behind > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              if (branch.upstream != null) {
                Text(branch.upstream, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
              }
              if (branch.ahead > 0 || branch.behind > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Text("↑${branch.ahead}  ↓${branch.behind}", color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
              }
            }
          }
        }
      }

      // Branch Actions Menu
      Box {
        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
          Icon(Icons.Default.MoreVert, contentDescription = "Branch options", tint = TextSecondary, modifier = Modifier.size(16.dp))
        }

        DropdownMenu(
          expanded = showMenu,
          onDismissRequest = { showMenu = false },
          modifier = Modifier.background(DarkSurfaceElevated)
        ) {
          if (!isCurrent) {
            DropdownMenuItem(
              text = { Text("Checkout Branch", color = TextPrimary, fontSize = 12.sp) },
              onClick = { showMenu = false; onCheckout() },
              leadingIcon = { Icon(Icons.Outlined.Check, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(14.dp)) }
            )
            DropdownMenuItem(
              text = { Text("Merge into current", color = TextPrimary, fontSize = 12.sp) },
              onClick = { showMenu = false; onMerge() },
              leadingIcon = { Icon(Icons.AutoMirrored.Outlined.MergeType, contentDescription = null, tint = TerminalGreen, modifier = Modifier.size(14.dp)) }
            )
            DropdownMenuItem(
              text = { Text("Rebase current onto this", color = TextPrimary, fontSize = 12.sp) },
              onClick = { showMenu = false; onRebase() },
              leadingIcon = { Icon(Icons.AutoMirrored.Outlined.CallSplit, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(14.dp)) }
            )
          }

          DropdownMenuItem(
            text = { Text("Rename Branch", color = TextPrimary, fontSize = 12.sp) },
            onClick = { showMenu = false; onRename() },
            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp)) }
          )

          if (!isCurrent) {
            DropdownMenuItem(
              text = { Text("Delete Branch", color = DangerRed, fontSize = 12.sp) },
              onClick = { showMenu = false; onDelete() },
              leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = DangerRed, modifier = Modifier.size(14.dp)) }
            )
          }
        }
      }
    }
  }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// TAB 3: COMMITS / HISTORY
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
private fun GitCommitsTab(
  commits: List<GitCommit>,
  onLoadMore: () -> Unit,
  onSelectCommit: (GitCommit) -> Unit
) {
  var searchQuery by remember { mutableStateOf("") }

  val filteredCommits = remember(commits, searchQuery) {
    if (searchQuery.isBlank()) commits
    else commits.filter {
      it.message.contains(searchQuery.trim(), ignoreCase = true) ||
        it.author.contains(searchQuery.trim(), ignoreCase = true) ||
        it.hash.contains(searchQuery.trim(), ignoreCase = true)
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 14.dp, vertical = 10.dp)
  ) {
    // Search Bar
    OutlinedTextField(
      value = searchQuery,
      onValueChange = { searchQuery = it },
      placeholder = { Text("Filter commits by message, author, hash...", color = TextMuted, fontSize = 12.sp) },
      singleLine = true,
      modifier = Modifier
        .fillMaxWidth()
        .height(44.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = ElectricBlue,
        unfocusedBorderColor = DarkBorder,
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedContainerColor = DarkSurface,
        unfocusedContainerColor = DarkSurface
      ),
      textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    )

    Spacer(modifier = Modifier.height(10.dp))

    if (filteredCommits.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No commits found.", color = TextMuted, fontSize = 12.sp)
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
      ) {
        itemsIndexed(filteredCommits, key = { _, c -> c.hash }) { index, commit ->
          CommitRowItem(
            commit = commit,
            isHead = index == 0,
            onClick = { onSelectCommit(commit) }
          )
        }

        item {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
          ) {
            OutlinedButton(
              onClick = onLoadMore,
              border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
              Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(14.dp), tint = TextSecondary)
              Spacer(modifier = Modifier.width(6.dp))
              Text("Load More Commits", color = TextSecondary, fontSize = 12.sp)
            }
          }
        }
      }
    }
  }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// COMMIT ROW ITEM
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
private fun CommitRowItem(
  commit: GitCommit,
  isHead: Boolean,
  onClick: () -> Unit
) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = DarkSurface,
    border = androidx.compose.foundation.BorderStroke(1.dp, if (isHead) ElectricBlue.copy(alpha = 0.5f) else DarkBorderSubtle),
    modifier = Modifier.fillMaxWidth()
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { onClick() }
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Commit timeline dot
      Box(
        modifier = Modifier
          .size(8.dp)
          .clip(CircleShape)
          .background(if (isHead) ElectricBlueGlow else IndigoAccent)
      )

      Spacer(modifier = Modifier.width(10.dp))

      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = commit.message,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
          )
          if (isHead) {
            Spacer(modifier = Modifier.width(6.dp))
            Surface(shape = RoundedCornerShape(3.dp), color = ElectricBlue.copy(alpha = 0.25f)) {
              Text("HEAD", color = ElectricBlueGlow, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
            }
          }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = commit.hash.take(7),
            color = ElectricBlueGlow,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "${commit.author} · ${commit.date}",
            color = TextMuted,
            fontSize = 10.sp
          )
        }
      }

      Icon(Icons.Default.ChevronRight, contentDescription = "View commit", tint = TextMuted, modifier = Modifier.size(16.dp))
    }
  }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// TAB 4: STASHES & TAGS
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
private fun GitStashesAndTagsTab(
  stashes: List<GitStash>,
  tags: List<String>,
  onCreateStash: () -> Unit,
  onApplyStash: (Int) -> Unit,
  onPopStash: (Int) -> Unit,
  onDropStash: (GitStash) -> Unit,
  onCreateTag: () -> Unit,
  onDeleteTag: (String) -> Unit,
  onPushTags: () -> Unit
) {
  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 14.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    contentPadding = PaddingValues(bottom = 32.dp)
  ) {
    // ── STASHES SECTION ──
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("SAVED STASHES", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
          Spacer(modifier = Modifier.width(6.dp))
          Surface(shape = RoundedCornerShape(10.dp), color = WarningAmber.copy(alpha = 0.2f)) {
            Text("${stashes.size}", color = WarningAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
          }
        }

        Button(
          onClick = onCreateStash,
          modifier = Modifier.height(28.dp),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
        ) {
          Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(12.dp))
          Spacer(modifier = Modifier.width(3.dp))
          Text("Save Stash", fontSize = 11.sp)
        }
      }
    }

    if (stashes.isEmpty()) {
      item {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = DarkSurface,
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            "No stashes saved. Stashing allows you to save uncommitted changes without making a commit.",
            color = TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(14.dp)
          )
        }
      }
    } else {
      items(stashes, key = { it.ref }) { stash ->
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = DarkSurface,
          border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(12.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.Top
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = stash.message.ifBlank { "WIP on ${stash.branch}" },
                  color = TextPrimary,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = "stash@{${stash.index}} on ${stash.branch} · ${stash.date}",
                  color = TextMuted,
                  fontSize = 10.sp,
                  fontFamily = FontFamily.Monospace
                )
              }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Stash action buttons
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Button(
                onClick = { onPopStash(stash.index) },
                modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
              ) {
                Text("Pop", fontSize = 11.sp)
              }

              OutlinedButton(
                onClick = { onApplyStash(stash.index) },
                modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
              ) {
                Text("Apply", fontSize = 11.sp, color = TextPrimary)
              }

              OutlinedButton(
                onClick = { onDropStash(stash) },
                modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f))
              ) {
                Text("Drop", fontSize = 11.sp, color = DangerRed)
              }
            }
          }
        }
      }
    }

    // ── TAGS SECTION ──
    item {
      Spacer(modifier = Modifier.height(8.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("GIT TAGS", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
          Spacer(modifier = Modifier.width(6.dp))
          Surface(shape = RoundedCornerShape(10.dp), color = CyanAccent.copy(alpha = 0.2f)) {
            Text("${tags.size}", color = CyanAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
          }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          OutlinedButton(
            onClick = onPushTags,
            modifier = Modifier.height(28.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
          ) {
            Text("Push Tags", fontSize = 11.sp, color = TextSecondary)
          }

          Button(
            onClick = onCreateTag,
            modifier = Modifier.height(28.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
          ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(12.dp))
            Spacer(modifier = Modifier.width(3.dp))
            Text("Create Tag", fontSize = 11.sp, color = DarkBackground, fontWeight = FontWeight.SemiBold)
          }
        }
      }
    }

    if (tags.isEmpty()) {
      item {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = DarkSurface,
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            "No tags found. Tags are useful for marking release points (e.g. v1.0.0).",
            color = TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(14.dp)
          )
        }
      }
    } else {
      items(tags) { tagName ->
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = DarkSurface,
          border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Outlined.Bookmarks, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text(tagName, color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }

            IconButton(
              onClick = { onDeleteTag(tagName) },
              modifier = Modifier.size(28.dp)
            ) {
              Icon(Icons.Outlined.Delete, contentDescription = "Delete tag", tint = DangerRed.copy(alpha = 0.8f), modifier = Modifier.size(15.dp))
            }
          }
        }
      }
    }
  }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// HELPER METRIC CARD COMPONENT
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
private fun OverviewMetricCard(
  modifier: Modifier = Modifier,
  title: String,
  count: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  iconTint: Color,
  onClick: () -> Unit
) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = DarkSurface,
    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle),
    modifier = modifier.clickable { onClick() }
  ) {
    Column(
      modifier = Modifier.padding(10.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = count,
        color = TextPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        text = title,
        color = TextMuted,
        fontSize = 10.sp
      )
    }
  }
}
