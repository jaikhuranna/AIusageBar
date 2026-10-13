package com.jaikhurana.aiusagewidget

import android.content.Intent
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration

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
            CardScreen(from, card.value, backdrop.value, busy, onRefresh = ::refresh, onSelectProvider = ::selectProvider, onOpenApp = ::openApp, onDismiss = { open = false })
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

    // Not gated on busy: both providers are cached, so this is only a redraw.
    private fun selectProvider(provider: UsageProvider) {
        lifecycleScope.launch(Dispatchers.IO) { Sync.selectProvider(this@CardActivity, provider) }
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

    private companion object {
        const val MAX_BLUR_DP = 24f
    }
}

@Composable
private fun CardScreen(from: Rect?, card: Float, backdrop: Float, busy: Boolean, onRefresh: () -> Unit, onSelectProvider: (UsageProvider) -> Unit, onOpenApp: () -> Unit, onDismiss: () -> Unit) {
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
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Card(busy, onRefresh, onSelectProvider, onOpenApp)
        }
    }
}

@Composable
private fun Card(busy: Boolean, onRefresh: () -> Unit, onSelectProvider: (UsageProvider) -> Unit, onOpenApp: () -> Unit) {
    val ctx = LocalContext.current
    val fg = colorResource(R.color.widget_elements)
    val changes by Store.changes.collectAsState()
    val store = remember(changes) { Store(ctx) }

    Providers(onSelectProvider)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(Render.accent(store.selected)).copy(alpha = if (busy) 0.5f else 1f))
                .clickable(enabled = !busy, onClick = onRefresh)
                .padding(horizontal = 22.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Label(if (busy) "REFRESHING" else "REFRESH", Color.White, 1f)
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50)).clickable(onClick = onOpenApp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Label("OPEN APP", fg, 0.7f)
        }
    }
    store.host?.let { Label("FROM ${it.uppercase()}", fg, 0.35f, size = 10.sp) }
}

/**
 * Claude and Codex together, each with its own age. Both are fetched on every
 * refresh, so picking which one the widgets show is instant and never waits
 * on the network. Shared by the card and the app.
 */
@Composable
fun Providers(onSelect: (UsageProvider) -> Unit) {
    val ctx = LocalContext.current
    val changes by Store.changes.collectAsState()
    // On the wall-clock minute, the same instant the widgets' countdown ticks.
    val minute by produceState(0L) { while (true) { delay(60_000 - System.currentTimeMillis() % 60_000); value++ } }
    val selected = remember(changes) { Store(ctx).selected }
    val fg = colorResource(R.color.widget_elements)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        UsageProvider.entries.forEachIndexed { i, provider ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(fg.copy(alpha = 0.1f)))
            val store = remember(changes, minute, provider) { Store(ctx, provider) }
            val look = remember(changes, minute, provider) { Widgets.look(ctx, provider = provider) }
            ProviderBlock(look, store, provider == selected, fg) { onSelect(provider) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(fg.copy(alpha = 0.1f)))
        val claude = remember(changes, minute) { Widgets.look(ctx, provider = UsageProvider.CLAUDE) }
        val codex = remember(changes, minute) { Widgets.look(ctx, provider = UsageProvider.CODEX) }
        Threads(claude, codex, fg)
    }
}

/** Both weeks as the 4x1 draws them, twisted together. */
@Composable
private fun Threads(claude: Look, codex: Look, fg: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Label("${claude.weekLabel} · BOTH", fg, 0.9f)
            Spacer(Modifier.weight(1f))
            Label("FAINT = USED", fg, 0.4f, size = 10.sp)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp)) {
            val px = with(LocalDensity.current) { constraints.maxWidth to 44.dp.roundToPx() }
            val layers = remember(claude, codex, px) { Render.threads(claude, codex, px.first, px.second) }
            Image(layers.accent.asImageBitmap(), "Weekly left: Claude ${claude.weekLeft}%, Codex ${codex.weekLeft}%", Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ProviderBlock(look: Look, store: Store, selected: Boolean, fg: Color, onSelect: () -> Unit) {
    val headline = remember { FontFamily(Fonts.headline) }
    val accent = Color(Render.accent(look))
    val age = store.snapshot()?.cacheFetchedAt?.toEpochMilli() ?: look.fetchedAt
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(accent))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Label(look.provider.title.uppercase(), fg, 0.9f, size = 13.sp)
                Label(
                    when {
                        look.mode == Mode.UNPAIRED -> "NOT PAIRED"
                        look.mode == Mode.WAITING -> "WAITING FOR THE DESKTOP"
                        look.offline -> "OFFLINE · ${agoText(age, look.now).uppercase()}"
                        else -> "UPDATED ${agoText(age, look.now).uppercase()}"
                    },
                    fg, 0.5f, size = 10.sp,
                )
            }
            WidgetToggle(selected, accent, fg, onSelect)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Preview(Style.RING, look, 72.dp, 72.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WindowRow(look.sessionLabel, look.session, look.sessionLength, look, fg, headline, timer = look.mode == Mode.SESSION_OUT)
                WindowRow(look.weekLabel, look.week, look.weekLength, look, fg, headline, timer = false)
            }
        }
    }
}

/** Which provider the home-screen widgets draw. Never disabled: switching is a redraw. */
@Composable
private fun WidgetToggle(selected: Boolean, accent: Color, fg: Color, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.15f) else Color.Transparent)
            .border(1.dp, if (selected) accent else fg.copy(alpha = 0.2f), shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = { if (!selected) onSelect() })
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(if (selected) "ON WIDGETS" else "SHOW ON WIDGETS", if (selected) accent else fg, if (selected) 1f else 0.7f, size = 10.sp)
    }
}

@Composable
private fun WindowRow(
    label: String, e: Effective?, length: Duration, look: Look, fg: Color, headline: FontFamily, timer: Boolean,
) {
    val ctx = LocalContext.current
    val paired = look.mode != Mode.UNPAIRED && look.mode != Mode.WAITING && e != null
    val remaining = e?.remaining ?: 100
    val out = paired && remaining <= 0
    val color = when {
        look.mode == Mode.WEEK_OUT -> Color(Render.GREY)
        timer -> Color(Render.YELLOW)
        else -> Color(Render.accent(look))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Label(label, fg, 0.9f, Modifier.alignByBaseline())
            Spacer(Modifier.weight(1f))
            Text(
                when { !paired -> "--"; out -> "OUT"; else -> "$remaining%" },
                if (paired) color else fg,
                TextStyle(fontFamily = headline, fontSize = 24.sp, lineHeight = 24.sp),
                Modifier.alignByBaseline(),
            )
        }
        val lit = when {
            !paired -> 0f
            timer -> look.sessionTimerFraction()
            look.mode == Mode.WEEK_OUT -> 0f
            else -> remaining / 100f
        }
        val reset = e?.resetsAt
        val tick = if (paired && reset != null && !out && look.mode != Mode.WEEK_OUT)
            (Duration.between(look.now, reset).seconds.toFloat() / length.seconds).coerceIn(0f, 1f) else null
        DotBar(lit, color, fg, tick)
        val sub = when {
            !paired -> ""
            reset == null -> if (out) "Reset time unknown" else "Window not started"
            out || timer -> "Back at ${clockText(ctx, reset, look.now)}, in ${untilText(look.now, reset)}"
            else -> {
                val p = when (val pc = pace(e, length, look.now)) {
                    Pace.Lasts -> " · at this pace it lasts"
                    is Pace.RunsOut -> " · at this pace empty in ~${untilText(look.now, pc.at)}"
                    else -> ""
                }
                "Resets ${clockText(ctx, reset, look.now)}, in ${untilText(look.now, reset)}$p"
            }
        }
        if (sub.isNotEmpty()) Text(sub, fg.copy(alpha = 0.6f), TextStyle(fontSize = 12.sp, lineHeight = 16.sp))
    }
}

/** A row of dots, like the widgets' bars, with the "time left" tick. */
@Composable
private fun DotBar(lit: Float, on: Color, fg: Color, tick: Float?) {
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val spacing = 9.dp.toPx()
        val n = (size.width / spacing).toInt().coerceAtLeast(1)
        val r = 2.6.dp.toPx()
        val cy = size.height / 2
        val count = (n * lit).let { if (lit > 0f) kotlin.math.ceil(it).toInt() else 0 }
        for (i in 0 until n) {
            val c = Offset((i + 0.5f) * spacing, cy)
            drawCircle(if (i < count) on else fg.copy(alpha = 0.13f), r, c)
        }
        tick?.let {
            val x = it * n * spacing
            drawLine(fg, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
        }
    }
}

@Composable
internal fun Label(text: String, color: Color, alpha: Float, modifier: Modifier = Modifier, size: TextUnit = 12.sp) =
    Text(text, color.copy(alpha = alpha), TextStyle(fontSize = size, fontWeight = FontWeight.Medium, letterSpacing = 0.1.em), modifier)

@Composable
internal fun Text(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) =
    androidx.compose.material3.Text(text, modifier, style = style.copy(color = color))
