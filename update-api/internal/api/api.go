// Package api is the public HTTP surface of the update service.
//
// It deliberately knows nothing about where the artifacts come from: no route
// accepts an owner or repository, no response carries a GitHub address, and no
// error text reaches a client with an internal path or an upstream payload.
package api

import (
	"context"
	"encoding/json"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"awaki/updateapi/internal/metastore"
	"awaki/updateapi/internal/model"
	"awaki/updateapi/internal/objectstore"
	"awaki/updateapi/internal/ratelimit"
	"awaki/updateapi/internal/syncsvc"
)

// publicChannel is the channel announced on public surfaces. The other channel
// is served exactly the same way for the team's own builds, but is never named
// where a client can read it.
var publicChannel = model.ChannelRelease

// Config wires the handlers.
type Config struct {
	Logger        *slog.Logger
	Meta          *metastore.DB
	Objects       objectstore.Store
	Sync          *syncsvc.Syncer
	Limiter       *ratelimit.Limiter
	PublicBase    string
	SyncInterval  time.Duration
	StartedAt     time.Time
	EnableLanding bool
	Landing       http.Handler
}

// Server serves the versioned API.
type Server struct {
	cfg Config
	log *slog.Logger
}

// New builds a server.
func New(cfg Config) *Server {
	log := cfg.Logger
	if log == nil {
		log = slog.Default()
	}
	if cfg.StartedAt.IsZero() {
		cfg.StartedAt = time.Now()
	}
	return &Server{cfg: cfg, log: log}
}

// Handler returns the routed, middleware-wrapped HTTP handler.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /v1/updates/{channel}/latest", s.handleLatest)
	mux.HandleFunc("GET /v1/download/{channel}/{version}", s.handleDownload)
	mux.HandleFunc("GET /health", s.handleHealth)
	mux.HandleFunc("GET /ready", s.handleReady)

	if s.cfg.EnableLanding && s.cfg.Landing != nil {
		mux.Handle("GET /", s.cfg.Landing)
	} else {
		mux.HandleFunc("GET /", s.handleRoot)
	}
	// Anything else is a plain 404 without hinting at what does exist.
	mux.HandleFunc("/", s.handleNotFound)

	return s.middleware(mux)
}

// handleRoot advertises only the channel clients are meant to use; the internal
// channel stays served but unannounced.
func (s *Server) handleRoot(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		s.writeError(w, http.StatusNotFound, "not_found", "resource not found")
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{
		"service": "awaki-update-api",
		"endpoints": []string{
			"/v1/updates/release/latest",
			"/v1/download/release/latest",
			"/health",
			"/ready",
		},
	})
}

func (s *Server) handleNotFound(w http.ResponseWriter, _ *http.Request) {
	s.writeError(w, http.StatusNotFound, "not_found", "resource not found")
}

// handleLatest serves the channel's current metadata, and - when the client
// sends versionCode - the direct "is there an update?" answer.
func (s *Server) handleLatest(w http.ResponseWriter, r *http.Request) {
	channel, ok := model.ParseChannel(r.PathValue("channel"))
	if !ok {
		s.writeError(w, http.StatusBadRequest, "invalid_channel", "channel is not a supported update channel")
		return
	}

	var requested *int64
	if raw := strings.TrimSpace(r.URL.Query().Get("versionCode")); raw != "" {
		value, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || value < 0 {
			s.writeError(w, http.StatusBadRequest, "invalid_version_code", "versionCode must be a non-negative integer")
			return
		}
		requested = &value
	}

	record, found, err := s.cfg.Meta.CurrentRelease(r.Context(), channel)
	if err != nil {
		s.log.ErrorContext(r.Context(), "metadata lookup failed", "channel", channel.String(), "error", err)
		s.writeError(w, http.StatusServiceUnavailable, "metadata_unavailable", "update metadata is temporarily unavailable")
		return
	}
	if !found {
		s.log.InfoContext(r.Context(), "client requested update with nothing published yet",
			"channel", channel.String())
		s.writeError(w, http.StatusNotFound, "no_release", "no release has been published for this channel yet")
		return
	}

	info := s.updateInfo(r, channel, record)
	if requested == nil {
		s.log.InfoContext(r.Context(), "client requested update metadata",
			"channel", channel.String(), "tag", record.Tag, "cached", info.Cached)
		s.writeJSON(w, http.StatusOK, info)
		return
	}

	check := model.CheckResult{
		UpdateAvailable:    record.VersionCode > *requested,
		CurrentVersionCode: *requested,
		LatestVersionCode:  record.VersionCode,
		LatestVersionName:  record.VersionName,
		TagName:            record.Tag,
		DownloadURL:        info.DownloadURL,
		UpdateInfo:         info,
	}
	s.log.InfoContext(r.Context(), "client requested update check",
		"channel", channel.String(),
		"client_version_code", *requested,
		"latest_version_code", record.VersionCode,
		"update_available", check.UpdateAvailable)
	s.writeJSON(w, http.StatusOK, check)
}

// updateInfo renders stored metadata as the public document.
func (s *Server) updateInfo(r *http.Request, channel model.Channel, record metastore.Release) model.UpdateInfo {
	return model.UpdateInfo{
		Channel:      channel,
		VersionCode:  record.VersionCode,
		VersionName:  record.VersionName,
		TagName:      record.Tag,
		ApkName:      record.ApkName,
		Size:         record.Size,
		SHA256:       record.SHA256,
		ReleaseNotes: record.ReleaseNotes,
		PublishedAt:  utcOrZero(record.PublishedAt),
		SyncedAt:     utcOrZero(record.SyncedAt),
		DownloadURL:  s.downloadURL(r, channel, record.Tag),
		Cached:       record.CacheState == metastore.CacheCached,
	}
}

// downloadURL builds the absolute URL for one pinned release. The version is
// pinned rather than "latest" so a resumed download can never be repointed at a
// different artifact while it is in flight.
func (s *Server) downloadURL(r *http.Request, channel model.Channel, tag string) string {
	return s.baseURL(r) + "/v1/download/" + channel.String() + "/" + escapePathSegment(tag)
}

func (s *Server) baseURL(r *http.Request) string {
	if s.cfg.PublicBase != "" {
		return s.cfg.PublicBase
	}
	scheme := "http"
	if forwarded := r.Header.Get("X-Forwarded-Proto"); forwarded != "" {
		scheme = strings.TrimSpace(strings.Split(forwarded, ",")[0])
	} else if r.TLS != nil {
		scheme = "https"
	}
	host := r.Host
	if forwarded := r.Header.Get("X-Forwarded-Host"); forwarded != "" {
		host = strings.TrimSpace(strings.Split(forwarded, ",")[0])
	}
	return scheme + "://" + host
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	s.writeJSON(w, http.StatusOK, map[string]any{
		"status":          "ok",
		"time":            time.Now().UTC(),
		"uptime_seconds":  int64(time.Since(s.cfg.StartedAt).Seconds()),
		"sync_interval_s": int64(s.cfg.SyncInterval.Seconds()),
	})
}

// handleReady answers whether this instance can serve updates right now.
//
// The metadata store must be reachable and at least one channel must have
// completed a synchronisation. A GitHub outage after a successful sync keeps
// readiness true, which is the point: the last known good release stays
// servable. Only the public channel is described in the payload; the internal
// channel still counts towards readiness but is never named here.
func (s *Server) handleReady(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	if err := s.cfg.Meta.Ping(ctx); err != nil {
		s.writeJSON(w, http.StatusServiceUnavailable, map[string]any{
			"status": "not_ready",
			"reason": "metadata store unavailable",
		})
		return
	}

	channels, err := s.cfg.Meta.Channels(ctx)
	if err != nil {
		s.log.ErrorContext(r.Context(), "ready check failed", "error", err)
		s.writeJSON(w, http.StatusServiceUnavailable, map[string]any{
			"status": "not_ready",
			"reason": "metadata store unavailable",
		})
		return
	}

	ready := false
	view := make([]map[string]any, 0, 1)
	for _, snapshot := range channels {
		if snapshot.CurrentTag != "" {
			ready = true
		}
		if snapshot.Channel != publicChannel {
			continue
		}
		view = append(view, map[string]any{
			"channel":         snapshot.Channel.String(),
			"current_tag":     snapshot.CurrentTag,
			"status":          string(snapshot.Status),
			"last_attempt_at": utcOrZero(snapshot.LastAttemptAt),
			"last_success_at": utcOrZero(snapshot.LastSuccessAt),
		})
	}

	code := http.StatusServiceUnavailable
	status := "not_ready"
	if ready {
		code = http.StatusOK
		status = "ready"
	} else {
		s.log.WarnContext(r.Context(), "not ready: no channel has completed a sync yet")
	}
	s.writeJSON(w, code, map[string]any{
		"status":   status,
		"time":     time.Now().UTC(),
		"syncing":  s.cfg.Sync != nil,
		"channels": view,
	})
}

// writeJSON emits a response body. Encoding errors are logged but cannot be
// reported to the client after headers have gone out.
func (s *Server) writeJSON(w http.ResponseWriter, code int, body any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(code)
	if err := json.NewEncoder(w).Encode(body); err != nil {
		s.log.Error("could not encode response", "error", err)
	}
}

// errorBody is the single public error shape.
type errorBody struct {
	Error errorDetail `json:"error"`
}

type errorDetail struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// writeError sends a client-safe message: codes and short fixed text only, never
// an upstream payload or a filesystem path.
func (s *Server) writeError(w http.ResponseWriter, code int, errCode, message string) {
	s.writeJSON(w, code, errorBody{Error: errorDetail{Code: errCode, Message: message}})
}

func (s *Server) writeErrorWithRetry(w http.ResponseWriter, code int, errCode, message string, retry time.Duration) {
	w.Header().Set("Retry-After", strconv.Itoa(int(mathCeilSeconds(retry))))
	s.writeError(w, code, errCode, message)
}

func mathCeilSeconds(d time.Duration) int {
	seconds := int(d.Seconds())
	if d > time.Duration(seconds)*time.Second {
		seconds++
	}
	if seconds < 1 {
		return 1
	}
	return seconds
}

func escapePathSegment(value string) string {
	return strings.ReplaceAll(url.PathEscape(value), " ", "%20")
}

func utcOrZero(value time.Time) time.Time {
	if value.IsZero() {
		return time.Time{}
	}
	return value.UTC()
}

// clientKey identifies a caller for rate limiting. Proxy headers are honoured
// because every supported deployment terminates TLS in front of the service;
// the key only budgets requests, it never authorises anything.
func clientKey(r *http.Request) string {
	if forwarded := r.Header.Get("X-Forwarded-For"); forwarded != "" {
		if first := strings.TrimSpace(strings.Split(forwarded, ",")[0]); first != "" {
			return first
		}
	}
	if host, _, err := net.SplitHostPort(r.RemoteAddr); err == nil {
		return host
	}
	return r.RemoteAddr
}
