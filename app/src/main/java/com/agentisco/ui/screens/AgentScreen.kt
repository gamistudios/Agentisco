@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.agentisco.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.ContentCopy
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import com.agentisco.data.local.chat.AgentSessionEntity
import com.agentisco.ui.AgentTurnItem
import com.agentisco.ui.ActionBlock
import com.agentisco.ui.ApprovalBlock
import com.agentisco.ui.ChatItem
import com.agentisco.ui.ErrorBlock
import com.agentisco.ui.ReasoningBlock
import com.agentisco.ui.TextBlock
import com.agentisco.ui.TurnBlock
import com.agentisco.ui.TurnStatus
import com.agentisco.ui.UserMessageItem
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.components.MarkdownText
import com.agentisco.ui.theme.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

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
  val chatItems by viewModel.chatItems.collectAsState()
  val permissions by viewModel.permissions.collectAsState()
  val allModels by viewModel.aiModels.collectAsState()
  val sessions by viewModel.chatSessions.collectAsState()
  val activeSession by viewModel.activeChatSession.collectAsState()

  var promptText by remember { mutableStateOf("") }
  var showSessionSheet by remember { mutableStateOf(false) }
  var renameTarget by remember { mutableStateOf<AgentSessionEntity?>(null) }
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()

  // Auto-follow: scroll as new activity arrives, but only while the user is at
  // the bottom; otherwise they keep their position and get a "Jump to latest" chip.
  val autoFollow by remember { derivedStateOf {
    val info = listState.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
    val total = info.totalItemsCount
    total <= 2 || last >= total - 3
  } }
  LaunchedEffect(chatItems) {
    if (chatItems.isNotEmpty() && autoFollow) {
      listState.animateScrollToItem(chatItems.size) // bottom spacer item
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
      // Keep the composer usable above the soft keyboard.
      .imePadding()
  ) {
    // Session bar: current conversation + session management.
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 10.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Current session selector (opens the session sheet).
      Surface(
        onClick = { showSessionSheet = true },
        shape = RoundedCornerShape(8.dp),
        color = DarkSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle),
        modifier = Modifier
          .weight(1f)
          .testTag("btn_sessions")
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            Icons.AutoMirrored.Filled.Chat,
            contentDescription = null,
            tint = ElectricBlueGlow,
            modifier = Modifier.size(13.dp)
          )
          Spacer(modifier = Modifier.width(7.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = activeSession?.title ?: "New conversation",
              color = TextPrimary,
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = activeSession?.let { "${sessions.size} session${if (sessions.size == 1) "" else "s"} · ${relativeTime(it.updatedAt)}" }
                ?: "Start chatting to create one",
              color = TextMuted,
              fontSize = 9.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
          Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = "Switch session",
            tint = TextMuted,
            modifier = Modifier.size(14.dp)
          )
        }
      }

      // New chat button.
      IconButton(
        onClick = { viewModel.createChatSession() },
        enabled = !isWorking,
        modifier = Modifier
          .size(32.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(DarkSurface)
          .border(1.dp, DarkBorderSubtle, RoundedCornerShape(8.dp))
          .testTag("btn_new_session")
      ) {
        Icon(Icons.Default.Add, contentDescription = "New session", tint = TextSecondary, modifier = Modifier.size(16.dp))
      }

      // Live agent state + stop.
      if (isWorking) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(7.dp)
              .clip(CircleShape)
              .background(ElectricBlueGlow)
          )
          Spacer(modifier = Modifier.width(4.dp))
          IconButton(
            onClick = { viewModel.cancelAgent() },
            modifier = Modifier
              .size(32.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(DangerRed.copy(alpha = 0.15f))
              .border(1.dp, DangerRed, RoundedCornerShape(8.dp))
              .testTag("btn_stop_agent")
          ) {
            Icon(Icons.Default.Stop, contentDescription = "Stop agent", tint = DangerRed, modifier = Modifier.size(15.dp))
          }
        }
      }
    }

    // Conversation (the primary surface).
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        if (chatItems.isEmpty()) {
          item(key = "empty") {
            AgentEmptyState(
              project = activeProject,
              hasHistory = activeSession != null,
              onSuggestion = { promptText = it }
            )
          }
        } else {
          items(chatItems, key = { it.id }) { item ->
            when (item) {
              is UserMessageItem -> UserBubble(item)
              is AgentTurnItem -> AgentTurnCard(
                item = item,
                onAllow = { viewModel.resolveApproval(true) },
                onDeny = { viewModel.resolveApproval(false) },
                onRetry = { viewModel.retryAgentTurn(item.id) },
                onCancelTool = { viewModel.cancelToolCall(it) },
                onRetryTool = { viewModel.resolveToolCancellation(it, retry = true) },
                onContinueTool = { viewModel.resolveToolCancellation(it, retry = false) },
                onNavigate = onNavigate
              )
            }
          }
        }
        item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(12.dp)) }
      }

      // Don't fight the user's scroll: offer a jump control instead.
      if (chatItems.isNotEmpty()) {
        val showJump by remember(chatItems.size) { derivedStateOf {
          val info = listState.layoutInfo
          val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
          last < chatItems.size
        } }
        if (showJump) {
          Surface(
            onClick = {
              scope.launch { listState.animateScrollToItem(chatItems.size) }
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
              Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(12.dp))
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
      onPause = { viewModel.pauseAgent() },
      onStop = { viewModel.cancelAgent() }
    )
  }

  // Session management sheet.
  if (showSessionSheet) {
    ModalBottomSheet(
      onDismissRequest = { showSessionSheet = false },
      containerColor = DarkSurface
    ) {
      SessionSheet(
        sessions = sessions,
        activeSessionId = activeSession?.id,
        isWorking = isWorking,
        onSelect = {
          viewModel.selectChatSession(it.id)
          showSessionSheet = false
        },
        onNew = {
          viewModel.createChatSession()
          showSessionSheet = false
        },
        onRename = { renameTarget = it },
        onDelete = { viewModel.deleteChatSession(it.id) }
      )
    }
  }

  renameTarget?.let { target ->
    var name by remember(target.id) { mutableStateOf(target.title) }
    AlertDialog(
      onDismissRequest = { renameTarget = null },
      containerColor = DarkSurface,
      title = { Text("Rename session", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
      text = {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.renameChatSession(target.id, name)
            renameTarget = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
        ) { Text("Save", fontSize = 12.sp) }
      },
      dismissButton = {
        TextButton(onClick = { renameTarget = null }) { Text("Cancel", color = TextMuted, fontSize = 12.sp) }
      }
    )
  }
}

@Composable
private fun SessionSheet(
  sessions: List<AgentSessionEntity>,
  activeSessionId: String?,
  isWorking: Boolean,
  onSelect: (AgentSessionEntity) -> Unit,
  onNew: () -> Unit,
  onRename: (AgentSessionEntity) -> Unit,
  onDelete: (AgentSessionEntity) -> Unit
) {
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text("Sessions", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
      TextButton(onClick = onNew, enabled = !isWorking) {
        Icon(Icons.Default.Add, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("New chat", color = ElectricBlueGlow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      }
    }
    Spacer(modifier = Modifier.height(4.dp))
    if (sessions.isEmpty()) {
      Text(
        "No sessions yet for this project. Send a prompt to start one.",
        color = TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 16.dp)
      )
    } else {
      Column(
        modifier = Modifier.padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        sessions.forEach { session ->
          val isActive = session.id == activeSessionId
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(if (isActive) DarkSurfaceElevated else Color.Transparent)
              .border(
                1.dp,
                if (isActive) ElectricBlue.copy(alpha = 0.5f) else DarkBorderSubtle,
                RoundedCornerShape(8.dp)
              )
              .clickable(enabled = !isWorking || !isActive) { onSelect(session) }
              .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            SessionStatusDot(session.status)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                session.title,
                color = if (isActive) TextPrimary else TextSecondary,
                fontSize = 12.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                "${relativeTime(session.updatedAt)} · ${session.status}",
                color = TextMuted,
                fontSize = 9.sp
              )
            }
            IconButton(onClick = { onRename(session) }, modifier = Modifier.size(26.dp)) {
              Icon(Icons.Default.Edit, contentDescription = "Rename", tint = TextMuted, modifier = Modifier.size(13.dp))
            }
            IconButton(onClick = { onDelete(session) }, modifier = Modifier.size(26.dp)) {
              Icon(Icons.Default.Delete, contentDescription = "Delete", tint = DangerRed.copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SessionStatusDot(status: String) {
  val color = when (status) {
    "running" -> ElectricBlueGlow
    "completed" -> TerminalGreen
    "failed" -> DangerRed
    "cancelled", "interrupted" -> WarningAmber
    else -> TextMuted
  }
  Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
}

@Composable
private fun UserBubble(item: UserMessageItem) {
  val clipboard = LocalClipboardManager.current
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.End
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 300.dp)
        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 4.dp))
        .background(ElectricBlue.copy(alpha = 0.16f))
        .border(1.dp, ElectricBlue.copy(alpha = 0.4f), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 4.dp))
        .combinedClickable(
          onClick = {},
          onLongClick = { clipboard.setText(AnnotatedString(item.text)) }
        )
        .padding(horizontal = 12.dp, vertical = 8.dp)
        .testTag("chat_user_message")
    ) {
      Text(
        text = item.text,
        color = TextPrimary,
        fontSize = 13.sp,
        lineHeight = 18.sp
      )
      Spacer(modifier = Modifier.height(3.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(relativeTime(item.timestamp), color = TextMuted, fontSize = 8.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
          Icons.Outlined.ContentCopy,
          contentDescription = "Copy message",
          tint = TextMuted,
          modifier = Modifier
            .size(10.dp)
            .clickable { clipboard.setText(AnnotatedString(item.text)) }
        )
      }
    }
  }
}

@Composable
private fun AgentTurnCard(
  item: AgentTurnItem,
  onAllow: () -> Unit,
  onDeny: () -> Unit,
  onRetry: () -> Unit,
  onCancelTool: (String) -> Unit,
  onRetryTool: (String) -> Unit,
  onContinueTool: (String) -> Unit,
  onNavigate: (AppDestination) -> Unit
) {
  val clipboard = LocalClipboardManager.current
  val fullText = item.blocks.filterIsInstance<TextBlock>().joinToString("\n\n") { it.text }
  // The model's final answer (last completed text block of a finished turn)
  // gets the highlighted, copyable response treatment.
  val finalResponseId = if (item.status == TurnStatus.COMPLETED) {
    item.blocks.lastOrNull { it is TextBlock && (it as? TextBlock)?.text?.isNotBlank() == true }?.id
  } else null

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(DarkSurface.copy(alpha = 0.7f))
      .border(
        1.dp,
        when (item.status) {
          TurnStatus.RUNNING -> ElectricBlue.copy(alpha = 0.45f)
          TurnStatus.FAILED -> DangerRed.copy(alpha = 0.5f)
          TurnStatus.PAUSED, TurnStatus.CANCELLED, TurnStatus.INTERRUPTED -> WarningAmber.copy(alpha = 0.4f)
          TurnStatus.COMPLETED -> DarkBorderSubtle
        },
        RoundedCornerShape(12.dp)
      )
      .padding(10.dp)
      .testTag("chat_agent_turn")
  ) {
    // Turn header: identity + status.
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (item.status == TurnStatus.RUNNING) {
        val transition = rememberInfiniteTransition(label = "thinking")
        val alpha by transition.animateFloat(
          initialValue = 0.35f,
          targetValue = 1f,
          animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
          label = "thinking-alpha"
        )
        Icon(
          Icons.Default.AutoAwesome,
          contentDescription = null,
          tint = ElectricBlueGlow.copy(alpha = alpha),
          modifier = Modifier.size(14.dp)
        )
      } else {
        Icon(
          when (item.status) {
            TurnStatus.COMPLETED -> Icons.Default.CheckCircle
            TurnStatus.FAILED -> Icons.Default.Close
            else -> Icons.Default.AutoAwesome
          },
          contentDescription = null,
          tint = when (item.status) {
            TurnStatus.COMPLETED -> TerminalGreen
            TurnStatus.FAILED -> DangerRed
            TurnStatus.RUNNING -> ElectricBlueGlow
            else -> WarningAmber
          },
          modifier = Modifier.size(14.dp)
        )
      }
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = when (item.status) {
          TurnStatus.RUNNING -> "Working"
          TurnStatus.PAUSED -> "Paused"
          TurnStatus.COMPLETED -> "Completed"
          TurnStatus.FAILED -> "Failed"
          TurnStatus.CANCELLED -> "Cancelled"
          TurnStatus.INTERRUPTED -> "Interrupted"
        },
        color = when (item.status) {
          TurnStatus.RUNNING -> ElectricBlueGlow
          TurnStatus.COMPLETED -> TerminalGreen
          TurnStatus.FAILED -> DangerRed
          else -> WarningAmber
        },
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold
      )
      Spacer(modifier = Modifier.weight(1f))
      if (fullText.isNotBlank() && item.status != TurnStatus.RUNNING) {
        Icon(
          Icons.Outlined.ContentCopy,
          contentDescription = "Copy response",
          tint = TextMuted,
          modifier = Modifier
            .size(13.dp)
            .clickable { clipboard.setText(AnnotatedString(fullText)) }
        )
      }
    }

    // Live status line (what the agent is doing right now / why it stopped).
    if (item.statusMessage.isNotBlank() &&
      (item.status == TurnStatus.RUNNING || item.status == TurnStatus.FAILED || item.status == TurnStatus.CANCELLED || item.status == TurnStatus.INTERRUPTED) &&
      item.blocks.none { it is TextBlock && it.text.isNotBlank() }
    ) {
      Spacer(modifier = Modifier.height(6.dp))
      Row(verticalAlignment = Alignment.Top) {
        Icon(
          Icons.Default.Psychology,
          contentDescription = null,
          tint = CyanAccent.copy(alpha = 0.7f),
          modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(item.statusMessage, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
      }
    }

    // Turn blocks in order: streamed text, tool actions, approvals.
    item.blocks.forEach { block ->
      Spacer(modifier = Modifier.height(7.dp))
      when (block) {
        is TextBlock -> {
          if (block.text.isNotBlank()) {
            if (block.id == finalResponseId) {
              ResponseCard(block)
            } else {
              MarkdownText(
                text = block.text,
                streaming = block.streaming,
                modifier = Modifier.fillMaxWidth()
              )
            }
          }
        }
        is ReasoningBlock -> ThinkingBlock(block)
        is ActionBlock -> ToolCallRow(
          item = block,
          onCancelTool = { onCancelTool(block.callId) },
          onRetryTool = { onRetryTool(block.callId) },
          onContinueTool = { onContinueTool(block.callId) }
        )
        is ApprovalBlock -> ApprovalCard(block, onAllow, onDeny)
        is ErrorBlock -> ErrorCard(block, showRetry = item.status == TurnStatus.FAILED, onRetry = onRetry)
      }
    }

    // Resumable states: Resume continues a paused generation from its
    // persisted state; failed turns offer Retry on the error card.
    if (item.status == TurnStatus.PAUSED) {
      Spacer(modifier = Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = onRetry,
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
          modifier = Modifier.height(30.dp).testTag("btn_resume_turn")
        ) {
          Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Resume", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
      }
    }

    // Post-completion navigation affordances.
    if (item.status == TurnStatus.COMPLETED && fullText.isNotBlank()) {
      Spacer(modifier = Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniAction("Review changes") { onNavigate(AppDestination.DIFF) }
        MiniAction("Open files") { onNavigate(AppDestination.FILES) }
      }
    }
  }
}

@Composable
private fun AgentEmptyState(project: com.agentisco.data.model.Project, hasHistory: Boolean, onSuggestion: (String) -> Unit) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Spacer(modifier = Modifier.height(20.dp))
    Text(
      text = if (hasHistory) "Continue where you left off" else "What are we building?",
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolCallRow(
  item: ActionBlock,
  onCancelTool: () -> Unit = {},
  onRetryTool: () -> Unit = {},
  onContinueTool: () -> Unit = {}
) {
  var expanded by remember(item.id) { mutableStateOf(false) }
  val clipboard = LocalClipboardManager.current
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
          item.cancelled -> WarningAmber.copy(alpha = 0.6f)
          item.success == false -> DangerRed.copy(alpha = 0.5f)
          else -> DarkBorderSubtle
        },
        RoundedCornerShape(10.dp)
      )
      .combinedClickable(
        onClick = { if (item.detail.isNotBlank() || item.argsJson.isNotBlank()) expanded = !expanded },
        onLongClick = { clipboard.setText(AnnotatedString(item.detail.ifBlank { item.argsJson })) }
      )
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
        item.running -> {
          CircularProgressIndicator(modifier = Modifier.size(12.dp), color = ElectricBlueGlow, strokeWidth = 1.8.dp)
          // SIGKILL this specific call without stopping the whole task.
          if (item.callId.isNotBlank()) {
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
              onClick = onCancelTool,
              modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .testTag("btn_cancel_tool")
            ) {
              Icon(Icons.Default.Stop, contentDescription = "Cancel this step", tint = DangerRed, modifier = Modifier.size(13.dp))
            }
          }
        }
        item.cancelled -> Icon(
          Icons.Default.Stop,
          contentDescription = "Cancelled",
          tint = WarningAmber,
          modifier = Modifier.size(13.dp)
        )
        item.success == true -> Icon(Icons.Default.CheckCircle, contentDescription = "Done", tint = TerminalGreen, modifier = Modifier.size(13.dp))
        item.success == false -> Icon(Icons.Default.Close, contentDescription = "Failed", tint = DangerRed, modifier = Modifier.size(13.dp))
      }
      if (!item.running && item.detail.isNotBlank()) {
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
          Icons.Outlined.ContentCopy,
          contentDescription = "Copy output",
          tint = TextMuted,
          modifier = Modifier
            .size(12.dp)
            .clickable { clipboard.setText(AnnotatedString(item.detail)) }
        )
      }
    }

    // Inline error/result one-liner
    if (!item.running) {
      Spacer(modifier = Modifier.height(3.dp))
      Text(
        text = if (item.cancelled) "Cancelled by user" else item.summary,
        color = when {
          item.cancelled -> WarningAmber
          item.success == false -> DangerRed.copy(alpha = 0.9f)
          else -> TextMuted
        },
        fontSize = 10.sp,
        maxLines = if (expanded) Int.MAX_VALUE else 1,
        overflow = TextOverflow.Ellipsis
      )
    }

    // Cancelled calls: retry the same call, or continue and tell the model.
    if (item.cancelled && item.callId.isNotBlank()) {
      Spacer(modifier = Modifier.height(6.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = onRetryTool,
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
          modifier = Modifier.height(28.dp).testTag("btn_retry_tool")
        ) { Text("Retry", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
        Button(
          onClick = onContinueTool,
          colors = ButtonDefaults.buttonColors(containerColor = TerminalGreen),
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
          modifier = Modifier.height(28.dp).testTag("btn_continue_tool")
        ) { Text("Continue", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
      }
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
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Arguments", color = TextMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))
            Icon(
              Icons.Outlined.ContentCopy,
              contentDescription = "Copy arguments",
              tint = TextMuted,
              modifier = Modifier
                .size(11.dp)
                .clickable { clipboard.setText(AnnotatedString(item.argsJson)) }
            )
          }
          Text(prettyJson(item.argsJson), color = CyanAccent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
          Spacer(modifier = Modifier.height(6.dp))
        }
        if (item.detail.isNotBlank()) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Output", color = TextMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))
            Icon(
              Icons.Outlined.ContentCopy,
              contentDescription = "Copy output",
              tint = TextMuted,
              modifier = Modifier
                .size(11.dp)
                .clickable { clipboard.setText(AnnotatedString(item.detail)) }
            )
          }
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
private fun ApprovalCard(item: ApprovalBlock, onAllow: () -> Unit, onDeny: () -> Unit) {
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

/** Highlighted, copyable card for the agent's final answer. */
@Composable
private fun ResponseCard(block: TextBlock) {
  val clipboard = LocalClipboardManager.current
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(TerminalGreen.copy(alpha = 0.06f))
      .border(1.dp, TerminalGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
      .padding(12.dp)
      .testTag("chat_final_response")
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        Icons.Default.CheckCircle,
        contentDescription = null,
        tint = TerminalGreen,
        modifier = Modifier.size(14.dp)
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        "Response",
        color = TerminalGreen,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold
      )
      Spacer(modifier = Modifier.weight(1f))
      Icon(
        Icons.Outlined.ContentCopy,
        contentDescription = "Copy response",
        tint = TextMuted,
        modifier = Modifier
          .size(13.dp)
          .clickable { clipboard.setText(AnnotatedString(block.text)) }
      )
    }
    Spacer(modifier = Modifier.height(6.dp))
    MarkdownText(text = block.text, modifier = Modifier.fillMaxWidth())
  }
}

/** Collapsible reasoning/"thinking" box for models with reasoning enabled. */
@Composable
private fun ThinkingBlock(block: ReasoningBlock) {
  var expanded by remember(block.id) { mutableStateOf(false) }
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(DarkBackground.copy(alpha = 0.7f))
      .border(1.dp, DarkBorderSubtle, RoundedCornerShape(10.dp))
      .clickable { expanded = !expanded }
      .padding(horizontal = 10.dp, vertical = 7.dp)
      .testTag("stream_thinking")
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        Icons.Default.Psychology,
        contentDescription = null,
        tint = CyanAccent.copy(alpha = if (block.streaming) 1f else 0.6f),
        modifier = Modifier.size(13.dp)
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        if (block.streaming) "Thinking..." else "Thoughts",
        color = CyanAccent.copy(alpha = 0.85f),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold
      )
      Spacer(modifier = Modifier.weight(1f))
      Text(if (expanded) "v" else ">", color = TextMuted, fontSize = 10.sp)
    }
    if (expanded) {
      Spacer(modifier = Modifier.height(5.dp))
      Text(
        block.text,
        color = TextMuted,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontFamily = FontFamily.Monospace
      )
    }
  }
}

/** Single red error card for runtime/stream/provider failures. */
@Composable
private fun ErrorCard(block: ErrorBlock, showRetry: Boolean, onRetry: () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(DangerRed.copy(alpha = 0.08f))
      .border(1.dp, DangerRed.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
      .padding(12.dp)
      .testTag("stream_error_card")
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Default.Close, contentDescription = null, tint = DangerRed, modifier = Modifier.size(14.dp))
      Spacer(modifier = Modifier.width(6.dp))
      Text("Error", color = DangerRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(block.message, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
    if (showRetry) {
      Spacer(modifier = Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = onRetry,
          colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
          modifier = Modifier.height(30.dp).testTag("btn_retry_turn")
        ) {
          Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Retry", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
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
  onPause: () -> Unit,
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
            onClick = onPause,
            modifier = Modifier
              .size(40.dp)
              .clip(CircleShape)
              .background(WarningAmber)
              .testTag("btn_composer_pause")
          ) {
            Icon(Icons.Default.Pause, contentDescription = "Pause", tint = Color.White, modifier = Modifier.size(18.dp))
          }
          Spacer(modifier = Modifier.width(6.dp))
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

private fun relativeTime(timestamp: Long): String {
  if (timestamp <= 0) return ""
  val diff = System.currentTimeMillis() - timestamp
  val minutes = diff / 60000
  return when {
    minutes < 1 -> "just now"
    minutes < 60 -> "${minutes} min ago"
    minutes < 60 * 24 -> "${minutes / 60}h ago"
    else -> "${minutes / (60 * 24)}d ago"
  }
}

private fun friendlyToolLabel(name: String, argsJson: String): Pair<String, String> {
  val args = runCatching { JSONObject(argsJson) }.getOrNull()
  fun arg(key: String) = args?.optString(key).orEmpty().take(80)
  return when (name) {
    "read_file" -> "Reading" to arg("path")
    "read_files" -> "Reading files" to ""
    "glob_files" -> "Finding files" to arg("pattern")
    "regex_search" -> "Regex search" to arg("pattern")
    "file_info" -> "Inspecting" to arg("path")
    "directory_tree" -> "Listing tree" to arg("path").ifBlank { "workspace" }
    "task_plan" -> "Planning" to ""
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
