package com.jaikhurana.aiusagewear

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the app remembers, in app-private SharedPreferences. Backups are
 * off in the manifest, so the device token never leaves the watch.
 *
 * One cache, many readers: the app, the tile and the complication all render
 * from here, and only [Sync] fetches.
 */
class Store(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("aiusage", Context.MODE_PRIVATE)

    var baseUrl: String? by str("base_url")
    /** The LAN address pairing happened on, tried when [baseUrl] (the tunnel) fails. */
    var lanUrl: String? by str("lan_url")
    var token: String? by str("token")
    var host: String? by str("host")

    var snapshotJson: String? by str("snapshot")
    var etag: String? by str("etag")
    var fetchedAt: Long by long("fetched_at")
    var failures: Int by int("failures")
    /** null, or one of [ERROR_OFFLINE], [ERROR_UNPAIRED]. */
    var lastError: String? by str("last_error")

    var highWater: String? by str("high_water")
    /** The usage ETag the last good /events poll went with. Events only follow a usage change. */
    var eventsEtag: String? by str("events_etag")
    var comebackAt: Long by long("comeback_at")

    /** Dedupe keys already notified, newest last, so a replayed event stays quiet. */
    var notifiedKeys: List<String>
        get() = p.getString("notified", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
        set(v) = write { putString("notified", v.takeLast(100).joinToString("\n")) }

    val paired: Boolean get() = baseUrl != null && token != null

    fun snapshot(): Snapshot? = snapshotJson?.let { runCatching { parseSnapshot(it) }.getOrNull() }

    fun clear() = write { clear() }

    private fun write(block: android.content.SharedPreferences.Editor.() -> Unit) {
        p.edit(action = block)
        _changes.value++
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

        private val _changes = MutableStateFlow(0L)
        /** Bumped on every write, so the UI can re-read. In-process only. */
        val changes: StateFlow<Long> = _changes
    }
}
