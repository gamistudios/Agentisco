package com.awaki.agent.model

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

  /**
   * A protected operation needs explicit user approval before it can run, or the
   * agent is asking the user a question ([isQuestion] with [options]).
   */
  data class ApprovalRequested(
    val approvalId: String,
    val command: String,
    val title: String,
    val impact: String,
    val options: List<String> = emptyList(),
    val isQuestion: Boolean = false
  ) : AgentStreamEvent()

  data class ApprovalResolved(
    val approvalId: String,
    val allowed: Boolean,
    val answer: String? = null,
    /** Free text the user typed with the decision — usually why a refusal happened. */
    val rationale: String? = null,
    /**
     * True when the turn was stopped while the request was open, so the user
     * never actually chose. Recorded as a neutral "stalled" card, never a denial.
     */
    val terminated: Boolean = false
  ) : AgentStreamEvent()

  data class Completed(val summary: String) : AgentStreamEvent()

  data class Failed(val message: String) : AgentStreamEvent()

  data class Cancelled(val message: String = "Task cancelled by user") : AgentStreamEvent()

  /** Streamed partial text must be discarded (e.g. before an automatic retry). */
  data class TextReset(val reason: String) : AgentStreamEvent()

  /** The user cancelled a specific running tool call (SIGKILL); awaits a retry/continue decision. */
  data class ToolCancelled(val callId: String, val name: String) : AgentStreamEvent()

  /**
   * The transcript sent to the provider was compacted: older turns (through
   * [summarizedThroughRowId]) are now represented by [summary] instead of
   * their full text. The persisted chat is untouched — this only explains why
   * the model's context changed, and carries the summary for inspection.
   */
  data class ContextCompacted(
    val boundary: com.awaki.agent.compact.CompactBoundary,
    val summarizedThroughRowId: Long,
    val summary: String = ""
  ) : AgentStreamEvent()

  /**
   * One action by a delegated agent, belonging to the `delegate` call that started
   * it.
   *
   * [delegationId] is the parent's tool-call id, so the chat can hang this card
   * under the delegation it came from instead of in the mainline: a specialist's
   * work is shown as its own work, in order, while it happens - and the parent's
   * transcript still only ever gains the report.
   */
  data class DelegationActivity(
    val delegationId: String,
    val agent: String,
    val event: AgentStreamEvent
  ) : AgentStreamEvent()
}
