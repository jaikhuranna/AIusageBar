# CLAUDE.md — iOS widgets (`ios/`)

## Goal

Show Claude and Codex subscription usage on an iPhone's Home Screen and Lock Screen,
with the same faces as the Android widgets in `../android/`: how much of the
5h session and the weekly limit is left, a countdown when one runs out, and a
"GO!" when it comes back. The app exists for the widgets. It pairs with the
desktop bridge (the Go app at the repo root), shows the details card, and has
a gallery of every widget.

## The widgets

| Widget | iOS family | Android original |
| --- | --- | --- |
| AI Usage ring | `systemSmall`, `accessoryCircular` (Lock Screen) | 2x2 ring, 1x1 ring |
| AI Usage matrix | `systemSmall` | 2x2 matrix |
| AI Usage dash | `systemMedium` | 4x2 dash |

The states (`Mode` in `Core/Look.swift`: UNPAIRED, WAITING, WEEK_OUT,
SESSION_OUT, GO, NORMAL, plus stale) and what each face draws in them are the
Android ones; see `../android/CLAUDE.md`.

## Layout

- `project.yml` — XcodeGen spec for the app and the widget extension. The
  `.xcodeproj`, `Info.plist` and entitlements files are generated from it
  and gitignored.
- `Core/` — the pure rules, Foundation only: `Model.swift`, `Plan.swift`,
  `Look.swift`, `Format.swift`, `DotFont.swift`. **Ports of the Android
  files of the same names** (wire format, cadence, comeback, modes, pace,
  time text, the dot font). Keep them in step with `../android/` and
  `../wearos/` when the bridge contract or the rules change.
- `CoreTests/` — ports of Android's `LookTest` and `PlanTest`.
- `Package.swift` — builds `Core/` and `CoreTests/` alone, so `swift test`
  runs anywhere, Linux included. The Xcode targets compile `Core/` in directly.
- `Shared/` — compiled into both the app and the extension:
  - `Store.swift` — one cache in the App Group's `UserDefaults`; the token
    in the Keychain (the shared App Group access group, this device only, after first
    unlock so a locked phone's widgets can still fetch).
  - `Bridge.swift` — `/usage` (ETag, Bearer token) and `/pair`.
  - `Sync.swift` — the one fetcher. The extension runs it on each timeline
    reload (it fetches only once `Store.nextFetchAt` has come); the app runs it
    on open (throttled to one a minute) and on Refresh (always).
  - `Glass.swift` — native `glassEffect` on iOS 26+, material fallback.
  - `Faces.swift` — the three faces, drawn like Android's `Render.kt`: ops on
    an ink layer (`Color.primary`, so it follows dark mode) and an accent layer
    (orange for Claude, green for Codex, yellow and grey; dimmed when stale; `widgetAccentable` for
    tinted mode). `clear` ops knock bands out like Android's `CLEAR` paint.
- `Widgets/Widgets.swift` — the bundle, the three widgets, and the timeline
  provider.
- `App/` — `AIusageApp.swift` (the `AppModel` over `Store`), `ContentView.swift`
  (the card and the gallery), `Pairing.swift` (Bonjour + 6-digit code).

## Build, test, install

```sh
swift test                         # Core rules; needs only a Swift toolchain
python3 Tests/check_sync.py         # Shared fetch concurrency, using a delayed mock bridge
brew install xcodegen && xcodegen  # on a Mac: writes AIusageWidget.xcodeproj
open AIusageWidget.xcodeproj       # set your team under Signing, run on a phone
```

Needs Xcode 26+ and iOS 17+. Native Liquid Glass is available on iOS 26+;
earlier versions use material backgrounds. Both targets must share the App Group
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
  shows the card. There's no floating card over the Home Screen, and no
  "pin this widget" (iOS apps can't); the gallery says how to add one.
- **Fonts.** No NType 82 on iOS; headlines use the system font, as on
  non-Nothing Android phones. Never bundle Nothing's fonts.
- **Liquid Glass.** The three faces keep Android's geometry. App cards and
  previews use `glassEffect` on iOS 26+ with a material fallback. Home Screen
  widgets use a removable container background and `widgetAccentable`; iOS
  supplies the glass background when the owner chooses Clear appearance.
- **Providers.** The app selects Claude or Codex for all widgets. Cache keys
  are scoped by provider, including ETags and comeback timestamps. Responses
  use their captured provider when writing because app and extension run in
  different processes. Keep actual quota durations from `window_minutes`.

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
- Written on Linux: `Core/` is compiled and tested there, but the SwiftUI and
  WidgetKit code had not been through Xcode when it was added. Expect small
  compile fixes on the first Mac build, then check every face in the app's
  gallery against the Android screenshots.

## Repo status

Lives in `ios/` inside the **public** AIusageBar repo. Never put the live
bridge URL, tokens or addresses in tracked files; they go in the root's
gitignored `CLAUDE.local.md`. Ask before committing or pushing.
