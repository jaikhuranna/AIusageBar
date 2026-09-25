# CLAUDE.md — Android widgets (`android/`)

## Goal

Show Claude subscription usage on an Android home screen, in Nothing OS's
widget style: how much of the 5h session and the weekly limit is left, a
countdown when one runs out, and a "GO!" when it comes back. It's the phone
sibling of the Wear OS app in `../wearos/`, and reads the same desktop bridge
(the Go app at the repo root) the same way.

## The four widgets

| Widget | Size | Look |
| --- | --- | --- |
| Claude ring | 1x1 | Round face. Weekly left is the pie in the middle, the 5h session is the ring around it. All Claude orange. |
| Claude ring, large | 2x2 | The same face, bigger. |
| Claude matrix | 2x2 | An LED dot panel: the session's % left set in type in a window cut out of the dots, small dot labels (5h reset in the corner), the week as a dot bar. |
| Claude dash | 4x2 | The most urgent number on the left; both windows on the right as dot bars with a "time left" tick, reset times, and a pace estimate ("LASTS" / "EMPTY IN ~1H"). |

States, shared by all four (`Mode` in `Look.kt`, highest priority first):

- **UNPAIRED / WAITING** — "PAIR" or "...".
- **WEEK_OUT** — everything grey and struck through diagonally, counting down
  to the weekly reset.
- **SESSION_OUT** — the 5h ring/bar turns into yellow dots that drain as the
  reset nears, with an H:MM countdown.
- **GO** — "GO!" for an hour after a comeback (`Store.backAt`), or until the
  session is half used again, whichever comes first.
- **NORMAL**.
- **Stale** (offline, or more than 1h since a good fetch) dims the accents.
  An exhausted limit only goes stale by being offline: nothing is polled, or
  can change, until its reset.

## Layout

- `app/` — the only module. Package `com.jaikhurana.aiusagewidget`, minSdk 31.
  - `Model.kt`, `Plan.kt`, `Format.kt` — **copied from the watch app** in `../wearos/`
    (wire format, cadence and comeback rules, time text). Keep them in step with the
    watch app when the bridge contract changes.
  - `Bridge.kt` — the watch's client minus `/events`.
  - `Store.kt`, `Sync.kt` — one cache, one fetcher, WorkManager one-shot jobs
    on `Plan.nextCheck`'s cadence.
  - `Look.kt` — the pure state: mode, stale, countdown, pace. Unit-tested.
  - `DotFont.kt` — a hand-made 5×7 dot-matrix face (Ndot-like, no font
    file). Small accents only; see Typography.
  - `Render.kt` — the three faces (`RingFace`, `MatrixFace`, `DashFace`).
  - `Widgets.kt` — the four `AppWidgetProvider`s, sizing, and `Ticker`
    (redraws each minute during a countdown, every 5 min otherwise, never
    waking the phone).
  - `CardActivity.kt` — what a widget tap opens once paired: a Nothing-style
    card over the blurred home screen (both limits, resets, pace, the data's
    age, Refresh, Open app). Opening it refreshes. Its own task, so dismissing
    it returns to the home screen. Unpaired, a tap opens the app instead.
    The tap is a broadcast to `CardReceiver`, which starts the card: a tap
    that launches an activity directly gets the launcher's widget-to-window
    morph, which drew a stretched box behind the card on Nothing Launcher.
  - `MainActivity.kt`, `Pairing.kt` — pairing (NSD + 6-digit code, like the
    watch), status, and a gallery of every widget in every state. Tapping a
    gallery entry pins that widget.

## How the Nothing look works

Colors are Nothing OS's Material You tokens: the widget
background is `system_neutral1_50` (light) / `system_neutral1_900` (dark),
elements are black / white. Those are in `res/values*/colors.xml`, not code.

Each widget is two bitmaps over a background drawable:
- **ink**, drawn white and tinted by `android:tint="@color/widget_elements"`
  in the layout;
- **accent**, with the fixed orange `#D97757`, yellow and grey.

The launcher resolves the background and the tint with its own
configuration, so dark mode and wallpaper changes apply **without a redraw**.
Never bake the background or element color into a bitmap.

## Typography

Follows Nothing OS 3+, where NType 82 replaced Ndot for headlines and Ndot
became an accent:

- **Big numbers and words** (%, countdowns, "GO!", "PAIR") use `Fonts.headline`
  via `fitText`. It asks the system for `NType82-Headline` /
  `NType82-Regular` / `ntype82` (Nothing's own widgets request NType 82 and
  `ndot` by family name, and bundle no font files), and falls back to the
  system sans elsewhere. Never bundle Nothing's fonts.
- **Labels** use Roboto medium in caps with 0.1 letter spacing, as Nothing's
  pedometer widget does.
- **Dots** (`DotFont`) only at small sizes: the dash's "CLAUDE" header and the
  matrix panel's labels. Don't bring them back for big numbers.

The emulator has no NType 82, so screenshots there show the fallback.

## Build and install

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease   # SDK at ~/Android (local.properties)
adb -s <device> install -r app/build/outputs/apk/release/app-release.apk
```

Same pinned toolchain as the watch app (AGP 9.2.1, Gradle 9.4.1, Kotlin 2.2.10,
compileSdk 37). Several emulators and the watch are often on adb; always
pass `-s`. `emulator-5554` is a phone running Nothing Launcher, good for
checking the widgets.

Releases: bump `versionCode`/`versionName` in `app/build.gradle.kts`, build
the release APK, and attach it as `AIusageWidget-<version>.apk` with
`gh release create widget-v<version> <apk>` (prefixed, since the repo's tags
are shared with the tray and the watch). Releases up to 0.3.5 were cut from
the old separate repo. The APK is signed with the local debug
key, so an update installs over the old one only from this machine's builds.

Widget-picker previews for the matrix and dash
(`res/drawable-nodpi/preview_*.png`) are crops of the app's own gallery
screenshots. Re-take them if those faces change.

## Conventions and gotchas

- Everything in `../wearos/CLAUDE.md`'s conventions applies: never read usage from
  Anthropic directly (the bridge is the only source), only use `resets_at` for
  reset times, send the token as `Authorization: Bearer`, and show the data's
  age.
- **No notifications.** The bridge's alert events are the watch's job; two
  devices buzzing for one crossing is noise.
- The "time left" tick and the pace estimate infer a window's start from its
  length (5h/7d). They're display only; nothing schedules off them.
- Paired as `<model> widgets` in the bridge's `state.json`. Revoke it there.
- **Refreshing is just `GET /usage`.** The bridge has Claude Code refresh a
  stale cache before answering (`../README.md` → Freshness), so
  there's no refresh endpoint to call. `cache_fetched_at` in the snapshot is
  the true age of the numbers; the card shows it.
- **Cadence** (`Plan.nextCheck`, shared with the watch): every 30 min with
  more than half left, 15 min (the WorkManager floor) below that, just after
  each reset, and no polling while a limit is out.

## Repo status

Lives in `android/` inside the **public** AIusageBar repo, next to the bridge
it reads. It was a separate private repo until 2026-09-25; that history wasn't
imported, and the old repo is archived. Public means: never put the live bridge
URL, tokens or addresses in tracked files. They go in the root's gitignored
`CLAUDE.local.md`. Ask before committing or pushing, as for the rest of the repo.
