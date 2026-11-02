package bridge

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

const testKey = "test-mgmt-key"

// fakeHub serves the two management routes the hub source uses. usage maps an
// auth_index to the body Anthropic would return; a missing entry answers 401.
type fakeHub struct {
	files []hubAuthFile
	usage map[string]string
	calls atomic.Int32
}

func (f *fakeHub) start(t *testing.T) *hubSource {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.calls.Add(1)
		if r.Header.Get("Authorization") != "Bearer "+testKey {
			http.Error(w, "no", http.StatusUnauthorized)
			return
		}
		switch r.URL.Path {
		case "/v0/management/auth-files":
			json.NewEncoder(w).Encode(map[string]any{"files": f.files})
		case "/v0/management/api-call":
			var req struct {
				AuthIndex string            `json:"auth_index"`
				Method    string            `json:"method"`
				URL       string            `json:"url"`
				Header    map[string]string `json:"header"`
			}
			json.NewDecoder(r.Body).Decode(&req)
			if req.Method != "GET" || req.URL != usageURL || req.Header["Authorization"] != "Bearer $TOKEN$" || req.Header["anthropic-beta"] != "oauth-2025-04-20" {
				t.Errorf("unexpected api-call: %+v", req)
			}
			body, ok := f.usage[req.AuthIndex]
			if !ok {
				json.NewEncoder(w).Encode(map[string]any{"status_code": 401, "body": "{}"})
				return
			}
			json.NewEncoder(w).Encode(map[string]any{"status_code": 200, "body": body})
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(srv.Close)
	keyFile := filepath.Join(t.TempDir(), "keys.env")
	if err := os.WriteFile(keyFile, []byte("# hub\nOTHER=x\nexport CPA_MGMT_KEY=\""+testKey+"\"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	h, err := newHubSource(srv.URL, keyFile)
	if err != nil {
		t.Fatal(err)
	}
	return h
}

func usageBody(five, week float64, reset string) string {
	return `{"five_hour":{"utilization":` + jsonNum(five) + `,"resets_at":"` + reset + `"},"seven_day":{"utilization":` + jsonNum(week) + `,"resets_at":"` + reset + `"}}`
}

func jsonNum(f float64) string { b, _ := json.Marshal(f); return string(b) }

func TestHubMapsAccountsSortedByEmail(t *testing.T) {
	f := &fakeHub{
		files: []hubAuthFile{
			{ID: "3", AuthIndex: "c", Provider: "codex", Email: "aaa@x.com"},
			{ID: "2", AuthIndex: "b", Provider: "claude", Email: "zed@x.com"},
			{ID: "4", AuthIndex: "d", Provider: "claude", Email: "off@x.com", Disabled: true},
			{ID: "1", AuthIndex: "a", Provider: "claude", Email: "Amy@x.com"},
		},
		usage: map[string]string{
			"a": usageBody(42.4, 10, "2026-11-01T12:00:00.3Z"),
			"b": `{"five_hour":{"utilization":7,"resets_at":null}}`,
		},
	}
	h := f.start(t)

	first, second := h.slot(0), h.slot(1)
	if first.provider != "claude" || first.account != "Amy@x.com" || first.sourceError != "" {
		t.Fatalf("first = %+v", first)
	}
	if first.fiveHour.Utilization != 42 || first.sevenDay.Utilization != 10 {
		t.Errorf("windows = %+v %+v", first.fiveHour, first.sevenDay)
	}
	if got := first.fiveHour.resetTime(); !got.Equal(time.Date(2026, 11, 1, 12, 0, 0, 0, time.UTC)) {
		t.Errorf("reset not rounded to the minute: %v", got)
	}
	if first.cacheFetchedAt.IsZero() {
		t.Error("cacheFetchedAt not set")
	}
	if second.provider != "claude2" || second.account != "zed@x.com" || second.sourceError != "" {
		t.Fatalf("second = %+v", second)
	}
	if second.sevenDay != nil || second.fiveHour.Utilization != 7 || second.fiveHour.ResetsAt != "" {
		t.Errorf("optional windows mishandled: %+v", second)
	}
}

func TestHubPollsAtMostEveryFiveMinutes(t *testing.T) {
	f := &fakeHub{
		files: []hubAuthFile{{ID: "1", AuthIndex: "a", Provider: "claude", Email: "a@x.com"}},
		usage: map[string]string{"a": usageBody(1, 2, "2026-11-01T12:00:00Z")},
	}
	h := f.start(t)
	h.slot(0)
	h.slot(1)
	h.slot(0)
	if n := f.calls.Load(); n != 2 { // auth-files + one api-call, once
		t.Fatalf("hub called %d times, want 2", n)
	}
	h.last = time.Now().Add(-hubMinAge - time.Second)
	h.slot(0)
	if n := f.calls.Load(); n != 4 {
		t.Fatalf("hub called %d times after the interval, want 4", n)
	}
}

func TestHubMissingSecondAccountIsASourceError(t *testing.T) {
	f := &fakeHub{
		files: []hubAuthFile{{ID: "1", AuthIndex: "a", Provider: "claude", Email: "a@x.com"}},
		usage: map[string]string{"a": usageBody(1, 2, "2026-11-01T12:00:00Z")},
	}
	h := f.start(t)
	if s := h.slot(0); s.sourceError != "" {
		t.Errorf("first account errored: %q", s.sourceError)
	}
	s := h.slot(1)
	if s.provider != "claude2" || s.sourceError == "" || s.fiveHour != nil || s.account != "" {
		t.Fatalf("second = %+v", s)
	}
}

func TestHubFailureKeepsOldWindowsAndFlagsTheError(t *testing.T) {
	f := &fakeHub{
		files: []hubAuthFile{{ID: "1", AuthIndex: "a", Provider: "claude", Email: "a@x.com"}},
		usage: map[string]string{"a": usageBody(30, 40, "2026-11-01T12:00:00Z")},
	}
	h := f.start(t)
	if h.slot(0).fiveHour == nil {
		t.Fatal("no first sample")
	}

	delete(f.usage, "a") // Anthropic now refuses
	h.last = time.Time{}
	s := h.slot(0)
	if s.fiveHour == nil || s.fiveHour.Utilization != 30 || !strings.Contains(s.sourceError, "HTTP 401") {
		t.Fatalf("after a refusal: %+v", s)
	}

	f.usage["a"] = usageBody(31, 41, "2026-11-01T12:00:00Z")
	h.last = time.Time{}
	if s := h.slot(0); s.sourceError != "" || s.fiveHour.Utilization != 31 {
		t.Fatalf("recovery: %+v", s)
	}
}

func TestHubUnreachableAndBadKey(t *testing.T) {
	f := &fakeHub{}
	h := f.start(t)
	if err := os.WriteFile(h.keyFile, []byte("CPA_MGMT_KEY=wrong\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	s := h.slot(0)
	if !strings.Contains(s.sourceError, "HTTP 401") || strings.Contains(s.sourceError, "wrong") {
		t.Errorf("bad key error = %q", s.sourceError)
	}

	h.url = "http://127.0.0.1:1"
	h.last = time.Time{}
	if s := h.slot(1); !strings.Contains(s.sourceError, "unreachable") {
		t.Errorf("unreachable error = %q", s.sourceError)
	}

	os.Remove(h.keyFile)
	h.last = time.Time{}
	if s := h.slot(0); !strings.Contains(s.sourceError, "key file") {
		t.Errorf("missing key error = %q", s.sourceError)
	}
}

func TestHubFlagsValidate(t *testing.T) {
	if _, err := newHubSource("http://127.0.0.1:8317", ""); err == nil {
		t.Error("accepted a hub URL without a key file")
	}
	for _, bad := range []string{"ftp://x", "http://", "http://u:p@h", "nope"} {
		if _, err := newHubSource(bad, "k"); err == nil {
			t.Errorf("accepted %q", bad)
		}
	}
}

func TestReadEnvKey(t *testing.T) {
	p := filepath.Join(t.TempDir(), "e")
	os.WriteFile(p, []byte("A=1\r\nCPA_MGMT_KEY = 'abc'\n"), 0o600)
	if k, err := readEnvKey(p, "CPA_MGMT_KEY"); err != nil || k != "abc" {
		t.Errorf("got %q, %v", k, err)
	}
	os.WriteFile(p, []byte("CPA_MGMT_KEY=\n"), 0o600)
	if _, err := readEnvKey(p, "CPA_MGMT_KEY"); err == nil {
		t.Error("accepted an empty key")
	}
}

// End to end through the mux: claude carries the pooled cost, claude2 does
// not, and each has its own alert state.
func TestHubProvidersOverHTTP(t *testing.T) {
	t.Setenv("HOME", t.TempDir()) // no transcripts, no ~/.claude.json
	f := &fakeHub{
		files: []hubAuthFile{
			{ID: "1", AuthIndex: "a", Provider: "claude", Email: "a@x.com"},
			{ID: "2", AuthIndex: "b", Provider: "claude", Email: "b@x.com"},
		},
		usage: map[string]string{
			"a": usageBody(50, 20, "2099-01-01T00:00:00Z"),
			"b": usageBody(85, 60, "2099-01-01T00:00:00Z"),
		},
	}
	m := newTestMonitor(t)
	m.hub = f.start(t)
	m.poll()
	m.pollHub()
	srv := httptest.NewServer(newMux("", m))
	defer srv.Close()

	get := func(path string, into any) {
		t.Helper()
		resp, err := http.Get(srv.URL + path)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != 200 {
			t.Fatalf("%s: %s", path, resp.Status)
		}
		json.NewDecoder(resp.Body).Decode(into)
	}
	var c1, c2 struct {
		Provider      string                    `json:"provider"`
		Account       string                    `json:"account"`
		CostAvailable bool                      `json:"cost_available"`
		SourceError   string                    `json:"source_error"`
		FiveHour      struct{ Utilization int } `json:"five_hour"`
	}
	get("/usage", &c1)
	get("/usage?provider=claude2", &c2)
	if c1.Provider != "claude" || c1.Account != "a@x.com" || !c1.CostAvailable || c1.FiveHour.Utilization != 50 {
		t.Errorf("claude = %+v", c1)
	}
	if c2.Provider != "claude2" || c2.Account != "b@x.com" || c2.CostAvailable || c2.FiveHour.Utilization != 85 {
		t.Errorf("claude2 = %+v", c2)
	}

	var ev struct{ Events []event }
	get("/events?provider=claude2", &ev)
	if len(ev.Events) != 1 || ev.Events[0].Provider != "claude2" || ev.Events[0].Threshold != 80 {
		t.Errorf("claude2 events = %+v", ev.Events)
	}
	get("/events", &ev)
	if len(ev.Events) != 0 {
		t.Errorf("claude events leaked claude2's: %+v", ev.Events)
	}

	var share struct{ Provider, Account string }
	m.shareOrigins = []string{"*"}
	get("/share?provider=claude2", &share)
	if share.Provider != "claude2" || share.Account != "" {
		t.Errorf("share = %+v", share)
	}
}

func TestClaude2WithoutHubIsASourceError(t *testing.T) {
	m := newTestMonitor(t)
	srv := httptest.NewServer(newMux("", m))
	defer srv.Close()
	resp, err := http.Get(srv.URL + "/usage?provider=claude2")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var s struct {
		Provider      string
		SourceError   string `json:"source_error"`
		CostAvailable bool   `json:"cost_available"`
	}
	json.NewDecoder(resp.Body).Decode(&s)
	if resp.StatusCode != 200 || s.Provider != "claude2" || s.SourceError == "" || s.CostAvailable {
		t.Errorf("%s %+v", resp.Status, s)
	}
	resp2, _ := http.Get(srv.URL + "/usage?provider=nope")
	resp2.Body.Close()
	if resp2.StatusCode != 400 {
		t.Errorf("unknown provider: %s", resp2.Status)
	}
}
