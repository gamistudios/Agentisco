package com.awaki.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awaki.agent.model.PermissionMode
import com.awaki.background.RequirementAction
import com.awaki.background.RequirementStatus
import com.awaki.core.model.AppDestination
import com.awaki.data.repository.UpdateRepository
import com.awaki.editor.syntax.SyntaxTheme
import com.awaki.ui.WorkspaceViewModel
import com.awaki.ui.UpdateViewModel
import com.awaki.ui.components.AgentTeamSection
import com.awaki.ui.components.SkillSection
import com.awaki.ui.theme.AwakiTheme
import com.awaki.ui.theme.uiThemes

/**
 * The detail half of the compact settings screen: anything that is more than one
 * control lives here rather than inline, so a section stays a stack of 52dp rows.
 *
 * A sheet rather than a page because the setting it configures is one tap from the
 * list, and the user should come back to that list rather than unwind a stack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheetHost(
  sheet: SettingsSheet?,
  onDismiss: () -> Unit,
  viewModel: WorkspaceViewModel,
  updateViewModel: UpdateViewModel,
  onNavigate: (AppDestination) -> Unit
) {
  if (sheet == null) return
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = MaterialTheme.colorScheme.surface,
    tonalElevation = 8.dp,
    dragHandle = {
      Box(
        modifier = Modifier
          .padding(vertical = 10.dp)
          .width(36.dp)
          .height(4.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.outline)
      )
    }
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 14.dp)
        .padding(bottom = 28.dp)
    ) {
      when (sheet) {
        SettingsSheet.ModelPicker -> ModelPickerBody(viewModel, onDismiss, onNavigate)
        SettingsSheet.ToolPermissions -> ToolPermissionsBody(viewModel)
        SettingsSheet.FileEditing -> FileEditingBody(viewModel)
        SettingsSheet.TerminalSafety -> TerminalSafetyBody(viewModel)
        SettingsSheet.Compaction -> CompactionBody(viewModel)
        SettingsSheet.WebAccess -> WebAccessCard(viewModel)
        SettingsSheet.SkippedFolders -> ScanExclusionsBody(viewModel)
        SettingsSheet.BackgroundChecks -> BackgroundChecksBody(viewModel)
        SettingsSheet.AgentTeam -> AgentTeamBody(viewModel)
        SettingsSheet.Skills -> SkillsBody(viewModel)
        SettingsSheet.UiTheme -> UiThemeBody(viewModel)
        SettingsSheet.SyntaxTheme -> SyntaxThemeBody(viewModel)
        SettingsSheet.Updates -> UpdatesBody(updateViewModel)
        SettingsSheet.About -> AboutCard()
      }
    }
  }
}

@Composable
private fun SheetHeading(title: String, subtitle: String, tag: String? = null) {
  Column(modifier = Modifier.padding(bottom = 12.dp)) {
    Text(
      text = title,
      color = MaterialTheme.colorScheme.onSurface,
      fontSize = 16.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier)
    )
    if (subtitle.isNotEmpty()) {
      Spacer(modifier = Modifier.height(4.dp))
      Text(subtitle, color = AwakiTheme.extra.textMuted, fontSize = 11.sp, lineHeight = 15.sp)
    }
  }
}

@Composable
private fun SheetNote(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    color = AwakiTheme.extra.textMuted,
    fontSize = 11.5.sp,
    lineHeight = 15.sp,
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
      .padding(12.dp)
  )
}

// ---- AI model ----

@Composable
private fun ModelPickerBody(
  viewModel: WorkspaceViewModel,
  onPick: () -> Unit,
  onNavigate: (AppDestination) -> Unit
) {
  val models by viewModel.aiModels.collectAsState()
  val providers by viewModel.providers.collectAsState()
  val selected by viewModel.selectedModel.collectAsState()
  var query by remember { mutableStateOf("") }
  val providerNames = remember(providers) { providers.associate { it.id to it.name } }

  SheetHeading(
    title = "AI model",
    subtitle = "The model a new turn starts with. The composer can switch it mid-conversation, so a rate " +
      "limit does not end the work."
  )
  SettingsSearchField(
    query = query,
    onQueryChange = { query = it },
    placeholder = "Search models",
    tag = "input_model_search"
  )
  Spacer(modifier = Modifier.height(10.dp))

  if (models.isEmpty()) {
    SheetNote(
      "No models configured yet. Add a provider, or install a model to run one on this device.",
      modifier = Modifier.testTag("txt_no_models")
    )
  } else {
    val needle = query.trim().lowercase()
    val visible = models.filter {
      (it.displayName + " " + it.modelId + " " + (providerNames[it.providerId] ?: "")).lowercase()
        .contains(needle)
    }
    if (visible.isEmpty()) {
      SheetNote("No model matches \"$query\".", modifier = Modifier.testTag("txt_no_model_matches"))
    } else {
      SettingsRowGroup(
        visible.map { model ->
          SettingsItem(
            id = "pick_${model.id}",
            group = SettingsGroup.AiAgent,
            title = model.displayName,
            icon = Icons.Outlined.AutoAwesome,
            detail = buildString {
              append(providerNames[model.providerId] ?: "Provider")
              model.contextWindow?.let { append(" · ${it / 1000}k ctx") }
              if (model.providerId == com.awaki.local.LocalAiRuntime.PROVIDER_ID) append(" · on this device")
              else if (model.capabilities.tools) append(" · tools")
            },
            end = if (selected?.id == model.id) RowEnd.Check else RowEnd.Chevron,
            onClick = {
              viewModel.selectModel(model)
              onPick()
            }
          )
        }
      )
    }
  }

  Spacer(modifier = Modifier.height(10.dp))
  SettingsRowGroup(
    listOf(
      SettingsItem(
        id = "pick_add_provider",
        group = SettingsGroup.AiAgent,
        title = "Add a provider",
        icon = Icons.Outlined.Api,
        detail = "Connections, keys and the models each one offers are managed together.",
        onClick = { onNavigate(AppDestination.AI_PROVIDERS) }
      )
    )
  )
}

// ---- Tool permissions ----

/** The per-tool allow list. Each of these is enforced by the runtime before a tool body runs. */
@Composable
private fun ToolPermissionsBody(viewModel: WorkspaceViewModel) {
  val p by viewModel.permissions.collectAsState()
  fun row(id: String, title: String, detail: String, current: Boolean, update: (Boolean) -> Unit) = SettingsItem(
    id = "perm_$id",
    group = SettingsGroup.Tools,
    title = title,
    icon = Icons.AutoMirrored.Outlined.Rule,
    detail = detail,
    end = RowEnd.Switch(current, update, "switch_perm_$id")
  )

  SheetHeading(
    title = "Tool permissions",
    subtitle = "What the agent may use at all. A tool that is off here is never offered to the model, so it " +
      "cannot be talked into calling one."
  )
  SettingsRowGroup(
    listOf(
      row("read_files", "Read files", "Open and search what the project holds.", p.readFiles) {
        viewModel.updatePermissions { s -> s.copy(readFiles = it) }
      },
      row("create_files", "Create files", "Write a path that does not exist yet.", p.createFiles) {
        viewModel.updatePermissions { s -> s.copy(createFiles = it) }
      },
      row("modify_files", "Modify files", "Edit a file that already exists.", p.modifyFiles) {
        viewModel.updatePermissions { s -> s.copy(modifyFiles = it) }
      },
      row("delete_files", "Delete files", "Remove paths. Off by default: nothing else here is irreversible.", p.deleteFiles) {
        viewModel.updatePermissions { s -> s.copy(deleteFiles = it) }
      },
      row("run_commands", "Run commands", "Execute in the Linux shell.", p.runCommands) {
        viewModel.updatePermissions { s -> s.copy(runCommands = it) }
      },
      row("install_packages", "Install packages", "apt, pip and npm writes into the rootfs.", p.installPackages) {
        viewModel.updatePermissions { s -> s.copy(installPackages = it) }
      },
      row("network_commands", "Network commands", "Commands that reach outside the device.", p.networkCommands) {
        viewModel.updatePermissions { s -> s.copy(networkCommands = it) }
      },
      row("git_status", "Git status", "Read the working tree.", p.gitStatus) {
        viewModel.updatePermissions { s -> s.copy(gitStatus = it) }
      },
      row("git_diff", "Git diff", "Read the pending changes.", p.gitDiff) {
        viewModel.updatePermissions { s -> s.copy(gitDiff = it) }
      },
      row("git_commit", "Git commit", "Record changes in this repo.", p.gitCommit) {
        viewModel.updatePermissions { s -> s.copy(gitCommit = it) }
      },
      row("git_push", "Git push", "Send commits somewhere else. Off by default.", p.gitPush) {
        viewModel.updatePermissions { s -> s.copy(gitPush = it) }
      },
      row("ask_dangerous", "Ask before dangerous", "Pause for confirmation on anything on the risky list, " +
        "even when the mode would allow it.", p.alwaysAskDangerous) {
        viewModel.updatePermissions { s -> s.copy(alwaysAskDangerous = it) }
      }
    )
  )
}

private fun modeItems(
  selected: PermissionMode,
  choices: List<Triple<PermissionMode, String, String>>,
  onSelect: (PermissionMode) -> Unit
): List<SettingsItem> = choices.map { (mode, title, detail) ->
  SettingsItem(
    id = "mode_${mode.name.lowercase()}",
    group = SettingsGroup.Tools,
    title = title,
    icon = Icons.Outlined.GppGood,
    detail = detail,
    end = if (mode == selected) RowEnd.Check else RowEnd.Chevron,
    onClick = { onSelect(mode) }
  )
}

@Composable
private fun FileEditingBody(viewModel: WorkspaceViewModel) {
  val permissions by viewModel.permissions.collectAsState()
  SheetHeading(
    title = "File editing",
    subtitle = "How the agent may change files once it holds the tool for it."
  )
  SettingsRowGroup(
    modeItems(
      selected = permissions.fileEditing,
      choices = listOf(
        Triple(PermissionMode.ALWAYS_ASK, "Always ask before editing", "Every write waits for you."),
        Triple(
          PermissionMode.AUTO_APPROVE_PROJECT,
          "Auto-approve inside project workspace",
          "Writes inside the open project go through; anything outside still asks."
        ),
        Triple(
          PermissionMode.NEVER_ALLOW,
          "Never allow file modifications",
          "The agent can read and plan, and writes nothing."
        )
      ),
      onSelect = { mode -> viewModel.updatePermissions { it.copy(fileEditing = mode) } }
    )
  )
}

@Composable
private fun TerminalSafetyBody(viewModel: WorkspaceViewModel) {
  val permissions by viewModel.permissions.collectAsState()
  SheetHeading(
    title = "Terminal execution safety",
    subtitle = "The same question for commands, where the damage is not limited to the project."
  )
  SettingsRowGroup(
    modeItems(
      selected = permissions.terminalCommands,
      choices = listOf(
        Triple(PermissionMode.ALWAYS_ASK, "Always ask before running any command", "Nothing executes unattended."),
        Triple(
          PermissionMode.ALLOW_SAFE,
          "Allow safe commands",
          "ls, git status, npm test and the rest of the read-only set run on their own."
        ),
        Triple(
          PermissionMode.ALLOW_ALL,
          "Allow all commands",
          "Nothing is held back, including rm and a curl piped into a shell."
        )
      ),
      onSelect = { mode -> viewModel.updatePermissions { it.copy(terminalCommands = mode) } }
    )
  )
}

// ---- Context & compaction ----

@Composable
private fun CompactionBody(viewModel: WorkspaceViewModel) {
  val settings by viewModel.compactSettings.collectAsState()
  val usage by viewModel.contextUsage.collectAsState()
  val model by viewModel.selectedModel.collectAsState()
  val working by viewModel.isAgentWorking.collectAsState()

  SheetHeading(
    title = "Context & compaction",
    subtitle = "Long conversations are compressed before they overflow the model's window: old tool results " +
      "are cleared locally, then earlier turns become a summary the agent continues from. Nothing is " +
      "deleted — the chat above keeps every message and tool output."
  )

  ContextUsageBar(usage, model?.contextWindow)
  Spacer(modifier = Modifier.height(12.dp))

  SettingsRowGroup(
    listOf(
      SettingsItem(
        id = "autocompact",
        group = SettingsGroup.AiAgent,
        title = "Auto-compaction",
        icon = Icons.Outlined.Calculate,
        detail = if (settings.autoCompactEnabled) "Summarizes earlier turns before the window fills up"
        else "Off — the conversation grows until the provider rejects it",
        end = RowEnd.Switch(settings.autoCompactEnabled, viewModel::setAutoCompactEnabled, "switch_autocompact")
      ),
      SettingsItem(
        id = "microcompact",
        group = SettingsGroup.AiAgent,
        title = "Clear old tool results first",
        icon = Icons.Outlined.Compress,
        detail = "Cheap pass: drops the payloads of old read and command results, no extra model call",
        end = RowEnd.Switch(settings.microcompactEnabled, viewModel::setMicrocompactEnabled, "switch_microcompact")
      ),
      SettingsItem(
        id = "manual_compact",
        group = SettingsGroup.AiAgent,
        title = "Allow manual compaction",
        icon = Icons.Outlined.Compress,
        detail = "Lets you compress the conversation on demand from the composer",
        end = RowEnd.Switch(settings.manualCompactEnabled, viewModel::setManualCompactEnabled, "switch_manual_compact")
      )
    )
  )

  Spacer(modifier = Modifier.height(12.dp))
  SliderRow(
    label = "Compact threshold",
    value = "${settings.thresholdPercent}%",
    detail = "How full the window has to get before the summary runs. Lower compacts earlier and keeps more " +
      "headroom; 100% waits until the safety buffer is reached.",
    sliderValue = settings.thresholdPercent.toFloat(),
    range = 25f..150f,
    steps = 24,
    tag = "slider_compact_threshold",
    valueTag = "compact_threshold_value",
    onValueChange = { viewModel.setCompactThresholdPercent(it.toInt()) }
  )
  Spacer(modifier = Modifier.height(10.dp))
  SliderRow(
    label = "Keep recent turns",
    value = "${settings.keepRecentRounds} turn${if (settings.keepRecentRounds == 1) "" else "s"}",
    detail = "Assistant turns held verbatim after a summary, so the tool results the model is working with " +
      "right now are never summarized away.",
    sliderValue = settings.keepRecentRounds.toFloat(),
    range = 0f..8f,
    steps = 7,
    tag = "slider_keep_recent_rounds",
    valueTag = null,
    onValueChange = { viewModel.setCompactKeepRecentRounds(it.toInt()) }
  )

  Spacer(modifier = Modifier.height(12.dp))
  SheetAction(
    label = if (working) "Compaction waits for the current turn" else "Compact the conversation now",
    icon = Icons.Outlined.Compress,
    tag = "button_compact_now",
    enabled = settings.manualCompactEnabled && !working,
    onClick = { viewModel.compactNow() }
  )
}

@Composable
private fun ContextUsageBar(usage: com.awaki.agent.compact.ContextTokenUsage, contextWindow: Int?) {
  val tint = when {
    usage.isAboveThreshold -> MaterialTheme.colorScheme.error
    usage.pressurePercent >= 85 -> AwakiTheme.extra.warning
    usage.pressurePercent >= 60 -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = usage.label(),
        color = tint,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.testTag("context_usage_percent")
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = usage.detail() + (contextWindow?.let { " · model window $it" } ?: ""),
        color = AwakiTheme.extra.textMuted,
        fontSize = 10.sp,
        maxLines = 1
      )
    }
    Spacer(modifier = Modifier.height(6.dp))
    LinearProgressIndicator(
      progress = { (usage.percent / 100f).coerceIn(0f, 1f) },
      color = tint,
      trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
      modifier = Modifier
        .fillMaxWidth()
        .height(4.dp)
        .clip(RoundedCornerShape(2.dp))
    )
  }
}

@Composable
private fun SliderRow(
  label: String,
  value: String,
  detail: String,
  sliderValue: Float,
  range: ClosedFloatingPointRange<Float>,
  steps: Int,
  tag: String,
  valueTag: String?,
  onValueChange: (Float) -> Unit
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
      Text(
        text = value,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.then(if (valueTag != null) Modifier.testTag(valueTag) else Modifier)
      )
    }
    Text(detail, color = AwakiTheme.extra.textMuted, fontSize = 11.sp, lineHeight = 14.sp)
    Slider(
      value = sliderValue,
      onValueChange = onValueChange,
      valueRange = range,
      steps = steps,
      colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
      ),
      modifier = Modifier
        .fillMaxWidth()
        .testTag(tag)
    )
  }
}

@Composable
private fun SheetAction(
  label: String,
  icon: ImageVector,
  tag: String,
  enabled: Boolean,
  onClick: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(if (enabled) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface)
      .border(1.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 12.dp)
      .testTag(tag),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      icon,
      contentDescription = null,
      tint = if (enabled) MaterialTheme.colorScheme.primary else AwakiTheme.extra.textMuted,
      modifier = Modifier.size(15.dp)
    )
    Spacer(modifier = Modifier.width(9.dp))
    Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else AwakiTheme.extra.textMuted, fontSize = 12.5.sp)
  }
}

// ---- Battery & permissions ----

@Composable
private fun BackgroundChecksBody(viewModel: WorkspaceViewModel) {
  val context = LocalContext.current
  val requirements by viewModel.backgroundRequirements.collectAsState()
  val interrupted by viewModel.interruptedBackgroundWork.collectAsState()

  // The notification ask is a runtime permission, so the screen the user is looking
  // at has to raise it — BackgroundPermissions cannot.
  val requestNotifications = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) viewModel.dismissNotificationsPrompt() else viewModel.markNotificationsAsked()
  }

  SheetHeading(
    title = "Battery & permissions",
    subtitle = "Four things only you can grant. Awaki cannot switch any of them from inside itself, so each " +
      "row opens the system screen that can."
  )
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .background(MaterialTheme.colorScheme.surface)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
      .padding(horizontal = 12.dp)
  ) {
    requirements.forEachIndexed { index, requirement ->
      RequirementRow(
        requirement = requirement,
        onAction = { action ->
          if (action == RequirementAction.REQUEST_NOTIFICATIONS) {
            requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
          } else {
            viewModel.performBackgroundAction(context, action)
          }
        }
      )
      if (index < requirements.lastIndex) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
      }
    }
  }

  if (interrupted.isNotEmpty()) {
    Spacer(modifier = Modifier.height(12.dp))
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .background(AwakiTheme.extra.warningContainer.copy(alpha = 0.45f))
        .padding(12.dp)
        .testTag("card_interrupted_work")
    ) {
      Text(
        "Interrupted while Awaki was closed",
        color = AwakiTheme.extra.warning,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold
      )
      interrupted.forEach { entry ->
        Text(entry.label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
      }
      Text(
        "Nothing was restarted automatically — resuming spends your data and your API quota.",
        color = AwakiTheme.extra.textMuted,
        fontSize = 11.sp,
        lineHeight = 14.sp
      )
      Text(
        "Dismiss",
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
          .padding(top = 8.dp)
          .clickable { viewModel.acknowledgeInterruptedWork() }
          .testTag("btn_dismiss_interrupted")
      )
    }
  }
}

@Composable
private fun RequirementRow(
  requirement: com.awaki.background.BackgroundRequirement,
  onAction: (RequirementAction) -> Unit
) {
  val statusColor = when {
    requirement.status == RequirementStatus.GRANTED -> AwakiTheme.extra.success
    requirement.status == RequirementStatus.ACTION_REQUIRED && requirement.blocking -> MaterialTheme.colorScheme.error
    requirement.status == RequirementStatus.ACTION_REQUIRED -> AwakiTheme.extra.warning
    else -> AwakiTheme.extra.textMuted
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 10.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(requirement.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          when (requirement.status) {
            RequirementStatus.GRANTED -> "OK"
            RequirementStatus.ACTION_REQUIRED -> "Needs action"
            RequirementStatus.NOT_SUPPORTED -> "Not needed"
            RequirementStatus.DISABLED_BY_USER -> "Off"
          },
          color = statusColor,
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold
        )
      }
      Text(requirement.description, color = AwakiTheme.extra.textMuted, fontSize = 11.sp, lineHeight = 14.sp)
    }
    if (requirement.action != RequirementAction.NONE) {
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        if (requirement.status == RequirementStatus.GRANTED) "Settings" else "Fix",
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
          .clip(RoundedCornerShape(6.dp))
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
          .background(MaterialTheme.colorScheme.surfaceContainer)
          .padding(horizontal = 8.dp, vertical = 4.dp)
          .clickable { onAction(requirement.action) }
          .testTag("btn_requirement_${requirement.key.name.lowercase()}")
      )
    }
  }
}

// ---- UI theme ----

/**
 * Internal rather than private so the gallery is testable: a bottom sheet cannot be
 * driven from a Robolectric compose test, so the body is rendered on its own instead.
 */
@Composable
internal fun UiThemeBody(viewModel: WorkspaceViewModel) {
  val current by viewModel.uiTheme.collectAsState()
  SheetHeading(
    title = "Theme",
    subtitle = "The colours the whole app wears: chat, files, editor chrome, terminal, settings, dialogs and " +
      "sheets. How the code inside the editor is painted is the separate syntax choice, on the row below.",
    tag = "txt_ui_theme_sheet"
  )
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .background(MaterialTheme.colorScheme.surface)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
  ) {
    uiThemes.forEachIndexed { index, theme ->
      val selected = theme.key == current.key
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 52.dp)
          .clickable { viewModel.setUiTheme(theme.key) }
          .testTag("row_ui_theme_${theme.key}")
          .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              theme.name,
              color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
              fontSize = 13.5.sp,
              fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(theme.blurb, color = AwakiTheme.extra.textMuted, fontSize = 10.5.sp)
          }
          Spacer(modifier = Modifier.height(5.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
              theme.background,
              theme.surface,
              theme.surfaceContainer,
              theme.primary,
              theme.secondary,
              theme.tertiary
            ).forEach {
              Box(
                modifier = Modifier
                  .size(width = 18.dp, height = 8.dp)
                  .clip(RoundedCornerShape(2.dp))
                  .background(it)
              )
            }
          }
        }
        if (selected) Text("In use", color = AwakiTheme.extra.textMuted, fontSize = 11.sp)
      }
      if (index < uiThemes.lastIndex) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
      }
    }
  }
}

// ---- Syntax theme ----

@Composable
private fun SyntaxThemeBody(viewModel: WorkspaceViewModel) {
  val settings by viewModel.editorSettings.collectAsState()
  SheetHeading(
    title = "Syntax theme",
    subtitle = "How code is painted in the editor. Independent of the app theme above: any syntax palette can " +
      "sit inside any theme."
  )
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .background(MaterialTheme.colorScheme.surface)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
  ) {
    SyntaxTheme.allThemes.forEachIndexed { index, theme ->
      val selected = theme.name == settings.syntaxThemeName
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 52.dp)
          .clickable { viewModel.updateEditorSettings(settings.copy(syntaxThemeName = theme.name)) }
          .testTag("row_theme_${theme.name.lowercase().replace(" ", "_")}")
          .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            theme.name,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontSize = 13.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
          )
          Spacer(modifier = Modifier.height(5.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(theme.keyword, theme.function, theme.string, theme.number, theme.comment, theme.text).forEach {
              Box(
                modifier = Modifier
                  .size(width = 18.dp, height = 8.dp)
                  .clip(RoundedCornerShape(2.dp))
                  .background(it)
              )
            }
          }
        }
        if (selected) Text("In use", color = AwakiTheme.extra.textMuted, fontSize = 11.sp)
      }
      if (index < SyntaxTheme.allThemes.lastIndex) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
      }
    }
  }
}

// ---- App updates ----

@Composable
private fun UpdatesBody(updateViewModel: UpdateViewModel) {
  val state by updateViewModel.uiState.collectAsState()
  val context = LocalContext.current
  val versionName = remember(context) {
    runCatching {
      context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
  }

  SheetHeading(
    title = "App updates",
    subtitle = "Awaki checks its own release feed and installs the APK it downloads — no store account, and " +
      "no update you did not agree to."
  )
  SettingsRowGroup(
    listOf(
      SettingsItem(
        id = "auto_update",
        group = SettingsGroup.Advanced,
        title = "Check automatically",
        icon = Icons.Outlined.Update,
        detail = "On launch, and once a day after that",
        end = RowEnd.Switch(state.autoUpdateEnabled, updateViewModel::setAutoUpdateEnabled, "switch_auto_update")
      ),
      SettingsItem(
        id = "check_now",
        group = SettingsGroup.Advanced,
        title = if (state.updateState == UpdateRepository.UpdateState.CHECKING) "Checking…" else "Check now",
        icon = Icons.Outlined.Refresh,
        detail = "Last checked: ${state.lastCheckText}",
        onClick = { updateViewModel.checkForUpdates() }
      ),
      SettingsItem(
        id = "current_version",
        group = SettingsGroup.Advanced,
        title = "Installed version",
        icon = Icons.Outlined.Update,
        end = RowEnd.Value("v$versionName", ValueTone.Neutral, "txt_current_version")
      )
    )
  )

  if (state.availableUpdate != null) {
    Spacer(modifier = Modifier.height(10.dp))
    SettingsRowGroup(
      listOf(
        SettingsItem(
          id = "install_update",
          group = SettingsGroup.Advanced,
          title = "Update to v${state.availableUpdate!!.versionName}",
          icon = Icons.Outlined.Update,
          detail = "Download it, then open the installer.",
          end = RowEnd.Value("Ready", ValueTone.Accent, "txt_update_available"),
          onClick = { updateViewModel.showDialog() }
        )
      )
    )
  }

  if (state.hasUpdateFile) {
    Spacer(modifier = Modifier.height(10.dp))
    SettingsRowGroup(
      listOf(
        SettingsItem(
          id = "delete_update_file",
          group = SettingsGroup.Advanced,
          title = "Delete the downloaded APK",
          icon = Icons.Outlined.Delete,
          detail = "Frees the space and forces a clean re-download next time.",
          onClick = { updateViewModel.deleteDownloadedFile() }
        )
      )
    )
  }

  if (state.updateState != UpdateRepository.UpdateState.IDLE) {
    Spacer(modifier = Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (state.updateState == UpdateRepository.UpdateState.CHECKING ||
        state.updateState == UpdateRepository.UpdateState.DOWNLOADING
      ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(8.dp))
      }
      Text(
        text = when (state.updateState) {
          UpdateRepository.UpdateState.CHECKING -> "Checking for updates…"
          UpdateRepository.UpdateState.DOWNLOADING -> "Downloading update…"
          UpdateRepository.UpdateState.DOWNLOADED -> "Update ready to install"
          UpdateRepository.UpdateState.ERROR -> "Update failed — check now to retry"
          UpdateRepository.UpdateState.AVAILABLE -> "An update is available"
          else -> ""
        },
        color = if (state.updateState == UpdateRepository.UpdateState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.5.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.testTag("txt_update_state")
      )
    }
  }
}

// ---- Team and skills, whose lists keep their own editors ----

@Composable
private fun AgentTeamBody(viewModel: WorkspaceViewModel) {
  SheetHeading(
    title = "Agent team",
    subtitle = "Work that needs several kinds of attention can be handed to a specialist instead of done all " +
      "at once. Each gets its own brief, tools and slice of the project, and reports back."
  )
  AgentTeamSection(viewModel)
}

@Composable
private fun SkillsBody(viewModel: WorkspaceViewModel) {
  SheetHeading(
    title = "Skills",
    subtitle = "A skill is a short document you write once — how this project tests, ships or names things. " +
      "The agent reads the whole thing before doing that kind of work."
  )
  SkillSection(viewModel)
}
