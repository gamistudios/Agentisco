package com.awaki

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.awaki.ui.screens.settings.AboutCard
import com.awaki.ui.theme.AwakiTheme
import com.awaki.ui.theme.DarkBackground
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class AboutCardScreenshotTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `about card states what the app is and who built it`() {
    composeTestRule.setContent {
      AwakiTheme {
        AboutCard(modifier = Modifier.fillMaxSize().background(DarkBackground))
      }
    }

    composeTestRule.onNodeWithText("About").assertIsDisplayed()
    composeTestRule.onNodeWithText("Gemechis Chala").assertIsDisplayed()
    composeTestRule.onNodeWithText("gladsonchala@gmail.com").assertIsDisplayed()
    composeTestRule.onNodeWithText("@venopyx").assertIsDisplayed()
    composeTestRule
      .onNodeWithText("Awaki is a coding workspace that runs on the device itself", substring = true)
      .assertIsDisplayed()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/settings_about_card.png")
  }
}
