// HTTP bridge: exposes the same usage snapshot the tray shows as JSON, so
// other devices on the LAN can read it.
package main

import (
	"encoding/json"
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"time"
)

// wireWindow is one plan-limit window as sent over the wire. Remaining is
// included so clients don't have to know the 100-utilization convention.
type wireWindow struct {
	Utilization int    `json:"utilization"`
	Remaining   int    `json:"remaining"`
	ResetsAt    string `json:"resets_at,omitempty"`
	ResetsInSec int64  `json:"resets_in_sec"`
}

type wireSnapshot struct {
	FiveHour    *wireWindow `json:"five_hour"`
	SevenDay    *wireWindow `json:"seven_day"`
	CostToday   float64     `json:"cost_today"`
	CostSession float64     `json:"cost_session"`
	CostWeek    float64     `json:"cost_week"`
	GeneratedAt string      `json:"generated_at"`
	Host        string      `json:"host"`
}

func toWire(w *limitWindow) *wireWindow {
	if w == nil {
		return nil
	}
	out := &wireWindow{
		Utilization: w.Utilization,
		Remaining:   100 - w.Utilization,
	}
	if out.Remaining < 0 {
		out.Remaining = 0
	}
	if t := w.resetTime(); !t.IsZero() {
		out.ResetsAt = t.UTC().Format(time.RFC3339)
		if d := time.Until(t); d > 0 {
			out.ResetsInSec = int64(d.Seconds())
		}
	}
	return out
}

func wireOf(s snapshot) wireSnapshot {
	host, _ := os.Hostname()
	return wireSnapshot{
		FiveHour:    toWire(s.fiveHour),
		SevenDay:    toWire(s.sevenDay),
		CostToday:   s.costToday,
		CostSession: s.costSession,
		CostWeek:    s.costWeek,
		GeneratedAt: time.Now().UTC().Format(time.RFC3339),
		Host:        host,
	}
}

// serve runs the JSON bridge until the process exits. token, when non-empty,
// must be supplied by clients as ?token= or an "Authorization: Bearer" header.
func serve(addr, token string) error {
	mux := http.NewServeMux()
	handler := func(w http.ResponseWriter, r *http.Request) {
		if !authorized(r, token) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		enc := json.NewEncoder(w)
		enc.SetIndent("", "  ")
		if err := enc.Encode(wireOf(gather())); err != nil {
			log.Printf("serve: %v", err)
		}
	}
	mux.HandleFunc("/usage", handler)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		handler(w, r)
	})

	log.Printf("serving usage JSON on http://%s/usage", addr)
	for _, ip := range localIPs() {
		log.Printf("  reachable at http://%s%s/usage", ip, portOf(addr))
	}
	srv := &http.Server{
		Addr:         addr,
		Handler:      mux,
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 30 * time.Second,
	}
	return srv.ListenAndServe()
}

func authorized(r *http.Request, token string) bool {
	if token == "" {
		return true
	}
	if r.URL.Query().Get("token") == token {
		return true
	}
	auth := r.Header.Get("Authorization")
	return strings.TrimPrefix(auth, "Bearer ") == token && auth != ""
}

func portOf(addr string) string {
	if _, port, err := net.SplitHostPort(addr); err == nil {
		return ":" + port
	}
	return addr
}

// localIPs lists non-loopback IPv4 addresses, so the log tells you exactly
// what address a client on the LAN should point at.
func localIPs() []string {
	var out []string
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return out
	}
	for _, a := range addrs {
		ipnet, ok := a.(*net.IPNet)
		if !ok || ipnet.IP.IsLoopback() {
			continue
		}
		if v4 := ipnet.IP.To4(); v4 != nil {
			out = append(out, v4.String())
		}
	}
	return out
}
