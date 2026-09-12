package com.agentisco.workspace.terminal

import android.os.Build

/**
 * The Linux rootfs shipped **inside the APK** (per-ABI, via jniLibs). Ubuntu
 * 24.04 base is used because it is Debian-based (real apt/dpkg) and has
 * official arm64/armhf rootfs tarballs. The bundled archive is SHA-256
 * verified before extraction; the source URLs are kept for provenance.
 */
data class RootfsEntry(
  val bundledName: String,
  val fileName: String,
  val sha256: String,
  val distribution: String
)

object RootfsCatalog {

  private val entries = mapOf(
    "arm64-v8a" to RootfsEntry(
      bundledName = "librootfs64.so",
      fileName = "ubuntu-base-24.04.5-base-arm64.tar.gz",
      sha256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
      distribution = "Ubuntu 24.04 LTS (Debian-based)"
    ),
    "armeabi-v7a" to RootfsEntry(
      bundledName = "librootfs32.so",
      fileName = "ubuntu-base-24.04.5-base-armhf.tar.gz",
      sha256 = "4fcee4d278f1c5232e085a021a85e4c6cef3853557a88d98ff380b5e5d5841bb",
      distribution = "Ubuntu 24.04 LTS (Debian-based)"
    )
  )

  /** Picks the entry matching the device's primary ABI; null when unsupported. */
  fun forDevice(): RootfsEntry? {
    val primary = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
    return entries[primary]
  }

  fun forAbi(abi: String): RootfsEntry? = entries[abi]
}
