package api

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"

	"agentisco/updateapi/internal/metastore"
	"agentisco/updateapi/internal/model"
	"agentisco/updateapi/internal/objectstore"
)

// apkContentType is what the Android package installer expects to see.
const apkContentType = "application/vnd.android.package-archive"

// fillRetryAfter is how long a client should wait before asking again for an
// APK this server is busy fetching. The request path never blocks on GitHub:
// the transfer is started in the background and the client resumes shortly.
const fillRetryAfter = 30 * time.Second

// handleDownload streams the cached APK.
//
// The bytes always come from the local cache, never from GitHub, so a client
// request cannot be turned into an upstream fetch storm and a GitHub outage
// cannot interrupt an in-flight download.
func (s *Server) handleDownload(w http.ResponseWriter, r *http.Request) {
	channel, ok := model.ParseChannel(r.PathValue("channel"))
	if !ok {
		s.writeError(w, http.StatusBadRequest, "invalid_channel", "channel is not a supported update channel")
		return
	}

	rawVersion := strings.TrimSpace(r.PathValue("version"))
	if rawVersion == "" {
		s.writeError(w, http.StatusBadRequest, "invalid_version", "version is required")
		return
	}

	record, found, err := s.lookupVersion(r.Context(), channel, rawVersion)
	if err != nil {
		s.log.ErrorContext(r.Context(), "download lookup failed",
			"channel", channel.String(), "error", err)
		s.writeError(w, http.StatusServiceUnavailable, "metadata_unavailable", "update metadata is temporarily unavailable")
		return
	}
	if !found {
		s.log.InfoContext(r.Context(), "download for unknown version",
			"channel", channel.String())
		s.writeError(w, http.StatusNotFound, "unknown_version", "no release is known for that version")
		return
	}

	objectKey := record.ObjectKey
	if record.CacheState != metastore.CacheCached || objectKey == "" {
		s.handleCacheMiss(w, r, channel, record)
		return
	}

	object, info, err := s.cfg.Objects.Open(r.Context(), objectKey)
	if err != nil {
		if errors.Is(err, objectstore.ErrNotFound) {
			// The metadata promises bytes this instance no longer holds (a
			// restored volume, an evicted file). Mark it and fetch it again
			// instead of serving a broken file.
			s.log.WarnContext(r.Context(), "cache miss on open",
				"channel", channel.String(), "tag", record.Tag)
			s.markUncached(r.Context(), channel, record)
			s.handleCacheMiss(w, r, channel, record)
			return
		}
		s.log.ErrorContext(r.Context(), "could not open cached apk",
			"channel", channel.String(), "tag", record.Tag, "error", err)
		s.writeError(w, http.StatusServiceUnavailable, "cache_unavailable", "cached artifact is temporarily unavailable")
		return
	}
	defer object.Close()

	if info.Size != record.Size {
		// Never hand out bytes that contradict the published checksum/size: the
		// client would fail verification and the file would keep circulating.
		s.log.ErrorContext(r.Context(), "cached apk size does not match release metadata",
			"channel", channel.String(),
			"tag", record.Tag,
			"expected_bytes", record.Size,
			"cached_bytes", info.Size)
		s.markUncached(r.Context(), channel, record)
		s.handleCacheMiss(w, r, channel, record)
		return
	}

	// The content address is the strongest possible validator: If-Range uses it,
	// so a resumed download either continues the identical file or restarts.
	if record.SHA256 != "" {
		w.Header().Set("ETag", `"`+record.SHA256+`"`)
	}
	w.Header().Set("Content-Type", apkContentType)
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("Content-Disposition", contentDisposition(record.ApkName))
	w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if !info.ModTime.IsZero() {
		w.Header().Set("Last-Modified", info.ModTime.UTC().Format(http.TimeFormat))
	}

	counter := &countingObject{object: object}
	s.log.InfoContext(r.Context(), "download started",
		"channel", channel.String(),
		"tag", record.Tag,
		"bytes", record.Size,
		"range", r.Header.Get("Range"))

	modTime := info.ModTime
	if modTime.IsZero() {
		modTime = record.SyncedAt
	}
	http.ServeContent(w, r, record.ApkName, modTime, counter)

	written := counter.read
	if record.Size > 0 && written != record.Size && r.Header.Get("Range") == "" {
		// The client disconnected mid-transfer, which is normal for resume: the
		// bytes it got are still valid, and it will ask for the rest.
		s.log.WarnContext(r.Context(), "download ended early",
			"channel", channel.String(),
			"tag", record.Tag,
			"bytes_sent", written,
			"client", clientKey(r))
		return
	}
	s.log.InfoContext(r.Context(), "download completed",
		"channel", channel.String(),
		"tag", record.Tag,
		"bytes_sent", written,
		"client", clientKey(r))
}

// lookupVersion resolves "latest" to the channel's current release and any other
// value to that exact tag.
func (s *Server) lookupVersion(ctx context.Context, channel model.Channel, version string) (metastore.Release, bool, error) {
	if version == "latest" {
		return s.cfg.Meta.CurrentRelease(ctx, channel)
	}
	return s.cfg.Meta.ReleaseByTag(ctx, channel, version)
}

// handleCacheMiss fills the cache on demand for the release a client actually
// wants, and tells the client when to come back. Serving a partial file would
// break the client's verification step, so a miss is always an honest 503.
func (s *Server) handleCacheMiss(w http.ResponseWriter, r *http.Request, channel model.Channel, record metastore.Release) {
	current, hasCurrent, err := s.cfg.Meta.CurrentRelease(r.Context(), channel)
	if err != nil {
		s.log.ErrorContext(r.Context(), "cache miss lookup failed", "error", err)
		s.writeError(w, http.StatusServiceUnavailable, "metadata_unavailable", "update metadata is temporarily unavailable")
		return
	}

	isCurrent := hasCurrent && current.Tag == record.Tag
	if isCurrent && s.cfg.Sync != nil {
		// The transfer is single-flight inside the synchroniser, so a thousand
		// simultaneous misses still produce one fetch.
		s.log.InfoContext(r.Context(), "cache miss, filling",
			"channel", channel.String(), "tag", record.Tag)
		s.cfg.Sync.FillLatest(r.Context(), channel, record)
		s.writeErrorWithRetry(w, http.StatusServiceUnavailable, "artifact_not_cached",
			"the update is being cached, retry shortly", fillRetryAfter)
		return
	}

	s.log.InfoContext(r.Context(), "cache miss for non-current release",
		"channel", channel.String(), "tag", record.Tag)
	s.writeError(w, http.StatusNotFound, "artifact_not_cached",
		"that version is not cached on this server")
}

func (s *Server) markUncached(ctx context.Context, channel model.Channel, record metastore.Release) {
	if err := s.cfg.Meta.SetCacheState(ctx, channel, record.Tag, metastore.CachePending, ""); err != nil {
		s.log.ErrorContext(ctx, "could not mark release uncached", "error", err)
	}
}

// contentDisposition quotes the filename defensively: release asset names come
// from the repository, so a stray quote or control character must not escape the
// header.
func contentDisposition(name string) string {
	cleaned := make([]rune, 0, len(name))
	for _, r := range name {
		switch {
		case r == '"' || r == '\\' || r == ';':
			cleaned = append(cleaned, '_')
		case r < 0x20 || r == 0x7f:
			cleaned = append(cleaned, '_')
		default:
			cleaned = append(cleaned, r)
		}
	}
	fallback := "agentisco.apk"
	value := strings.TrimSpace(string(cleaned))
	if value == "" {
		value = fallback
	}
	if !strings.HasSuffix(strings.ToLower(value), ".apk") {
		value = value + ".apk"
	}
	return `attachment; filename="` + value + `"`
}

// countingObject remembers how many bytes were read from the cached object so
// the completion of a download can be logged. Seek is passed through untouched,
// which is what lets http.ServeContent honour Range requests.
type countingObject struct {
	object objectstore.Object
	read   int64
}

func (c *countingObject) Read(p []byte) (int, error) {
	n, err := c.object.Read(p)
	c.read += int64(n)
	return n, err
}

func (c *countingObject) Seek(offset int64, whence int) (int64, error) {
	return c.object.Seek(offset, whence)
}

func (c *countingObject) Close() error { return c.object.Close() }
