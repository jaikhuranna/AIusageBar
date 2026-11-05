package com.jaikhurana.aiusagewidget

import android.content.Context
import java.time.Instant

/** Release builds never draw made-up numbers; the debug source set has the real fixtures. */
object Fixtures {
    @Suppress("UNUSED_PARAMETER")
    fun pool(family: PoolFamily, now: Instant): Pool? = null

    @Suppress("UNUSED_PARAMETER")
    fun widgets(ctx: Context): Boolean = false

    /** Release ignores the switch; false means nothing changed. */
    @Suppress("UNUSED_PARAMETER")
    fun setWidgets(ctx: Context, on: Boolean): Boolean = false
}
