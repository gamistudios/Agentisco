package com.awaki

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.awaki.agent.web.WebGateway
import com.awaki.data.local.WebAccessSettings
import com.awaki.data.local.WebAccessStore
import com.awaki.data.repository.WorkspaceRepository
import com.awaki.ui.WorkspaceViewModel
import com.awaki.ui.screens.WebAccessCard
import com.awaki.ui.theme.AwakiTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Request
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
 * The Settings card that decides which tier answers a web tool. What has to hold on
 * screen: the tier order is stated plainly, a key joins the rotation as a handle and
 * never as a secret, and Test reports what actually answered rather than what was
 * configured. The gateway is injected, so nothing here reaches the network.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class WebAccessCardTest {

  @get:Rule
  val compose = createComposeRule()

  private val readerAnswer = StubAnswer(
    200,
    """{"code":200,"data":{"title":"Example Domain","url":"https://example.com/","""" +
      """content":"This domain is for use in examples."}}"""
  )

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  /**
   * A repository over a scripted gateway. The build's own keys are left out, because the
   * screen a user of this build sees is the anonymous tier plus whatever they pasted.
   */
  private fun viewModelWith(
    vararg answers: StubAnswer,
    preferJina: Boolean = true
  ): Pair<WorkspaceViewModel, MutableList<Request>> {
    val sent = mutableListOf<Request>()
    val gateway = WebGateway(
      client = stubHttpScripted(*answers, capture = { sent.add(it) }),
      settings = { WebAccessSettings(preferJina = preferJina) },
      appKeys = { emptyList() }
    )
    return WorkspaceViewModel(WorkspaceRepository(context = null, web = gateway)) to sent
  }

  @Test
  fun `the card states the tier order the tools actually follow`() {
    val (viewModel, _) = viewModelWith(readerAnswer)
    compose.setContent { AwakiTheme { WebAccessCard(viewModel) } }

    compose.onNodeWithText("Web Access (Jina.ai)").assertIsDisplayed()
    compose.onNodeWithText("Route web tools through Jina.ai").assertIsDisplayed()
    compose
      .onNodeWithText("Reader: Jina.ai first (20 pages a minute with no key)", substring = true)
      .assertIsDisplayed()
    compose.onNodeWithTag("switch_prefer_jina").assertIsDisplayed()
    // The count the card reports is the rotation the build actually carries, so a CI
    // build that bakes keys in cannot fail a test written for a build that does not.
    compose.onNodeWithText("rotation: ${viewModel.bundledJinaKeyCount}").assertIsDisplayed()
  }

  @Test
  fun `the switch says what it keeps on the device and costs nothing to change`() {
    val (viewModel, sent) = viewModelWith(readerAnswer)
    compose.setContent { AwakiTheme { WebAccessCard(viewModel) } }

    compose.onNodeWithTag("switch_prefer_jina").performClick()
    assertFalse(viewModel.webAccess.value.preferJina)
    compose.onNodeWithText("Off: the URL is never sent to a third party", substring = true).assertIsDisplayed()

    compose.onNodeWithTag("switch_prefer_jina").performClick()
    assertTrue(viewModel.webAccess.value.preferJina)
    assertEquals("the switch alone changes a setting; nothing was fetched to prove it", 0, sent.size)
  }

  @Test
  fun `a pasted key joins the rotation as a handle with the secret left out of the view`() {
    val (viewModel, _) = viewModelWith(readerAnswer)
    compose.setContent { AwakiTheme { WebAccessCard(viewModel) } }
    val key = "jina_super_secret_key_value"

    compose.onNodeWithTag("input_jina_key").performTextInput(key)
    compose.onNodeWithTag("btn_add_jina_key").performClick()

    val handle = WebAccessStore.fingerprintOf(key)
    val bundled = viewModel.bundledJinaKeyCount
    compose.onNodeWithText("Added 1 key to the rotation.").assertIsDisplayed()
    compose.onNodeWithText("rotation: ${bundled + 1}").assertIsDisplayed()
    compose.onNodeWithTag("chip_jina_key_$handle").assertIsDisplayed()
    assertEquals(listOf(handle), viewModel.jinaKeyHandles.value)
    compose.onNodeWithText(key).assertDoesNotExist()

    compose.onNodeWithTag("chip_jina_key_$handle").performClick()
    compose.onNodeWithTag("chip_jina_key_$handle").assertDoesNotExist()
    compose.onNodeWithText("rotation: $bundled").assertIsDisplayed()
  }

  @Test
  fun `a paste that holds no key says so instead of pretending`() {
    val (viewModel, _) = viewModelWith(readerAnswer)
    compose.setContent { AwakiTheme { WebAccessCard(viewModel) } }

    compose.onNodeWithTag("input_jina_key").performTextInput("here is my key, ask me later")
    compose.onNodeWithTag("btn_add_jina_key").performClick()

    compose.onNodeWithText("Nothing added — that held no key.").assertIsDisplayed()
    compose.onNodeWithText("rotation: ${viewModel.bundledJinaKeyCount}").assertIsDisplayed()
  }

  @Test
  fun `Test reports which tier answered rather than what was configured`() {
    val (viewModel, sent) = viewModelWith(readerAnswer)
    compose.setContent { AwakiTheme { WebAccessCard(viewModel) } }

    compose.onNodeWithTag("btn_test_web_access").performClick()
    compose.waitUntil(5_000) { viewModel.webAccessReport.value.isNotEmpty() }

    val report = viewModel.webAccessReport.value
    assertEquals(listOf("r.jina.ai"), sent.map { it.url.host })
    assertEquals(report.toString(), 3, report.size)
    assertTrue(report[0], report[0].startsWith("Reader: served by anonymous"))
    assertTrue(report[1], report[1].contains("no key configured"))
    assertTrue(report[2], report[2].contains("0 yours, 0 bundled"))
    compose.onNodeWithText(report[0], substring = true).assertIsDisplayed()
  }

  @Test
  fun `every control on the card is reachable on a phone width`() {
    val (viewModel, _) = viewModelWith(readerAnswer)
    compose.setContent { AwakiTheme { WebAccessCard(viewModel) } }

    compose.onNodeWithTag("btn_add_jina_key").assertIsDisplayed()
    compose.onNodeWithTag("btn_test_web_access").assertIsDisplayed()
    compose.onNodeWithText("Add").assertIsDisplayed()
    compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/settings_web_access_card.png")
  }
}
