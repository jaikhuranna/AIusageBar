package com.jaikhurana.aiusagewear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Just the time to the next reset that matters ([Plan.nextReset]): "2h 41m"
 * titled "5h". It ticks on the watch face, so like the other complication it
 * only needs a push when the reset time itself changes.
 */
class ResetComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        data(type, "five_hour", Instant.now().plus(Duration.ofMinutes(161)), out = false)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val snap = Store(this).snapshot() ?: return NoDataComplicationData()
        val now = Instant.now()
        val (key, at) = Plan.nextReset(snap, now) ?: return NoDataComplicationData()
        return data(request.complicationType, key, at, out = Plan.comeback(snap, now) != null)
    }

    private fun data(type: ComplicationType, key: String, at: Instant, out: Boolean): ComplicationData? {
        val name = if (key == "seven_day") "Weekly" else "Session"
        val desc = plain(if (out) "Claude is back in" else "$name resets in")
        val tap = Alerts.openApp(this)
        fun countdown(format: String? = null) =
            TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_DUAL_UNIT, CountDownTimeReference(at))
                .setMinimumTimeUnit(TimeUnit.MINUTES)
                .apply { format?.let(::setText) }
                .build()
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(countdown(), desc)
                    .setTitle(plain(if (out) "back" else windowLabel(key))).setTapAction(tap).build()
            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(countdown(if (out) "Claude back in ^1" else "$name resets in ^1"), desc)
                    .setTapAction(tap).build()
            else -> null
        }
    }

    private fun plain(s: String) = PlainComplicationText.Builder(s).build()
}
