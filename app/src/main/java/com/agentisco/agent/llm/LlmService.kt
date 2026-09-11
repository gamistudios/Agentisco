package com.agentisco.agent.llm

import com.agentisco.settings.model.AIModel
import com.agentisco.settings.model.AIProvider
import com.agentisco.settings.model.LLMProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Application-level gateway between the agent runtime and configured providers.
 * Protocol selection is driven by [AIProvider.protocol] configuration — never
 * by provider identity. Implementations parse real SSE streams; nothing here
 * fabricates responses.
 *
 * AgentRuntime → LlmService → protocol client → provider HTTP API
 */
class LlmService(
  httpClient: OkHttpClient? = null
) {
  private val http = httpClient ?: OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(300, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

  suspend fun streamChat(
    provider: AIProvider,
    model: AIModel,
    apiKey: String,
    request: LlmRequest,
    onEvent: (LlmStreamEvent) -> Unit
  ): Unit = when (provider.protocol) {
    LLMProtocol.OPENAI_CHAT_COMPLETIONS -> OpenAIChatCompletionsClient(http).streamChat(provider, model, apiKey, request, onEvent)
    LLMProtocol.ANTHROPIC_MESSAGES -> AnthropicMessagesClient(http).streamChat(provider, model, apiKey, request, onEvent)
  }

  /** Real connection test against the provider endpoint. Returns a user-safe message on failure. */
  suspend fun testConnection(provider: AIProvider, apiKey: String): Pair<Boolean, String> = when (provider.protocol) {
    LLMProtocol.OPENAI_CHAT_COMPLETIONS -> OpenAIChatCompletionsClient(http).testConnection(provider, apiKey)
    LLMProtocol.ANTHROPIC_MESSAGES -> AnthropicMessagesClient(http).testConnection(provider, apiKey)
  }
}

/** Shared HTTP/SSE plumbing for both protocol clients. */
internal abstract class BaseLlmClient(private val http: OkHttpClient) {

  protected abstract fun buildRequest(provider: AIProvider, model: AIModel, apiKey: String, request: LlmRequest, stream: Boolean): Request

  /** Parses one SSE data payload; returns true when the stream is complete. */
  protected abstract fun handleData(data: String, state: StreamState, onEvent: (LlmStreamEvent) -> Unit): Boolean

  protected abstract suspend fun testConnection(provider: AIProvider, apiKey: String): Pair<Boolean, String>

  protected class StreamState {
    val content = StringBuilder()
    val toolCalls = LinkedHashMap<String, LlmToolCall>()
    var sawAnyData = false
  }

  suspend fun streamChat(
    provider: AIProvider,
    model: AIModel,
    apiKey: String,
    request: LlmRequest,
    onEvent: (LlmStreamEvent) -> Unit
  ): Unit = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) throw LlmException("No API key configured for provider \"${provider.name}\".", LlmErrorKind.AUTH)
    if (provider.baseUrl.isBlank()) throw LlmException("No base URL configured for provider \"${provider.name}\".", LlmErrorKind.UNSUPPORTED)

    val stream = model.capabilities.streaming
    val state = StreamState()
    var interrupted = false
    val call = http.newCall(buildRequest(provider, model, apiKey, request, stream))

    try {
      onEvent(LlmStreamEvent.Started)
      call.execute().use { response ->
        if (!response.isSuccessful) {
          val body = response.body?.string().orEmpty().take(2000)
          throw httpError(response.code, body)
        }
        if (!stream) {
          val body = response.body?.string().orEmpty()
          state.sawAnyData = true
          handleData(body, state, onEvent)
        } else {
          val source = response.body?.source() ?: throw LlmException("Empty response body.", LlmErrorKind.INVALID_RESPONSE)
          while (true) {
            if (!currentCoroutineContext().isActive) {
              call.cancel()
              interrupted = true
              break
            }
            val line = try {
              source.readUtf8Line() ?: break
            } catch (e: IOException) {
              if (currentCoroutineContext().isActive) throw e else { interrupted = true; break }
            }
            if (line.isBlank() || line.startsWith(":") || line.startsWith("event:")) continue
            val data = when {
              line.startsWith("data:") -> line.removePrefix("data:").trim()
              else -> line.trim() // some providers omit the "data:" prefix
            }
            if (data.isEmpty()) continue
            state.sawAnyData = true
            val done = handleData(data, state, onEvent)
            if (done) break
          }
        }
      }
      if (interrupted) {
        onEvent(LlmStreamEvent.Interrupted)
        return@withContext
      }
      if (!state.sawAnyData) throw LlmException("Provider returned an empty stream.", LlmErrorKind.INVALID_RESPONSE)
      onEvent(LlmStreamEvent.Completed(LlmMessage(role = LlmRole.ASSISTANT, content = state.content.toString(), toolCalls = state.toolCalls.values.toList())))
    } catch (e: LlmException) {
      onEvent(LlmStreamEvent.Failed(e))
      throw e
    } catch (e: IOException) {
      val err = LlmException(
        if (e.message?.contains("timeout", ignoreCase = true) == true) "Connection timed out talking to \"${provider.name}\"."
        else "Network error contacting \"${provider.name}\": ${e.message ?: "connection failed"}",
        if (e.message?.contains("timeout", ignoreCase = true) == true) LlmErrorKind.TIMEOUT else LlmErrorKind.NETWORK,
        cause = e
      )
      onEvent(LlmStreamEvent.Failed(err))
      throw err
    }
  }

  protected fun httpError(code: Int, body: String): LlmException {
    val providerMessage = runCatching {
      JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    }.getOrNull()
    val friendly = when (code) {
      401 -> "Authentication failed: the API key was rejected."
      403 -> "Permission denied by the provider."
      404 -> "Invalid endpoint or unknown model (404). Check the base URL and model ID."
      429 -> "Rate limited by the provider. Try again shortly."
      in 500..599 -> "Provider server error ($code). The service may be temporarily down."
      else -> "Unexpected provider response (HTTP $code)."
    }
    return LlmException(listOfNotNull(friendly, providerMessage).joinToString(" "), kindFor(code), code)
  }

  private fun kindFor(code: Int): LlmErrorKind = when (code) {
    401 -> LlmErrorKind.AUTH
    403 -> LlmErrorKind.PERMISSION
    404 -> LlmErrorKind.NOT_FOUND
    429 -> LlmErrorKind.RATE_LIMIT
    in 500..599 -> LlmErrorKind.SERVER
    else -> LlmErrorKind.INVALID_RESPONSE
  }

  protected fun parseToolArguments(raw: String?): String = if (raw.isNullOrBlank()) "{}" else raw

  protected fun jsonBody(obj: JSONObject) = obj.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
}

/** OpenAI Chat Completions protocol (also used by OpenAI-compatible routers). */
internal class OpenAIChatCompletionsClient(http: OkHttpClient) : BaseLlmClient(http) {

  override fun buildRequest(provider: AIProvider, model: AIModel, apiKey: String, request: LlmRequest, stream: Boolean): Request {
    val body = JSONObject().apply {
      put("model", model.modelId)
      put("stream", stream)
      val msgs = JSONArray()
      for (m in request.messages) {
        val o = JSONObject()
        o.put("role", when (m.role) {
          LlmRole.SYSTEM -> "system"
          LlmRole.USER -> "user"
          LlmRole.ASSISTANT -> "assistant"
          LlmRole.TOOL -> "tool"
        })
        o.put("content", m.content)
        if (m.role == LlmRole.ASSISTANT && m.toolCalls.isNotEmpty()) {
          o.put("tool_calls", JSONArray().apply {
            m.toolCalls.forEach { tc ->
              put(JSONObject().apply {
                put("id", tc.id)
                put("type", "function")
                put("function", JSONObject().apply {
                  put("name", tc.name)
                  put("arguments", tc.argumentsJson)
                })
              })
            }
          })
        }
        if (m.role == LlmRole.TOOL) o.put("tool_call_id", m.toolCallId ?: "")
        msgs.put(o)
      }
      put("messages", msgs)
      if (request.tools.isNotEmpty() && model.capabilities.tools) {
        put("tools", JSONArray().apply {
          request.tools.forEach { t ->
            put(JSONObject().apply {
              put("type", "function")
              put("function", JSONObject().apply {
                put("name", t.name)
                put("description", t.description)
                put("parameters", JSONObject(t.parametersJsonSchema))
              })
            })
          }
        })
      }
      val maxTokens = request.maxOutputTokens ?: model.maxOutputTokens
      if (maxTokens != null) {
        if (model.capabilities.maxTokensParameter) put("max_completion_tokens", maxTokens) else put("max_tokens", maxTokens)
      }
      request.temperature?.let { put("temperature", it) }
      model.reasoning?.takeIf { it.enabled }?.let { put("reasoning_effort", it.effort) }
    }
    return Request.Builder()
      .url(provider.baseUrl.trimEnd('/') + "/chat/completions")
      .header("Authorization", "Bearer $apiKey")
      .post(jsonBody(body))
      .build()
  }

  override fun handleData(data: String, state: StreamState, onEvent: (LlmStreamEvent) -> Unit): Boolean {
    if (data == "[DONE]") return true
    val obj = runCatching { JSONObject(data) }.getOrElse {
      throw LlmException("Provider returned invalid JSON.", LlmErrorKind.INVALID_RESPONSE)
    }
    if (obj.has("error")) {
      val msg = obj.optJSONObject("error")?.optString("message") ?: "Provider error"
      throw LlmException(msg, LlmErrorKind.SERVER)
    }
    val choices = obj.optJSONArray("choices") ?: return false
    val choice = choices.optJSONObject(0) ?: return false
    // Streaming responses carry incremental "delta"; non-streaming carry the full "message".
    val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return false
    delta.optString("content", "").takeIf { it.isNotEmpty() }?.let {
      state.content.append(it)
      onEvent(LlmStreamEvent.Token(it))
    }
    val toolCalls = delta.optJSONArray("tool_calls")
    if (toolCalls != null) {
      for (i in 0 until toolCalls.length()) {
        val tc = toolCalls.optJSONObject(i) ?: continue
        val idx = tc.optInt("index", i)
        val key = tc.optString("id").ifBlank { "call-$idx" }
        val fn = tc.optJSONObject("function")
        val existing = state.toolCalls[key]
        state.toolCalls[key] = LlmToolCall(
          id = existing?.id ?: key,
          name = existing?.name ?: fn?.optString("name").orEmpty(),
          argumentsJson = (existing?.argumentsJson ?: "") + fn?.optString("arguments").orEmpty()
        )
      }
    }
    return false
  }

  override suspend fun testConnection(provider: AIProvider, apiKey: String): Pair<Boolean, String> {
    return try {
      val request = Request.Builder()
        .url(provider.baseUrl.trimEnd('/') + "/models")
        .header("Authorization", "Bearer $apiKey")
        .get()
        .build()
      http.newCall(request).execute().use { response ->
        when {
          response.isSuccessful -> true to "Connected"
          else -> {
            val body = response.body?.string().orEmpty().take(2000)
            false to httpError(response.code, body).message ?: "Connection failed"
          }
        }
      }
    } catch (e: IOException) {
      false to "Network error: ${e.message ?: "connection failed"}"
    }
  }
}

/** Anthropic Messages protocol. */
internal class AnthropicMessagesClient(http: OkHttpClient) : BaseLlmClient(http) {

  override fun buildRequest(provider: AIProvider, model: AIModel, apiKey: String, request: LlmRequest, stream: Boolean): Request {
    val body = JSONObject().apply {
      put("model", model.modelId)
      put("stream", stream)
      put("max_tokens", request.maxOutputTokens ?: model.maxOutputTokens ?: 4096)
      val systemText = request.messages.filter { it.role == LlmRole.SYSTEM }.joinToString("\n") { it.content }
      if (systemText.isNotBlank()) put("system", systemText)
      val msgs = JSONArray()
      for (m in request.messages.filter { it.role != LlmRole.SYSTEM }) {
        when {
          m.role == LlmRole.TOOL -> msgs.put(JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply {
              put(JSONObject().apply {
                put("type", "tool_result")
                put("tool_use_id", m.toolCallId ?: "")
                put("content", m.content)
              })
            })
          })
          m.role == LlmRole.ASSISTANT && m.toolCalls.isNotEmpty() -> msgs.put(JSONObject().apply {
            put("role", "assistant")
            put("content", JSONArray().apply {
              if (m.content.isNotBlank()) put(JSONObject().apply { put("type", "text"); put("text", m.content) })
              m.toolCalls.forEach { tc ->
                put(JSONObject().apply {
                  put("type", "tool_use")
                  put("id", tc.id)
                  put("name", tc.name)
                  put("input", JSONObject(parseToolArguments(tc.argumentsJson)))
                })
              }
            })
          })
          else -> msgs.put(JSONObject().apply {
            put("role", if (m.role == LlmRole.ASSISTANT) "assistant" else "user")
            put("content", m.content)
          })
        }
      }
      put("messages", msgs)
      if (request.tools.isNotEmpty() && model.capabilities.tools) {
        put("tools", JSONArray().apply {
          request.tools.forEach { t ->
            put(JSONObject().apply {
              put("name", t.name)
              put("description", t.description)
              put("input_schema", JSONObject(t.parametersJsonSchema))
            })
          }
        })
      }
    }
    return Request.Builder()
      .url(provider.baseUrl.trimEnd('/') + "/v1/messages")
      .header("x-api-key", apiKey)
      .header("anthropic-version", "2023-06-01")
      .post(jsonBody(body))
      .build()
  }

  override fun handleData(data: String, state: StreamState, onEvent: (LlmStreamEvent) -> Unit): Boolean {
    val obj = runCatching { JSONObject(data) }.getOrElse {
      throw LlmException("Provider returned invalid JSON.", LlmErrorKind.INVALID_RESPONSE)
    }
    when (obj.optString("type")) {
      "error" -> throw LlmException(
        obj.optJSONObject("error")?.optString("message") ?: "Provider error",
        LlmErrorKind.SERVER
      )
      // Non-streaming responses arrive as a single "message" payload.
      "message" -> {
        val content = obj.optJSONArray("content")
        if (content != null) {
          for (i in 0 until content.length()) {
            when (content.optJSONObject(i)?.optString("type")) {
              "text" -> content.optJSONObject(i)?.optString("text", "")?.takeIf { it.isNotEmpty() }?.let {
                state.content.append(it)
                onEvent(LlmStreamEvent.Token(it))
              }
              "tool_use" -> content.optJSONObject(i)?.let { b ->
                val id = b.optString("id")
                state.toolCalls[id] = LlmToolCall(id = id, name = b.optString("name"), argumentsJson = b.optJSONObject("input")?.toString() ?: "{}")
              }
            }
          }
        }
        return true
      }
      "content_block_start" -> {
        val block = obj.optJSONObject("content_block")
        if (block?.optString("type") == "tool_use") {
          val id = block.optString("id")
          state.toolCalls[id] = LlmToolCall(id = id, name = block.optString("name"), argumentsJson = "")
        }
      }
      "content_block_delta" -> {
        val delta = obj.optJSONObject("delta") ?: return false
        when (delta.optString("type")) {
          "text_delta" -> delta.optString("text", "").takeIf { it.isNotEmpty() }?.let {
            state.content.append(it)
            onEvent(LlmStreamEvent.Token(it))
          }
          "thinking_delta" -> delta.optString("thinking", "").takeIf { it.isNotEmpty() }?.let { onEvent(LlmStreamEvent.ReasoningToken(it)) }
          "input_json_delta" -> {
            val last = state.toolCalls.values.lastOrNull()
            if (last != null) {
              state.toolCalls[last.id] = last.copy(argumentsJson = last.argumentsJson + delta.optString("partial_json"))
            }
          }
        }
      }
      "message_stop" -> return true
    }
    return false
  }

  override suspend fun testConnection(provider: AIProvider, apiKey: String): Pair<Boolean, String> {
    return try {
      val request = Request.Builder()
        .url(provider.baseUrl.trimEnd('/') + "/v1/models")
        .header("x-api-key", apiKey)
        .header("anthropic-version", "2023-06-01")
        .get()
        .build()
      http.newCall(request).execute().use { response ->
        when {
          response.isSuccessful -> true to "Connected"
          else -> {
            val body = response.body?.string().orEmpty().take(2000)
            false to httpError(response.code, body).message ?: "Connection failed"
          }
        }
      }
    } catch (e: IOException) {
      false to "Network error: ${e.message ?: "connection failed"}"
    }
  }
}
