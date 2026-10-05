package com.awaki.local.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
class LocalAiServer(private val api: LocalAiApi) {

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
      try {
        socket.tcpNoDelay = true
        socket.soTimeout = READ_TIMEOUT_MS
        val request = readRequest(BufferedInputStream(socket.getInputStream())) ?: return
        if (!isAuthorized(request.authorization, endpoint?.token)) {
          writeJson(out, 401, errorJson("The request does not carry this server's token", "authentication_error"))
          return
        }
        var streamStarted = false
        val reply = api.handle(request.method, request.path, request.body) { payload ->
          if (!streamStarted) {
            writeStreamHeaders(out)
            streamStarted = true
          }
          writeEvent(out, payload)
        }
        when (reply) {
          is LocalAiApi.Reply.Body -> writeJson(out, reply.status, reply.json)
          // Either every chunk went out or the client left before they did; in the rarer
          // case of a stream that produced nothing at all, the headers are still owed.
          is LocalAiApi.Reply.Streamed -> if (!streamStarted) writeStreamHeaders(out)
        }
        runCatching { out.flush() }
      } catch (e: RequestTooLarge) {
        runCatching { writeJson(out, 413, errorJson("The request body is larger than this server accepts", "invalid_request_error")) }
      } catch (e: InterruptedIOException) {
        // Stopped or timed out mid-request: the client is gone, or the server is.
      } catch (e: IOException) {
        // A hung-up connection carries no information worth reporting.
      } catch (e: LocalServerException) {
        runCatching { writeJson(out, 400, errorJson(e.message ?: "The request could not be read", "invalid_request_error")) }
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

  private fun writeStreamHeaders(out: OutputStream) {
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

  /** One SSE frame; false means the client hung up while it was being written. */
  private fun writeEvent(out: OutputStream, payload: String): Boolean = try {
    out.write("data: $payload\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
    out.flush()
    true
  } catch (e: IOException) {
    false
  }

  private fun writeJson(out: OutputStream, status: Int, json: String) {
    val bytes = json.toByteArray(StandardCharsets.UTF_8)
    val head = "HTTP/1.1 $status ${reasonPhrase(status)}\r\n" +
      "Content-Type: application/json; charset=utf-8\r\n" +
      "Content-Length: ${bytes.size}\r\n" +
      "Connection: close\r\n" +
      "\r\n"
    out.write(head.toByteArray(StandardCharsets.US_ASCII))
    out.write(bytes)
    out.flush()
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
