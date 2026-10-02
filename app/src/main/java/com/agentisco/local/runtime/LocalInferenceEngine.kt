package com.agentisco.local.runtime

import com.agentisco.data.repository.LocalModelRepository
import com.agentisco.local.model.LocalGenerationSettings
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
    val session: LoadedLocalModel,
    /** Read once at load: answering what the template can do must not touch the handle. */
    val capabilities: LocalTemplateCapabilities
  )

  /**
   * Every native call — load, unload, render, decode — holds this.
   *
   * One decode at a time because the KV cache belongs to the sequence currently
   * running, and one load/unload at a time for the same reason in reverse: a swap that
   * freed the handle a decode is writing through is a crash with no stack trace worth
   * reading. Rendering waits too, since it reads the template compiled at load.
   */
  private val engineMutex = Mutex()

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
   * Generates [prompt] using the model's saved generation settings — or [settings] when
   * a caller passes them, which is how one request can ask for a different temperature
   * without editing the model's configuration — streaming pieces to [onPiece].
   * Returning false from [onPiece] stops the run, which is how a client that went away
   * ends up as a cancelled completion rather than a wasted battery.
   */
  suspend fun generate(
    model: LocalModel,
    prompt: String,
    grammar: String? = null,
    seed: Long? = null,
    settings: LocalGenerationSettings? = null,
    onPiece: (String) -> Boolean
  ): LocalFinishReason {
    return withContext(dispatcher) {
      engineMutex.withLock {
        val resident = openLocked(model)
        val generation = settings ?: repository.configuration(model.id).generation
        try {
          resident.session.generate(LocalGenerationRequest(prompt, generation, grammar, seed), onPiece)
        } catch (e: LocalEngineException) {
          // A mid-run engine failure leaves the context in an unknown state; the next
          // request should not inherit it. A prompt that was simply too long is the one
          // case where nothing was decoded, so the model is worth keeping resident.
          if (e !is LocalPromptTooLongException) unloadLocked()
          throw e
        }
      }
    }
  }

  /**
   * Renders a request with the model's own chat template. The caller owns the turn and
   * must close it; its prompt, grammar and stop sequences are what [generate] runs with,
   * and its [LocalChatTurn.parse] is what reads the answer back.
   */
  suspend fun openTurn(model: LocalModel, inputs: LocalChatInputs): LocalChatTurn = withContext(dispatcher) {
    engineMutex.withLock { openLocked(model).session.openTurn(inputs) }
  }

  /** What this model's template can do, loading the model if nothing like it is resident. */
  suspend fun capabilities(model: LocalModel): LocalTemplateCapabilities = withContext(dispatcher) {
    engineMutex.withLock { openLocked(model).capabilities }
  }

  /** Capabilities of whatever is resident right now, without touching the engine. */
  fun loadedCapabilities(): LocalTemplateCapabilities? = resident?.capabilities

  /** Metadata of the resident model, or null while nothing is loaded. */
  fun loadedInfo(): LoadedModelInfo? = resident?.session?.info

  /** Stops the generation in flight; safe from any thread, including the UI. */
  fun stop() {
    resident?.session?.abort()
  }

  /** Unloads whatever is resident, giving its memory back. */
  suspend fun release() = withContext(dispatcher) {
    engineMutex.withLock { unloadLocked() }
  }

  /** Returns the resident session, loading and verifying the model first. Holds [engineMutex]. */
  private suspend fun openLocked(model: LocalModel): Resident {
    val wanted = repository.configuration(model.id).runtime
    resident?.takeIf { it.modelId == model.id && it.runtime == wanted }?.let { return it }

    if (!engine.isAvailable) throw LocalEngineException(unavailableReason ?: "No engine")

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
    // Read before the model is ever asked to answer: a file whose template cannot be
    // compiled is a model the user should hear about now, not mid-conversation.
    val residentNow = Resident(model.id, wanted, session, session.templateCapabilities())
    resident = residentNow
    return residentNow
  }

  private fun unloadLocked() {
    resident?.let { runCatching { it.session.close() } }
    resident = null
  }
}
