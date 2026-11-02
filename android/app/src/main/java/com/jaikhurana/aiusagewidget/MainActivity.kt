package com.jaikhurana.aiusagewidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Debug builds only: made-up pools, to judge the design without live accounts.
        val fixture = intent.getBooleanExtra(CardActivity.EXTRA_FIXTURE, false)
        setContent { App(fixture) }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app is a manual refresh (throttled to one a minute).
        lifecycleScope.launch { Sync.run(this@MainActivity, manual = true) }
    }
}

/**
 * The app wears the widgets' colours: Nothing's page and card colors, the
 * provider's accent for anything you can press. Not Material You's dynamic ones.
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
        surfaceContainerHigh = card, surfaceContainerLow = card, surfaceVariant = card, onSurfaceVariant = fg.copy(alpha = 0.7f),
        outline = fg.copy(alpha = 0.3f), error = Color(Render.ORANGE),
    )
    MaterialTheme(colorScheme = scheme) {
        // No Surface here, so plain Material text (the pairing screen) needs its colour set.
        CompositionLocalProvider(LocalContentColor provides fg) {
            Box(Modifier.fillMaxSize().background(page)) { content() }
        }
    }
}

/**
 * Each family as the detail behind the widgets: a Session card and a Weekly
 * card, each the share of all the family's accounts left, what comes back at
 * the next reset, the pace, a segment per account and the accounts' own
 * numbers. Pairing and the widgets' settings sit behind the header's button.
 */
@Composable
private fun App(fixture: Boolean) {
    val ctx = LocalContext.current
    val changes by Store.changes.collectAsState()
    val minute = minuteTicks()
    val store = remember(changes, minute) { Store(ctx) }
    val look = remember(changes, minute) { Widgets.look(ctx) }
    val now = remember(changes, minute) { Instant.now() }
    val views = remember(changes, minute, fixture) {
        PoolFamily.entries.map { f ->
            Fixtures.pool(f, now).takeIf { fixture }?.let { FamilyView.of(it, now.toEpochMilli() - 120_000, false, now) }
                ?: familyView(ctx, f, now)
        }
    }
    val fg = colorResource(R.color.widget_elements)
    var settings by remember { mutableStateOf(false) }

    AppTheme(Color(Render.accent(look))) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Header(store, fg, onRefresh = { Sync.run(ctx, manual = true, force = true) }, onSettings = { settings = true })
            if (look.mode == Mode.UNPAIRED && !fixture) {
                Panel {
                    if (store.lastError == Store.ERROR_UNPAIRED) {
                        Text("The desktop no longer knows this phone. Pair again.", Color(Render.ORANGE), TextStyle(fontSize = 14.sp))
                    }
                    PairingScreen()
                }
            } else {
                views.forEach { view ->
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.padding(horizontal = 8.dp)) { FamilyHeader(view, fg, size = 14.sp, trailing = ageText(view, now)) }
                    if (view.known.isEmpty()) {
                        Panel { Text(view.error ?: "No numbers from the desktop yet.", fg.copy(alpha = 0.6f), TextStyle(fontSize = 14.sp, lineHeight = 20.sp)) }
                    } else {
                        Limit.entries.forEach { MeterCard(view, it, now, fg) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (settings) Settings(store, fg) { settings = false }
    }
}

/** The title, and two quiet round buttons: refresh, and the settings sheet. */
@Composable
private fun Header(store: Store, fg: Color, onRefresh: suspend () -> Unit, onSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Label("AI USAGE", fg, 0.95f, size = 15.sp)
            Label(store.host?.let { "FROM ${it.uppercase()}" } ?: "NOT PAIRED", fg, 0.4f, size = 10.sp)
        }
        if (store.paired) RoundButton("Refresh", fg, enabled = !busy, onClick = {
            busy = true
            scope.launch { onRefresh(); busy = false }
        }) { c -> ResetArrow(c, 20.dp) }
        RoundButton("Settings", fg, onClick = onSettings) { c ->
            // Three dots, the dot-matrix's smallest word.
            Canvas(Modifier.size(20.dp)) {
                for (i in -1..1) drawCircle(c, 1.9.dp.toPx(), Offset(size.width / 2 + i * 6.dp.toPx(), size.height / 2))
            }
        }
    }
}

@Composable
private fun RoundButton(
    description: String, fg: Color, enabled: Boolean = true, onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(colorResource(R.color.widget_bg)), contentAlignment = Alignment.Center) {
            icon(fg.copy(alpha = if (enabled) 0.85f else 0.3f))
        }
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

/**
 * Everything that isn't usage: which account the widgets draw, adding a
 * widget (by name; the faces live on the home screen), and the paired
 * desktop, with Unpair behind a confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Settings(store: Store, fg: Color, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colorResource(R.color.widget_bg),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Label("WIDGETS SHOW", fg, 0.9f)
            Text("The 1×1 pools every account of the family; the others draw the one you pick.", fg.copy(alpha = 0.55f), TextStyle(fontSize = 13.sp, lineHeight = 18.sp))
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageProvider.entries.forEach { p ->
                    val selected = p == store.selected
                    val accent = Color(Render.accent(p))
                    val shape = RoundedCornerShape(50)
                    Box(
                        Modifier
                            .heightIn(min = 40.dp)
                            .clip(shape)
                            .background(if (selected) accent.copy(alpha = 0.15f) else Color.Transparent)
                            .border(1.dp, if (selected) accent else fg.copy(alpha = 0.2f), shape)
                            .selectable(selected, role = Role.RadioButton) { scope.launch(Dispatchers.IO) { Sync.selectProvider(ctx, p) } }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { Label(p.title.uppercase(), if (selected) accent else fg, if (selected) 1f else 0.7f, size = 11.sp) }
                }
            }
            Divider(fg)
            Label("ADD TO HOME SCREEN", fg, 0.9f)
            Column {
                WIDGETS.forEach { (name, cls) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).clickable { pin(ctx, cls) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, fg.copy(alpha = 0.85f), TextStyle(fontSize = 15.sp), Modifier.weight(1f))
                        Label("+ ADD", fg, 0.45f, size = 11.sp)
                    }
                }
            }
            if (store.paired) {
                Divider(fg)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("PAIRED WITH ${(store.host ?: "desktop").uppercase()}", fg, 0.5f, Modifier.weight(1f), size = 11.sp)
                    Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).clickable { confirm = true }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        Label("UNPAIR", Color(Render.ORANGE), 1f, size = 11.sp)
                    }
                }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { androidx.compose.material3.Text("Unpair this phone?") },
        text = { androidx.compose.material3.Text("The widgets stop updating until you pair again with a new code from the desktop.") },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                onDismiss()
                Scheduler.cancel(ctx)
                Store(ctx).clear()
                Widgets.refreshAll(ctx)
            }) { androidx.compose.material3.Text("Unpair") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { androidx.compose.material3.Text("Cancel") } },
    )
}

@Composable
private fun Divider(fg: Color) = Box(Modifier.fillMaxWidth().height(1.dp).background(fg.copy(alpha = 0.08f)))

private val WIDGETS = listOf(
    "1×1 ring · every account pooled" to RingWidget::class.java,
    "2×2 ring" to RingLargeWidget::class.java,
    "2×2 dot matrix" to MatrixWidget::class.java,
    "4×2 dash" to DashWidget::class.java,
    "4×1 threads" to ThreadWidget::class.java,
)

private fun pin(ctx: android.content.Context, cls: Class<out UsageWidget>) {
    val mgr = AppWidgetManager.getInstance(ctx)
    if (mgr.isRequestPinAppWidgetSupported) mgr.requestPinAppWidget(ComponentName(ctx, cls), null, null)
    else Toast.makeText(ctx, "Long-press your home screen, then pick Widgets", Toast.LENGTH_LONG).show()
}
