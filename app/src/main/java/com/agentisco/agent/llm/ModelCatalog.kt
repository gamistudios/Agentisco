package com.agentisco.agent.llm

import org.json.JSONObject

/**
 * One entry of a provider's own model listing, already merged with whatever the
 * app knows about that model family. The settings UI offers these as the source
 * for a new model record, so the user picks a real model instead of typing an
 * id and guessing at its limits.
 */
data class CatalogModel(
  val modelId: String,
  val displayName: String,
  val contextWindow: Int? = null,
  val maxOutputTokens: Int? = null,
  val tools: Boolean? = null,
  val images: Boolean? = null
) {
  /** A copy that keeps the provider's facts but carries a different id. */
  fun withId(id: String, name: String = id) = copy(modelId = id, displayName = name)
}

/**
 * Outcome of asking a provider for its models. The same call answers two
 * questions: whether the credential and endpoint work at all (it costs no
 * tokens), and what is actually available to select.
 */
data class ModelListing(
  val ok: Boolean,
  val message: String,
  val models: List<CatalogModel> = emptyList()
)

/**
 * Durable facts about model families, used where a listing endpoint publishes
 * only names. Anthropic's `/v1/models` returns `id` and `display_name` and
 * nothing about size; Gemini's returns real token limits; OpenAI-compatible
 * routers vary. A family is keyed by the longest matching prefix, and each
 * ceiling is the smallest documented one in that family, so a request built
 * from these numbers is never rejected for asking more than the model can
 * write. Anything unknown stays null and the user fills it in.
 */
internal object ModelFacts {

  data class Limits(
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val tools: Boolean? = null,
    val images: Boolean? = null
  )

  /** Longest prefix wins, so `claude-sonnet-4-5` is read before `claude-sonnet-4`. */
  private val byPrefix: List<Pair<String, Limits>> = listOf(
    // Anthropic. Every Claude model shares one 200k window; the output ceiling
    // is what differs between generations.
    "claude-sonnet-4-5" to Limits(200_000, 64_000, tools = true, images = true),
    "claude-sonnet-4" to Limits(200_000, 64_000, tools = true, images = true),
    "claude-opus-4-1" to Limits(200_000, 32_000, tools = true, images = true),
    "claude-opus-4" to Limits(200_000, 32_000, tools = true, images = true),
    "claude-haiku-4" to Limits(200_000, 64_000, tools = true, images = true),
    "claude-3-7-sonnet" to Limits(200_000, 64_000, tools = true, images = true),
    "claude-3-5-sonnet" to Limits(200_000, 8_192, tools = true, images = true),
    "claude-3-5-haiku" to Limits(200_000, 8_192, tools = true, images = false),
    "claude-3-opus" to Limits(200_000, 4_096, tools = true, images = true),
    "claude-3-sonnet" to Limits(200_000, 4_096, tools = true, images = true),
    "claude-3-haiku" to Limits(200_000, 4_096, tools = true, images = true),
    "claude-" to Limits(200_000, 8_192, tools = true, images = true),

    // Google. The listing normally reports these, so the table is only a
    // fallback for gateways that proxy Gemini without the limits.
    "gemini-2.5-pro" to Limits(1_048_576, 65_536, tools = true, images = true),
    "gemini-2.5-flash" to Limits(1_048_576, 65_536, tools = true, images = true),
    "gemini-2.0-flash" to Limits(1_048_576, 8_192, tools = true, images = true),
    "gemini-1.5-pro" to Limits(2_097_152, 8_192, tools = true, images = true),
    "gemini-1.5-flash" to Limits(1_048_576, 8_192, tools = true, images = true),
    "gemini-" to Limits(32_768, 8_192, tools = true, images = true),

    // OpenAI and the routers that imitate it.
    "gpt-5" to Limits(400_000, 128_000, tools = true, images = true),
    "gpt-4.1" to Limits(1_047_576, 32_768, tools = true, images = true),
    "gpt-4o" to Limits(128_000, 16_384, tools = true, images = true),
    "o3" to Limits(200_000, 100_000, tools = true, images = true),
    "o4-mini" to Limits(200_000, 100_000, tools = true, images = true),
    "gpt-" to Limits(128_000, 16_384, tools = true, images = false)
  ).sortedByDescending { it.first.length }

  fun lookup(modelId: String): Limits? {
    val id = modelId.lowercase()
    return byPrefix.firstOrNull { id.startsWith(it.first) }?.second
  }
}

/**
 * Turns each protocol's model-listing payload into [CatalogModel] rows.
 *
 * Parsing is deliberately forgiving: a field a provider does not send stays
 * null rather than failing the whole listing, because a partial catalog still
 * beats typing model ids by hand. Whatever the endpoint omits, [ModelFacts]
 * fills in.
 */
internal object ModelCatalog {

  /** Anthropic `GET /v1/models`: `{"data":[{id, display_name, created_at}]}`. */
  fun anthropic(body: String): List<CatalogModel> =
    fromArray(body, "data") { o ->
      CatalogModel(
        modelId = o.optString("id"),
        displayName = o.optString("display_name").takeIf { it.isNotBlank() } ?: o.optString("id")
      )
    }

  /**
   * OpenAI `GET /v1/models`: `{"data":[{id, created, owned_by}]}`. Routers that
   * speak the same shape (OpenRouter and friends) additionally carry
   * `context_length` and `supported_parameters`, which we take when present.
   */
  fun openAiCompatible(body: String): List<CatalogModel> =
    fromArray(body, "data") { o ->
      val id = o.optString("id")
      val supports = o.optJSONArray("supported_parameters")
      CatalogModel(
        modelId = id,
        displayName = id.substringAfterLast('/'),
        contextWindow = o.optIntOrNull("context_length") ?: o.optIntOrNull("max_context_length"),
        maxOutputTokens = o.optIntOrNull("max_tokens"),
        tools = supports?.let { arr -> (0 until arr.length()).any { arr.optString(it) == "tools" } },
        images = o.optJSONObject("architecture")?.optJSONArray("input_modalities")
          ?.let { mods -> (0 until mods.length()).any { mods.optString(it) == "image" } }
      )
    }

  /**
   * Gemini `GET /v1beta/models`: `{"models":[{name, displayName,
   * inputTokenLimit, outputTokenLimit, supportedGenerationMethods}]}`. Only
   * models that answer `generateContent` are usable here; embeddings and
   * tuning-only entries are dropped.
   */
  fun gemini(body: String): List<CatalogModel> =
    fromArray(body, "models") { o ->
      val methods = o.optJSONArray("supportedGenerationMethods")
      val usable = methods == null ||
        (0 until methods.length()).any { methods.optString(it) == "generateContent" }
      if (!usable) return@fromArray null
      val id = o.optString("name").removePrefix("models/")
      CatalogModel(
        modelId = id,
        displayName = o.optString("displayName").takeIf { it.isNotBlank() } ?: id,
        contextWindow = o.optIntOrNull("inputTokenLimit"),
        maxOutputTokens = o.optIntOrNull("outputTokenLimit")
      )
    }

  private inline fun fromArray(
    body: String,
    key: String,
    read: (JSONObject) -> CatalogModel?
  ): List<CatalogModel> {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
    val array = root.optJSONArray(key) ?: return emptyList()
    val out = ArrayList<CatalogModel>(array.length())
    for (i in 0 until array.length()) {
      val o = array.optJSONObject(i) ?: continue
      val model = read(o) ?: continue
      if (model.modelId.isNotBlank()) out.add(model.withFilledLimits())
    }
    return out
  }

  /** A listing that omits a fact never overwrites what the app already knows. */
  private fun CatalogModel.withFilledLimits(): CatalogModel {
    val facts = ModelFacts.lookup(modelId) ?: return this
    return copy(
      contextWindow = contextWindow ?: facts.contextWindow,
      maxOutputTokens = maxOutputTokens ?: facts.maxOutputTokens,
      tools = tools ?: facts.tools,
      images = images ?: facts.images
    )
  }

  /** org.json answers a missing key with 0, which is never a real limit. */
  private fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name).takeIf { it > 0 } else null
}
