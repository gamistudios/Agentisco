package com.awaki

import com.awaki.agent.web.ParallelRead
import com.awaki.agent.web.ParallelSearch
import com.awaki.agent.web.WebGateway
import com.awaki.agent.web.parseParallelSearch
import com.awaki.data.local.FetchProvider
import com.awaki.data.local.SearchProvider
import com.awaki.data.local.WebAccessSettings
import com.awaki.data.local.WebAccessStore
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parallel's free MCP as the default provider, and the settings that choose between
 * providers. Nothing here reaches the network: the server's replies are scripted, in the
 * three shapes a Streamable HTTP server may answer with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParallelWebTest {

  private val initialized = StubAnswer(
    200,
    """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{}}}""",
    headers = mapOf("Mcp-Session-Id" to "session-123")
  )
  private val acknowledged = StubAnswer(202, "")

  private fun toolResult(id: Int, text: String): String =
    JSONObject()
      .put("jsonrpc", "2.0")
      .put("id", id)
      .put("result", JSONObject().put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text))))
      .toString()

  private fun gateway(vararg answers: StubAnswer, capture: (Request) -> Unit = {}): WebGateway =
    WebGateway(
      client = stubHttpScripted(*answers, capture = capture),
      settings = { WebAccessSettings(searchProvider = SearchProvider.Parallel, fetchProvider = FetchProvider.Parallel) },
      appKeys = { emptyList() }
    )

  @Test
  fun `a search performs the handshake and then asks for the query`() {
    val sent = mutableListOf<Request>()
    val body = """{"results":[{"url":"https://kotlinlang.org/docs/coroutines-guide.html","title":"Coroutines guide","excerpts":["Kotlin coroutines make async code sequential."]}]}"""
    val found = runBlocking {
      gateway(initialized, acknowledged, StubAnswer(200, toolResult(11, body)), capture = { sent.add(it) })
        .parallelSearch("kotlin coroutines", maxResults = 5)
    }
    assertTrue(found.toString(), found is ParallelSearch.Served)
    val hit = (found as ParallelSearch.Served).hits.single()
    assertEquals("https://kotlinlang.org/docs/coroutines-guide.html", hit.url)
    assertEquals("Coroutines guide", hit.title)
    assertTrue(hit.snippet.startsWith("Kotlin coroutines"))

    assertEquals(3, sent.size)
    assertTrue(sent.all { it.url.toString() == "https://search.parallel.ai/mcp" && it.method == "POST" })
    assertEquals("the session the server handed out is echoed", null, sent[0].header("Mcp-Session-Id"))
    assertEquals("session-123", sent[2].header("Mcp-Session-Id"))
    assertTrue(sent.all { it.header("Authorization") == null })
    val call = JSONObject(okio.Buffer().also { sent[2].body!!.writeTo(it) }.readUtf8())
    assertEquals("tools/call", call.getString("method"))
    assertEquals("web_search", call.getJSONObject("params").getString("name"))
    val arguments = call.getJSONObject("params").getJSONObject("arguments")
    assertEquals("kotlin coroutines", arguments.getJSONArray("search_queries").getString(0))
    assertTrue(arguments.getString("session_id").length >= 32)
  }

  @Test
  fun `the handshake is done once and reused`() {
    val sent = mutableListOf<Request>()
    val body = """{"results":[{"url":"https://kotlinlang.org/docs/home.html","title":"Docs"}]}"""
    runBlocking {
      val web = gateway(
        initialized, acknowledged,
        StubAnswer(200, toolResult(11, body)), StubAnswer(200, toolResult(12, body)),
        capture = { sent.add(it) }
      )
      web.parallelSearch("kotlin docs", 3)
      web.parallelSearch("kotlin docs again", 3)
    }
    assertEquals("initialize, initialized, call, call", 4, sent.size)
  }

  @Test
  fun `an event stream reply is read the same as a json one`() {
    val sse = "event: message\ndata: " + toolResult(11, """{"results":[{"url":"https://example.org/a","title":"A"}]}""") + "\n\n"
    val found = runBlocking {
      gateway(initialized, acknowledged, StubAnswer(200, sse, "text/event-stream")).parallelSearch("a thing", 3)
    }
    assertTrue(found.toString(), found is ParallelSearch.Served)
  }

  @Test
  fun `a reply that is only markdown still yields its links`() {
    val markdown = "1. [Room migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions) - Change the schema."
    val hits = parseParallelSearch(markdown, 5)
    assertEquals("https://developer.android.com/training/data-storage/room/migrating-db-versions", hits.single().url)
    assertEquals("Room migrations", hits.single().title)
  }

  @Test
  fun `a rate limited free tier is reported rather than retried into a wall`() {
    val sent = mutableListOf<Request>()
    val found = runBlocking {
      gateway(initialized, acknowledged, StubAnswer(429, "slow down"), capture = { sent.add(it) })
        .parallelSearch("kotlin", 3)
    }
    assertTrue(found.toString(), found is ParallelSearch.Unavailable)
    assertTrue((found as ParallelSearch.Unavailable).message.contains("rate limited"))
    assertEquals(3, sent.size)
  }

  @Test
  fun `a tool error is surfaced as unavailable`() {
    val error = """{"jsonrpc":"2.0","id":11,"result":{"isError":true,"content":[{"type":"text","text":"invalid url"}]}}"""
    val read = runBlocking {
      gateway(initialized, acknowledged, StubAnswer(200, error)).parallelRead("https://example.com/", 4_000)
    }
    assertTrue(read.toString(), read is ParallelRead.Unavailable)
    assertTrue((read as ParallelRead.Unavailable).message.contains("invalid url"))
  }

  @Test
  fun `a page read prefers the full content over excerpts`() {
    val body = """{"results":[{"url":"https://example.com/","title":"Example","full_content":"# Example\n\nFull page.","excerpts":["short"]}]}"""
    val read = runBlocking {
      gateway(initialized, acknowledged, StubAnswer(200, toolResult(11, body))).parallelRead("https://example.com/", 8_000)
    }
    assertTrue(read.toString(), read is ParallelRead.Served)
    val served = read as ParallelRead.Served
    assertEquals("# Example\n\nFull page.", served.markdown)
    assertEquals("Example", served.title)
  }

  @Test
  fun `a forgotten session is renegotiated once`() {
    val sent = mutableListOf<Request>()
    val body = """{"results":[{"url":"https://example.org/a","title":"A"}]}"""
    val found = runBlocking {
      gateway(
        initialized, acknowledged, StubAnswer(404, "no such session"),
        initialized, acknowledged, StubAnswer(200, toolResult(12, body)),
        capture = { sent.add(it) }
      ).parallelSearch("a thing", 3)
    }
    assertTrue(found.toString(), found is ParallelSearch.Served)
    assertEquals(6, sent.size)
  }

  // ---- Which providers a tool asks, and in what order ----

  private fun chains(settings: WebAccessSettings, keys: List<String> = emptyList()) =
    WebGateway(settings = { settings }, userKeys = { keys }, appKeys = { emptyList() })

  @Test
  fun `the defaults are Parallel for both tools`() {
    val defaults = WebAccessSettings()
    assertEquals(SearchProvider.Parallel, defaults.searchProvider)
    assertEquals(FetchProvider.Parallel, defaults.fetchProvider)
    assertTrue(defaults.fallback)
  }

  @Test
  fun `with fallback on every provider is tried, and a fetch always ends direct`() {
    val web = chains(WebAccessSettings())
    assertEquals(listOf(SearchProvider.Parallel, SearchProvider.DuckDuckGo), web.searchChain())
    assertEquals(listOf(FetchProvider.Parallel, FetchProvider.Jina, FetchProvider.Direct), web.fetchChain())
  }

  @Test
  fun `a Jina key puts Jina search into the chain`() {
    val web = chains(WebAccessSettings(), keys = listOf("jina_user_key"))
    assertEquals(
      listOf(SearchProvider.Parallel, SearchProvider.DuckDuckGo, SearchProvider.Jina),
      web.searchChain()
    )
  }

  @Test
  fun `the chosen provider goes first and without fallback goes alone`() {
    val picked = chains(WebAccessSettings(searchProvider = SearchProvider.Parallel, fetchProvider = FetchProvider.Direct))
    assertEquals(SearchProvider.Parallel, picked.searchChain().first())
    assertEquals(FetchProvider.Direct, picked.fetchChain().first())

    val alone = chains(WebAccessSettings(searchProvider = SearchProvider.Parallel, fetchProvider = FetchProvider.Parallel, fallback = false))
    assertEquals(listOf(SearchProvider.Parallel), alone.searchChain())
    assertEquals(listOf(FetchProvider.Parallel, FetchProvider.Direct), alone.fetchChain())
  }

  @Test
  fun `Jina search without a key and without fallback leaves nothing to ask`() {
    val web = chains(WebAccessSettings(searchProvider = SearchProvider.Jina, fallback = false))
    assertTrue(web.searchChain().isEmpty())
    assertFalse(web.searchPreferred)
  }

  // ---- What survives an upgrade ----

  @Test
  fun `a config from before providers existed keeps its meaning`() {
    val on = WebAccessStore.readSettings(JSONObject("""{"preferJina":true}"""))
    assertEquals(WebAccessSettings(), on)

    val off = WebAccessStore.readSettings(JSONObject("""{"preferJina":false}"""))
    assertEquals(FetchProvider.Direct, off.fetchProvider)
    assertFalse("off meant no third party was ever asked", off.fallback)

    val chosen = WebAccessStore.readSettings(
      JSONObject("""{"searchProvider":"Parallel","fetchProvider":"Direct","fallback":false}""")
    )
    assertEquals(SearchProvider.Parallel, chosen.searchProvider)
    assertEquals(FetchProvider.Direct, chosen.fetchProvider)
    assertFalse(chosen.fallback)

    assertEquals("an unreadable name falls back to the default", WebAccessSettings(),
      WebAccessStore.readSettings(JSONObject("""{"searchProvider":"Bing","fetchProvider":"Nope"}""")))
  }
}
