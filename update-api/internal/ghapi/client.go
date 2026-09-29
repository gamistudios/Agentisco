// Package ghapi is a minimal GitHub Releases client for the one repository the
// service is configured to watch.
//
// It is deliberately not a general GitHub proxy: the only operations it can
// perform are listing releases of the configured repository and reading one of
// their assets. The credential and the repository coordinates never leave this
// package, and no error it returns carries a URL, so nothing can surface the
// private location through logs or API responses.
package ghapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math/rand/v2"
	"net/http"
	"strconv"
	"time"
)

// Categories of upstream failure the synchroniser needs to distinguish.
var (
	// ErrUnauthorized means the credential is missing, expired or lacks access.
	ErrUnauthorized = errors.New("github: credential rejected")
	// ErrNotFound means the release or asset is gone for good.
	ErrNotFound = errors.New("github: not found")
	// ErrNoReleases means the repository has no published release yet.
	ErrNoReleases = errors.New("github: no published releases")
	// ErrUnavailable covers connectivity, rate limiting and 5xx answers. It is
	// retryable and must never be read as "this release disappeared".
	ErrUnavailable = errors.New("github: unavailable")
)

// Asset is a release attachment.
type Asset struct {
	ID          int64  `json:"id"`
	Name        string `json:"name"`
	Size        int64  `json:"size"`
	Digest      string `json:"digest"`
	ContentType string `json:"content_type"`
}

// Release is one published GitHub release.
type Release struct {
	ID          int64     `json:"id"`
	TagName     string    `json:"tag_name"`
	Name        string    `json:"name"`
	Body        string    `json:"body"`
	Draft       bool      `json:"draft"`
	PreRelease  bool      `json:"prerelease"`
	PublishedAt time.Time `json:"published_at"`
	Assets      []Asset   `json:"assets"`
}

// Options configures a Client.
type Options struct {
	APIBase   string
	Owner     string
	Repo      string
	Token     string
	Timeout   time.Duration
	MaxItems  int
	UserAgent string
	Logger    *slog.Logger
}

const (
	maxAttempts      = 4
	baseRetryBackoff = 500 * time.Millisecond
	maxRetryBackoff  = 20 * time.Second
	acceptJSON       = "application/vnd.github+json"
	acceptStream     = "application/octet-stream"
)

// Client talks to the GitHub Releases API for one repository.
type Client struct {
	http      *http.Client
	stream    *http.Client
	apiBase   string
	owner     string
	repo      string
	token     string
	maxItems  int
	perPage   int
	userAgent string
	logger    *slog.Logger
	// sleep is overridable so tests do not wait on backoff.
	sleep func(ctx context.Context, d time.Duration) error
}

// New builds a client whose timeout bounds headers and metadata reads.
//
// Streaming an APK out of a private repository takes far longer than the request
// that lists releases, so the transfer gets its own client with no overall
// deadline: it is bounded instead by the caller's context and by
// ResponseHeaderTimeout, which is what actually catches a dead upstream.
func New(opts Options) *Client {
	timeout := opts.Timeout
	if timeout <= 0 {
		timeout = 20 * time.Second
	}
	transport := &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		MaxIdleConns:          32,
		MaxIdleConnsPerHost:   8,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: timeout,
		ExpectContinueTimeout: 5 * time.Second,
	}
	return &Client{
		http:      &http.Client{Transport: transport, Timeout: timeout},
		stream:    &http.Client{Transport: transport},
		apiBase:   trimTrailingSlash(opts.APIBase),
		owner:     opts.Owner,
		repo:      opts.Repo,
		token:     opts.Token,
		maxItems:  max(1, opts.MaxItems),
		perPage:   100,
		userAgent: valueOr(opts.UserAgent, "agentisco-update-api"),
		logger:    opts.Logger,
		sleep:     sleepCtx,
	}
}

// ListReleases returns the newest releases of the configured repository, newest
// first, up to the configured page budget.
//
// The service never asks GitHub for "the latest release": each channel is
// resolved by the caller from this list, so a debug-only publish cannot hide the
// newest release build (and vice versa). Drafts and pre-releases are filtered
// out here, which matches what GitHub's own "latest" endpoint hands out.
func (c *Client) ListReleases(ctx context.Context) ([]Release, error) {
	releases := make([]Release, 0, c.maxItems)
	for page := 1; len(releases) < c.maxItems; page++ {
		endpoint := fmt.Sprintf("%s/repos/%s/%s/releases?per_page=%d&page=%d",
			c.apiBase, c.owner, c.repo, c.perPage, page)
		var batch []Release
		if err := c.getJSON(ctx, endpoint, &batch); err != nil {
			return nil, err
		}
		if len(batch) == 0 {
			break
		}
		releases = append(releases, batch...)
		if len(batch) < c.perPage {
			break
		}
	}
	if len(releases) > c.maxItems {
		releases = releases[:c.maxItems]
	}

	published := releases[:0]
	for _, release := range releases {
		if release.Draft || release.PreRelease {
			continue
		}
		published = append(published, release)
	}
	if len(published) == 0 {
		return nil, ErrNoReleases
	}
	return published, nil
}

// OpenAsset streams one release asset. The caller must close the returned body.
//
// Assets are fetched through the authenticated API rather than a browser
// download URL: on a private repository that is the only address that works, and
// it keeps the credential use entirely server-side.
func (c *Client) OpenAsset(ctx context.Context, asset Asset) (io.ReadCloser, int64, error) {
	endpoint := fmt.Sprintf("%s/repos/%s/%s/releases/assets/%d", c.apiBase, c.owner, c.repo, asset.ID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, 0, ErrUnavailable
	}
	req.Header.Set("Accept", acceptStream)
	c.authorize(req)

	resp, err := c.doRetrying(ctx, req, false)
	if err != nil {
		return nil, 0, err
	}
	if resp.StatusCode != http.StatusOK {
		defer resp.Body.Close()
		return nil, 0, classify(resp)
	}
	size := asset.Size
	if size <= 0 {
		size = resp.ContentLength
	}
	if size < 0 {
		size = 0
	}
	return resp.Body, size, nil
}

func (c *Client) getJSON(ctx context.Context, endpoint string, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return ErrUnavailable
	}
	req.Header.Set("Accept", acceptJSON)
	req.Header.Set("X-GitHub-Api-Version", "2022-11-28")
	c.authorize(req)

	resp, err := c.doRetrying(ctx, req, true)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return classify(resp)
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 32<<20)).Decode(out); err != nil {
		return fmt.Errorf("%w: decode release payload", ErrUnavailable)
	}
	return nil
}

// doRetrying performs the request, retrying transient failures with capped
// exponential backoff and honouring Retry-After. A 404/401/403 answer is
// returned immediately: repeating it only wastes the rate limit.
func (c *Client) doRetrying(ctx context.Context, req *http.Request, allowRetry bool) (*http.Response, error) {
	// Asset bodies use the deadline-free client: a large APK is a long request,
	// and it is the caller's context that must be able to cancel it.
	client := c.http
	if !allowRetry {
		client = c.stream
	}
	var lastErr error
	for attempt := 0; attempt < maxAttempts; attempt++ {
		if err := ctx.Err(); err != nil {
			return nil, ErrUnavailable
		}
		// Clone per attempt: a retried request must not reuse a consumed body.
		attemptReq := req.Clone(ctx)
		resp, err := client.Do(attemptReq)
		if err != nil {
			// The transport error text embeds the URL, so it is dropped here.
			lastErr = ErrUnavailable
			c.debug(ctx, "github request failed", "attempt", attempt+1)
			if !allowRetry {
				return nil, ErrUnavailable
			}
			if waitErr := c.wait(ctx, attempt, nil); waitErr != nil {
				return nil, waitErr
			}
			continue
		}

		if resp.StatusCode < http.StatusInternalServerError &&
			resp.StatusCode != http.StatusTooManyRequests &&
			resp.StatusCode != http.StatusRequestTimeout {
			return resp, nil
		}

		if !allowRetry {
			return resp, classify(resp)
		}

		c.debug(ctx, "github throttled or failing", "status", resp.StatusCode, "attempt", attempt+1)
		retryAfter := parseRetryAfter(resp.Header.Get("Retry-After"))
		resp.Body.Close()
		if waitErr := c.wait(ctx, attempt, retryAfter); waitErr != nil {
			return nil, waitErr
		}
	}
	if lastErr == nil {
		lastErr = ErrUnavailable
	}
	return nil, lastErr
}

func (c *Client) wait(ctx context.Context, attempt int, retryAfter *time.Duration) error {
	backoff := baseRetryBackoff << attempt
	if backoff > maxRetryBackoff {
		backoff = maxRetryBackoff
	}
	if retryAfter != nil && *retryAfter > backoff {
		backoff = min(*retryAfter, maxRetryBackoff)
	}
	// Jitter keeps a fleet of restarted services from retrying in lockstep.
	backoff += time.Duration(rand.Int64N(int64(baseRetryBackoff)))
	return c.sleep(ctx, backoff)
}

// authorize attaches the server-side credential. It is the only place the token
// is used, and it never reaches a response body or a log line.
func (c *Client) authorize(req *http.Request) {
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	req.Header.Set("User-Agent", c.userAgent)
}

func (c *Client) debug(ctx context.Context, message string, args ...any) {
	if c.logger == nil {
		return
	}
	c.logger.DebugContext(ctx, message, args...)
}

// classify maps an HTTP answer onto a retry category, without echoing the URL.
func classify(resp *http.Response) error {
	defer resp.Body.Close()
	switch {
	case resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden:
		return ErrUnauthorized
	case resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusGone:
		return ErrNotFound
	case resp.StatusCode == http.StatusTooManyRequests || resp.StatusCode == http.StatusRequestTimeout:
		return ErrUnavailable
	case resp.StatusCode >= http.StatusInternalServerError:
		return ErrUnavailable
	default:
		return fmt.Errorf("github: unexpected status %d", resp.StatusCode)
	}
}

func parseRetryAfter(raw string) *time.Duration {
	if raw == "" {
		return nil
	}
	if seconds, err := strconv.Atoi(raw); err == nil && seconds >= 0 {
		d := time.Duration(seconds) * time.Second
		return &d
	}
	if when, err := http.ParseTime(raw); err == nil {
		d := time.Until(when)
		if d < 0 {
			d = 0
		}
		return &d
	}
	return nil
}

func sleepCtx(ctx context.Context, d time.Duration) error {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

func trimTrailingSlash(value string) string {
	for len(value) > 1 && value[len(value)-1] == '/' {
		value = value[:len(value)-1]
	}
	return value
}

func valueOr(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}
