package com.agentisco

import com.agentisco.agent.llm.CatalogModel
import com.agentisco.agent.llm.LlmService
import com.agentisco.agent.llm.ModelListing
import com.agentisco.data.local.ProviderConfigStore
import com.agentisco.data.repository.WorkspaceRepository
import com.agentisco.settings.model.AIProvider
import com.agentisco.settings.model.LLMProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Auto-discovery is a convenience, never a dependency: it fires once when a
 * provider is created, asks the provider that was just configured, caches the
 * answer, and otherwise stays out of the way so the manual model list keeps
 * working. What it must never do is re-ask on every save, hand a provider's
 * listing to the UI as if it were saved configuration, or leak a key into the
 * failure text the settings screen shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ModelCatalogFetchTest {

  private companion object {
    const val KEY = "sk-ant-prod-2f9c-not-for-display"

    val SONNET = CatalogModel("claude-sonnet-4-5", "Claude Sonnet 4.5", 200_000, 64_000, tools = true, images = true)
  }

  /** Records every listing call the repository makes, answering with a canned outcome. */
  private class FakeLlmService(
    var outcome: ModelListing = ModelListing(true, "Connected", listOf(SONNET)),
    var boom: Throwable? = null
  ) : LlmService() {
    val calls = mutableListOf<Pair<String, String>>()

    override suspend fun listModels(provider: AIProvider, apiKey: String): ModelListing {
      calls += provider.id to apiKey
      boom?.let { throw it }
      return outcome
    }
  }

  private lateinit var llm: FakeLlmService
  private lateinit var repo: WorkspaceRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    llm = FakeLlmService()
    // A store with no context keeps providers, models and keys in memory, which
    // is what makes the key lookup in loadModelCatalog reachable here.
    repo = WorkspaceRepository(context = null, providerStore = ProviderConfigStore(), llmService = llm)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun catalog(providerId: String) = repo.modelCatalogs.value[providerId]

  private fun models(providerId: String): List<CatalogModel> =
    (catalog(providerId) as WorkspaceRepository.ModelCatalogState.Available).models

  private fun failed(providerId: String): String =
    (catalog(providerId) as WorkspaceRepository.ModelCatalogState.Failed).message

  private fun addProvider(name: String = "Anthropic", apiKey: String? = KEY): AIProvider =
    repo.saveProvider(name = name, baseUrl = "https://api.anthropic.com", protocol = LLMProtocol.ANTHROPIC_MESSAGES, apiKey = apiKey)

  @Test
  fun `a newly added provider is asked for its models once, with its own key`() {
    val provider = addProvider()

    assertEquals(listOf(SONNET), models(provider.id))
    assertEquals(1, llm.calls.size)
    assertEquals(provider.id to KEY, llm.calls.single())
  }

  @Test
  fun `editing a provider never re-asks on its own`() {
    val provider = addProvider()
    assertEquals(1, llm.calls.size)

    // Renaming, and even replacing the key, is a configuration save: the next
    // listing is the user's call, because it costs their quota.
    repo.saveProvider(
      name = "Anthropic Work", baseUrl = "https://api.anthropic.com",
      protocol = LLMProtocol.ANTHROPIC_MESSAGES, apiKey = "sk-ant-rotated", providerId = provider.id
    )

    assertEquals(1, llm.calls.size)
    // The old answer is stale and is dropped rather than shown as if it still applied.
    assertNull(catalog(provider.id))

    // Pressing Fetch Models is what asks again — with the key saved now, not the
    // one the provider had when the dialog was opened.
    repo.loadModelCatalog(provider.id)
    assertEquals(2, llm.calls.size)
    assertEquals("sk-ant-rotated", llm.calls.last().second)
  }

  @Test
  fun `a listing is cached and only a forced fetch repeats the request`() {
    val provider = addProvider()

    repo.loadModelCatalog(provider.id)
    repo.loadModelCatalog(provider.id)
    assertEquals(1, llm.calls.size)

    repo.loadModelCatalog(provider.id, force = true)
    assertEquals(2, llm.calls.size)
  }

  @Test
  fun `a provider without a key is not asked and says what the user must do`() {
    val provider = addProvider(apiKey = "")
    // No credential means no request to make at all.
    assertTrue(llm.calls.isEmpty())
    assertNull(catalog(provider.id))

    repo.loadModelCatalog(provider.id)
    assertTrue(llm.calls.isEmpty())
    assertTrue(failed(provider.id).contains("API key"))
  }

  @Test
  fun `a rejected listing fails without a stuck spinner and without the key`() {
    llm.outcome = ModelListing(false, "Authentication failed: the API key was rejected.")
    val provider = addProvider()

    val message = failed(provider.id)
    // The catalog is in-memory only, so the manual model list is untouched.
    assertFalse(message.isBlank())
    assertFalse(message.contains(KEY))
    assertEquals(KEY, llm.calls.single().second)
  }

  @Test
  fun `a provider that throws is reported as a failure, not a crash`() {
    // A transport fault inside the fetch coroutine has to land as a readable
    // failure, or the dialog sits on "asking…" for the rest of the session.
    llm.boom = java.io.IOException("Unable to resolve host api.anthropic.com")
    val provider = addProvider()

    assertTrue(failed(provider.id).contains("Unable to resolve host"))
    assertEquals(1, repo.providers.value.size)

    // An exception with no message still explains itself.
    llm.boom = IllegalStateException()
    repo.loadModelCatalog(provider.id)
    assertTrue(failed(provider.id).isNotEmpty())
  }

  @Test
  fun `each provider keeps its own catalog`() {
    val anthropic = addProvider()
    llm.outcome = ModelListing(true, "Connected", listOf(CatalogModel("gpt-4o", "gpt-4o", 128_000, 16_384)))
    val openai = repo.saveProvider(
      name = "OpenAI", baseUrl = "https://api.openai.com/v1",
      protocol = LLMProtocol.OPENAI_CHAT_COMPLETIONS, apiKey = "sk-open"
    )

    assertEquals(listOf(SONNET), models(anthropic.id))
    assertEquals(listOf("gpt-4o"), models(openai.id).map { it.modelId })
  }

  @Test
  fun `deleting a provider takes its catalog with it`() {
    val provider = addProvider()
    assertEquals(1, models(provider.id).size)

    repo.deleteProvider(provider.id)

    assertNull(catalog(provider.id))
    assertFalse(repo.providers.value.any { it.id == provider.id })
  }
}
