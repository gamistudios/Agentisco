package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"agentisco/updateapi/internal/ghapi"
	"agentisco/updateapi/internal/ghfake"
	"agentisco/updateapi/internal/metastore"
	"agentisco/updateapi/internal/model"
	"agentisco/updateapi/internal/notes"
	"agentisco/updateapi/internal/objectstore"
	"agentisco/updateapi/internal/ratelimit"
	"agentisco/updateapi/internal/syncsvc"
)

const e2eToken = "super-secret-e2e-token"

// stack is a full service: fake GitHub, real syncer, real SQLite, real files,
// real HTTP. Every behaviour the spec promises is asserted through this.
type stack struct {
	t        *testing.T
	fake     *ghfake.Server
	meta     *metastore.DB
	objects  objectstore.Store
	syncer   *syncsvc.Syncer
	server   *httptest.Server
	root     string
	public   string
	limiter  *ratelimit.Limiter
	settings *Config
}

func newStack(t *testing.T, fake *ghfake.Server) *stack {
	t.Helper()
	return newStackWith(t, fake, nil)
}

// newStackWith builds the service; configure may adjust the exposed API surface
// (landing page, public base URL, rate limits) before it starts serving.
func newStackWith(t *testing.T, fake *ghfake.Server, configure func(*Config)) *stack {
	t.Helper()

	fakeServer := httptest.NewServer(fake)
	t.Cleanup(fakeServer.Close)

	root := t.TempDir()
	meta, err := metastore.Open(filepath.Join(root, "metadata.db"))
	if err != nil {
		t.Fatalf("open metastore: %v", err)
	}
	t.Cleanup(func() { _ = meta.Close() })

	objects, err := objectstore.New(filepath.Join(root, "objects"))
	if err != nil {
		t.Fatalf("open object store: %v", err)
	}

	client := ghapi.New(ghapi.Options{
		APIBase:  fakeServer.URL,
		Owner:    "gamistudios",
		Repo:     "Agentisco",
		Token:    e2eToken,
		Timeout:  5 * time.Second,
		MaxItems: 30,
	})
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	syncer := syncsvc.New(syncsvc.Options{
		Client:      client,
		Meta:        meta,
		Objects:     objects,
		Sanitizer:   notes.NewSanitizer("gamistudios", "Agentisco"),
		Logger:      logger,
		Interval:    time.Hour,
		PassTimeout: 20 * time.Second,
	})

	s := &stack{
		t:        t,
		fake:     fake,
		meta:     meta,
		objects:  objects,
		syncer:   syncer,
		root:     root,
		public:   "https://updates.agentisco.test",
		settings: &Config{},
	}
	s.limiter = ratelimit.NewLimiter(1000, 1000)
	if configure != nil {
		configure(s.settings)
	}

	httpServer := httptest.NewServer(s.handler())
	t.Cleanup(httpServer.Close)
	s.server = httpServer
	return s
}

func (s *stack) handler() http.Handler {
	cfg := *s.settings
	cfg.Meta = s.meta
	cfg.Objects = s.objects
	cfg.Sync = s.syncer
	if cfg.Limiter == nil {
		cfg.Limiter = s.limiter
	}
	cfg.Logger = s.log()
	if cfg.PublicBase == "" {
		cfg.PublicBase = s.public
	}
	if cfg.SyncInterval == 0 {
		cfg.SyncInterval = s.syncer.Interval()
	}
	return New(cfg).Handler()
}

func (s *stack) log() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func (s *stack) syncAll() {
	s.t.Helper()
	for _, channel := range model.Channels {
		s.syncer.SyncChannel(context.Background(), channel)
	}
}

// get performs a request and fails the test on transport errors.
func (s *stack) get(path string, header map[string]string) *http.Response {
	s.t.Helper()
	req, err := http.NewRequest(http.MethodGet, s.server.URL+path, nil)
	if err != nil {
		s.t.Fatalf("build request: %v", err)
	}
	for key, value := range header {
		req.Header.Set(key, value)
	}
	resp, err := s.server.Client().Do(req)
	if err != nil {
		s.t.Fatalf("GET %s: %v", path, err)
	}
	s.t.Cleanup(func() { resp.Body.Close() })
	return resp
}

func readAll(t *testing.T, resp *http.Response) []byte {
	t.Helper()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	return body
}

// decodeInfo asserts the response is free of anything private, then decodes it.
func decodeInfo(t *testing.T, resp *http.Response) model.UpdateInfo {
	t.Helper()
	body := readAll(t, resp)
	assertNothingPrivate(t, "metadata body", body)
	var info model.UpdateInfo
	if err := json.Unmarshal(body, &info); err != nil {
		t.Fatalf("decode update info: %v\n%s", err, body)
	}
	return info
}

func decodeCheck(t *testing.T, resp *http.Response) model.CheckResult {
	t.Helper()
	body := readAll(t, resp)
	assertNothingPrivate(t, "check body", body)
	var check model.CheckResult
	if err := json.Unmarshal(body, &check); err != nil {
		t.Fatalf("decode check result: %v\n%s", err, body)
	}
	return check
}

func decodeErrorCode(t *testing.T, resp *http.Response) string {
	t.Helper()
	var payload struct {
		Error errorDetail `json:"error"`
	}
	if err := json.Unmarshal(readAll(t, resp), &payload); err != nil {
		t.Fatalf("decode error payload: %v", err)
	}
	return payload.Error.Code
}

// seedCIRelease publishes a release shaped exactly like the one CI produces: CI
// naming for the assets plus the changelog line with a private compare URL.
func (s *stack) seedCIRelease(tag string, published time.Time, channels ...model.Channel) map[model.Channel][]byte {
	s.t.Helper()
	assets := make([]ghfake.AssetInput, 0, len(channels))
	payloads := map[model.Channel][]byte{}
	for _, channel := range channels {
		content := ghfake.SyntheticAPK(tag+"/"+channel.String(), 2048+len(tag))
		payloads[channel] = content
		assets = append(assets, ghfake.AssetInput{
			Name:  fmt.Sprintf("Agentisco-%s-%s.apk", tag, channel.String()),
			Bytes: content,
		})
	}
	if len(channels) > 0 {
		assets = append(assets, ghfake.AssetInput{
			Name:  fmt.Sprintf("Agentisco-%s-release.aab", tag),
			Bytes: []byte("play store bundle"),
		})
	}
	s.fake.AddRelease(tag, tag, fmt.Sprintf(
		"## What's Changed\n\n* change for %s\n\n**Full Changelog**: https://github.com/gamistudios/Agentisco/compare/v0.0.0...%s",
		tag, tag), published, assets...)
	return payloads
}

// forbidden lists everything a public response must never contain.
var forbidden = []string{
	e2eToken,
	"gamistudios",
	"github.com",
	"api.github",
	"/repos/",
	"browser_download_url",
}

func assertNothingPrivate(t *testing.T, label string, content []byte) {
	t.Helper()
	text := string(content)
	for _, needle := range forbidden {
		if strings.Contains(strings.ToLower(text), strings.ToLower(needle)) {
			t.Fatalf("%s leaks %q: %s", label, needle, truncate(text, 400))
		}
	}
}

func TestLatestResolvesEachChannelIndependently(t *testing.T) {
	s := newStack(t, ghfake.New(e2eToken))
	older := s.seedCIRelease("v1.4.0", time.Now().Add(-72*time.Hour),
		model.ChannelDebug, model.ChannelRelease)
	s.seedCIRelease("v1.5.0", time.Now(), model.ChannelDebug)
	s.syncAll()

	debugResp := s.get("/v1/updates/debug/latest", nil)
	if debugResp.StatusCode != http.StatusOK {
		t.Fatalf("debug latest status %d", debugResp.StatusCode)
	}
	debugInfo := decodeInfo(t, debugResp)
	if debugInfo.TagName != "v1.5.0" || debugInfo.VersionCode != 10500 {
		t.Fatalf("debug channel resolved to %+v", debugInfo)
	}

	releaseResp := s.get("/v1/updates/release/latest", nil)
	releaseInfo := decodeInfo(t, releaseResp)
	if releaseInfo.TagName != "v1.4.0" || releaseInfo.VersionCode != 10400 {
		t.Fatalf("release channel must ignore the debug-only publish, got %+v", releaseInfo)
	}
	if !releaseInfo.Cached || !debugInfo.Cached {
		t.Fatalf("both current apks must be cached: debug=%v release=%v", debugInfo.Cached, releaseInfo.Cached)
	}
	if releaseInfo.SHA256 != digestOf(older[model.ChannelRelease]) {
		t.Fatalf("published digest mismatch: %s", releaseInfo.SHA256)
	}
	if releaseInfo.Size != int64(len(older[model.ChannelRelease])) {
		t.Fatalf("published size mismatch: %d", releaseInfo.Size)
	}
	if !strings.Contains(releaseInfo.ReleaseNotes, "change for v1.4.0") {
		t.Fatalf("changelog bullets lost: %q", releaseInfo.ReleaseNotes)
	}
	if strings.Contains(releaseInfo.ReleaseNotes, "compare/v0.0.0") {
		t.Fatalf("private compare link survived sanitising: %q", releaseInfo.ReleaseNotes)
	}
	if debugInfo.DownloadURL != s.public+"/v1/download/debug/v1.5.0" {
		t.Fatalf("download url must be public, absolute and pinned: %s", debugInfo.DownloadURL)
	}
	if got := releaseResp.Header.Get("Cache-Control"); got != "no-store" {
		t.Fatalf("metadata must not be cached by intermediaries, got %q", got)
	}
	if got := releaseResp.Header.Get("X-Content-Type-Options"); got != "nosniff" {
		t.Fatalf("security header missing: %q", got)
	}
}

func TestVersionCodeCheckAnswersTheClient(t *testing.T) {
	s := newStack(t, ghfake.New(e2eToken))
	s.seedCIRelease("v1.4.0", time.Now(), model.ChannelRelease)
	s.syncAll()

	outdated := decodeCheck(t, s.get("/v1/updates/release/latest?versionCode=10300", nil))
	if !outdated.UpdateAvailable || outdated.LatestVersionCode != 10400 || outdated.CurrentVersionCode != 10300 {
		t.Fatalf("client behind the current release must be told: %+v", outdated)
	}
	if outdated.DownloadURL == "" || outdated.TagName != "v1.4.0" {
		t.Fatalf("check result must carry the download: %+v", outdated)
	}

	uptodate := decodeCheck(t, s.get("/v1/updates/release/latest?versionCode=10400", nil))
	if uptodate.UpdateAvailable {
		t.Fatal("an installed build equal to the current release must not update")
	}

	invalid := s.get("/v1/updates/release/latest?versionCode=not-a-number", nil)
	if invalid.StatusCode != http.StatusBadRequest {
		t.Fatalf("bad versionCode status %d", invalid.StatusCode)
	}
	if code := decodeErrorCode(t, invalid); code != "invalid_version_code" {
		t.Fatalf("error code %q", code)
	}

	negative := s.get("/v1/updates/release/latest?versionCode=-5", nil)
	if negative.StatusCode != http.StatusBadRequest {
		t.Fatalf("negative versionCode must be refused, got %d", negative.StatusCode)
	}
}

func TestChannelValidationAndUnknownRoutes(t *testing.T) {
	s := newStack(t, ghfake.New(e2eToken))
	s.seedCIRelease("v1.4.0", time.Now(), model.ChannelDebug)
	s.syncAll()

	badChannel := s.get("/v1/updates/beta/latest", nil)
	if badChannel.StatusCode != http.StatusBadRequest {
		t.Fatalf("unknown channel answered %d", badChannel.StatusCode)
	}
	if code := decodeErrorCode(t, badChannel); code != "invalid_channel" {
		t.Fatalf("error code %q", code)
	}

	// Upper case is a documented channel spelling, so it must keep working.
	if resp := s.get("/v1/updates/RELEASE/latest", nil); resp.StatusCode != http.StatusNotFound {
		t.Fatalf("release channel has no published build, answered %d", resp.StatusCode)
	}

	missingSegment := s.get("/v1/updates/latest", nil)
	if missingSegment.StatusCode != http.StatusNotFound {
		t.Fatalf("malformed route answered %d", missingSegment.StatusCode)
	}

	traversal := s.get("/v1/download/release/"+url.PathEscape("../../etc/passwd"), nil)
	if traversal.StatusCode != http.StatusNotFound {
		t.Fatalf("traversal download answered %d", traversal.StatusCode)
	}
	assertNothingPrivate(t, "unknown version body", readAll(t, traversal))

	unknown := s.get("/v1/internal/anything", nil)
	if unknown.StatusCode != http.StatusNotFound {
		t.Fatalf("unknown route answered %d", unknown.StatusCode)
	}
	assertNothingPrivate(t, "404 body", readAll(t, unknown))

	post := s.post("/v1/updates/debug/latest")
	if post.StatusCode >= http.StatusMethodNotAllowed && post.StatusCode != http.StatusMethodNotAllowed &&
		post.StatusCode != http.StatusNotFound {
		t.Fatalf("POST answered %d", post.StatusCode)
	}
}

func (s *stack) post(path string) *http.Response {
	s.t.Helper()
	req, err := http.NewRequest(http.MethodPost, s.server.URL+path, strings.NewReader(""))
	if err != nil {
		s.t.Fatalf("build request: %v", err)
	}
	resp, err := s.server.Client().Do(req)
	if err != nil {
		s.t.Fatalf("POST %s: %v", path, err)
	}
	s.t.Cleanup(func() { resp.Body.Close() })
	return resp
}

func digestOf(content []byte) string {
	sum := sha256.Sum256(content)
	return hex.EncodeToString(sum[:])
}

// download fetches a channel's APK and returns the raw bytes.
func (s *stack) download(channel model.Channel, version string, header map[string]string) *http.Response {
	s.t.Helper()
	return s.get("/v1/download/"+channel.String()+"/"+url.PathEscape(version), header)
}

func TestDownloadStreamsTheCachedApkAndHonoursRanges(t *testing.T) {
	fake := ghfake.New(e2eToken)
	s := newStack(t, fake)
	payloads := s.seedCIRelease("v1.4.0", time.Now(), model.ChannelDebug, model.ChannelRelease)
	s.syncAll()

	expected := payloads[model.ChannelDebug]
	full := s.download(model.ChannelDebug, "latest", nil)
	if full.StatusCode != http.StatusOK {
		t.Fatalf("cached download answered %d", full.StatusCode)
	}
	body := readAll(t, full)
	assertNothingPrivate(t, "apk stream headers", headersText(full))

	if int64(len(body)) != int64(len(expected)) {
		t.Fatalf("served %d bytes, published %d", len(body), len(expected))
	}
	if digestOf(body) != digestOf(expected) {
		t.Fatal("served bytes differ from the published artifact")
	}
	if got := full.Header.Get("Content-Type"); got != apkContentType {
		t.Fatalf("content type %q", got)
	}
	if got := full.Header.Get("Content-Length"); got != strconv.Itoa(len(expected)) {
		t.Fatalf("content length %q, want %d", got, len(expected))
	}
	if got := full.Header.Get("Accept-Ranges"); got != "bytes" {
		t.Fatalf("accept ranges %q", got)
	}
	etag := full.Header.Get("ETag")
	if etag != `"`+digestOf(expected)+`"` {
		t.Fatalf("etag %q must be the content address", etag)
	}
	if got := full.Header.Get("Content-Disposition"); !strings.Contains(got, "Agentisco-v1.4.0-debug.apk") {
		t.Fatalf("content disposition %q", got)
	}
	if got := full.Header.Get("Cache-Control"); !strings.Contains(got, "immutable") {
		t.Fatalf("a pinned artifact must be immutable to caches, got %q", got)
	}
	if full.Header.Get("Last-Modified") == "" {
		t.Fatal("Last-Modified missing, so a resumed client cannot validate its partial file")
	}

	// Resume: exactly the range the Android client asks for.
	partial := s.download(model.ChannelDebug, "latest", map[string]string{"Range": "bytes=1000-1999"})
	if partial.StatusCode != http.StatusPartialContent {
		t.Fatalf("range request answered %d", partial.StatusCode)
	}
	chunk := readAll(t, partial)
	if string(chunk) != string(expected[1000:2000]) {
		t.Fatalf("range body wrong (%d bytes)", len(chunk))
	}
	if got := partial.Header.Get("Content-Range"); got != fmt.Sprintf("bytes 1000-1999/%d", len(expected)) {
		t.Fatalf("content range %q", got)
	}

	// A validator that matches turns the request into a 304.
	unchanged := s.download(model.ChannelDebug, "latest", map[string]string{"If-None-Match": etag})
	if unchanged.StatusCode != http.StatusNotModified {
		t.Fatalf("matching ETag answered %d, want 304", unchanged.StatusCode)
	}

	// A stale If-Range must restart the whole file rather than splice bytes.
	stale := s.download(model.ChannelDebug, "latest", map[string]string{
		"Range":    "bytes=1000-",
		"If-Range": `"deadbeef"`,
	})
	if stale.StatusCode != http.StatusOK {
		t.Fatalf("stale validator answered %d, want a full restart", stale.StatusCode)
	}
	if len(readAll(t, stale)) != len(expected) {
		t.Fatal("restart must send the whole artifact")
	}
}

func TestDownloadDoesNotReachGitHub(t *testing.T) {
	fake := ghfake.New(e2eToken)
	s := newStack(t, fake)
	s.seedCIRelease("v1.4.0", time.Now(), model.ChannelDebug)
	s.syncAll()

	_, before := fake.Stats()
	for i := 0; i < 5; i++ {
		resp := s.download(model.ChannelDebug, "latest", nil)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("download %d answered %d", i, resp.StatusCode)
		}
		readAll(t, resp)
	}
	_, after := fake.Stats()
	for id, count := range after {
		if count != before[id] {
			t.Fatalf("asset %d was fetched %d times during client traffic", id, count-before[id])
		}
	}
}

func TestCacheMissIsHonestAndRecoversWithOneBackgroundFill(t *testing.T) {
	fake := ghfake.New(e2eToken)
	s := newStack(t, fake)
	payloads := s.seedCIRelease("v1.4.0", time.Now(), model.ChannelDebug)
	s.syncAll()

	current, found, err := s.meta.CurrentRelease(context.Background(), model.ChannelDebug)
	if err != nil || !found {
		t.Fatalf("no current release after sync: %v", err)
	}
	if err := s.objects.Delete(context.Background(), current.ObjectKey); err != nil {
		t.Fatalf("drop cached apk: %v", err)
	}
	if err := s.meta.SetCacheState(context.Background(), model.ChannelDebug, current.Tag, metastore.CachePending, ""); err != nil {
		t.Fatalf("mark pending: %v", err)
	}

	miss := s.download(model.ChannelDebug, "latest", nil)
	if miss.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("uncached artifact answered %d, want an honest 503", miss.StatusCode)
	}
	if code := decodeErrorCode(t, miss); code != "artifact_not_cached" {
		t.Fatalf("error code %q", code)
	}
	if miss.Header.Get("Retry-After") == "" {
		t.Fatal("503 must tell the client when to come back")
	}

	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		if record, ok, _ := s.meta.CurrentRelease(context.Background(), model.ChannelDebug); ok &&
			record.CacheState == metastore.CacheCached {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}

	recovered := s.download(model.ChannelDebug, "latest", nil)
	if recovered.StatusCode != http.StatusOK {
		t.Fatalf("download after the background fill answered %d", recovered.StatusCode)
	}
	body := readAll(t, recovered)
	if digestOf(body) != digestOf(payloads[model.ChannelDebug]) {
		t.Fatal("refilled bytes differ from the published artifact")
	}

	_, fetches := fake.Stats()
	if fetches[current.AssetID] != 2 {
		t.Fatalf("asset refetched %d times, want the dropped copy filled exactly once", fetches[current.AssetID])
	}
}

func headersText(resp *http.Response) []byte {
	return []byte(resp.Request.URL.String() + " " + resp.Status + " " + fmt.Sprint(resp.Header))
}
