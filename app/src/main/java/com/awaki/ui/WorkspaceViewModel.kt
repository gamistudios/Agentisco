package com.awaki.ui

import com.awaki.settings.model.AIProvider
import com.awaki.settings.model.AIModel
import com.awaki.agent.model.AgentPermissions
import com.awaki.agent.model.PendingApproval
import com.awaki.agent.model.ToolExecution
import com.awaki.agent.model.AgentTaskStep
import com.awaki.agent.model.AgentStreamEvent
import com.awaki.core.model.AppDestination
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awaki.data.local.chat.AgentBlockEntity
import com.awaki.data.local.chat.AgentMessageEntity
import com.awaki.data.local.chat.AgentSessionEntity
import com.awaki.data.model.*
import com.awaki.data.repository.AgentChatStore
import com.awaki.data.repository.ChatHistoryMessage as AgentHistoryMessage
import com.awaki.data.repository.WorkspaceRepository
import com.awaki.editor.model.EditorSettings
import com.awaki.editor.model.EditorTab
import com.awaki.editor.syntax.Language
import com.awaki.workspace.git.*
import com.awaki.workspace.buildrun.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModel(
  val repository: WorkspaceRepository = WorkspaceRepository()
) : ViewModel() {

  val projects: StateFlow<List<Project>> = repository.projects
  val activeProject: StateFlow<Project> = repository.activeProject
  val currentDestination: StateFlow<AppDestination> = repository.currentDestination
  val projectFiles: StateFlow<List<ProjectFile>> = repository.projectFiles
  val dirChildren: StateFlow<Map<String, List<ProjectFile>>> = repository.dirChildren
  val isFilesLoading: StateFlow<Boolean> = repository.isFilesLoading
  val nameSearchResults: StateFlow<List<ProjectFile>> = repository.nameSearchResults
  val activeFile: StateFlow<ProjectFile> = repository.activeFile
  val editorContent: StateFlow<String> = repository.editorContent
  val isEditorDirty: StateFlow<Boolean> = repository.isEditorDirty

  // Multi-file editor tab management
  private val _openTabs = MutableStateFlow<List<EditorTab>>(emptyList())
  val openTabs: StateFlow<List<EditorTab>> = _openTabs.asStateFlow()

  private val _activeTabIndex = MutableStateFlow(0)
  val activeTabIndex: StateFlow<Int> = _activeTabIndex.asStateFlow()

  private val _recentlyClosedTabs = MutableStateFlow<List<EditorTab>>(emptyList())
  val recentlyClosedTabs: StateFlow<List<EditorTab>> = _recentlyClosedTabs.asStateFlow()
  val hasClosedTabs: StateFlow<Boolean> = _recentlyClosedTabs.map { it.isNotEmpty() }
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val editorSettings: StateFlow<EditorSettings> = repository.editorSettings

  /** The theme the whole app paints with; the Theme gallery in Settings writes here. */
  val uiTheme: StateFlow<com.awaki.ui.theme.UiPalette> = repository.uiTheme

  fun setUiTheme(key: String) = repository.setUiTheme(key)

  val isAgentWorking: StateFlow<Boolean> = repository.isAgentWorking
  val agentStatusText: StateFlow<String> = repository.agentStatusText
  /** Delegate call id -> whether the user is holding that specialist still. */
  val delegationPhases: StateFlow<Map<String, Boolean>> = repository.delegationPhases

  // ---- Background execution ----
  private val background = repository.backgroundExecution

  val backgroundRequirements: StateFlow<List<com.awaki.background.BackgroundRequirement>> =
    background?.requirements ?: MutableStateFlow(emptyList())

  val allowBackgroundExecution: StateFlow<Boolean> =
    background?.allowBackgroundExecution ?: MutableStateFlow(false)

  val backgroundWakeLockEnabled: StateFlow<Boolean> =
    background?.wakeLockEnabled ?: MutableStateFlow(false)

  val terminalHeld: StateFlow<Boolean> = background?.terminalHeld ?: MutableStateFlow(false)

  /** Which of the app's alerting notifications the user still wants to be interrupted by. */
  val workAlerts: StateFlow<com.awaki.background.WorkAlerts> =
    background?.alerts ?: MutableStateFlow(
      com.awaki.background.WorkAlerts(approvalRequested = true, interruptedWork = true, updateReady = true)
    )

  fun setAlertApprovalRequested(enabled: Boolean) {
    background?.setAlertApprovalRequested(enabled)
  }

  fun setAlertInterruptedWork(enabled: Boolean) {
    background?.setAlertInterruptedWork(enabled)
  }

  fun setAlertUpdateReady(enabled: Boolean) {
    background?.setAlertUpdateReady(enabled)
  }

  /** Work a previous process was running when it died, offered back once. */
  val interruptedBackgroundWork: StateFlow<List<com.awaki.background.JournalEntry>> =
    background?.interruptedWork ?: MutableStateFlow(emptyList())

  /** True while the UI should ask for notification permission. */
  val notificationsPromptRequested: StateFlow<Boolean> =
    background?.notificationsPromptRequested ?: MutableStateFlow(false)

  /** True when a work notification, not the launcher icon, brought the user back. */
  val openedFromNotification: StateFlow<Boolean> =
    background?.openedFromNotification ?: MutableStateFlow(false)

  fun setAllowBackgroundExecution(enabled: Boolean) {
    background?.setAllowBackgroundExecution(enabled)
  }

  fun setBackgroundWakeLockEnabled(enabled: Boolean) {
    background?.setWakeLockEnabled(enabled)
  }

  fun setTerminalHeld(enabled: Boolean) {
    background?.setTerminalHold(enabled)
  }

  fun markNotificationsAsked() {
    background?.markNotificationsAsked()
  }

  fun dismissNotificationsPrompt() {
    background?.resolveNotificationsPrompt()
  }

  /** The tap already landed where it promised; the flag must not fire twice. */
  fun acknowledgeOpenedFromNotification() {
    background?.acknowledgeOpenedFromNotification()
  }

  /** The user has seen the interruption in the app; the notice is spent. */
  fun acknowledgeInterruptedWork() {
    background?.consumeInterruptedWork()
  }

  fun performBackgroundAction(
    context: android.content.Context,
    action: com.awaki.background.RequirementAction
  ): Boolean = background?.let { com.awaki.background.BackgroundPermissions.perform(context, action) } ?: false

  val agentSteps: StateFlow<List<AgentTaskStep>> = repository.agentSteps
  val toolExecutions: StateFlow<List<ToolExecution>> = repository.toolExecutions
  val pendingApproval: StateFlow<PendingApproval?> = repository.pendingApproval
  /** True when a request was parked in the chat instead of answered in the dialog. */
  val approvalDeferred: StateFlow<Boolean> = repository.approvalDeferred

  val fileDiffs: StateFlow<List<FileDiff>> = repository.fileDiffs
  val stagedFiles: StateFlow<Set<String>> = repository.stagedFiles
  val commitMessage: StateFlow<String> = repository.commitMessage
  val commitHistory: StateFlow<List<GitCommit>> = repository.commitHistory
  val repoStatus: StateFlow<GitRepoStatus> = repository.repoStatus
  val branches: StateFlow<List<GitBranch>> = repository.branches
  val stashes: StateFlow<List<GitStash>> = repository.stashes
  val remotes: StateFlow<List<GitRemote>> = repository.remotes
  val tags: StateFlow<List<String>> = repository.tags
  val activeGitOperationText: StateFlow<String?> = repository.activeGitOperationText
  val gitOperationFeedback: StateFlow<String?> = repository.gitOperationFeedback
  val gitError: StateFlow<String?> = repository.gitError
  val commitGenState: StateFlow<WorkspaceRepository.CommitGenState> = repository.commitGenState

  val terminalSessions: StateFlow<List<TerminalSession>> = repository.terminalSessions
  val activeTerminalSessionId: StateFlow<String> = repository.activeTerminalSessionId
  val ptySessions: StateFlow<Map<String, com.termux.terminal.TerminalSession>> = repository.ptySessions
  val linuxEnvironmentState: StateFlow<com.awaki.workspace.terminal.LinuxEnvironmentState> =
    repository.debianBootstrap?.state
      ?: MutableStateFlow(
        com.awaki.workspace.terminal.LinuxEnvironmentState.Failed(
          "Linux terminal unavailable: repository was created without an application context."
        )
      )

  val providers: StateFlow<List<AIProvider>> = repository.providers
  val aiModels: StateFlow<List<AIModel>> = repository.aiModels
  val selectedModel: StateFlow<AIModel?> = repository.selectedModel
  val connectionTests: StateFlow<Map<String, WorkspaceRepository.ConnectionTestState>> = repository.connectionTests
  /** Models each provider says it serves, fetched on demand from the provider. */
  val modelCatalogs: StateFlow<Map<String, WorkspaceRepository.ModelCatalogState>> = repository.modelCatalogs
  val agentResponse: StateFlow<String> = repository.agentResponse

  // ---- Context usage & compaction ----
  /** Live share of the model's context window the current conversation uses. */
  val contextUsage: StateFlow<com.awaki.agent.compact.ContextTokenUsage> = repository.contextUsage
  val compactSettings: StateFlow<com.awaki.data.local.CompactSettings> = repository.compactSettings
  val compactionLog: StateFlow<List<com.awaki.agent.compact.CompactBoundary>> = repository.compactionLog

  fun setAutoCompactEnabled(enabled: Boolean) =
    repository.updateCompactSettings { it.copy(autoCompactEnabled = enabled) }

  fun setMicrocompactEnabled(enabled: Boolean) =
    repository.updateCompactSettings { it.copy(microcompactEnabled = enabled) }

  fun setContextUsageVisible(visible: Boolean) =
    repository.updateCompactSettings { it.copy(showContextUsage = visible) }

  fun setCompactThresholdPercent(percent: Int) =
    repository.updateCompactSettings { it.copy(thresholdPercent = percent.coerceIn(10, 200)) }

  fun setCompactKeepRecentRounds(rounds: Int) =
    repository.updateCompactSettings { it.copy(keepRecentRounds = rounds.coerceIn(0, 10)) }

  fun setManualCompactEnabled(enabled: Boolean) =
    repository.updateCompactSettings { it.copy(manualCompactEnabled = enabled) }

  /**
   * Summarizes the open conversation now, without asking the model for a turn.
   * The chat keeps every message on screen; only what the next request sends is
   * compressed.
   */
  fun compactNow() {
    if (repository.isAgentWorking.value) return
    val session = _activeSessionId.value ?: return
    viewModelScope.launch {
      var compacted: AgentStreamEvent.ContextCompacted? = null
      repository.compactConversationNow(session) { event ->
        if (event is AgentStreamEvent.ContextCompacted) compacted = event
      }
      val result = compacted ?: return@launch
      // The card belongs under the newest answer: a manual compaction starts no
      // turn of its own, so an invented message would render as an empty bubble.
      val turnUuid = chatStore.messagesWithBlocks(session).first()
        .lastOrNull { it.message.role == "assistant_turn" }?.message?.uuid ?: return@launch
      chatStore.insertBlock(
        AgentBlockEntity(
          uuid = AgentChatStore.newId(), messageUuid = turnUuid, kind = "compaction",
          name = "compaction", argsJson = "", status = "done",
          summary = result.boundary.describe(),
          detail = result.summary,
          exitCode = null, createdAt = System.currentTimeMillis()
        )
      )
    }
  }

  // ---- Persistent agent chat (sessions → messages → turn blocks) ----
  // The UI list is derived entirely from SQLite; runtime events are written
  // through the AgentChatStore, so live streaming, restore, and dedupe share
  // one source of truth.

  private val chatStore: AgentChatStore get() = repository.chatStore

  private val _activeSessionId = MutableStateFlow<String?>(null)
  val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

  /** Track sessions that need auto-titling when their first message arrives */
  private val _pendingAutoTitleSessionId = MutableStateFlow<String?>(null)
  val pendingAutoTitleSessionId: StateFlow<String?> = _pendingAutoTitleSessionId.asStateFlow()

  val chatSessions: StateFlow<List<AgentSessionEntity>> = activeProject
    .flatMapLatest { project -> chatStore.sessionsForProject(project.path) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val chatItems: StateFlow<List<ChatItem>> = _activeSessionId
    .flatMapLatest { id ->
      if (id == null) flowOf(emptyList())
      else flow<List<com.awaki.data.local.chat.MessageWithBlocks>> {
        // Clear stale rows from the previous session before the new query
        // lands, so the chat UI can detect "freshly opened session" reliably.
        emit(emptyList())
        chatStore.messagesWithBlocks(id).collect { emit(it) }
      }
    }
    .map { rows -> rows.map { it.toChatItem() } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _sessionsPreviewProjectId = MutableStateFlow<String?>(null)

  /** Sessions of a specific project (opened from the Projects screen Chats sheet). */
  val projectSessionsPreview: StateFlow<List<AgentSessionEntity>> = _sessionsPreviewProjectId
    .flatMapLatest { id ->
      if (id == null) flowOf(emptyList()) else chatStore.sessionsForProject(id)
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  /** Recent tool activity for the active project (Projects screen overview). */
  val recentActivity: StateFlow<List<AgentBlockEntity>> = activeProject
    .flatMapLatest { project -> chatStore.recentBlocksForProject(project.path, 4) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val activeChatSession: StateFlow<AgentSessionEntity?> = chatSessions
    .map { sessions -> sessions.firstOrNull { it.id == _activeSessionId.value } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  private val _isAgentCancelled = MutableStateFlow(false)
  val isAgentCancelled: StateFlow<Boolean> = _isAgentCancelled.asStateFlow()

  /** True while a generation is paused (resumable) rather than cancelled. */
  private val _isAgentPaused = MutableStateFlow(false)

  private var agentJob: Job? = null

  // Per-turn stream bookkeeping: which persisted rows the live events map to.
  private var currentTurnUuid: String? = null
  private var turnText = StringBuilder()
  private var streamingTextBlockUuid: String? = null
  private var reasoningBlockUuid: String? = null
  private var reasoningText = StringBuilder()
  private var reasoningFlushJob: Job? = null
  private var runningToolBlocks = mutableMapOf<String, String>() // tool name -> block uuid
  private var approvalBlocks = mutableMapOf<String, String>() // approvalId -> block uuid
  private var textFlushJob: Job? = null

  /**
   * One delegated agent's live stream, held per delegation so two specialists
   * running beside each other never write into one another's cards.
   */
  private class DelegationStream {
    val toolBlocks = mutableMapOf<String, String>()
    val approvalBlocks = mutableMapOf<String, String>()
    var textUuid: String? = null
    var text = StringBuilder()
    var textJob: Job? = null
    var reasoningUuid: String? = null
    var reasoning = StringBuilder()
    var reasoningJob: Job? = null
  }

  /** delegation call id -> the stream hanging under its card. */
  private var delegationStreams = mutableMapOf<String, DelegationStream>()

  /**
   * Points the streaming bookkeeping at a turn row that is ALREADY persisted.
   * Every block the run emits (text, tool, approval, error) is written with
   * [currentTurnUuid] as its messageUuid, so a turn without a matching
   * agent_messages row would stream into orphan blocks the UI never renders.
   */
  private fun pointAtTurn(turnUuid: String) {
    currentTurnUuid = turnUuid
    turnText = StringBuilder()
    streamingTextBlockUuid = null
    reasoningBlockUuid = null
    reasoningText = StringBuilder()
    runningToolBlocks = mutableMapOf()
    approvalBlocks = mutableMapOf()
    delegationStreams = mutableMapOf()
  }

  init {
    // The notification's Stop action asks the repository to end the turn; only this
    // view model can cancel the coroutine that drives it, so the hook lives here.
    repository.agentTurnStopRequested = { agentJob?.cancel() }
    viewModelScope.launch {
      repository.agentEvents.collect { event -> onAgentEvent(event) }
    }
    // Turns/blocks left "running" by a previous process become interrupted.
    viewModelScope.launch { chatStore.recoverInterrupted() }
    // Keep the context percentage correct even when nothing is running: every
    // recorded message and compaction of the open session feeds the estimate,
    // so opening a session shows the size the next request would have.
    viewModelScope.launch {
      combine(_activeSessionId, chatItems, isAgentWorking) { id, items, working -> Triple(id, items, working) }
        .collect { (id, items, working) ->
          if (id == null || working) return@collect
          refreshContextUsage(id, items)
          refreshLatestCompaction(id)
        }
    }
    // Follow project switches: restore that project's most recent session.
    viewModelScope.launch {
      var lastProjectPath: String? = null
      activeProject.collect { project ->
        if (project.path != lastProjectPath) {
          lastProjectPath = project.path
          if (!isAgentWorking.value) {
            _activeSessionId.value = chatStore.latestSession(project.path)?.id
          }
        }
      }
    }
    // Synchronize open tabs with active file
    viewModelScope.launch {
      activeFile.collect { file ->
        if (file.path.isNotBlank()) {
          val current = _openTabs.value
          val existingIdx = current.indexOfFirst { it.file.path == file.path }
          if (existingIdx >= 0) {
            _activeTabIndex.value = existingIdx
          } else {
            val newTab = EditorTab(
              id = file.path,
              file = file,
              content = file.content,
              savedContent = file.content
            )
            _openTabs.value = current + newTab
            _activeTabIndex.value = _openTabs.value.lastIndex
          }
        }
      }
    }
    viewModelScope.launch {
      editorContent.collect { content ->
        val current = _openTabs.value
        val idx = _activeTabIndex.value
        if (idx in current.indices) {
          val tab = current[idx]
          if (tab.content != content) {
            val updated = current.toMutableList()
            updated[idx] = tab.copy(content = content)
            _openTabs.value = updated
          }
        }
      }
    }
  }

  /**
   * Re-estimates the context usage of an idle conversation from the persisted
   * transcript, honouring any recorded compaction (the older messages are
   * represented by the summary, so they are not counted twice).
   */
  private suspend fun refreshContextUsage(sessionId: String, items: List<ChatItem>) {
    val model = selectedModel.value
    val window = model?.contextWindow ?: com.awaki.agent.compact.CompactPolicyConfig.DEFAULT_CONTEXT_WINDOW
    val policy = repository.compactSettings.value.toPolicyConfig(model?.contextWindow, model?.maxOutputTokens)
    val compaction = chatStore.latestCompactionBlocking(sessionId)
    val summaryMessages = chatStore.buildConversationMessages(sessionId)
      .filter { it.rowId == 0L || it.rowId > (compaction?.summarizedThroughRowId ?: 0L) }
      .map { historyToLlmMessage(it) }
    val systemTokens = com.awaki.agent.compact.estimateTokens(
      "You are Awaki, an elite senior software engineer working inside the mobile IDE \"Awaki\"."
    )
    val total = systemTokens + com.awaki.agent.compact.estimateMessageTokens(summaryMessages) +
      (compaction?.let { com.awaki.agent.compact.estimateTokens(it.summary) } ?: 0)
    repository.publishContextUsage(
      com.awaki.agent.compact.ContextTokenUsage(
        usedTokens = total,
        contextWindow = window,
        thresholdTokens = policy.thresholdTokens,
        source = com.awaki.agent.compact.TokenUsageSource.ESTIMATED,
        clearedToolResults = compaction?.clearedToolResults ?: 0
      )
    )
  }

  /** Converts a persisted history row into the message shape the meter counts. */
  private fun historyToLlmMessage(m: AgentHistoryMessage): com.awaki.agent.llm.LlmMessage = when (m.role) {
    "assistant_tool_call" -> com.awaki.agent.llm.LlmMessage(
      com.awaki.agent.llm.LlmRole.ASSISTANT, m.content,
      toolCalls = listOf(
        com.awaki.agent.llm.LlmToolCall(m.toolCallId ?: "call", m.toolName ?: "unknown", m.toolArgs ?: "{}")
      )
    )
    "tool" -> com.awaki.agent.llm.LlmMessage(
      com.awaki.agent.llm.LlmRole.TOOL, m.content,
      toolCallId = m.toolCallId, toolName = m.toolName
    )
    "assistant" -> com.awaki.agent.llm.LlmMessage(com.awaki.agent.llm.LlmRole.ASSISTANT, m.content)
    else -> com.awaki.agent.llm.LlmMessage(com.awaki.agent.llm.LlmRole.USER, m.content)
  }

  private fun onAgentEvent(event: AgentStreamEvent) {
    val turn = currentTurnUuid ?: return
    val session = _activeSessionId.value
    when (event) {
      // The user message + turn rows are already persisted at send time.
      is AgentStreamEvent.TaskStarted -> Unit
      is AgentStreamEvent.Status ->
        chatStore.updateMessageStatus(turn, "running", event.text)
      is AgentStreamEvent.ReasoningToken -> {
        val uuid = reasoningBlockUuid
        if (uuid == null) {
          val newUuid = AgentChatStore.newId()
          reasoningBlockUuid = newUuid
          reasoningText = StringBuilder(event.text)
          chatStore.insertBlock(
            AgentBlockEntity(
              uuid = newUuid, messageUuid = turn, kind = "reasoning", name = "",
              argsJson = "", status = "streaming", summary = event.text,
              detail = "", exitCode = null, createdAt = System.currentTimeMillis()
            )
          )
        } else {
          reasoningText.append(event.text)
          scheduleReasoningFlush(uuid, turn)
        }
      }
      is AgentStreamEvent.TextReset -> {
        // Keep everything streamed so far: partial text/thinking stay in the
        // database (marked done) and the next attempt starts a fresh block, so
        // an interrupted generation never loses visible progress.
        closeStreamingText(turn)
        streamingTextBlockUuid = null
        closeStreamingReasoning()
      }
      is AgentStreamEvent.Token -> {
        val uuid = streamingTextBlockUuid
        if (uuid == null) {
          val newUuid = AgentChatStore.newId()
          streamingTextBlockUuid = newUuid
          turnText = StringBuilder(event.text)
          chatStore.insertBlock(
            AgentBlockEntity(
              uuid = newUuid, messageUuid = turn, kind = "text", name = "",
              argsJson = "", status = "streaming", summary = event.text,
              detail = "", exitCode = null, createdAt = System.currentTimeMillis()
            )
          )
        } else {
          turnText.append(event.text)
          scheduleTextFlush(uuid, turn)
        }
      }
      is AgentStreamEvent.ToolStarted -> {
        closeStreamingText(turn)
        val uuid = AgentChatStore.newId()
        // Parallel batched calls can share a tool name — key by callId.
        if (event.callId.isNotBlank()) runningToolBlocks[event.callId] = uuid
        else runningToolBlocks[event.name] = uuid
        chatStore.insertBlock(
          AgentBlockEntity(
            uuid = uuid, messageUuid = turn, kind = "tool", name = event.name,
            argsJson = event.argsJson, status = "running", summary = "Running…",
            detail = "", exitCode = null, createdAt = System.currentTimeMillis(),
            callId = event.callId.ifBlank { null }
          )
        )
      }
      is AgentStreamEvent.ToolCancelled -> {
        // The user SIGKILLed this call; the card shows Retry / Continue.
        runningToolBlocks.remove(event.callId)?.let { uuid ->
          chatStore.updateBlockStatus(uuid, "cancelled")
        }
      }
      is AgentStreamEvent.ToolFinished -> {
        val uuid = runningToolBlocks.remove(event.callId.ifBlank { event.name })
        if (uuid != null) {
          chatStore.updateToolBlock(
            uuid = uuid,
            status = if (event.success) "success" else "failed",
            summary = event.summary, detail = event.detail, exitCode = event.exitCode
          )
        }
      }
      is AgentStreamEvent.ApprovalRequested -> {
        val uuid = AgentChatStore.newId()
        approvalBlocks[event.approvalId] = uuid
        chatStore.insertBlock(
          AgentBlockEntity(
            uuid = uuid, messageUuid = turn,
            kind = if (event.isQuestion) "question" else "approval",
            name = event.approvalId,
            argsJson = event.command, status = "pending", summary = event.title,
            detail = event.impact, exitCode = null, createdAt = System.currentTimeMillis(),
            // Persisted so a deferred question can still be answered from its card.
            optionsJson = if (event.options.isEmpty()) null else event.options.toJsonArray()
          )
        )
      }
      is AgentStreamEvent.ApprovalResolved -> {
        approvalBlocks.remove(event.approvalId)?.let { uuid ->
          // A stopped turn is not a refusal: "stalled" keeps the user from being
          // recorded as having denied something they never chose.
          val status = when {
            event.terminated -> "stalled"
            event.allowed -> "allowed"
            else -> "denied"
          }
          val answer = event.answer
          // The refusal's reason is stored where the impact text was, so the card
          // stops showing "what would happen" once the user has explained.
          if (!event.allowed && !event.rationale.isNullOrBlank()) {
            chatStore.updateBlockAnswer(uuid, status, event.rationale.trim())
          } else if (answer.isNullOrBlank()) {
            chatStore.updateBlockStatus(uuid, status)
          } else {
            chatStore.updateBlockAnswer(uuid, status, answer)
          }
        }
      }
      is AgentStreamEvent.Completed -> {
        finalizeTurn(turn, session, "completed", "")
        if (turnText.isBlank() && event.summary.isNotBlank()) {
          chatStore.updateMessageContent(turn, event.summary)
        }
      }
      is AgentStreamEvent.Failed -> finalizeTurn(turn, session, "failed", event.message)
      is AgentStreamEvent.Cancelled -> {
        val status = if (_isAgentPaused.value) "paused" else "cancelled"
        _isAgentPaused.value = false
        finalizeTurn(turn, session, status, event.message)
      }
      // The provider's context was compacted: record a one-line note in the
      // turn so the user can see why the model's memory starts from a summary.
      // No message is deleted — the transcript above stays complete.
      is AgentStreamEvent.ContextCompacted -> {
        chatStore.insertBlock(
          AgentBlockEntity(
            uuid = AgentChatStore.newId(), messageUuid = turn, kind = "compaction",
            name = "compaction", argsJson = "", status = "done",
            summary = event.boundary.describe(),
            detail = event.summary,
            exitCode = null, createdAt = System.currentTimeMillis()
          )
        )
      }
      // A delegated agent's own work: persisted against this turn but hung under
      // the delegate card that started it, so the user watches it happen instead
      // of only reading its report.
      is AgentStreamEvent.DelegationActivity -> onDelegationActivity(turn, event)
    }
  }

  /**
   * Persists one action from a delegated agent. The shape mirrors the mainline
   * stream above — a specialist does ordinary agent work, it just belongs inside
   * a card — with the delegation's call id recorded so it nests back in.
   */
  private fun onDelegationActivity(turn: String, activity: AgentStreamEvent.DelegationActivity) {
    val delegationId = activity.delegationId
    if (delegationId.isBlank()) return
    val stream = delegationStreams.getOrPut(delegationId) { DelegationStream() }
    fun insert(kind: String, name: String, argsJson: String, status: String, summary: String, detail: String): String {
      val uuid = AgentChatStore.newId()
      chatStore.insertBlock(
        AgentBlockEntity(
          uuid = uuid, messageUuid = turn, kind = kind, name = name,
          argsJson = argsJson, status = status, summary = summary, detail = detail,
          exitCode = null, createdAt = System.currentTimeMillis(),
          parentCallId = delegationId,
          callId = null
        )
      )
      return uuid
    }

    when (val event = activity.event) {
      is AgentStreamEvent.ToolStarted -> {
        closeDelegationText(stream)
        closeDelegationReasoning(stream)
        val uuid = insert("tool", event.name, event.argsJson, "running", "Running…", "")
        // Parallel calls in the specialist's own batch share a name — key by call id.
        stream.toolBlocks[event.callId.ifBlank { event.name }] = uuid
      }
      is AgentStreamEvent.ToolFinished -> {
        stream.toolBlocks.remove(event.callId.ifBlank { event.name })?.let { uuid ->
          chatStore.updateToolBlock(
            uuid = uuid,
            status = if (event.success) "success" else "failed",
            summary = event.summary, detail = event.detail, exitCode = event.exitCode
          )
        }
      }
      is AgentStreamEvent.ToolCancelled -> {
        stream.toolBlocks.remove(event.callId)?.let { uuid -> chatStore.updateBlockStatus(uuid, "cancelled") }
      }
      is AgentStreamEvent.Token -> {
        val uuid = stream.textUuid
        if (uuid == null) {
          stream.textUuid = insert("text", "", "", "streaming", event.text, "")
          stream.text = StringBuilder(event.text)
        } else {
          stream.text.append(event.text)
          stream.textJob?.cancel()
          val snapshot = stream.text.toString()
          stream.textJob = viewModelScope.launch {
            delay(250)
            chatStore.updateTextBlock(uuid, snapshot)
          }
        }
      }
      is AgentStreamEvent.ReasoningToken -> {
        val uuid = stream.reasoningUuid
        if (uuid == null) {
          stream.reasoningUuid = insert("reasoning", "", "", "streaming", event.text, "")
          stream.reasoning = StringBuilder(event.text)
        } else {
          stream.reasoning.append(event.text)
          stream.reasoningJob?.cancel()
          val snapshot = stream.reasoning.toString()
          stream.reasoningJob = viewModelScope.launch {
            delay(250)
            chatStore.updateTextBlock(uuid, snapshot)
          }
        }
      }
      is AgentStreamEvent.TextReset -> {
        // A retry: what streamed so far stays visible, the next attempt is its own block.
        closeDelegationText(stream)
        closeDelegationReasoning(stream)
      }
      is AgentStreamEvent.ApprovalRequested -> {
        val uuid = insert(
          kind = if (event.isQuestion) "question" else "approval",
          name = event.approvalId, argsJson = event.command, status = "pending",
          summary = event.title, detail = event.impact
        )
        stream.approvalBlocks[event.approvalId] = uuid
      }
      is AgentStreamEvent.ApprovalResolved -> {
        stream.approvalBlocks.remove(event.approvalId)?.let { uuid ->
          val status = when {
            event.terminated -> "stalled"
            event.allowed -> "allowed"
            else -> "denied"
          }
          val answer = event.answer
          if (!event.allowed && !event.rationale.isNullOrBlank()) {
            chatStore.updateBlockAnswer(uuid, status, event.rationale.trim())
          } else if (answer.isNullOrBlank()) {
            chatStore.updateBlockStatus(uuid, status)
          } else {
            chatStore.updateBlockAnswer(uuid, status, answer)
          }
        }
      }
      is AgentStreamEvent.Failed -> {
        closeDelegationText(stream)
        closeDelegationReasoning(stream)
        insert("error", "error", "", "failed", event.message, "")
      }
      // A specialist's context compaction is its own bookkeeping; the user is
      // watching its actions, and its report reaches the parent either way.
      is AgentStreamEvent.ContextCompacted -> Unit
      else -> Unit
    }
  }

  /** Ends the delegated agent's current text segment so the next one starts its own card. */
  private fun closeDelegationText(stream: DelegationStream) {
    val uuid = stream.textUuid ?: return
    stream.textJob?.cancel()
    chatStore.updateTextBlock(uuid, stream.text.toString())
    chatStore.updateBlockStatus(uuid, "done")
    stream.textUuid = null
    stream.text = StringBuilder()
  }

  private fun closeDelegationReasoning(stream: DelegationStream) {
    val uuid = stream.reasoningUuid ?: return
    stream.reasoningJob?.cancel()
    chatStore.updateTextBlock(uuid, stream.reasoning.toString())
    chatStore.updateBlockStatus(uuid, "done")
    stream.reasoningUuid = null
    stream.reasoning = StringBuilder()
  }

  /**
   * Closes every delegated stream at the end of a turn: a specialist stopped
   * mid-run must not leave a card that spins forever.
   */
  private fun closeDelegations() {
    delegationStreams.values.forEach { stream ->
      closeDelegationText(stream)
      closeDelegationReasoning(stream)
      stream.approvalBlocks.values.forEach { uuid -> chatStore.updateBlockStatus(uuid, "stalled") }
      stream.toolBlocks.values.forEach { uuid ->
        chatStore.updateToolBlock(uuid, "cancelled", "Stopped", "", null)
      }
    }
    delegationStreams = mutableMapOf()
  }

  /** Flush streamed text into the turn row and mark the text block done. */
  private fun closeStreamingText(turn: String) {
    val uuid = streamingTextBlockUuid ?: return
    textFlushJob?.cancel()
    val snapshot = turnText.toString()
    chatStore.updateTextBlock(uuid, snapshot)
    chatStore.updateBlockStatus(uuid, "done")
    chatStore.updateMessageContent(turn, snapshot)
    // Clear the pointer so the model's NEXT text segment starts its own block —
    // this keeps narration -> tools -> narration in chronological order even
    // with parallel batched tool calls.
    streamingTextBlockUuid = null
    turnText = StringBuilder()
  }

  private fun scheduleReasoningFlush(uuid: String, turn: String) {
    reasoningFlushJob?.cancel()
    val snapshot = reasoningText.toString()
    reasoningFlushJob = viewModelScope.launch {
      delay(250)
      chatStore.updateTextBlock(uuid, snapshot)
    }
  }

  /** Marks the current reasoning block done and flushes its text. */
  private fun closeStreamingReasoning() {
    val uuid = reasoningBlockUuid ?: return
    reasoningFlushJob?.cancel()
    chatStore.updateTextBlock(uuid, reasoningText.toString())
    chatStore.updateBlockStatus(uuid, "done")
    reasoningBlockUuid = null
    reasoningText = StringBuilder()
  }

  private fun scheduleTextFlush(uuid: String, turn: String) {
    textFlushJob?.cancel()
    val snapshot = turnText.toString()
    textFlushJob = viewModelScope.launch {
      delay(250)
      chatStore.updateTextBlock(uuid, snapshot)
      chatStore.updateMessageContent(turn, snapshot)
    }
  }

  private fun finalizeTurn(turn: String, session: String?, status: String, message: String) {
    closeStreamingText(turn)
    streamingTextBlockUuid = null
    closeStreamingReasoning()
    closeDelegations()
    chatStore.updateMessageStatus(turn, status, "")
    // One red error card carries the failure — not scattered across the turn.
    if (message.isNotBlank() && status != "completed") {
      chatStore.insertBlock(
        AgentBlockEntity(
          uuid = AgentChatStore.newId(), messageUuid = turn, kind = "error", name = "error",
          argsJson = "", status = "failed", summary = message,
          detail = "", exitCode = null, createdAt = System.currentTimeMillis()
        )
      )
    }
    session?.let { chatStore.setSessionStatus(it, status) }
    currentTurnUuid = null
    runningToolBlocks = mutableMapOf()
    approvalBlocks = mutableMapOf()
  }

  // ---- session management ----

  fun selectChatSession(id: String) {
    if (isAgentWorking.value) return
    _activeSessionId.value = id
  }

  fun createChatSession(title: String = "New session") {
    if (isAgentWorking.value) return
    viewModelScope.launch {
      val project = activeProject.value
      val now = System.currentTimeMillis()
      val session = AgentSessionEntity(
        id = AgentChatStore.newId(), projectId = project.path,
        title = title, status = "active", createdAt = now, updatedAt = now
      )
      chatStore.createSessionBlocking(session)
      _activeSessionId.value = session.id
      
      // If this is a "New session" without a specific title, set up auto-titling
      // when the first user message arrives
      if (title == "New session") {
        _pendingAutoTitleSessionId.value = session.id
      }
    }
  }

  fun previewSessionsFor(projectPath: String?) {
    _sessionsPreviewProjectId.value = projectPath
  }

  /** Opens a project's session in the agent: selects project + session. */
  fun continueSession(project: Project, sessionId: String) {
    repository.selectProject(project)
    _activeSessionId.value = sessionId
    repository.navigateTo(com.awaki.core.model.AppDestination.AGENT)
  }

  /** Re-reads the open session's latest compaction so the chat can surface it. */
  fun refreshLatestCompaction(sessionId: String) {
    viewModelScope.launch { repository.loadLatestCompaction(sessionId) }
  }

  fun archiveChatSession(id: String) {
    chatStore.setSessionStatus(id, "archived")
  }

  fun renameChatSession(id: String, title: String) {
    if (title.isNotBlank()) chatStore.renameSession(id, title.trim())
  }

  fun deleteChatSession(id: String) {
    if (isAgentWorking.value && id == _activeSessionId.value) return
    chatStore.deleteSession(id)
    if (_activeSessionId.value == id) {
      viewModelScope.launch {
        _activeSessionId.value = chatStore.latestSession(activeProject.value.path)?.id
      }
    }
  }

  /**
   * Pauses the running generation: stream is aborted and state persisted, but
   * the turn stays resumable — Resume continues it from the exact state.
   */
  fun pauseAgent() {
    _isAgentPaused.value = true
    repository.cancelAgentGeneration()
    if (repository.pendingApproval.value != null) {
      // Never a denial: the user stopped the turn, they didn't refuse the action.
      repository.resolveApproval(allowed = false, termination = true)
    }
    repository.interruptTerminal()
    agentJob?.cancel()
  }

  /** Interrupts the running agent task (streamed request cancellation + tool loop stop). */
  fun cancelAgent() {
    _isAgentPaused.value = false
    _isAgentCancelled.value = true
    // Abort the blocked network read and any running tool process immediately,
    // then cancel the coroutine driving the loop.
    repository.cancelAgentGeneration()
    if (repository.pendingApproval.value != null) {
      // Never a denial: the user stopped the turn, they didn't refuse the action.
      repository.resolveApproval(allowed = false, termination = true)
    }
    repository.interruptTerminal()
    agentJob?.cancel()
  }

  /**
   * Retries a failed turn from its persisted state — the same conversation,
   * tool calls and tool results, not the user's message from scratch.
   */
  fun retryAgentTurn(turnUuid: String) {
    if (repository.isAgentWorking.value) return
    val session = _activeSessionId.value ?: return
    _isAgentCancelled.value = false
    agentJob = viewModelScope.launch {
      chatStore.clearErrorBlocks(turnUuid)
      chatStore.updateMessageStatus(turnUuid, "running", "Retrying…")
      chatStore.setSessionStatus(session, "running")
      currentTurnUuid = turnUuid
      turnText = StringBuilder()
      streamingTextBlockUuid = null
      reasoningBlockUuid = null
      reasoningText = StringBuilder()
      runningToolBlocks = mutableMapOf()
      approvalBlocks = mutableMapOf()
      repository.resumeAgentTask(turnUuid, session)
    }
  }

  /**
   * Edits a user message and regenerates the agent response from that point.
   * This deletes all subsequent messages/blocks in the session and re-runs the agent.
   * If the agent is currently working, it is interrupted first.
   */
  fun editUserMessage(userMessageUuid: String, newContent: String) {
    val session = _activeSessionId.value ?: return
    val wasWorking = repository.isAgentWorking.value

    // Interrupt if currently working
    if (wasWorking) {
      cancelAgent()
    }

    agentJob = viewModelScope.launch {
      val now = System.currentTimeMillis()

      // Update the user message content
      chatStore.updateMessageContent(userMessageUuid, newContent)
      chatStore.updateMessageStatus(userMessageUuid, "sent", "")

      // Delete all subsequent messages and their blocks
      chatStore.deleteMessagesAfter(session, userMessageUuid)

      // Mark the session as running again
      chatStore.setSessionStatus(session, "running")

      // Persist the assistant turn row up front, exactly as [launchTurn] does:
      // the streaming blocks that follow attach to it by uuid, so without it
      // the whole response would be written as orphans the UI never renders.
      val turnUuid = AgentChatStore.newId()
      val (providerName, modelName) = turnAttribution()
      chatStore.insertMessage(
        AgentMessageEntity(
          uuid = turnUuid, sessionId = session, role = "assistant_turn", content = "",
          status = "running", statusMessage = "Starting…", createdAt = System.currentTimeMillis(),
          providerName = providerName, modelName = modelName
        )
      )
      pointAtTurn(turnUuid)

      // Update the session title based on the new prompt
      val newTitle = newContent.lineSequence().firstOrNull()?.take(48) ?: "Edited"
      chatStore.renameSession(session, newTitle)

      repository.runAgentTask(newContent, session)
    }
  }
  val permissions: StateFlow<AgentPermissions> = repository.permissions
  val searchQuery: StateFlow<String> = repository.searchQuery
  val isCommandPaletteOpen: StateFlow<Boolean> = repository.isCommandPaletteOpen
  val isModelSheetOpen: StateFlow<Boolean> = repository.isModelSheetOpen

  // Run & Build Center (real install/build/test/run pipeline + live preview)
  val buildRunConfig: StateFlow<BuildRunConfig?> = repository.buildRunConfig
  val buildRunStageStates: StateFlow<Map<BuildStageKind, BuildStageState>> = repository.buildRunStageStates
  val buildRunLogs: StateFlow<List<BuildLogLine>> = repository.buildRunLogs
  val buildRunEndpoints: StateFlow<List<PreviewEndpoint>> = repository.buildRunEndpoints
  val buildRunPreviewRequest: StateFlow<Int> = repository.buildRunPreviewRequest
  val buildRunPipelineRunning: StateFlow<Boolean> = repository.buildRunPipelineRunning
  val buildRunDetectState: StateFlow<BuildRunDetectState> = repository.buildRunDetectState

  fun navigateTo(dest: AppDestination) {
    repository.navigateTo(dest)
  }

  fun selectProject(project: Project) {
    repository.selectProject(project)
  }

  fun createProject(name: String, desc: String, rootPath: String? = null): Project? =
    repository.createProject(name, desc, rootPath)

  fun importProject(rootPath: String, displayName: String? = null, onResult: (Project?) -> Unit) {
    viewModelScope.launch {
      onResult(repository.importProject(rootPath, displayName))
    }
  }

  fun loadChildren(relativePath: String) = repository.loadChildren(relativePath)

  fun searchFileNames(query: String) = repository.searchFileNames(query)

  // ---- Scan exclusions (Settings → Codebase scanning) ----
  val scanIgnoreSettings: StateFlow<com.awaki.data.local.ScanIgnoreSettings> =
    repository.scanIgnoreSettings
  val effectiveIgnoredDirs: StateFlow<List<String>> = repository.scanIgnoreSettings
    .map { com.awaki.data.local.ScanIgnoreStore.effectiveDirs(it).sorted() }
    .stateIn(
      viewModelScope,
      SharingStarted.Eagerly,
      com.awaki.data.local.ScanIgnoreStore.effectiveDirs(repository.scanIgnoreSettings.value).sorted()
    )

  fun addIgnoredDir(raw: String) = repository.addIgnoredDir(raw)
  fun removeIgnoredDir(name: String) = repository.removeIgnoredDir(name)
  fun setIgnoredDirsOverride(enabled: Boolean) = repository.setIgnoredDirsOverride(enabled)
  fun restoreDefaultIgnoredDirs() = repository.restoreDefaultIgnoredDirs()

  // ---- Web access (Settings: how the agent reaches the internet) ----

  /** Whether a web tool may hand its URL to Jina.ai before fetching it itself. */
  val webAccess: StateFlow<com.awaki.data.local.WebAccessSettings> = repository.webAccess

  /** The user's own keys, as handles only — the secret never reaches the UI layer. */
  val jinaKeyHandles: StateFlow<List<String>> = repository.jinaKeyHandles

  /** Whether the app itself was built with keys to rotate through. */
  val bundledJinaKeyCount: Int get() = com.awaki.agent.web.bundledAppKeyCount()

  fun setPreferJina(enabled: Boolean) = repository.setPreferJina(enabled)

  /** Returns how many keys were stored, so the screen can report what landed. */
  fun addJinaKeys(raw: String): Int = repository.addJinaKeys(raw)

  fun removeJinaKey(handle: String): Boolean = repository.removeJinaKey(handle)

  val webAccessReport: StateFlow<List<String>> = repository.webAccessReport
  val webAccessChecking: StateFlow<Boolean> = repository.webAccessChecking

  /** Ask the tiers what they actually answer with right now. */
  fun checkWebAccess() = repository.checkWebAccess()

  // ---- Agent team (Settings: who the agent may hand work to) ----

  /** The user's own specialists; the built-in seats are the app's and not editable. */
  val customAgents: StateFlow<List<com.awaki.agent.model.AgentRole>> = repository.customAgents

  /** Every seat `delegate` can be told about, built-ins first. */
  fun agentRoster(): List<com.awaki.agent.model.AgentRole> = repository.agentRoster()

  /** The tool names a delegated run may hold, for the editor's checklist. */
  fun delegableToolNames(readOnly: Boolean): List<String> = repository.delegableToolNames(readOnly)

  /** Saved as the app will run it: the store normalizes the id and drops what cannot run. */
  fun saveCustomAgent(agent: com.awaki.agent.model.AgentRole): List<com.awaki.agent.model.AgentRole> =
    repository.saveCustomAgent(agent)

  fun deleteCustomAgent(id: String) = repository.removeCustomAgent(id)

  // ---- Skills (Settings: the know-how an agent can pull in) ----

  /** The active project's skills, re-read on demand: a skill is a file, not a row. */
  fun skills(): List<com.awaki.agent.skill.AgentSkill> = repository.skillsFor(activeProject.value)

  fun saveSkill(skill: com.awaki.agent.skill.AgentSkill): List<com.awaki.agent.skill.AgentSkill> =
    activeProject.value?.let { repository.skillStore.save(java.io.File(it.path), skill) } ?: emptyList()

  fun deleteSkill(name: String, scope: com.awaki.agent.skill.SkillScope): List<com.awaki.agent.skill.AgentSkill> =
    activeProject.value?.let { repository.skillStore.delete(java.io.File(it.path), name, scope) } ?: emptyList()

  // ---- Chat display (Settings → Tool activity) ----
  val chatDisplay: StateFlow<com.awaki.data.local.ChatDisplaySettings> =
    repository.chatDisplay

  fun setChatToolJsonVisible(visible: Boolean) = repository.setChatToolJsonVisible(visible)

  // ---- Projects layout (grid vs. list) ----
  val projectsView: StateFlow<com.awaki.data.local.ProjectsViewSettings> =
    repository.projectsView

  fun setProjectsGridView(enabled: Boolean) = repository.setProjectsGridView(enabled)

  /**
   * Deletes a project entirely after the user confirms: folder, chat sessions
   * and registry entry all go together (see [WorkspaceRepository.removeProject]).
   */
  fun removeProject(project: Project) {
    val wasActive = activeProject.value.id == project.id
    repository.removeProject(project)
    if (wasActive) {
      // The sessions that belonged to the deleted project are gone; point the
      // chat at whatever the now-active project has left.
      viewModelScope.launch {
        _activeSessionId.value = chatStore.latestSession(activeProject.value.path)?.id
      }
    }
  }

  fun refreshProjects() {
    repository.refreshProjects()
  }

  /** Mirrors the workspace to the project's original folder. */
  fun syncProjectToSource(project: Project) {
    repository.syncProjectToSource(project)
  }

  fun setProjectAutoSync(projectId: String, enabled: Boolean) {
    repository.setProjectAutoSync(projectId, enabled)
  }

  val isGitRepository: StateFlow<Boolean?> = repository.isGitRepository

  fun initGitRepository() {
    repository.initGitRepository()
  }

  fun openFile(file: ProjectFile) {
    repository.openFile(file)
  }

  fun openTab(tab: EditorTab) {
    val current = _openTabs.value
    val existingIdx = current.indexOfFirst { it.file.path == tab.file.path }
    if (existingIdx >= 0) {
      _activeTabIndex.value = existingIdx
    } else {
      _openTabs.value = current + tab
      _activeTabIndex.value = _openTabs.value.lastIndex
    }
    repository.openFile(tab.file)
    repository.updateEditorContent(tab.content)
  }

  fun selectTab(index: Int) {
    val tabs = _openTabs.value
    if (index in tabs.indices) {
      _activeTabIndex.value = index
      val tab = tabs[index]
      repository.openFile(tab.file)
      repository.updateEditorContent(tab.content)
    }
  }

  fun closeTab(index: Int) {
    val tabs = _openTabs.value.toMutableList()
    if (index in tabs.indices) {
      val removed = tabs.removeAt(index)
      _recentlyClosedTabs.update { (listOf(removed) + it).take(10) }
      _openTabs.value = tabs
      if (tabs.isNotEmpty()) {
        val newIndex = index.coerceAtMost(tabs.lastIndex)
        _activeTabIndex.value = newIndex
        val active = tabs[newIndex]
        repository.openFile(active.file)
        repository.updateEditorContent(active.content)
      } else {
        _activeTabIndex.value = 0
        repository.openFile(com.awaki.data.model.ProjectFile("", "", false))
      }
    }
  }

  fun closeOtherTabs(keepIndex: Int) {
    val tabs = _openTabs.value
    if (keepIndex in tabs.indices) {
      val kept = tabs[keepIndex]
      val closed = tabs.filterIndexed { i, _ -> i != keepIndex }
      _recentlyClosedTabs.update { (closed + it).take(10) }
      _openTabs.value = listOf(kept)
      _activeTabIndex.value = 0
    }
  }

  fun closeAllTabs() {
    val tabs = _openTabs.value
    if (tabs.isNotEmpty()) {
      _recentlyClosedTabs.update { (tabs + it).take(10) }
      _openTabs.value = emptyList()
      _activeTabIndex.value = 0
      repository.openFile(com.awaki.data.model.ProjectFile("", "", false))
    }
  }

  fun reopenLastClosedTab() {
    val closed = _recentlyClosedTabs.value
    if (closed.isNotEmpty()) {
      val tabToReopen = closed.first()
      _recentlyClosedTabs.value = closed.drop(1)
      val tabs = _openTabs.value
      val existingIdx = tabs.indexOfFirst { it.file.path == tabToReopen.file.path }
      if (existingIdx >= 0) {
        _activeTabIndex.value = existingIdx
      } else {
        _openTabs.value = tabs + tabToReopen
        _activeTabIndex.value = _openTabs.value.lastIndex
        repository.openFile(tabToReopen.file)
        repository.updateEditorContent(tabToReopen.content)
      }
    }
  }

  fun updateTabContent(index: Int, newContent: String) {
    val tabs = _openTabs.value.toMutableList()
    if (index in tabs.indices) {
      tabs[index] = tabs[index].withContent(newContent)
      _openTabs.value = tabs
      if (index == _activeTabIndex.value) {
        repository.updateEditorContent(newContent)
      }
    }
  }

  fun undoTab(index: Int = _activeTabIndex.value): String? {
    val tabs = _openTabs.value.toMutableList()
    if (index in tabs.indices) {
      val undoneTab = tabs[index].undo() ?: return null
      tabs[index] = undoneTab
      _openTabs.value = tabs
      if (index == _activeTabIndex.value) {
        repository.updateEditorContent(undoneTab.content)
      }
      return undoneTab.content
    }
    return null
  }

  fun redoTab(index: Int = _activeTabIndex.value): String? {
    val tabs = _openTabs.value.toMutableList()
    if (index in tabs.indices) {
      val redoneTab = tabs[index].redo() ?: return null
      tabs[index] = redoneTab
      _openTabs.value = tabs
      if (index == _activeTabIndex.value) {
        repository.updateEditorContent(redoneTab.content)
      }
      return redoneTab.content
    }
    return null
  }

  fun setTab(index: Int, tab: EditorTab) {
    val tabs = _openTabs.value.toMutableList()
    if (index in tabs.indices) {
      tabs[index] = tab
      _openTabs.value = tabs
      if (index == _activeTabIndex.value) {
        repository.updateEditorContent(tab.content)
      }
    }
  }

  fun setTabManualLanguage(index: Int, language: Language) {
    val tabs = _openTabs.value.toMutableList()
    if (index in tabs.indices) {
      tabs[index] = tabs[index].copy(manualLanguage = language)
      _openTabs.value = tabs
    }
  }

  fun updateEditorSettings(settings: EditorSettings) {
    repository.updateEditorSettings(settings)
  }

  fun updateEditorContent(content: String) {
    repository.updateEditorContent(content)
  }

  fun saveActiveFile() {
    repository.saveActiveFile()
  }

  fun createFile(relativePath: String, content: String = ""): Boolean {
    return repository.createFile(relativePath, content)
  }

  fun createDirectory(relativePath: String): Boolean {
    return repository.createDirectory(relativePath)
  }

  fun deleteFile(relativePath: String): Boolean {
    return repository.deleteFile(relativePath)
  }

  fun renameFile(oldPath: String, newName: String): Boolean {
    return repository.renameFile(oldPath, newName)
  }

  fun duplicateFile(relativePath: String): Boolean {
    return repository.duplicateFile(relativePath)
  }

  fun refreshFiles(showLoading: Boolean = false) {
    repository.refreshFiles(showLoading)
  }

  fun toggleCommandPalette(open: Boolean? = null) {
    repository.toggleCommandPalette(open)
  }

  fun toggleModelSheet(open: Boolean? = null) {
    repository.toggleModelSheet(open)
  }

  fun selectModel(model: AIModel) {
    repository.selectModel(model.id)
  }

  // ---- Provider & model management (Task: AI provider system) ----

  fun saveProvider(name: String, baseUrl: String, protocol: com.awaki.settings.model.LLMProtocol, apiKey: String?, providerId: String? = null) {
    repository.saveProvider(name, baseUrl, protocol, apiKey, providerId)
  }

  fun deleteProvider(providerId: String) {
    repository.deleteProvider(providerId)
  }

  fun saveModel(
    providerId: String,
    modelId: String,
    displayName: String,
    contextWindow: Int?,
    maxOutputTokens: Int?,
    capabilities: com.awaki.settings.model.ModelCapabilities,
    reasoning: com.awaki.settings.model.ReasoningConfig?,
    recordId: String? = null
  ): AIModel? = repository.saveModel(
    providerId, modelId, displayName, contextWindow, maxOutputTokens,
    capabilities, reasoning, recordId
  )

  fun deleteModel(recordId: String) {
    repository.deleteModel(recordId)
  }

  fun testProviderConnection(providerId: String) {
    repository.testProviderConnection(providerId)
  }

  /** Fetches a provider's model listing when the model form needs it. */
  fun loadModelCatalog(providerId: String, force: Boolean = false) {
    repository.loadModelCatalog(providerId, force)
  }

  /**
   * Returns the stored key for a provider, or null when none is set. Fetched on
   * demand for the reveal-toggle in the provider UI — the secret is deliberately
   * kept out of [com.awaki.settings.model.AIProvider] UI state.
   */
  fun getApiKey(providerId: String): String? = repository.getApiKey(providerId)

  // ---- On-device models ----

  /** Built-in and user-added models, with installation recomputed from the files. */
  val localModels: StateFlow<List<com.awaki.local.model.LocalModel>> =
    repository.localModelStore?.models ?: MutableStateFlow(emptyList())

  /** Live download/install state per model id; empty when nothing is running. */
  val localInstallStates: StateFlow<Map<String, com.awaki.data.repository.LocalModelInstallState>> =
    repository.localModelStore?.installStates ?: MutableStateFlow(emptyMap())

  /** Why the on-device models cannot answer, or null when the agent can use them. */
  val localAiNote: String? get() = repository.localAiNote

  private val localStore: com.awaki.data.repository.LocalModelRepository?
    get() = repository.localModelStore

  fun installLocalModel(modelId: String) {
    val store = localStore ?: return
    val model = store.model(modelId) ?: return
    viewModelScope.launch { store.install(model) }
  }

  fun cancelLocalInstall(modelId: String) {
    localStore?.cancel(modelId)
  }

  fun uninstallLocalModel(modelId: String) {
    val store = localStore ?: return
    val model = store.model(modelId) ?: return
    viewModelScope.launch { store.uninstall(model) }
  }

  /** Removes a user-added model entirely; a built-in one can only be uninstalled. */
  fun forgetLocalModel(modelId: String) {
    viewModelScope.launch { localStore?.remove(modelId) }
  }

  /**
   * Replaces the bytes a model is running from. An install that is already current
   * answers without touching the network, so the file has to go first for the fetch
   * to actually happen.
   */
  fun redownloadLocalModel(modelId: String) {
    val store = localStore ?: return
    val model = store.model(modelId) ?: return
    viewModelScope.launch {
      store.uninstall(model)
      store.install(model)
    }
  }

  private val _localModelLoading = MutableStateFlow<Set<String>>(emptySet())

  /** The models being brought into memory right now, so a row can say so rather than wait. */
  val localModelLoading: StateFlow<Set<String>> = _localModelLoading.asStateFlow()

  private val _localModelLoadErrors = MutableStateFlow<Map<String, String>>(emptyMap())

  /** Why a load last failed, per model; cleared when that model is asked for again. */
  val localModelLoadErrors: StateFlow<Map<String, String>> = _localModelLoadErrors.asStateFlow()

  /** The single model in memory, so the list can mark the one it is holding. */
  val localResidentModelId: StateFlow<String?> = repository.localResidentModelId

  /**
   * Puts [modelId] into memory now. Opening a model takes seconds on a phone, and a turn that
   * starts with a load looks exactly like a turn that has stalled, so the list lets the user
   * pay for it while nothing is being asked of the model.
   */
  fun loadLocalModel(modelId: String) {
    if (modelId in _localModelLoading.value) return
    _localModelLoading.value = _localModelLoading.value + modelId
    _localModelLoadErrors.value = _localModelLoadErrors.value - modelId
    viewModelScope.launch {
      try {
        repository.loadLocalModel(modelId)
      } catch (e: Exception) {
        _localModelLoadErrors.value = _localModelLoadErrors.value +
          (modelId to (e.message ?: "The model could not be loaded"))
      } finally {
        _localModelLoading.value = _localModelLoading.value - modelId
      }
    }
  }

  private val _localModelUnloading = MutableStateFlow<Set<String>>(emptySet())

  /**
   * The rows waiting for memory to come back. Unloading waits on the same lock a turn holds, so
   * pressing it mid-answer is a request that lands when the answer does, and the row has to say
   * it heard the press while it waits.
   */
  val localModelUnloading: StateFlow<Set<String>> = _localModelUnloading.asStateFlow()

  /**
   * Takes [modelId] out of memory again. Only what is actually resident can be handed back, and
   * the engine holds one model, so the row that asks is the one holding it.
   */
  fun unloadLocalModel(modelId: String) {
    if (modelId in _localModelUnloading.value) return
    if (repository.localResidentModelId.value != modelId) return
    _localModelUnloading.value = _localModelUnloading.value + modelId
    viewModelScope.launch {
      try {
        repository.unloadLocalModel()
      } finally {
        _localModelUnloading.value = _localModelUnloading.value - modelId
      }
    }
  }

  /**
   * Adds a model from a URL the user typed. The result comes back to the form, because
   * a rejected URL is the form's problem to explain, not the list's.
   */
  fun addLocalModel(
    name: String,
    downloadUrl: String,
    description: String,
    quantization: String,
    onResult: (Result<com.awaki.local.model.LocalModel>) -> Unit
  ) {
    val store = localStore ?: return
    viewModelScope.launch { onResult(store.addCustom(name, downloadUrl, description, quantization)) }
  }

  /**
   * Copies a model the user picked from the device's storage. The result comes back to
   * the screen because a file that turns out not to be a model is the picker's problem
   * to explain — the list never sees a row for it.
   */
  fun importLocalModel(
    uri: android.net.Uri,
    onResult: (Result<com.awaki.local.model.LocalModel>) -> Unit
  ) {
    val store = localStore ?: return
    viewModelScope.launch { onResult(store.import(uri)) }
  }

  /** Proves the bytes on disk still match what the install recorded. */
  fun verifyLocalModel(modelId: String) {
    viewModelScope.launch { localStore?.verifyInstalled(modelId) }
  }

  fun refreshLocalModels() {
    viewModelScope.launch { localStore?.refreshRemoteInfo() }
  }

  fun updateLocalConfiguration(
    modelId: String,
    configuration: com.awaki.local.model.LocalModelConfiguration
  ) {
    localStore?.updateConfiguration(modelId, configuration)
  }

  fun resetLocalConfiguration(modelId: String) {
    localStore?.resetConfiguration(modelId)
  }

  /**
   * The tools an on-device model can be offered, each with the share of the prompt it
   * carries — the number a user has to trade against the model's context.
   */
  fun localToolChoices(): List<com.awaki.agent.tool.ToolOffering> = repository.offerableTools()

  /** What the model file itself says — architecture, quantization, tensor count. */
  fun localModelMetadata(modelId: String): com.awaki.local.model.GgufMetadata? {
    val store = localStore ?: return null
    return store.model(modelId)?.let(store::metadataOf)
  }

  fun localModelSelectable(modelId: String): AIModel? =
    repository.aiModels.value.firstOrNull { it.id == com.awaki.local.LocalAiRuntime.recordId(modelId) }

  fun updateSearchQuery(query: String) {
    repository.updateSearchQuery(query)
  }

  fun refreshDiffsAndGit(forceHistoryReload: Boolean = false) {
    repository.refreshDiffsAndGit(forceHistoryReload)
  }

  fun toggleFileStaged(filePath: String) {
    repository.toggleFileStaged(filePath)
  }

  /** Explicit staging intent from the Git tab (VS Code-style +/− rows). */
  fun setFileStaged(filePath: String, stage: Boolean) {
    repository.setFileStaged(filePath, stage)
  }

  fun stageFile(filePath: String) {
    repository.setFileStaged(filePath, true)
  }

  fun unstageFile(filePath: String) {
    repository.setFileStaged(filePath, false)
  }

  fun stageAll() {
    repository.stageAll()
  }

  fun unstageAll() {
    repository.unstageAll()
  }

  fun updateCommitMessage(msg: String) {
    repository.updateCommitMessage(msg)
  }

  fun generateCommitMessageWithAgent() {
    repository.generateCommitMessageWithAgent()
  }

  fun dismissCommitGenState() {
    repository.dismissCommitGenState()
  }

  fun commitStagedChanges(customMessage: String? = null, amend: Boolean = false) {
    repository.commitStagedChanges(customMessage, amend)
  }

  fun commitAndPush(customMessage: String? = null) {
    repository.commitAndPush(customMessage)
  }

  fun undoLastCommit(mode: UndoCommitMode = UndoCommitMode.KEEP_STAGED) {
    repository.undoLastCommit(mode)
  }

  fun loadMoreCommitHistory() {
    repository.loadMoreCommitHistory()
  }

  suspend fun getCommitDetail(hash: String): GitCommitDetail? {
    return repository.getCommitDetail(hash)
  }

  suspend fun getCommitDiff(hash: String): String {
    return repository.getCommitDiff(hash)
  }

  fun revertCommit(hash: String) {
    repository.revertCommit(hash)
  }

  fun cherryPickCommit(hash: String) {
    repository.cherryPickCommit(hash)
  }

  fun resetToCommit(hash: String, mode: ResetMode) {
    repository.resetToCommit(hash, mode)
  }

  fun checkoutBranch(name: String) {
    repository.checkoutBranch(name)
  }

  fun createBranch(name: String, checkout: Boolean = true) {
    repository.createBranch(name, checkout)
  }

  fun deleteBranch(name: String, force: Boolean = false) {
    repository.deleteBranch(name, force)
  }

  fun renameBranch(oldName: String, newName: String) {
    repository.renameBranch(oldName, newName)
  }

  fun mergeBranch(name: String) {
    repository.mergeBranch(name)
  }

  fun abortMerge() {
    repository.abortMerge()
  }

  fun continueMerge() {
    repository.continueMerge()
  }

  fun rebaseBranch(name: String) {
    repository.rebaseBranch(name)
  }

  fun abortRebase() {
    repository.abortRebase()
  }

  fun continueRebase() {
    repository.continueRebase()
  }

  fun abortCherryPick() {
    repository.abortCherryPick()
  }

  fun continueCherryPick() {
    repository.continueCherryPick()
  }

  fun fetch(remote: String = "origin", prune: Boolean = false) {
    repository.fetch(remote, prune)
  }

  fun pull(remote: String = "origin", branch: String? = null, rebase: Boolean = false) {
    repository.pull(remote, branch, rebase)
  }

  fun push(remote: String = "origin", branch: String? = null, setUpstream: Boolean = false, force: Boolean = false) {
    repository.push(remote, branch, setUpstream, force)
  }

  fun sync() {
    repository.sync()
  }

  fun addRemote(name: String, url: String) {
    repository.addRemote(name, url)
  }

  fun removeRemote(name: String) {
    repository.removeRemote(name)
  }

  fun setRemoteUrl(name: String, url: String) {
    repository.setRemoteUrl(name, url)
  }

  fun createTag(name: String, message: String = "", commitHash: String? = null) {
    repository.createTag(name, message, commitHash)
  }

  fun deleteTag(name: String) {
    repository.deleteTag(name)
  }

  fun saveStash(message: String = "", includeUntracked: Boolean = false) {
    repository.saveStash(message, includeUntracked)
  }

  fun applyStash(index: Int) {
    repository.applyStash(index)
  }

  fun popStash(index: Int) {
    repository.popStash(index)
  }

  fun dropStash(index: Int) {
    repository.dropStash(index)
  }

  fun deleteUntrackedFile(filePath: String) {
    repository.deleteUntrackedFile(filePath)
  }

  fun clearGitError() {
    repository.clearGitError()
  }

  fun dismissGitError() {
    repository.dismissGitError()
  }

  fun clearGitIndexLock() {
    repository.clearGitIndexLock()
  }

  fun clearGitOperationFeedback() {
    repository.clearGitOperationFeedback()
  }

  suspend fun getFullDiffText(type: DiffCopyType, filePath: String? = null): String {
    return repository.getFullDiffText(type, filePath)
  }

  suspend fun explainChangesWithAgent(diffText: String): String? {
    return repository.explainChangesWithAgent(diffText)
  }

  suspend fun reviewChangesWithAgent(diffText: String): String? {
    return repository.reviewChangesWithAgent(diffText)
  }

  suspend fun explainCommitWithAgent(commit: GitCommit): String? {
    return repository.explainCommitWithAgent(commit)
  }

  fun acceptAllDiffs() {
    repository.acceptAllDiffs()
  }

  fun rejectAllDiffs() {
    repository.rejectAllDiffs()
  }

  fun rejectDiff(filePath: String) {
    repository.rejectDiff(filePath)
  }

  fun selectTerminalSession(id: String) {
    repository.selectTerminalSession(id)
  }

  fun createTerminalSession(name: String = "bash") {
    repository.createTerminalSession(name)
  }

  fun closeTerminalSession(id: String) {
    repository.closeTerminalSession(id)
  }

  fun executeTerminalCommand(cmd: String) {
    // Palette / quick actions write a real command line into the live PTY.
    repository.sendTerminalLine(cmd)
  }

  fun startLinuxBootstrap() {
    repository.startLinuxBootstrap()
  }

  fun ensurePtySession(tabId: String, name: String) {
    repository.ensurePtySession(tabId, name)
  }

  fun interruptTerminal(sessionId: String = activeTerminalSessionId.value) {
    repository.interruptTerminal(sessionId)
  }

  fun resolveApproval(allowed: Boolean, answer: String? = null, termination: Boolean = false) {
    repository.resolveApproval(allowed, answer, null, termination)
  }

  /**
   * Refuses with the reason the user typed, so the model gets "no, because…"
   * instead of a bare denial it can only guess at.
   */
  fun denyWithReason(rationale: String) {
    repository.resolveApproval(allowed = false, rationale = rationale)
  }

  /** Answers an agent question with a chosen option or typed text. */
  fun answerQuestion(answer: String?) {
    repository.resolveApproval(allowed = true, answer = answer)
  }

  /**
   * Closes the approval dialog without deciding anything.
   *
   * The request stays pending: the tool remains suspended and its chat card keeps
   * the controls, so the user can decide later instead of being forced into a
   * denial by a stray back-button press.
   */
  fun deferApproval() {
    repository.deferApproval()
  }

  /** Re-opens the dialog for a request the user parked in the chat. */
  fun showApprovalDialog() {
    repository.showApprovalDialog()
  }

  /** SIGKILLs a specific running tool call (process kill, task keeps going). */
  fun cancelToolCall(callId: String) {
    repository.cancelToolCall(callId)
  }

  /** Retry re-runs the cancelled call; continue tells the model it was cancelled. */
  fun resolveToolCancellation(callId: String, retry: Boolean) {
    repository.resolveToolCancellation(callId, retry)
  }

  /** Holds one specialist at its next step, or lets a held one go on. */
  fun setSubagentPaused(delegationId: String, paused: Boolean) {
    repository.setSubagentPaused(delegationId, paused)
  }

  val defaultTaskModelId: StateFlow<String?> = repository.defaultTaskModelId

  /** Marks a model as the default for background tasks (commit msgs, titles, ...). */
  fun setDefaultTaskModel(modelId: String?) {
    repository.setDefaultTaskModel(modelId)
  }

  /** Imports a .zip archive (SAF uri) as a project in the app workspace. */
  fun importZipProject(uri: android.net.Uri, displayName: String?, onResult: (Project?) -> Unit) {
    viewModelScope.launch {
      onResult(repository.importZipProject(uri, displayName))
    }
  }

  fun updatePermissions(transform: (AgentPermissions) -> AgentPermissions) {
    repository.updatePermissions(transform)
  }

  fun runAgentTask(prompt: String) {
    if (repository.isAgentWorking.value) return
    _isAgentCancelled.value = false
    agentJob = viewModelScope.launch {
      val project = activeProject.value
      // Ensure a session: reuse the active one or create one titled from the prompt.
      var sessionId = _activeSessionId.value
      var isNewSession = false
      val now = System.currentTimeMillis()
      if (sessionId == null) {
        val session = AgentSessionEntity(
          id = AgentChatStore.newId(), projectId = project.path,
          title = generateInitialTitle(prompt),
          status = "running", createdAt = now, updatedAt = now
        )
        chatStore.createSessionBlocking(session)
        sessionId = session.id
        _activeSessionId.value = sessionId
        isNewSession = true
      } else {
        chatStore.setSessionStatus(sessionId, "running")
      }
      if (isNewSession) autoTitleSession(sessionId, prompt)

      launchTurn(prompt, sessionId)
    }
  }

  /**
   * Starts a file-context request ("Ask Agent on <file>") in its own separate
   * session, so per-file conversations never append to the active chat.
   */
  fun runAgentTaskInNewSession(prompt: String) {
    if (repository.isAgentWorking.value) return
    _isAgentCancelled.value = false
    agentJob = viewModelScope.launch {
      val project = activeProject.value
      val now = System.currentTimeMillis()
      val session = AgentSessionEntity(
        id = AgentChatStore.newId(), projectId = project.path,
        title = generateInitialTitle(prompt),
        status = "running", createdAt = now, updatedAt = now
      )
      chatStore.createSessionBlocking(session)
      _activeSessionId.value = session.id
      autoTitleSession(session.id, prompt)
      launchTurn(prompt, session.id)
    }
  }

  /**
   * Provider/model pair to stamp on a turn row, resolved when the turn starts.
   * Display names are stored (not record ids) so history survives a later
   * rename or deletion of the configured model.
   */
  private fun turnAttribution(): Pair<String?, String?> {
    val model = selectedModel.value ?: return null to null
    val providerName = providers.value.firstOrNull { it.id == model.providerId }?.name
    return providerName to model.displayName
  }

  /** Persists the user prompt + agent turn rows, then drives the runtime. */
  private suspend fun launchTurn(prompt: String, sessionId: String) {
    val userUuid = AgentChatStore.newId()
    val turnUuid = AgentChatStore.newId()
    val (providerName, modelName) = turnAttribution()
    
    // Insert the user message
    val userMessage = AgentMessageEntity(
      uuid = userUuid, sessionId = sessionId, role = "user", content = prompt,
      status = "sent", statusMessage = "", createdAt = System.currentTimeMillis()
    )
    chatStore.insertMessage(userMessage)
    
    // Check if this is the first message in a session that needs auto-titling
    if (_pendingAutoTitleSessionId.value == sessionId) {
      _pendingAutoTitleSessionId.value = null
      autoTitleSession(sessionId, prompt)
    }
    
    // Insert the assistant turn
    chatStore.insertMessage(
      AgentMessageEntity(
        uuid = turnUuid, sessionId = sessionId, role = "assistant_turn", content = "",
        status = "running", statusMessage = "Starting…", createdAt = System.currentTimeMillis(),
        providerName = providerName, modelName = modelName
      )
    )
    currentTurnUuid = turnUuid
    turnText = StringBuilder()
    streamingTextBlockUuid = null
    reasoningBlockUuid = null
    reasoningText = StringBuilder()
    runningToolBlocks = mutableMapOf()
    approvalBlocks = mutableMapOf()
    repository.runAgentTask(prompt, sessionId)
  }

  /** Generate a clean title from the first sentence of a prompt */
  private fun generateInitialTitle(prompt: String): String {
    val firstSentence = prompt.split(Regex("[.!?]\\s*")).firstOrNull() ?: prompt
    return firstSentence.take(48).trim().ifBlank { "New session" }
  }

  /** AI-generated session name (max 6 words); falls back to the prompt slice. */
  private fun autoTitleSession(sessionId: String, prompt: String) {
    viewModelScope.launch {
      repository.requestSessionTitle(prompt)?.let { title ->
        chatStore.renameSession(sessionId, title)
      } ?: run {
        // AI title generation failed, use improved fallback from prompt
        val fallbackTitle = generateInitialTitle(prompt)
        chatStore.renameSession(sessionId, fallbackTitle)
      }
    }
  }

  fun runBuildStage(kind: BuildStageKind) = repository.runBuildStage(kind)

  fun stopBuildStage(kind: BuildStageKind) = repository.stopBuildStage(kind)

  fun stopAllBuildStages() = repository.stopAllBuildStages()

  fun runBuildPipeline() = repository.runBuildPipeline()

  fun clearBuildRunLogs() = repository.clearBuildRunLogs()

  fun saveBuildStageCommand(kind: BuildStageKind, command: String, port: Int?) =
    repository.saveBuildRunStageCommand(kind, command, port)

  fun resetBuildRunCommands() = repository.resetBuildRunCommands()

  fun autoConfigureBuildRun() = repository.autoConfigureBuildRun()

  /**
   * This view model holds the only reference to the repository, and that repository owns
   * a scope of its own: scans, git reads, build runs and web probes that resume on
   * Main. Clearing without ending it leaves that work running against a UI nobody is
   * looking at any more.
   */
  override fun onCleared() {
    repository.dispose()
  }
}

/** Encodes a question's choices for the block's `optionsJson` column. */
private fun List<String>.toJsonArray(): String {
  val array = org.json.JSONArray()
  forEach { array.put(it) }
  return array.toString()
}
