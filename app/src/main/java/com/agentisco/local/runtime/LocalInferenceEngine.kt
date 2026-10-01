package com.agentisco.local.runtime

import com.agentisco.data.repository.LocalModelRepository
import com.agentisco.local.model.LocalModel
import com.agentisco.local.model.LocalRuntimeSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the one model that is allowed to be resident.
 *
 * A 230M-parameter quant costs a few hundred MB of weights and KV cache on a phone
 * that is already running a terminal, a file watcher and the chat it is answering in.
 * So this holds at most one loaded model, loads it on the first request that needs it
 * rather than at startup, and swaps it out when a different model is asked for or a
 * runtime setting changes — those are read at load time, which is why the settings
 * screen says a reload follows.
 *
 * Every native call runs on a background dispatcher: loading takes seconds and a
 * stalled main thread is an ANR with the app's name on it.
 *
 * Integrity is checked before a model is opened. After that, failures are reported
 * and the model stays installed: a device that was short on memory for one context
 * size is not a device whose download has gone bad, and wiping the install would push
 * the user into re-fetching 142 MB to fix a setting.
 */
class LocalInferenceEngine(
  private val repository: LocalModelRepository,
  private val engine: LocalModelEngine = LlamaEngine(),
  private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {

  private class Resident(
    val modelId: String,
    /** The settings the model was opened with, so a change forces a reload. */
    val runtime: LocalRuntimeSettings,
    val session: LoadedLocalModel
  )

  /** Guards [resident]; held only across load and unload, never across a generation. */
  private val stateMutex = Mutex()

  /** One decode at a time: the KV cache belongs to the sequence currently running. */
  private val generateMutex = Mutex()

  @Volatile
  private var resident: Resident? = null

  /** The model currently in memory, or null when nothing is loaded. */
  val loadedModelId: String? get() = resident?.modelId

  val isAvailable: Boolean get() = engine.isAvailable

  /** Why this build cannot run models, phrased for the user rather than the log. */
  val unavailableReason: String?
    get() = if (engine.isAvailable) null else "This build has no on-device inference engine"

  /** CPU threads the device has, so "use all" can show the number it means. */
  fun systemThreads(): Int = engine.systemThreads()

  /**
   * Generates [prompt] using the model's saved generation settings, streaming pieces
   * to [onPiece]. Returning false from [onPiece] stops the run, which is how a client
   * that went away ends up as a cancelled completion rather than a wasted battery.
   */
  suspend fun generate(
    model: LocalModel,
    prompt: String,
    grammar: String? = null,
    seed: Long? = null,
    onPiece: (String) -> Boolean
  ): LocalFinishReason {
    val session = open(model)
    val settings = repository.configuration(model.id).generation
    return withContext(dispatcher) {
      generateMutex.withLock {
        try {
          session.generate(LocalGenerationRequest(prompt, settings, grammar, seed), onPiece)
        } catch (e: LocalEngineException) {
          // A mid-run engine failure leaves the context in an unknown state; the next
          // request should not inherit it.
          unload(model.id)
          throw e
        }
      }
    }
  }

  /** Metadata of the resident model, or null while nothing is loaded. */
  fun loadedInfo(): LoadedModelInfo? = resident?.session?.info

  /** Stops the generation in flight; safe from any thread, including the UI. */
  fun stop() {
    resident?.session?.abort()
  }

  /** Unloads whatever is resident, giving its memory back. */
  suspend fun release() = withContext(dispatcher) {
    stateMutex.withLock { unloadLocked() }
  }

  /** Returns the resident session, loading and verifying the model first. */
  private suspend fun open(model: LocalModel): LoadedLocalModel {
    val wanted = repository.configuration(model.id).runtime
    resident?.takeIf { it.modelId == model.id && it.runtime == wanted }?.let { return it.session }

    if (!engine.isAvailable) throw LocalEngineException(unavailableReason ?: "No engine")

    return stateMutex.withLock {
      resident?.takeIf { it.modelId == model.id && it.runtime == wanted }?.let { return@withLock it.session }

      // Cheap checks first, and the expensive one only when the model is actually
      // about to be opened: hashing 142 MB per request would be slower than the
      // inference itself.
      val file = repository.fileFor(model)
        ?: throw LocalEngineException("${model.name} is not installed")
      if (!repository.verifyInstalled(model.id)) {
        throw LocalEngineException(
          "${model.name} no longer matches its checksum. Reinstall it before using it offline."
        )
      }

      unloadLocked()
      val threads = wanted.threadCount.takeIf { it > 0 } ?: engine.systemThreads()
      val session = engine.load(file.absolutePath, wanted.copy(threadCount = threads))
      resident = Resident(model.id, wanted, session)
      return@withLock session
    }
  }

  private suspend fun unload(modelId: String) = withContext(dispatcher) {
    stateMutex.withLock {
      if (resident?.modelId == modelId) unloadLocked()
    }
  }

  private fun unloadLocked() {
    resident?.let { runCatching { it.session.close() } }
    resident = null
  }
}
