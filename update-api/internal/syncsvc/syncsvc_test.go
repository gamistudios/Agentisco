package syncsvc

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"agentisco/updateapi/internal/ghapi"
	"agentisco/updateapi/internal/ghfake"
	"agentisco/updateapi/internal/metastore"
	"agentisco/updateapi/internal/model"
	"agentisco/updateapi/internal/notes"
	"agentisco/updateapi/internal/objectstore"
)

const harnessToken = "super-secret-harness-token"

type harness struct {
	test    *testing.T
	fake    *ghfake.Server
	meta    *metastore.DB
	objects objectstore.Store
	syncer  *Syncer
}

func newHarness(t *testing.T, handler http.Handler) *harness {
	t.Helper()
	root := t.TempDir()

	client := ghapi.New(ghapi.Options{
		APIBase:  startFake(t, handler),
		Owner:    "gamistudios",
		Repo:     "Agentisco",
		Token:    harnessToken,
		Timeout:  5 * time.Second,
		MaxItems: 30,
	})

	meta, err := metastore.Open(filepath.Join(root, "metadata.db"))
	if err != nil {
		t.Fatalf("open metastore: %v", err)
	}
	t.Cleanup(func() { _ = meta.Close() })

	objects, err := objectstore.New(filepath.Join(root, "objects"))
	if err != nil {
		t.Fatalf("open object store: %v", err)
	}

	syncer := New(Options{
		Client:      client,
		Meta:        meta,
		Objects:     objects,
		Sanitizer:   notes.NewSanitizer("gamistudios", "Agentisco"),
		Logger:      slog.New(slog.NewTextHandler(io.Discard, nil)),
		Interval:    time.Minute,
		PassTimeout: 20 * time.Second,
	})
	return &harness{test: t, meta: meta, objects: objects, syncer: syncer}
}

func startFake(t *testing.T, handler http.Handler) string {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	return server.URL
}

func (h *harness) sync(channel model.Channel) {
	h.test.Helper()
	h.syncer.SyncChannel(context.Background(), channel)
}

func digestOf(content []byte) string {
	sum := sha256.Sum256(content)
	return hex.EncodeToString(sum[:])
}

func ciNotes(tag, previous string) string {
	return "## What's Changed\n\n* first change\n\n**Full Changelog**: https://github.com/gamistudios/Agentisco/compare/" +
		previous + "..." + tag
}

func TestSyncResolvesEachChannelToItsOwnRelease(t *testing.T) {
	fake := ghfake.New(harnessToken)
	fake.AddRelease("v1.4.0", "v1.4.0", ciNotes("v1.4.0", "v1.3.0"), time.Now().Add(-48*time.Hour),
		ghfake.AssetInput{Name: "Agentisco-v1.4.0-debug.apk", Bytes: ghfake.SyntheticAPK("1.4.0-debug", 512)},
		ghfake.AssetInput{Name: "Agentisco-v1.4.0-release.apk", Bytes: ghfake.SyntheticAPK("1.4.0-release", 640)},
		ghfake.AssetInput{Name: "Agentisco-v1.4.0-release.aab", Bytes: []byte("bundle")},
	)
	fake.AddRelease("v1.5.0", "v1.5.0", ciNotes("v1.5.0", "v1.4.0"), time.Now(),
		ghfake.AssetInput{Name: "Agentisco-v1.5.0-debug.apk", Bytes: ghfake.SyntheticAPK("1.5.0-debug", 768)},
	)

	h := newHarness(t, fake)
	h.syncer.pass(context.Background())

	debug, ok, err := h.meta.CurrentRelease(context.Background(), model.ChannelDebug)
	if err != nil || !ok {
		t.Fatalf("debug channel has no current release (ok=%v err=%v)", ok, err)
	}
	if debug.Tag != "v1.5.0" {
		t.Fatalf("debug resolved to %s, want the newest release carrying a debug APK", debug.Tag)
	}
	release, ok, err := h.meta.CurrentRelease(context.Background(), model.ChannelRelease)
	if err != nil || !ok {
		t.Fatalf("release channel has no current release (ok=%v err=%v)", ok, err)
	}
	if release.Tag != "v1.4.0" {
		t.Fatalf("release resolved to %s, want the newest release carrying a release APK", release.Tag)
	}
	if debug.VersionCode != 10500 || release.VersionCode != 10400 {
		t.Fatalf("version codes wrong: debug=%d release=%d", debug.VersionCode, release.VersionCode)
	}
	if release.CacheState != metastore.CacheCached || debug.CacheState != metastore.CacheCached {
		t.Fatalf("both current releases must be cached: debug=%s release=%s", debug.CacheState, release.CacheState)
	}
	for _, record := range []metastore.Release{debug, release} {
		if _, statErr := h.objects.Stat(context.Background(), record.ObjectKey); statErr != nil {
			t.Fatalf("%s/%s object missing: %v", record.Channel, record.Tag, statErr)
		}
		if strings.Contains(record.ReleaseNotes, "github.com") ||
			strings.Contains(record.ReleaseNotes, "gamistudios") {
			t.Fatalf("release notes still carry a private reference: %q", record.ReleaseNotes)
		}
		if !strings.Contains(record.ReleaseNotes, "first change") {
			t.Fatalf("release notes lost their changelog bullets: %q", record.ReleaseNotes)
		}
	}
}

func TestSyncDownloadsEachApkExactlyOnce(t *testing.T) {
	fake := ghfake.New(harnessToken)
	fake.AddRelease("v1.4.0", "v1.4.0", "notes", time.Now(),
		ghfake.AssetInput{Name: "Agentisco-v1.4.0-debug.apk", Bytes: ghfake.SyntheticAPK("debug", 256)},
		ghfake.AssetInput{Name: "Agentisco-v1.4.0-release.apk", Bytes: ghfake.SyntheticAPK("release", 256)},
	)
	h := newHarness(t, fake)

	for i := 0; i < 3; i++ {
		h.syncer.pass(context.Background())
	}

	_, fetches := fake.Stats()
	for id, count := range fetches {
		if count != 1 {
			t.Fatalf("asset %d downloaded %d times, want once", id, count)
		}
	}
	if len(fetches) != 2 {
		t.Fatalf("expected two cached assets, saw %d fetch groups", len(fetches))
	}
}

// TestPassListsReleasesOnceForBothChannels is the traffic contract: a tick costs
// one metadata request for the whole service, not one per channel, and never a
// repeat download of bytes already cached.
func TestPassListsReleasesOnceForBothChannels(t *testing.T) {
	fake := ghfake.New(harnessToken)
	fake.AddRelease("v1.4.0", "v1.4.0", "notes", time.Now().Add(-time.Hour),
		ghfake.AssetInput{Name: "Agentisco-v1.4.0-debug.apk", Bytes: ghfake.SyntheticAPK("debug", 256)},
		ghfake.AssetInput{Name: "Agentisco-v1.4.0-release.apk", Bytes: ghfake.SyntheticAPK("release", 256)},
	)
	h := newHarness(t, fake)

	for i := 0; i < 3; i++ {
		h.syncer.pass(context.Background())
	}

	lists, fetches := fake.Stats()
	if lists != 3 {
		t.Fatalf("three passes must cost three listings, got %d", lists)
	}
	for id, count := range fetches {
		if count != 1 {
			t.Fatalf("asset %d fetched %d times, want once", id, count)
		}
	}
}

func TestSyncReplacesCachedObjectAndRetiresTheOldOne(t *testing.T) {
	fake := ghfake.New(harnessToken)
	fake.AddRelease("v1.0.0", "v1.0.0", "notes", time.Now().Add(-time.Hour),
		ghfake.AssetInput{Name: "Agentisco-v1.0.0-debug.apk", Bytes: ghfake.SyntheticAPK("one", 256)},
	)
	h := newHarness(t, fake)
	h.sync(model.ChannelDebug)
	previous, ok, err := h.meta.CurrentRelease(context.Background(), model.ChannelDebug)
	if err != nil || !ok {
		t.Fatalf("first sync produced no current release")
	}

	fake.AddRelease("v1.1.0", "v1.1.0", "notes", time.Now(),
		ghfake.AssetInput{Name: "Agentisco-v1.1.0-debug.apk", Bytes: ghfake.SyntheticAPK("two", 320)},
	)
	h.sync(model.ChannelDebug)

	current, ok, err := h.meta.CurrentRelease(context.Background(), model.ChannelDebug)
	if err != nil || !ok || current.Tag != "v1.1.0" {
		t.Fatalf("want v1.1.0 current, got %+v (ok=%v err=%v)", current, ok, err)
	}
	if _, statErr := h.objects.Stat(context.Background(), current.ObjectKey); statErr != nil {
		t.Fatalf("new apk was not cached: %v", statErr)
	}
	if _, statErr := h.objects.Stat(context.Background(), previous.ObjectKey); !errors.Is(statErr, objectstore.ErrNotFound) {
		t.Fatalf("retired apk still present (err=%v)", statErr)
	}

	_, fetches := fake.Stats()
	if count := fetches[previous.AssetID]; count != 1 {
		t.Fatalf("retired asset re-fetched %d times", count)
	}
	// The history row stays queryable but must stop advertising bytes that are gone.
	older, found, err := h.meta.ReleaseByTag(context.Background(), model.ChannelDebug, "v1.0.0")
	if err != nil || !found {
		t.Fatalf("history row disappeared: found=%v err=%v", found, err)
	}
	if older.CacheState != metastore.CacheEvicted || older.ObjectKey != "" {
		t.Fatalf("evicted row still promises bytes: %+v", older)
	}
}

func TestIdenticalApkBytesAreStoredOnce(t *testing.T) {
	shared := ghfake.SyntheticAPK("shared-bytes", 400)
	fake := ghfake.New(harnessToken)
	fake.AddRelease("v1.0.0", "v1.0.0", "notes", time.Now().Add(-time.Hour),
		ghfake.AssetInput{Name: "Agentisco-v1.0.0-debug.apk", Bytes: shared})
	h := newHarness(t, fake)
	h.sync(model.ChannelDebug)
	first, _, _ := h.meta.CurrentRelease(context.Background(), model.ChannelDebug)

	// A republish under a new tag with byte-identical content must not transfer
	// again, and its object must survive the retirement of the old release.
	fake.AddRelease("v1.0.1", "v1.0.1", "notes", time.Now(),
		ghfake.AssetInput{Name: "Agentisco-v1.0.1-debug.apk", Bytes: append([]byte(nil), shared...)})
	h.sync(model.ChannelDebug)

	second, _, _ := h.meta.CurrentRelease(context.Background(), model.ChannelDebug)
	if second.Tag != "v1.0.1" || second.ObjectKey != first.ObjectKey {
		t.Fatalf("content-addressed cache missed: current=%+v first=%s", second, first.ObjectKey)
	}
	_, fetches := fake.Stats()
	if count := fetches[second.AssetID]; count != 0 {
		t.Fatalf("duplicate bytes fetched %d times, want zero", count)
	}
	if _, statErr := h.objects.Stat(context.Background(), second.ObjectKey); statErr != nil {
		t.Fatalf("shared object deleted while still referenced: %v", statErr)
	}
}

func TestSyncRefusesArtifactWhoseBytesDoNotMatchThePublishedDigest(t *testing.T) {
	fake := ghfake.New(harnessToken)
	release := fake.AddRelease("v1.2.0", "v1.2.0", "notes", time.Now(),
		ghfake.AssetInput{Name: "Agentisco-v1.2.0-debug.apk", Bytes: ghfake.SyntheticAPK("original", 256)})
	// The published metadata still advertises the original digest while the
	// stored asset has been replaced: the transfer must be rejected.
	release.Assets[0].Bytes = ghfake.SyntheticAPK("tampered", 256)

	h := newHarness(t, fake)
	h.sync(model.ChannelDebug)

	current, ok, err := h.meta.CurrentRelease(context.Background(), model.ChannelDebug)
	if err != nil {
		t.Fatalf("current release: %v", err)
	}
	if !ok || current.Tag != "v1.2.0" {
		t.Fatalf("the release must still be advertised for version checks, got %+v", current)
	}
	if current.CacheState != metastore.CachePending || current.ObjectKey != "" {
		t.Fatalf("unverified bytes must never be marked cached: %+v", current)
	}
	if _, statErr := h.objects.Stat(context.Background(), digestKey(model.ChannelDebug, digestOf(release.Assets[0].Bytes))); !errors.Is(statErr, objectstore.ErrNotFound) {
		t.Fatalf("tampered apk was published to the cache (err=%v)", statErr)
	}
	snapshot, err := h.meta.ChannelSnapshot(context.Background(), model.ChannelDebug)
	if err != nil {
		t.Fatalf("snapshot: %v", err)
	}
	if snapshot.Status != metastore.SyncUnavailable {
		t.Fatalf("failed transfer must be recorded, got %q", snapshot.Status)
	}
}

func TestFetchRejectsSizeMismatchAndLeavesNoScratch(t *testing.T) {
	content := ghfake.SyntheticAPK("payload", 300)
	fake := ghfake.New(harnessToken)
	release := fake.AddRelease("v1.3.0", "v1.3.0", "notes", time.Now(),
		ghfake.AssetInput{Name: "Agentisco-v1.3.0-debug.apk", Bytes: content})
	h := newHarness(t, fake)

	asset := release.Assets[0]
	lying := ghapi.Asset{
		ID:     asset.ID,
		Name:   asset.Name,
		Size:   int64(len(content) + 10),
		Digest: digestOf(content),
	}
	if _, err := h.syncer.Fetch(context.Background(), model.ChannelDebug, lying, release.Tag); err == nil {
		t.Fatal("short transfer must fail verification")
	}

	entries, err := filepath.Glob(filepath.Join(h.syncer.objects.(*objectstore.FileStore).Root, "staging", "*", "*.apk"))
	if err != nil {
		t.Fatalf("glob staging: %v", err)
	}
	if len(entries) != 0 {
		t.Fatalf("failed transfer left scratch files: %v", entries)
	}
}

func TestGitHubOutageKeepsLastKnownGoodVersion(t *testing.T) {
	fake := ghfake.New(harnessToken)
	fake.AddRelease("v1.4.0", "v1.4.0", "notes", time.Now(),
		ghfake.AssetInput{Name: "Agentisco-v1.4.0-debug.apk", Bytes: ghfake.SyntheticAPK("debug", 256)},
		ghfake.AssetInput{Name: "Agentisco-v1.4.0-release.apk", Bytes: ghfake.SyntheticAPK("release", 256)},
	)
	h := newHarness(t, fake)
	h.syncer.pass(context.Background())

	fake.QueueFault(500, 500, 500, 500, 500, 500, 500, 500)
	h.syncer.pass(context.Background())

	for _, channel := range model.Channels {
		current, ok, err := h.meta.CurrentRelease(context.Background(), channel)
		if err != nil || !ok {
			t.Fatalf("%s: outage dropped the known good release (ok=%v err=%v)", channel, ok, err)
		}
		if current.CacheState != metastore.CacheCached {
			t.Fatalf("%s: cached state lost during outage", channel)
		}
		if _, statErr := h.objects.Stat(context.Background(), current.ObjectKey); statErr != nil {
			t.Fatalf("%s: cached bytes vanished: %v", channel, statErr)
		}
		snapshot, err := h.meta.ChannelSnapshot(context.Background(), channel)
		if err != nil {
			t.Fatalf("snapshot: %v", err)
		}
		if snapshot.Status != metastore.SyncUnavailable {
			t.Fatalf("%s: outage must be visible in the status, got %q", channel, snapshot.Status)
		}
		for _, forbidden := range []string{harnessToken, "gamistudios", "Agentisco", "github.com", "/repos/"} {
			if strings.Contains(strings.ToLower(snapshot.LastError), strings.ToLower(forbidden)) {
				t.Fatalf("%s: stored status text leaks %q: %s", channel, forbidden, snapshot.LastError)
			}
		}
	}
}

func TestUnreachableGitHubWithNoHistoryDoesNotPanic(t *testing.T) {
	fake := ghfake.New(harnessToken)
	fake.QueueFault(500, 500, 500, 500, 500)
	h := newHarness(t, fake)
	h.syncer.pass(context.Background())

	if _, ok, err := h.meta.CurrentRelease(context.Background(), model.ChannelDebug); ok || err != nil {
		t.Fatalf("nothing was ever published, yet a current release appeared: ok=%v err=%v", ok, err)
	}
	snapshot, err := h.meta.ChannelSnapshot(context.Background(), model.ChannelDebug)
	if err != nil {
		t.Fatalf("snapshot: %v", err)
	}
	if snapshot.Status != metastore.SyncUnavailable {
		t.Fatalf("status after first failed pass: %q", snapshot.Status)
	}
}

func TestReleaseDisappearingFromAChannelKeepsTheLastKnownGood(t *testing.T) {
	fake := ghfake.New(harnessToken)
	fake.AddRelease("v1.4.0", "v1.4.0", "notes", time.Now().Add(-time.Hour),
		ghfake.AssetInput{Name: "Agentisco-v1.4.0-release.apk", Bytes: ghfake.SyntheticAPK("release", 256)})
	h := newHarness(t, fake)
	h.sync(model.ChannelRelease)

	// A debug-only publish follows: the release channel keeps advertising v1.4.0.
	fake.AddRelease("v1.5.0", "v1.5.0", "notes", time.Now(),
		ghfake.AssetInput{Name: "Agentisco-v1.5.0-debug.apk", Bytes: ghfake.SyntheticAPK("debug", 256)})
	h.sync(model.ChannelRelease)

	current, ok, err := h.meta.CurrentRelease(context.Background(), model.ChannelRelease)
	if err != nil || !ok || current.Tag != "v1.4.0" {
		t.Fatalf("release channel must keep v1.4.0, got %+v (ok=%v err=%v)", current, ok, err)
	}
}

// gate holds asset responses open so single-flight behaviour can be observed.
type gate struct {
	inner   *ghfake.Server
	entered chan struct{}
	release chan struct{}
	once    sync.Once
}

func (g *gate) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if strings.Contains(r.URL.Path, "/releases/assets/") {
		g.once.Do(func() { close(g.entered) })
		<-g.release
	}
	g.inner.ServeHTTP(w, r)
}

func TestConcurrentFillsShareOneTransfer(t *testing.T) {
	fake := ghfake.New(harnessToken)
	release := fake.AddRelease("v1.0.0", "v1.0.0", "notes", time.Now(),
		ghfake.AssetInput{Name: "Agentisco-v1.0.0-debug.apk", Bytes: ghfake.SyntheticAPK("debug", 256)})
	gated := &gate{inner: fake, entered: make(chan struct{}), release: make(chan struct{})}

	h := newHarness(t, gated)
	asset := ghapi.Asset{
		ID:     release.Assets[0].ID,
		Name:   release.Assets[0].Name,
		Size:   int64(len(release.Assets[0].Bytes)),
		Digest: digestOf(release.Assets[0].Bytes),
	}

	type result struct {
		key string
		err error
	}
	first := make(chan result, 1)
	go func() {
		key, err := h.syncer.Fetch(context.Background(), model.ChannelDebug, asset, release.Tag)
		first <- result{key, err}
	}()

	select {
	case <-gated.entered:
	case <-time.After(5 * time.Second):
		t.Fatal("first fill never reached the asset endpoint")
	}

	secondKey, secondErr := h.syncer.Fetch(context.Background(), model.ChannelDebug, asset, release.Tag)
	if !errors.Is(secondErr, ErrFillInProgress) {
		t.Fatalf("overlapping fill must be refused, got key=%q err=%v", secondKey, secondErr)
	}

	close(gated.release)
	done := <-first
	if done.err != nil {
		t.Fatalf("first fill failed: %v", done.err)
	}
	if done.key == "" {
		t.Fatal("first fill published no object")
	}
}

// failingAssets serves listings normally and refuses every asset download until
// it is switched off again - the poor connection a large APK meets in reality.
type failingAssets struct {
	inner   *ghfake.Server
	failing atomic.Bool
}

func (f *failingAssets) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if f.failing.Load() && strings.Contains(r.URL.Path, "/releases/assets/") {
		http.Error(w, "synthetic download failure", http.StatusBadGateway)
		return
	}
	f.inner.ServeHTTP(w, r)
}

// TestMetadataIsPublishedWhileTheBytesFailToArrive: a client must be able to ask
// "is there a new version?" even while the artifact is still undelivered.
func TestMetadataIsPublishedWhileTheBytesFailToArrive(t *testing.T) {
	fake := ghfake.New(harnessToken)
	fake.AddRelease("v2.0.42", "v2.0.42", "notes", time.Now(),
		ghfake.AssetInput{Name: "Agentisco-v2.0.42-debug.apk", Bytes: ghfake.SyntheticAPK("debug", 1<<10)})
	link := &failingAssets{inner: fake, failing: atomic.Bool{}}
	link.failing.Store(true)
	h := newHarness(t, link)

	h.syncer.pass(context.Background())

	current, ok, err := h.meta.CurrentRelease(context.Background(), model.ChannelDebug)
	if err != nil || !ok {
		t.Fatalf("an undeliverable apk hid its version (ok=%v err=%v)", ok, err)
	}
	if current.Tag != "v2.0.42" || current.VersionCode != 20042 {
		t.Fatalf("wrong release promoted: %+v", current)
	}
	if current.CacheState == metastore.CacheCached {
		t.Fatal("metadata claimed the bytes were cached")
	}
	if snapshot, snapErr := h.meta.ChannelSnapshot(context.Background(), model.ChannelDebug); snapErr != nil {
		t.Fatalf("snapshot: %v", snapErr)
	} else if snapshot.CurrentTag == "" {
		t.Fatal("the channel stopped advertising the release that failed to download")
	}

	entries, err := filepath.Glob(filepath.Join(h.syncer.objects.(*objectstore.FileStore).Root, "staging", "*", "*.apk"))
	if err != nil {
		t.Fatalf("glob staging: %v", err)
	}
	if len(entries) != 0 {
		t.Fatalf("failed pass left scratch files: %v", entries)
	}

	// The next pass retries the transfer, and the same row turns cached without the
	// tag or version changing.
	link.failing.Store(false)
	h.syncer.pass(context.Background())

	recovered, ok, err := h.meta.CurrentRelease(context.Background(), model.ChannelDebug)
	if err != nil || !ok {
		t.Fatalf("retry pass lost the release (ok=%v err=%v)", ok, err)
	}
	if recovered.Tag != "v2.0.42" {
		t.Fatalf("retry changed the promoted tag to %s", recovered.Tag)
	}
	if recovered.CacheState != metastore.CacheCached || recovered.ObjectKey == "" {
		t.Fatalf("retry must cache the apk, got %+v", recovered)
	}
	if _, statErr := h.objects.Stat(context.Background(), recovered.ObjectKey); statErr != nil {
		t.Fatalf("object missing after retry: %v", statErr)
	}
}

func TestPruneForgetsReleasesBeyondRetention(t *testing.T) {
	fake := ghfake.New(harnessToken)
	// Ascending tags so the newest publish is also the highest version code.
	tags := []string{"v2.0.0", "v2.1.0", "v2.2.0", "v2.3.0", "v2.4.0", "v2.5.0", "v2.6.0", "v2.7.0", "v2.8.0"}
	for i, tag := range tags {
		fake.AddRelease(tag, tag, "notes", time.Now().Add(-time.Duration(len(tags)-i)*time.Hour),
			ghfake.AssetInput{Name: "Agentisco-" + tag + "-debug.apk", Bytes: ghfake.SyntheticAPK(tag, 64)})
	}
	h := newHarness(t, fake)
	h.sync(model.ChannelDebug)

	snapshot, err := h.meta.ChannelSnapshot(context.Background(), model.ChannelDebug)
	if err != nil {
		t.Fatalf("snapshot: %v", err)
	}
	if snapshot.CurrentTag != tags[len(tags)-1] {
		t.Fatalf("newest release should be current, got %q", snapshot.CurrentTag)
	}
	// The current release always survives, so retention is the newest five.
	_, oldest, err := h.meta.ReleaseByTag(context.Background(), model.ChannelDebug, tags[0])
	if err != nil {
		t.Fatalf("lookup: %v", err)
	}
	if oldest {
		t.Fatal("release outside the retention window was kept")
	}
	_, retained, err := h.meta.ReleaseByTag(context.Background(), model.ChannelDebug, tags[len(tags)-retainedReleases])
	if err != nil {
		t.Fatalf("lookup: %v", err)
	}
	if !retained {
		t.Fatalf("release inside the retention window was forgotten")
	}
}
