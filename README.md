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

## Start on login

```sh
cp aiusagebar.desktop ~/.config/autostart/
```

(Edit the `Exec=` path in `aiusagebar.desktop` first if you build/install the
binary somewhere other than this directory.)
