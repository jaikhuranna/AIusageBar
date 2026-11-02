package tray

import (
	"aiusagebar/internal/client"
	"aiusagebar/internal/protocol"
	"bytes"
	"image/png"
	"strings"
	"testing"
	"time"
)

var now = time.Date(2026, 10, 31, 12, 0, 0, 0, time.Local)

func snap(provider string, five, week int, extra ...func(*protocol.Snapshot)) entry {
	s := &protocol.Snapshot{Provider: provider, Schema: 1,
		FiveHour: &protocol.Window{WindowMinutes: 300, Utilization: five, ResetsAt: now.Add(2*time.Hour + 13*time.Minute).Format(time.RFC3339)},
		SevenDay: &protocol.Window{WindowMinutes: 10080, Utilization: week, ResetsAt: now.Add(76 * time.Hour).Format(time.RFC3339)},
	}
	for _, f := range extra {
		f(s)
	}
	return entry{snap: s, fetched: now, age: time.Minute}
}

func TestPanelLabel(t *testing.T) {
	both := map[string]entry{
		"claude":  snap("claude", 42, 30),
		"claude2": snap("claude2", 17, 91), // its week binds
		"codex":   snap("codex", 63, 84),
	}
	for _, tc := range []struct {
		pick  string
		two   bool
		label string
		bars  []int
	}{
		{client.Pool, true, "Claude 42 · 91%", []int{42, 91}},
		{"claude", true, "C1 5h 42% · 7d 30%", []int{42, 30}},
		{"claude2", true, "C2 5h 17% · 7d 91%", []int{17, 91}},
		{"codex", true, "Codex 5h 63% · 7d 84%", []int{63, 84}},
		// A server without a second account: the pool falls back to Claude.
		{client.Pool, false, "Claude 5h 42% · 7d 30%", []int{42, 30}},
	} {
		label, gauges := view{entries: both, two: tc.two, pick: tc.pick}.panel()
		if label != tc.label {
			t.Errorf("%s: label %q, want %q", tc.pick, label, tc.label)
		}
		for i, g := range gauges {
			if !g.known || g.percent != tc.bars[i] || g.stale {
				t.Errorf("%s: gauge %d = %+v, want %d", tc.pick, i, g, tc.bars[i])
			}
		}
	}
}

func TestPanelMissingAndStale(t *testing.T) {
	stale := snap("claude2", 5, 6, func(s *protocol.Snapshot) { s.SourceError = "hub unreachable" })
	v := view{entries: map[string]entry{"claude2": stale}, two: true, pick: client.Pool}
	label, gauges := v.panel()
	if label != "Claude -- · 6% · stale" {
		t.Errorf("label %q", label)
	}
	if gauges[0].known || !gauges[1].stale {
		t.Errorf("gauges %+v", gauges)
	}
	old := snap("codex", 1, 2)
	old.age = 2 * time.Hour
	if label, _ := (view{entries: map[string]entry{"codex": old}, pick: "codex"}).panel(); !strings.HasSuffix(label, " · stale") {
		t.Errorf("old data not marked stale: %q", label)
	}
	if label, _ := (view{entries: map[string]entry{}, pick: "codex"}).panel(); label != "Codex 5h -- · 7d --" {
		t.Errorf("empty label %q", label)
	}
	unpaired := view{entries: map[string]entry{"claude": {err: client.ErrUnpaired}}, pick: "codex"}
	if label, _ := unpaired.panel(); label != "AI usage · not paired" {
		t.Errorf("unpaired label %q", label)
	}
}

func TestSections(t *testing.T) {
	c1 := snap("claude", 42, 30, func(s *protocol.Snapshot) { s.Account = "first@example.com" })
	v := view{entries: map[string]entry{"claude": c1}, two: true, pick: client.Pool}
	if h := v.header("claude"); h != "Claude 1 — first@example.com" {
		t.Errorf("header %q", h)
	}
	if h := (view{entries: v.entries}).header("claude"); h != "Claude — first@example.com" {
		t.Errorf("single-account header %q", h)
	}
	five, week, issue := v.rows("claude", now)
	if five != "▰▰▰▰▱▱▱▱▱▱  42%  5h · resets in 2h 13m (2:13 PM)" {
		t.Errorf("five %q", five)
	}
	if week != "▰▰▰▱▱▱▱▱▱▱  30%  7d · resets in 3d 4h (Tue 4:00 PM)" {
		t.Errorf("week %q", week)
	}
	if issue != "" {
		t.Errorf("issue %q", issue)
	}
	c1.age = 3 * time.Hour
	v.entries["claude"] = c1
	if _, _, issue := v.rows("claude", now); issue != "⚠ Last updated 3h ago" {
		t.Errorf("stale issue %q", issue)
	}
	// No limits at all: one line says why, the week line is dropped.
	v.entries["claude2"] = entry{snap: &protocol.Snapshot{Provider: "claude2", SourceError: "Waiting for the first hub sample."}}
	if five, week, _ := v.rows("claude2", now); five != "⚠ Waiting for the first hub sample." || week != "" {
		t.Errorf("source error rows %q %q", five, week)
	}
	if five, _, _ := v.rows("codex", now); five != "Waiting for data…" {
		t.Errorf("waiting row %q", five)
	}
	v.entries["codex"] = entry{err: client.ErrUnavailable}
	if five, _, _ := v.rows("codex", now); five != "Server unavailable" {
		t.Errorf("error row %q", five)
	}
}

func TestSecondAccountVisibility(t *testing.T) {
	one := view{pick: client.Pool}
	if one.visible("claude2") || one.visible(client.Pool) || one.shown() != "claude" || one.name("claude") != "Claude" {
		t.Error("single-account server shows a second account")
	}
	if !(view{pick: "claude2"}).visible("claude2") {
		t.Error("an explicitly chosen claude2 must stay visible")
	}
	two := view{pick: client.Pool, two: true}
	if !two.visible("claude2") || !two.visible(client.Pool) || two.shown() != client.Pool {
		t.Error("pool hidden with two accounts")
	}
}

func TestCostAndStatus(t *testing.T) {
	c1 := snap("claude", 1, 2, func(s *protocol.Snapshot) {
		s.CostAvailable, s.CostToday, s.CostSession, s.CostWeek, s.Host = true, 3.2, 1.1, 41.1, "desk"
	})
	c2 := snap("claude2", 1, 2) // cost_available=false: the pooled figure lives on claude
	v := view{entries: map[string]entry{"claude": c1, "claude2": c2}, two: true}
	head, body, ok := v.cost()
	if !ok || !strings.Contains(head, "both accounts") || body != "$3.20 today · $1.10 session · $41.10 week" {
		t.Errorf("cost %q %q %v", head, body, ok)
	}
	if _, _, ok := (view{entries: map[string]entry{"claude2": c2}}).cost(); ok {
		t.Error("cost shown without claude's figure")
	}
	if s := v.status(now.Add(15 * time.Second)); s != "Connected to desk · checked 15s ago" {
		t.Errorf("status %q", s)
	}
	c1.err = client.ErrUnavailable
	v.entries["claude"] = c1
	if s := v.status(now); s != "Server unavailable · showing last known values" {
		t.Errorf("offline status %q", s)
	}
	// An old server rejects claude2; with one account that is not an outage.
	v = view{entries: map[string]entry{"claude": snap("claude", 1, 2), "claude2": {err: client.ErrUnavailable}}}
	if s := v.status(now); !strings.HasPrefix(s, "Connected") {
		t.Errorf("claude2 error leaked into status: %q", s)
	}
	if s := (view{entries: map[string]entry{}}).status(now); s != "Connecting to server…" {
		t.Errorf("initial status %q", s)
	}
}

func TestHelpers(t *testing.T) {
	for p, want := range map[int]string{0: "▱▱▱▱▱▱▱▱▱▱", 1: "▰▱▱▱▱▱▱▱▱▱", 55: "▰▰▰▰▰▰▱▱▱▱", 100: "▰▰▰▰▰▰▰▰▰▰", 140: "▰▰▰▰▰▰▰▰▰▰"} {
		if got := meter(p, true); got != want {
			t.Errorf("meter(%d) = %s", p, got)
		}
	}
	if got := resetPhrase(now.Add(-time.Minute), now); got != "now" {
		t.Errorf("past reset %q", got)
	}
	if got := resetPhrase(now.Add(20*time.Second), now); got != "in 1m (12:00 PM)" {
		t.Errorf("imminent reset %q", got)
	}
	if got := windowLabel(&protocol.Window{WindowMinutes: 1440}, 300); got != "1d" {
		t.Errorf("window label %q", got)
	}
	if got := ellipsize("a-very-long-address@subdomain.example.com", 12); got != "a-very-long…" {
		t.Errorf("ellipsize %q", got)
	}
	if got := ago(90 * time.Minute); got != "1h ago" {
		t.Errorf("ago %q", got)
	}
}

func TestIconBars(t *testing.T) {
	img, err := png.Decode(bytes.NewReader(icon([]gauge{{percent: 90, known: true}, {percent: 10, known: true, stale: true}})))
	if err != nil {
		t.Fatal(err)
	}
	if b := img.Bounds(); b.Dx() != iconSize || b.Dy() != iconSize {
		t.Fatalf("icon size %v", b)
	}
	near := func(x, y int, want [4]uint8) bool {
		r, g, b, a := img.At(x, y).RGBA()
		got := [4]uint32{r >> 8, g >> 8, b >> 8, a >> 8}
		for i := range got {
			if d := int(got[i]) - int(want[i]); d < -2 || d > 2 {
				return false
			}
		}
		return true
	}
	if !near(16, 50, [4]uint8{hot.R, hot.G, hot.B, 255}) {
		t.Error("90% bar is not red at its base")
	}
	if !near(46, 56, [4]uint8{faded.R, faded.G, faded.B, 255}) {
		t.Error("stale bar is not faded")
	}
	if _, _, _, a := img.At(46, 20).RGBA(); a == 0 || a == 0xffff {
		t.Error("empty part of a bar should be a translucent track")
	}
	if _, _, _, a := img.At(1, 1).RGBA(); a != 0 {
		t.Error("corner should be transparent")
	}
}
