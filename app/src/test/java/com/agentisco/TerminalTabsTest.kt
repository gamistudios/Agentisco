package com.agentisco

import com.agentisco.data.model.Project
import com.agentisco.data.repository.WorkspaceRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The terminal is a shell in the Linux environment, not a window into a project:
 * the page has to open when the app has just started and no project folder has
 * been found, which is exactly the state that used to crash it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalTabsTest {

  private fun repository() = WorkspaceRepository(context = null)

  @Test
  fun `a tab exists with no project chosen`() {
    val repo = repository()

    val tabs = repo.terminalSessions.value
    assertTrue("the terminal page had no tab to show", tabs.isNotEmpty())
    // The page reads the active one, so the id it starts with has to point at it.
    assertEquals(tabs.first().id, repo.activeTerminalSessionId.value)
    // No project means no folder to sit in, but the guest home always exists.
    assertEquals("/root", tabs.first().currentDir)
  }

  @Test
  fun `a new tab opens in the home whatever the project`() {
    val repo = repository()
    val before = repo.terminalSessions.value.size

    repo.createTerminalSession("build")

    val tabs = repo.terminalSessions.value
    assertEquals(before + 1, tabs.size)
    // One tab added, not one per view of the list: a duplicate id here means the
    // project's tab registry and the published tabs are the same mutable object.
    assertEquals(tabs.map { it.id }.distinct(), tabs.map { it.id })
    val newest = tabs.last()
    assertEquals("build", newest.name)
    assertEquals("/root", newest.currentDir)
    assertEquals(newest.id, repo.activeTerminalSessionId.value)
  }

  /** The header names the project the workspace is, even though the shell ignores it. */
  @Test
  fun `the header labels the project without the tab depending on it`() {
    val repo = repository()
    val noProject = Project(id = "p", name = "T", branch = "main", lastActivity = "", path = "")
    assertEquals("/root", repo.terminalWorkspaceLabel(noProject))

    val project = Project(
      id = "p", name = "T", branch = "main", lastActivity = "", path = "/data/user/0/com.agentisco/files/project"
    )
    assertEquals(project.path, repo.terminalWorkspaceLabel(project))
  }
}
