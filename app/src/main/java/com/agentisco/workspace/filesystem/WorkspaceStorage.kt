package com.agentisco.workspace.filesystem

import android.os.StatFs
import com.agentisco.data.model.WorkspaceStorageInfo
import java.io.File

/**
 * Free/total space of the filesystem a workspace folder lives on, read with
 * [StatFs]. Walks up to the nearest existing ancestor first, because the proot
 * workspace root does not exist until the Linux environment is bootstrapped.
 *
 * Returns null when nothing is measurable — callers render a placeholder
 * instead of a fabricated number.
 */
object WorkspaceStorage {

  fun read(root: File): WorkspaceStorageInfo? {
    val target = generateSequence(root) { it.parentFile }.firstOrNull { it.exists() } ?: return null
    return runCatching {
      val stat = StatFs(target.absolutePath)
      WorkspaceStorageInfo(freeBytes = stat.availableBytes, totalBytes = stat.totalBytes)
    }.getOrNull()
  }
}
