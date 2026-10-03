package com.agentisco.local.py

import com.agentisco.local.model.LocalGenerationSettings
import com.agentisco.local.model.LocalRuntimeSettings
import com.agentisco.local.runtime.LocalEngineException
import com.agentisco.local.runtime.LocalFinishReason
import com.agentisco.local.runtime.LocalPromptTooLongException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
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

  /** What the server read out of the GGUF it just opened. */
  data class ModelInfo(
    val path: String,
    val contextSize: Int,
    val vocabularySize: Int,
    val architecture: String,
    val publishedName: String
  )

  /** One decode: the rendered prompt and everything the sampler and the template demand. */
  data class Completion(
    val prompt: String,
    val settings: LocalGenerationSettings,
    val grammar: String? = null,
    val seed: Long? = null,
    val stop: List<String> = emptyList()
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
      publishedName = model.optString("name")
    )
  }

  /**
   * Decodes [completion], handing each piece to [onPiece] as it arrives. Returning false stops
   * the run and tells the server to stop decoding too, so a cancelled turn stops costing power
   * instead of finishing a reply nobody reads.
   */
  fun complete(completion: Completion, onPiece: (String) -> Boolean): LocalFinishReason {
    val request = JSONObject()
      .put("prompt", completion.prompt)
      .put("stream", true)
      .put("max_tokens", completion.settings.maxOutputTokens)
      .put("temperature", completion.settings.temperature)
      .put("top_k", completion.settings.topK)
      .put("top_p", completion.settings.topP)
      // llama-cpp-python defaults to a min-p the native engine has no equivalent of, so a
      // model would answer differently depending on which half of the phone read it.
      .put("min_p", 0.0)
      .put("repeat_penalty", completion.settings.repeatPenalty)
    completion.grammar?.let { request.put("grammar", it) }
    completion.seed?.let { request.put("seed", it) }
    request.put("stop", org.json.JSONArray(completion.stop))

    val connection = open("POST", "/v1/completions", DECODE_TIMEOUT_MS)
    connection.doOutput = true
    try {
      connection.outputStream.use { it.write(request.toString().toByteArray(StandardCharsets.UTF_8)) }
      // Only a failure has a body worth reading in one go. A decode that is going well is read
      // event by event below, because the first words have to reach the screen while the model
      // is still writing the last ones — draining the response here would wait for the turn.
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
          choice.optString("text").takeIf { it.isNotEmpty() }?.let { piece ->
            if (!onPiece(piece)) stoppedByCaller = true
          }
          when (choice.optString("finish_reason")) {
            "stop" -> finish = LocalFinishReason.END_OF_SEQUENCE
            "length" -> finish = LocalFinishReason.MAX_TOKENS
            "abort" -> finish = LocalFinishReason.ABORTED
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
