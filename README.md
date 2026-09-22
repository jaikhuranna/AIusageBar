# AIusageBar

GNOME top-bar indicator for [Claude Code](https://claude.com/claude-code) usage. Written in Go.

- Tray icon = colored dot (green/orange/red) by how close you are to your
  worst plan limit. Tray text = `5h NN% · 7d NN%`.
- Click menu shows:
  - **Session (5h)** and **Week (7d)** limit utilization and when each resets
    - read straight from Anthropic's own cached usage data in `~/.claude.json`
      (the same numbers Claude Code itself shows you), so the percentages and
      reset times are exact, not estimated.
  - **API-cost equivalent** for today / this session / this week - what the
    same token usage would have cost on pay-as-you-go API pricing, computed
    from the token counts logged in `~/.claude/projects/**/*.jsonl` using
    current per-model Anthropic pricing (updated 2026-06).
- Refreshes every 30s, or on demand via the "Refresh" menu item.
- Can also serve the same numbers over your LAN (`-serve`) as JSON, for other
  devices on the network.

Subscription plans (Pro/Max) don't expose a dollar limit or a metered
balance - only a percentage of your session/weekly allowance. The cost
figure here is a separate, independent estimate of value, not what you're
being billed.

## Build

```sh
go build -o aiusagebar .
```

Needs Go 1.22+, a C compiler (`gcc`), and GTK/AppIndicator dev headers for
the tray (same class of dependency the old GTK version needed):

```sh
sudo apt install build-essential libgtk-3-dev libayatana-appindicator3-dev
```

## Run

```sh
./aiusagebar
```

## Serve usage over the LAN

The same snapshot the tray shows can be served as JSON, so anything else on the
network can read it:

```sh
./aiusagebar -serve :8765              # tray + JSON bridge
./aiusagebar -headless -serve :8765    # bridge only, no tray
./aiusagebar -serve :8765 -token s3cr3t  # require a shared secret
```

`GET /usage` returns the current snapshot as JSON. On startup the process
logs every LAN address it is reachable on.

```json
{
  "five_hour": {"utilization": 53, "remaining": 47, "resets_at": "2026-09-21T18:20:00Z", "resets_in_sec": 16631},
  "seven_day": {"utilization": 41, "remaining": 59, "resets_at": "2026-09-27T00:00:00Z", "resets_in_sec": 469031},
  "cost_today": 251.02, "cost_session": 25.06, "cost_week": 351.44,
  "generated_at": "2026-09-21T13:43:29Z", "host": "pop-os"
}
```

The bridge is plain HTTP with no transport security. Bind it to a trusted
network, and use `-token` if that network has guests on it.

## Start on login

```sh
cp aiusagebar.desktop ~/.config/autostart/
```

(Edit the `Exec=` path in `aiusagebar.desktop` first if you build/install the
binary somewhere other than this directory.)
