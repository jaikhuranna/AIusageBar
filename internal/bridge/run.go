package bridge

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"net/url"
	"strings"
	"time"
)

// Run starts the independently managed usage server, or mints a pairing code.
// The state and control-socket paths are retained so existing clients keep working.
func Run(ctx context.Context, args []string) error {
	f := flag.NewFlagSet("aiusage-server", flag.ContinueOnError)
	addr := f.String("serve", ":8765", "HTTP listen address")
	token := f.String("token", "", "optional shared secret; clients use Authorization: Bearer")
	thresholds := f.String("thresholds", defaultThreshold, "utilization percentages that raise alerts")
	hysteresis := f.Int("hysteresis", 5, "points below a threshold required to re-arm an alert")
	mdns := f.Bool("mdns", true, "advertise the server on the LAN")
	pair := f.Bool("pair", false, "mint a one-time code through the running server's local control socket")
	publicURL := f.String("public-url", "", "public HTTPS tunnel URL; requires authentication from startup")
	refreshAge := f.Duration("refresh-min-age", time.Minute, "minimum age before the official Claude CLI refreshes its usage cache; 0 disables")
	share := f.String("share", "", "origins allowed to read public limits, comma separated, or *")
	codexBin := f.String("codex", "", "path to the official Codex CLI")
	claudeBin := f.String("claude", "", "path to the official Claude CLI")
	if err := f.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return nil
		}
		return err
	}
	if f.NArg() != 0 {
		return fmt.Errorf("unexpected positional arguments")
	}
	*publicURL = strings.TrimRight(*publicURL, "/")
	if *publicURL != "" {
		u, err := url.Parse(*publicURL)
		if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
			return errors.New("-public-url must be an HTTPS URL without credentials, query, or fragment")
		}
	}
	if *pair {
		return requestPairCode(*addr)
	}
	if *addr == "" {
		return errors.New("-serve needs a listen address")
	}
	m := newMonitor(parseThresholds(*thresholds), *hysteresis)
	m.codex = newCodexSource(*codexBin)
	m.publicURL = *publicURL
	m.shareOrigins = parseOrigins(*share)
	m.refresh = newRefresher(*claudeBin, *refreshAge)
	stopControl, err := serveControl(m)
	if err != nil {
		return fmt.Errorf("pairing control socket: %w", err)
	}
	defer stopControl()
	if *mdns {
		stop := advertise(*addr, *token != "" || *publicURL != "")
		defer stop()
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	go m.run(ctx)
	return serve(ctx, *addr, *token, m)
}
