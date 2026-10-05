package com.awaki.agent.compact

import com.awaki.agent.llm.LlmMessage
import com.awaki.agent.llm.LlmUsage
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Average characters per token across the code / JSON / prose mix that makes
 * up an agent conversation. Deliberately conservative (≈3.6): under-counting
 * would compact too late and let the next request be rejected.
 */
const val ESTIMATED_TOKEN_CHAR_DIVISOR = 3.6

/** Wire-format overhead (role, separators, ids) charged per message. */
private const val PER_MESSAGE_OVERHEAD_TOKENS = 4

/** Converts a character count into a token estimate, always rounding up. */
fun charsToTokens(chars: Int): Int =
  if (chars <= 0) 0 else ceil(chars / ESTIMATED_TOKEN_CHAR_DIVISOR).toInt()

fun estimateTokens(text: String): Int = charsToTokens(text.length)

/**
 * Token cost of one message, including tool-call arguments — they travel
 * inside the request body and were historically under-counted, which made
 * tool-heavy turns look far cheaper than they are.
 */
fun estimateMessageTokens(message: LlmMessage): Int {
  var chars = message.content.length
  message.toolCalls.forEach { call -> chars += call.name.length + call.argumentsJson.length }
  // Inline media is billed as image/video tokens, not characters; an eighth of
  // the payload is the usual base64-expanded approximation.
  message.inlineData.forEach { media -> chars += media.data.size / 8 }
  return charsToTokens(chars) + PER_MESSAGE_OVERHEAD_TOKENS
}

fun estimateMessageTokens(messages: List<LlmMessage>): Int =
  messages.sumOf { estimateMessageTokens(it) }

/**
 * The provider's own count, when it sent one. `total_tokens` is preferred;
 * otherwise input + output is used, and a report with no readable counter is
 * ignored so an absent value never masquerades as "zero tokens".
 */
fun reportedTokens(usage: LlmUsage): Int {
  usage.totalTokens?.takeIf { it > 0 }?.let { return it }
  val summed = (usage.inputTokens ?: 0) + (usage.outputTokens ?: 0)
  return summed.takeIf { it > 0 } ?: 0
}

/** Where the number in [ContextTokenUsage.usedTokens] came from. */
enum class TokenUsageSource { ESTIMATED, PROVIDER }

/**
 * Live view of how much of the model's context window the next request will
 * occupy. Rendered next to the composer's model/permission dropdowns and
 * recomputed on every streamed token, compaction and provider usage report.
 */
data class ContextTokenUsage(
  val usedTokens: Int = 0,
  val contextWindow: Int = CompactPolicyConfig.DEFAULT_CONTEXT_WINDOW,
  val thresholdTokens: Int = 0,
  val source: TokenUsageSource = TokenUsageSource.ESTIMATED,
  /** Tool results the last microcompact removed from the request payload. */
  val clearedToolResults: Int = 0
) {
  /** Share of the whole window in percent (may exceed 100 before compaction). */
  val percent: Int
    get() = if (contextWindow <= 0) 0 else (usedTokens.toLong() * 100 / contextWindow).toInt()

  /** Share of the compact threshold; at 100 the next turn compacts. */
  val pressurePercent: Int
    get() = if (thresholdTokens <= 0) 0 else (usedTokens.toLong() * 100 / thresholdTokens).toInt()

  val isAboveThreshold: Boolean get() = thresholdTokens > 0 && usedTokens >= thresholdTokens

  /** Short label for the composer chip, e.g. "37% context". */
  fun label(): String = "${percent.coerceAtLeast(0)}% context"

  fun detail(): String = "${compactNumber(usedTokens)} / ${compactNumber(contextWindow)} tokens"

  companion object {
    fun empty(contextWindow: Int = CompactPolicyConfig.DEFAULT_CONTEXT_WINDOW): ContextTokenUsage =
      ContextTokenUsage(
        contextWindow = contextWindow,
        thresholdTokens = CompactPolicyConfig(contextWindow = contextWindow).thresholdTokens
      )
  }
}

/** "812", "14.2k", "1.3M" — keeps the chip to a fixed, narrow width. */
fun compactNumber(value: Int): String = when {
  value >= 1_000_000 -> scaledValue(value / 1_000_000.0) + "M"
  value >= 1_000 -> scaledValue(value / 1_000.0) + "k"
  else -> value.toString()
}

/** One decimal while it costs nothing ("37.5k"); whole once it would mislead ("100k"). */
private fun scaledValue(value: Double): String {
  val rounded = (value * 10).roundToInt() / 10.0
  return if (rounded >= 100.0) rounded.roundToInt().toString()
  else String.format(java.util.Locale.US, "%.1f", rounded)
}

/**
 * Incremental token meter for one agent run.
 *
 * A full re-estimate on every streamed token would be O(conversation) per
 * token, so the base (history + tool payloads) is measured once and streamed
 * assistant text is added character-wise. The first provider-reported usage
 * wins afterwards — it is authoritative, and includes hidden reasoning tokens
 * no local estimate can see.
 */
class CompactTokenMeter(
  private val contextWindow: Int,
  private val policy: CompactPolicyConfig = CompactPolicyConfig(contextWindow = contextWindow)
) {
  private var baseTokens = 0
  private var pendingChars = 0
  private var clearedToolResults = 0
  private var source = TokenUsageSource.ESTIMATED

  /** Establishes the baseline from the exact messages that will be sent. */
  fun setBase(messages: List<LlmMessage>) {
    baseTokens = estimateMessageTokens(messages)
    pendingChars = 0
    source = TokenUsageSource.ESTIMATED
  }

  fun setBaseTokens(tokens: Int) {
    baseTokens = max(0, tokens)
    pendingChars = 0
  }

  /** Adds streamed assistant text without re-walking the whole transcript. */
  fun appendAssistantText(text: String) {
    if (text.isNotEmpty()) pendingChars += text.length
  }

  /**
   * Folds the completed response into the baseline. Provider usage (input +
   * output) replaces the local estimate when the protocol reported it.
   */
  fun onResponseCompleted(usage: LlmUsage?) {
    val reported = usage?.let(::reportedTokens) ?: 0
    if (reported > 0) {
      baseTokens = reported
      source = TokenUsageSource.PROVIDER
    } else {
      baseTokens += charsToTokens(pendingChars)
      source = TokenUsageSource.ESTIMATED
    }
    pendingChars = 0
  }
  /** Records a local tool-result clearing so the chip can show what it saved. */
  fun onMicrocompacted(clearedResults: Int, savedTokens: Int) {
    if (clearedResults <= 0) return
    clearedToolResults += clearedResults
    baseTokens = max(0, baseTokens - savedTokens)
    pendingChars = 0
  }

  /** Adopts the post-compaction size reported by the compaction pass. */
  fun onCompacted(usage: ContextTokenUsage) {
    baseTokens = max(0, usage.usedTokens)
    clearedToolResults = usage.clearedToolResults
    pendingChars = 0
    source = usage.source
  }

  fun snapshot(): ContextTokenUsage = ContextTokenUsage(
    usedTokens = max(0, baseTokens + charsToTokens(pendingChars)),
    contextWindow = contextWindow,
    thresholdTokens = policy.thresholdTokens,
    source = source,
    clearedToolResults = clearedToolResults
  )
}
