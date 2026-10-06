package com.awaki.data.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * How the agent's web tools are allowed to reach the internet.
 *
 * - `preferJina = true` (the default): read pages through Jina.ai's reader, which
 *   returns clean markdown instead of stripped markup and answers 20 requests a
 *   minute with no key at all. Search is only sent to Jina when a key is usable,
 *   because that endpoint refuses an anonymous call. Either way the direct route
 *   stays underneath as the fallback.
 * - `preferJina = false`: no third party ever sees the URL. The tools fetch and
 *   scrape on their own, which is how they worked before the reader existed.
 */
data class WebAccessSettings(
  val preferJina: Boolean = true
)

/**
 * Durable web-tool settings: the reader switch plus the user's own Jina.ai keys.
 *
 * The switch lives in `web_access.json` and the keys in `web_credentials.json`,
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
      runCatching {
        settingsCache = WebAccessSettings(preferJina = JSONObject(cfg).optBoolean("preferJina", true))
      }
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
      file.writeText(JSONObject().put("preferJina", settingsCache.preferJina).toString(2))
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
