package com.agentisco.ui.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Decodes the real icon file [com.agentisco.workspace.filesystem.ProjectMetadataScanner]
 * located inside a project (logo / favicon / Android launcher icon) into a
 * Compose [ImageBitmap]. Dependency-free on purpose: image decoding goes through
 * [BitmapFactory] (PNG / JPEG / WebP) plus a small ICO reader for the common
 * case of a `.ico` that embeds a PNG payload.
 *
 * Every failure path returns null so the caller can fall back to a type icon or
 * a name-derived initial instead of showing a broken image.
 */
object ProjectIconLoader {

  /** Icons are drawn at ~36dp, so there is no point decoding a 512px logo. */
  private const val TARGET_PX = 128
  private const val MAX_CACHE_ENTRIES = 24

  private val cache = ConcurrentHashMap<String, ImageBitmap>()

  /** Already-decoded icon for [path], safe to call from composition. */
  fun cached(path: String): ImageBitmap? = cache[path]

  /** Decodes [path] and memoizes the result. Blocking — call from IO. */
  fun load(path: String): ImageBitmap? {
    cache[path]?.let { return it }
    val file = File(path)
    if (!file.isFile || !file.canRead()) return null
    val bitmap = runCatching {
      if (path.substringAfterLast('.', "").equals("ico", ignoreCase = true)) {
        decodeIco(file)
      } else {
        decodeFile(file)
      }
    }.getOrNull() ?: return null

    if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
    cache[path] = bitmap.asImageBitmap()
    return cache[path]
  }

  private fun decodeFile(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
      inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
    }
    return BitmapFactory.decodeFile(file.absolutePath, options)
  }

  private data class IcoDirEntry(
    val offset: Int,
    val length: Int,
    val width: Int,
    val height: Int
  )

  /**
   * Robust ICO reader: parses directory entries, handles PNG payloads, 32-bit,
   * 24-bit, 8-bit, and 4-bit (16-color) BMP/DIB payloads, and tries entries in
   * optimal size order (preferring 32..64px for mobile icons).
   */
  private fun decodeIco(file: File): Bitmap? {
    val bytes = file.readBytes()
    if (bytes.size < 22) return null

    // If the file is directly a PNG / WebP / JPEG with a .ico extension:
    if (isPng(bytes)) {
      return decodePngPayload(bytes)
    }

    val hasIconHeader = bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
      bytes[2] == 1.toByte() && bytes[3] == 0.toByte()
    if (!hasIconHeader) return null

    val count = readShort(bytes, 4)
    if (count <= 0) return null

    val entries = ArrayList<IcoDirEntry>(count)
    for (i in 0 until count) {
      val entry = 6 + i * 16
      if (entry + 16 > bytes.size) break
      val rawW = bytes[entry].toInt() and 0xFF
      val rawH = bytes[entry + 1].toInt() and 0xFF
      val width = if (rawW == 0) 256 else rawW
      val height = if (rawH == 0) 256 else rawH
      val length = readInt(bytes, entry + 8)
      val offset = readInt(bytes, entry + 12)
      if (length <= 0 || offset < 0 || offset + length > bytes.size) continue
      entries.add(IcoDirEntry(offset, length, width, height))
    }

    if (entries.isEmpty()) return null

    // Rank entries: prefer crisp sizes between 32 and 96 px, then larger, then 16 px.
    fun rank(e: IcoDirEntry): Int {
      val dim = maxOf(e.width, e.height)
      return when {
        dim in 32..64 -> 1000 - kotlin.math.abs(dim - 48)
        dim in 65..128 -> 800 - dim
        dim > 128 -> 500 - dim
        else -> dim // e.g. 16px
      }
    }

    val sorted = entries.sortedByDescending { rank(it) }

    for (entry in sorted) {
      val payload = bytes.copyOfRange(entry.offset, entry.offset + entry.length)
      val bmp = if (isPng(payload)) {
        decodePngPayload(payload)
      } else {
        decodeDib(payload)
      }
      if (bmp != null) return bmp
    }

    return null
  }

  private fun decodePngPayload(payload: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeStream(ByteArrayInputStream(payload), null, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
      inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
    }
    return BitmapFactory.decodeStream(ByteArrayInputStream(payload), null, options)
  }

  /**
   * Decodes Windows DIB (BMP) payloads commonly found in favicon.ico files.
   * Supports 32-bit (ARGB/RGB), 24-bit (RGB), 8-bit palette (256 colors),
   * and 4-bit palette (16 colors).
   */
  private fun decodeDib(bytes: ByteArray): Bitmap? {
    if (bytes.size < 40) return null
    val headerSize = readInt(bytes, 0)
    if (headerSize < 40 || headerSize > bytes.size) return null
    val width = readInt(bytes, 4)
    val doubleHeight = readInt(bytes, 8)
    val height = kotlin.math.abs(doubleHeight / 2).let { if (it == 0) kotlin.math.abs(doubleHeight) else it }
    if (width <= 0 || width > 512 || height <= 0 || height > 512) return null
    val bitCount = readShort(bytes, 14)
    val compression = readInt(bytes, 16)
    if (compression != 0 && compression != 3) return null // BI_RGB (0) or BI_BITFIELDS (3)

    var clrUsed = readInt(bytes, 32)
    if (clrUsed == 0 && bitCount <= 8) clrUsed = 1 shl bitCount

    val paletteOffset = headerSize
    val paletteSize = if (bitCount <= 8) clrUsed * 4 else if (compression == 3) 12 else 0
    val xorOffset = paletteOffset + paletteSize
    if (xorOffset > bytes.size) return null

    val pixels = IntArray(width * height)
    val xorRowStride = ((width * bitCount + 31) / 32) * 4
    val andRowStride = ((width + 31) / 32) * 4
    val andOffset = xorOffset + xorRowStride * height
    val hasAndMask = andOffset + andRowStride * height <= bytes.size

    for (y in 0 until height) {
      val srcY = height - 1 - y
      val rowStart = xorOffset + srcY * xorRowStride
      val andRowStart = if (hasAndMask) andOffset + srcY * andRowStride else -1

      for (x in 0 until width) {
        val pixelIndex = y * width + x
        var isTransparent = false
        if (andRowStart >= 0 && andRowStart + (x / 8) < bytes.size) {
          val byteVal = bytes[andRowStart + (x / 8)].toInt() and 0xFF
          val bit = (byteVal shr (7 - (x % 8))) and 1
          if (bit == 1) isTransparent = true
        }

        if (isTransparent) {
          pixels[pixelIndex] = 0
          continue
        }

        when (bitCount) {
          32 -> {
            val offset = rowStart + x * 4
            if (offset + 3 < bytes.size) {
              val b = bytes[offset].toInt() and 0xFF
              val g = bytes[offset + 1].toInt() and 0xFF
              val r = bytes[offset + 2].toInt() and 0xFF
              val a = bytes[offset + 3].toInt() and 0xFF
              val finalAlpha = if (a == 0 && !hasAndMask) 255 else a
              pixels[pixelIndex] = (finalAlpha shl 24) or (r shl 16) or (g shl 8) or b
            }
          }
          24 -> {
            val offset = rowStart + x * 3
            if (offset + 2 < bytes.size) {
              val b = bytes[offset].toInt() and 0xFF
              val g = bytes[offset + 1].toInt() and 0xFF
              val r = bytes[offset + 2].toInt() and 0xFF
              pixels[pixelIndex] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
          }
          8 -> {
            val offset = rowStart + x
            if (offset < bytes.size) {
              val colorIdx = bytes[offset].toInt() and 0xFF
              val palEntry = paletteOffset + colorIdx * 4
              if (palEntry + 2 < bytes.size) {
                val b = bytes[palEntry].toInt() and 0xFF
                val g = bytes[palEntry + 1].toInt() and 0xFF
                val r = bytes[palEntry + 2].toInt() and 0xFF
                pixels[pixelIndex] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
              }
            }
          }
          4 -> {
            val byteOffset = rowStart + (x / 2)
            if (byteOffset < bytes.size) {
              val byteVal = bytes[byteOffset].toInt() and 0xFF
              val colorIdx = if (x % 2 == 0) (byteVal shr 4) and 0x0F else byteVal and 0x0F
              val palEntry = paletteOffset + colorIdx * 4
              if (palEntry + 2 < bytes.size) {
                val b = bytes[palEntry].toInt() and 0xFF
                val g = bytes[palEntry + 1].toInt() and 0xFF
                val r = bytes[palEntry + 2].toInt() and 0xFF
                pixels[pixelIndex] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
              }
            }
          }
          else -> return null
        }
      }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
  }

  private fun isPng(bytes: ByteArray): Boolean =
    bytes.size > 8 &&
      bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
      bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()

  private fun sampleSizeFor(width: Int, height: Int): Int {
    var sample = 1
    var longest = maxOf(width, height)
    while (longest / 2 >= TARGET_PX) {
      longest /= 2
      sample *= 2
    }
    return sample
  }

  private fun readShort(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

  private fun readInt(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xFF) or
      ((bytes[at + 1].toInt() and 0xFF) shl 8) or
      ((bytes[at + 2].toInt() and 0xFF) shl 16) or
      ((bytes[at + 3].toInt() and 0xFF) shl 24)
}

/**
 * Loads a project icon without blocking composition: returns the memoized
 * bitmap immediately when available, otherwise decodes on [Dispatchers.IO] and
 * recomposes. null means "no decodable icon" — the caller picks a fallback.
 */
@Composable
fun rememberProjectIcon(iconPath: String?): ImageBitmap? {
  if (iconPath.isNullOrBlank()) return null
  val state = produceState<ImageBitmap?>(initialValue = ProjectIconLoader.cached(iconPath), key1 = iconPath) {
    value = ProjectIconLoader.cached(iconPath) ?: withContext(Dispatchers.IO) { ProjectIconLoader.load(iconPath) }
  }
  return state.value
}
