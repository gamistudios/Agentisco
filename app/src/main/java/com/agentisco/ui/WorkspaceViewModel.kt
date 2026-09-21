package com.agentisco.ui

import com.agentisco.settings.model.AIProvider
import com.agentisco.settings.model.AIModel
import com.agentisco.agent.model.AgentPermissions
import com.agentisco.agent.model.PendingApproval
import com.agentisco.agent.model.ToolExecution
import com.agentisco.agent.model.AgentTaskStep
import com.agentisco.agent.model.AgentStreamEvent
import com.agentisco.core.model.AppDestination
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentisco.data.local.chat.AgentBlockEntity
import com.agentisco.data.local.chat.AgentMessageEntity
import com.agentisco.data.local.chat.AgentSessionEntity
import com.agentisco.data.model.*
import com.agentisco.data.repository.AgentChatStore
import com.agentisco.data.repository.WorkspaceRepository
import com.agentisco.editor.model.EditorSettings
import com.agentisco.editor.model.EditorTab
import com.agentisco.editor.syntax.Language
import com.agentisco.workspace.git.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
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

  private val _editorSettings = MutableStateFlow(EditorSettings())
  val editorSettings: StateFlow<EditorSettings> = _editorSettings.asStateFlow()

  val isAgentWorking: StateFlow<Boolean> = repository.isAgentWorking
  val agentStatusText: StateFlow<String> = repository.agentStatusText
  val agentSteps: StateFlow<List<AgentTaskStep>> = repository.agentSteps
  val toolExecutions: StateFlow<List<ToolExecution>> = repository.toolExecutions
  val pendingApproval: StateFlow<PendingApproval?> = repository.pendingApproval

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
  val linuxEnvironmentState: StateFlow<com.agentisco.workspace.terminal.LinuxEnvironmentState> =
    repository.debianBootstrap?.state
      ?: MutableStateFlow(
        com.agentisco.workspace.terminal.LinuxEnvironmentState.Failed(
          "Linux terminal unavailable: repository was created without an application context."
        )
      )

  val providers: StateFlow<List<AIProvider>> = repository.providers
  val aiModels: StateFlow<List<AIModel>> = repository.aiModels
  val selectedModel: StateFlow<AIModel?> = repository.selectedModel
  val connectionTests: StateFlow<Map<String, WorkspaceRepository.ConnectionTestState>> = repository.connectionTests
  val agentResponse: StateFlow<String> = repository.agentResponse

  // ---- Persistent agent chat (sessions → messages → turn blocks) ----
  // The UI list is derived entirely from SQLite; runtime events are written
  // through the AgentChatStore, so live streaming, restore, and dedupe share
  // one source of truth.

  private val chatStore: AgentChatStore get() = repository.chatStore

  private val _activeSessionId = MutableStateFlow<String?>(null)
  val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

  val chatSessions: StateFlow<List<AgentSessionEntity>> = activeProject
    .flatMapLatest { project -> chatStore.sessionsForProject(project.path) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val chatItems: StateFlow<List<ChatItem>> = _activeSessionId
    .flatMapLatest { id ->
      if (id == null) flowOf(emptyList())
      else flow<List<com.agentisco.data.local.chat.MessageWithBlocks>> {
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
  }

  init {
    viewModelScope.launch {
      repository.agentEvents.collect { event -> onAgentEvent(event) }
    }
    // Turns/blocks left "running" by a previous process become interrupted.
    viewModelScope.launch { chatStore.recoverInterrupted() }
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
            uuid = uuid, messageUuid = turn, kind = "approval", name = event.approvalId,
            argsJson = event.command, status = "pending", summary = event.title,
            detail = event.impact, exitCode = null, createdAt = System.currentTimeMillis()
          )
        )
      }
      is AgentStreamEvent.ApprovalResolved -> {
        approvalBlocks.remove(event.approvalId)?.let { uuid ->
          chatStore.updateBlockStatus(uuid, if (event.allowed) "allowed" else "denied")
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
    }
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
    }
  }

  fun previewSessionsFor(projectPath: String?) {
    _sessionsPreviewProjectId.value = projectPath
  }

  /** Opens a project's session in the agent: selects project + session. */
  fun continueSession(project: Project, sessionId: String) {
    repository.selectProject(project)
    _activeSessionId.value = sessionId
    repository.navigateTo(com.agentisco.core.model.AppDestination.AGENT)
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
      repository.resolveApproval(false)
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
      repository.resolveApproval(false)
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
  val isDevServerRunning: StateFlow<Boolean> = repository.isDevServerRunning

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
  val scanIgnoreSettings: StateFlow<com.agentisco.data.local.ScanIgnoreSettings> =
    repository.scanIgnoreSettings
  val effectiveIgnoredDirs: StateFlow<List<String>> = repository.scanIgnoreSettings
    .map { com.agentisco.data.local.ScanIgnoreStore.effectiveDirs(it).sorted() }
    .stateIn(
      viewModelScope,
      SharingStarted.Eagerly,
      com.agentisco.data.local.ScanIgnoreStore.effectiveDirs(repository.scanIgnoreSettings.value).sorted()
    )

  fun addIgnoredDir(raw: String) = repository.addIgnoredDir(raw)
  fun removeIgnoredDir(name: String) = repository.removeIgnoredDir(name)
  fun setIgnoredDirsOverride(enabled: Boolean) = repository.setIgnoredDirsOverride(enabled)
  fun restoreDefaultIgnoredDirs() = repository.restoreDefaultIgnoredDirs()

  // ---- Chat display (Settings → Tool activity) ----
  val chatDisplay: StateFlow<com.agentisco.data.local.ChatDisplaySettings> =
    repository.chatDisplay

  fun setChatToolJsonVisible(visible: Boolean) = repository.setChatToolJsonVisible(visible)

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
        repository.openFile(com.agentisco.data.model.ProjectFile("", "", false))
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
      repository.openFile(com.agentisco.data.model.ProjectFile("", "", false))
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
    _editorSettings.value = settings
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

  fun saveProvider(name: String, baseUrl: String, protocol: com.agentisco.settings.model.LLMProtocol, apiKey: String?, providerId: String? = null) {
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
    capabilities: com.agentisco.settings.model.ModelCapabilities,
    reasoning: com.agentisco.settings.model.ReasoningConfig?,
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

  fun updateSearchQuery(query: String) {
    repository.updateSearchQuery(query)
  }

  fun refreshDiffsAndGit() {
    repository.refreshDiffsAndGit()
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

  fun resolveApproval(allowed: Boolean) {
    repository.resolveApproval(allowed)
  }

  /** SIGKILLs a specific running tool call (process kill, task keeps going). */
  fun cancelToolCall(callId: String) {
    repository.cancelToolCall(callId)
  }

  /** Retry re-runs the cancelled call; continue tells the model it was cancelled. */
  fun resolveToolCancellation(callId: String, retry: Boolean) {
    repository.resolveToolCancellation(callId, retry)
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
          title = prompt.lineSequence().firstOrNull()?.take(48) ?: "New session",
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
        title = prompt.lineSequence().firstOrNull()?.take(48) ?: "New session",
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
    chatStore.insertMessage(
      AgentMessageEntity(
        uuid = userUuid, sessionId = sessionId, role = "user", content = prompt,
        status = "sent", statusMessage = "", createdAt = System.currentTimeMillis()
      )
    )
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

  /** AI-generated session name (max 6 words); falls back to the prompt slice. */
  private fun autoTitleSession(sessionId: String, prompt: String) {
    viewModelScope.launch {
      repository.requestSessionTitle(prompt)?.let { title ->
        chatStore.renameSession(sessionId, title)
      }
    }
  }

  fun toggleDevServer() {
    repository.toggleDevServer()
  }
}
