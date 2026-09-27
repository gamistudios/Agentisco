package com.agentisco.agent.compact

import com.agentisco.agent.llm.LlmMessage
import com.agentisco.agent.llm.LlmUsage

/**
 * Public entry points of the conversation-compaction subsystem.
 *
 * Files in this package:
 *  - `CompactPolicy.kt`      thresholds, decision reasons, circuit breaker
 *  - `CompactTokens.kt`      token estimation + the live usage meter
 *  - `CompactRounds.kt`      assistant-started round grouping
 *  - `Microcompact.kt`       local tool-result clearing (no LLM)
 *  - `CompactPrompt.kt`      summary prompt + post-summary continuation message
 *  - `ManualCompact.kt`      round selection, transcript assembly, boundary
 *  - `CompactCoordinator.kt` runs both tiers over one agent run
 *
 * The persisted chat transcript is never rewritten: only the message list that
 * goes to the provider is compacted, so the UI keeps every message the user
 * has ever seen.
 */
object Compact {

  /**
   * One-shot tier 1 over a transcript: clears old tool payloads locally when
   * the conversation is under pressure or has been idle for a long time.
   */
  fun microcompact(
    messages: List<LlmMessage>,
    config: CompactPolicyConfig = CompactPolicyConfig(),
    currentTokens: Int = estimateMessageTokens(messages),
    idleMillis: Long = 0
  ): MicrocompactResult = Microcompact.run(messages, config, currentTokens, idleMillis)

  /** Round-aware decision on whether the transcript must be summarized. */
  fun shouldAutoCompact(
    messages: List<LlmMessage>,
    config: CompactPolicyConfig = CompactPolicyConfig(),
    usage: LlmUsage? = null,
    consecutiveFailures: Int = 0
  ): CompactDecision = CompactPolicy.evaluate(
    messages = messages,
    rounds = groupByAssistantStartedRounds(messages),
    config = config,
    usage = usage,
    consecutiveFailures = consecutiveFailures
  )

  /** The user's explicit "compact now": same plan, forced trigger. */
  fun plan(messages: List<LlmMessage>, config: CompactPolicyConfig = CompactPolicyConfig()): ManualCompactPlan =
    ManualCompact.plan(messages, config)

  /** Replaces the summarized turns with the summary plus a continuation note. */
  fun applySummary(
    plan: ManualCompactPlan,
    summary: String,
    context: CompactSummaryContext
  ): List<LlmMessage> = ManualCompact.applySummary(plan, summary, context)
}
