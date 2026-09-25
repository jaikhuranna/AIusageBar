package com.jaikhurana.aiusagewidget

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.RemoteViews
import java.time.Instant
import kotlin.math.min
import kotlin.math.sqrt

/** The four widgets. Each is only a style and a layout; the drawing is shared. */
abstract class UsageWidget(val style: Style, val layout: Int) : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val look = Widgets.look(ctx)
        ids.forEach { Widgets.render(ctx, mgr, it, this, look) }
        Ticker.arm(ctx, look)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        Widgets.render(ctx, mgr, id, this, Widgets.look(ctx))
    }

    override fun onEnabled(ctx: Context) {
        // First widget of this kind: get fresh numbers instead of waiting for the next slot.
        if (Store(ctx).paired) Scheduler.soon(ctx)
    }
}

class RingWidget : UsageWidget(Style.RING, R.layout.widget_glyph)
class RingLargeWidget : UsageWidget(Style.RING, R.layout.widget_glyph)
class MatrixWidget : UsageWidget(Style.MATRIX, R.layout.widget_card)
class DashWidget : UsageWidget(Style.DASH, R.layout.widget_card)

object Widgets {
    val ALL: List<UsageWidget> = listOf(RingWidget(), RingLargeWidget(), MatrixWidget(), DashWidget())

    /** A bitmap layer's budget; RemoteViews refuse very large parcels. */
    private const val MAX_LAYER_BYTES = 3_000_000f

    fun look(ctx: Context, now: Instant = Instant.now()): Look {
        val s = Store(ctx)
        return Look.of(s.paired, s.snapshot(), s.fetchedAt, s.lastError, s.backAt, now)
    }

    fun refreshAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val look = look(ctx)
        for (w in ALL) mgr.getAppWidgetIds(ComponentName(ctx, w.javaClass)).forEach { render(ctx, mgr, it, w, look) }
        Ticker.arm(ctx, look)
    }

    fun anyPlaced(ctx: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx)
        return ALL.any { mgr.getAppWidgetIds(ComponentName(ctx, it.javaClass)).isNotEmpty() }
    }

    fun render(ctx: Context, mgr: AppWidgetManager, id: Int, w: UsageWidget, look: Look) {
        val (wPx, hPx) = sizePx(ctx, mgr.getAppWidgetOptions(id), w.style)
        val layers = Render.draw(ctx, w.style, look, wPx, hPx)
        val rv = RemoteViews(ctx.packageName, w.layout).apply {
            setImageViewBitmap(R.id.ink, layers.ink)
            setImageViewBitmap(R.id.accent, layers.accent)
            setContentDescription(R.id.accent, describe(look))
            // Paired, a tap opens the card (and refreshes); unpaired, it opens pairing.
            val tap = if (look.mode == Mode.UNPAIRED) openApp(ctx) else openCard(ctx)
            setOnClickPendingIntent(if (w.layout == R.layout.widget_glyph) R.id.root else android.R.id.background, tap)
        }
        mgr.updateAppWidget(id, rv)
    }

    /** The widget's current size in pixels, scaled down if the bitmaps would be too big. */
    private fun sizePx(ctx: Context, o: Bundle, style: Style): Pair<Int, Int> {
        val portrait = ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        var wDp = (if (portrait) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)).toFloat()
        var hDp = (if (portrait) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)).toFloat()
        if (wDp <= 0f || hDp <= 0f) {
            wDp = if (style == Style.DASH) 320f else 160f
            hDp = 160f
        }
        val density = ctx.resources.displayMetrics.density
        val scale = min(density, sqrt(MAX_LAYER_BYTES / 4f / (wDp * hDp)))
        return (wDp * scale).toInt() to (hDp * scale).toInt()
    }

    private fun describe(l: Look): String = when (l.mode) {
        Mode.UNPAIRED -> "Claude usage: not paired. Tap to set up."
        Mode.WAITING -> "Claude usage: waiting for the first reading."
        Mode.NORMAL -> "Claude: ${l.sessionLeft}% of the session left, ${l.weekLeft}% of the week. Tap for details."
        Mode.SESSION_OUT -> "Claude session limit reached" + (l.countdownTo?.let { ", back in ${untilText(l.now, it)}" } ?: "")
        Mode.WEEK_OUT -> "Claude weekly limit reached" + (l.countdownTo?.let { ", back in ${untilText(l.now, it)}" } ?: "")
        Mode.GO -> "Claude is back."
    }

    /**
     * A broadcast, not an activity: a widget tap that launches an activity gets
     * the launcher's "widget morphs into the app window" transition, which
     * stretches the widget into a box behind the card. [CardReceiver] opens the
     * card itself, so only the card's own animation plays.
     */
    fun openCard(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, CardReceiver::class.java),
        // Mutable so the launcher's fill-in (the tapped widget's bounds, which
        // the card grows out of) gets through; immutable drops it. The intent
        // is explicit, so nothing else can be redirected with it.
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Redraws the widgets between fetches: every minute while a countdown is on
 * screen, every 5 minutes otherwise (reset times, pace ticks, GO! expiry).
 * The alarms never wake the phone; an overdue one fires as soon as the screen
 * comes on, which is the only time a widget can be seen.
 */
object Ticker {
    private const val FIVE_MIN = 5 * 60_000L

    fun arm(ctx: Context, look: Look) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = intent(ctx)
        if (!Widgets.anyPlaced(ctx)) {
            am.cancel(pi)
            return
        }
        val now = System.currentTimeMillis()
        if (look.ticking) {
            val nextMinute = (now / 60_000L + 1) * 60_000L
            if (am.canScheduleExactAlarms()) am.setExact(AlarmManager.RTC, nextMinute, pi)
            else am.setWindow(AlarmManager.RTC, nextMinute, 30_000L, pi)
        } else {
            am.setWindow(AlarmManager.RTC, now + FIVE_MIN, 60_000L, pi)
        }
    }

    private fun intent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, TickReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class CardReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ctx.startActivity(
            Intent(ctx, CardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply { sourceBounds = intent.sourceBounds }, // the tapped widget, to grow out of
            // No system window transition (Nothing OS slides a new task up from
            // the bottom); the card animates itself out of the widget.
            ActivityOptions.makeCustomAnimation(ctx, 0, 0).toBundle(),
        )
    }
}

class TickReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Widgets.refreshAll(ctx)
}

/** Alarms don't survive a reboot, and a clock change moves every countdown. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        Widgets.refreshAll(ctx)
        if (Store(ctx).paired) Scheduler.soon(ctx)
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
