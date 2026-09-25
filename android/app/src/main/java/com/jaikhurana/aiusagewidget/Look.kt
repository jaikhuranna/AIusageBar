package com.jaikhurana.aiusagewidget

import java.time.Duration
import java.time.Instant

/** What every widget is showing. Priority runs top to bottom. */
enum class Mode {
    /** No token yet: tap to pair. */
    UNPAIRED,
    /** Paired, but no snapshot has arrived. */
    WAITING,
    /** Weekly limit hit: grey, struck through, until the weekly reset. */
    WEEK_OUT,
    /** 5h limit hit: the yellow dotted timer counts down to the session reset. */
    SESSION_OUT,
    /** A limit just came back. */
    GO,
    NORMAL,
}

/**
 * Everything a renderer needs, decided once. Pure, so the rules are unit-tested
 * and every widget agrees on them.
 */
data class Look(
    val mode: Mode,
    val now: Instant,
    val session: Effective?,
    val week: Effective?,
    val fetchedAt: Long,
    val offline: Boolean,
) {
    /**
     * Old enough that the numbers shouldn't be trusted at full brightness. An
     * exhausted limit isn't polled until its reset (nothing can change), so it
     * only goes stale by being offline.
     */
    val stale: Boolean
        get() = when (mode) {
            Mode.UNPAIRED -> false
            Mode.WEEK_OUT, Mode.SESSION_OUT -> offline
            else -> offline || Duration.between(Instant.ofEpochMilli(fetchedAt), now) > STALE
        }

    val sessionLeft: Int get() = session?.remaining ?: 100
    val weekLeft: Int get() = week?.remaining ?: 100

    /** What the countdown in SESSION_OUT / WEEK_OUT is counting to. */
    val countdownTo: Instant?
        get() = when (mode) {
            Mode.SESSION_OUT -> session?.resetsAt
            Mode.WEEK_OUT -> week?.resetsAt
            else -> null
        }

    /** True while a countdown is on screen, so the widgets redraw every minute. */
    val ticking: Boolean get() = countdownTo != null

    /**
     * How much of the 5h timer is still to run, 1 → 0. Display only: the reset
     * time itself always comes from `resets_at`.
     */
    fun sessionTimerFraction(): Float {
        val at = session?.resetsAt ?: return 0f
        return (Duration.between(now, at).seconds.toFloat() / SESSION.seconds).coerceIn(0f, 1f)
    }

    companion object {
        val SESSION: Duration = Duration.ofHours(5)
        val WEEK: Duration = Duration.ofDays(7)
        /** The background cadence tops out at 30 min, so an hour means two missed checks. */
        val STALE: Duration = Duration.ofHours(1)
        /** How long "GO!" stays up after a comeback. */
        val GO_FOR: Duration = Duration.ofHours(1)
        /** ...or until the session is half used again, when the numbers matter more. */
        const val GO_WHILE_LEFT = 50

        fun of(
            paired: Boolean,
            snap: Snapshot?,
            fetchedAt: Long,
            lastError: String?,
            backAt: Long,
            now: Instant,
        ): Look {
            val session = snap?.fiveHour?.effective(now)
            val week = snap?.sevenDay?.effective(now)
            val back = Instant.ofEpochMilli(backAt)
            val mode = when {
                !paired || lastError == Store.ERROR_UNPAIRED -> Mode.UNPAIRED
                snap == null -> Mode.WAITING
                week != null && week.remaining <= 0 -> Mode.WEEK_OUT
                session != null && session.remaining <= 0 -> Mode.SESSION_OUT
                backAt != 0L && !now.isBefore(back) && now.isBefore(back.plus(GO_FOR)) &&
                    (session?.remaining ?: 100) >= GO_WHILE_LEFT -> Mode.GO
                else -> Mode.NORMAL
            }
            return Look(mode, now, session, week, fetchedAt, lastError == Store.ERROR_OFFLINE)
        }
    }
}

/**
 * Will this window last until it resets, at the rate it has been used so far?
 * An estimate for display; the window's start is inferred from its length.
 */
sealed interface Pace {
    data object Unused : Pace
    data object Lasts : Pace
    data class RunsOut(val at: Instant) : Pace
}

fun pace(e: Effective?, length: Duration, now: Instant): Pace? {
    val reset = e?.resetsAt ?: return null
    if (e.remaining <= 0) return null
    val left = Duration.between(now, reset)
    val elapsed = length.minus(left)
    val used = 100 - e.remaining
    if (used <= 0) return Pace.Unused
    // Too early in the window for a rate to mean anything.
    if (elapsed < Duration.ofMinutes(10)) return null
    val secondsToEmpty = e.remaining.toDouble() * elapsed.seconds / used
    return if (secondsToEmpty >= left.seconds) Pace.Lasts else Pace.RunsOut(now.plusSeconds(secondsToEmpty.toLong()))
}

/** "2:41" above an hour, "41M" under it, "3D" for days. Fits the dot-matrix numerals. */
fun countdownText(from: Instant, to: Instant): String {
    val d = Duration.between(from, to)
    if (d.isNegative || d.isZero) return "0M"
    val m = (d.toMinutes() + if (d.toSecondsPart() > 0) 1 else 0)
    return when {
        m >= 48 * 60 -> "${m / 1440}D"
        m >= 60 -> "${m / 60}:${"%02d".format(m % 60)}"
        else -> "${m}M"
    }
}

/** "2H", "41M", "3D": the tightest form, for a corner label. */
fun shortCountdown(from: Instant, to: Instant?): String {
    to ?: return "--"
    val m = Duration.between(from, to).toMinutes()
    return when {
        m < 1 -> "0M"
        m < 60 -> "${m}M"
        m < 48 * 60 -> "${m / 60}H"
        else -> "${m / 1440}D"
    }
}
