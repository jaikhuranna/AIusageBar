package com.jaikhurana.aiusagewear

import android.content.Context
import android.text.format.DateFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** "2h 41m", "3d 4h", "12m". */
fun untilText(from: Instant, to: Instant): String {
    val d = Duration.between(from, to)
    if (d.isNegative || d.isZero) return "now"
    val h = d.toHours()
    val m = d.toMinutes() % 60
    return when {
        h >= 24 -> "${h / 24}d ${h % 24}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${maxOf(m, 1)}m"
    }
}

fun agoText(fetchedAtMillis: Long, now: Instant): String {
    if (fetchedAtMillis == 0L) return "never"
    val m = Duration.between(Instant.ofEpochMilli(fetchedAtMillis), now).toMinutes()
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60}h ago"
        else -> "${m / 1440}d ago"
    }
}

/** "3:40 PM" today-ish, "Fri 9:00 AM" further out. Follows the watch's 12/24h setting. */
fun clockText(ctx: Context, t: Instant, now: Instant): String {
    val time = if (DateFormat.is24HourFormat(ctx)) "H:mm" else "h:mm a"
    val pattern = if (Duration.between(now, t).toHours() < 20) time else "EEE $time"
    return DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(t)
}

fun windowLabel(key: String) = if (key == "seven_day") "7d" else "5h"
