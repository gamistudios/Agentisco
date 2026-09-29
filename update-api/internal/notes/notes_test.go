package notes

import (
	"strings"
	"testing"
)

const ciBody = `## What's Changed

* fix: keep a delegated agent's work out of the transcript by @dev in #121
* feat: build center runs the project by @dev in #122
  

**Full Changelog**: https://github.com/gamistudios/Agentisco/compare/v1.4.0...v1.5.0`

func TestProcessKeepsFormatAndDropsPrivateURL(t *testing.T) {
	sanitizer := NewSanitizer("gamistudios", "Agentisco")
	got := sanitizer.Process(ciBody)

	for _, want := range []string{
		"## What's Changed",
		"* fix: keep a delegated agent's work out of the transcript by @dev in #121",
		"* feat: build center runs the project by @dev in #122",
		"**Full Changelog**: v1.4.0...v1.5.0",
	} {
		if !strings.Contains(got, want) {
			t.Errorf("processed notes are missing %q\n---\n%s", want, got)
		}
	}
	assertNoPrivateReference(t, got)
}

func TestProcessHandlesEveryShapeOfRepositoryURL(t *testing.T) {
	sanitizer := NewSanitizer("gamistudios", "Agentisco")
	cases := map[string]string{
		"api host":         `see https://api.github.com/repos/gamistudios/Agentisco/releases/latest for details`,
		"release tag link": `**Full Changelog**: https://github.com/gamistudios/Agentisco/releases/tag/v1.5.0`,
		"markdown link":    `Read the [release](https://github.com/gamistudios/Agentisco/compare/v1.4.0...v1.5.0).`,
		"download link":    `Download from https://github.com/gamistudios/Agentisco/releases/download/v1.5.0/Agentisco-v1.5.0-debug.apk now`,
		"uppercase owner":  `MIRROR https://GITHUB.COM/GAMISTUDIOS/AGENTISCO/compare/v1...v2`,
	}
	for name, body := range cases {
		got := sanitizer.Process(body)
		assertNoPrivateReference(t, got)
		if strings.TrimSpace(got) == "" && name != "download link" {
			t.Errorf("%s: everything was dropped, got %q", name, got)
		}
	}
}

// TestProcessPreservesUnrelatedLinks: notes legitimately reference other
// projects; only the configured repository is private.
func TestProcessPreservesUnrelatedLinks(t *testing.T) {
	sanitizer := NewSanitizer("gamistudios", "Agentisco")
	body := "Upstream: https://github.com/termux/termux-app/compare/v1...v2"
	got := sanitizer.Process(body)
	if got != body {
		t.Fatalf("unrelated link changed: %q", got)
	}
}

func TestProcessNormalizesLineEndingsAndTrims(t *testing.T) {
	sanitizer := NewSanitizer("gamistudios", "Agentisco")
	got := sanitizer.Process("line one\r\nline two\r\n\r\n\r\n")
	if got != "line one\nline two" {
		t.Fatalf("normalization produced %q", got)
	}
}

func TestProcessTruncatesOversizedNotes(t *testing.T) {
	sanitizer := NewSanitizer("gamistudios", "Agentisco")
	body := strings.Repeat("- a long line of commit subject text\n", 20000)
	got := sanitizer.Process(body)
	if len(got) <= MaxBytes {
		t.Fatalf("expected truncation past %d bytes, got %d", MaxBytes, len(got))
	}
	if !strings.HasSuffix(got, "_Changelog truncated._") {
		t.Fatalf("truncation marker missing: %q", got[len(got)-40:])
	}
}

func TestProcessEmptyBody(t *testing.T) {
	sanitizer := NewSanitizer("gamistudios", "Agentisco")
	if got := sanitizer.Process(""); got != "" {
		t.Fatalf("empty body must stay empty, got %q", got)
	}
}

func assertNoPrivateReference(t *testing.T, value string) {
	t.Helper()
	for _, forbidden := range []string{"github.com/gamistudios", "api.github.com", "gamistudios"} {
		if strings.Contains(strings.ToLower(value), forbidden) {
			t.Fatalf("notes leak %q:\n%s", forbidden, value)
		}
	}
}
