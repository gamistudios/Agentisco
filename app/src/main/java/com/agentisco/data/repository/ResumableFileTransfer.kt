package com.agentisco.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * One verified, resumable file transfer: fetch bytes over HTTP, pick up where a
 * interrupted attempt stopped, and only declare success once the file on disk is
 * proven to be the whole asset.
 *
 * This is the update APK's download loop, lifted out of [UpdateRepository] so
 * local model files are installed under exactly the same rules rather than a
 * second implementation that can drift. Two properties carry over because they
 * were hard-won:
 *
 *  - completion is decided by [UpdateDownloadVerifier.decide] against the
 *    expected size and digest, never by this loop's own byte counter;
 *  - a partial file is only ever resumed if it can plausibly be a prefix of the
 *    asset, and anything left after every attempt fails is deleted, so no
 *    truncated file survives to be mistaken for a finished one.
 *
 * What differs per artifact is passed in: [Spec.acceptedContents] decides whether
 * the bytes are the right *kind* of file (an APK's ZIP header and package, a
 * model's GGUF magic), and [Spec.assetSignaturePresent] decides which partial
 * files may be continued.
 */
class ResumableFileTransfer(
  private val streamSource: UpdateStreamSource,
  private val maxAttempts: Int = MAX_ATTEMPTS,
  /** Overridable so tests exercise the retry path without waiting. */
  private val retryDelayMs: Long = RETRY_DELAY_MS,
  private val bufferSize: Int = BUFFER_SIZE
) {

  /**
   * @param expectedSizeBytes authoritative size of the asset, or 0 when the source
   *        does not publish one — then a response's own length is used instead.
   * @param expectedDigest lowercase SHA-256, when known.
   * @param assetSignaturePresent the cheap check: do the first bytes say this could
   *        be our asset at all? Decides which partial files may be resumed, so it
   *        must not read a whole file.
   * @param acceptedContents returns null when the finished file is usable, otherwise
   *        the reason it is not. Only a positive mismatch should fail here — a fact
   *        the platform cannot read (a manifest it cannot parse) must not.
   * @param onProgress reports bytes on disk against the total, with [verified]
   *        true only once the finished file passed every check.
   * @param isCancelled polled between reads; cancelling keeps the partial file.
   */
  data class Spec(
    val url: String,
    val file: File,
    val expectedSizeBytes: Long = 0L,
    val expectedDigest: String? = null,
    val assetSignaturePresent: (File) -> Boolean = { true },
    val acceptedContents: (File) -> String? = { null },
    val onProgress: (bytesOnDisk: Long, totalBytes: Long, verified: Boolean) -> Unit = { _, _, _ -> },
    val isCancelled: () -> Boolean = { false }
  )

  sealed class Outcome {
    data class Completed(val bytesOnDisk: Long, val expectedSize: Long) : Outcome()
    /** Cancelled by the caller; the partial file stays put for a later resume. */
    data class Cancelled(val bytesOnDisk: Long) : Outcome()
    data class Failed(val reason: String) : Outcome()
  }

  private class AbortedException : Exception("Transfer cancelled")

  suspend fun run(spec: Spec): Outcome = withContext(Dispatchers.IO) {
    val file = spec.file
    var expectedSize = spec.expectedSizeBytes.takeIf { it > 0L } ?: 0L
    var lastError: String? = null
    var attempt = 0

    while (attempt < maxAttempts) {
      attempt++
      try {
        if (spec.isCancelled()) throw AbortedException()

        var offset = 0L
        if (file.length() > 0L) {
          // Without a known size a partial file cannot be validated at all, so it
          // is re-fetched from zero rather than trusted.
          offset = if (expectedSize <= 0L) {
            0L
          } else {
            UpdateDownloadVerifier.resumeOffset(
              existingBytes = file.length(),
              expectedSize = expectedSize,
              existingPrefixIsApk = spec.assetSignaturePresent(file)
            )
          }
          if (offset == 0L) file.delete()
        }

        streamSource.open(spec.url, offset).use { stream ->
          if (stream.rangeIgnored && offset > 0L) {
            // The server answered our Range request with the whole file; appending
            // would duplicate bytes, so restart from zero.
            file.delete()
            offset = 0L
          }
          if (expectedSize <= 0L && stream.totalSizeHint > 0L) {
            expectedSize = stream.totalSizeHint
          }

          val startOffset = offset
          val startedAtZero = startOffset == 0L
          spec.onProgress(startOffset, expectedSize, false)

          RandomAccessFile(file, "rw").use { raf ->
            raf.seek(startOffset)
            // Drop anything past the resume point: the file may only ever grow into
            // exactly the bytes this transfer is writing.
            raf.setLength(startOffset)
            val buffer = ByteArray(bufferSize)
            var copied = 0L
            while (true) {
              if (spec.isCancelled()) throw AbortedException()
              val read = stream.input.read(buffer)
              if (read == -1) break
              raf.write(buffer, 0, read)
              copied += read
              spec.onProgress(startOffset + copied, expectedSize, false)
            }
          }

          val bytesOnDisk = file.length()
          // Size and digest are the artifact-independent questions and stay in the
          // verifier; "are these bytes the right kind of file" is answered by the
          // caller, in the same order the update path has always used them.
          val decision = UpdateDownloadVerifier.decide(
            expectedSize = expectedSize,
            actualSize = bytesOnDisk,
            hasApkMagic = true,
            actualPackage = null,
            downloadedFromZero = startedAtZero,
            expectedDigest = spec.expectedDigest,
            actualDigest = spec.expectedDigest?.let { UpdateDownloadVerifier.sha256Hex(file) }
          )
          if (!decision.complete) {
            throw Exception(decision.reason ?: "Download verification failed")
          }
          spec.acceptedContents(file)?.let { reason -> throw Exception(reason) }

          spec.onProgress(bytesOnDisk, expectedSize, true)
          return@withContext Outcome.Completed(bytesOnDisk, expectedSize)
        }
      } catch (e: AbortedException) {
        return@withContext Outcome.Cancelled(file.length())
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        lastError = e.message ?: "Download failed"
      }

      if (attempt < maxAttempts) delay(attempt * retryDelayMs)
    }

    // Every attempt failed: remove the partial so a truncated or foreign file can
    // never be adopted later, and the next run starts from scratch.
    file.delete()
    Outcome.Failed(lastError ?: "Download failed")
  }

  companion object {
    const val MAX_ATTEMPTS = 3
    const val RETRY_DELAY_MS = 2000L
    private const val BUFFER_SIZE = 64 * 1024
  }
}
