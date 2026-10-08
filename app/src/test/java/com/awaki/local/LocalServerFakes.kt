package com.awaki.local

import android.content.Context
import com.awaki.data.local.LocalModelStore
import com.awaki.data.repository.LocalModelRepository
import com.awaki.data.repository.UpdateStream
import com.awaki.data.repository.UpdateStreamSource
import com.awaki.local.model.LocalModel
import com.awaki.local.runtime.LocalAnswerDelta
import com.awaki.local.runtime.LocalChatRequest
import com.awaki.local.runtime.LocalEngineException
import com.awaki.local.runtime.LocalFinishReason
import com.awaki.local.runtime.LocalTemplateCapabilities
import com.awaki.local.runtime.LocalToolCall
import com.awaki.local.model.LocalRuntimeSettings
import com.awaki.local.runtime.LoadedLocalModel
import com.awaki.local.runtime.LoadedModelInfo
import com.awaki.local.runtime.LocalModelEngine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Test doubles for the layers under the OpenAI-compatible server.
 *
 * The repository and the transfer are real — a model installs through the same verified
 * path a device uses — while the engine answers from a script. That is what lets the
 * routing and the SSE framing run their actual code on the JVM, leaving the chat template
 * and the answer read-back to the native engine, which is tested against them.
 */

private val CAPABLE = LocalTemplateCapabilities(
  available = true,
  usesOwnTemplate = true,
  supportsTools = true,
  supportsParallelToolCalls = false,
  supportsThinking = false,
  supportsSystemMessage = true
)

internal class FakeEngine(var available: Boolean = true) : LocalModelEngine {
  val sessions = mutableListOf<FakeSession>()
  var capabilities = CAPABLE
  var loadFailure: Throwable? = null
  var unavailableText = "The model runtime is not installed yet."

  /** Applied to a session as it is created, so a test can script the answer before it runs. */
  var sessionScript: (FakeSession) -> Unit = {}

  override val isAvailable: Boolean get() = available

  override val unavailableReason: String get() = unavailableText

  override fun load(path: String, runtime: LocalRuntimeSettings): LoadedLocalModel {
    loadFailure?.let { throw it }
    if (!available) throw LocalEngineException(unavailableText)
    return FakeSession(path, runtime, capabilities).also {
      sessionScript(it)
      sessions += it
    }
  }

  override fun shutdown() {}
}

internal class FakeSession(
  val path: String,
  val runtime: LocalRuntimeSettings,
  val capabilities: LocalTemplateCapabilities
) : LoadedLocalModel {

  val requests = mutableListOf<LocalChatRequest>()

  /** The pieces the decode hands out; each becomes [deltas] worth of answer. */
  var reply = listOf("hello")
  var finish = LocalFinishReason.END_OF_SEQUENCE
  var failure: Throwable? = null
  var aborts = 0
  var closes = 0

  /** How many pieces go out before [failure] is thrown — 0 fails before the decode starts. */
  var failsAfter = 0

  /** How many pieces the decode handed out, so a test can prove a run was cut short. */
  var consumed = 0

  /** How a newly-arrived piece of text is split: prose, reasoning, or a call fragment. */
  var deltas: (String) -> List<LocalAnswerDelta> = { piece -> listOf(LocalAnswerDelta(content = piece)) }

  override val info: LoadedModelInfo =
    LoadedModelInfo("fake", "lfm2", 65536, runtime.contextSize)

  override fun capabilities(): LocalTemplateCapabilities = capabilities.also {
    // A file whose template will not render chat is known at load, before any turn is asked for.
    if (!it.available) throw LocalEngineException(it.reason)
  }

  override fun chat(request: LocalChatRequest, onDelta: (LocalAnswerDelta) -> Boolean): LocalFinishReason {
    requests += request
    if (!capabilities.available) throw LocalEngineException(capabilities.reason)
    failIfDue()
    for (piece in reply) {
      consumed++
      for (delta in deltas(piece)) {
        if (!onDelta(delta)) return LocalFinishReason.ABORTED
      }
      failIfDue()
    }
    return finish
  }

  private fun failIfDue() {
    val error = failure ?: return
    if (consumed >= failsAfter) throw error
  }

  override fun abort() {
    aborts++
  }

  override fun close() {
    closes++
  }
}

// ---- repository fixtures ----

/** A GGUF-shaped payload of exactly [totalSize] bytes: header the validator reads, then padding. */
internal fun ggufBytes(totalSize: Int): ByteArray {
  val out = ByteArrayOutputStream()
  fun le(value: Long, width: Int) {
    var remaining = value
    repeat(width) {
      out.write((remaining and 0xFF).toInt())
      remaining = remaining ushr 8
    }
  }
  fun text(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    le(bytes.size.toLong(), 8)
    out.write(bytes)
  }
  out.write("GGUF".toByteArray(StandardCharsets.UTF_8))
  le(3, 4)
  le(1, 8)
  le(2, 8)
  text("general.architecture"); le(8, 4); text("lfm2")
  text("general.file_type"); le(4, 4); le(2, 4)
  while (out.size() < totalSize) out.write(0)
  return out.toByteArray()
}

internal fun sha256Hex(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal class ServingSource(private val payload: ByteArray) : UpdateStreamSource {
  override fun open(url: String, offset: Long): UpdateStream = UpdateStream(
    input = ByteArrayInputStream(payload, offset.toInt(), (payload.size - offset).toInt()),
    totalSizeHint = payload.size.toLong()
  )
}

internal object NoRemoteAssets : LocalModelAssetSource {
  override fun lookup(downloadUrl: String): RemoteAssetInfo? = null
}

internal fun modelRecord(id: String) = LocalModel(
  id = id,
  name = id.replaceFirstChar { it.uppercase() },
  sourceUrl = "https://example.test/org/$id",
  downloadUrl = "https://example.test/org/repo/resolve/main/$id.gguf"
)

/** A repository over one in-memory payload, with [ids] already installed and verified. */
internal suspend fun repositoryWithInstalled(context: Context, payload: ByteArray, vararg ids: String): LocalModelRepository {
  val repository = LocalModelRepository(context, LocalModelStore(context), ServingSource(payload), NoRemoteAssets, 0L)
  for (id in ids) {
    check(repository.install(modelRecord(id).copy(sizeBytes = payload.size.toLong(), checksum = sha256Hex(payload))))
  }
  return repository
}
