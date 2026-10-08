package com.awaki.local.jni

import com.awaki.local.model.LocalRuntimeSettings
import com.awaki.local.runtime.LoadedLocalModel
import com.awaki.local.runtime.LoadedModelInfo
import com.awaki.local.runtime.LocalAnswerDelta
import com.awaki.local.runtime.LocalChatInputs
import com.awaki.local.runtime.LocalChatRequest
import com.awaki.local.runtime.LocalEngineDiagnostics
import com.awaki.local.runtime.LocalEngineException
import com.awaki.local.runtime.LocalFinishReason
import com.awaki.local.runtime.LocalModelEngine
import com.awaki.local.runtime.LocalPromptTooLongException
import com.awaki.local.runtime.LocalTemplateCapabilities
import com.awaki.local.runtime.LocalToolCall
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * The production engine: [LocalModelEngine] over the llama.cpp build compiled into the APK.
 *
 * It adds nothing to the native loop beyond translation — handles in, finish codes out, UTF-8
 * decoded at the boundary — and turns the engine's terse failures into [LocalEngineException]
 * messages a user can act on, because "Failed to create context" is not a thing to show someone
 * who tapped Load on a model.
 *
 * Chat templates are deliberately *not* translated here. A GGUF file carries its own Jinja
 * template and llama.cpp carries the renderer plus the matching answer parser, so the request
 * goes to the engine as OpenAI-shaped JSON and comes back as a rendered prompt, a grammar and
 * the sequences that end the turn. Any dialect a model author invented stays somebody else's
 * problem, which is the reason this engine exists: the library beside the weights is the one
 * that knows how those weights are spoken to.
 */
class NativeLlamaEngine(
  /** The directory Android extracted this app's native libraries into. */
  private val nativeLibDir: () -> String,
  /** The file the Crash Log screen reads, which is where a fault in the engine's own code records itself. */
  private val crashLogPath: () -> String
) : LocalModelEngine {

  private val native: NativeLlama? get() = NativeLlama.get()

  @Volatile
  private var backendReady = false

  override val isAvailable: Boolean get() = NativeLlama.isAvailable

  override val unavailableReason: String get() = if (isAvailable) "" else NO_ENGINE

  /**
   * The performance cores, not every core.
   *
   * A decode step ends in a barrier all its workers wait at, so a thread parked on a little
   * core makes the big ones wait for it on every token. The native side reads each core's
   * maximum clock, which is the only portable description of big.LITTLE on a phone.
   */
  override fun systemThreads(): Int =
    native?.nativeSystemThreads()?.takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors()

  /**
   * The device and build facts, read with no model loaded.
   *
   * Handle 0 asks the engine what a model *would* run on, which is the honest answer here: the
   * numbers that explain a slow turn are properties of the packaged code and of the silicon, and
   * the screen that shows them is open while no model is in memory too.
   */
  override fun diagnostics(): LocalEngineDiagnostics {
    val api = native ?: return LocalEngineDiagnostics.noEngine.copy(reason = unavailableReason)
    val bytes = api.nativeEngineDiagnostics(0L)
      ?: return LocalEngineDiagnostics.noEngine.copy(
        reason = describeFailure(api, "The engine could not report its own build")
      )
    val json = runCatching { JSONObject(bytes.toStringUtf8()) }.getOrNull()
      ?: return LocalEngineDiagnostics.noEngine.copy(reason = "The engine reported unreadable diagnostics")
    val backends = json.optJSONArray("backends").jsonObjectList().map { node ->
      val description = node.optString("description")
      val name = node.optString("name")
      if (description.isBlank()) name else "$name: $description"
    }
    return LocalEngineDiagnostics(
      enginePresent = true,
      compiledOptimized = json.optBoolean("optimized", false),
      coresSeen = json.optInt("coresSeen", 0),
      decodeThreads = json.optInt("decodeThreads", 0),
      batchThreads = json.optInt("batchThreads", 0),
      pooledWorkers = json.optBoolean("pooledWorkers", false),
      availableMb = json.optLong("availableMb", -1L).takeIf { it >= 0 },
      backends = backends,
      systemInfo = json.optString("systemInfo")
    )
  }

  override fun load(path: String, runtime: LocalRuntimeSettings): LoadedLocalModel {
    val api = native ?: throw LocalEngineException(NativeLlama.unavailableReasonText ?: NO_ENGINE)

    if (!backendReady) {
      // Before anything that can fault: a segfault in a kernel is not an exception, and the only
      // record of it is the file the engine writes from its own signal handler.
      api.nativeInstallCrashCapture(crashLogPath())
      // The CPU kernels are separate libraries, so they have to be found and registered before
      // anything can decode, and which of them runs is decided by the CPU this device actually
      // has rather than by the machine that compiled them.
      api.nativeInit(nativeLibDir())
      backendReady = true
    }

    // "use every core" is resolved here rather than left to the library, whose default is a
    // small fixed number: on a phone the difference between four threads and eight is a turn
    // that finishes before the user gives up on it.
    val threads = runtime.threadCount.takeIf { it > 0 } ?: systemThreads()
    val handle = try {
      api.nativeLoadModel(path, runtime.contextSize, threads, runtime.batchSize.coerceAtLeast(1))
    } catch (e: OutOfMemoryError) {
      throw LocalEngineException(
        "Not enough memory to load this model. Close other apps, or lower the model's context size.",
        e
      )
    }
    if (handle == 0L) throw LocalEngineException(describeFailure(api, "Could not load the model"))

    return try {
      NativeSession(api, handle, readInfo(api, handle))
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

  private fun readInfo(api: NativeLlama, handle: Long): LoadedModelInfo {
    val raw = api.nativeModelInfo(handle)?.toStringUtf8()
      ?: throw LocalEngineException(describeFailure(api, "Could not read the model's metadata"))
    val json = runCatching { JSONObject(raw) }.getOrNull()
      ?: throw LocalEngineException(describeFailure(api, "The model reported unreadable metadata"))
    return LoadedModelInfo(
      publishedName = json.optString("name"),
      architecture = json.optString("architecture"),
      vocabSize = json.optInt("vocabSize", 0),
      contextSize = json.optInt("contextSize", 0)
    )
  }

  /**
   * One open model.
   *
   * The native handle is freed on [close], while a decode may still be running on another
   * thread and [abort] may arrive at any moment — including from a UI stop button on a model
   * the user has already switched away from. So the handle's lifetime is reference-counted on
   * [state]: a close that lands mid-decode is remembered and carried out when the decode lets
   * go, which is the only order that cannot free a handle something is still using.
   */
  private class NativeSession(
    private val api: NativeLlama,
    private val handle: Long,
    override val info: LoadedModelInfo
  ) : LoadedLocalModel {

    private val state = Any()
    private var decodes = 0
    private var closeRequested = false
    private var released = false

    override fun capabilities(): LocalTemplateCapabilities {
      val bytes = api.nativeChatTemplatesInfo(handle)
        ?: return LocalTemplateCapabilities.unavailable.copy(
          reason = describeFailure(api, "The engine could not report what this template can do")
        )
      val json = runCatching { JSONObject(bytes.toStringUtf8()) }.getOrNull()
        ?: return LocalTemplateCapabilities.unavailable.copy(
          reason = "The engine reported unreadable template info"
        )
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
        // False when the file ships no template and llama.cpp's generic ChatML one is in use:
        // the model can still answer, but its formatting is a guess rather than the file's choice.
        usesOwnTemplate = json.optBoolean("explicit", false),
        supportsTools = caps["supports_tools"] == true && caps["supports_tool_calls"] == true,
        supportsParallelToolCalls = caps["supports_parallel_tool_calls"] == true,
        supportsThinking = caps["supports_preserve_reasoning"] == true || caps["supports_reasoning_effort"] == true,
        supportsSystemMessage = caps["supports_system_role"] == true
      )
    }

    /**
     * Render the transcript with this file's own template, decode it, and read the answer back
     * through the parser that same template generated.
     *
     * The parse is throttled rather than run per token: the parser re-reads the whole answer so
     * far each time, which is work the decode needs the cores for, and a client assembling a
     * stream cannot tell the difference between a delta that arrived after two tokens and one
     * that arrived after one.
     */
    override fun chat(request: LocalChatRequest, onDelta: (LocalAnswerDelta) -> Boolean): LocalFinishReason {
      beginUse()
      try {
        val turn = openTurn(request.inputs)
          ?: throw LocalEngineException(describeFailure(api, "Could not render this request"))
        val decoded = try {
          runTurn(turn, request, onDelta)
        } finally {
          api.nativeChatCloseTurn(turn)
        }
        return decoded
      } finally {
        endUse()
      }
    }

    private fun runTurn(
      turn: Long,
      request: LocalChatRequest,
      onDelta: (LocalAnswerDelta) -> Boolean
    ): LocalFinishReason {
      val rendered = NativeChatTurn(api, turn)
      val stops = rendered.stopSequences + request.inputs.stop
      val reader = AnswerReader(
        parse = rendered::parse,
        filter = StopSequenceFilter(stops),
        // A turn with no tools offered has no call to miss, so nothing reads over the engine's
        // shoulder: the engine's parser is the only thing that knows where a model's thinking was.
        fallback = if (request.inputs.tools.isEmpty()) null
        else NativeAnswerParser(stops, request.inputs.tools.map { it.name }),
        onDelta = onDelta,
      )
      val generated = StringBuilder()
      var piecesSinceParse = 0

      // The abort flag outlives a run, so each request starts with it cleared or a cancelled
      // turn would stop the next one too.
      api.nativeResetAbort(handle)
      // Whether this side ended the run. The engine can stop a decode on its own when a piece of
      // the answer cannot be handed over at all, and both endings arrive as the same finish code
      // while only one of them is a cancel: a refused handover is a turn that has to say so rather
      // than a stream that quietly stopped early.
      var sinkRefused = false
      val sink = object : NativeLlama.TokenSink {
        override fun onToken(piece: ByteArray): Boolean {
          generated.append(String(piece, StandardCharsets.UTF_8))
          if (++piecesSinceParse < PARSE_EVERY_PIECES) return true
          piecesSinceParse = 0
          val keep = reader.read(generated.toString(), final = false)
          if (!keep) sinkRefused = true
          return keep
        }
      }

      val settings = request.settings
      val code = api.nativeComplete(
        handle,
        rendered.prompt.toByteArray(StandardCharsets.UTF_8),
        settings.temperature.toFloat(),
        settings.topK,
        settings.topP.toFloat(),
        settings.repeatPenalty.toFloat(),
        settings.maxOutputTokens,
        request.seed ?: 0L,
        rendered.grammar?.toByteArray(StandardCharsets.UTF_8),
        sink
      )
      // -8 is the engine declining to start rather than failing: the prompt needs more context
      // than this model has. A different exception because a request that was too big says
      // nothing is wrong with the resident model, and burning it would make the user's next
      // turn pay for a reload that changes nothing.
      if (code == -8) throw LocalPromptTooLongException(lastError())

      // Code 2 means the sink said no, and the sink only ever said no from here — unless the engine
      // stopped itself because the answer could not be crossed over at all, which a phone out of
      // memory does mid-turn. That case used to look exactly like a cancel: a stream that ended
      // early with nothing said about why.
      if (code == 2 && !sinkRefused && !reader.stopTriggered) {
        throw LocalEngineException(describeFailure(api, "The model's answer could not be delivered"))
      }

      // Only a run that reached the end of an answer can be read one last time: re-parsing a
      // turn the user cut short would offer a tool call the model never finished writing.
      if (code == 0 || code == 1 || code == 4) reader.read(generated.toString(), final = true)
      // Read after that last parse, because the sequence that ended the turn can be one the
      // filter only recognises once the answer is whole — and a model that reached its own
      // end-of-turn marker finished, however the decoder counts it.
      return nativeFinishReason(code, stopTriggered = reader.stopTriggered)
        ?: throw LocalEngineException("Inference failed (engine code $code): " + lastError())
    }

    private fun openTurn(inputs: LocalChatInputs): Long? {
      // The template is compiled once at load; a model whose template failed to compile has no
      // turn to open, and the reason recorded then is still the useful one.
      val capabilities = capabilities()
      if (!capabilities.available) {
        throw LocalEngineException(capabilities.reason.ifBlank { "This model has no usable chat template" })
      }
      val wire = inputs.toWireJson().toString().toByteArray(StandardCharsets.UTF_8)
      return api.nativeChatOpenTurn(handle, wire).takeIf { it != 0L }
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

    private fun lastError(): String =
      api.nativeLastError()?.toStringUtf8()?.trim()?.ifEmpty { null } ?: "unknown error"
  }

  companion object {
    private const val NO_ENGINE =
      "This build has no on-device inference engine. On-device models need the 64-bit app; a " +
        "32-bit phone cannot run one."

    /** How many decoded pieces can pile up before the answer gets re-read. */
    private const val PARSE_EVERY_PIECES = 3
  }
}

/**
 * The two readers of one answer.
 *
 * The engine's parser — generated from the model's own chat template — reads every turn, and it is
 * the right reader for a model that answers the way its template was written. Its blind spot is a
 * model that writes the same dialect with the markup left out: that call comes back as content, and
 * an agent loop handed prose runs no tool ([NativeAnswerParser] is what reads it instead). So the
 * fallback parses the same text in parallel until one of the two reports a call, which is the only
 * disagreement that matters and the one a bare dialect announces in its first tokens.
 *
 * The handover has to happen before the engine puts those tokens on the wire, because a stream
 * cannot take a piece back. Until the two agree the engine's deltas are held here, which costs one
 * parse interval at the start of an answer whose first token already waited for the whole prefill.
 *
 * Once the engine reports a call, or the fallback agrees that the answer began as prose, the engine
 * reads the rest of the turn alone and the fallback is not fed again.
 */
internal class AnswerReader(
  /**
   * The engine's reader of the turn: the text as far as it has arrived, and whether it is still
   * arriving. [AnswerReader] is the only place that knows both words for that flag.
   */
  private val parse: (String, Boolean) -> List<LocalAnswerDelta>,
  private val filter: StopSequenceFilter,
  private val fallback: NativeAnswerParser?,
  private val onDelta: (LocalAnswerDelta) -> Boolean,
) {

  /** What the fallback has been given already, since it reads pieces rather than whole answers. */
  private val fed = StringBuilder()

  /** Every byte of the turn the engine decoded, which is what [showWordsIfNothing] falls back on. */
  private var raw = ""
  private var deciding = fallback != null
  private var usingFallback = false
  private var held: List<LocalAnswerDelta> = emptyList()

  /** True once anything at all reached the caller. */
  private var published = false

  /** True when the answer reached one of the sequences that ends a turn. */
  val stopTriggered: Boolean get() = filter.triggered != null

  /** Hands the caller everything new in [text]. False means the decode should stop. */
  fun read(text: String, final: Boolean): Boolean {
    if (text.length > raw.length) raw = text
    if (!deciding) {
      return publish(if (usingFallback) fallback!!.feed(newText(text), final) else parse(text, !final), final)
    }
    val engineDeltas = parse(text, !final)
    val fallbackDeltas = fallback!!.feed(newText(text), final)
    return when {
      // The engine recognised a call: it knows this model's dialect, so it keeps reading it. Whether
      // that call has a name yet is the caller's business — the wire forwards what was parsed and the
      // agent runs the named ones — and holding the turn here to wait for one keeps both readers
      // re-reading an answer that never settles, which stops the stream and shows the model's markup.
      engineDeltas.any { it.toolCall != null } -> engineWins(held + engineDeltas, final)
      fallback.askedForTool -> {
        usingFallback = true
        deciding = false
        held = emptyList()
        publish(fallbackDeltas, final)
      }
      // Prose on both sides is agreement, and an answer that opens with prose is not the dialect
      // this race was there to catch.
      fallback.content.isNotEmpty() || final -> engineWins(held + engineDeltas, final)
      // Everything so far is the head of a marker: show it as text and a tool call flashes on
      // screen as a word, so it waits for the piece that settles it.
      else -> {
        held = held + engineDeltas
        true
      }
    }
  }

  private fun engineWins(deltas: List<LocalAnswerDelta>, final: Boolean): Boolean {
    deciding = false
    held = emptyList()
    return publish(deltas, final)
  }

  private fun publish(deltas: List<LocalAnswerDelta>, final: Boolean): Boolean {
    for (delta in deltas) {
      val piece = if (delta.reasoning.isEmpty() && delta.toolCall == null) {
        delta.copy(content = filter.feed(delta.content))
      } else {
        // Thinking and tool arguments are the model's own markup, already separated by the
        // template's parser: a stop sequence cannot appear inside one.
        delta
      }
      if (!piece.isEmpty) {
        published = true
        if (!onDelta(piece)) return false
      }
      if (filter.triggered != null) return false
    }
    if (final) {
      // The tail that was only ambiguous about a stop sequence is real text once the answer ends.
      val tail = filter.flush()
      if (tail.isNotEmpty()) return onDelta(LocalAnswerDelta(content = tail))
      return showWordsIfNothing()
    }
    return true
  }

  /**
   * The turn's own text, published if the readers made nothing of it.
   *
   * Both readers can report a turn as finished with nothing in it, and each time the user sees an
   * empty message instead of an answer. The template's parser discards a call whose function name
   * it never matched (common_chat_parse throws away the markup around it), and this engine reports
   * no call at all when the answer's shape is one the template does not allow. Neither is the
   * model having said nothing: the bytes are the words it chose, so they go out as content once, at
   * the end, rather than as fragments of markup flashing on screen mid-turn.
   */
  private fun showWordsIfNothing(): Boolean {
    if (published || filter.triggered != null) return true
    val words = raw.trim()
    if (words.isEmpty()) return true
    published = true
    return onDelta(LocalAnswerDelta(content = words))
  }

  private fun newText(text: String): String {
    if (text.length <= fed.length) return ""
    val tail = text.substring(fed.length)
    fed.setLength(0)
    fed.append(text)
    return tail
  }
}

/**
 * One rendered request, read back from the engine.
 *
 * [parse] reports the deltas since its last call, which is what lets a stream carry tool
 * arguments without ever showing the model's markup to the user. A final parse of text the
 * template will not accept is the model answering in a shape its own file does not allow: the
 * words are still the answer that was asked for, so they go out as content instead of a silent
 * empty turn.
 */
internal class NativeChatTurn(private val api: NativeLlama, private val handle: Long) {

  private val info: JSONObject = runCatching {
    JSONObject(api.nativeChatTurnInfo(handle)?.toStringUtf8().orEmpty())
  }.getOrElse {
    throw LocalEngineException(describeFailure(api, "The engine could not hand over the rendered prompt"))
  }

  val prompt: String = info.optString("prompt")

  // An empty grammar means the template asked for no constraints; the engine takes null for
  // that, not a zero-length file.
  val grammar: String? = info.optString("grammar").takeIf { it.isNotEmpty() }

  /** The sequences this template writes to end a turn, on top of the ones the caller asked for. */
  val stopSequences: List<String> = info.optJSONArray("additionalStops").stringList()

  val format: String = info.optString("format")

  val expectsToolCalls: Boolean = info.optBoolean("hasTools", false)

  val supportsThinking: Boolean = info.optBoolean("supportsThinking", false)

  fun parse(text: String, partial: Boolean): List<LocalAnswerDelta> {
    // A handover the Java heap refused reports no deltas rather than wrong ones: the text stays
    // where it is, and the next read re-parses the whole answer from the engine's own buffer.
    val json = runCatching {
      JSONObject(
        api.nativeChatParse(handle, text.toByteArray(StandardCharsets.UTF_8), partial)?.toStringUtf8().orEmpty()
      )
    }.getOrNull() ?: return emptyList()
    if (json.optBoolean("error", false)) {
      if (partial || json.optBoolean("partial", false)) return emptyList()
      return listOf(LocalAnswerDelta(content = text))
    }
    return json.optJSONArray("deltas").deltaList()
  }
}

/** The request as the engine's template layer wants it: the OpenAI wire format. */
internal fun LocalChatInputs.toWireJson(): JSONObject = JSONObject().apply {
  put("messages", JSONArray().apply {
    for (message in this@toWireJson.messages) {
      val node = JSONObject()
      node.put("role", message.role)
      // Always present: a template distinguishes an empty turn from a message that said nothing
      // about content, and an absent key is the one case it cannot render.
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
  // The app only ever asks the model to answer, never to inspect a transcript.
  put("add_generation_prompt", true)
  put("parallel_tool_calls", parallelToolCalls)
  put("enable_thinking", enableThinking)
}

/**
 * Decodes text the engine produced.
 *
 * UTF-8 bytes cross the JNI boundary rather than strings because `NewStringUTF` takes
 * modified-UTF-8, which mangles the emoji and CJK a model answers with. Decoding happens on
 * this side, where the bytes mean what the weights wrote.
 */
private fun ByteArray.toStringUtf8(): String = String(this, StandardCharsets.UTF_8)

private fun JSONArray?.stringList(): List<String> =
  if (this == null) emptyList() else (0 until length()).map { optString(it) }

private fun JSONArray?.jsonObjectList(): List<JSONObject> =
  if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.deltaList(): List<LocalAnswerDelta> =
  if (this == null) emptyList() else (0 until length()).map { i ->
    val node = optJSONObject(i) ?: return@map LocalAnswerDelta()
    val index = node.optInt("toolCallIndex", -1)
    LocalAnswerDelta(
      content = node.optString("content"),
      reasoning = node.optString("reasoning"),
      toolCallIndex = index,
      toolCall = node.optJSONObject("toolCall")?.let { call ->
        LocalToolCall(
          id = call.optString("id").ifEmpty { "call_$index" },
          name = call.optString("name"),
          argumentsJson = call.optString("arguments").ifEmpty { "{}" }
        )
      }
    )
  }

/**
 * The engine's finish code, in terms the API layer maps onto a finish_reason.
 *
 * Code 2 means the decode stopped because its sink said no, which is two different things: a
 * client that went away, and the answer reaching the end-of-turn marker this template writes.
 * The second is a finished turn and the first is not, so the stop filter decides.
 */
internal fun nativeFinishReason(code: Int, stopTriggered: Boolean): LocalFinishReason? = when (code) {
  0 -> LocalFinishReason.END_OF_SEQUENCE
  1 -> LocalFinishReason.MAX_TOKENS
  2 -> if (stopTriggered) LocalFinishReason.END_OF_SEQUENCE else LocalFinishReason.ABORTED
  3 -> LocalFinishReason.ABORTED
  4 -> LocalFinishReason.CONTEXT_FULL
  else -> null
}

private fun describeFailure(api: NativeLlama, action: String): String {
  // The reason is itself a handover, so it can fail too — and a failure here must not become a
  // second one standing where the user was about to be told about the first.
  val detail = api.nativeLastError()?.toStringUtf8()?.trim().orEmpty()
  return if (detail.isEmpty()) action else "$action: $detail"
}
