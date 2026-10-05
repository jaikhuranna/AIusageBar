package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"time"
)

// Codex owns authentication. Only initialize and account/rateLimits/read are
// sent to its documented stdio API; no inference, login, or credential reads.
type codexSource struct {
	mu          sync.Mutex
	read        func(context.Context) (snapshot, error)
	lastAttempt time.Time
	cached      snapshot
}

func newCodexSource(bin string) *codexSource {
	if bin == "" {
		bin, _ = exec.LookPath("codex")
		if bin == "" {
			home, _ := os.UserHomeDir()
			bin = filepath.Join(home, ".local", "bin", "codex")
		}
	}
	return &codexSource{read: func(ctx context.Context) (snapshot, error) { return readCodex(ctx, bin) }}
}

func (c *codexSource) sample() snapshot {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.lastAttempt.IsZero() && time.Since(c.lastAttempt) < time.Minute {
		return c.cached
	}
	c.lastAttempt = time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	s, err := c.read(ctx)
	if err != nil {
		// Never log CLI output: upstream errors may include account details.
		c.cached.provider = "codex"
		c.cached.sourceError = "Codex unavailable. Install the Codex CLI and sign in with ChatGPT on the desktop, then refresh."
	} else {
		s.provider = "codex"
		s.cacheFetchedAt = time.Now()
		c.cached = s
	}
	return c.cached
}

func readCodex(ctx context.Context, bin string) (snapshot, error) {
	cmd := exec.CommandContext(ctx, bin, "app-server")
	cmd.WaitDelay = time.Second
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return snapshot{}, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		stdin.Close()
		return snapshot{}, err
	}
	if err = cmd.Start(); err != nil {
		stdin.Close()
		return snapshot{}, err
	}
	// A launcher can leave descendants holding the pipes after cancellation.
	// Closing our readers bounds the entire RPC, including that case.
	stopClose := context.AfterFunc(ctx, func() { _ = stdout.Close(); _ = stdin.Close() })
	defer stopClose()
	defer func() { stdin.Close(); _ = cmd.Process.Kill(); _ = cmd.Wait() }()
	scanner := bufio.NewScanner(stdout)
	scanner.Buffer(make([]byte, 4096), 4*1024*1024)
	enc := json.NewEncoder(stdin)
	if err = enc.Encode(map[string]any{"id": 1, "method": "initialize", "params": map[string]any{"clientInfo": map[string]string{"name": "aiusagebar", "version": "1.0"}}}); err != nil {
		return snapshot{}, err
	}
	if _, err = codexReply(scanner, 1); err != nil {
		return snapshot{}, err
	}
	if err = enc.Encode(map[string]any{"method": "initialized"}); err != nil {
		return snapshot{}, err
	}
	if err = enc.Encode(map[string]any{"id": 2, "method": "account/rateLimits/read"}); err != nil {
		return snapshot{}, err
	}
	body, err := codexReply(scanner, 2)
	if err != nil {
		return snapshot{}, err
	}
	return parseCodexLimits(body)
}

func codexReply(scanner *bufio.Scanner, id int) (json.RawMessage, error) {
	for scanner.Scan() {
		var msg struct {
			ID     *int            `json:"id"`
			Result json.RawMessage `json:"result"`
			Error  json.RawMessage `json:"error"`
		}
		if err := json.Unmarshal(scanner.Bytes(), &msg); err != nil {
			return nil, err
		}
		if msg.ID == nil || *msg.ID != id {
			continue
		}
		if len(msg.Error) > 0 && string(msg.Error) != "null" {
			return nil, errors.New("Codex RPC failed")
		}
		if len(msg.Result) == 0 {
			return nil, errors.New("Codex RPC missing result")
		}
		return msg.Result, nil
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	return nil, io.ErrUnexpectedEOF
}

type codexWindow struct {
	Used    *float64 `json:"usedPercent"`
	Minutes int      `json:"windowDurationMins"`
	Reset   int64    `json:"resetsAt"`
}
type codexLimits struct {
	ID        string       `json:"limitId"`
	Primary   *codexWindow `json:"primary"`
	Secondary *codexWindow `json:"secondary"`
}

func parseCodexLimits(body []byte) (snapshot, error) {
	var result struct {
		Limits *codexLimits            `json:"rateLimits"`
		ByID   map[string]*codexLimits `json:"rateLimitsByLimitId"`
	}
	if err := json.Unmarshal(body, &result); err != nil {
		return snapshot{}, err
	}
	limits := result.Limits
	if len(result.ByID) > 0 {
		limits = result.ByID["codex"]
	}
	if limits == nil || (limits.ID != "" && limits.ID != "codex") {
		return snapshot{}, errors.New("no Codex quota")
	}
	s := snapshot{provider: "codex"}
	for i, w := range []*codexWindow{limits.Primary, limits.Secondary} {
		if w == nil {
			continue
		}
		if w.Used == nil || *w.Used < 0 || *w.Used > 100 || w.Minutes <= 0 {
			return snapshot{}, errors.New("invalid Codex quota")
		}
		win := &limitWindow{Utilization: int(math.Floor(*w.Used)), WindowMinutes: w.Minutes}
		if w.Reset > 0 {
			win.ResetsAt = time.Unix(w.Reset, 0).UTC().Format(time.RFC3339)
		}
		// The legacy keys carry primary/secondary roles, not fixed durations.
		// Codex can report, for example, hourly and daily windows.
		if i == 0 {
			s.fiveHour = win
		} else {
			s.sevenDay = win
		}
	}
	if s.fiveHour == nil && s.sevenDay == nil {
		return snapshot{}, errors.New("Codex quota unavailable")
	}
	return s, nil
}
