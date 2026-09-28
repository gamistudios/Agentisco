package com.agentisco

import androidx.test.core.app.ApplicationProvider
import com.agentisco.ui.components.DiffKind
import com.agentisco.ui.screens.displayCommandForTool
import com.agentisco.ui.screens.editDiffForTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Covers "show the real command, not {\"command\": …}" on terminal tool cards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolCommandDisplayTest {

  init {
    // Touch the context so Robolectric bootstraps the app classloader (JSONObject).
    ApplicationProvider.getApplicationContext<android.content.Context>()
  }

  @Test
  fun `run_command renders the raw shell command`() {
    assertEquals(
      "npm test --watch",
      displayCommandForTool("run_command", """{"command":"npm test --watch"}""")
    )
  }

  @Test
  fun `script tools render the npm run form`() {
    assertEquals("npm run build", displayCommandForTool("build", "{}"))
    assertEquals("npm run test -- --coverage", displayCommandForTool("test", """{"args":"--coverage"}"""))
  }

  @Test
  fun `non-terminal or malformed tools have no command`() {
    assertNull(displayCommandForTool("read_file", """{"path":"a.txt"}"""))
    assertNull(displayCommandForTool("run_command", "not json"))
    assertNull(displayCommandForTool("run_command", """{"command":"   "}"""))
  }

  @Test
  fun `a batch edit shows every edit with the file it belongs to`() {
    val lines = editDiffForTool(
      "edit_files",
      """{"edits":[{"path":"a.ts","old_string":"const a = 1;","new_string":"const a = 2;"},
       {"path":"b.ts","old_string":"let b = 1","new_string":"let b = 2\nlet c = 3"}]}"""
    )
    assertEquals(
      listOf("── a.ts ──", "── b.ts ──"),
      lines?.filter { it.kind == DiffKind.CONTEXT }?.map { it.text }
    )
    assertEquals(3, lines?.count { it.kind == DiffKind.ADDED })
    assertEquals(2, lines?.count { it.kind == DiffKind.REMOVED })

    // One edit needs no header: it is shown exactly like a single edit_file.
    val single = editDiffForTool(
      "edit_files",
      """{"edits":[{"path":"a.ts","old_string":"const a = 1;","new_string":"const a = 2;"}]}"""
    )
    assertEquals(emptyList<String>(), single?.filter { it.kind == DiffKind.CONTEXT }?.map { it.text })
    assertEquals(1, single?.count { it.kind == DiffKind.REMOVED })
  }

  @Test
  fun `single edits still produce their own diff`() {
    val edited = editDiffForTool("edit_file", """{"path":"a","old_string":"one","new_string":"two"}""")
    assertEquals(listOf("one", "two"), edited?.map { it.text })

    val created = editDiffForTool("write_file", """{"path":"a","content":"line"}""")
    assertEquals(listOf(DiffKind.ADDED), created?.map { it.kind })

    assertNull(editDiffForTool("read_file", """{"path":"a"}"""))
    assertNull(editDiffForTool("edit_files", """{"edits":[]}"""))
    assertNull(editDiffForTool("edit_file", "not json"))
  }
}
