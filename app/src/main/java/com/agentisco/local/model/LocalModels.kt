package com.agentisco.local.model

/**
 * Container format of a local model file. The set is deliberately narrow and
 * extensible: a format is only listable here if Agentisco can both recognise it
 * from its file header and run it, so a custom URL cannot smuggle in something
 * the runtime would treat as opaque bytes.
 */
enum class LocalModelFormat(
  val displayName: String,
  /** Magic bytes every file of this format starts with, checked before install. */
  val magic: ByteArray
) {
  GGUF("GGUF", byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()));

  companion object {
    /** Formats are matched by header, never by file extension — a URL can lie. */
    fun fromMagic(header: ByteArray): LocalModelFormat? =
      entries.firstOrNull { fmt ->
        header.size >= fmt.magic.size && fmt.magic.indices.all { header[it] == fmt.magic[it] }
      }
  }
}

/**
 * The installation states the local-model screen distinguishes. Named exactly as the
 * product asks, because the UI's whole job here is to never let a half-downloaded file
 * look like an installed one.
 *
 * [IMPORTING] is the same moment seen from the other end: the bytes are already on the
 * device, so the app copies them instead of fetching them, and the user has to be able to
 * tell those two waits apart.
 */
enum class LocalModelInstallStatus(val label: String) {
  NOT_INSTALLED("Not installed"),
  DOWNLOADING("Downloading"),
  IMPORTING("Importing"),
  INSTALLING("Installing"),
  INSTALLED("Installed"),
  UPDATE_AVAILABLE("Update available"),
  FAILED("Failed")
}

/** Live transfer counters; percentage and ETA are derived, never stored. */
data class LocalModelProgress(
  val bytesTransferred: Long = 0L,
  val totalBytes: Long = 0L,
  val speedBytesPerSec: Long = 0L
) {
  val fraction: Float
    get() = if (totalBytes <= 0L) 0f else (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
  val percent: Int get() = (fraction * 100f).toInt()
  val remainingBytes: Long get() = (totalBytes - bytesTransferred).coerceAtLeast(0L)
  val etaSeconds: Long?
    get() = if (speedBytesPerSec > 0L && totalBytes > bytesTransferred) remainingBytes / speedBytesPerSec else null
}

/**
 * How the inference engine is configured for one model. These are read at load
 * time, so changing one means the model is reloaded.
 *
 * There is intentionally no GPU/offload control: the bundled engine is compiled
 * CPU-only, and offering a knob the runtime cannot honour is worse than not
 * offering it.
 */
data class LocalRuntimeSettings(
  val contextSize: Int = DEFAULT_CONTEXT,
  val threadCount: Int = 0,
  val batchSize: Int = DEFAULT_BATCH
) {
  companion object {
    /**
     * The context a model gets when the user has not retuned it.
     *
     * An agent turn is not a chat bubble: the playbook and the list of tools the runtime
     * offers are rendered into the prompt before the user's message is, which is a couple
     * of thousand tokens on their own. A window smaller than that cannot be answered at
     * all — the engine refuses the prompt — so the default has to leave room for the
     * machinery as well as the conversation, while staying small enough for a phone to
     * hold the KV cache of the models this catalog ships.
     */
    const val DEFAULT_CONTEXT = 4096

    /**
     * How many prompt tokens the engine evaluates per graph evaluation.
     *
     * The cost of a turn is dominated by prefilling the prompt, and each evaluation ends
     * in a barrier every worker thread waits at — so the smaller the batch, the more
     * barriers a prompt pays for. 128 was measured at roughly ten barriers a second on a
     * phone; 512 is what llama.cpp's own reference apps ship with and still fits a
     * mid-range device's compute buffer alongside a 4 K context.
     */
    const val DEFAULT_BATCH = 512

    /**
     * [DEFAULT_CONTEXT] capped by what the file says it was trained for. A window longer
     * than the weights can hold yields confident nonsense rather than an error, so the
     * smaller of the two wins; a file that declares nothing gets the default.
     */
    fun contextSizeFor(trainedContextLength: Long?): Int =
      trainedContextLength?.takeIf { it > 0L }?.toInt()?.let { minOf(DEFAULT_CONTEXT, it) } ?: DEFAULT_CONTEXT

    val Defaults = LocalRuntimeSettings()
  }
}

/**
 * Per-request generation parameters. Applied to every request for the model,
 * separately from [LocalRuntimeSettings] so a user can retune sampling without
 * paying for a model reload.
 */
data class LocalGenerationSettings(
  val maxOutputTokens: Int = DEFAULT_MAX_TOKENS,
  val temperature: Double = DEFAULT_TEMPERATURE,
  val topK: Int = DEFAULT_TOP_K,
  /** 1.0 disables nucleus sampling; llama's top-p is only applied below 1.0. */
  val topP: Double = 1.0,
  val repeatPenalty: Double = DEFAULT_REPEAT_PENALTY
) {
  companion object {
    const val DEFAULT_MAX_TOKENS = 200
    const val DEFAULT_TEMPERATURE = 0.1
    const val DEFAULT_TOP_K = 50
    const val DEFAULT_REPEAT_PENALTY = 1.05
    val Defaults = LocalGenerationSettings()
  }
}

/** Everything the user can tune for one installed local model. */
data class LocalModelConfiguration(
  val runtime: LocalRuntimeSettings = LocalRuntimeSettings.Defaults,
  val generation: LocalGenerationSettings = LocalGenerationSettings.Defaults,
  /**
   * The tools this model is offered. Every tool costs its description and its JSON
   * schema in the prompt the phone must prefill before the first word, so an
   * on-device model is handed a chosen set rather than the whole registry.
   *
   * Null is "the built-in default set" rather than a copy of it, which is what keeps a
   * model added before this existed from silently inheriting a frozen, possibly stale,
   * list — and what lets a later, better-chosen default reach it. An empty set is a
   * deliberate answer: this model gets no tools at all.
   */
  val allowedTools: Set<String>? = null
) {
  val isDefault: Boolean
    get() = runtime == LocalRuntimeSettings.Defaults &&
      generation == LocalGenerationSettings.Defaults &&
      (allowedTools == null || allowedTools == com.agentisco.agent.tool.OnDeviceTools.DEFAULT)

  /** The tool set a run with this configuration may be offered. */
  fun toolsOrDefault(): Set<String> = allowedTools ?: com.agentisco.agent.tool.OnDeviceTools.DEFAULT

  companion object {
    val Defaults = LocalModelConfiguration()
  }
}

/**
 * A local model Agentisco knows about — either shipped in the built-in catalog or
 * added by the user. The record is configuration: whether the bytes are actually
 * on disk is [installed], which the repository recomputes from the file plus its
 * recorded checksum on every start, never from a remembered flag.
 */
data class LocalModel(
  val id: String,
  val name: String,
  /** Page the model is published on; shown as the source and used to derive a download URL. */
  val sourceUrl: String,
  /** Direct asset URL the installer fetches. */
  val downloadUrl: String,
  val format: LocalModelFormat = LocalModelFormat.GGUF,
  /** Quantization tag shown to the user (Q4_0, Q5_K_M, ...). */
  val quantization: String = "",
  val description: String = "",
  /** Authoritative byte size; 0 until the source reports it. */
  val sizeBytes: Long = 0L,
  /** Lowercase SHA-256, when the source publishes one. */
  val checksum: String? = null,
  val version: String = "",
  val builtIn: Boolean = false,
  val installed: Boolean = false,
  val localPath: String? = null,
  val configuration: LocalModelConfiguration = LocalModelConfiguration.Defaults
) {
  /**
   * True when these bytes were copied off the device's own storage rather than fetched
   * from anywhere. There is no source to update them from, which is the only thing the
   * screen needs to know in order to stop offering a Download or an Update.
   *
   * Derived instead of stored so a model added before imports existed cannot be
   * mislabelled, and so nothing has to keep two fields in step.
   */
  val isImported: Boolean get() = !builtIn && downloadUrl.isBlank()
}
