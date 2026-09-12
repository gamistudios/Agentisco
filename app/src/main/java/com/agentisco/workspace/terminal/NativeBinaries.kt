package com.agentisco.workspace.terminal

import android.content.Context
import java.io.File

/**
 * Resolves the native Linux binaries bundled via jniLibs. Android extracts
 * jniLibs to `nativeLibraryDir`, which — unlike app data — stays executable
 * on every targetSdk (this is why the binaries are named lib*.so).
 */
class NativeBinaries(context: Context) {

  private val dir: String = context.applicationInfo.nativeLibraryDir
  private val linkDir: File = File(context.filesDir, "proot-lib").apply { mkdirs() }

  val proot: File? = find("libproot.so")
  val prootLoader: File? = find("libproot-loader.so")
  val prootLoader32: File? = find("libproot-loader32.so")
  val talloc: File? = find("libtalloc.so")

  // Bundled Debian-based rootfs tarball, shipped per-ABI inside the APK
  // (named lib*.so so Android extracts it alongside the native libraries).
  val rootfs64: File? = find("librootfs64.so")
  val rootfs32: File? = find("librootfs32.so")
  val rootfsArchive: File? get() {
    val primary = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: return rootfs64 ?: rootfs32
    return if (primary == "arm64-v8a") (rootfs64 ?: rootfs32) else (rootfs32 ?: rootfs64)
  }

  fun isComplete(): Boolean = proot != null && prootLoader != null && talloc != null

  fun missingFiles(): List<String> = listOfNotNull(
    if (proot == null) "libproot.so" else null,
    if (prootLoader == null) "libproot-loader.so" else null,
    if (talloc == null) "libtalloc.so" else null
  )

  /** LD_LIBRARY_PATH value so proot can locate libtalloc/libandroid-shmem. */
  val libraryPath: String = dir

  /**
   * proot's ELF dependency is `libtalloc.so.2` (versioned soname), but Android
   * packaging only allows `lib*.so` file names. Creates a versioned symlink in
   * a private dir and returns an LD_LIBRARY_PATH that includes it first.
   */
  fun ensureRuntimeLibraryPath(): String {
    talloc?.let { talloc ->
      val versioned = File(linkDir, "libtalloc.so.2")
      if (!versioned.exists()) {
        runCatching {
          versioned.delete()
          android.system.Os.symlink(talloc.absolutePath, versioned.absolutePath)
        }
      }
    }
    return "${linkDir.absolutePath}:$dir"
  }

  private fun find(name: String): File? = File(dir, name).takeIf { it.exists() }
}
