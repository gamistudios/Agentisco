package com.agentisco.local.runtime

import com.agentisco.local.model.LocalGenerationSettings
import com.agentisco.local.model.LocalRuntimeSettings

/**
 * The engine contract the rest of the app talks to.
 *
 * Nothing above this line knows about GGUF handles, JNI, llama.cpp or sampling
 * order; it knows a model can be opened, asked to produce text piece by piece,
 * stopped, and closed. That is also what makes the runtime testable on the JVM:
 * a fake engine satisfies this interface, so the prompt renderer, the tool-call
 * normalizer and the OpenAI-compatible server all run their real code in unit
 * tests.
 */
interface LocalModelEngine {

  /** False when this build or platform has no inference runtime at all. */
  val isAvailable: Boolean

  /** CPU threads the machine has, so a "use all" setting can show a real number. */
  fun systemThreads(): Int

  /**
   * Opens [path] with [runtime] settings. Throws [LocalEngineException] with a reason
   * the user can act on when the file will not load or the memory is not there.
   */
  fun load(path: String, runtime: LocalRuntimeSettings): LoadedLocalModel

  /** Releases whatever the backend holds globally. Safe to call once at teardown. */
  fun shutdown()
}

/** An open model: its real limits, and the ability to generate from them. */
interface LoadedLocalModel : AutoCloseable {

  val info: LoadedModelInfo

  /**
   * Generates from [request.prompt], handing each piece to [onPiece]. Returning false
   * from [onPiece] stops the run — that is how a stop sequence, a cancelled request
   * or a closed client reaches the engine.
   */
  fun generate(request: LocalGenerationRequest, onPiece: (String) -> Boolean): LocalFinishReason

  /** Stops a generation running on another thread. */
  fun abort()
}

/** Why a generation ended, in terms the API layer can map to a finish_reason. */
enum class LocalFinishReason {
  END_OF_SEQUENCE,
  MAX_TOKENS,
  STOPPED,
  CONTEXT_FULL,
  ABORTED;

  companion object {
    /** The engine's own finish codes, from `llama_jni.cpp`. */
    fun fromNativeCode(code: Int): LocalFinishReason? = when (code) {
      0 -> END_OF_SEQUENCE
      1 -> MAX_TOKENS
      2 -> STOPPED
      3 -> ABORTED
      4 -> CONTEXT_FULL
      else -> null
    }
  }
}

/** What a loaded model reports about itself — read from the file, never assumed. */
data class LoadedModelInfo(
  val publishedName: String,
  /** `general.architecture`, e.g. "lfm2". */
  val architecture: String,
  /** The chat template this model ships with, empty when it has none. */
  val chatTemplate: String,
  val eosToken: String,
  val vocabSize: Int,
  /** Context the engine actually created, which may be smaller than requested. */
  val contextSize: Int,
  /** Context the model was trained with, the ceiling a user may set. */
  val trainedContextSize: Int,
  val supportsGrammar: Boolean
)

/** One generation: rendered prompt plus the sampling knobs for this request. */
data class LocalGenerationRequest(
  val prompt: String,
  val settings: LocalGenerationSettings,
  /** GBNF grammar constraining the output, when the request needs a shaped answer. */
  val grammar: String? = null,
  val seed: Long? = null
)

/** An engine failure worth showing the user, as opposed to a stack trace. */
class LocalEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)
