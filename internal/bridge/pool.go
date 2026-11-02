// pool turns a provider family's accounts into one pooled limit: totals, a
// burn rate, a projection with weekly reset step-ups, and the recorded history
// behind the graph. GET /pool serves it; the wire types are in
// internal/protocol/pool.go and the contract is README -> Endpoints.
//
// Everything is in "account-percent" (one account = 100). The math here is
// pure (buildPool, projectPool, poolBurn take their inputs and the time) so it
// can be tested without a clock; the persisted history is poolHistory.
package bridge

import (
	"aiusagebar/internal/protocol"
	"bufio"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"maps"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"sync"
	"time"
)

const (
	poolHorizon     = 7 * 24 * time.Hour
	poolStep        = time.Hour
	poolBurnWindow  = 3 * time.Hour
	poolMinBurnSpan = time.Minute
	poolMinSpacing  = 5 * time.Minute // history is never denser than this
	poolHeartbeat   = time.Hour       // an unchanged pool is still sampled this often
	poolKeep        = 14 * 24 * time.Hour
	poolDefaultDays = 7
	poolMaxDays     = 14
	poolETagBucket  = 15 * time.Minute // the projection is anchored at "now", so a 304 may not outlive this
	poolCompactAt   = 256              // dead lines in the file before it is rewritten
	poolEps         = 1e-9
)

var poolFamilies = []string{"claude", "codex"}

// ---------- members and totals ----------

// poolMember is one account's contribution, in account-percent remaining.
type poolMember struct {
	snap      wireSnapshot
	r5, r7    float64
	effective float64
	resetAt   time.Time     // the weekly window's next reset; zero if unknown
	period    time.Duration // how often that window resets
}

func remainingOf(w *wireWindow) (float64, bool) {
	if w == nil {
		return 0, false
	}
	return math.Min(math.Max(100-float64(w.Utilization), 0), 100), true
}

// poolMembers keeps the snapshots that carry limit data. A window an account
// does not report stands in for the other one, so a Codex plan with a single
// window still pools.
func poolMembers(snaps []wireSnapshot) []poolMember {
	var out []poolMember
	for _, s := range snaps {
		r5, ok5 := remainingOf(s.FiveHour)
		r7, ok7 := remainingOf(s.SevenDay)
		if !ok5 && !ok7 {
			continue
		}
		if !ok5 {
			r5 = r7
		}
		if !ok7 {
			r7 = r5
		}
		m := poolMember{snap: s, r5: r5, r7: r7, effective: math.Min(r5, r7), period: poolHorizon}
		if w := s.SevenDay; w != nil {
			if t, err := time.Parse(time.RFC3339, w.ResetsAt); err == nil {
				m.resetAt = t
			}
			if w.WindowMinutes > 0 {
				m.period = time.Duration(w.WindowMinutes) * time.Minute
			}
		}
		out = append(out, m)
	}
	return out
}

func sampleOf(ms []poolMember, now time.Time) protocol.PoolSample {
	s := protocol.PoolSample{T: now.Unix()}
	for _, m := range ms {
		s.RemainingPct += m.r7
		s.FiveHourRemainingPct += m.r5
		s.EffectiveRemainingPct += m.effective
		if w := m.snap.FiveHour; w != nil {
			if s.FiveHourRemaining == nil {
				s.FiveHourRemaining = map[string]float64{}
			}
			s.FiveHourRemaining[m.snap.Provider] = round(m.r5, 1)
			if t, err := time.Parse(time.RFC3339, w.ResetsAt); err == nil {
				if s.FiveHourResetsAt == nil {
					s.FiveHourResetsAt = map[string]int64{}
				}
				s.FiveHourResetsAt[m.snap.Provider] = t.Unix()
			}
		}
	}
	s.RemainingPct, s.FiveHourRemainingPct, s.EffectiveRemainingPct =
		round(s.RemainingPct, 1), round(s.FiveHourRemainingPct, 1), round(s.EffectiveRemainingPct, 1)
	return s
}

func round(v float64, places int) float64 {
	p := math.Pow10(places)
	return math.Round(v*p) / p
}

// ---------- burn ----------

// poolBurn is how fast the weekly pool drained over the last three hours, in
// percent per hour. hist is the recorded history (oldest first) and cur the
// pool's remaining right now. The history is a step function, so a pool that
// has not changed for hours burns 0, not "unknown". Only drops count: a weekly
// reset inside the window is a step up and must not cancel the drain. It is
// nil until there is a minute of history to measure.
func poolBurn(hist []protocol.PoolSample, now time.Time, cur float64) *float64 {
	if len(hist) == 0 {
		return nil
	}
	cutoff := now.Add(-poolBurnWindow)
	type pt struct {
		t time.Time
		v float64
	}
	var pts []pt
	for _, s := range hist {
		t := time.Unix(s.T, 0)
		if t.After(now) {
			continue
		}
		if t.Before(cutoff) {
			pts = append(pts[:0], pt{cutoff, s.RemainingPct}) // value still standing at the cutoff
			continue
		}
		pts = append(pts, pt{t, s.RemainingPct})
	}
	if len(pts) == 0 {
		return nil
	}
	span := now.Sub(pts[0].t)
	if span < poolMinBurnSpan {
		return nil
	}
	pts = append(pts, pt{now, cur})
	drop := 0.0
	for i := 1; i < len(pts); i++ {
		if d := pts[i-1].v - pts[i].v; d > 0 {
			drop += d
		}
	}
	b := round(drop/span.Hours(), 2)
	return &b
}

// ---------- projection ----------

type projection struct {
	points     []protocol.PoolPoint
	nextReset  *protocol.PoolReset
	firstEmpty time.Time // zero if not within the horizon
	poolEmpty  time.Time
}

// projectPool plays the pool forward seven days from now. Remaining drains
// linearly at burn (percent per hour for the whole pool), split evenly across
// the accounts that still have weekly capacity, so the last ones left drain
// faster. An account never goes below 0, and at each of its weekly resets it
// jumps back to 100.
//
// Points: one per hour from now, plus a before/after pair at each reset (same
// t, the after-point flagged reset). firstEmpty is the first future moment an
// account with capacity reaches 0; poolEmpty the first moment every account is
// at 0 at once (now, if the pool is already empty).
func projectPool(now time.Time, ms []poolMember, burn float64) projection {
	sim := &poolSim{burn: math.Max(burn, 0), rem: make([]float64, len(ms)), next: make([]time.Time, len(ms))}
	for i, m := range ms {
		sim.rem[i] = m.r7
		if !m.resetAt.IsZero() && m.period > 0 {
			t := m.resetAt
			if !t.After(now) { // stale resets_at: the next occurrence still ahead
				t = t.Add(m.period * time.Duration(now.Sub(t)/m.period+1))
			}
			sim.next[i] = t
		}
	}
	var p projection
	if len(ms) == 0 {
		return p
	}
	if sim.empty() {
		p.poolEmpty = now
	}
	end := now.Add(poolHorizon)
	at := now
	hourly := now.Add(poolStep)
	emit := func(t time.Time, reset bool) {
		p.points = append(p.points, protocol.PoolPoint{T: t.Unix(), RemainingPct: round(sim.total(), 1), Reset: reset})
	}
	emit(at, false)

	for at.Before(end) {
		// The next thing that happens: the hour tick or the earliest reset.
		ev, resets := hourly, false
		for _, t := range sim.next {
			if t.IsZero() || t.After(end) {
				continue
			}
			if t.Before(ev) || (t.Equal(ev) && !resets) {
				ev, resets = t, true
			}
		}
		if ev.After(end) {
			ev = end
		}
		sim.advance(at, ev.Sub(at).Hours(), &p)
		at = ev
		if resets {
			emit(at, false)
			gain := 0.0
			for i, t := range sim.next {
				if t.Equal(at) {
					gain += 100 - sim.rem[i]
					sim.rem[i] = 100
					sim.next[i] = t.Add(ms[i].period)
				}
			}
			if p.nextReset == nil {
				p.nextReset = &protocol.PoolReset{At: at.UTC().Format(time.RFC3339), GainPct: round(gain, 1)}
			}
			emit(at, true)
		}
		if at.Equal(hourly) {
			if !resets {
				emit(at, false)
			}
			hourly = hourly.Add(poolStep)
		}
	}
	return p
}

type poolSim struct {
	burn float64
	rem  []float64
	next []time.Time // each account's next weekly reset; zero = never
}

func (s *poolSim) total() (sum float64) {
	for _, r := range s.rem {
		sum += r
	}
	return sum
}

func (s *poolSim) empty() bool {
	for _, r := range s.rem {
		if r > poolEps {
			return false
		}
	}
	return true
}

// advance drains the pool for hours starting at from, noting in p when an
// account first empties and when the whole pool does.
func (s *poolSim) advance(from time.Time, hours float64, p *projection) {
	at := from
	for hours > poolEps && s.burn > 0 {
		var active []int
		lowest := math.Inf(1)
		for i, r := range s.rem {
			if r > poolEps {
				active = append(active, i)
				lowest = math.Min(lowest, r)
			}
		}
		if len(active) == 0 {
			return
		}
		rate := s.burn / float64(len(active))
		toEmpty := lowest / rate
		step := math.Min(toEmpty, hours)
		for _, i := range active {
			s.rem[i] -= rate * step
		}
		at = at.Add(time.Duration(step * float64(time.Hour)))
		hours -= step
		if step < toEmpty { // the span ended first
			break
		}
		for _, i := range active {
			if s.rem[i] <= poolEps {
				s.rem[i] = 0
				if p.firstEmpty.IsZero() {
					p.firstEmpty = at
				}
			}
		}
		if s.empty() && p.poolEmpty.IsZero() {
			p.poolEmpty = at
		}
	}
}

// ---------- the response ----------

func rfc3339Ptr(t time.Time) *string {
	if t.IsZero() {
		return nil
	}
	s := t.UTC().Format(time.RFC3339)
	return &s
}

// buildPool assembles the /pool answer. snaps are the family's current
// snapshots, hist the recorded history to return (oldest first, the last
// three hours at least).
func buildPool(family string, snaps []wireSnapshot, hist []protocol.PoolSample, now time.Time) protocol.Pool {
	ms := poolMembers(snaps)
	cur := sampleOf(ms, now)
	pool := protocol.Pool{
		Family:                family,
		Accounts:              len(ms),
		CapacityPct:           float64(100 * len(ms)),
		RemainingPct:          cur.RemainingPct,
		FiveHourRemainingPct:  cur.FiveHourRemainingPct,
		EffectiveRemainingPct: cur.EffectiveRemainingPct,
		Members:               []protocol.PoolMember{},
		History:               []protocol.PoolSample{},
		Projection:            []protocol.PoolPoint{},
		GeneratedAt:           now.UTC().Format(time.RFC3339),
	}
	if len(ms) == 0 {
		for _, s := range snaps {
			if s.SourceError != "" {
				pool.SourceError = s.SourceError
				break
			}
		}
		if pool.SourceError == "" {
			pool.SourceError = "No " + family + " account has usage data yet."
		}
		return pool
	}
	for _, m := range ms {
		pool.Members = append(pool.Members, protocol.PoolMember{
			Provider: m.snap.Provider, Account: m.snap.Account,
			FiveHour: refreshCountdown(m.snap.FiveHour), SevenDay: refreshCountdown(m.snap.SevenDay),
			SourceError: m.snap.SourceError,
		})
	}
	pool.History = append(pool.History, hist...)
	pool.BurnPctPerHour = poolBurn(hist, now, cur.RemainingPct)
	burn := 0.0
	if pool.BurnPctPerHour != nil {
		burn = *pool.BurnPctPerHour
	}
	proj := projectPool(now, ms, burn)
	pool.Projection = proj.points
	pool.NextReset = proj.nextReset
	pool.FirstEmptyAt = rfc3339Ptr(proj.firstEmpty)
	pool.PoolEmptyAt = rfc3339Ptr(proj.poolEmpty)
	return pool
}

// poolETag hashes what the answer is made of rather than the body, whose
// projection is re-anchored at every request. The time bucket bounds how long
// a 304 can keep a projection that has drifted from "now".
func poolETag(p protocol.Pool, snaps []wireSnapshot, days int, now time.Time) string {
	h := sha256.New()
	fmt.Fprintf(h, "v%d|%s|%d|%d|", schemaVersion, p.Family, days, now.Unix()/int64(poolETagBucket.Seconds()))
	for _, s := range snaps {
		fmt.Fprint(h, etagOf(s), "|")
	}
	if p.BurnPctPerHour != nil {
		fmt.Fprintf(h, "b%.2f|", *p.BurnPctPerHour)
	}
	fmt.Fprintf(h, "%d", len(p.History))
	if n := len(p.History); n > 0 {
		fmt.Fprintf(h, "@%d", p.History[n-1].T)
	}
	return `"` + hex.EncodeToString(h.Sum(nil))[:16] + `"`
}

// ---------- history ----------

// poolHistory is a family's recorded samples, mirrored to a JSON-lines file.
type poolHistory struct {
	mu      sync.Mutex
	path    string // "" = memory only
	samples []protocol.PoolSample
	dead    int // lines in the file that samples no longer holds
}

func poolHistoryPath(family string) string {
	p := statePath()
	if p == "" {
		return ""
	}
	return filepath.Join(filepath.Dir(p), "pool-history-"+family+".jsonl")
}

// loadPoolHistory reads the file, dropping samples older than the retention
// period and any line that is unreadable or out of order, and rewrites it
// compacted when something was dropped.
func loadPoolHistory(path string, now time.Time) *poolHistory {
	h := &poolHistory{path: path}
	if path == "" {
		return h
	}
	f, err := os.Open(path)
	if err != nil {
		return h
	}
	defer f.Close()
	cutoff := now.Add(-poolKeep).Unix()
	lines, last := 0, int64(0)
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		lines++
		var s protocol.PoolSample
		if json.Unmarshal(sc.Bytes(), &s) != nil || s.T < cutoff || s.T < last {
			continue
		}
		last = s.T
		h.samples = append(h.samples, s)
	}
	if dead := lines - len(h.samples); dead > 0 {
		h.dead = dead
		h.compactLocked()
	}
	return h
}

// record stores s unless it adds nothing: no change since the last sample and
// the heartbeat is not due, or the last sample is less than the minimum
// spacing old (a change within that is picked up by a later call).
func (h *poolHistory) record(s protocol.PoolSample) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	if n := len(h.samples); n > 0 {
		last := h.samples[n-1]
		gap := time.Duration(s.T-last.T) * time.Second
		same := last.RemainingPct == s.RemainingPct && last.FiveHourRemainingPct == s.FiveHourRemainingPct &&
			last.EffectiveRemainingPct == s.EffectiveRemainingPct &&
			maps.Equal(last.FiveHourRemaining, s.FiveHourRemaining) && maps.Equal(last.FiveHourResetsAt, s.FiveHourResetsAt)
		if gap < poolMinSpacing || (same && gap < poolHeartbeat) {
			return false
		}
	}
	h.samples = append(h.samples, s)
	cutoff := s.T - int64(poolKeep.Seconds())
	drop := sort.Search(len(h.samples), func(i int) bool { return h.samples[i].T >= cutoff })
	if drop > 0 {
		h.samples = append([]protocol.PoolSample(nil), h.samples[drop:]...)
		h.dead += drop
	}
	if h.path == "" {
		return true
	}
	if h.dead >= poolCompactAt {
		h.compactLocked()
		return true
	}
	if err := os.MkdirAll(filepath.Dir(h.path), 0o700); err != nil {
		log.Printf("pool history: %v", err)
		return true
	}
	f, err := os.OpenFile(h.path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		log.Printf("pool history: %v", err)
		return true
	}
	defer f.Close()
	line, _ := json.Marshal(s)
	if _, err := f.Write(append(line, '\n')); err != nil {
		log.Printf("pool history: %v", err)
	}
	return true
}

// compactLocked rewrites the file atomically from memory.
func (h *poolHistory) compactLocked() {
	if h.path == "" {
		return
	}
	if err := os.MkdirAll(filepath.Dir(h.path), 0o700); err != nil {
		log.Printf("pool history: %v", err)
		return
	}
	var buf []byte
	for _, s := range h.samples {
		line, _ := json.Marshal(s)
		buf = append(append(buf, line...), '\n')
	}
	tmp := h.path + ".tmp"
	if err := os.WriteFile(tmp, buf, 0o600); err != nil {
		log.Printf("pool history: %v", err)
		return
	}
	if err := os.Rename(tmp, h.path); err != nil {
		log.Printf("pool history: %v", err)
		return
	}
	h.dead = 0
}

// since returns a copy of the samples at or after t, oldest first.
func (h *poolHistory) since(t time.Time) []protocol.PoolSample {
	h.mu.Lock()
	defer h.mu.Unlock()
	i := sort.Search(len(h.samples), func(i int) bool { return h.samples[i].T >= t.Unix() })
	return append([]protocol.PoolSample{}, h.samples[i:]...)
}

// ---------- monitor glue ----------

// poolSnaps is the family's current snapshots: every hub account for Claude
// (just "claude" without a hub), Codex's single one.
func (m *monitor) poolSnaps(family string) []wireSnapshot {
	if family == "codex" {
		s, _ := m.latestFor("codex")
		return []wireSnapshot{s}
	}
	names := []string{"claude"}
	if m.hub != nil {
		names = claudeProviders
	}
	out := make([]wireSnapshot, 0, len(names))
	for _, p := range names {
		s, _ := m.latestFor(p)
		out = append(out, s)
	}
	return out
}

// recordPools samples every family into its history. The poll loop calls it
// after each round; /pool calls it for the family it is answering, so a
// request never serves a point newer than the history it plots.
func (m *monitor) recordPools() {
	for _, f := range poolFamilies {
		m.recordPool(f)
	}
}

func (m *monitor) recordPool(family string) {
	ms := poolMembers(m.poolSnaps(family))
	if len(ms) == 0 {
		return
	}
	m.pools[family].record(sampleOf(ms, time.Now()))
}

// poolHandler serves GET /pool?family=claude|codex[&days=1..14].
func poolHandler(token string, m *monitor) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !authorized(r, token, m) {
			unauthorized(w)
			return
		}
		family := r.URL.Query().Get("family")
		if family == "" {
			family = "claude"
		}
		if family != "claude" && family != "codex" {
			http.Error(w, "unknown family", http.StatusBadRequest)
			return
		}
		days := poolDefaultDays
		if d := r.URL.Query().Get("days"); d != "" {
			n, err := strconv.Atoi(d)
			if err != nil || n < 1 || n > poolMaxDays {
				http.Error(w, "days must be 1 to 14", http.StatusBadRequest)
				return
			}
			days = n
		}
		// Asking is refreshing, as for /usage.
		if family == "codex" {
			m.pollCodex()
		} else {
			m.freshen()
			m.pollHub()
		}
		m.recordPool(family)

		now := time.Now()
		snaps := m.poolSnaps(family)
		hist := m.pools[family].since(now.Add(-time.Duration(days) * 24 * time.Hour))
		pool := buildPool(family, snaps, hist, now)
		etag := poolETag(pool, snaps, days, now)
		w.Header().Set("ETag", etag)
		w.Header().Set("Cache-Control", "no-cache")
		if matchesETag(r.Header.Get("If-None-Match"), etag) {
			w.WriteHeader(http.StatusNotModified)
			return
		}
		writeJSON(w, pool)
	}
}
