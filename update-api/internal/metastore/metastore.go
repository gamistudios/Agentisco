// Package metastore persists what the synchroniser learned about each release.
//
// Metadata is stored separately from the APK bytes (which live in the object
// store) and survives a restart, so a redeploy with an unreachable GitHub keeps
// serving its last known good version.
package metastore

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite"

	"awaki/updateapi/internal/model"
)

// CacheState describes whether this server holds the APK bytes.
type CacheState string

const (
	CachePending CacheState = "pending"
	CacheCached  CacheState = "cached"
	CacheEvicted CacheState = "evicted"
)

// SyncStatus is the outcome of the most recent synchronisation of a channel.
type SyncStatus string

const (
	SyncOK          SyncStatus = "ok"
	SyncNoChange    SyncStatus = "no_change"
	SyncUnavailable SyncStatus = "unavailable"
	SyncError       SyncStatus = "error"
)

// Release is one release of one channel, with its cache bookkeeping.
type Release struct {
	Channel      model.Channel
	Tag          string
	VersionName  string
	VersionCode  int64
	ApkName      string
	AssetID      int64
	Size         int64
	SHA256       string
	ReleaseNotes string
	PublishedAt  time.Time
	SyncedAt     time.Time
	ObjectKey    string
	CacheState   CacheState
}

// Channel is the per-channel view of the latest known good release.
type Channel struct {
	Channel       model.Channel
	CurrentTag    string
	LastAttemptAt time.Time
	LastSuccessAt time.Time
	Status        SyncStatus
	LastError     string
}

// DB is the metadata database.
type DB struct {
	handle *sql.DB
	path   string
}

const schema = `
CREATE TABLE IF NOT EXISTS releases (
	channel        TEXT    NOT NULL,
	tag            TEXT    NOT NULL,
	version_name   TEXT    NOT NULL,
	version_code   INTEGER NOT NULL,
	apk_name       TEXT    NOT NULL,
	asset_id       INTEGER NOT NULL,
	size_bytes     INTEGER NOT NULL,
	sha256         TEXT    NOT NULL,
	release_notes  TEXT    NOT NULL,
	published_at   TEXT    NOT NULL,
	synced_at      TEXT    NOT NULL,
	object_key     TEXT    NOT NULL DEFAULT '',
	cache_state    TEXT    NOT NULL DEFAULT 'pending',
	PRIMARY KEY (channel, tag)
);

CREATE INDEX IF NOT EXISTS idx_releases_channel_code
	ON releases (channel, version_code DESC);

CREATE TABLE IF NOT EXISTS channels (
	channel          TEXT PRIMARY KEY,
	current_tag      TEXT    NOT NULL DEFAULT '',
	last_attempt_at  TEXT    NOT NULL DEFAULT '',
	last_success_at  TEXT    NOT NULL DEFAULT '',
	status           TEXT    NOT NULL DEFAULT 'pending',
	last_error       TEXT    NOT NULL DEFAULT ''
);
`

// Open prepares (creating if needed) the SQLite database at path.
func Open(path string) (*DB, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, fmt.Errorf("metastore: create dir: %w", err)
	}
	dsn := "file:" + filepath.ToSlash(filepath.Clean(path)) + "?" + url.Values{
		"_pragma": []string{"busy_timeout(10000)", "journal_mode(WAL)", "foreign_keys(1)", "synchronous(NORMAL)"},
	}.Encode()

	handle, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("metastore: open: %w", err)
	}
	// One writer at a time: the synchroniser is the only writer and HTTP reads
	// are millisecond index lookups, so this removes lock contention entirely.
	handle.SetMaxOpenConns(1)
	handle.SetConnMaxLifetime(time.Hour)

	if err := handle.Ping(); err != nil {
		handle.Close()
		return nil, fmt.Errorf("metastore: connect: %w", err)
	}
	if _, err := handle.Exec(schema); err != nil {
		handle.Close()
		return nil, fmt.Errorf("metastore: migrate: %w", err)
	}
	for _, channel := range model.Channels {
		if _, err := handle.ExecContext(context.Background(),
			`INSERT OR IGNORE INTO channels (channel) VALUES (?)`, channel.String()); err != nil {
			handle.Close()
			return nil, fmt.Errorf("metastore: seed channels: %w", err)
		}
	}
	return &DB{handle: handle, path: path}, nil
}

// Path reports where the database lives.
func (d *DB) Path() string { return d.path }

// Close releases the database handle.
func (d *DB) Close() error {
	if d == nil {
		return nil
	}
	return d.handle.Close()
}

// Ping verifies the store is usable; it backs the readiness endpoint.
func (d *DB) Ping(ctx context.Context) error { return d.handle.PingContext(ctx) }

// UpsertRelease stores the metadata of one release of one channel.
//
// cache_state and object_key are deliberately not touched on conflict: they
// describe what this instance holds, and only SetCacheState may change them. A
// metadata refresh on every pass must not make a cached APK look missing.
func (d *DB) UpsertRelease(ctx context.Context, r Release) error {
	_, err := d.handle.ExecContext(ctx, `
		INSERT INTO releases (
			channel, tag, version_name, version_code, apk_name, asset_id,
			size_bytes, sha256, release_notes, published_at, synced_at, object_key, cache_state
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT (channel, tag) DO UPDATE SET
			version_name = excluded.version_name,
			version_code = excluded.version_code,
			apk_name     = excluded.apk_name,
			asset_id     = excluded.asset_id,
			size_bytes   = excluded.size_bytes,
			sha256       = excluded.sha256,
			release_notes = excluded.release_notes,
			published_at = excluded.published_at,
			synced_at    = excluded.synced_at`,
		r.Channel.String(), r.Tag, r.VersionName, r.VersionCode, r.ApkName, r.AssetID,
		r.Size, r.SHA256, r.ReleaseNotes, r.PublishedAt.UTC().Format(time.RFC3339Nano),
		r.SyncedAt.UTC().Format(time.RFC3339Nano), r.ObjectKey, string(r.CacheState))
	if err != nil {
		return fmt.Errorf("metastore: upsert release: %w", err)
	}
	return nil
}

// SetCacheState records the object-store outcome for one release.
func (d *DB) SetCacheState(ctx context.Context, channel model.Channel, tag string, state CacheState, objectKey string) error {
	_, err := d.handle.ExecContext(ctx,
		`UPDATE releases SET cache_state = ?, object_key = ? WHERE channel = ? AND tag = ?`,
		string(state), objectKey, channel.String(), tag)
	if err != nil {
		return fmt.Errorf("metastore: set cache state: %w", err)
	}
	return nil
}

// Release lookup helpers.

type row interface {
	Scan(dest ...any) error
}

func scanRelease(target row) (Release, error) {
	var r Release
	var publishedAt, syncedAt, state string
	err := target.Scan(
		&r.Tag, &r.VersionName, &r.VersionCode, &r.ApkName, &r.AssetID,
		&r.Size, &r.SHA256, &r.ReleaseNotes, &publishedAt, &syncedAt,
		&r.ObjectKey, &state)
	if err != nil {
		return Release{}, err
	}
	r.PublishedAt = parseTime(publishedAt)
	r.SyncedAt = parseTime(syncedAt)
	r.CacheState = CacheState(state)
	if r.CacheState == "" {
		r.CacheState = CachePending
	}
	return r, nil
}

// CurrentRelease returns the release a channel currently offers, if any.
func (d *DB) CurrentRelease(ctx context.Context, channel model.Channel) (Release, bool, error) {
	var currentTag string
	err := d.handle.QueryRowContext(ctx,
		`SELECT current_tag FROM channels WHERE channel = ?`, channel.String()).Scan(&currentTag)
	if errors.Is(err, sql.ErrNoRows) {
		return Release{}, false, nil
	}
	if err != nil {
		return Release{}, false, fmt.Errorf("metastore: read channel: %w", err)
	}
	if currentTag == "" {
		return Release{}, false, nil
	}
	return d.ReleaseByTag(ctx, channel, currentTag)
}

// ReleaseByTag returns one stored release of a channel.
func (d *DB) ReleaseByTag(ctx context.Context, channel model.Channel, tag string) (Release, bool, error) {
	target := d.handle.QueryRowContext(ctx, `
		SELECT tag, version_name, version_code, apk_name, asset_id, size_bytes, sha256,
		       release_notes, published_at, synced_at, object_key, cache_state
		FROM releases WHERE channel = ? AND tag = ?`, channel.String(), tag)
	r, err := scanRelease(target)
	if errors.Is(err, sql.ErrNoRows) {
		return Release{}, false, nil
	}
	if err != nil {
		return Release{}, false, fmt.Errorf("metastore: read release: %w", err)
	}
	r.Channel = channel
	return r, true, nil
}

// RecordAttempt writes the bookkeeping of one synchronisation attempt. It never
// touches current_tag, so a failed pass keeps the last known good version.
func (d *DB) RecordAttempt(ctx context.Context, channel model.Channel, attempt time.Time, status SyncStatus, lastError string) error {
	_, err := d.handle.ExecContext(ctx, `
		UPDATE channels SET last_attempt_at = ?, status = ?, last_error = ?
		WHERE channel = ?`,
		attempt.UTC().Format(time.RFC3339Nano), string(status), truncate(lastError, 512), channel.String())
	if err != nil {
		return fmt.Errorf("metastore: record attempt: %w", err)
	}
	return nil
}

// SetCurrent promotes a release to be the version a channel offers and marks
// the pass successful. A release whose APK is still pending its first transfer
// is promoted too, so its metadata stays queryable while cached=false.
func (d *DB) SetCurrent(ctx context.Context, channel model.Channel, tag string, attempt time.Time, status SyncStatus) error {
	tx, err := d.handle.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("metastore: begin: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	var previousSuccess string
	if err := tx.QueryRowContext(ctx,
		`SELECT last_success_at FROM channels WHERE channel = ?`, channel.String()).Scan(&previousSuccess); err != nil {
		previousSuccess = ""
	}
	attemptText := attempt.UTC().Format(time.RFC3339Nano)
	successText := valueOr(previousSuccess, attemptText)
	if status == SyncOK || status == SyncNoChange {
		successText = attemptText
	}

	if _, err := tx.ExecContext(ctx, `
		UPDATE channels
		SET current_tag = ?, last_attempt_at = ?, last_success_at = ?, status = ?, last_error = ''
		WHERE channel = ?`,
		tag, attemptText, successText, string(status), channel.String()); err != nil {
		return fmt.Errorf("metastore: set current: %w", err)
	}
	if _, err := tx.ExecContext(ctx,
		`UPDATE releases SET synced_at = ? WHERE channel = ? AND tag = ?`,
		attemptText, channel.String(), tag); err != nil {
		return fmt.Errorf("metastore: touch release: %w", err)
	}
	return tx.Commit()
}

// Forget drops release rows older than the newest `keep` of a channel and
// returns them so their cached bytes can be deleted. The current release is
// never forgotten, so a channel always keeps one offerable version.
func (d *DB) Forget(ctx context.Context, channel model.Channel, keep int) ([]Release, error) {
	if keep < 1 {
		keep = 1
	}
	rows, err := d.handle.QueryContext(ctx, `
		SELECT tag, version_name, version_code, apk_name, asset_id, size_bytes, sha256,
		       release_notes, published_at, synced_at, object_key, cache_state
		FROM releases
		WHERE channel = ?
		  AND tag <> (SELECT current_tag FROM channels WHERE channel = ?)
		  AND tag NOT IN (
			SELECT tag FROM releases
			WHERE channel = ?
			ORDER BY version_code DESC, published_at DESC
			LIMIT ?
		  )
		ORDER BY version_code DESC, published_at DESC`,
		channel.String(), channel.String(), channel.String(), keep)
	if err != nil {
		return nil, fmt.Errorf("metastore: list stale: %w", err)
	}
	defer rows.Close()

	var stale []Release
	for rows.Next() {
		r, err := scanRelease(rows)
		if err != nil {
			return nil, fmt.Errorf("metastore: scan stale: %w", err)
		}
		r.Channel = channel
		stale = append(stale, r)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("metastore: iterate stale: %w", err)
	}
	if len(stale) == 0 {
		return nil, nil
	}

	tx, err := d.handle.BeginTx(ctx, nil)
	if err != nil {
		return nil, fmt.Errorf("metastore: begin forget: %w", err)
	}
	defer func() { _ = tx.Rollback() }()
	for _, r := range stale {
		if _, err := tx.ExecContext(ctx,
			`DELETE FROM releases WHERE channel = ? AND tag = ?`, channel.String(), r.Tag); err != nil {
			return nil, fmt.Errorf("metastore: forget release: %w", err)
		}
	}
	if err := tx.Commit(); err != nil {
		return nil, fmt.Errorf("metastore: commit forget: %w", err)
	}
	return stale, nil
}

// ObjectReferenced reports whether any release row other than (channel, tag)
// still promises the bytes stored under key. Objects are content-addressed, so
// two releases whose APKs are byte-identical share one object: dropping it must
// wait until nothing points at it.
func (d *DB) ObjectReferenced(ctx context.Context, key string, channel model.Channel, tag string) (bool, error) {
	if key == "" {
		return false, nil
	}
	var count int
	err := d.handle.QueryRowContext(ctx, `
		SELECT COUNT(*) FROM releases
		WHERE object_key = ? AND NOT (channel = ? AND tag = ?)`,
		key, channel.String(), tag).Scan(&count)
	if err != nil {
		return false, fmt.Errorf("metastore: count object references: %w", err)
	}
	return count > 0, nil
}

// ChannelSnapshot reports the synchronisation bookkeeping for a channel.
func (d *DB) ChannelSnapshot(ctx context.Context, channel model.Channel) (Channel, error) {
	var c Channel
	var attemptAt, successAt, status, lastErr sql.NullString
	err := d.handle.QueryRowContext(ctx, `
		SELECT channel, current_tag, last_attempt_at, last_success_at, status, last_error
		FROM channels WHERE channel = ?`, channel.String()).
		Scan(&c.Channel, &c.CurrentTag, &attemptAt, &successAt, &status, &lastErr)
	if err != nil {
		return Channel{}, fmt.Errorf("metastore: channel snapshot: %w", err)
	}
	c.Channel = channel
	c.LastAttemptAt = nullTime(attemptAt)
	c.LastSuccessAt = nullTime(successAt)
	c.Status = SyncStatus(status.String)
	c.LastError = lastErr.String
	return c, nil
}

// Channels lists the bookkeeping for every known channel.
func (d *DB) Channels(ctx context.Context) ([]Channel, error) {
	out := make([]Channel, 0, len(model.Channels))
	for _, channel := range model.Channels {
		snapshot, err := d.ChannelSnapshot(ctx, channel)
		if err != nil {
			return nil, err
		}
		out = append(out, snapshot)
	}
	return out, nil
}

func parseTime(value string) time.Time {
	if value == "" {
		return time.Time{}
	}
	if t, err := time.Parse(time.RFC3339Nano, value); err == nil {
		return t.UTC()
	}
	if t, err := time.Parse(time.RFC3339, value); err == nil {
		return t.UTC()
	}
	return time.Time{}
}

func nullTime(value sql.NullString) time.Time {
	if !value.Valid {
		return time.Time{}
	}
	return parseTime(value.String)
}

func truncate(value string, max int) string {
	if len(value) <= max {
		return value
	}
	return value[:max]
}

func valueOr(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}
