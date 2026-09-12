package com.agentisco.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.agentisco.agent.model.PermissionMode
import com.agentisco.agent.model.ToolType
import com.agentisco.core.model.AppDestination
import com.agentisco.data.model.Project
import com.agentisco.ui.AgentStreamItem
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.theme.*
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun AgentScreen(
  viewModel: WorkspaceViewModel,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  val activeProject by viewModel.activeProject.collectAsState()
  val selectedModel by viewModel.selectedModel.collectAsState()
  val providers by viewModel.providers.collectAsState()
  val isWorking by viewModel.isAgentWorking.collectAsState()
  val stream by viewModel.agentStream.collectAsState()
  val permissions by viewModel.permissions.collectAsState()
  val allModels by viewModel.aiModels.collectAsState()

  var promptText by remember { mutableStateOf("") }
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()

  // Auto-follow: scroll as new events arrive, but only while the user is at the
  // bottom; otherwise they keep their position and get a "Jump to latest" chip.
  val autoFollow by remember { derivedStateOf {
    val info = listState.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
    val total = info.totalItemsCount
    total <= 2 || last >= total - 3
  } }
  LaunchedEffect(stream) {
    if (stream.isNotEmpty() && autoFollow) {
      listState.animateScrollToItem(stream.size) // bottom spacer item
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
      // Keep the composer usable above the soft keyboard.
      .imePadding()
  ) {
    // Compact agent header: live agent state only — model selection lives in the composer.
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.End,
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (isWorking) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(7.dp)
              .clip(CircleShape)
              .background(ElectricBlueGlow)
          )
          Spacer(modifier = Modifier.width(5.dp))
          Text("Working…", color = ElectricBlueGlow, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
          Spacer(modifier = Modifier.width(8.dp))
          IconButton(
            onClick = { viewModel.cancelAgent() },
            modifier = Modifier
              .size(30.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(DangerRed.copy(alpha = 0.15f))
              .border(1.dp, DangerRed, RoundedCornerShape(8.dp))
              .testTag("btn_stop_agent")
          ) {
            Icon(Icons.Default.Stop, contentDescription = "Stop agent", tint = DangerRed, modifier = Modifier.size(15.dp))
          }
        }
      } else {
        Text("Ready", color = TextMuted, fontSize = 11.sp)
      }
    }

    // Live agent event stream (the primary surface).
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        if (stream.isEmpty()) {
          item(key = "empty") {
            AgentEmptyState(
              project = activeProject,
              onSuggestion = { promptText = it }
            )
          }
        } else {
          items(stream, key = { it.id }) { item ->
            AgentStreamItemView(
              item = item,
              onAllow = { viewModel.resolveApproval(true) },
              onDeny = { viewModel.resolveApproval(false) },
              onNavigate = onNavigate
            )
          }
        }
        item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(12.dp)) }
      }

      // Don't fight the user's scroll: offer a jump control instead.
      if (stream.isNotEmpty()) {
        val showJump by remember(stream.size) { derivedStateOf {
          val info = listState.layoutInfo
          val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
          last < stream.size - 1
        } }
        if (showJump) {
          Surface(
            onClick = {
              scope.launch { listState.animateScrollToItem(stream.size) }
            },
            shape = CircleShape,
            color = DarkSurfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .padding(bottom = 10.dp)
              .testTag("btn_jump_to_latest")
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(Icons.Default.Check, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(12.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Jump to latest", color = TextSecondary, fontSize = 11.sp)
            }
          }
        }
      }
    }

    // Sticky agent composer with inline configuration row.
    AgentComposer(
      viewModel = viewModel,
      selectedModel = selectedModel,
      providers = providers,
      models = allModels,
      permissions = permissions,
      promptText = promptText,
      onPromptChange = { promptText = it },
      isWorking = isWorking,
      onSend = {
        val p = promptText.trim()
        if (p.isNotEmpty()) {
          viewModel.runAgentTask(p)
          promptText = ""
        }
      },
      onStop = { viewModel.cancelAgent() }
    )
  }
}

@Composable
private fun AgentEmptyState(project: Project, onSuggestion: (String) -> Unit) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Spacer(modifier = Modifier.height(20.dp))
    Text(
      text = "What are we building?",
      color = TextPrimary,
      fontSize = 20.sp,
      fontWeight = FontWeight.Bold,
      letterSpacing = (-0.3).sp
    )
    Text(
      text = "Describe a task or let the agent navigate ${project.name}",
      color = TextMuted,
      fontSize = 12.sp
    )

    Spacer(modifier = Modifier.height(16.dp))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      listOf(
        "Review the existing code and explain the architecture",
        "Fix bugs in this project",
        "Add a feature and run the tests",
        "Run the build and report failures"
      ).forEach { suggestion ->
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurface)
            .border(1.dp, DarkBorderSubtle, RoundedCornerShape(8.dp))
            .clickable { onSuggestion(suggestion) }
            .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
          Text(suggestion, color = TextSecondary, fontSize = 12.sp)
        }
      }
    }
  }
}

@Composable
private fun AgentStreamItemView(
  item: AgentStreamItem,
  onAllow: () -> Unit,
  onDeny: () -> Unit,
  onNavigate: (AppDestination) -> Unit
) {
  when (item) {
    is AgentStreamItem.Status -> StatusRow(item)
    is AgentStreamItem.AssistantText -> AssistantTextBlock(item)
    is AgentStreamItem.ToolCall -> ToolCallRow(item)
    is AgentStreamItem.Approval -> ApprovalCard(item, onAllow, onDeny)
    is AgentStreamItem.Final -> FinalCard(item, onNavigate)
  }
}

@Composable
private fun StatusRow(item: AgentStreamItem.Status) {
  Row(verticalAlignment = Alignment.Top) {
    if (item.running) {
      val transition = rememberInfiniteTransition(label = "thinking")
      val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "thinking-alpha"
      )
      Icon(
        Icons.Default.Psychology,
        contentDescription = "Thinking",
        tint = CyanAccent.copy(alpha = alpha),
        modifier = Modifier.size(15.dp)
      )
    } else {
      Icon(
        Icons.Default.Psychology,
        contentDescription = "Status",
        tint = CyanAccent.copy(alpha = 0.6f),
        modifier = Modifier.size(15.dp)
      )
    }
    Spacer(modifier = Modifier.width(8.dp))
    Column {
      Text(
        text = if (item.running) "Thinking" else "Status",
        color = TextMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold
      )
      Text(item.text, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
    }
  }
}

@Composable
private fun AssistantTextBlock(item: AgentStreamItem.AssistantText) {
  Row(verticalAlignment = Alignment.Top) {
    Icon(
      Icons.Default.AutoAwesome,
      contentDescription = null,
      tint = ElectricBlueGlow,
      modifier = Modifier
        .size(14.dp)
        .padding(top = 2.dp)
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text(
      text = item.text + if (item.running) "▍" else "",
      color = TextCode,
      fontSize = 13.sp,
      lineHeight = 18.sp
    )
  }
}

@Composable
private fun ToolCallRow(item: AgentStreamItem.ToolCall) {
  var expanded by remember(item.id) { mutableStateOf(false) }
  val (verb, target) = friendlyToolLabel(item.name, item.argsJson)
  val icon = toolIcon(item.name)
  val iconColor = toolColor(item.name)

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(DarkSurface.copy(alpha = 0.6f))
      .border(
        1.dp,
        when {
          item.running -> ElectricBlue.copy(alpha = 0.5f)
          item.success == false -> DangerRed.copy(alpha = 0.5f)
          else -> DarkBorderSubtle
        },
        RoundedCornerShape(10.dp)
      )
      .clickable { if (item.detail.isNotBlank() || item.argsJson.isNotBlank()) expanded = !expanded }
      .padding(horizontal = 10.dp, vertical = 8.dp)
      .testTag("stream_tool_${item.name}")
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Icon(icon, contentDescription = verb, tint = iconColor, modifier = Modifier.size(15.dp))
      Spacer(modifier = Modifier.width(8.dp))
      Text(verb, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      if (target.isNotBlank()) {
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          target,
          color = TextSecondary,
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false)
        )
      }
      Spacer(modifier = Modifier.weight(1f))
      when {
        item.running -> CircularProgressIndicator(modifier = Modifier.size(12.dp), color = ElectricBlueGlow, strokeWidth = 1.8.dp)
        item.success == true -> Icon(Icons.Default.CheckCircle, contentDescription = "Done", tint = TerminalGreen, modifier = Modifier.size(13.dp))
        item.success == false -> Icon(Icons.Default.Close, contentDescription = "Failed", tint = DangerRed, modifier = Modifier.size(13.dp))
      }
    }

    // Inline error/result one-liner
    if (!item.running) {
      Spacer(modifier = Modifier.height(3.dp))
      Text(
        text = item.summary,
        color = if (item.success == false) DangerRed.copy(alpha = 0.9f) else TextMuted,
        fontSize = 10.sp,
        maxLines = if (expanded) Int.MAX_VALUE else 1,
        overflow = TextOverflow.Ellipsis
      )
    }

    if (expanded) {
      Spacer(modifier = Modifier.height(6.dp))
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(6.dp))
          .background(DarkBackground)
          .padding(8.dp)
      ) {
        if (item.argsJson.isNotBlank() && item.argsJson != "{}") {
          Text("Arguments", color = TextMuted, fontSize = 9.sp)
          Text(prettyJson(item.argsJson), color = CyanAccent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
          Spacer(modifier = Modifier.height(6.dp))
        }
        if (item.detail.isNotBlank()) {
          Text("Output", color = TextMuted, fontSize = 9.sp)
          Text(item.detail, color = TextCode, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        item.exitCode?.let {
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            "exit code: $it",
            color = if (it == 0) TerminalGreen else DangerRed,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
          )
        }
      }
    }
  }
}

@Composable
private fun ApprovalCard(item: AgentStreamItem.Approval, onAllow: () -> Unit, onDeny: () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(WarningAmber.copy(alpha = 0.08f))
      .border(1.dp, WarningAmber, RoundedCornerShape(10.dp))
      .padding(12.dp)
      .testTag("stream_approval")
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .size(7.dp)
          .clip(CircleShape)
          .background(WarningAmber)
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(item.title, color = WarningAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(item.command, color = TextCode, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    Spacer(modifier = Modifier.height(4.dp))
    Text(item.impact, color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
    Spacer(modifier = Modifier.height(8.dp))
    if (item.resolved) {
      Text(
        if (item.allowed) "✓ Allowed" else "✗ Denied",
        color = if (item.allowed) TerminalGreen else DangerRed,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
      )
    } else {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = onAllow,
          colors = ButtonDefaults.buttonColors(containerColor = TerminalGreen),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
          modifier = Modifier.height(30.dp).testTag("btn_allow_tool")
        ) { Text("Allow", color = Color.White, fontSize = 11.sp) }
        Button(
          onClick = onDeny,
          colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
          modifier = Modifier.height(30.dp).testTag("btn_deny_tool")
        ) { Text("Deny", color = Color.White, fontSize = 11.sp) }
      }
    }
  }
}

@Composable
private fun FinalCard(item: AgentStreamItem.Final, onNavigate: (AppDestination) -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(DarkSurface)
      .border(
        1.dp,
        if (item.success) TerminalGreen.copy(alpha = 0.6f) else DangerRed,
        RoundedCornerShape(12.dp)
      )
      .padding(12.dp)
      .testTag("stream_final_response")
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        if (item.success) Icons.Default.CheckCircle else Icons.Default.Close,
        contentDescription = null,
        tint = if (item.success) TerminalGreen else DangerRed,
        modifier = Modifier.size(15.dp)
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        if (item.success) "Completed" else "Failed",
        color = if (item.success) TerminalGreen else DangerRed,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold
      )
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(item.text, color = TextPrimary, fontSize = 13.sp, lineHeight = 18.sp)

    if (item.success) {
      Spacer(modifier = Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniAction("Review changes") { onNavigate(AppDestination.DIFF) }
        MiniAction("Open files") { onNavigate(AppDestination.FILES) }
      }
    }
  }
}

@Composable
private fun MiniAction(label: String, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(DarkSurfaceElevated)
      .border(1.dp, DarkBorderSubtle, RoundedCornerShape(6.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 4.dp)
  ) {
    Text(label, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun AgentComposer(
  viewModel: WorkspaceViewModel,
  selectedModel: com.agentisco.settings.model.AIModel?,
  providers: List<com.agentisco.settings.model.AIProvider>,
  models: List<com.agentisco.settings.model.AIModel>,
  permissions: com.agentisco.agent.model.AgentPermissions,
  promptText: String,
  onPromptChange: (String) -> Unit,
  isWorking: Boolean,
  onSend: () -> Unit,
  onStop: () -> Unit
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = DarkSurface,
    border = androidx.compose.foundation.BorderStroke(0.5.dp, DarkBorder)
  ) {
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
      Row(verticalAlignment = Alignment.Bottom) {
        TextField(
          value = promptText,
          onValueChange = onPromptChange,
          placeholder = { Text("Ask for follow-up changes…", color = TextMuted, fontSize = 13.sp) },
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp, max = 120.dp)
            .testTag("agent_prompt_input"),
          colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary
          ),
          textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 18.sp)
        )

        if (isWorking) {
          IconButton(
            onClick = onStop,
            modifier = Modifier
              .size(40.dp)
              .clip(CircleShape)
              .background(DangerRed)
              .testTag("btn_composer_stop")
          ) {
            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.White, modifier = Modifier.size(18.dp))
          }
        } else {
          IconButton(
            onClick = onSend,
            enabled = promptText.isNotBlank(),
            modifier = Modifier
              .size(40.dp)
              .clip(CircleShape)
              .background(if (promptText.isBlank()) DarkSurfaceElevated else ElectricBlue)
              .testTag("btn_send_agent_prompt")
          ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(17.dp))
          }
        }
      }

      Spacer(modifier = Modifier.height(2.dp))

      // Inline configuration row: model, file-edit policy, terminal policy.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Model dropdown grouped by provider (desktop-reference style).
        val providerName = selectedModel?.let { m -> providers.firstOrNull { it.id == m.providerId }?.name }
        ConfigDropdown(
          label = selectedModel?.displayName ?: "Select model",
          sublabel = providerName,
          tint = if (selectedModel == null) TextMuted else ElectricBlueGlow,
          options = buildList {
            providers.forEach { provider ->
              add(DropdownOption(header = true, label = provider.name))
              models.filter { it.providerId == provider.id }.forEach { m ->
                add(DropdownOption(label = m.displayName, sublabel = m.modelId, tag = "${m.id}|${m.displayName}"))
              }
            }
            if (models.isEmpty()) add(DropdownOption(header = true, label = "No models configured"))
            add(DropdownOption(label = "Configure providers…", configure = true))
          },
          onPick = { option ->
            if (option.configure) {
              viewModel.navigateTo(AppDestination.SETTINGS)
            } else {
              val recordId = option.tag?.substringBefore("|")
              models.firstOrNull { it.id == recordId }?.let { viewModel.selectModel(it) }
            }
          },
          modifier = Modifier.testTag("composer_model_selector")
        )

        // File editing policy dropdown
        ConfigDropdown(
          label = when (permissions.fileEditing) {
            PermissionMode.ALWAYS_ASK -> "Edits: ask"
            PermissionMode.AUTO_APPROVE_PROJECT -> "Edits: auto"
            PermissionMode.NEVER_ALLOW -> "Edits: off"
            else -> "Edits: ask"
          },
          tint = if (permissions.fileEditing == PermissionMode.NEVER_ALLOW) DangerRed else TerminalGreen,
          options = listOf(
            DropdownOption(label = "Ask before editing", tag = PermissionMode.ALWAYS_ASK.name),
            DropdownOption(label = "Auto-approve in workspace", tag = PermissionMode.AUTO_APPROVE_PROJECT.name),
            DropdownOption(label = "Never edit files", tag = PermissionMode.NEVER_ALLOW.name)
          ),
          onPick = { option ->
            option.tag?.let { PermissionMode.valueOf(it) }?.let { mode ->
              viewModel.updatePermissions { it.copy(fileEditing = mode) }
            }
          }
        )

        // Terminal execution strategy dropdown
        ConfigDropdown(
          label = when (permissions.terminalCommands) {
            PermissionMode.ALLOW_ALL -> "Terminal: all"
            PermissionMode.ALLOW_SAFE -> "Terminal: safe"
            PermissionMode.NEVER_ALLOW -> "Terminal: off"
            else -> "Terminal: ask"
          },
          tint = if (permissions.terminalCommands == PermissionMode.ALLOW_ALL) WarningAmber else TerminalGreen,
          options = listOf(
            DropdownOption(label = "Ask before running", tag = PermissionMode.ALWAYS_ASK.name),
            DropdownOption(label = "Allow safe commands", tag = PermissionMode.ALLOW_SAFE.name),
            DropdownOption(label = "Allow all commands", tag = PermissionMode.ALLOW_ALL.name),
            DropdownOption(label = "Never run commands", tag = PermissionMode.NEVER_ALLOW.name)
          ),
          onPick = { option ->
            option.tag?.let { PermissionMode.valueOf(it) }?.let { mode ->
              viewModel.updatePermissions { it.copy(terminalCommands = mode) }
            }
          }
        )
      }
    }
  }
}

data class DropdownOption(
  val label: String,
  val sublabel: String? = null,
  val tag: String? = null,
  val header: Boolean = false,
  val configure: Boolean = false
)

@Composable
private fun ConfigDropdown(
  label: String,
  options: List<DropdownOption>,
  onPick: (DropdownOption) -> Unit,
  modifier: Modifier = Modifier,
  sublabel: String? = null,
  tint: Color = TextSecondary
) {
  var expanded by remember { mutableStateOf(false) }
  Box(modifier = modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .clip(RoundedCornerShape(6.dp))
        .clickable { expanded = true }
        .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
      Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
          Spacer(modifier = Modifier.width(3.dp))
          Text("▾", color = TextMuted, fontSize = 9.sp)
        }
        sublabel?.let {
          Text(it, color = TextMuted, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      containerColor = DarkSurfaceElevated,
      modifier = Modifier.heightIn(max = 360.dp)
    ) {
      options.forEach { option ->
        if (option.header) {
          Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(option.label, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
          }
        } else {
          DropdownMenuItem(
            text = {
              Column {
                Text(
                  option.label,
                  color = if (option.configure) ElectricBlueGlow else TextPrimary,
                  fontSize = 13.sp
                )
                option.sublabel?.let {
                  Text(it, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
              }
            },
            onClick = {
              expanded = false
              onPick(option)
            }
          )
        }
      }
    }
  }
}

// ---- helpers ----

private fun friendlyToolLabel(name: String, argsJson: String): Pair<String, String> {
  val args = runCatching { JSONObject(argsJson) }.getOrNull()
  fun arg(key: String) = args?.optString(key).orEmpty().take(80)
  return when (name) {
    "read_file" -> "Reading" to arg("path")
    "write_file" -> "Writing" to arg("path")
    "edit_file" -> "Editing" to arg("path")
    "create_file" -> "Creating" to arg("path")
    "delete_file" -> "Deleting" to arg("path")
    "move_file" -> "Moving" to arg("new_path")
    "list_files" -> "Listing" to arg("path").ifBlank { "workspace" }
    "search_files" -> "Searching" to arg("query")
    "run_command" -> "Running" to arg("command")
    "write_terminal_input" -> "Terminal input" to ""
    "interrupt_terminal" -> "Interrupting" to ""
    "git_status" -> "Git status" to ""
    "git_diff" -> "Git diff" to arg("path")
    "git_stage" -> "Staging" to arg("path").ifBlank { "all changes" }
    "git_unstage" -> "Unstaging" to ""
    "git_commit" -> "Committing" to arg("message")
    "build" -> "Building" to arg("args")
    "test" -> "Testing" to arg("args")
    "run" -> "Starting" to "dev server"
    else -> name to ""
  }
}

private fun toolIcon(name: String): ImageVector = when (toolTypeForUi(name)) {
  ToolType.READ_FILE -> Icons.Outlined.Description
  ToolType.SEARCH -> Icons.Outlined.Search
  ToolType.TERMINAL -> Icons.Outlined.Terminal
  ToolType.EDIT_FILE -> Icons.Outlined.Edit
  ToolType.GIT -> Icons.Outlined.Commit
  ToolType.BUILD -> Icons.Outlined.Build
}

private fun toolColor(name: String): Color = when (toolTypeForUi(name)) {
  ToolType.READ_FILE -> CyanAccent
  ToolType.SEARCH -> WarningAmber
  ToolType.TERMINAL -> TerminalGreen
  ToolType.EDIT_FILE -> ElectricBlueGlow
  ToolType.GIT -> IndigoAccent
  ToolType.BUILD -> WarningAmber
}

private fun toolTypeForUi(name: String): ToolType = when {
  name.startsWith("git_") -> ToolType.GIT
  name == "run_command" || name == "build" || name == "test" || name == "run" ||
    name.startsWith("terminal") -> ToolType.TERMINAL
  name == "write_file" || name == "create_file" || name == "move_file" || name == "delete_file" -> ToolType.EDIT_FILE
  name == "search_files" -> ToolType.SEARCH
  name == "build" -> ToolType.BUILD
  else -> ToolType.READ_FILE
}

private fun prettyJson(raw: String): String = runCatching {
  JSONObject(raw).toString(2)
}.getOrDefault(raw)
