package com.awaki.local.jni

/**
 * The JNI surface of the bundled engine: one Kotlin method per exported symbol, no
 * logic. Keeping the declarations this bare is what lets the rest of the runtime be
 * replaced or faked without touching native code.
 *
 * Text crosses as UTF-8 byte arrays in both directions. JNI's own string functions
 * use *modified* UTF-8, which encodes an astral character as two surrogate
 * three-byte sequences — bytes the tokenizer would read as a valid-but-wrong
 * string. Encoding in Kotlin avoids the whole class of mismatch, and it keeps the
 * prompt the model sees byte-identical to the prompt the user sent.
 *
 * The library is optional by construction: a JVM test, or a build whose `.so` is
 * missing for some ABI, reports [isAvailable] false instead of crashing the app on
 * class load. A 32-bit device is the case that is missing by design.
 *
 * Every method that answers with bytes answers with `ByteArray?`, and the null is not decoration.
 * The engine builds those arrays with `NewByteArray`, which on a phone out of heap returns nothing
 * and leaves the `OutOfMemoryError` *pending on the thread* — the JNI mistake that turns into an
 * abort of the whole process, mid-turn, with no message anywhere. So the native side describes the
 * failure, clears it, and records it as its own error; the null is what reaches this side, where a
 * non-null type would have been a lie the compiler cannot catch for a native method.
 */
class NativeLlama private constructor() {

  /**
   * Registers the CPU backends and initializes the engine.
   *
   * [nativeLibDir] is the directory this class's own libraries were extracted to:
   * the CPU kernels are separate `.so` files, so llama.cpp cannot find them without
   * being pointed at the folder, and only Android knows where it put them.
   */
  external fun nativeInit(nativeLibDir: String)

  /**
   * Records a fault in the engine's own code into [logPath] before the process dies.
   *
   * A segmentation fault never reaches the JVM's uncaught-exception handler, so without this the app
   * disappears and the Crash Log screen has nothing to show. The path is the file that screen reads.
   */
  external fun nativeInstallCrashCapture(logPath: String)

  external fun nativeBackendFree()

  /**
   * Engine handle, or 0 when the model or its context could not be created.
   *
   * [threads] of 0 leaves the counts to this device's plan, which sizes decoding and prefill
   * separately; a named count is used for both kinds of work.
   */
  external fun nativeLoadModel(path: String, contextSize: Int, threads: Int, batchSize: Int): Long

  /** The engine handle's own facts, as JSON. */
  external fun nativeModelInfo(handle: Long): ByteArray?

  /**
   * Runs one completion. Returns the engine's finish code — 0 end of sequence,
   * 1 max tokens, 2 stopped by the sink, 3 aborted, 4 context full, negative for an
   * engine error whose text [nativeLastError] holds. -5 means the request itself never
   * reached the engine, and -2 that there was nowhere to send the tokens.
   */
  external fun nativeComplete(
    handle: Long,
    prompt: ByteArray,
    temperature: Float,
    topK: Int,
    topP: Float,
    repeatPenalty: Float,
    maxTokens: Int,
    seed: Long,
    grammar: ByteArray?,
    sink: TokenSink
  ): Int

  external fun nativeAbort(handle: Long)

  external fun nativeResetAbort(handle: Long)

  external fun nativeUnload(handle: Long)

  /** Why the last step failed — or null when even *this* could not be handed over. */
  external fun nativeLastError(): ByteArray?

  /**
   * What this device and this build are: optimisation state, kernel sets, core plan, free memory.
   *
   * Works with handle 0, where it reports what a model *would* run on. Every slow or silent turn
   * is a property of one of these numbers, and a phone that is not plugged into a computer cannot
   * show logcat to whoever has to fix it.
   */
  external fun nativeEngineDiagnostics(handle: Long): ByteArray?

  // ---- chat templates, in the engine's own code ----

  /**
   * Capability flags of the loaded model's own template, as JSON. llama.cpp reads the
   * template out of the GGUF file and reports what it can express, which is the only
   * honest answer to "can this model do tool calls".
   */
  external fun nativeChatTemplatesInfo(handle: Long): ByteArray?

  /**
   * Applies that template to one OpenAI-shaped request, given as UTF-8 JSON bytes.
   * Returns a turn handle, or 0 when the request or the template was rejected —
   * [nativeLastError] then holds the reason.
   */
  external fun nativeChatOpenTurn(handle: Long, inputs: ByteArray): Long

  external fun nativeChatTurnInfo(turn: Long): ByteArray?

  /** Reads generated text back with the parser the template produced. */
  external fun nativeChatParse(turn: Long, text: ByteArray, partial: Boolean): ByteArray?

  external fun nativeChatCloseTurn(turn: Long)

  /**
   * Receives each decoded piece. Returning false stops generation, which the engine
   * reports as finish code 2.
   */
  interface TokenSink {
    fun onToken(piece: ByteArray): Boolean
  }

  companion object {
    /** Matches the `add_library(awaki-llm …)` target, without the `lib` prefix. */
    private const val LIBRARY_NAME = "awaki-llm"

    @Volatile
    private var instance: NativeLlama? = null

    @Volatile
    private var unavailableReason: String? = null

    /** Null and [unavailableReason] set when this platform has no engine to run. */
    fun get(): NativeLlama? {
      instance?.let { return it }
      synchronized(this) {
        instance?.let { return it }
        unavailableReason = runCatching {
          System.loadLibrary(LIBRARY_NAME)
          instance = NativeLlama()
          null
        }.exceptionOrNull()?.let { error ->
          "Native engine '${LIBRARY_NAME}' is unavailable: ${error.javaClass.simpleName}"
        }
        return instance
      }
    }

    val isAvailable: Boolean get() = get() != null

    /** Why the engine cannot run here, for an error the user can act on. */
    val unavailableReasonText: String? get() = unavailableReason
  }
}
