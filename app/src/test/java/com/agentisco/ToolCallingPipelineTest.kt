package com.agentisco

import androidx.test.core.app.ApplicationProvider
import com.agentisco.agent.llm.BaseLlmClient
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
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    permissions = com.agentisco.agent.model.AgentPermissions(),
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
