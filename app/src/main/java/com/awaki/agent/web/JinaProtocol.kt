package com.awaki.agent.web

import com.awaki.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * The wire contract of Jina.ai's reader and search endpoints, kept apart from the
 * pool that spends requests against it: what a response means, and what it holds.
 */

/** Bundled with the build so a device without its own key still gets authenticated calls. */
internal fun bundledAppKeys(): List<String> = readAppKeyList(BuildConfig.JINA_API_KEYS)

/**
 * Split on whatever separates a pasted key list, and drop anything after a `#` — a list
 * kept in a `.env` file or a CI secret often carries a trailing note, and a note read as
 * a key would sit in the rotation burning a call on every turn. An unset build
 * contributes the empty string, which reads as "no keys" rather than a key that fails.
 */
internal fun readAppKeyList(raw: String?): List<String> =
  raw?.lines()?.joinToString("\n") { it.substringBefore('#') }
    ?.replace(Regex("""(?i)\bbearer[ \t]+"""), "")
    ?.split(',', ';', '\n', ' ', '\t')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.distinct()
    ?: emptyList()

/** Adds the credential to a request that is about to be dialed to Jina. */
internal fun Request.Builder.authorize(identity: JinaIdentity): Request.Builder =
  if (identity.key.isNullOrBlank()) this else header("Authorization", "Bearer ${identity.key}")

/** How a Jina answer should be treated, after its own error body is taken into account. */
internal sealed class JinaFault {
  /** The identity is out of requests; [retryAfterSeconds] is what Jina asked for. */
  data class RateLimited(val retryAfterSeconds: Long?) : JinaFault()

  /** The key itself is refused, revoked or out of credit — a different key may work. */
  data class CredentialUnusable(val message: String) : JinaFault()

  /** The target page is missing, blocked or refused. Another key sees the same page. */
  data class PageRefused(val status: Int, val message: String) : JinaFault()

  /** Jina could not do its job. Nothing about the request was wrong. */
  data class ServiceDown(val message: String) : JinaFault()
}

/**
 * Classifies one Jina response. Jina reports its own failures in the body as well as in
 * the status line, and a body `code` of 429 beside an HTTP 200 is still a refusal, so
 * the body wins where the two disagree.
 */
internal fun jinaFailure(response: WebResponse): JinaFault? {
  val status = response.status
  val body = response.text
  val reported = statusCodeFrom(body) ?: status
  val message = messageFrom(body) ?: "HTTP $status"
  val retryAfter = RateSignal.of(response.headers).retryAfterSeconds
  return when {
    reported == 429 || body.contains("RateLimitExceed") -> JinaFault.RateLimited(retryAfter)
    reported == 401 || reported == 402 || reported == 403 -> JinaFault.CredentialUnusable(message)
    reported in 400..499 -> JinaFault.PageRefused(reported, message)
    reported in 500..599 -> JinaFault.ServiceDown(message)
    reported in 200..299 -> null
    else -> JinaFault.ServiceDown(message)
  }
}

/** Reads Jina's own `code`/`status` from a JSON error body. */
private fun statusCodeFrom(body: String): Int? {
  val trimmed = body.trimStart()
  if (!trimmed.startsWith("{")) return null
  val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
  val code = obj.optInt("code", 0)
  if (code in 1..599) return code
  // Some answers carry only the wrapped status, e.g. 42900 beside RateLimitExceedError.
  val status = obj.optInt("status", 0)
  return if (status >= 1_000) status / 100 else null
}

private fun messageFrom(body: String): String? {
  val trimmed = body.trimStart()
  if (!trimmed.startsWith("{")) return trimmed.takeIf { it.isNotEmpty() }?.take(240)
  val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
  return obj.optString("message").takeIf { it.isNotBlank() }
    ?: obj.optString("readableMessage").takeIf { it.isNotBlank() }
    ?: obj.optString("name").takeIf { it.isNotBlank() }
}

/** The page as the reader returned it. */
internal class ReaderAnswer(val markdown: String, val title: String?, val finalUrl: String?) {
  val isEmpty: Boolean get() = markdown.isBlank()
}

/**
 * Accepts both shapes the reader can produce: JSON when `Accept: application/json` is
 * honored, and the plain form — `Title:` / `URL Source:` / `Markdown Content:` — when
 * it is not. Both are read from live responses, so choosing one would lose the page.
 */
internal fun parseReaderBody(body: String): ReaderAnswer {
  val trimmed = body.trim()
  if (trimmed.startsWith("{")) {
    val data = runCatching { JSONObject(trimmed).optJSONObject("data") }.getOrNull()
    if (data != null) {
      return ReaderAnswer(
        markdown = data.optString("content", ""),
        title = data.optString("title").takeIf { it.isNotBlank() },
        finalUrl = data.optString("url").takeIf { it.isNotBlank() }
      )
    }
  }
  return ReaderAnswer(
    markdown = trimmed.substringAfter("Markdown Content:", trimmed).trim(),
    title = Regex("""(?m)^Title:[ \t]*(.+)$""").find(trimmed)?.groupValues?.get(1)?.trim(),
    finalUrl = Regex("""(?m)^URL Source:[ \t]*(.+)$""").find(trimmed)?.groupValues?.get(1)?.trim()
  )
}

/** A citation, which Jina wraps in doubled brackets: `[[1]](https://…)`. */
private val MARKDOWN_LINK = Regex("""(?<!!)\[{1,2}([^\]\n]{1,200})]{1,2}\((https?://[^)\s]+)\)""")
private val BARE_URL = Regex("""(?<!\()\bhttps?://[^\s)\]>"']+""")
private val NUMBER_ONLY = Regex("""^\[?\d{1,3}]?$""")
private val ASSET = Regex("""\.(png|jpe?g|gif|webp|svg|ico|css|js|mp4|webm)$""", RegexOption.IGNORE_CASE)

/**
 * Turns a Jina search answer into links. Jina has shipped this two ways — a structured
 * `associatedLinks` array, and a written answer whose citations are markdown links — so
 * the structured form is used when present and the prose is mined when it is not.
 *
 * A citation's link text is often only its number, which tells the model nothing, so the
 * site's own name stands in for the title and the text beside the link becomes the
 * snippet. Nothing is invented: an answer with no links in it yields no hits, and the
 * caller says so rather than padding with guesses.
 */
internal fun parseSearchHits(body: String, limit: Int): List<WebHit> {
  val trimmed = body.trim()
  if (trimmed.startsWith("{")) {
    val data = runCatching { JSONObject(trimmed).optJSONObject("data") }.getOrNull()
    val structured = data?.optJSONArray("associatedLinks").toStructuredHits()
    if (structured != null && structured.isNotEmpty()) return structured.take(limit)
    val content = data?.optString("content", "").orEmpty().ifBlank { trimmed }
    return hitsFromMarkdown(content, limit)
  }
  return hitsFromMarkdown(trimmed, limit)
}

private fun JSONArray?.toStructuredHits(): List<WebHit>? {
  if (this == null) return null
  return (0 until length()).mapNotNull { index ->
    val item = optJSONObject(index) ?: return@mapNotNull null
    val url = item.optString("url").takeIf { it.toHttpUrlOrNull() != null } ?: return@mapNotNull null
    WebHit(
      title = item.optString("title").takeIf { it.isNotBlank() } ?: hostOf(url),
      url = url,
      snippet = item.optString("snippet").takeIf { it.isNotBlank() }
        ?: item.optString("description").takeIf { it.isNotBlank() }.orEmpty()
    )
  }.usableHits()
}

internal fun hitsFromMarkdown(markdown: String, limit: Int): List<WebHit> {
  val lines = markdown.lines()
  val hits = ArrayList<WebHit>()
  val seen = HashSet<String>()
  for (match in MARKDOWN_LINK.findAll(markdown)) {
    val url = match.groupValues[2].trim().trimEnd('.', ',', '>')
    if (url.toHttpUrlOrNull() == null || !seen.add(url)) continue
    val text = match.groupValues[1].trim()
    val line = lines.firstOrNull { candidate -> candidate.contains(match.value) }.orEmpty()
    val beside = line.substring(line.indexOf(match.value) + match.value.length)
      .trim().removePrefix(":").removePrefix("—").removePrefix("-").trim()
    hits += WebHit(
      title = if (text.isBlank() || NUMBER_ONLY.matches(text)) hostOf(url) else text,
      url = url,
      snippet = beside.take(280)
    )
    if (hits.size >= limit * 3) break
  }
  if (hits.isEmpty()) {
    for (match in BARE_URL.findAll(markdown)) {
      val url = match.value.trimEnd('.', ',', '>')
      if (url.toHttpUrlOrNull() == null || !seen.add(url)) continue
      hits += WebHit(hostOf(url), url, "")
      if (hits.size >= limit * 3) break
    }
  }
  return hits.usableHits().take(limit)
}

/**
 * Only pages a reader could actually open: no duplicates, no link back at the search
 * service itself, no bare domain root and no image or stylesheet.
 */
private fun List<WebHit>.usableHits(): List<WebHit> = distinctBy { it.url }.filterNot { hit ->
  val host = hit.url.toHttpUrlOrNull()?.host.orEmpty()
  host.endsWith("jina.ai") ||
    ASSET.containsMatchIn(hit.url) ||
    hit.url.toHttpUrlOrNull()?.encodedPath.let { it == null || it == "/" || it.isBlank() }
}

internal fun hostOf(url: String): String =
  url.toHttpUrlOrNull()?.host?.removePrefix("www.").orEmpty().ifBlank { url.take(60) }
