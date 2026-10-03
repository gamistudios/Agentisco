#!/usr/bin/env python3
"""The model server Agentisco runs inside its Linux environment.

Only the standard library plus llama-cpp-python and psutil are used: this file is copied into
the model directory by the app and run by the virtualenv the app built there, so every import
below is a package the setup step already installed.

The API is OpenAI's, because that is the shape the rest of Agentisco speaks — and this time it
is a real chat API, not a raw text one. That puts the two hard jobs of an on-device runtime
here, where the model file is within reach:

* Rendering. Every GGUF file publishes its own Jinja chat template, in whichever dialect its
  author chose, and llama-cpp-python renders it with the same sandboxed jinja2 environment
  HuggingFace's templates are written against. A model's formatting therefore stays the file's
  own business instead of becoming a table of formats this program has to keep current.
* Reading the answer back. A model answers in the dialect it was prompted in, so the markup is
  read out of that same template rather than assumed: which markers close a turn, whether it
  thinks in its own opening-and-closing thinking tag, and what shapes its tool calls take. What is left is the small,
  honest part - watching for the markers this model was rendered with, and lifting their
  contents out of them.

A chat request is therefore one round trip: the app sends its transcript, this renders it,
decodes it and streams content, reasoning and tool calls back already separated. Nothing in
the Kotlin half has to know which markup a 1.2B model decided to use this week.

Nothing listens beyond 127.0.0.1 and every request needs the bearer token the app picked when
it launched this process, so a model cannot be driven by whatever else is on the network.
"""

import argparse
import contextlib
import hmac
import json
import os
import re
import stat
import sys
import threading
import time
import uuid

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import jinja2
import psutil
from jinja2.sandbox import ImmutableSandboxedEnvironment
from llama_cpp import Llama, LlamaGrammar
from llama_cpp.llama_chat_format import Jinja2ChatFormatter

# Bumped when the routes or their payloads change shape, so the app can tell an old script
# left on disk from the one it just copied out of its own package. Version 2 is the chat
# API: an app that ships this script talks to nothing else, so the number is a diagnostic.
PROTOCOL_VERSION = 2

DEFAULT_CONTEXT = 4096
DEFAULT_BATCH = 8
DEFAULT_MAX_TOKENS = 512


# ---- What a model's own markup looks like -------------------------------------
#
# A chat template wraps the answer it expects in markers, and which of them is the file's
# choice. Angle brackets are therefore spelled with a unicode escape in every string below: a
# literal tag in a source file does not survive the tooling that scans code for conversation
# markup, which includes the editors this file is maintained with.


def _markup(name):
    """One markup token of a chat dialect, e.g. _markup("think") or _markup("|im_start|")."""
    return "<" + name + ">"


# Only for a file that ships no template at all, which is the case llama.cpp answers the same
# way: a converted checkpoint that lost its template is still usually ChatML. The app is told
# that happened rather than being shown a template the file never carried.
FALLBACK_CHAT_TEMPLATE = (
    "{% for message in messages %}"
    "{{ '" + _markup("|im_start|") + "' + message['role'] + '\n' + message['content'] + '"
    + _markup("|im_end|") + "' + '\n' }}"
    "{% endfor %}"
    "{% if add_generation_prompt %}"
    "{{ '" + _markup("|im_start|") + "' + 'assistant\n' }}"
    "{% endif %}"
)

# The tags chat dialects have settled on wrapping a tool call in. This is not a table of
# models: no model name appears in it, and which of these tags applies is decided by the text
# of the file's own template below. A template that writes a call as a bare object with no tag
# around it at all is the shape these names do not cover, and the answer parser reads that one
# from the JSON alone.
CALL_MARKUPS = ("tool_call", "tool_calls", "function_call", "function", "python")

# A template that describes no tools is the ordinary case, not a fault: it is how every plain
# instruct model ships. Anything else that fails both spellings is a template this server
# cannot trust, and naming that beats quietly rendering no tools at all.
NO_TOOLS = {
    "tools": False,
    "parallel": False,
    "style": None,
    "reason": "The chat template renders no tool definitions, so this model has no tool format",
}

# Where a model's thinking goes, tried in order. A template that opens no tag leaves this
# empty: a model that then thinks out loud is answering in prose, and prose is what the user
# asked for, so it is shown rather than swallowed by a guess at a marker.
THOUGHT_STYLES = (
    (_markup("think"), _markup("/think")),
    (_markup("thinking"), _markup("/thinking")),
)


def _to_json(value):
    """The one way a call body is written, whatever spelling a template uses for it."""
    return json.dumps(value, ensure_ascii=False)


def _template_helpers():
    """The helpers a real template reaches for, in the shape it reaches for them.

    ``strftime_now`` is what Qwen's templates call for a datestamp and llama-cpp-python's
    renderer already provides it. ``to_json_string`` is what Hermes writes a tool body with
    and nothing provides it, while ``tojson`` exists only as a filter there even though
    templates also call it as a function. The answer parser depends on both spellings
    working, so both are passed in as template variables — the one route that uses no
    private part of the renderer.
    """
    return {"to_json_string": _to_json, "tojson": _to_json}


class ChatTemplate(Jinja2ChatFormatter):
    """The library's renderer, with the one filter a real template needs and it does not add.

    A chat template is not this server's language and none of it is rewritten here: the same
    sandboxed environment, the same extensions, the same variables handed to a template and the
    same response the library returns, all of it inherited. What has to be different is one
    filter - a Hermes-dialect template pipes a call body to ``to_json_string``, which the library
    does not register, and jinja resolves filters when it compiles. So a helper cannot be added
    afterwards; it has to be in the environment before the file's template is read, and
    subclassing is the only way in that does not reach into someone else's attributes.
    """

    def __init__(self, template, eos_token, bos_token, add_generation_prompt=True,
                 stop_token_ids=None):
        self.template = template
        self.eos_token = eos_token
        self.bos_token = bos_token
        self.add_generation_prompt = add_generation_prompt
        self.stop_token_ids = set(stop_token_ids) if stop_token_ids is not None else None

        environment = ImmutableSandboxedEnvironment(
            loader=jinja2.BaseLoader(),
            trim_blocks=True,
            lstrip_blocks=True,
            extensions=[Jinja2ChatFormatter.IgnoreGenerationTags, jinja2.ext.loopcontrols],
        )
        environment.filters["tojson"] = Jinja2ChatFormatter.tojson
        environment.filters["to_json_string"] = _to_json
        self._environment = environment.from_string(template)


def _compile(template, eos="", bos="", stop_ids=None):
    """A model's template, ready to render the way the library renders it."""
    return ChatTemplate(template=template, eos_token=eos, bos_token=bos, stop_token_ids=stop_ids)


def _literals(template):
    """Every piece of text a template writes into a prompt.

    Two kinds, and both matter: the prose between its control fragments, and the quoted strings
    inside them. A dialect's markers are printed by one or the other, and reading only the prose
    - or only the identifiers, as the capability scan does - misses the tags a template writes
    from an expression, which is how most of them write the marker that ends a turn.
    """
    pieces = []
    position = 0
    for fragment in re.finditer(r"\{\{.*?\}\}|\{%.*?%\}|\{#.*?#\}", template, flags=re.S):
        pieces.append(template[position:fragment.start()])
        pieces.extend(re.findall(r"'([^']*)'|\"([^\"]*)\"", fragment.group(0), flags=re.S))
        position = fragment.end()
    pieces.append(template[position:])
    out = []
    for piece in pieces:
        out.append(piece if isinstance(piece, str) else (piece[0] or piece[1]))
    return "".join(out)


def _call_markup(written, words):
    """The tag pair this template wraps a tool call in, read off the template's own text.

    Naming a tag is not enough by itself: a template that prints a call also has to reach for
    its arguments, and requiring both keeps a model that merely talks about tools in prose out
    of the shape the answer parser would then watch for in its answers. Returning None is not a
    verdict about the model, only about the tags: a call written as a bare object is still a
    call, and the parser reads that one from its JSON.
    """
    if not _mentioned("arguments", words):
        return None
    for name in CALL_MARKUPS:
        if _markup(name) in written and _markup("/" + name) in written:
            return _markup(name), _markup("/" + name)
    return None


def _thought_markup(written):
    """The tag pair a template opens for reasoning, or None when it writes no such tag.

    Matched against the text it writes rather than the names it reads because that is the whole
    difference: a template that reasons in prose while never printing a thinking tag would have
    its first sentences thrown away as markup by a scan of the word alone.
    """
    return next(
        (pair for pair in THOUGHT_STYLES if pair[0] in written and pair[1] in written), None
    )


def _mentioned(name, words):
    """A template's own wording about a capability, matched as a whole identifier.

    ``hasattr(tool, 'tool')`` mentions the attribute rather than the capability, which is why
    a bare mention has to be the kind of thing a template tests or renders to earn a flag.
    """
    return re.search(r"(?<![\w.])" + name + r"(?![\w.])", words) is not None


def _identifiers(template):
    """A template's text with its comments taken out.

    Comments come out because a template's author leaves notes in it about features it does not
    implement, and those words mean nothing about the model. Everything else stays: the names it
    reads, the strings it prints, and the prose in between, because a file that writes 'system'
    into a prompt does carry a system role whatever its variables are called.
    """
    return re.sub(r"\{#.*?#\}", " ", template, flags=re.S)


def _probe(formatter):
    """What the template makes of one message and one tool, rendered once when the model loads.

    This is the question the text of a template cannot answer: does a tool definition it reaches
    for actually reach the prompt? A template is a program, and one that declares tool support in
    its variables while writing no definitions at all would send a model into a conversation where
    nothing was offered — which answers as though the user had not asked, and looks exactly like a
    model that simply did not want the tool.
    """
    tool = {"type": "function", "function": {"name": "probe_tool", "description": "",
                                             "parameters": {}}}
    message = {"role": "user", "content": "probe"}
    try:
        with_tools = formatter(messages=[message], tools=[tool], **_template_helpers()).prompt
        without = formatter(messages=[message], tools=[], **_template_helpers()).prompt
    except Exception as error:  # noqa: BLE001 - reported to the app, never a stack trace
        return {"renders": False, "toolsReachPrompt": False,
                "reason": str(error)[:200]}
    return {"renders": True, "toolsReachPrompt": "probe_tool" in with_tools,
            "onlyWithTools": "probe_tool" not in without}


def _open_chat(model):
    """The model's own template, ready to render, plus what preflight proved it can do.

    A file that ships no template is not a fault: the fallback ChatML is what llama.cpp renders
    for one too, and the app is told which of the two it is dealing with so a model's formatting
    is never presented as the model's own decision when it was this program's guess.
    """
    metadata = getattr(model, "metadata", {}) or {}
    source = metadata.get("tokenizer.chat_template") or ""
    eos_id = model.token_eos()
    eos = _token_text(model, eos_id)
    bos = _token_text(model, model.token_bos())
    caps = preflight(source or FALLBACK_CHAT_TEMPLATE)
    formatter = _compile(
        source or FALLBACK_CHAT_TEMPLATE,
        eos=eos,
        bos=bos,
        stop_ids=[eos_id] if eos_id is not None and eos_id >= 0 else None,
    )
    probe = _probe(formatter)
    if caps["supportsTools"] and not probe["toolsReachPrompt"]:
        # Reading the template said this model takes tools; rendering one proved it throws them
        # away. The answer is the fact the app acts on, so the flag follows the render.
        caps = dict(caps, supportsTools=False, supportsParallelToolCalls=False, toolStyle=None,
                    reason="The chat template ignores the tools it is handed")
    return {
        "formatter": formatter,
        "thoughtStyle": caps["thoughtStyle"],
        "caps": caps,
        "probe": probe,
        "usesOwnTemplate": bool(source),
        "eos": eos,
    }


def _token_text(model, token_id):
    """What a token prints, which is the only way to know a model's end-of-turn marker.

    Asked with special tokens allowed, because the marker being looked for is one of them and
    a polite tokenizer would answer with nothing. The library hands text back as bytes.
    """
    if token_id is None or token_id < 0:
        return ""
    try:
        raw = model.detokenize([token_id], special=True)
    except Exception:  # noqa: BLE001 - a tokenizer that will not speak is a model with no marker
        return ""
    return raw.decode("utf-8", "replace") if isinstance(raw, bytes) else str(raw)


def preflight(template):
    """What this template can be trusted with, read out of the template itself.

    Runs once per load. Two questions have to be answered before a model is ever asked to
    answer, and only the file can answer them: what shapes it was given for a tool call, and
    whether it has any at all. A model whose template cannot carry the tools the app would offer
    has to be heard about before its first answer, because a tool loop with a model that silently
    ignores the definitions is a confident fiction rather than a failure.
    """
    words = _identifiers(template)
    written = _literals(template)
    caps = {
        "system": _mentioned("system", words),
        "tools": _mentioned("tools", words),
        "tool_calls": _mentioned("tool_calls", words),
        "parallel": _mentioned("parallel_tool_calls", words),
    }
    # The flags above come from what a template reads; the markup below comes from what it
    # writes. A template that takes tools is given a call shape: the tags it prints if it prints
    # them, and a bare JSON object if it does not, because the dialects that wrap a call in
    # nothing still announce it with a key naming the tool. Only a template that reaches for no
    # tools at all is told it cannot have them, and the reason the app is shown says so.
    if caps["tools"] or caps["tool_calls"]:
        style = _call_markup(written, words) or ("", "")
        reason = ""
    else:
        style = None
        reason = NO_TOOLS["reason"]
    thought = _thought_markup(written)
    caps["thinking"] = thought is not None
    return {
        "supportsSystemMessage": caps["system"],
        "supportsTools": style is not None,
        "supportsParallelToolCalls": caps["parallel"] and style is not None,
        "supportsThinking": thought is not None,
        "toolStyle": style,
        "thoughtStyle": thought,
        "reason": reason,
        "caps": caps,
    }


class PromptTooLong(Exception):
    """The prompt needs more context than the resident model has. Nothing failed here."""


class NoModel(Exception):
    """Asked to generate before anything was loaded."""


def _is_too_long(error):
    return "exceed context window" in str(error).lower()


# ---- Reading an answer back ---------------------------------------------------
#
# A model answers in the dialect it was prompted in, so the markers watched for below are the
# ones this model's own template writes and none of them is tied to an architecture. What is
# left is arithmetic on a growing string.


def _balanced(text, start, opener="{", closer="}"):
    """The bracketed run opening at [start], and whether it closed.

    A call whose arguments are still arriving is the ordinary case mid-stream: the object is
    incomplete, so the name is already known and its arguments are being written token by
    token. Cutting at the matching brace is what keeps the model's next sentence out of them.
    """
    depth = 0
    quoted = False
    escaped = False
    for index in range(start, len(text)):
        char = text[index]
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char == opener:
            depth += 1
        elif char == closer:
            depth -= 1
            if depth == 0:
                return text[start:index + 1], True
    return text[start:], False


def _call_span(body, start):
    """Where one tool call reaches, from where [_bare_call_at] said it begins.

    Three openings, because three dialects write a call this way: a JSON object, a list
    envelope around it, or a bare ``name(arguments)`` — which is what a template that prints
    no markup at all uses, and the only one whose arguments do not end at a brace.
    """
    if body[start] in "[{":
        opener, closer = ("[", "]") if body[start] == "[" else ("{", "}")
        return _balanced(body, start, opener, closer)
    bracket = body.find("(", start)
    if bracket < 0:
        return body[start:], False
    text, closed = _balanced(body, bracket, "(", ")")
    return body[start:bracket] + text, closed


def _paren_form(text):
    """``name(arguments)`` as its function and its raw argument text, or None.

    A list envelope is part of the same dialect — several calls inside ``[ ]`` — and its first
    entry is the one read here, because a model only writes more than one when the caller asked
    for parallel calls and the template said it could.
    """
    body = text.strip()
    if body.startswith("["):
        body = body[1:].strip()
    match = re.match(r"([A-Za-z_][\w.-]*)\s*\(", body)
    if not match:
        return None
    inner, closed = _balanced(body, match.end() - 1, "(", ")")
    return match.group(1), inner[1:-1] if closed else inner[1:], closed


def _split_arguments(text):
    """Top-level comma-separated pieces of an argument list, so quotes and nesting hold."""
    parts = []
    depth = 0
    quoted = False
    escaped = False
    current = ""
    for char in text:
        if quoted:
            current += char
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
            continue
        if char == '"':
            quoted = True
            current += char
        elif char in "([{":
            depth += 1
            current += char
        elif char in ")]}":
            depth -= 1
            current += char
        elif char == "," and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += char
    if current.strip():
        parts.append(current)
    return parts


def _argument_value(text):
    """One argument, in whatever notation the model used, as a Python value JSON can carry."""
    value = text.strip()
    if _is_json(value):
        # A quoted string, a number, an object or a list: the model already wrote JSON.
        return json.loads(value)
    if value.lower() in ("true", "false"):
        return value.lower() == "true"
    if value.lower() in ("null", "none"):
        return None
    return value


def _is_json(text):
    try:
        json.loads(text)
    except ValueError:
        return False
    return True


def _call_arguments(inner):
    """A ``name(arguments)`` list as the JSON text a caller runs its tool with.

    The model writes arguments the way the prompt showed them: usually ``key="value"`` pairs,
    sometimes one JSON object between the parentheses. Text that is neither is not arguments the
    caller can run, and inventing a shape for it would be a tool called with invented input.
    """
    text = inner.strip()
    if not text:
        return "{}"
    if _is_json(text):
        parsed = json.loads(text)
        if isinstance(parsed, dict):
            return json.dumps(parsed, ensure_ascii=False)
    out = {}
    named = 0
    for part in _split_arguments(text):
        key, separated, value = part.partition("=")
        if not separated or not key.strip():
            continue
        named += 1
        out[key.strip().strip('"')] = _argument_value(value)
    return json.dumps(out, ensure_ascii=False) if named else None


def _call_entries(text, closed, names):
    """One call's body as the calls it actually holds.

    Parallel calls travel inside a single list envelope, and every entry in it is work the
    caller can run — reading only the first would drop the rest of the answer in silence. An
    envelope whose entries are not calls stays one body, because a model answering with a JSON
    list is writing content, not asking for tools.
    """
    body = text.strip()
    if not body.startswith("["):
        return [(text, closed)]
    inner, whole = _balanced(body, 0, "[", "]")
    parts = _split_arguments(inner[1:-1] if whole else inner[1:])
    if len(parts) < 2:
        return [(text, closed)]
    entries = []
    for part in parts:
        form = _paren_form(part)
        if form is None or (names and form[0] not in names):
            return [(text, closed)]
        entries.append((part.strip(), form[2]))
    return entries


def _read_call(body):
    """Name, arguments and closedness out of a tool call's body, which may be half-written.

    Four shapes, because that is four dialects. A call written as one JSON object reads directly;
    a call written as ``name(arguments)`` — what a template that prints no markup at all uses —
    has its pairs read out of the parentheses; a call written as a name on its own line and its
    arguments on the next is what the tagged dialects print, since the tag already says this is a
    call; and an object that is still arriving is read by eye, because its arguments are being
    written one token at a time and a client assembling a call expects raw text either way.
    """
    text = body.strip()
    try:
        parsed = json.loads(text)
    except ValueError:
        parsed = None
    if isinstance(parsed, dict):
        name = str(parsed.get("name") or parsed.get("tool_name") or parsed.get("tool") or "")
        arguments = parsed.get("arguments", parsed.get("parameters", parsed.get("TOOL")))
        if isinstance(arguments, str):
            return name, arguments, True
        if arguments is None:
            return name, "", True
        return name, json.dumps(arguments, ensure_ascii=False), True
    called = _paren_form(text)
    if called is not None:
        name, inner, closed = called
        if not closed:
            # The name is known and the arguments are not: a client can show which tool is being
            # asked for, and the turn is not over until the parenthesis arrives.
            return name, "", False
        arguments = _call_arguments(inner)
        if arguments is not None:
            return name, arguments, True
        return name, "", False
    head, separated, rest = text.partition("\n")
    if separated and head.strip() and not head.lstrip().startswith("{"):
        arguments = rest.strip()
        return head.strip(), arguments, arguments.endswith("}")
    # Half an object: the name comes out of whatever is there and the arguments are handed on
    # as raw text, which is what a client assembling a call expects to be given.
    named = re.search(r'"(?:name|tool_name)"\s*:\s*"([^"]*)"', text)
    tail = ""
    after = text.find('"arguments"')
    if after >= 0:
        colon = text.find(":", after)
        if colon >= 0:
            tail = text[colon + 1:].strip().rstrip(",}")
    return (named.group(1) if named else ""), tail, False


def _bare_call_at(body, pos, names):
    """Where a tool call starts in an answer that carries no markup, or None.

    Two announcements, because two dialects write a call bare. A JSON object opens with a key
    naming the tool, its function, or one of the tools that were offered; and a model trained to
    call functions the way the prompt showed it writes ``name(arguments)``, which only counts when
    the name is one that was offered — otherwise a sentence mentioning `read_file (the tool)` reads
    as a call. Anything else is a model answering in JSON, which is content rather than a call, and
    the difference matters because a call ends the turn.
    """
    keys = "|".join([re.escape(word) for word in ("name", "tool", "tool_call", "TOOL")]
                    + [re.escape(name) for name in names])
    # The quote closes the alternation, not one of its branches: `"name":` is the key, and a
    # pattern that reads `name"` as the branch matches nothing.
    opening = re.compile(r'"(?:' + keys + r')"\s*:\s*')
    object_at = None
    for match in re.finditer(r"\{", body[pos:]):
        head = body[pos + match.start() + 1:pos + match.start() + 80]
        if opening.match(head.lstrip()):
            object_at = pos + match.start()
            break
    spelled = "|".join(re.escape(name) for name in names)
    if not spelled:
        return object_at
    # Not preceded by a word character: `lookup` inside `grep_lookup(` is not a call's name.
    called = re.search(r"(?<![\w.])(" + spelled + r")\s*\(", body[pos:])
    if not called:
        return object_at
    at = pos + called.start()
    # Several calls travel inside one list envelope, and the bracket belongs to the call rather
    # than to the prose before it — including it here is what keeps it off the screen.
    back = at - 1
    while back >= pos and body[back] in " \t\n":
        back -= 1
    if back >= pos and body[back] == "[":
        at = back
    return at if object_at is None else min(at, object_at)


class AnswerParser:
    """Splits a raw answer into what the user reads, what the model thought, and what it asked for.

    Every piece is parsed against the whole answer so far rather than remembered from the last
    one, because a model that changes its mind mid-marker - the tag that turns out to be prose
    after all - resolves on the next token instead of poisoning a state machine. An answer with
    no markup in it at all is content, which is the common case and not a failure.
    """

    def __init__(self, thought=None, call=None, stop=(), names=()):
        self.thought = thought
        self.call = call
        self.stop = [s for s in stop if s]
        self.names = [n for n in names if n]
        # A close tag with no open one before it is the model ending a turn it never started -
        # the residue of a transcript it was trained on. It ends the answer the same way, and
        # what follows it is not shown, because a stray marker is markup rather than prose.
        self.pairs = [pair for pair in ([thought] if thought else [])
                      + ([call] if call and call[0] else []) if pair and pair[1]]
        self.raw = ""
        self.content = ""
        self.reasoning = ""
        self.calls = []
        self.tags = [t for t in ([thought[0]] if thought else [])
                     + ([call[0]] if call and call[0] else [])
                     # A call written as name(arguments) announces itself with the name, and a
                     # set of them opens with a bracket: either one at the end of a chunk waits
                     # for the next token instead of flashing on screen as prose.
                     + (self.names + ["[", "{"] + ["[" + n for n in self.names]
                        if call and not call[0] else [])
                     + [close for _, close in self.pairs] + self.stop if t]

    def feed(self, piece, final=False):
        """The OpenAI deltas everything since the last piece added.

        ``final`` is what lets the end of a stream tell the truth: markup left open when a
        model ran out of budget is no longer a marker waiting to close but the text it wrote,
        and a user shown nothing in its place gets an empty answer with no explanation.
        """
        self.raw += piece
        deltas = []
        index = 0
        for kind, text, closed in self._segments(final):
            if kind == "call":
                delta = self._call_delta(index, text, closed)
                index += 1
            else:
                delta = self._text_delta(kind, text, final)
            if delta:
                deltas.append(delta)
        return deltas

    def finish_reason(self, raw):
        """Why the turn ended, with the one thing a decoder cannot know added.

        A model that stopped after writing a call asked for work, and that is the distinction
        the caller acts on: no end-of-sequence token carries it. An answer that ran out of
        budget half-way through a call does not have a call to hand back — its arguments are
        truncated, and running a tool on them is worse than the caller hearing that the answer
        was cut short.
        """
        if raw == "abort":
            return "abort"
        if self.calls and all(c["done"] for c in self.calls):
            return "tool_calls"
        return raw or "stop"

    def message(self):
        """The whole answer, for a client that asked for it in one piece."""
        return {
            "content": self.content,
            "reasoning": self.reasoning,
            "tool_calls": [{"id": c["id"], "name": c["name"], "arguments": c["arguments"]}
                           for c in self.calls],
        }

    def _segments(self, final):
        body = self.raw
        for marker in self.stop:
            at = body.find(marker)
            if at >= 0:
                body = body[:at]
                break
        for open_tag, close_tag in self.pairs:
            at = body.find(close_tag)
            if at >= 0 and body.find(open_tag) < 0:
                body = body[:at]
                break
        found = []
        pos = 0
        while pos < len(body):
            region = self._next(body, pos)
            if region is None:
                break
            before, head, tail, kind, closed, resume = region
            if before > pos:
                found.append(["content", body[pos:before], False])
            if kind == "call":
                # A list envelope holds one call per entry, and each of them is its own call
                # for the client to assemble.
                for entry, entry_closed in _call_entries(body[head:tail], closed, self.names):
                    found.append(["call", entry, entry_closed])
            else:
                found.append([kind, body[head:tail], closed])
            pos = max(resume, head)
        if pos < len(body):
            found.append(["content", body[pos:], False])
        if not final and found and found[-1][0] == "content":
            # The very end of the answer could still grow into a marker, so it waits for the
            # next piece instead of flashing on screen as prose the model never wrote.
            text = found[-1][1]
            found[-1][1] = text[:len(text) - self._held(text)]
        return [(kind, text, closed) for kind, text, closed in found]

    def _next(self, body, pos):
        """The next piece of markup after [pos], as (where prose ends, contents, how far to read).

        The prose boundary is not the markups own start: a tagged call begins with its open
        tag, and a reader shown that tag sees machinery instead of an answer. The last value is
        where the scan resumes, which is past the close tag for the same reason.
        """
        candidates = []
        if self.thought:
            at = body.find(self.thought[0], pos)
            if at >= 0:
                candidates.append((at, "reasoning"))
        if self.call:
            if self.call[0]:
                at = body.find(self.call[0], pos)
                # find reports a miss as -1, and -1 is a position the parser would happily
                # walk back to and read the same call for ever.
                if at >= 0:
                    candidates.append((at, "call"))
            else:
                at = _bare_call_at(body, pos, self.names)
                if at is not None:
                    candidates.append((at, "call"))
        if not candidates:
            return None
        start, kind = min(candidates)
        open_tag, close_tag = self.thought if kind == "reasoning" else self.call
        if not open_tag:
            text, closed = _call_span(body, start)
            end = start + len(text)
            return start, start, end, "call", closed, end
        head = start + len(open_tag)
        if not close_tag:
            return start, head, len(body), kind, False, len(body)
        tail = body.find(close_tag, head)
        if tail < 0:
            return start, head, len(body), kind, False, len(body)
        return start, head, tail, kind, True, tail + len(close_tag)

    def _held(self, text):
        """How much of this text's end could still grow into the start of a marker.

        The longest candidate wins, not the first: `read_file` at the end of a chunk is a whole
        name and the head of a longer one at the same time, and holding back only nine
        characters would put a tool call on the screen as a word.
        """
        held = 0
        for tag in self.tags:
            for size in range(min(len(tag), len(text)), held, -1):
                if text.endswith(tag[:size]):
                    held = size
                    break
        return held

    def _text_delta(self, kind, text, final):
        already = self.content if kind == "content" else self.reasoning
        if text.startswith(already):
            added = text[len(already):]
        elif final:
            added = text
        else:
            return None
        if kind == "content":
            self.content = text
        else:
            self.reasoning = text
        return {("content" if kind == "content" else "reasoning_content"): added} if added else None

    def _call_delta(self, index, text, closed):
        """What this feed added to the call at [index] in the answer.

        The index is the call's identity across feeds: the parser reads the whole answer every
        time, so a call that already closed must not be read again as a second one arriving.
        """
        name, arguments, known = _read_call(text)
        while len(self.calls) <= index:
            self.calls.append({"id": "call_%d" % (len(self.calls) + 1), "name": "",
                               "arguments": "", "announced": False, "done": False})
        call = self.calls[index]
        if known or closed:
            # Only a call that actually closed is one the caller can run. A final flush still
            # hands its arguments out, but the turn's reason says the answer was cut short.
            call["done"] = True
        if name and not call["name"]:
            call["name"] = name
        opening = not call["announced"] and bool(call["name"])
        if opening:
            call["announced"] = True
        added = ""
        if arguments.startswith(call["arguments"]):
            added = arguments[len(call["arguments"]):]
            call["arguments"] = arguments
        function = {}
        if opening:
            function["name"] = call["name"]
        if added:
            function["arguments"] = added
        if not function:
            return None
        item = {"index": index, "function": function}
        if opening:
            item["id"] = call["id"]
            item["type"] = "function"
        return {"tool_calls": [item]}


class Engine:
    """The one resident model.

    ``busy`` is held for the whole of a decode: the KV cache belongs to the sequence currently
    running, so a second completion is not merely slow, it corrupts the first. ``abort`` is how
    a request the app has lost interest in actually stops — a CPU decode on a phone can run for
    a minute, and a stop button that waits for it is a stop button that does nothing.
    """

    def __init__(self):
        self.busy = threading.Lock()
        self.abort = threading.Event()
        self.model = None
        self.model_path = None
        self.settings = {}
        self.template = None
        self.process = psutil.Process()

    @property
    def loaded(self):
        return self.model is not None

    def load(self, spec):
        path = spec.get("model_path") or ""
        if not os.path.isfile(path):
            raise ValueError("There is no model file at " + path)
        settings = {
            "model_path": path,
            "n_ctx": int(spec.get("n_ctx") or DEFAULT_CONTEXT),
            "n_batch": int(spec.get("n_batch") or DEFAULT_BATCH),
            "verbose": False,
        }
        threads = int(spec.get("n_threads") or 0)
        if threads > 0:
            settings["n_threads"] = threads
            settings["n_threads_batch"] = threads
        seed = spec.get("seed")
        if seed is not None:
            settings["seed"] = int(seed)
        # Whatever was resident before is replaced, and its memory has to be given back before
        # the new file is opened — a phone cannot hold both.
        self.unload()
        with self.busy:
            self.model = Llama(**settings)
            self.model_path = path
            self.settings = settings
            # The template is opened here, with the model, because it is the model's own: a
            # file whose template cannot render the tools the app would offer has to be heard
            # about now rather than in the middle of a conversation.
            self.template = _open_chat(self.model)
        return self.describe()

    def unload(self):
        with self.busy:
            self.model = None
            self.model_path = None
            self.settings = {}
            self.template = None

    def describe(self):
        """What is resident, in the shape the app reads at load and polls in /health.

        The chat block is what the file's own template turned out to be able to do, so the
        app offers tools and thinking only where the model can actually carry them.
        """
        if self.model is None:
            return {"loaded": False}
        metadata = getattr(self.model, "metadata", {}) or {}
        return {
            "loaded": True,
            "path": self.model_path,
            "n_ctx": int(self.model.n_ctx()),
            "n_vocab": int(self.model.n_vocab()),
            "architecture": metadata.get("general.architecture", ""),
            "name": metadata.get("general.name", ""),
            "threads": self.settings.get("n_threads"),
            "chat": dict(self.template["caps"], usesOwnTemplate=self.template["usesOwnTemplate"],
                         probe=self.template["probe"]),
        }

    def render(self, body):
        """The prompt this model's own template makes of a request, and how its turn ends.

        Tool arguments are handed to the template as the object they really are rather than
        the string the wire format carries them in, because that is what HuggingFace's
        templates iterate over; a call re-encoded here would print as an escaped blob.
        """
        if self.template is None:
            raise NoModel("No model is loaded")
        messages = [_template_message(m) for m in (body.get("messages") or []) if isinstance(m, dict)]
        if not messages:
            raise ValueError("messages must be a non-empty array")
        tools = [t for t in (body.get("tools") or []) if isinstance(t, dict) and t.get("function")]
        rendered = self.template["formatter"](
            messages=messages,
            tools=tools,
            tool_choice=body.get("tool_choice") or "auto",
            enable_thinking=bool(body.get("enable_thinking", True)),
            parallel_tool_calls=bool(body.get("parallel_tool_calls", False)),
            **_template_helpers()
        )
        stops = [s for s in (body.get("stop") or []) if isinstance(s, str) and s]
        for marker in rendered.stop or []:
            if marker and marker not in stops:
                stops.append(marker)
        return rendered.prompt, stops, rendered.stopping_criteria

    def decode(self, prompt, body, stops, stopping_criteria):
        """Yields one raw piece of the answer at a time, then its end.

        The prompt goes in as tokens with the beginning-of-text decision already made, which
        is how llama-cpp-python's own chat path feeds a rendered template: a template that
        opens its turn with a marker must not have that marker tokenised a second time.
        """
        arguments = {
            "max_tokens": int(body.get("max_tokens") or DEFAULT_MAX_TOKENS),
            "temperature": float(body.get("temperature", 0.8)),
            "top_p": float(body.get("top_p", 0.95)),
            "min_p": float(body.get("min_p", 0.0)),
            "repeat_penalty": float(body.get("repeat_penalty", 1.0)),
            "top_k": int(body.get("top_k", 40)),
            "stop": stops,
            "stopping_criteria": stopping_criteria,
            "stream": True,
        }
        if body.get("seed") is not None:
            arguments["seed"] = int(body["seed"])
        grammar = body.get("grammar")
        if grammar:
            arguments["grammar"] = LlamaGrammar.from_string(grammar, verbose=False)
        tokens = self.model.tokenize(prompt.encode("utf-8"), add_bos=False, special=True)
        generator = self.model.create_completion(tokens, **arguments)
        finish = "length"
        try:
            for chunk in generator:
                if self.abort.is_set():
                    # Saying so is the whole point: a turn cut short looks identical to a
                    # finished one in the text alone, and the app would show a half answer
                    # as though the model had chosen to stop.
                    finish = "abort"
                    break
                choice = (chunk.get("choices") or [{}])[0]
                reason = choice.get("finish_reason")
                if reason == "stop":
                    finish = "stop"
                elif reason:
                    finish = "length"
                text = choice.get("text") or ""
                if text:
                    yield text, None
                if reason:
                    break
        except ValueError as error:
            if _is_too_long(error):
                raise PromptTooLong(str(error))
            raise
        finally:
            generator.close()
        yield "", finish

    def chat(self, body):
        """One whole turn: render, decode, and read the answer back as an OpenAI stream.

        The chunks are what a client appends: a role to open with, then content, thinking and
        tool-call arguments as they grow, and a finish reason that says whether the model
        stopped, ran out of budget, or asked for a tool.
        """
        prompt, stops, stopping_criteria = self.render(body)
        completion_id = "chatcmpl-" + uuid.uuid4().hex
        created = int(time.time())
        model_name = os.path.basename(self.model_path or "model")
        parser = AnswerParser(
            thought=self.template["thoughtStyle"],
            call=self.template["caps"]["toolStyle"],
            stop=stops,
            names=[(t.get("function") or {}).get("name") for t in (body.get("tools") or []) if isinstance(t, dict)],
        )
        with self.busy:
            self.abort.clear()
            yield _chunk(completion_id, created, model_name, {"role": "assistant"}, None)
            finish = "length"
            for text, reason in self.decode(prompt, body, stops, stopping_criteria):
                # Everything the model has written so far is re-read on each piece, so only the
                # answer's final shape is ever sent: a marker that turns out to be prose has
                # already been held back rather than shown as markup.
                for delta in parser.feed(text, final=reason is not None):
                    yield _chunk(completion_id, created, model_name, delta, None)
                if reason:
                    finish = reason
                    break
        # No token counts: a stream reports none to llama-cpp-python, and a number made up
        # here would be billed against by whoever reads it.
        yield _chunk(completion_id, created, model_name, {}, parser.finish_reason(finish))

    def stats(self):
        memory = self.process.memory_info()
        return {
            "rss_mb": round(memory.rss / (1024 * 1024), 1),
            "threads": self.process.num_threads(),
            "cpu_percent": round(self.process.cpu_percent(interval=None), 1),
        }



def _template_message(message):
    """One wire message, with tool arguments turned back into the objects templates expect.

    The OpenAI format carries a call's arguments as a string; HuggingFace's templates iterate
    over them as data. Handing a template the string would make it print an escaped blob of
    quotes, which is not the prompt the model was trained to answer.
    """
    out = dict(message)
    calls = out.get("tool_calls")
    if isinstance(calls, list):
        fixed = []
        for call in calls:
            if not isinstance(call, dict):
                continue
            call = dict(call)
            function = call.get("function")
            if isinstance(function, dict) and isinstance(function.get("arguments"), str):
                function = dict(function)
                try:
                    function["arguments"] = json.loads(function["arguments"])
                except ValueError:
                    pass  # Not JSON is still the text the model wrote; a template may print it.
                call["function"] = function
            fixed.append(call)
        out["tool_calls"] = fixed
    return out


def _chunk(completion_id, created, model, delta, finish_reason):
    """One streamed chunk: the same envelope every time, with one delta inside."""
    payload = {
        "id": completion_id,
        "object": "chat.completion.chunk",
        "created": created,
        "model": model,
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish_reason}],
    }
    return payload


class Handler(BaseHTTPRequestHandler):
    engine = None
    token = None

    # HTTP/1.1 keeps the app's connections open between turns; the streaming responses below
    # say goodbye explicitly, because a server-sent stream ends at the connection, not at a
    # content length it cannot know in advance.
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/health":
            self._json(200, {
                "ok": True,
                "protocol": PROTOCOL_VERSION,
                "pid": os.getpid(),
                "loaded": self.engine.loaded,
                "busy": self.engine.busy.locked(),
                "model": self.engine.describe(),
                "stats": self.engine.stats(),
            })
        elif path == "/v1/models":
            if not self._authorized():
                return
            data = []
            if self.engine.model_path:
                data.append({
                    "id": os.path.basename(self.engine.model_path),
                    "object": "model",
                    "created": int(time.time()),
                    "owned_by": "agentisco",
                })
            self._json(200, {"object": "list", "data": data})
        elif path == "/v1/stats":
            if not self._authorized():
                return
            self._json(200, self.engine.stats())
        else:
            self._json(404, _error("Not found", "invalid_request_error"))

    def do_POST(self):
        path = self.path.split("?")[0]
        if not self._authorized():
            return
        if path == "/v1/load":
            self._load()
        elif path == "/v1/chat/completions":
            self._chat()
        elif path == "/abort":
            # Answers first: the decode may still be walking out of the model, and a caller
            # waiting on that has already been told the request is over.
            self.engine.abort.set()
            self._json(200, {"ok": True})
        elif path == "/v1/unload":
            self.engine.unload()
            self._json(200, {"ok": True, "model": self.engine.describe()})
        else:
            self._json(404, _error("Not found", "invalid_request_error"))

    def _load(self):
        body = self._body()
        if body is None:
            return
        try:
            self._json(200, {"ok": True, "model": self.engine.load(body)})
        except Exception as error:  # noqa: BLE001 - the app shows this text to the user
            self._json(500, _error("Could not load the model: " + str(error), "model_load_failed"))

    def _chat(self):
        """One turn, streamed or whole. The decode itself is the engine's."""
        body = self._body()
        if body is None:
            return
        wants_stream = bool(body.get("stream"))
        stream = self.engine.chat(body)
        try:
            # The first chunk is pulled before any header is written, because that is the last
            # moment a request that cannot run -- too big, no model, no messages -- can still
            # answer with a status code the app can turn into advice.
            first = next(stream)
        except PromptTooLong as error:
            self._json(400, _error(str(error), "context_length_exceeded"))
            return
        except NoModel as error:
            self._json(400, _error(str(error), "no_model"))
            return
        except ValueError as error:
            self._json(400, _error(str(error), "invalid_request_error"))
            return
        except Exception as error:  # noqa: BLE001 - reported, never a stack trace on a phone
            self._json(500, _error(str(error), "server_error"))
            return

        if not wants_stream:
            chunks = [first]
            chunks.extend(stream)
            self._json(200, _completion(chunks))
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.close_connection = True
        self.end_headers()
        try:
            self._sse(first)
            for chunk in stream:
                self._sse(chunk)
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            # The app stopped reading: it cancelled, its client died, or the user pressed stop.
            self.engine.abort.set()
        except Exception as error:  # noqa: BLE001 - a stream cannot answer with a status any more
            # The headers already say 200, so the failure travels inside the stream and the
            # client turns it into the error a caller would have got from a plain response.
            with contextlib.suppress(OSError):
                self._sse(_error(str(error), "server_error"))
                self.wfile.write(b"data: [DONE]\n\n")
                self.wfile.flush()
        finally:
            stream.close()

    def _body(self):
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        raw = self.rfile.read(length) if length > 0 else b"{}"
        try:
            body = json.loads(raw.decode("utf-8") or "{}")
        except (UnicodeDecodeError, ValueError):
            self._json(400, _error("The request body is not JSON", "invalid_request_error"))
            return None
        if not isinstance(body, dict):
            self._json(400, _error("The request body is not an object", "invalid_request_error"))
            return None
        return body

    def _authorized(self):
        header = self.headers.get("Authorization") or ""
        expected = "Bearer " + (self.token or "")
        if hmac.compare_digest(header, expected):
            return True
        self._json(401, _error("Missing or wrong bearer token", "invalid_request_error"))
        return False

    def _json(self, code, payload):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _sse(self, payload):
        self.wfile.write(b"data: " + json.dumps(payload, ensure_ascii=False).encode("utf-8") + b"\n\n")
        self.wfile.flush()



def _completion(chunks):
    """The whole answer at once, folded from the same deltas a stream would have carried.

    A client that asked for one JSON body gets the content, the thinking and the calls split
    the way the OpenAI format splits them, because that is what the app's own tool loop reads.
    """
    content = []
    reasoning = []
    calls = []
    finish = "stop"
    head = chunks[0] if chunks else {}
    for chunk in chunks:
        choice = (chunk.get("choices") or [{}])[0]
        delta = choice.get("delta") or {}
        if delta.get("content"):
            content.append(delta["content"])
        if delta.get("reasoning_content"):
            reasoning.append(delta["reasoning_content"])
        for item in delta.get("tool_calls") or []:
            index = int(item.get("index") or 0)
            while len(calls) <= index:
                calls.append({"id": "", "type": "function", "function": {"name": "", "arguments": ""}})
            call = calls[index]
            if item.get("id"):
                call["id"] = item["id"]
            function = item.get("function") or {}
            if function.get("name"):
                call["function"]["name"] = function["name"]
            if function.get("arguments"):
                call["function"]["arguments"] += function["arguments"]
        if choice.get("finish_reason"):
            finish = choice["finish_reason"]
    message = {"role": "assistant", "content": "".join(content)}
    if "".join(reasoning):
        message["reasoning_content"] = "".join(reasoning)
    if calls:
        message["tool_calls"] = calls
    return {
        "id": head.get("id", "chatcmpl-unknown"),
        "object": "chat.completion",
        "created": head.get("created", 0),
        "model": head.get("model", ""),
        "choices": [{"index": 0, "message": message, "finish_reason": finish}],
    }

def _error(message, error_type):
    return {"error": {"message": message, "type": error_type, "code": None, "param": None}}


def watch_stdin():
    """Exit when the app closes the pipe it launched this process through.

    ``proot --kill-on-exit`` covers proot exiting on its own; it cannot help when the app is
    killed outright, and a Python process still holding a model's weights after its client is
    gone is the worst possible outcome on a phone. A parent that dies closes the pipe, and the
    pipe closing is the one signal that reliably reaches here.
    """
    try:
        is_pipe = stat.S_ISFIFO(os.fstat(0).st_mode)
    except OSError:
        return
    if not is_pipe:
        return

    def wait():
        while sys.stdin.readline():
            pass
        os._exit(0)

    threading.Thread(target=wait, daemon=True).start()


def main(argv=None):
    parser = argparse.ArgumentParser(description="Agentisco OpenAI-compatible model server")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--host", default="127.0.0.1")
    args = parser.parse_args(argv)

    engine = Engine()
    Handler.engine = engine
    Handler.token = args.token

    watch_stdin()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(json.dumps({"event": "ready", "port": args.port, "protocol": PROTOCOL_VERSION}), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        engine.unload()


if __name__ == "__main__":
    sys.exit(main())
