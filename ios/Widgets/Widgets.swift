import SwiftUI
import WidgetKit

/// The three widgets. Each is only a style and a set of sizes; the drawing is shared.
@main
struct ClaudeWidgets: WidgetBundle {
    var body: some Widget {
        RingWidget()
        MatrixWidget()
        DashWidget()
    }
}

struct RingWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "ring", provider: Provider()) { UsageWidgetView(style: .ring, entry: $0) }
            .configurationDisplayName("AI Usage ring")
            .description("Weekly left is the pie in the middle, the session the ring around it.")
            .supportedFamilies([.systemSmall, .accessoryCircular])
            .contentMarginsDisabled()
            .containerBackgroundRemovable(true)
    }
}

struct MatrixWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "matrix", provider: Provider()) { UsageWidgetView(style: .matrix, entry: $0) }
            .configurationDisplayName("AI Usage matrix")
            .description("The session's % left on an LED panel, the week as a dot bar.")
            .supportedFamilies([.systemSmall])
            .contentMarginsDisabled()
            .containerBackgroundRemovable(true)
    }
}

struct DashWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "dash", provider: Provider()) { UsageWidgetView(style: .dash, entry: $0) }
            .configurationDisplayName("AI Usage dash")
            .description("The most urgent number, both limits with their resets, and whether they'll last.")
            .supportedFamilies([.systemMedium])
            .contentMarginsDisabled()
            .containerBackgroundRemovable(true)
    }
}

struct UsageWidgetView: View {
    let style: Style
    let entry: Entry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        Face(style: style, look: entry.look)
            .containerBackground(for: .widget) {
                if family == .accessoryCircular { AccessoryWidgetBackground() } else { Rectangle().fill(.ultraThinMaterial) }
            }
            // Paired, a tap opens the app and refreshes; unpaired, it opens pairing.
            .widgetURL(URL(string: "aiusage://card"))
            .accessibilityElement()
            .accessibilityLabel(entry.look.summary)
    }
}

struct Entry: TimelineEntry {
    let date: Date
    let look: Look
}

/// Fetches when `Plan.nextCheck` says it's time, then lays out a redraw for
/// every minute while a countdown is on screen and every 5 minutes otherwise
/// (reset times, pace ticks, GO! expiry), up to the next fetch.
struct Provider: TimelineProvider {
    /// How far ahead one timeline reaches when no fetch comes sooner.
    private static let horizon: TimeInterval = 2 * 3600

    func placeholder(in context: Context) -> Entry {
        Entry(date: Date(), look: Look.demos(Date())[0])
    }

    func getSnapshot(in context: Context, completion: @escaping (Entry) -> Void) {
        let now = Date()
        // The widget gallery: show real numbers if there are any, a sample otherwise.
        let look = Store.shared.look(now: now)
        completion(Entry(date: now, look: context.isPreview && look.mode == .unpaired ? Look.demos(now)[0] : look))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<Entry>) -> Void) {
        Task {
            await Sync.shared.run(.due)
            completion(Provider.timeline(now: Date()))
        }
    }

    static func timeline(now: Date) -> Timeline<Entry> {
        let store = Store.shared
        let paired = store.paired
        let snap = store.snapshot()
        // Pin the brand to this snapshot while another process may switch.
        let provider = snap?.provider ?? store.provider
        let (fetchedAt, lastError, backAt) = (store.fetchedAt, store.lastError, store.backAt)
        func look(_ t: Date) -> Look {
            var look = Look.of(paired: paired, snap: snap, fetchedAt: fetchedAt, lastError: lastError, backAt: backAt, now: t)
            look.provider = provider
            return look
        }

        let end = max(min(store.nextFetchAt ?? now + Plan.base, now + horizon), now + 60)
        var entries: [Entry] = []
        var t = now
        while t < end {
            let l = look(t)
            entries.append(Entry(date: t, look: l))
            // On the wall-clock minute, so the countdown ticks with the clock.
            let step: TimeInterval = l.ticking ? 60 : 300
            t = Date(timeIntervalSince1970: (floor(t.timeIntervalSince1970 / step) + 1) * step)
        }
        return Timeline(entries: entries, policy: .after(end))
    }
}
