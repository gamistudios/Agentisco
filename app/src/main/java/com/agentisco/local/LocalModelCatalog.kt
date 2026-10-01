package com.agentisco.local

import com.agentisco.local.model.LocalModel
import com.agentisco.local.model.LocalModelFormat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * What a model's host reports about the asset on the other end of its URL.
 *
 * Kept separate from [LocalModel] because the model record is configuration the
 * user may have edited, while these numbers are the remote source's own claim —
 * the two disagreeing is exactly what an update looks like.
 */
data class RemoteAssetInfo(
  val sizeBytes: Long,
  val checksum: String?,
  /** Version stamp of the source revision, used to detect a newer asset. */
  val version: String = ""
)

/**
 * Reads asset metadata from Hugging Face's model API.
 *
 * The size and SHA-256 a repository publishes for each file are what the
 * installer verifies the download against, so the app never has to guess how big
 * a model is or trust that the bytes it received are the bytes the author
 * uploaded. Anything the API withholds stays unknown, and a checksum-less
 * download still gets the format and size checks.
 */
class HuggingFaceAssetSource(httpClient: OkHttpClient? = null) {

  private val http = (httpClient ?: OkHttpClient()).newBuilder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()

  /**
   * Resolves a `…/resolve/<rev>/<file>` asset URL to its published size and
   * digest, or null when the URL is not a Hugging Face resolve link or the
   * request fails — a missing entry is never treated as a reason to block a
   * download the user explicitly asked for.
   */
  fun lookup(downloadUrl: String): RemoteAssetInfo? {
    val parsed = parseResolveUrl(downloadUrl) ?: return null
    val (repo, revision, path) = parsed

    val api = "https://huggingface.co/api/models/$repo/tree/$revision"
    val request = Request.Builder().url(api).header("Accept", "application/json").build()
    return runCatching {
      http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        val body = response.body?.string() ?: return null
        // The tree endpoint answers with a bare array; gateways that wrap it
        // still give us the same entries under "data".
        val entries = runCatching { JSONArray(body) }
          .getOrNull() ?: JSONObject(body).optJSONArray("data") ?: return null
        val file = findFile(entries, path) ?: return null
        val lfs = file.optJSONObject("lfs")
        val size = lfs?.optLong("size", 0L)?.takeIf { it > 0L } ?: file.optLong("size", 0L)
        val oid = lfs?.optString("oid", "").orEmpty()
          .takeIf { it.length == 64 && it.all { ch -> ch in "0123456789abcdefABCDEF" } }
          ?.lowercase()
        // The tree entry carries no per-file revision of its own; `oid` is the git
        // blob id, which changes as soon as the published bytes do.
        RemoteAssetInfo(sizeBytes = size, checksum = oid, version = file.optString("oid", ""))
      }
    }.getOrNull()
  }

  private fun findFile(entries: JSONArray, path: String): JSONObject? {
    for (i in 0 until entries.length()) {
      val o = entries.optJSONObject(i) ?: continue
      if (o.optString("path") == path) return o
      val nested = o.optJSONArray("entries")
      if (nested != null) findFile(nested, path)?.let { return it }
    }
    return null
  }

  companion object {
    /**
     * Splits a `https://huggingface.co/<owner>/<repo>/resolve/<revision>/<path>`
     * asset link into its parts, or null for anything else.
     *
     * The host is compared exactly, not by suffix: this decides whether the app
     * asks *this* API for the checksum it will verify a download against, and a
     * URL like `https://someone-elses.host/huggingface.co/…` must not answer for
     * a file that Hugging Face never published.
     */
    internal fun parseResolveUrl(url: String): Triple<String, String, String>? {
      val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
      val host = uri.host?.lowercase() ?: return null
      if (host != "huggingface.co" && host != "www.huggingface.co") return null
      if (!"https".equals(uri.scheme, ignoreCase = true)) return null

      // The raw path keeps a revision like refs%2Fpr%2F7 encoded, which is how the
      // API wants it back; a decoded one would read as three separate segments.
      val segments = (uri.rawPath ?: return null).split('/').filter { it.isNotBlank() }
      val resolveAt = segments.indexOf("resolve")
      if (resolveAt < 2 || segments.size < resolveAt + 3) return null
      val revision = segments[resolveAt + 1]
      val path = segments.drop(resolveAt + 2).joinToString("/")
      if (revision.isBlank() || path.isBlank()) return null
      return Triple(segments.take(resolveAt).joinToString("/"), revision, path)
    }
  }
}

/**
 * The models Agentisco ships knowledge of — the only entries a user gets without
 * typing a URL. Adding support for another model means adding a row here, plus
 * whatever the runtime needs to actually run it.
 */
object LocalModelCatalog {

  /**
   * Liquid AI's LFM2.5-230M at Q4_0: the smallest model Agentisco officially
   * supports, chosen because llama.cpp implements its `lfm2` graph natively and
   * because Q4_0 is the quantization the vendor recommends for phones.
   *
   * The size and digest below are this repository's published values, kept as an
   * offline fallback; [refresh] replaces them with whatever the source reports
   * now, so the number shown to the user is never a stale guess.
   */
  val lfm2_5_230m_q4_0 = LocalModel(
    id = "lfm2.5-230m-q4_0",
    name = "LFM2.5-230M",
    sourceUrl = "https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF",
    downloadUrl = "https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF/resolve/main/LFM2.5-230M-Q4_0.gguf",
    format = LocalModelFormat.GGUF,
    quantization = "Q4_0",
    description = "Small local language model for offline AI.",
    sizeBytes = 149_080_928L,
    checksum = "430fbec5b1b355e9bb12cd0638c9f2a8f21fedd6eafb4103e42c7e88887daa73",
    version = "main",
    builtIn = true
  )

  val builtIn: List<LocalModel> = listOf(lfm2_5_230m_q4_0)

  /** Applies a source's freshly read numbers to a catalog entry. */
  fun refresh(model: LocalModel, info: RemoteAssetInfo?): LocalModel {
    if (info == null) return model
    return model.copy(
      sizeBytes = if (info.sizeBytes > 0L) info.sizeBytes else model.sizeBytes,
      checksum = info.checksum ?: model.checksum,
      version = info.version.takeIf { it.isNotBlank() } ?: model.version
    )
  }
}
