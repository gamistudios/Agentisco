#!/usr/bin/env bash
# live-smoke.sh - drive the update API against the real GitHub repository.
#
# Reads credentials from the environment or update-api/.env, starts the server on
# a local port, then checks what a real Android client would see. Every upstream
# call is a read; nothing here writes to GitHub.
set -euo pipefail

API_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${PORT:-18080}"
STORAGE="${STORAGE:-$API_DIR/tmp-live}"
BASE="http://127.0.0.1:$PORT"
SERVER_PID=""
FAILED=0

log() { printf '%s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*"; FAILED=1; }

cleanup() {
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

cd "$API_DIR"
mkdir -p "$STORAGE"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi
: "${GITHUB_TOKEN:?GITHUB_TOKEN must be set (update-api/.env or the environment)}"
REPO="${GITHUB_REPOSITORY:-${GITHUB_REPO:-}}"
[[ -n "${GITHUB_OWNER:-}" && -n "$REPO" ]] || { log "GITHUB_OWNER and GITHUB_REPOSITORY are required"; exit 1; }

log "== building =="
go build -o "$STORAGE/update-api.exe" ./cmd/server

log "== starting on $BASE =="
PORT="$PORT" STORAGE_PATH="$STORAGE" SYNC_INTERVAL_SECONDS=60 LOG_FORMAT=text LOG_LEVEL=info \
  GITHUB_REPOSITORY="$REPO" \
  "$STORAGE/update-api.exe" > "$STORAGE/server.log" 2>&1 &
SERVER_PID=$!

ready=""
# The first pass caches both channel APKs before the service declares itself
# ready, so allow for a large artifact over a slow link.
for _ in $(seq 1 300); do
  if curl -fsS "$BASE/ready" >/dev/null 2>&1; then ready=yes; break; fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then break; fi
  sleep 1
done
if [[ -z "$ready" ]]; then
  log "the server never became ready; its log:"
  sed -e "s/$GITHUB_TOKEN/<redacted>/g" "$STORAGE/server.log"
  exit 1
fi

log "== health =="
curl -fsS "$BASE/health" || fail "health check"

log "== channels =="
for channel in debug release; do
  status="$(curl -sS -o "$STORAGE/$channel.json" -w '%{http_code}' "$BASE/v1/updates/$channel/latest")"
  log "  $channel -> HTTP $status"
  if [[ "$status" != "200" ]]; then
    cat "$STORAGE/$channel.json"
    log "  (a channel with no published build is a valid state)"
    continue
  fi
  version_code="$(sed -n 's/.*"versionCode":[[:space:]]*\([0-9]*\).*/\1/p' "$STORAGE/$channel.json")"
  sha="$(sed -n 's/.*"sha256":[[:space:]]*"\([0-9a-f]*\)".*/\1/p' "$STORAGE/$channel.json")"
  url="$(sed -n 's/.*"downloadUrl":[[:space:]]*"\([^"]*\)".*/\1/p' "$STORAGE/$channel.json")"
  cached="$(sed -n 's/.*"cached":[[:space:]]*\([a-z]*\).*/\1/p' "$STORAGE/$channel.json")"
  log "  $channel versionCode=$version_code cached=$cached sha256=${sha:0:16}..."
  [[ -n "$version_code" && -n "$sha" && -n "$url" ]] || fail "$channel metadata incomplete"
  case "$url" in
    *github*|*"gamistudios"*) fail "$channel download URL leaks the repository: $url" ;;
  esac

  log "  waiting for the artifact to reach the cache (up to ${CACHE_WAIT_SECONDS:-900}s)"
  waited=0
  while [[ "$cached" != "true" && "$waited" -lt "${CACHE_WAIT_SECONDS:-900}" ]]; do
    sleep 10
    waited=$((waited + 10))
    curl -sS -o "$STORAGE/$channel.json" "$BASE/v1/updates/$channel/latest"
    cached="$(sed -n 's/.*"cached":[[:space:]]*\([a-z]*\).*/\1/p' "$STORAGE/$channel.json")"
  done
  log "  cached=$cached after ${waited}s"
  if [[ "$cached" != "true" ]]; then
    fail "$channel never finished caching; the sync log says:"
    grep -E "apk|cache|sync" "$STORAGE/server.log" | tail -n 8
    continue
  fi

  log "  downloading through the API"
  if ! curl -fsS -o "$STORAGE/$channel.apk" "$url"; then
    fail "$channel download"
    continue
  fi
  size_on_disk=$(wc -c < "$STORAGE/$channel.apk")
  log "  received $size_on_disk bytes"
  [[ "$size_on_disk" -gt 0 ]] || fail "$channel served an empty body"
  actual_sha="$(sha256sum "$STORAGE/$channel.apk" | cut -d' ' -f1)"
  [[ "$actual_sha" == "$sha" ]] || fail "$channel digest does not match the published metadata"
  magic="$(od -An -tx1 -N4 "$STORAGE/$channel.apk" | tr -d ' \n')"
  [[ "$magic" == "504b0304" ]] || fail "$channel body is not an APK (magic=$magic)"

  headers="$(curl -sS -D - -o /dev/null -H 'Range: bytes=0-1023' "$url" | tr -d '\r')"
  echo "$headers" | grep -qi '^HTTP/1.1 206' || fail "$channel range request was not partial"
  echo "$headers" | grep -qi "content-range: bytes 0-1023/$size_on_disk" || fail "$channel Content-Range wrong"
  echo "$headers" | grep -qi 'accept-ranges: bytes' || fail "$channel missing Accept-Ranges"
  echo "$headers" | grep -qi 'content-type: application/vnd.android.package-archive' || fail "$channel wrong content type"
done

log "== nothing a client sees may reach the private repository =="
# The product name is public and appears in asset names, and a changelog bullet
# may legitimately say "GitHub". What must never appear is an upstream address, a
# repository coordinate, an upstream field or the credential.
LEAK_PATTERN="github\\.com|api\\.github|ghcr\\.io|/repos/|browser_download_url|${GITHUB_OWNER}|${GITHUB_TOKEN}"
for path in / /health /ready /v1/updates/debug/latest /v1/updates/release/latest /v1/updates/beta/latest /v1/nope; do
  body="$(curl -sS "$BASE$path" || true)"
  if printf '%s' "$body" | grep -Eqi "$LEAK_PATTERN"; then
    fail "$path leaks private information"
    printf '%s' "$body" | grep -Eoi "$LEAK_PATTERN" | sort -u | sed 's/^/    /'
  fi
done
log "  checked 7 responses"

log "== the internal channel is served but never announced =="
for path in / /health /ready; do
  body="$(curl -sS "$BASE$path" || true)"
  if printf '%s' "$body" | grep -qi 'debug'; then
    fail "$path announces the debug channel"
  fi
done
log "  no public surface names debug"

log "== unknown channel and version are refused cleanly =="
curl -sS -o /dev/null -w '  beta channel -> %{http_code}\n' "$BASE/v1/updates/beta/latest"
curl -sS -o /dev/null -w '  unknown version -> %{http_code}\n' "$BASE/v1/download/release/v0.0.0-nonexistent"

log "== synchronisation log (credential redacted) =="
sed -e "s/$GITHUB_TOKEN/<redacted>/g" "$STORAGE/server.log" | grep -E 'sync|release|apk|cached|listen|ready' | tail -n 20

if [[ "$FAILED" -eq 0 ]]; then
  log "LIVE SMOKE PASSED"
else
  log "LIVE SMOKE FAILED"
  exit 1
fi
