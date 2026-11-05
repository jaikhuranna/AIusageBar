import SwiftUI

/// What a widget tap opens, like Android's floating tap card: the essentials,
/// short enough never to scroll. Each family's session and week as a share of
/// all its accounts, with what the next reset brings back and a slim bar split
/// per account; the five-hour graph; every account's week as twisted threads;
/// then Refresh and Open app. iOS can't float a card over the Home Screen, so
/// it is a sheet over the app, which opening already refreshed.
struct CardView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    /// The card's own height: the sheet fits it, so it never scrolls or floats in empty space.
    @State private var height: CGFloat = 640

    var body: some View {
        TimelineView(.everyMinute) { ctx in
            let _ = model.version
            let now = ctx.date
            let views = model.views(at: now)
            let chart = model.store.chartFamily
            VStack(alignment: .leading, spacing: 18) {
                ForEach(views, id: \.family) { FamilySummary(view: $0, now: now) }
                SessionGraph(view: views.first { $0.family == chart } ?? views[0], now: now) { model.pickChart($0) }
                Threads(strands: strandsOf(views, now: now))
                HStack {
                    Button { Task { await model.refresh(.force) } } label: {
                        CapsLabel(model.busy ? "REFRESHING" : "REFRESH", color: .white)
                            .padding(.horizontal, 20).padding(.vertical, 12)
                            .frame(minHeight: 44)
                            .background(Palette.accent(model.store.selected).opacity(model.busy ? 0.5 : 1), in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(model.busy)
                    Spacer()
                    let from = [model.store.host.map { "FROM \($0.uppercased())" }, Fixtures.enabled ? "FIXTURE" : nil].compactMap { $0 }.joined(separator: " · ")
                    if !from.isEmpty { CapsLabel(from, alpha: 0.35, size: 10, oneLine: true) }
                    Button { dismiss() } label: {
                        CapsLabel("OPEN APP", alpha: 0.7).padding(.horizontal, 12).frame(minHeight: 44)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(22)
            // Its natural height, not what the sheet offers, so the sheet can fit it.
            .fixedSize(horizontal: false, vertical: true)
            .background(GeometryReader { g in Color.clear.preference(key: CardHeight.self, value: g.size.height) })
            .frame(maxHeight: .infinity, alignment: .top)
        }
        .onPreferenceChange(CardHeight.self) { height = $0 }
        .presentationDetents([.height(height)])
        .presentationBackground(Palette.background)
        .presentationCornerRadius(32)
    }
}

private struct CardHeight: PreferenceKey {
    static let defaultValue: CGFloat = 640
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

/// One family in two columns: session and week, each a number, its comeback and a slim bar.
private struct FamilySummary: View {
    let view: FamilyView
    let now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            FamilyHeader(view: view, trailing: ageText(view, now: now))
            if view.known.isEmpty {
                Text(view.error ?? "No numbers yet.").font(.system(size: 13)).lineSpacing(3).foregroundStyle(fg(0.55))
            } else {
                HStack(alignment: .top, spacing: 20) {
                    ForEach(Limit.allCases, id: \.self) { limit in
                        let meter = Meter.of(view.members, limit, now: now)
                        VStack(alignment: .leading, spacing: 6) {
                            HStack(alignment: .firstTextBaseline, spacing: 6) {
                                Text(meter.left == nil ? "--" : meter.out ? "OUT" : "\(meter.left!)%")
                                    .font(headline(34)).tracking(-0.68)
                                    .foregroundStyle(fg(meter.out ? 0.45 : 1))
                                CapsLabel(durationLabel(view.known[0].length(limit)), alpha: 0.45, size: 11)
                            }
                            SegmentBar(meter: meter, family: view.family, slim: true).frame(height: 6)
                            ResetLine(text: comebackLine(meter, now: now), arrow: meter.comeback != nil, alpha: 0.55, size: 12)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        }
    }
}

/// Claude | Codex, a small segmented pill: the picked one lit in its family's colour.
private struct FamilyToggle: View {
    let selected: PoolFamily
    let onPick: (PoolFamily) -> Void

    var body: some View {
        HStack(spacing: 0) {
            ForEach(PoolFamily.allCases, id: \.self) { f in
                let on = f == selected
                Button { onPick(f) } label: {
                    CapsLabel(f.title.uppercased(), alpha: on ? 1 : 0.5, size: 10, color: on ? .white : .primary)
                        .padding(.horizontal, 11).padding(.vertical, 5)
                        .background(on ? Palette.member(f, 0) : .clear, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? [.isSelected] : [])
            }
        }
        .padding(2)
        .background(fg(0.07), in: Capsule())
    }
}

/// One family's five-hour session over the last 12 hours, picked with a
/// Claude | Codex pill (remembered): the family's share left as a solid line
/// over a soft fill in its colour, a dashed projection that holds until each
/// upcoming reset and steps up by what it gives back ("+N%", in that account's
/// colour), dotted gridlines with their %, NOW with a dot, and every reset
/// notched on the axis with its time. Static, like the rest of the card.
private struct SessionGraph: View {
    let view: FamilyView
    let now: Date
    let onPick: (PoolFamily) -> Void

    private static let time: DateFormatter = {
        let f = DateFormatter()
        let h24 = DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .current)?.contains("H") == true
        f.dateFormat = h24 ? "H:mm" : "h:mm"
        return f
    }()

    var body: some View {
        let chart = SessionChart.of(view.members, history: view.history, now: now)
        let length = view.known.first?.sessionLength ?? Look.session
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                CapsLabel("\(durationLabel(length)) · LAST \(Int(SessionChart.back / 3600))H", alpha: 0.9, size: 11)
                Spacer()
                FamilyToggle(selected: view.family, onPick: onPick)
            }
            ZStack {
                Canvas { ctx, size in draw(chart, &ctx, size) }
                if chart.line.isEmpty {
                    Text(view.error ?? "No \(view.family.title) numbers yet.").font(.system(size: 12)).foregroundStyle(fg(0.45))
                }
            }
            .frame(height: 100)
        }
    }

    private func draw(_ chart: SessionChart, _ ctx: inout GraphicsContext, _ size: CGSize) {
        let small = Font.system(size: 9, weight: .medium)
        let bold = Font.system(size: 9, weight: .bold)
        func text(_ s: String, _ font: Font, _ color: Color) -> GraphicsContext.ResolvedText {
            ctx.resolve(Text(s).font(font).tracking(0.54).foregroundStyle(color))
        }
        let dim = view.offline ? 0.45 : 1
        let accent = Palette.member(view.family, 0)

        let axis = [(1.0, "100%"), (0.5, "50%"), (0.0, "0%")].map { v, t in (v, text(t, small, fg(0.4))) }
        let gutter = axis.map { $0.1.measure(in: size).width }.max()! + 6
        let w = size.width - gutter
        let top: CGFloat = 15 // room for NOW and the "+N%" over a step to full
        let notch: CGFloat = 4
        let base = size.height - 14 - notch
        let plotH = base - top
        func x(_ t: Date) -> CGFloat { gutter + chart.x(t, w) }
        func y(_ left: Double) -> CGFloat { chart.y(left, top: top, height: plotH) }
        // A reset the history can't pin to an account takes the family's colour, a shade fainter.
        func colorOf(_ i: Int) -> (Color, Double) { i == SessionChart.unknown ? (accent, 0.7 * dim) : (Palette.member(view.family, i), dim) }
        let line = accent.opacity(dim)
        let nowX = x(chart.now)

        // Dotted gridlines at full, half and empty, each with its %.
        for (v, label) in axis {
            let gy = y(v)
            var dots = Path()
            var dx = gutter
            while dx <= size.width { dots.addDot(CGPoint(x: dx, y: gy), 0.7); dx += 4 }
            ctx.fill(dots, with: .color(fg(0.2)))
            ctx.draw(label, at: CGPoint(x: gutter - 6, y: gy), anchor: .trailing)
        }
        // A faint rule every three hours of the clock.
        let cal = Calendar.current
        var hours: [Date] = []
        var hr = cal.dateInterval(of: .hour, for: chart.from)!.start + 3600
        while hr <= chart.to {
            if cal.component(.hour, from: hr) % 3 == 0 { hours.append(hr) }
            hr += 3600
        }
        for h in hours {
            ctx.stroke(.line(CGPoint(x: x(h), y: top), CGPoint(x: x(h), y: base)), with: .color(fg(0.07)), lineWidth: 1)
        }

        // The fill: the line's colour fading to nothing at empty, fainter ahead.
        func fill(_ points: [ChartPoint], _ strong: Double) {
            let gradient = Gradient(colors: [accent.opacity(strong * dim), accent.opacity(strong * 0.12 * dim)])
            for s in stretches(points) where s.count >= 2 {
                var p = Path()
                p.move(to: CGPoint(x: x(s[0].t), y: base))
                for pt in s { p.addLine(to: CGPoint(x: x(pt.t), y: y(pt.left))) }
                p.addLine(to: CGPoint(x: x(s.last!.t), y: base))
                p.closeSubpath()
                ctx.fill(p, with: .linearGradient(gradient, startPoint: CGPoint(x: 0, y: top), endPoint: CGPoint(x: 0, y: base)))
            }
        }
        fill(chart.line, 0.55)
        fill(chart.projection, 0.2)

        ctx.stroke(.line(CGPoint(x: nowX, y: top - 2), CGPoint(x: nowX, y: base)), with: .color(fg(0.35)), lineWidth: 1)

        func polyline(_ pts: [ChartPoint]) -> Path {
            var p = Path()
            for (k, pt) in pts.enumerated() {
                let c = CGPoint(x: x(pt.t), y: y(pt.left))
                if k == 0 { p.move(to: c) } else { p.addLine(to: c) }
            }
            return p
        }
        for s in stretches(chart.line) {
            ctx.stroke(polyline(s), with: .color(line), style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
        }
        let dash: [CGFloat] = [4, 3]
        if chart.projection.count > 1 {
            ctx.stroke(polyline(chart.projection), with: .color(accent.opacity(0.85 * dim)), style: StrokeStyle(lineWidth: 1.6, lineCap: .butt, lineJoin: .round, dash: dash))
        }

        // Each reset: its step in its account's colour, a notch on the axis.
        let steps = chart.resets.map { r in (r, stepOf(r.upcoming ? chart.projection : chart.line, at: r.at)) }
        for (r, step) in steps {
            let (c, a) = colorOf(r.color)
            let rx = x(r.at)
            if let (lo, hi) = step, r.color != SessionChart.unknown {
                ctx.stroke(.line(CGPoint(x: rx, y: y(lo)), CGPoint(x: rx, y: y(hi))), with: .color(c.opacity(a)),
                           style: StrokeStyle(lineWidth: r.upcoming ? 1.6 : 2, lineCap: .round, dash: r.upcoming ? dash : []))
            }
            ctx.stroke(.line(CGPoint(x: rx, y: base + 1), CGPoint(x: rx, y: base + notch)), with: .color(c.opacity(a * (r.upcoming ? 1 : 0.65))), lineWidth: 2)
        }

        // NOW: a dot on the line, cut clear of it.
        if let v = chart.current {
            ctx.fill(.dot(CGPoint(x: nowX, y: y(v)), 5), with: .color(Palette.background))
            ctx.fill(.dot(CGPoint(x: nowX, y: y(v)), 3.25), with: .color(line))
        }

        // Above: NOW, then each step's "+N%" just over its top (what's coming first,
        // then the newest past), as many as fit.
        var placed: [CGRect] = []
        func place(_ t: GraphicsContext.ResolvedText, cx: CGFloat, bottom: CGFloat) {
            let s = t.measure(in: size)
            let left = min(max(cx - s.width / 2, gutter), size.width - s.width)
            let tt = max(bottom - s.height, 0)
            let rect = CGRect(x: left - 3, y: tt, width: s.width + 6, height: s.height)
            if placed.contains(where: { $0.intersects(rect) }) { return }
            placed.append(rect)
            ctx.draw(t, at: CGPoint(x: left, y: tt), anchor: .topLeading)
        }
        place(text("NOW", small, fg(0.6)), cx: nowX, bottom: top - 3)
        for (r, step) in steps.filter({ $0.0.upcoming }) + steps.filter({ !$0.0.upcoming }).reversed() {
            let gain = Int((r.gain * 100).rounded())
            guard gain >= 1, let step else { continue }
            let (c, a) = colorOf(r.color)
            place(text("+\(gain)%", bold, c.opacity(a * (r.upcoming ? 1 : 0.7))), cx: x(r.at), bottom: y(step.1) - 3)
        }

        // Below: every reset's time in its account's colour (coming first, then
        // the newest past), then the clock, as many as fit.
        var labels: [(Date, GraphicsContext.ResolvedText)] = []
        for r in chart.resets where r.upcoming {
            let (c, a) = colorOf(r.color)
            labels.append((r.at, text(Self.time.string(from: r.at), bold, c.opacity(a))))
        }
        for r in chart.resets.filter({ !$0.upcoming }).reversed() {
            let (c, a) = colorOf(r.color)
            labels.append((r.at, text(Self.time.string(from: r.at), bold, c.opacity(a * 0.7))))
        }
        for h in hours { labels.append((h, text(Self.time.string(from: h), small, fg(0.35)))) }
        let widths = labels.map { $0.1.measure(in: size).width }
        let lefts = placeLabels(labels.enumerated().map { k, l in (x(l.0) - gutter, widths[k]) }, width: w, gap: 4, nudge: 10)
        for (k, left) in lefts.enumerated() {
            guard let left else { continue }
            ctx.draw(labels[k].1, at: CGPoint(x: gutter + left, y: base + notch + 2), anchor: .topLeading)
        }
    }
}
