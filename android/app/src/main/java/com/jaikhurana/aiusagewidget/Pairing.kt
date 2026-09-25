package com.jaikhurana.aiusagewidget

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address

private data class Found(val name: String, val url: String)

private const val SERVICE = "_aiusage._tcp"
private const val TAG = "AIusageWidget"

/**
 * Pair with the AIusageBar bridge the same way the watch does: find it on the
 * LAN (or type its address), enter the 6-digit code from the tray, and keep
 * the token and public URL it hands back.
 */
@Composable
fun PairingScreen() {
    var target by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pair with your desktop", style = MaterialTheme.typography.headlineSmall)
        Text(
            "On the desktop, choose Pair a device… in the AIusageBar tray menu (or run aiusagebar -pair).",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (target == null) ChooseBridge(onChosen = { target = it }) else CodeEntry(target!!, onBack = { target = null })
    }
}

@Composable
private fun ChooseBridge(onChosen: (String) -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val found = rememberBridges(attempt).toList()

    Text("On this Wi-Fi", style = MaterialTheme.typography.titleMedium)
    if (found.isEmpty()) Text("Searching…", style = MaterialTheme.typography.bodySmall)
    found.forEach { f ->
        Button(onClick = { onChosen(f.url) }, modifier = Modifier.fillMaxWidth()) { Text(f.name.removePrefix("AIusageBar on ")) }
    }
    TextButton(onClick = { attempt++ }) { Text("Search again") }

    Text("Or by address", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = address,
        onValueChange = { address = it; error = null },
        label = { Text("Bridge address") },
        placeholder = { Text("usage.example.com") },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = { normalizeAddress(address).fold(onChosen) { error = it.message } }, modifier = Modifier.fillMaxWidth()) {
        Text("Next")
    }
}

@Composable
private fun CodeEntry(base: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Text(base.substringAfter("://"), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter(Char::isDigit).take(6); error = null },
        label = { Text("Pairing code") },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        enabled = code.length == 6 && !busy,
        onClick = {
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { Bridge.pair(base, code, "${Build.MODEL} widgets") }
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
    TextButton(onClick = onBack) { Text("Back") }
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
