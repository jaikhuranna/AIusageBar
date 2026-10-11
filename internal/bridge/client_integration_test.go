package bridge

import (
	"aiusagebar/internal/client"
	"context"
	"net/http/httptest"
	"testing"
	"time"
)

// The tray uses the same pairing and authenticated wire API as other clients;
// no monitor pointer, local provider data, or control socket reaches the client.
func TestDesktopClientUsesServerPairingAndBothProviders(t *testing.T) {
	m := newTestMonitor(t)
	m.publicURL = "https://usage.example.com"
	m.snap = wireOf(sample(35, time.Now().Add(time.Hour)))
	m.etag = etagOf(m.snap)
	m.codex = &codexSource{read: func(context.Context) (snapshot, error) {
		s := sample(72, time.Now().Add(time.Hour))
		s.provider = "codex"
		return s, nil
	}}
	srv := httptest.NewServer(newMux("", m))
	defer srv.Close()
	cfg, err := client.Pair(context.Background(), client.Config{Server: srv.URL, Provider: "claude"}, m.newPairCode(), "Linux tray")
	if err != nil {
		t.Fatal(err)
	}
	api, err := client.New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	for _, p := range []string{"claude", "codex", "claude"} {
		r := api.Fetch(context.Background(), p)
		if r.Err != nil || r.Snapshot.Provider != p {
			t.Fatalf("%s: %+v", p, r)
		}
	}
	if len(m.st.Devices) != 1 || m.st.Devices[0].Name != "Linux tray" {
		t.Fatal("tray did not receive its own pairing")
	}
	// A server restart loads that same token; the client does not need to pair again.
	restarted := newMonitor([]int{80, 95}, 5)
	if !restarted.tokenOK("", cfg.Token) {
		t.Fatal("server restart lost tray pairing")
	}
}
