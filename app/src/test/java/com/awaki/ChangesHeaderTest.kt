package com.awaki

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.awaki.ui.screens.ChangesHeader
import com.awaki.ui.screens.ChangesViewMode
import com.awaki.ui.theme.AwakiTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Changes header on a narrow phone. Its action buttons are a fixed row of
 * controls, so everything else has to give way — before, a long branch name
 * wrapped inside the chip one letter per line and the header expanded downward
 * over the file list. Two lines is the most it may ever take.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class ChangesHeaderTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val longBranch = "feature/agent-planning-ui-redesign-2026-10"

  private fun render(widthDp: Int, branch: String = longBranch) {
    composeTestRule.setContent {
      AwakiTheme {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
          Box(Modifier.width(widthDp.dp)) {
            ChangesHeader(
              branch = branch,
              filesChanged = 12,
              additions = 340,
              deletions = 87,
              viewMode = ChangesViewMode.FILES_LIST,
              canShowDiff = true,
              isSearchOpen = false,
              onToggleSearch = {},
              onViewModeChange = {},
              onRefresh = {},
              onBack = {},
              onOpenGit = {},
              modifier = Modifier.testTag("changes_header")
            )
          }
        }
      }
    }
  }

  private fun renderHeader(widthDp: Int) {
    render(widthDp)
    val bounds = composeTestRule.onNodeWithTag("changes_header").getBoundsInRoot()
    val height = (bounds.bottom - bounds.top).value
    assertTrue("the header took $height dp at ${widthDp}dp wide", height in 40f..72f)
  }

  @Test
  fun `a long branch name keeps the header to two lines`() {
    renderHeader(widthDp = 300)
    // The name stays readable in the semantics even when the chip ellipsizes it.
    composeTestRule.onNodeWithText(longBranch).assertExists()
  }

  @Test
  fun `a real phone width shows the title, the branch and every action`() {
    render(widthDp = 411)

    composeTestRule.onNodeWithText("Changes").assertIsDisplayed()
    composeTestRule.onNodeWithTag("changes_branch").assertIsDisplayed()
    composeTestRule.onNodeWithTag("btn_goto_git").assertIsDisplayed()
    composeTestRule.onRoot().captureRoboImage("src/test/screenshots/changes_header_411.png")
  }

  @Test
  fun `the smallest phone class still shows the title and the metrics`() {
    render(widthDp = 320)

    composeTestRule.onNodeWithText("Changes").assertIsDisplayed()
    composeTestRule.onNodeWithText("12 files changed").assertIsDisplayed()
    composeTestRule.onNodeWithTag("btn_goto_git").assertIsDisplayed()
    composeTestRule.onRoot().captureRoboImage("src/test/screenshots/changes_header_320.png")
  }

  @Test
  fun `a narrower phone still gets a two line header`() {
    renderHeader(widthDp = 260)
    composeTestRule.onRoot().captureRoboImage("src/test/screenshots/changes_header_narrow.png")
  }
}
