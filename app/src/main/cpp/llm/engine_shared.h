// Shared internals of the Awaki inference library.
//
// The JNI is split across translation units — llama_jni.cpp owns loading and
// decoding, chat_jni.cpp owns the chat-template layer — so the session they both
// reach into, and the bytes that cross to Kotlin, live here. Nothing in this header
// is public API: it exists so the two halves of one shared library agree on what an
// engine handle is.
//
// Text crosses as UTF-8 byte arrays in both directions. JNI's own string functions
// use *modified* UTF-8, which encodes an astral character as two surrogate
// three-byte sequences — bytes a tokenizer would read as a valid-but-wrong string.
// Encoding in Kotlin avoids the mismatch entirely.

#pragma once

#include <jni.h>

#include <llama.h>

#include <ggml-backend.h>
#include <ggml.h>

#include <chat.h>

#include <atomic>
#include <string>
#include <vector>

struct Session {
  llama_model *model = nullptr;
  llama_context *ctx = nullptr;
  const llama_vocab *vocab = nullptr;
  int32_t n_batch = 512;
  std::atomic<bool> abort{false};

  /**
   * The worker threads this context decodes with, kept for the context's whole life.
   *
   * Without them ggml builds a pool for every graph evaluation and tears it down again, so each
   * token pays for `n_threads` thread creations and joins - and the new threads are placed by the
   * scheduler, which is free to put them on the little cores the thread count exists to avoid.
   * Null when this build's CPU backend does not hand its pool constructor out.
   */
  ggml_threadpool_t threadpool = nullptr;
  ggml_threadpool_t threadpool_batch = nullptr;

  /** Cores this device actually runs on and the plan made from them, for the log and for Settings. */
  int32_t cores_seen = 0;
  int32_t n_threads = 0;
  int32_t n_threads_batch = 0;

  /**
   * The prefix of the last prompt whose engine state is held in [snapshot].
   *
   * An agent turn re-sends the same playbook and the same tool specs ahead of the user's new
   * message, and on a phone prefilling those tokens is most of the wait the user sits through.
   * So the state left by the last request is copied out at the point where the two prompts
   * still agree, and the next one loads that copy and evaluates only its own tail.
   *
   * A copy rather than a trim of the cache, because trimming is not available to every model:
   * a hybrid one like LFM2.5 keeps recurrent state as well as keys and values, and its recurrent
   * part can only be rewound a handful of tokens. The copy costs a memcpy and works on all of
   * them, including the model this app ships.
   */
  std::vector<llama_token> prefix_tokens;
  std::vector<uint8_t> snapshot;

  /** The chat template this model carries, plus its variants, parsed once at load. */
  common_chat_templates_ptr templates;

  /**
   * Special tokens whose text belongs to the answer, because this model's own template writes them.
   *
   * A tool call is one special token in this template and literal text in another, and a special
   * token detokenizes to nothing unless it is asked for by name. So the turn's preserved list
   * decides which markers reach the parsers: without it, a model that calls in markers has the
   * markers deleted before anything that could read them sees them.
   */
  std::vector<llama_token> preserved_tokens;

  /**
   * Whether the current turn's grammar waits for a trigger before it constrains anything, and what
   * the triggers are. Both come from the template alongside the grammar text, which is why they
   * live here rather than crossing as extra arguments: the grammar arrives as a string, and a
   * string carries no opinion about when it should start applying.
   */
  bool grammar_lazy = false;
  std::vector<common_grammar_trigger> grammar_triggers;
};

/** Message of the last failure any part of this library hit. */
extern std::string g_last_error;

void set_error(const std::string &message);

/** Session pointer held in a jlong, or null for a closed or never-opened handle. */
inline Session *session_of(jlong handle) {
  return reinterpret_cast<Session *>(static_cast<intptr_t>(handle));
}

/**
 * Starts the engine's own crash record, appended to [log_path] next to the JVM's.
 *
 * A fault in native code never reaches Thread.setDefaultUncaughtExceptionHandler, so without this
 * the app vanishes mid-turn and the Crash Log screen has nothing to show.
 */
void install_crash_capture(const char *log_path);

/**
 * Records the step the engine is about to take, for the crash record.
 *
 * Pass null to leave one of the two unchanged. The strings are copied into fixed buffers, so a
 * caller that is about to fault has already said what it was doing.
 */
void set_engine_phase(const char *phase, const char *model);

inline std::vector<char> copy_bytes(JNIEnv *env, jbyteArray array) {
  std::vector<char> out;
  if (array == nullptr) return out;
  const jsize len = env->GetArrayLength(array);
  out.resize(static_cast<size_t>(len > 0 ? len : 0));
  if (!out.empty()) env->GetByteArrayRegion(array, 0, len, reinterpret_cast<jbyte *>(out.data()));
  return out;
}

inline jbyteArray to_bytes(JNIEnv *env, const std::string &text) {
  jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
  if (!text.empty()) {
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
  }
  return out;
}

/**
 * Quotes text into JSON. Model metadata and a model's own generated text are
 * attacker-influenced for a custom URL, so they are escaped rather than trusted to
 * be well-formed. UTF-8 bytes pass through: they are already valid JSON content.
 */
inline std::string json_escape(const std::string &raw) {
  std::string out;
  out.reserve(raw.size() + 8);
  for (const unsigned char c : raw) {
    switch (c) {
      case '"': out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default:
        if (c < 0x20) {
          static const char *hex = "0123456789abcdef";
          out += "\\u00";
          out += static_cast<char>(hex[(c >> 4) & 0xF]);
          out += static_cast<char>(hex[c & 0xF]);
        } else {
          out += static_cast<char>(c);
        }
    }
  }
  return out;
}
