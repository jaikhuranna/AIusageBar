package com.jaikhurana.aiusagewidget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Instant

/** Ticks on the wall-clock minute, the same instant the widgets' countdowns move. */
@Composable
fun minuteTicks(): Long {
    val minute by produceState(0L) { while (true) { delay(60_000 - System.currentTimeMillis() % 60_000); value++ } }
    return minute
}

@Composable
fun rememberHeadline(): FontFamily = remember { FontFamily(Fonts.headline) }

/** "UPDATED 2 MIN AGO", "OFFLINE · 3H AGO". */
fun ageText(view: FamilyView, now: Instant): String = when {
    view.fetchedAt == 0L -> if (view.offline) "CAN'T REACH THE DESKTOP" else "WAITING FOR THE DESKTOP"
    view.offline -> "OFFLINE · ${agoText(view.fetchedAt, now).uppercase()}"
    else -> agoText(view.fetchedAt, now).uppercase()
}

/** A family's name with its dot, and how many accounts pool in it. [trailing] sits at the right. */
@Composable
fun FamilyHeader(view: FamilyView, fg: Color, size: TextUnit = 13.sp, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Color(Render.memberColor(view.family, 0))))
        Spacer(Modifier.width(10.dp))
        Label(view.family.title.uppercase(), fg, 0.95f, size = size)
        val n = view.known.size
        if (n > 1) {
            Spacer(Modifier.width(8.dp))
            Label("$n ACCOUNTS", fg, 0.4f, size = 10.sp)
        }
        Spacer(Modifier.weight(1f))
        trailing?.let { Label(it, fg, 0.4f, size = 10.sp) }
    }
}

/**
 * One limit across a family, for the app: the share of the whole family left,
 * big; what the next reset brings back; the pace; a segment per account; and
 * the accounts themselves when there's more than one (or it has an email).
 */
@Composable
fun MeterCard(view: FamilyView, limit: Limit, now: Instant, fg: Color) {
    val headline = rememberHeadline()
    val meter = remember(view, limit, now) { Meter.of(view.members, limit, now) }
    val length = view.known.firstOrNull()?.length(limit) ?: if (limit == Limit.SESSION) Look.SESSION else Look.WEEK
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(androidx.compose.ui.res.colorResource(R.color.widget_bg)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(limit.title.uppercase(), fg, 0.9f)
            Label(" · ${durationLabel(length)}", fg, 0.4f)
            Spacer(Modifier.weight(1f))
            // Ahead is the one worth noticing: the timer yellow, deepened to amber on a light card.
            val warn = if (isSystemInDarkTheme()) Color(Render.YELLOW) else Color(0xFFB0700C)
            meter.pace?.let { Label(it.text.uppercase(), if (it == PaceMark.AHEAD) warn else fg, if (it == PaceMark.AHEAD) 1f else 0.45f, size = 10.sp) }
            if (meter.out) Label("LIMIT REACHED", fg, 0.45f, size = 10.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    when { meter.left == null -> "--"; meter.out -> "OUT"; else -> "${meter.left}%" },
                    if (meter.out) fg.copy(alpha = 0.45f) else fg,
                    TextStyle(fontFamily = headline, fontSize = 48.sp, lineHeight = 48.sp, letterSpacing = (-0.02).em),
                    Modifier.alignByBaseline(),
                )
                if (meter.left != null && !meter.out) {
                    Spacer(Modifier.width(6.dp))
                    Text("left", fg.copy(alpha = 0.55f), TextStyle(fontSize = 16.sp), Modifier.alignByBaseline())
                }
            }
            ResetLine(comebackLine(meter, now), meter.comeback != null, fg.copy(alpha = 0.6f), 13.sp)
        }
        SegmentBar(meter, view.family, fg, Modifier.fillMaxWidth().height(24.dp), numbered = meter.segments.size > 1)
        if (view.members.size > 1 || view.members.any { it.account != null }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                view.members.forEachIndexed { i, m -> AccountRow(m, i, limit, view.family, now, fg, headline) }
            }
        }
    }
}

/** "+28% in 3h 30m" (drawn after a reset arrow), or why there's nothing coming back. */
fun comebackLine(meter: Meter, now: Instant): String = when {
    meter.left == null -> "Not reported"
    meter.comeback != null -> meter.comeback.text(now)
    meter.left >= 100 -> "Untouched"
    else -> "Reset time unknown"
}

/** [text] after the reset arrow when [arrow], the way the Usage sheet writes a comeback. */
@Composable
fun ResetLine(text: String, arrow: Boolean, color: Color, size: TextUnit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (arrow) {
            ResetArrow(color, (size.value * 0.95f).dp)
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color, TextStyle(fontSize = size), oneLine = true)
    }
}

/** A small circular arrow: most of a ring, clockwise, ending in a solid head. */
@Composable
fun ResetArrow(color: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.12f
        val head = this.size.minDimension * 0.26f
        val r = this.size.minDimension / 2 - head * 0.6f
        val c = center
        val start = 30f
        val sweep = 270f
        drawArc(color, start, sweep, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Butt))
        val t = Math.toRadians((start + sweep).toDouble())
        val p = Offset(c.x + r * kotlin.math.cos(t).toFloat(), c.y + r * kotlin.math.sin(t).toFloat())
        val along = Offset(-kotlin.math.sin(t).toFloat(), kotlin.math.cos(t).toFloat()) // clockwise
        val out = Offset(kotlin.math.cos(t).toFloat(), kotlin.math.sin(t).toFloat())
        drawPath(Path().apply {
            moveTo(p.x + along.x * head, p.y + along.y * head)
            lineTo(p.x + out.x * head * 0.85f, p.y + out.y * head * 0.85f)
            lineTo(p.x - out.x * head * 0.85f, p.y - out.y * head * 0.85f)
            close()
        }, color)
    }
}

@Composable
private fun AccountRow(m: Member, index: Int, limit: Limit, family: PoolFamily, now: Instant, fg: Color, headline: FontFamily) {
    val e = m.window(limit)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Badge(m.number, Color(Render.memberColor(family, index)), fg)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // Emails are lower case on the wire; caps would only make a long one longer.
            Label(m.name, fg, 0.85f, size = 14.sp, letterSpacing = 0.01f, oneLine = true)
            m.error?.let { Label(it, fg, 0.45f, size = 11.sp, letterSpacing = 0.01f, oneLine = true) }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            when { e == null -> "--"; e.remaining <= 0 -> "OUT"; else -> "${e.remaining}%" },
            fg.copy(alpha = if (e == null || e.remaining <= 0) 0.45f else 0.95f),
            TextStyle(fontFamily = headline, fontSize = 18.sp),
        )
        Spacer(Modifier.width(10.dp))
        val reset = e?.resetsAt?.takeIf { it.isAfter(now) }
        Box(Modifier.width(78.dp)) { if (reset != null) ResetLine(untilText(now, reset), true, fg.copy(alpha = 0.5f), 13.sp) }
    }
}

/** An account's number, in its own colour on a quiet tile. */
@Composable
fun Badge(number: Int, color: Color, fg: Color) {
    Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(fg.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
        Text("$number", color, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium))
    }
}

/**
 * One limit as a bar cut into one equal segment per account (every account is
 * the same 100%). Each is lit in its account's colour for what's left, with
 * a dot-matrix ghost where it's been spent, and a tick at how much of the
 * window is still to run: lit past the tick is under pace. [numbered] puts the
 * account's number in dots at the segment's start.
 */
@Composable
fun SegmentBar(meter: Meter, family: PoolFamily, fg: Color, modifier: Modifier, numbered: Boolean = false, slim: Boolean = false) {
    val bg = androidx.compose.ui.res.colorResource(R.color.widget_bg)
    Canvas(modifier) {
        val gap = (if (slim) 3.dp else 4.dp).toPx()
        val spans = segmentSpans(size.width, meter.segments.size.coerceAtLeast(1), gap)
        val r = if (slim) size.height / 2 else 7.dp.toPx()
        meter.segments.forEachIndexed { i, seg ->
            val span = spans[i]
            val w = span.endInclusive - span.start
            val shape = Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(span.start, 0f, span.endInclusive, size.height, CornerRadius(r)))
            }
            val color = Color(Render.memberColor(family, i))
            val litW = (seg.left ?: 0f).coerceIn(0f, 1f) * w
            clipPath(shape) {
                // The spent stretch is the widgets' dot-matrix: a grid, or one row of dots when slim.
                if (!slim) drawRect(fg.copy(alpha = 0.05f), Offset(span.start, 0f), Size(w, size.height))
                clipRect(span.start + litW, 0f, span.endInclusive, size.height) {
                    if (slim) dotGrid(span.start, w, fg.copy(alpha = 0.3f), pitch = size.height + 2.dp.toPx(), r = size.height * 0.36f)
                    else dotGrid(span.start, w, fg.copy(alpha = 0.22f))
                }
                if (litW > 0f) drawRect(color.copy(alpha = if (slim) 1f else 0.88f), Offset(span.start, 0f), Size(litW, size.height))
            }
            if (!slim) seg.timeLeft?.takeIf { seg.left != null }?.let { t ->
                val x = (span.start + t * w).coerceIn(span.start + 2.dp.toPx(), span.endInclusive - 2.dp.toPx())
                drawLine(fg, Offset(x, -2.dp.toPx()), Offset(x, size.height + 2.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
            }
            if (numbered) {
                val pitch = (size.height / 11f).coerceAtMost(2.2.dp.toPx())
                val textW = DotFont.width("${seg.number}") * pitch
                val x = span.start + 8.dp.toPx()
                val top = (size.height - DotFont.HEIGHT * pitch) / 2
                val onLit = litW >= x - span.start + textW + 2.dp.toPx()
                // Off the lit stretch, a clean patch so the number isn't lost in the texture or the tick.
                if (!onLit) clipPath(shape) {
                    drawRoundRect(bg, Offset(span.start, 0f), Size(x - span.start + textW + 6.dp.toPx(), size.height), CornerRadius(r))
                    drawRect(fg.copy(alpha = 0.05f), Offset(span.start, 0f), Size(x - span.start + textW + 6.dp.toPx(), size.height))
                }
                dotText("${seg.number}", x, top, pitch, if (onLit) Color.White else fg.copy(alpha = 0.75f))
            }
        }
    }
}

/** The spent stretch's texture: a fine grid of dots, the widgets' dot-matrix at small size. */
private fun DrawScope.dotGrid(left: Float, width: Float, color: Color, pitch: Float = 4.dp.toPx(), r: Float = 0.8.dp.toPx()) {
    var y = if (pitch > size.height) size.height / 2 else pitch / 2 + (size.height % pitch) / 2
    while (y < size.height) {
        var x = left + pitch / 2
        while (x < left + width) {
            drawCircle(color, r, Offset(x, y))
            x += pitch
        }
        y += pitch
    }
}

private fun DrawScope.dotText(text: String, left: Float, top: Float, pitch: Float, color: Color) {
    DotFont.forEachDot(text) { c, row -> drawCircle(color, pitch * 0.42f, Offset(left + (c + 0.5f) * pitch, top + (row + 0.5f) * pitch)) }
}

/** Every account's week as the 4x1 draws it, twisted together. Static: no reveal, no glow. */
@Composable
fun Threads(strands: List<ThreadStrand>, fg: Color, height: androidx.compose.ui.unit.Dp) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label("7D · ALL", fg, 0.9f, size = 11.sp)
        BoxWithConstraints(Modifier.fillMaxWidth().height(height)) {
            val px = with(LocalDensity.current) { constraints.maxWidth to height.roundToPx() }
            val layers = remember(strands, px) { Render.threads(strands, px.first, px.second, fancy = true) }
            Image(layers.accent.asImageBitmap(), "Weekly left, every account", Modifier.fillMaxSize())
        }
    }
}

fun durationLabel(d: java.time.Duration): String = when {
    d.toMinutes() % 1440 == 0L -> "${d.toDays()}D"
    d.toMinutes() % 60 == 0L -> "${d.toHours()}H"
    else -> "${d.toMinutes()}M"
}

/** Tracked caps, Nothing's label style. [oneLine] elides in the middle, which keeps an email's domain. */
@Composable
internal fun Label(
    text: String, color: Color, alpha: Float, modifier: Modifier = Modifier, size: TextUnit = 12.sp,
    letterSpacing: Float = 0.1f, oneLine: Boolean = false,
) = androidx.compose.material3.Text(
    text, modifier,
    style = TextStyle(color = color.copy(alpha = alpha), fontSize = size, fontWeight = FontWeight.Medium, letterSpacing = letterSpacing.em),
    maxLines = if (oneLine) 1 else Int.MAX_VALUE,
    overflow = if (oneLine) TextOverflow.MiddleEllipsis else TextOverflow.Clip,
)

@Composable
internal fun Text(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier, oneLine: Boolean = false) =
    androidx.compose.material3.Text(
        text, modifier, style = style.copy(color = color),
        maxLines = if (oneLine) 1 else Int.MAX_VALUE, overflow = if (oneLine) TextOverflow.Ellipsis else TextOverflow.Clip,
    )
