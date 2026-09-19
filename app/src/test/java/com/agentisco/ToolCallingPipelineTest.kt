package com.agentisco

import androidx.test.core.app.ApplicationProvider
import com.agentisco.agent.llm.AnthropicMessagesClient
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
import com.agentisco.agent.llm.OpenAIResponsesClient
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

  // ---- OpenAI wire format: request shape, finish reason and usage ----

  private val openAiProvider =
    AIProvider("openai", "OpenAI", "https://api.openai.com/v1", LLMProtocol.OPENAI_CHAT_COMPLETIONS)

  private fun openAiModel(
    maxOutputTokens: Int? = null,
    tools: Boolean = true,
    maxTokensParameter: Boolean = true
  ) = AIModel(
    id = "openai-model",
    providerId = "openai",
    modelId = "gpt-4o",
    displayName = "GPT-4o",
    maxOutputTokens = maxOutputTokens,
    capabilities = com.agentisco.settings.model.ModelCapabilities(
      tools = tools,
      maxTokensParameter = maxTokensParameter
    )
  )

  @Test
  fun `OpenAI request maps roles tool results and the modern token parameter onto the wire format`() {
    val client = OpenAIChatCompletionsClient(OkHttpClient())

    val request = client.buildRequest(
      openAiProvider, openAiModel(maxOutputTokens = 4000), "sk-redacted",
      LlmRequest(
        messages = listOf(
          LlmMessage(LlmRole.SYSTEM, "You are a coding agent."),
          LlmMessage(LlmRole.USER, "Read src/App.tsx"),
          LlmMessage(LlmRole.ASSISTANT, "", toolCalls = listOf(LlmToolCall("call_1", "read_file", "{\"path\":\"src/App.tsx\"}"))),
          LlmMessage(LlmRole.TOOL, "export default App", toolCallId = "call_1", toolName = "read_file")
        ),
        tools = listOf(LlmToolSpec("read_file", testTool.description, testTool.parametersJsonSchema()))
      ),
      stream = false
    )

    assertEquals("https://api.openai.com/v1/chat/completions", request.url.toString())
    assertEquals("Bearer sk-redacted", request.header("Authorization"))

    val body = bodyOf(request)
    assertEquals("gpt-4o", body.getString("model"))
    // max_tokens is deprecated on this endpoint; the modern name carries the cap.
    assertEquals(4000, body.getInt("max_completion_tokens"))
    assertFalse(body.has("max_tokens"))

    // Unlike the other protocols, the system prompt is a message in the array.
    val messages = body.getJSONArray("messages")
    assertEquals(4, messages.length())
    assertEquals("system", messages.getJSONObject(0).getString("role"))
    assertEquals("user", messages.getJSONObject(1).getString("role"))
    assertEquals("assistant", messages.getJSONObject(2).getString("role"))
    assertEquals("tool", messages.getJSONObject(3).getString("role"))
    assertEquals("call_1", messages.getJSONObject(3).getString("tool_call_id"))

    val tool = body.getJSONArray("tools").getJSONObject(0)
    assertEquals("function", tool.getString("type"))
    assertEquals("read_file", tool.getJSONObject("function").getString("name"))
    assertEquals("object", tool.getJSONObject("function").getJSONObject("parameters").getString("type"))
  }

  @Test
  fun `OpenAI routers without the modern parameter fall back to max_tokens`() {
    val client = OpenAIChatCompletionsClient(OkHttpClient())
    val body = bodyOf(
      client.buildRequest(
        openAiProvider,
        openAiModel(maxOutputTokens = 4096, tools = false, maxTokensParameter = false), "k",
        LlmRequest(messages = listOf(LlmMessage(LlmRole.USER, "hi"))),
        stream = false
      )
    )
    assertEquals(4096, body.getInt("max_tokens"))
    assertFalse(body.has("max_completion_tokens"))
    // A model without tool support must not be offered tools at all.
    assertFalse(body.has("tools"))
  }

  @Test
  fun `OpenAI stream captures finish reason and the usage-only final chunk`() {
    val client = OpenAIChatCompletionsClient(OkHttpClient())
    val state = newState()
    val events = mutableListOf<LlmStreamEvent>()

    client.handleData("""{"choices":[{"index":0,"delta":{"role":"assistant","content":"Read"},"finish_reason":null}]}""", state, events::add)
    client.handleData("""{"choices":[{"index":0,"delta":{"content":"ing now"},"finish_reason":null}]}""", state, events::add)
    client.handleData("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_file","arguments":""}}]},"finish_reason":null}]}""", state, events::add)
    client.handleData("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]},"finish_reason":null}]}""", state, events::add)
    client.handleData("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"src\"}"}}]},"finish_reason":null}]}""", state, events::add)
    client.handleData("""{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""", state, events::add)
    // The usage chunk has an empty choices array; parsing must not stop there.
    client.handleData("""{"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":34,"total_tokens":154,"prompt_tokens_details":{"cached_tokens":96},"completion_tokens_details":{"reasoning_tokens":8}}}""", state, events::add)
    assertTrue(client.handleData("[DONE]", state, events::add))

    assertEquals("Reading now", state.content.toString())
    assertEquals(LlmFinishReason.TOOL_CALLS, state.finishReason)
    assertEquals(120, state.usage?.inputTokens)
    assertEquals(34, state.usage?.outputTokens)
    assertEquals(154, state.usage?.totalTokens)
    assertEquals(96, state.usage?.cachedInputTokens)
    assertEquals(8, state.usage?.reasoningTokens)
    val call = state.toolCalls.values.single()
    assertEquals("call_1", call.id)
    assertEquals("src", JSONObject(call.argumentsJson).getString("path"))
  }

  @Test
  fun `OpenAI non streamed completion yields finish reason and usage`() {
    val client = OpenAIChatCompletionsClient(OkHttpClient())
    val state = newState()
    val events = mutableListOf<LlmStreamEvent>()

    client.handleData("""{"choices":[{"index":0,"finish_reason":"length","message":{"role":"assistant","content":"truncated"}}],"usage":{"prompt_tokens":9,"completion_tokens":5,"total_tokens":14}}""", state, events::add)

    assertEquals("truncated", state.content.toString())
    assertEquals(LlmFinishReason.LENGTH, state.finishReason)
    assertEquals(9, state.usage?.inputTokens)
    assertEquals(14, state.usage?.totalTokens)
  }

  @Test
  fun `OpenAI error bodies separate retryable from permanent failures`() {
    val client = OpenAIChatCompletionsClient(OkHttpClient())

    // A permanent request fault must not be retried by the agent loop.
    val invalid = assertThrows(LlmException::class.java) {
      client.handleData("""{"error":{"message":"Unknown parameter: 'foo'.","type":"invalid_request_error"}}""", newState()) { }
    }
    assertEquals(LlmErrorKind.INVALID_RESPONSE, invalid.kind)
    assertEquals("Unknown parameter: 'foo'.", invalid.message)

    val limited = assertThrows(LlmException::class.java) {
      client.handleData("""{"error":{"message":"Rate limit reached","type":"rate_limit_error"}}""", newState()) { }
    }
    assertEquals(LlmErrorKind.RATE_LIMIT, limited.kind)

    val server = assertThrows(LlmException::class.java) {
      client.handleData("""{"error":{"message":"The server had an error","type":"server_error"}}""", newState()) { }
    }
    assertEquals(LlmErrorKind.SERVER, server.kind)
  }

  // ---- OpenAI Responses wire format, streaming and terminals ----

  private val responsesProvider =
    AIProvider("resp", "OpenAI Responses", "https://api.openai.com/v1", LLMProtocol.OPENAI_RESPONSES)

  private fun responsesModel(
    maxOutputTokens: Int? = null,
    tools: Boolean = true,
    reasoning: com.agentisco.settings.model.ReasoningConfig? = null
  ) = AIModel(
    id = "resp-model",
    providerId = "resp",
    modelId = "gpt-5.1",
    displayName = "GPT-5.1",
    maxOutputTokens = maxOutputTokens,
    capabilities = com.agentisco.settings.model.ModelCapabilities(tools = tools),
    reasoning = reasoning
  )

  @Test
  fun `Responses request sends the system prompt as instructions and the transcript as typed items`() {
    val client = OpenAIResponsesClient(OkHttpClient())
    val request = client.buildRequest(
      responsesProvider, responsesModel(maxOutputTokens = 16000), "sk-redacted",
      LlmRequest(
        messages = listOf(
          LlmMessage(LlmRole.SYSTEM, "You are a coding agent."),
          LlmMessage(LlmRole.USER, "Read src/App.tsx"),
          LlmMessage(LlmRole.ASSISTANT, "Let me look.", toolCalls = listOf(LlmToolCall("call_1", "read_file", "{\"path\":\"src/App.tsx\"}"))),
          LlmMessage(LlmRole.TOOL, "export default App", toolCallId = "call_1", toolName = "read_file"),
          // An empty assistant row from persisted history contributes no item.
          LlmMessage(LlmRole.ASSISTANT, "")
        ),
        tools = listOf(LlmToolSpec("read_file", testTool.description, testTool.parametersJsonSchema()))
      ),
      stream = true
    )

    assertEquals("https://api.openai.com/v1/responses", request.url.toString())
    assertEquals("Bearer sk-redacted", request.header("Authorization"))

    val body = bodyOf(request)
    assertTrue(body.getBoolean("stream"))
    assertEquals("gpt-5.1", body.getString("model"))
    // The system prompt has its own field and never appears among the items.
    assertEquals("You are a coding agent.", body.getString("instructions"))
    assertFalse(body.has("messages"))

    val input = body.getJSONArray("input")
    assertEquals(4, input.length())
    val user = input.getJSONObject(0)
    assertEquals("message", user.getString("type"))
    assertEquals("user", user.getString("role"))
    assertEquals("input_text", user.getJSONArray("content").getJSONObject(0).getString("type"))
    assertEquals("Read src/App.tsx", user.getJSONArray("content").getJSONObject(0).getString("text"))
    // The assistant turn splits into its own text item plus one call item.
    assertEquals("output_text", input.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("type"))
    val call = input.getJSONObject(2)
    assertEquals("function_call", call.getString("type"))
    assertEquals("call_1", call.getString("call_id"))
    assertEquals("read_file", call.getString("name"))
    assertEquals("src/App.tsx", JSONObject(call.getString("arguments")).getString("path"))
    // The result is a standalone item bound to the call above by call_id.
    val result = input.getJSONObject(3)
    assertEquals("function_call_output", result.getString("type"))
    assertEquals("call_1", result.getString("call_id"))
    assertEquals("export default App", result.getString("output"))

    // Tool definitions are flat here, unlike Chat Completions' nested function.
    val tool = body.getJSONArray("tools").getJSONObject(0)
    assertEquals("function", tool.getString("type"))
    assertEquals("read_file", tool.getString("name"))
    assertEquals("object", tool.getJSONObject("parameters").getString("type"))

    // Nothing accumulates server-side; every turn replays the whole transcript.
    assertFalse(body.getBoolean("store"))
  }

  @Test
  fun `Responses floors the output budget and asks for reasoning summaries only when enabled`() {
    val client = OpenAIResponsesClient(OkHttpClient())
    val body = bodyOf(
      client.buildRequest(
        responsesProvider,
        responsesModel(
          maxOutputTokens = 512,
          tools = false,
          reasoning = com.agentisco.settings.model.ReasoningConfig(enabled = true, effort = "turbo")
        ),
        "k",
        LlmRequest(messages = listOf(LlmMessage(LlmRole.USER, "hi"))),
        stream = false
      )
    )
    // Thinking is billed against the cap, so a tiny one returns nothing visible.
    assertEquals(8192, body.getInt("max_output_tokens"))
    assertFalse(body.has("tools"))
    // An unknown effort must not be sent verbatim; the provider rejects it.
    assertEquals("medium", body.getJSONObject("reasoning").getString("effort"))
    // Summaries stream only if the request asks, and they arrive as reasoning.
    assertEquals("auto", body.getJSONObject("reasoning").getString("summary"))
    assertFalse(body.has("instructions"))

    val background = bodyOf(
      client.buildRequest(
        responsesProvider,
        responsesModel(
          maxOutputTokens = 20000,
          tools = false,
          reasoning = com.agentisco.settings.model.ReasoningConfig(enabled = true, effort = "high")
        ),
        "k",
        LlmRequest(messages = listOf(LlmMessage(LlmRole.USER, "hi")), disableReasoning = true),
        stream = false
      )
    )
    assertFalse(background.has("reasoning"))
    assertEquals(20000, background.getInt("max_output_tokens"))
  }

  @Test
  fun `Responses stream binds argument deltas to their item and ends on the completed event`() {
    val client = OpenAIResponsesClient(OkHttpClient())
    val state = newState()
    val events = mutableListOf<LlmStreamEvent>()

    assertFalse(client.handleData("""{"type":"response.created","response":{"id":"resp_1","status":"queued"}}""", state, events::add))
    assertFalse(client.handleData("""{"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"msg_1","role":"assistant","content":[]}}""", state, events::add))
    assertFalse(client.handleData("""{"type":"response.reasoning_summary_text.delta","item_id":"rs_1","output_index":0,"summary_index":0,"delta":"Checking the workspace."}""", state, events::add))
    assertFalse(client.handleData("""{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Reading "}""", state, events::add))
    assertFalse(client.handleData("""{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"now"}""", state, events::add))
    // The call is announced before any of its arguments have arrived.
    assertFalse(client.handleData("""{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_abc","name":"read_file","arguments":""}}""", state, events::add))
    // Argument deltas carry no name or call_id — only output_index binds them.
    assertFalse(client.handleData("""{"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":1,"delta":"{\"pa"}""", state, events::add))
    assertFalse(client.handleData("""{"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":1,"delta":"th\":\"src/App.tsx\"}"}""", state, events::add))
    assertTrue(client.handleData("""{"type":"response.completed","response":{"id":"resp_1","status":"completed","output":[],"usage":{"input_tokens":120,"output_tokens":34,"total_tokens":154,"input_tokens_details":{"cached_tokens":96},"output_tokens_details":{"reasoning_tokens":8}}}}""", state, events::add))

    assertEquals("Reading now", state.content.toString())
    assertEquals(LlmFinishReason.TOOL_CALLS, state.finishReason)
    assertEquals(120, state.usage?.inputTokens)
    assertEquals(34, state.usage?.outputTokens)
    assertEquals(96, state.usage?.cachedInputTokens)
    assertEquals(8, state.usage?.reasoningTokens)
    val call = state.toolCalls.values.single()
    assertEquals("call_abc", call.id)
    assertEquals("read_file", call.name)
    assertEquals("src/App.tsx", JSONObject(call.argumentsJson).getString("path"))
    assertTrue(events.any { it is LlmStreamEvent.ToolCallRequested && it.call.name == "read_file" })
    assertEquals(listOf("Reading ", "now"), events.filterIsInstance<LlmStreamEvent.Token>().map { it.text })
    // Hidden thinking is reported separately and never mixed into the answer.
    assertTrue(events.any { it is LlmStreamEvent.ReasoningToken && it.text == "Checking the workspace." })
  }

  @Test
  fun `Responses keeps parallel calls apart and trusts the finalized arguments`() {
    val client = OpenAIResponsesClient(OkHttpClient())
    val state = newState()
    val events = mutableListOf<LlmStreamEvent>()

    client.handleData("""{"type":"response.output_item.added","output_index":2,"item":{"type":"function_call","id":"fc_a","call_id":"call_a","name":"read_file","arguments":""}}""", state, events::add)
    client.handleData("""{"type":"response.output_item.added","output_index":5,"item":{"type":"function_call","id":"fc_b","call_id":"call_b","name":"list_files","arguments":""}}""", state, events::add)
    client.handleData("""{"type":"response.function_call_arguments.delta","output_index":5,"delta":"{}"}""", state, events::add)
    client.handleData("""{"type":"response.function_call_arguments.delta","output_index":2,"delta":"{\"path\":\"a.tsx\"}"}""", state, events::add)
    // The done event is authoritative, even when it differs from the fragments.
    client.handleData("""{"type":"response.function_call_arguments.done","output_index":2,"arguments":"{\"path\":\"b.tsx\"}"}""", state, events::add)
    assertTrue(client.handleData("""{"type":"response.completed","response":{"status":"completed","output":[]}}""", state, events::add))

    assertEquals(listOf(2, 5), state.toolCalls.keys.toList())
    assertEquals("b.tsx", JSONObject(state.toolCalls[2]!!.argumentsJson).getString("path"))
    assertEquals("{}", state.toolCalls[5]!!.argumentsJson)
    assertEquals(2, events.filterIsInstance<LlmStreamEvent.ToolCallRequested>().size)
  }

  @Test
  fun `Responses incomplete output reports length and refusals reach the user as text`() {
    val client = OpenAIResponsesClient(OkHttpClient())
    val state = newState()
    val events = mutableListOf<LlmStreamEvent>()

    assertFalse(client.handleData("""{"type":"response.refusal.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"I cannot help with that."}""", state, events::add))
    assertTrue(client.handleData("""{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[],"usage":{"output_tokens":4096}}}""", state, events::add))

    assertEquals(LlmFinishReason.LENGTH, state.finishReason)
    assertEquals("I cannot help with that.", state.content.toString())
    // A counter the provider never sent stays absent rather than becoming zero.
    assertNull(state.usage?.inputTokens)
    assertEquals(4096, state.usage?.outputTokens)
    // Gateways that still close with the Chat Completions sentinel stay legal.
    assertTrue(client.handleData("[DONE]", state, events::add))
  }

  @Test
  fun `Responses error events separate retryable from permanent failures`() {
    val client = OpenAIResponsesClient(OkHttpClient())

    val limited = assertThrows(LlmException::class.java) {
      client.handleData("""{"type":"error","code":"rate_limit_exceeded","message":"High traffic, retry soon","param":null}""", newState()) { }
    }
    assertEquals(LlmErrorKind.RATE_LIMIT, limited.kind)
    assertEquals("High traffic, retry soon", limited.message)

    // A blocked prompt fails identically on every retry.
    val blocked = assertThrows(LlmException::class.java) {
      client.handleData("""{"type":"response.failed","response":{"status":"failed","error":{"code":"misalignment_policy_violation","message":"Request blocked"}}}""", newState()) { }
    }
    assertEquals(LlmErrorKind.INVALID_RESPONSE, blocked.kind)

    val quota = assertThrows(LlmException::class.java) {
      client.handleData("""{"type":"error","code":"insufficient_quota","message":"Set up billing to continue"}""", newState()) { }
    }
    assertEquals(LlmErrorKind.AUTH, quota.kind)

    val transient = assertThrows(LlmException::class.java) {
      client.handleData("""{"type":"error","code":"server_error","message":"The server had an error"}""", newState()) { }
    }
    assertEquals(LlmErrorKind.SERVER, transient.kind)
  }

  @Test
  fun `Responses non streamed body absorbs output items without exposing raw reasoning`() {
    val client = OpenAIResponsesClient(OkHttpClient())
    val state = newState()
    val events = mutableListOf<LlmStreamEvent>()

    val response = JSONObject().apply {
      put("object", "response")
      put("status", "completed")
      put("output", JSONArray().apply {
        put(
          JSONObject().put("type", "reasoning").put("id", "rs_1").put("summary", JSONArray().put(
            JSONObject().put("type", "summary_text").put("text", "private deliberation")
          ))
        )
        put(
          JSONObject().put("type", "message").put("role", "assistant").put("content", JSONArray().put(
            JSONObject().put("type", "output_text").put("text", "Done")
          ))
        )
        put(
          JSONObject().put("type", "function_call").put("call_id", "call_9")
            .put("name", "read_file").put("arguments", "{\"path\":\"src/App.tsx\"}")
        )
      })
      put("usage", JSONObject().put("input_tokens", 42).put("output_tokens", 17).put("total_tokens", 59))
    }

    assertTrue(client.handleData(response.toString(), state, events::add))
    assertEquals("Done", state.content.toString())
    assertEquals(LlmFinishReason.TOOL_CALLS, state.finishReason)
    assertEquals(59, state.usage?.totalTokens)
    val call = state.toolCalls.values.single()
    assertEquals("call_9", call.id)
    assertEquals("src/App.tsx", JSONObject(call.argumentsJson).getString("path"))
    assertFalse(events.any { it is LlmStreamEvent.Token && it.text == "private deliberation" })
  }

  @Test
  fun `Responses streaming round trip terminates on its own completion event`() {
    val sse = buildString {
      append("event: response.created\n")
      append("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\"}}\n\n")
      append("data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"delta\":\"hello\"}\n\n")
      append("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[],\"usage\":{\"output_tokens\":5}}}\n\n")
      // Nothing downstream may be parsed once the terminal event has arrived.
      append("data: not-json-at-all\n\n")
    }
    val http = OkHttpClient.Builder().addInterceptor { chain ->
      okhttp3.Response.Builder()
        .request(chain.request())
        .protocol(okhttp3.Protocol.HTTP_1_1)
        .code(200)
        .message("mock")
        .body(sse.toResponseBody("text/event-stream".toMediaType()))
        .build()
    }.build()
    val events = mutableListOf<LlmStreamEvent>()

    runBlocking {
      OpenAIResponsesClient(http).streamChat(
        responsesProvider, responsesModel(tools = false), "k",
        LlmRequest(messages = listOf(LlmMessage(LlmRole.USER, "hi"))),
        events::add
      )
    }

    assertTrue(events.first() is LlmStreamEvent.Started)
    val completed = events.filterIsInstance<LlmStreamEvent.Completed>().single()
    assertEquals("hello", completed.message.content)
    assertEquals(5, completed.message.usage?.outputTokens)
    assertFalse(events.any { it is LlmStreamEvent.Failed })
  }

  // ---- Anthropic Messages wire format and response normalization ----

  private val anthropicProvider =
    AIProvider("anthropic", "Anthropic", "https://api.anthropic.com", LLMProtocol.ANTHROPIC_MESSAGES)

  private fun anthropicModel(maxOutputTokens: Int? = null) = AIModel(
    id = "claude-model",
    providerId = "anthropic",
    modelId = "claude-sonnet-4-5",
    displayName = "Claude Sonnet 4.5",
    maxOutputTokens = maxOutputTokens,
    capabilities = com.agentisco.settings.model.ModelCapabilities(tools = true)
  )

  @Test
  fun `Anthropic request maps system tool calls and results onto the messages wire format`() {
    val client = AnthropicMessagesClient(OkHttpClient())

    val request = client.buildRequest(
      anthropicProvider, anthropicModel(maxOutputTokens = 8000), "sk-ant-redacted",
      LlmRequest(
        messages = listOf(
          LlmMessage(LlmRole.SYSTEM, "You are a coding agent."),
          LlmMessage(LlmRole.USER, "Read src/App.tsx"),
          LlmMessage(LlmRole.ASSISTANT, "", toolCalls = listOf(LlmToolCall("toolu_01", "read_file", "{\"path\":\"src/App.tsx\"}"))),
          LlmMessage(LlmRole.TOOL, "export default App", toolCallId = "toolu_01", toolName = "read_file"),
          // A blank row from persisted history must never reach the API.
          LlmMessage(LlmRole.ASSISTANT, "")
        ),
        tools = listOf(LlmToolSpec("read_file", testTool.description, testTool.parametersJsonSchema()))
      ),
      stream = true
    )

    assertEquals("https://api.anthropic.com/v1/messages", request.url.toString())
    assertEquals("sk-ant-redacted", request.header("x-api-key"))
    assertEquals("2023-06-01", request.header("anthropic-version"))

    val body = bodyOf(request)
    assertEquals("claude-sonnet-4-5", body.getString("model"))
    assertTrue(body.getBoolean("stream"))
    assertEquals(8000, body.getInt("max_tokens"))
    // The system prompt travels as its own top-level field, never as a message.
    assertEquals("You are a coding agent.", body.getString("system"))

    val tool = body.getJSONArray("tools").getJSONObject(0)
    assertEquals("read_file", tool.getString("name"))
    assertEquals("object", tool.getJSONObject("input_schema").getString("type"))

    val messages = body.getJSONArray("messages")
    assertEquals(3, messages.length())
    assertEquals("user", messages.getJSONObject(0).getString("role"))
    assertEquals("Read src/App.tsx", messages.getJSONObject(0).getString("content"))

    // tool_use carries `input` as a JSON object, never as an argument string.
    val assistantBlock = messages.getJSONObject(1).getJSONArray("content").getJSONObject(0)
    assertEquals("tool_use", assistantBlock.getString("type"))
    assertEquals("toolu_01", assistantBlock.getString("id"))
    assertEquals("src/App.tsx", assistantBlock.getJSONObject("input").getString("path"))

    // A tool result is a user message wrapping one tool_result block.
    val result = messages.getJSONObject(2)
    assertEquals("user", result.getString("role"))
    val resultBlock = result.getJSONArray("content").getJSONObject(0)
    assertEquals("tool_result", resultBlock.getString("type"))
    assertEquals("toolu_01", resultBlock.getString("tool_use_id"))
    assertEquals("export default App", resultBlock.getString("content"))
  }

  @Test
  fun `Anthropic base url already ending in v1 is not duplicated`() {
    val client = AnthropicMessagesClient(OkHttpClient())
    val request = client.buildRequest(
      anthropicProvider.copy(baseUrl = "https://api.anthropic.com/v1"), anthropicModel(), "k",
      LlmRequest(messages = listOf(LlmMessage(LlmRole.USER, "hi"))),
      stream = false
    )
    assertEquals("https://api.anthropic.com/v1/messages", request.url.toString())
    assertFalse(bodyOf(request).getBoolean("stream"))
  }

  @Test
  fun `Anthropic stream normalizes text tool fragments stop reason and usage`() {
    val client = AnthropicMessagesClient(OkHttpClient())
    val state = newState()
    val events = mutableListOf<LlmStreamEvent>()

    assertFalse(client.handleData("""{"type":"message_start","message":{"id":"msg_01","role":"assistant","model":"claude-sonnet-4-5","content":[],"stop_reason":null,"usage":{"input_tokens":1204,"cache_read_input_tokens":300,"output_tokens":4}}}""", state, events::add))
    client.handleData("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""", state, events::add)
    client.handleData("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Reading "}}""", state, events::add)
    client.handleData("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"now"}}""", state, events::add)
    client.handleData("""{"type":"content_block_stop","index":0}""", state, events::add)
    client.handleData("""{"type":"ping"}""", state, events::add)
    client.handleData("""{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_01","name":"read_file","input":{}}}""", state, events::add)
    client.handleData("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"pa"}}""", state, events::add)
    client.handleData("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"th\":\"src\"}"}}""", state, events::add)
    client.handleData("""{"type":"content_block_stop","index":1}""", state, events::add)
    // The terminal stop_reason and cumulative output count arrive only here.
    client.handleData("""{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":87}}""", state, events::add)
    assertTrue(client.handleData("""{"type":"message_stop"}""", state, events::add))

    assertEquals("Reading now", state.content.toString())
    assertEquals(LlmFinishReason.TOOL_CALLS, state.finishReason)
    // Prompt counters come from message_start and survive the later delta.
    assertEquals(1204, state.usage?.inputTokens)
    assertEquals(300, state.usage?.cachedInputTokens)
    assertEquals(87, state.usage?.outputTokens)
    val call = state.toolCalls.values.single()
    assertEquals("toolu_01", call.id)
    assertEquals("read_file", call.name)
    assertEquals("src", JSONObject(call.argumentsJson).getString("path"))
    assertTrue(events.any { it is LlmStreamEvent.Token && it.text == "Reading " })
  }

  @Test
  fun `Anthropic non streamed message yields stop reason usage and tool call`() {
    val client = AnthropicMessagesClient(OkHttpClient())
    val state = newState()
    val events = mutableListOf<LlmStreamEvent>()

    assertTrue(
      client.handleData(
        """{"id":"msg_02","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":[{"type":"text","text":"Done"},{"type":"tool_use","id":"toolu_02","name":"read_file","input":{"path":"src/main.kt"}}],"stop_reason":"tool_use","usage":{"input_tokens":42,"output_tokens":17}}""",
        state, events::add
      )
    )

    assertEquals("Done", state.content.toString())
    assertEquals(LlmFinishReason.TOOL_CALLS, state.finishReason)
    assertEquals(42, state.usage?.inputTokens)
    assertEquals(17, state.usage?.outputTokens)
    assertEquals("src/main.kt", JSONObject(state.toolCalls.values.single().argumentsJson).getString("path"))
  }

  @Test
  fun `Anthropic truncation maps to a length finish reason without inventing counters`() {
    val client = AnthropicMessagesClient(OkHttpClient())
    val state = newState()

    client.handleData("""{"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":4096}}""", state) { }

    assertEquals(LlmFinishReason.LENGTH, state.finishReason)
    assertEquals(4096, state.usage?.outputTokens)
    // Anthropic reports no total and never sent a prompt count here.
    assertNull(state.usage?.inputTokens)
    assertNull(state.usage?.totalTokens)
  }

  @Test
  fun `Anthropic error events separate retryable from permanent failures`() {
    val client = AnthropicMessagesClient(OkHttpClient())

    val overloaded = assertThrows(LlmException::class.java) {
      client.handleData("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""", newState()) { }
    }
    assertEquals(LlmErrorKind.SERVER, overloaded.kind)
    assertEquals("Overloaded", overloaded.message)

    // A permanent request fault must not be retried by the agent loop.
    val invalid = assertThrows(LlmException::class.java) {
      client.handleData("""{"type":"error","error":{"type":"invalid_request_error","message":"messages.2.content: at least one item required"}}""", newState()) { }
    }
    assertEquals(LlmErrorKind.INVALID_RESPONSE, invalid.kind)

    val limited = assertThrows(LlmException::class.java) {
      client.handleData("""{"type":"error","error":{"type":"rate_limit_error","message":"Rate limited"}}""", newState()) { }
    }
    assertEquals(LlmErrorKind.RATE_LIMIT, limited.kind)
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
