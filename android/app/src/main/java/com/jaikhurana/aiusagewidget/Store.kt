package com.jaikhurana.aiusagewidget

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Everything the app remembers, in app-private SharedPreferences. Backups are
 * off in the manifest, so the device token never leaves the phone.
 *
 * One cache per provider, many readers: the app and every widget render from
 * here, and only [Sync] fetches. `Store(ctx)` is the provider the widgets show;
 * `Store(ctx, provider)` reads or writes another one's cache. Both caches are
 * kept warm, so switching is only a redraw.
 */
class Store(context: Context, forProvider: UsageProvider? = null) {
    private val p = context.applicationContext.getSharedPreferences("aiusage", Context.MODE_PRIVATE)

    /** The provider the widgets show. */
    val selected: UsageProvider get() = UsageProvider.fromWire(p.getString("provider", "claude") ?: "claude")

    /** Whose cache this Store reads and writes. */
    val provider: UsageProvider = forProvider ?: selected

    fun selectProvider(value: UsageProvider) = write { putString("provider", value.wire) }

    /** The family the card's five-hour graph shows: the last one picked there, Claude at first. */
    var chartFamily: PoolFamily
        get() = PoolFamily.entries.firstOrNull { it.wire == p.getString("card_chart", null) } ?: PoolFamily.CLAUDE
        set(v) = write { putString("card_chart", v.wire) }

    init {
        // Before per-provider caches, the selected provider's lived under bare keys.
        if (p.contains("fetched_at")) {
            val old = selected.wire
            val all = p.all
            p.edit {
                PER_PROVIDER.forEach { k ->
                    when (val v = all[k]) {
                        is String -> putString("${k}_$old", v)
                        is Long -> putLong("${k}_$old", v)
                        is Int -> putInt("${k}_$old", v)
                    }
                    remove(k)
                }
                listOf("high_water", "events_etag", "comeback_at", "notified").forEach { remove(it) }
            }
        }
    }

    var baseUrl: String? by str("base_url")
    /** The LAN address pairing happened on, tried when [baseUrl] (the tunnel) fails. */
    var lanUrl: String? by str("lan_url")
    var token: String? by str("token")
    var host: String? by str("host")

    private val suffix = "_${provider.wire}"
    var snapshotJson: String? by str("snapshot$suffix")
    var etag: String? by str("etag$suffix")
    var fetchedAt: Long by long("fetched_at$suffix")
    var failures: Int by int("failures$suffix")
    /** null, or one of [ERROR_OFFLINE], [ERROR_UNPAIRED]. */
    var lastError: String? by str("last_error$suffix")

    /** The last comeback time seen while out of quota; the widgets say GO! just after it. */
    var backAt: Long by long("back_at$suffix")

    val paired: Boolean get() = baseUrl != null && token != null

    fun snapshot(): Snapshot? = snapshotJson?.let { runCatching { parseSnapshot(it) }.getOrNull() }

    fun clear() = write { clear() }

    private fun write(block: android.content.SharedPreferences.Editor.() -> Unit) = write(p, block)

    private fun str(key: String) = prop({ p.getString(key, null) }) { v: String? ->
        write { if (v == null) remove(key) else putString(key, v) }
    }
    private fun long(key: String) = prop({ p.getLong(key, 0L) }) { v: Long -> write { putLong(key, v) } }
    private fun int(key: String) = prop({ p.getInt(key, 0) }) { v: Int -> write { putInt(key, v) } }

    private fun <T> prop(get: () -> T, set: (T) -> Unit) =
        object : kotlin.properties.ReadWriteProperty<Any?, T> {
            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = get()
            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) = set(value)
        }

    companion object {
        const val ERROR_OFFLINE = "offline"
        const val ERROR_UNPAIRED = "unpaired"
        private val PER_PROVIDER = listOf("snapshot", "etag", "fetched_at", "failures", "last_error", "back_at")

        private val _changes = MutableStateFlow(0L)
        /** Bumped on every write, so the UI can re-read. In-process only. */
        val changes: StateFlow<Long> = _changes

        internal fun write(p: android.content.SharedPreferences, block: android.content.SharedPreferences.Editor.() -> Unit) {
            p.edit(action = block)
            _changes.update { it + 1 }
        }
    }
}

/**
 * One family's `/pool` answer, cached beside the per-provider snapshots (keys
 * suffixed `_claude` / `_codex`). [missing] means the bridge answered 404: an
 * older server, so the widgets and the card fall back to the per-provider look.
 */
class PoolStore(context: Context, val family: PoolFamily) {
    private val p = context.applicationContext.getSharedPreferences("aiusage", Context.MODE_PRIVATE)
    private val suffix = "_${family.wire}"

    var json: String?
        get() = p.getString("pool$suffix", null)
        set(v) = Store.write(p) { if (v == null) remove("pool$suffix") else putString("pool$suffix", v) }
    var etag: String?
        get() = p.getString("pool_etag$suffix", null)
        set(v) = Store.write(p) { if (v == null) remove("pool_etag$suffix") else putString("pool_etag$suffix", v) }
    var fetchedAt: Long
        get() = p.getLong("pool_fetched_at$suffix", 0L)
        set(v) = Store.write(p) { putLong("pool_fetched_at$suffix", v) }
    var offline: Boolean
        get() = p.getBoolean("pool_offline$suffix", false)
        set(v) = Store.write(p) { putBoolean("pool_offline$suffix", v) }

    fun pool(): Pool? = json?.let { runCatching { parsePool(it) }.getOrNull() }?.takeIf { it.family == family }
}

/**
 * A family as the app and the card show it: from its `/pool` when the bridge
 * serves one, else from its providers' snapshots. Its age is the oldest of the
 * family's snapshots (`cache_fetched_at`, the numbers' true age), falling back
 * to when they were fetched.
 */
fun familyView(ctx: Context, family: PoolFamily, now: java.time.Instant): FamilyView {
    val stores = family.providers.map { Store(ctx, it) }
    val snaps = stores.map { it.snapshot() }
    val ages = stores.zip(snaps).mapNotNull { (s, snap) -> snap?.cacheFetchedAt?.toEpochMilli() ?: s.fetchedAt.takeIf { it > 0 } }
    val pool = PoolStore(ctx, family)
    val p = pool.pool()
    return if (p != null) FamilyView.of(p, ages.minOrNull() ?: pool.fetchedAt, pool.offline, now)
    else FamilyView.of(family, snaps, ages.minOrNull() ?: 0L, stores.any { it.lastError == Store.ERROR_OFFLINE }, now)
}
