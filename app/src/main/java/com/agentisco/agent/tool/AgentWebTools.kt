package com.agentisco.agent.tool

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Read a web page as text. The agent's other context sources are the workspace
 * and the terminal; without these it cannot find a library's documentation or
 * follow the error page URL it just saw.
 *
 * Deliberately narrow: http(s) only, a byte cap, an explicit output budget, and a
 * binary body is named rather than dumped.
 */
class WebFetchTool(private val client: OkHttpClient? = null) : AgentTool {
  override val name = "web_fetch"
  override val description =
    "Fetch an http(s) URL and return its readable text (HTML tags stripped, script/style removed). Use it for documentation, error pages, changelogs and API responses. Binary bodies are refused, and a cut-off is always stated. To find a URL in the first place use web_search."
  override val params = listOf(
    ToolParam("url", "Full URL to fetch, e.g. \"https://example.com/docs\"."),
    ToolParam(
      "max_chars",
      "Maximum characters to return (default $DEFAULT_CHARS, max $MAX_CHARS).",
      type = "integer", required = false
    )
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val rawUrl = args.str("url").trim().trim('<', '>')
    if (rawUrl.isEmpty()) return ToolResult(false, error = "url must be a full http(s) URL.")
    val candidate = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
    val request = candidate.toHttpUrlOrNull()?.let { Request.Builder().url(it).get().build() }
      ?: return ToolResult(
        false,
        error = "Not a fetchable URL: \"${candidate.take(120)}\". Only complete http and https URLs can be read."
      )
    val maxChars = args.optInt("max_chars", DEFAULT_CHARS).coerceIn(1_000, MAX_CHARS)

    val fetched = httpGet(client, request, maxChars)
      ?: return ToolResult(
        false,
        error = "Could not fetch $candidate: the connection failed or exceeded ${TIMEOUT_SECONDS}s. Verify the URL, or use run_command (curl) when the Linux environment has network access.",
        exitCode = -1
      )

    val body = if (fetched.contentType.isHtmlLike()) fetched.text.stripHtml() else fetched.text
    val header = "${fetched.finalUrl} — HTTP ${fetched.status}, ${fetched.contentType.ifBlank { "unknown type" }}\n\n"
    val hint = if (fetched.truncated) "the response was larger than the read cap; raise max_chars or fetch a deeper URL"
    else "raise max_chars, or fetch a deeper URL, for more"
    val output = ToolOutput.limit(header + body, maxChars, hint)
    if (fetched.status !in 200..299) {
      return ToolResult(
        success = false,
        error = "HTTP ${fetched.status} for ${fetched.finalUrl}",
        output = output,
        metadata = mapOf("status" to fetched.status.toString())
      )
    }
    return ToolResult(
      success = true,
      output = output,
      metadata = mapOf("status" to fetched.status.toString())
    )
  }

  companion object {
    const val DEFAULT_CHARS = 12_000
    const val MAX_CHARS = 60_000
    const val MAX_BYTES_TO_READ = 2_000_000L
  }
}

/**
 * Find documentation, issue threads and release notes by phrase. The model knows
 * the error text but rarely the canonical page for it; guessing a URL produces a
 * 404 and a second guess, so search is a separate step with its own honest
 * failure: no parsed results is reported as such rather than padded with guesses.
 */
class WebSearchTool(private val client: OkHttpClient? = null) : AgentTool {
  override val name = "web_search"
  override val description =
    "Search the web and get back titles, URLs and short snippets. Use it to find the documentation, issue or changelog for an error message or library, then web_fetch the URL that looks right. Returns at most $MAX_RESULTS results; when the engine does not answer it says so instead of inventing links."
  override val params = listOf(
    ToolParam("query", "Search terms, e.g. \"Room android database migration UNIQUE constraint\"."),
    ToolParam(
      "max_results",
      "Results to return (default $DEFAULT_RESULTS, max $MAX_RESULTS).",
      type = "integer", required = false
    )
  )

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    val query = args.str("query").trim()
    if (query.isEmpty()) return ToolResult(false, error = "query must be a non-empty search phrase.")
    val wanted = args.optInt("max_results", DEFAULT_RESULTS).coerceIn(1, MAX_RESULTS)
    val request = Request.Builder()
      .url(
        okhttp3.HttpUrl.Builder()
          .scheme("https").host(SEARCH_HOST).addPathSegment("html").addQueryParameter("q", query).build()
      )
      .header("User-Agent", USER_AGENT)
      .header("Accept-Language", "en")
      .get()
      .build()

    val fetched = httpGet(client, request, SNAPSHOT_CHARS)
      ?: return ToolResult(
        false,
        error = "Search for \"$query\" failed: the connection to $SEARCH_HOST did not complete within ${TIMEOUT_SECONDS}s. Use run_command (curl) when the Linux environment has network.",
        exitCode = -1
      )
    if (fetched.status !in 200..299) {
      return ToolResult(
        success = false,
        error = "Search for \"$query\" returned HTTP ${fetched.status} — the engine refused the request.",
        metadata = mapOf("status" to fetched.status.toString(), "results" to "0")
      )
    }
    val results = parseSearchResults(fetched.text, wanted)
    if (results.isEmpty()) {
      return ToolResult(
        false,
        error = "Search for \"$query\" returned no readable results, so the engine's page could not be parsed (it may have served a challenge or changed markup). Do not invent links: fetch the project's own documentation with web_fetch, or use run_command (curl) instead.",
        metadata = mapOf("results" to "0")
      )
    }
    val out = StringBuilder("Search results for \"$query\":\n")
    results.forEachIndexed { index, result ->
      out.appendLine("${index + 1}. ${result.title}")
      out.appendLine("   ${result.url}")
      if (result.snippet.isNotBlank()) out.appendLine("   ${result.snippet}")
    }
    out.append("\nRead one of these with web_fetch before acting on it.")
    return ToolResult(
      success = true,
      output = ToolOutput.limit(out.toString().trimEnd(), ToolOutput.LIST_CHARS, "raise max_results or narrow the query"),
      metadata = mapOf("results" to results.size.toString())
    )
  }

  companion object {
    const val DEFAULT_RESULTS = 5
    const val MAX_RESULTS = 10
    const val SEARCH_HOST = "html.duckduckgo.com"
    private const val SNAPSHOT_CHARS = 60_000
    private const val USER_AGENT =
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
  }
}

/** One search hit, already reduced to what the model needs to pick a link. */
internal data class SearchResult(val title: String, val url: String, val snippet: String)

private val RESULT_LINK = Regex(
  "(?is)<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>"
)
private val RESULT_SNIPPET = Regex(
  "(?is)class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.{0,600}?)</(?:a|div|span|p)>"
)
/** Snippets follow their link, so only this much of the page is scanned per hit. */
private const val SNIPPET_WINDOW = 1_200

/**
 * Pulls title/url/snippet triples out of the search engine's HTML. Anchor-based
 * rather than tree-based on purpose: the markup is a flat result list, and a
 * link whose target cannot be resolved is skipped instead of reported.
 */
internal fun parseSearchResults(html: String, limit: Int): List<SearchResult> {
  val out = ArrayList<SearchResult>(limit)
  for (match in RESULT_LINK.findAll(html)) {
    if (out.size >= limit) break
    val url = resolveSearchTarget(match.groupValues[1]) ?: continue
    val title = match.groupValues[2].stripHtml()
    if (title.isBlank()) continue
    val snippet = RESULT_SNIPPET
      .find(html.substring(match.range.last, minOf(html.length, match.range.last + SNIPPET_WINDOW)))
      ?.groupValues?.get(1).orEmpty().stripHtml()
    out.add(SearchResult(title, url, snippet))
  }
  return out
}

/**
 * The engine wraps every link in its own redirect (`//host/l/?uddg=<encoded>`) so
 * that clicks are counted. Only the real destination is useful to the model, and a
 * link that still points at the engine after unwrapping is dropped — reporting the
 * wrapper would send the model somewhere it cannot read.
 */
private fun resolveSearchTarget(href: String): String? {
  val trimmed = href.trim()
  if (trimmed.isEmpty() || trimmed.startsWith("javascript:") || trimmed.startsWith("#")) return null
  val absolute = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
  val parsed = absolute.toHttpUrlOrNull() ?: return null
  if (parsed.host.endsWith(ENGINE_HOST_SUFFIX)) {
    return parsed.queryParameter("uddg")?.toHttpUrlOrNull()?.toString()
  }
  return absolute
}

private const val ENGINE_HOST_SUFFIX = "duckduckgo.com"

/** Both web tools give the network the same budget. */
private const val TIMEOUT_SECONDS = 30L

/** One HTTP GET, read under a byte cap. Null means the transport failed. */
private suspend fun httpGet(client: OkHttpClient?, request: Request, maxChars: Int): HttpBody? {
  val http = (client ?: sharedClient).newBuilder()
    .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
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

/** Result of one HTTP round trip, already size-limited. */
internal class HttpBody(
  val status: Int,
  val contentType: String,
  val finalUrl: String,
  val text: String,
  val truncated: Boolean
)

/** Reads at most [WebFetchTool.MAX_BYTES_TO_READ] so a huge download cannot exhaust the app. */
private fun readBody(response: Response, maxChars: Int): HttpBody {
  val contentType = response.header("Content-Type").orEmpty().lowercase()
  val url = response.request.url.toString()
  if (contentType.contains("octet-stream") || contentType.contains("image/") ||
    contentType.contains("video/") || contentType.contains("audio/") ||
    contentType.contains("application/pdf") || contentType.contains("zip")
  ) {
    return HttpBody(
      status = response.code,
      contentType = contentType,
      finalUrl = url,
      text = "(refused: this is a ${contentType.substringBefore(';')} file, not text. Download it with run_command if the project needs it.)",
      truncated = false
    )
  }
  val input = response.body?.byteStream()
    ?: return HttpBody(response.code, contentType, url, "(empty response body)", false)
  val builder = StringBuilder()
  var bytesRead = 0L
  var truncated = false
  input.use { stream ->
    val buffer = ByteArray(8_192)
    while (true) {
      val read = stream.read(buffer)
      if (read < 0) break
      bytesRead += read
      if (bytesRead > WebFetchTool.MAX_BYTES_TO_READ || builder.length > maxChars * 2) {
        truncated = true
        break
      }
      builder.append(String(buffer, 0, read, Charsets.UTF_8))
    }
  }
  return HttpBody(response.code, contentType, url, builder.toString(), truncated)
}

/** One shared client keeps a single connection pool; call timeouts are per call. */
private val sharedClient by lazy {
  OkHttpClient.Builder()
    .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .build()
}

private fun String.isHtmlLike(): Boolean =
  contains("html", ignoreCase = true) || contains("xml", ignoreCase = true)

/**
 * Tags out, text preserved. Script and style blocks carry no readable content,
 * block-level tags become line breaks, list items become bullets and the common
 * entities are decoded — no HTML parser dependency for what is context for a
 * model, not something to render.
 */
internal fun String.stripHtml(): String {
  val withoutTags = this
    .replace(Regex("(?is)<(script|style|noscript|template)[^>]*>.*?</\\1>"), " ")
    .replace(Regex("(?is)<!--.*?-->"), " ")
    .replace(Regex("(?is)<br\\s*/?>"), "\n")
    .replace(Regex("(?is)<li[^>]*>"), "\n- ")
    .replace(Regex("(?is)<(h[1-6])[^>]*>"), "\n")
    .replace(
      Regex("(?is)</?(p|div|ul|ol|section|article|header|footer|tr|table|pre|blockquote|td|th)[^>]*>"),
      "\n"
    )
    .replace(Regex("(?is)<[^>]+>"), " ")
  return withoutTags
    .replace("&nbsp;", " ").replace("&#160;", " ")
    .replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
    .replace("&amp;", "&")
    .replace(Regex("[ \t]+"), " ")
    .replace(Regex(" ?\n ?"), "\n")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()
}
