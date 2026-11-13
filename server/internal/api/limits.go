package api

import (
	"sync"
	"time"

	syncengine "yaop-server/internal/sync"
)

// RateLimits: the per-device pacing of spec/sync.md §Default limits.
type RateLimits struct {
	PushPerMin int // 60 pushes/min per device
	PullPerMin int // 120 pulls/min per device
	OpsPerDay  int // 20 000 ops/day per device
}

// DefaultRateLimits are the spec defaults.
func DefaultRateLimits() RateLimits {
	return RateLimits{PushPerMin: 60, PullPerMin: 120, OpsPerDay: 20000}
}

// rateLimiter keeps one rolling window per device (pushes, pulls, ops).
type rateLimiter struct {
	mu     sync.Mutex
	limits RateLimits
	pushes map[string][]time.Time
	pulls  map[string][]time.Time
	ops    map[string][]time.Time
}

func newRateLimiter(limits RateLimits) *rateLimiter {
	return &rateLimiter{
		limits: limits,
		pushes: map[string][]time.Time{},
		pulls:  map[string][]time.Time{},
		ops:    map[string][]time.Time{},
	}
}

func (r *rateLimiter) setLimits(l RateLimits) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.limits = l
}

// prune drops entries older than the window from a slice and returns the rest
// plus the wait until the oldest remaining entry exits the window.
func prune(list []time.Time, window time.Duration, now time.Time) ([]time.Time, time.Duration) {
	cut := now.Add(-window)
	i := 0
	for i < len(list) && list[i].Before(cut) {
		i++
	}
	list = list[i:]
	if len(list) == 0 {
		return list, 0
	}
	next := window - now.Sub(list[0])
	if next < time.Second {
		next = time.Second
	}
	return list, next
}

// rateLimited builds the 429 with the spec field retry_after (seconds).
func rateLimited(kind string, used, limit int, retry time.Duration) *syncengine.SyncError {
	secs := int(retry.Seconds())
	if secs < 1 {
		secs = 1
	}
	return &syncengine.SyncError{
		Status:  429,
		Code:    "rate_limited",
		Message: kind + " rate limit exceeded: " + itoa(used) + " of " + itoa(limit) + " allowed; retry after " + itoa(secs) + "s",
		Extra:   map[string]any{"retry_after": secs},
	}
}

// allowPush counts one push of nOps ops against the device's windows. It
// returns the wait until allowed when the answer is no.
func (r *rateLimiter) allowPush(deviceID string, nOps int) (time.Duration, *syncengine.SyncError) {
	now := time.Now()
	r.mu.Lock()
	defer r.mu.Unlock()

	pushes, waitPush := prune(r.pushes[deviceID], time.Minute, now)
	r.pushes[deviceID] = pushes
	if len(pushes) >= r.limits.PushPerMin {
		return waitPush, rateLimited("push", len(pushes), r.limits.PushPerMin, waitPush)
	}
	ops, waitOps := prune(r.ops[deviceID], 24*time.Hour, now)
	r.ops[deviceID] = ops
	if len(ops)+nOps > r.limits.OpsPerDay {
		return waitOps, rateLimited("daily ops", len(ops), r.limits.OpsPerDay, waitOps)
	}

	pushes = append(pushes, now)
	r.pushes[deviceID] = pushes
	r.ops[deviceID] = append(r.ops[deviceID], now)
	return 0, nil
}

// allowPull counts one pull against the device's window.
func (r *rateLimiter) allowPull(deviceID string) (time.Duration, *syncengine.SyncError) {
	now := time.Now()
	r.mu.Lock()
	defer r.mu.Unlock()

	pulls, wait := prune(r.pulls[deviceID], time.Minute, now)
	r.pulls[deviceID] = pulls
	if len(pulls) >= r.limits.PullPerMin {
		return wait, rateLimited("pull", len(pulls), r.limits.PullPerMin, wait)
	}
	r.pulls[deviceID] = append(pulls, now)
	return 0, nil
}
