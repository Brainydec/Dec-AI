package com.dec.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private val Bg = Color(0xFF05080F)
private val Surface0 = Color(0xFF0D1424)
private val Cyan = Color(0xFF22D3EE)
private val Violet = Color(0xFFA78BFA)
private val Green = Color(0xFF34D399)
private val Red = Color(0xFFF43F5E)
private val Muted = Color(0xFF94A3B8)

private enum class Screen(val label: String, val glyph: String) {
    Home("DEC", "◉"), History("Tasks", "≡"), Perms("Access", "⛨"), Memory("Memory", "✦"), Settings("Setup", "⚙")
}

@Composable
fun DecApp() {
    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Bg, surface = Surface0, onSurface = Color(0xFFE2E8F0), onBackground = Color(0xFFE2E8F0))) {
        var screen by remember { mutableStateOf(Screen.Home) }
        val pending by Dec.pending.collectAsState()
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = Surface0) {
                    Screen.values().forEach {
                        NavigationBarItem(
                            selected = screen == it, onClick = { screen = it },
                            icon = { Text(it.glyph, fontSize = 18.sp) }, label = { Text(it.label, fontSize = 11.sp) }
                        )
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize().background(Bg)) {
                when (screen) {
                    Screen.Home -> HomeScreen()
                    Screen.History -> HistoryScreen()
                    Screen.Perms -> PermsScreen()
                    Screen.Memory -> MemoryScreen()
                    Screen.Settings -> SettingsScreen()
                }
            }
        }
        pending?.let { p ->
            AlertDialog(
                onDismissRequest = { p.resolve(false) },
                title = { Text("DEC needs your approval") },
                text = { Text(p.question) },
                confirmButton = { Button(onClick = { p.resolve(true) }) { Text("Approve") } },
                dismissButton = { OutlinedButton(onClick = { p.resolve(false) }) { Text("Deny") } }
            )
        }
    }
}

// ------------------------------------------------------------------ HOME
@Composable
private fun HomeScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx) }
    val state by Dec.state.collectAsState()
    val level by Dec.level.collectAsState()
    val msgs by Dec.messages.collectAsState()
    val online by Dec.online.collectAsState()
    val wake by Dec.wakeActive.collectAsState()
    val notice by Dec.notice.collectAsState()
    val resume by Dec.resumeTick.collectAsState()
    var estop by remember { mutableStateOf(prefs.emergencyStop) }
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1) }

    val netNow = remember(resume, state, prefs.forceOffline, prefs.localOnlyConversation) {
        Dec.isOnline(ctx) && !prefs.forceOffline && !prefs.localOnlyConversation
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) startDec(ctx)
    }
    fun launch() {
        val need = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) startDec(ctx) else permLauncher.launch(need.toTypedArray())
    }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(
                if (prefs.localOnlyConversation) "LOCAL ONLY" else if (netNow) "CLOUD AI" else "OFFLINE",
                if (netNow) Green else Violet
            )
            Chip(if (wake) "WAKE: ON" else "WAKE: OFF", if (wake) Cyan else Muted)
            Chip("MIC: " + if (state == DecState.LISTENING) "LIVE" else "IDLE", if (state == DecState.LISTENING) Red else Muted)
        }
        Spacer(Modifier.height(12.dp))
        Orb(state, level) {
            if (estop) return@Orb
            if (state == DecState.OFF) launch() else ctx.startService(Intent(ctx, DecService::class.java).setAction(DecService.ACTION_TALK))
        }
        Text(
            when (state) {
                DecState.OFF -> "Tap to start DEC"; DecState.STANDBY -> if (wake) "Say \"Hey Deck\"" else "Tap to talk"
                DecState.LISTENING -> "Listening…"; DecState.THINKING -> "Thinking…"; DecState.SPEAKING -> "Speaking · tap to interrupt"
            }, color = Muted, fontSize = 14.sp
        )
        Spacer(Modifier.height(6.dp))
        Waveform(state, level)
        if (notice.isNotBlank()) Text(notice, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message DEC…") },
                maxLines = 3,
                enabled = !estop
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = draft.isNotBlank() && !estop && state != DecState.THINKING,
                onClick = {
                    val message = draft.trim()
                    draft = ""
                    val intent = Intent(ctx, DecService::class.java)
                        .setAction(DecService.ACTION_TEXT)
                        .putExtra(DecService.EXTRA_TEXT, message)
                    ContextCompat.startForegroundService(ctx, intent)
                }
            ) { Text("Send") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { Dec.clearConversation(ctx) }) {
                Text("Clear conversation", color = Red, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(msgs) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(
                        m.text,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (m.fromUser) Color(0xFF123B48) else Surface0).padding(10.dp),
                        fontSize = 14.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (estop) {
                Button(onClick = { prefs.emergencyStop = false; estop = false }, modifier = Modifier.weight(1f)) { Text("Resume DEC") }
            } else {
                OutlinedButton(
                    onClick = { if (state == DecState.OFF) launch() else ctx.startService(Intent(ctx, DecService::class.java).setAction(DecService.ACTION_STOP)) },
                    modifier = Modifier.weight(1f)
                ) { Text(if (state == DecState.OFF) "Start DEC" else "Stop DEC") }
                Button(
                    onClick = {
                        prefs.emergencyStop = true; estop = true
                        ctx.startService(Intent(ctx, DecService::class.java).setAction(DecService.ACTION_ESTOP))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White), modifier = Modifier.weight(1f)
                ) { Text("EMERGENCY STOP") }
            }
        }
    }
}

private fun startDec(ctx: Context) {
    ContextCompat.startForegroundService(ctx, Intent(ctx, DecService::class.java))
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun Orb(state: DecState, level: Float, onTap: () -> Unit) {
    val t by rememberInfiniteTransition(label = "orb").animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "t")
    val color = when (state) {
        DecState.OFF -> Color(0xFF475569); DecState.STANDBY -> Cyan.copy(alpha = 0.6f); DecState.LISTENING -> Cyan
        DecState.THINKING -> Violet; DecState.SPEAKING -> Green
    }
    val speed = if (state == DecState.THINKING) 3f else 1f
    Canvas(Modifier.size(230.dp).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { onTap() }) {
        val c = Offset(size.width / 2, size.height / 2)
        val base = size.minDimension / 2 * 0.52f
        for (i in 0..2) {
            val ph = ((t * speed + i / 3f) % 1f)
            drawCircle(color.copy(alpha = (1f - ph) * 0.5f), radius = base * (1f + ph * 0.55f + level * 0.25f), center = c, style = Stroke(width = 3f))
        }
        drawCircle(color.copy(alpha = 0.18f), radius = base * (1f + level * 0.2f), center = c)
        drawCircle(color, radius = base, center = c, style = Stroke(width = 6f))
        drawCircle(color, radius = base * 0.35f * (1f + 0.15f * sin(2 * PI * t * speed).toFloat()), center = c)
    }
}

@Composable
private fun Waveform(state: DecState, level: Float) {
    val t by rememberInfiniteTransition(label = "wave").animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "w")
    val color = if (state == DecState.LISTENING) Cyan else if (state == DecState.SPEAKING) Green else Muted
    Canvas(Modifier.fillMaxWidth().height(40.dp)) {
        val n = 32; val gap = size.width / n
        val amp = when (state) { DecState.LISTENING -> 0.1f + level * 0.9f; DecState.SPEAKING -> 0.5f; DecState.THINKING -> 0.2f; else -> 0.05f }
        for (i in 0 until n) {
            val h = size.height * (0.08f + amp * abs(sin(2 * PI * (t + i * 0.11)).toFloat()))
            drawLine(color, Offset(gap * i + gap / 2, size.height / 2 - h / 2), Offset(gap * i + gap / 2, size.height / 2 + h / 2), strokeWidth = 4f, cap = StrokeCap.Round)
        }
    }
}

// ------------------------------------------------------------------ HISTORY
@Composable
private fun HistoryScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx) }
    val tick by Dec.logTick.collectAsState()
    val rows = remember(tick) { prefs.history() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Task execution history", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Everything DEC did or refused, newest first.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        if (rows.isEmpty()) Text("Nothing yet.", color = Muted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows) { r ->
                Card(colors = CardDefaults.cardColors(containerColor = Surface0)) {
                    Column(Modifier.padding(12.dp).fillMaxWidth()) {
                        Text(r.tool, fontWeight = FontWeight.Bold, color = Cyan)
                        Text(r.outcome, fontSize = 13.sp)
                        Text(r.detail, fontSize = 11.sp, color = Muted)
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.time)), fontSize = 10.sp, color = Muted)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ PERMISSIONS
@Composable
private fun PermsScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx) }
    val resume by Dec.resumeTick.collectAsState()
    var bump by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { bump++ }
    fun missing(t: Tool) = t.perms.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    fun open(i: Intent) = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    key(resume, bump) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Permissions", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Turn a switch off to revoke DEC's ability to do that. Sensitive actions (B) ask for approval every single time.", color = Muted, fontSize = 12.sp)
            Tool.values().forEach { t ->
                var on by remember { mutableStateOf(prefs.toolEnabled(t)) }
                val miss = missing(t)
                Card(colors = CardDefaults.cardColors(containerColor = Surface0)) {
                    Column(Modifier.padding(12.dp).fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.label, fontWeight = FontWeight.Bold)
                                Text(if (t.cat == Cat.B) "SENSITIVE · asks every time" else "Harmless · runs immediately", fontSize = 10.sp, color = if (t.cat == Cat.B) Red else Green)
                            }
                            Switch(checked = on, onCheckedChange = { on = it; prefs.setToolEnabled(t, it) })
                        }
                        Text(t.why, fontSize = 12.sp, color = Muted)
                        if (miss.isNotEmpty()) {
                            TextButton(onClick = { launcher.launch(miss.toTypedArray()) }) { Text("Grant Android permission") }
                        } else if (t.perms.isNotEmpty()) Text("✓ Android permission granted", fontSize = 11.sp, color = Green)
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Surface0)) {
                Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Screen reading and control", fontWeight = FontWeight.Bold)
                    Text(
                        "DEC asks before first screen access in each Accessibility Service session. Android turning this service off and back on also resets approval.",
                        fontSize = 12.sp,
                        color = Muted
                    )
                    OutlinedButton(
                        onClick = { prefs.revokeScreenApproval(); bump++ },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("REVOKE SCREEN ACCESS APPROVAL") }
                    Text(
                        "To revoke Android's underlying access completely, turn DEC AI off in Android Accessibility settings.",
                        fontSize = 11.sp,
                        color = Muted
                    )
                    TextButton(onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                        Text("Open Accessibility settings")
                    }
                }
            }
            Text("System access", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            SpecialRow("Modify system settings (brightness)", Settings.System.canWrite(ctx)) {
                open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }
            SpecialRow("Display over other apps (lets DEC open apps, alarms and calls while in the background)", Settings.canDrawOverlays(ctx)) {
                open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }
            SpecialRow("Battery: don't restrict DEC (keeps wake word alive)", null) { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            SpecialRow("Xiaomi/MIUI: allow Autostart & set Battery saver to \"No restrictions\"", null) {
                val miui = Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                try { ctx.startActivity(miui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }
            }
        }
    }
}

@Composable
private fun SpecialRow(text: String, granted: Boolean?, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface0)) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (granted == true) Text("✓", color = Green, fontSize = 18.sp) else TextButton(onClick = onClick) { Text("Open") }
        }
    }
}

// ------------------------------------------------------------------ MEMORY
@Composable
private fun MemoryScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx) }
    var items by remember { mutableStateOf(prefs.memories()) }
    var k by remember { mutableStateOf("") }
    var v by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("What DEC remembers", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Stored encrypted on this phone. Say \"remember that my brother is Sam\" or add facts here. Passwords and secrets are refused.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(k, { k = it }, label = { Text("About") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(v, { v = it }, label = { Text("Fact") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Button(onClick = {
            msg = if (prefs.putMemory(k, v)) { k = ""; v = ""; "Saved." } else "Not saved (empty, or looks like a password/secret)."
            items = prefs.memories()
        }, modifier = Modifier.padding(top = 6.dp)) { Text("Save") }
        if (msg.isNotEmpty()) Text(msg, fontSize = 11.sp, color = Muted)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items) { m ->
                Card(colors = CardDefaults.cardColors(containerColor = Surface0)) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(m.k, fontWeight = FontWeight.Bold, color = Cyan); Text(m.v, fontSize = 13.sp) }
                        TextButton(onClick = { k = m.k; v = m.v }) { Text("Edit") }
                        TextButton(onClick = { prefs.deleteMemory(m.k); items = prefs.memories() }) { Text("Delete", color = Red) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ SETTINGS

@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx) }
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(prefs.backendUrl) }
    var token by remember { mutableStateOf(prefs.apiToken) }
    var cc by remember { mutableStateOf(prefs.countryCode) }
    var persona by remember { mutableStateOf(prefs.personality) }
    var custom by remember { mutableStateOf(prefs.customInstructions) }
    var rate by remember { mutableFloatStateOf(prefs.ttsRate) }
    var offline by remember { mutableStateOf(prefs.forceOffline) }
    var status by remember { mutableStateOf("") }

    var wakeModelReady by remember {
        mutableStateOf(
            runCatching {
                ctx.assets.open("wake.onnx").use { }
                ctx.assets.open("wake.json").use { }
                true
            }.getOrDefault(false)
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        Text(
            "AI model & backend",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        OutlinedTextField(
            url,
            {
                url = it
                prefs.backendUrl = it
            },
            label = {
                Text("Backend URL (https://…onrender.com)")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            token,
            {
                token = it
                prefs.apiToken = it
            },
            label = { Text("Backend API token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                status =
                    "Contacting backend (can take about 60 seconds if asleep)…"

                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        try {
                            val c = OkHttpClient.Builder()
                                .readTimeout(
                                    75,
                                    java.util.concurrent.TimeUnit.SECONDS
                                )
                                .build()

                            c.newCall(
                                Request.Builder()
                                    .url(prefs.backendUrl.trimEnd('/') + "/health")
                                    .build()
                            ).execute().use { response ->
                                if (response.isSuccessful) {
                                    "Backend OK: " +
                                        (response.body?.string() ?: "").take(120)
                                } else {
                                    "Backend error ${response.code}"
                                }
                            }
                        } catch (e: Exception) {
                            "Cannot reach backend: ${e.message}"
                        }
                    }
                }
            }
        ) {
            Text("Test backend connection")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Force offline mode",
                Modifier.weight(1f)
            )

            Switch(
                offline,
                {
                    offline = it
                    prefs.forceOffline = it
                }
            )
        }

        var localOnly by remember { mutableStateOf(prefs.localOnlyConversation) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Local-only conversations", fontWeight = FontWeight.SemiBold)
                Text("Chat text, history, memories and screen context stay off DEC's cloud AI provider. Offline replies are limited to on-device commands and simple responses. Android voice recognition may use its own service; use text chat for the strongest local-only privacy.", color = Muted, fontSize = 11.sp)
            }
            Switch(
                checked = localOnly,
                onCheckedChange = {
                    localOnly = it
                    prefs.localOnlyConversation = it
                }
            )
        }

        Text(
            "Hybrid privacy",
            fontSize = 13.sp,
            color = Cyan,
            fontWeight = FontWeight.Bold
        )
        Text(
            "When Local-only is off, DEC may send your current message, recent chat context, selected memories and requested screen text to your configured AI provider. Your conversation history remains stored encrypted on this phone.",
            color = Muted,
            fontSize = 11.sp
        )

        Text(
            "Offline wake word",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )

        Text(
            "Wake phrase: Hey Deck",
            fontSize = 14.sp
        )

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (wakeModelReady) {
                    "Heed ONNX model and configuration found"
                } else {
                    "Wake model files are missing"
                },
                Modifier.weight(1f),
                color = if (wakeModelReady) Green else Muted,
                fontSize = 13.sp
            )

            Button(
                onClick = {
                    wakeModelReady = runCatching {
                        ctx.assets.open("wake.onnx").use { }
                        ctx.assets.open("wake.json").use { }
                        true
                    }.getOrDefault(false)

                    status = if (wakeModelReady) {
                        "Both Heed files were found in app assets."
                    } else {
                        "Missing wake.onnx or wake.json in app assets."
                    }
                }
            ) {
                Text("Check")
            }
        }

        Text(
            "The wake model runs offline. Only the phrase Hey Deck has been trained so far.",
            fontSize = 12.sp,
            color = Muted
        )

        Text(
            "Personality",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )

        OutlinedTextField(
            persona,
            {
                persona = it
                prefs.personality = it
            },
            label = {
                Text("Style (e.g. calm, formal, witty)")
            },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            custom,
            {
                custom = it
                prefs.customInstructions = it
            },
            label = { Text("Custom instructions") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            cc,
            {
                cc = it
                prefs.countryCode = it
            },
            label = {
                Text("Country code for WhatsApp (e.g. 234)")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            "Speech speed: %.1fx".format(rate),
            fontSize = 13.sp
        )

        Slider(
            rate,
            {
                rate = it
                prefs.ttsRate = it
            },
            valueRange = 0.6f..1.6f
        )

        Text(
            "Allowed when offline",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )

        Tool.values()
            .filter { it != Tool.REMEMBER }
            .forEach { tool ->
                var enabled by remember {
                    mutableStateOf(prefs.offlineAllowed(tool))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        tool.label,
                        Modifier.weight(1f),
                        fontSize = 14.sp
                    )

                    Switch(
                        enabled,
                        {
                            enabled = it
                            prefs.setOfflineAllowed(tool, it)
                        }
                    )
                }
            }

        if (status.isNotEmpty()) {
            Text(
                status,
                color = Cyan,
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}
