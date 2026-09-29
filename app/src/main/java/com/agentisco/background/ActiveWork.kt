package com.agentisco.background

/**
 * A unit of work that keeps running when Agentisco is not on screen.
 *
 * Every kind here is driven by a real process or a real socket: an agent turn
 * streams from a provider and spawns Linux commands, a build stage runs in the
 * rootfs, a bootstrap extracts a rootfs. The distinction that matters for the
 * battery is which of them needs the CPU (`[BackgroundPolicy.shouldHoldWakeLock]`)
 * and which only needs the network.
 */
enum class WorkKind {
  /** An agent turn: model streaming plus tool commands. */
  AGENT_TURN,

  /** A scripted command in the embedded Linux environment, build stage included. */
  TERMINAL,

  /** Downloading and unpacking the Debian rootfs. */
  BOOTSTRAP,

  /** Downloading an update APK. */
  UPDATE_DOWNLOAD,

  /** The user asked the CPU to stay awake for the interactive terminal. */
  TERMINAL_HOLD
}

/**
 * One live piece of work. [label] is what the notification shows and [detail]
 * the second line, so a backgrounded user can tell what Agentisco is doing
 * without opening it. [needsAttention] means the work is blocked on the user
 * (an approval or a question) and would otherwise hang forever silently.
 */
data class ActiveWork(
  val id: String,
  val kind: WorkKind,
  val label: String,
  val detail: String = "",
  val startedAt: Long = System.currentTimeMillis(),
  val needsAttention: Boolean = false,
  /**
   * False when nobody registered a way to stop this record. The notification only
   * offers Stop where the app can actually honour it, rather than pretending.
   */
  val cancellable: Boolean = false
) {
  val elapsedSeconds: Long
    get() = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0L)
}
