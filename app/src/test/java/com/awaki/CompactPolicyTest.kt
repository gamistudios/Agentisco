package com.awaki

import com.awaki.agent.compact.CompactPolicy
import com.awaki.agent.compact.CompactPolicyConfig
import com.awaki.agent.compact.CompactReason
import com.awaki.agent.compact.estimateMessageTokens
import com.awaki.agent.compact.estimateTokens
import com.awaki.agent.compact.groupByAssistantStartedRounds
import com.awaki.agent.llm.LlmMessage
import com.awaki.agent.llm.LlmRole
import com.awaki.agent.llm.LlmToolCall
import com.awaki.agent.llm.LlmUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactPolicyTest {

  private fun policy(
    contextWindow: Int = 200_000,
    outputReserve: Int = 32_000,
    buffer: Int = 13_000,
    thresholdPercent: Int = 100,
    maxConsecutiveFailures: Int = 3
  ) = CompactPolicyConfig(
    contextWindow = contextWindow,
    outputReserve = outputReserve,
    buffer = buffer,
    thresholdPercent = thresholdPercent,
    maxConsecutiveFailures = maxConsecutiveFailures
  )

  private val large = buildString {
    repeat(60_000) { append("lorem ipsum dolor ") }
  }

  private fun transcript(): List<LlmMessage> = listOf(
    LlmMessage(LlmRole.SYSTEM, "system prompt"),
    LlmMessage(LlmRole.USER, "first request"),
    LlmMessage(LlmRole.ASSISTANT, "answer one"),
    LlmMessage(LlmRole.USER, "second request"),
    LlmMessage(LlmRole.ASSISTANT, "answer two", toolCalls = listOf(LlmToolCall("call_1", "read_file", "{\"path\":\"a.kt\"}"))),
    LlmMessage(LlmRole.TOOL, large, toolCallId = "call_1", toolName = "read_file")
  )

  /** A short conversation: comfortably inside any window. */
  private fun smallTranscript(): List<LlmMessage> = listOf(
    LlmMessage(LlmRole.SYSTEM, "system prompt"),
    LlmMessage(LlmRole.USER, "first request"),
    LlmMessage(LlmRole.ASSISTANT, "answer one"),
    LlmMessage(LlmRole.USER, "second request"),
    LlmMessage(
      LlmRole.ASSISTANT, "answer two",
      toolCalls = listOf(LlmToolCall("call_1", "read_file", "{\"path\":\"a.kt\"}"))
    ),
    LlmMessage(LlmRole.TOOL, "1: fun main() {}", toolCallId = "call_1", toolName = "read_file")
  )

  /** The production grouping, so the decision is tested with real rounds. */
  private fun rounds(messages: List<LlmMessage>) = groupByAssistantStartedRounds(messages)

  @Test
  fun `the threshold is the effective window minus the buffer`() {
    // effective window = 200_000 - 21_000 (reserve capped at the preflight max)
    // threshold = 179_000 - 13_000
    assertEquals(166_000, policy().thresholdTokens)
  }

  @Test
  fun `a huge configured output reserve is capped for the preflight check`() {
    // 60_000 exceeds MAX_OUTPUT_RESERVE, so the effective window keeps 21_000.
    assertEquals(166_000, policy(outputReserve = 60_000).thresholdTokens)
  }

  @Test
  fun `a small model gets the floor, not a negative budget`() {
    val tiny = policy(contextWindow = 8_000, outputReserve = 7_000, buffer = 5_000)
    assertEquals(7_000, tiny.outputReserve)
    // The effective window floors at 16_000, but the threshold is still capped
    // by what the model can actually hold — otherwise it could never be reached.
    assertEquals(16_000, tiny.effectiveContextWindow)
    assertEquals(8_000, tiny.thresholdTokens)
  }

  @Test
  fun `threshold percent scales the point of compaction`() {
    assertEquals(83_000, policy(thresholdPercent = 50).thresholdTokens)
    assertEquals(166_000, policy(thresholdPercent = 100).thresholdTokens)
  }

  @Test
  fun `below threshold the transcript is left alone`() {
    val messages = smallTranscript()
    val decision = CompactPolicy.evaluate(messages, rounds(messages), policy())
    assertFalse(decision.shouldCompact)
    assertEquals(CompactReason.BELOW_THRESHOLD, decision.reason)
    assertTrue(decision.estimatedTokens < decision.thresholdTokens)
  }

  @Test
  fun `above threshold the transcript must be compacted`() {
    val config = policy(contextWindow = 2_000, buffer = 0)
    val messages = transcript()
    val decision = CompactPolicy.evaluate(messages, rounds(messages), config)
    assertTrue(decision.shouldCompact)
    assertEquals(CompactReason.ABOVE_THRESHOLD, decision.reason)
  }

  @Test
  fun `the circuit breaker stops compaction after the configured failures`() {
    val messages = transcript()
    val config = policy(contextWindow = 2_000, buffer = 0, maxConsecutiveFailures = 2)
    val ok = CompactPolicy.evaluate(messages, rounds(messages), config, consecutiveFailures = 1)
    assertEquals(CompactReason.ABOVE_THRESHOLD, ok.reason)
    val tripped = CompactPolicy.evaluate(messages, rounds(messages), config, consecutiveFailures = 2)
    assertFalse("the breaker must open at the configured count", tripped.shouldCompact)
    assertEquals(CompactReason.CIRCUIT_BREAKER, tripped.reason)
  }

  @Test
  fun `a conversation without enough rounds is never compacted`() {
    val messages = listOf(LlmMessage(LlmRole.USER, "hi"))
    val config = policy(contextWindow = 2_000, buffer = 0)
    val decision = CompactPolicy.evaluate(messages, emptyList(), config)
    assertFalse(decision.shouldCompact)
    assertEquals(CompactReason.NOT_ENOUGH_MESSAGES, decision.reason)
  }

  @Test
  fun `compaction can be disabled entirely`() {
    val messages = transcript()
    val config = policy(contextWindow = 2_000, buffer = 0).copy(enabled = false)
    val decision = CompactPolicy.evaluate(messages, rounds(messages), config)
    assertFalse(decision.shouldCompact)
    assertEquals(CompactReason.DISABLED, decision.reason)
  }

  @Test
  fun `provider usage is authoritative over the local estimate`() {
    val messages = transcript()
    val estimated = estimateMessageTokens(messages)
    val config = policy(contextWindow = 2_000, buffer = 0)
    val decision = CompactPolicy.evaluate(
      messages, rounds(messages), config,
      usage = LlmUsage(inputTokens = 10, outputTokens = 5, totalTokens = 15)
    )
    assertEquals(15, decision.estimatedTokens)
    assertTrue(15 < estimated)
    assertEquals(CompactReason.BELOW_THRESHOLD, decision.reason)
  }

  @Test
  fun `a usage report with no counters is ignored`() {
    val messages = transcript()
    val estimated = estimateMessageTokens(messages)
    val config = policy(contextWindow = 2_000, buffer = 0)
    val decision = CompactPolicy.evaluate(messages, rounds(messages), config, usage = LlmUsage())
    assertEquals(estimated, decision.estimatedTokens)
  }

  @Test
  fun `microcompact fires at about 90 percent of the threshold, with a buffer`() {
    val config = policy()
    val trigger = CompactPolicy.microcompactTriggerTokens(config)
    val atTrigger = CompactPolicy.microcompactTrigger(trigger, config)
    assertEquals(CompactPolicy.MicrocompactTrigger.TOKEN_PRESSURE, atTrigger)
    val below = CompactPolicy.microcompactTrigger(trigger - 1, config)
    assertEquals(CompactPolicy.MicrocompactTrigger.NONE, below)
    // 200_000 window -> 166_000 threshold -> 149_400 - 2_000
    assertEquals(147_400, trigger)
  }

  @Test
  fun `an idle conversation clears old tool results even at low pressure`() {
    val config = policy()
    val idle = config.microcompactIdleMillis
    assertEquals(CompactPolicy.MicrocompactTrigger.IDLE, CompactPolicy.microcompactTrigger(10, config, idle))
    assertEquals(CompactPolicy.MicrocompactTrigger.NONE, CompactPolicy.microcompactTrigger(10, config, idle - 1))
  }

  @Test
  fun `microcompact can be turned off independently of the summary pass`() {
    val config = policy().copy(microcompactEnabled = false)
    assertEquals(
      CompactPolicy.MicrocompactTrigger.DISABLED,
      CompactPolicy.microcompactTrigger(500_000, config)
    )
  }

  @Test
  fun `the default tool set covers the bulky read and command tools`() {
    val tools = CompactPolicyConfig.DEFAULT_MICROCOMPACT_TOOLS
    listOf("read_file", "read_files", "run_command", "git_diff", "search_files", "build", "test")
      .forEach { assertTrue("expected $it to be clearable", tools.contains(it)) }
    // Writing tools mutate state: their result is small and their effect is
    // the file itself, so clearing them buys nothing.
    listOf("write_file", "edit_file", "create_file", "delete_file", "git_commit")
      .forEach { assertFalse("expected $it to be preserved", tools.contains(it)) }
  }

  @Test
  fun `token estimation counts tool call arguments`() {
    val args = "{\"path\":\"src/very/deeply/nested/File.kt\"}"
    val withCall = LlmMessage(LlmRole.ASSISTANT, "", toolCalls = listOf(LlmToolCall("c1", "read_file", args)))
    val without = LlmMessage(LlmRole.ASSISTANT, "")
    assertTrue(estimateMessageTokens(withCall) > estimateMessageTokens(without))
    assertTrue(estimateMessageTokens(withCall) >= estimateTokens(args))
  }

  @Test
  fun `the percent is relative to the whole window and can exceed it`() {
    val config = policy()
    val at = CompactPolicy.evaluate(
      transcript(), rounds(transcript()), config,
      usage = LlmUsage(totalTokens = 100_000)
    )
    assertEquals(50, at.estimatedTokens * 100 / config.contextWindow)
  }
}
