package com.agentisco

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.agentisco.ui.screens.PlanModeNotice
import com.agentisco.ui.screens.PlanModeToggle
import com.agentisco.ui.theme.AgentiscoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The plan-mode switch has one job outside the look: a tap flips the mode, in
 * either direction, at any time. The refusal itself is the runtime's business and
 * is tested there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanModeUiTest {

  @get:Rule val composeTestRule = createComposeRule()

  private fun toggle(active: Boolean, onClick: () -> Unit) {
    composeTestRule.setContent {
      AgentiscoTheme { PlanModeToggle(active = active, onToggle = onClick) }
    }
  }

  @Test
  fun `one tap on the switch is one request to change the mode`() {
    var taps = 0
    toggle(active = false, onClick = { taps++ })

    composeTestRule.onNodeWithTag("btn_plan_mode").assertIsDisplayed().performClick()
    assertEquals(1, taps)
  }

  @Test
  fun `a lit switch still switches`() {
    var taps = 0
    toggle(active = true, onClick = { taps++ })

    // The control must stay live while planning: "stop, just plan" is the reason
    // it exists, and a disabled toggle could not end a turn that is already run.
    composeTestRule.onNodeWithTag("btn_plan_mode").performClick()
    assertEquals(1, taps)
  }

  @Test
  fun `the notice states what planning refuses`() {
    composeTestRule.setContent {
      AgentiscoTheme { PlanModeNotice() }
    }
    composeTestRule.onNodeWithTag("plan_mode_notice").assertIsDisplayed()
    composeTestRule
      .onNodeWithText("refuse any change", substring = true)
      .assertIsDisplayed()
  }
}
