package com.agentisco.data.local

import android.content.Context
import com.agentisco.local.model.LocalGenerationSettings
import com.agentisco.local.model.LocalModel
import com.agentisco.local.model.LocalModelConfiguration
import com.agentisco.local.model.LocalModelFormat
import com.agentisco.local.model.LocalRuntimeSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Durable record of the local models Agentisco knows about, and of what it saw on
 * disk the last time it installed one.
 *
 * The shape follows [ProviderConfigStore]: one JSON file in the app's private
 * config directory, in-memory operation when there is no [context] (unit tests,
 * previews). Model bytes are never stored here — only the configuration that
 * describes them and the evidence an install left behind. Whether a model is
 * actually installed is recomputed from the file itself, because a remembered
 * flag survives a manual deletion or a corrupted download and would then be a
 * lie the UI has to build a broken Install button on.
 */
class LocalModelStore(private val context: Context? = null) {

  /**
   * What the installer saw when it finished. [digest] is the SHA-256 of the bytes
   * that were accepted; it is what a later integrity check compares against, and
   * what an update is measured against.
   */
  data class InstallEvidence(
    val digest: String?,
    val version: String,
    val sizeBytes: Long,
    val installedAtMillis: Long
  )

  /** A stored model plus, when it is installed, the evidence of that install. */
  data class Entry(val model: LocalModel, val evidence: InstallEvidence?)

  private val dir: File? = context?.getDir("agentisco", Context.MODE_PRIVATE)
  private val configFile: File? get() = dir?.resolve("local-models.json")

  private var entriesCache: List<Entry> = emptyList()

  val hasPersistence: Boolean get() = context != null

  init {
    load()
  }

  // ---- Load / save ----

  private fun load() {
    entriesCache = emptyList()
    val json = configFile?.takeIf { it.exists() }?.readText() ?: return
    runCatching {
      val stored = JSONArray(json)
      entriesCache = (0 until stored.length()).mapNotNull { index ->
        val obj = stored.optJSONObject(index) ?: return@mapNotNull null
        val model = obj.optJSONObject("model").toModel() ?: return@mapNotNull null
        Entry(model, obj.optJSONObject("installed").toEvidence())
      }
    }
  }

  private fun persist() {
    val file = configFile ?: return
    val array = JSONArray().apply {
      entriesCache.forEach { entry ->
        put(JSONObject().apply {
          put("model", entry.model.toJson())
          entry.evidence?.let { put("installed", it.toJson()) }
        })
      }
    }
    runCatching {
      file.parentFile?.mkdirs()
      file.writeText(array.toString(2))
    }
  }

  // ---- Queries ----

  fun entries(): List<Entry> = entriesCache

  fun entry(modelId: String): Entry? = entriesCache.firstOrNull { it.model.id == modelId }

  fun model(modelId: String): LocalModel? = entry(modelId)?.model

  fun evidence(modelId: String): InstallEvidence? = entry(modelId)?.evidence

  fun configurations(): Map<String, LocalModelConfiguration> =
    entriesCache.associate { it.model.id to it.model.configuration }

  // ---- Mutations ----

  /**
   * Stores [model], keeping the install evidence that already belongs to that id:
   * refreshing a catalog entry with new remote numbers must not make an installed
   * model look uninstalled.
   */
  fun upsert(model: LocalModel) {
    val previous = entry(model.id)
    entriesCache = entriesCache.filterNot { it.model.id == model.id } +
      Entry(model.copy(installed = false), previous?.evidence)
    persist()
  }

  /** Records a successful install of [model] with the bytes that were accepted. */
  fun markInstalled(model: LocalModel, evidence: InstallEvidence) {
    val previous = entry(model.id)
    val stored = (previous?.model ?: model).copy(installed = true)
    entriesCache = entriesCache.filterNot { it.model.id == model.id } + Entry(stored, evidence)
    persist()
  }

  /** Drops only the evidence, leaving the user's configuration and the record. */
  fun clearInstalled(modelId: String) {
    val existing = entry(modelId) ?: return
    entriesCache = entriesCache.filterNot { it.model.id == modelId } +
      Entry(existing.model.copy(installed = false), null)
    persist()
  }

  fun updateConfiguration(modelId: String, configuration: LocalModelConfiguration) {
    val existing = entry(modelId) ?: return
    entriesCache = entriesCache.map {
      if (it.model.id == modelId) it.copy(model = it.model.copy(configuration = configuration)) else it
    }
    persist()
  }

  /** Removes the record entirely; deleting the bytes is the repository's job. */
  fun delete(modelId: String) {
    entriesCache = entriesCache.filterNot { it.model.id == modelId }
    persist()
  }

  // ---- JSON helpers ----

  private fun LocalModel.toJson() = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("sourceUrl", sourceUrl)
    put("downloadUrl", downloadUrl)
    put("format", format.name)
    put("quantization", quantization)
    put("description", description)
    put("sizeBytes", sizeBytes)
    checksum?.let { put("checksum", it) }
    put("version", version)
    put("builtIn", builtIn)
    put("runtime", JSONObject().apply {
      put("contextSize", configuration.runtime.contextSize)
      put("threadCount", configuration.runtime.threadCount)
      put("batchSize", configuration.runtime.batchSize)
    })
    put("generation", JSONObject().apply {
      put("maxOutputTokens", configuration.generation.maxOutputTokens)
      put("temperature", configuration.generation.temperature)
      put("topK", configuration.generation.topK)
      put("topP", configuration.generation.topP)
      put("repeatPenalty", configuration.generation.repeatPenalty)
    })
    // Absent means "the default set", which is not the same as an empty one.
    configuration.allowedTools?.let { names -> put("tools", JSONArray(names.toList())) }
  }

  private fun JSONObject?.toModel(): LocalModel? {
    val obj = this ?: return null
    return runCatching {
      val runtime = obj.optJSONObject("runtime")
      val generation = obj.optJSONObject("generation")
      LocalModel(
        id = obj.getString("id"),
        name = obj.getString("name"),
        sourceUrl = obj.optString("sourceUrl"),
        downloadUrl = obj.getString("downloadUrl"),
        format = runCatching { LocalModelFormat.valueOf(obj.optString("format")) }
          .getOrDefault(LocalModelFormat.GGUF),
        quantization = obj.optString("quantization"),
        description = obj.optString("description"),
        sizeBytes = obj.optLong("sizeBytes", 0L),
        checksum = obj.optString("checksum").takeIf { it.isNotEmpty() },
        version = obj.optString("version"),
        builtIn = obj.optBoolean("builtIn", false),
        // Never trusted from disk; the repository recomputes it from the file.
        installed = false,
        configuration = LocalModelConfiguration(
          runtime = LocalRuntimeSettings(
            contextSize = runtime?.optInt("contextSize", LocalRuntimeSettings.DEFAULT_CONTEXT)
              ?: LocalRuntimeSettings.DEFAULT_CONTEXT,
            threadCount = runtime?.optInt("threadCount", 0) ?: 0,
            batchSize = runtime?.optInt("batchSize", LocalRuntimeSettings.DEFAULT_BATCH)
              ?: LocalRuntimeSettings.DEFAULT_BATCH
          ),
          generation = LocalGenerationSettings(
            maxOutputTokens = generation?.optInt("maxOutputTokens", LocalGenerationSettings.DEFAULT_MAX_TOKENS)
              ?: LocalGenerationSettings.DEFAULT_MAX_TOKENS,
            temperature = generation?.optDouble("temperature", LocalGenerationSettings.DEFAULT_TEMPERATURE)
              ?: LocalGenerationSettings.DEFAULT_TEMPERATURE,
            topK = generation?.optInt("topK", LocalGenerationSettings.DEFAULT_TOP_K)
              ?: LocalGenerationSettings.DEFAULT_TOP_K,
            topP = generation?.optDouble("topP", 1.0) ?: 1.0,
            repeatPenalty = generation?.optDouble("repeatPenalty", LocalGenerationSettings.DEFAULT_REPEAT_PENALTY)
              ?: LocalGenerationSettings.DEFAULT_REPEAT_PENALTY
          ),
          allowedTools = obj.optJSONArray("tools")?.let { stored ->
            (0 until stored.length()).map { stored.optString(it) }.toSet()
          }
        )
      )
    }.getOrNull()
  }

  private fun InstallEvidence.toJson() = JSONObject().apply {
    digest?.let { put("digest", it) }
    put("version", version)
    put("sizeBytes", sizeBytes)
    put("installedAtMillis", installedAtMillis)
  }

  private fun JSONObject?.toEvidence(): InstallEvidence? {
    val obj = this ?: return null
    return InstallEvidence(
      digest = obj.optString("digest").takeIf { it.isNotEmpty() },
      version = obj.optString("version"),
      sizeBytes = obj.optLong("sizeBytes", 0L),
      installedAtMillis = obj.optLong("installedAtMillis", 0L)
    )
  }
}
