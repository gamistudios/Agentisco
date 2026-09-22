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
import com.agentisco.workspace.filesystem.ProjectMetadataScanner
import com.agentisco.workspace.filesystem.WorkspaceFileWatcher
import com.agentisco.workspace.git.*
import android.content.Context
import com.agentisco.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class WorkspaceRepository(
  context: Context? = null,
  baseDir: File = context?.let { File(it.filesDir, "sco_projects") }
    ?: File(System.getProperty("java.io.tmpdir") ?: ".", "sco_projects"),
  val providerStore: com.agentisco.data.local.ProviderConfigStore? = null
) {

  private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val appContext: Context? = context?.applicationContext

  /** SQLite-backed persistence for agent sessions/messages/turn-blocks. */
  val chatStore = AgentChatStore(context)

  // Real Linux terminal stack (rootfs location is needed below to place
  // projects inside the guest's home directory).
  val nativeBinaries: NativeBinaries? = context?.let(::NativeBinaries)
  val debianBootstrap: DebianBootstrap? = context?.let { ctx ->
    nativeBinaries?.let { DebianBootstrap(ctx, it) }
  }

  /**
   * Projects live INSIDE the proot rootfs home (`/root/projects`, i.e.
   * `~/projects` for the terminal user) on the app's private ext4 storage —
   * full Linux semantics for git/builds/locks, and the paths the terminal
   * shows are the paths that actually work inside the shell.
   */
  val projectsRoot: File = debianBootstrap?.rootfsDir?.let { File(it, "root/projects") } ?: baseDir

  val fileSystem = ProjectFileSystem(projectsRoot)

  // ---- Scan exclusions (Settings → Codebase scanning) ----
  // Persisted user-tunable folder list skipped by tree scans, searches and
  // imports. Applied globally to ProjectFileSystem at startup.
  val scanIgnoreStore = com.agentisco.data.local.ScanIgnoreStore(context)
  private val _scanIgnoreSettings = MutableStateFlow(scanIgnoreStore.get())
  val scanIgnoreSettings: StateFlow<com.agentisco.data.local.ScanIgnoreSettings> =
    _scanIgnoreSettings.asStateFlow()

  init {
    ProjectFileSystem.ignoredDirs =
      com.agentisco.data.local.ScanIgnoreStore.effectiveDirs(_scanIgnoreSettings.value)
  }

  fun addIgnoredDir(raw: String) {
    val name = com.agentisco.data.local.ScanIgnoreStore.sanitizeName(raw)
    if (name.isBlank()) return
    _scanIgnoreSettings.value = scanIgnoreStore.update { s ->
      when {
        s.extraDirs.contains(name) -> s
        !s.useCustomListOnly && name in ProjectFileSystem.DEFAULT_IGNORED_DIRS ->
          s.copy(removedDefaults = s.removedDefaults - name)
        else -> s.copy(extraDirs = s.extraDirs + name)
      }
    }
  }

  fun removeIgnoredDir(raw: String) {
    val name = com.agentisco.data.local.ScanIgnoreStore.sanitizeName(raw)
    if (name.isBlank()) return
    _scanIgnoreSettings.value = scanIgnoreStore.update { s ->
      when {
        s.extraDirs.contains(name) -> s.copy(extraDirs = s.extraDirs - name)
        s.useCustomListOnly -> s
        name in ProjectFileSystem.DEFAULT_IGNORED_DIRS ->
          s.copy(removedDefaults = s.removedDefaults + name)
        else -> s
      }
    }
  }

  /** true = ignore the built-in defaults entirely; only the custom list applies. */
  fun setIgnoredDirsOverride(enabled: Boolean) {
    _scanIgnoreSettings.value = scanIgnoreStore.update { it.copy(useCustomListOnly = enabled) }
  }

  fun restoreDefaultIgnoredDirs() {
    scanIgnoreStore.reset()
    _scanIgnoreSettings.value = scanIgnoreStore.get()
  }

  // ---- Chat display (Settings → Tool activity) ----
  val chatDisplayStore = com.agentisco.data.local.ChatDisplayStore(context)
  private val _chatDisplay = MutableStateFlow(chatDisplayStore.get())
  val chatDisplay: StateFlow<com.agentisco.data.local.ChatDisplaySettings> =
    _chatDisplay.asStateFlow()

  fun setChatToolJsonVisible(visible: Boolean) {
    _chatDisplay.value = chatDisplayStore.update { it.copy(showToolJson = visible) }
  }

  val gitManager = GitRepositoryManager(fileSystem) { projectPath, args ->
    runGitCommand(projectPath, args)
  }

  /** Maps a host-side project path to the path visible inside the guest shell. */
  fun guestPathFor(hostPath: String): String {
    val prefix = projectsRoot.absolutePath.trimEnd('/')
    if (hostPath.startsWith(prefix)) {
      val rel = hostPath.removePrefix(prefix).trimStart('/')
      return if (rel.isEmpty()) "~/projects" else "~/projects/$rel"
    }
    return hostPath
  }

  /**
   * Executes a real `git` command inside the rootfs with the project folder
   * mounted as the workspace — the same environment the terminal uses.
   * Runs are serialized per project so background status refreshes can never
   * collide with long operations (pull/commit) over `.git/index.lock`.
   */
  private suspend fun runGitCommand(projectPath: String, args: String): com.agentisco.workspace.git.GitRunResult {
    val mutex = gitRunMutexes.getOrPut(projectPath) { Mutex() }
    return mutex.withLock { runGitCommandUnlocked(projectPath, args) }
  }

  private val gitRunMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

  private suspend fun runGitCommandUnlocked(projectPath: String, args: String): com.agentisco.workspace.git.GitRunResult {
    val out = StringBuilder()
    val runnerSession = TerminalSession(
      id = "git-runner-" + projectPath.hashCode(),
      name = "git",
      currentDir = projectPath
    )
    val code = terminalManager.executeCommand(
      runnerSession, args,
      { line -> out.appendLine(line.text) },
      projectDir = File(projectPath).takeIf { it.isDirectory }
    )
    val outputStr = out.toString()
    if (code != 0 && (outputStr.contains("Linux environment is not ready yet") || outputStr.contains("Failed to run command in Linux environment"))) {
      val hostRes = runHostGit(projectPath, args)
      if (hostRes != null) return hostRes
    }
    return com.agentisco.workspace.git.GitRunResult(code, outputStr)
  }

  private suspend fun runHostGit(projectPath: String, args: String): com.agentisco.workspace.git.GitRunResult? =
    withContext(Dispatchers.IO) {
      try {
        val dir = File(projectPath).takeIf { it.isDirectory } ?: return@withContext null
        val pb = ProcessBuilder("sh", "-c", "cd \"${dir.absolutePath}\" && $args")
        pb.redirectErrorStream(false)
        val env = pb.environment()
        env["GIT_TERMINAL_PROMPT"] = "0"
        val process = pb.start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        val combined = when {
          stderr.isBlank() -> stdout
          stdout.isBlank() -> stderr
          else -> "$stdout\n$stderr"
        }
        com.agentisco.workspace.git.GitRunResult(code, combined)
      } catch (e: Exception) {
        null
      }
    }

  // (Terminal stack initialized above — the projects root depends on it.)
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
  // The chain store keeps Gemini Interactions' server-side conversation ids, so
  // a session continues where it left off even after the app is restarted.
  private val llmService = com.agentisco.agent.llm.LlmService(
    chainStore = com.agentisco.agent.llm.GeminiChainStoreImpl(appContext)
  )
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

  // Current Projects. The registry (projects.json) is the source of truth for
  // each project's real root folder; legacy projects found on disk under the
  // base directory are migrated into it on first launch.
  val projectRegistry = com.agentisco.data.local.ProjectRegistryStore(context)
  private val placeholderProject = Project(
    id = "proj-none", name = "No project", branch = "main",
    lastActivity = "", description = "Create or import a project to start",
    path = "", isMissing = true
  )
  private val _projects = MutableStateFlow<List<Project>>(emptyList())
  val projects: StateFlow<List<Project>> = _projects.asStateFlow()

  private val _activeProject = MutableStateFlow<Project>(placeholderProject)
  val activeProject: StateFlow<Project> = _activeProject.asStateFlow()

  /** In-flight background measurement pass (cancelled/restarted per refresh). */
  private var metadataJob: Job? = null

  /** Rebuilds the project list from the registry, re-validating root folders. */
  private fun refreshProjectList() {
    // The workspace's own .agentisco.json is the authoritative project config;
    // the registry caches it for the project list.
    fun configFor(entry: com.agentisco.data.local.ProjectRegistryEntry): com.agentisco.workspace.filesystem.ProjectConfig? =
      com.agentisco.workspace.filesystem.ProjectFileSystem.readProjectConfig(File(entry.rootPath))

    val known = projectRegistry.all().map { entry ->
      val cfg = configFor(entry)
      Project(
        id = entry.id,
        name = entry.name,
        branch = "main",
        lastActivity = relativeActivity(entry.lastOpenedAt),
        changedFilesCount = 0,
        isDirty = false,
        description = entry.description,
        path = entry.rootPath,
        isMissing = !File(entry.rootPath).isDirectory,
        isImported = entry.imported,
        sourcePath = cfg?.sourcePath ?: entry.sourcePath,
        autoSyncToSource = cfg?.autoSync ?: entry.autoSync
      )
    }
    // Legacy projects living under the base dir but not yet registered.
    fileSystem.getProjects().forEach { legacy ->
      if (known.none { it.path == legacy.path }) {
        projectRegistry.upsert(
          com.agentisco.data.local.ProjectRegistryEntry(
            id = legacy.id, name = legacy.name, description = legacy.description,
            rootPath = legacy.path,
            createdAt = System.currentTimeMillis(),
            lastOpenedAt = System.currentTimeMillis(),
            imported = false
          )
        )
      }
    }
    _projects.value = projectRegistry.all().map { entry ->
      val cfg = configFor(entry)
      withCachedMetadata(
        Project(
          id = entry.id, name = entry.name, branch = "main",
          lastActivity = relativeActivity(entry.lastOpenedAt),
          description = entry.description, path = entry.rootPath,
          isMissing = !File(entry.rootPath).isDirectory,
          isImported = entry.imported,
          sourcePath = cfg?.sourcePath ?: entry.sourcePath,
          autoSyncToSource = cfg?.autoSync ?: entry.autoSync
        )
      )
    }
    enrichProjectMetadataAsync()
  }

  /**
   * Overlays the metadata measured for [project.path] on a previous refresh.
   * Reads only the in-memory cache, so it is safe on the main thread; unknown
   * values keep their "not measured yet" defaults.
   */
  private fun withCachedMetadata(project: Project): Project {
    val meta = ProjectMetadataScanner.cached(project.path) ?: return project
    return project.copy(
      sizeBytes = meta.sizeBytes,
      lastModified = meta.lastModified,
      iconPath = meta.iconPath,
      kind = meta.kind
    )
  }

  /**
   * Measures every project folder (recursive size, newest modification time,
   * icon file, project type) on IO and republishes the list once done. Results
   * are cached by [ProjectMetadataScanner], so a refresh that finds the folders
   * unchanged never re-walks them.
   */
  private fun enrichProjectMetadataAsync() {
    val targets = _projects.value.filter { it.path.isNotBlank() }
    if (targets.isEmpty()) return
    metadataJob?.cancel()
    metadataJob = repositoryScope.launch {
      val measured = withContext(Dispatchers.IO) {
        ProjectMetadataScanner.evictStale()
        targets.mapNotNull { project ->
          ProjectMetadataScanner.scan(project)?.let { project.id to it }
        }
      }
      if (measured.isEmpty()) return@launch
      val byId = measured.toMap()
      _projects.update { list ->
        list.map { project -> byId[project.id]?.let { project.withMetadata(it) } ?: project }
      }
      // The open project feeds the header (branch/size) — keep it in step.
      val active = _activeProject.value
      _projects.value.firstOrNull { it.id == active.id }?.let { _activeProject.value = it }
    }
  }

  private fun Project.withMetadata(meta: com.agentisco.workspace.filesystem.ProjectMetadata): Project =
    copy(
      sizeBytes = meta.sizeBytes,
      lastModified = meta.lastModified,
      iconPath = meta.iconPath,
      kind = meta.kind
    )

  private fun relativeActivity(timestamp: Long): String {
    if (timestamp <= 0) return "Active"
    val minutes = (System.currentTimeMillis() - timestamp) / 60000
    return when {
      minutes < 1 -> "Just now"
      minutes < 60 -> "${minutes}m ago"
      minutes < 60 * 24 -> "${minutes / 60}h ago"
      else -> "${minutes / (60 * 24)}d ago"
    }
  }

  // App Navigation Destination
  private val _currentDestination = MutableStateFlow(AppDestination.AGENT)
  val currentDestination: StateFlow<AppDestination> = _currentDestination.asStateFlow()

  // Files in Active Project. Only the root listing is materialized upfront;
  // subfolders are loaded lazily on expand ([loadChildren]) and scans run on
  // the IO dispatcher so huge repos can never freeze or OOM the UI.
  private val _projectFiles = MutableStateFlow<List<ProjectFile>>(emptyList())
  val projectFiles: StateFlow<List<ProjectFile>> = _projectFiles.asStateFlow()

  /** Children of already-loaded folders, keyed by project-relative path. */
  private val _dirChildren = MutableStateFlow<Map<String, List<ProjectFile>>>(emptyMap())
  val dirChildren: StateFlow<Map<String, List<ProjectFile>>> = _dirChildren.asStateFlow()

  private val _isFilesLoading = MutableStateFlow(false)
  val isFilesLoading: StateFlow<Boolean> = _isFilesLoading.asStateFlow()

  private val _nameSearchResults = MutableStateFlow<List<ProjectFile>>(emptyList())
  val nameSearchResults: StateFlow<List<ProjectFile>> = _nameSearchResults.asStateFlow()

  private val loadingDirs = mutableSetOf<String>()
  private var nameSearchJob: kotlinx.coroutines.Job? = null

  /** Loads one folder's children in the background (no-op when already cached). */
  fun loadChildren(relativePath: String) {
    val project = _activeProject.value
    if (project.path.isBlank()) return
    if (_dirChildren.value.containsKey(relativePath) || !loadingDirs.add(relativePath)) return
    repositoryScope.launch {
      try {
        val kids = withContext(Dispatchers.IO) {
          runCatching { fileSystem.listChildren(project, relativePath) }.getOrNull()
        }
        if (kids != null && _activeProject.value.id == project.id) {
          _dirChildren.update { it + (relativePath to kids) }
        }
      } finally {
        loadingDirs.remove(relativePath)
      }
    }
  }

  /** Debounced background filename search across the (ignore-pruned) tree. */
  fun searchFileNames(query: String) {
    nameSearchJob?.cancel()
    if (query.isBlank()) {
      _nameSearchResults.value = emptyList()
      return
    }
    val project = _activeProject.value
    if (project.path.isBlank()) return
    nameSearchJob = repositoryScope.launch {
      delay(250)
      val hits = withContext(Dispatchers.IO) {
        runCatching { fileSystem.findFilesByName(project, query) }.getOrDefault(emptyList())
      }
      if (_activeProject.value.id == project.id) _nameSearchResults.value = hits
    }
  }

  // Currently Active File in Editor
  private val _activeFile = MutableStateFlow<ProjectFile>(
    ProjectFile("", "", false)
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

  /** null = unknown/unchecked, false = project folder is not a git repository yet. */
  private val _isGitRepository = MutableStateFlow<Boolean?>(null)
  val isGitRepository: StateFlow<Boolean?> = _isGitRepository.asStateFlow()

  /** Rich repository status (branch, upstream, ahead/behind, operations, conflicts). */
  private val _repoStatus = MutableStateFlow(GitRepoStatus(isRepo = false))
  val repoStatus: StateFlow<GitRepoStatus> = _repoStatus.asStateFlow()

  private val _branches = MutableStateFlow<List<GitBranch>>(emptyList())
  val branches: StateFlow<List<GitBranch>> = _branches.asStateFlow()

  private val _stashes = MutableStateFlow<List<GitStash>>(emptyList())
  val stashes: StateFlow<List<GitStash>> = _stashes.asStateFlow()

  private val _remotes = MutableStateFlow<List<GitRemote>>(emptyList())
  val remotes: StateFlow<List<GitRemote>> = _remotes.asStateFlow()

  private val _tags = MutableStateFlow<List<String>>(emptyList())
  val tags: StateFlow<List<String>> = _tags.asStateFlow()

  private val _activeGitOperationText = MutableStateFlow<String?>(null)
  val activeGitOperationText: StateFlow<String?> = _activeGitOperationText.asStateFlow()

  private val _gitOperationFeedback = MutableStateFlow<String?>(null)
  val gitOperationFeedback: StateFlow<String?> = _gitOperationFeedback.asStateFlow()

  fun clearGitOperationFeedback() {
    _gitOperationFeedback.value = null
  }

  /** Real-time filesystem observer that watches project directory for changes. */
  private val fileWatcher = WorkspaceFileWatcher(repositoryScope) {
    refreshFiles(showLoadingIndicator = false)
    refreshDiffsAndGit()
  }

  /** Last git operation failure, surfaced in the Git tab for debugging. */
  private val _gitError = MutableStateFlow<String?>(null)
  val gitError: StateFlow<String?> = _gitError.asStateFlow()

  fun clearGitError() {
    _gitError.value = null
  }

  fun dismissGitError() {
    _gitError.value = null
  }

  /** One-click recovery from a stale `.git/index.lock`. */
  fun clearGitIndexLock() {
    val projectPath = _activeProject.value.path
    if (projectPath.isBlank()) {
      _gitError.value = "No project is open."
      return
    }
    repositoryScope.launch {
      val outcome = withContext(Dispatchers.IO) {
        val existed = gitManager.hasIndexLock(projectPath)
        val removed = gitManager.removeIndexLock(projectPath)
        Triple(removed, existed, gitManager.indexLockFile(projectPath)?.absolutePath)
      }
      val (removed, existed, lockPath) = outcome
      if (removed && existed) {
        _gitError.value = null
        _gitOperationFeedback.value = "Removed stale lock file ($lockPath). Retry the operation."
        refreshDiffsAndGit()
      } else if (removed && !existed) {
        _gitError.value = null
        _gitOperationFeedback.value = "No lock file present — it may have cleared itself. Retry the operation."
        refreshDiffsAndGit()
      } else {
        _gitError.value = "Could not remove the lock file${lockPath?.let { " at $it" } ?: ""}. " +
          "Another git process may still be running (check open terminals)."
      }
    }
  }

  private fun reportGitError(operation: String, result: com.agentisco.workspace.git.GitRunResult?) {
    _gitError.value = when {
      result == null -> null
      result.exitCode == 0 -> null
      else -> "$operation failed (exit ${result.exitCode}): ${result.output.take(200).ifBlank { "no output" }}"
    }
  }

  private val _commitMessage = MutableStateFlow("Fix chat message lifecycle and store recreation")
  val commitMessage: StateFlow<String> = _commitMessage.asStateFlow()

  private val _commitHistory = MutableStateFlow<List<GitCommit>>(emptyList())
  val commitHistory: StateFlow<List<GitCommit>> = _commitHistory.asStateFlow()

  // Terminal tabs (metadata only; the actual console lives in a real PTY
  // session per tab, created on demand when the environment is bootstrapped).
  private val _terminalSessions = MutableStateFlow<List<TerminalSession>>(emptyList())
  /** Terminal tabs per project: switching workspaces switches tab sets. */
  private val terminalTabsByProject = mutableMapOf<String, MutableList<TerminalSession>>()
  val terminalSessions: StateFlow<List<TerminalSession>> = _terminalSessions.asStateFlow()

  private val _activeTerminalSessionId = MutableStateFlow("term-1")
  val activeTerminalSessionId: StateFlow<String> = _activeTerminalSessionId.asStateFlow()

  /** Last opened project ID (persisted separately to survive app restarts). */
  private val _rememberedLastProjectId = MutableStateFlow<String?>(null)

  /** Loads the last opened project ID from disk (plain text, one line). */
  @Synchronized
  fun loadRememberedLastProjectId(): String? {
    val file = rememberedProjectConfigDir?.let { File(it, "last_project.txt") }
      ?.takeIf { it.isFile } ?: return null
    return runCatching { file.readText().trim().ifBlank { null } }.getOrNull()
  }

  @Synchronized
  fun saveRememberedLastProjectId(projectId: String) {
    val dir = rememberedProjectConfigDir
      ?: run { _rememberedLastProjectId.value = projectId; return }
    dir.mkdirs()
    runCatching { File(dir, "last_project.txt").writeText(projectId) }
    _rememberedLastProjectId.value = projectId
  }

  // Same location ProjectRegistryStore uses for projects.json; the last-opened
  // project pointer lives next to it without touching that class's internals.
  private val rememberedProjectConfigDir: File? = context?.getDir("agentisco", Context.MODE_PRIVATE)

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

  /** Model used for background tasks: the user's default, else the selected one. */
  private val _defaultTaskModelId = MutableStateFlow(providerStore?.getDefaultTaskModelId())
  val defaultTaskModelId: StateFlow<String?> = _defaultTaskModelId.asStateFlow()


  init {
    repositoryScope.launch {
      migrateLegacyProjects()
      refreshProjectList()
      // Prioritize the last opened project if it exists and is still valid
      val rememberedProjectId = loadRememberedLastProjectId()
      val lastProject = rememberedProjectId?.let {
        _projects.value.firstOrNull { p -> p.id == rememberedProjectId }
      }
      val activeProjectToLoad = when {
        // Use remembered project if it's valid (exists and not missing)
        lastProject != null && !lastProject.isMissing -> lastProject
        // Otherwise use the first available project
        else -> _projects.value.firstOrNull { !it.isMissing }
          ?: _projects.value.firstOrNull()
          ?: placeholderProject
      }
      _activeProject.value = activeProjectToLoad
      saveRememberedLastProjectId(activeProjectToLoad.id)
      loadActiveProjectState(activeProjectToLoad)
    }
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
      _defaultTaskModelId.value = ensureDefaultTaskModel()
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
    if (project.path.isBlank()) {
      _projectFiles.value = emptyList()
      _dirChildren.value = emptyMap()
      _activeFile.value = ProjectFile("", "", false)
      _editorContent.value = ""
      _isEditorDirty.value = false
      _fileDiffs.value = emptyList()
      return
    }

    _dirChildren.value = emptyMap()
    loadingDirs.clear()
    _nameSearchResults.value = emptyList()
    repositoryScope.launch {
      _isFilesLoading.value = true
      // Root listing + bounded scan for the editor's initial file, off main.
      val snapshot = withContext(Dispatchers.IO) {
        runCatching {
          val root = fileSystem.listChildren(project, "") ?: emptyList()
          val shallow = fileSystem.getFileTree(project, maxDepth = 4)
          fun findFirstFile(list: List<ProjectFile>): ProjectFile? {
            for (f in list) {
              if (!f.isDirectory) return f
              findFirstFile(f.children)?.let { return it }
            }
            return null
          }
          val firstFile = findFirstFile(shallow) ?: ProjectFile("README.md", "README.md", false)
          Triple(root, firstFile, fileSystem.readFile(project, firstFile.path))
        }
      }.getOrNull()
      _isFilesLoading.value = false
      if (snapshot == null || _activeProject.value.id != project.id) return@launch
      val (root, firstFile, content) = snapshot
      _projectFiles.value = root
      _activeFile.value = firstFile.copy(content = content)
      _editorContent.value = content
      _isEditorDirty.value = false
    }

    // Terminal tabs belong to the project: open its tab set (with the real
    // project root as the working directory) and never inherit another
    // project's tabs.
    val tabs = terminalTabsByProject.getOrPut(project.id) {
      mutableListOf(
        TerminalSession(
          id = "term-${System.currentTimeMillis()}",
          name = "main",
          currentDir = guestPathFor(project.path),
          projectId = project.id
        )
      )
    }
    _terminalSessions.value = tabs
    if (_terminalSessions.value.none { it.id == _activeTerminalSessionId.value }) {
      _activeTerminalSessionId.value = tabs.first().id
    }

    fileWatcher.setRoot(File(project.path).takeIf { it.isDirectory })
    refreshDiffsAndGit()
  }

  fun refreshFiles(showLoadingIndicator: Boolean = false) {
    val project = _activeProject.value
    if (project.path.isBlank()) {
      _projectFiles.value = emptyList()
      _dirChildren.value = emptyMap()
      refreshDiffsAndGit()
      return
    }
    repositoryScope.launch {
      if (showLoadingIndicator || _projectFiles.value.isEmpty()) {
        _isFilesLoading.value = true
      }
      // Re-scan the root plus every folder the UI already has open.
      val snapshot = withContext(Dispatchers.IO) {
        runCatching {
          val dirs = (setOf("") + _dirChildren.value.keys).mapNotNull { path ->
            fileSystem.listChildren(project, path)?.let { path to it }
          }.toMap()
          (dirs[""] ?: emptyList()) to dirs
        }
      }.getOrNull()
      _isFilesLoading.value = false
      if (snapshot == null || _activeProject.value.id != project.id) return@launch
      _projectFiles.value = snapshot.first
      _dirChildren.value = snapshot.second
    }
  }

  private var gitRefreshJob: Job? = null
  private var gitHistoryJob: Job? = null
  private var gitHistoryProjectPath: String = ""
  private var lastHistoryLoadAt = 0L

  /** Refreshes diffs/staging/history/status from real git, asynchronously. */
  fun refreshDiffsAndGit() {
    val project = _activeProject.value
    if (project.path.isBlank()) {
      _fileDiffs.value = emptyList()
      _commitHistory.value = emptyList()
      _stagedFiles.value = emptySet()
      _isGitRepository.value = null
      _repoStatus.value = GitRepoStatus(isRepo = false)
      _branches.value = emptyList()
      _stashes.value = emptyList()
      _remotes.value = emptyList()
      _tags.value = emptyList()
      return
    }
    // Commit history runs as its own job: file-watcher-triggered refreshes
    // (every ~350ms during git ops) must not cancel it before it gets a turn.
    if (gitHistoryProjectPath != project.path) {
      gitHistoryJob?.cancel()
      gitHistoryProjectPath = project.path
      _commitHistory.value = emptyList()
      lastHistoryLoadAt = 0L
    }
    val historyRecentlyLoaded =
      System.currentTimeMillis() - lastHistoryLoadAt < HISTORY_MIN_RELOAD_INTERVAL_MS
    if (gitHistoryJob?.isActive != true && !historyRecentlyLoaded) {
      gitHistoryJob = repositoryScope.launch { loadCommitHistory(project) }
    }
    gitRefreshJob?.cancel()
    gitRefreshJob = repositoryScope.launch {
      try {
        val isRepo = gitManager.isGitRepository(project)
        _isGitRepository.value = isRepo
        if (!isRepo) {
          _fileDiffs.value = emptyList()
          _stagedFiles.value = emptySet()
          _repoStatus.value = GitRepoStatus(isRepo = false)
          _branches.value = emptyList()
          _stashes.value = emptyList()
          _remotes.value = emptyList()
          _tags.value = emptyList()
          _activeProject.update { it.copy(changedFilesCount = 0, isDirty = false) }
          return@launch
        }
        val status = gitManager.getRepoStatus(project)
        if (_activeProject.value.path != project.path) return@launch
        _repoStatus.value = status
        _branches.value = gitManager.getBranches(project)
        _stashes.value = gitManager.getStashes(project)
        _remotes.value = status.remotes
        _tags.value = status.tags
        _fileDiffs.value = gitManager.computeAllDiffs(project)
        _stagedFiles.value = status.stagedFiles.map { it.path }.toSet()
        _activeProject.update {
          it.copy(
            branch = status.currentBranch,
            changedFilesCount = status.totalChangedFiles,
            isDirty = !status.isClean
          )
        }
      } catch (e: Exception) {
        if (e !is kotlinx.coroutines.CancellationException) {
          android.util.Log.e("ScoOS-Git", "git refresh failed", e)
        }
      }
    }
  }

  /** Loads the first page of commit history with retries; keeps the previous list on failure. */
  private suspend fun loadCommitHistory(project: Project) {
    try {
      var attempts = 0
      while (attempts < 4) {
        val history = gitManager.getCommitHistory(project, limit = 30)
        if (_activeProject.value.path != project.path) return
        if (history != null) {
          _commitHistory.value = history
          lastHistoryLoadAt = System.currentTimeMillis()
          return
        }
        attempts++
        delay(1500)
      }
      lastHistoryLoadAt = System.currentTimeMillis()
      android.util.Log.w("ScoOS-Git", "commit history unavailable after $attempts attempts")
    } catch (e: Exception) {
      lastHistoryLoadAt = System.currentTimeMillis()
      if (e !is kotlinx.coroutines.CancellationException) {
        android.util.Log.e("ScoOS-Git", "commit history load failed", e)
      }
    }
  }

  // Navigation
  fun navigateTo(destination: AppDestination) {
    _currentDestination.value = destination
    if (destination == AppDestination.DIFF || destination == AppDestination.GIT) {
      refreshDiffsAndGit()
    }
  }

  fun selectProject(project: Project) {
    projectRegistry.byPath(project.path)?.let { projectRegistry.touch(it.id) }
    _activeProject.value = project
    // Persist the last opened project
    _rememberedLastProjectId.value = project.id
    saveRememberedLastProjectId(project.id)
    fileWatcher.setRoot(File(project.path).takeIf { it.isDirectory })
    if (File(project.path, "README.md").exists()) {
      _activeFile.value = ProjectFile("README.md", "README.md", false)
    }
    loadActiveProjectState(project)
  }

  /**
   * Creates a new project folder. When [rootPath] is blank the project lives
   * under the default projects root (`~/projects/<name>`).
   */
  fun createProject(name: String, description: String, rootPath: String? = null): Project? {
    return try {
      val root = rootPath?.takeIf { it.isNotBlank() }?.let { expandProjectPath(it) }
      val newProj = fileSystem.createProject(name, description, root)
      projectRegistry.upsert(
        com.agentisco.data.local.ProjectRegistryEntry(
          id = newProj.id, name = newProj.name, description = newProj.description,
          rootPath = newProj.path,
          createdAt = System.currentTimeMillis(),
          lastOpenedAt = System.currentTimeMillis(),
          imported = false
        )
      )
      refreshProjectList()
      selectProject(newProj)
      newProj
    } catch (e: Exception) {
      android.util.Log.e("ScoOS-Projects", "Failed to create project", e)
      _agentStatusText.value = "Could not create project: ${e.message}"
      null
    }
  }

  /**
   * Imports an existing folder by COPYING its contents into the app's Linux
   * workspace (`~/projects/<name>`). The workspace gets a real POSIX
   * filesystem, so git, builds, package managers and file locks all work
   * reliably. The original folder is remembered as [Project.sourcePath] and
   * changes can be mirrored back (manually or automatically — see
   * [syncProjectToSource] / [setProjectAutoSync]).
   *
   * Ignored folders ([ProjectFileSystem.ignoredDirs]) are skipped and the
   * copy runs on an IO thread, so importing a huge repo never blocks the UI.
   */
  suspend fun importProject(rootPath: String, displayName: String? = null): Project? {
    val source = expandProjectPath(rootPath)
    if (!source.isDirectory || !source.canRead()) {
      _agentStatusText.value = "Folder not found or not readable: ${source.absolutePath}"
      return null
    }
    val name = (displayName ?: source.name).ifBlank { source.name }
    return try {
      val workspace = fileSystem.suggestDefaultRoot(name)
      _agentStatusText.value = "Importing \"$name\" — copying folder (dependency/build folders skipped)…"
      val copied = withContext(Dispatchers.IO) { fileSystem.copyFolder(source, workspace) }
      val imported = fileSystem.importProject(workspace, name, sourcePath = source.absolutePath)
      projectRegistry.upsert(
        com.agentisco.data.local.ProjectRegistryEntry(
          id = imported.id, name = imported.name, description = imported.description,
          rootPath = imported.path,
          createdAt = System.currentTimeMillis(),
          lastOpenedAt = System.currentTimeMillis(),
          imported = true,
          sourcePath = source.absolutePath,
          autoSync = true
        )
      )
      refreshProjectList()
      selectProject(imported)
      _agentStatusText.value = "Imported \"$name\" — $copied file(s) copied into the workspace"
      imported
    } catch (e: Exception) {
      android.util.Log.e("ScoOS-Projects", "Failed to import project", e)
      _agentStatusText.value = "Could not import folder: ${e.message}"
      null
    }
  }

  /**
   * Mirrors the workspace to the project's original folder (one-way,
   * workspace wins): copies new/updated files and removes deleted ones.
   */
  fun syncProjectToSource(project: Project = _activeProject.value) {
    if (project.sourcePath.isBlank()) return
    repositoryScope.launch {
      try {
        val (copied, removed) = withContext(Dispatchers.IO) {
          fileSystem.mirrorFolder(File(project.path), File(project.sourcePath))
        }
        _agentStatusText.value = "Synced to ${project.sourcePath} — $copied file(s) updated, $removed removed"
      } catch (e: Exception) {
        android.util.Log.e("ScoOS-Sync", "sync failed", e)
        _agentStatusText.value = "Sync to original folder failed: ${e.message}"
      }
    }
  }

  /** Mirrors automatically when the project has a source folder and it's enabled. */
  private fun maybeAutoSync(project: Project) {
    if (project.sourcePath.isNotBlank() && project.autoSyncToSource) {
      syncProjectToSource(project)
    }
  }

  fun setProjectAutoSync(projectId: String, enabled: Boolean) {
    projectRegistry.setAutoSync(projectId, enabled)
    // Persist in the workspace's .agentisco.json as well (it's authoritative).
    val entry = projectRegistry.all().firstOrNull { it.id == projectId }
    if (entry != null) {
      val dir = File(entry.rootPath)
      val cfg = com.agentisco.workspace.filesystem.ProjectFileSystem.readProjectConfig(dir)
      if (cfg != null) {
        com.agentisco.workspace.filesystem.ProjectFileSystem.writeProjectConfig(
          dir, cfg.copy(autoSync = enabled)
        )
      }
    }
    refreshProjectList()
    _activeProject.value = _projects.value.firstOrNull { it.id == projectId } ?: _activeProject.value
  }

  /**
   * Imports a .zip archive as a project: extracts to a temp dir, picks the
   * archive root (the single top-level folder if there is one, otherwise the
   * extraction root), then copies it into the app's Linux workspace like a
   * normal import. No sync source — the original is a zip file.
   */
  suspend fun importZipProject(uri: android.net.Uri, displayName: String? = null): Project? {
    val context = appContext
    if (context == null) {
      _agentStatusText.value = "Zip import unavailable in this environment."
      return null
    }
    var tempDir: File? = null
    val imported = try {
      tempDir = File(context.cacheDir, "zip-import-" + System.currentTimeMillis())
      tempDir.mkdirs()
      withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
        if (input == null) {
          _agentStatusText.value = "Could not read the selected zip file."
          null
        } else {
          var extracted = 0
          val canonicalRoot = tempDir.canonicalPath + File.separator
          input.use { stream ->
            java.util.zip.ZipInputStream(stream.buffered()).use { zis ->
              var entry = zis.nextEntry
              while (entry != null) {
                val outFile = resolveSafeZipTarget(tempDir, canonicalRoot, entry.name)
                if (outFile != null) {
                  if (entry.isDirectory) {
                    outFile.mkdirs()
                  } else {
                    outFile.parentFile?.mkdirs()
                    zis.copyTo(outFile.outputStream())
                    extracted++
                  }
                }
                zis.closeEntry()
                entry = zis.nextEntry
              }
            }
          }
          if (extracted == 0) {
            _agentStatusText.value = "The archive is empty or could not be read."
            null
          } else {
            // Root selection: a single top-level directory becomes the project root,
            // otherwise use the extraction root directly (no 2-level nesting).
            val tops = tempDir.listFiles().orEmpty()
            val root = if (tops.size == 1 && tops[0].isDirectory) tops[0] else tempDir
            val name = (displayName ?: root.name).ifBlank { "zip-project" }
            val workspace = fileSystem.suggestDefaultRoot(name)
            fileSystem.copyFolder(root, workspace)
            fileSystem.importProject(workspace, name, sourcePath = "")
          }
        }
      }
    } catch (e: Exception) {
      android.util.Log.e("ScoOS-Projects", "Failed to import zip", e)
      _agentStatusText.value = "Could not import zip: ${e.message}"
      null
    } finally {
      tempDir?.deleteRecursively()
    }
    if (imported == null) return null
    projectRegistry.upsert(
      com.agentisco.data.local.ProjectRegistryEntry(
        id = imported.id, name = imported.name, description = imported.description,
        rootPath = imported.path,
        createdAt = System.currentTimeMillis(),
        lastOpenedAt = System.currentTimeMillis(),
        imported = true,
        sourcePath = ""
      )
    )
    refreshProjectList()
    selectProject(imported)
    return imported
  }

  /** Zip-slip protection: refuses paths escaping the extraction directory. */
  private fun resolveSafeZipTarget(extractDir: File, canonicalRoot: String, entryName: String): File? {
    val cleaned = entryName.replace("\\", "/")
    if (cleaned.startsWith("/") || cleaned.contains("..")) return null
    val target = File(extractDir, cleaned)
    val canonical = target.canonicalPath
    return if (canonical.startsWith(canonicalRoot) || canonical == canonicalRoot.trimEnd('/')) target else null
  }

  /**
   * Removes a project ENTIRELY: its workspace folder on disk, its chat
   * sessions, and its registry entry.
   *
   * Deleting the folder matters — [refreshProjectList] re-registers any
   * folder still present under the projects root, so unregistering alone
   * would make the project reappear instantly.
   */
  fun removeProject(project: Project) {
    projectRegistry.remove(project.id)
    chatStore.deleteSessionsForProject(project.path)
    repositoryScope.launch {
      // Deleting a big project folder takes real time — never on the main thread.
      withContext(Dispatchers.IO) { fileSystem.deleteProjectFolder(project) }
      refreshProjectList()
      if (_activeProject.value.id == project.id) {
        _activeProject.value = _projects.value.firstOrNull { !it.isMissing }
          ?: _projects.value.firstOrNull()
          ?: placeholderProject
        loadActiveProjectState(_activeProject.value)
      }
    }
  }

  /**
   * One-time migration: projects stored under the old app-private directory
   * (filesDir/sco_projects) are MOVED into the rootfs home (/root/projects),
   * so terminal paths like ~/projects/<name> work. Chat sessions are re-keyed
   * to the new location.
   */
  private suspend fun migrateLegacyProjects() {
    val legacyRoot = appContext?.filesDir?.let { File(it, "sco_projects") } ?: return
    if (!legacyRoot.isDirectory) return
    val legacyPrefix = legacyRoot.absolutePath.trimEnd('/')
    projectRegistry.all()
      .filter { it.rootPath.trimEnd('/').startsWith(legacyPrefix) }
      .forEach { entry ->
        try {
          val target = fileSystem.suggestDefaultRoot(entry.name)
          withContext(Dispatchers.IO) { fileSystem.copyFolder(File(entry.rootPath), target) }
          val oldPath = entry.rootPath
          projectRegistry.upsert(entry.copy(rootPath = target.absolutePath))
          chatStore.remapProjectSessionsBlocking(oldPath, target.absolutePath)
        } catch (e: Exception) {
          android.util.Log.e("ScoOS-Projects", "Failed to migrate project ${entry.name}", e)
        }
      }
  }

  /** Re-validates root folders (moved/deleted projects) and refreshes recency. */
  fun refreshProjects() {
    refreshProjectList()
    _activeProject.value = _projects.value.firstOrNull { it.id == _activeProject.value.id }
      ?: _activeProject.value
  }

  /** Resolves user-entered locations: `~` maps to the projects root directory. */
  private fun expandProjectPath(raw: String): File {
    val trimmed = raw.trim()
    val resolved = when {
      trimmed == "~" || trimmed.startsWith("~/") ->
        File(fileSystem.defaultProjectsRoot(), trimmed.removePrefix("~").removePrefix("/"))
      else -> File(trimmed)
    }
    return File(resolved.absolutePath)
  }

  fun openFile(file: ProjectFile) {
    if (!file.isDirectory) {
      val diskContent = fileSystem.readFile(_activeProject.value, file.path)
      _activeFile.value = file.copy(content = diskContent)
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
    if (file.path.isEmpty()) return
    // Binary viewer files are never round-tripped through the text editor —
    // writing the (deliberately empty) editor buffer would destroy them.
    if (com.agentisco.editor.model.FileViewer.mustNotDecodeAsText(file.name)) return
    val text = _editorContent.value
    fileSystem.writeFile(_activeProject.value, file.path, text)
    _activeFile.value = file.copy(content = text, sizeBytes = text.length.toLong())
    _isEditorDirty.value = false
    refreshFiles()
    maybeAutoSync(_activeProject.value)
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

  fun duplicateFile(relativePath: String): Boolean {
    val project = _activeProject.value
    val ext = relativePath.substringAfterLast(".", "")
    val base = if (ext.isNotEmpty()) relativePath.substringBeforeLast(".") else relativePath
    val newPath = if (ext.isNotEmpty()) "${base}_copy.$ext" else "${base}_copy"
    val success = fileSystem.copyFile(project, relativePath, newPath)
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

  /** Stages or unstages one file — the UI states the intent explicitly, so a
   *  stale staged-files snapshot can never swap add/unstage. */
  fun setFileStaged(filePath: String, stage: Boolean) {
    repositoryScope.launch {
      val project = _activeProject.value
      val result = if (stage) {
        runGitCommand(project.path, "git add -- \"" + filePath + "\"")
      } else {
        runGitCommand(project.path, "git restore --staged -- \"" + filePath + "\"")
      }
      reportGitError(if (stage) "Stage" else "Unstage", result.takeIf { it.exitCode != 0 })
      refreshDiffsAndGit()
    }
  }

  /** Kept for compatibility; infers current state from the git index snapshot. */
  fun toggleFileStaged(filePath: String) {
    setFileStaged(filePath, !_stagedFiles.value.contains(filePath))
  }

  fun stageAll() {
    repositoryScope.launch {
      val result = runGitCommand(_activeProject.value.path, "git add -A")
      reportGitError("Stage all", result.takeIf { it.exitCode != 0 })
      refreshDiffsAndGit()
    }
  }

  fun unstageAll() {
    repositoryScope.launch {
      val result = runGitCommand(_activeProject.value.path, "git reset")
      reportGitError("Unstage all", result.takeIf { it.exitCode != 0 })
      refreshDiffsAndGit()
    }
  }

  fun updateCommitMessage(msg: String) {
    _commitMessage.value = msg
  }

  /** State of the AI commit-message generation, surfaced in the Git tab. */
  sealed class CommitGenState {
    data object Idle : CommitGenState()
    data object Generating : CommitGenState()
    data class Done(val message: String) : CommitGenState()
    data class Failed(val error: String) : CommitGenState()
  }

  private val _commitGenState = MutableStateFlow<CommitGenState>(CommitGenState.Idle)
  val commitGenState: StateFlow<CommitGenState> = _commitGenState.asStateFlow()

  /**
   * Generates a concise conventional-commit message from the complete staged
   * diff (`git diff --cached` — staged changes only, unstaged excluded) using
   * the currently configured provider/model.
   */
  fun generateCommitMessageWithAgent() {
    repositoryScope.launch {
      val project = _activeProject.value
      _commitMessage.value = ""
      _commitGenState.value = CommitGenState.Generating
      try {
        if (!gitManager.isGitRepository(project)) {
          _commitGenState.value = CommitGenState.Failed("Not a git repository — initialize it first.")
          return@launch
        }
        val staged = gitManager.stagedDiff(project)
        if (staged.isBlank()) {
          _commitGenState.value = CommitGenState.Failed("Nothing staged — stage changes first, then generate.")
          return@launch
        }
        val model = _selectedModel.value
        if (model == null) {
          _commitGenState.value = CommitGenState.Failed("No model configured — add a provider/model in Settings first.")
          return@launch
        }
        val generated = requestLlmText(
          system = "You are a commit message writer. You output exactly one git commit message and NOTHING else. " +
            "No greetings, no reasoning, no analysis, no alternatives, no per-file breakdown, no markdown, no quotes, no code fences.",
          user = buildString {
            appendLine("Write ONE commit message summarizing ALL staged changes together as a single unit of work.")
            appendLine("Hard rules:")
            appendLine("- Output ONLY the commit message. Your entire response is the commit message itself.")
            appendLine("- Exactly one message for the whole diff — NEVER one message per file, never multiple messages.")
            appendLine("- First line: type prefix (feat|fix|chore|docs|refactor|test|perf|build|ci|style), optional scope, colon, imperative summary (max 72 chars).")
            appendLine("- If the change is non-trivial, one blank line, then a short bullet list of the key changes.")
            appendLine("- Do not write anything before or after the message. No 'Let me', 'Here is', 'Sure', commentary, or reasoning.")
            appendLine()
            append("Staged diff:\n" + staged.take(12000))
          },
          maxTokens = 300,
          disableReasoning = true
        )
        val sanitized = generated?.let { sanitizeCommitMessage(it) }
        if (sanitized.isNullOrBlank()) {
          _commitGenState.value = CommitGenState.Failed(
            "The model returned an empty response. Try again or pick a different default model in Settings."
          )
        } else {
          _commitMessage.value = sanitized
          _commitGenState.value = CommitGenState.Done(sanitized)
        }
      } catch (e: Exception) {
        android.util.Log.e("ScoOS-Git", "commit message generation failed", e)
        _commitGenState.value = CommitGenState.Failed("Generation failed: ${e.message ?: e.javaClass.simpleName}")
      }
    }
  }

  fun dismissCommitGenState() {
    _commitGenState.value = CommitGenState.Idle
  }

  private val commitPrefixRegex = Regex(
    "^(feat|fix|chore|docs|refactor|test|perf|build|ci|style|revert)(\\([^)]*\\))?\\s?:\\s?.+",
    RegexOption.IGNORE_CASE
  )

  /**
   * Extracts the actual commit message from model output, tolerating chatter,
   * code fences, and per-file message lists: finds the first conventional
   * commit line, keeps its bullet body, and drops everything else.
   */
  private fun sanitizeCommitMessage(raw: String): String? {
    val lines = raw
      .replace("```", "")
      .lines()
      .map { it.trimEnd() }
    val start = lines.indexOfFirst { commitPrefixRegex.containsMatchIn(it.trim()) }
    if (start < 0) return raw.trim().lineSequence().firstOrNull()?.take(200)?.ifBlank { null }
    val body = mutableListOf(lines[start].trim())
    var i = start + 1
    var sawBody = false
    while (i < lines.size) {
      val line = lines[i].trim()
      if (line.isBlank()) {
        // Allow one blank line between subject and bullets; stop after the body ends.
        if (sawBody) break
        i++
        continue
      }
      if (commitPrefixRegex.containsMatchIn(line)) break // another message = stop
      if (line.startsWith("-") || line.startsWith("*") || line.startsWith("•")) {
        body.add(line)
        sawBody = true
      } else if (sawBody && !line.startsWith("#")) {
        body.add(line)
      } else if (!sawBody && body.size == 1) {
        break // chatter after the subject line
      }
      i++
    }
    return body.joinToString("\n").take(600).ifBlank { null }
  }

  /**
   * Guarantees a usable default task model: the stored one if still valid,
   * otherwise the first model in the catalog (persisted so there is always
   * exactly one default).
   */
  private fun ensureDefaultTaskModel(): String? {
    val models = _aiModels.value
    val current = _defaultTaskModelId.value
    if (current != null && models.any { it.id == current }) return current
    val fallback = models.firstOrNull()?.id
    if (fallback != null && fallback != current) {
      providerStore?.setDefaultTaskModelId(fallback)
      _defaultTaskModelId.value = fallback
    }
    return fallback
  }

  private fun simpleCommitFallback(diff: String): String {
    val files = Regex("^diff --git a/(\\S+)").findAll(diff).toList()
    val scope = files.maxOfOrNull { it.groupValues[1].substringAfterLast('/') } ?: "project"
    return "chore(" + scope + "): update " + files.size + " file" + if (files.size == 1) "" else "s"
  }

  fun setDefaultTaskModel(modelId: String?) {
    providerStore?.setDefaultTaskModelId(modelId)
    _defaultTaskModelId.value = modelId
  }

  private fun resolveTaskModel(): AIModel? {
    // The default task model is only usable when its provider is actually
    // configured with a key — otherwise background generation (session titles,
    // commit messages) would fail silently while the selected model works.
    _defaultTaskModelId.value?.let { id ->
      _aiModels.value.firstOrNull { it.id == id }?.let { model ->
        if (resolveProviderForModel(model) != null) return model
      }
    }
    return _selectedModel.value
  }

  /**
   * One-shot LLM text generation. Returns null only when no model is
   * configured; otherwise throws the real provider/network error so callers
   * can surface exactly what went wrong.
   */
  private suspend fun requestLlmText(system: String, user: String, maxTokens: Int, disableReasoning: Boolean = false): String? {
    ensureDefaultTaskModel()
    val model = resolveTaskModel() ?: return null
    val connection = resolveProviderForModel(model)
      ?: throw IllegalStateException("Provider for model \"${model.displayName}\" has no API key configured.")
    val (provider, apiKey) = connection
    val collected = StringBuilder()
    llmService.streamChat(
      provider = provider, model = model, apiKey = apiKey,
      request = com.agentisco.agent.llm.LlmRequest(
        messages = listOf(
          com.agentisco.agent.llm.LlmMessage(com.agentisco.agent.llm.LlmRole.SYSTEM, system),
          com.agentisco.agent.llm.LlmMessage(com.agentisco.agent.llm.LlmRole.USER, user)
        ),
        maxOutputTokens = maxTokens,
        disableReasoning = disableReasoning
      )
    ) { event ->
      if (event is com.agentisco.agent.llm.LlmStreamEvent.Token) collected.append(event.text)
    }
    return collected.toString().trim().ifBlank { null }
  }

  /** AI-generated short session title (max 6 words) for a new conversation. */
  suspend fun requestSessionTitle(prompt: String): String? {
    val title = try {
      requestLlmText(
        system = "You generate very short chat session titles. Reply with only the title text.",
        user = "Create a title of at most 6 words (no quotes, no ending punctuation) for a coding-agent conversation that starts with this request: \"" + prompt.take(400) + "\"",
        maxTokens = 32
      )
    } catch (e: Exception) {
      android.util.Log.w("ScoOS-Sessions", "title generation failed: ${e.message}")
      null
    } ?: return null
    return title.split(Regex("\\s+")).take(6).joinToString(" ").take(60).ifBlank { null }
  }

  /** Creates a real git repository in the project folder (VS Code-style init). */
  fun initGitRepository() {
    repositoryScope.launch {
      val ok = gitManager.initRepository(_activeProject.value)
      _gitError.value = if (ok) null else "git init failed — is the Linux environment bootstrapped (open the Terminal tab once)?"
      refreshDiffsAndGit()
    }
  }

  fun commitStagedChanges(customMessage: String? = null, amend: Boolean = false) {
    val message = customMessage ?: _commitMessage.value
    if (message.isBlank() && !amend) {
      _gitError.value = "Commit message cannot be empty."
      return
    }
    repositoryScope.launch {
      _activeGitOperationText.value = if (amend) "Amending commit..." else "Committing..."
      try {
        val result = gitManager.commit(_activeProject.value, _stagedFiles.value, message, amend = amend)
        val commit = result.commit
        if (commit != null) {
          _gitError.value = null
          _gitOperationFeedback.value = if (amend) "Amended commit: ${commit.hash}" else "Committed: ${commit.hash}"
          _stagedFiles.value = emptySet()
          _commitMessage.value = ""
          refreshDiffsAndGit()
          maybeAutoSync(_activeProject.value)
        } else {
          _gitError.value = "Commit failed: " + (result.errorOutput ?: "is the folder a git repository and are changes staged?")
        }
      } catch (e: Exception) {
        android.util.Log.e("ScoOS-Git", "commit failed", e)
        _gitError.value = "Commit failed: " + (e.message ?: e.javaClass.simpleName)
      } finally {
        _activeGitOperationText.value = null
      }
    }
  }

  fun commitAndPush(customMessage: String? = null) {
    val message = customMessage ?: _commitMessage.value
    if (message.isBlank()) {
      _gitError.value = "Commit message cannot be empty."
      return
    }
    repositoryScope.launch {
      _activeGitOperationText.value = "Committing & Pushing..."
      try {
        val result = gitManager.commit(_activeProject.value, _stagedFiles.value, message)
        val commit = result.commit
        if (commit != null) {
          _stagedFiles.value = emptySet()
          _commitMessage.value = ""
          _gitOperationFeedback.value = "Committed: ${commit.hash}. Pushing..."
          val pushRes = gitManager.push(_activeProject.value)
          if (pushRes.success) {
            _gitError.value = null
            _gitOperationFeedback.value = "Committed and pushed successfully!"
          } else {
            _gitError.value = "Committed, but push failed: ${pushRes.output.ifBlank { "Unknown error" }}"
          }
          refreshDiffsAndGit()
          maybeAutoSync(_activeProject.value)
        } else {
          _gitError.value = "Commit failed: " + (result.errorOutput ?: "check staged changes.")
        }
      } catch (e: Exception) {
        _gitError.value = "Commit and push failed: ${e.message}"
      } finally {
        _activeGitOperationText.value = null
      }
    }
  }

  fun undoLastCommit(mode: UndoCommitMode = UndoCommitMode.KEEP_STAGED) {
    repositoryScope.launch {
      _activeGitOperationText.value = "Undoing last commit..."
      val res = gitManager.undoLastCommit(_activeProject.value, mode)
      if (res.success) {
        _gitOperationFeedback.value = "Last commit undone successfully"
        _gitError.value = null
      } else {
        _gitError.value = "Undo commit failed: ${res.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit()
    }
  }

  fun loadMoreCommitHistory() {
    val current = _commitHistory.value
    repositoryScope.launch {
      val more = gitManager.getCommitHistory(_activeProject.value, limit = 30, offset = current.size)
      if (!more.isNullOrEmpty()) {
        _commitHistory.value = current + more
      }
    }
  }

  suspend fun getCommitDetail(hash: String): GitCommitDetail? {
    return gitManager.getCommitDetail(_activeProject.value, hash)
  }

  suspend fun getCommitDiff(hash: String): String {
    return gitManager.getCommitDetail(_activeProject.value, hash)?.diff ?: ""
  }

  fun revertCommit(hash: String) {
    repositoryScope.launch {
      _activeGitOperationText.value = "Reverting commit $hash..."
      val res = gitManager.revertCommit(_activeProject.value, hash)
      if (res.success) {
        _gitOperationFeedback.value = "Commit $hash reverted"
        _gitError.value = null
      } else {
        _gitError.value = "Revert failed: ${res.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit()
    }
  }

  fun cherryPickCommit(hash: String) {
    repositoryScope.launch {
      _activeGitOperationText.value = "Cherry-picking $hash..."
      val res = gitManager.cherryPick(_activeProject.value, hash)
      if (res.success) {
        _gitOperationFeedback.value = "Cherry-picked $hash successfully"
        _gitError.value = null
      } else {
        _gitError.value = "Cherry-pick failed: ${res.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit()
    }
  }

  fun resetToCommit(hash: String, mode: ResetMode) {
    repositoryScope.launch {
      _activeGitOperationText.value = "Resetting to $hash..."
      val res = gitManager.resetToCommit(_activeProject.value, hash, mode)
      if (res.success) {
        _gitOperationFeedback.value = "Reset to $hash completed"
        _gitError.value = null
      } else {
        _gitError.value = "Reset failed: ${res.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit()
    }
  }

  fun checkoutBranch(name: String) {
    repositoryScope.launch {
      _activeGitOperationText.value = "Switching to branch $name..."
      val res = gitManager.checkoutBranch(_activeProject.value, name)
      if (res.success) {
        _gitOperationFeedback.value = "Switched to branch $name"
        _gitError.value = null
      } else {
        _gitError.value = "Checkout failed: ${res.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit()
      refreshFiles()
    }
  }

  fun createBranch(name: String, checkout: Boolean = true) {
    repositoryScope.launch {
      _activeGitOperationText.value = "Creating branch $name..."
      val res = gitManager.createBranch(_activeProject.value, name, checkout)
      if (res.success) {
        _gitOperationFeedback.value = "Created branch $name"
        _gitError.value = null
      } else {
        _gitError.value = "Create branch failed: ${res.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit()
    }
  }

  fun deleteBranch(name: String, force: Boolean = false) {
    repositoryScope.launch {
      _activeGitOperationText.value = "Deleting branch $name..."
      val res = gitManager.deleteBranch(_activeProject.value, name, force)
      if (res.success) {
        _gitOperationFeedback.value = "Deleted branch $name"
        _gitError.value = null
      } else {
        _gitError.value = "Delete branch failed: ${res.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit()
    }
  }

  fun renameBranch(oldName: String, newName: String) {
    repositoryScope.launch {
      _activeGitOperationText.value = "Renaming branch..."
      val res = gitManager.renameBranch(_activeProject.value, oldName, newName)
      if (res.success) {
        _gitOperationFeedback.value = "Branch renamed to $newName"
        _gitError.value = null
      } else {
        _gitError.value = "Rename branch failed: ${res.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit()
    }
  }

  fun mergeBranch(name: String) {
    repositoryScope.launch {
      _activeGitOperationText.value = "Merging $name..."
      val res = gitManager.mergeBranch(_activeProject.value, name)
      if (res.success) {
        _gitOperationFeedback.value = "Merged $name successfully"
        _gitError.value = null
      } else {
        _gitError.value = "Merge failed: ${res.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit()
      refreshFiles()
    }
  }

  fun abortMerge() {
    repositoryScope.launch {
      val res = gitManager.abortMerge(_activeProject.value)
      if (res.success) _gitOperationFeedback.value = "Merge aborted" else _gitError.value = res.output.ifBlank { "Abort merge failed" }
      refreshDiffsAndGit()
      refreshFiles()
    }
  }

  fun continueMerge() {
    repositoryScope.launch {
      val res = gitManager.continueMerge(_activeProject.value)
      if (res.success) _gitOperationFeedback.value = "Merge continued" else _gitError.value = res.output.ifBlank { "Continue merge failed" }
      refreshDiffsAndGit()
      refreshFiles()
    }
  }

  fun rebaseBranch(name: String) {
    repositoryScope.launch {
      _activeGitOperationText.value = "Rebasing on $name..."
      val res = gitManager.rebaseBranch(_activeProject.value, name)
      if (res.success) {
        _gitOperationFeedback.value = "Rebased on $name"
        _gitError.value = null
      } else {
        _gitError.value = "Rebase failed: ${res.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit()
      refreshFiles()
    }
  }

  fun abortRebase() {
    repositoryScope.launch {
      val res = gitManager.abortRebase(_activeProject.value)
      if (res.success) _gitOperationFeedback.value = "Rebase aborted" else _gitError.value = res.output.ifBlank { "Abort rebase failed" }
      refreshDiffsAndGit()
      refreshFiles()
    }
  }

  fun continueRebase() {
    repositoryScope.launch {
      val res = gitManager.continueRebase(_activeProject.value)
      if (res.success) _gitOperationFeedback.value = "Rebase continued" else _gitError.value = res.output.ifBlank { "Continue rebase failed" }
      refreshDiffsAndGit()
      refreshFiles()
    }
  }

  fun abortCherryPick() {
    repositoryScope.launch {
      val res = gitManager.abortCherryPick(_activeProject.value)
      if (res.success) _gitOperationFeedback.value = "Cherry-pick aborted" else _gitError.value = res.output.ifBlank { "Abort cherry-pick failed" }
      refreshDiffsAndGit()
      refreshFiles()
    }
  }

  fun continueCherryPick() {
    repositoryScope.launch {
      val res = gitManager.continueCherryPick(_activeProject.value)
      if (res.success) _gitOperationFeedback.value = "Cherry-pick continued" else _gitError.value = res.output.ifBlank { "Continue cherry-pick failed" }
      refreshDiffsAndGit()
      refreshFiles()
    }
  }

  fun fetch(remote: String = "origin", prune: Boolean = false) {
    repositoryScope.launch {
      _activeGitOperationText.value = "Fetching from $remote..."
      val res = gitManager.fetch(_activeProject.value, remote, prune)
      if (res.success) {
        _gitOperationFeedback.value = "Fetched from $remote"
        _gitError.value = null
      } else {
        _gitError.value = "Fetch failed: ${res.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit()
    }
  }

  fun pull(remote: String = "origin", branch: String? = null, rebase: Boolean = false) {
    repositoryScope.launch {
      _activeGitOperationText.value = "Pulling from $remote..."
      val res = gitManager.pull(_activeProject.value, remote, branch, rebase)
      if (res.success) {
        _gitOperationFeedback.value = "Pulled successfully"
        _gitError.value = null
      } else {
        _gitError.value = "Pull failed: ${res.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit()
      refreshFiles()
    }
  }

  fun push(remote: String = "origin", branch: String? = null, setUpstream: Boolean = false, force: Boolean = false) {
    repositoryScope.launch {
      _activeGitOperationText.value = "Pushing to $remote..."
      val res = gitManager.push(_activeProject.value, remote, branch, setUpstream, force)
      if (res.success) {
        _gitOperationFeedback.value = "Pushed successfully"
        _gitError.value = null
      } else {
        _gitError.value = "Push failed: ${res.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit()
    }
  }

  fun sync() {
    repositoryScope.launch {
      _activeGitOperationText.value = "Syncing with remote..."
      val pullRes = gitManager.pull(_activeProject.value)
      if (!pullRes.success) {
        _gitError.value = "Sync failed during pull: ${pullRes.output.ifBlank { "Unknown error" }}"
        _activeGitOperationText.value = null
        refreshDiffsAndGit()
        return@launch
      }
      val pushRes = gitManager.push(_activeProject.value)
      if (pushRes.success) {
        _gitOperationFeedback.value = "Synced with remote successfully"
        _gitError.value = null
      } else {
        _gitError.value = "Pulled changes, but push failed: ${pushRes.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit()
      refreshFiles()
    }
  }

  fun addRemote(name: String, url: String) {
    repositoryScope.launch {
      _activeGitOperationText.value = "Adding remote $name..."
      val res = gitManager.addRemote(_activeProject.value, name, url)
      if (res.success) {
        _gitOperationFeedback.value = "Remote $name added"
        _gitError.value = null
      } else {
        _gitError.value = "Add remote failed: ${res.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit()
    }
  }

  fun removeRemote(name: String) {
    repositoryScope.launch {
      val res = gitManager.removeRemote(_activeProject.value, name)
      if (res.success) _gitOperationFeedback.value = "Remote $name removed" else _gitError.value = res.output.ifBlank { "Remove remote failed" }
      refreshDiffsAndGit()
    }
  }

  fun setRemoteUrl(name: String, url: String) {
    repositoryScope.launch {
      val res = gitManager.setRemoteUrl(_activeProject.value, name, url)
      if (res.success) _gitOperationFeedback.value = "Remote $name URL updated" else _gitError.value = res.output.ifBlank { "Update remote URL failed" }
      refreshDiffsAndGit()
    }
  }

  fun createTag(name: String, message: String = "", commitHash: String? = null) {
    repositoryScope.launch {
      _activeGitOperationText.value = "Creating tag $name..."
      val res = gitManager.createTag(_activeProject.value, name, message, commitHash)
      if (res.success) {
        _gitOperationFeedback.value = "Tag $name created"
        _gitError.value = null
      } else {
        _gitError.value = "Create tag failed: ${res.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit()
    }
  }

  fun deleteTag(name: String) {
    repositoryScope.launch {
      val res = gitManager.deleteTag(_activeProject.value, name)
      if (res.success) _gitOperationFeedback.value = "Tag $name deleted" else _gitError.value = res.output.ifBlank { "Delete tag failed" }
      refreshDiffsAndGit()
    }
  }

  fun saveStash(message: String = "", includeUntracked: Boolean = false) {
    repositoryScope.launch {
      _activeGitOperationText.value = "Saving stash..."
      val res = gitManager.stashChanges(_activeProject.value, message, includeUntracked)
      if (res.success) {
        _gitOperationFeedback.value = "Stash saved"
        _gitError.value = null
      } else {
        _gitError.value = "Stash failed: ${res.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit()
      refreshFiles()
    }
  }

  fun applyStash(index: Int) {
    repositoryScope.launch {
      _activeGitOperationText.value = "Applying stash@{$index}..."
      val res = gitManager.stashApply(_activeProject.value, index)
      if (res.success) {
        _gitOperationFeedback.value = "Stash applied"
        _gitError.value = null
      } else {
        _gitError.value = "Apply stash failed: ${res.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit()
      refreshFiles()
    }
  }

  fun popStash(index: Int) {
    repositoryScope.launch {
      _activeGitOperationText.value = "Popping stash@{$index}..."
      val res = gitManager.stashPop(_activeProject.value, index)
      if (res.success) {
        _gitOperationFeedback.value = "Stash popped"
        _gitError.value = null
      } else {
        _gitError.value = "Pop stash failed: ${res.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit()
      refreshFiles()
    }
  }

  fun dropStash(index: Int) {
    repositoryScope.launch {
      val res = gitManager.stashDrop(_activeProject.value, index)
      if (res.success) _gitOperationFeedback.value = "Stash dropped" else _gitError.value = res.output.ifBlank { "Drop stash failed" }
      refreshDiffsAndGit()
    }
  }

  fun deleteUntrackedFile(filePath: String) {
    repositoryScope.launch {
      val res = gitManager.deleteUntrackedFile(_activeProject.value, filePath)
      if (res) {
        _gitOperationFeedback.value = "Deleted $filePath"
        refreshFiles()
      } else {
        _gitError.value = "Could not delete $filePath"
      }
      refreshDiffsAndGit()
    }
  }

  fun setFilesStaged(paths: Collection<String>, stage: Boolean) {
    repositoryScope.launch {
      val project = _activeProject.value
      if (stage) {
        gitManager.stageFiles(project, paths)
      } else {
        paths.forEach { gitManager.unstageFile(project, it) }
      }
      refreshDiffsAndGit()
    }
  }

  suspend fun getFullDiffText(type: DiffCopyType, filePath: String? = null): String {
    val project = _activeProject.value
    return when (type) {
      DiffCopyType.ALL -> gitManager.fullDiff(project)
      DiffCopyType.STAGED -> gitManager.stagedDiff(project)
      DiffCopyType.UNSTAGED -> gitManager.unstagedDiff(project)
      DiffCopyType.FILE -> filePath?.let { gitManager.fileDiff(project, it) } ?: ""
    }
  }

  suspend fun explainChangesWithAgent(diffText: String): String? {
    if (diffText.isBlank()) return "No changes to explain."
    return try {
      requestLlmText(
        system = "You are an expert code reviewer and software architect. Explain the git diff concisely to the developer, highlighting purpose, key logic changes, and any architectural implications.",
        user = "Explain these git changes:\n\n" + diffText.take(12000),
        maxTokens = 600
      )
    } catch (e: Exception) {
      "Explanation unavailable: ${e.message}"
    }
  }

  suspend fun reviewChangesWithAgent(diffText: String): String? {
    if (diffText.isBlank()) return "No changes to review."
    return try {
      requestLlmText(
        system = "You are a senior code reviewer. Review the following git diff for bugs, edge cases, security issues, performance pitfalls, and code style. Structure with concise bullet points.",
        user = "Perform a code review on this diff:\n\n" + diffText.take(12000),
        maxTokens = 800
      )
    } catch (e: Exception) {
      "Code review unavailable: ${e.message}"
    }
  }

  suspend fun explainCommitWithAgent(commit: GitCommit): String? {
    return try {
      val detail = getCommitDetail(commit.hash)
      val diff = detail?.diff?.ifBlank { null } ?: getCommitDiff(commit.hash)
      requestLlmText(
        system = "You are an expert software engineer. Explain this git commit clearly and concisely, including what changed and why.",
        user = "Commit: ${commit.hash} - ${commit.message}\nAuthor: ${commit.author}\nDate: ${commit.date}\n\nDiff:\n" + diff.take(10000),
        maxTokens = 500
      )
    } catch (e: Exception) {
      "Commit explanation unavailable: ${e.message}"
    }
  }

  fun acceptAllDiffs() {
    // Stage every working-tree change (the user reviews/commits from the Git tab).
    repositoryScope.launch {
      runGitCommand(_activeProject.value.path, "git add -A")
      refreshDiffsAndGit()
    }
  }

  fun rejectAllDiffs() {
    repositoryScope.launch {
      gitManager.revertAllFiles(_activeProject.value)
      refreshFiles()
      val reloaded = fileSystem.readFile(_activeProject.value, _activeFile.value.path)
      _editorContent.value = reloaded
      _isEditorDirty.value = false
    }
  }

  fun rejectDiff(filePath: String) {
    repositoryScope.launch {
      gitManager.revertFile(_activeProject.value, filePath)
      refreshFiles()
      if (_activeFile.value.path == filePath) {
        val reloaded = fileSystem.readFile(_activeProject.value, _activeFile.value.path)
        _editorContent.value = reloaded
        _isEditorDirty.value = false
      }
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
    val project = _activeProject.value
    val newId = "term-${System.currentTimeMillis()}"
    val newSession = TerminalSession(
      id = newId,
      name = name,
      currentDir = guestPathFor(project.path),
      projectId = project.id
    )
    terminalTabsByProject.getOrPut(project.id) { mutableListOf() }.add(newSession)
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
    val project = _activeProject.value
    val currentList = _terminalSessions.value
    if (currentList.size <= 1) {
      val resetSession = TerminalSession(
        id = "term-${System.currentTimeMillis()}",
        name = "main",
        currentDir = guestPathFor(project.path),
        projectId = project.id
      )
      terminalTabsByProject[project.id] = mutableListOf(resetSession)
      _terminalSessions.value = listOf(resetSession)
      _activeTerminalSessionId.value = resetSession.id
      return
    }

    val remaining = currentList.filter { it.id != id }
    terminalTabsByProject[project.id] = remaining.toMutableList()
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

  /** Aborts the in-flight streaming LLM request so Stop takes effect immediately. */
  fun cancelAgentGeneration() {
    llmService.cancelActive()
  }

  /** SIGKILLs one specific running tool call (the task keeps running). */
  fun cancelToolCall(callId: String) {
    agentRuntime.cancelToolCall(callId)
  }

  /** Retry re-executes a cancelled tool call; continue feeds a cancellation result. */
  fun resolveToolCancellation(callId: String, retry: Boolean) {
    agentRuntime.resolveToolCancellation(callId, retry)
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

  // Run Real Agent Task Workflow. `sessionId` ties the run to a persisted chat
  // session: stateless protocols rebuild every request from the full persisted
  // conversation, while stateful ones (Gemini Interactions) use it to resume
  // their server-side chain and send only the new turn.
  suspend fun runAgentTask(prompt: String, sessionId: String? = null) {
    if (_isAgentWorking.value) return
    val history = sessionId?.let { chatStore.buildConversationMessages(it, excludeLastUser = true) } ?: emptyList()
    executeAgentTask(prompt, history, resume = false, sessionId = sessionId)
  }

  /**
   * Resumes a failed turn from its persisted state: the conversation is
   * rebuilt from SQLite including the turn's tool calls/results, so the retry
   * continues exactly where the failure happened.
   */
  suspend fun resumeAgentTask(turnUuid: String, sessionId: String) {
    if (_isAgentWorking.value) return
    val history = chatStore.buildConversationMessages(sessionId, excludeLastUser = false, currentTurnUuid = turnUuid)
    executeAgentTask("", history, resume = true, sessionId = sessionId)
  }

  private suspend fun executeAgentTask(
    prompt: String,
    history: List<com.agentisco.data.repository.ChatHistoryMessage>,
    resume: Boolean,
    sessionId: String?
  ) {
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

    val result = try {
      agentRuntime.executeTask(
        prompt = prompt,
        project = _activeProject.value,
        provider = provider,
        model = model,
        apiKey = apiKey,
        permissions = { _permissions.value },
        terminalSession = currentSession,
        sessionId = sessionId,
        history = history,
        resume = resume,
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
    } finally {
      // Always leave the "Working" state — on cancel, failure, or completion.
      _isAgentWorking.value = false
      _pendingApproval.value = null
    }
    refreshFiles()

    // Refresh active file if modified
    if (result.modifiedFiles.contains(_activeFile.value.path)) {
      val freshContent = fileSystem.readFile(_activeProject.value, _activeFile.value.path)
      _editorContent.value = freshContent
      _isEditorDirty.value = false
    }

    _agentStatusText.value = if (result.success) result.summary else "Task failed — ${result.summary}"
    // Mirror the agent's changes to the original folder (if enabled).
    maybeAutoSync(_activeProject.value)
  }

  fun toggleDevServer() {
    _isDevServerRunning.update { !it }
  }

  companion object {
    /** Minimum gap between full commit-history reloads triggered by file-watcher churn. */
    private const val HISTORY_MIN_RELOAD_INTERVAL_MS = 1200L
  }
}
