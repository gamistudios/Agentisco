package com.agentisco.workspace.terminal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

sealed class LinuxEnvironmentState {
  data object NotBootstrapped : LinuxEnvironmentState()
  data class Verifying(val bytesChecked: Long, val totalBytes: Long) : LinuxEnvironmentState()
  data class Extracting(val currentPath: String, val entriesDone: Int) : LinuxEnvironmentState()
  data class Configuring(val detail: String) : LinuxEnvironmentState()
  data object Ready : LinuxEnvironmentState()
  data class Failed(val reason: String) : LinuxEnvironmentState()
}

/**
 * Prepares the Debian-based rootfs the terminal runs inside via proot. The
 * rootfs tarball ships **inside the APK** (per-ABI, via jniLibs), so there is
 * no first-launch download: this class verifies the archive's SHA-256, extracts
 * it to app-private storage and writes the guest configuration. The first
 * terminal session then performs a real `apt-get` transaction over the network.
 */
class DebianBootstrap(
  private val context: Context,
  private val nativeBinaries: NativeBinaries
) {

  private val _state = MutableStateFlow<LinuxEnvironmentState>(LinuxEnvironmentState.NotBootstrapped)
  val state: StateFlow<LinuxEnvironmentState> = _state.asStateFlow()

  val rootfsDir: File = File(context.filesDir, "linux-rootfs")

  fun isBootstrapped(): Boolean = File(rootfsDir, ".scoos-ready").exists() && rootfsDir.resolve("bin").exists()

  private val bootstrapMutex = Mutex()

  /** Verifies, extracts and configures the bundled rootfs. Idempotent. */
  suspend fun bootstrap(): Boolean = withContext(Dispatchers.IO) {
    if (isBootstrapped()) {
      _state.value = LinuxEnvironmentState.Ready
      return@withContext true
    }
    bootstrapMutex.withLock {
    val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull()
    val entry = abi?.let { RootfsCatalog.forAbi(it) }
    val archive = nativeBinaries.rootfsArchive
    when {
      entry == null -> {
        _state.value = LinuxEnvironmentState.Failed("Unsupported device ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
        false
      }
      archive == null -> {
        _state.value = LinuxEnvironmentState.Failed("Rootfs archive missing from the APK (no librootfs for this ABI)")
        false
      }
      !nativeBinaries.isComplete() -> {
        _state.value = LinuxEnvironmentState.Failed("Missing native binaries: ${nativeBinaries.missingFiles().joinToString()}")
        false
      }
      else -> try {
        verify(archive, entry)
        if (rootfsDir.exists()) rootfsDir.deleteRecursively()
        extract()
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
  }

  private fun verify(archive: File, entry: RootfsEntry) {
    _state.value = LinuxEnvironmentState.Verifying(0, archive.length())
    val digest = MessageDigest.getInstance("SHA-256")
    var checked = 0L
    archive.inputStream().buffered(256 * 1024).use { input ->
      val buffer = ByteArray(256 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
        checked += read
        _state.value = LinuxEnvironmentState.Verifying(checked, archive.length())
      }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    check(actual == entry.sha256) {
      "Rootfs checksum mismatch: expected ${entry.sha256.take(12)}…, got ${actual.take(12)}…"
    }
  }

  private fun extract() {
    var entriesDone = 0
    GZIPInputStream(rootfsArchiveStream().buffered(256 * 1024)).use { gzip ->
      TarArchiveInputStream(gzip).use { tar ->
        while (true) {
          val archiveEntry = tar.nextEntry as? TarArchiveEntry ?: break
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
              FileOutputStream(target).use { tar.copyTo(it) }
            }
          }
          if (!archiveEntry.isDirectory) {
            try { android.system.Os.chmod(target.absolutePath, archiveEntry.mode.toInt()) } catch (_: Exception) {}
          }
          entriesDone++
          if (entriesDone % 200 == 0) {
            _state.value = LinuxEnvironmentState.Extracting(name, entriesDone)
          }
        }
      }
    }
  }

  private fun rootfsArchiveStream() = when {
    nativeBinaries.rootfs64?.exists() == true &&
      android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" -> nativeBinaries.rootfs64!!.inputStream()
    else -> nativeBinaries.rootfsArchive?.inputStream() ?: error("Rootfs archive not found")
  }

  private fun configure(entry: RootfsEntry) {
    _state.value = LinuxEnvironmentState.Configuring("Writing apt sources")
    rootfsDir.resolve("etc/resolv.conf").writeText(
      "nameserver 8.8.8.8\nnameserver 1.1.1.1\n"
    )
    // Android injects its supplementary group IDs into every child process;
    // define them in /etc/group so bash/groups don't print "cannot find name"
    // warnings at every shell start.
    rootfsDir.resolve("etc/group").appendText(
      "inet:x:3003:\n" +
        "everybody:x:9997:\n" +
        "all_a345:x:20450:\n" +
        "ext_a345:x:50450:\n"
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
