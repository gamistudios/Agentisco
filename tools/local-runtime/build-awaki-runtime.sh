#!/usr/bin/env bash
# build-awaki-runtime.sh — assemble the prebuilt Python runtime that Awaki ships inside the APK.
#
# WHAT IT PRODUCES
#   <output-dir>/awaki-runtime-<arch>.tar.gz  the runtime tree, gzip'd, reproducible byte-for-byte
#   <output-dir>/bundles-<arch>.json          the catalog the app reads (schema 1)
# With --install-into-repo the same two files also land at
#   app/src/main/assets/local-runtime/awaki-runtime-<arch>.tar.gz
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
#   PyPI publishes no wheel at all for llama-cpp-python: every release is an sdist, so the ggml
#   native libraries have to be compiled by somebody. Compiling them on a 3.6 GB phone that also
#   has to hold a model is what used to freeze the device, so they are compiled HERE instead and
#   shipped as bytes. What the phone does afterwards is one checksum and one unpack: no pip, no
#   compiler, no network. tools/local-runtime/README.md covers the pins and how to bump them.
#
# USAGE
#   bash tools/local-runtime/build-awaki-runtime.sh                    # build for the host arch
#   bash tools/local-runtime/build-awaki-runtime.sh --target aarch64   # the shipping artifact
#   bash tools/local-runtime/build-awaki-runtime.sh --help
#
# No root is needed: the interpreter this script downloads runs pip itself, and cmake comes from
# a wheel when the machine has none. What the machine does need is a C/C++ compiler, and it must
# be of the architecture being produced, because that is the architecture the compiler emits.
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
RUNTIME_REVISION="r1"

LLAMA_CPP_PYTHON="0.3.36"
NUMPY="2.5.3"
PSUTIL="7.2.2"
MARKUPSAFE="3.0.4"
JINJA2="3.1.6"
DISKCACHE="5.6.3"
TYPING_EXTENSIONS="4.16.0"

# scikit-build-core reads CMAKE_ARGS, space-separated, for the configure step (older releases
# only read SKBUILD_CMAKE_ARGS, so both are exported).
#   GGML_NATIVE=OFF  is mandatory. -march=native bakes the build machine's instruction set into
#                    the bundle, and the next phone answers SIGILL instead of an error message.
#   GGML_OPENMP=ON   makes libgomp.so.1 a dependency, which the guest rootfs does not carry, so
#                    copy_extra_shared_libs puts a private copy in runtime/lib.
#   LLAMA_CURL=OFF   keeps libcurl and its dependency chain out of the bundle: the app downloads
#                    models, the guest never does.
CMAKE_CONFIGURE_ARGS="-DGGML_OPENMP=ON -DGGML_NATIVE=OFF -DLLAMA_CURL=OFF"

# Shared libraries the Ubuntu 24.04 rootfs shipped in the APK already provides, verified by
# listing that archive. Everything else the bundle needs has to travel with it, because the
# guest has no package manager: note that glibc's compatibility stubs (libpthread.so.0,
# libdl.so.2, librt.so.1, libutil.so.1), which the interpreter is linked against, are NOT in
# that list and so get copied in by the sweep rather than assumed.
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
)

# Triples are python-build-standalone asset names; the digests come from that release's
# SHA256SUMS. armv7 is the HARD-FLOAT build: Android's armeabi-v7a is EABI hard-float, and the
# soft-float gnueabi build would load against a libc whose ABI does not match the device.
declare -A PBS_TRIPLE=(
  [aarch64]="aarch64-unknown-linux-gnu"
  [x86_64]="x86_64-unknown-linux-gnu"
  [armv7]="armv7-unknown-linux-gnueabihf"
)
declare -A PBS_SHA256=(
  [aarch64]="6541297dd1798dec8b98c3ad7492808a5b9d1c126801ceb2011e7754cd20d1ce"
  [x86_64]="731af898886c5f821890dc901eca3c651cca8e51fa7308c159d12a1194aeac91"
  [armv7]="c90a03e8ae6e6be58d15127063d5cdef4e1b17253ec82c9d04646a56c9b0003d"
)
declare -A TARGET_ABI=(
  [aarch64]="arm64-v8a"
  [x86_64]="x86_64"
  [armv7]="armeabi-v7a"
)
# uname -m values a host may report and still produce this target natively.
declare -A TARGET_HOST_MACHINES=(
  [aarch64]="aarch64"
  [x86_64]="x86_64"
  [armv7]="armv7l armv8l arm"
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

# ---------- arguments -----------------------------------------------------------
TARGET=""
OUTPUT_DIR="dist"
INSTALL_INTO_REPO=0
VERIFY_ARCHIVE=""
CHECK_CATALOG=""

usage() {
  cat <<'EOF'
Usage: bash tools/local-runtime/build-awaki-runtime.sh [options]

  --target aarch64|x86_64|armv7
                        architecture to build for. Default: the host's own.
                        aarch64 is the shipping artifact and needs a native ARM64 Linux host
                        (GitHub's ubuntu-24.04-arm). x86_64 exists so the recipe can be
                        validated on a dev machine; its bundle is never shipped. armv7 needs
                        a native 32-bit ARM host.
  --output-dir DIR      where the archive and catalog are written (default: dist)
  --install-into-repo   copy the archive into app/src/main/assets/local-runtime/ and merge its
                        entry into that directory's bundles.json
  --verify-archive FILE unpack an existing archive and re-run the acceptance self-check against
                        it without building anything (the CI verify step)
  --check-catalog FILE  validate a bundles.json against the keys the app parses, building nothing
  -h, --help            this message

Environment:
  AWAKI_DOWNLOAD_CACHE  interpreter download cache (default: ~/.cache/awaki/local-runtime)
  KEEP_WORK=1           keep the scratch tree for inspection

Two files have to reach the repository for the APK to carry a runtime:
  app/src/main/assets/local-runtime/awaki-runtime-<arch>.tar.gz
  app/src/main/assets/local-runtime/bundles.json
EOF
}

default_target() {
  case "$(uname -m)" in
    aarch64 | arm64) printf 'aarch64' ;;
    x86_64 | amd64) printf 'x86_64' ;;
    armv7l | armv8l | arm*) printf 'armv7' ;;
    *) die "cannot map 'uname -m' ($(uname -m)) to a supported --target; pass --target explicitly" ;;
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

  if [ -z "$TARGET" ]; then
    # A plain invocation is always a valid native build of whatever this machine is.
    TARGET="$(default_target)"
  fi
  if [ -z "${PBS_TRIPLE[$TARGET]:-}" ]; then
    die "unknown --target '$TARGET' (expected aarch64, x86_64 or armv7)"
  fi

  TRIPLE="${PBS_TRIPLE[$TARGET]}"
  EXPECTED_SHA="${PBS_SHA256[$TARGET]}"
  ABI="${TARGET_ABI[$TARGET]}"
  BUNDLE_ID="${TARGET}-${RUNTIME_REVISION}"
  ASSET_NAME="awaki-runtime-${TARGET}.tar.gz"
  ASSET_PATH="local-runtime/${ASSET_NAME}"
  CATALOG_NAME="bundles-${TARGET}.json"
  PYTHON_ASSET="cpython-${PBS_PY_VERSION}+${PBS_RELEASE}-${TRIPLE}-install_only_stripped.tar.gz"
  # The '+' in the asset name has to be percent-encoded for the release download URL.
  PYTHON_URL="https://github.com/astral-sh/python-build-standalone/releases/download/${PBS_RELEASE}/${PYTHON_ASSET/+/%2B}"
  SUMS_URL="https://github.com/astral-sh/python-build-standalone/releases/download/${PBS_RELEASE}/SHA256SUMS"
  CACHE_DIR="${AWAKI_DOWNLOAD_CACHE:-${HOME:-.}/.cache/awaki/local-runtime}"
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
}

# ---------- host preflight ------------------------------------------------------
check_host_arch() {
  local machine allowed
  machine="$(uname -m)"
  for allowed in ${TARGET_HOST_MACHINES[$TARGET]}; do
    if [ "$machine" = "$allowed" ]; then
      return 0
    fi
  done
  # Refusing beats producing: the interpreter would be for the right architecture and every
  # compiled package inside the tree would be for the machine that built it.
  die "--target $TARGET needs a native $TARGET host, this host reports '$machine'. llama.cpp is compiled by the host compiler, so the bundle's architecture is the build machine's architecture."
}

check_build_tools() {
  require_cmd curl tar gzip sha256sum ldd find mktemp sed grep awk sort cp mkdir rm du
  if ! command -v cc >/dev/null 2>&1 && ! command -v gcc >/dev/null 2>&1; then
    die "no C compiler on PATH (cc/gcc): llama-cpp-python ships only an sdist, so it has to be compiled"
  fi
  if ! command -v c++ >/dev/null 2>&1 && ! command -v g++ >/dev/null 2>&1; then
    die "no C++ compiler on PATH (c++/g++): ggml is C++"
  fi
  local glibc
  glibc="$(ldd --version 2>/dev/null | head -n 1 | grep -oE '[0-9]+\.[0-9]+' | head -n 1 || true)"
  # Libraries are copied out of this host into runtime/lib, and they have to keep loading
  # against the guest's glibc, so a host newer than the guest is worth saying out loud.
  if [ -n "$glibc" ] && [ "$glibc" != "${GUEST_LIBC#glibc-}" ]; then
    warn "build host glibc is $glibc but the guest ships $GUEST_LIBC: libraries copied from here may not load in the guest"
  fi
  log "target $TARGET ($TRIPLE), abi $ABI, bundleId $BUNDLE_ID, host glibc ${glibc:-unknown}"
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

fetch_interpreter() {
  mkdir -p "$CACHE_DIR"
  local cached="$CACHE_DIR/$PYTHON_ASSET" want

  # Cross-check the pin in this script against the release's own SHA256SUMS before downloading
  # 30 MB: a pin that drifted from upstream is a mistake in this file, not a network problem.
  want=""
  if download_to "$SUMS_URL" "$CACHE_DIR/SHA256SUMS"; then
    want="$(awk -v n="$PYTHON_ASSET" '$2 == n { print $1; exit }' "$CACHE_DIR/SHA256SUMS")"
    if [ -z "$want" ]; then
      die "SHA256SUMS of release $PBS_RELEASE has no line for $PYTHON_ASSET: this script's pin no longer matches upstream"
    fi
    if [ "$want" != "$EXPECTED_SHA" ]; then
      die "pinned sha256 for $PYTHON_ASSET is $EXPECTED_SHA but release $PBS_RELEASE publishes $want"
    fi
    log "pinned sha256 confirmed against the release SHA256SUMS"
  else
    warn "could not fetch $SUMS_URL, trusting the pinned sha256 alone"
  fi

  if [ -f "$cached" ]; then
    if [ "$(sha256sum "$cached" | cut -d' ' -f1)" = "$EXPECTED_SHA" ]; then
      log "using cached interpreter: $cached"
    else
      log "cached copy has the wrong digest, re-downloading"
      rm -f "$cached"
    fi
  fi
  if [ ! -f "$cached" ]; then
    log "downloading $PYTHON_URL"
    download_to "$PYTHON_URL" "$cached" || die "download failed: $PYTHON_URL"
  fi

  local actual
  actual="$(sha256sum "$cached" | cut -d' ' -f1)"
  if [ "$actual" != "$EXPECTED_SHA" ]; then
    # Deleting the bad copy means the next run cannot be fooled by it again.
    rm -f "$cached"
    die "digest mismatch for $PYTHON_ASSET
  expected $EXPECTED_SHA
  actual   $actual
The cached copy was deleted; check the network or a rewriting proxy, then re-run."
  fi

  INTERPRETER_TARBALL="$cached"
  log "verified $PYTHON_ASSET ($(human_bytes "$(wc -c <"$cached" | tr -d '[:space:]')"))"
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
  local got
  got="$("$RUNTIME/bin/python3.12" -I -c 'import sys; print("%d.%d.%d" % sys.version_info[:3])')"
  if [ "$got" != "$PBS_PY_VERSION" ]; then
    die "the interpreter reports $got but this script pins $PBS_PY_VERSION"
  fi
  log "staged CPython $got at $RUNTIME"
}

# ---------- packages ------------------------------------------------------------
# The bundle's own interpreter runs pip, so the host's python never enters the picture and
# nothing lands outside the tree that gets packaged. --prefix puts the packages in
# runtime/lib/python3.12/site-packages, which is the layout the app's installer expects.
pip_install() {
  local mode="$1" logfile="$2"
  shift 2
  local -a cmd=(
    "$RUNTIME/bin/python3.12" -I -m pip install
    --no-cache-dir
    --prefix "$RUNTIME"
    --disable-pip-version-check
    --no-input
    --progress-bar off
  )
  log "pip install $* (output: $logfile)"
  if [ "$mode" = "compile" ]; then
    # --no-deps: llama-cpp-python's four runtime dependencies are pinned and installed above,
    # so resolution can only ever surprise us by upgrading one of them.
    CMAKE_ARGS="$CMAKE_CONFIGURE_ARGS" SKBUILD_CMAKE_ARGS="$CMAKE_CONFIGURE_ARGS" \
      CMAKE_BUILD_PARALLEL_LEVEL="$BUILD_JOBS" \
      "${cmd[@]}" --no-deps "$@" >"$logfile" 2>&1
  else
    CMAKE_BUILD_PARALLEL_LEVEL="$BUILD_JOBS" \
      "${cmd[@]}" "$@" >"$logfile" 2>&1
  fi
}

ensure_cmake() {
  if command -v cmake >/dev/null 2>&1; then
    log "cmake: $(cmake --version | head -n 1)"
    return 0
  fi
  # The cmake wheel on PyPI is a real cmake binary, so a machine with no cmake still needs no
  # root. It goes into a directory beside the bundle and is never packaged with it.
  log "no cmake on PATH, installing one into $TOOLS from a wheel (about 30 MB)"
  mkdir -p "$TOOLS"
  if ! CMAKE_BUILD_PARALLEL_LEVEL="$BUILD_JOBS" \
    "$RUNTIME/bin/python3.12" -I -m pip install --no-cache-dir --target "$TOOLS" \
    --disable-pip-version-check --no-input --progress-bar off cmake \
    >"$LOG_DIR/cmake-bootstrap.log" 2>&1; then
    die "could not install cmake with the bundled interpreter, see $LOG_DIR/cmake-bootstrap.log"
  fi
  export PATH="$TOOLS/bin:$PATH"
  if ! command -v cmake >/dev/null 2>&1; then
    die "cmake was installed but is still not on PATH (expected $TOOLS/bin/cmake)"
  fi
  log "cmake: $(cmake --version | head -n 1)"
}

install_packages() {
  ensure_cmake
  # The wheels and pure packages first, the compile second: one failure mode per pip run, so a
  # log that stops part-way says whether cmake or a download is the reason.
  if ! pip_install plain "$LOG_DIR/pip-deps.log" \
    "numpy==$NUMPY" \
    "psutil==$PSUTIL" \
    "markupsafe==$MARKUPSAFE" \
    "jinja2==$JINJA2" \
    "diskcache==$DISKCACHE" \
    "typing-extensions==$TYPING_EXTENSIONS"; then
    tail -n 30 "$LOG_DIR/pip-deps.log" >&2 || true
    die "installing the pinned dependencies failed, see $LOG_DIR/pip-deps.log"
  fi

  log "compiling llama-cpp-python $LLAMA_CPP_PYTHON with '$CMAKE_CONFIGURE_ARGS' (the slow step)"
  if ! pip_install compile "$LOG_DIR/pip-llama.log" "llama_cpp_python==$LLAMA_CPP_PYTHON"; then
    tail -n 40 "$LOG_DIR/pip-llama.log" >&2 || true
    die "the llama-cpp-python compile failed, see $LOG_DIR/pip-llama.log"
  fi

  SITE="$RUNTIME/lib/python$PY_SERIES/site-packages"
  if [ ! -d "$SITE/llama_cpp" ] || [ ! -d "$SITE/numpy" ]; then
    die "expected llama_cpp and numpy under $SITE after the install"
  fi
  local native
  native="$(find "$SITE/llama_cpp" -type f \( -name '*.so' -o -name '*.so.*' \) | wc -l | tr -d '[:space:]')"
  if [ "$native" = "0" ]; then
    die "llama_cpp carries no native library: the ggml build did not land in the tree"
  fi
  log "site-packages: $(find "$SITE" -maxdepth 1 -type d | wc -l | tr -d '[:space:]') top-level entries, $native llama.cpp native libraries"
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

  # tkinter's native twins, otherwise the library sweep below dutifully copies libtcl and
  # libtk in for a module nothing imports.
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

  if [ ! -x "$runtime/bin/python3.12" ]; then
    die "bin/python3.12 is missing after the trim"
  fi
  # bin/python is a real COPY of the interpreter, not the symlink python-build-standalone ships:
  # the app streams the archive entry by entry and must be able to exec the interpreter without
  # symlink support, and without a second entry that has to resolve against the first one.
  rm -f "$runtime/bin/python"
  cp -p "$runtime/bin/python3.12" "$runtime/bin/python"

  # Byte-code for the whole tree, kept in __pycache__, so a phone never compiles the standard
  # library on first import. unchecked-hash because the app's unpacker writes files with the
  # extraction time, and a timestamp-mode .pyc would then look stale on every one of them.
  log "compiling the byte-code cache"
  if ! "$runtime/bin/python" -I -m compileall -q -j "$BUILD_JOBS" \
    --invalidation-mode unchecked-hash "$stdlib" >"$LOG_DIR/compileall.log" 2>&1; then
    tail -n 20 "$LOG_DIR/compileall.log" >&2 || true
    die "compileall failed, see $LOG_DIR/compileall.log"
  fi
  log "trimmed tree: $(du -sh "$runtime" | cut -f1) under $runtime"
}

# ---------- libraries the guest does not have ----------------------------------
is_guest_provided() {
  local soname="$1" provided
  for provided in "${GUEST_PROVIDED_LIBS[@]}"; do
    if [ "$provided" = "$soname" ]; then
      return 0
    fi
  done
  return 1
}

bundle_has_lib() {
  local runtime="$1" soname="$2"
  # Present already means found already: either through LD_LIBRARY_PATH on runtime/lib, or
  # through a package's own RPATH (numpy.libs, llama_cpp/lib).
  if [ -e "$runtime/lib/$soname" ]; then
    return 0
  fi
  if [ -n "$(find "$runtime" -name "$soname" -type f -print -quit 2>/dev/null)" ]; then
    return 0
  fi
  return 1
}

copy_extra_shared_libs() {
  local runtime="$1"
  local -A copied=()
  local -a targets=("$runtime/bin/python" "$runtime/bin/python3.12")
  local so
  while IFS= read -r so; do
    targets+=("$so")
  done < <(find "$runtime" -type f \( -name '*.so' -o -name '*.so.*' \) | sort)

  local file line soname resolved missing=""
  for file in "${targets[@]}"; do
    # ldd resolves the bundle's own RPATH ($ORIGIN/../lib), which is the same question the
    # dynamic loader will answer on the phone once LD_LIBRARY_PATH points at runtime/lib.
    while IFS= read -r line; do
      case "$line" in
        *"=> not found"*)
          soname="${line%%=>*}"
          soname="${soname#"${soname%%[![:space:]]*}"}"
          if is_guest_provided "$soname"; then
            continue
          fi
          if bundle_has_lib "$runtime" "$soname"; then
            continue
          fi
          missing="$missing
  $soname (needed by ${file#"$runtime"/})"
          continue
          ;;
        *"=>"*)
          soname="${line%%=>*}"
          soname="${soname#"${soname%%[![:space:]]*}"}"
          resolved="${line#*=>}"
          resolved="${resolved%%(0x*}"
          resolved="${resolved%"${resolved##*[![:space:]]}"}"
          ;;
        # The loader itself and the vdso appear as bare paths, and both belong to the guest.
        *) continue ;;
      esac
      if [ -z "$soname" ] || [ -z "$resolved" ] || [ "$resolved" = "statically linked" ]; then
        continue
      fi
      if is_guest_provided "$soname"; then
        continue
      fi
      case "$resolved" in
        "$runtime"/*) continue ;;
      esac
      if bundle_has_lib "$runtime" "$soname" || [ ! -f "$resolved" ]; then
        continue
      fi
      if [ -n "${copied[$soname]:-}" ]; then
        continue
      fi
      # cp -L: the tree must carry a real file, not a dangling reference to this host's
      # symlink farm.
      cp -Lp "$resolved" "$runtime/lib/$soname"
      copied["$soname"]="$resolved"
      log "copied $soname <- $resolved"
    done < <(ldd "$file" 2>/dev/null || true)
  done

  if [ -n "$missing" ]; then
    die "these libraries are needed by the bundle but resolve to nothing on this host:$missing
Install what provides them on the BUILD machine (the bundle itself needs no root), or the guest
will fail to load the modules that ask for them."
  fi

  BUNDLED_EXTRA_LIBS="${#copied[@]}"
  if [ "$BUNDLED_EXTRA_LIBS" = "0" ]; then
    log "no shared library beyond what the guest rootfs provides is needed"
  else
    log "bundled $BUNDLED_EXTRA_LIBS extra shared libraries into $runtime/lib"
  fi
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
  "guestLibrariesBundled": $BUNDLED_EXTRA_LIBS
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
  if env -i PATH="/usr/bin:/bin" \
    AWAKI_EXPECT_PREFIX="$target" \
    LD_LIBRARY_PATH="$target/lib" \
    "$target/bin/python" -I -c "$SELFTEST_PY"; then
    rm -rf "$probe"
  else
    rm -rf "$probe"
    die "the bundle did not pass its acceptance self-check (its output is above)"
  fi
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
  export GZIP="-9 -n"
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
      LC_ALL=C tar --create --gzip \
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
  CATALOG_PATH="$path" \
    CAT_ABI="$ABI" CAT_ARCH="$TARGET" CAT_ASSET="$ASSET_PATH" \
    CAT_SHA256="$ARCHIVE_SHA256" CAT_SIZE="$ARCHIVE_SIZE" \
    CAT_PYTHON="$PBS_PY_VERSION" CAT_LLAMA="$LLAMA_CPP_PYTHON" CAT_BUNDLE_ID="$BUNDLE_ID" \
    "$RUNTIME/bin/python" -I - <<'PY'
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

# One catalog carries every ABI, so an armv7 build must not erase the arm64 entry: this
# replaces the entry with the same arch and copies the rest through untouched.
install_into_repo() {
  local dir="$REPO_ROOT/app/src/main/assets/local-runtime"
  mkdir -p "$dir"
  cp -f "$ARCHIVE_PATH" "$dir/$ASSET_NAME"
  if ! CATALOG_PATH="$dir/bundles.json" CAT_ENTRY_FILE="$OUTPUT_DIR/$CATALOG_NAME" \
    "$RUNTIME/bin/python" -I - <<'PY'
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
  TOOLS="$WORK/tools"
  LOG_DIR="$WORK/logs"
  mkdir -p "$STAGE" "$TOOLS" "$LOG_DIR"
  # An 8-core CI box should not be reduced to one translation unit at a time.
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

  if [ -n "$VERIFY_ARCHIVE" ]; then
    require_cmd tar
    verify_archive "$VERIFY_ARCHIVE"
    exit 0
  fi

  if [ -n "$CHECK_CATALOG" ]; then
    check_catalog "$CHECK_CATALOG"
    exit 0
  fi

  check_host_arch
  check_build_tools
  prepare_workspace
  mkdir -p "$OUTPUT_DIR"

  fetch_interpreter
  stage_bundle
  install_packages
  # Order matters: trim first so the library sweep never sees a tkinter or pip leftover, then
  # sweep so runtime/lib holds everything the guest lacks, then the manifest, then the
  # self-check of exactly the tree that is about to be packed.
  BUNDLED_EXTRA_LIBS=0
  trim_bundle "$RUNTIME"
  copy_extra_shared_libs "$RUNTIME"
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
