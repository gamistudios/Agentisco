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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
 * tapping one repaints the app through the same call the real sheet makes, the "In use"
 * mark is never on two rows at once, and the search on top narrows the list instead of
 * scattering it.
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
    held.closeAll()
    Dispatchers.resetMain()
  }

  private val held = HeldWork()

  /** The way the activity wears a theme: the palette is collected, not read once. */
  private fun showGallery(): WorkspaceViewModel {
    val viewModel = held.hold(WorkspaceViewModel(WorkspaceRepository(context = null)))
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

    // A light theme from deep inside a fifty-seven row list. The search is what brings
    // it to the tap, which is the flow the search box exists for.
    compose.onNodeWithTag("input_ui_theme_search").performTextInput("daylight")
    compose.onNodeWithTag("row_ui_theme_daylight").performClick()
    assertEquals("daylight", viewModel.uiTheme.value.key)
    assertEquals(1, inUseCount())

    compose.onNodeWithTag("btn_clear_ui_theme_search").performClick()
    compose.onNodeWithTag("row_ui_theme_nocturne").performClick()
    assertEquals(DefaultUiTheme.key, viewModel.uiTheme.value.key)
  }

  @Test
  fun `the search on top narrows the gallery to the themes that match`() {
    showGallery()

    // Two cuts of one favourite colour, and nothing else.
    compose.onNodeWithTag("input_ui_theme_search").performTextInput("turquoise")
    compose.onNodeWithTag("row_ui_theme_turquoise_sunset").assertExists()
    compose.onNodeWithTag("row_ui_theme_turquoise_sunset_night").assertExists()
    compose.onNodeWithTag("row_ui_theme_nocturne").assertDoesNotExist()
    assertEquals("each cut still says whether it is worn", 0, inUseCount())

    // The second word has to match too, so the pair separates the night from the day.
    compose.onNodeWithTag("btn_clear_ui_theme_search").performClick()
    compose.onNodeWithTag("input_ui_theme_search").performTextInput("turquoise night")
    compose.onNodeWithTag("row_ui_theme_turquoise_sunset_night").assertExists()
    compose.onNodeWithTag("row_ui_theme_turquoise_sunset").assertDoesNotExist()

    // A whole family, by the word its blurb carries: every night cut answers, and no
    // light theme does.
    compose.onNodeWithTag("btn_clear_ui_theme_search").performClick()
    compose.onNodeWithTag("input_ui_theme_search").performTextInput("dark")
    compose.onNodeWithTag("row_ui_theme_cobalt_lemon_night").assertExists()
    compose.onNodeWithTag("row_ui_theme_butterfly_blue_night").assertExists()
    compose.onNodeWithTag("row_ui_theme_cobalt_lemon").assertDoesNotExist()
    compose.onNodeWithTag("row_ui_theme_graphite").assertDoesNotExist()
    assertEquals("the theme being worn is still in the results", 1, inUseCount())
  }

  @Test
  fun `a query with no theme behind it says so instead of showing an empty sheet`() {
    showGallery()

    compose.onNodeWithTag("input_ui_theme_search").performTextInput("chartreuse")
    compose.onNodeWithTag("txt_no_theme_results").assertIsDisplayed()
    compose.onNodeWithText("No theme matches", substring = true).assertIsDisplayed()

    compose.onNodeWithTag("btn_clear_ui_theme_search").performClick()
    compose.onNodeWithTag("row_ui_theme_nocturne").assertExists()
  }
}
