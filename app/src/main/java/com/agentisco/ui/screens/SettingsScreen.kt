package com.agentisco.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentisco.BuildConfig
import com.agentisco.core.model.AppDestination
import com.agentisco.agent.model.PermissionMode
import com.agentisco.agent.model.UNLIMITED_ITERATIONS
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.components.AgentTeamCard
import com.agentisco.ui.components.SkillCard
import com.agentisco.ui.components.formatModelBytes
import com.agentisco.ui.theme.*

@Composable
fun SettingsScreen(
  viewModel: WorkspaceViewModel,
  updateViewModel: com.agentisco.ui.UpdateViewModel,
  onNavigate: (AppDestination) -> Unit,
  onShowCrashLog: () -> Unit = {},
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

    item { BackgroundExecutionCard(viewModel) }

    // The two AI surfaces, each managed on its own screen: the provider list grows long
    // (10+ providers is normal) and so does a device with several models on it, so
    // embedding either here buried every setting below it.
    item {
      val providers by viewModel.providers.collectAsState()
      val models by viewModel.aiModels.collectAsState()
      val localModels by viewModel.localModels.collectAsState()
      val installed = localModels.count { it.installed }
      val bytesOnDisk = localModels.filter { it.installed }.sumOf { it.sizeBytes }

      SettingsNavigationCard(
        icon = Icons.Outlined.Psychology,
        title = "AI Providers",
        summary = if (providers.isEmpty()) {
          "No providers configured — tap to add one"
        } else {
          "${providers.size} provider${if (providers.size == 1) "" else "s"} · ${models.size} model${if (models.size == 1) "" else "s"}"
        },
        onClick = { onNavigate(AppDestination.AI_PROVIDERS) },
        tag = "card_ai_providers"
      )

      Spacer(modifier = Modifier.height(16.dp))

      SettingsNavigationCard(
        icon = Icons.Outlined.Memory,
        title = "Local Models",
        summary = if (installed == 0) {
          "No models installed — tap to add one"
        } else {
          "$installed installed · ${formatModelBytes(bytesOnDisk)} on disk"
        },
        onClick = { onNavigate(AppDestination.LOCAL_MODELS) },
        tag = "card_local_models"
      )
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

    // Agent team card: who the agent may hand a piece of work to
    item {
      AgentTeamCard(viewModel)
    }

    // Skills card: the know-how the agent can read for itself
    item {
      SkillCard(viewModel)
    }

    // Chat Tool Activity Card
    item {
      ChatToolActivityCard(viewModel)
    }

    // Context & compaction card
    item {
      ContextCompactionCard(viewModel)
    }

    // Codebase Scanning Performance Card (bottom: affects every project)
    item {
      ScanExclusionsCard(viewModel)
    }

    // Debug diagnostics: the crash trace the app captured from its last run.
    if (BuildConfig.DEBUG) {
      item {
        DebugDiagnosticsCard(onShowCrashLog = onShowCrashLog)
      }
    }

    // About: what the app is, and who to reach.
    item {
      AboutCard()
    }

    item {
      Spacer(modifier = Modifier.height(24.dp))
    }
  }
}

/**
 * Debug-only shortcut back to [com.agentisco.ui.components.CrashLogDialog], so
 * a captured crash can be re-opened after dismissing the launch-time popup.
 */
/**
 * Everything that decides whether Agentisco survives leaving the screen: the
 * master switch, the two battery-conscious knobs under it, and the live
 * permission checklist that only the user can complete.
 */
@Composable
private fun BackgroundExecutionCard(viewModel: WorkspaceViewModel) {
  val context = LocalContext.current
  val allowed by viewModel.allowBackgroundExecution.collectAsState()
  val wakeLock by viewModel.backgroundWakeLockEnabled.collectAsState()
  val terminalHold by viewModel.terminalHeld.collectAsState()
  val requirements by viewModel.backgroundRequirements.collectAsState()
  val interrupted by viewModel.interruptedBackgroundWork.collectAsState()

  // The notification ask is a runtime permission, so it has to be raised by the
  // screen the user is looking at — BackgroundPermissions cannot do it.
  val requestNotifications = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) viewModel.dismissNotificationsPrompt() else viewModel.markNotificationsAsked()
  }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
    colors = CardDefaults.cardColors(containerColor = DarkSurface)
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text("Background execution", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
      Text(
        "Keep agent turns, Linux builds, terminal servers and downloads running after you leave the screen.",
        color = TextMuted,
        fontSize = 11.sp,
        lineHeight = 14.sp
      )
      Spacer(modifier = Modifier.height(6.dp))

      ToggleRow(
        title = "Run while backgrounded",
        subtitle = "Raises a foreground service with an ongoing notification for as long as work is live, and stops it the moment nothing is running.",
        checked = allowed,
        tag = "switch_background_execution",
        onCheckedChange = { viewModel.setAllowBackgroundExecution(it) }
      )

      if (allowed) {
        ToggleRow(
          title = "Keep the CPU awake",
          subtitle = "A partial wake lock for work that computes — a turn running tools, a build, apt. Never for a download, and never longer than six hours in one stretch.",
          checked = wakeLock,
          tag = "switch_background_wakelock",
          onCheckedChange = { viewModel.setBackgroundWakeLockEnabled(it) }
        )
        ToggleRow(
          title = "Keep the terminal awake",
          subtitle = "Hold the CPU for a shell you started by hand, so a dev server you launched keeps serving. Turn it off when you are done with it.",
          checked = terminalHold,
          tag = "switch_terminal_hold",
          onCheckedChange = { viewModel.setTerminalHeld(it) }
        )
      }

      HorizontalDivider(color = DarkBorderSubtle, modifier = Modifier.padding(vertical = 10.dp))

      requirements.forEach { requirement ->
        BackgroundRequirementRow(
          requirement = requirement,
          onAction = { action ->
            if (action == com.agentisco.background.RequirementAction.REQUEST_NOTIFICATIONS) {
              requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
              viewModel.performBackgroundAction(context, action)
            }
          }
        )
      }

      if (interrupted.isNotEmpty()) {
        HorizontalDivider(color = DarkBorderSubtle, modifier = Modifier.padding(vertical = 10.dp))
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(WarningAmberBg.copy(alpha = 0.45f))
            .padding(10.dp)
            .testTag("card_interrupted_work")
        ) {
          Text(
            "Interrupted while Agentisco was closed",
            color = WarningAmber,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
          )
          interrupted.forEach { entry ->
            Text(
              entry.label,
              color = TextSecondary,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace
            )
          }
          Text(
            "Nothing was restarted automatically — resuming spends your data and your API quota.",
            color = TextMuted,
            fontSize = 11.sp,
            lineHeight = 14.sp
          )
          TextButton(onClick = { viewModel.acknowledgeInterruptedWork() }) {
            Text("Dismiss", color = ElectricBlueGlow, fontSize = 12.sp)
          }
        }
      }
    }
  }
}

@Composable
private fun BackgroundRequirementRow(
  requirement: com.agentisco.background.BackgroundRequirement,
  onAction: (com.agentisco.background.RequirementAction) -> Unit
) {
  val statusColor = when {
    requirement.status == com.agentisco.background.RequirementStatus.GRANTED -> TerminalGreen
    requirement.status == com.agentisco.background.RequirementStatus.ACTION_REQUIRED && requirement.blocking -> DangerRed
    requirement.status == com.agentisco.background.RequirementStatus.ACTION_REQUIRED -> WarningAmber
    else -> TextMuted
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 5.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(requirement.title, color = TextPrimary, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          when (requirement.status) {
            com.agentisco.background.RequirementStatus.GRANTED -> "OK"
            com.agentisco.background.RequirementStatus.ACTION_REQUIRED -> "Needs action"
            com.agentisco.background.RequirementStatus.NOT_SUPPORTED -> "Not needed"
            com.agentisco.background.RequirementStatus.DISABLED_BY_USER -> "Off"
          },
          color = statusColor,
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold
        )
      }
      Text(
        requirement.description,
        color = TextMuted,
        fontSize = 11.sp,
        lineHeight = 14.sp
      )
    }
    if (requirement.action != com.agentisco.background.RequirementAction.NONE) {
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        if (requirement.status == com.agentisco.background.RequirementStatus.GRANTED) "Settings" else "Fix",
        color = ElectricBlueGlow,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
          .clip(RoundedCornerShape(6.dp))
          .border(1.dp, DarkBorder, RoundedCornerShape(6.dp))
          .background(DarkSurfaceElevated)
          .padding(horizontal = 8.dp, vertical = 4.dp)
          .clickable { onAction(requirement.action) }
          .testTag("btn_requirement_${requirement.key.name.lowercase()}")
      )
    }
  }
}

@Composable
private fun DebugDiagnosticsCard(onShowCrashLog: () -> Unit) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
    colors = CardDefaults.cardColors(containerColor = DarkSurface)
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.BugReport, contentDescription = null, tint = DangerRed, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Debug Diagnostics", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
      }
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        "The previous run's crash trace is stored on-device and can be copied or exported " +
          "for inspection without logcat.",
        color = TextMuted,
        fontSize = 11.sp,
        lineHeight = 15.sp
      )
      Spacer(modifier = Modifier.height(10.dp))
      OutlinedButton(
        onClick = onShowCrashLog,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
      ) {
        Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(14.dp), tint = TextSecondary)
        Spacer(modifier = Modifier.width(6.dp))
        Text("Show last crash log", fontSize = 12.sp, color = TextPrimary)
      }
    }
  }
}

/**
 * When and how the agent's context window is compressed.
 *
 * The transcript the user reads is never shortened: compaction only changes
 * what is *sent* to the provider, so every message and tool result stays in
 * the chat. This card tunes that behaviour.
 */
@Composable
private fun ContextCompactionCard(viewModel: WorkspaceViewModel) {
  val settings by viewModel.compactSettings.collectAsState()
  val usage by viewModel.contextUsage.collectAsState()
  val model by viewModel.selectedModel.collectAsState()
  val working by viewModel.isAgentWorking.collectAsState()

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
      .testTag("card_context_compaction"),
    colors = CardDefaults.cardColors(containerColor = DarkSurface)
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Calculate, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Context & Compaction", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
      }
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        "Long conversations are compressed before they overflow the model's context window: old tool " +
          "results are cleared locally, then the earlier turns are replaced with a structured summary the " +
          "agent continues from. Nothing is deleted — the chat above keeps every message and tool output.",
        color = TextMuted,
        fontSize = 11.sp,
        lineHeight = 15.sp
      )
      Spacer(modifier = Modifier.height(10.dp))

      // Current occupancy, measured from the live transcript.
      ContextUsageRow(usage, model?.contextWindow)

      Spacer(modifier = Modifier.height(10.dp))

      ToggleRow(
        title = "Auto-compaction",
        subtitle = if (settings.autoCompactEnabled)
          "Summarizes earlier turns before the context window fills up"
        else
          "Off — the conversation grows until the provider rejects it",
        checked = settings.autoCompactEnabled,
        tag = "switch_autocompact"
      ) { viewModel.setAutoCompactEnabled(it) }

      ToggleRow(
        title = "Clear old tool results locally",
        subtitle = if (settings.microcompactEnabled)
          "Cheap first pass: drops payloads of old read/command results, no extra model call"
        else
          "Only the full summary pass runs",
        checked = settings.microcompactEnabled,
        tag = "switch_microcompact"
      ) { viewModel.setMicrocompactEnabled(it) }

      ToggleRow(
        title = "Allow manual compaction",
        subtitle = "Lets you compress the conversation on demand from the composer",
        checked = settings.manualCompactEnabled,
        tag = "switch_manual_compact"
      ) { viewModel.setManualCompactEnabled(it) }

      ToggleRow(
        title = "Show context percentage in the composer",
        subtitle = "Live share of the model's context window, next to the model dropdown",
        checked = settings.showContextUsage,
        tag = "switch_show_context_usage"
      ) { viewModel.setContextUsageVisible(it) }

      Spacer(modifier = Modifier.height(10.dp))

      // Compact threshold: 100 % means "as late as safely possible".
      Column {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("Compact threshold", color = TextPrimary, fontSize = 13.sp)
          Text(
            "${settings.thresholdPercent}%",
            color = ElectricBlue,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("compact_threshold_value")
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          "Share of the available window at which the conversation is summarized. Lower compacts earlier " +
            "and keeps more headroom; 100% waits until the safety buffer is reached.",
          color = TextMuted,
          fontSize = 11.sp,
          lineHeight = 14.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
          value = settings.thresholdPercent.toFloat(),
          onValueChange = { viewModel.setCompactThresholdPercent(it.toInt()) },
          valueRange = 25f..150f,
          steps = 24,
          colors = SliderDefaults.colors(
            thumbColor = ElectricBlue,
            activeTrackColor = ElectricBlue,
            inactiveTrackColor = DarkSurfaceHighlight
          ),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("slider_compact_threshold")
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Kept rounds: how much recent context survives a summary verbatim.
      Column {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("Keep recent turns", color = TextPrimary, fontSize = 13.sp)
          Text(
            "${settings.keepRecentRounds} turn${if (settings.keepRecentRounds == 1) "" else "s"}",
            color = ElectricBlue,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          "Assistant turns held verbatim after a summary, so the tool results the model is currently " +
            "working with are never summarized away.",
          color = TextMuted,
          fontSize = 11.sp,
          lineHeight = 14.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          StepperButton("-", enabled = settings.keepRecentRounds > 0) {
            viewModel.setCompactKeepRecentRounds(settings.keepRecentRounds - 1)
          }
          Slider(
            value = settings.keepRecentRounds.toFloat(),
            onValueChange = { viewModel.setCompactKeepRecentRounds(it.toInt()) },
            valueRange = 0f..8f,
            steps = 7,
            colors = SliderDefaults.colors(
              thumbColor = ElectricBlue,
              activeTrackColor = ElectricBlue,
              inactiveTrackColor = DarkSurfaceHighlight
            ),
            modifier = Modifier
              .weight(1f)
              .testTag("slider_keep_recent_rounds")
          )
          StepperButton("+", enabled = settings.keepRecentRounds < 8) {
            viewModel.setCompactKeepRecentRounds(settings.keepRecentRounds + 1)
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      OutlinedButton(
        onClick = { viewModel.compactNow() },
        enabled = settings.manualCompactEnabled && !working,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
        modifier = Modifier.testTag("button_compact_now")
      ) {
        Icon(Icons.Outlined.Compress, contentDescription = null, modifier = Modifier.size(14.dp), tint = TextSecondary)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = if (working) "Compaction waits for the current turn" else "Compact conversation now",
          fontSize = 12.sp,
          color = TextPrimary
        )
      }
    }
  }
}

@Composable
private fun ContextUsageRow(usage: com.agentisco.agent.compact.ContextTokenUsage, contextWindow: Int?) {
  val tint = when {
    usage.isAboveThreshold -> DangerRed
    usage.pressurePercent >= 85 -> WarningAmber
    usage.pressurePercent >= 60 -> ElectricBlueGlow
    else -> TextSecondary
  }
  Column {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
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
        color = TextMuted,
        fontSize = 10.sp,
        maxLines = 1
      )
    }
    Spacer(modifier = Modifier.height(6.dp))
    LinearProgressIndicator(
      progress = { (usage.percent / 100f).coerceIn(0f, 1f) },
      color = tint,
      trackColor = DarkSurfaceHighlight,
      modifier = Modifier
        .fillMaxWidth()
        .height(4.dp)
        .clip(RoundedCornerShape(2.dp))
    )
  }
}

@Composable
private fun ToggleRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  tag: String,
  onCheckedChange: (Boolean) -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, color = TextPrimary, fontSize = 13.sp)
      Text(subtitle, color = TextMuted, fontSize = 11.sp, lineHeight = 14.sp)
    }
    Spacer(modifier = Modifier.width(8.dp))
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors = SwitchDefaults.colors(
        checkedThumbColor = ElectricBlue,
        checkedTrackColor = ElectricBlue.copy(alpha = 0.35f),
        checkedBorderColor = ElectricBlue,
        uncheckedThumbColor = TextSecondary,
        uncheckedTrackColor = DarkSurfaceHighlight,
        uncheckedBorderColor = DarkBorder
      ),
      modifier = Modifier.testTag(tag)
    )
  }
}

@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .size(26.dp)
      .clip(RoundedCornerShape(6.dp))
      .background(if (enabled) DarkSurfaceElevated else DarkSurface.copy(alpha = 0.5f))
      .border(1.dp, DarkBorder, RoundedCornerShape(6.dp))
      .clickable(enabled = enabled) { onClick() },
    contentAlignment = Alignment.Center
  ) {
    Text(label, color = if (enabled) TextPrimary else TextMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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

/**
 * Doorway to a surface that has its own screen. Settings keeps these instead of the
 * lists themselves — a provider list or a device with several models on it pushes
 * everything else below the fold — and the summary carries the one number that says
 * whether there is anything to do on the other side.
 */
@Composable
internal fun SettingsNavigationCard(
  icon: ImageVector,
  title: String,
  summary: String,
  onClick: () -> Unit,
  tag: String
) {
  Card(
    onClick = onClick,
    modifier = Modifier
      .fillMaxWidth()
      .testTag(tag),
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
    border = BorderStroke(1.dp, DarkBorder)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(34.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(ElectricBlue.copy(alpha = 0.15f))
          .border(1.dp, ElectricBlue.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
      ) {
        Icon(icon, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(18.dp))
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(summary, color = TextSecondary, fontSize = 11.sp)
      }
      Icon(
        Icons.Default.ChevronRight,
        contentDescription = "Open $title",
        tint = ElectricBlueGlow.copy(alpha = 0.7f),
        modifier = Modifier.size(18.dp)
      )
    }
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

private const val DEVELOPER_NAME = "Gemechis Chala"
private const val DEVELOPER_EMAIL = "gladsonchala@gmail.com"
private const val DEVELOPER_TELEGRAM = "venopyx"
private const val DEVELOPER_LOCATION = "Addis Ababa, Ethiopia"

@Composable
internal fun AboutCard(modifier: Modifier = Modifier) {
  val uriHandler = LocalUriHandler.current
  Card(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
    colors = CardDefaults.cardColors(containerColor = DarkSurface)
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text("About", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = "Agentisco is a coding workspace that runs on the device itself: an Ubuntu " +
          "userland, a real terminal, and an agent that reads, edits, builds and tests your " +
          "projects. Projects, chats and settings stay in the app's private storage — only the " +
          "prompts you send leave it, for the model provider you configure.",
        color = TextSecondary,
        fontSize = 12.sp,
        lineHeight = 17.sp
      )
      Spacer(modifier = Modifier.height(10.dp))
      Text(
        text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        color = TextMuted,
        fontSize = 10.5.sp,
        fontFamily = FontFamily.Monospace
      )

      Spacer(modifier = Modifier.height(14.dp))
      Text(DEVELOPER_NAME, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
      Text(
        text = "Developer · $DEVELOPER_LOCATION",
        color = TextMuted,
        fontSize = 11.sp
      )
      Spacer(modifier = Modifier.height(10.dp))
      ContactRow(
        icon = Icons.Outlined.MailOutline,
        label = "Email me",
        value = DEVELOPER_EMAIL,
        tint = ElectricBlueGlow,
        tag = "about_email",
        onClick = { uriHandler.openUri("mailto:$DEVELOPER_EMAIL") }
      )
      Spacer(modifier = Modifier.height(8.dp))
      ContactRow(
        icon = Icons.AutoMirrored.Outlined.Send,
        label = "Telegram",
        value = "@$DEVELOPER_TELEGRAM",
        tint = TerminalGreen,
        tag = "about_telegram",
        onClick = { uriHandler.openUri("https://t.me/$DEVELOPER_TELEGRAM") }
      )
    }
  }
}

@Composable
private fun ContactRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  value: String,
  tint: Color,
  tag: String,
  onClick: () -> Unit
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(DarkBackground)
      .border(1.dp, DarkBorder, RoundedCornerShape(10.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 9.dp)
      .testTag(tag)
  ) {
    Box(
      modifier = Modifier
        .size(26.dp)
        .clip(androidx.compose.foundation.shape.CircleShape)
        .background(tint.copy(alpha = 0.14f)),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(14.dp)
      )
    }
    Spacer(modifier = Modifier.width(10.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(label, color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
      Text(value, color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
    Icon(
      imageVector = Icons.Default.ChevronRight,
      contentDescription = null,
      tint = TextMuted,
      modifier = Modifier.size(16.dp)
    )
  }
}
