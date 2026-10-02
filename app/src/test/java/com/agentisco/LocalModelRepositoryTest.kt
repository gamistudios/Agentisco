package com.agentisco

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agentisco.data.local.LocalModelStore
import com.agentisco.data.repository.HttpUpdateStreamSource
import com.agentisco.data.repository.LocalModelInstallState
import com.agentisco.data.repository.LocalModelRepository
import com.agentisco.data.repository.UpdateStream
import com.agentisco.data.repository.UpdateStreamSource
import com.agentisco.local.LocalModelAssetSource
import com.agentisco.local.LocalModelCatalog
import com.agentisco.local.RemoteAssetInfo
import com.agentisco.local.model.LocalGenerationSettings
import com.agentisco.local.model.LocalModel
import com.agentisco.local.model.LocalModelConfiguration
import com.agentisco.local.model.LocalModelInstallStatus
import com.agentisco.local.model.LocalRuntimeSettings
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
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
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Behaviour tests for the local model store: what has to be true before the screen
 * may say "Installed", and what survives a restart, an interrupted transfer, a
 * manual deletion or newer bytes at the source.
 *
 * Model files are served from memory and are genuine GGUF headers, so the whole
 * install path — resume, size check, digest check, format check, atomic promotion —
 * runs exactly as it does on a device, without a network call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalModelRepositoryTest {

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

    // ---- fixtures ----

    /** A model record pointing at the in-memory asset; [remoteSize] is what the source claims. */
    private fun record(
        id: String = "lfm-test",
        remoteSize: Long,
        digest: String? = null,
        version: String = "rev-1"
    ) = LocalModel(
        id = id,
        name = "Test LFM",
        sourceUrl = "https://example.test/org/repo",
        downloadUrl = "https://example.test/org/repo/resolve/main/$id.gguf",
        sizeBytes = remoteSize,
        checksum = digest,
        version = version
    )

    private class ServingSource(
        private val payload: ByteArray,
        private val available: Int = payload.size,
        private val rangeIgnored: Boolean = false
    ) : UpdateStreamSource {
        val requestedOffsets = mutableListOf<Long>()

        override fun open(url: String, offset: Long): UpdateStream {
            requestedOffsets.add(offset)
            val start = if (rangeIgnored) 0L else offset.coerceIn(0L, payload.size.toLong())
            val from = start.toInt()
            val end = minOf(available, payload.size)
            val length = (end - from).coerceAtLeast(0)
            return UpdateStream(
                input = ByteArrayInputStream(payload, from, length),
                totalSizeHint = payload.size.toLong(),
                rangeIgnored = rangeIgnored && offset > 0L
            )
        }
    }

    /** Stops handing out bytes once [cancelAfter] have been read, after asking for a cancel. */
    private class CancellingSource(
        private val payload: ByteArray,
        private val cancelAfter: Int,
        private val onCancel: () -> Unit
    ) : UpdateStreamSource {
        val requestedOffsets = mutableListOf<Long>()

        override fun open(url: String, offset: Long): UpdateStream {
            requestedOffsets.add(offset)
            val start = offset.coerceIn(0L, payload.size.toLong()).toInt()
            val stream = object : InputStream() {
                private var handed = 0
                override fun read(): Int {
                    if (handed >= cancelAfter) {
                      onCancel()
                      return -1
                    }
                    val byte = payload[start + handed].toInt() and 0xFF
                    handed++
                    return byte
                }

                override fun available(): Int = (cancelAfter - handed).coerceAtLeast(0)
            }
            return UpdateStream(input = stream, totalSizeHint = payload.size.toLong())
        }
    }

    private class FixedRemote(private val info: RemoteAssetInfo?) : LocalModelAssetSource {
        override fun lookup(downloadUrl: String): RemoteAssetInfo? = info
    }

    private fun repository(
        source: UpdateStreamSource,
        remote: RemoteAssetInfo? = null,
        modelStore: LocalModelStore = store
    ) = LocalModelRepository(
        context,
        store = modelStore,
        streamSource = source,
        remoteSource = FixedRemote(remote),
        retryDelayMs = 0L
    )

    private fun stateOf(repo: LocalModelRepository, modelId: String): LocalModelInstallState =
        repo.installState(modelId)

    /** A real, if tiny, GGUF file: header plus the metadata keys the app reads. */
    private fun ggufBytes(
        totalSize: Int,
        architecture: String = "lfm2",
        fileType: Long = 2,
        contextLength: Long? = null
    ): ByteArray {
        require(totalSize > 128) { "the fixture has to be bigger than its own header" }
        val out = java.io.ByteArrayOutputStream()
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
        le(if (contextLength == null) 2 else 3, 8)
        text("general.architecture"); le(8, 4); text(architecture)
        text("general.file_type"); le(4, 4); le(fileType, 4)
        contextLength?.let { text("$architecture.context_length"); le(4, 4); le(it, 4) }
        while (out.size() < totalSize) out.write(0)
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun installedFile(name: String = "lfm-test") = File(modelsDir, "$name.gguf")

    private fun partialFile(name: String = "lfm-test") = File(File(modelsDir, "partials"), "$name.part")

    private fun assertSameBytes(expected: ByteArray, file: File) {
        val actual = file.readBytes()
        assertEquals(expected.size, actual.size)
        assertTrue(expected.contentEquals(actual))
    }

    // ---- a successful install ----

    @Test
    fun `installing lands the file and marks the model installed`() = runTest {
        val payload = ggufBytes(4096)
        val repo = repository(ServingSource(payload))
        val model = record(remoteSize = payload.size.toLong(), digest = sha256(payload))

        assertTrue(repo.install(model))

        assertTrue("the model must live in the repository's own directory", installedFile().isFile)
        assertSameBytes(payload, installedFile())
        assertEquals(LocalModelInstallStatus.INSTALLED, stateOf(repo, model.id).status)
        assertEquals(installedFile().absolutePath, repo.model(model.id)?.localPath)
        assertEquals(listOf(model.id), repo.installedModels().map { it.id })
    }

    @Test
    fun `a built-in catalog row installs under its own id`() = runTest {
        val payload = ggufBytes(8192)
        val repo = repository(ServingSource(payload))
        val builtIn = LocalModelCatalog.builtIn.first()
            .copy(sizeBytes = payload.size.toLong(), checksum = sha256(payload))

        assertTrue(repo.install(builtIn))

        assertEquals(
            File(modelsDir, "lfm2.5-230m-q4_0.gguf").absolutePath,
            repo.installedFile(builtIn).absolutePath
        )
        assertSameBytes(payload, repo.installedFile(builtIn))
        assertEquals(payload.size.toLong(), repo.model(builtIn.id)?.sizeBytes)
    }

    // ---- failures that must never look like success ----

    @Test
    fun `a dropped connection leaves the model uninstalled and says why`() = runTest {
        val payload = ggufBytes(4096)
        // Every attempt is cut short, so the file can never reach its expected size.
        val repo = repository(ServingSource(payload, available = 1500))
        val model = record(remoteSize = payload.size.toLong())

        assertFalse(repo.install(model))

        assertEquals(LocalModelInstallStatus.FAILED, stateOf(repo, model.id).status)
        assertTrue(stateOf(repo, model.id).error!!.contains("expected"))
        assertFalse(installedFile().exists())
        assertFalse("a failed transfer must not leave a partial to be adopted", partialFile().exists())
        assertTrue(repo.installedModels().isEmpty())
    }

    @Test
    fun `the right number of wrong bytes is still refused`() = runTest {
        val notAModel = ByteArray(4096) { (it % 251).toByte() }.also {
            // An APK's ZIP header: a real file of the right size that is not a model.
            it[0] = 0x50; it[1] = 0x4B; it[2] = 0x03; it[3] = 0x04
        }
        val repo = repository(ServingSource(notAModel))
        val model = record(remoteSize = notAModel.size.toLong())

        assertFalse(repo.install(model))

        assertTrue(stateOf(repo, model.id).error!!.contains("GGUF"))
        assertFalse(installedFile().exists())
    }

    @Test
    fun `contents that fail the published digest are refused`() = runTest {
        val payload = ggufBytes(4096)
        val repo = repository(ServingSource(payload))
        val model = record(remoteSize = payload.size.toLong(), digest = "ab".repeat(32))

        assertFalse(repo.install(model))

        assertEquals(LocalModelInstallStatus.FAILED, stateOf(repo, model.id).status)
        assertFalse(installedFile().exists())
    }

    // ---- interruption, cancel, resume ----

    @Test
    fun `cancelling keeps the bytes and the next install resumes from them`() = runTest {
        val payload = ggufBytes(4096)
        val cutAt = 1024
        var repo: LocalModelRepository? = null
        val cancelSource = CancellingSource(payload, cutAt) { repo?.cancel("lfm-test") }
        val model = record(remoteSize = payload.size.toLong(), digest = sha256(payload))

        val cancellingRepo = repository(cancelSource)
        repo = cancellingRepo
        assertFalse(cancellingRepo.install(model))

        assertEquals(LocalModelInstallStatus.NOT_INSTALLED, stateOf(cancellingRepo, model.id).status)
        assertEquals(cutAt.toLong(), stateOf(cancellingRepo, model.id).resumableBytes)
        assertEquals(cutAt.toLong(), cancellingRepo.partialBytes(model.id))
        assertTrue(partialFile().isFile)
        assertFalse(installedFile().exists())

        // A different repository instance, as after a restart, over the same bytes.
        val resuming = ServingSource(payload)
        val resumed = repository(resuming)
        assertTrue(resumed.install(model))

        assertEquals(cutAt.toLong(), resuming.requestedOffsets.first())
        assertSameBytes(payload, installedFile())
    }

    @Test
    fun `a server that ignores Range restarts rather than duplicating bytes`() = runTest {
        val payload = ggufBytes(4096)
        partialFile().parentFile?.mkdirs()
        partialFile().writeBytes(payload.copyOfRange(0, 1024))

        val ignoring = ServingSource(payload, rangeIgnored = true)
        val repo = repository(ignoring)
        val model = record(remoteSize = payload.size.toLong(), digest = sha256(payload))

        assertTrue(repo.install(model))

        // A whole body landed on top of 1024 resumable bytes: the only way the file
        // can be right is if the writer gave up the resume and started over.
        assertSameBytes(payload, installedFile())
        assertEquals(listOf(1024L), ignoring.requestedOffsets)
    }

    @Test
    fun `an interrupted transfer is still resumable after the process restarts`() = runTest {
        val payload = ggufBytes(4096)
        partialFile().parentFile?.mkdirs()
        partialFile().writeBytes(payload.copyOfRange(0, 2048))
        val model = record(remoteSize = payload.size.toLong(), digest = sha256(payload))
        store.upsert(model)

        val afterRestart = repository(ServingSource(payload))
        assertEquals(2048L, afterRestart.partialBytes(model.id))
        assertEquals(2048L, stateOf(afterRestart, model.id).resumableBytes)
        assertEquals(LocalModelInstallStatus.NOT_INSTALLED, stateOf(afterRestart, model.id).status)

        val completing = repository(ServingSource(payload))
        assertTrue(completing.install(model))
        assertEquals(LocalModelInstallStatus.INSTALLED, stateOf(completing, model.id).status)
    }

    @Test
    fun `an installed model is not downloaded again by another tap`() = runTest {
        val payload = ggufBytes(4096)
        val source = ServingSource(payload)
        val repo = repository(source)
        val model = record(remoteSize = payload.size.toLong(), digest = sha256(payload))

        assertTrue(repo.install(model))
        // The bytes are on disk and verified, so a second tap is a no-op that
        // succeeds rather than a second 142 MB transfer.
        assertTrue(repo.install(model))

        assertEquals(LocalModelInstallStatus.INSTALLED, stateOf(repo, model.id).status)
        assertEquals(1, source.requestedOffsets.size)
    }

    // ---- restarts and integrity ----

    @Test
    fun `an install survives an app restart`() = runTest {
        val payload = ggufBytes(4096)
        val repo = repository(ServingSource(payload))
        val model = record(remoteSize = payload.size.toLong(), digest = sha256(payload))
        assertTrue(repo.install(model))

        val restarted = LocalModelRepository(
            context,
            store = LocalModelStore(context),
            streamSource = HttpUpdateStreamSource(OkHttpClient()),
            remoteSource = FixedRemote(null)
        )

        assertEquals(LocalModelInstallStatus.INSTALLED, restarted.installState(model.id).status)
        assertEquals(installedFile().absolutePath, restarted.model(model.id)?.localPath)
        assertTrue(restarted.verifyInstalled(model.id))
    }

    @Test
    fun `a file the user deleted is not installed however the record remembers it`() = runTest {
        val payload = ggufBytes(4096)
        val repo = repository(ServingSource(payload))
        val model = record(remoteSize = payload.size.toLong(), digest = sha256(payload))
        assertTrue(repo.install(model))
        installedFile().delete()

        val restarted = repository(ServingSource(payload))

        assertEquals(LocalModelInstallStatus.NOT_INSTALLED, restarted.installState(model.id).status)
        assertNull(restarted.model(model.id)?.localPath)
        assertTrue(restarted.installedModels().isEmpty())
    }

    @Test
    fun `a model file that lost bytes underneath us fails verification`() = runTest {
        val payload = ggufBytes(4096)
        val repo = repository(ServingSource(payload))
        val model = record(remoteSize = payload.size.toLong(), digest = sha256(payload))
        assertTrue(repo.install(model))

        installedFile().writeBytes(payload.copyOfRange(0, 1000))

        val restarted = repository(ServingSource(payload))
        assertEquals(LocalModelInstallStatus.NOT_INSTALLED, restarted.installState(model.id).status)
        assertFalse(restarted.verifyInstalled(model.id))
    }

    @Test
    fun `a model whose contents changed at the same size is caught by its digest`() = runTest {
        val payload = ggufBytes(4096)
        val repo = repository(ServingSource(payload))
        val model = record(remoteSize = payload.size.toLong(), digest = sha256(payload))
        assertTrue(repo.install(model))

        // Same length, different bytes: only hashing can see this one.
        installedFile().writeBytes(payload.copyOf().also { it[2048] = 0x7F.toByte() })

        val checking = repository(ServingSource(payload))
        assertFalse(checking.verifyInstalled(model.id))
        assertEquals(LocalModelInstallStatus.FAILED, checking.installState(model.id).status)
    }

    // ---- updates ----

    @Test
    fun `newer bytes at the source offer an update and replace the file`() = runTest {
        val payload = ggufBytes(4096)
        val repo = repository(ServingSource(payload), remote = RemoteAssetInfo(payload.size.toLong(), sha256(payload), "rev-1"))
        val model = record(remoteSize = payload.size.toLong(), digest = sha256(payload))
        assertTrue(repo.install(model))

        val newer = payload.copyOf().also { it[500] = 0x11.toByte() }
        val updating = repository(
            ServingSource(newer),
            remote = RemoteAssetInfo(newer.size.toLong(), sha256(newer), "rev-2")
        )
        updating.refreshRemoteInfo()
        assertEquals(LocalModelInstallStatus.UPDATE_AVAILABLE, updating.installState(model.id).status)

        assertTrue(updating.install(updating.model(model.id)!!))

        assertEquals(LocalModelInstallStatus.INSTALLED, updating.installState(model.id).status)
        assertSameBytes(newer, installedFile())
        assertEquals("rev-2", store.evidence(model.id)?.version)
    }

    @Test
    fun `an installed model with a matching source is not offered an update`() = runTest {
        val payload = ggufBytes(4096)
        val repo = repository(ServingSource(payload))
        val model = record(remoteSize = payload.size.toLong(), digest = sha256(payload))
        assertTrue(repo.install(model))

        val unchanged = repository(ServingSource(payload), remote = RemoteAssetInfo(payload.size.toLong(), sha256(payload), "rev-1"))
        unchanged.refreshRemoteInfo()

        assertEquals(LocalModelInstallStatus.INSTALLED, unchanged.installState(model.id).status)
    }

    // ---- deletion ----

    @Test
    fun `uninstalling removes the bytes and keeps the model row`() = runTest {
        val payload = ggufBytes(4096)
        val repo = repository(ServingSource(payload))
        val model = record(remoteSize = payload.size.toLong(), digest = sha256(payload))
        assertTrue(repo.install(model))

        assertTrue(repo.uninstall(model))

        assertFalse(installedFile().exists())
        assertNotNull(repo.model(model.id))
        assertEquals(LocalModelInstallStatus.NOT_INSTALLED, repo.installState(model.id).status)
        assertNull(store.evidence(model.id))
    }

    @Test
    fun `a custom model can be removed entirely`() = runTest {
        val repo = repository(ServingSource(ggufBytes(4096)))
        val added = repo.addCustom(
            name = "Doomed",
            downloadUrl = "https://example.test/org/repo/resolve/main/doomed.gguf"
        ).getOrThrow()

        assertTrue(repo.remove(added.id))

        assertNull(repo.model(added.id))
        assertNull(store.model(added.id))
    }

    @Test
    fun `a built-in model cannot be deleted, only uninstalled`() = runTest {
        val repo = repository(ServingSource(ggufBytes(4096)))
        assertFalse(repo.remove(LocalModelCatalog.builtIn.first().id))
    }

    // ---- custom models ----

    @Test
    fun `a custom model is installed through the same door as a catalog entry`() = runTest {
        val payload = ggufBytes(6000, fileType = 15)
        val repo = repository(ServingSource(payload))
        val added = repo.addCustom(
            name = "My Q4_K_M model",
            downloadUrl = "https://example.test/org/repo/resolve/main/model.gguf",
            description = "Added by URL",
            sizeBytes = payload.size.toLong(),
            checksum = sha256(payload)
        ).getOrThrow()

        assertFalse(added.builtIn)
        assertEquals("", added.quantization) // nothing has been read yet, so nothing is claimed
        assertTrue(repo.install(added))

        val stored = repo.model(added.id)!!
        assertEquals(LocalModelInstallStatus.INSTALLED, repo.installState(added.id).status)
        assertEquals("Q4_K_M", stored.quantization)
        assertEquals("Added by URL", stored.description)
    }

    @Test
    fun `an untrusted URL is refused before a single byte is asked for`() = runTest {
        val source = ServingSource(ggufBytes(4096))
        val repo = repository(source)
        listOf(
            "http://example.test/model.gguf",
            "ftp://example.test/model.gguf",
            "https://example.test/model.bin",
            "https://example.test/download",
            "not a url",
            ""
        ).forEach { url ->
            val result = repo.addCustom(name = "Candidate", downloadUrl = url)
            assertTrue("must refuse $url", result.isFailure)
        }
        assertTrue("refusing must not touch the network", source.requestedOffsets.isEmpty())
    }

    @Test
    fun `a nameless model is refused`() = runTest {
        val repo = repository(ServingSource(ggufBytes(4096)))
        assertTrue(repo.addCustom(name = "   ", downloadUrl = "https://example.test/a/b/resolve/main/x.gguf").isFailure)
    }

    @Test
    fun `a hostile model name cannot escape the model directory`() = runTest {
        val payload = ggufBytes(4096)
        val repo = repository(ServingSource(payload))
        val added = repo.addCustom(
            name = "../../../../escape",
            downloadUrl = "https://example.test/org/repo/resolve/main/x.gguf",
            sizeBytes = payload.size.toLong(),
            checksum = sha256(payload)
        ).getOrThrow()

        assertTrue(repo.install(added))

        val file = repo.installedFile(added)
        assertEquals(modelsDir.canonicalFile, file.parentFile!!.canonicalFile)
        assertTrue(file.canonicalFile.path.startsWith(modelsDir.canonicalFile.path + File.separator))
    }

    // ---- configuration ----

    @Test
    fun `per model settings persist across a restart and reset to defaults`() = runTest {
        val payload = ggufBytes(4096)
        val repo = repository(ServingSource(payload))
        val model = record(remoteSize = payload.size.toLong(), digest = sha256(payload))
        assertTrue(repo.install(model))

        repo.updateConfiguration(
            model.id,
            LocalModelConfiguration(
                runtime = LocalRuntimeSettings(contextSize = 4096, threadCount = 4, batchSize = 64),
                generation = LocalGenerationSettings(
                    maxOutputTokens = 512,
                    temperature = 0.7,
                    topK = 40,
                    topP = 0.9,
                    repeatPenalty = 1.1
                )
            )
        )

        val restarted = LocalModelStore(context)
        val saved = restarted.model(model.id)!!.configuration
        assertEquals(4096, saved.runtime.contextSize)
        assertEquals(4, saved.runtime.threadCount)
        assertEquals(64, saved.runtime.batchSize)
        assertEquals(512, saved.generation.maxOutputTokens)
        assertEquals(0.7, saved.generation.temperature, 0.0)
        assertEquals(0.9, saved.generation.topP, 0.0)
        assertEquals(1.1, saved.generation.repeatPenalty, 0.0)

        repository(ServingSource(payload), modelStore = restarted).resetConfiguration(model.id)

        assertEquals(LocalModelConfiguration.Defaults, LocalModelStore(context).model(model.id)!!.configuration)
    }

    @Test
    fun `a settings change does not make an installed model look uninstalled`() = runTest {
        val payload = ggufBytes(4096)
        val repo = repository(ServingSource(payload))
        val model = record(remoteSize = payload.size.toLong(), digest = sha256(payload))
        assertTrue(repo.install(model))

        repo.updateConfiguration(model.id, LocalModelConfiguration.Defaults.copy(runtime = LocalRuntimeSettings(contextSize = 512)))

        assertEquals(LocalModelInstallStatus.INSTALLED, repo.installState(model.id).status)
        assertEquals(512, repo.configuration(model.id).runtime.contextSize)
        assertTrue(installedFile().isFile)
    }

    /**
     * The context a fresh install opens with is the one thing that can make every later
     * request fail: an agent turn carries the playbook and the list of tools before the
     * user's message does. A model that cannot hold that window is given its own rather
     * than a number it was never trained for.
     */
    @Test
    fun `installing a model with a short training window gives it that window`() = runTest {
        val payload = ggufBytes(4096, contextLength = 2048)
        val repo = repository(ServingSource(payload))
        val model = record(remoteSize = payload.size.toLong(), digest = sha256(payload))
        assertTrue(repo.install(model))

        assertEquals(2048, repo.configuration(model.id).runtime.contextSize)
    }

    @Test
    fun `a model with room to spare keeps the default window`() = runTest {
        val payload = ggufBytes(4096, contextLength = 32_768)
        val repo = repository(ServingSource(payload))
        val model = record(remoteSize = payload.size.toLong(), digest = sha256(payload))
        assertTrue(repo.install(model))

        assertEquals(LocalRuntimeSettings.DEFAULT_CONTEXT, repo.configuration(model.id).runtime.contextSize)
    }

    @Test
    fun `a context the user chose is not undone by installing the model again`() = runTest {
        val payload = ggufBytes(4096, contextLength = 2048)
        val repo = repository(ServingSource(payload))
        val model = record(remoteSize = payload.size.toLong(), digest = sha256(payload))
        assertTrue(repo.install(model))
        repo.updateConfiguration(
            model.id,
            LocalModelConfiguration.Defaults.copy(runtime = LocalRuntimeSettings(contextSize = 1024))
        )

        assertTrue(repo.install(repo.model(model.id)!!))

        assertEquals(1024, repo.configuration(model.id).runtime.contextSize)
    }

    // ---- listing ----
    @Test
    fun `installed models sort ahead of the ones still to download`() = runTest {
        val payload = ggufBytes(4096)
        val repo = repository(ServingSource(payload))
        val added = repo.addCustom(
            name = "Aardvark",
            downloadUrl = "https://example.test/org/repo/resolve/main/a.gguf"
        ).getOrThrow()
        repo.install(record(remoteSize = payload.size.toLong(), digest = sha256(payload)))

        val names = repo.models.value.map { it.name }

        assertEquals("Test LFM", names.first())
        assertTrue(names.contains("Aardvark"))
        assertEquals(listOf("lfm-test"), repo.selectableModels().map { it.id })
        assertTrue(repo.models.value.any { it.id == added.id && !it.installed })
    }
}
