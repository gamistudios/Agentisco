package com.awaki.local

import com.awaki.local.LocalModelPaths.LEGACY_READY_MARKER_NAME
import com.awaki.local.LocalModelPaths.LEGACY_RUNTIME_DIR_NAME
import com.awaki.local.LocalModelPaths.LEGACY_SERVER_SCRIPT_NAME
import com.awaki.local.LocalModelPaths.LEGACY_VENV_DIR_NAME
import com.awaki.local.LocalModelPaths.hostDir
import java.io.File

/**
 * The one upgrade the native engine owes every existing install.
 *
 * Earlier builds unpacked a Python model runtime inside the model directory: 2,814 files and
 * roughly 177 MiB, holding an interpreter whose only job was to reach the same decoder this build
 * links directly. Nothing in this build reads any of it, and nothing on the device will ever
 * delete it, because the app stopped writing there — so a phone that updated would carry that tree
 * forever.
 *
 * It runs at launch rather than when the Models screen opens because a user who never opens that
 * screen is exactly the user who would not notice the space is still gone.
 */
object LocalModelUpgrade {

  /** What an older build left in the model directory, whether or not it is still there. */
  fun legacyPaths(filesDir: File): List<File> = listOf(
    File(hostDir(filesDir), LEGACY_RUNTIME_DIR_NAME),
    File(hostDir(filesDir), LEGACY_VENV_DIR_NAME),
    File(hostDir(filesDir), LEGACY_SERVER_SCRIPT_NAME),
    File(hostDir(filesDir), LEGACY_READY_MARKER_NAME)
  )

  /**
   * Deletes what an older build left behind and returns the bytes it freed, so the caller can
   * say what the upgrade cost the device in the only currency that matters here.
   *
   * The app owns this directory and nothing else writes into it, which is what makes an unasked
   * delete safe: a model the user installed sits beside these paths and is never one of them.
   */
  fun reclaimLegacyRuntime(filesDir: File): Long {
    var freed = 0L
    for (stale in legacyPaths(filesDir)) {
      if (!stale.exists()) continue
      freed += runCatching {
        if (stale.isDirectory) stale.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        else stale.length()
      }.getOrDefault(0L)
      runCatching { stale.deleteRecursively() }
    }
    return freed
  }
}
