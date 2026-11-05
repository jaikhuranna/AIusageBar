package com.jaikhurana.aiusagewear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import org.json.JSONObject
import java.time.Instant
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** A family of accounts, as `/pool?family=` names it. */
enum class PoolFamily(val wire: String, val title: String, val logo: Int) {
    CLAUDE("claude", "Claude", R.drawable.logo_claude), CODEX("codex", "Codex", R.drawable.logo_codex);

    /**
     * Account [index]'s colour: the family's own first, then its siblings, so
     * every thread of a family reads as that family. The phone's `memberColor`.
     */
    fun color(index: Int): Int {
        val shades = when (this) {
            CLAUDE -> intArrayOf(0xFFD97757.toInt(), 0xFFBF8B5A.toInt(), 0xFFE9A07E.toInt(), 0xFF9E5A3C.toInt())
            CODEX -> intArrayOf(0xFF10A37F.toInt(), 0xFF6CC9A9.toInt(), 0xFF0B7559.toInt())
        }
        return shades[index.coerceAtLeast(0) % shades.size]
    }
}

/** One account in `/pool`: what's left of its session and week, %, null unreported. */
data class PoolAccount(val session: Int?, val week: Int?, val sessionResetsAt: Instant?, val weekResetsAt: Instant?) {
    /** A reset that passed since the fetch counts as full again. */
    fun session(now: Instant) = if (sessionResetsAt != null && !sessionResetsAt.isAfter(now)) 100 else session
    fun week(now: Instant) = if (weekResetsAt != null && !weekResetsAt.isAfter(now)) 100 else week
}

/** `/pool`'s members that reported anything, in the server's order. */
fun parsePoolAccounts(json: String): List<PoolAccount> {
    val arr = JSONObject(json).optJSONArray("members") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val m = arr.getJSONObject(i)
        fun left(key: String) = m.optJSONObject(key)?.let { w ->
            if (w.has("remaining")) w.optInt("remaining") else (100 - w.optInt("utilization")).coerceAtLeast(0)
        }
        fun reset(key: String) = m.optJSONObject(key)?.let { runCatching { Instant.parse(it.optString("resets_at")) }.getOrNull() }
        PoolAccount(left("five_hour"), left("seven_day"), reset("five_hour"), reset("seven_day"))
            .takeIf { it.session != null || it.week != null }
    }
}

/** One thread: an account's week, bright for [left] (0..1, null unknown). */
data class Strand(val color: Int, val left: Float?, val stale: Boolean)

/** One ring of the orbit: a family's threads, and its pooled session and week left. */
data class OrbitRing(val family: PoolFamily, val strands: List<Strand>, val session: Int?, val week: Int?)

/**
 * The phone's 2x2 orbit on the watch: every account's week as threads wound
 * into concentric rings, Claude's outside and Codex's inside, bright for
 * what's left, with a bead where it runs out. The drawing is the phone's
 * `ThreadFace.orbit` (`android/…/Render.kt`), closed rings only; keep the
 * two in step.
 */
object Orbit {
    /** Older than this, the threads lose their colour (the phone's `Look.STALE`). */
    private const val STALE_MS = 3_600_000L

    /** The rings from the cache. A family with no accounts keeps one faint thread. */
    fun rings(store: Store, now: Instant = Instant.now()): List<OrbitRing> = PoolFamily.entries.map { f ->
        val accounts = store.poolJson(f)?.let { runCatching { parsePoolAccounts(it) }.getOrNull() } ?: emptyList()
        val stale = store.lastError != null || now.toEpochMilli() - store.poolFetchedAt(f) > STALE_MS
        if (accounts.isEmpty()) OrbitRing(f, listOf(Strand(f.color(0), null, false)), null, null)
        else OrbitRing(
            f,
            accounts.mapIndexed { i, a -> Strand(f.color(i), (a.week(now) ?: a.session(now))?.div(100f), stale) },
            average(accounts.map { it.session(now) }),
            average(accounts.map { it.week(now) }),
        )
    }

    private fun average(xs: List<Int?>): Int? = xs.filterNotNull().takeIf { it.isNotEmpty() }?.let { v ->
        // Never round a sliver to empty or full.
        val a = v.sum().toDouble() / v.size
        a.roundToInt().coerceIn(if (a > 0) 1 else 0, if (a < 100) 99 else 100)
    }

    /**
     * Ring [k]'s radius and twist height, as shares of the square's side: the
     * phone's, grown by a tenth to fill a round screen.
     */
    val RADII = floatArrayOf(0.39f, 0.225f)
    private val AMPS = floatArrayOf(0.055f, 0.044f)

    /** Which ring a point [d] (distance from the centre, share of the side) is on, if any. */
    fun ringAt(d: Float, rings: Int): Int? =
        (0 until minOf(rings, RADII.size)).minByOrNull { kotlin.math.abs(RADII[it] - d) }
            ?.takeIf { kotlin.math.abs(RADII[it] - d) < AMPS[it] + 0.07f }

    /**
     * The orbit, [size] px square, on transparent. [only] draws just that
     * ring; [except] leaves it out (the app blurs it on its own).
     */
    fun render(rings: List<OrbitRing>, size: Int, only: Int? = null, except: Int? = null): Bitmap {
        val bmp = Bitmap.createBitmap(size.coerceAtLeast(1), size.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()
        val sw = s * 0.03f
        rings.take(RADII.size).forEachIndexed { k, ring ->
            if ((only != null && k != only) || k == except) return@forEachIndexed
            val count = ring.strands.size
            val radius = s * RADII[k]
            val amp = s * AMPS[k]
            // A lone strand has nothing to twist round: a plain circle.
            val a = if (count > 1) amp else 0f
            // Whole twists per turn, so every strand closes on itself.
            val twists = (2 * PI * radius / (12 * amp.coerceAtLeast(1f))).roundToInt().coerceAtLeast(1)
            fun t(u: Double) = 2 * PI * twists * u
            braid(ring.strands, c, sw, ::t) { i, u ->
                val r = radius + a * Braid.offset(i, count, t(u)).toFloat()
                val angle = -PI / 2 + 2 * PI * u
                (s / 2 + r * cos(angle).toFloat()) to (s / 2 + r * sin(angle).toFloat())
            }
        }
        return bmp
    }

    /**
     * A lightly blurred copy for behind the figures: at half size, three box
     * passes (close to a Gaussian of about 5 px at full size), in premultiplied
     * colour so transparent edges don't darken, then scaled back up. Done once
     * per data change, never per frame: a few milliseconds on a watch.
     */
    fun soften(src: Bitmap, radius: Int = 2): Bitmap {
        val w = (src.width / 2).coerceAtLeast(1)
        val h = (src.height / 2).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        small.recycle()
        val ch = Array(4) { FloatArray(w * h) }
        for (k in px.indices) {
            val c = px[k]
            val a = (c ushr 24) / 255f
            ch[0][k] = a
            ch[1][k] = ((c shr 16) and 0xFF) * a
            ch[2][k] = ((c shr 8) and 0xFF) * a
            ch[3][k] = (c and 0xFF) * a
        }
        val tmp = FloatArray(maxOf(w, h))
        for (c in ch) repeat(3) {
            box(c, w, h, radius, horizontal = true, tmp)
            box(c, w, h, radius, horizontal = false, tmp)
        }
        for (k in px.indices) {
            val a = ch[0][k].coerceIn(0f, 1f)
            fun u(v: Float) = if (a <= 0f) 0 else (v / a).roundToInt().coerceIn(0, 255)
            px[k] = ((a * 255).roundToInt() shl 24) or (u(ch[1][k]) shl 16) or (u(ch[2][k]) shl 8) or u(ch[3][k])
        }
        val blurred = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        return Bitmap.createScaledBitmap(blurred, src.width, src.height, true).also { blurred.recycle() }
    }

    /** One running-sum box pass along rows or columns, edges clamped. Plain index loops: it runs on a watch. */
    private fun box(v: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean, tmp: FloatArray) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        val inv = 1f / (2 * r + 1)
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            val last = len - 1
            var sum = 0f
            for (i in -r..r) sum += v[base + (if (i < 0) 0 else if (i > last) last else i) * step]
            for (i in 0 until len) {
                tmp[i] = sum * inv
                val add = i + r + 1
                val sub = i - r
                sum += v[base + (if (add > last) last else add) * step] - v[base + (if (sub < 0) 0 else sub) * step]
            }
            for (i in 0 until len) v[base + i * step] = tmp[i]
        }
    }

    // --- The phone's braid, closed rings only ---------------------------------

    private const val STALE = 0.45f
    /** How far (a share of the braid) a crossing's bright redraw overlaps the strand either side. */
    private const val SEAM = 0.003
    private const val GHOST_ALPHA = 0.32f
    private const val BEAD = 0.95f
    private const val BEAD_CUT = 1.6f
    /** The share of a turn the bright stretch takes to fade in at 12 o'clock. */
    private const val FADE_IN = 0.07

    private val CLEAR_MODE = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

    private fun line(color: Int, width: Float, alpha: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeJoin = Paint.Join.ROUND
    }
    private fun gap(width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = CLEAR_MODE
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeJoin = Paint.Join.ROUND
    }

    private fun braid(
        strands: List<Strand>, acc: Canvas, sw: Float,
        twist: (Double) -> Double, point: (Int, Double) -> Pair<Float, Float>,
    ) {
        val count = strands.size
        if (count == 0) return
        val ghost = sw * 0.55f
        val moat = sw * 1.9f
        val ghostMoat = sw * 1.3f
        val alphas = strands.map { if (it.stale) STALE else 1f }
        val splits = strands.map { (it.left ?: 0f).coerceIn(0f, 1f).toDouble() }

        fun pieces(i: Int, a: Double, b: Double): List<Triple<Double, Double, Boolean>> {
            val cuts = mutableListOf(a, b)
            for (k in floor(a).toInt()..ceil(b).toInt()) cuts += listOf(k.toDouble(), k + splits[i])
            return cuts.filter { it in a..b }.distinct().sorted().zipWithNext().mapNotNull { (from, to) ->
                if (to - from <= 1e-6) return@mapNotNull null
                val mid = (from + to) / 2
                Triple(from, to, mid - floor(mid) < splits[i])
            }
        }
        fun path(i: Int, a: Double, b: Double) = Path().apply {
            val n = ((b - a) / 0.002).toInt().coerceAtLeast(2)
            for (k in 0..n) {
                val (x, y) = point(i, a + (b - a) * k / n)
                if (k == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        fun spans(from: Double, to: Double): List<Triple<Double, Double, Boolean>> {
            val cuts = mutableListOf(from, to)
            for (k in floor(from).toInt()..ceil(to).toInt()) cuts += listOf(k.toDouble(), k + FADE_IN)
            return cuts.filter { it in from..to }.distinct().sorted().zipWithNext().filter { it.second - it.first > 1e-6 }.map { (x, y) ->
                val mid = (x + y) / 2
                Triple(x, y, mid - floor(mid) < FADE_IN)
            }
        }
        val stops = FloatArray(9) { it / 8f }
        fun fadePaint(i: Int, start: Double): Paint {
            val (x0, y0) = point(i, start)
            val (x1, y1) = point(i, start + FADE_IN)
            val colors = IntArray(stops.size) { k ->
                val x = stops[k]
                val a = (x * x * (3 - 2 * x)) * alphas[i]
                (strands[i].color and 0xFFFFFF) or ((a * 255).roundToInt().coerceIn(0, 255) shl 24)
            }
            return line(Color.WHITE, sw, 1f).apply { shader = LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP) }
        }
        fun draw(i: Int, a: Double, b: Double, clearing: Boolean) {
            for ((from, to, bright) in pieces(i, a, b)) {
                for ((x, y, fade) in if (bright) spans(from, to) else listOf(Triple(from, to, false))) {
                    val p = path(i, x, y)
                    if (clearing) acc.drawPath(p, gap(if (bright) moat else ghostMoat))
                    if (fade) {
                        acc.drawPath(p, line(strands[i].color, ghost, alphas[i] * GHOST_ALPHA))
                        acc.drawPath(p, fadePaint(i, floor((x + y) / 2)))
                    } else {
                        // Over a cleared gap, the bright redraw runs a hair past it each
                        // end: two anti-aliased butt edges on one line leave a seam.
                        val q = if (clearing && bright) path(i, x - SEAM, y + SEAM) else p
                        acc.drawPath(q, if (bright) line(strands[i].color, sw, alphas[i]) else line(strands[i].color, ghost, alphas[i] * GHOST_ALPHA))
                    }
                }
            }
        }

        for (i in 0 until count) draw(i, 0.0, 1.0, clearing = false)

        val t0 = twist(0.0)
        val t1 = twist(1.0)
        val reach = (max(moat, ghostMoat) + sw) / 2 * 1.15f
        fun apart(c: Braid.Crossing, u: Double): Boolean {
            val (fx, fy) = point(c.front, u)
            val (bx, by) = point(c.back, u)
            return hypot(fx - bx, fy - by) > reach
        }
        for (c in Braid.crossings(count, t0, t1)) {
            val uc = (c.t - t0) / (t1 - t0)
            var a = uc
            var b = uc
            while (uc - a < 0.2 && !apart(c, a)) a -= 0.001
            while (b - uc < 0.2 && !apart(c, b)) b += 0.001
            draw(c.front, a, b, clearing = true)
        }

        // A bead where each thread's week runs out, cut clear of what's under it.
        val beads = (0 until count).filter { splits[it] > 0 && splits[it] < 1 }
        val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = CLEAR_MODE }
        beads.forEach { i -> val (x, y) = point(i, splits[i]); acc.drawCircle(x, y, sw * BEAD_CUT, cut) }
        beads.forEach { i ->
            val (x, y) = point(i, splits[i])
            acc.drawCircle(x, y, sw * BEAD, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = strands[i].color; alpha = (alphas[i] * 255).roundToInt() })
        }
    }
}
