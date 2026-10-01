package com.agentisco

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.agentisco.agent.llm.CatalogModel
import com.agentisco.ui.components.CatalogSuggestions
import com.agentisco.ui.theme.AgentiscoTheme
import com.agentisco.ui.theme.DarkBackground
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
 * The provider's own model listing, offered as rows under the Model ID field.
 * This replaced a Material dropdown whose anchor was never initialised inside
 * the dialog, so asking it for focus crashed the app as soon as a user typed a
 * model id — the rows have to work without any of that machinery.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class CatalogSuggestionsTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val picked = mutableListOf<CatalogModel>()
  private var dismissed = 0

  private fun model(n: Int) =
    CatalogModel("claude-sonnet-4-$n", "Claude Sonnet 4.$n", 200_000, 64_000, tools = true, images = true)

  private fun render(matches: List<CatalogModel>) {
    composeTestRule.setContent {
      AgentiscoTheme {
        Box(Modifier.fillMaxWidth().background(DarkBackground)) {
          CatalogSuggestions(
            matches = matches,
            onPick = { picked += it },
            onDismiss = { dismissed++ }
          )
        }
      }
    }
  }

  @Test
  fun `every suggested model can be picked and brings its own id`() {
    render(listOf(model(1), model(2)))

    assertEquals(2, composeTestRule.onAllNodesWithTag("catalog_suggestion").fetchSemanticsNodes().size)
    composeTestRule.onAllNodesWithTag("catalog_suggestion")[0].performClick()

    assertEquals(listOf("claude-sonnet-4-1"), picked.map { it.modelId })
  }

  @Test
  fun `a row states the limits the provider gave that model`() {
    render(listOf(model(1)))

    composeTestRule.onNodeWithText("200k window · 64k out · tools").assertExists()
  }

  @Test
  fun `a long listing scrolls instead of pushing the form off screen`() {
    render((1..12).map { model(it) })

    val list = composeTestRule.onNodeWithTag("catalog_suggestions").getBoundsInRoot()
    val height = list.bottom - list.top
    assertTrue("the listing took $height", height in 100.dp..180.dp)
    assertEquals(12, composeTestRule.onAllNodesWithTag("catalog_suggestion").fetchSemanticsNodes().size)
    composeTestRule.onRoot().captureRoboImage("src/test/screenshots/catalog_suggestions.png")
  }

  @Test
  fun `the listing can be stepped out of without picking`() {
    render(listOf(model(1)))

    composeTestRule.onNodeWithTag("btn_suggestions_dismiss").performClick()

    assertEquals(1, dismissed)
  }
}
