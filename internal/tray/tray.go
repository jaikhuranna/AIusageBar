// Package tray renders the usage API. All collection, authentication with AI
// providers, pairing-code creation, and alert generation belong to the server.
package tray

import (
	"aiusagebar/internal/client"
	"aiusagebar/internal/protocol"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/getlantern/systray"
)

func Run(args []string) error {
	path, err := client.ConfigPath()
	if err != nil {
		return err
	}
	f := flag.NewFlagSet("aiusagebar", flag.ContinueOnError)
	configPath := f.String("config", path, "tray client configuration file (contains its device token)")
	server := f.String("server", "", "server base URL; defaults to saved address or http://127.0.0.1:8765")
	pair := f.String("pair", "", "one-time code from aiusage-server -pair; saves this client and exits")
	token := f.String("token", "", "use an existing server token instead of pairing; saved privately")
	provider := f.String("provider", "", "initial provider: claude or codex (saved)")
	check := f.Bool("check", false, "fetch usage once as JSON without opening the tray")
	if err := f.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return nil
		}
		return err
	}
	if f.NArg() != 0 {
		return errors.New("unexpected positional arguments")
	}
	cfg, err := client.Load(*configPath)
	if err != nil {
		return err
	}
	if *server != "" {
		if err := cfg.SetServer(*server); err != nil {
			return err
		}
	}
	if *token != "" {
		cfg.Token = *token
	}
	if *provider != "" {
		if !client.ValidProvider(*provider) {
			return errors.New("-provider must be claude or codex")
		}
		cfg.Provider = *provider
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if *pair != "" {
		host, _ := os.Hostname()
		cfg, err = client.Pair(ctx, cfg, *pair, host+" Linux tray")
		if err != nil {
			return err
		}
		if err := client.Save(*configPath, cfg); err != nil {
			return err
		}
		fmt.Println("Tray paired. Run aiusagebar to show usage.")
		return nil
	}
	if *server != "" || *token != "" || *provider != "" {
		if err := client.Save(*configPath, cfg); err != nil {
			return err
		}
	}
	api, err := client.New(cfg)
	if err != nil {
		return err
	}
	if *check {
		r := api.Fetch(ctx, cfg.Provider)
		if r.Err != nil {
			return r.Err
		}
		return json.NewEncoder(os.Stdout).Encode(r.Snapshot)
	}
	t := &tray{cfg: cfg, configPath: *configPath, api: api, ctx: ctx}
	systray.Run(t.ready, stop)
	return nil
}

type tray struct {
	cfg                                                                                   client.Config
	configPath                                                                            string
	api                                                                                   *client.Client
	ctx                                                                                   context.Context
	provider, session, week, costToday, costSession, costWeek, status, age, refresh, quit *systray.MenuItem
}

func (t *tray) ready() {
	systray.SetTitle("AI usage …")
	systray.SetTooltip("AIusageBar client")
	systray.SetIcon(icon(-1))
	t.provider = systray.AddMenuItem("Switch provider", "Choose Claude or Codex")
	t.session = systray.AddMenuItem("Primary limit: …", "")
	t.week = systray.AddMenuItem("Secondary limit: …", "")
	t.session.Disable()
	t.week.Disable()
	systray.AddSeparator()
	t.costToday = systray.AddMenuItem("API-cost equivalent today: …", "")
	t.costSession = systray.AddMenuItem("API-cost equivalent this session: …", "")
	t.costWeek = systray.AddMenuItem("API-cost equivalent this week: …", "")
	t.costToday.Disable()
	t.costSession.Disable()
	t.costWeek.Disable()
	systray.AddSeparator()
	t.status = systray.AddMenuItem("Connecting to server…", "")
	t.age = systray.AddMenuItem("Waiting for data", "")
	t.status.Disable()
	t.age.Disable()
	t.refresh = systray.AddMenuItem("Refresh", "Fetch the server's latest usage")
	t.quit = systray.AddMenuItem("Quit", "Quit this client; the server keeps running")
	go func() {
		select {
		case <-t.ctx.Done():
		case <-t.quit.ClickedCh:
		}
		systray.Quit()
	}()
	go t.loop()
}

// One client loop owns selection and rendering, so a late request cannot draw
// one provider's cached values under the other provider's name.
func (t *tray) loop() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	refresh := func() {
		t.render(client.Reading{Err: errors.New("Connecting to server…")})
		t.render(t.api.Fetch(t.ctx, t.cfg.Provider))
	}
	refresh()
	for {
		select {
		case <-t.ctx.Done():
			return
		case <-ticker.C:
			t.render(t.api.Fetch(t.ctx, t.cfg.Provider))
		case <-t.refresh.ClickedCh:
			t.render(t.api.Fetch(t.ctx, t.cfg.Provider))
		case <-t.provider.ClickedCh:
			if t.cfg.Provider == "codex" {
				t.cfg.Provider = "claude"
			} else {
				t.cfg.Provider = "codex"
			}
			if err := client.Save(t.configPath, t.cfg); err != nil {
				log.Print("Could not save tray provider selection")
			}
			refresh()
		}
	}
}

func (t *tray) render(r client.Reading) {
	if t.ctx.Err() != nil {
		return
	}
	name, other := "Claude", "Codex"
	if t.cfg.Provider == "codex" {
		name, other = "Codex", "Claude"
	}
	t.provider.SetTitle("Show " + other)
	s := protocol.Snapshot{Provider: t.cfg.Provider}
	if r.Snapshot != nil {
		s = *r.Snapshot
	}
	fh, wk := "--", "--"
	if s.FiveHour != nil {
		fh = fmt.Sprintf("%d%%", s.FiveHour.Utilization)
	}
	if s.SevenDay != nil {
		wk = fmt.Sprintf("%d%%", s.SevenDay.Utilization)
	}
	status := "Connected to server"
	if s.Host != "" {
		status = "Connected to " + s.Host
	}
	if r.Err != nil {
		status = r.Err.Error()
	} else if s.SourceError != "" {
		status = s.SourceError
	}
	age := "Waiting for data"
	stale := r.Err != nil || s.SourceError != ""
	if at := r.SourceTime(); !at.IsZero() {
		d := max(time.Duration(0), time.Since(at))
		age = "Source updated " + d.Round(time.Second).String() + " ago"
		stale = stale || d > time.Hour
	}
	title := fmt.Sprintf("%s %s %s · %s %s", name, windowLabel(s.FiveHour, 300), fh, windowLabel(s.SevenDay, 10080), wk)
	if stale && r.Snapshot != nil {
		title += " · stale"
	}
	systray.SetTitle(title)
	systray.SetTooltip(strings.Join([]string{title, status, age}, " | "))
	percent := maxPercent(s)
	if stale || (s.FiveHour == nil && s.SevenDay == nil) {
		percent = -1
	}
	systray.SetIcon(icon(percent))
	t.session.SetTitle(name + " " + windowLabel(s.FiveHour, 300) + " limit: " + fmtLimit(s.FiveHour))
	t.week.SetTitle(name + " " + windowLabel(s.SevenDay, 10080) + " limit: " + fmtLimit(s.SevenDay))
	t.status.SetTitle(status)
	t.age.SetTitle(age)
	if r.Snapshot == nil || !s.CostAvailable {
		t.costToday.SetTitle(name + " API-cost equivalent: unavailable")
		t.costSession.Hide()
		t.costWeek.Hide()
		return
	}
	t.costSession.Show()
	t.costWeek.Show()
	t.costToday.SetTitle("API-cost equivalent today: " + money(s.CostToday))
	t.costSession.SetTitle("API-cost equivalent this session: " + money(s.CostSession))
	t.costWeek.SetTitle("API-cost equivalent this week: " + money(s.CostWeek))
}
