package com.jaikhurana.aiusagewidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class LookTest {
    private val now = Instant.parse("2026-09-24T12:00:00Z")
    private val fetched = now.minusSeconds(60).toEpochMilli()
    private fun at(minutes: Long) = now.plus(Duration.ofMinutes(minutes))
    private fun win(remaining: Int, resetInMinutes: Long?) =
        LimitWindow(100 - remaining, remaining, resetInMinutes?.let(::at))
    private fun snap(five: LimitWindow?, week: LimitWindow?) = Snapshot(five, week, 0.0, 0.0, 0.0, "host")
    private fun look(s: Snapshot?, backAt: Long = 0, error: String? = null, paired: Boolean = true) =
        Look.of(paired, s, fetched, error, backAt, now)

    @Test fun unpairedWinsOverEverything() {
        assertEquals(Mode.UNPAIRED, look(snap(win(0, 60), null), paired = false).mode)
        assertEquals(Mode.UNPAIRED, look(snap(win(50, 60), null), error = Store.ERROR_UNPAIRED).mode)
    }

    @Test fun pairedWithoutDataIsWaiting() = assertEquals(Mode.WAITING, look(null).mode)

    @Test fun weeklyOutBeatsSessionOut() {
        val l = look(snap(win(0, 60), win(0, 3000)))
        assertEquals(Mode.WEEK_OUT, l.mode)
        assertEquals(at(3000), l.countdownTo)
    }

    @Test fun sessionOutCountsDownToTheSessionReset() {
        val l = look(snap(win(0, 150), win(40, 3000)))
        assertEquals(Mode.SESSION_OUT, l.mode)
        assertEquals(at(150), l.countdownTo)
        assertEquals(0.5f, l.sessionTimerFraction(), 0.001f)
        assertTrue(l.ticking)
    }

    @Test fun goForAnHourAfterTheComeback() {
        val s = snap(win(100, null), win(40, 3000))
        assertEquals(Mode.GO, look(s, backAt = at(-10).toEpochMilli()).mode)
        assertEquals(Mode.NORMAL, look(s, backAt = at(-61).toEpochMilli()).mode)
        assertEquals(Mode.NORMAL, look(s, backAt = at(10).toEpochMilli()).mode)
    }

    @Test fun goEndsOnceTheSessionIsHalfGone() {
        val back = at(-14).toEpochMilli()
        assertEquals(Mode.GO, look(snap(win(50, 286), win(40, 3000)), backAt = back).mode)
        assertEquals(Mode.NORMAL, look(snap(win(28, 286), win(40, 3000)), backAt = back).mode)
    }

    @Test fun untilTextRoundsUpLikeTheCountdown() {
        val left = Duration.ofMinutes(270).plusSeconds(40)
        assertEquals("4:31", countdownText(now, now.plus(left)))
        assertEquals("4h 31m", untilText(now, now.plus(left)))
    }

    @Test fun aResetThatPassedOfflineSaysGo() {
        // Still holding the exhausted snapshot, but its reset time has gone by.
        val l = look(snap(win(0, -5), win(40, 3000)), backAt = at(-5).toEpochMilli())
        assertEquals(Mode.GO, l.mode)
    }

    @Test fun staleAfterAnHourOrWhenOffline() {
        assertFalse(look(snap(win(50, 60), null)).stale)
        assertTrue(look(snap(win(50, 60), null), error = Store.ERROR_OFFLINE).stale)
        val old = Look.of(true, snap(win(50, 60), null), now.minus(Duration.ofMinutes(61)).toEpochMilli(), null, 0, now)
        assertTrue(old.stale)
    }

    @Test fun anExhaustedLimitIsNotStaleWhileWaitingForItsReset() {
        val old = Look.of(true, snap(win(0, 240), null), now.minus(Duration.ofHours(2)).toEpochMilli(), null, 0, now)
        assertFalse(old.stale)
    }

    @Test fun paceSaysWhetherTheWindowLasts() {
        // 2.5h into a 5h window with 30% used: plenty.
        assertEquals(Pace.Lasts, pace(Effective(70, at(150), false), Look.SESSION, now))
        // 1h in with 50% used: empty in another hour.
        assertEquals(Pace.RunsOut(at(60)), pace(Effective(50, at(240), false), Look.SESSION, now))
        assertEquals(Pace.Unused, pace(Effective(100, at(240), false), Look.SESSION, now))
    }

    @Test fun countdownFormats() {
        assertEquals("2:41", countdownText(now, at(161)))
        assertEquals("41M", countdownText(now, at(41)))
        assertEquals("1M", countdownText(now, now.plusSeconds(20)))
        assertEquals("3D", countdownText(now, at(3 * 1440 + 100)))
        assertEquals("2H", shortCountdown(now, at(161)))
        assertEquals("--", shortCountdown(now, null))
    }

    @Test fun dotFontWidths() {
        assertEquals(5, DotFont.width("0"))
        assertEquals(3, DotFont.width("1"))
        assertEquals(5 + 1 + 1 + 1 + 5 + 1 + 3, DotFont.width("2:41")) // ":" is 1 wide, "1" is 3
    }
}
