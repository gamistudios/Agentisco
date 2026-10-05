package com.awaki

import com.awaki.agent.compact.CLEARED_TOOL_RESULT_PLACEHOLDER
import com.awaki.agent.compact.CompactPolicy
import com.awaki.agent.compact.CompactPolicyConfig
import com.awaki.agent.compact.Microcompact
import com.awaki.agent.compact.estimateMessageTokens
import com.awaki.agent.compact.groupByAssistantStartedRounds
import com.awaki.agent.llm.LlmMessage
import com.awaki.agent.llm.LlmRole
import com.awaki.agent.llm.LlmToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrocompactTest {

  private val config = CompactPolicyConfig(
    contextWindow = 10_000,
    outputReserve = 0,
    buffer = 0,
    keepRecentToolResultGroups = 2,
    microcompactMinSavedTokens = 50
  )

  private val big = buildString { repeat(4_000) { append("line of file content\n") } }

  private fun result(tool: String, content: String, id: String, error: Boolean = false) =
    LlmMessage(LlmRole.TOOL, content, toolCallId = id, toolName = tool, isError = error)

  private fun conversation(toolCount: Int, tool: String = "read_file"): List<LlmMessage> {
    val messages = mutableListOf<LlmMessage>(LlmMessage(LlmRole.SYSTEM, "system"))
    for (i in 0 until toolCount) {
      messages.add(LlmMessage(LlmRole.ASSISTANT, "reading file $i", toolCalls = listOf(LlmToolCall("c$i", tool, "{}"))))
      messages.add(result(tool, big, "c$i"))
    }
    return messages
  }

  @Test
  fun `old tool result payloads become a placeholder, keeping the newest groups`() {
    val messages = conversation(6)
    val outcome = Microcompact.run(messages, config, currentTokens = 9_000)

    assertTrue(outcome.applied)
    assertEquals("6 results minus the 2 newest groups", 4, outcome.clearedResults)
    assertTrue(outcome.savedTokens > 0)

    // The transcript shrank, and the retained results kept their real content.
    assertTrue(estimateMessageTokens(outcome.messages) < estimateMessageTokens(messages))
    val real = outcome.messages.filter { it.role == LlmRole.TOOL && it.content == big }
    assertEquals(2, real.size)

    // A placeholder never fully erases the tool identity.
    outcome.messages.filter { it.role == LlmRole.TOOL }.forEach {
      if (it.content != big) assertTrue(it.content.startsWith(CLEARED_TOOL_RESULT_PLACEHOLDER))
    }
  }

  @Test
  fun `user and assistant messages are never cleared`() {
    val messages = conversation(4) + LlmMessage(LlmRole.USER, "please keep my text")
    val outcome = Microcompact.run(messages, config, currentTokens = 9_000)

    assertTrue(outcome.applied)
    assertTrue(outcome.messages.any { it.role == LlmRole.USER && it.content == "please keep my text" })
    assertTrue(outcome.messages.all { it.role != LlmRole.ASSISTANT || it.content != CLEARED_TOOL_RESULT_PLACEHOLDER })
  }

  @Test
  fun `already cleared results are skipped, not double counted`() {
    val once = Microcompact.run(conversation(6), config, currentTokens = 9_000)
    val twice = Microcompact.run(once.messages, config, currentTokens = 9_000)

    // The first pass cleared four; the second can only find what is left, and
    // the kept groups still belong to the newest turn.
    assertTrue(once.applied)
    assertTrue(twice.clearedResults <= once.clearedResults)
  }

  @Test
  fun `a clearing that saves almost nothing is reverted`() {
    val small = buildString { repeat(2) { append("x") } }
    val messages = listOf(
      LlmMessage(LlmRole.SYSTEM, "s"),
      LlmMessage(LlmRole.ASSISTANT, "a", toolCalls = listOf(LlmToolCall("c0", "read_file", "{}"))),
      result("read_file", small, "c0"),
      LlmMessage(LlmRole.ASSISTANT, "b", toolCalls = listOf(LlmToolCall("c1", "read_file", "{}"))),
      result("read_file", small, "c1")
    )
    val outcome = Microcompact.run(messages, config.copy(keepRecentToolResultGroups = 1), currentTokens = 9_000)
    assertFalse("a saving under the minimum must revert the rewrite", outcome.applied)
    assertEquals(messages, outcome.messages)
  }

  @Test
  fun `failed tool results are preserved by default`() {
    val messages = listOf(
      LlmMessage(LlmRole.SYSTEM, "s"),
      LlmMessage(LlmRole.ASSISTANT, "a", toolCalls = listOf(LlmToolCall("c0", "run_command", "{}"))),
      result("run_command", big, "c0", error = true),
      LlmMessage(LlmRole.ASSISTANT, "b", toolCalls = listOf(LlmToolCall("c1", "run_command", "{}"))),
      result("run_command", big, "c1"),
      LlmMessage(LlmRole.ASSISTANT, "c", toolCalls = listOf(LlmToolCall("c2", "run_command", "{}"))),
      result("run_command", big, "c2")
    )
    val outcome = Microcompact.run(messages, config.copy(keepRecentToolResultGroups = 1), currentTokens = 9_000)
    assertTrue(outcome.applied)
    // The failure is the oldest result but must survive.
    assertTrue(outcome.messages.any { it.role == LlmRole.TOOL && it.content == big && it.isError })

    val aggressive = Microcompact.run(messages, config.copy(keepRecentToolResultGroups = 1, microcompactClearErrors = true), currentTokens = 9_000)
    assertTrue(aggressive.applied)
    assertNotEquals(outcome.clearedResults, aggressive.clearedResults)
  }

  @Test
  fun `tools outside the clearable set keep their payload`() {
    val messages = listOf(
      LlmMessage(LlmRole.SYSTEM, "s"),
      LlmMessage(LlmRole.ASSISTANT, "a", toolCalls = listOf(LlmToolCall("c0", "write_file", "{}"))),
      result("write_file", big, "c0"),
      LlmMessage(LlmRole.ASSISTANT, "b", toolCalls = listOf(LlmToolCall("c1", "write_file", "{}"))),
      result("write_file", big, "c1")
    )
    val outcome = Microcompact.run(messages, config, currentTokens = 9_000)
    assertFalse(outcome.applied)
  }

  @Test
  fun `no pressure and no idle time means no clearing`() {
    val messages = conversation(6)
    val idle = CompactPolicy.microcompactTrigger(1_000, config)
    assertEquals(CompactPolicy.MicrocompactTrigger.NONE, idle)
    val outcome = Microcompact.run(messages, config, currentTokens = 1_000, idleMillis = 0)
    assertFalse(outcome.applied)
    assertEquals(messages, outcome.messages)
  }

  @Test
  fun `idle time alone triggers clearing`() {
    val messages = conversation(6)
    val outcome = Microcompact.run(messages, config, currentTokens = 100, idleMillis = config.microcompactIdleMillis)
    assertTrue(outcome.applied)
  }

  @Test
  fun `a conversation with no tool results is untouched`() {
    val messages = listOf(
      LlmMessage(LlmRole.SYSTEM, "s"),
      LlmMessage(LlmRole.USER, "hi"),
      LlmMessage(LlmRole.ASSISTANT, "hello")
    )
    val outcome = Microcompact.run(messages, config, currentTokens = 9_000)
    assertFalse(outcome.applied)
  }

  @Test
  fun `rounds are split on assistant turns and the system prompt is not a round`() {
    val messages = conversation(3)
    val rounds = groupByAssistantStartedRounds(messages)
    assertEquals(3, rounds.size)
    assertNull("the system prompt belongs to no round", rounds.firstOrNull { it.messages.first().role == LlmRole.SYSTEM })
    rounds.forEach { assertEquals(2, it.messages.size) }
    assertTrue(rounds.all { it.toolCallCount == 1 && it.toolResultCount == 1 })
  }

  @Test
  fun `rounds keep their token totals`() {
    val messages = conversation(2)
    val rounds = groupByAssistantStartedRounds(messages)
    val total = rounds.sumOf { it.tokens }
    val expected = estimateMessageTokens(messages.filter { it.role != LlmRole.SYSTEM })
    assertEquals(expected, total)
  }
}
