package com.agentisco.workspace.terminal

import android.os.Build

/**
 * The Linux rootfs distributed with the terminal. Ubuntu 24.04 base is used
 * because it is Debian-based (real apt/dpkg), ships official gzip-compressed
 * arm64/armhf rootfs tarballs, and needs no on-device xz decompressor.
 */
data class RootfsEntry(
  val fileName: String,
  val url: String,
  val sha256: String,
  val sizeBytes: Long,
  val distribution: String
)

object RootfsCatalog {

  private val entries = mapOf(
    "arm64-v8a" to RootfsEntry(
      fileName = "ubuntu-base-24.04.5-base-arm64.tar.gz",
      url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
      sha256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
      sizeBytes = 29_936_675L,
      distribution = "Ubuntu 24.04 LTS (Debian-based)"
    ),
    "armeabi-v7a" to RootfsEntry(
      fileName = "ubuntu-base-24.04.5-base-armhf.tar.gz",
      url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-armhf.tar.gz",
      sha256 = "4fcee4d278f1c5232e085a021a85e4c6cef3853557a88d98ff380b5e5d5841bb",
      sizeBytes = 30_000_000L,
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
