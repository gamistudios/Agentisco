package com.awaki

import com.awaki.local.py.BundleResolution
import com.awaki.local.py.RuntimeBundle
import com.awaki.local.py.RuntimeBundleCatalog
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runtime catalog as the app reads it.
 *
 * The file is written next to a 50 MB archive by `tools/local-runtime/build-awaki-runtime.sh`,
 * and a committed pair of those drifts sooner or later: a truncated digest, an entry for an ABI
 * whose archive never landed, a schema that grew a field the installed app has never seen. Each
 * of those is a phone that cannot run a model, so they are test cases here rather than something
 * only the device finds out.
 */
class LocalRuntimeCatalogTest {

  private val digest = "a".repeat(63) + "f"

  private val catalog = """
    {
      "schema": 1,
      "bundles": [
        { "abi": "arm64-v8a", "arch": "aarch64", "asset": "local-runtime/runtime-aarch64.tar.gz",
          "sha256": "$digest", "sizeBytes": 52428800, "python": "3.12.15",
          "llamaCpp": "0.3.36", "bundleId": "20261003-a", "generatedBy": "build-awaki-runtime.sh" },
        { "abi": "armeabi-v7a", "arch": "armv7l", "asset": "local-runtime/runtime-armv7.tar.gz",
          "sha256": "${"b".repeat(64)}", "sizeBytes": 44000000, "python": "3.12.15",
          "llamaCpp": "0.3.36", "bundleId": "20261003-b" }
      ]
    }
  """.trimIndent()

  private fun resolve(abis: List<String>, text: String?): BundleResolution =
    RuntimeBundleCatalog.resolve({ text?.let { ByteArrayInputStream(it.toByteArray()) } }, abis)

  @Test
  fun `a catalog lists every bundle it holds`() {
    val bundles = RuntimeBundleCatalog.parse(catalog)

    assertEquals(2, bundles.size)
    assertEquals("arm64-v8a", bundles.first().abi)
    assertEquals(digest, bundles.first().sha256)
    assertEquals(52428800L, bundles.first().sizeBytes)
  }

  /** The screen names what is installed, so the versions have to survive the round trip. */
  @Test
  fun `a bundle describes itself for the screen`() {
    assertEquals("Python 3.12.15 · llama-cpp-python 0.3.36", RuntimeBundleCatalog.parse(catalog).first().detail)
  }

  /** Unknown keys are the normal case for a catalog written by a newer script. */
  @Test
  fun `an entry that carries a field this build does not know still parses`() {
    assertTrue(RuntimeBundleCatalog.parse(catalog).first().bundleId.startsWith("20261003"))
  }

  @Test
  fun `an entry without a full digest is dropped instead of trusted`() {
    val broken = """{"schema":1,"bundles":[{"abi":"arm64-v8a","arch":"aarch64",
      "asset":"local-runtime/a.tar.gz","sha256":"deadbeef","sizeBytes":1,"python":"3.12",
      "llamaCpp":"0.3.36","bundleId":"x"}]}"""

    assertTrue(RuntimeBundleCatalog.parse(broken).isEmpty())
  }

  @Test
  fun `an entry naming no asset is dropped`() {
    val broken = catalog.replace("\"asset\": \"local-runtime/runtime-aarch64.tar.gz\"", "\"asset\": \"\"")

    assertEquals("only the armv7 entry is readable", listOf("armeabi-v7a"),
      RuntimeBundleCatalog.parse(broken).map { it.abi })
  }

  @Test
  fun `an unreadable catalog reads as no catalog`() {
    assertTrue(RuntimeBundleCatalog.parse(null).isEmpty())
    assertTrue(RuntimeBundleCatalog.parse("").isEmpty())
    assertTrue(RuntimeBundleCatalog.parse("{ this is not json").isEmpty())
    assertTrue(RuntimeBundleCatalog.parse("""{"schema":1,"bundles":[]}""").isEmpty())
  }

  /** An arm64 phone also lists 32-bit ABIs; the first of its own width has to win. */
  @Test
  fun `the device's primary abi picks the bundle`() {
    val bundles = RuntimeBundleCatalog.parse(catalog)

    assertEquals("arm64-v8a", RuntimeBundleCatalog.entryFor(listOf("arm64-v8a", "armeabi-v7a"), bundles)?.abi)
    assertEquals("armeabi-v7a", RuntimeBundleCatalog.entryFor(listOf("armeabi-v7a"), bundles)?.abi)
    assertNull(RuntimeBundleCatalog.entryFor(listOf("x86_64"), bundles))
  }

  @Test
  fun `resolution says which of the three problems this is`() {
    assertEquals(BundleResolution.Found(RuntimeBundleCatalog.parse(catalog).first()), resolve(listOf("arm64-v8a"), catalog))
    assertEquals(BundleResolution.NoCatalog, resolve(listOf("arm64-v8a"), null))
    assertEquals(BundleResolution.UnsupportedAbi(listOf("x86")), resolve(listOf("x86"), catalog))
  }

  /** A build whose asset is simply not there must not throw on the way to the screen. */
  @Test
  fun `a missing catalog asset is a clean resolution rather than an exception`() {
    assertEquals(BundleResolution.NoCatalog, RuntimeBundleCatalog.resolve({ throw java.io.FileNotFoundException("bundles.json") }, listOf("arm64-v8a")))
  }
}
