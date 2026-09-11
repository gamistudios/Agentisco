package com.agentisco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentisco.data.repository.WorkspaceRepository
import com.agentisco.settings.model.AIModel
import com.agentisco.settings.model.AIProvider
import com.agentisco.settings.model.LLMProtocol
import com.agentisco.settings.model.ModelCapabilities
import com.agentisco.settings.model.ReasoningConfig
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.theme.*

/**
 * Provider-centric AI configuration UI: provider cards (name, base URL,
 * protocol, model count, connection state) with open/edit/test/delete, a
 * provider detail view listing its models, and add/edit model forms.
 */
@Composable
fun AIProvidersSection(viewModel: WorkspaceViewModel, modifier: Modifier = Modifier) {
  val providers by viewModel.providers.collectAsState()
  val models by viewModel.aiModels.collectAsState()
  val connectionTests by viewModel.connectionTests.collectAsState()

  var editProvider by remember { mutableStateOf<AIProvider?>(null) }
  var showAddProvider by remember { mutableStateOf(false) }
  var detailProvider by remember { mutableStateOf<AIProvider?>(null) }
  var confirmDeleteProvider by remember { mutableStateOf<AIProvider?>(null) }
  var addModelFor by remember { mutableStateOf<AIProvider?>(null) }
  var editModel by remember { mutableStateOf<AIModel?>(null) }

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text("AI Providers", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
      Surface(
        onClick = { showAddProvider = true },
        shape = RoundedCornerShape(8.dp),
        color = ElectricBlue.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue),
        modifier = Modifier.testTag("btn_add_provider")
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(Icons.Default.Add, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Add Provider", color = ElectricBlueGlow, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
      }
    }

    Spacer(modifier = Modifier.height(10.dp))

    if (providers.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(DarkBackground)
          .border(1.dp, DarkBorderSubtle, RoundedCornerShape(12.dp))
          .padding(14.dp)
      ) {
        Text(
          "No providers configured. Add one (e.g. an OpenAI-compatible router) with its base URL, protocol and API key, then add models to it.",
          color = TextMuted,
          fontSize = 12.sp
        )
      }
    }

    providers.forEach { provider ->
      val testState = connectionTests[provider.id]
      val modelCount = models.count { it.providerId == provider.id }
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(DarkSurface)
          .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
          .clickable { detailProvider = provider }
          .padding(12.dp)
          .testTag("provider_card_${provider.name}")
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
            Box(
              modifier = Modifier
                .size(8.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(
                  when (testState) {
                    is WorkspaceRepository.ConnectionTestState.Connected -> TerminalGreen
                    is WorkspaceRepository.ConnectionTestState.Testing -> WarningAmber
                    is WorkspaceRepository.ConnectionTestState.Failed -> DangerRed
                    null -> if (provider.hasApiKey) TerminalGreen.copy(alpha = 0.5f) else TextMuted
                  }
                )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(provider.name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
          }
          Text(
            text = when (testState) {
              is WorkspaceRepository.ConnectionTestState.Testing -> "Testing…"
              is WorkspaceRepository.ConnectionTestState.Connected -> testState.note
              is WorkspaceRepository.ConnectionTestState.Failed -> "Connection failed"
              null -> if (provider.hasApiKey) "Key set" else "No API key"
            },
            color = when (testState) {
              is WorkspaceRepository.ConnectionTestState.Connected -> TerminalGreen
              is WorkspaceRepository.ConnectionTestState.Failed -> DangerRed
              else -> TextMuted
            },
            fontSize = 10.sp,
            maxLines = 1
          )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(provider.protocol.displayName, color = CyanAccent, fontSize = 11.sp)
        Text(
          provider.baseUrl,
          color = TextMuted,
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace,
          maxLines = 1
        )
        Text(
          "$modelCount model${if (modelCount == 1) "" else "s"}",
          color = TextSecondary,
          fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          MiniAction("Open") { detailProvider = provider }
          MiniAction("Test") { viewModel.testProviderConnection(provider.id) }
          MiniAction("Edit") { editProvider = provider }
          MiniAction("Delete", DangerRed) { confirmDeleteProvider = provider }
        }

        testState?.let { state ->
          if (state is WorkspaceRepository.ConnectionTestState.Failed) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(state.message, color = DangerRed.copy(alpha = 0.9f), fontSize = 10.sp, maxLines = 3)
          }
        }
      }
      Spacer(modifier = Modifier.height(10.dp))
    }
  }

  // ---- Dialogs ----

  if (showAddProvider) {
    ProviderFormDialog(
      existing = null,
      onDismiss = { showAddProvider = false },
      onSave = { name, url, protocol, key ->
        viewModel.saveProvider(name, url, protocol, key)
        showAddProvider = false
      }
    )
  }

  editProvider?.let { provider ->
    ProviderFormDialog(
      existing = provider,
      onDismiss = { editProvider = null },
      onSave = { name, url, protocol, key ->
        viewModel.saveProvider(name, url, protocol, key, provider.id)
        editProvider = null
      }
    )
  }

  detailProvider?.let { provider ->
    ProviderDetailDialog(
      viewModel = viewModel,
      provider = provider,
      models = models.filter { it.providerId == provider.id },
      onDismiss = { detailProvider = null },
      onAddModel = { addModelFor = provider },
      onEditModel = { editModel = it }
    )
  }

  addModelFor?.let { provider ->
    ModelFormDialog(
      provider = provider,
      existing = null,
      onDismiss = { addModelFor = null },
      onSave = { providerId, modelId, name, ctxWin, maxOut, caps, reasoning ->
        viewModel.saveModel(providerId, modelId, name, ctxWin, maxOut, caps, reasoning)
        addModelFor = null
      }
    )
  }

  editModel?.let { model ->
    val provider = providers.firstOrNull { it.id == model.providerId }
    ModelFormDialog(
      provider = provider,
      existing = model,
      onDismiss = { editModel = null },
      onSave = { providerId, modelId, name, ctxWin, maxOut, caps, reasoning ->
        viewModel.saveModel(providerId, modelId, name, ctxWin, maxOut, caps, reasoning, model.id)
        editModel = null
      }
    )
  }

  confirmDeleteProvider?.let { provider ->
    AlertDialog(
      onDismissRequest = { confirmDeleteProvider = null },
      containerColor = DarkSurface,
      title = { Text("Delete \"${provider.name}\"?", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
      text = {
        Text(
          "All models belonging to this provider (${models.count { it.providerId == provider.id }}) will also be removed and become unavailable to the agent. Stored API keys for this provider are erased. This cannot be undone.",
          color = TextSecondary,
          fontSize = 12.sp
        )
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.deleteProvider(provider.id)
            confirmDeleteProvider = null
            if (detailProvider?.id == provider.id) detailProvider = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
        ) { Text("Delete Provider", color = Color.White) }
      },
      dismissButton = {
        TextButton(onClick = { confirmDeleteProvider = null }) { Text("Cancel", color = TextMuted) }
      }
    )
  }
}

@Composable
private fun MiniAction(label: String, tint: Color = TextSecondary, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(DarkSurfaceElevated)
      .border(1.dp, DarkBorderSubtle, RoundedCornerShape(6.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 4.dp)
  ) {
    Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun ProviderFormDialog(
  existing: AIProvider?,
  onDismiss: () -> Unit,
  onSave: (name: String, baseUrl: String, protocol: LLMProtocol, apiKey: String?) -> Unit
) {
  var name by remember { mutableStateOf(existing?.name ?: "") }
  var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "https://") }
  var protocol by remember { mutableStateOf(existing?.protocol ?: LLMProtocol.OPENAI_CHAT_COMPLETIONS) }
  var apiKey by remember { mutableStateOf("") }

  val valid = name.isNotBlank() && baseUrl.startsWith("http")

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = DarkSurface,
    title = {
      Text(
        if (existing == null) "Add Provider" else "Edit Provider",
        color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold
      )
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        OutlinedTextField(
          value = name, onValueChange = { name = it },
          label = { Text("Provider name", fontSize = 11.sp) },
          singleLine = true, modifier = Modifier.fillMaxWidth().testTag("input_provider_name")
        )
        OutlinedTextField(
          value = baseUrl, onValueChange = { baseUrl = it },
          label = { Text("Base URL", fontSize = 11.sp) },
          supportingText = {
            Text(
              if (protocol == LLMProtocol.OPENAI_CHAT_COMPLETIONS) "OpenAI-compatible base, e.g. https://router.huggingface.co/v1"
              else "Anthropic base, e.g. https://api.anthropic.com",
              fontSize = 9.sp, fontFamily = FontFamily.Monospace
            )
          },
          singleLine = true, modifier = Modifier.fillMaxWidth().testTag("input_provider_url")
        )
        Text("Protocol", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          LLMProtocol.entries.forEach { p ->
            Surface(
              onClick = { protocol = p },
              shape = RoundedCornerShape(6.dp),
              color = if (protocol == p) ElectricBlue.copy(alpha = 0.2f) else DarkSurfaceElevated,
              border = androidx.compose.foundation.BorderStroke(1.dp, if (protocol == p) ElectricBlue else DarkBorderSubtle)
            ) {
              Text(
                p.displayName, color = if (protocol == p) ElectricBlueGlow else TextSecondary,
                fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
              )
            }
          }
        }
        OutlinedTextField(
          value = apiKey, onValueChange = { apiKey = it },
          label = { Text(if (existing?.hasApiKey == true) "API key (leave blank to keep)" else "API key", fontSize = 11.sp) },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true, modifier = Modifier.fillMaxWidth().testTag("input_provider_key")
        )
        Text(
          "Keys are stored in the app's private storage and never sent anywhere except the provider endpoint.",
          color = TextMuted, fontSize = 9.sp
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onSave(name, baseUrl, protocol, apiKey.takeIf { it.isNotBlank() }) },
        enabled = valid,
        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
        modifier = Modifier.testTag("btn_save_provider")
      ) { Text("Save", fontSize = 12.sp) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted, fontSize = 12.sp) }
    }
  )
}

@Composable
private fun ProviderDetailDialog(
  viewModel: WorkspaceViewModel,
  provider: AIProvider,
  models: List<AIModel>,
  onDismiss: () -> Unit,
  onAddModel: () -> Unit,
  onEditModel: (AIModel) -> Unit
) {
  val connectionTests by viewModel.connectionTests.collectAsState()
  val testState = connectionTests[provider.id]

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = DarkSurface,
    title = {
      Column {
        Text(provider.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(provider.protocol.displayName, color = CyanAccent, fontSize = 11.sp)
      }
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        DetailField("Base URL", provider.baseUrl)
        DetailField("API Key", if (provider.hasApiKey) "••••••••••••" else "not set")
        if (testState != null) {
          when (testState) {
            is WorkspaceRepository.ConnectionTestState.Testing -> StatusLine("Testing connection…", WarningAmber)
            is WorkspaceRepository.ConnectionTestState.Connected -> StatusLine(testState.note, TerminalGreen)
            is WorkspaceRepository.ConnectionTestState.Failed -> StatusLine(testState.message, DangerRed)
          }
        }

        Text("${models.size} Model${if (models.size == 1) "" else "s"}", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (models.isEmpty()) {
          Text("No models yet — the agent cannot use this provider until a model is added.", color = TextMuted, fontSize = 11.sp)
        }
        models.forEach { model ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(DarkBackground)
              .border(1.dp, DarkBorderSubtle, RoundedCornerShape(8.dp))
              .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(model.displayName, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
              Text(model.modelId, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                Icons.Default.Edit, contentDescription = "Edit model",
                tint = ElectricBlueGlow,
                modifier = Modifier
                  .size(16.dp)
                  .clickable { onEditModel(model) }
                  .testTag("btn_edit_model_${model.modelId}")
              )
              Spacer(modifier = Modifier.width(10.dp))
              Icon(
                Icons.Default.Delete, contentDescription = "Delete model",
                tint = DangerRed,
                modifier = Modifier
                  .size(16.dp)
                  .clickable { viewModel.deleteModel(model.id) }
              )
            }
          }
        }

        Surface(
          onClick = onAddModel,
          shape = RoundedCornerShape(8.dp),
          color = ElectricBlue.copy(alpha = 0.12f),
          border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue),
          modifier = Modifier.fillMaxWidth().testTag("btn_add_model")
        ) {
          Row(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add Model", color = ElectricBlueGlow, fontSize = 12.sp, fontWeight = FontWeight.Medium)
          }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          MiniAction("Test Connection") { viewModel.testProviderConnection(provider.id) }
          MiniAction("Close") { onDismiss() }
        }
      }
    },
    confirmButton = {},
    dismissButton = {}
  )
}

@Composable
private fun DetailField(label: String, value: String) {
  Column {
    Text(label, color = TextMuted, fontSize = 9.sp)
    Text(value, color = TextCode, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
  }
}

@Composable
private fun StatusLine(text: String, color: Color) {
  Text(text, color = color, fontSize = 10.sp)
}

@Composable
private fun ModelFormDialog(
  provider: AIProvider?,
  existing: AIModel?,
  onDismiss: () -> Unit,
  onSave: (providerId: String, modelId: String, displayName: String, contextWindow: Int?, maxOutputTokens: Int?, ModelCapabilities, ReasoningConfig?) -> Unit
) {
  var modelId by remember { mutableStateOf(existing?.modelId ?: "") }
  var displayName by remember { mutableStateOf(existing?.displayName ?: "") }
  var ctxWindow by remember { mutableStateOf(existing?.contextWindow?.toString() ?: "") }
  var maxOut by remember { mutableStateOf(existing?.maxOutputTokens?.toString() ?: "") }
  var tools by remember { mutableStateOf(existing?.capabilities?.tools ?: true) }
  var streaming by remember { mutableStateOf(existing?.capabilities?.streaming ?: true) }
  var images by remember { mutableStateOf(existing?.capabilities?.images ?: false) }
  var promptCaching by remember { mutableStateOf(existing?.capabilities?.promptCaching ?: false) }
  var interleaved by remember { mutableStateOf(existing?.capabilities?.interleavedReasoning ?: false) }
  var maxTokensParam by remember { mutableStateOf(existing?.capabilities?.maxTokensParameter ?: true) }
  var reasoningEnabled by remember { mutableStateOf(existing?.reasoning?.enabled ?: false) }
  var reasoningEffort by remember { mutableStateOf(existing?.reasoning?.effort ?: "medium") }

  val valid = provider != null && modelId.isNotBlank() && displayName.isNotBlank()

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = DarkSurface,
    title = {
      Column {
        Text(
          if (existing == null) "Add Model" else "Edit Model",
          color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold
        )
        provider?.let { Text("Provider: ${it.name}", color = CyanAccent, fontSize = 11.sp) }
      }
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        OutlinedTextField(
          value = modelId, onValueChange = { modelId = it },
          label = { Text("Model ID", fontSize = 11.sp) },
          supportingText = { Text("Wire identifier, e.g. openai/gpt-oss-120b", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
          singleLine = true, modifier = Modifier.fillMaxWidth().testTag("input_model_id")
        )
        OutlinedTextField(
          value = displayName, onValueChange = { displayName = it },
          label = { Text("Display Name", fontSize = 11.sp) },
          singleLine = true, modifier = Modifier.fillMaxWidth().testTag("input_model_name")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = ctxWindow, onValueChange = { ctxWindow = it.filter { c -> c.isDigit() } },
            label = { Text("Context (tokens)", fontSize = 11.sp) },
            singleLine = true, modifier = Modifier.weight(1f)
          )
          OutlinedTextField(
            value = maxOut, onValueChange = { maxOut = it.filter { c -> c.isDigit() } },
            label = { Text("Max output", fontSize = 11.sp) },
            singleLine = true, modifier = Modifier.weight(1f)
          )
        }

        Text("Capabilities", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        CapabilityRow("Tool calling", tools) { tools = it }
        CapabilityRow("Streaming", streaming) { streaming = it }
        CapabilityRow("Images", images) { images = it }
        CapabilityRow("Prompt caching", promptCaching) { promptCaching = it }
        CapabilityRow("Interleaved reasoning", interleaved) { interleaved = it }
        CapabilityRow("max_tokens parameter", maxTokensParam) { maxTokensParam = it }

        CapabilityRow("Reasoning mode", reasoningEnabled) { reasoningEnabled = it }
        if (reasoningEnabled) {
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("low", "medium", "high").forEach { effort ->
              Surface(
                onClick = { reasoningEffort = effort },
                shape = RoundedCornerShape(6.dp),
                color = if (reasoningEffort == effort) ElectricBlue.copy(alpha = 0.2f) else DarkSurfaceElevated,
                border = androidx.compose.foundation.BorderStroke(1.dp, if (reasoningEffort == effort) ElectricBlue else DarkBorderSubtle)
              ) {
                Text(
                  effort, color = if (reasoningEffort == effort) ElectricBlueGlow else TextSecondary,
                  fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
              }
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSave(
            provider!!.id,
            modelId,
            displayName,
            ctxWindow.takeIf { it.isNotBlank() }?.toIntOrNull(),
            maxOut.takeIf { it.isNotBlank() }?.toIntOrNull(),
            ModelCapabilities(
              tools = tools, images = images, streaming = streaming,
              promptCaching = promptCaching, interleavedReasoning = interleaved,
              maxTokensParameter = maxTokensParam
            ),
            ReasoningConfig(enabled = reasoningEnabled, effort = reasoningEffort).takeIf { reasoningEnabled }
          )
        },
        enabled = valid,
        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
        modifier = Modifier.testTag("btn_save_model")
      ) { Text("Save", fontSize = 12.sp) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted, fontSize = 12.sp) }
    }
  )
}

@Composable
private fun CapabilityRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onChange(!checked) },
    verticalAlignment = Alignment.CenterVertically
  ) {
    Checkbox(checked = checked, onCheckedChange = onChange, colors = CheckboxDefaults.colors(checkedColor = ElectricBlue))
    Text(label, color = TextPrimary, fontSize = 12.sp)
  }
}
