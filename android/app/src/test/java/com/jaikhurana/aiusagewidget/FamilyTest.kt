package com.jaikhurana.aiusagewidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class FamilyTest {
    private val now = Instant.parse("2026-10-30T12:00:00Z")
    private fun m(min: Long) = now.plus(Duration.ofMinutes(min))

    /** An account with [left]% of its session, resetting in [resetIn] minutes of a 5h window. */
    private fun session(number: Int, left: Int, resetIn: Long?) =
        Member(number, "claude", "a$number@x.com", Effective(left, resetIn?.let(::m), false), null)

    // --- the share left, and what comes back ------------------------------------

    @Test fun leftIsAShareOfEveryAccount() {
        // The Usage sheet's example: 45% and 82% are 64% of the pair.
        val meter = Meter.of(listOf(session(1, 45, 210), session(2, 82, 290)), Limit.SESSION, now)
        assertEquals(64, meter.left)
        assertEquals(2, meter.segments.size)
    }

    @Test fun theNextResetGivesBackItsAccountsShare() {
        // Account 1 resets first, with 55% used: 27.5% of the pair, shown as 28%.
        val c = Meter.of(listOf(session(1, 45, 210), session(2, 82, 290)), Limit.SESSION, now).comeback!!
        assertEquals(28, c.gain)
        assertEquals(m(210), c.at)
        assertEquals("+28% in 3h 30m", c.text(now))
    }

    @Test fun accountsResettingTogetherAddUp() {
        val c = Meter.comeback(listOf(session(1, 40, 60), session(2, 20, 60), session(3, 100, 30)), Limit.SESSION, now)!!
        assertEquals(47, c.gain) // (60 + 80) / 3
        assertEquals(m(60), c.at)
    }

    @Test fun aFullAccountGivesNothingBackSoTheNextOneCounts() {
        val c = Meter.comeback(listOf(session(1, 100, 10), session(2, 70, 120)), Limit.SESSION, now)!!
        assertEquals(15, c.gain)
        assertEquals(m(120), c.at)
    }

    @Test fun aSliverStillComesBackAsOnePercent() {
        assertEquals(1, Meter.comeback(listOf(session(1, 99, 10), session(2, 100, 10), session(3, 100, 10)), Limit.SESSION, now)!!.gain)
    }

    @Test fun nothingUsedOrNoResetMeansNoComeback() {
        assertNull(Meter.comeback(listOf(session(1, 100, 30)), Limit.SESSION, now))
        assertNull(Meter.comeback(listOf(session(1, 50, null)), Limit.SESSION, now))
    }

    @Test fun anOutFamilyComesBackInFull() {
        val meter = Meter.of(listOf(session(1, 0, 101)), Limit.SESSION, now)
        assertTrue(meter.out)
        assertNull("no pace when there's nothing to spend", meter.pace)
        assertEquals("+100% in 1h 41m", meter.comeback!!.text(now))
    }

    @Test fun unreportedWindowsAreUnknown() {
        val meter = Meter.of(listOf(session(1, 50, 60)), Limit.WEEK, now)
        assertNull(meter.left)
        assertNull(meter.segments.single().left)
    }

    // --- pace ----------------------------------------------------------------

    @Test fun spendingFasterThanTheClockIsAhead() {
        // 3h30m of 5h still to run (70%) but only 45% left.
        assertEquals(PaceMark.AHEAD, Meter.pace(listOf(session(1, 45, 210)), Limit.SESSION, now))
    }

    @Test fun moreLeftThanTimeIsUnder() {
        assertEquals(PaceMark.UNDER, Meter.pace(listOf(session(1, 90, 150)), Limit.SESSION, now))
    }

    @Test fun closeToTheClockIsOnPace() {
        // 50% of the window to run, 53% left: within the slack.
        assertEquals(PaceMark.ON, Meter.pace(listOf(session(1, 53, 150)), Limit.SESSION, now))
    }

    @Test fun paceAveragesTheAccounts() {
        // One 30 points ahead, one 30 under.
        assertEquals(PaceMark.ON, Meter.pace(listOf(session(1, 20, 150), session(2, 80, 150)), Limit.SESSION, now))
    }

    @Test fun noPaceInTheFirstTenMinutesOrWithoutAReset() {
        assertNull(Meter.pace(listOf(session(1, 90, 295)), Limit.SESSION, now))
        assertNull(Meter.pace(listOf(session(1, 90, null)), Limit.SESSION, now))
    }

    @Test fun segmentsCarryLeftAndTimeLeft() {
        val seg = Meter.of(listOf(session(1, 45, 210)), Limit.SESSION, now).segments.single()
        assertEquals(1, seg.number)
        assertEquals(0.45f, seg.left!!, 0.001f)
        assertEquals(0.70f, seg.timeLeft!!, 0.001f)
    }

    // --- segment widths -------------------------------------------------------

    @Test fun segmentsAreEqualAndGapped() {
        val s = segmentSpans(208f, 2, 8f)
        assertEquals(0f, s[0].start, 0.001f)
        assertEquals(100f, s[0].endInclusive, 0.001f)
        assertEquals(108f, s[1].start, 0.001f)
        assertEquals(208f, s[1].endInclusive, 0.001f)
    }

    @Test fun oneSegmentFillsTheBar() {
        val s = segmentSpans(300f, 1, 8f).single()
        assertEquals(0f, s.start, 0.001f)
        assertEquals(300f, s.endInclusive, 0.001f)
    }

    @Test fun manySegmentsStillFitAndGapsShrinkBeforeSegmentsVanish() {
        val s = segmentSpans(100f, 5, 40f)
        assertEquals(100f, s.last().endInclusive, 0.001f)
        s.forEach { assertTrue(it.endInclusive - it.start > 0f) }
        assertTrue(segmentSpans(100f, 0, 4f).isEmpty())
    }

    // --- building a family -------------------------------------------------------

    @Test fun aPoolNumbersItsMembersInOrder() {
        val pool = Pool(
            PoolFamily.CLAUDE, 2, 200, 0.0, 0.0, 0.0,
            listOf(
                PoolMember("claude", "a@x.com", LimitWindow(30, 70, m(60), 300), LimitWindow(40, 60, m(3000), 10080), null),
                PoolMember("claude2", null, LimitWindow(50, 50, now.minusSeconds(5), 300), null, "hub read failed"),
            ),
            now, null,
        )
        val v = FamilyView.of(pool, 0L, false, now)
        assertEquals(listOf(1, 2), v.members.map { it.number })
        assertEquals("a@x.com", v.members[0].name)
        assertEquals("Claude 2", v.members[1].name)
        assertEquals("hub read failed", v.members[1].error)
        assertEquals("a reset that passed offline is full", 100, v.members[1].session!!.remaining)
    }

    @Test fun withoutAPoolTheSnapshotsMakeTheFamily() {
        fun snap(p: UsageProvider, w: LimitWindow?, err: String? = null) = Snapshot(w, w, 0.0, 0.0, 0.0, "h", provider = p, sourceError = err)
        val v = FamilyView.of(PoolFamily.CLAUDE, listOf(snap(UsageProvider.CLAUDE, LimitWindow(20, 80, m(60))), snap(UsageProvider.CLAUDE2, null, "not signed in")), 0L, false, now)
        assertEquals(1, v.members.size)
        assertNull(v.error)
        val none = FamilyView.of(PoolFamily.CLAUDE, listOf(null, snap(UsageProvider.CLAUDE2, null, "not signed in")), 0L, false, now)
        assertTrue(none.members.isEmpty())
        assertEquals("not signed in", none.error)
    }
}
