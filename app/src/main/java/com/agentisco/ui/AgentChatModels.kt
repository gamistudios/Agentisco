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
  val blocks: List<TurnBlock>
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
  val exitCode: Int?
) : TurnBlock()

data class ApprovalBlock(
  override val id: String,
  val approvalId: String,
  val command: String,
  val title: String,
  val impact: String,
  val resolved: Boolean,
  val allowed: Boolean
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
      blocks = blocks.mapNotNull { it.toTurnBlock() }
    )
  }
}

private fun AgentBlockEntity.toTurnBlock(): TurnBlock? = when (kind) {
  "text" -> TextBlock(uuid, summary, status == "streaming")
  "reasoning" -> ReasoningBlock(uuid, summary, status == "streaming")
  "approval" -> ApprovalBlock(
    id = uuid,
    approvalId = name, // approval runtime id stored in `name`
    command = argsJson,
    title = summary,
    impact = detail,
    resolved = status != "pending",
    allowed = status == "allowed"
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
    exitCode = exitCode
  )
  "error" -> ErrorBlock(uuid, summary)
  else -> null
}
