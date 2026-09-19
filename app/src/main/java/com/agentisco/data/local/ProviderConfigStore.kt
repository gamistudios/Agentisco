package com.agentisco.data.local

import android.content.Context
import com.agentisco.settings.model.AIModel
import com.agentisco.settings.model.AIProvider
import com.agentisco.settings.model.LLMProtocol
import com.agentisco.settings.model.ModelCapabilities
import com.agentisco.settings.model.ModelGenerationSettings
import com.agentisco.settings.model.ReasoningConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Durable provider/model configuration storage.
 *
 * Provider/model records live in one JSON file; API keys live in a separate
 * app-private file so secrets never mix with ordinary configuration state and
 * are never embedded in [AIProvider] objects. Both live under filesDir and are
 * therefore not accessible to other apps or exported via backups of project data.
 *
 * When [context] is null (unit tests / preview), the store degrades to
 * in-memory operation.
 */
class ProviderConfigStore(private val context: Context? = null) {

  private val dir: File? = context?.getDir("agentisco", Context.MODE_PRIVATE)
  private val configFile: File? get() = dir?.resolve("providers.json")
  private val credentialsFile: File? get() = dir?.resolve("credentials.json")

  // In-memory fallback / cache
  private var providersCache: List<AIProvider> = emptyList()
  private var modelsCache: List<AIModel> = emptyList()
  private var selectedIdCache: String? = null
  private var defaultTaskModelIdCache: String? = null
  private val credentialsCache = mutableMapOf<String, String>()

  val hasPersistence: Boolean get() = context != null

  init {
    load()
  }

  // ---- Load / save ----

  private fun load() {
    providersCache = emptyList()
    modelsCache = emptyList()
    selectedIdCache = null
    credentialsCache.clear()

    val cfg = configFile?.takeIf { it.exists() }?.readText()
    if (cfg != null) {
      runCatching {
        val obj = JSONObject(cfg)
        selectedIdCache = obj.optString("selectedModelId").takeIf { it.isNotEmpty() }
        defaultTaskModelIdCache = obj.optString("defaultTaskModelId").takeIf { it.isNotEmpty() }
        providersCache = obj.getJSONArray("providers").toProviderList()
        modelsCache = obj.getJSONArray("models").toModelList()
      }
    }

    val creds = credentialsFile?.takeIf { it.exists() }?.readText()
    if (creds != null) {
      runCatching {
        val obj = JSONObject(creds)
        for (key in obj.keys()) credentialsCache[key] = obj.getString(key)
      }
    }
  }

  private fun persistConfig() {
    val file = configFile ?: return
    val obj = JSONObject()
    obj.put("selectedModelId", selectedIdCache ?: "")
    obj.put("defaultTaskModelId", defaultTaskModelIdCache ?: "")
    obj.put("providers", JSONArray().apply { providersCache.forEach { put(it.toJson()) } })
    obj.put("models", JSONArray().apply { modelsCache.forEach { put(it.toJson()) } })
    runCatching {
      file.parentFile?.mkdirs()
      file.writeText(obj.toString(2))
    }
  }

  private fun persistCredentials() {
    val file = credentialsFile ?: return
    val obj = JSONObject()
    credentialsCache.forEach { (k, v) -> obj.put(k, v) }
    runCatching {
      file.parentFile?.mkdirs()
      file.writeText(obj.toString())
    }
  }

  // ---- Queries ----

  fun getProviders(): List<AIProvider> = providersCache
  fun getModels(): List<AIModel> = modelsCache
  fun getModelsFor(providerId: String): List<AIModel> = modelsCache.filter { it.providerId == providerId }
  fun getSelectedModelId(): String? = selectedIdCache
  fun getApiKey(providerId: String): String? = credentialsCache[providerId]
  fun hasApiKey(providerId: String): Boolean = credentialsCache[providerId]?.isNotBlank() == true

  // ---- Mutations ----

  fun upsertProvider(provider: AIProvider, apiKey: String?) {
    providersCache = providersCache.filterNot { it.id == provider.id } + provider
    if (apiKey != null) {
      if (apiKey.isBlank()) credentialsCache.remove(provider.id) else credentialsCache[provider.id] = apiKey
      persistCredentials()
    }
    persistConfig()
  }

  /** Deletes a provider and cascades to all of its model records. Returns removed model ids. */
  fun deleteProvider(providerId: String): List<String> {
    val removedModels = modelsCache.filter { it.providerId == providerId }.map { it.id }
    providersCache = providersCache.filterNot { it.id == providerId }
    modelsCache = modelsCache.filterNot { it.providerId == providerId }
    credentialsCache.remove(providerId)
    // If the selected model was removed with the provider, fall back to another
    // available model so the selection stays valid after restarts.
    if (selectedIdCache != null && selectedIdCache !in modelsCache.map { it.id }) {
      selectedIdCache = modelsCache.firstOrNull()?.id
    }
    persistConfig()
    persistCredentials()
    return removedModels
  }

  fun upsertModel(model: AIModel) {
    modelsCache = modelsCache.filterNot { it.id == model.id } + model
    persistConfig()
  }

  fun deleteModel(modelId: String) {
    modelsCache = modelsCache.filterNot { it.id == modelId }
    if (selectedIdCache == modelId) selectedIdCache = null
    persistConfig()
  }

  fun selectModel(modelId: String) {
    if (modelsCache.any { it.id == modelId }) {
      selectedIdCache = modelId
      persistConfig()
    }
  }

  /** Model used for background tasks (commit messages, session titles, ...). */
  @Synchronized
  fun getDefaultTaskModelId(): String? = defaultTaskModelIdCache

  @Synchronized
  fun setDefaultTaskModelId(modelId: String?) {
    defaultTaskModelIdCache = modelId
    persistConfig()
  }

  /** Drops a stale selected model; picks the first available one when [autoSelectFallback] is set. */
  fun reconcileSelection(autoSelectFallback: Boolean) {
    if (selectedIdCache != null && selectedIdCache !in modelsCache.map { it.id }) selectedIdCache = null
    if (selectedIdCache == null && autoSelectFallback) selectedIdCache = modelsCache.firstOrNull()?.id
    persistConfig()
  }

  // ---- JSON helpers ----

  private fun AIProvider.toJson() = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("baseUrl", baseUrl)
    put("protocol", protocol.name)
  }

  private fun AIModel.toJson() = JSONObject().apply {
    put("id", id)
    put("providerId", providerId)
    put("modelId", modelId)
    put("displayName", displayName)
    contextWindow?.let { put("contextWindow", it) }
    maxOutputTokens?.let { put("maxOutputTokens", it) }
    put("capabilities", JSONObject().apply {
      put("tools", capabilities.tools)
      put("images", capabilities.images)
      put("parallelToolCalls", capabilities.parallelToolCalls)
      put("promptCaching", capabilities.promptCaching)
      put("interleavedReasoning", capabilities.interleavedReasoning)
      put("maxTokensParameter", capabilities.maxTokensParameter)
      put("streaming", capabilities.streaming)
    })
    reasoning?.let {
      put("reasoning", JSONObject().apply {
        put("enabled", it.enabled)
        put("effort", it.effort)
      })
    }
    put("generationSettings", JSONObject().apply {
      generationSettings.temperature?.let { put("temperature", it) }
      generationSettings.topP?.let { put("topP", it) }
      generationSettings.topK?.let { put("topK", it) }
      put("stopSequences", JSONArray(generationSettings.stopSequences))
      generationSettings.responseMimeType?.let { put("responseMimeType", it) }
      generationSettings.responseJsonSchema?.let { put("responseJsonSchema", it) }
    })
  }

  private fun JSONArray.toProviderList(): List<AIProvider> = (0 until length()).mapNotNull { i ->
    runCatching {
      val o = getJSONObject(i)
      AIProvider(
        id = o.getString("id"),
        name = o.getString("name"),
        baseUrl = o.getString("baseUrl"),
        protocol = LLMProtocol.fromName(o.optString("protocol")) ?: LLMProtocol.OPENAI_CHAT_COMPLETIONS
      )
    }.getOrNull()
  }

  private fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name) else null

  private fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

  private fun JSONArray.toModelList(): List<AIModel> = (0 until length()).mapNotNull { i ->
    runCatching {
      val o = getJSONObject(i)
      val caps = o.optJSONObject("capabilities")
      AIModel(
        id = o.getString("id"),
        providerId = o.getString("providerId"),
        modelId = o.getString("modelId"),
        displayName = o.optString("displayName", o.getString("modelId")),
        contextWindow = if (o.has("contextWindow")) o.getInt("contextWindow") else null,
        maxOutputTokens = if (o.has("maxOutputTokens")) o.getInt("maxOutputTokens") else null,
        capabilities = ModelCapabilities(
          tools = caps?.optBoolean("tools", false) ?: false,
          images = caps?.optBoolean("images", false) ?: false,
          parallelToolCalls = caps?.optBoolean("parallelToolCalls", false) ?: false,
          promptCaching = caps?.optBoolean("promptCaching", false) ?: false,
          interleavedReasoning = caps?.optBoolean("interleavedReasoning", false) ?: false,
          maxTokensParameter = caps?.optBoolean("maxTokensParameter", true) ?: true,
          streaming = caps?.optBoolean("streaming", true) ?: true
        ),
        reasoning = o.optJSONObject("reasoning")?.let {
          ReasoningConfig(enabled = it.optBoolean("enabled", false), effort = it.optString("effort", "medium"))
        },
        generationSettings = o.optJSONObject("generationSettings")?.let { settings ->
          ModelGenerationSettings(
            temperature = settings.optDoubleOrNull("temperature"),
            topP = settings.optDoubleOrNull("topP"),
            topK = settings.optIntOrNull("topK"),
            stopSequences = settings.optJSONArray("stopSequences")?.let { values ->
              (0 until values.length()).mapNotNull { index -> values.optString(index).takeIf { it.isNotBlank() } }
            } ?: emptyList(),
            responseMimeType = settings.optString("responseMimeType").takeIf { it.isNotBlank() },
            responseJsonSchema = settings.optString("responseJsonSchema").takeIf { it.isNotBlank() }
          )
        } ?: ModelGenerationSettings()
      )
    }.getOrNull()
  }
}
