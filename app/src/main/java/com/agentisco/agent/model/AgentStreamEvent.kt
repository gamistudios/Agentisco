package com.agentisco.agent.model

/**
 * Real events emitted by the agent runtime while a task executes. The UI
 * renders these chronologically as one hierarchical live stream — there are no
 * placeholder/simulated activities anywhere in the pipeline.
 */
sealed class AgentStreamEvent {
  /** Task accepted and starting. */
  data class TaskStarted(val prompt: String) : AgentStreamEvent()

  /** Concise user-facing status/progress summary (never raw chain-of-thought). */
  data class Status(val text: String) : AgentStreamEvent()

  /** Streamed assistant text chunk. */
  data class Token(val text: String) : AgentStreamEvent()

  /** Streamed reasoning/"thinking" chunk (models with reasoning enabled). */
  data class ReasoningToken(val text: String) : AgentStreamEvent()

  /** The model requested a tool; arguments are validated by the runtime.
   *  [callId] disambiguates parallel batched calls with the same tool name. */
  data class ToolStarted(val name: String, val argsJson: String, val callId: String = "") : AgentStreamEvent()

  /** Structured result of a tool execution. */
  data class ToolFinished(
    val name: String,
    val success: Boolean,
    val summary: String,
    val detail: String,
    val exitCode: Int?,
    val callId: String = ""
  ) : AgentStreamEvent()

  /** A protected operation needs explicit user approval before it can run. */
  data class ApprovalRequested(
    val approvalId: String,
    val command: String,
    val title: String,
    val impact: String
  ) : AgentStreamEvent()

  data class ApprovalResolved(val approvalId: String, val allowed: Boolean) : AgentStreamEvent()

  data class Completed(val summary: String) : AgentStreamEvent()

  data class Failed(val message: String) : AgentStreamEvent()

  data class Cancelled(val message: String = "Task cancelled by user") : AgentStreamEvent()

  /** Streamed partial text must be discarded (e.g. before an automatic retry). */
  data class TextReset(val reason: String) : AgentStreamEvent()

  /** The user cancelled a specific running tool call (SIGKILL); awaits a retry/continue decision. */
  data class ToolCancelled(val callId: String, val name: String) : AgentStreamEvent()
}
