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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
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
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.ripple
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

class MainActivity : ComponentActivity() {
    /** The orbit ring a tile tap picked, shown with its figures. */
    private val picked = mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Alerts.ensureChannels(this)
        picked.value = ringOf(intent)
        setContent { MaterialTheme { App(picked) } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        ringOf(intent)?.let { picked.value = it }
    }

    private fun ringOf(i: android.content.Intent?) = i?.getIntExtra(EXTRA_RING, -1)?.takeIf { it >= 0 }

    companion object {
        const val EXTRA_RING = "ring"
    }

    override fun onResume() {
        super.onResume()
        // Opening the app counts as a manual refresh (throttled to once a minute).
        lifecycleScope.launch { Sync.run(this@MainActivity, manual = true) }
    }
}

private val SOFT = Color(0xFFFFD3AE)
internal val DIM = Color(0xFFB9987E)

@Composable
private fun App(picked: androidx.compose.runtime.MutableState<Int?>) {
    val version by Store.changes.collectAsState()
    val store = Store(LocalContext.current)
    // Re-read on every write; the store is small and in memory.
    @Suppress("UNUSED_EXPRESSION") version
    if (!store.paired) PairingScreen() else Paired(store, picked)
}

/** Swipe left or turn the bezel: Orbit → Setup. */
@Composable
private fun Paired(store: Store, picked: androidx.compose.runtime.MutableState<Int?>) {
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val pager = rememberPagerState { PAGES }
    // A tile tap on a ring: show the orbit with that ring's figures.
    LaunchedEffect(picked.value) { if (picked.value != null) pager.scrollToPage(0) }
    val setupList = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            // One bezel click (or a crown nudge) is one page. On Setup the
            // list scrolls first, and turning back past its top leaves it.
            .onRotaryScrollEvent { e ->
                val d = e.verticalScrollPixels
                val onSetup = pager.currentPage == PAGES - 1 && !pager.isScrollInProgress
                if (onSetup && (if (d > 0) setupList.canScrollForward else setupList.canScrollBackward)) {
                    scope.launch { setupList.scrollBy(d) }
                } else if (!pager.isScrollInProgress && kotlin.math.abs(d) >= 1f) {
                    val to = (pager.currentPage + if (d > 0) 1 else -1).coerceIn(0, PAGES - 1)
                    if (to != pager.currentPage) scope.launch { pager.animateScrollToPage(to) }
                }
                true
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        HorizontalPager(state = pager) { page ->
            when (page) {
                0 -> OrbitPage(store, picked.value) { picked.value = it }
                else -> SetupPage(store, setupList)
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
        PageDots(current = pager.currentPage, count = PAGES, alpha = dotsAlpha)
    }
}

@Composable
internal fun PairAgain(onClick: () -> Unit) {
    Box(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Pair again", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private const val PAGES = 2


/** Which page you're on, in the ring's gap at the bottom. */
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
private fun SetupPage(store: Store, list: ScalingLazyListState) {
    val scope = rememberCoroutineScope()
    var switching by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val snap = store.snapshot()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ScalingLazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            horizontalAlignment = Alignment.CenterHorizontally,
            rotaryScrollableBehavior = null, // the pager's handler routes the bezel
        ) {
            item {
                Button(enabled = !switching, onClick = {
                    switching = true
                    scope.launch {
                        Sync.selectProvider(ctx, if (store.provider == UsageProvider.CLAUDE) UsageProvider.CODEX else UsageProvider.CLAUDE)
                        switching = false
                    }
                }) { Text(if (switching) "Switching…" else "Provider: ${store.provider.title}") }
            }
            snap?.sourceError?.let { item { Text(it, fontSize = 12.sp, textAlign = TextAlign.Center) } }
            item { Text("Setup", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            item { Text(store.host?.let { "Paired with $it" } ?: "Paired", color = SOFT, fontSize = 13.sp) }
            item { Text(store.baseUrl ?: "", color = DIM, fontSize = 10.sp, textAlign = TextAlign.Center) }
            store.lanUrl?.let { item { Text("at home: ${it.substringAfter("://")}", color = DIM, fontSize = 10.sp) } }
            item { Spacer(Modifier.size(6.dp)) }
            item { Text("API-cost equivalent", fontWeight = FontWeight.Medium) }
            if (store.provider == UsageProvider.CODEX) item { Text("Unavailable for Codex", color = DIM, fontSize = 11.sp) }
            item { Text("an estimate, not a bill", color = DIM, fontSize = 11.sp) }
            if (snap?.costAvailable == true) {
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

internal fun unpair(ctx: android.content.Context, store: Store) {
    Scheduler.cancel(ctx)
    Alerts.clearComeback(ctx)
    store.clear()
    Surfaces.refresh(ctx)
}
