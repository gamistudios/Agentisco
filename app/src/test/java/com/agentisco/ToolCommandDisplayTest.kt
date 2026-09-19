package com.agentisco

import androidx.test.core.app.ApplicationProvider
import com.agentisco.ui.screens.displayCommandForTool
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
}
