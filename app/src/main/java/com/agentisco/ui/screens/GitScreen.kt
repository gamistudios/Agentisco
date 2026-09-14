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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentisco.core.model.AppDestination
import com.agentisco.data.repository.WorkspaceRepository
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.theme.*

@Composable
fun GitScreen(
  viewModel: WorkspaceViewModel,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  val activeProject by viewModel.activeProject.collectAsState()
  val diffs by viewModel.fileDiffs.collectAsState()
  val stagedFiles by viewModel.stagedFiles.collectAsState()
  val commitMessage by viewModel.commitMessage.collectAsState()
  val commitHistory by viewModel.commitHistory.collectAsState()
  val isGitRepository by viewModel.isGitRepository.collectAsState()
  val commitGenState by viewModel.commitGenState.collectAsState()
  val gitError by viewModel.gitError.collectAsState()

  // VS Code-style split: staged files are listed under "Staged Changes",
  // everything else under "Changes". The staging area (git index) is the
  // single source of truth for the split.
  val isRepo = isGitRepository == true
  val stagedDiffs = diffs.filter { stagedFiles.contains(it.filePath) }
  val unstagedDiffs = diffs.filter { !stagedFiles.contains(it.filePath) }

  var messageText by remember(commitMessage) { mutableStateOf(commitMessage) }
  val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
      .padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    item {
      Spacer(modifier = Modifier.height(10.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text("Source Control", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Commit, contentDescription = "Branch", tint = ElectricBlueGlow, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(activeProject.branch, color = ElectricBlueGlow, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.width(10.dp))
            Text("↑ 2 commits ahead  ·  ↓ 0 behind", color = TextMuted, fontSize = 11.sp)
          }
        }

        OutlinedButton(
          onClick = { onNavigate(AppDestination.DIFF) },
          border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
          modifier = Modifier.height(32.dp)
        ) {
          Text("Diff All", fontSize = 11.sp, color = TextSecondary)
        }
      }
    }

    if (gitError != null) {
      item {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DangerRed.copy(alpha = 0.1f))
            .border(1.dp, DangerRed.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            gitError!!,
            color = DangerRed,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier.weight(1f)
          )
          Icon(
            Icons.Outlined.ContentCopy,
            contentDescription = "Copy error",
            tint = TextMuted,
            modifier = Modifier
              .padding(start = 4.dp)
              .size(12.dp)
              .clickable {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(gitError!!))
              }
          )
          TextButton(onClick = { viewModel.dismissGitError() }) {
            Text("Dismiss", color = TextMuted, fontSize = 10.sp)
          }
        }
      }
    }

    // Not a git repository yet: offer VS Code-style initialization.
    if (isGitRepository == false) {
      item {
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, WarningAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
          colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
          Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Outlined.Info, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                "This project folder is not a git repository yet.",
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
              )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              "Initialize a repository to start tracking changes, staging files, and committing.",
              color = TextMuted,
              fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
              onClick = { viewModel.initGitRepository() },
              colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
              modifier = Modifier.testTag("btn_git_init")
            ) {
              Text("Initialize Repository", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
          }
        }
      }
    }

    // Commit Message Input Card
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text("Commit message", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

            // Generate with Agent: uses the configured provider/model on the
            // complete staged diff; shows generating / failed states.
            val genBusy = commitGenState is WorkspaceRepository.CommitGenState.Generating
            Button(
              onClick = { viewModel.generateCommitMessageWithAgent() },
              enabled = !genBusy,
              colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue.copy(alpha = 0.2f)),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
              modifier = Modifier
                .height(26.dp)
                .testTag("btn_generate_commit_msg")
            ) {
              if (genBusy) {
                CircularProgressIndicator(
                  modifier = Modifier.size(11.dp),
                  color = ElectricBlueGlow,
                  strokeWidth = 1.5.dp
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Generating…", color = ElectricBlueGlow, fontSize = 10.sp, fontWeight = FontWeight.Bold)
              } else {
                Icon(Icons.Default.AutoAwesome, contentDescription = "Agent", tint = ElectricBlueGlow, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Generate with Agent", color = ElectricBlueGlow, fontSize = 10.sp, fontWeight = FontWeight.Bold)
              }
            }
          }

          val genFailed = commitGenState as? WorkspaceRepository.CommitGenState.Failed
          if (genFailed != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(DangerRed.copy(alpha = 0.1f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                genFailed.error,
                color = DangerRed,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                modifier = Modifier.weight(1f)
              )
              TextButton(onClick = { viewModel.dismissCommitGenState() }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("Dismiss", color = TextMuted, fontSize = 9.sp)
              }
            }
          }

          Spacer(modifier = Modifier.height(8.dp))

          TextField(
            value = messageText,
            onValueChange = {
              messageText = it
              viewModel.updateCommitMessage(it)
            },
            placeholder = { Text("Brief description of what changed...", color = TextMuted, fontSize = 12.sp) },
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(DarkBackground)
              .testTag("input_commit_message"),
            colors = TextFieldDefaults.colors(
              focusedContainerColor = DarkBackground,
              unfocusedContainerColor = DarkBackground,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary,
              focusedIndicatorColor = Color.Transparent,
              unfocusedIndicatorColor = Color.Transparent
            )
          )

          Spacer(modifier = Modifier.height(12.dp))

          Button(
            onClick = {
              viewModel.commitStagedChanges()
            },
            enabled = stagedFiles.isNotEmpty() && messageText.isNotBlank(),
            modifier = Modifier
              .fillMaxWidth()
              .height(42.dp)
              .testTag("btn_commit_changes"),
            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
            shape = RoundedCornerShape(8.dp)
          ) {
            Text("Commit ${stagedFiles.size} Staged Changes", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
          }
        }
      }
    }

    // Staged & Changed Files List Header with Stage/Unstage all
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = if (isRepo) "Staged Changes (${stagedDiffs.size})" else "Staged Changes",
          color = TextSecondary,
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold
        )

        if (isRepo && stagedDiffs.isNotEmpty()) {
          TextButton(
            onClick = { viewModel.unstageAll() },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
          ) {
            Text("Unstage All", fontSize = 11.sp, color = TextMuted)
          }
        }
      }
    }

    if (!isRepo) {
      item {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurfaceElevated)
            .padding(20.dp),
          contentAlignment = Alignment.Center
        ) {
          Text("Initialize a repository to track changes", color = TextMuted, fontSize = 12.sp)
        }
      }
    } else if (stagedDiffs.isEmpty()) {
      item {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurfaceElevated)
            .padding(20.dp),
          contentAlignment = Alignment.Center
        ) {
          Text("No staged changes — stage files from the Changes section below", color = TextMuted, fontSize = 12.sp)
        }
      }
    } else {
      items(stagedDiffs) { diff ->
        ChangeRow(
          diff = diff,
          staged = true,
          onToggle = { viewModel.setFileStaged(diff.filePath, stage = false) },
          onViewDiff = { onNavigate(AppDestination.DIFF) }
        )
      }
    }

    // Changes (unstaged) section
    item {
      Spacer(modifier = Modifier.height(12.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Changes (${unstagedDiffs.size})",
          color = TextSecondary,
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold
        )

        if (isRepo && unstagedDiffs.isNotEmpty()) {
          TextButton(
            onClick = { viewModel.stageAll() },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
          ) {
            Text("Stage All", fontSize = 11.sp, color = ElectricBlueGlow)
          }
        }
      }
    }

    if (isRepo && unstagedDiffs.isEmpty()) {
      item {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurfaceElevated)
            .padding(20.dp),
          contentAlignment = Alignment.Center
        ) {
          Text("No unstaged modifications", color = TextMuted, fontSize = 12.sp)
        }
      }
    } else if (isRepo) {
      items(unstagedDiffs) { diff ->
        ChangeRow(
          diff = diff,
          staged = false,
          onToggle = { viewModel.setFileStaged(diff.filePath, stage = true) },
          onViewDiff = { onNavigate(AppDestination.DIFF) }
        )
      }
    }

    // Commit History Section
    item {
      Spacer(modifier = Modifier.height(10.dp))
      Text(
        text = "Commit History (${commitHistory.size})",
        color = TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold
      )
    }

    if (commitHistory.isEmpty()) {
      item {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurfaceElevated)
            .padding(16.dp),
          contentAlignment = Alignment.Center
        ) {
          Text("No commits recorded yet", color = TextMuted, fontSize = 12.sp)
        }
      }
    } else {
      items(commitHistory) { commit ->
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, DarkBorder, RoundedCornerShape(10.dp)),
          colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
          Column(modifier = Modifier.padding(12.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Commit, contentDescription = "Commit", tint = ElectricBlueGlow, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = commit.hash,
                  color = ElectricBlueGlow,
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = FontWeight.SemiBold
                )
              }
              Text(
                text = "${commit.filesChanged.size} files",
                color = TextMuted,
                fontSize = 10.sp
              )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = commit.message,
              color = TextPrimary,
              fontSize = 13.sp,
              fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(
                text = commit.author,
                color = TextSecondary,
                fontSize = 10.sp
              )
              Text(
                text = "just now",
                color = TextMuted,
                fontSize = 10.sp
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
}

/**
 * One change row, VS Code-style: green `+` stages an unstaged change,
 * red `-` unstages a staged change.
 */
@Composable
private fun ChangeRow(
  diff: com.agentisco.data.model.FileDiff,
  staged: Boolean,
  onToggle: () -> Unit,
  onViewDiff: () -> Unit
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .border(
        1.dp,
        if (staged) TerminalGreen.copy(alpha = 0.45f) else DarkBorder,
        RoundedCornerShape(10.dp)
      ),
    colors = CardDefaults.cardColors(containerColor = DarkSurface)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 10.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f)
      ) {
        Box(
          modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(
              if (staged) TerminalGreen.copy(alpha = 0.15f) else DarkSurfaceElevated
            )
            .border(
              1.dp,
              if (staged) TerminalGreen.copy(alpha = 0.6f) else DarkBorder,
              CircleShape
            )
            .clickable(onClick = onToggle)
            .testTag(if (staged) "btn_unstage_file" else "btn_stage_file"),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = if (staged) "−" else "+",
            color = if (staged) DangerRed else TerminalGreen,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column {
          Text(
            text = diff.filePath.substringAfterLast('/'),
            color = TextPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
          )
          Text(
            text = diff.filePath,
            color = TextMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
          )
        }
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("+${diff.additionsCount}", color = TerminalGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.width(4.dp))
        Text("-${diff.deletionsCount}", color = DangerRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        IconButton(
          onClick = onViewDiff,
          modifier = Modifier.size(28.dp)
        ) {
          Icon(Icons.Default.ChevronRight, contentDescription = "View Diff", tint = TextMuted, modifier = Modifier.size(16.dp))
        }
      }
    }
  }
}

