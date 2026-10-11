package bridge

import (
	"bufio"
	"encoding/json"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const pollInterval = 30 * time.Second

// ---------- pricing ----------

// price is dollars per token. Cache reads are usually 0.1x input, but not for
// every model (Opus 5.5 is 0.05x, Fable 5.1 0.025x), so each model says.
type price struct{ in, out, read float64 }

// Cache writes follow Anthropic's standard multipliers on the input price:
// 5-minute = 1.25x, 1-hour = 2x.
const (
	cacheWrite5m = 1.25
	cacheWrite1h = 2.0
)

// mtok builds a price from $/million-token figures.
func mtok(in, out, read float64) price { return price{in / 1e6, out / 1e6, read / 1e6} }

// priceFor maps a model id to its rate. More specific names come first:
// "opus-5-5" must not fall through to "opus".
func priceFor(model string) price {
	m := strings.ToLower(model)
	switch {
	case strings.Contains(m, "haiku"):
		return mtok(1, 5, 0.10)
	case strings.Contains(m, "opus-5-5"), strings.Contains(m, "opus-5.5"):
		return mtok(4, 20, 0.20)
	case strings.Contains(m, "opus"):
		return mtok(5, 25, 0.50)
	case strings.Contains(m, "fable-5-1"), strings.Contains(m, "fable-5.1"):
		return mtok(10, 50, 0.25)
	case strings.Contains(m, "fable"), strings.Contains(m, "mythos"):
		return mtok(10, 50, 1.00)
	case strings.Contains(m, "sonnet-5"), strings.Contains(m, "sonnet5"):
		return mtok(2, 10, 0.20)
	default: // older Sonnets and anything unknown
		return mtok(3, 15, 0.30)
	}
}

// ---------- ~/.claude.json: Anthropic's own plan-limit cache ----------

type limitWindow struct {
	WindowMinutes int    `json:"-"`
	Utilization   int    `json:"utilization"`
	ResetsAt      string `json:"resets_at"`
}

func (w *limitWindow) resetTime() time.Time {
	if w == nil || w.ResetsAt == "" {
		return time.Time{}
	}
	t, err := time.Parse(time.RFC3339, w.ResetsAt)
	if err != nil {
		return time.Time{}
	}
	if w.WindowMinutes > 0 {
		return t
	} // Codex reports exact Unix reset times.
	// Anthropic's resets_at wobbles by a second across fetches (12:39:59.9,
	// then 12:40:00.3). Unrounded, each wobble reads as the window rolling
	// over, which re-arms and re-fires every alert. Real resets are on the
	// minute and move by hours, so the nearest minute is the true value.
	return t.Round(time.Minute)
}

type claudeConfig struct {
	CachedUsageUtilization struct {
		FetchedAtMs int64 `json:"fetchedAtMs"` // when Claude Code last fetched usage
		Utilization struct {
			FiveHour *limitWindow `json:"five_hour"`
			SevenDay *limitWindow `json:"seven_day"`
		} `json:"utilization"`
	} `json:"cachedUsageUtilization"`
}

func readClaudeConfig() (claudeConfig, bool) {
	var cfg claudeConfig
	home, err := os.UserHomeDir()
	if err != nil {
		return cfg, false
	}
	data, err := os.ReadFile(filepath.Join(home, ".claude.json"))
	if err != nil {
		return cfg, false
	}
	return cfg, json.Unmarshal(data, &cfg) == nil
}

func loadLimits() (fiveHour, sevenDay *limitWindow, fetchedAt time.Time) {
	cfg, ok := readClaudeConfig()
	if !ok {
		return nil, nil, time.Time{}
	}
	c := cfg.CachedUsageUtilization
	if c.FetchedAtMs > 0 {
		fetchedAt = time.UnixMilli(c.FetchedAtMs)
	}
	return c.Utilization.FiveHour, c.Utilization.SevenDay, fetchedAt
}

// loadCacheFetchedAt is when Claude Code last fetched usage, zero if unknown.
func loadCacheFetchedAt() time.Time {
	_, _, t := loadLimits()
	return t
}

// ---------- ~/.claude/projects/**/*.jsonl: token usage -> $ equivalent ----------

type usage struct {
	InputTokens              int `json:"input_tokens"`
	OutputTokens             int `json:"output_tokens"`
	CacheCreationInputTokens int `json:"cache_creation_input_tokens"`
	CacheReadInputTokens     int `json:"cache_read_input_tokens"`
	CacheCreation            *struct {
		Ephemeral5m int `json:"ephemeral_5m_input_tokens"`
		Ephemeral1h int `json:"ephemeral_1h_input_tokens"`
	} `json:"cache_creation"`
}

type transcriptEntry struct {
	Type      string `json:"type"`
	Timestamp string `json:"timestamp"`
	RequestID string `json:"requestId"`
	Message   *struct {
		ID    string `json:"id"`
		Model string `json:"model"`
		Usage *usage `json:"usage"`
	} `json:"message"`
}

func costOf(u *usage, p price) float64 {
	if u == nil {
		return 0
	}
	cost := float64(u.InputTokens)*p.in + float64(u.OutputTokens)*p.out
	cost += float64(u.CacheReadInputTokens) * p.read
	if u.CacheCreation != nil {
		cost += float64(u.CacheCreation.Ephemeral5m) * p.in * cacheWrite5m
		cost += float64(u.CacheCreation.Ephemeral1h) * p.in * cacheWrite1h
	} else {
		cost += float64(u.CacheCreationInputTokens) * p.in * cacheWrite5m
	}
	return cost
}

// costSince scans every project transcript and returns the total $ equivalent
// of assistant-message token usage at or after each of the given start times.
//
// Claude Code writes one transcript line per content block of a reply, each
// repeating the whole reply's usage, and resumed sessions copy old lines into
// new files. So a reply is counted once, by message id + request id, across
// every file (the same key T3 and ccusage use).
func costSince(starts []time.Time) []float64 {
	totals := make([]float64, len(starts))
	seen := map[string]bool{}
	home, err := os.UserHomeDir()
	if err != nil {
		return totals
	}
	root := filepath.Join(home, ".claude", "projects")
	earliest := starts[0]
	for _, s := range starts[1:] {
		if s.Before(earliest) {
			earliest = s
		}
	}

	filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".jsonl") {
			return nil
		}
		if info, err := d.Info(); err == nil && info.ModTime().Before(earliest) {
			return nil // file hasn't been touched since our oldest window started
		}
		scanFile(path, starts, totals, seen)
		return nil
	})
	return totals
}

func scanFile(path string, starts []time.Time, totals []float64, seen map[string]bool) {
	f, err := os.Open(path)
	if err != nil {
		return
	}
	defer f.Close()

	r := bufio.NewReaderSize(f, 64*1024)
	for {
		line, err := r.ReadString('\n')
		if len(line) > 0 {
			applyLine(line, starts, totals, seen)
		}
		if err != nil {
			if err != io.EOF {
				return
			}
			break
		}
	}
}

func applyLine(line string, starts []time.Time, totals []float64, seen map[string]bool) {
	line = strings.TrimSpace(line)
	if line == "" {
		return
	}
	var e transcriptEntry
	if err := json.Unmarshal([]byte(line), &e); err != nil {
		return
	}
	if e.Type != "assistant" || e.Message == nil || e.Message.Usage == nil {
		return
	}
	ts, err := time.Parse(time.RFC3339, e.Timestamp)
	if err != nil {
		return
	}
	if e.Message.ID != "" || e.RequestID != "" {
		key := e.Message.ID + ":" + e.RequestID
		if seen[key] {
			return
		}
		seen[key] = true
	}
	cost := costOf(e.Message.Usage, priceFor(e.Message.Model))
	for i, start := range starts {
		if !ts.Before(start) {
			totals[i] += cost
		}
	}
}

// ---------- collected snapshot ----------

type snapshot struct {
	provider, sourceError            string
	fiveHour, sevenDay               *limitWindow
	cacheFetchedAt                   time.Time
	costToday, costSession, costWeek float64
}

func gather() snapshot {
	fiveHour, sevenDay, fetchedAt := loadLimits()

	now := time.Now()
	todayStart := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, now.Location())
	sessionStart := now.Add(-5 * time.Hour)
	if t := fiveHour.resetTime(); !t.IsZero() {
		sessionStart = t.Add(-5 * time.Hour)
	}
	weekStart := now.Add(-7 * 24 * time.Hour)
	if t := sevenDay.resetTime(); !t.IsZero() {
		weekStart = t.Add(-7 * 24 * time.Hour)
	}

	totals := costSince([]time.Time{todayStart, sessionStart, weekStart})
	return snapshot{
		fiveHour:       fiveHour,
		sevenDay:       sevenDay,
		cacheFetchedAt: fetchedAt,
		costToday:      totals[0],
		costSession:    totals[1],
		costWeek:       totals[2],
	}
}
