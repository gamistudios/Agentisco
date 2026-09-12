package com.agentisco.workspace.terminal

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * Creates real PTY-backed terminal sessions running `bash -l` inside the
 * Debian-based rootfs through proot. Output is raw xterm-256color, rendered by
 * the vendored termux terminal emulator; apt, vim, htop and every other
 * interactive program work because a genuine pseudoterminal is used.
 */
class ProotSessionManager(
  context: Context,
  private val nativeBinaries: NativeBinaries,
  private val rootfsDir: File
) {

  private val appFilesDir: File = context.filesDir

  /**
   * Launches a session. Returns null when the environment is not bootstrapped
   * or the native binaries are missing — the UI shows the bootstrap flow in
   * that case instead of a shell.
   */
  fun createSession(
    name: String,
    projectDir: File?,
    client: TerminalSessionClient
  ): TerminalSession? {
    if (!nativeBinaries.isComplete()) return null
    if (!File(rootfsDir, ".scoos-ready").exists()) return null

    val argsBuilder = ProotArgsBuilder(nativeBinaries, rootfsDir)

    // First boot inside a PTY performs the initial real apt transaction.
    val shellScript = buildString {
      append("if [ ! -f /root/.scoos-firstboot ]; then ")
      append("echo 'ScoOS Linux: performing first-boot setup (real apt)...'; ")
      append("apt-get update && ")
      append("apt-get install -y --no-install-recommends git openssh-client ca-certificates && ")
      append("touch /root/.scoos-firstboot; ")
      append("echo 'First-boot setup complete.'; ")
      append("fi; ")
      append("exec bash -l")
    }

    val guestCommand = listOf(
      "/usr/bin/env", "-i"
    ) + argsBuilder.guestEnv().map { (k, v) -> "$k=$v" } + listOf(
      "/bin/bash", "-lc", shellScript
    )

    val (argv, hostEnv) = argsBuilder.buildCommand(guestCommand)
    val envArray = hostEnv.map { (k, v) -> "$k=$v" }.toTypedArray()

    val cwd = projectDir?.takeIf { it.isDirectory && it.canRead() } ?: appFilesDir

    return TerminalSession(
      argv.first(),
      cwd.absolutePath,
      argv.drop(1).toTypedArray(),
      envArray,
      null,
      client
    ).also { it.mSessionName = name }
  }
}
