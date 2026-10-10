package com.awaki.agent.web

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * The wire contract of Parallel's free, keyless Search MCP (`https://search.parallel.ai/mcp`):
 * JSON-RPC 2.0 over MCP's Streamable HTTP transport, exposing two tools, `web_search` and
 * `web_fetch`. Kept apart from the gateway for the same reason [JinaProtocol] is: what a
 * response means is one concern, who is allowed to spend a request is another.
 *
 * The server's reply text is not a documented schema, so every parser here accepts the
 * structured form (a `results` array) and falls back to reading markdown and URLs, and
 * none of them invents a hit the server did not send.
 */
internal const val PARALLEL_MCP_URL = "https://search.parallel.ai/mcp"
internal const val MCP_PROTOCOL_VERSION = "2025-03-26"

internal fun mcpInitializeBody(): String =
  JSONObject()
    .put("jsonrpc", "2.0")
    .put("id", 1)
    .put("method", "initialize")
    .put(
      "params",
      JSONObject()
        .put("protocolVersion", MCP_PROTOCOL_VERSION)
        .put("capabilities", JSONObject())
        .put("clientInfo", JSONObject().put("name", "awaki").put("version", "1"))
    )
    .toString()

internal fun mcpInitializedBody(): String =
  JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString()

internal fun mcpToolCallBody(id: Int, tool: String, arguments: JSONObject): String =
  JSONObject()
    .put("jsonrpc", "2.0")
    .put("id", id)
    .put("method", "tools/call")
    .put("params", JSONObject().put("name", tool).put("arguments", arguments))
    .toString()

internal fun parallelSearchArguments(query: String, sessionId: String): JSONObject =
  JSONObject()
    .put("objective", "Find current, relevant web pages for: $query")
    .put("search_queries", JSONArray().put(query))
    .put("session_id", sessionId)

internal fun parallelFetchArguments(url: String, sessionId: String): JSONObject =
  JSONObject()
    .put("urls", JSONArray().put(url))
    .put("full_content", true)
    .put("session_id", sessionId)

/** What a `tools/call` came back with, reduced to text or a reason. */
internal sealed class McpReply {
  data class Text(val text: String) : McpReply()
  data class Failed(val message: String) : McpReply()
}

/**
 * Streamable HTTP answers either a single JSON body or a short `text/event-stream`, and a
 * server may use either per request. Both are read for the JSON-RPC message that carries
 * [expectedId]; for a stream the last such `data:` event wins.
 */
internal fun parseRpcMessage(body: String, expectedId: Int): JSONObject? {
  val trimmed = body.trim()
  if (trimmed.startsWith("{")) {
    val obj = runCatching { JSONObject(trimmed) }.getOrNull()
    if (obj != null && (!obj.has("id") || obj.optInt("id", expectedId) == expectedId)) return obj
  }
  var found: JSONObject? = null
  val event = StringBuilder()
  fun flush() {
    if (event.isNotEmpty()) {
      val obj = runCatching { JSONObject(event.toString()) }.getOrNull()
      if (obj != null && obj.optInt("id", expectedId) == expectedId) found = obj
      event.setLength(0)
    }
  }
  for (line in body.lineSequence()) {
    when {
      line.startsWith("data:") -> event.append(line.removePrefix("data:").trimStart())
      line.isBlank() -> flush()
    }
  }
  flush()
  return found
}

/** The text of a tool result, or the way it failed. */
internal fun readToolReply(message: JSONObject?): McpReply {
  if (message == null) return McpReply.Failed("the server sent no readable JSON-RPC message")
  message.optJSONObject("error")?.let { error ->
    return McpReply.Failed(error.optString("message").ifBlank { "the server returned an error" }.take(240))
  }
  val result = message.optJSONObject("result") ?: return McpReply.Failed("the reply carried no result")
  val parts = result.optJSONArray("content")
  val text = buildString {
    if (parts != null) {
      for (index in 0 until parts.length()) {
        val part = parts.optJSONObject(index) ?: continue
        if (part.optString("type", "text") == "text") {
          if (isNotEmpty()) append("\n\n")
          append(part.optString("text"))
        }
      }
    }
  }.trim()
  if (result.optBoolean("isError", false)) {
    return McpReply.Failed(text.ifBlank { "the tool reported an error" }.take(240))
  }
  if (text.isEmpty()) return McpReply.Failed("the tool returned no text")
  return McpReply.Text(text)
}

/**
 * Turns Parallel's search text into links. The structured form is a `results` array whose
 * items carry `url`, `title` and `excerpts`; anything else is mined as markdown.
 */
internal fun parseParallelSearch(text: String, limit: Int): List<WebHit> {
  val trimmed = text.trim()
  val results = runCatching {
    when {
      trimmed.startsWith("{") -> JSONObject(trimmed).optJSONArray("results")
      trimmed.startsWith("[") -> JSONArray(trimmed)
      else -> null
    }
  }.getOrNull()
  if (results != null) {
    val hits = (0 until results.length()).mapNotNull { index ->
      val item = results.optJSONObject(index) ?: return@mapNotNull null
      val url = item.optString("url").takeIf { it.toHttpUrlOrNull() != null } ?: return@mapNotNull null
      WebHit(
        title = item.optString("title").takeIf { it.isNotBlank() } ?: hostOf(url),
        url = url,
        snippet = excerptOf(item).take(PARALLEL_SNIPPET_CHARS)
      )
    }.distinctBy { it.url }
    if (hits.isNotEmpty()) return hits.take(limit)
  }
  return hitsFromMarkdown(trimmed, limit)
}

/** The first non-empty excerpt, whether the server sent an array or a single string. */
private fun excerptOf(item: JSONObject): String {
  val excerpts = item.optJSONArray("excerpts")
  if (excerpts != null) {
    for (index in 0 until excerpts.length()) {
      val excerpt = excerpts.optString(index).trim()
      if (excerpt.isNotEmpty()) return excerpt.replace(Regex("\\s+"), " ")
    }
  }
  return listOf("excerpt", "snippet", "description")
    .firstNotNullOfOrNull { key -> item.optString(key).trim().takeIf { it.isNotEmpty() } }
    ?.replace(Regex("\\s+"), " ")
    .orEmpty()
}

/**
 * Turns Parallel's fetch text into a page. `full_content` is preferred because the tool
 * asked for it; excerpts are the answer when only those came back, and a body that is not
 * JSON is already the markdown.
 */
internal fun parseParallelFetch(text: String): ReaderAnswer {
  val trimmed = text.trim()
  val results = runCatching {
    when {
      trimmed.startsWith("{") -> JSONObject(trimmed).optJSONArray("results")
      trimmed.startsWith("[") -> JSONArray(trimmed)
      else -> null
    }
  }.getOrNull()
  val first = results?.optJSONObject(0)
  if (first != null) {
    val full = first.optString("full_content").takeIf { it.isNotBlank() }
    val excerpts = first.optJSONArray("excerpts")?.let { array ->
      (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotEmpty() }
    }.orEmpty()
    val markdown = full ?: excerpts.joinToString("\n\n")
    return ReaderAnswer(
      markdown = markdown,
      title = first.optString("title").takeIf { it.isNotBlank() },
      finalUrl = first.optString("url").takeIf { it.isNotBlank() }
    )
  }
  return ReaderAnswer(markdown = trimmed, title = null, finalUrl = null)
}

private const val PARALLEL_SNIPPET_CHARS = 400
