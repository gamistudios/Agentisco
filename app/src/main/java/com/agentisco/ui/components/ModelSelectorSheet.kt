package com.agentisco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import com.agentisco.settings.model.AIModel
import com.agentisco.settings.model.AIProvider
import com.agentisco.ui.theme.*

/**
 * Model selector listing every configured model grouped by its provider.
 * The same model identifier under two providers appears twice — the entries
 * are distinct selectable models because they inherit different connections.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorSheet(
  isOpen: Boolean,
  currentModel: AIModel?,
  providers: List<AIProvider>,
  models: List<AIModel>,
  onSelectModel: (AIModel) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier
) {
  if (!isOpen) return

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = DarkSurface,
    tonalElevation = 8.dp,
    dragHandle = {
      Box(
        modifier = Modifier
          .padding(vertical = 10.dp)
          .width(36.dp)
          .height(4.dp)
          .clip(CircleShape)
          .background(DarkBorder)
      )
    }
  ) {
    Column(
      modifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 6.dp)
        .padding(bottom = 32.dp)
    ) {
      Column {
        Text(
          text = "Select AI Model",
          color = TextPrimary,
          fontSize = 17.sp,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = "Models grouped by provider · manage providers in Settings",
          color = TextSecondary,
          fontSize = 12.sp
        )
      }

      Spacer(modifier = Modifier.height(16.dp))

      if (models.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkBackground)
            .border(1.dp, DarkBorderSubtle, RoundedCornerShape(12.dp))
            .padding(16.dp)
        ) {
          Text(
            text = "No models configured yet.\nAdd a provider and its models in Settings → AI Providers.",
            color = TextMuted,
            fontSize = 12.sp
          )
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          items(providers, key = { it.id }) { provider ->
            val providerModels = models.filter { it.providerId == provider.id }
            Column(modifier = Modifier.fillMaxWidth()) {
              // Provider header with separator
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(bottom = 6.dp)
              ) {
                Box(
                  modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (provider.hasApiKey) TerminalGreen else TextMuted)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = provider.name,
                  color = TextSecondary,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.SemiBold,
                  letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = if (provider.hasApiKey) "Key set" else "No API key",
                  color = TextMuted,
                  fontSize = 10.sp
                )
              }
              HorizontalDivider(color = DarkBorderSubtle, thickness = 0.5.dp)
              Spacer(modifier = Modifier.height(4.dp))

              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(12.dp))
                  .background(DarkBackground)
                  .border(1.dp, DarkBorderSubtle, RoundedCornerShape(12.dp))
              ) {
                if (providerModels.isEmpty()) {
                  Text(
                    text = "No models configured for ${provider.name}. Add models in Settings.",
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(12.dp)
                  )
                } else {
                  providerModels.forEachIndexed { index, model ->
                    val isSelected = currentModel?.id == model.id
                    ModelRow(
                      model = model,
                      providerName = provider.name,
                      isSelected = isSelected,
                      onSelect = { onSelectModel(model) }
                    )
                    if (index < providerModels.size - 1) {
                      HorizontalDivider(color = DarkBorderSubtle, thickness = 0.5.dp)
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ModelRow(
  model: AIModel,
  providerName: String,
  isSelected: Boolean,
  onSelect: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onSelect)
      .testTag("model_option_${model.id}")
      .padding(horizontal = 12.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = model.displayName,
          color = if (isSelected) ElectricBlueGlow else TextPrimary,
          fontSize = 13.sp,
          fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = providerName,
          color = TextMuted,
          fontSize = 10.sp
        )
      }

      Spacer(modifier = Modifier.height(3.dp))

      Text(
        text = model.modelId,
        color = TextMuted,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace
      )

      Spacer(modifier = Modifier.height(5.dp))

      // Capability indicators (only what the runtime actually branches on)
      Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        model.contextWindow?.let { CapabilityPill("Ctx ${it / 1000}k") }
        if (model.capabilities.tools) CapabilityPill("Tools") else CapabilityPill("No tools")
        if (model.capabilities.streaming) CapabilityPill("Stream")
        if (model.capabilities.images) CapabilityPill("Images")
        if (model.reasoning?.enabled == true) CapabilityPill("Reasoning")
      }
    }

    if (isSelected) {
      Icon(
        imageVector = Icons.Default.Check,
        contentDescription = "Selected",
        tint = ElectricBlueGlow,
        modifier = Modifier.size(18.dp)
      )
    }
  }
}

@Composable
private fun CapabilityPill(text: String) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(DarkSurfaceElevated)
      .padding(horizontal = 5.dp, vertical = 1.dp)
  ) {
    Text(
      text = text,
      color = TextMuted,
      fontSize = 9.sp,
      fontFamily = FontFamily.Monospace
    )
  }
}
