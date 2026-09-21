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

  /**
   * Minimal ICO reader: picks the largest directory entry and decodes it when it
   * carries a PNG payload (what every modern `.ico` from a favicon generator or
   * Windows tool does). Classic BMP-payload icons and multi-image files we
   * cannot read simply yield null.
   */
  private fun decodeIco(file: File): Bitmap? {
    val bytes = file.readBytes()
    if (bytes.size < 22) return null
    val hasIconHeader = bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
      bytes[2] == 1.toByte() && bytes[3] == 0.toByte()
    if (!hasIconHeader) return null

    val count = readShort(bytes, 4)
    if (count <= 0) return null

    var best: Triple<Int, Int, Int>? = null // offset, length, pixel area
    for (i in 0 until count) {
      val entry = 6 + i * 16
      if (entry + 16 > bytes.size) break
      val width = bytes[entry].toInt() and 0xFF
      val height = bytes[entry + 1].toInt() and 0xFF
      val length = readInt(bytes, entry + 8)
      val offset = readInt(bytes, entry + 12)
      if (length <= 0 || offset < 0 || offset + length > bytes.size) continue
      val area = (if (width == 0) 256 else width) * (if (height == 0) 256 else height)
      val current = best
      if (current == null || area > current.third) best = Triple(offset, length, area)
    }
    val (offset, length, _) = best ?: return null
    val payload = bytes.copyOfRange(offset, offset + length)
    if (!isPng(payload)) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeStream(ByteArrayInputStream(payload), null, bounds)
    if (bounds.outWidth <= 0) return null
    val options = BitmapFactory.Options().apply {
      inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
    }
    return BitmapFactory.decodeStream(ByteArrayInputStream(payload), null, options)
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
