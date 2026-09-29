package com.agentisco.workspace.buildrun

import com.agentisco.data.local.BuildRunConfigStore
import com.agentisco.data.model.TerminalLine
import com.agentisco.data.model.TerminalLineType
import com.agentisco.data.model.TerminalSession
import com.agentisco.workspace.terminal.TerminalProcessManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs the Run & Build pipeline of the active project. Every stage becomes a
 * real command inside the embedded Linux environment (through
 * [TerminalProcessManager]), its output streams into a console buffer, the run
 * stage's HTTP endpoint is detected from that output (or the configured port),
 * and a reachability probe asks the UI to open the live preview.
 */
class BuildRunController(
  private val terminalManager: TerminalProcessManager,
  private val scope: CoroutineScope,
  private val configStore: BuildRunConfigStore,
  private val probeClient: OkHttpClient = defaultProbeClient()
) {

  private val logIds = AtomicLong()
  private val runningSessions = mutableMapOf<BuildStageKind, String>()
  private val cancelledSessions = mutableSetOf<String>()
  private val probedPorts = mutableSetOf<Int>()
  private var probeJob: Job? = null
  private var probeSucceeded = false
  private var pipelineJob: Job? = null
  private var projectId = ""
  private var projectPath = ""

  private val _config = MutableStateFlow<BuildRunConfig?>(null)
  val config: StateFlow<BuildRunConfig?> = _config.asStateFlow()

  private val _stageStates = MutableStateFlow(freshStates(null))
  val stageStates: StateFlow<Map<BuildStageKind, BuildStageState>> = _stageStates.asStateFlow()

  private val _logs = MutableStateFlow<List<BuildLogLine>>(emptyList())
  val logs: StateFlow<List<BuildLogLine>> = _logs.asStateFlow()

  private val _endpoints = MutableStateFlow<List<PreviewEndpoint>>(emptyList())
  val endpoints: StateFlow<List<PreviewEndpoint>> = _endpoints.asStateFlow()

  private val _previewRequest = MutableStateFlow(0)

  /**
   * Increments when a fresh run-stage endpoint became reachable, so the screen
   * opens the preview exactly once per run.
   */
  val previewRequest: StateFlow<Int> = _previewRequest.asStateFlow()

  private val _pipelineRunning = MutableStateFlow(false)
  val pipelineRunning: StateFlow<Boolean> = _pipelineRunning.asStateFlow()

  // ---- Project binding --------------------------------------------------

  /** Rebinds to the active project: stops old runs, loads its stored config. */
  fun setProject(id: String, path: String) {
    if (path == projectPath) return
    stopAll()
    projectId = id
    projectPath = path
    _logs.value = emptyList()
    _endpoints.value = emptyList()
    val stored = path.takeIf { it.isNotBlank() }?.let { configStore.get(it) }
    _config.value = stored
    _stageStates.value = freshStates(stored)
    if (stored == null && path.isNotBlank()) {
      // First open of a project: pre-fill from the local detection pass.
      scope.launch {
        val dir = File(path)
        val suggestion = withContext(Dispatchers.IO) {
          if (dir.isDirectory) BuildRecipeDetector.detect(dir) else BuildRecipeSuggestion()
        }
        if (projectPath == path && _config.value == null &&
          suggestion.commands.values.any { it.isNotBlank() }
        ) {
          applySuggestion(suggestion, BuildRunConfigSource.LOCAL)
        }
      }
    }
  }

  // ---- Configuration -----------------------------------------------------

  /** Persists a manually edited stage command (and optional run preview port). */
  fun saveManualCommand(kind: BuildStageKind, command: String, runPort: Int?) {
    val current = _config.value ?: BuildRunConfig(projectPath = projectPath)
    val updated = current.copy(
      projectPath = current.projectPath.ifBlank { projectPath },
      commands = current.commands + (kind to command.trim()),
      runPort = if (kind == BuildStageKind.RUN) runPort else current.runPort,
      source = BuildRunConfigSource.MANUAL,
      updatedAt = System.currentTimeMillis()
    )
    applyConfig(updated)
  }

  /** Restores the last auto-detected snapshot of the pipeline. */
  fun resetToDetected() {
    val current = _config.value ?: return
    val detected = current.detectedCommands ?: return
    val updated = current.copy(
      commands = detected,
      runPort = current.detectedRunPort ?: current.runPort,
      source = current.detectedSource ?: BuildRunConfigSource.LOCAL,
      updatedAt = System.currentTimeMillis()
    )
    applyConfig(updated)
  }

  /** Applies a detection result (local or AI), keeping commands it did not cover. */
  fun applySuggestion(suggestion: BuildRecipeSuggestion, source: BuildRunConfigSource) {
    val current = _config.value ?: BuildRunConfig(projectPath = projectPath)
    val merged = current.commands.toMutableMap()
    suggestion.commands.forEach { (kind, command) ->
      if (command.isNotBlank()) merged[kind] = command.trim()
    }
    val runPort = suggestion.runPort ?: current.runPort
    val updated = current.copy(
      projectPath = current.projectPath.ifBlank { projectPath },
      commands = merged,
      runPort = runPort,
      source = source,
      detectedCommands = merged.filterValues { it.isNotBlank() },
      detectedRunPort = runPort,
      detectedSource = source,
      updatedAt = System.currentTimeMillis()
    )
    applyConfig(updated)
  }

  private fun applyConfig(config: BuildRunConfig) {
    if (config.projectPath.isBlank()) return
    _config.value = config
    configStore.save(config)
    BuildStageKind.entries.forEach { kind ->
      _stageStates.updateStage(kind) { state ->
        when {
          !config.isConfigured(kind) ->
            state.copy(status = BuildStageStatus.NOT_CONFIGURED, exitCode = null, durationMs = null)
          state.status == BuildStageStatus.NOT_CONFIGURED ->
            state.copy(status = BuildStageStatus.IDLE)
          else -> state
        }
      }
    }
  }

  // ---- Execution ---------------------------------------------------------

  /** Runs one stage on user request. */
  fun runStage(kind: BuildStageKind) {
    val config = _config.value ?: return
    if (!config.isConfigured(kind)) return
    if (runningSessions.containsKey(kind)) return
    if (_pipelineRunning.value) return
    scope.launch { executeStage(kind) }
  }

  /** Stops one running stage (the run server included). */
  fun stopStage(kind: BuildStageKind) {
    val sessionId = runningSessions[kind] ?: return
    cancelledSessions.add(sessionId)
    terminalManager.interrupt(sessionId)
    if (kind == BuildStageKind.RUN) {
      probeJob?.cancel()
      probeJob = null
    }
  }

  /** Stops everything: the pipeline and every running stage. */
  fun stopAll() {
    runningSessions.keys.toList().forEach { stopStage(it) }
    pipelineJob?.cancel()
    pipelineJob = null
    _pipelineRunning.value = false
  }

  fun clearLogs() {
    _logs.value = emptyList()
  }

  /**
   * The Vercel-style "deploy" flow: install → build → test sequentially
   * (stopping at the first failure), then start the run server and leave it
   * alive. A live run server is replaced, mirroring a redeploy.
   */
  fun runPipeline() {
    if (_pipelineRunning.value) return
    val config = _config.value ?: return
    if (runningSessions.keys.any { it != BuildStageKind.RUN }) return
    _pipelineRunning.value = true
    pipelineJob = scope.launch {
      try {
        if (runningSessions.containsKey(BuildStageKind.RUN)) {
          stopStage(BuildStageKind.RUN)
          var waited = 0
          while (runningSessions.containsKey(BuildStageKind.RUN) && waited < 3000) {
            delay(100)
            waited += 100
          }
        }
        var ok = true
        for (kind in listOf(BuildStageKind.INSTALL, BuildStageKind.BUILD, BuildStageKind.TEST)) {
          if (!config.isConfigured(kind)) continue
          val exit = executeStage(kind)
          if (exit != 0) {
            ok = false
            break
          }
        }
        if (ok && config.isConfigured(BuildStageKind.RUN)) {
          scope.launch { executeStage(BuildStageKind.RUN) }
        }
      } finally {
        _pipelineRunning.value = false
      }
    }
  }

  private suspend fun executeStage(kind: BuildStageKind): Int {
    if (runningSessions.containsKey(kind)) return -1
    val config = _config.value ?: return -1
    val command = config.commandFor(kind)
    if (command.isBlank()) return -1
    val path = projectPath
    if (path.isBlank()) return -1

    val sessionId = "buildrun-${kind.name.lowercase()}-${System.currentTimeMillis()}"
    runningSessions[kind] = sessionId
    if (kind == BuildStageKind.RUN) {
      probeJob?.cancel()
      probeJob = null
      probeSucceeded = false
      probedPorts.clear()
      _endpoints.value = config.runPort?.let { listOf(PreviewEndpoint("127.0.0.1", it)) } ?: emptyList()
      maybeStartProbe()
    }
    val startedAt = System.currentTimeMillis()
    _stageStates.updateStage(kind) {
      it.copy(
        status = BuildStageStatus.RUNNING,
        exitCode = null,
        startedAt = startedAt,
        finishedAt = 0L,
        durationMs = null
      )
    }
    appendLog(kind, "── ${kind.label.lowercase()} · started ──", LogTone.INFO)
    appendLog(kind, "\$ $command", LogTone.MUTED)

    var exit = -1
    var cancelled = false
    try {
      exit = terminalManager.executeCommand(
        session = TerminalSession(id = sessionId, name = "buildrun", currentDir = path, projectId = projectId),
        command = command,
        onLine = { line -> handleTerminalLine(kind, line) },
        projectDir = File(path)
      )
    } catch (e: CancellationException) {
      cancelled = true
      throw e
    } finally {
      runningSessions.remove(kind)
      if (kind == BuildStageKind.RUN) {
        probeJob?.cancel()
        probeJob = null
      }
      if (cancelledSessions.remove(sessionId)) cancelled = true
      if (path == projectPath) {
        val finishedAt = System.currentTimeMillis()
        val duration = (finishedAt - startedAt).coerceAtLeast(0L)
        val status = when {
          cancelled -> BuildStageStatus.STOPPED
          exit == 0 -> BuildStageStatus.PASSED
          else -> BuildStageStatus.FAILED
        }
        _stageStates.updateStage(kind) {
          it.copy(status = status, exitCode = exit, finishedAt = finishedAt, durationMs = duration)
        }
        appendLog(kind, summaryLine(kind, status, exit, duration), toneFor(status))
      }
    }
    return exit
  }

  // ---- Output handling ---------------------------------------------------

  private fun handleTerminalLine(kind: BuildStageKind, line: TerminalLine) {
    val text = stripAnsi(line.text)
    if (text.isBlank()) return
    appendLog(kind, text, if (line.type == TerminalLineType.STDERR) LogTone.ERROR else LogTone.NORMAL)
    if (kind == BuildStageKind.RUN) {
      val parsed = BuildRunPortParser.parse(text)
      if (parsed.isNotEmpty()) {
        _endpoints.value = (_endpoints.value + parsed).distinctBy { it.port }
        maybeStartProbe()
      }
    }
  }

  private fun appendLog(kind: BuildStageKind?, text: String, tone: LogTone) {
    val line = BuildLogLine(logIds.incrementAndGet(), kind, text, tone)
    _logs.update { current ->
      if (current.size >= MAX_LOG_LINES) current.drop(current.size - MAX_LOG_LINES + 1) + line
      else current + line
    }
  }

  // ---- Preview probing ---------------------------------------------------

  /**
   * Waits (bounded) until one candidate port actually answers, then asks the
   * screen to open the preview. Only one endpoint auto-opens per run.
   */
  private fun maybeStartProbe() {
    if (probeSucceeded || probeJob?.isActive == true) return
    val candidates = (_endpoints.value.map { it.port } + listOfNotNull(_config.value?.runPort)).distinct()
    val port = candidates.firstOrNull { it !in probedPorts } ?: return
    probedPorts.add(port)
    probeJob = scope.launch {
      repeat(PROBE_ATTEMPTS) {
        if (probeReachable(port)) {
          probeSucceeded = true
          _previewRequest.update { it + 1 }
          return@launch
        }
        delay(PROBE_INTERVAL_MS)
      }
    }
  }

  private suspend fun probeReachable(port: Int): Boolean = withContext(Dispatchers.IO) {
    val request = Request.Builder().url("http://127.0.0.1:$port/").get().build()
    try {
      probeClient.newCall(request).execute().use { true }
    } catch (_: Exception) {
      false
    }
  }

  private fun summaryLine(kind: BuildStageKind, status: BuildStageStatus, exit: Int, durationMs: Long): String =
    when (status) {
      BuildStageStatus.PASSED -> "✓ ${kind.label.lowercase()} finished in ${formatDuration(durationMs)} (exit 0)"
      BuildStageStatus.FAILED -> "✗ ${kind.label.lowercase()} failed after ${formatDuration(durationMs)} (exit $exit)"
      BuildStageStatus.STOPPED -> "■ ${kind.label.lowercase()} stopped"
      else -> "${kind.label.lowercase()} exited (${formatDuration(durationMs)})"
    }

  private fun toneFor(status: BuildStageStatus): LogTone = when (status) {
    BuildStageStatus.PASSED -> LogTone.SUCCESS
    BuildStageStatus.FAILED -> LogTone.ERROR
    BuildStageStatus.STOPPED -> LogTone.MUTED
    else -> LogTone.INFO
  }

  companion object {
    private const val MAX_LOG_LINES = 2000
    private const val PROBE_ATTEMPTS = 30
    private const val PROBE_INTERVAL_MS = 1000L

    private val ANSI_REGEX = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    /** Strips ANSI SGR/escape sequences so raw command output reads cleanly. */
    internal fun stripAnsi(text: String): String = ANSI_REGEX.replace(text, "")

    /** "12.4s" / "2m 05s" — used by the console summaries and the screen chips. */
    fun formatDuration(ms: Long): String =
      if (ms < 10_000) String.format(Locale.US, "%.1fs", ms / 1000.0)
      else String.format(Locale.US, "%dm %02ds", ms / 60_000, (ms / 1000) % 60)
  }
}

private fun MutableStateFlow<Map<BuildStageKind, BuildStageState>>.updateStage(
  kind: BuildStageKind,
  transform: (BuildStageState) -> BuildStageState
) {
  update { states ->
    val base = states[kind] ?: BuildStageState(kind)
    states.toMutableMap().also { it[kind] = transform(base) }
  }
}

private fun freshStates(config: BuildRunConfig?): Map<BuildStageKind, BuildStageState> =
  BuildStageKind.entries.associateWith { kind ->
    BuildStageState(
      kind = kind,
      status = if (config?.isConfigured(kind) == true) BuildStageStatus.IDLE else BuildStageStatus.NOT_CONFIGURED
    )
  }

private fun defaultProbeClient(): OkHttpClient = OkHttpClient.Builder()
  .connectTimeout(1500, TimeUnit.MILLISECONDS)
  .readTimeout(1500, TimeUnit.MILLISECONDS)
  .callTimeout(2500, TimeUnit.MILLISECONDS)
  .build()
