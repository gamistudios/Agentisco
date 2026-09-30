package com.agentisco

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
}
