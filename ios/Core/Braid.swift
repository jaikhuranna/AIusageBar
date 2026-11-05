import Foundation

/// The twist the threads faces draw, as a rope seen from the side: strand `i`
/// of `count` is a point on a circle turning along the braid, `2π·i/count`
/// round from the first. Seen from the side, a strand's height is the sine
/// of its angle and its nearness the cosine. Two strands are at the same
/// height only where their angles sum to π, and there their nearness is
/// equal and opposite, so at every crossing one is clearly in front. Drawing
/// each short slice back to front, with a gap cut around each strand before
/// it's drawn, gives true over-and-under crossings for any number of
/// accounts. Port target: Android's `Braid` in `Render.kt`.
enum Braid {
    /// Strand `i`'s angle at `t` (radians along the braid; one half twist is π).
    static func angle(_ i: Int, of count: Int, at t: Double) -> Double {
        t + .pi + 2 * .pi * Double(i) / Double(max(count, 1))
    }

    /// Height off the braid's middle line, -1...1.
    static func offset(_ i: Int, of count: Int, at t: Double) -> Double {
        sin(angle(i, of: count, at: t))
    }

    /// Nearness to the viewer, -1 (behind) ... 1 (in front).
    static func depth(_ i: Int, of count: Int, at t: Double) -> Double {
        cos(angle(i, of: count, at: t))
    }

    /// The strands at `t`, back to front.
    static func order(_ count: Int, at t: Double) -> [Int] {
        (0..<count).sorted { depth($0, of: count, at: t) < depth($1, of: count, at: t) }
    }

    /// Where two strands cross: at `t`, `front` passes over `back`.
    struct Crossing: Equatable {
        var t: Double
        var front: Int
        var back: Int
    }

    /// Every crossing between `t0` and `t1`, found to within a thousandth of a radian.
    static func crossings(_ count: Int, from t0: Double, to t1: Double) -> [Crossing] {
        guard count > 1, t1 > t0 else { return [] }
        var out: [Crossing] = []
        let step = 0.01
        for i in 0..<count {
            for j in (i + 1)..<count {
                func gap(_ t: Double) -> Double { offset(i, of: count, at: t) - offset(j, of: count, at: t) }
                var a = t0, ga = gap(t0)
                while a < t1 {
                    let b = min(a + step, t1), gb = gap(b)
                    if ga == 0 || (ga < 0) != (gb < 0) {
                        // Halve down to the crossing.
                        var lo = a, hi = b
                        for _ in 0..<20 {
                            let mid = (lo + hi) / 2
                            if (gap(lo) < 0) != (gap(mid) < 0) { hi = mid } else { lo = mid }
                        }
                        let t = (lo + hi) / 2
                        let iFront = depth(i, of: count, at: t) > depth(j, of: count, at: t)
                        if !out.contains(where: { $0.front == (iFront ? i : j) && $0.back == (iFront ? j : i) && abs($0.t - t) < 1e-3 }) {
                            out.append(Crossing(t: t, front: iFront ? i : j, back: iFront ? j : i))
                        }
                    }
                    a = b
                    ga = gb
                }
            }
        }
        return out.sorted { $0.t < $1.t }
    }
}
