package com.awaki

import com.awaki.data.repository.UpdateDownloadVerifier
import com.awaki.data.repository.parseUpdateRelease
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The contract between the Update API's document and the app's update check.
 *
 * These field mappings are what keep the switch from the releases API invisible to
 * users: the version comparison, the notes shown before downloading, and the size
 * and digest the finished APK is verified against all come from this parsing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateReleaseParsingTest {

    @Test
    fun `a full service document becomes an update with the service's version code`() {
        val release = parseUpdateRelease(
            JSONObject()
                .put("channel", "release")
                .put("tagName", "v2.0.23")
                .put("versionName", "2.0.23")
                .put("versionCode", 2_00_23L)
                .put("apkName", "Awaki-v2.0.23-release.apk")
                .put("size", 70_161_981L)
                .put("sha256", "sha256:82A6E7BC53AB09EBF59AB6E7C5C8B745E69B639C71EC91E17863241541322B19")
                .put("releaseNotes", "## What's new\n- Faster sync")
                .put("downloadUrl", "https://example.invalid/v1/download/release/latest")
        )

        assertEquals("v2.0.23", release.tagName)
        assertEquals("2.0.23", release.versionName)
        assertEquals(2_00_23L, release.versionCode)
        assertEquals(70_161_981L, release.assetSize)
        assertEquals("Awaki-v2.0.23-release.apk", release.assetName)
        assertEquals("## What's new\n- Faster sync", release.releaseNotes)
        assertEquals("https://example.invalid/v1/download/release/latest", release.downloadUrl)
        // The service publishes the digest in its own notation; the app compares
        // lowercase hex, exactly as it hashes the finished file.
        assertEquals(
            "82a6e7bc53ab09ebf59ab6e7c5c8b745e69b639c71ec91e17863241541322b19",
            release.assetDigest
        )
    }

    @Test
    fun `a deployment that omits the version code falls back to the tag`() {
        val release = parseUpdateRelease(
            JSONObject()
                .put("tagName", "v2.0.23")
                .put("downloadUrl", "https://example.invalid/latest"),
            fallback = { tag -> tag.removePrefix("v").split(".").let { it[0].toLong() * 10000 + it[1].toLong() * 100 + it[2].toLong() } }
        )

        assertEquals(2_00_23L, release.versionCode)
        // Nothing else in the document: the UI must still have a name to show and
        // the downloader must not invent a checksum it cannot satisfy.
        assertEquals("v2.0.23", release.versionName)
        assertEquals(0L, release.assetSize)
        assertNull(release.assetDigest)
    }

    @Test
    fun `an unusable digest is dropped rather than trusted`() {
        val truncated = parseUpdateRelease(
            JSONObject()
                .put("tagName", "v1.0.0")
                .put("downloadUrl", "https://example.invalid/latest")
                .put("sha256", "sha256:not-a-digest")
        )
        assertNull(truncated.assetDigest)

        val bare = parseUpdateRelease(
            JSONObject()
                .put("tagName", "v1.0.0")
                .put("downloadUrl", "https://example.invalid/latest")
                .put("sha256", "F".repeat(64))
        )
        assertEquals("f".repeat(64), bare.assetDigest)
    }

    @Test
    fun `a document with no download URL is refused`() {
        val failure = runCatching {
            parseUpdateRelease(JSONObject().put("tagName", "v2.0.0").put("versionName", "2.0.0"))
        }.exceptionOrNull()

        assertTrue(
            "the check must fail with a message the error state can show: ${failure?.message}",
            failure?.message?.contains("download URL") == true
        )
    }

    @Test
    fun `digest normalization is shared with the download verifier`() {
        // The parser must not grow its own idea of what a digest looks like.
        assertEquals(
            UpdateDownloadVerifier.normalizeDigest("sha256:" + "a".repeat(64)),
            parseUpdateRelease(
                JSONObject()
                    .put("tagName", "v1.0.0")
                    .put("downloadUrl", "https://example.invalid/latest")
                    .put("sha256", "sha256:" + "a".repeat(64))
            ).assetDigest
        )
    }
}
