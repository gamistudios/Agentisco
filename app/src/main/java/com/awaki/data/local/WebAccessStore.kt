package com.awaki.data.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where `web_search` asks first. The others answer only if this one cannot (see [WebAccessSettings.fallback]). */
enum class SearchProvider(val label: String) {
  /** Scrapes DuckDuckGo's HTML results. Keyless and fresh: the live index. */
  DuckDuckGo("DuckDuckGo"),

  /** Parallel's free hosted search MCP. Keyless, rate limited, ready-made excerpts. The default. */
  Parallel("Parallel (free)"),

  /** s.jina.ai. Refuses an anonymous call, so it only runs when a Jina key is in hand. */
  Jina("Jina.ai (key)");

  companion object {
    fun fromName(name: String?): SearchProvider? = values().firstOrNull { it.name == name }
  }
}

/** Where `web_fetch` reads a page first. */
enum class FetchProvider(val label: String) {
  /** r.jina.ai: renders JavaScript pages and returns markdown. Free for 20 pages a minute. */
  Jina("Jina.ai"),

  /** Parallel's free hosted extractor: token-efficient markdown, no key. The default. */
  Parallel("Parallel (free)"),

  /** The device fetches the URL itself. No third party sees it, but JavaScript pages come back empty. */
  Direct("Direct");

  companion object {
    fun fromName(name: String?): FetchProvider? = values().firstOrNull { it.name == name }
  }
}

/**
 * How the agent's web tools are allowed to reach the internet.
 *
 * - `searchProvider` (default Parallel): Parallel's free MCP answers keyless with
 *   excerpts already in the result. DuckDuckGo is the live index — keyless, but
 *   scraped and occasionally challenged. Jina's search is only worth choosing with
 *   a key, because that endpoint refuses an anonymous call.
 * - `fetchProvider` (default Parallel): Parallel's extractor is keyless markdown.
 *   Jina's reader runs the page's JavaScript and answers 20 requests a minute with
 *   no key at all, which is why it stays one tap away rather than being removed.
 * - `fallback` (default on): when the chosen provider cannot answer, the others are tried
 *   in a fixed order. Off means the chosen provider is the only third party ever asked;
 *   a fetch still ends with the device's own direct request, which only the site sees.
 */
data class WebAccessSettings(
  val searchProvider: SearchProvider = SearchProvider.Parallel,
  val fetchProvider: FetchProvider = FetchProvider.Parallel,
  val fallback: Boolean = true
)

/**
 * Durable web-tool settings: the chosen providers plus the user's own Jina.ai keys.
 *
 * The choices live in `web_access.json` and the keys in `web_credentials.json`,
 * split for the same reason [ProviderConfigStore] splits its own two files — a
 * secret must never be read out beside ordinary configuration, and the config
 * file is the one a user would think to share when asking for help.
 *
 * Degrades to in-memory operation when [context] is null (unit tests / previews).
 */
class WebAccessStore(private val context: Context? = null) {

  private val dir: File? = context?.getDir("awaki", Context.MODE_PRIVATE)
  private val configFile: File? get() = dir?.resolve("web_access.json")
  private val credentialsFile: File? get() = dir?.resolve("web_credentials.json")

  private var settingsCache: WebAccessSettings = WebAccessSettings()
  private var keysCache: List<String> = emptyList()

  init {
    load()
  }

  private fun load() {
    val cfg = configFile?.takeIf { it.isFile }?.readText()
    if (cfg != null) {
      runCatching { settingsCache = readSettings(JSONObject(cfg)) }
    }
    val creds = credentialsFile?.takeIf { it.isFile }?.readText()
    if (creds != null) {
      runCatching {
        keysCache = parseKeyList(JSONObject(creds).optJSONArray("jinaKeys"))
      }
    }
  }

  private fun persistConfig() {
    val file = configFile ?: return
    runCatching {
      file.parentFile?.mkdirs()
      file.writeText(
        JSONObject()
          .put("searchProvider", settingsCache.searchProvider.name)
          .put("fetchProvider", settingsCache.fetchProvider.name)
          .put("fallback", settingsCache.fallback)
          .toString(2)
      )
    }
  }

  private fun persistKeys() {
    val file = credentialsFile ?: return
    runCatching {
      file.parentFile?.mkdirs()
      file.writeText(JSONObject().put("jinaKeys", JSONArray(keysCache)).toString())
    }
  }

  // ---- Queries ----

  @Synchronized
  fun get(): WebAccessSettings = settingsCache

  /** The user's own keys, in the order they were added. Never shown in full. */
  @Synchronized
  fun keys(): List<String> = keysCache

  // ---- Mutations ----

  @Synchronized
  fun update(transform: (WebAccessSettings) -> WebAccessSettings): WebAccessSettings {
    settingsCache = transform(settingsCache)
    persistConfig()
    return settingsCache
  }

  /**
   * Stores every distinct key in [raw] — a pasted block of several keys is one
   * gesture, not several. Returns the number added, so the UI can say what landed
   * rather than assuming the paste worked.
   */
  @Synchronized
  fun addKeys(raw: String): Int {
    val incoming = parseKeyList(raw)
    if (incoming.isEmpty()) return 0
    val added = incoming.filterNot { it in keysCache }
    if (added.isEmpty()) return 0
    keysCache = keysCache + added
    persistKeys()
    return added.size
  }

  /** Deletes the key whose [fingerprint] the settings screen is showing. */
  @Synchronized
  fun removeKey(fingerprint: String): Boolean {
    val victim = keysCache.firstOrNull { fingerprintOf(it) == fingerprint } ?: return false
    keysCache = keysCache - victim
    persistKeys()
    return true
  }

  @Synchronized
  fun setKeys(keys: List<String>) {
    keysCache = keys.flatMap { parseKeyList(it) }.distinct()
    persistKeys()
  }

  companion object {
    /**
     * Reads a stored config. Files written before providers existed carry only
     * `preferJina`: `false` meant "never hand a URL to a third party", which is a direct
     * fetch with no fallback; `true` meant third-party reading was fine, which is simply
     * whatever the shipped defaults are now.
     */
    internal fun readSettings(json: JSONObject): WebAccessSettings {
      val legacyDirect = json.has("preferJina") && !json.optBoolean("preferJina", true)
      val defaults = WebAccessSettings()
      return WebAccessSettings(
        searchProvider = SearchProvider.fromName(json.optString("searchProvider")) ?: defaults.searchProvider,
        fetchProvider = FetchProvider.fromName(json.optString("fetchProvider"))
          ?: if (legacyDirect) FetchProvider.Direct else defaults.fetchProvider,
        fallback = json.optBoolean("fallback", !legacyDirect)
      )
    }

    /**
     * A key is an opaque token: trimmed, stripped of the `Bearer ` a user copied
     * from a curl example, and never split on internal characters. Length is not
     * validated — Jina has shipped keys of more than one shape, and a rejected
     * working key is worse than an accepted bad one that simply 401s.
     */
    fun normalizeKey(raw: String): String {
      val trimmed = raw.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
      return if (trimmed.any { it.isWhitespace() }) "" else trimmed
    }

    /** Comma- or newline-separated input, as pasted from a key dashboard. */
    fun parseKeyList(raw: String): List<String> =
      raw.split(',', '\n').mapNotNull { normalizeKey(it).takeIf { key -> key.isNotEmpty() } }.distinct()

    fun parseKeyList(array: JSONArray?): List<String> {
      if (array == null) return emptyList()
      return (0 until array.length())
        .mapNotNull { index -> normalizeKey(array.optString(index)).takeIf { key -> key.isNotEmpty() } }
        .distinct()
    }

    /**
     * The handle the settings screen shows instead of the secret. The head is
     * Jina's own prefix, so it says what kind of key it is; the tail tells two
     * pasted keys apart. Nothing in between is ever rendered.
     */
    fun fingerprintOf(key: String): String = when {
      key.length <= 10 -> key.take(2) + "…" + key.takeLast(2)
      else -> key.take(6) + "…" + key.takeLast(4)
    }
  }
}
