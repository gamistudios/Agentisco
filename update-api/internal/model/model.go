// Package model holds the channel rules and the metadata served to clients.
package model

import (
	"strings"
	"time"
)

// Channel is one of the two supported update channels.
type Channel string

const (
	ChannelDebug   Channel = "debug"
	ChannelRelease Channel = "release"
)

// Channels lists the supported channels in documentation order.
var Channels = []Channel{ChannelDebug, ChannelRelease}

// ParseChannel validates a channel coming from a URL path. Anything other than
// the two known channels is an error: the API has no notion of "any" channel.
func ParseChannel(raw string) (Channel, bool) {
	switch Channel(strings.ToLower(strings.TrimSpace(raw))) {
	case ChannelDebug:
		return ChannelDebug, true
	case ChannelRelease:
		return ChannelRelease, true
	default:
		return "", false
	}
}

func (c Channel) String() string { return string(c) }

// MatchesAsset reports whether a release asset belongs to this channel.
//
// The naming convention comes from .github/workflows/ci.yml, which publishes
// Awaki-<tag>-debug.apk and Awaki-<tag>-release.apk (plus the .aab,
// which is never an installable update). The suffix rules are the primary
// match; the fallbacks keep older releases whose assets only carry the channel
// word (for example awaki-debug.apk) resolvable, so previously published
// releases remain servable.
func (c Channel) MatchesAsset(name string) bool {
	lower := strings.ToLower(strings.TrimSpace(name))
	if !strings.HasSuffix(lower, ".apk") {
		return false
	}
	switch c {
	case ChannelDebug:
		if strings.HasSuffix(lower, "-debug.apk") {
			return true
		}
		return strings.Contains(lower, "debug") && !strings.Contains(lower, "release")
	case ChannelRelease:
		if strings.HasSuffix(lower, "-release.apk") {
			return true
		}
		return strings.Contains(lower, "release") && !strings.Contains(lower, "debug")
	default:
		return false
	}
}

// AssetKind classifies a release asset name for logging and diagnostics.
func AssetKind(name string) string {
	lower := strings.ToLower(strings.TrimSpace(name))
	switch {
	case strings.HasSuffix(lower, ".apk"):
		return "apk"
	case strings.HasSuffix(lower, ".aab"):
		return "aab"
	default:
		return "other"
	}
}

// UpdateInfo is the public metadata document served for a channel. Field names
// and semantics follow what the Android UpdateRepository needs: the numeric
// version code decides whether an update exists, size and sha256 gate the
// download verification, and downloadUrl must stay stable for one release so a
// resumed transfer can never be pointed at a different artifact.
type UpdateInfo struct {
	Channel      Channel   `json:"channel"`
	VersionCode  int64     `json:"versionCode"`
	VersionName  string    `json:"versionName"`
	TagName      string    `json:"tagName"`
	ApkName      string    `json:"apkName"`
	Size         int64     `json:"size"`
	SHA256       string    `json:"sha256"`
	ReleaseNotes string    `json:"releaseNotes"`
	PublishedAt  time.Time `json:"publishedAt"`
	SyncedAt     time.Time `json:"syncedAt"`
	DownloadURL  string    `json:"downloadUrl"`
	// Cached reports whether the APK bytes are already on this server. Clients
	// can use it to decide whether a download will start immediately.
	Cached bool `json:"cached"`
}

// CheckResult answers "is this client version current?".
type CheckResult struct {
	UpdateAvailable    bool   `json:"updateAvailable"`
	CurrentVersionCode int64  `json:"currentVersionCode"`
	LatestVersionCode  int64  `json:"latestVersionCode"`
	LatestVersionName  string `json:"latestVersionName"`
	TagName            string `json:"tagName,omitempty"`
	DownloadURL        string `json:"downloadUrl,omitempty"`
	UpdateInfo
}
