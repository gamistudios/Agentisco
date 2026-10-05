package com.awaki.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.awaki.core.model.AppDestination
import com.awaki.data.model.Project
import com.awaki.data.model.ProjectKind
import com.awaki.ui.WorkspaceViewModel
import com.awaki.ui.theme.*
import com.awaki.workspace.buildrun.*
import com.awaki.workspace.terminal.LinuxEnvironmentState
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The Run & Build Center: a Vercel/Render-style pipeline for the active
 * project. Each stage (install / build / test / run) is a configurable command
 * executed inside the embedded Linux environment; output streams into the
 * console below, and the run stage's HTTP endpoint opens as a live in-app
 * preview once it answers. Commands are configured by the one-tap
 * auto-detection pass or edited by hand — nothing on this page is simulated.
 */
@Composable
fun BuildRunScreen(
  viewModel: WorkspaceViewModel,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  val project by viewModel.activeProject.collectAsState()
  val envState by viewModel.linuxEnvironmentState.collectAsState()
  val config by viewModel.buildRunConfig.collectAsState()
  val stageStates by viewModel.buildRunStageStates.collectAsState()
  val logs by viewModel.buildRunLogs.collectAsState()
  val endpoints by viewModel.buildRunEndpoints.collectAsState()
  val previewRequest by viewModel.buildRunPreviewRequest.collectAsState()
  val pipelineRunning by viewModel.buildRunPipelineRunning.collectAsState()
  val detectState by viewModel.buildRunDetectState.collectAsState()

  var previewOpen by remember { mutableStateOf(false) }
  var previewUrl by remember { mutableStateOf<String?>(null) }
  var handledPreviewRequest by remember { mutableStateOf(0) }

  val editable = project.path.isNotBlank() && !project.isMissing
  val runState = stageStates[BuildStageKind.RUN]
  val anyRunning = stageStates.values.any { it.status == BuildStageStatus.RUNNING }
  val nonRunRunning = stageStates.entries.any {
    it.key != BuildStageKind.RUN && it.value.status == BuildStageStatus.RUNNING
  }
  val hasCommands = config?.let { cfg -> BuildStageKind.entries.any { cfg.isConfigured(it) } } == true

  // Opens the preview exactly once per run: the first increment of the request
  // counter happens when the server actually answers the reachability probe.
  LaunchedEffect(previewRequest) {
    if (previewRequest > handledPreviewRequest) {
      handledPreviewRequest = previewRequest
      val target = endpoints.firstOrNull()?.url
      if (target != null && !previewOpen) {
        previewUrl = target
        previewOpen = true
      }
    }
  }

  val openPreview: (String) -> Unit = { url ->
    previewUrl = url
    previewOpen = true
  }

  val activePreviewUrl = previewUrl
  if (previewOpen && activePreviewUrl != null) {
    PreviewModal(url = activePreviewUrl, onDismiss = { previewOpen = false })
  }

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
      .padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    item(key = "header") { HeaderBlock(project) }

    if (!editable) {
      item(key = "missing") { MissingProjectNote(project) }
    }

    if (envState !is LinuxEnvironmentState.Ready) {
      item(key = "env") {
        EnvBanner(state = envState, onOpenTerminal = { onNavigate(AppDestination.TERMINAL) })
      }
    }

    item(key = "config") {
      ConfigCard(
        config = config,
        detectState = detectState,
        editable = editable,
        onAutoConfigure = { viewModel.autoConfigureBuildRun() },
        onReset = { viewModel.resetBuildRunCommands() }
      )
    }

    BuildStageKind.entries.forEach { kind ->
      item(key = "stage-${kind.name}") {
        StageCard(
          kind = kind,
          state = stageStates[kind] ?: BuildStageState(kind),
          command = config?.commandFor(kind).orEmpty(),
          runPort = config?.runPort,
          editable = editable,
          pipelineRunning = pipelineRunning,
          onRun = { viewModel.runBuildStage(kind) },
          onStop = { viewModel.stopBuildStage(kind) },
          onSave = { command, port -> viewModel.saveBuildStageCommand(kind, command, port) }
        )
      }
    }

    item(key = "pipeline") {
      PipelineActions(
        pipelineRunning = pipelineRunning,
        nonRunRunning = nonRunRunning,
        anyRunning = anyRunning,
        hasCommands = hasCommands,
        editable = editable,
        onRunPipeline = { viewModel.runBuildPipeline() },
        onStopAll = { viewModel.stopAllBuildStages() }
      )
    }

    item(key = "console") {
      ConsoleCard(logs = logs, onClear = { viewModel.clearBuildRunLogs() })
    }

    item(key = "preview") {
      PreviewCard(
        config = config,
        runState = runState,
        endpoints = endpoints,
        onOpen = openPreview
      )
    }

    item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
  }
}

@Composable
private fun HeaderBlock(project: Project) {
  Column {
    Spacer(Modifier.height(10.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(Modifier.weight(1f)) {
        Text("Run & Build Center", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
          text = "${project.name} · install → build → test → run",
          color = TextMuted,
          fontSize = 12.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
      if (project.kind != ProjectKind.UNKNOWN) {
        Box(
          Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(DarkSurfaceElevated)
            .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
          Text(project.kind.label, color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
      }
    }
  }
}

@Composable
private fun MissingProjectNote(project: Project) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .border(1.dp, WarningAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
    colors = CardDefaults.cardColors(containerColor = DarkSurface)
  ) {
    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Default.Warning, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Text(
        text = if (project.path.isBlank()) {
          "Select or create a project to configure and run its pipeline."
        } else {
          "This project's folder is missing on disk — restore it from Projects to run commands."
        },
        color = TextSecondary,
        fontSize = 11.sp
      )
    }
  }
}

@Composable
private fun EnvBanner(state: LinuxEnvironmentState, onOpenTerminal: () -> Unit) {
  val detail = when (state) {
    is LinuxEnvironmentState.Failed -> state.reason
    LinuxEnvironmentState.NotBootstrapped -> "Open the Terminal tab once to set up the bundled Debian environment."
    else -> "Setup is in progress on the Terminal screen — come back once it says Ready."
  }
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .border(1.dp, WarningAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
    colors = CardDefaults.cardColors(containerColor = DarkSurface)
  ) {
    Column(Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Linux environment isn't ready", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
      }
      Spacer(Modifier.height(4.dp))
      Text(detail, color = TextSecondary, fontSize = 11.sp)
      Spacer(Modifier.height(8.dp))
      OutlinedButton(
        onClick = onOpenTerminal,
        modifier = Modifier.height(34.dp),
        border = BorderStroke(1.dp, DarkBorder),
        shape = RoundedCornerShape(8.dp)
      ) {
        Icon(Icons.Outlined.Terminal, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("Open Terminal", color = TextPrimary, fontSize = 11.sp)
      }
    }
  }
}

@Composable
private fun ConfigCard(
  config: BuildRunConfig?,
  detectState: BuildRunDetectState,
  editable: Boolean,
  onAutoConfigure: () -> Unit,
  onReset: () -> Unit
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
    colors = CardDefaults.cardColors(containerColor = DarkSurface)
  ) {
    Column(Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(Modifier.weight(1f)) {
          Text("Pipeline Commands", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
          Spacer(Modifier.height(2.dp))
          Text(
            text = config?.let { "${it.source.label} · ${relativeTime(it.updatedAt)}" }
              ?: "Not configured yet — run Auto-configure or edit each stage.",
            color = TextMuted,
            fontSize = 11.sp
          )
        }
        Spacer(Modifier.width(10.dp))
        OutlinedButton(
          onClick = onAutoConfigure,
          enabled = editable && detectState !is BuildRunDetectState.Running,
          modifier = Modifier
            .height(34.dp)
            .testTag("btn_auto_configure"),
          border = BorderStroke(1.dp, if (editable) ElectricBlue.copy(alpha = 0.6f) else DarkBorder),
          shape = RoundedCornerShape(8.dp),
          contentPadding = PaddingValues(horizontal = 10.dp)
        ) {
          if (detectState is BuildRunDetectState.Running) {
            CircularProgressIndicator(Modifier.size(12.dp), color = ElectricBlueGlow, strokeWidth = 1.5.dp)
          } else {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(14.dp))
          }
          Spacer(Modifier.width(6.dp))
          Text(
            text = if (detectState is BuildRunDetectState.Running) "Analyzing…" else "Auto-configure",
            color = TextPrimary,
            fontSize = 11.sp
          )
        }
      }
      if (detectState is BuildRunDetectState.Done) {
        Spacer(Modifier.height(6.dp))
        Text(detectState.message, color = TextSecondary, fontSize = 11.sp)
      }
      Spacer(Modifier.height(6.dp))
      Text(
        "Stages run inside the embedded Linux environment at the project root. AI refinement uses the model selected for background tasks.",
        color = TextMuted,
        fontSize = 10.sp
      )
      if (config?.detectedCommands != null) {
        TextButton(
          onClick = onReset,
          enabled = editable,
          contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
          modifier = Modifier.height(28.dp)
        ) {
          Text("Reset to detected", color = TextSecondary, fontSize = 11.sp)
        }
      }
    }
  }
}

@Composable
private fun StageCard(
  kind: BuildStageKind,
  state: BuildStageState,
  command: String,
  runPort: Int?,
  editable: Boolean,
  pipelineRunning: Boolean,
  onRun: () -> Unit,
  onStop: () -> Unit,
  onSave: (String, Int?) -> Unit
) {
  var editorOpen by remember { mutableStateOf(false) }
  var commandDraft by remember(editorOpen) { mutableStateOf(command) }
  var portDraft by remember(editorOpen) { mutableStateOf(runPort?.toString().orEmpty()) }
  val running = state.status == BuildStageStatus.RUNNING
  val configured = command.isNotBlank()

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .border(1.dp, if (running) ElectricBlue.copy(alpha = 0.5f) else DarkBorder, RoundedCornerShape(12.dp)),
    colors = CardDefaults.cardColors(containerColor = DarkSurface)
  ) {
    Column(Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
          Icon(
            stageIcon(kind),
            contentDescription = kind.label,
            tint = if (running) ElectricBlueGlow else TextSecondary,
            modifier = Modifier.size(18.dp)
          )
          Spacer(Modifier.width(8.dp))
          Text(kind.label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
          Spacer(Modifier.width(8.dp))
          StageStatusChip(state)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (running) {
            IconButton(
              onClick = onStop,
              modifier = Modifier
                .size(30.dp)
                .testTag("btn_stop_stage_${kind.name.lowercase()}")
            ) {
              Icon(Icons.Default.Stop, contentDescription = "Stop ${kind.label}", tint = DangerRed, modifier = Modifier.size(16.dp))
            }
          } else {
            IconButton(
              onClick = onRun,
              enabled = editable && configured && !pipelineRunning,
              modifier = Modifier
                .size(30.dp)
                .testTag("btn_run_stage_${kind.name.lowercase()}")
            ) {
              Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Run ${kind.label}",
                tint = if (editable && configured && !pipelineRunning) TerminalGreen else TextMuted,
                modifier = Modifier.size(18.dp)
              )
            }
          }
          IconButton(
            onClick = { editorOpen = !editorOpen },
            enabled = editable,
            modifier = Modifier
              .size(30.dp)
              .testTag("btn_edit_stage_${kind.name.lowercase()}")
          ) {
            Icon(Icons.Default.Edit, contentDescription = "Edit ${kind.label} command", tint = TextSecondary, modifier = Modifier.size(15.dp))
          }
        }
      }
      Spacer(Modifier.height(6.dp))
      if (configured) {
        Text(
          text = command,
          color = TextCode,
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      } else {
        Text("No command configured — use Auto-configure or tap Edit.", color = TextMuted, fontSize = 11.sp)
      }
      if (kind == BuildStageKind.RUN && runPort != null && !editorOpen) {
        Spacer(Modifier.height(3.dp))
        Text("Preview port: $runPort", color = CyanAccent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
      }
      if (editorOpen) {
        Spacer(Modifier.height(10.dp))
        StageEditor(
          kind = kind,
          commandDraft = commandDraft,
          onCommandChange = { commandDraft = it },
          portDraft = portDraft,
          onPortChange = { input -> portDraft = input.filter { it.isDigit() }.take(5) },
          onCancel = { editorOpen = false },
          onSave = {
            onSave(commandDraft.trim(), portDraft.toIntOrNull()?.takeIf { it in 1..65535 })
            editorOpen = false
          }
        )
      }
    }
  }
}

@Composable
private fun StageEditor(
  kind: BuildStageKind,
  commandDraft: String,
  onCommandChange: (String) -> Unit,
  portDraft: String,
  onPortChange: (String) -> Unit,
  onCancel: () -> Unit,
  onSave: () -> Unit
) {
  Column {
    OutlinedTextField(
      value = commandDraft,
      onValueChange = onCommandChange,
      modifier = Modifier
        .fillMaxWidth()
        .testTag("input_stage_${kind.name.lowercase()}"),
      placeholder = { Text(commandPlaceholder(kind), fontSize = 12.sp, color = TextMuted) },
      textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextCode),
      singleLine = true,
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = ElectricBlue,
        unfocusedBorderColor = DarkBorder,
        cursorColor = ElectricBlueGlow
      )
    )
    if (kind == BuildStageKind.RUN) {
      Spacer(Modifier.height(8.dp))
      OutlinedTextField(
        value = portDraft,
        onValueChange = onPortChange,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("input_run_port"),
        placeholder = { Text("Preview port (optional) — e.g. 5173", fontSize = 12.sp, color = TextMuted) },
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextCode),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = ElectricBlue,
          unfocusedBorderColor = DarkBorder,
          cursorColor = ElectricBlueGlow
        )
      )
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      TextButton(onClick = onCancel) { Text("Cancel", color = TextMuted, fontSize = 12.sp) }
      Spacer(Modifier.width(6.dp))
      Button(
        onClick = onSave,
        modifier = Modifier.testTag("btn_save_stage_${kind.name.lowercase()}"),
        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
      ) {
        Text("Save", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

@Composable
private fun StageStatusChip(state: BuildStageState) {
  val bg: Color
  val fg: Color
  val label: String
  when (state.status) {
    BuildStageStatus.NOT_CONFIGURED -> {
      bg = DarkSurfaceElevated; fg = TextMuted; label = "Not configured"
    }
    BuildStageStatus.IDLE -> {
      bg = DarkSurfaceElevated; fg = TextSecondary; label = "Ready"
    }
    BuildStageStatus.RUNNING -> {
      bg = ElectricBlue.copy(alpha = 0.15f); fg = ElectricBlueGlow
      label = "Running · ${runningElapsed(state.startedAt)}"
    }
    BuildStageStatus.PASSED -> {
      bg = TerminalGreenBg.copy(alpha = 0.5f); fg = TerminalGreen
      label = "Passed" + (state.durationMs?.let { " · ${formatDurationMs(it)}" } ?: "")
    }
    BuildStageStatus.FAILED -> {
      bg = DangerRedBg.copy(alpha = 0.5f); fg = DangerRed
      label = "Failed · exit ${state.exitCode ?: "?"}"
    }
    BuildStageStatus.STOPPED -> {
      bg = WarningAmberBg.copy(alpha = 0.5f); fg = WarningAmber; label = "Stopped"
    }
  }
  Box(
    Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(bg)
      .padding(horizontal = 6.dp, vertical = 3.dp)
  ) {
    Text(label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun PipelineActions(
  pipelineRunning: Boolean,
  nonRunRunning: Boolean,
  anyRunning: Boolean,
  hasCommands: Boolean,
  editable: Boolean,
  onRunPipeline: () -> Unit,
  onStopAll: () -> Unit
) {
  Column {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(
        onClick = onRunPipeline,
        enabled = editable && hasCommands && !pipelineRunning && !nonRunRunning,
        modifier = Modifier
          .weight(1f)
          .height(42.dp)
          .testTag("btn_run_pipeline"),
        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
        shape = RoundedCornerShape(8.dp)
      ) {
        if (pipelineRunning) {
          CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 1.5.dp)
          Spacer(Modifier.width(8.dp))
          Text("Pipeline running…", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        } else {
          Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text("Run Pipeline", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
      }
      OutlinedButton(
        onClick = onStopAll,
        enabled = anyRunning || pipelineRunning,
        modifier = Modifier
          .weight(1f)
          .height(42.dp)
          .testTag("btn_stop_all"),
        border = BorderStroke(1.dp, DarkBorder),
        shape = RoundedCornerShape(8.dp)
      ) {
        Icon(Icons.Default.Stop, contentDescription = null, tint = DangerRed, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Stop All", fontSize = 12.sp, color = TextPrimary)
      }
    }
    Spacer(Modifier.height(4.dp))
    Text(
      "Install → build → test run in order and stop at the first failure; the run server then starts and stays live.",
      color = TextMuted,
      fontSize = 10.sp
    )
  }
}

@Composable
private fun ConsoleCard(logs: List<BuildLogLine>, onClear: () -> Unit) {
  var filter by remember { mutableStateOf<BuildStageKind?>(null) }
  val filtered = remember(logs, filter) {
    if (filter == null) logs else logs.filter { it.stage == filter }
  }
  val stagesWithLogs = remember(logs) {
    BuildStageKind.entries.filter { kind -> logs.any { it.stage == kind } }
  }
  val listState = rememberLazyListState()
  val stickToBottom by remember { derivedStateOf { !listState.canScrollForward } }
  LaunchedEffect(filtered.size) {
    if (filtered.isNotEmpty() && stickToBottom) listState.scrollToItem(filtered.size - 1)
  }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
    colors = CardDefaults.cardColors(containerColor = DarkSurface)
  ) {
    Column(Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Outlined.Terminal, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(8.dp))
          Text("Output Log", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        TextButton(
          onClick = onClear,
          enabled = logs.isNotEmpty(),
          contentPadding = PaddingValues(horizontal = 8.dp),
          modifier = Modifier.height(28.dp)
        ) {
          Text("Clear", color = TextSecondary, fontSize = 11.sp)
        }
      }
      if (stagesWithLogs.isNotEmpty()) {
        Spacer(Modifier.height(2.dp))
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
        ) {
          FilterChip(
            selected = filter == null,
            onClick = { filter = null },
            label = { Text("All", fontSize = 10.sp) },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = ElectricBlue.copy(alpha = 0.3f),
              selectedLabelColor = ElectricBlueGlow
            )
          )
          stagesWithLogs.forEach { kind ->
            FilterChip(
              selected = filter == kind,
              onClick = { filter = if (filter == kind) null else kind },
              label = { Text(kind.label, fontSize = 10.sp) },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = ElectricBlue.copy(alpha = 0.3f),
                selectedLabelColor = ElectricBlueGlow
              )
            )
          }
        }
      }
      Spacer(Modifier.height(8.dp))
      Box(
        Modifier
          .fillMaxWidth()
          .height(230.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(DarkBackground)
          .border(1.dp, DarkBorderSubtle, RoundedCornerShape(8.dp))
          .padding(8.dp)
          .testTag("console_log")
      ) {
        if (filtered.isEmpty()) {
          Text("Run a stage to stream its output here.", color = TextMuted, fontSize = 11.sp)
        } else {
          LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { line -> LogRow(line) }
          }
        }
      }
    }
  }
}

@Composable
private fun LogRow(line: BuildLogLine) {
  val prefix = line.stage?.let { "[${it.label.lowercase()}] " } ?: ""
  Text(
    text = prefix + line.text,
    color = toneColor(line.tone),
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 1.dp)
  )
}

@Composable
private fun PreviewCard(
  config: BuildRunConfig?,
  runState: BuildStageState?,
  endpoints: List<PreviewEndpoint>,
  onOpen: (String) -> Unit
) {
  val configured = config?.isConfigured(BuildStageKind.RUN) == true
  val running = runState?.status == BuildStageStatus.RUNNING

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
    colors = CardDefaults.cardColors(containerColor = DarkSurface)
  ) {
    Column(Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (running) TerminalGreen else TextMuted)
        )
        Spacer(Modifier.width(8.dp))
        Text("Live Preview", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
      }
      Spacer(Modifier.height(8.dp))
      when {
        !configured -> Text(
          "Configure a Run command to start a server and preview it here.",
          color = TextMuted,
          fontSize = 11.sp
        )
        endpoints.isNotEmpty() -> {
          Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState())
          ) {
            endpoints.take(3).forEach { endpoint ->
              Box(
                Modifier
                  .clip(RoundedCornerShape(6.dp))
                  .background(DarkBackground)
                  .border(1.dp, CyanAccent.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                  .padding(horizontal = 8.dp, vertical = 4.dp)
              ) {
                Text(endpoint.url, color = CyanAccent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
              }
            }
          }
          Spacer(Modifier.height(10.dp))
          Button(
            onClick = { onOpen(endpoints.first().url) },
            enabled = running,
            modifier = Modifier
              .fillMaxWidth()
              .height(38.dp)
              .testTag("btn_open_preview"),
            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
            shape = RoundedCornerShape(8.dp)
          ) {
            Icon(Icons.Outlined.Preview, contentDescription = "Preview", modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Open Preview", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
          }
          if (!running) {
            Spacer(Modifier.height(4.dp))
            Text(
              "The server is not running — start the Run stage to open the preview.",
              color = TextMuted,
              fontSize = 10.sp
            )
          }
        }
        running -> Row(verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(Modifier.size(13.dp), color = ElectricBlueGlow, strokeWidth = 1.5.dp)
          Spacer(Modifier.width(8.dp))
          Text("Waiting for the server endpoint…", color = TextSecondary, fontSize = 11.sp)
        }
        else -> Text(
          "Start the Run stage — the endpoint appears here and opens automatically.",
          color = TextMuted,
          fontSize = 11.sp
        )
      }
    }
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewModal(url: String, onDismiss: () -> Unit) {
  val context = LocalContext.current
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loadError by remember { mutableStateOf(false) }

    Surface(color = DarkBackground, modifier = Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        Row(
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Box(
            Modifier
              .size(8.dp)
              .clip(CircleShape)
              .background(if (loadError) DangerRed else TerminalGreen)
          )
          Spacer(Modifier.width(8.dp))
          Text(
            text = url,
            color = TextPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
          )
          IconButton(
            onClick = {
              loadError = false
              webView?.reload()
            },
            modifier = Modifier.size(32.dp)
          ) {
            Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = TextSecondary, modifier = Modifier.size(17.dp))
          }
          IconButton(
            onClick = {
              runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            },
            modifier = Modifier.size(32.dp)
          ) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open in browser", tint = TextSecondary, modifier = Modifier.size(17.dp))
          }
          IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close preview", tint = TextMuted, modifier = Modifier.size(17.dp))
          }
        }
        HorizontalDivider(color = DarkBorder)
        Box(Modifier.weight(1f).fillMaxWidth()) {
          AndroidView(
            factory = { ctx ->
              WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                  override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url
                    val scheme = target.scheme ?: return false
                    return if (scheme == "http" || scheme == "https") {
                      view.loadUrl(target.toString())
                      true
                    } else {
                      true
                    }
                  }

                  override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) loadError = true
                  }
                }
                webView = this
                loadUrl(url)
              }
            },
            update = { view ->
              if (view.url != null && view.url != url) {
                loadError = false
                view.loadUrl(url)
              }
            },
            modifier = Modifier.fillMaxSize(),
            // A WebView holds a renderer; dismissed means destroyed.
            onRelease = { view ->
              webView = null
              view.destroy()
            }
          )
          if (loadError) {
            Box(
              Modifier
                .fillMaxSize()
                .background(DarkBackground.copy(alpha = 0.94f)),
              contentAlignment = Alignment.Center
            ) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't load $url", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("The server may still be starting, or it stopped.", color = TextMuted, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                Button(
                  onClick = {
                    loadError = false
                    webView?.reload()
                  },
                  colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                  shape = RoundedCornerShape(8.dp)
                ) {
                  Text("Retry", fontSize = 12.sp)
                }
              }
            }
          }
        }
      }
    }

    DisposableEffect(Unit) {
      onDispose { webView?.destroy() }
    }
  }
}

@Composable
private fun runningElapsed(startedAt: Long): String {
  var now by remember(startedAt) { mutableStateOf(System.currentTimeMillis()) }
  LaunchedEffect(startedAt) {
    while (true) {
      now = System.currentTimeMillis()
      delay(1000L)
    }
  }
  val seconds = ((now - startedAt) / 1000).coerceAtLeast(0)
  return when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
  }
}

private fun stageIcon(kind: BuildStageKind) = when (kind) {
  BuildStageKind.INSTALL -> Icons.Outlined.Download
  BuildStageKind.BUILD -> Icons.Outlined.Build
  BuildStageKind.TEST -> Icons.Outlined.Science
  BuildStageKind.RUN -> Icons.Default.PlayArrow
}

private fun commandPlaceholder(kind: BuildStageKind): String = when (kind) {
  BuildStageKind.INSTALL -> "e.g. npm install"
  BuildStageKind.BUILD -> "e.g. npm run build"
  BuildStageKind.TEST -> "e.g. npm test"
  BuildStageKind.RUN -> "e.g. npm run dev"
}

private fun toneColor(tone: LogTone): Color = when (tone) {
  LogTone.NORMAL -> TextCode
  LogTone.ERROR -> Color(0xFFF87171)
  LogTone.SUCCESS -> TerminalGreen
  LogTone.INFO -> ElectricBlueGlow
  LogTone.MUTED -> TextMuted
}

private fun formatDurationMs(ms: Long): String =
  if (ms < 10_000) String.format(Locale.US, "%.1fs", ms / 1000.0)
  else String.format(Locale.US, "%dm %02ds", ms / 60_000, (ms / 1000) % 60)

private fun relativeTime(timestamp: Long): String {
  if (timestamp <= 0) return "just now"
  val delta = System.currentTimeMillis() - timestamp
  return when {
    delta < 60_000 -> "just now"
    delta < 3_600_000 -> "${delta / 60_000}m ago"
    delta < 86_400_000 -> "${delta / 3_600_000}h ago"
    else -> "${delta / 86_400_000}d ago"
  }
}
