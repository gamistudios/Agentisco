package com.awaki

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.awaki.data.local.LocalModelStore
import com.awaki.data.repository.LocalModelRepository
import com.awaki.data.repository.UpdateStream
import com.awaki.data.repository.UpdateStreamSource
import com.awaki.local.LocalModelAssetSource
import com.awaki.local.RemoteAssetInfo
import com.awaki.local.model.LocalModel
import com.awaki.local.model.LocalModelConfiguration
import com.awaki.local.model.LocalModelInstallStatus
import com.awaki.local.model.LocalRuntimeSettings
import com.awaki.local.runtime.LoadedLocalModel
import com.awaki.local.runtime.LoadedModelInfo
import com.awaki.local.runtime.LocalAnswerDelta
import com.awaki.local.runtime.LocalChatInputs
import com.awaki.local.runtime.LocalChatMessage
import com.awaki.local.runtime.LocalChatRequest
import com.awaki.local.runtime.LocalChatTool
import com.awaki.local.runtime.LocalEngineDiagnostics
import com.awaki.local.runtime.LocalEngineException
import com.awaki.local.runtime.LocalFinishReason
import com.awaki.local.runtime.LocalInferenceEngine
import com.awaki.local.runtime.LocalModelEngine
import com.awaki.local.runtime.LocalPromptTooLongException
import com.awaki.local.runtime.LocalTemplateCapabilities
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
 * Lifecycle rules for the on-device runtime: nothing loaded until it is needed, one model at a
 * time, a file proven before it is opened, and a failure that leaves the user's download intact.
 *
 * The repository is real — models install through the same verified transfer a device uses —
 * while the decode backend is a fake, so these tests are about ownership and integrity rather
 * than about the model runtime, which owns the chat template and the answer read-back.
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
        val requests = mutableListOf<LocalChatRequest>()
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
            supportsSystemMessage = true
        )

        override val info: LoadedModelInfo =
            LoadedModelInfo("fake", "lfm2", 65536, runtime.contextSize)

        override fun capabilities(): LocalTemplateCapabilities {
            capabilityReads++
            if (!capabilities.available) throw LocalEngineException(capabilities.reason)
            return capabilities
        }

        override fun chat(request: LocalChatRequest, onDelta: (LocalAnswerDelta) -> Boolean): LocalFinishReason {
            requests += request
            failure?.let { throw it }
            for (piece in reply) {
                if (!onDelta(LocalAnswerDelta(content = piece))) return LocalFinishReason.ABORTED
            }
            return finish
        }

        override fun abort() {
            aborts++
        }

        override fun close() {
            closes++
        }
    }

    private class FakeEngine(
        var available: Boolean = true,
        override val unavailableReason: String = NO_ENVIRONMENT
    ) : LocalModelEngine {
        val sessions = mutableListOf<FakeSession>()
        var loadFailure: Throwable? = null
        /** What this fake's build and silicon are, as the engine would report them. */
        var reportedDiagnostics: LocalEngineDiagnostics = LocalEngineDiagnostics.noEngine

        override val isAvailable: Boolean get() = available

        override fun diagnostics(): LocalEngineDiagnostics = reportedDiagnostics

        override fun load(path: String, runtime: LocalRuntimeSettings): LoadedLocalModel {
            loadFailure?.let { throw it }
            if (!available) throw LocalEngineException(unavailableReason)
            return FakeSession(path, runtime).also { sessions += it }
        }

        override fun shutdown() {}

        companion object {
            const val NO_ENVIRONMENT = "The model runtime is not installed yet."
        }
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

    private fun chatInputs(id: String) = LocalChatInputs(
        messages = listOf(
            LocalChatMessage("system", "be terse"),
            LocalChatMessage("user", "what is $id?")
        ),
        tools = listOf(LocalChatTool("lookup", "find a thing", """{"type":"object"}"""))
    )

    private suspend fun answerOnce(engine: LocalInferenceEngine, model: LocalModel): String {
        val pieces = StringBuilder()
        engine.chat(model, chatInputs("alpha")) { pieces.append(it.content); true }
        return pieces.toString()
    }

    private suspend fun failureOf(engine: LocalInferenceEngine, model: LocalModel): Throwable? =
        runCatching { engine.chat(model, chatInputs("alpha")) { true } }.exceptionOrNull()

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

        assertEquals("hello", answerOnce(engine, installed))
        assertEquals(listOf(File(modelsDir, "alpha.gguf").absolutePath), fake.sessions.map { it.path })
        assertEquals("alpha", engine.loadedModelId)
    }

    // ---- loading on purpose ----

    @Test
    fun `a preload opens the model and asks it nothing`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)

        val info = engine.preload(installed)

        assertEquals(listOf("fake", "lfm2"), listOf(info.publishedName, info.architecture))
        assertEquals(LocalRuntimeSettings.DEFAULT_CONTEXT, info.contextSize)
        assertEquals("alpha", engine.loadedModelId)
        assertEquals("alpha", engine.residentModelId.value)
        assertTrue("a load decodes nothing", fake.sessions.single().requests.isEmpty())
        // Asking again when it is already open costs no second load.
        engine.preload(installed)
        assertEquals(1, fake.sessions.size)
    }

    @Test
    fun `residency names what is in memory and clears when it is given back`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val engine = engineFor(repository, FakeEngine())

        assertNull(engine.residentModelId.value)
        engine.preload(installed)
        assertEquals("alpha", engine.residentModelId.value)
        engine.release()
        assertNull(engine.residentModelId.value)
    }

    @Test
    fun `a model that would not open is not reported as resident`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine().apply { loadFailure = LocalEngineException("The model file is corrupt") }
        val engine = engineFor(repository, fake)

        val failure = runCatching { engine.preload(installed) }.exceptionOrNull()

        assertTrue(failure is LocalEngineException)
        assertNull(engine.residentModelId.value)
    }

    @Test
    fun `a second request reuses the model already in memory`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)

        engine.chat(installed, chatInputs("one")) { true }
        engine.chat(installed, chatInputs("two")) { true }

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

        engine.chat(alpha, chatInputs("a")) { true }
        engine.chat(beta, chatInputs("b")) { true }

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

        engine.chat(installed, chatInputs("first")) { true }
        repository.updateConfiguration(
            "alpha",
            LocalModelConfiguration(runtime = LocalRuntimeSettings(contextSize = 4096, threadCount = 2))
        )
        engine.chat(installed, chatInputs("second")) { true }

        assertEquals(2, fake.sessions.size)
        assertEquals(1, fake.sessions.first().closes)
        assertEquals(
            listOf(LocalRuntimeSettings.DEFAULT_CONTEXT, 4096),
            fake.sessions.map { it.runtime.contextSize }
        )
        // What the user saved is what the runtime is handed: 0 means "this device decides", and
        // deciding the counts is the native engine's job, not this layer's.
        assertEquals(listOf(0, 2), fake.sessions.map { it.runtime.threadCount })
    }

    @Test
    fun `the transcript reaches the runtime whole, in the order it was sent`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)

        engine.chat(installed, chatInputs("alpha")) { true }

        val request = fake.sessions.single().requests.single()
        assertEquals(
            listOf("system: be terse", "user: what is alpha?"),
            request.inputs.messages.map { "${it.role}: ${it.content}" }
        )
        assertEquals(listOf("lookup"), request.inputs.tools.map { it.name })
    }

    // ---- integrity before inference ----

    @Test
    fun `a model that is not installed is refused before the runtime is touched`() = runTest {
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
    fun `a file that no longer matches its checksum is not handed to the runtime`() = runTest {
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
    fun `a device without the model runtime says so instead of crashing`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine(available = false)
        val engine = engineFor(repository, fake)

        assertFalse(engine.isAvailable)
        val error = failureOf(engine, installed)

        assertTrue(error is LocalEngineException)
        assertTrue(error!!.message!!.contains("model runtime"))
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
    fun `a failure mid-turn drops the context but keeps the model`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)
        answerOnce(engine, installed)

        fake.sessions.single().failure = LocalEngineException("Failed to evaluate generated token")
        assertNotNull(failureOf(engine, installed))

        assertEquals(1, fake.sessions.single().closes)
        assertNull(engine.loadedModelId)
        assertEquals(LocalModelInstallStatus.INSTALLED, repository.installState("alpha").status)

        // The next attempt opens the model again rather than staying broken.
        fake.sessions.single().failure = null
        assertEquals("hello", answerOnce(engine, installed))
        assertEquals(2, fake.sessions.size)
    }

    /**
     * A prompt longer than the window is the request being wrong, not the model being broken.
     * Dropping the resident model here would make every turn after it pay for a reload that
     * cannot change the answer.
     */
    @Test
    fun `a prompt too long for the context leaves the model loaded`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)
        answerOnce(engine, installed)

        fake.sessions.single().failure = LocalPromptTooLongException("needs 2900 tokens, holds 2048")
        assertTrue(failureOf(engine, installed) is LocalPromptTooLongException)
        fake.sessions.single().failure = null

        assertEquals(0, fake.sessions.single().closes)
        assertEquals("alpha", engine.loadedModelId)
        assertEquals("hello", answerOnce(engine, installed))
        assertEquals(1, fake.sessions.size)
    }

    @Test
    fun `stopping the runtime reaches the model that is running`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)
        answerOnce(engine, installed)

        val session = fake.sessions.single()
        session.reply = listOf("one ", "two ", "three ")

        val seen = mutableListOf<String>()
        val finish = engine.chat(installed, chatInputs("alpha")) { delta ->
            if (seen.size == 2) {
                // a client that goes away stops asking for pieces
                engine.stop()
                false
            } else {
                seen += delta.content
                true
            }
        }

        assertEquals(LocalFinishReason.ABORTED, finish)
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
        answerOnce(engine, installed)

        engine.release()

        assertNull(engine.loadedModelId)
        assertEquals(1, fake.sessions.single().closes)

        engine.chat(installed, chatInputs("again")) { true }
        assertEquals(2, fake.sessions.size)
    }

    @Test
    fun `running past the window ends the request instead of looping forever`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)
        answerOnce(engine, installed)
        fake.sessions.single().finish = LocalFinishReason.CONTEXT_FULL

        val finish = engine.chat(installed, chatInputs("long")) { true }

        assertEquals(LocalFinishReason.CONTEXT_FULL, finish)
        assertEquals(0, fake.sessions.single().closes)
    }

    // ---- what the file can do ----

    @Test
    fun `what a model can do is read once when it loads, not on every request`() = runTest {
        val payload = ggufBytes(4096)
        val repository = repositoryFor(payload)
        val installed = install(repository, "alpha", payload)
        val fake = FakeEngine()
        val engine = engineFor(repository, fake)

        assertTrue(engine.capabilities(installed).supportsTools)
        engine.chat(installed, chatInputs("alpha")) { true }
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
        val opened = mutableListOf<FakeSession>()
        // Capabilities are read when the model loads, so a file whose template will not compile
        // is known before any turn is asked for.
        val unavailable = LocalTemplateCapabilities(
            available = false,
            usesOwnTemplate = false,
            supportsTools = false,
            supportsParallelToolCalls = false,
            supportsThinking = false,
            supportsSystemMessage = false,
            reason = "This model's chat template could not be compiled"
        )
        val engine = object : LocalModelEngine by fake {
            override fun load(path: String, runtime: LocalRuntimeSettings): LoadedLocalModel =
                FakeSession(path, runtime).apply { capabilities = unavailable }.also { opened += it }
        }
        val manager = LocalInferenceEngine(repository, engine, Dispatchers.Unconfined)

        // Refusing to render is the load failing, not a model that answers badly: the reason
        // comes back as the engine's own words, and nothing is decoded.
        val error = runCatching { manager.capabilities(installed) }.exceptionOrNull()
        assertTrue(error is LocalEngineException)
        assertTrue(error!!.message!!.contains("could not be compiled"))

        assertTrue(runCatching { manager.chat(installed, chatInputs("alpha")) { true } }.isFailure)
        assertTrue("a model that cannot render chat is not left holding memory", opened.isNotEmpty())
        assertNull(manager.loadedModelId)
        opened.forEach {
            assertEquals("the refused model was closed again", 1, it.closes)
            assertTrue("no turn was ever asked of it", it.requests.isEmpty())
        }
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
        engine.chat(beta, chatInputs("beta")) { true }

        assertEquals("beta", engine.loadedModelId)
        assertEquals(1, fake.sessions.first().closes)
        assertEquals(
            listOf("system: be terse", "user: what is beta?"),
            fake.sessions.last().requests.single().inputs.messages.map { "${it.role}: ${it.content}" }
        )
        assertTrue(
            "the new model answers the request, not the one that was swapped out",
            fake.sessions.last().requests.single().inputs.tools.isNotEmpty()
        )
    }

    // ---- engine diagnostics ----

    /**
     * The numbers a slow or silent turn is judged by. A phone cannot be plugged into a computer to
     * find out why it decodes at a walking pace, so the screen shows what the engine itself
     * reports — and the app must report exactly that, rather than a guess about the device.
     */
    @Test
    fun `what the engine says about this build is what the app reports`() {
        val fake = FakeEngine().apply {
            reportedDiagnostics = LocalEngineDiagnostics(
                enginePresent = true,
                compiledOptimized = true,
                coresSeen = 8,
                decodeThreads = 2,
                batchThreads = 8,
                pooledWorkers = true,
                availableMb = 1_240,
                backends = listOf("armv8_2dot0", "android_armv9", "android_armv8_2_0"),
                systemInfo = "CPU: ARMV8_2_FMA"
            )
        }

        val report = engineFor(repositoryFor(ggufBytes(4096)), fake).diagnostics()

        assertEquals(fake.reportedDiagnostics, report)
        val lines = report.lines()
        assertTrue("the build must be named as optimised: $lines", lines.contains("Engine build: optimised"))
        assertTrue(lines.contains("Cores seen: 8 - 2 decoding, 8 prefiling"))
        assertTrue(lines.contains("Worker threads: reused between steps"))
        assertTrue(lines.contains("Memory available: 1240 MB"))
        assertEquals(3, lines.count { it.startsWith("Kernel set:") })
    }

    /**
     * The one fault this exists to catch. A debug build of this app compiled the inference kernels
     * with no optimisation at all, which made every model look broken rather than slow — an order
     * of magnitude of prefill time with nothing in the interface to explain it. The absence of the
     * optimisation has to be the loudest line the screen has.
     */
    @Test
    fun `a build that was not compiled to run fast says so in those words`() {
        val lines = LocalEngineDiagnostics(
            enginePresent = true,
            compiledOptimized = false,
            coresSeen = 8,
            decodeThreads = 4,
            batchThreads = 8,
            pooledWorkers = false,
            availableMb = null
        ).lines()

        assertTrue(
            "unoptimised code must be named, not left to be guessed: $lines",
            lines.first().contains("UNOPTIMISED")
        )
        assertTrue(lines.any { it.contains("rebuilt for every step") })
        // A device that reports no free memory says nothing about it rather than inventing a number.
        assertTrue(lines.none { it.contains("Memory available") })
    }

    @Test
    fun `a build with no engine at all reports the reason instead of a table of zeros`() {
        val lines = LocalEngineDiagnostics.noEngine.lines()

        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("no on-device inference engine"))
    }
}
