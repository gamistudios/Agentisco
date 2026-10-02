package com.agentisco.local

import android.content.Context
import com.agentisco.data.local.LocalModelStore
import com.agentisco.data.repository.LocalModelRepository
import com.agentisco.data.repository.UpdateStream
import com.agentisco.data.repository.UpdateStreamSource
import com.agentisco.local.model.LocalModel
import com.agentisco.local.runtime.LocalChatInputs
import com.agentisco.local.runtime.LocalChatTurn
import com.agentisco.local.runtime.LocalEngineException
import com.agentisco.local.runtime.LocalFinishReason
import com.agentisco.local.runtime.LocalGenerationRequest
import com.agentisco.local.runtime.LocalParseDelta
import com.agentisco.local.runtime.LocalParsedMessage
import com.agentisco.local.runtime.LocalTemplateCapabilities
import com.agentisco.local.runtime.LocalToolCall
import com.agentisco.local.model.LocalRuntimeSettings
import com.agentisco.local.runtime.LoadedLocalModel
import com.agentisco.local.runtime.LoadedModelInfo
import com.agentisco.local.runtime.LocalModelEngine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Test doubles for the layers under the OpenAI-compatible server.
 *
 * The repository and the transfer are real — a model installs through the same verified
 * path a device uses — while the engine answers from a script. That is what lets the
 * routing, the stop sequences and the SSE framing run their actual code on the JVM,
 * leaving llama.cpp itself to the device test.
 */

private val CAPABLE = LocalTemplateCapabilities(
  available = true,
  usesOwnTemplate = true,
  supportsTools = true,
  supportsParallelToolCalls = false,
  supportsThinking = false,
  supportsSystemMessage = true,
  supportsTypedContent = false
)

internal class FakeEngine(var available: Boolean = true) : LocalModelEngine {
  val sessions = mutableListOf<FakeSession>()
  var threadCount = 8
  var capabilities = CAPABLE
  var loadFailure: Throwable? = null

  /** Applied to a session as it is created, so a test can script the answer before it runs. */
  var sessionScript: (FakeSession) -> Unit = {}

  override val isAvailable: Boolean get() = available

  override fun systemThreads(): Int = threadCount

  override fun load(path: String, runtime: LocalRuntimeSettings): LoadedLocalModel {
    loadFailure?.let { throw it }
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

  val requests = mutableListOf<LocalGenerationRequest>()
  val turns = mutableListOf<FakeTurn>()

  /** The pieces the decode hands out, one callback each. */
  var reply = listOf("hello")
  var finish = LocalFinishReason.END_OF_SEQUENCE
  var failure: Throwable? = null
  var aborts = 0
  var closes = 0

  /** How many pieces go out before [failure] is thrown — 0 fails before the decode starts. */
  var failsAfter = 0

  /** How many pieces the decode handed out, so a test can prove a run was cut short. */
  var consumed = 0

  /** What this fake template writes at the end of a turn. */
  var stopSequences = listOf("end of turn")

  /** How the template reads a newly-arrived piece back: text, reasoning, or a call fragment. */
  var deltas: (String) -> List<LocalParseDelta> = { piece -> listOf(LocalParseDelta(piece, "", -1, null)) }

  /** The calls the finished answer contains. */
  var calls: List<LocalToolCall> = emptyList()

  override val info: LoadedModelInfo =
    LoadedModelInfo("fake", "lfm2", "", "</s>", 65536, runtime.contextSize, 8192, true)

  override fun templateCapabilities(): LocalTemplateCapabilities = capabilities

  override fun openTurn(inputs: LocalChatInputs): LocalChatTurn {
    if (!capabilities.available) throw LocalEngineException(capabilities.reason)
    return FakeTurn(inputs, stopSequences, deltas = { piece -> deltas(piece) }, calls = { calls })
      .also { turns += it }
  }

  override fun generate(request: LocalGenerationRequest, onPiece: (String) -> Boolean): LocalFinishReason {
    requests += request
    failIfDue()
    for (piece in reply) {
      consumed++
      if (!onPiece(piece)) return LocalFinishReason.STOPPED
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

/**
 * A template that treats generated text as content: the answer is the text, and each
 * parse reports only what arrived since the last one, which is how a real incremental
 * parser behaves.
 */
internal class FakeTurn(
  val inputs: LocalChatInputs,
  override val stopSequences: List<String>,
  private val deltas: (String) -> List<LocalParseDelta>,
  private val calls: () -> List<LocalToolCall>
) : LocalChatTurn {

  var closes = 0
  var parses = 0
  private var reported = 0

  override val prompt: String get() = inputs.messages.joinToString("\n") { "${it.role}: ${it.content}" }
  override val grammar: String? get() = null
  override val format: String get() = "FAKE"
  override val expectsToolCalls: Boolean get() = inputs.tools.isNotEmpty()
  override val supportsThinking: Boolean get() = false

  override fun parse(text: String, partial: Boolean): LocalParsedMessage {
    parses++
    val fresh = if (text.length > reported) text.substring(reported) else ""
    reported = maxOf(reported, text.length)
    return LocalParsedMessage(
      content = text,
      reasoning = "",
      toolCalls = if (partial) emptyList() else calls(),
      deltas = if (fresh.isEmpty()) emptyList() else deltas(fresh),
      rejected = false
    )
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
