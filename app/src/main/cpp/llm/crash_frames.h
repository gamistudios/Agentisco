// Which mapped file an address came from, spelled out of /proc/self/maps.
//
// The engine's crash handler needs this and cannot use the obvious library call for it: `dladdr`
// takes the loader's lock, and the thread that just faulted is often the one holding it. The
// kernel's own map list answers the same question with open/read/close, which are all safe to call
// while a process is dying.
//
// It lives here rather than inside crash_jni.cpp because a resolution rule that is only reachable
// from a signal handler is a rule nobody has ever seen run: the parsing below is plain code over a
// buffer, so tests/crash_frames_test.cpp exercises it on a desk with a synthetic map.

#pragma once

#include <cstddef>
#include <cstdint>

namespace crash_frames {

struct ParsedNumber {
  std::uintptr_t value = 0;
  size_t next = 0;
  bool ok = false;
};

/** Hex without `strtoull`, which is not on the list of calls safe to make while dying. */
inline ParsedNumber parse_hex(const char *text, size_t length, size_t index) {
  ParsedNumber out;
  out.next = index;
  while (index < length) {
    const char c = text[index];
    int digit = -1;
    if (c >= '0' && c <= '9') digit = c - '0';
    else if (c >= 'a' && c <= 'f') digit = c - 'a' + 10;
    else if (c >= 'A' && c <= 'F') digit = c - 'A' + 10;
    if (digit < 0) break;
    out.value = out.value * 16u + static_cast<std::uintptr_t>(digit);
    out.ok = true;
    out.next = ++index;
  }
  return out;
}

inline size_t skip_spaces(const char *text, size_t limit, size_t index) {
  while (index < limit && (text[index] == ' ' || text[index] == '\t')) ++index;
  return index;
}

/** Past the field starting at [index] and the spaces after it, which is how a map line is walked. */
inline size_t next_field(const char *text, size_t limit, size_t index) {
  while (index < limit && text[index] != ' ' && text[index] != '\t') ++index;
  return skip_spaces(text, limit, index);
}

/** Where a program counter came from: which mapped file, and how far into it. */
struct Location {
  const char *path = nullptr;
  size_t path_length = 0;
  std::uintptr_t offset = 0;
  /** An address inside a mapping that names no file (the heap, a vdso, anonymous code). */
  bool anonymous = false;
};

/**
 * The line of [maps] that contains [pc].
 *
 * The offset is `pc - mapping start + the file offset that mapping was made at`, which is the
 * number `llvm-addr2line` and `ndk-stack` read against the built .so: the load address cancels
 * out, so a record from a phone can be symbolized on a desk with nothing but the library.
 */
inline Location locate(const char *maps, size_t maps_length, std::uintptr_t pc) {
  Location found;
  size_t line = 0;
  while (line < maps_length) {
    size_t end = line;
    while (end < maps_length && maps[end] != '\n') ++end;

    const ParsedNumber start = parse_hex(maps, end, line);
    const bool dashed = start.ok && start.next < end && maps[start.next] == '-';
    const ParsedNumber limit = dashed ? parse_hex(maps, end, start.next + 1) : ParsedNumber{};
    if (dashed && limit.ok && pc >= start.value && pc < limit.value) {
      size_t index = next_field(maps, end, skip_spaces(maps, end, limit.next));  // past rwxp
      const ParsedNumber file_offset = parse_hex(maps, end, index);
      index = next_field(maps, end, next_field(maps, end, file_offset.next));     // past dev
      const size_t path_start = next_field(maps, end, index);                     // past inode

      found.offset = pc - start.value + (file_offset.ok ? file_offset.value : 0);
      found.anonymous = path_start >= end;
      if (!found.anonymous) {
        size_t path_end = path_start;
        while (path_end < end && maps[path_end] != ' ' && maps[path_end] != '\t') ++path_end;
        found.path = maps + path_start;
        found.path_length = path_end - path_start;
      }
      return found;
    }
    line = end + 1;
  }
  return found;
}

/**
 * One address, named if it could be: `libawaki-llm.so+0x1f0a6c`.
 *
 * A frame with a file and an offset is actionable; a bare `0x77d1c2f0ac` from an address space
 * nobody else can see is a guess, which is what every report from this engine used to be. The
 * absolute address stays in parentheses because a frame with no file behind it - the stack, a
 * thunk, jit - still has to be written down somehow.
 *
 * Returns the length written, which is the input length capped at [capacity] - 1 so the buffer is
 * always a terminated string.
 */
inline size_t render(const char *maps, size_t maps_length, std::uintptr_t pc, char *out, size_t capacity) {
  if (capacity == 0) return 0;
  size_t length = 0;
  auto put = [&](const char *value, size_t value_length) {
    for (size_t i = 0; i < value_length && length + 1 < capacity; ++i) out[length++] = value[i];
  };
  auto put_text = [&](const char *value) {
    while (*value != '\0' && length + 1 < capacity) out[length++] = *value++;
  };
  auto hex = [&](std::uintptr_t value) {
    static constexpr char digits[] = "0123456789abcdef";
    put_text("0x");
    if (value == 0) {
      put_text("0");
      return;
    }
    char spelled[17];
    size_t count = 0;
    while (value != 0 && count < sizeof(spelled)) {
      spelled[count++] = digits[value & 0xF];
      value >>= 4;
    }
    while (count > 0 && length + 1 < capacity) out[length++] = spelled[--count];
  };

  if (pc == 0) {
    hex(0);
    out[length] = '\0';
    return length;
  }

  const Location where = locate(maps, maps_length, pc);
  if (where.path != nullptr && where.path_length > 0) {
    size_t base = 0;
    for (size_t i = 0; i + 1 < where.path_length; ++i) {
      if (where.path[i] == '/') base = i + 1;
    }
    put(where.path + base, where.path_length - base);
    put_text("+");
    hex(where.offset);
    put_text(" (");
    hex(pc);
    put_text(")");
    out[length] = '\0';
    return length;
  }

  hex(pc);
  // An address that names no file still says why, because "there was no map to read" and "this
  // address is not in any map" are different failures and only one of them is this code's.
  if (where.anonymous) put_text(" (not in a file)");
  else if (maps_length == 0) put_text(" (no map to read)");
  else put_text(" (not in any mapping)");
  out[length] = '\0';
  return length;
}

}  // namespace crash_frames
