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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
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
            CardScreen(from, card.value, backdrop.value, busy, onRefresh = ::refresh, onOpenApp = ::openApp, onDismiss = { open = false })
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
            Sync.run(this@CardActivity, manual = true, force = true)
            busy = false
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

    private companion object {
        const val MAX_BLUR_DP = 24f
    }
}

@Composable
private fun CardScreen(from: Rect?, card: Float, backdrop: Float, busy: Boolean, onRefresh: () -> Unit, onOpenApp: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val changes by Store.changes.collectAsState()
    // On the wall-clock minute, the same instant the widgets' countdown ticks.
    val minute by produceState(0L) { while (true) { delay(60_000 - System.currentTimeMillis() % 60_000); value++ } }
    val store = remember(changes, minute) { Store(ctx) }
    val look = remember(changes, minute) { Widgets.look(ctx) }

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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Card(look, store, busy, onRefresh, onOpenApp)
        }
    }
}

@Composable
private fun Card(look: Look, store: Store, busy: Boolean, onRefresh: () -> Unit, onOpenApp: () -> Unit) {
    val fg = colorResource(R.color.widget_elements)
    val headline = remember { FontFamily(Fonts.headline) }

    // Header: whose numbers, and how old.
    val snap = store.snapshot()
    val age = snap?.cacheFetchedAt?.toEpochMilli() ?: look.fetchedAt
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label("CLAUDE", fg, 1f)
        Spacer(Modifier.weight(1f))
        Label(
            when {
                busy -> "REFRESHING…"
                look.offline -> "OFFLINE · ${agoText(age, look.now).uppercase()}"
                else -> "UPDATED ${agoText(age, look.now).uppercase()}"
            },
            fg, 0.55f,
        )
    }

    // The ring and the one number that matters most right now.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Preview(Style.RING, look, 104.dp, 104.dp)
        val (big, caption, color) = hero(look)
        Column {
            Text(big, color, TextStyle(fontFamily = headline, fontSize = 60.sp, letterSpacing = (-0.03).em, lineHeight = 60.sp))
            Label(caption, fg, 0.7f)
        }
    }

    WindowRow("5H SESSION", look.session, Look.SESSION, look, fg, headline, timer = look.mode == Mode.SESSION_OUT)
    WindowRow("WEEK", look.week, Look.WEEK, look, fg, headline, timer = false)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(Render.ORANGE).copy(alpha = if (busy) 0.5f else 1f))
                .clickable(enabled = !busy, onClick = onRefresh)
                .padding(horizontal = 22.dp, vertical = 12.dp),
        ) {
            Label(if (busy) "REFRESHING" else "REFRESH", Color.White, 1f)
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpenApp).padding(horizontal = 12.dp, vertical = 12.dp)) {
            Label("OPEN APP", fg, 0.7f)
        }
    }
    if (snap != null && store.host != null) Label("FROM ${store.host!!.uppercase()}", fg, 0.35f, size = 10.sp)
}

/** The big figure: whichever window is closest to biting, or the countdown. */
private fun hero(look: Look): Triple<String, String, Color> = when (look.mode) {
    Mode.UNPAIRED -> Triple("PAIR", "OPEN THE APP TO PAIR", Color(Render.GREY))
    Mode.WAITING -> Triple("…", "WAITING FOR THE DESKTOP", Color(Render.GREY))
    Mode.NORMAL ->
        if (look.sessionLeft <= look.weekLeft) Triple("${look.sessionLeft}%", "OF THE SESSION LEFT", Color(Render.ORANGE))
        else Triple("${look.weekLeft}%", "OF THE WEEK LEFT", Color(Render.ORANGE))
    Mode.SESSION_OUT -> Triple(look.countdownTo?.let { countdownText(look.now, it) } ?: "OUT", "UNTIL THE 5H RESET", Color(Render.YELLOW))
    Mode.WEEK_OUT -> Triple(look.countdownTo?.let { countdownText(look.now, it) } ?: "OUT", "WEEKLY LIMIT HIT", Color(Render.GREY))
    Mode.GO -> Triple("GO!", "CLAUDE'S BACK", Color(Render.ORANGE))
}

@Composable
private fun WindowRow(
    label: String, e: Effective?, length: Duration, look: Look, fg: Color, headline: FontFamily, timer: Boolean,
) {
    val ctx = LocalContext.current
    val paired = look.mode != Mode.UNPAIRED && look.mode != Mode.WAITING
    val remaining = e?.remaining ?: 100
    val out = paired && remaining <= 0
    val color = when {
        look.mode == Mode.WEEK_OUT -> Color(Render.GREY)
        timer -> Color(Render.YELLOW)
        else -> Color(Render.ORANGE)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Label(label, fg, 0.9f, Modifier.alignByBaseline())
            Spacer(Modifier.weight(1f))
            Text(
                when { !paired -> "--"; out -> "OUT"; else -> "$remaining%" },
                if (paired) color else fg,
                TextStyle(fontFamily = headline, fontSize = 28.sp, lineHeight = 28.sp),
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
        if (sub.isNotEmpty()) Text(sub, fg.copy(alpha = 0.6f), TextStyle(fontSize = 13.sp))
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
private fun Label(text: String, color: Color, alpha: Float, modifier: Modifier = Modifier, size: TextUnit = 12.sp) =
    Text(text, color.copy(alpha = alpha), TextStyle(fontSize = size, fontWeight = FontWeight.Medium, letterSpacing = 0.1.em), modifier)

@Composable
private fun Text(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) =
    androidx.compose.material3.Text(text, modifier, style = style.copy(color = color))
