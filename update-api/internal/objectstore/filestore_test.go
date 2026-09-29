package objectstore

import (
	"bytes"
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func newTestStore(t *testing.T) *FileStore {
	t.Helper()
	store, err := New(filepath.Join(t.TempDir(), "objects"))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	return store
}

func TestPutStatOpenRoundTrip(t *testing.T) {
	store := newTestStore(t)
	ctx := context.Background()
	payload := []byte("PK\x03\x04 deterministic apk bytes")

	if _, err := store.Put(ctx, "apks/release/abc.apk", bytes.NewReader(payload)); err != nil {
		t.Fatalf("Put: %v", err)
	}

	info, err := store.Stat(ctx, "apks/release/abc.apk")
	if err != nil {
		t.Fatalf("Stat: %v", err)
	}
	if info.Size != int64(len(payload)) {
		t.Errorf("Stat size = %d, want %d", info.Size, len(payload))
	}

	object, openInfo, err := store.Open(ctx, "apks/release/abc.apk")
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer object.Close()

	if _, err := object.Seek(3, io.SeekStart); err != nil {
		t.Fatalf("Seek: %v", err)
	}
	rest, err := io.ReadAll(object)
	if err != nil {
		t.Fatalf("ReadAll: %v", err)
	}
	if string(rest) != string(payload[3:]) {
		t.Errorf("seek-then-read produced %q, want %q", rest, payload[3:])
	}
	if openInfo.Size != int64(len(payload)) {
		t.Errorf("Open size = %d, want %d", openInfo.Size, len(payload))
	}
}

// TestFailedPutLeavesNoObject is the guarantee the download endpoint relies on:
// a truncated transfer can never be handed to a client as an APK.
func TestFailedPutLeavesNoObject(t *testing.T) {
	store := newTestStore(t)
	ctx := context.Background()
	key := "apks/debug/broken.apk"

	failing := io.MultiReader(strings.NewReader("PK\x03\x04 partial"), failingReader{})
	if _, err := store.Put(ctx, key, failing); err == nil {
		t.Fatal("Put should fail when the source errors")
	}

	if _, err := store.Stat(ctx, key); !errors.Is(err, ErrNotFound) {
		t.Fatalf("Stat after failed Put = %v, want ErrNotFound", err)
	}
	assertNoTempFiles(t, store.Root)
}

func TestPutHonoursContextCancellation(t *testing.T) {
	store := newTestStore(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	if _, err := store.Put(ctx, "apks/debug/x.apk", bytes.NewReader([]byte("bytes"))); err == nil {
		t.Fatal("Put with a cancelled context must fail")
	}
	if _, err := store.Stat(context.Background(), "apks/debug/x.apk"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("cancelled Put left an object behind: %v", err)
	}
}

func TestPutOverwritesExistingObject(t *testing.T) {
	store := newTestStore(t)
	ctx := context.Background()
	if _, err := store.Put(ctx, "apks/debug/a.apk", bytes.NewReader([]byte("first"))); err != nil {
		t.Fatalf("first Put: %v", err)
	}
	info, err := store.Put(ctx, "apks/debug/a.apk", bytes.NewReader([]byte("second longer")))
	if err != nil {
		t.Fatalf("second Put: %v", err)
	}
	if info.Size != int64(len("second longer")) {
		t.Errorf("size after overwrite = %d", info.Size)
	}
	object, _, err := store.Open(ctx, "apks/debug/a.apk")
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer object.Close()
	content, _ := io.ReadAll(object)
	if string(content) != "second longer" {
		t.Errorf("content after overwrite = %q", content)
	}
}

func TestMovePublishesStagedObject(t *testing.T) {
	store := newTestStore(t)
	ctx := context.Background()
	payload := bytes.Repeat([]byte("z"), 4096)

	if _, err := store.Put(ctx, "staging/debug/1-1.apk", bytes.NewReader(payload)); err != nil {
		t.Fatalf("stage Put: %v", err)
	}
	info, err := store.Move(ctx, "staging/debug/1-1.apk", "apks/debug/deadbeef.apk")
	if err != nil {
		t.Fatalf("Move: %v", err)
	}
	if info.Size != int64(len(payload)) {
		t.Errorf("moved size = %d, want %d", info.Size, len(payload))
	}
	if _, err := store.Stat(ctx, "staging/debug/1-1.apk"); !errors.Is(err, ErrNotFound) {
		t.Errorf("staged object still present: %v", err)
	}
}

func TestRejectsTraversalAndUnsafeKeys(t *testing.T) {
	store := newTestStore(t)
	ctx := context.Background()

	for _, key := range []string{
		"../outside.apk",
		"/absolute/outside.apk",
		"a/../../b.apk",
		"ok/../../nope",
		"with spaces/file.apk",
		"semi;colon",
		"back\\slash",
		"",
	} {
		if _, err := store.Put(ctx, key, bytes.NewReader([]byte("x"))); err == nil {
			t.Errorf("Put(%q) accepted an unsafe key", key)
		}
		if _, err := store.Stat(ctx, key); err == nil {
			t.Errorf("Stat(%q) accepted an unsafe key", key)
		}
	}

	// Nothing may be written outside the root.
	matches, err := filepath.Glob(filepath.Join(filepath.Dir(store.Root), "outside.apk"))
	if err != nil {
		t.Fatal(err)
	}
	if len(matches) > 0 {
		t.Fatalf("escape wrote %v", matches)
	}
}

func TestDeleteMissingObjectIsNotAnError(t *testing.T) {
	store := newTestStore(t)
	if err := store.Delete(context.Background(), "apks/debug/never.apk"); err != nil {
		t.Fatalf("Delete of a missing object: %v", err)
	}
}

func TestOpenMissingObjectIsNotFound(t *testing.T) {
	store := newTestStore(t)
	_, _, err := store.Open(context.Background(), "apks/debug/never.apk")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("Open missing = %v, want ErrNotFound", err)
	}
}

func TestCleanupTempRemovesOnlyStaleScratch(t *testing.T) {
	store := newTestStore(t)
	ctx := context.Background()
	if _, err := store.Put(ctx, "apks/debug/keep.apk", bytes.NewReader([]byte("committed"))); err != nil {
		t.Fatalf("Put: %v", err)
	}

	stale := filepath.Join(store.Root, "apks", "debug", "keep.apk.tmp-12345")
	fresh := filepath.Join(store.Root, "apks", "debug", "keep.apk.tmp-99999")
	if err := os.WriteFile(stale, []byte("abandoned"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(fresh, []byte("in flight"), 0o644); err != nil {
		t.Fatal(err)
	}
	past := time.Now().Add(-2 * time.Hour)
	if err := os.Chtimes(stale, past, past); err != nil {
		t.Fatal(err)
	}

	removed, err := store.CleanupTemp(ctx, time.Hour)
	if err != nil {
		t.Fatalf("CleanupTemp: %v", err)
	}
	if removed != 1 {
		t.Errorf("CleanupTemp removed %d, want 1", removed)
	}
	if _, err := os.Stat(stale); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("stale scratch survived")
	}
	if _, err := os.Stat(fresh); err != nil {
		t.Errorf("fresh scratch was removed: %v", err)
	}
	if _, err := store.Stat(ctx, "apks/debug/keep.apk"); err != nil {
		t.Errorf("committed object was touched: %v", err)
	}
}

type failingReader struct{}

func (failingReader) Read([]byte) (int, error) { return 0, errors.New("connection reset") }

func assertNoTempFiles(t *testing.T, root string) {
	t.Helper()
	err := filepath.WalkDir(root, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !entry.IsDir() && strings.Contains(entry.Name(), tempMarker) {
			t.Errorf("leftover scratch file %s", path)
		}
		return nil
	})
	if err != nil {
		t.Fatalf("walk: %v", err)
	}
}
