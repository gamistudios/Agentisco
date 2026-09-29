package metastore

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"agentisco/updateapi/internal/model"
)

func openDB(t *testing.T) *DB {
	t.Helper()
	db, err := Open(filepath.Join(t.TempDir(), "metadata.db"))
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })
	return db
}

func sample(channel model.Channel, tag string, code int64) Release {
	return Release{
		Channel:      channel,
		Tag:          tag,
		VersionName:  tag,
		VersionCode:  code,
		ApkName:      "Agentisco-" + tag + "-" + channel.String() + ".apk",
		AssetID:      code,
		Size:         1024,
		SHA256:       "digest-" + tag,
		ReleaseNotes: "## What's Changed\n* something",
		PublishedAt:  time.Now().Add(-time.Hour).UTC().Truncate(time.Second),
		SyncedAt:     time.Now().UTC().Truncate(time.Second),
		CacheState:   CachePending,
	}
}

func TestUpsertAndCurrentRoundTrip(t *testing.T) {
	db := openDB(t)
	ctx := context.Background()
	record := sample(model.ChannelRelease, "v1.4.0", 10400)

	if _, found, err := db.CurrentRelease(ctx, model.ChannelRelease); err != nil || found {
		t.Fatalf("a fresh store must have no current release (found=%v err=%v)", found, err)
	}

	if err := db.UpsertRelease(ctx, record); err != nil {
		t.Fatalf("UpsertRelease: %v", err)
	}
	if _, found, err := db.ReleaseByTag(ctx, model.ChannelRelease, "v1.4.0"); err != nil || !found {
		t.Fatalf("ReleaseByTag found=%v err=%v", found, err)
	}

	if err := db.SetCurrent(ctx, model.ChannelRelease, "v1.4.0", time.Now(), SyncOK); err != nil {
		t.Fatalf("SetCurrent: %v", err)
	}
	current, found, err := db.CurrentRelease(ctx, model.ChannelRelease)
	if err != nil || !found {
		t.Fatalf("CurrentRelease found=%v err=%v", found, err)
	}
	if current.Tag != "v1.4.0" || current.VersionCode != 10400 || current.ApkName != "Agentisco-v1.4.0-release.apk" {
		t.Fatalf("unexpected current release: %+v", current)
	}
	if current.CacheState != CachePending || current.ObjectKey != "" {
		t.Fatalf("cache bookkeeping wrong: %+v", current)
	}
	if current.ReleaseNotes == "" || current.PublishedAt.IsZero() {
		t.Fatalf("notes or published timestamp lost: %+v", current)
	}

	snapshot, err := db.ChannelSnapshot(ctx, model.ChannelRelease)
	if err != nil {
		t.Fatalf("ChannelSnapshot: %v", err)
	}
	if snapshot.CurrentTag != "v1.4.0" || snapshot.Status != SyncOK || snapshot.LastSuccessAt.IsZero() {
		t.Fatalf("unexpected snapshot: %+v", snapshot)
	}
}

func TestSetCacheStateRecordsObjectKey(t *testing.T) {
	db := openDB(t)
	ctx := context.Background()
	record := sample(model.ChannelDebug, "v1.5.0", 10500)
	if err := db.UpsertRelease(ctx, record); err != nil {
		t.Fatalf("Upsert: %v", err)
	}
	if err := db.SetCacheState(ctx, model.ChannelDebug, "v1.5.0", CacheCached, "apks/debug/aa.apk"); err != nil {
		t.Fatalf("SetCacheState: %v", err)
	}
	stored, found, err := db.ReleaseByTag(ctx, model.ChannelDebug, "v1.5.0")
	if err != nil || !found {
		t.Fatalf("lookup found=%v err=%v", found, err)
	}
	if stored.CacheState != CacheCached || stored.ObjectKey != "apks/debug/aa.apk" {
		t.Fatalf("cache state not persisted: %+v", stored)
	}

	// An upsert that carries no cache information must not undo a completed
	// transfer - the scheduler re-writes metadata on every pass.
	refreshed := record
	refreshed.ReleaseNotes = "updated notes"
	if err := db.UpsertRelease(ctx, refreshed); err != nil {
		t.Fatalf("re-Upsert: %v", err)
	}
	after, _, err := db.ReleaseByTag(ctx, model.ChannelDebug, "v1.5.0")
	if err != nil {
		t.Fatalf("lookup after re-upsert: %v", err)
	}
	if after.ReleaseNotes != "updated notes" {
		t.Fatalf("metadata refresh lost: %q", after.ReleaseNotes)
	}
	if after.CacheState != CacheCached || after.ObjectKey != "apks/debug/aa.apk" {
		t.Fatalf("re-upsert clobbered cache bookkeeping: %+v", after)
	}
}

// TestRecordAttemptKeepsLastKnownGood is the outage guarantee: a failed pass may
// update its bookkeeping but must never drop the version clients are served.
func TestRecordAttemptKeepsLastKnownGood(t *testing.T) {
	db := openDB(t)
	ctx := context.Background()
	record := sample(model.ChannelRelease, "v1.4.0", 10400)
	if err := db.UpsertRelease(ctx, record); err != nil {
		t.Fatalf("Upsert: %v", err)
	}
	if err := db.SetCurrent(ctx, model.ChannelRelease, "v1.4.0", time.Now().Add(-time.Minute), SyncOK); err != nil {
		t.Fatalf("SetCurrent: %v", err)
	}
	before, err := db.ChannelSnapshot(ctx, model.ChannelRelease)
	if err != nil {
		t.Fatalf("snapshot: %v", err)
	}

	if err := db.RecordAttempt(ctx, model.ChannelRelease, time.Now(), SyncUnavailable, "github unavailable"); err != nil {
		t.Fatalf("RecordAttempt: %v", err)
	}

	after, err := db.ChannelSnapshot(ctx, model.ChannelRelease)
	if err != nil {
		t.Fatalf("snapshot after failure: %v", err)
	}
	if after.CurrentTag != "v1.4.0" {
		t.Fatalf("failure dropped the current tag: %+v", after)
	}
	if after.Status != SyncUnavailable || after.LastError != "github unavailable" {
		t.Fatalf("failure not recorded: %+v", after)
	}
	if !after.LastSuccessAt.Equal(before.LastSuccessAt) {
		t.Fatalf("failed attempt moved last_success_at: before=%v after=%v", before.LastSuccessAt, after.LastSuccessAt)
	}

	current, found, err := db.CurrentRelease(ctx, model.ChannelRelease)
	if err != nil || !found || current.Tag != "v1.4.0" {
		t.Fatalf("clients must still be served v1.4.0 (found=%v err=%v)", found, err)
	}
}

func TestForgetKeepsCurrentAndNewest(t *testing.T) {
	db := openDB(t)
	ctx := context.Background()

	for _, code := range []int64{10100, 10200, 10300, 10400, 10500} {
		record := sample(model.ChannelRelease, codeToTag(code), code)
		record.ObjectKey = "apks/release/" + codeToTag(code) + ".apk"
		record.CacheState = CacheCached
		if err := db.UpsertRelease(ctx, record); err != nil {
			t.Fatalf("Upsert %d: %v", code, err)
		}
		if err := db.SetCacheState(ctx, model.ChannelRelease, record.Tag, CacheCached, record.ObjectKey); err != nil {
			t.Fatalf("SetCacheState: %v", err)
		}
	}
	if err := db.SetCurrent(ctx, model.ChannelRelease, "v1.5.0", time.Now(), SyncOK); err != nil {
		t.Fatalf("SetCurrent: %v", err)
	}

	stale, err := db.Forget(ctx, model.ChannelRelease, 3)
	if err != nil {
		t.Fatalf("Forget: %v", err)
	}
	if len(stale) != 2 {
		t.Fatalf("Forget returned %d rows, want the 2 oldest: %+v", len(stale), tags(stale))
	}
	for _, record := range stale {
		if record.Tag == "v1.5.0" {
			t.Fatal("the current release must never be forgotten")
		}
	}
	if stale[0].ObjectKey == "" {
		t.Fatalf("forgotten rows must carry their object key so the cache can be cleaned: %+v", stale)
	}

	remaining, err := db.Forget(ctx, model.ChannelRelease, 3)
	if err != nil {
		t.Fatalf("second Forget: %v", err)
	}
	if len(remaining) != 0 {
		t.Fatalf("Forget should be idempotent, got %+v", tags(remaining))
	}

	// The other channel is untouched.
	other := sample(model.ChannelDebug, "v9.0.0", 90000)
	if err := db.UpsertRelease(ctx, other); err != nil {
		t.Fatalf("Upsert debug: %v", err)
	}
	if err := db.SetCurrent(ctx, model.ChannelDebug, "v9.0.0", time.Now(), SyncOK); err != nil {
		t.Fatalf("SetCurrent debug: %v", err)
	}
	current, found, err := db.CurrentRelease(ctx, model.ChannelDebug)
	if err != nil || !found || current.Tag != "v9.0.0" {
		t.Fatalf("debug channel disturbed: found=%v err=%v", found, err)
	}
}

func TestSurvivesReopen(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "metadata.db")

	db, err := Open(path)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	ctx := context.Background()
	record := sample(model.ChannelRelease, "v1.4.0", 10400)
	record.CacheState = CacheCached
	record.ObjectKey = "apks/release/abc.apk"
	if err := db.UpsertRelease(ctx, record); err != nil {
		t.Fatalf("Upsert: %v", err)
	}
	if err := db.SetCacheState(ctx, model.ChannelRelease, "v1.4.0", CacheCached, record.ObjectKey); err != nil {
		t.Fatalf("SetCacheState: %v", err)
	}
	if err := db.SetCurrent(ctx, model.ChannelRelease, "v1.4.0", time.Now(), SyncOK); err != nil {
		t.Fatalf("SetCurrent: %v", err)
	}
	if err := db.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	reopened, err := Open(path)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	defer reopened.Close()

	current, found, err := reopened.CurrentRelease(ctx, model.ChannelRelease)
	if err != nil || !found {
		t.Fatalf("current release lost across restart: found=%v err=%v", found, err)
	}
	if current.Tag != "v1.4.0" || current.CacheState != CacheCached || current.ObjectKey != "apks/release/abc.apk" {
		t.Fatalf("restart lost cache bookkeeping: %+v", current)
	}
	snapshot, err := reopened.ChannelSnapshot(ctx, model.ChannelRelease)
	if err != nil || snapshot.LastSuccessAt.IsZero() {
		t.Fatalf("restart lost sync bookkeeping: %+v err=%v", snapshot, err)
	}
}

func TestReleaseByTagUnknown(t *testing.T) {
	db := openDB(t)
	if _, found, err := db.ReleaseByTag(context.Background(), model.ChannelRelease, "v0.0.0"); err != nil || found {
		t.Fatalf("unknown tag found=%v err=%v", found, err)
	}
}

func codeToTag(code int64) string {
	major := code / 10000
	minor := (code / 100) % 100
	patch := code % 100
	return "v" + itoa(major) + "." + itoa(minor) + "." + itoa(patch)
}

func itoa(value int64) string {
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

func tags(records []Release) []string {
	out := make([]string, 0, len(records))
	for _, record := range records {
		out = append(out, record.Tag)
	}
	return out
}
