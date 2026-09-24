package com.jaikhurana.aiusagewear

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address

private data class Found(val name: String, val url: String)

private val GREY = Color(0xFFB0B0B0)
private const val SERVICE = "_aiusage._tcp"
private const val TAG = "AIusageWear"

/**
 * First run: find the bridge on the LAN (nothing typed), enter the 6-digit code
 * from the tray, and store the token and public URL the bridge hands back.
 */
@Composable
fun PairingScreen() {
    var target by remember { mutableStateOf<String?>(null) }
    var typing by remember { mutableStateOf(false) }
    when {
        target != null -> CodeEntry(target!!, onBack = { target = null })
        typing -> AddressEntry(onDone = { target = it; typing = false }, onBack = { typing = false })
        else -> ChooseBridge(onChosen = { target = it }, onType = { typing = true })
    }
}

@Composable
private fun ChooseBridge(onChosen: (String) -> Unit, onType: () -> Unit) {
    var attempt by remember { mutableStateOf(0) }
    // Snapshot the list here, in composition scope, so a bridge found while the
    // page is open recomposes it straight away.
    val found = rememberBridges(attempt).toList()
    ScalingLazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text("Pair with your desktop", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
        item {
            Text(
                "In the tray menu, choose Pair a device…",
                color = GREY, fontSize = 12.sp, textAlign = TextAlign.Center,
            )
        }
        if (found.isEmpty()) {
            item {
                Text(
                    "Searching… (the watch's Wi-Fi must be on, on your home network)",
                    color = GREY, fontSize = 11.sp, textAlign = TextAlign.Center,
                )
            }
        }
        found.forEach { f ->
            item {
                Button(onClick = { onChosen(f.url) }, modifier = Modifier.fillMaxWidth()) {
                    Text(f.name.removePrefix("AIusageBar on "), maxLines = 1)
                }
            }
        }
        item { Button(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth()) { Text("Search again") } }
        item { Button(onClick = onType, modifier = Modifier.fillMaxWidth()) { Text("Enter address") } }
    }
}

@Composable
private fun AddressEntry(onDone: (String) -> Unit, onBack: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ScalingLazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text("Bridge address", fontWeight = FontWeight.Bold) }
        item { Field(text, { text = it; error = null }, KeyboardType.Uri, "usage.example.com") }
        error?.let { item { Text(it, color = Color(0xFFFF8A80), fontSize = 11.sp, textAlign = TextAlign.Center) } }
        item {
            Button(onClick = { normalizeAddress(text).fold(onDone) { error = it.message } }, modifier = Modifier.fillMaxWidth()) {
                Text("Next")
            }
        }
        item { Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") } }
    }
}

@Composable
private fun CodeEntry(base: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ScalingLazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text("Pairing code", fontWeight = FontWeight.Bold) }
        item { Text(base.substringAfter("://"), color = GREY, fontSize = 11.sp, maxLines = 1) }
        item { Field(code, { code = it.filter(Char::isDigit).take(6); error = null }, KeyboardType.NumberPassword, "123456") }
        error?.let { item { Text(it, color = Color(0xFFFF8A80), fontSize = 11.sp, textAlign = TextAlign.Center) } }
        item {
            Button(
                enabled = code.length == 6 && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { Bridge.pair(base, code, Build.MODEL) }
                        r.onSuccess { p ->
                            val store = Store(ctx)
                            store.clear()
                            // Use the tunnel from now on, if the bridge has one.
                            store.baseUrl = p.publicUrl ?: base
                            if (p.publicUrl != null && p.publicUrl != base) store.lanUrl = base
                            store.host = p.host
                            store.token = p.token
                            Sync.run(ctx, manual = false)
                        }.onFailure {
                            error = it.message
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Pairing…" else "Pair") }
        }
        item { Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") } }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, type: KeyboardType, hint: String) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(Color.White),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GREY, RoundedCornerShape(12.dp))
            .padding(10.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(hint, color = Color(0xFF666666), fontSize = 16.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            inner()
        },
    )
    Spacer(Modifier.height(2.dp))
}

/** Bridges advertising `_aiusage._tcp` on the LAN. IPv4 only, to keep URLs simple. */
@Composable
private fun rememberBridges(attempt: Int): List<Found> {
    val ctx = LocalContext.current
    val found = remember { mutableStateListOf<Found>() }
    DisposableEffect(attempt) {
        found.clear()
        val nsd = ctx.getSystemService(NsdManager::class.java)
        val main = Handler(Looper.getMainLooper())
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        // resolveService handles one request at a time, so resolve in a queue.
        fun next() {
            if (resolving) return
            val info = queue.removeFirstOrNull() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "resolve failed for ${i.serviceName}: $errorCode")
                    main.post { resolving = false; next() }
                }

                override fun onServiceResolved(i: NsdServiceInfo) {
                    Log.i(TAG, "resolved ${i.serviceName}: host=${i.host} port=${i.port}" +
                        if (Build.VERSION.SDK_INT >= 34) " addrs=${i.hostAddresses}" else "")
                    val v4 = if (Build.VERSION.SDK_INT >= 34) {
                        i.hostAddresses.firstOrNull { it is Inet4Address }
                    } else {
                        @Suppress("DEPRECATION") i.host?.takeIf { it is Inet4Address }
                    }
                    main.post {
                        if (v4 != null) {
                            val url = "http://${v4.hostAddress}:${i.port}"
                            if (found.none { it.url == url }) found += Found(i.serviceName, url)
                        }
                        resolving = false
                        next()
                    }
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(i: NsdServiceInfo) { Log.i(TAG, "found ${i.serviceName} ${i.serviceType}"); main.post { queue += i; next() } }
            override fun onServiceLost(i: NsdServiceInfo) { main.post { found.removeAll { it.name == i.serviceName } } }
            override fun onDiscoveryStarted(serviceType: String) { Log.i(TAG, "discovery started: $serviceType") }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "discovery failed: $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
        onDispose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }
    return found
}
