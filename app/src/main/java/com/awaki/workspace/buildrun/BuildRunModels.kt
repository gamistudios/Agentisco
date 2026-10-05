package com.awaki.workspace.buildrun

/** The four stages of a project's Run & Build pipeline. */
enum class BuildStageKind(val label: String) {
  INSTALL("Install"),
  BUILD("Build"),
  TEST("Test"),
  RUN("Run")
}

/** How a Run & Build configuration came to be. */
enum class BuildRunConfigSource(val label: String) {
  AI("Auto-configured · AI"),
  LOCAL("Auto-configured · detected"),
  MANUAL("Edited manually")
}

/**
 * Persisted Run & Build configuration of one project: one command per stage,
 * plus the optional preview port of the run (server) stage, provenance, and the
 * last auto-detected snapshot so the user can reset to it.
 */
data class BuildRunConfig(
  val projectPath: String = "",
  val commands: Map<BuildStageKind, String> = emptyMap(),
  val runPort: Int? = null,
  val source: BuildRunConfigSource = BuildRunConfigSource.MANUAL,
  val detectedCommands: Map<BuildStageKind, String>? = null,
  val detectedRunPort: Int? = null,
  val detectedSource: BuildRunConfigSource? = null,
  val updatedAt: Long = 0L
) {
  fun commandFor(kind: BuildStageKind): String = commands[kind].orEmpty()
  fun isConfigured(kind: BuildStageKind): Boolean = commandFor(kind).isNotBlank()
}

/** Runtime status of a single stage. */
enum class BuildStageStatus {
  NOT_CONFIGURED, IDLE, RUNNING, PASSED, FAILED, STOPPED
}

/** Live state of one stage, shown next to its card. */
data class BuildStageState(
  val kind: BuildStageKind,
  val status: BuildStageStatus = BuildStageStatus.NOT_CONFIGURED,
  val exitCode: Int? = null,
  val startedAt: Long = 0L,
  val finishedAt: Long = 0L,
  val durationMs: Long? = null
)

/** How a console line should read. */
enum class LogTone { NORMAL, ERROR, SUCCESS, INFO, MUTED }

/** One line of the Run & Build console. */
data class BuildLogLine(
  val id: Long,
  val stage: BuildStageKind?,
  val text: String,
  val tone: LogTone = LogTone.NORMAL
)

/** A local HTTP endpoint the run stage appears to serve. */
data class PreviewEndpoint(val host: String, val port: Int) {
  val url: String get() = "http://$host:$port"
}

/** Result of a recipe detection pass (local heuristics or AI refinement). */
data class BuildRecipeSuggestion(
  val commands: Map<BuildStageKind, String> = emptyMap(),
  val runPort: Int? = null,
  val notes: String = ""
)

/** State of the one-tap auto-configure action on the Run & Build screen. */
sealed interface BuildRunDetectState {
  data object Idle : BuildRunDetectState
  data object Running : BuildRunDetectState
  data class Done(val message: String) : BuildRunDetectState
}
