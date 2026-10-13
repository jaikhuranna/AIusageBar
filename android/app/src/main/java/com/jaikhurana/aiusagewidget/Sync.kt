package com.jaikhurana.aiusagewidget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The one fetcher. Everything else renders what this leaves in [Store].
 * Each run asks for Claude and Codex in parallel and caches both.
 * Same cadence as AIusageWear (Plan.nextCheck), minus the alerts: the bridge's
 * events are the watch's job, and two devices buzzing for one crossing is noise.
 */
object Sync {
    private val mutex = Mutex()
    private const val MANUAL_THROTTLE_MS = 60_000L

    /**
     * Fetch every provider at once, redraw every widget, and schedule the next
     * check. Opening the app within a minute of a good fetch skips the network;
     * [force] always fetches.
     */
    suspend fun run(context: Context, manual: Boolean, force: Boolean = false) = mutex.withLock {
        val ctx = context.applicationContext
        val main = Store(ctx)
        val base = main.baseUrl
        val token = main.token
        if (base == null || token == null) {
            Widgets.refreshAll(ctx)
            return@withLock
        }
        val bases = listOfNotNull(base, main.lanUrl).distinct()
        val now = System.currentTimeMillis()
        // Each provider has its own cache, so the two requests never touch the same keys.
        coroutineScope {
            UsageProvider.entries.map { provider ->
                async(Dispatchers.IO) {
                    val store = Store(ctx, provider)
                    val recent = store.lastError == null && now - store.fetchedAt < MANUAL_THROTTLE_MS
                    if (force || !(manual && recent)) fetch(store, bases, token, now)
                    store.snapshot()?.let { Plan.comeback(it, Instant.now()) }?.let { store.backAt = it.toEpochMilli() }
                    // Redraw as soon as the provider on screen has its answer.
                    if (provider == store.selected) Widgets.refreshAll(ctx)
                }
            }.awaitAll()
        }
        withContext(Dispatchers.IO) {
            val stores = UsageProvider.entries.map { Store(ctx, it) }
            if (stores.any { it.lastError == Store.ERROR_UNPAIRED }) {
                Scheduler.cancel(ctx) // polling can't fix a revoked token; the app asks to pair again
            } else {
                // Soonest of the two: the card shows both, so neither may go stale.
                Scheduler.schedule(ctx, stores.minOf { Plan.nextCheck(it.snapshot(), Instant.now(), it.failures) })
            }
        }
    }

    /** Both caches are kept warm, so switching is only a redraw. It never waits for a fetch. */
    fun selectProvider(context: Context, provider: UsageProvider) {
        val ctx = context.applicationContext
        Store(ctx).selectProvider(provider)
        Widgets.refreshAll(ctx)
    }

    /** Tries the tunnel, then the LAN address; the first one that answers wins. */
    private fun fetch(store: Store, bases: List<String>, token: String, now: Long) {
        val etag = store.etag.takeIf { store.snapshotJson != null }
        var r: Bridge.Usage = Bridge.Usage.Failed("no address")
        for (b in bases) {
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
}

object Scheduler {
    private const val NAME = "sync"

    // REPLACE also stops a sync that is still running when it reschedules itself.
    // That's harmless because rescheduling is the last thing a sync does.
    fun schedule(ctx: Context, delay: Duration) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delay.toMillis().coerceAtLeast(60_000L), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
    }

    /** A widget was just placed: fetch now rather than at the next slot. */
    fun soon(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
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
