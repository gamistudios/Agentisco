package com.agentisco.ui.components

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentisco.core.model.AppDestination
import com.agentisco.data.model.Project
import com.agentisco.data.repository.UpdateRepository
import com.agentisco.ui.theme.*

/**
 * The global IDE header. Row 1 is the project/Git header (opened workspace +
 * branch + global actions); row 2 is the IDE toolbar that navigates the seven
 * primary IDE surfaces. Both are rendered above every screen, so they carry the
 * "where am I / what can I run" burden that otherwise costs each screen a card.
 */
@Composable
fun AgentIDETopAppBar(
  activeProject: Project,
  currentDestination: AppDestination,
  onNavigate: (AppDestination) -> Unit,
  onOpenModelSheet: () -> Unit,
  onOpenCommandPalette: () -> Unit,
  modifier: Modifier = Modifier,
  updateState: UpdateRepository.UpdateState? = null,
  updateProgress: Float = 0f,
  hasNewUpdate: Boolean = false,
  onUpdateClick: (() -> Unit)? = null
) {
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .statusBarsPadding(),
    color = DarkBackground,
    border = androidx.compose.foundation.BorderStroke(0.dp, DarkBorderSubtle)
  ) {
    Box(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.fillMaxWidth()) {
        // ——— Row 1: project / Git header ———
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          ProjectSelectorPill(
            project = activeProject,
            onClick = { onNavigate(AppDestination.PROJECTS) },
            modifier = Modifier.weight(1f)
          )

          // One-handed reach strip: update · command palette · settings.
          if (onUpdateClick != null) {
            HeaderIconButton(
              icon = Icons.Outlined.SystemUpdateAlt,
              contentDescription = if (hasNewUpdate) {
                "Update available — tap to download"
              } else {
                "Check for app updates"
              },
              tint = if (hasNewUpdate) WarningAmber else TextSecondary,
              borderColor = if (hasNewUpdate) WarningAmber.copy(alpha = 0.65f) else DarkBorder,
              background = if (hasNewUpdate) WarningAmber.copy(alpha = 0.10f) else DarkSurfaceElevated,
              onClick = onUpdateClick,
              showDot = hasNewUpdate,
              testTag = "top_update"
            )
          }

          CommandPaletteButton(onClick = onOpenCommandPalette)

          HeaderIconButton(
            icon = Icons.Outlined.Settings,
            contentDescription = "Settings",
            tint = TextSecondary,
            onClick = { onNavigate(AppDestination.SETTINGS) },
            testTag = "top_settings"
          )
        }

        // ——— Row 2: IDE toolbar (seven primary surfaces) ———
        IdeToolbar(
          selected = currentDestination,
          changedFilesCount = activeProject.changedFilesCount,
          onNavigate = onNavigate
        )

        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = DarkBorderSubtle, thickness = 1.dp)
      }

      DownloadProgressIndicator(
        progress = updateProgress,
        visible = updateState == UpdateRepository.UpdateState.DOWNLOADING,
        modifier = Modifier
          .fillMaxWidth()
          .align(Alignment.TopCenter)
      )
    }
  }
}

/**
 * Compact workspace selector: live project name, current Git branch in mono,
 * and a dropdown affordance. Opens the Projects screen, which is the project
 * switcher. The name truncates before the branch or the chevron ever do.
 */
@Composable
private fun ProjectSelectorPill(
  project: Project,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val statusColor = when {
    project.isMissing -> DangerRed
    project.isDirty -> WarningAmber
    else -> TerminalGreen
  }
  val branch = project.branch.ifBlank { "main" }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .height(36.dp)
      .clip(RoundedCornerShape(10.dp))
      .background(DarkSurfaceElevated)
      .border(1.dp, DarkBorder, RoundedCornerShape(10.dp))
      .clickable(onClick = onClick)
      .padding(start = 9.dp, end = 2.dp)
      .testTag("top_project_selector")
  ) {
    Box(
      modifier = Modifier
        .size(7.dp)
        .clip(CircleShape)
        .background(statusColor)
        .border(1.dp, statusColor.copy(alpha = 0.35f), CircleShape)
    )
    Spacer(modifier = Modifier.width(8.dp))

    Text(
      text = project.name.ifBlank { "No project" },
      color = TextPrimary,
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false)
    )

    Spacer(modifier = Modifier.width(7.dp))

    // Branch chip — monospace, matching what git prints in the terminal.
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .clip(RoundedCornerShape(6.dp))
        .background(DarkSurfaceHighlight)
        .border(1.dp, DarkBorderSubtle, RoundedCornerShape(6.dp))
        .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
      Icon(
        imageVector = Icons.Outlined.AccountTree,
        contentDescription = null,
        tint = TextMuted,
        modifier = Modifier.size(10.dp)
      )
      Spacer(modifier = Modifier.width(4.dp))
      Text(
        text = branch,
        color = TextSecondary,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1
      )
    }

    Icon(
      imageVector = Icons.Default.ArrowDropDown,
      contentDescription = "Switch project",
      tint = TextMuted,
      modifier = Modifier.size(18.dp)
    )
  }
}

/**
 * The command palette entry point. Deliberately reads as a global search/command
 * surface (prompt chevron + magnifier) rather than a generic overflow menu.
 */
@Composable
private fun CommandPaletteButton(onClick: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .height(36.dp)
      .clip(RoundedCornerShape(10.dp))
      .background(DarkSurfaceElevated)
      .border(1.dp, IndigoAccent.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp)
      .semantics { contentDescription = "Command palette — search commands and files" }
      .testTag("top_cmd_palette")
  ) {
    Text(
      text = ">",
      color = CyanAccent,
      fontSize = 13.sp,
      fontWeight = FontWeight.Bold,
      fontFamily = FontFamily.Monospace
    )
    Spacer(modifier = Modifier.width(5.dp))
    Icon(
      imageVector = Icons.Outlined.Search,
      contentDescription = null,
      tint = TextSecondary,
      modifier = Modifier.size(15.dp)
    )
  }
}

@Composable
private fun HeaderIconButton(
  icon: ImageVector,
  contentDescription: String,
  tint: Color,
  onClick: () -> Unit,
  testTag: String,
  borderColor: Color = DarkBorder,
  background: Color = DarkSurfaceElevated,
  showDot: Boolean = false
) {
  Box(
    modifier = Modifier
      .size(36.dp)
      .clip(RoundedCornerShape(10.dp))
      .background(background)
      .border(1.dp, borderColor, RoundedCornerShape(10.dp))
      .clickable(onClick = onClick)
      .testTag(testTag)
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = tint,
      modifier = Modifier
        .size(17.dp)
        .align(Alignment.Center)
    )
    if (showDot) {
      Box(
        modifier = Modifier
          .size(7.dp)
          .align(Alignment.TopEnd)
          .offset(x = (-3).dp, y = 3.dp)
          .clip(CircleShape)
          .background(WarningAmber)
          .border(1.dp, DarkSurface, CircleShape)
      )
    }
  }
}

/** One entry in the IDE toolbar — icon over label, so it never reads as a mystery glyph. */
private data class IdeTool(
  val label: String,
  val icon: ImageVector,
  val destination: AppDestination,
  val testTag: String,
  val hint: String? = null
)

/** Gap between two toolbar items; kept small so all seven share the row. */
private val IdeToolbarGap = 3.dp

/** Item width under which the label drops a point rather than truncating. */
private val IdeToolbarNarrowItem = 44.dp

/**
 * The seven primary IDE surfaces, spread evenly across the full width: every
 * item gets an equal share of the row, so all seven tools are visible at once on
 * a phone. Nothing here scrolls — an earlier horizontally scrollable variant
 * pushed Git and Build off-screen behind a scroll with no affordance at all.
 *
 * Icons sit above their labels to buy horizontal room; [AppDestination.BUILD_RUN]
 * carries a "soon" hint, which shrinks to a corner marker because a word cannot
 * fit seven-up. Its meaning survives in the item's content description.
 */
@Composable
private fun IdeToolbar(
  selected: AppDestination,
  changedFilesCount: Int,
  onNavigate: (AppDestination) -> Unit
) {
  val tools = listOf(
    IdeTool("Projects", Icons.Outlined.Dashboard, AppDestination.PROJECTS, "tool_projects"),
    IdeTool("Agent", Icons.Outlined.AutoAwesome, AppDestination.AGENT, "tool_agent"),
    IdeTool("Files", Icons.Outlined.FolderOpen, AppDestination.FILES, "tool_files"),
    IdeTool("Editor", Icons.Outlined.Code, AppDestination.EDITOR, "tool_editor"),
    IdeTool("Changes", Icons.Outlined.Difference, AppDestination.DIFF, "tool_changes"),
    IdeTool("Git", Icons.Outlined.AccountTree, AppDestination.GIT, "tool_git"),
    IdeTool("Build", Icons.Outlined.Build, AppDestination.BUILD_RUN, "tool_build", hint = "soon")
  )

  BoxWithConstraints(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 10.dp)
      .padding(bottom = 4.dp)
  ) {
    // Seven labels ("Projects" is the longest) have to fit the screen we have:
    // on very narrow devices drop a point instead of letting them ellipsize.
    val perItem = (maxWidth - IdeToolbarGap * (tools.size - 1)) / tools.size
    val narrow = perItem < IdeToolbarNarrowItem
    val labelSize: TextUnit = if (narrow) 8.sp else 9.sp
    // The theme's body style carries a 24sp line height, which would make a 9sp
    // label taller than the chip. These labels set their own.
    val labelLine: TextUnit = if (narrow) 10.sp else 11.sp

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .testTag("ide_toolbar"),
      horizontalArrangement = Arrangement.spacedBy(IdeToolbarGap),
      verticalAlignment = Alignment.CenterVertically
    ) {
      tools.forEach { tool ->
        IdeToolChip(
          tool = tool,
          selected = selected == tool.destination,
          badge = if (tool.destination == AppDestination.DIFF && changedFilesCount > 0) {
            changedFilesCount.toString()
          } else {
            null
          },
          labelSize = labelSize,
          labelLine = labelLine,
          modifier = Modifier.weight(1f),
          onClick = { onNavigate(tool.destination) }
        )
      }
    }
  }
}

@Composable
private fun IdeToolChip(
  tool: IdeTool,
  selected: Boolean,
  badge: String?,
  labelSize: TextUnit,
  labelLine: TextUnit,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val shape = RoundedCornerShape(9.dp)
  Box(
    modifier = modifier
      .height(44.dp)
      .clip(shape)
      .background(if (selected) ElectricBlue.copy(alpha = 0.16f) else DarkSurface)
      .border(
        1.dp,
        if (selected) ElectricBlue.copy(alpha = 0.55f) else DarkBorderSubtle,
        shape
      )
      .clickable(onClick = onClick)
      .then(
        // The "soon" marker is a dot here; keep the word for screen readers.
        if (tool.hint != null) {
          Modifier.semantics { contentDescription = "${tool.label} — ${tool.hint}" }
        } else {
          Modifier
        }
      )
      .testTag(tool.testTag),
    contentAlignment = Alignment.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Icon(
        imageVector = tool.icon,
        contentDescription = null,
        tint = if (selected) ElectricBlueGlow else TextMuted,
        modifier = Modifier.size(16.dp)
      )

      Spacer(modifier = Modifier.height(2.dp))

      Text(
        text = tool.label,
        fontSize = labelSize,
        lineHeight = labelLine,
        letterSpacing = 0.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        color = if (selected) ElectricBlueGlow else TextSecondary,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 1.dp)
      )
    }

    // Both markers hang off the chip's own top-right corner, *inside* the chip,
    // so the rounded clip can never shave them off. Anchoring them to the icon
    // instead put them half outside the chip and cut them flat.
    when {
      badge != null -> ToolCountBadge(
        count = badge,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(top = 3.dp, end = 4.dp)
      )
      tool.hint != null -> Box(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(top = 4.dp, end = 5.dp)
          .size(6.dp)
          .clip(CircleShape)
          .background(TextSecondary)
          .border(1.dp, DarkSurface, CircleShape)
      )
    }
  }
}

/** Changed-files count on the Changes tool — solid amber so it survives at this size. */
@Composable
private fun ToolCountBadge(count: String, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .defaultMinSize(minWidth = 13.dp, minHeight = 13.dp)
      .clip(RoundedCornerShape(7.dp))
      .background(WarningAmber)
      .border(1.dp, DarkSurface, RoundedCornerShape(7.dp))
      .padding(horizontal = 3.dp),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = count,
      fontSize = 8.sp,
      lineHeight = 9.sp,
      fontWeight = FontWeight.Bold,
      color = DarkBackground,
      maxLines = 1
    )
  }
}
