package com.awaki.local

import java.io.File

/**
 * Where the on-device model files live, and where the Linux environment sees them.
 *
 * One directory answers both halves of the local AI feature: the app writes bytes into it
 * from a download or an import, and the guest mounts it at its own home path and reads the
 * same bytes — no copy, and nothing that has to be kept in sync between the two worlds.
 *
 * The Python venv is installed inside it for the same reason, and because the venv then
 * survives a rootfs reinstall: only the interpreter it was built against comes back.
 */
object LocalModelPaths {

  /** Directory name under the app's private files dir. */
  const val HOST_DIR_NAME = "local-models"

  /** The same directory as the guest sees it, in the fake root's home. */
  const val GUEST_DIR = "/root/local-models"

  const val VENV_DIR = "$GUEST_DIR/.venv"

  /** The interpreter every local AI command runs on once setup has finished. */
  const val VENV_PYTHON = "$VENV_DIR/bin/python"

  /** File name of the model server, inside [HOST_DIR_NAME] on the host and [GUEST_DIR] below. */
  const val SERVER_SCRIPT_NAME = "serve.py"

  /** The resident model server, written by the app and started by the venv interpreter. */
  const val SERVER_SCRIPT = "$GUEST_DIR/$SERVER_SCRIPT_NAME"

  fun hostDir(filesDir: File): File = File(filesDir, HOST_DIR_NAME)

  fun hostScript(filesDir: File): File = File(hostDir(filesDir), SERVER_SCRIPT_NAME)

  /**
   * The same file as the guest reads it.
   *
   * The model directory is bind-mounted rather than copied, so a host path under [hostDir] has
   * an exact guest twin by name — which is how a download the app wrote becomes a file the
   * Python runtime can open without either side holding a second copy.
   */
  fun guestPath(hostFile: File): String = "$GUEST_DIR/${hostFile.name}"
}
