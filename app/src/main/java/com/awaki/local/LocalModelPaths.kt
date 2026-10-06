package com.awaki.local

import java.io.File

/**
 * Where the on-device model files live, and where the Linux environment sees them.
 *
 * One directory answers both halves of the local AI feature: the app writes bytes into it
 * from a download or an import, and the guest mounts it at its own home path and reads the
 * same bytes — no copy, and nothing that has to be kept in sync between the two worlds.
 *
 * The Python runtime is unpacked inside it for the same reason, and because it then survives
 * a rootfs reinstall: the interpreter travels with the app, not with the guest.
 */
object LocalModelPaths {

  /** Directory name under the app's private files dir. */
  const val HOST_DIR_NAME = "local-models"

  /** The same directory as the guest sees it, in the fake root's home. */
  const val GUEST_DIR = "/root/local-models"

  /** The unpacked Python runtime, installed by [com.awaki.local.py.PythonRuntime]. */
  const val RUNTIME_DIR_NAME = "runtime"

  const val RUNTIME_GUEST_DIR = "$GUEST_DIR/$RUNTIME_DIR_NAME"

  /** The interpreter every local AI command runs on: a real file, not a symlink. */
  const val RUNTIME_PYTHON = "$RUNTIME_GUEST_DIR/bin/python"

  /**
   * Shared libraries the runtime carries with it — the interpreter's own, plus the ones the
   * guest's Ubuntu base does not ship (llama.cpp needs `libgomp.so.1`, which is not in it).
   */
  const val RUNTIME_GUEST_LIB = "$RUNTIME_GUEST_DIR/lib"

  /** Written last, holding the archive digest that produced this tree. */
  const val RUNTIME_READY_NAME = ".awaki-runtime-ready"

  /**
   * The virtualenv an older build compiled inside the model directory. The app owns that
   * directory and nothing else writes there, so an install clears it rather than leaving
   * a gigabyte of compiler output the runtime no longer reads.
   */
  const val LEGACY_VENV_DIR_NAME = ".venv"

  /** File name of the model server, inside [HOST_DIR_NAME] on the host and [GUEST_DIR] below. */
  const val SERVER_SCRIPT_NAME = "serve.py"

  /** The resident model server, written by the app and started by the runtime interpreter. */
  const val SERVER_SCRIPT = "$GUEST_DIR/$SERVER_SCRIPT_NAME"

  fun hostDir(filesDir: File): File = File(filesDir, HOST_DIR_NAME)

  fun hostScript(filesDir: File): File = File(hostDir(filesDir), SERVER_SCRIPT_NAME)

  fun hostRuntimeDir(filesDir: File): File = File(hostDir(filesDir), RUNTIME_DIR_NAME)

  fun hostRuntimeReady(filesDir: File): File = File(hostRuntimeDir(filesDir), RUNTIME_READY_NAME)

  /**
   * The same file as the guest reads it.
   *
   * The model directory is bind-mounted rather than copied, so a host path under [hostDir] has
   * an exact guest twin by name — which is how a download the app wrote becomes a file the
   * Python runtime can open without either side holding a second copy.
   */
  fun guestPath(hostFile: File): String = "$GUEST_DIR/${hostFile.name}"
}
