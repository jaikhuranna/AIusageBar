# CLAUDE.md — AIusageBar

## Goal

Make Claude Code usage visible at a glance, wherever you are.

AIusageBar is a GNOME top-bar tray indicator (Go) that reads Anthropic's own
cached plan-limit data and shows how much of the session (5h) and weekly (7d)
allowance is gone, when each resets, and what the same token usage would have
cost on pay-as-you-go API pricing. It also serves that snapshot as JSON over the
LAN so other devices on the network can show the same numbers.

The point is answering "can I start this long task right now?" without opening a
terminal.

## Layout

- `main.go` — pricing table, readers for `~/.claude.json` (plan limits) and
  `~/.claude/projects/**/*.jsonl` (token usage → cost equivalent), tray UI.
- `server.go` — the `-serve` JSON bridge (`GET /usage`), optional shared token.
- `aiusagebar.desktop` — autostart entry.

## Build and run

```sh
go build -o aiusagebar .          # needs Go 1.22+, gcc, GTK + AppIndicator headers
./aiusagebar                      # tray only
./aiusagebar -serve :8765         # tray + LAN JSON bridge
./aiusagebar -headless -serve :8765 -token s3cr3t
```

## Conventions and gotchas

- **Data sources are read-only and local.** Never write to `~/.claude.json` or
  the transcript files; they belong to Claude Code.
- **The cost figure is an estimate of value, not a bill.** Subscription plans
  expose no dollar amount. Don't let UI (or an alert) imply a charge.
- **The bridge is plain HTTP with no transport security**, bound to all
  interfaces. It is LAN-only by design. Anything that widens its reach needs TLS
  and revocable per-device tokens first.
- Pricing in `priceFor` is hand-maintained per model; update the date noted in
  `README.md` when it changes.
- Keep `README.md` and this file in sync when flags, sources, or the wire format
  change.

## Repo status

**This repo is public** (`github.com/jaikhuranna/AIusageBar`), so commits and
pushes are not automatic — ask before committing or pushing.
