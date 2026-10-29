package io.github.arnavdugad.arnavisland

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.arnavisland.link.BatteryHealth
import io.github.arnavdugad.arnavisland.link.Drive
import io.github.arnavdugad.arnavisland.link.IslandWire
import io.github.arnavdugad.arnavisland.link.NetAdapter
import io.github.arnavdugad.arnavisland.link.PeerView
import io.github.arnavdugad.arnavisland.link.StartupApp
import io.github.arnavdugad.arnavisland.link.Storage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * 1.11: what island 0.28 tells about its PC, for the Island tab: the battery's health, the drives and what fills them,
 * what starts with Windows, the network in full, Windows Update, an hour of temperatures, and its apps to open.
 */

/** Sizes as Windows gives them (binary): "512 MB", "45.2 GB", "1.82 TB". */
fun sizeShort(bytes: Long): String = when {
    bytes < 1L shl 30 -> Hub.sizeText(bytes)
    bytes < 1L shl 40 -> "%.1f GB".format(bytes / 1073741824.0)
    else -> "%.2f TB".format(bytes / 1099511627776.0)
}
/** An adapter's link speed: "1 Gb/s", "866.7 Mb/s". */
fun bpsText(bps: Long): String = when {
    bps <= 0 -> "—"
    bps >= 1_000_000_000 -> (bps / 1e9).let { if (it % 1.0 == 0.0) "%.0f Gb/s".format(it) else "%.1f Gb/s".format(it) }
    bps >= 1_000_000 -> (bps / 1e6).let { if (it % 1.0 == 0.0) "%.0f Mb/s".format(it) else "%.1f Mb/s".format(it) }
    else -> "%.0f kb/s".format(bps / 1e3)
}
/** "5 min ago", "just now", "yesterday", or the date, to follow a word ("Checked …"). */
private fun since(at: Long) = ago(at).let { if (it == "Just now" || it == "Yesterday") it.lowercase() else it }
@Composable private fun Swatch(c: Color, size: Dp = 8.dp) { Box(Modifier.size(size).clip(CircleShape).background(c)) }
/** A name and its value, as iOS lists an adapter's details. */
@Composable private fun Detail(label: String, value: String) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.body, color = t.text); Spacer(Modifier.width(16.dp))
        Text(value.ifBlank { "—" }, style = Type.callout, color = t.muted, textAlign = TextAlign.End, modifier = Modifier.weight(1f), maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}
@Composable private fun Waiting(height: Dp) { Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) { Radar(LocalTokens.current.accent, Modifier.size(36.dp)) } }

// ---- battery health (31) ----
/** The battery as it is against as it was made: its health with a bar, cycles, both capacities, chemistry and maker ("—" until the PC says). */
@Composable fun HealthCard(h: BatteryHealth?) {
    val t = LocalTokens.current
    val health = h?.health?.takeIf { it > 0 }
    val colour = when { health == null -> t.faint; health >= .8 -> t.good; health >= .6 -> t.warn; else -> t.danger }
    val f by animateFloatAsState((health ?: 0.0).toFloat().coerceIn(0f, 1f), spring(dampingRatio = .9f, stiffness = 90f), label = "Health")
    fun wh(mwh: Long) = if (mwh > 0) "%.1f Wh".format(mwh / 1000.0) else "—"
    GlassPanel(Modifier.fillMaxWidth()) {
        Column {
            Column(Modifier.padding(16.dp).semantics(mergeDescendants = true) { contentDescription = "Battery health " + (health?.let { "${(it * 100).roundToInt().coerceAtMost(100)} percent" } ?: "unknown") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.FavoriteBorder, t.good, size = 28.dp); Spacer(Modifier.width(14.dp))
                    Text("Health", style = Type.body, color = t.text, modifier = Modifier.weight(1f))
                    RollingNumber(health?.let { "${(it * 100).roundToInt().coerceAtMost(100)}%" } ?: "—", Type.headline, t.text)
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(t.track)) { Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(CircleShape).background(colour)) }
                Text(if (h != null && h.fullMwh > 0 && h.designMwh > 0) "Holds ${wh(h.fullMwh)} of ${wh(h.designMwh)}" else "—", style = Type.caption, color = t.muted, modifier = Modifier.padding(top = 6.dp))
            }
            Hairline(); Fact(Icons.Rounded.Autorenew, "Cycles", h?.cycles?.takeIf { it >= 0 }?.toString() ?: "—", t.blue)
            Hairline(); Fact(Icons.Rounded.BatteryFull, "Full charge", h?.let { wh(it.fullMwh) } ?: "—", t.teal)
            Hairline(); Fact(Icons.Rounded.BatteryStd, "Design", h?.let { wh(it.designMwh) } ?: "—", t.gray)
            Hairline(); Fact(Icons.Rounded.Science, "Chemistry", h?.chemistry?.ifBlank { null } ?: "—", t.purple)
            Hairline(); Fact(Icons.Rounded.Factory, "Made by", h?.let { listOf(it.manufacturer, it.device).filter { s -> s.isNotBlank() }.distinct().joinToString(" ") }?.ifBlank { null } ?: "—", t.indigo)
        }
    }
}

// ---- storage (32) ----
private val storageKinds = listOf("Apps", "Windows", "Documents", "Downloads", "Pictures", "Videos", "Music", "Desktop", "Recycle bin", "Other")
@Composable private fun storageColour(kind: Int): Color { val t = LocalTokens.current
    return when (kind) { 0 -> t.blue; 1 -> t.gray; 2 -> t.indigo; 3 -> t.teal; 4 -> t.yellow; 5 -> t.pink; 6 -> t.purple; 7 -> t.good; 8 -> t.warn; else -> t.faint } }
/**
 * The PC's drives, each as a bar of what's used; the Windows drive's bar in what fills it, as Apple's Storage shows it,
 * with each part's size under it. Measured on the PC (a moment the first time): "Measuring…" meanwhile, asked every 2 s.
 */
@Composable fun StoragePanel(pc: PeerView, visible: Boolean) {
    val t = LocalTokens.current; val s by Hub.storage.collectAsState(); val scope = rememberCoroutineScope()
    LaunchedEffect(visible, pc.id) {
        if (!visible) return@LaunchedEffect; var at = 0L
        while (isActive) { val now = System.currentTimeMillis(); if (Hub.storage.value?.ready == false || now - at >= 60_000) { at = now; Hub.refreshStorage() }; delay(2000) }
    }
    val storage = s
    GlassPanel(Modifier.fillMaxWidth()) {
        Column {
            if (storage == null) Waiting(220.dp)
            else storage.drives.sortedWith(compareByDescending<Drive> { it.system }.thenBy { it.root }).forEachIndexed { i, d -> if (i > 0) Hairline(16.dp); DriveCard(d, if (d.system) storage else null) }
            Hairline(16.dp)
            val measuring = storage?.ready == false
            Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (measuring) { Radar(t.accent, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
                Text(if (measuring) "Measuring… ${storage!!.progress}%" else if (storage != null) "Measured" else "—", style = Type.caption, color = t.muted, modifier = Modifier.weight(1f))
                Text("Measure again", style = Type.body, color = if (measuring || storage == null) t.faint else t.accent,
                    modifier = Modifier.clip(CircleShape).clickable(enabled = !measuring && storage != null, role = Role.Button) { scope.launch { Hub.refreshStorage(again = true) } }.padding(horizontal = 6.dp, vertical = 8.dp))
            }
        }
    }
}
@Composable private fun DriveCard(d: Drive, s: Storage?) {
    val t = LocalTokens.current
    val name = "${d.label.ifBlank { when (d.kind) { 0 -> "Local Disk"; 1 -> "USB drive"; 2 -> "Network drive"; else -> "Drive" } }} (${d.root.trimEnd('\\', '/')})"
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(when (d.kind) { 1 -> Icons.Rounded.Usb; 2 -> Icons.Rounded.Lan; else -> if (d.system) Icons.Rounded.DesktopWindows else Icons.Rounded.Storage }, if (d.system) t.blue else t.teal); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = Type.body, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${sizeShort(d.used)} of ${sizeShort(d.total)} used", style = Type.caption, color = t.muted, maxLines = 1)
            }
            Text("${sizeShort(d.free)} free", style = Type.callout, color = t.muted)
        }
        Spacer(Modifier.height(12.dp))
        if (s == null) {
            val f by animateFloatAsState(if (d.total > 0) (d.used.toDouble() / d.total).toFloat().coerceIn(0f, 1f) else 0f, spring(dampingRatio = .9f, stiffness = 90f), label = "Drive")
            Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(t.track)) { Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(CircleShape).background(if (f > .9f) t.danger else t.teal)) }
        } else Groups(d, s)
    }
}
/** The Windows drive's bar in its parts (in the legend's order), what's used but not yet measured, then what's free; and the legend, two to a row. */
@Composable private fun Groups(d: Drive, s: Storage) {
    val t = LocalTokens.current
    // Each kind once, in its own place while measuring (so nothing moves), largest first once measured.
    val byKind = (0..9).map { k -> k to s.groups.firstOrNull { it.kind == k } }
    val legend = if (s.ready) byKind.sortedByDescending { it.second?.bytes ?: 0 } else byKind
    val total = d.total.coerceAtLeast(1).toFloat()
    // Each part eases to its size by its own kind, whatever its place.
    val eased = byKind.map { (_, g) -> animateFloatAsState((g?.bytes ?: 0) / total, spring(dampingRatio = .9f, stiffness = 90f), label = "Part").value }
    val parts = legend.map { (k, _) -> eased[k] to k }
    val used by animateFloatAsState(d.used / total, spring(dampingRatio = .9f, stiffness = 90f), label = "Used")
    val colours = (0..9).map { storageColour(it) }; val pending = t.faint.copy(alpha = .35f)
    Canvas(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(6.dp)).semantics { contentDescription = "${sizeShort(d.used)} used: " + legend.filter { it.second != null }.joinToString { "${it.second!!.name} ${sizeShort(it.second!!.bytes)}" } }) {
        drawRect(t.track)
        var x = 0f; val gap = 1.dp.toPx()
        for ((f, k) in parts) { val w = f * size.width; if (w <= 0f) continue; drawRect(colours[k], Offset(x, 0f), Size((w - gap).coerceAtLeast(1f), size.height)); x += w }
        // Used, but not in a part yet (still measuring).
        val rest = used * size.width - x; if (rest > 0f) drawRect(pending, Offset(x, 0f), Size(rest, size.height))
    }
    Spacer(Modifier.height(12.dp))
    legend.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            row.forEach { (k, g) ->
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Swatch(colours[k]); Spacer(Modifier.width(7.dp))
                    Text(g?.name?.ifBlank { null } ?: storageKinds[k], style = Type.caption, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(g?.let { sizeShort(it.bytes) } ?: "—", style = Type.caption, color = t.muted, maxLines = 1)
                }
            }
        }
    }
}

// ---- startup apps (33) ----
private fun startupSource(source: Int) = when (source) { 0 -> "This user"; 1 -> "All users"; 2 -> "All users · 32-bit"; 3 -> "Startup folder"; 4 -> "Shared Startup folder"; 5 -> "Store app"; else -> "Startup" }
/** What starts with Windows on the PC, each with a switch (as Task Manager's Startup apps); those for every user are locked. */
@Composable fun StartupPanel(pc: PeerView, visible: Boolean) {
    val t = LocalTokens.current; val list by Hub.startup.collectAsState(); val icons by Hub.startupIcons.collectAsState()
    LaunchedEffect(visible, pc.id) { if (visible) while (isActive) { Hub.refreshStartup(); delay(30_000) } }
    val apps = list
    GlassPanel(Modifier.fillMaxWidth()) {
        Column {
            when {
                apps == null -> Waiting(240.dp)
                apps.isEmpty() -> Text("Nothing starts with Windows", style = Type.body, color = t.muted, modifier = Modifier.padding(16.dp))
                else -> apps.sortedBy { it.name.lowercase() }.forEachIndexed { i, a -> if (i > 0) Hairline(60.dp); StartupRow(a, icons[a.id]) }
            }
        }
    }
    if (!apps.isNullOrEmpty()) Text("${apps.count { it.enabled }} of ${apps.size} on", style = Type.caption, color = t.muted, modifier = Modifier.padding(start = 18.dp, top = 7.dp))
}
@Composable private fun StartupRow(a: StartupApp, icon: androidx.compose.ui.graphics.ImageBitmap?) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(t.fill), contentAlignment = Alignment.Center) {
            if (icon != null) Image(icon, null, Modifier.size(24.dp)) else Icon(Icons.Rounded.Apps, null, tint = t.muted, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, style = Type.body, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(startupSource(a.source), style = Type.caption, color = t.muted, maxLines = 1)
        }
        if (!a.canChange) { Icon(Icons.Rounded.Lock, "For every user", tint = t.faint, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
        GlassSwitch(a.enabled, { on -> Hub.setStartup(a, on) }, Modifier.semantics { contentDescription = a.name }, enabled = a.canChange)
    }
}

// ---- network (34) ----
/** The PC's network in full: the adapter it reaches the internet by first (its Wi-Fi's name and signal, its addresses), the others folded under it. */
@Composable fun NetworkPanel(pc: PeerView, visible: Boolean) {
    val t = LocalTokens.current; val list by Hub.network.collectAsState()
    LaunchedEffect(visible, pc.id) { if (visible) while (isActive) { Hub.refreshNetwork(); delay(5000) } }
    val sorted = list?.sortedWith(compareByDescending<NetAdapter> { it.isDefault }.thenByDescending { it.up }.thenBy { it.kind })
    val main = sorted?.firstOrNull(); val others = sorted?.drop(1).orEmpty()
    GlassPanel(Modifier.fillMaxWidth()) {
        Column {
            when {
                sorted == null -> Waiting(300.dp)
                main == null -> Text("No adapters", style = Type.body, color = t.muted, modifier = Modifier.padding(16.dp))
                else -> {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(if (main.wifi) Icons.Rounded.Wifi else if (main.kind == 0) Icons.Rounded.SettingsEthernet else Icons.Rounded.Lan, if (main.up) t.blue else t.gray, size = 40.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (main.wifi) main.ssid.ifBlank { main.name } else main.name, style = Type.headline, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(if (!main.up) "Not connected" else if (main.isDefault) "Connected" else "Up", main.description).filter { it.isNotBlank() }.joinToString("  ·  "), style = Type.caption, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (main.wifi) { Spacer(Modifier.width(10.dp)); SignalBars(main.signal); Spacer(Modifier.width(6.dp)); Text(if (main.signal >= 0) "${main.signal}%" else "—", style = Type.callout, color = t.muted) }
                    }
                    Hairline(16.dp)
                    if (main.wifi) {
                        Detail("Band", listOf(main.band, if (main.channel > 0) "Channel ${main.channel}" else "").filter { it.isNotBlank() }.joinToString("  ·  ")); Hairline(16.dp)
                        Detail("Security", main.security); Hairline(16.dp)
                    }
                    Detail("IPv4", main.ipv4.joinToString("\n")); Hairline(16.dp)
                    Detail("IPv6", main.ipv6.joinToString("\n")); Hairline(16.dp)
                    Detail("Gateway", main.gateway); Hairline(16.dp)
                    Detail("DNS", main.dns.joinToString("\n")); Hairline(16.dp)
                    Detail("MAC", main.mac.uppercase()); Hairline(16.dp)
                    Detail("Link speed", bpsText(main.linkBps))
                }
            }
        }
    }
    if (others.isNotEmpty()) {
        var open by remember { mutableStateOf(!Hub.prefs.getBoolean("fold_netOthers", true)) }
        Spacer(Modifier.height(12.dp))
        GlassPanel(Modifier.fillMaxWidth()) {
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open = !open; Hub.prefs.edit().putBoolean("fold_netOthers", !open).apply() }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Other adapters", style = Type.body, color = t.text, modifier = Modifier.weight(1f)); Text("${others.size}", style = Type.body, color = t.muted); Spacer(Modifier.width(6.dp)); FoldArrow(open)
                }
                AnimatedVisibility(open, enter = expandVertically(spring(dampingRatio = .9f, stiffness = 420f)) + fadeIn(tween(220)), exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(140))) {
                    Column { others.forEach { a -> Hairline(16.dp); OtherAdapter(a) } }
                }
            }
        }
    }
}
@Composable private fun OtherAdapter(a: NetAdapter) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(if (a.wifi) Icons.Rounded.Wifi else if (a.kind == 0) Icons.Rounded.SettingsEthernet else Icons.Rounded.Lan, if (a.up) t.teal else t.gray); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(if (a.wifi && a.ssid.isNotBlank()) a.ssid else a.name, style = Type.body, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(a.description, a.mac.uppercase()).filter { it.isNotBlank() }.joinToString("  ·  "), style = Type.caption, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Text(if (a.up) a.ipv4.firstOrNull()?.substringBefore('/') ?: bpsText(a.linkBps) else "Not connected", style = Type.callout, color = t.muted, maxLines = 1)
    }
}
/** Wi-Fi's four bars, lit to the signal. */
@Composable private fun SignalBars(signal: Int) {
    val t = LocalTokens.current; val lit = if (signal < 0) 0 else ((signal + 24) / 25).coerceIn(1, 4)
    Canvas(Modifier.size(20.dp, 16.dp).semantics { contentDescription = if (signal < 0) "Signal unknown" else "Signal $signal percent" }) {
        val w = size.width / 4.6f; val gap = (size.width - w * 4) / 3
        for (i in 0 until 4) { val h = size.height * (.35f + .65f * i / 3f); drawRoundRect(if (i < lit) t.text else t.track, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(w / 3, w / 3)) }
    }
}

// ---- Windows Update (35) ----
private fun updateIcon(kind: Int): ImageVector = when (kind) { 0 -> Icons.Rounded.Security; 1 -> Icons.Rounded.Memory; 2 -> Icons.Rounded.NewReleases; 3 -> Icons.Rounded.Shield; else -> Icons.Rounded.SystemUpdate }
private fun updateKind(kind: Int) = when (kind) { 0 -> "Security"; 1 -> "Driver"; 2 -> "Feature"; 3 -> "Definitions"; else -> "Update" }
/**
 * Windows Update on the PC: whether it's up to date, when it last looked and installed, what it has found, and Check now,
 * Open on the PC, and (when it needs one) Restart now, asked first as Power's restart is.
 */
@Composable fun UpdatesPanel(pc: PeerView, visible: Boolean, onConfirm: (Confirm) -> Unit) {
    val t = LocalTokens.current; val u by Hub.updates.collectAsState()
    LaunchedEffect(visible, pc.id) {
        if (!visible) return@LaunchedEffect; var at = 0L
        while (isActive) { val now = System.currentTimeMillis(); if (Hub.updates.value?.state == 4 || now - at >= 30_000) { at = now; Hub.refreshUpdates() }; delay(2000) }
    }
    val state = u?.state ?: -1; val items = u?.items.orEmpty()
    GlassPanel(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (state == 4) Radar(t.accent, Modifier.fillMaxSize())
                    else IconTile(when (state) { 1 -> Icons.Rounded.CheckCircle; 2 -> Icons.Rounded.Download; 3 -> Icons.Rounded.RestartAlt; else -> Icons.Rounded.SystemUpdate }, when (state) { 1 -> t.good; 2 -> t.blue; 3 -> t.warn; else -> t.gray }, size = 40.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(when (state) { -1 -> "—"; 1 -> "Up to date"; 2 -> if (items.isEmpty()) "Updates available" else "${items.size} update${if (items.size == 1) "" else "s"} available"; 3 -> "Restart required"; 4 -> "Checking…"; else -> "Status unknown" },
                        style = Type.headline, color = t.text)
                    Text(listOf(u?.lastChecked?.takeIf { it > 0 }?.let { "Checked ${since(it * 1000)}" }, u?.lastInstalled?.takeIf { it > 0 }?.let { "Installed ${since(it * 1000)}" }).filterNotNull().joinToString("  ·  ").ifEmpty { "—" },
                        style = Type.caption, color = t.muted, maxLines = 2)
                }
            }
            items.forEach { i ->
                Hairline()
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(updateIcon(i.kind), when (i.kind) { 0 -> t.blue; 1 -> t.gray; 2 -> t.purple; 3 -> t.good; else -> t.indigo }); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(i.title, style = Type.body, color = t.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(updateKind(i.kind), i.size.takeIf { it > 0 }?.let(::sizeShort), if (i.downloaded) "Downloaded" else null, if (i.needsRestart) "Needs a restart" else null).joinToString("  ·  "), style = Type.caption, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassButton({ Hub.checkUpdates() }, Modifier.weight(1f), enabled = state != 4 && u != null, contentPadding = PaddingValues(vertical = 12.dp)) { Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Check now", style = Type.bodyStrong) }
        GlassButton({ Hub.openUpdates() }, Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp)) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open on PC", style = Type.bodyStrong) }
    }
    if (state == 3) {
        val haptics = LocalHapticFeedback.current
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().clip(GlassShapes.capsule).background(t.warn).clickable(role = Role.Button) {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onConfirm(Confirm("Restart ${pc.name}?", "Unsaved work may be lost.", "Restart", true) { Hub.setControl(IslandWire.RESTART, 1) })
        }.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.RestartAlt, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Restart now", style = Type.bodyStrong, color = Color.White) }
        }
    }
}

// ---- an hour of temperatures (25, kept here) ----
/**
 * The PC's processor and graphics temperatures (and its fan, when it tells), now and over the last hour on this phone
 * (a point every ten seconds while they're read); touch and drag along the graph for any moment.
 */
@Composable fun HeatPanel() {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    val trail by Hub.heatTrail.collectAsState(); val s by Hub.sensors.collectAsState()
    val now = System.currentTimeMillis(); val hour = remember(trail) { trail.filter { now - it.at <= 3_600_000 } }
    val fans = hour.any { it.fan >= 0 } || s?.fans?.isNotEmpty() == true
    val read = s; val none = read != null && !read.cpu.isFinite() && !read.gpu.isFinite() && read.fans.isEmpty() && hour.isEmpty()
    var at by remember { mutableStateOf<Int?>(null) }
    fun deg(v: Float?) = if (v == null || !v.isFinite() || v < 0) "—" else "${v.roundToInt()}°C"
    Section(null) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Temperatures", style = Type.bodyStrong, color = t.text, modifier = Modifier.weight(1f))
                Swatch(t.blue); Text(" CPU  ", style = Type.caption, color = t.muted); Swatch(t.purple); Text(" GPU", style = Type.caption, color = t.muted)
                if (fans) { Text("  ", style = Type.caption); Swatch(t.teal); Text(" Fan", style = Type.caption, color = t.muted) }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Reading("CPU", deg(s?.cpu)); Reading("GPU", deg(s?.gpu))
                if (fans) Reading("Fan", s?.fans?.maxOfOrNull { it.second }?.let { "$it rpm" } ?: "—")
            }
            Spacer(Modifier.height(12.dp))
            val first = hour.firstOrNull()?.at ?: 0L; val span = ((hour.lastOrNull()?.at ?: 0L) - first).coerceAtLeast(1).toFloat()
            val high = max(60f, hour.maxOfOrNull { max(it.cpu, it.gpu) } ?: 0f).let { listOf(60f, 80f, 100f, 120f).firstOrNull { step -> step >= it * 1.08f } ?: it * 1.1f }
            val fanHigh = (hour.maxOfOrNull { it.fan } ?: 0).coerceAtLeast(1) * 1.15f
            Box(Modifier.fillMaxWidth().height(96.dp).pointerInput(hour.size) {
                awaitEachGesture {
                    val down = awaitFirstDown(); down.consume()
                    fun index(x: Float): Int? { if (hour.size < 2) return null; val time = first + (x / size.width).coerceIn(0f, 1f) * span; return hour.indices.minByOrNull { abs(hour[it].at - time) } }
                    at = index(down.position.x); haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    while (true) { val e = awaitPointerEvent(); val c = e.changes.firstOrNull() ?: break; if (!c.pressed) break; c.consume(); val i = index(c.position.x); if (i != at) { at = i; if (i != null && i % 6 == 0) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) } }
                    at = null
                }
            }) {
                Canvas(Modifier.fillMaxSize().semantics { contentDescription = "The last hour's temperatures" }) {
                    for (k in 1..3) drawLine(t.hairline, Offset(0f, size.height * k / 4f), Offset(size.width, size.height * k / 4f), .5.dp.toPx())
                    if (hour.size < 2) return@Canvas
                    fun x(p: Hub.HeatPoint) = (p.at - first) / span * size.width
                    fun line(pick: (Hub.HeatPoint) -> Float, top: Float, c: Color, w: Float) {
                        val path = Path(); var started = false; var last = 0L
                        // A gap (the tab wasn't open) breaks the line rather than bridging it.
                        hour.forEach { p -> val v = pick(p); if (v < 0) { started = false; return@forEach }
                            val y = size.height - (v / top).coerceIn(0f, 1f) * size.height * .92f
                            if (!started || p.at - last > 90_000) path.moveTo(x(p), y) else path.lineTo(x(p), y); started = true; last = p.at }
                        drawPath(path, c, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    if (fans) line({ it.fan.toFloat() }, fanHigh, t.teal.copy(alpha = .7f), 1.4.dp.toPx())
                    line({ it.gpu }, high, t.purple, 1.8.dp.toPx()); line({ it.cpu }, high, t.blue, 1.8.dp.toPx())
                    at?.takeIf { it < hour.size }?.let { i -> val px = x(hour[i]); drawLine(t.text.copy(alpha = .35f), Offset(px, 0f), Offset(px, size.height), 1.dp.toPx()) }
                }
                when {
                    none -> Text("No sensors", style = Type.caption, color = t.faint, modifier = Modifier.align(Alignment.Center))
                    hour.size < 2 -> Text("Measuring…", style = Type.caption, color = t.faint, modifier = Modifier.align(Alignment.Center))
                    at == null -> Text("${high.roundToInt()}°", style = Type.micro, color = t.faint, modifier = Modifier.align(Alignment.TopEnd))
                }
                at?.takeIf { it < hour.size }?.let { i -> val p = hour[i]; val ago = ((System.currentTimeMillis() - p.at) / 1000).coerceAtLeast(0)
                    Text(listOfNotNull("CPU ${deg(p.cpu)}", "GPU ${deg(p.gpu)}", if (p.fan >= 0) "${p.fan} rpm" else null, if (ago < 90) "$ago s ago" else "${ago / 60} min ago").joinToString("  ·  "),
                        style = Type.micro, color = t.text, modifier = Modifier.align(Alignment.TopCenter).glass(GlassShapes.capsule, GlassLevel.Control).padding(horizontal = 10.dp, vertical = 4.dp)) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(hour.firstOrNull()?.let { ago(it.at) } ?: "—", style = Type.micro, color = t.faint, modifier = Modifier.weight(1f)); Text("Now", style = Type.micro, color = t.faint)
            }
        }
    }
}
@Composable private fun Reading(label: String, value: String) {
    val t = LocalTokens.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) { RollingNumber(value, Type.headline, t.text); Text(label, style = Type.caption, color = t.muted) }
}

// ---- the PC's apps (37) ----
/** The PC's Start menu's apps in a grid, searchable; a tap opens one there. */
@Composable fun PcAppsSheet(visible: Boolean, pc: PeerView?, onDismiss: () -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current; val scope = rememberCoroutineScope()
    val apps by Hub.pcApps.collectAsState(); var query by remember { mutableStateOf("") }; var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(visible, pc?.id) { if (visible) { query = ""; loaded = apps.isNotEmpty(); Hub.refreshPcApps(icons = true); loaded = true } }
    GlassSheet(visible, onDismiss, Modifier.fillMaxHeight(.92f)) {
        SheetHeader("Apps on ${pc?.name ?: "your PC"}", null, onDismiss)
        Spacer(Modifier.height(8.dp))
        SearchField(query, { query = it.take(80) }, "Search")
        Spacer(Modifier.height(10.dp))
        val q = query.trim().lowercase(); val list = remember(apps, q) { apps.filter { q.isEmpty() || q in it.name.lowercase() } }
        when {
            pc == null || !Hub.rev11(pc) -> Text("Needs island 0.28", style = Type.body, color = t.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
            apps.isEmpty() && !loaded -> Waiting(240.dp)
            list.isEmpty() -> Text(if (apps.isEmpty()) "No apps" else "Nothing matches", style = Type.body, color = t.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
            else -> LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.id }) { a ->
                    AppCell(a) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); scope.launch { Hub.openPcApp(a.id) } }
                }
            }
        }
    }
}
@Composable private fun AppCell(a: PcApp, onOpen: () -> Unit) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { onOpen() }.semantics { contentDescription = "Open ${a.name}" }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(t.fill), contentAlignment = Alignment.Center) {
            if (a.icon != null) Image(a.icon, null, Modifier.size(40.dp)) else Icon(Icons.Rounded.Apps, null, tint = t.muted, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(a.name, style = Type.caption, color = t.text, textAlign = TextAlign.Center, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
    }
}
