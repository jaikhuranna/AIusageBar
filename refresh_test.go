package main

import (
	"context"
	"os/exec"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// fakeRefresher counts CLI runs and reports whatever cache age the test sets.
func fakeRefresher(age *atomic.Int64, runs *atomic.Int32, cmd string) *refresher {
	return &refresher{
		cmd: func(ctx context.Context) *exec.Cmd {
			runs.Add(1)
			return exec.CommandContext(ctx, "sh", "-c", cmd)
		},
		cacheAge: func() time.Duration { return time.Duration(age.Load()) },
		minAge:   time.Minute,
		timeout:  5 * time.Second,
	}
}

func TestRefreshOnlyWhenTheCacheIsStale(t *testing.T) {
	var age atomic.Int64
	var runs atomic.Int32
	r := fakeRefresher(&age, &runs, "true")

	age.Store(int64(10 * time.Second))
	if r.ensureFresh() || runs.Load() != 0 {
		t.Fatal("a fresh cache must not be refreshed")
	}
	age.Store(int64(5 * time.Minute))
	if !r.ensureFresh() || runs.Load() != 1 {
		t.Fatal("a stale cache should be refreshed")
	}
}

func TestRefreshAtMostOncePerMinAge(t *testing.T) {
	var age atomic.Int64
	var runs atomic.Int32
	// The CLI "succeeds" but the cache stays old (say Claude Code couldn't
	// reach Anthropic): clients must not turn that into a retry storm.
	r := fakeRefresher(&age, &runs, "true")
	age.Store(int64(time.Hour))
	r.ensureFresh()
	r.ensureFresh()
	r.ensureFresh()
	if runs.Load() != 1 {
		t.Fatalf("ran %d times within minAge, want 1", runs.Load())
	}
}

func TestAFailedRefreshAlsoWaitsOutMinAge(t *testing.T) {
	var age atomic.Int64
	var runs atomic.Int32
	r := fakeRefresher(&age, &runs, "exit 1")
	age.Store(int64(time.Hour))
	if r.ensureFresh() {
		t.Fatal("a failed refresh must report false")
	}
	r.ensureFresh()
	if runs.Load() != 1 {
		t.Fatalf("retried a failure %d times within minAge", runs.Load()-1)
	}
}

func TestConcurrentClientsShareOneRefresh(t *testing.T) {
	var age atomic.Int64
	var runs atomic.Int32
	r := fakeRefresher(&age, &runs, "sleep 0.2")
	age.Store(int64(time.Hour))
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); r.ensureFresh() }()
	}
	wg.Wait()
	if runs.Load() != 1 {
		t.Fatalf("8 concurrent requests ran the CLI %d times, want 1", runs.Load())
	}
}

func TestNilRefresherIsOff(t *testing.T) {
	var r *refresher
	if r.ensureFresh() {
		t.Fatal("nil refresher should do nothing")
	}
	if newRefresher("", 0) != nil {
		t.Fatal("min age 0 should disable refresh")
	}
}
