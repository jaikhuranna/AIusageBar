package com.jaikhurana.aiusagewidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app is a manual refresh (throttled to one a minute).
        lifecycleScope.launch { Sync.run(this@MainActivity, manual = true) }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val changes by Store.changes.collectAsState()
    // Re-read once a minute too, so countdowns and "updated" ages move.
    val minute by produceState(0L) { while (true) { delay(60_000); value++ } }
    val store = remember(changes, minute) { Store(ctx) }
    val look = remember(changes, minute) { Widgets.look(ctx) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (look.mode == Mode.UNPAIRED) {
            if (store.lastError == Store.ERROR_UNPAIRED) {
                Text("The desktop no longer knows this phone. Pair again.", color = MaterialTheme.colorScheme.error)
            }
            PairingScreen()
            Gallery()
        } else {
            Status(store, look)
            Gallery()
        }
    }
}

@Composable
private fun Status(store: Store, look: Look) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    Text("Claude usage", style = MaterialTheme.typography.headlineSmall)
    Text(
        buildString {
            append(store.host ?: "desktop")
            append(" · ")
            append(if (look.offline) "offline, last reading ${agoText(look.fetchedAt, look.now)}" else "updated ${agoText(look.fetchedAt, look.now)}")
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Preview(Style.RING, look, 96.dp, 96.dp)
        Preview(Style.DASH, look, 216.dp, 108.dp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch { Sync.run(ctx, manual = true, force = true); busy = false }
        }) { Text(if (busy) "Refreshing…" else "Refresh") }
        OutlinedButton(onClick = {
            Scheduler.cancel(ctx)
            Store(ctx).clear()
            Widgets.refreshAll(ctx)
        }) { Text("Unpair") }
    }
}

/** Every widget in every state, drawn by the same code the home screen uses. Tap one to place it. */
@Composable
private fun Gallery() {
    val ctx = LocalContext.current
    val now = remember { Instant.now() }
    Text("Widgets", style = MaterialTheme.typography.titleLarge)
    Text("Tap one to add it to the home screen.", style = MaterialTheme.typography.bodySmall)
    val widgets = listOf(
        Triple("1x1 ring", RingWidget::class.java, Style.RING to (72.dp to 72.dp)),
        Triple("2x2 ring", RingLargeWidget::class.java, Style.RING to (150.dp to 150.dp)),
        Triple("2x2 matrix", MatrixWidget::class.java, Style.MATRIX to (150.dp to 150.dp)),
        Triple("4x2 dash", DashWidget::class.java, Style.DASH to (310.dp to 150.dp)),
    )
    for ((name, cls, spec) in widgets) {
        val (style, size) = spec
        Text(name, style = MaterialTheme.typography.titleMedium)
        val perRow = when {
            size.first < 100.dp -> 4
            size.first < 200.dp -> 2
            else -> 1
        }
        Column(
            Modifier.clickable {
                AppWidgetManager.getInstance(ctx).requestPinAppWidget(ComponentName(ctx, cls), null, null)
            },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            demoLooks(now).chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { Preview(style, it, size.first, size.second) }
                }
            }
        }
    }
}

/** One of each state, with plausible numbers. */
fun demoLooks(now: Instant): List<Look> {
    fun e(rem: Int, inMin: Long?) = Effective(rem, inMin?.let { now.plus(Duration.ofMinutes(it)) }, false)
    val fetched = now.minus(Duration.ofMinutes(4)).toEpochMilli()
    return listOf(
        Look(Mode.NORMAL, now, e(62, 131), e(71, 3 * 1440 + 200), fetched, false),
        Look(Mode.NORMAL, now, e(18, 170), e(44, 4000), fetched, false),
        Look(Mode.SESSION_OUT, now, e(0, 101), e(38, 3000), fetched, false),
        Look(Mode.WEEK_OUT, now, e(40, 60), e(0, 2 * 1440 + 300), fetched, false),
        Look(Mode.GO, now, e(100, null), e(38, 3000), fetched, false),
        Look(Mode.NORMAL, now, e(62, 131), e(71, 4000), now.minus(Duration.ofHours(5)).toEpochMilli(), true),
        Look(Mode.UNPAIRED, now, null, null, 0, false),
    )
}

/** A widget as the launcher would show it: system background, tinted ink, accent on top. */
@Composable
fun Preview(style: Style, look: Look, width: Dp, height: Dp) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { width.roundToPx() to height.roundToPx() }
    val layers = remember(style, look, px) { Render.draw(ctx, style, look, px.first, px.second) }
    val shape = if (style == Style.RING) CircleShape else RoundedCornerShape(20.dp)
    Box(
        Modifier
            .size(width, height)
            .clip(shape)
            .background(colorResource(R.color.widget_bg)),
    ) {
        Image(
            layers.ink.asImageBitmap(), null, Modifier.fillMaxSize(),
            colorFilter = ColorFilter.tint(colorResource(R.color.widget_elements)),
        )
        Image(layers.accent.asImageBitmap(), null, Modifier.fillMaxSize())
    }
}
