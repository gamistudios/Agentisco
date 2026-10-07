"""Fail unless every PT_LOAD in these ELF files is aligned to at least 16 KB.

The page size Android 15 targets is 16 KB, and a library linked for 4 KB pages cannot be
loaded by such a device at all. readelf's column layout is not stable across the two
implementations a runner may have, so the program headers are read from the file.

A file under lib/ that is not an ELF library at all is a data blob shipped with a `.so`
name to keep it out of the asset pipeline's compression, and is reported as skipped: it
is never mapped, so it has no page alignment to get wrong.
"""
import struct
import sys

MIN_ALIGN = 16384
PT_LOAD = 1

checked = 0
failed = False
for path in sys.argv[1:]:
    with open(path, "rb") as handle:
        data = handle.read(64)
    if data[:4] != b"\x7fELF" or data[4] != 2:
        print(f"{path}: not an ELF library, skipped")
        continue
    with open(path, "rb") as handle:
        data = handle.read()
    fmt = "<" if data[5] == 1 else ">"
    phoff = struct.unpack_from(fmt + "Q", data, 0x20)[0]
    phentsize = struct.unpack_from(fmt + "H", data, 0x36)[0]
    phnum = struct.unpack_from(fmt + "H", data, 0x38)[0]
    loads = []
    for index in range(phnum):
        base = phoff + index * phentsize
        p_type = struct.unpack_from(fmt + "I", data, base)[0]
        p_align = struct.unpack_from(fmt + "Q", data, base + 48)[0]
        if p_type == PT_LOAD:
            loads.append(p_align)
    checked += 1
    if not loads:
        print(f"::error::{path} has no LOAD segment")
        failed = True
    elif min(loads) < MIN_ALIGN:
        print(f"::error::{path} LOAD alignment {min(loads)} is under {MIN_ALIGN}")
        failed = True
    else:
        print(f"{path}: LOAD alignment {min(loads)}")

if checked == 0:
    print("::error::no ELF libraries were given to check")
    failed = True

sys.exit(1 if failed else 0)
