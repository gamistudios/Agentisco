package com.agentisco

import com.agentisco.workspace.buildrun.BuildRunPortParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildRunPortParserTest {

  @Test
  fun `vite local url yields a localhost endpoint`() {
    val endpoints = BuildRunPortParser.parse("  Local:   http://localhost:5173/")
    assertEquals(1, endpoints.size)
    assertEquals(5173, endpoints[0].port)
    assertEquals("http://localhost:5173", endpoints[0].url)
  }

  @Test
  fun `explicit loopback url is detected`() {
    val endpoints = BuildRunPortParser.parse("Uvicorn running on http://127.0.0.1:8000 (Press CTRL+C to quit)")
    assertEquals(listOf(8000), endpoints.map { it.port })
    assertEquals("http://127.0.0.1:8000", endpoints[0].url)
  }

  @Test
  fun `wildcard bind is normalized to loopback`() {
    val endpoints = BuildRunPortParser.parse("Listening on 0.0.0.0:3000")
    assertEquals(listOf(3000), endpoints.map { it.port })
    assertEquals("http://127.0.0.1:3000", endpoints[0].url)
  }

  @Test
  fun `keyword fallback finds ports without a url`() {
    val endpoints = BuildRunPortParser.parse("Server started, listening on port 8080")
    assertEquals(listOf(8080), endpoints.map { it.port })
  }

  @Test
  fun `lines without endpoints stay empty`() {
    assertTrue(BuildRunPortParser.parse("Compiled successfully in 1200 ms").isEmpty())
    assertTrue(BuildRunPortParser.parse("Network: use --host to expose").isEmpty())
  }

  @Test
  fun `localhost without a port is ignored`() {
    assertTrue(BuildRunPortParser.parse("connect to localhost to continue").isEmpty())
  }

  @Test
  fun `private hosts are ignored`() {
    assertTrue(BuildRunPortParser.parse("Network: http://192.168.1.5:5173/").isEmpty())
  }

  @Test
  fun `duplicate ports collapse`() {
    val endpoints = BuildRunPortParser.parse("Local: http://localhost:5173/ also http://127.0.0.1:5173/")
    assertEquals(1, endpoints.size)
    assertEquals(5173, endpoints[0].port)
  }
}
