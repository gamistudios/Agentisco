package com.awaki

import com.awaki.agent.tool.WebFetchTool
import com.awaki.agent.tool.stripHtml
import com.awaki.agent.web.WebGateway
import com.awaki.data.local.FetchProvider
import com.awaki.data.local.WebAccessSettings
import com.awaki.data.local.WebAccessStore
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `web_fetch`: the agent's only route to documentation it does not already have.
 * It may never invent page content, and a cut-off has to be stated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebFetchToolTest {

  private var workspace: TestWorkspace? = null

  private fun ws(): TestWorkspace = newWorkspace("web").also { workspace = it }

  @After
  fun cleanUp() {
    workspace?.dispose()
    workspace = null
  }

  @Test
  fun `markup is stripped and only readable text is kept`() {
    val html = """
      <html><head><script>var secret = 1;</script><style>.x{color:red}</style></head>
      <body><h1>Install</h1><p>Run <code>npm i thing</code> &amp; enjoy.</p>
      <ul><li>step one</li><li>step two</li></ul></body></html>
    """.trimIndent()
    val result = runBlocking {
      WebFetchTool(WebGateway.direct(stubHttp(200, "text/html; charset=utf-8", html)))
        .execute(args("""{"url": "https://example.com/docs"}"""), contextFor(ws()))
    }
    assertTrue(result.error ?: "", result.success)
    assertTrue(result.output.contains("Install"))
    assertTrue(result.output.contains("npm i thing & enjoy"))
    assertTrue(result.output.contains("- step one"))
    assertFalse(result.output.contains("color:red"))
    assertFalse(result.output.contains("var secret"))
    assertTrue(result.output.contains("HTTP 200"))
    assertEquals("200", result.metadata["status"])
  }

  @Test
  fun `an error page is returned but the call is marked failed`() {
    val result = runBlocking {
      WebFetchTool(WebGateway.direct(stubHttp(404, "text/html", "<h1>Not Found</h1><p>check the url</p>")))
        .execute(args("""{"url": "https://example.com/missing"}"""), contextFor(ws()))
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("HTTP 404"))
    assertTrue(result.output.contains("Not Found"))
    assertTrue(result.output.contains("check the url"))
  }

  @Test
  fun `a binary body is refused instead of dumped into the conversation`() {
    val result = runBlocking {
      WebFetchTool(WebGateway.direct(stubHttp(200, "application/pdf", "%PDF-1.4 garbage bytes")))
        .execute(args("""{"url": "https://example.com/manual.pdf"}"""), contextFor(ws()))
    }
    assertTrue(result.output.contains("refused"))
    assertTrue(result.output.contains("run_command"))
  }

  @Test
  fun `only complete http urls are fetched`() {
    val ctx = contextFor(ws())
    val noScheme = runBlocking {
      WebFetchTool(WebGateway.direct(stubHttp(200, "text/plain", "ok"))).execute(args("""{"url": "example.com/docs"}"""), ctx)
    }
    assertFalse(noScheme.success)
    assertTrue(noScheme.error!!.contains("Not a fetchable URL"))

    val fileUrl = runBlocking {
      WebFetchTool(WebGateway.direct(stubHttp(200, "text/plain", "ok"))).execute(args("""{"url": "file:///etc/passwd"}"""), ctx)
    }
    assertFalse(fileUrl.success)
    assertTrue(fileUrl.error!!.contains("http and https"))
    assertTrue(fileUrl.error!!.contains("file:///etc/passwd"))
  }

  @Test
  fun `a connection failure is explained with the way forward`() {
    val broken = OkHttpClient.Builder().addInterceptor { chain ->
      throw java.net.UnknownHostException("example.com")
    }.build()
    val result = runBlocking {
      WebFetchTool(WebGateway.direct(broken)).execute(args("""{"url": "https://example.com/down"}"""), contextFor(ws()))
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("Could not fetch"))
    assertTrue(result.error!!.contains("run_command"))
  }

  @Test
  fun `entity decoding leaves ampersands intact`() {
    assertEquals("a & b", "<p>a &amp; b</p>".stripHtml())
    assertEquals("<tag>", "&lt;tag&gt;".stripHtml())
  }

  // ---- The Jina.ai tier in front of that route ----

  /**
   * A gateway over a scripted client. The app's own keys are forced empty: a build on CI
   * may carry them, and these tests are about which tier answers, not about the secret.
   * Jina is picked explicitly because that is the route under test; the shipped default
   * (Parallel) is proven separately, in ParallelWebTest and the default-provider tests.
   */
  private fun pool(
    vararg answers: StubAnswer,
    userKeys: List<String> = emptyList(),
    capture: (Request) -> Unit = {}
  ): WebGateway = WebGateway(
    client = stubHttpScripted(*answers, capture = capture),
    settings = { WebAccessSettings(fetchProvider = FetchProvider.Jina, fallback = false) },
    userKeys = { userKeys },
    appKeys = { emptyList() }
  )

  private val readerAnswer = StubAnswer(
    200,
    """{"code":200,"data":{"title":"Room migrations","url":"https://developer.android.com/room/migrations","""" +
      """content":"# Migrations\n\nUse a Migration to change the schema."}}"""
  )

  @Test
  fun `the reader's markdown is what the model reads when it answers`() {
    val sent = mutableListOf<Request>()
    val result = runBlocking {
      pool(readerAnswer, capture = { sent.add(it) })
        .let { WebFetchTool(it) }
        .execute(args("""{"url": "https://developer.android.com/room/migrations"}"""), contextFor(ws()))
    }
    assertTrue(result.output, result.success)
    assertEquals("the free tier is tried first, so the reader is the only host called",
      listOf("r.jina.ai"), sent.map { it.url.host })
    assertTrue(result.output.contains("markdown from Jina.ai"))
    assertTrue(result.output.contains("Room migrations"))
    assertTrue(result.output.contains("Use a Migration"))
    assertEquals("jina.ai", result.metadata["via"])
    assertEquals("anonymous", result.metadata["served_by"])
  }

  @Test
  fun `a reader at its ceiling falls back to fetching the page directly`() {
    val sent = mutableListOf<Request>()
    val result = runBlocking {
      WebFetchTool(
        pool(
          StubAnswer(429, """{"code":429,"name":"RateLimitExceedError","status":42900,"message":"Rate limit exceeded"}"""),
          StubAnswer(200, "<html><body><h1>Migrations</h1><p>Change the schema.</p></body></html>", "text/html"),
          capture = { sent.add(it) }
        )
      ).execute(args("""{"url": "https://developer.android.com/room"}"""), contextFor(ws()))
    }
    assertTrue(result.output, result.success)
    assertEquals("a spent free tier does not end the call — the page itself is read next",
      listOf("r.jina.ai", "developer.android.com"), sent.map { it.url.host })
    assertTrue(result.output.contains("Migrations"))
    assertTrue(result.output.contains("HTTP 200"))
    assertEquals("direct", result.metadata["via"])
  }

  @Test
  fun `a user's key answers when the free tier is spent`() {
    val sent = mutableListOf<Request>()
    val result = runBlocking {
      WebFetchTool(
        pool(
          StubAnswer(429, """{"code":429,"name":"RateLimitExceedError","status":42900,"message":"Rate limit exceeded"}"""),
          readerAnswer,
          userKeys = listOf("jina_user_key"),
          capture = { sent.add(it) }
        )
      ).execute(args("""{"url": "https://developer.android.com/room/migrations"}"""), contextFor(ws()))
    }
    assertTrue(result.output, result.success)
    assertEquals(
      "the second call is the key's, and it is the only one carrying a credential",
      listOf(null, "Bearer jina_user_key"),
      sent.map { it.header("Authorization") }
    )
    assertTrue("the key's answer is what the model reads: ${result.output}", result.output.contains("Room migrations"))
    assertEquals(WebAccessStore.fingerprintOf("jina_user_key"), result.metadata["served_by"])
  }

  @Test
  fun `Parallel is the reader when nothing is configured`() {
    val sent = mutableListOf<Request>()
    val toolReply = """{"jsonrpc":"2.0","id":11,"result":{"content":[{"type":"text","text":"{\"results\":[{\"url\":\"https://developer.android.com/room\",\"title\":\"Room\",\"full_content\":\"# Room\\n\\nChange the schema.\"}]}"}]}}"""
    val result = runBlocking {
      WebFetchTool(
        WebGateway(
          client = stubHttpScripted(
            StubAnswer(
              200,
              """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{}}}""",
              headers = mapOf("Mcp-Session-Id" to "session-1")
            ),
            StubAnswer(202, ""),
            StubAnswer(200, toolReply),
            capture = { sent.add(it) }
          ),
          settings = { WebAccessSettings() },
          appKeys = { emptyList() }
        )
      ).execute(args("""{"url": "https://developer.android.com/room"}"""), contextFor(ws()))
    }
    assertTrue(result.output, result.success)
    assertEquals(
      listOf("search.parallel.ai", "search.parallel.ai", "search.parallel.ai"),
      sent.map { it.url.host }
    )
    assertEquals("parallel", result.metadata["via"])
    assertTrue(result.output.contains("Change the schema."))
  }

  @Test
  fun `when neither route reaches the page the model is told both reasons`() {
    val client = OkHttpClient.Builder().addInterceptor { chain ->
      val request = chain.request()
      if (request.url.host == "r.jina.ai") {
        okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
          .code(429).message("Error")
          .header("Content-Type", "application/json")
          .body("""{"code":429,"name":"RateLimitExceedError","status":42900,"message":"Rate limit exceeded"}"""
            .toResponseBody("application/json".toMediaType()))
          .build()
      } else {
        throw java.net.UnknownHostException(request.url.host)
      }
    }.build()
    val result = runBlocking {
      WebGateway(
        client = client,
        settings = { WebAccessSettings(fetchProvider = FetchProvider.Jina, fallback = false) },
        appKeys = { emptyList() }
      ).let { WebFetchTool(it) }
        .execute(args("""{"url": "https://developer.android.com/room"}"""), contextFor(ws()))
    }
    assertFalse(result.success)
    val error = result.error!!
    assertTrue(error, error.contains("Could not fetch"))
    assertTrue(error.contains("rate-limited"))
    assertTrue(error.contains("run_command"))
  }
}
