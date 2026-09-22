// HTTP bridge: exposes the usage snapshot, the alert event ring and device
// pairing, so other devices on the LAN (the Wear OS app in wearos/) can read
// the same numbers the tray shows. Wire contract v1: wearos/DESIGN.md §7.
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
	Schema      int         `json:"schema"`
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
		Schema:      schemaVersion,
		FiveHour:    toWire(s.fiveHour),
		SevenDay:    toWire(s.sevenDay),
		CostToday:   s.costToday,
		CostSession: s.costSession,
		CostWeek:    s.costWeek,
		GeneratedAt: time.Now().UTC().Format(time.RFC3339),
		Host:        host,
	}
}

// serve runs the JSON bridge until the process exits. token, when non-empty, is
// a shared secret; per-device tokens handed out by /pair are always accepted.
func serve(addr, token string, m *monitor) error {
	mux := http.NewServeMux()

	usage := func(w http.ResponseWriter, r *http.Request) {
		if !authorized(r, token, m) {
			unauthorized(w)
			return
		}
		snap, etag := m.latest()
		w.Header().Set("ETag", etag)
		w.Header().Set("Cache-Control", "no-cache")
		if matchesETag(r.Header.Get("If-None-Match"), etag) {
			w.WriteHeader(http.StatusNotModified)
			return
		}
		// resets_in_sec is recomputed per request: the countdown has to be
		// right even when the cached sample is 29 seconds old.
		snap.FiveHour = refreshCountdown(snap.FiveHour)
		snap.SevenDay = refreshCountdown(snap.SevenDay)
		writeJSON(w, snap)
	}
	mux.HandleFunc("/usage", usage)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		usage(w, r)
	})

	mux.HandleFunc("/events", func(w http.ResponseWriter, r *http.Request) {
		if !authorized(r, token, m) {
			unauthorized(w)
			return
		}
		events, high := m.eventsSince(r.URL.Query().Get("since"))
		writeJSON(w, map[string]any{
			"schema":     schemaVersion,
			"events":     events,
			"high_water": high,
		})
	})

	// /pair/new mints a code. It is loopback-only and unauthenticated: standing
	// at the desktop is the proof of intent.
	mux.HandleFunc("/pair/new", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		if !fromLoopback(r) {
			http.Error(w, "pair codes can only be minted on the host itself", http.StatusForbidden)
			return
		}
		code := m.newPairCode()
		log.Printf("pairing code %s (valid %s)", code, pairCodeTTL)
		writeJSON(w, map[string]any{"code": code, "expires_in_sec": int(pairCodeTTL.Seconds())})
	})

	mux.HandleFunc("/pair", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		var body struct {
			Code string `json:"code"`
			Name string `json:"name"`
		}
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&body); err != nil {
			http.Error(w, "bad json", http.StatusBadRequest)
			return
		}
		d, ok := m.redeem(strings.TrimSpace(body.Code), strings.TrimSpace(body.Name))
		if !ok {
			http.Error(w, "bad or expired pairing code", http.StatusForbidden)
			return
		}
		writeJSON(w, map[string]any{"token": d.Token, "device_id": d.ID, "host": hostname()})
	})

	// /healthz is unauthenticated and carries no usage data, so a client can
	// find out whether the bridge is up and whether it needs to pair first.
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		auth := "none"
		if m.authRequired(token) {
			auth = "token"
		}
		writeJSON(w, map[string]any{
			"ok":         true,
			"schema":     schemaVersion,
			"host":       hostname(),
			"auth":       auth,
			"uptime_sec": int(time.Since(m.started).Seconds()),
		})
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

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	if err := enc.Encode(v); err != nil {
		log.Printf("serve: %v", err)
	}
}

func unauthorized(w http.ResponseWriter) {
	// Points a client that has never paired at the one endpoint that will
	// tell it what to do next.
	w.Header().Set("WWW-Authenticate", `Bearer realm="aiusagebar", pair="/pair"`)
	http.Error(w, "unauthorized: pair this device first", http.StatusUnauthorized)
}

// refreshCountdown recomputes resets_in_sec from resets_at at send time.
func refreshCountdown(w *wireWindow) *wireWindow {
	if w == nil || w.ResetsAt == "" {
		return w
	}
	t, err := time.Parse(time.RFC3339, w.ResetsAt)
	if err != nil {
		return w
	}
	cp := *w
	cp.ResetsInSec = 0
	if d := time.Until(t); d > 0 {
		cp.ResetsInSec = int64(d.Seconds())
	}
	return &cp
}

// matchesETag handles the comma list and the W/ prefix a proxy may add.
func matchesETag(header, etag string) bool {
	if header == "" || etag == "" {
		return false
	}
	for _, part := range strings.Split(header, ",") {
		part = strings.TrimSpace(part)
		if part == "*" || strings.TrimPrefix(part, "W/") == etag {
			return true
		}
	}
	return false
}

func authorized(r *http.Request, shared string, m *monitor) bool {
	if !m.authRequired(shared) {
		return true
	}
	if tok := r.URL.Query().Get("token"); m.tokenOK(shared, tok) {
		return true
	}
	auth := r.Header.Get("Authorization")
	if !strings.HasPrefix(auth, "Bearer ") {
		return false
	}
	return m.tokenOK(shared, strings.TrimPrefix(auth, "Bearer "))
}

func fromLoopback(r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func hostname() string {
	h, _ := os.Hostname()
	return h
}

func portOf(addr string) string {
	if _, port, err := net.SplitHostPort(addr); err == nil {
		return ":" + port
	}
	return addr
}

// localIPs lists non-loopback IPv4 addresses, so the log tells you exactly
// what to type into the watch.
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
