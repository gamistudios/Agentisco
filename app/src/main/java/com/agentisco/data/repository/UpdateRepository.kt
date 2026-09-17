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
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Repository for checking app updates against the GitHub Releases API and
 * downloading the APK with resumable (HTTP Range) support.
 */
class UpdateRepository(
    private val context: Context
) {
    private val client = OkHttpClient()

    enum class UpdateState {
        IDLE, CHECKING, AVAILABLE, DOWNLOADING, DOWNLOADED, ERROR
    }

    data class AvailableUpdate(
        val tagName: String,
        val versionName: String,
        val versionCode: Long,
        val downloadUrl: String,
        val releaseNotes: String
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

    /** Thrown internally so a user-cancelled download is not treated as an error. */
    class AbortedDownloadException : Exception("Download cancelled by user")

    private fun updateFile(): File = File(context.filesDir, UPDATE_APK_NAME)

    /**
     * Queries GitHub for the latest release and resolves it against the
     * currently installed versionCode.
     */
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

                // Find the first APK asset.
                val assets = json.getJSONArray("assets")
                var downloadUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = asset.getString("browser_download_url")
                        break
                    }
                }
                if (downloadUrl == null) throw Exception("No APK asset in latest release")

                val remoteCode = parseVersionCode(tagName.ifBlank { versionName })
                val localCode = currentVersionCode()

                val isNewer = remoteCode > localCode
                if (isNewer) {
                    _availableUpdate.value = AvailableUpdate(
                        tagName = tagName,
                        versionName = versionName,
                        versionCode = remoteCode,
                        downloadUrl = downloadUrl,
                        releaseNotes = notes
                    )
                    _updateState.value = UpdateState.AVAILABLE
                } else {
                    _availableUpdate.value = null
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

    /**
     * Parses a numeric versionCode from a tag like "v1.2.3" -> 10203 (major * 10000 + minor * 100 + patch).
     * Falls back to stripping non-digits.
     */
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
     * Downloads the APK; resumes from the partial file if present via a Range
     * request. Network hiccups are retried automatically with backoff — the
     * partial bytes already on disk are reused on every attempt.
     */
    suspend fun downloadUpdate(): Boolean = withContext(Dispatchers.IO) {
        val update = _availableUpdate.value ?: return@withContext false

        _updateState.value = UpdateState.DOWNLOADING
        _updateError.value = null
        downloadCancelled = false

        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            try {
                if (downloadCancelled) throw AbortedDownloadException()
                val done = attemptDownloadOnce(update.downloadUrl)
                if (done) {
                    _updateState.value = UpdateState.DOWNLOADED
                    return@withContext true
                }
                // Cancelled — leave AVAILABLE so the user can retry.
                _updateState.value = UpdateState.AVAILABLE
                return@withContext false
            } catch (e: AbortedDownloadException) {
                _updateState.value = UpdateState.AVAILABLE
                return@withContext false
            } catch (e: CancellationException) {
                _updateState.value = if (_availableUpdate.value != null) UpdateState.AVAILABLE else UpdateState.IDLE
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt >= MAX_ATTEMPTS) {
                    _updateError.value = e.message ?: "Download failed"
                    _updateState.value = UpdateState.ERROR
                    return@withContext false
                }
                // Back off and resume — partial bytes stay on disk.
                kotlinx.coroutines.delay(attempt * RETRY_DELAY_MS)
            }
        }
        false
    }

    /**
     * One download attempt: opens a (possibly ranged) request and appends to
     * the on-disk APK. Returns true when the full file was written; throws on
     * failures so the caller can retry/abort.
     */
    private fun attemptDownloadOnce(downloadUrl: String): Boolean {
        val file = updateFile()
        val existingBytes = if (file.exists()) file.length() else 0L

        val request = Request.Builder()
            .url(downloadUrl)
            .apply { if (existingBytes > 0) header("Range", "bytes=$existingBytes-") }
            .build()

        client.newCall(request).execute().use { response ->
            val resumed = response.code == 206 && existingBytes > 0
            if (response.code != 206 && !response.isSuccessful) {
                throw Exception("Download failed (HTTP ${response.code})")
            }

            val body = response.body ?: throw Exception("Empty response body")
            val contentLength = body.contentLength()
            val totalSize = if (resumed) existingBytes + contentLength else contentLength

            val startOffset = if (resumed) existingBytes else 0L
            if (!resumed && existingBytes > 0) file.delete()

            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(startOffset)
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = startOffset
                    while (true) {
                        if (downloadCancelled) throw AbortedDownloadException()
                        val read = input.read(buffer)
                        if (read == -1) break
                        raf.write(buffer, 0, read)
                        downloaded += read
                        if (totalSize > 0) {
                            _updateProgress.value = downloaded.toFloat() / totalSize.toFloat()
                        }
                    }
                }
            }

            if (downloadCancelled) return false

            _updateProgress.value = 1f
            _downloadedApkPath.value = file.absolutePath
            return true
        }
    }

    /**
     * Signals the download loop to abort cleanly. The partial APK stays on
     * disk so the next [downloadUpdate] call resumes via an HTTP Range request.
     */
    fun cancelDownload() {
        downloadCancelled = true
    }

    fun resetToIdle() {
        _updateState.value = UpdateState.IDLE
        _updateError.value = null
        _updateProgress.value = 0f
    }

    /**
     * Opens the package installer for the downloaded APK via FileProvider.
     */
    fun installDownloadedApk(): Boolean {
        val path = _downloadedApkPath.value ?: return false
        val file = File(path)
        if (!file.exists()) return false
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.updateprovider",
                file
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
            diff < 60_000 -> "Just now"
            diff < 3_600_000 -> "${diff / 60_000} min ago"
            diff < 86_400_000 -> "${diff / 3_600_000} hours ago"
            diff < 604_800_000 -> "${diff / 86_400_000} days ago"
            else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestampMs))
        }
    }

    companion object {
        private const val RELEASES_URL =
            "https://api.github.com/repos/gamistudios/Agentisco/releases/latest"
        private const val UPDATE_APK_NAME = "agentisco-update.apk"
        private const val MAX_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 2_000L
    }
}
