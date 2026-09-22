package com.agentisco.data.repository

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Supplies the parsed JSON of the latest GitHub release for the update check.
 *
 * The production implementation ([HttpUpdateReleaseSource]) calls the GitHub
 * Releases API; tests inject a source that serves crafted JSON so the
 * version-compare and stale-download cleanup can be exercised without the network.
 */
fun interface UpdateReleaseSource {
    /** Returns the "latest release" JSON; throws when the request fails. */
    fun fetchLatestRelease(): JSONObject
}

/** Reads the latest release metadata from the GitHub Releases API. */
class HttpUpdateReleaseSource(private val client: OkHttpClient) : UpdateReleaseSource {

    override fun fetchLatestRelease(): JSONObject {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw Exception("Update check failed: ${e.message ?: "network error"}")
        }

        response.use {
            if (!it.isSuccessful) throw Exception("Update check failed (HTTP ${it.code})")
            val body = it.body?.string() ?: throw Exception("Empty response")
            return JSONObject(body)
        }
    }

    companion object {
        private const val RELEASES_URL =
            "https://api.github.com/repos/gamistudios/Agentisco/releases/latest"
    }
}
