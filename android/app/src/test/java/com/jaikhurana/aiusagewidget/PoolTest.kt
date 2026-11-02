package com.jaikhurana.aiusagewidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class PoolTest {
    private val now = Instant.parse("2026-10-30T12:00:00Z")
    private val fetched = now.minusSeconds(60).toEpochMilli()
    private fun h(hours: Long) = now.plus(Duration.ofHours(hours))

    private fun member(session: Int?, week: Int?, sessionReset: Instant? = h(2), weekReset: Instant? = h(50), provider: String = "claude") =
        PoolMember(
            provider, "a@x.com",
            session?.let { LimitWindow(100 - it, it, sessionReset, 300) },
            week?.let { LimitWindow(100 - it, it, weekReset, 10080) },
            null,
        )

    private fun pool(vararg members: PoolMember, accounts: Int = members.size, sourceError: String? = null) =
        Pool(PoolFamily.CLAUDE, accounts, accounts * 100, 0.0, 0.0, 0.0, members.toList(), now, sourceError)

    private fun ring(p: Pool?, offline: Boolean = false, backAts: List<Long> = emptyList()) = poolRing(p, fetched, offline, backAts, now)

    // --- parsing -----------------------------------------------------------

    @Test fun parsesTheContractExample() {
        val json = """
            {"family":"claude","accounts":2,"capacity_pct":200,"remaining_pct":131.0,
             "five_hour_remaining_pct":160.0,"effective_remaining_pct":118.0,"burn_pct_per_hour":3.7,
             "next_reset":{"at":"2026-10-31T10:00:00Z","gain_pct":100.0},
             "first_empty_at":"2026-10-31T03:49:00Z","pool_empty_at":null,
             "members":[
               {"provider":"claude","account":"a@x.com","five_hour":{"window_minutes":300,"utilization":30,"remaining":70,"resets_at":"2026-10-30T14:00:00Z"},
                "seven_day":{"window_minutes":10080,"utilization":40,"remaining":60,"resets_at":"2026-11-01T14:00:00Z"},"source_error":""},
               {"provider":"claude2","account":"b@x.com","five_hour":null,"seven_day":null,"source_error":"no token"}],
             "history":[{"t":1793390000,"remaining_pct":140.0}],
             "projection":[{"t":1793400000,"remaining_pct":131.0,"reset":false}],
             "generated_at":"2026-10-30T12:00:00Z","source_error":""}
        """.trimIndent()
        val p = parsePool(json)
        assertEquals(PoolFamily.CLAUDE, p.family)
        assertEquals(2, p.accounts)
        assertEquals(200, p.capacity)
        assertEquals(131.0, p.remaining, 0.0)
        assertEquals(118.0, p.effectiveRemaining, 0.0)
        assertEquals(60, p.members[0].sevenDay!!.remaining)
        assertEquals("no token", p.members[1].sourceError)
        assertNull(p.members[1].fiveHour)
        assertNull(p.sourceError)
        assertEquals(now, p.generatedAt)
    }

    @Test fun parsesTheFiveHourHistory() {
        val p = parsePool("""{"family":"claude","accounts":2,"members":[],"history":[
            {"t":1793390600,"remaining_pct":140,"five_hour_remaining_pct":150,"effective_remaining_pct":120,
             "five_hour_remaining":{"claude":60,"claude2":90},"five_hour_resets_at":{"claude":1793400000}},
            {"t":1793390000,"remaining_pct":140,"five_hour_remaining_pct":170,"effective_remaining_pct":125},
            {"remaining_pct":1}]}""")
        // Oldest first; a sample without a time is skipped; an older one has no per-account maps.
        assertEquals(listOf(1793390000L, 1793390600L), p.history.map { it.t.epochSecond })
        assertEquals(170.0, p.history[0].pooled, 0.0)
        assertTrue(p.history[0].left.isEmpty())
        assertEquals(mapOf("claude" to 60.0, "claude2" to 90.0), p.history[1].left)
        assertEquals(mapOf("claude" to Instant.ofEpochSecond(1793400000)), p.history[1].resetsAt)
    }

    @Test fun parsesNullsAndAnEmptyFamily() {
        val p = parsePool("""{"family":"codex","accounts":0,"capacity_pct":0,"burn_pct_per_hour":null,"next_reset":null,"members":[],"history":[],"projection":[],"source_error":"no accounts"}""")
        assertEquals(PoolFamily.CODEX, p.family)
        assertEquals(0, p.accounts)
        assertTrue(p.members.isEmpty())
        assertEquals("no accounts", p.sourceError)
    }

    @Test fun parsesTheServersRealCodexSample() {
        // Trimmed from pool-samples/pool-codex.json: weekly out, so nothing is spendable.
        val p = parsePool("""{"family":"codex","accounts":1,"capacity_pct":100,"remaining_pct":0,"five_hour_remaining_pct":100,
            "effective_remaining_pct":0,"burn_pct_per_hour":null,"next_reset":{"at":"2026-11-04T07:53:23Z","gain_pct":100},
            "first_empty_at":null,"pool_empty_at":"2026-10-30T12:00:00Z",
            "members":[{"provider":"codex","account":"","five_hour":{"window_minutes":300,"utilization":0,"remaining":100,"resets_at":"2026-10-30T17:00:00Z","resets_in_sec":1},
              "seven_day":{"window_minutes":10080,"utilization":100,"remaining":0,"resets_at":"2026-11-04T07:53:23Z","resets_in_sec":1},"source_error":""}],
            "history":[],"projection":[],"generated_at":"2026-10-30T12:00:00Z","source_error":""}""")
        assertNull(p.members[0].account)
        val l = ring(p)!!
        assertEquals(Mode.WEEK_OUT, l.mode)
        assertEquals(UsageProvider.CODEX, l.provider)
        assertEquals(Instant.parse("2026-11-04T07:53:23Z"), l.countdownTo)
    }

    // --- the pooled 1x1 ring -------------------------------------------------

    @Test fun noPoolFallsBackToTheProviderRing() {
        assertNull(ring(null))
    }

    @Test fun zeroAccountsOrNoDataWaits() {
        assertEquals(Mode.WAITING, ring(pool(accounts = 0, sourceError = "No Claude account is signed in to the hub."))!!.mode)
        assertEquals(Mode.WAITING, ring(pool(member(null, null), sourceError = "hub unreachable"))!!.mode)
    }

    @Test fun oneAccountIsItsOwnRing() {
        // The ring is what can be spent now (the tighter window), the pie the week.
        val l = ring(pool(member(70, 62)))!!
        assertEquals(Mode.NORMAL, l.mode)
        assertEquals(62, l.sessionLeft)
        assertEquals(62, l.weekLeft)
        val roomy = ring(pool(member(40, 90)))!!
        assertEquals(40, roomy.sessionLeft)
        assertEquals(90, roomy.weekLeft)
    }

    @Test fun twoAccountsPoolAsAShareOfBoth() {
        // Spendable: min(70,60) + min(48,71) = 108 of 200; week: 60 + 71 = 131 of 200.
        val l = ring(pool(member(70, 60), member(48, 71)))!!
        assertEquals(54, l.sessionLeft)
        assertEquals(66, l.weekLeft)
    }

    @Test fun anyNumberOfAccounts() {
        val l = ring(pool(member(100, 90), member(10, 80), member(55, 55), member(100, 100)))!!
        assertEquals(64, l.sessionLeft) // 90 + 10 + 55 + 100 = 255 of 400
        assertEquals(81, l.weekLeft) // 325 of 400
    }

    @Test fun anAccountWithoutDataIsLeftOut() {
        val l = ring(pool(member(70, 60), member(null, null)))!!
        assertEquals(60, l.sessionLeft)
    }

    @Test fun aSlimRemainderNeverRoundsToAnEmptyOrFullRing() {
        // 1 of 400 is 0.25%: still a sliver; 399 of 400 isn't a closed ring.
        assertEquals(1, ring(pool(member(1, 1), member(0, 50), member(0, 50), member(0, 50)))!!.sessionLeft)
        assertEquals(99, ring(pool(member(100, 100), member(100, 100), member(100, 100), member(99, 99)))!!.sessionLeft)
    }

    @Test fun nothingSpendableCountsDownToTheFirstBack() {
        // One is out for the week (back in 50h), the other only for the session (back in 2h).
        val l = ring(pool(member(40, 0, weekReset = h(50)), member(0, 30, sessionReset = h(2))))!!
        assertEquals(Mode.SESSION_OUT, l.mode)
        assertEquals(h(2), l.countdownTo)
        assertTrue(l.ticking)
    }

    @Test fun everyWeekOutIsWeekOut() {
        val l = ring(pool(member(40, 0, weekReset = h(50)), member(80, 0, weekReset = h(20))))!!
        assertEquals(Mode.WEEK_OUT, l.mode)
        assertEquals(h(20), l.countdownTo)
    }

    @Test fun aComebackIsGo() {
        val back = now.minus(Duration.ofMinutes(10)).toEpochMilli()
        assertEquals(Mode.GO, ring(pool(member(100, 80), member(60, 70)), backAts = listOf(0L, back))!!.mode)
        // ...but not once the pool is under half, nor an hour on.
        assertEquals(Mode.NORMAL, ring(pool(member(30, 80), member(20, 70)), backAts = listOf(back))!!.mode)
        assertEquals(Mode.NORMAL, ring(pool(member(100, 80)), backAts = listOf(now.minus(Duration.ofMinutes(61)).toEpochMilli()))!!.mode)
    }

    @Test fun aResetThatPassedOfflineCountsAsFull() {
        assertEquals(50, ring(pool(member(0, 50, sessionReset = now.minusSeconds(60))))!!.sessionLeft)
    }

    @Test fun offlineOrOldIsStale() {
        assertTrue(ring(pool(member(70, 60)), offline = true)!!.stale)
        assertFalse(ring(pool(member(70, 60)))!!.stale)
        val old = poolRing(pool(member(70, 60)), now.minus(Duration.ofHours(2)).toEpochMilli(), false, emptyList(), now)!!
        assertTrue(old.stale)
    }

    @Test fun familiesCoverEveryProvider() {
        assertEquals(PoolFamily.CLAUDE, PoolFamily.of(UsageProvider.CLAUDE2))
        assertEquals(PoolFamily.CODEX, PoolFamily.of(UsageProvider.CODEX))
        UsageProvider.entries.forEach { PoolFamily.of(it) }
    }
}
