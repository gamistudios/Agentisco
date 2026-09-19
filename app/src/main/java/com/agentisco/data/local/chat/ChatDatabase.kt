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
  val createdAt: Long,
  /**
   * Provider and model that produced this turn, kept as display names rather
   * than ids: a session can switch models mid-conversation, and a later
   * deletion or rename of the configured record must not erase the history of
   * what actually answered. Null on user rows and on rows from before v4.
   */
  val providerName: String? = null,
  val modelName: String? = null
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

  // Messages and blocks cascade off their session, so this removes a whole
  // project's conversation history in one go.
  @Query("DELETE FROM agent_sessions WHERE projectId = :projectId")
  suspend fun deleteSessionsForProject(projectId: String)

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

  @Query("SELECT rowId FROM agent_messages WHERE uuid = :uuid LIMIT 1")
  suspend fun messageRowId(uuid: String): Long?

  @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND rowId > :minRowId")
  suspend fun deleteMessagesAfter(sessionId: String, minRowId: Long)

  @Query("DELETE FROM agent_blocks WHERE messageUuid IN (SELECT uuid FROM agent_messages WHERE sessionId = :sessionId AND rowId > :minRowId)")
  suspend fun deleteBlocksForMessagesAfter(sessionId: String, minRowId: Long)

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
  version = 5,
  exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {
  abstract fun chatDao(): ChatDao

  companion object {
    /** True when [column] still exists on [table] (older SQLite has no DROP COLUMN). */
    private fun hasColumn(
      db: androidx.sqlite.db.SupportSQLiteDatabase,
      table: String,
      column: String
    ): Boolean {
      val cursor = db.query("PRAGMA table_info(`$table`)")
      return try {
        val nameIndex = cursor.getColumnIndex("name")
        var found = false
        while (cursor.moveToNext() && !found) found = cursor.getString(nameIndex) == column
        found
      } finally {
        cursor.close()
      }
    }

    val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
      override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agent_blocks ADD COLUMN callId TEXT")
      }
    }

    val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
      override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agent_sessions ADD COLUMN modelId TEXT")
        db.execSQL("ALTER TABLE agent_sessions ADD COLUMN providerId TEXT")
      }
    }

    val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
      override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agent_messages ADD COLUMN providerName TEXT")
        db.execSQL("ALTER TABLE agent_messages ADD COLUMN modelName TEXT")
      }
    }

    /**
     * Drops the session-level model/provider columns, which per-message
     * attribution replaced. Pre-drop rebuild of agent_sessions is the only way
     * on the SQLite shipped by older APIs, and dropping a parent table deletes
     * its children when foreign keys are enforced — which Room does not arrange
     * for during a migration. So every descendant row is snapshotted first and
     * restored afterwards, which is a no-op when nothing was cascaded away.
     */
    val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
      override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        if (!hasColumn(db, "agent_sessions", "modelId") &&
          !hasColumn(db, "agent_sessions", "providerId")
        ) return

        db.execSQL(
          "CREATE TABLE `agent_sessions_new` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, " +
            "`title` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
          "INSERT INTO `agent_sessions_new` " +
            "SELECT `id`, `projectId`, `title`, `status`, `createdAt`, `updatedAt` FROM `agent_sessions`"
        )
        db.execSQL("CREATE TABLE `_sessions_v4_messages` AS SELECT * FROM `agent_messages`")
        db.execSQL("CREATE TABLE `_sessions_v4_blocks` AS SELECT * FROM `agent_blocks`")
        db.execSQL("DROP TABLE `agent_sessions`")
        db.execSQL("ALTER TABLE `agent_sessions_new` RENAME TO `agent_sessions`")
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_agent_sessions_projectId_updatedAt` " +
            "ON `agent_sessions` (`projectId`, `updatedAt`)"
        )
        db.execSQL("INSERT OR IGNORE INTO `agent_messages` SELECT * FROM `_sessions_v4_messages`")
        db.execSQL("INSERT OR IGNORE INTO `agent_blocks` SELECT * FROM `_sessions_v4_blocks`")
        db.execSQL("DROP TABLE `_sessions_v4_messages`")
        db.execSQL("DROP TABLE `_sessions_v4_blocks`")
      }
    }
  }
}
