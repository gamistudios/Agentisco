package com.awaki

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.awaki.core.model.AppDestination
import com.awaki.data.model.Project
import com.awaki.data.model.ProjectKind
import com.awaki.ui.components.AgentIDETopAppBar
import com.awaki.ui.theme.AwakiTheme
import com.awaki.workspace.git.GitBranch
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class TopAppBarBranchTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val project = Project(
    id = "proj-1",
    name = "awaki-app",
    branch = "main",
    lastActivity = "Just now",
    path = "/path/to/awaki-app",
    sizeBytes = 12_000_000,
    lastModified = System.currentTimeMillis(),
    kind = ProjectKind.ANDROID
  )

  @Test
  fun `branch pill switches between local branches`() {
    var checkedOut: String? = null
    composeTestRule.setContent {
      AwakiTheme {
        AgentIDETopAppBar(
          activeProject = project,
          currentDestination = AppDestination.AGENT,
          onNavigate = {},
          onOpenModelSheet = {},
          onOpenCommandPalette = {},
          branches = listOf(
            GitBranch(name = "main", isCurrent = true, isRemote = false),
            GitBranch(name = "feat/preview", isCurrent = false, isRemote = false),
            GitBranch(name = "origin/main", isCurrent = false, isRemote = true)
          ),
          onCheckoutBranch = { checkedOut = it }
        )
      }
    }

    composeTestRule.onNodeWithTag("top_branch_switcher").assertIsDisplayed().performClick()
    // Remotes are not something a pill should check out by accident.
    composeTestRule.onNodeWithTag("branch_option_origin/main").assertDoesNotExist()
    composeTestRule.onNodeWithTag("branch_option_feat/preview").assertIsDisplayed().performClick()
    assertEquals("feat/preview", checkedOut)
  }

  @Test
  fun `single branch keeps the pill a projects shortcut`() {
    var navigated: AppDestination? = null
    composeTestRule.setContent {
      AwakiTheme {
        AgentIDETopAppBar(
          activeProject = project,
          currentDestination = AppDestination.AGENT,
          onNavigate = { navigated = it },
          onOpenModelSheet = {},
          onOpenCommandPalette = {},
          branches = listOf(GitBranch(name = "main", isCurrent = true, isRemote = false)),
          onCheckoutBranch = { throw AssertionError("nothing to switch to") }
        )
      }
    }

    composeTestRule.onNodeWithTag("top_project_selector").assertIsDisplayed().performClick()
    assertEquals(AppDestination.PROJECTS, navigated)
  }

  @Test
  fun `pill follows the project branch as it changes`() {
    var shown by mutableStateOf(project)
    composeTestRule.setContent {
      AwakiTheme {
        AgentIDETopAppBar(
          activeProject = shown,
          currentDestination = AppDestination.AGENT,
          onNavigate = {},
          onOpenModelSheet = {},
          onOpenCommandPalette = {},
          branches = emptyList()
        )
      }
    }

    composeTestRule.onNodeWithText("main").assertIsDisplayed()
    shown = project.copy(branch = "release/2")
    composeTestRule.waitForIdle()
    // The header must re-render with whatever branch Git just reported.
    composeTestRule.onNodeWithText("release/2").assertIsDisplayed()
  }

  @Test
  fun `branch menu renders under the header`() {
    composeTestRule.setContent {
      AwakiTheme {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
          AgentIDETopAppBar(
            activeProject = project,
            currentDestination = AppDestination.AGENT,
            onNavigate = {},
            onOpenModelSheet = {},
            onOpenCommandPalette = {},
            branches = listOf(
              GitBranch(name = "main", isCurrent = true, isRemote = false, ahead = 2, behind = 1),
              GitBranch(name = "feat/live-preview", isCurrent = false, isRemote = false, upstream = "origin/feat/live-preview"),
              GitBranch(name = "release/2", isCurrent = false, isRemote = false)
            ),
            onCheckoutBranch = {}
          )
        }
      }
    }

    composeTestRule.onNodeWithTag("top_branch_switcher").performClick()
    composeTestRule.onNodeWithTag("branch_option_release/2").assertIsDisplayed()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/top_bar_branch_menu.png")
  }
}
