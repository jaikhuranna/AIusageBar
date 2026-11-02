package com.jaikhurana.aiusagewidget

import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.roundToInt

/** The two limits every account has. */
enum class Limit(val title: String) { SESSION("Session"), WEEK("Weekly") }

/**
 * One account of a family, numbered from 1 in the bridge's order. [session]
 * and [week] are already [effective], so a reset that passed offline is full.
 */
data class Member(
    val number: Int,
    val provider: String,
    val account: String?,
    val session: Effective?,
    val week: Effective?,
    val sessionLength: Duration = Look.SESSION,
    val weekLength: Duration = Look.WEEK,
    val error: String? = null,
) {
    /** The email when the bridge knows it, else the provider's own name ("Codex"). */
    val name: String get() = account ?: UsageProvider.entries.firstOrNull { it.wire == provider }?.title ?: provider

    fun window(limit: Limit): Effective? = if (limit == Limit.SESSION) session else week
    fun length(limit: Limit): Duration = if (limit == Limit.SESSION) sessionLength else weekLength

    companion object {
        fun of(number: Int, provider: String, account: String?, fiveHour: LimitWindow?, sevenDay: LimitWindow?, error: String?, now: Instant) =
            Member(
                number, provider, account, fiveHour?.effective(now), sevenDay?.effective(now),
                fiveHour?.windowMinutes?.let { Duration.ofMinutes(it.toLong()) } ?: Look.SESSION,
                sevenDay?.windowMinutes?.let { Duration.ofMinutes(it.toLong()) } ?: Look.WEEK,
                error,
            )
    }
}

/**
 * A family as the app and the card show it: its accounts, how old the numbers
 * are, and the bridge's reason when there are none. Built from `/pool` when the
 * bridge has it (any number of accounts), else from the per-provider
 * snapshots (an older bridge answers 404 to `/pool`).
 */
data class FamilyView(
    val family: PoolFamily,
    val members: List<Member>,
    val fetchedAt: Long,
    val offline: Boolean,
    val error: String?,
    /** `/pool`'s recorded five-hour samples, oldest first; empty from the per-provider fallback. */
    val history: List<SessionSample> = emptyList(),
) {
    val known: List<Member> get() = members.filter { it.session != null || it.week != null }

    companion object {
        fun of(pool: Pool, fetchedAt: Long, offline: Boolean, now: Instant) = FamilyView(
            pool.family,
            pool.members.mapIndexed { i, m -> Member.of(i + 1, m.provider, m.account, m.fiveHour, m.sevenDay, m.sourceError, now) },
            fetchedAt, offline, pool.sourceError, pool.history,
        )

        /** The fallback: one member per provider of the family that has numbers. */
        fun of(family: PoolFamily, snapshots: List<Snapshot?>, fetchedAt: Long, offline: Boolean, now: Instant): FamilyView {
            val withData = snapshots.filterNotNull().filter { it.fiveHour != null || it.sevenDay != null }
            return FamilyView(
                family,
                withData.mapIndexed { i, s -> Member.of(i + 1, s.provider.wire, s.account, s.fiveHour, s.sevenDay, s.sourceError, now) },
                fetchedAt, offline,
                if (withData.isEmpty()) snapshots.firstNotNullOfOrNull { it?.sourceError } else null,
            )
        }
    }
}

/** What comes back at the family's next reset of a limit, as a share of the whole family. */
data class Comeback(val gain: Int, val at: Instant) {
    /** "+28% in 3h 30m". */
    fun text(now: Instant) = "+$gain% in ${untilText(now, at)}"
}

/** Spending against the clock: ahead burns faster than the window allows. */
enum class PaceMark(val text: String) { AHEAD("Ahead of pace"), ON("On pace"), UNDER("Under pace") }

/** One account's slice of a [Meter]: what's left, and how much of its window is still to run. Null is unknown. */
data class Segment(val number: Int, val left: Float?, val timeLeft: Float?)

/**
 * One limit across a family, the way the app's cards draw it: the share of
 * the whole family left (two accounts at 45% and 82% are 64%), what the next
 * reset brings back, the pace, and one segment per account.
 */
data class Meter(val left: Int?, val comeback: Comeback?, val pace: PaceMark?, val segments: List<Segment>) {
    val out: Boolean get() = left != null && left <= 0

    companion object {
        /** How far off the clock, in points of the family, still counts as on pace. */
        const val PACE_SLACK = 5.0

        fun of(members: List<Member>, limit: Limit, now: Instant): Meter {
            val known = members.filter { it.window(limit) != null }
            val segments = members.map { m ->
                val e = m.window(limit)
                Segment(m.number, e?.let { it.remaining / 100f }, e?.resetsAt?.let { timeLeft(now, it, m.length(limit)) })
            }
            if (known.isEmpty()) return Meter(null, null, null, segments)
            val left = share(known.sumOf { it.window(limit)!!.remaining }.toDouble(), known.size)
            return Meter(left, comeback(known, limit, now), if (left <= 0) null else pace(known, limit, now), segments)
        }

        /**
         * The soonest reset that gives anything back, and how much: every
         * account resetting within a minute of it counts, as a share of [members].
         */
        fun comeback(members: List<Member>, limit: Limit, now: Instant): Comeback? {
            val due = members.mapNotNull { m -> m.window(limit)?.takeIf { it.remaining < 100 && it.resetsAt?.isAfter(now) == true } }
            val first = due.minOfOrNull { it.resetsAt!! } ?: return null
            val together = due.filter { Duration.between(first, it.resetsAt).abs() <= Duration.ofMinutes(1) }
            val gain = together.sumOf { 100 - it.remaining }.toDouble()
            return Comeback(max(1, share(gain, members.size)), first)
        }

        /**
         * Left against time left, summed over the accounts whose window is
         * running: each window "should" have as much left as it has time left.
         * Null until the windows are ten minutes in, when a rate means something.
         */
        fun pace(members: List<Member>, limit: Limit, now: Instant): PaceMark? {
            val running = members.mapNotNull { m ->
                val e = m.window(limit) ?: return@mapNotNull null
                val reset = e.resetsAt ?: return@mapNotNull null
                val length = m.length(limit)
                val elapsed = length.minus(Duration.between(now, reset))
                if (elapsed < Duration.ofMinutes(10)) null else e.remaining - timeLeft(now, reset, length) * 100.0
            }
            if (running.isEmpty()) return null
            val ahead = running.sum() / running.size
            return when {
                ahead < -PACE_SLACK -> PaceMark.AHEAD
                ahead > PACE_SLACK -> PaceMark.UNDER
                else -> PaceMark.ON
            }
        }

        /** [sum] percent across [count] accounts, as a percent of all of them. */
        private fun share(sum: Double, count: Int) = (sum / count.coerceAtLeast(1)).roundToInt()

        private fun timeLeft(now: Instant, reset: Instant, length: Duration) =
            (Duration.between(now, reset).seconds.toFloat() / length.seconds.coerceAtLeast(1)).coerceIn(0f, 1f)
    }
}

/**
 * Where each of [count] equal segments sits across [width], [gap] apart:
 * every account is the same 100%, so every segment is the same width.
 */
fun segmentSpans(width: Float, count: Int, gap: Float): List<ClosedFloatingPointRange<Float>> {
    if (count <= 0) return emptyList()
    val g = if (count > 1) gap.coerceAtMost(width / (count - 1) / 2) else 0f
    val w = (width - g * (count - 1)) / count
    return List(count) { i -> (i * (w + g)).let { it..(it + w) } }
}
