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

    private fun write(block: android.content.SharedPreferences.Editor.() -> Unit) {
        p.edit(action = block)
        _changes.update { it + 1 }
    }

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
    }
}
