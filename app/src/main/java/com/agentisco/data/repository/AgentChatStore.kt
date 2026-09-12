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
 * Persistence facade for agent chat. All writes are funneled through a single
 * serialized queue so runtime events emitted at stream speed keep their order
 * in the database, and callers never block the UI collector.
 */
class AgentChatStore(context: Context?) {

  private val db: ChatDatabase? = context?.let {
    Room.databaseBuilder(it.applicationContext, ChatDatabase::class.java, "agentisco_chat.db")
      .fallbackToDestructiveMigration()
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

  companion object {
    fun newId(): String = UUID.randomUUID().toString()
  }
}
