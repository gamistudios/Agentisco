#!/usr/bin/env python3
"""The model server Agentisco runs inside its Linux environment.

Only the standard library plus llama-cpp-python and psutil are used: this file is copied into
the model directory by the app and run by the virtualenv the app built there, so every import
below is a package the setup step already installed.

The API is OpenAI's, because that is the shape the rest of Agentisco speaks. What this server
deliberately does *not* do is render a chat template or read tool calls back out of an answer.
A GGUF ships its own Jinja template, in whatever dialect its author chose, and llama.cpp ships
the only renderer that understands it — which the Android side already runs through its native
binding. So the app sends a finished prompt and receives finished text, and the tensors, the
part that costs the phone its battery, are this program's only job.

Nothing listens beyond 127.0.0.1 and every request needs the bearer token the app picked when
it launched this process, so a model cannot be driven by whatever else is on the network.
"""

import argparse
import contextlib
import hmac
import json
import os
import stat
import sys
import threading
import time
import uuid

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import psutil
from llama_cpp import Llama, LlamaGrammar

# Bumped when the routes or their payloads change shape, so the app can tell an old script
# left on disk from the one it just copied out of its own package.
PROTOCOL_VERSION = 1

DEFAULT_CONTEXT = 4096
DEFAULT_BATCH = 8
DEFAULT_MAX_TOKENS = 512


class PromptTooLong(Exception):
    """The prompt needs more context than the resident model has. Nothing failed here."""


class NoModel(Exception):
    """Asked to generate before anything was loaded."""


def _is_too_long(error):
    return "exceed context window" in str(error).lower()


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
        return self.describe()

    def unload(self):
        with self.busy:
            self.model = None
            self.model_path = None
            self.settings = {}

    def describe(self):
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
        }

    def stream(self, body):
        """Yields one OpenAI chunk per decoded piece, then a final chunk with the reason.

        The first piece is pulled before any HTTP header is written, which is what lets a
        prompt that is too large for the context answer 400 instead of a 200 whose stream
        contains an error nobody can turn into a status code.
        """
        prompt = body.get("prompt")
        if not isinstance(prompt, str) or not prompt:
            raise ValueError("prompt must be a non-empty string")
        if self.model is None:
            raise NoModel("No model is loaded")

        arguments = {
            "prompt": prompt,
            "stream": True,
            "max_tokens": int(body.get("max_tokens") or DEFAULT_MAX_TOKENS),
            "temperature": float(body.get("temperature", 0.8)),
            "top_p": float(body.get("top_p", 0.95)),
            "min_p": float(body.get("min_p", 0.05)),
            "repeat_penalty": float(body.get("repeat_penalty", 1.0)),
            "stop": [s for s in (body.get("stop") or []) if isinstance(s, str) and s],
        }
        if body.get("top_k") is not None:
            arguments["top_k"] = int(body["top_k"])
        if body.get("seed") is not None:
            arguments["seed"] = int(body["seed"])
        grammar = body.get("grammar")
        if grammar:
            # GBNF written by llama.cpp's own template analysis, handed to llama.cpp's own
            # grammar compiler — no translation, and therefore no second implementation.
            arguments["grammar"] = LlamaGrammar.from_string(grammar, verbose=False)

        completion_id = "cmpl-" + uuid.uuid4().hex
        created = int(time.time())
        model_name = os.path.basename(self.model_path or "model")

        with self.busy:
            self.abort.clear()
            pieces = 0
            finish = "length"
            generator = None
            try:
                # A prompt that does not fit is reported here, and it reaches us either as the
                # call is made or as the first token is pulled depending on the version, so
                # both sit inside the same guard.
                generator = self.model.create_completion(**arguments)
                for chunk in generator:
                    if self.abort.is_set():
                        finish = "abort"
                        break
                    choice = chunk.get("choices") or [{}]
                    text = choice[0].get("text") or ""
                    reason = choice[0].get("finish_reason")
                    if reason == "stop":
                        finish = "stop"
                    elif reason:
                        finish = "length"
                    if text:
                        pieces += 1
                        yield {
                            "id": completion_id,
                            "object": "text_completion",
                            "created": created,
                            "model": model_name,
                            "choices": [{"index": 0, "text": text, "logprobs": None, "finish_reason": None}],
                        }
                    if reason:
                        break
            except ValueError as error:
                if _is_too_long(error):
                    raise PromptTooLong(str(error))
                raise
            finally:
                if generator is not None:
                    generator.close()
            yield {
                "id": completion_id,
                "object": "text_completion",
                "created": created,
                "model": model_name,
                "choices": [{"index": 0, "text": "", "logprobs": None, "finish_reason": finish}],
                "usage": {"prompt_tokens": 0, "completion_tokens": pieces, "total_tokens": pieces},
            }

    def stats(self):
        memory = self.process.memory_info()
        return {
            "rss_mb": round(memory.rss / (1024 * 1024), 1),
            "threads": self.process.num_threads(),
            "cpu_percent": round(self.process.cpu_percent(interval=None), 1),
        }


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
        elif path == "/v1/completions":
            self._completions()
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

    def _completions(self):
        body = self._body()
        if body is None:
            return
        wants_stream = bool(body.get("stream"))
        stream = self.engine.stream(body)
        try:
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
            text = [first["choices"][0]["text"]]
            final = first
            for chunk in stream:
                piece = chunk["choices"][0]["text"]
                if piece:
                    text.append(piece)
                final = chunk
            self._json(200, {
                "id": final["id"],
                "object": "text_completion",
                "created": final["created"],
                "model": final["model"],
                "choices": [{"index": 0, "text": "".join(text), "logprobs": None,
                             "finish_reason": final["choices"][0]["finish_reason"]}],
                "usage": final.get("usage"),
            })
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
