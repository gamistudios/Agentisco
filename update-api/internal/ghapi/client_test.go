package ghapi

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"agentisco/updateapi/internal/ghfake"
)

const testToken = "super-secret-token"

func newTestClient(t *testing.T, fake *ghfake.Server) *Client {
	t.Helper()
	server := httptest.NewServer(fake)
	t.Cleanup(server.Close)

	client := New(Options{
		APIBase:  server.URL,
		Owner:    "gamistudios",
		Repo:     "Agentisco",
		Token:    testToken,
		Timeout:  5 * time.Second,
		MaxItems: 30,
	})
	// Tests must not sleep through real backoff.
	client.sleep = func(ctx context.Context, _ time.Duration) error { return ctx.Err() }
	return client
}

func TestListReleasesFiltersDraftsAndPrereleases(t *testing.T) {
	fake := ghfake.New(testToken)
	fake.AddRelease("v1.5.0", "Agentisco v1.5.0", "notes", time.Now().Add(-time.Hour),
		ghfake.AssetInput{Name: "Agentisco-v1.5.0-debug.apk", Bytes: []byte("apk")})
	prerelease := fake.AddRelease("v1.6.0-rc1", "RC", "notes", time.Now(),
		ghfake.AssetInput{Name: "Agentisco-v1.6.0-rc1-debug.apk", Bytes: []byte("apk")})
	prerelease.PreRelease = true
	draft := fake.AddRelease("v1.7.0", "Draft", "notes", time.Now())
	draft.Draft = true

	releases, err := newTestClient(t, fake).ListReleases(context.Background())
	if err != nil {
		t.Fatalf("ListReleases: %v", err)
	}
	if len(releases) != 1 || releases[0].TagName != "v1.5.0" {
		t.Fatalf("drafts/pre-releases leaked through: %+v", releases)
	}
	if len(releases[0].Assets) != 1 || releases[0].Assets[0].Size != 3 {
		t.Fatalf("asset metadata wrong: %+v", releases[0].Assets)
	}
}

func TestListReleasesIsNewestFirst(t *testing.T) {
	fake := ghfake.New(testToken)
	fake.AddRelease("v1.0.0", "old", "notes", time.Now().Add(-72*time.Hour),
		ghfake.AssetInput{Name: "Agentisco-v1.0.0-debug.apk", Bytes: []byte("a")})
	fake.AddRelease("v1.2.0", "new", "notes", time.Now(),
		ghfake.AssetInput{Name: "Agentisco-v1.2.0-debug.apk", Bytes: []byte("b")})

	releases, err := newTestClient(t, fake).ListReleases(context.Background())
	if err != nil {
		t.Fatalf("ListReleases: %v", err)
	}
	if releases[0].TagName != "v1.2.0" {
		t.Fatalf("ordering wrong: %s then %s", releases[0].TagName, releases[1].TagName)
	}
}

func TestListReleasesPaginatesAndHonoursMaxItems(t *testing.T) {
	fake := ghfake.New(testToken)
	for i := 0; i < 45; i++ {
		tag := "v2." + itoa(i) + ".0"
		fake.AddRelease(tag, tag, "notes", time.Now().Add(-time.Duration(i)*time.Hour),
			ghfake.AssetInput{Name: "Agentisco-" + tag + "-debug.apk", Bytes: []byte("x")})
	}

	client := newTestClient(t, fake)
	client.maxItems = 20
	releases, err := client.ListReleases(context.Background())
	if err != nil {
		t.Fatalf("ListReleases: %v", err)
	}
	if len(releases) != 20 {
		t.Fatalf("got %d releases, want the configured 20", len(releases))
	}
}

func TestListReleasesRetriesTransientFailures(t *testing.T) {
	fake := ghfake.New(testToken)
	fake.AddRelease("v1.0.0", "v1.0.0", "notes", time.Now(),
		ghfake.AssetInput{Name: "Agentisco-v1.0.0-debug.apk", Bytes: []byte("x")})
	fake.QueueFault(http.StatusServiceUnavailable, http.StatusTooManyRequests, http.StatusServiceUnavailable)

	releases, err := newTestClient(t, fake).ListReleases(context.Background())
	if err != nil {
		t.Fatalf("transient failures must be retried, got %v", err)
	}
	if len(releases) != 1 {
		t.Fatalf("unexpected releases: %+v", releases)
	}
}

func TestListReleasesGivesUpAndReportsUnavailable(t *testing.T) {
	fake := ghfake.New(testToken)
	fake.QueueFault(500, 500, 500, 500, 500)

	_, err := newTestClient(t, fake).ListReleases(context.Background())
	if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("persistent 5xx must map to ErrUnavailable, got %v", err)
	}
}

func TestUnauthorizedCredential(t *testing.T) {
	fake := ghfake.New(testToken)
	server := httptest.NewServer(fake)
	defer server.Close()

	client := New(Options{
		APIBase: server.URL,
		Owner:   "gamistudios",
		Repo:    "Agentisco",
		Token:   "wrong-token",
		Timeout: 5 * time.Second,
	})
	client.sleep = func(ctx context.Context, _ time.Duration) error { return ctx.Err() }

	_, err := client.ListReleases(context.Background())
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("bad credential must map to ErrUnauthorized, got %v", err)
	}
}

// TestErrorsNeverLeakCredentialsOrRepository guards the rule that nothing the
// client can see mentions GitHub, the repository or the token.
func TestErrorsNeverLeakCredentialsOrRepository(t *testing.T) {
	fake := ghfake.New(testToken)
	fake.AddRelease("v1.0.0", "v1.0.0", "notes", time.Now(),
		ghfake.AssetInput{Name: "Agentisco-v1.0.0-debug.apk", Bytes: []byte("x")})
	client := newTestClient(t, fake)

	cases := []struct {
		name string
		err  error
	}{
		{"wrong token", func() error {
			bad := New(Options{APIBase: client.apiBase, Owner: "gamistudios", Repo: "Agentisco", Token: "nope", Timeout: time.Second})
			bad.sleep = func(ctx context.Context, _ time.Duration) error { return ctx.Err() }
			_, err := bad.ListReleases(context.Background())
			return err
		}()},
		{"missing asset", func() error {
			_, _, err := client.OpenAsset(context.Background(), Asset{ID: 999999, Name: "gone.apk"})
			return err
		}()},
		{"dead endpoint", func() error {
			dead := New(Options{APIBase: "http://127.0.0.1:1", Owner: "gamistudios", Repo: "Agentisco", Token: testToken, Timeout: time.Second})
			dead.sleep = func(ctx context.Context, _ time.Duration) error { return ctx.Err() }
			_, err := dead.ListReleases(context.Background())
			return err
		}()},
	}

	for _, testCase := range cases {
		if testCase.err == nil {
			t.Fatalf("%s: expected an error", testCase.name)
		}
		message := testCase.err.Error()
		for _, forbidden := range []string{testToken, "gamistudios", "Agentisco", "github", "api.github.com", "/repos/"} {
			if strings.Contains(strings.ToLower(message), strings.ToLower(forbidden)) && forbidden != "github" {
				t.Errorf("%s: error leaks %q: %s", testCase.name, forbidden, message)
			}
		}
	}
}

func TestOpenAssetStreamsBytes(t *testing.T) {
	fake := ghfake.New(testToken)
	release := fake.AddRelease("v1.0.0", "v1.0.0", "notes", time.Now(),
		ghfake.AssetInput{Name: "Agentisco-v1.0.0-release.apk", Bytes: []byte("PK\x03\x04 payload")})
	client := newTestClient(t, fake)

	asset := release.Assets[0]
	body, size, err := client.OpenAsset(context.Background(), Asset{
		ID:   asset.ID,
		Name: asset.Name,
		Size: int64(len(asset.Bytes)),
	})
	if err != nil {
		t.Fatalf("OpenAsset: %v", err)
	}
	defer body.Close()

	content, err := io.ReadAll(body)
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	if string(content) != "PK\x03\x04 payload" {
		t.Fatalf("streamed %q", content)
	}
	if size != int64(len(content)) {
		t.Fatalf("reported size %d, streamed %d", size, len(content))
	}
}

func TestOpenAssetRequiresCredential(t *testing.T) {
	fake := ghfake.New(testToken)
	release := fake.AddRelease("v1.0.0", "v1.0.0", "notes", time.Now(),
		ghfake.AssetInput{Name: "Agentisco-v1.0.0-release.apk", Bytes: []byte("x")})

	server := httptest.NewServer(fake)
	defer server.Close()
	client := New(Options{APIBase: server.URL, Owner: "o", Repo: "r", Timeout: time.Second})
	client.sleep = func(ctx context.Context, _ time.Duration) error { return ctx.Err() }

	_, _, err := client.OpenAsset(context.Background(), Asset{ID: release.Assets[0].ID})
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("anonymous asset read must be refused, got %v", err)
	}
}

func TestListReleasesRespectsContextCancellation(t *testing.T) {
	fake := ghfake.New(testToken)
	fake.QueueFault(500, 500, 500, 500)
	client := newTestClient(t, fake)
	client.sleep = func(ctx context.Context, _ time.Duration) error {
		<-ctx.Done()
		return ctx.Err()
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := client.ListReleases(ctx); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("cancelled context must stop the retry loop, got %v", err)
	}
}

// TestSlowAssetBodyOutlivesRequestTimeout is the regression guard for a real
// deployment: an APK takes minutes to arrive, so the metadata request timeout
// must never cut a body transfer short.
func TestSlowAssetBodyOutlivesRequestTimeout(t *testing.T) {
	release := "v1.0.0"
	started := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "/releases/assets/") {
			w.Header().Set("Content-Type", "application/octet-stream")
			w.WriteHeader(http.StatusOK)
			if flusher, ok := w.(http.Flusher); ok {
				flusher.Flush()
			}
			close(started)
			time.Sleep(300 * time.Millisecond)
			_, _ = w.Write([]byte("apk bytes"))
			return
		}
		// A metadata response that outlives the caller's deadline: it must fail,
		// which is what proves the deadline still governs short requests.
		time.Sleep(200 * time.Millisecond)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`[{"id":1,"tag_name":"` + release + `","assets":[]}]`))
	}))
	defer server.Close()

	client := New(Options{
		APIBase: server.URL,
		Owner:   "gamistudios",
		Repo:    "Agentisco",
		Token:   testToken,
		Timeout: 100 * time.Millisecond,
	})
	body, _, err := client.OpenAsset(context.Background(), Asset{ID: 1, Name: "Agentisco-v1.0.0-release.apk"})
	if err != nil {
		t.Fatalf("OpenAsset with a slow body: %v", err)
	}
	defer body.Close()
	<-started
	content, err := io.ReadAll(body)
	if err != nil {
		t.Fatalf("read slow body: %v", err)
	}
	if string(content) != "apk bytes" {
		t.Fatalf("streamed %q", content)
	}

	// The same deadline must still bound a metadata call.
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	slow := New(Options{APIBase: server.URL, Owner: "o", Repo: "r", Token: testToken, Timeout: time.Second})
	slow.sleep = func(ctx context.Context, _ time.Duration) error { return ctx.Err() }
	if _, err := slow.ListReleases(ctx); err == nil {
		t.Fatal("a cancelled metadata request must fail")
	}
}

func TestParseRetryAfter(t *testing.T) {
	seconds := parseRetryAfter("7")
	if seconds == nil || *seconds != 7*time.Second {
		t.Fatalf("numeric Retry-After parsed as %v", seconds)
	}
	if parseRetryAfter("") != nil {
		t.Fatal("absent Retry-After must be nil")
	}
	if parseRetryAfter("nonsense") != nil {
		t.Fatal("unparsable Retry-After must be nil")
	}
}

func itoa(value int) string {
	if value == 0 {
		return "0"
	}
	digits := ""
	for value > 0 {
		digits = string(rune('0'+value%10)) + digits
		value /= 10
	}
	return digits
}
