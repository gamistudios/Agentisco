package com.awaki

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.awaki.data.repository.UpdateRepository
import com.awaki.data.repository.WorkspaceRepository
import com.awaki.settings.store.UserPreferencesStore
import com.awaki.ui.UpdateViewModel
import com.awaki.ui.WorkspaceViewModel
import com.awaki.ui.screens.settings.SettingsGroup
import com.awaki.ui.screens.settings.SettingsScreen
import com.awaki.ui.theme.AwakiTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The whole of Awaki's configuration behind one search box, on a phone.
 *
 * What is pinned here is the shape the redesign asked for: sections rather than
 * cards, one dense row per setting, search over exactly the rows the page shows, and
 * a switch that changes its setting on the row instead of opening something. The
 * scroll test is the one that earns its keep — it is what notices when a setting
 * quietly disappears from the page while the code behind it keeps running.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsScreenTest {

  @get:Rule
  val compose = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val density get() = context.resources.displayMetrics.density

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun showSettings(): WorkspaceViewModel {
    // No application for the workspace repository: every store it owns stays in
    // memory, so a settings test cannot write into a real device's preferences.
    val viewModel = WorkspaceViewModel(WorkspaceRepository(context = null))
    val updateViewModel = UpdateViewModel(UpdateRepository(context), UserPreferencesStore(context))
    compose.setContent {
      AwakiTheme { SettingsScreen(viewModel, updateViewModel, onNavigate = {}) }
    }
    return viewModel
  }

  private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

  @Test
  fun `the page opens on quick access with search above the sections`() {
    showSettings()

    compose.onNodeWithText("Settings").assertIsDisplayed()
    compose.onNodeWithTag("input_settings_search").assertIsDisplayed()
    compose.onNodeWithText("QUICK ACCESS").assertIsDisplayed()
    compose.onNodeWithText("AI & AGENT").assertIsDisplayed()
    compose.onNodeWithTag("row_quick_model").assertIsDisplayed()
    compose.onNodeWithTag("row_model").assertIsDisplayed()
    compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/settings_screen_compact.png")
  }

  @Test
  fun `a row is one line of explanation and one control, not a card`() {
    showSettings()

    // Measured on rows that are on screen without scrolling: a LazyColumn does not
    // lay out what the viewport never reaches, so a row below the fold has no size
    // to measure and would report the test broken instead of the layout.
    val heights = listOf("row_quick_model", "row_model", "row_plan_mode")
      .associateWith { tag ->
        compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height / density
      }

    heights.forEach { (tag, dp) ->
      assertTrue("$tag measured $dp dp; a dense row is 52-60dp", dp in 51.0..68.0)
    }
  }

  @Test
  fun `searching leaves only the rows that match, under their own heading`() {
    showSettings()

    compose.onNodeWithTag("input_settings_search").performTextInput("battery")

    compose.onNodeWithTag("row_background_wakelock").assertIsDisplayed()
    compose.onNodeWithText("EXECUTION").assertIsDisplayed()
    compose.onNodeWithTag("row_model").assertDoesNotExist()
    compose.onNodeWithTag("row_editor_font_size").assertDoesNotExist()
    compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/settings_screen_search.png")
  }

  @Test
  fun `a query that matches nothing says so instead of showing a blank page`() {
    showSettings()

    compose.onNodeWithTag("input_settings_search").performTextInput("quantum tunneling")

    compose.onNodeWithTag("txt_no_settings_results").assertIsDisplayed()
    compose.onNodeWithText("No setting matches", substring = true).assertIsDisplayed()
  }

  @Test
  fun `clearing the search brings the whole list back`() {
    showSettings()

    compose.onNodeWithTag("input_settings_search").performTextInput("jina")
    compose.onNodeWithTag("row_web_access").assertIsDisplayed()
    compose.onNodeWithTag("btn_clear_settings_search").performClick()

    compose.onNodeWithTag("row_quick_model").assertIsDisplayed()
    compose.onNodeWithText("QUICK ACCESS").assertIsDisplayed()
  }

  @Test
  fun `a switch changes its setting on the row, with nothing opened`() {
    val viewModel = showSettings()
    assertFalse(viewModel.permissions.value.planMode)

    compose.onNodeWithTag("input_settings_search").performTextInput("planning")
    compose.onNodeWithTag("switch_plan_mode").performClick()
    assertTrue(viewModel.permissions.value.planMode)

    compose.onNodeWithTag("switch_plan_mode").performClick()
    assertFalse(viewModel.permissions.value.planMode)
    // It stayed a search: one row matched, and no sheet or page was opened for it.
    compose.onNodeWithTag("row_editor_font_size").assertDoesNotExist()
  }

  @Test
  fun `an editor row changes what the editor itself reads`() {
    val viewModel = showSettings()
    assertFalse(viewModel.editorSettings.value.wordWrap)

    compose.onNodeWithTag("input_settings_search").performTextInput("word wrap")
    compose.onNodeWithTag("switch_editor_word_wrap").performClick()

    // The same flow the editor renders from, not a copy the screen keeps to itself.
    assertTrue(viewModel.editorSettings.value.wordWrap)
  }

  @Test
  fun `the stepper on a number row moves it without a dialog`() {
    val viewModel = showSettings()
    assertEquals(2, viewModel.editorSettings.value.tabSize)

    compose.onNodeWithTag("input_settings_search").performTextInput("tab size")
    compose.onNodeWithTag("stepper_editor_tab_size_increase").performClick()

    assertEquals(4, viewModel.editorSettings.value.tabSize)
    compose.onNodeWithText("4").assertIsDisplayed()
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `every setting the page is made of is reachable`() {
    showSettings()

    // "Work lost with the last process" is deliberately absent: that row only exists
    // while there is interrupted work to show, and a test workspace has none.
    val expected = setOf(
      "quick_model", "quick_background", "quick_permissions", "quick_theme",
      "model", "providers", "local_models", "plan_mode", "compaction", "agent_team", "skills",
      "file_editing", "terminal_safety", "tool_permissions", "network_access",
      "tool_iterations", "web_access", "skipped_folders",
      "editor_font_size", "editor_line_height", "editor_tab_size", "editor_use_spaces",
      "editor_word_wrap", "editor_auto_save", "editor_format_on_save",
      "editor_bracket_matching", "editor_code_folding", "editor_touch_shortcuts",
      "syntax_theme", "editor_line_numbers", "editor_active_line", "editor_minimap",
      "show_tool_json", "show_context_usage",
      "background_execution", "background_wakelock", "terminal_hold", "background_checks",
      "alert_approval", "alert_interrupted", "alert_update",
      "updates", "about"
    ) + if (BuildConfig.DEBUG) setOf("crash_log") else emptySet()

    // One section is one lazy item, so jumping to a section key composes that
    // section's rows — and recycles the ones already scrolled past, which is why the
    // rows are collected after every jump instead of once at the end. Touch drags are
    // not used here: the fling they leave behind keeps the Robolectric idling bridge
    // busy until it gives up at 60s.
    val seen = mutableSetOf<String>()
    fun collect() { expected.filterTo(seen) { exists("row_$it") } }
    collect()
    SettingsGroup.values().forEach { group ->
      compose.onNodeWithTag("settings_list").performScrollToKey("section_${group.name}")
      collect()
      if (group == SettingsGroup.Editor) {
        compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/settings_screen_editor.png")
      }
    }
    compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/settings_screen_bottom.png")

    val missing = expected - seen
    assertTrue("rows never composed: $missing", missing.isEmpty())
  }
}
