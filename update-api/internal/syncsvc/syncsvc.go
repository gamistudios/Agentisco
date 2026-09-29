// Package syncsvc keeps the local cache in step with the private GitHub
// repository, so no client request has to reach GitHub.
//
//	GitHub -> background sync -> APK downloaded once -> object cache -> clients
package syncsvc

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math/rand/v2"
	"path"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"agentisco/updateapi/internal/ghapi"
	"agentisco/updateapi/internal/metastore"
	"agentisco/updateapi/internal/model"
	"agentisco/updateapi/internal/notes"
	"agentisco/updateapi/internal/objectstore"
	"agentisco/updateapi/internal/version"
)

// Channels may hold several generations of release rows; only the newest few
// are remembered, and only the current one keeps its APK bytes.
const retainedReleases = 5

// Options wires the synchroniser.
type Options struct {
	Client    *ghapi.Client
	Meta      *metastore.DB
	Objects   objectstore.Store
	Sanitizer *notes.Sanitizer
	Logger    *slog.Logger
	Interval  time.Duration
	// PassTimeout bounds one whole synchronisation pass, including GitHub calls
	// and the APK transfer. A poor connection is expected to need a long budget.
	PassTimeout time.Duration
	// StallTimeout aborts a transfer only while no bytes arrive at all, so a slow
	// link keeps going and a dead one is abandoned for the next pass to retry.
	StallTimeout time.Duration
	// StartupDelay holds back the first pass; useful when the process starts
	// before the network is ready.
	StartupDelay time.Duration
}

// Syncer owns the background synchronisation of both channels.
type Syncer struct {
	client       *ghapi.Client
	meta         *metastore.DB
	objects      objectstore.Store
	sanitizer    *notes.Sanitizer
	logger       *slog.Logger
	interval     time.Duration
	passTimeout  time.Duration
	fillTimeout  time.Duration
	stallTimeout time.Duration
	startupDelay time.Duration
	// now is overridable so tests can drive the ticker.
	now func() time.Time

	// channelMu serialises whole-channel passes; a tick that finds its channel
	// busy is skipped rather than queued.
	channelMuMu sync.Mutex
	channelMu   map[model.Channel]*sync.Mutex

	// fillMu guards per-asset transfers so the same APK is never downloaded
	// twice at once.
	fillMu     sync.Mutex
	fillInProc map[string]struct{}
}

// New builds a synchroniser.
func New(opts Options) *Syncer {
	interval := opts.Interval
	if interval <= 0 {
		interval = 60 * time.Second
	}
	stall := opts.StallTimeout
	if stall <= 0 {
		stall = 30 * time.Second
	}
	logger := opts.Logger
	if logger == nil {
		logger = slog.Default()
	}
	return &Syncer{
		client:       opts.Client,
		meta:         opts.Meta,
		objects:      opts.Objects,
		sanitizer:    opts.Sanitizer,
		logger:       logger,
		interval:     interval,
		passTimeout:  opts.PassTimeout,
		fillTimeout:  opts.PassTimeout,
		stallTimeout: stall,
		startupDelay: opts.StartupDelay,
		now:          time.Now,
		channelMu:    map[model.Channel]*sync.Mutex{},
		fillInProc:   map[string]struct{}{},
	}
}

// Run performs the first pass and then repeats on the configured interval until
// the context is cancelled. Errors are logged, never fatal: GitHub being down
// must not take the service with it.
func (s *Syncer) Run(ctx context.Context) {
	if delay := s.startupDelay; delay > 0 {
		timer := time.NewTimer(delay)
		select {
		case <-ctx.Done():
			timer.Stop()
			return
		case <-timer.C:
		}
	}

	s.pass(ctx)
	ticker := time.NewTicker(s.interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			s.logger.Info("sync worker stopped", "reason", ctx.Err())
			return
		case <-ticker.C:
			s.pass(ctx)
		}
	}
}

// Interval exposes the configured cadence for health reporting.
func (s *Syncer) Interval() time.Duration { return s.interval }

// passContext bounds one synchronisation pass.
func (s *Syncer) passContext(ctx context.Context) (context.Context, context.CancelFunc) {
	if s.passTimeout <= 0 {
		return ctx, func() {}
	}
	return context.WithTimeout(ctx, s.passTimeout)
}

// pass refreshes every channel from a single listing of the releases.
//
// Both channels are resolved from the same snapshot, so a tick costs one metadata
// request to GitHub rather than one per channel; each channel still gets its own
// download budget.
func (s *Syncer) pass(ctx context.Context) {
	listCtx, cancelList := s.passContext(ctx)
	releases, err := s.listReleases(listCtx)
	cancelList()

	for _, channel := range model.Channels {
		if ctx.Err() != nil {
			return
		}
		syncCtx, cancelSync := s.passContext(ctx)
		s.runChannel(syncCtx, channel, releases, err)
		cancelSync()
	}
}

func (s *Syncer) listReleases(ctx context.Context) ([]ghapi.Release, error) {
	return s.client.ListReleases(ctx)
}

// SyncChannel resolves and caches the newest valid release for one channel.
//
// Channel resolution is explicit: the debug channel is the newest release that
// actually carries a debug APK, the release channel the newest release that
// carries a release APK. The globally newest release is never used as an answer
// for both.
func (s *Syncer) SyncChannel(ctx context.Context, channel model.Channel) {
	passCtx, cancel := s.passContext(ctx)
	defer cancel()
	releases, err := s.listReleases(passCtx)
	s.runChannel(passCtx, channel, releases, err)
}

// runChannel applies a release listing to one channel, serialised against the
// previous pass for that channel.
func (s *Syncer) runChannel(ctx context.Context, channel model.Channel, releases []ghapi.Release, listingErr error) {
	lock := s.channelLock(channel)
	if !lock.TryLock() {
		s.logger.Info("sync skipped, channel already syncing", "channel", channel.String())
		return
	}
	defer lock.Unlock()

	started := s.now()
	s.logger.Info("sync started", "channel", channel.String())

	if listingErr != nil {
		s.recordListingFailure(ctx, channel, started, listingErr)
		return
	}
	if err := s.syncChannel(ctx, channel, started, releases); err != nil {
		s.recordFailure(ctx, channel, started, statusForError(err), err)
		return
	}
	s.logger.Info("sync completed",
		"channel", channel.String(),
		"duration_ms", time.Since(started).Milliseconds())
}

// recordListingFailure keeps the last known good release while GitHub is
// unreachable or has published nothing yet.
func (s *Syncer) recordListingFailure(ctx context.Context, channel model.Channel, at time.Time, err error) {
	if errors.Is(err, ghapi.ErrNoReleases) {
		s.logger.Warn("github has no published releases", "channel", channel.String())
		s.recordFailure(ctx, channel, at, metastore.SyncNoChange, err)
		return
	}
	s.logger.Warn("github unavailable", "channel", channel.String(), "error", publicError(err))
	s.recordFailure(ctx, channel, at, statusForError(err), err)
}

func statusForError(err error) metastore.SyncStatus {
	if errors.Is(err, ghapi.ErrUnavailable) || errors.Is(err, ghapi.ErrUnauthorized) {
		return metastore.SyncUnavailable
	}
	return metastore.SyncError
}

// syncChannel stores the channel's releases and promotes the newest one that
// carries this channel's asset.
func (s *Syncer) syncChannel(ctx context.Context, channel model.Channel, started time.Time, releases []ghapi.Release) error {
	// Every release that can serve this channel is recorded, so a client that was
	// pointed at a slightly older build can still resolve it and so retention has
	// a real history to prune. Only the newest matching release is offered, and
	// only its bytes are cached.
	type candidate struct {
		release ghapi.Release
		asset   ghapi.Asset
	}
	var candidates []candidate
	for _, release := range releases {
		asset, ok := selectAsset(channel, release.Assets)
		if !ok {
			continue
		}
		candidates = append(candidates, candidate{release: release, asset: asset})
	}

	if len(candidates) == 0 {
		// Every release was checked and none carries this channel's asset, which
		// is a publishing problem, not an outage. Keep offering the last release
		// that did, if we ever had one.
		const reason = "no release carries this channel's asset"
		current, hadCurrent, err := s.meta.CurrentRelease(ctx, channel)
		if err != nil {
			return err
		}
		if hadCurrent {
			s.logger.Warn("no release carries this channel's asset; keeping last known good",
				"channel", channel.String(), "keeping_tag", current.Tag)
		} else {
			s.logger.Warn("no release carries this channel's asset yet",
				"channel", channel.String())
		}
		return s.meta.RecordAttempt(ctx, channel, started, metastore.SyncNoChange, reason)
	}

	for _, entry := range candidates {
		if err := s.meta.UpsertRelease(ctx, s.buildRecord(channel, entry.release, entry.asset, started)); err != nil {
			return err
		}
	}

	chosen := candidates[0]
	record := s.buildRecord(channel, chosen.release, chosen.asset, started)
	s.logger.Info("github release detected",
		"channel", channel.String(),
		"tag", record.Tag,
		"asset", record.ApkName,
		"bytes", record.Size)

	previous, hadPrevious, err := s.meta.CurrentRelease(ctx, channel)
	if err != nil {
		return err
	}
	status := metastore.SyncOK
	if hadPrevious && previous.Tag == record.Tag {
		status = metastore.SyncNoChange
	}

	// The metadata is promoted before the bytes are fetched: a client on a slow
	// link must be able to compare its version the moment the release is known,
	// and the download endpoint tells the truth about caching either way.
	if err := s.meta.SetCurrent(ctx, channel, record.Tag, started, status); err != nil {
		return err
	}
	s.logger.Info("sync channel current",
		"channel", channel.String(),
		"tag", record.Tag,
		"version_code", record.VersionCode,
		"status", string(status),
		"cached", false)

	cached, err := s.ensureObject(ctx, channel, record)
	if err != nil {
		s.logger.Warn("apk cache failed",
			"channel", channel.String(), "tag", record.Tag, "error", publicError(err))
		if setErr := s.meta.SetCacheState(ctx, channel, record.Tag, metastore.CachePending, ""); setErr != nil {
			return setErr
		}
		return s.meta.RecordAttempt(ctx, channel, started, metastore.SyncUnavailable, err.Error())
	}

	record.ObjectKey = cached
	record.CacheState = metastore.CacheCached
	if err := s.meta.SetCacheState(ctx, channel, record.Tag, metastore.CacheCached, cached); err != nil {
		return err
	}
	s.logger.Info("apk ready for download",
		"channel", channel.String(), "tag", record.Tag, "bytes", record.Size)

	s.prune(ctx, channel)
	if hadPrevious && previous.Tag != record.Tag {
		s.retire(previous)
	}
	return nil
}

// buildRecord turns a GitHub release into the stored metadata row.
func (s *Syncer) buildRecord(channel model.Channel, release ghapi.Release, asset ghapi.Asset, syncedAt time.Time) metastore.Release {
	tag := release.TagName
	versionName := release.Name
	if versionName == "" {
		versionName = tag
	}
	code := version.Code(tag)
	if code == 0 {
		// A tag that carries no digits (an unusual manual release) still needs a
		// comparable number, so fall back to the release name.
		code = version.Code(versionName)
	}
	return metastore.Release{
		Channel:      channel,
		Tag:          tag,
		VersionName:  versionName,
		VersionCode:  code,
		ApkName:      asset.Name,
		AssetID:      asset.ID,
		Size:         asset.Size,
		SHA256:       normalizedDigest(asset.Digest),
		ReleaseNotes: s.sanitizer.Process(release.Body),
		PublishedAt:  release.PublishedAt,
		SyncedAt:     syncedAt,
		CacheState:   metastore.CachePending,
	}
}

// ensureObject makes sure the record's APK is in the object cache and returns
// the object key holding it. The transfer happens at most once per asset:
// identical bytes across releases share one object, and a cached asset is never
// re-fetched.
func (s *Syncer) ensureObject(ctx context.Context, channel model.Channel, record metastore.Release) (string, error) {
	existing, found, err := s.meta.ReleaseByTag(ctx, channel, record.Tag)
	if err != nil {
		return "", err
	}
	if found && existing.CacheState == metastore.CacheCached && existing.AssetID == record.AssetID &&
		existing.ObjectKey != "" {
		if _, statErr := s.objects.Stat(ctx, existing.ObjectKey); statErr == nil {
			s.logger.Info("apk already cached",
				"channel", channel.String(), "tag", record.Tag, "bytes", existing.Size)
			return existing.ObjectKey, nil
		}
	}

	// When GitHub published the digest we can address the object up front and
	// skip a transfer whose bytes we already hold.
	if expected := digestKey(channel, record.SHA256); record.SHA256 != "" && expected != "" {
		if _, statErr := s.objects.Stat(ctx, expected); statErr == nil {
			s.logger.Info("apk already cached",
				"channel", channel.String(), "tag", record.Tag, "bytes", record.Size)
			return expected, nil
		}
	}

	return s.download(ctx, channel, record)
}

// download streams the asset into a staged object, verifies it, then publishes
// it under its content-addressed key.
func (s *Syncer) download(ctx context.Context, channel model.Channel, record metastore.Release) (string, error) {
	asset := ghapi.Asset{ID: record.AssetID, Name: record.ApkName, Size: record.Size, Digest: record.SHA256}
	return s.Fetch(ctx, channel, asset, record.Tag)
}

// Fetch transfers one asset with single-flight protection and verification. It
// is used both by the scheduler and by the on-demand cache fill behind the
// download endpoint.
func (s *Syncer) Fetch(ctx context.Context, channel model.Channel, asset ghapi.Asset, tag string) (string, error) {
	leaseKey := fmt.Sprintf("%s/%d", channel.String(), asset.ID)
	releaseFn, err := s.acquireFill(leaseKey)
	if err != nil {
		return "", err
	}
	defer releaseFn()

	// The transfer is allowed to take as long as the link needs, but a connection
	// that delivers nothing at all is given up on: the watchdog cancels it so the
	// next pass can try again instead of holding the channel for a whole budget.
	streamCtx, cancelStream := context.WithCancel(ctx)
	watch := newStallWatch(streamCtx, cancelStream, s.stallTimeout, s.logger, channel, tag)
	defer watch.stopWatchdog()
	defer cancelStream()

	stageKey := stagingKey(channel, asset.ID)
	s.logger.Info("apk download started",
		"channel", channel.String(), "tag", tag, "asset", asset.Name, "bytes", asset.Size)

	body, size, err := s.client.OpenAsset(streamCtx, asset)
	if err != nil {
		return "", fmt.Errorf("open asset: %w", err)
	}
	defer body.Close()

	hasher := sha256.New()
	progress := &touchReader{reader: body, touch: watch.touch}
	info, err := s.objects.Put(streamCtx, stageKey, io.TeeReader(progress, hasher))
	if err != nil {
		_ = s.objects.Delete(ctx, stageKey)
		if watch.stalled.Load() {
			return "", errTransferStalled
		}
		return "", fmt.Errorf("stage apk: %w", err)
	}

	digest := hex.EncodeToString(hasher.Sum(nil))
	if err := verifyTransfer(asset, size, info.Size, digest); err != nil {
		_ = s.objects.Delete(ctx, stageKey)
		return "", err
	}

	finalKey := digestKey(channel, digest)
	if _, statErr := s.objects.Stat(ctx, finalKey); statErr == nil {
		// Another release already contributed these exact bytes.
		_ = s.objects.Delete(ctx, stageKey)
	} else {
		if _, moveErr := s.objects.Move(ctx, stageKey, finalKey); moveErr != nil {
			_ = s.objects.Delete(ctx, stageKey)
			return "", fmt.Errorf("publish apk: %w", moveErr)
		}
	}

	s.logger.Info("apk cached",
		"channel", channel.String(),
		"tag", tag,
		"bytes", info.Size,
		"sha256", digest)
	return finalKey, nil
}

// FillAsset is the on-demand path used when a client asks for an APK this server
// does not hold yet. It is single-flight: a second concurrent request for the
// same asset is told to come back rather than starting a second transfer.
func (s *Syncer) FillAsset(ctx context.Context, channel model.Channel, record metastore.Release) error {
	asset := ghapi.Asset{ID: record.AssetID, Name: record.ApkName, Size: record.Size, Digest: record.SHA256}
	key, err := s.Fetch(ctx, channel, asset, record.Tag)
	if err != nil {
		s.logger.Warn("on-demand cache fill failed",
			"channel", channel.String(), "tag", record.Tag, "error", publicError(err))
		_ = s.meta.SetCacheState(ctx, channel, record.Tag, metastore.CachePending, "")
		return err
	}
	if err := s.meta.SetCacheState(ctx, channel, record.Tag, metastore.CacheCached, key); err != nil {
		return err
	}
	s.logger.Info("on-demand cache fill completed", "channel", channel.String(), "tag", record.Tag)
	return nil
}

// ErrFillInProgress tells a caller that another request already started this
// transfer.
var ErrFillInProgress = errors.New("cache fill already in progress")

func (s *Syncer) acquireFill(key string) (func(), error) {
	s.fillMu.Lock()
	defer s.fillMu.Unlock()
	if _, busy := s.fillInProc[key]; busy {
		return nil, ErrFillInProgress
	}
	if s.fillInProc == nil {
		s.fillInProc = map[string]struct{}{}
	}
	s.fillInProc[key] = struct{}{}
	return func() {
		s.fillMu.Lock()
		delete(s.fillInProc, key)
		s.fillMu.Unlock()
	}, nil
}

// FillLatest starts a background fill for the channel's current release; the
// download endpoint uses it so a client never receives partial bytes and the
// request path never blocks on GitHub.
func (s *Syncer) FillLatest(ctx context.Context, channel model.Channel, record metastore.Release) {
	go func() {
		ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), s.fillTimeout+10*time.Second)
		defer cancel()
		if err := s.FillAsset(ctx, channel, record); err != nil {
			return
		}
	}()
}

// errTransferStalled distinguishes "the link went quiet" from a genuine
// transport failure, so logs say what actually happened.
var errTransferStalled = errors.New("apk transfer stalled")

// touchReader reports progress to the stall watchdog without buffering.
type touchReader struct {
	reader io.Reader
	touch  func()
}

func (t *touchReader) Read(p []byte) (int, error) {
	n, err := t.reader.Read(p)
	if n > 0 {
		t.touch()
	}
	return n, err
}

// stallWatch cancels a transfer that stops making progress. A slow link is left
// alone: only silence for the whole window counts as dead, which is what lets a
// poor connection still deliver a large APK while a hung one is released quickly.
type stallWatch struct {
	window   time.Duration
	logger   *slog.Logger
	channel  model.Channel
	tag      string
	done     chan struct{}
	stalled  atomic.Bool
	lastMove atomic.Int64
}

func newStallWatch(ctx context.Context, cancel context.CancelFunc, window time.Duration,
	logger *slog.Logger, channel model.Channel, tag string) *stallWatch {
	w := &stallWatch{
		window:  window,
		logger:  logger,
		channel: channel,
		tag:     tag,
		done:    make(chan struct{}),
	}
	w.lastMove.Store(time.Now().UnixNano())

	go func() {
		ticker := time.NewTicker(max(window/2, time.Second))
		defer ticker.Stop()
		for {
			select {
			case <-w.done:
				return
			case <-ctx.Done():
				return
			case <-ticker.C:
				silent := time.Since(time.Unix(0, w.lastMove.Load()))
				if silent >= window {
					w.stalled.Store(true)
					logger.Warn("apk transfer stalled",
						"channel", channel.String(),
						"tag", tag,
						"seconds_silent", int64(silent.Seconds()))
					cancel()
					return
				}
			}
		}
	}()
	return w
}

func (w *stallWatch) touch() { w.lastMove.Store(time.Now().UnixNano()) }

func (w *stallWatch) stopWatchdog() {
	select {
	case <-w.done:
	default:
		close(w.done)
	}
}

// verifyTransfer proves the staged bytes are the whole asset before they are
// ever published.
func verifyTransfer(asset ghapi.Asset, streamedSize, onDiskSize int64, digest string) error {
	expected := asset.Size
	if expected > 0 && onDiskSize != expected {
		return fmt.Errorf("apk size mismatch: expected %d bytes, stored %d", expected, onDiskSize)
	}
	if expected <= 0 && onDiskSize <= 0 {
		return errors.New("apk size unknown after transfer")
	}
	if reported := normalizedDigest(asset.Digest); reported != "" && reported != digest {
		return fmt.Errorf("apk sha256 mismatch: release reports %s, transfer produced %s", reported, digest)
	}
	return nil
}

// digestKey addresses an object by content so identical APKs exist once.
func digestKey(channel model.Channel, digest string) string {
	if len(digest) != 64 {
		return ""
	}
	return path.Join("apks", channel.String(), digest+".apk")
}

func stagingKey(channel model.Channel, assetID int64) string {
	nonce := rand.Int64N(1<<40) + 1
	return path.Join("staging", channel.String(), fmt.Sprintf("%d-%d.apk", assetID, nonce))
}

// normalizedDigest accepts GitHub's "sha256:<hex>" form, a bare hex digest, or
// nothing at all, matching what the Android client already tolerates.
func normalizedDigest(raw string) string {
	value := strings.TrimSpace(raw)
	value = strings.TrimPrefix(strings.ToLower(value), "sha256:")
	value = strings.TrimSpace(value)
	if len(value) != 64 {
		return ""
	}
	for _, r := range value {
		if !(r >= '0' && r <= '9' || r >= 'a' && r <= 'f') {
			return ""
		}
	}
	return value
}

// selectAsset picks the asset for one channel out of a release, preferring the
// CI naming convention over a looser match.
func selectAsset(channel model.Channel, assets []ghapi.Asset) (ghapi.Asset, bool) {
	suffix := "-" + channel.String() + ".apk"
	var best ghapi.Asset
	bestScore := 0
	for _, asset := range assets {
		if !channel.MatchesAsset(asset.Name) {
			continue
		}
		score := 1
		if strings.HasSuffix(strings.ToLower(strings.TrimSpace(asset.Name)), suffix) {
			score = 2
		}
		if score > bestScore {
			best, bestScore = asset, score
		}
	}
	return best, bestScore > 0
}

func (s *Syncer) channelLock(channel model.Channel) *sync.Mutex {
	s.channelMuMu.Lock()
	defer s.channelMuMu.Unlock()
	if s.channelMu == nil {
		s.channelMu = map[model.Channel]*sync.Mutex{}
	}
	lock, ok := s.channelMu[channel]
	if !ok {
		lock = &sync.Mutex{}
		s.channelMu[channel] = lock
	}
	return lock
}

func (s *Syncer) recordFailure(ctx context.Context, channel model.Channel, at time.Time, status metastore.SyncStatus, err error) {
	message := publicError(err)
	s.logger.Warn("sync failed", "channel", channel.String(), "status", string(status), "error", message)
	// Bookkeeping only: a failed pass must never drop the previously cached
	// version, which is what keeps clients working through a GitHub outage.
	if setErr := s.meta.RecordAttempt(ctx, channel, at, status, message); setErr != nil {
		s.logger.Error("could not record sync failure", "channel", channel.String(), "error", setErr)
	}
}

// prune forgets release rows beyond the retention window.
func (s *Syncer) prune(ctx context.Context, channel model.Channel) {
	stale, err := s.meta.Forget(ctx, channel, retainedReleases)
	if err != nil {
		s.logger.Warn("could not prune release history", "channel", channel.String(), "error", err)
		return
	}
	for _, record := range stale {
		s.retire(record)
	}
}

// retire deletes cached bytes that no live release references any more, and
// marks the row that pointed at them as evicted so metadata never promises a
// file this server dropped. Housekeeping gets its own deadline: a pass that is
// cancelled or cut short must not leave orphaned APKs behind.
func (s *Syncer) retire(record metastore.Release) {
	if record.ObjectKey == "" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	referenced, err := s.meta.ObjectReferenced(ctx, record.ObjectKey, record.Channel, record.Tag)
	if err != nil {
		s.logger.Warn("could not check apk references", "error", err)
		return
	}
	if referenced {
		// Identical APKs share one content-addressed object; another release
		// still needs these bytes.
		return
	}
	if err := s.objects.Delete(ctx, record.ObjectKey); err != nil {
		s.logger.Warn("could not delete retired apk", "error", err)
		return
	}
	if err := s.meta.SetCacheState(ctx, record.Channel, record.Tag, metastore.CacheEvicted, ""); err != nil {
		s.logger.Warn("could not mark retired apk", "error", err)
	}
	s.logger.Info("retired cached apk", "channel", record.Channel.String(), "tag", record.Tag)
}

// publicError keeps repository coordinates and credentials out of logs.
func publicError(err error) string {
	if err == nil {
		return ""
	}
	switch {
	case errors.Is(err, ghapi.ErrUnauthorized):
		return "github rejected the credential"
	case errors.Is(err, ghapi.ErrNotFound):
		return "github no longer has that release"
	case errors.Is(err, ghapi.ErrUnavailable):
		return "github unavailable"
	case errors.Is(err, context.DeadlineExceeded):
		return "github request timed out"
	case errors.Is(err, context.Canceled):
		return "cancelled"
	default:
		return err.Error()
	}
}
