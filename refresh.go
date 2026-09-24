// refresh keeps Claude Code's plan-limit cache fresh while someone is looking.
//
// ~/.claude.json only moves when the official Claude Code fetches usage, which
// it does during a session. So when a client asks for /usage and that cache is
// older than minAge, the bridge runs the official CLI's local /usage command
// (no model call, a couple of seconds), which makes Claude Code fetch and
// rewrite the cache, and then samples it. The bridge itself never talks to
// Anthropic: the official client does the fetching, on its own credentials.
//
// minAge is the floor on how often that can happen, however many clients ask.
package main

import (
	"context"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"time"
)

type refresher struct {
	// cmd builds the refresh command. Swapped out in tests.
	cmd func(ctx context.Context) *exec.Cmd
	// cacheAge reports how old Claude Code's cached usage is.
	cacheAge func() time.Duration

	minAge  time.Duration
	timeout time.Duration

	mu      sync.Mutex // one refresh at a time; latecomers wait and share it
	lastTry time.Time
}

// newRefresher returns nil (refresh off) if minAge is 0 or no claude binary
// can be found.
func newRefresher(bin string, minAge time.Duration) *refresher {
	if minAge <= 0 {
		return nil
	}
	path := findClaude(bin)
	if path == "" {
		log.Printf("refresh: no claude binary found (set -claude); serving the cache as Claude Code leaves it")
		return nil
	}
	log.Printf("refresh: %s /usage when the cache is older than %s", path, minAge)
	return &refresher{
		cmd: func(ctx context.Context) *exec.Cmd {
			c := exec.CommandContext(ctx, path, "-p", "/usage", "--no-session-persistence", "--strict-mcp-config")
			c.Dir = os.TempDir() // not a project: no CLAUDE.md, no trust prompt
			return c
		},
		cacheAge: cacheAge,
		minAge:   minAge,
		timeout:  30 * time.Second,
	}
}

// ensureFresh refreshes the cache if it's older than minAge, at most once per
// minAge however it went, and reports whether it ran. Concurrent callers queue
// on the lock and then find the cache fresh.
func (r *refresher) ensureFresh() bool {
	if r == nil {
		return false
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.cacheAge() < r.minAge || time.Since(r.lastTry) < r.minAge {
		return false
	}
	r.lastTry = time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), r.timeout)
	defer cancel()
	start := time.Now()
	if out, err := r.cmd(ctx).CombinedOutput(); err != nil {
		log.Printf("refresh: claude /usage failed after %s: %v: %.200s", time.Since(start).Round(time.Millisecond), err, out)
		return false
	}
	return true
}

// findClaude resolves the CLI. The tray is started from a desktop autostart
// entry, whose PATH often lacks ~/.local/bin, where the installer puts it.
func findClaude(bin string) string {
	if bin != "" {
		if p, err := exec.LookPath(bin); err == nil {
			return p
		}
		return ""
	}
	if p, err := exec.LookPath("claude"); err == nil {
		return p
	}
	if home, err := os.UserHomeDir(); err == nil {
		p := filepath.Join(home, ".local", "bin", "claude")
		if info, err := os.Stat(p); err == nil && !info.IsDir() {
			return p
		}
	}
	return ""
}

// cacheAge is how long ago Claude Code last fetched usage. Unknown reads as
// ancient, so the first request refreshes.
func cacheAge() time.Duration {
	t := loadCacheFetchedAt()
	if t.IsZero() {
		return 24 * time.Hour
	}
	return time.Since(t)
}
