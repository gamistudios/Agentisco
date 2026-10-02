package com.agentisco

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agentisco.data.local.LocalModelStore
import com.agentisco.data.repository.LocalModelRepository
import com.agentisco.data.repository.UpdateStream
import com.agentisco.data.repository.UpdateStreamSource
import com.agentisco.local.LocalModelAssetSource
import com.agentisco.local.RemoteAssetInfo
import com.agentisco.local.model.LocalModel
import com.agentisco.local.model.LocalModelConfiguration
import com.agentisco.local.model.LocalModelInstallStatus
import com.agentisco.local.model.LocalRuntimeSettings
import com.agentisco.local.runtime.LoadedLocalModel
import com.agentisco.local.runtime.LoadedModelInfo
import com.agentisco.local.runtime.LocalChatInputs
import com.agentisco.local.runtime.LocalChatMessage
import com.agentisco.local.runtime.LocalChatTool
import com.agentisco.local.runtime.LocalChatTurn
import com.agentisco.local.runtime.LocalEngineException
import com.agentisco.local.runtime.LocalFinishReason
import com.agentisco.local.runtime.LocalGenerationRequest
import com.agentisco.local.runtime.LocalInferenceEngine
import com.agentisco.local.runtime.LocalModelEngine
import com.agentisco.local.runtime.LocalParsedMessage
import com.agentisco.local.runtime.LocalPromptTooLongException
import com.agentisco.local.runtime.LocalTemplateCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Lifecycle rules for the on-device engine: nothing loaded until it is needed, one
 * model at a time, a file proven before it is opened, and a failure that leaves the
 * user's download intact.
 *
 * The repository is real — models install through the same verified transfer a device
 * uses — while the decode backend is a fake, so these tests are about ownership and
 * integrity rather than about llama.cpp itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalInferenceEngineTest {

    private lateinit var context: Context
    private lateinit var modelsDir: File
    private lateinit var store: LocalModelStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        modelsDir = File(context.filesDir, "local-models")
        modelsDir.deleteRecursively()
        store = LocalModelStore(context)
        store.entries().map { it.model.id }.forEach { store.delete(it) }
    }

    // ---- fakes ----

    private class FakeSession(
        val path: String,
        val runtime: LocalRuntimeSettings
    ) : LoadedLocalModel {
        val requests = mutableListOf<LocalGenerationRequest>()
        val turns = mutableListOf<LocalChatInputs>()
        var reply = listOf("hello")
        var finish = LocalFinishReason.END_OF_SEQUENCE
        var failure: Throwable? = null
        var aborts = 0
        var closes = 0
        var capabilityReads = 0
        var capabilities = LocalTemplateCapabilities(
            available = true,
            usesOwnTemplate = true,
            supportsTools = true,
            supportsParallelToolCalls = true,
            supportsThinking = false,
            supportsSystemMessage = true,
            supportsTypedContent = false
        )

        override val info: LoadedModelInfo =
            LoadedModelInfo("fake", "lfm2", "", "</s>", 65536, runtime.contextSize, 8192, true)

        override fun templateCapabilities(): LocalTemplateCapabilities {
            capabilityReads++
            return capabilities
        }

        override fun openTurn(inputs: LocalChatInputs): LocalChatTurn {
            if (!capabilities.available) throw LocalEngineException(capabilities.reason)
            return FakeTurn(inputs).also { turns += inputs }
        }

        override fun generate(request: LocalGenerationRequest, onPiece: (String) -> Boolean): LocalFinishReason {
            requests += request
            failure?.let { throw it }
            for (piece in reply) if (!onPiece(piece)) return LocalFinishReason.STOPPED
            return finish
        }

        override fun abort() {
            aborts++
        }

        override fun close() {
            closes++
        }
    }

    /** A turn that renders by joining the messages, so the layer above can be asserted on. */
    private class FakeTurn(val inputs: LocalChatInputs) : LocalChatTurn {
        var closes = 0
        var parses = 0
        var reply = LocalParsedMessage("ok", "", emptyList(), emptyList(), rejected = false)
        override val prompt: String get() = inputs.messages.joinToString("\n") { "${it.role}: ${it.content}" }
        override val grammar: String? get() = null
        override val stopSequences: List<String> get() = listOf("end of turn")
        override val format: String get() = "FAKE"
        override val expectsToolCalls: Boolean get() = inputs.tools.isNotEmpty()
        override val supportsThinking: Boolean get() = false
        override fun parse(text: String, partial: Boolean): LocalParsedMessage {
            parses++
            return reply
        }
        override fun close() {
            closes++
        }
    }

    private class FakeEngine(var available: Boolean = true) : LocalModelEngine {
        val sessions = mutableListOf<FakeSession>()
        var threadCount = 8
        var loadFailure: Throwable? = null

        override val isAvailable: Boolean get() = available

        override fun systemThreads(): Int = threadCount

        override fun load(path: String, runtime: LocalRuntimeSettings): LoadedLocalModel {
            loadFailure?.let { throw it }
            return FakeSession(path, runtime).also { sessions += it }
        }

        override fun shutdown() {}
    }

    private class ServingSource(private val payload: ByteArray) : UpdateStreamSource {
        override fun open(url: String, offset: Long): UpdateStream = UpdateStream(
            input = ByteArrayInputStream(payload, offset.toInt(), (payload.size - offset).toInt()),
            totalSizeHint = payload.size.toLong()
        )
    }

    private object NoRemote : LocalModelAssetSource {
        override fun lookup(downloadUrl: String): RemoteAssetInfo? = null
    }

    // ---- fixtures ----

    private fun model(id: String) = LocalModel(
        id = id,
        name = id.replaceFirstChar { it.uppercase() },
        sourceUrl = "https://example.test/org/$id",
        downloadUrl = "https://example.test/org/repo/resolve/main/$id.gguf"
    )

    private fun ggufBytes(totalSize: Int): ByteArray {
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

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun repositoryFor(payload: ByteArray) =
        LocalModelRepository(context, store, ServingSource(payload), NoRemote, 0L)

    /** Installs [id] through the real transfer and returns the record held afterwards. */
    private suspend fun install(repository: LocalModelRepository, id: String, payload: ByteArray): LocalModel {
        val record = model(id).copy(sizeBytes = payload.size.toLong(), checksum = sha256(payload))
        assertTrue(repository.install(record))
        return repository.model(id)!!
    }

    private fun engineFor(repository: LocalModelRepository, fake: FakeEngine) =
        LocalInferenceEngine(repository, fake, Dispatchers.Unconfined)

    private suspend fun generateOnce(engine: LocalInferenceEngine, model: LocalModel): String {
        val pieces = StringBuilder()
        engine.generate(model, "hi") { pieces.append(it); true }
        return pieces.toString()
    }

    private suspend fun failureOf(engine: LocalInferenceEngine, model: LocalModel): Throwable? =
        runCatching { engine.generate(model, "hi") { true } }.exceptionOrNull()

    // ---- laziness ----

    @Test
    fun `nothing is loaded until a request needs the model`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)

        assertTrue(engine.isAvailable)
        assertNull(engine.loadedModelId)
        assertTrue("no model may be resident before it is asked for", fake.sessions.isEmpty())

        assertEquals("hello", generateOnce(engine, installed))
        assertEquals(listOf(File(modelsDir, "alpha.gguf").absolutePath), fake.sessions.map { it.path })
        assertEquals("alpha", engine.loadedModelId)
    }

    @Test
    fun `a second request reuses the model already in memory`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)

        engine.generate(installed, "one") { true }
        engine.generate(installed, "two") { true }

        assertEquals(1, fake.sessions.size)
        assertEquals(2, fake.sessions.single().requests.size)
        assertEquals(0, fake.sessions.single().closes)
    }

    // ---- only one model resident ----

    @Test
    fun `choosing another model unloads the one in memory`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val alpha = install(repository, "alpha", payload)
        val beta = install(repository, "beta", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)

        engine.generate(alpha, "a") { true }
        engine.generate(beta, "b") { true }

        assertEquals(listOf("alpha.gguf", "beta.gguf"), fake.sessions.map { File(it.path).name })
        assertEquals(1, fake.sessions.first().closes)
        assertEquals("beta", engine.loadedModelId)
    }

    @Test
    fun `changing a load-time setting reloads the model`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)

        engine.generate(installed, "first") { true }
        repository.updateConfiguration(
            "alpha",
            LocalModelConfiguration(runtime = LocalRuntimeSettings(contextSize = 4096, threadCount = 2))
        )
        engine.generate(installed, "second") { true }

        assertEquals(2, fake.sessions.size)
        assertEquals(1, fake.sessions.first().closes)
        assertEquals(listOf(LocalRuntimeSettings.DEFAULT_CONTEXT, 4096), fake.sessions.map { it.runtime.contextSize })
        // "use all" was resolved at load, and the saved thread count is what a request runs with.
        assertEquals(listOf(8, 2), fake.sessions.map { it.runtime.threadCount })
    }

    @Test
    fun `thread count zero means the engine's own count, not one`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine().apply { threadCount = 6 }
        val engine = engineFor(repository, fake)

        engine.generate(installed, "hi") { true }

        assertEquals(6, fake.sessions.single().runtime.threadCount)
    }

    // ---- integrity before inference ----

    @Test
    fun `a model that is not installed is refused before the engine is touched`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)

        val error = failureOf(engine, model("alpha"))

        assertTrue(error is LocalEngineException)
        assertTrue(error!!.message!!.contains("not installed"))
        assertTrue(fake.sessions.isEmpty())
    }

    @Test
    fun `a file that no longer matches its checksum is not handed to the engine`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        // Same length, different contents: only the hash can see this.
        File(modelsDir, "alpha.gguf").writeBytes(payload.copyOf().also { it[2048] = 0x7F.toByte() })
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)

        val error = failureOf(engine, installed)

        assertTrue(error is LocalEngineException)
        assertTrue(error!!.message!!.contains("checksum"))
        assertTrue("a model that failed verification must never reach the runtime", fake.sessions.isEmpty())
    }

    @Test
    fun `a build without the engine says so instead of crashing`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val engine = engineFor(repository, FakeEngine(available = false))

        assertFalse(engine.isAvailable)
        val error = failureOf(engine, installed)

        assertTrue(error is LocalEngineException)
        assertTrue(error!!.message!!.contains("inference engine"))
    }

    // ---- memory and failure ----

    @Test
    fun `a model that cannot load stays downloaded`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine().apply { loadFailure = LocalEngineException("Not enough memory to load this model") }
        val engine = engineFor(repository, fake)

        val error = failureOf(engine, installed)

        assertEquals("Not enough memory to load this model", error?.message)
        // A device short on memory needs a smaller context, not a 142 MB re-download.
        assertEquals(LocalModelInstallStatus.INSTALLED, repository.installState("alpha").status)
        assertTrue(File(modelsDir, "alpha.gguf").isFile)
        assertNull(engine.loadedModelId)
    }

    @Test
    fun `a failure mid-generation drops the context but keeps the model`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)
        generateOnce(engine, installed)

        fake.sessions.single().failure = LocalEngineException("Failed to evaluate generated token")
        assertNotNull(failureOf(engine, installed))

        assertEquals(1, fake.sessions.single().closes)
        assertNull(engine.loadedModelId)
        assertEquals(LocalModelInstallStatus.INSTALLED, repository.installState("alpha").status)

        // The next attempt opens the model again rather than staying broken.
        fake.sessions.single().failure = null
        assertEquals("hello", generateOnce(engine, installed))
        assertEquals(2, fake.sessions.size)
    }

    /**
     * A prompt longer than the window is the request being wrong, not the model being
     * broken. Dropping the resident model here would make every turn after it pay for a
     * reload that cannot change the answer.
     */
    @Test
    fun `a prompt too long for the context leaves the model loaded`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)
        generateOnce(engine, installed)

        fake.sessions.single().failure = LocalPromptTooLongException("needs 2900 tokens, holds 2048")
        assertTrue(failureOf(engine, installed) is LocalPromptTooLongException)
        fake.sessions.single().failure = null

        assertEquals(0, fake.sessions.single().closes)
        assertEquals("alpha", engine.loadedModelId)
        assertEquals("hello", generateOnce(engine, installed))
        assertEquals(1, fake.sessions.size)
    }

    @Test
    fun `stopping the engine reaches the model that is running`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)
        generateOnce(engine, installed)

        val session = fake.sessions.single()
        session.reply = listOf("one ", "two ", "three ")

        val seen = mutableListOf<String>()
        val finish = engine.generate(installed, "hi") { piece ->
            if (seen.size == 2) {
                engine.stop() // a client that goes away stops asking for pieces
                false
            } else {
                seen += piece
                true
            }
        }

        assertEquals(LocalFinishReason.STOPPED, finish)
        assertEquals(listOf("one ", "two "), seen)
        assertEquals(1, session.aborts)
    }

    @Test
    fun `releasing gives the model's memory back`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)
        generateOnce(engine, installed)

        engine.release()

        assertNull(engine.loadedModelId)
        assertEquals(1, fake.sessions.single().closes)

        engine.generate(installed, "again") { true }
        assertEquals(2, fake.sessions.size)
    }

    @Test
    fun `running past the window ends the request instead of looping forever`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)
        generateOnce(engine, installed)
        fake.sessions.single().finish = LocalFinishReason.CONTEXT_FULL

        val finish = engine.generate(installed, "long") { true }

        assertEquals(LocalFinishReason.CONTEXT_FULL, finish)
        assertEquals(0, fake.sessions.single().closes)
    }

    // ---- chat templates ----

    private fun chatInputs(id: String) = LocalChatInputs(
        messages = listOf(
            LocalChatMessage("system", "be terse"),
            LocalChatMessage("user", "what is $id?")
        ),
        tools = listOf(LocalChatTool("lookup", "find a thing", """{"type":"object"}"""))
    )

    @Test
    fun `a turn is rendered by the model's own template and its prompt is what runs`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)

        val turn = engine.openTurn(installed, chatInputs("alpha"))
        val pieces = StringBuilder()
        val finish = engine.generate(installed, turn.prompt, turn.grammar) {
            pieces.append(it)
            true
        }

        assertEquals(LocalFinishReason.END_OF_SEQUENCE, finish)
        assertEquals(listOf("system: be terse", "user: what is alpha?").joinToString("\n"), turn.prompt)
        assertTrue(turn.expectsToolCalls)
        assertEquals(listOf(turn.prompt), fake.sessions.single().requests.map { it.prompt })
        assertEquals("hello", pieces.toString())
    }

    @Test
    fun `what a model can do is read once when it loads, not on every request`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)

        assertTrue(engine.capabilities(installed).supportsTools)
        engine.generate(installed, "hi") { true }
        engine.capabilities(installed)

        assertEquals(1, fake.sessions.size)
        assertEquals(
            "the template is a property of the loaded model, so it is read at load",
            1,
            fake.sessions.single().capabilityReads
        )
        assertEquals(true, engine.loadedCapabilities()?.supportsTools)
    }

    @Test
    fun `a model with no usable template is refused instead of formatted by guesswork`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)
        // Capabilities are read when the model loads, so a file whose template will not
        // compile is known before any turn is asked for.
        val unavailable = LocalTemplateCapabilities(
            available = false,
            usesOwnTemplate = false,
            supportsTools = false,
            supportsParallelToolCalls = false,
            supportsThinking = false,
            supportsSystemMessage = false,
            supportsTypedContent = false,
            reason = "This model's chat template could not be compiled"
        )
        val engineWithBrokenTemplate = object : LocalModelEngine by fake {
            override fun load(path: String, runtime: LocalRuntimeSettings): LoadedLocalModel =
                FakeSession(path, runtime).apply { capabilities = unavailable }
        }
        val manager = LocalInferenceEngine(repository, engineWithBrokenTemplate, Dispatchers.Unconfined)

        assertFalse(manager.capabilities(installed).available)
        val error = runCatching { manager.openTurn(installed, chatInputs("alpha")) }.exceptionOrNull()
        assertTrue(error is LocalEngineException)
        assertTrue(error!!.message!!.contains("could not be compiled"))
    }

    @Test
    fun `switching models replaces the capabilities on offer`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val alpha = install(repository, "alpha", payload)
        val beta = install(repository, "beta", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)

        engine.capabilities(alpha)
        val betaTurn = engine.openTurn(beta, chatInputs("beta"))

        assertEquals("beta", engine.loadedModelId)
        assertEquals(1, fake.sessions.first().closes)
        assertEquals(listOf("system: be terse", "user: what is beta?").joinToString("\n"), betaTurn.prompt)
        assertTrue("the new model renders the request, not the one that was swapped out",
            fake.sessions.last().turns.single().tools.isNotEmpty())
    }

    @Test
    fun `a turn the caller never closes is still not a model left loaded`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)

        val turn = engine.openTurn(installed, chatInputs("alpha")) as FakeTurn
        turn.parse("partial answer", partial = true)
        turn.close()

        assertEquals(1, turn.parses)
        assertEquals(1, turn.closes)
        // Closing a turn releases the render, not the model: the next request reuses it.
        assertEquals(1, fake.sessions.size)
        assertEquals(0, fake.sessions.single().closes)
        engine.release()
        assertEquals(1, fake.sessions.single().closes)
    }
}
