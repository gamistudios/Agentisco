package com.agentisco

import androidx.test.core.app.ApplicationProvider
import com.agentisco.data.local.chat.AgentBlockEntity
import com.agentisco.data.local.chat.AgentMessageEntity
import com.agentisco.data.local.chat.AgentSessionEntity
import com.agentisco.data.repository.AgentChatStore
import com.agentisco.ui.AgentTurnItem
import com.agentisco.ui.toChatItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for the "edited message swallows the response" bug.
 *
 * The streamed reply is persisted as blocks whose messageUuid points at the
 * assistant turn row. When that row is missing, the Room relation keyed on
 * agent_messages.uuid never surfaces those blocks, so the UI renders nothing
 * even though the request succeeded. Any code path that regenerates a turn
 * must therefore persist the turn row before streaming.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatStoreTurnVisibilityTest {

  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
  private val store = AgentChatStore(context)

  private val sessionId = "sess-edit"

  /** Writes through [AgentChatStore.await] so reads observe them deterministically. */
  private suspend fun put(message: AgentMessageEntity) = store.await { it.insertMessage(message) }
  private suspend fun put(block: AgentBlockEntity) = store.await { it.insertBlock(block) }

  private suspend fun seed(): String {
    val now = System.currentTimeMillis()
    store.createSessionBlocking(
      AgentSessionEntity(
        id = sessionId, projectId = "/proj", title = "Original", status = "running",
        createdAt = now, updatedAt = now
      )
    )
    val userUuid = AgentChatStore.newId()
    put(
      AgentMessageEntity(
        uuid = userUuid, sessionId = sessionId, role = "user", content = "first draft",
        status = "sent", statusMessage = "", createdAt = now
      )
    )
    return userUuid
  }

  @Test
  fun `blocks attached to an unpersisted turn uuid are invisible`() = runBlocking {
    val userUuid = seed()

    // The old buggy behaviour: stream into a turn uuid that has no message row.
    val phantomTurn = AgentChatStore.newId()
    put(
      AgentBlockEntity(
        uuid = AgentChatStore.newId(), messageUuid = phantomTurn, kind = "text", name = "",
        argsJson = "", status = "done", summary = "the real reply", detail = "",
        exitCode = null, createdAt = System.currentTimeMillis()
      )
    )

    val visible = store.messagesWithBlocks(sessionId).first()
    assertEquals("the user message must be visible", 1, visible.size)
    assertEquals(userUuid, visible[0].message.uuid)
    assertTrue("orphaned blocks must not leak into the message list", visible[0].blocks.isEmpty())
  }

  @Test
  fun `blocks attached to a persisted turn uuid are visible`() = runBlocking {
    val userUuid = seed()

    // The fixed behaviour: persist the turn row first, then stream into it.
    val turnUuid = AgentChatStore.newId()
    put(
      AgentMessageEntity(
        uuid = turnUuid, sessionId = sessionId, role = "assistant_turn", content = "",
        status = "running", statusMessage = "Starting…", createdAt = System.currentTimeMillis()
      )
    )
    put(
      AgentBlockEntity(
        uuid = AgentChatStore.newId(), messageUuid = turnUuid, kind = "text", name = "",
        argsJson = "", status = "done", summary = "the real reply", detail = "",
        exitCode = null, createdAt = System.currentTimeMillis()
      )
    )

    val visible = store.messagesWithBlocks(sessionId).first().map { it.message.uuid to it.blocks.size }
    assertEquals(
      "user + assistant turn, both rendered", listOf(userUuid to 0, turnUuid to 1), visible
    )
  }

  @Test
  fun `editing trims later messages and their blocks`() = runBlocking {
    val sessionId = "sess-trim"
    val now = System.currentTimeMillis()
    store.createSessionBlocking(
      AgentSessionEntity(
        id = sessionId, projectId = "/proj", title = "T", status = "running",
        createdAt = now, updatedAt = now
      )
    )
    val user = AgentChatStore.newId()
    val staleTurn = AgentChatStore.newId()
    put(AgentMessageEntity(uuid = user, sessionId = sessionId, role = "user", content = "q", status = "sent", statusMessage = "", createdAt = now))
    put(AgentMessageEntity(uuid = staleTurn, sessionId = sessionId, role = "assistant_turn", content = "old answer", status = "completed", statusMessage = "", createdAt = now))
    put(AgentBlockEntity(uuid = AgentChatStore.newId(), messageUuid = staleTurn, kind = "text", name = "", argsJson = "", status = "done", summary = "old answer", detail = "", exitCode = null, createdAt = now))

    store.deleteMessagesAfter(sessionId, user)

    val remaining = store.messagesWithBlocks(sessionId).first()
    assertEquals("only the edited user message survives", 1, remaining.size)
    assertEquals(user, remaining[0].message.uuid)
  }

  /**
   * One session can be answered by different models: the composer dropdown is
   * live between turns, so attribution is written per message row and must
   * never bleed across turns in the same session.
   */
  @Test
  fun `each turn keeps its own provider and model within one session`() = runBlocking {
    val sessionId = "sess-attribution"
    val now = System.currentTimeMillis()
    store.createSessionBlocking(
      AgentSessionEntity(
        id = sessionId, projectId = "/proj", title = "T", status = "running",
        createdAt = now, updatedAt = now
      )
    )
    put(
      AgentMessageEntity(
        uuid = AgentChatStore.newId(), sessionId = sessionId, role = "assistant_turn",
        content = "", status = "completed", statusMessage = "", createdAt = now,
        providerName = "Gemini", modelName = "gemini-3.1-flash-lite"
      )
    )
    put(
      AgentMessageEntity(
        uuid = AgentChatStore.newId(), sessionId = sessionId, role = "assistant_turn",
        content = "", status = "completed", statusMessage = "", createdAt = now,
        providerName = "OpenRouter", modelName = "gpt-oss-120b"
      )
    )
    // A turn recorded before v4 has no attribution at all.
    put(
      AgentMessageEntity(
        uuid = AgentChatStore.newId(), sessionId = sessionId, role = "assistant_turn",
        content = "", status = "completed", statusMessage = "", createdAt = now
      )
    )

    val turns = store.messagesWithBlocks(sessionId).first().map { it.toChatItem() }
    assertEquals(
      listOf("Gemini · gemini-3.1-flash-lite", "OpenRouter · gpt-oss-120b", ""),
      turns.map {
        val t = it as AgentTurnItem
        listOfNotNull(t.providerName, t.modelName).joinToString(" · ")
      }
    )
  }
}
