package model

import "testing"

func TestParseChannel(t *testing.T) {
	cases := map[string]struct {
		parsed Channel
		ok     bool
	}{
		"debug":         {ChannelDebug, true},
		"release":       {ChannelRelease, true},
		"Release":       {ChannelRelease, true},
		" release ":     {ChannelRelease, true},
		"":              {"", false},
		"beta":          {"", false},
		"../etc":        {"", false},
		"debug.apk":     {"", false},
		"RELEASE-EXTRA": {"", false},
	}
	for raw, want := range cases {
		got, ok := ParseChannel(raw)
		if ok != want.ok || got != want.parsed {
			t.Errorf("ParseChannel(%q) = (%q, %v), want (%q, %v)", raw, got, ok, want.parsed, want.ok)
		}
	}
}

// TestMatchesAssetUsesThePublishedNamingConvention: ci.yml uploads
// Awaki-<tag>-debug.apk, Awaki-<tag>-release.apk and a .aab. The .aab is
// not installable and must never be chosen for a channel.
func TestMatchesAssetUsesThePublishedNamingConvention(t *testing.T) {
	cases := []struct {
		channel   Channel
		asset     string
		wantMatch bool
	}{
		{ChannelDebug, "Awaki-v1.5.0-debug.apk", true},
		{ChannelRelease, "Awaki-v1.5.0-release.apk", true},
		{ChannelDebug, "Awaki-v1.5.0-release.apk", false},
		{ChannelRelease, "Awaki-v1.5.0-debug.apk", false},
		{ChannelDebug, "Awaki-v1.5.0-release.aab", false},
		{ChannelRelease, "Awaki-v1.5.0-release.aab", false},
		{ChannelDebug, "mapping.txt", false},
		// Older releases that only carry the channel word stay resolvable.
		{ChannelDebug, "awaki-debug.apk", true},
		{ChannelRelease, "app-release.apk", true},
		{ChannelDebug, "app-release.apk", false},
		{ChannelRelease, "app-release.apk", true},
		{ChannelDebug, "Awaki-v1.5.0-DEBUG.APK", true},
		// An APK for a different product must not be picked up.
		{ChannelRelease, "OtherApp-release.apk", true},
	}
	for _, testCase := range cases {
		if got := testCase.channel.MatchesAsset(testCase.asset); got != testCase.wantMatch {
			t.Errorf("%s.MatchesAsset(%q) = %v, want %v",
				testCase.channel, testCase.asset, got, testCase.wantMatch)
		}
	}
}

func TestAssetKind(t *testing.T) {
	cases := map[string]string{
		"a.apk":         "apk",
		"a.AAB":         "aab",
		"notes.txt":     "other",
		"a-release.aab": "aab",
	}
	for name, want := range cases {
		if got := AssetKind(name); got != want {
			t.Errorf("AssetKind(%q) = %q, want %q", name, got, want)
		}
	}
}
