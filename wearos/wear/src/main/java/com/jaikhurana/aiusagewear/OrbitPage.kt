package com.jaikhurana.aiusagewear

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.hypot

/**
 * The orbit, full screen. Tap a ring: it softens and dims, and its family's
 * session and week left circle over it, slowly turning. Tap it again, or
 * anywhere off the rings, to put it back.
 *
 * Kept cheap for a watch: each ring, its softened copy and its lettering are
 * bitmaps drawn once per data change, off the main thread (the blur is a
 * shrink-and-grow, [Orbit.soften], not a per-frame effect). A tap only changes
 * which are drawn, and each animation frame only redraws them, the lettering
 * rotated. Nothing turns while no ring is picked.
 */
@Composable
fun OrbitPage(store: Store, picked: Int?, onPick: (Int?) -> Unit) {
    val rings = Orbit.rings(store, Instant.now())
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val px = minOf(constraints.maxWidth, constraints.maxHeight)
        // Every ring's images, drawn off the main thread whenever the data
        // changes, so a tap only switches what's drawn. The last set stays up
        // while the next is drawn.
        var art by remember { mutableStateOf<Art?>(null) }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(rings, px) {
            // From the watch's cache when these threads were drawn before (the
            // data moves a few times an hour); otherwise the threads first, so
            // the orbit is up at once, then the blur and lettering, then saved.
            withContext(Dispatchers.IO) { Art.load(ctx, rings, px) }?.let { art = it; return@LaunchedEffect }
            val sharp = withContext(Dispatchers.Default) { Art.sharp(rings, px) }
            if (art == null) art = Art(sharp.map { it.asImageBitmap() }, emptyList(), emptyList())
            art = withContext(Dispatchers.Default) { Art.of(rings, px, sharp).also { Art.save(ctx, rings, px, it) } }
        }
        // The last ring picked stays drawn while the overlay fades out.
        var shown by remember { mutableStateOf<Int?>(null) }
        if (picked != null && picked < rings.size) shown = picked
        val t by animateFloatAsState(if (picked != null) 1f else 0f, tween(if (picked != null) 260 else 200), label = "pick")
        if (picked == null && t == 0f) shown = null
        val angle = if (shown != null) {
            rememberInfiniteTransition(label = "turn")
                .animateFloat(0f, 360f, infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
        } else null

        Canvas(
            Modifier.fillMaxSize().pointerInput(rings, picked) {
                detectTapGestures { o ->
                    val d = hypot(o.x - size.width / 2f, o.y - size.height / 2f) / px
                    val k = Orbit.ringAt(d, rings.size)
                    onPick(if (k == null || k == picked) null else k)
                }
            },
        ) {
            val a = art ?: return@Canvas
            val at = Offset((size.width - px) / 2, (size.height - px) / 2)
            val k = shown
            a.sharp.forEachIndexed { i, img ->
                // The picked ring fades to its softened copy; the rest stay sharp.
                drawImage(img, at, alpha = if (i == k && k < a.soft.size) 1f - t else 1f)
            }
            if (k != null && k < a.soft.size) {
                drawImage(a.soft[k], at, alpha = t * 0.55f)
                rotate(angle?.value ?: 0f) { drawImage(a.words[k], at, alpha = t) }
            }
        }
        // The orbit has no figures of its own, so its age and trouble go under it.
        val status = when {
            store.lastError == Store.ERROR_UNPAIRED -> null
            store.lastError == Store.ERROR_OFFLINE -> "offline · ${agoText(store.fetchedAt, Instant.now())}"
            store.fetchedAt > 0 && Instant.now().toEpochMilli() - store.fetchedAt > 3_600_000 -> "updated ${agoText(store.fetchedAt, Instant.now())}"
            else -> null
        }
        if (store.lastError == Store.ERROR_UNPAIRED) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) { PairAgain { unpair(ctx, store) } }
        } else if (status != null) {
            Text(status, color = DIM, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
        }
        val ring = shown?.let { rings.getOrNull(it) }
        if (ring != null) {
            // The provider's mark, not its name, in the family's colour.
            androidx.compose.foundation.Image(
                painterResource(ring.family.logo), contentDescription = ring.family.title,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(ring.family.color(0))),
                alpha = t, modifier = Modifier.size(34.dp),
            )
        }
    }
}

/** Each ring alone, sharp and softened, and its figures to turn round it. */
private class Art(val sharp: List<ImageBitmap>, val soft: List<ImageBitmap>, val words: List<ImageBitmap>) {
    companion object {
        fun sharp(rings: List<OrbitRing>, px: Int) = (0 until minOf(rings.size, Orbit.RADII.size)).map { Orbit.render(rings, px, only = it) }

        fun of(rings: List<OrbitRing>, px: Int, sharp: List<Bitmap>) = Art(
            sharp.map { it.asImageBitmap() },
            sharp.map { Orbit.soften(it).asImageBitmap() },
            sharp.indices.map { words(rings[it], px, Orbit.RADII[it] * px) },
        )

        private fun dir(ctx: android.content.Context) = java.io.File(ctx.cacheDir, "orbit")
        private fun key(rings: List<OrbitRing>, px: Int) = "${Integer.toHexString(rings.hashCode())}-$px"

        /** The saved images for exactly these rings at this size, or null. */
        fun load(ctx: android.content.Context, rings: List<OrbitRing>, px: Int): Art? {
            val n = minOf(rings.size, Orbit.RADII.size)
            val k = key(rings, px)
            fun read(kind: String): List<ImageBitmap>? = (0 until n).map { i ->
                android.graphics.BitmapFactory.decodeFile(java.io.File(dir(ctx), "$k-$kind$i.png").path)?.asImageBitmap() ?: return null
            }
            return Art(read("sharp") ?: return null, read("soft") ?: return null, read("words") ?: return null)
        }

        /** Saves [art], replacing whatever older set was there. */
        fun save(ctx: android.content.Context, rings: List<OrbitRing>, px: Int, art: Art) {
            val d = dir(ctx).apply { mkdirs() }
            d.listFiles()?.forEach { it.delete() }
            val k = key(rings, px)
            for ((kind, imgs) in listOf("sharp" to art.sharp, "soft" to art.soft, "words" to art.words)) imgs.forEachIndexed { i, img ->
                java.io.File(d, "$k-$kind$i.png").outputStream().use { img.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}

/** "5H 64% · WEEK 71% ·" spaced evenly round a circle of radius [r], reading clockwise from 12. */
private fun words(ring: OrbitRing, px: Int, r: Float): ImageBitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = px * 0.052f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.12f
    }
    val unit = "5H ${ring.session?.let { "$it%" } ?: "--"}  ·  WEEK ${ring.week?.let { "$it%" } ?: "--"}  ·  "
    val circumference = (2 * PI * r).toFloat()
    val n = floor(circumference / paint.measureText(unit)).toInt().coerceAtLeast(1)
    val path = Path().apply { addCircle(px / 2f, px / 2f, r, Path.Direction.CW) }
    // addCircle starts at 3 o'clock; a quarter turn back puts the first at 12,
    // and every one then fits before the path's end without wrapping.
    c.rotate(-90f, px / 2f, px / 2f)
    // Pushed in by a third of the type, so the letters sit centred on the ring.
    for (i in 0 until n) c.drawTextOnPath(unit, path, circumference * i / n, paint.textSize * 0.36f, paint)
    return bmp.asImageBitmap()
}
