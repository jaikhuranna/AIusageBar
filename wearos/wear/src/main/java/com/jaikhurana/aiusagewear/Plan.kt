package com.jaikhurana.aiusagewear

import java.time.Duration
import java.time.Instant

/**
 * What a window means right now. A reset that passed while the desktop was out
 * of reach reads as full but unconfirmed: after a reset a window really is full
 * until it's used, so the stale pre-reset number would be the wrong answer.
 */
data class Effective(val remaining: Int, val resetsAt: Instant?, val unconfirmedReset: Boolean)

fun LimitWindow.effective(now: Instant): Effective =
    if (resetsAt != null && !resetsAt.isAfter(now)) Effective(100, null, true)
    else Effective(remaining, resetsAt, false)

/** Pure scheduling and countdown rules, shared by every surface. */
object Plan {
    /**
     * The owner's baseline: hourly while there's headroom, to spare the
     * battery. Opening the app always fetches, so this only paces the tile and
     * complications.
     */
    val BASE: Duration = Duration.ofMinutes(60)
    private val BACKOFF_CAP: Duration = Duration.ofMinutes(120)
    private val FLOOR: Duration = Duration.ofMinutes(30)
    private val ONE_MINUTE: Duration = Duration.ofMinutes(1)

    fun windows(s: Snapshot, now: Instant): List<Pair<String, Effective>> =
        listOfNotNull(
            s.fiveHour?.let { "five_hour" to it.effective(now) },
            s.sevenDay?.let { "seven_day" to it.effective(now) },
        )

    /** The window with the least headroom, which is the one that bites first. */
    fun worst(s: Snapshot, now: Instant): Pair<String, Effective>? =
        windows(s, now).minByOrNull { it.second.remaining }

    /**
     * When Claude comes back: the later reset among exhausted windows. If the
     * weekly window is out, the 5h reset gives nothing back. Null when nothing
     * is exhausted, or when an exhausted window has no known reset.
     */
    fun comeback(s: Snapshot, now: Instant): Instant? {
        val exhausted = windows(s, now).map { it.second }.filter { it.remaining <= 0 }
        if (exhausted.isEmpty() || exhausted.any { it.resetsAt == null }) return null
        return exhausted.mapNotNull { it.resetsAt }.max()
    }

    /**
     * How long until the next background check. A reset that passes between
     * checks already shows as full (see [effective]), so resets don't get a
     * wakeup of their own; only the comeback does, via its alarm.
     */
    fun nextCheck(s: Snapshot?, now: Instant, failures: Int): Duration {
        if (failures > 0) {
            // 30, 60, then the 120 min cap.
            val minutes = 30L shl (failures - 1).coerceAtMost(2)
            return Duration.ofMinutes(minutes.coerceAtMost(BACKOFF_CAP.toMinutes()))
        }
        if (s == null) return FLOOR
        // Out of quota: nothing can change until the reset, so don't ask.
        comeback(s, now)?.let { return Duration.between(now, it).plus(ONE_MINUTE) }
        val left = worst(s, now)?.second?.remaining ?: 100
        return if (left > 50) BASE else FLOOR
    }

    /**
     * The reset worth counting down to: the comeback when Claude is out,
     * otherwise the soonest upcoming reset among the windows with the least
     * headroom (the 5h one on a tie, since it comes first).
     */
    fun nextReset(s: Snapshot, now: Instant): Pair<String, Instant>? {
        comeback(s, now)?.let { at ->
            val key = windows(s, now).firstOrNull { it.second.remaining <= 0 && it.second.resetsAt == at }?.first ?: "five_hour"
            return key to at
        }
        val known = windows(s, now).filter { it.second.resetsAt != null }
        val least = known.minOfOrNull { it.second.remaining } ?: return null
        return known.filter { it.second.remaining == least }
            .minBy { it.second.resetsAt!! }
            .let { it.first to it.second.resetsAt!! }
    }
}

/**
 * Turns what the user typed into a bridge base URL. Private addresses get
 * http:// (pairing on the LAN), everything else https:// (a tunnel), and a
 * plain-http public address is refused: the token would cross the internet
 * unencrypted.
 */
fun normalizeAddress(input: String): Result<String> {
    val s = input.trim().trimEnd('/')
    if (s.isEmpty()) return Result.failure(IllegalArgumentException("Enter an address"))
    val hasScheme = "://" in s
    val rest = if (hasScheme) s.substringAfter("://") else s
    val host = hostOf(rest)
    if (host.isEmpty()) return Result.failure(IllegalArgumentException("That doesn't look like an address"))
    return when {
        s.startsWith("https://") -> Result.success(s)
        s.startsWith("http://") ->
            if (isPrivateHost(host)) Result.success(s)
            else Result.failure(IllegalArgumentException("Use https:// outside your home network"))
        hasScheme -> Result.failure(IllegalArgumentException("Use an http:// or https:// address"))
        isPrivateHost(host) -> Result.success("http://$s")
        else -> Result.success("https://$s")
    }
}

private fun hostOf(rest: String): String {
    val authority = rest.substringBefore('/')
    return if (authority.startsWith("[")) authority.substringBefore(']').removePrefix("[")
    else authority.substringBefore(':')
}

fun isPrivateHost(host: String): Boolean {
    val h = host.lowercase()
    if (h == "localhost" || h.endsWith(".local")) return true
    val parts = h.split('.').map { it.toIntOrNull() ?: return false }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    val (a, b) = parts
    return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
}
