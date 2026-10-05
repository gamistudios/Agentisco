package config

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

// writtenEnvFile puts a .env in place and guarantees the process environment is
// clean afterwards, because loadDotEnv writes into os.Environ.
func writtenEnvFile(t *testing.T, content string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), ".env")
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("write env file: %v", err)
	}
	t.Setenv("ENV_FILE", path)
	for _, key := range []string{
		"GITHUB_TOKEN", "GITHUB_OWNER", "GITHUB_REPOSITORY", "GITHUB_REPO",
		"SYNC_INTERVAL_SECONDS", "STORAGE_PATH", "PUBLIC_BASE_URL", "PORT",
		"ENABLE_LANDING", "RATE_LIMIT_RPS", "RATE_LIMIT_BURST", "RELEASES_PER_SYNC",
		"GITHUB_API_BASE_URL", "GITHUB_TIMEOUT_SECONDS", "LOG_LEVEL", "LOG_FORMAT",
		"SYNC_STARTUP_DELAY_SECONDS", "READ_TIMEOUT_SECONDS", "WRITE_TIMEOUT_SECONDS",
		"IDLE_TIMEOUT_SECONDS", "HANDLER_TIMEOUT_SECONDS",
	} {
		t.Setenv(key, "")
		os.Unsetenv(key)
	}
}

func TestLoadAppliesEnvFileAndRepoAlias(t *testing.T) {
	writtenEnvFile(t, `
# local development secrets
GITHUB_TOKEN=file-token
GITHUB_OWNER=gamistudios
GITHUB_REPO=Awaki
SYNC_INTERVAL_SECONDS=90
`)
	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.GitHubToken != "file-token" || cfg.GitHubOwner != "gamistudios" {
		t.Fatalf("env file not applied: %+v", cfg)
	}
	if cfg.GitHubRepo != "Awaki" {
		t.Fatalf("GITHUB_REPO alias ignored: %q", cfg.GitHubRepo)
	}
	if cfg.SyncInterval != 90*time.Second {
		t.Fatalf("sync interval %v", cfg.SyncInterval)
	}
	if cfg.StoragePath != "./data" || cfg.Port != 8080 {
		t.Fatalf("defaults not applied: %s :%d", cfg.StoragePath, cfg.Port)
	}
}

func TestRealEnvironmentWinsOverEnvFile(t *testing.T) {
	writtenEnvFile(t, "GITHUB_TOKEN=file-token\nGITHUB_OWNER=file-owner\nGITHUB_REPOSITORY=file-repo\n")
	t.Setenv("GITHUB_TOKEN", "real-token")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.GitHubToken != "real-token" {
		t.Fatalf("the environment file overrode a real variable: %q", cfg.GitHubToken)
	}
	if cfg.GitHubOwner != "file-owner" {
		t.Fatalf("env file value missing: %q", cfg.GitHubOwner)
	}
}

func TestQuotedAndInlineValues(t *testing.T) {
	writtenEnvFile(t, `GITHUB_TOKEN="quoted token"
GITHUB_OWNER='single'
GITHUB_REPOSITORY=Repo
PUBLIC_BASE_URL=https://updates.example.com/
`)
	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.GitHubToken != "quoted token" || cfg.GitHubOwner != "single" {
		t.Fatalf("quoting not handled: %q / %q", cfg.GitHubToken, cfg.GitHubOwner)
	}
	if cfg.PublicBaseURL != "https://updates.example.com" {
		t.Fatalf("trailing slash kept: %q", cfg.PublicBaseURL)
	}
}

func TestLoadRequiresEveryCredentialPiece(t *testing.T) {
	writtenEnvFile(t, "GITHUB_OWNER=gamistudios\nGITHUB_REPOSITORY=Awaki\n")
	if _, err := Load(); err == nil {
		t.Fatal("a missing token must refuse to start")
	}

	writtenEnvFile(t, "GITHUB_TOKEN=t\nGITHUB_REPOSITORY=Awaki\n")
	if _, err := Load(); err == nil {
		t.Fatal("a missing owner must refuse to start")
	}

	writtenEnvFile(t, "GITHUB_TOKEN=t\nGITHUB_OWNER=o\n")
	if _, err := Load(); err == nil {
		t.Fatal("a missing repository must refuse to start")
	}
}

func TestLoadRejectsUnsafeConfiguration(t *testing.T) {
	cases := []struct {
		name  string
		file  string
		extra map[string]string
	}{
		{"sync interval too small", "SYNC_INTERVAL_SECONDS=1", nil},
		{"bad public base url", "PUBLIC_BASE_URL=updates.example.com", nil},
		{"bad github api base url", "GITHUB_API_BASE_URL=not a url", nil},
		{"releases out of range", "RELEASES_PER_SYNC=500", nil},
		{"rate limit disabled", "RATE_LIMIT_RPS=0", nil},
		{"burst below one", "RATE_LIMIT_BURST=0", nil},
	}
	for _, testCase := range cases {
		writtenEnvFile(t, testCase.file)
		for key, value := range testCase.extra {
			t.Setenv(key, value)
		}
		if _, err := Load(); err == nil {
			t.Fatalf("%s: configuration accepted", testCase.name)
		}
	}
}

func TestParseEnvLine(t *testing.T) {
	accepted := map[string][2]string{
		"KEY=value":        {"KEY", "value"},
		"export KEY=value": {"KEY", "value"},
		"  KEY = value ":   {"KEY", "value"},
		`KEY="quoted"`:     {"KEY", "quoted"},
		`KEY='single'`:     {"KEY", "single"},
		"KEY=":             {"KEY", ""},
	}
	for line, want := range accepted {
		key, value, ok := parseEnvLine(line)
		if !ok {
			t.Fatalf("%q rejected", line)
		}
		if key != want[0] || value != want[1] {
			t.Fatalf("%q parsed as %q/%q, want %q/%q", line, key, value, want[0], want[1])
		}
	}

	rejected := []string{"", "# comment", "   ", "just-a-word", "=value", "1BAD=x", "SPACED KEY=x"}
	for _, line := range rejected {
		if _, _, ok := parseEnvLine(line); ok {
			t.Fatalf("%q should be rejected", line)
		}
	}
}

func TestMissingEnvFileIsNotAnError(t *testing.T) {
	t.Setenv("ENV_FILE", filepath.Join(t.TempDir(), "absent.env"))
	t.Setenv("GITHUB_TOKEN", "t")
	t.Setenv("GITHUB_OWNER", "o")
	t.Setenv("GITHUB_REPOSITORY", "r")
	if _, err := Load(); err != nil {
		t.Fatalf("Load without an env file: %v", err)
	}
}
