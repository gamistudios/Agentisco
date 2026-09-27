package com.agentisco.agent.compact

import com.agentisco.agent.llm.LlmMessage
import com.agentisco.agent.llm.LlmRole

/** Text that replaces a dropped tool payload. */
const val CLEARED_TOOL_RESULT_PLACEHOLDER = "[Old tool result content cleared]"

/** The result of one local (no-LLM) tool-result clearing pass. */
data class MicrocompactResult(
  /** The transcript after clearing; identical to the input when nothing applied. */
  val messages: List<LlmMessage>,
  val clearedResults: Int,
  val savedTokens: Int,
  val trigger: CompactPolicy.MicrocompactTrigger
) {
  val applied: Boolean get() = clearedResults > 0 && savedTokens > 0

  companion object {
    fun skipped(trigger: CompactPolicy.MicrocompactTrigger, messages: List<LlmMessage>) =
      MicrocompactResult(messages, 0, 0, trigger)
  }
}

/**
 * One contiguous run of tool results produced by a single assistant turn.
 */
private data class ToolResultGroup(
  val indices: List<Int>,
  val messages: List<LlmMessage>,
  val tokens: Int
)

/**
 * The cheap half of the two-tier system: drop the payloads of old tool results
 * without calling the model at all.
 *
 * The agent's own history in SQLite is never modified — this only rewrites the
 * in-memory message list that the *next request* carries, so the chat still
 * shows every card and every line the user has ever seen. When the model needs
 * the content again it simply re-runs the tool.
 *
 * What is preserved: system messages, user messages, assistant text and tool
 * calls, media, failed results (unless explicitly enabled), and the most recent
 * tool-result groups.
 */
object Microcompact {

  fun run(
    messages: List<LlmMessage>,
    config: CompactPolicyConfig = CompactPolicyConfig(),
    currentTokens: Int = estimateMessageTokens(messages),
    idleMillis: Long = 0
  ): MicrocompactResult {
    val trigger = CompactPolicy.microcompactTrigger(currentTokens, config, idleMillis)
    if (trigger == CompactPolicy.MicrocompactTrigger.DISABLED || trigger == CompactPolicy.MicrocompactTrigger.NONE) {
      return MicrocompactResult.skipped(trigger, messages)
    }

    val groups = toolResultGroups(messages, config.microcompactTools)
    if (groups.isEmpty()) return MicrocompactResult.skipped(trigger, messages)

    // Always keep the newest groups: the model is usually reasoning about them.
    val clearable = groups.dropLast(config.keepRecentToolResultGroups.coerceAtLeast(1))
    if (clearable.isEmpty()) return MicrocompactResult.skipped(trigger, messages)

    var saved = 0
    val clearedIndices = mutableSetOf<Int>()
    val replacements = mutableMapOf<Int, LlmMessage>()
    for (group in clearable) {
      for (position in group.indices.indices) {
        val index = group.indices[position]
        val message = group.messages[position]
        if (message.content == CLEARED_TOOL_RESULT_PLACEHOLDER) continue // already cleared
        if (!config.microcompactClearErrors && isErrorResult(message)) continue
        if (message.inlineData.isNotEmpty()) continue // media must survive verbatim
        val messageSaved = (estimateMessageTokens(message) - estimateMessageTokens(
          message.copy(content = CLEARED_TOOL_RESULT_PLACEHOLDER)
        )).coerceAtLeast(0)
        if (messageSaved <= 0) continue
        saved += messageSaved
        clearedIndices += index
        replacements[index] = message.copy(
          content = "$CLEARED_TOOL_RESULT_PLACEHOLDER (${message.toolName.orEmpty().ifBlank { "tool" }} output dropped to save context; re-run the tool if you need it again)"
        )
      }
    }

    // Not worth a rewritten transcript: revert and let the full compact run.
    if (clearedIndices.isEmpty() || saved < config.microcompactMinSavedTokens) {
      return MicrocompactResult.skipped(trigger, messages)
    }

    val rewritten = messages.mapIndexed { index, message -> replacements[index] ?: message }
    return MicrocompactResult(rewritten, clearedIndices.size, saved, trigger)
  }

  /**
   * Groups consecutive tool results, but only those belonging to the same
   * assistant turn: results of the newest turn are the most valuable and are
   * kept by [CompactPolicyConfig.keepRecentRounds].
   */
  private fun toolResultGroups(messages: List<LlmMessage>, tools: Set<String>): List<ToolResultGroup> {
    val groups = mutableListOf<ToolResultGroup>()
    var indices = mutableListOf<Int>()
    var group = mutableListOf<LlmMessage>()
    var groupTokens = 0

    fun flush() {
      if (group.isNotEmpty()) groups.add(ToolResultGroup(indices.toList(), group.toList(), groupTokens))
      indices = mutableListOf()
      group = mutableListOf()
      groupTokens = 0
    }

    messages.forEachIndexed { index, message ->
      if (message.role == LlmRole.TOOL && message.toolName in tools) {
        indices.add(index)
        group.add(message)
        groupTokens += estimateMessageTokens(message)
        return@forEachIndexed
      }
      // Any non-tool message ends the run; a new assistant call starts a new one.
      flush()
    }
    flush()
    return groups
  }

  private fun isErrorResult(message: LlmMessage): Boolean {
    if (message.isError) return true
    val text = message.content
    return text.startsWith("ERROR:") || text.contains("\nexit code:") || text.contains("User denied")
  }
}
