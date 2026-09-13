package com.agentisco.agent.tool

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of kill-switches for currently running tool executions, keyed by
 * the model's tool-call id. Terminal-backed tools (run_command, build, test)
 * register a SIGKILL-style handle so a specific in-flight call can be
 * cancelled from the UI without stopping the whole agent task.
 */
object ToolCancellation {
  private val killers = ConcurrentHashMap<String, () -> Boolean>()

  fun register(id: String, kill: () -> Boolean) {
    if (id.isNotBlank()) killers[id] = kill
  }

  fun unregister(id: String) {
    if (id.isNotBlank()) killers.remove(id)
  }

  /** Returns true when a running execution was found and killed. */
  fun kill(id: String): Boolean {
    val kill = killers.remove(id) ?: return false
    return runCatching { kill() }.getOrDefault(false)
  }
}
