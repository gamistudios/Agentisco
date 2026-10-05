package com.awaki

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.awaki.data.local.LocalModelStore
import com.awaki.data.repository.HttpUpdateStreamSource
import com.awaki.data.repository.LocalModelRepository
import com.awaki.local.LocalModelAssetSource
import com.awaki.local.model.LocalModelInstallStatus
import com.awaki.local.model.LocalModelInstallStatus as Status
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Importing a model file the user already has.
 *
 * A picked file is treated exactly like a downloaded one: it lands as a temporary
 * sibling, has to prove itself as GGUF before a single megabyte is wasted, and is
 * renamed into place only once the whole header parses. What is different is what a
 * failure costs — an import that is refused leaves no row behind, because there is no
 * URL to try again from and a model nobody can run is only clutter to delete.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalModelImportTest {

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

  @After
  fun tearDown() {
    modelsDir.deleteRecursively()
  }

  // ---- fixtures ----

  private class NoSource : LocalModelAssetSource {
    override fun lookup(downloadUrl: String): com.awaki.local.RemoteAssetInfo? = null
  }

  private fun repository() = LocalModelRepository(
    context,
    store = store,
    streamSource = HttpUpdateStreamSource(OkHttpClient()),
    remoteSource = NoSource()
  )

  /** A real GGUF header plus padding, big enough to be copied in more than one read. */
  private fun gguf(totalSize: Int, architecture: String = "lfm2", contextLength: Long? = null): ByteArray {
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
    text("general.file_type"); le(4, 4); le(2L, 4)
    contextLength?.let { text("$architecture.context_length"); le(4, 4); le(it, 4) }
    while (out.size() < totalSize) out.write(0)
    return out.toByteArray()
  }

  private fun sha256(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
      .joinToString("") { "%02x".format(it) }

  /** A picked document: the provider hands out [payload] and answers with [name] and [size]. */
  private fun picked(
    payload: ByteArray,
    name: String = "My Model.gguf",
    size: Long? = payload.size.toLong(),
    uri: String = "content://downloads/documents/model"
  ): Uri {
    val parsed = Uri.parse(uri)
    val resolver = shadowOf(context.contentResolver)
    resolver.registerInputStream(parsed, ByteArrayInputStream(payload))
    if (name.isNotEmpty()) {
      resolver.setCursor(parsed, OfferingCursor().apply { offer(name, size) })
    }
    return parsed
  }

  /** A content provider's answer about a document: the two columns the import asks for. */
  private class OfferingCursor : RoboCursor() {
    fun offer(name: String?, size: Long?) {
      columnNames = mutableListOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
      results = Array(1) { arrayOf<Any?>(name, size) }
    }
  }

  // ---- a successful import ----

  @Test
  fun `a picked file is copied, verified and installed like a downloaded one`() = runBlocking {
    val payload = gguf(600_000)
    val repo = repository()

    val result = repo.import(picked(payload))

    val model = result.getOrThrow()
    assertTrue("the bytes the import accepted are the model's", model.installed)
    assertTrue("nothing is fetched for a file that is already here", model.isImported)
    assertEquals("My Model", model.name)
    assertEquals(payload.size.toLong(), model.sizeBytes)
    val file = File(modelsDir, "${model.id}.gguf")
    assertTrue(file.isFile)
    assertEquals(payload.size.toLong(), file.length())
    assertTrue(payload.contentEquals(file.readBytes()))
    assertEquals(Status.INSTALLED, repo.installState(model.id).status)
    assertEquals(file.absolutePath, repo.model(model.id)?.localPath)
    assertEquals(listOf(model.id), repo.installedModels().map { it.id })
    // The file is its own evidence: no server published these bytes, so the digest the
    // integrity check compares against has to come from the copy itself.
    val evidence = store.evidence(model.id)
    assertNotNull(evidence)
    assertEquals(sha256(payload), evidence?.digest)
    assertEquals(payload.size.toLong(), evidence?.sizeBytes)
    assertEquals(sha256(payload), model.checksum)
    assertTrue("a model that arrived must still pass the full check", repo.verifyInstalled(model.id))
  }

  @Test
  fun `the runtime window an import gets is the one its own file can hold`() = runBlocking {
    val payload = gguf(600_000, contextLength = 2048L)

    val model = repository().import(picked(payload)).getOrThrow()

    assertEquals("Q4_0", model.quantization)
    assertEquals(2048, model.configuration.runtime.contextSize)
  }

  @Test
  fun `the name comes from the provider and the extension is only a label`() = runBlocking {
    val payload = gguf(600_000)

    val model = repository().import(picked(payload, name = "Qwen Tiny.gguf")).getOrThrow()

    assertEquals("Qwen Tiny", model.name)
    assertEquals("", model.sourceUrl)
    assertEquals("", model.downloadUrl)
  }

  @Test
  fun `a provider that says nothing still gets its file named from the URI`() = runBlocking {
    val payload = gguf(600_000)
    val uri = Uri.parse("content://downloads/documents/primary%3ADownload%2Ftiny%20model.gguf")
    shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(payload))

    val model = repository().import(uri).getOrThrow()

    assertEquals("tiny model", model.name)
    assertTrue(model.installed)
  }

  // ---- what a refusal costs ----

  @Test
  fun `a file that is not GGUF is refused before it is copied`() = runBlocking {
    val repo = repository()
    val payload = "this is a video, not a model".toByteArray(StandardCharsets.UTF_8) + ByteArray(200_000)

    val failure = runCatching { repo.import(picked(payload)).getOrThrow() }.exceptionOrNull()

    assertEquals("That is not a GGUF model file", failure?.message)
    assertTrue("the wrong bytes never reach the model directory", modelsDir.walk().none { it.isFile })
    assertEquals(emptyList<String>(), repo.models.value.filter { !it.builtIn }.map { it.id })
    assertEquals(emptyList<String>(), store.entries().map { it.model.id })
  }

  @Test
  fun `a file whose header does not parse is refused and taken back`() = runBlocking {
    val repo = repository()
    // Right magic, nonsense behind it: the copy starts, the full inspection stops it.
    val payload = "GGUF".toByteArray(StandardCharsets.UTF_8) + ByteArray(200_000) { (it % 251).toByte() }

    val failure = runCatching { repo.import(picked(payload)).getOrThrow() }.exceptionOrNull()

    assertEquals("That file is not a usable GGUF model", failure?.message)
    assertNull(repo.models.value.firstOrNull { !it.builtIn })
    assertEquals("a rejected import leaves no row to delete", emptyList<String>(), store.entries().map { it.model.id })
    assertTrue(modelsDir.walk().none { it.isFile })
  }

  @Test
  fun `an unreadable file is refused with a reason, not a crash`() = runBlocking {
    val repo = repository()
    val resolver = shadowOf(context.contentResolver)
    val uri = Uri.parse("content://downloads/documents/gone")
    resolver.registerInputStream(uri, object : InputStream() {
      override fun read(): Int = throw java.io.IOException("the file vanished")
    })

    val failure = runCatching { repo.import(uri).getOrThrow() }.exceptionOrNull()

    assertEquals("the file vanished", failure?.message)
    assertEquals(emptyList<String>(), store.entries().map { it.model.id })
  }

  @Test
  fun `a copy that lost its tail is refused instead of installed short`() = runBlocking {
    val repo = repository()
    // The provider declares twice what the stream then hands out.
    val payload = gguf(300_000)

    val failure = runCatching {
      repo.import(picked(payload, name = "Half.gguf", size = 1_200_000L)).getOrThrow()
    }.exceptionOrNull()

    assertEquals("The copy stopped after 300000 of 1200000 bytes", failure?.message)
    assertTrue("a truncated model never becomes selectable", modelsDir.walk().none { it.isFile })
    assertEquals(emptyList<String>(), store.entries().map { it.model.id })
  }

  @Test
  fun `a cancelled import stops and leaves nothing`() = runBlocking {
    val repo = repository()
    val payload = gguf(2_000_000)
    val uri = Uri.parse("content://downloads/documents/slow")
    // "slow" is the name the row is built from, so it is also the id the cancel targets.
    val modelId = "custom-slow"
    var handed = 0
    shadowOf(context.contentResolver).registerInputStream(
      uri,
      object : InputStream() {
        override fun read(block: ByteArray, offset: Int, length: Int): Int {
          if (handed >= 512 * 1024) {
            repo.cancel(modelId)
            return -1
          }
          val take = minOf(length, payload.size - handed, 64 * 1024)
          System.arraycopy(payload, handed, block, offset, take)
          handed += take
          return take
        }

        override fun read(): Int = payload[handed++].toInt() and 0xFF
      }
    )

    val failure = runCatching { repo.import(uri).getOrThrow() }.exceptionOrNull()

    assertEquals("Import cancelled", failure?.message)
    assertEquals(emptyList<String>(), store.entries().map { it.model.id })
    assertTrue(modelsDir.walk().none { it.isFile })
    assertEquals(Status.NOT_INSTALLED, repo.installState(modelId).status)
  }

  @Test
  fun `a second import of the same name is refused without touching the first`() = runBlocking {
    val payload = gguf(600_000)
    val repo = repository()
    val first = repo.import(picked(payload, uri = "content://a/1")).getOrThrow()
    val file = File(modelsDir, "${first.id}.gguf")

    val failure = runCatching { repo.import(picked(payload, uri = "content://a/2")).getOrThrow() }.exceptionOrNull()

    assertEquals("\"My Model\" is already added", failure?.message)
    assertTrue("the installed model is still there", file.isFile)
    assertEquals(1, repo.models.value.count { !it.builtIn })
  }

  // ---- an imported model's own promises ----

  @Test
  fun `an imported model has no source, so nothing can offer it an update`() = runBlocking {
    val payload = gguf(600_000)
    val repo = repository()
    val model = repo.import(picked(payload)).getOrThrow()

    val refreshed = repo.refreshRemoteInfo().first { it.id == model.id }

    assertEquals("the numbers of an imported model are its own file's", payload.size.toLong(), refreshed.sizeBytes)
    assertEquals(model.checksum, refreshed.checksum)
    assertEquals(Status.INSTALLED, repo.installState(model.id).status)
  }

  @Test
  fun `an import cannot be re-fetched, and says so instead of fetching nothing`() = runBlocking {
    val payload = gguf(600_000)
    val repo = repository()
    val model = repo.import(picked(payload)).getOrThrow()
    // The bytes are gone, which is the only state in which an install would be tried.
    repo.uninstall(model)

    assertFalse("there is no URL to install from", repo.install(model))

    assertEquals(
      "That model was imported from this device, so there is nothing to download",
      repo.installState(model.id).error
    )
  }

  @Test
  fun `deleting an imported model takes its row and its bytes`() = runBlocking {
    val payload = gguf(600_000)
    val repo = repository()
    val model = repo.import(picked(payload)).getOrThrow()
    val file = File(modelsDir, "${model.id}.gguf")
    assertTrue(file.isFile)

    assertTrue(repo.remove(model.id))

    assertFalse(file.isFile)
    assertNull(store.model(model.id))
    assertEquals(LocalModelInstallStatus.NOT_INSTALLED, repo.installState(model.id).status)
  }

  @Test
  fun `an import survives a restart with the same evidence`() = runBlocking {
    val payload = gguf(600_000)
    val model = repository().import(picked(payload)).getOrThrow()

    val restarted = repository()

    val reread = restarted.model(model.id)
    assertNotNull(reread)
    assertTrue("the file on disk is what makes it installed again", reread?.installed == true)
    assertEquals(model.checksum, reread?.checksum)
    assertEquals(model.name, reread?.name)
    assertEquals(0, restarted.partialBytes(model.id))
  }
}
