package com.agentisco

import com.agentisco.local.FakePythonServer
import com.agentisco.local.PythonCall
import com.agentisco.local.PythonResponse
import com.agentisco.local.model.LocalGenerationSettings
import com.agentisco.local.model.LocalRuntimeSettings
import com.agentisco.local.py.PythonModelClient
import com.agentisco.local.runtime.LocalEngineException
import com.agentisco.local.runtime.LocalFinishReason
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Android half of the Python model server, against a socket that speaks serve.py's protocol. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalPythonModelClientTest {

  private fun event(text: String, reason: String? = null): String = JSONObject().apply {
    put("id", "cmpl-1")
    put("object", "text_completion")
    put("choices", JSONArray().put(JSONObject().apply {
      put("index", 0)
      put("text", text)
      reason?.let { put("finish_reason", it) }
    }))
  }.toString()

  private fun withServer(
    reply: (PythonCall) -> PythonResponse,
    block: (FakePythonServer, PythonModelClient) -> Unit
  ) {
    val server = FakePythonServer(reply)
    server.start()
    try {
      block(server, server.client())
    } finally {
      server.stop()
    }
  }

  @Test
  fun `the health answer is read into the numbers the app acts on`() = withServer(
    { PythonResponse.json("""{"ok":true,"protocol":1,"loaded":true,"busy":false,"pid":4242,"stats":{"rss_mb":517.5}}""") }
  ) { _, client ->
    val health = client.health()
    assertNotNull(health)
    assertEquals(1, health!!.protocol)
    assertTrue(health.loaded)
    assertFalse(health.busy)
    assertEquals(4242, health.pid)
    assertEquals(517.5, health.rssMb, 0.001)
  }

  @Test
  fun `a server that is not there is a null, not a crash`() {
    val server = FakePythonServer { PythonResponse.json("{}") }
    server.start()
    val client = server.client()
    server.stop()
    assertNull(client.health())
  }

  @Test
  fun `every call carries the token this app chose`() = withServer(
    { PythonResponse.json("""{"protocol":1,"loaded":false,"busy":false,"pid":1}""") }
  ) { server, client ->
    client.health()
    val call = server.calls.single()
    assertEquals("Bearer test-token", call.token)
    assertEquals("GET", call.method)
    assertEquals("/health", call.path)
  }

  @Test
  fun `loading sends the context the user chose, in the words the guest understands`() = withServer(
    { PythonResponse.json("""{"ok":true,"model":{"path":"/root/local-models/m.gguf","n_ctx":4000,"n_vocab":32000,"architecture":"lfm2","name":"LFM2.5-230M"}}""") }
  ) { server, client ->
    val info = client.load(
      "/root/local-models/m.gguf",
      LocalRuntimeSettings(contextSize = 4000, threadCount = 0, batchSize = 512)
    )
    val sent = JSONObject(server.calls.last().body)
    assertEquals("/root/local-models/m.gguf", sent.getString("model_path"))
    assertEquals(4000, sent.getInt("n_ctx"))
    assertEquals(512, sent.getInt("n_batch"))
    // 0 means "however many cores this phone has", which is the interpreter's own default.
    assertFalse(sent.has("n_threads"))
    assertEquals(4000, info.contextSize)
    assertEquals("lfm2", info.architecture)
    assertEquals("LFM2.5-230M", info.publishedName)
  }

  @Test
  fun `a load that fails says why in the server's own words`() = withServer(
    { call ->
      if (call.path == "/v1/load") {
        PythonResponse.json("""{"error":{"message":"Could not load the model: out of memory","type":"model_load_failed"}}""", 500)
      } else {
        PythonResponse.json("{}")
      }
    }
  ) { _, client ->
    val failure = runCatching { client.load("/root/m.gguf", LocalRuntimeSettings()) }.exceptionOrNull()
    assertTrue(failure is LocalEngineException)
    assertTrue(failure!!.message!!.contains("out of memory"))
  }

  @Test
  fun `a completion hands over every piece in the order the model wrote them`() {
    val events = listOf(event("Hel"), event("lo "), event("world"), event("", "stop"), "[DONE]")
    withServer({ PythonResponse.stream(events) }) { _, client ->
      val received = StringBuilder()
      val finish = client.complete(
        PythonModelClient.Completion("prompt", LocalGenerationSettings.Defaults)
      ) { piece -> received.append(piece); true }
      assertEquals("Hello world", received.toString())
      assertEquals(LocalFinishReason.END_OF_SEQUENCE, finish)
    }
  }

  @Test
  fun `a decode is asked with the saved sampling and no min-p the native engine lacks`() = withServer(
    { PythonResponse.stream(listOf(event("x", "length"), "[DONE]")) }
  ) { server, client ->
    client.complete(
      PythonModelClient.Completion(
        prompt = "rendered prompt",
        settings = LocalGenerationSettings(
          maxOutputTokens = 300,
          temperature = 0.4,
          topK = 40,
          topP = 0.9,
          repeatPenalty = 1.1
        ),
        grammar = "root ::= \"a\"",
        seed = 7L,
        stop = listOf("</s>")
      )
    ) { true }
    val sent = JSONObject(server.calls.last().body)
    assertEquals("rendered prompt", sent.getString("prompt"))
    assertTrue(sent.getBoolean("stream"))
    assertEquals(300, sent.getInt("max_tokens"))
    assertEquals(0.4, sent.getDouble("temperature"), 0.001)
    assertEquals(40, sent.getInt("top_k"))
    assertEquals(0.9, sent.getDouble("top_p"), 0.001)
    assertEquals(1.1, sent.getDouble("repeat_penalty"), 0.001)
    // llama-cpp-python defaults to a min-p the JNI engine has no equivalent of: leaving it in
    // would make a model answer differently depending on which half of the phone read it.
    assertEquals(0.0, sent.getDouble("min_p"), 0.001)
    assertEquals(7L, sent.getLong("seed"))
    assertEquals("root ::= \"a\"", sent.getString("grammar"))
    assertEquals("</s>", sent.getJSONArray("stop").getString(0))
  }

  /** A stop press has to reach a decode that is already running, not just the caller. */
  @Test
  fun `stopping the answer on the app side stops the phone too`() = withServer(
    { call ->
      if (call.path == "/abort") {
        PythonResponse.json("""{"ok":true}""")
      } else {
        PythonResponse.stream(listOf(event("first"), event("second"), event("", "stop"), "[DONE]"))
      }
    }
  ) { server, client ->
    val seen = StringBuilder()
    val finish = client.complete(
      PythonModelClient.Completion("prompt", LocalGenerationSettings.Defaults)
    ) { piece ->
      seen.append(piece)
      false
    }
    assertEquals(LocalFinishReason.ABORTED, finish)
    assertEquals("first", seen.toString())
    assertEquals("/abort", server.calls.last().path)
  }

  /** The one engine failure the agent loop must not retry, arriving as an HTTP 400. */
  @Test
  fun `a prompt larger than the context is the error that says do not try again`() = withServer(
    { call ->
      if (call.method == "POST") {
        PythonResponse.json(
          """{"error":{"message":"Requested tokens (5000) exceed context window of 4000","type":"context_length_exceeded"}}""",
          400
        )
      } else {
        PythonResponse.json("{}")
      }
    }
  ) { _, client ->
    val failure = runCatching {
      client.complete(PythonModelClient.Completion("big", LocalGenerationSettings.Defaults)) { true }
    }.exceptionOrNull()
    assertTrue(failure is com.agentisco.local.runtime.LocalPromptTooLongException)
    assertTrue(failure!!.message!!.contains("exceed context window"))
  }

  @Test
  fun `a stream that ends without a reason is a failure, not a short answer`() = withServer(
    { PythonResponse.stream(listOf(event("par"), event("tial"))) }
  ) { _, client ->
    val failure = runCatching {
      client.complete(PythonModelClient.Completion("p", LocalGenerationSettings.Defaults)) { true }
    }.exceptionOrNull()
    assertTrue(failure is LocalEngineException)
    assertTrue(failure!!.message!!.contains("stopped answering"))
  }

  /** Headers already said 200, so a decode that fails mid-stream says so inside the stream. */
  @Test
  fun `an error inside a stream still reaches the caller`() = withServer(
    { PythonResponse.stream(listOf(event("par"), """{"error":{"message":"the guest died"}}""")) }
  ) { _, client ->
    val failure = runCatching {
      client.complete(PythonModelClient.Completion("p", LocalGenerationSettings.Defaults)) { true }
    }.exceptionOrNull()
    assertTrue(failure is LocalEngineException)
    assertTrue(failure!!.message!!.contains("guest died"))
  }

  @Test
  fun `an answer the app cannot parse is a message about the environment`() = withServer(
    { PythonResponse.stream(listOf("this is not json")) }
  ) { _, client ->
    val failure = runCatching {
      client.complete(PythonModelClient.Completion("p", LocalGenerationSettings.Defaults)) { true }
    }.exceptionOrNull()
    assertTrue(failure is LocalEngineException)
    assertTrue(failure!!.message!!.contains("Setup environment"))
  }

  @Test
  fun `waiting for the server gives up the moment its process is gone`() {
    val server = FakePythonServer { PythonResponse.json("""{"error":{"message":"not ready"}}""", 503) }
    server.start()
    try {
      val client = server.client()
      assertFalse(client.awaitHealthy(30_000, alive = { false }))
      assertTrue(
        "a server that never answers is waited out, not waited on forever",
        !client.awaitHealthy(600, alive = { true })
      )
    } finally {
      server.stop()
    }
  }

  /** A stop request against a dead server must not turn into a second failure on top of the first. */
  @Test
  fun `aborting a turn that is not running is not an error`() {
    val server = FakePythonServer { PythonResponse.json("{}") }
    server.start()
    val client = server.client()
    server.stop()
    client.abort()
    client.unload()
  }
}
