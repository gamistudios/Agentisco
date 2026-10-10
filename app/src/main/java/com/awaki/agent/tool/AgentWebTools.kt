package com.awaki.agent.tool

import com.awaki.agent.web.DuckSearch
import com.awaki.agent.web.JinaRead
import com.awaki.agent.web.JinaSearch
import com.awaki.agent.web.ParallelRead
import com.awaki.agent.web.ParallelSearch
import com.awaki.agent.web.WEB_TIMEOUT_SECONDS
import com.awaki.agent.web.WebGateway
import com.awaki.agent.web.WebHit
import com.awaki.data.local.FetchProvider
import com.awaki.data.local.SearchProvider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject

/**
 * Read a web page as text. The agent's other context sources are the workspace
 * and the terminal; without these it cannot find a library's documentation or
 * follow the error page URL it just saw.
 *
 * Deliberately narrow: http(s) only, a byte cap, an explicit output budget, and a
 * binary body is named rather than dumped.
 *
 * The providers come from the gateway's chain: by default Jina.ai's reader, which runs a
 * page's JavaScript and hands back markdown, needs no key for twenty fetches a minute;
 * Parallel's free extractor is the keyless alternative; and the URL fetched directly is
 * always the last step. That route is the one that carries an honest status line, so a
 * page that is gone still reports as gone rather than as whatever the site renders for it.
 */
class WebFetchTool(private val web: WebGateway) : AgentTool {
  override val name = "web_fetch"
  override val description =
    "Fetch an http(s) URL and return its readable text: markdown from a reader service (Parallel or Jina.ai, as configured in Settings; these run JavaScript pages) where one answers, otherwise the page fetched directly with HTML tags stripped and script/style removed. Use it for documentation, error pages, changelogs and API responses. Binary bodies are refused, and a cut-off is always stated. To find a URL in the first place use web_search."
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
    val target = candidate.toHttpUrlOrNull()
      ?: return ToolResult(
        false,
        error = "Not a fetchable URL: \"${candidate.take(120)}\". Only complete http and https URLs can be read."
      )
    val maxChars = args.optInt("max_chars", DEFAULT_CHARS).coerceIn(1_000, MAX_CHARS)

    var readerNote = ""
    var direct: com.awaki.agent.web.WebResponse? = null
    for (provider in web.fetchChain()) {
      when (provider) {
        FetchProvider.Jina -> when (val reader = web.read(target.toString(), maxChars)) {
          is JinaRead.Served -> return served(
            origin = reader.finalUrl ?: target.toString(),
            via = "Jina.ai",
            title = reader.title,
            markdown = reader.markdown,
            maxChars = maxChars,
            metadata = mapOf("via" to "jina.ai", "served_by" to reader.servedBy)
          )

          // A page the reader could not open is still worth dialing directly: a site that
          // blocks the reader's address may answer a plain client, and only the direct
          // route knows which status code the server chose.
          is JinaRead.Refused -> readerNote += " The Jina.ai reader reported ${reader.status}: ${reader.message}."
          is JinaRead.Unavailable -> readerNote += " The Jina.ai reader did not serve it (${reader.message})."
        }

        FetchProvider.Parallel -> when (val reader = web.parallelRead(target.toString(), maxChars)) {
          is ParallelRead.Served -> return served(
            origin = reader.finalUrl ?: target.toString(),
            via = "Parallel",
            title = reader.title,
            markdown = reader.markdown,
            maxChars = maxChars,
            metadata = mapOf("via" to "parallel")
          )

          is ParallelRead.Unavailable -> readerNote += " The Parallel reader did not serve it (${reader.message})."
        }

        // The device's own request. It is the end of every chain, and the start of one when
        // the user picked it; a good answer ends the walk, a bad one is kept so the
        // failure reported is the server's own, not the last reader's.
        FetchProvider.Direct -> {
          direct = web.fetch(Request.Builder().url(target).get().build(), maxChars)
          if (direct != null && direct.status in 200..299) return directResult(direct, maxChars)
        }
      }
    }

    val fetched = direct
      ?: return ToolResult(
        false,
        error = "Could not fetch $candidate: the connection failed or exceeded ${WEB_TIMEOUT_SECONDS}s." +
          readerNote +
          " Verify the URL, or use run_command (curl) when the Linux environment has network access.",
        exitCode = -1
      )

    val output = directOutput(fetched, maxChars)
    return ToolResult(
      success = false,
      error = "HTTP ${fetched.status} for ${fetched.finalUrl}",
      output = output,
      metadata = mapOf("status" to fetched.status.toString(), "via" to "direct")
    )
  }

  private fun directOutput(fetched: com.awaki.agent.web.WebResponse, maxChars: Int): String {
    val body = if (fetched.contentType.isHtmlLike()) fetched.text.stripHtml() else fetched.text
    val header = "${fetched.finalUrl} — HTTP ${fetched.status}, ${fetched.contentType.ifBlank { "unknown type" }}\n\n"
    val hint = if (fetched.truncated) CAP_HINT else MORE_HINT
    return ToolOutput.limit(header + body, maxChars, hint)
  }

  private fun directResult(fetched: com.awaki.agent.web.WebResponse, maxChars: Int): ToolResult =
    ToolResult(
      success = true,
      output = directOutput(fetched, maxChars),
      metadata = mapOf("status" to fetched.status.toString(), "via" to "direct")
    )

  private fun served(
    origin: String,
    via: String,
    title: String?,
    markdown: String,
    maxChars: Int,
    metadata: Map<String, String>
  ): ToolResult {
    val header = buildString {
      append(origin)
      append(" — markdown from ").append(via)
      title?.let { append(" — \"").append(it.take(120)).append('"') }
      append("\n\n")
    }
    return ToolResult(
      success = true,
      output = ToolOutput.limit(header + markdown.trim(), maxChars, MORE_HINT),
      metadata = metadata
    )
  }

  companion object {
    const val DEFAULT_CHARS = 12_000
    const val MAX_CHARS = 60_000
    private const val MORE_HINT = "raise max_chars, or fetch a deeper URL, for more"
    private const val CAP_HINT = "the response was larger than the read cap; raise max_chars or fetch a deeper URL"
  }
}

/**
 * Find documentation, issue threads and release notes by phrase. The model knows
 * the error text but rarely the canonical page for it; guessing a URL produces a
 * 404 and a second guess, so search is a separate step with its own honest
 * failure: no parsed results is reported as such rather than padded with guesses.
 *
 * Parallel's free MCP is the default engine for both tools: keyless, and the answer
 * arrives as excerpts or markdown ready for the model. DuckDuckGo's live result page is
 * the keyless search alternative, and Jina.ai answers with real titles, links and
 * snippets but refuses an anonymous call, so it only takes part when a key exists.
 * Settings picks the first engine; when it cannot
 * answer, the gateway's chain names the next.
 */
class WebSearchTool(private val web: WebGateway) : AgentTool {
  override val name = "web_search"
  override val description =
    "Search the web and get back titles, URLs and short snippets (Parallel's free search by default; DuckDuckGo or Jina.ai as configured in Settings, with the others as fallback). Use it to find the documentation, issue or changelog for an error message or library, then web_fetch the URL that looks right. Returns at most $MAX_RESULTS results; when the engine does not answer it says so instead of inventing links."
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

    val chain = web.searchChain()
    if (chain.isEmpty()) {
      return ToolResult(
        false,
        error = "Search for \"$query\" could not run: Jina.ai search is selected in Settings but no Jina key is " +
          "configured, and fallback to the other engines is off. Add a key, pick another engine, or turn fallback on.",
        metadata = mapOf("results" to "0")
      )
    }

    val failures = ArrayList<String>()
    for (engine in chain) {
      val found: Found? = when (engine) {
        SearchProvider.DuckDuckGo -> when (val answer = web.searchDuckDuckGo(query, wanted)) {
          is DuckSearch.Served -> Found(answer.hits, "duckduckgo", null)
          is DuckSearch.Unavailable -> {
            failures += "${answer.message}."
            null
          }
        }

        SearchProvider.Parallel -> when (val answer = web.parallelSearch(query, wanted)) {
          is ParallelSearch.Served -> Found(answer.hits, "parallel", null)
          is ParallelSearch.Unavailable -> {
            failures += "The Parallel search did not answer (${answer.message})."
            null
          }
        }

        SearchProvider.Jina -> when (val answer = web.search(query, wanted)) {
          is JinaSearch.Served -> Found(answer.hits, "jina.ai", answer.servedBy)
          is JinaSearch.Unavailable -> {
            failures += "The Jina.ai search did not answer (${answer.message})."
            null
          }
        }
      }
      if (found == null) continue
      return ToolResult(
        success = true,
        output = ToolOutput.limit(
          renderHits(query, found.hits),
          ToolOutput.LIST_CHARS,
          "raise max_results or narrow the query"
        ),
        metadata = buildMap {
          put("results", found.hits.size.toString())
          put("via", found.via)
          found.servedBy?.let { put("served_by", it) }
        }
      )
    }

    val timedOut = failures.isNotEmpty() && failures.all { it.contains("did not complete") || it.contains("did not answer within") }
    return ToolResult(
      success = false,
      error = "Search for \"$query\" failed: ${failures.joinToString(" ")} " +
        "Do not invent links: fetch the project's own documentation with web_fetch, " +
        "or use run_command (curl) when the Linux environment has network access.",
      metadata = mapOf("results" to "0"),
      exitCode = if (timedOut) -1 else null
    )
  }

  /** One engine's answer, reduced to what the tool reports. */
  private class Found(val hits: List<WebHit>, val via: String, val servedBy: String?)

  companion object {
    const val DEFAULT_RESULTS = 5
    const val MAX_RESULTS = 10
    const val SEARCH_HOST = "html.duckduckgo.com"

    /** Both engines answer in the same shape, so the model reads one format. */
    private fun renderHits(query: String, hits: List<WebHit>): String {
      val out = StringBuilder("Search results for \"$query\":\n")
      hits.forEachIndexed { index, hit ->
        out.appendLine("${index + 1}. ${hit.title}")
        out.appendLine("   ${hit.url}")
        if (hit.snippet.isNotBlank()) out.appendLine("   ${hit.snippet}")
      }
      out.append("\nRead one of these with web_fetch before acting on it.")
      return out.toString().trimEnd()
    }
  }
}

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
internal fun parseSearchResults(html: String, limit: Int): List<WebHit> {
  val out = ArrayList<WebHit>(limit)
  for (match in RESULT_LINK.findAll(html)) {
    if (out.size >= limit) break
    val url = resolveSearchTarget(match.groupValues[1]) ?: continue
    val title = match.groupValues[2].stripHtml()
    if (title.isBlank()) continue
    val snippet = RESULT_SNIPPET
      .find(html.substring(match.range.last, minOf(html.length, match.range.last + SNIPPET_WINDOW)))
      ?.groupValues?.get(1).orEmpty().stripHtml()
    out.add(WebHit(title, url, snippet))
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
