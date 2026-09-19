package com.agentisco.agent.llm

import android.util.Base64
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

  /** The in-flight streaming call, so Stop can abort a blocked socket read. */
  @Volatile
  private var activeCall: okhttp3.Call? = null

  /** Cancels the currently streaming LLM request (safe to call anytime). */
  fun cancelActive() {
    LlmStreamRegistry.cancelActive()
  }

  suspend fun streamChat(
    provider: AIProvider,
    model: AIModel,
    apiKey: String,
    request: LlmRequest,
    onEvent: (LlmStreamEvent) -> Unit
  ): Unit = when (provider.protocol) {
    LLMProtocol.OPENAI_CHAT_COMPLETIONS -> OpenAIChatCompletionsClient(http).streamChat(provider, model, apiKey, request, onEvent)
    LLMProtocol.ANTHROPIC_MESSAGES -> AnthropicMessagesClient(http).streamChat(provider, model, apiKey, request, onEvent)
    LLMProtocol.GOOGLE_GEMINI -> GeminiNativeClient(http).streamChat(provider, model, apiKey, request, onEvent)
  }

  /**
   * Real connection verification: first a free model-listing probe, then — only
   * if that fails — a minimal chat request through the same protocol
   * implementation the agent runtime uses. Never exposes the API key.
   */
  suspend fun testConnection(provider: AIProvider, model: AIModel?, apiKey: String): Pair<Boolean, String> {
    if (apiKey.isBlank()) return false to "No API key configured for \"${provider.name}\"."
    if (provider.baseUrl.isBlank()) return false to "No base URL configured for \"${provider.name}\"."

    val client = when (provider.protocol) {
      LLMProtocol.OPENAI_CHAT_COMPLETIONS -> OpenAIChatCompletionsClient(http)
      LLMProtocol.ANTHROPIC_MESSAGES -> AnthropicMessagesClient(http)
      LLMProtocol.GOOGLE_GEMINI -> GeminiNativeClient(http)
    }

    // Stage 1: free credential/endpoint probe via the provider's model listing.
    val (probeOk, probeMessage) = client.probeModels(provider, apiKey)
    if (probeOk) {
      return true to (model?.let { "Connected · ${it.displayName}" } ?: "Connected")
    }

    if (model == null) {
      return false to "$probeMessage Also add a model to this provider before using the agent."
    }

    // Stage 2 fallback: end-to-end request via the agent's own request path —
    // covers routers that don't implement /models and validates the model itself.
    val collected = StringBuilder()
    return try {
      streamChat(
        provider = provider,
        model = model,
        apiKey = apiKey,
        request = LlmRequest(
          messages = listOf(LlmMessage(LlmRole.USER, "Connection test. Reply with exactly: PONG")),
          tools = emptyList(),
          maxOutputTokens = 16
        )
      ) { event ->
        if (event is LlmStreamEvent.Token) collected.append(event.text)
      }
      val reply = collected.toString().trim()
      if (reply.isNotEmpty()) {
        true to "Connected · ${model.displayName}"
      } else {
        false to "Provider returned an empty response for ${model.displayName}."
      }
    } catch (e: LlmException) {
      false to (e.message ?: probeMessage)
    } catch (e: IOException) {
      false to "Network error: ${e.message ?: probeMessage}"
    }
  }
}

/** Shared HTTP/SSE plumbing for both protocol clients. */
internal abstract class BaseLlmClient(protected val http: OkHttpClient) {

  internal abstract fun buildRequest(provider: AIProvider, model: AIModel, apiKey: String, request: LlmRequest, stream: Boolean): Request

  /** Parses one SSE data payload; returns true when the stream is complete. */
  internal abstract fun handleData(data: String, state: StreamState, onEvent: (LlmStreamEvent) -> Unit): Boolean

  /** Free credential/endpoint probe via the provider's model listing (no tokens billed). */
  internal abstract suspend fun probeModels(provider: AIProvider, apiKey: String): Pair<Boolean, String>

  internal class StreamState {
    val content = StringBuilder()
    // Keyed by provider block index: OpenAI streams tool-call argument
    // fragments across chunks where only the first carries the id/name.
    val toolCalls = LinkedHashMap<Int, LlmToolCall>()
    var finishReason: LlmFinishReason? = null
    var usage: LlmUsage? = null
    var sawAnyData = false
  }

  /** org.json renders JSON null as the literal string "null" — treat it as absent. */
  internal fun cleanWireString(v: String?): String = if (v.isNullOrEmpty() || v == "null") "" else v

  internal fun normalizeArgs(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text == "null") return "{}"
    // Never double-parse fragments; normalize only complete argument strings so
    // the wire format is always a valid JSON object string.
    return runCatching { JSONObject(text).toString() }.getOrDefault(text)
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
    LlmStreamRegistry.activeCall = call

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
      onEvent(
        LlmStreamEvent.Completed(
          LlmMessage(
            role = LlmRole.ASSISTANT,
            content = state.content.toString(),
            toolCalls = state.toolCalls.values.toList(),
            finishReason = state.finishReason,
            usage = state.usage
          )
        )
      )
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
    } finally {
      LlmStreamRegistry.clear(call)
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

  override internal fun buildRequest(provider: AIProvider, model: AIModel, apiKey: String, request: LlmRequest, stream: Boolean): Request {
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
      model.reasoning?.takeIf { it.enabled && !request.disableReasoning }?.let { put("reasoning_effort", it.effort) }
    }
    return Request.Builder()
      .url(provider.baseUrl.trimEnd('/') + "/chat/completions")
      .header("Authorization", "Bearer $apiKey")
      .post(jsonBody(body))
      .build()
  }

  override internal fun handleData(data: String, state: StreamState, onEvent: (LlmStreamEvent) -> Unit): Boolean {
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
        val fn = tc.optJSONObject("function")
        val fragmentId = cleanWireString(tc.optString("id"))
        val fragmentName = cleanWireString(fn?.optString("name"))
        val fragmentArgs = fn?.optString("arguments") ?: ""
        val existing = state.toolCalls[idx]
        state.toolCalls[idx] = LlmToolCall(
          id = existing?.id?.takeIf { it.isNotEmpty() } ?: fragmentId.ifEmpty { "call_$idx" },
          name = existing?.name?.takeIf { it.isNotEmpty() } ?: fragmentName,
          argumentsJson = (existing?.argumentsJson ?: "") + fragmentArgs
        )
      }
    }
    return false
  }


  override suspend fun probeModels(provider: AIProvider, apiKey: String): Pair<Boolean, String> {
    return try {
      val request = Request.Builder()
        .url(provider.baseUrl.trimEnd('/') + "/models")
        .header("Authorization", "Bearer $apiKey")
        .get()
        .build()
      withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
        when {
          response.isSuccessful -> true to "Connected"
          else -> {
            val body = response.body?.string().orEmpty().take(2000)
            false to (httpError(response.code, body).message ?: "Connection failed")
            }
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

  override internal fun buildRequest(provider: AIProvider, model: AIModel, apiKey: String, request: LlmRequest, stream: Boolean): Request {
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
                  put("input", runCatching { JSONObject(normalizeArgs(tc.argumentsJson)) }.getOrDefault(JSONObject()))
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

  override internal fun handleData(data: String, state: StreamState, onEvent: (LlmStreamEvent) -> Unit): Boolean {
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
                state.toolCalls[i] = LlmToolCall(
                  id = b.optString("id"),
                  name = b.optString("name"),
                  argumentsJson = normalizeArgs(b.optJSONObject("input")?.toString() ?: "{}")
                )
              }
            }
          }
        }
        return true
      }
      "content_block_start" -> {
        val block = obj.optJSONObject("content_block")
        if (block?.optString("type") == "tool_use") {
          val idx = obj.optInt("index", state.toolCalls.size)
          state.toolCalls[idx] = LlmToolCall(
            id = block.optString("id"),
            name = cleanWireString(block.optString("name")),
            argumentsJson = ""
          )
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
            val idx = obj.optInt("index", -1).let { if (it < 0) state.toolCalls.keys.lastOrNull() ?: 0 else it }
            val existing = state.toolCalls[idx]
            if (existing != null) {
              state.toolCalls[idx] = existing.copy(argumentsJson = existing.argumentsJson + delta.optString("partial_json"))
            }
          }
        }
      }
      "message_stop" -> return true
    }
    return false
  }

  override suspend fun probeModels(provider: AIProvider, apiKey: String): Pair<Boolean, String> {
    return try {
      val request = Request.Builder()
        .url(provider.baseUrl.trimEnd('/') + "/v1/models")
        .header("x-api-key", apiKey)
        .header("anthropic-version", "2023-06-01")
        .get()
        .build()
      withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
        when {
          response.isSuccessful -> true to "Connected"
          else -> {
            val body = response.body?.string().orEmpty().take(2000)
            false to (httpError(response.code, body).message ?: "Connection failed")
            }
          }
        }
      }
    } catch (e: IOException) {
      false to "Network error: ${e.message ?: "connection failed"}"
    }
  }
}

/** Google Gemini native GenerateContent protocol. */
internal class GeminiNativeClient(http: OkHttpClient) : BaseLlmClient(http) {

  override internal fun buildRequest(provider: AIProvider, model: AIModel, apiKey: String, request: LlmRequest, stream: Boolean): Request {
    val body = JSONObject().apply {
      val systemText = request.messages.filter { it.role == LlmRole.SYSTEM }
        .joinToString("\n") { it.content }
      if (systemText.isNotBlank()) {
        put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText))))
      }
      put("contents", buildContents(request.messages.filter { it.role != LlmRole.SYSTEM }))
      if (request.tools.isNotEmpty() && model.capabilities.tools) {
        put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().apply {
          request.tools.forEach { tool ->
            put(JSONObject().apply {
              put("name", tool.name)
              put("description", tool.description)
              put("parameters", runCatching { JSONObject(tool.parametersJsonSchema) }.getOrDefault(JSONObject()))
            })
          }
        })))
      }
      put("generationConfig", buildGenerationConfig(model, request))
    }
    val action = if (stream) ":streamGenerateContent?alt=sse" else ":generateContent"
    return Request.Builder()
      .url(modelUrl(provider, model, action))
      .header("x-goog-api-key", apiKey)
      .post(jsonBody(body))
      .build()
  }

  private fun buildContents(messages: List<LlmMessage>): JSONArray {
    val contents = JSONArray()
    var index = 0
    while (index < messages.size) {
      val message = messages[index]
      if (message.role == LlmRole.TOOL) {
        val parts = JSONArray()
        while (index < messages.size && messages[index].role == LlmRole.TOOL) {
          val result = messages[index]
          parts.put(JSONObject().put("functionResponse", JSONObject().apply {
            put("name", result.toolName.orEmpty())
            result.toolCallId?.takeIf { it.isNotBlank() }?.let { put("id", it) }
            put("response", JSONObject().put("result", result.content))
          }))
          index++
        }
        contents.put(JSONObject().put("role", "user").put("parts", parts))
        continue
      }

      val parts = JSONArray()
      message.content.takeIf { it.isNotEmpty() }?.let { parts.put(JSONObject().put("text", it)) }
      message.inlineData.forEach { media ->
        parts.put(JSONObject().put("inline_data", JSONObject().apply {
          put("mime_type", media.mimeType)
          put("data", Base64.encodeToString(media.data, Base64.NO_WRAP))
        }))
      }
      if (message.role == LlmRole.ASSISTANT) {
        message.toolCalls.forEach { call ->
          parts.put(JSONObject().put("functionCall", JSONObject().apply {
            put("name", call.name)
            put("id", call.id)
            put("args", runCatching { JSONObject(normalizeArgs(call.argumentsJson)) }.getOrDefault(JSONObject()))
          }))
        }
      }
      if (parts.length() > 0) {
        contents.put(JSONObject().apply {
          put("role", if (message.role == LlmRole.ASSISTANT) "model" else "user")
          put("parts", parts)
        })
      }
      index++
    }
    return contents
  }

  private fun buildGenerationConfig(model: AIModel, request: LlmRequest): JSONObject = JSONObject().apply {
    val settings = model.generationSettings
    (request.maxOutputTokens ?: model.maxOutputTokens)?.let { put("maxOutputTokens", it) }
    (request.temperature ?: settings.temperature)?.let { put("temperature", it) }
    (request.topP ?: settings.topP)?.let { put("topP", it) }
    (request.topK ?: settings.topK)?.let { put("topK", it) }
    val stopSequences = request.stopSequences.ifEmpty { settings.stopSequences }
    if (stopSequences.isNotEmpty()) put("stopSequences", JSONArray(stopSequences))
    val schema = request.responseJsonSchema ?: settings.responseJsonSchema
    val responseMimeType = request.responseMimeType ?: settings.responseMimeType
    (responseMimeType ?: if (!schema.isNullOrBlank()) "application/json" else null)?.let { put("responseMimeType", it) }
    schema?.takeIf { it.isNotBlank() }?.let {
      put("responseJsonSchema", runCatching { JSONObject(it) }.getOrElse {
        throw LlmException("Gemini structured-output schema must be valid JSON.", LlmErrorKind.INVALID_RESPONSE)
      })
    }
    model.reasoning?.takeIf { it.enabled && !request.disableReasoning }?.let { reasoning ->
      put("thinkingConfig", JSONObject().apply {
        put("thinkingLevel", reasoning.effort.uppercase())
        put("includeThoughts", model.capabilities.interleavedReasoning)
      })
    }
  }

  override internal fun handleData(data: String, state: StreamState, onEvent: (LlmStreamEvent) -> Unit): Boolean {
    if (data == "[DONE]") return true
    val obj = runCatching { JSONObject(data) }.getOrElse {
      throw LlmException("Provider returned invalid JSON.", LlmErrorKind.INVALID_RESPONSE)
    }
    obj.optJSONObject("error")?.let { error ->
      throw LlmException(error.optString("message", "Gemini provider error"), LlmErrorKind.SERVER)
    }
    state.usage = obj.optJSONObject("usageMetadata")?.toUsage() ?: state.usage
    val candidate = obj.optJSONArray("candidates")?.optJSONObject(0) ?: return false
    val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
    if (parts != null) {
      for (index in 0 until parts.length()) {
        val part = parts.optJSONObject(index) ?: continue
        part.optString("text").takeIf { it.isNotEmpty() }?.let { text ->
          if (part.optBoolean("thought", false)) onEvent(LlmStreamEvent.ReasoningToken(text))
          else {
            state.content.append(text)
            onEvent(LlmStreamEvent.Token(text))
          }
        }
        part.optJSONObject("functionCall")?.let { functionCall ->
          val name = functionCall.optString("name")
          val arguments = normalizeArgs(functionCall.optJSONObject("args")?.toString() ?: "{}")
          val wireId = cleanWireString(functionCall.optString("id"))
          val existing = state.toolCalls.entries.firstOrNull { entry ->
            (wireId.isNotEmpty() && entry.value.id == wireId) ||
              (wireId.isEmpty() && entry.value.name == name && entry.value.argumentsJson == arguments)
          }
          val key = existing?.key ?: state.toolCalls.size
          val call = LlmToolCall(
            id = existing?.value?.id ?: wireId.ifEmpty { "gemini_call_$key" },
            name = name,
            argumentsJson = arguments
          )
          state.toolCalls[key] = call
          if (existing == null) onEvent(LlmStreamEvent.ToolCallRequested(call))
        }
      }
    }
    val wireFinishReason = candidate.optString("finishReason").takeIf { it.isNotBlank() }
    if (wireFinishReason != null) {
      state.finishReason = if (state.toolCalls.isNotEmpty()) LlmFinishReason.TOOL_CALLS else normalizeFinishReason(wireFinishReason)
      return true
    }
    return false
  }

  private fun JSONObject.toUsage(): LlmUsage = LlmUsage(
    inputTokens = optIntOrNull("promptTokenCount"),
    outputTokens = optIntOrNull("candidatesTokenCount"),
    cachedInputTokens = optIntOrNull("cachedContentTokenCount"),
    reasoningTokens = optIntOrNull("thoughtsTokenCount"),
    totalTokens = optIntOrNull("totalTokenCount")
  )

  private fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

  private fun normalizeFinishReason(value: String): LlmFinishReason = when (value.uppercase()) {
    "STOP" -> LlmFinishReason.STOP
    "MAX_TOKENS" -> LlmFinishReason.LENGTH
    "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY" -> LlmFinishReason.CONTENT_FILTER
    "MALFORMED_FUNCTION_CALL" -> LlmFinishReason.ERROR
    else -> LlmFinishReason.OTHER
  }

  override suspend fun probeModels(provider: AIProvider, apiKey: String): Pair<Boolean, String> {
    return try {
      val request = Request.Builder()
        .url(provider.baseUrl.trimEnd('/') + "/models")
        .header("x-goog-api-key", apiKey)
        .get()
        .build()
      withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
          if (response.isSuccessful) true to "Connected"
          else {
            val body = response.body?.string().orEmpty().take(2000)
            false to (httpError(response.code, body).message ?: "Connection failed")
          }
        }
      }
    } catch (e: IOException) {
      false to "Network error: ${e.message ?: "connection failed"}"
    }
  }

  private fun modelUrl(provider: AIProvider, model: AIModel, action: String): String =
    provider.baseUrl.trimEnd('/') + "/models/" + model.modelId.removePrefix("models/") + action
}

/** Shared holder for the in-flight streaming call so Stop can abort blocked socket reads. */
internal object LlmStreamRegistry {
  @Volatile
  var activeCall: okhttp3.Call? = null

  fun cancelActive() {
    activeCall?.cancel()
    activeCall = null
  }

  fun clear(call: okhttp3.Call) {
    if (activeCall === call) activeCall = null
  }
}
