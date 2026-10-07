# Awaki local model runtime

The Python tree that runs GGUF models inside the PRoot Ubuntu guest, assembled here and shipped
**inside the APK**. `tools/local-runtime/build-awaki-runtime.sh` assembles it; the finished
archive and its catalog live at `app/src/main/assets/local-runtime/`. Building is a local, cached,
minutes-not-hours job — CI does not compile anything, because CI does not need to.

## Why this exists

PyPI publishes **no wheels at all** for `llama-cpp-python` — every release is an sdist — so the
naive install means compiling llama.cpp from source. The app used to do exactly that on the phone
(`apt-get install build-essential cmake`, then `pip install llama-cpp-python`), which on a 3.6 GB
device that also has to hold a model in memory is how the screen froze.

Upstream does however publish a **manylinux wheel per architecture** as a GitHub release asset
(`abetlen/llama-cpp-python`, also served as a PEP-503 index at
`https://abetlen.github.io/llama-cpp-python/whl/cpu/`), and python-build-standalone publishes a
relocatable CPython. So this script downloads, unpacks and trims. **Nothing is compiled, on any
machine, ever** — which also means nothing needs a machine of the target's architecture: an x86_64
laptop produces the arm64 bundle from arm64 wheels.

The phone's entire setup is a SHA-256 check and a `tar` extract: no pip, no compiler, no network,
no `apt-get` in the guest.

## What comes out

| File | What it is |
|---|---|
| `dist/awaki-runtime-<arch>.pack` | the runtime tree: a gzip'd tar holding `./bin/python`, `./lib/python3.12/`, `./manifest.json` |
| `dist/bundles-<arch>.json` | the catalog (`schema: 1`, one entry per ABI) the app reads from its assets |

The shipping arm64 bundle is 57.4 MiB compressed, 177 MiB unpacked across 2,814 members.

**The archive is gzip but its file name does not say so, and that is not an accident.** Android's
asset pipeline treats a `.gz` asset as compressed input: `mergeDebugAssets` inflated it and
republished it under the stripped name, so `assets/local-runtime/awaki-runtime-aarch64.tar.gz`
arrived in the APK as a 187 MB `awaki-runtime-aarch64.tar`. The catalog then named an asset the
build does not contain, and hashed bytes the build does not carry. `.pack` passes through untouched
— the app opens whatever name `bundles.json` gives it and inflates it itself, so the extension is
only there to avoid colliding with the pipeline. (`.bin` would be a poor choice too: the packaging
guard that keeps model weights out of the APK lists `bin` as a weight extension, and the runtime is
not a model.)

The archive is reproducible — pinned versions, `--sort=name`, `--mtime='UTC 2000-01-01'`,
`--owner=0 --group=0 --numeric-owner`, `--dereference`, pax records with
`delete=atime,delete=ctime`, `--use-compress-program='gzip -9 -n'`, `LC_ALL=C`, plus byte-code
compiled with `-s <build path> -p /root/local-models/runtime -f --invalidation-mode
unchecked-hash` — so rebuilding the same pins on a different machine gives the same digest and no
device re-unpacks anything. It is gzip because that is all the guest can read: no `zstd`, no `xz`,
and the app unpacks with `GZIPInputStream` + `TarArchiveInputStream`.

The tree that lands in the archive:

```
./                                   the archive root; the app unpacks it as local-models/runtime
├── manifest.json                    runtime, arch, libc, python, llamaCpp, bundleId, wheel digest
├── bin/python                       a real COPY of the interpreter, never a symlink
├── bin/python3.12
├── lib/                             the interpreter's own libraries, and nothing the guest owns
│   └── python3.12/
│       ├── <stdlib + __pycache__>
│       └── site-packages/           llama_cpp, numpy, psutil, jinja2, markupsafe,
│                                    diskcache, typing_extensions
│                                    (+ llama_cpp_python.libs/, numpy.libs/: the auditwheel
│                                     copies their RPATHs point at)
```

## Running it

Any amd64 or arm64 Linux (a WSL Ubuntu 24.04 shell is what this was built in). No root, and no
package is installed outside the tree being packaged — the wheels are downloaded with
`pip install --target`, which needs `--target` precisely because it is not installing into this
machine. It needs `curl tar gzip sha256sum readelf python3` (with `pip`), and
`qemu-user-static` if the build machine cannot run the target natively. Invoke it with `bash`,
since the executable bit does not survive every checkout on this project.

Produce the shipping artifact and install it where the APK picks it up:

```sh
bash tools/local-runtime/build-awaki-runtime.sh --target aarch64 --install-into-repo
```

`--install-into-repo` copies the archive to `app/src/main/assets/local-runtime/` and **merges** its
entry into `bundles.json` rather than clobbering the file, so building one ABI never erases another:

```
app/src/main/assets/local-runtime/awaki-runtime-aarch64.pack
app/src/main/assets/local-runtime/bundles.json
```

The other switches:

```sh
bash tools/local-runtime/build-awaki-runtime.sh --target x86_64             # the recipe, on this CPU
bash tools/local-runtime/build-awaki-runtime.sh --verify-archive FOO.pack     # re-run the gate
bash tools/local-runtime/build-awaki-runtime.sh --check-catalog bundles.json # validate the catalog
```

`--verify-archive` re-extracts a finished archive and re-runs the acceptance self-check against the
relocated tree, so a truncated or misplaced asset cannot reach a release. It takes the architecture
from the file name, and it **refuses to run on a machine that cannot execute that architecture** —
verification that never ran the interpreter is a file listing, and would pass. `AWAKI_SKIP_SELFCHECK=1`
says you meant the static checks only.

## Why CI does not build this

The bundle is produced locally and committed, and `.github/workflows/local-runtime.yml` only
verifies what is already in the repository: it runs `--check-catalog` and `--verify-archive` against
the committed asset on a plain `ubuntu-latest` runner with `qemu-user-static`. Building it on every
push would spend minutes re-downloading wheels to reproduce bytes that are already in git, and
producing them needs no special hardware any more.

The one time CI earns its keep on this is a reproducibility check, which is a manual dispatch:

```sh
gh workflow run local-runtime.yml -f arch=aarch64 --ref main
```

It rebuilds the bundle from the pins and fails if the digest differs from the committed one — the
way to catch a pin that floats or a byte-code path that stopped being reproducible.

## Proving an arm64 tree on a machine that is not arm64

The architecture decides the *proof*, not the build. When the bundle cannot run natively the script
executes it under `qemu-aarch64-static` with `-L` pointed at a sysroot it builds by extracting
`app/src/main/jniLibs/arm64-v8a/librootfs64.so` — **the same rootfs archive the app unpacks on the
device**. The check therefore runs against the phone's own glibc, OpenSSL and `libstdc++`, not the
build machine's, which is exactly the environment the app cannot test before shipping.

A native build machine skips the emulator and runs the tree directly. If neither is possible the
build warns and still static-checks everything, but says so plainly: nothing was executed.

## The gate every build has to pass

Nothing is packaged that a phone could not run. Before the archive is written — and again from the
packed bytes with `--verify-archive` — the tree is copied to a second path, run from there under
`env -i` with only `LD_LIBRARY_PATH` pointing at its own `lib`, and it must:

- import `llama_cpp`, `numpy`, `jinja2`, `psutil`, `diskcache`, `typing_extensions` and
  `from llama_cpp import Llama`, and print `llama_print_system_info()`;
- report a `sys.prefix` **and** a `sys.executable` inside the relocated bundle, and `bin/python`
  must not be a symlink (the tree being relocatable is the only thing the phone cares about);
- **fail** to import `pip`, `setuptools`, `wheel`, `ensurepip`, `idlelib`, `tkinter`, `test`,
  `dbm`.

Two static gates run beside it:

- **Every `*.so*` in the tree, and the interpreter, are read with `readelf`** and each `NEEDED`
  soname must resolve — through `runtime/lib`, the referrer's own `$ORIGIN` paths, or an
  auditwheel `.libs` directory. An unresolvable soname kills the build. So does a `runtime/lib`
  entry the guest *already* provides: shadowing the rootfs's libc or `libstdc++` is how a bundle
  built on one machine dies on another.
- **Every ELF in the tree must report the target's machine type** (`readelf -h`). A stray x86_64
  `.so` inside an arm64 bundle is a packaging bug that reads as `Exec format error` on the phone,
  and this catches it instead.

A build that fails any of it exits non-zero before producing an archive.

## Changing a pin

The constants are at the top of the script: `PBS_RELEASE`, `PBS_PY_VERSION`, `LLAMA_CPP_PYTHON`,
`NUMPY`, `PSUTIL`, `MARKUPSAFE`, `JINJA2`, `DISKCACHE`, `TYPING_EXTENSIONS`, and
`RUNTIME_REVISION`. Each target's interpreter digest is in `PBS_SHA256`, its wheel name and digest
in `LLAMA_WHEEL`, and its manylinux asset is under
`https://github.com/abetlen/llama-cpp-python/releases/tag/v<version>`.

To move a version: change the pin, download the new asset once, take its digest from `sha256sum`
(for the interpreter, read the release's `SHA256SUMS` — the build cross-checks `PBS_SHA256` against
it and stops if they disagree), put both in the map, and **bump `RUNTIME_REVISION`** — that is what
makes `bundleId` change, which is how an installed phone learns its runtime is stale. Never float a
pin: the archive digest is the runtime's identity on every device, so an unpinned rebuild silently
re-installs itself on every phone.

Re-read the `system_info` line the self-check prints whenever `LLAMA_CPP_PYTHON` moves. Today it
says `NEON = 1 | ARM_FMA = 1 | LLAMAFILE = 1 | OPENMP = 1 | REPACK = 1`, which is baseline armv8-a
plus the NEON everyone arm64 Android ships with: a wheel additionally reporting `DOTPROD` or `I8MM`
is tuned for newer cores and answers SIGILL on an older phone instead of an error message.
That is the same trap `-march=native` used to be when this script compiled the library itself.

## Targets, ABIs and armv7

| `--target` | interpreter triple | Android ABI | shipped? |
|---|---|---|---|
| `aarch64` | `aarch64-unknown-linux-gnu` | `arm64-v8a` | yes |
| `x86_64` | `x86_64-unknown-linux-gnu` | `x86_64` | never — recipe validation |

One bundle per ABI: the catalog keys on `abi` (`Build.SUPPORTED_ABIS`), so an arm64 phone never
receives another ABI's tree and several entries can coexist in one `bundles.json`.

**armv7 is not supported, and there is no plan to add it by hand:** upstream publishes no
`armeabi-v7a` wheel for `llama-cpp-python`, so a 32-bit bundle would mean compiling it — the thing
this script exists to avoid. The app's guest rootfs does carry a 32-bit build, so a 32-bit device
gets a clear "no runtime for this ABI" state rather than a broken one. If 32-bit devices ever
matter, the answer is a 32-bit device with an owner, not an armhf wheel invented here.

## Contract with the app

The app reads `assets/local-runtime/bundles.json`, picks the entry for its ABI list, verifies the
archive's SHA-256 as it streams it, and unpacks it to `filesDir/local-models/runtime`
(`com.awaki.local.LocalModelPaths`), which the guest mounts as `/root/local-models/runtime`.

- **The asset is named for what the pipeline does to it**: `awaki-runtime-<arch>.pack`, and the
  catalog's `asset` field carries exactly that path. `ShippedRuntimeBundleTest` opens the asset the
  shipped catalog names and hashes the bytes, so a bundle that reaches the APK under a different
  name — or a rebuilt archive beside an old digest — fails a gate instead of a device.
- **Archive entries are rooted at the runtime tree**: `./bin/python`, `./manifest.json`,
  `./lib/python3.12/…`. The app unpacks each entry relative to `local-models/runtime` itself, so a
  top-level `runtime/` wrapper would land as `runtime/runtime` and the interpreter would never be
  found. `create_archive` reads the packed listing back and refuses such an archive, and
  `--verify-archive` requires `bin/` at the root.
- **The archive contains no symlinks at all** — `bin/python` is a real copy of the interpreter, and
  `--dereference` flattens the versioned links beside it. The app's entry-by-entry extractor treats
  symlinks as best-effort, and a skipped link reads as a missing runtime forever.
- **`runtime/lib` holds the interpreter's libraries and nothing the guest already owns.** The loader
  and libc come from the rootfs; glibc's compatibility stubs (`libpthread.so.0`, `libdl.so.2`,
  `librt.so.1`, `libutil.so.1`) are in it, as are `libz`, `libssl`, `libcrypto`, `libstdc++` and
  `libgcc_s` — each one confirmed by extracting `librootfs64.so` and finding the file inside, and
  re-checked by the dependency gate so the bundle can never shadow them. OpenMP is the exception
  the other way: the guest has no `libgomp.so.1`, and none is needed, because the wheels vendor
  hashed copies (`libgomp-d22c30c5.so.1.0.0` under `llama_cpp_python.libs/`) that their own RPATH
  resolves. `LD_LIBRARY_PATH=/root/local-models/runtime/lib` is what makes the rest findable.
- **`pip` is gone and unimportable.** Anything that could change the runtime from the phone is
  removed on purpose, not by omission — `pip`, `setuptools`, `wheel`, `distutils_hack`, the
  `bin/`/`include/`/`lib64/`/`share/` a wheel carries for a build rather than for a run, and the
  duplicated versioned libraries the wheel unpacks where an install would symlink.
- **Bytecode ships** (`__pycache__` kept, `unchecked-hash` invalidation) so the first model load
  does not compile the standard library, and stays valid after an extract that rewrites mtimes.
  It is compiled with `-s/-p` so a `.pyc` on any machine names `/root/local-models/runtime/…` —
  the path a traceback on a device will actually show — and `-f` because `python -m compileall`
  writes byte-code for its own import closure before it starts walking, which without forcing would
  then be skipped as already current and baked with the build machine's path.
- Nothing marks a runtime ready until the guest has run it: `PythonRuntime` writes
  `.awaki-runtime-ready` with the archive digest only after its own import check succeeds.

## Rebuilding on a phone that already has one

The device compares the digest in `bundles.json` with the marker file. Move a pin, rebuild, and
`--install-into-repo` changes `bundleId`; the next open of the local-models screen unpacks the new
tree over the old one. There is no download and no build there either way.
