# CLAUDE.md — wearos (the Wear OS app)

## Goal

Show Claude and Codex subscription usage on a Wear OS watch (Galaxy Watch): how much of
the session (5h) and weekly (7d) plan limit is left, when each resets, and, when
a limit hits 0, a countdown on the wrist to when it comes back. It works from
anywhere the watch has connectivity, as long as the server is running.

The face of the app is **the orbit**, the phone's 2x2 widget: every account's
week as threads wound into rings, Claude's outside and Codex's inside, bright
for what's left (see `../android/CLAUDE.md`, the threads). It is the app's
first page and a tile. Tapping a ring blurs it slightly and circles the
family's pooled session and week left over it, slowly turning, with the
family's mark (Claude's, Codex's) in the middle; tap again, or off the rings,
to put it back. From the tile, the outer ring opens the app on Claude's
figures and the inner on Codex's. The old Session and Weekly pages and the
two-arc tile are gone.

The Setup page selects the provider. Complications and alerts follow that
choice; the orbit always shows every account. Clear comeback alarms and event
cursors when switching; the first events page for the new provider is
history. Codex cost estimates are unavailable.

## Status

`DESIGN.md` (rev 3) is the plan. M0 (bridge hardening) lives in
`../internal/bridge/`, the standalone Go server that is this app's only data source.
M1–M5 are **built** and the app runs on a real watch (a Galaxy Watch,
SM-R950, Wear OS 6): pairing, the orbit tile and the tap-a-ring overlay were
checked there against the live server in 0.6.0.

## Deployment

The bridge runs on the desktop behind Tailscale Funnel. The live URL and the
ops commands are in the repo root's gitignored `CLAUDE.local.md`; keep the URL
out of tracked files, since this repo is public. The watch learns the URL when
it pairs, so nothing in the app hardcodes it.

## Layout

- `DESIGN.md` — current architecture and milestones.
- `archive/` — rev 1 (LAN only) and rev 2 (direct Anthropic API, abandoned
  because it breaks Anthropic's Consumer Terms). History only; don't build from
  them.
- `wear/` — the only module; there is no phone app.
  - `Plan.kt` — the pure rules: cadence, comeback time, the offline-reset
    display, address schemes. Unit-tested in `src/test`.
  - `Sync.kt` — the one fetcher, the scheduler (one-shot WorkManager jobs) and
    the tile/complication refresh.
  - `Alerts.kt` — notifications, the comeback alarm ("Claude's back"), and
    the boot re-arm. There is no ongoing countdown notification any more.
  - `MainActivity.kt` / `Pairing.kt` — the pager (Orbit, then Setup; swipe
    or bezel/crown) and NSD pairing.
  - `Orbit.kt` — `PoolFamily` (colours, marks), `/pool` parsing, the rings
    from the cache, and the drawing: the phone's `ThreadFace.orbit` braid
    (closed rings), ported. Keep it in step with `../android/.../Render.kt`.
    `Braid.kt` is the phone's, copied (`BraidTest` too).
  - `OrbitPage.kt` — the app's orbit and the tap overlay. Each ring alone,
    its blurred copy (`Orbit.soften`: half size, three box passes) and its
    lettering are bitmaps drawn off the main thread once per data change and
    cached in `cacheDir/orbit` by the rings' hash, so opening the app loads
    them and a tap or an animation frame only redraws bitmaps. Nothing
    animates while no ring is picked. Drawing them takes about 4 s on a
    Galaxy Watch, so never do it on the main thread or per tap.
  - `OrbitTileService.kt` — the orbit tile: one inline PNG, its resources
    version the rings' hash, two tap targets (outer ring, inner square).
  - `UsageComplicationService.kt` (% left), `ResetComplicationService.kt`
    (time to the reset that matters).
  - `res/drawable/logo_claude.xml` (Simple Icons, CC0) and `logo_codex.xml`
    (LobeHub Icons, MIT): the marks in the overlay. Their path data is
    rewritten with every number separated: Android's vector parser misreads
    SVG's packed arc flags (`a.848.848 0 00-1.473.842`) and clipped the mark.

## Build and install

```sh
./gradlew :wear:testDebugUnitTest :wear:assembleRelease   # SDK at ~/Android (local.properties)
adb -s <watch-ip:port> install -r wear/build/outputs/apk/release/wear-release.apk
```

The release build is minified and signed with the local debug key, which is
fine for sideloading. The debug APK is about 10× larger and noticeably laggy on
a watch. Two emulators from other work are often attached to adb, so always
pass `-s`.

Versions are pinned to what was already in the Gradle cache (AGP 9.2.1,
Gradle 9.4.1, Kotlin 2.2.10, compileSdk 37 because the Compose BOM needs it).
Lint's "newer version available" warnings are expected.

## Conventions and gotchas

- **Never read usage from Anthropic directly.** Using a Pro/Max OAuth token
  outside Claude Code and claude.ai violates Anthropic's Consumer Terms. The
  bridge, which reads the official Claude Code's local cache, is the only
  source.
- **The bridge decides alerts; the watch renders them.** Never derive
  threshold alerts on the watch. The comeback countdown is display state
  computed from `resets_at`, not an alert.
- **Never compute reset times from "5h/7d after X".** Only use `resets_at`.
- **Send the token as `Authorization: Bearer`**, never `?token=`; query strings
  end up in proxy logs.
- A number without its age is a bug: every surface shows staleness.
- **Battery first.** Background checks are hourly (30 min under 50% left);
  opening the app is what fetches on demand. Don't add wakeups for resets or
  re-render surfaces on a `304`. Each check also asks `/pool` for both
  families with their ETags (usually two `304`s); a bridge older than `/pool`
  answers 404, and the orbit is then built from each provider's `/usage`.
- **The watch on adb.** Wireless debugging: pair with the watch's code
  (`adb pair <ip>:<pairing port> <code>`), then `adb connect <ip>:<port>`;
  `dns-sd -B _adb-tls-pairing._tcp` / `_adb-tls-connect._tcp` and `dns-sd -L`
  find the ports. On macOS the background adb daemon can be denied Local
  Network access ("No route to host" while `nc` from a shell connects); then
  relay through a local TCP forwarder started from the shell and pair/connect
  to `127.0.0.1:<port>`, or grant adb Local Network access. Once connected,
  the watch shows in T3's Device panel; drive it there. Its screen sleeps in
  seconds, so open the app right before tapping.
- Screenshots from the watch go in `wearos/screenshots/` (gitignored), taken
  through T3's `agent-device screenshot`.
  README shots can come from the `claudewear` emulator instead: run a
  throwaway bridge (`XDG_STATE_HOME=/tmp/x XDG_RUNTIME_DIR=/tmp/x
  ./aiusage-server -serve 127.0.0.1:8799 -mdns=false`) so the live
  device list is untouched, and pair at `http://10.0.2.2:8799`. `adb shell
  input text` doesn't survive the Wear keyboard's confirm, so run
  `ime disable` on the keyboard while typing, then `ime enable` it again.
- **Round buttons need a round ripple.** `clickable` on an unclipped box
  ripples as a square; clip it, or pass `ripple(bounded = false, radius = …)`.
  Draw spinning icons on a `Canvas` rather than using a glyph, whose box
  isn't centred on its ink.
- The wire contract is `../README.md` → Endpoints. Change it there,
  not here.

## Repo status

Lives in `wearos/` inside the **public** AIusageBar repo, next to the bridge it
reads. It was a separate private repo until 2026-09-24; that history wasn't
imported, and the old repo is archived. Public means: never put the live bridge
URL, tokens or addresses in tracked files. They go in the root's gitignored
`CLAUDE.local.md`. Ask before committing or pushing, as for the rest of the repo.
