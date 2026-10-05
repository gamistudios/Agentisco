package com.awaki.ui

import com.awaki.data.local.chat.AgentBlockEntity
import com.awaki.data.local.chat.AgentMessageEntity
import com.awaki.data.local.chat.MessageWithBlocks

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
  val cancelled: Boolean = false,
  /**
   * The work a delegated agent did for this call, in the order it did it: its
   * tool cards, its text, its thinking. Only a `delegate` call ever has any —
   * a specialist cannot delegate further.
   */
  val children: List<TurnBlock> = emptyList(),
  /** The brief the orchestrator handed that agent, for `delegate` calls only. */
  val delegation: DelegationBrief? = null,
  /** When the call started — lets the plan time the steps between two publishes. */
  val createdAt: Long = 0L
) : TurnBlock()

/**
 * What a delegation asked for, read back off the `delegate` call's own arguments:
 * the specialist's brief is part of the record, so it survives a restart without a
 * second copy anywhere.
 */
data class DelegationBrief(
  val role: String,
  val description: String,
  val prompt: String
)

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

/**
 * Reads a `delegate` call's arguments back into the brief it carried. An
 * argument blob that is not JSON, or holds neither a role nor a prompt, simply
 * has no brief — a card must never invent what the agent was asked.
 */
private fun String?.toDelegationBrief(): DelegationBrief? {
  if (isNullOrBlank()) return null
  return runCatching {
    val obj = org.json.JSONObject(this)
    val role = obj.optString("role")
    val prompt = obj.optString("prompt")
    if (role.isBlank() && prompt.isBlank()) null
    else DelegationBrief(role, obj.optString("description"), prompt)
  }.getOrNull()
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
      blocks = blocks.toTurnBlocks(),
      providerName = message.providerName,
      modelName = message.modelName
    )
  }
}

/**
 * Decodes a turn's blocks and hangs each delegated agent's work under the
 * delegation card that started it. Blocks are written in event order, so the
 * `delegate` card is always recorded before the blocks nested under it.
 */
fun List<AgentBlockEntity>.toTurnBlocks(): List<TurnBlock> {
  val nested = LinkedHashMap<String, MutableList<TurnBlock>>()
  val mainline = mutableListOf<TurnBlock>()
  forEach { entity ->
    val block = entity.toTurnBlock() ?: return@forEach
    val parent = entity.parentCallId
    if (parent.isNullOrBlank()) mainline.add(block)
    else nested.getOrPut(parent) { mutableListOf() }.add(block)
  }
  if (nested.isEmpty()) return mainline
  return mainline.map { block ->
    if (block is ActionBlock && block.callId.isNotBlank()) {
      val children = nested[block.callId]
      if (children.isNullOrEmpty()) block else block.copy(children = children)
    } else block
  }
}

fun AgentBlockEntity.toTurnBlock(): TurnBlock? = when (kind) {
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
    // The reason for a refusal lives in `detail`, which held the impact text
    // while the request was still pending — so a card that was never decided
    // keeps showing what the action would have done, and a denial shows the
    // user's own words instead.
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
    cancelled = status == "cancelled",
    delegation = if (name == "delegate") argsJson.toDelegationBrief() else null,
    createdAt = createdAt
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
