package com.agentisco

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.agentisco.ui.components.DevKeyboardBar
import com.agentisco.ui.theme.AgentiscoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CTRL is a desktop-style modifier, not a character: it arms, qualifies the
 * next key press, then releases. These pin that contract so it can't regress
 * into inserting text of its own or leaking its armed state across modes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevKeyboardBarCtrlTest {

  @get:Rule val composeTestRule = createComposeRule()

  private var lastInserted: String? = null
  private var lastCtrlKey: String? = null

  private fun setContent() {
    lastInserted = null
    lastCtrlKey = null
    composeTestRule.setContent {
      AgentiscoTheme {
        DevKeyboardBar(
          onInsertSymbol = { lastInserted = it },
          onCtrlKey = { lastCtrlKey = it }
        )
      }
    }
  }

  @Test
  fun `ctrl is the first key of the first row`() {
    setContent()

    composeTestRule.onNodeWithTag("key_ctrl").assertIsDisplayed()
    assertNull("a fresh bar has not armed CTRL", lastCtrlKey)
  }

  @Test
  fun `arming ctrl inserts nothing on its own`() {
    setContent()

    composeTestRule.onNodeWithTag("key_ctrl").performClick()

    // A modifier does nothing by itself — it qualifies the NEXT key.
    assertNull("arming CTRL must not insert text", lastInserted)
    assertNull("arming CTRL must not fire a ctrl combo", lastCtrlKey)
  }

  @Test
  fun `pressing a symbol without ctrl inserts the symbol`() {
    setContent()

    // NOTE: do not touch key_ctrl here — this covers the unmodified path.
    composeTestRule.onNodeWithTag("key_/").performClick()

    assertEquals("the / key inserts itself", "/", lastInserted)
    assertNull("no modifier was armed, so no ctrl combo fires", lastCtrlKey)
  }

  @Test
  fun `ctrl plus slash fires the combo and releases the modifier`() {
    setContent()

    composeTestRule.onNodeWithTag("key_ctrl").performClick()
    composeTestRule.onNodeWithTag("key_/").performClick()

    assertEquals("CTRL+/ is delivered to onCtrlKey", "/", lastCtrlKey)
    assertNull("CTRL must suppress the plain insert", lastInserted)

    // The modifier is one-shot: the next press is an ordinary insert again.
    composeTestRule.onNodeWithTag("key_/").performClick()
    assertEquals("CTRL released — / now inserts normally", "/", lastInserted)
  }

  @Test
  fun `tapping ctrl twice cancels the modifier`() {
    setContent()

    composeTestRule.onNodeWithTag("key_ctrl").performClick()
    composeTestRule.onNodeWithTag("key_ctrl").performClick()
    composeTestRule.onNodeWithTag("key_/").performClick()

    assertEquals("disarmed CTRL inserts the plain symbol", "/", lastInserted)
    assertNull("disarmed CTRL fires no combo", lastCtrlKey)
  }

  @Test
  fun `tab key keeps its multi-character insert under ctrl`() {
    setContent()

    composeTestRule.onNodeWithTag("key_Tab").performClick()
    assertEquals("Tab inserts two spaces", "  ", lastInserted)

    // CTRL + Tab: the modifier consumes the key, so the spaces are NOT inserted.
    composeTestRule.onNodeWithTag("key_ctrl").performClick()
    composeTestRule.onNodeWithTag("key_Tab").performClick()
    assertEquals("CTRL+Tab is reported as a combo", "Tab", lastCtrlKey)
    assertEquals("the plain Tab insert is suppressed", "  ", lastInserted)
  }

  @Test
  fun `arming ctrl then switching mode drops the modifier`() {
    setContent()
    assertNull("precondition: nothing fired yet", lastCtrlKey)

    composeTestRule.onNodeWithTag("key_ctrl").performClick()
    // Switch to the Actions tab — the armed modifier must not carry over.
    composeTestRule.onNodeWithTag("tab_actions").performClick()
    composeTestRule.onNodeWithTag("key_Undo").performClick()

    assertNull("CTRL did not survive a mode switch", lastCtrlKey)
  }
}
