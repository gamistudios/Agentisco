package com.agentisco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentisco.core.model.AppDestination
import com.agentisco.ui.theme.*

/**
 * The IDE's three primary surfaces. Projects and Terminal are workspaces with
 * their own chrome; Agent is the hero surface and keeps an accented outline of
 * its own (plus a live pulse while it is working). Only the open surface is ever
 * filled, so the bar never shows two equally active items.
 */
@Composable
fun AgentIDEBottomBar(
  currentDestination: AppDestination,
  isAgentWorking: Boolean,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    color = DarkSurface,
    tonalElevation = 8.dp,
    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .defaultMinSize(minHeight = 62.dp)
        .padding(horizontal = 10.dp, vertical = 5.dp),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically
    ) {
      BottomNavItem(
        label = "Projects",
        selected = currentDestination == AppDestination.PROJECTS,
        selectedIcon = Icons.Filled.Folder,
        unselectedIcon = Icons.Outlined.Folder,
        onClick = { onNavigate(AppDestination.PROJECTS) },
        testTag = "nav_projects"
      )

      BottomNavItem(
        label = "Agent",
        selected = currentDestination == AppDestination.AGENT,
        selectedIcon = Icons.Filled.AutoAwesome,
        unselectedIcon = Icons.Outlined.AutoAwesome,
        isHero = true,
        isPulse = isAgentWorking,
        onClick = { onNavigate(AppDestination.AGENT) },
        testTag = "nav_agent"
      )

      BottomNavItem(
        label = "Terminal",
        selected = currentDestination == AppDestination.TERMINAL,
        selectedIcon = Icons.Filled.Terminal,
        unselectedIcon = Icons.Outlined.Terminal,
        onClick = { onNavigate(AppDestination.TERMINAL) },
        testTag = "nav_terminal"
      )
    }
  }
}

@Composable
private fun BottomNavItem(
  label: String,
  selected: Boolean,
  selectedIcon: ImageVector,
  unselectedIcon: ImageVector,
  isHero: Boolean = false,
  isPulse: Boolean = false,
  onClick: () -> Unit,
  testTag: String
) {
  val interactionSource = remember { MutableInteractionSource() }

  // Exactly one item is ever "active": only the selected surface gets a filled
  // highlight. The hero (Agent) keeps its standing by wearing an accent *outline*
  // when it is not selected — important, but visibly not the open screen.
  val container = when {
    selected -> ElectricBlue.copy(alpha = 0.20f)
    else -> Color.Transparent
  }
  val borderColor = when {
    selected -> ElectricBlue.copy(alpha = 0.55f)
    isHero -> ElectricBlue.copy(alpha = 0.22f)
    else -> Color.Transparent
  }
  val contentColor = when {
    selected -> ElectricBlueGlow
    isHero -> ElectricBlueGlow.copy(alpha = 0.62f)
    else -> TextMuted
  }

  Box(
    modifier = Modifier
      .defaultMinSize(minWidth = 84.dp, minHeight = 50.dp)
      .clip(RoundedCornerShape(14.dp))
      .clickable(
        interactionSource = interactionSource,
        indication = ripple(color = ElectricBlue),
        onClick = onClick
      )
      .testTag(testTag),
    contentAlignment = Alignment.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .clip(RoundedCornerShape(11.dp))
          .background(container)
          .border(1.dp, borderColor, RoundedCornerShape(11.dp))
          .padding(horizontal = 18.dp, vertical = 5.dp)
      ) {
        Icon(
          imageVector = if (selected) selectedIcon else unselectedIcon,
          contentDescription = label,
          tint = contentColor,
          modifier = Modifier.size(22.dp)
        )

        // Working pulse badge
        if (isPulse && isHero) {
          Box(
            modifier = Modifier
              .size(8.dp)
              .align(Alignment.TopEnd)
              .offset(x = 2.dp, y = (-2).dp)
              .clip(CircleShape)
              .background(TerminalGreen)
              .border(1.dp, DarkSurface, CircleShape)
          )
        }
      }

      Spacer(modifier = Modifier.height(2.dp))

      Text(
        text = label,
        fontSize = 10.5.sp,
        fontWeight = if (selected || isHero) FontWeight.SemiBold else FontWeight.Medium,
        color = contentColor,
        maxLines = 1
      )
    }
  }
}
