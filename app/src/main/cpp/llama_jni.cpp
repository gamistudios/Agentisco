// JNI bridge between Agentisco and the pinned llama.cpp engine.
//
// Deliberately thin: it owns model + context lifetime and the decode loop, and
// nothing else. Prompt rendering, tool-call grammar, stop-sequence handling and
// the OpenAI-compatible wire format all live in Kotlin, so the engine stays
// replaceable and the parts that carry product rules stay testable on the JVM.
//
// Finish codes returned by nativeComplete:
//   0 = end of sequence   1 = max tokens   2 = caller stopped   3 = aborted
//   4 = context full      negative = engine error (see nativeLastError)

#include <jni.h>
#include <android/log.h>

#include <llama.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <ctime>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "AgentiscoLlm"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
  llama_model *model = nullptr;
  llama_context *ctx = nullptr;
  const llama_vocab *vocab = nullptr;
  int32_t n_batch = 128;
  std::atomic<bool> abort{false};
};

std::string g_last_error;

void set_error(const std::string &msg) {
  g_last_error = msg;
  LOGE("%s", msg.c_str());
}

// A UTF-8 codepoint can straddle two token pieces. Emitting a half sequence
// would corrupt it, so only the complete prefix is handed to the caller and the
// trailing bytes wait for the next piece.
size_t complete_utf8_prefix_len(const std::string &s) {
  const size_t n = s.size();
  for (int back = 0; back < 4 && (size_t) back < n; ++back) {
    const unsigned char c = (unsigned char) s[n - 1 - back];
    if ((c & 0x80) == 0) return n - back;
    if ((c & 0xC0) == 0xC0) {
      const int need = (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : 4;
      const size_t have = (size_t) back + 1;
      return have >= (size_t) need ? n : n - (size_t) back - 1;
    }
  }
  return n;
}

std::string meta_string(llama_model *model, const char *key) {
  char buf[4096];
  const int32_t len = llama_model_meta_val_str(model, key, buf, sizeof(buf));
  if (len < 0) return std::string();
  return std::string(buf, (size_t) (len < (int32_t) sizeof(buf) ? len : (int32_t) sizeof(buf) - 1));
}

llama_sampler *build_sampler(const Session *s,
                             jfloat temperature,
                             jint top_k,
                             jfloat top_p,
                             jfloat repeat_penalty,
                             jlong seed,
                             const char *grammar) {
  auto chain_params = llama_sampler_chain_default_params();
  llama_sampler *chain = llama_sampler_chain_init(chain_params);

  // Same order llama.cpp's own sampler builder uses: constrain, then discourage
  // repetition, then truncate the distribution, then sample from it.
  if (grammar != nullptr && grammar[0] != '\0') {
    llama_sampler_chain_add(chain, llama_sampler_init_grammar(s->vocab, grammar, "root"));
  }
  if (repeat_penalty > 0.0f && repeat_penalty != 1.0f) {
    llama_sampler_chain_add(
        chain, llama_sampler_init_penalties(llama_vocab_n_tokens(s->vocab), 64, repeat_penalty, 0.0f, 0.0f));
  }
  if (top_k > 0) {
    llama_sampler_chain_add(chain, llama_sampler_init_top_k((int32_t) top_k));
  }
  if (top_p > 0.0f && top_p < 1.0f) {
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
  }
  llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature <= 0.0f ? 0.0f : temperature));
  llama_sampler_chain_add(chain, llama_sampler_init_dist(seed <= 0 ? (uint32_t) time(nullptr) : (uint32_t) seed));
  return chain;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL Java_com_agentisco_local_runtime_LlamaNative_nativeBackendInit(JNIEnv *, jobject) {
  llama_log_set(
      [](ggml_log_level level, const char *text, void *) {
        if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_CONT) {
          set_error(text ? text : "engine error");
        } else {
          LOGI("%s", text ? text : "");
        }
      },
      nullptr);
  llama_backend_init();
}

JNIEXPORT jlong JNICALL
Java_com_agentisco_local_runtime_LlamaNative_nativeLoadModel(JNIEnv *env,
                                                             jobject,
                                                             jstring path,
                                                             jint ctx_size,
                                                             jint threads,
                                                             jint n_batch) {
  const char *cpath = env->GetStringUTFChars(path, nullptr);

  auto mparams = llama_model_default_params();
  mparams.n_gpu_layers = 0;  // CPU only: no Vulkan/OpenCL backend is compiled in.
  mparams.check_tensors = true;

  llama_model *model = llama_model_load_from_file(cpath, mparams);
  env->ReleaseStringUTFChars(path, cpath);
  if (model == nullptr) {
    set_error("Failed to load GGUF model");
    return 0;
  }

  auto cparams = llama_context_default_params();
  cparams.n_ctx = (uint32_t) (ctx_size > 0 ? ctx_size : 0);
  cparams.n_batch = (uint32_t) (n_batch > 0 ? n_batch : 128);
  cparams.n_ubatch = cparams.n_batch;
  cparams.n_threads = (int32_t) (threads > 0 ? threads : 0);
  cparams.n_threads_batch = cparams.n_threads;

  llama_context *ctx = llama_init_from_model(model, cparams);
  if (ctx == nullptr) {
    llama_model_free(model);
    set_error("Failed to create context — not enough memory for this context size");
    return 0;
  }

  auto *s = new Session();
  s->model = model;
  s->ctx = ctx;
  s->vocab = llama_model_get_vocab(model);
  s->n_batch = (int32_t) cparams.n_batch;
  g_last_error.clear();
  return (jlong) (intptr_t) s;
}

JNIEXPORT jstring JNICALL
Java_com_agentisco_local_runtime_LlamaNative_nativeModelInfo(JNIEnv *env, jobject, jlong handle) {
  auto *s = (Session *) (intptr_t) handle;
  if (s == nullptr) return env->NewStringUTF("{}");

  char arch[256] = {0};
  llama_model_meta_val_str(s->model, "general.architecture", arch, sizeof(arch));
  const std::string tmpl = meta_string(s->model, "tokenizer.chat_template");
  const std::string name = meta_string(s->model, "general.name");

  // Kotlin needs to know the engine's real limits so Settings never offers a
  // parameter value the loaded model cannot honour.
  std::string json = "{\"architecture\":\"" + name + "\",";
  json += "\"arch\":\"";
  json += arch;
  json += "\",\"nVocab\":" + std::to_string(llama_vocab_n_tokens(s->vocab));
  json += ",\"nCtx\":" + std::to_string(llama_n_ctx(s->ctx));
  json += ",\"nCtxTrain\":" + std::to_string(llama_model_n_ctx_train(s->model));
  json += ",\"eos\":" + std::to_string(llama_vocab_eos(s->vocab));
  json += ",\"addBos\":" + std::string(llama_vocab_get_add_bos(s->vocab) ? "true" : "false");
  json += ",\"addEos\":" + std::string(llama_vocab_get_add_eos(s->vocab) ? "true" : "false");
  json += ",\"supportsGrammar\":true";

  std::string eos_str = meta_string(s->model, "tokenizer.ggml.eos_token");
  json += ",\"eosToken\":\"" + eos_str + "\"";
  json += ",\"chatTemplate\":\"";
  for (char c : tmpl) {
    switch (c) {
      case '"': json += "\\\""; break;
      case '\\': json += "\\\\"; break;
      case '\n': json += "\\n"; break;
      case '\r': json += "\\r"; break;
      case '\t': json += "\\t"; break;
      default:
        if ((unsigned char) c >= 0x20) json += c;
    }
  }
  json += "\"}";
  return env->NewStringUTF(json.c_str());
}

JNIEXPORT jint JNICALL
Java_com_agentisco_local_runtime_LlamaNative_nativeComplete(JNIEnv *env,
                                                            jobject,
                                                            jlong handle,
                                                            jstring prompt,
                                                            jfloat temperature,
                                                            jint top_k,
                                                            jfloat top_p,
                                                            jfloat repeat_penalty,
                                                            jint max_tokens,
                                                            jlong seed,
                                                            jstring grammar,
                                                            jobject callback) {
  auto *s = (Session *) (intptr_t) handle;
  if (s == nullptr) return -1;

  jclass cb_class = env->GetObjectClass(callback);
  jmethodID on_token = env->GetMethodID(cb_class, "onToken", "([BI)Z");
  if (on_token == nullptr) return -2;

  std::vector<char> prompt_bytes;
  {
    const char *chars = env->GetStringUTFChars(prompt, nullptr);
    const jsize len = env->GetStringUTFLength(prompt);
    prompt_bytes.assign(chars, chars + len);
    env->ReleaseStringUTFChars(prompt, chars);
  }

  const char *cgrammar = grammar == nullptr ? nullptr : env->GetStringUTFChars(grammar, nullptr);
  llama_sampler *smpl =
      build_sampler(s, temperature, top_k, top_p, repeat_penalty, seed, cgrammar ? cgrammar : "");
  if (cgrammar) env->ReleaseStringUTFChars(grammar, cgrammar);
  if (smpl == nullptr) {
    set_error("Failed to build sampler");
    return -3;
  }

  // Tokenize: a negative return is the number of tokens the buffer would need.
  std::vector<llama_token> tokens;
  {
    const char *text = prompt_bytes.data();
    const int32_t text_len = (int32_t) prompt_bytes.size();
    int32_t need = llama_tokenize(s->vocab, text, text_len, nullptr, 0, true, true);
    if (need < 0) need = -need;
    tokens.resize((size_t) need + 8);
    const int32_t got =
        llama_tokenize(s->vocab, text, text_len, tokens.data(), (int32_t) tokens.size(), true, true);
    if (got < 0) {
      llama_sampler_free(smpl);
      set_error("Failed to tokenize prompt");
      return -4;
    }
    tokens.resize((size_t) got);
  }

  const uint32_t n_ctx = llama_n_ctx(s->ctx);
  const llama_token eos = llama_vocab_eos(s->vocab);
  const int32_t limit = max_tokens > 0 ? max_tokens : 512;

  llama_batch batch = llama_batch_init(s->n_batch, 0, 1);
  std::string pending;
  int32_t n_past = 0;
  int32_t generated = 0;
  int finish = 1;

  auto emit = [&](const std::string &bytes) -> bool {
    if (bytes.empty()) return true;
    jbyteArray arr = env->NewByteArray((jsize) bytes.size());
    env->SetByteArrayRegion(arr, 0, (jsize) bytes.size(), (const jbyte *) bytes.data());
    const jboolean keep = env->CallBooleanMethod(callback, on_token, arr, (jint) bytes.size());
    env->DeleteLocalRef(arr);
    return env->ExceptionCheck() ? false : (keep == JNI_TRUE);
  };

  // Prefill: the whole prompt, in chunks the context batch can take.
  bool prefill_ok = !tokens.empty();
  for (size_t i = 0; i < tokens.size() && prefill_ok;) {
    const int32_t chunk =
        (int32_t) std::min<size_t>((size_t) s->n_batch, tokens.size() - i);
    batch.n_tokens = chunk;
    for (int32_t j = 0; j < chunk; ++j) {
      batch.token[j] = tokens[i + (size_t) j];
      batch.pos[j] = n_past + j;
      batch.n_seq_id[j] = 1;
      batch.seq_id[j][0] = 0;
      batch.logits[j] = (j == chunk - 1) ? 1 : 0;
    }
    if (llama_decode(s->ctx, batch) != 0) {
      prefill_ok = false;
      finish = -5;
      set_error("Failed to evaluate prompt");
      break;
    }
    n_past += chunk;
    i += (size_t) chunk;
  }

  while (prefill_ok && generated < limit) {
    if (s->abort.load()) {
      finish = 3;
      break;
    }
    if ((uint32_t) n_past >= n_ctx) {
      finish = 4;
      break;
    }

    const llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
    if (s->abort.load()) {
      finish = 3;
      break;
    }
    llama_sampler_accept(smpl, tok);
    generated++;

    if (eos != LLAMA_TOKEN_NULL && tok == eos) {
      finish = 0;
      break;
    }

    char piece[256];
    const int32_t written = llama_token_to_piece(s->vocab, tok, piece, sizeof(piece), 0, false);
    if (written < 0) {
      finish = -6;
      set_error("Failed to detokenize");
      break;
    }
    pending.append(piece, (size_t) std::max(0, written));

    const size_t safe = complete_utf8_prefix_len(pending);
    if (safe > 0) {
      const bool keep = emit(pending.substr(0, safe));
      pending.erase(0, safe);
      if (!keep) {
        finish = 2;
        break;
      }
    }

    batch.n_tokens = 1;
    batch.token[0] = tok;
    batch.pos[0] = n_past;
    batch.n_seq_id[0] = 1;
    batch.seq_id[0][0] = 0;
    batch.logits[0] = 1;
    if (llama_decode(s->ctx, batch) != 0) {
      finish = -7;
      set_error("Failed to evaluate generated token");
      break;
    }
    n_past++;
  }

  if (!pending.empty() && finish != 2) emit(pending);

  llama_batch_free(batch);
  llama_sampler_free(smpl);
  return finish;
}

JNIEXPORT void JNICALL
Java_com_agentisco_local_runtime_LlamaNative_nativeAbort(JNIEnv *, jobject, jlong handle) {
  auto *s = (Session *) (intptr_t) handle;
  if (s != nullptr) s->abort.store(true);
}

JNIEXPORT void JNICALL
Java_com_agentisco_local_runtime_LlamaNative_nativeResetAbort(JNIEnv *, jobject, jlong handle) {
  auto *s = (Session *) (intptr_t) handle;
  if (s != nullptr) s->abort.store(false);
}

JNIEXPORT void JNICALL
Java_com_agentisco_local_runtime_LlamaNative_nativeUnload(JNIEnv *, jobject, jlong handle) {
  auto *s = (Session *) (intptr_t) handle;
  if (s == nullptr) return;
  if (s->ctx) llama_free(s->ctx);
  if (s->model) llama_model_free(s->model);
  delete s;
}

JNIEXPORT void JNICALL
Java_com_agentisco_local_runtime_LlamaNative_nativeBackendFree(JNIEnv *, jobject) {
  llama_backend_free();
}

JNIEXPORT jstring JNICALL
Java_com_agentisco_local_runtime_LlamaNative_nativeLastError(JNIEnv *env, jobject) {
  return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT jint JNICALL
Java_com_agentisco_local_runtime_LlamaNative_nativeSystemThreads(JNIEnv *, jobject) {
  return (jint) std::thread::hardware_concurrency();
}

}  // extern "C"
