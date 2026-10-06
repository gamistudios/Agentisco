package com.awaki.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awaki.agent.llm.CatalogModel
import com.awaki.data.repository.WorkspaceRepository
import com.awaki.settings.model.AIModel
import com.awaki.settings.model.AIProvider
import com.awaki.settings.model.LLMProtocol
import com.awaki.settings.model.ModelCapabilities
import com.awaki.settings.model.ReasoningConfig
import com.awaki.ui.WorkspaceViewModel
import com.awaki.ui.theme.*
import com.awaki.ui.theme.AwakiTheme

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
  val catalogs by viewModel.modelCatalogs.collectAsState()

  /** Models a provider listed for itself, once they have been fetched. */
  fun catalogOf(providerId: String): List<CatalogModel> =
    (catalogs[providerId] as? WorkspaceRepository.ModelCatalogState.Available)?.models.orEmpty()

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
      Text("AI Providers", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
      Surface(
        onClick = { showAddProvider = true },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.testTag("btn_add_provider")
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Add Provider", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
      }
    }

    Spacer(modifier = Modifier.height(10.dp))

    if (providers.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.background)
          .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
          .padding(14.dp)
      ) {
        Text(
          "No providers configured. Add one (e.g. an OpenAI-compatible router) with its base URL, protocol and API key, then add models to it.",
          color = AwakiTheme.extra.textMuted,
          fontSize = 12.sp
        )
      }
    }

    providers.forEach { provider ->
      val testState = connectionTests[provider.id]
      val modelCount = models.count { it.providerId == provider.id }
      // The on-device provider is derived from the files this device holds: its address
      // and token belong to this run, so there is nothing here to edit or delete.
      val derived = provider.id == com.awaki.local.LocalAiRuntime.PROVIDER_ID
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.surface)
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
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
                    is WorkspaceRepository.ConnectionTestState.Connected -> AwakiTheme.extra.success
                    is WorkspaceRepository.ConnectionTestState.Testing -> AwakiTheme.extra.warning
                    is WorkspaceRepository.ConnectionTestState.Failed -> MaterialTheme.colorScheme.error
                    null -> if (provider.hasApiKey) AwakiTheme.extra.success.copy(alpha = 0.5f) else AwakiTheme.extra.textMuted
                  }
                )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(provider.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
          }
          Text(
            text = when (testState) {
              is WorkspaceRepository.ConnectionTestState.Testing -> "Testing…"
              is WorkspaceRepository.ConnectionTestState.Connected -> testState.note
              is WorkspaceRepository.ConnectionTestState.Failed -> "Connection failed"
              null -> when {
                derived -> if (modelCount > 0) "On this device" else "Nothing installed"
                provider.hasApiKey -> "Key set"
                else -> "No API key"
              }
            },
            color = when (testState) {
              is WorkspaceRepository.ConnectionTestState.Connected -> AwakiTheme.extra.success
              is WorkspaceRepository.ConnectionTestState.Failed -> MaterialTheme.colorScheme.error
              else -> AwakiTheme.extra.textMuted
            },
            fontSize = 10.sp,
            maxLines = 1
          )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(provider.protocol.displayName, color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp)
        Text(
          provider.baseUrl,
          color = AwakiTheme.extra.textMuted,
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace,
          maxLines = 1
        )
        Text(
          "$modelCount model${if (modelCount == 1) "" else "s"}",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          MiniAction("Open") { detailProvider = provider }
          MiniAction("Test") { viewModel.testProviderConnection(provider.id) }
          if (!derived) {
            MiniAction("Edit") { editProvider = provider }
            MiniAction("Delete", MaterialTheme.colorScheme.error) { confirmDeleteProvider = provider }
          }
        }

        testState?.let { state ->
          if (state is WorkspaceRepository.ConnectionTestState.Failed) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(state.message, color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f), fontSize = 10.sp, maxLines = 3)
          }
        }

        // Discovery is a convenience, so its failure says little and blocks nothing.
        if (modelCount == 0 && catalogs[provider.id] is WorkspaceRepository.ModelCatalogState.Failed) {
          Spacer(modifier = Modifier.height(6.dp))
          Text("Model auto-discovery failed — add models manually.", color = AwakiTheme.extra.textMuted, fontSize = 10.sp)
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
      readOnly = provider.id == com.awaki.local.LocalAiRuntime.PROVIDER_ID,
      onDismiss = { detailProvider = null },
      onAddModel = { addModelFor = provider },
      onEditModel = { editModel = it }
    )
  }

  addModelFor?.let { provider ->
    ModelFormDialog(
      provider = provider,
      existing = null,
      catalog = catalogOf(provider.id),
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
      catalog = catalogOf(model.providerId),
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
      containerColor = MaterialTheme.colorScheme.surface,
      title = { Text("Delete \"${provider.name}\"?", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
      text = {
        Text(
          "All models belonging to this provider (${models.count { it.providerId == provider.id }}) will also be removed and become unavailable to the agent. Stored API keys for this provider are erased. This cannot be undone.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
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
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) { Text("Delete Provider", color = MaterialTheme.colorScheme.onError) }
      },
      dismissButton = {
        TextButton(onClick = { confirmDeleteProvider = null }) { Text("Cancel", color = AwakiTheme.extra.textMuted) }
      }
    )
  }
}

@Composable
internal fun MiniAction(label: String, tint: Color = Color.Unspecified, modifier: Modifier = Modifier, onClick: () -> Unit) {
  val labelColor = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(6.dp))
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 4.dp)
  ) {
    Text(label, color = labelColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
  var keyVisible by remember { mutableStateOf(false) }

  val valid = name.isNotBlank() && baseUrl.startsWith("http")

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
    title = {
      Text(
        if (existing == null) "Add Provider" else "Edit Provider",
        color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold
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
              when (protocol) {
                LLMProtocol.OPENAI_CHAT_COMPLETIONS -> "OpenAI-compatible base, e.g. https://router.huggingface.co/v1"
                LLMProtocol.OPENAI_RESPONSES -> "Responses base, e.g. https://api.openai.com/v1"
                LLMProtocol.ANTHROPIC_MESSAGES -> "Anthropic base, e.g. https://api.anthropic.com"
                LLMProtocol.GOOGLE_GEMINI -> "Gemini base, e.g. https://generativelanguage.googleapis.com/v1beta"
              },
              fontSize = 9.sp, fontFamily = FontFamily.Monospace
            )
          },
          singleLine = true, modifier = Modifier.fillMaxWidth().testTag("input_provider_url")
        )
        Text("Protocol", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        // FlowRow, not Row: on a narrow screen a fixed Row squeezes every chip
        // until the label wraps one character per line ("G/e/m/i/n/i").
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          LLMProtocol.entries.forEach { p ->
            Surface(
              onClick = { protocol = p },
              shape = RoundedCornerShape(6.dp),
              color = if (protocol == p) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainer,
              border = androidx.compose.foundation.BorderStroke(1.dp, if (protocol == p) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
            ) {
              Text(
                p.displayName, color = if (protocol == p) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp, maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
              )
            }
          }
        }
        OutlinedTextField(
          value = apiKey, onValueChange = { apiKey = it },
          label = { Text(if (existing?.hasApiKey == true) "API key (leave blank to keep)" else "API key", fontSize = 11.sp) },
          singleLine = true, modifier = Modifier.fillMaxWidth().testTag("input_provider_key"),
          visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
          trailingIcon = {
            IconButton(
              onClick = { keyVisible = !keyVisible },
              modifier = Modifier.testTag("btn_toggle_key_visible")
            ) {
              Icon(
                imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = if (keyVisible) "Hide API key" else "Show API key",
                tint = AwakiTheme.extra.textMuted,
                modifier = Modifier.size(16.dp)
              )
            }
          }
        )
        Text(
          "Keys are stored in the app's private storage and never sent anywhere except the provider endpoint.",
          color = AwakiTheme.extra.textMuted, fontSize = 9.sp
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onSave(name, baseUrl, protocol, apiKey.takeIf { it.isNotBlank() }) },
        enabled = valid,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        modifier = Modifier.testTag("btn_save_provider")
      ) { Text("Save", fontSize = 12.sp) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel", color = AwakiTheme.extra.textMuted, fontSize = 12.sp) }
    }
  )
}

@Composable
private fun ProviderDetailDialog(
  viewModel: WorkspaceViewModel,
  provider: AIProvider,
  models: List<AIModel>,
  readOnly: Boolean,
  onDismiss: () -> Unit,
  onAddModel: () -> Unit,
  onEditModel: (AIModel) -> Unit
) {
  val connectionTests by viewModel.connectionTests.collectAsState()
  val testState = connectionTests[provider.id]
  val catalogs by viewModel.modelCatalogs.collectAsState()
  val defaultTaskModelId by viewModel.defaultTaskModelId.collectAsState()

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
    title = {
      Column {
        Text(provider.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(provider.protocol.displayName, color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp)
      }
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        DetailField("Base URL", provider.baseUrl)
        if (!readOnly) ApiKeyField(viewModel = viewModel, provider = provider)
        if (testState != null) {
          when (testState) {
            is WorkspaceRepository.ConnectionTestState.Testing -> StatusLine("Testing connection…", AwakiTheme.extra.warning)
            is WorkspaceRepository.ConnectionTestState.Connected -> StatusLine(testState.note, AwakiTheme.extra.success)
            is WorkspaceRepository.ConnectionTestState.Failed -> StatusLine(testState.message, MaterialTheme.colorScheme.error)
          }
        }

        Text("${models.size} Model${if (models.size == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
          "Tap the star to set the default model used for background tasks (commit messages, session titles).",
          color = AwakiTheme.extra.textMuted,
          fontSize = 9.sp,
          lineHeight = 12.sp
        )
        if (models.isEmpty()) {
          Text(
            if (readOnly) {
              "No model is installed on this device yet."
            } else {
              "No models yet — the agent cannot use this provider until a model is added."
            },
            color = AwakiTheme.extra.textMuted,
            fontSize = 11.sp
          )
        }
        models.forEach { model ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(MaterialTheme.colorScheme.background)
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
              .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(model.displayName, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
              Text(model.modelId, color = AwakiTheme.extra.textMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
              val isDefault = model.id == defaultTaskModelId
              Icon(
                if (isDefault) Icons.Default.Star else Icons.Outlined.StarOutline,
                contentDescription = if (isDefault) "Default model (tap to unset)" else "Set as default model",
                tint = if (isDefault) AwakiTheme.extra.warning else AwakiTheme.extra.textMuted,
                modifier = Modifier
                  .size(16.dp)
                  .clickable {
                    viewModel.setDefaultTaskModel(if (isDefault) null else model.id)
                  }
                  .testTag("btn_default_model_${model.modelId}")
              )
              Spacer(modifier = Modifier.width(10.dp))
              if (!readOnly) {
                Icon(
                  Icons.Default.Edit, contentDescription = "Edit model",
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier
                    .size(16.dp)
                    .clickable { onEditModel(model) }
                    .testTag("btn_edit_model_${model.modelId}")
                )
                Spacer(modifier = Modifier.width(10.dp))
                Icon(
                  Icons.Default.Delete, contentDescription = "Delete model",
                  tint = MaterialTheme.colorScheme.error,
                  modifier = Modifier
                    .size(16.dp)
                    .clickable { viewModel.deleteModel(model.id) }
                )
              }
            }
          }
        }

        // Discovery is optional and only ever costs a free request: nothing here
        // stops a provider or model from being configured by hand.
        when (val catalogState = catalogs[provider.id]) {
          is WorkspaceRepository.ModelCatalogState.Loading ->
            StatusLine("Asking the provider for its models…", AwakiTheme.extra.warning)
          is WorkspaceRepository.ModelCatalogState.Available ->
            StatusLine("${catalogState.models.size} models listed — typing a Model ID offers them.", AwakiTheme.extra.success)
          is WorkspaceRepository.ModelCatalogState.Failed ->
            StatusLine("Auto-discovery failed: ${catalogState.message} Add models manually.", AwakiTheme.extra.textMuted)
          null -> Unit
        }

        if (!readOnly) {
          Surface(
            onClick = onAddModel,
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().testTag("btn_add_model")
          ) {
            Row(
              modifier = Modifier.padding(vertical = 8.dp),
              horizontalArrangement = Arrangement.Center,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Add Model", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
          }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          MiniAction("Test Connection") { viewModel.testProviderConnection(provider.id) }
          if (!readOnly) {
            MiniAction(
              "Fetch Models",
              modifier = Modifier.testTag("btn_fetch_models"),
              onClick = { viewModel.loadModelCatalog(provider.id, force = true) }
            )
          }
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
    Text(label, color = AwakiTheme.extra.textMuted, fontSize = 9.sp)
    Text(value, color = AwakiTheme.extra.textCode, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
  }
}

/**
 * The provider's key: masked by default, revealed by the eye toggle.
 *
 * The plaintext is fetched from credential storage only when the user asks to
 * see it (and re-fetched every time the dialog re-opens), so the secret is not
 * held in ordinary UI state or recomposed into memory when nobody is looking.
 */
@Composable
private fun ApiKeyField(viewModel: WorkspaceViewModel, provider: AIProvider) {
  var keyVisible by remember { mutableStateOf(false) }
  // Loaded lazily on first reveal: reading it eagerly would put the plaintext in
  // memory even while it stays masked on screen.
  var revealedKey by remember { mutableStateOf<String?>(null) }

  Column {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("API Key", color = AwakiTheme.extra.textMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))
      Icon(
        imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
        contentDescription = if (keyVisible) "Hide API key" else "Show API key",
        tint = if (provider.hasApiKey) MaterialTheme.colorScheme.onSurfaceVariant else AwakiTheme.extra.textMuted,
        modifier = Modifier
          .size(14.dp)
          .clickable(enabled = provider.hasApiKey) {
            keyVisible = !keyVisible
            if (keyVisible && revealedKey == null) {
              revealedKey = viewModel.getApiKey(provider.id)
            }
          }
          .testTag("btn_toggle_key_detail")
      )
    }
    if (!provider.hasApiKey) {
      Text("not set", color = AwakiTheme.extra.textMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    } else if (keyVisible) {
      Text(
        revealedKey ?: "",
        color = AwakiTheme.extra.textCode,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.testTag("txt_api_key_revealed")
      )
    } else {
      Text("••••••••••••", color = AwakiTheme.extra.textCode, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
  }
}

@Composable
private fun StatusLine(text: String, color: Color) {
  Text(text, color = color, fontSize = 10.sp)
}

/**
 * The part of the model form a provider's own listing can answer: the id, the
 * name and the two limits, plus the two capabilities a listing states. The rest
 * of the form stays the user's call.
 */
internal data class ModelFormFields(
  val modelId: String,
  val displayName: String,
  val contextWindow: String,
  val maxOutputTokens: String,
  val tools: Boolean,
  val images: Boolean
) {
  /**
   * Takes what [m] states and keeps what it does not — a listing that omits a
   * ceiling must not blank out a number the user already typed.
   */
  fun filledFrom(m: CatalogModel): ModelFormFields = copy(
    modelId = m.modelId,
    displayName = m.displayName.ifBlank { displayName },
    contextWindow = m.contextWindow?.toString() ?: contextWindow,
    maxOutputTokens = m.maxOutputTokens?.toString() ?: maxOutputTokens,
    tools = m.tools ?: tools,
    images = m.images ?: images
  )
}

/**
 * Entries the partial id could mean. Case-insensitive on both the wire id and
 * the friendly name, because a user types whichever one they remember, and
 * capped so a provider listing hundreds of models cannot fill the screen.
 */
internal fun catalogSuggestions(catalog: List<CatalogModel>, query: String): List<CatalogModel> =
  if (query.isBlank()) emptyList()
  else catalog
    .filter { it.modelId.contains(query, ignoreCase = true) || it.displayName.contains(query, ignoreCase = true) }
    .sortedBy { it.modelId }
    .take(8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelFormDialog(
  provider: AIProvider?,
  existing: AIModel?,
  catalog: List<CatalogModel>,
  onDismiss: () -> Unit,
  onSave: (providerId: String, modelId: String, displayName: String, contextWindow: Int?, maxOutputTokens: Int?, ModelCapabilities, ReasoningConfig?) -> Unit
) {
  var fields by remember {
    mutableStateOf(
      ModelFormFields(
        modelId = existing?.modelId ?: "",
        displayName = existing?.displayName ?: "",
        contextWindow = existing?.contextWindow?.toString() ?: "",
        maxOutputTokens = existing?.maxOutputTokens?.toString() ?: "",
        tools = existing?.capabilities?.tools ?: true,
        images = existing?.capabilities?.images ?: false
      )
    )
  }
  var streaming by remember { mutableStateOf(existing?.capabilities?.streaming ?: true) }
  var promptCaching by remember { mutableStateOf(existing?.capabilities?.promptCaching ?: false) }
  var interleaved by remember { mutableStateOf(existing?.capabilities?.interleavedReasoning ?: false) }
  var maxTokensParam by remember { mutableStateOf(existing?.capabilities?.maxTokensParameter ?: true) }
  var reasoningEnabled by remember { mutableStateOf(existing?.reasoning?.enabled ?: false) }
  var reasoningEffort by remember { mutableStateOf(existing?.reasoning?.effort ?: "medium") }

  val valid = provider != null && fields.modelId.isNotBlank() && fields.displayName.isNotBlank()

  // What the provider itself lists, filtered as the user types. Picking an entry
  // fills in the limits and capabilities that model really has, so nothing has to
  // be looked up by hand — but every field stays editable and a model missing
  // from the listing can still be typed in.
  var showMatches by remember { mutableStateOf(false) }
  val matches = catalogSuggestions(catalog, fields.modelId)

  fun pickFromCatalog(m: CatalogModel) {
    fields = fields.filledFrom(m)
    showMatches = false
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
    title = {
      Column {
        Text(
          if (existing == null) "Add Model" else "Edit Model",
          color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold
        )
        provider?.let { Text("Provider: ${it.name}", color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp) }
      }
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        OutlinedTextField(
          value = fields.modelId,
          onValueChange = { fields = fields.copy(modelId = it); showMatches = catalog.isNotEmpty() },
          label = { Text("Model ID", fontSize = 11.sp) },
          supportingText = {
            Text(
              if (catalog.isEmpty()) "Wire identifier, e.g. openai/gpt-oss-120b"
              else "Typing suggests from the ${catalog.size} models this provider lists.",
              fontSize = 9.sp, fontFamily = FontFamily.Monospace
            )
          },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("input_model_id")
        )
        if (showMatches && matches.isNotEmpty()) {
          CatalogSuggestions(
            matches = matches,
            onPick = { pickFromCatalog(it) },
            onDismiss = { showMatches = false }
          )
        }
        OutlinedTextField(
          value = fields.displayName,
          onValueChange = { fields = fields.copy(displayName = it) },
          label = { Text("Display Name", fontSize = 11.sp) },
          singleLine = true, modifier = Modifier.fillMaxWidth().testTag("input_model_name")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = fields.contextWindow,
            onValueChange = { fields = fields.copy(contextWindow = it.filter { c -> c.isDigit() }) },
            label = { Text("Context (tokens)", fontSize = 11.sp) },
            singleLine = true, modifier = Modifier.weight(1f)
          )
          OutlinedTextField(
            value = fields.maxOutputTokens,
            onValueChange = { fields = fields.copy(maxOutputTokens = it.filter { c -> c.isDigit() }) },
            label = { Text("Max output", fontSize = 11.sp) },
            singleLine = true, modifier = Modifier.weight(1f)
          )
        }

        Text("Capabilities", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        CapabilityRow("Tool calling", fields.tools) { fields = fields.copy(tools = it) }
        CapabilityRow("Streaming", streaming) { streaming = it }
        CapabilityRow("Images", fields.images) { fields = fields.copy(images = it) }
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
                color = if (reasoningEffort == effort) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainer,
                border = androidx.compose.foundation.BorderStroke(1.dp, if (reasoningEffort == effort) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
              ) {
                Text(
                  effort, color = if (reasoningEffort == effort) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
            fields.modelId,
            fields.displayName,
            fields.contextWindow.takeIf { it.isNotBlank() }?.toIntOrNull(),
            fields.maxOutputTokens.takeIf { it.isNotBlank() }?.toIntOrNull(),
            ModelCapabilities(
              tools = fields.tools, images = fields.images, streaming = streaming,
              promptCaching = promptCaching, interleavedReasoning = interleaved,
              maxTokensParameter = maxTokensParam
            ),
            ReasoningConfig(enabled = reasoningEnabled, effort = reasoningEffort).takeIf { reasoningEnabled }
          )
        },
        enabled = valid,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        modifier = Modifier.testTag("btn_save_model")
      ) { Text("Save", fontSize = 12.sp) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel", color = AwakiTheme.extra.textMuted, fontSize = 12.sp) }
    }
  )
}

/**
 * The provider's own listing, offered as rows under the Model ID field. Not a
 * Material dropdown: ExposedDropdownMenuBox asks its anchor for focus as it
 * opens, and inside this dialog that anchor is never initialised, so typing a
 * model id crashed the app. Rows in the form's own column scroll with it.
 */
@Composable
internal fun CatalogSuggestions(
  matches: List<CatalogModel>,
  onPick: (CatalogModel) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, top = 2.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text("From this provider", color = AwakiTheme.extra.textMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))
      IconButton(
        onClick = onDismiss,
        modifier = Modifier.size(22.dp).testTag("btn_suggestions_dismiss")
      ) {
        Icon(Icons.Default.Close, contentDescription = "Hide suggestions", tint = AwakiTheme.extra.textMuted, modifier = Modifier.size(12.dp))
      }
    }
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = 176.dp)
        .verticalScroll(rememberScrollState())
        .testTag("catalog_suggestions")
    ) {
      matches.forEach { m ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(m) }
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag("catalog_suggestion"),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            m.modelId, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
          )
          Text(catalogLimitsLabel(m), color = AwakiTheme.extra.textMuted, fontSize = 9.sp, maxLines = 1)
        }
      }
    }
  }
}

/** The size a catalog entry claims, in the shorthand the rest of the UI uses. */
private fun catalogLimitsLabel(m: CatalogModel): String = listOfNotNull(
  m.contextWindow?.let { "${it / 1000}k window" },
  m.maxOutputTokens?.let { "${it / 1000}k out" },
  if (m.tools == true) "tools" else null
).joinToString(" · ")

@Composable
private fun CapabilityRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onChange(!checked) },
    verticalAlignment = Alignment.CenterVertically
  ) {
    Checkbox(checked = checked, onCheckedChange = onChange, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
    Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
  }
}
