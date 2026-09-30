package com.agentisco

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.agentisco.data.model.Project
import com.agentisco.data.model.ProjectKind
import com.agentisco.data.model.WorkspaceStorageInfo
import com.agentisco.ui.screens.ProjectOverflowAction
import com.agentisco.ui.screens.ProjectsScreenContent
import com.agentisco.ui.theme.AgentiscoTheme
import com.agentisco.ui.theme.DarkBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectsScreenInteractionTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val sampleProject1 = Project(
    id = "proj-1",
    name = "agentisco-app",
    branch = "main",
    lastActivity = "Just now",
    path = "/path/to/agentisco-app",
    sizeBytes = 12_000_000,
    lastModified = System.currentTimeMillis(),
    kind = ProjectKind.ANDROID
  )

  private val sampleProject2 = Project(
    id = "proj-2",
    name = "web-frontend",
    branch = "main",
    lastActivity = "1h ago",
    path = "/path/to/web-frontend",
    sizeBytes = 4_500_000,
    lastModified = System.currentTimeMillis() - 3600_000,
    kind = ProjectKind.NODE
  )

  private val storage = WorkspaceStorageInfo(
    freeBytes = 25_000_000_000L,
    totalBytes = 64_000_000_000L
  )

  @Test
  fun test_primary_and_secondary_actions_and_view_toggle() {
    var newProjectClicked = false
    var openFolderClicked = false
    var selectedProject: Project? = null
    var gridView by mutableStateOf(false)

    composeTestRule.setContent {
      AgentiscoTheme {
        ProjectsScreenContent(
          projects = listOf(sampleProject1, sampleProject2),
          activeProject = sampleProject1,
          workspacePath = "~/projects",
          storage = storage,
          gridView = gridView,
          onGridViewChange = { gridView = it },
          onNewProject = { newProjectClicked = true },
          onOpenFolder = { openFolderClicked = true },
          onProjectClick = { selectedProject = it },
          onCopyPath = {},
          onProjectAction = { _, _ -> },
          modifier = Modifier.fillMaxSize().background(DarkBackground)
        )
      }
    }

    // Verify New Project and Open Folder buttons are displayed
    composeTestRule.onNodeWithTag("btn_new_project").assertIsDisplayed().performClick()
    assertTrue(newProjectClicked)

    composeTestRule.onNodeWithTag("btn_import_folder").assertIsDisplayed().performClick()
    assertTrue(openFolderClicked)

    // Verify Linear card is displayed
    composeTestRule.onNodeWithTag("project_card_proj-1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("project_card_proj-2").assertIsDisplayed()

    // Click project 1
    composeTestRule.onNodeWithTag("project_card_proj-1").performClick()
    assertEquals(sampleProject1.id, selectedProject?.id)

    // Toggle to Grid view: the screen reports the intent, the caller owns the state
    composeTestRule.onNodeWithTag("btn_grid_view").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    assertTrue(gridView)

    // Both cards should still be displayed in Grid view
    composeTestRule.onNodeWithTag("project_card_proj-1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("project_card_proj-2").assertIsDisplayed()

    // Toggle back to Linear view
    composeTestRule.onNodeWithTag("btn_linear_view").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    assertEquals(false, gridView)
    composeTestRule.onNodeWithTag("project_card_proj-1").assertIsDisplayed()
  }

  @Test
  fun test_overflow_menu_triggers_action() {
    var triggeredAction: ProjectOverflowAction? = null
    var targetProject: Project? = null

    composeTestRule.setContent {
      AgentiscoTheme {
        ProjectsScreenContent(
          projects = listOf(sampleProject1),
          activeProject = sampleProject1,
          workspacePath = "~/projects",
          storage = storage,
          gridView = false,
          onGridViewChange = {},
          onNewProject = {},
          onOpenFolder = {},
          onProjectClick = {},
          onCopyPath = {},
          onProjectAction = { proj, action ->
            targetProject = proj
            triggeredAction = action
          },
          modifier = Modifier.fillMaxSize().background(DarkBackground)
        )
      }
    }

    // Open overflow menu for project 1
    composeTestRule.onNodeWithTag("project_overflow_proj-1").assertIsDisplayed().performClick()

    // Click "Files" option in menu
    composeTestRule.onNodeWithTag("project_card_proj-1").assertIsDisplayed()
  }
}
