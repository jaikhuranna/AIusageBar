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

/**
 * Every account's week as a thread, family by family. A family with no
 * accounts still has one faint thread, so the twist keeps its shape.
 */
fun strandsOf(views: List<FamilyView>, now: Instant): List<ThreadStrand> = views.flatMap { v ->
    val stale = v.offline || now.toEpochMilli() - v.fetchedAt > Look.STALE.toMillis()
    if (v.known.isEmpty()) listOf(ThreadStrand(Render.memberColor(v.family, 0), null, false))
    else v.known.mapIndexed { i, m -> ThreadStrand(Render.memberColor(v.family, i), (m.week ?: m.session)?.remaining?.div(100f), stale) }
}

/** The five widgets. Each is only a style and a layout; the drawing is shared. */
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

/** The 1x1: the original ring, for every account of the selected provider's family pooled. */
class RingWidget : UsageWidget(Style.POOL, R.layout.widget_glyph)
class RingLargeWidget : UsageWidget(Style.RING, R.layout.widget_glyph)
/** Every account's week as threads wound into concentric rings: Claude's outside, Codex's inside. */
class OrbitWidget : UsageWidget(Style.ORBIT, R.layout.widget_card)
class DashWidget : UsageWidget(Style.DASH, R.layout.widget_card)
/** Every account at once, whichever the others show. */
class ThreadWidget : UsageWidget(Style.THREAD, R.layout.widget_card)

object Widgets {
    val ALL: List<UsageWidget> = listOf(RingWidget(), RingLargeWidget(), OrbitWidget(), DashWidget(), ThreadWidget())

    /** The faces that draw every account, so any provider's answer redraws them. */
    val EVERY_ACCOUNT = setOf(Style.POOL, Style.THREAD, Style.ORBIT)

    /** A bitmap layer's budget; RemoteViews refuse very large parcels. */
    private const val MAX_LAYER_BYTES = 3_000_000f

    fun look(ctx: Context, now: Instant = Instant.now(), provider: UsageProvider? = null): Look {
        val s = Store(ctx, provider)
        return Look.of(s.paired, s.snapshot(), s.fetchedAt, s.lastError, s.backAt, now).copy(provider = s.provider)
    }

    /**
     * The pooled ring of the family the widgets show (picking Claude 2 still
     * pools every Claude account); null without a pool from this bridge.
     */
    fun pooledRing(ctx: Context, now: Instant = Instant.now()): Look? {
        val family = PoolFamily.of(Store(ctx).selected)
        val pool = PoolStore(ctx, family)
        val stores = family.providers.map { Store(ctx, it) }
        val fetched = stores.mapNotNull { it.snapshot()?.cacheFetchedAt?.toEpochMilli() }.minOrNull() ?: pool.fetchedAt
        return poolRing(pool.pool(), fetched, pool.offline, stores.map { it.backAt }, now)
    }

    /**
     * Every account's week as a thread, Claude's then Codex's. A family with no
     * accounts (or none paired yet) still has one faint thread, so the twist
     * keeps its shape.
     */
    fun strands(ctx: Context, now: Instant = Instant.now()): List<ThreadStrand> = rings(ctx, now).flatten()

    /** The same threads, one group per family: the orbit's rings, Claude's outside. */
    fun rings(ctx: Context, now: Instant = Instant.now()): List<List<ThreadStrand>> {
        val paired = Store(ctx).paired || Fixtures.widgets(ctx)
        return PoolFamily.entries.map { f -> strandsOf(listOf(familyView(ctx, f, now).let { if (paired) it else it.copy(members = emptyList()) }), now) }
    }

    fun refreshAll(ctx: Context, only: List<UsageWidget> = ALL) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val look = look(ctx)
        for (w in only) mgr.getAppWidgetIds(ComponentName(ctx, w.javaClass)).forEach { render(ctx, mgr, it, w, look) }
        Ticker.arm(ctx, look)
    }

    fun anyPlaced(ctx: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx)
        return ALL.any { mgr.getAppWidgetIds(ComponentName(ctx, it.javaClass)).isNotEmpty() }
    }

    fun render(ctx: Context, mgr: AppWidgetManager, id: Int, w: UsageWidget, look: Look) {
        val (wPx, hPx) = sizePx(ctx, mgr.getAppWidgetOptions(id), w.style)
        val ring = if (w.style == Style.POOL && look.mode != Mode.UNPAIRED) pooledRing(ctx, look.now) else null
        val layers = when (w.style) {
            Style.THREAD -> Render.threads(strands(ctx, look.now), wPx, hPx)
            Style.ORBIT -> Render.orbit(rings(ctx, look.now), wPx, hPx)
            Style.POOL -> Render.pool(ctx, ring, look, wPx, hPx)
            else -> Render.draw(ctx, w.style, look, wPx, hPx)
        }
        val rv = RemoteViews(ctx.packageName, w.layout).apply {
            setImageViewBitmap(R.id.ink, layers.ink)
            setImageViewBitmap(R.id.accent, layers.accent)
            setContentDescription(R.id.accent, when {
                w.style == Style.THREAD || w.style == Style.ORBIT -> describeWeeks(ctx)
                ring != null -> describePool(ctx, ring)
                else -> describe(look)
            })
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
            wDp = if (style == Style.DASH || style == Style.THREAD) 320f else 160f
            hDp = if (style == Style.THREAD) 70f else 160f
        }
        val density = ctx.resources.displayMetrics.density
        val scale = min(density, sqrt(MAX_LAYER_BYTES / 4f / (wDp * hDp)))
        return (wDp * scale).toInt() to (hDp * scale).toInt()
    }

    private fun describeWeeks(ctx: Context): String {
        val now = Instant.now()
        return PoolFamily.entries.flatMap { f ->
            val view = familyView(ctx, f, now)
            if (view.known.isEmpty()) listOf("${f.title} unknown")
            else view.known.map { m -> "${if (view.known.size > 1) "${f.title} ${m.number}" else f.title} ${(m.week ?: m.session)?.remaining}%" }
        }.joinToString(", ", "Weekly allowance left: ", ". Tap for details.")
    }

    private fun describePool(ctx: Context, ring: Look): String {
        val family = PoolFamily.of(Store(ctx).selected)
        val n = familyView(ctx, family, ring.now).known.size
        val who = if (n > 1) "${family.title}, $n accounts pooled" else family.title
        return when (ring.mode) {
            Mode.WAITING -> "${family.title}: no usage data yet. Tap for details."
            Mode.WEEK_OUT, Mode.SESSION_OUT -> "$who: every limit reached" + (ring.countdownTo?.let { ", back in ${untilText(ring.now, it)}" } ?: "") + "."
            Mode.GO -> "$who is back."
            else -> "$who: ${ring.sessionLeft}% usable now, ${ring.weekLeft}% of the week left. Tap for details."
        }
    }

    private fun describe(l: Look): String = when (l.mode) {
        Mode.UNPAIRED -> "${l.provider.title} usage: not paired. Tap to set up."
        Mode.WAITING -> "${l.provider.title} usage: waiting for the first reading."
        Mode.NORMAL -> {
            val session = if (l.session == null) "primary allowance not reported" else "${l.sessionLeft}% of the ${l.sessionLabel} allowance left"
            val week = if (l.week == null) "secondary allowance not reported" else "${l.weekLeft}% of the ${l.weekLabel} allowance left"
            "${l.provider.title}: $session, $week. Tap for details."
        }
        Mode.SESSION_OUT -> "${l.provider.title} ${l.sessionLabel} limit reached" + (l.countdownTo?.let { ", back in ${untilText(l.now, it)}" } ?: "")
        Mode.WEEK_OUT -> "${l.provider.title} ${l.weekLabel} limit reached" + (l.countdownTo?.let { ", back in ${untilText(l.now, it)}" } ?: "")
        Mode.GO -> "${l.provider.title} is back."
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
        if (look.ticking || Widgets.pooledRing(ctx)?.ticking == true) {
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
