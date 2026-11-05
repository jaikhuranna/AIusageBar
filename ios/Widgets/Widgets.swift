import SwiftUI
import WidgetKit

/// The widgets. iOS has no 1×1 Home Screen widget, so the ring lives on the
/// Lock Screen (pooled) and as the app's icon; the 2×2 is the orbit.
@main
struct ClaudeWidgets: WidgetBundle {
    var body: some Widget {
        PoolWidget()
        OrbitWidget()
        DashWidget()
        ThreadsWidget()
    }
}

/// Android's 1x1: the original ring, for every account of the selected family
/// pooled. Only the Lock Screen's circle is 1:1 on iOS.
struct PoolWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "pool", provider: Provider()) { UsageWidgetView(kind: .pool, entry: $0) }
            .configurationDisplayName("AI Usage pool")
            .description("Every account of the family pooled: what you can spend now as the ring, the week as the pie.")
            .supportedFamilies([.accessoryCircular])
            .contentMarginsDisabled()
            .containerBackgroundRemovable(true)
    }
}

/// The 2×2: every account's week as threads wound into concentric rings.
struct OrbitWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "orbit", provider: Provider()) { UsageWidgetView(kind: .orbit, entry: $0) }
            .configurationDisplayName("AI Usage orbit")
            .description("Every account's week as threads circling: Claude's outside, Codex's inside, bright for what's left.")
            .supportedFamilies([.systemSmall])
            .contentMarginsDisabled()
            .containerBackgroundRemovable(true)
    }
}

struct DashWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "dash", provider: Provider()) { UsageWidgetView(kind: .face(.dash), entry: $0) }
            .configurationDisplayName("AI Usage dash")
            .description("The most urgent number, both limits with their resets, and whether they'll last.")
            .supportedFamilies([.systemMedium])
            .contentMarginsDisabled()
            .containerBackgroundRemovable(true)
    }
}

/// Android's 4x1 threads; on the Home Screen, with the family's figures under them.
struct ThreadsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "threads", provider: Provider()) { UsageWidgetView(kind: .threads, entry: $0) }
            .configurationDisplayName("AI Usage threads")
            .description("Every account's week as threads twisted together, with the session left, the burn rate and what you can use now.")
            .supportedFamilies([.systemMedium, .accessoryRectangular])
            .contentMarginsDisabled()
            .containerBackgroundRemovable(true)
    }
}

enum WidgetKind {
    case pool, orbit, threads, face(Style)
}

struct UsageWidgetView: View {
    let kind: WidgetKind
    let entry: Entry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        content
            .containerBackground(for: .widget) {
                if family == .accessoryCircular || family == .accessoryRectangular { AccessoryWidgetBackground() }
                else { Rectangle().fill(.ultraThinMaterial) }
            }
            // Paired, a tap opens the card (and refreshes); unpaired, it opens pairing.
            .widgetURL(URL(string: entry.look.mode == .unpaired ? "aiusage://app" : "aiusage://card"))
            .accessibilityElement()
            .accessibilityLabel(label)
    }

    @ViewBuilder private var content: some View {
        switch kind {
        case let .face(style): Face(style: style, look: entry.look)
        // Unpaired, or with no pool from this bridge (an older one), the selected provider's own ring.
        case .pool: Face(style: .ring, look: entry.look.mode == .unpaired ? entry.look : entry.pool ?? entry.look)
        case .orbit: OrbitView(rings: entry.rings).padding(4)
        case .threads:
            if family == .systemMedium {
                ThreadsCard(entry: entry)
            } else {
                GeometryReader { g in
                    // The 4x1's proportions, whatever the widget's height.
                    let h = min(g.size.height, g.size.width / 4.4)
                    ThreadsView(strands: entry.strands)
                        .frame(width: g.size.width, height: h)
                        .position(x: g.size.width / 2, y: g.size.height / 2)
                }
            }
        }
    }

    private var label: String {
        switch kind {
        case .face: return entry.look.summary
        case .threads, .orbit: return entry.weeks + entry.stats.summary
        case .pool: return entry.pool.map { describePool($0, entry.poolAccounts) } ?? entry.look.summary
        }
    }
}

private func describePool(_ ring: Look, _ accounts: Int) -> String {
    let family = PoolFamily.of(ring.provider)
    let who = accounts > 1 ? "\(family.title), \(accounts) accounts pooled" : family.title
    switch ring.mode {
    case .unpaired, .waiting: return "\(family.title): no usage data yet. Tap for details."
    case .weekOut, .sessionOut:
        return "\(who): every limit reached" + (ring.countdownTo.map { ", back in \(untilText(from: ring.now, to: $0))" } ?? "") + "."
    case .go: return "\(who) is back."
    case .normal: return "\(who): \(ring.sessionLeft)% usable now, \(ring.weekLeft)% of the week left. Tap for details."
    }
}

/// The selected family's figures under the threads.
struct Stats: Equatable {
    var family: PoolFamily
    /// The pooled session left, %.
    var session: Int?
    /// How fast the family's week is going, % of the family per hour.
    var burn: Double?
    /// What every account together can spend right now (each one's tighter window), %.
    var usable: Int?

    var summary: String {
        " \(family.title): \(session.map { "\($0)% of the session left" } ?? "session unknown")"
            + (burn.map { String(format: ", burning %.1f%% an hour", $0) } ?? "")
            + (usable.map { ", \($0)% usable now." } ?? ".")
    }

    static func of(_ store: Store, now: Date) -> Stats {
        let family = PoolFamily.of(store.selected)
        let view = store.familyView(family, now: now)
        let ring = store.pooledRing(now: now)
        let accounts = max(view.known.count, 1)
        return Stats(
            family: family,
            session: Meter.of(view.members, .session, now: now).left,
            burn: store.poolAnswer(family, now: now)?.burnPctPerHour.map { $0 / Double(accounts) },
            usable: ring.flatMap { $0.mode == .waiting ? nil : $0.sessionLeft } ?? (view.known.isEmpty ? nil : Meter.of(view.members, .session, now: now).left)
        )
    }
}

struct Entry: TimelineEntry {
    let date: Date
    /// The selected provider's own look.
    let look: Look
    /// The selected family's pooled ring; nil without `/pool`.
    let pool: Look?
    let poolAccounts: Int
    /// The threads, one group per family, Claude's first.
    let rings: [[ThreadStrand]]
    let stats: Stats
    /// The threads, for VoiceOver.
    let weeks: String

    var strands: [ThreadStrand] { rings.flatMap { $0 } }

    static func at(_ t: Date, store: Store = .shared) -> Entry {
        let views = PoolFamily.allCases.map { store.familyView($0, now: t) }
        let weeks = views.flatMap { v -> [String] in
            v.known.isEmpty ? ["\(v.family.title) unknown"]
                : v.known.map { m in "\(v.known.count > 1 ? "\(v.family.title) \(m.number)" : v.family.title) \((m.week ?? m.session)?.remaining ?? 0)%" }
        }.joined(separator: ", ")
        return Entry(
            date: t, look: store.look(now: t), pool: store.pooledRing(now: t),
            poolAccounts: store.familyView(PoolFamily.of(store.selected), now: t).known.count,
            rings: store.rings(now: t), stats: Stats.of(store, now: t),
            weeks: "Weekly allowance left: \(weeks)."
        )
    }

    static func demo(_ now: Date) -> Entry {
        let look = Look.demos(now)[0]
        let rings = [[ThreadStrand(family: .claude, index: 0, left: 0.6, stale: false), ThreadStrand(family: .claude, index: 1, left: 0.71, stale: false)],
                     [ThreadStrand(family: .codex, index: 0, left: 0.3, stale: false)]]
        return Entry(date: now, look: look, pool: look, poolAccounts: 1, rings: rings,
                     stats: Stats(family: .claude, session: 64, burn: 2.1, usable: 58), weeks: "")
    }
}

/// The Home Screen threads: the twist up top, the selected family's figures
/// under it. Sizes come from the widget's height, like the dash's.
private struct ThreadsCard: View {
    let entry: Entry

    var body: some View {
        GeometryReader { g in
            let u = g.size.height / 100
            VStack(alignment: .leading, spacing: 0) {
                ThreadsView(strands: entry.strands)
                    .frame(height: 42 * u)
                    .padding(.top, 6 * u)
                Spacer(minLength: 0)
                HStack(alignment: .bottom, spacing: 0) {
                    stat("5H LEFT", entry.stats.session.map { "\($0)%" }, dot: true, u: u)
                    stat("BURN", entry.stats.burn.map(burnText), u: u)
                    stat("USABLE NOW", entry.stats.usable.map { "\($0)%" }, u: u)
                }
                .padding(.horizontal, 9 * u)
                .padding(.bottom, 10 * u)
            }
        }
    }

    private func burnText(_ b: Double) -> String {
        b < 10 ? String(format: "%.1f%%/H", b) : "\(Int(b.rounded()))%/H"
    }

    private func stat(_ label: String, _ value: String?, dot: Bool = false, u: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 2.5 * u) {
            HStack(spacing: 2.5 * u) {
                if dot { Circle().fill(Palette.member(entry.stats.family, 0)).frame(width: 3.4 * u, height: 3.4 * u) }
                Text(label)
                    .font(.system(size: 5.2 * u, weight: .medium)).tracking(0.52 * u)
                    .foregroundStyle(fg(0.55)).lineLimit(1)
            }
            Text(value ?? "--")
                .font(.system(size: 17 * u, weight: .regular)).tracking(-0.5 * u)
                .foregroundStyle(fg(value == nil ? 0.45 : 1)).lineLimit(1).minimumScaleFactor(0.6)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Fetches when `Plan.nextCheck` says it's time, then lays out a redraw for
/// every minute while a countdown is on screen and every 5 minutes otherwise
/// (reset times, pace ticks, GO! expiry), up to the next fetch.
struct Provider: TimelineProvider {
    /// How far ahead one timeline reaches when no fetch comes sooner.
    private static let horizon: TimeInterval = 2 * 3600

    func placeholder(in context: Context) -> Entry { Entry.demo(Date()) }

    func getSnapshot(in context: Context, completion: @escaping (Entry) -> Void) {
        let now = Date()
        // The widget gallery: show real numbers if there are any, a sample otherwise.
        let entry = Entry.at(now)
        completion(context.isPreview && entry.look.mode == .unpaired ? Entry.demo(now) : entry)
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<Entry>) -> Void) {
        Task {
            await Sync.shared.run(.due)
            completion(Provider.timeline(now: Date()))
        }
    }

    static func timeline(now: Date) -> Timeline<Entry> {
        let end = max(min(Store.shared.nextFetchAt ?? now + Plan.base, now + horizon), now + 60)
        var entries: [Entry] = []
        var t = now
        while t < end {
            let e = Entry.at(t)
            entries.append(e)
            // On the wall-clock minute, so the countdown ticks with the clock.
            let step: TimeInterval = e.look.ticking || e.pool?.ticking == true ? 60 : 300
            t = Date(timeIntervalSince1970: (floor(t.timeIntervalSince1970 / step) + 1) * step)
        }
        return Timeline(entries: entries, policy: .after(end))
    }
}
