package com.agentisco.workspace.terminal

import java.io.File

/**
 * Builds the proot command line that turns the downloaded Debian-based rootfs
 * into a usable Linux environment. `-0` fakes uid 0 inside the sandbox so apt
 * can install packages without a rooted device (same approach as proot-distro).
 */
class ProotArgsBuilder(
  private val nativeBinaries: NativeBinaries,
  private val rootfsDir: File
) {

  /**
   * Returns the host-side argv (proot + guest command) and the host environment
   * variables proot itself needs (loader paths, bundled shared libraries).
   */
  fun buildCommand(
    guestCommand: List<String>,
    workingDir: String = "/root",
    extraGuestEnv: Map<String, String> = emptyMap()
  ): Pair<List<String>, Map<String, String>> {
    val proot = requireNotNull(nativeBinaries.proot) { "proot binary is missing" }
    val loader = nativeBinaries.prootLoader?.absolutePath ?: ""
    val loader32 = nativeBinaries.prootLoader32?.absolutePath ?: ""

    val binds = mutableListOf(
      "/dev" to "/dev",
      "/proc" to "/proc",
      "/sys" to "/sys"
    )
    if (File("/storage/emulated/0").exists()) {
      binds += "/storage/emulated/0" to "/sdcard"
    }

    val args = mutableListOf(
      proot.absolutePath,
      "--kill-on-exit",
      "--link2symlink",
      "-0",
      "-w", workingDir,
      "-r", rootfsDir.absolutePath
    )
    binds.forEach { (from, to) -> args += listOf("-b", "$from:$to") }
    args += guestCommand

    val hostEnv = mapOf(
      "PROOT_LOADER" to loader,
      "PROOT_LOADER32" to loader32,
      "PROOT_TMP_DIR" to (rootfsDir.parent ?: "/data/local/tmp"),
      "LD_LIBRARY_PATH" to nativeBinaries.ensureRuntimeLibraryPath(),
      "PROOT_NO_SECCOMP" to "1"
    )
    return args to hostEnv
  }

  /** Environment for `/usr/bin/env -i` inside the rootfs. */
  fun guestEnv(home: String = "/root", extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
    put("HOME", home)
    put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
    put("TERM", "xterm-256color")
    put("LANG", "C.UTF-8")
    put("SHELL", "/bin/bash")
    putAll(extra)
  }
}
