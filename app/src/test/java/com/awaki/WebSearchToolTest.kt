package com.awaki

import com.awaki.agent.tool.ToolResult
import com.awaki.agent.tool.WebSearchTool
import com.awaki.agent.tool.parseSearchResults
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `web_search`: the step before `web_fetch` when the agent does not know the URL.
 * The contract that matters here is that it only ever reports links the engine
 * actually returned — an invented documentation URL costs the user a wrong fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebSearchToolTest {

  private var workspace: TestWorkspace? = null

  private fun ws(): TestWorkspace = newWorkspace("search").also { workspace = it }

  @After
  fun cleanUp() {
    workspace?.dispose()
    workspace = null
  }

  /** Two usable hits plus one engine redirect that carries no destination. */
  private val resultsPage = """
    <html><body><div class="results">
      <div class="result web-result">
        <h2 class="result__title"><a rel="nofollow" class="result__a"
           href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdeveloper.android.com%2Froom%2Fmigrations&rut=3a8f">Migrations | Android Developers</a></h2>
        <a rel="nofollow" class="result__snippet"
           href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdeveloper.android.com%2Froom%2Fmigrations">Room <b>migrations</b> let you change the schema across versions.</a>
      </div>
      <div class="result web-result">
        <h2 class="result__title"><a class="result__a" href="https://github.com/android/android-test/issues/1234">Crash when migrating, issue #1234</a></h2>
        <div class="result__snippet">Reported with a UNIQUE constraint failure.</div>
      </div>
      <div class="result web-result">
        <h2 class="result__title"><a class="result__a" href="//duckduckgo.com/l/?rut=tracker-only">Ad with no destination</a></h2>
      </div>
    </div></body></html>
  """.trimIndent()

  private fun search(html: String, arguments: String): ToolResult {
    val captured = mutableListOf<okhttp3.Request>()
    val client = stubHttp(200, "text/html; charset=utf-8", html) { captured.add(it) }
    val result = runBlocking {
      WebSearchTool(client).execute(args(arguments), contextFor(ws()))
    }
    assertEquals(1, captured.size)
    assertEquals("html.duckduckgo.com", captured[0].url.host)
    return result
  }

  @Test
  fun `hits are returned with their real destination and snippet`() {
    val result = search(resultsPage, """{"query": "room android database migration"}""")
    assertTrue(result.output, result.success)
    assertTrue(result.output.contains("Search results for \"room android database migration\""))
    assertTrue(result.output.contains("Migrations | Android Developers"))
    assertTrue(result.output.contains("https://developer.android.com/room/migrations"))
    assertTrue(result.output.contains("Room migrations let you change the schema"))
    assertTrue(result.output.contains("https://github.com/android/android-test/issues/1234"))
    assertEquals("2", result.metadata["results"])
  }

  @Test
  fun `a hit without a resolvable destination is dropped, not reported`() {
    val result = search(resultsPage, """{"query": "room migration"}""")
    assertFalse(result.output.contains("Ad with no destination"))
    assertFalse("a redirect wrapper must not leak into the answer", result.output.contains("duckduckgo.com/l/"))
  }

  @Test
  fun `the answer tells the model to read the page before using it`() {
    val result = search(resultsPage, """{"query": "room migration"}""")
    assertTrue(result.output.contains("web_fetch"))
  }

  @Test
  fun `max_results limits how many hits come back`() {
    val many = (1..12).joinToString("") { index ->
      """<div class="result"><a class="result__a" href="https://example.com/$index">Hit $index</a></div>"""
    }
    val result = search(many, """{"query": "anything", "max_results": 3}""")
    assertEquals("3", result.metadata["results"])
    assertTrue(result.output.contains("Hit 3"))
    assertFalse(result.output.contains("Hit 4"))

    val clamped = search(many, """{"query": "anything", "max_results": 99}""")
    assertEquals("10", clamped.metadata["results"])
  }

  @Test
  fun `parsing stops at the limit and skips anchors with no destination`() {
    val html = """
      <a class="result__a" href="javascript:void(0)">Script</a>
      <a class="result__a" href="#top">Anchor</a>
      <a class="result__a" href="/relative-only">Not Resolvable</a>
      <a class="result__a" href="https://ok.example/page">Ok</a>
    """.trimIndent()
    val parsed = parseSearchResults(html, 5)
    assertEquals(listOf("Ok"), parsed.map { it.title })
    assertEquals("https://ok.example/page", parsed[0].url)

    assertEquals(1, parseSearchResults(html, 1).size)
  }

  @Test
  fun `a page with no parseable results says so instead of guessing`() {
    val result = search("<html><body><p>Something went wrong.</p></body></html>", """{"query": "obscure flag"}""")
    assertFalse(result.success)
    assertTrue(result.error!!.contains("no readable results"))
    assertTrue(result.error!!.contains("Do not invent links"))
    assertTrue(result.error!!.contains("web_fetch"))
    assertEquals("0", result.metadata["results"])
  }

  @Test
  fun `a refused search is reported with its status`() {
    val captured = mutableListOf<okhttp3.Request>()
    val result = runBlocking {
      WebSearchTool(stubHttp(403, "text/html", "<html>blocked</html>") { captured.add(it) })
        .execute(args("""{"query": "room migration"}"""), contextFor(ws()))
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("HTTP 403"))
    assertEquals("0", result.metadata["results"])
    assertEquals(1, captured.size)
  }

  @Test
  fun `no connection points the agent at the terminal instead of a fake answer`() {
    val broken = OkHttpClient.Builder().addInterceptor { chain ->
      throw java.net.SocketTimeoutException("timeout")
    }.build()
    val result = runBlocking {
      WebSearchTool(broken).execute(args("""{"query": "room migration"}"""), contextFor(ws()))
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("run_command"))
  }

  @Test
  fun `a blank query never reaches the network`() {
    var sent = 0
    val client = stubHttp(200, "text/html", resultsPage) { sent++ }
    val result = runBlocking {
      WebSearchTool(client).execute(args("""{"query": "   "}"""), contextFor(ws()))
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("non-empty search phrase"))
    assertEquals(0, sent)
  }

  @Test
  fun `the query is sent as a search parameter, not pasted into a url`() {
    val captured = mutableListOf<okhttp3.Request>()
    runBlocking {
      WebSearchTool(stubHttp(200, "text/html", resultsPage) { captured.add(it) })
        .execute(args("""{"query": "a b&c \"d\""}"""), contextFor(ws()))
    }
    assertEquals("a b&c \"d\"", captured[0].url.queryParameter("q"))
  }
}
