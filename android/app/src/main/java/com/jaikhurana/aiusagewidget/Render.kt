package com.jaikhurana.aiusagewidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import java.time.Duration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.floor
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Every widget is two transparent bitmaps over a system-colored background:
 * "ink" is drawn in white and tinted to Nothing's element color by the layout
 * (so it flips with dark mode), "accent" carries the fixed orange, yellow and
 * grey. Neither layer knows the background color, which the launcher resolves.
 */
class Layers(val w: Int, val h: Int) {
    val ink: Bitmap = createBitmap(w, h)
    val accent: Bitmap = createBitmap(w, h)
    val inkCanvas = Canvas(ink)
    val accCanvas = Canvas(accent)
}

/** [POOL] is the 1x1: the ring, drawn for a whole family of accounts. [ORBIT] is the threads wound into rings. */
enum class Style { RING, ORBIT, DASH, THREAD, POOL }

/** One thread of [Style.THREAD]: an account's week, bright for [left] (0..1, null unknown). */
data class ThreadStrand(val color: Int, val left: Float?, val stale: Boolean)

object Render {
    fun accent(look: Look): Int = accent(look.provider)
    fun accent(provider: UsageProvider): Int = when (provider) {
        UsageProvider.CLAUDE -> ORANGE
        UsageProvider.CODEX -> GREEN
        UsageProvider.CLAUDE2 -> CLAUDE2
    }
    const val ORANGE = 0xFFD97757.toInt()
    /**
     * Claude 2: a tan sibling of Claude's orange, so both read as Claude. Less
     * red than the orange, well short of the timer yellow, and as legible on the
     * light card as the orange and green are (about 2.6:1).
     */
    const val CLAUDE2 = 0xFFBF8B5A.toInt()
    /**
     * Account [index] of a family: the first in the family's own colour, the
     * second in its sibling (Claude 2's tan), and more in further shades, so
     * every thread and segment of a family reads as that family.
     */
    fun memberColor(family: PoolFamily, index: Int): Int {
        val shades = when (family) {
            PoolFamily.CLAUDE -> intArrayOf(ORANGE, CLAUDE2, 0xFFE9A07E.toInt(), 0xFF9E5A3C.toInt())
            PoolFamily.CODEX -> intArrayOf(GREEN, 0xFF6CC9A9.toInt(), 0xFF0B7559.toInt())
        }
        return shades[index.coerceAtLeast(0) % shades.size]
    }
    const val GREEN = 0xFF10A37F.toInt()
    const val YELLOW = 0xFFFFC53D.toInt()
    const val GREY = 0xFF8C8C8C.toInt()
    /** Stale data keeps its shape but loses its color. */
    private const val STALE_ALPHA = 115

    /** Every face but the threads and the orbit, which draw many accounts at once ([threads], [orbit]). */
    fun draw(ctx: Context, style: Style, look: Look, w: Int, h: Int): Layers {
        val l = Layers(w.coerceAtLeast(1), h.coerceAtLeast(1))
        if (look.stale) l.accCanvas.saveLayerAlpha(null, STALE_ALPHA)
        when (style) {
            Style.RING, Style.POOL -> RingFace.draw(look, l)
            Style.DASH -> DashFace.draw(ctx, look, l)
            Style.THREAD, Style.ORBIT -> {}
        }
        if (look.stale) l.accCanvas.restore()
        return l
    }

    /**
     * The pooled 1x1: the original ring, drawn from [ring] ([poolRing]).
     * Unpaired, or with no pool from this bridge (an older one), it is the
     * selected provider's own ring.
     */
    fun pool(ctx: Context, ring: Look?, look: Look, w: Int, h: Int): Layers =
        draw(ctx, Style.RING, if (look.mode == Mode.UNPAIRED || ring == null) look else ring, w, h)

    /**
     * Every account's week as twisted threads. [fancy] is the card's: a deeper
     * twist, a fine ghost where the week's been used, and a bead where one
     * meets the other.
     */
    fun threads(strands: List<ThreadStrand>, w: Int, h: Int, fancy: Boolean = false): Layers =
        Layers(w.coerceAtLeast(1), h.coerceAtLeast(1)).also { ThreadFace.straight(strands, it, fancy) }

    /**
     * The 2x2 orbit: the same threads wound into concentric rings, one per
     * family, outermost first (Claude's accounts outside, Codex's inside), each
     * bright clockwise from 12 o'clock for its week left.
     */
    fun orbit(rings: List<List<ThreadStrand>>, w: Int, h: Int): Layers =
        Layers(w.coerceAtLeast(1), h.coerceAtLeast(1)).also { ThreadFace.orbit(rings, it) }
}

internal fun fill(color: Int, alpha: Float = 1f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    this.alpha = (alpha * 255).roundToInt()
}

internal fun stroke(color: Int, width: Float, alpha: Float = 1f, round: Boolean = true) = fill(color, alpha).apply {
    style = Paint.Style.STROKE
    strokeWidth = width
    if (round) strokeCap = Paint.Cap.ROUND
}

internal fun ink(alpha: Float = 1f) = fill(Color.WHITE, alpha)

internal val CLEAR = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
}

/** Dot-matrix text, left edge at [left], top at [top], one dot per [pitch]. */
internal fun Canvas.dots(text: String, left: Float, top: Float, pitch: Float, p: Paint) {
    val r = pitch * 0.4f
    DotFont.forEachDot(text) { c, row -> drawCircle(left + (c + 0.5f) * pitch, top + (row + 0.5f) * pitch, r, p) }
}

/**
 * Type, the way Nothing OS 3+ sets it: NType 82 for headlines and big
 * numbers, Roboto in tracked caps for labels, and Ndot only as a small accent
 * (the 5×7 [DotFont] here). Nothing's own widgets ask for these by family
 * name, so on a Nothing phone they come from the system; elsewhere the
 * headline falls back to the system sans.
 */
object Fonts {
    private val NTYPE = listOf("NType82-Headline", "NType82-Regular", "ntype82")

    val headline: Typeface by lazy {
        NTYPE.map { Typeface.create(it, Typeface.NORMAL) }.firstOrNull { it != Typeface.DEFAULT }
            ?: Typeface.create("sans-serif", Typeface.NORMAL)
    }

    val label: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
}

internal fun headline(color: Int, alpha: Float = 1f) = fill(color, alpha).apply {
    typeface = Fonts.headline
    letterSpacing = -0.03f
}

/**
 * Draws [text] as large as fits in [box], sized and centred on the height of
 * a digit so "62", "1:41" and "GO!" share a baseline. Returns the ink bounds.
 */
internal fun Canvas.fitText(text: String, box: RectF, p: Paint, alignLeft: Boolean = false): RectF {
    p.textSize = 100f
    val digit = Rect().also { p.getTextBounds("0", 0, 1, it) }
    val width = p.measureText(text)
    p.textSize = 100f * min(box.width() / width, box.height() / digit.height())
    val capH = digit.height() * p.textSize / 100f
    val w = p.measureText(text)
    val x = if (alignLeft) box.left else box.centerX() - w / 2
    val baseline = box.centerY() + capH / 2
    drawText(text, x, baseline, p)
    return RectF(x, baseline - capH, x + w, baseline)
}

/**
 * The 1x1 (and its 2x2 twin): the weekly limit as a pie in the middle, the 5h
 * session as a ring around it.
 */
private object RingFace {
    fun draw(look: Look, l: Layers) {
        val s = min(l.w, l.h).toFloat()
        val cx = l.w / 2f
        val cy = l.h / 2f
        val ringR = s * 0.385f
        val sw = s * 0.07f
        val pieR = s * 0.25f
        val ring = RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        val pie = RectF(cx - pieR, cy - pieR, cx + pieR, cy + pieR)
        val ink = l.inkCanvas
        val acc = l.accCanvas
        val track = stroke(Color.WHITE, sw, 0.13f)

        when (look.mode) {
            Mode.UNPAIRED, Mode.WAITING -> {
                ink.drawCircle(cx, cy, ringR, track)
                val box = RectF(cx - pieR * 0.9f, cy - pieR * 0.32f, cx + pieR * 0.9f, cy + pieR * 0.32f)
                ink.fitText(if (look.mode == Mode.UNPAIRED) "PAIR" else "…", box, headline(Color.WHITE, 0.8f))
            }
            Mode.NORMAL -> {
                ink.drawCircle(cx, cy, ringR, track)
                ink.drawCircle(cx, cy, pieR, ink(0.13f))
                if (look.sessionLeft > 0) acc.drawArc(ring, -90f, 360f * look.sessionLeft / 100, false, stroke(Render.accent(look), sw))
                if (look.weekLeft > 0) acc.drawArc(pie, -90f, 360f * look.weekLeft / 100, true, fill(Render.accent(look)))
            }
            Mode.SESSION_OUT -> {
                // The ring becomes a timer: a dot per slice of the 5h window, the
                // lit ones shrinking back toward 12 o'clock as the reset nears.
                val n = 40
                val lit = ceil(n * look.sessionTimerFraction()).toInt()
                val on = fill(Render.YELLOW)
                val off = fill(Render.YELLOW, 0.22f)
                for (i in 0 until n) {
                    val a = Math.toRadians(-90.0 + i * 360.0 / n)
                    acc.drawCircle(cx + ringR * cos(a).toFloat(), cy + ringR * sin(a).toFloat(), sw * 0.4f, if (i < lit) on else off)
                }
                ink.drawCircle(cx, cy, pieR, ink(0.08f))
                if (look.weekLeft > 0) acc.drawArc(pie, -90f, 360f * look.weekLeft / 100, true, fill(Render.accent(look), 0.22f))
                val text = look.countdownTo?.let { countdownText(look.now, it) } ?: "OUT"
                ink.fitText(text, RectF(cx - pieR * 0.88f, cy - pieR * 0.42f, cx + pieR * 0.88f, cy + pieR * 0.42f), headline(Color.WHITE))
            }
            Mode.WEEK_OUT -> {
                acc.drawCircle(cx, cy, ringR, stroke(Render.GREY, sw, 0.55f))
                acc.drawCircle(cx, cy, pieR, fill(Render.GREY, 0.4f))
                // Struck through, like a no-entry sign, with a gap cut around the bar.
                val d = (ringR + sw / 2) * 0.7071f
                CLEAR.strokeWidth = sw * 2.6f
                acc.drawLine(cx - d, cy - d, cx + d, cy + d, CLEAR)
                acc.drawLine(cx - d, cy - d, cx + d, cy + d, stroke(Render.GREY, sw))
            }
            Mode.GO -> {
                acc.drawArc(ring, 0f, 360f, false, stroke(Render.accent(look), sw))
                acc.fitText("GO!", RectF(cx - pieR * 1.05f, cy - pieR * 0.55f, cx + pieR * 1.05f, cy + pieR * 0.55f), headline(Render.accent(look)))
            }
        }
    }
}

/**
 * The 4x2: the number that matters most on the left, both windows on the
 * right as dot bars with a "time left" tick, so you can see at a glance
 * whether the headroom will outlast the clock.
 */
private object DashFace {
    fun draw(ctx: Context, look: Look, l: Layers) {
        val u = l.h / 100f
        val pad = 9 * u
        val leftW = l.w * 0.36f
        val ink = l.inkCanvas
        val acc = l.accCanvas
        // Labels as Nothing sets them: Roboto, tracked out 0.1.
        fun text(size: Float, alpha: Float) = ink(alpha).apply {
            typeface = Fonts.label
            textSize = size
            letterSpacing = 0.1f
        }

        val accent = when (look.mode) {
            Mode.SESSION_OUT -> Render.YELLOW
            Mode.WEEK_OUT -> Render.GREY
            else -> Render.accent(look)
        }

        // Header: the one place for Ndot-style dots, as a small accent.
        acc.drawCircle(pad + 1.6f * u, pad + 2.6f * u, 1.7f * u, fill(accent))
        ink.dots(look.provider.title.uppercase(), pad + 5.5f * u, pad, 0.75f * u, ink(0.75f))

        // The big number and what it means.
        val (big, caption, bigColor) = when (look.mode) {
            Mode.UNPAIRED -> Triple("PAIR", "TAP TO SET UP", Color.WHITE)
            Mode.WAITING -> Triple("...", "FETCHING", Color.WHITE)
            Mode.NORMAL ->
                if (look.week == null || (look.session != null && look.sessionLeft <= look.weekLeft)) Triple("${look.sessionLeft}%", "${look.sessionLabel} LEFT", Render.accent(look))
                else Triple("${look.weekLeft}%", "${look.weekLabel} LEFT", Render.accent(look))
            Mode.SESSION_OUT -> Triple(look.countdownTo?.let { countdownText(look.now, it) } ?: "OUT", "UNTIL THE ${look.sessionLabel} RESET", Render.YELLOW)
            Mode.WEEK_OUT -> Triple(look.countdownTo?.let { countdownText(look.now, it) } ?: "OUT", "${look.weekLabel} LIMIT HIT", Render.GREY)
            Mode.GO -> Triple("GO!", "${look.provider.title.uppercase()}'S BACK", Render.accent(look))
        }
        val bigCanvas = if (bigColor == Color.WHITE) ink else acc
        bigCanvas.fitText(big, RectF(pad, 22 * u, leftW - pad, 52 * u), headline(bigColor), alignLeft = true)
        ink.drawText(caption, pad, 64 * u, text(5.2f * u, 0.6f))

        // Footer: how old this is. A number without its age is a bug.
        val age = when {
            look.mode == Mode.UNPAIRED -> "NOT PAIRED"
            look.offline -> "OFFLINE · ${agoText(look.fetchedAt, look.now).uppercase()}"
            else -> "UPDATED ${agoText(look.fetchedAt, look.now).uppercase()}"
        }
        ink.drawText(age, pad, l.h - pad, text(4.6f * u, if (look.stale) 0.75f else 0.4f))

        ink.drawLine(leftW, pad, leftW, l.h - pad, stroke(Color.WHITE, 0.5f * u, 0.12f, round = false))

        val x0 = leftW + pad
        val x1 = l.w - pad
        row(ctx, look, l, look.sessionLabel, look.session, look.sessionLength, 30 * u, x0, x1, u, ::text)
        row(ctx, look, l, look.weekLabel, look.week, look.weekLength, 70 * u, x0, x1, u, ::text)
    }

    private fun row(
        ctx: Context, look: Look, l: Layers, label: String, e: Effective?, length: Duration,
        y: Float, x0: Float, x1: Float, u: Float, text: (Float, Float) -> Paint,
    ) {
        val ink = l.inkCanvas
        val acc = l.accCanvas
        val paired = look.mode != Mode.UNPAIRED && look.mode != Mode.WAITING && e != null
        val remaining = e?.remaining ?: 100
        val timerRow = look.mode == Mode.SESSION_OUT && label == look.sessionLabel
        val out = remaining <= 0 && paired

        ink.drawText(label, x0, y + 2.2f * u, text(6f * u, 0.9f))

        // Right-hand figure.
        val figure = when {
            !paired -> "--"
            out -> "OUT"
            else -> "$remaining%"
        }
        val figColor = when {
            !paired -> Color.WHITE
            look.mode == Mode.WEEK_OUT -> Render.GREY
            timerRow -> Render.YELLOW
            else -> Render.accent(look)
        }
        val fp = headline(figColor).apply { textSize = 13 * u }
        val figW = fp.measureText("100%")
        fp.textAlign = Paint.Align.RIGHT
        (if (figColor == Color.WHITE) ink else acc).drawText(figure, x1, y + 4.6f * u, fp)

        // The bar: one dot per slice.
        val bx0 = x0 + 11 * u
        val bx1 = x1 - figW - 4 * u
        val spacing = 3.7f * u
        val n = ((bx1 - bx0) / spacing).toInt().coerceAtLeast(1)
        val r = 1.2f * u
        val (lit, on, off) = when {
            !paired -> Triple(0, fill(Render.accent(look)), ink(0.12f))
            timerRow -> Triple(ceil(n * look.sessionTimerFraction()).toInt(), fill(Render.YELLOW), fill(Render.YELLOW, 0.22f))
            look.mode == Mode.WEEK_OUT -> Triple(0, fill(Render.GREY), fill(Render.GREY, 0.45f))
            // Any headroom at all lights a dot; an empty bar means OUT.
            else -> Triple(if (remaining > 0) ceil(n * remaining / 100f).toInt() else 0, fill(Render.accent(look)), ink(0.13f))
        }
        for (i in 0 until n) {
            val cx = bx0 + (i + 0.5f) * spacing
            if (i < lit) acc.drawCircle(cx, y, r, on)
            else (if (off.color == Color.WHITE) ink else acc).drawCircle(cx, y, r, off)
        }
        // The spent window is struck through, leaving its labels readable.
        if (out && look.mode == Mode.WEEK_OUT) acc.drawLine(bx0, y, bx0 + n * spacing, y, stroke(Render.GREY, 1.2f * u))

        // The "time left" tick: headroom reaching past it means you'll last.
        val reset = e?.resetsAt
        if (paired && reset != null && !out && look.mode != Mode.WEEK_OUT) {
            val f = (Duration.between(look.now, reset).seconds.toFloat() / length.seconds).coerceIn(0f, 1f)
            val tx = bx0 + f * n * spacing
            ink.drawLine(tx, y - 4.2f * u, tx, y + 4.2f * u, stroke(Color.WHITE, 0.7f * u))
        }

        // The line under it.
        val sub = when {
            !paired -> ""
            reset == null -> if (out) "RESET TIME UNKNOWN" else "WINDOW NOT STARTED"
            out || timerRow -> "BACK ${clockText(ctx, reset, look.now).uppercase()}"
            look.mode == Mode.WEEK_OUT -> "RESETS ${clockText(ctx, reset, look.now).uppercase()}"
            else -> {
                val p = when (val pc = pace(e, length, look.now)) {
                    Pace.Lasts -> " · LASTS"
                    is Pace.RunsOut -> " · EMPTY IN ~${untilText(look.now, pc.at).uppercase()}"
                    else -> ""
                }
                val left = untilText(look.now, reset).uppercase()
                // Drop words until it fits the row.
                val sp = text(4.6f * u, 0.55f)
                listOf("RESETS IN $left$p", "$left$p", "$left${p.replace("EMPTY IN ", "EMPTY ")}")
                    .firstOrNull { sp.measureText(it) <= x1 - x0 } ?: "RESETS IN $left"
            }
        }
        ink.drawText(sub, x0, y + 13 * u, text(4.6f * u, 0.55f))
    }
}

/**
 * The 4x1, the foot of the card, and the 2x2 orbit: every account's weekly
 * limit as threads twisted round each other (Claude's accounts in orange and
 * tan, Codex's in green), bright for the share of its week left and faint
 * where it's been used. No figures.
 *
 * Every strand is drawn whole; then at each crossing ([Braid.crossings]) a gap
 * is cleared round the front strand for as long as the two are close, and the
 * front strand is drawn again over it. That gives true over-and-under for any
 * number of strands with no seams, since nothing is cut into strips. Bright and
 * faint are separate butt-ended pieces, so they meet square to the thread.
 */
private object ThreadFace {
    /** A used stretch of thread: still there, only faint. */
    private const val SPENT = 0.2f
    /** Stale data keeps its shape but loses its color, as on the other faces. */
    private const val STALE = 0.45f
    /** How far (a share of the braid) a crossing's bright redraw overlaps the strand either side. */
    private const val SEAM = 0.003

    /**
     * [bead] and [beadCut] are the bead's radius and the gap round it, as
     * multiples of the width (0 is none). [fadeIn], on a ring, is how far (a
     * share of the turn) the bright stretch takes to fade in from the faint one
     * at 12 o'clock, so the start isn't a hard edge.
     */
    private data class Look(
        val width: Float, val ghostWidth: Float, val ghostAlpha: Float, val moat: Float, val ghostMoat: Float,
        val bead: Float = 0f, val beadCut: Float = 0f, val fadeIn: Double = 0.0,
    ) {
        companion object {
            /** [fancy] is the card's: a fine ghost for the used stretch, a narrower gap round it, and beads. */
            fun of(sw: Float, fancy: Boolean) =
                if (fancy) Look(sw, sw * 0.55f, 0.32f, sw * 1.9f, sw * 1.3f, bead = 1.4f, beadCut = 2.3f)
                // A gap a third of a strand each side: wide enough to read as a crossing,
                // narrow enough that four strands in one band don't fall into dashes.
                else Look(sw, sw, SPENT, sw * 1.7f, sw * 1.7f)

            /** The orbit's: the card's look with quieter beads and a quick fade-in at the top. */
            fun orbit(sw: Float) = of(sw, fancy = true).copy(bead = 0.95f, beadCut = 1.6f, fadeIn = 0.07)
        }
    }

    private fun line(color: Int, width: Float, alpha: Float) = stroke(color, width, alpha, round = false).apply { strokeJoin = Paint.Join.ROUND }
    private fun gap(width: Float) = Paint(CLEAR).apply { strokeWidth = width; strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.ROUND }

    /**
     * One braid: strand `i` at `point(i, u)` for u in 0..1 (any u when
     * [closed], a ring), twisted by `twist(u)`, the braid's angle, a straight
     * line in u.
     */
    private fun braid(
        strands: List<ThreadStrand>, acc: Canvas, look: Look, closed: Boolean,
        twist: (Double) -> Double, point: (Int, Double) -> Pair<Float, Float>,
    ) {
        val count = strands.size
        if (count == 0) return
        val alphas = strands.map { if (it.stale) STALE else 1f }
        val splits = strands.map { (it.left ?: 0f).coerceIn(0f, 1f).toDouble() }

        /** a..b in bright and used pieces: (from, to, bright). */
        fun pieces(i: Int, a: Double, b: Double): List<Triple<Double, Double, Boolean>> {
            val cuts = mutableListOf(a, b)
            if (closed) for (k in floor(a).toInt()..ceil(b).toInt()) cuts += listOf(k.toDouble(), k + splits[i])
            else cuts += splits[i]
            val sorted = cuts.filter { it in a..b }.distinct().sorted()
            return sorted.zipWithNext().mapNotNull { (from, to) ->
                if (to - from <= 1e-6) return@mapNotNull null
                val mid = (from + to) / 2
                Triple(from, to, (if (closed) mid - floor(mid) else mid) < splits[i])
            }
        }
        fun path(i: Int, a: Double, b: Double) = Path().apply {
            val n = ((b - a) / 0.002).toInt().coerceAtLeast(2)
            for (k in 0..n) {
                val (x, y) = point(i, a + (b - a) * k / n)
                if (k == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        val fading = closed && look.fadeIn > 0
        /** A bright piece split where it fades in: (from, to, fading). */
        fun spans(from: Double, to: Double): List<Triple<Double, Double, Boolean>> {
            if (!fading) return listOf(Triple(from, to, false))
            val cuts = mutableListOf(from, to)
            for (k in floor(from).toInt()..ceil(to).toInt()) cuts += listOf(k.toDouble(), k + look.fadeIn)
            return cuts.filter { it in from..to }.distinct().sorted().zipWithNext().filter { it.second - it.first > 1e-6 }.map { (x, y) ->
                val mid = (x + y) / 2
                Triple(x, y, mid - floor(mid) < look.fadeIn)
            }
        }
        // Eased, so it's quick but not a step.
        val stops = FloatArray(9) { it / 8f }
        fun fadePaint(i: Int, start: Double): Paint {
            val (x0, y0) = point(i, start)
            val (x1, y1) = point(i, start + look.fadeIn)
            val colors = IntArray(stops.size) { k ->
                val x = stops[k]
                val a = (x * x * (3 - 2 * x)) * alphas[i]
                (strands[i].color and 0xFFFFFF) or ((a * 255).roundToInt().coerceIn(0, 255) shl 24)
            }
            return line(Color.WHITE, look.width, 1f).apply { shader = LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP) }
        }
        fun draw(i: Int, a: Double, b: Double, clearing: Boolean) {
            for ((from, to, bright) in pieces(i, a, b)) {
                for ((x, y, fade) in if (bright) spans(from, to) else listOf(Triple(from, to, false))) {
                    val p = path(i, x, y)
                    if (clearing) acc.drawPath(p, gap(if (bright) look.moat else look.ghostMoat))
                    if (fade) {
                        // One stroke with a gradient along the fade, over the faint thread,
                        // so the strand carries on and brightens without seams.
                        acc.drawPath(p, line(strands[i].color, look.ghostWidth, alphas[i] * look.ghostAlpha))
                        acc.drawPath(p, fadePaint(i, floor((x + y) / 2)))
                    } else {
                        // Over a cleared gap, the bright redraw runs a hair past it each
                        // end: two anti-aliased butt edges on one line leave a seam.
                        val q = if (clearing && bright) path(i, x - SEAM, y + SEAM) else p
                        acc.drawPath(q, if (bright) line(strands[i].color, look.width, alphas[i]) else line(strands[i].color, look.ghostWidth, alphas[i] * look.ghostAlpha))
                    }
                }
            }
        }

        for (i in 0 until count) draw(i, 0.0, 1.0, clearing = false)

        // Each crossing: how far either side the two stay within a gap's reach,
        // then the front strand again over a cleared gap for that stretch.
        val t0 = twist(0.0)
        val t1 = twist(1.0)
        val reach = (max(look.moat, look.ghostMoat) + look.width) / 2 * 1.15f
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
            if (!closed) {
                a = a.coerceAtLeast(0.0)
                b = b.coerceAtMost(1.0)
            }
            draw(c.front, a, b, clearing = true)
        }

        // A bead where each thread's week runs out: "you are here", cut clear of
        // what's under it. Every cut first, then every bead, so two beads that
        // meet both stay whole.
        if (look.bead <= 0f) return
        val beads = (0 until count).filter { splits[it] > 0 && splits[it] < 1 }
        beads.forEach { i -> val (x, y) = point(i, splits[i]); acc.drawCircle(x, y, look.width * look.beadCut, Paint(CLEAR).apply { style = Paint.Style.FILL }) }
        beads.forEach { i -> val (x, y) = point(i, splits[i]); acc.drawCircle(x, y, look.width * look.bead, fill(strands[i].color, alphas[i])) }
    }

    /** The 4x1 and the card's foot: a low, long wave, left to right. */
    fun straight(strands: List<ThreadStrand>, l: Layers, fancy: Boolean) {
        val count = strands.size
        if (count < 2) return
        val w = l.w.toFloat()
        val h = l.h.toFloat()
        val pad = h * 0.16f
        // The card's strands are finer than the widget's.
        val sw = h * (if (fancy) 0.049f else 0.06f)
        val x0 = pad + sw
        val x1 = w - pad - sw
        // A low, long wave: the threads cross at a shallow angle (about 28°).
        val amp = (h / 2 - pad - sw / 2) * (if (fancy) 0.8f else 0.6f)
        if (x1 <= x0 || amp <= 0f) return
        // A whole number of half twists.
        val halves = ((x1 - x0) / (amp * 6f)).roundToInt().coerceAtLeast(1)
        fun t(u: Double) = PI * halves * u
        braid(strands, l.accCanvas, Look.of(sw, fancy), closed = false, twist = ::t) { i, u ->
            (x0 + u.toFloat() * (x1 - x0)) to (h / 2 + amp * Braid.offset(i, count, t(u)).toFloat())
        }
    }

    /** Concentric rings, outermost first, filling the square. */
    fun orbit(rings: List<List<ThreadStrand>>, l: Layers) {
        val s = min(l.w, l.h).toFloat()
        val cx = l.w / 2f
        val cy = l.h / 2f
        val sw = s * 0.028f
        val radii = floatArrayOf(0.355f, 0.205f)
        val amps = floatArrayOf(0.05f, 0.04f)
        rings.take(radii.size).forEachIndexed { k, strands ->
            val count = strands.size
            if (count == 0) return@forEachIndexed
            val radius = s * radii[k]
            val amp = s * amps[k]
            // A lone strand has nothing to twist round: a plain circle.
            val a = if (count > 1) amp else 0f
            // Whole twists per turn, so every strand closes on itself.
            val twists = (2 * PI * radius / (12 * amp.coerceAtLeast(1f))).roundToInt().coerceAtLeast(1)
            fun t(u: Double) = 2 * PI * twists * u
            braid(strands, l.accCanvas, Look.orbit(sw), closed = true, twist = ::t) { i, u ->
                val r = radius + a * Braid.offset(i, count, t(u)).toFloat()
                val angle = -PI / 2 + 2 * PI * u
                (cx + r * cos(angle).toFloat()) to (cy + r * sin(angle).toFloat())
            }
        }
    }
}
