// The crash record the Java handler cannot see.
//
// AwakiApplication already writes files/awaki-last-crash.txt when a Kotlin thread dies, and the
// settings screen reads that file back. None of it applies to this process's other way of ending:
// a segmentation fault inside ggml, a SIGABRT from a C++ exception that escaped a noexcept
// boundary, or a SIGILL from a kernel set the device cannot run. Those never reach a Java frame,
// so the app simply disappears — no dialog, no crash log, and nothing for the person holding the
// phone to send back.
//
// So the engine chains onto whatever already watches these signals and appends the same file the
// Crash Log screen reads, using only what is safe to call while a thread is dying: no allocation,
// no lock, no stdio. The fault is then handed back so debuggerd still gets its tombstone and the
// process still dies the way it would have without this handler.

#include <fcntl.h>
#include <signal.h>
#include <unistd.h>

#if defined(__aarch64__)
#include <sys/ucontext.h>
#endif

#include <unwind.h>

#include <cstddef>
#include <cstdint>
#include <ctime>

#include <android/log.h>

#include "engine_shared.h"

namespace {

/** Where to append. Copied out of Java at install because the heap may not be trustable later. */
char g_log_path[1024];
size_t g_log_path_length = 0;

/** What the engine was doing, so a fault is tied to a step rather than to a process. */
char g_phase[192] = "engine not started";
char g_model[512] = "(none loaded)";

constexpr int kSignals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE};
constexpr size_t kSignalCount = sizeof(kSignals) / sizeof(kSignals[0]);

struct sigaction g_previous[kSignalCount];

const char *signal_name(int number) {
  switch (number) {
    case SIGSEGV: return "SIGSEGV";
    case SIGABRT: return "SIGABRT";
    case SIGBUS: return "SIGBUS";
    case SIGILL: return "SIGILL";
    case SIGFPE: return "SIGFPE";
    default: return "signal";
  }
}

/**
 * Bytes assembled without touching the heap.
 *
 * Fixed capacity on purpose: running out stops recording rather than growing, and growing means
 * malloc, which is the one call not allowed here — a fault inside ggml is exactly the moment the
 * heap and the loader's locks may already be wrecked or held by this very thread.
 */
class Sink {
 public:
  void text(const char *value) {
    for (; value != nullptr && *value != '\0' && length_ + 1 < capacity_; ++value) data_[length_++] = *value;
  }

  void hex(std::uintptr_t value) {
    text("0x");
    if (value == 0) {
      text("0");
      return;
    }
    char digits[17];
    size_t count = 0;
    while (value != 0 && count < sizeof(digits)) {
      digits[count++] = kHexDigits[value & 0xF];
      value >>= 4;
    }
    while (count > 0 && length_ + 1 < capacity_) data_[length_++] = digits[--count];
  }

  void number(long long value) {
    if (value < 0) {
      text("-");
      value = -value;
    }
    char digits[21];
    size_t count = 0;
    do {
      digits[count++] = static_cast<char>('0' + value % 10);
      value /= 10;
    } while (value != 0 && count < sizeof(digits));
    while (count > 0 && length_ + 1 < capacity_) data_[length_++] = digits[--count];
  }

  const char *bytes() const { return data_; }
  size_t length() const { return length_; }

 private:
  static constexpr size_t capacity_ = 6144;
  static constexpr char kHexDigits[] = "0123456789abcdef";
  char data_[capacity_];
  size_t length_ = 0;
};

struct Walk {
  std::uintptr_t frames[24];
  int depth = 0;
};

_Unwind_Reason_Code collect(struct _Unwind_Context *context, void *token) {
  auto *walk = static_cast<Walk *>(token);
  const std::uintptr_t pc = static_cast<std::uintptr_t>(_Unwind_GetIP(context));
  if (pc == 0) return _URC_NO_REASON;
  if (walk->depth >= static_cast<int>(sizeof(walk->frames) / sizeof(walk->frames[0]))) return _URC_END_OF_STACK;
  walk->frames[walk->depth++] = pc;
  return _URC_NO_REASON;
}

size_t slot_of(int number) {
  for (size_t i = 0; i < kSignalCount; ++i) {
    if (kSignals[i] == number) return i;
  }
  return kSignalCount;
}

/**
 * The current UTC second, spelled out with arithmetic and nothing else.
 *
 * The calendar helpers are the tempting way to write this line and the reason not to: `strftime`
 * reads the timezone, which can take a lock and allocate, and a thread that just faulted inside an
 * allocator may be holding that lock itself. Counting days off the epoch needs only `time`, and the
 * line still says which run the record belongs to.
 */
void append_utc_time(Sink &sink) {
  const long long seconds = static_cast<long long>(time(nullptr));
  const long long hour = (seconds % 86400) / 3600;
  const long long minute = (seconds % 3600) / 60;
  const long long part_second = seconds % 60;

  // Days since 0000-03-01, the era a four-century cycle divides into exactly; March counts as the
  // year's first month so the leap day is the last thing that has to be accounted for.
  const long long days = seconds / 86400 + 719468;
  const long long era = (days >= 0 ? days : days - 146096) / 146097;
  const long long day_of_era = days - era * 146097;
  const long long year_of_era =
      (day_of_era - day_of_era / 1460 + day_of_era / 36524 - day_of_era / 146096) / 365;
  long long year = year_of_era + era * 400;
  const long long day_of_year =
      day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
  const long long month_index = (5 * day_of_year + 2) / 153;
  const long long day = day_of_year - (153 * month_index + 2) / 5 + 1;
  const long long month = month_index + (month_index < 10 ? 3 : -9);
  if (month <= 2) year++;

  sink.text("At: ");
  sink.number(year);
  sink.text("-");
  if (month < 10) sink.text("0");
  sink.number(month);
  sink.text("-");
  if (day < 10) sink.text("0");
  sink.number(day);
  sink.text(" ");
  if (hour < 10) sink.text("0");
  sink.number(hour);
  sink.text(":");
  if (minute < 10) sink.text("0");
  sink.number(minute);
  sink.text(":");
  if (part_second < 10) sink.text("0");
  sink.number(part_second);
  sink.text(" UTC\n");
}

void record_crash(int number, siginfo_t *info, void *context) {
  if (g_log_path_length == 0) return;

  Sink sink;
  sink.text("\n--- native crash ---\n");
  append_utc_time(sink);
  sink.text(signal_name(number));
  sink.text(" (");
  sink.number(number);
  sink.text(")\n");
  sink.text("Fault address: ");
  sink.hex(reinterpret_cast<std::uintptr_t>(info != nullptr ? info->si_addr : nullptr));
  sink.text("\nPhase: ");
  sink.text(g_phase);
  sink.text("\nModel: ");
  sink.text(g_model);
  sink.text("\n");

#if defined(__aarch64__)
  const mcontext_t &machine = static_cast<ucontext_t *>(context)->uc_mcontext;
  sink.text("pc=");
  sink.hex(static_cast<std::uintptr_t>(machine.pc));
  sink.text(" lr=");
  sink.hex(static_cast<std::uintptr_t>(machine.regs[30]));
  sink.text("\n");
#endif

  // The least safe part comes last: whatever is recorded by now already says which engine step
  // died and where, even if the walk stops early on a corrupted stack.
  Walk walk;
  _Unwind_Backtrace(collect, &walk);
  sink.text("Backtrace:\n");
  for (int i = 0; i < walk.depth; ++i) {
    sink.text("  #");
    sink.number(i);
    sink.text(" ");
    sink.hex(walk.frames[i]);
    sink.text("\n");
  }
  sink.text("--- end of native crash ---\n");

  const int fd = open(g_log_path, O_WRONLY | O_CREAT | O_APPEND, 0600);
  if (fd >= 0) {
    // A device crash-looping a bad model must not fill storage with records, and the newest one is
    // the useful one: past this size the log starts over. `lseek` and `ftruncate` are both safe to
    // call here, and O_APPEND puts the write at the new end either way.
    if (lseek(fd, 0, SEEK_END) > 512 * 1024) ftruncate(fd, 0);
    const ssize_t written = write(fd, sink.bytes(), sink.length());
    (void) written;
    close(fd);
  }
  __android_log_print(ANDROID_LOG_ERROR, "AwakiLlm", "native crash (%s) while: %s", signal_name(number), g_phase);
}

}  // namespace

void set_engine_phase(const char *phase, const char *model) {
  if (phase != nullptr) {
    size_t i = 0;
    for (; phase[i] != '\0' && i + 1 < sizeof(g_phase); ++i) g_phase[i] = phase[i];
    g_phase[i] = '\0';
  }
  if (model != nullptr) {
    size_t i = 0;
    for (; model[i] != '\0' && i + 1 < sizeof(g_model); ++i) g_model[i] = model[i];
    g_model[i] = '\0';
  }
}

void install_crash_capture(const char *log_path) {
  if (log_path == nullptr || log_path[0] == '\0') return;
  size_t i = 0;
  for (; log_path[i] != '\0' && i + 1 < sizeof(g_log_path); ++i) g_log_path[i] = log_path[i];
  g_log_path[i] = '\0';
  g_log_path_length = i;

  for (size_t index = 0; index < kSignalCount; ++index) {
    struct sigaction action = {};
    sigemptyset(&action.sa_mask);
    action.sa_sigaction = [](int number, siginfo_t *info, void *context) {
      record_crash(number, info, context);
      // Hand the fault to whoever was watching before this library existed - ART's own chain, or
      // the kernel's default - so recording a crash does not become suppressing one. The signal is
      // masked for the duration of this handler, so a re-raised one is delivered when it returns.
      const size_t slot = slot_of(number);
      if (slot < kSignalCount && g_previous[slot].sa_flags & SA_SIGINFO) {
        g_previous[slot].sa_sigaction(number, info, context);
        return;
      }
      // A watcher that registered the older, simpler form still has to see the fault: dropping it
      // here would make this library the reason another one's crash report never got written.
      if (slot < kSignalCount && g_previous[slot].sa_handler != SIG_DFL &&
          g_previous[slot].sa_handler != SIG_IGN && g_previous[slot].sa_handler != nullptr) {
        g_previous[slot].sa_handler(number);
        return;
      }
      if (slot < kSignalCount && g_previous[slot].sa_handler != SIG_IGN) {
        signal(number, SIG_DFL);
      }
      raise(number);
    };
    action.sa_flags = SA_SIGINFO;
    sigaction(kSignals[index], &action, &g_previous[index]);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_awaki_local_jni_NativeLlama_nativeInstallCrashCapture(JNIEnv *env, jobject, jstring log_path) {
  if (g_log_path_length > 0) return;  // one install per process, so a second cannot hide the first
  const char *path = env->GetStringUTFChars(log_path, nullptr);
  install_crash_capture(path);
  if (path != nullptr) env->ReleaseStringUTFChars(log_path, path);
}
