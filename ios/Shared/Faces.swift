import SwiftUI
import UIKit
import WidgetKit

/// The three faces, ported from the Android widgets' `Render.kt`. The shapes,
/// proportions and states are the same; only the drawing API differs.
enum Style { case ring, matrix, dash }

enum Palette {
    static func accent(_ provider: UsageProvider) -> Color {
        provider == .codex ? Color(red: 16 / 255, green: 163 / 255, blue: 127 / 255) : orange
    }
    static let orange = Color(red: 0xD9 / 255, green: 0x77 / 255, blue: 0x57 / 255)
    static let yellow = Color(red: 1, green: 0xC5 / 255, blue: 0x3D / 255)
    static let grey = Color(red: 0x8C / 255, green: 0x8C / 255, blue: 0x8C / 255)
    /// Nothing's widget background: system_neutral1_50 in light mode, system_neutral1_900 in dark.
    static let background = Color(UIColor { t in
        t.userInterfaceStyle == .dark
            ? UIColor(red: 0x1B / 255, green: 0x1B / 255, blue: 0x1F / 255, alpha: 1)
            : UIColor(red: 0xF1 / 255, green: 0xF0 / 255, blue: 0xF4 / 255, alpha: 1)
    })
    /// Stale data keeps its shape but loses its color.
    static let staleAlpha = 115.0 / 255
}

/// The element color: black in light mode, white in dark, like Nothing's widgets.
func fg(_ alpha: Double = 1) -> Color { Color.primary.opacity(alpha) }

/// A widget face at any size. The home screen, the lock screen and the app's
/// gallery all draw through this.
struct Face: View {
    let style: Style
    let look: Look

    var body: some View {
        GeometryReader { g in
            let l = Faces.draw(style, look, g.size)
            ZStack(alignment: .topLeading) {
                LayerView(ops: l.ink.ops, size: g.size)
                LayerView(ops: l.accent.ops, size: g.size)
                    .compositingGroup()
                    .opacity(look.stale ? Palette.staleAlpha : 1)
                    .widgetAccentable()
            }
        }
    }
}

// MARK: - Drawing model

/// A face is drawn the way the Android canvases were: operations on two layers.
/// "Ink" is the element color, so it follows dark mode; "accent" carries the
/// fixed orange, yellow and grey, dims when the data is stale, and is the part
/// iOS tints in tinted mode.
enum Op {
    case fill(Path, Color)
    case stroke(Path, Color, StrokeStyle)
    case text(Placed, Color)
    /// Knocks a band out of everything drawn on this layer so far.
    case clear(Path)
}

struct Layer {
    var ops: [Op] = []

    mutating func fill(_ p: Path, _ c: Color) { ops.append(.fill(p, c)) }
    mutating func stroke(_ p: Path, _ c: Color, width: CGFloat, round: Bool = true) {
        ops.append(.stroke(p, c, StrokeStyle(lineWidth: width, lineCap: round ? .round : .butt)))
    }
    mutating func circle(_ c: CGPoint, _ r: CGFloat, _ color: Color) { fill(.dot(c, r), color) }
    mutating func ring(_ c: CGPoint, _ r: CGFloat, _ color: Color, width: CGFloat) { stroke(.dot(c, r), color, width: width) }
    mutating func line(_ a: CGPoint, _ b: CGPoint, _ color: Color, width: CGFloat, round: Bool = true) {
        stroke(.line(a, b), color, width: width, round: round)
    }
    mutating func text(_ t: Placed, _ color: Color) { ops.append(.text(t, color)) }
    mutating func clear(_ a: CGPoint, _ b: CGPoint, width: CGFloat) {
        ops.append(.clear(Path.line(a, b).strokedPath(StrokeStyle(lineWidth: width, lineCap: .round))))
    }
}

struct Layers {
    var ink = Layer()
    var accent = Layer()
}

extension Path {
    static func dot(_ c: CGPoint, _ r: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
    }

    static func line(_ a: CGPoint, _ b: CGPoint) -> Path {
        var p = Path()
        p.move(to: a)
        p.addLine(to: b)
        return p
    }

    /// Clockwise from 12 o'clock, `fraction` of the way round.
    static func arc(_ c: CGPoint, _ r: CGFloat, _ fraction: Double) -> Path {
        var p = Path()
        p.addArc(center: c, radius: r, startAngle: .degrees(-90), endAngle: .degrees(-90 + 360 * fraction), clockwise: false)
        return p
    }

    static func pie(_ c: CGPoint, _ r: CGFloat, _ fraction: Double) -> Path {
        var p = Path()
        p.move(to: c)
        p.addArc(center: c, radius: r, startAngle: .degrees(-90), endAngle: .degrees(-90 + 360 * fraction), clockwise: false)
        p.closeSubpath()
        return p
    }

    /// Dot-matrix text, left edge at `left`, top at `top`, one dot per `pitch`.
    static func dotText(_ text: String, left: CGFloat, top: CGFloat, pitch: CGFloat) -> Path {
        var p = Path()
        DotFont.forEachDot(text) { c, row in
            p.addDot(CGPoint(x: left + (CGFloat(c) + 0.5) * pitch, y: top + (CGFloat(row) + 0.5) * pitch), pitch * 0.4)
        }
        return p
    }

    mutating func addDot(_ c: CGPoint, _ r: CGFloat) {
        addEllipse(in: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
    }
}

/// Type as Nothing OS 3+ sets it: NType 82 for headlines and big numbers,
/// tracked caps for labels. iOS has no NType 82, so headlines use the system
/// sans, as the Android widgets do off Nothing phones.
enum Fonts {
    static func headline(_ size: CGFloat) -> UIFont { .systemFont(ofSize: size, weight: .regular) }
    static func label(_ size: CGFloat) -> UIFont { .systemFont(ofSize: size, weight: .medium) }
}

/// A line of text set at an exact left edge and baseline, the way a canvas draws it.
struct Placed {
    let text: String
    let font: UIFont
    let kern: CGFloat
    var x: CGFloat
    let baseline: CGFloat
    let width: CGFloat

    /// `tracking` is in ems, like Android's letterSpacing.
    init(_ text: String, font: UIFont, tracking: CGFloat = 0, x: CGFloat, baseline: CGFloat, alignRight: Bool = false) {
        self.text = text
        self.font = font
        kern = tracking * font.pointSize
        width = (text as NSString).size(withAttributes: [.font: font, .kern: kern]).width
        self.x = alignRight ? x - width : x
        self.baseline = baseline
    }

    /// The digit-height box the text occupies: what a fitted number covers.
    var capBounds: CGRect { CGRect(x: x, y: baseline - font.capHeight, width: width, height: font.capHeight) }

    /// Headline type as large as fits in `box`, sized and centred on the height
    /// of a digit so "62", "1:41" and "GO!" share a baseline.
    static func fit(_ text: String, in box: CGRect, alignLeft: Bool = false) -> Placed {
        let probe = Placed(text, font: Fonts.headline(100), tracking: -0.03, x: 0, baseline: 0)
        let size = 100 * min(box.width / max(probe.width, 1), box.height / probe.font.capHeight)
        let font = Fonts.headline(max(size, 1))
        var p = Placed(text, font: font, tracking: -0.03, x: 0, baseline: box.midY + font.capHeight / 2)
        p.x = alignLeft ? box.minX : box.midX - p.width / 2
        return p
    }

    func view(_ color: Color) -> some View {
        Text(text)
            .font(Font(font as CTFont))
            .kerning(kern)
            .foregroundStyle(color)
            .lineLimit(1)
            .fixedSize()
            .position(x: x + width / 2, y: baseline - (font.ascender + font.descender) / 2)
    }
}

private struct LayerView: View {
    let ops: [Op]
    let size: CGSize

    private struct Cut {
        let index: Int
        let band: Path
    }

    private var lastCut: Cut? {
        for i in ops.indices.reversed() {
            if case let .clear(band) = ops[i] { return Cut(index: i, band: band) }
        }
        return nil
    }

    var body: some View {
        if let cut = lastCut {
            ZStack(alignment: .topLeading) {
                AnyView(LayerView(ops: Array(ops[..<cut.index]), size: size))
                    .mask { Path(CGRect(origin: .zero, size: size)).subtracting(cut.band) }
                AnyView(LayerView(ops: Array(ops[(cut.index + 1)...]), size: size))
            }
        } else {
            ZStack(alignment: .topLeading) {
                ForEach(ops.indices, id: \.self) { i in
                    switch ops[i] {
                    case let .fill(p, c): p.fill(c)
                    case let .stroke(p, c, s): p.stroke(c, style: s)
                    case let .text(t, c): t.view(c)
                    case .clear: EmptyView()
                    }
                }
            }
        }
    }
}

// MARK: - The faces

enum Faces {
    static func draw(_ style: Style, _ look: Look, _ size: CGSize) -> Layers {
        guard size.width >= 1, size.height >= 1 else { return Layers() }
        switch style {
        case .ring: return ring(look, size)
        case .matrix: return matrix(look, size)
        case .dash: return dash(look, size)
        }
    }

    private static func countdown(_ look: Look) -> String? {
        look.countdownTo.map { countdownText(from: look.now, to: $0) }
    }

    /// The weekly limit as a pie in the middle, the 5h session as a ring around it.
    private static func ring(_ look: Look, _ size: CGSize) -> Layers {
        var l = Layers()
        let s = min(size.width, size.height)
        let c = CGPoint(x: size.width / 2, y: size.height / 2)
        let ringR = s * 0.385
        let sw = s * 0.07
        let pieR = s * 0.25
        func box(_ hw: CGFloat, _ hh: CGFloat) -> CGRect { CGRect(x: c.x - hw, y: c.y - hh, width: 2 * hw, height: 2 * hh) }

        switch look.mode {
        case .unpaired, .waiting:
            l.ink.ring(c, ringR, fg(0.13), width: sw)
            l.ink.text(.fit(look.mode == .unpaired ? "PAIR" : "…", in: box(pieR * 0.9, pieR * 0.32)), fg(0.8))
        case .normal:
            l.ink.ring(c, ringR, fg(0.13), width: sw)
            l.ink.circle(c, pieR, fg(0.13))
            if look.sessionLeft > 0 { l.accent.stroke(.arc(c, ringR, Double(look.sessionLeft) / 100), Palette.accent(look.provider), width: sw) }
            if look.weekLeft > 0 { l.accent.fill(.pie(c, pieR, Double(look.weekLeft) / 100), Palette.accent(look.provider)) }
        case .sessionOut:
            // The ring becomes a timer: a dot per slice of the 5h window, the
            // lit ones shrinking back toward 12 o'clock as the reset nears.
            let n = 40
            let lit = Int((Double(n) * look.sessionTimerFraction()).rounded(.up))
            var on = Path(), off = Path()
            for i in 0..<n {
                let a = (-90 + Double(i) * 360 / Double(n)) * .pi / 180
                let p = CGPoint(x: c.x + ringR * cos(a), y: c.y + ringR * sin(a))
                if i < lit { on.addDot(p, sw * 0.4) } else { off.addDot(p, sw * 0.4) }
            }
            l.accent.fill(on, Palette.yellow)
            l.accent.fill(off, Palette.yellow.opacity(0.22))
            l.ink.circle(c, pieR, fg(0.08))
            if look.weekLeft > 0 { l.accent.fill(.pie(c, pieR, Double(look.weekLeft) / 100), Palette.accent(look.provider).opacity(0.22)) }
            l.ink.text(.fit(countdown(look) ?? "OUT", in: box(pieR * 0.88, pieR * 0.42)), fg())
        case .weekOut:
            l.accent.ring(c, ringR, Palette.grey.opacity(0.55), width: sw)
            l.accent.circle(c, pieR, Palette.grey.opacity(0.4))
            // Struck through, like a no-entry sign, with a gap cut around the bar.
            let d = (ringR + sw / 2) * 0.7071
            let a = CGPoint(x: c.x - d, y: c.y - d), b = CGPoint(x: c.x + d, y: c.y + d)
            l.accent.clear(a, b, width: sw * 2.6)
            l.accent.line(a, b, Palette.grey, width: sw)
        case .go:
            l.accent.ring(c, ringR, Palette.accent(look.provider), width: sw)
            l.accent.text(.fit("GO!", in: box(pieR * 1.05, pieR * 0.55)), Palette.accent(look.provider))
        }
        return l
    }

    /// An LED panel: the session's headroom set in type in a window cut out of
    /// the dots, small dot labels, the week as a bar of dots along the bottom.
    private static func matrix(_ look: Look, _ size: CGSize) -> Layers {
        enum Cell { case off, ink, orange, yellow, grey, blank }
        var l = Layers()

        // Inset, so the panel sits inside the widget's rounded corners.
        let inset = min(size.width, size.height) * 0.08
        let aw = size.width - 2 * inset
        let ah = size.height - 2 * inset
        let pitch = min(aw / 38, ah / 26)
        let cols = Int(aw / pitch)
        let rows = Int(ah / pitch)
        let ox = (size.width - CGFloat(cols) * pitch) / 2
        let oy = (size.height - CGFloat(rows) * pitch) / 2
        var grid = Array(repeating: Array(repeating: Cell.off, count: cols), count: rows)
        func centre(_ r: Int, _ c: Int) -> CGPoint {
            CGPoint(x: ox + (CGFloat(c) + 0.5) * pitch, y: oy + (CGFloat(r) + 0.5) * pitch)
        }

        func put(_ text: String, _ col: Int, _ row: Int, _ v: Cell) {
            DotFont.forEachDot(text) { c, r in
                if (0..<rows).contains(row + r), (0..<cols).contains(col + c) { grid[row + r][col + c] = v }
            }
        }
        func putRight(_ text: String, _ right: Int, _ row: Int, _ v: Cell) { put(text, right - DotFont.width(text) + 1, row, v) }
        func putCentered(_ text: String, _ row: Int, _ v: Cell) { put(text, (cols - DotFont.width(text)) / 2, row, v) }

        // Top labels, a big middle, a labelled bar: 7 + gap + 14 + gap + 7 rows.
        let gap = min(max((rows - 2 - 28) / 2, 0), 4)
        let top = max((rows - (28 + 2 * gap)) / 2, 0)
        let mid = top + 7 + gap
        let bottom = mid + 14 + gap
        func bar(_ label: String, _ labelV: Cell, _ percent: Int, _ v: Cell) {
            put(label, 1, bottom, labelV)
            let x0 = 1 + DotFont.width(label) + 2
            let x1 = cols - 2
            guard x0 <= x1 else { return }
            let lit = Int((Double(x1 - x0 + 1) * Double(percent) / 100).rounded())
            for c in x0...x1 {
                for r in (bottom + 2)...(bottom + 4) where (0..<rows).contains(r) { grid[r][c] = c - x0 < lit ? v : .off }
            }
        }

        // The big middle is set in type, not dots: see `Fonts`.
        var big: (text: String, cell: Cell)?
        var slashed = false
        switch look.mode {
        case .unpaired:
            big = ("PAIR", .ink)
        case .waiting:
            big = ("…", .ink)
        case .normal:
            put(look.sessionLabel, 1, top, .ink)
            putRight(shortCountdown(from: look.now, to: look.session?.resetsAt), cols - 2, top, .ink)
            big = (look.session == nil ? "--" : String(look.sessionLeft), .orange)
            bar(look.weekLabel, .ink, look.weekLeft, .orange)
        case .sessionOut:
            put(look.sessionLabel, 1, top, .yellow)
            putRight("OUT", cols - 2, top, .yellow)
            big = (countdown(look) ?? "OUT", .yellow)
            bar(look.weekLabel, .ink, look.weekLeft, .orange)
        case .weekOut:
            put(look.weekLabel, 1, top, .grey)
            putRight("OUT", cols - 2, top, .grey)
            big = (countdown(look) ?? "--", .grey)
            bar(look.sessionLabel, .grey, 0, .grey)
            slashed = true
        case .go:
            putCentered(look.provider.title.uppercased(), top, .ink)
            big = ("GO!", .orange)
            bar(look.weekLabel, .ink, look.weekLeft, .orange)
        }

        let colors: [Cell: Color] = [.orange: Palette.accent(look.provider), .yellow: Palette.yellow, .grey: Palette.grey]
        if let big {
            let box = CGRect(x: ox + 1.5 * pitch, y: oy + CGFloat(mid + 1) * pitch, width: CGFloat(cols - 3) * pitch, height: 12 * pitch)
            let t = Placed.fit(big.text, in: box)
            if big.cell == .ink { l.ink.text(t, fg()) } else { l.accent.text(t, colors[big.cell]!) }
            // Switch off the dots under the type, one cell of margin around it.
            let ink = t.capBounds.insetBy(dx: -pitch, dy: -pitch)
            for r in 0..<rows {
                for c in 0..<cols {
                    let p = centre(r, c)
                    if p.x > ink.minX && p.x < ink.maxX && p.y > ink.minY && p.y < ink.maxY { grid[r][c] = .blank }
                }
            }
        }
        if slashed {
            // A diagonal of lit dots through the whole panel, with a dark moat
            // cut through the type as well.
            l.accent.clear(centre(0, 0), centre(rows - 1, cols - 1), width: pitch * 4.6)
            for c in 0..<cols {
                let y = Double(c) * Double(rows - 1) / Double(max(cols - 1, 1))
                for r in 0..<rows {
                    let d = abs(Double(r) - y)
                    if d <= 0.75 { grid[r][c] = .ink } else if d <= 2.3 { grid[r][c] = .blank }
                }
            }
        }

        var paths: [Cell: Path] = [:]
        for r in 0..<rows {
            for c in 0..<cols where grid[r][c] != .blank {
                paths[grid[r][c], default: Path()].addDot(centre(r, c), pitch * 0.36)
            }
        }
        if let p = paths[.off] { l.ink.fill(p, fg(0.09)) }
        if let p = paths[.ink] { l.ink.fill(p, fg()) }
        for (cell, color) in colors { if let p = paths[cell] { l.accent.fill(p, color) } }
        return l
    }

    /// The number that matters most on the left, both windows on the right as
    /// dot bars with a "time left" tick, so you can see at a glance whether the
    /// headroom will outlast the clock.
    private static func dash(_ look: Look, _ size: CGSize) -> Layers {
        var l = Layers()
        let w = size.width, h = size.height
        let u = h / 100
        let pad = 9 * u
        let leftW = w * 0.36
        // Labels as Nothing sets them: medium caps, tracked out 0.1.
        func label(_ s: String, _ px: CGFloat, x: CGFloat, baseline: CGFloat) -> Placed {
            Placed(s, font: Fonts.label(px), tracking: 0.1, x: x, baseline: baseline)
        }

        let accent: Color = switch look.mode {
        case .sessionOut: Palette.yellow
        case .weekOut: Palette.grey
        default: Palette.accent(look.provider)
        }

        // Header: the one place for Ndot-style dots, as a small accent.
        l.accent.circle(CGPoint(x: pad + 1.6 * u, y: pad + 2.6 * u), 1.7 * u, accent)
        l.ink.fill(.dotText(look.provider.title.uppercased(), left: pad + 5.5 * u, top: pad, pitch: 0.75 * u), fg(0.75))

        // The big number and what it means. A nil color is ink.
        let (big, caption, bigColor): (String, String, Color?) = switch look.mode {
        case .unpaired: ("PAIR", "TAP TO SET UP", nil)
        case .waiting: ("...", "FETCHING", nil)
        case .normal:
            look.week == nil || (look.session != nil && look.sessionLeft <= look.weekLeft)
                ? ("\(look.sessionLeft)", "% \(look.sessionLabel) LEFT", Palette.accent(look.provider))
                : ("\(look.weekLeft)", "% \(look.weekLabel) LEFT", Palette.accent(look.provider))
        case .sessionOut: (countdown(look) ?? "OUT", "UNTIL THE \(look.sessionLabel) RESET", Palette.yellow)
        case .weekOut: (countdown(look) ?? "OUT", "\(look.weekLabel) LIMIT HIT", Palette.grey)
        case .go: ("GO!", "\(look.provider.title.uppercased())'S BACK", Palette.accent(look.provider))
        }
        let bigText = Placed.fit(big, in: CGRect(x: pad, y: 22 * u, width: leftW - 2 * pad, height: 30 * u), alignLeft: true)
        if let bigColor { l.accent.text(bigText, bigColor) } else { l.ink.text(bigText, fg()) }
        l.ink.text(label(caption, 5.2 * u, x: pad, baseline: 64 * u), fg(0.6))

        // Footer: how old this is. A number without its age is a bug.
        let ago = agoText(look.fetchedAt, now: look.now).uppercased()
        let age = look.mode == .unpaired ? "NOT PAIRED" : look.offline ? "OFFLINE · \(ago)" : "UPDATED \(ago)"
        l.ink.text(label(age, 4.6 * u, x: pad, baseline: h - pad), fg(look.stale ? 0.75 : 0.4))

        l.ink.line(CGPoint(x: leftW, y: pad), CGPoint(x: leftW, y: h - pad), fg(0.12), width: 0.5 * u, round: false)

        let x0 = leftW + pad
        let x1 = w - pad
        dashRow(&l, look, look.sessionLabel, look.session, look.sessionLength, y: 30 * u, x0: x0, x1: x1, u: u)
        dashRow(&l, look, look.weekLabel, look.week, look.weekLength, y: 70 * u, x0: x0, x1: x1, u: u)

        if look.mode == .weekOut {
            let a = CGPoint(x: w * 0.04, y: h * 0.08), b = CGPoint(x: w * 0.96, y: h * 0.92)
            l.ink.clear(a, b, width: 5.5 * u)
            l.accent.clear(a, b, width: 5.5 * u)
            l.accent.line(a, b, Palette.grey, width: 1.8 * u)
        }
        return l
    }

    private static func dashRow(
        _ l: inout Layers, _ look: Look, _ name: String, _ e: Effective?, _ length: TimeInterval,
        y: CGFloat, x0: CGFloat, x1: CGFloat, u: CGFloat
    ) {
        let paired = look.mode != .unpaired && look.mode != .waiting && e != nil
        let remaining = e?.remaining ?? 100
        let timerRow = look.mode == .sessionOut && name == look.sessionLabel
        let out = remaining <= 0 && paired

        l.ink.text(Placed(name, font: Fonts.label(6 * u), tracking: 0.1, x: x0, baseline: y + 2.2 * u), fg(0.9))

        // Right-hand figure. A nil color is ink.
        let figure = !paired ? "--" : out ? "OUT" : "\(remaining)%"
        let figColor: Color? = !paired ? nil : look.mode == .weekOut ? Palette.grey : timerRow ? Palette.yellow : Palette.accent(look.provider)
        let figFont = Fonts.headline(13 * u)
        let figW = Placed("100%", font: figFont, tracking: -0.03, x: 0, baseline: 0).width
        let fig = Placed(figure, font: figFont, tracking: -0.03, x: x1, baseline: y + 4.6 * u, alignRight: true)
        if let figColor { l.accent.text(fig, figColor) } else { l.ink.text(fig, fg()) }

        // The bar: one dot per slice.
        let bx0 = x0 + 11 * u
        let bx1 = x1 - figW - 4 * u
        let spacing = 3.7 * u
        let n = max(Int((bx1 - bx0) / spacing), 1)
        let (lit, on, off, offOnInk): (Int, Color, Color, Bool) =
            if !paired {
                (0, Palette.accent(look.provider), fg(0.12), true)
            } else if timerRow {
                (Int((Double(n) * look.sessionTimerFraction()).rounded(.up)), Palette.yellow, Palette.yellow.opacity(0.22), false)
            } else if look.mode == .weekOut {
                (0, Palette.grey, Palette.grey.opacity(0.45), false)
            } else {
                (Int((Double(n) * Double(remaining) / 100).rounded()), Palette.accent(look.provider), fg(0.13), true)
            }
        var onDots = Path(), offDots = Path()
        for i in 0..<n {
            let p = CGPoint(x: bx0 + (CGFloat(i) + 0.5) * spacing, y: y)
            if i < lit { onDots.addDot(p, 1.2 * u) } else { offDots.addDot(p, 1.2 * u) }
        }
        l.accent.fill(onDots, on)
        if offOnInk { l.ink.fill(offDots, off) } else { l.accent.fill(offDots, off) }

        // The "time left" tick: headroom reaching past it means you'll last.
        let reset = e?.resetsAt
        if paired, let reset, !out, look.mode != .weekOut {
            let f = min(max(reset.timeIntervalSince(look.now) / length, 0), 1)
            let tx = bx0 + f * CGFloat(n) * spacing
            l.ink.line(CGPoint(x: tx, y: y - 4.2 * u), CGPoint(x: tx, y: y + 4.2 * u), fg(), width: 0.7 * u)
        }

        // The line under it.
        let subFont = Fonts.label(4.6 * u)
        let sub: String
        if !paired {
            sub = ""
        } else if let reset {
            if out || timerRow {
                sub = "BACK \(clockText(reset, now: look.now).uppercased())"
            } else if look.mode == .weekOut {
                sub = "RESETS \(clockText(reset, now: look.now).uppercased())"
            } else {
                let p: String = switch pace(e, length: length, now: look.now) {
                case .lasts?: " · LASTS"
                case let .runsOut(at)?: " · EMPTY IN ~\(untilText(from: look.now, to: at).uppercased())"
                default: ""
                }
                let left = untilText(from: look.now, to: reset).uppercased()
                // Drop words until it fits the row.
                sub = ["RESETS IN \(left)\(p)", "\(left)\(p)", "\(left)\(p.replacingOccurrences(of: "EMPTY IN ", with: "EMPTY "))"]
                    .first { Placed($0, font: subFont, tracking: 0.1, x: 0, baseline: 0).width <= x1 - x0 } ?? "RESETS IN \(left)"
            }
        } else {
            sub = out ? "RESET TIME UNKNOWN" : "WINDOW NOT STARTED"
        }
        l.ink.text(Placed(sub, font: subFont, tracking: 0.1, x: x0, baseline: y + 13 * u), fg(0.55))
    }
}
