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
	provider := f.String("provider", "", "what the top bar shows: pool (both Claude accounts), claude, claude2 or codex (saved)")
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
		if !client.ValidChoice(*provider) {
			return errors.New("-provider must be pool, claude, claude2 or codex")
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
		return checkOnce(ctx, api, cfg.Provider)
	}
	t := &tray{cfg: cfg, configPath: *configPath, api: api, ctx: ctx,
		readings: map[string]client.Reading{}, titles: map[*systray.MenuItem]string{}, visible: map[*systray.MenuItem]bool{}}
	systray.Run(t.ready, stop)
	return nil
}

// checkOnce prints one snapshot, or an array of both Claude accounts for the pool.
func checkOnce(ctx context.Context, api *client.Client, choice string) error {
	if choice != client.Pool {
		r := api.Fetch(ctx, choice)
		if r.Err != nil {
			return r.Err
		}
		return json.NewEncoder(os.Stdout).Encode(r.Snapshot)
	}
	var out []*protocol.Snapshot
	for _, p := range []string{"claude", "claude2"} {
		r := api.Fetch(ctx, p)
		if r.Err != nil {
			return fmt.Errorf("%s: %w", p, r.Err)
		}
		out = append(out, r.Snapshot)
	}
	return json.NewEncoder(os.Stdout).Encode(out)
}

type section struct {
	header, five, week, issue *systray.MenuItem
}

type tray struct {
	cfg        client.Config
	configPath string
	api        *client.Client
	ctx        context.Context

	// Owned by loop: only it reads readings or touches menu items.
	readings map[string]client.Reading
	seen2    bool // the server has delivered limits for a second Claude account
	titles   map[*systray.MenuItem]string
	visible  map[*systray.MenuItem]bool

	sections           map[string]*section
	costHead, costBody *systray.MenuItem
	status, chooser    *systray.MenuItem
	choices            map[string]*systray.MenuItem
	refresh, quit      *systray.MenuItem
	picks              chan string
}

type fetched struct {
	provider string
	reading  client.Reading
}

func (t *tray) ready() {
	systray.SetTitle("AI usage …")
	systray.SetTooltip("AIusageBar client")
	systray.SetIcon(icon([]gauge{{}, {}}))
	t.picks = make(chan string)
	t.sections = map[string]*section{}
	for _, id := range providerIDs {
		// The Claude accounts form one group, so a hidden second account
		// can never leave two separators touching.
		if id == "codex" {
			systray.AddSeparator()
		}
		s := &section{
			header: systray.AddMenuItem(id, "Show this in the top bar"),
			five:   systray.AddMenuItem("", ""),
			week:   systray.AddMenuItem("", ""),
			issue:  systray.AddMenuItem("", ""),
		}
		s.five.Disable()
		s.week.Disable()
		s.issue.Disable()
		s.issue.Hide()
		t.sections[id] = s
		t.forward(s.header, id)
	}
	systray.AddSeparator()
	t.costHead = systray.AddMenuItem("", "")
	t.costBody = systray.AddMenuItem("", "")
	t.costHead.Disable()
	t.costBody.Disable()
	t.costHead.Hide()
	t.costBody.Hide()
	t.status = systray.AddMenuItem("Connecting to server…", "")
	t.status.Disable()
	systray.AddSeparator()
	t.chooser = systray.AddMenuItem("Top bar shows", "Choose what the panel label shows")
	t.choices = map[string]*systray.MenuItem{}
	for _, id := range append([]string{client.Pool}, providerIDs...) {
		t.choices[id] = t.chooser.AddSubMenuItemCheckbox(id, "", false)
		t.forward(t.choices[id], id)
	}
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

func (t *tray) forward(item *systray.MenuItem, choice string) {
	go func() {
		for {
			select {
			case <-t.ctx.Done():
				return
			case <-item.ClickedCh:
				select {
				case t.picks <- choice:
				case <-t.ctx.Done():
					return
				}
			}
		}
	}()
}

// One loop owns the state and all drawing; a separate fetcher keeps a slow
// Claude refresh from blocking clicks. The client keeps per-provider caches,
// so one provider's values can never be drawn under another's name.
func (t *tray) loop() {
	results := make(chan fetched)
	kick := make(chan struct{}, 1)
	go func() {
		for {
			select {
			case <-t.ctx.Done():
				return
			case <-kick:
			}
			for _, id := range providerIDs {
				r := t.api.Fetch(t.ctx, id)
				select {
				case results <- fetched{id, r}:
				case <-t.ctx.Done():
					return
				}
			}
		}
	}()
	poll := func() {
		select {
		case kick <- struct{}{}:
		default: // a round is already queued
		}
	}
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	poll()
	t.render()
	for {
		select {
		case <-t.ctx.Done():
			return
		case <-ticker.C:
			poll()
		case <-t.refresh.ClickedCh:
			poll()
		case f := <-results:
			t.readings[f.provider] = f.reading
			if f.provider == "claude2" && f.reading.Snapshot != nil &&
				(f.reading.Snapshot.FiveHour != nil || f.reading.Snapshot.SevenDay != nil) {
				t.seen2 = true
			}
		case p := <-t.picks:
			if p != t.cfg.Provider {
				t.cfg.Provider = p
				if err := client.Save(t.configPath, t.cfg); err != nil {
					log.Print("Could not save tray provider selection")
				}
			}
		}
		t.render()
	}
}

// set skips unchanged titles, so an open menu doesn't flicker every poll.
func (t *tray) set(item *systray.MenuItem, title string) {
	if t.titles[item] != title {
		t.titles[item] = title
		item.SetTitle(title)
		t.rehide(item)
	}
}

// check restates a check mark; GTK flips one on click by itself.
func (t *tray) check(item *systray.MenuItem, on bool) {
	if on {
		item.Check()
	} else {
		item.Uncheck()
	}
	t.rehide(item)
}

// systray's Linux backend shows an item on every update, hidden or not.
func (t *tray) rehide(item *systray.MenuItem) {
	if v, ok := t.visible[item]; ok && !v {
		item.Hide()
	}
}

func (t *tray) show(item *systray.MenuItem, on bool) {
	if v, ok := t.visible[item]; ok && v == on {
		return
	}
	t.visible[item] = on
	if on {
		item.Show()
	} else {
		item.Hide()
	}
}

func (t *tray) render() {
	if t.ctx.Err() != nil {
		return
	}
	now := time.Now()
	v := view{entries: map[string]entry{}, two: t.seen2, pick: t.cfg.Provider}
	for id, r := range t.readings {
		v.entries[id] = entryOf(r, now)
	}
	label, gauges := v.panel()
	systray.SetTitle(label)
	systray.SetIcon(icon(gauges))
	status := v.status(now)
	systray.SetTooltip(label + " | " + status)

	for _, id := range providerIDs {
		s := t.sections[id]
		visible := v.visible(id)
		five, week, issue := v.rows(id, now)
		t.set(s.header, v.header(id))
		t.set(s.five, five)
		t.set(s.week, week)
		t.set(s.issue, issue)
		t.show(s.header, visible)
		t.show(s.five, visible)
		t.show(s.week, visible && week != "")
		t.show(s.issue, visible && issue != "")
	}
	head, body, ok := v.cost()
	t.set(t.costHead, head)
	t.set(t.costBody, body)
	t.show(t.costHead, ok)
	t.show(t.costBody, ok)
	t.set(t.status, status)

	shown := v.shown()
	for id, item := range t.choices {
		t.set(item, v.name(id))
		t.show(item, v.visible(id))
		t.check(item, id == shown)
	}
	t.set(t.chooser, "Top bar shows: "+v.name(shown))
}
