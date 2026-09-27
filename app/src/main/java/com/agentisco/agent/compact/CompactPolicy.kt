package com.agentisco.agent.compact

import com.agentisco.agent.llm.LlmMessage
import com.agentisco.agent.llm.LlmRole
import com.agentisco.agent.llm.LlmUsage
import kotlin.math.max
import kotlin.math.roundToInt

/** Why the auto-compact decision came out the way it did. */
enum class CompactReason {
  /** The user turned compaction off. */
  DISABLED,

  /** Too little conversation to be worth summarizing. */
  NOT_ENOUGH_MESSAGES,

  /** Consecutive failures tripped the circuit breaker. */
  CIRCUIT_BREAKER,

  /** Still comfortably below the threshold. */
  BELOW_THRESHOLD,

  /** The conversation crossed the threshold — compact now. */
  ABOVE_THRESHOLD,

  /** Forced by the user (the /compact command). */
  MANUAL
}

/**
 * Thresholds that decide *when* the transcript is compacted, and the circuit
 * breaker that stops a failing model from burning money on every turn.
 *
 * The model never sees this configuration: it is an internal budget, derived
 * from the model's real context window and output reserve.
 */
data class CompactPolicyConfig(
  /** The model's context window. */
  val contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
  /**
   * How much of the window is held back for the model's own answer. Capped at
   * [MAX_OUTPUT_RESERVE] for the preflight check so a huge configured
   * max-output budget cannot shrink the input budget to nothing.
   */
  val outputReserve: Int = DEFAULT_OUTPUT_RESERVE,
  /**
   * Headroom left between the compact threshold and the real limit, so the
   * summarization request itself (and the tokens streamed after it) always
   * fit.
   */
  val buffer: Int = DEFAULT_BUFFER,
  /** Consecutive failed compactions before compaction is disabled. */
  val maxConsecutiveFailures: Int = DEFAULT_MAX_CONSECUTIVE_FAILURES,
  /** Compaction runs on the tool loop as well as between user turns. */
  val enabled: Boolean = true,
  /**
   * When the transcript crosses this share of the effective window
   * (100 = as soon as the buffer is reached), compaction runs.
   */
  val thresholdPercent: Int = DEFAULT_THRESHOLD_PERCENT,
  /** Keep this many assistant-started rounds verbatim after summarizing. */
  val keepRecentRounds: Int = DEFAULT_KEEP_RECENT_ROUNDS,
  /** Trigger the cheap local tool-result clearing before the LLM summary. */
  val microcompactEnabled: Boolean = true,
  /** Only clear the payloads of these tools (the bulky, re-readable ones). */
  val microcompactTools: Set<String> = DEFAULT_MICROCOMPACT_TOOLS,
  /** Tool-result groups (one assistant turn's batch) kept verbatim. */
  val keepRecentToolResultGroups: Int = DEFAULT_KEEP_RECENT_TOOL_RESULT_GROUPS,
  /** Local clearing must save at least this many tokens, or it is reverted. */
  val microcompactMinSavedTokens: Int = DEFAULT_MICROCOMPACT_MIN_SAVED,
  /** Wall-clock idle time after which old tool results are cleared anyway. */
  val microcompactIdleMillis: Long = DEFAULT_MICROCOMPACT_IDLE_MILLIS,
  /** Clear failed tool results too (off by default: errors are cheap and matter). */
  val microcompactClearErrors: Boolean = false,
  /** Minimum rounds/messages a conversation needs before auto-compaction. */
  val minRounds: Int = DEFAULT_MIN_ROUNDS
) {
  /** Tokens available for the conversation itself. */
  val effectiveContextWindow: Int
    get() = max(MIN_EFFECTIVE_WINDOW, contextWindow - outputReserve.coerceAtMost(MAX_OUTPUT_RESERVE))

  /**
   * The token count at which the transcript is summarized.
   *
   * Capped by the model's real window: on a small model the floors above can
   * mathematically exceed it, and a threshold outside the window is never
   * reached — the request would be rejected before compaction ever ran.
   */
  val thresholdTokens: Int
    get() = minOf(
      contextWindow.coerceAtLeast(1),
      max(
        MIN_THRESHOLD,
        ((effectiveContextWindow - buffer) * thresholdPercent.coerceIn(10, 200)) / 100
      )
    )

  companion object {
    const val DEFAULT_CONTEXT_WINDOW = 200_000
    const val DEFAULT_OUTPUT_RESERVE = 32_000
    const val MAX_OUTPUT_RESERVE = 21_000
    const val DEFAULT_BUFFER = 13_000
    const val DEFAULT_THRESHOLD_PERCENT = 100
    const val DEFAULT_MAX_CONSECUTIVE_FAILURES = 3
    const val DEFAULT_KEEP_RECENT_ROUNDS = 2
    const val DEFAULT_KEEP_RECENT_TOOL_RESULT_GROUPS = 5
    const val DEFAULT_MICROCOMPACT_MIN_SAVED = 256
    const val DEFAULT_MICROCOMPACT_IDLE_MILLIS = 60L * 60L * 1000L
    const val DEFAULT_MIN_ROUNDS = 2

    private const val MIN_EFFECTIVE_WINDOW = 16_000
    private const val MIN_THRESHOLD = 8_000

    /** Read-heavy, output-heavy tools whose payload is safe to drop later. */
    val DEFAULT_MICROCOMPACT_TOOLS = setOf(
      "read_file",
      "read_files",
      "list_files",
      "directory_tree",
      "search_files",
      "regex_search",
      "glob_files",
      "file_info",
      "run_command",
      "build",
      "test",
      "git_status",
      "git_diff"
    )

    /** Builds the policy for a model, honouring its declared limits. */
    fun forModel(
      contextWindow: Int?,
      maxOutputTokens: Int?,
      overrides: CompactPolicyConfig = CompactPolicyConfig()
    ): CompactPolicyConfig = overrides.copy(
      contextWindow = (contextWindow ?: overrides.contextWindow).coerceAtLeast(1),
      outputReserve = (maxOutputTokens ?: overrides.outputReserve).coerceAtLeast(0)
    )
  }
}

/** The full outcome of one auto-compact evaluation, including the numbers. */
data class CompactDecision(
  val shouldCompact: Boolean,
  val reason: CompactReason,
  /** Tokens the next request is estimated (or reported) to occupy. */
  val estimatedTokens: Int,
  val thresholdTokens: Int,
  val contextWindow: Int,
  /** How many compactable assistant-started rounds the transcript has. */
  val roundCount: Int,
  val hasAssistantMessage: Boolean,
  val consecutiveFailures: Int
) {
  /** Share of the threshold; 100 means "compact on the next turn". */
  val pressurePercent: Int
    get() = if (thresholdTokens <= 0) 0 else (estimatedTokens.toLong() * 100 / thresholdTokens).toInt()
}

/**
 * Decides whether the transcript must be summarized before the next request.
 *
 * Three guards keep a compaction from being useless or destructive:
 *  - the conversation must have at least two compactable rounds and one
 *    assistant message, otherwise there is nothing to summarize;
 *  - a conversation that is still below the threshold is left alone;
 *  - after [CompactPolicyConfig.maxConsecutiveFailures] failures the circuit
 *    breaker opens and compaction is skipped until it is reset, so a model that
 *    cannot summarize never stalls every turn.
 */
object CompactPolicy {

  /**
   * @param messages the transcript the next request would carry.
   * @param rounds compactable assistant-started rounds in that transcript.
   * @param usage the provider's own usage report, when the protocol gave one.
   * @param consecutiveFailures failed compactions since the last success.
   */
  fun evaluate(
    messages: List<LlmMessage>,
    rounds: List<CompactRound>,
    config: CompactPolicyConfig = CompactPolicyConfig(),
    usage: LlmUsage? = null,
    consecutiveFailures: Int = 0
  ): CompactDecision {
    val estimated = tokenCount(usage, messages)
    val threshold = config.thresholdTokens
    val hasAssistant = messages.any { it.role == LlmRole.ASSISTANT }
    fun decide(reason: CompactReason) = CompactDecision(
      shouldCompact = reason == CompactReason.ABOVE_THRESHOLD || reason == CompactReason.MANUAL,
      reason = reason,
      estimatedTokens = estimated,
      thresholdTokens = threshold,
      contextWindow = config.contextWindow,
      roundCount = rounds.size,
      hasAssistantMessage = hasAssistant,
      consecutiveFailures = consecutiveFailures
    )
    return when {
      !config.enabled -> decide(CompactReason.DISABLED)
      rounds.size < config.minRounds || !hasAssistant -> decide(CompactReason.NOT_ENOUGH_MESSAGES)
      consecutiveFailures >= config.maxConsecutiveFailures -> decide(CompactReason.CIRCUIT_BREAKER)
      estimated >= threshold -> decide(CompactReason.ABOVE_THRESHOLD)
      else -> decide(CompactReason.BELOW_THRESHOLD)
    }
  }

  /** Forces a decision regardless of size — the user's /compact request. */
  fun forceCompact(
    messages: List<LlmMessage>,
    rounds: List<CompactRound>,
    config: CompactPolicyConfig = CompactPolicyConfig(),
    usage: LlmUsage? = null
  ): CompactDecision = CompactDecision(
    shouldCompact = true,
    reason = CompactReason.MANUAL,
    estimatedTokens = tokenCount(usage, messages),
    thresholdTokens = config.thresholdTokens,
    contextWindow = config.contextWindow,
    roundCount = rounds.size,
    hasAssistantMessage = messages.any { it.role == LlmRole.ASSISTANT },
    consecutiveFailures = 0
  )

  /** Share of the threshold the local microcompact waits for before clearing. */
  fun microcompactTriggerTokens(config: CompactPolicyConfig = CompactPolicyConfig()): Int =
    ((config.thresholdTokens * MICROCOMPACT_TRIGGER_PERCENT) / 100.0).roundToInt() -
    MICROCOMPACT_TRIGGER_BUFFER

  /** Why the cheap local pass should run, if at all. */
  enum class MicrocompactTrigger { DISABLED, TOKEN_PRESSURE, IDLE, NONE }

  fun microcompactTrigger(
    currentTokens: Int,
    config: CompactPolicyConfig = CompactPolicyConfig(),
    idleMillis: Long = 0
  ): MicrocompactTrigger = when {
    !config.enabled || !config.microcompactEnabled -> MicrocompactTrigger.DISABLED
    currentTokens >= microcompactTriggerTokens(config) -> MicrocompactTrigger.TOKEN_PRESSURE
    idleMillis >= config.microcompactIdleMillis -> MicrocompactTrigger.IDLE
    else -> MicrocompactTrigger.NONE
  }

  /** 90 % of the threshold — compact locally before paying for a summary. */
  private const val MICROCOMPACT_TRIGGER_PERCENT = 90
  private const val MICROCOMPACT_TRIGGER_BUFFER = 2_000

  /**
   * The provider's own number whenever it reported a readable one; otherwise
   * the local estimate. A usage object whose counters are all absent must not
   * become "0 tokens", which would read as an empty conversation.
   */
  private fun tokenCount(usage: LlmUsage?, messages: List<LlmMessage>): Int =
    usage?.let(::reportedTokens)?.takeIf { it > 0 } ?: estimateMessageTokens(messages)
}
