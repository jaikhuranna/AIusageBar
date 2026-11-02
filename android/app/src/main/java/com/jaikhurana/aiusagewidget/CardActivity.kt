package com.jaikhurana.aiusagewidget

import android.content.Intent
import android.text.format.DateFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * What a widget tap opens: a card floating over the home screen, like Nothing's
 * expanded widgets. Opening it is a refresh (`GET /usage`, which the bridge
 * answers with fresh numbers), and it has its own Refresh button.
 */
class CardActivity : ComponentActivity() {
    private var busy by mutableStateOf(false)

    /** False once dismissed: the card animates away, then the activity finishes. */
    private var open by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Belt and braces with the launch options in CardReceiver: no system
        // open/close transition, only the card's own animation.
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        val from = intent.sourceBounds
        // Debug builds only: draw made-up pools, to judge the design without live accounts.
        val fixture = intent.getBooleanExtra(EXTRA_FIXTURE, false)
        val blur = windowManager.isCrossWindowBlurEnabled
        val maxBlur = MAX_BLUR_DP * resources.displayMetrics.density
        setContent {
            // Opening: the card pops out of the tapped widget on its own, then
            // the backdrop (dim and blur) fades in behind it. Closing: both
            // fade out together.
            val card = remember { Animatable(0f) }
            val backdrop = remember { Animatable(0f) }
            LaunchedEffect(open) {
                if (open) {
                    card.animateTo(1f, tween(220))
                    backdrop.animateTo(1f, tween(250))
                } else {
                    launch { backdrop.animateTo(0f, tween(180)) }
                    card.animateTo(0f, tween(180))
                    finish()
                }
            }
            if (blur) LaunchedEffect(Unit) {
                snapshotFlow { (maxBlur * backdrop.value).toInt() }
                    .collect { r -> window.attributes = window.attributes.apply { blurBehindRadius = r } }
            }
            CardScreen(from, card.value, backdrop.value, fixture, busy, onRefresh = ::refresh, onOpenApp = ::openApp, onDismiss = { open = false })
        }
        onBackPressedDispatcher.addCallback(this) { open = false }
        if (savedInstanceState == null) refresh()
    }

    // A second tap while the card is up lands here (singleTask).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        refresh()
    }

    private fun refresh() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                Sync.run(this@CardActivity, manual = true, force = true)
            } finally {
                busy = false
            }
        }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun finish() {
        super.finish()
        // The card has already animated itself away; no window animation on top.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val MAX_BLUR_DP = 24f
        const val EXTRA_FIXTURE = "fixture"
    }
}

@Composable
private fun CardScreen(from: Rect?, card: Float, backdrop: Float, fixture: Boolean, busy: Boolean, onRefresh: () -> Unit, onOpenApp: () -> Unit, onDismiss: () -> Unit) {
    // Grow out of the widget that was tapped.
    var origin by remember { mutableStateOf(TransformOrigin.Center) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f * backdrop))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)
            .safeDrawingPadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .onGloballyPositioned { c ->
                    if (from != null) {
                        val b = c.boundsInWindow()
                        origin = TransformOrigin(
                            ((from.exactCenterX() - b.left) / b.width).coerceIn(0f, 1f),
                            ((from.exactCenterY() - b.top) / b.height).coerceIn(0f, 1f),
                        )
                    }
                }
                .graphicsLayer {
                    transformOrigin = origin
                    val s = 0.6f + 0.4f * card
                    scaleX = s
                    scaleY = s
                    alpha = card
                }
                .clip(RoundedCornerShape(32.dp))
                .background(colorResource(R.color.widget_bg))
                .clickable(remember { MutableInteractionSource() }, null) {} // taps on the card don't dismiss it
                .padding(horizontal = 22.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Card(fixture, busy, onRefresh, onOpenApp)
        }
    }
}

/**
 * The essentials, and nothing that scrolls: each family's session and week
 * as a share of all its accounts, with what the next reset brings back and a
 * slim bar split per account; then every account's week as twisted threads.
 * The detail (pace, each account's numbers) is in the app.
 */
@Composable
private fun Card(fixture: Boolean, busy: Boolean, onRefresh: () -> Unit, onOpenApp: () -> Unit) {
    val ctx = LocalContext.current
    val fg = colorResource(R.color.widget_elements)
    val changes by Store.changes.collectAsState()
    val minute = minuteTicks()
    val store = remember(changes) { Store(ctx) }
    val now = remember(changes, minute) { Instant.now() }
    val views = remember(changes, minute, fixture) {
        PoolFamily.entries.map { f ->
            Fixtures.pool(f, now).takeIf { fixture }?.let { FamilyView.of(it, now.toEpochMilli() - 120_000, false, now) }
                ?: familyView(ctx, f, now)
        }
    }
    val strands = remember(views) { strandsOf(views, now) }
    var chart by remember { mutableStateOf(store.chartFamily) }

    views.forEach { v -> FamilySummary(v, now, fg) }
    SessionGraph(views.first { it.family == chart }, now, fg) { f -> chart = f; Store(ctx).chartFamily = f }
    Threads(strands, fg, 60.dp)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(Render.accent(store.selected)).copy(alpha = if (busy) 0.5f else 1f))
                .clickable(enabled = !busy, onClick = onRefresh)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Label(if (busy) "REFRESHING" else "REFRESH", Color.White, 1f)
        }
        Spacer(Modifier.weight(1f))
        val from = listOfNotNull(store.host?.let { "FROM ${it.uppercase()}" }, "FIXTURE".takeIf { fixture }).joinToString(" · ")
        if (from.isNotEmpty()) Label(from, fg, 0.35f, size = 10.sp)
        Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).clickable(onClick = onOpenApp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Label("OPEN APP", fg, 0.7f)
        }
    }
}

/**
 * One family's five-hour session over the last 12 hours, picked with a
 * Claude | Codex pill (remembered), drawn like the app's old pool chart: the
 * family's share left as a solid line over a soft fill in its colour, a dashed
 * projection that holds until each upcoming reset and steps up by what it
 * gives back ("+N%", in that account's colour), dotted gridlines with their
 * %, NOW with a dot, and every reset notched on the axis with its time. Static,
 * like the rest of the card: no animation, no glow.
 */
@Composable
private fun SessionGraph(view: FamilyView, now: Instant, fg: Color, onPick: (PoolFamily) -> Unit) {
    val ctx = LocalContext.current
    val chart = remember(view, now) { SessionChart.of(view.members, view.history, now) }
    val length = view.known.firstOrNull()?.sessionLength ?: Look.SESSION
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("${durationLabel(length)} · LAST ${SessionChart.BACK.toHours()}H", fg, 0.9f, size = 11.sp)
            Spacer(Modifier.weight(1f))
            FamilyToggle(view.family, fg, onPick)
        }
        val bg = colorResource(R.color.widget_bg)
        val measurer = rememberTextMeasurer()
        val small = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.06.em)
        val gainStyle = small.copy(fontWeight = FontWeight.Bold)
        val time = remember { DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(ctx)) "H:mm" else "h:mm") }
        val zone = remember { ZoneId.systemDefault() }
        val dim = if (view.offline) 0.45f else 1f
        val accent = Color(Render.memberColor(view.family, 0))
        Box(Modifier.fillMaxWidth().height(100.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val axis = listOf(1f to "100%", 0.5f to "50%", 0f to "0%").map { (v, t) -> v to measurer.measure(t, small) }
                val gutter = axis.maxOf { it.second.size.width } + 6.dp.toPx()
                val w = size.width - gutter
                val top = 15.dp.toPx() // room for NOW and the "+N%" over a step to full
                val notch = 4.dp.toPx()
                val bottomLabels = 14.dp.toPx()
                val base = size.height - bottomLabels - notch
                val plotH = base - top
                fun x(t: Instant) = gutter + chart.x(t, w)
                fun y(left: Float) = chart.y(left, top, plotH)
                // A reset the history can't pin to an account takes the family's colour, a shade fainter.
                fun colorOf(i: Int) = (if (i == SessionChart.UNKNOWN) accent.copy(alpha = 0.7f) else Color(Render.memberColor(view.family, i)))
                    .let { it.copy(alpha = it.alpha * dim) }
                val line = accent.copy(alpha = dim)
                val nowX = x(chart.now)

                // Dotted gridlines at full, half and empty, each with its %.
                axis.forEach { (v, label) ->
                    val gy = y(v)
                    var dx = gutter
                    while (dx <= size.width) { drawCircle(fg.copy(alpha = 0.2f), 0.7.dp.toPx(), Offset(dx, gy)); dx += 4.dp.toPx() }
                    drawText(label, fg.copy(alpha = 0.4f), Offset(gutter - 6.dp.toPx() - label.size.width, gy - label.size.height / 2f))
                }
                // A faint rule every three hours of the clock.
                val hours = generateSequence(chart.from.atZone(zone).withMinute(0).withSecond(0).withNano(0).plusHours(1)) { it.plusHours(1) }
                    .map { it.toInstant() }.takeWhile { !it.isAfter(chart.to) }
                    .filter { it.atZone(zone).hour % 3 == 0 }.toList()
                hours.forEach { h -> drawLine(fg.copy(alpha = 0.07f), Offset(x(h), top), Offset(x(h), base), 1.dp.toPx()) }

                // The fill: the line's colour fading to nothing at empty, fainter ahead.
                fun fill(points: List<ChartPoint>, strong: Float) {
                    val brush = Brush.verticalGradient(listOf(line.copy(alpha = strong * dim), line.copy(alpha = strong * 0.12f * dim)), startY = top, endY = base)
                    stretches(points).forEach { s ->
                        if (s.size < 2) return@forEach
                        val path = Path().apply {
                            moveTo(x(s.first().t), base)
                            s.forEach { lineTo(x(it.t), y(it.left)) }
                            lineTo(x(s.last().t), base)
                            close()
                        }
                        drawPath(path, brush)
                    }
                }
                fill(chart.line, 0.55f)
                fill(chart.projection, 0.2f)

                drawLine(fg.copy(alpha = 0.35f), Offset(nowX, top - 2.dp.toPx()), Offset(nowX, base), 1.dp.toPx())

                val solid = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                stretches(chart.line).forEach { s ->
                    drawPath(Path().apply { s.forEachIndexed { k, p -> if (k == 0) moveTo(x(p.t), y(p.left)) else lineTo(x(p.t), y(p.left)) } }, line, style = solid)
                }
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                val dashed = Stroke(1.6.dp.toPx(), cap = StrokeCap.Butt, join = StrokeJoin.Round, pathEffect = dash)
                if (chart.projection.size > 1) {
                    drawPath(Path().apply { chart.projection.forEachIndexed { k, p -> if (k == 0) moveTo(x(p.t), y(p.left)) else lineTo(x(p.t), y(p.left)) } }, line.copy(alpha = 0.85f * dim), style = dashed)
                }

                // Each reset: its step in its account's colour, a notch on the axis.
                val steps = chart.resets.map { r -> r to stepOf(if (r.upcoming) chart.projection else chart.line, r.at) }
                steps.forEach { (r, step) ->
                    val c = colorOf(r.color)
                    val rx = x(r.at)
                    step?.let { (lo, hi) ->
                        if (r.color != SessionChart.UNKNOWN) drawLine(c, Offset(rx, y(lo)), Offset(rx, y(hi)), (if (r.upcoming) 1.6f else 2f).dp.toPx(), cap = StrokeCap.Round, pathEffect = if (r.upcoming) dash else null)
                    }
                    drawLine(c.copy(alpha = c.alpha * (if (r.upcoming) 1f else 0.65f)), Offset(rx, base + 1.dp.toPx()), Offset(rx, base + notch), 2.dp.toPx())
                }

                // NOW: a dot on the line, cut clear of it.
                chart.current?.let { v ->
                    drawCircle(bg, 5.dp.toPx(), Offset(nowX, y(v)))
                    drawCircle(line, 3.25.dp.toPx(), Offset(nowX, y(v)))
                }

                // Above: NOW, then each step's "+N%" just over its top (what's coming first, then the newest past), as many as fit.
                val placed = mutableListOf<androidx.compose.ui.geometry.Rect>()
                fun place(text: androidx.compose.ui.text.TextLayoutResult, cx: Float, bottom: Float, color: Color) {
                    val left = (cx - text.size.width / 2f).coerceIn(gutter, size.width - text.size.width)
                    val t = (bottom - text.size.height).coerceAtLeast(0f)
                    val r = androidx.compose.ui.geometry.Rect(left - 3.dp.toPx(), t, left + text.size.width + 3.dp.toPx(), t + text.size.height)
                    if (placed.any { it.overlaps(r) }) return
                    placed += r
                    drawText(text, color, Offset(left, t))
                }
                place(measurer.measure("NOW", small), nowX, top - 3.dp.toPx(), fg.copy(alpha = 0.6f))
                (steps.filter { it.first.upcoming } + steps.filter { !it.first.upcoming }.reversed()).forEach { (r, step) ->
                    val gain = (r.gain * 100).roundToInt()
                    if (gain < 1 || step == null) return@forEach
                    val c = colorOf(r.color)
                    place(measurer.measure("+$gain%", gainStyle), x(r.at), y(step.second) - 3.dp.toPx(), c.copy(alpha = c.alpha * (if (r.upcoming) 1f else 0.7f)))
                }

                // Below: every reset's time in its account's colour (coming first, then the newest past), then the clock, as many as fit.
                val labels = chart.resets.filter { it.upcoming }.map { Triple(it.at, colorOf(it.color), true) } +
                    chart.resets.filter { !it.upcoming }.reversed().map { r -> Triple(r.at, colorOf(r.color).let { it.copy(alpha = it.alpha * 0.7f) }, true) } +
                    hours.map { Triple(it, fg.copy(alpha = 0.35f), false) }
                val measured = labels.map { measurer.measure(time.format(it.first.atZone(zone)), if (it.third) gainStyle else small) }
                val lefts = placeLabels(labels.mapIndexed { k, l -> x(l.first) - gutter to measured[k].size.width.toFloat() }, w, 4.dp.toPx(), nudge = 10.dp.toPx())
                lefts.forEachIndexed { k, left ->
                    left ?: return@forEachIndexed
                    drawText(measured[k], labels[k].second, Offset(gutter + left, base + notch + 2.dp.toPx()))
                }
            }
            if (chart.line.isEmpty()) {
                Text(view.error ?: "No ${view.family.title} numbers yet.", fg.copy(alpha = 0.45f), TextStyle(fontSize = 12.sp), Modifier.align(Alignment.Center))
            }
        }
    }
}

/** [points] split where the recording broke off. */
private fun stretches(points: List<ChartPoint>): List<List<ChartPoint>> =
    points.fold(mutableListOf<MutableList<ChartPoint>>()) { acc, p ->
        if (acc.isEmpty() || p.penUp) acc += mutableListOf(p) else acc.last() += p
        acc
    }

/** The step a reset at [at] makes in [points]: (from, to), or null where it isn't drawn (a gap in the recording). */
private fun stepOf(points: List<ChartPoint>, at: Instant): Pair<Float, Float>? {
    val i = points.indexOfFirst { it.t == at }
    val next = points.getOrNull(i + 1)?.takeIf { i >= 0 && it.t == at && !it.penUp } ?: return null
    return points[i].left to next.left
}

/** Claude | Codex, a small segmented pill: the picked one lit in its family's colour. */
@Composable
private fun FamilyToggle(selected: PoolFamily, fg: Color, onPick: (PoolFamily) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(fg.copy(alpha = 0.07f)).padding(2.dp).selectableGroup()) {
        PoolFamily.entries.forEach { f ->
            val on = f == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Color(Render.memberColor(f, 0)) else Color.Transparent)
                    .selectable(on, role = Role.Tab, onClick = { onPick(f) })
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Label(f.title.uppercase(), if (on) Color.White else fg, if (on) 1f else 0.5f, size = 10.sp)
            }
        }
    }
}

/** One family in two columns: session and week, each a number, its comeback and a slim bar. */
@Composable
private fun FamilySummary(view: FamilyView, now: Instant, fg: Color) {
    val headline = rememberHeadline()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FamilyHeader(view, fg, trailing = ageText(view, now))
        if (view.known.isEmpty()) {
            Text(view.error ?: "No numbers yet.", fg.copy(alpha = 0.55f), TextStyle(fontSize = 13.sp, lineHeight = 17.sp))
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Limit.entries.forEach { limit ->
                val meter = remember(view, limit, now) { Meter.of(view.members, limit, now) }
                val length = view.known.first().length(limit)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            when { meter.left == null -> "--"; meter.out -> "OUT"; else -> "${meter.left}%" },
                            if (meter.out) fg.copy(alpha = 0.45f) else fg,
                            TextStyle(fontFamily = headline, fontSize = 34.sp, lineHeight = 34.sp, letterSpacing = (-0.02).em),
                            Modifier.alignByBaseline(),
                        )
                        Spacer(Modifier.width(6.dp))
                        Label(durationLabel(length), fg, 0.45f, Modifier.alignByBaseline(), size = 11.sp)
                    }
                    SegmentBar(meter, view.family, fg, Modifier.fillMaxWidth().height(6.dp), slim = true)
                    ResetLine(comebackLine(meter, now), meter.comeback != null, fg.copy(alpha = 0.55f), 12.sp)
                }
            }
        }
    }
}
