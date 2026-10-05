package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestCodexQuotaMapping(t *testing.T) {
	body := []byte(`{"rateLimits":{"limitId":"code_review","primary":{"usedPercent":100,"windowDurationMins":300}},"rateLimitsByLimitId":{"codex":{"primary":{"usedPercent":99.9,"windowDurationMins":300,"resetsAt":1791000037},"secondary":{"usedPercent":42,"windowDurationMins":10080,"resetsAt":1791400037}},"code_review":{"primary":{"usedPercent":100,"windowDurationMins":10080}}}}`)
	s, err := parseCodexLimits(body)
	if err != nil {
		t.Fatal(err)
	}
	if s.fiveHour.Utilization != 99 || s.sevenDay.Utilization != 42 {
		t.Fatalf("wrong quota or false exhaustion: %+v", s)
	}
	if s.fiveHour.WindowMinutes != 300 || s.sevenDay.WindowMinutes != 10080 {
		t.Fatal("lost quota duration")
	}
	if got := s.fiveHour.resetTime().Unix(); got != 1791000037 {
		t.Fatalf("Codex reset rounded: %d", got)
	}
	w := wireOf(s)
	if w.Provider != "codex" || w.CostAvailable {
		t.Fatalf("wrong provider or invented costs: %+v", w)
	}
}

func TestCodexQuotaVariants(t *testing.T) {
	cases := []struct {
		name, body string
		minutes    int
		weekly     bool
		bad        bool
	}{
		{name: "legacy", body: `{"rateLimits":{"primary":{"usedPercent":25,"windowDurationMins":15,"resetsAt":1791000037}}}`, minutes: 15},
		{name: "weekly only", body: `{"rateLimits":{"limitId":"codex","primary":null,"secondary":{"usedPercent":40,"windowDurationMins":10080}}}`, minutes: 10080, weekly: true},
		{name: "null limits", body: `{"rateLimits":null}`, bad: true},
		{name: "empty windows", body: `{"rateLimits":{"limitId":"codex"}}`, bad: true},
		{name: "review only", body: `{"rateLimits":{"limitId":"code_review","primary":{"usedPercent":20,"windowDurationMins":300}}}`, bad: true},
		{name: "no codex bucket", body: `{"rateLimitsByLimitId":{"other":{"primary":{"usedPercent":20,"windowDurationMins":300}}}}`, bad: true},
		{name: "missing percentage", body: `{"rateLimits":{"primary":{"windowDurationMins":300}}}`, bad: true},
		{name: "negative duration", body: `{"rateLimits":{"primary":{"usedPercent":0,"windowDurationMins":-1}}}`, bad: true},
		{name: "bad percentage", body: `{"rateLimits":{"primary":{"usedPercent":101,"windowDurationMins":300}}}`, bad: true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s, err := parseCodexLimits([]byte(tc.body))
			if tc.bad {
				if err == nil {
					t.Fatal("invalid quota accepted")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			win := s.fiveHour
			if tc.weekly {
				win = s.sevenDay
				if s.fiveHour != nil {
					t.Fatal("invented primary quota")
				}
			} else if s.sevenDay != nil {
				t.Fatal("invented weekly quota")
			}
			if win == nil || win.WindowMinutes != tc.minutes {
				t.Fatalf("wrong window: %+v", win)
			}
		})
	}
}

func TestCodexWindowRolesDoNotDependOnDuration(t *testing.T) {
	for _, durations := range [][2]int{{60, 1440}, {300, 10080}, {10080, 43200}} {
		body := fmt.Sprintf(`{"rateLimits":{"limitId":"codex","primary":{"usedPercent":21,"windowDurationMins":%d},"secondary":{"usedPercent":84,"windowDurationMins":%d}}}`, durations[0], durations[1])
		s, err := parseCodexLimits([]byte(body))
		if err != nil {
			t.Fatalf("durations %v: %v", durations, err)
		}
		if s.fiveHour == nil || s.sevenDay == nil || s.fiveHour.WindowMinutes != durations[0] || s.sevenDay.WindowMinutes != durations[1] || s.fiveHour.Utilization != 21 || s.sevenDay.Utilization != 84 {
			t.Fatalf("lost primary/secondary roles for %v: %+v", durations, s)
		}
	}
	s, err := parseCodexLimits([]byte(`{"rateLimits":{"secondary":{"usedPercent":84,"windowDurationMins":1440}}}`))
	if err != nil || s.fiveHour != nil || s.sevenDay == nil || s.sevenDay.WindowMinutes != 1440 {
		t.Fatalf("secondary-only daily quota misplaced: %+v, %v", s, err)
	}
}

func TestCodexSourceCoalescesAndPreservesAgeOnFailure(t *testing.T) {
	var reads atomic.Int32
	reset := time.Now().Add(time.Hour)
	c := &codexSource{read: func(context.Context) (snapshot, error) {
		reads.Add(1)
		time.Sleep(5 * time.Millisecond)
		return sample(20, reset), nil
	}}
	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); c.sample() }()
	}
	wg.Wait()
	if reads.Load() != 1 {
		t.Fatalf("concurrent requests made %d reads", reads.Load())
	}
	original := c.sample()
	c.mu.Lock()
	c.lastAttempt = time.Time{}
	c.read = func(context.Context) (snapshot, error) { return snapshot{}, errors.New("offline") }
	c.mu.Unlock()
	failed := c.sample()
	if failed.sourceError == "" || !failed.cacheFetchedAt.Equal(original.cacheFetchedAt) || failed.fiveHour.Utilization != 20 {
		t.Fatalf("lost stale data or gave it a new age: %+v", failed)
	}
	if etagOf(wireOf(original)) == etagOf(wireOf(failed)) {
		t.Fatal("failure hidden by 304")
	}
	c.mu.Lock()
	c.lastAttempt = time.Time{}
	c.read = func(context.Context) (snapshot, error) { return sample(25, reset), nil }
	c.mu.Unlock()
	recovered := c.sample()
	if recovered.sourceError != "" || recovered.fiveHour.Utilization != 25 {
		t.Fatalf("did not recover: %+v", recovered)
	}
}

func TestCodexRPCHandshake(t *testing.T) {
	path := filepath.Join(t.TempDir(), "codex")
	script := `#!/usr/bin/env python3
import json, sys
assert sys.argv[1:] == ["app-server"]
init = json.loads(sys.stdin.readline())
assert init["method"] == "initialize"
print(json.dumps({"id":init["id"],"result":{}}), flush=True)
assert json.loads(sys.stdin.readline())["method"] == "initialized"
request = json.loads(sys.stdin.readline())
assert request["method"] == "account/rateLimits/read"
print(json.dumps({"method":"account/updated","params":{}}), flush=True)
print(json.dumps({"id":request["id"],"result":{"rateLimits":{"limitId":"codex","primary":{"usedPercent":23,"windowDurationMins":300,"resetsAt":1791000037}}}}), flush=True)
`
	if err := os.WriteFile(path, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	s, err := readCodex(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	if s.fiveHour == nil || s.fiveHour.Utilization != 23 {
		t.Fatalf("bad RPC result: %+v", s)
	}
}

func TestCodexRPCTimeout(t *testing.T) {
	path := filepath.Join(t.TempDir(), "codex")
	if err := os.WriteFile(path, []byte("#!/bin/sh\nsleep 5\n"), 0700); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	start := time.Now()
	if _, err := readCodex(ctx, path); err == nil {
		t.Fatal("hung CLI succeeded")
	}
	if time.Since(start) > 2*time.Second {
		t.Fatal("CLI timeout failed to bound the RPC")
	}
}

func TestProviderRoutesAndAuth(t *testing.T) {
	m := newTestMonitor(t)
	m.snap = wireOf(sample(10, time.Now().Add(time.Hour)))
	m.etag = etagOf(m.snap)
	var reads int
	m.codex = &codexSource{read: func(context.Context) (snapshot, error) { reads++; return sample(80, time.Now().Add(time.Hour)), nil }}
	mux := newMux("secret", m)
	request := func(path, token string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(http.MethodGet, path, nil)
		if token != "" {
			r.Header.Set("Authorization", "Bearer "+token)
		}
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, r)
		return w
	}
	if got := request("/usage?provider=codex", ""); got.Code != 401 || reads != 0 {
		t.Fatal("unauthorized request ran Codex")
	}
	for _, p := range []string{"claude", "codex"} {
		got := request("/usage?provider="+p, "secret")
		var w wireSnapshot
		if err := json.Unmarshal(got.Body.Bytes(), &w); err != nil {
			t.Fatal(err)
		}
		if got.Code != 200 || w.Provider != p {
			t.Fatalf("wrong provider: %s", got.Body)
		}
	}
	if got := request("/usage?provider=unknown", "secret"); got.Code != 400 {
		t.Fatal("unknown provider accepted")
	}
	if got := request("/usage", "secret"); !strings.Contains(got.Body.String(), `"provider": "claude"`) {
		t.Fatal("legacy clients lost Claude default")
	}
	claude, _ := m.latestFor("claude")
	codex, _ := m.latestFor("codex")
	if etagOf(claude) == etagOf(codex) {
		t.Fatal("ETag shared across providers")
	}
	// Provider-specific data still needs the opt-in public share flag.
	m.shareOrigins = []string{"*"}
	before := reads
	got := request("/share?provider=codex", "")
	if got.Code != 200 || reads != before || strings.Contains(got.Body.String(), "cost_") || strings.Contains(got.Body.String(), `"host"`) {
		t.Fatalf("public share leaked private fields or ran CLI: %s", got.Body)
	}
}

func TestProviderAlertsAreIndependent(t *testing.T) {
	m := newTestMonitor(t)
	s := sample(81, time.Now().Add(time.Hour))
	for _, p := range []string{"claude", "codex"} {
		got := m.evaluateProviderLocked(s, time.Now(), p)
		if len(got) != 1 || got[0].Provider != p {
			t.Fatalf("provider %s didn't fire independently: %+v", p, got)
		}
		if again := m.evaluateProviderLocked(s, time.Now(), p); len(again) != 0 {
			t.Fatal("crossing fired twice")
		}
	}
	// Old persisted events without a provider remain Claude history.
	m.st.appendEvent(event{Kind: "window_reset", DedupeKey: "legacy"})
	mux := newMux("", m)
	for _, p := range []string{"claude", "codex"} {
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, httptest.NewRequest("GET", "/events?provider="+p, nil))
		var page struct {
			Events []event `json:"events"`
			High   string  `json:"high_water"`
		}
		if err := json.Unmarshal(w.Body.Bytes(), &page); err != nil {
			t.Fatal(err)
		}
		want := 1
		if p == "claude" {
			want = 2
		}
		if len(page.Events) != want || page.High != eventID(3) {
			t.Fatalf("bad %s event page: %+v", p, page)
		}
		for _, e := range page.Events {
			if e.Provider != "" && e.Provider != p {
				t.Fatal("another provider's alert leaked")
			}
		}
	}
	if got := fmt.Sprint(m.st.Fired); !strings.Contains(got, "codex:five_hour:80") {
		t.Fatal("Codex alert state not namespaced")
	}
}

func TestProviderETagsTrackSourceAge(t *testing.T) {
	for _, provider := range []string{"claude", "codex"} {
		a := wireOf(sample(25, time.Now().Add(time.Hour)))
		a.Provider = provider
		a.CacheFetchedAt = "2026-10-03T12:00:00Z"
		b := a
		b.CacheFetchedAt = "2026-10-03T12:01:00Z"
		if etagOf(a) == etagOf(b) {
			t.Fatalf("%s fresh source age hidden behind 304", provider)
		}
	}
}
