package com.agentisco.local.server

import com.agentisco.data.repository.LocalModelRepository
import com.agentisco.local.model.LocalGenerationSettings
import com.agentisco.local.model.LocalModel
import com.agentisco.local.runtime.LocalChatInputs
import com.agentisco.local.runtime.LocalChatMessage
import com.agentisco.local.runtime.LocalChatTool
import com.agentisco.local.runtime.LocalChatTurn
import com.agentisco.local.runtime.LocalEngineException
import com.agentisco.local.runtime.LocalFinishReason
import com.agentisco.local.runtime.LocalInferenceEngine
import com.agentisco.local.runtime.LocalParseDelta
import com.agentisco.local.runtime.LocalParsedMessage
import com.agentisco.local.runtime.LocalToolCall
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
 * about GGUF, tokens or JNI — it knows the wire format, and it asks [LocalInferenceEngine]
 * for an answer.
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
   * The installed models, in the shape Agentisco's own model listing reads.
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

    val turn = try {
      engine.openTurn(model, parsed.chatInputs)
    } catch (e: LocalEngineException) {
      return Reply.Body(503, errorJson(e.message ?: "The model could not start", "server_error"))
    }
    return try {
      // Rendering the request is what made the template's capabilities known. A model whose
      // template has nowhere to put tool definitions would answer as though no tools had
      // been offered at all — a quietly wrong answer, so it is refused instead.
      if (parsed.chatInputs.tools.isNotEmpty() && engine.loadedCapabilities()?.supportsTools == false) {
        Reply.Body(
          400,
          errorJson("The model '$modelId' has no tool-calling template, so it cannot be given tools.", "invalid_request_error")
        )
      } else {
        runCompletion(model, parsed, turn, emit)
      }
    } finally {
      turn.close()
    }
  }

  /**
   * Decodes one turn and hands the answer out, streamed or whole.
   *
   * Two things happen between the engine and the client. The sequences that end the
   * turn are watched for and cut — llama.cpp's stop-string hook is not in the build
   * this runs in, so a model's own end-of-turn marker arrives as ordinary text and
   * would reach the conversation if nothing caught it. And the answer is read back
   * through the template's own parser, so what the client sees is content, reasoning
   * and tool calls rather than the markup the model wrapped them in.
   */
  private suspend fun runCompletion(
    model: LocalModel,
    request: WireRequest,
    turn: LocalChatTurn,
    emit: (String) -> Boolean
  ): Reply {
    val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "")
    val created = System.currentTimeMillis() / 1000L
    val stream = request.stream
    val visible = StringBuilder()
    val filter = StopSequenceFilter(turn.stopSequences + request.stop)
    var clientGone = false
    var headSent = false

    /** Re-reads everything shown so far and pushes whatever is new. */
    fun publish(): Boolean {
      // Only a client that went away is a reason to stop the decode; a caller that asked
      // for the answer in one piece has nowhere to push to and keeps going.
      if (clientGone) return false
      if (!stream || visible.isEmpty()) return true
      for (delta in turn.parse(visible.toString(), partial = true).deltas) {
        val node = deltaNode(delta) ?: continue
        headSent = true
        if (!emit(chunk(id, created, request.model, node, null))) {
          clientGone = true
          return false
        }
      }
      return true
    }

    if (stream) {
      headSent = emit(chunk(id, created, request.model, JSONObject().put("role", "assistant"), null))
      if (!headSent) clientGone = true
    }

    var finish = LocalFinishReason.END_OF_SEQUENCE
    var failure: LocalEngineException? = null
    try {
      finish = engine.generate(
        model = model,
        prompt = turn.prompt,
        grammar = turn.grammar,
        seed = request.seed,
        settings = request.settings
      ) { piece ->
        val text = filter.feed(piece)
        if (text.isNotEmpty()) {
          visible.append(text)
          if (!publish()) return@generate false
        }
        // End of the turn, or a client that stopped listening: either way there is no
        // sense spending the rest of the battery budget on text nobody will read.
        !clientGone && filter.triggered == null
      }
    } catch (e: LocalEngineException) {
      failure = e
    }

    failure?.let {
      val body = errorJson(it.message ?: "Generation failed", "server_error")
      return if (headSent) {
        emit(body)
        emit("[DONE]")
        Reply.Streamed(200)
      } else {
        Reply.Body(500, body)
      }
    }

    val tail = filter.flush()
    if (tail.isNotEmpty()) {
      visible.append(tail)
      publish()
    }

    val answer = if (visible.isEmpty()) LocalParsedMessage.empty else turn.parse(visible.toString(), partial = false)
    val finishReason = finishReasonFor(finish, filter.triggered, answer)

    if (!stream) return Reply.Body(200, completionJson(id, created, request.model, answer, finishReason))

    emit(chunk(id, created, request.model, JSONObject(), finishReason))
    emit("[DONE]")
    return Reply.Streamed(200)
  }

  private companion object {
    const val OWNERSHIP = "agentisco-local"

    /** Maps the engine's reason for stopping onto OpenAI's `finish_reason`. */
    fun finishReasonFor(
      finish: LocalFinishReason,
      stopSequence: String?,
      answer: LocalParsedMessage
    ): String = when {
      answer.toolCalls.isNotEmpty() -> "tool_calls"
      // A turn that ended on its own marker is finished, however the decoder counts it.
      stopSequence != null -> "stop"
      finish == LocalFinishReason.MAX_TOKENS || finish == LocalFinishReason.CONTEXT_FULL -> "length"
      else -> "stop"
    }

    /** One streamed change in the shape the client appends deltas from. */
    fun deltaNode(delta: LocalParseDelta): JSONObject? {
      val node = JSONObject()
      if (delta.reasoning.isNotEmpty()) node.put("reasoning_content", delta.reasoning)
      if (delta.content.isNotEmpty()) node.put("content", delta.content)
      val call = delta.toolCall
      if (call != null) {
        val opening = JSONObject()
        if (call.id.isNotEmpty()) opening.put("id", call.id).put("type", "function")
        val function = JSONObject()
        if (call.name.isNotEmpty()) function.put("name", call.name)
        if (call.argumentsJson.isNotEmpty()) function.put("arguments", call.argumentsJson)
        if (function.length() > 0) opening.put("function", function)
        if (opening.length() > 0) {
          opening.put("index", delta.toolCallIndex)
          node.put("tool_calls", JSONArray().put(opening))
        }
      }
      return node.takeIf { it.length() > 0 }
    }
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
  answer: LocalParsedMessage,
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
    parallelToolCalls = raw.optBoolean("parallel_tool_calls", false)
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
