package com.agentisco.data.repository

import android.content.Context
import com.agentisco.data.local.LocalModelStore
import com.agentisco.local.HuggingFaceAssetSource
import com.agentisco.local.LocalModelAssetSource
import com.agentisco.local.LocalModelCatalog
import com.agentisco.local.fetch
import com.agentisco.local.model.GgufInspection
import com.agentisco.local.model.GgufInspector
import com.agentisco.local.model.GgufMetadata
import com.agentisco.local.model.LocalModel
import com.agentisco.local.model.LocalModelConfiguration
import com.agentisco.local.model.LocalModelInstallStatus
import com.agentisco.local.model.LocalModelProgress
import com.agentisco.local.model.LocalRuntimeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * How far one model has come, as the management screen sees it.
 *
 * [progress] carries the live transfer numbers, [resumableBytes] the bytes an
 * interrupted transfer left behind, [error] the reason a run stopped. Nothing here
 * is remembered from a previous process: after a restart the numbers are measured
 * again from the file on disk.
 */
data class LocalModelInstallState(
  val status: LocalModelInstallStatus,
  val progress: LocalModelProgress? = null,
  val error: String? = null,
  val resumableBytes: Long = 0L
) {
  val isBusy: Boolean
    get() = status == LocalModelInstallStatus.DOWNLOADING || status == LocalModelInstallStatus.INSTALLING
}

/**
 * The single owner of local model files: where they live, how they get there, what
 * proves they are complete, and when they are gone.
 *
 * Everything the rest of the app asks about a model's bytes goes through this class,
 * so no screen and no runtime holds a path of its own. Installation reuses
 * [ResumableFileTransfer] — the engine the app's own update uses — because a model
 * that must resume, verify and never pretend is the same problem a 100 MB APK is:
 *
 *  - the file lands as a temporary sibling and is renamed into place only after the
 *    size, digest and GGUF-format checks pass, so a partial download can never look
 *    installed;
 *  - progress is measured against the size the *source* publishes, and after a
 *    restart it is measured again from the bytes actually on disk, so the bar never
 *    shows a remembered number;
 *  - a cancelled or interrupted transfer keeps its partial file so the next attempt
 *    resumes from it, while a transfer that fails outright deletes it, so nothing
 *    stale is ever adopted.
 *
 * [LocalModelStore] holds configuration plus the evidence an install left behind;
 * whether a model *is* installed is always recomputed from the file itself.
 */
class LocalModelRepository(
  context: Context,
  private val store: LocalModelStore = LocalModelStore(context),
  private val streamSource: UpdateStreamSource = HttpUpdateStreamSource(OkHttpClient()),
  private val remoteSource: LocalModelAssetSource = HuggingFaceAssetSource(),
  /** Retry backoff between download attempts, overridable so tests do not wait. */
  private val retryDelayMs: Long = RETRY_DELAY_MS
) {

  private val appContext = context.applicationContext

  /** Every model file sits in one private directory; nothing else writes there. */
  private val modelsDir: File = File(appContext.filesDir, MODELS_DIR_NAME).apply { mkdirs() }

  private val partialDir: File = File(modelsDir, PARTIALS_DIR_NAME).apply { mkdirs() }

  private val _models = MutableStateFlow<List<LocalModel>>(emptyList())
  /** Built-in and user-added models, each with its installation recomputed. */
  val models: StateFlow<List<LocalModel>> = _models.asStateFlow()

  private val _installStates = MutableStateFlow<Map<String, LocalModelInstallState>>(emptyMap())
  /** The six install states per model, with live numbers while a transfer runs. */
  val installStates: StateFlow<Map<String, LocalModelInstallState>> = _installStates.asStateFlow()

  private val cancelled = ConcurrentHashMap<String, Boolean>()
  private val installLocks = ConcurrentHashMap<String, Mutex>()

  init {
    publish(recomputeRecords())
  }

  // ---- Paths ----

  /** The file a model installs to, whether or not those bytes exist yet. */
  fun installedFile(model: LocalModel): File = File(modelsDir, "${fileNameStem(model.id)}.gguf")

  private fun partialFile(model: LocalModel): File = File(partialDir, "${fileNameStem(model.id)}.part")

  /** Bytes on disk for a transfer that has not finished, used to offer a resume. */
  fun partialBytes(modelId: String): Long {
    val model = _models.value.firstOrNull { it.id == modelId } ?: store.model(modelId) ?: return 0L
    return runCatching { partialFile(model).length() }.getOrDefault(0L)
  }

  /**
   * Turns an id into a file name.
   *
   * Custom-model ids are built from a name the user typed, so only characters a path
   * can safely hold survive, and the result is checked for real containment in the
   * model directory: an id crafted to escape is refused rather than written
   * somewhere else in the app's storage.
   */
  private fun fileNameStem(modelId: String): String {
    val cleaned = modelId.lowercase()
      .map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }
      .joinToString("")
      .trim('.')
      .take(80)
      .ifBlank { "model" }
    val root = modelsDir.canonicalFile
    val candidate = File(root, "$cleaned.gguf")
    val resolved = runCatching { candidate.canonicalFile }.getOrNull()
    if (resolved == null || !resolved.path.startsWith(root.path + File.separator)) {
      throw IllegalArgumentException("Model id '$modelId' cannot be stored safely")
    }
    return resolved.name.removeSuffix(".gguf")
  }

  // ---- Reading records ----

  fun model(modelId: String): LocalModel? = _models.value.firstOrNull { it.id == modelId }

  fun installedModels(): List<LocalModel> = _models.value.filter { it.installed }

  /** Installed models the runtime may load, with the catalog's defaults applied. */
  fun selectableModels(): List<LocalModel> = installedModels()

  /** The file to hand the inference engine, or null when the model is not installed. */
  fun fileFor(model: LocalModel): File? = installedFile(model).takeIf { model.installed && it.isFile }

  fun configuration(modelId: String): LocalModelConfiguration =
    store.model(modelId)?.configuration ?: LocalModelConfiguration.Defaults

  fun installState(modelId: String): LocalModelInstallState =
    _installStates.value[modelId] ?: LocalModelInstallState(LocalModelInstallStatus.NOT_INSTALLED)

  /** Metadata read from the installed file, for the "model info" view. */
  fun metadataOf(model: LocalModel): GgufMetadata? =
    (GgufInspector.inspect(installedFile(model)) as? GgufInspection.Valid)?.metadata

  // ---- Configuration ----

  /** Saves per-model runtime and generation settings. */
  fun updateConfiguration(modelId: String, configuration: LocalModelConfiguration) {
    val existing = store.model(modelId) ?: return
    store.upsert(existing.copy(configuration = configuration))
    publish(recomputeRecords())
  }

  /** Puts a model's settings back to the defaults the product pins for it. */
  fun resetConfiguration(modelId: String) {
    updateConfiguration(modelId, LocalModelConfiguration.Defaults)
  }

  // ---- Custom models ----

  /**
   * Adds a model the user pointed at, stored, downloaded and verified exactly like a
   * catalog entry. The URL is checked here rather than trusted later: it decides
   * where 142 MB of bytes and the device's storage go.
   *
   * @param checksum the digest the source publishes, when the user has one; without
   *        it completeness rests on the transfer ending at the expected length.
   */
  suspend fun addCustom(
    name: String,
    downloadUrl: String,
    description: String = "",
    quantization: String = "",
    sizeBytes: Long = 0L,
    checksum: String? = null
  ): Result<LocalModel> {
    val trimmedName = name.trim()
    if (trimmedName.isEmpty()) return Result.failure(IllegalArgumentException("Give the model a name"))
    urlFailure(downloadUrl)?.let { return Result.failure(IllegalArgumentException(it)) }

    val id = customId(trimmedName)
    if (store.model(id) != null) {
      return Result.failure(IllegalArgumentException("\"$trimmedName\" is already added"))
    }

    // Whatever the source will say is worth asking before the user commits to a
    // download: the size drives the bar, the digest proves the bytes.
    val remote = remoteSource.fetch(downloadUrl.trim())
    val model = LocalModel(
      id = id,
      name = trimmedName,
      sourceUrl = sourcePageOf(downloadUrl),
      downloadUrl = downloadUrl.trim(),
      sizeBytes = sizeBytes.takeIf { it > 0L } ?: remote?.sizeBytes?.takeIf { it > 0L } ?: 0L,
      checksum = UpdateDownloadVerifier.normalizeDigest(checksum) ?: remote?.checksum,
      version = remote?.version.orEmpty(),
      // Corrected from the file itself once the bytes have been verified.
      quantization = quantization.trim(),
      description = description.trim(),
      builtIn = false
    )
    store.upsert(model)
    publish(recomputeRecords())
    return Result.success(model)
  }

  /** Deletes a custom model's record and its bytes. Built-in rows stay in the catalog. */
  suspend fun remove(modelId: String): Boolean {
    val existing = store.model(modelId) ?: _models.value.firstOrNull { it.id == modelId } ?: return false
    if (existing.builtIn) return false
    return installLock(modelId) {
      withContext(Dispatchers.IO) {
        cancelled.remove(modelId)
        deleteModelAndPartial(existing)
        store.delete(modelId)
        _installStates.update { it - modelId }
        publish(recomputeRecords())
        true
      }
    }
  }

  // ---- Installation ----

  /**
   * Downloads, verifies and installs [model], resuming whatever is already on disk.
   * Returns true only when the model is installed with verified bytes.
   *
   * A model that is already installed and current answers true without touching the
   * network: re-downloading 142 MB because a button was tapped twice is not a
   * safety win, and the file that is on disk has already been proven. An update is
   * the one case where the installed bytes are knowingly replaced.
   */
  suspend fun install(model: LocalModel): Boolean {
    val record = _models.value.firstOrNull { it.id == model.id }
    if (record != null && record.installed && !updateAvailable(record)) return true

    val target = store.model(model.id) ?: model
    // Known before a single byte arrives, so an interrupted transfer still has a row
    // to resume into after a restart — and a partial file nobody can name is a
    // partial file that never gets cleaned up.
    if (store.model(target.id) == null) store.upsert(target)

    val lock = installLocks.getOrPut(target.id) { Mutex() }
    if (!lock.tryLock()) return false // already running for this model
    return try {
      runInstall(target)
    } finally {
      lock.unlock()
    }
  }

  /**
   * Stops the running transfer for [modelId]. The partial file stays where it is, so
   * starting the download again continues from those bytes instead of redoing them.
   */
  fun cancel(modelId: String) {
    cancelled[modelId] = true
  }

  /** Deletes the model's bytes and its install evidence, keeping the record. */
  suspend fun uninstall(model: LocalModel): Boolean = installLock(model.id) {
    withContext(Dispatchers.IO) {
      cancelled.remove(model.id)
      val deleted = deleteModelAndPartial(model)
      store.clearInstalled(model.id)
      publish(recomputeRecords())
      deleted
    }
  }

  private suspend fun installLock(modelId: String, block: suspend () -> Boolean): Boolean {
    val lock = installLocks.getOrPut(modelId) { Mutex() }
    if (!lock.tryLock()) return false
    return try {
      block()
    } finally {
      lock.unlock()
    }
  }

  private suspend fun runInstall(model: LocalModel): Boolean = withContext(Dispatchers.IO) {
    val installed = installedFile(model)
    val partial = partialFile(model)
    val totalBytes = model.sizeBytes.takeIf { it > 0L } ?: 0L

    spaceFailure(model, totalBytes)?.let { reason ->
      setState(model.id, LocalModelInstallState(LocalModelInstallStatus.FAILED, error = reason))
      return@withContext false
    }

    cancelled[model.id] = false
    val speed = TransferSpeedTracker()
    setState(
      model.id,
      LocalModelInstallState(
        LocalModelInstallStatus.DOWNLOADING,
        progress = LocalModelProgress(partial.length(), totalBytes, 0L)
      )
    )

    val outcome = ResumableFileTransfer(streamSource, maxAttempts = MAX_ATTEMPTS, retryDelayMs = retryDelayMs)
      .run(
        ResumableFileTransfer.Spec(
          url = model.downloadUrl,
          file = partial,
          expectedSizeBytes = totalBytes,
          expectedDigest = model.checksum,
          assetSignaturePresent = { GgufInspector.looksLikeGguf(it) },
          acceptedContents = { file ->
            when (val inspection = GgufInspector.inspect(file)) {
              is GgufInspection.Valid -> null
              is GgufInspection.Invalid -> inspection.reason
            }
          },
          isCancelled = { cancelled[model.id] == true },
          onProgress = { bytesOnDisk, total, verified ->
            // Past the last byte everything left is hashing and renaming, which is
            // exactly what "Installing" means to the user.
            val status =
              if (verified || (totalBytes > 0L && bytesOnDisk >= totalBytes)) {
                LocalModelInstallStatus.INSTALLING
              } else {
                LocalModelInstallStatus.DOWNLOADING
              }
            setState(
              model.id,
              LocalModelInstallState(
                status,
                progress = LocalModelProgress(bytesOnDisk, total, speed.speedFor(bytesOnDisk))
              )
            )
          }
        )
      )

    when (outcome) {
      is ResumableFileTransfer.Outcome.Completed -> promote(model, partial, installed, outcome)
      is ResumableFileTransfer.Outcome.Cancelled -> {
        cancelled.remove(model.id)
        setState(
          model.id,
          LocalModelInstallState(LocalModelInstallStatus.NOT_INSTALLED, resumableBytes = outcome.bytesOnDisk)
        )
        false
      }
      is ResumableFileTransfer.Outcome.Failed -> {
        setState(model.id, LocalModelInstallState(LocalModelInstallStatus.FAILED, error = outcome.reason))
        false
      }
    }
  }

  /**
   * Moves the verified temporary file into place and records what was accepted.
   *
   * Replacement is a rename rather than a copy: an update over an installed model
   * swaps one complete file for another, so the model directory is never seen to
   * hold half a model. The quantization tag is corrected from the verified header,
   * because after this the file — not a URL's filename — is the evidence.
   */
  private fun promote(
    model: LocalModel,
    partial: File,
    installed: File,
    outcome: ResumableFileTransfer.Outcome.Completed
  ): Boolean {
    val metadata = (GgufInspector.inspect(partial) as? GgufInspection.Valid)?.metadata
    if (metadata == null) {
      partial.delete()
      setState(
        model.id,
        LocalModelInstallState(
          LocalModelInstallStatus.FAILED,
          error = "Downloaded file is not a valid GGUF model"
        )
      )
      return false
    }
    val digest = model.checksum ?: UpdateDownloadVerifier.sha256Hex(partial)
    if (!replaceFile(partial, installed)) {
      setState(
        model.id,
        LocalModelInstallState(
          LocalModelInstallStatus.FAILED,
          error = "Could not move the verified model into place"
        )
      )
      return false
    }

    val recorded = model.copy(
      sizeBytes = outcome.bytesOnDisk,
      checksum = digest ?: model.checksum,
      quantization = model.quantization.ifBlank { GgufMetadata.fileTypeLabel(metadata.fileType).orEmpty() },
      // Same rule as the quantization: the verified file is the evidence. A model that
      // cannot hold the default window is given its own, so the first request has a
      // chance of fitting without the user having to know the number exists.
      configuration = configurationAfterInstall(model.configuration, metadata)
    )
    store.upsert(recorded)
    store.markInstalled(
      recorded,
      LocalModelStore.InstallEvidence(
        digest = digest,
        version = model.version,
        sizeBytes = outcome.bytesOnDisk,
        installedAtMillis = System.currentTimeMillis()
      )
    )
    publish(recomputeRecords())
    setState(recorded.id, LocalModelInstallState(LocalModelInstallStatus.INSTALLED))
    return true
  }

  /**
   * The settings to record for a model whose bytes just passed verification.
   *
   * Only untouched defaults are recomputed: whoever set a context size chose it, and
   * re-installing or updating the file underneath them is not a licence to undo that.
   */
  private fun configurationAfterInstall(
    configuration: LocalModelConfiguration,
    metadata: GgufMetadata
  ): LocalModelConfiguration {
    if (configuration.runtime != LocalRuntimeSettings.Defaults) return configuration
    val contextSize = LocalRuntimeSettings.contextSizeFor(metadata.contextLength)
    return if (contextSize == configuration.runtime.contextSize) configuration
    else configuration.copy(runtime = configuration.runtime.copy(contextSize = contextSize))
  }

  /**
   * Full integrity check: hash what is on disk and compare it with what the install
   * accepted. Startup only gets the cheap checks (see [recomputeRecords]); this runs
   * before a model is handed to the engine, where loading a corrupted file would
   * otherwise fail as an opaque native error.
   */
  suspend fun verifyInstalled(modelId: String): Boolean = withContext(Dispatchers.IO) {
    val entry = store.entry(modelId) ?: return@withContext false
    val file = installedFile(entry.model)
    if (!file.isFile) {
      recordIntegrityFailure(modelId, "Model file is missing")
      return@withContext false
    }
    when (val inspection = GgufInspector.inspect(file)) {
      is GgufInspection.Invalid -> {
        recordIntegrityFailure(modelId, inspection.reason)
        false
      }
      is GgufInspection.Valid -> {
        val expected = entry.evidence?.digest ?: entry.model.checksum
        if (expected == null) {
          true
        } else if (UpdateDownloadVerifier.sha256Hex(file) == expected) {
          true
        } else {
          recordIntegrityFailure(modelId, "Model file no longer matches its checksum")
          false
        }
      }
    }
  }

  /**
   * Asks each source what it publishes today, so the screen shows a real size and an
   * honest "Update available" rather than the numbers recorded at install time.
   */
  suspend fun refreshRemoteInfo(): List<LocalModel> = withContext(Dispatchers.IO) {
    val refreshed = _models.value.map { model ->
      val updated = LocalModelCatalog.refresh(model, remoteSource.fetch(model.downloadUrl))
      if (updated != model) store.upsert(updated)
      updated
    }
    publish(refreshed)
    refreshed
  }

  // ---- Internals ----

  /**
   * Reads the records and decides, from the files, which models are installed.
   *
   * Installation needs evidence, a file of the size that evidence accepted, and a
   * header that still parses as GGUF. A remembered flag is never enough: the user can
   * delete a file, and a download can lose bytes without anything noticing, and in
   * both cases an "installed" model would mean inference failing at load time.
   */
  private fun recomputeRecords(): List<LocalModel> {
    val records = linkedMapOf<String, LocalModel>()
    LocalModelCatalog.builtIn.forEach { records[it.id] = it }
    store.entries().forEach { records[it.model.id] = it.model }

    return records.values.map { stored ->
      val merged = if (stored.builtIn) LocalModelCatalog.mergeWithDefaults(stored) else stored
      val file = installedFile(merged)
      val evidence = store.evidence(merged.id)
      val installed = evidence != null && file.isFile &&
        GgufInspector.looksLikeGguf(file) &&
        (evidence.sizeBytes <= 0L || file.length() == evidence.sizeBytes)
      merged.copy(
        installed = installed,
        localPath = if (installed) file.absolutePath else null
      )
    }
  }

  private fun publish(records: List<LocalModel>) {
    _models.value = records.sortedWith(compareByDescending<LocalModel> { it.installed }.thenBy { it.name })
    _installStates.update { current ->
      val next = current.toMutableMap()
      records.forEach { model ->
        val live = next[model.id]?.status
        val running = live == LocalModelInstallStatus.DOWNLOADING || live == LocalModelInstallStatus.INSTALLING
        if (!running) {
          next[model.id] = when {
            model.installed && updateAvailable(model) ->
              LocalModelInstallState(LocalModelInstallStatus.UPDATE_AVAILABLE)
            model.installed -> LocalModelInstallState(LocalModelInstallStatus.INSTALLED)
            next[model.id]?.status == LocalModelInstallStatus.FAILED -> next.getValue(model.id)
            else -> LocalModelInstallState(
              LocalModelInstallStatus.NOT_INSTALLED,
              resumableBytes = partialBytes(model.id)
            )
          }
        }
      }
      next.keys.retainAll(records.map { it.id }.toSet())
      next
    }
  }

  /** True when the source now publishes different bytes than the installed ones. */
  private fun updateAvailable(model: LocalModel): Boolean {
    if (!model.installed) return false
    val evidence = store.evidence(model.id) ?: return false
    val remoteDigest = model.checksum
    if (remoteDigest != null && evidence.digest != null) return remoteDigest != evidence.digest
    if (model.sizeBytes > 0L && evidence.sizeBytes > 0L) return model.sizeBytes != evidence.sizeBytes
    return model.version.isNotBlank() && evidence.version.isNotBlank() && model.version != evidence.version
  }

  private fun recordIntegrityFailure(modelId: String, reason: String) {
    store.clearInstalled(modelId)
    publish(recomputeRecords())
    setState(modelId, LocalModelInstallState(LocalModelInstallStatus.FAILED, error = reason))
  }

  /** Removes a model's bytes and its partial; false only when the model file refused. */
  private fun deleteModelAndPartial(model: LocalModel): Boolean {
    partialFile(model).delete()
    val file = installedFile(model)
    return !file.isFile || file.delete()
  }

  private fun spaceFailure(model: LocalModel, expected: Long): String? {
    if (expected <= 0L) return null
    val room = runCatching { modelsDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
    // The rename at the end needs the same room again, so the check is not just for
    // the transfer but for the file it produces.
    return if (expected * 2 > room) {
      "Not enough storage space for ${model.name}: ${(expected * 2) / BYTES_PER_MB} MB free needed, " +
        "${room / BYTES_PER_MB} MB available"
    } else {
      null
    }
  }

  private fun setState(modelId: String, state: LocalModelInstallState) {
    _installStates.update { it + (modelId to state) }
  }

  /** Renames [source] over [destination], falling back to delete-then-rename. */
  private fun replaceFile(source: File, destination: File): Boolean {
    if (source.renameTo(destination)) return true
    if (!destination.isFile) return false
    val replaced = destination.delete() && source.renameTo(destination)
    if (!replaced) source.delete()
    return replaced
  }

  companion object {
    private const val MODELS_DIR_NAME = "local-models"
    private const val PARTIALS_DIR_NAME = "partials"
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 2_000L
    private const val BYTES_PER_MB = 1024L * 1024L

    /** A file-system-safe, stable id for a user-supplied model name. */
    internal fun customId(name: String): String =
      "custom-" + name.lowercase()
        .map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '-' }
        .joinToString("")
        .trim('-', '.')
        .ifBlank { "model" }

    /**
     * Rejects a URL the app will not download a model from.
     *
     * HTTPS only, and a GGUF file: the format check is a first filter, not the
     * decision — the bytes are still verified as a GGUF after they arrive, because a
     * filename is something a server can say anything about.
     */
    internal fun urlFailure(downloadUrl: String): String? {
      val trimmed = downloadUrl.trim()
      if (trimmed.isEmpty()) return "Enter the model's download URL"
      val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return "That is not a valid URL"
      if (uri.scheme == null) return "The URL needs https:// and a host"
      if (!"https".equals(uri.scheme, ignoreCase = true)) return "Model URLs must use HTTPS"
      val host = uri.host?.lowercase() ?: return "That URL has no host"
      if (host.isBlank() || host.contains("..")) return "That URL has no valid host"
      val path = uri.rawPath.orEmpty()
      if (!path.substringAfterLast('/').endsWith(".gguf", ignoreCase = true)) {
        return "Only GGUF model files can be installed from a URL"
      }
      return null
    }

    /** The repository page a resolve link points at, for the model's source label. */
    internal fun sourcePageOf(downloadUrl: String): String {
      val parsed = HuggingFaceAssetSource.parseResolveUrl(downloadUrl) ?: return downloadUrl.trim()
      return "https://huggingface.co/${parsed.first}/tree/${parsed.second}"
    }
  }
}

/** Rolling estimate of transfer speed, from the byte counts the transfer reports. */
private class TransferSpeedTracker {
  private var anchorBytes = -1L
  private var anchorMillis = 0L
  private var lastSpeed = 0L

  fun speedFor(bytesOnDisk: Long): Long {
    val now = System.currentTimeMillis()
    if (anchorBytes < 0L || bytesOnDisk < anchorBytes) {
      anchorBytes = bytesOnDisk
      anchorMillis = now
      return 0L
    }
    val elapsed = now - anchorMillis
    if (elapsed < SPEED_SAMPLE_MS) return lastSpeed
    lastSpeed = (bytesOnDisk - anchorBytes) * 1000L / elapsed.coerceAtLeast(1L)
    anchorBytes = bytesOnDisk
    anchorMillis = now
    return lastSpeed
  }

  companion object {
    private const val SPEED_SAMPLE_MS = 1_000L
  }
}
