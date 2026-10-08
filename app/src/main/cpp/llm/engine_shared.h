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
#include <optional>
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

/**
 * The exception this thread is failing with, named and then taken away.
 *
 * JNI's failure model is the trap: a call that fails does not throw into C++, it leaves an
 * exception *pending on the thread* and returns null. Nothing in C++ can read that, and the next
 * JNI call on the thread is undefined behaviour - which ART turns into an abort of the whole
 * process. That is the app vanishing mid-turn with no message for the person holding the phone,
 * so every place this library crosses to Kotlin asks first whether the thread is already failing
 * and takes the failure away instead of building on it.
 *
 * The Throwable's own name and message are read here rather than guessed at, because the two
 * ways this happens need different words: an allocation the Java heap refused is a phone out of
 * memory, while a token sink that threw is this app's own bug. Every call in that lookup can
 * itself fail, so each one leaves the thread clean and the fallback string stands.
 */
inline std::string describe_exception(JNIEnv *env) {
  // The object is taken before the trace is printed, because the two runtimes disagree about the
  // printing: ExceptionDescribe is specified to leave the exception pending and HotSpot clears it,
  // so an ExceptionOccurred afterwards reports nothing at all. The local reference holds either
  // way, which is what makes this order the one that always has a name to show.
  jobject thrown = env->ExceptionOccurred();
  env->ExceptionDescribe();  // the full trace, once, for whoever can read logcat
  env->ExceptionClear();     // ... wherever the runtime still kept it
  if (thrown == nullptr) return "a Java failure";

  std::string text;
  jclass clazz = env->GetObjectClass(thrown);
  if (clazz != nullptr && !env->ExceptionCheck()) {
    // Throwable::toString is the class name and the message in one call, which is exactly the
    // half-sentence a user needs: `java.lang.OutOfMemoryError: Failed to allocate ...`. It answers
    // for any exception this library has never heard of, because the lookup is on the throwable's
    // own class and every throwable inherits it.
    const jmethodID to_string = env->GetMethodID(clazz, "toString", "()Ljava/lang/String;");
    if (to_string != nullptr && !env->ExceptionCheck()) {
      auto value = (jstring) env->CallObjectMethod(thrown, to_string);
      if (value != nullptr && !env->ExceptionCheck()) {
        const char *chars = env->GetStringUTFChars(value, nullptr);
        if (chars != nullptr) {
          text = chars;
          env->ReleaseStringUTFChars(value, chars);
        }
        env->DeleteLocalRef(value);
      }
    }
    env->DeleteLocalRef(clazz);
  }
  env->DeleteLocalRef(thrown);
  // Whatever the reading of the message itself left behind - a toString that threw is rare, but a
  // thread this far out of memory is not choosing its own failures.
  if (env->ExceptionCheck()) env->ExceptionClear();

  return text.empty() ? "a Java failure" : text;
}

/**
 * Records that the thread was already failing, in terms the turn's caller can show.
 *
 * True means the caller must not touch Java again and must not pretend the step succeeded; the
 * engine's own error now says which handover broke and what Java made of it.
 */
inline bool take_pending_exception(JNIEnv *env, const char *where) {
  if (!env->ExceptionCheck()) return false;
  set_error("The model's turn could not be delivered (" + std::string(where) + "): " + describe_exception(env));
  return true;
}

/**
 * The array's bytes, or no value at all when the thread was already failing.
 *
 * The failure has to be distinguishable from an empty array: a prompt that read as empty would
 * otherwise be tokenized and decoded as a request that said nothing, and an empty input to the
 * parser would be reported as a model that answered with nothing.
 */
inline std::optional<std::vector<char>> copy_bytes(JNIEnv *env, jbyteArray array) {
  if (take_pending_exception(env, "reading text the app handed in")) return std::nullopt;
  std::vector<char> out;
  if (array == nullptr) return out;
  const jsize len = env->GetArrayLength(array);
  out.resize(static_cast<size_t>(len > 0 ? len : 0));
  if (!out.empty()) env->GetByteArrayRegion(array, 0, len, reinterpret_cast<jbyte *>(out.data()));
  if (env->ExceptionCheck()) {
    take_pending_exception(env, "reading text the app handed in");
    return std::nullopt;
  }
  return out;
}

/**
 * The text as a Java byte array, or null when the Java heap refused it.
 *
 * Every caller must expect the null: handing it to another JNI call is the undefined behaviour
 * that aborts the process, which is the failure this whole boundary exists to prevent.
 */
inline jbyteArray to_bytes(JNIEnv *env, const std::string &text, const char *where = "a report to the app") {
  if (take_pending_exception(env, where)) return nullptr;
  jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
  if (out == nullptr) {
    // A refused allocation leaves its OutOfMemoryError pending; describing it and clearing it is
    // the only way the turn ends with a message instead of the thread carrying it into the next
    // call, where ART would kill the process for it.
    if (!take_pending_exception(env, where)) {
      set_error("The model's turn could not be delivered (" + std::string(where) +
                "): the Java heap refused the bytes");
    }
    return nullptr;
  }
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
