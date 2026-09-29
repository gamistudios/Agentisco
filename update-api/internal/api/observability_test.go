package api

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"agentisco/updateapi/internal/ghfake"
	"agentisco/updateapi/internal/metastore"
	"agentisco/updateapi/internal/model"
	"agentisco/updateapi/internal/ratelimit"
	"agentisco/updateapi/web"
)

func TestPinnedVersionAndUnknownVersion(t *testing.T) {
	s := newStack(t, ghfake.New(e2eToken))
	payloads := s.seedCIRelease("v1.4.0", time.Now(), model.ChannelRelease)
	s.syncAll()

	// The URL the API hands out is pinned to the tag, so that tag must resolve to
	// exactly the bytes "latest" serves.
	pinned := s.download(model.ChannelRelease, "v1.4.0", nil)
	if pinned.StatusCode != http.StatusOK {
		t.Fatalf("pinned version answered %d", pinned.StatusCode)
	}
	if digestOf(readAll(t, pinned)) != digestOf(payloads[model.ChannelRelease]) {
		t.Fatal("pinned version served different bytes than latest")
	}

	unknown := s.download(model.ChannelRelease, "v9.9.9", nil)
	if unknown.StatusCode != http.StatusNotFound {
		t.Fatalf("unknown version answered %d", unknown.StatusCode)
	}
	if code := decodeErrorCode(t, unknown); code != "unknown_version" {
		t.Fatalf("error code %q", code)
	}
}

func TestEvictedHistoryRowDoesNotPromiseBytes(t *testing.T) {
	s := newStack(t, ghfake.New(e2eToken))
	s.seedCIRelease("v1.4.0", time.Now().Add(-time.Hour), model.ChannelDebug)
	s.syncAll()

	s.seedCIRelease("v1.5.0", time.Now(), model.ChannelDebug)
	s.syncAll()

	older, found, err := s.meta.ReleaseByTag(context.Background(), model.ChannelDebug, "v1.4.0")
	if err != nil || !found {
		t.Fatalf("history row missing: found=%v err=%v", found, err)
	}
	if older.CacheState != metastore.CacheEvicted {
		t.Fatalf("retired row must stop promising bytes, got %q", older.CacheState)
	}
	resp := s.download(model.ChannelDebug, "v1.4.0", nil)
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("evicted version answered %d, want a clean 404", resp.StatusCode)
	}
	if code := decodeErrorCode(t, resp); code != "artifact_not_cached" {
		t.Fatalf("error code %q", code)
	}
}

func TestGitHubOutageLeavesClientsWorking(t *testing.T) {
	fake := ghfake.New(e2eToken)
	s := newStack(t, fake)
	payloads := s.seedCIRelease("v1.4.0", time.Now(), model.ChannelDebug, model.ChannelRelease)
	s.syncAll()

	for i := 0; i < 2; i++ {
		fake.QueueFault(500, 500, 500, 500)
		s.syncAll()
	}

	info := decodeInfo(t, s.get("/v1/updates/release/latest", nil))
	if info.TagName != "v1.4.0" || !info.Cached {
		t.Fatalf("outage changed what clients are offered: %+v", info)
	}
	downloaded := s.download(model.ChannelRelease, "latest", nil)
	if downloaded.StatusCode != http.StatusOK {
		t.Fatalf("download during outage answered %d", downloaded.StatusCode)
	}
	if digestOf(readAll(t, downloaded)) != digestOf(payloads[model.ChannelRelease]) {
		t.Fatal("download during outage served the wrong bytes")
	}
	if ready := s.get("/ready", nil); ready.StatusCode != http.StatusOK {
		t.Fatalf("a servable cache must keep the instance ready, got %d", ready.StatusCode)
	}
}

func TestHealthAndReadiness(t *testing.T) {
	fake := ghfake.New(e2eToken)
	s := newStack(t, fake)

	health := s.get("/health", nil)
	if health.StatusCode != http.StatusOK {
		t.Fatalf("health answered %d", health.StatusCode)
	}
	var healthBody map[string]any
	if err := json.Unmarshal(readAll(t, health), &healthBody); err != nil {
		t.Fatalf("decode health: %v", err)
	}
	if healthBody["status"] != "ok" {
		t.Fatalf("health payload %+v", healthBody)
	}
	if healthBody["sync_interval_s"] != float64(3600) {
		t.Fatalf("health must report the configured cadence, got %v", healthBody["sync_interval_s"])
	}

	notReady := s.get("/ready", nil)
	if notReady.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("readiness before the first sync answered %d", notReady.StatusCode)
	}
	assertNothingPrivate(t, "not ready body", readAll(t, notReady))

	s.seedCIRelease("v1.4.0", time.Now(), model.ChannelDebug)
	s.syncAll()
	ready := s.get("/ready", nil)
	if ready.StatusCode != http.StatusOK {
		t.Fatalf("readiness after a successful sync answered %d", ready.StatusCode)
	}
	var readyBody map[string]any
	if err := json.Unmarshal(readAll(t, ready), &readyBody); err != nil {
		t.Fatalf("decode ready: %v", err)
	}
	if readyBody["status"] != "ready" {
		t.Fatalf("ready payload %+v", readyBody)
	}
	channels, _ := readyBody["channels"].([]any)
	if len(channels) != 1 {
		t.Fatalf("readiness must describe only the public channel, got %+v", readyBody["channels"])
	}
}

// TestInternalChannelStaysUnadvertised: the debug channel is served to the team's
// own builds, but no public surface may reveal that it exists.
func TestInternalChannelStaysUnadvertised(t *testing.T) {
	fake := ghfake.New(e2eToken)
	s := newStack(t, fake)
	s.seedCIRelease("v1.4.0", time.Now(), model.ChannelDebug, model.ChannelRelease)
	s.syncAll()

	for _, path := range []string{"/", "/health", "/ready"} {
		resp := s.get(path, nil)
		body := string(readAll(t, resp))
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("%s answered %d", path, resp.StatusCode)
		}
		if strings.Contains(strings.ToLower(body), "debug") {
			t.Fatalf("%s announces the internal channel: %s", path, body)
		}
	}

	var root struct {
		Service   string   `json:"service"`
		Endpoints []string `json:"endpoints"`
	}
	if err := json.Unmarshal(readAll(t, s.get("/", nil)), &root); err != nil {
		t.Fatalf("decode root: %v", err)
	}
	if len(root.Endpoints) == 0 {
		t.Fatal("the service must still document its public endpoints")
	}

	// Unadvertised is not unreachable: the debug channel answers normally.
	debug := s.get("/v1/updates/debug/latest", nil)
	if debug.StatusCode != http.StatusOK {
		t.Fatalf("the internal channel must keep working, got %d", debug.StatusCode)
	}
	readAll(t, debug)
	download := s.download(model.ChannelDebug, "latest", nil)
	if download.StatusCode != http.StatusOK {
		t.Fatalf("the internal channel must keep serving bytes, got %d", download.StatusCode)
	}
	readAll(t, download)

	// A mistyped channel must not teach a caller which channels exist.
	typo := s.get("/v1/updates/staging/latest", nil)
	if body := string(readAll(t, typo)); strings.Contains(strings.ToLower(body), "debug") {
		t.Fatalf("channel validation leaked the internal channel: %s", body)
	}
}

func TestNothingPublishedYetIsANotFound(t *testing.T) {
	s := newStack(t, ghfake.New(e2eToken))
	resp := s.get("/v1/updates/debug/latest", nil)
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("empty repository answered %d", resp.StatusCode)
	}
	if code := decodeErrorCode(t, resp); code != "no_release" {
		t.Fatalf("error code %q", code)
	}
}

func TestRateLimiterProtectsTheService(t *testing.T) {
	s := newStackWith(t, ghfake.New(e2eToken), func(cfg *Config) {
		cfg.Limiter = ratelimit.NewLimiter(1, 2)
	})
	s.seedCIRelease("v1.4.0", time.Now(), model.ChannelDebug)
	s.syncAll()

	var limited *http.Response
	for i := 0; i < 6; i++ {
		resp := s.get("/v1/updates/debug/latest", nil)
		if resp.StatusCode == http.StatusTooManyRequests {
			limited = resp
			break
		}
		readAll(t, resp)
	}
	if limited == nil {
		t.Fatal("a client hammering the API was never rate limited")
	}
	if limited.Header.Get("Retry-After") == "" {
		t.Fatal("429 must carry Retry-After")
	}
	if code := decodeErrorCode(t, limited); code != "rate_limited" {
		t.Fatalf("error code %q", code)
	}

	// A different client must not inherit the exhausted budget.
	other := s.get("/v1/updates/debug/latest", map[string]string{"X-Forwarded-For": "203.0.113.9"})
	if other.StatusCode != http.StatusOK {
		t.Fatalf("a distinct client answered %d", other.StatusCode)
	}
}

func TestLandingPageCarriesNoRepositoryTrace(t *testing.T) {
	s := newStackWith(t, ghfake.New(e2eToken), func(cfg *Config) {
		cfg.EnableLanding = true
		cfg.Landing = web.Handler(web.Config{})
	})
	s.seedCIRelease("v1.4.0", time.Now(), model.ChannelDebug, model.ChannelRelease)
	s.syncAll()

	resp := s.get("/", nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("landing answered %d", resp.StatusCode)
	}
	body := readAll(t, resp)
	if !bytes.Contains(body, []byte("<html")) {
		t.Fatalf("landing is not a page: %.200s", body)
	}
	assertNothingPrivate(t, "landing page", body)
	if strings.Contains(strings.ToLower(string(body)), "debug") {
		t.Fatal("the landing page must not offer the internal channel")
	}
	if !bytes.Contains(body, []byte("/v1/updates/release/latest")) {
		t.Fatal("landing page must read its versions from the API")
	}
}

func TestConcurrentClientsShareOneCachedObject(t *testing.T) {
	fake := ghfake.New(e2eToken)
	s := newStack(t, fake)
	payloads := s.seedCIRelease("v1.4.0", time.Now(), model.ChannelRelease)
	s.syncAll()

	const clients = 12
	var wg sync.WaitGroup
	statuses := make([]int, clients)
	digests := make([]string, clients)
	for i := 0; i < clients; i++ {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			resp, err := http.Get(s.server.URL + "/v1/download/release/latest")
			if err != nil {
				statuses[index] = -1
				return
			}
			defer resp.Body.Close()
			content, err := io.ReadAll(resp.Body)
			if err != nil {
				statuses[index] = -2
				return
			}
			statuses[index] = resp.StatusCode
			digests[index] = digestOf(content)
		}(i)
	}
	wg.Wait()

	for i, status := range statuses {
		if status != http.StatusOK {
			t.Fatalf("client %d answered %d", i, status)
		}
		if digests[i] != digestOf(payloads[model.ChannelRelease]) {
			t.Fatalf("client %d received different bytes", i)
		}
	}
}
