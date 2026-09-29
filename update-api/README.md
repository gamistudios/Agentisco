# Agentisco Update API

The public face of Agentisco's self-update pipeline. Android clients ask this
service for the newest build of their channel and download the APK from it; they
never learn — and can never be told — where the artifacts actually come from.

The upstream release source is a **private** repository. This module is the only
thing about it that is public:

- It is the only place the upstream credential, owner and repository name exist at
  runtime, and all three stay on the server.
- Nothing a client can read — a JSON document, a log line, an error message, a
  redirect, a cached metadata row or the landing page — can carry an upstream
  address, a repository coordinate, an upstream field name or the token.
- Clients get one stable origin: `https://agentisco.onrender.com`.

The module is deliberately separate from the Android app: it is a standalone Go
program with its own `go.mod`, so it is never part of the APK build and deploys on
its own.

## What it does

```
GitHub releases (private)                    this service                     app / browser
        |                                        |                                 |
        |  <---- background sync, every 60s -----|                                 |
        |      list once, pick per channel       |                                 |
        |      download each APK once            |                                 |
        |      verify SHA-256 + APK header       |                                 |
        |                                        |                                 |
        |                    GET /v1/updates/{channel}/latest  <--------------------+
        |                    GET /v1/download/{channel}/{tag}  <--------------------+
        |                                        |                    served from cache
```

1. **Sync.** Every `SYNC_INTERVAL_SECONDS` one listing of the releases is fetched
   and shared by both channels. For each channel the newest release that really
   carries that channel's APK asset becomes the current release — `debug` never
   falls back to "whatever was published last", and neither does `release`.
2. **Cache.** Each release's APK is downloaded **once**, into staging, hashed, and
   only then moved into the content-addressed object store
   (`apks/<channel>/<sha256>.apk`). A download that never completes leaves nothing
   a client could be served. Old releases are pruned; the last five stay available
   so an app that is behind can still fetch the build it started downloading.
3. **Serve.** Metadata and bytes come from the local cache. When the upstream is
   unreachable the service keeps answering with the last known good release —
   a GitHub outage cannot stop already-installed apps from updating to a build
   that is already here. A channel whose bytes are not cached yet answers a
   download with `503` + `Retry-After` instead of proxying the upstream, and
   `/ready` says so.
4. **Stream.** APK responses carry `Content-Type`, `Content-Length`, `Content-Disposition`,
   `Last-Modified`, an `ETag` equal to the SHA-256, `Accept-Ranges: bytes` and full
   `Range`/`If-Range` support, so the app's resumable download works across dropped
   connections.

## Channels

| Channel  | Resolved as                                                   | Who uses it |
|----------|---------------------------------------------------------------|-------------|
| `release`| Newest valid release carrying `Agentisco-<tag>-release.apk`    | Everyone    |
| `debug`  | Newest valid release carrying `Agentisco-<tag>-debug.apk`      | Team builds only |

Both channels are routed and served identically. The difference is visibility:
`debug` is **never announced** on a public surface. `/` , `/health`, `/ready`, the
landing page and the OpenAPI document name only the release channel, and an invalid
channel error never lists the supported names. Knowing the debug URL is the only
thing that grants access to it, which is the current intent — it is not an access
control. Add real authorisation to `debug` before distributing debug builds outside
the team.

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/v1/updates/{channel}/latest` | The channel's current update document (JSON) |
| `GET` | `/v1/download/{channel}/latest` | The APK for that document, streamed from cache |
| `GET` | `/v1/download/{channel}/{tag}` | The APK of one specific tag, for a resumed download |
| `GET` | `/health` | Liveness: process up, sync scheduled |
| `GET` | `/ready` | Readiness: at least one channel has a current release |
| `GET` | `/` | Landing page when enabled, otherwise a short JSON index |

`UpdateInfo` — the body of `/v1/updates/...` — is what the Android client parses:

```json
{
  "channel": "release",
  "versionCode": 20023,
  "versionName": "Agentisco v2.0.23",
  "tagName": "v2.0.23",
  "apkName": "Agentisco-v2.0.23-release.apk",
  "size": 70161981,
  "sha256": "82a6e7bc…",
  "releaseNotes": "## What's Changed\n…",
  "publishedAt": "2026-09-22T08:45:19Z",
  "syncedAt": "2026-09-29T15:07:33Z",
  "downloadUrl": "https://agentisco.onrender.com/v1/download/release/v2.0.23",
  "cached": true
}
```

## Configuration

Everything is environment-driven; `.env.example` is the annotated reference and the
file a local `.env` should be copied from. The process refuses to start — with a
message naming the missing variable, never a value — rather than booting in a state
where it would silently serve nothing.

Required:

| Variable | Meaning |
|----------|---------|
| `GITHUB_TOKEN` | Fine-grained PAT (or equivalent) with read access to the repository's releases. Server-side only. |
| `GITHUB_OWNER` | Owner of the private release repository. |
| `GITHUB_REPOSITORY` | Repository name (`GITHUB_REPO` is accepted as an alias). |

Common optional:

| Variable | Default | Meaning |
|----------|---------|---------|
| `GITHUB_API_BASE_URL` | `https://api.github.com` | Override to point at a mock during local testing. |
| `GITHUB_TIMEOUT_SECONDS` | `20` | Deadline for metadata calls (listing, release JSON). Never applied to the APK transfer. |
| `RELEASES_PER_SYNC` | `30` | How far back one listing reaches. |
| `SYNC_INTERVAL_SECONDS` | `60` | Period between passes. Minimum 5. |
| `SYNC_STARTUP_DELAY_SECONDS` | `0` | Wait before the first pass. |
| `DOWNLOAD_TIMEOUT_SECONDS` | `3600` | Budget for one whole pass including the APK transfer. The artifacts are tens of megabytes and the app ships to regions with poor links, so the default is an hour. Must exceed `GITHUB_TIMEOUT_SECONDS`. |
| `STALL_TIMEOUT_SECONDS` | `30` | Abandon a transfer only when **no bytes at all** arrive for this long — that is what separates a slow link from a dead one. Must be smaller than `DOWNLOAD_TIMEOUT_SECONDS`. |
| `STORAGE_PATH` | `./data` | Writable directory for the SQLite metadata database and the cached APKs. |
| `PORT` | `8080` | Listen port. On Render the platform injects it. |
| `PUBLIC_BASE_URL` | empty | Absolute origin used to build `downloadUrl`. Leave empty behind a PaaS that assigns the host; then the request's own `Host`/forwarded host is used. |
| `READ_TIMEOUT_SECONDS` / `WRITE_TIMEOUT_SECONDS` / `IDLE_TIMEOUT_SECONDS` | `30` / `0` / `120` | HTTP server timeouts. `WRITE_TIMEOUT_SECONDS` stays `0` on purpose: a response write must not be cut off in the middle of a 70 MB APK. |
| `RATE_LIMIT_RPS` / `RATE_LIMIT_BURST` | `10` / `40` | Per-client token bucket. The client key honours `X-Forwarded-For` so requests behind a proxy are not all counted as the proxy. |
| `ENABLE_LANDING` | `true` | Serve the landing page at `/`. |
| `LOG_LEVEL` / `LOG_FORMAT` | `info` / `json` | Structured logging to stdout. |
| `ENV_FILE` | `.env` | Alternative location for the local env file. |

Real environment variables always win over `.env`, so a deployed container cannot be
altered by a stale file baked into the image.

Release notes are copied from the release body with one transformation: the
auto-generated `**Full Changelog**: https://github.com/<owner>/<repo>/compare/…`
line is rewritten to `**Full Changelog**: v2.0.22...v2.0.23`, and any other upstream
URL in a body is reduced to its path-free form. The service sanitises what it caches,
so the text that reaches an app can never name the repository.

## Running it locally

No credential is needed to exercise the whole state machine — the mock serves two
releases whose newest debug build differs from their newest release build, which is
precisely the case a plain "latest release" lookup gets wrong:

```bash
cd update-api
./scripts/smoke-test.sh          # offline: mock upstream, 30+ assertions
```

To drive the real repository (every upstream call is a read; nothing writes):

```bash
cd update-api
cp .env.example .env             # fill in GITHUB_TOKEN / OWNER / REPOSITORY
./scripts/live-smoke.sh          # starts the server, waits for both channels to cache
```

`live-smoke.sh` polls until each channel reports `"cached": true` before it checks the
download, because on a cold container the first pass is a real 70 MB transfer; give it
`CACHE_WAIT_SECONDS` (default 900) and let it finish. It then verifies the digest, the
APK magic, a ranged resume, that no request reaches the upstream during a client
download, that the last known good release keeps serving during a simulated outage,
and that neither the debug channel nor any repository trace appears in a response.

Manually:

```bash
go run ./cmd/mockgithub -addr 127.0.0.1:18199 &
GITHUB_API_BASE_URL=http://127.0.0.1:18199 GITHUB_TOKEN=x GITHUB_OWNER=x GITHUB_REPO=x \
  STORAGE_PATH=./data PORT=18200 go run ./cmd/server

curl -s localhost:18200/v1/updates/release/latest | jq
curl -s -o /dev/null -D - -r 0-1023 localhost:18200/v1/download/release/latest
```

## Tests

```bash
go vet ./... && go test ./...          # unit + in-process integration
./scripts/smoke-test.sh                # end-to-end, offline, against cmd/mockgithub
./scripts/live-smoke.sh                # end-to-end against the real upstream
```

## Deployment

`update-api` is its own deployable; the Android app is not involved and nothing here
is ever compiled into the APK.

```bash
cd update-api
docker build -t agentisco-update-api .
docker run --rm -p 8080:8080 \
  -e GITHUB_TOKEN=… -e GITHUB_OWNER=… -e GITHUB_REPOSITORY=… \
  -v "$PWD/data:/data" \
  agentisco-update-api
```

The image is multi-stage: a static `CGO_ENABLED=0` binary, then
`gcr.io/distroless/static-debian12:nonroot` with only the binary and the CA bundle.
No shell, no package manager, nothing listening but the API, running as uid 65532.

### Render

Live at `https://agentisco.onrender.com`, which is the origin the app is built against
(`app/build.gradle.kts` → `BuildConfig.UPDATE_API_BASE_URL`, overridable with the
`UPDATE_API_BASE_URL` environment variable at build time).

- **Root directory:** the *repository* root, with the build and start commands pointing
  into this module — the Docker runtime with `update-api/Dockerfile`, or
  `go build -o update-api ./cmd/server` / `./update-api` with a pre-build command of
  `cd update-api`.
- **Port:** Render injects `PORT` and the service listens on it. Leave
  `PUBLIC_BASE_URL` empty and `downloadUrl` is built from the request's own host, so a
  preview environment serves its own URLs instead of lying about the origin.
- **Secrets:** set `GITHUB_TOKEN`, `GITHUB_OWNER` and `GITHUB_REPOSITORY` in the service
  environment. Never commit a `.env` (it is git-ignored), and remember that real
  environment variables always beat a file.
- **Health checks:** liveness on `/health` (green whenever the process is up), readiness
  on `/ready`. `/ready` reports ready once *any* channel has a current release, so a
  debug-only publish cannot crash-loop the platform while the public channel serves.

### Storage

`STORAGE_PATH` holds the SQLite metadata database (`metadata.db`, WAL mode) and the
cached artifacts (`apks/<channel>/<sha256>.apk`). It must be writable, and it should be
persistent:

- On a persistent volume the cache survives restarts, and a release is fetched from the
  upstream exactly once, ever.
- On an ephemeral filesystem (Render's free tier, a bare container) every cold start
  re-downloads the current APK of each channel — roughly 150 MB, which is the slowest
  part of booting a cold instance. Attach a Render Disk or equivalent at `/data` and
  set `STORAGE_PATH=/data`, or accept the re-download and keep
  `DOWNLOAD_TIMEOUT_SECONDS` generous enough to finish it.
- S3/R2-compatible storage is the planned alternative when a disk is not available; the
  object store is already an interface (`internal/objectstore`) with the filesystem as
  its only implementation.

Nothing stored there is secret, but it does hold release notes and artifact bytes:
treat the directory like a copy of the release feed, and never serve it directly.

## Security posture

- The token is only ever used as an `Authorization` header on upstream calls. It never
  appears in a log line, an error message, a response, a redirect or the database; sync
  failures are stored as a category plus a sanitised message.
- Everything a client can read goes through the sanitiser before it is cached: upstream
  URLs collapse to a path-free form and the repository coordinate never leaves the
  server. `**Full Changelog**: https://github.com/<owner>/<repo>/compare/v2.0.22...v2.0.23`
  is published as `**Full Changelog**: v2.0.22...v2.0.23`.
- Errors answer with a category and a safe message — no stack trace, no internal path,
  no upstream name.
- A per-client token bucket (`RATE_LIMIT_RPS`, `RATE_LIMIT_BURST`) guards the metadata
  and download routes, keyed on the forwarded client address so requests behind a proxy
  are not all counted as the proxy's.
- Responses carry `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` and
  `Referrer-Policy: no-referrer`; the landing page only ever calls same-origin API
  routes.
- Methods are GET/HEAD, and an unknown path or channel is refused without enumerating
  what exists.

This is not authentication. The debug channel is *unadvertised*, not access-controlled:
anyone who guesses `/v1/updates/debug/latest` can use it, which is the intended
arrangement while only the team holds debug builds. Add authorisation there before debug
builds leave the team.

## Operations

| Symptom | What it means |
|---------|---------------|
| `/ready` shows a channel with no current release | Its first pass has not finished — on a cold instance that is the initial download. Watch the `sync` log lines. |
| `/v1/download/...` answers `503` with `Retry-After` | The release is known but its bytes are not cached here yet. Metadata is deliberately published *before* the transfer starts, so version comparison never waits on a download. |
| `last_result: upstream_unavailable` | Listing or download failed; the previous release keeps serving. Backoff is exponential and honours `Retry-After`. |
| `cached: false` for a long time | Transfers keep stalling. Compare `STALL_TIMEOUT_SECONDS` with the instance's real throughput. |
| A resumed download produces duplicate bytes | Cannot happen: a `Range` request answered with a full body is reported as such, and the app restarts the file from zero. |
| Disk usage grows | Releases are pruned to the five most recent per channel, and a prune failure is logged rather than swallowed. |

Logs are structured JSON on stdout — nothing to rotate. Useful fields: `channel`, `tag`,
`result`, `cached`, `bytes`, `attempt`, `error`.

## Android side

- `UpdateReleaseSource.kt` — `HttpUpdateReleaseSource` reads
  `/v1/updates/<channel>/latest` for the channel matching the build type and parses the
  document into `UpdateRelease`.
- `UpdateRepository.kt` — behaviour is unchanged: it compares `versionCode`, shows the
  service's release notes, downloads with resume through `UpdateStreamSource`, and only
  offers Install after `UpdateDownloadVerifier` confirms the exact size, the SHA-256 and
  the APK signature.

The version-code rule (`major*10000 + minor*100 + patch`) is applied by the service to
the same tag the app used to parse itself, and the app still falls back to parsing the
tag when a deployment reports no `versionCode`, so an older server can never offer a
downgrade.

## Layout

```
cmd/server/           process: config, logger, store, syncer, HTTP server, shutdown
cmd/mockgithub/       offline upstream for scripts/smoke-test.sh
internal/config/      environment loading and validation
internal/ghapi/       upstream client: metadata + bounded stream, backoff, sanitising
internal/ghfake/      in-process fake of that API, used by the tests
internal/metastore/   SQLite metadata: releases, sync status, object references
internal/model/       channels, release types, the public UpdateInfo document
internal/notes/       release-note parsing and sanitising
internal/objectstore/ storage interface + filesystem implementation (staging, atomic publish, prune)
internal/ratelimit/   token bucket keyed on client address
internal/syncsvc/     one pass: list once, resolve per channel, download once, verify, publish
internal/version/     version-code computation, shared rule with the app
web/                  embedded landing page and the app's launcher icon
scripts/              smoke-test.sh (offline), live-smoke.sh (real upstream)
openapi.yaml          the public contract
Dockerfile            distroless nonroot image
```



