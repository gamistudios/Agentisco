// Off-device proof for the crash record's frame resolver.
//
// crash_frames.h turns an address into `libawaki-llm.so+0x10a6c` by reading /proc/self/maps, and
// the code that needs it runs in a signal handler on a dying thread — which is not a place a test
// can be written. So the parsing is a plain function over a buffer, and this file is what proves
// it: synthetic maps for the arithmetic, and this process's real map for everything the synthetic
// text could have been written to flatter.
//
// Run it with a host compiler - it needs nothing from the NDK and nothing from Android:
//   g++ -std=c++17 -O0 -g -rdynamic -o /tmp/crash_frames_test
//       app/src/main/cpp/llm/tests/crash_frames_test.cpp && /tmp/crash_frames_test
// Compiling without -rdynamic hides libc, and the live-map checks then say so rather than pass.

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#include <unistd.h>

#include "../crash_frames.h"

namespace {

int failures = 0;

void check(const std::string &case_name, const std::string &got, const std::string &want) {
  if (got == want) {
    std::printf("  ok   %s\n", case_name.c_str());
    return;
  }
  ++failures;
  std::printf("  FAIL %s\n         want: %s\n         got:  %s\n", case_name.c_str(), want.c_str(), got.c_str());
}

void check_true(const std::string &case_name, bool condition, const std::string &what) {
  if (condition) {
    std::printf("  ok   %s\n", case_name.c_str());
    return;
  }
  ++failures;
  std::printf("  FAIL %s: %s\n", case_name.c_str(), what.c_str());
}

std::string shown(const char *maps, size_t length, std::uintptr_t pc) {
  char out[256];
  const size_t written = crash_frames::render(maps, length, pc, out, sizeof(out));
  return std::string(out, written);
}

std::string shown(const std::string &maps, std::uintptr_t pc) {
  return shown(maps.c_str(), maps.size(), pc);
}

/** A frame inside a library mapped from partway into the file, which is how every .so looks. */
void library_frame() {
  const std::string maps =
      "77d1000000-77d10f0000 r--p 00000000 07:41 1234  /data/app/~~abc==/com.awaki-def/base.apk!/lib/arm64-v8a/libawaki-llm.so\n"
      "77d1c00000-77d1d00000 r-xp 001e0000 07:41 1234  /data/app/~~abc==/com.awaki-def/base.apk!/lib/arm64-v8a/libawaki-llm.so\n"
      "77d2000000-77d2010000 rw-p 00000000 00:00 0      [anon:.bss]\n";
  // 0x77d1c00a6c is 0xa6c into a mapping that began at file offset 0x1e0000.
  check("an executable frame names its library and adds the mapping's own file offset",
        shown(maps, 0x77d1c00a6c), "libawaki-llm.so+0x1e0a6c (0x77d1c00a6c)");
  // The read-only first mapping of the same file is still that file.
  check("a data frame of the same library names the same library",
        shown(maps, 0x77d1000010), "libawaki-llm.so+0x10 (0x77d1000010)");
  // Nothing contains this address, so the record has to say that rather than invent a file.
  check("an address no mapping holds is left unnamed",
        shown(maps, 0x1u), "0x1 (not in any mapping)");
}

/** A frame at the very edge of a mapping, where an inclusive bound would name the wrong file. */
void mapping_edges() {
  const std::string maps =
      "10000000-10001000 r-xp 00010000 07:41 7 /data/local/tmp/libfirst.so\n"
      "10001000-10002000 r-xp 00020000 07:41 8 /data/local/tmp/libsecond.so\n";
  check("the last byte of a mapping belongs to it", shown(maps, 0x10000fff), "libfirst.so+0x10fff (0x10000fff)");
  check("the first byte of the next one belongs to the next one",
        shown(maps, 0x10001000), "libsecond.so+0x20000 (0x10001000)");
}

/** The addresses a report also carries that no file owns. */
void unnamed_addresses() {
  const std::string maps =
      "80000000-80001000 ---p 00000000 00:00 0\n"
      "90000000-90001000 r-xp 00000000 07:41 9 /system/lib64/libc.so\n";
  check("a mapping with no name is said to have no file", shown(maps, 0x80000010), "0x80000010 (not in a file)");
  check("a zero address is a zero address", shown(maps, 0), "0x0");
  check("with no map at all, every frame admits it", shown("", 0x90000010), "0x90000010 (no map to read)");
  // A deleted or renamed mapping writes its real path, not the " (deleted)" the kernel appends.
  const std::string deleted =
      "a0000000-a0001000 r-xp 00005000 07:41 10 /memfd:jit-cache (deleted)\n";
  check("a name the kernel decorated is trimmed to the name",
        shown(deleted, 0xa0000010), "memfd:jit-cache+0x5010 (0xa0000010)");
}

/** Junk lines must not stop the walk, since a report that names nothing is the bug being fixed. */
void malformed_lines() {
  const std::string maps =
      "not a map line at all\n"
      "\n"
      "b0000000-b0001000 r-xp 00000000 07:41 11 /vendor/lib64/libggml-base.so\n";
  check("a frame after unparseable lines still resolves", shown(maps, 0xb0000abc), "libggml-base.so+0xabc (0xb0000abc)");
}

std::uintptr_t self_address() { return reinterpret_cast<std::uintptr_t>(&self_address); }

std::string executable_name() {
  char path[4096];
  const ssize_t length = ::readlink("/proc/self/exe", path, sizeof(path) - 1);
  if (length <= 0) return "";
  path[length] = '\0';
  const std::string full(path, static_cast<size_t>(length));
  const size_t slash = full.find_last_of('/');
  return slash == std::string::npos ? full : full.substr(slash + 1);
}

std::string read_real_maps() {
  FILE *handle = std::fopen("/proc/self/maps", "r");
  if (handle == nullptr) return "";
  std::string text;
  char line[1024];
  while (std::fgets(line, sizeof(line), handle) != nullptr) text += line;
  std::fclose(handle);
  return text;
}

/**
 * The same resolver, against this process's live map.
 *
 * The synthetic cases can only show that the rules were applied to text they were written beside.
 * This one asks where a function that is really running right now lives, and the answer has to be
 * this executable plus an offset the map's own columns agree with.
 */
void live_map() {
  const std::string maps = read_real_maps();
  if (maps.empty()) {
    std::printf("  skip live map: /proc/self/maps could not be read\n");
    return;
  }
  const std::string name = executable_name();
  const std::uintptr_t pc = self_address();
  const std::string frame = shown(maps, pc);
  check_true("a running function resolves inside this executable", frame.rfind(name + "+", 0) == 0,
             frame + " should start with " + name + "+0x...");

  // The offset must be the address's distance from the mapping start plus that mapping's file
  // offset - recomputed here from the map's own text, independently of the parser under test.
  size_t line = 0;
  bool verified = false;
  while (line < maps.size()) {
    const size_t end = maps.find('\n', line);
    const size_t limit = end == std::string::npos ? maps.size() : end;
    std::uintptr_t start = 0;
    std::uintptr_t past = 0;
    std::uintptr_t file_offset = 0;
    if (std::sscanf(maps.c_str() + line, "%lx-%lx %*4s %lx", &start, &past, &file_offset) == 3 &&
        pc >= start && pc < past) {
      const std::uintptr_t expected = pc - start + file_offset;
      char spelled[32];
      std::snprintf(spelled, sizeof(spelled), "+0x%lx", static_cast<unsigned long>(expected));
      check_true("the reported offset is the map's own arithmetic", frame.find(spelled) != std::string::npos,
                 std::string("expected ") + spelled + " in " + frame);
      verified = true;
      break;
    }
    line = limit + 1;
  }
  check_true("the executable's mapping was found in the live map", verified, "no line held the address");

  // A second library proves the name is read per frame rather than assumed from the first line.
  const std::uintptr_t libc_pc = reinterpret_cast<std::uintptr_t>(&malloc);
  const std::string libc_frame = shown(maps, libc_pc);
  check_true("a library call resolves to the library it came from",
             libc_frame.find("libc") != std::string::npos, libc_frame);
}

}  // namespace

int main() {
  std::printf("crash_frames: resolving an address against the process map\n");
  library_frame();
  mapping_edges();
  unnamed_addresses();
  malformed_lines();
  live_map();
  if (failures != 0) {
    std::printf("%d check(s) failed\n", failures);
    return 1;
  }
  std::printf("all checks passed\n");
  return 0;
}
