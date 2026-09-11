package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.getValue
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
import com.example.data.model.AppDestination
import com.example.ui.theme.*

@Composable
fun AgentIDEBottomBar(
  currentDestination: AppDestination,
  isAgentWorking: Boolean,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .windowInsetsPadding(WindowInsets.navigationBars),
    color = DarkSurface,
    tonalElevation = 6.dp,
    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(64.dp)
        .padding(horizontal = 16.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceAround,
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

  Box(
    modifier = Modifier
      .height(52.dp)
      .clip(RoundedCornerShape(16.dp))
      .clickable(
        interactionSource = interactionSource,
        indication = ripple(color = ElectricBlue),
        onClick = onClick
      )
      .padding(horizontal = 16.dp, vertical = 4.dp)
      .testTag(testTag),
    contentAlignment = Alignment.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = if (selected) {
          Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isHero) ElectricBlue.copy(alpha = 0.22f) else DarkSurfaceElevated)
            .padding(horizontal = 12.dp, vertical = 4.dp)
        } else {
          Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        }
      ) {
        Icon(
          imageVector = if (selected) selectedIcon else unselectedIcon,
          contentDescription = label,
          tint = when {
            selected && isHero -> ElectricBlueGlow
            selected -> TextPrimary
            else -> TextMuted
          },
          modifier = Modifier.size(22.dp)
        )

        // Working pulse badge
        if (isPulse && isHero) {
          Box(
            modifier = Modifier
              .size(8.dp)
              .align(Alignment.TopEnd)
              .clip(CircleShape)
              .background(TerminalGreen)
              .border(1.dp, DarkSurface, CircleShape)
          )
        }
      }

      Spacer(modifier = Modifier.height(2.dp))

      Text(
        text = label,
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) TextPrimary else TextMuted
      )
    }
  }
}
