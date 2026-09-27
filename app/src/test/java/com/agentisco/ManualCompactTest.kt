package com.agentisco

import com.agentisco.agent.compact.CompactPolicyConfig
import com.agentisco.agent.compact.CompactReason
import com.agentisco.agent.compact.CompactSummaryContext
import com.agentisco.agent.compact.ManualCompact
import com.agentisco.agent.compact.buildCompactPrompt
import com.agentisco.agent.compact.buildCompactSummaryMessage
import com.agentisco.agent.compact.cleanCompactSummary
import com.agentisco.agent.compact.estimateMessageTokens
import com.agentisco.agent.llm.LlmMessage
import com.agentisco.agent.llm.LlmRole
import com.agentisco.agent.llm.LlmToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCompactTest {

  private val config = CompactPolicyConfig(contextWindow = 100_000, keepRecentRounds = 2)
  private val big = buildString { repeat(40_000) { append("a line of the transcript\n") } }

  private fun transcript(rounds: Int): List<LlmMessage> {
    val messages = mutableListOf<LlmMessage>(LlmMessage(LlmRole.SYSTEM, "system prompt"))
    for (i in 0 until rounds) {
      messages.add(LlmMessage(LlmRole.USER, "request $i"))
      messages.add(
        LlmMessage(
          LlmRole.ASSISTANT,
          "answer $i",
          toolCalls = listOf(LlmToolCall("c$i", "read_file", "{\"path\":\"f$i.kt\"}"))
        )
      )
      messages.add(LlmMessage(LlmRole.TOOL, big, toolCallId = "c$i", toolName = "read_file"))
    }
    return messages
  }

  @Test
  fun `the plan summarizes older rounds and keeps the newest verbatim`() {
    val messages = transcript(5)
    val plan = ManualCompact.plan(messages, config)

    assertEquals(3, plan.summarizedRoundCount)
    assertEquals(2, plan.keptRoundCount)
    // Rounds are assistant-started (see groupByAssistantStartedRounds): the
    // opening group carries the first user prompt too, so three summarized
    // rounds are 4 + 3 + 3 messages.
    assertEquals(10, plan.transcript().size)
    // Nothing is lost or duplicated across the split.
    assertEquals(messages.size, plan.transcript().size + plan.preservedMessageCount)
    // The system prompt is kept out of the summary and survives in the tail.
    assertTrue(plan.preservedMessages.first().role == LlmRole.SYSTEM)
    assertEquals(1 + 3 + 2, plan.preservedMessageCount) // system + the two newest rounds
  }

  @Test
  fun `the summary lands where the old turns were, newest last`() {
    val messages = transcript(3)
    val plan = ManualCompact.plan(messages, config)
    val compacted = ManualCompact.applySummary(
      plan,
      "1. Primary Request and Intent\n…",
      CompactSummaryContext(
        preservedRecentCount = plan.preservedMessageCount,
        tokensBefore = 50_000,
        tokensAfter = 5_000,
        trigger = CompactReason.ABOVE_THRESHOLD
      )
    )

    assertEquals(LlmRole.SYSTEM, compacted.first().role)
    // The summary is a user turn, exactly where the summarized turns used to be.
    assertEquals(LlmRole.USER, compacted[1].role)
    assertTrue(compacted[1].content.contains("Conversation compacted"))
    // The preserved tail follows, in its original order, newest last.
    assertEquals(LlmRole.TOOL, compacted.last().role)
    assertTrue(compacted.all { it.role != LlmRole.USER || it.content.contains("Conversation compacted") || it.content == "request 2" })
  }

  @Test
  fun `compaction must shrink the transcript`() {
    val messages = transcript(5)
    val plan = ManualCompact.plan(messages, config)
    val before = estimateMessageTokens(messages)
    val compacted = ManualCompact.applySummary(
      plan,
      buildString { repeat(500) { appendLine("summary line") } },
      CompactSummaryContext(
        preservedRecentCount = plan.preservedMessageCount,
        tokensBefore = before,
        tokensAfter = 0,
        trigger = CompactReason.ABOVE_THRESHOLD
      )
    )
    val after = estimateMessageTokens(compacted)
    assertTrue("compaction must reclaim space, before=$before after=$after", after < before)
  }

  @Test
  fun `keeping zero rounds summarizes everything`() {
    val messages = transcript(3)
    val plan = ManualCompact.plan(messages, config.copy(keepRecentRounds = 0))
    assertEquals(3, plan.summarizedRoundCount)
    assertEquals(0, plan.keptRoundCount)
    // Only the system prompt is preserved verbatim.
    assertEquals(1, plan.preservedMessageCount)
    assertEquals(LlmRole.SYSTEM, plan.preservedMessages.single().role)
  }

  @Test
  fun `keeping every round summarizes nothing`() {
    val messages = transcript(2)
    val plan = ManualCompact.plan(messages, config.copy(keepRecentRounds = 5))
    assertEquals(0, plan.summarizedRoundCount)
    assertEquals(2, plan.keptRoundCount)
    assertTrue(plan.transcript().isEmpty())
  }

  @Test
  fun `the prompt demands a tool free answer with the nine required sections`() {
    val prompt = buildCompactPrompt(transcript(2).filter { it.role != LlmRole.SYSTEM })
    assertTrue(prompt.contains("TEXT ONLY", ignoreCase = true))
    assertTrue(prompt.contains("no tools", ignoreCase = true))
    assertTrue(prompt.contains("<analysis>"))
    listOf(
      "Primary Request and Intent",
      "Key Technical Concepts",
      "Files and Code Sections",
      "Errors and Fixes",
      "Problem Solving",
      "All user messages",
      "Pending Tasks",
      "Current Work",
      "Optional Next Step"
    ).forEach { section -> assertTrue("missing section: $section", prompt.contains(section)) }
    // The transcript itself must be in the prompt, labeled by role.
    assertTrue(prompt.contains("[USER]"))
    assertTrue(prompt.contains("[ASSISTANT TOOL CALL]"))
    assertTrue(prompt.contains("[TOOL read_file]"))
    assertTrue(prompt.contains("----- TRANSCRIPT -----"))
  }

  @Test
  fun `custom instructions are appended verbatim`() {
    val prompt = buildCompactPrompt(
      transcript(1).filter { it.role != LlmRole.SYSTEM },
      customInstructions = "focus on the database migration"
    )
    assertTrue(prompt.contains("focus on the database migration"))
  }

  @Test
  fun `the prompt never claims to reproduce preserved messages`() {
    val messages = transcript(3)
    val plan = ManualCompact.plan(messages, config)
    val prompt = buildCompactPrompt(plan.transcript(), preserveRecentMessages = plan.preservedMessageCount)
    assertTrue(prompt.contains("last ${plan.preservedMessageCount} message(s)"))
  }

  @Test
  fun `preamble and analysis blocks are stripped from the summary`() {
    val raw = """
      <analysis>
      Let me think about what matters here.
      </analysis>
      1. Primary Request and Intent
      The user asked for a migration.
      2. Key Technical Concepts
      Room, SQLite.
    """.trimIndent()
    val cleaned = cleanCompactSummary(raw)
    assertFalse(cleaned.contains("<analysis>"))
    assertTrue(cleaned.startsWith("1. Primary Request and Intent"))
  }

  @Test
  fun `a thinking block is stripped too`() {
    val raw = """
      Let me think about this carefully.
      1. Primary Request and Intent
      The goal.
    """.trimIndent()
    val cleaned = cleanCompactSummary(raw)
    assertEquals("1. Primary Request and Intent\nThe goal.", cleaned)
  }

  @Test
  fun `a summary with no numbered section is kept as is`() {
    val raw = "Just a free-form summary with no sections."
    assertEquals(raw, cleanCompactSummary(raw))
  }

  @Test
  fun `the continuation message tells the agent how to resume`() {
    val message = buildCompactSummaryMessage(
      "1. Primary Request and Intent\nThe goal.",
      CompactSummaryContext(
        preservedRecentCount = 3,
        tokensBefore = 40_000,
        tokensAfter = 4_000,
        trigger = CompactReason.ABOVE_THRESHOLD,
        clearedToolResults = 2,
        summarizerModel = "Test Model"
      )
    )
    assertTrue(message.contains("<summary>"))
    assertTrue(message.contains("1. Primary Request and Intent"))
    assertTrue(message.contains("40.0k tokens"))
    assertTrue(message.contains("4.0k tokens"))
    assertTrue(message.contains("above threshold"))
    assertTrue(message.contains("last 3 message(s)"))
    assertTrue(message.contains("2 older tool result(s)"))
    assertTrue(message.contains("Test Model"))
    // Continuation instructions: no acknowledgement, re-read before editing.
    assertTrue(message.contains("do not acknowledge"))
    assertTrue(message.contains("re-read them with a tool"))
    assertTrue(message.contains("Security constraints"))
  }

  @Test
  fun `the boundary records what was summarized and what it saved`() {
    val messages = transcript(4)
    val plan = ManualCompact.plan(messages, config)
    val boundary = ManualCompact.buildBoundary(
      plan = plan,
      tokensBefore = 90_000,
      tokensAfter = 12_000,
      config = config,
      trigger = CompactReason.ABOVE_THRESHOLD,
      clearedToolResults = 3,
      summaryTokens = 900
    )
    assertEquals(78_000, boundary.savedTokens)
    assertEquals(86, boundary.savedPercent)
    assertEquals(2, plan.summarizedRoundCount)
    assertTrue(boundary.summarizedMessages > boundary.keptMessages)
    assertTrue(boundary.describe().contains("Compacted"))
    assertTrue(boundary.describe().contains("86%"))
  }

  @Test
  fun `the boundary reports failure without misleading numbers`() {
    val boundary = ManualCompact.buildBoundary(
      plan = ManualCompact.plan(transcript(2), config),
      tokensBefore = 90_000,
      tokensAfter = 90_000,
      config = config
    ).copy(failed = true, failureReason = "rate limited")
    assertTrue(boundary.describe().startsWith("Compaction failed: rate limited"))
  }

  @Test
  fun `the system instruction keeps the agent's identity after compaction`() {
    val instruction = ManualCompact.systemInstruction()
    assertNotNull(instruction)
    assertTrue(instruction.contains("Agentisco", ignoreCase = true))
    assertTrue(instruction.contains("win whenever they disagree"))
  }

  @Test
  fun `a round boundary is never inside an assistant turn`() {
    // A tool result arrives after its assistant message: the round must own both.
    val messages = transcript(3)
    val plan = ManualCompact.plan(messages, config.copy(keepRecentRounds = 1))
    val kept = plan.keepRounds.single()
    assertEquals(LlmRole.ASSISTANT, kept.messages.first().role)
    assertEquals(LlmRole.TOOL, kept.messages.last().role)
    // The kept tail can never start with an orphaned tool result, and every
    // call it answers is either in the same round or summarized away.
    assertFalse(kept.messages.any { it.role == LlmRole.TOOL && it.toolCallId == null })
    val summarizedCalls = plan.transcript().flatMap { r -> r.toolCalls.map { it.id } }.toSet()
    assertTrue(kept.messages.filter { it.role == LlmRole.TOOL }
      .none { summarizedCalls.contains(it.toolCallId) })
    assertNotEquals(0, kept.messages.size)
  }
}
