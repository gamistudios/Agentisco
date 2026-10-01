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
 * The six installation states the local-model screen distinguishes. Named exactly
 * as the product asks, because the UI's whole job here is to never let a
 * half-downloaded file look like an installed one.
 */
enum class LocalModelInstallStatus(val label: String) {
  NOT_INSTALLED("Not installed"),
  DOWNLOADING("Downloading"),
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
    const val DEFAULT_CONTEXT = 2048
    const val DEFAULT_BATCH = 128
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
  val generation: LocalGenerationSettings = LocalGenerationSettings.Defaults
) {
  val isDefault: Boolean
    get() = this == Defaults

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
)
