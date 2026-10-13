package com.jaikhurana.aiusagewidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app is a manual refresh (throttled to one a minute).
        lifecycleScope.launch { Sync.run(this@MainActivity, manual = true) }
    }
}

/**
 * The app wears the widgets' look: Nothing's page and card colors, the
 * provider's accent for anything you can press, and the card's readout up top.
 */
@Composable
private fun AppTheme(accent: Color, content: @Composable () -> Unit) {
    val page = colorResource(R.color.app_bg)
    val card = colorResource(R.color.widget_bg)
    val fg = colorResource(R.color.widget_elements)
    val base = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = accent, onPrimary = Color.White, secondary = accent, onSecondary = Color.White,
        background = page, onBackground = fg, surface = card, onSurface = fg,
        surfaceContainerHigh = card, surfaceVariant = card, onSurfaceVariant = fg.copy(alpha = 0.7f),
        outline = fg.copy(alpha = 0.3f), error = Color(Render.ORANGE),
    )
    MaterialTheme(colorScheme = scheme) {
        Box(Modifier.fillMaxSize().background(page)) { content() }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val changes by Store.changes.collectAsState()
    // Re-read on the wall-clock minute too, so countdowns and "updated" ages move.
    val minute by produceState(0L) { while (true) { delay(60_000 - System.currentTimeMillis() % 60_000); value++ } }
    val store = remember(changes, minute) { Store(ctx) }
    val look = remember(changes, minute) { Widgets.look(ctx) }
    val fg = colorResource(R.color.widget_elements)

    AppTheme(Color(Render.accent(look))) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(look, fg)
            Panel {
                if (look.mode == Mode.UNPAIRED) {
                    if (store.lastError == Store.ERROR_UNPAIRED) {
                        Text("The desktop no longer knows this phone. Pair again.", Color(Render.ORANGE), TextStyle(fontSize = 14.sp))
                    }
                    PairingScreen()
                } else {
                    Status(store, look, fg)
                }
            }
            Gallery(look, fg)
            if (look.mode != Mode.UNPAIRED) Footer(store, fg)
        }
    }
}

/** The app's title, in the card's header style. Each provider shows its own age below. */
@Composable
private fun Header(look: Look, fg: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("AI USAGE", fg, 0.9f, size = 13.sp)
        Spacer(Modifier.weight(1f))
        if (look.mode == Mode.UNPAIRED) Label("NOT PAIRED", fg, 0.55f, size = 11.sp)
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colorResource(R.color.widget_bg))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
private fun Status(store: Store, look: Look, fg: Color) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    store.snapshot()?.sourceError?.let { Text(it, Color(Render.ORANGE), TextStyle(fontSize = 14.sp)) }
    Providers { p -> scope.launch(kotlinx.coroutines.Dispatchers.IO) { Sync.selectProvider(ctx, p) } }
    Pill(if (busy) "REFRESHING" else "REFRESH", Color(Render.accent(look)), enabled = !busy) {
        busy = true
        scope.launch { Sync.run(ctx, manual = true, force = true); busy = false }
    }
}

@Composable
private fun Pill(text: String, accent: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = if (enabled) 1f else 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Label(text, Color.White, 1f) }
}

/** Where the paired desktop is, and the way out, kept small and behind a confirmation. */
@Composable
private fun Footer(store: Store, fg: Color) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("PAIRED WITH ${(store.host ?: "desktop").uppercase()}", fg, 0.45f, Modifier.weight(1f), size = 11.sp)
        Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50)).clickable { confirm = true }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Label("UNPAIR", fg, 0.7f, size = 11.sp)
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { androidx.compose.material3.Text("Unpair this phone?") },
        text = { androidx.compose.material3.Text("The widgets stop updating until you pair again with a new code from the desktop.") },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                Scheduler.cancel(ctx)
                Store(ctx).clear()
                Widgets.refreshAll(ctx)
            }) { androidx.compose.material3.Text("Unpair") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { androidx.compose.material3.Text("Cancel") } },
    )
}

private val STATES = listOf("LIVE", "NORMAL", "LOW", "5H OUT", "7D OUT", "GO!", "STALE", "UNPAIRED")

/**
 * The five widgets, drawn by the same code the home screen uses, showing your
 * numbers or any state picked from the row above them. Tap one to place it.
 */
@Composable
private fun Gallery(look: Look, fg: Color) {
    val now = remember { Instant.now() }
    var state by remember { mutableStateOf(0) }
    val shown = if (state == 0) look else demoLooks(now)[state - 1].copy(provider = look.provider)
    val accent = Color(Render.accent(look))
    // The threads draw both providers: the picked state for the one on the
    // widgets, live numbers for the other.
    val ctx = LocalContext.current
    val otherProvider = if (look.provider == UsageProvider.CLAUDE) UsageProvider.CODEX else UsageProvider.CLAUDE
    val other = remember(look) { Widgets.look(ctx, provider = otherProvider) }

    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label("WIDGETS", fg, 0.9f, size = 13.sp)
            Text("Tap one to add it to your home screen.", fg.copy(alpha = 0.6f), TextStyle(fontSize = 14.sp))
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            STATES.forEachIndexed { i, name ->
                val selected = i == state
                val shape = RoundedCornerShape(50)
                Box(
                    Modifier
                        .heightIn(min = 40.dp)
                        .clip(shape)
                        .background(if (selected) accent.copy(alpha = 0.15f) else Color.Transparent)
                        .border(1.dp, if (selected) accent else fg.copy(alpha = 0.2f), shape)
                        .selectable(selected, role = Role.RadioButton) { state = i }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Label(name, if (selected) accent else fg, 0.85f, size = 11.sp) }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 12.dp
            val full = maxWidth
            val half = (full - gap) / 2
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Tile("2×2 RING", RingLargeWidget::class.java, Style.RING, shown, half, half, fg)
                    Tile("2×2 MATRIX", MatrixWidget::class.java, Style.MATRIX, shown, half, half, fg)
                }
                Tile("4×2 DASH", DashWidget::class.java, Style.DASH, shown, full, full * 0.48f, fg)
                Tile("4×1 THREADS", ThreadWidget::class.java, Style.THREAD, shown, full, full * 0.22f, fg, other)
                SmallTile(shown, fg)
            }
        }
    }
}

private fun pin(ctx: android.content.Context, cls: Class<out UsageWidget>) {
    val mgr = AppWidgetManager.getInstance(ctx)
    if (mgr.isRequestPinAppWidgetSupported) mgr.requestPinAppWidget(ComponentName(ctx, cls), null, null)
    else Toast.makeText(ctx, "Long-press your home screen, then pick Widgets", Toast.LENGTH_LONG).show()
}

/** One widget with its name under it; tapping asks the launcher to place it. */
@Composable
private fun Tile(name: String, cls: Class<out UsageWidget>, style: Style, look: Look, width: Dp, height: Dp, fg: Color, other: Look? = null) {
    val ctx = LocalContext.current
    Column(Modifier.size(width, height + 36.dp).clip(RoundedCornerShape(28.dp)).clickable { pin(ctx, cls) }) {
        Preview(style, look, width, height, other)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(name, fg, 0.7f, Modifier.weight(1f), size = 11.sp)
            Label("+ ADD", fg, 0.45f, size = 11.sp)
        }
    }
}

/** The 1x1 is too small to fill a column, so it sits beside its name. */
@Composable
private fun SmallTile(look: Look, fg: Color) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable { pin(ctx, RingWidget::class.java) }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Preview(Style.RING, look, 72.dp, 72.dp)
        Label("1×1 RING", fg, 0.7f, Modifier.weight(1f), size = 11.sp)
        Label("+ ADD", fg, 0.45f, size = 11.sp)
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
fun Preview(style: Style, look: Look, width: Dp, height: Dp, other: Look? = null) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { width.roundToPx() to height.roundToPx() }
    val layers = remember(style, look, other, px) { Render.draw(ctx, style, look, px.first, px.second, other) }
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
