import SwiftUI

// The pieces the app and the tap card share, ported from Android's `Usage.kt`:
// the family header, the app's meter card, the per-account segment bar, the
// reset arrow, and Nothing's tracked-caps labels.

/// "2 MIN AGO", "OFFLINE · 3H AGO".
func ageText(_ view: FamilyView, now: Date) -> String {
    guard let at = view.fetchedAt else { return view.offline ? "CAN'T REACH THE DESKTOP" : "WAITING FOR THE DESKTOP" }
    let ago = agoText(at, now: now).uppercased()
    return view.offline ? "OFFLINE · \(ago)" : ago
}

/// "+28% in 3h 30m" (drawn after a reset arrow), or why there's nothing coming back.
func comebackLine(_ meter: Meter, now: Date) -> String {
    guard let left = meter.left else { return "Not reported" }
    if let c = meter.comeback { return c.text(now) }
    return left >= 100 ? "Untouched" : "Reset time unknown"
}

func durationLabel(_ d: TimeInterval) -> String {
    let minutes = Int(d / 60)
    if minutes % 1440 == 0 { return "\(minutes / 1440)D" }
    if minutes % 60 == 0 { return "\(minutes / 60)H" }
    return "\(minutes)M"
}

/// Headlines and big numbers. NType 82 on Nothing phones; the system face here, as off Nothing.
func headline(_ size: CGFloat) -> Font { .system(size: size, weight: .regular) }

/// Tracked caps, Nothing's label style. `oneLine` elides in the middle, which keeps an email's domain.
struct CapsLabel: View {
    let text: String
    var alpha: Double = 1
    var size: CGFloat = 12
    var tracking: CGFloat = 0.1
    var oneLine = false
    var color: Color = .primary

    init(_ text: String, alpha: Double = 1, size: CGFloat = 12, tracking: CGFloat = 0.1, oneLine: Bool = false, color: Color = .primary) {
        self.text = text
        self.alpha = alpha
        self.size = size
        self.tracking = tracking
        self.oneLine = oneLine
        self.color = color
    }

    var body: some View {
        Text(text)
            .font(.system(size: size, weight: .medium))
            .tracking(size * tracking)
            .foregroundStyle(color.opacity(alpha))
            .lineLimit(oneLine ? 1 : nil)
            .truncationMode(.middle)
    }
}

/// A family's name with its dot, and how many accounts pool in it. `trailing` sits at the right.
struct FamilyHeader: View {
    let view: FamilyView
    var size: CGFloat = 13
    var trailing: String? = nil

    var body: some View {
        HStack(spacing: 0) {
            Circle().fill(Palette.member(view.family, 0)).frame(width: 8, height: 8)
            Spacer().frame(width: 10)
            CapsLabel(view.family.title.uppercased(), alpha: 0.95, size: size)
            if view.known.count > 1 {
                Spacer().frame(width: 8)
                CapsLabel("\(view.known.count) ACCOUNTS", alpha: 0.4, size: 10)
            }
            Spacer(minLength: 8)
            if let trailing { CapsLabel(trailing, alpha: 0.4, size: 10) }
        }
    }
}

/// One limit across a family, for the app: the share of the whole family left,
/// big; what the next reset brings back; the pace; a segment per account; and
/// the accounts themselves when there's more than one (or it has an email).
struct MeterCard: View {
    let view: FamilyView
    let limit: Limit
    let now: Date
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let meter = Meter.of(view.members, limit, now: now)
        let length = view.known.first?.length(limit) ?? (limit == .session ? Look.session : Look.week)
        // Ahead is the one worth noticing: the timer yellow, deepened to amber on a light card.
        let warn = scheme == .dark ? Palette.yellow : Palette.hex(0xB0700C)
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 0) {
                CapsLabel(limit.title.uppercased(), alpha: 0.9)
                CapsLabel(" · \(durationLabel(length))", alpha: 0.4)
                Spacer(minLength: 8)
                if let pace = meter.pace {
                    CapsLabel(pace.text.uppercased(), alpha: pace == .ahead ? 1 : 0.45, size: 10, color: pace == .ahead ? warn : .primary)
                }
                if meter.out { CapsLabel("LIMIT REACHED", alpha: 0.45, size: 10) }
            }
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(meter.left == nil ? "--" : meter.out ? "OUT" : "\(meter.left!)%")
                        .font(headline(48)).tracking(-0.96)
                        .foregroundStyle(fg(meter.out ? 0.45 : 1))
                    if meter.left != nil && !meter.out {
                        Text("left").font(.system(size: 16)).foregroundStyle(fg(0.55))
                    }
                }
                ResetLine(text: comebackLine(meter, now: now), arrow: meter.comeback != nil, alpha: 0.6, size: 13)
            }
            SegmentBar(meter: meter, family: view.family, numbered: meter.segments.count > 1)
                .frame(height: 24)
            if view.members.count > 1 || view.members.contains(where: { $0.account != nil }) {
                VStack(spacing: 10) {
                    ForEach(Array(view.members.enumerated()), id: \.offset) { i, m in
                        AccountRow(member: m, index: i, limit: limit, family: view.family, now: now)
                    }
                }
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.background, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
    }
}

/// `text` after the reset arrow when `arrow`, the way the Usage sheet writes a comeback.
struct ResetLine: View {
    let text: String
    let arrow: Bool
    var alpha: Double = 0.6
    var size: CGFloat = 13

    var body: some View {
        HStack(spacing: 5) {
            if arrow { ResetArrow(color: fg(alpha)).frame(width: size * 0.95, height: size * 0.95) }
            Text(text).font(.system(size: size)).foregroundStyle(fg(alpha)).lineLimit(1)
        }
    }
}

/// A small circular arrow: most of a ring, clockwise, ending in a solid head.
struct ResetArrow: View {
    let color: Color

    var body: some View {
        Canvas { ctx, size in
            let m = min(size.width, size.height)
            let stroke = m * 0.12
            let head = m * 0.26
            let r = m / 2 - head * 0.6
            let c = CGPoint(x: size.width / 2, y: size.height / 2)
            let start = 30.0, sweep = 270.0
            var arc = Path()
            arc.addArc(center: c, radius: r, startAngle: .degrees(start), endAngle: .degrees(start + sweep), clockwise: false)
            ctx.stroke(arc, with: .color(color), style: StrokeStyle(lineWidth: stroke, lineCap: .butt))
            let t = (start + sweep) * .pi / 180
            let p = CGPoint(x: c.x + r * cos(t), y: c.y + r * sin(t))
            let along = CGPoint(x: -sin(t), y: cos(t)) // clockwise
            let out = CGPoint(x: cos(t), y: sin(t))
            var tip = Path()
            tip.move(to: CGPoint(x: p.x + along.x * head, y: p.y + along.y * head))
            tip.addLine(to: CGPoint(x: p.x + out.x * head * 0.85, y: p.y + out.y * head * 0.85))
            tip.addLine(to: CGPoint(x: p.x - out.x * head * 0.85, y: p.y - out.y * head * 0.85))
            tip.closeSubpath()
            ctx.fill(tip, with: .color(color))
        }
    }
}

private struct AccountRow: View {
    let member: Member
    let index: Int
    let limit: Limit
    let family: PoolFamily
    let now: Date

    var body: some View {
        let e = member.window(limit)
        HStack(spacing: 12) {
            Badge(number: member.number, color: Palette.member(family, index))
            VStack(alignment: .leading, spacing: 1) {
                // Emails are lower case on the wire; caps would only make a long one longer.
                CapsLabel(member.name, alpha: 0.85, size: 14, tracking: 0.01, oneLine: true)
                if let error = member.error { CapsLabel(error, alpha: 0.45, size: 11, tracking: 0.01, oneLine: true) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: 10) {
                Text(e == nil ? "--" : e!.remaining <= 0 ? "OUT" : "\(e!.remaining)%")
                    .font(headline(18))
                    .foregroundStyle(fg(e == nil || e!.remaining <= 0 ? 0.45 : 0.95))
                Group {
                    if let reset = e?.resetsAt, reset > now {
                        ResetLine(text: untilText(from: now, to: reset), arrow: true, alpha: 0.5, size: 13)
                    }
                }
                .frame(width: 78, alignment: .leading)
            }
        }
    }
}

/// An account's number, in its own colour on a quiet tile.
struct Badge: View {
    let number: Int
    let color: Color

    var body: some View {
        Text("\(number)")
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(color)
            .frame(width: 24, height: 24)
            .background(fg(0.07), in: RoundedRectangle(cornerRadius: 7, style: .continuous))
    }
}

/// One limit as a bar cut into one equal segment per account (every account is
/// the same 100%). Each is lit in its account's colour for what's left, with a
/// dot-matrix ghost where it's been spent, and a tick at how much of the window
/// is still to run: lit past the tick is under pace. `numbered` puts the
/// account's number in dots at the segment's start.
struct SegmentBar: View {
    let meter: Meter
    let family: PoolFamily
    var numbered = false
    var slim = false

    var body: some View {
        Canvas { ctx, size in
            let gap: CGFloat = slim ? 3 : 4
            let spans = segmentSpans(size.width, max(meter.segments.count, 1), gap: gap)
            let r: CGFloat = slim ? size.height / 2 : 7
            for (i, seg) in meter.segments.enumerated() {
                let start = spans[i].lowerBound, end = spans[i].upperBound
                let w = end - start
                let shape = Path(roundedRect: CGRect(x: start, y: 0, width: w, height: size.height), cornerRadius: r, style: .continuous)
                let color = Palette.member(family, i)
                let litW = min(max(seg.left ?? 0, 0), 1) * w
                var c = ctx
                c.clip(to: shape)
                // The spent stretch is the widgets' dot-matrix: a grid, or one row of dots when slim.
                if !slim { c.fill(Path(CGRect(x: start, y: 0, width: w, height: size.height)), with: .color(fg(0.05))) }
                var spent = c
                spent.clip(to: Path(CGRect(x: start + litW, y: 0, width: end - start - litW, height: size.height)))
                if slim { dotGrid(&spent, size, left: start, width: w, color: fg(0.3), pitch: size.height + 2, r: size.height * 0.36) }
                else { dotGrid(&spent, size, left: start, width: w, color: fg(0.22), pitch: 4, r: 0.8) }
                if litW > 0 { c.fill(Path(CGRect(x: start, y: 0, width: litW, height: size.height)), with: .color(color.opacity(slim ? 1 : 0.88))) }
                if !slim, seg.left != nil, let t = seg.timeLeft {
                    let x = min(max(start + t * w, start + 2), end - 2)
                    var tick = Path()
                    tick.move(to: CGPoint(x: x, y: -2))
                    tick.addLine(to: CGPoint(x: x, y: size.height + 2))
                    ctx.stroke(tick, with: .color(fg()), style: StrokeStyle(lineWidth: 2, lineCap: .round))
                }
                if numbered {
                    let pitch = min(size.height / 11, 2.2)
                    let textW = CGFloat(DotFont.width("\(seg.number)")) * pitch
                    let x = start + 8
                    let top = (size.height - CGFloat(DotFont.height) * pitch) / 2
                    let onLit = litW >= x - start + textW + 2
                    // Off the lit stretch, a clean patch so the number isn't lost in the texture or the tick.
                    if !onLit {
                        let patch = Path(roundedRect: CGRect(x: start, y: 0, width: x - start + textW + 6, height: size.height), cornerRadius: r, style: .continuous)
                        c.fill(patch, with: .color(Palette.background))
                        c.fill(patch, with: .color(fg(0.05)))
                    }
                    ctx.fill(.dotText("\(seg.number)", left: x, top: top, pitch: pitch), with: .color(onLit ? .white : fg(0.75)))
                }
            }
        }
    }

    /// The spent stretch's texture: a fine grid of dots, the widgets' dot-matrix at small size.
    private func dotGrid(_ ctx: inout GraphicsContext, _ size: CGSize, left: CGFloat, width: CGFloat, color: Color, pitch: CGFloat, r: CGFloat) {
        var dots = Path()
        var y = pitch > size.height ? size.height / 2 : pitch / 2 + size.height.truncatingRemainder(dividingBy: pitch) / 2
        while y < size.height {
            var x = left + pitch / 2
            while x < left + width {
                dots.addDot(CGPoint(x: x, y: y), r)
                x += pitch
            }
            y += pitch
        }
        ctx.fill(dots, with: .color(color))
    }
}

/// Every account's week as the threads widget draws it, twisted together. Static: no reveal, no glow.
struct Threads: View {
    let strands: [ThreadStrand]
    var height: CGFloat = 60

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            CapsLabel("7D · ALL", alpha: 0.9, size: 11)
            ThreadsView(strands: strands, fancy: true)
                .frame(height: height)
                .accessibilityLabel("Weekly left, every account")
        }
    }
}
