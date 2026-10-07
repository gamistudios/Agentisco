package com.awaki.local.py

import java.io.File
import java.io.InputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One prebuilt Python runtime, as the catalog describes it.
 *
 * The bundle is assembled by `tools/local-runtime/build-awaki-runtime.sh` out of prebuilt wheels —
 * nothing is compiled, anywhere — and ships inside the APK, so a phone only ever has to check the
 * bytes it was given and unpack them. The digest is both the integrity check and the identity: a
 * build of the app that carries a different archive holds a different runtime, and the installer
 * replaces the old one without being asked.
 */
@Serializable
data class RuntimeBundle(
  /** Android ABI this bundle belongs to, matched against `Build.SUPPORTED_ABIS`. */
  val abi: String,
  /** The architecture the guest reports for it — `uname -m` inside the rootfs. */
  val arch: String,
  /** The archive, as a path under the app's assets. */
  val asset: String,
  val sha256: String,
  val sizeBytes: Long,
  val python: String,
  @SerialName("llamaCpp") val llamaCpp: String,
  val bundleId: String
) {

  /** What the screen says about a runtime that is in place. */
  val detail: String get() = "Python $python · llama-cpp-python $llamaCpp"
}

/**
 * The runtime catalog the APK ships, read from `assets/local-runtime/bundles.json`.
 *
 * Parsing is a pure function of the JSON text so a malformed or partial catalog is a test case
 * rather than a device-only failure: the file is written by the build script and committed
 * beside a 50 MB archive, which is exactly the pair that drifts.
 */
object RuntimeBundleCatalog {

  const val ASSET_PATH = "local-runtime/bundles.json"

  @Serializable
  internal data class Catalog(
    val schema: Int = 0,
    val bundles: List<RuntimeBundle> = emptyList()
  )

  private val json = Json { ignoreUnknownKeys = true }

  /** Every usable entry; an unreadable or empty catalog yields none rather than throwing. */
  fun parse(text: String?): List<RuntimeBundle> {
    val catalog = text?.let { runCatching { json.decodeFromString<Catalog>(it) }.getOrNull() }
      ?: return emptyList()
    return catalog.bundles.filter { entry ->
      entry.abi.isNotBlank() && entry.asset.isNotBlank() && entry.sha256.length == 64 &&
        entry.sha256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
  }

  fun parse(catalogFile: File): List<RuntimeBundle> =
    parse(runCatching { catalogFile.readText() }.getOrNull())

  /**
   * The bundle for this device, from its ABI list.
   *
   * The primary ABI wins: an arm64 phone wants the aarch64 runtime even when its list also
   * mentions 32-bit. A device with no entry gets null, which the installer reports as an
   * unsupported ABI rather than failing a build it has no compiler for.
   */
  fun entryFor(abis: List<String>, bundles: List<RuntimeBundle>): RuntimeBundle? =
    abis.firstNotNullOfOrNull { abi -> bundles.firstOrNull { it.abi == abi } }

  /**
   * Reads the catalog and matches it against the device, which is the whole of "what should this
   * phone install". Whether the archive it names is actually in the build is a separate question
   * the installer answers with its own reason, so this stays a catalogue lookup.
   */
  fun resolve(openCatalog: () -> InputStream?, abis: List<String>): BundleResolution {
    val text = runCatching { openCatalog()?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
    val bundles = parse(text)
    if (bundles.isEmpty()) return BundleResolution.NoCatalog
    return entryFor(abis, bundles)?.let { BundleResolution.Found(it) }
      ?: BundleResolution.UnsupportedAbi(abis)
  }
}
