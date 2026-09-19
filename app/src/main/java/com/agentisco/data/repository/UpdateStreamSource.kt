package com.agentisco.data.repository

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.InputStream

/**
 * Supplies the APK bytes for one download attempt.
 *
 * The production implementation ([HttpUpdateStreamSource]) reads from the GitHub
 * release URL; tests inject an in-memory source so the retry/resume/verify state
 * machine can be exercised without touching the network.
 */
fun interface UpdateStreamSource {
    /** Opens the stream at [offset]; throws when the request fails. */
    fun open(url: String, offset: Long): UpdateStream
}

/**
 * One attempt's byte stream plus what the response told us about the asset.
 *
 * @param input the body, positioned at the requested offset.
 * @param totalSizeHint size of the *whole* asset implied by this response, or -1 when
 *        the server did not report one. For a 206 answer this is offset +
 *        Content-Length; for a 200 answer just Content-Length.
 * @param rangeIgnored true when a Range request was answered with the full body from
 *        offset 0 — the caller must restart instead of appending.
 */
class UpdateStream(
    val input: InputStream,
    val totalSizeHint: Long = -1L,
    val rangeIgnored: Boolean = false
) : Closeable {
    override fun close() {
        input.close()
    }
}

/** Reads the release APK over HTTP, resuming with a Range header when asked to. */
class HttpUpdateStreamSource(private val client: OkHttpClient) : UpdateStreamSource {

    override fun open(url: String, offset: Long): UpdateStream {
        val request = Request.Builder()
            .url(url)
            .apply { if (offset > 0L) header("Range", "bytes=$offset-") }
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw Exception("Download failed: ${e.message ?: "network error"}")
        }

        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw Exception("Download failed (HTTP $code)")
        }

        val body = response.body
        if (body == null) {
            response.close()
            throw Exception("Empty response body")
        }

        val contentLength = body.contentLength()
        // 206 = "Partial Content" (our Range was honoured). Any other success code
        // answering a Range request is the whole file, so it must be re-fetched
        // from zero or the bytes would be duplicated.
        val rangeHonoured = response.code == 206
        val totalSizeHint = when {
            contentLength <= 0L -> -1L
            rangeHonoured -> offset + contentLength
            else -> contentLength
        }

        return UpdateStream(
            input = body.byteStream(),
            totalSizeHint = totalSizeHint,
            rangeIgnored = offset > 0L && !rangeHonoured
        )
    }
}
