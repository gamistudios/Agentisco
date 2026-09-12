package com.agentisco.workspace.terminal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.utils.IOUtils
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed class LinuxEnvironmentState {
  data object NotBootstrapped : LinuxEnvironmentState()
  data class Downloading(val bytesSoFar: Long, val totalBytes: Long) : LinuxEnvironmentState()
  data class Extracting(val currentPath: String, val entriesDone: Int) : LinuxEnvironmentState()
  data class Configuring(val detail: String) : LinuxEnvironmentState()
  data object Ready : LinuxEnvironmentState()
  data class Failed(val reason: String) : LinuxEnvironmentState()
}

/**
 * Downloads, verifies and extracts the Debian-based rootfs that the terminal
 * runs inside via proot. Nothing here is simulated: the tarball comes from the
 * official Ubuntu CD image server, is SHA-256 verified before extraction, and
 * the first boot performs a real `apt-get update && apt-get install` against
 * ports.ubuntu.com.
 */
class DebianBootstrap(
  private val context: Context,
  private val nativeBinaries: NativeBinaries
) {

  private val _state = MutableStateFlow<LinuxEnvironmentState>(initialState())
  val state: StateFlow<LinuxEnvironmentState> = _state.asStateFlow()

  val rootfsDir: File = File(context.filesDir, "linux-rootfs")
  private val archiveFile: File = File(context.cacheDir, "linux-rootfs.tar.gz")

  private val okHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

  fun isBootstrapped(): Boolean = File(rootfsDir, ".scoos-ready").exists() && rootfsDir.resolve("bin").exists()

  private fun initialState(): LinuxEnvironmentState = LinuxEnvironmentState.NotBootstrapped

  /** Runs the full bootstrap; safe to call only when not already bootstrapped. */
  suspend fun bootstrap(): Boolean = withContext(Dispatchers.IO) {
    val entry = RootfsCatalog.forDevice()
    when {
      entry == null -> { _state.value = LinuxEnvironmentState.Failed("Unsupported device ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}"); false }
      !nativeBinaries.isComplete() -> {
        _state.value = LinuxEnvironmentState.Failed("Missing native binaries: ${nativeBinaries.missingFiles().joinToString()}")
        false
      }
      else -> try {
        if (rootfsDir.exists()) rootfsDir.deleteRecursively()
        downloadAndVerify(entry)
        extract(entry)
        configure(entry)
        File(rootfsDir, ".scoos-ready").writeText(entry.fileName)
        _state.value = LinuxEnvironmentState.Ready
        true
      } catch (e: Exception) {
        _state.value = LinuxEnvironmentState.Failed(e.message ?: e.javaClass.simpleName)
        false
      }
    }
  }

  private fun downloadAndVerify(entry: RootfsEntry) {
    _state.value = LinuxEnvironmentState.Downloading(0, entry.sizeBytes)
    val request = Request.Builder().url(entry.url).build()
    okHttpClient.newCall(request).execute().use { response ->
      check(response.isSuccessful) { "Rootfs download failed: HTTP ${response.code}" }
      val body = response.body ?: error("Rootfs download returned an empty body")
      val digest = MessageDigest.getInstance("SHA-256")
      var bytesSoFar = 0L
      body.byteStream().use { input ->
        FileOutputStream(archiveFile).use { output ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            bytesSoFar += read
            _state.value = LinuxEnvironmentState.Downloading(bytesSoFar, entry.sizeBytes)
          }
        }
      }
      val actual = digest.digest().joinToString("") { "%02x".format(it) }
      check(actual == entry.sha256) {
        "Rootfs checksum mismatch: expected ${entry.sha256.take(12)}…, got ${actual.take(12)}…"
      }
    }
  }

  private fun extract(entry: RootfsEntry) {
    var entriesDone = 0
    java.util.zip.GZIPInputStream(archiveFile.inputStream().buffered(256 * 1024)).use { gzip ->
      TarArchiveInputStream(gzip).use { tar ->
        while (true) {
          val entryOrNull = tar.nextTarEntry ?: break
          val archiveEntry = entryOrNull as TarArchiveEntry
          val name = archiveEntry.name.removePrefix("./")
          if (name.isBlank()) continue
          val target = File(rootfsDir, name)
          if (!target.canonicalPath.startsWith(rootfsDir.canonicalPath)) {
            throw IllegalStateException("Refusing to extract path outside rootfs: ${archiveEntry.name}")
          }
          when {
            archiveEntry.isDirectory -> target.mkdirs()
            archiveEntry.isSymbolicLink -> {
              target.parentFile?.mkdirs()
              target.delete()
              try { android.system.Os.symlink(archiveEntry.linkName, target.absolutePath) }
              catch (_: Exception) { /* some devices deny symlinks in app data; skip */ }
            }
            archiveEntry.isLink -> {
              target.parentFile?.mkdirs()
              val linkTarget = File(rootfsDir, archiveEntry.linkName.removePrefix("./"))
              if (linkTarget.exists()) {
                target.delete()
                try { android.system.Os.link(linkTarget.absolutePath, target.absolutePath) }
                catch (_: Exception) { target.writeBytes(linkTarget.readBytes()) }
              }
            }
            else -> {
              target.parentFile?.mkdirs()
              FileOutputStream(target).use { IOUtils.copy(tar, it) }
            }
          }
          if (!archiveEntry.isDirectory) {
            // 0644/0755 permission bits from the tarball, mapped onto the owner mask.
            val mode = archiveEntry.mode
            try { android.system.Os.chmod(target.absolutePath, mode.toInt()) } catch (_: Exception) {}
          }
          entriesDone++
          if (entriesDone % 200 == 0) {
            _state.value = LinuxEnvironmentState.Extracting(name, entriesDone)
          }
        }
      }
    }
    archiveFile.delete()
  }

  private fun configure(entry: RootfsEntry) {
    _state.value = LinuxEnvironmentState.Configuring("Writing apt sources")
    rootfsDir.resolve("etc/resolv.conf").writeText(
      "nameserver 8.8.8.8\nnameserver 1.1.1.1\n"
    )
    rootfsDir.resolve("etc/apt/sources.list").writeText(
      "deb http://ports.ubuntu.com/ubuntu-ports noble main universe\n" +
        "deb http://ports.ubuntu.com/ubuntu-ports noble-updates main universe\n" +
        "deb http://ports.ubuntu.com/ubuntu-ports noble-security main universe\n"
    )
    rootfsDir.resolve("etc/profile.d/scoos.sh").writeText(
      "export SCOOS_DISTRIBUTION=\"${entry.distribution}\"\n" +
        "cd \${SCOOS_WORKDIR:-/root} 2>/dev/null\n"
    )
    rootfsDir.resolve("root/.bashrc").appendText(
      "\nexport PS1='\\u@\\h:\\w\\$ '\n"
    )
  }
}
