package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AIModel
import com.example.data.model.AppDestination
import com.example.data.model.Project
import com.example.ui.theme.*

@Composable
fun AgentIDETopAppBar(
  activeProject: Project,
  selectedModel: AIModel,
  currentDestination: AppDestination,
  onNavigate: (AppDestination) -> Unit,
  onOpenModelSheet: () -> Unit,
  onOpenCommandPalette: () -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .statusBarsPadding(),
    color = DarkBackground,
    border = androidx.compose.foundation.BorderStroke(0.dp, DarkBorderSubtle)
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(56.dp)
          .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Project Selector Pill
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurfaceElevated)
            .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
            .clickable { onNavigate(AppDestination.PROJECTS) }
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("top_project_selector")
        ) {
          Box(
            modifier = Modifier
              .size(8.dp)
              .clip(CircleShape)
              .background(if (activeProject.isDirty) WarningAmber else TerminalGreen)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = activeProject.name,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = "· ${activeProject.branch}",
            color = TextMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
          )
          Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = "Switch project",
            tint = TextMuted,
            modifier = Modifier.size(16.dp)
          )
        }

        // Action controls
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          // Model chip
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
              .clip(RoundedCornerShape(8.dp))
              .background(DarkSurface)
              .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
              .clickable { onOpenModelSheet() }
              .padding(horizontal = 8.dp, vertical = 5.dp)
              .testTag("top_model_selector")
          ) {
            Text(
              text = selectedModel.name,
              color = ElectricBlueGlow,
              fontSize = 11.sp,
              fontWeight = FontWeight.Medium,
              maxLines = 1
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
              imageVector = Icons.Default.KeyboardArrowDown,
              contentDescription = "Select Model",
              tint = ElectricBlueGlow,
              modifier = Modifier.size(14.dp)
            )
          }

          // Command Palette Shortcut Button (>)
          IconButton(
            onClick = onOpenCommandPalette,
            modifier = Modifier
              .size(34.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(DarkSurfaceElevated)
              .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
              .testTag("top_cmd_palette")
          ) {
            Text(
              text = ">",
              color = CyanAccent,
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              fontFamily = FontFamily.Monospace
            )
          }

          // Settings Button
          IconButton(
            onClick = { onNavigate(AppDestination.SETTINGS) },
            modifier = Modifier
              .size(34.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(DarkSurfaceElevated)
              .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
              .testTag("top_settings")
          ) {
            Icon(
              imageVector = Icons.Outlined.Settings,
              contentDescription = "Settings",
              tint = TextSecondary,
              modifier = Modifier.size(16.dp)
            )
          }
        }
      }

      // Contextual Tabs Row (Agent, Files, Changes, Terminal, Build/Run, Git)
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        ContextChip(
          title = "Agent",
          selected = currentDestination == AppDestination.AGENT,
          onClick = { onNavigate(AppDestination.AGENT) }
        )
        ContextChip(
          title = "Files",
          selected = currentDestination == AppDestination.FILES,
          onClick = { onNavigate(AppDestination.FILES) }
        )
        ContextChip(
          title = "Editor",
          selected = currentDestination == AppDestination.EDITOR,
          onClick = { onNavigate(AppDestination.EDITOR) }
        )
        ContextChip(
          title = "Changes",
          badge = if (activeProject.changedFilesCount > 0) "${activeProject.changedFilesCount}" else null,
          selected = currentDestination == AppDestination.DIFF,
          onClick = { onNavigate(AppDestination.DIFF) }
        )
        ContextChip(
          title = "Git",
          selected = currentDestination == AppDestination.GIT,
          onClick = { onNavigate(AppDestination.GIT) }
        )
        ContextChip(
          title = "Build",
          selected = currentDestination == AppDestination.BUILD_RUN,
          onClick = { onNavigate(AppDestination.BUILD_RUN) }
        )
      }
      Spacer(modifier = Modifier.height(4.dp))
      HorizontalDivider(color = DarkBorderSubtle, thickness = 1.dp)
    }
  }
}

@Composable
private fun ContextChip(
  title: String,
  badge: String? = null,
  selected: Boolean,
  onClick: () -> Unit
) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(if (selected) ElectricBlue.copy(alpha = 0.18f) else DarkSurface)
      .border(
        1.dp,
        if (selected) ElectricBlue.copy(alpha = 0.6f) else DarkBorderSubtle,
        RoundedCornerShape(6.dp)
      )
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 4.dp),
    contentAlignment = Alignment.Center
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) ElectricBlueGlow else TextSecondary
      )
      if (badge != null) {
        Spacer(modifier = Modifier.width(4.dp))
        Box(
          modifier = Modifier
            .clip(CircleShape)
            .background(WarningAmber)
            .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
          Text(
            text = badge,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = DarkBackground
          )
        }
      }
    }
  }
}
