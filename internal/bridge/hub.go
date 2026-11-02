// hub reads per-account Claude limits from a CLIProxyAPI hub (-cliproxy-url).
//
// With several Claude subscriptions pooled behind the hub, Claude Code no
// longer refreshes ~/.claude.json for any one of them, so the limits come from
// the hub instead: it lists its Claude accounts and, through its management
// api-call route, asks Anthropic's usage endpoint on each account's behalf.
// The server never sees an OAuth token or calls Anthropic itself; the hub does.
//
// Enabled accounts sorted by email fill claudeProviders in order, so the
// order is stable across restarts and adding an account only appends.
package bridge

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
)

// claudeProviders are the provider names the hub accounts map to, in order.
// Add a name here (and to the clients) to support another account.
var claudeProviders = []string{"claude", "claude2"}

func isClaudeProvider(p string) bool {
	for _, c := range claudeProviders {
		if c == p {
			return true
		}
	}
	return false
}

const (
	hubMinAge  = 5 * time.Minute
	hubTimeout = 15 * time.Second
	hubKeyName = "CPA_MGMT_KEY"
	usageURL   = "https://api.anthropic.com/api/oauth/usage"
)

type hubSource struct {
	url     string
	keyFile string
	client  *http.Client
	minAge  time.Duration
	timeout time.Duration

	mu    sync.Mutex // one refresh at a time; latecomers share its result
	last  time.Time
	slots []snapshot // indexed like claudeProviders
}

func newHubSource(rawURL, keyFile string) (*hubSource, error) {
	u, err := url.Parse(rawURL)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return nil, errors.New("-cliproxy-url must be an http(s) URL without credentials, query, or fragment")
	}
	if keyFile == "" {
		return nil, errors.New("-cliproxy-url needs -cliproxy-key-file")
	}
	h := &hubSource{
		url:     strings.TrimRight(rawURL, "/"),
		keyFile: keyFile,
		client:  &http.Client{},
		minAge:  hubMinAge,
		timeout: hubTimeout,
		slots:   make([]snapshot, len(claudeProviders)),
	}
	for i, p := range claudeProviders {
		h.slots[i] = snapshot{provider: p, sourceError: "Waiting for the first hub sample."}
	}
	log.Printf("hub: Claude limits from %s, at most every %s", h.url, h.minAge)
	return h, nil
}

// slot is the latest snapshot (limits only, no cost) for claudeProviders[i],
// polling the hub first if the last attempt was more than minAge ago.
func (h *hubSource) slot(i int) snapshot {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.last.IsZero() || time.Since(h.last) >= h.minAge {
		h.last = time.Now()
		h.refreshLocked()
	}
	return h.slots[i]
}

type hubAuthFile struct {
	ID        string `json:"id"`
	AuthIndex string `json:"auth_index"`
	Provider  string `json:"provider"`
	Email     string `json:"email"`
	Disabled  bool   `json:"disabled"`
}

func (h *hubSource) refreshLocked() {
	ctx, cancel := context.WithTimeout(context.Background(), h.timeout)
	defer cancel()

	accounts, err := h.accounts(ctx)
	if err != nil {
		// Keep the last good windows, but say they're not being updated.
		log.Printf("hub: %v", err)
		for i := range h.slots {
			h.slots[i].sourceError = sentence(err)
		}
		return
	}
	next := make([]snapshot, len(h.slots))
	var wg sync.WaitGroup
	for i, p := range claudeProviders {
		if i >= len(accounts) {
			next[i] = snapshot{provider: p, sourceError: fmt.Sprintf("No Claude account %d is signed in to the hub.", i+1)}
			continue
		}
		wg.Add(1)
		go func(i int, a hubAuthFile) {
			defer wg.Done()
			s, err := h.read(ctx, a)
			s.provider, s.account = claudeProviders[i], a.Email
			if err != nil {
				log.Printf("hub: %s: %v", claudeProviders[i], err)
				// Keep the previous windows only if they were this account's.
				if prev := h.slots[i]; prev.account == a.Email {
					s = prev
				}
				s.sourceError = sentence(err)
			}
			next[i] = s
		}(i, accounts[i])
	}
	wg.Wait()
	copy(h.slots, next)
}

// sentence turns an error message into a source_error clients can show.
func sentence(err error) string {
	m := err.Error()
	return strings.ToUpper(m[:1]) + m[1:] + "."
}

// accounts lists the hub's enabled Claude accounts in a stable order.
func (h *hubSource) accounts(ctx context.Context) ([]hubAuthFile, error) {
	var resp struct {
		Files []hubAuthFile `json:"files"`
	}
	if err := h.call(ctx, http.MethodGet, "auth-files", nil, &resp); err != nil {
		return nil, fmt.Errorf("the hub could not list accounts: %w", err)
	}
	var out []hubAuthFile
	for _, f := range resp.Files {
		if f.Provider == "claude" && !f.Disabled {
			out = append(out, f)
		}
	}
	sort.SliceStable(out, func(i, j int) bool {
		a, b := strings.ToLower(out[i].Email), strings.ToLower(out[j].Email)
		if a != b {
			return a < b
		}
		return out[i].ID < out[j].ID
	})
	return out, nil
}

type hubWindow struct {
	Utilization float64 `json:"utilization"`
	ResetsAt    *string `json:"resets_at"`
}

// read asks Anthropic's usage endpoint through the hub for one account.
func (h *hubSource) read(ctx context.Context, a hubAuthFile) (snapshot, error) {
	req := map[string]any{
		"auth_index": a.AuthIndex,
		"method":     "GET",
		"url":        usageURL,
		"header": map[string]string{
			"Authorization":  "Bearer $TOKEN$", // the hub swaps in the account's token
			"anthropic-beta": "oauth-2025-04-20",
		},
	}
	var resp struct {
		StatusCode int    `json:"status_code"`
		Body       string `json:"body"`
	}
	if err := h.call(ctx, http.MethodPost, "api-call", req, &resp); err != nil {
		return snapshot{}, fmt.Errorf("the hub could not read this account's usage: %w", err)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return snapshot{}, fmt.Errorf("Anthropic refused the hub's usage request (HTTP %d)", resp.StatusCode)
	}
	var usage struct {
		FiveHour *hubWindow `json:"five_hour"`
		SevenDay *hubWindow `json:"seven_day"`
	}
	if err := json.Unmarshal([]byte(resp.Body), &usage); err != nil {
		return snapshot{}, errors.New("the hub returned an unreadable usage response")
	}
	if usage.FiveHour == nil && usage.SevenDay == nil {
		return snapshot{}, errors.New("the hub returned no usage windows")
	}
	return snapshot{
		fiveHour:       usage.FiveHour.window(),
		sevenDay:       usage.SevenDay.window(),
		cacheFetchedAt: time.Now(),
	}, nil
}

func (w *hubWindow) window() *limitWindow {
	if w == nil {
		return nil
	}
	out := &limitWindow{Utilization: int(math.Round(math.Min(math.Max(w.Utilization, 0), 100)))}
	if w.ResetsAt != nil {
		out.ResetsAt = *w.ResetsAt
	}
	return out
}

// call makes one authorised management request. Errors never include the
// response body or the key.
func (h *hubSource) call(ctx context.Context, method, path string, body, out any) error {
	key, err := readEnvKey(h.keyFile, hubKeyName)
	if err != nil {
		return err
	}
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, h.url+"/v0/management/"+path, rd)
	if err != nil {
		return errors.New("the hub URL is not valid")
	}
	req.Header.Set("Authorization", "Bearer "+key)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := h.client.Do(req)
	if err != nil {
		return errors.New("the hub is unreachable")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("the hub answered HTTP %d", resp.StatusCode)
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 4<<20)).Decode(out); err != nil {
		return errors.New("the hub sent an unreadable response")
	}
	return nil
}

// readEnvKey finds NAME=value in an env-style file. Read on every call so a
// rotated key is picked up without a restart.
func readEnvKey(path, name string) (string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return "", errors.New("the hub key file is unreadable")
	}
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimPrefix(strings.TrimSpace(line), "export ")
		k, v, ok := strings.Cut(line, "=")
		if !ok || strings.TrimSpace(k) != name {
			continue
		}
		v = strings.TrimSpace(v)
		if len(v) >= 2 && (v[0] == '"' || v[0] == '\'') && v[len(v)-1] == v[0] {
			v = v[1 : len(v)-1]
		}
		if v != "" {
			return v, nil
		}
	}
	return "", fmt.Errorf("the hub key file has no %s", name)
}
