package com.agentisco.local.runtime

import com.agentisco.local.model.LocalRuntimeSettings
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * The production engine: [LocalModelEngine] over the bundled llama.cpp build.
 *
 * It adds nothing to the native loop beyond translation — handles in, finish codes
 * out, UTF-8 decoded at the boundary — and turns the engine's terse failures into
 * [LocalEngineException] messages a user can act on, because "Failed to create
 * context" is not a thing to show someone who tapped Install on a model.
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

  private fun describeFailure(api: LlamaNative, action: String): String {
    val detail = api.nativeLastError().toStringUtf8().trim()
    return if (detail.isEmpty()) action else "$action: $detail"
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

  /** One open model. Usable by one generation at a time, which the manager enforces. */
  private class LlamaSession(
    private val api: LlamaNative,
    private val handle: Long,
    override val info: LoadedModelInfo
  ) : LoadedLocalModel {

    private var closed = false

    override fun generate(request: LocalGenerationRequest, onPiece: (String) -> Boolean): LocalFinishReason {
      check(!closed) { "The model has been unloaded" }

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
    }

    override fun abort() {
      if (!closed) api.nativeAbort(handle)
    }

    override fun close() {
      if (closed) return
      closed = true
      api.nativeUnload(handle)
    }

    private fun lastError(): String = api.nativeLastError().toStringUtf8().trim().ifEmpty { "unknown error" }

    private fun ByteArray.toStringUtf8(): String = String(this, StandardCharsets.UTF_8)
  }
}
