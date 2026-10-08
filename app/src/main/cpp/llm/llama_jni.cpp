// JNI bridge between Awaki and the pinned llama.cpp engine.
//
// Deliberately thin: it owns model + context lifetime and the decode loop, and
// nothing else. Prompt rendering and tool-call parsing live next door, in
// chat_jni.cpp, which hands the engine a prompt and a grammar and takes back the
// parsed answer; the OpenAI-compatible wire format lives in Kotlin, so the parts
// that carry product rules stay testable on the JVM.
//
// Finish codes returned by nativeComplete:
//   0 = end of sequence   1 = max tokens   2 = caller stopped   3 = aborted
//   4 = context full      negative = engine error (see nativeLastError)
//
// Of the negatives, -8 is not an engine error: it means the prompt is longer than the
// context the user configured, so the request should be changed rather than retried.

#include "engine_shared.h"
#include <android/log.h>

#include <ggml-backend.h>
#include <llama.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <ctime>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "AwakiLlm"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

std::string g_last_error;

void set_error(const std::string &message) {
  g_last_error = message;
  LOGE("%s", message.c_str());
}

namespace {

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

/** The clock core [cpu] can reach, in kHz, or 0 when it reports nothing. */
long read_cpu_max_freq(unsigned cpu) {
  char path[128];
  snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%u/cpufreq/cpuinfo_max_freq", cpu);
  FILE *file = fopen(path, "r");
  if (file == nullptr) return 0;
  long khz = 0;
  const int scanned = fscanf(file, "%ld", &khz);
  fclose(file);
  return scanned == 1 ? khz : 0;
}

/** What this device's cores are, in the two numbers a turn is asked to work with. */
struct ThreadPlan {
  int32_t cores;
  /** Threads for a single token, where the barrier at the end of the step is the whole cost. */
  int32_t decode;
  /** Threads for a chunk of the prompt, which is the same work over far fewer barriers. */
  int32_t batch;
};

/**
 * How many of this device's cores should be given the two kinds of work a turn is made of.
 *
 * A decode step ends in a barrier every worker waits at, so one thread parked on a little core
 * holds up the fast ones and the step pays for it. Measured on this engine with the same prompt, a
 * pool sized to every core was ten to fifteen times slower than one sized to the fast cluster.
 *
 * A prompt chunk is the opposite shape: the same work spread over far fewer, much wider graph
 * evaluations, so the barrier is amortised and cores that are merely slow still add throughput.
 * That is why prefill gets the wider number.
 *
 * The clusters come from the clock each core reports, which is the only portable description of
 * big.LITTLE there is, with one correction: a device whose cores all report nearly the same top
 * clock is not a device with no little cores, it is a device whose clocks do not tell them apart.
 * A Helio G99 runs its six Cortex-A55s at 91 % of the frequency of its two A76s, and an A55 is
 * nowhere near an A76. Where every core clears the cut-off, the cut-off is discarded and half of
 * them are used - the same answer a device that reports no clocks at all gets.
 */
ThreadPlan thread_plan() {
  unsigned total = std::thread::hardware_concurrency();
  if (total == 0) total = 1;

  long fastest = 0;
  std::vector<long> clocks;
  clocks.reserve(total);
  for (unsigned cpu = 0; cpu < total; ++cpu) {
    const long khz = read_cpu_max_freq(cpu);
    if (khz <= 0) continue;
    fastest = std::max(fastest, khz);
    clocks.push_back(khz);
  }

  const int32_t cores = (int32_t) (clocks.empty() ? total : clocks.size());
  const int32_t half = std::max(1, cores / 2);
  if (clocks.empty()) return {/* cores = */ cores, /* decode = */ half, /* batch = */ cores};

  int32_t fast = 0;
  int32_t wide = 0;
  for (const long khz : clocks) {
    if (khz * 5 >= fastest * 4) ++fast;  // within 80 % of the top clock
    if (khz * 2 >= fastest) ++wide;      // within 50 % of it
  }
  if (fast == 0) fast = 1;
  if (fast >= cores) fast = half;
  return {/* cores = */ cores, /* decode = */ fast, /* batch = */ std::max(fast, wide)};
}

using pool_new = ggml_threadpool_t (*)(struct ggml_threadpool_params *);

/**
 * The CPU backend's pool constructor, reached through the registry.
 *
 * The constructor lives in the CPU backend module rather than in libggml - this build ships the
 * backends as modules - so it is only reachable this way, which is how llama.cpp's own callers get
 * it. Null is not a failure: ggml then falls back to building a pool for every graph evaluation,
 * which is what a device without the symbol has always done. Diagnostics reports which of the two
 * this device is on, because the difference shows up as tokens per second and nothing else.
 */
pool_new threadpool_factory() {
  ggml_backend_reg_t reg = ggml_backend_reg_by_name("CPU");
  if (reg == nullptr) return nullptr;
  return (pool_new) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new");
}

/**
 * The pool one model decodes with.
 *
 * Every graph evaluation without one builds `n_threads` threads, joins them and destroys them, and
 * leaves their placement to a scheduler that is free to put a worker on a little core the thread
 * count above was chosen to avoid. One pool per loaded model replaces that with threads created
 * once, waiting on a condition between steps.
 */
ggml_threadpool_t new_threadpool(int32_t n_threads) {
  const pool_new create = threadpool_factory();
  if (create == nullptr || n_threads <= 0) return nullptr;
  struct ggml_threadpool_params params = ggml_threadpool_params_default(n_threads);
  return create(&params);
}

void free_threadpool(ggml_threadpool_t pool) {
  if (pool == nullptr) return;
  using pool_free = void (*)(ggml_threadpool_t);
  ggml_backend_reg_t reg = ggml_backend_reg_by_name("CPU");
  if (reg == nullptr) return;
  const auto destroy = (pool_free) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_free");
  if (destroy != nullptr) destroy(pool);
}

/** Whether the code running on this device was compiled to run fast, which the caller cannot guess. */
bool compiled_optimized() {
#ifdef __OPTIMIZE__
  return true;
#else
  return false;
#endif
}

const char *optimization_state() {
  return compiled_optimized() ? "optimized" : "UNOPTIMIZED";
}

/** Kilobytes the kernel could still hand out, or -1 when /proc says nothing. */
long mem_available_kb() {
  FILE *file = fopen("/proc/meminfo", "r");
  if (file == nullptr) return -1;
  char line[256];
  long kb = -1;
  while (fgets(line, sizeof(line), file) != nullptr) {
    if (sscanf(line, "MemAvailable: %ld kB", &kb) == 1) break;
  }
  fclose(file);
  return kb;
}

/**
 * What the device could give a model when the model was refused.
 *
 * "Failed to create context" is true and useless to the person who tapped Load: the
 * usual cause is a context size this phone no longer has room for next to a terminal
 * and a chat, and the only way to say that back is to name the free memory.
 */
std::string memory_note() {
  const long kb = mem_available_kb();
  if (kb < 0) return std::string();
  return " (this device had " + std::to_string(kb / 1024) + " MB free)";
}

/** Milliseconds since [started] — the unit every phase of a turn is reported in. */
long elapsed_ms(const std::chrono::steady_clock::time_point &started) {
  return (long) std::chrono::duration_cast<std::chrono::milliseconds>(
      std::chrono::steady_clock::now() - started).count();
}

/**
 * The shortest run of repeated tokens worth a copy of the engine's state for. Reuse buys
 * nothing on a head too short to prefill slowly, and every restore is a chance to be wrong
 * about what the state holds.
 */
constexpr size_t MIN_PREFIX_REUSE_TOKENS = 64;

/**
 * Copies this sequence's engine state out, so a later request that starts with the same tokens
 * can load it back and skip prefilling them.
 *
 * The copy is one whole prefix or nothing: the state a request shares with the next one is a
 * position in the sequence, and loading it back has to leave the engine certain where it is.
 * A prompt too short to be worth copying leaves the last snapshot alone, which is also why the
 * small side requests — naming a conversation, summarizing a turn — cost nothing here.
 */
void save_prefix_snapshot(Session *s, const std::vector<llama_token> &tokens, size_t head) {
  if (head < MIN_PREFIX_REUSE_TOKENS) return;
  const size_t want = llama_state_seq_get_size(s->ctx, 0);
  if (want == 0) return;
  s->snapshot.resize(want);
  if (llama_state_seq_get_data(s->ctx, s->snapshot.data(), want, 0) != want) {
    LOGI("prefix snapshot at %zu tokens could not be taken", head);
    s->snapshot.clear();
    s->snapshot.shrink_to_fit();
    s->prefix_tokens.clear();
    return;
  }
  s->prefix_tokens.assign(tokens.begin(), tokens.begin() + (ptrdiff_t) head);
  LOGI("prefix snapshot: %zu tokens, %.1f MB", head, (double) want / 1048576.0);
}

std::string meta_string(llama_model *model, const char *key) {
  char buf[4096];
  const int32_t len = llama_model_meta_val_str(model, key, buf, sizeof(buf));
  if (len < 0) return std::string();
  return std::string(buf, (size_t) (len < (int32_t) sizeof(buf) ? len : (int32_t) sizeof(buf) - 1));
}

/**
 * The grammar that constrains this turn's answer, held off until the answer starts needing it.
 *
 * A grammar is tested against the whole vocabulary on every token, so a turn that ends in prose
 * pays the full cost of a tool-call grammar it never uses. The template knows what opens a call in
 * this model's dialect, so the constraint can wait for that and let the tokens before it be
 * sampled plain. Applying it eagerly - which is what this did - makes every token of an on-device
 * turn pay a pass it cannot use, on the one device that cannot afford it.
 *
 * A lazy grammar with nothing to trigger it would never engage, and a tool call would be answered
 * unconstrained, which is worse than slow. So that case falls back to constraining from token zero.
 */
llama_sampler *grammar_sampler(const Session *s, const char *grammar) {
  if (!s->grammar_lazy || s->grammar_triggers.empty()) {
    return llama_sampler_init_grammar(s->vocab, grammar, "root");
  }

  std::vector<std::string> patterns;
  std::vector<llama_token> tokens;
  for (const auto &trigger : s->grammar_triggers) {
    switch (trigger.type) {
      case COMMON_GRAMMAR_TRIGGER_TYPE_WORD:
        patterns.push_back(regex_escape(trigger.value));
        break;
      case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN:
        patterns.push_back(trigger.value);
        break;
      case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN_FULL: {
        const std::string &pattern = trigger.value;
        std::string anchored = pattern;
        if (!anchored.empty()) {
          if (anchored.front() != '^') anchored.insert(anchored.begin(), '^');
          if (anchored.back() != '$') anchored.push_back('$');
        } else {
          anchored = "^$";
        }
        patterns.push_back(anchored);
        break;
      }
      case COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN:
        tokens.push_back(trigger.token);
        break;
    }
  }

  std::vector<const char *> pattern_text;
  pattern_text.reserve(patterns.size());
  for (const auto &pattern : patterns) pattern_text.push_back(pattern.c_str());

  return llama_sampler_init_grammar_lazy_patterns(
      s->vocab, grammar, "root", pattern_text.data(), pattern_text.size(), tokens.data(), tokens.size());
}

/** Lets a cancelled request stop in the middle of a prompt, not only between its tokens. */
bool aborted(void *user_data) {
  return static_cast<Session *>(user_data)->abort.load();
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
    llama_sampler_chain_add(chain, grammar_sampler(s, grammar));
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

/**
 * Prepares the engine once per process, from the directory its own libraries sit in.
 *
 * The CPU backends are dynamic modules, so nothing can decode until the ones this
 * device can run have been registered; ggml scores each against the actual CPU and
 * picks the best. Kotlin names the directory because it is the only one that knows
 * where the app's native libraries were extracted to.
 */
JNIEXPORT void JNICALL
Java_com_awaki_local_jni_NativeLlama_nativeInit(JNIEnv *env, jobject, jstring native_lib_dir) {
  // llama.cpp describes its computation graph node by node at debug and info level, which
  // is tens of lines for every token. Each one is a logcat write on the thread that is
  // trying to decode, so only what the caller has to hear about crosses here.
  //
  // GGML_LOG_LEVEL_CONT is deliberately left out: it continues whatever line came before,
  // and in practice that is the '.' the loader ticks while it reads tensors. Recording one
  // as the last error would replace the message that mattered with a dot.
  llama_log_set(
      [](ggml_log_level level, const char *text, void *) {
        const std::string message(text ? text : "");
        if (level == GGML_LOG_LEVEL_ERROR) {
          set_error(message);
        } else if (level == GGML_LOG_LEVEL_WARN) {
          LOGI("%s", message.c_str());
        }
      },
      nullptr);

  // A refused copy of the folder is a start-up that cannot find its kernels, which is worth a
  // message rather than a backend list that is quietly empty.
  const char *dir = env->GetStringUTFChars(native_lib_dir, nullptr);
  if (dir == nullptr) {
    take_pending_exception(env, "the folder the engine's own libraries are in");
    return;
  }
  const std::string library_dir(dir);
  env->ReleaseStringUTFChars(native_lib_dir, dir);
  ggml_backend_load_all_from_path(library_dir.c_str());

  llama_backend_init();

  // Three facts a slow or silent turn can be, none of which this engine used to say out loud:
  // whether the code running was compiled to run fast, which of the CPU kernel sets this device
  // was scored onto, and how many cores the thread plan chose from. All three are properties of
  // the device rather than of a request, so they are logged once here and kept for the settings
  // screen to read back.
  const ThreadPlan plan = thread_plan();
  const size_t devices = ggml_backend_dev_count();
  LOGI("engine %s, %d cores, %d threads to decode and %d to prefill, %zu backends from %s",
       optimization_state(), plan.cores, plan.decode, plan.batch, devices, library_dir.c_str());
  for (size_t i = 0; i < devices; ++i) {
    const ggml_backend_dev_t dev = ggml_backend_dev_get(i);
    LOGI("backend: %s | %s", ggml_backend_dev_name(dev), ggml_backend_dev_description(dev));
  }
}

JNIEXPORT jlong JNICALL
Java_com_awaki_local_jni_NativeLlama_nativeLoadModel(JNIEnv *env,
                                                        jobject,
                                                        jstring path,
                                                        jint ctx_size,
                                                        jint threads,
                                                        jint n_batch) {
  const char *cpath = env->GetStringUTFChars(path, nullptr);
  if (cpath == nullptr) {
    take_pending_exception(env, "the file name of the model to load");
    return 0;
  }
  // If this step is the one that faults, the crash record has to say which file was being read.
  set_engine_phase("loading model", cpath);

  const auto load_started = std::chrono::steady_clock::now();

  auto mparams = llama_model_default_params();
  mparams.n_gpu_layers = 0;  // CPU only: no Vulkan/OpenCL backend is compiled in.
  // The file already passed GgufInspector before it was installed, and a tensor whose
  // data is really corrupt fails at the first read anyway. Checking every tensor on load
  // costs seconds on a phone for a check the user has effectively already paid for.
  mparams.check_tensors = false;

  llama_model *model = llama_model_load_from_file(cpath, mparams);
  const long load_ms = (long) std::chrono::duration_cast<std::chrono::milliseconds>(
      std::chrono::steady_clock::now() - load_started).count();
  // Every phase of a turn is logged with its numbers, because "it never answered" is
  // three different faults on a phone — still prefilling, refused the prompt, or died —
  // and only the last one announces itself.
  LOGI("model read in %ld ms: %s", load_ms, model != nullptr ? cpath : "FAILED");
  env->ReleaseStringUTFChars(path, cpath);
  if (model == nullptr) {
    set_error(std::string("Could not read this GGUF file") + memory_note());
    return 0;
  }

  char arch[256] = {0};
  llama_model_meta_val_str(model, "general.architecture", arch, sizeof(arch));

  const ThreadPlan plan = thread_plan();
  auto cparams = llama_context_default_params();
  cparams.n_ctx = (uint32_t) (ctx_size > 0 ? ctx_size : 0);
  // A larger batch is the same prefill work in fewer, wider graph evaluations, which on
  // CPU is most of what a phone has to give: 128 leaves the cores idle between chunks.
  cparams.n_batch = (uint32_t) (n_batch > 0 ? n_batch : 512);
  cparams.n_ubatch = cparams.n_batch;
  // Zero from Kotlin means the device decides, and llama.cpp's own default is a small fixed number
  // that leaves most of a phone idle. A caller that names a count gets that count for both kinds of
  // work, because it is the user's number and the plan's guess is not a reason to override it.
  cparams.n_threads = threads > 0 ? (int32_t) threads : plan.decode;
  cparams.n_threads_batch =
      threads > 0 ? (int32_t) threads : std::max(cparams.n_threads, plan.batch);

  // Allocated before the context, because the context is told to ask it whether to stop: an
  // interrupt has to be able to land in the middle of a prompt, and a prompt is where a phone spends
  // most of the wait a user is interrupting.
  auto *s = new Session();
  s->model = model;
  s->cores_seen = plan.cores;
  s->n_threads = cparams.n_threads;
  s->n_threads_batch = cparams.n_threads_batch;
  cparams.abort_callback = aborted;
  cparams.abort_callback_data = s;

  llama_context *ctx = llama_init_from_model(model, cparams);
  if (ctx == nullptr) {
    llama_model_free(model);
    delete s;
    set_error("Could not hold " + std::to_string(cparams.n_ctx) + " tokens of context for this " +
              (std::string(arch) + " model") + memory_note() +
              ". Lower the context size, or close other apps.");
    return 0;
  }
  s->ctx = ctx;
  s->vocab = llama_model_get_vocab(model);
  s->n_batch = (int32_t) cparams.n_batch;
  g_last_error.clear();

  // One pool for both kinds of work, sized to the larger of the two counts: ggml clamps a request
  // to the threads a pool has rather than growing it, so a pool built to the decode number would
  // quietly prefill at the decode number too.
  s->threadpool = new_threadpool(std::max(cparams.n_threads, cparams.n_threads_batch));
  if (s->threadpool != nullptr) {
    llama_attach_threadpool(ctx, s->threadpool, nullptr);
  }
  LOGI("context ready: %u tokens, batch %u, %d threads to decode / %d to prefill%s",
       cparams.n_ctx, cparams.n_batch, cparams.n_threads, cparams.n_threads_batch,
       s->threadpool == nullptr ? ", pooled workers unavailable" : "");

  // Compile the template this file ships, once, at load. A model whose template does
  // not compile is still a usable model for plain completions, so the failure is
  // recorded rather than fatal — chat_jni.cpp reports it when a chat is asked for.
  // llama.cpp falls back to ChatML when a file carries no template at all; `explicit`
  // in nativeChatTemplatesInfo is how Kotlin learns that happened.
  try {
    s->templates = common_chat_templates_init(model, std::string());
  } catch (const std::exception &e) {
    s->templates = nullptr;
    set_error(std::string("This model's chat template could not be compiled: ") + e.what());
  }
  return (jlong) (intptr_t) s;
}

JNIEXPORT jbyteArray JNICALL
Java_com_awaki_local_jni_NativeLlama_nativeModelInfo(JNIEnv *env, jobject, jlong handle) {
  Session *s = session_of(handle);
  if (s == nullptr) return to_bytes(env, "{}", "the model's metadata");

  char arch[256] = {0};
  llama_model_meta_val_str(s->model, "general.architecture", arch, sizeof(arch));

  // Kotlin needs the engine's real limits so Settings never offers a parameter
  // value the loaded model cannot honour, and so the screen can say what the file
  // turned out to be rather than what its catalog entry claimed.
  std::string json = "{";
  json += "\"name\":\"" + json_escape(meta_string(s->model, "general.name")) + "\",";
  json += "\"architecture\":\"" + json_escape(arch) + "\",";
  json += "\"vocabSize\":" + std::to_string(llama_vocab_n_tokens(s->vocab));
  json += ",\"contextSize\":" + std::to_string(llama_n_ctx(s->ctx));
  json += ",\"trainedContextSize\":" + std::to_string(llama_model_n_ctx_train(s->model));
  json += ",\"eosTokenId\":" + std::to_string(llama_vocab_eos(s->vocab));
  json += ",\"addBos\":" + std::string(llama_vocab_get_add_bos(s->vocab) ? "true" : "false");
  json += "}";
  return to_bytes(env, json, "the model's metadata");
}

JNIEXPORT jint JNICALL
Java_com_awaki_local_jni_NativeLlama_nativeComplete(JNIEnv *env,
                                                      jobject,
                                                      jlong handle,
                                                      jbyteArray prompt,
                                                      jfloat temperature,
                                                      jint top_k,
                                                      jfloat top_p,
                                                      jfloat repeat_penalty,
                                                      jint max_tokens,
                                                      jlong seed,
                                                      jbyteArray grammar,
                                                      jobject callback) {
  Session *s = session_of(handle);
  if (s == nullptr) return -1;

  // Nothing in this function may call into Java on a thread that is already failing, so the check
  // comes before the first lookup rather than being left to the helpers below.
  if (take_pending_exception(env, "the start of a decode")) return -5;

  // The method lookup is a JNI call like any other, and a failing one leaves its exception on the
  // thread rather than only returning null. Taking it here is what keeps a bad callback from
  // turning the next call this function makes into the fault that kills the process.
  jclass cb_class = callback == nullptr ? nullptr : env->GetObjectClass(callback);
  if (cb_class == nullptr) {
    take_pending_exception(env, "the token callback this app passed");
    if (cb_class != nullptr) env->DeleteLocalRef(cb_class);
    set_error("The engine was asked to decode without anywhere to send the tokens");
    return -2;
  }
  jmethodID on_token = env->GetMethodID(cb_class, "onToken", "([B)Z");
  env->DeleteLocalRef(cb_class);
  if (on_token == nullptr) {
    take_pending_exception(env, "the token callback this app passed");
    return -2;
  }

  const std::optional<std::vector<char>> prompt_bytes = copy_bytes(env, prompt);
  if (!prompt_bytes.has_value()) return -5;
  const std::optional<std::string> grammar_text = [&] {
    // A missing grammar is a request that wants none; an unreadable one is a broken handover, and
    // sampling under a grammar that never arrived would be the model answering in prose when the
    // caller asked for calls.
    const std::optional<std::vector<char>> raw = copy_bytes(env, grammar);
    if (!raw.has_value()) return std::optional<std::string>();
    return std::optional<std::string>(std::string(raw->begin(), raw->end()));
  }();
  if (!grammar_text.has_value()) return -5;

  llama_sampler *smpl = build_sampler(s, temperature, top_k, top_p, repeat_penalty, seed, grammar_text->c_str());
  if (smpl == nullptr) {
    set_error("Failed to build sampler");
    return -3;
  }

  // Tokenize. parse_special because a rendered template is full of role markers that
  // must become single tokens, and add_special for the same reason llama.cpp's own
  // server uses it on chat prompts: a GGUF whose template already writes <bos> says so
  // with tokenizer.ggml.add_bos_token=false, so this prepends a BOS only for the files
  // that need one and never gives a second to the ones that do not.
  std::vector<llama_token> tokens;
  {
    const char *text = prompt_bytes->empty() ? "" : prompt_bytes->data();
    const int32_t text_len = (int32_t) prompt_bytes->size();
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
  const int32_t limit = max_tokens > 0 ? max_tokens : 512;

  // From here to the end of the loop is where this library spends both its time and its risk: the
  // kernels that fault are reached from llama_decode. The token count is refreshed as decoding
  // goes, so a crash record says how far the turn got rather than only what kind of step it was.
  set_engine_phase("tokenizing the prompt", nullptr);

  // A prompt that cannot fit is a caller error, not an engine fault. Letting decode
  // try anyway costs tens of seconds of prefill and leaves the KV cache in a state
  // the next request inherits, all to report "Failed to evaluate prompt" with no way
  // for the client to say which number has to change.
  if (tokens.size() + 1 > (size_t) n_ctx) {
    llama_sampler_free(smpl);
    set_error("This request needs " + std::to_string(tokens.size()) +
              " tokens but the context for this model holds " + std::to_string(n_ctx) +
              ". Raise the context size, or shorten the conversation.");
    return -8;
  }

  llama_batch batch = llama_batch_init(s->n_batch, 0, 1);
  std::string pending;
  int32_t generated = 0;
  int finish = 1;

  auto emit = [&](const std::string &bytes) -> bool {
    if (bytes.empty()) return true;
    // Handing a piece over is the one place this loop calls into Java, so it is the one place a
    // Java failure can be left pending. A callback that throws used to be noticed and then
    // abandoned, and ART keeps such an exception on the thread for the *next* JNI call - anywhere
    // in the engine - to abort the process over. That is the app vanishing mid-tool-call with no
    // message anywhere. to_bytes refuses a thread that is already failing, and the sink's own
    // throw is described and taken here, so a broken handover ends the turn with a reason.
    jbyteArray arr = to_bytes(env, bytes, "a piece of the answer");
    if (arr == nullptr) return false;
    const jboolean keep = env->CallBooleanMethod(callback, on_token, arr);
    env->DeleteLocalRef(arr);
    if (env->ExceptionCheck()) {
      take_pending_exception(env, "the sink reading a piece of the answer");
      return false;
    }
    return keep == JNI_TRUE;
  };

  // Each completion is a sequence of its own, but the *head* of this prompt is usually the
  // head of the last one: an agent turn re-sends the same playbook and the same tool specs
  // ahead of the user's new message, and re-prefilling those tokens on a phone is most of the
  // wait the user sits through. So the state left at the point the two prompts still agree is
  // copied out after one request and loaded back before the next, which then evaluates only
  // its own tail. The last token of the prompt is never part of the copy — it is the position
  // the answer is sampled from, so every request evaluates it, including a retried turn whose
  // prompt is identical to the one before it.
  llama_memory_t mem = llama_get_memory(s->ctx);
  const size_t head = tokens.size() > 1 ? tokens.size() - 1 : 0;
  size_t reuse = 0;
  if (!s->snapshot.empty() && head >= s->prefix_tokens.size() &&
      std::equal(s->prefix_tokens.begin(), s->prefix_tokens.end(), tokens.begin())) {
    if (llama_state_seq_set_data(s->ctx, s->snapshot.data(), s->snapshot.size(), 0) ==
        s->snapshot.size()) {
      reuse = s->prefix_tokens.size();
    } else {
      LOGI("the saved prefix could not be restored; evaluating the whole prompt");
      s->snapshot.clear();
      s->snapshot.shrink_to_fit();
      s->prefix_tokens.clear();
    }
  }
  if (reuse == 0) llama_memory_clear(mem, false);

  const auto prefill_started = std::chrono::steady_clock::now();
  int32_t n_past = (int32_t) reuse;

  // Evaluates [from, to) in chunks the context batch can take. Only the last position of the
  // whole prompt asks for logits; the others are here to fill the cache.
  auto evaluate = [&](size_t from, size_t to) -> bool {
    for (size_t i = from; i < to;) {
      const int32_t chunk = (int32_t) std::min<size_t>((size_t) s->n_batch, to - i);
      batch.n_tokens = chunk;
      for (int32_t j = 0; j < chunk; ++j) {
        batch.token[j] = tokens[i + (size_t) j];
        batch.pos[j] = n_past + j;
        batch.n_seq_id[j] = 1;
        batch.seq_id[j][0] = 0;
        batch.logits[j] = (i + (size_t) j + 1 == tokens.size()) ? 1 : 0;
      }
      if (llama_decode(s->ctx, batch) != 0) return false;
      n_past += chunk;
      i += (size_t) chunk;
    }
    return true;
  };

  // The shared head goes in first, because the copy has to be taken before the answer's own
  // tokens are written over the positions the next request would share.
  set_engine_phase(("prefilling " + std::to_string(tokens.size() - reuse) + " tokens").c_str(), nullptr);
  bool prefill_ok = !tokens.empty();
  if (prefill_ok) prefill_ok = evaluate(reuse, head);
  if (prefill_ok && head > reuse) save_prefix_snapshot(s, tokens, head);
  if (prefill_ok) prefill_ok = evaluate(head, tokens.size());
  if (!prefill_ok) {
    // Where the engine stopped is not known, so nothing copied out before it can be trusted.
    s->snapshot.clear();
    s->snapshot.shrink_to_fit();
    s->prefix_tokens.clear();
    if (s->abort.load()) {
      // The caller stopped it partway through the prompt rather than the engine failing, which the
      // runtime needs to hear as an abort: an error here would make it drop the model the user only
      // wanted to interrupt, and their next turn would pay for a reload.
      finish = 3;
    } else {
      finish = -5;
      set_error("Failed to evaluate prompt");
    }
  }

  const long prefill_ms = elapsed_ms(prefill_started);
  LOGI("prompt %zu tokens: %zu reused, %zu evaluated in %ld ms (%.0f tok/s)",
       tokens.size(), reuse, tokens.size() - reuse, prefill_ms,
       prefill_ms > 0 ? (double) (tokens.size() - reuse) * 1000.0 / (double) prefill_ms : 0.0);

  long sample_ms = 0;
  long decode_ms = 0;
  long detok_ms = 0;
  long emit_ms = 0;
  long first_text_ms = -1;
  const auto decode_started = std::chrono::steady_clock::now();

  while (prefill_ok && generated < limit) {
    if (s->abort.load()) {
      finish = 3;
      break;
    }
    if ((uint32_t) n_past >= n_ctx) {
      finish = 4;
      break;
    }

    const auto sample_started = std::chrono::steady_clock::now();
    const llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
    sample_ms += elapsed_ms(sample_started);
    if (s->abort.load()) {
      finish = 3;
      break;
    }
    llama_sampler_accept(smpl, tok);
    generated++;
    // Refreshed every so often rather than every token: the string costs an allocation, and a
    // crash record that names the last 32nd token is specific enough to point at a kernel.
    if (generated % 32 == 0) {
      set_engine_phase(("decoding token " + std::to_string(generated)).c_str(), nullptr);
    }

    if (llama_vocab_is_eog(s->vocab, tok)) {
      finish = 0;
      break;
    }

    char piece[256];
    const auto detok_started = std::chrono::steady_clock::now();
    // A special token's text is nothing at all unless it is asked for by name, so a model that
    // writes its tool calls as markers had them deleted before either parser could see them. The
    // turn's own template says which markers belong to an answer; a special token outside that list
    // is still dropped, so markup the caller never asked for does not reach the screen either.
    const bool preserved =
        std::find(s->preserved_tokens.begin(), s->preserved_tokens.end(), tok) != s->preserved_tokens.end();
    const int32_t written = llama_token_to_piece(s->vocab, tok, piece, sizeof(piece), 0, preserved);
    detok_ms += elapsed_ms(detok_started);
    if (written < 0) {
      finish = -6;
      set_error("Failed to detokenize");
      break;
    }
    pending.append(piece, (size_t) std::max(0, written));

    const size_t safe = complete_utf8_prefix_len(pending);
    if (safe > 0) {
      if (first_text_ms < 0) first_text_ms = elapsed_ms(decode_started);
      const auto emit_started = std::chrono::steady_clock::now();
      const bool keep = emit(pending.substr(0, safe));
      emit_ms += elapsed_ms(emit_started);
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
    const auto step_started = std::chrono::steady_clock::now();
    if (llama_decode(s->ctx, batch) != 0) {
      finish = -7;
      set_error("Failed to evaluate generated token");
      break;
    }
    decode_ms += elapsed_ms(step_started);
    n_past++;
  }

  if (!pending.empty() && finish != 2) emit(pending);

  // Nothing of this library is running between turns, so a later fault - in the terminal, in the
  // UI - must not be filed against the last decode this handle performed.
  set_engine_phase("between requests", nullptr);

  LOGI("turn %d: %d tokens in %ld ms (sample %ld, decode %ld, detok %ld, emit %ld), first text at %ld ms",
       finish, generated, elapsed_ms(decode_started), sample_ms, decode_ms, detok_ms, emit_ms, first_text_ms);

  llama_batch_free(batch);
  llama_sampler_free(smpl);
  return finish;
}

JNIEXPORT void JNICALL
Java_com_awaki_local_jni_NativeLlama_nativeAbort(JNIEnv *, jobject, jlong handle) {
  Session *s = session_of(handle);
  if (s != nullptr) s->abort.store(true);
}

JNIEXPORT void JNICALL
Java_com_awaki_local_jni_NativeLlama_nativeResetAbort(JNIEnv *, jobject, jlong handle) {
  Session *s = session_of(handle);
  if (s != nullptr) s->abort.store(false);
}

JNIEXPORT void JNICALL
Java_com_awaki_local_jni_NativeLlama_nativeUnload(JNIEnv *, jobject, jlong handle) {
  Session *s = session_of(handle);
  if (s == nullptr) return;
  // Freeing gigabytes of weights is a fault candidate of its own on a device under memory
  // pressure, and it is the one step a user asks for by tapping Unload.
  set_engine_phase("unloading the model", nullptr);
  s->abort.store(true);
  if (s->ctx) {
    // The context only holds a pointer to the pool, so the pool outlives it and has to be released
    // here: a model that is loaded and unloaded repeatedly would otherwise leave a pool of worker
    // threads parked on every cycle, on a device whose whole problem is running out of memory.
    llama_detach_threadpool(s->ctx);
    llama_free(s->ctx);
  }
  free_threadpool(s->threadpool);
  free_threadpool(s->threadpool_batch);
  s->threadpool = nullptr;
  s->threadpool_batch = nullptr;
  if (s->model) llama_model_free(s->model);
  delete s;
}

JNIEXPORT void JNICALL
Java_com_awaki_local_jni_NativeLlama_nativeBackendFree(JNIEnv *, jobject) {
  llama_backend_free();
}

JNIEXPORT jbyteArray JNICALL
Java_com_awaki_local_jni_NativeLlama_nativeLastError(JNIEnv *env, jobject) {
  // The one report that has to survive everything else failing: it is what each failure below
  // reads back, so a refused allocation here leaves Kotlin with nothing but its own fallback.
  return to_bytes(env, g_last_error, "the reason a step failed");
}

/**
 * What this device and this build of the engine actually are.
 *
 * Every fault of the kind this app has had — a turn that never finishes, a model that decodes at
 * walking pace, a phone that is much slower than the measurements on the desk — is a property of
 * the build or of the silicon rather than of the request, and none of it used to be written
 * anywhere the app could show. So the questions get answered from the inside: was this compiled to
 * run fast, which kernel set did ggml score this CPU onto, how many cores does the thread plan see,
 * and what could the device still hand out. A logcat dump is not something a user can paste into a
 * bug report from a phone that is not plugged into a computer.
 *
 * Works with nothing loaded: the device facts do not come from a model.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_awaki_local_jni_NativeLlama_nativeEngineDiagnostics(JNIEnv *env, jobject, jlong handle) {
  const ThreadPlan plan = thread_plan();
  Session *s = session_of(handle);

  std::string json = "{";
  json += "\"optimized\":" + std::string(compiled_optimized() ? "true" : "false");
  json += ",\"systemInfo\":" + common_json::make(llama_print_system_info()).dump_safe();
  json += ",\"coresSeen\":" + std::to_string(plan.cores);
  json += ",\"decodeThreads\":" + std::to_string(s != nullptr ? s->n_threads : plan.decode);
  json += ",\"batchThreads\":" + std::to_string(s != nullptr ? s->n_threads_batch : plan.batch);
  json += ",\"pooledWorkers\":" + std::string(threadpool_factory() != nullptr ? "true" : "false");
  json += ",\"availableMb\":" + std::to_string(mem_available_kb() / 1024);
  json += ",\"backends\":[";
  const size_t devices = ggml_backend_dev_count();
  for (size_t i = 0; i < devices; ++i) {
    const ggml_backend_dev_t dev = ggml_backend_dev_get(i);
    if (i > 0) json += ",";
    json += "{\"name\":" + common_json::make(ggml_backend_dev_name(dev)).dump_safe();
    json += ",\"description\":" + common_json::make(ggml_backend_dev_description(dev)).dump_safe() + "}";
  }
  json += "]}";
  return to_bytes(env, json, "the engine's diagnostics");
}

}  // extern "C"
