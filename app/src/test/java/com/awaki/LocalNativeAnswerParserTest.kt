package com.awaki

import com.awaki.local.jni.NativeAnswerParser
import com.awaki.local.runtime.LocalAnswerDelta
import com.awaki.local.runtime.LocalToolCall
import com.awaki.local.server.Answer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The corpus the Python server's answer parser was checked against, replayed on the Kotlin reader
 * that replaces it. Every case is a text a real model has written, and the two readers disagreeing
 * about one of them is a tool call that runs on one engine and reaches the user as prose on the
 * other.
 */
@RunWith(RobolectricTestRunner::class)
class LocalNativeAnswerParserTest {

  private val names = listOf("search_files", "read_file", "write_file")

  private class Read(val content: String, val calls: List<LocalToolCall>)

  /**
   * Compares two argument texts the way the tool caller receives them: as the JSON each one says,
   * not as the string literal that happened to be typed. (Android's `JSONObject` is not comparable
   * by content, so `assertEquals` on two parsed objects says no to two identical documents.)
   */
  private fun assertJson(expected: String, actual: String) {
    assertEquals(JSONObject(expected).toString(), JSONObject(actual).toString())
  }

  /** Reads [text] in [chunks], reassembling the deltas the way the loopback server does. */
  private fun parse(text: String, chunks: List<String>? = null): Read {
    val parser = NativeAnswerParser(names = names)
    val answer = Answer()
    fun publish(deltas: List<LocalAnswerDelta>) = deltas.forEach { answer.append(it) }
    for (piece in (chunks ?: listOf(text))) publish(parser.feed(piece))
    publish(parser.feed("", final = true))
    val ids = answer.toolCalls.map { it.id }
    assertEquals("every call needs its own id", ids.size, ids.distinct().size)
    return Read(answer.content, answer.toolCalls)
  }

  @Test
  fun `a bracketed call is one call`() {
    val read = parse("[search_files(query=\"model list\")]")
    assertEquals(1, read.calls.size)
    assertEquals("search_files", read.calls[0].name)
    assertJson("""{"query": "model list"}""", read.calls[0].argumentsJson)
    assertEquals("", read.content)
  }

  @Test
  fun `a call with no envelope at all is still one call`() {
    val read = parse("search_files(query=\"model list\")")
    assertEquals(1, read.calls.size)
    assertJson("""{"query": "model list"}""", read.calls[0].argumentsJson)
    assertEquals("", read.content)
  }

  @Test
  fun `parallel calls inside one envelope are read as the two calls they are`() {
    val read = parse("[search_files(query=\"a, b\", limit=3), read_file(path=\"x.py\")]")
    assertEquals(2, read.calls.size)
    assertEquals("search_files", read.calls[0].name)
    assertJson("""{"query": "a, b", "limit": 3}""", read.calls[0].argumentsJson)
    assertEquals("read_file", read.calls[1].name)
    assertJson("""{"path": "x.py"}""", read.calls[1].argumentsJson)
    assertEquals("", read.content)
  }

  @Test
  fun `booleans and newline escapes survive the argument reader`() {
    val read = parse("write_file(path=\"out.txt\", content=\"line1\\nline2\", overwrite=true)")
    assertEquals(1, read.calls.size)
    val arguments = JSONObject(read.calls[0].argumentsJson)
    assertEquals("out.txt", arguments.getString("path"))
    assertEquals("line1\nline2", arguments.getString("content"))
    assertTrue(arguments.getBoolean("overwrite"))
  }

  @Test
  fun `a whole number stays whole`() {
    // An integer argument written back as 3.0 is a type the tool's own schema can refuse, and a
    // refused tool reads to the model as a broken app.
    val read = parse("search_files(query=\"x\", limit=3)")
    assertEquals(3, JSONObject(read.calls[0].argumentsJson).getInt("limit"))
    assertTrue("no decimal point in the raw text", !read.calls[0].argumentsJson.contains("3.0"))
  }

  @Test
  fun `a call followed by prose keeps the prose for the user`() {
    val read = parse("search_files(query=\"q\")\n\nLet me know if you want more.")
    assertEquals(1, read.calls.size)
    assertEquals("\n\nLet me know if you want more.", read.content)
  }

  @Test
  fun `an answer with no call in it is content`() {
    val read = parse("The answer is 42.")
    assertEquals(emptyList<LocalToolCall>(), read.calls)
    assertEquals("The answer is 42.", read.content)
  }

  @Test
  fun `a json object naming the tool is a call`() {
    val read = parse("""{"name":"read_file","arguments":"{\"path\":\"a\"}"}""")
    assertEquals(1, read.calls.size)
    assertEquals("read_file", read.calls[0].name)
    assertEquals("""{"path":"a"}""", read.calls[0].argumentsJson)
    assertEquals("", read.content)
  }

  @Test
  fun `a json object carrying its arguments as data is a call`() {
    val read = parse("""{"tool": "read_file", "arguments": {"path": "a"}}""")
    assertEquals(1, read.calls.size)
    assertEquals("read_file", read.calls[0].name)
    assertJson("""{"path": "a"}""", read.calls[0].argumentsJson)
  }

  @Test
  fun `a call assembled token by token arrives as one call`() {
    val text = "read_file(path=\"a\")"
    val read = parse(text, chunks = text.map { it.toString() })
    assertEquals(1, read.calls.size)
    assertJson("""{"path": "a"}""", read.calls[0].argumentsJson)
    assertEquals("", read.content)
  }

  @Test
  fun `an envelope assembled token by token arrives as two calls`() {
    val text = "[search_files(query=\"a\"), read_file(path=\"b\")]"
    val read = parse(text, chunks = text.map { it.toString() })
    assertEquals(2, read.calls.size)
    assertEquals(listOf("search_files", "read_file"), read.calls.map { it.name })
    assertEquals("", read.content)
  }

  @Test
  fun `a call cut off mid-argument is announced but not invented`() {
    // The name is what a client can show while the arguments are still arriving; arguments the
    // model never finished are not a tool the caller may run.
    val parser = NativeAnswerParser(names = names)
    assertEquals(emptyList<LocalAnswerDelta>(), parser.feed("search_fil"))
    val announced = parser.feed("es(que")
    assertEquals(1, announced.size)
    assertEquals("search_files", announced[0].toolCall?.name)
    assertTrue("no arguments until the parenthesis closes", announced[0].toolCall?.argumentsJson.isNullOrEmpty())
  }

  @Test
  fun `a name that is not one of the offered tools is a sentence`() {
    val read = parse("The lookup(x) thing")
    assertEquals(emptyList<LocalToolCall>(), read.calls)
    assertEquals("The lookup(x) thing", read.content)
  }

  @Test
  fun `an offered name inside a longer word is not a call`() {
    val read = parse("It reads the grep_lookup(path) form.")
    assertEquals(emptyList<LocalToolCall>(), read.calls)
    assertEquals("It reads the grep_lookup(path) form.", read.content)
  }

  @Test
  fun `text already shown is always a prefix of the answer`() {
    // The engine hands a turn over to this reader only while both of them have shown nothing, and
    // a stream cannot take a piece back: prose already handed out has to stay the start of content.
    val parser = NativeAnswerParser(names = names)
    val shown = StringBuilder()
    for (piece in parser.feed("Let me check the file list. ")) shown.append(piece.content)
    assertEquals("Let me check the file list. ", shown.toString())
    val second = parser.feed("[read_file(path=\"a.txt\")]")
    for (piece in second) shown.append(piece.content)
    assertEquals("Let me check the file list. ", shown.toString())
    assertEquals(
      listOf("read_file"),
      second.mapNotNull { it.toolCall?.name }.filter { it.isNotEmpty() },
    )
    assertEquals("Let me check the file list. ", parser.content)
  }

  @Test
  fun `a nested argument object is carried as json`() {
    val read = parse("read_file(path=\"a\", ranges=[{\"from\": 1}])")
    assertEquals(1, read.calls.size)
    val arguments = JSONObject(read.calls[0].argumentsJson)
    assertEquals("a", arguments.getString("path"))
    assertEquals(1, arguments.getJSONArray("ranges").getJSONObject(0).getInt("from"))
  }

  @Test
  fun `a stop sequence ends the answer before anything after it`() {
    val parser = NativeAnswerParser(stop = listOf("<|end|>"), names = names)
    val answer = Answer()
    (parser.feed("read_file(path=\"a\")<|end|>more") + parser.feed("", final = true)).forEach { answer.append(it) }
    assertEquals(1, answer.toolCalls.size)
    assertJson("""{"path": "a"}""", answer.toolCalls[0].argumentsJson)
    assertEquals("", answer.content)
  }

  @Test
  fun `a bracket still waiting for its name is not shown until it settles`() {
    val parser = NativeAnswerParser(names = names)
    // The bracket at the end is held back: it is either the start of a list of calls or the end of
    // a sentence, and only the next token knows which.
    assertEquals("The answer is in ", parser.feed("The answer is in [").joinToString("") { it.content })
    assertEquals("[", parser.feed("", final = true).joinToString("") { it.content })
  }
}
