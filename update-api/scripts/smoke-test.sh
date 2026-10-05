#!/usr/bin/env bash
# smoke-test.sh - run the whole update service offline against the mock GitHub.
#
# No credential and no network access to GitHub are needed: cmd/mockgithub serves
# two releases so that the debug channel's newest build differs from the release
# channel's, which is exactly the case a plain "latest release" lookup gets wrong.
set -euo pipefail

API_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$API_DIR/tmp-smoke"
MOCK_ADDR="127.0.0.1:18199"
PORT="${PORT:-18200}"
BASE="http://127.0.0.1:$PORT"
MOCK_BASE="http://$MOCK_ADDR"
FAILED=0
MOCK_PID=""
SERVER_PID=""

log() { printf '%s\n' "$*"; }
check() {
  local label="$1" condition="$2"
  if [[ "$condition" == "yes" ]]; then log "  ok   $label"; else log "  FAIL $label"; FAILED=1; fi
}
cleanup() {
  for pid in "$SERVER_PID" "$MOCK_PID"; do
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then kill "$pid" 2>/dev/null || true; fi
  done
}
trap cleanup EXIT

cd "$API_DIR"
rm -rf "$WORK"
mkdir -p "$WORK/data"

log "== build =="
go build -o "$WORK/mockgithub.exe" ./cmd/mockgithub
go build -o "$WORK/update-api.exe" ./cmd/server

log "== mock github on $MOCK_ADDR =="
"$WORK/mockgithub.exe" -addr "$MOCK_ADDR" > "$WORK/mock.log" 2>&1 &
MOCK_PID=$!

log "== service on $BASE =="
GITHUB_API_BASE_URL="$MOCK_BASE" GITHUB_TOKEN=mock-github-token GITHUB_OWNER=gamistudios \
  GITHUB_REPOSITORY=Awaki PORT="$PORT" STORAGE_PATH="$WORK/data" \
  SYNC_INTERVAL_SECONDS=15 LOG_FORMAT=text LOG_LEVEL=info \
  "$WORK/update-api.exe" > "$WORK/server.log" 2>&1 &
SERVER_PID=$!

for _ in $(seq 1 60); do
  curl -fsS "$BASE/ready" >/dev/null 2>&1 && break
  kill -0 "$SERVER_PID" 2>/dev/null || { log "server died; log:"; cat "$WORK/server.log"; exit 1; }
  sleep 1
done

field() { sed -n "s/.*\"$2\":[[:space:]]*\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p" "$1" | head -1; }
present() { grep -q "$2" "$1" && echo yes || echo no; }

log "== channel resolution is per channel, not globally newest =="
for channel in debug release; do
  curl -sS -o "$WORK/$channel.json" "$BASE/v1/updates/$channel/latest"
  tag="$(field "$WORK/$channel.json" tagName)"
  code="$(field "$WORK/$channel.json" versionCode)"
  log "  $channel -> $tag (versionCode $code)"
done
check "debug offers the newest debug build" "$([[ $(field "$WORK/debug.json" tagName) == v1.5.0 ]] && echo yes || echo no)"
check "release offers the newest release build" "$([[ $(field "$WORK/release.json" tagName) == v1.4.0 ]] && echo yes || echo no)"
check "the two channels differ" "$([[ $(field "$WORK/debug.json" tagName) != $(field "$WORK/release.json" tagName) ]] && echo yes || echo no)"
check "debug build is cached" "$(present "$WORK/debug.json" '"cached":true')"

log "== release notes keep the changelog and lose the private URL =="
check "changelog bullets kept" "$(present "$WORK/release.json" 'per-channel update API client')"
check "no github reference" "$(grep -qi 'github' "$WORK/release.json" && echo no || echo yes)"
check "no repository owner" "$(grep -q 'gamistudios' "$WORK/release.json" && echo no || echo yes)"

log "== version comparison answers the client =="
curl -sS -o "$WORK/check.json" "$BASE/v1/updates/release/latest?versionCode=10300"
check "an old build is told to update" "$(present "$WORK/check.json" '"updateAvailable":true')"
curl -sS -o "$WORK/check.json" "$BASE/v1/updates/release/latest?versionCode=10400"
check "the current build is not" "$(present "$WORK/check.json" '"updateAvailable":false')"
status="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/v1/updates/beta/latest")"
check "an unknown channel is a 400 (got $status)" "$([[ "$status" == 400 ]] && echo yes || echo no)"

log "== downloads are served from the cache, with resume support =="
for channel in debug release; do
  url="$(field "$WORK/$channel.json" downloadUrl)"
  sha="$(field "$WORK/$channel.json" sha256)"
  curl -fsS -o "$WORK/$channel.apk" "$url"
  size=$(wc -c < "$WORK/$channel.apk")
  actual="$(sha256sum "$WORK/$channel.apk" | cut -d' ' -f1)"
  check "$channel served $size bytes" "$([[ "$size" -gt 0 ]] && echo yes || echo no)"
  check "$channel digest matches the metadata" "$([[ "$actual" == "$sha" ]] && echo yes || echo no)"
  check "$channel body is an APK" "$([[ "$(od -An -tx1 -N4 "$WORK/$channel.apk" | tr -d ' \n')" == 504b0304 ]] && echo yes || echo no)"
  headers="$(curl -sS -D - -o /dev/null -H "Range: bytes=$((size / 2))-$((size - 1))" "$url" | tr -d '\r')"
  check "$channel range request is partial" "$(printf '%s' "$headers" | grep -qi 'HTTP/1.1 206' && echo yes || echo no)"
  check "$channel advertises ranges" "$(printf '%s' "$headers" | grep -qi 'accept-ranges: bytes' && echo yes || echo no)"
done

log "== a download never touches the upstream =="
before="$(grep -c 'mock github request.*releases/assets' "$WORK/mock.log" || true)"
for _ in 1 2 3; do curl -fsS -o /dev/null "$BASE/v1/download/debug/latest"; done
sleep 1
after="$(grep -c 'mock github request.*releases/assets' "$WORK/mock.log" || true)"
check "three client downloads caused no upstream fetch" "$([[ "$before" == "$after" ]] && echo yes || echo no)"

log "== github goes away; clients keep working =="
kill "$MOCK_PID" 2>/dev/null || true
wait "$MOCK_PID" 2>/dev/null || true
MOCK_PID=""
sleep 20
tag="$(curl -sS "$BASE/v1/updates/release/latest" | sed -n 's/.*"tagName":[[:space:]]*"\([^"]*\)".*/\1/p')"
check "the last known good release is still offered (got ${tag:-none})" "$([[ "$tag" == v1.4.0 ]] && echo yes || echo no)"
status="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/v1/download/release/latest")"
check "and still downloadable (HTTP $status)" "$([[ "$status" == 200 ]] && echo yes || echo no)"
status="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/ready")"
check "the instance stays ready through the outage (HTTP $status)" "$([[ "$status" == 200 ]] && echo yes || echo no)"
check "the outage is visible in the logs" "$(grep -q 'github unavailable' "$WORK/server.log" && echo yes || echo no)"

log "== no response mentions the upstream at all =="
leaked=no
for path in / /health /ready /v1/updates/debug/latest /v1/updates/release/latest /v1/updates/beta/latest /v1/nope /v1/download/release/v9.9.9; do
  body="$(curl -sS "$BASE$path" || true)"
  printf '%s' "$body" | grep -Eqi 'github|mockgithub|/repos/|browser_download_url' && leaked=yes
done
check "7 responses scanned, none leak" "$([[ "$leaked" == no ]] && echo yes || echo no)"

log "== the internal channel is served but never announced =="
announced=no
for path in / /health /ready; do
  curl -sS "$BASE$path" | grep -qi 'debug' && announced=yes
done
check "no public surface names the debug channel" "$([[ "$announced" == no ]] && echo yes || echo no)"
status="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/v1/updates/debug/latest")"
check "the debug route still answers (HTTP $status)" "$([[ "$status" == 200 ]] && echo yes || echo no)"
body="$(curl -sS "$BASE/v1/updates/beta/latest")"
check "a mistyped channel does not name the valid ones" "$(printf '%s' "$body" | grep -qi debug && echo no || echo yes)"

log "== structured logs carry no secret =="
check "the token never reaches the log" "$(grep -q 'mock-github-token' "$WORK/server.log" && echo no || echo yes)"

log ""
if [[ "$FAILED" -eq 0 ]]; then log "SMOKE TEST PASSED"; else log "SMOKE TEST FAILED"; exit 1; fi
