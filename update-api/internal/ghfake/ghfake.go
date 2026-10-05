// Package ghfake is a stand-in for the GitHub Releases API of a private
// repository.
//
// It exists so the whole flow - channel resolution, checksum verification,
// caching, failure handling - can be exercised locally and in tests without
// touching a real repository, and so credentials are never needed to develop
// against the service.
package ghfake

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Asset is a release attachment served by the fake.
type Asset struct {
	ID   int64
	Name string
	// Bytes is mutable, which is how a test injects a corrupt or replaced
	// artifact between synchronisations.
	Bytes []byte

	digest string
}

func (a *Asset) applyBytes(bytes []byte) {
	a.Bytes = bytes
	sum := sha256.Sum256(bytes)
	a.digest = "sha256:" + hex.EncodeToString(sum[:])
}

// Release is one release in the fake repository.
type Release struct {
	ID          int64
	Tag         string
	Name        string
	Body        string
	Draft       bool
	PreRelease  bool
	PublishedAt time.Time
	Assets      []*Asset
}

// AssetInput describes an asset when adding a release.
type AssetInput struct {
	Name  string
	Bytes []byte
}

// Server is the fake API.
type Server struct {
	// Token is the credential the fake accepts; an empty Token means the fake
	// accepts any Bearer token, which mirrors "the credential is not checked".
	Token string

	mu        sync.Mutex
	releases  []*Release
	assets    map[int64]*Asset
	assetTag  map[int64]string
	nextID    int64
	faults    []int
	listCalls int
	assetGets map[int64]int
}

// New starts an empty repository.
func New(token string) *Server {
	return &Server{
		Token:     token,
		assets:    map[int64]*Asset{},
		assetTag:  map[int64]string{},
		assetGets: map[int64]int{},
		nextID:    1000,
	}
}

// AddRelease appends a release with assets and returns it, newest-first order
// is maintained so listing behaves like GitHub.
func (s *Server) AddRelease(tag, name, body string, published time.Time, assets ...AssetInput) *Release {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.nextID++
	release := &Release{
		ID:          s.nextID,
		Tag:         tag,
		Name:        name,
		Body:        body,
		PublishedAt: published.UTC(),
	}
	for _, input := range assets {
		s.nextID++
		asset := &Asset{ID: s.nextID, Name: input.Name, Bytes: input.Bytes}
		asset.applyBytes(input.Bytes)
		release.Assets = append(release.Assets, asset)
		s.assets[asset.ID] = asset
		s.assetTag[asset.ID] = tag
	}
	s.releases = append(s.releases, release)
	sort.SliceStable(s.releases, func(i, j int) bool {
		return s.releases[i].PublishedAt.After(s.releases[j].PublishedAt)
	})
	return release
}

// QueueFault makes the next list releases calls answer with these statuses,
// which is how outage and retry behaviour is tested.
func (s *Server) QueueFault(statuses ...int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.faults = append(s.faults, statuses...)
}

// Stats reports how much traffic the fake saw.
func (s *Server) Stats() (listCalls int, assetFetches map[int64]int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	fetches := make(map[int64]int, len(s.assetGets))
	for id, count := range s.assetGets {
		fetches[id] = count
	}
	return s.listCalls, fetches
}

// AssetIDByName exposes an asset's identifier for assertions.
func (s *Server) AssetIDByName(name string) (int64, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, asset := range s.assets {
		if asset.Name == name {
			return asset.ID, true
		}
	}
	return 0, false
}

// ServeHTTP implements the two endpoints the service uses.
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if !s.authorized(r) {
		writeJSONStatus(w, http.StatusUnauthorized, map[string]any{"message": "Bad credentials"})
		return
	}

	path := strings.TrimSuffix(r.URL.Path, "/")
	switch {
	case strings.HasSuffix(path, "/releases"):
		s.handleList(w, r, path)
	case strings.Contains(path, "/releases/assets/"):
		s.handleAsset(w, r, path)
	default:
		writeJSONStatus(w, http.StatusNotFound, map[string]any{"message": "Not Found"})
	}
}

func (s *Server) authorized(r *http.Request) bool {
	provided := r.Header.Get("Authorization")
	if !strings.HasPrefix(provided, "Bearer ") {
		return false
	}
	if s.Token == "" {
		return true
	}
	return strings.TrimPrefix(provided, "Bearer ") == s.Token
}

func (s *Server) handleList(w http.ResponseWriter, r *http.Request, path string) {
	if !strings.HasPrefix(path, "/repos/") {
		writeJSONStatus(w, http.StatusNotFound, map[string]any{"message": "Not Found"})
		return
	}

	s.mu.Lock()
	s.listCalls++
	if len(s.faults) > 0 {
		status := s.faults[0]
		s.faults = s.faults[1:]
		s.mu.Unlock()
		writeJSONStatus(w, status, map[string]any{"message": "synthetic fault"})
		return
	}

	perPage := parseInt(r.URL.Query().Get("per_page"), 30)
	page := parseInt(r.URL.Query().Get("page"), 1)
	if page < 1 {
		page = 1
	}
	if perPage < 1 || perPage > 100 {
		perPage = 30
	}
	start := (page - 1) * perPage
	var visible []*Release
	if start < len(s.releases) {
		end := min(start+perPage, len(s.releases))
		visible = s.releases[start:end]
	}

	payload := make([]map[string]any, 0, len(visible))
	for _, release := range visible {
		assets := make([]map[string]any, 0, len(release.Assets))
		for _, asset := range release.Assets {
			assets = append(assets, map[string]any{
				"id":                   asset.ID,
				"name":                 asset.Name,
				"size":                 len(asset.Bytes),
				"digest":               asset.digest,
				"content_type":         "application/vnd.android.package-archive",
				"browser_download_url": fmt.Sprintf("https://github.example.invalid%s", assetPath(asset.ID)),
			})
		}
		payload = append(payload, map[string]any{
			"id":           release.ID,
			"tag_name":     release.Tag,
			"name":         release.Name,
			"body":         release.Body,
			"draft":        release.Draft,
			"prerelease":   release.PreRelease,
			"published_at": release.PublishedAt.Format(time.RFC3339),
			"assets":       assets,
		})
	}
	s.mu.Unlock()

	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	if err := json.NewEncoder(w).Encode(payload); err != nil {
		return
	}
}

func (s *Server) handleAsset(w http.ResponseWriter, r *http.Request, path string) {
	parts := strings.Split(strings.Trim(path, "/"), "/")
	if len(parts) < 2 || parts[0] != "repos" {
		writeJSONStatus(w, http.StatusNotFound, map[string]any{"message": "Not Found"})
		return
	}
	id, err := strconv.ParseInt(parts[len(parts)-1], 10, 64)
	if err != nil {
		writeJSONStatus(w, http.StatusNotFound, map[string]any{"message": "Not Found"})
		return
	}

	s.mu.Lock()
	asset, ok := s.assets[id]
	if !ok {
		s.mu.Unlock()
		writeJSONStatus(w, http.StatusNotFound, map[string]any{"message": "Not Found"})
		return
	}
	s.assetGets[id]++
	content := append([]byte(nil), asset.Bytes...)
	digest := asset.digest
	name := asset.Name
	s.mu.Unlock()

	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.Itoa(len(content)))
	w.Header().Set("Content-Disposition", `attachment; filename="`+name+`"`)
	w.Header().Set("X-Fake-Digest", digest)
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(content)
}

func assetPath(id int64) string {
	return "/repos/fake/awaki/releases/assets/" + strconv.FormatInt(id, 10)
}

// SyntheticAPK builds deterministic bytes that begin with the ZIP local-file
// header, so a client that checks for an APK signature accepts them while the
// fixtures stay tiny and reproducible.
func SyntheticAPK(seed string, size int) []byte {
	if size < 8 {
		size = 8
	}
	content := make([]byte, size)
	content[0], content[1], content[2], content[3] = 'P', 'K', 0x03, 0x04
	state := uint64(1469598103934665603)
	for _, b := range []byte(seed) {
		state = (state ^ uint64(b)) * 1099511628211
	}
	for i := 4; i < size; i++ {
		state ^= state << 13
		state ^= state >> 7
		state ^= state << 17
		content[i] = byte(state)
	}
	return content
}

func writeJSONStatus(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func parseInt(value string, fallback int) int {
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return fallback
	}
	return parsed
}
