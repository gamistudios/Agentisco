package com.awaki

import com.awaki.local.py.RuntimeBundleCatalog
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runtime archive and the catalog that names it are one pair, committed together by
 * `tools/local-runtime/build-awaki-runtime.sh`, and the pair is what drifts: the script writes the
 * digest into the JSON, and a rebuild that lands only one of the two files leaves every phone
 * looking for bytes that are not in its APK.
 *
 * This reads the catalog the way the installer does and checks each entry against the file beside
 * it, so a stale digest, a renamed archive or an empty `bundles.json` fails a build instead of
 * surfacing as "no runtime for this ABI" on a device.
 */
class ShippedRuntimeBundleTest {

  @Test
  fun `the catalog shipped in the assets names real archives`() {
    val assets = File(sourceSet(), "assets")
    val catalog = File(assets, RuntimeBundleCatalog.ASSET_PATH)
    assertTrue("There is no $RUNTIME_DIR/bundles.json in the build", catalog.isFile)

    val bundles = RuntimeBundleCatalog.parse(catalog)
    assertTrue("assets/$RUNTIME_DIR/bundles.json names no bundle the installer could use", bundles.isNotEmpty())

    for (bundle in bundles) {
      // The name has to survive the asset pipeline as well as the catalog. A `.gz` asset is inflated
      // and republished under the stripped name during the build, so the archive the catalog points
      // at would not exist in the APK - which is why the recipe names these `.pack`.
      assertTrue(
        "${bundle.asset} is named for a codec the build rewrites; ship the gzip bytes under an " +
          "extension the asset pipeline leaves alone",
        !bundle.asset.endsWith(".gz")
      )

      val archive = File(assets, bundle.asset)
      assertTrue(
        "bundles.json names ${bundle.asset} for ${bundle.abi}, and this build does not carry it",
        archive.isFile
      )
      assertEquals(
        "bundles.json says ${bundle.asset} is ${bundle.sizeBytes} bytes; it is ${archive.length()}",
        bundle.sizeBytes,
        archive.length()
      )
      assertEquals(
        "the archive beside this catalog is not the one bundles.json hashes, so every device would " +
          "fail its checksum and install nothing",
        bundle.sha256.lowercase(),
        sha256(archive)
      )
    }
  }

  /** One SHA-256 over the streamed bytes, which is what the installer hashes as it unpacks. */
  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered(1 shl 16).use { stream ->
      val buffer = ByteArray(1 shl 16)
      while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  /**
   * Gradle may run a unit test from anywhere under the module, so the source set is found by
   * walking up from the working directory rather than assumed.
   */
  private fun sourceSet(): File =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .flatMap { dir -> sequenceOf(File(dir, "src/main"), File(dir, "app/src/main")) }
      .firstOrNull { it.isDirectory }
      ?: error("No src/main below ${File("").absolutePath} — the scan looked in the wrong place")

  private companion object {
    const val RUNTIME_DIR = "local-runtime"
  }
}
