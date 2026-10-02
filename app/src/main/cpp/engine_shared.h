// Shared internals of the Agentisco inference library.
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

#include <chat.h>

#include <atomic>
#include <string>
#include <vector>

struct Session {
  llama_model *model = nullptr;
  llama_context *ctx = nullptr;
  const llama_vocab *vocab = nullptr;
  int32_t n_batch = 128;
  std::atomic<bool> abort{false};

  /**
   * The prompt the last request left in the KV cache, and whether that cache may be
   * trimmed and reused. An agent turn re-sends the same playbook and the same tool
   * specs ahead of the user's new message, and on a phone prefilling those tokens is
   * most of the wait — so the next request keeps whatever head still matches.
   */
  std::vector<llama_token> cached_prompt;
  bool cache_reusable = false;

  /** The chat template this model carries, plus its variants, parsed once at load. */
  common_chat_templates_ptr templates;
};

/** Message of the last failure any part of this library hit. */
extern std::string g_last_error;

void set_error(const std::string &message);

/** Session pointer held in a jlong, or null for a closed or never-opened handle. */
inline Session *session_of(jlong handle) {
  return reinterpret_cast<Session *>(static_cast<intptr_t>(handle));
}

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
