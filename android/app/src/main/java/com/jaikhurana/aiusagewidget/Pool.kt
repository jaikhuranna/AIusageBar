package com.jaikhurana.aiusagewidget

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * A provider family: every account whose limits pool together. Claude is the
 * hub's 1..N accounts; Codex is a pool of one. `GET /pool?family=` answers
 * for one family.
 */
enum class PoolFamily(val wire: String, val title: String, val providers: List<UsageProvider>) {
    CLAUDE("claude", "Claude", listOf(UsageProvider.CLAUDE, UsageProvider.CLAUDE2)),
    CODEX("codex", "Codex", listOf(UsageProvider.CODEX));

    /** The pool is drawn in the family's first provider's colour. */
    val accentProvider: UsageProvider get() = providers.first()

    companion object {
        fun of(provider: UsageProvider) = entries.first { provider in it.providers }
    }
}

data class PoolMember(
    val provider: String,
    val account: String?,
    val fiveHour: LimitWindow?,
    val sevenDay: LimitWindow?,
    val sourceError: String?,
) {
    /** What this account can spend right now: the tighter of its two windows. Null with no data. */
    fun effective(now: Instant): Int? {
        if (fiveHour == null && sevenDay == null) return null
        return minOf(fiveHour?.effective(now)?.remaining ?: 100, sevenDay?.effective(now)?.remaining ?: 100)
    }

    /** When an exhausted account can spend again: the weekly reset if that's out, else the 5h one. */
    fun comeback(now: Instant): Instant? {
        val week = sevenDay?.effective(now)
        val session = fiveHour?.effective(now)
        return when {
            week != null && week.remaining <= 0 -> week.resetsAt
            session != null && session.remaining <= 0 -> session.resetsAt
            else -> null
        }
    }
}

/**
 * GET /pool. The wire contract is the Endpoints table in AIusageBar's README.
 * Of the history only the five-hour numbers are parsed (the card's session
 * graph); the projection and burn figures aren't drawn, so they aren't parsed.
 */
data class Pool(
    val family: PoolFamily,
    val accounts: Int,
    val capacity: Int,
    /** Σ weekly left. */
    val remaining: Double,
    val fiveHourRemaining: Double,
    /** Σ per account of the tighter window: what can be spent right now. */
    val effectiveRemaining: Double,
    val members: List<PoolMember>,
    val generatedAt: Instant?,
    val sourceError: String?,
    /** Oldest first. */
    val history: List<SessionSample> = emptyList(),
)

fun parsePool(json: String): Pool {
    val o = JSONObject(json)
    fun num(key: String) = if (!o.has(key) || o.isNull(key)) null else o.optDouble(key).takeIf { !it.isNaN() }
    val members: JSONArray? = o.optJSONArray("members")
    val family = PoolFamily.entries.firstOrNull { it.wire == o.optString("family") } ?: PoolFamily.CLAUDE
    val accounts = o.optInt("accounts", 0).coerceAtLeast(0)
    return Pool(
        family = family,
        accounts = accounts,
        capacity = o.optInt("capacity_pct", accounts * 100).coerceAtLeast(0),
        remaining = num("remaining_pct") ?: 0.0,
        fiveHourRemaining = num("five_hour_remaining_pct") ?: 0.0,
        effectiveRemaining = num("effective_remaining_pct") ?: 0.0,
        members = (0 until (members?.length() ?: 0)).mapNotNull { members!!.optJSONObject(it) }.map { m ->
            PoolMember(
                provider = m.optString("provider"),
                account = m.optString("account").takeIf { it.isNotBlank() },
                fiveHour = m.optJSONObject("five_hour")?.let(::parseWindow),
                sevenDay = m.optJSONObject("seven_day")?.let(::parseWindow),
                sourceError = m.optString("source_error").takeIf { it.isNotBlank() },
            )
        },
        generatedAt = runCatching { Instant.parse(o.optString("generated_at")) }.getOrNull(),
        sourceError = o.optString("source_error").takeIf { it.isNotBlank() },
        history = parseHistory(o.optJSONArray("history")),
    )
}

/** `history[]`'s five-hour numbers. A sample without a time is skipped; older bridges send no per-account maps. */
private fun parseHistory(a: JSONArray?): List<SessionSample> =
    (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }.mapNotNull { h ->
        val t = h.optLong("t", 0L).takeIf { it > 0 } ?: return@mapNotNull null
        val left = h.optJSONObject("five_hour_remaining")
        val resets = h.optJSONObject("five_hour_resets_at")
        SessionSample(
            Instant.ofEpochSecond(t),
            h.optDouble("five_hour_remaining_pct", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            left?.keys()?.asSequence()?.mapNotNull { k -> left.optDouble(k).takeIf { !it.isNaN() }?.let { k to it } }?.toMap().orEmpty(),
            resets?.keys()?.asSequence()?.mapNotNull { k -> resets.optLong(k, 0L).takeIf { it > 0 }?.let { k to Instant.ofEpochSecond(it) } }?.toMap().orEmpty(),
        )
    }.sortedBy { it.t }

/**
 * The 1x1's ring for a whole family, in the original ring's terms: the outer
 * ring is what every account together can spend right now (each account's
 * tighter window, summed), the pie the pooled week, both as a share of the
 * family's capacity. With one account that is the account's own ring. Pure,
 * like [Look.of], whose modes it reuses:
 *
 * - every account's week out → WEEK_OUT, counting down to the first weekly reset;
 * - nothing spendable anywhere → SESSION_OUT, counting down to the first comeback;
 * - a family account came back within the hour ([backAts]) and the pool is
 *   at least half full → GO.
 *
 * Null when there is no pool to draw (an older bridge, or none fetched yet):
 * the widget then draws the selected provider's own ring, as before `/pool`.
 */
fun poolRing(pool: Pool?, fetchedAt: Long, offline: Boolean, backAts: List<Long>, now: Instant): Look? {
    pool ?: return null
    val provider = pool.family.accentProvider
    val waiting = Look(Mode.WAITING, now, null, null, fetchedAt, offline, provider)
    if (pool.accounts == 0) return waiting
    val known = pool.members.filter { it.fiveHour != null || it.sevenDay != null }
    if (known.isEmpty() && pool.members.isNotEmpty()) return waiting
    val capacity = (if (known.isNotEmpty()) known.size * 100 else pool.capacity).coerceAtLeast(1)

    val spendable = if (known.isEmpty()) pool.effectiveRemaining else known.sumOf { it.effective(now) ?: 0 }.toDouble()
    val week = if (known.isEmpty()) pool.remaining
    else known.sumOf { (it.sevenDay ?: it.fiveHour)!!.effective(now).remaining }.toDouble()
    fun share(v: Double) = (v * 100 / capacity).roundToInt().coerceIn(0, 100)
    // Something left must never round to an empty ring, nor a nearly empty one to a full one.
    fun visible(v: Double) = share(v).let { if (v > 0 && it == 0) 1 else if (v < capacity && it == 100) 99 else it }

    val weekOut = known.isNotEmpty() && known.all { (it.sevenDay ?: it.fiveHour)!!.effective(now).remaining <= 0 }
    val firstWeekBack = known.mapNotNull { it.sevenDay?.effective(now)?.resetsAt }.minOrNull()
    val firstBack = known.mapNotNull { it.comeback(now) }.minOrNull()
    val sessionLength = known.firstNotNullOfOrNull { it.fiveHour?.windowMinutes }?.let { Duration.ofMinutes(it.toLong()) } ?: Look.SESSION
    val weekLength = known.firstNotNullOfOrNull { it.sevenDay?.windowMinutes }?.let { Duration.ofMinutes(it.toLong()) } ?: Look.WEEK

    val session = Effective(visible(spendable), if (spendable <= 0) firstBack else null, false)
    val weekly = Effective(visible(week), firstWeekBack, false)
    val cameBack = backAts.any { it != 0L && !now.isBefore(Instant.ofEpochMilli(it)) && now.isBefore(Instant.ofEpochMilli(it).plus(Look.GO_FOR)) }
    val mode = when {
        weekOut -> Mode.WEEK_OUT
        spendable <= 0 -> Mode.SESSION_OUT
        cameBack && session.remaining >= Look.GO_WHILE_LEFT -> Mode.GO
        else -> Mode.NORMAL
    }
    return Look(mode, now, session, weekly, fetchedAt, offline, provider, sessionLength, weekLength)
}
