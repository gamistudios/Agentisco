package com.agentisco.data.repository

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Repository for checking app updates against the GitHub Releases API,
 * downloading the APK with resume from the bytes already on disk, verifying the
 * result against the asset size GitHub reports, and installing via FileProvider.
 *
 * A download is only ever marked [UpdateState.DOWNLOADED] (the state that offers
 * Install) after [UpdateDownloadVerifier] confirms the file on disk is exactly the
 * expected size and really is our APK.
 */
class UpdateRepository(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient(),
    private val streamSource: UpdateStreamSource = HttpUpdateStreamSource(client),
    /** Backoff between download attempts; overridable so tests don't wait. */
    private val retryDelayMs: Long = RETRY_DELAY_MS
) {

    enum class UpdateState {
        IDLE, CHECKING, AVAILABLE, DOWNLOADING, DOWNLOADED, ERROR
    }

    data class AvailableUpdate(
        val tagName: String,
        val versionName: String,
        val versionCode: Long,
        val downloadUrl: String,
        val releaseNotes: String,
        /** Name of the APK asset in the release, as reported by the GitHub API. */
        val assetName: String = "",
        /**
         * Byte size of that asset, as reported by the GitHub API. This is the
         * authoritative download total: progress and completion are measured
         * against it, not against whatever the HTTP response happened to send.
         */
        val assetSize: Long = 0L,
        /**
         * Lowercase SHA-256 hex of the asset, when the GitHub API reports one.
         * Hashing the whole file is the only proof that every byte of a (possibly
         * resumed, multi-part) transfer really landed — size alone cannot see
         * sparse holes or a leftover that merely happens to match.
         */
        val assetDigest: String? = null
    )

    private val _updateState = MutableStateFlow(UpdateState.IDLE)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _updateProgress = MutableStateFlow(0f)
    val updateProgress: StateFlow<Float> = _updateProgress.asStateFlow()

    private val _updateError = MutableStateFlow<String?>(null)
    val updateError: StateFlow<String?> = _updateError.asStateFlow()

    private val _availableUpdate = MutableStateFlow<AvailableUpdate?>(null)
    val availableUpdate: StateFlow<AvailableUpdate?> = _availableUpdate.asStateFlow()

    private val _downloadedApkPath = MutableStateFlow<String?>(null)
    val downloadedApkPath: StateFlow<String?> = _downloadedApkPath.asStateFlow()

    @Volatile private var downloadCancelled = false

    class AbortedDownloadException : Exception("Download cancelled by user")

    private fun updateFile(): File = File(context.filesDir, UPDATE_APK_NAME)

    /**
     * Sidecar recording which release the APK on disk was *fully* downloaded and
     * verified as. It is written only after a transfer passed [UpdateDownloadVerifier]
     * and deleted whenever that proof is invalidated, so a leftover file whose size
     * merely happens to match (e.g. one left by the old sparse-file bug) can never
     * be promoted to DOWNLOADED on the next app start.
     */
    private fun markerFile(): File = File(context.filesDir, UPDATE_META_NAME)

    private fun writeMarker(update: AvailableUpdate) {
        runCatching {
            markerFile().writeText(
                JSONObject()
                    .put("url", update.downloadUrl)
                    .put("size", update.assetSize)
                    .put("digest", update.assetDigest ?: "")
                    .toString()
            )
        }
    }

    private fun clearMarker() {
        markerFile().delete()
    }

    /** True when the marker says the file on disk completed this exact download. */
    private fun markerMatches(update: AvailableUpdate): Boolean {
        val json = runCatching { JSONObject(markerFile().readText()) }.getOrNull() ?: return false
        if (json.optString("url", "") != update.downloadUrl) return false
        if (update.assetSize > 0L && json.optLong("size", -1L) != update.assetSize) return false
        val markedDigest = json.optString("digest", "")
        if (markedDigest != (update.assetDigest ?: "")) return false
        return true
    }

    suspend fun checkForUpdates(): Boolean = withContext(Dispatchers.IO) {
        _updateState.value = UpdateState.CHECKING
        _updateError.value = null
        try {
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Update check failed (HTTP ${response.code})")
                }
                val body = response.body?.string() ?: throw Exception("Empty response")
                val json = JSONObject(body)

                val tagName = json.optString("tag_name", "")
                val versionName = json.optString("name").ifBlank { tagName }
                val notes = json.optString("body", "")

                var apkName: String? = null
                var apkSize = 0L
                var apkDigest: String? = null

                // Find the -debug.apk file from assets
                val assets = json.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val assetJSON = assets.getJSONObject(i)
                    val name = assetJSON.optString("name", "")

                    when {
                        name.contains("-debug.apk", ignoreCase = true) -> {
                            apkName = name
                            apkSize = assetJSON.optLong("size", 0L)
                            apkDigest = UpdateDownloadVerifier.normalizeDigest(
                                assetJSON.optString("digest", "")
                            )
                            break
                        }
                        name.endsWith(".apk", ignoreCase = true) && apkName == null -> {
                            apkName = name
                            apkSize = assetJSON.optLong("size", 0L)
                            apkDigest = UpdateDownloadVerifier.normalizeDigest(
                                assetJSON.optString("digest", "")
                            )
                        }
                    }
                }
                if (apkName == null) throw Exception("No -debug.apk asset in latest release")

                // Construct direct download URL: https://github.com/{owner}/{repo}/releases/download/{tag}/{file}
                val owner = "gamistudios"
                val repo = "Agentisco"
                val apkUrl = "https://github.com/$owner/$repo/releases/download/$tagName/$apkName"

                val remoteCode = parseVersionCode(tagName.ifBlank { versionName })
                val localCode = currentVersionCode()

                val isNewer = remoteCode > localCode
                if (isNewer) {
                    adoptAvailableUpdate(
                        AvailableUpdate(
                            tagName = tagName,
                            versionName = versionName,
                            versionCode = remoteCode,
                            downloadUrl = apkUrl,
                            releaseNotes = notes,
                            assetName = apkName,
                            assetSize = apkSize,
                            assetDigest = apkDigest
                        )
                    )
                } else {
                    _availableUpdate.value = null
                    _downloadedApkPath.value = null
                    _updateState.value = UpdateState.IDLE
                }
                isNewer
            }
        } catch (e: CancellationException) {
            _updateState.value = UpdateState.IDLE
            throw e
        } catch (e: Exception) {
            _updateError.value = e.message ?: "Unknown error"
            _updateState.value = UpdateState.ERROR
            false
        }
    }

    /**
     * Records [update] as the pending release and derives the state the UI should
     * show for it.
     *
     * A previous run may already have finished this very download, so the file on
     * disk is only trusted when the completion marker left by that run names it and
     * the bytes still validate against the freshly fetched asset size (and digest):
     * when so, the state goes straight to [UpdateState.DOWNLOADED] (Install) instead
     * of [UpdateState.AVAILABLE] (Download). This is also the entry point tests use
     * to point the downloader at a known asset.
     */
    fun adoptAvailableUpdate(update: AvailableUpdate): UpdateState {
        _availableUpdate.value = update
        // A leftover only counts as ready when a previous run actually finished and
        // verified this exact download (marker) and the bytes still check out.
        val verifiedLeftover = markerMatches(update) &&
            verifyUpdateFile(
                expectedSize = update.assetSize,
                downloadedFromZero = false,
                expectedDigest = update.assetDigest
            ).complete
        return if (verifiedLeftover) {
            _downloadedApkPath.value = updateFile().absolutePath
            _updateProgress.value = 1f
            _updateState.value = UpdateState.DOWNLOADED
            UpdateState.DOWNLOADED
        } else {
            _downloadedApkPath.value = null
            _updateProgress.value = 0f
            _updateState.value = UpdateState.AVAILABLE
            UpdateState.AVAILABLE
        }
    }

    private fun currentVersionCode(): Long {
        return try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION") pkgInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun parseVersionCode(tag: String): Long {
        val cleaned = tag.removePrefix("v").removePrefix("V").trim()
        val parts = cleaned.split(".")
        return try {
            when (parts.size) {
                1 -> parts[0].toLong()
                2 -> parts[0].toLong() * 100 + parts[1].toLong()
                else -> parts[0].toLong() * 10000 + parts[1].toLong() * 100 + parts[2].toLong()
            }
        } catch (e: NumberFormatException) {
            cleaned.filter { it.isDigit() }.toLongOrNull() ?: 0L
        }
    }

    /**
     * Downloads the pending release APK, resuming from the bytes really present on
     * disk.
     *
     * Progress and completion are measured against [AvailableUpdate.assetSize] — the
     * size GitHub reports for the asset — never against the response that happens to
     * be in flight (a resumed response only describes the remaining range). Returns
     * true, and sets [UpdateState.DOWNLOADED], only when the finished file passes
     * [UpdateDownloadVerifier]: exactly the expected size, the SHA-256 the release
     * reports when it publishes one (so every byte of a multi-part/resumed transfer
     * is proven present), and really our APK. Anything else surfaces an error
     * through [updateError] and removes the unusable file.
     */
    suspend fun downloadUpdate(): Boolean = withContext(Dispatchers.IO) {
        val update = _availableUpdate.value ?: return@withContext false
        val file = updateFile()
        // Resume markers written by older builds are no longer trusted; a stale one
        // could point past a truncated file and make the writer seek beyond EOF.
        val legacyOffsetFile = File(file.parentFile, "${file.name}.offset")

        _updateState.value = UpdateState.DOWNLOADING
        _updateError.value = null
        downloadCancelled = false
        // The completion marker only describes a finished download; once a new one
        // starts the file on disk is in flight again.
        clearMarker()

        // GitHub's asset size is authoritative; Content-Length only fills in when
        // the release omits it.
        var expectedSize = update.assetSize.takeIf { it > 0L } ?: 0L
        var lastError: String? = null
        var attempt = 0

        while (attempt < MAX_ATTEMPTS) {
            attempt++
            try {
                if (downloadCancelled) throw AbortedDownloadException()
                legacyOffsetFile.delete()

                var offset = 0L
                if (file.length() > 0L) {
                    // Without a known size a partial file cannot be validated, so it is
                    // re-fetched from zero (a full, non-range download) instead of trusted.
                    offset = if (expectedSize <= 0L) {
                        0L
                    } else {
                        UpdateDownloadVerifier.resumeOffset(
                            existingBytes = file.length(),
                            expectedSize = expectedSize,
                            existingPrefixIsApk = UpdateDownloadVerifier.hasApkMagic(file)
                        )
                    }
                    // Nothing usable to resume from: start from a clean file rather
                    // than writing on top of a truncated or foreign one.
                    if (offset == 0L) file.delete()
                }

                streamSource.open(update.downloadUrl, offset).use { stream ->
                    if (stream.rangeIgnored && offset > 0L) {
                        // The server answered our Range request with the whole file;
                        // appending it would duplicate bytes, so restart at zero.
                        file.delete()
                        offset = 0L
                    }
                    if (expectedSize <= 0L && stream.totalSizeHint > 0L) {
                        expectedSize = stream.totalSizeHint
                    }

                    val startOffset = offset
                    val startedAtZero = startOffset == 0L
                    _updateProgress.value =
                        UpdateDownloadVerifier.progress(startOffset, expectedSize)

                    RandomAccessFile(file, "rw").use { raf ->
                        raf.seek(startOffset)
                        // Drop anything past the resume point so the file can only
                        // ever grow into exactly the bytes we are writing now.
                        raf.setLength(startOffset)
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesCopied = 0L
                        while (true) {
                            if (downloadCancelled) throw AbortedDownloadException()
                            val read = stream.input.read(buffer)
                            if (read == -1) break
                            raf.write(buffer, 0, read)
                            bytesCopied += read
                            _updateProgress.value = UpdateDownloadVerifier.progress(
                                bytesOnDisk = startOffset + bytesCopied,
                                expectedSize = expectedSize
                            )
                        }
                    }

                    val bytesOnDisk = file.length()
                    val decision = verifyUpdateFile(
                        expectedSize = expectedSize,
                        downloadedFromZero = startedAtZero,
                        expectedDigest = update.assetDigest
                    )
                    if (!decision.complete) {
                        throw Exception(decision.reason ?: "Download verification failed")
                    }

                    _updateProgress.value = UpdateDownloadVerifier.progress(
                        bytesOnDisk = bytesOnDisk,
                        expectedSize = expectedSize,
                        verified = true
                    )
                    writeMarker(update)
                    _downloadedApkPath.value = file.absolutePath
                    _updateState.value = UpdateState.DOWNLOADED
                    return@withContext true
                }
            } catch (e: AbortedDownloadException) {
                // Cancelling keeps the partial file so the next run resumes it; the
                // file is NOT downloaded, so no install path may be exposed.
                _downloadedApkPath.value = null
                _updateProgress.value = UpdateDownloadVerifier.progress(file.length(), expectedSize)
                return@withContext false
            } catch (e: CancellationException) {
                _downloadedApkPath.value = null
                _updateProgress.value = UpdateDownloadVerifier.progress(file.length(), expectedSize)
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: "Download failed"
            }

            if (attempt < MAX_ATTEMPTS) {
                kotlinx.coroutines.delay(attempt * retryDelayMs)
            }
        }

        // Every attempt failed: drop the partial so a truncated or foreign file can
        // never be installed later, and let the retry start from scratch.
        file.delete()
        legacyOffsetFile.delete()
        _downloadedApkPath.value = null
        _updateProgress.value = 0f
        _updateError.value = lastError ?: "Download failed"
        _updateState.value = UpdateState.ERROR
        return@withContext false
    }

    /**
     * Reads the update file from disk and validates it against [expectedSize] and,
     * when the release publishes one, [expectedDigest] — the digest is computed over
     * every byte actually on disk, so a file of the right length with holes or wrong
     * contents fails.
     *
     * @param downloadedFromZero whether the transfer that produced the file started at
     *        offset 0 — the fallback proof of completeness when no size is known.
     */
    private fun verifyUpdateFile(
        expectedSize: Long,
        downloadedFromZero: Boolean,
        expectedDigest: String? = null
    ): UpdateDownloadVerifier.Decision {
        val file = updateFile()
        return UpdateDownloadVerifier.decide(
            expectedSize = expectedSize,
            actualSize = if (file.exists()) file.length() else 0L,
            hasApkMagic = UpdateDownloadVerifier.hasApkMagic(file),
            actualPackage = readApkPackageName(file),
            downloadedFromZero = downloadedFromZero,
            expectedDigest = expectedDigest,
            actualDigest = if (expectedDigest != null) {
                UpdateDownloadVerifier.sha256Hex(file)
            } else null
        )
    }

    /**
     * Package name declared by the APK at [file], or null when the platform cannot
     * parse it. Robolectric (and any environment whose PackageManager has no APK
     * parsing) reports "unknown" instead of failing, so the verifier rejects only a
     * file that positively belongs to someone else.
     */
    private fun readApkPackageName(file: File): String? {
        if (file.length() < UpdateDownloadVerifier.MAGIC_LENGTH) return null
        if (!platformCanParseApks()) return null
        return try {
            // flags = 0 is what targetSdk 28 needs to read the manifest; the newer
            // PackageInfoFlags overload only exists from API 33.
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            info?.packageName
        } catch (e: Exception) {
            null
        }
    }

    /** False on Robolectric/JVM, where PackageManager cannot parse a real APK. */
    private fun platformCanParseApks(): Boolean = try {
        !android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)
    } catch (t: Throwable) {
        false
    }

    /**
     * Aborts the in-flight download. The partial file is kept so the next
     * [downloadUpdate] resumes it, and the state returns to [UpdateState.AVAILABLE]
     * so the UI offers Download again instead of staying stuck on "Downloading".
     */
    fun cancelDownload() {
        downloadCancelled = true
        if (_availableUpdate.value != null && _updateState.value == UpdateState.DOWNLOADING) {
            _updateState.value = UpdateState.AVAILABLE
        }
    }

    fun resetToIdle() {
        _updateState.value = UpdateState.IDLE
        _updateError.value = null
        _updateProgress.value = 0f
    }

    fun installDownloadedApk(): Boolean {
        val path = _downloadedApkPath.value ?: return false
        val realPath = File(path)
        if (!realPath.exists()) return false

        // Final gate in front of the package installer: whatever marked the file
        // ready, it must still be the expected size, hash to the release digest when
        // one is known, and be a real Agentisco APK.
        val available = _availableUpdate.value
        val expectedSize = available?.assetSize?.takeIf { it > 0L } ?: realPath.length()
        val decision = verifyUpdateFile(
            expectedSize = expectedSize,
            downloadedFromZero = true,
            expectedDigest = available?.assetDigest
        )
        if (!decision.complete) {
            realPath.delete()
            clearMarker()
            _downloadedApkPath.value = null
            _updateProgress.value = 0f
            _updateError.value = decision.reason ?: UpdateDownloadVerifier.NOT_AN_APK_REASON
            _updateState.value = UpdateState.AVAILABLE
            return false
        }

        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.updateprovider",
                realPath
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun formatLastCheck(timestampMs: Long): String {
        if (timestampMs <= 0) return "Never"
        val diff = System.currentTimeMillis() - timestampMs
        return when {
            diff < 60_000L -> "Just now"
            diff < 3_600_000L -> "${diff / 60_000L} min ago"
            diff < 86_400_000L -> "${diff / 3_600_000L} hours ago"
            diff < 604_800_000L -> "${diff / 86_400_000L} days ago"
            else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestampMs))
        }
    }

    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/gamistudios/Agentisco/releases/latest"
        private const val UPDATE_APK_NAME = "agentisco-update.apk"
        private const val UPDATE_META_NAME = "agentisco-update.meta.json"
        private const val MAX_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 2_000L
        private const val BUFFER_SIZE = 64 * 1024
    }
}
