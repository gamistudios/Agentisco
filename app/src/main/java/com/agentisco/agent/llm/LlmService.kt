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
  httpClient: OkHttpClient? = null,
  private val chainStore: GeminiChainStore = GeminiChainStoreImpl()
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
    LLMProtocol.GOOGLE_GEMINI -> GeminiInteractionsClient(http, chainStore).streamChat(provider, model, apiKey, request, onEvent)
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
      LLMProtocol.GOOGLE_GEMINI -> GeminiInteractionsClient(http, chainStore)
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

  /** Overridden by protocols that need per-stream state beyond the shared fields. */
  protected open fun newState(): StreamState = StreamState()

  internal open class StreamState {
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

  /** A counter the provider never reported must stay absent, not become 0. */
  internal fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

  internal fun normalizeArgs(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text == "null") return "{}"
    // Never double-parse fragments; normalize only complete argument strings so
    // the wire format is always a valid JSON object string.
    return runCatching { JSONObject(text).toString() }.getOrDefault(text)
  }

  open suspend fun streamChat(
    provider: AIProvider,
    model: AIModel,
    apiKey: String,
    request: LlmRequest,
    onEvent: (LlmStreamEvent) -> Unit
  ): Unit = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) throw LlmException("No API key configured for provider \"${provider.name}\".", LlmErrorKind.AUTH)
    if (provider.baseUrl.isBlank()) throw LlmException("No base URL configured for provider \"${provider.name}\".", LlmErrorKind.UNSUPPORTED)

    val stream = model.capabilities.streaming
    val state = newState()
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

/**
 * Anthropic Messages protocol.
 *
 * Extended thinking is deliberately left off: once tools are in play the API
 * requires every `thinking` block and its `signature` to be echoed back on the
 * next assistant turn, and the persisted chat history stores neither.
 */
internal class AnthropicMessagesClient(http: OkHttpClient) : BaseLlmClient(http) {

  /** The settings hint omits the version segment, but users routinely paste a base ending in `/v1`. */
  private fun endpoint(provider: AIProvider, path: String): String {
    val base = provider.baseUrl.trimEnd('/')
    val root = if (base.endsWith("/v1")) base.removeSuffix("/v1") else base
    return "$root/v1/$path"
  }

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
          // The API rejects any message whose content is empty, so a blank row
          // from persisted history is dropped instead of poisoning the request.
          m.content.isNotBlank() -> msgs.put(JSONObject().apply {
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
      .url(endpoint(provider, "messages"))
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
      "error" -> {
        val err = obj.optJSONObject("error")
        throw LlmException(
          err?.optString("message")?.takeIf { it.isNotBlank() } ?: "Anthropic provider error",
          when (err?.optString("type")) {
            "rate_limit_error" -> LlmErrorKind.RATE_LIMIT
            // Permanent request faults must not be retried by the agent loop.
            "invalid_request_error", "request_too_large" -> LlmErrorKind.INVALID_RESPONSE
            "authentication_error" -> LlmErrorKind.AUTH
            // api_error and overloaded_error are transient.
            else -> LlmErrorKind.SERVER
          }
        )
      }
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
        finishFor(cleanWireString(obj.optString("stop_reason")))?.let { state.finishReason = it }
        absorbUsage(state, obj.optJSONObject("usage"))
        return true
      }
      // Carries the prompt counters; output_tokens here is only the first chunk.
      "message_start" -> obj.optJSONObject("message")?.let { absorbUsage(state, it.optJSONObject("usage")) }
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
          // signature_delta and citations_delta carry nothing the agent acts on.
        }
      }
      // The terminal stop_reason and the cumulative output count arrive here,
      // not in message_start — dropping them loses usage for streamed replies.
      "message_delta" -> {
        obj.optJSONObject("delta")?.let { d ->
          finishFor(cleanWireString(d.optString("stop_reason")))?.let { state.finishReason = it }
        }
        absorbUsage(state, obj.optJSONObject("usage"))
      }
      "message_stop" -> return true
      // ping, content_block_stop: nothing to act on.
    }
    return false
  }

  /** Anthropic reports no total, and `input_tokens` excludes cache reads — so nothing is synthesized. */
  private fun absorbUsage(state: StreamState, usage: JSONObject?) {
    usage ?: return
    val prior = state.usage
    val input = usage.optIntOrNull("input_tokens") ?: prior?.inputTokens
    val output = usage.optIntOrNull("output_tokens") ?: prior?.outputTokens
    val cached = usage.optIntOrNull("cache_read_input_tokens") ?: prior?.cachedInputTokens
    if (input == null && output == null && cached == null) return
    state.usage = LlmUsage(inputTokens = input, outputTokens = output, cachedInputTokens = cached)
  }

  private fun finishFor(stopReason: String): LlmFinishReason? = when (stopReason) {
    "" -> null
    "tool_use" -> LlmFinishReason.TOOL_CALLS
    "max_tokens" -> LlmFinishReason.LENGTH
    "end_turn", "stop_sequence" -> LlmFinishReason.STOP
    "refusal", "model_failure" -> LlmFinishReason.ERROR
    // pause_turn and any future value: the agent has no special handling for them.
    else -> LlmFinishReason.OTHER
  }

  override suspend fun probeModels(provider: AIProvider, apiKey: String): Pair<Boolean, String> {
    return try {
      val request = Request.Builder()
        .url(endpoint(provider, "models"))
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

/**
 * Google Gemini Interactions protocol (`POST /v1beta/interactions`).
 *
 * Unlike generateContent this API is stateful: the provider holds the
 * transcript, and a continuation turn sends only its own delta plus the
 * `previous_interaction_id` of the turn before it. Function calling works only
 * through that chain — prior calls cannot be replayed from local history — so
 * the id is persisted per [LlmRequest.conversationKey] and an app restart
 * resumes the same server-side conversation.
 *
 * Responses are step-based: `thought` (reasoning), `model_output` (the answer)
 * and `function_call` (a tool the model asks us to run — execution always stays
 * on-device). Unknown event types are skipped rather than treated as fatal.
 */
internal class GeminiInteractionsClient(
  http: OkHttpClient,
  private val chainStore: GeminiChainStore
) : BaseLlmClient(http) {

  private companion object {
    const val DEFAULT_MAX_OUTPUT_TOKENS = 65536
    /**
     * Thinking tokens are billed against max_output_tokens, so a small cap makes
     * the interaction end as "incomplete" with no answer at all.
     */
    const val MIN_MAX_OUTPUT_TOKENS = 8192
    const val DEFAULT_THINKING_LEVEL = "medium"
  }

  private var chainKey: String? = null
  private var chainDisabled = false
  private var sentChainId = false
  private var priorEnvironmentId: String? = null

  internal class PendingCall(val id: String, val name: String, val args: StringBuilder)

  internal class InteractionsState : StreamState() {
    var interactionId: String? = null
    var environmentId: String? = null
    var status: String? = null
    val pendingCalls = HashMap<Int, PendingCall>()
  }

  override fun newState(): StreamState = InteractionsState()

  /**
   * A rejected chain is recoverable, so it is retried once here instead of
   * surfacing: the provider purges idle interactions, and the local transcript
   * can always reopen the conversation.
   */
  override suspend fun streamChat(
    provider: AIProvider,
    model: AIModel,
    apiKey: String,
    request: LlmRequest,
    onEvent: (LlmStreamEvent) -> Unit
  ) {
    var reopen = false
    try {
      super.streamChat(provider, model, apiKey, request) { event ->
        if (event is LlmStreamEvent.Failed && isRejectedChain(event.error)) reopen = true else onEvent(event)
      }
    } catch (e: LlmException) {
      if (!reopen) throw e
    }
    if (!reopen) return
    request.conversationKey?.let { chainStore.clear(it) }
    chainDisabled = true
    super.streamChat(provider, model, apiKey, request, onEvent)
  }

  private fun isRejectedChain(error: LlmException): Boolean =
    sentChainId && (error.httpCode == 400 || error.httpCode == 404)

  override internal fun buildRequest(provider: AIProvider, model: AIModel, apiKey: String, request: LlmRequest, stream: Boolean): Request {
    chainKey = request.conversationKey?.takeIf { it.isNotBlank() }
    val stored = chainKey?.takeIf { !chainDisabled }?.let { chainStore.load(it) }
      ?.takeIf { it.interactionId.isNotBlank() }
    priorEnvironmentId = stored?.environmentId

    // A continuation carries only the turn the provider has not seen yet: the
    // trailing tool results, or the trailing user prompt. Anything older already
    // lives in the server-side chain and must not be replayed.
    val continuation = stored?.let {
      JSONArray().also { blocks -> trailingTurn(request.messages).forEach { m -> appendTurnBlock(blocks, m) } }
    }?.takeIf { it.length() > 0 }
    sentChainId = continuation != null

    val input = continuation ?: freshChainInput(request.messages)
    if (input.length() == 0) {
      throw LlmException("Nothing to send to the model — the conversation has no content.", LlmErrorKind.INVALID_RESPONSE)
    }

    val body = JSONObject().apply {
      put("model", model.modelId)
      if (stream) put("stream", true)
      if (stored != null && continuation != null) {
        put("previous_interaction_id", stored.interactionId)
        stored.environmentId?.let { put("environment", it) }
      }
      val systemText = request.messages.filter { it.role == LlmRole.SYSTEM }
        .joinToString("\n") { it.content }.trim()
      if (systemText.isNotEmpty()) put("system_instruction", systemText)
      put("input", input)
      if (request.tools.isNotEmpty() && model.capabilities.tools) {
        put("tools", JSONArray().apply {
          request.tools.forEach { tool ->
            put(JSONObject().apply {
              put("type", "function")
              put("name", tool.name)
              put("description", tool.description)
              put("parameters", runCatching { JSONObject(tool.parametersJsonSchema) }.getOrDefault(JSONObject()))
            })
          }
        })
      }
      put("generation_config", buildGenerationConfig(model, request))
    }
    return Request.Builder()
      .url(provider.baseUrl.trimEnd('/') + "/interactions")
      .header("x-goog-api-key", apiKey)
      .post(jsonBody(body))
      .build()
  }

  /**
   * The messages that make up the turn about to be sent: a batch of tool
   * results, or a single user prompt. The agent loop only ever requests a
   * response after appending one of those two shapes.
   */
  private fun trailingTurn(messages: List<LlmMessage>): List<LlmMessage> = when (messages.lastOrNull()?.role) {
    LlmRole.TOOL -> {
      val start = messages.indexOfLast { it.role != LlmRole.TOOL } + 1
      messages.subList(start, messages.size)
    }
    LlmRole.USER -> listOf(messages.last())
    else -> emptyList()
  }

  /**
   * Opening a chain has no server-side transcript to lean on, so the earlier
   * conversation is rendered as labeled text: Interactions input blocks carry no
   * role, and prior function calls cannot be replayed into a new chain.
   */
  private fun freshChainInput(messages: List<LlmMessage>): JSONArray {
    val input = JSONArray()
    val transcript = messages.filter { it.role != LlmRole.SYSTEM }
    val endsWithUser = transcript.lastOrNull()?.role == LlmRole.USER
    val rendered = renderTranscript(if (endsWithUser) transcript.dropLast(1) else transcript)
    if (rendered.isNotBlank()) input.put(textBlock(rendered))
    if (endsWithUser) {
      val last = transcript.last()
      if (last.content.isNotEmpty()) input.put(textBlock(last.content))
      last.inlineData.forEach { input.put(imageBlock(it)) }
    } else if (transcript.isNotEmpty()) {
      // A resumed turn ends in tool results; they were rendered above, so state
      // plainly what the model should do with them.
      input.put(textBlock("Continue from the tool results above."))
    }
    return input
  }

  private fun appendTurnBlock(input: JSONArray, message: LlmMessage) {
    when (message.role) {
      // The provider produced the assistant turns itself and already holds them.
      LlmRole.SYSTEM, LlmRole.ASSISTANT -> Unit
      LlmRole.TOOL -> input.put(functionResultBlock(message))
      LlmRole.USER -> {
        if (message.content.isNotEmpty()) input.put(textBlock(message.content))
        message.inlineData.forEach { input.put(imageBlock(it)) }
      }
    }
  }

  private fun renderTranscript(messages: List<LlmMessage>): String {
    val relevant = messages.filter { it.role != LlmRole.SYSTEM }
    if (relevant.isEmpty()) return ""
    val sb = StringBuilder("[Earlier conversation]\n")
    for (m in relevant) {
      when (m.role) {
        LlmRole.USER -> if (m.content.isNotBlank()) sb.append("User: ").append(m.content).append('\n')
        LlmRole.ASSISTANT -> {
          if (m.content.isNotBlank()) sb.append("Assistant: ").append(m.content).append('\n')
          m.toolCalls.forEach { sb.append("Assistant called ").append(it.name).append('(').append(it.argumentsJson).append(")\n") }
        }
        LlmRole.TOOL -> sb.append("Tool ").append(m.toolName ?: "result").append(" returned: ").append(m.content).append('\n')
        LlmRole.SYSTEM -> Unit
      }
    }
    return sb.append("[End of earlier conversation]").toString()
  }

  private fun textBlock(text: String) = JSONObject().put("type", "text").put("text", text)

  private fun imageBlock(media: LlmInlineData) = JSONObject().apply {
    put("type", "image")
    put("mime_type", media.mimeType)
    put("data", Base64.encodeToString(media.data, Base64.NO_WRAP))
  }

  private fun functionResultBlock(message: LlmMessage) = JSONObject().apply {
    put("type", "function_result")
    put("name", message.toolName.orEmpty())
    message.toolCallId?.takeIf { it.isNotBlank() }?.let { put("call_id", it) }
    put("result", JSONObject().put("content", JSONArray().put(textBlock(message.content))))
  }

  /**
   * Users may leave every generation field blank: agentic work needs a large
   * output budget (thinking is billed against it) and a real reasoning level.
   */
  private fun buildGenerationConfig(model: AIModel, request: LlmRequest) = JSONObject().apply {
    val maxTokens = request.maxOutputTokens ?: model.maxOutputTokens ?: DEFAULT_MAX_OUTPUT_TOKENS
    put("max_output_tokens", maxTokens.coerceAtLeast(MIN_MAX_OUTPUT_TOKENS))
    put("thinking_level", thinkingLevel(model, request))
  }

  private fun thinkingLevel(model: AIModel, request: LlmRequest): String {
    val reasoning = model.reasoning?.takeIf { it.enabled && !request.disableReasoning }
    if (reasoning == null) return if (request.disableReasoning) "low" else DEFAULT_THINKING_LEVEL
    return when (reasoning.effort.lowercase()) {
      "low", "minimal", "none" -> "low"
      "high", "max", "maximum" -> "high"
      else -> "medium"
    }
  }

  override internal fun handleData(data: String, state: StreamState, onEvent: (LlmStreamEvent) -> Unit): Boolean {
    if (data == "[DONE]") return true
    val s = state as InteractionsState
    val obj = runCatching { JSONObject(data) }.getOrElse {
      throw LlmException("Provider returned invalid JSON.", LlmErrorKind.INVALID_RESPONSE)
    }
    // Non-streaming responses are the interaction object itself, with no event_type.
    val eventType = obj.optString("event_type")
    if (eventType.isEmpty()) {
      absorbInteraction(obj, s, onEvent)
      return true
    }
    when (eventType) {
      "interaction.created" -> obj.optJSONObject("interaction")?.let { captureChain(it, s) }
      "step.start" -> stepStart(obj, s, onEvent)
      "step.delta" -> stepDelta(obj, s, onEvent)
      "step.stop" -> stepStop(obj, s)
      "interaction.completed" -> obj.optJSONObject("interaction")?.let { absorbInteraction(it, s, onEvent) }
      "error" -> throw providerError(obj.optJSONObject("error"))
      // interaction.status_update, done, and any future event type carry nothing
      // the agent acts on.
    }
    return false
  }

  private fun captureChain(obj: JSONObject, s: InteractionsState) {
    cleanWireString(obj.optString("id")).takeIf { it.isNotEmpty() }?.let { s.interactionId = it }
    cleanWireString(obj.optString("environment_id")).takeIf { it.isNotEmpty() }?.let { s.environmentId = it }
  }

  private fun absorbInteraction(obj: JSONObject, s: InteractionsState, onEvent: (LlmStreamEvent) -> Unit) {
    captureChain(obj, s)
    obj.optJSONObject("usage")?.let { s.usage = it.toUsage() }
    val steps = obj.optJSONArray("steps")
    if (steps != null) {
      for (index in 0 until steps.length()) steps.optJSONObject(index)?.let { absorbStep(it, index, s, onEvent) }
    }
    val status = obj.optString("status").takeIf { it.isNotBlank() }
    if (status != null) {
      s.status = status
      s.finishReason = when (status) {
        "requires_action" -> LlmFinishReason.TOOL_CALLS
        "completed" -> if (s.toolCalls.isNotEmpty()) LlmFinishReason.TOOL_CALLS else LlmFinishReason.STOP
        "incomplete" -> LlmFinishReason.LENGTH
        "failed" -> LlmFinishReason.ERROR
        else -> s.finishReason
      }
    }
    commitChain(s)
  }

  /** Only a finished interaction advances the chain, so a mid-stream failure cannot poison it. */
  private fun commitChain(s: InteractionsState) {
    val key = chainKey ?: return
    val id = s.interactionId ?: return
    when (s.status) {
      "completed", "requires_action", "incomplete" ->
        chainStore.save(
          key,
          GeminiChainState(id, s.environmentId ?: priorEnvironmentId, System.currentTimeMillis())
        )
    }
  }

  private fun absorbStep(step: JSONObject, index: Int, s: InteractionsState, onEvent: (LlmStreamEvent) -> Unit) {
    when (step.optString("type")) {
      "model_output" -> {
        val content = step.optJSONArray("content") ?: return
        for (i in 0 until content.length()) {
          val text = content.optJSONObject(i)?.optString("text").orEmpty()
          if (text.isNotEmpty()) {
            s.content.append(text)
            onEvent(LlmStreamEvent.Token(text))
          }
        }
      }
      "function_call" -> {
        val call = toolCall(step, index)
        s.toolCalls[index] = call
        onEvent(LlmStreamEvent.ToolCallRequested(call))
      }
      // thought (signature only), our own function_result, and provider-executed
      // steps such as google_search_result add no text the agent should re-emit.
    }
  }

  private fun stepStart(obj: JSONObject, s: InteractionsState, onEvent: (LlmStreamEvent) -> Unit) {
    val index = obj.optInt("index")
    val step = obj.optJSONObject("step") ?: return
    if (step.optString("type") != "function_call") return
    // Arguments arrive as later deltas; announce the call now so the UI shows it.
    val call = toolCall(step, index)
    s.pendingCalls[index] = PendingCall(call.id, call.name, StringBuilder())
    onEvent(LlmStreamEvent.ToolCallRequested(call))
  }

  private fun stepDelta(obj: JSONObject, s: InteractionsState, onEvent: (LlmStreamEvent) -> Unit) {
    val index = obj.optInt("index")
    val delta = obj.optJSONObject("delta") ?: return
    when (delta.optString("type")) {
      "text" -> {
        val text = delta.optString("text")
        if (text.isNotEmpty()) {
          s.content.append(text)
          onEvent(LlmStreamEvent.Token(text))
        }
      }
      "thought_summary" -> {
        val text = delta.optJSONObject("content")?.optString("text").orEmpty()
        if (text.isNotEmpty()) onEvent(LlmStreamEvent.ReasoningToken(text))
      }
      "arguments_delta" -> s.pendingCalls[index]?.args?.append(delta.optString("arguments"))
      // thought_signature, image and audio have no text the agent can use.
    }
  }

  private fun stepStop(obj: JSONObject, s: InteractionsState) {
    val index = obj.optInt("index")
    s.pendingCalls.remove(index)?.let { pending ->
      s.toolCalls[index] = LlmToolCall(pending.id, pending.name, normalizeArgs(pending.args.toString()))
    }
    obj.optJSONObject("usage")?.let { s.usage = it.toUsage() }
  }

  private fun toolCall(step: JSONObject, index: Int) = LlmToolCall(
    id = cleanWireString(step.optString("id")).ifEmpty { "gemini_call_$index" },
    name = step.optString("name"),
    argumentsJson = normalizeArgs(step.opt("arguments")?.toString())
  )

  private fun providerError(error: JSONObject?): LlmException {
    val message = error?.optString("message")?.takeIf { it.isNotBlank() } ?: "Gemini provider error"
    when {
      error?.optString("code").orEmpty().contains("rate_limit") -> return LlmException(message, LlmErrorKind.RATE_LIMIT)
      error?.optString("code").orEmpty().let { it.contains("deadline") || it.contains("timeout") } ->
        return LlmException(message, LlmErrorKind.TIMEOUT)
      error?.optString("code") == "invalid_request" -> return LlmException(message, LlmErrorKind.INVALID_RESPONSE)
    }
    return LlmException(message, LlmErrorKind.SERVER)
  }

  private fun JSONObject.toUsage(): LlmUsage = LlmUsage(
    inputTokens = optIntOrNull("total_input_tokens"),
    outputTokens = optIntOrNull("total_output_tokens"),
    cachedInputTokens = optIntOrNull("total_cached_tokens"),
    reasoningTokens = optIntOrNull("total_thought_tokens"),
    totalTokens = optIntOrNull("total_tokens")
  )

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
