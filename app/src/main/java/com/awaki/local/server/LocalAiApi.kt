package com.awaki.local.server

import com.awaki.data.repository.LocalModelRepository
import com.awaki.local.model.LocalGenerationSettings
import com.awaki.local.model.LocalModel
import com.awaki.local.runtime.LocalAnswerDelta
import com.awaki.local.runtime.LocalChatInputs
import com.awaki.local.runtime.LocalChatMessage
import com.awaki.local.runtime.LocalChatTool
import com.awaki.local.runtime.LocalEngineException
import com.awaki.local.runtime.LocalFinishReason
import com.awaki.local.runtime.LocalInferenceEngine
import com.awaki.local.runtime.LocalPromptTooLongException
import com.awaki.local.runtime.LocalToolCall
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The OpenAI-compatible surface of the models on this device.
 *
 * Answering `GET /v1/models` and `POST /v1/chat/completions` like any OpenAI router is
 * what makes a local model *not* a special case: the agent loop, the streaming UI, the
 * retries, the approvals and the tool runner all keep working untouched, because from
 * their side there is just another provider at a base URL. Nothing in this file knows
 * about GGUF, tokens or template dialects — it knows the wire format, and it asks
 * [LocalInferenceEngine] for an answer.
 *
 * Transport lives next door in [LocalAiServer]; this class takes a request body and a
 * callback for streamed chunks, which keeps every rule here testable on the JVM.
 */
class LocalAiApi(
  private val engine: LocalInferenceEngine,
  private val repository: LocalModelRepository
) {

  /**
   * A reply for the transport. [Streamed] means the chunks already went out through the
   * callback, so only the status line and the SSE headers are left to write; a request
   * that failed before its first chunk is reported as a plain [Body] instead, because a
   * client that never saw `text/event-stream` should not be handed one.
   */
  sealed interface Reply {
    val status: Int

    data class Body(override val status: Int, val json: String) : Reply
    data class Streamed(override val status: Int) : Reply
  }

  /**
   * Ends the decode in flight.
   *
   * The transport calls this when writing to a stream fails, which is the first moment it can know
   * the client went away: a phone decoding a two-thousand-token prompt has nothing to write for
   * seconds, and the model is not going to notice on its own that nobody is listening. Without
   * this the orphan turn runs to its end while holding the one model slot, and the request the
   * user actually made next waits on it.
   */
  fun stopGeneration() = engine.stop()

  /** Handles one request. [emit] writes one SSE `data:` payload and returns false when the client is gone. */
  suspend fun handle(method: String, path: String, body: String, emit: (String) -> Boolean): Reply {
    val route = path.trimEnd('/').removePrefix("/v1").trimStart('/')
    return when {
      method == "GET" && (route == "models" || route.isEmpty()) -> models()
      method == "GET" && route.startsWith("models/") -> model(route.removePrefix("models/"))
      method == "POST" && route == "chat/completions" -> chatCompletions(body, emit)
      method == "GET" && route == "health" -> Reply.Body(200, JSONObject().put("status", "ok").toString())
      else -> Reply.Body(404, errorJson("Unknown endpoint '$route'", "invalid_request_error"))
    }
  }

  /**
   * The installed models, in the shape Awaki's own model listing reads.
   *
   * `context_length` and `max_tokens` come from the user's saved configuration for each
   * model rather than from the file, because that is what the engine will actually create
   * on this device. `supported_parameters` is answered from the loaded model's template
   * when one is resident — that is a fact — and left optimistic when it is not, because
   * asking every model to load just to fill in a listing would cost the user seconds and
   * memory for a label.
   */
  private fun models(): Reply {
    val data = JSONArray()
    for (model in repository.installedModels()) {
      val capabilities = engine.loadedCapabilities().takeIf { engine.loadedModelId == model.id }
      data.put(
        JSONObject()
          .put("id", model.id)
          .put("object", "model")
          .put("created", model.createdAtEpochSeconds())
          .put("owned_by", OWNERSHIP)
          .put("context_length", model.configuration.runtime.contextSize)
          .put("max_tokens", model.configuration.generation.maxOutputTokens)
          .put(
            "supported_parameters",
            JSONArray().apply {
              put("temperature"); put("top_p"); put("max_tokens"); put("seed"); put("stop"); put("streaming")
              if (capabilities == null || capabilities.supportsTools) put("tools")
            }
          )
      )
    }
    return Reply.Body(200, JSONObject().put("object", "list").put("data", data).toString())
  }

  private fun model(id: String): Reply {
    val model = repository.model(id)?.takeIf { it.installed }
      ?: return Reply.Body(404, errorJson("The model '$id' is not installed on this device", "invalid_request_error"))
    return Reply.Body(
      200,
      JSONObject()
        .put("id", model.id)
        .put("object", "model")
        .put("created", model.createdAtEpochSeconds())
        .put("owned_by", OWNERSHIP)
        .put("root", model.id)
        .toString()
    )
  }

  private suspend fun chatCompletions(body: String, emit: (String) -> Boolean): Reply {
    val request = runCatching { JSONObject(body) }.getOrElse {
      return Reply.Body(400, errorJson("Request body is not valid JSON", "invalid_request_error"))
    }
    val modelId = request.optString("model")
    if (modelId.isBlank()) {
      return Reply.Body(400, errorJson("Missing 'model'", "invalid_request_error"))
    }
    val model = repository.model(modelId)?.takeIf { it.installed }
      ?: return Reply.Body(404, errorJson("The model '$modelId' is not installed on this device", "invalid_request_error"))

    // One read of the saved configuration; the request overrides whatever it carries,
    // so a caller that says nothing gets the user's numbers and one that says 0.9 gets 0.9.
    // Reading it is also where a malformed transcript or an unusable parameter is caught.
    val parsed = runCatching { WireRequest(request, repository.configuration(model.id).generation) }
      .getOrElse {
        return Reply.Body(400, errorJson(it.message ?: "The request does not fit the chat format", "invalid_request_error"))
      }

    // Loading the model is what makes its template's abilities known. A model whose template has
    // nowhere to put tool definitions would answer as though no tools had been offered at all —
    // a quietly wrong answer, so it is refused instead.
    val capabilities = try {
      engine.capabilities(model)
    } catch (e: LocalEngineException) {
      return Reply.Body(503, errorJson(e.message ?: "The model could not start", "server_error"))
    }
    if (parsed.chatInputs.tools.isNotEmpty() && !capabilities.supportsTools) {
      return Reply.Body(
        400,
        errorJson("The model '$modelId' has no tool-calling template, so it cannot be given tools.", "invalid_request_error")
      )
    }
    return runCompletion(model, parsed, emit)
  }

  /**
   * Decodes one turn and hands the answer out, streamed or whole.
   *
   * The runtime has already rendered the transcript with the model's own template, watched for
   * the sequences that end the turn and read the answer back into content, reasoning and tool
   * calls — so what arrives here is exactly what a client is told. This layer only keeps the
   * OpenAI envelope: the opening frame rides with the first real delta rather than before the
   * decode starts, because once it is written the only remaining answer is a truncated one,
   * and before it a failure can still be returned as the plain 4xx request error that it is.
   */
  private suspend fun runCompletion(
    model: LocalModel,
    request: WireRequest,
    emit: (String) -> Boolean
  ): Reply {
    val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "")
    val created = System.currentTimeMillis() / 1000L
    val stream = request.stream
    val answer = Answer()
    var clientGone = false
    var headSent = false

    /** The frame that tells a client an answer is coming. */
    fun emitHead(): Boolean {
      headSent = true
      return emit(chunk(id, created, request.model, JSONObject().put("role", "assistant"), null))
    }

    var finish = LocalFinishReason.END_OF_SEQUENCE
    var failure: LocalEngineException? = null
    try {
      finish = engine.chat(model, request.chatInputs, request.settings, request.seed) { delta ->
        val publishable = answer.publish(delta)
        // Only a client that went away is a reason to stop the decode; a caller that asked for
        // the answer in one piece has nowhere to push to and keeps going.
        if (!stream || clientGone) return@chat true
        for (piece in publishable) {
          val node = deltaNode(piece) ?: continue
          if (!headSent && !emitHead()) {
            clientGone = true
            return@chat false
          }
          if (!emit(chunk(id, created, request.model, node, null))) {
            clientGone = true
            return@chat false
          }
        }
        true
      }
    } catch (e: LocalEngineException) {
      failure = e
    }

    failure?.let { error ->
      // A prompt that would not fit is the request being wrong, not this server failing.
      // The distinction decides what happens next upstream: a 400 with
      // `invalid_request_error` ends the run and tells the user which number to change,
      // while a server error is retried — and retrying the same oversized prompt fails the
      // same way, which is how a turn stalls into silence.
      val tooLong = error is LocalPromptTooLongException
      val body = errorJson(error.message ?: "Generation failed", if (tooLong) "invalid_request_error" else "server_error")
      return if (headSent) {
        emit(body)
        emit("[DONE]")
        Reply.Streamed(200)
      } else {
        Reply.Body(if (tooLong) 400 else 500, body)
      }
    }

    val finishReason = finishReasonFor(finish, answer)

    if (!stream) return Reply.Body(200, completionJson(id, created, request.model, answer, finishReason))

    // A turn that showed no text — an empty answer, or markup the runtime consumed — still
    // has to open its stream the way every other one does: role first, then the end.
    if (!clientGone && !headSent) emitHead()
    emit(chunk(id, created, request.model, JSONObject(), finishReason))
    emit("[DONE]")
    return Reply.Streamed(200)
  }

  private companion object {
    const val OWNERSHIP = "awaki-local"

    /** Maps the runtime's reason for stopping onto OpenAI's `finish_reason`. */
    fun finishReasonFor(finish: LocalFinishReason, answer: Answer): String = when {
      finish == LocalFinishReason.MAX_TOKENS || finish == LocalFinishReason.CONTEXT_FULL -> "length"
      finish == LocalFinishReason.ABORTED -> "abort"
      // A call the model finished writing is a call the caller has to run; a turn that ended
      // on its own marker is finished, however the decoder counts it.
      answer.toolCalls.isNotEmpty() -> "tool_calls"
      else -> "stop"
    }

    /** One streamed change in the shape the client appends deltas from. */
    fun deltaNode(delta: LocalAnswerDelta): JSONObject? {
      val node = JSONObject()
      if (delta.reasoning.isNotEmpty()) node.put("reasoning_content", delta.reasoning)
      if (delta.content.isNotEmpty()) node.put("content", delta.content)
      val call = delta.toolCall
      if (call != null) {
        val piece = JSONObject().put("index", delta.toolCallIndex)
        if (call.id.isNotEmpty()) piece.put("id", call.id).put("type", "function")
        val function = JSONObject()
        if (call.name.isNotEmpty()) function.put("name", call.name)
        if (call.argumentsJson.isNotEmpty()) function.put("arguments", call.argumentsJson)
        if (function.length() > 0) piece.put("function", function)
        if (piece.length() > 1) node.put("tool_calls", JSONArray().put(piece))
      }
      return node.takeIf { it.length() > 0 }
    }
  }
}

/**
 * The answer as it arrives, reassembled from its fragments.
 *
 * A tool call streams as its name and id once and its arguments growing token by token, so
 * they are joined here by index — which is how a client that asked for one JSON body gets the
 * same calls a streaming client assembles for itself.
 */
internal class Answer {
  private val text = StringBuilder()
  private val thinking = StringBuilder()
  private val calls = sortedMapOf<Int, LocalToolCall>()

  /** Fragments of a call whose name has not arrived yet, held back by [publish]. */
  private val pending = sortedMapOf<Int, MutableList<LocalAnswerDelta>>()

  val content: String get() = text.toString()
  val reasoning: String get() = thinking.toString()

  /** Only a call that named its function is one the caller can run. */
  val toolCalls: List<LocalToolCall> get() = calls.values.filter { it.name.isNotEmpty() }

  /**
   * The pieces of [delta] a client may be given, which is not always the delta itself.
   *
   * An incremental parser knows a call has started before it knows which tool the model meant: the
   * dialect puts its arguments first, or the markup arrives one token at a time and only the last
   * of them spells out a name. Forwarding those fragments as-is hands the caller a tool call whose
   * name is the empty string, and the agent then spends a round being told that no such tool
   * exists — for a model that was trying to end its turn, once per turn. So a call's fragments wait
   * here until something names it, and go out together with the delta that does.
   *
   * A call that is never named never goes out at all. Its bytes are the model's markup rather than
   * an answer, and they belong neither in `tool_calls` nor in `content`: the turn reads as finished
   * with nothing offered, which is what actually happened.
   */
  fun publish(delta: LocalAnswerDelta): List<LocalAnswerDelta> {
    val call = delta.toolCall
    if (call == null) {
      append(delta)
      return listOf(delta)
    }
    val named = call.name.isNotEmpty() || calls[delta.toolCallIndex]?.name?.isNotEmpty() == true
    if (!named) {
      pending.getOrPut(delta.toolCallIndex) { mutableListOf() }.add(delta)
      return emptyList()
    }
    val held = pending.remove(delta.toolCallIndex).orEmpty()
    // The held fragments are this call's own bytes, so they join the reassembled call as well as
    // the stream: a caller that asked for one JSON body would otherwise get arguments with the
    // front of them missing.
    held.forEach { append(it) }
    append(delta)
    return held + delta
  }

  fun append(delta: LocalAnswerDelta) {
    text.append(delta.content)
    thinking.append(delta.reasoning)
    val piece = delta.toolCall ?: return
    val previous = calls[delta.toolCallIndex] ?: LocalToolCall("", "", "")
    calls[delta.toolCallIndex] = LocalToolCall(
      id = piece.id.ifEmpty { previous.id },
      name = piece.name.ifEmpty { previous.name },
      argumentsJson = previous.argumentsJson + piece.argumentsJson
    )
  }
}

/** Wall-clock seconds, the unit OpenAI's `created` field is in. */
private fun LocalModel.createdAtEpochSeconds(): Long =
  (localPath?.let { java.io.File(it).lastModified() } ?: System.currentTimeMillis()) / 1000L

/** One streamed chunk: the same envelope every time, with a different delta inside. */
private fun chunk(id: String, created: Long, model: String, delta: JSONObject, finishReason: String?): String =
  JSONObject()
    .put("id", id)
    .put("object", "chat.completion.chunk")
    .put("created", created)
    .put("model", model)
    .put(
      "choices",
      JSONArray().put(
        JSONObject()
          .put("index", 0)
          .put("delta", delta)
          .apply { finishReason?.let { put("finish_reason", it) } }
      )
    )
    .toString()

/**
 * The whole answer at once.
 *
 * `content` stays a string even when the turn only produced calls — a client that reads
 * the field without looking first gets empty text rather than a literal "null" to show
 * the user. Usage counts are left out on purpose: this engine reports no token tally for
 * a run, and inventing one would be worse than saying nothing.
 */
private fun completionJson(
  id: String,
  created: Long,
  model: String,
  answer: Answer,
  finishReason: String
): String = JSONObject()
  .put("id", id)
  .put("object", "chat.completion")
  .put("created", created)
  .put("model", model)
  .put(
    "choices",
    JSONArray().put(
      JSONObject()
        .put("index", 0)
        .put(
          "message",
          JSONObject()
            .put("role", "assistant")
            .put("content", answer.content)
            .apply {
              if (answer.reasoning.isNotEmpty()) put("reasoning_content", answer.reasoning)
              if (answer.toolCalls.isNotEmpty()) {
                put(
                  "tool_calls",
                  JSONArray().apply {
                    for (call in answer.toolCalls) {
                      put(
                        JSONObject()
                          .put("id", call.id)
                          .put("type", "function")
                          .put(
                            "function",
                            JSONObject()
                              .put("name", call.name)
                              .put("arguments", call.argumentsJson)
                          )
                      )
                    }
                  }
                )
              }
            }
        )
        .put("finish_reason", finishReason)
    )
  )
  .toString()

/** The body OpenAI's clients read failures from, so a local error behaves like a remote one. */
internal fun errorJson(message: String, type: String): String =
  JSONObject().put("error", JSONObject().put("message", message).put("type", type)).toString()

/**
 * Whether this body asks for a streamed answer, read by the transport before the request gets here.
 *
 * It decides how long the server may stay silent before committing to `text/event-stream`: headers
 * written for a request that wanted one JSON body are a response its client cannot parse. A body
 * that is not readable JSON is not a streaming request either — the API answers that with a 400 a
 * moment later, which is exactly what the transport is waiting to find out.
 */
internal fun requestsEventStream(body: String): Boolean =
  runCatching { JSONObject(body).optBoolean("stream", false) }.getOrDefault(false)

/**
 * A chat request read into what the engine needs, with the model's saved generation
 * settings underneath the caller's overrides.
 *
 * Everything it refuses, it refuses out loud. A parameter this runtime cannot honour —
 * an image part, a call forced to one named function — is an error the client sees, not
 * something quietly dropped: an answer about a picture nobody sent looks exactly like an
 * answer, and the user has no way to tell.
 */
private class WireRequest(raw: JSONObject, saved: LocalGenerationSettings) {

  val model: String = raw.optString("model")
  val stream: Boolean = raw.optBoolean("stream", false)
  val stop: List<String> = stringList(raw.opt("stop"))
  val seed: Long? = (raw.opt("seed") as? Number)?.toLong()

  val settings: LocalGenerationSettings = saved.copy(
    maxOutputTokens = intOf(raw, "max_tokens", "max_completion_tokens")
      ?.coerceAtLeast(1) ?: saved.maxOutputTokens,
    temperature = doubleOf(raw, "temperature")?.coerceIn(0.0, 2.0) ?: saved.temperature,
    topK = intOf(raw, "top_k")?.coerceAtLeast(1) ?: saved.topK,
    topP = doubleOf(raw, "top_p")?.coerceIn(0.0, 1.0) ?: saved.topP,
    repeatPenalty = doubleOf(raw, "repeat_penalty") ?: saved.repeatPenalty
  )

  val chatInputs: LocalChatInputs = LocalChatInputs(
    messages = chatMessages(raw.optJSONArray("messages")),
    tools = chatTools(raw),
    toolChoice = toolChoiceOf(raw),
    // A caller that asks for no thinking is honoured; `reasoning_effort: none` is the
    // OpenAI-shaped way to say the same thing.
    enableThinking = thinkingRequested(raw),
    parallelToolCalls = raw.optBoolean("parallel_tool_calls", false),
    stop = stop
  )
}

private val ROLES = setOf("system", "user", "assistant", "tool")
private val TOOL_CHOICES = setOf("auto", "required", "none")

/** The transcript, in the shape the template layer takes it. */
private fun chatMessages(array: JSONArray?): List<LocalChatMessage> {
  if (array == null || array.length() == 0) throw IllegalArgumentException("'messages' must be a non-empty array")
  return (0 until array.length()).map { i ->
    val node = array.optJSONObject(i)
      ?: throw IllegalArgumentException("messages[$i] is not an object")
    val role = node.optString("role").takeIf { it.isNotBlank() }
      ?: throw IllegalArgumentException("messages[$i] has no role")
    if (role !in ROLES) throw IllegalArgumentException("Role '$role' is not supported on-device")
    LocalChatMessage(
      role = role,
      content = contentOf(node, "messages[$i]"),
      toolCalls = toolCallsOf(node, "messages[$i]"),
      toolCallId = node.optString("tool_call_id").takeIf { it.isNotEmpty() },
      reasoningContent = node.optString("reasoning_content").takeIf { it.isNotEmpty() }
    )
  }
}

/**
 * Message text. A bare string is the common case; the part list is what a client sends
 * when it might also send an image, and an image is refused rather than dropped — a
 * text-only model answering about a picture nobody sent looks exactly like an answer.
 */
private fun contentOf(node: JSONObject, where: String): String = when (val value = node.opt("content")) {
  null -> ""
  is String -> value
  is JSONArray -> (0 until value.length()).joinToString("") { i ->
    val part = value.optJSONObject(i)
      ?: throw IllegalArgumentException("$where.content[$i] is not an object")
    when (val type = part.optString("type")) {
      "text", "output_text", "" -> part.optString("text")
      else -> throw IllegalArgumentException("This model runs on-device and is text-only; it cannot read '$type' content")
    }
  }
  else -> throw IllegalArgumentException("$where.content is neither text nor a list of parts")
}

private fun toolCallsOf(node: JSONObject, where: String): List<LocalToolCall> {
  val array = node.optJSONArray("tool_calls") ?: return emptyList()
  return (0 until array.length()).map { i ->
    val call = array.optJSONObject(i)
      ?: throw IllegalArgumentException("$where.tool_calls[$i] is not an object")
    val function = call.optJSONObject("function")
      ?: throw IllegalArgumentException("$where.tool_calls[$i] does not name a function")
    LocalToolCall(
      id = call.optString("id").ifEmpty { "call_$i" },
      name = function.optString("name"),
      argumentsJson = function.optString("arguments").ifEmpty { "{}" }
    )
  }
}

private fun chatTools(raw: JSONObject): List<LocalChatTool> {
  val array = raw.optJSONArray("tools") ?: return emptyList()
  return (0 until array.length()).map { i ->
    val function = array.optJSONObject(i)?.optJSONObject("function")
      ?: throw IllegalArgumentException("tools[$i] must describe a function")
    val name = function.optString("name").takeIf { it.isNotBlank() }
      ?: throw IllegalArgumentException("tools[$i] has no name")
    LocalChatTool(
      name = name,
      description = function.optString("description"),
      parametersJsonSchema = function.optJSONObject("parameters")?.toString() ?: "{}"
    )
  }
}

private fun toolChoiceOf(raw: JSONObject): String = when (val value = raw.opt("tool_choice")) {
  null -> "auto"
  is String -> value.takeIf { it in TOOL_CHOICES }
    ?: throw IllegalArgumentException("tool_choice '$value' is not supported on-device")
  else -> throw IllegalArgumentException(
    "This model cannot be forced to one named function; tool_choice must be 'auto', 'required' or 'none'"
  )
}

private fun thinkingRequested(raw: JSONObject): Boolean {
  if (raw.opt("enable_thinking") == false) return false
  return raw.optString("reasoning_effort").lowercase() != "none"
}

/** `stop` is one string or a list of them; anything else stops nothing. */
private fun stringList(value: Any?): List<String> = when (value) {
  is String -> listOf(value)
  is JSONArray -> (0 until value.length()).map { value.optString(it) }.filter { it.isNotEmpty() }
  else -> emptyList()
}

private fun intOf(raw: JSONObject, vararg keys: String): Int? =
  keys.firstNotNullOfOrNull { key -> (raw.opt(key) as? Number)?.toInt() }

private fun doubleOf(raw: JSONObject, key: String): Double? = (raw.opt(key) as? Number)?.toDouble()
