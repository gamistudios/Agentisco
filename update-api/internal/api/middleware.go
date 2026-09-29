package api

import (
	"net/http"
	"runtime/debug"
	"strconv"
	"time"
)

// responseRecorder captures what a handler decided, for the access log.
type responseRecorder struct {
	http.ResponseWriter
	status int
	bytes  int64
}

func (r *responseRecorder) WriteHeader(code int) {
	if r.status == 0 {
		r.status = code
	}
	r.ResponseWriter.WriteHeader(code)
}

func (r *responseRecorder) Write(p []byte) (int, error) {
	n, err := r.ResponseWriter.Write(p)
	r.bytes += int64(n)
	return n, err
}

// Flush passes through to the underlying writer when it supports it, which
// streaming handlers rely on.
func (r *responseRecorder) Flush() {
	if flusher, ok := r.ResponseWriter.(http.Flusher); ok {
		flusher.Flush()
	}
}

// middleware wraps every route with recovery, security headers, rate limiting
// and a structured access log.
func (s *Server) middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		recorder := &responseRecorder{ResponseWriter: w}

		defer func() {
			if recovered := recover(); recovered != nil {
				// The detail stays in the log; the client gets a fixed message so
				// nothing about the runtime or the filesystem leaks.
				s.log.Error("panic while handling request",
					"method", r.Method,
					"path", r.URL.Path,
					"panic", recovered,
					"stack", string(debug.Stack()))
				if recorder.status == 0 {
					s.writeError(recorder, http.StatusInternalServerError, "internal_error",
						"the request could not be completed")
				}
			}
			if recorder.status == 0 {
				recorder.status = http.StatusOK
			}
			s.log.Info("request",
				"method", r.Method,
				"path", r.URL.Path,
				"status", recorder.status,
				"bytes", recorder.bytes,
				"duration_ms", time.Since(started).Milliseconds(),
				"client", clientKey(r),
				"user_agent", truncate(r.UserAgent(), 200))
		}()

		s.setBaselineHeaders(recorder)

		if s.cfg.Limiter != nil {
			allowed, wait := s.cfg.Limiter.Allow(clientKey(r))
			if !allowed {
				s.log.Info("rate limited client",
					"client", clientKey(r),
					"path", r.URL.Path,
					"retry_after_seconds", int(wait.Seconds()))
				s.writeErrorWithRetry(recorder, http.StatusTooManyRequests, "rate_limited",
					"too many requests, slow down", wait)
				return
			}
		}

		next.ServeHTTP(recorder, r)
	})
}

// setBaselineHeaders states the safest possible posture for a JSON/API host.
func (s *Server) setBaselineHeaders(w http.ResponseWriter) {
	header := w.Header()
	header.Set("X-Content-Type-Options", "nosniff")
	header.Set("X-Frame-Options", "DENY")
	header.Set("Referrer-Policy", "no-referrer")
	header.Set("Cache-Control", "no-store")
	header.Set("X-Request-Id", strconv.FormatInt(time.Now().UnixNano(), 36))
}

func truncate(value string, max int) string {
	if len(value) <= max {
		return value
	}
	return value[:max]
}
