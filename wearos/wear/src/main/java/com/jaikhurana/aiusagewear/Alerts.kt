package com.jaikhurana.aiusagewear

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import java.time.Duration
import java.time.Instant

/**
 * Notifications, the comeback alarm and its countdown.
 *
 * The bridge decides alerts ([notifyEvent] only renders them). The comeback
 * countdown is display state derived from `resets_at`, not an alert.
 */
object Alerts {
    private const val CH_ALERTS = "alerts"
    private const val CH_COUNTDOWN = "countdown"
    private const val CH_BACK = "back"
    private const val ID_COUNTDOWN = 1
    private const val ID_BACK = 2

    fun ensureChannels(ctx: Context) {
        NotificationManagerCompat.from(ctx).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CH_ALERTS, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Limit alerts").build(),
                NotificationChannelCompat.Builder(CH_COUNTDOWN, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Comeback countdown").build(),
                NotificationChannelCompat.Builder(CH_BACK, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Claude is back").build(),
            ),
        )
    }

    fun notifyEvent(ctx: Context, e: UsageEvent) {
        if (e.kind != "threshold_crossed") return // resets are covered by the comeback alarm
        val window = if (e.window == "seven_day") "Weekly" else "Session"
        val (title, text) = if (e.threshold >= 100) {
            "$window limit reached" to "Out of Claude until it resets"
        } else {
            "$window at ${e.threshold}%" to "${100 - e.threshold}% left"
        }
        post(ctx, e.dedupeKey.hashCode(), base(ctx, CH_ALERTS).setContentTitle(title).setContentText(text))
    }

    /** Arms or clears the alarm and countdown to match the snapshot. */
    fun syncComeback(ctx: Context, store: Store, snap: Snapshot?) {
        val at = snap?.let { Plan.comeback(it, Instant.now()) }?.toEpochMilli() ?: 0L
        if (at == store.comebackAt) return
        store.comebackAt = at
        if (at == 0L) clearComeback(ctx) else armComeback(ctx, at)
    }

    fun armComeback(ctx: Context, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(ctx)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        showCountdown(ctx, at)
    }

    fun clearComeback(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(alarmIntent(ctx))
        NotificationManagerCompat.from(ctx).cancel(ID_COUNTDOWN)
    }

    fun notifyBack(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(ID_COUNTDOWN)
        post(
            ctx,
            ID_BACK,
            base(ctx, CH_BACK)
                .setContentTitle("Claude's back")
                .setContentText("Your limit has reset")
                .setVibrate(longArrayOf(0, 250, 150, 250)),
        )
    }

    private fun showCountdown(ctx: Context, at: Long) {
        val open = openApp(ctx)
        val b = base(ctx, CH_COUNTDOWN)
            .setContentTitle("Claude is out")
            .setContentText("Back at ${clockText(ctx, Instant.ofEpochMilli(at), Instant.now())}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setShowWhen(true)
            .setWhen(at)
        clockTimerAction(ctx, at)?.let(b::addAction)
        OngoingActivity.Builder(ctx, ID_COUNTDOWN, b)
            .setStaticIcon(R.drawable.ic_notification)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate("Back in #t#").addPart("t", Status.TimerPart(at)).build())
            .build()
            .apply(ctx)
        post(ctx, ID_COUNTDOWN, b)
    }

    /** The real Clock app timer, one tap away. A user tap may launch it; background work may not. */
    private fun clockTimerAction(ctx: Context, at: Long): NotificationCompat.Action? {
        val seconds = Duration.between(Instant.now(), Instant.ofEpochMilli(at)).seconds
        if (seconds !in 1..86_400) return null // Clock timers cap at 24h
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Claude's back")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (intent.resolveActivity(ctx.packageManager) == null) return null
        val pi = PendingIntent.getActivity(ctx, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action.Builder(null, "Start Clock timer", pi).build()
    }

    private fun base(ctx: Context, channel: String) =
        NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(channel != CH_COUNTDOWN)

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, ComebackReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission") // checked by canNotify
    private fun post(ctx: Context, id: Int, b: NotificationCompat.Builder) {
        if (canNotify(ctx)) NotificationManagerCompat.from(ctx).notify(id, b.build())
    }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/** Fires at the comeback time. */
class ComebackReceiver : BroadcastReceiver() {
    override fun onReceive(receiverContext: Context, intent: Intent) {
        val context = receiverContext.applicationContext // a receiver's own context can't bind services
        Store(context).comebackAt = 0L
        Alerts.notifyBack(context)
        Surfaces.refresh(context)
        Scheduler.schedule(context, Duration.ofMinutes(1)) // confirm the reset against the desktop
    }
}

/** Alarms don't survive a reboot; WorkManager does. Re-arm the one we own. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(receiverContext: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val context = receiverContext.applicationContext
        val store = Store(context)
        if (!store.paired) return
        val at = store.comebackAt
        if (at > System.currentTimeMillis()) Alerts.armComeback(context, at) else store.comebackAt = 0L
        Scheduler.schedule(context, Duration.ofMinutes(1))
    }
}
