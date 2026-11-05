package com.jaikhurana.aiusagewear

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** The one fetcher. Everything else renders what this leaves in [Store]. */
object Sync {
    private val mutex = Mutex()
    private const val MANUAL_THROTTLE_MS = 60_000L

    /**
     * Fetch, alert, refresh every surface, and schedule the next check.
     * Opening the app within a minute of a good fetch skips the network;
     * [force] (the Refresh button) always fetches.
     */
    suspend fun run(context: Context, manual: Boolean, force: Boolean = false) = mutex.withLock {
        withContext(Dispatchers.IO) { runLocked(context.applicationContext, manual, force) }
    }

    suspend fun selectProvider(context: Context, provider: UsageProvider) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val ctx = context.applicationContext
            val store = Store(ctx)
            if (store.provider != provider) {
                Alerts.clearComeback(ctx)
                store.selectProvider(provider)
                Surfaces.refresh(ctx)
                runLocked(ctx, manual = true, force = true)
            }
        }
    }

    private fun runLocked(ctx: Context, manual: Boolean, force: Boolean) {
        val store = Store(ctx)
        val base = store.baseUrl
        val token = store.token
        if (base == null || token == null) return

        val now = System.currentTimeMillis()
        val recent = store.lastError == null && now - store.fetchedAt < MANUAL_THROTTLE_MS
        val before = listOf(store.snapshotJson, store.lastError) + PoolFamily.entries.map { store.poolJson(it) }
        if (force || !(manual && recent)) fetch(ctx, store, listOfNotNull(base, store.lanUrl).distinct(), token, now)

        val snap = store.snapshot()
        Alerts.syncComeback(ctx, store, snap)
        // A 304 changes nothing a surface shows, so don't wake them for it.
        if (listOf(store.snapshotJson, store.lastError) + PoolFamily.entries.map { store.poolJson(it) } != before) Surfaces.refresh(ctx)
        if (store.lastError == Store.ERROR_UNPAIRED) {
            Scheduler.cancel(ctx) // polling can't fix a revoked token; the app asks to pair again
        } else {
            Scheduler.schedule(ctx, Plan.nextCheck(snap, Instant.now(), store.failures))
        }
    }

    /** Tries the tunnel, then the LAN address; the first one that answers wins. */
    private fun fetch(ctx: Context, store: Store, bases: List<String>, token: String, now: Long) {
        val etag = store.etag.takeIf { store.snapshotJson != null }
        var base = bases.first()
        var r: Bridge.Usage = Bridge.Usage.Failed("no address")
        for (b in bases) {
            base = b
            r = Bridge.usage(b, token, etag, store.provider)
            if (r !is Bridge.Usage.Failed) break
        }
        when (r) {
            is Bridge.Usage.Fresh -> if (runCatching { parseSnapshot(r.body).provider == store.provider }.getOrDefault(false)) {
                store.snapshotJson = r.body
                store.etag = r.etag
                ok(store, now)
            } else {
                failed(store)
            }
            Bridge.Usage.NotModified -> ok(store, now)
            Bridge.Usage.Unauthorized -> store.lastError = Store.ERROR_UNPAIRED
            is Bridge.Usage.Failed -> failed(store)
        }
        // Every account of both families, for the orbit: two small requests
        // that usually answer 304, on the same base that just worked.
        if (store.lastError == null) for (f in PoolFamily.entries) {
            when (val p = Bridge.pool(base, token, store.poolEtag(f).takeIf { store.poolJson(f) != null }, f)) {
                is Bridge.Usage.Fresh -> store.setPool(f, p.body, p.etag, now)
                Bridge.Usage.NotModified -> store.setPool(f, null, store.poolEtag(f), now)
                // A bridge older than /pool: the same members from each account's /usage.
                is Bridge.Usage.Failed -> if (p.reason == "HTTP 404") poolFromUsage(base, token, f)?.let { store.setPool(f, it, null, now) }
                else -> Unit // keep the last answer; it dims as it ages
            }
        }
        // A threshold crossing always moves the numbers, so an unchanged
        // ETag means no new events: skip the second request.
        if (store.lastError == null && (store.etag == null || store.etag != store.eventsEtag)) pollEvents(ctx, store, base, token)
    }

    /** `/pool`'s members, built from each of the family's providers' `/usage`; null if none answered. */
    private fun poolFromUsage(base: String, token: String, f: PoolFamily): String? {
        val members = org.json.JSONArray()
        for (p in UsageProvider.entries.filter { it.family == f }) {
            val r = Bridge.usage(base, token, null, p) as? Bridge.Usage.Fresh ?: continue
            val o = runCatching { org.json.JSONObject(r.body) }.getOrNull() ?: continue
            if (o.optString("source_error").isNotBlank()) continue
            members.put(org.json.JSONObject().put("provider", p.wire).put("five_hour", o.opt("five_hour")).put("seven_day", o.opt("seven_day")))
        }
        return if (members.length() == 0) null else org.json.JSONObject().put("family", f.wire).put("members", members).toString()
    }

    private fun ok(store: Store, now: Long) {
        store.fetchedAt = now
        store.failures = 0
        store.lastError = null
    }

    private fun failed(store: Store) {
        store.failures = store.failures + 1
        store.lastError = Store.ERROR_OFFLINE
    }

    private fun pollEvents(ctx: Context, store: Store, base: String, token: String) {
        val body = Bridge.events(base, token, store.highWater, store.provider) ?: return
        val (events, high) = runCatching { parseEvents(body) }.getOrNull() ?: return
        store.eventsEtag = store.etag
        // The first page after pairing is history: remember it, don't replay it.
        if (store.highWater != null) {
            val seen = store.notifiedKeys
            events.filter { it.dedupeKey !in seen }.forEach { Alerts.notifyEvent(ctx, it) }
            store.notifiedKeys = seen + events.map { it.dedupeKey }
        } else {
            store.notifiedKeys = events.map { it.dedupeKey }
        }
        if (high != null) store.highWater = high
    }
}

object Scheduler {
    private const val NAME = "sync"

    // REPLACE also stops a sync that is still running when it reschedules itself.
    // That's harmless because rescheduling is the last thing a sync does.
    fun schedule(ctx: Context, delay: Duration) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delay.toMillis().coerceAtLeast(60_000L), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true) // the app still fetches when opened
                    .build(),
            )
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Sync.run(applicationContext, manual = false)
        return Result.success()
    }
}

/** Tells the tile and complications to re-read the cache. */
object Surfaces {
    // Both requesters bind to a service, which a BroadcastReceiver's own
    // context refuses (ReceiverCallNotAllowedException): use the app context.
    fun refresh(context: Context) {
        val ctx = context.applicationContext
        TileService.getUpdater(ctx).requestUpdate(OrbitTileService::class.java)
        for (service in listOf(UsageComplicationService::class.java, ResetComplicationService::class.java)) {
            ComplicationDataSourceUpdateRequester.create(ctx, ComponentName(ctx, service)).requestUpdateAll()
        }
    }
}
