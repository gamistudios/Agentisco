package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.data.model.AppDestination
import com.example.ui.WorkspaceViewModel
import com.example.ui.theme.*

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

  var messageText by remember(commitMessage) { mutableStateOf(commitMessage) }

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

            // Generate with Agent Button (Section 17)
            Button(
              onClick = {
                viewModel.generateCommitMessageWithAgent()
              },
              colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue.copy(alpha = 0.2f)),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
              modifier = Modifier
                .height(26.dp)
                .testTag("btn_generate_commit_msg")
            ) {
              Icon(Icons.Default.AutoAwesome, contentDescription = "Agent", tint = ElectricBlueGlow, modifier = Modifier.size(12.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Generate with Agent", color = ElectricBlueGlow, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
          text = "Changes (${diffs.size})",
          color = TextSecondary,
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          TextButton(
            onClick = { viewModel.stageAll() },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
          ) {
            Text("Stage All", fontSize = 11.sp, color = ElectricBlueGlow)
          }

          TextButton(
            onClick = { viewModel.unstageAll() },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
          ) {
            Text("Unstage All", fontSize = 11.sp, color = TextMuted)
          }
        }
      }
    }

    if (diffs.isEmpty()) {
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
    } else {
      items(diffs) { diff ->
        val isStaged = stagedFiles.contains(diff.filePath)

        Card(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, DarkBorder, RoundedCornerShape(10.dp)),
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
              Checkbox(
                checked = isStaged,
                onCheckedChange = { viewModel.toggleFileStaged(diff.filePath) },
                colors = CheckboxDefaults.colors(checkedColor = ElectricBlue)
              )

              Column {
                Text(
                  text = "M  ${diff.filePath.substringAfterLast('/')}",
                  color = WarningAmber,
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
                onClick = { onNavigate(AppDestination.DIFF) },
                modifier = Modifier.size(28.dp)
              ) {
                Icon(Icons.Default.ChevronRight, contentDescription = "View Diff", tint = TextMuted, modifier = Modifier.size(16.dp))
              }
            }
          }
        }
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
