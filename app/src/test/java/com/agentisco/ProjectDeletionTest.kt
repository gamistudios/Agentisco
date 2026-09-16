package com.agentisco

import androidx.test.core.app.ApplicationProvider
import com.agentisco.data.local.chat.AgentBlockEntity
import com.agentisco.data.local.chat.AgentMessageEntity
import com.agentisco.data.local.chat.AgentSessionEntity
import com.agentisco.data.model.Project
import com.agentisco.data.repository.AgentChatStore
import com.agentisco.workspace.filesystem.ProjectFileSystem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Regression coverage for "Remove doesn't remove the project".
 *
 * The old [com.agentisco.data.repository.WorkspaceRepository.removeProject] only
 * deleted the registry entry; the folder stayed on disk and the next project-list
 * refresh re-registered it as a legacy project, so it reappeared instantly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectDeletionTest {

  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
  private val fs = ProjectFileSystem(context)
  private val store = AgentChatStore(context)

  private fun makeProject(dir: File, name: String, imported: Boolean = false): Project {
    fs.createProject(name, "test", dir)
    return Project(
      id = "proj-${dir.name}", name = name, branch = "main", lastActivity = "now",
      path = dir.absolutePath, isImported = imported,
      sourcePath = if (imported) "/external/keep-me" else ""
    )
  }

  @Test
  fun `deleting a project session cascades to its messages and blocks`() = runBlocking {
    val now = System.currentTimeMillis()
    store.createSessionBlocking(
      AgentSessionEntity(
        id = "s1", projectId = "/proj/a", title = "chat", status = "completed",
        createdAt = now, updatedAt = now
      )
    )
    store.await { dao ->
      dao.insertMessage(
        AgentMessageEntity(uuid = "m1", sessionId = "s1", role = "user", content = "hi", status = "sent", statusMessage = "", createdAt = now)
      )
      dao.insertMessage(
        AgentMessageEntity(uuid = "t1", sessionId = "s1", role = "assistant_turn", content = "hello", status = "completed", statusMessage = "", createdAt = now)
      )
      dao.insertBlock(
        AgentBlockEntity(uuid = "b1", messageUuid = "t1", kind = "text", name = "", argsJson = "", status = "done", summary = "hello", detail = "", exitCode = null, createdAt = now)
      )
    }

    store.deleteSessionsForProject("/proj/a")

    assertEquals(
      "session, message and block all removed together",
      emptyList<Any>(),
      store.messagesWithBlocks("s1").first()
    )
  }

  @Test
  fun `deleteProjectFolder removes a project under the projects root`() {
    val dir = fs.suggestDefaultRoot("deletable")
    makeProject(dir, "deletable")
    assertTrue("precondition: folder exists", dir.isDirectory)

    val deleted = fs.deleteProjectFolder(makeProject(dir, "deletable"))

    assertTrue("reports success", deleted)
    assertFalse("folder is gone", dir.exists())
  }

  @Test
  fun `deleteProjectFolder refuses a folder outside the projects root`() {
    val outside = File(System.getProperty("java.io.tmpdir"), "scoos-keep-${System.currentTimeMillis()}")
      .apply { mkdirs() }
    try {
      val project = Project(
        id = "proj-external", name = "external", branch = "main", lastActivity = "now",
        path = outside.absolutePath
      )

      val deleted = fs.deleteProjectFolder(project)

      assertFalse("must not claim it deleted an external folder", deleted)
      assertTrue("the external folder must survive", outside.isDirectory)
    } finally {
      outside.deleteRecursively()
    }
  }
}
