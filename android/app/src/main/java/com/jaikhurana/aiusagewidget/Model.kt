package com.jaikhurana.aiusagewidget

import org.json.JSONObject
import java.time.Instant

/** One plan-limit window as the bridge reports it. */
data class LimitWindow(val utilization: Int, val remaining: Int, val resetsAt: Instant?)

/** GET /usage. The wire contract is the Endpoints table in AIusageBar's README. */
data class Snapshot(
    val fiveHour: LimitWindow?,
    val sevenDay: LimitWindow?,
    val costToday: Double,
    val costSession: Double,
    val costWeek: Double,
    val host: String,
    /** When Claude Code last fetched these numbers: their true age. Older bridges omit it. */
    val cacheFetchedAt: Instant? = null,
)

/** One entry from GET /events. The bridge decides alerts; we only render them. */
data class UsageEvent(
    val id: String,
    val dedupeKey: String,
    val kind: String,
    val window: String,
    val threshold: Int,
)

fun parseSnapshot(json: String): Snapshot {
    val o = JSONObject(json)
    return Snapshot(
        fiveHour = o.optJSONObject("five_hour")?.let(::parseWindow),
        sevenDay = o.optJSONObject("seven_day")?.let(::parseWindow),
        costToday = o.optDouble("cost_today", 0.0),
        costSession = o.optDouble("cost_session", 0.0),
        costWeek = o.optDouble("cost_week", 0.0),
        host = o.optString("host"),
        cacheFetchedAt = runCatching { Instant.parse(o.optString("cache_fetched_at")) }.getOrNull(),
    )
}

private fun parseWindow(o: JSONObject): LimitWindow {
    val util = o.optInt("utilization")
    return LimitWindow(
        utilization = util,
        remaining = if (o.has("remaining")) o.optInt("remaining") else (100 - util).coerceAtLeast(0),
        resetsAt = runCatching { Instant.parse(o.optString("resets_at")) }.getOrNull(),
    )
}

/** The events page, plus the high-water mark to send back as ?since= next time. */
fun parseEvents(json: String): Pair<List<UsageEvent>, String?> {
    val o = JSONObject(json)
    val arr = o.optJSONArray("events")
    val events = (0 until (arr?.length() ?: 0)).map { i ->
        val e = arr!!.getJSONObject(i)
        UsageEvent(
            id = e.optString("id"),
            dedupeKey = e.optString("dedupe_key"),
            kind = e.optString("kind"),
            window = e.optString("window"),
            threshold = e.optInt("threshold"),
        )
    }
    return events to o.optString("high_water").takeIf { it.isNotEmpty() }
}
