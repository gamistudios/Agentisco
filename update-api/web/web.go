// Package web serves the public Agentisco landing page.
//
// It is a separate concern from the update API and only happens to be deployed
// with it: the page reads the API for everything it shows, so it carries no
// version numbers, no artifact names and no repository references of its own.
package web

import (
	_ "embed"
	"log/slog"
	"net/http"
	"strings"
)

//go:embed index.html
var indexHTML []byte

// Config wires the landing page.
type Config struct {
	Logger *slog.Logger
}

// Handler returns the landing page handler, or nil when it is disabled.
func Handler(cfg Config) http.Handler {
	log := cfg.Logger
	if log == nil {
		log = slog.Default()
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.Header().Set("Allow", "GET, HEAD")
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		if strings.Trim(r.URL.Path, "/") != "" && r.URL.Path != "/index.html" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		if r.Method == http.MethodHead {
			w.WriteHeader(http.StatusOK)
			return
		}
		if _, err := w.Write(indexHTML); err != nil {
			log.Warn("could not write landing page", "error", err)
		}
	})
}
