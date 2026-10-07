#!/usr/bin/env bash
# build-awaki-runtime.sh — assemble the prebuilt Python runtime that Awaki ships inside the APK.
#
# WHAT IT PRODUCES
#   <output-dir>/awaki-runtime-<arch>.pack     the runtime tree: a gzip'd tar, reproducible
#                                             byte-for-byte, named so the asset pipeline leaves it
#                                             alone (see ASSET_NAME below)
#   <output-dir>/bundles-<arch>.json          the catalog the app reads (schema 1)
# With --install-into-repo the same two files also land at
#   app/src/main/assets/local-runtime/awaki-runtime-<arch>.pack
#   app/src/main/assets/local-runtime/bundles.json   (this arch's entry replaced, others kept)
#
# THE ARCHIVE CONTRACT
#   The tree IS the archive root, so its first entries are ./manifest.json, ./bin/python,
#   ./lib/python3.12/site-packages/... The app unpacks those bytes straight into
#   /root/local-models/runtime and runs /root/local-models/runtime/bin/python — a real file,
#   never a symlink — with LD_LIBRARY_PATH=/root/local-models/runtime/lib, and nothing else is
#   required of it. A top-level runtime/ directory inside the archive would land as
#   runtime/runtime on the phone, and the interpreter check would fail forever.
#   The container has to be gzip: the guest has no zstd and no xz binary, and the app reads the
#   archive with GZIPInputStream plus TarArchiveInputStream.
#
# WHY IT EXISTS
#   A 3.6 GB phone that also has to hold a model in memory cannot compile llama.cpp, and it
#   certainly should not: compiling on the device is what used to freeze the screen. So the
#   native libraries come as bytes instead. Upstream publishes a manylinux wheel per architecture
#   as a GitHub release asset (PyPI carries only the sdist), python-build-standalone publishes a
#   relocatable interpreter, and this script only ever downloads, unpacks and trims.
#
#   Nothing here compiles, so nothing here needs a machine of the target's architecture. An
#   x86_64 laptop produces the arm64 bundle from arm64 wheels, and the one thing the architecture
#   still decides is how the result is *proven*: a tree that runs on the build machine is run
#   there, and a tree that cannot be is run under qemu-user inside the very rootfs archive the
#   app unpacks on the device. What the phone does afterwards is one checksum and one tar
#   extract: no pip, no compiler, no network. tools/local-runtime/README.md covers the pins.
#
# USAGE
#   bash tools/local-runtime/build-awaki-runtime.sh                    # build for the host arch
#   bash tools/local-runtime/build-awaki-runtime.sh --target aarch64   # the shipping artifact
#   bash tools/local-runtime/build-awaki-runtime.sh --help
#
# No root is needed. The machine needs curl, tar, gzip, sha256sum, readelf, a python3 with pip
# (only to run the installer's own resolver - no package of this host is touched) and, to execute
# a foreign-architecture tree, qemu-user-static.
#
# Environment:
#   AWAKI_DOWNLOAD_CACHE  interpreter download cache (default ~/.cache/awaki/local-runtime)
#   KEEP_WORK=1           leave the scratch tree behind for inspection

set -euo pipefail

# ---------- pinned inputs -------------------------------------------------------
# Bumping any of these means rebuilding every bundle; RUNTIME_REVISION is how a device learns
# that the runtime it holds is no longer the one the catalog describes.
PBS_RELEASE="20261003"
PBS_PY_VERSION="3.12.15"
PY_SERIES="3.12"
GUEST_LIBC="glibc-2.39"
RUNTIME_REVISION="r2"

# Where the tree this script assembles lives on the phone: `LocalModelPaths.RUNTIME_GUEST_DIR`.
# Byte-code is compiled as if the tree were already there, so a traceback on a device names the file
# it actually ran and two machines agree on every .pyc they write.
GUEST_RUNTIME_DIR="/root/local-models/runtime"

LLAMA_CPP_PYTHON="0.3.36"
NUMPY="2.5.3"
PSUTIL="7.2.2"
MARKUPSAFE="3.0.4"
JINJA2="3.1.6"
DISKCACHE="5.6.3"
TYPING_EXTENSIONS="4.16.0"

# PyPI carries only an sdist for llama-cpp-python, but upstream publishes a manylinux wheel per
# architecture as a release asset, built in a manylinux2014 container by a compiler that is not
# the phones. Those bytes are what the runtime is assembled from, which is why this script needs
# no C toolchain and no machine of the targets own: nothing here is ever compiled.
#
# The aarch64 asset reports `NEON | ARM_FMA | OPENMP | REPACK` and nothing beyond baseline
# armv8-a, so one build serves every arm64 device. A wheel that reported DOTPROD or I8MM would
# SIGILL on an older phone, so re-read that line from `llama_print_system_info()` whenever
# LLAMA_CPP_PYTHON moves (the acceptance self-check below prints it on every build).
declare -A LLAMA_WHEEL=(
  [aarch64]="llama_cpp_python-0.3.36-py3-none-manylinux2014_aarch64.manylinux_2_17_aarch64.whl|410bdf3cf66478f55de0ad51dd6ab241424591bdd36aff086b000d155ac182c6"
  [x86_64]="llama_cpp_python-0.3.36-py3-none-manylinux2014_x86_64.manylinux_2_17_x86_64.whl|c8a3e98093e950d7a770f794842e36f2db82b2933e8762ed7b5ffd435011e8e4"
)
LLAMA_WHEEL_BASE="https://github.com/abetlen/llama-cpp-python/releases/download/v${LLAMA_CPP_PYTHON}"
# The platform tags pip resolves every dependency against, so a wheel is chosen for the device's
# glibc rather than this machine's: manylinux2014 and its manylinux_2_17 spelling are the floor the
# wheels were audited against, and manylinux_2_28 is the ceiling that lets a newer-tagged asset
# (numpy's is manylinux_2_27) still match. The guest is Ubuntu 24.04, $GUEST_LIBC, far above the
# 2.17 floor these tags describe.

# Shared libraries the Ubuntu 24.04 rootfs the app ships already provides — every one of them
# confirmed by extracting `app/src/main/jniLibs/arm64-v8a/librootfs64.so` (the archive the guest is
# unpacked from) and finding the file inside it. glibc's compatibility stubs (libpthread, libdl,
# librt, libutil) are in it: the interpreter and llama.cpp's CPU backend are linked against them,
# and a build machine of a different architecture could not supply them even if they were missing,
# so the list is checked rather than assumed.
# `libgomp.so.1` is deliberately NOT here — the guest has no OpenMP runtime, and no library in the
# bundle asks for one by that name: the wheels vendor their own hashed copies
# (`libgomp-d22c30c5.so.1.0.0` under `llama_cpp_python.libs`), which their RPATH finds.
GUEST_PROVIDED_LIBS=(
  ld-linux-aarch64.so.1
  ld-linux-x86-64.so.2
  libc.so.6
  libm.so.6
  libz.so.1
  libssl.so.3
  libcrypto.so.3
  libstdc++.so.6
  libgcc_s.so.1
  libpthread.so.0
  libdl.so.2
  librt.so.1
  libutil.so.1
  libcrypt.so.1
)

# Triples are python-build-standalone asset names; the digests come from that release's
# SHA256SUMS, which the build cross-checks before trusting them.
declare -A PBS_TRIPLE=(
  [aarch64]="aarch64-unknown-linux-gnu"
  [x86_64]="x86_64-unknown-linux-gnu"
)
declare -A PBS_SHA256=(
  [aarch64]="6541297dd1798dec8b98c3ad7492808a5b9d1c126801ceb2011e7754cd20d1ce"
  [x86_64]="731af898886c5f821890dc901eca3c651cca8e51fa7308c159d12a1194aeac91"
)
declare -A TARGET_ABI=(
  [aarch64]="arm64-v8a"
  [x86_64]="x86_64"
)
# What `uname -m` reports on a machine that runs this target natively.
declare -A TARGET_UNAME_M=(
  [aarch64]="aarch64"
  [x86_64]="x86_64"
)
# On any other machine the acceptance self-check still runs, under the user-mode emulator and
# inside the very rootfs archive the app unpacks on the device. The bundle is assembled from
# prebuilt wheels, so nothing about the build needs a machine of the target's architecture -
# only the proof that it runs does, and that is what these two are for.
#
# The app ships a guest rootfs for arm64 and armv7 only, so an x86_64 bundle has no rootfs to be
# run inside: it is checked on the build machine's own libc, which is the same Ubuntu 24.04 glibc
# the arm64 rootfs carries. Its bundle is a proof of the recipe, never a shipping artifact.
declare -A TARGET_QEMU=(
  [aarch64]="qemu-aarch64-static"
)
declare -A TARGET_ROOTFS_ASSET=(
  [aarch64]="app/src/main/jniLibs/arm64-v8a/librootfs64.so"
)

# ---------- helpers -------------------------------------------------------------
log() { printf '>> %s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  local cmd
  for cmd in "$@"; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
      die "required command not found: $cmd"
    fi
  done
}

human_bytes() {
  local bytes="${1:-0}"
  if [ "$bytes" -ge 1048576 ]; then
    printf '%s.%s MB' "$((bytes / 1048576))" "$(((bytes % 1048576) * 10 / 1048576))"
  else
    printf '%s kB' "$((bytes / 1024))"
  fi
}

# The machine word readelf prints for a file, or nothing for a file that is not an ELF binary
# (a header, a .py, or one of the text stubs a wheel unpacks beside its libraries). Asking the
# bytes rather than the wheel tags is the only architecture check that works on a machine which
# cannot run what it is inspecting.
elf_machine_of() {
  readelf -h "$1" 2>/dev/null | awk -F: '/Machine/ { gsub(/^ +/, "", $2); print $2; exit }'
}

# ---------- arguments -----------------------------------------------------------
TARGET=""
OUTPUT_DIR="dist"
INSTALL_INTO_REPO=0
VERIFY_ARCHIVE=""
CHECK_CATALOG=""

usage() {
  cat <<'EOF'
Usage: bash tools/local-runtime/build-awaki-runtime.sh [options]

  --target aarch64|x86_64
                        architecture to bundle. Default: the host's own. aarch64 is the shipping
                        artifact and can be produced on any Linux machine, because every input is
                        a prebuilt wheel - nothing is compiled. x86_64 exists to run the recipe
                        end to end where it can be executed; its bundle is never shipped.
                        armv7 is not supported: upstream publishes no armv7 wheel, so a 32-bit
                        runtime would have to be compiled on 32-bit hardware.
  --output-dir DIR      where the archive and catalog are written (default: dist)
  --install-into-repo   copy the archive into app/src/main/assets/local-runtime/ and merge its
                        entry into that directory's bundles.json
  --verify-archive FILE unpack an existing archive and re-run the acceptance self-check against
                        it without building anything. The architecture comes from the file name,
                        so this is the check to run on the bytes that will ship. It refuses to run
                        on a host that cannot execute that architecture, unless
                        AWAKI_SKIP_SELFCHECK=1 is set.
  --check-catalog FILE  validate a bundles.json against the keys the app parses, building nothing
  -h, --help            this message

Environment:
  AWAKI_DOWNLOAD_CACHE  downloads cache, interpreter and wheels (default: ~/.cache/awaki/local-runtime)
  AWAKI_SKIP_SELFCHECK=1
                        assemble without executing the tree. Only for producing a bundle to test on
                        a device: an unexecuted runtime is an unverified one.
  KEEP_WORK=1           keep the scratch tree for inspection

Building the shipping artifact is a local, cached, minutes-not-hours job:
  bash tools/local-runtime/build-awaki-runtime.sh --target aarch64 --install-into-repo
To execute the arm64 tree on a machine that is not arm64, install qemu-user-static first; the
self-check then runs the bundle inside the app's own Ubuntu rootfs.

Two files have to reach the repository for the APK to carry a runtime:
  app/src/main/assets/local-runtime/awaki-runtime-<arch>.pack
  app/src/main/assets/local-runtime/bundles.json
The archive is gzip'd but not named .gz on purpose: Android's asset pipeline inflates a .gz asset
and stores it under the stripped name, which would leave the catalog pointing at bytes the build
does not contain.
EOF
}

default_target() {
  case "$(uname -m)" in
    aarch64 | arm64) printf 'aarch64' ;;
    x86_64 | amd64) printf 'x86_64' ;;
    *) die "cannot map 'uname -m' ($(uname -m)) to a supported --target; pass --target aarch64 explicitly to bundle for arm64 devices" ;;
  esac
}

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --target)
        [ $# -ge 2 ] || die "--target needs a value"
        TARGET="$2"
        shift 2
        ;;
      --target=*) TARGET="${1#*=}"; shift ;;
      --output-dir)
        [ $# -ge 2 ] || die "--output-dir needs a value"
        OUTPUT_DIR="$2"
        shift 2
        ;;
      --output-dir=*) OUTPUT_DIR="${1#*=}"; shift ;;
      --install-into-repo) INSTALL_INTO_REPO=1; shift ;;
      --verify-archive)
        [ $# -ge 2 ] || die "--verify-archive needs a path"
        VERIFY_ARCHIVE="$2"
        shift 2
        ;;
      --verify-archive=*) VERIFY_ARCHIVE="${1#*=}"; shift ;;
      --check-catalog)
        [ $# -ge 2 ] || die "--check-catalog needs a path"
        CHECK_CATALOG="$2"
        shift 2
        ;;
      --check-catalog=*) CHECK_CATALOG="${1#*=}"; shift ;;
      -h | --help)
        usage
        exit 0
        ;;
      *)
        printf 'error: unknown argument: %s\n\n' "$1" >&2
        usage >&2
        exit 1
        ;;
      esac
  done

  if [ -z "$TARGET" ] && [ -n "$VERIFY_ARCHIVE" ]; then
    # Verifying a finished archive is a question about the archive, not about this machine: an
    # x86_64 box checking dist/awaki-runtime-aarch64.pack has to plan an aarch64 check, or it
    # would try to run arm64 bytes natively and report a working bundle as broken.
    TARGET="$(basename "$VERIFY_ARCHIVE")"
    TARGET="${TARGET#awaki-runtime-}"
    TARGET="${TARGET%%.*}"
  fi
  if [ -z "$TARGET" ]; then
    # A plain invocation is always a valid native build of whatever this machine is.
    TARGET="$(default_target)"
  fi
  if [ -z "${PBS_TRIPLE[$TARGET]:-}" ]; then
    die "unknown --target '$TARGET' (expected aarch64 or x86_64)"
  fi

  TRIPLE="${PBS_TRIPLE[$TARGET]}"
  EXPECTED_SHA="${PBS_SHA256[$TARGET]}"
  ABI="${TARGET_ABI[$TARGET]}"
  BUNDLE_ID="${TARGET}-${RUNTIME_REVISION}"
  # The bytes are a gzip'd tar, but the shipped name says neither `.gz` nor `.bin`, and both absences
  # are load-bearing:
  #   - A `.gz` asset goes through Android's asset pipeline as compressed input. `mergeDebugAssets`
  #     inflates it and republishes it under the stripped name, so `awaki-runtime-aarch64.tar.gz`
  #     arrives in the APK as a 187 MB `awaki-runtime-aarch64.tar`: the catalog then names an asset
  #     the build does not contain, and hashes bytes it does not carry.
  #   - `bin` is one of the model-weight extensions the packaging guard refuses to ship inside the
  #     app, and the runtime is not a model.
  # `.pack` is opaque to both. The app opens whatever name `bundles.json` gives it and inflates the
  # bytes itself, so the extension carries no meaning beyond not colliding with either rule.
  ASSET_NAME="awaki-runtime-${TARGET}.pack"
  ASSET_PATH="local-runtime/${ASSET_NAME}"
  CATALOG_NAME="bundles-${TARGET}.json"
  PYTHON_ASSET="cpython-${PBS_PY_VERSION}+${PBS_RELEASE}-${TRIPLE}-install_only_stripped.tar.gz"
  # The '+' in the asset name has to be percent-encoded for the release download URL.
  PYTHON_URL="https://github.com/astral-sh/python-build-standalone/releases/download/${PBS_RELEASE}/${PYTHON_ASSET/+/%2B}"
  SUMS_URL="https://github.com/astral-sh/python-build-standalone/releases/download/${PBS_RELEASE}/SHA256SUMS"
  # The llama-cpp-python wheel is the target's own: name and digest come from the map together,
  # so a bundle can never pick up another architecture's libraries.
  if [ -z "${LLAMA_WHEEL[$TARGET]:-}" ]; then
    die "upstream publishes no llama-cpp-python $LLAMA_CPP_PYTHON wheel for $TARGET, so this recipe cannot assemble that architecture"
  fi
  LLAMA_ASSET="${LLAMA_WHEEL[$TARGET]%%|*}"
  LLAMA_SHA="${LLAMA_WHEEL[$TARGET]#*|}"
  LLAMA_URL="$LLAMA_WHEEL_BASE/$LLAMA_ASSET"
  # The wheel tag for the interpreter this bundle carries: 3.12 -> cp312's "312". Deriving it from
  # PY_SERIES is what makes a python bump move the resolver too, rather than installing 3.11 wheels
  # into a 3.12 tree and letting the device discover it.
  PY_TAG="${PY_SERIES//./}"
  pip_target=(
    --only-binary=:all:
    --platform "manylinux2014_$TARGET"
    --platform "manylinux_2_17_$TARGET"
    --platform "manylinux_2_28_$TARGET"
  )
  case "$TARGET" in
    aarch64) TARGET_ELF_MACHINE="AArch64" ;;
    x86_64) TARGET_ELF_MACHINE="Advanced microprocessor" ;;
  esac
  CACHE_DIR="${AWAKI_DOWNLOAD_CACHE:-${HOME:-.}/.cache/awaki/local-runtime}"
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
}

# ---------- host preflight ------------------------------------------------------
# How the assembled tree will be proven: on this machine, under the emulator inside the app's own
# rootfs, or not at all. A bundle for arm64 is assembled from arm64 bytes on any host, so the
# build itself has no architecture requirement - but "it was assembled" is not "it runs", and the
# difference is what this decides.
plan_verification() {
  local machine
  machine="$(uname -m)"
  if [ "$machine" = "${TARGET_UNAME_M[$TARGET]}" ]; then
    RUN_MODE="native"
    log "target $TARGET ($TRIPLE), abi $ABI, bundleId $BUNDLE_ID, host $machine: the tree will be run here"
    return 0
  fi
  local qemu="${TARGET_QEMU[$TARGET]:-}" rootfs="${TARGET_ROOTFS_ASSET[$TARGET]:-}"
  if [ -z "$qemu" ] || [ -z "$rootfs" ]; then
    RUN_MODE="none"
    warn "no way to execute a $TARGET tree on a $machine host: it will be assembled and statically checked, and only a device can say whether it runs"
    return 0
  fi
  if ! command -v "$qemu" >/dev/null 2>&1; then
    if [ "${AWAKI_SKIP_SELFCHECK:-0}" = "1" ]; then
      RUN_MODE="none"
      warn "$qemu is not installed, so the $TARGET tree will not be executed (AWAKI_SKIP_SELFCHECK is set)"
      return 0
    fi
    die "proving a $TARGET tree on a $machine host needs $qemu: apt-get install qemu-user-static, or pass AWAKI_SKIP_SELFCHECK=1 to build unverified"
  fi
  if [ ! -f "$REPO_ROOT/$rootfs" ]; then
    RUN_MODE="none"
    warn "$REPO_ROOT/$rootfs is not here, so the $TARGET tree cannot be run inside the guests userspace"
    return 0
  fi
  RUN_MODE="emulated"
  EMULATOR="$qemu"
  GUEST_ROOTFS="$REPO_ROOT/$rootfs"
  log "target $TARGET ($TRIPLE), abi $ABI, bundleId $BUNDLE_ID, host $machine: the tree will be run under $qemu inside $rootfs"
}

# ---------- running the tree ----------------------------------------------------
# Assembling a bundle needs no machine of the target's architecture; executing one does, and that
# is what these two decide. Under the user-mode emulator the guest's own shared tree is the sysroot,
# so the loader, libc and every system library the bundle asks for are the ones on the phone rather
# than the ones on this machine — which is the difference between "it imported here" and "it can
# import there".
prepare_sysroot() {
  [ "$RUN_MODE" = "emulated" ] || return 0
  SYSROOT="$WORK/sysroot"
  mkdir -p "$SYSROOT"
  log "unpacking the guests userspace ($(basename "$GUEST_ROOTFS")) to serve as the emulator sysroot"
  if ! tar -xzf "$GUEST_ROOTFS" -C "$SYSROOT" 2>/dev/null; then
    die "$GUEST_ROOTFS did not unpack - the guest rootfs has to be a gzip'd tar for this check to mean anything"
  fi
  if [ ! -e "$SYSROOT/lib/ld-linux-aarch64.so.1" ] &&
    [ ! -e "$SYSROOT/usr/lib/ld-linux-aarch64.so.1" ]; then
    die "the unpacked rootfs carries no aarch64 dynamic loader, so nothing in it could be run"
  fi
  log "sysroot ready ($(du -sh "$SYSROOT" | cut -f1))"
}

# Runs the bundle's interpreter against the assembled tree. Everything after the interpreter path
# is python's own argument list, so callers read like the guest will execute them. The environment
# is emptied first, because the guest gives the interpreter nothing else: PATH, the tree's own lib
# directory, and any AWAKI_* variable the caller exported for a check to read.
run_bundle_python() {
  local python="$1"
  shift
  local -a given=(PATH="/usr/bin:/bin" "LD_LIBRARY_PATH=$(dirname "$(dirname "$python")")/lib")
  if [ -n "${AWAKI_EXPECT_PREFIX:-}" ]; then
    given+=("AWAKI_EXPECT_PREFIX=$AWAKI_EXPECT_PREFIX")
  fi
  case "$RUN_MODE" in
    native)
      env -i "${given[@]}" "$python" "$@"
      ;;
    emulated)
      env -i "${given[@]}" "$EMULATOR" -L "$SYSROOT" "$python" "$@"
      ;;
    none)
      die "this build cannot execute a $TARGET tree, so it cannot run $python"
      ;;
  esac
}

can_run_bundle() { [ "$RUN_MODE" != "none" ]; }

check_build_tools() {
  require_cmd curl tar gzip sha256sum readelf find mktemp sed grep awk sort cp mkdir rm du python3
  # The host's python only ever runs pip's resolver: every file that lands in the tree comes from
  # a wheel selected for the target's platform tag, and --target writes nothing outside it. So a
  # machine with an old or externally managed python3 is still fine, as long as it has pip in it.
  if ! python3 -m pip --version >"$LOG_DIR/pip.log" 2>&1; then
    die "python3 has no pip module: install python3-pip (the bundle's own interpreter is not used to install anything)"
  fi
  log "resolver: $(head -n 1 "$LOG_DIR/pip.log")"
  # Whether this machine's python could write byte-code for the tree being assembled, for the one
  # case where the tree itself cannot be run here.
  HOST_PY_SERIES="$(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])' 2>/dev/null || true)"
  log "host python $HOST_PY_SERIES, bundle python $PY_SERIES"
  local glibc
  glibc="$(ldd --version 2>/dev/null | head -n 1 | grep -oE '[0-9]+\.[0-9]+' | head -n 1 || true)"
  log "build host glibc ${glibc:-unknown}, guest $GUEST_LIBC, wheels resolved for the manylinux2014 floor"
}

# ---------- download ------------------------------------------------------------
download_to() {
  local url="$1" dest="$2"
  if curl --fail --location --silent --show-error --retry 5 --retry-all-errors \
    --output "$dest.part" "$url"; then
    mv -f "$dest.part" "$dest"
    return 0
  fi
  rm -f "$dest.part"
  return 1
}

# One download, one digest, one cache. Every input to the bundle is pinned by sha256 in this
# file, so a cached copy that no longer matches is re-fetched rather than trusted, and a fetched
# copy that does not match is deleted before anything reads it.
fetch_verified() {
  local url="$1" dest="$2" expected="$3" label="$4" actual
  if [ -f "$dest" ] && [ "$(sha256sum "$dest" | cut -d' ' -f1)" = "$expected" ]; then
    log "using cached $label"
    return 0
  fi
  rm -f "$dest"
  log "downloading $url"
  download_to "$url" "$dest" || die "download failed: $url"
  actual="$(sha256sum "$dest" | cut -d' ' -f1)"
  if [ "$actual" != "$expected" ]; then
    rm -f "$dest"
    die "digest mismatch for $label
  expected $expected
  actual   $actual
The downloaded copy was deleted; check the network or a rewriting proxy, then re-run."
  fi
  log "verified $label ($(human_bytes "$(wc -c <"$dest" | tr -d '[:space:]')"))"
}

fetch_interpreter() {
  mkdir -p "$CACHE_DIR"
  # Cross-check the pin in this script against the release's own SHA256SUMS before downloading
  # 30 MB: a pin that drifted from upstream is a mistake in this file, not a network problem.
  local want="" sums="$CACHE_DIR/SHA256SUMS"
  if download_to "$SUMS_URL" "$sums"; then
    want="$(awk -v n="$PYTHON_ASSET" '$2 == n { print $1; exit }' "$sums")"
    if [ -z "$want" ]; then
      die "SHA256SUMS of release $PBS_RELEASE has no line for $PYTHON_ASSET: this script's pin no longer matches upstream"
    fi
    if [ "$want" != "$EXPECTED_SHA" ]; then
      die "pinned sha256 for $PYTHON_ASSET is $EXPECTED_SHA but release $PBS_RELEASE publishes $want"
    fi
    log "pinned interpreter sha256 confirmed against the release SHA256SUMS"
  else
    warn "could not fetch $SUMS_URL, trusting the pinned sha256 alone"
  fi
  fetch_verified "$PYTHON_URL" "$CACHE_DIR/$PYTHON_ASSET" "$EXPECTED_SHA" "$PYTHON_ASSET"
  INTERPRETER_TARBALL="$CACHE_DIR/$PYTHON_ASSET"
}

stage_bundle() {
  mkdir -p "$STAGE" "$RUNTIME"
  local pbs="$WORK/pbs"
  mkdir -p "$pbs"
  tar -xzf "$INTERPRETER_TARBALL" -C "$pbs"
  if [ ! -d "$pbs/python" ]; then
    die "unexpected python-build-standalone layout: no python/ top directory in $PYTHON_ASSET"
  fi
  # The distribution's contents become runtime/{bin,lib,...}: that directory is the archive's
  # root, so its entries are ./bin/python and ./manifest.json — which is where the app's
  # unpacker puts them when it streams the archive into /root/local-models/runtime.
  cp -a "$pbs/python/." "$RUNTIME/"
  rm -rf "$pbs"

  if [ ! -f "$RUNTIME/bin/python3.12" ] || [ ! -d "$RUNTIME/lib/python$PY_SERIES" ]; then
    die "the extracted interpreter is missing bin/python3.12 or lib/python$PY_SERIES"
  fi
  # The architecture is checked in the bytes rather than by running them: this is the step that
  # says an x86_64 interpreter never slipped into an arm64 bundle, which no later check on a
  # machine that cannot run either of them would ever notice.
  local machine
  machine="$(elf_machine_of "$RUNTIME/bin/python3.12")"
  if [ "$machine" != "$TARGET_ELF_MACHINE" ]; then
    die "bin/python3.12 is a '$machine' binary but the target is $TARGET ($TARGET_ELF_MACHINE)"
  fi
  log "staged $TARGET CPython $PBS_PY_VERSION at $RUNTIME ($machine)"

  # The interpreter reports its own version only on a machine that can run it; the download is
  # pinned by sha256 and its asset name says the version, so the number is known either way.
  if [ "$RUN_MODE" != "none" ]; then
    local got
    got="$(run_bundle_python "$RUNTIME/bin/python3.12" -I -c 'import sys; print("%d.%d.%d" % sys.version_info[:3])')"
    if [ "$got" != "$PBS_PY_VERSION" ]; then
      die "the interpreter reports $got but this script pins $PBS_PY_VERSION"
    fi
  fi
}

# ---------- packages ------------------------------------------------------------
# Nothing is compiled, and the bundle's own interpreter is not used to install anything either:
# pip on the build machine is told to resolve for the *device* — its platform tag and its python
# version — and to write into the bundle's site-packages with --target. Every byte that lands in
# the tree comes from a wheel this script checked, and no package of the host is read, upgraded
# or touched.
#
# --no-deps on both runs, with all seven names spelled out: a resolver that is allowed to fetch
# what it likes adds an unpinned dependency to the bundle, and the archive digest is the runtime's
# identity on every device. Silent resolution is the one thing this recipe must never do.
fetch_llama_wheel() {
  mkdir -p "$CACHE_DIR"
  fetch_verified "$LLAMA_URL" "$CACHE_DIR/$LLAMA_ASSET" "$LLAMA_SHA" "llama-cpp-python $LLAMA_CPP_PYTHON $TARGET wheel"
  LLAMA_WHEEL_PATH="$CACHE_DIR/$LLAMA_ASSET"
}

# Downloads into a directory rather than installing straight in, because one run per package set is
# what makes a stopped build say whether the network or the resolver is the reason.
download_packages() {
  local wheels="$WORK/wheels"
  mkdir -p "$wheels"
  cp -f "$LLAMA_WHEEL_PATH" "$wheels/"
  if ! python3 -m pip download -q --no-deps "${pip_target[@]}" --python-version "$PY_TAG" -d "$wheels" \
    "numpy==$NUMPY" "psutil==$PSUTIL" "markupsafe==$MARKUPSAFE" "jinja2==$JINJA2" \
    "diskcache==$DISKCACHE" "typing-extensions==$TYPING_EXTENSIONS" \
    >"$LOG_DIR/pip-download.log" 2>&1; then
    tail -n 30 "$LOG_DIR/pip-download.log" >&2 || true
    die "could not fetch the pinned wheels for $TARGET, see $LOG_DIR/pip-download.log"
  fi
  log "wheels for $TARGET: $(find "$wheels" -maxdepth 1 -name '*.whl' | wc -l | tr -d '[:space:]') files, $(human_bytes "$(du -sb "$wheels" | cut -f1)")"
}

install_packages() {
  SITE="$RUNTIME/lib/python$PY_SERIES/site-packages"
  mkdir -p "$SITE"
  # --target is what makes the cross-resolution legal to pip: it is not installing into this
  # machine's python, it is unpacking wheels into a directory the phone's python will import.
  if ! python3 -m pip install -q --no-index --find-links "$WORK/wheels" --target "$SITE" \
    --no-deps "${pip_target[@]}" --python-version "$PY_TAG" \
    "llama_cpp_python==$LLAMA_CPP_PYTHON" \
    "numpy==$NUMPY" "psutil==$PSUTIL" "markupsafe==$MARKUPSAFE" "jinja2==$JINJA2" \
    "diskcache==$DISKCACHE" "typing_extensions==$TYPING_EXTENSIONS" \
    >"$LOG_DIR/pip-install.log" 2>&1; then
    tail -n 40 "$LOG_DIR/pip-install.log" >&2 || true
    die "installing the pinned wheels failed, see $LOG_DIR/pip-install.log"
  fi

  for module in llama_cpp numpy psutil jinja2 diskcache markupsafe; do
    if [ ! -d "$SITE/$module" ]; then
      die "expected $module under $SITE after the install"
    fi
  done
  local native
  native="$(find "$SITE/llama_cpp/lib" -type f -name 'libllama.so*' | wc -l | tr -d '[:space:]')"
  if [ "$native" = "0" ]; then
    die "llama_cpp carries no libllama.so: the wheel did not unpack its native libraries"
  fi
  # A wheel chosen for the wrong architecture installs silently and fails on the phone with an
  # import error that names nothing useful, so the bytes answer instead of the tags: every ELF in
  # site-packages has to be the machine this bundle claims.
  local wrong="" file machine
  while IFS= read -r file; do
    machine="$(elf_machine_of "$file")"
    [ -z "$machine" ] || [ "$machine" = "$TARGET_ELF_MACHINE" ] ||
      wrong="$wrong
  $machine: ${file#"$SITE"/}"
  done < <(find "$SITE" -type f \( -name '*.so' -o -name '*.so.*' \) | sort)
  if [ -n "$wrong" ]; then
    die "these libraries in site-packages are not $TARGET binaries:$wrong
A wheel for another architecture was installed; the cache or the platform tags are the place to look."
  fi
  log "site-packages: $(find "$SITE" -maxdepth 1 -type d | wc -l | tr -d '[:space:]') top-level entries, $(find "$SITE" -type f \( -name '*.so' -o -name '*.so.*' \) | wc -l | tr -d '[:space:]') native libraries, all $TARGET"
}

# ---------- trim ----------------------------------------------------------------
trim_bundle() {
  local runtime="$1"
  local stdlib="$runtime/lib/python$PY_SERIES"

  # Everything that would let the device change its own runtime goes: a phone that can run pip
  # has a runtime whose bundleId no longer describes it. The rest is what a headless inference
  # process never imports - test, idlelib, tkinter, lib2to3, ensurepip, unittest, dbm, crypt -
  # plus the headers and docs only a source build would want. numpy 2.5 imports none of them,
  # and the acceptance self-check below re-proves that on every build.
  rm -rf \
    "$runtime"/bin/pip* \
    "$runtime"/bin/easy_install* \
    "$runtime"/bin/2to3* \
    "$runtime"/bin/idle* \
    "$runtime"/bin/pydoc* \
    "$runtime"/bin/f2py* \
    "$runtime"/bin/*config \
    "$runtime"/include \
    "$runtime"/share \
    "$runtime"/lib/pkgconfig \
    "$stdlib"/test \
    "$stdlib"/idlelib \
    "$stdlib"/tkinter \
    "$stdlib"/lib2to3 \
    "$stdlib"/ensurepip \
    "$stdlib"/unittest \
    "$stdlib"/turtledemo \
    "$stdlib"/dbm \
    "$stdlib"/crypt.py \
    "$stdlib"/config-"$PY_SERIES"-* \
    "$stdlib"/site-packages/pip \
    "$stdlib"/site-packages/pip-* \
    "$stdlib"/site-packages/setuptools \
    "$stdlib"/site-packages/setuptools-* \
    "$stdlib"/site-packages/wheel \
    "$stdlib"/site-packages/wheel-* \
    "$stdlib"/site-packages/_distutils_hack \
    "$stdlib"/site-packages/pkg_resources

  # tkinter's native twins, otherwise the dependency check below dutifully asks for libtcl and
  # libtk on a guest that does not have them.
  rm -rf \
    "$runtime"/lib/tcl* \
    "$runtime"/lib/tk* \
    "$runtime"/lib/itcl* \
    "$runtime"/lib/thread* \
    "$runtime"/lib/libtcl*.so \
    "$runtime"/lib/libtk*.so \
    "$stdlib"/lib-dynload/_tkinter*.so \
    "$stdlib"/lib-dynload/_dbm*.so \
    "$stdlib"/lib-dynload/_crypt*.so

  # What a wheel carries for a *build* rather than for a run: pip --target drops the console
  # scripts it would otherwise put in bin/ into the target directory itself, and the llama.cpp
  # asset ships its CMake package, its headers and a second complete copy of every library under
  # lib64. serve.py loads libllama through ctypes from llama_cpp/lib; none of the rest has a
  # consumer on a phone, and 39 MB of it is the single largest thing in the archive.
  rm -rf "$SITE/bin" "$SITE/include" "$SITE/lib64" "$SITE/share"
  # The same library two or three times over, because the wheel unpacks real files where a normal
  # install would symlink soname -> version: libllama.so, libllama.so.0 and libllama.so.0.5.0 are
  # one 4 MB library, and two copies of a library in one directory is a recipe for loading the
  # wrong one. The remaining pair is what ctypes and the dynamic loader actually resolve.
  #
  # The version-named files a package's `.libs` directory holds are a different thing entirely:
  # those are auditwheel's hashed copies, and other libraries ask for them by exactly that name
  # (`libgomp-d22c30c5.so.1.0.0`), so deleting them breaks the bundle instead of trimming it.
  # Anything the pattern above still catches wrongly is caught below by the dependency check,
  # which names the library and the file that misses it.
  find "$SITE" -type f -name '*.so.*.*' -not -path '*.libs/*' -delete
  # If a future wheel spells those sonames as links instead of copies, deleting what they point at
  # leaves a link to nothing - and tar --dereference would answer that with an error about the
  # archive, not about the library. So the tree says it here.
  local dangling
  dangling="$(find "$SITE" -xtype l -print | head -n 3)"
  if [ -n "$dangling" ]; then
    die "a library link points at nothing after the trim: $(tr '\n' ' ' <<<"$dangling")
This wheel symlinks its sonames, so the versioned copies above are its real files: keep one name
per library instead of deleting by pattern."
  fi

  if [ ! -x "$runtime/bin/python3.12" ]; then
    die "bin/python3.12 is missing after the trim"
  fi
  # bin/python is a real COPY of the interpreter, not the symlink python-build-standalone ships:
  # the app streams the archive entry by entry and must be able to exec the interpreter without
  # symlink support, and without a second entry that has to resolve against the first one.
  rm -f "$runtime/bin/python"
  cp -p "$runtime/bin/python3.12" "$runtime/bin/python"
  # The remaining links have to go before the pack, not after: the archive is written with
  # --dereference, so each symlink left in the tree becomes a second complete copy of a 30 MB
  # binary in the APK. bin/python3 has no consumer (the app execs bin/python by absolute path)
  # and libpython3.12.so is the dev-interface alias for libpython3.12.so.1.0, which nothing in
  # the tree links against and which only ever mattered to a compiler, now deleted.
  rm -f "$runtime/bin/python3" "$runtime/lib/libpython$PY_SERIES.so"

  # Byte-code for the whole tree, kept in __pycache__, so a phone never compiles the standard
  # library on first import. unchecked-hash because the app's unpacker writes files with the
  # extraction time, and a timestamp-mode .pyc would then look stale on every one of them.
  #
  # The tree being compiled is the target's, but byte-code is architecture-independent: only the
  # python *series* has to match, or the magic number makes every .pyc inert and the phone compiles
  # on first import anyway. So the bundle's own interpreter does this when this machine can run it,
  # and the host's 3.12 does it when it cannot; anything else says so out loud rather than packing
  # a tree whose byte-code nothing will read.
  log "compiling the byte-code cache"
  local -a bytecode=() jobs=()
  # One qemu process at a time: -j forks, and a forked emulator is not the thing being tested.
  [ "$RUN_MODE" = "native" ] && jobs=(-j "$BUILD_JOBS")
  # -s/-p record the path the *phone* will read, not the one this machine assembled the tree at.
  # A .pyc embeds its source file's name, so without this every build in a fresh scratch directory
  # writes 973 byte-code files that differ from the previous build's for no reason other than the
  # directory they were made in - and a different digest means every device re-unpacks a runtime it
  # already holds. With it, two builds of the same pins are the same bytes and a traceback on a
  # device names a file that exists there.
  local -a where=(-s "$runtime" -p "$GUEST_RUNTIME_DIR")
  if can_run_bundle; then
    bytecode=(run_bundle_python "$runtime/bin/python" -I -m compileall)
  elif [ "${HOST_PY_SERIES:-}" = "$PY_SERIES" ]; then
    log "cross-assembling: the host python $PY_SERIES writes the byte-code the bundle would"
    bytecode=(python3 -I -m compileall)
  else
    warn "no python $PY_SERIES this machine can run: the bundle ships without byte-code, so the first import on a device compiles the standard library"
  fi
  if [ ${#bytecode[@]} -gt 0 ]; then
    # -f is not decoration. Running `python -m compileall` imports argparse, bz2, contextlib and
    # the rest of its own closure first, and the import system writes their byte-code as it goes,
    # with this scratch directory as the recorded file name and the default timestamp invalidation.
    # Without -f the walk then considers those files up to date and leaves them alone, so close to
    # a thousand .pyc keep the build path and two machines never agree on the archive digest.
    if ! "${bytecode[@]}" -q -f "${jobs[@]}" "${where[@]}" --invalidation-mode unchecked-hash "$stdlib" \
      >"$LOG_DIR/compileall.log" 2>&1; then
      tail -n 20 "$LOG_DIR/compileall.log" >&2 || true
      die "compileall failed, see $LOG_DIR/compileall.log"
    fi
  fi
  log "trimmed tree: $(du -sh "$runtime" | cut -f1) under $runtime"
}

# ---------- every library the bundle asks for ----------------------------------
# Nothing is copied here any more, and that is the point: the bundle is assembled on a machine of
# a different architecture, whose /usr/lib holds no arm64 bytes to lend. Every library has to come
# from the bundle or from the guest, so this step does not repair a gap, it decides whether one
# exists — and a soname that is neither in the rootfs list nor inside the tree stops the build
# rather than becoming an import error on a phone that names nothing useful.
sonames_needed_by() {
  readelf -dW "$1" 2>/dev/null | awk '/Shared library:/ { gsub(/.*\[|\]/, "", $NF); print $NF }'
}

is_guest_provided() {
  local soname="$1" provided
  for provided in "${GUEST_PROVIDED_LIBS[@]}"; do
    if [ "$provided" = "$soname" ]; then
      return 0
    fi
  done
  return 1
}

bundle_provides() {
  local runtime="$1" soname="$2" dir="$3" base="${2##*/}"
  # Present already means found already, by any of the three ways a loader is pointed at a file:
  # LD_LIBRARY_PATH on runtime/lib, the RPATH a package carries ($ORIGIN and its own `.libs`
  # sibling), or a NEEDED that spells out a path of its own — python-build-standalone's
  # libpython3.so asks for "$ORIGIN/../lib/libpython3.12.so.1.0", which is resolved against the
  # directory of the file that names it rather than searched by name. Both file types count: an
  # auditwheel library is often a symlink to the versioned twin beside it, and a check that looks
  # only for regular files reports a perfectly loadable library as missing.
  if [ -e "$runtime/lib/$base" ]; then
    return 0
  fi
  if [ "$base" != "$soname" ] && [ -e "$dir/${soname#*ORIGIN/}" ]; then
    return 0
  fi
  [ -n "$(find "$runtime" \( -type f -o -type l \) -name "$base" -print -quit 2>/dev/null)" ]
}

verify_dependencies() {
  local runtime="$1"
  local -a targets=("$runtime/bin/python" "$runtime/bin/python3.12")
  local so
  while IFS= read -r so; do
    targets+=("$so")
  done < <(find "$runtime" -type f \( -name '*.so' -o -name '*.so.*' \) | sort)

  local missing="" checked=0 selfcontained=0 file soname
  for file in "${targets[@]}"; do
    [ -f "$file" ] || continue
    while IFS= read -r soname; do
      [ -n "$soname" ] || continue
      checked=$((checked + 1))
      if is_guest_provided "$soname"; then
        continue
      fi
      if bundle_provides "$runtime" "$soname" "$(dirname "$file")"; then
        selfcontained=$((selfcontained + 1))
        continue
      fi
      missing="$missing
  $soname (needed by ${file#"$runtime"/})"
    done < <(sonames_needed_by "$file")
  done

  if [ -n "$missing" ]; then
    die "these libraries are needed by the bundle and nothing provides them:$missing
Either the wheel carries a library the guest rootfs does not (see
app/src/main/jniLibs/arm64-v8a/librootfs64.so for what the guest really has), or a package of
another architecture slipped in. GUEST_PROVIDED_LIBS in this script is the list to correct once
the rootfs has been checked, not before."
  fi

  # The other direction, which is the one that bricks a runtime quietly: a library in runtime/lib
  # that the guest already owns wins over the guest's copy through LD_LIBRARY_PATH, and a second
  # libc or libstdc++ in the tree means the interpreter runs against a pairing nothing tested.
  local shadowed="" extra
  for extra in "$runtime"/lib/*.so*; do
    [ -e "$extra" ] || continue
    if is_guest_provided "$(basename "$extra")"; then
      shadowed="$shadowed $(basename "$extra")"
    fi
  done
  if [ -n "$shadowed" ]; then
    die "runtime/lib carries libraries the guest rootfs already provides and does not need twice:$shadowed
The interpreter would load them instead of the guest's, and no check run outside the guest would
notice. Remove them from the tree."
  fi

  log "$checked library references checked: $selfcontained satisfied inside the bundle, the rest by the $GUEST_LIBC guest rootfs"
}

# ---------- manifest ------------------------------------------------------------
write_manifest() {
  local runtime="$1"
  cat >"$runtime/manifest.json" <<EOF
{
  "runtime": "awaki-python",
  "arch": "$TARGET",
  "libc": "$GUEST_LIBC",
  "python": "$PBS_PY_VERSION",
  "llamaCpp": "$LLAMA_CPP_PYTHON",
  "bundleId": "$BUNDLE_ID",
  "generatedBy": "build-awaki-runtime.sh",
  "pythonBuildStandalone": "$PBS_RELEASE",
  "sitePackages": {
    "numpy": "$NUMPY",
    "psutil": "$PSUTIL",
    "markupsafe": "$MARKUPSAFE",
    "jinja2": "$JINJA2",
    "diskcache": "$DISKCACHE",
    "typing_extensions": "$TYPING_EXTENSIONS"
  },
  "assembledFrom": "prebuilt wheels, nothing compiled",
  "llamaCppWheelSha256": "$LLAMA_SHA"
}
EOF
  log "wrote manifest.json (bundleId $BUNDLE_ID)"
}

# ---------- acceptance self-check ----------------------------------------------
# The gate: nothing gets packaged that the phone could not run. It runs against a COPY of the
# tree at a second path, because a bundle that only works where it was assembled is a bundle
# that fails on the phone, and it runs under env -i because the guest supplies nothing else.
SELFTEST_PY='
import os
import sys

expected = os.path.realpath(os.environ["AWAKI_EXPECT_PREFIX"])
prefix = os.path.realpath(sys.prefix)
if prefix != expected:
    raise SystemExit("sys.prefix is %s, expected it inside the bundle at %s" % (prefix, expected))
executable = os.path.realpath(sys.executable)
if not executable.startswith(expected + os.sep):
    raise SystemExit("sys.executable escaped the bundle: %s" % executable)
if os.path.islink(sys.executable):
    raise SystemExit("bin/python is a symlink; the app must not depend on symlink support")

import llama_cpp, numpy, jinja2, psutil, diskcache, typing_extensions
from llama_cpp import Llama
import llama_cpp.llama_cpp as lc

for name in ("pip", "setuptools", "wheel", "ensurepip", "idlelib", "tkinter", "test", "dbm"):
    try:
        __import__(name)
    except ImportError:
        continue
    raise SystemExit("%s is still importable: the device could change its own runtime" % name)

print("python       ", sys.version.split()[0])
print("prefix       ", prefix)
print("llama_cpp    ", llama_cpp.__version__)
print("numpy        ", numpy.__version__)
print("psutil       ", psutil.__version__)
print("jinja2       ", jinja2.__version__)
print("system_info  ", lc.llama_print_system_info().decode("utf-8", "replace").strip())
'

selfcheck_runtime() {
  local runtime="$1"
  if [ "$RUN_MODE" = "none" ]; then
    warn "the tree was NOT executed: this machine cannot run a $TARGET interpreter. Static checks passed; only a device has now proved anything."
    return 0
  fi
  local probe target
  probe="$(mktemp -d "${TMPDIR:-/tmp}/awaki-selfcheck-XXXXXX")"
  target="$probe/relocated/runtime"
  mkdir -p "$probe/relocated"
  # cp -a carries the modes, which is what keeps bin/python executable here.
  cp -a "$runtime" "$probe/relocated/runtime"
  if [ ! -x "$target/bin/python" ]; then
    rm -rf "$probe"
    die "self-check: $target/bin/python is not executable"
  fi
  log "self-checking a relocated copy at $target"
  if ! AWAKI_EXPECT_PREFIX="$target" run_bundle_python "$target/bin/python" -I -c "$SELFTEST_PY"; then
    rm -rf "$probe"
    die "the bundle did not pass its acceptance self-check (its output is above)"
  fi
  rm -rf "$probe"
}

# ---------- archive -------------------------------------------------------------
# The app unpacks each entry relative to the runtime directory itself, so an archive wrapped in a
# top-level runtime/ becomes runtime/runtime on the phone: the interpreter is never found, the
# guest check fails, and every Retry reproduces the same dead tree. The layout is the one contract
# a successful build can still break, so the packed bytes are read back instead of trusted.
check_archive_layout() {
  local archive="$1" listing
  listing="$(tar -tzf "$archive")"
  if ! grep -qxF './bin/python' <<<"$listing"; then
    die "the archive carries no ./bin/python at its root (first entries: $(head -n 3 <<<"$listing" | tr '\n' ' '))"
  fi
  if ! grep -qxF './manifest.json' <<<"$listing"; then
    die "the archive carries no ./manifest.json at its root"
  fi
  if grep -qE '^\./runtime/?$' <<<"$listing"; then
    die "the archive wraps the tree in a runtime/ directory; the app unpacks entries INTO the runtime directory"
  fi
  log "archive layout: $(grep -c '' <<<"$listing") entries rooted at ./bin/python"
}

create_archive() {
  local out="$1"
  # Absolute before the cd below, so a relative --output-dir is still relative to where the
  # operator stands rather than to the tree being packed.
  out="$(cd "$(dirname "$out")" && printf '%s/%s' "$PWD" "$(basename "$out")")"
  rm -f "$out"
  (
    cd "$RUNTIME" &&
      # LC_ALL=C because tar --sort=name collates through the locale, and two machines that
      # disagree about collation would produce two different archives from one identical tree.
      # --dereference so the archive holds only regular files: the app's unpacker treats a
      # symlink as best-effort, and a runtime whose bin/python is a skipped symlink is not a
      # runtime. --format=pax because the stdlib paths outgrow ustar's 100-character name field,
      # and its atime/ctime records are deleted because they would otherwise differ per machine
      # and a changing digest means every phone re-unpacks a runtime it already holds.
      # -n for gzip because its header otherwise stores the file name and this timestamp, and the
      # program is named here rather than through GZIP="-9 -n" because that environment variable
      # is deprecated and gzip 1.10 warns about it on every run.
      LC_ALL=C tar --create \
        --use-compress-program='gzip -9 -n' \
        --format=pax \
        --pax-option=exthdr.name=%d/PaxHeaders/%f,delete=atime,delete=ctime \
        --sort=name \
        --mtime='UTC 2000-01-01' \
        --owner=0 --group=0 --numeric-owner \
        --dereference \
        --file="$out" .
  )
  ARCHIVE_PATH="$out"
  ARCHIVE_SHA256="$(sha256sum "$out" | cut -d' ' -f1)"
  ARCHIVE_SIZE="$(wc -c <"$out" | tr -d '[:space:]')"
  check_archive_layout "$out"
}

verify_archive() {
  local archive="$1"
  [ -s "$archive" ] || die "no such archive: $archive"
  # An archive nobody executed is only a file listing. Verifying exists to prove the packed bytes
  # run, so a host that cannot run the target refuses instead of passing quietly - unless
  # AWAKI_SKIP_SELFCHECK=1 says the operator only wanted the static checks.
  if [ "$RUN_MODE" = "none" ] && [ "${AWAKI_SKIP_SELFCHECK:-0}" != "1" ]; then
    die "--verify-archive cannot prove anything about a $TARGET tree on a $(uname -m) host: install ${TARGET_QEMU[$TARGET]:-a user-mode emulator} (apt-get install qemu-user-static), or pass AWAKI_SKIP_SELFCHECK=1 to mean it as a static check"
  fi
  local absolute
  absolute="$(cd "$(dirname "$archive")" && printf '%s/%s' "$PWD" "$(basename "$archive")")"
  local probe
  probe="$(mktemp -d "${TMPDIR:-/tmp}/awaki-verify-XXXXXX")"
  log "verifying $absolute"
  if ! tar -xzf "$absolute" -C "$probe"; then
    rm -rf "$probe"
    die "the archive did not unpack - it is truncated or not gzip"
  fi
  # The archive is checked the way the phone will read it: unpacked, then run from wherever the
  # unpack put it, with nothing but its own lib on LD_LIBRARY_PATH.
  local target="$probe"
  if [ ! -d "$probe/bin" ]; then
    rm -rf "$probe"
    die "the archive has no bin/ at its root - the app unpacks entries into the runtime directory, it does not expect a runtime/ wrapper"
  fi
  if [ ! -f "$target/manifest.json" ] || [ ! -x "$target/bin/python" ]; then
    rm -rf "$probe"
    die "the unpacked tree has no manifest.json or no executable bin/python"
  fi
  selfcheck_runtime "$target"
  printf '>> archive sha256 %s (%s bytes)\n' \
    "$(sha256sum "$absolute" | cut -d' ' -f1)" "$(wc -c <"$absolute" | tr -d '[:space:]')"
}

# ---------- catalog -------------------------------------------------------------
write_catalog() {
  local path="$1"
  # The host's python writes the catalog: it is two JSON files and a hash, and the bundle's
  # interpreter may be bytes this machine cannot run.
  CATALOG_PATH="$path" \
    CAT_ABI="$ABI" CAT_ARCH="$TARGET" CAT_ASSET="$ASSET_PATH" \
    CAT_SHA256="$ARCHIVE_SHA256" CAT_SIZE="$ARCHIVE_SIZE" \
    CAT_PYTHON="$PBS_PY_VERSION" CAT_LLAMA="$LLAMA_CPP_PYTHON" CAT_BUNDLE_ID="$BUNDLE_ID" \
    python3 - <<'PY'
import json
import os

entry = {
    "abi": os.environ["CAT_ABI"],
    "arch": os.environ["CAT_ARCH"],
    "asset": os.environ["CAT_ASSET"],
    "sha256": os.environ["CAT_SHA256"],
    "sizeBytes": int(os.environ["CAT_SIZE"]),
    "python": os.environ["CAT_PYTHON"],
    "llamaCpp": os.environ["CAT_LLAMA"],
    "bundleId": os.environ["CAT_BUNDLE_ID"],
}
path = os.environ["CATALOG_PATH"]
with open(path, "w", encoding="utf-8", newline="\n") as handle:
    json.dump({"schema": 1, "bundles": [entry]}, handle, indent=2)
    handle.write("\n")
print(">> wrote " + path)
PY
}

# The app reads bundles.json into `RuntimeBundle` and ignores unknown keys, so a renamed field is
# not an error there — it is a phone that sees no runtime at all. Every key the Kotlin data class
# declares is required here, and the digest has to be the 64 hex characters its parser asks for,
# because an entry that fails those filters is dropped silently rather than rejected.
check_catalog() {
  local path="$1"
  require_cmd python3
  [ -s "$path" ] || die "no such catalog: $path"
  CATALOG_FILE="$path" python3 - <<'PY'
import json
import os
import re
import sys

required = {"abi", "arch", "asset", "sha256", "sizeBytes", "python", "llamaCpp", "bundleId"}
path = os.environ["CATALOG_FILE"]
try:
    catalog = json.load(open(path, encoding="utf-8"))
except ValueError as error:
    sys.exit("%s is not readable JSON: %s" % (path, error))

bundles = catalog.get("bundles")
if not isinstance(bundles, list) or not bundles:
    sys.exit("%s carries no bundles" % path)
problems = []
for index, entry in enumerate(bundles):
    for key in sorted(required - set(entry)):
        problems.append("bundles[%d] (%s) has no %r" % (index, entry.get("arch", "?"), key))
    digest = entry.get("sha256", "")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", str(digest)):
        problems.append("bundles[%d] sha256 %r is not 64 hex digits" % (index, digest))
    size = entry.get("sizeBytes")
    if not isinstance(size, int) or size <= 0:
        problems.append("bundles[%d] sizeBytes %r is not a positive size" % (index, size))
    for key in ("abi", "arch", "asset", "bundleId"):
        if not str(entry.get(key, "")).strip():
            problems.append("bundles[%d] %s is blank" % (index, key))
if problems:
    sys.exit("%s:\n  %s" % (path, "\n  ".join(problems)))
print(">> %s: %d bundle(s), every key the app parses is present" % (path, len(bundles)))
PY
}

# One catalog carries every ABI, so building x86_64 must not erase the arm64 entry: this replaces
# the entry with the same arch and copies the rest through untouched.
install_into_repo() {
  local dir="$REPO_ROOT/app/src/main/assets/local-runtime"
  mkdir -p "$dir"
  cp -f "$ARCHIVE_PATH" "$dir/$ASSET_NAME"
  if ! CATALOG_PATH="$dir/bundles.json" CAT_ENTRY_FILE="$OUTPUT_DIR/$CATALOG_NAME" \
    python3 - <<'PY'
import json
import os

entry = json.load(open(os.environ["CAT_ENTRY_FILE"], encoding="utf-8"))["bundles"][0]
path = os.environ["CATALOG_PATH"]
try:
    catalog = json.load(open(path, encoding="utf-8"))
except FileNotFoundError:
    catalog = {"schema": 1, "bundles": []}
except ValueError as error:
    raise SystemExit("refusing to overwrite %s: it is not readable JSON (%s)" % (path, error))

bundles = catalog.setdefault("bundles", [])
replaced = any(item.get("arch") == entry["arch"] for item in bundles)
bundles[:] = [item for item in bundles if item.get("arch") != entry["arch"]]
bundles.append(entry)
catalog["schema"] = catalog.get("schema") or 1
with open(path, "w", encoding="utf-8", newline="\n") as handle:
    json.dump(catalog, handle, indent=2)
    handle.write("\n")
print(">> %s %s: %s (now %d bundles)" % (
    "replaced the entry in" if replaced else "added an entry to", path, entry["arch"], len(bundles)))
PY
  then
    die "could not update $dir/bundles.json"
  fi
  REPO_ASSET_FILE="$dir/$ASSET_NAME"
  REPO_CATALOG_FILE="$dir/bundles.json"
}

# ---------- workspace -----------------------------------------------------------
prepare_workspace() {
  WORK="$(mktemp -d "${TMPDIR:-/tmp}/awaki-runtime-${TARGET}-XXXXXX")"
  STAGE="$WORK/stage"
  RUNTIME="$STAGE/runtime"
  LOG_DIR="$WORK/logs"
  mkdir -p "$STAGE" "$LOG_DIR"
  # Only ever a parallelism for compileall; nothing here compiles a translation unit.
  BUILD_JOBS="$(nproc 2>/dev/null || echo 4)"
  trap 'cleanup_work' EXIT
}

cleanup_work() {
  if [ "${KEEP_WORK:-0}" = "1" ] && [ -n "${WORK:-}" ]; then
    log "work tree kept at $WORK"
  elif [ -n "${WORK:-}" ]; then
    rm -rf "$WORK"
  fi
}

# ---------- main ----------------------------------------------------------------
main() {
  parse_args "$@"

  if [ -n "$CHECK_CATALOG" ]; then
    check_catalog "$CHECK_CATALOG"
    exit 0
  fi

  prepare_workspace
  # Decides how - and whether - the assembled tree gets executed, and unpacks the guest rootfs the
  # emulator will run it inside. Everything after this point knows the answer.
  plan_verification
  prepare_sysroot

  if [ -n "$VERIFY_ARCHIVE" ]; then
    require_cmd tar grep
    verify_archive "$VERIFY_ARCHIVE"
    exit 0
  fi

  check_build_tools
  mkdir -p "$OUTPUT_DIR"

  fetch_interpreter
  fetch_llama_wheel
  stage_bundle
  download_packages
  install_packages
  # Order matters: trim first so the dependency check never sees a pip or tkinter leftover, then
  # check the libraries the tree asks for, then the manifest, then the self-check of exactly the
  # tree that is about to be packed.
  trim_bundle "$RUNTIME"
  verify_dependencies "$RUNTIME"
  write_manifest "$RUNTIME"
  selfcheck_runtime "$RUNTIME"

  create_archive "$OUTPUT_DIR/$ASSET_NAME"
  log "archive $ARCHIVE_PATH ($(human_bytes "$ARCHIVE_SIZE"), sha256 $ARCHIVE_SHA256)"
  write_catalog "$OUTPUT_DIR/$CATALOG_NAME"

  if [ "$INSTALL_INTO_REPO" = "1" ]; then
    install_into_repo
    check_catalog "$REPO_ROOT/app/src/main/assets/local-runtime/bundles.json"
  fi

  cat <<EOF

>> Done.
   archive   $ARCHIVE_PATH ($(human_bytes "$ARCHIVE_SIZE"))
   sha256    $ARCHIVE_SHA256
   catalog   $OUTPUT_DIR/$CATALOG_NAME
   proven    the tree was executed $(
    case "$RUN_MODE" in
      native) printf 'on this machine' ;;
      emulated) printf 'under %s inside the app rootfs' "$EMULATOR" ;;
      *) printf 'NOWHERE - static checks only' ;;
    esac
   )
EOF
  if [ "$INSTALL_INTO_REPO" = "1" ]; then
    cat <<EOF
   in repo   $REPO_ASSET_FILE
             $REPO_CATALOG_FILE
EOF
  else
    cat <<EOF
   for the APK to carry it, both belong in the repository:
             app/src/main/assets/local-runtime/$ASSET_NAME
             app/src/main/assets/local-runtime/bundles.json   (--install-into-repo does this)
EOF
  fi
}

# Sourced rather than executed - which is how the functions above get tested without a build -
# the file only defines; nothing happens until main runs.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
