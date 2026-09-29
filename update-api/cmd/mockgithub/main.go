// Command mockgithub runs the fake GitHub Releases API locally so the update
// service can be developed, smoke-tested and demonstrated without a repository
// or a credential.
//
// It deliberately serves two channels whose newest releases differ: the debug
// channel's latest build is newer than the release channel's, which is the case
// a plain "latest release" lookup gets wrong.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"agentisco/updateapi/internal/ghfake"
)

func main() {
	addr := flag.String("addr", "127.0.0.1:9100", "address to listen on")
	token := flag.String("token", "mock-github-token", "bearer token the fake accepts")
	seed := flag.Bool("seed", true, "publish the demo debug and release builds")
	flag.Parse()

	logger := slog.New(slog.NewTextHandler(os.Stdout, nil))
	server := ghfake.New(*token)

	if *seed {
		// The release channel's newest build is older than the debug channel's:
		// a single "latest release" answer cannot serve both.
		server.AddRelease("v1.4.0", "Agentisco v1.4.0", ciStyleNotes("v1.3.0", "v1.4.0"),
			time.Now().Add(-48*time.Hour),
			ghfake.AssetInput{Name: "Agentisco-v1.4.0-release.apk", Bytes: ghfake.SyntheticAPK("v1.4.0-release", 3<<20)},
			ghfake.AssetInput{Name: "Agentisco-v1.4.0-debug.apk", Bytes: ghfake.SyntheticAPK("v1.4.0-debug", 3<<20)},
			ghfake.AssetInput{Name: "Agentisco-v1.4.0-release.aab", Bytes: ghfake.SyntheticAPK("v1.4.0-aab", 1<<10)},
		)
		server.AddRelease("v1.5.0", "Agentisco v1.5.0", ciStyleNotes("v1.4.0", "v1.5.0"),
			time.Now().Add(-1*time.Hour),
			ghfake.AssetInput{Name: "Agentisco-v1.5.0-debug.apk", Bytes: ghfake.SyntheticAPK("v1.5.0-debug", 2<<20)},
		)
	}

	httpServer := &http.Server{
		Addr:              *addr,
		Handler:           loggingHandler(server, logger),
		ReadHeaderTimeout: 10 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = httpServer.Shutdown(shutdownCtx)
	}()

	lists, _ := server.Stats()
	logger.Info("mock github listening", "addr", *addr, "seeded", *seed, "release_calls", lists,
		"note", "the service must be pointed at this address with GITHUB_API_BASE_URL")

	if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		fmt.Fprintln(os.Stderr, "mock github failed:", err)
		os.Exit(1)
	}
	logger.Info("mock github stopped")
}

// ciStyleNotes mirrors the body .github/workflows/ci.yml writes, including the
// Full Changelog line that points at the private repository: the service under
// test has to strip that reference before publishing notes.
func ciStyleNotes(previous, current string) string {
	return fmt.Sprintf(`## What's Changed

* fix: resume downloads without a stale offset by @dev in #%d
* feat: per-channel update API client by @dev in #%d

**Full Changelog**: https://github.com/gamistudios/Agentisco/compare/%s...%s`,
		120+hashOf(current), 121+hashOf(current), previous, current)
}

func hashOf(value string) int {
	total := 0
	for _, r := range value {
		total += int(r)
	}
	if total%80 == 0 {
		total++
	}
	return total % 900
}

func loggingHandler(next http.Handler, logger *slog.Logger) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		recorder := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(recorder, r)
		logger.Info("mock github request",
			"method", r.Method,
			"path", r.URL.Path,
			"status", recorder.status,
			"duration_ms", time.Since(started).Milliseconds())
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(status int) {
	r.status = status
	r.ResponseWriter.WriteHeader(status)
}
