package com.awaki

import com.awaki.agent.web.JinaRead
import com.awaki.agent.web.JinaSearch
import com.awaki.agent.web.WebGateway
import com.awaki.agent.web.isJinaHost
import com.awaki.agent.web.parseReaderBody
import com.awaki.agent.web.parseSearchHits
import com.awaki.agent.web.readAppKeyList
import com.awaki.data.local.FetchProvider
import com.awaki.data.local.SearchProvider
import com.awaki.data.local.WebAccessSettings
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Jina.ai key pool: which identity is asked, in what order, and when the pool
 * stops asking. A web tool's own fetch is covered by the tool tests; what belongs here
 * is the policy that decides whether a call is made with no key, the user's key, or the
 * app's — and that an exhausted tier is skipped rather than knocked on again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebGatewayTest {

  private var now = 1_000_000L
  private val clock: () -> Long = { now }

  private val readerJson =
    """{"code":200,"status":20000,"data":{"title":"Room migrations","""" +
      """url":"https://developer.android.com/room/migrations","""" +
      """content":"# Migrations\n\nUse a Migration to change the schema."}}"""

  private val searchJson =
    """{"code":200,"status":20000,"data":{"title":"Search: room","content":"",
       "associatedLinks":[
         {"title":"Migrations | Android Developers","url":"https://developer.android.com/room/migrations","snippet":"Room migrations change the schema."},
         {"title":"Issue #1234","url":"https://github.com/android/android-test/issues/1200","description":"UNIQUE constraint failure."}
       ]}}"""

  private val rateLimitedJson =
    """{"data":null,"code":429,"name":"RateLimitExceedError","status":42900,"message":"Rate limit exceeded"}"""

  private val refusedJson =
    """{"data":null,"code":404,"name":"ResourceNotFoundError","status":40400,"message":"Target URL not found"}"""

  private class Recorder {
    val requests = mutableListOf<Request>()
    fun capture(request: Request) {
      requests.add(request)
    }

    /** The bearer token each call went out with, in order; null means anonymous. */
    val credentials: List<String?>
      get() = requests.map { it.header("Authorization") }
  }

  private fun gateway(
    vararg answers: StubAnswer,
    recorder: Recorder,
    userKeys: List<String> = emptyList(),
    appKeys: List<String> = emptyList(),
    preferJina: Boolean = true
  ): WebGateway = WebGateway(
    client = stubHttpScripted(*answers, capture = { recorder.capture(it) }),
    settings = {
      WebAccessSettings(
        searchProvider = SearchProvider.Jina,
        fetchProvider = if (preferJina) FetchProvider.Jina else FetchProvider.Direct,
        fallback = preferJina
      )
    },
    userKeys = { userKeys },
    // Never left to the build's own value: CI may set JINA_API_KEYS, and a test that
    // asserts "no key configured" must not pass or fail on the environment.
    appKeys = { appKeys },
    clock = clock
  )

  @Test
  fun `the free anonymous tier is tried before any key`() {
    val sent = Recorder()
    val read = runBlocking {
      gateway(StubAnswer(200, readerJson), recorder = sent, userKeys = listOf("jina_user"))
        .read("https://developer.android.com/room/migrations", maxChars = 8_000)
    }
    assertTrue(read.toString(), read is JinaRead.Served)
    assertEquals(1, sent.requests.size)
    assertEquals("r.jina.ai", sent.requests[0].url.host)
    assertNull("the free tier is the point: no credential on the first call", sent.credentials[0])
    assertTrue(sent.requests[0].url.encodedPath.contains("developer.android.com"))
    read as JinaRead.Served
    assertEquals("anonymous", read.servedBy)
    assertTrue(read.markdown.contains("Use a Migration"))
    assertEquals("Room migrations", read.title)
    assertEquals("https://developer.android.com/room/migrations", read.finalUrl)
  }

  @Test
  fun `a refused free tier rotates to the user's own key`() {
    val sent = Recorder()
    val read = runBlocking {
      gateway(
        StubAnswer(429, rateLimitedJson),
        StubAnswer(200, readerJson),
        recorder = sent,
        userKeys = listOf("jina_user")
      ).read("https://developer.android.com/room", maxChars = 8_000)
    }
    assertTrue(read.toString(), read is JinaRead.Served)
    assertEquals(listOf(null, "Bearer jina_user"), sent.credentials)
  }

  @Test
  fun `the keys bundled with the app are spent after the user's own`() {
    val sent = Recorder()
    runBlocking {
      gateway(
        StubAnswer(429, rateLimitedJson),
        StubAnswer(429, rateLimitedJson),
        StubAnswer(200, readerJson),
        recorder = sent,
        userKeys = listOf("jina_user"),
        appKeys = listOf("jina_app")
      ).read("https://developer.android.com/room", maxChars = 8_000)
    }
    assertEquals(listOf(null, "Bearer jina_user", "Bearer jina_app"), sent.credentials)
  }

  @Test
  fun `a page the reader refuses is not retried with another key`() {
    val sent = Recorder()
    val read = runBlocking {
      gateway(
        StubAnswer(404, refusedJson),
        recorder = sent,
        userKeys = listOf("jina_one", "jina_two")
      ).read("https://developer.android.com/gone", maxChars = 8_000)
    }
    assertTrue(read.toString(), read is JinaRead.Refused)
    read as JinaRead.Refused
    assertEquals(404, read.status)
    assertTrue(read.message, read.message.contains("not found"))
    assertEquals("a refusal about the page costs the pool one call, not three", 1, sent.requests.size)
  }

  @Test
  fun `a rate-limited tier is skipped while it says it is cooling down`() {
    val sent = Recorder()
    val pool = gateway(
      StubAnswer(429, rateLimitedJson, headers = mapOf("Retry-After" to "5")),
      StubAnswer(200, readerJson),
      recorder = sent,
      userKeys = listOf("jina_user")
    )
    runBlocking { pool.read("https://developer.android.com/room", maxChars = 8_000) }
    assertEquals(2, sent.requests.size)

    now += 2_000
    sent.requests.clear()
    runBlocking { pool.read("https://developer.android.com/room/migrations", maxChars = 8_000) }
    assertEquals("the anonymous tier is still cooling, so the key answers first", listOf("Bearer jina_user"), sent.credentials)

    now += 4_000
    sent.requests.clear()
    runBlocking { pool.read("https://developer.android.com/room/migrations", maxChars = 8_000) }
    assertEquals("once Retry-After has passed the free tier is tried again", listOf<String?>(null), sent.credentials)
  }

  @Test
  fun `the ceiling a response states is the ceiling the pool keeps`() {
    val sent = Recorder()
    val oneCallOnly = StubAnswer(
      status = 200,
      body = readerJson,
      headers = mapOf("x-ratelimit-limit" to "1, 1;w=60", "x-ratelimit-remaining" to "0")
    )
    val pool = gateway(oneCallOnly, oneCallOnly, recorder = sent, userKeys = listOf("jina_user"))
    runBlocking { pool.read("https://developer.android.com/room", maxChars = 8_000) }
    assertEquals(listOf<String?>(null), sent.credentials)

    sent.requests.clear()
    runBlocking { pool.read("https://developer.android.com/room", maxChars = 8_000) }
    assertEquals("the server said the minute is spent, so the key is asked", listOf("Bearer jina_user"), sent.credentials)
  }

  @Test
  fun `search refuses to call the service when no key could authenticate it`() {
    val sent = Recorder()
    val found = runBlocking {
      gateway(StubAnswer(200, searchJson), recorder = sent).search("room migration", maxResults = 3)
    }
    assertTrue(found.toString(), found is JinaSearch.Unavailable)
    assertTrue(found.toString(), (found as JinaSearch.Unavailable).message.contains("no Jina.ai key"))
    assertEquals("s.jina.ai refuses an anonymous call, so it is not made", 0, sent.requests.size)
  }

  @Test
  fun `search results come back as links the reader can open`() {
    val sent = Recorder()
    val found = runBlocking {
      gateway(StubAnswer(200, searchJson), recorder = sent, userKeys = listOf("jina_user"))
        .search("room migration", maxResults = 5)
    }
    assertTrue(found.toString(), found is JinaSearch.Served)
    found as JinaSearch.Served
    assertEquals("s.jina.ai", sent.requests[0].url.host)
    assertEquals("room migration", sent.requests[0].url.queryParameter("q"))
    assertEquals(
      listOf("https://developer.android.com/room/migrations", "https://github.com/android/android-test/issues/1200"),
      found.hits.map { it.url }
    )
    assertEquals("Migrations | Android Developers", found.hits[0].title)
    assertEquals("UNIQUE constraint failure.", found.hits[1].snippet)
  }

  @Test
  fun `keys take turns so one key is not the whole app's budget`() {
    val sent = Recorder()
    val pool = gateway(
      StubAnswer(200, searchJson),
      recorder = sent,
      userKeys = listOf("jina_first", "jina_second")
    )
    runBlocking { pool.search("room migration", maxResults = 3) }
    runBlocking { pool.search("workmanager constraints", maxResults = 3) }
    val used = sent.credentials
    assertEquals("two calls, two different keys", 2, used.distinct().size)
    assertTrue(used.containsAll(listOf("Bearer jina_first", "Bearer jina_second")))
  }

  @Test
  fun `a turned off pool never reaches the service`() {
    val sent = Recorder()
    val read = runBlocking {
      gateway(StubAnswer(200, readerJson), recorder = sent, preferJina = false)
        .read("https://developer.android.com/room", maxChars = 8_000)
    }
    assertTrue(read.toString(), read is JinaRead.Unavailable)
    assertEquals(0, sent.requests.size)
  }

  @Test
  fun `a credential is only ever sent to jina's own hosts`() {
    assertTrue(isJinaHost("jina.ai"))
    assertTrue(isJinaHost("r.jina.ai"))
    assertTrue(isJinaHost("s.jina.ai"))
    assertFalse("a redirect target that merely ends in the name is not Jina", isJinaHost("r.jina.ai.evil.example"))
    assertFalse(isJinaHost("evil.example"))
  }

  @Test
  fun `both reader shapes are read`() {
    val structured = parseReaderBody(readerJson)
    assertEquals("Room migrations", structured.title)
    assertTrue(structured.markdown.contains("# Migrations"))
    assertFalse(structured.isEmpty)

    val plain = parseReaderBody(
      """
        Title: Example Domain

        URL Source: https://example.com/

        Markdown Content:
        This domain is for use in documentation examples.
      """.trimIndent()
    )
    assertEquals("Example Domain", plain.title)
    assertEquals("https://example.com/", plain.finalUrl)
    assertTrue(plain.markdown.contains("documentation examples"))

    assertTrue("a JSON answer with no content is empty, not a guess", parseReaderBody("""{"data":{}}""").isEmpty)
  }

  @Test
  fun `a prose answer is mined for its citations and their sites`() {
    val prose = """{"code":200,"data":{"content":"Room migrations let you change a schema [[1]](https://developer.android.com/room/migrations): use a Migration. Reports of failures appear in [[2]](https://github.com/android/android-test/issues/1200). More at https://jina.ai/."}}"""
    val hits = parseSearchHits(prose, limit = 5)
    assertEquals(2, hits.size)
    assertEquals("developer.android.com", hits[0].title)
    assertTrue(hits[0].snippet.contains("use a Migration"))
    assertFalse("the service's own page is not a result", hits.any { it.url.contains("jina.ai") })
  }

  @Test
  fun `an answer with no links yields no hits`() {
    assertEquals(emptyList<com.awaki.agent.web.WebHit>(), parseSearchHits("""{"data":{"content":"no links here"}}""", 5))
    assertEquals(emptyList<com.awaki.agent.web.WebHit>(), parseSearchHits("plain prose without a URL", 5))
  }

  @Test
  fun `a pasted key list becomes the keys it names`() {
    assertEquals(
      listOf("jina_alpha", "jina_beta"),
      readAppKeyList("Bearer jina_alpha, jina_beta\n")
    )
    assertEquals("an unset build holds no key at all", emptyList<String>(), readAppKeyList(""))
    assertEquals(emptyList<String>(), readAppKeyList(null))
    assertEquals(listOf("jina_alpha"), readAppKeyList("jina_alpha # a comment that is not a key"))
  }

  @Test
  fun `the direct route is a plain fetch with no credential on it`() {
    val sent = Recorder()
    val pool = gateway(
      StubAnswer(200, "<html><body>Room migrations</body></html>", contentType = "text/html"),
      recorder = sent,
      userKeys = listOf("jina_user")
    )
    val response = runBlocking {
      pool.fetch(Request.Builder().url("https://developer.android.com/room").get().build(), maxChars = 1_000)
    }
    assertEquals("a fetch goes to the page, not to the reader",
      listOf("developer.android.com"), sent.requests.map { it.url.host })
    assertNull("nothing to authenticate against a third-party site", sent.credentials[0])
    assertEquals(200, response?.status)
    assertTrue(response?.text?.contains("Room migrations") == true)
    assertFalse(response?.truncated ?: true)
  }
}
