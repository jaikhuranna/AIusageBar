package client

import (
	"aiusagebar/internal/protocol"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sync"
	"time"
)

var ErrUnpaired = errors.New("pair this tray with the server using aiusagebar -server URL -pair CODE")
var ErrUnavailable = errors.New("server unavailable")

type Reading struct {
	Snapshot  *protocol.Snapshot
	FetchedAt time.Time
	Err       error
}

func (r Reading) SourceTime() time.Time {
	if r.Snapshot != nil {
		if t, err := time.Parse(time.RFC3339, r.Snapshot.CacheFetchedAt); err == nil {
			return t
		}
	}
	return r.FetchedAt
}

type cached struct {
	snapshot protocol.Snapshot
	etag     string
	fetched  time.Time
}

type Client struct {
	config Config
	http   *http.Client
	mu     sync.Mutex
	cache  map[string]cached
}

func New(c Config) (*Client, error) {
	server, err := NormalizeURL(c.Server)
	if err != nil {
		return nil, err
	}
	c.Server = server
	return &Client{config: c, cache: map[string]cached{}, http: &http.Client{
		Timeout: 50 * time.Second,
		// Even same-host redirects could downgrade transport or leak a token.
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}}, nil
}

// Fetch keeps an independent ETag/snapshot per provider. A failed fetch retains
// the old reading and its original age; 304 only updates the successful fetch time.
func (c *Client) Fetch(ctx context.Context, provider string) Reading {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !ValidProvider(provider) {
		return Reading{Err: errors.New("unknown provider")}
	}
	previous, exists := c.cache[provider]
	reading := Reading{}
	if exists {
		s := previous.snapshot
		reading.Snapshot = &s
		reading.FetchedAt = previous.fetched
	}
	if c.config.Token == "" {
		return Reading{Err: ErrUnpaired}
	}
	u, _ := url.Parse(c.config.Server + "/usage")
	u.RawQuery = url.Values{"provider": {provider}}.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		reading.Err = ErrUnavailable
		return reading
	}
	req.Header.Set("Authorization", "Bearer "+c.config.Token)
	if exists && previous.etag != "" {
		req.Header.Set("If-None-Match", previous.etag)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		reading.Err = ErrUnavailable
		return reading
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusUnauthorized {
		delete(c.cache, provider)
		return Reading{Err: ErrUnpaired}
	}
	if resp.StatusCode == http.StatusNotModified && exists {
		previous.fetched = time.Now()
		c.cache[provider] = previous
		reading.FetchedAt = previous.fetched
		return reading
	}
	if resp.StatusCode != http.StatusOK {
		reading.Err = ErrUnavailable
		return reading
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, 1024*1024+1))
	var s protocol.Snapshot
	if err != nil || len(data) > 1024*1024 || json.Unmarshal(data, &s) != nil || s.Schema != 1 || s.Provider != provider {
		reading.Err = errors.New("server returned incompatible usage data")
		return reading
	}
	now := time.Now()
	c.cache[provider] = cached{snapshot: s, etag: resp.Header.Get("ETag"), fetched: now}
	return Reading{Snapshot: &s, FetchedAt: now}
}

// Pair uses the same one-time-code endpoint as phone and watch clients. The
// supplied address stays authoritative; a LAN desktop needn't use a public tunnel.
func Pair(ctx context.Context, c Config, code, name string) (Config, error) {
	api, err := New(c)
	if err != nil {
		return c, err
	}
	data, _ := json.Marshal(map[string]string{"code": code, "name": name})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, api.config.Server+"/pair", bytes.NewReader(data))
	if err != nil {
		return c, ErrUnavailable
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := api.http.Do(req)
	if err != nil {
		return c, ErrUnavailable
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return c, fmt.Errorf("pairing failed (HTTP %d): check the server and one-time code", resp.StatusCode)
	}
	var body struct {
		Token string `json:"token"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 64*1024)).Decode(&body); err != nil || body.Token == "" {
		return c, errors.New("server returned an invalid pairing response")
	}
	c.Server = api.config.Server
	c.Token = body.Token
	return c, nil
}
