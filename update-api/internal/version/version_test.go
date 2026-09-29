package version

import "testing"

// TestCodeMatchesClient pins the rule to UpdateRepository.parseVersionCode. The
// Android client compares these numbers to decide whether to offer an update, so
// a divergence would silently break or spam auto-updates.
func TestCodeMatchesClient(t *testing.T) {
	cases := []struct {
		version  string
		wantCode int64
	}{
		{"v1.4.0", 10400},
		{"1.4.0", 10400},
		{"V1.4.0", 10400},
		{"v1.0.1", 10001},
		{"v1.0.0", 10000},
		{"v9.9.9", 90909},
		{"1.4", 104},
		{"v2", 2},
		{"2", 2},
		{"v1.2.3.4", 10203},
		{"release-1.2.3", 123},
		{"v1.beta", 1},
		{"", 0},
		{"   ", 0},
		{"no-numbers", 0},
		{"v1.02.3", 10203},
		{"99999999999999999999", 0},
	}

	for _, testCase := range cases {
		got := Code(testCase.version)
		if got != testCase.wantCode {
			t.Errorf("Code(%q) = %d, want %d", testCase.version, got, testCase.wantCode)
		}
	}
}

// TestCodeOrdersMonotonically guards the comparison the whole API rests on:
// numeric ordering of codes must match the release order of the versions.
func TestCodeOrdersMonotonically(t *testing.T) {
	ordered := []string{"v1.0.0", "v1.0.1", "v1.1.0", "v1.9.9", "v1.10.0", "v2.0.0"}
	for i := 1; i < len(ordered); i++ {
		previous := Code(ordered[i-1])
		current := Code(ordered[i])
		if current <= previous {
			t.Fatalf("Code(%q)=%d must be greater than Code(%q)=%d", ordered[i], current, ordered[i-1], previous)
		}
	}
}
