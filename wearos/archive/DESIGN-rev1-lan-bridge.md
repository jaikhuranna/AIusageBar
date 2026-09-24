# AIusageBar on the wrist — design (rev 1, SUPERSEDED)

> **Superseded by `../DESIGN.md` (rev 3).** Kept for history. Rev 3 returns to
> this revision's data source, the desktop tray's bridge, but reaches it through
> a Cloudflare Tunnel instead of the LAN, and the bridge's wire contract now
> lives in `../../AIusageBar/README.md`.

Status: M0 built in `../AIusageBar`; M1+ abandoned. Scope: a Wear OS companion that shows
Claude session (5h) / weekly (7d) plan-limit utilization, reset countdowns, and
the API-cost equivalent, and that can warn before a limit bites.

## 1. Where the data actually lives

The framing "the watch needs data that lives on my phone" is off by one node.
Nothing lives on a phone here. The numbers exist in exactly one place:

| Field | Source of truth | Who can read it |
| --- | --- | --- |
| 5h / 7d utilization, `resets_at` | `~/.claude.json` → `cachedUsageUtilization` | local processes on the dev machine |
| API-cost equivalent | `~/.claude/projects/**/*.jsonl` token counts × local price table (`main.go:38`) | local processes on the dev machine |

Both are written by Claude Code on a Linux desktop. `aiusagebar -serve` already
exposes a derived snapshot as JSON (`server.go`). So the real topology is
**desktop → (relay?) → watch**, and a phone, if one appears in the design at
all, is a *relay*, not the origin. That distinction drives everything below.

### 1.1 Can the watch just ask Claude directly?

No. This was the first thing to check and it closes off the cleanest-sounding
architecture.

- The **Usage & Cost Admin API** (`GET /v1/organizations/usage_report/messages`,
  `GET /v1/organizations/cost_report`) reports *Console / API-key* consumption
  for an **organization**, authenticated with an Admin API key (`sk-ant-admin01-…`)
  or an `org:admin` OAuth token. The docs state plainly that the Admin API is
  **unavailable for individual accounts**, and workspace-scoped keys don't work.
- Neither endpoint reports the thing this app is about: the **Pro/Max
  subscription rolling windows** (5h session, 7d week) and their `resets_at`.
  Those percentages are a subscription concept, not a billing-ledger concept.
  Anthropic does not publish exact tier figures, and there is no public
  claude.ai endpoint that returns them.
- Claude Code itself gets them by calling an internal endpoint and caching the
  result into `~/.claude.json`. That cache *is* the public interface, in
  practice, and it's local-only.
- Scraping claude.ai from the watch is a dead end twice over: it needs a session
  cookie (an unsupported, ToS-adjacent credential to put on a wrist device), and
  `android.webkit` — including `CookieManager` — is **not available on Wear OS**,
  so cookie handling would be hand-rolled against an unstable private API.

**Decision D1: the watch never talks to Anthropic. The desktop bridge is the
only upstream.** If Anthropic ever ships a first-party subscription-usage
endpoint with a per-user credential, revisit — that would make a genuinely
standalone LTE watch app possible and would delete most of section 3.

## 2. How the industry solves "data lives on another device"

Four recurring patterns, and what each one costs.

**(a) Wearable Data Layer as the only channel.** `DataClient` /
`MessageClient` / `CapabilityClient` over Google Play Services. `DataItem`s are
replicated to nearby nodes and survive disconnection (they re-sync on
reconnect); messages are fire-and-forget RPC. Routing is keyed on **identical
package name + identical signing key** across the phone and watch APKs, which
is also how the pairing "just works" with no user step. Cost: it requires an
Android *phone* app to exist as the other node, and the payload is **not
encrypted by default**.

**(b) Local HTTP over the LAN.** xDrip/Nightscout is the closest prior art to
this project: a phone holds glucose data, and its `XdripWebService` (port 17580)
serves `/sgv.json` so watch faces, widgets and data fields can read it over the
local network **with no internet connection**. xDrip notably ships *both* — Data
Layer (`WatchUpdaterService` → `ListenerService`) **and** the LAN web service —
because neither alone covers every watch/network combination. That dual-path
hedge is the single most useful lesson from prior art.

**(c) Standalone app talking to a self-hosted server.** Home Assistant's Wear OS
app points at the user's own server and renders tiles from it. Onboarding is the
hard part, and HA solves it by doing login on the phone and pushing the
resulting config to the watch.

**(d) Cloud push (FCM).** Google's own guidance for Wear OS is to use **FCM
directly** — there are no Wear-specific messaging APIs — because it cooperates
with Doze instead of fighting it. Cost: a hosted relay and your data leaving
the machine.

## 3. Transport reachability on the wrist

This is where naive designs die. Wear OS network behaviour:

- **When Bluetooth-connected to a phone, the watch's IP traffic is generally
  proxied through the phone.** The app doesn't see this; a plain HTTP call just
  works. Bandwidth is tiny (measured around ~4 KB/s in BT-proxy conditions) and
  the phone's buffering can inflate end-to-end latency to tens of seconds.
- **When the phone is unavailable**, the watch falls back to its own Wi-Fi or
  LTE, hardware permitting. The platform prioritises **battery over bandwidth**,
  so radios may simply be off; high-bandwidth work needs an explicit
  `ConnectivityManager.requestNetwork(TRANSPORT_WIFI)` + `bindProcessToNetwork`,
  released immediately after.
- HTTP/TCP/UDP are all available. `android.webkit` is not.

Consequence for a `192.168.x.x` bridge URL — the reachability matrix:

| Watch state | Path to `http://desktop:8765/usage` | Works? |
| --- | --- | --- |
| BT-proxied via phone, phone on home Wi-Fi | watch → BT → phone → LAN | **yes** (slow, that's fine for a 30 s-stale number) |
| BT-proxied via phone, phone on cellular only | phone's route has no path to a private LAN IP | **no** |
| Watch on home Wi-Fi directly | watch → LAN | **yes**, fastest |
| Watch on LTE, no phone | public internet only | **no** |
| Desktop asleep / `-serve` not running | — | **no**, by definition |

The BT-proxy row is the pleasant surprise: the LAN-direct design keeps working
while the watch sits on the charger with Wi-Fi off, *as long as the phone is
home*. That is most of the actual duty cycle, which is why the first milestone
can skip the phone app entirely.

**Decision D2: LAN-direct HTTP is the primary transport; the Data Layer is an
additive second path, not the foundation.** Building on the Data Layer first
would mean shipping two Android apps before a single number appears on the
watch, and would still fail when the phone can't reach the desktop.

**Decision D3: the watch app is declared `standalone=true`.** It never depends
on a phone app being installed; the phone relay (M4) is an optimisation that is
detected at runtime via `CapabilityClient`, never assumed.

## 4. Who decides an alert

Three candidates, and the failure mode of each:

- **Watch decides.** It must poll often enough to catch a crossing, which is
  exactly the battery pattern Wear OS 6 tightened background limits to prevent;
  and it silently sees nothing while off-LAN, so "no alert" is ambiguous.
- **Phone relay decides.** Better power budget, but if both the phone app and
  the watch app can evaluate thresholds, one crossing yields two alerts, and the
  two nodes disagree whenever one has staler data.
- **Desktop decides.** It is the only node that observes *every* 30 s sample,
  it is already awake (it's the machine burning the quota), and it holds the
  full history needed to distinguish a crossing from a flap.

**Decision D4: the bridge is the single writer of alert events. Watch and phone
render events; they never derive them.** Concretely, `aiusagebar` keeps an
append-only event ring, each event carrying a stable `id`, a `dedupe_key`
(e.g. `five_hour:80:<window_reset_at>` — so one crossing per window, and a new
window re-arms it), `severity`, and the snapshot that triggered it. Hysteresis
lives in one place: fire on cross-up, re-arm only after dropping a few points
below the threshold or after the window resets.

Delivery, in order of preference at runtime:

1. **Phone relay present** → relay raises a normal Android notification with a
   `bridge tag` and a **dismissal ID** so dismissing on one device clears it on
   the other. Default bridging already mirrors phone notifications to the watch;
   `BridgingManager` is the lever if the watch app ever posts its own and we
   need to suppress the duplicate.
2. **No relay** → the watch app's own periodic sync notices `events` newer than
   its high-water mark and posts locally. Latency is bounded by the poll
   interval, which is honest and documented, not hidden.
3. **Off-LAN** → nothing, until M5. The UI must say "can't reach the bridge"
   rather than implying "you're fine" (see §6).

Alert-worthy by default: crossing 80% and 95% on either window; a window
resetting (the good news event); and — deliberately — *not* cost thresholds,
since the cost figure is an independent estimate of value, not a bill
(`README.md`), and alerting on it would imply a charge that isn't happening.

## 5. Onboarding without typing on a watch

Typing `192.168.1.37:8765` plus a token on a 1.2" screen is the project's
biggest adoption risk. Ranked solutions:

1. **mDNS/DNS-SD discovery — the primary path.** The bridge advertises
   `_aiusage._tcp` with TXT records (`host`, `schema`, `auth=token|none`). The
   watch uses `NsdManager` and offers a pick-list of found bridges, so the
   common case is *zero characters typed*. Caveats to design around: NSD is
   limited to the local network, networks that block multicast break it, and the
   platform `NsdManager` has enough historical bugs that a resolve timeout and a
   manual fallback are mandatory, not optional.
2. **Pairing code instead of a token.** If discovery finds the bridge, the user
   should not then type a 20-char secret. The bridge prints a short-lived
   6-digit code (`aiusagebar -pair`); the watch sends it to
   `POST /pair`; the bridge returns a long random per-device token that the
   watch stores in `EncryptedSharedPreferences`. Six digits on a rotary keypad
   is survivable; a shared secret is not. The code is single-use and expires in
   ~2 minutes.
3. **Phone relay pushes the config (M4).** Once the phone app exists, it is the
   best onboarding surface: configure on a real keyboard, then send the host +
   token as a `DataItem`, exactly the pattern Home Assistant and the Wear
   onboarding `Flows` component use (`onAppConfigRetrieved` → send `AppConfig`
   to the wearable).
4. **`RemoteActivityHelper.startRemoteActivity()`** to open a setup page or the
   Play listing on the phone when the watch needs something the wrist can't do.
5. **Rejected: QR codes.** Wear OS watches have no camera. The inverse (watch
   *displays* a QR, phone scans) is viable for handing a pairing code to the
   phone, but it's redundant once (3) exists.
6. **Rejected for now: OAuth.** `RemoteAuthClient` with PKCE, or the device
   authorization grant, is the correct pattern for a real identity provider and
   the `WearOAuth` sample covers both. There is no identity provider here — the
   bridge is a single-user LAN service — so this is over-engineering unless M5
   puts the bridge behind a real front door.

## 6. Tile and complication freshness

Three different clocks, and conflating them is the classic bug.

**The number's age.** The wire snapshot already carries `generated_at`. Every
surface renders staleness explicitly: fresh (< 2 min) shows the value; stale
shows the value plus a dimmed age ("6 min ago"); unreachable shows a distinct
"can't reach bridge" state. A tile showing `53%` from 40 minutes ago with no age
marker is worse than a tile showing nothing, because the whole point is knowing
whether it's safe to start a long task.

**The reset countdown — no refresh needed.** `resets_in_sec` is already in the
payload, but transmitting it repeatedly is the wrong mechanism. Countdown to a
fixed instant should tick **on-device**: ProtoLayout's dynamic types bound to
platform time for tiles, and `TimeDifferenceComplicationText` for complications,
which counts down without any data-source update at all. So one fetch every N
minutes yields a *second-accurate* countdown for free.

**The refresh cadence, against real platform limits.**

- **Tiles:** `freshnessIntervalMillis` asks the system to call `onTileRequest()`
  after the interval, but Wear OS limits update frequency and — outside manual
  refresh — **does not guarantee** the update lands. Design for "at most this
  often, possibly later," never "exactly every N." Proposal: 5 min (300000) when
  the worst window is < 50%, 2 min when ≥ 80%, plus an immediate refresh on
  `onTileEnterEvent`. On Wear OS 6 enter/leave events are **batched**, so an app
  targeting 6+ must read them via `onRecentInteractionEventsAsync()` rather than
  assuming a live callback.
- **Complications:** `UPDATE_PERIOD_SECONDS` has a **hard system minimum of 300
  seconds**; anything smaller is either rejected or ignored. The right pattern
  is `UPDATE_PERIOD_SECONDS=0` (no polling) plus
  `ComplicationDataSourceUpdateRequester.requestUpdateAll()` whenever new data
  lands from any path — poll, Data Layer push, or notification. That way the
  complication is exactly as fresh as the app's data and costs nothing when idle.
- **One fetcher, many renderers.** Tile, complication, app and relay all read a
  single local cache (Room or DataStore), written by one `WorkManager` sync
  worker. The Data Layer is explicitly "not a storage mechanism" — keep the app's
  own copy. Four independent pollers would be four times the radio wakeups for
  the same bytes.

## 7. Wire contract (bridge side, v1)

Additive to today's `/usage`; nothing existing changes shape.

```
GET  /usage                    → current snapshot (as today) + "schema": 1
                                 supports ETag / If-None-Match  → 304 on no change
GET  /events?since=<id>        → { "events": [...], "high_water": "<id>" }
POST /pair   {"code":"123456"} → { "token": "<per-device>", "device_id": "..." }
GET  /healthz                  → liveness, unauthenticated, no data
```

An event:

```json
{ "id": "evt_0191…", "dedupe_key": "five_hour:80:2026-09-21T18:20:00Z",
  "kind": "threshold_crossed", "window": "five_hour", "threshold": 80,
  "severity": "warn", "at": "2026-09-21T16:02:11Z",
  "snapshot": { "utilization": 81, "resets_in_sec": 8291 } }
```

ETag support matters more than it looks: the watch polls a value that changes
rarely, and a `304` over a Bluetooth proxy is roughly free compared with the
full JSON body.

**Security.** The bridge is plain HTTP with an optional shared token and binds
to every interface. That is defensible on a trusted LAN and is documented as
such. Before anything in M5 makes it reachable off-LAN it needs: TLS, per-device
tokens (from `/pair`) that can be revoked individually, and a bind default of
LAN-only. The Data Layer path has its own caveat — payloads aren't encrypted by
default, though routing is restricted to apps sharing the package name and
signing key, so no third-party app on either device can read them.

## 8. Milestones

Each stage ships something usable on its own and is reversible.

### M0 — Bridge contract v1 (Go only, no Android)
Add `schema`, ETag/`If-None-Match`, the event ring + threshold evaluation with
hysteresis, `/events`, `/pair` + per-device tokens, `/healthz`, and mDNS
advertisement of `_aiusage._tcp`. Thresholds configurable via flags.
**Exit:** `curl` shows a stable snapshot, a crossing produces exactly one event
that survives a restart of the poll loop but re-arms on window reset, and
`avahi-browse` finds the service.

### M1 — Standalone watch app, LAN-direct
Kotlin + Compose for Wear. NSD discovery → pick-list → 6-digit pair. One
`WorkManager` sync worker → local cache. Full-screen app UI: both windows,
countdowns, cost, and an explicit unreachable state. `standalone=true`.
**Exit:** install on watch, discover the desktop with zero typed characters, see
correct numbers both on watch Wi-Fi and while BT-proxied through a phone on the
same LAN.

### M2 — Tile + complications
One tile (3-slot Material 3 layout: worst window, both percentages, countdown),
plus complication data sources (`SHORT_TEXT` percentage, `RANGED_VALUE` arc,
countdown via `TimeDifferenceComplicationText`). `UPDATE_PERIOD_SECONDS=0` +
push updates; adaptive `freshnessIntervalMillis`; staleness rendering per §6.
**Exit:** glanceable from the watch face; a forced desktop change appears within
one freshness interval; killing the bridge visibly degrades to "stale/unreachable"
rather than lying.

### M3 — Alerts, watch-decided delivery of desktop-decided events
Watch consumes `/events` on each sync, posts local notifications above its
high-water mark, dedupes by `dedupe_key`. Per-threshold on/off and quiet hours.
**Exit:** crossing 80% on the desktop produces exactly one wrist notification,
and reinstalling the app doesn't replay history.

### M4 — Optional phone relay companion
A minimal Android phone app sharing the watch's package name and signing key.
It polls the bridge, republishes snapshots as urgent `DataItem`s, raises
notifications with bridge tags + dismissal IDs, and — the real prize — becomes
the onboarding surface by pushing host+token to the watch. Watch detects it via
`CapabilityClient` and prefers Data Layer over HTTP when both are live.
**Exit:** with the phone app installed, the watch updates with Wi-Fi off and
never double-alerts; uninstalling the phone app silently falls back to M1
behaviour.

### M5 — Off-LAN (optional, only if wanted)
Two candidates, decided when we get there: (a) a WireGuard/Tailscale overlay so
the existing LAN design keeps working from anywhere with no new server, or
(b) desktop → self-hosted push (ntfy or FCM via a small relay) so events arrive
on LTE. (a) preserves "no data leaves my machines"; (b) is the only thing that
delivers an alert to a watch with no phone nearby. Prerequisite either way: TLS
and revocable per-device tokens from M0.

## 9. Open questions

1. Is off-LAN actually wanted, or is "at my desk / in the house" the whole use
   case? If the latter, M5 never happens and the design stays entirely local.
2. Should the cost-equivalent appear on the tile at all? It's the least
   actionable number and the easiest to misread as a bill.
3. Multiple dev machines — does the watch aggregate several bridges, or pick
   one? The snapshot already carries `host`, so aggregation is possible, but
   "worst window across machines" needs a definition before it's built.
4. Does `~/.claude.json`'s cache go stale when Claude Code isn't running? If so,
   the bridge should expose the cache's own age, not just `generated_at`, so the
   watch can distinguish "bridge is stale" from "Claude hasn't run in a while."
