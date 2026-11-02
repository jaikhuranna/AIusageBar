package com.jaikhurana.aiusagewidget

import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * One `/pool` history sample's five-hour numbers: the family's pooled sum and,
 * from bridges that record them, each account's own left and window reset,
 * keyed by provider (`claude`, `claude2`, `codex`).
 */
data class SessionSample(val t: Instant, val pooled: Double, val left: Map<String, Double> = emptyMap(), val resetsAt: Map<String, Instant> = emptyMap())

/** A point of the line: [left] is the family's share left, 0..1. [penUp] starts a new stretch (a gap in the recording). */
data class ChartPoint(val t: Instant, val left: Float, val penUp: Boolean = false)

/**
 * A five-hour window turning over, recorded or inferred ([upcoming] false) or
 * still to come. [color] is the account's member index for
 * `Render.memberColor` ([SessionChart.UNKNOWN] when only the family's sum was
 * recorded, so it can't tell whose it was); [gain] is the share of the family
 * it gives back (0..1), the "+N%" over its step.
 */
data class ResetMark(val at: Instant, val color: Int, val upcoming: Boolean, val gain: Float)

/**
 * The card's five-hour graph for one family, pure so it tests without a
 * clock: the family's session left (Σ accounts ÷ accounts, the card's big
 * number) over the last [BACK] as [line], then [projection]: holding at
 * today's number until each upcoming reset, stepping up by what it gives
 * back. Every reset in range is in [resets].
 *
 * Resets come from each account's recorded `resets_at` when the bridge has
 * it (exact), else from a rise of at least [JUMP] points of one account
 * between two samples (placed at the later one). A reset is a vertical step
 * at its instant, so the line and its marker agree.
 */
data class SessionChart(
    val from: Instant, val now: Instant, val to: Instant,
    val line: List<ChartPoint>, val projection: List<ChartPoint>, val resets: List<ResetMark>,
) {
    /** [t] across [width]. */
    fun x(t: Instant, width: Float): Float = (Duration.between(from, t).toMillis().toFloat() / span().coerceAtLeast(1)) * width

    /** [left] (0..1) down a plot [height] tall starting at [top]: full is the top. */
    fun y(left: Float, top: Float, height: Float): Float = top + (1f - left.coerceIn(0f, 1f)) * height

    /** Where the family is now, or null without numbers. */
    val current: Float? get() = line.lastOrNull()?.takeIf { it.t == now }?.left

    private fun span() = Duration.between(from, to).toMillis()

    companion object {
        const val UNKNOWN = -1
        val BACK: Duration = Duration.ofHours(12)
        /** A rise this big (points of one account) between samples with no recorded reset is one. */
        const val JUMP = 15.0
        /** Samples further apart than this weren't recorded in between (the heartbeat is hourly). */
        val GAP: Duration = Duration.ofMinutes(150)
        /** At least this much future, so NOW never sits on the right edge. */
        private val MIN_AHEAD: Duration = Duration.ofMinutes(45)
        /** Two marks of one account this close are one reset. */
        private val SAME: Duration = Duration.ofMinutes(2)

        fun of(members: List<Member>, history: List<SessionSample>, now: Instant, back: Duration = BACK): SessionChart {
            val from = now.minus(back)
            val samples = history.sortedBy { it.t }.filter { !it.t.isAfter(now) }
            // Keep the last sample before the window, so the line starts at the left edge.
            val firstIn = samples.indexOfFirst { !it.t.isBefore(from) }.let { if (it < 0) samples.size else it }
            val shown = samples.drop((firstIn - 1).coerceAtLeast(0))
            val known = members.withIndex().filter { it.value.session != null }
            val n = known.size.coerceAtLeast(1)

            // A sample as a share of the family: each account's own number when all
            // are recorded, else the pooled sum (older bridges).
            fun share(s: SessionSample): Float {
                val each = known.map { s.left[it.value.provider] }
                val sum = if (each.isNotEmpty() && each.all { it != null }) each.sumOf { it!! } else s.pooled
                return (sum / n / 100).toFloat().coerceIn(0f, 1f)
            }
            val raw = shown.map { Raw(it.t, share(it), it) } +
                (if (known.isEmpty()) emptyList() else listOf(Raw(now, (known.sumOf { it.value.session!!.remaining }.toFloat() / n / 100).coerceIn(0f, 1f), null)))

            val marks = mutableListOf<ResetMark>()
            val line = mutableListOf<ChartPoint>()
            raw.forEachIndexed { k, b ->
                val a = raw.getOrNull(k - 1)
                if (a == null) { line += ChartPoint(b.t, b.left); return@forEachIndexed }
                val gap = Duration.between(a.t, b.t) > GAP
                val steps = a.sample?.takeIf { it.resetsAt.isNotEmpty() }?.let { s ->
                    // Each account's recorded reset since [a], giving back what it had spent.
                    known.mapNotNull { (i, m) ->
                        val at = s.resetsAt[m.provider]?.takeIf { it.isAfter(a.t) && !it.isAfter(b.t) } ?: return@mapNotNull null
                        ResetMark(at, i, false, ((100 - (s.left[m.provider] ?: 100.0)) / n / 100).toFloat().coerceAtLeast(0f))
                    }.sortedBy { it.at }
                } ?: if (b.left - a.left >= JUMP / n / 100) listOf(ResetMark(b.t, if (n == 1) known.firstOrNull()?.index ?: 0 else UNKNOWN, false, b.left - a.left)) else emptyList()
                marks += steps
                if (!gap) {
                    var v = a.left
                    steps.forEach { r ->
                        line += ChartPoint(r.at, v)
                        v = (v + r.gain).coerceAtMost(1f)
                        line += ChartPoint(r.at, v)
                    }
                }
                line += ChartPoint(b.t, b.left, penUp = gap)
            }

            val upcoming = known.mapNotNull { (i, m) ->
                val s = m.session!!
                s.resetsAt?.takeIf { it.isAfter(now) }?.let { ResetMark(it, i, true, ((100 - s.remaining).toFloat() / n / 100).coerceAtLeast(0f)) }
            }.sortedBy { it.at }
            val latest = maxOf(upcoming.lastOrNull()?.at ?: now, now.plus(MIN_AHEAD))
            // A little room past the last reset, so its step and notch aren't on the edge.
            val to = latest.plusMillis(Duration.between(from, latest).toMillis() / 30)

            val projection = mutableListOf<ChartPoint>()
            line.lastOrNull()?.takeIf { it.t == now }?.let { start ->
                var v = start.left
                projection += ChartPoint(now, v)
                upcoming.forEach { r ->
                    projection += ChartPoint(r.at, v)
                    v = (v + r.gain).coerceAtMost(1f)
                    projection += ChartPoint(r.at, v)
                }
                projection += ChartPoint(to, v)
            }

            val kept = (marks + upcoming).filter { !it.at.isBefore(from) && !it.at.isAfter(to) }
                .sortedBy { it.at }
                .fold(mutableListOf<ResetMark>()) { acc, mk ->
                    if (acc.none { it.color == mk.color && Duration.between(it.at, mk.at).abs() <= SAME }) acc += mk
                    acc
                }
            // The sample just before the range is useful for interpolation,
            // but must never draw into the percentage labels beside the plot.
            val visible = line.dropWhile { it.t.isBefore(from) }
            val first = visible.firstOrNull()
            val previous = line.lastOrNull { it.t.isBefore(from) }
            val edge = if (first != null && previous != null && first.t.isAfter(from) && !first.penUp) {
                val fraction = Duration.between(previous.t, from).toMillis().toDouble() /
                    Duration.between(previous.t, first.t).toMillis()
                listOf(ChartPoint(from, (previous.left + (first.left - previous.left) * fraction).toFloat()))
            } else emptyList()
            return SessionChart(from, now, to, edge + visible, projection, kept)
        }

        private class Raw(val t: Instant, val left: Float, val sample: SessionSample?)
    }
}

/**
 * Where labels go along an axis [width] wide: each is (centre, width), in
 * priority order. A label is centred on its mark and slid inside the axis; if
 * it would come within [gap] of one already placed, it moves just clear of it
 * when that's no more than [nudge] off its mark, else it's dropped. The result
 * is each label's left edge, or null where it was dropped.
 */
fun placeLabels(labels: List<Pair<Float, Float>>, width: Float, gap: Float, nudge: Float = 0f): List<Float?> {
    val placed = mutableListOf<ClosedFloatingPointRange<Float>>()
    fun fits(left: Float, w: Float) = left >= 0f && left + w <= width + 0.01f &&
        placed.none { left < it.endInclusive + gap && it.start < left + w + gap }
    return labels.map { (centre, w) ->
        val ideal = (centre - w / 2).coerceIn(0f, (width - w).coerceAtLeast(0f))
        val beside = placed.flatMap { listOf(it.endInclusive + gap, it.start - gap - w) }.filter { abs(it - ideal) <= nudge }
        val left = (listOf(ideal) + beside.sortedBy { abs(it - ideal) }).firstOrNull { fits(it, w) }
        left?.also { placed += it..(it + w) }
    }
}
