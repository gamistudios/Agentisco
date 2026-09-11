package com.agentisco.agent.llm

/** Roles for LLM conversation messages. */
enum class LlmRole { SYSTEM, USER, ASSISTANT, TOOL }

data class LlmMessage(
  val role: LlmRole,
  val content: String,
  val toolCalls: List<LlmToolCall> = emptyList(),
  val toolCallId: String? = null,
  val toolName: String? = null
)

/** A tool exposed to the model. The model may only request it; execution stays app-owned. */
data class LlmToolSpec(
  val name: String,
  val description: String,
  val parametersJsonSchema: String
)

data class LlmToolCall(
  val id: String,
  val name: String,
  val argumentsJson: String
)

data class LlmRequest(
  val messages: List<LlmMessage>,
  val tools: List<LlmToolSpec> = emptyList(),
  val maxOutputTokens: Int? = null,
  val temperature: Double? = null
)

/** Errors surfaced by the LLM communication layer; user-facing, never containing secrets. */
enum class LlmErrorKind {
  AUTH, PERMISSION, NOT_FOUND, RATE_LIMIT, SERVER,
  NETWORK, TIMEOUT, INVALID_RESPONSE, UNSUPPORTED, CANCELLED
}

class LlmException(
  message: String,
  val kind: LlmErrorKind,
  val httpCode: Int? = null,
  cause: Throwable? = null
) : Exception(message, cause)

/** Events emitted while a model response streams in. Never synthesized from a completed payload. */
sealed class LlmStreamEvent {
  data object Started : LlmStreamEvent()
  data class Token(val text: String) : LlmStreamEvent()
  data class ReasoningToken(val text: String) : LlmStreamEvent()
  data class ToolCallRequested(val call: LlmToolCall) : LlmStreamEvent()
  data class Completed(val message: LlmMessage) : LlmStreamEvent()
  data object Interrupted : LlmStreamEvent()
  data class Failed(val error: LlmException) : LlmStreamEvent()
}
