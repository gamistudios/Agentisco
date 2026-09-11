package com.agentisco.data.repository

import com.agentisco.settings.model.AIProvider
import com.agentisco.settings.model.AIModel
import com.agentisco.agent.model.AgentPermissions
import com.agentisco.agent.model.PendingApproval
import com.agentisco.agent.model.ToolExecution
import com.agentisco.agent.model.ToolType
import com.agentisco.agent.model.AgentTaskStep
import com.agentisco.agent.model.AgentStepStatus
import com.agentisco.core.model.AppDestination
import com.agentisco.agent.permission.DestructiveCommandGuard
import com.agentisco.agent.runtime.AgentRuntime
import com.agentisco.workspace.terminal.LinuxEnvironmentManager
import com.agentisco.workspace.terminal.TerminalProcessManager
import com.agentisco.workspace.git.GitRepositoryManager
import com.agentisco.workspace.filesystem.ProjectFileSystem
import android.content.Context
import com.agentisco.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class WorkspaceRepository(
  context: Context? = null,
  baseDir: File = context?.let { File(it.filesDir, "sco_projects") }
    ?: File(System.getProperty("java.io.tmpdir") ?: ".", "sco_projects")
) {

  private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  val fileSystem = ProjectFileSystem(baseDir)
  val gitManager = GitRepositoryManager(fileSystem)
  val linuxEnv = LinuxEnvironmentManager(
    fileSystem = fileSystem,
    gitManager = gitManager,
    activeProjectProvider = { _activeProject.value },
    stagedFilesProvider = { _stagedFiles.value },
    onStageFile = { f -> toggleFileStaged(f) },
    onStageAll = { stageAll() },
    onCommit = { msg -> commitStagedChanges() },
    onRevertFile = { f -> rejectDiff(f) }
  )
  val terminalManager = TerminalProcessManager(linuxEnv)
  val agentRuntime = AgentRuntime(fileSystem, terminalManager, gitManager)

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

  private val _agentWorkingDurationSeconds = MutableStateFlow(0)
  val agentWorkingDurationSeconds: StateFlow<Int> = _agentWorkingDurationSeconds.asStateFlow()

  private val _agentSteps = MutableStateFlow<List<AgentTaskStep>>(getInitialAgentSteps())
  val agentSteps: StateFlow<List<AgentTaskStep>> = _agentSteps.asStateFlow()

  private val _toolExecutions = MutableStateFlow<List<ToolExecution>>(getInitialToolExecutions())
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

  // Terminal Sessions
  private val _terminalSessions = MutableStateFlow<List<TerminalSession>>(getInitialTerminalSessions())
  val terminalSessions: StateFlow<List<TerminalSession>> = _terminalSessions.asStateFlow()

  private val _activeTerminalSessionId = MutableStateFlow("term-1")
  val activeTerminalSessionId: StateFlow<String> = _activeTerminalSessionId.asStateFlow()

  // Terminal command history (shared across sessions, like a shell's ~/.bash_history)
  private val _terminalCommandHistory = MutableStateFlow<List<String>>(emptyList())
  val terminalCommandHistory: StateFlow<List<String>> = _terminalCommandHistory.asStateFlow()

  // AI Providers & Models
  private val _providers = MutableStateFlow<List<AIProvider>>(getInitialProviders())
  val providers: StateFlow<List<AIProvider>> = _providers.asStateFlow()

  private val _selectedModel = MutableStateFlow(
    AIModel(
      id = "glm-5.3-free",
      name = "GLM 5.3 Free",
      providerId = "tokenrouter",
      contextWindow = "1000k",
      hasTools = true,
      hasStreaming = true,
      hasReasoning = true,
      isFree = true
    )
  )
  val selectedModel: StateFlow<AIModel> = _selectedModel.asStateFlow()

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
  fun selectTerminalSession(id: String) {
    _activeTerminalSessionId.value = id
  }

  fun createTerminalSession(name: String = "bash") {
    val newId = "term-${System.currentTimeMillis()}"
    val newSession = TerminalSession(
      id = newId,
      name = name,
      currentDir = _activeProject.value.path,
      lines = listOf(
        TerminalLine("Agentisco Terminal Environment v2.4 (Android Linux)", TerminalLineType.INFO),
        TerminalLine("Current Dir: ${_activeProject.value.path}", TerminalLineType.INFO),
        TerminalLine("Available: git, ssh, apt, pkg, termux-bridge, curl, node, python, sudo", TerminalLineType.INFO),
        TerminalLine("Type 'help' for available commands, or 'apt install <pkg>' to install packages.", TerminalLineType.SUCCESS)
      )
    )
    _terminalSessions.update { it + newSession }
    _activeTerminalSessionId.value = newId
  }

  fun closeTerminalSession(id: String) {
    val currentList = _terminalSessions.value
    if (currentList.size <= 1) {
      val resetSession = TerminalSession(
        id = "term-${System.currentTimeMillis()}",
        name = "main",
        currentDir = _activeProject.value.path,
        lines = listOf(
          TerminalLine("Agentisco Terminal Environment v2.4 (Android Linux)", TerminalLineType.INFO),
          TerminalLine("Current Dir: ${_activeProject.value.path}", TerminalLineType.INFO),
          TerminalLine("Session cleared. Type 'help' for developer tools.", TerminalLineType.SUCCESS)
        )
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

  private var pendingTerminalApprovalAction: ((Boolean) -> Unit)? = null

  private fun appendTerminalHistory(command: String) {
    if (command.isBlank()) return
    _terminalCommandHistory.update { history ->
      val next = if (history.lastOrNull() == command) history else history + command
      if (next.size > MAX_HISTORY_SIZE) next.drop(next.size - MAX_HISTORY_SIZE) else next
    }
  }

  fun executeTerminalCommand(command: String, bypassGuard: Boolean = false) {
    val cleanCmd = command.trim()
    if (cleanCmd.isEmpty()) return

    val currentId = _activeTerminalSessionId.value
    val session = _terminalSessions.value.firstOrNull { it.id == currentId } ?: return

    if (!bypassGuard) {
      val assessment = DestructiveCommandGuard.assess(cleanCmd)
      if (assessment != null) {
        _terminalSessions.update { list ->
          list.map { s ->
            if (s.id == currentId) {
              s.copy(
                lines = s.lines + listOf(
                  TerminalLine("$ $cleanCmd", TerminalLineType.COMMAND),
                  TerminalLine("⚠️ DESTRUCTIVE ACTION GUARD TRIGGERED", TerminalLineType.STDERR),
                  TerminalLine("Command: $cleanCmd", TerminalLineType.STDERR),
                  TerminalLine("Impact: ${assessment.reason}", TerminalLineType.STDERR),
                  TerminalLine("A safety confirmation is required. Please review the dialog to proceed.", TerminalLineType.INFO)
                ),
                isRunning = false
              )
            } else s
          }
        }

        val approval = PendingApproval(
          id = "guard-${System.currentTimeMillis()}",
          command = cleanCmd,
          title = assessment.title,
          impactDescription = assessment.reason,
          isDestructive = true
        )

        pendingTerminalApprovalAction = { allowed ->
          if (allowed) {
            _terminalSessions.update { list ->
              list.map { s ->
                if (s.id == currentId) {
                  s.copy(
                    lines = s.lines + TerminalLine("✓ Safety confirmation granted by user. Executing...", TerminalLineType.SUCCESS)
                  )
                } else s
              }
            }
            appendTerminalHistory(cleanCmd)
            executeTerminalCommandInternal(cleanCmd, currentId)
          } else {
            _terminalSessions.update { list ->
              list.map { s ->
                if (s.id == currentId) {
                  s.copy(
                    lines = s.lines + TerminalLine("✗ Aborted: Destructive execution cancelled by user.", TerminalLineType.STDERR)
                  )
                } else s
              }
            }
          }
        }

        requestApproval(approval)
        return
      }
    }

    // Record in history before echoing the command line (also covers `clear`)
    appendTerminalHistory(cleanCmd)

    // Add command input line immediately
    _terminalSessions.update { list ->
      list.map { s ->
        if (s.id == currentId) {
          if (cleanCmd == "clear") {
            s.copy(lines = emptyList(), isRunning = false)
          } else {
            s.copy(
              lines = s.lines + TerminalLine("$ $cleanCmd", TerminalLineType.COMMAND),
              isRunning = true
            )
          }
        } else s
      }
    }

    if (cleanCmd == "clear") return

    executeTerminalCommandInternal(cleanCmd, currentId)
  }

  private fun executeTerminalCommandInternal(cleanCmd: String, currentId: String) {
    val session = _terminalSessions.value.firstOrNull { it.id == currentId } ?: return

    _terminalSessions.update { list ->
      list.map { s ->
        if (s.id == currentId) s.copy(isRunning = true) else s
      }
    }

    repositoryScope.launch {
      val exitCode = terminalManager.executeCommand(session, cleanCmd) { line ->
        _terminalSessions.update { list ->
          list.map { s ->
            if (s.id == currentId) {
              s.copy(lines = s.lines + line)
            } else s
          }
        }
      }

      _terminalSessions.update { list ->
        list.map { s ->
          if (s.id == currentId) s.copy(isRunning = false) else s
        }
      }

      // Check if command might have affected files or git
      if (cleanCmd.startsWith("touch") || cleanCmd.startsWith("rm") || cleanCmd.startsWith("mkdir") || cleanCmd.startsWith("git")) {
        refreshFiles()
      }
    }
  }

  fun interruptTerminal(sessionId: String = _activeTerminalSessionId.value) {
    terminalManager.interrupt(sessionId)
    _terminalSessions.update { list ->
      list.map { s ->
        if (s.id == sessionId) {
          s.copy(lines = s.lines + TerminalLine("^C (Interrupted)", TerminalLineType.STDERR), isRunning = false)
        } else s
      }
    }
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
    val terminalAction = pendingTerminalApprovalAction
    pendingTerminalApprovalAction = null
    terminalAction?.invoke(allowed)
    agentRuntime.resolvePendingApproval(allowed)
  }

  // Run Real Agent Task Workflow
  suspend fun runAgentTask(prompt: String) {
    if (_isAgentWorking.value) return

    _isAgentWorking.value = true
    _agentStatusText.value = "Starting agent task..."
    _agentWorkingDurationSeconds.value = 0

    val currentSession = _terminalSessions.value.firstOrNull { it.id == _activeTerminalSessionId.value }
      ?: _terminalSessions.value.first()

    val result = agentRuntime.executeTask(
      prompt = prompt,
      project = _activeProject.value,
      model = _selectedModel.value,
      permissions = _permissions.value,
      terminalSession = currentSession,
      onStatus = { _agentStatusText.value = it },
      onStepUpdate = { _agentSteps.value = it },
      onToolExecuted = { tool -> _toolExecutions.update { listOf(tool) + it } },
      onRequestApproval = { approval -> _pendingApproval.value = approval }
    )

    _isAgentWorking.value = false
    refreshFiles()

    // Refresh active file if modified
    if (result.modifiedFiles.contains(_activeFile.value.path)) {
      val freshContent = fileSystem.readFile(_activeProject.value, _activeFile.value.path)
      _editorContent.value = freshContent
      _isEditorDirty.value = false
    }

    _agentStatusText.value = result.summary
  }

  fun toggleDevServer() {
    _isDevServerRunning.update { !it }
  }

  companion object {
    private const val MAX_HISTORY_SIZE = 500

    fun getInitialAgentSteps(): List<AgentTaskStep> {
      return listOf(
        AgentTaskStep("s1", "Understand project context", AgentStepStatus.COMPLETED, "Inspected package.json and workspace files", listOf("package.json", "tsconfig.json")),
        AgentTaskStep("s2", "Locate relevant source files", AgentStepStatus.COMPLETED, "Searched for chat components and state handlers", listOf("Chat.tsx", "chatStore.ts")),
        AgentTaskStep("s3", "Trace message store lifecycle", AgentStepStatus.COMPLETED, "Analyzed zustand hooks and cache mapping"),
        AgentTaskStep("s4", "Identify root state bug", AgentStepStatus.COMPLETED, finding = "Message state was reset to empty array on conversation mount without checking cached map"),
        AgentTaskStep("s5", "Implement code fix", AgentStepStatus.COMPLETED, "Updated Chat.tsx and chatStore.ts with memoized selectors"),
        AgentTaskStep("s6", "Run project test suite", AgentStepStatus.COMPLETED, "Ran vitest unit tests with exit code 0"),
        AgentTaskStep("s7", "Verify diff & build", AgentStepStatus.COMPLETED, "Confirmed clean compilation and diff generation")
      )
    }

    fun getInitialToolExecutions(): List<ToolExecution> {
      return listOf(
        ToolExecution(
          id = "init-1",
          type = ToolType.TERMINAL,
          title = "$ npm test",
          subtitle = "42 tests passed · Exit code 0",
          exitCode = 0,
          output = "PASS src/components/Chat.test.tsx\nTests: 42 passed, 42 total"
        ),
        ToolExecution(
          id = "init-2",
          type = ToolType.EDIT_FILE,
          title = "write_file src/components/Chat.tsx",
          subtitle = "+14 lines, -6 lines applied",
          details = "Updated chat component with cached memo selectors"
        ),
        ToolExecution(
          id = "init-3",
          type = ToolType.READ_FILE,
          title = "read_file src/store/chatStore.ts",
          subtitle = "62 lines inspected",
          details = "Inspected Zustand cachedMap handler"
        )
      )
    }

    fun getInitialTerminalSessions(): List<TerminalSession> {
      return listOf(
        TerminalSession(
          id = "term-1",
          name = "main",
          currentDir = "~/projects/agentisco",
          lines = listOf(
            TerminalLine("Agentisco Developer Shell v2.4 (Android sh)", TerminalLineType.INFO),
            TerminalLine("Working directory initialized.", TerminalLineType.INFO)
          )
        ),
        TerminalSession(
          id = "term-2",
          name = "dev-server",
          currentDir = "~/projects/agentisco",
          lines = listOf(
            TerminalLine("> vite --port 5173", TerminalLineType.COMMAND),
            TerminalLine("VITE v5.2.0  ready in 280 ms", TerminalLineType.SUCCESS),
            TerminalLine("➜  Local:   http://localhost:5173/", TerminalLineType.SUCCESS)
          )
        )
      )
    }

    fun getInitialProviders(): List<AIProvider> {
      return listOf(
        AIProvider(
          id = "google",
          name = "Google Gemini",
          baseUrl = "https://generativelanguage.googleapis.com",
          apiKey = "AIzaSy•••••••••••••",
          isConnected = true,
          models = listOf(
            AIModel("gemini-3.1-pro-preview", "Gemini 3.1 Pro", "google", "2000k", hasTools = true, hasStreaming = true, hasVision = true, hasReasoning = true),
            AIModel("gemini-3.5-flash", "Gemini 3.5 Flash", "google", "1000k", hasTools = true, hasStreaming = true, hasVision = true)
          )
        ),
        AIProvider(
          id = "tokenrouter",
          name = "TokenRouter",
          baseUrl = "https://api.tokenrouter.io/v1",
          apiKey = "tr-live-•••••••••••••",
          isConnected = true,
          models = listOf(
            AIModel("glm-5.3-free", "GLM 5.3 Free", "tokenrouter", "1000k", hasTools = true, hasStreaming = true, hasReasoning = true, isFree = true),
            AIModel("deepseek-r1", "DeepSeek R1", "tokenrouter", "128k", hasTools = true, hasStreaming = true, hasReasoning = true)
          )
        ),
        AIProvider(
          id = "openai",
          name = "OpenAI",
          baseUrl = "https://api.openai.com/v1",
          apiKey = "sk-•••••••••••••",
          isConnected = true,
          models = listOf(
            AIModel("gpt-4o", "GPT-4o", "openai", "128k", hasTools = true, hasStreaming = true, hasVision = true),
            AIModel("o3-mini", "o3-mini", "openai", "200k", hasTools = true, hasStreaming = true, hasReasoning = true)
          )
        )
      )
    }
  }
}
