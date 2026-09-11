package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.example.data.model.DiffLine
import com.example.data.model.DiffLineType
import com.example.data.model.FileDiff
import com.example.ui.WorkspaceViewModel
import com.example.ui.theme.*

@Composable
fun DiffScreen(
  viewModel: WorkspaceViewModel,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  val fileDiffs by viewModel.fileDiffs.collectAsState()

  val totalAdditions = remember(fileDiffs) { fileDiffs.sumOf { it.additionsCount } }
  val totalDeletions = remember(fileDiffs) { fileDiffs.sumOf { it.deletionsCount } }

  var selectedDiffIndex by remember { mutableIntStateOf(0) }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
  ) {
    // Diff Top Header
    Surface(
      modifier = Modifier.fillMaxWidth(),
      color = DarkSurface,
      border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle)
    ) {
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
              text = "Changes Diff",
              color = TextPrimary,
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = "${fileDiffs.size} files changed",
                color = TextSecondary,
                fontSize = 11.sp
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "+$totalAdditions",
                color = TerminalGreen,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "-$totalDeletions",
                color = DangerRed,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
              )
            }
          }
        }

        // Accept all / Reject all buttons
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            onClick = { viewModel.rejectAllDiffs() },
            modifier = Modifier
              .height(32.dp)
              .testTag("btn_reject_diff"),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
          ) {
            Text("Reject", color = TextSecondary, fontSize = 11.sp)
          }

          Button(
            onClick = { viewModel.acceptAllDiffs() },
            modifier = Modifier
              .height(32.dp)
              .testTag("btn_accept_diff"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TerminalGreen)
          ) {
            Text("Accept All", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
          }
        }
      }
    }

    if (fileDiffs.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(24.dp),
        contentAlignment = Alignment.Center
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(
            imageVector = Icons.Outlined.CheckCircleOutline,
            contentDescription = "No changes",
            tint = TerminalGreen,
            modifier = Modifier.size(48.dp)
          )
          Spacer(modifier = Modifier.height(12.dp))
          Text("No Unstaged Changes", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
          Spacer(modifier = Modifier.height(4.dp))
          Text("All files are clean and synchronized with git.", color = TextMuted, fontSize = 12.sp)
          Spacer(modifier = Modifier.height(16.dp))
          Button(
            onClick = { onNavigate(AppDestination.AGENT) },
            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
          ) {
            Text("Back to Agent")
          }
        }
      }
    } else {
      // File selector tabs
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .background(DarkSurfaceElevated)
          .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        fileDiffs.forEachIndexed { index, diff ->
          val isSelected = index == selectedDiffIndex
          val fileName = diff.filePath.substringAfterLast('/')

          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(6.dp))
              .background(if (isSelected) DarkSurfaceHighlight else DarkSurface)
              .border(1.dp, if (isSelected) ElectricBlue else DarkBorderSubtle, RoundedCornerShape(6.dp))
              .clickable { selectedDiffIndex = index }
              .padding(horizontal = 10.dp, vertical = 6.dp)
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = fileName,
                color = if (isSelected) TextPrimary else TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "+${diff.additionsCount}",
                color = TerminalGreen,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
              )
              Spacer(modifier = Modifier.width(2.dp))
              Text(
                text = "-${diff.deletionsCount}",
                color = DangerRed,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
              )
            }
          }
        }
      }

      val currentDiff = fileDiffs.getOrNull(selectedDiffIndex) ?: fileDiffs.first()

      // Full diff view
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .background(DarkBackground)
      ) {
        item {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .background(DarkSurface)
              .padding(horizontal = 14.dp, vertical = 8.dp)
          ) {
            Text(
              text = currentDiff.filePath,
              color = TextMuted,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace
            )
          }
        }

        items(currentDiff.lines) { line ->
          DiffLineRow(line = line)
        }

        item {
          Spacer(modifier = Modifier.height(40.dp))
        }
      }
    }
  }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
  val bgColor = when (line.type) {
    DiffLineType.ADDED -> TerminalGreenBg.copy(alpha = 0.35f)
    DiffLineType.REMOVED -> DangerRedBg.copy(alpha = 0.35f)
    DiffLineType.UNCHANGED -> Color.Transparent
  }

  val textColor = when (line.type) {
    DiffLineType.ADDED -> TerminalGreen
    DiffLineType.REMOVED -> DangerRed
    DiffLineType.UNCHANGED -> TextSecondary
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(bgColor)
      .padding(vertical = 1.5.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    // Old Line No
    Text(
      text = line.oldLineNo?.toString() ?: "",
      color = TextMuted,
      fontSize = 10.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.width(28.dp)
    )

    // New Line No
    Text(
      text = line.newLineNo?.toString() ?: "",
      color = TextMuted,
      fontSize = 10.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.width(28.dp)
    )

    // Prefix symbol (+ or -)
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

    // Content
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
