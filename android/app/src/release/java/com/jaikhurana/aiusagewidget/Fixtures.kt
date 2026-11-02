package com.jaikhurana.aiusagewidget

import java.time.Instant

/** Release builds never draw made-up numbers; the debug source set has the real fixtures. */
object Fixtures {
    @Suppress("UNUSED_PARAMETER")
    fun pool(family: PoolFamily, now: Instant): Pool? = null
}
