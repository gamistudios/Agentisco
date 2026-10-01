package com.agentisco.local.runtime

import com.agentisco.local.model.LocalRuntimeSettings
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * The production engine: [LocalModelEngine] over the bundled llama.cpp build.
 *
 * It adds nothing to the native loop beyond translation — handles in, finish codes out,
 * UTF-8 decoded at the boundary — and turns the engine's terse failures into
 * [LocalEngineException] messages a user can act on, because "Failed to create context"
 * is not a thing to show someone who tapped Install on a model.
 *
 * Chat templates are deliberately *not* translated here. A GGUF file carries its own
 * Jinja template and llama.cpp carries the renderer and the matching answer parser, so
 * the request goes to the engine as OpenAI-shaped JSON and comes back as a rendered
 * prompt plus a grammar. Any dialect a model author invented stays somebody else's
 * problem.
 */
class LlamaEngine : LocalModelEngine {

  private val native: LlamaNative? get() = LlamaNative.get()

  @Volatile
  private var backendReady = false

  override val isAvailable: Boolean get() = LlamaNative.isAvailable

  override fun systemThreads(): Int =
    native?.nativeSystemThreads()?.takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors()

  override fun load(path: String, runtime: LocalRuntimeSettings): LoadedLocalModel {
    val api = native ?: throw LocalEngineException(
      LlamaNative.unavailableReasonText ?: "No on-device inference engine in this build"
    )

    if (!backendReady) {
      api.nativeBackendInit()
      backendReady = true
    }

    val handle = try {
      api.nativeLoadModel(
        path,
        runtime.contextSize,
        runtime.threadCount.coerceAtLeast(1),
        runtime.batchSize.coerceAtLeast(1)
      )
    } catch (e: OutOfMemoryError) {
      throw LocalEngineException(
        "Not enough memory to load this model. Close other apps, or lower the model's context size.",
        e
      )
    }
    if (handle == 0L) throw LocalEngineException(describeFailure(api, "Could not load the model"))

    return try {
      LlamaSession(api, handle, readInfo(api, handle))
    } catch (e: Throwable) {
      api.nativeUnload(handle)
      if (e is LocalEngineException) throw e
      throw LocalEngineException(describeFailure(api, "Could not read the model's metadata"), e)
    }
  }

  override fun shutdown() {
    val api = native ?: return
    if (backendReady) {
      api.nativeBackendFree()
      backendReady = false
    }
  }

  private fun readInfo(api: LlamaNative, handle: Long): LoadedModelInfo {
    val raw = api.nativeModelInfo(handle).toStringUtf8()
    val json = runCatching { JSONObject(raw) }.getOrNull()
      ?: throw LocalEngineException(describeFailure(api, "The model reported unreadable metadata"))
    return LoadedModelInfo(
      publishedName = json.optString("name"),
      architecture = json.optString("architecture"),
      chatTemplate = json.optString("chatTemplate"),
      eosToken = json.optString("eosToken"),
      vocabSize = json.optInt("vocabSize", 0),
      contextSize = json.optInt("contextSize", 0),
      trainedContextSize = json.optInt("trainedContextSize", 0),
      supportsGrammar = json.optBoolean("supportsGrammar", false)
    )
  }

  private fun ByteArray.toStringUtf8(): String = String(this, StandardCharsets.UTF_8)

  /**
   * One open model.
   *
   * The native handle is freed on [close], while a decode may still be running on
   * another thread and [abort] may arrive at any moment — including from a UI stop
   * button on a model the user has already switched away from. So the handle's lifetime
   * is reference-counted on [state]: a close that lands mid-decode is remembered and
   * carried out when the decode lets go, which is the only order that cannot free a
   * handle something is still using.
   */
  private class LlamaSession(
    private val api: LlamaNative,
    private val handle: Long,
    override val info: LoadedModelInfo
  ) : LoadedLocalModel {

    private val state = Any()
    private var decodes = 0
    private var closeRequested = false
    private var released = false

    override fun templateCapabilities(): LocalTemplateCapabilities {
      val json = runCatching { JSONObject(api.nativeChatTemplatesInfo(handle).toStringUtf8()) }.getOrNull()
        ?: return LocalTemplateCapabilities.unavailable.copy(reason = "The engine reported unreadable template info")
      if (!json.optBoolean("available", false)) {
        return LocalTemplateCapabilities.unavailable.copy(
          reason = json.optString("reason").ifBlank { "This model has no usable chat template" }
        )
      }
      val caps = mutableMapOf<String, Boolean>()
      json.optJSONObject("caps")?.let { node ->
        node.keys().forEach { key -> caps[key] = node.optBoolean(key) }
      }
      return LocalTemplateCapabilities(
        available = true,
        usesOwnTemplate = json.optBoolean("explicit", false),
        supportsTools = caps["supports_tools"] == true && caps["supports_tool_calls"] == true,
        supportsParallelToolCalls = caps["supports_parallel_tool_calls"] == true,
        supportsThinking = caps["supports_preserve_reasoning"] == true || caps["supports_reasoning_effort"] == true,
        supportsSystemMessage = caps["supports_system_role"] == true,
        supportsTypedContent = caps["supports_typed_content"] == true,
        caps = caps
      )
    }

    override fun openTurn(inputs: LocalChatInputs): LocalChatTurn {
      // The template is compiled once at load; a model whose template failed to compile
      // has no turn to open, and the reason recorded then is still the useful one.
      val capabilities = templateCapabilities()
      if (!capabilities.available) {
        throw LocalEngineException(capabilities.reason.ifBlank { "This model has no usable chat template" })
      }
      val turn = api.nativeChatOpenTurn(handle, inputs.toWireJson().toString().toByteArray(StandardCharsets.UTF_8))
      if (turn == 0L) throw LocalEngineException(describeFailure(api, "Could not render this request"))
      return try {
        LlamaChatTurn(api, turn)
      } catch (e: Throwable) {
        api.nativeChatCloseTurn(turn)
        if (e is LocalEngineException) throw e
        throw LocalEngineException(describeFailure(api, "Could not read the rendered prompt"), e)
      }
    }

    override fun generate(request: LocalGenerationRequest, onPiece: (String) -> Boolean): LocalFinishReason {
      beginUse()
      try {
        // The abort flag outlives a run, so each request starts with it cleared or a
        // cancelled turn would stop the next one too.
        api.nativeResetAbort(handle)
        val sink = object : LlamaNative.TokenSink {
          override fun onToken(piece: ByteArray): Boolean = onPiece(piece.toStringUtf8())
        }
        val grammar = request.grammar?.toByteArray(StandardCharsets.UTF_8)
        val code = api.nativeComplete(
          handle,
          request.prompt.toByteArray(StandardCharsets.UTF_8),
          request.settings.temperature.toFloat(),
          request.settings.topK,
          request.settings.topP.toFloat(),
          request.settings.repeatPenalty.toFloat(),
          request.settings.maxOutputTokens,
          request.seed ?: 0L,
          grammar,
          sink
        )
        return LocalFinishReason.fromNativeCode(code)
          ?: throw LocalEngineException("Inference failed (engine code $code): " + lastError())
      } finally {
        endUse()
      }
    }

    override fun abort() {
      // Only a decode that is actually running can be aborted, and the handle is only
      // guaranteed alive while one is: this is why the count is checked under the lock.
      synchronized(state) {
        if (!released && decodes > 0) api.nativeAbort(handle)
      }
    }

    override fun close() {
      synchronized(state) {
        if (released) return
        if (decodes > 0) closeRequested = true else releaseLocked()
      }
    }

    private fun beginUse() {
      synchronized(state) {
        if (released || closeRequested) throw LocalEngineException("The model has been unloaded")
        decodes++
      }
    }

    private fun endUse() {
      synchronized(state) {
        decodes--
        if (closeRequested && decodes == 0) releaseLocked()
      }
    }

    private fun releaseLocked() {
      released = true
      api.nativeUnload(handle)
    }

    private fun lastError(): String = api.nativeLastError().toStringUtf8().trim().ifEmpty { "unknown error" }

    private fun ByteArray.toStringUtf8(): String = String(this, StandardCharsets.UTF_8)
  }

  /**
   * One rendered request. Reads like a plain data object because that is what the engine
   * hands back; the parser state it carries is native and lives until [close].
   */
  private class LlamaChatTurn(private val api: LlamaNative, private val handle: Long) : LocalChatTurn {

    private val info: JSONObject = runCatching { JSONObject(api.nativeChatTurnInfo(handle).toStringUtf8()) }
      .getOrElse { throw LocalEngineException("The engine returned an unreadable prompt") }

    private var closed = false

    override val prompt: String = info.optString("prompt")

    // An empty grammar means the template asked for no constraints; the engine takes
    // null for that, not a zero-length file.
    override val grammar: String? = info.optString("grammar").takeIf { it.isNotEmpty() }

    override val stopSequences: List<String> = info.optJSONArray("additionalStops").stringList()

    override val format: String = info.optString("format")

    override val expectsToolCalls: Boolean = info.optBoolean("hasTools", false)

    override val supportsThinking: Boolean = info.optBoolean("supportsThinking", false)

    override fun parse(text: String, partial: Boolean): LocalParsedMessage {
      if (closed) return LocalParsedMessage.empty
      val json = runCatching {
        JSONObject(api.nativeChatParse(handle, text.toByteArray(StandardCharsets.UTF_8), partial).toStringUtf8())
      }.getOrNull() ?: return LocalParsedMessage.empty
      if (json.optBoolean("error", false)) {
        // The answer does not fit the model's own format. The text is still the answer
        // the user asked for, so it is returned as content with the failure marked for
        // whoever logs these — never as a silent empty turn.
        return LocalParsedMessage(text, "", emptyList(), emptyList(), rejected = true)
      }
      val message = json.optJSONObject("message") ?: return LocalParsedMessage.empty
      return LocalParsedMessage(
        content = message.optString("content"),
        reasoning = message.optString("reasoning"),
        toolCalls = message.optJSONArray("toolCalls").toolCallList(),
        deltas = json.optJSONArray("deltas").deltaList(),
        rejected = false
      )
    }

    override fun close() {
      if (closed) return
      closed = true
      api.nativeChatCloseTurn(handle)
    }

    private fun ByteArray.toStringUtf8(): String = String(this, StandardCharsets.UTF_8)
  }
}

/** The request as the engine's template layer wants it: the OpenAI wire format. */
internal fun LocalChatInputs.toWireJson(): JSONObject = JSONObject().apply {
  put("messages", JSONArray().apply {
    for (message in this@toWireJson.messages) {
      val node = JSONObject()
      node.put("role", message.role)
      // Always present: a template distinguishes an empty turn from a message that said
      // nothing about content, and an absent key is the one case it cannot render.
      node.put("content", message.content)
      if (message.toolCalls.isNotEmpty()) {
        node.put("tool_calls", JSONArray().apply {
          message.toolCalls.forEach { call ->
            put(JSONObject().apply {
              put("id", call.id)
              put("type", "function")
              put("function", JSONObject().apply {
                put("name", call.name)
                put("arguments", call.argumentsJson)
              })
            })
          }
        })
      }
      message.toolCallId?.let { node.put("tool_call_id", it) }
      message.reasoningContent?.takeIf { it.isNotEmpty() }?.let { node.put("reasoning_content", it) }
      put(node)
    }
  })
  if (tools.isNotEmpty()) {
    put("tools", JSONArray().apply {
      tools.forEach { tool ->
        put(JSONObject().apply {
          put("type", "function")
          put("function", JSONObject().apply {
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", JSONObject(tool.parametersJsonSchema))
          })
        })
      }
    })
  }
  put("tool_choice", toolChoice)
  put("add_generation_prompt", addGenerationPrompt)
  put("parallel_tool_calls", parallelToolCalls)
  put("enable_thinking", enableThinking)
}

private fun JSONArray?.stringList(): List<String> =
  if (this == null) emptyList() else (0 until length()).map { optString(it) }

private fun JSONArray?.toolCallList(): List<LocalToolCall> =
  if (this == null) emptyList() else (0 until length()).map { i ->
    val node = optJSONObject(i)
      ?: return@map LocalToolCall("call_$i", "", "{}")
    LocalToolCall(
      id = node.optString("id").ifEmpty { "call_$i" },
      name = node.optString("name"),
      argumentsJson = node.optString("arguments").ifEmpty { "{}" }
    )
  }

private fun JSONArray?.deltaList(): List<LocalParseDelta> =
  if (this == null) emptyList() else (0 until length()).map { i ->
    val node = optJSONObject(i) ?: return@map LocalParseDelta("", "", -1, null)
    val call = node.optJSONObject("toolCall")
    LocalParseDelta(
      content = node.optString("content"),
      reasoning = node.optString("reasoning"),
      toolCallIndex = node.optInt("toolCallIndex", -1),
      toolCall = call?.let { LocalToolCall(it.optString("id"), it.optString("name"), it.optString("arguments")) }
    )
  }

private fun describeFailure(api: LlamaNative, action: String): String {
  val detail = String(api.nativeLastError(), StandardCharsets.UTF_8).trim()
  return if (detail.isEmpty()) action else "$action: $detail"
}
