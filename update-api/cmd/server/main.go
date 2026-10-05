// Command server runs the Awaki Update API.
//
// It is the only public interface between Awaki clients and the private
// GitHub repository: clients see channels and downloads, never a repository.
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
	"path/filepath"
	"syscall"
	"time"

	"awaki/updateapi/internal/api"
	"awaki/updateapi/internal/config"
	"awaki/updateapi/internal/ghapi"
	"awaki/updateapi/internal/metastore"
	"awaki/updateapi/internal/notes"
	"awaki/updateapi/internal/objectstore"
	"awaki/updateapi/internal/ratelimit"
	"awaki/updateapi/internal/syncsvc"
	"awaki/updateapi/web"
)

func main() {
	showVersion := flag.Bool("version", false, "print the version and exit")
	flag.Parse()

	if *showVersion {
		fmt.Println("awaki-update-api")
		return
	}

	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "fatal:", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("configuration: %w", err)
	}
	logger := cfg.NewLogger()
	slog.SetDefault(logger)

	// Fail loudly before binding a port: a half-configured service that cannot
	// reach GitHub would otherwise look alive while serving nothing.
	logger.Info("starting awaki update api",
		"port", cfg.Port,
		"storage_path", cfg.StoragePath,
		"sync_interval_seconds", int(cfg.SyncInterval.Seconds()),
		"download_timeout_seconds", int(cfg.DownloadTimeout.Seconds()),
		"github_credential_present", cfg.GitHubToken != "",
		"landing_enabled", cfg.EnableLanding)

	if err := os.MkdirAll(cfg.StoragePath, 0o755); err != nil {
		return fmt.Errorf("create storage path: %w", err)
	}

	objects, err := objectstore.New(filepath.Join(cfg.StoragePath, "objects"))
	if err != nil {
		return err
	}
	if removed, err := objects.CleanupTemp(context.Background(), time.Hour); err != nil {
		logger.Warn("temporary object sweep failed", "error", err)
	} else if removed > 0 {
		logger.Info("removed stale partial downloads", "count", removed)
	}

	meta, err := metastore.Open(filepath.Join(cfg.StoragePath, "metadata.db"))
	if err != nil {
		return err
	}
	defer func() {
		if err := meta.Close(); err != nil {
			logger.Warn("could not close metadata store", "error", err)
		}
	}()

	client := ghapi.New(ghapi.Options{
		APIBase:   cfg.GitHubAPIBase,
		Owner:     cfg.GitHubOwner,
		Repo:      cfg.GitHubRepo,
		Token:     cfg.GitHubToken,
		Timeout:   cfg.GitHubTimeout,
		MaxItems:  cfg.ReleasesPerSync,
		UserAgent: "awaki-update-api",
		Logger:    logger,
	})

	synchronizer := syncsvc.New(syncsvc.Options{
		Client:       client,
		Meta:         meta,
		Objects:      objects,
		Sanitizer:    notes.NewSanitizer(cfg.GitHubOwner, cfg.GitHubRepo),
		Logger:       logger,
		Interval:     cfg.SyncInterval,
		PassTimeout:  cfg.DownloadTimeout,
		StallTimeout: cfg.StallTimeout,
		StartupDelay: cfg.SyncStartupDelay,
	})

	handler := api.New(api.Config{
		Logger:        logger,
		Meta:          meta,
		Objects:       objects,
		Sync:          synchronizer,
		Limiter:       ratelimit.NewLimiter(cfg.RateLimitRPS, cfg.RateLimitBurst),
		PublicBase:    cfg.PublicBaseURL,
		SyncInterval:  cfg.SyncInterval,
		StartedAt:     time.Now(),
		EnableLanding: cfg.EnableLanding,
		Landing:       web.Handler(web.Config{Logger: logger}),
	}).Handler()

	server := &http.Server{
		Addr:              fmt.Sprintf(":%d", cfg.Port),
		Handler:           handler,
		ReadTimeout:       cfg.ReadTimeout,
		WriteTimeout:      cfg.WriteTimeout,
		IdleTimeout:       cfg.IdleTimeout,
		ReadHeaderTimeout: 10 * time.Second,
		ErrorLog:          slog.NewLogLogger(logger.Handler(), slog.LevelWarn),
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	syncDone := make(chan struct{})
	go func() {
		defer close(syncDone)
		synchronizer.Run(ctx)
	}()

	serveErr := make(chan error, 1)
	go func() {
		logger.Info("listening", "addr", server.Addr)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serveErr <- err
			return
		}
		serveErr <- nil
	}()

	select {
	case err := <-serveErr:
		stop()
		<-syncDone
		if err != nil {
			return fmt.Errorf("http server: %w", err)
		}
		return nil
	case <-ctx.Done():
		logger.Info("shutdown requested, draining connections")
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Warn("graceful shutdown failed", "error", err)
	}
	<-syncDone
	logger.Info("stopped")
	return nil
}
