package com.awaki.data.repository

import com.awaki.settings.model.AIProvider
import com.awaki.settings.model.AIModel
import com.awaki.agent.model.AgentPermissions
import com.awaki.agent.model.PendingApproval
import com.awaki.agent.model.ToolExecution
import com.awaki.agent.model.ToolType
import com.awaki.agent.model.AgentTaskStep
import com.awaki.core.model.AppDestination
import com.awaki.agent.permission.DestructiveCommandGuard
import com.awaki.agent.runtime.AgentRuntime
import com.awaki.workspace.terminal.DebianBootstrap
import com.awaki.workspace.terminal.NativeBinaries
import com.awaki.workspace.terminal.ProotArgsBuilder
import com.awaki.workspace.terminal.ProotSessionManager
import com.awaki.workspace.terminal.TerminalProcessManager
import com.awaki.data.local.BuildRunConfigStore
import com.awaki.workspace.buildrun.BuildLogLine
import com.awaki.workspace.buildrun.BuildRecipeDetector
import com.awaki.workspace.buildrun.BuildRunConfig
import com.awaki.workspace.buildrun.BuildRunConfigSource
import com.awaki.workspace.buildrun.BuildRunController
import com.awaki.workspace.buildrun.BuildRunDetectState
import com.awaki.workspace.buildrun.BuildStageKind
import com.awaki.workspace.buildrun.BuildStageState
import com.awaki.workspace.buildrun.PreviewEndpoint
import com.termux.terminal.TerminalSessionClient
import com.awaki.workspace.git.GitRepositoryManager
import com.awaki.workspace.filesystem.ProjectFileSystem
import com.awaki.workspace.filesystem.ProjectMetadataScanner
import com.awaki.workspace.filesystem.WorkspaceFileWatcher
import com.awaki.workspace.git.*
import android.content.Context
import com.awaki.data.model.*
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class WorkspaceRepository(
  context: Context? = null,
  baseDir: File = context?.let { File(it.filesDir, "sco_projects") }
    ?: File(System.getProperty("java.io.tmpdir") ?: ".", "sco_projects"),
  val providerStore: com.awaki.data.local.ProviderConfigStore? = null,
  /**
   * LLM communication. Providers are configuration only; the protocol adapter is
   * chosen from the provider config, never from its name. The chain store keeps
   * Gemini Interactions' server-side conversation ids, so a session continues
   * where it left off even after the app is restarted.
   */
  private val llmService: com.awaki.agent.llm.LlmService =
    com.awaki.agent.llm.LlmService(
      chainStore = com.awaki.agent.llm.GeminiChainStoreImpl(context?.applicationContext)
    ),
  /**
   * The models installed on this device, published as one more OpenAI-compatible provider.
   * Absent in tests and previews that own no application, where the selectable list is
   * exactly what the durable store holds.
   */
  private val localAi: com.awaki.local.LocalAiRuntime? = null,
  /**
   * The agent's front door to the web. Given by a test that drives the settings screen
   * over a stubbed transport; built here from the durable web-access store otherwise.
   */
  private val web: com.awaki.agent.web.WebGateway? = null
) {

  private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val appContext: Context? = context?.applicationContext

  /**
   * Stops the work this repository set in motion. [repositoryScope] is the one thing
   * here that outlives any single call, so whoever holds the last reference — a view
   * model being cleared, a test class finishing — cancels through this instead of
   * leaving scans, git reads and web probes to resume on a Main dispatcher its owner
   * has already let go of.
   */
  fun dispose() {
    fileWatcher.stop()
    repositoryScope.cancel()
  }

  /**
   * What this process is busy with, when an application owns it. Registering here is
   * what raises the foreground service, so an agent turn, a build stage or a rootfs
   * download survives the screen going off. A repository built for tests has no
   * application and simply does not track anything.
   */
  private val workRegistry: com.awaki.background.WorkRegistry? =
    (context?.applicationContext as? com.awaki.AwakiApplication)?.workRegistry

  /**
   * The process-level background coordinator: the settings behind it, its permission
   * checklist and the work a previous process lost.
   */
  val backgroundExecution: com.awaki.background.BackgroundExecution? =
    (context?.applicationContext as? com.awaki.AwakiApplication)?.backgroundExecution

  /**
   * The models installed on this device, for the screen that manages them — download,
   * cancel, verify, edit. Null where no application owns a filesystem, so a test or
   * preview simply has no local models to manage.
   */
  val localModelStore: com.awaki.data.repository.LocalModelRepository? = localAi?.repository

  /** Why the on-device models cannot answer right now, or null when they can. */
  val localAiNote: String? get() = localAi?.unavailableReason

  /** The one model sitting in memory, whether a turn put it there or the user asked for it. */
  val localResidentModelId: kotlinx.coroutines.flow.StateFlow<String?> =
    localAi?.residentModelId ?: kotlinx.coroutines.flow.MutableStateFlow(null)

  /**
   * Puts [modelId] into memory now, so its first turn does not begin with a load. The failure
   * arrives as the sentence the user should read, which is why this reaches through the runtime
   * rather than asking the screen to guess what went wrong.
   */
  suspend fun loadLocalModel(modelId: String): com.awaki.local.runtime.LoadedModelInfo =
    localAi?.loadModel(modelId)
      ?: throw com.awaki.local.runtime.LocalEngineException(
        "This build has no on-device model to load."
      )

  /**
   * Takes whatever is resident out of memory again. A load is a few hundred megabytes the phone
   * keeps until it is told otherwise, and asking is the only way a user gets them back — the
   * runtime holds at most one model, so the next turn or load pays for itself either way.
   */
  suspend fun unloadLocalModel() {
    localAi?.releaseModel()
  }

  /** The registry record for the turn currently running, if any. */
  private var agentTurnWorkId: String? = null

  /**
   * Cancels the coroutine driving the turn. The turn is launched by the UI layer and
   * owns the transcript writes, so only the UI can stop its job; this is how the
   * notification's Stop action reaches it.
   */
  var agentTurnStopRequested: (() -> Unit)? = null

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
  val scanIgnoreStore = com.awaki.data.local.ScanIgnoreStore(context)
  private val _scanIgnoreSettings = MutableStateFlow(scanIgnoreStore.get())
  val scanIgnoreSettings: StateFlow<com.awaki.data.local.ScanIgnoreSettings> =
    _scanIgnoreSettings.asStateFlow()

  init {
    ProjectFileSystem.ignoredDirs =
      com.awaki.data.local.ScanIgnoreStore.effectiveDirs(_scanIgnoreSettings.value)
  }

  fun addIgnoredDir(raw: String) {
    val name = com.awaki.data.local.ScanIgnoreStore.sanitizeName(raw)
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
    val name = com.awaki.data.local.ScanIgnoreStore.sanitizeName(raw)
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

  // ---- Web access (Settings → Web access) ----
  /**
   * The agent's front door to the web: Jina.ai first, with whatever keys this device
   * can spend, and the URL itself after. One instance for the whole repository, so the
   * request budget the tools are spending is the same one the settings screen reports.
   */
  val webAccessStore = com.awaki.data.local.WebAccessStore(context)

  private val _webAccess = MutableStateFlow(webAccessStore.get())

  val webAccess: StateFlow<com.awaki.data.local.WebAccessSettings> = _webAccess.asStateFlow()

  /** The user's own keys as handles, never as secrets. */
  private val _jinaKeyHandles = MutableStateFlow(webAccessStore.keys().map(::jinaHandle))

  val jinaKeyHandles: StateFlow<List<String>> = _jinaKeyHandles.asStateFlow()

  val webGateway: com.awaki.agent.web.WebGateway = web ?: com.awaki.agent.web.WebGateway(
    settings = { _webAccess.value },
    userKeys = { webAccessStore.keys() }
  )

  fun setPreferJina(enabled: Boolean) {
    _webAccess.value = webAccessStore.update { it.copy(preferJina = enabled) }
  }

  /**
   * Stores every distinct key in [raw] — a paste of several is one gesture — and
   * returns how many landed, so the screen can say what happened rather than assume.
   */
  fun addJinaKeys(raw: String): Int {
    val added = webAccessStore.addKeys(raw)
    _jinaKeyHandles.value = webAccessStore.keys().map(::jinaHandle)
    return added
  }

  fun removeJinaKey(handle: String): Boolean {
    val removed = webAccessStore.removeKey(handle)
    if (removed) _jinaKeyHandles.value = webAccessStore.keys().map(::jinaHandle)
    return removed
  }

  private fun jinaHandle(key: String): String =
    com.awaki.data.local.WebAccessStore.fingerprintOf(key)

  private val _webAccessReport = MutableStateFlow<List<String>>(emptyList())

  /** What actually answers right now, one sentence per tier. */
  val webAccessReport: StateFlow<List<String>> = _webAccessReport.asStateFlow()

  private val _webAccessChecking = MutableStateFlow(false)
  val webAccessChecking: StateFlow<Boolean> = _webAccessChecking.asStateFlow()

  /** Costs one reader call, and one search call where a key exists. */
  fun checkWebAccess() {
    if (_webAccessChecking.value) return
    _webAccessChecking.value = true
    repositoryScope.launch {
      try {
        _webAccessReport.value = webGateway.probe()
      } finally {
        _webAccessChecking.value = false
      }
    }
  }

  // ---- Chat display (Settings → Tool activity) ----
  val chatDisplayStore = com.awaki.data.local.ChatDisplayStore(context)
  private val _chatDisplay = MutableStateFlow(chatDisplayStore.get())
  val chatDisplay: StateFlow<com.awaki.data.local.ChatDisplaySettings> =
    _chatDisplay.asStateFlow()

  fun setChatToolJsonVisible(visible: Boolean) {
    _chatDisplay.value = chatDisplayStore.update { it.copy(showToolJson = visible) }
  }

  // ---- Editor preferences (Settings → Editor, and the editor's own quick panel) ----
  // Owned here rather than by the view model so the two surfaces that edit these
  // share one source of truth, and a change survives the process.
  val editorSettingsStore = com.awaki.data.local.EditorSettingsStore(context)
  private val _editorSettings = MutableStateFlow(editorSettingsStore.get())
  val editorSettings: StateFlow<com.awaki.editor.model.EditorSettings> = _editorSettings.asStateFlow()

  fun updateEditorSettings(settings: com.awaki.editor.model.EditorSettings) {
    _editorSettings.value = editorSettingsStore.update { settings }
  }

  // ---- UI theme (Settings → Appearance → Theme gallery) ----
  // The application owns this state rather than the repository: the very first frame of
  // the process is already themed, before any screen has asked for a view model, and the
  // terminal and the cold-start window read their colours from the same flow. A
  // repository with no application (tests, previews) falls back to its own store, so the
  // settings screen stays drivable there.
  private val owningApplication: com.awaki.AwakiApplication? =
    appContext as? com.awaki.AwakiApplication

  private val uiThemeStore by lazy { com.awaki.data.local.UiThemeStore(context) }

  private val fallbackUiTheme by lazy {
    MutableStateFlow(com.awaki.ui.theme.uiThemeByKey(uiThemeStore.get().orEmpty()))
  }

  val uiTheme: StateFlow<com.awaki.ui.theme.UiPalette>
    get() = owningApplication?.uiTheme ?: fallbackUiTheme

  /** Paint the whole app with the theme registered under [key], and remember it. */
  fun setUiTheme(key: String) {
    val palette = com.awaki.ui.theme.uiThemeByKey(key)
    val app = owningApplication
    if (app != null) {
      app.setUiTheme(key)
    } else {
      uiThemeStore.setKey(key)
      fallbackUiTheme.value = palette
      com.awaki.workspace.terminal.TerminalPalette.apply(palette)
    }
    // A shell that is already running holds the colours it started with; every live
    // session is pulled back to the theme and told to redraw.
    _ptySessions.value.values.forEach { com.awaki.workspace.terminal.TerminalPalette.refresh(it) }
  }

  // ---- Projects layout (grid vs. list) ----
  val projectsViewStore = com.awaki.data.local.ProjectsViewStore(context)
  private val _projectsView = MutableStateFlow(projectsViewStore.get())
  val projectsView: StateFlow<com.awaki.data.local.ProjectsViewSettings> =
    _projectsView.asStateFlow()

  fun setProjectsGridView(enabled: Boolean) {
    _projectsView.value = projectsViewStore.update { it.copy(gridView = enabled) }
  }

  // ---- Context & compaction (Settings → Context) ----
  val compactSettingsStore = com.awaki.data.local.CompactSettingsStore(context)
  private val _compactSettings = MutableStateFlow(compactSettingsStore.get())
  val compactSettings: StateFlow<com.awaki.data.local.CompactSettings> =
    _compactSettings.asStateFlow()

  fun updateCompactSettings(transform: (com.awaki.data.local.CompactSettings) -> com.awaki.data.local.CompactSettings) {
    _compactSettings.value = compactSettingsStore.update(transform)
    // A change to the budget must be reflected immediately, even idle.
    _contextUsage.value = _contextUsage.value.copy(
      contextWindow = _contextUsage.value.contextWindow,
      thresholdTokens = _compactSettings.value
        .toPolicyConfig(_selectedModel.value?.contextWindow, _selectedModel.value?.maxOutputTokens)
        .thresholdTokens
    )
  }

  /**
   * Live context occupancy of the conversation being answered: drives the
   * percentage chip next to the composer's dropdowns.
   */
  private val _contextUsage = MutableStateFlow(com.awaki.agent.compact.ContextTokenUsage.empty())
  val contextUsage: StateFlow<com.awaki.agent.compact.ContextTokenUsage> = _contextUsage.asStateFlow()

  /** Set true once a turn has actually measured something, so the chip can hide. */
  private val _hasContextUsage = MutableStateFlow(false)
  val hasContextUsage: StateFlow<Boolean> = _hasContextUsage.asStateFlow()

  /** Recent compaction logs of the open session, newest last. */
  private val _compactionLog = MutableStateFlow<List<com.awaki.agent.compact.CompactBoundary>>(emptyList())
  val compactionLog: StateFlow<List<com.awaki.agent.compact.CompactBoundary>> = _compactionLog.asStateFlow()

  /** Clears the context meter when the chat switches to another conversation. */
  fun resetContextUsage() {
    _contextUsage.value = com.awaki.agent.compact.ContextTokenUsage.empty(
      _selectedModel.value?.contextWindow ?: com.awaki.agent.compact.CompactPolicyConfig.DEFAULT_CONTEXT_WINDOW
    )
    _hasContextUsage.value = false
  }

  /**
   * Publishes a context-usage snapshot. Called by the runtime while a turn
   * streams, and by the chat when it re-estimates an idle conversation so the
   * percentage chip is already correct when a session is opened.
   */
  fun publishContextUsage(usage: com.awaki.agent.compact.ContextTokenUsage) {
    _contextUsage.value = usage
    _hasContextUsage.value = usage.usedTokens > 0
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
   * Where every terminal shell starts: the Linux home. A tab is not tied to a
   * project's folder, so the page works the same whether or not one was chosen.
   */
  private fun newTerminalTab(project: Project, name: String = "main") = TerminalSession(
    id = "term-${terminalTabSeq.incrementAndGet()}",
    name = name,
    currentDir = GUEST_HOME_DIR,
    projectId = project.id
  )

  /**
   * What the terminal header shows as the workspace: the project whose folder is
   * mounted at `/workspace` inside the guest, or the home alone when no project
   * has been chosen yet.
   */
  fun terminalWorkspaceLabel(project: Project): String =
    if (project.path.isBlank()) GUEST_HOME_DIR else guestPathFor(project.path)

  /**
   * Shows [project]'s terminal tabs, opening a first one when it has none. Called
   * both from the constructor and whenever a project becomes active: the terminal
   * page is reachable the instant the app is, so it must have a tab before the
   * first project has been found on disk.
   */
  private fun openTerminalTabsFor(project: Project) {
    val tabs = terminalTabsByProject.getOrPut(project.id) { mutableListOf(newTerminalTab(project)) }
    // A snapshot, never the map's own list: the flow and the registry must not be
    // the same object, or adding a tab would reach the flow twice.
    _terminalSessions.value = tabs.toList()
    if (tabs.none { it.id == _activeTerminalSessionId.value }) {
      _activeTerminalSessionId.value = tabs.first().id
    }
  }

  /**
   * Executes a real `git` command inside the rootfs with the project folder
   * mounted as the workspace — the same environment the terminal uses.
   * Runs are serialized per project so background status refreshes can never
   * collide with long operations (pull/commit) over `.git/index.lock`.
   */
  private suspend fun runGitCommand(projectPath: String, args: String): com.awaki.workspace.git.GitRunResult {
    val mutex = gitRunMutexes.getOrPut(projectPath) { Mutex() }
    return mutex.withLock { runGitCommandUnlocked(projectPath, args) }
  }

  private val gitRunMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

  private suspend fun runGitCommandUnlocked(projectPath: String, args: String): com.awaki.workspace.git.GitRunResult {
    val out = StringBuilder()
    var truncated = false
    val runnerSession = TerminalSession(
      id = "git-runner-" + projectPath.hashCode(),
      name = "git",
      currentDir = projectPath
    )
    val code = terminalManager.executeCommand(
      runnerSession, args,
      { line ->
        // Whatever is asked of git, its whole output must never sit in memory: a
        // `git show` of a bundle is megabytes per line. Callers that need the full
        // body check [GitRunResult.truncated] and degrade instead.
        if (out.length < MAX_GIT_OUTPUT_CHARS) out.appendLine(line.text) else truncated = true
      },
      projectDir = File(projectPath).takeIf { it.isDirectory }
    )
    val outputStr = out.toString()
    if (code != 0 && (outputStr.contains("Linux environment is not ready yet") || outputStr.contains("Failed to run command in Linux environment"))) {
      val hostRes = runHostGit(projectPath, args)
      if (hostRes != null) return hostRes
    }
    return com.awaki.workspace.git.GitRunResult(code, outputStr, truncated)
  }

  private suspend fun runHostGit(projectPath: String, args: String): com.awaki.workspace.git.GitRunResult? =
    withContext(Dispatchers.IO) {
      try {
        val dir = File(projectPath).takeIf { it.isDirectory } ?: return@withContext null
        val pb = ProcessBuilder("sh", "-c", "cd \"${dir.absolutePath}\" && $args")
        pb.redirectErrorStream(false)
        val env = pb.environment()
        env["GIT_TERMINAL_PROMPT"] = "0"
        val process = pb.start()
        val stdout = readCapped(process.inputStream)
        val stderr = readCapped(process.errorStream)
        val code = process.waitFor()
        val combined = when {
          stderr.first.isBlank() -> stdout.first
          stdout.first.isBlank() -> stderr.first
          else -> "${stdout.first}\n${stderr.first}"
        }
        com.awaki.workspace.git.GitRunResult(code, combined, stdout.second || stderr.second)
      } catch (e: Exception) {
        null
      }
    }

  /**
   * Drains a process stream while keeping at most [MAX_GIT_OUTPUT_CHARS] of it;
   * the stream is still read to the end so the process cannot block on a full
   * pipe. Second half of the pair says content was dropped.
   */
  private fun readCapped(stream: java.io.InputStream): Pair<String, Boolean> {
    val sb = StringBuilder()
    var truncated = false
    stream.bufferedReader().use { reader ->
      val buffer = CharArray(8192)
      while (true) {
        val read = reader.read(buffer)
        if (read < 0) break
        if (sb.length < MAX_GIT_OUTPUT_CHARS) sb.appendRange(buffer, 0, read) else truncated = true
      }
    }
    return sb.toString() to truncated
  }

  // (Terminal stack initialized above — the projects root depends on it.)

  /**
   * The model directory, offered to every guest command at the path the guest calls home.
   * The app writes bytes into it from a download and a terminal session reads the same bytes from
   * inside the rootfs, so neither side copies and neither keeps a list the other could disagree
   * with. proot refuses a bind whose host directory is missing, hence `mkdirs`.
   *
   * Decoding a model no longer needs this — the engine opens the host file itself — but a user
   * who wants to see what is installed, or an agent asked to inspect it, reaches it by name.
   */
  private val guestModelBinds: List<Pair<String, String>> = context?.let { ctx ->
    val dir = com.awaki.local.LocalModelPaths.hostDir(ctx.filesDir).apply { mkdirs() }
    listOf(dir.absolutePath to com.awaki.local.LocalModelPaths.GUEST_DIR)
  } ?: emptyList()

  private val prootSessionManager: ProotSessionManager? = context?.let { ctx ->
    val bootstrap = debianBootstrap ?: return@let null
    val bins = nativeBinaries ?: return@let null
    ProotSessionManager(ctx, bins, bootstrap.rootfsDir, guestModelBinds)
  }
  val terminalManager = TerminalProcessManager {
    val bootstrap = debianBootstrap?.takeIf { it.isBootstrapped() } ?: return@TerminalProcessManager null
    val bins = nativeBinaries ?: return@TerminalProcessManager null
    ProotArgsBuilder(bins, bootstrap.rootfsDir, guestModelBinds)
  }.also { manager ->
    // Every scripted Linux command - an agent tool call, a Run & Build stage, a dev
    // server - is a child of this process, so the moment one is live the app owes
    // itself a foreground service to keep the whole tree running.
    manager.onRunningChanged = { commands -> syncRunningCommands(commands) }
  }

  /** Real execution + state behind the Run & Build Center. */
  val buildRunController = BuildRunController(
    terminalManager = terminalManager,
    scope = repositoryScope,
    configStore = BuildRunConfigStore(context)
  )

  // LLM communication + agent tooling.

  /**
   * Who is working in this workspace, shared by the chat's own run and every
   * agent it delegates to. One board per workspace is what makes the second one
   * know the first is there.
   */
  private val teamBoard = com.awaki.agent.runtime.AgentTeamBoard()

  /**
   * The user's own specialists. Read here rather than in a screen because the
   * roster is what the agent addresses: `delegate` can only name a seat the
   * registry already knows, so a saved agent joins the team at the moment it is
   * saved, with no restart.
   */
  val customAgentStore = com.awaki.data.local.CustomAgentStore(context)
  private val _customAgents = MutableStateFlow(customAgentStore.get())
  val customAgents: StateFlow<List<com.awaki.agent.model.AgentRole>> = _customAgents.asStateFlow()

  /** Built-in seats first, then the user's: the whole team one run may delegate to. */
  fun agentRoster(): List<com.awaki.agent.model.AgentRole> =
    com.awaki.agent.model.AgentRoles.roster(_customAgents.value)

  fun saveCustomAgent(agent: com.awaki.agent.model.AgentRole): List<com.awaki.agent.model.AgentRole> =
    customAgentStore.save(agent).also { _customAgents.value = it }

  /**
   * The tools a delegated run may be given, as the agent editor's checklist. Read
   * from the registry the runtime actually uses, so a tool that is added to the
   * app appears here and cannot be promised to a custom agent by accident.
   */
  fun delegableToolNames(readOnly: Boolean): List<String> =
    toolRegistry.delegableToolNames(readOnly).sorted()

  /**
   * Every tool an on-device model may be handed, with what each one costs the prompt.
   * Read from the same registry the runtime narrows, so the checklist can never offer
   * a tool the agent could then be told about.
   */
  fun offerableTools(): List<com.awaki.agent.tool.ToolOffering> = toolRegistry.offerableTools()

  fun removeCustomAgent(id: String): List<com.awaki.agent.model.AgentRole> =
    customAgentStore.remove(id).also { _customAgents.value = it }

  /**
   * The `SKILL.md` folders, read from disk on demand. The app's own directory holds
   * the installed ones and a project's `.awaki/skills` the repo's, so adding a
   * skill is writing a file - no install step, and it survives a reinstall of the
   * app when it lives in the repo.
   */
  val skillStore = com.awaki.agent.skill.SkillStore(context)

  /** Skills for one project, with the project's own winning by name. */
  fun skillsFor(project: Project?): List<com.awaki.agent.skill.AgentSkill> =
    project?.let { skillStore.discover(java.io.File(it.path)) } ?: emptyList()

  private val toolRegistry: com.awaki.agent.tool.AgentToolRegistry =
    com.awaki.agent.tool.AgentToolRegistry(
    fileSystem = fileSystem,
    gitManager = gitManager,
    terminalManager = terminalManager,
    webGateway = webGateway,
    skillStore = skillStore,
    stagedFilesProvider = { _stagedFiles.value },
    onStageFile = { f -> toggleFileStaged(f) },
    onStageAll = { stageAll() },
    onUnstageAll = { unstageAll() },
    // Delegated research is run by the runtime, which owns the model call. The
    // lookup here stays silent: a sub-agent with no model reports that to the
    // agent, it does not open the model sheet in the middle of a turn.
    subagentLauncher = com.awaki.agent.tool.SubagentLauncher { role, description, prompt, project, terminal, delegationId ->
      // A specialist may be pinned to a model. If that model is no longer
      // configured the run still goes ahead on the user's current choice, and
      // says so in the turn's own status stream rather than failing quietly.
      val pinned = role.modelId.takeIf { it.isNotBlank() }?.let { wanted ->
        _aiModels.value.firstOrNull { it.id == wanted || it.modelId == wanted }
      }
      if (role.modelId.isNotBlank() && pinned == null) {
        _agentEvents.tryEmit(
          com.awaki.agent.model.AgentStreamEvent.Status(
            "${role.name} is set to model \"${role.modelId}\", which is not configured - running it on the selected model."
          )
        )
      }
      val model = pinned ?: _selectedModel.value
      val connection = model?.let { resolveProviderForModel(it) }
      if (model == null || connection == null) {
        com.awaki.agent.tool.SubagentOutcome(
          success = false,
          error = "No selected model with a usable API key, so the ${role.name} could not start."
        )
      } else {
        agentRuntime.runSubagent(
          role = role,
          description = description,
          prompt = prompt,
          project = project,
          provider = connection.first,
          model = model,
          apiKey = connection.second,
          parentPermissions = _permissions.value,
          terminalSession = terminal,
          // Everything the specialist does belongs to the delegate card that
          // started it, so the chat shows its work as it happens, not only at the end.
          delegationId = delegationId,
          onEvent = { event -> _agentEvents.tryEmit(event) }
        )
      }
    },
    subagentRoles = { agentRoster() }
  )

  /**
   * Where the agent runtime records compaction: the summary and the boundary
   * land in SQLite, while the chat keeps every original message.
   */
  private val compactSink = object : AgentRuntime.CompactSink {
    override suspend fun onCompacted(
      sessionId: String,
      summary: String,
      boundary: com.awaki.agent.compact.CompactBoundary,
      summarizedThroughRowId: Long,
      keptFromRowId: Long
    ) {
      chatStore.recordCompaction(
        sessionId = sessionId,
        summary = summary,
        summarizedThroughRowId = summarizedThroughRowId,
        keptFromRowId = keptFromRowId,
        tokensBefore = boundary.tokensBefore,
        tokensAfter = boundary.tokensAfter,
        contextWindow = boundary.contextWindow,
        summarizedMessages = boundary.summarizedMessages,
        keptMessages = boundary.keptMessages,
        clearedToolResults = boundary.clearedToolResults,
        trigger = boundary.trigger.name
      )
      _compactionLog.update { (it + boundary).takeLast(8) }
      loadLatestCompaction(sessionId)
    }

    override suspend fun latestCompaction(sessionId: String): SessionCompaction? =
      chatStore.latestCompactionBlocking(sessionId)
  }

  /** Most recent compaction of the session currently being discussed. */
  private val _latestCompaction = MutableStateFlow<SessionCompaction?>(null)
  val latestCompaction: StateFlow<SessionCompaction?> = _latestCompaction.asStateFlow()

  /** Re-reads a session's compaction so the chat can show its summary. */
  suspend fun loadLatestCompaction(sessionId: String) {
    _latestCompaction.value = chatStore.latestCompactionBlocking(sessionId)
  }

  val agentRuntime: AgentRuntime =
    AgentRuntime(fileSystem, terminalManager, gitManager, llmService, toolRegistry, teamBoard, skillStore) { compactSink }

  // Current Projects. The registry (projects.json) is the source of truth for
  // each project's real root folder; legacy projects found on disk under the
  // base directory are migrated into it on first launch.
  val projectRegistry = com.awaki.data.local.ProjectRegistryStore(context)
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
    // The workspace's own .awaki.json is the authoritative project config;
    // the registry caches it for the project list.
    fun configFor(entry: com.awaki.data.local.ProjectRegistryEntry): com.awaki.workspace.filesystem.ProjectConfig? =
      com.awaki.workspace.filesystem.ProjectFileSystem.readProjectConfig(File(entry.rootPath))

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
          com.awaki.data.local.ProjectRegistryEntry(
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

  private fun Project.withMetadata(meta: com.awaki.workspace.filesystem.ProjectMetadata): Project =
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
  private val _agentEvents = MutableSharedFlow<com.awaki.agent.model.AgentStreamEvent>(
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

  /**
   * True while a request is parked in the chat rather than waiting in a dialog.
   *
   * Deferring is not deciding: the runtime keeps its suspended tool call and the
   * chat card keeps its controls, but the modal is closed until the user asks
   * for it again from the card. Distinct from [pendingApproval] being null,
   * which means the request is genuinely resolved.
   */
  private val _approvalDeferred = MutableStateFlow(false)
  val approvalDeferred: StateFlow<Boolean> = _approvalDeferred.asStateFlow()

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

  private fun reportGitError(operation: String, result: com.awaki.workspace.git.GitRunResult?) {
    _gitError.value = when {
      result == null -> null
      result.exitCode == 0 -> null
      else -> "$operation failed (exit ${result.exitCode}): ${result.output.take(200).ifBlank { "no output" }}"
    }
  }

  private val _commitMessage = MutableStateFlow("")
  val commitMessage: StateFlow<String> = _commitMessage.asStateFlow()

  private val _commitHistory = MutableStateFlow<List<GitCommit>>(emptyList())
  val commitHistory: StateFlow<List<GitCommit>> = _commitHistory.asStateFlow()

  // Terminal tabs (metadata only; the actual console lives in a real PTY
  // session per tab, created on demand when the environment is bootstrapped).
  private val _terminalSessions = MutableStateFlow<List<TerminalSession>>(emptyList())
  /** Terminal tabs per project: switching workspaces switches tab sets. */
  private val terminalTabsByProject = mutableMapOf<String, MutableList<TerminalSession>>()
  /** Makes tab ids unique; a wall-clock timestamp repeats within a millisecond. */
  private val terminalTabSeq = java.util.concurrent.atomic.AtomicLong()
  val terminalSessions: StateFlow<List<TerminalSession>> = _terminalSessions.asStateFlow()

  private val _activeTerminalSessionId = MutableStateFlow("")
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
  private val rememberedProjectConfigDir: File? = context?.getDir("awaki", Context.MODE_PRIVATE)

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

  /** The store's own records, before the on-device ones are added to the selection list. */
  private var storedProviders: List<AIProvider> = emptyList()
  private var storedModels: List<AIModel> = emptyList()

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

  /** A provider's model listing, as fetched from that provider. */
  sealed class ModelCatalogState {
    data object Loading : ModelCatalogState()
    data class Available(val models: List<com.awaki.agent.llm.CatalogModel>) : ModelCatalogState()
    data class Failed(val message: String) : ModelCatalogState()
  }

  /**
   * Catalogs by provider id, in memory only. This is a convenience for filling
   * in the model form — the durable record is the model the user saves, so a
   * provider that renames a model needs no migration.
   */
  private val _modelCatalogs = MutableStateFlow<Map<String, ModelCatalogState>>(emptyMap())
  val modelCatalogs: StateFlow<Map<String, ModelCatalogState>> = _modelCatalogs.asStateFlow()

  // Agent Permissions
  // Owned by a store like every other setting: seventeen switches that returned to
  // their shipped values on the next launch were the report that "changing a
  // permission doesn't stick".
  val permissionsStore = com.awaki.data.local.PermissionsStore(context)
  private val _permissions = MutableStateFlow(permissionsStore.get())
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

  // ---- Run & Build Center (install / build / test / run pipeline) ----

  val buildRunConfig: StateFlow<BuildRunConfig?> = buildRunController.config
  val buildRunStageStates: StateFlow<Map<BuildStageKind, BuildStageState>> = buildRunController.stageStates
  val buildRunLogs: StateFlow<List<BuildLogLine>> = buildRunController.logs
  val buildRunEndpoints: StateFlow<List<PreviewEndpoint>> = buildRunController.endpoints
  val buildRunPreviewRequest: StateFlow<Int> = buildRunController.previewRequest
  val buildRunPipelineRunning: StateFlow<Boolean> = buildRunController.pipelineRunning

  private val _buildRunDetectState = MutableStateFlow<BuildRunDetectState>(BuildRunDetectState.Idle)
  val buildRunDetectState: StateFlow<BuildRunDetectState> = _buildRunDetectState.asStateFlow()

  /** Model used for background tasks: the user's default, else the selected one. */
  private val _defaultTaskModelId = MutableStateFlow(providerStore?.getDefaultTaskModelId())
  val defaultTaskModelId: StateFlow<String?> = _defaultTaskModelId.asStateFlow()


  init {
    // A terminal tab exists from this moment, whatever the project scan below
    // takes or finds: with no project chosen the shell opens in the guest's home.
    openTerminalTabsFor(_activeProject.value)
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
    // An install, a delete or a server that came up changes the selectable list on its own.
    localAi?.let { local ->
      repositoryScope.launch { local.models.collect { publishProviderRecords() } }
      repositoryScope.launch { local.provider.collect { publishProviderRecords() } }
    }
    // Keep the Run & Build Center bound to whichever project is active.
    repositoryScope.launch {
      _activeProject.collect { project ->
        buildRunController.setProject(project.id, project.path)
      }
    }
  }

  // ---- Provider / model configuration (Task: AI provider system) ----

  private fun loadProviderConfiguration() {
    val store = providerStore
    if (store != null) {
      // A local model is selectable but not stored: its record exists only while the
      // on-device server is up, so the store needs telling before it discards a selection.
      store.reconcileSelection(
        autoSelectFallback = true,
        isKnownModel = { id -> store.ownsRecord(id) || localModelRecords.any { it.id == id } }
      )
      storedProviders = store.getProviders()
      storedModels = store.getModels()
    } else {
      // No durable store (tests/previews): operate in-memory.
      storedProviders = emptyList()
      storedModels = emptyList()
    }
    publishProviderRecords()
    _defaultTaskModelId.value = ensureDefaultTaskModel()
  }

  /**
   * The selectable list: what the durable store holds plus what this device serves.
   *
   * On-device records are derived on every publish rather than saved, because the address
   * and token that reach them belong to this run only. Everything downstream — the picker,
   * the connection test, the agent loop — reads one flat list and asks nothing about which
   * kind of model a record is.
   */
  private fun publishProviderRecords() {
    _providers.value = storedProviders + listOfNotNull(localAi?.provider?.value)
    _aiModels.value = storedModels + localModelRecords
    reconcileSelectedModel()
  }

  private val localModelRecords: List<AIModel> get() = localAi?.models?.value ?: emptyList()

  /** Keeps the selection when it still exists; otherwise falls back to the remembered or first model. */
  private fun reconcileSelectedModel() {
    val current = _selectedModel.value
    val remembered = providerStore?.getSelectedModelId()
    if (current != null && current.id == remembered && _aiModels.value.any { it.id == current.id }) return
    // The store holds what the user last chose — [selectModel] writes every choice
    // through — so it outranks whatever an earlier publish had to settle for. An
    // on-device model's record exists only while the local server is up, which is
    // after the first publish of a cold start: choosing it used to survive exactly
    // one restart, because the fallback below replaced it and persisted the
    // replacement. Now the choice is restored as soon as its record is back.
    val next = remembered?.let { id -> _aiModels.value.firstOrNull { it.id == id } }
      ?: _aiModels.value.firstOrNull()
    _selectedModel.value = next
    // A remembered on-device choice stays in the store even while this session has to
    // settle for a model that is actually up: writing the fallback back is what made
    // the choice come back as the first cloud model after a restart.
    val choiceIsOnDevice = remembered != null &&
      com.awaki.local.LocalAiRuntime.isLocalRecord(remembered)
    if (next != null && !choiceIsOnDevice && next.id != remembered) {
      providerStore?.selectModel(next.id) { id -> _aiModels.value.any { it.id == id } }
    }
  }

  /**
   * Ids for the records a user creates here. A wall-clock timestamp alone
   * repeats within a millisecond, and two records with one id silently replace
   * each other — the second save wins and the first provider or model is gone.
   */
  private val configRecordSeq = java.util.concurrent.atomic.AtomicLong()

  private fun nextConfigRecordId(prefix: String) =
    "$prefix-${System.currentTimeMillis()}-${configRecordSeq.incrementAndGet()}"

  fun saveProvider(name: String, baseUrl: String, protocol: com.awaki.settings.model.LLMProtocol, apiKey: String?, providerId: String? = null): AIProvider {
    val id = providerId ?: nextConfigRecordId("provider")
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
    storedProviders = providerStore?.getProviders() ?: (storedProviders.filterNot { it.id == id } + provider)
    publishProviderRecords()
    // A new URL or key makes both the last test and the last catalog stale.
    _connectionTests.update { it - id }
    _modelCatalogs.update { it - id }
    // A brand-new provider discovers its own models once, so the model form has
    // something to suggest. After that the listing is only ever re-fetched when
    // the user asks for it.
    if (providerId == null && provider.hasApiKey) loadModelCatalog(id, force = true)
    return provider
  }

  /**
   * Deletes a provider and all model records that belong to it. The selection
   * is reconciled afterwards: if the selected model was removed, another
   * available model is selected (or the selection becomes empty).
   */
  fun deleteProvider(providerId: String) {
    // The on-device provider is not a record: it exists while models are installed, and
    // taking those away is what the Local AI screen does.
    if (providerId == com.awaki.local.LocalAiRuntime.PROVIDER_ID) return
    providerStore?.deleteProvider(providerId)
    storedProviders = providerStore?.getProviders() ?: storedProviders.filterNot { it.id == providerId }
    storedModels = providerStore?.getModels() ?: storedModels.filterNot { it.providerId == providerId }
    publishProviderRecords()
    _connectionTests.update { it - providerId }
    _modelCatalogs.update { it - providerId }
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
  ): AIModel? {
    if (modelId.isBlank() || displayName.isBlank()) return null
    // An on-device model is configured from the Local AI screen, from the file it
    // installed from; a stored record pointing at a loopback port would outlive it.
    if (providerId == com.awaki.local.LocalAiRuntime.PROVIDER_ID) return null
    if (_providers.value.none { it.id == providerId }) return null
    val model = AIModel(
      id = recordId ?: nextConfigRecordId("model"),
      providerId = providerId,
      modelId = modelId.trim(),
      displayName = displayName.trim(),
      contextWindow = contextWindow,
      maxOutputTokens = maxOutputTokens,
      capabilities = capabilities,
      reasoning = reasoning
    )
    providerStore?.upsertModel(model)
    storedModels = providerStore?.getModels() ?: (storedModels.filterNot { it.id == model.id } + model)
    publishProviderRecords()
    if (_selectedModel.value == null) selectModel(model.id)
    return model
  }

  fun deleteModel(recordId: String) {
    if (com.awaki.local.LocalAiRuntime.isLocalRecord(recordId)) return
    providerStore?.deleteModel(recordId)
    storedModels = providerStore?.getModels() ?: storedModels.filterNot { it.id == recordId }
    publishProviderRecords()
  }

  /** Selects by unique model record id — never by model name. */
  fun selectModel(recordId: String) {
    val model = _aiModels.value.firstOrNull { it.id == recordId } ?: return
    providerStore?.selectModel(recordId) { id -> _aiModels.value.any { it.id == id } }
    _selectedModel.value = model
    _isModelSheetOpen.value = false
  }

  /** Real connection test: probe the endpoint first, then fall back to an actual model request. */
  fun getApiKey(providerId: String): String? {
    val local = localAi ?: return apiKeyOf(providerId)
    if (providerId == local.provider.value?.id) return local.endpoint.value?.apiKey
    return apiKeyOf(providerId)
  }

  private fun apiKeyOf(providerId: String): String? {
    val existing = _providers.value.firstOrNull { it.id == providerId } ?: return null
    if (!existing.hasApiKey) return null
    return providerStore?.getApiKey(providerId)
  }

  fun testProviderConnection(providerId: String) {
    val provider = _providers.value.firstOrNull { it.id == providerId } ?: return
    val apiKey = getApiKey(providerId)
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

  /**
   * Asks [providerId] for the models it actually serves, so the model form can
   * offer real ids with their limits instead of typed guesses. The listing
   * request is free — no tokens are billed — and its result is cached until the
   * provider's URL or key changes; [force] re-asks.
   */
  fun loadModelCatalog(providerId: String, force: Boolean = false) {
    val provider = _providers.value.firstOrNull { it.id == providerId } ?: return
    when (val current = _modelCatalogs.value[providerId]) {
      is ModelCatalogState.Loading -> return
      is ModelCatalogState.Available -> if (!force) return
      else -> Unit
    }
    val apiKey = getApiKey(providerId).orEmpty()
    if (apiKey.isBlank()) {
      // The key is saved with the provider; a brand-new one may not have one yet.
      _modelCatalogs.update { it + (providerId to ModelCatalogState.Failed("Save an API key for this provider to list its models.")) }
      return
    }
    _modelCatalogs.update { it + (providerId to ModelCatalogState.Loading) }
    repositoryScope.launch {
      val next = try {
        val listing = llmService.listModels(provider, apiKey)
        if (listing.ok) ModelCatalogState.Available(listing.models)
        else ModelCatalogState.Failed(listing.message)
      } catch (e: Exception) {
        ModelCatalogState.Failed(e.message ?: "The provider did not return its model list.")
      }
      _modelCatalogs.update { it + (providerId to next) }
    }
  }

  private fun resolveProviderForModel(model: AIModel): Pair<AIProvider, String>? {
    // An on-device model is a provider too: the answer is the loopback address and the
    // token that guard it, which is all the protocol client ever asks for.
    localAi?.connectionFor(model)?.let { return it }
    val provider = _providers.value.firstOrNull { it.id == model.providerId } ?: return null
    val apiKey = providerStore?.getApiKey(provider.id) ?: return null
    if (apiKey.isBlank()) return null
    return provider to apiKey
  }

  private fun loadActiveProjectState(project: Project) {
    // Each project keeps its own tab set, so switching workspaces never inherits
    // another project's tabs. This runs before the early exit below: the terminal
    // page needs a tab to show even when no project was ever chosen.
    openTerminalTabsFor(project)

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

  /**
   * Refreshes diffs/staging/history/status from real git, asynchronously.
   *
   * [forceHistoryReload] cancels the throttle and any in-flight history load so
   * the History tab reflects an operation immediately. It is meant for calls
   * after an operation rewrites history (reset / revert / cherry-pick / commit
   * / merge…): git is briefly unreadable while HEAD moves, so the normal
   * throttled loader can hold a stale list — which looks exactly like the
   * operation did nothing.
   */
  fun refreshDiffsAndGit(forceHistoryReload: Boolean = false) {
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
    if (forceHistoryReload) {
      // The previous load may be stuck in its retry loop against a repo that
      // was momentarily unreadable; start over so the new HEAD is fetched.
      gitHistoryJob?.cancel()
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
          android.util.Log.e("Awaki-Git", "git refresh failed", e)
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
      android.util.Log.w("Awaki-Git", "commit history unavailable after $attempts attempts")
    } catch (e: Exception) {
      lastHistoryLoadAt = System.currentTimeMillis()
      if (e !is kotlinx.coroutines.CancellationException) {
        android.util.Log.e("Awaki-Git", "commit history load failed", e)
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
        com.awaki.data.local.ProjectRegistryEntry(
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
      android.util.Log.e("Awaki-Projects", "Failed to create project", e)
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
        com.awaki.data.local.ProjectRegistryEntry(
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
      android.util.Log.e("Awaki-Projects", "Failed to import project", e)
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
        android.util.Log.e("Awaki-Sync", "sync failed", e)
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
    // Persist in the workspace's .awaki.json as well (it's authoritative).
    val entry = projectRegistry.all().firstOrNull { it.id == projectId }
    if (entry != null) {
      val dir = File(entry.rootPath)
      val cfg = com.awaki.workspace.filesystem.ProjectFileSystem.readProjectConfig(dir)
      if (cfg != null) {
        com.awaki.workspace.filesystem.ProjectFileSystem.writeProjectConfig(
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
      android.util.Log.e("Awaki-Projects", "Failed to import zip", e)
      _agentStatusText.value = "Could not import zip: ${e.message}"
      null
    } finally {
      tempDir?.deleteRecursively()
    }
    if (imported == null) return null
    projectRegistry.upsert(
      com.awaki.data.local.ProjectRegistryEntry(
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
          android.util.Log.e("Awaki-Projects", "Failed to migrate project ${entry.name}", e)
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
    if (com.awaki.editor.model.FileViewer.mustNotDecodeAsText(file.name)) return
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
        val model = resolveTaskModel()
        if (model == null) {
          _commitGenState.value = CommitGenState.Failed(
            if (_selectedModel.value == null) "No model configured — add a provider/model in Settings first."
            else "The selected model runs on this device — commit generation writes a multi-hundred-token message and needs a cloud model. Pick one as the task model in Settings."
          )
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
        android.util.Log.e("Awaki-Git", "commit message generation failed", e)
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
   * exactly one default). An on-device model is never a default: see
   * [resolveTaskModel].
   */
  private fun ensureDefaultTaskModel(): String? {
    val models = _aiModels.value
    val current = _defaultTaskModelId.value
    if (current != null && models.any { it.id == current && !onDeviceModel(it) }) return current
    val fallback = models.firstOrNull { !onDeviceModel(it) }?.id
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

  /**
   * A model that runs on this device is not a background worker. A diff explanation
   * sends 12k characters and asks for 800 tokens back, which on a phone CPU is minutes of
   * work on the one resident engine the user is waiting for an answer from.
   *
   * A chore therefore looks for the task model, then any other model whose provider is
   * configured with a key, and is refused with a reason when there is none. It is never
   * quietly handed to the phone.
   */
  private fun onDeviceModel(model: AIModel): Boolean =
    model.providerId == com.awaki.local.LocalAiRuntime.PROVIDER_ID

  private fun resolveTaskModel(): AIModel? {
    // The default task model is only usable when its provider is actually
    // configured with a key — otherwise background generation (session titles,
    // commit messages) would fail silently while the selected model works.
    _defaultTaskModelId.value?.let { id ->
      _aiModels.value.firstOrNull { it.id == id }?.let { model ->
        if (!onDeviceModel(model) && resolveProviderForModel(model) != null) return model
      }
    }
    // Never fall back to the on-device engine the chat is using, and never to a cloud
    // model whose provider has no key: another configured model answers instead.
    val selected = _selectedModel.value?.takeUnless { onDeviceModel(it) || resolveProviderForModel(it) == null }
    return selected
      ?: _aiModels.value.firstOrNull { !onDeviceModel(it) && resolveProviderForModel(it) != null }
  }

  /**
   * One-shot LLM text generation on the background task model; null means the
   * model answered with nothing usable. Throws when there is no model to run the
   * chore on—including when the only model the user has is the on-device
   * engine—so a caller never shows an empty panel without a reason, and otherwise
   * throws the real provider/network error.
   */
  private suspend fun requestLlmText(system: String, user: String, maxTokens: Int, disableReasoning: Boolean = false): String? {
    ensureDefaultTaskModel()
    val model = resolveTaskModel() ?: throw IllegalStateException(
      if (_selectedModel.value == null) "No model is configured — add a provider and model in Settings."
      else "Only the on-device model is configured. Background generation needs a cloud model: it would occupy the engine you are waiting on."
    )
    val connection = resolveProviderForModel(model)
      ?: throw IllegalStateException("Provider for model \"${model.displayName}\" has no API key configured.")
    val (provider, apiKey) = connection
    val collected = StringBuilder()
    llmService.streamChat(
      provider = provider, model = model, apiKey = apiKey,
      request = com.awaki.agent.llm.LlmRequest(
        messages = listOf(
          com.awaki.agent.llm.LlmMessage(com.awaki.agent.llm.LlmRole.SYSTEM, system),
          com.awaki.agent.llm.LlmMessage(com.awaki.agent.llm.LlmRole.USER, user)
        ),
        maxOutputTokens = maxTokens,
        disableReasoning = disableReasoning
      )
    ) { event ->
      if (event is com.awaki.agent.llm.LlmStreamEvent.Token) collected.append(event.text)
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
      android.util.Log.w("Awaki-Sessions", "title generation failed: ${e.message}")
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
        android.util.Log.e("Awaki-Git", "commit failed", e)
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
          refreshDiffsAndGit(forceHistoryReload = true)
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
      refreshDiffsAndGit(forceHistoryReload = true)
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
      refreshDiffsAndGit(forceHistoryReload = true)
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
      refreshDiffsAndGit(forceHistoryReload = true)
    }
  }

  fun resetToCommit(hash: String, mode: ResetMode) {
    repositoryScope.launch {
      _activeGitOperationText.value = "Resetting to $hash..."
      val res = gitManager.resetToCommit(_activeProject.value, hash, mode)
      if (res.success) {
        // The manager verifies HEAD actually moved and reports the new hash.
        _gitOperationFeedback.value = res.output.ifBlank { "Reset to $hash completed" }
        _gitError.value = null
      } else {
        _gitError.value = "Reset failed: ${res.output.ifBlank { "Unknown error" }}"
      }
      _activeGitOperationText.value = null
      refreshDiffsAndGit(forceHistoryReload = true)
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
      refreshDiffsAndGit(forceHistoryReload = true)
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
      refreshDiffsAndGit(forceHistoryReload = true)
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
      refreshDiffsAndGit(forceHistoryReload = true)
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
      refreshDiffsAndGit(forceHistoryReload = true)
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
      refreshDiffsAndGit(forceHistoryReload = true)
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
        system = "You are a senior code reviewer and software architect. Explain what this diff actually does: the intent behind it, the key logic and where it now lives, and the behavioural or architectural consequences the developer should know about. Read the diff instead of restating it line by line. Name real files and symbols. Say plainly what you cannot tell from the diff alone. Markdown, under 250 words.",
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
        system = "You are a demanding senior reviewer hunting real defects in a git diff, ordered by cost: correctness bugs, unhandled edge cases, races and concurrency, security holes, data loss, performance traps, then broken conventions. For each finding name the file and line, state concretely what breaks and under which input, mark it blocking or acceptable, and give the smallest fix. Never invent a problem to lengthen the list - if the diff is sound, say so and name what you checked.",
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
        system = "You are an expert software engineer reading someone else's commit. Explain what changed, why it was needed, and what it affects at runtime - behaviour, data, APIs or performance. Infer the motivation from the diff and the message, name the risk a reader should watch, and say what the commit left undone rather than inventing intent. Markdown, under 200 words.",
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
    repositoryScope.launch {
      // Unpacking a rootfs is minutes of network and I/O, and the screen is
      // usually off by the time it gets to the slow part.
      workRegistry?.begin(
        id = LINUX_BOOTSTRAP_WORK_ID,
        kind = com.awaki.background.WorkKind.BOOTSTRAP,
        label = "Setting up the Linux environment",
        detail = "Downloading and unpacking the Debian rootfs"
      )
      try {
        bootstrap.bootstrap()
      } finally {
        workRegistry?.end(LINUX_BOOTSTRAP_WORK_ID)
      }
    }
  }

  fun selectTerminalSession(id: String) {
    _activeTerminalSessionId.value = id
  }

  fun createTerminalSession(name: String = "bash") {
    val project = _activeProject.value
    val newSession = newTerminalTab(project, name)
    val tabs = terminalTabsByProject.getOrPut(project.id) { mutableListOf() }.also { it.add(newSession) }
    _terminalSessions.value = tabs.toList()
    ensurePtySession(newSession.id, name)
    _activeTerminalSessionId.value = newSession.id
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
      android.util.Log.e("Awaki-Terminal", "Failed to create PTY session", t)
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
      val resetSession = newTerminalTab(project)
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

  /**
   * Publishes this turn as live work for as long as it runs. A turn is the longest
   * thing the app does - minutes of streaming and commands - and it is the reason the
   * foreground service exists.
   */
  private fun beginAgentTurnWork(label: String) {
    val registry = workRegistry ?: return
    val id = "agent-turn-${System.currentTimeMillis()}"
    agentTurnWorkId = id
    registry.begin(
      id = id,
      kind = com.awaki.background.WorkKind.AGENT_TURN,
      label = label,
      detail = "Starting…",
      canceller = { stopAgentTurnFromBackground() }
    )
    // A turn that starts with the app on screen is the last moment a notification
    // permission can still be asked politely.
    backgroundExecution?.evaluateNotificationsPrompt()
  }

  private fun endAgentTurnWork() {
    val id = agentTurnWorkId ?: return
    agentTurnWorkId = null
    workRegistry?.end(id)
  }

  /**
   * Stops the running turn the way the chat's Stop button does. The notification's
   * action reaches the turn through here because the coroutine driving it belongs to
   * the UI layer that writes the transcript.
   */
  fun stopAgentTurnFromBackground() {
    cancelAgentGeneration()
    if (_pendingApproval.value != null) {
      // Never a denial: the user stopped the turn, they did not refuse the action.
      resolveApproval(allowed = false, termination = true)
    }
    interruptTerminal()
    agentTurnStopRequested?.invoke()
  }

  /** The commands live in the rootfs right now, newest not tracked - any will do. */
  private var runningCommands: List<String> = emptyList()

  /**
   * Keeps exactly one registry record for the scripted Linux commands: agent tool
   * calls, build stages and dev servers alike. They are child processes of this app,
   * so while one of them runs the process must not be allowed to be cached away.
   */
  private fun syncRunningCommands(commands: List<TerminalProcessManager.RunningCommand>) {
    runningCommands = commands.map { it.id }
    val registry = workRegistry ?: return
    if (commands.isEmpty()) {
      registry.end(TERMINAL_WORK_ID)
      return
    }
    // A build stage is a scripted command with a friendlier name in the notification.
    val isBuild = commands.any { it.id.startsWith("buildrun-") }
    val label = if (isBuild) "Build is running" else "Linux command is running"
    val head = commands.first().command.lineSequence().firstOrNull().orEmpty().take(60)
    val detail = if (commands.size == 1) head else "$head (+${commands.size - 1} more)"
    if (registry.has(TERMINAL_WORK_ID)) {
      registry.setProgress(TERMINAL_WORK_ID, label = label, detail = detail)
    } else {
      registry.begin(
        id = TERMINAL_WORK_ID,
        kind = com.awaki.background.WorkKind.TERMINAL,
        label = label,
        detail = detail,
        canceller = { runningCommands.firstOrNull()?.let { terminalManager.interrupt(it) } }
      )
    }
  }

  /** SIGKILLs one specific running tool call (the task keeps running). */
  fun cancelToolCall(callId: String) {
    agentRuntime.cancelToolCall(callId)
  }

  /** Retry re-executes a cancelled tool call; continue feeds a cancellation result. */
  fun resolveToolCancellation(callId: String, retry: Boolean) {
    agentRuntime.resolveToolCancellation(callId, retry)
  }

  /** Which specialists the user is holding still, keyed by their delegate call id. */
  val delegationPhases: StateFlow<Map<String, Boolean>> get() = agentRuntime.delegationPhases

  /**
   * Pauses or resumes one specialist by the id of the `delegate` call that started
   * it. It stops at the boundary after its current step and keeps everything it
   * has learned, so resuming sends it on from the exact step it reached. The agent
   * that delegated to it waits for the report either way — the turn is still
   * running, one of its specialists is just standing still.
   */
  fun setSubagentPaused(delegationId: String, paused: Boolean) {
    agentRuntime.setDelegationPaused(delegationId, paused)
  }

  // Permission handling
  fun updatePermissions(transform: (AgentPermissions) -> AgentPermissions) {
    _permissions.value = permissionsStore.update(transform)
  }

  fun requestApproval(approval: PendingApproval) {
    _approvalDeferred.value = false
    _pendingApproval.value = approval
    // A turn parked on a decision looks exactly like one that is grinding, so the
    // background notification has to say the user is the reason it stopped.
    agentTurnWorkId?.let { id ->
      workRegistry?.setAttention(
        id,
        needed = true,
        reason = "${approval.title} ${approval.command.take(70)}".trim()
      )
    }
  }

  /**
   * Handles the user closing the dialog without deciding.
   *
   * The request survives: the tool stays suspended and its chat card keeps the
   * Allow / Deny controls. Only the modal goes away.
   */
  fun deferApproval() {
    _approvalDeferred.value = true
  }

  /** Re-opens the modal for a request the user parked in the chat. */
  fun showApprovalDialog() {
    _approvalDeferred.value = false
  }

  /**
   * Applies a real decision. [termination] marks a request the turn never got an
   * answer to because the user stopped it — recorded as neutral, not as a denial.
   * [rationale] is the free text the user typed with it, surfaced to the model so
   * a denial says why instead of just "no".
   */
  fun resolveApproval(
    allowed: Boolean,
    answer: String? = null,
    rationale: String? = null,
    termination: Boolean = false
  ) {
    _pendingApproval.value = null
    _approvalDeferred.value = false
    agentTurnWorkId?.let { workRegistry?.setAttention(it, needed = false) }
    agentRuntime.resolvePendingApproval(allowed, answer, rationale, termination)
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

  /**
   * The selected model plus its provider credentials. Surfaces why it is missing
   * (and opens the model sheet) so the caller can simply not start.
   */
  private fun resolveAgentTarget(): Triple<AIModel, AIProvider, String>? {
    val model = _selectedModel.value
    if (model == null) {
      _agentStatusText.value = "No model selected — configure a provider and select a model in Settings first."
      _isModelSheetOpen.value = true
      return null
    }
    val connection = resolveProviderForModel(model)
    if (connection == null) {
      _agentStatusText.value = if (model.providerId == com.awaki.local.LocalAiRuntime.PROVIDER_ID) {
        localAi?.unavailableReason ?: "The on-device model is not answering right now."
      } else {
        "Provider \"${_providers.value.firstOrNull { it.id == model.providerId }?.name ?: model.providerId}\" is not configured with a valid API key."
      }
      _isModelSheetOpen.value = true
      return null
    }
    return Triple(model, connection.first, connection.second)
  }

  /**
   * The user's "compact now": summarize this session's stored conversation
   * without asking the model for a new turn.
   *
   * Refuses to run while a task is in flight — that turn owns the transcript, and
   * compacting underneath it would send the provider two different histories.
   *
   * Events go to [onEvent] rather than the shared turn stream: there is no turn
   * of its own here, and the UI decides where the compaction card belongs.
   */
  suspend fun compactConversationNow(
    sessionId: String,
    onEvent: (com.awaki.agent.model.AgentStreamEvent) -> Unit = {}
  ) {
    if (_isAgentWorking.value) return
    if (!_compactSettings.value.manualCompactEnabled) {
      _agentStatusText.value = "Manual compaction is turned off in Settings → Context & Compaction."
      return
    }
    val (model, provider, apiKey) = resolveAgentTarget() ?: return
    _isAgentWorking.value = true
    _agentStatusText.value = "Compacting the conversation…"
    try {
      agentRuntime.compactNow(
        project = _activeProject.value,
        provider = provider,
        model = model,
        apiKey = apiKey,
        sessionId = sessionId,
        history = chatStore.buildConversationMessages(sessionId, excludeLastUser = false),
        compactPolicy = _compactSettings.value.toPolicyConfig(model.contextWindow, model.maxOutputTokens),
        onTokenUsage = { usage -> publishContextUsage(usage) },
        onEvent = { event ->
          // Deliberately not the shared agent stream: that one writes events
          // into the running turn, and a manual compaction belongs to no turn.
          when (event) {
            is com.awaki.agent.model.AgentStreamEvent.Status -> _agentStatusText.value = event.text
            is com.awaki.agent.model.AgentStreamEvent.ContextCompacted ->
              _agentStatusText.value = event.boundary.describe()
            else -> Unit
          }
          onEvent(event)
        }
      )
    } finally {
      _isAgentWorking.value = false
    }
  }

  private suspend fun executeAgentTask(
    prompt: String,
    history: List<com.awaki.data.repository.ChatHistoryMessage>,
    resume: Boolean,
    sessionId: String?
  ) {
    val (model, provider, apiKey) = resolveAgentTarget() ?: return

    _isAgentWorking.value = true
    _agentStatusText.value = "Starting agent task..."
    _agentResponse.value = ""
    _agentWorkingDurationSeconds.value = 0
    resetContextUsage()
    beginAgentTurnWork(
      prompt.lineSequence().firstOrNull { it.isNotBlank() }?.take(60)?.ifBlank { null } ?: "Agent task"
    )

    val currentSession = _terminalSessions.value.firstOrNull { it.id == _activeTerminalSessionId.value }
      ?: _terminalSessions.value.firstOrNull()
      ?: newTerminalTab(_activeProject.value)

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
        compactPolicy = _compactSettings.value.toPolicyConfig(model.contextWindow, model.maxOutputTokens),
        onTokenUsage = { usage -> publishContextUsage(usage) },
        onRequestApproval = { approval -> requestApproval(approval) },
      onEvent = { event ->
        _agentEvents.tryEmit(event)
        when (event) {
          is com.awaki.agent.model.AgentStreamEvent.Status -> {
            _agentStatusText.value = event.text
            // The notification says what the turn is doing, not just that it exists.
            agentTurnWorkId?.let { workRegistry?.setProgress(it, detail = event.text.take(100)) }
          }
          is com.awaki.agent.model.AgentStreamEvent.Token -> _agentResponse.value += event.text
          is com.awaki.agent.model.AgentStreamEvent.ContextCompacted ->
            _agentStatusText.value = event.boundary.describe()
          is com.awaki.agent.model.AgentStreamEvent.ToolFinished -> _toolExecutions.update { list ->
            listOf(
              ToolExecution(
                id = "tool-${System.currentTimeMillis()}-${event.name}",
                type = com.awaki.agent.tool.toolTypeFor(event.name),
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
      _approvalDeferred.value = false
      // This is what lets the foreground service down: no turn, no notification,
      // no wake lock. It runs on every exit path, including a cancelled one.
      endAgentTurnWork()
    }
    refreshFiles()

    // Refresh active file if modified
    if (result.modifiedFiles.contains(_activeFile.value.path)) {
      val freshContent = fileSystem.readFile(_activeProject.value, _activeFile.value.path)
      _editorContent.value = freshContent
      _isEditorDirty.value = false
    }

    // The status line is one line, so it takes the run's first sentence rather
    // than the whole report the delegating agent received.
    val closing = result.summary.lineSequence().first().let {
      if (it.length > 140) it.take(139) + "…" else it
    }
    _agentStatusText.value =
      if (result.success) closing.ifBlank { "Task completed." } else "Task failed — $closing"
    // Mirror the agent's changes to the original folder (if enabled).
    maybeAutoSync(_activeProject.value)
  }

  // ---- Run & Build Center actions ----

  fun runBuildStage(kind: BuildStageKind) = buildRunController.runStage(kind)

  fun stopBuildStage(kind: BuildStageKind) = buildRunController.stopStage(kind)

  fun stopAllBuildStages() = buildRunController.stopAll()

  fun runBuildPipeline() = buildRunController.runPipeline()

  fun clearBuildRunLogs() = buildRunController.clearLogs()

  fun saveBuildRunStageCommand(kind: BuildStageKind, command: String, runPort: Int?) =
    buildRunController.saveManualCommand(kind, command, runPort)

  fun resetBuildRunCommands() = buildRunController.resetToDetected()

  /**
   * One-tap "Auto-configure": a deterministic local scan of the project files
   * first (always works, no network), then an optional LLM refinement using
   * the background-task model when a provider is configured.
   */
  fun autoConfigureBuildRun() {
    if (_buildRunDetectState.value is BuildRunDetectState.Running) return
    val project = _activeProject.value
    if (project.path.isBlank()) {
      _buildRunDetectState.value = BuildRunDetectState.Done("Select a project first.")
      return
    }
    _buildRunDetectState.value = BuildRunDetectState.Running
    repositoryScope.launch {
      val dir = File(project.path)
      val local = runCatching {
        withContext(Dispatchers.IO) { BuildRecipeDetector.detect(dir) }
      }.getOrNull()
      val localUsable = local != null && local.commands.values.any { it.isNotBlank() }
      if (local != null && localUsable) {
        buildRunController.applySuggestion(local, BuildRunConfigSource.LOCAL)
      }
      val refined = runCatching {
        val context = withContext(Dispatchers.IO) { BuildRecipeDetector.buildAiContext(dir) }
        val raw = requestLlmText(
          system = AI_BUILD_CONFIG_SYSTEM_PROMPT,
          user = AI_BUILD_CONFIG_USER_INSTRUCTIONS + "\n\n" + context,
          maxTokens = 800,
          disableReasoning = true
        )
        raw?.let { BuildRecipeDetector.parseAiResponse(it) }
      }.getOrNull()
      val message = when {
        refined != null -> {
          buildRunController.applySuggestion(refined, BuildRunConfigSource.AI)
          "AI refined the pipeline commands — review and edit if needed."
        }
        localUsable -> "Commands detected from the project files. AI refinement was unavailable."
        else -> "Couldn't detect a known project stack — configure the commands manually."
      }
      _buildRunDetectState.value = BuildRunDetectState.Done(message)
    }
  }

  companion object {
    /** Minimum gap between full commit-history reloads triggered by file-watcher churn. */
    private const val HISTORY_MIN_RELOAD_INTERVAL_MS = 1200L

    /** Most git output the app keeps in memory for one command. */
    private const val MAX_GIT_OUTPUT_CHARS = 2_000_000

    /** Registry record for the scripted Linux commands live right now. */
    private const val TERMINAL_WORK_ID = "linux-commands"

    /** The Linux home a terminal tab uses when it belongs to no project. */
    private const val GUEST_HOME_DIR = "/root"

    /** Registry record for the one-shot Debian rootfs bootstrap. */
    private const val LINUX_BOOTSTRAP_WORK_ID = "linux-bootstrap"

    /** System prompt for the Run & Build AI auto-configuration pass. */
    private const val AI_BUILD_CONFIG_SYSTEM_PROMPT =
      "You are a release engineer who configures install, build, test and run pipelines for real projects and gets them to work first time. " +
        "Every command you emit runs non-interactively, as root, in a minimal Debian Linux shell (proot) at the project root, with network access. " +
        "Debian's apt-get and the language's own package manager are the only installers; there is no TTY, no systemd, no docker and no browser, so nothing may wait for input or expect a service manager. " +
        "A command that cannot work in that shell is worse than an empty step. " +
        "Reply with strict JSON only - no prose, no markdown, no code fences."

    private const val AI_BUILD_CONFIG_USER_INSTRUCTIONS =
      "Analyse the project below and give the four commands a developer would run at the project root, in order: install dependencies, build, test, run the dev server. " +
        "Derive them from what the files actually show - the package.json scripts and which lockfile is present, the framework named in requirements.txt or pyproject.toml, the Gradle wrapper, the real entry point - never from a generic template. " +
        "Rules: one shell line per command, no placeholders or angle brackets, no chained experiments, nothing interactive. " +
        "Prefer the project's own scripts (npm run dev, ./gradlew assembleDebug) over calling the toolchain directly. " +
        "When the toolchain itself is not part of the project, install it inside 'install' non-interactively (apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y ...). " +
        "Leave a step an empty string only when the project genuinely has no such step. 'run' must be the foreground dev server and 'port' the port it binds, or null when nothing serves HTTP. " +
        "Reply with JSON exactly like: {\"install\":\"...\",\"build\":\"...\",\"test\":\"...\",\"run\":\"...\",\"port\":5173,\"summary\":\"one short sentence naming the stack and why these commands\"}."
  }
}
