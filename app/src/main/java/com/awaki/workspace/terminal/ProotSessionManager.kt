package com.awaki.workspace.terminal

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
  private val rootfsDir: File,
  /** Host directories the shell also sees, so `~/local-models` is real in a terminal. */
  private val extraBinds: List<Pair<String, String>> = emptyList()
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
    if (!File(rootfsDir, ".awaki-ready").exists()) return null

    val argsBuilder = ProotArgsBuilder(nativeBinaries, rootfsDir, extraBinds)

    val shellScript = guestStartupScript()

    val guestCommand = listOf(
      "/usr/bin/env", "-i"
    ) + argsBuilder.guestEnv().map { (k, v) -> "$k=$v" } + listOf(
      "/bin/bash", "-lc", shellScript
    )

    // Bind the project folder into the guest and start the shell there, so
    // the terminal always opens in the workspace it was launched from.
    val (argv, hostEnv) = if (projectDir != null && projectDir.isDirectory) {
      argsBuilder.buildCommand(guestCommand, workingDir = ProotArgsBuilder.WORKSPACE_GUEST_PATH, bindHostDir = projectDir)
    } else {
      argsBuilder.buildCommand(guestCommand)
    }
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

/**
 * The one-time apt transaction a fresh rootfs runs the first time a terminal
 * opens; the marker file keeps every later session from repeating it.
 */
internal const val FIRST_BOOT_SETUP =
  "if [ ! -f /root/.awaki-firstboot ]; then " +
    "echo 'Awaki Linux: performing first-boot setup (real apt)...'; " +
    "apt-get update && " +
    "apt-get install -y --no-install-recommends git openssh-client ca-certificates && " +
    "touch /root/.awaki-firstboot; " +
    "echo 'First-boot setup complete.'; " +
    "fi; "

/**
 * Android hands the PTY process its own supplementary groups, and those GIDs
 * have no entry in the guest `/etc/group`, so `groups` and `id` print
 * "cannot find name for group ID" for each of them. The numbering differs per
 * device and per install, so it is read at runtime rather than listed here.
 * Only GIDs that resolve to nothing get an entry — existing groups are left
 * exactly as the rootfs shipped them — which makes this safe to repeat on
 * every session. A single failing `groupadd` (a race, a read-only /etc/group)
 * must never stop the shell from starting, hence `|| true`.
 */
internal const val GROUP_SYNC =
  "for gid in \$(id -G 2>/dev/null); do " +
    "if ! getent group \"\$gid\" >/dev/null 2>&1; then " +
    "groupadd -g \"\$gid\" \"grp\$gid\" 2>/dev/null || true; " +
    "fi; " +
    "done; "

/** What `/bin/bash -lc` runs when a terminal opens, before the shell takes over. */
internal fun guestStartupScript(): String =
  buildString {
    append(FIRST_BOOT_SETUP)
    append(GROUP_SYNC)
    append("exec bash -l")
  }
