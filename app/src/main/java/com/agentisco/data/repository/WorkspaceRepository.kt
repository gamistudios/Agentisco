package com.agentisco.data.repository

import com.agentisco.settings.model.AIProvider
import com.agentisco.settings.model.AIModel
import com.agentisco.agent.model.AgentPermissions
import com.agentisco.agent.model.PendingApproval
import com.agentisco.agent.model.ToolExecution
import com.agentisco.agent.model.ToolType
import com.agentisco.agent.model.AgentTaskStep
import com.agentisco.core.model.AppDestination
import com.agentisco.agent.permission.DestructiveCommandGuard
import com.agentisco.agent.runtime.AgentRuntime
import com.agentisco.workspace.terminal.DebianBootstrap
import com.agentisco.workspace.terminal.NativeBinaries
import com.agentisco.workspace.terminal.ProotArgsBuilder
import com.agentisco.workspace.terminal.ProotSessionManager
import com.agentisco.workspace.terminal.TerminalProcessManager
import com.termux.terminal.TerminalSessionClient
import com.agentisco.workspace.git.GitRepositoryManager
import com.agentisco.workspace.filesystem.ProjectFileSystem
import android.content.Context
import com.agentisco.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class WorkspaceRepository(
  context: Context? = null,
  baseDir: File = context?.let { File(it.filesDir, "sco_projects") }
    ?: File(System.getProperty("java.io.tmpdir") ?: ".", "sco_projects"),
  val providerStore: com.agentisco.data.local.ProviderConfigStore? = null
) {

  private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val appContext: Context? = context?.applicationContext

  val fileSystem = ProjectFileSystem(baseDir)
  val gitManager = GitRepositoryManager(fileSystem)

  // Real Linux terminal stack: proot binaries (bundled via jniLibs) + a
  // Debian-based rootfs downloaded and verified on first use. Null when no
  // Android context is available (unit tests / previews).
  val nativeBinaries: NativeBinaries? = context?.let(::NativeBinaries)
  val debianBootstrap: DebianBootstrap? = context?.let { ctx ->
    nativeBinaries?.let { DebianBootstrap(ctx, it) }
  }
  private val prootSessionManager: ProotSessionManager? = context?.let { ctx ->
    val bootstrap = debianBootstrap ?: return@let null
    val bins = nativeBinaries ?: return@let null
    ProotSessionManager(ctx, bins, bootstrap.rootfsDir)
  }
  val terminalManager = TerminalProcessManager {
    val bootstrap = debianBootstrap?.takeIf { it.isBootstrapped() } ?: return@TerminalProcessManager null
    val bins = nativeBinaries ?: return@TerminalProcessManager null
    ProotArgsBuilder(bins, bootstrap.rootfsDir)
  }

  // LLM communication + agent tooling. Providers are configuration only; the
  // protocol adapter is chosen from the provider config, never from its name.
  private val llmService = com.agentisco.agent.llm.LlmService()
  private val toolRegistry = com.agentisco.agent.tool.AgentToolRegistry(
    fileSystem = fileSystem,
    gitManager = gitManager,
    terminalManager = terminalManager,
    stagedFilesProvider = { _stagedFiles.value },
    onStageFile = { f -> toggleFileStaged(f) },
    onStageAll = { stageAll() },
    onUnstageAll = { unstageAll() }
  )
  val agentRuntime = AgentRuntime(fileSystem, terminalManager, gitManager, llmService, toolRegistry)

  // Current Projects
  private val _projects = MutableStateFlow<List<Project>>(fileSystem.getProjects())
  val projects: StateFlow<List<Project>> = _projects.asStateFlow()

  private val _activeProject = MutableStateFlow<Project>(_projects.value.first())
  val activeProject: StateFlow<Project> = _activeProject.asStateFlow()

  // App Navigation Destination
  private val _currentDestination = MutableStateFlow(AppDestination.AGENT)
  val currentDestination: StateFlow<AppDestination> = _currentDestination.asStateFlow()

  // Files in Active Project
  private val _projectFiles = MutableStateFlow<List<ProjectFile>>(emptyList())
  val projectFiles: StateFlow<List<ProjectFile>> = _projectFiles.asStateFlow()

  // Currently Active File in Editor
  private val _activeFile = MutableStateFlow<ProjectFile>(
    ProjectFile("src/components/Chat.tsx", "Chat.tsx", false)
  )
  val activeFile: StateFlow<ProjectFile> = _activeFile.asStateFlow()

  // Editor content & unsaved tracking
  private val _editorContent = MutableStateFlow("")
  val editorContent: StateFlow<String> = _editorContent.asStateFlow()

  private val _isEditorDirty = MutableStateFlow(false)
  val isEditorDirty: StateFlow<Boolean> = _isEditorDirty.asStateFlow()

  // Agent State
  private val _isAgentWorking = MutableStateFlow(false)
  val isAgentWorking: StateFlow<Boolean> = _isAgentWorking.asStateFlow()

  private val _agentStatusText = MutableStateFlow("Ready for tasks")
  val agentStatusText: StateFlow<String> = _agentStatusText.asStateFlow()

  /** Progressively streamed assistant response for the current/last task. */
  private val _agentResponse = MutableStateFlow("")
  val agentResponse: StateFlow<String> = _agentResponse.asStateFlow()

  private val _agentWorkingDurationSeconds = MutableStateFlow(0)
  val agentWorkingDurationSeconds: StateFlow<Int> = _agentWorkingDurationSeconds.asStateFlow()

  /**
   * Live agent event stream. Every event is emitted by the real runtime as it
   * happens (status, streamed tokens, tool calls, approvals, results) — the UI
   * renders it chronologically; nothing here is simulated.
   */
  private val _agentEvents = MutableSharedFlow<com.agentisco.agent.model.AgentStreamEvent>(
    replay = 0, extraBufferCapacity = 128
  )
  val agentEvents = _agentEvents.asSharedFlow()

  // Real activity only: empty until the agent actually performs something.
  private val _agentSteps = MutableStateFlow<List<AgentTaskStep>>(emptyList())
  val agentSteps: StateFlow<List<AgentTaskStep>> = _agentSteps.asStateFlow()

  private val _toolExecutions = MutableStateFlow<List<ToolExecution>>(emptyList())
  val toolExecutions: StateFlow<List<ToolExecution>> = _toolExecutions.asStateFlow()

  private val _pendingApproval = MutableStateFlow<PendingApproval?>(null)
  val pendingApproval: StateFlow<PendingApproval?> = _pendingApproval.asStateFlow()

  // Diffs
  private val _fileDiffs = MutableStateFlow<List<FileDiff>>(emptyList())
  val fileDiffs: StateFlow<List<FileDiff>> = _fileDiffs.asStateFlow()

  // Git State
  private val _stagedFiles = MutableStateFlow<Set<String>>(emptySet())
  val stagedFiles: StateFlow<Set<String>> = _stagedFiles.asStateFlow()

  private val _commitMessage = MutableStateFlow("Fix chat message lifecycle and store recreation")
  val commitMessage: StateFlow<String> = _commitMessage.asStateFlow()

  private val _commitHistory = MutableStateFlow<List<GitCommit>>(emptyList())
  val commitHistory: StateFlow<List<GitCommit>> = _commitHistory.asStateFlow()

  // Terminal tabs (metadata only; the actual console lives in a real PTY
  // session per tab, created on demand when the environment is bootstrapped).
  private val _terminalSessions = MutableStateFlow<List<TerminalSession>>(getInitialTerminalSessions())
  val terminalSessions: StateFlow<List<TerminalSession>> = _terminalSessions.asStateFlow()

  private val _activeTerminalSessionId = MutableStateFlow("term-1")
  val activeTerminalSessionId: StateFlow<String> = _activeTerminalSessionId.asStateFlow()

  // Real PTY-backed terminal sessions keyed by tab id.
  private val _ptySessions = MutableStateFlow<Map<String, com.termux.terminal.TerminalSession>>(emptyMap())
  val ptySessions: StateFlow<Map<String, com.termux.terminal.TerminalSession>> = _ptySessions.asStateFlow()

  /** Per-tab client bridges; the UI attaches a redraw callback to the visible one. */
  val terminalClientRegistry = HashMap<String, TerminalClientBridge>()

  // AI Providers & Models — durable configuration via ProviderConfigStore.
  // A model belongs to exactly one provider record; the same model identifier
  // may exist under multiple providers (each is a distinct selectable model).
  private val _providers = MutableStateFlow<List<AIProvider>>(emptyList())
  val providers: StateFlow<List<AIProvider>> = _providers.asStateFlow()

  private val _aiModels = MutableStateFlow<List<AIModel>>(emptyList())
  val aiModels: StateFlow<List<AIModel>> = _aiModels.asStateFlow()

  private val _selectedModel = MutableStateFlow<AIModel?>(null)
  val selectedModel: StateFlow<AIModel?> = _selectedModel.asStateFlow()

  /** Per-provider connection test outcome (never contains secrets). */
  sealed class ConnectionTestState {
    data object Testing : ConnectionTestState()
    data class Connected(val note: String) : ConnectionTestState()
    data class Failed(val message: String) : ConnectionTestState()
  }

  private val _connectionTests = MutableStateFlow<Map<String, ConnectionTestState>>(emptyMap())
  val connectionTests: StateFlow<Map<String, ConnectionTestState>> = _connectionTests.asStateFlow()

  // Agent Permissions
  private val _permissions = MutableStateFlow(AgentPermissions())
  val permissions: StateFlow<AgentPermissions> = _permissions.asStateFlow()

  // Search Query & Results
  private val _searchQuery = MutableStateFlow("")
  val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

  // Command Palette Open
  private val _isCommandPaletteOpen = MutableStateFlow(false)
  val isCommandPaletteOpen: StateFlow<Boolean> = _isCommandPaletteOpen.asStateFlow()

  // Model Selector Sheet Open
  private val _isModelSheetOpen = MutableStateFlow(false)
  val isModelSheetOpen: StateFlow<Boolean> = _isModelSheetOpen.asStateFlow()

  // Dev Server / Preview State
  private val _isDevServerRunning = MutableStateFlow(true)
  val isDevServerRunning: StateFlow<Boolean> = _isDevServerRunning.asStateFlow()

  init {
    loadActiveProjectState(_activeProject.value)
    loadProviderConfiguration()
  }

  // ---- Provider / model configuration (Task: AI provider system) ----

  private fun loadProviderConfiguration() {
    val store = providerStore
    if (store != null) {
      store.reconcileSelection(autoSelectFallback = true)
      _providers.value = store.getProviders()
      _aiModels.value = store.getModels()
      _selectedModel.value = store.getSelectedModelId()?.let { id -> _aiModels.value.firstOrNull { it.id == id } }
    } else {
      // No durable store (tests/previews): operate in-memory.
      _providers.value = emptyList()
      _aiModels.value = emptyList()
      _selectedModel.value = null
    }
  }

  fun saveProvider(name: String, baseUrl: String, protocol: com.agentisco.settings.model.LLMProtocol, apiKey: String?, providerId: String? = null): AIProvider {
    val id = providerId ?: "provider-${System.currentTimeMillis()}"
    val existing = _providers.value.firstOrNull { it.id == id }
    val keyChanged = apiKey != null
    val provider = AIProvider(
      id = id,
      name = name.trim(),
      baseUrl = baseUrl.trim(),
      protocol = protocol,
      hasApiKey = if (keyChanged) apiKey?.isNotBlank() == true else (existing?.hasApiKey ?: false)
    )
    providerStore?.upsertProvider(provider, apiKey)
    _providers.value = providerStore?.getProviders() ?: (_providers.value.filterNot { it.id == id } + provider)
    _connectionTests.update { it - id }
    return provider
  }

  /**
   * Deletes a provider and all model records that belong to it. The selection
   * is reconciled afterwards: if the selected model was removed, another
   * available model is selected (or the selection becomes empty).
   */
  fun deleteProvider(providerId: String) {
    providerStore?.deleteProvider(providerId)
    _providers.value = providerStore?.getProviders() ?: _providers.value.filterNot { it.id == providerId }
    _aiModels.value = providerStore?.getModels() ?: _aiModels.value.filterNot { it.providerId == providerId }
    if (_selectedModel.value?.providerId == providerId) {
      _selectedModel.value = _aiModels.value.firstOrNull()
      _selectedModel.value?.let { providerStore?.selectModel(it.id) }
    }
    _connectionTests.update { it - providerId }
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
  ): AIModel? {
    if (modelId.isBlank() || displayName.isBlank()) return null
    if (_providers.value.none { it.id == providerId }) return null
    val model = AIModel(
      id = recordId ?: "model-${System.currentTimeMillis()}",
      providerId = providerId,
      modelId = modelId.trim(),
      displayName = displayName.trim(),
      contextWindow = contextWindow,
      maxOutputTokens = maxOutputTokens,
      capabilities = capabilities,
      reasoning = reasoning
    )
    providerStore?.upsertModel(model)
    _aiModels.value = providerStore?.getModels() ?: (_aiModels.value.filterNot { it.id == model.id } + model)
    if (_selectedModel.value == null) selectModel(model.id)
    return model
  }

  fun deleteModel(recordId: String) {
    val wasSelected = _selectedModel.value?.id == recordId
    providerStore?.deleteModel(recordId)
    _aiModels.value = providerStore?.getModels() ?: _aiModels.value.filterNot { it.id == recordId }
    if (wasSelected) {
      _selectedModel.value = _aiModels.value.firstOrNull()
      _selectedModel.value?.let { providerStore?.selectModel(it.id) }
    }
  }

  /** Selects by unique model record id — never by model name. */
  fun selectModel(recordId: String) {
    val model = _aiModels.value.firstOrNull { it.id == recordId } ?: return
    providerStore?.selectModel(recordId)
    _selectedModel.value = model
    _isModelSheetOpen.value = false
  }

  /** Real connection test: probe the endpoint first, then fall back to an actual model request. */
  fun testProviderConnection(providerId: String) {
    val provider = _providers.value.firstOrNull { it.id == providerId } ?: return
    val apiKey = providerStore?.getApiKey(providerId)
    // Test with the selected model when it belongs to this provider, otherwise
    // the provider's first configured model (the one the agent would use).
    val testModel = sequenceOf(_selectedModel.value, _aiModels.value.firstOrNull { it.providerId == providerId })
      .filterNotNull()
      .firstOrNull { it.providerId == providerId }
    _connectionTests.update { it + (providerId to ConnectionTestState.Testing) }
    repositoryScope.launch {
      val state = try {
        val (ok, message) = llmService.testConnection(provider, testModel, apiKey ?: "")
        if (ok) {
          val modelCount = _aiModels.value.count { it.providerId == providerId }
          val note = message + if (modelCount == 0) " (no models configured — add one before using the agent)" else ""
          ConnectionTestState.Connected(note)
        } else ConnectionTestState.Failed(message)
      } catch (e: Exception) {
        ConnectionTestState.Failed(e.message ?: "Connection test failed")
      }
      _connectionTests.update { it + (providerId to state) }
    }
  }

  private fun resolveProviderForModel(model: AIModel): Pair<AIProvider, String>? {
    val provider = _providers.value.firstOrNull { it.id == model.providerId } ?: return null
    val apiKey = providerStore?.getApiKey(provider.id) ?: return null
    if (apiKey.isBlank()) return null
    return provider to apiKey
  }

  private fun loadActiveProjectState(project: Project) {
    gitManager.initializeProjectBaseline(project)
    val files = fileSystem.getFileTree(project)
    _projectFiles.value = files

    // Pick first code file
    fun findFirstFile(list: List<ProjectFile>): ProjectFile? {
      for (f in list) {
        if (!f.isDirectory) return f
        val sub = findFirstFile(f.children)
        if (sub != null) return sub
      }
      return null
    }

    val firstFile = findFirstFile(files) ?: ProjectFile("README.md", "README.md", false)
    _activeFile.value = firstFile
    val content = fileSystem.readFile(project, firstFile.path)
    _editorContent.value = content
    _isEditorDirty.value = false

    // Update terminal session directory
    _terminalSessions.update { list ->
      list.map { it.copy(currentDir = project.path) }
    }

    refreshDiffsAndGit()
  }

  fun refreshFiles() {
    _projectFiles.value = fileSystem.getFileTree(_activeProject.value)
    refreshDiffsAndGit()
  }

  private fun refreshDiffsAndGit() {
    val diffs = gitManager.computeAllDiffs(_activeProject.value)
    _fileDiffs.value = diffs
    val changedFiles = gitManager.getChangedFiles(_activeProject.value)
    _activeProject.update {
      it.copy(
        changedFilesCount = changedFiles.size,
        isDirty = changedFiles.isNotEmpty()
      )
    }
    _commitHistory.value = gitManager.getCommitHistory(_activeProject.value)
  }

  // Navigation
  fun navigateTo(destination: AppDestination) {
    _currentDestination.value = destination
  }

  fun selectProject(project: Project) {
    _activeProject.value = project
    loadActiveProjectState(project)
  }

  fun createProject(name: String, description: String) {
    val newProj = fileSystem.createProject(name, description)
    _projects.value = fileSystem.getProjects()
    selectProject(newProj)
  }

  fun openFile(file: ProjectFile) {
    if (!file.isDirectory) {
      _activeFile.value = file
      val diskContent = fileSystem.readFile(_activeProject.value, file.path)
      _editorContent.value = diskContent
      _isEditorDirty.value = false
      _currentDestination.value = AppDestination.EDITOR
    }
  }

  fun updateEditorContent(content: String) {
    _editorContent.value = content
    _isEditorDirty.value = content != _activeFile.value.content
  }

  fun saveActiveFile() {
    val file = _activeFile.value
    val text = _editorContent.value
    fileSystem.writeFile(_activeProject.value, file.path, text)
    _activeFile.value = file.copy(content = text, sizeBytes = text.length.toLong())
    _isEditorDirty.value = false
    refreshFiles()
  }

  fun createFile(relativePath: String, content: String = ""): Boolean {
    val success = fileSystem.createFile(_activeProject.value, relativePath, content)
    if (success) {
      refreshFiles()
      openFile(ProjectFile(relativePath, relativePath.substringAfterLast("/"), false, content))
    }
    return success
  }

  fun createDirectory(relativePath: String): Boolean {
    val success = fileSystem.createDirectory(_activeProject.value, relativePath)
    if (success) {
      refreshFiles()
    }
    return success
  }

  fun deleteFile(relativePath: String): Boolean {
    val success = fileSystem.deleteFile(_activeProject.value, relativePath)
    if (success) {
      refreshFiles()
    }
    return success
  }

  fun renameFile(oldPath: String, newName: String): Boolean {
    val success = fileSystem.renameFile(_activeProject.value, oldPath, newName)
    if (success) {
      refreshFiles()
    }
    return success
  }

  fun toggleCommandPalette(open: Boolean? = null) {
    _isCommandPaletteOpen.value = open ?: !_isCommandPaletteOpen.value
  }

  fun toggleModelSheet(open: Boolean? = null) {
    _isModelSheetOpen.value = open ?: !_isModelSheetOpen.value
  }

  fun selectModel(model: AIModel) {
    _selectedModel.value = model
    _isModelSheetOpen.value = false
  }

  fun updateSearchQuery(query: String) {
    _searchQuery.value = query
  }

  fun toggleFileStaged(filePath: String) {
    _stagedFiles.update { current ->
      if (current.contains(filePath)) current - filePath else current + filePath
    }
  }

  fun stageAll() {
    val allChanged = gitManager.getChangedFiles(_activeProject.value)
    _stagedFiles.value = allChanged.toSet()
  }

  fun unstageAll() {
    _stagedFiles.value = emptySet()
  }

  fun updateCommitMessage(msg: String) {
    _commitMessage.value = msg
  }

  fun generateCommitMessageWithAgent() {
    val changed = gitManager.getChangedFiles(_activeProject.value)
    val filesDesc = if (changed.isNotEmpty()) changed.joinToString(", ") { it.substringAfterLast("/") } else "code"
    val suggested = listOf(
      "fix($filesDesc): update component lifecycle and state consistency",
      "refactor($filesDesc): improve data flow and type safety",
      "feat($filesDesc): implement requested updates from agent workflow",
      "chore: update project configuration and verification tests"
    ).random()
    _commitMessage.value = suggested
  }

  fun commitStagedChanges() {
    val commit = gitManager.commit(_activeProject.value, _stagedFiles.value, _commitMessage.value)
    if (commit != null) {
      _stagedFiles.value = emptySet()
      refreshDiffsAndGit()
    }
  }

  fun acceptAllDiffs() {
    // Staging all and setting baseline to current
    val changed = gitManager.getChangedFiles(_activeProject.value)
    gitManager.commit(_activeProject.value, changed.toSet(), "Accept changes")
    _stagedFiles.value = emptySet()
    refreshDiffsAndGit()
  }

  fun rejectAllDiffs() {
    gitManager.revertAllFiles(_activeProject.value)
    refreshFiles()
    // Reload active file content if it was reverted
    val reloaded = fileSystem.readFile(_activeProject.value, _activeFile.value.path)
    _editorContent.value = reloaded
    _isEditorDirty.value = false
  }

  fun rejectDiff(filePath: String) {
    gitManager.revertFile(_activeProject.value, filePath)
    refreshFiles()
    if (_activeFile.value.path == filePath) {
      val reloaded = fileSystem.readFile(_activeProject.value, filePath)
      _editorContent.value = reloaded
      _isEditorDirty.value = false
    }
  }

  // Terminal actions

  /** Runs the rootfs bootstrap (download → verify → extract → configure). */
  fun startLinuxBootstrap() {
    val bootstrap = debianBootstrap ?: return
    repositoryScope.launch { bootstrap.bootstrap() }
  }

  fun selectTerminalSession(id: String) {
    _activeTerminalSessionId.value = id
  }

  fun createTerminalSession(name: String = "bash") {
    val newId = "term-${System.currentTimeMillis()}"
    val newSession = TerminalSession(
      id = newId,
      name = name,
      currentDir = _activeProject.value.path
    )
    _terminalSessions.update { it + newSession }
    ensurePtySession(newId, name)
    _activeTerminalSessionId.value = newId
  }

  /** Lazily creates the PTY-backed session for a tab once Debian is ready. */
  fun ensurePtySession(tabId: String, name: String) {
    val manager = prootSessionManager ?: return
    if (_ptySessions.value.containsKey(tabId)) return
    try {
      val bridge = terminalClientRegistry.getOrPut(tabId) { TerminalClientBridge(appContext) }
      val session = manager.createSession(name, File(_activeProject.value.path), bridge)
      if (session != null) {
        _ptySessions.update { it + (tabId to session) }
      }
    } catch (t: Throwable) {
      // Never let a failed shell spawn take the whole app down.
      android.util.Log.e("ScoOS-Terminal", "Failed to create PTY session", t)
    }
  }

  fun closeTerminalSession(id: String) {
    _ptySessions.value[id]?.finishIfRunning()
    _ptySessions.update { it - id }
    terminalClientRegistry.remove(id)
    closeTerminalSessionTab(id)
  }

  private fun closeTerminalSessionTab(id: String) {
    val currentList = _terminalSessions.value
    if (currentList.size <= 1) {
      val resetSession = TerminalSession(
        id = "term-${System.currentTimeMillis()}",
        name = "main",
        currentDir = _activeProject.value.path
      )
      _terminalSessions.value = listOf(resetSession)
      _activeTerminalSessionId.value = resetSession.id
      return
    }

    val remaining = currentList.filter { it.id != id }
    _terminalSessions.value = remaining
    if (_activeTerminalSessionId.value == id) {
      _activeTerminalSessionId.value = remaining.first().id
    }
  }

  fun interruptTerminal(sessionId: String = _activeTerminalSessionId.value) {
    _ptySessions.value[sessionId]?.let { session ->
      // Send a real INTR character through the PTY: the foreground process
      // group inside the rootfs receives SIGINT exactly like a Linux terminal.
      session.write(byteArrayOf(0x03), 0, 1)
    }
    terminalManager.interrupt(sessionId)
  }

  /** Writes a full command line (plus newline) into the active PTY session. */
  fun sendTerminalLine(command: String) {
    val id = _activeTerminalSessionId.value
    _ptySessions.value[id]?.let { session ->
      val bytes = (command + "\n").toByteArray(Charsets.UTF_8)
      session.write(bytes, 0, bytes.size)
    }
  }

  /**
   * Bridges a PTY session to the visible terminal view: text changes trigger
   * the registered redraw callback, everything else is ignored.
   */
  class TerminalClientBridge(private val appContext: Context?) : TerminalSessionClient {
    @Volatile var redrawCallback: (() -> Unit)? = null

    override fun onTextChanged(session: com.termux.terminal.TerminalSession) {
      redrawCallback?.invoke()
    }
    override fun onTitleChanged(session: com.termux.terminal.TerminalSession) {}
    override fun onSessionFinished(session: com.termux.terminal.TerminalSession) {
      redrawCallback?.invoke()
    }
    override fun onCopyTextToClipboard(session: com.termux.terminal.TerminalSession, text: String) {
      val clipboard = appContext?.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
      clipboard.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
    }
    override fun onPasteTextFromClipboard(session: com.termux.terminal.TerminalSession) {
      val clipboard = appContext?.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
      val clip = clipboard.primaryClip ?: return
      val text = clip.getItemAt(0).coerceToText(appContext)?.toString() ?: return
      if (text.isEmpty()) return
      val bytes = text.toByteArray(Charsets.UTF_8)
      session.write(bytes, 0, bytes.size)
    }
    override fun onBell(session: com.termux.terminal.TerminalSession) {}
    override fun onColorsChanged(session: com.termux.terminal.TerminalSession) {
      redrawCallback?.invoke()
    }
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = 0
    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}
  }

  // Permission handling
  fun updatePermissions(transform: (AgentPermissions) -> AgentPermissions) {
    _permissions.update(transform)
  }

  fun requestApproval(approval: PendingApproval) {
    _pendingApproval.value = approval
  }

  fun resolveApproval(allowed: Boolean) {
    _pendingApproval.value = null
    agentRuntime.resolvePendingApproval(allowed)
  }

  // Run Real Agent Task Workflow
  suspend fun runAgentTask(prompt: String) {
    if (_isAgentWorking.value) return

    val model = _selectedModel.value
    if (model == null) {
      _agentStatusText.value = "No model selected — configure a provider and select a model in Settings first."
      _isModelSheetOpen.value = true
      return
    }
    val connection = resolveProviderForModel(model)
    if (connection == null) {
      _agentStatusText.value = "Provider \"${_providers.value.firstOrNull { it.id == model.providerId }?.name ?: model.providerId}\" is not configured with a valid API key."
      _isModelSheetOpen.value = true
      return
    }
    val (provider, apiKey) = connection

    _isAgentWorking.value = true
    _agentStatusText.value = "Starting agent task..."
    _agentResponse.value = ""
    _agentWorkingDurationSeconds.value = 0

    val currentSession = _terminalSessions.value.firstOrNull { it.id == _activeTerminalSessionId.value }
      ?: _terminalSessions.value.first()

    val result = agentRuntime.executeTask(
      prompt = prompt,
      project = _activeProject.value,
      provider = provider,
      model = model,
      apiKey = apiKey,
      permissions = _permissions.value,
      terminalSession = currentSession,
      onRequestApproval = { approval -> _pendingApproval.value = approval },
      onEvent = { event ->
        _agentEvents.tryEmit(event)
        when (event) {
          is com.agentisco.agent.model.AgentStreamEvent.Status -> _agentStatusText.value = event.text
          is com.agentisco.agent.model.AgentStreamEvent.Token -> _agentResponse.value += event.text
          is com.agentisco.agent.model.AgentStreamEvent.ToolFinished -> _toolExecutions.update { list ->
            listOf(
              ToolExecution(
                id = "tool-${System.currentTimeMillis()}-${event.name}",
                type = com.agentisco.agent.tool.toolTypeFor(event.name),
                title = event.name,
                subtitle = event.summary.take(80),
                exitCode = event.exitCode,
                output = event.detail.take(4000)
              )
            ) + list
          }
          else -> Unit
        }
      }
    )

    _isAgentWorking.value = false
    refreshFiles()

    // Refresh active file if modified
    if (result.modifiedFiles.contains(_activeFile.value.path)) {
      val freshContent = fileSystem.readFile(_activeProject.value, _activeFile.value.path)
      _editorContent.value = freshContent
      _isEditorDirty.value = false
    }

    _agentStatusText.value = if (result.success) result.summary else "Task failed — ${result.summary}"
  }

  fun toggleDevServer() {
    _isDevServerRunning.update { !it }
  }

  companion object {
    fun getInitialTerminalSessions(): List<TerminalSession> {
      return listOf(
        TerminalSession(
          id = "term-1",
          name = "main",
          currentDir = "~/projects/agentisco"
        )
      )
    }
  }
}
