package com.agentisco.settings.model

/**
 * Wire/API protocol used to talk to a provider. Providers are pure connection
 * configuration; models reference their provider and inherit the transport.
 */
enum class LLMProtocol(val displayName: String) {
  OPENAI_CHAT_COMPLETIONS("OpenAI Chat Completions"),
  ANTHROPIC_MESSAGES("Anthropic Messages");

  companion object {
    fun fromName(raw: String?): LLMProtocol? = entries.firstOrNull { it.name == raw || it.displayName == raw }
  }
}

/**
 * Structured capabilities the runtime actually branches on.
 * Only include flags the LLM clients / agent runtime really consume.
 */
data class ModelCapabilities(
  val tools: Boolean = false,
  val images: Boolean = false,
  val parallelToolCalls: Boolean = false,
  val promptCaching: Boolean = false,
  val interleavedReasoning: Boolean = false,
  val maxTokensParameter: Boolean = true,
  val streaming: Boolean = true
)

/** Optional reasoning configuration, honored only when [ModelCapabilities.tools]-style requests enable it. */
data class ReasoningConfig(
  val enabled: Boolean = false,
  val effort: String = "medium" // provider-specific: low | medium | high
)

/**
 * A selectable model record. [id] is the unique record key (stable across
 * persistence); the same [modelId] identifier may exist under many providers —
 * each record is a distinct selectable model because it inherits a different
 * provider connection.
 */
data class AIModel(
  val id: String,
  val providerId: String,
  val modelId: String,
  val displayName: String,
  val contextWindow: Int? = null,
  val maxOutputTokens: Int? = null,
  val capabilities: ModelCapabilities = ModelCapabilities(),
  val reasoning: ReasoningConfig? = null
)

/**
 * A provider configuration: where/how we communicate with an API.
 * Credentials are held in secure app-private storage, referenced by [id],
 * never embedded here as ordinary UI state.
 */
data class AIProvider(
  val id: String,
  val name: String,
  val baseUrl: String,
  val protocol: LLMProtocol,
  val hasApiKey: Boolean = false
)
