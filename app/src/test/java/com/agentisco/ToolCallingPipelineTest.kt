package com.agentisco

import androidx.test.core.app.ApplicationProvider
import com.agentisco.agent.llm.BaseLlmClient
import com.agentisco.agent.llm.GeminiChainState
import com.agentisco.agent.llm.GeminiChainStoreImpl
import com.agentisco.agent.llm.GeminiInteractionsClient
import com.agentisco.agent.llm.LlmErrorKind
import com.agentisco.agent.llm.LlmException
import com.agentisco.agent.llm.LlmFinishReason
import com.agentisco.agent.llm.LlmInlineData
import com.agentisco.agent.llm.LlmMessage
import com.agentisco.agent.llm.LlmRequest
import com.agentisco.agent.llm.LlmRole
import com.agentisco.agent.llm.LlmStreamEvent
import com.agentisco.agent.llm.LlmToolCall
import com.agentisco.agent.llm.LlmToolSpec
import com.agentisco.agent.llm.OpenAIChatCompletionsClient
import com.agentisco.agent.tool.AgentTool
import com.agentisco.agent.tool.ToolArgumentError
import com.agentisco.agent.tool.ToolContext
import com.agentisco.agent.tool.ToolParam
import com.agentisco.agent.tool.ToolResult
import com.agentisco.data.local.ProviderConfigStore
import com.agentisco.data.model.Project
import com.agentisco.data.model.TerminalSession
import com.agentisco.settings.model.AIModel
import com.agentisco.settings.model.AIProvider
import com.agentisco.settings.model.LLMProtocol
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A deterministic test tool exercising the canonical schema contract. */
private class ReadFileLikeTool(
  private val files: Map<String, String> = mapOf("src/App.tsx" to "export default App")
) : AgentTool {
  override val name = "read_file"
  override val description = "Read the contents of a file in the current workspace."
  override val params = listOf(
    ToolParam("path", "File path relative to the project root.")
  )
  var lastArgs: JSONObject? = null

  override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
    lastArgs = args
    val path = args.optString("path")
    return files[path]?.let { ToolResult(true, output = it) }
      ?: ToolResult(false, error = "File not found: $path")
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolCallingPipelineTest {

  private val testTool = ReadFileLikeTool()
  private val ctx = ToolContext(
    project = Project(id = "p", name = "T", branch = "main", lastActivity = "now", path = "/tmp"),
    permissions = { com.agentisco.agent.model.AgentPermissions() },
    terminalSession = TerminalSession(id = "t", name = "main", currentDir = "/tmp"),
    requestApproval = { true },
    activeSessions = { emptyList() }
  )

  // ---- Canonical schema: validation ----

  @Test
  fun `valid arguments parse and execute`() {
    val args = testTool.parseAndValidate("{\"path\": \"src/App.tsx\"}")
    assertEquals("src/App.tsx", args.getString("path"))
  }

  @Test
  fun `empty arguments for a tool that requires arguments produce a correctable error`() {
    val err = runCatching { testTool.parseAndValidate("") }.exceptionOrNull()
    assertTrue(err is ToolArgumentError)
    assertTrue(err!!.message!!.contains("path"))
    assertTrue(err.message!!.contains("JSON object"))
  }

  @Test
  fun `null literal arguments are treated as missing`() {
    val err = runCatching { testTool.parseAndValidate("null") }.exceptionOrNull()
    assertTrue(err is ToolArgumentError)
  }

  @Test
  fun `malformed truncated JSON produces a validation error not a crash`() {
    val broken = "{\"path\": \"src/"
    val err = runCatching { testTool.parseAndValidate(broken) }.exceptionOrNull()
    assertTrue(err is ToolArgumentError)
    assertTrue(err!!.message!!.contains("JSON"))
  }

  @Test
  fun `missing required key produces a specific error`() {
    val err = runCatching { testTool.parseAndValidate("{\"other\": 1}") }.exceptionOrNull()
    assertTrue(err is ToolArgumentError)
    assertTrue(err!!.message!!.contains("Missing required argument"))
  }

  @Test
  fun `optional arguments may be omitted`() {
    val optionalTool = object : AgentTool {
      override val name = "list_files"
      override val description = "Inspect directory structure."
      override val params = listOf(ToolParam("path", "Optional subdirectory.", required = false))
      override suspend fun execute(args: JSONObject, ctx: ToolContext) = ToolResult(true, output = "ok")
    }
    val args = optionalTool.parseAndValidate("")
    assertEquals(0, args.length())
  }

  @Test
  fun `generated schema exposes names descriptions and required list`() {
    val schema = JSONObject(testTool.parametersJsonSchema())
    assertEquals("object", schema.getString("type"))
    assertEquals(
      "File path relative to the project root.",
      schema.getJSONObject("properties").getJSONObject("path").getString("description")
    )
    val required = schema.getJSONArray("required")
    assertEquals(listOf("path"), (0 until required.length()).map { required.getString(it) })
  }

  @Test
  fun `structured tool error is returned for a path that does not exist`() {
    val result = kotlinx.coroutines.runBlocking {
      testTool.execute(JSONObject("{\"path\": \"missing.tsx\"}"), ctx)
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("not found"))
  }

  // ---- OpenAI streaming tool-call accumulation ----

  private fun openAiToolCallChunk(index: Int, id: String?, name: String?, args: String?): String {
    val fn = JSONObject()
    if (name != null) fn.put("name", name)
    if (args != null) fn.put("arguments", args)
    val tc = JSONObject().put("index", index).put("function", fn)
    if (id != null) tc.put("id", id)
    val delta = JSONObject().put("tool_calls", JSONArray().put(tc))
    val choice = JSONObject().put("delta", delta)
    return JSONObject().put("choices", JSONArray().put(choice)).toString()
  }

  private fun newState() = BaseLlmClient.StreamState()

  @Test
  fun `streamed fragmented tool call arguments accumulate by index until complete`() {
    val client = OpenAIChatCompletionsClient(OkHttpClient())
    val state = newState()
    val events = mutableListOf<LlmStreamEvent>()

    client.handleData(openAiToolCallChunk(0, "call_abc", "read_file", "{\"pa"), state, events::add)
    client.handleData(openAiToolCallChunk(0, null, null, "th\": \"src/"), state, events::add)
    client.handleData(openAiToolCallChunk(0, null, null, "App.tsx\"}"), state, events::add)

    val calls = state.toolCalls.values.toList()
    assertEquals(1, calls.size)
    val call = calls.single()
    assertEquals("call_abc", call.id)
    assertEquals("read_file", call.name)
    // Complete JSON parses exactly once, here.
    assertEquals("src/App.tsx", JSONObject(call.argumentsJson).getString("path"))
  }

  @Test
  fun `multiple simultaneous tool calls accumulate independently`() {
    val client = OpenAIChatCompletionsClient(OkHttpClient())
    val state = newState()
    val events = mutableListOf<LlmStreamEvent>()

    client.handleData(openAiToolCallChunk(0, "call_1", "read_file", "{\"path\":\"a.tsx\"}"), state, events::add)
    client.handleData(openAiToolCallChunk(1, "call_2", "list_files", "{}"), state, events::add)

    val calls = state.toolCalls.values.toList()
    assertEquals(2, calls.size)
    assertEquals(setOf("call_1", "call_2"), calls.map { it.id }.toSet())
    assertEquals(setOf("read_file", "list_files"), calls.map { it.name }.toSet())
  }

  @Test
  fun `json null name fragments never become the literal string null`() {
    val client = OpenAIChatCompletionsClient(OkHttpClient())
    val state = newState()
    val events = mutableListOf<LlmStreamEvent>()

    // Some providers emit "name": null on continuation chunks.
    client.handleData(openAiToolCallChunk(0, "call_9", "read_file", "{}"), state, events::add)
    client.handleData(openAiToolCallChunk(0, null, null, ""), state, events::add)

    assertEquals("read_file", state.toolCalls.values.single().name)
    assertFalse(state.toolCalls.values.any { it.name == "null" })
  }

  // ---- OpenAI wire format: assistant tool_calls round trip ----

  @Test
  fun `assistant tool call messages serialize arguments as a JSON object string`() {
    val client = OpenAIChatCompletionsClient(OkHttpClient())
    val provider = AIProvider("prov", "Router", "https://example.com/v1", LLMProtocol.OPENAI_CHAT_COMPLETIONS)
    // The model must declare tool support — buildRequest only sends tools to
    // models whose capabilities actually include them.
    val model = AIModel("m", "prov", "test-model", "Test Model", capabilities = com.agentisco.settings.model.ModelCapabilities(tools = true))

    val request = client.buildRequest(
      provider, model, "sk-redacted",
      LlmRequest(
        messages = listOf(
          LlmMessage(LlmRole.USER, "review"),
          LlmMessage(LlmRole.ASSISTANT, "", toolCalls = listOf(LlmToolCall("call_1", "read_file", "{\"path\":\"src/App.tsx\"}"))),
          LlmMessage(LlmRole.TOOL, "file body", toolCallId = "call_1", toolName = "read_file")
        ),
        tools = listOf(LlmToolSpec("read_file", testTool.description, testTool.parametersJsonSchema()))
      ),
      stream = false
    )

    val buffer = okio.Buffer()
    request.body!!.writeTo(buffer)
    val body = JSONObject(buffer.readUtf8())

    // Tool definitions are function tools with valid JSON Schema parameters.
    val tool = body.getJSONArray("tools").getJSONObject(0)
    assertEquals("function", tool.getString("type"))
    assertEquals("read_file", tool.getJSONObject("function").getString("name"))
    // `parameters` is correctly embedded as a JSON Schema object on the wire.
    val wireSchema = tool.getJSONObject("function").getJSONObject("parameters")
    assertEquals("object", wireSchema.getString("type"))
    assertTrue(wireSchema.getJSONObject("properties").has("path"))

    // The echoed assistant tool call keeps id + function name, and its
    // arguments field is a STRING containing valid JSON (never an object,
    // never an empty/invalid string).
    val echoed = body.getJSONArray("messages").getJSONObject(1)
    val wireCall = echoed.getJSONArray("tool_calls").getJSONObject(0)
    assertEquals("call_1", wireCall.getString("id"))
    assertEquals("read_file", wireCall.getJSONObject("function").getString("name"))
    assertEquals(
      "src/App.tsx",
      JSONObject(wireCall.getJSONObject("function").getString("arguments")).getString("path")
    )

    // The tool result references the exact tool-call id.
    val result = body.getJSONArray("messages").getJSONObject(2)
    assertEquals("tool", result.getString("role"))
    assertEquals("call_1", result.getString("tool_call_id"))
  }

  // ---- Gemini Interactions wire format, streaming, and multi-turn chaining ----

  private fun bodyOf(request: okhttp3.Request): JSONObject {
    val buffer = okio.Buffer()
    request.body!!.writeTo(buffer)
    return JSONObject(buffer.readUtf8())
  }

  private fun geminiModel(maxOutputTokens: Int? = null) = AIModel(
    id = "gemini-model",
    providerId = "gemini",
    modelId = "models/gemini-3.1-flash-lite",
    displayName = "Gemini 3.1 Flash Lite",
    maxOutputTokens = maxOutputTokens,
    capabilities = com.agentisco.settings.model.ModelCapabilities(tools = true)
  )

  private val geminiProvider =
    AIProvider("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta", LLMProtocol.GOOGLE_GEMINI)

  @Test
  fun `Gemini interactions request serializes system instruction media tools and agentic defaults`() {
    val client = GeminiInteractionsClient(OkHttpClient(), GeminiChainStoreImpl())
    val request = client.buildRequest(
      geminiProvider,
      geminiModel(),
      "gemini-key",
      LlmRequest(
        messages = listOf(
          LlmMessage(LlmRole.SYSTEM, "You are concise."),
          LlmMessage(LlmRole.USER, "Earlier question"),
          LlmMessage(LlmRole.ASSISTANT, "Earlier answer"),
          LlmMessage(LlmRole.USER, "Inspect this image", inlineData = listOf(LlmInlineData("image/png", byteArrayOf(1, 2, 3))))
        ),
        tools = listOf(LlmToolSpec("read_file", testTool.description, testTool.parametersJsonSchema()))
      ),
      stream = true
    )

    assertEquals("https://generativelanguage.googleapis.com/v1beta/interactions", request.url.toString())
    assertEquals("gemini-key", request.header("x-goog-api-key"))
    val body = bodyOf(request)

    assertEquals("models/gemini-3.1-flash-lite", body.getString("model"))
    assertTrue(body.getBoolean("stream"))
    assertFalse(body.has("previous_interaction_id"))
    // A plain string, not the generateContent parts wrapper.
    assertEquals("You are concise.", body.getString("system_instruction"))

    // Interactions input blocks carry no role, so prior turns are rendered as
    // labeled text and only the live turn is sent as real content blocks.
    val input = body.getJSONArray("input")
    assertEquals(3, input.length())
    val transcript = input.getJSONObject(0)
    assertEquals("text", transcript.getString("type"))
    assertTrue(transcript.getString("text").contains("User: Earlier question"))
    assertTrue(transcript.getString("text").contains("Assistant: Earlier answer"))
    assertEquals("Inspect this image", input.getJSONObject(1).getString("text"))
    val image = input.getJSONObject(2)
    assertEquals("image", image.getString("type"))
    assertEquals("image/png", image.getString("mime_type"))
    assertEquals("AQID", image.getString("data"))

    val tool = body.getJSONArray("tools").getJSONObject(0)
    assertEquals("function", tool.getString("type"))
    assertEquals("read_file", tool.getString("name"))
    assertEquals("object", tool.getJSONObject("parameters").getString("type"))

    // Every generation field is defaulted for agentic work so users can leave
    // the model form blank.
    val config = body.getJSONObject("generation_config")
    assertEquals(65536, config.getInt("max_output_tokens"))
    assertEquals("medium", config.getString("thinking_level"))
    assertFalse(config.has("temperature"))
    assertFalse(config.has("top_p"))
    assertFalse(config.has("top_k"))
  }

  @Test
  fun `Gemini interactions floors the output budget because thinking is billed against it`() {
    val client = GeminiInteractionsClient(OkHttpClient(), GeminiChainStoreImpl())
    val request = client.buildRequest(
      geminiProvider,
      geminiModel(maxOutputTokens = 16),
      "gemini-key",
      LlmRequest(messages = listOf(LlmMessage(LlmRole.USER, "hi"))),
      stream = false
    )
    val config = bodyOf(request).getJSONObject("generation_config")
    assertEquals(8192, config.getInt("max_output_tokens"))
    assertFalse(bodyOf(request).has("stream"))
  }

  @Test
  fun `Gemini interactions normalizes non streamed steps usage and requires action`() {
    val client = GeminiInteractionsClient(OkHttpClient(), GeminiChainStoreImpl())
    val state = GeminiInteractionsClient.InteractionsState()
    val events = mutableListOf<LlmStreamEvent>()
    val payload = JSONObject().apply {
      put("id", "v1_NONSTREAM")
      put("status", "requires_action")
      put("usage", JSONObject().apply {
        put("total_input_tokens", 68)
        put("total_output_tokens", 16)
        put("total_thought_tokens", 30)
        put("total_cached_tokens", 0)
        put("total_tokens", 114)
      })
      put("steps", JSONArray().apply {
        put(JSONObject().put("type", "thought").put("signature", "opaque"))
        put(JSONObject().put("type", "model_output").put("content", JSONArray().put(
          JSONObject().put("type", "text").put("text", "Let me check that file.")
        )))
        put(JSONObject().apply {
          put("type", "function_call")
          put("id", "call_316236")
          put("name", "read_file")
          put("arguments", JSONObject().put("path", "src/App.kt"))
        })
      })
    }

    assertTrue(client.handleData(payload.toString(), state, events::add))
    assertEquals("Let me check that file.", state.content.toString())
    assertEquals(LlmFinishReason.TOOL_CALLS, state.finishReason)
    assertEquals(68, state.usage?.inputTokens)
    assertEquals(16, state.usage?.outputTokens)
    assertEquals(30, state.usage?.reasoningTokens)
    assertEquals(114, state.usage?.totalTokens)
    assertTrue(events.any { it is LlmStreamEvent.Token && it.text == "Let me check that file." })
    // An opaque thought signature is never surfaced as answer text.
    assertFalse(events.any { it is LlmStreamEvent.Token && it.text == "opaque" })

    val call = state.toolCalls.values.single()
    assertEquals("call_316236", call.id)
    assertEquals("read_file", call.name)
    assertEquals("src/App.kt", JSONObject(call.argumentsJson).getString("path"))
  }

  @Test
  fun `Gemini interactions continuation sends only the new turn and advances the chain`() {
    val store = GeminiChainStoreImpl()
    val client = GeminiInteractionsClient(OkHttpClient(), store)
    val tools = listOf(LlmToolSpec("list_dir", testTool.description, testTool.parametersJsonSchema()))
    val events = mutableListOf<LlmStreamEvent>()

    // Turn 1 opens the chain.
    client.buildRequest(
      geminiProvider, geminiModel(), "gemini-key",
      LlmRequest(
        messages = listOf(LlmMessage(LlmRole.SYSTEM, "You are a coding agent."), LlmMessage(LlmRole.USER, "List src")),
        tools = tools, conversationKey = "session-1"
      ),
      stream = true
    )
    assertNull(store.load("session-1"))

    // The provider streams a function call, then finishes as requires_action.
    val state = GeminiInteractionsClient.InteractionsState()
    client.handleData("""{"interaction":{"id":"v1_AAA","status":"in_progress"},"event_type":"interaction.created"}""", state, events::add)
    assertNull("An in-flight interaction must not advance the chain", store.load("session-1"))
    client.handleData("""{"index":0,"step":{"type":"thought"},"event_type":"step.start"}""", state, events::add)
    client.handleData("""{"index":0,"delta":{"signature":"opaque","type":"thought_signature"},"event_type":"step.delta"}""", state, events::add)
    client.handleData("""{"index":0,"event_type":"step.stop"}""", state, events::add)
    client.handleData("""{"index":1,"step":{"id":"call_261152","type":"function_call","name":"list_dir","arguments":{}},"event_type":"step.start"}""", state, events::add)
    client.handleData("""{"index":1,"delta":{"arguments":"{\"pa","type":"arguments_delta"},"event_type":"step.delta"}""", state, events::add)
    client.handleData("""{"index":1,"delta":{"arguments":"th\":\"src\"}","type":"arguments_delta"},"event_type":"step.delta"}""", state, events::add)
    client.handleData("""{"index":1,"event_type":"step.stop"}""", state, events::add)
    assertTrue(events.any { it is LlmStreamEvent.ToolCallRequested && it.call.name == "list_dir" })

    // The interaction payload carries the terminal status; the stream itself only
    // ends on the sentinel that follows it.
    assertFalse(client.handleData("""{"interaction":{"id":"v1_AAA","status":"requires_action","usage":{"total_input_tokens":68,"total_output_tokens":16,"total_thought_tokens":30,"total_tokens":114}},"event_type":"interaction.completed"}""", state, events::add))
    assertTrue(client.handleData("[DONE]", state, events::add))

    assertEquals(LlmFinishReason.TOOL_CALLS, state.finishReason)
    assertEquals("", state.content.toString())
    val call = state.toolCalls.values.single()
    assertEquals("call_261152", call.id)
    assertEquals("list_dir", call.name)
    // Argument fragments are accumulated across deltas into one JSON object.
    assertEquals("src", JSONObject(call.argumentsJson).getString("path"))
    assertEquals("v1_AAA", store.load("session-1")?.interactionId)

    // Turn 2 resumes: only the tool result travels, never the replayed prompt.
    val second = client.buildRequest(
      geminiProvider, geminiModel(), "gemini-key",
      LlmRequest(
        messages = listOf(
          LlmMessage(LlmRole.SYSTEM, "You are a coding agent."),
          LlmMessage(LlmRole.USER, "List src"),
          LlmMessage(LlmRole.ASSISTANT, "", toolCalls = listOf(call)),
          LlmMessage(LlmRole.TOOL, "App.kt\nmain.kt", toolCallId = call.id, toolName = "list_dir")
        ),
        tools = tools, conversationKey = "session-1"
      ),
      stream = true
    )
    val secondBody = bodyOf(second)
    assertEquals("v1_AAA", secondBody.getString("previous_interaction_id"))
    val input = secondBody.getJSONArray("input")
    assertEquals(1, input.length())
    val result = input.getJSONObject(0)
    assertEquals("function_result", result.getString("type"))
    assertEquals("list_dir", result.getString("name"))
    assertEquals("call_261152", result.getString("call_id"))
    assertEquals(
      "App.kt\nmain.kt",
      result.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
    )

    // Turn 3 chains off the newest id, proving the chain is transitive.
    client.handleData("""{"interaction":{"id":"v1_BBB","status":"completed"},"event_type":"interaction.completed"}""", state, events::add)
    assertEquals("v1_BBB", store.load("session-1")?.interactionId)
    assertEquals(LlmFinishReason.TOOL_CALLS, state.finishReason)
  }

  @Test
  fun `Gemini interactions maps a mid stream rate limit error to a retryable failure`() {
    val client = GeminiInteractionsClient(OkHttpClient(), GeminiChainStoreImpl())
    val state = GeminiInteractionsClient.InteractionsState()
    val error = assertThrows(LlmException::class.java) {
      client.handleData(
        """{"error":{"message":"Rate limit exceeded for model gemini-3.1-flash-lite (limit: 20 requests per day on Free Tier).","code":"rate_limit_exceeded"},"event_type":"error"}""",
        state
      ) { }
    }
    assertEquals(LlmErrorKind.RATE_LIMIT, error.kind)
    assertTrue(error.message!!.contains("Rate limit exceeded"))
  }

  @Test
  fun `Gemini interactions reopens the conversation when the stored chain is rejected`() {
    val store = GeminiChainStoreImpl()
    store.save("session-9", GeminiChainState("v1_EXPIRED"))

    // Responses are queued and request bodies captured by an interceptor, so
    // the retry path is exercised without a network or an extra test dependency.
    val queued = ArrayDeque(
      listOf(
        400 to """{"error":{"message":"Request contains an invalid argument.","code":"invalid_request"}}""",
        200 to """{"id":"v1_FRESH","status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"recovered"}]}]}"""
      )
    )
    val sent = mutableListOf<String>()
    val http = OkHttpClient.Builder().addInterceptor { chain ->
      val buffer = okio.Buffer()
      chain.request().body?.writeTo(buffer)
      sent.add(buffer.readUtf8())
      val (code, payload) = queued.removeFirst()
      okhttp3.Response.Builder()
        .request(chain.request())
        .protocol(okhttp3.Protocol.HTTP_1_1)
        .code(code)
        .message("mock")
        .body(payload.toResponseBody("application/json".toMediaType()))
        .build()
    }.build()

    val client = GeminiInteractionsClient(http, store)
    val provider = AIProvider("gemini", "Gemini", "https://gemini.test/v1beta", LLMProtocol.GOOGLE_GEMINI)
    val model = geminiModel().copy(
      capabilities = com.agentisco.settings.model.ModelCapabilities(tools = true, streaming = false)
    )
    val events = mutableListOf<LlmStreamEvent>()

    runBlocking {
      client.streamChat(
        provider, model, "gemini-key",
        LlmRequest(
          messages = listOf(LlmMessage(LlmRole.SYSTEM, "sys"), LlmMessage(LlmRole.USER, "hello")),
          conversationKey = "session-9"
        ),
        events::add
      )
    }

    assertEquals(2, sent.size)
    assertEquals("v1_EXPIRED", JSONObject(sent[0]).getString("previous_interaction_id"))
    // The retry drops the rejected chain and rebuilds the prompt from scratch.
    val retry = JSONObject(sent[1])
    assertFalse(retry.has("previous_interaction_id"))
    val retryInput = retry.getJSONArray("input")
    assertEquals(1, retryInput.length())
    assertEquals("hello", retryInput.getJSONObject(0).getString("text"))
    // The swallowed failure is never reported; only the recovery reaches the agent.
    assertFalse(events.any { it is LlmStreamEvent.Failed })
    assertTrue(events.any { it is LlmStreamEvent.Token && it.text == "recovered" })
    assertEquals("v1_FRESH", store.load("session-9")?.interactionId)
  }

  // ---- Provider/model relationship sanity inside the pipeline ----

  @Test
  fun `same model id under two providers resolves to distinct records when selecting`() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    File(context.getDir("agentisco", android.content.Context.MODE_PRIVATE), "providers.json").delete()
    File(context.getDir("agentisco", android.content.Context.MODE_PRIVATE), "credentials.json").delete()
    val store = ProviderConfigStore(context)
    store.upsertProvider(AIProvider("p1", "A", "https://a/v1", LLMProtocol.OPENAI_CHAT_COMPLETIONS), "k1")
    store.upsertProvider(AIProvider("p2", "B", "https://b/v1", LLMProtocol.OPENAI_CHAT_COMPLETIONS), "k2")
    store.upsertModel(AIModel("m1", "p1", "shared/model", "Shared"))
    store.upsertModel(AIModel("m2", "p2", "shared/model", "Shared"))
    store.selectModel("m2")
    assertEquals("m2", store.getSelectedModelId())
    assertEquals("p2", store.getModels().first { it.id == "m2" }.providerId)
    assertNotNull(store.getApiKey("p2"))
  }
}
