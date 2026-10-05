package com.awaki.local

import com.awaki.local.py.PythonModelClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/** One recorded call the fake Python server saw, in the shape a test asserts on. */
data class PythonCall(val method: String, val path: String, val body: String, val token: String?)

/**
 * A stand-in for `serve.py`, on a real socket.
 *
 * The client under test is an HTTP client, and the interesting defects are on the wire: a
 * stream parsed one event at a time, an error that arrives after the headers are gone, an abort
 * that has to leave a decode already in flight. A transport seam would test the parsing against
 * a fake that agrees with whatever the code says, so this speaks the same protocol the guest
 * does and both halves run for real.
 */
class FakePythonServer(
  private val reply: (PythonCall) -> PythonResponse = { PythonResponse.json("{}") }
) {
  val calls = CopyOnWriteArrayList<PythonCall>()

  private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
  val port: Int = socket.localPort
  private var closed = false

  fun start() {
    Thread {
      while (!closed) {
        val connection = try {
          socket.accept()
        } catch (e: IOException) {
          break
        }
        // One thread per connection: a client that aborts mid-stream holds one connection open
        // while it opens another, and a single-threaded server would deadlock on itself.
        Thread { runCatching { handle(connection) } }.apply { isDaemon = true }.start()
      }
    }.apply { isDaemon = true }
      .start()
  }

  fun stop() {
    closed = true
    runCatching { socket.close() }
  }

  /** A client aimed at this server, with the token it accepts. */
  fun client(token: String = "test-token") = PythonModelClient(port, token)

  private fun handle(connection: Socket) {
    connection.use { open ->
      val input = open.getInputStream()
      // Headers are read a byte at a time off the socket itself. A BufferedReader over the same
      // stream would take the request body into its own buffer with them, and the body is read
      // from the raw stream below — that is a server waiting for bytes a reader already swallowed.
      val header = readHeaderBlock(input) ?: return
      val parts = header.lines().first().split(" ")
      if (parts.size < 2) return
      var length = 0
      var token: String? = null
      for (line in header.lines().drop(1)) {
        val lower = line.lowercase()
        if (lower.startsWith("content-length:")) {
          length = line.substringAfter(":").trim().toIntOrNull() ?: 0
        }
        if (lower.startsWith("authorization:")) token = line.substringAfter(":").trim()
      }
      val body = if (length > 0) readExactly(input, length) else ""
      val call = PythonCall(parts[0], parts[1], body, token)
      calls += call
      reply(call).writeTo(open.getOutputStream())
    }
  }

  /** The request line and headers, up to the blank line, without reading past it. */
  private fun readHeaderBlock(input: InputStream): String? {
    val bytes = ByteArrayOutputStream()
    var ended = 0
    while (ended < HEADER_END.size) {
      val next = input.read()
      if (next < 0) break
      bytes.write(next)
      ended = if (next == HEADER_END[ended]) ended + 1 else if (next == CR) 1 else 0
    }
    if (bytes.size() == 0) return null
    return String(bytes.toByteArray(), StandardCharsets.UTF_8)
  }

  private companion object {
    const val CR = 13
    /** The blank line that ends an HTTP request head, as the byte codes to watch for. */
    val HEADER_END = intArrayOf(CR, 10, CR, 10)
  }

  private fun readExactly(stream: InputStream, length: Int): String {
    val bytes = ByteArray(length)
    var read = 0
    while (read < length) {
      val count = stream.read(bytes, read, length - read)
      if (count < 0) break
      read += count
    }
    return String(bytes, 0, read, StandardCharsets.UTF_8)
  }
}

/** What the fake answers: a JSON body, or a server-sent stream of events. */
class PythonResponse private constructor(
  private val status: Int,
  private val contentType: String,
  private val body: ByteArray,
  private val streaming: Boolean
) {
  fun writeTo(out: OutputStream) {
    val header = buildString {
      append("HTTP/1.1 $status ${if (status < 400) "OK" else "ERROR"}\r\n")
      append("Content-Type: $contentType\r\n")
      // A stream has no length to promise: it ends when the connection does, which is exactly
      // how a decode that dies mid-turn looks to a client.
      if (streaming) append("Connection: close\r\n") else append("Content-Length: ${body.size}\r\n")
      append("\r\n")
    }.toByteArray(StandardCharsets.UTF_8)
    out.write(header)
    out.write(body)
    out.flush()
  }

  companion object {
    fun json(body: String, status: Int = 200) =
      PythonResponse(status, "application/json", body.toByteArray(StandardCharsets.UTF_8), false)

    /** Each event is the text after `data: `; the caller decides whether `[DONE]` is among them. */
    fun stream(events: List<String>, status: Int = 200) = PythonResponse(
      status,
      "text/event-stream",
      events.joinToString("") { "data: $it\n\n" }.toByteArray(StandardCharsets.UTF_8),
      true
    )
  }
}
