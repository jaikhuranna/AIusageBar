package com.jaikhurana.aiusagewear

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The twist the threads faces draw, as a rope seen from the side: strand `i`
 * of `count` is a point on a circle turning along the braid, `2π·i/count`
 * round from the first. Its height is the sine of its angle and its nearness
 * the cosine. Two strands are level only where their angles sum to π, and
 * there their nearness is equal and opposite, so at every crossing one is
 * clearly in front, for any number of accounts. Pure; `BraidTest`.
 * The phone's `Braid.kt` and `ios/Core/Braid.swift` are the same.
 */
object Braid {
    /** Strand [i]'s angle at [t] (radians along the braid; one half twist is π). */
    fun angle(i: Int, count: Int, t: Double) = t + PI + 2 * PI * i / count.coerceAtLeast(1)

    /** Height off the braid's middle line, -1..1. */
    fun offset(i: Int, count: Int, t: Double) = sin(angle(i, count, t))

    /** Nearness to the viewer, -1 (behind) .. 1 (in front). */
    fun depth(i: Int, count: Int, t: Double) = cos(angle(i, count, t))

    /** Where two strands cross: at [t], [front] passes over [back]. */
    data class Crossing(val t: Double, val front: Int, val back: Int)

    /** Every crossing between [t0] and [t1], found to within a thousandth of a radian. */
    fun crossings(count: Int, t0: Double, t1: Double): List<Crossing> {
        if (count < 2 || t1 <= t0) return emptyList()
        val out = mutableListOf<Crossing>()
        val step = 0.01
        for (i in 0 until count) for (j in i + 1 until count) {
            fun gap(t: Double) = offset(i, count, t) - offset(j, count, t)
            var a = t0
            var ga = gap(t0)
            while (a < t1) {
                val b = min(a + step, t1)
                val gb = gap(b)
                if (ga == 0.0 || (ga < 0) != (gb < 0)) {
                    // Halve down to the crossing.
                    var lo = a
                    var hi = b
                    repeat(20) {
                        val mid = (lo + hi) / 2
                        if ((gap(lo) < 0) != (gap(mid) < 0)) hi = mid else lo = mid
                    }
                    val t = (lo + hi) / 2
                    val iFront = depth(i, count, t) > depth(j, count, t)
                    val c = Crossing(t, if (iFront) i else j, if (iFront) j else i)
                    if (out.none { it.front == c.front && it.back == c.back && abs(it.t - t) < 1e-3 }) out += c
                }
                a = b
                ga = gb
            }
        }
        return out.sortedBy { it.t }
    }
}
