package com.agentisco.editor.model

import java.io.File

/** How the editor displays a file, decided from its extension. */
enum class FileViewerKind {
  /** Editable text — syntax-highlighted code canvas. */
  TEXT,

  /** Raster image or SVG, rendered by the image viewer (Coil). */
  IMAGE,

  /** Rendered page-by-page with Android's built-in PdfRenderer. */
  PDF,

  /** Audio/video, played inline with MediaPlayer. */
  MEDIA,

  /** Anything else binary (archives, databases, executables…). */
  OTHER_BINARY
}

object FileViewer {

  /** Text files bigger than this are not decoded into memory at all. */
  const val TEXT_PREVIEW_LIMIT_BYTES = 2_000_000L

  private val IMAGE_EXTS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "jfif",
    "heic", "heif", "avif", "svg", "svgz"
  )
  private val PDF_EXTS = setOf("pdf")
  private val MEDIA_EXTS = setOf(
    "mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "3gpp", "ts",
    "mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus", "amr", "mid", "midi"
  )

  /** Extensions that are text despite matching a media/image set (e.g. TypeScript `.ts`). */
  private val TEXT_EXCEPTIONS = setOf("ts")

  private val KNOWN_BINARY_EXTS = setOf(
    "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar", "war", "apk", "dmg", "iso",
    "exe", "dll", "so", "dylib", "bin", "o", "a", "class", "pyc", "pyo", "obj", "lib",
    "woff", "woff2", "ttf", "otf", "eot",
    "db", "sqlite", "sqlite3", "dat", "pack", "mp3", "mp4", "mov", "avi", "mkv", "webm",
    "jpg", "jpeg", "png", "gif", "bmp", "ico", "pdf", "psd", "ai", "sketch", "fig", "blend"
  )

  fun kindOf(fileName: String): FileViewerKind {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    if (ext.isNotEmpty() && ext !in TEXT_EXCEPTIONS) {
      if (ext in IMAGE_EXTS) return FileViewerKind.IMAGE
      if (ext in PDF_EXTS) return FileViewerKind.PDF
      if (ext in MEDIA_EXTS) return FileViewerKind.MEDIA
      if (ext in KNOWN_BINARY_EXTS) return FileViewerKind.OTHER_BINARY
    }
    return FileViewerKind.TEXT
  }

  fun isImage(fileName: String): Boolean = kindOf(fileName) == FileViewerKind.IMAGE

  fun isPdf(fileName: String): Boolean = kindOf(fileName) == FileViewerKind.PDF

  fun isMedia(fileName: String): Boolean = kindOf(fileName) == FileViewerKind.MEDIA

  /**
   * True when the file must never be decoded into a String: it is binary, or
   * larger than the text preview cap. SVG is text XML, so it stays decodable
   * to allow source editing.
   */
  fun mustNotDecodeAsText(fileName: String): Boolean {
    val kind = kindOf(fileName)
    if (kind == FileViewerKind.TEXT) return false
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext != "svg"
  }

  /**
   * Heuristic sniff of raw bytes for extensionless files: NUL bytes or a known
   * magic-number header within the first [header] bytes mean binary.
   */
  fun looksBinary(header: ByteArray): Boolean {
    if (header.isEmpty()) return false
    val magic = listOf(
      byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()),
      byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03, 0x04),
      "%PDF".toByteArray(),
      byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte()),
      byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
      byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    )
    if (magic.any { header.startsWith(it) }) return true
    if (header.size >= 2 && header[1] == 0.toByte() && header[0] in setOf('M'.code.toByte(), 'Z'.code.toByte())) return true
    val sample = header.take(512)
    if (sample.contains(0.toByte())) return true
    val odd = sample.count { b ->
      val c = b.toInt() and 0xFF
      c != 0x09 && c != 0x0A && c != 0x0D && (c < 0x20 || c == 0x7F)
    }
    return odd > sample.size / 5
  }

  private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (i in prefix.indices) if (this[i] != prefix[i]) return false
    return true
  }

  /** Absolute file on disk, or null when it does not exist. */
  fun resolve(projectRoot: String, relativePath: String): File? {
    val f = File(projectRoot, relativePath)
    return if (f.isFile) f else null
  }
}
