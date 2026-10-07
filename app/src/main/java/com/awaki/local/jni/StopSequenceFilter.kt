package com.awaki.local.jni

/**
 * Streams text out while watching for the sequences that end a turn.
 *
 * A stop sequence can straddle two pieces — `…turn` then `|>` — so anything at the tail that
 * could still turn out to be one is held back until the next piece decides it. When a match
 * finally arrives, the text before it is emitted and nothing after it is, which is what keeps a
 * model's own end-of-turn marker out of the user's conversation.
 */
internal class StopSequenceFilter(stops: List<String>) {

  private val patterns: List<String> = stops.filter { it.isNotEmpty() }.sortedBy { it.length }

  private val held = StringBuilder()

  /** The sequence that ended the stream, or null while it is still running. */
  var triggered: String? = null
    private set

  /** Text safe to hand to the client now; empty when everything is still ambiguous. */
  fun feed(piece: String): String {
    if (triggered != null) return ""
    held.append(piece)
    val text = held.toString()

    val match = earliestMatch(text)
    if (match != null) {
      val (index, stop) = match
      val emit = text.substring(0, index)
      held.setLength(0)
      triggered = stop
      return emit
    }

    val keep = ambiguousTailLength(text)
    return if (keep >= text.length) {
      ""
    } else {
      val emit = text.substring(0, text.length - keep)
      held.setLength(0)
      held.append(text, text.length - keep, text.length)
      emit
    }
  }

  /** The tail that was only ambiguous: the generation ended, so it is real text. */
  fun flush(): String {
    if (triggered != null) return ""
    val emit = held.toString()
    held.setLength(0)
    return emit
  }

  private fun earliestMatch(text: String): Pair<Int, String>? {
    var best: Pair<Int, String>? = null
    for (stop in patterns) {
      val index = text.indexOf(stop)
      if (index >= 0 && (best == null || index < best.first)) best = index to stop
    }
    return best
  }

  /** Length of the longest tail of [text] that is a strict prefix of any stop. */
  private fun ambiguousTailLength(text: String): Int {
    var longest = 0
    for (stop in patterns) {
      val max = minOf(stop.length - 1, text.length)
      for (size in max downTo longest + 1) {
        if (text.regionMatches(text.length - size, stop, 0, size)) {
          longest = size
          break
        }
      }
    }
    return longest
  }
}
