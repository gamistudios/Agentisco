package com.agentisco.local.runtime

import com.agentisco.local.model.LocalGenerationSettings
import com.agentisco.local.model.LocalRuntimeSettings

/**
 * The engine contract the rest of the app talks to.
 *
 * Nothing above this line knows about GGUF handles, JNI, llama.cpp or sampling order;
 * it knows a model can be opened, asked what its own template can do, rendered, asked
 * to produce text piece by piece, stopped, and closed. That is also what makes the
 * runtime testable on the JVM: a fake engine satisfies this interface, so the request
 * routing, the tool-call normalizer and the OpenAI-compatible server all run their real
 * code in unit tests while the template rendering itself stays llama.cpp's problem.
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
   * What this file's own chat template can do. Read from the model, so a model that
   * claims tools in a catalog but renders them nowhere reports the truth instead.
   */
  fun templateCapabilities(): LocalTemplateCapabilities

  /**
   * Renders [inputs] with this model's template and returns the turn: prompt, grammar,
   * stop sequences, and the parser that reads the answer back. Closes like anything
   * else, and only after the generation using it is done.
   */
  fun openTurn(inputs: LocalChatInputs): LocalChatTurn

  /**
   * Generates from [request.prompt], handing each piece to [onPiece]. Returning false
   * from [onPiece] stops the run — that is how a stop sequence, a cancelled request
   * or a closed client reaches the engine.
   */
  fun generate(request: LocalGenerationRequest, onPiece: (String) -> Boolean): LocalFinishReason

  /** Stops a generation running on another thread. */
  fun abort()
}

/**
 * One message in the shape a chat template expects: the OpenAI wire format, because
 * that is the format the templates, the parsers and the rest of Agentisco all speak.
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

/** Everything a template needs to render one request. */
data class LocalChatInputs(
  val messages: List<LocalChatMessage>,
  val tools: List<LocalChatTool> = emptyList(),
  /** `auto`, `required` or `none`, exactly as the OpenAI field means it. */
  val toolChoice: String = "auto",
  /** False when the caller wants the history rendered without an assistant turn. */
  val addGenerationPrompt: Boolean = true,
  val enableThinking: Boolean = true,
  val parallelToolCalls: Boolean = false
)

/** The truth about a resident model's template, as the engine reports it. */
data class LocalTemplateCapabilities(
  /** False when the model cannot be used for chat at all; [reason] says why. */
  val available: Boolean,
  /**
   * False when the file ships no template and llama.cpp's generic ChatML one is being
   * used instead — a model can then still answer, but its formatting is a guess.
   */
  val usesOwnTemplate: Boolean,
  /** Both halves of it: the template can describe tools and parse them back. */
  val supportsTools: Boolean,
  val supportsParallelToolCalls: Boolean,
  /** The template has somewhere to put thinking, so reasoning can be surfaced. */
  val supportsThinking: Boolean,
  val supportsSystemMessage: Boolean,
  val supportsTypedContent: Boolean,
  /** Every capability flag the template declared, for diagnostics. */
  val caps: Map<String, Boolean> = emptyMap(),
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
      supportsTypedContent = false,
      reason = "No model is loaded"
    )
  }
}

/**
 * One rendered request. [prompt] and [grammar] are what the decoder runs with;
 * [parse] reads the model's answer back into content, reasoning and tool calls using
 * the parser this template was analyzed into, so no dialect is hard-coded anywhere.
 */
interface LocalChatTurn : AutoCloseable {

  val prompt: String
  /** Grammar constraining the output, or null when the template needs none. */
  val grammar: String?
  /** Sequences that end the turn, as this template writes them. */
  val stopSequences: List<String>
  /** Name of the format the template was recognized as, for logs and errors. */
  val format: String
  /** True when the template rendered tools, so an answer may contain calls. */
  val expectsToolCalls: Boolean
  val supportsThinking: Boolean

  /**
   * Parses everything generated so far. [partial] is true while the model is still
   * writing, which lets a half-open tool call stream instead of failing to parse.
   */
  fun parse(text: String, partial: Boolean): LocalParsedMessage
}

/** The answer a turn produced, split the way the OpenAI format splits it. */
data class LocalParsedMessage(
  val content: String,
  val reasoning: String,
  val toolCalls: List<LocalToolCall>,
  /** What [parse] added since its last call on this turn, for streaming. */
  val deltas: List<LocalParseDelta>,
  /** True when the answer did not fit the model's own format at all. */
  val rejected: Boolean
) {
  companion object {
    val empty = LocalParsedMessage("", "", emptyList(), emptyList(), false)
  }
}

/** One streamed change: text, reasoning, or a piece of a tool call. */
data class LocalParseDelta(
  val content: String,
  val reasoning: String,
  /** Index of the call [toolCall] extends, or -1 when this delta is text. */
  val toolCallIndex: Int,
  val toolCall: LocalToolCall?
)

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
open class LocalEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The prompt is larger than the context configured for this model.
 *
 * Its own type because it is the one engine failure meaning nothing is wrong with the
 * model, the engine or the device: the request needs a different setting or a shorter
 * conversation, and retrying changes neither. The resident model stays resident — it
 * never began decoding — and the loopback server answers 400, which the agent loop
 * treats as terminal.
 */
class LocalPromptTooLongException(message: String) : LocalEngineException(message)
