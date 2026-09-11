package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import com.example.data.model.AIModel
import com.example.data.model.AIProvider
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorSheet(
  isOpen: Boolean,
  currentModel: AIModel,
  providers: List<AIProvider>,
  onSelectModel: (AIModel) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier
) {
  if (!isOpen) return

  var showAddModelDialog by remember { mutableStateOf(false) }

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
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text(
            text = "Select AI Model",
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Choose model and provider for coding agent",
            color = TextSecondary,
            fontSize = 12.sp
          )
        }

        IconButton(
          onClick = { showAddModelDialog = true },
          modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurfaceElevated)
            .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
            .testTag("btn_add_custom_model")
        ) {
          Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "Add custom model",
            tint = ElectricBlueGlow,
            modifier = Modifier.size(18.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        items(providers) { provider ->
          Column(modifier = Modifier.fillMaxWidth()) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(bottom = 6.dp)
            ) {
              Box(
                modifier = Modifier
                  .size(8.dp)
                  .clip(CircleShape)
                  .background(if (provider.isConnected) TerminalGreen else TextMuted)
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
                text = if (provider.isConnected) "Connected" else "Not configured",
                color = TextMuted,
                fontSize = 10.sp
              )
            }

            Column(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkBackground)
                .border(1.dp, DarkBorderSubtle, RoundedCornerShape(12.dp))
            ) {
              if (provider.models.isEmpty()) {
                Text(
                  text = "No models configured for ${provider.name}. Tap + to add.",
                  color = TextMuted,
                  fontSize = 11.sp,
                  modifier = Modifier.padding(12.dp)
                )
              } else {
                provider.models.forEachIndexed { index, model ->
                  val isSelected = model.id == currentModel.id

                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .clickable { onSelectModel(model) }
                      .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Column(modifier = Modifier.weight(1f)) {
                      Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                          text = model.name,
                          color = if (isSelected) ElectricBlueGlow else TextPrimary,
                          fontSize = 13.sp,
                          fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                        if (model.isFree) {
                          Spacer(modifier = Modifier.width(6.dp))
                          Box(
                            modifier = Modifier
                              .clip(RoundedCornerShape(4.dp))
                              .background(TerminalGreenBg)
                              .padding(horizontal = 5.dp, vertical = 1.dp)
                          ) {
                            Text("FREE", color = TerminalGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                          }
                        }
                      }

                      Spacer(modifier = Modifier.height(3.dp))

                      // Capabilities badges
                      Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                      ) {
                        CapabilityPill("Ctx ${model.contextWindow}")
                        if (model.hasTools) CapabilityPill("Tools")
                        if (model.hasReasoning) CapabilityPill("Reasoning")
                        if (model.hasVision) CapabilityPill("Vision")
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

                  if (index < provider.models.size - 1) {
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

  if (showAddModelDialog) {
    AddCustomModelDialog(onDismiss = { showAddModelDialog = false })
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

@Composable
private fun AddCustomModelDialog(onDismiss: () -> Unit) {
  var modelId by remember { mutableStateOf("z-ai/glm-5.3-free") }
  var displayName by remember { mutableStateOf("GLM 5.3 Free") }
  var contextWindow by remember { mutableStateOf("1000000") }
  var toolCalling by remember { mutableStateOf(true) }
  var streaming by remember { mutableStateOf(true) }
  var vision by remember { mutableStateOf(false) }
  var reasoning by remember { mutableStateOf(true) }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = DarkSurface,
    title = {
      Text("Add Model", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        OutlinedTextField(
          value = modelId,
          onValueChange = { modelId = it },
          label = { Text("Model ID", fontSize = 11.sp) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
          value = displayName,
          onValueChange = { displayName = it },
          label = { Text("Display name", fontSize = 11.sp) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
          value = contextWindow,
          onValueChange = { contextWindow = it },
          label = { Text("Context window", fontSize = 11.sp) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )

        Text("Capabilities", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically) {
          Checkbox(checked = toolCalling, onCheckedChange = { toolCalling = it })
          Text("Tool calling", color = TextPrimary, fontSize = 12.sp)
          Spacer(modifier = Modifier.width(12.dp))
          Checkbox(checked = streaming, onCheckedChange = { streaming = it })
          Text("Streaming", color = TextPrimary, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Checkbox(checked = reasoning, onCheckedChange = { reasoning = it })
          Text("Reasoning", color = TextPrimary, fontSize = 12.sp)
          Spacer(modifier = Modifier.width(12.dp))
          Checkbox(checked = vision, onCheckedChange = { vision = it })
          Text("Vision", color = TextPrimary, fontSize = 12.sp)
        }
      }
    },
    confirmButton = {
      Button(
        onClick = onDismiss,
        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
      ) {
        Text("Save Model", fontSize = 12.sp)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel", color = TextMuted, fontSize = 12.sp)
      }
    }
  )
}
