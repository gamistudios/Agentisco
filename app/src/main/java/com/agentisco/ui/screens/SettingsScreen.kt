package com.agentisco.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentisco.core.model.AppDestination
import com.agentisco.agent.model.PermissionMode
import com.agentisco.agent.model.UNLIMITED_ITERATIONS
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.theme.*

@Composable
fun SettingsScreen(
  viewModel: WorkspaceViewModel,
  updateViewModel: com.agentisco.ui.UpdateViewModel,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  val permissions by viewModel.permissions.collectAsState()
  val updateUiState by updateViewModel.uiState.collectAsState()
  val updateContext = LocalContext.current
  val currentVersionName = remember(updateContext) {
    updateContext.packageManager
      .getPackageInfo(updateContext.packageName, 0)
      .versionName ?: "?"
  }

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
          onClick = { onNavigate(AppDestination.AGENT) },
          modifier = Modifier.size(32.dp)
        ) {
          Icon(Icons.Default.ChevronLeft, contentDescription = "Back", tint = TextMuted)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Column {
          Text("Agent Permissions & Settings", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
          Text("Control autonomous agent tool boundaries", color = TextMuted, fontSize = 12.sp)
        }
      }
    }

    // App Updates Card
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Text("App Updates", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
          Spacer(modifier = Modifier.height(8.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text("Auto-Update", color = TextPrimary, fontSize = 13.sp)
              Text(
                "Automatically check for new versions",
                color = TextMuted,
                fontSize = 11.sp
              )
            }
            Switch(
              checked = updateUiState.autoUpdateEnabled,
              onCheckedChange = { updateViewModel.setAutoUpdateEnabled(it) },
              colors = SwitchDefaults.colors(
                checkedThumbColor = ElectricBlue,
                checkedTrackColor = ElectricBlue.copy(alpha = 0.35f),
                checkedBorderColor = ElectricBlue,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = DarkSurfaceHighlight,
                uncheckedBorderColor = DarkBorder
              )
            )
          }

          HorizontalDivider(
            color = DarkBorderSubtle,
            modifier = Modifier.padding(vertical = 10.dp)
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text("Current Version", color = TextPrimary, fontSize = 13.sp)
              Text(
                "v$currentVersionName",
                color = TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
              )
            }
            Text(
              "Last checked: ${updateUiState.lastCheckText}",
              color = TextMuted,
              fontSize = 11.sp
            )
          }

          if (updateUiState.availableUpdate != null) {
            HorizontalDivider(
              color = DarkBorderSubtle,
              modifier = Modifier.padding(vertical = 10.dp)
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "New version available: ${updateUiState.availableUpdate!!.versionName}",
                color = ElectricBlueGlow,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
              )
              Text(
                text = "Update Now",
                color = ElectricBlueGlow,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                  .clip(RoundedCornerShape(6.dp))
                  .background(ElectricBlue)
                  .border(1.dp, ElectricBlue, RoundedCornerShape(6.dp))
                  .padding(horizontal = 8.dp, vertical = 4.dp)
                  .clickable { updateViewModel.showDialog() }
              )
            }
          }

          if (updateUiState.hasUpdateFile) {
            HorizontalDivider(
              color = DarkBorderSubtle,
              modifier = Modifier.padding(vertical = 10.dp)
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text("Downloaded update file", color = TextPrimary, fontSize = 13.sp)
                Text(
                  "Frees the space and forces a clean re-download next time.",
                  color = TextMuted,
                  fontSize = 11.sp
                )
              }
              TextButton(
                onClick = { updateViewModel.deleteDownloadedFile() },
                modifier = Modifier.testTag("btn_delete_update_file")
              ) {
                Text("Delete file", color = DangerRed, fontSize = 13.sp)
              }
            }
          }

          if (updateUiState.updateState != com.agentisco.data.repository.UpdateRepository.UpdateState.IDLE) {
            HorizontalDivider(
              color = DarkBorderSubtle,
              modifier = Modifier.padding(vertical = 10.dp)
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.Center,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                when (updateUiState.updateState) {
                  com.agentisco.data.repository.UpdateRepository.UpdateState.CHECKING ->
                    "Checking for updates..."
                  com.agentisco.data.repository.UpdateRepository.UpdateState.DOWNLOADING ->
                    "Downloading update..."
                  com.agentisco.data.repository.UpdateRepository.UpdateState.DOWNLOADED ->
                    "Update ready to install"
                  com.agentisco.data.repository.UpdateRepository.UpdateState.ERROR ->
                    "Update failed — tap to retry"
                  com.agentisco.data.repository.UpdateRepository.UpdateState.AVAILABLE ->
                    "Updates found"
                  else -> "Checking..."
                },
                color = if (updateUiState.updateState == com.agentisco.data.repository.UpdateRepository.UpdateState.ERROR) DangerRed else TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { updateViewModel.checkForUpdates() }
              )
            }
          }
        }
      }
    }

    // AI Providers & Models (provider-centric configuration)
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          com.agentisco.ui.components.AIProvidersSection(viewModel = viewModel)
        }
      }
    }

    // File Editing Permission Card (Section 22)
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Text("File Editing Permissions", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
          Spacer(modifier = Modifier.height(8.dp))

          PermissionRadioItem(
            title = "Always ask before editing",
            selected = permissions.fileEditing == PermissionMode.ALWAYS_ASK,
            onClick = { viewModel.updatePermissions { it.copy(fileEditing = PermissionMode.ALWAYS_ASK) } }
          )
          PermissionRadioItem(
            title = "Auto-approve inside project workspace",
            selected = permissions.fileEditing == PermissionMode.AUTO_APPROVE_PROJECT,
            onClick = { viewModel.updatePermissions { it.copy(fileEditing = PermissionMode.AUTO_APPROVE_PROJECT) } }
          )
          PermissionRadioItem(
            title = "Never allow file modifications",
            selected = permissions.fileEditing == PermissionMode.NEVER_ALLOW,
            onClick = { viewModel.updatePermissions { it.copy(fileEditing = PermissionMode.NEVER_ALLOW) } }
          )
        }
      }
    }

    // Terminal Commands Permission Card
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Text("Terminal Execution Safety", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
          Spacer(modifier = Modifier.height(8.dp))

          PermissionRadioItem(
            title = "Always ask before running any command",
            selected = permissions.terminalCommands == PermissionMode.ALWAYS_ASK,
            onClick = { viewModel.updatePermissions { it.copy(terminalCommands = PermissionMode.ALWAYS_ASK) } }
          )
          PermissionRadioItem(
            title = "Allow safe commands (ls, git, npm test)",
            selected = permissions.terminalCommands == PermissionMode.ALLOW_SAFE,
            onClick = { viewModel.updatePermissions { it.copy(terminalCommands = PermissionMode.ALLOW_SAFE) } }
          )
          PermissionRadioItem(
            title = "Allow all commands (Dangerous)",
            selected = permissions.terminalCommands == PermissionMode.ALLOW_ALL,
            warning = true,
            onClick = { viewModel.updatePermissions { it.copy(terminalCommands = PermissionMode.ALLOW_ALL) } }
          )
        }
      }
    }

    // Network & Boundaries Card
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Text("Network & Tool Loop Limits", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
          Spacer(modifier = Modifier.height(10.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text("Network Access", color = TextPrimary, fontSize = 13.sp)
              Text("Allow agent to fetch web docs & packages", color = TextMuted, fontSize = 11.sp)
            }
            Switch(
              checked = permissions.networkAccess,
              onCheckedChange = { ch -> viewModel.updatePermissions { it.copy(networkAccess = ch) } },
              colors = SwitchDefaults.colors(
              checkedThumbColor = ElectricBlue,
              checkedTrackColor = ElectricBlue.copy(alpha = 0.35f),
              checkedBorderColor = ElectricBlue,
              uncheckedThumbColor = TextSecondary,
              uncheckedTrackColor = DarkSurfaceHighlight,
              uncheckedBorderColor = DarkBorder
            )
            )
          }

          HorizontalDivider(color = DarkBorderSubtle, modifier = Modifier.padding(vertical = 10.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Max Tool Iterations", color = TextPrimary, fontSize = 13.sp)
              Text("Unlimited lets the agent work fully autonomously", color = TextMuted, fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(
                modifier = Modifier
                  .size(30.dp)
                  .clip(RoundedCornerShape(6.dp))
                  .background(DarkSurfaceElevated)
                  .border(1.dp, DarkBorderSubtle, RoundedCornerShape(6.dp))
                  .clickable {
                    viewModel.updatePermissions {
                      val v = it.maxToolIterations
                      it.copy(maxToolIterations = when {
                        v == UNLIMITED_ITERATIONS -> 100
                        else -> (v - 5).coerceAtLeast(1)
                      })
                    }
                  }
                  .testTag("btn_iter_decrease"),
                contentAlignment = Alignment.Center
              ) { Text("−", color = TextPrimary, fontSize = 14.sp) }
              Box(
                modifier = Modifier
                  .padding(horizontal = 10.dp)
                  .clip(RoundedCornerShape(6.dp))
                  .background(DarkSurfaceElevated)
                  .padding(horizontal = 10.dp, vertical = 6.dp)
                  .testTag("txt_iter_value")
              ) {
                Text(
                  if (permissions.maxToolIterations == UNLIMITED_ITERATIONS) "∞" else "${permissions.maxToolIterations}",
                  color = ElectricBlueGlow,
                  fontSize = 13.sp,
                  fontWeight = FontWeight.SemiBold,
                  fontFamily = FontFamily.Monospace
                )
              }
              Box(
                modifier = Modifier
                  .size(30.dp)
                  .clip(RoundedCornerShape(6.dp))
                  .background(DarkSurfaceElevated)
                  .border(1.dp, DarkBorderSubtle, RoundedCornerShape(6.dp))
                  .clickable {
                    viewModel.updatePermissions {
                      val v = it.maxToolIterations
                      it.copy(maxToolIterations = when {
                        v == UNLIMITED_ITERATIONS -> v
                        v >= 100 -> UNLIMITED_ITERATIONS
                        else -> v + 5
                      })
                    }
                  }
                  .testTag("btn_iter_increase"),
                contentAlignment = Alignment.Center
              ) { Text("+", color = TextPrimary, fontSize = 14.sp) }
            }
          }
        }
      }
    }

    // Notifications Preferences Card
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Text("Push & In-App Alerts", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
          Spacer(modifier = Modifier.height(8.dp))

          NotificationToggleRow("Task completed", true)
          NotificationToggleRow("Build failed", true)
          NotificationToggleRow("Approval requested", true)
          NotificationToggleRow("Background task finished", true)
        }
      }
    }

    // Chat Tool Activity Card
    item {
      ChatToolActivityCard(viewModel)
    }

    // Codebase Scanning Performance Card (bottom: affects every project)
    item {
      ScanExclusionsCard(viewModel)
    }

    item {
      Spacer(modifier = Modifier.height(24.dp))
    }
  }
}

/**
 * How agent tool activity is rendered in chat: structured cards (command,
 * git-style diff, plain output) by default, with raw request JSON optional.
 */
@Composable
private fun ChatToolActivityCard(viewModel: WorkspaceViewModel) {
  val chatDisplay by viewModel.chatDisplay.collectAsState()

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
    colors = CardDefaults.cardColors(containerColor = DarkSurface)
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text("Chat Tool Activity", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        "Tool cards always show the structured view: the real command with a copy button, " +
          "git-style +/- diffs for file edits, and plain output. Turn this on to ALSO see the " +
          "raw JSON the agent sent with each request when a card is expanded.",
        color = TextMuted,
        fontSize = 11.sp,
        lineHeight = 15.sp
      )
      Spacer(modifier = Modifier.height(10.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Show raw JSON requests", color = TextPrimary, fontSize = 13.sp)
          Text(
            if (chatDisplay.showToolJson) "Expanded cards include a \"Request (raw JSON)\" section"
            else "Structured view only (recommended)",
            color = TextMuted,
            fontSize = 11.sp
          )
        }
        Switch(
          checked = chatDisplay.showToolJson,
          onCheckedChange = { viewModel.setChatToolJsonVisible(it) },
          colors = SwitchDefaults.colors(
            checkedThumbColor = ElectricBlue,
            checkedTrackColor = ElectricBlue.copy(alpha = 0.35f),
            checkedBorderColor = ElectricBlue,
            uncheckedThumbColor = TextSecondary,
            uncheckedTrackColor = DarkSurfaceHighlight,
            uncheckedBorderColor = DarkBorder
          ),
          modifier = Modifier.testTag("switch_show_tool_json")
        )
      }
    }
  }
}

/**
 * Editable scan-exclusion list: the folder names skipped by tree scans,
 * searches, agent tools and imports. Users can remove defaults, add their
 * own, or replace the built-in list entirely.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ScanExclusionsCard(viewModel: WorkspaceViewModel) {
  val settings by viewModel.scanIgnoreSettings.collectAsState()
  val skippedDirs by viewModel.effectiveIgnoredDirs.collectAsState()
  var newDirInput by remember { mutableStateOf("") }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
    colors = CardDefaults.cardColors(containerColor = DarkSurface)
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text("Codebase Scanning Performance", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        "Folders listed here are skipped everywhere: the Files tree, project search, agent tools and folder imports. " +
          "Generated folders like node_modules or build outputs often hold hundreds of thousands of files — " +
          "skipping them is what keeps huge projects fast to open. Only remove one if you truly need to browse it.",
        color = TextMuted,
        fontSize = 11.sp,
        lineHeight = 15.sp
      )
      Spacer(modifier = Modifier.height(12.dp))

      // List composition mode: extend the defaults, or override them fully.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Only my custom list", color = TextPrimary, fontSize = 13.sp)
          Text(
            if (settings.useCustomListOnly)
              "Built-in defaults are fully overridden — only the folders below are skipped."
            else
              "Off: built-in defaults + folders you add, minus any you remove.",
            color = TextMuted,
            fontSize = 11.sp,
            lineHeight = 14.sp
          )
        }
        Switch(
          checked = settings.useCustomListOnly,
          onCheckedChange = { viewModel.setIgnoredDirsOverride(it) },
          modifier = Modifier.testTag("switch_ignore_override"),
          colors = SwitchDefaults.colors(
            checkedThumbColor = ElectricBlue,
            checkedTrackColor = ElectricBlue.copy(alpha = 0.35f),
            checkedBorderColor = ElectricBlue,
            uncheckedThumbColor = TextSecondary,
            uncheckedTrackColor = DarkSurfaceHighlight,
            uncheckedBorderColor = DarkBorder
          )
        )
      }

      HorizontalDivider(color = DarkBorderSubtle, modifier = Modifier.padding(vertical = 10.dp))

      Text(
        if (settings.useCustomListOnly) "Skipped folders (custom)" else "Skipped folders",
        color = TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold
      )
      Spacer(modifier = Modifier.height(6.dp))
      if (skippedDirs.isEmpty()) {
        Text(
          "Nothing is skipped — opening huge projects may be slow or run out of memory.",
          color = WarningAmber,
          fontSize = 11.sp,
          modifier = Modifier.testTag("txt_ignore_warning")
        )
      } else {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          skippedDirs.forEach { name ->
            IgnoreChip(
              name = name,
              custom = settings.extraDirs.contains(name),
              onRemove = { viewModel.removeIgnoredDir(name) }
            )
          }
        }
      }

      // Removed defaults stay one tap away from being restored.
      if (!settings.useCustomListOnly && settings.removedDefaults.isNotEmpty()) {
        Spacer(modifier = Modifier.height(10.dp))
        Text("Removed from defaults — tap to restore", color = TextMuted, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          settings.removedDefaults.sorted().forEach { name ->
            IgnoreChip(
              name = name,
              custom = false,
              struckThrough = true,
              onRemove = { viewModel.addIgnoredDir(name) }
            )
          }
        }
      }

      // Add a folder name to the list.
      Spacer(modifier = Modifier.height(12.dp))
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
      ) {
        OutlinedTextField(
          value = newDirInput,
          onValueChange = { newDirInput = it },
          placeholder = { Text("folder name, e.g. third_party", color = TextMuted, fontSize = 12.sp) },
          singleLine = true,
          modifier = Modifier
            .weight(1f)
            .testTag("input_ignore_dir"),
          textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextPrimary),
          shape = RoundedCornerShape(8.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ElectricBlue,
            unfocusedBorderColor = DarkBorder,
            focusedContainerColor = DarkBackground,
            unfocusedContainerColor = DarkBackground
          )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
          onClick = {
            viewModel.addIgnoredDir(newDirInput)
            newDirInput = ""
          },
          enabled = newDirInput.isNotBlank(),
          modifier = Modifier.testTag("btn_add_ignore_dir"),
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
        ) { Text("Add", fontSize = 12.sp) }
      }

      TextButton(
        onClick = { viewModel.restoreDefaultIgnoredDirs() },
        modifier = Modifier.testTag("btn_reset_ignore_dirs")
      ) {
        Text("Restore built-in defaults", color = TextMuted, fontSize = 12.sp)
      }
    }
  }
}

@Composable
private fun IgnoreChip(
  name: String,
  custom: Boolean,
  struckThrough: Boolean = false,
  onRemove: () -> Unit
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(DarkSurfaceElevated)
      .border(
        1.dp,
        if (custom) ElectricBlue.copy(alpha = 0.6f) else DarkBorderSubtle,
        RoundedCornerShape(6.dp)
      )
      .clickable(onClick = onRemove)
      .padding(horizontal = 8.dp, vertical = 5.dp)
      .testTag("chip_ignore_$name")
  ) {
    Text(
      text = name,
      color = if (struckThrough) TextMuted else TextPrimary,
      fontSize = 11.sp,
      fontFamily = FontFamily.Monospace,
      textDecoration = if (struckThrough) {
        androidx.compose.ui.text.style.TextDecoration.LineThrough
      } else {
        androidx.compose.ui.text.style.TextDecoration.None
      }
    )
    Spacer(modifier = Modifier.width(5.dp))
    Icon(
      imageVector = if (struckThrough) Icons.Default.Add else Icons.Default.Close,
      contentDescription = if (struckThrough) "Restore $name" else "Stop skipping $name",
      tint = TextMuted,
      modifier = Modifier.size(12.dp)
    )
  }
}

@Composable
private fun PermissionRadioItem(
  title: String,
  selected: Boolean,
  warning: Boolean = false,
  onClick: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    RadioButton(
      selected = selected,
      onClick = onClick,
      colors = RadioButtonDefaults.colors(
        selectedColor = if (warning) DangerRed else ElectricBlue
      )
    )
    Spacer(modifier = Modifier.width(6.dp))
    Text(
      text = title,
      color = if (warning && selected) DangerRed else TextPrimary,
      fontSize = 13.sp,
      fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
    )
  }
}

@Composable
private fun NotificationToggleRow(label: String, initial: Boolean) {
  var checked by remember { mutableStateOf(initial) }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(label, color = TextSecondary, fontSize = 12.sp)
    Switch(
      checked = checked,
      onCheckedChange = { checked = it },
      colors = SwitchDefaults.colors(
              checkedThumbColor = ElectricBlue,
              checkedTrackColor = ElectricBlue.copy(alpha = 0.35f),
              checkedBorderColor = ElectricBlue,
              uncheckedThumbColor = TextSecondary,
              uncheckedTrackColor = DarkSurfaceHighlight,
              uncheckedBorderColor = DarkBorder
            )
    )
  }
}
