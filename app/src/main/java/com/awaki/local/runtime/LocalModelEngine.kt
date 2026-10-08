package com.awaki.local.runtime

import com.awaki.local.model.LocalGenerationSettings
import com.awaki.local.model.LocalRuntimeSettings

/**
 * The engine contract the rest of the app talks to.
 *
 * One method, `chat`, because that is what a model turn actually is: a transcript goes in, an
 * answer comes out in pieces. Rendering the transcript with the model's own chat template,
 * decoding it and splitting the answer into prose, reasoning and tool calls all happen on the
 * other side of this line, in the llama.cpp build the app compiles, next door to the GGUF file
 * whose template says how the model must be spoken to. Nothing here knows jinja, sampling order
 * or which of the engine's CPU kernels the device picked.
 *
 * That is also what keeps this testable on the JVM: a fake engine satisfies the interface, so
 * the routing, the tool normalizer and the OpenAI-compatible server all run their real code in
 * unit tests.
 */
interface LocalModelEngine {

  /** False when this build has no way to run a model at all; [unavailableReason] says why. */
  val isAvailable: Boolean

  /** Why a model cannot run right now, phrased for the user rather than for the log. */
  val unavailableReason: String

  /**
   * What this device and this build of the engine are.
   *
   * Describes the machine, not the resident model, so it answers with nothing loaded: the faults it
   * exists to explain — a turn that never ends, a model decoding at walking pace — are build and
   * silicon facts, and a phone that cannot be plugged into a computer still has a settings screen.
   */
  fun diagnostics(): LocalEngineDiagnostics = LocalEngineDiagnostics.noEngine

  /**
   * Makes [path] the resident model with [runtime] settings, and returns its handle.
   *
   * Throws [LocalEngineException] with a reason the user can act on when the file will not
   * load, the memory is not there, or the environment it runs in is not set up yet.
   */
  fun load(path: String, runtime: LocalRuntimeSettings): LoadedLocalModel

  /** Releases whatever the backend holds globally: the process, the port, the weights. */
  fun shutdown()
}

/** An open model: what its file turned out to be able to do, and the turns it can answer. */
interface LoadedLocalModel : AutoCloseable {

  val info: LoadedModelInfo

  /**
   * What this file's own chat template can carry. Read from the model, so a model that
   * claims tools in a catalog but renders them nowhere reports the truth instead.
   */
  fun capabilities(): LocalTemplateCapabilities

  /**
   * Decodes one turn, handing every piece of the answer to [onDelta] as it arrives.
   *
   * Returning false stops the run, which is how a cancelled request or a client that went away
   * reaches the model: the decode stops rather than finishing a reply nobody reads.
   */
  fun chat(request: LocalChatRequest, onDelta: (LocalAnswerDelta) -> Boolean): LocalFinishReason

  /** Stops the generation in flight from another thread. Safe when nothing is running. */
  fun abort()
}

/**
 * One message in the shape a chat template expects: the OpenAI wire format, because that is
 * what the templates, the agent loop and the loopback server all speak.
 */
data class LocalChatMessage(
  /** `system`, `user`, `assistant` or `tool`. */
  val role: String,
  val content: String = "",
  val toolCalls: List<LocalToolCall> = emptyList(),
  /** Set on a `tool` message: the call this result answers. */
  val toolCallId: String? = null,
  /** Reasoning the model published separately from its answer, carried back verbatim. */
  val reasoningContent: String? = null
)

/** A function the model may call, as the template has to describe it. */
data class LocalChatTool(
  val name: String,
  val description: String,
  val parametersJsonSchema: String
)

/** A call the model asked for. [argumentsJson] is the raw JSON text, never a parsed object. */
data class LocalToolCall(
  val id: String,
  val name: String,
  val argumentsJson: String
)

/** Everything the runtime needs to render and answer one request. */
data class LocalChatInputs(
  val messages: List<LocalChatMessage>,
  val tools: List<LocalChatTool> = emptyList(),
  /** `auto`, `required` or `none`, exactly as the OpenAI field means it. */
  val toolChoice: String = "auto",
  val enableThinking: Boolean = true,
  val parallelToolCalls: Boolean = false,
  /** Extra sequences that end the turn, on top of the ones the template itself writes. */
  val stop: List<String> = emptyList()
)

/** One turn: the transcript, the sampling numbers, and the seed when the caller fixed one. */
data class LocalChatRequest(
  val inputs: LocalChatInputs,
  val settings: LocalGenerationSettings,
  val seed: Long? = null
)

/** The truth about a resident model's template, as the runtime reports it. */
data class LocalTemplateCapabilities(
  /** False when the model cannot be used for chat at all; [reason] says why. */
  val available: Boolean,
  /**
   * False when the file ships no template and the runtime's generic ChatML one is being used
   * instead — a model can still answer, but its formatting is a guess, not the file's choice.
   */
  val usesOwnTemplate: Boolean,
  /** Both halves of it: the template can describe tools and its answer can be read back. */
  val supportsTools: Boolean,
  val supportsParallelToolCalls: Boolean,
  /** The template has somewhere to put thinking, so reasoning can be surfaced. */
  val supportsThinking: Boolean,
  val supportsSystemMessage: Boolean,
  val reason: String = ""
) {
  companion object {
    val unavailable = LocalTemplateCapabilities(
      available = false,
      usesOwnTemplate = false,
      supportsTools = false,
      supportsParallelToolCalls = false,
      supportsThinking = false,
      supportsSystemMessage = false,
      reason = "No model is loaded"
    )
  }
}

/**
 * One streamed piece of an answer, already separated by the runtime: text, reasoning, or a
 * fragment of a tool call. A call arrives as its name and then its arguments growing token by
 * token, which is what a client assembling one expects.
 */
data class LocalAnswerDelta(
  val content: String = "",
  val reasoning: String = "",
  /** Index of the call [toolCall] extends, or -1 when this delta is text. */
  val toolCallIndex: Int = -1,
  val toolCall: LocalToolCall? = null
) {
  val isEmpty: Boolean get() = content.isEmpty() && reasoning.isEmpty() && toolCall == null
}

/** Why a generation ended, in terms the API layer can map onto a finish_reason. */
enum class LocalFinishReason {
  END_OF_SEQUENCE,
  MAX_TOKENS,
  CONTEXT_FULL,
  ABORTED;

  companion object {
    /** The word the model server uses, mapped onto this enum. */
    fun fromServerName(name: String): LocalFinishReason = when (name) {
      "length" -> MAX_TOKENS
      "abort" -> ABORTED
      "tool_calls", "stop" -> END_OF_SEQUENCE
      else -> END_OF_SEQUENCE
    }
  }
}

/** What a loaded model reports about itself — read from the file, never assumed. */
data class LoadedModelInfo(
  val publishedName: String,
  /** `general.architecture`, e.g. "lfm2". */
  val architecture: String,
  val vocabSize: Int,
  /** Context the runtime actually created, which may be smaller than requested. */
  val contextSize: Int
)

/**
 * The facts a slow or silent turn is judged by, read from inside the engine.
 *
 * Each one is a different explanation for the same experience, and the app could not previously
 * tell any of them apart: [compiledOptimized] false means the packaged code is an order of
 * magnitude off and the model will look broken rather than slow; [backends] holding only a
 * baseline kernel set means the phone was handed the conservative kernels it is several times
 * slower with; [pooledWorkers] false means every token pays for building a thread pool; a low
 * [availableMb] means the next context size asked for is the one that will be refused.
 *
 * The thread counts are what this device's cores plan to, before any per-model override: a decode
 * step ends in a barrier all its workers wait at, so the plan keeps the little cores out of it and
 * lets prefill use them, which is a different number for each kind of work.
 */
data class LocalEngineDiagnostics(
  val enginePresent: Boolean,
  val compiledOptimized: Boolean,
  val coresSeen: Int,
  val decodeThreads: Int,
  val batchThreads: Int,
  val pooledWorkers: Boolean,
  /** Memory the kernel could still hand a model, or null when this device reports nothing. */
  val availableMb: Long?,
  /** The CPU kernel sets shipped in this build, in the order the device scored them. */
  val backends: List<String> = emptyList(),
  val systemInfo: String = "",
  /** Why nothing is reported, when the engine itself is not there. */
  val reason: String = ""
) {
  /** Lines for the settings screen, each one a fact a reader can act on. */
  fun lines(): List<String> = when {
    !enginePresent -> listOf(reason)
    else -> buildList {
      add(
        if (compiledOptimized) "Engine build: optimised"
        else "Engine build: UNOPTIMISED - this APK runs the model several times slower than it should"
      )
      add("Cores seen: $coresSeen - $decodeThreads decoding, $batchThreads prefiling")
      add(if (pooledWorkers) "Worker threads: reused between steps" else "Worker threads: rebuilt for every step")
      availableMb?.let { add("Memory available: $it MB") }
      // One line per kernel set the device accepted, because the fault this shows is a missing
      // one: a build whose modern variants are refused answers a fast phone with baseline NEON.
      backends.forEach { add("Kernel set: $it") }
    }
  }

  companion object {
    val noEngine = LocalEngineDiagnostics(
      enginePresent = false,
      compiledOptimized = false,
      coresSeen = 0,
      decodeThreads = 0,
      batchThreads = 0,
      pooledWorkers = false,
      availableMb = null,
      reason = "This build has no on-device inference engine"
    )
  }
}

/** An engine failure worth showing the user, as opposed to a stack trace. */
open class LocalEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The prompt is larger than the context configured for this model.
 *
 * Its own type because it is the one failure meaning nothing is wrong with the model, the
 * runtime or the device: the request needs a different setting or a shorter conversation, and
 * retrying changes neither. The resident model stays resident — it never began decoding — and
 * the loopback server answers 400, which the agent loop treats as terminal.
 */
class LocalPromptTooLongException(message: String) : LocalEngineException(message)
