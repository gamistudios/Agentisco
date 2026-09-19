package com.agentisco.data.repository

import android.content.Context
import androidx.room.Room
import com.agentisco.data.local.chat.AgentBlockEntity
import com.agentisco.data.local.chat.AgentMessageEntity
import com.agentisco.data.local.chat.AgentSessionEntity
import com.agentisco.data.local.chat.ChatDatabase
import com.agentisco.data.local.chat.ChatDao
import com.agentisco.data.local.chat.MessageWithBlocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * One reconstructed conversation message for LLM requests, built from the
 * persisted session: user prompts, assistant responses, tool calls with their
 * arguments, and tool results (including failures).
 */
data class ChatHistoryMessage(
  val role: String, // user | assistant | assistant_tool_call | tool
  val content: String,
  val toolName: String? = null,
  val toolArgs: String? = null,
  val toolCallId: String? = null
)

/**
 * Persistence facade for agent chat. All writes are funneled through a single
 * serialized queue so runtime events emitted at stream speed keep their order
 * in the database, and callers never block the UI collector.
 */
class AgentChatStore(context: Context?) {

  private val db: ChatDatabase? = context?.let {
    Room.databaseBuilder(it.applicationContext, ChatDatabase::class.java, "agentisco_chat.db")
      .addMigrations(
        ChatDatabase.MIGRATION_1_2,
        ChatDatabase.MIGRATION_2_3,
        ChatDatabase.MIGRATION_3_4,
        ChatDatabase.MIGRATION_4_5
      )
      .build()
  }
  private val dao: ChatDao? = db?.chatDao()
  val available: Boolean get() = dao != null

  private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val writeQueue = Channel<(suspend () -> Unit)>(capacity = Channel.UNLIMITED)

  init {
    storeScope.launch {
      for (write in writeQueue) runCatching { write() }
    }
  }

  /** Enqueue a write; executes in order, never throws to the caller. */
  private fun enqueue(write: suspend () -> Unit) {
    if (dao == null) return
    writeQueue.trySend(write)
  }

  suspend fun await(write: suspend (ChatDao) -> Unit) {
    val d = dao ?: return
    runCatching { write(d) }
  }

  // ---- reads ----

  fun sessionsForProject(projectId: String): Flow<List<AgentSessionEntity>> =
    dao?.sessionsForProject(projectId) ?: flowOf(emptyList())

  fun messagesWithBlocks(sessionId: String): Flow<List<MessageWithBlocks>> =
    dao?.messagesWithBlocks(sessionId) ?: flowOf(emptyList())

  fun recentBlocksForProject(projectId: String, limit: Int = 5): Flow<List<AgentBlockEntity>> =
    dao?.recentBlocksForProject(projectId, limit) ?: flowOf(emptyList())

  suspend fun latestSession(projectId: String): AgentSessionEntity? =
    dao?.let { runCatching { it.latestSession(projectId) }.getOrNull() }

  fun updateTextBlock(uuid: String, text: String) =
    enqueue { dao!!.updateTextBlock(uuid, text) }

  // ---- session writes ----

  fun createSession(session: AgentSessionEntity) = enqueue { dao!!.insertSession(session) }

  suspend fun createSessionBlocking(session: AgentSessionEntity) = await { it.insertSession(session) }

  fun renameSession(id: String, title: String) =
    enqueue { dao!!.renameSession(id, title, System.currentTimeMillis()) }

  fun setSessionStatus(id: String, status: String) =
    enqueue { dao!!.setSessionStatus(id, status, System.currentTimeMillis()) }

  fun deleteSession(id: String) = enqueue { dao!!.deleteSession(id) }

  /** Deletes every session (and its cascaded messages/blocks) for a project. */
  fun deleteSessionsForProject(projectId: String) =
    enqueue { dao!!.deleteSessionsForProject(projectId) }

  /** Mark any turns/sessions left running by a previous process as interrupted. */
  suspend fun recoverInterrupted() = await {
    it.interruptRunningSessions()
    it.interruptRunningTurns()
    it.failRunningBlocks()
  }

  /**
   * Prior conversation as (role, text) pairs for LLM history injection: user
   * prompts plus the text of completed turns only. The just-sent user message
   * is excluded — the runtime appends the current prompt itself.
   */
  suspend fun conversationHistory(sessionId: String): List<Pair<String, String>> {
    val d = dao ?: return emptyList()
    return runCatching {
      val messages = d.messagesOnce(sessionId)
      val lastUserIndex = messages.indexOfLast { it.role == "user" }
      messages.mapIndexedNotNull { index, m ->
        when {
          index == lastUserIndex -> null
          m.role == "user" && m.content.isNotBlank() -> "user" to m.content
          m.role == "assistant_turn" && m.status == "completed" && m.content.isNotBlank() -> "assistant" to m.content
          else -> null
        }
      }
    }.getOrDefault(emptyList())
  }

  // ---- message writes ----

  fun insertMessage(message: AgentMessageEntity) = enqueue { dao!!.insertMessage(message) }

  fun updateMessageContent(uuid: String, content: String) =
    enqueue { dao!!.updateMessageContent(uuid, content) }

  fun updateMessageStatus(uuid: String, status: String, statusMessage: String) =
    enqueue { dao!!.updateMessageStatus(uuid, status, statusMessage) }

  // ---- block writes ----

  fun insertBlock(block: AgentBlockEntity) = enqueue { dao!!.insertBlock(block) }

  fun updateToolBlock(uuid: String, status: String, summary: String, detail: String, exitCode: Int?) =
    enqueue { dao!!.updateToolBlock(uuid, status, summary, detail, exitCode) }

  fun updateBlockStatus(uuid: String, status: String) =
    enqueue { dao!!.updateBlockStatus(uuid, status) }

  // ---- full conversation reconstruction (for LLM requests & retries) ----

  /**
   * Rebuilds the complete LLM conversation for [sessionId] from SQLite: user
   * messages, assistant text, tool calls + real results, and denial outcomes.
   *
   * @param excludeLastUser skip the newest user message (a fresh prompt the
   *   runtime appends itself).
   * @param currentTurnUuid for a failed turn being retried: include only its
   *   tool calls/results (not partial text) so the retry resumes the exact
   *   pending request.
   */
  suspend fun buildConversationMessages(
    sessionId: String,
    excludeLastUser: Boolean = false,
    currentTurnUuid: String? = null
  ): List<ChatHistoryMessage> {
    val d = dao ?: return emptyList()
    return runCatching {
      val messages = d.messagesOnce(sessionId)
      val lastUserIndex = messages.indexOfLast { it.role == "user" }
      val result = mutableListOf<ChatHistoryMessage>()
      messages.forEachIndexed { index, m ->
        if (m.role == "user") {
          if (!(excludeLastUser && index == lastUserIndex) && m.content.isNotBlank()) {
            result.add(ChatHistoryMessage("user", m.content))
          }
          return@forEachIndexed
        }
        // assistant turn
        val blocks = d.blocksForMessage(m.uuid)
        val includeText = m.status == "completed" && m.uuid != currentTurnUuid
        val pendingText = StringBuilder()
        fun flushText() {
          val text = pendingText.toString().trim()
          if (text.isNotEmpty()) result.add(ChatHistoryMessage("assistant", text))
          pendingText.setLength(0)
        }
        blocks.forEach { b ->
          when (b.kind) {
            "text" -> if (includeText && b.status == "done" && b.summary.isNotBlank()) {
              pendingText.append(b.summary).append("\n\n")
            }
            "tool" -> {
              flushText()
              val callId = "call_" + b.uuid.take(12)
              result.add(ChatHistoryMessage("assistant_tool_call", "", toolName = b.name, toolArgs = b.argsJson, toolCallId = callId))
              result.add(
                ChatHistoryMessage(
                  "tool",
                  b.detail.ifBlank { b.summary.ifBlank { "(no output)" } },
                  toolName = b.name, toolCallId = callId
                )
              )
            }
            "approval" -> {
              flushText()
              // Only denied approvals need explaining to the model.
              if (b.status == "denied") {
                val callId = "call_" + b.uuid.take(12)
                val command = b.argsJson
                val escaped = command.replace("\\", "\\\\").replace("\"", "\\\"")
                result.add(
                  ChatHistoryMessage(
                    "assistant_tool_call", "", toolName = "run_command",
                    toolArgs = "{\"command\": \"$escaped\"}", toolCallId = callId
                  )
                )
                result.add(
                  ChatHistoryMessage("tool", "User denied permission to run: $command", toolName = "run_command", toolCallId = callId)
                )
              }
            }
            // "error" blocks are UI diagnostics; the failing request itself is retried.
          }
        }
        flushText()
      }
      result
    }.getOrDefault(emptyList())
  }

  /** Re-keys a project's chat sessions after the project folder moved. */
  suspend fun remapProjectSessionsBlocking(oldProjectId: String, newProjectId: String) = await {
    it.remapProjectSessions(oldProjectId, newProjectId)
  }

  /** Removes persisted error cards of a turn (e.g. before a manual retry). */
  fun clearErrorBlocks(turnUuid: String) = enqueue {
    dao!!.deleteBlocksOfKind(turnUuid, "error")
  }

  suspend fun getMessage(turnUuid: String) = dao?.let { runCatching { it.messageByUuid(turnUuid) }.getOrNull() }

  /** Gets the rowId for a message uuid (used for ordering). */
  suspend fun getMessageRowId(uuid: String): Long? = dao?.messageRowId(uuid)

  /** Deletes all messages in a session after the given message's rowId. */
  suspend fun deleteMessagesAfter(sessionId: String, afterUuid: String) = await { d ->
    val rowId = d.messageRowId(afterUuid) ?: return@await
    d.deleteMessagesAfter(sessionId, rowId)
    d.deleteBlocksForMessagesAfter(sessionId, rowId)
  }

  companion object {
    fun newId(): String = UUID.randomUUID().toString()
  }
}
