@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.awaki.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreHoriz
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
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
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
import com.awaki.agent.model.PermissionMode
import com.awaki.agent.model.ToolType
import com.awaki.agent.tool.toolTypeFor
import com.awaki.core.model.AppDestination
import com.awaki.data.local.chat.AgentSessionEntity
import com.awaki.ui.AgentTurnItem
import com.awaki.ui.ActionBlock
import com.awaki.ui.ApprovalBlock
import com.awaki.ui.ChatItem
import com.awaki.ui.CompactionBlock
import com.awaki.ui.ErrorBlock
import com.awaki.ui.ReasoningBlock
import com.awaki.ui.TextBlock
import com.awaki.ui.TurnBlock
import com.awaki.ui.TurnStatus
import com.awaki.ui.UserMessageItem
import com.awaki.ui.WorkspaceViewModel
import com.awaki.ui.agentPlanFrom
import com.awaki.ui.components.DiffKind
import com.awaki.ui.components.DiffLine
import com.awaki.ui.components.DiffTable
import com.awaki.ui.components.MarkdownText
import com.awaki.ui.components.PlanCard
import com.awaki.ui.components.computeLineDiff
import com.awaki.ui.components.diffStats
import com.awaki.ui.isPlanPublish
import com.awaki.ui.theme.*
import com.awaki.ui.theme.AwakiTheme
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
  val delegationPhases by viewModel.delegationPhases.collectAsState()
  val pausedDelegations = delegationPhases.filterValues { it }.keys

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

  // The plan is part of the transcript, not of this screen: reading it back out
  // of the persisted tool calls is what lets one pinned card hold every update,
  // and survive a restart showing the same steps.
  val plan = remember(chatItems) { agentPlanFrom(chatItems) }
  var planExpanded by rememberSaveable { mutableStateOf(false) }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      // Keep the composer usable above the soft keyboard.
      .imePadding()
  ) {
    // Session bar: modern run / agent header
    Surface(
      onClick = { showSessionSheet = true },
      shape = RoundedCornerShape(10.dp),
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
      border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp)
        .testTag("btn_sessions")
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth()
        ) {
          Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "Agent",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.width(8.dp))
          // Role badge
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(10.dp))
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
              .padding(horizontal = 8.dp, vertical = 2.dp)
          ) {
            Text(
              text = "General Agent",
              color = MaterialTheme.colorScheme.primary,
              fontSize = 10.sp,
              fontWeight = FontWeight.Medium
            )
          }
          Spacer(modifier = Modifier.weight(1f))
          if (isWorking) {
            Box(
              modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(8.dp))
          }
          IconButton(
            onClick = { viewModel.createChatSession() },
            enabled = !isWorking,
            modifier = Modifier
              .size(24.dp)
              .testTag("btn_new_session")
          ) {
            Icon(Icons.Default.Add, contentDescription = "New session", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
          }
          Spacer(modifier = Modifier.width(4.dp))
          Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = "Switch session",
            tint = AwakiTheme.extra.textMuted,
            modifier = Modifier.size(16.dp)
          )
        }
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = activeSession?.title ?: "New conversation",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = activeSession?.let {
              "${sessions.size} session${if (sessions.size == 1) "" else "s"} · ${relativeTime(it.updatedAt)}"
            } ?: "Start chatting to create one",
            color = AwakiTheme.extra.textMuted,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
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
                pausedDelegations = pausedDelegations,
                onPauseSubagent = { viewModel.setSubagentPaused(it, paused = true) },
                onResumeSubagent = { viewModel.setSubagentPaused(it, paused = false) },
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
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .padding(bottom = 10.dp)
              .testTag("btn_jump_to_latest")
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Jump to latest", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
          }
        }
      }
    }

    // The plan sits between the scrolling activity and the input, so activity
    // cards always stay above it and it never scrolls out of reach.
    if (plan != null) {
      // An expanded plan and the keyboard together can exceed the screen, and
      // the input has to stay reachable, so the plan folds while it is open.
      val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
      PlanCard(
        plan = plan,
        expanded = planExpanded && !keyboardOpen,
        onToggle = { planExpanded = !planExpanded },
        modifier = Modifier.padding(horizontal = 10.dp)
      )
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
      containerColor = MaterialTheme.colorScheme.surface
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
      containerColor = MaterialTheme.colorScheme.surface,
      title = { Text("Rename session", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
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
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) { Text("Save", fontSize = 12.sp) }
      },
      dismissButton = {
        TextButton(onClick = { renameTarget = null }) { Text("Cancel", color = AwakiTheme.extra.textMuted, fontSize = 12.sp) }
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
      Text("Sessions", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
      TextButton(onClick = onNew, enabled = !isWorking) {
        Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("New chat", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      }
    }
    Spacer(modifier = Modifier.height(4.dp))
    if (sessions.isEmpty()) {
      Text(
        "No sessions yet for this project. Send a prompt to start one.",
        color = AwakiTheme.extra.textMuted,
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
              .background(if (isActive) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
              .border(
                1.dp,
                if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
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
                color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                "${relativeTime(session.updatedAt)} · ${session.status}",
                color = AwakiTheme.extra.textMuted,
                fontSize = 9.sp
              )
            }
            IconButton(onClick = { onRename(session) }, modifier = Modifier.size(26.dp)) {
              Icon(Icons.Default.Edit, contentDescription = "Rename", tint = AwakiTheme.extra.textMuted, modifier = Modifier.size(13.dp))
            }
            IconButton(onClick = { onDelete(session) }, modifier = Modifier.size(26.dp)) {
              Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
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
    "running" -> MaterialTheme.colorScheme.primary
    "completed" -> AwakiTheme.extra.success
    "failed" -> MaterialTheme.colorScheme.error
    "cancelled", "interrupted" -> AwakiTheme.extra.warning
    else -> AwakiTheme.extra.textMuted
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
    Surface(
      shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp),
      color = MaterialTheme.colorScheme.surfaceContainer,
      border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
      modifier = Modifier
        .widthIn(max = 320.dp)
        .combinedClickable(
          onClick = {},
          onLongClick = { clipboard.setText(AnnotatedString(item.text)) }
        )
        .testTag("chat_user_message")
    ) {
      Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(
          text = item.text,
          color = MaterialTheme.colorScheme.onSurface,
          fontSize = 13.sp,
          lineHeight = 18.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.End,
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(relativeTime(item.timestamp), color = AwakiTheme.extra.textMuted, fontSize = 9.sp)
          Spacer(modifier = Modifier.width(6.dp))
          Icon(
            Icons.Outlined.ContentCopy,
            contentDescription = "Copy message",
            tint = AwakiTheme.extra.textMuted,
            modifier = Modifier
              .size(11.dp)
              .clickable { clipboard.setText(AnnotatedString(item.text)) }
          )
          Spacer(modifier = Modifier.width(6.dp))
          Icon(
            Icons.Default.Edit,
            contentDescription = "Edit message",
            tint = AwakiTheme.extra.textMuted,
            modifier = Modifier
              .size(11.dp)
              .clickable { showEditDialog = true }
          )
        }
      }
    }

    // Edit dialog
    if (showEditDialog) {
      AlertDialog(
        onDismissRequest = { showEditDialog = false },
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Edit message", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp) },
        text = {
          OutlinedTextField(
            value = editText,
            onValueChange = { editText = it },
            placeholder = { Text("Enter new content", color = AwakiTheme.extra.textMuted) },
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
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
          ) { Text("Save", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp) }
        },
        dismissButton = {
          TextButton(onClick = { showEditDialog = false }) { Text("Cancel", color = AwakiTheme.extra.textMuted, fontSize = 12.sp) }
        }
      )
    }
  }
}

@Composable
private fun SubagentTimelineBanner(name: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp)
  ) {
    Box(
      modifier = Modifier
        .width(16.dp)
        .height(1.dp)
        .background(MaterialTheme.colorScheme.outlineVariant)
    )
    Spacer(modifier = Modifier.width(6.dp))
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
      border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          Icons.Outlined.Search,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
          text = name,
          color = MaterialTheme.colorScheme.primary,
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold
        )
      }
    }
    Spacer(modifier = Modifier.width(6.dp))
    Box(
      modifier = Modifier
        .weight(1f)
        .height(1.dp)
        .background(MaterialTheme.colorScheme.outlineVariant)
    )
  }
}

/**
 * Card chrome: a solid accent rail down the left edge of a rounded, faintly
 * outlined surface — what marks a card as the agent asking, failing or working.
 */
private fun Modifier.cardWithRail(
  accent: Color,
  surface: Color,
  shape: RoundedCornerShape = RoundedCornerShape(10.dp)
): Modifier =
  this.clip(shape)
    .background(surface, shape)
    .drawBehind { drawRect(color = accent, size = Size(4.dp.toPx(), size.height)) }
    .border(1.dp, accent.copy(alpha = 0.35f), shape)

/** Round status marker: filled disc once a step is settled, outlined ring for a card. */
@Composable
private fun StatusCircle(icon: ImageVector, color: Color, filled: Boolean = false) {
  Box(
    modifier = Modifier
      .size(26.dp)
      .clip(CircleShape)
      .background(if (filled) color else color.copy(alpha = 0.12f))
      .then(if (filled) Modifier else Modifier.border(1.dp, color.copy(alpha = 0.5f), CircleShape)),
    contentAlignment = Alignment.Center
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = if (filled) MaterialTheme.colorScheme.background else color,
      modifier = Modifier.size(14.dp)
    )
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
  /** The delegate calls the user is holding still, by their own call id. */
  pausedDelegations: Set<String>,
  onPauseSubagent: (String) -> Unit,
  onResumeSubagent: (String) -> Unit,
  onNavigate: (AppDestination) -> Unit
) {
  val clipboard = LocalClipboardManager.current
  val fullText = item.blocks.filterIsInstance<TextBlock>().joinToString("\n\n") { it.text }
  // The model's final answer (last completed text block of a finished turn)
  // gets the highlighted, copyable response treatment.
  val finalResponseId = if (item.status == TurnStatus.COMPLETED) {
    item.blocks.lastOrNull { it is TextBlock && (it as? TextBlock)?.text?.isNotBlank() == true }?.id
  } else null

  val statusColor = when (item.status) {
    TurnStatus.RUNNING -> MaterialTheme.colorScheme.primary
    TurnStatus.COMPLETED -> AwakiTheme.extra.success
    TurnStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> AwakiTheme.extra.warning
  }
  val statusIcon = when (item.status) {
    TurnStatus.COMPLETED -> Icons.Default.CheckCircle
    TurnStatus.FAILED -> Icons.Default.Close
    TurnStatus.CANCELLED, TurnStatus.INTERRUPTED -> Icons.Default.Stop
    TurnStatus.PAUSED -> Icons.Default.Pause
    else -> Icons.Default.AutoAwesome
  }
  // A working turn breathes; everything else states its result.
  val statusAlpha: Float = if (item.status == TurnStatus.RUNNING) {
    val transition = rememberInfiniteTransition(label = "thinking")
    transition
      .animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "thinking-alpha"
      )
      .value
  } else 1f

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .cardWithRail(statusColor, MaterialTheme.colorScheme.surface.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
      .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
      .testTag("chat_agent_turn")
  ) {
    // Turn header: identity + status.
    Row(verticalAlignment = Alignment.CenterVertically) {
      StatusCircle(icon = statusIcon, color = statusColor.copy(alpha = statusAlpha), filled = false)
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = "Agent",
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold
      )
      Spacer(modifier = Modifier.width(8.dp))
      // Subtle status badge
      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(10.dp))
          .background(statusColor.copy(alpha = 0.14f))
          .padding(horizontal = 8.dp, vertical = 2.dp)
      ) {
        Text(
          text = when (item.status) {
            TurnStatus.RUNNING -> "Working"
            TurnStatus.PAUSED -> "Paused"
            TurnStatus.COMPLETED -> "Completed"
            TurnStatus.FAILED -> "Failed"
            TurnStatus.CANCELLED -> "Cancelled"
            TurnStatus.INTERRUPTED -> "Interrupted"
          },
          color = statusColor,
          fontSize = 10.sp,
          fontWeight = FontWeight.SemiBold
        )
      }
      Spacer(modifier = Modifier.weight(1f))
      if (fullText.isNotBlank() && item.status != TurnStatus.RUNNING) {
        Icon(
          Icons.Outlined.ContentCopy,
          contentDescription = "Copy response",
          tint = AwakiTheme.extra.textMuted,
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
          tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
          modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(item.statusMessage, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
      }
    }

    // Turn blocks in order with sub-agent timeline continuity
    var activeSubagent: String? = null
    item.blocks.forEach { block ->
      // The pinned plan card above the composer shows these; a plan update must
      // not leave another card in the conversation.
      if (block.isPlanPublish()) return@forEach
      val blockSubagent = when (block) {
        is ActionBlock -> block.delegation?.role
        else -> null
      }
      if (blockSubagent != null && blockSubagent != activeSubagent) {
        activeSubagent = blockSubagent
        Spacer(modifier = Modifier.height(10.dp))
        SubagentTimelineBanner(name = blockSubagent)
      }
      Spacer(modifier = Modifier.height(6.dp))
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
          onContinueTool = { onContinueTool(block.callId) },
          pausedDelegations = pausedDelegations,
          onPauseSubagent = onPauseSubagent,
          onResumeSubagent = onResumeSubagent
        )
        is ApprovalBlock -> ApprovalCard(
          item = block,
          onAllow = { onAllow(block.approvalId) },
          onDeny = { reason -> onDeny(block.approvalId to reason) },
          onAnswer = { answer -> onAnswer(block.approvalId, answer) },
          onReopen = { onReopen(block.approvalId) }
        )
        is ErrorBlock -> ErrorCard(block, showRetry = item.status == TurnStatus.FAILED, onRetry = onRetry)
        is CompactionBlock -> CompactionCard(block)
      }
    }

    // Resumable states: prominent Resume primary button right below the cancelled/paused event.
    if (item.status == TurnStatus.PAUSED || item.status == TurnStatus.CANCELLED) {
      Spacer(modifier = Modifier.height(10.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = onRetry,
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
          shape = RoundedCornerShape(8.dp),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
          modifier = Modifier
            .height(34.dp)
            .testTag("btn_resume_turn")
        ) {
          Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(15.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Resume", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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

    // Model & provider attribution
    val attribution = listOfNotNull(item.providerName, item.modelName).joinToString(" · ")
    if (attribution.isNotBlank()) {
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = attribution,
        color = AwakiTheme.extra.textMuted,
        fontSize = 9.sp,
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
private fun AgentEmptyState(project: com.awaki.data.model.Project, hasHistory: Boolean, onSuggestion: (String) -> Unit) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Spacer(modifier = Modifier.height(20.dp))
    Text(
      text = if (hasHistory) "Continue where you left off" else "What are we building?",
      color = MaterialTheme.colorScheme.onSurface,
      fontSize = 20.sp,
      fontWeight = FontWeight.Bold,
      letterSpacing = (-0.3).sp
    )
    Text(
      text = "Describe a task or let the agent navigate ${project.name}",
      color = AwakiTheme.extra.textMuted,
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
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable { onSuggestion(suggestion) }
            .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
          Text(suggestion, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
      .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
      .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
      .clickable { expanded = !expanded }
      .padding(horizontal = 10.dp, vertical = 8.dp)
      .testTag("stream_compaction")
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Outlined.Compress, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
      Spacer(modifier = Modifier.width(8.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text("Context compacted", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(block.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp)
      }
      Icon(
        Icons.Default.KeyboardArrowDown,
        contentDescription = if (expanded) "Hide summary" else "Show summary",
        tint = AwakiTheme.extra.textMuted,
        modifier = Modifier.size(14.dp)
      )
    }
    if (expanded && block.summaryText.isNotBlank()) {
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        block.summaryText,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 220.dp)
      )
    }
  }
}

/**
 * The work of a delegated agent, rendered with the same cards the orchestrator's
 * own actions use, nested inside the delegation that asked for it.
 *
 * A step of this work has no controls of its own — the specialist's approvals are
 * asked through the parent turn's single dialog. A delegation nested in here is the
 * one exception: it is held and released by its own card, as any other is.
 */
@Composable
private fun DelegationActivityStream(
  blocks: List<TurnBlock>,
  showToolJson: Boolean,
  pausedDelegations: Set<String>,
  onPauseSubagent: (String) -> Unit,
  onResumeSubagent: (String) -> Unit
) {
  if (blocks.isEmpty()) return
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 10.dp)
      .testTag("delegation_activity_stream")
  ) {
    Spacer(modifier = Modifier.height(5.dp))
    Text("Its own work", color = AwakiTheme.extra.textMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    blocks.forEach { block ->
      Spacer(modifier = Modifier.height(5.dp))
      when (block) {
        is TextBlock -> if (block.text.isNotBlank()) {
          MarkdownText(text = block.text, streaming = block.streaming, modifier = Modifier.fillMaxWidth())
        }
        is ReasoningBlock -> ThinkingBlock(block)
        is ActionBlock -> ToolCallRow(
          item = block,
          showToolJson = showToolJson,
          pausedDelegations = pausedDelegations,
          onPauseSubagent = onPauseSubagent,
          onResumeSubagent = onResumeSubagent
        )
        is ApprovalBlock -> ApprovalCard(item = block, onAllow = {}, onDeny = {}, onAnswer = {}, onReopen = {})
        is ErrorBlock -> ErrorCard(block, showRetry = false, onRetry = {})
        is CompactionBlock -> CompactionCard(block)
      }
    }
  }
}

@Composable
internal fun ToolCallRow(
  item: ActionBlock,
  showToolJson: Boolean = false,
  onCancelTool: () -> Unit = {},
  onRetryTool: () -> Unit = {},
  onContinueTool: () -> Unit = {},
  /** The specialists the user is holding still, by the id of their delegate call. */
  pausedDelegations: Set<String> = emptySet(),
  onPauseSubagent: (String) -> Unit = {},
  onResumeSubagent: (String) -> Unit = {}
) {
  val subagentPaused = item.callId in pausedDelegations
  var expanded by remember(item.id) { mutableStateOf(false) }
  val clipboard = LocalClipboardManager.current
  val (verb, target) = friendlyToolLabel(item.name, item.argsJson)
  val icon = toolIcon(item.name)
  val iconColor = toolColor(item.name)
  // File edits render as an inline git-style diff instead of raw JSON.
  val diffLines = remember(item.argsJson) { editDiffForTool(item.name, item.argsJson) }
  // Terminal cards show the real shell command instead of {"command": …}.
  val command = remember(item.argsJson) { displayCommandForTool(item.name, item.argsJson) }
  val statusColor = when {
    subagentPaused -> AwakiTheme.extra.warning
    item.running -> MaterialTheme.colorScheme.primary
    item.cancelled -> AwakiTheme.extra.warning
    item.success == false -> MaterialTheme.colorScheme.error
    item.success == true -> AwakiTheme.extra.success
    else -> iconColor
  }
  val statusIcon = when {
    subagentPaused -> Icons.Default.Pause
    item.cancelled -> Icons.Default.Stop
    item.success == false -> Icons.Default.Close
    item.success == true -> Icons.Default.Check
    else -> icon
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
      .border(
        1.dp,
        statusColor.copy(alpha = if (item.running) 0.45f else 0.28f),
        RoundedCornerShape(8.dp)
      )
      .combinedClickable(
        onClick = { if (item.detail.isNotBlank() || item.argsJson.isNotBlank() || (diffLines != null && diffLines.isNotEmpty())) expanded = !expanded },
        onLongClick = { clipboard.setText(AnnotatedString(item.detail.ifBlank { item.argsJson })) }
      )
      .padding(horizontal = 10.dp, vertical = 6.dp)
      .testTag("stream_tool_${item.name}")
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      StatusCircle(icon = statusIcon, color = statusColor, filled = true)
      Spacer(modifier = Modifier.width(8.dp))
      Text(verb, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      if (target.isNotBlank()) {
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          target,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false)
        )
      }
      Spacer(modifier = Modifier.weight(1f))
      // A settled step states its result in the leading circle; only a running
      // one still needs the right-hand spinner and its own kill switch.
      if (item.running) {
        // A delegated card is held and released by its own control: the spinner
        // says it is working, and it stops saying so the moment the user holds it.
        if (item.delegation == null || !subagentPaused) {
          CircularProgressIndicator(modifier = Modifier.size(12.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 1.8.dp)
        }
        if (item.delegation != null && item.callId.isNotBlank()) {
          Spacer(modifier = Modifier.width(4.dp))
          IconButton(
            onClick = { if (subagentPaused) onResumeSubagent(item.callId) else onPauseSubagent(item.callId) },
            modifier = Modifier
              .size(22.dp)
              .clip(RoundedCornerShape(6.dp))
              .testTag(if (subagentPaused) "btn_resume_subagent" else "btn_pause_subagent")
          ) {
            Icon(
              imageVector = if (subagentPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
              contentDescription = if (subagentPaused) "Resume this agent" else "Pause this agent",
              tint = if (subagentPaused) AwakiTheme.extra.success else AwakiTheme.extra.warning,
              modifier = Modifier.size(13.dp)
            )
          }
        }
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
            Icon(Icons.Default.Stop, contentDescription = "Cancel this step", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(13.dp))
          }
        }
      }
      if (!item.running && item.detail.isNotBlank()) {
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
          Icons.Outlined.ContentCopy,
          contentDescription = "Copy output",
          tint = AwakiTheme.extra.textMuted,
          modifier = Modifier
            .size(12.dp)
            .clickable { clipboard.setText(AnnotatedString(item.detail)) }
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
          Icons.Default.KeyboardArrowDown,
          contentDescription = if (expanded) "Collapse details" else "Expand details",
          tint = AwakiTheme.extra.textMuted,
          modifier = Modifier.size(14.dp)
        )
      }
    }

    // The brief the orchestrator handed this agent, as the card's first entry.
    // Collapsed by default: reading the instructions is deliberate, watching the
    // specialist work is not.
    item.delegation?.let { brief ->
      var briefOpen by remember(item.id) { mutableStateOf(false) }
      Spacer(modifier = Modifier.height(5.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          if (briefOpen) "Hide the brief" else "Brief for ${brief.role.ifBlank { "this agent" }}",
          color = MaterialTheme.colorScheme.primary,
          fontSize = 10.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier
            .clickable { briefOpen = !briefOpen }
            .testTag("btn_toggle_delegation_brief")
        )
        Spacer(modifier = Modifier.weight(1f))
        if (brief.description.isNotBlank()) {
          Text(
            brief.description,
            color = AwakiTheme.extra.textMuted,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
          )
        }
      }
      if (briefOpen && brief.prompt.isNotBlank()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          brief.prompt,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontSize = 11.sp,
          lineHeight = 15.sp,
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(8.dp)
            .testTag("delegation_brief_text")
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
          .background(MaterialTheme.colorScheme.background)
          .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          Icons.Outlined.ContentCopy,
          contentDescription = "Copy command",
          tint = AwakiTheme.extra.textMuted,
          modifier = Modifier
            .size(13.dp)
            .clickable { clipboard.setText(AnnotatedString(command)) }
            .testTag("btn_copy_command")
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
          "\$ ${command.trim()}",
          color = AwakiTheme.extra.textCode,
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
    if (!item.running && (expanded || item.cancelled || item.success == false || (item.summary.isNotBlank() && item.summary != target))) {
      Spacer(modifier = Modifier.height(3.dp))
      Text(
        text = if (item.cancelled) "Cancelled by user" else item.summary,
        color = when {
          item.cancelled -> AwakiTheme.extra.warning
          item.success == false -> MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
          else -> AwakiTheme.extra.textMuted
        },
        fontSize = 10.sp,
        maxLines = if (expanded) Int.MAX_VALUE else 1,
        overflow = TextOverflow.Ellipsis
      )
    }

    // A delegated agent's work, live and in order — the same cards the
    // orchestrator gets for its own actions. Collapsed by default: one answer
    // should not arrive as ten parallel streams the user has to scroll past.
    if (item.children.isNotEmpty()) {
      var workOpen by remember(item.id) { mutableStateOf(false) }
      val liveChild = item.children.filterIsInstance<ActionBlock>().lastOrNull { it.running }
      val headline = when {
        subagentPaused -> "paused"
        liveChild != null -> friendlyToolLabel(liveChild.name, liveChild.argsJson).first
        item.running -> "thinking"
        else -> "finished"
      }
      Spacer(modifier = Modifier.height(6.dp))
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(6.dp))
          .background(MaterialTheme.colorScheme.background)
          .clickable { workOpen = !workOpen }
          .padding(horizontal = 8.dp, vertical = 5.dp)
          .testTag("btn_toggle_subagent_work")
      ) {
        Icon(
          imageVector = if (workOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
          contentDescription = null,
          tint = AwakiTheme.extra.textMuted,
          modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
          text = if (workOpen) "Hide" else "Show",
          color = MaterialTheme.colorScheme.primary,
          fontSize = 10.5.sp,
          fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = "${item.children.size} steps · $headline",
          color = AwakiTheme.extra.textMuted,
          fontSize = 10.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
        if (liveChild != null && !subagentPaused) {
          CircularProgressIndicator(
            modifier = Modifier.size(10.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 1.4.dp
          )
        }
      }
      if (workOpen) DelegationActivityStream(
        blocks = item.children,
        showToolJson = showToolJson,
        pausedDelegations = pausedDelegations,
        onPauseSubagent = onPauseSubagent,
        onResumeSubagent = onResumeSubagent
      )
    }

    // Git-style diff for file edits: -removed / +added with line numbers.
    if (diffLines != null && diffLines.isNotEmpty()) {
      Spacer(modifier = Modifier.height(6.dp))
      val (added, removed) = diffStats(diffLines)
      var showAllDiff by remember(item.id) { mutableStateOf(false) }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("+$added", color = AwakiTheme.extra.success, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.width(6.dp))
        Text("\u2212$removed", color = MaterialTheme.colorScheme.error, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
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
          color = MaterialTheme.colorScheme.primary,
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
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
          modifier = Modifier.height(28.dp).testTag("btn_retry_tool")
        ) { Text("Retry", color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
        Button(
          onClick = onContinueTool,
          colors = ButtonDefaults.buttonColors(
            containerColor = AwakiTheme.extra.success,
            contentColor = AwakiTheme.extra.onSuccess
          ),
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
          modifier = Modifier.height(28.dp).testTag("btn_continue_tool")
        ) { Text("Continue", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
      }
    }

    if (expanded) {
      Spacer(modifier = Modifier.height(6.dp))
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(6.dp))
          .background(MaterialTheme.colorScheme.background)
          .padding(8.dp)
      ) {
        // Raw request JSON is opt-in (Settings → Chat Tool Activity).
        if (showToolJson && item.argsJson.isNotBlank() && item.argsJson != "{}") {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Request (raw JSON)", color = AwakiTheme.extra.textMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))
            Icon(
              Icons.Outlined.ContentCopy,
              contentDescription = "Copy arguments",
              tint = AwakiTheme.extra.textMuted,
              modifier = Modifier
                .size(11.dp)
                .clickable { clipboard.setText(AnnotatedString(item.argsJson)) }
            )
          }
          Text(prettyJson(item.argsJson), color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
          Spacer(modifier = Modifier.height(6.dp))
        }
        if (item.detail.isNotBlank()) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Output", color = AwakiTheme.extra.textMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))
            Icon(
              Icons.Outlined.ContentCopy,
              contentDescription = "Copy output",
              tint = AwakiTheme.extra.textMuted,
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
            Text(item.detail, color = AwakiTheme.extra.textCode, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
          }
        }
        item.exitCode?.let {
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            "exit code: $it",
            color = if (it == 0) AwakiTheme.extra.success else MaterialTheme.colorScheme.error,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
          )
        }
      }
    }
  }
}

@Composable
internal fun ApprovalCard(
  item: ApprovalBlock,
  onAllow: () -> Unit,
  onDeny: (String?) -> Unit,
  onAnswer: (String) -> Unit,
  onReopen: () -> Unit
) {
  val clipboard = LocalClipboardManager.current
  val isTerminal = item.command.isNotBlank()
  val accent = if (item.isQuestion) MaterialTheme.colorScheme.primary else AwakiTheme.extra.warning

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .cardWithRail(accent, MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
      .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
      .testTag("stream_approval")
  ) {
    // The title says who is asking — a delegated agent's command strip alone
    // would not name the agent that wants to run it.
    Row(verticalAlignment = Alignment.CenterVertically) {
      StatusCircle(
        icon = if (item.isQuestion) Icons.Outlined.QuestionAnswer else Icons.Outlined.Terminal,
        color = accent
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        item.title,
        color = accent,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f)
      )
    }

    if (isTerminal) {
      Spacer(modifier = Modifier.height(6.dp))
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(6.dp))
          .background(MaterialTheme.colorScheme.background)
          .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          "$ ",
          color = MaterialTheme.colorScheme.secondary,
          fontSize = 12.sp,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold
        )
        Text(
          item.command,
          color = AwakiTheme.extra.textCode,
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace,
          softWrap = false,
          maxLines = 1,
          modifier = Modifier
            .weight(1f)
            .horizontalScroll(rememberScrollState())
        )
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
          Icons.Outlined.ContentCopy,
          contentDescription = "Copy command",
          tint = AwakiTheme.extra.textMuted,
          modifier = Modifier
            .size(13.dp)
            .clickable { clipboard.setText(AnnotatedString(item.command)) }
            .testTag("btn_copy_command")
        )
      }
    }

    if (item.rationale.isNotBlank()) {
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        "“${item.rationale}”",
        color = MaterialTheme.colorScheme.error,
        fontSize = 11.sp,
        lineHeight = 15.sp
      )
    } else if (item.impact.isNotBlank() && item.impact != item.command) {
      Spacer(modifier = Modifier.height(4.dp))
      Text(item.impact, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp)
    }

    Spacer(modifier = Modifier.height(6.dp))

    val unanswered = !item.resolved

    when {
      unanswered -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          if (item.isQuestion) "Waiting for your answer — choose below."
          else "Waiting for your decision — nothing has run yet.",
          color = AwakiTheme.extra.textMuted,
          fontSize = 11.sp
        )
        if (item.isQuestion) {
          item.options.forEachIndexed { index, option ->
            Button(
              onClick = { onAnswer(option) },
              colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
              contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
              modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .testTag("btn_card_answer_$index")
            ) { Text(option, color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp) }
          }
          TextButton(
            onClick = onReopen,
            modifier = Modifier.testTag("btn_reopen_dialog")
          ) { Text("Answer in the dialog…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
        } else {
          var rationale by remember { mutableStateOf("") }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
              onClick = onAllow,
              colors = ButtonDefaults.buttonColors(
                containerColor = AwakiTheme.extra.success,
                contentColor = AwakiTheme.extra.onSuccess
              ),
              contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
              modifier = Modifier.height(30.dp).testTag("btn_allow_tool")
            ) { Text("Allow", fontSize = 11.sp) }
            Button(
              onClick = { onDeny(rationale.trim().ifBlank { null }) },
              colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
              contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
              modifier = Modifier.height(30.dp).testTag("btn_deny_tool")
            ) { Text("Deny", color = MaterialTheme.colorScheme.onError, fontSize = 11.sp) }
          }
          OutlinedTextField(
            value = rationale,
            onValueChange = { rationale = it },
            modifier = Modifier
              .fillMaxWidth()
              .testTag("card_deny_reason"),
            placeholder = { Text(item.freeTextLabel, color = AwakiTheme.extra.textMuted, fontSize = 11.sp) },
            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = AwakiTheme.extra.warning,
              unfocusedBorderColor = MaterialTheme.colorScheme.outline,
              cursorColor = AwakiTheme.extra.warning,
              focusedTextColor = MaterialTheme.colorScheme.onSurface,
              unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            )
          )
          TextButton(
            onClick = onReopen,
            modifier = Modifier.testTag("btn_reopen_dialog")
          ) { Text("Open the dialog…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
        }
      }

      item.answer.isNotBlank() -> Text(
        "Answered: ${item.answer}",
        color = AwakiTheme.extra.success,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
      )
      item.stalled -> Text(
        "Turn stopped before you decided — nothing was run.",
        color = AwakiTheme.extra.textMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
      )
      item.isQuestion -> Text(
        "Not answered",
        color = AwakiTheme.extra.textMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
      )
      else -> {
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (item.allowed) {
            Icon(Icons.Default.Check, contentDescription = null, tint = AwakiTheme.extra.success, modifier = Modifier.size(13.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Allowed", color = AwakiTheme.extra.success, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
          } else {
            Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(13.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Denied", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
          }
        }
      }
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
      .background(AwakiTheme.extra.success.copy(alpha = 0.06f))
      .border(1.dp, AwakiTheme.extra.success.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
      .padding(12.dp)
      .testTag("chat_final_response")
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        Icons.Default.CheckCircle,
        contentDescription = null,
        tint = AwakiTheme.extra.success,
        modifier = Modifier.size(14.dp)
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        "Response",
        color = AwakiTheme.extra.success,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold
      )
      Spacer(modifier = Modifier.weight(1f))
      Icon(
        Icons.Outlined.ContentCopy,
        contentDescription = "Copy response",
        tint = AwakiTheme.extra.textMuted,
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
      .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f))
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
      .clickable { expanded = !expanded }
      .padding(horizontal = 10.dp, vertical = 7.dp)
      .testTag("stream_thinking")
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        Icons.Default.Psychology,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.secondary.copy(alpha = if (block.streaming) 1f else 0.6f),
        modifier = Modifier.size(13.dp)
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        if (block.streaming) "Thinking..." else "Thoughts",
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold
      )
      Spacer(modifier = Modifier.weight(1f))
      Text(if (expanded) "v" else ">", color = AwakiTheme.extra.textMuted, fontSize = 10.sp)
    }
    if (expanded) {
      Spacer(modifier = Modifier.height(5.dp))
      Text(
        block.text,
        color = AwakiTheme.extra.textMuted,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontFamily = FontFamily.Monospace
      )
    }
  }
}

/** Single red error card for runtime/stream/provider failures. */
@Composable
internal fun ErrorCard(block: ErrorBlock, showRetry: Boolean, onRetry: () -> Unit) {
  val clipboard = LocalClipboardManager.current
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .cardWithRail(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
      .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
      .testTag("stream_error_card")
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      StatusCircle(icon = Icons.Default.Close, color = MaterialTheme.colorScheme.error)
      Spacer(modifier = Modifier.width(8.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text("Error", color = MaterialTheme.colorScheme.error, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
        Text(
          text = block.message,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontSize = 11.5.sp,
          lineHeight = 15.sp,
          maxLines = 4,
          overflow = TextOverflow.Ellipsis
        )
      }
      Icon(
        Icons.Outlined.ContentCopy,
        contentDescription = "Copy error",
        tint = AwakiTheme.extra.textMuted,
        modifier = Modifier
          .size(12.dp)
          .clickable { clipboard.setText(AnnotatedString(block.message)) }
      )
    }
    if (showRetry) {
      Spacer(modifier = Modifier.height(6.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = onRetry,
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
          modifier = Modifier.height(28.dp).testTag("btn_retry_turn")
        ) {
          Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(12.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Retry", color = MaterialTheme.colorScheme.onError, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 4.dp)
  ) {
    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun AgentComposer(
  viewModel: WorkspaceViewModel,
  selectedModel: com.awaki.settings.model.AIModel?,
  providers: List<com.awaki.settings.model.AIProvider>,
  models: List<com.awaki.settings.model.AIModel>,
  permissions: com.awaki.agent.model.AgentPermissions,
  contextUsage: com.awaki.agent.compact.ContextTokenUsage?,
  promptText: String,
  onPromptChange: (String) -> Unit,
  isWorking: Boolean,
  onSend: () -> Unit,
  onPause: () -> Unit,
  onStop: () -> Unit
) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 10.dp, vertical = 6.dp)
      .clip(RoundedCornerShape(14.dp))
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
  ) {
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
      // Clean multiline input area
      TextField(
        value = promptText,
        onValueChange = onPromptChange,
        placeholder = { Text("Ask for follow-up changes…", color = AwakiTheme.extra.textMuted, fontSize = 13.sp) },
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 40.dp, max = 110.dp)
          .testTag("agent_prompt_input"),
        colors = TextFieldDefaults.colors(
          focusedContainerColor = Color.Transparent,
          unfocusedContainerColor = Color.Transparent,
          focusedIndicatorColor = Color.Transparent,
          unfocusedIndicatorColor = Color.Transparent,
          focusedTextColor = MaterialTheme.colorScheme.onSurface,
          unfocusedTextColor = MaterialTheme.colorScheme.onSurface
        ),
        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 18.sp)
      )

      Spacer(modifier = Modifier.height(4.dp))

      // Bottom Row: Model & Config Controls on left, Integrated Send/Action on right
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        // Controls Row
        Row(
          modifier = Modifier.weight(1f, fill = false),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Model dropdown
          val providerName = selectedModel?.let { m -> providers.firstOrNull { it.id == m.providerId }?.name }
          ConfigDropdown(
            label = selectedModel?.displayName ?: "Select model",
            sublabel = providerName,
            tint = if (selectedModel == null) AwakiTheme.extra.textMuted else MaterialTheme.colorScheme.primary,
            leadingIcon = Icons.Default.AutoAwesome,
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

          // File editing policy dropdown
          ConfigDropdown(
            label = when {
              permissions.planMode -> "Plan mode"
              permissions.fileEditing == PermissionMode.ALWAYS_ASK -> "Edits: ask"
              permissions.fileEditing == PermissionMode.AUTO_APPROVE_PROJECT -> "Edits: auto"
              permissions.fileEditing == PermissionMode.NEVER_ALLOW -> "Edits: off"
              else -> "Edits: ask"
            },
            tint = if (permissions.planMode) MaterialTheme.colorScheme.primary
              else if (permissions.fileEditing == PermissionMode.NEVER_ALLOW) MaterialTheme.colorScheme.error
              else AwakiTheme.extra.success,
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
            tint = if (permissions.terminalCommands == PermissionMode.ALLOW_ALL) AwakiTheme.extra.warning else AwakiTheme.extra.success,
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

          // Context Usage Chip
          contextUsage?.let { usage -> ContextUsageChip(usage) }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Integrated Send/Stop/Pause Button
        if (isWorking) {
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(
              onClick = onPause,
              modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(AwakiTheme.extra.warning)
                .testTag("btn_composer_pause")
            ) {
              Icon(Icons.Default.Pause, contentDescription = "Pause", tint = AwakiTheme.extra.onWarning, modifier = Modifier.size(16.dp))
            }
            IconButton(
              onClick = onStop,
              modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error)
                .testTag("btn_composer_stop")
            ) {
              Icon(Icons.Default.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(16.dp))
            }
          }
        } else {
          val canSend = promptText.isNotBlank()
          IconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier
              .size(34.dp)
              .clip(CircleShape)
              .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
              .testTag("btn_send_agent_prompt")
          ) {
            Icon(
              Icons.AutoMirrored.Filled.Send,
              contentDescription = "Send",
              tint = if (canSend) MaterialTheme.colorScheme.onPrimary else AwakiTheme.extra.textMuted,
              modifier = Modifier.size(15.dp)
            )
          }
        }
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
private fun ContextUsageChip(usage: com.awaki.agent.compact.ContextTokenUsage) {
  if (usage.contextWindow <= 0) return
  val tint = when {
    usage.isAboveThreshold -> MaterialTheme.colorScheme.error
    usage.pressurePercent >= 85 -> AwakiTheme.extra.warning
    usage.pressurePercent >= 60 -> MaterialTheme.colorScheme.primary
    else -> AwakiTheme.extra.textMuted
  }
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
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
    color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
    border = androidx.compose.foundation.BorderStroke(
      1.dp,
      if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
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
        tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(13.dp)
      )
      Spacer(modifier = Modifier.width(5.dp))
      Text(
        "Plan",
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
      .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
      .padding(horizontal = 12.dp, vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      Icons.Outlined.Description,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(12.dp)
    )
    Spacer(modifier = Modifier.width(6.dp))
    Text(
      text = "Plan mode: the agent reads, researches and plans. It will refuse any change until you turn this off.",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
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
  tint: Color = Color.Unspecified,
  leadingIcon: ImageVector? = null
) {
  val labelColor = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint
  var expanded by remember { mutableStateOf(false) }
  Box(modifier = modifier) {
    Surface(
      shape = RoundedCornerShape(6.dp),
      color = MaterialTheme.colorScheme.surface,
      border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      modifier = Modifier.clickable { expanded = true }
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
      ) {
        if (leadingIcon != null) {
          Icon(leadingIcon, contentDescription = null, tint = labelColor, modifier = Modifier.size(11.dp))
          Spacer(modifier = Modifier.width(4.dp))
        }
        Column {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = labelColor, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(modifier = Modifier.width(3.dp))
            Text("▾", color = AwakiTheme.extra.textMuted, fontSize = 9.sp)
          }
          sublabel?.let {
            Text(it, color = AwakiTheme.extra.textMuted, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
      }
    }
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      containerColor = MaterialTheme.colorScheme.surfaceContainer,
      modifier = Modifier.heightIn(max = 360.dp)
    ) {
      options.forEach { option ->
        if (option.header) {
          Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(option.label, color = AwakiTheme.extra.textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
          }
        } else {
          DropdownMenuItem(
            text = {
              Row(verticalAlignment = Alignment.CenterVertically) {
                if (option.checked) {
                  Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                }
                Column {
                  Text(
                    option.label,
                    color = if (option.configure) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp
                  )
                  option.sublabel?.let {
                    Text(it, color = AwakiTheme.extra.textMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
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
    "delegate" -> "Delegating to ${arg("role")}" to arg("description")
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

@Composable
private fun toolColor(name: String): Color = when (toolTypeFor(name)) {
  ToolType.READ_FILE -> MaterialTheme.colorScheme.secondary
  ToolType.SEARCH -> AwakiTheme.extra.warning
  ToolType.TERMINAL -> AwakiTheme.extra.success
  ToolType.EDIT_FILE -> MaterialTheme.colorScheme.primary
  ToolType.GIT -> MaterialTheme.colorScheme.tertiary
  ToolType.BUILD -> AwakiTheme.extra.warning
  ToolType.WEB -> MaterialTheme.colorScheme.primary
  ToolType.QUESTION -> AwakiTheme.extra.success
}

private fun prettyJson(raw: String): String = runCatching {
  JSONObject(raw).toString(2)
}.getOrDefault(raw)

/** Collapsed diff shows a bounded preview; the user can expand the rest. */
private const val DIFF_PREVIEW_LINES = 14

/**
 * Human-readable shell command for terminal-family tools, or null when the
 * tool isn't one. Script tools mirror [com.awaki.agent.tool.ScriptTool]'s
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
