package com.awaki.data.repository

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Supplies the APK bytes for one download attempt.
 *
 * The production implementation ([HttpUpdateStreamSource]) reads from the download
 * URL the Update API gives; tests inject an in-memory source so the
 * retry/resume/verify state machine can be exercised without touching the network.
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
class HttpUpdateStreamSource(client: OkHttpClient) : UpdateStreamSource {

    /**
     * A bulk transfer on a phone connection goes quiet often enough that OkHttp's
     * ten-second read budget cuts it off mid-download, so the shared client is
     * re-cut with one that only gives up once a whole minute passes without a
     * single byte. Per-read, not per-call: a slow-but-live download keeps running.
     */
    private val client = client.newBuilder()
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

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

    private companion object {
        const val READ_TIMEOUT_SECONDS = 60L
    }
}
