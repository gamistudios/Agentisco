// Package config loads the service configuration from the environment.
//
// Every value that identifies the upstream repository lives here and nowhere
// else: the public API never accepts an owner or repository, so the private
// GitHub location cannot be probed through the service.
package config

import (
	"bufio"
	"errors"
	"fmt"
	"log/slog"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	// GitHub source of truth (server-side only; never surfaced to clients).
	GitHubToken     string
	GitHubOwner     string
	GitHubRepo      string
	GitHubAPIBase   string
	GitHubTimeout   time.Duration
	ReleasesPerSync int

	// Background synchronisation.
	SyncInterval     time.Duration
	SyncStartupDelay time.Duration
	// DownloadTimeout caps one whole synchronisation pass, including the APK
	// transfer. The artifacts are tens of megabytes and the app ships to regions
	// where links are poor, so the budget is an hour by default; operators can
	// raise it with DOWNLOAD_TIMEOUT_SECONDS.
	DownloadTimeout time.Duration
	// StallTimeout abandons a transfer only when bytes stop arriving entirely,
	// which is what distinguishes a slow link (keep waiting) from a dead one
	// (stop and retry on the next pass).
	StallTimeout time.Duration

	// Storage.
	StoragePath string // persistent: metadata database + cached APKs

	// HTTP surface.
	Port           int
	PublicBaseURL  string
	ReadTimeout    time.Duration
	WriteTimeout   time.Duration
	IdleTimeout    time.Duration
	RateLimitRPS   float64
	RateLimitBurst int
	EnableLanding  bool

	LogLevel  string
	LogFormat string
}

const (
	defaultAPIBase    = "https://api.github.com"
	defaultStorageDir = "./data"
)

// Load reads the environment and validates it. Missing or malformed required
// values are returned as errors so the process can refuse to start with a clear
// message instead of failing later against GitHub.
func Load() (*Config, error) {
	loadDotEnv()

	cfg := &Config{
		GitHubToken:     strings.TrimSpace(os.Getenv("GITHUB_TOKEN")),
		GitHubOwner:     strings.TrimSpace(os.Getenv("GITHUB_OWNER")),
		GitHubRepo:      envFirst("GITHUB_REPOSITORY", "GITHUB_REPO"),
		GitHubAPIBase:   envString("GITHUB_API_BASE_URL", defaultAPIBase),
		GitHubTimeout:   envDuration("GITHUB_TIMEOUT_SECONDS", 20*time.Second),
		ReleasesPerSync: envInt("RELEASES_PER_SYNC", 30),

		SyncInterval:     envDuration("SYNC_INTERVAL_SECONDS", 60*time.Second),
		SyncStartupDelay: envDuration("SYNC_STARTUP_DELAY_SECONDS", 0),
		DownloadTimeout:  envDuration("DOWNLOAD_TIMEOUT_SECONDS", 60*time.Minute),
		StallTimeout:     envDuration("STALL_TIMEOUT_SECONDS", 30*time.Second),

		StoragePath: envString("STORAGE_PATH", defaultStorageDir),

		Port:           envPort(),
		PublicBaseURL:  strings.TrimRight(strings.TrimSpace(os.Getenv("PUBLIC_BASE_URL")), "/"),
		ReadTimeout:    envDuration("READ_TIMEOUT_SECONDS", 30*time.Second),
		WriteTimeout:   envDuration("WRITE_TIMEOUT_SECONDS", 0), // 0: APK streaming may be long
		IdleTimeout:    envDuration("IDLE_TIMEOUT_SECONDS", 120*time.Second),
		RateLimitRPS:   envFloat("RATE_LIMIT_RPS", 10),
		RateLimitBurst: envInt("RATE_LIMIT_BURST", 40),
		EnableLanding:  envBool("ENABLE_LANDING", true),

		LogLevel:  strings.ToLower(envString("LOG_LEVEL", "info")),
		LogFormat: strings.ToLower(envString("LOG_FORMAT", "json")),
	}

	if cfg.GitHubToken == "" {
		return nil, errors.New("GITHUB_TOKEN is required")
	}
	if cfg.GitHubOwner == "" {
		return nil, errors.New("GITHUB_OWNER is required")
	}
	if cfg.GitHubRepo == "" {
		return nil, errors.New("GITHUB_REPOSITORY (or GITHUB_REPO) is required")
	}
	base, err := url.Parse(cfg.GitHubAPIBase)
	if err != nil || base.Scheme == "" || base.Host == "" {
		return nil, fmt.Errorf("GITHUB_API_BASE_URL is not a valid URL")
	}
	if base.Scheme != "https" && base.Scheme != "http" {
		return nil, fmt.Errorf("GITHUB_API_BASE_URL must be http or https")
	}
	if cfg.PublicBaseURL != "" {
		u, err := url.Parse(cfg.PublicBaseURL)
		if err != nil || u.Scheme == "" || u.Host == "" {
			return nil, fmt.Errorf("PUBLIC_BASE_URL must be an absolute URL, e.g. https://updates.example.com")
		}
	}
	if cfg.SyncInterval < 5*time.Second {
		return nil, fmt.Errorf("SYNC_INTERVAL_SECONDS must be at least 5")
	}
	if cfg.DownloadTimeout <= cfg.GitHubTimeout {
		return nil, fmt.Errorf("DOWNLOAD_TIMEOUT_SECONDS must exceed GITHUB_TIMEOUT_SECONDS, otherwise an APK can never finish transferring")
	}
	if cfg.StallTimeout >= cfg.DownloadTimeout {
		return nil, fmt.Errorf("STALL_TIMEOUT_SECONDS must be smaller than DOWNLOAD_TIMEOUT_SECONDS, otherwise it can never fire")
	}
	if cfg.ReleasesPerSync < 1 || cfg.ReleasesPerSync > 100 {
		return nil, fmt.Errorf("RELEASES_PER_SYNC must be between 1 and 100")
	}
	if cfg.Port < 1 || cfg.Port > 65535 {
		return nil, fmt.Errorf("PORT must be between 1 and 65535")
	}
	if cfg.RateLimitRPS <= 0 {
		return nil, fmt.Errorf("RATE_LIMIT_RPS must be greater than 0")
	}
	if cfg.RateLimitBurst < 1 {
		return nil, fmt.Errorf("RATE_LIMIT_BURST must be at least 1")
	}
	return cfg, nil
}

// NewLogger builds the structured logger. Secrets are never logged: the GitHub
// token is only ever referenced by presence, never value.
func (c *Config) NewLogger() *slog.Logger {
	level := slog.LevelInfo
	switch c.LogLevel {
	case "debug":
		level = slog.LevelDebug
	case "warn":
		level = slog.LevelWarn
	case "error":
		level = slog.LevelError
	}
	opts := &slog.HandlerOptions{Level: level}
	if c.LogFormat == "text" {
		return slog.New(slog.NewTextHandler(os.Stdout, opts))
	}
	return slog.New(slog.NewJSONHandler(os.Stdout, opts))
}

// BaseURL renders the API origin used to build absolute asset URLs, falling
// back to the request's own host when unset (PaaS hosts vary per deployment).
func (c *Config) BaseURL(scheme, host string) string {
	if c.PublicBaseURL != "" {
		return c.PublicBaseURL
	}
	if host == "" {
		host = fmt.Sprintf("localhost:%d", c.Port)
	}
	if scheme == "" {
		scheme = "http"
	}
	return scheme + "://" + host
}

func envString(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}

// envFirst returns the first of several variable names that carries a value, so
// a shorter alias (GITHUB_REPO) works as well as the documented name.
func envFirst(keys ...string) string {
	for _, key := range keys {
		if v := strings.TrimSpace(os.Getenv(key)); v != "" {
			return v
		}
	}
	return ""
}

// loadDotEnv reads KEY=VALUE pairs from a local .env file into the process
// environment without ever overriding a real environment variable.
//
// This is a local convenience for development. Deployments inject secrets
// through the platform, and ENV_FILE points at a specific file when the working
// directory already holds one. Values are never logged.
func loadDotEnv() {
	path := strings.TrimSpace(os.Getenv("ENV_FILE"))
	if path == "" {
		path = ".env"
	}
	file, err := os.Open(path)
	if err != nil {
		return
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 0, 4096), 64*1024)
	for scanner.Scan() {
		key, value, ok := parseEnvLine(scanner.Text())
		if !ok {
			continue
		}
		if _, present := os.LookupEnv(key); present {
			continue
		}
		if err := os.Setenv(key, value); err != nil {
			slog.Warn("could not apply value from the env file", "name", key)
		}
	}
}

func parseEnvLine(line string) (string, string, bool) {
	text := strings.TrimSpace(line)
	if text == "" || strings.HasPrefix(text, "#") {
		return "", "", false
	}
	text = strings.TrimPrefix(text, "export ")
	key, value, found := strings.Cut(text, "=")
	if !found {
		return "", "", false
	}
	key = strings.TrimSpace(key)
	if key == "" || !isValidEnvName(key) {
		return "", "", false
	}
	value = strings.TrimSpace(value)
	if quoted := strings.Trim(value, `"'`); quoted != value && len(value) >= 2 {
		value = quoted
	}
	return key, value, true
}

func isValidEnvName(key string) bool {
	for i, r := range key {
		switch {
		case r >= 'A' && r <= 'Z', r >= 'a' && r <= 'z':
		case i > 0 && (r == '_' || (r >= '0' && r <= '9')):
		default:
			return false
		}
	}
	return true
}

func envInt(key string, fallback int) int {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback
	}
	v, err := strconv.Atoi(raw)
	if err != nil {
		slog.Warn("invalid integer env, using default", "name", key, "value", raw, "default", fallback)
		return fallback
	}
	return v
}

func envFloat(key string, fallback float64) float64 {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback
	}
	v, err := strconv.ParseFloat(raw, 64)
	if err != nil {
		slog.Warn("invalid float env, using default", "name", key, "value", raw, "default", fallback)
		return fallback
	}
	return v
}

// envDuration reads a *_SECONDS variable; non-positive or unparsable input
// falls back to the default.
func envDuration(key string, fallback time.Duration) time.Duration {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback
	}
	seconds, err := strconv.ParseFloat(raw, 64)
	if err != nil || seconds <= 0 {
		slog.Warn("invalid seconds env, using default", "name", key, "value", raw)
		return fallback
	}
	return time.Duration(seconds * float64(time.Second))
}

func envBool(key string, fallback bool) bool {
	raw := strings.ToLower(strings.TrimSpace(os.Getenv(key)))
	if raw == "" {
		return fallback
	}
	v, err := strconv.ParseBool(raw)
	if err != nil {
		return fallback
	}
	return v
}

// envPort prefers PORT (the convention on Koyeb, Render, Fly.io and Railway)
// and falls back to a fixed local development port.
func envPort() int {
	if v := strings.TrimSpace(os.Getenv("PORT")); v != "" {
		if p, err := strconv.Atoi(v); err == nil && p > 0 && p <= 65535 {
			return p
		}
	}
	return 8080
}
