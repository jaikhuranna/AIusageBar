package bridge

import (
	"aiusagebar/internal/protocol"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

var t0 = time.Date(2026, 11, 1, 12, 0, 0, 0, time.UTC)

func hoursAfter(h float64) time.Time { return t0.Add(time.Duration(h * float64(time.Hour))) }

// acct is a poolMember with r7 remaining and, when resetIn > 0, a weekly reset
// that many hours from t0 (period 7 days).
func acct(r7, resetIn float64) poolMember {
	m := poolMember{r5: r7, r7: r7, effective: r7, period: poolHorizon}
	if resetIn > 0 {
		m.resetAt = hoursAfter(resetIn)
	}
	return m
}

func approx(a, b float64) bool { return a-b < 1e-6 && b-a < 1e-6 }

func TestProjectPool(t *testing.T) {
	cases := []struct {
		name       string
		accts      []poolMember
		burn       float64
		firstEmpty float64 // hours after t0; -1 = null
		poolEmpty  float64
		resetAt    float64 // hours after t0 of next_reset; -1 = null
		resetGain  float64
		lastValue  float64 // projected value at t0+7d
		extra      int     // projection points beyond the 169 hourly ones
	}{
		{name: "no burn, no reset is flat", accts: []poolMember{acct(60, 0)}, firstEmpty: -1, poolEmpty: -1, resetAt: -1, lastValue: 60},
		{name: "no burn still steps up at reset", accts: []poolMember{acct(60, 50.5)}, firstEmpty: -1, poolEmpty: -1,
			resetAt: 50.5, resetGain: 40, lastValue: 100, extra: 2},
		{name: "burn split evenly: B empties at 4h, A at 7h", accts: []poolMember{acct(50, 0), acct(20, 0)}, burn: 10,
			firstEmpty: 4, poolEmpty: 7, resetAt: -1, lastValue: 0},
		{name: "clamped at 0 and stays there", accts: []poolMember{acct(5, 0)}, burn: 100,
			firstEmpty: 0.05, poolEmpty: 0.05, resetAt: -1, lastValue: 0},
		{name: "step-up before empty restarts the drain", accts: []poolMember{acct(30, 2.5)}, burn: 10,
			firstEmpty: 12.5, poolEmpty: 12.5, resetAt: 2.5, resetGain: 95, lastValue: 0, extra: 2},
		{name: "reset delays pool empty past the naive time", accts: []poolMember{acct(20, 1.5), acct(0, 0)}, burn: 10,
			// A reaches 5 at 1.5h, resets to 100, then empties at 11.5h; a naive pool_empty would be 2h.
			firstEmpty: 11.5, poolEmpty: 11.5, resetAt: 1.5, resetGain: 95, lastValue: 0, extra: 2},
		{name: "reset keeps the pool from emptying within 7d", accts: []poolMember{acct(100, 0), acct(100, 160)}, burn: 1,
			// 0.5/h each: A would empty at 200h (out of range); B has 20 left at its 160h reset.
			firstEmpty: -1, poolEmpty: -1, resetAt: 160, resetGain: 80, lastValue: 112, extra: 1},
		{name: "already empty pool is empty now", accts: []poolMember{acct(0, 0)}, burn: 5,
			firstEmpty: -1, poolEmpty: 0, resetAt: -1, lastValue: 0},
		{name: "reset exactly on the hour replaces that hour's point", accts: []poolMember{acct(40, 3)}, firstEmpty: -1, poolEmpty: -1,
			resetAt: 3, resetGain: 60, lastValue: 100, extra: 1},
		{name: "two accounts resetting together add up", accts: []poolMember{acct(10, 5.5), acct(70, 5.5)}, firstEmpty: -1, poolEmpty: -1,
			resetAt: 5.5, resetGain: 120, lastValue: 200, extra: 2},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			p := projectPool(t0, c.accts, c.burn)
			if got, want := len(p.points), 169+c.extra; got != want {
				t.Errorf("points = %d, want %d", got, want)
			}
			if last := p.points[len(p.points)-1]; last.T != hoursAfter(168).Unix() || !approx(last.RemainingPct, c.lastValue) {
				t.Errorf("last point = %+v, want t=%d value %v", last, hoursAfter(168).Unix(), c.lastValue)
			}
			checkTime(t, "first_empty", p.firstEmpty, c.firstEmpty)
			checkTime(t, "pool_empty", p.poolEmpty, c.poolEmpty)
			if c.resetAt < 0 {
				if p.nextReset != nil {
					t.Errorf("next_reset = %+v, want nil", p.nextReset)
				}
			} else if p.nextReset == nil {
				t.Errorf("next_reset nil, want %vh +%v", c.resetAt, c.resetGain)
			} else {
				if want := hoursAfter(c.resetAt).Format(time.RFC3339); p.nextReset.At != want {
					t.Errorf("next_reset.at = %s, want %s", p.nextReset.At, want)
				}
				if !approx(p.nextReset.GainPct, c.resetGain) {
					t.Errorf("next_reset.gain = %v, want %v", p.nextReset.GainPct, c.resetGain)
				}
			}
			// Invariants: time never goes backwards; a reset is an after-point
			// sharing t with the point before it, and never lowers the value.
			for i, pt := range p.points {
				if pt.RemainingPct < 0 {
					t.Errorf("point %d below 0: %+v", i, pt)
				}
				if i == 0 {
					continue
				}
				prev := p.points[i-1]
				if pt.T < prev.T {
					t.Errorf("point %d goes back in time: %+v after %+v", i, pt, prev)
				}
				if pt.Reset && (pt.T != prev.T || pt.RemainingPct < prev.RemainingPct) {
					t.Errorf("reset point %d malformed: %+v after %+v", i, pt, prev)
				}
			}
		})
	}
}

func checkTime(t *testing.T, name string, got time.Time, wantHours float64) {
	t.Helper()
	if wantHours < 0 {
		if !got.IsZero() {
			t.Errorf("%s = %v, want null", name, got)
		}
		return
	}
	if want := hoursAfter(wantHours); got.Sub(want) > time.Second || want.Sub(got) > time.Second {
		t.Errorf("%s = %v, want %v (+%vh)", name, got, want, wantHours)
	}
}

func TestProjectPoolRepeatsWeeklyAndSkipsStaleReset(t *testing.T) {
	// A 3-day window: resets at 12h, 84h and 156h within the 168h horizon.
	m := acct(50, 12)
	m.period = 72 * time.Hour
	p := projectPool(t0, []poolMember{m}, 0)
	resets := 0
	for _, pt := range p.points {
		if pt.Reset {
			resets++
		}
	}
	if resets != 3 {
		t.Errorf("resets = %d, want 3", resets)
	}

	// A resets_at that is already past (the sample is a few minutes old) means
	// the next reset is one period on, not an instant step-up.
	stale := acct(50, 0)
	stale.resetAt = t0.Add(-10 * time.Minute)
	for _, period := range []time.Duration{poolHorizon, 24 * time.Hour} {
		stale.period = period
		p = projectPool(t0, []poolMember{stale}, 0)
		want := t0.Add(-10*time.Minute + period).Format(time.RFC3339)
		if p.nextReset == nil || p.nextReset.At != want || p.nextReset.GainPct != 50 {
			t.Errorf("period %v: next_reset = %+v, want %s", period, p.nextReset, want)
		}
	}
}

func TestPoolBurn(t *testing.T) {
	s := func(minsAgo int, rem float64) protocol.PoolSample {
		return protocol.PoolSample{T: t0.Add(-time.Duration(minsAgo) * time.Minute).Unix(), RemainingPct: rem}
	}
	cases := []struct {
		name string
		hist []protocol.PoolSample
		cur  float64
		want float64 // -1 = nil
	}{
		{"no history", nil, 100, -1},
		{"a sample seconds old is too little", []protocol.PoolSample{{T: t0.Unix() - 20, RemainingPct: 100}}, 99, -1},
		{"steady drain over 2h", []protocol.PoolSample{s(120, 100), s(60, 95)}, 90, 5},
		{"unchanged for hours is 0, not unknown", []protocol.PoolSample{s(500, 80)}, 80, 0},
		{"baseline from before the window counts", []protocol.PoolSample{s(400, 100), s(30, 94)}, 91, 3}, // 100->94 at -30m is 6, +3 after: 9 over 3h
		{"reset step-up does not cancel the drain", []protocol.PoolSample{s(150, 150), s(100, 140), s(90, 240), s(30, 230)}, 220, 12},
		{"never negative", []protocol.PoolSample{s(120, 50), s(60, 80)}, 100, 0},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := poolBurn(c.hist, t0, c.cur)
			if c.want < 0 {
				if got != nil {
					t.Errorf("burn = %v, want nil", *got)
				}
				return
			}
			if got == nil || !approx(*got, round(c.want, 2)) {
				t.Errorf("burn = %+v, want %v", got, c.want)
			}
		})
	}
}

func snapOf(provider, account string, util5, util7 int, resetIn time.Duration) wireSnapshot {
	reset := t0.Add(resetIn).Format(time.RFC3339)
	return wireSnapshot{
		Provider: provider, Account: account,
		FiveHour: &wireWindow{Utilization: util5, Remaining: 100 - util5, ResetsAt: reset},
		SevenDay: &wireWindow{Utilization: util7, Remaining: 100 - util7, ResetsAt: reset},
	}
}

func TestBuildPoolTotals(t *testing.T) {
	snaps := []wireSnapshot{
		snapOf("claude", "a@x.com", 40, 20, 24*time.Hour),                                  // 5h 60, 7d 80, effective 60
		snapOf("claude2", "b@x.com", 10, 70, 48*time.Hour),                                 // 5h 90, 7d 30, effective 30
		{Provider: "claude3", SourceError: "No Claude account 3 is signed in to the hub."}, // contributes nothing
	}
	p := buildPool("claude", snaps, nil, t0)
	if p.Accounts != 2 || p.CapacityPct != 200 || p.RemainingPct != 110 || p.FiveHourRemainingPct != 150 || p.EffectiveRemainingPct != 90 {
		t.Errorf("totals = %+v", p)
	}
	if len(p.Members) != 2 || p.Members[1].Provider != "claude2" || p.Members[1].Account != "b@x.com" {
		t.Errorf("members = %+v", p.Members)
	}
	if p.BurnPctPerHour != nil || p.SourceError != "" {
		t.Errorf("burn %v, source_error %q", p.BurnPctPerHour, p.SourceError)
	}
	if p.NextReset == nil || p.NextReset.At != t0.Add(24*time.Hour).Format(time.RFC3339) || p.NextReset.GainPct != 20 {
		t.Errorf("next_reset = %+v", p.NextReset)
	}
}

func TestBuildPoolClampsAndFillsMissingWindows(t *testing.T) {
	over := snapOf("claude", "a@x.com", 130, 100, time.Hour) // upstream says over 100%
	only5 := wireSnapshot{Provider: "claude2", FiveHour: &wireWindow{Utilization: 25, ResetsAt: ""}}
	p := buildPool("claude", []wireSnapshot{over, only5}, nil, t0)
	// over: 0 / 0 / 0. only5: its one window stands in for the other.
	if p.RemainingPct != 75 || p.FiveHourRemainingPct != 75 || p.EffectiveRemainingPct != 75 {
		t.Errorf("totals = %+v", p)
	}
}

func TestBuildPoolBurnFeedsProjection(t *testing.T) {
	snaps := []wireSnapshot{snapOf("claude", "a@x.com", 0, 50, 100*time.Hour)}
	hist := []protocol.PoolSample{
		{T: t0.Add(-2 * time.Hour).Unix(), RemainingPct: 70},
		{T: t0.Add(-1 * time.Hour).Unix(), RemainingPct: 60},
	}
	p := buildPool("claude", snaps, hist, t0)
	// 70 -> 50 over 2h = 10/h; 50 left: empty in 5h.
	if p.BurnPctPerHour == nil || *p.BurnPctPerHour != 10 {
		t.Fatalf("burn = %v", p.BurnPctPerHour)
	}
	if p.FirstEmptyAt == nil || *p.FirstEmptyAt != hoursAfter(5).Format(time.RFC3339) || *p.PoolEmptyAt != *p.FirstEmptyAt {
		t.Errorf("first_empty %v, pool_empty %v", p.FirstEmptyAt, p.PoolEmptyAt)
	}
}

func TestSampleOfRecordsEachAccountsSession(t *testing.T) {
	snaps := []wireSnapshot{
		snapOf("claude", "a@x.com", 40, 20, 2*time.Hour),
		snapOf("claude2", "b@x.com", 10, 70, 3*time.Hour),
		// Only a weekly window: it stands in for the pooled 5h sum but has no session of its own.
		{Provider: "claude3", SevenDay: &wireWindow{Utilization: 50, Remaining: 50}},
	}
	s := sampleOf(poolMembers(snaps), t0)
	if s.FiveHourRemainingPct != 200 {
		t.Errorf("pooled 5h = %v, want 200", s.FiveHourRemainingPct)
	}
	if want := map[string]float64{"claude": 60, "claude2": 90}; !reflect.DeepEqual(s.FiveHourRemaining, want) {
		t.Errorf("five_hour_remaining = %v, want %v", s.FiveHourRemaining, want)
	}
	if want := map[string]int64{"claude": t0.Add(2 * time.Hour).Unix(), "claude2": t0.Add(3 * time.Hour).Unix()}; !reflect.DeepEqual(s.FiveHourResetsAt, want) {
		t.Errorf("five_hour_resets_at = %v, want %v", s.FiveHourResetsAt, want)
	}
	b, _ := json.Marshal(s)
	if !strings.Contains(string(b), `"five_hour_remaining":{"claude":60,"claude2":90}`) {
		t.Errorf("JSON = %s", b)
	}
	// An older sample without the maps still reads, and writes without them.
	var old protocol.PoolSample
	if err := json.Unmarshal([]byte(`{"t":1,"remaining_pct":1,"five_hour_remaining_pct":2,"effective_remaining_pct":1}`), &old); err != nil || old.FiveHourRemaining != nil {
		t.Errorf("old sample = %+v, %v", old, err)
	}
	if b, _ := json.Marshal(old); strings.Contains(string(b), `"five_hour_remaining"`) || strings.Contains(string(b), `"five_hour_resets_at"`) {
		t.Errorf("old sample writes the maps: %s", b)
	}
}

func TestPoolHistoryRecordsAccountChangesTheSumHides(t *testing.T) {
	h := loadPoolHistory("", t0)
	at := func(d time.Duration, a, b float64, reset int64) protocol.PoolSample {
		s := sampleAt(t0.Add(d), a+b)
		s.FiveHourRemaining = map[string]float64{"claude": a, "claude2": b}
		s.FiveHourResetsAt = map[string]int64{"claude": reset}
		return s
	}
	if !h.record(at(0, 50, 50, 1)) {
		t.Fatal("first sample rejected")
	}
	// Same pooled sum, different accounts: still a change.
	if !h.record(at(10*time.Minute, 40, 60, 1)) {
		t.Error("an account change with the same sum was dropped")
	}
	// Same numbers, a new window: a change too.
	if !h.record(at(20*time.Minute, 40, 60, 2)) {
		t.Error("a new reset time was dropped")
	}
	if h.record(at(30*time.Minute, 40, 60, 2)) {
		t.Error("an unchanged sample was recorded before the heartbeat")
	}
}

func TestBuildPoolNoAccounts(t *testing.T) {
	p := buildPool("claude", []wireSnapshot{{Provider: "claude", SourceError: "No Claude account 1 is signed in to the hub."}}, nil, t0)
	if p.Accounts != 0 || p.CapacityPct != 0 || p.SourceError != "No Claude account 1 is signed in to the hub." {
		t.Errorf("pool = %+v", p)
	}
	b, _ := json.Marshal(p)
	for _, want := range []string{`"members":[]`, `"history":[]`, `"projection":[]`, `"burn_pct_per_hour":null`, `"next_reset":null`, `"first_empty_at":null`, `"pool_empty_at":null`} {
		if !strings.Contains(string(b), want) {
			t.Errorf("JSON lacks %s: %s", want, b)
		}
	}
}

// ---------- history ----------

func writeLines(t *testing.T, path string, lines ...string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(strings.Join(lines, "\n")+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
}

func sampleAt(at time.Time, rem float64) protocol.PoolSample {
	return protocol.PoolSample{T: at.Unix(), RemainingPct: rem, FiveHourRemainingPct: rem, EffectiveRemainingPct: rem}
}

func TestPoolHistoryPersistsAndReloads(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state", "pool-history-claude.jsonl")
	h := loadPoolHistory(path, t0)
	for i, rem := range []float64{100, 90, 80} {
		if !h.record(sampleAt(t0.Add(time.Duration(i)*10*time.Minute), rem)) {
			t.Fatalf("sample %d not recorded", i)
		}
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Errorf("mode = %v, want 0600", info.Mode().Perm())
	}
	again := loadPoolHistory(path, t0.Add(time.Hour))
	got := again.since(time.Time{})
	if len(got) != 3 || got[0].RemainingPct != 100 || !reflect.DeepEqual(got[2], sampleAt(t0.Add(20*time.Minute), 80)) {
		t.Errorf("reloaded = %+v", got)
	}
}

func TestPoolHistoryTrimsOldAndSkipsBadLines(t *testing.T) {
	path := filepath.Join(t.TempDir(), "h.jsonl")
	old := func(days float64, rem float64) string {
		b, _ := json.Marshal(sampleAt(t0.Add(-time.Duration(days*24)*time.Hour), rem))
		return string(b)
	}
	writeLines(t, path, old(20, 1), old(15, 2), "not json", old(13, 3), old(12, 4), old(13.5, 99) /* out of order */, old(1, 5))
	h := loadPoolHistory(path, t0)
	got := h.since(time.Time{})
	var rems []float64
	for _, s := range got {
		rems = append(rems, s.RemainingPct)
	}
	if fmt.Sprint(rems) != "[3 4 5]" {
		t.Errorf("kept %v, want [3 4 5]", rems)
	}
	// Loading compacted the file: reading it again drops nothing more.
	data, _ := os.ReadFile(path)
	if n := strings.Count(string(data), "\n"); n != 3 {
		t.Errorf("file has %d lines after load, want 3:\n%s", n, data)
	}
	if info, _ := os.Stat(path); info.Mode().Perm() != 0o600 {
		t.Errorf("compacted mode = %v", info.Mode().Perm())
	}
}

func TestPoolHistoryDownsamples(t *testing.T) {
	h := loadPoolHistory("", t0)
	steps := []struct {
		at   time.Duration
		rem  float64
		want bool
	}{
		{0, 100, true},
		{time.Minute, 99, false}, // changed, but under 5 min since the last sample
		{4*time.Minute + 59*time.Second, 98, false},
		{5 * time.Minute, 98, true},   // change, spacing satisfied: the dropped changes are not lost
		{20 * time.Minute, 98, false}, // no change, no heartbeat yet
		{64 * time.Minute, 98, false}, // 59 min since the last sample: no heartbeat yet
		{65 * time.Minute, 98, true},  // heartbeat: an hour since the last sample
		{66 * time.Minute, 97, false}, // spacing again
		{70 * time.Minute, 97, true},
	}
	for _, s := range steps {
		if got := h.record(sampleAt(t0.Add(s.at), s.rem)); got != s.want {
			t.Errorf("record at +%v (%v) = %v, want %v", s.at, s.rem, got, s.want)
		}
	}
	if n := len(h.since(time.Time{})); n != 4 {
		t.Errorf("kept %d samples, want 4", n)
	}
}

func TestPoolHistoryTrimsWhileRunningAndCompacts(t *testing.T) {
	path := filepath.Join(t.TempDir(), "h.jsonl")
	h := loadPoolHistory(path, t0)
	// poolCompactAt+10 samples spread over more than the retention period.
	n := poolCompactAt + 10
	step := (poolKeep + 24*time.Hour) / time.Duration(n)
	var last time.Time
	for i := 0; i < n; i++ {
		last = t0.Add(time.Duration(i) * step)
		if !h.record(sampleAt(last, float64(i))) {
			t.Fatalf("sample %d rejected", i)
		}
	}
	kept := h.since(time.Time{})
	if len(kept) >= n || kept[0].T < last.Add(-poolKeep).Unix() {
		t.Errorf("kept %d of %d, oldest %d vs cutoff %d", len(kept), n, kept[0].T, last.Add(-poolKeep).Unix())
	}
	// The file stays bounded: compaction ran once the dead lines piled up.
	data, _ := os.ReadFile(path)
	if lines := strings.Count(string(data), "\n"); lines > len(kept)+poolCompactAt {
		t.Errorf("file has %d lines for %d samples", lines, len(kept))
	}
	if got := loadPoolHistory(path, last).since(time.Time{}); len(got) != len(kept) {
		t.Errorf("reload kept %d, want %d", len(got), len(kept))
	}
}

// ---------- /pool ----------

func getPool(t *testing.T, mux http.Handler, query string, header ...string) (*httptest.ResponseRecorder, protocol.Pool) {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/pool"+query, nil)
	for i := 0; i+1 < len(header); i += 2 {
		req.Header.Set(header[i], header[i+1])
	}
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	var p protocol.Pool
	if rec.Code == http.StatusOK {
		if err := json.Unmarshal(rec.Body.Bytes(), &p); err != nil {
			t.Fatalf("bad JSON: %v\n%s", err, rec.Body)
		}
	}
	return rec, p
}

func TestPoolEndpointClaudeTwoAccounts(t *testing.T) {
	reset := time.Now().Add(30 * time.Hour).UTC().Format(time.RFC3339)
	f := &fakeHub{
		files: []hubAuthFile{
			{ID: "1", AuthIndex: "a", Provider: "claude", Email: "a@x.com"},
			{ID: "2", AuthIndex: "b", Provider: "claude", Email: "b@x.com"},
		},
		usage: map[string]string{"a": usageBody(40, 20, reset), "b": usageBody(10, 70, reset)},
	}
	m := newTestMonitor(t)
	m.hub = f.start(t)
	m.snap = wireOf(m.hub.slot(0)) // what poll() stores, minus the transcript scan
	m.pollHub()
	mux := newMux("", m)

	rec, p := getPool(t, mux, "?family=claude")
	if rec.Code != 200 {
		t.Fatalf("status %d: %s", rec.Code, rec.Body)
	}
	if p.Family != "claude" || p.Accounts != 2 || p.CapacityPct != 200 || p.RemainingPct != 110 ||
		p.FiveHourRemainingPct != 150 || p.EffectiveRemainingPct != 90 {
		t.Errorf("pool = %+v", p)
	}
	if len(p.Members) != 2 || p.Members[0].Provider != "claude" || p.Members[1].Provider != "claude2" || p.Members[1].Account != "b@x.com" {
		t.Errorf("members = %+v", p.Members)
	}
	if len(p.History) != 1 || p.History[0].RemainingPct != 110 {
		t.Errorf("history = %+v", p.History)
	}
	if len(p.Projection) != 171 || p.NextReset == nil || p.NextReset.GainPct != 90 {
		t.Errorf("projection %d points, next_reset %+v", len(p.Projection), p.NextReset)
	}
	if p.BurnPctPerHour != nil || p.SourceError != "" {
		t.Errorf("burn %v, source_error %q", p.BurnPctPerHour, p.SourceError)
	}

	// The default family is claude, and the answer is cacheable.
	rec2, p2 := getPool(t, mux, "")
	if p2.Family != "claude" || p2.Accounts != 2 {
		t.Errorf("default family = %+v", p2)
	}
	etag := rec.Header().Get("ETag")
	if etag == "" || rec2.Header().Get("ETag") != etag {
		t.Errorf("etag %q vs %q", etag, rec2.Header().Get("ETag"))
	}
	if rec3, _ := getPool(t, mux, "?family=claude", "If-None-Match", etag); rec3.Code != http.StatusNotModified {
		t.Errorf("If-None-Match status = %d, want 304", rec3.Code)
	}
}

func TestPoolEndpointClaudeWithoutAccounts(t *testing.T) {
	f := &fakeHub{} // a hub with no Claude accounts signed in
	m := newTestMonitor(t)
	m.hub = f.start(t)
	m.snap = wireOf(m.hub.slot(0))
	m.pollHub()

	rec, p := getPool(t, newMux("", m), "?family=claude")
	if rec.Code != 200 {
		t.Fatalf("status %d: %s", rec.Code, rec.Body)
	}
	if p.Accounts != 0 || p.CapacityPct != 0 || p.SourceError == "" || len(p.History) != 0 || len(p.Projection) != 0 || len(p.Members) != 0 {
		t.Errorf("pool = %+v", p)
	}
	if !strings.Contains(rec.Body.String(), `"members": []`) {
		t.Errorf("members should be an empty array: %s", rec.Body)
	}
}

func TestPoolEndpointCodex(t *testing.T) {
	m := newTestMonitor(t)
	reset := time.Now().Add(5 * 24 * time.Hour).UTC()
	m.codexSnap = wireOf(snapshot{
		provider:       "codex",
		fiveHour:       &limitWindow{Utilization: 30, WindowMinutes: 300, ResetsAt: reset.Add(-119 * time.Hour).Format(time.RFC3339)},
		sevenDay:       &limitWindow{Utilization: 55, WindowMinutes: 10080, ResetsAt: reset.Format(time.RFC3339)},
		cacheFetchedAt: time.Now(),
	})
	mux := newMux("", m)

	rec, p := getPool(t, mux, "?family=codex")
	if rec.Code != 200 {
		t.Fatalf("status %d: %s", rec.Code, rec.Body)
	}
	if p.Family != "codex" || p.Accounts != 1 || p.CapacityPct != 100 || p.RemainingPct != 45 ||
		p.FiveHourRemainingPct != 70 || p.EffectiveRemainingPct != 45 {
		t.Errorf("pool = %+v", p)
	}
	if len(p.Members) != 1 || p.Members[0].Provider != "codex" || p.Members[0].SevenDay.WindowMinutes != 10080 {
		t.Errorf("members = %+v", p.Members)
	}
	if p.NextReset == nil || p.NextReset.GainPct != 55 {
		t.Errorf("next_reset = %+v", p.NextReset)
	}
	// Claude has nothing in this setup, and says so without failing.
	if rec, p := getPool(t, mux, "?family=claude"); rec.Code != 200 || p.Accounts != 0 || p.SourceError == "" {
		t.Errorf("claude: %d %+v", rec.Code, p)
	}
	// History is persisted for the next start.
	data, err := os.ReadFile(filepath.Join(os.Getenv("XDG_STATE_HOME"), "aiusagebar", "pool-history-codex.jsonl"))
	if err != nil || !strings.Contains(string(data), `"remaining_pct":45`) {
		t.Errorf("history file = %q, %v", data, err)
	}
	restarted := newMonitor([]int{80, 95}, 5)
	if got := restarted.pools["codex"].since(time.Time{}); len(got) != 1 {
		t.Errorf("history after restart = %+v", got)
	}
}

func TestPoolEndpointValidatesInput(t *testing.T) {
	m := newTestMonitor(t)
	mux := newMux("", m)
	for _, q := range []string{"?family=gemini", "?family=claude2", "?days=0", "?days=15", "?days=x"} {
		if rec, _ := getPool(t, mux, q); rec.Code != http.StatusBadRequest {
			t.Errorf("%s: status %d, want 400", q, rec.Code)
		}
	}
	if rec, _ := getPool(t, mux, "?family=codex&days=14"); rec.Code != 200 {
		t.Errorf("days=14: status %d", rec.Code)
	}
}

func TestPoolEndpointRequiresToken(t *testing.T) {
	m := newTestMonitor(t)
	mux := newMux("secret", m)
	if rec, _ := getPool(t, mux, "?family=codex"); rec.Code != http.StatusUnauthorized {
		t.Errorf("no token: status %d, want 401", rec.Code)
	}
	if rec, _ := getPool(t, mux, "?family=codex", "Authorization", "Bearer secret"); rec.Code != 200 {
		t.Errorf("token: status %d, want 200", rec.Code)
	}
}
