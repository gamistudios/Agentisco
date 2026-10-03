package com.agentisco.local.py

import com.agentisco.local.model.LocalRuntimeSettings
import com.agentisco.local.runtime.LocalAnswerDelta
import com.agentisco.local.runtime.LocalChatMessage
import com.agentisco.local.runtime.LocalChatRequest
import com.agentisco.local.runtime.LocalChatTool
import com.agentisco.local.runtime.LocalEngineException
import com.agentisco.local.runtime.LocalFinishReason
import com.agentisco.local.runtime.LocalPromptTooLongException
import com.agentisco.local.runtime.LocalTemplateCapabilities
import com.agentisco.local.runtime.LocalToolCall
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/**
 * The client half of [ServerScript]: the same OpenAI-shaped calls, read from the Android side.
 *
 * Every method blocks, which is deliberate. [com.agentisco.local.runtime.LocalModelEngine] is a
 * blocking contract, the HTTP call *is* the engine call, and a second coroutine layer on top of
 * a socket that already has to be drained token by token would only add a place for a turn to
 * get stuck.
 *
 * There is no retry here. A turn that dies halfway is a partially decoded answer; sending it
 * again would pay for a minute of CPU to produce a different sentence, and the phone would
 * still be warm.
 */
class PythonModelClient(private val port: Int, private val token: String) {

  companion object {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val HEALTH_TIMEOUT_MS = 1_500

    /** Loading reads hundreds of megabytes off slow storage; it is measured in minutes. */
    private const val LOAD_TIMEOUT_MS = 10 * 60 * 1_000

    /**
     * A decode on a phone's CPU can be silent between tokens for a whole prefill, so a read
     * timeout here would cancel the requests most worth waiting for. The caller stops it
     * instead, by cancelling the turn or the process.
     */
    private const val DECODE_TIMEOUT_MS = 0
  }

  /** What `/health` says: enough to decide whether the server is usable and busy. */
  data class Health(
    val protocol: Int,
    val loaded: Boolean,
    val busy: Boolean,
    val pid: Int,
    /** Resident memory of the server, in MB — the number that explains an out-of-memory load. */
    val rssMb: Double
  )

  /** What the server read out of the GGUF it just opened, including its template's abilities. */
  data class ModelInfo(
    val path: String,
    val contextSize: Int,
    val vocabularySize: Int,
    val architecture: String,
    val publishedName: String,
    val capabilities: LocalTemplateCapabilities
  )

  /** Null when nothing usable answered: not an error, the server may simply not be up yet. */
  fun health(): Health? = runCatching {
    val body = JSONObject(call("GET", "/health", null, HEALTH_TIMEOUT_MS).second)
    val stats = body.optJSONObject("stats")
    Health(
      protocol = body.optInt("protocol"),
      loaded = body.optBoolean("loaded"),
      busy = body.optBoolean("busy"),
      pid = body.optInt("pid"),
      rssMb = stats?.optDouble("rss_mb") ?: 0.0
    )
  }.getOrNull()

  /**
   * Waits for the first answer, giving up as soon as [alive] says the process is gone —
   * proot failing to start is otherwise indistinguishable from a slow start.
   */
  fun awaitHealthy(timeoutMs: Long, alive: () -> Boolean = { true }): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (!alive()) return false
      health()?.let { return true }
      try {
        Thread.sleep(200)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        return false
      }
    }
    return false
  }

  /** Opens [modelPath] in the server, replacing whatever it held. */
  fun load(modelPath: String, runtime: LocalRuntimeSettings): ModelInfo {
    val request = JSONObject()
      .put("model_path", modelPath)
      .put("n_ctx", runtime.contextSize)
      .put("n_batch", runtime.batchSize.coerceAtLeast(1))
    if (runtime.threadCount > 0) request.put("n_threads", runtime.threadCount)
    val body = JSONObject(post("/v1/load", request, LOAD_TIMEOUT_MS))
    if (!body.optBoolean("ok")) throw LocalEngineException("The model server refused to load $modelPath")
    val model = body.optJSONObject("model") ?: JSONObject()
    return ModelInfo(
      path = model.optString("path"),
      contextSize = model.optInt("n_ctx"),
      vocabularySize = model.optInt("n_vocab"),
      architecture = model.optString("architecture"),
      publishedName = model.optString("name"),
      capabilities = capabilitiesOf(model.optJSONObject("chat"))
    )
  }

  /**
   * What the server's preflight turned up in this file's own template.
   *
   * Absent means the server on the other end predates the chat API, which is a script written
   * by an older app still sitting in the model directory: saying so beats rendering a
   * conversation with a runtime that cannot tell the user what the model can carry.
   */
  private fun capabilitiesOf(chat: JSONObject?): LocalTemplateCapabilities {
    if (chat == null) {
      return LocalTemplateCapabilities.unavailable.copy(
        reason = "The model server on this device is older than the app. Re-run Setup environment."
      )
    }
    return LocalTemplateCapabilities(
      available = true,
      usesOwnTemplate = chat.optBoolean("usesOwnTemplate"),
      supportsTools = chat.optBoolean("supportsTools"),
      supportsParallelToolCalls = chat.optBoolean("supportsParallelToolCalls"),
      supportsThinking = chat.optBoolean("supportsThinking"),
      supportsSystemMessage = chat.optBoolean("supportsSystemMessage"),
      reason = chat.optString("reason")
    )
  }

  /**
   * Decodes one turn, handing every piece of the answer to [onDelta] as it arrives.
   *
   * One request per turn, because the server is the half that owns the model's chat template: it
   * renders the transcript, decodes it and reads the answer back into content, reasoning and
   * tool calls, all next door to the file those markers come from.
   *
   * Returning false from [onDelta] stops the run and tells the server to stop decoding too, so a
   * cancelled turn stops costing power instead of finishing a reply nobody reads.
   */
  fun chat(request: LocalChatRequest, onDelta: (LocalAnswerDelta) -> Boolean): LocalFinishReason {
    val connection = open("POST", "/v1/chat/completions", DECODE_TIMEOUT_MS)
    connection.doOutput = true
    try {
      connection.outputStream.use {
        it.write(requestJson(request).toString().toByteArray(StandardCharsets.UTF_8))
      }
      // Only a failure has a body worth reading in one go. A decode that is going well is read
      // event by event below, because the first words have to reach the screen while the model
      // is still writing the last ones - draining the response here would wait for the turn.
      val code = connection.responseCode
      if (code >= 400) throw failure(code, drain(connection))
      var finish: LocalFinishReason? = null
      var stoppedByCaller = false
      connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { stream ->
        while (finish == null) {
          val line = stream.readLine() ?: break
          if (!line.startsWith("data: ")) continue
          val payload = line.substring("data: ".length)
          if (payload == "[DONE]") break
          val chunk = try {
            JSONObject(payload)
          } catch (e: org.json.JSONException) {
            throw LocalEngineException(
              "The model server answered in a shape this app does not understand. " +
                "Re-run Setup environment to replace it."
            )
          }
          chunk.optJSONObject("error")?.let { error ->
            // Headers were already 200, so a mid-stream failure travels inside the stream.
            throw LocalEngineException(error.optString("message").ifBlank { "The model server failed" })
          }
          val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
          for (delta in choice.optJSONObject("delta")?.deltaList() ?: emptyList<LocalAnswerDelta>()) {
            // A caller that has stopped wanting the answer stops here, not after the rest of
            // this chunk's pieces have been handed over too.
            if (!onDelta(delta)) {
              stoppedByCaller = true
              break
            }
          }
          choice.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }?.let {
            finish = LocalFinishReason.fromServerName(it)
          }
          if (stoppedByCaller) {
            finish = LocalFinishReason.ABORTED
            break
          }
        }
      }
      // Asked after the response is closed, never while it is still open: a server that is
      // blocked writing tokens into a socket nobody reads cannot also answer a second request,
      // so asking first would stall the stop until a timeout rescued it.
      if (stoppedByCaller) abort()
      return finish
        ?: throw LocalEngineException(
          "The model server stopped answering in the middle of the turn."
        )
    } catch (e: IOException) {
      throw LocalEngineException("The model server stopped answering: ${e.message}", e)
    } finally {
      connection.disconnect()
    }
  }

  /**
   * The turn as the OpenAI wire format, which is the only format a model's own chat template
   * was written against.
   *
   * A tool's schema travels as the object it is, not the string it is stored in: a template
   * iterates over its properties, and printing a JSON string there would show the model an
   * escaped blob instead of a function.
   */
  private fun requestJson(request: LocalChatRequest): JSONObject {
    val inputs = request.inputs
    val settings = request.settings
    val body = JSONObject()
      .put("messages", JSONArray(inputs.messages.map { messageJson(it) }))
      .put("stream", true)
      .put("max_tokens", settings.maxOutputTokens)
      .put("temperature", settings.temperature)
      .put("top_k", settings.topK)
      .put("top_p", settings.topP)
      // llama-cpp-python defaults to a min-p the rest of the app has no setting for, so a
      // model would answer differently depending on which half of the phone read it.
      .put("min_p", 0.0)
      .put("repeat_penalty", settings.repeatPenalty)
      .put("tool_choice", inputs.toolChoice)
      .put("enable_thinking", inputs.enableThinking)
      .put("parallel_tool_calls", inputs.parallelToolCalls)
    if (inputs.tools.isNotEmpty()) {
      body.put("tools", JSONArray(inputs.tools.map { toolJson(it) }))
    }
    if (inputs.stop.isNotEmpty()) body.put("stop", JSONArray(inputs.stop))
    request.seed?.let { body.put("seed", it) }
    return body
  }

  private fun messageJson(message: LocalChatMessage): JSONObject {
    val out = JSONObject().put("role", message.role).put("content", message.content)
    if (message.toolCalls.isNotEmpty()) {
      out.put(
        "tool_calls",
        JSONArray(
          message.toolCalls.map { call ->
            JSONObject()
              .put("id", call.id)
              .put("type", "function")
              .put("function", JSONObject().put("name", call.name).put("arguments", call.argumentsJson))
          }
        )
      )
    }
    message.toolCallId?.let { out.put("tool_call_id", it) }
    message.reasoningContent?.let { out.put("reasoning_content", it) }
    return out
  }

  private fun toolJson(tool: LocalChatTool): JSONObject = JSONObject()
    .put("type", "function")
    .put(
      "function",
      JSONObject()
        .put("name", tool.name)
        .put("description", tool.description)
        .put("parameters", parseSchema(tool.parametersJsonSchema))
    )

  /** The schema as data when it parses, as text when it does not — a broken tool is not a reason to drop the rest. */
  private fun parseSchema(schema: String): Any =
    runCatching { JSONObject(schema) }.getOrNull() ?: schema

  /** Stops the decode in flight. Safe when nothing is running. */
  fun abort() {
    runCatching { post("/abort", JSONObject(), HEALTH_TIMEOUT_MS) }
  }

  /** Frees the weights without ending the process, so the next load does not pay for a start. */
  fun unload() {
    runCatching { post("/v1/unload", JSONObject(), LOAD_TIMEOUT_MS) }
  }

  private fun post(path: String, body: JSONObject, timeoutMs: Int): String =
    call("POST", path, body, timeoutMs).second

  private fun call(method: String, path: String, body: JSONObject?, timeoutMs: Int): Pair<Int, String> {
    val connection = open(method, path, timeoutMs)
    try {
      if (body != null) {
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
      }
      val response = read(connection)
      if (response.first >= 400) throw failure(response.first, response.second)
      return response
    } catch (e: IOException) {
      throw LocalEngineException("The model server did not answer $path: ${e.message}", e)
    } finally {
      connection.disconnect()
    }
  }

  private fun open(method: String, path: String, timeoutMs: Int): HttpURLConnection =
    (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
      requestMethod = method
      connectTimeout = CONNECT_TIMEOUT_MS
      readTimeout = timeoutMs
      setRequestProperty("Authorization", "Bearer $token")
      setRequestProperty("Content-Type", "application/json")
      useCaches = false
    }

  private fun read(connection: HttpURLConnection): Pair<Int, String> =
    connection.responseCode to drain(connection)

  /** The whole body of a response that is not a stream: for an error, everything the server said. */
  private fun drain(connection: HttpURLConnection): String =
    (connection.errorStream ?: connection.inputStream)
      ?.use { it.readBytes().toString(StandardCharsets.UTF_8) } ?: ""

  /** The server's own words, in the one exception type the runtime layer already reports. */
  private fun failure(code: Int, body: String): LocalEngineException {
    val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
    val message = error?.optString("message").orEmpty().ifBlank { body }.ifBlank { "HTTP $code" }
    val type = error?.optString("type").orEmpty()
    return when {
      // Nothing is wrong with the model or the phone: the request needs a bigger context or a
      // shorter conversation, and the resident model stays resident because nothing decoded.
      type == "context_length_exceeded" -> LocalPromptTooLongException(message)
      code == 401 -> LocalEngineException("The model server did not accept this app's token.")
      else -> LocalEngineException(message)
    }
  }
}

/**
 * One streamed delta, split the way the app's own tool loop reads it.
 *
 * A call arrives in pieces — first its name and id, then arguments growing token by token — and
 * each piece keeps the index it extends, so a client can tell a second call from more of the
 * first without reassembling anything.
 */
private fun JSONObject.deltaList(): List<LocalAnswerDelta> {
  val content = optString("content")
  val reasoning = optString("reasoning_content")
  val calls = optJSONArray("tool_calls")
  if (calls == null) {
    val delta = LocalAnswerDelta(content = content, reasoning = reasoning)
    return if (delta.isEmpty) emptyList() else listOf(delta)
  }
  val out = mutableListOf<LocalAnswerDelta>()
  if (content.isNotEmpty() || reasoning.isNotEmpty()) {
    out += LocalAnswerDelta(content = content, reasoning = reasoning)
  }
  for (i in 0 until calls.length()) {
    val item = calls.optJSONObject(i) ?: continue
    val function = item.optJSONObject("function") ?: JSONObject()
    out += LocalAnswerDelta(
      toolCallIndex = item.optInt("index"),
      toolCall = LocalToolCall(
        id = item.optString("id"),
        name = function.optString("name"),
        argumentsJson = function.optString("arguments")
      )
    )
  }
  return out
}
