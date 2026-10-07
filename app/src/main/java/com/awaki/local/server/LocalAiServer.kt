package com.awaki.local.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Where a client points an OpenAI-compatible provider at the model on this device. */
data class LocalAiEndpoint(val baseUrl: String, val token: String) {
  /** The full endpoint Awaki's provider records hold: base URL plus its key. */
  val apiKey: String get() = token
}

/**
 * The loopback transport in front of [LocalAiApi].
 *
 * It binds to 127.0.0.1 and only to 127.0.0.1: `anyLocalAddress` would put a model, its
 * prompts and its token spend on the device's Wi-Fi interface, where anything on the
 * network could reach it. Requests are answered one coroutine per connection, the way
 * any small HTTP server is, because the client here is Awaki's own OkHttp connection
 * and it expects nothing more exotic than `Content-Length` and `text/event-stream`.
 *
 * A client that goes away is not an error: the write fails, the API's stream callback
 * returns false, the decode stops, and the model's memory stays available for the next
 * request instead of being burned on an answer nobody is reading.
 */
class LocalAiServer(
  private val api: LocalAiApi,
  /**
   * How long a streamed request may stay silent before it commits to its headers, and how often it
   * is prodded after that. The shipped numbers are long enough to be invisible on a working phone
   * and far too long for a test to wait on, so the tests drive these two directly.
   */
  private val streamOpenAfterMs: Long = STREAM_OPEN_AFTER_MS,
  private val keepAliveMs: Long = KEEP_ALIVE_MS,
) {

  private val serving = AtomicBoolean(false)

  @Volatile
  private var socket: ServerSocket? = null

  @Volatile
  private var endpoint: LocalAiEndpoint? = null

  @Volatile
  private var scope: CoroutineScope? = null

  /** Where the server answers, or null while it is stopped. */
  val currentEndpoint: LocalAiEndpoint? get() = endpoint

  val isRunning: Boolean get() = serving.get()

  /**
   * Binds and starts serving, returning the address to configure a provider with.
   *
   * Idempotent: a second call hands back the endpoint that is already live rather than
   * binding a second socket the app then has no way to close.
   */
  @Synchronized
  fun start(): LocalAiEndpoint {
    endpoint?.let { return it }

    val server = bind()
    // A random token per run: nothing about it needs to survive a restart, since the
    // provider record is rewritten with whatever the server reports.
    val live = LocalAiEndpoint(
      baseUrl = "http://127.0.0.1:${server.localPort}/v1",
      token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
    )
    val run = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    socket = server
    endpoint = live
    serving.set(true)
    scope = run
    run.launch { acceptAll(server) }
    return live
  }

  /** Stops listening. The loaded model is left alone — that is the engine's decision. */
  @Synchronized
  fun stop() {
    serving.set(false)
    runCatching { socket?.close() }
    socket = null
    scope?.cancel()
    scope = null
    endpoint = null
  }

  private fun bind(): ServerSocket {
    val loopback = InetAddress.getByName(LOOPBACK)
    return runCatching { ServerSocket(PREFERRED_PORT, BACKLOG, loopback) }.getOrElse {
      try {
        ServerSocket(0, BACKLOG, loopback)
      } catch (e: IOException) {
        throw LocalServerException("Could not open a loopback port for the local model")
      }
    }
  }

  private fun acceptAll(server: ServerSocket) {
    // The loop ends when stop() closes the socket, which makes the blocked accept throw.
    while (serving.get()) {
      val client = try {
        server.accept()
      } catch (e: IOException) {
        // Closing the socket to stop the server lands here; anything else is the same.
        return
      }
      scope?.launch { serve(client) }
    }
  }

  private suspend fun serve(client: Socket) {
    client.use { socket ->
      val out = socket.getOutputStream()
      val writes = Any()
      try {
        socket.tcpNoDelay = true
        socket.soTimeout = READ_TIMEOUT_MS
        val request = readRequest(BufferedInputStream(socket.getInputStream())) ?: return
        if (!isAuthorized(request.authorization, endpoint?.token)) {
          writeJson(out, writes, 401, errorJson("The request does not carry this server's token", "authentication_error"))
          return
        }

        // A streamed answer that is slow to start has to become a live stream at some point, because
        // a connection showing no sign of life is indistinguishable from a broken one - to a read
        // timeout, to a proxy, and to the person watching a spinner. The API layer deliberately
        // writes nothing until it has run every check that could still answer with a plain error
        // body, so this decides on the clock: silence this long means the wait is a model being read
        // from storage or an agent prompt being prefilled, and from there the only news left is
        // progress. The wait is long because a too-long prompt must still arrive as a 400 - that
        // check happens inside the decode, after a load has already been paid for.
        //
        // Once a stream is open it is kept warm with comment frames, which no SSE parser reads as
        // data: a client from outside this app has a read timeout of its own, and this is the one
        // thing about a phone's inference speed that can be answered for it.
        val streamRequested = requestsEventStream(request.body)
        val streamStarted = AtomicBoolean(false)
        val keepWarm = AtomicBoolean(false)
        val ticker = if (streamRequested) scope?.launch {
          delay(streamOpenAfterMs)
          if (!streamStarted.compareAndSet(false, true)) return@launch
          writeStreamHeaders(out, writes)
          keepWarm.set(true)
          while (isActive) {
            delay(keepAliveMs)
            if (!keepWarm.get()) break
            // A failed write is the only way this thread learns the client hung up, and the decode
            // has to hear about it now rather than at the next token it would have written: the
            // orphaned turn otherwise finishes holding the one model slot, and the request the user
            // actually made next waits behind it.
            val written = runCatching { synchronized(writes) { out.write(KEEP_ALIVE_FRAME); out.flush() } }.isSuccess
            if (!written) {
              api.stopGeneration()
              break
            }
          }
        } else null
        try {
          val reply = api.handle(request.method, request.path, request.body) { payload ->
            keepWarm.set(false)
            if (streamStarted.compareAndSet(false, true)) writeStreamHeaders(out, writes)
            writeEvent(out, writes, payload)
          }
          when (reply) {
            // Headers already went out on the clock, so the only way left to report an error body is
            // as stream frames: JSON appended to an event stream is not readable by anything.
            is LocalAiApi.Reply.Body -> if (streamStarted.get()) {
              writeEvent(out, writes, reply.json)
              writeEvent(out, writes, "[DONE]")
            } else writeJson(out, writes, reply.status, reply.json)
            // Either every chunk went out or the client left before they did; in the rarer
            // case of a stream that produced nothing at all, the headers are still owed.
            is LocalAiApi.Reply.Streamed -> if (!streamStarted.get()) writeStreamHeaders(out, writes)
          }
          runCatching { synchronized(writes) { out.flush() } }
        } finally {
          ticker?.cancel()
        }
      } catch (e: RequestTooLarge) {
        runCatching { writeJson(out, writes, 413, errorJson("The request body is larger than this server accepts", "invalid_request_error")) }
      } catch (e: InterruptedIOException) {
        // Stopped or timed out mid-request: the client is gone, or the server is.
      } catch (e: IOException) {
        // A hung-up connection carries no information worth reporting.
      } catch (e: LocalServerException) {
        runCatching { writeJson(out, writes, 400, errorJson(e.message ?: "The request could not be read", "invalid_request_error")) }
      }
    }
  }

  private fun isAuthorized(header: String?, token: String?): Boolean {
    if (token == null) return false
    val expected = "Bearer $token".toByteArray(StandardCharsets.UTF_8)
    // Constant-time: this token is the only thing between another app on the device and
    // the user's model, so comparing it with `==` would leak it a character at a time.
    return header != null && MessageDigest.isEqual(header.toByteArray(StandardCharsets.UTF_8), expected)
  }

  private fun writeStreamHeaders(out: OutputStream, writes: Any) {
    synchronized(writes) {
      out.write(
        (
          "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "X-Accel-Buffering: no\r\n" +
            "Connection: close\r\n" +
            "\r\n"
          ).toByteArray(StandardCharsets.US_ASCII)
      )
      out.flush()
    }
  }

  /** One SSE frame; false means the client hung up while it was being written. */
  private fun writeEvent(out: OutputStream, writes: Any, payload: String): Boolean = try {
    synchronized(writes) {
      out.write("data: $payload\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
      out.flush()
    }
    true
  } catch (e: IOException) {
    false
  }

  private fun writeJson(out: OutputStream, writes: Any, status: Int, json: String) {
    val bytes = json.toByteArray(StandardCharsets.UTF_8)
    val head = "HTTP/1.1 $status ${reasonPhrase(status)}\r\n" +
      "Content-Type: application/json; charset=utf-8\r\n" +
      "Content-Length: ${bytes.size}\r\n" +
      "Connection: close\r\n" +
      "\r\n"
    synchronized(writes) {
      out.write(head.toByteArray(StandardCharsets.US_ASCII))
      out.write(bytes)
      out.flush()
    }
  }

  private fun reasonPhrase(status: Int): String = when (status) {
    200 -> "OK"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    413 -> "Payload Too Large"
    500 -> "Internal Server Error"
    503 -> "Service Unavailable"
    else -> "Status"
  }

  /** One request off the wire, or null when the peer closed the connection before sending it. */
  private fun readRequest(input: InputStream): ParsedRequest? {
    val requestLine = readLine(input) ?: return null
    val parts = requestLine.split(' ')
    if (parts.size < 2) throw LocalServerException("Malformed request line")

    val headers = mutableMapOf<String, String>()
    while (true) {
      val line = readLine(input) ?: break
      if (line.isEmpty()) break
      val colon = line.indexOf(':')
      if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
    }

    return ParsedRequest(
      method = parts[0].uppercase(),
      path = originForm(parts[1]),
      body = readBody(input, headers),
      authorization = headers["authorization"]
    )
  }

  /**
   * The body, which is either counted up front or arriving in chunks — OkHttp sends the
   * first and anything hand-written with curl may send the second, and a chat request
   * that loses half its transcript is worse than one that fails.
   */
  private fun readBody(input: InputStream, headers: Map<String, String>): String {
    if (headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true) {
      return String(readChunked(input), StandardCharsets.UTF_8)
    }
    val declared = headers["content-length"]?.toLongOrNull() ?: 0L
    if (declared > MAX_BODY_BYTES) throw RequestTooLarge()
    val bytes = ByteArray(declared.toInt())
    var read = 0
    while (read < bytes.size) {
      val count = input.read(bytes, read, bytes.size - read)
      if (count <= 0) break
      read += count
    }
    return String(bytes, 0, read, StandardCharsets.UTF_8)
  }

  private fun readChunked(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    while (true) {
      val sizeLine = readLine(input)?.trim()?.substringBefore(';') ?: break
      val size = sizeLine.toIntOrNull(16) ?: throw LocalServerException("Malformed chunked body")
      if (size == 0) {
        // Trailing headers, if the sender chose to add some, end at a blank line.
        while (true) {
          val trailer = readLine(input) ?: break
          if (trailer.isEmpty()) break
        }
        break
      }
      if (out.size() + size > MAX_BODY_BYTES) throw RequestTooLarge()
      val chunk = ByteArray(size)
      var read = 0
      while (read < size) {
        val count = input.read(chunk, read, size - read)
        if (count <= 0) throw LocalServerException("The request body ended mid-chunk")
        read += count
      }
      out.write(chunk)
      readLine(input) // the CRLF that closes a chunk
    }
    return out.toByteArray()
  }

  /** One CRLF-terminated header line, ASCII, capped — a peer that streams forever is not a client. */
  private fun readLine(input: InputStream): String? {
    val line = StringBuilder()
    while (true) {
      val byte = input.read()
      if (byte == -1) return if (line.isEmpty()) null else line.toString()
      if (byte == '\n'.code) {
        if (line.isNotEmpty() && line.last() == '\r') line.deleteCharAt(line.length - 1)
        return line.toString()
      }
      if (line.length > MAX_HEADER_BYTES) throw LocalServerException("Request headers are too large")
      line.append(byte.toChar())
    }
  }

  /** A proxy may send the whole URL where a normal client sends the path; the API wants the path. */
  private fun originForm(target: String): String {
    if (!target.startsWith("http://") && !target.startsWith("https://")) return target
    val afterScheme = target.indexOf("://") + 3
    val slash = target.indexOf('/', afterScheme)
    return if (slash < 0) "/" else target.substring(slash)
  }

  private class ParsedRequest(
    val method: String,
    val path: String,
    val body: String,
    val authorization: String?
  )

  private companion object {
    const val LOOPBACK = "127.0.0.1"
    const val BACKLOG = 4

    /**
     * Fixed first so a device that restarts the app keeps the same base URL, and
     * ephemeral after that so a port someone else took is not an outage.
     */
    const val PREFERRED_PORT = 43128
    const val READ_TIMEOUT_MS = 60_000

    /**
     * How long the server stays silent on a streamed request before it commits to
     * `text/event-stream`.
     *
     * It has to clear the two things a request can legitimately wait on before its first token: a
     * model being read into memory and an agent prompt of a couple of thousand tokens being
     * prefilled. Shorter than that and a prompt the context cannot hold would stop arriving as the
     * 400 that tells the user which number to change; longer, and a client with a 30-second read
     * timeout gives up on a phone that was still working.
     */
    const val STREAM_OPEN_AFTER_MS = 20_000L

    /**
     * How long a stream may sit with nothing to say before it is prodded.
     *
     * A comment frame is what SSE has for this: every parser skips a line beginning with a colon,
     * so a keep-alive reaches nobody's deltas.
     */
    const val KEEP_ALIVE_MS = 5_000L
    val KEEP_ALIVE_FRAME = ": waiting-for-model\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)

    /** Headers are small, and a request that sends more than this is not a client we speak to. */
    const val MAX_HEADER_BYTES = 32 * 1024

    /** A long agent transcript with tool results is comfortably under this. */
    const val MAX_BODY_BYTES = 16L * 1024 * 1024
  }
}

/** A server-side failure worth a status code rather than a stack trace. */
class LocalServerException(message: String) : Exception(message)

/** A body over the cap, noticed before it is read rather than after it fills memory. */
private class RequestTooLarge : Exception()
