package com.jaikhurana.aiusagewear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class PlanTest {
    private val now = Instant.parse("2026-09-23T12:00:00Z")
    private fun at(minutes: Long) = now.plus(Duration.ofMinutes(minutes))
    private fun win(remaining: Int, resetInMinutes: Long?) =
        LimitWindow(100 - remaining, remaining, resetInMinutes?.let(::at))
    private fun snap(five: LimitWindow?, week: LimitWindow?) =
        Snapshot(five, week, 0.0, 0.0, 0.0, "host")

    @Test fun comebackIsTheLaterResetAmongExhaustedWindows() {
        val s = snap(win(0, 60), win(0, 3000))
        assertEquals(at(3000), Plan.comeback(s, now))
    }

    @Test fun weeklyOutMeansTheSessionResetDoesNotHelp() {
        val s = snap(win(40, 60), win(0, 3000))
        assertEquals(at(3000), Plan.comeback(s, now))
    }

    @Test fun noComebackWhileThereIsHeadroom() {
        assertNull(Plan.comeback(snap(win(5, 60), win(50, 3000)), now))
    }

    @Test fun aResetThatPassedOfflineReadsAsFullButUnconfirmed() {
        val e = win(0, -10).effective(now)
        assertEquals(100, e.remaining)
        assertTrue(e.unconfirmedReset)
        assertNull(Plan.comeback(snap(win(0, -10), null), now))
    }

    @Test fun plentyLeftChecksEveryHalfHour() {
        assertEquals(Duration.ofMinutes(30), Plan.nextCheck(snap(win(80, 240), null), now, 0))
    }

    @Test fun aResetSoonerThanTheIntervalIsCheckedJustAfter() {
        assertEquals(Duration.ofMinutes(11), Plan.nextCheck(snap(win(80, 10), null), now, 0))
    }

    @Test fun cadenceTightensAsHeadroomShrinks() {
        assertEquals(Duration.ofMinutes(15), Plan.nextCheck(snap(win(50, 290), null), now, 0))
        assertEquals(Duration.ofMinutes(15), Plan.nextCheck(snap(win(10, 290), null), now, 0))
    }

    @Test fun exhaustedStopsPollingUntilTheComeback() {
        assertEquals(Duration.ofMinutes(121), Plan.nextCheck(snap(win(0, 120), null), now, 0))
    }

    @Test fun failuresBackOffToAnHour() {
        assertEquals(Duration.ofMinutes(15), Plan.nextCheck(null, now, 1))
        assertEquals(Duration.ofMinutes(30), Plan.nextCheck(null, now, 2))
        assertEquals(Duration.ofMinutes(60), Plan.nextCheck(null, now, 9))
    }

    @Test fun addressesGetTheRightScheme() {
        assertEquals("http://192.168.0.19:8765", normalizeAddress("192.168.0.19:8765").getOrThrow())
        assertEquals("http://pop-os.local:8765", normalizeAddress("pop-os.local:8765/").getOrThrow())
        assertEquals("https://usage.example.com", normalizeAddress("usage.example.com").getOrThrow())
        assertEquals("https://usage.example.com", normalizeAddress("https://usage.example.com/").getOrThrow())
        assertTrue(normalizeAddress("http://usage.example.com").isFailure)
        assertTrue(normalizeAddress("ftp://x").isFailure)
        assertTrue(normalizeAddress("  ").isFailure)
    }

    @Test fun privateHosts() {
        for (h in listOf("10.0.0.1", "172.20.1.1", "192.168.1.5", "127.0.0.1", "box.local")) assertTrue(h, isPrivateHost(h))
        for (h in listOf("172.32.0.1", "8.8.8.8", "usage.example.com", "999.1.1.1")) assertTrue(h, !isPrivateHost(h))
    }

    @Test fun parsesTheBridgeWireFormat() {
        val s = parseSnapshot(
            """{"schema":1,"five_hour":{"utilization":53,"remaining":47,"resets_at":"2026-09-21T18:20:00Z","resets_in_sec":1},
               "seven_day":null,"cost_today":2.5,"cost_session":1,"cost_week":9,"generated_at":"x","host":"pop-os"}""",
        )
        assertEquals(47, s.fiveHour!!.remaining)
        assertEquals(Instant.parse("2026-09-21T18:20:00Z"), s.fiveHour!!.resetsAt)
        assertNull(s.sevenDay)
        assertEquals("pop-os", s.host)

        val (events, high) = parseEvents(
            """{"schema":1,"events":[{"id":"evt_000000000002","dedupe_key":"five_hour:100:x","kind":"threshold_crossed",
               "window":"five_hour","threshold":100,"severity":"critical"}],"high_water":"evt_000000000002"}""",
        )
        assertEquals(100, events.single().threshold)
        assertEquals("evt_000000000002", high)
    }
}
