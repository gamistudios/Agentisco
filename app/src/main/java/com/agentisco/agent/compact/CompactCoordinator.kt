package com.agentisco.agent.compact

import com.agentisco.agent.llm.LlmMessage
import com.agentisco.agent.llm.LlmRequest
import com.agentisco.agent.llm.LlmRole
import com.agentisco.agent.llm.LlmService
import com.agentisco.agent.llm.LlmStreamEvent
import com.agentisco.settings.model.AIModel
import com.agentisco.settings.model.AIProvider
import android.util.Log

/**
 * Runs the two-tier compaction over one agent run's in-memory transcript.
 *
 * Tier 1 ([Microcompact]) clears old tool payloads locally — no model call, no
 * user-visible change. Tier 2 ([ManualCompact]) asks the model for a structured
 * summary and replaces the older turns with it.
 *
 * The chat transcript in SQLite is never rewritten: only the message list sent
 * to the provider is compressed, so the UI keeps every message and tool card
 * the user has ever seen, and a later turn re-sends the compressed history
 * (summary + preserved tail) until it is compacted again.
 */
class CompactCoordinator(
  private val llmService: LlmService,
  private val configProvider: () -> CompactPolicyConfig = { CompactPolicyConfig() }
) {
  private companion object {
    const val TAG = "AgentiscoCompact"
    /** A summary request is a background task: no tools, no reasoning mode. */
    const val SUMMARY_MAX_OUTPUT_TOKENS = 8_192
  }

  /** Consecutive failures since the last success — the circuit breaker's input. */
  private var consecutiveFailures = 0

  fun resetCircuitBreaker() {
    consecutiveFailures = 0
  }

  /**
   * Tier 1. Returns the (possibly rewritten) transcript plus what it saved.
   * Cheap enough to attempt on every iteration of the tool loop.
   */
  fun microcompact(
    messages: List<LlmMessage>,
    currentTokens: Int,
    idleMillis: Long = 0
  ): MicrocompactResult = Microcompact.run(
    messages = messages,
    config = configProvider(),
    currentTokens = currentTokens,
    idleMillis = idleMillis
  )

  /**
   * Tier 2. Summarizes [plan]'s older rounds and returns the new transcript,
   * or null when compaction should be skipped (not enough material, or the
   * circuit breaker is open — in which case the failure counter advances).
   */
  suspend fun compactWithModel(
    plan: ManualCompactPlan,
    provider: AIProvider,
    model: AIModel,
    apiKey: String,
    tokensBefore: Int,
    clearedToolResults: Int = 0,
    savedTokensByMicrocompact: Int = 0,
    trigger: CompactReason = CompactReason.ABOVE_THRESHOLD,
    customInstructions: String? = null,
    onProgress: (String) -> Unit = {}
  ): CompactResult? {
    val config = configProvider()
    val transcript = plan.transcript()
    if (transcript.isEmpty() || plan.summarizedRoundCount == 0) {
      onProgress("Nothing older to compact yet.")
      return null
    }
    if (consecutiveFailures >= config.maxConsecutiveFailures) {
      onProgress("Compaction paused after ${consecutiveFailures} failed attempts — the model could not produce a summary.")
      return null
    }

    val started = System.currentTimeMillis()
    val prompt = buildCompactPrompt(
      transcript = transcript,
      customInstructions = customInstructions,
      preserveRecentMessages = plan.preservedMessageCount
    )
    onProgress("Compacting ${plan.summarizedRoundCount} earlier turn(s) into a summary…")

    val summary = try {
      requestSummary(provider, model, apiKey, prompt)
    } catch (e: Exception) {
      consecutiveFailures++
      Log.w(TAG, "compaction request failed (${e.message})")
      onProgress("Compaction failed: ${e.message ?: e.javaClass.simpleName}")
      return null
    }

    if (summary.isBlank()) {
      consecutiveFailures++
      onProgress("Compaction returned an empty summary — keeping the full history.")
      return null
    }
    consecutiveFailures = 0

    val cleaned = cleanCompactSummary(summary)
    val preserved = plan.preservedMessages
    val messages = ManualCompact.applySummary(
      plan = plan,
      summary = cleaned,
      context = CompactSummaryContext(
        preservedRecentCount = plan.preservedMessageCount,
        tokensBefore = tokensBefore,
        // Filled in by the caller's meter once it re-measures the new list.
        tokensAfter = estimateMessageTokens(preserved) + estimateTokens(cleaned),
        trigger = trigger,
        clearedToolResults = clearedToolResults,
        summarizerModel = model.displayName
      )
    )
    val boundary = ManualCompact.buildBoundary(
      plan = plan,
      tokensBefore = tokensBefore,
      tokensAfter = estimateMessageTokens(messages),
      config = config,
      trigger = trigger,
      clearedToolResults = clearedToolResults,
      savedTokensByMicrocompact = savedTokensByMicrocompact,
      summaryTokens = estimateTokens(cleaned),
      durationMillis = System.currentTimeMillis() - started,
      summarizerModel = model.displayName
    )
    Log.i(TAG, boundary.describe())
    return CompactResult(messages, cleaned, boundary)
  }

  /**
   * Sends the summarization request with **no tools attached**, so the model
   * can only answer in text. Reasoning is disabled for the same reason: the
   * agent's own reasoning config does not apply to a bookkeeping request.
   */
  private suspend fun requestSummary(
    provider: AIProvider,
    model: AIModel,
    apiKey: String,
    prompt: String
  ): String {
    val collected = StringBuilder()
    llmService.streamChat(
      provider = provider,
      model = model,
      apiKey = apiKey,
      request = LlmRequest(
        messages = listOf(
          LlmMessage(LlmRole.SYSTEM, ManualCompact.systemInstruction()),
          LlmMessage(LlmRole.USER, prompt)
        ),
        tools = emptyList(),
        maxOutputTokens = SUMMARY_MAX_OUTPUT_TOKENS,
        disableReasoning = true
      )
    ) { event ->
      if (event is LlmStreamEvent.Token) collected.append(event.text)
    }
    return collected.toString().trim()
  }
}
