package com.agentisco.workspace.buildrun

/**
 * Extracts candidate local HTTP endpoints from a dev-server log line so the
 * preview can open the page the run command actually serves. Only loopback
 * hosts are considered: the embedded Linux environment shares the device's
 * network stack, so anything the server binds on the device loopback is
 * reachable from the app itself.
 */
object BuildRunPortParser {

  private val HOST_PORT_PATTERN =
    Regex("""(?:(?:https?)://)?(localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1?])(?::(\d{2,5}))?""")

  private val KEYWORD_PATTERN =
    Regex("""(?i)(?:listening(?:\s+(?:on|at))?|running(?:\s+(?:on|at))?|started(?:\s+(?:on|at))?|available(?:\s+(?:on|at))?|\bport\b)\D{0,20}?(\d{4,5})\b""")

  /** All loopback endpoints mentioned in [line], deduplicated by port. */
  fun parse(line: String): List<PreviewEndpoint> {
    val found = LinkedHashMap<Int, PreviewEndpoint>()
    HOST_PORT_PATTERN.findAll(line).forEach { match ->
      val port = match.groupValues[2].toIntOrNull() ?: return@forEach
      if (port in 1..65535) {
        found.putIfAbsent(port, PreviewEndpoint(normalizeHost(match.groupValues[1]), port))
      }
    }
    if (found.isEmpty()) {
      KEYWORD_PATTERN.findAll(line).forEach { match ->
        val port = match.groupValues[1].toIntOrNull() ?: return@forEach
        if (port in 1..65535) found.putIfAbsent(port, PreviewEndpoint("127.0.0.1", port))
      }
    }
    return found.values.toList()
  }

  private fun normalizeHost(raw: String): String = when (raw) {
    "localhost" -> "localhost"
    "127.0.0.1" -> "127.0.0.1"
    "[::1]" -> "localhost"
    else -> "127.0.0.1" // 0.0.0.0 / [::] — the same machine's loopback
  }
}
