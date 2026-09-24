package main

import (
	"testing"
	"time"
)

// newTestMonitor keeps state in a temp dir so tests never touch the real one.
func newTestMonitor(t *testing.T) *monitor {
	t.Helper()
	t.Setenv("XDG_STATE_HOME", t.TempDir())
	return newMonitor([]int{80, 95}, 5)
}

func sample(util int, resetsAt time.Time) snapshot {
	return snapshot{fiveHour: &limitWindow{Utilization: util, ResetsAt: resetsAt.UTC().Format(time.RFC3339)}}
}

func evaluate(m *monitor, s snapshot) []event {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.evaluateLocked(s, time.Now())
}

func TestCrossingFiresExactlyOnce(t *testing.T) {
	m := newTestMonitor(t)
	reset := time.Now().Add(2 * time.Hour)

	if got := evaluate(m, sample(79, reset)); len(got) != 0 {
		t.Fatalf("below threshold should be silent, got %d events", len(got))
	}
	got := evaluate(m, sample(81, reset))
	if len(got) != 1 || got[0].Kind != "threshold_crossed" || got[0].Threshold != 80 {
		t.Fatalf("want one 80%% crossing, got %+v", got)
	}
	if got[0].Severity != "warn" {
		t.Errorf("80%% should be warn, got %q", got[0].Severity)
	}
	for i := 0; i < 5; i++ {
		if extra := evaluate(m, sample(82, reset)); len(extra) != 0 {
			t.Fatalf("staying above threshold refired: %+v", extra)
		}
	}
	if crit := evaluate(m, sample(96, reset)); len(crit) != 1 || crit[0].Threshold != 95 || crit[0].Severity != "critical" {
		t.Fatalf("want one critical 95%% crossing, got %+v", crit)
	}
}

func TestHysteresisRearms(t *testing.T) {
	m := newTestMonitor(t)
	reset := time.Now().Add(2 * time.Hour)
	evaluate(m, sample(81, reset))

	// 77 is within the 5-point band, so the threshold stays disarmed.
	evaluate(m, sample(77, reset))
	if got := evaluate(m, sample(81, reset)); len(got) != 0 {
		t.Fatalf("flapping inside the hysteresis band refired: %+v", got)
	}
	// 74 is far enough below to re-arm.
	evaluate(m, sample(74, reset))
	if got := evaluate(m, sample(81, reset)); len(got) != 1 {
		t.Fatalf("want a refire after dropping out of the band, got %+v", got)
	}
}

func TestWindowResetRearmsAndAnnounces(t *testing.T) {
	m := newTestMonitor(t)
	reset := time.Now().Add(2 * time.Hour)
	evaluate(m, sample(81, reset))

	next := reset.Add(5 * time.Hour)
	got := evaluate(m, sample(3, next))
	if len(got) != 1 || got[0].Kind != "window_reset" || got[0].Severity != "info" {
		t.Fatalf("want one window_reset, got %+v", got)
	}
	// A new window re-arms the threshold even though utilization never dipped
	// below the band within the old one.
	if again := evaluate(m, sample(81, next)); len(again) != 1 || again[0].Kind != "threshold_crossed" {
		t.Fatalf("new window should re-arm the threshold, got %+v", again)
	}
}

func TestStateSurvivesRestart(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_STATE_HOME", dir)
	reset := time.Now().Add(2 * time.Hour)

	m := newMonitor([]int{80, 95}, 5)
	if got := evaluate(m, sample(81, reset)); len(got) != 1 {
		t.Fatalf("setup: want one event, got %+v", got)
	}
	if err := m.st.save(); err != nil {
		t.Fatal(err)
	}

	restarted := newMonitor([]int{80, 95}, 5)
	if got := evaluate(restarted, sample(82, reset)); len(got) != 0 {
		t.Fatalf("restart replayed a crossing: %+v", got)
	}
	events, high := restarted.eventsSince("")
	if len(events) != 1 || high != events[0].ID {
		t.Fatalf("want the earlier event still readable, got %+v high=%q", events, high)
	}
	if got := evaluate(restarted, sample(96, reset)); len(got) != 1 || got[0].ID <= events[0].ID {
		t.Fatalf("ids must keep increasing across a restart, got %+v", got)
	}
}

func TestEventsSinceHighWater(t *testing.T) {
	m := newTestMonitor(t)
	reset := time.Now().Add(2 * time.Hour)
	evaluate(m, sample(81, reset))
	all, high := m.eventsSince("")
	if len(all) != 1 {
		t.Fatalf("want 1 event, got %d", len(all))
	}
	rest, high2 := m.eventsSince(high)
	if len(rest) != 0 || high2 != high {
		t.Fatalf("polling from the high-water mark should be empty, got %+v %q", rest, high2)
	}
	evaluate(m, sample(96, reset))
	rest, _ = m.eventsSince(high)
	if len(rest) != 1 || rest[0].Threshold != 95 {
		t.Fatalf("want only the new event, got %+v", rest)
	}
}

func TestEventRingIsBounded(t *testing.T) {
	m := newTestMonitor(t)
	for i := 0; i < maxEvents+50; i++ {
		m.st.appendEvent(event{Kind: "threshold_crossed"})
	}
	if len(m.st.Events) != maxEvents {
		t.Fatalf("ring should cap at %d, got %d", maxEvents, len(m.st.Events))
	}
	if m.st.Events[0].ID >= m.st.Events[len(m.st.Events)-1].ID {
		t.Fatal("ring kept the wrong end")
	}
}

// The ETag must ignore the fields that change on every poll but carry no news,
// otherwise the watch downloads the same numbers forever.
func TestETagIgnoresGeneratedAtAndCountdown(t *testing.T) {
	reset := time.Now().Add(2 * time.Hour)
	a := wireOf(sample(50, reset))
	time.Sleep(time.Millisecond)
	b := wireOf(sample(50, reset))
	b.GeneratedAt = time.Now().Add(time.Hour).UTC().Format(time.RFC3339)
	b.FiveHour.ResetsInSec -= 30
	if etagOf(a) != etagOf(b) {
		t.Fatal("etag changed without the numbers changing")
	}
	c := wireOf(sample(51, reset))
	if etagOf(a) == etagOf(c) {
		t.Fatal("etag did not change when utilization did")
	}
}

func TestMatchesETag(t *testing.T) {
	for _, tc := range []struct {
		header, etag string
		want         bool
	}{
		{`"abc"`, `"abc"`, true},
		{`W/"abc"`, `"abc"`, true},
		{`"zzz", "abc"`, `"abc"`, true},
		{`*`, `"abc"`, true},
		{`"zzz"`, `"abc"`, false},
		{``, `"abc"`, false},
	} {
		if got := matchesETag(tc.header, tc.etag); got != tc.want {
			t.Errorf("matchesETag(%q, %q) = %v", tc.header, tc.etag, got)
		}
	}
}

func TestPairingIsSingleUseAndExpires(t *testing.T) {
	m := newTestMonitor(t)
	code := m.newPairCode()
	if len(code) != 6 {
		t.Fatalf("want a 6-digit code, got %q", code)
	}
	d, ok := m.redeem(code, "watch")
	if !ok || d.Token == "" {
		t.Fatalf("first redemption should succeed, got %+v %v", d, ok)
	}
	if _, ok := m.redeem(code, "watch2"); ok {
		t.Fatal("codes must be single-use")
	}
	if !m.tokenOK("", d.Token) {
		t.Fatal("issued token should authenticate")
	}
	if m.tokenOK("", "nope") {
		t.Fatal("random token authenticated")
	}
	if !m.authRequired("") {
		t.Fatal("a paired device should close the bridge")
	}

	expired := m.newPairCode()
	m.mu.Lock()
	m.pending[expired] = time.Now().Add(-time.Second)
	m.mu.Unlock()
	if _, ok := m.redeem(expired, "late"); ok {
		t.Fatal("expired code accepted")
	}
}

func TestBadCodesBurnPendingCodes(t *testing.T) {
	m := newTestMonitor(t)
	good := m.newPairCode()
	for i := 0; i < maxPairFailures; i++ {
		m.redeem("0000000", "attacker") // 7 digits: can never be a real code
	}
	if _, ok := m.redeem(good, "watch"); ok {
		t.Fatal("a run of bad guesses should invalidate pending codes")
	}
}

func TestParseThresholds(t *testing.T) {
	got := parseThresholds("80, 95,nonsense,0,101,50")
	want := []int{80, 95, 50}
	if len(got) != len(want) {
		t.Fatalf("got %v want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("got %v want %v", got, want)
		}
	}
}

func TestResetJitterIsNotARollover(t *testing.T) {
	m := newTestMonitor(t)
	// Real values seen from Anthropic for one window, fetched minutes apart.
	jitter := []string{"2026-09-24T12:39:59.912Z", "2026-09-24T12:40:00.316843+00:00", "2026-09-24T12:39:59.5Z"}
	var all []event
	for _, r := range jitter {
		all = append(all, evaluate(m, snapshot{fiveHour: &limitWindow{Utilization: 96, ResetsAt: r}})...)
	}
	kinds := map[string]int{}
	for _, e := range all {
		kinds[e.Kind]++
	}
	if kinds["window_reset"] != 0 {
		t.Fatalf("second-level jitter in resets_at fired %d window_reset events", kinds["window_reset"])
	}
	if kinds["threshold_crossed"] != 2 { // 80 and 95, once each
		t.Fatalf("got %d threshold events, want 2 (80, 95) exactly once", kinds["threshold_crossed"])
	}
}
