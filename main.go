// AIusageBar - top-bar tray indicator for Claude usage.
//
// Shows Anthropic's own session (5h) and weekly (7d) plan-limit utilization
// and reset times (read from Claude Code's local cache in ~/.claude.json),
// plus what that same usage would have cost on pay-as-you-go API pricing
// (computed from the token counts in ~/.claude/projects/**/*.jsonl).
package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"io"
	"io/fs"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/getlantern/systray"
)

const pollInterval = 30 * time.Second

// ---------- pricing ----------

type price struct{ in, out float64 } // dollars per token

// Cache multipliers are Anthropic's standard formula, applied on top of a
// model's base input price: 5m cache write = 1.25x, 1h cache write = 2x,
// cache read = 0.1x.
const (
	cacheWrite5m = 1.25
	cacheWrite1h = 2.0
	cacheRead    = 0.1
)

func priceFor(model string) price {
	m := strings.ToLower(model)
	switch {
	case strings.Contains(m, "haiku"):
		return price{1.00 / 1e6, 5.00 / 1e6}
	case strings.Contains(m, "opus"):
		return price{5.00 / 1e6, 25.00 / 1e6}
	case strings.Contains(m, "fable"), strings.Contains(m, "mythos"):
		return price{10.00 / 1e6, 50.00 / 1e6}
	case strings.Contains(m, "sonnet-5"), strings.Contains(m, "sonnet5"):
		return price{2.00 / 1e6, 10.00 / 1e6}
	case strings.Contains(m, "sonnet"):
		return price{3.00 / 1e6, 15.00 / 1e6}
	default:
		return price{3.00 / 1e6, 15.00 / 1e6}
	}
}

// ---------- ~/.claude.json: Anthropic's own plan-limit cache ----------

type limitWindow struct {
	Utilization int    `json:"utilization"`
	ResetsAt    string `json:"resets_at"`
}

func (w *limitWindow) resetTime() time.Time {
	if w == nil || w.ResetsAt == "" {
		return time.Time{}
	}
	t, err := time.Parse(time.RFC3339, w.ResetsAt)
	if err != nil {
		return time.Time{}
	}
	return t
}

type claudeConfig struct {
	CachedUsageUtilization struct {
		Utilization struct {
			FiveHour *limitWindow `json:"five_hour"`
			SevenDay *limitWindow `json:"seven_day"`
		} `json:"utilization"`
	} `json:"cachedUsageUtilization"`
}

func loadLimits() (fiveHour, sevenDay *limitWindow) {
	home, err := os.UserHomeDir()
	if err != nil {
		return nil, nil
	}
	data, err := os.ReadFile(filepath.Join(home, ".claude.json"))
	if err != nil {
		return nil, nil
	}
	var cfg claudeConfig
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, nil
	}
	return cfg.CachedUsageUtilization.Utilization.FiveHour, cfg.CachedUsageUtilization.Utilization.SevenDay
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
	Message   *struct {
		Model string `json:"model"`
		Usage *usage `json:"usage"`
	} `json:"message"`
}

func costOf(u *usage, p price) float64 {
	if u == nil {
		return 0
	}
	cost := float64(u.InputTokens)*p.in + float64(u.OutputTokens)*p.out
	cost += float64(u.CacheReadInputTokens) * p.in * cacheRead
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
func costSince(starts []time.Time) []float64 {
	totals := make([]float64, len(starts))
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
		scanFile(path, starts, totals)
		return nil
	})
	return totals
}

func scanFile(path string, starts []time.Time, totals []float64) {
	f, err := os.Open(path)
	if err != nil {
		return
	}
	defer f.Close()

	r := bufio.NewReaderSize(f, 64*1024)
	for {
		line, err := r.ReadString('\n')
		if len(line) > 0 {
			applyLine(line, starts, totals)
		}
		if err != nil {
			if err != io.EOF {
				return
			}
			break
		}
	}
}

func applyLine(line string, starts []time.Time, totals []float64) {
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
	cost := costOf(e.Message.Usage, priceFor(e.Message.Model))
	for i, start := range starts {
		if !ts.Before(start) {
			totals[i] += cost
		}
	}
}

// ---------- tray UI ----------

type snapshot struct {
	fiveHour, sevenDay               *limitWindow
	costToday, costSession, costWeek float64
}

func gather() snapshot {
	fiveHour, sevenDay := loadLimits()

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
		fiveHour:    fiveHour,
		sevenDay:    sevenDay,
		costToday:   totals[0],
		costSession: totals[1],
		costWeek:    totals[2],
	}
}

func money(v float64) string { return fmt.Sprintf("$%.2f", v) }

func fmtLimit(w *limitWindow) string {
	if w == nil {
		return "unknown"
	}
	return fmt.Sprintf("%d%% used, resets %s", w.Utilization, fmtReset(w.resetTime()))
}

func fmtReset(t time.Time) string {
	if t.IsZero() {
		return "at an unknown time"
	}
	d := time.Until(t)
	if d <= 0 {
		return "now"
	}
	h := int(d.Hours())
	m := int(d.Minutes()) % 60
	when := t.Local().Format("Mon 3:04PM")
	if h > 0 {
		return fmt.Sprintf("in %dh%02dm (%s)", h, m, when)
	}
	return fmt.Sprintf("in %dm (%s)", m, when)
}

func maxPercent(s snapshot) int {
	p := 0
	if s.fiveHour != nil && s.fiveHour.Utilization > p {
		p = s.fiveHour.Utilization
	}
	if s.sevenDay != nil && s.sevenDay.Utilization > p {
		p = s.sevenDay.Utilization
	}
	return p
}

// icon renders a small colored dot: green/orange/red by worst limit utilization.
func icon(percent int) []byte {
	var c color.RGBA
	switch {
	case percent >= 80:
		c = color.RGBA{220, 53, 69, 255}
	case percent >= 50:
		c = color.RGBA{255, 152, 0, 255}
	default:
		c = color.RGBA{76, 175, 80, 255}
	}
	const size = 22
	img := image.NewRGBA(image.Rect(0, 0, size, size))
	cx, cy, r := float64(size)/2, float64(size)/2, float64(size)/2-2
	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			dx, dy := float64(x)-cx, float64(y)-cy
			if dx*dx+dy*dy <= r*r {
				img.Set(x, y, c)
			}
		}
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		return nil
	}
	return buf.Bytes()
}

var (
	mon         *monitor
	serving     bool
	stopMDNS    func()
	stopControl func()
	pairingUI   atomic.Bool
)

func main() {
	serveAddr := flag.String("serve", "", "also serve usage JSON over HTTP at this address (e.g. :8765) for the Wear OS app")
	token := flag.String("token", "", "optional shared secret required by -serve clients (?token= or Authorization: Bearer)")
	headless := flag.Bool("headless", false, "run the -serve bridge only, without a tray icon")
	thresholds := flag.String("thresholds", defaultThreshold, "utilization percentages that raise an alert event, comma separated")
	hysteresis := flag.Int("hysteresis", 5, "points a window must fall back below a threshold before that threshold can fire again")
	mdns := flag.Bool("mdns", true, "advertise the bridge on the LAN as _aiusage._tcp so the watch can find it")
	pair := flag.Bool("pair", false, "ask the bridge already running on this machine for a pairing code, print it, and exit")
	publicURL := flag.String("public-url", "", "https URL a tunnel (e.g. Cloudflare) serves this bridge at; requires auth on every request and is handed to devices when they pair")
	flag.Parse()

	*publicURL = strings.TrimRight(*publicURL, "/")
	if *publicURL != "" && !strings.HasPrefix(*publicURL, "https://") {
		log.Fatal("-public-url must be https:// - tokens cross the internet on it")
	}

	if *pair {
		if err := requestPairCode(*serveAddr); err != nil {
			log.Fatal(err)
		}
		return
	}

	mon = newMonitor(parseThresholds(*thresholds), *hysteresis)
	mon.publicURL = *publicURL
	mon.onUpdate = func() { render(mon.latestRaw()) }

	if *serveAddr != "" {
		serving = true
		if *mdns {
			stopMDNS = advertise(*serveAddr, *token != "" || *publicURL != "")
		}
		if stop, err := serveControl(mon); err != nil {
			log.Printf("pairing unavailable: %v", err)
		} else {
			stopControl = stop
		}
		go catchSignals()
		if *headless {
			go mon.run()
			log.Fatal(serve(*serveAddr, *token, mon))
		}
		go func() {
			if err := serve(*serveAddr, *token, mon); err != nil {
				log.Printf("usage bridge stopped: %v", err)
			}
		}()
	} else if *headless {
		log.Fatal("-headless needs -serve, e.g. -headless -serve :8765")
	}

	go mon.run()
	systray.Run(onReady, onExit)
}

// catchSignals exists so the avahi advertisement and the control socket don't
// outlive the process.
func catchSignals() {
	c := make(chan os.Signal, 1)
	signal.Notify(c, os.Interrupt, syscall.SIGTERM)
	<-c
	shutdown()
	os.Exit(0)
}

func shutdown() {
	if stopMDNS != nil {
		stopMDNS()
		stopMDNS = nil
	}
	if stopControl != nil {
		stopControl()
		stopControl = nil
	}
}

const pairMenuTitle = "Pair a device\u2026"

var (
	mSession, mWeek                     *systray.MenuItem
	mCostToday, mCostSession, mCostWeek *systray.MenuItem
	mPair, mRefresh, mQuit              *systray.MenuItem
)

func onReady() {
	systray.SetTitle("...")
	systray.SetTooltip("AIusageBar")

	mSession = systray.AddMenuItem("Session (5h): ...", "")
	mSession.Disable()
	mWeek = systray.AddMenuItem("Week (7d): ...", "")
	mWeek.Disable()
	systray.AddSeparator()
	mCostToday = systray.AddMenuItem("API-cost equivalent today: ...", "")
	mCostToday.Disable()
	mCostSession = systray.AddMenuItem("API-cost equivalent this session: ...", "")
	mCostSession.Disable()
	mCostWeek = systray.AddMenuItem("API-cost equivalent this week: ...", "")
	mCostWeek.Disable()
	systray.AddSeparator()
	mPair = systray.AddMenuItem(pairMenuTitle, "Show a one-time code to pair a watch with this bridge")
	mRefresh = systray.AddMenuItem("Refresh", "Refresh now")
	mQuit = systray.AddMenuItem("Quit", "Quit AIusageBar")

	render(mon.latestRaw())
	go loop()
}

func loop() {
	for {
		select {
		case <-mPair.ClickedCh:
			go showPairCode()
		case <-mRefresh.ClickedCh:
			go mon.poll()
		case <-mQuit.ClickedCh:
			systray.Quit()
			return
		}
	}
}

// showPairCode puts a one-time pairing code in the menu and counts it down, so
// the code can be read off the screen while typing it on the watch.
func showPairCode() {
	if !serving {
		mPair.SetTitle(pairMenuTitle + " (needs -serve)")
		return
	}
	if !pairingUI.CompareAndSwap(false, true) {
		return // a code is already on screen
	}
	defer pairingUI.Store(false)

	code := mon.newPairCode()
	deadline := time.Now().Add(pairCodeTTL)
	for {
		left := int(time.Until(deadline).Seconds())
		if left <= 0 {
			break
		}
		mPair.SetTitle(fmt.Sprintf("Pairing code %s \u00b7 %ds", code, left))
		time.Sleep(time.Second)
	}
	mPair.SetTitle(pairMenuTitle)
}

// render paints the tray from the monitor's cached sample. It is called from
// the poll goroutine, and before the menu exists, so it tolerates nil items.
func render(s snapshot) {
	if mSession == nil {
		return
	}
	fh, wk := "--", "--"
	if s.fiveHour != nil {
		fh = fmt.Sprintf("%d%%", s.fiveHour.Utilization)
	}
	if s.sevenDay != nil {
		wk = fmt.Sprintf("%d%%", s.sevenDay.Utilization)
	}
	systray.SetTitle(fmt.Sprintf("5h %s \u00b7 7d %s", fh, wk))
	systray.SetIcon(icon(maxPercent(s)))
	systray.SetTooltip(fmt.Sprintf("Session %s | Week %s | Today's cost equiv %s", fh, wk, money(s.costToday)))

	mSession.SetTitle("Session (5h): " + fmtLimit(s.fiveHour))
	mWeek.SetTitle("Week (7d): " + fmtLimit(s.sevenDay))
	mCostToday.SetTitle("API-cost equivalent today: " + money(s.costToday))
	mCostSession.SetTitle("API-cost equivalent this session: " + money(s.costSession))
	mCostWeek.SetTitle("API-cost equivalent this week: " + money(s.costWeek))
}

func onExit() {
	shutdown()
	log.SetOutput(io.Discard)
}
