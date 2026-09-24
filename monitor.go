// monitor is the single sampler. One poll loop reads the Claude data, caches
// the wire snapshot, and evaluates alert thresholds; the tray and every HTTP
// handler read that cache instead of re-scanning the transcripts themselves.
//
// It is also the single writer of alert events: clients render events, they
// never derive them.
package main

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"log"
	"math/big"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	pairCodeTTL      = 2 * time.Minute
	maxPairFailures  = 10          // wrong codes before every pending code is burned
	defaultThreshold = "80,95,100" // 100 = the window is exhausted
)

type monitor struct {
	mu   sync.Mutex
	st   *state
	raw  snapshot
	snap wireSnapshot
	etag string

	thresholds []int
	hysteresis int

	pending  map[string]time.Time // pairing code -> expiry
	failures int

	// publicURL is where a tunnel serves this bridge. Set means reachable from
	// the internet, so every request needs a credential, paired or not.
	publicURL string

	// refresh, when set, has the official CLI refresh Claude Code's usage
	// cache before a client is answered from it. See refresh.go.
	refresh *refresher

	onUpdate func() // optional: tray refresh, called outside the lock
	started  time.Time
}

func newMonitor(thresholds []int, hysteresis int) *monitor {
	return &monitor{
		st:         loadState(statePath()),
		thresholds: thresholds,
		hysteresis: hysteresis,
		pending:    map[string]time.Time{},
		started:    time.Now(),
	}
}

// parseThresholds turns "80,95" into a sorted-as-given []int, ignoring junk.
func parseThresholds(s string) []int {
	var out []int
	for _, part := range strings.Split(s, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		n, err := strconv.Atoi(part)
		if err != nil || n <= 0 || n > 100 {
			log.Printf("ignoring bad threshold %q", part)
			continue
		}
		out = append(out, n)
	}
	return out
}

func (m *monitor) run() {
	m.poll()
	t := time.NewTicker(pollInterval)
	defer t.Stop()
	for range t.C {
		m.poll()
	}
}

func (m *monitor) poll() {
	s := gather() // the only place the transcripts get scanned
	w := wireOf(s)

	m.mu.Lock()
	m.raw = s
	m.snap = w
	m.etag = etagOf(w)
	fired := m.evaluateLocked(s, time.Now())
	if len(fired) > 0 {
		if err := m.st.save(); err != nil {
			log.Printf("state save: %v", err)
		}
	}
	m.mu.Unlock()

	for _, e := range fired {
		log.Printf("event %s %s %s (%d%%)", e.ID, e.Kind, e.Window, e.Snapshot.Utilization)
	}
	if m.onUpdate != nil {
		m.onUpdate()
	}
}

// evaluateLocked compares the new sample against the persisted arming state and
// returns any events it appended. Hysteresis lives here and nowhere else.
func (m *monitor) evaluateLocked(s snapshot, now time.Time) []event {
	var fired []event
	at := now.UTC().Format(time.RFC3339)

	for _, win := range []struct {
		name string
		w    *limitWindow
	}{{"five_hour", s.fiveHour}, {"seven_day", s.sevenDay}} {
		if win.w == nil {
			continue
		}
		util := win.w.Utilization
		resetsAt := ""
		var resetsIn int64
		if t := win.w.resetTime(); !t.IsZero() {
			resetsAt = t.UTC().Format(time.RFC3339)
			if d := time.Until(t); d > 0 {
				resetsIn = int64(d.Seconds())
			}
		}
		snap := eventSnap{Utilization: util, ResetsInSec: resetsIn}

		// A window whose resets_at moved has rolled over: good news, and the
		// thresholds below re-arm on the same signal.
		if prev := m.st.LastReset[win.name]; prev != "" && resetsAt != "" && resetsAt != prev {
			fired = append(fired, m.st.appendEvent(event{
				DedupeKey: win.name + ":reset:" + resetsAt,
				Kind:      "window_reset",
				Window:    win.name,
				Severity:  "info",
				At:        at,
				Snapshot:  snap,
			}))
		}
		if resetsAt != "" {
			m.st.LastReset[win.name] = resetsAt
		}

		for _, th := range m.thresholds {
			key := win.name + ":" + strconv.Itoa(th)
			firedFor, armed := m.st.Fired[key]
			if armed && (firedFor != resetsAt || util <= th-m.hysteresis) {
				delete(m.st.Fired, key) // new window, or dropped far enough back
				armed = false
			}
			if armed || util < th {
				continue
			}
			sev := "warn"
			if th >= 95 {
				sev = "critical"
			}
			fired = append(fired, m.st.appendEvent(event{
				DedupeKey: fmt.Sprintf("%s:%d:%s", win.name, th, resetsAt),
				Kind:      "threshold_crossed",
				Window:    win.name,
				Threshold: th,
				Severity:  sev,
				At:        at,
				Snapshot:  snap,
			}))
			m.st.Fired[key] = resetsAt
		}
	}
	return fired
}

// freshen is called before answering a client: if Claude Code's cache was
// stale and the CLI just refreshed it, resample now rather than serve the old
// sample for up to pollInterval. The refresher's lock keeps this to one extra
// sample per refresh, however many clients are waiting.
func (m *monitor) freshen() {
	if m.refresh.ensureFresh() {
		m.poll()
	}
}

// latestRaw is the same sample the wire snapshot came from, for the tray.
func (m *monitor) latestRaw() snapshot {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.raw
}

func (m *monitor) latest() (wireSnapshot, string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.snap, m.etag
}

// eventsSince returns events with an id strictly greater than since ("" = all
// we still hold), plus the new high-water mark to pass back next time.
func (m *monitor) eventsSince(since string) ([]event, string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []event{}
	for _, e := range m.st.Events {
		if since == "" || e.ID > since {
			out = append(out, e)
		}
	}
	high := since
	if n := len(m.st.Events); n > 0 {
		high = m.st.Events[n-1].ID
	}
	return out, high
}

// ---------- pairing ----------

// newPairCode mints a single-use 6-digit code, valid for pairCodeTTL.
func (m *monitor) newPairCode() string {
	code := randomDigits(6)
	m.mu.Lock()
	defer m.mu.Unlock()
	for c, exp := range m.pending {
		if time.Now().After(exp) {
			delete(m.pending, c)
		}
	}
	m.pending[code] = time.Now().Add(pairCodeTTL)
	return code
}

// redeem exchanges a valid code for a per-device token. Codes are single-use,
// and a run of wrong guesses burns every pending code rather than letting a
// LAN attacker walk the 6-digit space.
func (m *monitor) redeem(code, name string) (device, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	exp, ok := m.pending[code]
	if !ok || time.Now().After(exp) {
		delete(m.pending, code)
		m.failures++
		if m.failures >= maxPairFailures {
			m.pending = map[string]time.Time{}
			m.failures = 0
			log.Printf("pairing: too many bad codes, pending codes invalidated")
		}
		return device{}, false
	}
	delete(m.pending, code)
	m.failures = 0

	if name == "" {
		name = "device"
	}
	d := device{
		ID:       randomHex(8),
		Name:     name,
		Token:    randomToken(),
		PairedAt: time.Now().UTC().Format(time.RFC3339),
	}
	m.st.Devices = append(m.st.Devices, d)
	if err := m.st.save(); err != nil {
		log.Printf("state save: %v", err)
	}
	log.Printf("paired device %q (%s); the bridge now requires a token", d.Name, d.ID)
	return d, true
}

// tokenOK accepts the shared secret or any paired device token.
func (m *monitor) tokenOK(shared, tok string) bool {
	if tok == "" {
		return false
	}
	if shared != "" && subtle.ConstantTimeCompare([]byte(shared), []byte(tok)) == 1 {
		return true
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, d := range m.st.Devices {
		if subtle.ConstantTimeCompare([]byte(d.Token), []byte(tok)) == 1 {
			return true
		}
	}
	return false
}

// authRequired is true once there is any credential to check: a shared secret,
// or at least one paired device. Pairing a watch closes the bridge. A public
// bridge is closed from the start.
func (m *monitor) authRequired(shared string) bool {
	if shared != "" || m.publicURL != "" {
		return true
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.st.Devices) > 0
}

// ---------- helpers ----------

// etagOf hashes the fields that carry meaning. generated_at and resets_in_sec
// are deliberately excluded: they change on every poll but say nothing new, so
// including them would make every request a full body over a Bluetooth proxy.
// A 304 means "your copy is still current as of now".
func etagOf(w wireSnapshot) string {
	h := sha256.New()
	fmt.Fprintf(h, "v%d|%s|", schemaVersion, w.Host)
	for _, win := range []*wireWindow{w.FiveHour, w.SevenDay} {
		if win == nil {
			fmt.Fprint(h, "nil|")
			continue
		}
		fmt.Fprintf(h, "%d@%s|", win.Utilization, win.ResetsAt)
	}
	fmt.Fprintf(h, "%.2f|%.2f|%.2f", w.CostToday, w.CostSession, w.CostWeek)
	return `"` + hex.EncodeToString(h.Sum(nil))[:16] + `"`
}

func randomDigits(n int) string {
	b := make([]byte, n)
	for i := range b {
		v, err := rand.Int(rand.Reader, big.NewInt(10))
		if err != nil {
			v = big.NewInt(int64(time.Now().UnixNano() % 10))
		}
		b[i] = byte('0' + v.Int64())
	}
	return string(b)
}

func randomHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func randomToken() string {
	b := make([]byte, 32)
	rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}
