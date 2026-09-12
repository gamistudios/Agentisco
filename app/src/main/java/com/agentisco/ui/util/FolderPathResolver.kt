package com.agentisco.ui.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Resolves a SAF tree URI (from OpenDocumentTree) to a real filesystem path so
 * projects keep working on actual folders. Primary storage maps to
 * `/storage/emulated/0`; other volumes are mapped via their known mount roots.
 */
object FolderPathResolver {

  private val volumeRoots = mapOf(
    "primary" to "/storage/emulated/0",
    "sdcard" to "/storage/emulated/0",
    "home" to null // app-private home has no shared filesystem path
  )

  /** Returns an absolute directory path, or null when the location isn't resolvable. */
  fun resolve(context: Context, treeUri: Uri): String? {
    val docId = try {
      DocumentsContract.getTreeDocumentId(treeUri)
    } catch (_: Exception) {
      return null
    }
    val parts = docId.split(":", limit = 2)
    if (parts.isEmpty()) return null
    val volume = parts[0]
    val subPath = parts.getOrNull(1)?.trim('/')?.takeIf { it.isNotEmpty() } ?: ""

    val root = volumeRoots[volume]
      ?: File("/storage/$volume").takeIf { it.isDirectory }?.absolutePath
      ?: return null

    val resolved = if (subPath.isEmpty()) root else "$root/$subPath"
    return resolved.takeIf { File(it).isDirectory }
  }

  /** Keeps URI permission alive across restarts (harmless if unsupported). */
  fun takePersistablePermission(context: Context, uri: Uri) {
    runCatching {
      context.contentResolver.takePersistableUriPermission(
        uri,
        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
          android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
      )
    }
  }
}
