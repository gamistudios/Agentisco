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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
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
  val activeFile: StateFlow<ProjectFile> = repository.activeFile
  val editorContent: StateFlow<String> = repository.editorContent
  val isEditorDirty: StateFlow<Boolean> = repository.isEditorDirty

  val isAgentWorking: StateFlow<Boolean> = repository.isAgentWorking
  val agentStatusText: StateFlow<String> = repository.agentStatusText
  val agentSteps: StateFlow<List<AgentTaskStep>> = repository.agentSteps
  val toolExecutions: StateFlow<List<ToolExecution>> = repository.toolExecutions
  val pendingApproval: StateFlow<PendingApproval?> = repository.pendingApproval

  val fileDiffs: StateFlow<List<FileDiff>> = repository.fileDiffs
  val stagedFiles: StateFlow<Set<String>> = repository.stagedFiles
  val commitMessage: StateFlow<String> = repository.commitMessage
  val commitHistory: StateFlow<List<GitCommit>> = repository.commitHistory

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
      if (id == null) flowOf(emptyList()) else chatStore.messagesWithBlocks(id)
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

  private var agentJob: Job? = null

  // Per-turn stream bookkeeping: which persisted rows the live events map to.
  private var currentTurnUuid: String? = null
  private var turnText = StringBuilder()
  private var streamingTextBlockUuid: String? = null
  private var runningToolBlocks = mutableMapOf<String, String>() // tool name -> block uuid
  private var approvalBlocks = mutableMapOf<String, String>() // approvalId -> block uuid
  private var textFlushJob: Job? = null

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
  }

  private fun onAgentEvent(event: AgentStreamEvent) {
    val turn = currentTurnUuid ?: return
    val session = _activeSessionId.value
    when (event) {
      // The user message + turn rows are already persisted at send time.
      is AgentStreamEvent.TaskStarted -> Unit
      is AgentStreamEvent.Status ->
        chatStore.updateMessageStatus(turn, "running", event.text)
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
        runningToolBlocks[event.name] = uuid
        chatStore.insertBlock(
          AgentBlockEntity(
            uuid = uuid, messageUuid = turn, kind = "tool", name = event.name,
            argsJson = event.argsJson, status = "running", summary = "Running…",
            detail = "", exitCode = null, createdAt = System.currentTimeMillis()
          )
        )
      }
      is AgentStreamEvent.ToolFinished -> {
        val uuid = runningToolBlocks.remove(event.name)
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
      is AgentStreamEvent.Cancelled -> finalizeTurn(turn, session, "cancelled", event.message)
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
    chatStore.updateMessageStatus(turn, status, message)
    // Failure/cancel reasons should be visible even with no streamed text.
    if (message.isNotBlank() && turnText.isBlank()) {
      chatStore.updateMessageContent(turn, message)
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

  /** Interrupts the running agent task (streamed request cancellation + tool loop stop). */
  fun cancelAgent() {
    _isAgentCancelled.value = true
    agentJob?.cancel()
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

  fun importProject(rootPath: String, displayName: String? = null): Project? =
    repository.importProject(rootPath, displayName)

  fun removeProject(project: Project) {
    repository.removeProject(project)
  }

  fun refreshProjects() {
    repository.refreshProjects()
  }

  fun openFile(file: ProjectFile) {
    repository.openFile(file)
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

  fun refreshFiles() {
    repository.refreshFiles()
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
  ): AIModel? = repository.saveModel(providerId, modelId, displayName, contextWindow, maxOutputTokens, capabilities, reasoning, recordId)

  fun deleteModel(recordId: String) {
    repository.deleteModel(recordId)
  }

  fun testProviderConnection(providerId: String) {
    repository.testProviderConnection(providerId)
  }

  fun updateSearchQuery(query: String) {
    repository.updateSearchQuery(query)
  }

  fun toggleFileStaged(filePath: String) {
    repository.toggleFileStaged(filePath)
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

  fun commitStagedChanges() {
    repository.commitStagedChanges()
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
      } else {
        chatStore.setSessionStatus(sessionId, "running")
      }

      // Persist the user prompt and the agent turn immediately — one row each,
      // with stable UUIDs, so restart/reconnect can never duplicate them.
      val userUuid = AgentChatStore.newId()
      val turnUuid = AgentChatStore.newId()
      chatStore.insertMessage(
        AgentMessageEntity(
          uuid = userUuid, sessionId = sessionId, role = "user", content = prompt,
          status = "sent", statusMessage = "", createdAt = now
        )
      )
      chatStore.insertMessage(
        AgentMessageEntity(
          uuid = turnUuid, sessionId = sessionId, role = "assistant_turn", content = "",
          status = "running", statusMessage = "Starting…", createdAt = System.currentTimeMillis()
        )
      )

      // Reset per-turn stream bookkeeping before the runtime starts emitting.
      currentTurnUuid = turnUuid
      turnText = StringBuilder()
      streamingTextBlockUuid = null
      runningToolBlocks = mutableMapOf()
      approvalBlocks = mutableMapOf()

      repository.runAgentTask(prompt, sessionId)
    }
  }

  fun toggleDevServer() {
    repository.toggleDevServer()
  }
}
