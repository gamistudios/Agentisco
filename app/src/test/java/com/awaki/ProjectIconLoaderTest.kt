package com.awaki

import android.graphics.Bitmap
import com.awaki.ui.util.ProjectIconLoader
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectIconLoaderTest {

  private fun tempFile(name: String): File {
    val ext = name.substringAfterLast('.', "")
    val base = name.substringBeforeLast('.')
    val file = File(System.getProperty("java.io.tmpdir"), "awaki_icon_${base}_${System.nanoTime()}.$ext")
    file.deleteOnExit()
    return file
  }

  @Test
  fun test_png_named_as_ico_loads_successfully() {
    val file = tempFile("favicon.ico")
    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    val loaded = ProjectIconLoader.load(file.absolutePath)
    assertNotNull("Should decode PNG file even with .ico extension", loaded)
  }

  @Test
  fun test_standard_png_loads_successfully() {
    val file = tempFile("logo.png")
    val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    val loaded = ProjectIconLoader.load(file.absolutePath)
    assertNotNull("Should decode regular PNG", loaded)
  }

  @Test
  fun test_ico_container_with_png_payload() {
    val file = tempFile("container.ico")
    val pngBitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    val pngBytes = ByteArrayOutputStream().use {
      pngBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
      it.toByteArray()
    }

    // Build valid ICO header + 1 directory entry + PNG payload
    val stream = ByteArrayOutputStream()
    // ICONDIR: reserved=0 (2 bytes), type=1 (2 bytes), count=1 (2 bytes)
    stream.write(byteArrayOf(0, 0, 1, 0, 1, 0))
    // ICONDIRENTRY: width=32, height=32, colors=0, reserved=0, planes=1 (2 bytes), bpp=32 (2 bytes), size (4 bytes), offset=22 (4 bytes)
    stream.write(32)
    stream.write(32)
    stream.write(0)
    stream.write(0)
    stream.write(byteArrayOf(1, 0)) // planes
    stream.write(byteArrayOf(32, 0)) // bpp
    val size = pngBytes.size
    stream.write(byteArrayOf((size and 0xFF).toByte(), ((size shr 8) and 0xFF).toByte(), ((size shr 16) and 0xFF).toByte(), ((size shr 24) and 0xFF).toByte()))
    val offset = 22
    stream.write(byteArrayOf((offset and 0xFF).toByte(), ((offset shr 8) and 0xFF).toByte(), ((offset shr 16) and 0xFF).toByte(), ((offset shr 24) and 0xFF).toByte()))
    stream.write(pngBytes)

    file.writeBytes(stream.toByteArray())

    val loaded = ProjectIconLoader.load(file.absolutePath)
    assertNotNull("Should decode ICO with embedded PNG payload", loaded)
  }

  @Test
  fun test_missing_or_corrupted_file_returns_null() {
    assertNull(ProjectIconLoader.load("/non/existent/path/icon.png"))
    val corrupted = tempFile("bad.ico").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
    assertNull(ProjectIconLoader.load(corrupted.absolutePath))
  }
}
