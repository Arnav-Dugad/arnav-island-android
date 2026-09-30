package io.github.arnavdugad.arnavisland

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.arnavisland.link.PeerView
import kotlinx.coroutines.launch

/** How the app looks: the theme, liquid glass (or solid surfaces), and the PC's weather on the glass. */
data class Look(val appearance: Int, val glass: Boolean, val weather: Boolean)

/** This phone, your PCs (here, anywhere), what the island may show of the phone, this phone's own extras, updates and the app's look. */
@Composable fun DevicesScreen(peers: List<PeerView>, pc: PeerView?, running: Boolean, failure: String?, internet: Boolean, look: Look, onLook: (Look) -> Unit,
                              onPair: () -> Unit, onPairCode: () -> Unit, onUpdates: () -> Unit, onHotspot: () -> Unit, padding: PaddingValues) {
    val t = LocalTokens.current; val context = LocalContext.current
    var prefsVersion by remember { mutableIntStateOf(0) }
    // Reading prefsVersion here recomposes the switches when one changes.
    fun flag(key: String, default: Boolean = true): Boolean { prefsVersion.hashCode(); return Hub.prefs.getBoolean(key, default) }
    fun set(key: String, value: Boolean) { Hub.prefs.edit().putBoolean(key, value).apply(); prefsVersion++ }
    // Coming back from Android's settings, what was given is read again.
    var mirrorAllowed by remember { mutableStateOf(Mirror.allowed(context)) }
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var unrestricted by remember { mutableStateOf(power?.isIgnoringBatteryOptimizations(context.packageName) == true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        mirrorAllowed = Mirror.allowed(context); unrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) == true
        onPauseOrDispose { }
    }
    val anywhere = flag("internet")
    // 1.4: the battery's forecast, from this phone's own history, read again each minute while this shows.
    var battery by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { battery = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BatteryForecast.line(context) }; kotlinx.coroutines.delay(60_000) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 20.dp)) {
        ScreenTitle("Devices", over = "Arnav Island")
        // This phone.
        GlassPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(t.accent.copy(alpha = .16f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PhoneAndroid, null, tint = t.accent, modifier = Modifier.size(28.dp)) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(Hub.phoneName(), style = Type.headline, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(failure ?: when { !running -> "Starting…"; internet -> "Reachable on this Wi-Fi and anywhere"; anywhere -> "Visible on this Wi-Fi  ·  connecting anywhere…"; else -> "Visible to your PCs on this Wi-Fi" },
                        style = Type.caption, color = if (failure != null) t.danger else t.muted)
                }
                LiveDot(running && failure == null)
            }
        }
        SectionLabel("Your PCs")
        val paired = peers.filter { it.paired }
        GlassPanel(Modifier.fillMaxWidth()) {
            Column {
                paired.forEachIndexed { i, p ->
                    if (i > 0) Hairline()
                    // 1.3: its connection's quality ring (direct, relay or weak), and how it's reached with its round trip.
                    val q = quality(p)
                    GlassRow(if (p.phone) Icons.Rounded.PhoneAndroid else Icons.Rounded.Laptop, p.name,
                        listOf(q.text, if (p.id == pc?.id) "Remote and sends go here" else "", if (p.online && !p.remote) "Update its island for the remote" else "").filter { it.isNotEmpty() }.joinToString("  ·  "),
                        tint = if (p.online) t.good else t.faint, onClick = { if (!p.phone) Hub.choose(p.id) }, ring = if (p.online) q else null) {
                        var confirm by remember { mutableStateOf(false) }
                        Text(if (confirm) "Forget?" else "Forget", style = Type.caption, color = if (confirm) t.danger else t.muted,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { if (confirm) Hub.forget(p.id) else confirm = true }.padding(8.dp))
                    }
                }
                if (paired.isNotEmpty()) Hairline()
                GlassRow(Icons.Rounded.Add, "Pair a PC", "On this Wi-Fi: both show the same six digits", onClick = onPair)
                Hairline()
                GlassRow(Icons.Rounded.QrCodeScanner, "Scan the QR code", "From anywhere: the island’s Shelf › Nearby › Pair with a code. Or type the code", onClick = onPairCode)
            }
        }
        SectionLabel("Anywhere")
        GlassPanel(Modifier.fillMaxWidth()) {
            Column {
                GlassRow(Icons.Rounded.Public, "Reach my PCs anywhere",
                    if (!anywhere) "Off: only on the same Wi-Fi" else if (internet) "Connected${(Hub.link?.relayBrokersUp ?: 0).let { n -> if (n > 1) " through $n free relays" else if (n == 1) " through a free relay" else "" }}. Any Wi-Fi or mobile data, end-to-end encrypted" else "Connecting… Any Wi-Fi or mobile data, end-to-end encrypted") {
                    GlassSwitch(anywhere, { on -> set("internet", on); Hub.restart() })
                }
                if (!unrestricted && Build.VERSION.SDK_INT >= 23) {
                    Hairline()
                    GlassRow(Icons.Rounded.BatteryAlert, "Always reachable", "Let Android keep Arnav Island running in the background, so your PCs always find it", tint = t.warn) {
                        GlassButton({ runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)) { Text("Allow", style = Type.caption) }
                    }
                }
            }
        }
        SectionLabel("On your PC’s island")
        GlassPanel(Modifier.fillMaxWidth()) {
            Column {
                GlassRow(Icons.Rounded.NotificationsActive, "Your notifications", if (!mirrorAllowed) "Allow notification access so the island can show them" else "New ones show on the island, with the app’s icon; reply and act from the PC") {
                    GlassSwitch(mirrorAllowed && flag("mirror"), { on ->
                        if (on && !mirrorAllowed) runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        set("mirror", on)
                    })
                }
                Hairline()
                GlassRow(Icons.Rounded.Call, "Calls", "See who’s calling on the island, and decline from the PC") { GlassSwitch(mirrorAllowed && flag("calls"), { on -> if (on && !mirrorAllowed) runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; set("calls", on) }) }
                Hairline()
                GlassRow(Icons.Rounded.BatteryChargingFull, "Your battery", (battery?.let { "${it.replaceFirstChar { c -> c.uppercase() }}\n" } ?: "") + "On the island by this phone, and a word when it runs low") { GlassSwitch(flag("battery"), { set("battery", it); if (it) Hub.sendBattery(force = true) }) }
                Hairline()
                // 1.4: this phone's hotspot on the PC's island, one tap to join (its name and password typed once: Android doesn't tell apps).
                val hotspot by Hub.hotspot.collectAsState(); val hotspotOn by Hub.hotspotOn.collectAsState()
                GlassRow(Icons.Rounded.WifiTethering, "Your hotspot", when {
                    hotspot.name.isEmpty() -> "Show it on your PC’s island while it’s on, to join in one tap"
                    hotspotOn && hotspot.enabled -> "“${hotspot.name}” is on: your PC’s island offers to join it"
                    else -> "“${hotspot.name}” shows on your PC’s island while it’s on, to join in one tap"
                }, onClick = onHotspot) {
                    GlassSwitch(hotspot.enabled && hotspot.name.isNotEmpty(), { on -> if (on && hotspot.name.isEmpty()) onHotspot() else Hub.setHotspot(enabled = on) })
                }
                Hairline()
                GlassRow(Icons.Rounded.PhoneAndroid, "Your phone’s details", "Storage, memory, network, sound and more, in the island’s view of this phone") { GlassSwitch(flag("details"), { set("details", it); if (it) Hub.sendDetails(force = true) }) }
                Hairline()
                GlassRow(Icons.Rounded.ContentPaste, "Universal clipboard", "Copy on one, paste on the other, with the island’s own Universal clipboard switch on too") {
                    GlassSwitch(flag("clipboard"), { set("clipboard", it) })
                }
                Hairline()
                GlassRow(Icons.Rounded.Wifi, "Stay reachable", "Files, music and find-my-phone reach this phone while the app is closed") {
                    GlassSwitch(flag("reachable"), { on -> set("reachable", on); if (on) LinkService.start(context) })
                }
                Hairline()
                GlassRow(Icons.Rounded.Download, "Accept files from my PCs", "Without asking each time") { GlassSwitch(flag("autoAccept", false), { set("autoAccept", it) }) }
            }
        }
        SectionLabel("On this phone")
        GlassPanel(Modifier.fillMaxWidth()) {
            Column {
                GlassRow(Icons.Rounded.MusicNote, "Your PC’s music player", "What plays on your PC, on the lock screen and in quick settings, with its controls") {
                    GlassSwitch(flag("pcMedia"), { on -> set("pcMedia", on); if (on) PcMedia.update(context, Hub.pc(), Hub.status.value) else PcMedia.clear(context) })
                }
                val widgets = remember { AppWidgetManager.getInstance(context) }
                if (widgets.isRequestPinAppWidgetSupported) {
                    Hairline()
                    GlassRow(Icons.Rounded.Widgets, "Widgets", "Your PC’s music and quick actions on the home screen") {
                        GlassButton({ runCatching { widgets.requestPinAppWidget(ComponentName(context, PcWidget::class.java), null, null) } }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp), description = "Add the now playing widget") { Text("Music", style = Type.caption) }
                        Spacer(Modifier.width(6.dp))
                        GlassButton({ runCatching { widgets.requestPinAppWidget(ComponentName(context, ActionsWidget::class.java), null, null) } }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp), description = "Add the quick actions widget") { Text("Actions", style = Type.caption) }
                    }
                }
            }
        }
        SectionLabel("Updates")
        UpdateCard(onUpdates)
        SectionLabel("Appearance")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Automatic" to Icons.Rounded.BrightnessAuto, "Dark" to Icons.Rounded.DarkMode, "Light" to Icons.Rounded.LightMode).forEachIndexed { i, (label, icon) ->
                GlassChip(label, icon = icon, selected = look.appearance == i) { onLook(look.copy(appearance = i)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        GlassPanel(Modifier.fillMaxWidth()) {
            Column {
                GlassRow(Icons.Rounded.BlurOn, "Liquid glass", if (look.glass) "Surfaces bend and blur what’s behind them, and catch the light as you tilt the phone" else "Off: solid surfaces, calmer and lighter on the battery") {
                    GlassSwitch(look.glass, { onLook(look.copy(glass = it)) })
                }
                Hairline()
                GlassRow(Icons.Rounded.Umbrella, "Weather on the glass", "Rain, snow or fog on the app when that’s the weather where your PC is") {
                    GlassSwitch(look.weather, { onLook(look.copy(weather = it)) })
                }
            }
        }
        SectionLabel("Privacy")
        GlassPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Shield, null, tint = t.good, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text("Only between your own devices", style = Type.bodyStrong, color = t.text) }
                Spacer(Modifier.height(6.dp))
                Text("On the same Wi-Fi everything goes directly between your devices. On different networks it passes through a free public relay (MQTT over TLS), sealed end to end: the relay sees only random-looking topics and encrypted bytes, never what they carry. Every connection is end-to-end encrypted (ECDH P-256 and AES-256-GCM), only with devices you paired with a code. No account, no cloud storage, no tracking. Otherwise the app goes online only to look for its own updates on GitHub.", style = Type.caption, color = t.muted)
            }
        }
        Spacer(Modifier.height(16.dp))
        GlassButton({ openUrl(context, "https://github.com/${Updates.REPO}") }, Modifier.align(Alignment.CenterHorizontally)) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Arnav Island for Android on GitHub", style = Type.caption) }
        Spacer(Modifier.height(24.dp))
    }
}

/** The app's version, whether a newer one is out (and its download), and the way to the release history. */
@Composable fun UpdateCard(onUpdates: () -> Unit) {
    val t = LocalTokens.current; val context = LocalContext.current; val scope = rememberCoroutineScope()
    val state by Updates.state.collectAsState()
    var auto by remember { mutableStateOf(Updates.auto()) }
    GlassPanel(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(t.accent.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                    when (val s = state) {
                        is Updates.State.Downloading -> ProgressRing(s.fraction, t.accent, t.track, Modifier.size(34.dp), 3.5.dp)
                        is Updates.State.Checking, is Updates.State.Installing -> { val spin by rememberInfiniteTransition(label = "U").animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "R")
                            Icon(Icons.Rounded.Refresh, null, tint = t.accent, modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = spin }) }
                        is Updates.State.Available -> Icon(Icons.Rounded.NewReleases, null, tint = t.accent, modifier = Modifier.size(24.dp))
                        else -> Icon(Icons.Rounded.SystemUpdate, null, tint = t.accent, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Arnav Island ${Updates.current}", style = Type.bodyStrong, color = t.text)
                    Text(when (val s = state) {
                        Updates.State.Idle -> if (Updates.lastChecked() > 0) "Checked ${ago(Updates.lastChecked()).lowercase()}" else "Not checked yet"
                        Updates.State.Checking -> "Checking GitHub…"
                        is Updates.State.Current -> "Up to date  ·  checked ${ago(s.checkedAt).lowercase()}"
                        is Updates.State.Available -> "${s.release.versionName} is out"
                        is Updates.State.Downloading -> "Downloading ${s.release.versionName}  ·  ${(s.fraction * 100).toInt()}%"
                        is Updates.State.Installing -> "Installing ${s.release.versionName}…"
                        is Updates.State.Failed -> s.why
                    }, style = Type.caption, color = if (state is Updates.State.Failed) t.danger else if (state is Updates.State.Available) t.accent else t.muted)
                }
                val available = (state as? Updates.State.Available)?.release
                if (available != null) GlassButton({ scope.launch { if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        Hub.banners.tryEmit(Banner(Banner.Kind.Update, "Allow Arnav Island to install updates", "Then tap Update again")) } else Updates.install(available, context) } },
                    prominent = true, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) { Text("Update", style = Type.bodyStrong) }
                else GlassButton({ scope.launch { Updates.check(force = true) } }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)) { Text("Check", style = Type.caption) }
            }
            Hairline()
            GlassRow(Icons.Rounded.Bolt, "Update automatically", "Checked, then installed quietly") { GlassSwitch(auto, { auto = it; Hub.prefs.edit().putBoolean("autoUpdate", it).apply() }) }
            Hairline()
            GlassRow(Icons.Rounded.History, "Release history", "Every version and what it brought", onClick = onUpdates) { Icon(Icons.Rounded.ChevronRight, null, tint = t.muted) }
        }
    }
}
