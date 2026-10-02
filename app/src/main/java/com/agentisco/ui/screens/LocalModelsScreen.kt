package com.agentisco.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentisco.core.model.AppDestination
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.components.LocalModelsSection
import com.agentisco.ui.theme.DarkBackground
import com.agentisco.ui.theme.DarkBorder
import com.agentisco.ui.theme.DarkSurface
import com.agentisco.ui.theme.TextMuted
import com.agentisco.ui.theme.TextPrimary

/**
 * Management surface for the models that run on this device.
 *
 * Separate from [AiProvidersScreen] because the two manage different objects: a provider
 * row is an address and a secret, a model row is a file on disk with a download to resume,
 * a size to reclaim and runtime settings of its own. Both are doorways from the same place
 * in Settings, and a model still reaches the agent as a provider — only its management
 * lives here.
 */
@Composable
fun LocalModelsScreen(
  viewModel: WorkspaceViewModel,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
      .padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    item {
      Spacer(modifier = Modifier.height(10.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
          onClick = { onNavigate(AppDestination.SETTINGS) },
          modifier = Modifier.size(32.dp).testTag("btn_local_models_back")
        ) {
          Icon(Icons.Default.ChevronLeft, contentDescription = "Back to settings", tint = TextMuted)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text("Local Models", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
      }
    }

    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
      ) {
        // The section itself is not scrollable; this LazyColumn provides the scrolling
        // for it (nesting a verticalScroll Column here would crash).
        LocalModelsSection(viewModel = viewModel, modifier = Modifier.padding(14.dp))
      }
    }

    item {
      Spacer(modifier = Modifier.height(24.dp))
    }
  }
}
