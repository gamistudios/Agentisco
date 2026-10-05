package com.awaki.data.repository

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
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Repository for checking app updates against the Awaki Update API,
 * downloading the APK with resume from the bytes already on disk, verifying the
 * result against the asset size the service reports, and installing via FileProvider.
 *
 * A download is only ever marked [UpdateState.DOWNLOADED] (the state that offers
 * Install) after [UpdateDownloadVerifier] confirms the file on disk is exactly the
 * expected size and really is our APK.
 */
class UpdateRepository(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient(),
    private val streamSource: UpdateStreamSource = HttpUpdateStreamSource(client),
    private val releaseSource: UpdateReleaseSource = HttpUpdateReleaseSource(client),
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
        /** Name of the APK asset, as reported by the Update API. */
        val assetName: String = "",
        /**
         * Byte size of that asset, as reported by the Update API. This is the
         * authoritative download total: progress and completion are measured
         * against it, not against whatever the HTTP response happened to send.
         */
        val assetSize: Long = 0L,
        /**
         * Lowercase SHA-256 hex of the asset, when the Update API reports one.
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

    /**
     * The download is tracked as process-level work: it belongs to neither screen,
     * and a multi-megabyte APK does not stop being useful because the user closed
     * the update dialog.
     */
    private val workRegistry: com.awaki.background.WorkRegistry? by lazy {
        (context.applicationContext as? com.awaki.AwakiApplication)?.workRegistry
    }

    private val _updateError = MutableStateFlow<String?>(null)
    val updateError: StateFlow<String?> = _updateError.asStateFlow()

    private val _availableUpdate = MutableStateFlow<AvailableUpdate?>(null)
    val availableUpdate: StateFlow<AvailableUpdate?> = _availableUpdate.asStateFlow()

    private val _downloadedApkPath = MutableStateFlow<String?>(null)
    val downloadedApkPath: StateFlow<String?> = _downloadedApkPath.asStateFlow()

    @Volatile private var downloadCancelled = false

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
                    .put("versionCode", update.versionCode)
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

    /**
     * Fetches the latest release and, when its version is newer than the running
     * app's, records it as [availableUpdate]. A download a previous run finished
     * for an older release is deleted here — see the marker check below.
     */
    suspend fun checkForUpdates(): Boolean = withContext(Dispatchers.IO) {
        _updateState.value = UpdateState.CHECKING
        _updateError.value = null
        try {
            val release = releaseSource.fetchLatestUpdate()

            val tagName = release.tagName
            val versionName = release.versionName
            val notes = release.releaseNotes
            val apkName = release.assetName
            val apkSize = release.assetSize
            val apkDigest = release.assetDigest

            val remoteCode = release.versionCode.takeIf { it > 0L }
                ?: parseVersionCode(tagName.ifBlank { versionName })
            val localCode = currentVersionCode()

            val isNewer = remoteCode > localCode
            if (isNewer) {
                val candidate = AvailableUpdate(
                    tagName = tagName,
                    versionName = versionName,
                    versionCode = remoteCode,
                    downloadUrl = release.downloadUrl,
                    releaseNotes = notes,
                    assetName = apkName,
                    assetSize = apkSize,
                    assetDigest = apkDigest
                )
                // Whatever a previous run finished downloading belongs to an older
                // release than the one just found, so throw it away instead of
                // leaving a stale apk in filesDir. Only a marker that names this
                // very release (the check ran again for an already-finished
                // download) survives, so adoptAvailableUpdate can offer Install
                // right away. A marker without a versionCode predates version
                // tracking and is stale by definition.
                val markerJson = runCatching { JSONObject(markerFile().readText()) }.getOrNull()
                if (markerJson != null && markerJson.optLong("versionCode", -1L) != remoteCode) {
                    deleteDownloadedUpdate()
                }
                adoptAvailableUpdate(candidate)
            } else {
                _availableUpdate.value = null
                _downloadedApkPath.value = null
                _updateState.value = UpdateState.IDLE
            }
            isNewer
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
     * Downloads the pending release APK as tracked background work, so closing the
     * update dialog - or the app - does not throw away minutes of transfer.
     */
    suspend fun downloadUpdate(): Boolean {
        val registry = workRegistry
        val version = _availableUpdate.value?.versionName
        registry?.begin(
          id = UPDATE_DOWNLOAD_WORK_ID,
          kind = com.awaki.background.WorkKind.UPDATE_DOWNLOAD,
          label = "Downloading an update",
          detail = if (version.isNullOrBlank()) "Starting" else "v$version",
          canceller = { cancelDownload() }
        )
        return try {
            runDownload()
        } finally {
            registry?.end(UPDATE_DOWNLOAD_WORK_ID)
        }
    }

    /**
     * Publishes download progress and mirrors it into the background notification, so
     * a user who left the app can still see the transfer moving.
     */
    private fun publishProgress(fraction: Float) {
        _updateProgress.value = fraction
        workRegistry?.setProgress(
            id = UPDATE_DOWNLOAD_WORK_ID,
            label = "Downloading an update",
            detail = if (fraction <= 0f) "Starting" else "${(fraction * 100).toInt()}% of the APK"
        )
    }

    /**
     * Downloads the pending release APK, resuming from the bytes really present on
     * disk.
     *
     * Progress and completion are measured against [AvailableUpdate.assetSize] — the
     * size the Update API reports for the asset — never against the response that
     * happens to be in flight (a resumed response only describes the remaining
     * range). Returns true, and sets [UpdateState.DOWNLOADED], only when the
     * finished file passes [UpdateDownloadVerifier]: exactly the expected size, the
     * SHA-256 the release reports when it publishes one (so every byte of a
     * multi-part/resumed transfer is proven present), and really our APK. Anything
     * else surfaces an error through [updateError] and removes the unusable file.
     *
     * The transfer itself is [ResumableFileTransfer], shared with local model
     * installation; this method supplies the APK-specific content rules and maps the
     * outcome onto the repository's states.
     */
    private suspend fun runDownload(): Boolean {
        val update = _availableUpdate.value ?: return false
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
        legacyOffsetFile.delete()

        val outcome = ResumableFileTransfer(
            streamSource = streamSource,
            maxAttempts = MAX_ATTEMPTS,
            retryDelayMs = retryDelayMs
        ).run(
            ResumableFileTransfer.Spec(
                url = update.downloadUrl,
                file = file,
                expectedSizeBytes = update.assetSize,
                expectedDigest = update.assetDigest,
                assetSignaturePresent = { UpdateDownloadVerifier.hasApkMagic(it) },
                acceptedContents = { apkContentsReason(it) },
                isCancelled = { downloadCancelled },
                onProgress = { bytesOnDisk, totalBytes, verified ->
                    publishProgress(UpdateDownloadVerifier.progress(bytesOnDisk, totalBytes, verified))
                }
            )
        )

        return when (outcome) {
            is ResumableFileTransfer.Outcome.Completed -> {
                writeMarker(update)
                _downloadedApkPath.value = file.absolutePath
                _updateState.value = UpdateState.DOWNLOADED
                true
            }

            // Cancelling keeps the partial file so the next run resumes it; the file
            // is NOT downloaded, so no install path may be exposed.
            is ResumableFileTransfer.Outcome.Cancelled -> {
                _downloadedApkPath.value = null
                _updateProgress.value = UpdateDownloadVerifier.progress(outcome.bytesOnDisk, update.assetSize)
                false
            }

            is ResumableFileTransfer.Outcome.Failed -> {
                _downloadedApkPath.value = null
                _updateError.value = outcome.reason
                _updateState.value = UpdateState.ERROR
                false
            }
        }
    }

    /**
     * Why this file is not a usable Awaki APK, or null when it is. A manifest
     * the platform cannot read is not a failure — only a package that positively
     * belongs to somebody else is.
     */
    private fun apkContentsReason(file: File): String? {
        if (!UpdateDownloadVerifier.hasApkMagic(file)) return UpdateDownloadVerifier.NOT_AN_APK_REASON
        val owner = readApkPackageName(file)
        return if (owner != null && owner != UpdateDownloadVerifier.EXPECTED_PACKAGE) {
            UpdateDownloadVerifier.wrongPackageReason(owner)
        } else {
            null
        }
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

    /** True when an update APK — complete or a resumable partial — is on disk. */
    fun hasUpdateFileOnDisk(): Boolean = updateFile().exists()

    /**
     * Throws away everything the update download has written: the APK, the legacy
     * resume marker and the completion marker. Any in-flight download is aborted,
     * and the state falls back so the UI offers a fresh download again.
     */
    fun deleteDownloadedUpdate() {
        downloadCancelled = true
        val file = updateFile()
        file.delete()
        File(file.parentFile, "${file.name}.offset").delete()
        clearMarker()
        _downloadedApkPath.value = null
        _updateProgress.value = 0f
        _updateError.value = null
        _updateState.value =
            if (_availableUpdate.value != null) UpdateState.AVAILABLE else UpdateState.IDLE
    }

    fun resetToIdle() {
        _updateState.value = UpdateState.IDLE
        _updateError.value = null
        _updateProgress.value = 0f
    }

    fun installDownloadedApk(): Boolean {
        val intent = verifiedInstallIntent() ?: return false
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * The installer intent for the verified APK on disk, or null when there is no
     * usable file. Split out because an app that is not on screen may not start a
     * screen: a download that finishes while the user is elsewhere has to hand this
     * over as a notification action, where their tap is what opens the installer.
     */
    fun verifiedInstallIntent(): Intent? {
        val path = _downloadedApkPath.value ?: return null
        val realPath = File(path)
        if (!realPath.exists()) return null

        // Final gate in front of the package installer: whatever marked the file
        // ready, it must still be the expected size, hash to the release digest when
        // one is known, and be a real Awaki APK.
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
            return null
        }

        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.updateprovider",
                realPath
            )
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }.getOrNull()
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
        private const val UPDATE_APK_NAME = "awaki-update.apk"
        private const val UPDATE_META_NAME = "awaki-update.meta.json"
        private const val MAX_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 2_000L
        private const val BUFFER_SIZE = 64 * 1024
        private const val UPDATE_DOWNLOAD_WORK_ID = "update-download"
    }
}
