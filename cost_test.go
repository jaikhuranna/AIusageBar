package main

import (
	"math"
	"testing"
	"time"
)

func near(a, b float64) bool { return math.Abs(a-b) < 1e-9 }

// One reply split over three content blocks, each line repeating the usage,
// plus the same reply copied into another file by a resumed session.
func TestRepeatedReplyLinesCountOnce(t *testing.T) {
	start := []time.Time{time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC)}
	totals := make([]float64, 1)
	seen := map[string]bool{}
	line := `{"type":"assistant","timestamp":"2026-09-20T10:00:00Z","requestId":"req_1",` +
		`"message":{"id":"msg_1","model":"claude-opus-5","usage":{"output_tokens":1000000}}}`
	for i := 0; i < 4; i++ {
		applyLine(line, start, totals, seen)
	}
	if !near(totals[0], 25) {
		t.Fatalf("got $%.2f, want $25.00 (one reply, counted once)", totals[0])
	}

	other := `{"type":"assistant","timestamp":"2026-09-20T10:01:00Z","requestId":"req_2",` +
		`"message":{"id":"msg_2","model":"claude-opus-5","usage":{"output_tokens":1000000}}}`
	applyLine(other, start, totals, seen)
	if !near(totals[0], 50) {
		t.Fatalf("a different reply must still count: got $%.2f, want $50.00", totals[0])
	}
}

func TestModelSpecificCacheReadRates(t *testing.T) {
	u := &usage{CacheReadInputTokens: 1_000_000}
	for model, want := range map[string]float64{
		"claude-opus-5":         0.50,
		"claude-opus-5-5":       0.20, // not 0.1x of input
		"claude-opus-5-5[1m]":   0.20,
		"claude-sonnet-5":       0.20,
		"claude-fable-5-1":      0.25,
		"claude-haiku-4-5-2025": 0.10,
	} {
		if got := costOf(u, priceFor(model)); !near(got, want) {
			t.Errorf("%s: 1M cache reads = $%.4f, want $%.2f", model, got, want)
		}
	}
	if p := priceFor("claude-opus-5-5"); !near(p.out*1e6, 20) {
		t.Errorf("opus 5.5 output = $%.2f/M, want $20", p.out*1e6)
	}
}
