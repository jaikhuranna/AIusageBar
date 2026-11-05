import Foundation

/// One `/pool` history sample's five-hour numbers: the family's pooled sum and,
/// from bridges that record them, each account's own left and window reset,
/// keyed by provider (`claude`, `claude2`, `codex`). Port of Android's `SessionChart.kt`.
struct SessionSample: Equatable {
    var t: Date
    var pooled: Double
    var left: [String: Double] = [:]
    var resetsAt: [String: Date] = [:]
}

/// A point of the line: `left` is the family's share left, 0..1. `penUp` starts a new stretch (a gap in the recording).
struct ChartPoint: Equatable {
    var t: Date
    var left: Double
    var penUp = false
}

/// A five-hour window turning over, recorded or inferred (`upcoming` false) or
/// still to come. `color` is the account's member index for `Palette.member`
/// (`SessionChart.unknown` when only the family's sum was recorded, so it can't
/// tell whose it was); `gain` is the share of the family it gives back (0..1),
/// the "+N%" over its step.
struct ResetMark: Equatable {
    var at: Date
    var color: Int
    var upcoming: Bool
    var gain: Double
}

/// The card's five-hour graph for one family, pure so it tests without a
/// clock: the family's session left (Σ accounts ÷ accounts, the card's big
/// number) over the last `back` as `line`, then `projection`: holding at today's
/// number until each upcoming reset, stepping up by what it gives back. Every
/// reset in range is in `resets`.
///
/// Resets come from each account's recorded `resets_at` when the bridge has it
/// (exact), else from a rise of at least `jump` points of one account between
/// two samples (placed at the later one). A reset is a vertical step at its
/// instant, so the line and its marker agree.
struct SessionChart: Equatable {
    var from: Date
    var now: Date
    var to: Date
    var line: [ChartPoint]
    var projection: [ChartPoint]
    var resets: [ResetMark]

    static let unknown = -1
    static let back: TimeInterval = 12 * 3600
    /// A rise this big (points of one account) between samples with no recorded reset is one.
    static let jump = 15.0
    /// Samples further apart than this weren't recorded in between (the heartbeat is hourly).
    static let gap: TimeInterval = 150 * 60
    /// At least this much future, so NOW never sits on the right edge.
    private static let minAhead: TimeInterval = 45 * 60
    /// Two marks of one account this close are one reset.
    private static let same: TimeInterval = 2 * 60

    /// `t` across `width`.
    func x(_ t: Date, _ width: Double) -> Double { t.timeIntervalSince(from) / max(to.timeIntervalSince(from), 0.001) * width }

    /// `left` (0..1) down a plot `height` tall starting at `top`: full is the top.
    func y(_ left: Double, top: Double, height: Double) -> Double { top + (1 - min(max(left, 0), 1)) * height }

    /// Where the family is now, or nil without numbers.
    var current: Double? { line.last.flatMap { $0.t == now ? $0.left : nil } }

    private struct Raw {
        let t: Date
        let left: Double
        let sample: SessionSample?
    }

    static func of(_ members: [Member], history: [SessionSample], now: Date, back: TimeInterval = SessionChart.back) -> SessionChart {
        let from = now - back
        let samples = history.sorted { $0.t < $1.t }.filter { $0.t <= now }
        // Keep the last sample before the window, so the line starts at the left edge.
        let firstIn = samples.firstIndex { $0.t >= from } ?? samples.count
        let shown = Array(samples.dropFirst(max(firstIn - 1, 0)))
        let known = members.enumerated().filter { $0.element.session != nil }
        let n = Double(max(known.count, 1))

        // A sample as a share of the family: each account's own number when all
        // are recorded, else the pooled sum (older bridges).
        func share(_ s: SessionSample) -> Double {
            let each = known.map { s.left[$0.element.provider] }
            let sum = !each.isEmpty && each.allSatisfy({ $0 != nil }) ? each.reduce(0) { $0 + $1! } : s.pooled
            return min(max(sum / n / 100, 0), 1)
        }
        var raw = shown.map { Raw(t: $0.t, left: share($0), sample: $0) }
        if !known.isEmpty {
            let sum = Double(known.reduce(0) { $0 + $1.element.session!.remaining })
            raw.append(Raw(t: now, left: min(max(sum / n / 100, 0), 1), sample: nil))
        }

        var marks: [ResetMark] = []
        var line: [ChartPoint] = []
        for (k, b) in raw.enumerated() {
            guard k > 0 else { line.append(ChartPoint(t: b.t, left: b.left)); continue }
            let a = raw[k - 1]
            let isGap = b.t.timeIntervalSince(a.t) > gap
            let steps: [ResetMark]
            if let s = a.sample, !s.resetsAt.isEmpty {
                // Each account's recorded reset since `a`, giving back what it had spent.
                steps = known.compactMap { i, m -> ResetMark? in
                    guard let at = s.resetsAt[m.provider], at > a.t, at <= b.t else { return nil }
                    return ResetMark(at: at, color: i, upcoming: false, gain: max((100 - (s.left[m.provider] ?? 100)) / n / 100, 0))
                }.sorted { $0.at < $1.at }
            } else if b.left - a.left >= jump / n / 100 {
                let color = n == 1 ? (known.first?.offset ?? 0) : unknown
                steps = [ResetMark(at: b.t, color: color, upcoming: false, gain: b.left - a.left)]
            } else {
                steps = []
            }
            marks += steps
            if !isGap {
                var v = a.left
                for r in steps {
                    line.append(ChartPoint(t: r.at, left: v))
                    v = min(v + r.gain, 1)
                    line.append(ChartPoint(t: r.at, left: v))
                }
            }
            line.append(ChartPoint(t: b.t, left: b.left, penUp: isGap))
        }

        let upcoming = known.compactMap { i, m -> ResetMark? in
            let s = m.session!
            guard let r = s.resetsAt, r > now else { return nil }
            return ResetMark(at: r, color: i, upcoming: true, gain: max(Double(100 - s.remaining) / n / 100, 0))
        }.sorted { $0.at < $1.at }
        let latest = max(upcoming.last?.at ?? now, now + minAhead)
        // A little room past the last reset, so its step and notch aren't on the edge.
        let to = latest + (latest.timeIntervalSince(from) / 30 * 1000).rounded(.towardZero) / 1000

        var projection: [ChartPoint] = []
        if let start = line.last, start.t == now {
            var v = start.left
            projection.append(ChartPoint(t: now, left: v))
            for r in upcoming {
                projection.append(ChartPoint(t: r.at, left: v))
                v = min(v + r.gain, 1)
                projection.append(ChartPoint(t: r.at, left: v))
            }
            projection.append(ChartPoint(t: to, left: v))
        }

        var kept: [ResetMark] = []
        for mk in (marks + upcoming).filter({ $0.at >= from && $0.at <= to }).sorted(by: { $0.at < $1.at }) {
            if !kept.contains(where: { $0.color == mk.color && abs($0.at.timeIntervalSince(mk.at)) <= same }) { kept.append(mk) }
        }
        // The sample just before the range is useful for interpolation, but
        // must never draw into the percentage labels beside the plot.
        let visible = Array(line.drop { $0.t < from })
        var edge: [ChartPoint] = []
        if let first = visible.first, let previous = line.last(where: { $0.t < from }), first.t > from, !first.penUp {
            let fraction = from.timeIntervalSince(previous.t) / first.t.timeIntervalSince(previous.t)
            edge = [ChartPoint(t: from, left: previous.left + (first.left - previous.left) * fraction)]
        }
        return SessionChart(from: from, now: now, to: to, line: edge + visible, projection: projection, resets: kept)
    }
}

/// Where labels go along an axis `width` wide: each is (centre, width), in
/// priority order. A label is centred on its mark and slid inside the axis; if
/// it would come within `gap` of one already placed, it moves just clear of it
/// when that's no more than `nudge` off its mark, else it's dropped. The result
/// is each label's left edge, or nil where it was dropped.
func placeLabels(_ labels: [(centre: Double, width: Double)], width: Double, gap: Double, nudge: Double = 0) -> [Double?] {
    var placed: [ClosedRange<Double>] = []
    func fits(_ left: Double, _ w: Double) -> Bool {
        left >= 0 && left + w <= width + 0.01 && !placed.contains { left < $0.upperBound + gap && $0.lowerBound < left + w + gap }
    }
    return labels.map { centre, w in
        let ideal = min(max(centre - w / 2, 0), max(width - w, 0))
        let beside = placed.flatMap { [$0.upperBound + gap, $0.lowerBound - gap - w] }.filter { abs($0 - ideal) <= nudge }
        let left = ([ideal] + beside.sorted { abs($0 - ideal) < abs($1 - ideal) }).first { fits($0, w) }
        if let left { placed.append(left...(left + w)) }
        return left
    }
}

/// `points` split where the recording broke off.
func stretches(_ points: [ChartPoint]) -> [[ChartPoint]] {
    var out: [[ChartPoint]] = []
    for p in points {
        if out.isEmpty || p.penUp { out.append([p]) } else { out[out.count - 1].append(p) }
    }
    return out
}

/// The step a reset at `at` makes in `points`: (from, to), or nil where it isn't drawn (a gap in the recording).
func stepOf(_ points: [ChartPoint], at: Date) -> (Double, Double)? {
    guard let i = points.firstIndex(where: { $0.t == at }), i + 1 < points.count else { return nil }
    let next = points[i + 1]
    guard next.t == at, !next.penUp else { return nil }
    return (points[i].left, next.left)
}
