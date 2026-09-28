package com.agentisco.ui

import com.agentisco.data.local.chat.AgentBlockEntity
import com.agentisco.data.local.chat.AgentMessageEntity
import com.agentisco.data.local.chat.MessageWithBlocks

/**
 * Chat-shaped UI model, derived entirely from persisted data (sessions →
 * messages → turn blocks). Stable UUID ids make recomposition, restore, and
 * dedup safe.
 */
sealed class ChatItem {
  abstract val id: String
}

data class UserMessageItem(
  override val id: String,
  val text: String,
  val timestamp: Long
) : ChatItem()

enum class TurnStatus { RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

data class AgentTurnItem(
  override val id: String,
  val status: TurnStatus,
  val statusMessage: String,
  val blocks: List<TurnBlock>,
  /** Provider/model that answered this turn; null on turns recorded before v4. */
  val providerName: String? = null,
  val modelName: String? = null
) : ChatItem()

sealed class TurnBlock {
  abstract val id: String
}

/** A streamed assistant text segment (or the static status line while connecting). */
data class TextBlock(
  override val id: String,
  val text: String,
  val streaming: Boolean
) : TurnBlock()

data class ActionBlock(
  override val id: String,
  val name: String,
  val argsJson: String,
  val running: Boolean,
  val success: Boolean?,
  val summary: String,
  val detail: String,
  val exitCode: Int?,
  /** The model's tool-call id — lets the UI cancel/retry this specific call. */
  val callId: String = "",
  /** True when the user SIGKILLed this call and a retry/continue choice is pending. */
  val cancelled: Boolean = false
) : TurnBlock()

data class ApprovalBlock(
  override val id: String,
  val approvalId: String,
  val command: String,
  val title: String,
  val impact: String,
  val resolved: Boolean,
  val allowed: Boolean,
  /** The agent asked the user to choose instead of approving a command. */
  val isQuestion: Boolean = false,
  /** What the user picked/typed, once answered. */
  val answer: String = "",
  /**
   * True when the request was never decided — the dialog was dismissed, or the
   * turn was stopped. Deliberately distinct from a denial, so the card can stay
   * actionable and never claim the user refused something.
   */
  val stalled: Boolean = false,
  /** The reason the user gave for refusing, when they typed one. */
  val rationale: String = "",
  /** Choices the agent offered a question, so the card can render them inline. */
  val options: List<String> = emptyList()
) : TurnBlock()

/** Streamed model reasoning ("thinking"), rendered as a collapsible box. */
data class ReasoningBlock(
  override val id: String,
  val text: String,
  val streaming: Boolean
) : TurnBlock()

/** A runtime failure rendered as a single red error card in the turn. */
data class ErrorBlock(
  override val id: String,
  val message: String
) : TurnBlock()

/**
 * A compaction note in a turn: the transcript the provider received was
 * summarized. The chat keeps every message; this only explains the model's
 * narrower view and carries the summary for inspection.
 */
data class CompactionBlock(
  override val id: String,
  /** One-line "compacted 42.1k → 6.2k tokens (85% of a 128k window)". */
  val summary: String,
  /** The full summary that replaced the older turns. */
  val summaryText: String,
  val tokensBefore: Int,
  val tokensAfter: Int,
  val contextWindow: Int
) : TurnBlock()

/**
 * Decodes a stored question's choices, tolerating anything that isn't a clean
 * array of strings: a card that cannot render its options must still show the
 * question and the "reopen" affordance rather than crash the chat.
 */
private fun String?.toOptionList(): List<String> {
  if (isNullOrBlank()) return emptyList()
  return runCatching {
    val array = org.json.JSONArray(this)
    (0 until array.length()).mapNotNull { index ->
      array.optString(index).takeIf { it.isNotBlank() }
    }
  }.getOrDefault(emptyList())
}

fun MessageWithBlocks.toChatItem(): ChatItem {
  val message = message
  return if (message.role == "user") {
    UserMessageItem(message.uuid, message.content, message.createdAt)
  } else {
    AgentTurnItem(
      id = message.uuid,
      status = when (message.status) {
        "running" -> TurnStatus.RUNNING
        "paused" -> TurnStatus.PAUSED
        "failed" -> TurnStatus.FAILED
        "cancelled" -> TurnStatus.CANCELLED
        "interrupted" -> TurnStatus.INTERRUPTED
        else -> TurnStatus.COMPLETED
      },
      statusMessage = message.statusMessage,
      blocks = blocks.mapNotNull { it.toTurnBlock() },
      providerName = message.providerName,
      modelName = message.modelName
    )
  }
}

private fun AgentBlockEntity.toTurnBlock(): TurnBlock? = when (kind) {
  "text" -> TextBlock(uuid, summary, status == "streaming")
  "reasoning" -> ReasoningBlock(uuid, summary, status == "streaming")
  "approval", "question" -> ApprovalBlock(
    id = uuid,
    approvalId = name, // approval runtime id stored in `name`
    command = argsJson,
    title = summary,
    impact = detail,
    resolved = status != "pending",
    allowed = status == "allowed",
    isQuestion = kind == "question",
    // Only an actual answer lives in `detail`; a declined/stalled question still
    // has the question text there, which must never render as an answer.
    answer = if (kind == "question" && status == "allowed") detail else "",
    // Never decided: the dialog was dismissed, or the turn was stopped. Kept
    // apart from `denied` so the UI never implies the user refused.
    stalled = status == "stalled",
    // The reason for a refusal lives in `detail` for approvals (whose impact
    // text is stored there too, so it only shows before the decision).
    rationale = if (status == "denied") detail else "",
    options = optionsJson.toOptionList()
  )
  "tool" -> ActionBlock(
    id = uuid,
    name = name,
    argsJson = argsJson,
    running = status == "running",
    success = when (status) {
      "success" -> true
      "failed" -> false
      else -> null
    },
    summary = summary,
    detail = detail,
    exitCode = exitCode,
    callId = callId.orEmpty(),
    cancelled = status == "cancelled"
  )
  "error" -> ErrorBlock(uuid, summary)
  // Compaction only changes what is sent to the provider; the chat above
  // keeps every message and tool result this card refers to.
  "compaction" -> CompactionBlock(
    id = uuid,
    summary = summary,
    summaryText = detail,
    tokensBefore = 0,
    tokensAfter = 0,
    contextWindow = 0
  )
  else -> null
}
