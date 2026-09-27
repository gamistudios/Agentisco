package com.agentisco

import com.agentisco.agent.compact.CompactPolicyConfig
import com.agentisco.agent.compact.CompactTokenMeter
import com.agentisco.agent.compact.ContextTokenUsage
import com.agentisco.agent.compact.TokenUsageSource
import com.agentisco.agent.compact.compactNumber
import com.agentisco.agent.compact.estimateMessageTokens
import com.agentisco.agent.llm.LlmMessage
import com.agentisco.agent.llm.LlmRole
import com.agentisco.agent.llm.LlmUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactTokenMeterTest {

  private val config = CompactPolicyConfig(contextWindow = 100_000, outputReserve = 0, buffer = 0)
  private val big = buildString { repeat(30_000) { append("token-ish text ") } }

  @Test
  fun `a fresh meter reports the measured baseline`() {
    val meter = CompactTokenMeter(config.contextWindow, config)
    val messages = listOf(LlmMessage(LlmRole.USER, big))
    meter.setBase(messages)
    val usage = meter.snapshot()
    assertEquals(estimateMessageTokens(messages), usage.usedTokens)
    assertEquals(config.contextWindow, usage.contextWindow)
    assertEquals(config.thresholdTokens, usage.thresholdTokens)
    assertEquals(TokenUsageSource.ESTIMATED, usage.source)
  }

  @Test
  fun `streamed text grows the estimate without re walking the transcript`() {
    val meter = CompactTokenMeter(config.contextWindow, config)
    meter.setBase(listOf(LlmMessage(LlmRole.USER, "short")))
    val before = meter.snapshot().usedTokens
    meter.appendAssistantText(big)
    val after = meter.snapshot().usedTokens
    assertTrue(after > before)
    assertTrue(after >= before + big.length / 4)
  }

  @Test
  fun `provider usage replaces the estimate once reported`() {
    val meter = CompactTokenMeter(config.contextWindow, config)
    meter.setBase(listOf(LlmMessage(LlmRole.USER, big)))
    val estimated = meter.snapshot().usedTokens
    meter.onResponseCompleted(LlmUsage(inputTokens = 1_000, outputTokens = 300, totalTokens = 1_300))
    val usage = meter.snapshot()
    assertEquals(1_300, usage.usedTokens)
    assertEquals(TokenUsageSource.PROVIDER, usage.source)
    assertTrue(usage.usedTokens < estimated)
  }

  @Test
  fun `input plus output is used when total is absent`() {
    val meter = CompactTokenMeter(config.contextWindow, config)
    meter.setBase(listOf(LlmMessage(LlmRole.USER, big)))
    meter.onResponseCompleted(LlmUsage(inputTokens = 700, outputTokens = 200))
    assertEquals(900, meter.snapshot().usedTokens)
  }

  @Test
  fun `a usage object with no counters falls back to the estimate`() {
    val meter = CompactTokenMeter(config.contextWindow, config)
    meter.setBase(listOf(LlmMessage(LlmRole.USER, big)))
    val baseline = meter.snapshot().usedTokens
    meter.appendAssistantText("a bit more text")
    meter.onResponseCompleted(LlmUsage())
    assertTrue(meter.snapshot().usedTokens >= baseline)
    assertEquals(TokenUsageSource.ESTIMATED, meter.snapshot().source)
  }

  @Test
  fun `microcompacting subtracts what the pass saved`() {
    val meter = CompactTokenMeter(config.contextWindow, config)
    meter.setBase(listOf(LlmMessage(LlmRole.USER, big)))
    val before = meter.snapshot().usedTokens
    meter.onMicrocompacted(clearedResults = 4, savedTokens = 5_000)
    val usage = meter.snapshot()
    assertEquals(before - 5_000, usage.usedTokens)
    assertEquals(4, usage.clearedToolResults)
  }

  @Test
  fun `an empty microcompact is a no op`() {
    val meter = CompactTokenMeter(config.contextWindow, config)
    meter.setBase(listOf(LlmMessage(LlmRole.USER, big)))
    val before = meter.snapshot().usedTokens
    meter.onMicrocompacted(0, 0)
    assertEquals(before, meter.snapshot().usedTokens)
    assertEquals(0, meter.snapshot().clearedToolResults)
  }

  @Test
  fun `a full compaction adopts the compressed size`() {
    val meter = CompactTokenMeter(config.contextWindow, config)
    meter.setBase(listOf(LlmMessage(LlmRole.USER, big)))
    meter.onCompacted(ContextTokenUsage(usedTokens = 4_000, contextWindow = config.contextWindow))
    assertEquals(4_000, meter.snapshot().usedTokens)
    // The next streamed segment still adds on top.
    meter.appendAssistantText("hello world")
    assertTrue(meter.snapshot().usedTokens > 4_000)
  }

  @Test
  fun `percent is relative to the window and tracks growth`() {
    val meter = CompactTokenMeter(config.contextWindow, config)
    meter.setBase(listOf(LlmMessage(LlmRole.USER, buildString { repeat(10_000) { append("abcd ") } })))
    val first = meter.snapshot()
    meter.appendAssistantText(buildString { repeat(10_000) { append("abcd ") } })
    val second = meter.snapshot()
    assertTrue(second.percent > first.percent)
    assertTrue(second.percent <= 100)
  }

  @Test
  fun `pressure percent is relative to the compact threshold`() {
    val usage = ContextTokenUsage(usedTokens = 50_000, contextWindow = 100_000, thresholdTokens = 80_000)
    assertEquals(50, usage.percent)
    assertEquals(62, usage.pressurePercent)
    assertFalse(usage.isAboveThreshold)
    val over = usage.copy(usedTokens = 80_000)
    assertTrue(over.isAboveThreshold)
    assertEquals(100, over.pressurePercent)
  }

  @Test
  fun `the chip label stays short and whole`() {
    val usage = ContextTokenUsage(usedTokens = 37_500, contextWindow = 100_000)
    assertEquals("37% context", usage.label())
    assertEquals("37.5k / 100k tokens", usage.detail())
    assertEquals("37.5k", compactNumber(37_500))
    assertEquals("100k", compactNumber(100_000))
    assertEquals("1.3M", compactNumber(1_300_000))
    assertEquals("812", compactNumber(812))
  }

  @Test
  fun `an empty meter reports zero usage against the window`() {
    val empty = ContextTokenUsage.empty(contextWindow = 50_000)
    assertEquals(0, empty.usedTokens)
    assertEquals(50_000, empty.contextWindow)
    assertTrue(empty.thresholdTokens > 0)
    assertEquals(0, empty.percent)
  }
}
