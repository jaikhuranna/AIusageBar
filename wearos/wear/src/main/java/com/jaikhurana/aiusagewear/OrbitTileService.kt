package com.jaikhurana.aiusagewear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Image
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream

/**
 * The phone's 2x2 orbit as a tile: every account's week wound into rings,
 * drawn by [Orbit] into one image. Tapping the outer ring opens the app on
 * Claude's figures, the inner on Codex's. It never fetches; [Sync] asks for
 * an update when a `/pool` answer changes, and the resources version is the
 * data's hash, so the image is only redrawn when the threads move.
 */
class OrbitTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { it.set(tile(requestParams.deviceConfiguration.screenWidthDp.toFloat())); "orbit" }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = CallbackToFutureAdapter.getFuture {
        val dc = requestParams.deviceConfiguration
        val px = (dc.screenWidthDp * dc.screenDensity).toInt().coerceIn(200, 600)
        val png = ByteArrayOutputStream().also { out ->
            Orbit.render(Orbit.rings(Store(this)), px).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        it.set(
            ResourceBuilders.Resources.Builder()
                .setVersion(requestParams.version)
                .addIdToImageMapping(
                    IMAGE,
                    ResourceBuilders.ImageResource.Builder()
                        .setInlineResource(
                            ResourceBuilders.InlineImageResource.Builder()
                                .setData(png).setWidthPx(px).setHeightPx(px)
                                .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED) // PNG-encoded
                                .build(),
                        ).build(),
                ).build(),
        )
        "orbit-res"
    }

    private fun tile(screenDp: Float): TileBuilders.Tile {
        val rings = Orbit.rings(Store(this))
        // The inner ring's tap target: a square round the Codex ring and its twist.
        val inner = screenDp * 2 * (Orbit.RADII[1] + 0.07f)
        val root = Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setModifiers(open(0, "claude"))
            .addContent(Image.Builder().setResourceId(IMAGE).setWidth(expand()).setHeight(expand()).build())
            .addContent(Box.Builder().setWidth(dp(inner)).setHeight(dp(inner)).setModifiers(open(1, "codex")).build())
            .build()
        return TileBuilders.Tile.Builder()
            .setResourcesVersion("${RES}-${rings.hashCode()}")
            .setFreshnessIntervalMillis(Plan.BASE.toMillis())
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
    }

    /** Opens the app's orbit with ring [k] picked. */
    private fun open(k: Int, id: String) = ModifiersBuilders.Modifiers.Builder()
        .setClickable(
            ModifiersBuilders.Clickable.Builder()
                .setId(id)
                .setOnClick(
                    ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(
                            ActionBuilders.AndroidActivity.Builder()
                                .setPackageName(packageName)
                                .setClassName(MainActivity::class.java.name)
                                .addKeyToExtraMapping(MainActivity.EXTRA_RING, ActionBuilders.AndroidIntExtra.Builder().setValue(k).build())
                                .build(),
                        ).build(),
                ).build(),
        ).build()

    private companion object {
        const val RES = "orbit1"
        const val IMAGE = "orbit"
    }
}
