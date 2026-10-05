import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var phase

    var body: some View {
        // Re-read once a minute too, so countdowns and "updated" ages move.
        TimelineView(.everyMinute) { ctx in
            let look = model.look(at: ctx.date)
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if model.store.paired {
                        Picker("Provider", selection: Binding(get: { model.store.provider }, set: { provider in
                            Task { await model.selectProvider(provider) }
                        })) {
                            ForEach(UsageProvider.allCases, id: \.self) { Text($0.title).tag($0) }
                        }
                        .pickerStyle(.segmented)
                        .disabled(model.busy)
                        Text("All widgets show the selected provider.").font(.caption).foregroundStyle(.secondary)
                        if let error = model.store.snapshot()?.sourceError {
                            Text(error).font(.callout).foregroundStyle(.secondary)
                        }
                    }
                    if look.mode == .unpaired {
                        if model.store.lastError == .unpaired {
                            Text("The desktop no longer knows this phone. Pair again.").foregroundStyle(.red)
                        }
                        PairingView()
                    } else {
                        CardView(look: look)
                    }
                    GalleryView()
                }
                .padding(20)
            }
            .background {
                LinearGradient(colors: [Palette.accent(model.store.provider).opacity(0.16),
                                        Color.primary.opacity(0.03), Color.clear],
                               startPoint: .topLeading, endPoint: .bottomTrailing)
                    .ignoresSafeArea()
            }
        }
        // Opening the app is a refresh (throttled to one a minute).
        .onChange(of: phase, initial: true) { _, p in
            if p == .active { Task { await model.refresh(.manual) } }
        }
    }
}

/// Both limits, their resets and pace, the data's age, and Refresh: the
/// Android widgets' tap card, as the app's front page.
struct CardView: View {
    @EnvironmentObject private var model: AppModel
    let look: Look

    var body: some View {
        let snap = model.store.snapshot()
        let age = agoText(snap?.cacheFetchedAt ?? look.fetchedAt, now: look.now).uppercased()
        let figure = hero(look)
        VStack(alignment: .leading, spacing: 20) {
            HStack {
                CapsLabel(look.provider.title.uppercased(), alpha: 1)
                Spacer()
                CapsLabel(model.busy ? "REFRESHING…" : look.offline ? "OFFLINE · \(age)" : "UPDATED \(age)", alpha: 0.55)
            }
            HStack(spacing: 20) {
                Face(style: .ring, look: look)
                    .frame(width: 104, height: 104)
                VStack(alignment: .leading) {
                    Text(figure.big).font(.system(size: 60)).kerning(-1.8).foregroundStyle(figure.color)
                    CapsLabel(figure.caption, alpha: 0.7)
                }
            }
            WindowRow(label: look.sessionLabel, e: look.session, length: look.sessionLength, look: look, timer: look.mode == .sessionOut)
            WindowRow(label: look.weekLabel, e: look.week, length: look.weekLength, look: look, timer: false)
            HStack {
                Button { Task { await model.refresh(.force) } } label: {
                    CapsLabel(model.busy ? "REFRESHING" : "REFRESH", alpha: 1)
                        .padding(.horizontal, 22).padding(.vertical, 12)
                        .usageGlass(cornerRadius: 24)
                }
                .disabled(model.busy)
                Spacer()
                Button { model.unpair() } label: { CapsLabel("UNPAIR", alpha: 0.7).padding(12) }
            }
            if snap != nil, let host = model.store.host {
                CapsLabel("FROM \(host.uppercased())", alpha: 0.35, size: 10)
            }
        }
        .foregroundStyle(.primary)
        .padding(24)
        .usageGlass(cornerRadius: 32)
    }
}

/// The big figure: whichever window is closest to biting, or the countdown.
private func hero(_ look: Look) -> (big: String, caption: String, color: Color) {
    let countdown = look.countdownTo.map { countdownText(from: look.now, to: $0) } ?? "OUT"
    switch look.mode {
    case .unpaired: return ("PAIR", "PAIR BELOW", Palette.grey)
    case .waiting: return ("…", "WAITING FOR THE DESKTOP", Palette.grey)
    case .normal:
        return look.week == nil || (look.session != nil && look.sessionLeft <= look.weekLeft)
            ? ("\(look.sessionLeft)%", "OF THE \(look.sessionLabel) LIMIT LEFT", Palette.accent(look.provider))
            : ("\(look.weekLeft)%", "OF THE \(look.weekLabel) LIMIT LEFT", Palette.accent(look.provider))
    case .sessionOut: return (countdown, "UNTIL THE \(look.sessionLabel) RESET", Palette.yellow)
    case .weekOut: return (countdown, "\(look.weekLabel) LIMIT HIT", Palette.grey)
    case .go: return ("GO!", "\(look.provider.title.uppercased())'S BACK", Palette.accent(look.provider))
    }
}

private struct WindowRow: View {
    let label: String
    let e: Effective?
    let length: TimeInterval
    let look: Look
    let timer: Bool

    var body: some View {
        let paired = look.mode != .unpaired && look.mode != .waiting && e != nil
        let remaining = e?.remaining ?? 100
        let out = paired && remaining <= 0
        let color = look.mode == .weekOut ? Palette.grey : timer ? Palette.yellow : Palette.accent(look.provider)
        let reset = e?.resetsAt
        let lit: Double = !paired || look.mode == .weekOut ? 0 : timer ? look.sessionTimerFraction() : Double(remaining) / 100
        let tick: Double? = paired && !out && look.mode != .weekOut ? reset.map { min(max($0.timeIntervalSince(look.now) / length, 0), 1) } : nil
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                CapsLabel(label, alpha: 0.9)
                Spacer()
                Text(!paired ? "--" : out ? "OUT" : "\(remaining)%")
                    .font(.system(size: 28))
                    .foregroundStyle(paired ? color : .primary)
            }
            DotBar(lit: lit, on: color, tick: tick)
            if let sub = subtitle(paired: paired, out: out, reset: reset) {
                Text(sub).font(.system(size: 13)).foregroundStyle(fg(0.6))
            }
        }
    }

    private func subtitle(paired: Bool, out: Bool, reset: Date?) -> String? {
        guard paired else { return nil }
        guard let reset else { return out ? "Reset time unknown" : "Window not started" }
        let when = "\(clockText(reset, now: look.now)), in \(untilText(from: look.now, to: reset))"
        if out || timer { return "Back at \(when)" }
        let p = switch pace(e, length: length, now: look.now) {
        case .lasts?: " · at this pace it lasts"
        case let .runsOut(at)?: " · at this pace empty in ~\(untilText(from: look.now, to: at))"
        default: ""
        }
        return "Resets \(when)\(p)"
    }
}

/// A row of dots, like the widgets' bars, with the "time left" tick.
private struct DotBar: View {
    let lit: Double
    let on: Color
    let tick: Double?

    var body: some View {
        Canvas { ctx, size in
            let spacing: CGFloat = 9
            let n = max(Int(size.width / spacing), 1)
            let count = lit > 0 ? Int((Double(n) * lit).rounded(.up)) : 0
            for i in 0..<n {
                let c = CGPoint(x: (CGFloat(i) + 0.5) * spacing, y: size.height / 2)
                ctx.fill(.dot(c, 2.6), with: .color(i < count ? on : fg(0.13)))
            }
            if let tick {
                let x = tick * CGFloat(n) * spacing
                ctx.stroke(.line(CGPoint(x: x, y: 0), CGPoint(x: x, y: size.height)), with: .color(fg()), lineWidth: 1.5)
            }
        }
        .frame(height: 12)
    }
}

/// Medium caps, tracked out 0.1, as Nothing sets labels.
private struct CapsLabel: View {
    let text: String
    let alpha: Double
    let size: CGFloat

    init(_ text: String, alpha: Double, size: CGFloat = 12) {
        self.text = text
        self.alpha = alpha
        self.size = size
    }

    var body: some View {
        Text(text).font(.system(size: size, weight: .medium)).tracking(size * 0.1).opacity(alpha)
    }
}

/// Every widget in every state, drawn by the same code the home screen uses.
struct GalleryView: View {
    @EnvironmentObject private var model: AppModel
    private let now = Date()

    private struct Spec {
        let name: String
        let style: Style
        let size: CGSize
        let round: Bool
    }

    private let widgets = [
        Spec(name: "Ring · Lock Screen", style: .ring, size: CGSize(width: 72, height: 72), round: true),
        Spec(name: "Ring · small", style: .ring, size: CGSize(width: 150, height: 150), round: false),
        Spec(name: "Matrix · small", style: .matrix, size: CGSize(width: 150, height: 150), round: false),
        Spec(name: "Dash · medium", style: .dash, size: CGSize(width: 320, height: 150), round: false),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Widgets").font(.title2.bold())
            Text("To add one, touch and hold the Home Screen or Lock Screen, tap Edit, then Add Widget, and search for AI Usage. Select Clear in Home Screen customization for Liquid Glass widgets.")
                .font(.footnote).foregroundStyle(.secondary)
            ForEach(widgets, id: \.name) { w in
                Text(w.name).font(.headline)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(Array(Look.demos(now).map { sample in var look = sample; look.provider = model.store.provider; return look }.enumerated()), id: \.offset) { _, look in
                            Face(style: w.style, look: look)
                                .frame(width: w.size.width, height: w.size.height)
                                .usageGlass(cornerRadius: w.round ? 75 : 24)
                                .clipShape(w.round ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: 22)))
                        }
                    }
                }
            }
        }
    }
}
