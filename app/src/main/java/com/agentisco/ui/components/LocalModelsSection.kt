package com.agentisco.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentisco.data.repository.LocalModelInstallState
import com.agentisco.local.LocalAiRuntime
import com.agentisco.local.model.GgufMetadata
import com.agentisco.local.model.LocalGenerationSettings
import com.agentisco.local.model.LocalModel
import com.agentisco.local.model.LocalModelConfiguration
import com.agentisco.local.model.LocalModelInstallStatus
import com.agentisco.local.model.LocalModelProgress
import com.agentisco.local.model.LocalRuntimeSettings
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.theme.*

/**
 * Management surface for models that run on this device.
 *
 * Lives on [com.agentisco.ui.screens.LocalModelsScreen] because it manages the same thing
 * the cloud providers do — the models the agent can be pointed at — with files instead of
 * API keys. A model becomes selectable only once its bytes are on disk and verified, so
 * what this row shows is read from the install, never remembered from a previous launch.
 */
@Composable
fun LocalModelsSection(
  viewModel: WorkspaceViewModel,
  modifier: Modifier = Modifier
) {
  val models by viewModel.localModels.collectAsState()
  val states by viewModel.localInstallStates.collectAsState()
  val selectedId by viewModel.selectedModel.collectAsState()

  var addOpen by remember { mutableStateOf(false) }
  var settingsFor by remember { mutableStateOf<LocalModel?>(null) }
  var infoFor by remember { mutableStateOf<LocalModel?>(null) }
  var confirmDelete by remember { mutableStateOf<LocalModel?>(null) }
  var importError by remember { mutableStateOf<String?>(null) }

  // Every mime type, because a .gguf has no registered one and file managers answer
  // with anything from octet-stream to a bare `*/*`. What the file actually is, the
  // repository decides from its header.
  val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    importError = null
    viewModel.importLocalModel(uri) { result ->
      result.onFailure { importError = it.message ?: "That file could not be imported" }
    }
  }

  val installedCount = models.count { it.installed }
  val bytesOnDisk = models.filter { it.installed }.sumOf { it.sizeBytes }

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("On-device models", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
          text = if (models.isEmpty()) {
            "None available to add"
          } else {
            "$installedCount of ${models.size} installed · ${formatModelBytes(bytesOnDisk)} on disk"
          },
          color = TextMuted,
          fontSize = 11.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
      Spacer(modifier = Modifier.width(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
          onClick = { importPicker.launch(arrayOf("*/*")) },
          shape = RoundedCornerShape(8.dp),
          color = DarkSurfaceElevated,
          border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
          modifier = Modifier.testTag("btn_import_local_model")
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              Icons.Outlined.FolderOpen,
              contentDescription = null,
              tint = TextSecondary,
              modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Import", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
          }
        }
        Surface(
          onClick = { addOpen = true },
          shape = RoundedCornerShape(8.dp),
          color = ElectricBlue.copy(alpha = 0.15f),
          border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue),
          modifier = Modifier.testTag("btn_add_local_model")
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add model", color = ElectricBlueGlow, fontSize = 11.sp, fontWeight = FontWeight.Medium)
          }
        }
      }
    }

    importError?.let {
      Spacer(modifier = Modifier.height(6.dp))
      Text(it, color = DangerRed.copy(alpha = 0.9f), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }

    Spacer(modifier = Modifier.height(10.dp))

    models.forEach { model ->
      LocalModelCard(
        model = model,
        state = states[model.id],
        selected = selectedId?.id == LocalAiRuntime.recordId(model.id),
        onInstall = { viewModel.installLocalModel(model.id) },
        onCancel = { viewModel.cancelLocalInstall(model.id) },
        onSettings = { settingsFor = model },
        onInfo = { infoFor = model },
        onUse = { viewModel.localModelSelectable(model.id)?.let(viewModel::selectModel) },
        onRedownload = { viewModel.redownloadLocalModel(model.id) },
        onDelete = { confirmDelete = model },
        onForget = { viewModel.forgetLocalModel(model.id) }
      )
      Spacer(modifier = Modifier.height(10.dp))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      MiniAction("Check for updates") { viewModel.refreshLocalModels() }
      Text(
        viewModel.localAiNote ?: when (installedCount) {
          0 -> "Nothing installed to run"
          1 -> "1 model ready to run on this device"
          else -> "$installedCount models ready to run on this device"
        },
        color = TextMuted,
        fontSize = 10.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
    }
  }

  if (addOpen) {
    AddLocalModelDialog(
      onDismiss = { addOpen = false },
      onAdd = { name, url, quantization, description, report ->
        viewModel.addLocalModel(name, url, description, quantization) { result ->
          result
            .onSuccess { addOpen = false }
            .onFailure { report(it.message ?: "That model could not be added") }
        }
      }
    )
  }

  settingsFor?.let { model ->
    LocalModelSettingsDialog(
      model = model,
      contextLimit = viewModel.localModelMetadata(model.id)?.contextLength?.takeIf { it > 0L }?.toInt(),
      onDismiss = { settingsFor = null },
      onSave = { configuration ->
        viewModel.updateLocalConfiguration(model.id, configuration)
        settingsFor = null
      }
    )
  }

  infoFor?.let { model ->
    LocalModelInfoDialog(
      model = model,
      metadata = viewModel.localModelMetadata(model.id),
      state = states[model.id],
      onDismiss = { infoFor = null }
    )
  }

  confirmDelete?.let { model ->
    AlertDialog(
      onDismissRequest = { confirmDelete = null },
      containerColor = DarkSurface,
      title = { Text("Delete \"${model.name}\"?", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
      text = {
        Text(
          text = if (model.builtIn) {
            "Removes ${formatModelBytes(model.sizeBytes)} of model data from this device. The model stays in the list and can be downloaded again."
          } else {
            "Removes ${formatModelBytes(model.sizeBytes)} of model data and forgets this model. Add it again with its URL to reinstall."
          },
          color = TextSecondary,
          fontSize = 12.sp
        )
      },
      confirmButton = {
        Button(
          onClick = {
            if (model.installed) viewModel.uninstallLocalModel(model.id)
            if (!model.builtIn) viewModel.forgetLocalModel(model.id)
            confirmDelete = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
          modifier = Modifier.testTag("btn_confirm_local_delete")
        ) { Text("Delete", color = Color.White, fontSize = 12.sp) }
      },
      dismissButton = {
        TextButton(onClick = { confirmDelete = null }) { Text("Cancel", color = TextMuted, fontSize = 12.sp) }
      }
    )
  }
}

@Composable
internal fun LocalModelCard(
  model: LocalModel,
  state: LocalModelInstallState?,
  selected: Boolean,
  onInstall: () -> Unit,
  onCancel: () -> Unit,
  onSettings: () -> Unit,
  onInfo: () -> Unit,
  onUse: () -> Unit,
  onRedownload: () -> Unit,
  onDelete: () -> Unit,
  onForget: () -> Unit
) {
  val status = state?.status ?: if (model.installed) LocalModelInstallStatus.INSTALLED else LocalModelInstallStatus.NOT_INSTALLED
  val resumable = (state?.resumableBytes ?: 0L) > 0L && !model.installed

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(DarkSurface)
      .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
      .padding(12.dp)
      .testTag("local_model_${model.id}")
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
            .clip(CircleShape)
            .background(statusColor(status))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          model.name,
          color = TextPrimary,
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        if (model.quantization.isNotBlank()) {
          Spacer(modifier = Modifier.width(6.dp))
          QuantTag(model.quantization)
        }
      }
      Text(statusLabel(status, selected), color = statusColor(status), fontSize = 10.sp, maxLines = 1)
    }

    if (model.description.isNotBlank()) {
      Spacer(modifier = Modifier.height(3.dp))
      Text(
        model.description,
        color = TextSecondary,
        fontSize = 11.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
    }

    Spacer(modifier = Modifier.height(4.dp))
    Text(
      localModelSummary(model, resumable),
      color = TextMuted,
      fontSize = 10.sp,
      fontFamily = FontFamily.Monospace
    )

    state?.progress?.let { progress ->
      Spacer(modifier = Modifier.height(8.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .weight(1f)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(DarkSurfaceHighlight)
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth(progress.fraction)
              .height(3.dp)
              .clip(RoundedCornerShape(2.dp))
              .background(ElectricBlue)
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          transferLabel(progress),
          color = TextSecondary,
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace,
          maxLines = 1
        )
      }
    }

    state?.error?.let { error ->
      Spacer(modifier = Modifier.height(6.dp))
      Text(error, color = DangerRed.copy(alpha = 0.9f), fontSize = 10.sp, maxLines = 3)
    }

    Spacer(modifier = Modifier.height(8.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      when {
        state?.isBusy == true -> MiniAction(
          label = "Cancel",
          tint = DangerRed,
          modifier = Modifier.testTag("btn_local_cancel_${model.id}"),
          onClick = onCancel
        )
        // An imported model has nothing to fetch, nothing to update from and no second
        // copy to re-download: its bytes only ever arrive from the picker, so the row
        // says so by offering no such button.
        !model.isImported && (status == LocalModelInstallStatus.NOT_INSTALLED || status == LocalModelInstallStatus.FAILED) -> MiniAction(
          label = if (resumable) "Resume" else "Download",
          tint = ElectricBlueGlow,
          modifier = Modifier.testTag("btn_local_install_${model.id}"),
          onClick = onInstall
        )
        !model.isImported && status == LocalModelInstallStatus.UPDATE_AVAILABLE -> MiniAction(
          label = "Update",
          tint = ElectricBlueGlow,
          modifier = Modifier.testTag("btn_local_update_${model.id}"),
          onClick = onInstall
        )
        model.installed && !selected -> MiniAction(
          label = "Select",
          tint = ElectricBlueGlow,
          modifier = Modifier.testTag("btn_local_select_${model.id}"),
          onClick = onUse
        )
        model.installed && selected && !model.isImported -> MiniAction(
          label = "Re-download",
          modifier = Modifier.testTag("btn_local_redownload_${model.id}"),
          onClick = onRedownload
        )
      }
      if (model.installed) {
        MiniAction(
          label = "Settings",
          modifier = Modifier.testTag("btn_local_settings_${model.id}"),
          onClick = onSettings
        )
        MiniAction(
          label = "Info",
          modifier = Modifier.testTag("btn_local_info_${model.id}"),
          onClick = onInfo
        )
      }
      Spacer(modifier = Modifier.weight(1f))
      if (model.installed) {
        MiniAction(
          label = "Delete",
          tint = DangerRed,
          modifier = Modifier.testTag("btn_local_delete_${model.id}"),
          onClick = onDelete
        )
      } else if (!model.builtIn) {
        MiniAction(
          label = "Remove",
          modifier = Modifier.testTag("btn_local_remove_${model.id}"),
          onClick = onForget
        )
      }
    }
  }
}

/** Quantization tag: the number a user picks a model file by, so it sits next to the name. */
@Composable
private fun QuantTag(text: String) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(DarkSurfaceElevated)
      .padding(horizontal = 5.dp, vertical = 1.dp)
  ) {
    Text(text, color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
  }
}

private fun statusColor(status: LocalModelInstallStatus): Color = when (status) {
  LocalModelInstallStatus.INSTALLED -> TerminalGreen
  LocalModelInstallStatus.UPDATE_AVAILABLE -> ElectricBlue
  LocalModelInstallStatus.DOWNLOADING, LocalModelInstallStatus.IMPORTING,
  LocalModelInstallStatus.INSTALLING -> WarningAmber
  LocalModelInstallStatus.FAILED -> DangerRed
  LocalModelInstallStatus.NOT_INSTALLED -> TextMuted
}

private fun statusLabel(status: LocalModelInstallStatus, selected: Boolean): String = when (status) {
  LocalModelInstallStatus.INSTALLED -> if (selected) "Installed · in use" else "Installed"
  LocalModelInstallStatus.UPDATE_AVAILABLE -> "Update available"
  LocalModelInstallStatus.DOWNLOADING -> "Downloading"
  LocalModelInstallStatus.IMPORTING -> "Importing"
  LocalModelInstallStatus.INSTALLING -> "Verifying"
  LocalModelInstallStatus.FAILED -> "Failed"
  LocalModelInstallStatus.NOT_INSTALLED -> "Not installed"
}

/** One line of facts: what the file is, where it came from and how it will be loaded. */
private fun localModelSummary(model: LocalModel, resumable: Boolean): String {
  val parts = mutableListOf<String>()
  if (model.sizeBytes > 0L) parts.add(formatModelBytes(model.sizeBytes))
  model.version.takeIf { it.isNotBlank() }?.let { parts.add(it) }
  if (resumable) parts.add("part downloaded")
  if (model.installed) {
    parts.add("ctx ${model.configuration.runtime.contextSize}")
    parts.add("max ${model.configuration.generation.maxOutputTokens}")
  }
  parts.add(when {
    model.builtIn -> "built in"
    model.isImported -> "from this device"
    else -> "added by URL"
  })
  return parts.joinToString(" · ")
}

private fun transferLabel(progress: LocalModelProgress): String {
  // A file the provider will not size has no percentage to show; counting the bytes
  // that actually landed is the honest version of the same fact.
  if (progress.totalBytes <= 0L) return "${formatModelBytes(progress.bytesTransferred)} copied"
  val speed = if (progress.speedBytesPerSec > 0L) "${formatModelBytes(progress.speedBytesPerSec)}/s" else "—"
  val eta = progress.etaSeconds?.let { " · ${formatDuration(it)}" } ?: ""
  return "${progress.percent}% · $speed$eta"
}

/** Model files are measured in megabytes; one decimal is enough to tell two apart. */
internal fun formatModelBytes(bytes: Long): String = when {
  bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
  bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
  bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
  else -> "$bytes B"
}

internal fun formatDuration(seconds: Long): String = when {
  seconds < 60L -> "${seconds}s"
  seconds < 3600L -> "%d min %d s".format(seconds / 60, seconds % 60)
  else -> "%d h %d min".format(seconds / 3600, (seconds % 3600) / 60)
}

@Composable
private fun AddLocalModelDialog(
  onDismiss: () -> Unit,
  /** [report] puts a rejection back in the form, where the user can fix it. */
  onAdd: (name: String, url: String, quantization: String, description: String, report: (String) -> Unit) -> Unit
) {
  var name by remember { mutableStateOf("") }
  var url by remember { mutableStateOf("") }
  var quantization by remember { mutableStateOf("") }
  var description by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  var pending by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = { if (!pending) onDismiss() },
    containerColor = DarkSurface,
    title = { Text("Add a model by URL", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        OutlinedTextField(
          value = name, onValueChange = { name = it },
          label = { Text("Name", fontSize = 11.sp) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("input_local_name")
        )
        OutlinedTextField(
          value = url, onValueChange = { url = it },
          label = { Text("Download URL", fontSize = 11.sp) },
          supportingText = {
            Text(
              "HTTPS · a .gguf file · read and verified before it becomes selectable",
              fontSize = 9.sp,
              fontFamily = FontFamily.Monospace
            )
          },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("input_local_url")
        )
        OutlinedTextField(
          value = quantization, onValueChange = { quantization = it },
          label = { Text("Quantization tag (optional)", fontSize = 11.sp) },
          placeholder = { Text("Q4_K_M", fontSize = 11.sp) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("input_local_quant")
        )
        OutlinedTextField(
          value = description, onValueChange = { description = it },
          label = { Text("Description (optional)", fontSize = 11.sp) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("input_local_description")
        )
        Text(
          "The bytes come from wherever this URL points. Agentisco checks the GGUF header, the size and the digest the source publishes, and keeps nothing that does not verify.",
          color = TextMuted,
          fontSize = 9.sp
        )
        error?.let { Text(it, color = DangerRed, fontSize = 10.sp) }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          error = null
          pending = true
          onAdd(name, url, quantization, description) { message ->
            pending = false
            error = message
          }
        },
        enabled = name.isNotBlank() && url.isNotBlank() && !pending,
        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
        modifier = Modifier.testTag("btn_save_local_model")
      ) { Text(if (pending) "Checking…" else "Add", fontSize = 12.sp) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted, fontSize = 12.sp) }
    }
  )
}

@Composable
private fun LocalModelInfoDialog(
  model: LocalModel,
  metadata: GgufMetadata?,
  state: LocalModelInstallState?,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = DarkSurface,
    title = { Text(model.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        metadata?.let { meta ->
          InfoRow("Architecture", meta.architecture)
          InfoRow("Format", "GGUF v${meta.formatVersion}")
          InfoRow("Tensors", "${meta.tensorCount}")
          InfoRow("Metadata entries", "${meta.metadataCount}")
          GgufMetadata.fileTypeLabel(meta.fileType)?.let { InfoRow("Weight type", it) }
          meta.contextLength?.let { InfoRow("Trained context", "${it} tokens") }
          meta.publishedName?.let { InfoRow("Published as", it) }
        } ?: Text(
          if (model.installed) "The file could not be read — reinstall it." else "Not downloaded yet.",
          color = TextMuted,
          fontSize = 10.sp
        )
        InfoRow("Size", if (model.sizeBytes > 0L) formatModelBytes(model.sizeBytes) else "unknown")
        InfoRow("Source", if (model.isImported) "Copied from this device" else model.sourceUrl)
        if (model.version.isNotBlank()) InfoRow("Version", model.version)
        model.checksum?.let { InfoRow("SHA-256", it) }
        InfoRow("Status", statusLabel(state?.status ?: if (model.installed) LocalModelInstallStatus.INSTALLED else LocalModelInstallStatus.NOT_INSTALLED, false))
      }
    },
    confirmButton = {
      Button(
        onClick = onDismiss,
        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
        modifier = Modifier.testTag("btn_close_local_info")
      ) { Text("Close", fontSize = 12.sp) }
    }
  )
}

@Composable
private fun InfoRow(label: String, value: String) {
  Row(modifier = Modifier.fillMaxWidth()) {
    Text(label, color = TextMuted, fontSize = 10.sp, modifier = Modifier.width(110.dp))
    Text(
      value,
      color = TextSecondary,
      fontSize = 10.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.weight(1f)
    )
  }
}

@Composable
private fun LocalModelSettingsDialog(
  model: LocalModel,
  contextLimit: Int?,
  onDismiss: () -> Unit,
  onSave: (LocalModelConfiguration) -> Unit
) {
  var input by remember { mutableStateOf(LocalSettingsInput.of(model.configuration)) }
  val parsed = remember(input, contextLimit) { input.parse(contextLimit) }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = DarkSurface,
    title = { Text("${model.name} settings", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text("Runtime", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(
          "Reloading the model applies these.",
          color = TextMuted,
          fontSize = 9.sp
        )
        SettingsFieldRow(
          SettingsField("Context", input.context, { input = input.copy(context = it) }, "ctx",
            testTag = "input_local_context"),
          SettingsField("Threads", input.threads, { input = input.copy(threads = it) }, "0 = auto",
            testTag = "input_local_threads"),
          SettingsField("Batch", input.batch, { input = input.copy(batch = it) }, "size",
            testTag = "input_local_batch")
        )
        contextLimit?.let {
          Text("This model was trained for $it tokens.", color = TextMuted, fontSize = 9.sp)
        }

        Text("Generation", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        SettingsFieldRow(
          SettingsField("Max tokens", input.maxTokens, { input = input.copy(maxTokens = it) }, "out",
            testTag = "input_local_max_tokens"),
          SettingsField("Temperature", input.temperature, { input = input.copy(temperature = it) }, "0–2",
            testTag = "input_local_temperature"),
          SettingsField("Top-K", input.topK, { input = input.copy(topK = it) }, "k",
            testTag = "input_local_top_k")
        )
        SettingsFieldRow(
          SettingsField("Top-P", input.topP, { input = input.copy(topP = it) }, "1 = off",
            testTag = "input_local_top_p"),
          SettingsField("Repeat", input.repeatPenalty, { input = input.copy(repeatPenalty = it) }, "penalty",
            testTag = "input_local_repeat")
        )

        parsed.exceptionOrNull()?.message?.let { Text(it, color = DangerRed, fontSize = 10.sp) }
      }
    },
    confirmButton = {
      Column(horizontalAlignment = Alignment.End) {
        Button(
          onClick = { parsed.getOrNull()?.let(onSave) },
          enabled = parsed.isSuccess,
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
          modifier = Modifier.testTag("btn_save_local_settings")
        ) { Text("Save", fontSize = 12.sp) }
        if (!model.configuration.isDefault) {
          TextButton(
            onClick = { input = LocalSettingsInput.of(LocalModelConfiguration.Defaults) },
            modifier = Modifier.testTag("btn_reset_local_settings")
          ) { Text("Reset to defaults", color = TextMuted, fontSize = 11.sp) }
        }
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted, fontSize = 12.sp) }
    }
  )
}

/** One field of the settings form: a label, the text typed, and what it means. */
private class SettingsField(
  val label: String,
  val value: String,
  val onChange: (String) -> Unit,
  val hint: String,
  val testTag: String
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsFieldRow(
  first: SettingsField,
  second: SettingsField,
  third: SettingsField? = null
) {
  FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    listOfNotNull(first, second, third).forEach { field ->
      OutlinedTextField(
        value = field.value,
        onValueChange = field.onChange,
        label = { Text(field.label, fontSize = 10.sp) },
        placeholder = { Text(field.hint, fontSize = 10.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(110.dp).testTag(field.testTag)
      )
    }
  }
}

/**
 * The settings form as typed text.
 *
 * Every number is checked against what the engine can honour before it is saved, so a
 * typo cannot reach a model load. The context ceiling comes from the model file: a
 * longer window than the weights were trained for produces confident nonsense rather
 * than an error, which is worse than refusing the value.
 */
internal data class LocalSettingsInput(
  val context: String,
  val threads: String,
  val batch: String,
  val maxTokens: String,
  val temperature: String,
  val topK: String,
  val topP: String,
  val repeatPenalty: String,
  /**
   * Carried through untouched. The form has no widget for it — the tools a model is
   * offered are picked on its own page — but a settings save writes the whole
   * configuration, so a form that dropped this would unset that choice.
   */
  val allowedTools: Set<String>? = null
) {
  /** Reads the form, or names the field that cannot be applied to a model load. */
  fun parse(contextLimit: Int?): Result<LocalModelConfiguration> {
    val context = whole(context) ?: return wrong("Context", context)
    val threads = whole(threads) ?: return wrong("Threads", threads)
    val batch = whole(batch) ?: return wrong("Batch size", batch)
    val maxTokens = whole(maxTokens) ?: return wrong("Max tokens", maxTokens)
    val temperature = decimal(temperature) ?: return wrong("Temperature", temperature)
    val topK = whole(topK) ?: return wrong("Top-K", topK)
    val topP = decimal(topP) ?: return wrong("Top-P", topP)
    val penalty = decimal(repeatPenalty) ?: return wrong("Repeat penalty", repeatPenalty)

    val ceiling = contextLimit?.coerceAtLeast(MIN_CONTEXT) ?: MAX_CONTEXT
    if (context < MIN_CONTEXT) return Result.failure(IllegalArgumentException("Context must be at least $MIN_CONTEXT"))
    if (context > ceiling) {
      return Result.failure(
        if (contextLimit != null) {
          IllegalArgumentException("Context cannot exceed the $ceiling tokens this model was trained for")
        } else {
          IllegalArgumentException("Context cannot exceed $MAX_CONTEXT")
        }
      )
    }
    if (threads !in 0..32) return Result.failure(IllegalArgumentException("Threads must be 0 (auto) to 32"))
    if (batch !in 16..2048) return Result.failure(IllegalArgumentException("Batch size must be 16 to 2048"))
    if (maxTokens < 1) return Result.failure(IllegalArgumentException("Max tokens must be at least 1"))
    if (temperature !in 0.0..2.0) return Result.failure(IllegalArgumentException("Temperature must be 0 to 2"))
    if (topK !in 1..1000) return Result.failure(IllegalArgumentException("Top-K must be 1 to 1000"))
    if (topP <= 0.0 || topP > 1.0) return Result.failure(IllegalArgumentException("Top-P must be above 0 and at most 1"))
    if (penalty < 1.0 || penalty > 2.0) return Result.failure(IllegalArgumentException("Repeat penalty must be 1 to 2"))

    return Result.success(
      LocalModelConfiguration(
        runtime = LocalRuntimeSettings(contextSize = context, threadCount = threads, batchSize = batch),
        generation = LocalGenerationSettings(
          maxOutputTokens = maxTokens,
          temperature = temperature,
          topK = topK,
          topP = topP,
          repeatPenalty = penalty
        ),
        allowedTools = allowedTools
      )
    )
  }

  private fun whole(text: String): Int? = text.trim().toIntOrNull()

  private fun decimal(text: String): Double? = text.trim().toDoubleOrNull()

  private fun wrong(label: String, text: String): Result<LocalModelConfiguration> =
    Result.failure(IllegalArgumentException("$label must be a number${if (text.isBlank()) "" else " — \"$text\" is not"}"))

  companion object {
    const val MIN_CONTEXT = 128
    const val MAX_CONTEXT = 8192

    fun of(configuration: LocalModelConfiguration) = LocalSettingsInput(
      context = configuration.runtime.contextSize.toString(),
      threads = configuration.runtime.threadCount.toString(),
      batch = configuration.runtime.batchSize.toString(),
      maxTokens = configuration.generation.maxOutputTokens.toString(),
      temperature = configuration.generation.temperature.toString(),
      topK = configuration.generation.topK.toString(),
      topP = configuration.generation.topP.toString(),
      repeatPenalty = configuration.generation.repeatPenalty.toString(),
      allowedTools = configuration.allowedTools
    )
  }
}


