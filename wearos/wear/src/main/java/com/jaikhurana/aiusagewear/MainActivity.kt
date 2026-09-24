package com.jaikhurana.aiusagewear

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Alerts.ensureChannels(this)
        setContent { MaterialTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app counts as a manual refresh (throttled to once a minute).
        lifecycleScope.launch { Sync.run(this@MainActivity, manual = true) }
    }
}

private val ORANGE = Color(0xFFFF8A1F)
private val ORANGE_DEEP = Color(0xFF8A3A00)
private val TRACK = Color(0x40FF8A1F)
private val SOFT = Color(0xFFFFD3AE)
private val DIM = Color(0xFFB9987E)

@Composable
private fun App() {
    val version by Store.changes.collectAsState()
    val store = Store(LocalContext.current)
    // Re-read on every write; the store is small and in memory.
    @Suppress("UNUSED_EXPRESSION") version
    if (!store.paired) PairingScreen() else Paired(store)
}

/** Swipe left: Session → Weekly → Setup. */
@Composable
private fun Paired(store: Store) {
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val pager = rememberPagerState { 3 }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = pager) { page ->
            when (page) {
                0 -> LimitPage(store, "five_hour")
                1 -> LimitPage(store, "seven_day")
                else -> SetupPage(store)
            }
        }
        // The dots show while you swipe, then get out of the way.
        var dotsShown by remember { mutableStateOf(true) }
        LaunchedEffect(pager.currentPage, pager.isScrollInProgress) {
            dotsShown = true
            if (!pager.isScrollInProgress) {
                delay(1_500)
                dotsShown = false
            }
        }
        val dotsAlpha by animateFloatAsState(if (dotsShown) 1f else 0f, tween(durationMillis = 400), label = "dots")
        PageDots(current = pager.currentPage, count = 3, alpha = dotsAlpha)
    }
}

/** One limit, Samsung Health style: glow from below, a ring open at the bottom, one big number. */
@Composable
private fun LimitPage(store: Store, key: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Instant.now()
        }
    }
    var refreshing by remember { mutableStateOf(false) }
    val snap = store.snapshot()
    val window = if (key == "seven_day") snap?.sevenDay else snap?.fiveHour
    val e = window?.effective(now)
    val comeback = snap?.let { Plan.comeback(it, now) }
    val name = if (key == "seven_day") "Weekly" else "Session"

    val word = if (key == "seven_day") "this week" else "this session"
    // One big number, one line saying what it is, and at most one more line.
    val (big, what, detail) = when {
        store.lastError == Store.ERROR_UNPAIRED -> Triple("—", "This watch was unpaired", null)
        snap == null -> Triple("…", if (store.lastError == Store.ERROR_OFFLINE) "Can't reach the desktop" else "Waiting for the desktop", null)
        e == null -> Triple("—", "$name not reported", null)
        e.unconfirmedReset -> Triple("100%", "left $word", "reset · not confirmed yet")
        e.remaining <= 0 && comeback != null -> Triple(untilText(now, comeback), "until Claude's back", "at ${clockText(ctx, comeback, now)}")
        e.remaining <= 0 -> Triple("0%", "left $word", null)
        else -> Triple("${e.remaining}%", "left $word", e.resetsAt?.let { "resets in ${untilText(now, it)}" })
    }
    // Only when it matters: fresh data needs no timestamp.
    val stale = store.fetchedAt > 0 && now.toEpochMilli() - store.fetchedAt > 15 * 60_000
    val status = when {
        refreshing -> null // the pill already says so
        store.lastError == Store.ERROR_OFFLINE -> "desktop offline · ${agoText(store.fetchedAt, now)}"
        stale -> "updated ${agoText(store.fetchedAt, now)}"
        else -> null
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            // Orange glow rising from the bottom edge.
            drawRect(Color.Black)
            drawRect(
                Brush.radialGradient(
                    colors = listOf(ORANGE_DEEP, ORANGE_DEEP.copy(alpha = 0.35f), Color.Transparent),
                    center = Offset(size.width / 2, size.height * 1.08f),
                    radius = size.height * 0.95f,
                ),
            )
            ring(e?.remaining)
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(18.dp))
            Text(big, color = Color.White, fontSize = if (big.length > 5) 40.sp else 54.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(what, color = SOFT, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 2)
            detail?.let { Text(it, color = DIM, fontSize = 13.sp, maxLines = 1) }
            status?.let { Text(it, color = DIM, fontSize = 11.sp, maxLines = 1) }
            Spacer(Modifier.height(8.dp))
            val unpaired = store.lastError == Store.ERROR_UNPAIRED
            Pill(
                label = when {
                    unpaired -> "Pair again"
                    refreshing -> "Refreshing…"
                    else -> "↻  Refresh"
                },
                enabled = !refreshing,
            ) {
                if (unpaired) {
                    unpair(ctx, store)
                } else {
                    refreshing = true
                    scope.launch {
                        Sync.run(ctx, manual = true, force = true)
                        refreshing = false
                        now = Instant.now()
                    }
                }
            }
        }
    }
}

/** The reference's "+ 250 ml" button. */
@Composable
private fun Pill(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(118.dp)
            .height(42.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A thick ring open at the bottom (270°, from about 7:30 round to 4:30), filled
 * clockwise from the bottom-left with what's left.
 */
private fun DrawScope.ring(remaining: Int?) {
    val stroke = 15.dp.toPx()
    val inset = 5.dp.toPx() + stroke / 2
    val topLeft = Offset(inset, inset)
    val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
    val style = Stroke(width = stroke, cap = StrokeCap.Round)
    drawArc(TRACK, RING_START, RING_SPAN, useCenter = false, topLeft = topLeft, size = arcSize, style = style)
    val left = (remaining ?: 0).coerceIn(0, 100)
    if (left > 0) {
        drawArc(ORANGE, RING_START, RING_SPAN * left / 100f, useCenter = false, topLeft = topLeft, size = arcSize, style = style)
    }
}

private const val RING_START = 135f // 0° is 3 o'clock, clockwise
private const val RING_SPAN = 270f

/** Which of the three pages you're on, in the ring's gap at the bottom. */
@Composable
private fun PageDots(current: Int, count: Int, alpha: Float) {
    if (alpha <= 0f) return
    Canvas(Modifier.fillMaxSize()) {
        val r = 3.dp.toPx()
        val gap = 10.dp.toPx()
        val y = size.height - 9.dp.toPx()
        val left = size.width / 2 - gap * (count - 1) / 2
        repeat(count) { i ->
            val a = if (i == current) 1f else 0.35f
            drawCircle(Color.White.copy(alpha = a * alpha), r, Offset(left + i * gap, y))
        }
    }
}

@Composable
private fun SetupPage(store: Store) {
    val ctx = LocalContext.current
    val snap = store.snapshot()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ScalingLazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            item { Text("Setup", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            item { Text(store.host?.let { "Paired with $it" } ?: "Paired", color = SOFT, fontSize = 13.sp) }
            item { Text(store.baseUrl ?: "", color = DIM, fontSize = 10.sp, textAlign = TextAlign.Center) }
            store.lanUrl?.let { item { Text("at home: ${it.substringAfter("://")}", color = DIM, fontSize = 10.sp) } }
            item { Spacer(Modifier.size(6.dp)) }
            item { Text("API-cost equivalent", fontWeight = FontWeight.Medium) }
            item { Text("an estimate, not a bill", color = DIM, fontSize = 11.sp) }
            if (snap != null) {
                item { Text("today   $%.2f".format(snap.costToday)) }
                item { Text("session $%.2f".format(snap.costSession)) }
                item { Text("week    $%.2f".format(snap.costWeek)) }
            }
            item { Spacer(Modifier.size(6.dp)) }
            item {
                Button(onClick = { unpair(ctx, store) }, modifier = Modifier.fillMaxWidth()) { Text("Unpair") }
            }
        }
    }
}

private fun unpair(ctx: android.content.Context, store: Store) {
    Scheduler.cancel(ctx)
    Alerts.clearComeback(ctx)
    store.clear()
    Surfaces.refresh(ctx)
}
