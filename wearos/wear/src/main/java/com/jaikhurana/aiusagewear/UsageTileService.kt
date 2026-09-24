package com.jaikhurana.aiusagewear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Arc
import androidx.wear.protolayout.LayoutElementBuilders.ArcLine
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.time.Instant

/**
 * The same two top-half arcs as the app, from the cache. It never fetches:
 * [Sync] asks for an update after each fetch, and reset times are printed as
 * clock times ("resets 3:40 PM") so the tile is never stale between fetches.
 */
class UsageTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { it.set(tile()); "tile" }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { it.set(ResourceBuilders.Resources.Builder().setVersion(RES).build()); "res" }

    private fun tile(): TileBuilders.Tile {
        val store = Store(this)
        val now = Instant.now()
        val snap = store.snapshot()
        val five = snap?.fiveHour?.effective(now)
        val week = snap?.sevenDay?.effective(now)

        val (big, small, line) = when {
            !store.paired -> Triple("—", "Open to pair", "")
            store.lastError == Store.ERROR_UNPAIRED -> Triple("—", "Pair again", "open the app")
            snap == null -> Triple("…", "Waiting", "for the desktop")
            else -> {
                val comeback = Plan.comeback(snap, now)
                val (key, worst) = Plan.worst(snap, now) ?: ("five_hour" to Effective(100, null, false))
                val status = if (store.lastError == Store.ERROR_OFFLINE) "desktop offline" else null
                if (comeback != null) {
                    Triple("Out", "back ${clockText(this, comeback, now)}", status ?: "in ${untilText(now, comeback)}")
                } else {
                    val reset = worst.resetsAt?.let { "resets ${clockText(this, it, now)}" } ?: ""
                    Triple("${worst.remaining}%", "left · ${windowLabel(key)}", status ?: reset)
                }
            }
        }

        val root = Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("open")
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName(packageName)
                                            .setClassName(MainActivity::class.java.name)
                                            .build(),
                                    ).build(),
                            ).build(),
                    ).build(),
            )
            .addContent(arc(five?.remaining))
            .addContent(
                Box.Builder().setWidth(expand()).setHeight(expand())
                    .setModifiers(ModifiersBuilders.Modifiers.Builder().setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(13f)).build()).build())
                    .addContent(arc(week?.remaining))
                    .build(),
            )
            .addContent(
                Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(text(big, 34f, WHITE, bold = true))
                    .addContent(text(small, 14f, GREY))
                    .addContent(text(line, 13f, GREY))
                    .build(),
            )
            .build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RES)
            .setFreshnessIntervalMillis(Plan.BASE.toMillis())
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
    }

    /** A ~140° crescent centred on 12 o'clock: remaining fill, then the empty track. */
    private fun arc(remaining: Int?): Arc {
        val fill = SPAN * ((remaining ?: 0).coerceIn(0, 100) / 100f)
        val b = Arc.Builder()
            .setAnchorAngle(degrees(0f))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_CENTER)
        if (fill >= 1f) b.addContent(line(fill, ORANGE))
        if (SPAN - fill >= 1f) b.addContent(line(SPAN - fill, TRACK))
        return b.build()
    }

    private fun line(length: Float, color: Int) = ArcLine.Builder()
        .setLength(degrees(length))
        .setThickness(dp(8f))
        .setColor(argb(color))
        .setStrokeCap(LayoutElementBuilders.STROKE_CAP_ROUND)
        .build()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = Text.Builder()
        .setText(s)
        .setMaxLines(1)
        .setFontStyle(
            FontStyle.Builder()
                .setSize(sp(size))
                .setColor(argb(color))
                .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                .build(),
        ).build()

    private companion object {
        const val RES = "1"
        const val SPAN = 140f
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GREY = 0xFFB0B0B0.toInt()
        const val TRACK = 0xFF4A2A12.toInt()
        const val ORANGE = 0xFFFF8A1F.toInt()
    }
}

/** The tray's scheme: green, orange below 50% left, red below 20% left. */
fun colorFor(remaining: Int): Int = when {
    remaining < 20 -> 0xFFDC3545.toInt()
    remaining < 50 -> 0xFFFF9800.toInt()
    else -> 0xFF4CAF50.toInt()
}
