# CLAUDE.md — AIusageBar

## Goal

Make Claude Code and Codex usage visible at a glance, wherever you are.

AIusageBar has two independent Go programs. `aiusage-server` is the headless
server: it reads provider usage, owns tokens and alert state, and serves the
shared API. `aiusagebar` is a GNOME tray client, with the same HTTP boundary as
the Android, Wear OS and iOS clients. Closing a client must never stop the server.

## Layout

- `cmd/aiusage-server/main.go` — server entry point and process cancellation.
- `internal/bridge/` — server implementation and its tests:
  - `sources.go` — Claude cache/transcript readers, pricing, collected snapshot.
  - `codex.go` — official CLI app-server quota reader; no inference.
  - `hub.go` — optional CLIProxyAPI hub source (`-cliproxy-url`): per-account Claude limits for `claude`/`claude2`.
  - `pool.go` — `/pool`: pooled limit across a family's accounts (totals, burn,
    7-day projection with reset step-ups) and its persisted history.
  - `monitor.go` — one sampler, cached snapshots and the alert state machine.
  - `server.go` — `/usage`, `/pool`, `/events`, `/pair`, `/healthz`, opt-in `/share`.
  - `control.go`, `pair.go` — local socket minting for `aiusage-server -pair`.
  - `state.go` — existing `$XDG_STATE_HOME/aiusagebar/state.json` (0600).
  - `refresh.go`, `mdns.go` — source refresh and LAN discovery.
- `internal/protocol/` — shared wire types (`usage.go` snapshot, `pool.go` `/pool`); no server or GUI dependencies.
- `internal/client/` — HTTP client, provider caches, one-time-code pairing,
  private tray config. Never reads provider/server files or starts their CLIs.
- `internal/tray/`, root `main.go` — Linux tray renderer and client flags.
  The tray stores only its device token/settings in the user config directory.
  `format.go` holds every label, menu line and the icon as pure, tested
  functions; `tray.go` only wires them to systray. It polls all three
  providers (`claude`, `claude2`, `codex`); the saved choice (`pool` or one
  provider, `client.Pool`/`ValidChoice`) only picks what the panel label shows.
- `deploy/aiusage-server.service` — independent user service example.
- `aiusagebar.desktop` — tray-only desktop autostart entry.
- `scripts/setup-tunnel.sh` — independent Cloudflare tunnel/server services.
- `android/`, `wearos/`, `ios/` — companion clients, with their own `CLAUDE.md`.
  Any APK built for checking on a device gets a link: its GitHub release, or if
  it isn't released, served over the home network and the tailnet (see
  `android/CLAUDE.md` → Build and install).
  Pure widget rules remain in step across Android/Wear and `ios/Core/`.
- `docs/screenshots/` — app-only screenshots; exclude deployment URLs/addresses.
- `docs/architecture.svg` — the README's diagram, a hand-written SVG (light and
  dark via `prefers-color-scheme`) rather than Mermaid, which many viewers don't render.
- `CLAUDE.local.md` — gitignored live deployment details; never publish them.

## Build and run

```sh
CGO_ENABLED=0 go build -o aiusage-server ./cmd/aiusage-server
./aiusage-server -serve :8765
./aiusage-server -serve :8765 -public-url https://usage.example.com
./aiusage-server -pair             # mint a client pairing code locally

go build -o aiusagebar .           # tray needs gcc + GTK/AppIndicator headers
./aiusagebar -server http://127.0.0.1:8765 -pair 123456
./aiusagebar                       # HTTP-only tray client
./aiusagebar -provider codex -check # one request without opening the tray

CGO_ENABLED=0 go test ./internal/bridge ./internal/client ./internal/protocol
go test -race ./...                # full tree; tray requires GTK headers
python3 scripts/check-split.py ./aiusage-server ./aiusagebar  # isolated process integration
```

## Live deployment

This machine runs the bridge publicly through **Tailscale Funnel**. The live URL
and the ops commands (restart, logs, pairing a new client) are in
`CLAUDE.local.md`, which is **gitignored on purpose**: this repo is public, and
a published URL just invites probing. Never copy the URL into a tracked file.
Anything new that needs usage data should follow README → **Writing another
client**, and get its own token by pairing.

When the deployment changes (URL, tunnel, flags, how it starts), update
`CLAUDE.local.md` and README in the same change.

## Conventions and gotchas

- **Server/client separation is strict.** The server builds with CGO disabled and
  never imports the tray. The tray imports the client/protocol packages only,
  reads no server state and calls no provider CLIs. Keep pairing codes local to
  the server; a tray is an ordinary paired HTTP client.
- **Provider selection is additive.** `/usage`, `/events` and `/share` accept
  `provider=claude|claude2|codex`; omitted means Claude. Keep caches, ETags, alert
  arming and client comeback state isolated. Codex and `claude2` costs are
  unavailable (transcripts are pooled; the cost stays on `claude`).
  `claude2` exists only in hub mode (`claude2` without a hub is a `source_error`);
  more accounts mean one more name in `claudeProviders` (`hub.go`).
  Honor `window_minutes`, `source_error` and `cache_fetched_at` on every client.
- **`/pool` is a family view, not a provider.** `family=claude|codex`; Claude is
  every hub account (just `claude` without a hub), Codex a pool of one. Math is
  in account-percent (one account = 100) and lives in pure functions in
  `pool.go` (`buildPool`, `projectPool`, `poolBurn`) so it tests without a
  clock. History is appended by the poll loop and by `/pool` itself, never
  denser than 5 min, 14-day retention, in
  `$XDG_STATE_HOME/aiusagebar/pool-history-<family>.jsonl` (0600). Accounts
  with no data are left out (`accounts: 0` + `source_error` when none have any).
  Its ETag hashes inputs plus a 15-minute bucket, never the body (the projection
  is re-anchored at every request).
- **Data sources are read-only and local.** Never write to `~/.claude.json` or
  the transcript files; they belong to Claude Code. To get fresher limits,
  `internal/bridge/refresh.go` runs the official CLI's local `/usage` command, which makes
  Claude Code fetch and rewrite its own cache. The bridge never calls
  Anthropic itself, and never uses the OAuth token (Consumer Terms).
  **Hub mode is the one exception to "local files":** with `-cliproxy-url` and
  `-cliproxy-key-file`, limits come from a local CLIProxyAPI hub (`hub.go`),
  polled at most every 5 min, and the `claude -p /usage` refresher is skipped.
  The server still never calls Anthropic or holds an OAuth token: the *hub*
  calls Anthropic's usage endpoint on each account's token. The management key
  is read from the key file on each poll and never logged or put in an error.
- **`GET /usage` is the refresh.** A stale cache (older than
  `-refresh-min-age`, default 1m) is refreshed before the answer, at most once
  per that interval, with concurrent requests sharing it. Don't add a separate
  refresh endpoint; clients shouldn't need to know.
- **Reset times are rounded to the minute** (`resetTime`). Anthropic's
  `resets_at` wobbles by a second between fetches; unrounded, every wobble was
  a fake `window_reset` that re-armed and re-fired the alerts.
- **The cost figure is an estimate of value, not a bill.** Subscription plans
  expose no dollar amount. Don't let UI (or an alert) imply a charge.
- **The bridge is plain HTTP with no transport security.** Off the LAN it is
  reached only through a TLS tunnel (Tailscale Funnel or Cloudflare), set up per `README.md`, with
  `-public-url` so nothing is readable before a device pairs.
- **Never trust "came from loopback".** A tunnel or reverse proxy on this
  machine makes internet traffic arrive from `127.0.0.1`. Anything privileged
  (minting pairing codes) goes over the Unix control socket, never a TCP route.
- **The bridge is the only writer of alert events.**
  Watch and phone render events; they never derive them. Keep threshold logic
  and hysteresis in `evaluateLocked` and nowhere else, or one crossing turns
  into two notifications that disagree.
- **One sampler.** Adding a second caller of `gather()` means walking every
  transcript twice. Server handlers read `monitor.latest()`; clients use the HTTP API. The one
  exception is `freshen()`, which resamples once right after a refresh so the
  waiting client gets the new numbers.
- **The ETag excludes `generated_at` and `resets_in_sec`** on purpose, so an
  idle bridge answers `304`. Clients date their data from the fetch, not from
  `generated_at`.
- **Pairing one device closes the bridge** to unauthenticated requests. That is
  intentional, and documented in `README.md`; don't "fix" it by falling back to
  open access.
- Pricing in `internal/bridge/sources.go` (`priceFor`) is hand-maintained per model; update the date noted in
  `README.md` when it changes. Check each model's cache-read rate: it isn't
  always 0.1x input. T3 caches a LiteLLM price table at
  `~/.t3/userdata/usage-model-rates.json` that's handy for checking (read only).
- **Count each reply once.** Transcripts repeat a reply's usage on every
  content-block line; `costSince` dedupes by `message.id:requestId` across all
  files. Removing that roughly doubles every cost figure.
- **getlantern/systray re-shows a hidden item on every update** (its Linux C
  code calls `gtk_widget_show` in each title/check change). The tray's `set`
  and `check` helpers re-hide; use them, not `SetTitle`/`Check` directly.
- Keep `README.md` and this file in sync when flags, sources, or the wire format
  change.

## License

PolyForm Noncommercial 1.0.0 (`LICENSE.md`), covering the whole repo including
`wearos/`, `android/` and `ios/`. Keep `LICENSE.md` verbatim: only the `Required Notice:` line at the
top is ours. Anything added must be compatible with noncommercial-only
licensing, so no copyleft code copied in.

## Repo status

**This repo is public** (`github.com/jaikhuranna/AIusageBar`), so commits and
pushes are not automatic — ask before committing or pushing.
