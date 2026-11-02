package tray

import (
	"aiusagebar/internal/client"
	"aiusagebar/internal/protocol"
	"bytes"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"strings"
	"time"
	"unicode/utf8"
)

// providerIDs is the menu order. "claude2" is the hub's second subscription;
// it stays out of sight until the server has delivered limits for it.
var providerIDs = []string{"claude", "claude2", "codex"}

// staleAfter matches the other clients: older numbers are drawn dimmed.
const staleAfter = time.Hour

// entry is one provider's latest reading, reduced to what the tray draws.
type entry struct {
	snap    *protocol.Snapshot
	err     error         // the last fetch failed; snap may still be cached data
	age     time.Duration // since the provider supplied the numbers
	fetched time.Time
}

func entryOf(r client.Reading, now time.Time) entry {
	e := entry{snap: r.Snapshot, err: r.Err, fetched: r.FetchedAt}
	if at := r.SourceTime(); !at.IsZero() {
		e.age = max(0, now.Sub(at))
	}
	return e
}

func (e entry) stale() bool {
	return e.snap != nil && (e.err != nil || e.snap.SourceError != "" || e.age > staleAfter)
}

func (e entry) hasLimits() bool {
	return e.snap != nil && (e.snap.FiveHour != nil || e.snap.SevenDay != nil)
}

// binding is the utilization of whichever window runs out first: an account
// at 10% of its 5h window but 100% of its week is still blocked.
func (e entry) binding() (int, bool) {
	if !e.hasLimits() {
		return 0, false
	}
	return maxPercent(*e.snap), true
}

// view is what the tray shows for a set of readings.
type view struct {
	entries map[string]entry
	two     bool   // both Claude accounts are known to the server
	pick    string // client.Pool or a provider id
}

// shown resolves the saved choice: the pool needs a second account.
func (v view) shown() string {
	if v.pick == client.Pool && !v.two {
		return "claude"
	}
	return v.pick
}

func (v view) name(id string) string {
	switch id {
	case "claude":
		if v.two {
			return "Claude 1"
		}
		return "Claude"
	case "claude2":
		return "Claude 2"
	case "codex":
		return "Codex"
	case client.Pool:
		return "Both Claude accounts"
	}
	return id
}

// short is the panel's prefix; with two accounts "Claude 1" would crowd it.
func (v view) short(id string) string {
	switch {
	case id == "claude" && v.two:
		return "C1"
	case id == "claude2":
		return "C2"
	}
	return v.name(id)
}

// visible hides the second account, and the pool, from single-account servers.
func (v view) visible(id string) bool {
	switch id {
	case client.Pool:
		return v.two
	case "claude2":
		return v.two || v.pick == "claude2"
	}
	return true
}

// gauge is one bar of the panel icon.
type gauge struct {
	percent int
	known   bool
	stale   bool
}

// panel returns the top-bar label and the two icon bars, which always stand
// for the label's two numbers in the same left-to-right order.
//
//	pool:   "Claude 42 · 17%"         each account's binding limit
//	single: "C1 5h 42% · 7d 61%"      one provider's two windows
func (v view) panel() (string, []gauge) {
	for _, e := range v.entries {
		if errors.Is(e.err, client.ErrUnpaired) {
			return "AI usage · not paired", []gauge{{}, {}}
		}
	}
	id := v.shown()
	var label string
	var gauges []gauge
	var ids []string
	if id == client.Pool {
		ids = []string{"claude", "claude2"}
		nums := make([]string, 2)
		for i, p := range ids {
			e := v.entries[p]
			n, ok := e.binding()
			nums[i] = "--"
			if ok {
				nums[i] = fmt.Sprint(n)
			}
			gauges = append(gauges, gauge{n, ok, e.stale()})
		}
		label = "Claude " + nums[0] + " · " + nums[1] + "%"
	} else {
		ids = []string{id}
		e := v.entries[id]
		var five, week *protocol.Window
		if e.snap != nil {
			five, week = e.snap.FiveHour, e.snap.SevenDay
		}
		label = fmt.Sprintf("%s %s %s · %s %s", v.short(id), windowLabel(five, 300), percent(five), windowLabel(week, 10080), percent(week))
		for _, w := range []*protocol.Window{five, week} {
			g := gauge{known: w != nil, stale: e.stale()}
			if w != nil {
				g.percent = w.Utilization
			}
			gauges = append(gauges, g)
		}
	}
	for _, p := range ids {
		if v.entries[p].stale() {
			return label + " · stale", gauges
		}
	}
	return label, gauges
}

func percent(w *protocol.Window) string {
	if w == nil {
		return "--"
	}
	return fmt.Sprintf("%d%%", w.Utilization)
}

// header names a menu section, with the subscription's email when known.
func (v view) header(id string) string {
	h := v.name(id)
	if e := v.entries[id]; e.snap != nil && e.snap.Account != "" {
		h += " — " + ellipsize(e.snap.Account, 36)
	}
	return h
}

// rows returns a section's two limit lines and an optional problem line.
func (v view) rows(id string, now time.Time) (five, week, issue string) {
	e := v.entries[id]
	if !e.hasLimits() {
		switch {
		case e.snap != nil && e.snap.SourceError != "":
			return "⚠ " + e.snap.SourceError, "", ""
		case e.err != nil:
			return describe(e.err), "", ""
		}
		return "Waiting for data…", "", ""
	}
	five = limitRow(e.snap.FiveHour, 300, now)
	week = limitRow(e.snap.SevenDay, 10080, now)
	switch {
	case e.snap.SourceError != "":
		issue = "⚠ " + e.snap.SourceError
	case e.age > staleAfter:
		issue = "⚠ Last updated " + ago(e.age)
	}
	return five, week, issue
}

// limitRow keeps the meter first so the bars line up in a proportional font.
//
//	▰▰▰▰▱▱▱▱▱▱  42%  5h · resets in 2h 13m (3:40 PM)
func limitRow(w *protocol.Window, fallbackMinutes int, now time.Time) string {
	label := windowLabel(w, fallbackMinutes)
	if w == nil {
		return meter(0, false) + "  --  " + label + " · unknown"
	}
	reset := "reset time unknown"
	if t := resetTime(w); !t.IsZero() {
		reset = "resets " + resetPhrase(t, now)
	}
	return fmt.Sprintf("%s  %d%%  %s · %s", meter(w.Utilization, true), w.Utilization, label, reset)
}

// meter draws ten segments; any use at all lights the first one.
func meter(p int, known bool) string {
	const n = 10
	lit := 0
	if known {
		lit = min(n, max(0, (p*n+50)/100))
		if p > 0 && lit == 0 {
			lit = 1
		}
	}
	return strings.Repeat("▰", lit) + strings.Repeat("▱", n-lit)
}

// cost is the pooled API-value estimate, which the server reports on "claude".
func (v view) cost() (head, body string, ok bool) {
	e := v.entries["claude"]
	if e.snap == nil || !e.snap.CostAvailable {
		return "", "", false
	}
	head = "API-cost equivalent (an estimate, not a bill)"
	if v.two {
		head = "API-cost equivalent, both accounts (an estimate)"
	}
	body = money(e.snap.CostToday) + " today · " + money(e.snap.CostSession) + " session · " + money(e.snap.CostWeek) + " week"
	return head, body, true
}

// status is the connection line at the foot of the menu.
func (v view) status(now time.Time) string {
	var host string
	var last time.Time
	for _, id := range providerIDs {
		e := v.entries[id]
		if e.err != nil && (id != "claude2" || v.two) {
			if e.snap != nil {
				return describe(e.err) + " · showing last known values"
			}
			return describe(e.err)
		}
		if e.snap != nil && host == "" {
			host = e.snap.Host
		}
		if e.fetched.After(last) {
			last = e.fetched
		}
	}
	if last.IsZero() {
		return "Connecting to server…"
	}
	s := "Connected"
	if host != "" {
		s += " to " + host
	}
	return s + " · checked " + ago(now.Sub(last))
}

func describe(err error) string {
	switch {
	case errors.Is(err, client.ErrUnpaired):
		return "Not paired: run aiusagebar -server URL -pair CODE"
	case errors.Is(err, client.ErrUnavailable):
		return "Server unavailable"
	}
	return err.Error()
}

func ago(d time.Duration) string {
	switch {
	case d < 10*time.Second:
		return "just now"
	case d < time.Minute:
		return fmt.Sprintf("%ds ago", int(d.Seconds()))
	case d < time.Hour:
		return fmt.Sprintf("%dm ago", int(d.Minutes()))
	case d < 48*time.Hour:
		return fmt.Sprintf("%dh ago", int(d.Hours()))
	}
	return fmt.Sprintf("%dd ago", int(d.Hours()/24))
}

func ellipsize(s string, n int) string {
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	return string([]rune(s)[:n-1]) + "…"
}

func resetTime(w *protocol.Window) time.Time {
	if w == nil {
		return time.Time{}
	}
	t, _ := time.Parse(time.RFC3339, w.ResetsAt)
	return t
}

func windowLabel(w *protocol.Window, fallbackMinutes int) string {
	minutes := fallbackMinutes
	if w != nil && w.WindowMinutes > 0 {
		minutes = w.WindowMinutes
	}
	if minutes%1440 == 0 {
		return fmt.Sprintf("%dd", minutes/1440)
	}
	if minutes%60 == 0 {
		return fmt.Sprintf("%dh", minutes/60)
	}
	return fmt.Sprintf("%dm", minutes)
}

func money(v float64) string { return fmt.Sprintf("$%.2f", v) }

// resetPhrase: "in 37m (3:40 PM)", "in 2h 13m (3:40 PM)", "in 3d 4h (Tue 9:00 AM)".
func resetPhrase(t, now time.Time) string {
	d := t.Sub(now)
	if d <= 0 {
		return "now"
	}
	d = d.Round(time.Minute)
	var in string
	switch h := int(d.Hours()); {
	case h >= 24:
		in = fmt.Sprintf("in %dd %dh", h/24, h%24)
	case h > 0:
		in = fmt.Sprintf("in %dh %02dm", h, int(d.Minutes())%60)
	default:
		in = fmt.Sprintf("in %dm", max(1, int(d.Minutes())))
	}
	local, today := t.In(now.Location()), now
	clock := local.Format("3:04 PM")
	if local.YearDay() != today.YearDay() || local.Year() != today.Year() {
		clock = local.Format("Mon 3:04 PM")
	}
	return in + " (" + clock + ")"
}

func maxPercent(s protocol.Snapshot) int {
	p := 0
	if s.FiveHour != nil && s.FiveHour.Utilization > p {
		p = s.FiveHour.Utilization
	}
	if s.SevenDay != nil && s.SevenDay.Utilization > p {
		p = s.SevenDay.Utilization
	}
	return p
}

// GNOME palette tones that read on both light and dark top bars.
var (
	calm  = color.RGBA{0x2e, 0xc2, 0x7e, 0xff} // green 4
	warm  = color.RGBA{0xff, 0x78, 0x00, 0xff} // orange 3
	hot   = color.RGBA{0xe0, 0x1b, 0x24, 0xff} // red 3
	faded = color.RGBA{0x9a, 0x99, 0x96, 0xff} // light 5: stale data
	track = color.RGBA{0x80, 0x80, 0x80, 0x70} // translucent, so either panel shows through
)

func severity(p int) color.RGBA {
	switch {
	case p >= 80:
		return hot
	case p >= 50:
		return warm
	}
	return calm
}

const iconSize = 64 // drawn large; the panel scales it down crisply

// icon draws two rounded meters filling from the bottom, one per label number.
func icon(gauges []gauge) []byte {
	img := image.NewRGBA(image.Rect(0, 0, iconSize, iconSize))
	const (
		top, bottom = 6.0, 58.0
		width, gap  = 21.0, 8.0
		radius      = 6.0
		ss          = 4 // supersamples per axis
	)
	left := (iconSize - 2*width - gap) / 2
	for i, g := range gauges[:min(2, len(gauges))] {
		x0 := left + float64(i)*(width+gap)
		x1 := x0 + width
		level := bottom + 1 // nothing filled
		fill := severity(g.percent)
		if g.stale {
			fill = faded
		}
		if g.known && g.percent > 0 {
			h := (bottom - top) * float64(min(100, g.percent)) / 100
			level = bottom - max(h, 5)
		}
		for y := int(top); y < int(bottom)+1; y++ {
			for x := int(x0); x < int(x1)+1; x++ {
				var r, gr, b, a float64
				for sy := 0; sy < ss; sy++ {
					for sx := 0; sx < ss; sx++ {
						px := float64(x) + (float64(sx)+0.5)/ss
						py := float64(y) + (float64(sy)+0.5)/ss
						if !inRounded(px, py, x0, top, x1, bottom, radius) {
							continue
						}
						c := track
						if py >= level {
							c = fill
						}
						ca := float64(c.A) / 255
						r += float64(c.R) * ca
						gr += float64(c.G) * ca
						b += float64(c.B) * ca
						a += ca
					}
				}
				n := float64(ss * ss)
				img.SetRGBA(x, y, color.RGBA{uint8(r / n), uint8(gr / n), uint8(b / n), uint8(255 * a / n)})
			}
		}
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		return nil
	}
	return buf.Bytes()
}

func inRounded(px, py, x0, y0, x1, y1, r float64) bool {
	if px < x0 || px > x1 || py < y0 || py > y1 {
		return false
	}
	cx := min(max(px, x0+r), x1-r)
	cy := min(max(py, y0+r), y1-r)
	dx, dy := px-cx, py-cy
	return dx*dx+dy*dy <= r*r
}
