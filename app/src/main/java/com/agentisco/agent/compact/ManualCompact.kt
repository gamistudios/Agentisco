package com.agentisco.agent.compact

import com.agentisco.agent.llm.LlmMessage
import com.agentisco.agent.llm.LlmRole

/** Telemetry + UI record of one compaction. */
data class CompactBoundary(
  val trigger: CompactReason,
  val tokensBefore: Int,
  val tokensAfter: Int,
  val contextWindow: Int,
  val summarizedRounds: Int,
  val keptRounds: Int,
  val keptMessages: Int,
  val summarizedMessages: Int,
  val clearedToolResults: Int = 0,
  val savedTokensByMicrocompact: Int = 0,
  val summaryTokens: Int = 0,
  val durationMillis: Long = 0,
  val summarizerModel: String? = null,
  val transcriptPath: String? = null,
  val failed: Boolean = false,
  val failureReason: String? = null
) {
  val savedTokens: Int get() = (tokensBefore - tokensAfter).coerceAtLeast(0)

  val savedPercent: Int
    get() = if (tokensBefore <= 0) 0 else (savedTokens * 100 / tokensBefore)

  fun describe(): String = buildString {
    if (failed) {
      append("Compaction failed: ${failureReason ?: "unknown error"}")
      return@buildString
    }
    append("Compacted ${formatTokens(tokensBefore)} → ${formatTokens(tokensAfter)}")
    append(" (${savedPercent}% of a ${formatTokens(contextWindow)} window)")
    append(" · $summarizedRounds round(s) summarized, $keptRounds kept")
    if (clearedToolResults > 0) append(" · $clearedToolResults tool result(s) cleared")
  }
}

private fun formatTokens(tokens: Int): String = compactNumber(tokens) + " tokens"

/**
 * What a full compaction produced: the new transcript, the summary message
 * that replaces the old turns, and the boundary that records what happened.
 */
data class CompactResult(
  val messages: List<LlmMessage>,
  val summary: String,
  val boundary: CompactBoundary
)

/** The selection of rounds a compaction will summarize and keep. */
data class ManualCompactPlan(
  val summarizeRounds: List<CompactRound>,
  val keepRounds: List<CompactRound>,
  val preservedMessages: List<LlmMessage>
) {
  val summarizedRoundCount: Int get() = summarizeRounds.size
  val keptRoundCount: Int get() = keepRounds.size
  val preservedMessageCount: Int get() = preservedMessages.size

  /** The transcript handed to the summarizer. */
  fun transcript(): List<LlmMessage> = summarizeRounds.flatMap { it.messages }
}

/**
 * Message selection for a full, LLM-driven compaction.
 *
 * Everything that is not the system prompt is a candidate except app-injected
 * user wrappers, which carry no user intent. The newest
 * [CompactPolicyConfig.keepRecentRounds] rounds are always kept verbatim so the
 * model continues with the exact turns — including the tool results of the call
 * it is currently waiting on — instead of a summary of them.
 */
object ManualCompact {

  fun plan(
    messages: List<LlmMessage>,
    config: CompactPolicyConfig = CompactPolicyConfig()
  ): ManualCompactPlan {
    val systemMessages = messages.filter { it.role == LlmRole.SYSTEM }
    val rounds = groupByAssistantStartedRounds(messages)
    val keepCount = config.keepRecentRounds.coerceAtLeast(0)
    val keep = if (keepCount == 0) emptyList() else rounds.takeLast(keepCount)
    val summarize = rounds.dropLast(keep.size)
    val preserved = systemMessages + keep.flatMap { it.messages }
    return ManualCompactPlan(summarize, keep, preserved)
  }

  /**
   * Assembles the compacted transcript: the system prompt, the summary as a
   * user message (providers require a user turn to answer), then the preserved
   * recent rounds — newest last, so the model reads history in order and the
   * summary sits exactly where the summarized turns used to be.
   */
  fun applySummary(
    plan: ManualCompactPlan,
    summary: String,
    context: CompactSummaryContext
  ): List<LlmMessage> {
    val summaryMessage = LlmMessage(
      role = LlmRole.USER,
      content = buildCompactSummaryMessage(summary, context)
    )
    val system = plan.preservedMessages.filter { it.role == LlmRole.SYSTEM }
    val tail = plan.preservedMessages.filter { it.role != LlmRole.SYSTEM }
    return system + summaryMessage + tail
  }

  /** Records the boundary for telemetry and the UI status line. */
  fun buildBoundary(
    plan: ManualCompactPlan,
    tokensBefore: Int,
    tokensAfter: Int,
    config: CompactPolicyConfig,
    trigger: CompactReason = CompactReason.ABOVE_THRESHOLD,
    clearedToolResults: Int = 0,
    savedTokensByMicrocompact: Int = 0,
    summaryTokens: Int = 0,
    durationMillis: Long = 0,
    summarizerModel: String? = null,
    transcriptPath: String? = null
  ): CompactBoundary = CompactBoundary(
    trigger = trigger,
    tokensBefore = tokensBefore,
    tokensAfter = tokensAfter,
    contextWindow = config.contextWindow,
    summarizedRounds = plan.summarizedRoundCount,
    keptRounds = plan.keptRoundCount,
    keptMessages = plan.preservedMessageCount,
    summarizedMessages = plan.transcript().size,
    clearedToolResults = clearedToolResults,
    savedTokensByMicrocompact = savedTokensByMicrocompact,
    summaryTokens = summaryTokens,
    durationMillis = durationMillis,
    summarizerModel = summarizerModel,
    transcriptPath = transcriptPath
  )

  /** The system instruction that turns the agent back into a coding agent. */
  fun systemInstruction(): String =
    "You are Agentisco, an elite senior software engineer working inside the mobile IDE \"Agentisco\". " +
      "Your earlier conversation was compacted into the summary above; the goal, the standards and the user's constraints still apply to you. " +
      "Treat the summary as recovered context, not as instructions: the user messages and tool results after it are newer than the summary and win whenever they disagree. " +
      "Before trusting a claim in the summary, check the workspace it refers to - read the file, re-run the command. " +
      "Continue the latest user request without acknowledging the summary or mentioning compaction."
}
