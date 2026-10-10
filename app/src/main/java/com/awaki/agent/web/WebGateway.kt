package com.awaki.agent.web

import com.awaki.agent.tool.parseSearchResults
import com.awaki.data.local.FetchProvider
import com.awaki.data.local.SearchProvider
import com.awaki.data.local.WebAccessSettings
import com.awaki.data.local.WebAccessStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** The two Jina.ai endpoints the agent spends request budget against. */
internal enum class JinaEndpoint { Reader, Search }

/** Where a credential came from, which decides its order in the rotation and its name. */
enum class JinaKeyOrigin { User, App }

/**
 * One identity the pool may spend: Jina's anonymous tier (`key == null`), a key the
 * user added, or one bundled with the build.
 */
data class JinaIdentity(val key: String?, val origin: JinaKeyOrigin?, val label: String) {
  val keyed: Boolean get() = !key.isNullOrBlank()

  internal companion object {
    /** The anonymous tier: 20 reader requests a minute, no key, no account. */
    val ANONYMOUS = JinaIdentity(key = null, origin = null, label = "anonymous")

    fun forKey(key: String, origin: JinaKeyOrigin): JinaIdentity =
      JinaIdentity(key, origin, WebAccessStore.fingerprintOf(key))
  }
}

/** What one reader call produced. */
sealed class JinaRead {
  data class Served(
    val markdown: String,
    val title: String?,
    val finalUrl: String?,
    val servedBy: String
  ) : JinaRead()

  /** Jina answered about the page itself: it is missing, blocked or gone. */
  data class Refused(val status: Int, val message: String) : JinaRead()

  /** The reader could not be consulted, so the caller's own fetch may know better. */
  data class Unavailable(val message: String) : JinaRead()
}

/** What one search call produced. */
sealed class JinaSearch {
  data class Served(val hits: List<WebHit>, val servedBy: String) : JinaSearch()
  data class Unavailable(val message: String) : JinaSearch()
}

/** What one Parallel page read produced. */
sealed class ParallelRead {
  data class Served(val markdown: String, val title: String?, val finalUrl: String?) : ParallelRead()
  data class Unavailable(val message: String) : ParallelRead()
}

/** What one Parallel search produced. */
sealed class ParallelSearch {
  data class Served(val hits: List<WebHit>) : ParallelSearch()
  data class Unavailable(val message: String) : ParallelSearch()
}

/** What one DuckDuckGo search produced. */
sealed class DuckSearch {
  data class Served(val hits: List<WebHit>) : DuckSearch()
  data class Unavailable(val message: String) : DuckSearch()
}

/** One search hit, reduced to what a model needs in order to pick a link. */
data class WebHit(val title: String, val url: String, val snippet: String)

/** How many keys this build carries. The secrets themselves are never read out of it. */
fun bundledAppKeyCount(): Int = bundledAppKeys().size

/**
 * The agent's front door to the web: three providers behind two tools.
 *
 * `web_search` asks DuckDuckGo by default and `web_fetch` asks Jina.ai's reader by
 * default; Settings can change either, and [searchChain] / [fetchChain] say in what
 * order the providers are tried when the first one cannot answer. Parallel's free MCP is
 * the keyless third option for both. The direct request is always the end of a fetch.
 *
 * Within Jina.ai the policy is spend-nothing-first: an anonymous reader call costs the app nothing and
 * answers 20 times a minute, so it is tried before any key. A key — the user's own, or
 * one bundled with the build — is what a refused call rotates to, and every identity
 * keeps its own minute window so an exhausted one is skipped instead of knocked on
 * again. When the pool cannot serve a call the caller still has its own direct fetch or
 * scrape underneath this, which is why each failure here is a sentence rather than an
 * exception.
 */
class WebGateway(
  private val client: OkHttpClient? = null,
  private val settings: () -> WebAccessSettings = { WebAccessSettings() },
  private val userKeys: () -> List<String> = { emptyList() },
  private val appKeys: () -> List<String> = { bundledAppKeys() },
  private val clock: () -> Long = { System.currentTimeMillis() }
) {

  private val pool = IdentityPool(clock)

  /** Rotates the key list so one key's minute is not the whole app's minute. */
  private val cursor = AtomicInteger()

  /** Whether Jina's reader is somewhere in this fetch's chain. */
  val readerPreferred: Boolean get() = FetchProvider.Jina in fetchChain()

  /**
   * Search has no anonymous tier — `s.jina.ai` refuses an unauthenticated call — so it
   * is only in the chain when a key is in hand.
   */
  val searchPreferred: Boolean get() = SearchProvider.Jina in searchChain()

  /**
   * The providers a page read goes to, in order: the one chosen in Settings, then — when
   * fallback is on — the others. Direct ends every chain, because it is the only route that
   * reports the server's own status line and the only one that needs no third party; it
   * leads the chain only when the user chose it.
   */
  fun fetchChain(): List<FetchProvider> {
    val s = settings()
    return buildList {
      add(s.fetchProvider)
      if (s.fallback) {
        add(FetchProvider.Jina)
        add(FetchProvider.Parallel)
      }
      add(FetchProvider.Direct)
    }.distinct()
  }

  /**
   * The providers a search goes to, in order. Jina is left out while no key exists,
   * because its endpoint would only refuse. May be empty when Jina was chosen with no key
   * and fallback is off; the tool says so rather than guessing a provider.
   */
  fun searchChain(): List<SearchProvider> {
    val s = settings()
    return buildList {
      add(s.searchProvider)
      if (s.fallback) {
        add(SearchProvider.DuckDuckGo)
        add(SearchProvider.Parallel)
        add(SearchProvider.Jina)
      }
    }.distinct().filter { it != SearchProvider.Jina || keys().isNotEmpty() }
  }

  private fun jinaReaderAllowed(): Boolean =
    settings().let { it.fetchProvider == FetchProvider.Jina || it.fallback }

  private fun jinaSearchAllowed(): Boolean =
    settings().let { it.searchProvider == SearchProvider.Jina || it.fallback }

  /**
   * The caller's own route: one GET through whichever transport this gateway was
   * given, so a stubbed client in a test and the shared pool in production behave the
   * same whether or not the reader served anything.
   */
  suspend fun fetch(request: Request, maxChars: Int): WebResponse? = webGet(client, request, maxChars)

  /** Read [target] as markdown. Never throws: a useless answer is [JinaRead.Unavailable]. */
  suspend fun read(target: String, maxChars: Int, force: Boolean = false): JinaRead {
    if (!force && !jinaReaderAllowed()) return JinaRead.Unavailable("Jina.ai is turned off in Settings.")
    val raw = if (target.startsWith("//")) "https:$target" else target
    val url = raw.toHttpUrlOrNull() ?: return JinaRead.Unavailable("\"${raw.take(120)}\" is not a complete http(s) URL.")
    val walk = walk(JinaEndpoint.Reader) { spendable ->
      val requestUrl = (READER_BASE + url).toHttpUrlOrNull()
        ?: return@walk Walk.Exhausted("the reader could not be addressed for $url")
      val response = webGet(
        client,
        Request.Builder()
          .url(requestUrl)
          .header("Accept", "application/json")
          .header("X-Timeout", JINA_TARGET_TIMEOUT_SECONDS.toString())
          .authorize(spendable.identity)
          .get()
          .build(),
        maxChars,
        JINA_TIMEOUT_SECONDS
      )
        ?: return@walk Walk.Exhausted("r.jina.ai did not answer within ${JINA_TIMEOUT_SECONDS}s")
      spendable.spend(response)
      when (val fault = jinaFailure(response)) {
        null -> {
          val answer = parseReaderBody(response.text)
          if (answer.isEmpty) {
            Walk.Exhausted("the reader returned no text for $url")
          } else {
            Walk.Served(
              JinaRead.Served(
                markdown = answer.markdown,
                title = answer.title,
                finalUrl = answer.finalUrl ?: url.toString(),
                servedBy = spendable.identity.label
              )
            )
          }
        }

        is JinaFault.RateLimited -> {
          spendable.coolDown(fault.retryAfterSeconds, RATE_LIMIT_COOLDOWN_SECONDS)
          Walk.Rotated
        }

        is JinaFault.CredentialUnusable -> {
          spendable.coolDown(null, CREDENTIAL_COOLDOWN_SECONDS)
          Walk.Rotated
        }

        is JinaFault.PageRefused -> Walk.Refused(fault.status, fault.message)
        is JinaFault.ServiceDown -> Walk.Exhausted(fault.message)
      }
    }
    return when (walk) {
      is Walk.Served -> walk.value
      is Walk.Refused -> JinaRead.Refused(walk.status, walk.message)
      is Walk.Exhausted -> JinaRead.Unavailable(walk.reason)
      Walk.Rotated -> JinaRead.Unavailable("no Jina.ai identity answered")
    }
  }

  /** Find [query] on the web, as links. Unavailable whenever no key could answer. */
  suspend fun search(query: String, maxResults: Int, force: Boolean = false): JinaSearch {
    if (!force && !jinaSearchAllowed()) return JinaSearch.Unavailable("Jina.ai is turned off in Settings.")
    if (keys().isEmpty()) {
      return JinaSearch.Unavailable(
        "no Jina.ai key is configured, and s.jina.ai answers only an authenticated call."
      )
    }
    val requestUrl = "https://s.jina.ai/".toHttpUrlOrNull()?.newBuilder()
      ?.addQueryParameter("q", query)
      ?.build()
      ?: return JinaSearch.Unavailable("the search phrase could not be encoded as a query parameter.")
    val walk = walk(JinaEndpoint.Search) { spendable ->
      val response = webGet(
        client,
        Request.Builder()
          .url(requestUrl)
          .header("Accept", "application/json")
          .header("X-Timeout", JINA_TARGET_TIMEOUT_SECONDS.toString())
          .authorize(spendable.identity)
          .get()
          .build(),
        SEARCH_SNAPSHOT_CHARS,
        JINA_TIMEOUT_SECONDS
      )
        ?: return@walk Walk.Exhausted("s.jina.ai did not answer within ${JINA_TIMEOUT_SECONDS}s")
      spendable.spend(response)
      when (val fault = jinaFailure(response)) {
        null -> {
          val hits = parseSearchHits(response.text, maxResults)
          if (hits.isEmpty()) {
            Walk.Exhausted("Jina returned an answer with no links in it.")
          } else {
            Walk.Served(JinaSearch.Served(hits, spendable.identity.label))
          }
        }

        is JinaFault.RateLimited -> {
          spendable.coolDown(fault.retryAfterSeconds, RATE_LIMIT_COOLDOWN_SECONDS)
          Walk.Rotated
        }

        is JinaFault.CredentialUnusable -> {
          spendable.coolDown(null, CREDENTIAL_COOLDOWN_SECONDS)
          Walk.Rotated
        }

        is JinaFault.PageRefused -> Walk.Exhausted(fault.message)
        is JinaFault.ServiceDown -> Walk.Exhausted(fault.message)
      }
    }
    return when (walk) {
      is Walk.Served -> walk.value
      is Walk.Refused -> JinaSearch.Unavailable(walk.message)
      is Walk.Exhausted -> JinaSearch.Unavailable(walk.reason)
      Walk.Rotated -> JinaSearch.Unavailable("no Jina.ai key answered the search.")
    }
  }

  /**
   * A sentence per provider, for the settings screen to show what actually answers right
   * now rather than what the configuration claims. Makes one real call for the provider
   * chosen for each tool, and says what the rest of the chain is.
   */
  suspend fun probe(): List<String> {
    val config = settings()
    val lines = ArrayList<String>()
    lines += when (config.fetchProvider) {
      FetchProvider.Jina -> when (val read = read("https://example.com/", maxChars = 4_000, force = true)) {
        is JinaRead.Served -> "Fetch · Jina.ai: served by ${read.servedBy} (${read.markdown.length} characters of markdown)."
        is JinaRead.Refused -> "Fetch · Jina.ai: refused with ${read.status} — ${read.message}"
        is JinaRead.Unavailable -> "Fetch · Jina.ai: ${read.message}."
      }

      FetchProvider.Parallel -> when (val read = parallelRead("https://example.com/", maxChars = 4_000)) {
        is ParallelRead.Served -> "Fetch · Parallel: served (${read.markdown.length} characters of markdown)."
        is ParallelRead.Unavailable -> "Fetch · Parallel: ${read.message}."
      }

      FetchProvider.Direct -> when (val got = fetch(Request.Builder().url("https://example.com/").get().build(), 4_000)) {
        null -> "Fetch · Direct: the connection did not complete."
        else -> "Fetch · Direct: HTTP ${got.status} (${got.text.length} characters, JavaScript is not run)."
      }
    }
    lines += when (config.searchProvider) {
      SearchProvider.DuckDuckGo -> when (val found = searchDuckDuckGo("kotlin coroutines", maxResults = 3)) {
        is DuckSearch.Served -> "Search · DuckDuckGo: ${found.hits.size} results."
        is DuckSearch.Unavailable -> "Search · DuckDuckGo: ${found.message}."
      }

      SearchProvider.Parallel -> when (val found = parallelSearch("kotlin coroutines", maxResults = 3)) {
        is ParallelSearch.Served -> "Search · Parallel: ${found.hits.size} results."
        is ParallelSearch.Unavailable -> "Search · Parallel: ${found.message}."
      }

      SearchProvider.Jina -> when (val found = search("kotlin coroutines", maxResults = 3, force = true)) {
        is JinaSearch.Served -> "Search · Jina.ai: ${found.hits.size} results via ${found.servedBy}."
        is JinaSearch.Unavailable -> "Search · Jina.ai: ${found.message}"
      }
    }
    lines += if (config.fallback) {
      "Fallback is on — fetch tries ${fetchChain().joinToString(" → ") { it.label }}; " +
        "search tries ${searchChain().joinToString(" → ") { it.label }.ifEmpty { "nothing" }}."
    } else {
      "Fallback is off — only the chosen provider is asked (a fetch still ends with a direct request)."
    }
    val keyed = keys()
    lines += "Jina keys in rotation: ${keyed.count { it.origin == JinaKeyOrigin.User }} yours, " +
      "${keyed.count { it.origin == JinaKeyOrigin.App }} bundled with the app."
    return lines
  }

  // ---- DuckDuckGo ----

  /**
   * DuckDuckGo's keyless HTML results page, parsed into links. It is the live index, so
   * a page published this week is findable, which is why it is the default. A challenge
   * page or a changed layout parses to nothing and is reported as exactly that.
   */
  suspend fun searchDuckDuckGo(query: String, maxResults: Int): DuckSearch {
    val request = Request.Builder()
      .url(HttpUrl.Builder().scheme("https").host(DDG_HOST).addPathSegment("html").addQueryParameter("q", query).build())
      .header("User-Agent", BROWSER_USER_AGENT)
      .header("Accept-Language", "en")
      .get()
      .build()
    val fetched = webGet(client, request, DDG_SNAPSHOT_CHARS)
      ?: return DuckSearch.Unavailable("the connection to $DDG_HOST did not complete within ${WEB_TIMEOUT_SECONDS}s")
    if (fetched.status !in 200..299) {
      return DuckSearch.Unavailable("DuckDuckGo returned HTTP ${fetched.status} — the engine refused the request")
    }
    val hits = parseSearchResults(fetched.text, maxResults)
    if (hits.isEmpty()) {
      return DuckSearch.Unavailable(
        "DuckDuckGo returned no readable results, so the engine's page could not be parsed " +
          "(it may have served a challenge or changed markup)"
      )
    }
    return DuckSearch.Served(hits)
  }

  // ---- Parallel (free MCP) ----

  /** One id per gateway: Parallel's free tier rate-limits and correlates by it. */
  private val parallelSessionId = UUID.randomUUID().toString().replace("-", "")
  private val parallelLock = Mutex()
  private var parallelSession: String? = null
  private var parallelReady = false
  private val rpcId = AtomicInteger(10)

  /** Read [target] through Parallel's free extractor. Never throws. */
  suspend fun parallelRead(target: String, maxChars: Int): ParallelRead {
    val url = target.toHttpUrlOrNull()
      ?: return ParallelRead.Unavailable("\"${target.take(120)}\" is not a complete http(s) URL")
    return when (val reply = callParallel("web_fetch", parallelFetchArguments(url.toString(), parallelSessionId), maxChars)) {
      is McpReply.Failed -> ParallelRead.Unavailable(reply.message)
      is McpReply.Text -> {
        val answer = parseParallelFetch(reply.text)
        if (answer.isEmpty) {
          ParallelRead.Unavailable("Parallel returned no text for $url")
        } else {
          ParallelRead.Served(answer.markdown, answer.title, answer.finalUrl ?: url.toString())
        }
      }
    }
  }

  /** Find [query] through Parallel's free search. Never throws. */
  suspend fun parallelSearch(query: String, maxResults: Int): ParallelSearch =
    when (val reply = callParallel("web_search", parallelSearchArguments(query, parallelSessionId), PARALLEL_SEARCH_CHARS)) {
      is McpReply.Failed -> ParallelSearch.Unavailable(reply.message)
      is McpReply.Text -> {
        val hits = parseParallelSearch(reply.text, maxResults)
        if (hits.isEmpty()) ParallelSearch.Unavailable("Parallel returned an answer with no links in it")
        else ParallelSearch.Served(hits)
      }
    }

  /**
   * One tool call, with the MCP handshake done first and remembered. A server that
   * forgot the session (404) gets one fresh handshake before the call is given up on.
   */
  private suspend fun callParallel(tool: String, arguments: org.json.JSONObject, maxChars: Int): McpReply {
    repeat(2) { attempt ->
      if (!ensureParallelSession()) {
        return McpReply.Failed("Parallel did not accept the connection within ${PARALLEL_TIMEOUT_SECONDS}s")
      }
      val id = rpcId.incrementAndGet()
      val response = webGet(
        client,
        parallelRequest(mcpToolCallBody(id, tool, arguments)),
        maxChars + 4_000,
        PARALLEL_TIMEOUT_SECONDS
      ) ?: return McpReply.Failed("Parallel did not answer within ${PARALLEL_TIMEOUT_SECONDS}s")
      when {
        response.status == 429 -> return McpReply.Failed("Parallel's free tier is rate limited for the moment")
        response.status == 404 && attempt == 0 -> {
          resetParallelSession()
          return@repeat
        }
        response.status !in 200..299 -> return McpReply.Failed("Parallel returned HTTP ${response.status}")
      }
      return readToolReply(parseRpcMessage(response.text, id))
    }
    return McpReply.Failed("Parallel kept refusing the session")
  }

  private suspend fun ensureParallelSession(): Boolean = parallelLock.withLock {
    if (parallelReady) return@withLock true
    val init = webGet(client, parallelRequest(mcpInitializeBody()), 20_000, PARALLEL_TIMEOUT_SECONDS)
      ?: return@withLock false
    if (init.status !in 200..299) return@withLock false
    parallelSession = init.header("Mcp-Session-Id")
    // The notification is a courtesy the protocol asks for; a server that ignores it still answers.
    webGet(client, parallelRequest(mcpInitializedBody()), 4_000, PARALLEL_TIMEOUT_SECONDS)
    parallelReady = true
    true
  }

  private suspend fun resetParallelSession() = parallelLock.withLock {
    parallelReady = false
    parallelSession = null
  }

  private fun parallelRequest(body: String): Request =
    Request.Builder()
      .url(PARALLEL_MCP_URL)
      .header("Accept", "application/json, text/event-stream")
      .header("MCP-Protocol-Version", MCP_PROTOCOL_VERSION)
      .header("User-Agent", "Awaki")
      .apply { parallelSession?.let { header("Mcp-Session-Id", it) } }
      .post(body.toRequestBody("application/json".toMediaType()))
      .build()

  // ---- Rotation ----

  /**
   * Walks the pool in order and returns the first call that produced something. Each
   * outcome keeps its own meaning: a spent identity moves to the next, while a refusal
   * about the page or a dead service stops the walk, because a different key would be
   * told the same thing about the same page.
   */
  private suspend fun <T> walk(endpoint: JinaEndpoint, block: suspend (Spendable) -> Walk<T>): Walk<T> {
    val spendables = spendablesFor(endpoint)
    if (spendables.isEmpty()) return Walk.Exhausted("no Jina.ai identity is configured.")
    var rotated = 0
    var spent = 0
    for (spendable in spendables.take(MAX_IDENTITY_ATTEMPTS)) {
      if (!spendable.available()) {
        spent++
        continue
      }
      when (val outcome = block(spendable)) {
        is Walk.Served -> return outcome
        is Walk.Refused -> return outcome
        is Walk.Exhausted -> return outcome
        Walk.Rotated -> rotated++
      }
    }
    return Walk.Exhausted(
      when {
        rotated > 0 -> "every Jina.ai identity tried is rate-limited for the moment"
        spent > 0 -> "every Jina.ai identity is at its ceiling for this minute"
        else -> "no Jina.ai identity answered"
      }
    )
  }

  /**
   * Anonymous first, then the user's own keys, then the ones bundled with the build —
   * so a paid budget is spent only after the free minute is used up, and the user's key
   * is spent before the app's. Within the keyed group the start position advances on
   * every call, which is what keeps a single key from carrying the load.
   */
  private fun spendablesFor(endpoint: JinaEndpoint): List<Spendable> {
    val keyed = keys()
    val ordered = if (keyed.size > 1) {
      val start = Math.floorMod(cursor.getAndIncrement(), keyed.size)
      keyed.drop(start) + keyed.take(start)
    } else {
      keyed
    }
    val identities = if (endpoint == JinaEndpoint.Reader) listOf(JinaIdentity.ANONYMOUS) + ordered else ordered
    return identities.map { Spendable(it, ceilingFor(it, endpoint), endpoint) }
  }

  /** The keys this device can spend, user's own first, deduplicated by handle. */
  private fun keys(): List<JinaIdentity> =
    (userKeys().map { it to JinaKeyOrigin.User } + appKeys().map { it to JinaKeyOrigin.App })
      .mapNotNull { (key, origin) -> key.takeIf { it.isNotBlank() }?.let { JinaIdentity.forKey(it, origin) } }
      .distinctBy { it.label }

  /**
   * Published ceilings, used only until a response header says otherwise. They are
   * deliberately the lower of the two figures Jina states for each tier: exceeding a
   * limit costs a wasted call, staying under it costs nothing.
   */
  private fun ceilingFor(identity: JinaIdentity, endpoint: JinaEndpoint): Int = when {
    !identity.keyed -> 20
    endpoint == JinaEndpoint.Search -> 100
    else -> 500
  }

  /** One identity plus the ceiling the endpoint gives it. */
  private inner class Spendable(val identity: JinaIdentity, private val ceiling: Int, private val endpoint: JinaEndpoint) {
    fun available(): Boolean = pool.available(windowKey(), ceiling)
    fun spend(response: WebResponse) = pool.spend(windowKey(), ceiling, response)
    fun coolDown(retryAfterSeconds: Long?, defaultSeconds: Long) =
      pool.coolDown(windowKey(), retryAfterSeconds ?: defaultSeconds)

    /** Reader and search limits are separate per key, so the windows are too. */
    private fun windowKey(): String = "${identity.label}/${endpoint.name.lowercase()}"
  }

  /**
   * Per-identity minute windows. A window opens at its first spend and rolls over once
   * the window Jina described has passed; a refusal shuts it for as long as `Retry-After`
   * asks, or for this pool's own default.
   */
  private class IdentityPool(private val clock: () -> Long) {

    private class Window(var opensAt: Long, var used: Int, var ceiling: Int, var blockedUntil: Long)

    private val windows = HashMap<String, Window>()

    @Synchronized
    fun available(key: String, ceiling: Int): Boolean {
      val now = clock()
      val window = windows[key] ?: return true
      if (now - window.opensAt >= WINDOW_MILLIS) {
        window.opensAt = now
        window.used = 0
      }
      if (window.blockedUntil > now) return false
      return window.used < window.ceiling.takeIf { it > 0 } ?: ceiling
    }

    /** Counts the call, then adopts whatever the response headers revealed. */
    @Synchronized
    fun spend(key: String, ceiling: Int, response: WebResponse) {
      val now = clock()
      val window = windows.getOrPut(key) { Window(now, 0, ceiling, 0) }
      if (now - window.opensAt >= WINDOW_MILLIS) {
        window.opensAt = now
        window.used = 0
      }
      window.used++
      val signal = RateSignal.of(response.headers)
      signal.limit?.let { window.ceiling = it }
      // `remaining` is the server's own count and covers traffic this device shares an
      // address with, so it raises `used` rather than being averaged with it.
      if (signal.remaining != null && window.ceiling > 0) {
        window.used = maxOf(window.used, window.ceiling - signal.remaining)
      }
    }

    @Synchronized
    fun coolDown(key: String, seconds: Long) {
      val now = clock()
      val window = windows.getOrPut(key) { Window(now, 0, 1, 0) }
      window.blockedUntil = now + seconds * 1_000
    }
  }

  companion object {
    /**
     * A gateway with no third-party reader and no fallback: pages are fetched straight
     * from their URL and search is the DuckDuckGo scrape. This is what a test uses to
     * exercise the direct route alone.
     */
    fun direct(client: OkHttpClient? = null): WebGateway =
      WebGateway(
        client = client,
        settings = {
          WebAccessSettings(
            searchProvider = SearchProvider.DuckDuckGo,
            fetchProvider = FetchProvider.Direct,
            fallback = false
          )
        }
      )

    private const val READER_BASE = "https://r.jina.ai/"
    private const val DDG_HOST = "html.duckduckgo.com"
    private const val DDG_SNAPSHOT_CHARS = 60_000
    private const val PARALLEL_TIMEOUT_SECONDS = 30L
    private const val PARALLEL_SEARCH_CHARS = 60_000
    private const val BROWSER_USER_AGENT =
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private const val MAX_IDENTITY_ATTEMPTS = 3
    private const val WINDOW_MILLIS = 60_000L

    /** A refused minute is not retried inside itself. */
    private const val RATE_LIMIT_COOLDOWN_SECONDS = 60L

    /** A revoked, mistyped or unpaid-for key will not fix itself within a minute. */
    private const val CREDENTIAL_COOLDOWN_SECONDS = 300L

    private const val JINA_TIMEOUT_SECONDS = 25L

    /** Told to Jina so the service answers — or gives up on the page — before we do. */
    private const val JINA_TARGET_TIMEOUT_SECONDS = 20
    private const val SEARCH_SNAPSHOT_CHARS = 60_000
  }
}

/** Where one step of a pool walk ended. */
private sealed class Walk<out T> {
  data class Served<T>(val value: T) : Walk<T>()

  /** Jina answered about the page; a second key would be told the same. */
  data class Refused(val status: Int, val message: String) : Walk<Nothing>()

  /** The pool or the service could not produce an answer the caller can use. */
  data class Exhausted(val reason: String) : Walk<Nothing>()

  /** This identity is spent; move on to the next one. */
  object Rotated : Walk<Nothing>()
}
