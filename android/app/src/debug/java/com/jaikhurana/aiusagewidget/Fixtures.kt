package com.jaikhurana.aiusagewidget

import android.content.Context
import java.time.Duration
import java.time.Instant

/**
 * Debug builds only (release has a stub): made-up pools that follow the
 * `/pool` contract, for judging the card and the app without live accounts.
 * Three Claude accounts (one out for the session) and three Codex ones (the first out for the week):
 *
 *     adb shell am start -n com.jaikhurana.aiusagewidget/.CardActivity --ez fixture true
 *     adb shell am start -n com.jaikhurana.aiusagewidget/.MainActivity --ez fixture true
 */
object Fixtures {
    private const val KEY = "debug_fixture"

    /** Whether the widgets draw the fixture too: set by opening the app with the fixture extra. */
    fun widgets(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences("aiusage", Context.MODE_PRIVATE).getBoolean(KEY, false)

    /** Opening the app turns it on with the extra and off without; true when that changed it. */
    fun setWidgets(ctx: Context, on: Boolean): Boolean {
        if (widgets(ctx) == on) return false
        ctx.applicationContext.getSharedPreferences("aiusage", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        return true
    }

    /** [ended] is how much each earlier five-hour window had left when it reset, newest first. */
    private class Account(val email: String?, val session: Int, val week: Int, val sessionIn: Long, val weekIn: Long, val ended: List<Int> = emptyList())

    fun pool(family: PoolFamily, now: Instant): Pool? = when (family) {
        PoolFamily.CLAUDE -> build(
            family, now,
            Account("jai.khurana.longname+work@example-company.com", 45, 60, 210, 51 * 60, listOf(12, 40)),
            Account("second@example.com", 82, 71, 290, 129 * 60, listOf(30, 0, 65)),
            Account("third@example.com", 0, 38, 41, 20 * 60, listOf(0, 22)),
        )
        // Three subscriptions, so the orbit's inner ring has a twist to show.
        PoolFamily.CODEX -> build(
            family, now,
            Account(null, 100, 0, 300, 76 * 60, listOf(0, 18)),
            Account("codex-team@example.com", 64, 52, 175, 98 * 60, listOf(40, 70)),
            Account("codex-side@example.com", 90, 81, 250, 140 * 60, listOf(85)),
        )
    }

    private fun build(family: PoolFamily, now: Instant, vararg accounts: Account): Pool {
        fun at(min: Long) = now.plus(Duration.ofMinutes(min))
        val members = accounts.mapIndexed { i, a ->
            PoolMember(
                provider = if (i == 0) family.wire else "${family.wire}${i + 1}",
                account = a.email,
                fiveHour = LimitWindow(100 - a.session, a.session, at(a.sessionIn), 300),
                sevenDay = LimitWindow(100 - a.week, a.week, at(a.weekIn), 10080),
                sourceError = null,
            )
        }
        return Pool(
            history = history(members, accounts.toList(), now),
            family = family,
            accounts = accounts.size,
            capacity = accounts.size * 100,
            remaining = accounts.sumOf { it.week }.toDouble(),
            fiveHourRemaining = accounts.sumOf { it.session }.toDouble(),
            effectiveRemaining = accounts.sumOf { minOf(it.week, it.session) }.toDouble(),
            members = members,
            generatedAt = now,
            sourceError = null,
        )
    }

    /**
     * Twelve hours of samples every 10 min: each account's windows back to
     * back, 5h apart, ending at its next reset; each drains from full to what
     * [Account.ended] says (the current one to [Account.session]), then resets.
     * An account with nothing recorded for a window sits idle at full.
     */
    private fun history(members: List<PoolMember>, accounts: List<Account>, now: Instant): List<SessionSample> {
        val window = Duration.ofHours(5)
        return (72 downTo 1).map { k ->
            val t = now.minus(Duration.ofMinutes(10L * k))
            val left = mutableMapOf<String, Double>()
            val resets = mutableMapOf<String, Instant>()
            members.zip(accounts).forEach { (m, a) ->
                var reset = m.fiveHour!!.resetsAt!!
                var n = 0
                while (reset.minus(window).isAfter(t)) { reset = reset.minus(window); n++ }
                val end = if (n == 0) a.session else a.ended.getOrNull(n - 1) ?: 100
                val start = reset.minus(window)
                val until = if (n == 0) now else reset
                val f = (Duration.between(start, t).seconds.toDouble() / Duration.between(start, until).seconds).coerceIn(0.0, 1.0)
                // Spend early, then hold: a limit hit stays at 0 until the reset.
                left[m.provider] = 100 - (100 - end) * minOf(1.0, f * 1.6)
                resets[m.provider] = reset
            }
            SessionSample(t, left.values.sum(), left, resets)
        }
    }
}
