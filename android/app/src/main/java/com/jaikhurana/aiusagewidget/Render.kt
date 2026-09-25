package com.jaikhurana.aiusagewidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import java.time.Duration
import kotlin.math.ceil
import kotlin.math.cos
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

enum class Style { RING, MATRIX, DASH }

object Render {
    const val ORANGE = 0xFFD97757.toInt()
    const val YELLOW = 0xFFFFC53D.toInt()
    const val GREY = 0xFF8C8C8C.toInt()
    /** Stale data keeps its shape but loses its color. */
    private const val STALE_ALPHA = 115

    fun draw(ctx: Context, style: Style, look: Look, w: Int, h: Int): Layers {
        val l = Layers(w.coerceAtLeast(1), h.coerceAtLeast(1))
        if (look.stale) l.accCanvas.saveLayerAlpha(null, STALE_ALPHA)
        when (style) {
            Style.RING -> RingFace.draw(look, l)
            Style.MATRIX -> MatrixFace.draw(look, l)
            Style.DASH -> DashFace.draw(ctx, look, l)
        }
        if (look.stale) l.accCanvas.restore()
        return l
    }
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
                if (look.sessionLeft > 0) acc.drawArc(ring, -90f, 360f * look.sessionLeft / 100, false, stroke(Render.ORANGE, sw))
                if (look.weekLeft > 0) acc.drawArc(pie, -90f, 360f * look.weekLeft / 100, true, fill(Render.ORANGE))
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
                if (look.weekLeft > 0) acc.drawArc(pie, -90f, 360f * look.weekLeft / 100, true, fill(Render.ORANGE, 0.22f))
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
                acc.drawArc(ring, 0f, 360f, false, stroke(Render.ORANGE, sw))
                acc.fitText("GO!", RectF(cx - pieR * 1.05f, cy - pieR * 0.55f, cx + pieR * 1.05f, cy + pieR * 0.55f), headline(Render.ORANGE))
            }
        }
    }
}

/**
 * A 2x2 LED panel: the session's headroom as a big dot-matrix number, the week
 * as a bar of dots along the bottom. Every cell is a dot, lit or not.
 */
private object MatrixFace {
    private const val COLS = 38
    private const val OFF = 0
    private const val INK = 1
    private const val ORANGE = 2
    private const val YELLOW = 3
    private const val GREY = 4
    private const val BLANK = 5

    fun draw(look: Look, l: Layers) {
        // Inset, so the panel sits inside the card's rounded corners.
        val inset = min(l.w, l.h) * 0.08f
        val aw = l.w - 2 * inset
        val ah = l.h - 2 * inset
        val pitch = min(aw / COLS, ah / 26f)
        val cols = (aw / pitch).toInt()
        val rows = (ah / pitch).toInt()
        val ox = (l.w - cols * pitch) / 2
        val oy = (l.h - rows * pitch) / 2
        val grid = Array(rows) { IntArray(cols) }

        fun put(text: String, col: Int, row: Int, v: Int, scale: Int = 1) =
            DotFont.forEachDot(text, scale) { c, r ->
                if (row + r in 0 until rows && col + c in 0 until cols) grid[row + r][col + c] = v
            }
        fun putRight(text: String, right: Int, row: Int, v: Int) = put(text, right - DotFont.width(text) + 1, row, v)
        fun putCentered(text: String, row: Int, v: Int) = put(text, (cols - DotFont.width(text)) / 2, row, v)

        // Top labels, a big middle, a labelled bar: 7 + gap + 14 + gap + 7 rows.
        val gap = ((rows - 2 - 28) / 2).coerceIn(0, 4)
        val top = ((rows - (28 + 2 * gap)) / 2).coerceAtLeast(0)
        val mid = top + 7 + gap
        val bottom = mid + 14 + gap
        // The big middle is set in type, not dots: see [Fonts].
        var big: Pair<String, Int>? = null
        var slashed = false
        fun bar(label: String, labelV: Int, percent: Int, v: Int) {
            put(label, 1, bottom, labelV)
            val x0 = 1 + DotFont.width(label) + 2
            val x1 = cols - 2
            val lit = ((x1 - x0 + 1) * percent / 100f).roundToInt()
            for (c in x0..x1) for (r in bottom + 2..bottom + 4) {
                if (r in 0 until rows) grid[r][c] = if (c - x0 < lit) v else OFF
            }
        }

        when (look.mode) {
            Mode.UNPAIRED -> big = "PAIR" to INK
            Mode.WAITING -> big = "…" to INK
            Mode.NORMAL -> {
                put("5H", 1, top, INK)
                putRight(shortCountdown(look.now, look.session?.resetsAt), cols - 2, top, INK)
                big = look.sessionLeft.toString() to ORANGE
                bar("7D", INK, look.weekLeft, ORANGE)
            }
            Mode.SESSION_OUT -> {
                put("5H", 1, top, YELLOW)
                putRight("OUT", cols - 2, top, YELLOW)
                big = (look.countdownTo?.let { countdownText(look.now, it) } ?: "OUT") to YELLOW
                bar("7D", INK, look.weekLeft, ORANGE)
            }
            Mode.WEEK_OUT -> {
                put("7D", 1, top, GREY)
                putRight("OUT", cols - 2, top, GREY)
                big = (look.countdownTo?.let { countdownText(look.now, it) } ?: "--") to GREY
                bar("5H", GREY, 0, GREY)
                slashed = true
            }
            Mode.GO -> {
                putCentered("CLAUDE", top, INK)
                big = "GO!" to ORANGE
                bar("7D", INK, look.weekLeft, ORANGE)
            }
        }

        big?.let { (text, v) ->
            val canvas = if (v == INK) l.inkCanvas else l.accCanvas
            val color = when (v) {
                ORANGE -> Render.ORANGE
                YELLOW -> Render.YELLOW
                GREY -> Render.GREY
                else -> Color.WHITE
            }
            val box = RectF(ox + 1.5f * pitch, oy + (mid + 1) * pitch, ox + (cols - 1.5f) * pitch, oy + (mid + 13) * pitch)
            val ink = canvas.fitText(text, box, headline(color))
            // Switch off the dots under the type, one cell of margin around it.
            for (r in 0 until rows) for (c in 0 until cols) {
                val x = ox + (c + 0.5f) * pitch
                val y = oy + (r + 0.5f) * pitch
                if (x > ink.left - pitch && x < ink.right + pitch && y > ink.top - pitch && y < ink.bottom + pitch) grid[r][c] = BLANK
            }
        }
        if (slashed) {
            // A diagonal of lit dots through the whole panel, with a dark moat
            // cut through the type as well.
            CLEAR.strokeWidth = pitch * 4.6f
            l.accCanvas.drawLine(ox + pitch / 2, oy + pitch / 2, ox + (cols - 0.5f) * pitch, oy + (rows - 0.5f) * pitch, CLEAR)
            for (c in 0 until cols) {
                val y = c * (rows - 1f) / (cols - 1)
                for (r in 0 until rows) {
                    val d = kotlin.math.abs(r - y)
                    if (d <= 0.75f) grid[r][c] = INK else if (d <= 2.3f) grid[r][c] = BLANK
                }
            }
        }

        val rad = pitch * 0.36f
        val off = ink(0.09f)
        val on = ink()
        val paints = mapOf(ORANGE to fill(Render.ORANGE), YELLOW to fill(Render.YELLOW), GREY to fill(Render.GREY))
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = ox + (c + 0.5f) * pitch
            val y = oy + (r + 0.5f) * pitch
            when (val v = grid[r][c]) {
                BLANK -> {}
                OFF -> l.inkCanvas.drawCircle(x, y, rad, off)
                INK -> l.inkCanvas.drawCircle(x, y, rad, on)
                else -> l.accCanvas.drawCircle(x, y, rad, paints.getValue(v))
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
            else -> Render.ORANGE
        }

        // Header: the one place for Ndot-style dots, as a small accent.
        acc.drawCircle(pad + 1.6f * u, pad + 2.6f * u, 1.7f * u, fill(accent))
        ink.dots("CLAUDE", pad + 5.5f * u, pad, 0.75f * u, ink(0.75f))

        // The big number and what it means.
        val (big, caption, bigColor) = when (look.mode) {
            Mode.UNPAIRED -> Triple("PAIR", "TAP TO SET UP", Color.WHITE)
            Mode.WAITING -> Triple("...", "FETCHING", Color.WHITE)
            Mode.NORMAL ->
                if (look.sessionLeft <= look.weekLeft) Triple("${look.sessionLeft}", "% SESSION LEFT", Render.ORANGE)
                else Triple("${look.weekLeft}", "% WEEK LEFT", Render.ORANGE)
            Mode.SESSION_OUT -> Triple(look.countdownTo?.let { countdownText(look.now, it) } ?: "OUT", "UNTIL THE 5H RESET", Render.YELLOW)
            Mode.WEEK_OUT -> Triple(look.countdownTo?.let { countdownText(look.now, it) } ?: "OUT", "WEEKLY LIMIT HIT", Render.GREY)
            Mode.GO -> Triple("GO!", "CLAUDE'S BACK", Render.ORANGE)
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
        row(ctx, look, l, "5H", look.session, Look.SESSION, 30 * u, x0, x1, u, ::text)
        row(ctx, look, l, "7D", look.week, Look.WEEK, 70 * u, x0, x1, u, ::text)

        if (look.mode == Mode.WEEK_OUT) {
            val (ax, ay, bx, by) = listOf(l.w * 0.04f, l.h * 0.08f, l.w * 0.96f, l.h * 0.92f)
            CLEAR.strokeWidth = 5.5f * u
            ink.drawLine(ax, ay, bx, by, CLEAR)
            acc.drawLine(ax, ay, bx, by, CLEAR)
            acc.drawLine(ax, ay, bx, by, stroke(Render.GREY, 1.8f * u))
        }
    }

    private fun row(
        ctx: Context, look: Look, l: Layers, label: String, e: Effective?, length: Duration,
        y: Float, x0: Float, x1: Float, u: Float, text: (Float, Float) -> Paint,
    ) {
        val ink = l.inkCanvas
        val acc = l.accCanvas
        val paired = look.mode != Mode.UNPAIRED && look.mode != Mode.WAITING
        val remaining = e?.remaining ?: 100
        val timerRow = look.mode == Mode.SESSION_OUT && label == "5H"
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
            else -> Render.ORANGE
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
            !paired -> Triple(0, fill(Render.ORANGE), ink(0.12f))
            timerRow -> Triple(ceil(n * look.sessionTimerFraction()).toInt(), fill(Render.YELLOW), fill(Render.YELLOW, 0.22f))
            look.mode == Mode.WEEK_OUT -> Triple(0, fill(Render.GREY), fill(Render.GREY, 0.45f))
            else -> Triple((n * remaining / 100f).roundToInt(), fill(Render.ORANGE), ink(0.13f))
        }
        for (i in 0 until n) {
            val cx = bx0 + (i + 0.5f) * spacing
            if (i < lit) acc.drawCircle(cx, y, r, on)
            else (if (off.color == Color.WHITE) ink else acc).drawCircle(cx, y, r, off)
        }

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
