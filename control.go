// Local control socket: the only way to mint a pairing code.
//
// Minting used to be an HTTP endpoint guarded by a loopback check, but a tunnel
// or reverse proxy on this machine (cloudflared, tailscale serve, nginx)
// delivers internet traffic from 127.0.0.1, so "came from loopback" proves
// nothing. A Unix socket in the user's runtime dir is reachable only by
// processes running as this user, whatever sits in front of the TCP port.
package main

import (
	"context"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

func controlSocketPath() string {
	if dir := os.Getenv("XDG_RUNTIME_DIR"); dir != "" {
		return filepath.Join(dir, "aiusagebar.sock")
	}
	return filepath.Join(filepath.Dir(statePath()), "control.sock")
}

// serveControl listens on the control socket until stop is called.
func serveControl(m *monitor) (stop func(), err error) {
	path := controlSocketPath()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, err
	}
	// A socket left by a crashed bridge would make Listen fail; one that still
	// answers belongs to a live bridge, which must keep it.
	if c, err := net.DialTimeout("unix", path, time.Second); err == nil {
		c.Close()
		return nil, fmt.Errorf("another bridge already owns %s", path)
	}
	os.Remove(path)
	ln, err := net.Listen("unix", path)
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(path, 0o600); err != nil {
		ln.Close()
		return nil, err
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/pair/new", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		code := m.newPairCode()
		log.Printf("pairing code %s (valid %s)", code, pairCodeTTL)
		writeJSON(w, map[string]any{"code": code, "expires_in_sec": int(pairCodeTTL.Seconds())})
	})
	srv := &http.Server{Handler: mux, ReadTimeout: 5 * time.Second}
	go srv.Serve(ln)
	return func() {
		srv.Close()
		os.Remove(path)
	}, nil
}

// controlClient talks HTTP over the control socket; the host in the URL is
// ignored.
func controlClient() *http.Client {
	path := controlSocketPath()
	return &http.Client{
		Timeout: 5 * time.Second,
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "unix", path)
			},
		},
	}
}
