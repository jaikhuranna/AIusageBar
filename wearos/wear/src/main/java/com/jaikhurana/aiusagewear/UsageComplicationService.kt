package com.jaikhurana.aiusagewear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * "47%" with an arc, or a live countdown when out. UPDATE_PERIOD_SECONDS is 0:
 * [Sync] pushes updates after each fetch, and the countdown ticks on the watch
 * face itself.
 */
class UsageComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type, "five_hour", 47, null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val snap = Store(this).snapshot() ?: return NoDataComplicationData()
        val now = Instant.now()
        val (key, worst) = Plan.worst(snap, now) ?: return NoDataComplicationData()
        return data(request.complicationType, key, worst.remaining, Plan.comeback(snap, now))
    }

    private fun data(type: ComplicationType, key: String, remaining: Int, comeback: Instant?): ComplicationData? {
        val text: ComplicationText = if (comeback != null) {
            TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountDownTimeReference(comeback))
                .setMinimumTimeUnit(TimeUnit.MINUTES)
                .build()
        } else {
            plain("$remaining%")
        }
        val desc = plain(if (comeback != null) "Claude is out" else "Claude: $remaining% left")
        val title = plain(windowLabel(key))
        val tap = Alerts.openApp(this)
        return when (type) {
            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder(remaining.toFloat(), 0f, 100f, desc)
                    .setText(text).setTitle(title).setTapAction(tap).build()
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text, desc)
                    .setTitle(title).setTapAction(tap).build()
            else -> null
        }
    }

    private fun plain(s: String) = PlainComplicationText.Builder(s).build()
}
