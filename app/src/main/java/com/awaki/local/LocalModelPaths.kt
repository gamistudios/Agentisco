package com.awaki.local

import java.io.File

/**
 * Where the on-device model files live, and where the Linux environment sees them.
 *
 * One directory answers both halves of the local AI feature: the app writes bytes into it from a
 * download or an import, and the guest mounts it at its own home path and reads the same bytes —
 * no copy, and nothing that has to be kept in sync between the two worlds.
 *
 * The engine reads the weights from the host path directly, so the mount is no longer how a model
 * gets decoded. It stays because a terminal session and the agent's shell tools are how a user
 * inspects what is installed.
 */
object LocalModelPaths {

  /** Directory name under the app's private files dir. */
  const val HOST_DIR_NAME = "local-models"

  /** The same directory as the guest sees it, in the fake root's home. */
  const val GUEST_DIR = "/root/local-models"

  /**
   * The tree an older build unpacked inside the model directory: a CPython interpreter, its
   * libraries and a compiler's output, none of which the native engine reads. The app owns this
   * directory and nothing else writes there, so [com.awaki.local.LocalModelUpgrade] deletes it
   * on the first launch of this build.
   */
  const val LEGACY_RUNTIME_DIR_NAME = "runtime"

  /** The virtualenv a still older build compiled there. */
  const val LEGACY_VENV_DIR_NAME = ".venv"

  /** The model server script older builds wrote beside the weights. */
  const val LEGACY_SERVER_SCRIPT_NAME = "serve.py"

  /** The marker holding the archive digest an older installer proved its tree with. */
  const val LEGACY_READY_MARKER_NAME = ".awaki-runtime-ready"

  fun hostDir(filesDir: File): File = File(filesDir, HOST_DIR_NAME)
}
