package com.awaki.agent.web

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Both web tools give the network the same budget. */
const val WEB_TIMEOUT_SECONDS = 30L

/** Reads at most this many bytes so a huge download cannot exhaust the app. */
const val MAX_BYTES_TO_READ = 2_000_000L

/** One HTTP round trip, already size-limited. */
class WebResponse(
  val status: Int,
  val contentType: String,
  val finalUrl: String,
  val text: String,
  val truncated: Boolean,
  val headers: Headers
) {
  fun header(name: String): String? = headers[name]
}

/**
 * One shared client keeps a single connection pool; call timeouts are per call.
 *
 * The network interceptor is not decoration: it is what stops a `Bearer` key from
 * riding a redirect to somebody else's host. Application interceptors cannot see a
 * redirected request, so the guard has to sit at the network layer, where every hop
 * is re-built from the URL that is actually about to be dialed.
 */
private val sharedClient by lazy {
  OkHttpClient.Builder()
    .connectTimeout(WEB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(WEB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .addNetworkInterceptor { chain ->
      val request = chain.request()
      val guarded = if (request.header("Authorization") != null && !isJinaHost(request.url.host)) {
        request.newBuilder().removeHeader("Authorization").build()
      } else {
        request
      }
      chain.proceed(guarded)
    }
    .build()
}

/** True when [host] is one of Jina.ai's own endpoints. */
internal fun isJinaHost(host: String): Boolean =
  host == "jina.ai" || host.endsWith(".jina.ai")

/** A GET that answers at most once: null means the transport itself failed. */
internal suspend fun webGet(
  client: OkHttpClient?,
  request: Request,
  maxChars: Int,
  timeoutSeconds: Long = WEB_TIMEOUT_SECONDS
): WebResponse? {
  val http = (client ?: sharedClient).newBuilder()
    .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .build()
  val call = http.newCall(request)
  return suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(
      object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          if (continuation.isActive) continuation.resume(null)
        }

        override fun onResponse(call: Call, response: Response) {
          val outcome = runCatching { response.use { readBody(it, maxChars) } }.getOrNull()
          if (continuation.isActive) continuation.resume(outcome)
        }
      }
    )
  }
}

private fun readBody(response: Response, maxChars: Int): WebResponse {
  val contentType = response.header("Content-Type").orEmpty().lowercase()
  val url = response.request.url.toString()
  if (contentType.contains("octet-stream") || contentType.contains("image/") ||
    contentType.contains("video/") || contentType.contains("audio/") ||
    contentType.contains("application/pdf") || contentType.contains("zip")
  ) {
    return WebResponse(
      status = response.code,
      contentType = contentType,
      finalUrl = url,
      text = "(refused: this is a ${contentType.substringBefore(';')} file, not text. Download it with run_command if the project needs it.)",
      truncated = false,
      headers = response.headers
    )
  }
  val input = response.body?.byteStream()
    ?: return WebResponse(response.code, contentType, url, "(empty response body)", false, response.headers)
  val builder = StringBuilder()
  var bytesRead = 0L
  var truncated = false
  input.use { stream ->
    val buffer = ByteArray(8_192)
    while (true) {
      val read = stream.read(buffer)
      if (read < 0) break
      bytesRead += read
      if (bytesRead > MAX_BYTES_TO_READ || builder.length > maxChars * 2) {
        truncated = true
        break
      }
      builder.append(String(buffer, 0, read, Charsets.UTF_8))
    }
  }
  return WebResponse(response.code, contentType, url, builder.toString(), truncated, response.headers)
}

/**
 * What Jina says about its own budget. The headers are authoritative and the
 * free tier is shared per IP, so a device that only counts its own requests
 * would keep knocking on a door the server already closed.
 */
internal class RateSignal(
  val limit: Int?,
  val remaining: Int?,
  val windowSeconds: Long?,
  val retryAfterSeconds: Long?
) {
  companion object {
    private const val WINDOW_DEFAULT_SECONDS = 60L

    fun of(headers: Headers): RateSignal {
      val limit = Regex("\\d+").find(headers["x-ratelimit-limit"] ?: "")?.value?.toIntOrNull()
      val remaining = (headers["x-ratelimit-remaining"] ?: "").trim().toIntOrNull()
      val window = Regex("w=(\\d+)").find(headers["x-ratelimit-limit"] ?: "")?.groupValues?.get(1)?.toLongOrNull()
      val retryAfter = (headers["Retry-After"] ?: "").trim().toLongOrNull()
      return RateSignal(
        limit = limit,
        remaining = remaining,
        windowSeconds = window ?: if (limit != null) WINDOW_DEFAULT_SECONDS else null,
        retryAfterSeconds = retryAfter
      )
    }
  }
}
