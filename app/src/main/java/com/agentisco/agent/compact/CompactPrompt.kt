package com.agentisco.agent.compact

import com.agentisco.agent.llm.LlmMessage
import com.agentisco.agent.llm.LlmRole

/**
 * The instruction that turns a long tool-heavy transcript into a compact
 * handoff. It is deliberately rigid: the agent resumes *from* this text, so
 * anything the model leaves out is gone for the rest of the session.
 */
const val COMPACT_PREAMBLE = """You are compacting a coding-agent conversation so it can continue after the older turns are dropped.

ABSOLUTE RULES:
- Output TEXT ONLY. Do not request or call any tool.
- Do NOT write any conversational filler: no "Sure", no "I'll summarize", no closing remarks.
- Reason briefly inside a single <analysis></analysis> block FIRST, then emit the summary.
- The summary must let the agent resume work with no other context. Be specific and factual.
- NEVER invent facts, file paths, commands or errors that are not in the transcript."""

private const val COMPACT_SECTIONS = """1. Primary Request and Intent
   The user's verbatim goal(s) and any correction they issued.
2. Key Technical Concepts
   Languages, frameworks, build/test commands, conventions and constraints in play.
3. Files and Code Sections
   Every file that was read, created, edited or planned — with paths and the exact snippets/patterns that matter.
4. Errors and Fixes
   Each error message, its cause, and the fix that worked (or what is still broken).
5. Problem Solving
   Approaches already tried, decisions made and their reasons, dead ends to avoid repeating.
6. All user messages
   Reproduce every user message verbatim and in order. Security constraints, forbidden operations, paths that must not be touched and explicit "do not" instructions are binding and must appear here in full.
7. Pending Tasks
   What is still open, in priority order.
8. Current Work
   The exact step in progress when compaction happened: files being edited, commands running or about to run, the last known state.
9. Optional Next Step
   The single next action, phrased as a direct continuation, aligned with the most recent user request."""

private const val COMPACT_TRAILER = "Output format: <analysis>…</analysis> then a markdown summary with the numbered sections above. Nothing else."

/**
 * Builds the summarization request.
 *
 * @param transcript the messages to summarize (never includes the system prompt).
 * @param customInstructions extra focus for this compaction (e.g. a manual
 *   "focus on the migration" request); appended verbatim.
 * @param preserveRecentMessages how many trailing messages the runtime keeps
 *   un-summarized, so the prompt does not promise to reproduce them.
 */
fun buildCompactPrompt(
  transcript: List<LlmMessage>,
  customInstructions: String? = null,
  preserveRecentMessages: Int = 0
): String = buildString {
  appendLine(COMPACT_PREAMBLE)
  appendLine()
  appendLine("Write the summary in this exact structure:")
  appendLine(COMPACT_SECTIONS)
  appendLine()
  if (preserveRecentMessages > 0) {
    appendLine(
      "The last $preserveRecentMessages message(s) of the conversation are preserved verbatim and are NOT part of the transcript below — " +
        "do not duplicate them, but keep section 8 consistent with them."
    )
    appendLine()
  }
  if (!customInstructions.isNullOrBlank()) {
    appendLine("Additional instructions for this summary: ${customInstructions.trim()}")
    appendLine()
  }
  appendLine(COMPACT_TRAILER)
  appendLine()
  appendLine("----- TRANSCRIPT -----")
  transcript.forEach { appendLine(renderForSummary(it)) }
  appendLine("----- END TRANSCRIPT -----")
}

/** Renders one message as a labeled transcript line. */
private fun renderForSummary(message: LlmMessage): String = when (message.role) {
  LlmRole.USER -> "[USER] ${stripInjectedPrefixes(message.content)}"
  LlmRole.ASSISTANT -> buildString {
    if (message.content.isNotBlank()) appendLine("[ASSISTANT] ${message.content}")
    message.toolCalls.forEach { appendLine("[ASSISTANT TOOL CALL] ${it.name}(${it.argumentsJson})") }
    if (message.content.isBlank() && message.toolCalls.isEmpty()) appendLine("[ASSISTANT] (no content)")
  }
  LlmRole.TOOL -> "[TOOL ${message.toolName ?: "result"}] ${message.content}"
  LlmRole.SYSTEM -> "[SYSTEM] ${message.content}"
}

/**
 * Strips the model's private preamble so only the summary reaches the agent.
 *
 * Reasoning models emit their thinking as a `<think>…</think>` block or an
 * `<analysis>…</analysis>` wrapper even when told not to; the summary itself
 * starts at the first numbered section, so the text before that is dropped.
 */
fun cleanCompactSummary(raw: String): String {
  var text = raw.trim()
  val think = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE).find(text)
  if (think != null) text = text.removeRange(think.range).trim()
  val analysis = Regex("<analysis>[\\s\\S]*?</analysis>", RegexOption.IGNORE_CASE).find(text)
  if (analysis != null) text = text.removeRange(analysis.range).trim()
  // Whatever precedes the first required section is preamble, not summary.
  val firstSection = Regex("(?m)^\\s*1\\.\\s").find(text)
  if (firstSection != null && firstSection.range.first > 0) text = text.substring(firstSection.range.first)
  return text.trim().ifBlank { raw.trim() }
}

/** The message that replaces the summarized history. */
data class CompactSummaryContext(
  val preservedRecentCount: Int,
  val tokensBefore: Int,
  val tokensAfter: Int,
  val trigger: CompactReason,
  val clearedToolResults: Int = 0,
  val summarizerModel: String? = null
)

/**
 * The continuation message the agent receives instead of the old turns.
 *
 * It carries the summary *and* the instructions that make the agent behave as
 * if nothing had been dropped: continue the latest user request, re-read files
 * instead of trusting stale snippets, and never re-run a command blindly.
 */
fun buildCompactSummaryMessage(summary: String, context: CompactSummaryContext): String = buildString {
  appendLine("## Conversation compacted")
  appendLine()
  appendLine("<summary>")
  appendLine(cleanCompactSummary(summary))
  appendLine("</summary>")
  appendLine()
  appendLine(
    "This is a compacted record of the earlier conversation (≈${formatTokens(context.tokensBefore)} → " +
      "≈${formatTokens(context.tokensAfter)} tokens, ${context.trigger.name.lowercase().replace('_', ' ')})."
  )
  if (context.preservedRecentCount > 0) {
    appendLine(
      "The last ${context.preservedRecentCount} message(s) are preserved verbatim after this summary and take precedence over it when they conflict."
    )
  }
  if (context.clearedToolResults > 0) {
    appendLine(
      "${context.clearedToolResults} older tool result(s) were dropped locally; re-run a tool if you need its output again."
    )
  }
  context.summarizerModel?.let { appendLine("Summary produced by $it.") }
  appendLine()
  appendLine("How to continue:")
  appendLine("- Continue the most recent user request directly; do not acknowledge or re-explain the summary.")
  appendLine("- Treat file contents, command output and diffs quoted in the summary as possibly stale — re-read them with a tool before editing.")
  appendLine("- Never repeat a destructive or already-completed operation because the summary mentions it.")
  appendLine("- Security constraints and forbidden operations listed in the summary stay in force.")
}

private fun formatTokens(tokens: Int): String = compactNumber(tokens) + " tokens"
