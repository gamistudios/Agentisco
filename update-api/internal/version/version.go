// Package version resolves the numeric version code of a release.
//
// The rule is a faithful port of UpdateRepository.parseVersionCode on Android,
// which is the client that consumes these numbers. Comparison is always
// numeric — version strings are never ordered lexicographically.
package version

import (
	"regexp"
	"strconv"
	"strings"
)

// Code computes the Android version code for a version string such as
// "v1.4.0", "1.4.0" or "1.4".
//
// MAJOR.MINOR.PATCH -> MAJOR*10000 + MINOR*100 + PATCH
// MAJOR.MINOR       -> MAJOR*100 + MINOR
// MAJOR             -> MAJOR
//
// A string that is not numeric at all degrades to the digits it contains, and
// to 0 when there are none, exactly like the client does.
func Code(version string) int64 {
	// The order matters for parity with the client: the prefix is removed before
	// trimming, so " v1.2.3 " degrades to its digits exactly like parseVersionCode does.
	cleaned := strings.TrimPrefix(version, "v")
	cleaned = strings.TrimPrefix(cleaned, "V")
	cleaned = strings.TrimSpace(cleaned)

	parts := strings.Split(cleaned, ".")
	if len(parts) == 1 {
		if n, err := strconv.ParseInt(parts[0], 10, 64); err == nil {
			return n
		}
		return digitsOnly(cleaned)
	}

	nums := make([]int64, 0, len(parts))
	for _, p := range parts {
		n, err := strconv.ParseInt(p, 10, 64)
		if err != nil {
			return digitsOnly(cleaned)
		}
		nums = append(nums, n)
	}

	if len(nums) == 2 {
		return nums[0]*100 + nums[1]
	}
	return nums[0]*10000 + nums[1]*100 + nums[2]
}

var nonDigits = regexp.MustCompile("[^0-9]")

// digitsOnly mirrors the client's NumberFormatException fallback: keep only the
// digits found in the string.
func digitsOnly(value string) int64 {
	digits := nonDigits.ReplaceAllString(value, "")
	if digits == "" {
		return 0
	}
	n, err := strconv.ParseInt(digits, 10, 64)
	if err != nil {
		// Overflow: the client's toLongOrNull would also fail here.
		return 0
	}
	return n
}
