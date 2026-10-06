package com.awaki

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.awaki.core.model.AppDestination
import com.awaki.data.model.Project
import com.awaki.data.model.ProjectKind
import com.awaki.ui.components.AgentIDETopAppBar
import com.awaki.ui.theme.AwakiTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the update check may and may not do to the header while it waits.
 *
 * The update service can take tens of seconds to answer after a cold start, and the
 * check used to spend exactly that long inside a modal window that swallowed every
 * tap in the app. These are the two halves of the replacement: the wait is still
 * announced, and nothing at all is taken away while it lasts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class UpdateCheckNonBlockingTest {

  @get:Rule
  val compose = createComposeRule()

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

  private fun showHeader(
    updateChecking: Boolean,
    onUpdateClick: () -> Unit = {},
    onNavigate: (AppDestination) -> Unit = {},
    onOpenCommandPalette: () -> Unit = {}
  ) {
    // The wait is announced by an indicator that never stops animating, and this
    // rule's clock normally advances time on its own while an assertion waits for
    // the app to settle — an animation with no last frame means there is never a
    // moment to stop, and every check below would fail as "app not idle" instead
    // of as the thing it actually looks at. Time is handed out frame by frame
    // here; the spin's motion is not what is under test, its presence is.
    compose.mainClock.autoAdvance = false
    compose.setContent {
      AwakiTheme {
        AgentIDETopAppBar(
          activeProject = project,
          currentDestination = AppDestination.AGENT,
          onNavigate = onNavigate,
          onOpenModelSheet = {},
          onOpenCommandPalette = onOpenCommandPalette,
          updateChecking = updateChecking,
          onUpdateClick = onUpdateClick
        )
      }
    }
    // One frame draws the header; the animation stops asking for the next one.
    compose.mainClock.advanceTimeByFrame()
  }

  /** A press, then the frame that lets the header act on it. */
  private fun tap(tag: String) {
    compose.onNodeWithTag(tag).performClick()
    compose.mainClock.advanceTimeByFrame()
  }

  @Test
  fun `a running check spins the update button instead of covering the app`() {
    var updateClicks = 0
    showHeader(updateChecking = true, onUpdateClick = { updateClicks++ })

    compose.onNodeWithTag("top_update_checking").assertIsDisplayed()
    // One indicator at a time: the spinner takes the icon's place, it does not
    // stack on it or grow the row.
    compose.onNodeWithContentDescription("Check for updates").assertDoesNotExist()

    // The wait is still pressable, which is how the user asks again.
    tap("top_update")
    assertEquals(1, updateClicks)
  }

  @Test
  fun `every other header control keeps working while the check is in flight`() {
    var navigated: AppDestination? = null
    var paletteOpens = 0
    showHeader(
      updateChecking = true,
      onNavigate = { navigated = it },
      onOpenCommandPalette = { paletteOpens++ }
    )

    tap("top_settings")
    assertEquals(AppDestination.SETTINGS, navigated)
    tap("top_cmd_palette")
    assertEquals(1, paletteOpens)
  }

  @Test
  fun `with no check running the button is the plain download icon`() {
    showHeader(updateChecking = false)

    compose.onNodeWithTag("top_update_checking").assertDoesNotExist()
    compose.onNodeWithContentDescription("Check for updates").assertIsDisplayed()
  }
}
