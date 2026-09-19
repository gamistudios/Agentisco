package com.agentisco.data.repository

import java.io.File

/**
 * Pure decision logic for "is the downloaded update APK complete and installable?".
 *
 * The old download loop compared its own byte counter against the file length it had
 * just written — a tautology that became true whenever the HTTP body ended cleanly,
 * even if the connection dropped mid-transfer. Everything that decides whether the
 * Install button may be shown lives here instead, with no Android or OkHttp
 * dependency, so the rules can be unit-tested directly.
 */
object UpdateDownloadVerifier {

    /** APKs are ZIP archives, so they always start with the local header "PK\u0003\u0004". */
    private val ZIP_LOCAL_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** Bytes needed to recognise the ZIP/APK signature. */
    const val MAGIC_LENGTH = 4

    /** Package the downloaded artifact must declare to be our own APK. */
    const val EXPECTED_PACKAGE = "com.agentisco"

    /** Progress is held just below 1 until the file has been verified complete. */
    private const val MAX_UNVERIFIED_PROGRESS = 0.999f

    const val NOT_AN_APK_REASON = "Downloaded file is not a valid APK"

    fun sizeMismatchReason(expectedBytes: Long, actualBytes: Long): String =
        "Download incomplete: expected $expectedBytes bytes, got $actualBytes"

    fun wrongPackageReason(actualPackage: String): String =
        "Downloaded APK belongs to package '$actualPackage', expected '$EXPECTED_PACKAGE'"

    const val UNKNOWN_SIZE_REASON =
        "Download could not be verified: size unknown and the transfer did not start from zero"

    /** True when [header] begins with the ZIP local-file-header signature. */
    fun hasApkMagic(header: ByteArray): Boolean {
        if (header.size < MAGIC_LENGTH) return false
        return ZIP_LOCAL_HEADER.indices.all { header[it] == ZIP_LOCAL_HEADER[it] }
    }

    /** True when [file] exists and begins with the ZIP local-file-header signature. */
    fun hasApkMagic(file: File): Boolean {
        if (!file.isFile || file.length() < MAGIC_LENGTH) return false
        val header = ByteArray(MAGIC_LENGTH)
        return try {
            file.inputStream().use { stream ->
                var read = 0
                while (read < MAGIC_LENGTH) {
                    val count = stream.read(header, read, MAGIC_LENGTH - read)
                    if (count == -1) break
                    read += count
                }
                read == MAGIC_LENGTH && hasApkMagic(header)
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Fraction of the update already on disk, derived from the bytes that really
     * exist against [expectedSize] (the release asset size). Capped just below 1.0
     * until [verified] is true, so the UI can never show a finished bar for a file
     * that has not passed [decide]. An unknown expected size reports 0.
     */
    fun progress(bytesOnDisk: Long, expectedSize: Long, verified: Boolean = false): Float {
        if (expectedSize <= 0L || bytesOnDisk <= 0L) return 0f
        val raw = (bytesOnDisk.toDouble() / expectedSize.toDouble()).toFloat().coerceIn(0f, 1f)
        return if (verified) 1f else raw.coerceAtMost(MAX_UNVERIFIED_PROGRESS)
    }

    /**
     * Byte offset a resumed download may start from. Only bytes that are really
     * present on disk count: a stale resume marker pointing past the end of a
     * truncated file used to be trusted, which made the writer seek beyond EOF and
     * leave a sparse, corrupt APK.
     *
     * Returns 0 when the partial file cannot be a prefix of the expected asset
     * (wrong signature, or longer than the asset) — the caller deletes it and
     * starts over from a clean file.
     */
    fun resumeOffset(existingBytes: Long, expectedSize: Long, existingPrefixIsApk: Boolean): Long =
        when {
            existingBytes <= 0L -> 0L
            !existingPrefixIsApk -> 0L
            expectedSize > 0L && existingBytes > expectedSize -> 0L
            else -> existingBytes
        }

    /** Outcome of validating a download; [complete] is what gates the DOWNLOADED state. */
    data class Decision(val complete: Boolean, val reason: String? = null)

    /**
     * Single source of truth for "may the Install button be shown?".
     *
     * @param expectedSize authoritative byte size of the release asset (or, when
     *        GitHub omits it, the Content-Length of a clean full download); 0 when
     *        nothing is known.
     * @param actualSize bytes currently on disk.
     * @param hasApkMagic whether the file starts with the ZIP local-file-header.
     * @param actualPackage package name read from the APK manifest, or null when the
     *        platform could not parse it (JVM/Robolectric tests). Only a positive
     *        mismatch fails — an unreadable manifest never does.
     * @param downloadedFromZero whether this transfer started at offset 0 and no Range
     *        request was ignored: the last-resort proof of completeness when the
     *        expected size is unknown.
     */
    fun decide(
        expectedSize: Long,
        actualSize: Long,
        hasApkMagic: Boolean,
        actualPackage: String?,
        downloadedFromZero: Boolean
    ): Decision {
        if (expectedSize > 0L) {
            if (actualSize != expectedSize) {
                return Decision(false, sizeMismatchReason(expectedSize, actualSize))
            }
        } else if (!downloadedFromZero) {
            return Decision(false, UNKNOWN_SIZE_REASON)
        }

        if (actualSize < MAGIC_LENGTH) return Decision(false, NOT_AN_APK_REASON)
        if (!hasApkMagic) return Decision(false, NOT_AN_APK_REASON)
        if (actualPackage != null && actualPackage != EXPECTED_PACKAGE) {
            return Decision(false, wrongPackageReason(actualPackage))
        }
        return Decision(true, null)
    }
}
