package com.jaikhurana.aiusagewear

import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ProviderTest {
    @Test fun codexQuotasCarryTheirOwnDurationAndAvailability() {
        val s = parseSnapshot("""{"provider":"codex","five_hour":{"utilization":25,"remaining":75,"window_minutes":15},"seven_day":null,"cost_available":false}""")
        assertEquals(UsageProvider.CODEX, s.provider)
        assertFalse(s.costAvailable)
        assertEquals("15m", windowLabel("five_hour", s))
        assertNull(s.sevenDay)
    }

    @Test fun anUnavailableSourceRetriesBeforeAnExhaustedWindowResets() {
        val now = Instant.parse("2026-10-03T12:00:00Z")
        val s = Snapshot(LimitWindow(100, 0, now.plus(Duration.ofDays(3))), null, 0.0, 0.0, 0.0, "",
            provider = UsageProvider.CODEX, sourceError = "Source unavailable")
        assertEquals(Duration.ofMinutes(30), Plan.nextCheck(s, now, 0))
    }

    @Test fun olderBridgesStillDefaultToClaude() {
        val s = parseSnapshot("""{"five_hour":null,"seven_day":null}""")
        assertEquals(UsageProvider.CLAUDE, s.provider)
        assertTrue(s.costAvailable)
    }
}
