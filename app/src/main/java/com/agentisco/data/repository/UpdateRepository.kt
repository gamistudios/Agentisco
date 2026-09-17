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
 * downloading the APK chunk-wise with resume via .offset side file,
 * and installing via FileProvider.
 */
class UpdateRepository(private val context: Context) {
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

    class AbortedDownloadException : Exception("Download cancelled by user")

    private fun updateFile(): File = File(context.filesDir, UPDATE_APK_NAME)

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
                var apkUrl: String? = null
                var apkSize: Long? = null

                val assets = json.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val assetJSON = assets.getJSONObject(i)
                    val name = assetJSON.optString("name", "")
                    val downloadUrl = assetJSON.optString("browser_download_url", "")

                    when {
                        name.contains("-debug.apk", ignoreCase = true) -> {
                            apkName = name
                            apkUrl = downloadUrl
                            apkSize = assetJSON.optLong("size", 0L)
                            break
                        }
                        name.endsWith(".apk", ignoreCase = true) -> {
                            if (apkName == null) {
                                apkName = name
                                apkUrl = downloadUrl
                                apkSize = assetJSON.optLong("size", 0L)
                            }
                        }
                    }
                }
                if (apkUrl == null) throw Exception("No -debug.apk asset in latest release")

                val remoteCode = parseVersionCode(tagName.ifBlank { versionName })
                val localCode = currentVersionCode()

                val isNewer = remoteCode > localCode
                if (isNewer) {
                    _availableUpdate.value = AvailableUpdate(
                        tagName = tagName,
                        versionName = versionName,
                        versionCode = remoteCode,
                        downloadUrl = apkUrl,
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
     * Chunk-wise download with .offset side file for resume. Never uses HTTP
     * Range to avoid GitHub 416 responses.
     */
    suspend fun downloadUpdate(): Boolean = withContext(Dispatchers.IO) {
        val update = _availableUpdate.value ?: return@withContext false
        val file = updateFile()
        val offsetFile = File(file.parentFile, "${file.name}.offset")

        _updateState.value = UpdateState.DOWNLOADING
        _updateError.value = null
        downloadCancelled = false

        var attempt = 0
        var totalSize: Long? = null
        var downloadedOffset: Long = 0L
        var startOffset: Long = 0L

        while (attempt < MAX_ATTEMPTS) {
            try {
                if (downloadCancelled) throw AbortedDownloadException()

                val existingOffset = offsetFile.readText().toLongOrNull() ?: 0L
                val existingBytes = if (file.exists()) file.length() else 0L

                if (existingBytes != existingOffset) {
                    if (existingOffset > existingBytes || existingOffset == 0L) {
                        file.delete()
                        if (existingOffset > 0) offsetFile.delete()
                    } else {
                        RandomAccessFile(file, "rw").use { raf -> raf.seek(existingOffset) }
                    }
                }

                downloadedOffset = existingBytes
                val request = Request.Builder()
                    .url(update.downloadUrl)
                    .apply { if (downloadedOffset > 0) header("Range", "bytes=$downloadedOffset-") }
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Download failed (HTTP ${response.code})")
                    }

                    val body = response.body ?: throw Exception("Empty response body")
                    val contentLength = body.contentLength()
                    totalSize = contentLength.takeIf { it > 0 } ?: (downloadedOffset + 100 * 1024 * 1024)
                    startOffset = downloadedOffset

                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesCopied = 0
                        while (true) {
                            if (downloadCancelled) throw AbortedDownloadException()
                            val read = input.read(buffer)
                            if (read == -1) break

                            RandomAccessFile(file, "rw").use { raf ->
                                raf.seek(startOffset + bytesCopied)
                                raf.write(buffer, 0, read)
                            }
                            bytesCopied += read
                            downloadedOffset += read

                            if (bytesCopied % (64 * 1024) == 0 || read < 0) {
                                offsetFile.writeText(downloadedOffset.toString())
                            }

                            if (totalSize != null) {
                                _updateProgress.value = downloadedOffset.toFloat() / totalSize.toFloat()
                            }
                        }
                    }
                }

                if (downloadCancelled) {
                    _updateProgress.value = downloadedOffset.toFloat() / (totalSize?.toFloat() ?: 1f)
                    _downloadedApkPath.value = file.absolutePath
                    return@withContext false
                }

                if (downloadedOffset > 0L && downloadedOffset == file.length()) {
                    offsetFile.delete()
                    _updateProgress.value = 1f
                    _downloadedApkPath.value = file.absolutePath
                    _updateState.value = UpdateState.DOWNLOADED
                    return@withContext true
                } else {
                    throw Exception("Truncated download")
                }
            } catch (e: AbortedDownloadException) {
                _downloadedApkPath.value = file.absolutePath
                _updateProgress.value = if (file.exists()) {
                    if (totalSize != null) file.length().toFloat() / totalSize else 0f
                } else 0f
                return@withContext false
            } catch (e: CancellationException) {
                _downloadedApkPath.value = file.absolutePath
                _updateProgress.value = if (file.exists()) {
                    if (totalSize != null) file.length().toFloat() / totalSize else 0f
                } else 0f
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt >= MAX_ATTEMPTS) {
                    _updateError.value = e.message ?: "Download failed"
                    _downloadedApkPath.value = file.absolutePath
                    _updateProgress.value = if (file.exists()) {
                        if (totalSize != null) file.length().toFloat() / totalSize else 0f
                    } else 0f
                    _updateState.value = UpdateState.ERROR
                    return@withContext false
                }
                kotlinx.coroutines.delay(attempt * RETRY_DELAY_MS)
            }
        }
        return@withContext false
    }

    fun cancelDownload() {
        downloadCancelled = true
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
        private const val MAX_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 2_000L
    }
}
