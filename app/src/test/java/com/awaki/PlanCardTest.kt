package com.awaki

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import com.awaki.ui.AgentPlan
import com.awaki.ui.PlanStep
import com.awaki.ui.PlanStepState
import com.awaki.ui.components.PlanCard
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
 * The pinned plan card, rendered for real. What the user sees is the whole
 * point, so these assert the collapsed row, the expanded list, and that an
 * unlimited plan scrolls inside a capped height instead of pushing the
 * composer off screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class PlanCardTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private fun step(
    number: Int,
    content: String,
    state: PlanStepState,
    detail: String = "",
    seconds: Double = 0.0
  ) = PlanStep(number, content, state, detail, seconds)

  private val benchmarkPlan = AgentPlan(
    title = "Needle Retrieval Benchmark",
    note = "Evaluate Needle model with a verified question set",
    steps = listOf(
      step(1, "Download corpus", PlanStepState.DONE, seconds = 12.4),
      step(2, "Build question set", PlanStepState.DONE, seconds = 34.7),
      step(3, "Verify answer passages", PlanStepState.RUNNING, detail = "24 / 86"),
      step(4, "Measure retrieval accuracy", PlanStepState.PENDING),
      step(5, "Measure score separation", PlanStepState.PENDING),
      step(6, "Report findings", PlanStepState.PENDING)
    ),
    turnId = "turn-1",
    running = false,
    stepStartedAt = 0L
  )

  /** Pinned above a stand-in composer, which is where the screen puts it. */
  private fun show(plan: AgentPlan, startExpanded: Boolean) {
    composeTestRule.setContent {
      AwakiTheme {
        Column(
          modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp),
          verticalArrangement = Arrangement.Bottom
        ) {
          var expanded by remember { mutableStateOf(startExpanded) }
          PlanCard(
            plan = plan,
            expanded = expanded,
            onToggle = { expanded = !expanded }
          )
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(64.dp)
              .background(MaterialTheme.colorScheme.surfaceContainer)
          )
        }
      }
    }
  }

  @Test
  fun `collapsed is one row that states the plan and its count`() {
    show(benchmarkPlan, startExpanded = false)

    composeTestRule.onNodeWithText("Plan").assertIsDisplayed()
    composeTestRule.onNodeWithText("2 / 6").assertIsDisplayed()
    // Collapsed it hides the steps, so it costs the conversation almost no height.
    composeTestRule.onNodeWithText("Download corpus").assertDoesNotExist()
    composeTestRule.onNodeWithTag("plan_steps").assertDoesNotExist()
  }

  @Test
  fun `tapping the collapsed row expands the same card`() {
    show(benchmarkPlan, startExpanded = false)

    composeTestRule.onNodeWithTag("plan_toggle").performClick()

    composeTestRule.onNodeWithText("Needle Retrieval Benchmark").assertIsDisplayed()
    composeTestRule.onNodeWithText("Download corpus").assertIsDisplayed()
    composeTestRule.onNodeWithText("Collapse").assertIsDisplayed()

    composeTestRule.onNodeWithTag("plan_toggle").performClick()
    composeTestRule.onNodeWithText("Download corpus").assertDoesNotExist()
  }

  @Test
  fun `expanded numbers every step and states each one`() {
    show(benchmarkPlan, startExpanded = true)

    composeTestRule.onNodeWithText("Evaluate Needle model with a verified question set").assertIsDisplayed()
    composeTestRule.onNodeWithText("2 / 6").assertIsDisplayed()
    listOf("1.", "2.", "3.", "4.", "5.", "6.").forEach {
      composeTestRule.onNodeWithText(it).assertIsDisplayed()
    }
    // A finished step says how long it took; the open one carries its own note.
    composeTestRule.onAllNodesWithText("Completed · 12.4s").assertCountEquals(1)
    composeTestRule.onAllNodesWithText("Completed · 34.7s").assertCountEquals(1)
    composeTestRule.onAllNodesWithText("In progress · 24 / 86").assertCountEquals(1)
    composeTestRule.onAllNodesWithText("Pending").assertCountEquals(3)
  }

  @Test
  fun `a failed step states itself as failed`() {
    show(
      benchmarkPlan.copy(
        steps = listOf(
          step(1, "Download corpus", PlanStepState.FAILED),
          step(2, "Build question set", PlanStepState.SKIPPED),
          step(3, "Report findings", PlanStepState.PENDING)
        ),
        turnId = "turn-2"
      ),
      startExpanded = true
    )

    composeTestRule.onNodeWithText("Download corpus").assertIsDisplayed()
    composeTestRule.onAllNodesWithText("Failed").assertCountEquals(1)
    composeTestRule.onAllNodesWithText("Skipped").assertCountEquals(1)
    // Neither a failure nor a skip is progress.
    composeTestRule.onNodeWithText("0 / 3").assertIsDisplayed()
  }

  @Test
  fun `an unlimited plan scrolls inside a capped height`() {
    val long = benchmarkPlan.copy(
      title = "",
      note = "",
      steps = (1..40).map { number ->
        step(number, "Step $number", if (number == 1) PlanStepState.DONE else PlanStepState.PENDING)
      }
    )
    show(long, startExpanded = true)

    composeTestRule.onNodeWithText("Step 1").assertIsDisplayed()
    // Off-screen rows are not composed at all: the list is windowed, not grown.
    composeTestRule.onNodeWithText("Step 40").assertDoesNotExist()
    val bounds = composeTestRule.onNodeWithTag("plan_steps").getBoundsInRoot()
    val listHeight = bounds.bottom - bounds.top
    assertTrue("plan list grew to $listHeight instead of scrolling", listHeight <= 240.dp)

    composeTestRule.onNodeWithTag("plan_steps").performScrollToKey(40)
    composeTestRule.onNodeWithText("Step 40").assertIsDisplayed()
  }

  @Test
  fun `the card renders both states for inspection`() {
    show(benchmarkPlan, startExpanded = false)
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/agent_plan_collapsed.png")

    composeTestRule.onNodeWithTag("plan_toggle").performClick()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/agent_plan_expanded.png")
  }
}
