package com.awaki.local.py

import java.io.File

/**
 * Installs the model server script into the directory the Linux environment mounts.
 *
 * The script ships in the app's assets rather than inside the Debian rootfs or the runtime: it is
 * app code, versioned with the app, and the guest only ever runs the copy the current build
 * wrote. That makes rewriting it on every start the whole upgrade story — no migration, no
 * "an old server answered in a shape the app cannot parse", and a device that downgraded an
 * app gets a script that matches it again on the next launch.
 *
 * The copy is placed in the model directory instead of the rootfs because that directory is
 * already bound into the guest at a path the app knows, which is the only way a host-side write
 * becomes a guest-side file without a second copy of either.
 */
object ServerScript {

  /** Where the script lives inside the APK. */
  const val ASSET_PATH = "local-models/serve.py"

  /**
   * Writes [read] to [target] unless the target already holds exactly those bytes.
   *
   * Returns true when the file changed, which is the signal that a server already running from
   * the old copy has to be restarted.
   */
  fun install(read: () -> ByteArray, target: File): Boolean {
    val wanted = read()
    if (wanted.isEmpty()) throw IllegalStateException("The $ASSET_PATH asset is empty")
    if (target.isFile && target.length() == wanted.size.toLong() &&
      target.readBytes().contentEquals(wanted)
    ) {
      return false
    }
    // Written beside the target and renamed over it: a server starting a second later either
    // sees the old script or the new one, never half of either.
    val staging = File(target.parentFile, target.name + ".new")
    staging.parentFile?.mkdirs()
    try {
      staging.writeBytes(wanted)
      if (!staging.renameTo(target)) {
        target.delete()
        staging.renameTo(target)
      }
    } finally {
      staging.delete()
    }
    return true
  }
}
