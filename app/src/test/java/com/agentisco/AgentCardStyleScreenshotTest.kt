package com.agentisco

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.agentisco.ui.ActionBlock
import com.agentisco.ui.ApprovalBlock
import com.agentisco.ui.ErrorBlock
import com.agentisco.ui.screens.ApprovalCard
import com.agentisco.ui.screens.ErrorCard
import com.agentisco.ui.screens.ToolCallRow
import com.agentisco.ui.theme.AgentiscoTheme
import com.agentisco.ui.theme.DarkBackground
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the chat card trio (tool rows, an approval, an error) so the rail and
 * status-circle treatment can be inspected without a ViewModel or a live turn.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class AgentCardStyleScreenshotTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val readTool = ActionBlock(
    id = "b1",
    name = "read_file",
    argsJson = """{"path":"app/src/main/java/com/agentisco/workspace/filesystem/ProjectMetadataScanner.kt"}""",
    running = false,
    success = true,
    summary = "186 lines",
    detail = "",
    exitCode = 0
  )

  private val runningTool = ActionBlock(
    id = "b2",
    name = "terminal_execute",
    argsJson = """{"command":"./gradlew :app:assembleDebug"}""",
    running = true,
    success = null,
    summary = "",
    detail = "",
    exitCode = null,
    callId = "call-2"
  )

  private val approval = ApprovalBlock(
    id = "b3",
    approvalId = "a3",
    command = "grep -aoE \"v[0-9]+\\.[0-9]+\\.[0-9]+\" /usr/bin/node | sort -u | head -20",
    title = "Explore / Research Engineer",
    impact = "Runs in terminal session 'main': grep -aoE node version",
    resolved = true,
    allowed = true
  )

  /** A delegation in flight: two steps of its own, still running. */
  private val delegation = ActionBlock(
    id = "d1",
    name = "delegate",
    argsJson = """{"role":"Explore / Research Engineer","description":"map the repo","prompt":"Read the scanner"}""",
    running = true,
    success = null,
    summary = "",
    detail = "",
    exitCode = null,
    callId = "call-d1",
    children = listOf(
      readTool,
      readTool.copy(id = "d1-c2", name = "list_files", argsJson = """{"path":"app/src"}""")
    ),
    delegation = com.agentisco.ui.DelegationBrief(
      role = "Explore / Research Engineer",
      description = "map the repo",
      prompt = "Read the scanner"
    )
  )

  @Test
  fun `cards carry a status rail, a status circle and the impact line`() {
    composeTestRule.setContent {
      AgentiscoTheme {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top)
        ) {
          ToolCallRow(item = readTool)
          ToolCallRow(item = runningTool)
          ApprovalCard(
            item = approval,
            onAllow = {},
            onDeny = {},
            onAnswer = {},
            onReopen = {}
          )
          ErrorCard(block = ErrorBlock(id = "b4", message = "Task cancelled by user"), showRetry = false, onRetry = {})
        }
      }
    }

    composeTestRule.onNodeWithText("Allowed").assertExists()
    composeTestRule.onNodeWithText("Error").assertExists()
    composeTestRule
      .onNodeWithText("Runs in terminal session 'main': grep -aoE node version")
      .assertExists()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/agent_card_styles.png")
  }

  /**
   * A specialist's own steps stay folded away until asked, so a delegated turn
   * reads as one line per agent instead of a flood.
   */
  @Test
  fun `sub-agent work stays collapsed until opened, then closes again`() {
    composeTestRule.setContent {
      AgentiscoTheme {
        Column(modifier = Modifier.fillMaxSize().background(DarkBackground).padding(12.dp)) {
          ToolCallRow(item = delegation)
        }
      }
    }

    composeTestRule.onNodeWithTag("btn_toggle_subagent_work").assertIsDisplayed()
    composeTestRule.onNodeWithTag("stream_tool_list_files").assertDoesNotExist()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/agent_subagent_collapsed.png")

    composeTestRule.onNodeWithTag("btn_toggle_subagent_work").performClick()
    composeTestRule.onNodeWithTag("stream_tool_list_files").assertIsDisplayed()

    composeTestRule.onNodeWithTag("btn_toggle_subagent_work").performClick()
    composeTestRule.onNodeWithTag("stream_tool_list_files").assertDoesNotExist()
  }

  /**
   * One specialist of its own: the user can hold it between its steps and let it
   * go on again, and the card says which of the two it is looking at.
   */
  @Test
  fun `a delegation is held and released from its own card`() {
    var paused by mutableStateOf(false)
    var holds = 0
    var releases = 0
    composeTestRule.setContent {
      AgentiscoTheme {
        Column(modifier = Modifier.fillMaxSize().background(DarkBackground).padding(12.dp)) {
          ToolCallRow(
            item = delegation,
            pausedDelegations = if (paused) setOf(delegation.callId) else emptySet(),
            onPauseSubagent = { holds++; paused = true },
            onResumeSubagent = { releases++; paused = false }
          )
        }
      }
    }

    composeTestRule.onNodeWithTag("btn_pause_subagent").assertIsDisplayed()
    composeTestRule.onNodeWithTag("btn_resume_subagent").assertDoesNotExist()

    composeTestRule.onNodeWithTag("btn_pause_subagent").performClick()
    assertEquals(1, holds)
    composeTestRule.onNodeWithTag("btn_resume_subagent").assertIsDisplayed()
    composeTestRule.onNodeWithTag("btn_pause_subagent").assertDoesNotExist()
    composeTestRule.onNodeWithText("2 steps · paused").assertIsDisplayed()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/agent_subagent_paused.png")

    composeTestRule.onNodeWithTag("btn_resume_subagent").performClick()
    assertEquals(1, releases)
    composeTestRule.onNodeWithTag("btn_pause_subagent").assertIsDisplayed()
  }
}
