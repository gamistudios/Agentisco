package com.agentisco.data.local.chat

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Normalized persistence for agent conversations:
 * project → session → message (user prompt or agent turn) → block (tool
 * action / approval / streamed text segment inside a turn).
 */
@Entity(
  tableName = "agent_sessions",
  indices = [Index(value = ["projectId", "updatedAt"])]
)
data class AgentSessionEntity(
  @PrimaryKey val id: String,
  val projectId: String,
  val title: String,
  /** running | completed | failed | cancelled | interrupted */
  val status: String,
  val createdAt: Long,
  val updatedAt: Long
)

@Entity(
  tableName = "agent_messages",
  indices = [Index(value = ["uuid"], unique = true), Index(value = ["sessionId"])],
  foreignKeys = [
    ForeignKey(
      entity = AgentSessionEntity::class,
      parentColumns = ["id"],
      childColumns = ["sessionId"],
      onDelete = ForeignKey.CASCADE
    )
  ]
)
data class AgentMessageEntity(
  @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
  val uuid: String,
  val sessionId: String,
  /** user | assistant_turn */
  val role: String,
  /** user prompt text, or the agent turn's full response text (live-updated). */
  val content: String,
  /** user: sent. turn: running | completed | failed | cancelled | interrupted */
  val status: String,
  /** last runtime status line, or the failure/cancel reason. */
  val statusMessage: String,
  val createdAt: Long
)

@Entity(
  tableName = "agent_blocks",
  indices = [Index(value = ["uuid"], unique = true), Index(value = ["messageUuid"])],
  foreignKeys = [
    ForeignKey(
      entity = AgentMessageEntity::class,
      parentColumns = ["uuid"],
      childColumns = ["messageUuid"],
      onDelete = ForeignKey.CASCADE
    )
  ]
)
data class AgentBlockEntity(
  @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
  val uuid: String,
  val messageUuid: String,
  /** text | tool | approval */
  val kind: String,
  /** tool name, "approval" for approvals, unused for text. */
  val name: String,
  /** tool args JSON, or the command for approvals. */
  val argsJson: String,
  /** text: streaming | done. tool: running | success | failed. approval: pending | allowed | denied */
  val status: String,
  /** tool summary, approval title. */
  val summary: String,
  /** tool output detail, approval impact description. */
  val detail: String,
  val exitCode: Int?,
  val createdAt: Long,
  /** The model's tool-call id (null for legacy rows) — used for per-call cancellation. */
  val callId: String? = null
)

data class MessageWithBlocks(
  @Embedded val message: AgentMessageEntity,
  @Relation(parentColumn = "uuid", entityColumn = "messageUuid")
  val blocks: List<AgentBlockEntity>
)

@Dao
interface ChatDao {

  // ---- sessions ----

  @Query("SELECT * FROM agent_sessions WHERE projectId = :projectId ORDER BY updatedAt DESC")
  fun sessionsForProject(projectId: String): Flow<List<AgentSessionEntity>>

  @Query("SELECT * FROM agent_sessions WHERE projectId = :projectId ORDER BY updatedAt DESC LIMIT 1")
  suspend fun latestSession(projectId: String): AgentSessionEntity?

  @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
  suspend fun insertSession(session: AgentSessionEntity)

  @Query("UPDATE agent_sessions SET title = :title, updatedAt = :updatedAt WHERE id = :id")
  suspend fun renameSession(id: String, title: String, updatedAt: Long)

  @Query("UPDATE agent_sessions SET status = :status, updatedAt = :updatedAt WHERE id = :id")
  suspend fun setSessionStatus(id: String, status: String, updatedAt: Long)

  @Query("UPDATE agent_sessions SET status = 'interrupted' WHERE status = 'running'")
  suspend fun interruptRunningSessions()

  @Query("UPDATE agent_sessions SET projectId = :newProjectId WHERE projectId = :oldProjectId")
  suspend fun remapProjectSessions(oldProjectId: String, newProjectId: String)

  @Query("DELETE FROM agent_sessions WHERE id = :id")
  suspend fun deleteSession(id: String)

  // ---- messages ----

  @Transaction
  @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY rowId ASC")
  fun messagesWithBlocks(sessionId: String): Flow<List<MessageWithBlocks>>

  @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY rowId ASC")
  suspend fun messagesOnce(sessionId: String): List<AgentMessageEntity>

  @Query("SELECT * FROM agent_messages WHERE uuid = :uuid LIMIT 1")
  suspend fun messageByUuid(uuid: String): AgentMessageEntity?

  @Query("SELECT * FROM agent_blocks WHERE messageUuid = :messageUuid ORDER BY rowId ASC")
  suspend fun blocksForMessage(messageUuid: String): List<AgentBlockEntity>

  @Query("DELETE FROM agent_blocks WHERE messageUuid = :messageUuid AND kind = :kind")
  suspend fun deleteBlocksOfKind(messageUuid: String, kind: String)

  @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
  suspend fun insertMessage(message: AgentMessageEntity)

  @Query("UPDATE agent_messages SET content = :content WHERE uuid = :uuid")
  suspend fun updateMessageContent(uuid: String, content: String)

  @Query("UPDATE agent_messages SET status = :status, statusMessage = :statusMessage WHERE uuid = :uuid")
  suspend fun updateMessageStatus(uuid: String, status: String, statusMessage: String)

  @Query("UPDATE agent_messages SET status = 'interrupted' WHERE status = 'running'")
  suspend fun interruptRunningTurns()

  // ---- blocks ----

  @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
  suspend fun insertBlock(block: AgentBlockEntity)

  @Query(
    "UPDATE agent_blocks SET status = :status, summary = :summary, detail = :detail, exitCode = :exitCode WHERE uuid = :uuid"
  )
  suspend fun updateToolBlock(uuid: String, status: String, summary: String, detail: String, exitCode: Int?)

  @Query("UPDATE agent_blocks SET status = :status WHERE uuid = :uuid")
  suspend fun updateBlockStatus(uuid: String, status: String)

  @Query("UPDATE agent_blocks SET summary = :text WHERE uuid = :uuid")
  suspend fun updateTextBlock(uuid: String, text: String)

  @Query("UPDATE agent_blocks SET status = 'failed', summary = 'Interrupted' WHERE status = 'running'")
  suspend fun failRunningBlocks()

  // ---- cross-session activity ----

  @Query(
    """
    SELECT b.* FROM agent_blocks b
    JOIN agent_messages m ON b.messageUuid = m.uuid
    JOIN agent_sessions s ON m.sessionId = s.id
    WHERE s.projectId = :projectId
    ORDER BY b.rowId DESC LIMIT :limit
    """
  )
  fun recentBlocksForProject(projectId: String, limit: Int): Flow<List<AgentBlockEntity>>
}

@Database(
  entities = [AgentSessionEntity::class, AgentMessageEntity::class, AgentBlockEntity::class],
  version = 2,
  exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {
  abstract fun chatDao(): ChatDao

  companion object {
    val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
      override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agent_blocks ADD COLUMN callId TEXT")
      }
    }
  }
}
