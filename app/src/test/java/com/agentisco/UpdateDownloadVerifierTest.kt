package com.agentisco

import com.agentisco.data.repository.UpdateDownloadVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for the rules that decide whether a downloaded update APK may be
 * offered for install, and how much of it is really on disk.
 */
class UpdateDownloadVerifierTest {

    // ——— progress ———

    @Test
    fun `progress is the fraction of the expected size`() {
        assertEquals(0f, UpdateDownloadVerifier.progress(0L, 1000L), 0f)
        assertEquals(0.5f, UpdateDownloadVerifier.progress(500L, 1000L), 0f)
    }

    @Test
    fun `progress never exceeds one`() {
        assertEquals(1f, UpdateDownloadVerifier.progress(1000L, 1000L, verified = true), 0f)
        val overshoot = UpdateDownloadVerifier.progress(4000L, 1000L)
        assertTrue("overshoot must stay clamped: $overshoot", overshoot <= 1f)
    }

    @Test
    fun `progress stays below one until the file is verified`() {
        // Every byte is present, but nothing confirmed the file yet: the bar must
        // not look finished.
        val unverified = UpdateDownloadVerifier.progress(1000L, 1000L, verified = false)
        assertTrue("unverified progress must stay below 1: $unverified", unverified < 1f)
        assertTrue("unverified progress should still be close to 1: $unverified", unverified > 0.99f)

        assertEquals(1f, UpdateDownloadVerifier.progress(1000L, 1000L, verified = true), 0f)
    }

    @Test
    fun `progress reports zero when the expected size is unknown`() {
        assertEquals(0f, UpdateDownloadVerifier.progress(500L, 0L), 0f)
        assertEquals(0f, UpdateDownloadVerifier.progress(500L, -1L), 0f)
    }

    // ——— resume offset ———

    @Test
    fun `resume offset is the bytes really on disk, never a stale marker`() {
        // The file holds 10 bytes; a legacy marker may claim anything larger. Only
        // the real length may be used as a seek target.
        assertEquals(10L, UpdateDownloadVerifier.resumeOffset(10L, 1000L, existingPrefixIsApk = true))
    }

    @Test
    fun `resume restarts from zero for a partial that cannot be a prefix`() {
        assertEquals(0L, UpdateDownloadVerifier.resumeOffset(10L, 1000L, existingPrefixIsApk = false))
        assertEquals(0L, UpdateDownloadVerifier.resumeOffset(0L, 1000L, existingPrefixIsApk = true))
        // Longer than the asset: it can never be a prefix of it.
        assertEquals(0L, UpdateDownloadVerifier.resumeOffset(5000L, 1000L, existingPrefixIsApk = true))
    }

    // ——— completion decision ———

    @Test
    fun `a stream that ends early is not complete`() {
        val decision = UpdateDownloadVerifier.decide(
            expectedSize = 1000L,
            actualSize = 10L,
            hasApkMagic = true,
            actualPackage = null,
            downloadedFromZero = true
        )
        assertFalse(decision.complete)
        assertTrue(decision.reason!!.contains("expected 1000 bytes, got 10"))
    }

    @Test
    fun `a body longer than the asset is not complete`() {
        val decision = UpdateDownloadVerifier.decide(
            expectedSize = 1000L,
            actualSize = 4000L,
            hasApkMagic = true,
            actualPackage = null,
            downloadedFromZero = true
        )
        assertFalse(decision.complete)
    }

    @Test
    fun `the right size with no apk signature is not complete`() {
        val decision = UpdateDownloadVerifier.decide(
            expectedSize = 1000L,
            actualSize = 1000L,
            hasApkMagic = false,
            actualPackage = null,
            downloadedFromZero = true
        )
        assertFalse(decision.complete)
        assertEquals(UpdateDownloadVerifier.NOT_AN_APK_REASON, decision.reason)
    }

    @Test
    fun `the right size with an apk signature is complete`() {
        val decision = UpdateDownloadVerifier.decide(
            expectedSize = 1000L,
            actualSize = 1000L,
            hasApkMagic = true,
            actualPackage = UpdateDownloadVerifier.EXPECTED_PACKAGE,
            downloadedFromZero = true
        )
        assertTrue(decision.complete)
        assertEquals(null, decision.reason)
    }

    @Test
    fun `an unreadable manifest is treated as unknown, not as a mismatch`() {
        val decision = UpdateDownloadVerifier.decide(
            expectedSize = 1000L,
            actualSize = 1000L,
            hasApkMagic = true,
            actualPackage = null,
            downloadedFromZero = true
        )
        assertTrue(decision.complete)
    }

    @Test
    fun `a positively different package is not complete`() {
        val decision = UpdateDownloadVerifier.decide(
            expectedSize = 1000L,
            actualSize = 1000L,
            hasApkMagic = true,
            actualPackage = "com.someone.else",
            downloadedFromZero = true
        )
        assertFalse(decision.complete)
        assertTrue(decision.reason!!.contains("com.someone.else"))
    }

    @Test
    fun `without a size only a transfer that started at zero is complete`() {
        val fromZero = UpdateDownloadVerifier.decide(
            expectedSize = 0L,
            actualSize = 1000L,
            hasApkMagic = true,
            actualPackage = null,
            downloadedFromZero = true
        )
        assertTrue(fromZero.complete)

        val resumed = UpdateDownloadVerifier.decide(
            expectedSize = 0L,
            actualSize = 1000L,
            hasApkMagic = true,
            actualPackage = null,
            downloadedFromZero = false
        )
        assertFalse(resumed.complete)
    }

    // ——— file signature ———

    @Test
    fun `apk magic is detected on bytes and on files`() {
        val header = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00)
        assertTrue(UpdateDownloadVerifier.hasApkMagic(header))
        assertFalse(UpdateDownloadVerifier.hasApkMagic(byteArrayOf(0x50, 0x4B, 0x03)))
        assertFalse(UpdateDownloadVerifier.hasApkMagic(byteArrayOf(0x00, 0x00, 0x00, 0x00)))

        val apk = File.createTempFile("fake", ".apk")
        try {
            apk.writeBytes(header)
            assertTrue(UpdateDownloadVerifier.hasApkMagic(apk))
            apk.writeBytes(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x00))
            assertFalse(UpdateDownloadVerifier.hasApkMagic(apk))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun `a missing file has no apk magic`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "definitely-missing-${System.nanoTime()}.apk")
        assertFalse(UpdateDownloadVerifier.hasApkMagic(missing))
    }
}
