// Package notes turns a GitHub release body into the public-safe release notes
// the API serves.
//
// The existing format is preserved: CI writes "## What's Changed" with one
// "- subject" bullet per commit, indented body lines, and a trailing
// "**Full Changelog**:" line. Only the references to the (now private) GitHub
// repository are rewritten into a public-safe representation, because a
// repository URL is of no use to a client and would disclose where the service
// hides its artifacts.
package notes

import (
	"fmt"
	"regexp"
	"strings"
)

// MaxBytes bounds the notes stored and served for one release.
const MaxBytes = 64 * 1024

// Sanitizer removes references to the configured private repository.
type Sanitizer struct {
	private    *regexp.Regexp
	markdown   *regexp.Regexp
	compareURL *regexp.Regexp
	tagURL     *regexp.Regexp
}

const urlTail = `[^\s)\]"'<]*`

// NewSanitizer builds a sanitizer for one owner/repository pair.
func NewSanitizer(owner, repo string) *Sanitizer {
	pattern := "(?i)https?://(?:api\\.)?github\\.com/(?:repos/)?" +
		regexp.QuoteMeta(strings.TrimSpace(owner)) + "/" +
		regexp.QuoteMeta(strings.TrimSpace(repo)) + "(?:" + urlTail + ")"
	return &Sanitizer{
		private:    regexp.MustCompile(pattern),
		markdown:   regexp.MustCompile(`\[([^\]]*)\]\(([^()]+)\)`),
		compareURL: regexp.MustCompile(`/compare/([^/\s]+?)\.\.\.([^/\s]+?)` + `$`),
		tagURL:     regexp.MustCompile(`/releases/tag/([^/?#\s]+)`),
	}
}

// Process returns release notes that are safe to publish.
func (s *Sanitizer) Process(body string) string {
	normalized := strings.ReplaceAll(strings.ReplaceAll(body, "\r\n", "\n"), "\r", "\n")

	out := make([]string, 0, 32)
	for _, line := range strings.Split(normalized, "\n") {
		processed, keep := s.processLine(line)
		if keep {
			out = append(out, processed)
		}
	}

	trimmed := strings.TrimRight(strings.Join(out, "\n"), "\n ")
	if trimmed == "" {
		return ""
	}
	if len(trimmed) > MaxBytes {
		cut := trimmed[:MaxBytes]
		if idx := strings.LastIndex(cut, "\n"); idx > 0 {
			cut = cut[:idx]
		}
		trimmed = cut + "\n\n_Changelog truncated._"
	}
	return trimmed
}

// processLine rewrites private repository references. A line that carries
// nothing but the CI-generated Full Changelog link becomes a link-free
// comparison range, so the changelog intent survives without the URL.
func (s *Sanitizer) processLine(line string) (string, bool) {
	if !s.private.MatchString(line) {
		return line, true
	}

	// Markdown links keep their label; the private target is dropped or reduced
	// to a safe reference.
	line = s.markdown.ReplaceAllStringFunc(line, func(match string) string {
		groups := s.markdown.FindStringSubmatch(match)
		label, target := groups[1], groups[2]
		if !s.private.MatchString(target) {
			return match
		}
		if safe := s.safeReference(target); safe != "" {
			return fmt.Sprintf("%s (%s)", label, safe)
		}
		return label
	})

	replaced := s.private.ReplaceAllStringFunc(line, func(match string) string {
		return s.safeReference(match)
	})

	if strings.TrimSpace(replaced) == "" && strings.TrimSpace(line) != "" {
		return "", false
	}
	return replaced, true
}

// safeReference maps a repository URL onto a public-safe form: a compare range
// keeps its refs, a tag link keeps its tag, anything else is removed.
func (s *Sanitizer) safeReference(raw string) string {
	if !s.private.MatchString(raw) {
		return raw
	}
	if groups := s.compareURL.FindStringSubmatch(raw); groups != nil {
		return groups[1] + "..." + groups[2]
	}
	if groups := s.tagURL.FindStringSubmatch(raw); groups != nil {
		return groups[1]
	}
	return ""
}
