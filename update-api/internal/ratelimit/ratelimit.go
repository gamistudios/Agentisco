// Package ratelimit provides a small token bucket limiter keyed per client.
//
// The service is public and its responses are tiny, so the point is to keep a
// single client from monopolising it (or hammering the on-demand cache fill);
// it is not an authorization mechanism.
package ratelimit

import (
	"sync"
	"time"
)

// Bucket is one client's allowance.
type Bucket struct {
	// Tokens is fractional: refill is computed lazily on touch.
	tokens    float64
	lastTouch time.Time
}

// Limiter allows ratePerSecond updates per key with a burst allowance.
type Limiter struct {
	mu      sync.Mutex
	rate    float64
	burst   float64
	buckets map[string]*Bucket
	// now is overridable for tests.
	now func() time.Time
	// lastSweep stops the map from growing without bound.
	lastSweep time.Time
}

// NewLimiter builds a limiter. A burst of at least 1 is enforced: download
// endpoints are naturally spiky (a client resumes, retries, and asks twice).
func NewLimiter(ratePerSecond float64, burst int) *Limiter {
	if burst < 1 {
		burst = 1
	}
	if ratePerSecond <= 0 {
		ratePerSecond = 1
	}
	return &Limiter{
		rate:    ratePerSecond,
		burst:   float64(burst),
		buckets: map[string]*Bucket{},
		now:     time.Now,
	}
}

// Allow reports whether key may make a request now. When it returns false, the
// second argument says how long to wait before retrying.
func (l *Limiter) Allow(key string) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()

	now := l.now()
	l.sweepLocked(now)

	bucket, ok := l.buckets[key]
	if !ok {
		bucket = &Bucket{tokens: l.burst, lastTouch: now}
		l.buckets[key] = bucket
	}

	elapsed := now.Sub(bucket.lastTouch)
	if elapsed < 0 {
		elapsed = 0
	}
	bucket.tokens += elapsed.Seconds() * l.rate
	if bucket.tokens > l.burst {
		bucket.tokens = l.burst
	}
	bucket.lastTouch = now

	if bucket.tokens >= 1 {
		bucket.tokens--
		return true, 0
	}
	needed := 1 - bucket.tokens
	wait := time.Duration(needed / l.rate * float64(time.Second))
	if wait < time.Second {
		wait = time.Second
	}
	return false, wait
}

// sweepLocked drops idle buckets so a spoofed stream of client IPs cannot grow
// the limiter's memory without bound.
func (l *Limiter) sweepLocked(now time.Time) {
	if now.Sub(l.lastSweep) < time.Minute {
		return
	}
	l.lastSweep = now
	idle := 10 * time.Minute
	for key, bucket := range l.buckets {
		if now.Sub(bucket.lastTouch) > idle {
			delete(l.buckets, key)
		}
	}
}
