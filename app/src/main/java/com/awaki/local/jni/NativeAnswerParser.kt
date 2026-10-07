package com.awaki.local.jni

import com.awaki.local.runtime.LocalAnswerDelta
import com.awaki.local.runtime.LocalToolCall
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Reads a tool call out of an answer that carries no markup around it.
 *
 * llama.cpp's own parser is the first reader of every turn, and it is the right one: it is
 * generated from the model's chat template, so it knows exactly what that model was trained to
 * write. It cannot rescue the case this app actually hits, though. The LFM2.5 grammar insists on
 * `<|tool_call_start|>[name(args)]<|tool_call_end|>`, and the 230M weights leave the markers out
 * and write `[search_files(query="…")]` — which that grammar reads as a sentence. The call then
 * reaches the conversation as prose and no tool is ever run.
 *
 * So this class reads the raw text instead, for the turns where the engine's parser reports
 * content while this one reports a call (see [AnswerReader]). It is deliberately narrower than the
 * engine's parser rather than a replacement for it: it recognises a call only when the answer
 * names one of the tools the request offered, because that is the one signal that separates "the
 * model asked for work" from "the model wrote some brackets". Thinking stays the engine's job — an
 * answer this reader takes over has already failed the template that would have said where the
 * thinking was.
 *
 * Every piece is read against the whole answer so far instead of remembered from the last one, so
 * a marker that turns out to be prose resolves on the next token rather than poisoning a state
 * machine.
 */
internal class NativeAnswerParser(
  stop: List<String> = emptyList(),
  names: List<String> = emptyList(),
) {

  private val stopSequences: List<String> = stop.filter { it.isNotEmpty() }
  private val toolNames: List<String> = names.filter { it.isNotEmpty() }

  /**
   * Text that has to wait for the next token instead of flashing on screen as prose.
   *
   * A call announces itself with its name, and a set of them opens with a bracket, so either at
   * the end of a chunk is the head of a marker rather than an answer. The longest candidate wins:
   * `read_file` is a whole name and the head of a longer one at the same time, and holding back
   * nine characters would put a tool call on the screen as a word.
   */
  private val heldTails: List<String> = buildList {
    addAll(toolNames)
    add("[")
    add("{")
    toolNames.forEach { add("[$it") }
    addAll(stopSequences)
  }

  private val raw = StringBuilder()
  private var prose = ""
  private val calls = mutableListOf<StreamedCall>()

  /** The answer so far that this reader has classified as text the user reads. */
  val content: String get() = prose

  /** True once the answer has produced a call, however little of it has arrived. */
  val askedForTool: Boolean get() = calls.isNotEmpty()

  /** The deltas since the last piece. [final] means nothing more is coming. */
  fun feed(piece: String, final: Boolean = false): List<LocalAnswerDelta> {
    raw.append(piece)
    val deltas = mutableListOf<LocalAnswerDelta>()
    var index = 0
    for (segment in segments(final)) {
      val delta = if (segment.isCall) callDelta(index++, segment.text) else textDelta(segment.text, final)
      if (delta != null) deltas.add(delta)
    }
    return deltas
  }

  /**
   * The answer as ordered pieces of prose and calls.
   *
   * [final] is what lets the end of a stream tell the truth: markup left open when a model ran out
   * of budget is no longer a marker waiting to close but the text it wrote, and a user shown
   * nothing in its place gets an empty answer with no explanation.
   */
  private fun segments(final: Boolean): List<Segment> {
    var body = raw.toString()
    for (marker in stopSequences) {
      val at = body.indexOf(marker)
      if (at >= 0) {
        body = body.substring(0, at)
        break
      }
    }

    val found = mutableListOf<Segment>()
    var pos = 0
    while (pos < body.length) {
      val region = callRegion(body, pos) ?: break
      if (region.proseEnd > pos) found.add(Segment(body.substring(pos, region.proseEnd)))
      // A list envelope holds one call per entry, and each of them is its own call for the client
      // to assemble.
      for (entry in callEntries(body.substring(region.head, region.tail), toolNames)) {
        found.add(Segment(entry, isCall = true))
      }
      pos = maxOf(region.resume, region.head)
    }
    if (pos < body.length) found.add(Segment(body.substring(pos)))

    if (!final && found.isNotEmpty() && !found.last().isCall) {
      // The very end of the answer could still grow into a marker, so it waits for the next piece
      // instead of arriving as prose the model never wrote.
      val text = found.last().text
      found[found.size - 1] = Segment(text.substring(0, text.length - heldLength(text)))
    }
    return found
  }

  /** Where the next call starts, how far it reaches, and where the scan resumes after it. */
  private fun callRegion(body: String, pos: Int): Region? {
    val start = bareCallAt(body, pos, toolNames) ?: return null
    val end = start + callSpan(body, start).length
    return Region(proseEnd = start, head = start, tail = end, resume = end)
  }

  /** How much of this text's end could still grow into the start of a marker. */
  private fun heldLength(text: String): Int {
    var held = 0
    for (tag in heldTails) {
      for (size in minOf(tag.length, text.length) downTo held + 1) {
        if (text.endsWith(tag.substring(0, size))) {
          held = size
          break
        }
      }
    }
    return held
  }

  private fun textDelta(text: String, final: Boolean): LocalAnswerDelta? {
    val added = when {
      text.startsWith(prose) -> text.substring(prose.length)
      // A final flush is the last chance to show anything: text that shrank because a marker
      // resolved as prose is still the answer, so it goes out whole rather than not at all.
      final -> text
      else -> return null
    }
    prose = text
    return if (added.isEmpty()) null else LocalAnswerDelta(content = added)
  }

  /**
   * What this feed added to the call at [index].
   *
   * The index is the call's identity across feeds: the whole answer is re-read every time, so a
   * call that already closed must not be read again as a second one arriving.
   */
  private fun callDelta(index: Int, text: String): LocalAnswerDelta? {
    val read = readCall(text)
    while (calls.size <= index) calls.add(StreamedCall("call_${calls.size + 1}"))
    val call = calls[index]
    if (call.name.isEmpty() && read.name.isNotEmpty()) call.name = read.name

    val opening = !call.announced && call.name.isNotEmpty()
    if (opening) call.announced = true
    val added = if (read.arguments.startsWith(call.arguments)) {
      val tail = read.arguments.substring(call.arguments.length)
      call.arguments = read.arguments
      tail
    } else {
      // Arguments that got shorter mean the reader changed its mind about where the call ends,
      // and a stream cannot take a piece back: keep the text already sent.
      ""
    }
    if (!opening && added.isEmpty()) return null
    return LocalAnswerDelta(
      toolCallIndex = index,
      toolCall = LocalToolCall(
        id = if (opening) call.id else "",
        name = if (opening) call.name else "",
        argumentsJson = added,
      ),
    )
  }

  private class StreamedCall(val id: String) {
    var name: String = ""
    var arguments: String = ""
    var announced: Boolean = false
  }

  private class Segment(val text: String, val isCall: Boolean = false)

  private class Region(
    val proseEnd: Int,
    val head: Int,
    val tail: Int,
    val resume: Int,
  )
}

// ---- Reading one call --------------------------------------------------------
//
// A call is read out of a string that may still be growing, so every helper below answers two
// questions at once: what is here, and whether it is finished.

/** The bracketed run opening at [start], and whether it closed. */
private fun balanced(text: String, start: Int, opener: Char, closer: Char): Pair<String, Boolean> {
  var depth = 0
  var quoted = false
  var escaped = false
  for (index in start until text.length) {
    val char = text[index]
    if (quoted) {
      when {
        escaped -> escaped = false
        char == '\\' -> escaped = true
        char == '"' -> quoted = false
      }
    } else if (char == '"') {
      quoted = true
    } else if (char == opener) {
      depth++
    } else if (char == closer) {
      depth--
      if (depth == 0) return text.substring(start, index + 1) to true
    }
  }
  return text.substring(start) to false
}

/**
 * Where one call reaches, from where [bareCallAt] said it begins.
 *
 * Three openings, because three dialects write a call this way: a JSON object, a list envelope
 * around it, or a bare `name(arguments)` — which is what a template that prints no markup at all
 * uses, and the only one whose arguments do not end at a brace.
 */
private fun callSpan(body: String, start: Int): String {
  val first = body[start]
  if (first == '[' || first == '{') return balanced(body, start, first, if (first == '[') ']' else '}').first
  val bracket = body.indexOf('(', start)
  if (bracket < 0) return body.substring(start)
  return body.substring(start, bracket) + balanced(body, bracket, '(', ')').first
}

/** `name(arguments)` as its function, its raw argument text, and whether the parenthesis closed. */
private fun parenForm(text: String): Triple<String, String, Boolean>? {
  var body = text.trim()
  if (body.startsWith("[")) body = body.substring(1).trim()
  val match = Regex("^([A-Za-z_][\\w.-]*)\\s*\\(").find(body) ?: return null
  val (bracketed, closed) = balanced(body, match.range.last, '(', ')')
  val inner = if (closed && bracketed.length >= 2) bracketed.substring(1, bracketed.length - 1) else dropHead(bracketed)
  return Triple(match.groupValues[1], inner, closed)
}

/** Top-level comma-separated pieces of an argument list, so quotes and nesting hold. */
private fun splitArguments(text: String): List<String> {
  val parts = mutableListOf<String>()
  val current = StringBuilder()
  var depth = 0
  var quoted = false
  var escaped = false
  for (char in text) {
    if (quoted) {
      current.append(char)
      when {
        escaped -> escaped = false
        char == '\\' -> escaped = true
        char == '"' -> quoted = false
      }
      continue
    }
    when {
      char == '"' -> {
        quoted = true
        current.append(char)
      }
      char == '(' || char == '[' || char == '{' -> {
        depth++
        current.append(char)
      }
      char == ')' || char == ']' || char == '}' -> {
        depth--
        current.append(char)
      }
      char == ',' && depth == 0 -> {
        parts.add(current.toString())
        current.setLength(0)
      }
      else -> current.append(char)
    }
  }
  if (current.toString().trim().isNotEmpty()) parts.add(current.toString())
  return parts
}

/** One argument, in whatever notation the model used, as a value JSON can carry. */
private fun argumentValue(text: String): Any {
  val value = text.trim()
  jsonValue(value)?.let { return it }
  return when (value.lowercase(Locale.US)) {
    "true" -> true
    "false" -> false
    "null", "none" -> JSONObject.NULL
    else -> value
  }
}

/**
 * The text as a JSON value, or null when it is not one.
 *
 * Wrapping the text in a document beats hand-parsing the scalars: a model writes `"query"`, `3`,
 * `true` or a whole parameter object between the parentheses, and the JSON reader already knows
 * which of those a bare word is not.
 */
private fun jsonValue(text: String): Any? =
  runCatching { normalizeNumber(JSONObject("{\"v\":$text}").opt("v")) }.getOrNull()

private fun normalizeNumber(value: Any?): Any? = when (value) {
  // A whole number stays whole: an integer argument written back as 3.0 is a type a tool's own
  // schema may refuse.
  is Double -> if (!value.isNaN() && !value.isInfinite() && value == value.toLong().toDouble()) value.toLong() else value
  else -> value
}

private fun jsonText(value: Any): String = when (value) {
  is JSONObject, is JSONArray -> value.toString()
  // quote() is the same escaping the JSON writer applies to a bare string.
  is String -> JSONObject.quote(value)
  else -> value.toString()
}

/**
 * A `name(arguments)` list as the JSON text a caller runs its tool with.
 *
 * The model writes arguments the way the prompt showed them: usually `key="value"` pairs,
 * sometimes one JSON object between the parentheses. Text that is neither is not arguments the
 * caller can run, and inventing a shape for it would be a tool called with invented input.
 */
private fun callArguments(inner: String): String? {
  val text = inner.trim()
  if (text.isEmpty()) return "{}"
  (jsonValue(text) as? JSONObject)?.let { return it.toString() }
  val out = JSONObject()
  var named = 0
  for (part in splitArguments(text)) {
    val equals = part.indexOf('=')
    if (equals < 0) continue
    val key = part.substring(0, equals).trim()
    if (key.isEmpty()) continue
    named++
    out.put(key.trim('"'), argumentValue(part.substring(equals + 1)))
  }
  return if (named == 0) null else out.toString()
}

/**
 * One call's body as the calls it actually holds.
 *
 * Parallel calls travel inside a single list envelope, and every entry in it is work the caller
 * can run — reading only the first would drop the rest of the answer in silence. An envelope whose
 * entries are not calls stays one body, because a model answering with a JSON list is writing
 * content, not asking for tools.
 */
private fun callEntries(text: String, names: List<String>): List<String> {
  val body = text.trim()
  if (!body.startsWith("[")) return listOf(text)
  val (envelope, whole) = balanced(body, 0, '[', ']')
  val parts = splitArguments(if (whole) dropBoth(envelope) else dropHead(envelope))
  if (parts.size < 2) return listOf(text)
  val entries = mutableListOf<String>()
  for (part in parts) {
    val form = parenForm(part) ?: return listOf(text)
    if (names.isNotEmpty() && form.first !in names) return listOf(text)
    entries.add(part.trim())
  }
  return entries
}

/**
 * Name and arguments out of a tool call's body, which may be half-written.
 *
 * Four shapes, because that is four dialects: one JSON object reads directly; `name(arguments)`
 * has its pairs read out of the parentheses; a name on its own line with its arguments below it is
 * what the tagged dialects print, since the tag already says this is a call; and an object that is
 * still arriving is read by eye, because its arguments are being written one token at a time and a
 * client assembling a call expects raw text either way.
 */
private fun readCall(body: String): CallRead {
  val text = body.trim()
  (jsonValue(text) as? JSONObject)?.let { node ->
    val arguments = listOf("arguments", "parameters", "TOOL")
      .firstOrNull { node.has(it) }
      ?.let { node.opt(it) }
    val name = firstName(node)
    return when {
      arguments is String -> CallRead(name, arguments)
      arguments == null || arguments == JSONObject.NULL -> CallRead(name, "")
      else -> CallRead(name, jsonText(arguments))
    }
  }
  parenForm(text)?.let { (name, inner, closed) ->
    // A parenthesis that has not closed yet leaves the name known and the arguments not: a client
    // can show which tool is being asked for, and the turn is not over until they arrive.
    return CallRead(name, if (closed) callArguments(inner) ?: "" else "")
  }
  if (text.indexOf('\n') >= 0) {
    val head = text.substringBefore('\n').trim()
    if (head.isNotEmpty() && !head.startsWith("{")) return CallRead(head, text.substringAfter('\n').trim())
  }
  // Half an object: the name comes out of whatever is there and the arguments are handed on as
  // raw text, which is what a client assembling a call expects to be given.
  val named = NAME_IN_OBJECT.find(text)?.groupValues?.get(1) ?: ""
  var tail = ""
  val after = text.indexOf("\"arguments\"")
  if (after >= 0) {
    val colon = text.indexOf(':', after)
    if (colon >= 0) tail = text.substring(colon + 1).trim().trimEnd(',', '}')
  }
  return CallRead(named, tail)
}

/**
 * Where a tool call starts in an answer that carries no markup, or null.
 *
 * Two announcements, because two dialects write a call bare. A JSON object opens with a key naming
 * the tool, its function, or one of the tools that were offered; and a model trained to call
 * functions the way the prompt showed it writes `name(arguments)`, which only counts when the name
 * is one that was offered — otherwise a sentence mentioning `lookup (the tool)` reads as a call.
 * Anything else is a model answering in JSON, which is content rather than a call, and the
 * difference matters because a call ends the turn.
 */
private fun bareCallAt(body: String, pos: Int, names: List<String>): Int? {
  val keys = (listOf("name", "tool", "tool_call", "TOOL") + names).joinToString("|") { Regex.escape(it) }
  val opening = Regex("^\"(?:$keys)\"\\s*:\\s*")
  var objectAt: Int? = null
  var brace = body.indexOf('{', pos)
  while (brace >= 0) {
    val head = body.substring(brace + 1, minOf(body.length, brace + 80)).trimStart()
    if (opening.find(head) != null) {
      objectAt = brace
      break
    }
    brace = body.indexOf('{', brace + 1)
  }
  if (names.isEmpty()) return objectAt
  val spelled = names.joinToString("|") { Regex.escape(it) }
  // Not preceded by a word character: `lookup` inside `grep_lookup(` is not a call's name.
  val called = Regex("(?<![\\w.])($spelled)\\s*\\(").find(body, pos) ?: return objectAt
  var at = pos + called.range.first
  // Several calls travel inside one list envelope, and the bracket belongs to the call rather than
  // to the prose before it — including it here is what keeps it off the screen.
  var back = at - 1
  while (back >= pos && (body[back] == ' ' || body[back] == '\t' || body[back] == '\n')) back--
  if (back >= pos && body[back] == '[') at = back
  return if (objectAt == null) at else minOf(at, objectAt)
}

private class CallRead(val name: String, val arguments: String)

/** The keys a JSON-object call names its function with, wherever in the object they turn up. */
private val NAME_IN_OBJECT = Regex("\"(?:name|tool_name)\"\\s*:\\s*\"([^\"]*)\"")

private fun firstName(node: JSONObject): String {
  for (key in listOf("name", "tool_name", "tool")) {
    val value = node.opt(key)
    if (value != null && value != JSONObject.NULL && value.toString().isNotEmpty()) return value.toString()
  }
  return ""
}

private fun dropHead(text: String) = if (text.isEmpty()) "" else text.substring(1)

private fun dropBoth(text: String) = if (text.length < 2) "" else text.substring(1, text.length - 1)
