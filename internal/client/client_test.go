package client

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

func TestProviderCachesAndFreshness(t *testing.T) {
	var fail atomic.Bool
	age := time.Now().Add(-2 * time.Hour).UTC().Truncate(time.Second)
	seen := map[string]int{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer device-token" || r.URL.Query().Has("token") {
			t.Error("wrong authentication")
		}
		p := r.URL.Query().Get("provider")
		seen[p]++
		if fail.Load() {
			w.WriteHeader(503)
			return
		}
		etag := `"` + p + `"`
		if r.Header.Get("If-None-Match") != "" {
			if r.Header.Get("If-None-Match") != etag {
				t.Error("ETag crossed providers")
			}
			w.WriteHeader(304)
			return
		}
		w.Header().Set("ETag", etag)
		json.NewEncoder(w).Encode(map[string]any{"schema": 1, "provider": p, "cache_fetched_at": age.Format(time.RFC3339), "five_hour": map[string]int{"utilization": 25, "remaining": 75, "window_minutes": 60}})
	}))
	defer srv.Close()
	c, _ := New(Config{Server: srv.URL, Token: "device-token"})
	for _, p := range []string{"claude", "codex", "claude", "codex"} {
		r := c.Fetch(context.Background(), p)
		if r.Err != nil || r.Snapshot == nil || r.Snapshot.Provider != p {
			t.Fatalf("bad fetch for %s: %+v", p, r)
		}
		if !r.SourceTime().Equal(age) {
			t.Fatal("304 replaced source age with transport age")
		}
		if r.Snapshot.CostAvailable != (p == "claude") {
			t.Fatal("incorrect legacy cost default")
		}
	}
	before := c.Fetch(context.Background(), "codex")
	fail.Store(true)
	after := c.Fetch(context.Background(), "codex")
	if after.Err == nil || after.Snapshot == nil || after.Snapshot.Provider != "codex" || !after.FetchedAt.Equal(before.FetchedAt) {
		t.Fatal("failure lost cache or advanced its age")
	}
}

func TestUnauthorizedAndMismatchedProvider(t *testing.T) {
	var status atomic.Int32
	status.Store(200)
	var body atomic.Value
	body.Store(`{"schema":1,"provider":"codex","five_hour":{"utilization":20}}`)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(int(status.Load()))
		w.Write([]byte(body.Load().(string)))
	}))
	defer srv.Close()
	c, _ := New(Config{Server: srv.URL, Token: "token"})
	if c.Fetch(context.Background(), "codex").Err != nil {
		t.Fatal("initial fetch failed")
	}
	body.Store(`{"schema":1,"provider":"claude"}`)
	r := c.Fetch(context.Background(), "codex")
	if r.Err == nil || r.Snapshot.Provider != "codex" {
		t.Fatal("mismatched response replaced cache")
	}
	status.Store(401)
	r = c.Fetch(context.Background(), "codex")
	if r.Err != ErrUnpaired || r.Snapshot != nil {
		t.Fatal("revoked token retained visible data")
	}
}

func TestRedirectsNeverReceiveCredentials(t *testing.T) {
	var hits atomic.Int32
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hits.Add(1) }))
	defer other.Close()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, other.URL, http.StatusTemporaryRedirect)
	}))
	defer srv.Close()
	c, _ := New(Config{Server: srv.URL, Token: "secret"})
	if c.Fetch(context.Background(), "claude").Err == nil {
		t.Fatal("redirect accepted")
	}
	if _, err := Pair(context.Background(), Config{Server: srv.URL}, "123456", "tray"); err == nil {
		t.Fatal("pair redirect accepted")
	}
	if hits.Load() != 0 {
		t.Fatal("redirect forwarded a token or pairing code")
	}
}

func TestPairAndPrivateConfiguration(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" || r.URL.Path != "/pair" || r.Header.Get("Authorization") != "" {
			t.Error("wrong pairing request")
		}
		var req map[string]string
		json.NewDecoder(r.Body).Decode(&req)
		if req["code"] != "123456" || req["name"] != "Linux tray" {
			t.Error("wrong pairing body")
		}
		w.Write([]byte(`{"token":"new-device-token","public_url":"https://public.example"}`))
	}))
	defer srv.Close()
	c, err := Pair(context.Background(), Config{Server: srv.URL, Provider: "codex"}, "123456", "Linux tray")
	if err != nil || c.Token != "new-device-token" || c.Server != srv.URL {
		t.Fatalf("bad pairing: %v", err)
	}
	path := filepath.Join(t.TempDir(), "config", "client.json")
	if err := Save(path, c); err != nil {
		t.Fatal(err)
	}
	info, _ := os.Stat(path)
	if info.Mode().Perm() != 0600 {
		t.Fatal("token file is not private")
	}
	loaded, err := Load(path)
	if err != nil || loaded != c {
		t.Fatal("configuration did not round trip")
	}
	if err := loaded.SetServer("https://another.example"); err != nil || loaded.Token != "" {
		t.Fatal("server change retained another server's credential")
	}
}

func TestTransportPolicy(t *testing.T) {
	for _, raw := range []string{"http://127.0.0.1:8765", "http://[::1]:8765", "http://192.168.1.2:8765", "https://usage.example/prefix/"} {
		if _, err := NormalizeURL(raw); err != nil {
			t.Errorf("rejected %s", raw)
		}
	}
	for _, raw := range []string{"http://example.com", "http://8.8.8.8", "https://user:pass@example.com", "https://example.com?token=secret", "file:///etc/passwd"} {
		if _, err := NormalizeURL(raw); err == nil {
			t.Errorf("accepted unsafe URL %s", raw)
		}
	}
}
