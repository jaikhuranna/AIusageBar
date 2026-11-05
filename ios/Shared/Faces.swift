import SwiftUI
import UIKit
import WidgetKit

/// The faces, ported from the Android widgets' `Render.kt`. The shapes,
/// proportions and states are the same; only the drawing API differs. The
/// threads draw many accounts at once, so they are `ThreadsView`, not a style.
enum Style { case ring, dash }

enum Palette {
    static func accent(_ provider: UsageProvider) -> Color {
        switch provider {
        case .claude: return orange
        case .claude2: return claude2
        case .codex: return green
        }
    }
    static let orange = hex(0xD97757)
    /// Claude 2: a tan sibling of Claude's orange, so both read as Claude. Less
    /// red than the orange, well short of the timer yellow.
    static let claude2 = hex(0xBF8B5A)
    static let green = hex(0x10A37F)
    /// Account `index` of a family: the first in the family's own colour, the
    /// second in its sibling (Claude 2's tan), and more in further shades, so
    /// every thread and segment of a family reads as that family.
    static func member(_ family: PoolFamily, _ index: Int) -> Color {
        let shades = family == .claude ? [orange, claude2, hex(0xE9A07E), hex(0x9E5A3C)] : [green, hex(0x6CC9A9), hex(0x0B7559)]
        return shades[max(index, 0) % shades.count]
    }
    static func hex(_ v: Int) -> Color {
        Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }
    static let yellow = Color(red: 1, green: 0xC5 / 255, blue: 0x3D / 255)
    static let grey = Color(red: 0x8C / 255, green: 0x8C / 255, blue: 0x8C / 255)
    /// The app's page behind the cards: system_neutral1_100 in light mode, black in dark.
    static let page = Color(UIColor { t in
        t.userInterfaceStyle == .dark
            ? UIColor.black
            : UIColor(red: 0xE4 / 255, green: 0xE2 / 255, blue: 0xE6 / 255, alpha: 1)
    })
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
                ? ("\(look.sessionLeft)%", "\(look.sessionLabel) LEFT", Palette.accent(look.provider))
                : ("\(look.weekLeft)%", "\(look.weekLabel) LEFT", Palette.accent(look.provider))
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
                // Any headroom at all lights a dot; an empty bar means OUT.
                (remaining > 0 ? Int((Double(n) * Double(remaining) / 100).rounded(.up)) : 0, Palette.accent(look.provider), fg(0.13), true)
            }
        var onDots = Path(), offDots = Path()
        for i in 0..<n {
            let p = CGPoint(x: bx0 + (CGFloat(i) + 0.5) * spacing, y: y)
            if i < lit { onDots.addDot(p, 1.2 * u) } else { offDots.addDot(p, 1.2 * u) }
        }
        l.accent.fill(onDots, on)
        if offOnInk { l.ink.fill(offDots, off) } else { l.accent.fill(offDots, off) }
        // The spent window is struck through, leaving its labels readable.
        if out && look.mode == .weekOut {
            l.accent.line(CGPoint(x: bx0, y: y), CGPoint(x: bx0 + CGFloat(n) * spacing, y: y), Palette.grey, width: 1.2 * u)
        }

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

// MARK: - The threads

/// Every account's weekly limit as threads twisted round each other from left
/// to right (Claude's accounts in orange and tan, Codex's in green). Each is
/// bright for the share of its week that's left and faint where it's been used.
/// No figures. `fancy` is the card's: a deeper twist, a fine ghost where the
/// week's been used, and a bead where bright meets faint.
struct ThreadsView: View {
    let strands: [ThreadStrand]
    var fancy = false

    var body: some View {
        Canvas { ctx, size in
            ctx.drawLayer { layer in ThreadFace.straight(strands, &layer, size, fancy: fancy) }
        }
        .widgetAccentable()
    }
}

/// The orbit: the same threads wound into concentric circles, one ring per
/// family (Claude's accounts outside, Codex's inside), each bright clockwise
/// from 12 o'clock for its week left.
struct OrbitView: View {
    /// Outermost first.
    let rings: [[ThreadStrand]]

    var body: some View {
        Canvas { ctx, size in
            ctx.drawLayer { layer in ThreadFace.orbit(rings, &layer, size) }
        }
        .widgetAccentable()
    }
}

/// Draws threads along any path: every strand whole, then, at each crossing
/// (`Braid.crossings`), a gap cleared round the front strand for as long as the
/// two are close and the front strand drawn again over it. That gives true
/// over-and-under for any number of strands with no seams, since nothing is
/// cut into strips. Bright and faint are separate butt-ended pieces, so they
/// meet square to the thread.
enum ThreadFace {
    /// A used stretch of thread: still there, only faint.
    private static let spent = 0.2
    /// Stale data keeps its shape but loses its color, as on the other faces.
    private static let staleAlpha = 0.45

    private struct Look {
        let width: CGFloat
        /// The used stretch: its width and alpha, as a share of the bright one's.
        let ghostWidth: CGFloat
        let ghostAlpha: Double
        /// The gap cleared round a strand, bright and used.
        let moat: CGFloat
        let ghostMoat: CGFloat
        /// The bead's radius and the gap cut round it, as multiples of the width; 0 is none.
        var bead: CGFloat = 0
        var beadCut: CGFloat = 0
        /// On a ring, how far (a share of the turn) the bright stretch takes to fade
        /// in from the faint one at 12 o'clock, so the start isn't a hard edge.
        var fadeIn: Double = 0

        static func of(_ sw: CGFloat, fancy: Bool) -> Look {
            fancy
                ? Look(width: sw, ghostWidth: sw * 0.55, ghostAlpha: 0.32, moat: sw * 1.9, ghostMoat: sw * 1.3, bead: 1.4, beadCut: 2.3)
                // A gap a third of a strand each side: wide enough to read as a crossing,
                // narrow enough that four strands in one band don't fall into dashes.
                : Look(width: sw, ghostWidth: sw, ghostAlpha: spent, moat: sw * 1.7, ghostMoat: sw * 1.7)
        }

        /// The orbit's: the card's look with quieter beads and a quick fade-in at the top.
        static func orbit(_ sw: CGFloat) -> Look {
            var l = of(sw, fancy: true)
            l.bead = 0.95
            l.beadCut = 1.6
            l.fadeIn = 0.07
            return l
        }
    }

    /// One braid: strand `i` at `point(i, u)`, u in 0...1 (any u when `closed`,
    /// a ring), twisted by `twist(u)`, the braid's angle, a straight line in u.
    private static func braid(
        _ strands: [ThreadStrand], _ ctx: inout GraphicsContext, look: Look, closed: Bool,
        twist: (Double) -> Double, point: (Int, Double) -> CGPoint
    ) {
        let count = strands.count
        guard count > 0 else { return }
        let colors = strands.map { Palette.member($0.family, $0.index).opacity($0.stale ? staleAlpha : 1) }
        let splits = strands.map { min(max($0.left ?? 0, 0), 1) }
        // a...b in bright and used pieces: (from, to, bright).
        func pieces(_ i: Int, _ a: Double, _ b: Double) -> [(Double, Double, Bool)] {
            var cuts = [a, b]
            if closed {
                for k in Int(floor(a))...Int(ceil(b)) { cuts += [Double(k), Double(k) + splits[i]] }
            } else {
                cuts.append(splits[i])
            }
            let sorted = Array(Set(cuts.filter { $0 >= a && $0 <= b })).sorted()
            return zip(sorted, sorted.dropFirst()).compactMap { from, to in
                guard to - from > 1e-6 else { return nil }
                let mid = (from + to) / 2
                return (from, to, (closed ? mid - floor(mid) : mid) < splits[i])
            }
        }
        func path(_ i: Int, _ a: Double, _ b: Double) -> Path {
            var p = Path()
            let n = max(Int((b - a) / 0.002), 2)
            for k in 0...n {
                let pt = point(i, a + (b - a) * Double(k) / Double(n))
                if k == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
            }
            return p
        }
        let butt = { (w: CGFloat) in StrokeStyle(lineWidth: w, lineCap: .butt, lineJoin: .round) }
        let fading = closed && look.fadeIn > 0
        // A bright piece split where it fades in: (from, to, fading).
        func spans(_ from: Double, _ to: Double) -> [(Double, Double, Bool)] {
            guard fading else { return [(from, to, false)] }
            var cuts = [from, to]
            for k in Int(floor(from))...Int(ceil(to)) { cuts += [Double(k), Double(k) + look.fadeIn] }
            let sorted = Array(Set(cuts.filter { $0 >= from && $0 <= to })).sorted()
            return zip(sorted, sorted.dropFirst()).filter { $0.1 - $0.0 > 1e-6 }.map { x, y in
                let mid = (x + y) / 2
                return (x, y, mid - floor(mid) < look.fadeIn)
            }
        }
        // Eased, so it's quick but not a step.
        let easing = stride(from: 0.0, through: 1.0, by: 0.125).map { x in (x, x * x * (3 - 2 * x)) }
        func draw(_ i: Int, _ a: Double, _ b: Double, clearing: Bool) {
            for (from, to, bright) in pieces(i, a, b) {
                for (x, y, fade) in bright ? spans(from, to) : [(from, to, false)] {
                    let p = path(i, x, y)
                    if clearing {
                        var gap = ctx
                        gap.blendMode = .clear
                        gap.stroke(p, with: .color(.black), style: butt(bright ? look.moat : look.ghostMoat))
                    }
                    if fade {
                        // One stroke with a gradient along the fade, over the faint thread,
                        // so the strand carries on and brightens without seams.
                        let start = floor((x + y) / 2)
                        ctx.stroke(p, with: .color(colors[i].opacity(look.ghostAlpha)), style: butt(look.ghostWidth))
                        let stops = easing.map { Gradient.Stop(color: colors[i].opacity($0.1), location: $0.0) }
                        ctx.stroke(p, with: .linearGradient(Gradient(stops: stops), startPoint: point(i, start), endPoint: point(i, start + look.fadeIn)),
                                   style: butt(look.width))
                    } else {
                        // Over a cleared gap, the bright redraw runs a hair past it each
                        // end: two anti-aliased butt edges on one line leave a seam.
                        let q = clearing && bright ? path(i, x - 0.003, y + 0.003) : p
                        ctx.stroke(q, with: .color(bright ? colors[i] : colors[i].opacity(look.ghostAlpha)), style: butt(bright ? look.width : look.ghostWidth))
                    }
                }
            }
        }

        for i in 0..<count { draw(i, 0, 1, clearing: false) }

        // Each crossing: how far either side the two stay within a gap's reach,
        // then the front strand again over a cleared gap for that stretch.
        let t0 = twist(0), t1 = twist(1)
        let reach = (max(look.moat, look.ghostMoat) + look.width) / 2 * 1.15
        func apart(_ c: Braid.Crossing, _ u: Double) -> Bool {
            let f = point(c.front, u), b = point(c.back, u)
            return hypot(f.x - b.x, f.y - b.y) > reach
        }
        for c in Braid.crossings(count, from: t0, to: t1) {
            let uc = (c.t - t0) / (t1 - t0)
            var a = uc, b = uc
            while uc - a < 0.2 && !apart(c, a) { a -= 0.001 }
            while b - uc < 0.2 && !apart(c, b) { b += 0.001 }
            if !closed { a = max(a, 0); b = min(b, 1) }
            draw(c.front, a, b, clearing: true)
        }

        // A bead where each thread's week runs out: "you are here", cut clear of
        // what's under it. Every cut first, then every bead, so two beads that
        // meet both stay whole.
        guard look.bead > 0 else { return }
        let beads = (0..<count).filter { splits[$0] > 0 && splits[$0] < 1 }
        for i in beads {
            var gap = ctx
            gap.blendMode = .clear
            gap.fill(.dot(point(i, splits[i]), look.width * look.beadCut), with: .color(.black))
        }
        for i in beads { ctx.fill(.dot(point(i, splits[i]), look.width * look.bead), with: .color(colors[i])) }
    }

    /// The threads widget and the card's foot: a low, long wave, left to right.
    static func straight(_ strands: [ThreadStrand], _ ctx: inout GraphicsContext, _ size: CGSize, fancy: Bool) {
        guard strands.count >= 2 else { return }
        let w = size.width, h = size.height
        let pad = h * 0.16
        // The card's strands are finer than the widget's.
        let sw = h * (fancy ? 0.049 : 0.06)
        let x0 = pad + sw, x1 = w - pad - sw
        // A low, long wave: the threads cross at a shallow angle.
        let amp = (h / 2 - pad - sw / 2) * (fancy ? 0.8 : 0.6)
        guard x1 > x0, amp > 0 else { return }
        // A whole number of half twists.
        let halves = max(Int(((x1 - x0) / (amp * 6)).rounded()), 1)
        let count = strands.count
        func x(_ u: Double) -> CGFloat { x0 + CGFloat(u) * (x1 - x0) }
        braid(
            strands, &ctx, look: .of(sw, fancy: fancy), closed: false,
            twist: { .pi * Double(halves) * $0 },
            point: { i, u in CGPoint(x: x(u), y: h / 2 + amp * CGFloat(Braid.offset(i, of: count, at: .pi * Double(halves) * u))) }
        )
    }

    /// One ring of the orbit: the family's strands wound round `centre`.
    private static func ring(_ strands: [ThreadStrand], _ ctx: inout GraphicsContext, centre: CGPoint, radius: CGFloat, amp: CGFloat, sw: CGFloat) {
        let count = strands.count
        guard count > 0 else { return }
        // A lone strand has nothing to twist round: a plain circle.
        let a = count > 1 ? amp : 0
        // Whole twists per turn, so every strand closes on itself.
        let twists = max(Int((2 * .pi * radius / (12 * max(amp, 1))).rounded()), 1)
        func t(_ u: Double) -> Double { 2 * .pi * Double(twists) * u }
        func angle(_ u: Double) -> Double { -.pi / 2 + 2 * .pi * u }
        braid(
            strands, &ctx, look: .orbit(sw), closed: true,
            twist: t,
            point: { i, u in
                let r = radius + a * CGFloat(Braid.offset(i, of: count, at: t(u)))
                return CGPoint(x: centre.x + r * CGFloat(cos(angle(u))), y: centre.y + r * CGFloat(sin(angle(u))))
            }
        )
    }

    /// Concentric rings, outermost first, filling the square.
    static func orbit(_ rings: [[ThreadStrand]], _ ctx: inout GraphicsContext, _ size: CGSize) {
        let s = min(size.width, size.height)
        let centre = CGPoint(x: size.width / 2, y: size.height / 2)
        let sw = s * 0.028
        let radii: [CGFloat] = [0.355, 0.205]
        let amps: [CGFloat] = [0.05, 0.04]
        for (k, strands) in rings.prefix(radii.count).enumerated() {
            ring(strands, &ctx, centre: centre, radius: s * radii[k], amp: s * amps[k], sw: sw)
        }
    }
}
