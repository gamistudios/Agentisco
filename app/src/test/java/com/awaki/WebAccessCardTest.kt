package com.awaki

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.awaki.agent.web.WebGateway
import com.awaki.data.local.FetchProvider
import com.awaki.data.local.SearchProvider
import com.awaki.data.local.WebAccessSettings
import com.awaki.data.local.WebAccessStore
import com.awaki.data.repository.WorkspaceRepository
import com.awaki.ui.WorkspaceViewModel
import com.awaki.ui.screens.settings.WebAccessCard
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
 * The Settings card that decides which provider answers a web tool. What has to hold on
 * screen: the defaults are stated plainly, a key joins the rotation as a handle and
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

  /** A minimal DuckDuckGo results page: one hit behind the engine's own redirect. */
  private val searchPage = StubAnswer(
    200,
    """<a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fdocs">Docs</a>""" +
      """<a class="result__snippet">A snippet</a>""",
    "text/html"
  )

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

  /**
   * A repository over a scripted gateway. The build's own keys are left out, because the
   * screen a user of this build sees is the anonymous tier plus whatever they pasted.
   */
  private fun viewModelWith(
    vararg answers: StubAnswer
  ): Pair<WorkspaceViewModel, MutableList<Request>> {
    val sent = mutableListOf<Request>()
    val gateway = WebGateway(
      client = stubHttpScripted(*answers, capture = { sent.add(it) }),
      settings = { WebAccessSettings() },
      appKeys = { emptyList() }
    )
    val viewModel = held.hold(WorkspaceViewModel(WorkspaceRepository(context = null, web = gateway)))
    return viewModel to sent
  }

  @Test
  fun `the card states the defaults the tools actually follow`() {
    val (viewModel, _) = viewModelWith(readerAnswer)
    compose.setContent { AwakiTheme { WebAccessCard(viewModel) } }

    compose.onNodeWithText("Web Access").assertIsDisplayed()
    compose
      .onNodeWithText("Search defaults to DuckDuckGo", substring = true)
      .assertIsDisplayed()
    compose.onNodeWithTag("chip_search_duckduckgo").assertIsDisplayed()
    compose.onNodeWithTag("chip_search_parallel").assertIsDisplayed()
    compose.onNodeWithTag("chip_fetch_jina").assertIsDisplayed()
    compose.onNodeWithTag("chip_fetch_direct").assertIsDisplayed()
    compose.onNodeWithTag("switch_web_fallback").assertIsDisplayed()
    assertEquals(SearchProvider.DuckDuckGo, viewModel.webAccess.value.searchProvider)
    assertEquals(FetchProvider.Jina, viewModel.webAccess.value.fetchProvider)
    // The count the card reports is the rotation the build actually carries, so a CI
    // build that bakes keys in cannot fail a test written for a build that does not.
    compose.onNodeWithText("rotation: ${viewModel.bundledJinaKeyCount}").assertIsDisplayed()
  }

  @Test
  fun `choosing providers and toggling fallback only changes settings`() {
    val (viewModel, sent) = viewModelWith(readerAnswer)
    compose.setContent { AwakiTheme { WebAccessCard(viewModel) } }

    compose.onNodeWithTag("chip_search_parallel").performClick()
    assertEquals(SearchProvider.Parallel, viewModel.webAccess.value.searchProvider)

    compose.onNodeWithTag("chip_fetch_direct").performClick()
    assertEquals(FetchProvider.Direct, viewModel.webAccess.value.fetchProvider)
    compose.onNodeWithText("JavaScript pages come back empty", substring = true).assertIsDisplayed()

    compose.onNodeWithTag("switch_web_fallback").performClick()
    assertFalse(viewModel.webAccess.value.fallback)
    compose.onNodeWithText("Off: only the chosen provider is asked", substring = true).assertIsDisplayed()

    compose.onNodeWithTag("switch_web_fallback").performClick()
    assertTrue(viewModel.webAccess.value.fallback)
    assertEquals("choosing a provider alone changes a setting; nothing was fetched to prove it", 0, sent.size)
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
  fun `Test reports which provider answered rather than what was configured`() {
    val (viewModel, sent) = viewModelWith(readerAnswer, searchPage)
    compose.setContent { AwakiTheme { WebAccessCard(viewModel) } }

    compose.onNodeWithTag("btn_test_web_access").performClick()
    compose.waitUntil(5_000) { viewModel.webAccessReport.value.isNotEmpty() }

    val report = viewModel.webAccessReport.value
    assertEquals(listOf("r.jina.ai", "html.duckduckgo.com"), sent.map { it.url.host })
    assertEquals(report.toString(), 4, report.size)
    assertTrue(report[0], report[0].startsWith("Fetch · Jina.ai: served by anonymous"))
    assertTrue(report[1], report[1].startsWith("Search · DuckDuckGo: 1 results"))
    assertTrue(report[2], report[2].startsWith("Fallback is on"))
    assertTrue(report[3], report[3].contains("0 yours"))
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
