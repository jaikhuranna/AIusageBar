# wearos — Claude usage on the wrist — design

Status: M0 done; M1–M5 built and verified off-device (unit tests, lint, and
an install on a Wear OS 6 emulator), not yet run on a real watch. Revision 3;
earlier revisions are in `archive/` (§10 says why they were dropped).

A standalone Wear OS app (Galaxy Watch) that shows Claude **session (5h)** and
**weekly (7d)** plan-limit headroom in a Samsung Health–style layout, checks in
the background on a reset-anchored cadence, and, when a limit hits 0, counts
down on the wrist to the moment it comes back.

## 1. Data source: the desktop bridge

The numbers come from the repo root, the desktop tray, running with `-serve`.
It reads `~/.claude.json`, a cache the **official Claude Code** writes on your
own machine. No OAuth token is used anywhere, so this stays within Anthropic's
terms. (Rev 2 read Anthropic's internal usage endpoint directly, which breaks
them; see `archive/`.)

The wire contract is the **Endpoints** table in `../README.md`:
`GET /usage` (with ETag/304), `GET /events?since=`, `POST /pair`,
`GET /healthz`.

Freshness limits, stated up front:

- **The desktop has to be on** for new numbers. When it's off, the watch keeps
  the last reading and the reset times, so countdowns and the comeback alarm
  keep working; only new usage goes unseen.
- **The cache moves when Claude Code does.** claude.ai or phone usage counts
  against the same limits, but shows up only after Claude Code on the desktop
  next refreshes `~/.claude.json` (open question 1).

## 2. Reaching it: a tunnel, and no phone app

```
watch ──HTTPS──▶ Tailscale Funnel ──▶ bridge 127.0.0.1:8765     (deployed)
watch ──HTTPS──▶ Cloudflare edge ──▶ cloudflared ──▶ bridge     (alternative, needs a domain)
```

The tunnel gives the bridge a stable `https://` hostname with TLS included. Every watch state reaches a public HTTPS URL: through the
phone over Bluetooth (with the phone on any network, including cellular), on
the watch's own Wi-Fi, or on the watch's LTE. The only failures are "desktop
off" and "no connectivity at all".

**Decision D1: the watch fetches directly; there is no phone app.** Now that
the URL is public, a phone relay would add nothing, and it would cost:

- a Galaxy-phone background app, which Samsung kills aggressively;
- a second APK that must share the watch's package name and signing key for the
  Data Layer;
- a second copy of the credential.

The watch app is `standalone=true`.

**Bridge-side safety for the internet** (built in M0):

- **Pairing codes are minted only over a Unix socket.** cloudflared delivers
  internet traffic from `127.0.0.1`, so the old "loopback-only HTTP" check
  proved nothing.
- **`-public-url` closes the bridge from the start.** Nothing is readable until
  a device pairs.
- **Ten wrong codes burn every pending code.**
- **The tunnel's ingress only forwards the four public routes.** Everything
  else gets a 404.

## 3. Onboarding without typing

1. **First run, at home on Wi-Fi.** The watch uses `NsdManager` to discover
   `_aiusage._tcp` and shows the bridges it found. You pick one, with nothing
   typed.
2. **Enter the 6-digit code** from the tray's **Pair a device…** menu item (or
   `aiusagebar -pair`). The watch sends it with `POST /pair` over the LAN.
3. **The response carries a per-device `token` and the `public_url`.** The
   watch stores both in app-private storage, with backup and device-to-device
   transfer excluded, and uses the public URL from then on, everywhere.

Fallback: an **Enter address** screen for pairing away from home. You type the
hostname once with the watch keyboard or voice input, then the same code. mDNS
needs the watch on Wi-Fi itself, because multicast doesn't cross the Bluetooth
proxy.

Security note: LAN pairing sends the code and returns the token over plain HTTP
on your home network. That was rev 1's trust assumption and it still applies;
from then on, every request goes over HTTPS.

A `401` later (token revoked, or state file wiped) sends the watch back to step
1, with a clear "Pair again" screen instead of an error.

## 4. Background cadence

The owner's rule: **check hourly**, to spare the battery; opening the app
always fetches, so background checks only pace the tile and complications. (It
was 30 min until 2026-09-25, and 2.5 h before `GET /usage` refreshed the
bridge's cache.) It tightens as headroom shrinks so that hitting 0 is noticed
reasonably promptly:

| State (worst window, % remaining) | Next check |
| --- | --- |
| > 50% left | 60 min |
| ≤ 50% left | 30 min |
| **0% left** | **no polling** until the comeback time, then one check at comeback + 1 min to confirm |
| 5h window not started (no `resets_at`) | 60 min |
| Bridge unreachable | back off 30 → 60 → 120 min, cap 120 min |

Resets don't get a wakeup of their own: one that passes between checks already
shows as full (see "Offline past a reset" below). Each run enqueues **one-shot**
work with `NetworkType.CONNECTED` and `requiresBatteryNotLow`, because periodic
`WorkManager` work can't be pinned to wall-clock times. A run makes at most two
small requests:

- `GET /usage` with `If-None-Match`. It's usually a `304`, which costs almost
  nothing over a Bluetooth proxy.
- `GET /events?since=<high-water>`, only when the usage ETag has changed since
  the last good events poll. A threshold crossing always moves the numbers, so
  an unchanged ETag means no new events.

A `304` also leaves the tile and complications alone; they are only asked to
re-render when the snapshot or the error state changed.

**Manual refresh** (the app button, or tapping the tile) is ignored if the last
fetch succeeded under 60 s ago.

## 5. Alerts and the comeback timer

**The bridge decides alerts; the watch renders them.** The bridge samples every
30 s, so it catches every threshold crossing; the watch only polls every 30
to 120 min. Its defaults are `80,95,100`, and 100 means "exhausted". The watch
posts a notification for each event above its high-water mark, deduplicated by
`dedupe_key`, so reinstalling the app never replays history.

**The comeback timer** follows the snapshot. When any window shows 0 remaining,
the comeback time is the **later** reset among the exhausted windows: if the
weekly window is at 0, the 5h reset gives nothing back. Times come only from
`resets_at`, never from "5h after X".

The system Clock app's timer isn't used, for four reasons:
- starting it launches an activity, which Android blocks from background work;
- timers cap at 24h, and a weekly reset can be days away;
- Samsung's Clock may not accept it without showing its own screen;
- the app can't update or cancel it afterwards.

Instead the app sets **an exact alarm** at the comeback time (`AlarmManager`
with `USE_EXACT_ALARM`; fine for a sideloaded app). It fires "Claude's back"
with a vibration. Re-detecting the same exhaustion replaces it rather than
duplicating it, and any fetch showing the window isn't exhausted clears it. The
countdown itself lives in the app, the tile and the reset complication (§7).

An ongoing countdown notification (a Wear **Ongoing Activity**, with a "Start
Clock timer" action) was dropped on 2026-09-25: the reset complication does the
same job on the watch face without a permanent notification.

**Offline past a reset.** If the desktop is off when `resets_at` passes, the
watch shows the window as **"reset · full (unconfirmed)"**, not the stale
pre-reset number. After a reset, a window really is full until it's used.

## 6. UI — Samsung Health style

Coloured arcs with rounded ends along the **top edge** of the round screen, over
a dim background track; a big number in the centre; details below. We copy the
layout, not Samsung's graphics.

```
            ╭───── 5h ─────╮        ← outer arc, top half
          ╭──── 7d ────╮            ← inner arc, concentric
               47%                  ← % remaining, worst window
            left this session
     ─────────────────────────
      5h · resets 2h 41m            ← countdowns tick on-device
      7d · resets Fri 9:00
      updated 6 min ago      ⟳      ← staleness + manual refresh
```

- **Arcs show remaining, draining toward 0.** Compose for Wear OS Material 3
  `CircularProgressIndicator` with `startAngle`/`endAngle`, starting around
  200°→340° (a ~140° crescent; 0° = 3 o'clock, clockwise). Tune on the watch.
- Colours follow the tray's scheme: green, orange below 50% left, red below 20%
  left. At 0 the arc shows only the empty track, and the centre becomes the
  comeback countdown.
- The data's age is always visible, in one of these states: fresh (under 5
  min); stale (dimmed, with its age); "desktop offline"; "pair again".
- The **API-cost equivalent** (`cost_today`, `cost_session`, `cost_week`) goes
  on a second, swipeable page, labelled as an estimate and not a bill. It never
  goes on the main screen, the tile, or an alert.

## 7. Tile and complications

- **Tile**: the same two arcs via ProtoLayout (`Arc`/`ArcLine`), plus the centre
  % and a countdown line; tapping it opens the app. It renders from the local
  cache and never fetches anything itself. `freshnessIntervalMillis` follows the
  §4 cadence, but the system throttles tile updates and doesn't guarantee them.
  Wear OS 6 batches the events for users swiping to and away from a tile; read
  them via `onRecentInteractionEventsAsync()`.
- **Complications**, two of them:
  - *Claude left*: `RANGED_VALUE` (an arc on the watch face) and `SHORT_TEXT`
    ("47%"), or a countdown when out.
  - *Claude reset in*: `SHORT_TEXT` ("2h 41m", titled "5h"/"7d", or "back"
    when out) and `LONG_TEXT`. Only the reset that matters (`Plan.nextReset`):
    the comeback when out, otherwise the tightest window's reset.

  Both set `UPDATE_PERIOD_SECONDS=0` (the platform minimum is 300 s anyway);
  the sync worker calls `requestUpdateAll()` after a fetch that changed
  something. Countdown text uses `TimeDifferenceComplicationText`, which ticks
  on the watch without further updates.
- **One fetcher, many displays.** The app, tile and complications all read one
  local cache (`Store`), and only `Sync` writes to it.

## 8. Project shape

```
wearos/
  CLAUDE.md
  DESIGN.md        this file
  archive/         rev 1 (LAN only), rev 2 (direct Anthropic; abandoned)
  wear/            the only module: pairing, sync worker, cache, UI, tile,
                   complications, alarm
```

Kotlin, Compose for Wear OS Material 3, WorkManager, Tiles/ProtoLayout,
complications. HTTP is `HttpURLConnection`, JSON is the
platform's `org.json`, and storage is `SharedPreferences`.
`minSdk 30` (Wear OS 3, which covers the Galaxy Watch4 onwards). Sideloaded over
adb Wi-Fi debugging onto the physical watch; no emulator.

The SDK lives at `~/Android`; pinned versions are in `CLAUDE.md`.

## 9. Milestones

### M0 — Bridge ready for the internet ✅
Built in the repo root:
- Pairing codes are minted only over a Unix control socket.
- `-public-url` requires auth from the start and returns `public_url` from
  `/pair`.
- Alert thresholds now default to `80,95,100`.
- The startup log only lists LAN addresses when the bridge listens on all
  interfaces, and mDNS is skipped when it's bound to loopback.
- The README covers Cloudflare Tunnel setup, including the ingress path allow
  list.
- Tests cover every rule, and the binary was checked end to end.

**Deployed** (2026-09-24) through **Tailscale Funnel** instead of Cloudflare,
because Funnel needs no domain. Checked through the tunnel: `/healthz`
reports `"auth":"token"`, `/usage` and `/events` return 401 without a token,
and `POST /pair/new` returns 404. Public DNS for a new Funnel hostname can lag;
the watch keeps the LAN address from pairing as a fallback for exactly that.
Cloudflare still works if a domain is ever added (`../scripts/setup-tunnel.sh`).

### M1 — Pair and show raw numbers
NSD discovery, code entry, `/pair`, encrypted token store, `GET /usage` via the
public URL, a manual refresh button, plain text output.
**Exit:** paired at home with nothing typed but the code, and the correct
numbers show on the watch with the phone on cellular and home Wi-Fi out of
range.

### M2 — Samsung Health–style UI
The §6 layout and every state.
**Exit:** readable at a glance on the watch; every state can be triggered and
looks distinct.

### M3 — Background cadence and alerts
The §4 scheduler, ETag, `/events` → notifications with deduplication, backoff.
**Exit:** crossing 80% on the desktop produces exactly one notification within
one check interval; a day of use shows sane check times and battery use that
doesn't show up in the watch's battery stats.

### M4 — Comeback timer
Per §5: the alarm, the later-of-exhausted-windows rule, and "reset · full
(unconfirmed)" while offline.
**Exit:** exhausting a window switches the reset complication to the comeback,
and the watch vibrates "Claude's back" at the reset.

### M5 — Tile + complications
Per §7.
**Exit:** both show a new fetch without opening the app.

### M6 — Hardening
Re-pairing on `401`, a device list and revocation in the tray (today you delete
the device from `state.json`), and a week-long battery check.

## 10. Decision log

- **Rev 1** — watch reads the bridge on the LAN. Dropped because it only worked
  at home.
- **Rev 2** — watch reads Anthropic's internal `/api/oauth/usage` with a
  Pro/Max OAuth token. Abandoned: Anthropic's Consumer Terms (updated
  2026-02-19) explicitly forbid using those tokens in any third-party tool, and
  enforcement has disrupted accounts.
- **Rev 3** (this doc) — rev 1's data source, reached through a Cloudflare
  named tunnel; the watch fetches directly; no phone app. T3 Connect was
  considered and rejected: its phone app only refreshes usage while open, a
  watch module would require re-signing T3's mobile app, and push alerts would
  need changes to T3's hosted relay.

## 11. Open questions

1. ~~How stale can `~/.claude.json` get?~~ **Answered 2026-09-24:** very. Claude
   Code only refreshes `cachedUsageUtilization` during sessions (it once read
   86% while the real figure was 99%). The bridge now refreshes it on demand:
   a `GET /usage` that finds the cache over a minute old first runs the
   official `claude -p /usage` (no model call), and the snapshot carries
   `cache_fetched_at`. No watch change was needed; the Refresh button and the
   background poll both get current numbers. See `../README.md`
   → Freshness.

## References

- Bridge endpoints and tunnel setup: `../README.md`
- Cloudflare Tunnel: <https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/>
- Anthropic third-party OAuth ban: <https://www.theregister.com/2026/02/20/anthropic_clarifies_ban_third_party_claude_access/>
- Wear OS network access: <https://developer.android.com/training/wearables/data/network-access>
- Tiles updates: <https://developer.android.com/training/wearables/tiles/update>
- Complication data sources: <https://developer.android.com/training/wearables/complications/exposing-data>
