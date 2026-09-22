package com.agentisco

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agentisco.data.repository.UpdateReleaseSource
import com.agentisco.data.repository.UpdateRepository
import com.agentisco.data.repository.UpdateStream
import com.agentisco.data.repository.UpdateStreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Collections

/**
 * Regression tests for "the update looks finished after a few percent".
 *
 * The old download loop compared its own byte counter against the file length it had
 * just written, so any HTTP body that ended — even one truncated by a dropped
 * connection — counted as a complete download and enabled the Install button. The
 * APK bytes are served from memory here so the retry/resume/verify state machine can
 * be exercised without touching the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateDownloadVerificationTest {

    private lateinit var context: Context

    /** Mirrors UpdateRepository's private UPDATE_APK_NAME constant. */
    private val updateFile: File
        get() = File(context.filesDir, "agentisco-update.apk")

    private val offsetSidecar: File
        get() = File(updateFile.parentFile, "${updateFile.name}.offset")

    /** Mirrors UpdateRepository's private UPDATE_META_NAME completion marker. */
    private val markerFile: File
        get() = File(context.filesDir, "agentisco-update.meta.json")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        updateFile.delete()
        offsetSidecar.delete()
        markerFile.delete()
    }

    // ——— the reported bug: early completion ———

    @Test
    fun `a stream that ends early is never reported as downloaded`() = runTest {
        val payload = apkPayload(4096)
        val source = InMemoryStreamSource(payload, bytesAvailable = 16)
        val repository = repository(source)
        repository.adoptAvailableUpdate(update(assetSize = payload.size.toLong()))

        assertFalse(repository.downloadUpdate())

        assertEquals(UpdateRepository.UpdateState.ERROR, repository.updateState.value)
        assertNotNull(repository.updateError.value)
        assertTrue(
            "size mismatch must be reported: ${repository.updateError.value}",
            repository.updateError.value!!.contains("expected ${payload.size} bytes")
        )
        assertNull("no install path may be exposed", repository.downloadedApkPath.value)
        assertFalse("the unusable partial must be removed", updateFile.exists())
        assertTrue(repository.updateProgress.value < 1f)
    }

    @Test
    fun `a file of the right size that is not an apk is never reported as downloaded`() = runTest {
        val expectedSize = 4096L
        // Right size, but no ZIP/APK signature at all.
        val payload = ByteArray(expectedSize.toInt())
        val repository = repository(InMemoryStreamSource(payload))
        repository.adoptAvailableUpdate(update(assetSize = expectedSize))

        assertFalse(repository.downloadUpdate())

        assertEquals(UpdateRepository.UpdateState.ERROR, repository.updateState.value)
        assertTrue(
            "must be reported as not an APK: ${repository.updateError.value}",
            repository.updateError.value!!.contains("not a valid APK")
        )
        assertNull(repository.downloadedApkPath.value)
        assertFalse(updateFile.exists())
    }

    @Test
    fun `a body larger than the release asset is rejected`() = runTest {
        val payload = apkPayload(8192)
        val repository = repository(InMemoryStreamSource(payload))
        repository.adoptAvailableUpdate(update(assetSize = 4096L))

        assertFalse(repository.downloadUpdate())

        assertEquals(UpdateRepository.UpdateState.ERROR, repository.updateState.value)
        assertFalse(updateFile.exists())
    }

    // ——— the happy path ———

    @Test
    fun `a complete apk of exactly the expected size reaches DOWNLOADED and offers install`() = runTest {
        val payload = apkPayload(4096)
        val repository = repository(InMemoryStreamSource(payload))
        repository.adoptAvailableUpdate(update(assetSize = payload.size.toLong()))

        assertTrue(repository.downloadUpdate())

        assertEquals(UpdateRepository.UpdateState.DOWNLOADED, repository.updateState.value)
        assertNull(repository.updateError.value)
        assertEquals(1f, repository.updateProgress.value, 0f)
        assertEquals(updateFile.absolutePath, repository.downloadedApkPath.value)
        assertArrayEquals(payload, updateFile.readBytes())
    }

    @Test
    fun `progress never exceeds one and stays below it until the file is verified`() = runTest {
        val payload = apkPayload(64 * 1024)
        val repository = repository(InMemoryStreamSource(payload, bytesAvailable = 4096))
        repository.adoptAvailableUpdate(update(assetSize = payload.size.toLong()))

        val progressValues = Collections.synchronizedList(mutableListOf<Float>())
        backgroundScope.launch(Dispatchers.Unconfined) {
            repository.updateProgress.collect { progressValues.add(it) }
        }

        assertFalse(repository.downloadUpdate())

        assertTrue("expected some progress reports", progressValues.isNotEmpty())
        assertTrue(
            "progress must never exceed 1.0: $progressValues",
            progressValues.all { it in 0f..1f }
        )
        assertTrue(
            "a failed download must never report completion: $progressValues",
            progressValues.none { it >= 1f }
        )
    }

    // ——— resume safety ———

    @Test
    fun `a stale resume marker cannot push the writer past the real end of the file`() = runTest {
        val payload = apkPayload(8192)
        // Only 10 real bytes on disk, while the legacy sidecar claims 5000. The old
        // code seeked to 5000 on the fresh file and produced a sparse, oversized APK.
        updateFile.writeBytes(payload.copyOfRange(0, 10))
        offsetSidecar.writeText("5000")

        val source = InMemoryStreamSource(payload)
        val repository = repository(source)
        repository.adoptAvailableUpdate(update(assetSize = payload.size.toLong()))

        assertTrue(repository.downloadUpdate())

        assertEquals("must resume from the bytes really on disk", listOf(10L), source.requestedOffsets)
        assertEquals("file must be exactly the asset size", payload.size.toLong(), updateFile.length())
        assertArrayEquals(payload, updateFile.readBytes())
        assertEquals(UpdateRepository.UpdateState.DOWNLOADED, repository.updateState.value)
    }

    @Test
    fun `a partial file that is not an apk prefix is discarded and fetched from zero`() = runTest {
        val payload = apkPayload(4096)
        updateFile.writeBytes(ByteArray(10))
        offsetSidecar.writeText("4096")

        val source = InMemoryStreamSource(payload)
        val repository = repository(source)
        repository.adoptAvailableUpdate(update(assetSize = payload.size.toLong()))

        assertTrue(repository.downloadUpdate())

        assertEquals(listOf(0L), source.requestedOffsets)
        assertArrayEquals(payload, updateFile.readBytes())
    }

    @Test
    fun `a server that ignores the Range header restarts from zero instead of appending`() = runTest {
        val payload = apkPayload(8192)
        updateFile.writeBytes(payload.copyOfRange(0, 4096))

        val source = InMemoryStreamSource(payload, ignoreRange = true)
        val repository = repository(source)
        repository.adoptAvailableUpdate(update(assetSize = payload.size.toLong()))

        assertTrue(repository.downloadUpdate())

        assertEquals("the first attempt may ask to resume", listOf(4096L), source.requestedOffsets)
        assertEquals(
            "the whole body must have replaced the partial, not been appended to it",
            payload.size.toLong(),
            updateFile.length()
        )
        assertArrayEquals(payload, updateFile.readBytes())
    }

    // ——— size fallbacks ———

    @Test
    fun `a release without an asset size falls back to the response length`() = runTest {
        val payload = apkPayload(2048)
        val repository = repository(InMemoryStreamSource(payload))
        repository.adoptAvailableUpdate(update(assetSize = 0L))
        assertEquals(UpdateRepository.UpdateState.AVAILABLE, repository.updateState.value)

        assertTrue(repository.downloadUpdate())

        assertEquals(UpdateRepository.UpdateState.DOWNLOADED, repository.updateState.value)
        assertEquals(1f, repository.updateProgress.value, 0f)
        assertEquals(payload.size.toLong(), updateFile.length())
    }

    @Test
    fun `a retry resumes from the partial file and completes the transfer`() = runTest {
        val payload = apkPayload(4096)
        // Each response only ever delivers 2048 bytes from the requested offset, so
        // the first attempt stops short and the second one has to resume it.
        val source = InMemoryStreamSource(payload, bytesAvailable = 2048)
        val repository = repository(source)
        repository.adoptAvailableUpdate(update(assetSize = payload.size.toLong()))

        assertTrue(repository.downloadUpdate())

        assertEquals(listOf(0L, 2048L), source.requestedOffsets)
        assertArrayEquals(payload, updateFile.readBytes())
        assertEquals(UpdateRepository.UpdateState.DOWNLOADED, repository.updateState.value)
    }

    @Test
    fun `a truncated stream with only a response length is rejected`() = runTest {
        val payload = apkPayload(2048)
        // The response advertises 4096 bytes but the payload is only 2048, so every
        // attempt ends short of the length the server itself reported.
        val repository = repository(InMemoryStreamSource(payload, sizeHint = 4096L))
        repository.adoptAvailableUpdate(update(assetSize = 0L))

        assertFalse(repository.downloadUpdate())

        assertEquals(UpdateRepository.UpdateState.ERROR, repository.updateState.value)
        assertNull(repository.downloadedApkPath.value)
        assertFalse(updateFile.exists())
    }

    @Test
    fun `with no size information a partial file is refetched from zero`() = runTest {
        val payload = apkPayload(1024)
        updateFile.writeBytes(payload.copyOfRange(0, 512))

        val source = InMemoryStreamSource(payload, sizeHint = -1L)
        val repository = repository(source)
        repository.adoptAvailableUpdate(update(assetSize = 0L))

        assertTrue(repository.downloadUpdate())

        assertEquals("a partial file cannot be trusted without a size", listOf(0L), source.requestedOffsets)
        assertArrayEquals(payload, updateFile.readBytes())
    }

    // ——— the checksum: proof that every byte arrived ———

    @Test
    fun `a file of the right size whose bytes do not match the digest is not complete`() = runTest {
        val payload = apkPayload(4096)
        val tampered = payload.copyOf().apply { this[4] = (this[4] + 1).toByte() }
        // A full-length transfer that hashes to something else — a sparse hole or a
        // swapped leftover — must never be marked downloaded.
        val repository = repository(InMemoryStreamSource(payload))
        repository.adoptAvailableUpdate(
            update(assetSize = payload.size.toLong(), assetDigest = sha256Hex(tampered))
        )

        assertFalse(repository.downloadUpdate())

        assertEquals(UpdateRepository.UpdateState.ERROR, repository.updateState.value)
        assertTrue(
            "checksum failure must be reported: ${repository.updateError.value}",
            repository.updateError.value!!.contains("checksum")
        )
        assertNull(repository.downloadedApkPath.value)
        assertFalse(updateFile.exists())
        assertFalse(markerFile.exists())
    }

    @Test
    fun `every part of a resumed download must be present for the digest to pass`() = runTest {
        val payload = apkPayload(8192)
        // Each response delivers 2048 bytes, so completion takes four resumptions;
        // only the last one can hash to the whole asset.
        val source = InMemoryStreamSource(payload, bytesAvailable = 2048)
        val repository = repository(source)
        repository.adoptAvailableUpdate(
            update(assetSize = payload.size.toLong(), assetDigest = sha256Hex(payload))
        )

        assertTrue(repository.downloadUpdate())

        assertEquals(listOf(0L, 2048L, 4096L, 6144L), source.requestedOffsets)
        assertArrayEquals(payload, updateFile.readBytes())
        assertEquals(UpdateRepository.UpdateState.DOWNLOADED, repository.updateState.value)
        assertTrue(markerFile.exists())
    }

    // ——— surviving a restart ———

    @Test
    fun `a verified apk already on disk makes the adopted release offer install`() = runTest {
        val payload = apkPayload(4096)
        val first = repository(InMemoryStreamSource(payload))
        first.adoptAvailableUpdate(update(assetSize = payload.size.toLong()))
        assertTrue(first.downloadUpdate())
        assertTrue(markerFile.exists())

        // A fresh repository — standing in for a new app process over the same
        // filesDir — must recognise the finished download and offer install.
        val restarted = repository(InMemoryStreamSource(ByteArray(0)))
        val state = restarted.adoptAvailableUpdate(update(assetSize = payload.size.toLong()))

        assertEquals(UpdateRepository.UpdateState.DOWNLOADED, state)
        assertEquals(updateFile.absolutePath, restarted.downloadedApkPath.value)
        assertEquals(1f, restarted.updateProgress.value, 0f)
    }

    @Test
    fun `a leftover no completed download produced is not offered for install`() {
        // Regression: full-length APK-shaped leftovers from the old sparse-file bug
        // used to be adopted straight into DOWNLOADED on size + magic alone.
        updateFile.writeBytes(apkPayload(4096))

        val repository = repository(InMemoryStreamSource(ByteArray(0)))
        val state = repository.adoptAvailableUpdate(update(assetSize = 4096L))

        assertEquals(UpdateRepository.UpdateState.AVAILABLE, state)
        assertNull(repository.downloadedApkPath.value)
    }

    @Test
    fun `a marker from a different release does not offer the leftover for install`() = runTest {
        val payload = apkPayload(4096)
        val repository = repository(InMemoryStreamSource(payload))
        repository.adoptAvailableUpdate(update(assetSize = payload.size.toLong()))
        assertTrue(repository.downloadUpdate())

        // Same bytes on disk, but the release asset has moved on: the download has
        // to run again instead of offering the stale file for install.
        val movedOn = repository(InMemoryStreamSource(ByteArray(0)))
        val state = movedOn.adoptAvailableUpdate(
            update(assetSize = payload.size.toLong(), assetDigest = sha256Hex(payload))
        )

        assertEquals(UpdateRepository.UpdateState.AVAILABLE, state)
        assertNull(movedOn.downloadedApkPath.value)
    }

    @Test
    fun `a leftover of the wrong size keeps offering download and stays resumable`() {
        updateFile.writeBytes(apkPayload(4096).copyOfRange(0, 1024))

        val repository = repository(InMemoryStreamSource(ByteArray(0)))
        val state = repository.adoptAvailableUpdate(update(assetSize = 4096L))

        assertEquals(UpdateRepository.UpdateState.AVAILABLE, state)
        assertNull(repository.downloadedApkPath.value)
        assertTrue("the partial must be kept for resuming", updateFile.exists())
    }

    @Test
    fun `a leftover of the right size that is not an apk is not offered for install`() {
        updateFile.writeBytes(ByteArray(4096))

        val repository = repository(InMemoryStreamSource(ByteArray(0)))
        val state = repository.adoptAvailableUpdate(update(assetSize = 4096L))

        assertEquals(UpdateRepository.UpdateState.AVAILABLE, state)
        assertNull(repository.downloadedApkPath.value)
    }

    // ——— the installer gate ———

    @Test
    fun `install refuses a file that was corrupted after the download`() = runTest {
        val payload = apkPayload(4096)
        val repository = repository(InMemoryStreamSource(payload))
        repository.adoptAvailableUpdate(update(assetSize = payload.size.toLong()))
        assertTrue(repository.downloadUpdate())

        // Something truncated the file after it was marked ready.
        updateFile.writeBytes(ByteArray(4096))

        assertFalse(repository.installDownloadedApk())
        assertEquals(UpdateRepository.UpdateState.AVAILABLE, repository.updateState.value)
        assertNotNull(repository.updateError.value)
        assertFalse(updateFile.exists())
        assertFalse("the completion marker must not outlive its file", markerFile.exists())
    }

    // ——— a newer release discards what older ones left on disk ———

    @Test
    fun `a newer update deletes an old downloaded apk from disk`() = runTest {
        // A previous run finished and verified an older release; its apk and its
        // completion marker are still on disk when the next check finds v2.0.0.
        updateFile.writeBytes(apkPayload(4096))
        markerFile.writeText(
            JSONObject()
                .put("url", "https://github.com/gamistudios/Agentisco/releases/download/v1.0.0/agentisco-debug.apk")
                .put("size", 4096)
                .put("digest", "")
                .put("versionCode", 10_000L) // what parseVersionCode("v1.0.0") wrote
                .toString()
        )
        val repository = repository(
            InMemoryStreamSource(ByteArray(0)),
            releaseSource("v2.0.0")
        )

        assertTrue(repository.checkForUpdates())

        assertFalse("the stale apk must be deleted", updateFile.exists())
        assertFalse("the stale marker must be deleted with it", markerFile.exists())
        assertFalse(repository.hasUpdateFileOnDisk())
        assertNull(repository.downloadedApkPath.value)
        assertEquals(UpdateRepository.UpdateState.AVAILABLE, repository.updateState.value)
    }

    @Test
    fun `a stale download from a build that predates version markers is also deleted`() = runTest {
        updateFile.writeBytes(apkPayload(4096))
        // A marker written before versionCode was tracked has no versionCode field.
        markerFile.writeText(
            JSONObject()
                .put("url", "https://github.com/gamistudios/Agentisco/releases/download/v1.0.0/agentisco-debug.apk")
                .put("size", 4096)
                .put("digest", "")
                .toString()
        )
        val repository = repository(
            InMemoryStreamSource(ByteArray(0)),
            releaseSource("v2.0.0")
        )

        assertTrue(repository.checkForUpdates())

        assertFalse(updateFile.exists())
        assertFalse(markerFile.exists())
    }

    @Test
    fun `an update check that finds the very release on disk keeps its finished download`() = runTest {
        val payload = apkPayload(4096)
        updateFile.writeBytes(payload)
        markerFile.writeText(
            JSONObject()
                .put("url", "https://github.com/gamistudios/Agentisco/releases/download/v2.0.0/agentisco-debug.apk")
                .put("size", 4096)
                .put("digest", "")
                .put("versionCode", 20_000L) // what parseVersionCode("v2.0.0") wrote
                .toString()
        )
        val repository = repository(
            InMemoryStreamSource(ByteArray(0)),
            releaseSource("v2.0.0", assetSize = 4096L)
        )

        assertTrue(repository.checkForUpdates())

        assertTrue(
            "a finished download of the offered release must survive the check",
            updateFile.exists()
        )
        assertTrue(markerFile.exists())
        assertEquals(UpdateRepository.UpdateState.DOWNLOADED, repository.updateState.value)
        assertEquals(updateFile.absolutePath, repository.downloadedApkPath.value)
    }

    @Test
    fun `a finished download that was deleted re-adopts as AVAILABLE, not install`() = runTest {
        val payload = apkPayload(4096)
        val repository = repository(InMemoryStreamSource(ByteArray(0)))
        val deletedRepository = repository(InMemoryStreamSource(payload))
        deletedRepository.adoptAvailableUpdate(update(assetSize = payload.size.toLong()))
        assertTrue(deletedRepository.downloadUpdate())

        deletedRepository.deleteDownloadedUpdate()

        // Same release, nothing left on disk: the UI must offer Download again.
        repository.adoptAvailableUpdate(update(assetSize = payload.size.toLong()))
        assertEquals(UpdateRepository.UpdateState.AVAILABLE, repository.updateState.value)
        assertNull(repository.downloadedApkPath.value)
    }

    // ——— helpers ———

    private fun repository(
        source: UpdateStreamSource,
        releaseSource: UpdateReleaseSource = UpdateReleaseSource {
            error("test reached the network: inject a releaseSource when calling checkForUpdates")
        }
    ): UpdateRepository =
        UpdateRepository(
            context,
            streamSource = source,
            releaseSource = releaseSource,
            retryDelayMs = 0L
        )

    /** Serves a crafted "latest release" payload to checkForUpdates — no network. */
    private fun releaseSource(tagName: String, assetSize: Long = 0L): UpdateReleaseSource =
        UpdateReleaseSource {
            JSONObject()
                .put("tag_name", tagName)
                .put("name", tagName)
                .put("body", "")
                .put(
                    "assets",
                    JSONArray().put(
                        JSONObject()
                            .put("name", "agentisco-debug.apk")
                            .put("size", assetSize)
                            .put("digest", "")
                    )
                )
        }

    private fun update(assetSize: Long, assetDigest: String? = null) = UpdateRepository.AvailableUpdate(
        tagName = "v9.9.9",
        versionName = "9.9.9",
        versionCode = 9_09_09L,
        downloadUrl = "https://example.invalid/agentisco-debug.apk",
        releaseNotes = "",
        assetName = "agentisco-debug.apk",
        assetSize = assetSize,
        assetDigest = assetDigest
    )

    /** Bytes that pass the signature check: "PK\u0003\u0004" followed by filler. */
    private fun apkPayload(size: Int): ByteArray {
        val bytes = ByteArray(size)
        bytes[0] = 0x50
        bytes[1] = 0x4B
        bytes[2] = 0x03
        bytes[3] = 0x04
        for (i in 4 until size) bytes[i] = (i % 251).toByte()
        return bytes
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * Serves a fixed payload from memory. [bytesAvailable] cuts the stream short,
     * which is what a dropped connection looks like to the reader; [sizeHint] is the
     * size the "server" reports; [ignoreRange] makes a Range request answer with the
     * whole body from zero.
     */
    private class InMemoryStreamSource(
        private val payload: ByteArray,
        private val bytesAvailable: Int = payload.size,
        private val sizeHint: Long = payload.size.toLong(),
        private val ignoreRange: Boolean = false
    ) : UpdateStreamSource {
        val requestedOffsets = mutableListOf<Long>()

        override fun open(url: String, offset: Long): UpdateStream {
            requestedOffsets.add(offset)
            val start = if (ignoreRange) 0 else offset.coerceIn(0L, payload.size.toLong()).toInt()
            val end = minOf(payload.size, start + bytesAvailable).coerceAtLeast(start)
            return UpdateStream(
                input = ByteArrayInputStream(payload, start, end - start),
                totalSizeHint = sizeHint,
                rangeIgnored = ignoreRange && offset > 0L
            )
        }
    }
}
