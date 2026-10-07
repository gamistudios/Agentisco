package com.awaki.local

import com.awaki.local.jni.AnswerReader
import com.awaki.local.jni.NativeAnswerParser
import com.awaki.local.jni.StopSequenceFilter
import com.awaki.local.runtime.LocalAnswerDelta
import com.awaki.local.runtime.LocalToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two readers of one answer, driven with the shapes a real stream arrives in: the whole text so
 * far on every read, deltas for what is new, and one final read at the end. The flag's name is the
 * engine's own — `partial` means the message is still being written.
 */
@RunWith(RobolectricTestRunner::class)
class LocalAnswerReaderTest {

  private val engine = FakeEngine()
  private val sent = mutableListOf<LocalAnswerDelta>()

  private class FakeEngine {
    val partialFlags = mutableListOf<Boolean>()
    var script: (String, Boolean) -> List<LocalAnswerDelta> = { _, _ -> emptyList() }

    fun parse(text: String, partial: Boolean): List<LocalAnswerDelta> {
      partialFlags.add(partial)
      return script(text, partial)
    }
  }

  private fun turn(fallbackNames: List<String>? = null, stops: List<String> = emptyList()): AnswerReader =
    AnswerReader(
      parse = engine::parse,
      filter = StopSequenceFilter(stops),
      fallback = fallbackNames?.let { NativeAnswerParser(stops, it) },
      onDelta = { delta -> sent.add(delta); true },
    )

  @Test
  fun `a growing answer is read as still arriving and only its last read as finished`() {
    engine.script = { _, partial -> listOf(LocalAnswerDelta(content = if (partial) "…" else "done")) }
    val reader = turn()

    assertTrue(reader.read("Keep", final = false))
    assertTrue(reader.read("Keep going", final = false))
    assertTrue(reader.read("Keep going", final = true))

    // Telling the engine a growing answer is finished is what closes half-written tool arguments
    // into {} and discards a call whose tool name has not been decoded yet.
    assertEquals(listOf(true, true, false), engine.partialFlags)
  }

  @Test
  fun `what the engine parsed goes out as it is parsed, name not yet arrived included`() {
    engine.script = { _, partial ->
      if (partial) listOf(LocalAnswerDelta(toolCallIndex = 0, toolCall = LocalToolCall("call_1", "", """{"pa""")))
      else listOf(LocalAnswerDelta(toolCallIndex = 0, toolCall = LocalToolCall("", "read_file", """th":1}""")))
    }
    val reader = turn(fallbackNames = listOf("read_file"))

    reader.read("""{"name": "read_file", "arguments": {"path":1}}""", final = false)
    reader.read("""{"name": "read_file", "arguments": {"path":1}}""", final = true)

    assertEquals(2, sent.count { it.toolCall != null })
    assertEquals("read_file", sent.last().toolCall!!.name)
  }

  /**
   * The engine's parser throws away a call whose tool name it never matched, and the fallback only
   * reads a call it recognises. A turn made of that markup is then a message with nothing in it —
   * which the user reads as the agent having answered without answering.
   */
  @Test
  fun `the words of a turn no reader made anything of still reach the caller, once`() {
    val markup = "<|" + "tool_call_start|>" + "[do_" + "the_thing(a=1)]<|" + "tool_call_end|>"
    engine.script = { _, _ -> emptyList() }
    val reader = turn(fallbackNames = listOf("read_file"))

    reader.read(markup, final = false)
    assertTrue(reader.read(markup, final = true))

    assertEquals(listOf(markup), sent.map { it.content })
  }

  @Test
  fun `an answer that ended on its stop sequence is not answered by the last-resort text`() {
    val marker = "</|" + "tool|>"
    engine.script = { _, _ -> listOf(LocalAnswerDelta(content = "all done" + marker)) }
    val reader = turn(stops = listOf(marker))

    assertFalse(reader.read("all done" + marker, final = true))

    assertEquals("all done", sent.joinToString("") { it.content })
  }
}
