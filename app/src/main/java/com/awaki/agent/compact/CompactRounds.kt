package com.awaki.agent.compact

import com.awaki.agent.llm.LlmMessage
import com.awaki.agent.llm.LlmRole

/**
 * One assistant-started round: everything from an assistant message (or its
 * tool calls) up to — but not including — the next assistant message.
 *
 * Compaction cuts on round boundaries, never inside one, so a retained tail can
 * never start with orphaned tool results whose calls were summarized away.
 */
data class CompactRound(
  val messages: List<LlmMessage>,
  val toolCallCount: Int,
  val toolResultCount: Int,
  val tokens: Int
) {
  val isEmpty: Boolean get() = messages.isEmpty()
}

/**
 * Splits a transcript into assistant-started rounds.
 *
 * The system prompt is not a round: it is rebuilt on every request and is never
 * a candidate for summarization. Leading user messages before the first
 * assistant reply are kept as their own opening group so a conversation that
 * starts with two prompts is not merged into the following round.
 */
fun groupByAssistantStartedRounds(messages: List<LlmMessage>): List<CompactRound> {
  val rounds = mutableListOf<MutableList<LlmMessage>>()
  var current = mutableListOf<LlmMessage>()
  var assistantTurnSeen = false

  fun flush() {
    if (current.isNotEmpty()) {
      rounds.add(current)
      current = mutableListOf()
    }
  }

  for (message in messages) {
    when (message.role) {
      LlmRole.SYSTEM -> Unit // carried by the request, never summarized
      LlmRole.ASSISTANT -> {
        if (assistantTurnSeen) flush()
        assistantTurnSeen = true
        current.add(message)
      }
      else -> {
        // A user turn belongs to the round it follows; leading ones form their
        // own group so the first assistant reply still starts a round.
        if (message.role == LlmRole.USER && !assistantTurnSeen && current.isNotEmpty()) flush()
        current.add(message)
      }
    }
  }
  flush()
  return rounds.map { group ->
    CompactRound(
      messages = group.toList(),
      toolCallCount = group.sumOf { it.toolCalls.size },
      toolResultCount = group.count { it.role == LlmRole.TOOL },
      tokens = estimateMessageTokens(group)
    )
  }
}

/**
 * True for a user message that the app injected (a system-reminder wrapper),
 * never real user intent — those must not be counted as user requests.
 */
fun isSystemReminderUserMessage(message: LlmMessage): Boolean {
  if (message.role != LlmRole.USER) return false
  val text = message.content.trimStart()
  return text.startsWith("<system-reminder>") || text.startsWith("<system_instruction>")
}

/** Strips an app-injected wrapper so the summary keeps only the real text. */
fun stripInjectedPrefixes(text: String): String =
  text.trim()
    .removePrefix("<system-reminder>").removeSuffix("</system-reminder>").trim()
    .removePrefix("<system_instruction>").removeSuffix("</system_instruction>").trim()
