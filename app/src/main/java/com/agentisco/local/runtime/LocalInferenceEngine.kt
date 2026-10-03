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
 * A 230M-parameter quant costs a few hundred MB of weights and KV cache on a phone that is
 * already running a terminal, a file watcher and the chat it is answering in. So this holds at
 * most one loaded model, loads it on the first request that needs it rather than at startup, and
 * swaps it out when a different model is asked for or a runtime setting changes — those are read
 * when the model is opened, which is why the settings screen says a reload follows.
 *
 * Every call runs on a background dispatcher: opening a model takes seconds, and a stalled main
 * thread is an ANR with the app's name on it.
 *
 * Integrity is checked before a model is opened. After that, failures are reported and the model
 * stays installed: a device that was short on memory for one context size is not a device whose
 * download has gone bad, and wiping the install would push the user into re-fetching 142 MB to
 * fix a setting.
 */
class LocalInferenceEngine(
  private val repository: LocalModelRepository,
  private val engine: LocalModelEngine,
  private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {

  private class Resident(
    val modelId: String,
    /** The settings the model was opened with, so a change forces a reload. */
    val runtime: LocalRuntimeSettings,
    val session: LoadedLocalModel,
    /** Read once when the model was opened: answering what a template can do must not wait. */
    val capabilities: LocalTemplateCapabilities
  )

  /**
   * Every call that touches the resident model holds this.
   *
   * One turn at a time because the KV cache belongs to the sequence currently running, and one
   * load or unload at a time for the same reason in reverse: a swap that freed the handle a
   * decode is writing through is a crash with no stack trace worth reading.
   */
  private val engineMutex = Mutex()

  @Volatile
  private var resident: Resident? = null

  /** The model currently in memory, or null when nothing is loaded. */
  val loadedModelId: String? get() = resident?.modelId

  val isAvailable: Boolean get() = engine.isAvailable

  /** Why no model can run right now, phrased for the user rather than the log. */
  val unavailableReason: String get() = engine.unavailableReason

  /** CPU threads the device has, so "use all" can show the number it means. */
  fun systemThreads(): Int = engine.systemThreads()

  /**
   * Answers one turn with [model], streaming the answer through [onDelta].
   *
   * [settings] overrides the model's saved generation numbers for this request only, which is
   * how one caller can ask for a different temperature without editing the model's
   * configuration. Returning false from [onDelta] stops the decode.
   */
  suspend fun chat(
    model: LocalModel,
    inputs: LocalChatInputs,
    settings: LocalGenerationSettings? = null,
    seed: Long? = null,
    onDelta: (LocalAnswerDelta) -> Boolean
  ): LocalFinishReason = withContext(dispatcher) {
    engineMutex.withLock {
      val resident = openLocked(model)
      val generation = settings ?: repository.configuration(model.id).generation
      try {
        resident.session.chat(LocalChatRequest(inputs, generation, seed), onDelta)
      } catch (e: LocalEngineException) {
        // A mid-turn failure leaves the context in an unknown state; the next request should
        // not inherit it. A prompt that was simply too long is the one case where nothing was
        // decoded, so the model is worth keeping resident.
        if (e !is LocalPromptTooLongException) unloadLocked()
        throw e
      }
    }
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

    if (!engine.isAvailable) throw LocalEngineException(engine.unavailableReason)

    // Cheap checks first, and the expensive one only when the model is actually about to be
    // opened: hashing 142 MB per request would be slower than the inference itself.
    val file = repository.fileFor(model)
      ?: throw LocalEngineException("${model.name} is not installed")
    if (!repository.verifyInstalled(model.id)) {
      throw LocalEngineException(
        "${model.name} no longer matches its checksum. Reinstall it before using it offline."
      )
    }

    unloadLocked()
    val session = engine.load(file.absolutePath, wanted)
    // Read before the model is ever asked to answer: a file whose template cannot carry the
    // tools the app would offer is a model the user should hear about now, not mid-conversation.
    val capabilities = try {
      session.capabilities()
    } catch (e: Throwable) {
      // A template that cannot be read is not a model worth keeping resident: left loaded, it
      // holds a few hundred megabytes for a turn that can never be answered.
      runCatching { session.close() }
      throw e
    }
    val residentNow = Resident(model.id, wanted, session, capabilities)
    resident = residentNow
    return residentNow
  }

  private fun unloadLocked() {
    resident?.let { runCatching { it.session.close() } }
    resident = null
  }
}
