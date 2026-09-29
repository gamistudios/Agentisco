package com.agentisco.data.repository

import com.agentisco.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * The update document the Update API publishes for one channel.
 *
 * [versionCode] is the number the whole comparison rests on; [assetSize] and
 * [assetDigest] are what the finished download is verified against, and
 * [downloadUrl] is pinned to the release tag so a resumed transfer can never be
 * repointed at a different artifact.
 */
data class UpdateRelease(
    val tagName: String,
    val versionName: String,
    val versionCode: Long,
    val downloadUrl: String,
    val releaseNotes: String,
    val assetName: String,
    val assetSize: Long,
    val assetDigest: String?
)

/** Supplies the newest release of this build's channel. */
fun interface UpdateReleaseSource {
    /** Returns the channel's current update; throws when the request fails. */
    fun fetchLatestUpdate(): UpdateRelease
}

/**
 * Reads update metadata from the Agentisco Update API.
 *
 * The service is the only public face of the artifact source: the app never names
 * a repository, and no upstream address ever reaches it. Debug builds follow the
 * debug channel, release builds the release channel.
 */
class HttpUpdateReleaseSource(
    private val client: OkHttpClient,
    private val baseUrl: String = BuildConfig.UPDATE_API_BASE_URL.trimEnd('/'),
    private val channel: String = if (BuildConfig.DEBUG) "debug" else "release"
) : UpdateReleaseSource {

    override fun fetchLatestUpdate(): UpdateRelease {
        val request = Request.Builder()
            .url("$baseUrl/v1/updates/$channel/latest")
            .header("Accept", "application/json")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw Exception("Update check failed: ${e.message ?: "network error"}")
        }

        response.use {
            if (!it.isSuccessful) throw Exception("Update check failed (HTTP ${it.code})")
            val body = it.body?.string() ?: throw Exception("Empty response")
            return parseUpdateRelease(JSONObject(body))
        }
    }
}

/**
 * Turns the service's document into an [UpdateRelease].
 *
 * `versionCode` is the service's own number, computed with the same rule the app
 * applies to its version name; [fallback] covers a deployment that omits it.
 */
fun parseUpdateRelease(json: JSONObject, fallback: (String) -> Long = { 0L }): UpdateRelease {
    val tagName = json.optString("tagName", "")
    val versionName = json.optString("versionName").ifBlank { tagName }
    val downloadUrl = json.optString("downloadUrl", "")
    if (downloadUrl.isBlank()) throw Exception("Update API gave no download URL")
    val reportedCode = json.optLong("versionCode", 0L)
    return UpdateRelease(
        tagName = tagName,
        versionName = versionName,
        versionCode = if (reportedCode > 0L) reportedCode else fallback(tagName),
        downloadUrl = downloadUrl,
        releaseNotes = json.optString("releaseNotes", ""),
        assetName = json.optString("apkName", ""),
        assetSize = json.optLong("size", 0L),
        assetDigest = UpdateDownloadVerifier.normalizeDigest(json.optString("sha256", ""))
    )
}
