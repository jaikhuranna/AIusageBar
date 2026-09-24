# AIusageWear — Claude usage on the wrist — design

> **Abandoned; superseded by `../DESIGN.md` (rev 3).** Using a Pro/Max OAuth
> token in a third-party app violates Anthropic's Consumer Terms (§1). Kept
> because §4–§7 (cadence, comeback timer, UI, tiles) carried over into rev 3.

A Wear OS app (Galaxy Watch) that shows Claude **session (5h)** and **weekly
(7d)** plan-limit headroom in a Samsung Health–style layout, checks in the
background on a reset-anchored cadence, and, when a limit hits 0, counts down
on the wrist to the moment it comes back.

It reads usage **straight from Anthropic**. No desktop, no bridge, no home
network required.

## 1. The data source

There is no *documented* API for subscription plan limits. The Usage & Cost
Admin API is org-scoped, needs an Admin key, is unavailable to individual
accounts, and reports API-key spend rather than the Pro/Max rolling windows.

What does exist is the **internal endpoint Claude Code's own `/usage` command
calls**:

```
GET https://api.anthropic.com/api/oauth/usage
Authorization: Bearer <OAuth access token>
anthropic-beta: oauth-2025-04-20
User-Agent: claude-code/<version>
```

It returns `five_hour` and `seven_day`, each with `utilization` (0–100) and
`resets_at` (RFC 3339). That is the same shape Claude Code caches into
`~/.claude.json` and that the desktop tray (`../AIusageBar`) already parses.

**Risks accepted by choosing this source** (decision D1, made by the owner):

- **Undocumented.** Shape, path, headers or auth can change without notice. The
  app must fail *honestly* (stale + labelled). It must never crash, and never
  show an old number as current.
- **Impersonation.** Without a `claude-code/…` User-Agent the endpoint lands in a
  heavily rate-limited bucket and returns persistent 429s. Sending it means this
  app presents itself as Claude Code, and it also logs in with Claude Code's
  public OAuth client ID. **This is not a grey area: it violates the Consumer
  Terms.** Anthropic's legal compliance page (updated 2026-02-19) states that
  using OAuth tokens from Free, Pro or Max accounts "in any other product, tool,
  or service … is not permitted and constitutes a violation of the Consumer
  Terms of Service", and enforcement has disrupted accounts since January 2026.
  The realistic cost is losing the subscription. **Rev 2 is on hold for this
  reason**; see §11.
- **Rate limiting is aggressive** even with the right headers; reports describe
  429s that don't recover under frequent polling. The cadence in §4 is designed
  around this.
- **Window lengths are not guaranteed.** At least one user reports the "weekly"
  cap resetting on a ~72h cycle. The app therefore **never computes reset times
  from "5h/7d after X"**. It only ever uses `resets_at` from the response.

Containment: everything Anthropic-specific lives behind one `UsageSource`
interface in one module. If Anthropic ships an official per-user usage
endpoint, or this one breaks, only that one file changes.

Distribution: **sideloaded only** (adb over Wi-Fi to the watch). Given the
impersonation above, this is a personal tool, not a Play Store app.

## 2. Sign-in: why a phone app still exists

Claude Code authenticates with **OAuth Authorization Code + PKCE**. It
authorizes at `claude.ai/oauth/authorize` and exchanges the code at the token
endpoint (`console.anthropic.com/v1/oauth/token` per current references;
re-verify in M0, as console routes are migrating to `platform.claude.com`). It
supports a **copy/paste mode**: the redirect lands on
`console.anthropic.com/oauth/code/callback`, which shows a code for the user
to paste back. Access tokens last ~8h. Refresh uses `grant_type=refresh_token`,
and **the refresh token rotates** on every use.

Things that rule out easier paths:

| Shortcut | Why it doesn't work |
| --- | --- |
| `claude setup-token` (1-year token) | Carries only `user:inference`; `/api/oauth/usage` requires `user:profile` → **403**. There is an open feature request for a read-only usage scope. |
| Copy `~/.claude/.credentials.json` from the desktop | Needs the desktop (the thing we're removing). Also, two clients sharing one rotating refresh token means whichever refreshes second gets logged out. |
| Log in on the watch | Wear OS has no `android.webkit`, and pasting a long code on a 1.3" screen is not a real onboarding flow. |
| Wear `RemoteAuthClient` | Needs a `wear.googleapis.com/3p_auth/<package>` redirect registered with the OAuth provider. We can't register redirects on Claude Code's client. |

So **a small phone app does the sign-in, and only the sign-in.** It opens the
authorize URL in a Custom Tab. The user logs in and pastes the code back (or the
app reads it from the clipboard), and the phone exchanges it for tokens. This
creates a **new, independent session**; the desktop's Claude Code login is
untouched.

**Scopes: request least privilege.** Ask for `user:profile` only. If the usage
endpoint accepts that, a stolen token can *read* usage but cannot *spend* your
quota. If the endpoint also requires `user:inference`, accept that and note it.
M0 settles this.

## 3. Who polls: the watch (decision D2)

The earlier question was "a phone app that polls and sends to the watch?". With
a public HTTPS upstream, the watch can poll by itself, and it should:

- **The watch can always reach the endpoint.** `api.anthropic.com` is on the
  public internet, so every watch state reaches it: through the phone over
  Bluetooth (with the phone on any network, including cellular), on the watch's
  own Wi-Fi, or on the watch's LTE. The only failure is no connectivity at all.
- **Samsung phones kill background apps aggressively.** A background poller on
  a Galaxy phone would be the most fragile part of the system. A watch
  `WorkManager` job every 15 min or longer is well within Wear OS's normal
  background allowance.
- **The alarm lives on the watch anyway** (§5). Keeping the data on the same
  device as the alarm that depends on it removes a sync hop that could lag or
  drop.
- **Exactly one device owns the refresh token.** Because tokens rotate, two
  devices refreshing would log each other out. After sign-in the phone **hands
  the token pair to the watch and deletes its own copy.**

Hand-off uses `MessageClient` (ephemeral), **not** a `DataItem`. DataItems
persist and re-sync, and the Data Layer doesn't encrypt payloads by default.
The Data Layer only connects apps with the same package name and signing key,
so both APKs share one `applicationId` and one key. On the watch the tokens go
into Keystore-backed encrypted storage and are never logged.

After hand-off the phone app is idle. If the refresh token is ever rejected,
the watch shows "Sign in on phone" and uses `RemoteActivityHelper` to open the
phone app's sign-in screen directly.

The watch is also the **only device that decides alerts**. It is the only
device with the data, so there is no risk of two devices alerting for the same
event.

## 4. Background cadence

The owner's rule: **check every 2.5 h, anchored to the reset**; manual refresh
stays manual. This is kept as the baseline, with two amendments.

**Why amend it.** A heavy Claude Code session can use up a 5h window in well
under an hour. At a flat 2.5h the app could learn about hitting 0 up to 2.5h
late. The countdown would still be *correct* (it targets `resets_at`), but it
would *appear* late, which defeats its purpose.

The scheduler, re-evaluated after every successful fetch:

| State (worst window, % remaining) | Next check |
| --- | --- |
| > 50% left | 2.5h, aligned to the 5h window (reset − 2.5h, then reset) |
| 20–50% left | 30 min |
| < 20% left | 15 min (the `WorkManager` floor) |
| **0% left** | **no polling** until the comeback time, then one check at comeback + 1 min to confirm and clear |
| 5h window not started (`resets_at` null) | 2.5h |

Stopping at 0 is deliberate. Once you're out, nothing can change until the
reset, so the app makes no requests during the period when you'd be most
tempted to refresh.

Mechanics: periodic `WorkManager` work can't be pinned to wall-clock times, so
each run enqueues **one-shot** work for `min(now + interval, next_reset)`, with
`NetworkType.CONNECTED`.

**429 handling.** Honour `retry-after` when it's non-zero; otherwise back off
exponentially (15 min → 30 → 60 → cap 2.5h). Keep showing the cached data with
its age. Never retry in a tight loop; reports show the endpoint punishes it.

**Manual refresh** (app button, tile tap) fetches immediately. It is ignored if
the last successful fetch was under 60 s ago, so repeated taps can't trigger a
429.

## 5. The comeback timer

When a limit hits 0, the watch counts down to when it comes back.

**Which time.** "Comes back" means the **later** reset among exhausted windows.
If the weekly window is at 0, the 5h reset gives nothing back.

**Not the system Clock timer.** `AlarmClock.ACTION_SET_TIMER` has four problems:
- It launches an activity, which Android blocks from background work.
- Timers cap at 24h, and a weekly reset can be days out.
- Samsung's Clock may not accept it without showing its own screen.
- We can't update or cancel it if `resets_at` moves.

Instead the app uses three pieces of its own. Each is keyed by window, so
detecting the same exhaustion again replaces them rather than duplicating them:

1. **An exact alarm** at the comeback time (`AlarmManager`,
   `SCHEDULE_EXACT_ALARM`). This permission is denied by default on recent
   Android, so the app asks the user to grant it once, which is fine for a
   sideloaded app. The alarm fires a "Claude's back" notification with a
   vibration.
2. **An ongoing countdown notification**: `setUsesChronometer(true)` +
   `setChronometerCountDown(true)` + `setWhen(comeback)`, shown as a Wear
   **Ongoing Activity**. That puts a live countdown icon on the watch face and
   in the recent apps list. It looks and feels like a timer, and the system
   does the ticking.
3. **A "Start Clock timer" action** on that notification, for anyone who wants
   the real Clock app timer too. Android allows a user tap to launch it. It is
   offered only when the comeback is under 24h away.

All three are cleared when the confirming check shows headroom again, or
immediately if a later fetch shows the window isn't actually exhausted.

## 6. UI — Samsung Health style

Samsung Health's layout: coloured arcs with rounded ends along the **top edge**
of the round screen, over a dim background track, with a big number in the
centre and details below. We copy the layout, not Samsung's graphics.

App screen (round, ~1.3–1.5"):

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

- **Arcs show remaining, draining toward 0.** You think in terms of "hitting
  0," so the UI does too. Compose for Wear OS Material 3
  `CircularProgressIndicator` takes `startAngle`/`endAngle`. Start around
  200°→340° (a ~140° crescent; 0° = 3 o'clock, clockwise) and tune on the watch.
- Colours follow the tray app's scheme: green, orange below 50% left, red below
  20% left. At 0 the arc shows only the empty track, and the centre switches to
  the comeback countdown.
- The data's age is always visible, in one of three states: fresh (under 5
  min); stale (dimmed, with its age); or an error ("can't reach Anthropic",
  "sign in on phone", "rate limited, retrying at 3:40"). A number without an age
  is worse than no number.
- Cost-equivalent is **not shown**. The tray computes it from local transcripts
  on the desktop, and it isn't in the Anthropic response.

## 7. Tile and complications

- **Tile**: the same two arcs via ProtoLayout (`Arc`/`ArcLine`, or the M3
  circular progress component with angles), plus the centre % and a countdown
  line. Tapping it opens the app. It refreshes via `freshnessIntervalMillis`
  matching the §4 cadence. The system throttles tile updates and doesn't
  guarantee them, so the tile renders from the local cache and never fetches
  anything itself. Wear OS 6 batches the events for users swiping to and away
  from a tile; read them via `onRecentInteractionEventsAsync()`.
- **Complications**: `RANGED_VALUE` (an arc on the watch face) and `SHORT_TEXT`
  ("47%"). Set `UPDATE_PERIOD_SECONDS=0` (the platform minimum is 300s anyway);
  the sync worker calls `ComplicationDataSourceUpdateRequester.requestUpdateAll()`
  after each fetch. Countdown text uses `TimeDifferenceComplicationText`, which
  ticks on the watch without further updates.
- **One fetcher, many displays.** The app, tile and complications all read one
  local cache (DataStore), and only the sync worker writes to it.

## 8. Project shape

```
AIusageWear/
  CLAUDE.md
  DESIGN.md                    this file
  DESIGN-rev1-lan-bridge.md    superseded; kept for ../AIusageBar's bridge
  phone/     sign-in only: PKCE + paste code, hand-off, re-auth screen
  wear/      UsageSource, scheduler, cache, UI, tile, complications, alarm
```

One Gradle project with two modules and the **same `applicationId` and signing
key** (the Data Layer requires it). Kotlin, Compose for Wear OS Material 3,
WorkManager, DataStore. At runtime this project has no dependency on
`../AIusageBar`.

## 9. Milestones

### M0 — Spike: prove the endpoint (curl, no app)
Settle the facts the design depends on, with a throwaway script from any
machine:
- PKCE + paste-code login works end-to-end.
- **Which scopes** `/api/oauth/usage` accepts (`user:profile` alone?).
- The required headers.
- The response shape, including a null `resets_at`.
- How quickly 429s appear at 15-min and 2.5h cadences.
- How refresh-token rotation behaves.
- That the desktop's Claude Code stays logged in throughout.

**Exit:** findings written up here, plus a go/no-go decision. If the endpoint
needs something the watch can't do, stop here.

### M1 — Sign-in and raw numbers
Phone module: Custom Tab login, paste code, token exchange, `MessageClient`
hand-off, then delete the local copy. Wear module: encrypted token store,
refresh with rotation, `UsageSource`, a manual refresh button, plain text
output.
**Exit:** sign in on the phone and see correct numbers on the watch. They keep
working after the phone's Bluetooth is turned off (watch on Wi-Fi).

### M2 — Samsung Health–style UI
Concentric top-half arcs, centre remaining %, countdowns, and the staleness and
error states from §6.
**Exit:** readable at a glance on the actual watch; every error state can be
triggered and looks distinct.

### M3 — Background cadence
One-shot scheduler per §4, 429 backoff, manual-refresh throttle, stop-at-0.
**Exit:** over a day of normal use, logs show checks at the expected times and
no 429 loops, and the app's battery use is small enough not to show up in the
watch's battery stats.

### M4 — Comeback timer
Exact alarm, ongoing countdown / Ongoing Activity, and the "Start Clock timer"
action. Implements the later-of-exhausted-windows rule, and re-detecting or
clearing never duplicates the alarm or notification.
**Exit:** after a window is exhausted, a countdown icon appears on the watch
face within one check interval, and the watch vibrates at the reset.

### M5 — Tile + complications
Per §7.
**Exit:** the tile and a complication on the chosen watch face both show a new
fetch without opening the app.

### M6 — Hardening
- Re-sign-in via `RemoteActivityHelper` when refresh fails.
- Sign-out (wipes the tokens).
- If the endpoint changes shape, show stale + labelled data instead of
  crashing.
- Check battery use over a week.

## 10. Decision log

- **Rev 1** (superseded; `DESIGN-rev1-lan-bridge.md`): the watch read the
  desktop tray's LAN JSON bridge (`aiusagebar -serve`). Its M0, the bridge
  contract, was built in `../AIusageBar`. Dropped because it made the desktop a
  runtime dependency: the watch had no data whenever the desktop was off or you
  left the house.
- **Rev 2** (this doc): the watch reads Anthropic's internal usage endpoint
  directly; the phone app only signs in. Chosen by the owner with the risks in
  §1 stated.

## 11. Open questions

0. **Blocking: rev 2 breaks Anthropic's Consumer Terms (§1).** Do not start M0
   until the owner decides whether to proceed anyway, return to the desktop
   bridge (rev 1) with on-watch countdowns, or wait for an official read-only
   usage scope (anthropics/claude-code#81015).

1. Scope outcome from M0. If `user:inference` is mandatory, the watch holds a
   token that can spend quota. Is that acceptable on a personal device?
2. Should a window *starting* (first use after a reset), or crossing 80%/95%,
   also notify, or only hitting 0?
3. Multiple Claude accounts on one watch: out of scope unless wanted.

## References

- Usage & Cost Admin API: <https://platform.claude.com/docs/en/manage-claude/usage-cost-api>
- `/api/oauth/usage` behaviour and 429s:
  <https://github.com/Maciek-roboblog/Claude-Code-Usage-Monitor/issues/202>,
  <https://github.com/anthropics/claude-code/issues/31021>,
  <https://github.com/anthropics/claude-code/issues/31637>
- Third-party OAuth ban: <https://www.theregister.com/2026/02/20/anthropic_clarifies_ban_third_party_claude_access/>
- setup-token lacks `user:profile`: <https://github.com/anthropics/claude-code/issues/81015>
- Claude Code OAuth/PKCE flow: <https://gist.github.com/ben-vargas/c7c7cbfebbb47278f45feca9cef309d1>
- Weekly-reset cadence report: <https://gist.github.com/monperrus/3ac4b303a84946bbeaf2b1123ee99491>
- Wear OS network access: <https://developer.android.com/training/wearables/data/network-access>
- Data Layer: <https://developer.android.com/training/wearables/data/overview>
- Tiles updates: <https://developer.android.com/training/wearables/tiles/update>
- Complication data sources: <https://developer.android.com/training/wearables/complications/exposing-data>
- WearOAuth samples: <https://github.com/android/wear-os-samples/tree/main/WearOAuth>
