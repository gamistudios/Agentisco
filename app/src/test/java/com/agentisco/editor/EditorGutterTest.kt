package com.agentisco.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.agentisco.editor.model.EditorSettings
import com.agentisco.editor.syntax.Language
import com.agentisco.ui.editor.CodeEditorCanvas
import com.agentisco.ui.theme.AgentiscoTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The gutter used to be sized from the length of the document, which threw
 * `Can't represent a width of 0 and height of 570873 in Constraints` on a large
 * file and composed one node per line. These tests hold what replaced it: the
 * gutter never grows with the document, and it mirrors the code's scroll.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class EditorGutterTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private fun document(lines: Int): String =
    (1..lines).joinToString("\n") { "val value$it = $it" }

  private fun render(
    lines: Int,
    settings: EditorSettings = EditorSettings(),
    scrollState: ScrollState = ScrollState(0)
  ) {
    val text = document(lines)
    composeTestRule.setContent {
      AgentiscoTheme {
        CodeEditorCanvas(
          textFieldValue = TextFieldValue(text = text, selection = TextRange(0)),
          onValueChange = {},
          language = Language.KOTLIN,
          settings = settings,
          isEditMode = false,
          onEnterEditMode = {},
          scrollState = scrollState
        )
      }
    }
  }

  /** The numbers currently on screen, read from their `line_N` descriptions. */
  private fun shownLineNumbers(): List<Int> =
    composeTestRule
      .onAllNodesWithTag("editor_line_number")
      .fetchSemanticsNodes()
      .mapNotNull { node ->
        node.config[SemanticsProperties.ContentDescription]
          .firstOrNull()?.removePrefix("line_")?.toIntOrNull()
      }

  /** Drags upward inside the tagged node by `pixels`, the way a finger scrolls. */
  private fun dragUp(tag: String, pixels: Int) {
    composeTestRule.onNodeWithTag(tag).performTouchInput {
      down(center)
      var moved = 0
      while (moved < pixels) {
        val step = minOf(200f, (pixels - moved).toFloat())
        moveBy(Offset(0f, -step))
        moved += step.toInt()
      }
      up()
    }
    composeTestRule.waitForIdle()
  }

  @Test
  fun `a document taller than the screen lays out instead of killing the measure pass`() {
    // 1_000 lines at 20x line height is ~630_000px of content, the same order as
    // the 570_873px file that crashed on device, without laying out 20k lines.
    render(lines = 1_000, settings = EditorSettings(lineHeightMultiplier = 20f))

    composeTestRule.onNodeWithTag("editor_textarea").assertIsDisplayed()
  }

  @Test
  fun `the gutter numbers only the lines on screen`() {
    render(3_000)

    val shown = shownLineNumbers()
    assertTrue("expected some visible numbers, got ${shown.size}", shown.isNotEmpty())
    assertTrue("the gutter composed ${shown.size} numbers for a 3k line file", shown.size <= 80)
    assertEquals(1, shown.min())
    assertTrue("the tail must not be composed, max was ${shown.max()}", shown.max() < 1_000)
  }

  @Test
  fun `a file shorter than the viewport numbers every line`() {
    render(3)

    assertEquals(listOf(1, 2, 3), shownLineNumbers().sorted())
  }

  @Test
  fun `dragging the gutter scrolls the code and the numbers follow`() {
    val scrollState = ScrollState(0)
    render(3_000, scrollState = scrollState)

    dragUp("editor_gutter", pixels = 1_200)

    assertTrue("the drag never reached the scroll", scrollState.value > 0)
    val shown = shownLineNumbers()
    assertTrue("line 1 should have scrolled away, got $shown", 1 !in shown)
    assertTrue("first shown number was ${shown.min()}", shown.min() > 20)
    assertTrue("the gutter kept a ${shown.size} number window", shown.size <= 80)
  }

  @Test
  fun `a gutter row sits on the code row it numbers`() {
    render(60)

    val strip = composeTestRule.onNodeWithTag("editor_gutter").getBoundsInRoot()
    val first = composeTestRule.onNodeWithContentDescription("line_1").getBoundsInRoot()
    assertTrue(
      "line 1 started ${first.top - strip.top} below the gutter top, expected its 6dp pad",
      (first.top - strip.top) in 4.dp..8.dp
    )

    dragUp("editor_gutter", pixels = 4_000)

    val scrolled = composeTestRule.onNodeWithTag("editor_gutter").getBoundsInRoot()
    val last = composeTestRule.onNodeWithContentDescription("line_60").getBoundsInRoot()
    val gap = scrolled.bottom - last.bottom
    assertTrue(
      "the last line sat $gap above the bottom; the code ends at its 48dp bottom pad",
      gap in 44.dp..52.dp
    )
  }

  @Test
  fun `the gutter renders beside the code at rest and scrolled`() {
    render(60)

    composeTestRule.onRoot().captureRoboImage("src/test/screenshots/editor_gutter_rest.png")

    dragUp("editor_gutter", pixels = 400)

    composeTestRule.onRoot().captureRoboImage("src/test/screenshots/editor_gutter_scrolled.png")
  }
}
