package com.jaikhurana.aiusagewidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class SessionChartTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private fun m(minutes: Long) = now.plus(Duration.ofMinutes(minutes))

    private fun member(number: Int, provider: String, left: Int, reset: Instant?) =
        Member(number, provider, null, Effective(left, reset, false), Effective(50, m(3000), false))

    private fun sample(minutes: Long, vararg accounts: Triple<String, Double, Instant?>) = SessionSample(
        m(minutes), accounts.sumOf { it.second },
        accounts.associate { it.first to it.second },
        accounts.mapNotNull { a -> a.third?.let { a.first to it } }.toMap(),
    )

    private fun assertPoints(expected: List<Pair<Instant, Float>>, actual: List<ChartPoint>) {
        assertEquals(expected.map { it.first }, actual.map { it.t })
        expected.zip(actual).forEach { (e, a) -> assertEquals(e.second, a.left, 1e-4f) }
    }

    // --- the line and its resets --------------------------------------------

    @Test fun aRecordedResetStepsTheLineUpByWhatItGaveBack() {
        val reset = m(-120)
        val history = listOf(
            sample(-200, Triple("claude", 40.0, reset)),
            sample(-130, Triple("claude", 10.0, reset)),
            sample(-100, Triple("claude", 95.0, m(180))),
        )
        val chart = SessionChart.of(listOf(member(1, "claude", 80, m(180))), history, now)
        assertEquals(listOf(ResetMark(reset, 0, false, 0.9f), ResetMark(m(180), 0, true, 0.2f)), chart.resets)
        // ... 10% at -130, holds to the reset, up to full at it, then 95% at -100, and the live number now.
        assertPoints(listOf(m(-200) to 0.4f, m(-130) to 0.1f, reset to 0.1f, reset to 1f, m(-100) to 0.95f, now to 0.8f), chart.line)
        assertEquals(0.8f, chart.current!!, 1e-4f)
    }

    @Test fun aResetSinceTheLastSampleIsMarkedAtItsRecordedTime() {
        val reset = m(-10)
        val chart = SessionChart.of(
            listOf(member(1, "codex", 100, null)),
            listOf(sample(-30, Triple("codex", 5.0, reset))),
            now,
        )
        assertEquals(listOf(ResetMark(reset, 0, false, 0.95f)), chart.resets)
        // Nothing to come: the projection just holds.
        assertPoints(listOf(now to 1f, chart.to to 1f), chart.projection)
    }

    @Test fun withoutRecordedResetsAJumpIsAReset() {
        // An older bridge: the pooled sum only, for a family of one.
        val history = listOf(
            SessionSample(m(-300), 30.0), SessionSample(m(-240), 20.0),
            SessionSample(m(-180), 90.0), // +70: a reset, marked where it was seen
            SessionSample(m(-120), 95.0), // +5: just noise in the numbers, not a reset
        )
        val chart = SessionChart.of(listOf(member(1, "codex", 85, m(100))), history, now)
        assertEquals(listOf(ResetMark(m(-180), 0, false, 0.7f), ResetMark(m(100), 0, true, 0.15f)), chart.resets)
    }

    @Test fun resetsOutsideTheWindowAreDroppedAndRepeatsMerged() {
        val old = m(-13 * 60)
        val history = listOf(
            sample(-14 * 60, Triple("claude", 10.0, old)),
            sample(-12 * 60 - 30, Triple("claude", 100.0, m(-60))),
            sample(-70, Triple("claude", 50.0, m(-60))),
            sample(-50, Triple("claude", 100.0, m(-59))), // the same reset, a minute off
            sample(-40, Triple("claude", 90.0, m(240))),
        )
        val chart = SessionChart.of(listOf(member(1, "claude", 90, m(240))), history, now)
        assertEquals(listOf(m(-60), m(240)), chart.resets.map { it.at })
    }

    @Test fun severalAccountsAreOneLineWithEachResetInItsAccountsColour() {
        val history = listOf(
            sample(-90, Triple("claude", 20.0, m(-60)), Triple("claude2", 70.0, m(30))),
            sample(-30, Triple("claude", 100.0, m(240)), Triple("claude2", 60.0, m(30))),
        )
        val chart = SessionChart.of(listOf(member(1, "claude", 90, m(240)), member(2, "claude2", 55, m(30))), history, now)
        // The family's share, as the card's big number: (90 + 55) / 2 now.
        assertPoints(listOf(m(-90) to 0.45f, m(-60) to 0.45f, m(-60) to 0.85f, m(-30) to 0.8f, now to 0.725f), chart.line)
        assertEquals(
            listOf(ResetMark(m(-60), 0, false, 0.4f), ResetMark(m(30), 1, true, 0.225f), ResetMark(m(240), 0, true, 0.05f)),
            chart.resets,
        )
        // Ahead: holds, then steps up at each account's reset.
        assertPoints(listOf(now to 0.725f, m(30) to 0.725f, m(30) to 0.95f, m(240) to 0.95f, m(240) to 1f, chart.to to 1f), chart.projection)
    }

    @Test fun olderSamplesOfSeveralAccountsUseThePooledSum() {
        val history = listOf(SessionSample(m(-300), 120.0), SessionSample(m(-240), 100.0), sample(-60, Triple("claude", 50.0, null), Triple("claude2", 40.0, null)))
        val chart = SessionChart.of(listOf(member(1, "claude", 45, null), member(2, "claude2", 40, null)), history, now)
        assertPoints(listOf(m(-300) to 0.6f, m(-240) to 0.5f, m(-60) to 0.45f, now to 0.425f), chart.line)
    }

    @Test fun aJumpInThePooledSumOfSeveralCantSayWhose() {
        val history = listOf(SessionSample(m(-300), 40.0), SessionSample(m(-240), 140.0))
        val chart = SessionChart.of(listOf(member(1, "claude", 70, null), member(2, "claude2", 70, null)), history, now)
        assertEquals(listOf(ResetMark(m(-240), SessionChart.UNKNOWN, false, 0.5f)), chart.resets)
    }

    @Test fun aLongSilenceBreaksTheLine() {
        val history = listOf(sample(-600, Triple("codex", 50.0, null)), sample(-300, Triple("codex", 50.0, null)))
        val pts = SessionChart.of(listOf(member(1, "codex", 50, null)), history, now).line
        assertEquals(listOf(false, true, true), pts.map { it.penUp })
    }

    @Test fun historyCrossingTheLeftEdgeStartsAtTheInterpolatedValue() {
        val history = listOf(SessionSample(m(-750), 80.0), SessionSample(m(-690), 60.0))
        val chart = SessionChart.of(listOf(member(1, "codex", 50, null)), history, now)
        assertEquals(chart.from, chart.line.first().t)
        assertEquals(0.7f, chart.line.first().left, 1e-4f)
        assertTrue(chart.line.none { it.t.isBefore(chart.from) })
    }

    @Test fun aRecordingGapAcrossTheLeftEdgeIsNotFilledIn() {
        val history = listOf(SessionSample(m(-900), 80.0), SessionSample(m(-600), 60.0))
        val chart = SessionChart.of(listOf(member(1, "codex", 50, null)), history, now)
        assertEquals(m(-600), chart.line.first().t)
        assertTrue(chart.line.first().penUp)
    }

    @Test fun noNumbersNoLine() {
        val chart = SessionChart.of(emptyList(), emptyList(), now)
        assertTrue(chart.line.isEmpty() && chart.projection.isEmpty())
        assertNull(chart.current)
    }

    // --- scaling ------------------------------------------------------------

    @Test fun theRangeIsTwelveHoursBackToJustPastTheLastReset() {
        val chart = SessionChart.of(listOf(member(1, "claude", 50, m(120)), member(2, "claude2", 50, m(270))), emptyList(), now)
        assertEquals(now.minus(Duration.ofHours(12)), chart.from)
        assertTrue(chart.to.isAfter(m(270)) && chart.to.isBefore(m(270 + 40)))
        // Nothing upcoming: still some future, so NOW isn't on the edge.
        val idle = SessionChart.of(listOf(member(1, "codex", 100, null)), emptyList(), now)
        assertTrue(idle.to.isAfter(m(44)))
    }

    @Test fun xAndYScale() {
        val chart = SessionChart(m(-600), now, m(400), emptyList(), emptyList(), emptyList())
        assertEquals(0f, chart.x(m(-600), 500f), 1e-3f)
        assertEquals(300f, chart.x(now, 500f), 1e-3f)
        assertEquals(500f, chart.x(m(400), 500f), 1e-3f)
        assertEquals(10f, chart.y(1f, 10f, 60f), 1e-3f)
        assertEquals(70f, chart.y(0f, 10f, 60f), 1e-3f)
        assertEquals(25f, chart.y(0.75f, 10f, 60f), 1e-3f)
        assertEquals(70f, chart.y(-0.2f, 10f, 60f), 1e-3f) // clamped
    }

    // --- labels -------------------------------------------------------------

    @Test fun labelsKeepPriorityDropOverlapsAndStayInside() {
        val lefts = placeLabels(listOf(100f to 20f, 112f to 20f, 140f to 20f, 2f to 20f, 299f to 20f), 300f, 4f)
        assertEquals(listOf(90f, null, 130f, 0f, 280f), lefts)
    }

    @Test fun aCrowdedLabelMovesJustClearWhenThatsCloseEnough() {
        // 116 would overlap 100's label; 8 px over to clear it is near enough, 13 px isn't.
        assertEquals(listOf(90f, 114f), placeLabels(listOf(100f to 20f, 116f to 20f), 300f, 4f, nudge = 10f))
        assertEquals(listOf(90f, null), placeLabels(listOf(100f to 20f, 101f to 20f), 300f, 4f, nudge = 10f))
        // Never out past the end.
        assertEquals(listOf(270f, 246f), placeLabels(listOf(290f to 30f, 295f to 20f), 300f, 4f, nudge = 40f))
    }
}
