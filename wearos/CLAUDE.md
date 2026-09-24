# CLAUDE.md — wearos (the Wear OS app)

## Goal

Show Claude subscription usage on a Wear OS watch (Galaxy Watch): how much of
the session (5h) and weekly (7d) plan limit is left, when each resets, and, when
a limit hits 0, a countdown on the wrist to when it comes back. It works from
anywhere the watch has connectivity, as long as the desktop is on.

## Status

`DESIGN.md` (rev 3) is the plan. M0 (bridge hardening) lives in
the repo root, the desktop tray whose bridge is this app's only data source.
M1–M5 are **built**: `wear/` compiles, and it passes its unit tests and lint.
It also installs and registers its tile and complication on a Wear OS 6
emulator. It has **not been run on a real watch yet**, so the M1–M5 exit
criteria are unchecked.

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
  - `Alerts.kt` — notifications, the comeback alarm, the Ongoing Activity
    countdown, and the boot re-arm.
  - `MainActivity.kt` / `Pairing.kt` — the UI (Samsung Health–style
    crescents) and NSD pairing.
  - `UsageTileService.kt`, `UsageComplicationService.kt`.

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
- The wire contract is `../README.md` → Endpoints. Change it there,
  not here.

## Repo status

Lives in `wearos/` inside the **public** AIusageBar repo, next to the bridge it
reads. It was a separate private repo until 2026-09-24; that history wasn't
imported, and the old repo is archived. Public means: never put the live bridge
URL, tokens or addresses in tracked files. They go in the root's gitignored
`CLAUDE.local.md`. Ask before committing or pushing, as for the rest of the repo.
