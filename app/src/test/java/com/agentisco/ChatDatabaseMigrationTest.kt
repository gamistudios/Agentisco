package com.agentisco

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.agentisco.data.repository.AgentChatStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the schema migrations on a real, pre-populated database file.
 *
 * The v4→v5 step rebuilds agent_sessions, which is the parent of
 * agent_messages (ON DELETE CASCADE) and transitively of agent_blocks. A
 * rebuild written naively can therefore erase every stored conversation while
 * still leaving a perfectly valid schema — so what is asserted here is the
 * data, not just the shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatDatabaseMigrationTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()

  /** The schema exactly as migrations 1_2 / 2_3 / 3_4 leave it, with one conversation in it. */
  private fun writeLegacyDatabase() {
    val file = context.getDatabasePath(DB_NAME)
    file.parentFile?.mkdirs()
    val legacy = SQLiteDatabase.openOrCreateDatabase(file, null)
    legacy.use {
      it.execSQL(
        "CREATE TABLE `agent_sessions` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, " +
          "`title` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
          "`updatedAt` INTEGER NOT NULL, `modelId` TEXT, `providerId` TEXT, PRIMARY KEY(`id`))"
      )
      it.execSQL(
        "CREATE INDEX `index_agent_sessions_projectId_updatedAt` " +
          "ON `agent_sessions` (`projectId`, `updatedAt`)"
      )
      it.execSQL(
        "CREATE TABLE `agent_messages` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
          "`uuid` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `role` TEXT NOT NULL, " +
          "`content` TEXT NOT NULL, `status` TEXT NOT NULL, `statusMessage` TEXT NOT NULL, " +
          "`createdAt` INTEGER NOT NULL, `providerName` TEXT, `modelName` TEXT, " +
          "FOREIGN KEY(`sessionId`) REFERENCES `agent_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
      )
      it.execSQL("CREATE UNIQUE INDEX `index_agent_messages_uuid` ON `agent_messages` (`uuid`)")
      it.execSQL("CREATE INDEX `index_agent_messages_sessionId` ON `agent_messages` (`sessionId`)")
      it.execSQL(
        "CREATE TABLE `agent_blocks` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
          "`uuid` TEXT NOT NULL, `messageUuid` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
          "`name` TEXT NOT NULL, `argsJson` TEXT NOT NULL, `status` TEXT NOT NULL, " +
          "`summary` TEXT NOT NULL, `detail` TEXT NOT NULL, `exitCode` INTEGER, " +
          "`createdAt` INTEGER NOT NULL, `callId` TEXT, " +
          "FOREIGN KEY(`messageUuid`) REFERENCES `agent_messages`(`uuid`) ON UPDATE NO ACTION ON DELETE CASCADE)"
      )
      it.execSQL("CREATE UNIQUE INDEX `index_agent_blocks_uuid` ON `agent_blocks` (`uuid`)")
      it.execSQL("CREATE INDEX `index_agent_blocks_messageUuid` ON `agent_blocks` (`messageUuid`)")
      it.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
      it.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'legacy')")

      it.execSQL(
        "INSERT INTO `agent_sessions` VALUES " +
          "('s1','/proj','old chat','completed',1,2,'model-record-1','provider-1')"
      )
      it.execSQL(
        "INSERT INTO `agent_messages` VALUES " +
          "(1,'m1','s1','user','the original prompt','sent','',10,NULL,NULL)," +
          "(2,'m2','s1','assistant_turn','the stored answer','completed','',11,'Gemini','gemini-3.1-flash-lite')"
      )
      it.execSQL(
        "INSERT INTO `agent_blocks` VALUES " +
          "(1,'b1','m2','text','','','done','the stored answer','',NULL,12,NULL)"
      )
      // Room decides whether to migrate from this number.
      it.execSQL("PRAGMA user_version = 4")
    }
  }

  @Test
  fun `upgrading a v4 database preserves the stored conversation`() {
    writeLegacyDatabase()

    val store = AgentChatStore(context)
    runBlocking {
      val sessions = store.sessionsForProject("/proj").first()
      assertEquals("the session must survive the table rebuild", listOf("s1"), sessions.map { it.id })

      val messages = store.messagesWithBlocks("s1").first()
      assertEquals("both turns must survive", 2, messages.size)
      assertEquals(
        "turn order is rowId-based and must not be reshuffled",
        listOf("m1", "m2"), messages.map { it.message.uuid }
      )
      assertEquals("the original prompt", messages[0].message.content)
      assertEquals("the stored answer", messages[1].message.content)
      assertEquals(
        "per-message attribution is kept",
        listOf<Pair<String?, String?>>(null to null, "Gemini" to "gemini-3.1-flash-lite"),
        messages.map { it.message.providerName to it.message.modelName }
      )
      assertEquals(
        "turn blocks must survive", 1, messages[1].blocks.size
      )
      assertEquals("b1", messages[1].blocks.single().uuid)

      // And the migrated database is still writable.
      store.createSessionBlocking(
        com.agentisco.data.local.chat.AgentSessionEntity(
          id = "s2", projectId = "/proj", title = "post-migration", status = "active",
          createdAt = 20, updatedAt = 20
        )
      )
      assertEquals(
        listOf("s2", "s1"),
        store.sessionsForProject("/proj").first().map { it.id }
      )
    }
  }

  @Test
  fun `the dropped columns are gone from the schema`() {
    writeLegacyDatabase()
    // Opening through the store runs the migrations.
    val store = AgentChatStore(context)
    runBlocking { store.sessionsForProject("/proj").first() }

    val file = context.getDatabasePath(DB_NAME)
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
      db.rawQuery("PRAGMA table_info(`agent_sessions`)", null).use { c ->
        val names = generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndex("name")) else null }
          .toList()
        assertEquals(
          "session rows no longer carry model/provider columns",
          listOf("id", "projectId", "title", "status", "createdAt", "updatedAt"), names
        )
      }
      db.rawQuery("PRAGMA foreign_key_check", null).use { c ->
        assertEquals("no dangling foreign keys after the rebuild", 0, c.count)
      }
    }
  }

  private companion object {
    const val DB_NAME = "agentisco_chat.db"
  }
}
