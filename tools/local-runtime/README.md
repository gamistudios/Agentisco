# Awaki local model runtime

The Python tree that runs GGUF models inside the PRoot Ubuntu guest, built here and shipped
**inside the APK**. `tools/local-runtime/build-awaki-runtime.sh` assembles it, and
`.github/workflows/local-runtime.yml` runs that script on a native ARM64 runner.

## Why this exists

PyPI publishes **no wheels at all** for `llama-cpp-python` — every release is an sdist — so
installing it means compiling llama.cpp from source. The app used to do exactly that on the
phone (`apt-get install build-essential cmake` and then `pip install llama-cpp-python`), which
on a 3.6 GB device that also has to hold a model in memory is how the screen froze.

Now the compile happens once, on a machine that can afford it, and the phone's entire setup is a
SHA-256 check and a `tar` extract: no pip, no compiler, no network, no `apt-get` in the guest.

## What comes out

| File | What it is |
|---|---|
| `dist/awaki-runtime-<arch>.tar.gz` | the runtime tree: `runtime/bin/python`, `runtime/lib/python3.12/`, `runtime/manifest.json` |
| `dist/bundles-<arch>.json` | the catalog (`schema: 1`, one entry per ABI) the app reads from its assets |

The archive is reproducible — pinned versions, `--sort=name`, `--mtime='UTC 2000-01-01'`,
`--owner=0 --group=0 --numeric-owner`, `GZIP=-9 -n`, `LC_ALL=C` — so rebuilding the same pins
gives the same digest and no device re-unpacks anything. It is gzip because that is all the guest
can read: no `zstd`, no `xz`, and the app unpacks with `GZIPInputStream` + `TarArchiveInputStream`.

The tree that lands in the archive:

```
runtime/
├── manifest.json                    runtime, arch, libc, python, llamaCpp, bundleId
├── bin/python                       a real COPY of the interpreter, never a symlink
├── bin/python3.12
├── lib/                             the interpreter's libraries + the ones the guest lacks
│   └── python3.12/
│       ├── <stdlib + __pycache__>
│       └── site-packages/           llama_cpp, numpy, psutil, jinja2, markupsafe,
│                                    diskcache, typing_extensions
```

## Running it

Needs a native Linux of the target's own architecture (the compiler decides the bundle's
architecture), plus a C/C++ toolchain and cmake. No root, and nothing is installed outside the
tree being packaged — the bundled interpreter runs pip itself. Invoke it with `bash`, since the
executable bit does not survive every checkout on this project.

Validate the recipe locally on x86_64 (a WSL Ubuntu 24.04 shell is enough; ~10 minutes, and the
`aarch64` phone cannot run what it produces — it exists to prove the recipe):

```sh
bash tools/local-runtime/build-awaki-runtime.sh --target x86_64
```

Produce the shipping artifact on a native ARM64 Linux box (what CI does):

```sh
bash tools/local-runtime/build-awaki-runtime.sh --target aarch64 --output-dir dist
bash tools/local-runtime/build-awaki-runtime.sh --verify-archive dist/awaki-runtime-aarch64.tar.gz
```

Then put both files where the APK will pick them up — `--install-into-repo` does exactly this,
including merging the catalog instead of clobbering the other ABI's entry:

```sh
bash tools/local-runtime/build-awaki-runtime.sh --target aarch64 --install-into-repo
```

```
app/src/main/assets/local-runtime/awaki-runtime-aarch64.tar.gz
app/src/main/assets/local-runtime/bundles.json
```

`--verify-archive` re-extracts a finished archive and re-runs the acceptance self-check against
the relocated tree; CI runs it as a separate step so a truncated asset cannot reach a release.

## The gate every build has to pass

Nothing is packaged that a phone could not run. Before the archive is written — and again from
the packed bytes in CI — the tree is copied to a second path, run from there under `env -i` with
only `LD_LIBRARY_PATH` pointing at its own `lib`, and it must:

- import `llama_cpp`, `numpy`, `jinja2`, `psutil`, `diskcache`, `typing_extensions` and
  `from llama_cpp import Llama`, and print `llama_print_system_info()`;
- report a `sys.prefix` inside the relocated bundle (so the tree is relocatable, which is the
  only thing the phone cares about);
- **fail** to import `pip`, `setuptools`, `wheel`, `ensurepip`, `idlelib`, `tkinter`, `test`,
  `dbm`.

A build that fails any of it exits non-zero before producing an archive.

## Changing a pin

The constants are at the top of the script: `PBS_RELEASE`, `PBS_PY_VERSION`, `LLAMA_CPP_PYTHON`,
`NUMPY`, `PSUTIL`, `MARKUPSAFE`, `JINJA2`, `DISKCACHE`, `TYPING_EXTENSIONS`, and
`RUNTIME_REVISION`. Each target's interpreter digest is in `PBS_SHA256`.

To move a version: change the pin, read the new archive's digest from the release's `SHA256SUMS`
(the build cross-checks `PBS_SHA256` against it and stops if they disagree), put it in the map,
and **bump `RUNTIME_REVISION`** — that is what makes `bundleId` change, which is how an installed
phone learns its runtime is stale. Never float a pin: the archive digest is the runtime's
identity on every device, so an unpinned rebuild silently re-installs itself on every phone.

`CMAKE_CONFIGURE_ARGS` deserves the same care. `GGML_NATIVE=OFF` is not negotiable:
`-march=native` bakes the build machine's instruction set into the bundle, and the next phone
answers SIGILL on the first decode instead of an error message.

## Targets, ABIs and armv7

| `--target` | interpreter triple | Android ABI | shipped? |
|---|---|---|---|
| `aarch64` | `aarch64-unknown-linux-gnu` | `arm64-v8a` | yes |
| `armv7` | `armv7-unknown-linux-gnueabihf` | `armeabi-v7a` | only if 32-bit devices are targeted |
| `x86_64` | `x86_64-unknown-linux-gnu` | `x86_64` | never — recipe validation |

One bundle per ABI: an arm64 phone must not receive the armv7 tree, and the catalog keys on `abi`
(`Build.SUPPORTED_ABIS`), so `arm64-v8a` and `armeabi-v7a` coexist as entries in one
`bundles.json`. `--install-into-repo` replaces the entry with its own `arch` and leaves the
others alone, which is why building armv7 after arm64 does not erase the arm64 runtime.

armhf means the **hard-float** triple `armv7-unknown-linux-gnueabihf`; the soft-float `gnueabi`
build is a different ABI and will not load against a device's libc. And because the tree is
compiled natively, an aarch64 runner cannot produce it: the script refuses a mismatched host
instead of shipping a bundle that crashes, so an armv7 bundle needs an armv7 host
(`uname -m` → `armv7l`) — CI's `arch=armv7` dispatch says so and stops.

## Contract with the app

The app reads `assets/local-runtime/bundles.json`, picks the entry for its ABI list, verifies the
archive's SHA-256 as it streams it, and unpacks it to `filesDir/local-models/runtime`
(`com.awaki.local.LocalModelPaths`), which the guest mounts as `/root/local-models/runtime`.

- **Archive entries are under a top-level `runtime/` directory.** An installer that unpacks into
  the runtime directory itself should pass `--flat-archive`, which packs the identical tree at the
  archive root (`./bin/python`) instead. Decide which one the app wants and commit the matching
  bytes; `--verify-archive` accepts either.
- **`bin/python` is a real file, not a symlink** — the app's entry-by-entry extractor treats
  symlinks as best-effort, and a skipped link reads as a missing runtime forever.
- **Libraries the guest lacks live in `runtime/lib`** — `libgomp.so.1` (OpenMP), plus glibc's
  compatibility stubs the interpreter is linked against (`libpthread.so.0`, `libdl.so.2`,
  `librt.so.1`, `libutil.so.1`) if the rootfs does not carry them. Never a second `libc`: the
  loader and libc come from the guest, and `LD_LIBRARY_PATH=/root/local-models/runtime/lib` is
  what makes the rest findable.
- **`pip` is gone and unimportable.** Anything that could compile on the phone is removed on
  purpose, not by omission.
- **Bytecode ships** (`__pycache__` kept, `unchecked-hash` invalidation) so the first model load
  does not compile the standard library, and stays valid after an extract that rewrites mtimes.
- Nothing marks a runtime ready until the guest has run it: `PythonRuntime` writes
  `.awaki-runtime-ready` with the archive digest only after its own import check succeeds.
