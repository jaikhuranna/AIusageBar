# CLAUDE.md — AIusageBar

## Goal

Make Claude Code usage visible at a glance, wherever you are.

AIusageBar is a GNOME top-bar tray indicator (Go) that reads Anthropic's own
cached plan-limit data and shows how much of the session (5h) and weekly (7d)
allowance is gone, when each resets, and what the same token usage would have
cost on pay-as-you-go API pricing. It also serves that snapshot as JSON — on the
LAN, or from anywhere through a Cloudflare Tunnel — with pairing and alert
events when a limit is about to bite. That bridge is the only data source for
the Wear OS companion (`../AIusageWear`, design rev 3).

The point is answering "can I start this long task right now?" without opening a
terminal.

## Layout

- `main.go` — pricing table, readers for `~/.claude.json` (plan limits) and
  `~/.claude/projects/**/*.jsonl` (token usage → cost equivalent), tray UI.
- `monitor.go` — **the single poll loop.** Samples every 30s, caches the
  snapshot, evaluates alert thresholds, mints pairing codes. Tray and HTTP
  handlers read its cache; nothing else calls `gather()`.
- `server.go` — the `-serve` bridge: `/usage` (with ETag), `/events`, `/pair`,
  `/healthz`. The wire contract is the **Endpoints** table in `README.md`.
- `control.go` — the Unix control socket, the only place pairing codes are
  minted (see gotchas).
- `state.go` — what survives a restart: device tokens, the event ring, and the
  per-threshold arming state, in `$XDG_STATE_HOME/aiusagebar/state.json` (0600).
- `mdns.go` — `_aiusage._tcp` advertisement, shelled out to
  `avahi-publish-service`.
- `pair.go` — the `-pair` client that asks the running bridge for a code.
- `monitor_test.go` — the alert state machine (fire once, hysteresis, re-arm on
  window reset, survive restart) and the pairing rules.
- `bridge_test.go` — the HTTP/socket security rules: no minting over TCP,
  `-public-url` closes an unpaired bridge. `go test ./...`.
- `scripts/setup-tunnel.sh` — one-shot Cloudflare Tunnel setup: its own
  `~/.cloudflared/aiusage.yml`, a user systemd unit, autostart flags.
- `aiusagebar.desktop` — autostart entry.

## Build and run

```sh
go build -o aiusagebar .          # needs Go 1.22+, gcc, GTK + AppIndicator headers
./aiusagebar                      # tray only
./aiusagebar -serve :8765         # tray + LAN JSON bridge
./aiusagebar -headless -serve :8765 -token s3cr3t
./aiusagebar -serve :8765 -public-url https://usage.example.com   # behind a tunnel
./aiusagebar -pair                # print a pairing code for a watch
go test ./...                     # no GTK needed
```

## Conventions and gotchas

- **Data sources are read-only and local.** Never write to `~/.claude.json` or
  the transcript files; they belong to Claude Code.
- **The cost figure is an estimate of value, not a bill.** Subscription plans
  expose no dollar amount. Don't let UI (or an alert) imply a charge.
- **The bridge is plain HTTP with no transport security.** Off the LAN it is
  reached only through a TLS tunnel (Cloudflare), set up per `README.md`, with
  `-public-url` so nothing is readable before a device pairs.
- **Never trust "came from loopback".** A tunnel or reverse proxy on this
  machine makes internet traffic arrive from `127.0.0.1`. Anything privileged
  (minting pairing codes) goes over the Unix control socket, never a TCP route.
- **The bridge is the only writer of alert events.**
  Watch and phone render events; they never derive them. Keep threshold logic
  and hysteresis in `evaluateLocked` and nowhere else, or one crossing turns
  into two notifications that disagree.
- **One sampler.** Adding a second caller of `gather()` means walking every
  transcript twice. Read `monitor.latest()` / `latestRaw()` instead.
- **The ETag excludes `generated_at` and `resets_in_sec`** on purpose, so an
  idle bridge answers `304`. Clients date their data from the fetch, not from
  `generated_at`.
- **Pairing one device closes the bridge** to unauthenticated requests. That is
  intentional, and documented in `README.md`; don't "fix" it by falling back to
  open access.
- Pricing in `priceFor` is hand-maintained per model; update the date noted in
  `README.md` when it changes.
- Keep `README.md` and this file in sync when flags, sources, or the wire format
  change.

## Repo status

**This repo is public** (`github.com/jaikhuranna/AIusageBar`), so commits and
pushes are not automatic — ask before committing or pushing.
