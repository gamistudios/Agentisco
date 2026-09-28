@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.agentisco.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Calculate
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
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.QuestionAnswer
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
import com.agentisco.agent.tool.toolTypeFor
import com.agentisco.core.model.AppDestination
import com.agentisco.data.local.chat.AgentSessionEntity
import com.agentisco.ui.AgentTurnItem
import com.agentisco.ui.ActionBlock
import com.agentisco.ui.ApprovalBlock
import com.agentisco.ui.ChatItem
import com.agentisco.ui.CompactionBlock
import com.agentisco.ui.ErrorBlock
import com.agentisco.ui.ReasoningBlock
import com.agentisco.ui.TextBlock
import com.agentisco.ui.TurnBlock
import com.agentisco.ui.TurnStatus
import com.agentisco.ui.UserMessageItem
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.components.DiffKind
import com.agentisco.ui.components.DiffLine
import com.agentisco.ui.components.DiffTable
import com.agentisco.ui.components.MarkdownText
import com.agentisco.ui.components.computeLineDiff
import com.agentisco.ui.components.diffStats
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
  val activeSessionId by viewModel.activeSessionId.collectAsState()
  val chatDisplay by viewModel.chatDisplay.collectAsState()
  val compactSettings by viewModel.compactSettings.collectAsState()
  val contextUsage by viewModel.contextUsage.collectAsState()

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
  // Open a session at its LATEST message (chat-app style): the first non-empty
  // load after a session switch jumps straight to the bottom; afterwards new
  // activity is followed only while the user is already at the bottom,
  // otherwise they keep their position and get a "Jump to latest" chip.
  var followedSessionId by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(activeSessionId, chatItems) {
    if (chatItems.isEmpty()) {
      // Rows for the next session are still loading from Room.
      followedSessionId = null
    } else if (followedSessionId != activeSessionId) {
      followedSessionId = activeSessionId
      listState.scrollToItem(chatItems.size)
    } else if (autoFollow) {
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
              text = activeSession?.let {
                "${sessions.size} session${if (sessions.size == 1) "" else "s"} · ${relativeTime(it.updatedAt)}"
              } ?: "Start chatting to create one",
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

      // Live agent state: a pulse dot while working. The stop control lives in
      // the composer next to pause, so the session bar does not carry a
      // duplicate button.
      if (isWorking) {
        Box(
          modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(ElectricBlueGlow)
        )
      }
    }

    // What the mode means, stated where the user can see it while working.
    if (permissions.planMode) PlanModeNotice()

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
              is UserMessageItem -> UserBubble(item, onEdit = { viewModel.editUserMessage(item.id, it) })
              is AgentTurnItem -> AgentTurnCard(
                item = item,
                showToolJson = chatDisplay.showToolJson,
                onAllow = { viewModel.resolveApproval(true) },
                onDeny = { (id, reason) ->
                  if (reason.isNullOrBlank()) viewModel.resolveApproval(false) else viewModel.denyWithReason(reason)
                },
                onAnswer = { _, answer -> viewModel.answerQuestion(answer) },
                onReopen = { viewModel.showApprovalDialog() },
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
      contextUsage = if (compactSettings.showContextUsage) contextUsage else null,
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
private fun UserBubble(item: UserMessageItem, onEdit: (String) -> Unit = {}) {
  val clipboard = LocalClipboardManager.current
  var showEditDialog by remember { mutableStateOf(false) }
  var editText by remember { mutableStateOf(item.text) }

  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.End
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
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
          Icons.Default.Edit,
          contentDescription = "Edit message",
          tint = TextMuted,
          modifier = Modifier
            .size(10.dp)
            .clickable { showEditDialog = true }
        )
      }
    }

    // Edit dialog
    if (showEditDialog) {
      AlertDialog(
        onDismissRequest = { showEditDialog = false },
        containerColor = DarkSurface,
        title = { Text("Edit message", color = TextPrimary, fontSize = 14.sp) },
        text = {
          OutlinedTextField(
            value = editText,
            onValueChange = { editText = it },
            placeholder = { Text("Enter new content", color = TextMuted) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 4
          )
        },
        confirmButton = {
          Button(
            onClick = {
              onEdit(editText.trim())
              showEditDialog = false
            },
            enabled = editText.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
          ) { Text("Save", color = Color.White, fontSize = 12.sp) }
        },
        dismissButton = {
          TextButton(onClick = { showEditDialog = false }) { Text("Cancel", color = TextMuted, fontSize = 12.sp) }
        }
      )
    }
  }
}

@Composable
private fun AgentTurnCard(
  item: AgentTurnItem,
  showToolJson: Boolean,
  onAllow: (String) -> Unit,
  onDeny: (Pair<String, String?>) -> Unit,
  onAnswer: (String, String?) -> Unit,
  onReopen: (String) -> Unit,
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
          showToolJson = showToolJson,
          onCancelTool = { onCancelTool(block.callId) },
          onRetryTool = { onRetryTool(block.callId) },
          onContinueTool = { onContinueTool(block.callId) }
        )
        is ApprovalBlock -> ApprovalCard(
          item = block,
          onAllow = { onAllow(block.approvalId) },
          onDeny = { reason -> onDeny(block.approvalId to reason) },
          onAnswer = { answer -> onAnswer(block.approvalId, answer) },
          onReopen = { onReopen(block.approvalId) }
        )        is ErrorBlock -> ErrorCard(block, showRetry = item.status == TurnStatus.FAILED, onRetry = onRetry)
        is CompactionBlock -> CompactionCard(block)
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

    // Which provider/model answered this turn — a session can switch models
    // mid-conversation, so attribution belongs on the message, not the chat.
    val attribution = listOfNotNull(item.providerName, item.modelName).joinToString(" · ")
    if (attribution.isNotBlank()) {
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = attribution,
        color = TextMuted,
        fontSize = 8.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("chat_turn_attribution")
      )
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
/**
 * A compaction note: the transcript sent to the provider was summarized.
 * Nothing above it was deleted; it explains why the model's memory starts at
 * a summary and carries that summary for inspection.
 */
@Composable
private fun CompactionCard(block: CompactionBlock) {
  var expanded by remember { mutableStateOf(false) }
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(ElectricBlue.copy(alpha = 0.08f))
      .border(1.dp, ElectricBlue.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
      .clickable { expanded = !expanded }
      .padding(horizontal = 10.dp, vertical = 8.dp)
      .testTag("stream_compaction")
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Outlined.Compress, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(14.dp))
      Spacer(modifier = Modifier.width(8.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text("Context compacted", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(block.summary, color = TextSecondary, fontSize = 11.sp, lineHeight = 14.sp)
      }
      Icon(
        Icons.Default.KeyboardArrowDown,
        contentDescription = if (expanded) "Hide summary" else "Show summary",
        tint = TextMuted,
        modifier = Modifier.size(14.dp)
      )
    }
    if (expanded && block.summaryText.isNotBlank()) {
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        block.summaryText,
        color = TextSecondary,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 220.dp)
      )
    }
  }
}

@Composable
private fun ToolCallRow(
  item: ActionBlock,
  showToolJson: Boolean = false,
  onCancelTool: () -> Unit = {},
  onRetryTool: () -> Unit = {},
  onContinueTool: () -> Unit = {}
) {
  var expanded by remember(item.id) { mutableStateOf(false) }
  val clipboard = LocalClipboardManager.current
  val (verb, target) = friendlyToolLabel(item.name, item.argsJson)
  val icon = toolIcon(item.name)
  val iconColor = toolColor(item.name)
  // File edits render as an inline git-style diff instead of raw JSON.
  val diffLines = remember(item.argsJson) { editDiffForTool(item.name, item.argsJson) }
  // Terminal cards show the real shell command instead of {"command": …}.
  val command = remember(item.argsJson) { displayCommandForTool(item.name, item.argsJson) }

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
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
          Icons.Default.KeyboardArrowDown,
          contentDescription = if (expanded) "Collapse details" else "Expand details",
          tint = TextMuted,
          modifier = Modifier.size(14.dp)
        )
      }
    }

    // The command about to run / that ran — copyable, never truncated JSON.
    if (command != null) {
      Spacer(modifier = Modifier.height(5.dp))
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(6.dp))
          .background(DarkBackground)
          .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          Icons.Outlined.ContentCopy,
          contentDescription = "Copy command",
          tint = TextMuted,
          modifier = Modifier
            .size(13.dp)
            .clickable { clipboard.setText(AnnotatedString(command)) }
            .testTag("btn_copy_command")
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
          "\$ ${command.trim()}",
          color = TextCode,
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace,
          softWrap = false,
          maxLines = 1,
          overflow = TextOverflow.Clip,
          modifier = Modifier
            .weight(1f)
            .horizontalScroll(rememberScrollState())
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

    // Git-style diff for file edits: -removed / +added with line numbers.
    if (diffLines != null && diffLines.isNotEmpty()) {
      Spacer(modifier = Modifier.height(6.dp))
      val (added, removed) = diffStats(diffLines)
      var showAllDiff by remember(item.id) { mutableStateOf(false) }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("+$added", color = TerminalGreen, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.width(6.dp))
        Text("\u2212$removed", color = DangerRed, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
      }
      Spacer(modifier = Modifier.height(4.dp))
      DiffTable(
        lines = if (showAllDiff) diffLines else diffLines.take(DIFF_PREVIEW_LINES),
        modifier = Modifier.fillMaxWidth().testTag("chat_edit_diff")
      )
      if (!showAllDiff && diffLines.size > DIFF_PREVIEW_LINES) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          "Show all ${diffLines.size} lines",
          color = ElectricBlueGlow,
          fontSize = 10.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier
            .clickable { showAllDiff = true }
            .testTag("btn_show_diff_all")
        )
      }
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
        // Raw request JSON is opt-in (Settings → Chat Tool Activity).
        if (showToolJson && item.argsJson.isNotBlank() && item.argsJson != "{}") {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Request (raw JSON)", color = TextMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))
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
          Spacer(modifier = Modifier.height(2.dp))
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 260.dp)
              .verticalScroll(rememberScrollState())
              .testTag("tool_output_detail")
          ) {
            Text(item.detail, color = TextCode, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
          }
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
private fun ApprovalCard(
  item: ApprovalBlock,
  onAllow: () -> Unit,
  onDeny: (String?) -> Unit,
  onAnswer: (String) -> Unit,
  onReopen: () -> Unit
) {
  val accent = if (item.isQuestion) ElectricBlueGlow else WarningAmber
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(accent.copy(alpha = 0.08f))
      .border(1.dp, accent, RoundedCornerShape(10.dp))
      .padding(12.dp)
      .testTag("stream_approval")
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .size(7.dp)
          .clip(CircleShape)
          .background(accent)
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(item.title, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(item.command, color = TextCode, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    // The impact line explains what the action would do; once the user has
    // refused and said why, their reason replaces it.
    if (item.rationale.isNotBlank()) {
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        "“${item.rationale}”",
        color = DangerRed,
        fontSize = 11.sp,
        lineHeight = 15.sp
      )
    } else if (item.impact.isNotBlank() && item.impact != item.command) {
      Spacer(modifier = Modifier.height(4.dp))
      Text(item.impact, color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
    }
    Spacer(modifier = Modifier.height(8.dp))

    // Unanswered state: the request is still live, so the controls stay here.
    // Dismissing the dialog parks the request — it never answers it for you.
    val unanswered = !item.resolved

    when {
      unanswered -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          if (item.isQuestion) "Waiting for your answer — choose below."
          else "Waiting for your decision — nothing has run yet.",
          color = TextMuted,
          fontSize = 11.sp
        )
        if (item.isQuestion) {
          // The dialog is only a shortcut; the card is the durable surface, so
          // it has to carry the same choices for a deferred question.
          item.options.forEachIndexed { index, option ->
            Button(
              onClick = { onAnswer(option) },
              colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
              contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
              modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .testTag("btn_card_answer_$index")
            ) { Text(option, color = Color.White, fontSize = 11.sp) }
          }
          TextButton(
            onClick = onReopen,
            modifier = Modifier.testTag("btn_reopen_dialog")
          ) { Text("Answer in the dialog…", color = TextSecondary, fontSize = 11.sp) }
        } else {
          // A denial the user can explain is worth far more to the model than a
          // bare "no" — the field stays optional so one tap still refuses.
          var rationale by remember { mutableStateOf("") }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
              onClick = onAllow,
              colors = ButtonDefaults.buttonColors(containerColor = TerminalGreen),
              contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
              modifier = Modifier.height(30.dp).testTag("btn_allow_tool")
            ) { Text("Allow", color = Color.White, fontSize = 11.sp) }
            Button(
              onClick = { onDeny(rationale.trim().ifBlank { null }) },
              colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
              contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
              modifier = Modifier.height(30.dp).testTag("btn_deny_tool")
            ) { Text("Deny", color = Color.White, fontSize = 11.sp) }
          }
          OutlinedTextField(
            value = rationale,
            onValueChange = { rationale = it },
            modifier = Modifier
              .fillMaxWidth()
              .testTag("card_deny_reason"),
            placeholder = { Text(item.freeTextLabel, color = TextMuted, fontSize = 11.sp) },
            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = WarningAmber,
              unfocusedBorderColor = DarkBorder,
              cursorColor = WarningAmber,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary
            )
          )
          TextButton(
            onClick = onReopen,
            modifier = Modifier.testTag("btn_reopen_dialog")
          ) { Text("Open the dialog…", color = TextSecondary, fontSize = 11.sp) }        }
      }

      item.answer.isNotBlank() -> Text(
        "Answered: ${item.answer}",
        color = TerminalGreen,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
      )
      // Stopped while unanswered: the user never chose, so this is not a denial.
      item.stalled -> Text(
        "Turn stopped before you decided — nothing was run.",
        color = TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
      )
      item.isQuestion -> Text(
        "Not answered",
        color = TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
      )
      else -> Text(
        if (item.allowed) "✓ Allowed" else "✗ Denied",
        color = if (item.allowed) TerminalGreen else DangerRed,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
      )
    }
  }
}

/** Label for the free-text field the request's card and dialog share. */
private val ApprovalBlock.freeTextLabel: String
  get() = if (isQuestion) "Or type your own answer…" else "Why not? (the agent reads this)"
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
  val clipboard = LocalClipboardManager.current
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
      Spacer(modifier = Modifier.weight(1f))
      Icon(
        Icons.Outlined.ContentCopy,
        contentDescription = "Copy error",
        tint = TextMuted,
        modifier = Modifier
          .size(13.dp)
          .clickable { clipboard.setText(AnnotatedString(block.message)) }
      )
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
  contextUsage: com.agentisco.agent.compact.ContextTokenUsage?,
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
              viewModel.navigateTo(AppDestination.AI_PROVIDERS)
            } else {
              val recordId = option.tag?.substringBefore("|")
              models.firstOrNull { it.id == recordId }?.let { viewModel.selectModel(it) }
            }
          },
          modifier = Modifier.testTag("composer_model_selector")
        )

        // File editing policy dropdown. Plan mode lives here: it is the widest
        // possible file-edit policy ("edit nothing") and shares the dropdown's
        // semantics — pick once, it applies to the next prompt.
        ConfigDropdown(
          label = when {
            permissions.planMode -> "Plan mode"
            permissions.fileEditing == PermissionMode.ALWAYS_ASK -> "Edits: ask"
            permissions.fileEditing == PermissionMode.AUTO_APPROVE_PROJECT -> "Edits: auto"
            permissions.fileEditing == PermissionMode.NEVER_ALLOW -> "Edits: off"
            else -> "Edits: ask"
          },
          tint = if (permissions.planMode) ElectricBlueGlow
            else if (permissions.fileEditing == PermissionMode.NEVER_ALLOW) DangerRed
            else TerminalGreen,
          options = listOf(
            DropdownOption(
              label = "Plan mode — research only, no changes",
              tag = "PLAN_MODE",
              checked = permissions.planMode
            ),
            DropdownOption(label = "Ask before editing", tag = PermissionMode.ALWAYS_ASK.name),
            DropdownOption(label = "Auto-approve in workspace", tag = PermissionMode.AUTO_APPROVE_PROJECT.name),
            DropdownOption(label = "Never edit files", tag = PermissionMode.NEVER_ALLOW.name)
          ),
          onPick = { option ->
            when (option.tag) {
              // Toggling plan mode from here also clears an active edit policy
              // mismatch, since plan overrides whatever fileEditing says.
              "PLAN_MODE" -> viewModel.updatePermissions { it.copy(planMode = !it.planMode) }
              else -> option.tag?.let { PermissionMode.valueOf(it) }?.let { mode ->
                viewModel.updatePermissions { it.copy(fileEditing = mode) }
              }
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

        // Live context occupancy: how much of the model's window the next
        // request will consume. It moves while the agent streams, and turns
        // amber once the conversation is close to the compact threshold.
        contextUsage?.let { usage -> ContextUsageChip(usage) }
      }
    }
  }
}

data class DropdownOption(
  val label: String,
  val sublabel: String? = null,
  val tag: String? = null,
  val header: Boolean = false,
  val configure: Boolean = false,
  /** A toggle-style option: shows a checkmark while it is the active choice. */
  val checked: Boolean = false
)

/**
 * Context-percentage chip: a fixed-width read-out of how much of the model's
 * context window the running conversation occupies, so the user can see the
 * pressure build while the agent works — not when the context runs out.
 */
@Composable
private fun ContextUsageChip(usage: com.agentisco.agent.compact.ContextTokenUsage) {
  if (usage.contextWindow <= 0) return
  val tint = when {
    usage.isAboveThreshold -> DangerRed
    usage.pressurePercent >= 85 -> WarningAmber
    usage.pressurePercent >= 60 -> ElectricBlueGlow
    else -> TextMuted
  }
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(DarkSurface.copy(alpha = 0.6f))
      .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
      .padding(horizontal = 6.dp, vertical = 4.dp)
      .testTag("context_usage_chip")
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        imageVector = Icons.Outlined.Calculate,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(11.dp)
      )
      Spacer(modifier = Modifier.width(3.dp))
      Text(
        text = usage.label(),
        color = tint,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
      )
    }
  }
}

/**
 * The plan-mode switch. A mode the user cannot see is a mode the agent appears
 * to ignore, so it is lit while it is on, and it never disables itself: stopping
 * a runaway implementation to "just plan" is exactly when it is needed.
 */
@Composable
internal fun PlanModeToggle(active: Boolean, onToggle: () -> Unit) {
  Surface(
    onClick = onToggle,
    shape = RoundedCornerShape(8.dp),
    color = if (active) ElectricBlue.copy(alpha = 0.16f) else DarkSurface,
    border = androidx.compose.foundation.BorderStroke(
      1.dp,
      if (active) ElectricBlueGlow else DarkBorderSubtle
    ),
    modifier = Modifier.testTag("btn_plan_mode")
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        Icons.Outlined.Description,
        contentDescription = null,
        tint = if (active) ElectricBlueGlow else TextSecondary,
        modifier = Modifier.size(13.dp)
      )
      Spacer(modifier = Modifier.width(5.dp))
      Text(
        "Plan",
        color = if (active) ElectricBlueGlow else TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
      )
    }
  }
}

/** Says what the lit switch means, in the user's words rather than the model's. */
@Composable
internal fun PlanModeNotice() {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(ElectricBlue.copy(alpha = 0.10f))
      .padding(horizontal = 12.dp, vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      Icons.Outlined.Description,
      contentDescription = null,
      tint = ElectricBlueGlow,
      modifier = Modifier.size(12.dp)
    )
    Spacer(modifier = Modifier.width(6.dp))
    Text(
      text = "Plan mode: the agent reads, researches and plans. It will refuse any change until you turn this off.",
      color = TextSecondary,
      fontSize = 10.sp,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.testTag("plan_mode_notice")
    )
  }
}

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
              Row(verticalAlignment = Alignment.CenterVertically) {
                if (option.checked) {
                  Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = ElectricBlueGlow,
                    modifier = Modifier.size(14.dp)
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                }
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
    "edit_files" -> "Editing files" to "${args?.optJSONArray("edits")?.length() ?: 0} change(s)"
    "create_file" -> "Creating" to arg("path")
    "create_directory" -> "Creating folder" to arg("path")
    "delete_file" -> "Deleting" to arg("path")
    "move_file" -> "Moving" to arg("new_path")
    "copy_file" -> "Copying" to arg("new_path")
    "list_files" -> "Listing" to arg("path").ifBlank { "workspace" }
    "search_files" -> "Searching" to arg("query")
    "run_command" -> "Running" to arg("command")
    "terminal_output" -> "Reading output" to arg("runner_id")
    "write_terminal_input" -> "Terminal input" to arg("input").ifBlank { "(Enter)" }
    "interrupt_terminal" -> "Interrupting" to ""
    "git_status" -> "Git status" to ""
    "git_diff" -> "Git diff" to arg("path")
    "git_log" -> "Commit history" to ""
    "git_show" -> "Showing commit" to arg("hash")
    "git_stage" -> "Staging" to arg("path").ifBlank { "all changes" }
    "git_unstage" -> "Unstaging" to ""
    "git_commit" -> "Committing" to arg("message")
    "build" -> "Building" to arg("args")
    "test" -> "Testing" to arg("args")
    "run" -> "Starting" to "dev server"
    "web_fetch" -> "Fetching" to arg("url")
    "web_search" -> "Web search" to arg("query")
    "ask_user" -> "Asking" to arg("question")
    else -> name to ""
  }
}

private fun toolIcon(name: String): ImageVector = when (toolTypeFor(name)) {
  ToolType.READ_FILE -> Icons.Outlined.Description
  ToolType.SEARCH -> Icons.Outlined.Search
  ToolType.TERMINAL -> Icons.Outlined.Terminal
  ToolType.EDIT_FILE -> Icons.Outlined.Edit
  ToolType.GIT -> Icons.Outlined.Commit
  ToolType.BUILD -> Icons.Outlined.Build
  ToolType.WEB -> Icons.Outlined.Cloud
  ToolType.QUESTION -> Icons.Outlined.QuestionAnswer
}

private fun toolColor(name: String): Color = when (toolTypeFor(name)) {
  ToolType.READ_FILE -> CyanAccent
  ToolType.SEARCH -> WarningAmber
  ToolType.TERMINAL -> TerminalGreen
  ToolType.EDIT_FILE -> ElectricBlueGlow
  ToolType.GIT -> IndigoAccent
  ToolType.BUILD -> WarningAmber
  ToolType.WEB -> ElectricBlueGlow
  ToolType.QUESTION -> TerminalGreen
}

private fun prettyJson(raw: String): String = runCatching {
  JSONObject(raw).toString(2)
}.getOrDefault(raw)

/** Collapsed diff shows a bounded preview; the user can expand the rest. */
private const val DIFF_PREVIEW_LINES = 14

/**
 * Human-readable shell command for terminal-family tools, or null when the
 * tool isn't one. Script tools mirror [com.agentisco.agent.tool.ScriptTool]'s
 * `npm run <script> [-- <args>]` construction.
 */
internal fun displayCommandForTool(name: String, argsJson: String): String? {
  val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: return null
  return when (name) {
    "run_command" -> args.optString("command").trim().takeIf { it.isNotEmpty() }
    "build", "test", "run" -> {
      val extra = args.optString("args").trim()
      "npm run $name" + if (extra.isEmpty()) "" else " -- $extra"
    }
    else -> null
  }
}

/**
 * Diff lines for file-mutation tools, or null when the tool isn't an edit.
 * write/create have no old content persisted, so everything is an addition.
 */
internal fun editDiffForTool(name: String, argsJson: String): List<DiffLine>? {
  if (argsJson.isBlank() || argsJson == "{}") return null
  val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: return null
  return when (name) {
    "edit_file" -> {
      val old = args.optString("old_string")
      val new = args.optString("new_string")
      if (old.isEmpty() && new.isEmpty()) null else computeLineDiff(old, new)
    }
    "write_file", "create_file" -> {
      val content = args.optString("content")
      if (content.isBlank()) null else computeLineDiff("", content)
    }
    // A batch labels each block with its file, except when it holds one edit only.
    "edit_files" -> {
      val edits = args.optJSONArray("edits") ?: return null
      val blocks = ArrayList<Pair<String, List<DiffLine>>>(edits.length())
      for (i in 0 until edits.length()) {
        val edit = edits.optJSONObject(i) ?: continue
        val old = edit.optString("old_string")
        val new = edit.optString("new_string")
        if (old.isEmpty() && new.isEmpty()) continue
        val lines = computeLineDiff(old, new)
        if (lines.isNotEmpty()) blocks.add(edit.optString("path") to lines)
      }
      when (blocks.size) {
        0 -> null
        1 -> blocks.single().second
        else -> blocks.flatMap { (path, lines) ->
          listOf(DiffLine(DiffKind.CONTEXT, "── $path ──", null, null)) + lines
        }
      }
    }
    else -> null
  }
}
