// Package client is an HTTP-only client of the usage server. It never reads
// provider credentials, server state, or usage transcripts and never starts CLIs.
package client

import (
	"encoding/json"
	"errors"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"strings"
)

const DefaultServer = "http://127.0.0.1:8765"

type Config struct {
	Server   string `json:"server"`
	Token    string `json:"token,omitempty"`
	Provider string `json:"provider"`
}

func ConfigPath() (string, error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "aiusagebar", "client.json"), nil
}

func Load(path string) (Config, error) {
	c := Config{Server: DefaultServer, Provider: "claude"}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return c, nil
	}
	if err != nil {
		return c, err
	}
	if err = json.Unmarshal(data, &c); err != nil {
		return c, errors.New("invalid tray client configuration")
	}
	c.Server, err = NormalizeURL(c.Server)
	if err != nil {
		return c, err
	}
	if !ValidProvider(c.Provider) {
		return c, errors.New("provider must be claude or codex")
	}
	return c, nil
}

func Save(path string, c Config) error {
	if _, err := NormalizeURL(c.Server); err != nil {
		return err
	}
	if !ValidProvider(c.Provider) {
		return errors.New("provider must be claude or codex")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(path), ".client-*")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	if _, err = f.Write(data); err != nil {
		f.Close()
		return err
	}
	if err = f.Close(); err != nil {
		return err
	}
	return os.Rename(f.Name(), path)
}

func ValidProvider(p string) bool { return p == "claude" || p == "codex" }

// Changing server must never send the old server's token to the new address.
func (c *Config) SetServer(raw string) error {
	normalized, err := NormalizeURL(raw)
	if err != nil {
		return err
	}
	if normalized != c.Server {
		c.Token = ""
	}
	c.Server = normalized
	return nil
}

// Public connections require HTTPS; plaintext is restricted to literal private
// addresses and localhost, matching the mobile clients' pairing policy.
func NormalizeURL(raw string) (string, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.Opaque != "" {
		return "", errors.New("server must be an HTTP(S) base URL without credentials, query, or fragment")
	}
	if u.Scheme != "https" {
		ip := net.ParseIP(u.Hostname())
		local := u.Hostname() == "localhost" || (ip != nil && (ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast()))
		if u.Scheme != "http" || !local {
			return "", errors.New("use HTTPS for servers outside your LAN")
		}
	}
	u.Path = strings.TrimRight(u.Path, "/")
	u.RawPath = strings.TrimRight(u.RawPath, "/")
	return u.String(), nil
}
