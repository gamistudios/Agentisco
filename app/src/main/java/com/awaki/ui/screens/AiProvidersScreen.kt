package com.awaki.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awaki.core.model.AppDestination
import com.awaki.ui.WorkspaceViewModel
import com.awaki.ui.components.AIProvidersSection
import com.awaki.ui.theme.*

/**
 * Dedicated provider-management surface.
 *
 * Providers and their models can grow long (a dozen providers is normal), and
 * embedding that list in [SettingsScreen] pushed every other setting far below
 * the fold. Settings now holds only a navigation card that opens this screen,
 * so provider management gets the whole scroll area and the rest of Settings
 * stays reachable.
 */
@Composable
fun AiProvidersScreen(
  viewModel: WorkspaceViewModel,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  val providers by viewModel.providers.collectAsState()
  val models by viewModel.aiModels.collectAsState()

  val providerCount = providers.size
  val modelCount = models.size

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
          modifier = Modifier.size(32.dp).testTag("btn_ai_providers_back")
        ) {
          Icon(Icons.Default.ChevronLeft, contentDescription = "Back to settings", tint = TextMuted)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Column {
          Text("AI Providers & Models", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
          Text(
            "$providerCount provider${if (providerCount == 1) "" else "s"} · $modelCount model${if (modelCount == 1) "" else "s"} configured",
            color = TextMuted,
            fontSize = 12.sp
          )
        }
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
        // The section itself is not scrollable; this LazyColumn provides the
        // scrolling for it (nesting a verticalScroll Column here would crash).
        AIProvidersSection(viewModel = viewModel, modifier = Modifier.padding(14.dp))
      }
    }

    item {
      Spacer(modifier = Modifier.height(24.dp))
    }
  }
}
