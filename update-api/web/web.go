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
	"strconv"
	"strings"
)

//go:embed index.html
var indexHTML []byte

// logoPNG is the app's launcher icon, used as the page's logo and favicon. The
// same bytes serve both so the mark on the page is literally the app's icon.
//
//go:embed logo.png
var logoPNG []byte

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
		switch r.URL.Path {
		case "/logo.png", "/favicon.ico":
			// One icon for both names: browsers ask for /favicon.ico whether or
			// not the page declares it, and a 404 there is a console error users see.
			w.Header().Set("Content-Type", "image/png")
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
			w.Header().Set("X-Content-Type-Options", "nosniff")
			if r.Method == http.MethodHead {
				w.Header().Set("Content-Length", strconv.Itoa(len(logoPNG)))
				w.WriteHeader(http.StatusOK)
				return
			}
			if _, err := w.Write(logoPNG); err != nil {
				log.Warn("could not write the app icon", "error", err)
			}
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
