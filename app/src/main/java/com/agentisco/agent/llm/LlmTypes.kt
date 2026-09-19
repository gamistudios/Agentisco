package com.agentisco.agent.llm

/** Roles for LLM conversation messages. */
enum class LlmRole { SYSTEM, USER, ASSISTANT, TOOL }

/** Binary input is kept raw until a provider adapter serializes its wire format. */
data class LlmInlineData(
  val mimeType: String,
  val data: ByteArray
)

enum class LlmFinishReason { STOP, LENGTH, TOOL_CALLS, CONTENT_FILTER, ERROR, OTHER }

data class LlmUsage(
  val inputTokens: Int? = null,
  val outputTokens: Int? = null,
  val cachedInputTokens: Int? = null,
  val reasoningTokens: Int? = null,
  val totalTokens: Int? = null
)

data class LlmMessage(
  val role: LlmRole,
  val content: String,
  val toolCalls: List<LlmToolCall> = emptyList(),
  val toolCallId: String? = null,
  val toolName: String? = null,
  val inlineData: List<LlmInlineData> = emptyList(),
  val finishReason: LlmFinishReason? = null,
  val usage: LlmUsage? = null
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
  val temperature: Double? = null,
  val topP: Double? = null,
  val topK: Int? = null,
  val stopSequences: List<String> = emptyList(),
  val responseMimeType: String? = null,
  val responseJsonSchema: String? = null,
  /** Background tasks (commit msgs, titles) suppress the model's reasoning mode. */
  val disableReasoning: Boolean = false,
  /**
   * Stable conversation identity, used only by stateful protocols (Gemini
   * Interactions) to resume a server-side chain instead of resending history.
   * Stateless protocols ignore it and rebuild context from [messages].
   */
  val conversationKey: String? = null
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
