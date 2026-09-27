package com.agentisco

import com.agentisco.agent.tool.WebFetchTool
import com.agentisco.agent.tool.stripHtml
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
      WebFetchTool(stubHttp(200, "text/html; charset=utf-8", html))
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
      WebFetchTool(stubHttp(404, "text/html", "<h1>Not Found</h1><p>check the url</p>"))
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
      WebFetchTool(stubHttp(200, "application/pdf", "%PDF-1.4 garbage bytes"))
        .execute(args("""{"url": "https://example.com/manual.pdf"}"""), contextFor(ws()))
    }
    assertTrue(result.output.contains("refused"))
    assertTrue(result.output.contains("run_command"))
  }

  @Test
  fun `only complete http urls are fetched`() {
    val ctx = contextFor(ws())
    val noScheme = runBlocking {
      WebFetchTool(stubHttp(200, "text/plain", "ok")).execute(args("""{"url": "example.com/docs"}"""), ctx)
    }
    assertFalse(noScheme.success)
    assertTrue(noScheme.error!!.contains("Not a fetchable URL"))

    val fileUrl = runBlocking {
      WebFetchTool(stubHttp(200, "text/plain", "ok")).execute(args("""{"url": "file:///etc/passwd"}"""), ctx)
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
      WebFetchTool(broken).execute(args("""{"url": "https://example.com/down"}"""), contextFor(ws()))
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
}
