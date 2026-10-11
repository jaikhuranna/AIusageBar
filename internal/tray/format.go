package tray

import (
	"aiusagebar/internal/protocol"
	"bytes"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"time"
)

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

func fmtLimit(w *protocol.Window) string {
	if w == nil {
		return "unknown"
	}
	return fmt.Sprintf("%d%% used, resets %s", w.Utilization, fmtReset(resetTime(w)))
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

// icon renders a small colored dot: green/orange/red by worst limit utilization.
func icon(percent int) []byte {
	var c color.RGBA
	switch {
	case percent < 0:
		c = color.RGBA{128, 128, 128, 255}
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
