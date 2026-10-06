package com.awaki

import com.awaki.local.FakePythonServer
import com.awaki.local.PythonCall
import com.awaki.local.PythonResponse
import com.awaki.local.model.LocalGenerationSettings
import com.awaki.local.model.LocalRuntimeSettings
import com.awaki.local.py.PythonModelClient
import com.awaki.local.runtime.LocalAnswerDelta
import com.awaki.local.runtime.LocalChatInputs
import com.awaki.local.runtime.LocalChatMessage
import com.awaki.local.runtime.LocalChatRequest
import com.awaki.local.runtime.LocalChatTool
import com.awaki.local.runtime.LocalEngineException
import com.awaki.local.runtime.LocalFinishReason
import com.awaki.local.runtime.LocalPromptTooLongException
import com.awaki.local.runtime.LocalToolCall
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

  private fun delta(content: String = "", reasoning: String = "", call: JSONObject? = null): JSONObject =
    JSONObject().apply {
      if (content.isNotEmpty()) put("content", content)
      if (reasoning.isNotEmpty()) put("reasoning_content", reasoning)
      call?.let { put("tool_calls", JSONArray().put(it)) }
    }

  /** One streamed chunk, in the shape serve.py writes it. */
  private fun event(delta: JSONObject, reason: String? = null): String = JSONObject().apply {
    put("id", "chatcmpl-1")
    put("object", "chat.completion.chunk")
    put("choices", JSONArray().put(JSONObject().apply {
      put("index", 0)
      put("delta", delta)
      reason?.let { put("finish_reason", it) }
    }))
  }.toString()

  private fun textEvent(text: String, reason: String? = null): String = event(delta(content = text), reason)

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

  private fun turn(
    settings: LocalGenerationSettings = LocalGenerationSettings.Defaults,
    inputs: LocalChatInputs = LocalChatInputs(messages = listOf(LocalChatMessage("user", "hi")))
  ): LocalChatRequest = LocalChatRequest(inputs, settings)

  private fun piecesOf(client: PythonModelClient, request: LocalChatRequest): List<LocalAnswerDelta> {
    val seen = mutableListOf<LocalAnswerDelta>()
    client.chat(request) { seen += it; true }
    return seen
  }

  @Test
  fun `the health answer is read into the numbers the app acts on`() = withServer(
    { PythonResponse.json("""{"ok":true,"protocol":2,"loaded":true,"busy":false,"pid":4242,"stats":{"rss_mb":517.5}}""") }
  ) { _, client ->
    val health = client.health()
    assertNotNull(health)
    assertEquals(2, health!!.protocol)
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
    { PythonResponse.json("""{"protocol":2,"loaded":false,"busy":false,"pid":1}""") }
  ) { server, client ->
    client.health()
    val call = server.calls.single()
    assertEquals("Bearer test-token", call.token)
    assertEquals("GET", call.method)
    assertEquals("/health", call.path)
  }

  @Test
  fun `what the server read off the file's template becomes the abilities the app offers`() = withServer(
    {
      PythonResponse.json(
        """{"ok":true,"model":{"path":"/root/local-models/m.gguf","n_ctx":4000,"n_vocab":32000,""" +
          """"architecture":"lfm2","name":"LFM2.5-230M",""" +
          """"chat":{"usesOwnTemplate":true,"supportsTools":true,"supportsParallelToolCalls":false,""" +
          """"supportsThinking":true,"supportsSystemMessage":true}}}"""
      )
    }
  ) { _, client ->
    val info = client.load("/root/local-models/m.gguf", LocalRuntimeSettings(contextSize = 4000))
    assertTrue(info.capabilities.available)
    assertTrue(info.capabilities.usesOwnTemplate)
    assertTrue(info.capabilities.supportsTools)
    assertFalse(info.capabilities.supportsParallelToolCalls)
    assertTrue(info.capabilities.supportsThinking)
  }

  /** A server written by an older app has no chat abilities to report, and that has to be said. */
  @Test
  fun `a model server older than the app is named, not silently treated as capable`() = withServer(
    { PythonResponse.json("""{"ok":true,"model":{"path":"/root/m.gguf","n_ctx":4000}}""") }
  ) { _, client ->
    val info = client.load("/root/m.gguf", LocalRuntimeSettings())
    assertFalse(info.capabilities.available)
    assertTrue(info.capabilities.reason.contains("Reinstall the model runtime"))
  }

  @Test
  fun `loading sends the context the user chose, in the words the guest understands`() = withServer(
    { PythonResponse.json("""{"ok":true,"model":{"path":"/root/local-models/m.gguf","n_ctx":4000,"n_vocab":32000,"architecture":"lfm2","name":"LFM2.5-230M","chat":{}}}""") }
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
  fun `a turn hands over every piece in the order the model wrote them`() = withServer(
    {
      PythonResponse.stream(
        listOf(textEvent("Hel"), textEvent("lo "), textEvent("world"), event(delta(), "stop"), "[DONE]")
      )
    }
  ) { _, client ->
    val received = StringBuilder()
    val finish = client.chat(turn()) { piece -> received.append(piece.content); true }
    assertEquals("Hello world", received.toString())
    assertEquals(LocalFinishReason.END_OF_SEQUENCE, finish)
  }

  @Test
  fun `reasoning and a call keep their own fields across the seam`() = withServer(
    {
      PythonResponse.stream(
        listOf(
          event(delta(reasoning = "thinking")),
          event(
            delta(
              call = JSONObject()
                .put("index", 0)
                .put("id", "call_1")
                .put("type", "function")
                .put("function", JSONObject().put("name", "read_file").put("arguments", """{"path":"a"}"""))
            )
          ),
          event(delta(), "tool_calls"),
          "[DONE]"
        )
      )
    }
  ) { _, client ->
    val pieces = piecesOf(client, turn())
    assertEquals("thinking", pieces[0].reasoning)
    val call = pieces[1].toolCall
    assertNotNull(call)
    assertEquals("call_1", call!!.id)
    assertEquals("read_file", call.name)
    assertEquals("""{"path":"a"}""", call.argumentsJson)
    assertEquals(0, pieces[1].toolCallIndex)
    assertEquals(2, pieces.size)
  }

  @Test
  fun `a turn is asked with the saved sampling and no min-p the server would default`() = withServer(
    { PythonResponse.stream(listOf(textEvent("x"), event(delta(), "length"), "[DONE]")) }
  ) { server, client ->
    client.chat(
      turn(
        settings = LocalGenerationSettings(
          maxOutputTokens = 300,
          temperature = 0.4,
          topK = 40,
          topP = 0.9,
          repeatPenalty = 1.1
        ),
        inputs = LocalChatInputs(
          messages = listOf(LocalChatMessage("system", "be terse"), LocalChatMessage("user", "hi")),
          tools = listOf(LocalChatTool("read_file", "Read a file", """{"type":"object"}""")),
          toolChoice = "none",
          enableThinking = false,
          parallelToolCalls = true,
          stop = listOf("HALT")
        )
      ).copy(seed = 7L)
    ) { true }
    val sent = JSONObject(server.calls.last().body)
    assertTrue(sent.getBoolean("stream"))
    assertEquals(300, sent.getInt("max_tokens"))
    assertEquals(0.4, sent.getDouble("temperature"), 0.001)
    assertEquals(40, sent.getInt("top_k"))
    assertEquals(0.9, sent.getDouble("top_p"), 0.001)
    assertEquals(1.1, sent.getDouble("repeat_penalty"), 0.001)
    // The interpreter defaults to a min-p this app has no setting for, so it is pinned to a
    // no-op: leaving it unset would make sampling depend on the server's defaults.
    assertEquals(0.0, sent.getDouble("min_p"), 0.001)
    assertEquals(7L, sent.getLong("seed"))
    assertEquals("none", sent.getString("tool_choice"))
    assertFalse(sent.getBoolean("enable_thinking"))
    assertTrue(sent.getBoolean("parallel_tool_calls"))
    assertEquals("HALT", sent.getJSONArray("stop").getString(0))
    // The transcript travels as messages, and a tool's schema as the object the template iterates
    // over — a string here would show the model an escaped blob instead of a function.
    assertEquals("be terse", sent.getJSONArray("messages").getJSONObject(0).getString("content"))
    assertEquals("object", sent.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters").getString("type"))
  }

  /** A stop press has to reach a decode that is already running, not just the caller. */
  @Test
  fun `stopping the answer on the app side stops the phone too`() = withServer(
    { call ->
      if (call.path == "/abort") {
        PythonResponse.json("""{"ok":true}""")
      } else {
        PythonResponse.stream(listOf(textEvent("first"), textEvent("second"), event(delta(), "stop"), "[DONE]"))
      }
    }
  ) { server, client ->
    val seen = StringBuilder()
    val finish = client.chat(turn()) { piece ->
      seen.append(piece.content)
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
    val failure = runCatching { client.chat(turn()) { true } }.exceptionOrNull()
    assertTrue(failure is LocalPromptTooLongException)
    assertTrue(failure!!.message!!.contains("exceed context window"))
  }

  @Test
  fun `a stream that ends without a reason is a failure, not a short answer`() = withServer(
    { PythonResponse.stream(listOf(textEvent("par"), textEvent("tial"))) }
  ) { _, client ->
    val failure = runCatching { client.chat(turn()) { true } }.exceptionOrNull()
    assertTrue(failure is LocalEngineException)
    assertTrue(failure!!.message!!.contains("stopped answering"))
  }

  /** Headers already said 200, so a decode that fails mid-stream says so inside the stream. */
  @Test
  fun `an error inside a stream still reaches the caller`() = withServer(
    { PythonResponse.stream(listOf(textEvent("par"), """{"error":{"message":"the guest died"}}""")) }
  ) { _, client ->
    val failure = runCatching { client.chat(turn()) { true } }.exceptionOrNull()
    assertTrue(failure is LocalEngineException)
    assertTrue(failure!!.message!!.contains("guest died"))
  }

  @Test
  fun `an answer the app cannot parse is a message about the environment`() = withServer(
    { PythonResponse.stream(listOf("this is not json")) }
  ) { _, client ->
    val failure = runCatching { client.chat(turn()) { true } }.exceptionOrNull()
    assertTrue(failure is LocalEngineException)
    assertTrue(failure!!.message!!.contains("Reinstall the model runtime"))
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

  @Test
  fun `unloading tells the server to give the weights back`() = withServer(
    { PythonResponse.json("""{"ok":true}""") }
  ) { server, client ->
    client.unload()
    assertEquals("/v1/unload", server.calls.single().path)
  }
}
