package com.awaki

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.awaki.data.repository.WorkspaceRepository
import com.awaki.ui.WorkspaceViewModel
import com.awaki.ui.screens.settings.UiThemeBody
import com.awaki.ui.theme.AwakiTheme
import com.awaki.ui.theme.DefaultUiTheme
import com.awaki.ui.theme.uiThemes
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The theme gallery, driven as a body rather than as a sheet: a bottom sheet cannot be
 * opened from a Robolectric compose test, and the sheet scaffold is not what this
 * feature promises. Pinned here: the gallery holds every theme the catalogue has,
 * tapping one repaints the app through the same call the real sheet makes, and the
 * "In use" mark is never on two rows at once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class UiThemeGalleryTest {

  @get:Rule
  val compose = createComposeRule()

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  /** The way the activity wears a theme: the palette is collected, not read once. */
  private fun showGallery(): WorkspaceViewModel {
    val viewModel = WorkspaceViewModel(WorkspaceRepository(context = null))
    compose.setContent {
      val theme by viewModel.uiTheme.collectAsState()
      AwakiTheme(palette = theme) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) { UiThemeBody(viewModel) }
      }
    }
    return viewModel
  }

  private fun inUseCount() =
    compose.onAllNodesWithText("In use", substring = true).fetchSemanticsNodes().size

  @Test
  fun `every theme in the catalogue has a row, and the one being worn says so`() {
    val viewModel = showGallery()

    uiThemes.forEach {
      val row = "row_ui_theme_${it.key}"
      compose.onNodeWithTag(row).assertExists()
      // A row is a merged, clickable node, so its name and its blurb are both its own
      // text. Four themes share one blurb, which a bare text query finds four times, so
      // each string is matched inside the row it belongs to.
      compose.onNode(hasTestTag(row) and hasText(it.name, substring = true)).assertExists()
      compose.onNode(hasTestTag(row) and hasText(it.blurb, substring = true)).assertExists()
    }
    assertEquals("one row at a time is worn", 1, inUseCount())
    compose.onNodeWithTag("row_ui_theme_nocturne").assertIsDisplayed()
    assertEquals(DefaultUiTheme.key, viewModel.uiTheme.value.key)
    compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/settings_ui_theme_gallery.png")
  }

  @Test
  fun `tapping a theme repaints the app and moves the mark`() {
    val viewModel = showGallery()

    compose.onNodeWithTag("row_ui_theme_graphite").performClick()
    assertEquals("graphite", viewModel.uiTheme.value.key)
    assertEquals("the mark moved, it did not multiply", 1, inUseCount())

    // A light theme from the far end of the list: the same call, the other family.
    compose.onNodeWithTag("row_ui_theme_daylight").performClick()
    assertEquals("daylight", viewModel.uiTheme.value.key)
    assertEquals(1, inUseCount())

    compose.onNodeWithTag("row_ui_theme_nocturne").performClick()
    assertEquals(DefaultUiTheme.key, viewModel.uiTheme.value.key)
  }
}
