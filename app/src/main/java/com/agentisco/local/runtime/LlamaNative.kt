package com.agentisco.local.runtime

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
 * class load.
 */
class LlamaNative private constructor() {

  external fun nativeBackendInit()

  external fun nativeBackendFree()

  /** Engine handle, or 0 when the model or its context could not be created. */
  external fun nativeLoadModel(path: String, contextSize: Int, threads: Int, batchSize: Int): Long

  external fun nativeModelInfo(handle: Long): ByteArray

  /**
   * Runs one completion. Returns the engine's finish code — 0 end of sequence,
   * 1 max tokens, 2 stopped by the sink, 3 aborted, 4 context full, negative for an
   * engine error whose text [nativeLastError] holds.
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

  external fun nativeLastError(): ByteArray

  external fun nativeSystemThreads(): Int

  /**
   * Receives each decoded piece. Returning false stops generation, which the engine
   * reports as finish code 2.
   */
  interface TokenSink {
    fun onToken(piece: ByteArray): Boolean
  }

  companion object {
    /** Matches the `add_library(agentisco-llm …)` target, without the `lib` prefix. */
    private const val LIBRARY_NAME = "agentisco-llm"

    @Volatile
    private var instance: LlamaNative? = null

    @Volatile
    private var unavailableReason: String? = null

    /** Null and [unavailableReason] set when this platform has no engine to run. */
    fun get(): LlamaNative? {
      instance?.let { return it }
      synchronized(this) {
        instance?.let { return it }
        unavailableReason = runCatching {
          System.loadLibrary(LIBRARY_NAME)
          instance = LlamaNative()
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
