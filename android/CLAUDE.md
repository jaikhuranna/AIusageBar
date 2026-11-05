# CLAUDE.md — Android widgets (`android/`)

## Goal

Show Claude (one or more accounts, pooled) and Codex subscription usage on an Android home screen, in Nothing OS's
widget style: how much of the 5h session and the weekly limit is left, a
countdown when one runs out, and a "GO!" when it comes back. It's the phone
sibling of the Wear OS app in `../wearos/`, and reads the same standalone Go server
(`cmd/aiusage-server` at the repo root) the same way.

The app (MainActivity) is the detail behind the widgets, after T3 Code's mobile
Usage sheet but in this app's language (dark cards, tracked caps, NType
numerals, dot-matrix accents). Per family (Claude, then Codex): a Session card
and a Weekly card, each with the share of the whole family left ("64% left"),
what the next reset brings back ("↻ +28% in 3h 30m"), a pace mark, a bar cut
into one segment per account, and a row per account (number, email, % left,
reset countdown). No widget faces in the app. Pairing, "Widgets show"
(which provider the widgets draw) and adding widgets sit in a bottom sheet
behind the header's "•••" button. The widget tap card is the short version
(below). Codex uses a green accent; Claude
uses orange; Claude 2 (the second hub account, `UsageProvider.CLAUDE2`) a tan
sibling of the orange (`Render.CLAUDE2`, `#BF8B5A`), so both read as Claude.
When the bridge reports the account email (`account`), the app shows it under
the provider name, elided in the middle when long. `window_minutes` controls window
labels, pace and timer fractions. An unreported window stays unknown.
Every sync fetches every provider in parallel into separate caches
(`Store(ctx, provider)`, keys suffixed `_claude` / `_claude2` / `_codex`), so switching is
only a redraw: never gate it on a refresh, and never clear a cache on switch.

## Pools (`GET /pool?family=claude|codex`)

A family is every account whose limits pool together (`PoolFamily`): Claude is
the hub's 1..N accounts, Codex a pool of one. Each sync fetches both families
beside `/usage` into `PoolStore` (keys `pool_claude` / `pool_codex`). The wire
contract is in the root README, "Pooled limits (`/pool`)". The members and
sums drive the limit cards. Recorded five-hour history drives the tap card's
session graph; weekly projection and burn figures are not drawn.

- **`FamilyView`** (`Family.kt`, built by `familyView` in `Store.kt`) is what
  the app and card draw: the family's accounts as `Member`s, numbered from 1.
  From `/pool` when the bridge has it, else from the family's `/usage`
  snapshots. Its age is the oldest snapshot's `cache_fetched_at`.
- **`Meter.of(members, limit, now)`** is one limit across a family, pure and
  tested (`FamilyTest`): `left` is Σ remaining ÷ accounts (45% and 82% → 64%);
  `comeback` is the soonest reset that gives anything back, summing accounts
  resetting within a minute of it, as a share of the family (at least 1%);
  `pace` compares each running window's left with its time left (more than 5
  points behind is "Ahead of pace", more than 5 ahead "Under pace"; none in a
  window's first 10 min or when the family is out). `segmentSpans` lays out
  the equal segments.
- **The 1x1** (`Style.POOL`) is the original ring face (`RingFace`), drawn from
  a pooled `Look` (`poolRing` in `Pool.kt`): the ring is what every account
  together can spend now (Σ each account's tighter window ÷ capacity), the pie
  the pooled week. Every week out → WEEK_OUT; nothing spendable → SESSION_OUT
  counting to the first comeback; a family account's `backAt` within the hour
  with the pool half full → GO. No accounts or no data → "…". A sliver never
  rounds to an empty ring, nor almost-full to a closed one.
- **The tap card** (`CardActivity`) never scrolls: per family two columns
  (5h and 7d: big %, a slim per-account bar whose spent stretch is a row of dots like the app's bars, the comeback line), then a compact
  five-hour history graph with a remembered Claude | Codex toggle, then every
  account's week as threads ("7D · ALL"), then Refresh / Open app. The graph
  uses a coloured pooled line (0–100%, matching the headline), gradient fill,
  a NOW dot and dashed future reset steps. Each recorded or upcoming reset
  has an account-coloured notch and time, plus its "+N%" gain where space
  permits. Future steps assume no additional spending. Long recording gaps
  stay blank, and the line is cropped to the last 12 hours.
- **Older bridges answer 404**: `PoolStore` is cleared, the 1x1 draws the
  selected provider's own ring, and the app and card build each family from
  its providers' snapshots instead.

## The five widgets

| Widget | Size | Look |
| --- | --- | --- |
| Pool | 1x1 | The original ring, for every account of the selected family pooled: spendable now as the ring, the pooled week as the pie. Without `/pool`, the selected provider's own ring. |
| Claude ring, large | 2x2 | Round face. Weekly left is the pie in the middle, the 5h session is the ring around it. All Claude orange. |
| AI orbit | 2x2 | The threads wound into concentric rings, one per family: Claude's accounts twisted round the outer circle, Codex's round the inner one (a lone account is a plain circle). Each strand is bright clockwise from 12 o'clock for its week left, with a bead where bright meets faint. No figures. Replaced the dot-matrix 2x2 (decommissioned on Android and iOS). |
| Claude dash | 4x2 | The most urgent number on the left; both windows on the right as dot bars with a "time left" tick, reset times, and a pace estimate ("LASTS" / "EMPTY IN ~1H"). |
| AI threads | 4x1 (resizes to 5x1) | Every account's weekly limit as threads twisted round each other, left to right, in `Render.memberColor` order: Claude's accounts orange, tan, then further shades; Codex green (two strands are opposite; three are a third of a turn apart). A family with no accounts keeps one faint thread. Each is bright for the share of its week left and faint (not gone) where it's been used. No figures: a low, long wave, so the crossings are few and shallow (about 28°). The card ends with the same threads. |

The threads ignore the "Widgets show" pick and the session states: they
only draw each account's week (unknown or out is all faint), dimmed when
that family is stale. The card draws them `fancy`: a deeper twist, a fine ghost
for the used stretch (with a gap cut only as wide as itself), a bead where
bright meets faint, and strokes about 25% thinner than the earlier card.
The weekly threads and session graph are static, with no glow or reveal.
Crossings go over and under like a rope seen from the side (`Braid.kt`, tested
in `BraidTest`): strand `i` is a point on a turning circle, `2π·i/n` round, so
its height is the sine and its nearness the cosine, and at every crossing one
strand is clearly in front for any number of accounts. `ThreadFace` draws each
strand whole, then at each crossing clears a gap round the front strand for as
long as the two are close and draws it again. Never cut the braid into strips:
strip edges leak a comb of slivers wherever a gap is cleared. Bright and faint
are separate butt-ended paths, so they meet square to the thread. The orbit is
the same braid on a closed path, with whole twists per turn so it closes. On
the orbit each bright stretch fades in quickly at 12 o'clock (`Look.orbit`'s
`fadeIn`, 7% of the turn) instead of starting on a hard edge, and its beads are
smaller than the card's. Where a crossing redraws the front strand over its
cleared gap, the bright redraw overlaps the strand by `SEAM` either side: two
anti-aliased butt edges meeting on one line leave a hairline seam. The watch's
orbit (`../wearos/`) and `../ios/Shared/Faces.swift` draw the same braid.

States, shared by the ring and dash (`Mode` in `Look.kt`, highest priority first; the pooled 1x1 has its own `PoolState`):

- **UNPAIRED / WAITING** — "PAIR" or "...".
- **WEEK_OUT** — everything grey, counting down to the weekly reset. The ring
  is struck through like a no-entry sign; the dash strikes only the spent bar. Never draw a strike over
  text.
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
    watch app when the bridge contract changes (the `account` field and the
    `CLAUDE2` enum entry are copied to the watch and `../ios/Core/Model.swift`;
    the watch's own UI stays Claude/Codex).
  - `Bridge.kt` — the watch's client minus `/events`, plus `/pool` (404 → `NotFound`).
  - `Pool.kt` — `/pool` parsing and `poolRing` (the pooled 1x1's `Look`).
    Unit-tested in `PoolTest`. `../ios/Core/Pool.swift` ports it (and
    `Family.kt`, `SessionChart.kt`). The watch reads `/pool` only for its
    orbit (`../wearos/wear/.../Orbit.kt`, which ports `ThreadFace.orbit`).
  - `SessionChart.kt` — pure five-hour chart logic: pooled history, recorded
    reset markers, future reset steps and label placement. Tested in
    `SessionChartTest`; older bridges fall back to pooled history and
    inferred reset jumps.
  - `Family.kt` — `Member`, `FamilyView`, `Meter` (left, comeback, pace,
    segments) and `segmentSpans`. Pure; unit-tested in `FamilyTest`.
  - `Store.kt`, `Sync.kt` — one cache per provider and one per family
    (`PoolStore`), one fetcher that asks for all of them at once, WorkManager one-shot jobs on the soonest of the
    providers' `Plan.nextCheck`.
  - `Look.kt` — the pure state: mode, stale, countdown, pace. Unit-tested.
    `../ios/Core/` ports this and the other pure files (Model, Plan, Format,
    DotFont), and `../ios/Shared/Faces.swift` ports `Render.kt`. A rule or face
    change here should land there too.
  - `DotFont.kt` — a hand-made 5×7 dot-matrix face (Ndot-like, no font
    file). Small accents only; see Typography.
  - `Render.kt` — the faces (`RingFace`, `DashFace`, `ThreadFace` for the
    threads and the orbit) and `memberColor`. `Braid.kt` is the twist's pure geometry. `ThreadFace` takes a `ThreadStrand` per
    account (`strandsOf` in `Widgets.kt`), so `Sync` redraws the threads when
    any provider answers.
  - `Widgets.kt` — the five `AppWidgetProvider`s, sizing, and `Ticker`
    (redraws each minute during a countdown, every 5 min otherwise, never
    waking the phone).
  - `CardActivity.kt` — what a widget tap opens once paired: a Nothing-style
    card over the blurred home screen, short enough never to scroll: each
    family's `FamilySummary`, the threads, Refresh and Open app. Opening it
    refreshes. Its own task, so dismissing
    it returns to the home screen. Unpaired, a tap opens the app instead.
    The tap is a broadcast to `CardReceiver`, which starts the card: a tap
    that launches an activity directly gets the launcher's widget-to-window
    morph, which drew a stretched box behind the card on Nothing Launcher.
  - `Usage.kt` — the Compose pieces the app and the card share: `FamilyHeader`,
    `MeterCard` (the app's Session/Weekly card), `SegmentBar` (lit in each
    account's colour for what's left, a dot-grid ghost where it's spent, a tick
    at the share of the window still to run, the account's number in
    `DotFont` dots), `ResetArrow`/`ResetLine`, `Threads`, `Label`, `Text`.
  - `MainActivity.kt`, `Pairing.kt` — pairing (NSD + 6-digit code, like the
    watch), the family cards, and the settings sheet: "Widgets show", "Add to
    home screen" (by name; no faces in the app), Unpair behind a confirmation.
    The app uses the widget colours (`app_bg` page, `widget_bg` cards) and the
    selected provider's accent, not Material You's dynamic colours. A family
    with no accounts shows the bridge's `source_error` in its own card.

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
  account numbers on the app's bars. Don't bring them back for big numbers.

The emulator has no NType 82, so screenshots there show the fallback.

## Checking the design

A **debug** build can draw made-up pools (`src/debug/.../Fixtures.kt`: three
Claude accounts, one out for the session, and three Codex ones (the first out for the week); release
has a stub that returns null), and its manifest overlay exports the card so
`am start` can open it:

Open the emulator in T3 Code's Device panel (root `CLAUDE.md`) and launch
through its `agent-device`:

```sh
agent-device open com.jaikhurana.aiusagewidget --relaunch --launch-args --ez --launch-args fixture --launch-args true
```

(`adb shell am start -n …/.CardActivity --ez fixture true` opens the card.)

Opening the app with the fixture extra also hands it to the widgets (a
debug-only pref, `Fixtures.setWidgets`), so every face can be judged on the
home screen; opening the app without it turns that off again.

For live numbers, pair an emulator with the real server (`10.0.2.2:8765` is
the host, or the mDNS entry): `aiusage-server -pair` mints the code. Don't
force-stop the app before a home-screen screenshot: the Pixel launcher then
draws its "app paused" placeholder over the widgets.

The release build keeps the card unexported and ships no fixtures. Screenshots
need an unlocked screen; the phone usually isn't, so use an emulator.

## Build and install

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease   # SDK at ~/Android (local.properties)
adb -s <device> install -r app/build/outputs/apk/release/app-release.apk
```

Same pinned toolchain as the watch app (AGP 9.2.1, Gradle 9.4.1, Kotlin 2.2.10,
compileSdk 37). Several emulators and the watch are often on adb; always
pass `-s`. `emulator-5554` is a phone running Nothing Launcher, good for
checking the widgets.

**Every APK Jai should check on the phone needs a link Jai can open.** If
it's attached to a GitHub release, the release URL is enough; don't serve it
too. Otherwise serve it on the home network and the tailnet, per the "Local
servers" rule in the global `~/.claude/CLAUDE.md`: put only the APK in a
fresh directory, serve that directory (not port 8765), and give both
clickable links after checking they answer. The addresses belong in
`../CLAUDE.local.md`, never in this file.

Releases: bump `versionCode`/`versionName` in `app/build.gradle.kts`, build
the release APK, and attach it as `AIusageWidget-<version>.apk` with
`gh release create widget-v<version> <apk>` (prefixed, since the repo's tags
are shared with the tray and the watch). The APK is signed with the local debug
key, so an update installs over the old one only from this machine's builds.

Widget-picker previews for the orbit, dash and threads
(`res/drawable-nodpi/preview_*.png`) are crops of home-screen screenshots
(the app no longer has a gallery). Re-take them if those faces change.

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
