# CLAUDE.md — iOS widgets (`ios/`)

## Goal

Show Claude (one or more accounts, pooled) and Codex subscription usage on an
iPhone's Home Screen and Lock Screen, in step with the Android app in
`../android/`: the same widgets, the same family cards in the app, the same tap
card. How much of the 5h session and the weekly limit is left, a countdown when
one runs out, and a "GO!" when it comes back. It pairs with the standalone Go
server (`cmd/aiusage-server` at the repo root). **When Android's app, card or
widgets change, port the change here** (see `../android/CLAUDE.md`).

The app is Android's MainActivity: per family (Claude, then Codex) a Session and
a Weekly card (share of the family left, the next comeback, pace, a bar split per
account, a row per account), with pairing, "Widgets show" and the widget list in
a "•••" sheet. No widget faces in the app. A widget tap opens the tap card as a
sheet sized to fit it (`App/Card.swift`): each family's two limits, the
five-hour graph with its Claude | Codex pill, the weekly threads, Refresh and
Open app.

## The widgets

| Widget | iOS family | Android original |
| --- | --- | --- |
| AI Usage orbit | `systemSmall` | 2x2 orbit |
| AI Usage dash | `systemMedium` | 4x2 dash |
| AI Usage threads | `systemMedium`, `accessoryRectangular` (Lock Screen) | 4x1 threads; on the Home Screen moved up, with the selected family's 5H LEFT, BURN (`burn_pct_per_hour` ÷ accounts, %/h) and USABLE NOW (the pooled ring's spendable share) under them |
| AI Usage pool | `accessoryCircular` (Lock Screen) | 1x1 pooled ring |

iOS has no 1×1 Home Screen widget, so the ring face is **the app's icon**
(`App/Assets.xcassets/AppIcon.appiconset`, light, dark and tinted, drawn from
the ring's geometry), and there is no ring or pool on the Home Screen. The
dot-matrix 2x2 is decommissioned on both platforms; the orbit replaced it.

The states (`Mode` in `Core/Look.swift`: UNPAIRED, WAITING, WEEK_OUT,
SESSION_OUT, GO, NORMAL, plus stale) and what each face draws in them are the
Android ones; see `../android/CLAUDE.md`.

## Layout

- `project.yml` — XcodeGen spec for the app and the widget extension. The
  `.xcodeproj`, `Info.plist` and entitlements files are generated from it
  and gitignored.
- `Core/` — the pure rules, Foundation only: `Model.swift`, `Plan.swift`,
  `Look.swift`, `Format.swift`, `DotFont.swift`, `Pool.swift` (`/pool`,
  `poolRing`), `Family.swift` (`Member`, `FamilyView`, `Meter`,
  `segmentSpans`), `SessionChart.swift` (the card's graph, `placeLabels`) and
  `Threads.swift` (`strandsOf`) and `Braid.swift` (the threads' twist and
  crossings, Android's `Braid.kt`). **Ports of the Android files of the same
  names.** Keep them in step with `../android/` and `../wearos/` when the
  bridge contract or the rules change.
- `CoreTests/` — ports of Android's `LookTest`, `PlanTest`, `FamilyTest`,
  `PoolTest` and `SessionChartTest`.
- `Package.swift` — builds `Core/` and `CoreTests/` alone, so `swift test`
  runs anywhere, Linux included. The Xcode targets compile `Core/` in directly.
- `Shared/` — compiled into both the app and the extension:
  - `Store.swift` — the App Group's `UserDefaults`: one `ProviderCache` per
    provider (Claude's under bare keys, the others suffixed) and one
    `PoolCache` per family; `familyView`, `pooledRing` and `strands` build
    what the app, card and widgets draw. The token is in the Keychain (the
    shared App Group access group, this device only, after first unlock so a
    locked phone's widgets can still fetch).
  - `Bridge.swift` — `/usage`, `/pool` (404 → `notFound`, an older bridge;
    everything then falls back to the per-provider snapshots) and `/pair`.
  - `Sync.swift` — the one fetcher: every provider and both pools in
    parallel, scheduled at the soonest provider's `Plan.nextCheck`. The
    extension runs it on each timeline reload (it fetches only once
    `Store.nextFetchAt` has come); the app runs it on open (throttled to one a
    minute) and on Refresh and widget taps (always).
  - `Fixtures.swift` — Android's debug fixture: three Claude accounts and
    three Codex ones (the first out for the week). Launch with `-fixture` (and `-card` for the tap
    card); debug builds pass it on to the widgets through the App Group.
  - `Faces.swift` — the faces, drawn like Android's `Render.kt`: ops on an
    ink layer (`Color.primary`, so it follows dark mode) and an accent layer
    (orange for Claude, tan for Claude 2, green for Codex, yellow and grey;
    dimmed when stale; `widgetAccentable` for tinted mode). `clear` ops knock
    bands out like Android's `CLEAR` paint. `ThreadFace` (a `Canvas`) draws
    the threads and the orbit the way Android does (see `../android/CLAUDE.md`,
    the threads); `Palette.member` is `memberColor`.
- `Widgets/Widgets.swift` — the bundle, the four widgets, the threads'
  figures (`Stats`), and the timeline provider.
- `App/` — `AIusageApp.swift` (the `AppModel` over `Store`), `ContentView.swift`
  (the family cards and the "•••" sheet), `Usage.swift` (Android's `Usage.kt`:
  `MeterCard`, `SegmentBar`, `ResetArrow`, `FamilyHeader`), `Card.swift` (the
  tap card and its `SessionGraph`), `Pairing.swift` (Bonjour + 6-digit code).

## Build, test, install

```sh
swift test                         # Core rules; needs only a Swift toolchain
python3 Tests/check_sync.py         # Sync: one parallel fetch of every provider and pool, coalesced
brew install xcodegen && xcodegen  # on a Mac: writes AIusageWidget.xcodeproj
open AIusageWidget.xcodeproj       # set your team under Signing, run on a phone
```

Needs Xcode 26+ and iOS 17+. Both targets must share the App Group
`group.com.jaikhurana.aiusagewidget`; with automatic signing Xcode registers
it. If the bundle IDs are taken, change `bundleIdPrefix`, the bundle IDs and the App Group together
(the group is also `Store.appGroup`).

`.github/workflows/ios.yml` runs the core and concurrency checks on macOS 26
and builds both targets for the simulator without signing. Device installation
still needs your signing team.

## How it differs from Android

- **Refreshing.** WidgetKit decides when a timeline reloads. The provider asks
  for a reload at the next `Plan.nextCheck` (capped at 2h ahead) and lays out
  an entry per minute while a countdown is showing and every 5 minutes
  otherwise, which covers what Android's `Ticker` does. iOS rations reloads, so
  a busy day can land later than the plan says; the data's age is always shown.
- **Tapping** a widget opens the app (`aiusage://card`), which refreshes and
  shows the tap card as a sheet; there's no floating card over the Home
  Screen. Unpaired, it opens pairing (`aiusage://app`). There's no "+ ADD"
  (iOS apps can't place widgets); the "•••" sheet lists them and says how.
- **Fonts.** No NType 82 on iOS; headlines use the system font, as on
  non-Nothing Android phones. Never bundle Nothing's fonts.
- **Colours.** The app uses the widget colours like Android: a neutral page
  (`Palette.page`) behind `Palette.background` cards, and the selected
  provider's accent. Home Screen widgets use a removable container background
  and `widgetAccentable`; iOS supplies the glass background when the owner
  chooses Clear appearance.
- **Providers.** "Widgets show" picks Claude, Claude 2 or Codex for the dash;
  the pool and the threads' figures pool that pick's family, and the orbit and
  the threads show every account. Every cache is kept warm, so switching is only a redraw.
  Each request writes only to its own provider's or family's cache. Keep
  actual quota durations from `window_minutes`.

## Conventions and gotchas

- Everything in `../android/CLAUDE.md`'s conventions applies: the bridge is
  the only source, reset times come only from `resets_at`, send the token as
  `Authorization: Bearer`, always show the data's age, **no notifications**,
  and refreshing is just `GET /usage`.
- Pairs as `<hardware model> widgets` (e.g. `iPhone17,1 widgets`) in the
  bridge's `state.json`. Revoke it there.
- Lay faces out in `Faces.draw` with absolute coordinates from the widget
  size, as Android does. Measure text with `Placed` (UIKit metrics), because
  a widget can't measure a view after layout.
- Check UI changes on a simulator with `-fixture` (app, `-card`, and the
  widgets on the Home Screen) against the Android screenshots.

## Repo status

Lives in `ios/` inside the **public** AIusageBar repo. Never put the live
bridge URL, tokens or addresses in tracked files; they go in the root's
gitignored `CLAUDE.local.md`. Ask before committing or pushing.
