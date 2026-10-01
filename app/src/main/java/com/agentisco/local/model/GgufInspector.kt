package com.agentisco.local.model

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * The metadata Agentisco reads out of a GGUF file before it will run it.
 *
 * Nothing here needs the engine: the header alone says whether the bytes are a
 * GGUF at all, how many tensors and key/value pairs the file claims, and which
 * architecture and quantization the weights were packed with. That is enough to
 * reject a wrong-format or truncated download before it becomes selectable,
 * which is what a model added from an arbitrary URL needs — the file itself is
 * the only evidence about it.
 */
data class GgufMetadata(
  val formatVersion: Long,
  val tensorCount: Long,
  val metadataCount: Long,
  /** `general.architecture`, e.g. "lfm2" or "llama". */
  val architecture: String,
  /** `general.name`, the human-readable model name the publisher chose. */
  val publishedName: String?,
  /** `general.file_type`: which quantization the weights were packed with. */
  val fileType: Long?,
  /**
   * `<arch>.context_length` — what the model was trained for. The settings screen
   * caps its context control with this, because offering a longer window than the
   * weights can hold produces confident nonsense rather than an error.
   */
  val contextLength: Long? = null
) {
  companion object {
    /**
     * `general.file_type` as the pinned engine numbers it (`enum llama_ftype` in
     * llama.h), for the quantizations a downloaded model realistically carries.
     * Null for anything else, so the screen never invents a label: the catalog's
     * own quantization tag stays in front of the user instead.
     */
    fun fileTypeLabel(fileType: Long?): String? = when (fileType) {
      0L -> "F32"
      1L -> "F16"
      2L -> "Q4_0"
      3L -> "Q4_1"
      7L -> "Q8_0"
      8L -> "Q5_0"
      9L -> "Q5_1"
      10L -> "Q2_K"
      11L -> "Q3_K_S"
      12L -> "Q3_K_M"
      13L -> "Q3_K_L"
      14L -> "Q4_K_S"
      15L -> "Q4_K_M"
      16L -> "Q5_K_S"
      17L -> "Q5_K_M"
      18L -> "Q6_K"
      32L -> "BF16"
      else -> null
    }
  }
}

/** Outcome of inspecting a candidate model file. */
sealed class GgufInspection {
  data class Valid(val metadata: GgufMetadata) : GgufInspection()

  /** Reason the file will not be offered for inference. */
  data class Invalid(val reason: String) : GgufInspection()
}

/**
 * Reads the GGUF header and its key/value metadata section, and nothing else.
 *
 * The bytes may have come from any URL the user typed, so every length taken
 * from the file is checked against the real file size and against hard caps
 * before it is used: a header claiming a 4 GB string has to fail here rather
 * than be allocated. Array payloads are skipped by seeking, so a metadata
 * section carrying a whole vocabulary costs a few reads instead of a copy.
 */
object GgufInspector {

  private val MAGIC = LocalModelFormat.GGUF.magic

  private const val MIN_VERSION = 1L
  private const val MAX_VERSION = 3L

  private const val MAX_METADATA_PAIRS = 100_000L
  private const val MAX_TENSORS = 100_000_000L
  private const val MAX_ARRAY_ELEMENTS = 10_000_000L
  private const val MAX_STRING_BYTES = 32L * 1024 * 1024

  /** No published model nests metadata arrays this deep; further is only a loop risk. */
  private const val MAX_NESTED_ARRAY_DEPTH = 8

  private const val TYPE_UINT8 = 0
  private const val TYPE_INT8 = 1
  private const val TYPE_UINT16 = 2
  private const val TYPE_INT16 = 3
  private const val TYPE_UINT32 = 4
  private const val TYPE_INT32 = 5
  private const val TYPE_FLOAT32 = 6
  private const val TYPE_BOOL = 7
  private const val TYPE_STRING = 8
  private const val TYPE_ARRAY = 9
  private const val TYPE_UINT64 = 10
  private const val TYPE_INT64 = 11
  private const val TYPE_FLOAT64 = 12

  private const val KEY_ARCHITECTURE = "general.architecture"
  private const val KEY_NAME = "general.name"
  private const val KEY_FILE_TYPE = "general.file_type"

  /** `<architecture>.context_length`, whichever architecture the file declares. */
  private const val KEY_CONTEXT_LENGTH_SUFFIX = ".context_length"

  /**
   * Validates [file] as an installable model. A file that parses as GGUF but
   * whose metadata stops short of declaring an architecture is rejected too:
   * without it the engine cannot build the model, and offering the user a
   * selectable model that only fails at load time is the worse outcome.
   */
  fun inspect(file: File): GgufInspection =
    if (!file.isFile) {
      GgufInspection.Invalid("Model file is missing")
    } else if (file.length() < MAGIC.size) {
      GgufInspection.Invalid("File is too small to be a GGUF model")
    } else {
      try {
        RandomAccessFile(file, "r").use { reader -> GgufReader(reader, file.length()).readMetadata() }
      } catch (e: OutOfMemoryError) {
        GgufInspection.Invalid("Model metadata is too large to read safely")
      } catch (e: MalformedGguf) {
        GgufInspection.Invalid(e.message ?: "Could not read model metadata")
      } catch (e: Exception) {
        GgufInspection.Invalid("Could not read model metadata")
      }
    }

  /**
   * The cheap question: could the bytes at the start of [file] be a GGUF? Used to
   * decide whether a partial download may be resumed, where reading the whole
   * metadata section would cost too much for a repeated check.
   */
  fun looksLikeGguf(file: File): Boolean {
    if (!file.isFile || file.length() < MAGIC.size) return false
    return try {
      val header = ByteArray(MAGIC.size)
      file.inputStream().use { input -> input.read(header) }
      header.contentEquals(MAGIC)
    } catch (e: Exception) {
      false
    }
  }

  internal class MalformedGguf(reason: String) : Exception(reason)

  private class GgufReader(private val raf: RandomAccessFile, private val fileLength: Long) {
    private var position = 0L

    fun readMetadata(): GgufInspection {
      if (!read(MAGIC.size.toLong()).contentEquals(MAGIC)) throw MalformedGguf("Downloaded file is not a GGUF model")

      val version = bounded(unsigned(4), MIN_VERSION..MAX_VERSION) { "Unsupported GGUF format version $it" }
      // Version 1 packed the tensor and metadata counts as 32-bit values.
      val countWidth = if (version == 1L) 4 else 8
      val tensorCount = bounded(unsigned(countWidth), 0L..MAX_TENSORS) { "Model declares an impossible tensor count" }
      val metadataCount = bounded(unsigned(countWidth), 0L..MAX_METADATA_PAIRS) {
        "Model declares an impossible metadata count"
      }
      if (tensorCount == 0L) throw MalformedGguf("GGUF model contains no tensors")

      var architecture: String? = null
      var publishedName: String? = null
      var fileType: Long? = null
      var contextLength: Long? = null
      var pair = 0L
      while (pair < metadataCount) {
        val key = string()
        when (val value = value(unsigned(4).toInt())) {
          is String -> when (key) {
            KEY_ARCHITECTURE -> architecture = value
            KEY_NAME -> publishedName = value
          }
          is Long -> when {
            key == KEY_FILE_TYPE -> fileType = value
            key.endsWith(KEY_CONTEXT_LENGTH_SUFFIX) -> contextLength = value
          }
        }
        pair++
      }

      return GgufInspection.Valid(
        GgufMetadata(
          formatVersion = version,
          tensorCount = tensorCount,
          metadataCount = metadataCount,
          architecture = architecture?.takeIf { it.isNotBlank() }
            ?: throw MalformedGguf("Model metadata does not declare an architecture"),
          publishedName = publishedName?.takeIf { it.isNotBlank() },
          fileType = fileType,
          contextLength = contextLength?.takeIf { it > 0L }
        )
      )
    }

    private fun read(count: Long): ByteArray {
      if (count < 0L || position + count > fileLength) throw MalformedGguf("Model file is truncated")
      val bytes = ByteArray(count.toInt())
      raf.seek(position)
      raf.readFully(bytes)
      position += count
      return bytes
    }

    /** GGUF is little-endian throughout. */
    private fun unsigned(width: Int): Long {
      val bytes = read(width.toLong())
      var value = 0L
      for (index in width - 1 downTo 0) value = (value shl 8) or (bytes[index].toLong() and 0xFF)
      return value
    }

    private fun signed(width: Int): Long {
      val raw = unsigned(width)
      return if (width < 8 && (raw and (1L shl (width * 8 - 1))) != 0L) raw - (1L shl (width * 8)) else raw
    }

    private inline fun bounded(value: Long, range: LongRange, reason: (Long) -> String): Long {
      // A u64 above Long.MAX_VALUE arrives as a negative, so the range check has
      // to reject it rather than let it look like a small number.
      if (value !in range) throw MalformedGguf(reason(value))
      return value
    }

    private fun string(): String {
      val length = bounded(unsigned(8), 0L..MAX_STRING_BYTES) { "Model metadata declares an impossible string length" }
      return String(read(length), StandardCharsets.UTF_8)
    }

    private fun skipElements(elementType: Int, elementCount: Long, depth: Int = 0) {
      val width = fixedWidth(elementType)
      if (width > 0) {
        position = bounded(position + elementCount * width, position..fileLength) { "Model metadata is truncated" }
        return
      }
      // Arrays of strings and nested arrays carry no fixed stride, so the only way
      // past them is to read each length. Every step is a seek, never a copy, and
      // each element consumes bytes the truncation check accounts for, so a
      // fabricated element count runs out of file long before it runs out of time.
      if (elementType == TYPE_STRING) {
        var index = 0L
        while (index < elementCount) {
          val length = bounded(unsigned(8), 0L..(fileLength - position)) { "Model metadata is truncated" }
          position += length
          index++
        }
        return
      }
      if (elementType != TYPE_ARRAY || depth >= MAX_NESTED_ARRAY_DEPTH) {
        throw MalformedGguf("Model metadata uses an unsupported array layout")
      }
      var index = 0L
      while (index < elementCount) {
        val innerType = unsigned(4).toInt()
        val innerCount = bounded(unsigned(8), 0L..MAX_ARRAY_ELEMENTS) {
          "Model metadata declares an impossible array length"
        }
        skipElements(innerType, innerCount, depth + 1)
        index++
      }
    }

    /** The values Agentisco actually reads; everything else is skipped. */
    private fun value(type: Int): Any? = when (type) {
      TYPE_UINT8, TYPE_UINT16, TYPE_UINT32, TYPE_UINT64, TYPE_BOOL -> unsigned(fixedWidth(type))
      TYPE_INT8, TYPE_INT16, TYPE_INT32, TYPE_INT64 -> signed(fixedWidth(type))
      TYPE_FLOAT32 -> java.lang.Float.intBitsToFloat(unsigned(4).toInt()).toDouble()
      TYPE_FLOAT64 -> java.lang.Double.longBitsToDouble(unsigned(8))
      TYPE_STRING -> string()
      TYPE_ARRAY -> {
        val elementType = unsigned(4).toInt()
        val elementCount = bounded(unsigned(8), 0L..MAX_ARRAY_ELEMENTS) {
          "Model metadata declares an impossible array length"
        }
        skipElements(elementType, elementCount)
        null
      }
      else -> throw MalformedGguf("Model metadata uses an unknown value type")
    }
  }

  private fun fixedWidth(type: Int): Int = when (type) {
    TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> 1
    TYPE_UINT16, TYPE_INT16 -> 2
    TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> 4
    TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> 8
    else -> 0
  }
}
