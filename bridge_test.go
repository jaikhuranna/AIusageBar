package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

// A tunnel on this machine delivers internet traffic from loopback, so the TCP
// port must not mint codes for anyone, loopback or not.
func TestTCPPortCannotMintCodes(t *testing.T) {
	m := newTestMonitor(t)
	srv := httptest.NewServer(newMux("", m)) // httptest listens on 127.0.0.1
	defer srv.Close()

	resp, err := http.Post(srv.URL+"/pair/new", "application/json", strings.NewReader("{}"))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("POST /pair/new over TCP from loopback: got %s, want 404", resp.Status)
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.pending) != 0 {
		t.Fatal("a code was minted over TCP")
	}
}

func TestControlSocketMintsRedeemableCodes(t *testing.T) {
	m := newTestMonitor(t)
	t.Setenv("XDG_RUNTIME_DIR", t.TempDir())

	stop, err := serveControl(m)
	if err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(controlSocketPath())
	if err != nil {
		t.Fatal(err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Errorf("control socket mode %o, want 600", perm)
	}
	if _, err := serveControl(m); err == nil {
		t.Error("a second bridge took over a live control socket")
	}

	code, expiresIn, err := fetchPairCode()
	if err != nil {
		t.Fatal(err)
	}
	if expiresIn <= 0 {
		t.Errorf("expires_in_sec = %d", expiresIn)
	}
	if _, ok := m.redeem(code, "watch"); !ok {
		t.Fatal("code minted over the control socket did not redeem")
	}

	stop()
	if _, err := os.Stat(controlSocketPath()); !os.IsNotExist(err) {
		t.Error("stop left the socket file behind")
	}
	if _, _, err := fetchPairCode(); err == nil {
		t.Error("minting still worked after stop")
	}
}

// Without -public-url an unpaired bridge is open, as before. With it, the
// internet can reach the port, so nothing is readable without a credential.
func TestPublicBridgeIsClosedBeforeFirstPairing(t *testing.T) {
	m := newTestMonitor(t)
	m.publicURL = "https://usage.example.com"
	srv := httptest.NewServer(newMux("", m))
	defer srv.Close()

	for _, path := range []string{"/usage", "/", "/events"} {
		resp, err := http.Get(srv.URL + path)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("GET %s on an unpaired public bridge: %s, want 401", path, resp.Status)
		}
	}

	resp, err := http.Get(srv.URL + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	var health struct{ Auth string }
	json.NewDecoder(resp.Body).Decode(&health)
	resp.Body.Close()
	if health.Auth != "token" {
		t.Errorf("healthz auth = %q, want token", health.Auth)
	}

	// Pairing still works, and hands the device the URL to use from anywhere.
	code := m.newPairCode()
	resp, err = http.Post(srv.URL+"/pair", "application/json", strings.NewReader(`{"code":"`+code+`","name":"watch"}`))
	if err != nil {
		t.Fatal(err)
	}
	var paired struct {
		Token     string `json:"token"`
		PublicURL string `json:"public_url"`
	}
	json.NewDecoder(resp.Body).Decode(&paired)
	resp.Body.Close()
	if paired.Token == "" || paired.PublicURL != "https://usage.example.com" {
		t.Fatalf("pair response = %+v", paired)
	}

	req, _ := http.NewRequest("GET", srv.URL+"/usage", nil)
	req.Header.Set("Authorization", "Bearer "+paired.Token)
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /usage with the paired token: %s", resp.Status)
	}
}

func TestLANBridgeStaysOpenUntilFirstPairing(t *testing.T) {
	m := newTestMonitor(t)
	srv := httptest.NewServer(newMux("", m))
	defer srv.Close()
	resp, err := http.Get(srv.URL + "/usage")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("unpaired LAN bridge: %s, want 200", resp.Status)
	}
}

func TestListensEverywhere(t *testing.T) {
	for addr, want := range map[string]bool{
		":8765": true, "0.0.0.0:8765": true, "[::]:8765": true,
		"127.0.0.1:8765": false, "192.168.0.19:8765": false, "nonsense": false,
	} {
		if got := listensEverywhere(addr); got != want {
			t.Errorf("listensEverywhere(%q) = %v, want %v", addr, got, want)
		}
	}
}
