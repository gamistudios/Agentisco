package com.agentisco

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agentisco.data.local.ProviderConfigStore
import com.agentisco.settings.model.AIModel
import com.agentisco.settings.model.AIProvider
import com.agentisco.settings.model.LLMProtocol
import com.agentisco.settings.model.ModelCapabilities
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderConfigStoreTest {

  private fun newStore(): ProviderConfigStore =
    ProviderConfigStore(ApplicationProvider.getApplicationContext<Context>())

  @Before
  fun cleanStorage() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    File(context.getDir("agentisco", Context.MODE_PRIVATE), "providers.json").delete()
    File(context.getDir("agentisco", Context.MODE_PRIVATE), "credentials.json").delete()
  }

  private fun seedTwoProvidersWithSameModelId(store: ProviderConfigStore) {
    store.upsertProvider(
      AIProvider("p-hf", "Hugging Face", "https://router.huggingface.co/v1", LLMProtocol.OPENAI_CHAT_COMPLETIONS, hasApiKey = true),
      "hf-key"
    )
    store.upsertProvider(
      AIProvider("p-tr", "TokenRouter", "https://api.tokenrouter.io/v1", LLMProtocol.OPENAI_CHAT_COMPLETIONS, hasApiKey = true),
      "tr-key"
    )
    store.upsertModel(
      AIModel("model_001", "p-hf", "openai/gpt-oss-120b", "GPT OSS 120B", contextWindow = 131072, capabilities = ModelCapabilities(tools = true))
    )
    store.upsertModel(
      AIModel("model_002", "p-tr", "openai/gpt-oss-120b", "GPT OSS 120B", contextWindow = 131072, capabilities = ModelCapabilities(tools = true))
    )
  }

  @Test
  fun `same model identifier under two providers remains two distinct selectable models`() {
    val store = newStore()
    seedTwoProvidersWithSameModelId(store)

    val models = store.getModels()
    assertEquals(2, models.size)
    assertEquals(setOf("model_001", "model_002"), models.map { it.id }.toSet())
    // Same wire identifier, different provider relationship
    assertTrue(models.all { it.modelId == "openai/gpt-oss-120b" })
    assertEquals("p-hf", models.first { it.id == "model_001" }.providerId)
    assertEquals("p-tr", models.first { it.id == "model_002" }.providerId)
  }

  @Test
  fun `selection references the unique model record not the model name`() {
    val store = newStore()
    seedTwoProvidersWithSameModelId(store)

    store.selectModel("model_002")
    assertEquals("model_002", store.getSelectedModelId())

    // Selecting the other record with the identical modelId must be distinguishable
    store.selectModel("model_001")
    assertEquals("model_001", store.getSelectedModelId())
  }

  @Test
  fun `deleting a provider cascades to its models and reconciles selection`() {
    val store = newStore()
    seedTwoProvidersWithSameModelId(store)
    store.selectModel("model_002")

    val removed = store.deleteProvider("p-tr")
    assertEquals(listOf("model_002"), removed)
    assertEquals(1, store.getProviders().size)
    assertEquals(1, store.getModels().size)
    assertNull(store.getModels().firstOrNull { it.providerId == "p-tr" })
    // Selected model belonged to the deleted provider → fallback to the remaining one
    assertEquals("model_001", store.getSelectedModelId())
  }

  @Test
  fun `provider and model configuration survive a restart`() {
    val first = newStore()
    seedTwoProvidersWithSameModelId(first)
    first.selectModel("model_001")

    // "Restart": a fresh store instance re-reading storage
    val second = newStore()
    assertEquals(2, second.getProviders().size)
    assertEquals(2, second.getModels().size)
    assertEquals("model_001", second.getSelectedModelId())
    val reloaded = second.getModels().first { it.id == "model_001" }
    assertEquals("openai/gpt-oss-120b", reloaded.modelId)
    assertEquals(131072, reloaded.contextWindow)
    assertTrue(reloaded.capabilities.tools)
  }

  @Test
  fun `Gemini protocol and model configuration survive a restart`() {
    val first = newStore()
    first.upsertProvider(
      AIProvider("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta", LLMProtocol.GOOGLE_GEMINI),
      "gemini-key"
    )
    first.upsertModel(
      AIModel(
        id = "gemini-flash",
        providerId = "gemini",
        modelId = "models/gemini-3.1-flash-lite",
        displayName = "Gemini Flash Lite",
        contextWindow = 131072,
        maxOutputTokens = 65536,
        capabilities = ModelCapabilities(tools = true)
      )
    )

    val second = newStore()
    assertEquals(LLMProtocol.GOOGLE_GEMINI, second.getProviders().single { it.id == "gemini" }.protocol)
    val reloaded = second.getModels().single { it.id == "gemini-flash" }
    assertEquals("models/gemini-3.1-flash-lite", reloaded.modelId)
    assertEquals(131072, reloaded.contextWindow)
    assertEquals(65536, reloaded.maxOutputTokens)
    assertTrue(reloaded.capabilities.tools)
  }

  @Test
  fun `api keys live in credential storage separate from provider records`() {
    val store = newStore()
    seedTwoProvidersWithSameModelId(store)

    assertEquals("hf-key", store.getApiKey("p-hf"))
    assertEquals("tr-key", store.getApiKey("p-tr"))
    // The provider record itself never carries the secret
    assertTrue(store.getProviders().all { it.hasApiKey })
    val providerJson = File(
      ApplicationProvider.getApplicationContext<Context>().getDir("agentisco", Context.MODE_PRIVATE),
      "providers.json"
    ).readText()
    assertFalse(providerJson.contains("hf-key"))
    assertFalse(providerJson.contains("tr-key"))
  }

  @Test
  fun `updating a provider keeps its models and can replace the key`() {
    val store = newStore()
    seedTwoProvidersWithSameModelId(store)

    store.upsertProvider(
      AIProvider("p-hf", "Hugging Face", "https://router.huggingface.co/v2", LLMProtocol.OPENAI_CHAT_COMPLETIONS, hasApiKey = true),
      "hf-key-v2"
    )
    assertEquals(1, store.getModelsFor("p-hf").size)
    assertEquals("hf-key-v2", store.getApiKey("p-hf"))
    assertEquals(2, store.getProviders().size)
  }

  @Test
  fun `deleting a model clears selection only when it was selected`() {
    val store = newStore()
    seedTwoProvidersWithSameModelId(store)
    store.selectModel("model_001")

    store.deleteModel("model_002")
    assertEquals("model_001", store.getSelectedModelId())
    store.deleteModel("model_001")
    assertNull(store.getSelectedModelId())
  }

  @Test
  fun `a key saved with a provider still reads as set after a restart`() {
    // hasApiKey used to be dropped on save, so every provider with a stored
    // key reloaded as "No API key" forever.
    val first = newStore()
    first.upsertProvider(
      AIProvider(
        "p-hf", "Hugging Face", "https://router.huggingface.co/v1",
        LLMProtocol.OPENAI_CHAT_COMPLETIONS, hasApiKey = true
      ),
      "hf-key"
    )

    val second = newStore()
    val reloaded = second.getProviders().single()
    assertTrue("hasApiKey must survive a restart", reloaded.hasApiKey)
    assertEquals("hf-key", second.getApiKey("p-hf"))

    // Clearing the key has to flip it back, so the label tracks the truth.
    second.upsertProvider(reloaded.copy(hasApiKey = false), "")
    assertFalse(newStore().getProviders().single().hasApiKey)
  }

  @Test
  fun `providers saved by an older install fall back to credentials for the key flag`() {
    // Legacy providers.json has no hasApiKey field; the reload must trust
    // credentials.json instead of assuming the key is missing.
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dir = context.getDir("agentisco", Context.MODE_PRIVATE)

    newStore().upsertProvider(
      AIProvider(
        "p-hf", "Hugging Face", "https://router.huggingface.co/v1",
        LLMProtocol.OPENAI_CHAT_COMPLETIONS, hasApiKey = true
      ),
      "hf-key"
    )
    // Rewrite the config the pre-fix way: every field except hasApiKey.
    val legacy = JSONObject().apply {
      put("id", "p-hf")
      put("name", "Hugging Face")
      put("baseUrl", "https://router.huggingface.co/v1")
      put("protocol", LLMProtocol.OPENAI_CHAT_COMPLETIONS.name)
    }
    dir.resolve("providers.json").writeText(
      JSONObject().put("providers", JSONArray().apply { put(legacy) }).toString()
    )

    val second = newStore()
    assertTrue(second.getProviders().single().hasApiKey)
  }

  @Test
  fun `hasApiKey flag stays out of the persisted provider file`() {
    // The flag is persisted, but the secret itself must never be.
    newStore().upsertProvider(
      AIProvider(
        "p-hf", "Hugging Face", "https://router.huggingface.co/v1",
        LLMProtocol.OPENAI_CHAT_COMPLETIONS, hasApiKey = true
      ),
      "hf-secret-value"
    )

    val providerJson = File(
      ApplicationProvider.getApplicationContext<Context>().getDir("agentisco", Context.MODE_PRIVATE),
      "providers.json"
    ).readText()
    assertFalse(providerJson.contains("hf-secret-value"))
    // The flag itself is fine to write — it is not a secret.
    assertTrue(providerJson.contains("hasApiKey"))
  }
}
