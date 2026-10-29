package io.github.arnavdugad.arnavisland

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.arnavdugad.arnavisland.link.AudioOutput
import io.github.arnavdugad.arnavisland.link.CommandRow
import io.github.arnavdugad.arnavisland.link.IslandSettings
import io.github.arnavdugad.arnavisland.link.IslandSetting
import io.github.arnavdugad.arnavisland.link.IslandWire
import io.github.arnavdugad.arnavisland.link.Proto
import io.github.arnavdugad.arnavisland.link.PcControls
import io.github.arnavdugad.arnavisland.link.PcStats
import io.github.arnavdugad.arnavisland.link.PcBattery
import io.github.arnavdugad.arnavisland.link.PeerView
import io.github.arnavdugad.arnavisland.link.hex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt


/**
 * Everything on your PC's island, from here (island 0.22), as a dashboard: an overview (the PC, its rings, its
 * connection), its performance live (the processor with each core, graphics, memory, disk and network, with the session's
 * peaks and totals), its battery in full, its sound (each app's own volume, island 0.26), its controls, the focus clock,
 * its command bar, power, alerts, its pages, every one of its settings, and what it is. 1.8: each section folds away,
 * and once the title has scrolled off a row of its names stays at the top to jump to any of them.
 */
@Composable fun IslandScreen(pc: PeerView?, visible: Boolean, bar: TopBar, padding: PaddingValues) {
    val stats by Hub.pcStats.collectAsState(); val controls by Hub.pcControls.collectAsState(); val settings by Hub.islandSettings.collectAsState(); val outputs by Hub.outputs.collectAsState()
    val battery by Hub.pcBattery.collectAsState(); val peers by Hub.peers.collectAsState()
    val pcs = remember(peers) { peers.filter { it.paired && !it.phone } }
    val confirm: (Confirm) -> Unit = { IslandUi.confirm = it }
    val ready = Hub.islandReady(pc)
    val owner = LocalLifecycleOwner.current; val scope = rememberCoroutineScope(); val density = LocalDensity.current
    LaunchedEffect(pc?.id, ready, visible) {
        if (!ready || !visible) return@LaunchedEffect
        Hub.loadOutputs(); if (Hub.islandSettings.value == null) Hub.loadIslandSettings()
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var n = 0
            while (true) { Hub.refreshIsland(withStats = true); if ((pc?.revision ?: 0) >= 9) Hub.refreshMixer(); if (n % 3 == 0 && Hub.rev10(pc)) Hub.refreshSensors(); if (++n % 10 == 0) Hub.loadOutputs(); delay(1000) }
        }
    }
    val scroll = rememberScrollState()
    // Where each section starts (in the scrolled column), for the jump row.
    val anchors = remember { mutableStateMapOf<String, Int>() }
    val order by Hub.sectionOrder.collectAsState(); val pins by Hub.sectionPins.collectAsState()
    val sequence = remember(order, pins) { orderedSections(order, pins) }
    val shown = sequence.mapNotNull { k -> IslandSections.firstOrNull { it.first == k } }.filter { (k, _) -> k in anchors }
    val offset = with(density) { 58.dp.roundToPx() }
    val current by remember { derivedStateOf { shown.lastOrNull { (k, _) -> (anchors[k] ?: Int.MAX_VALUE) <= scroll.value + offset + 4 }?.first ?: shown.firstOrNull()?.first } }
    fun jump(key: String) { anchors[key]?.let { y -> scope.launch { scroll.animateScrollTo((y - offset).coerceAtLeast(0)) } } }
    bar.follow("Island", scroll, actions = if (ready) ({ GlassButton({ MoreUi.sections = true }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) { Text("Edit", style = Type.bodyStrong) } }) else null,
        accessory = if (ready) ({ JumpRow(shown, current, ::jump) }) else null)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(scroll).padding(padding).padding(horizontal = 16.dp)) {
            ScreenTitle("Island", over = pc?.let { if (!it.online) "${it.name}  ·  away" else "${it.name}  ·  ${quality(it).text}" } ?: "No PC yet") { if (pc != null) LiveDot(pc.online) }
            // With more than one PC, a tap moves the whole tab to another.
            if (pc != null && pcs.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp, start = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pcs.forEach { p -> GlassChip(p.name, icon = pcIcon(p.id), selected = p.id == pc.id, iconTint = if (p.online) pcColor(p.id) else Color.Unspecified) { Hub.choose(p.id) } }
            }
            when {
                pc == null -> Notice(Icons.Rounded.Laptop, "Pair with your PC", null, "Pair") { Ui.pair() }
                !pc.online -> AwayNotice(pc)
                pc.revision < 5 -> Notice(Icons.Rounded.SystemUpdate, "Update Arnav Island on ${pc.name}", "Needs version 0.22 or later.", null) {}
                else -> {
                    if (shown.size > 1) { JumpRow(shown, current, ::jump, inset = false); Spacer(Modifier.height(4.dp)) }
                    // 1.9: in your order, the pinned ones first.
                    for (key in sequence) when (key) {
                        "overview" -> Fold(key, anchors, pinned = key in pins) { Overview(pc, stats, controls) }
                        "performance" -> Fold(key, anchors, pinned = key in pins) { Performance(stats); Spacer(Modifier.height(12.dp)); HourPanel() }
                        "apps" -> if (Hub.rev10(pc) && Proto.CMD_PROCESSES !in Hub.missing.collectAsState().value) Fold(key, anchors, "Apps on ${pc.name}", pinned = key in pins) { AppsPanel(pc, visible, confirm) }
                        "battery" -> if (battery?.present == true) Fold(key, anchors, pinned = key in pins) { BatteryPanel(battery!!) }
                        "sound" -> Fold(key, anchors, pinned = key in pins) { SoundPanel(pc, controls, outputs, settings) }
                        "controls" -> Fold(key, anchors, pinned = key in pins) { ControlsPanel(controls) }
                        "focus" -> Fold(key, anchors, pinned = key in pins) { FocusPanel(controls) }
                        "run" -> Fold(key, anchors, "Run on ${pc.name}", pinned = key in pins) { CommandPanel(pc.name, confirm) }
                        "power" -> Fold(key, anchors, pinned = key in pins) { PowerPanel(pc, confirm) }
                        "alerts" -> Fold(key, anchors, pinned = key in pins) { AlertsPanel() }
                        "pages" -> Fold(key, anchors, "Show on ${pc.name}", pinned = key in pins) { PagesPanel() }
                        "settings" -> Fold(key, anchors, "Island settings", pinned = key in pins) { SettingsPanel(settings) { IslandUi.section = it } }
                        "about" -> Fold(key, anchors, "About ${pc.name}", pinned = key in pins) { AboutPanel(pc, stats) }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** The sections' names in a row of chips: the one in view lit, a tap scrolls to another. */
@Composable private fun JumpRow(sections: List<Pair<String, String>>, current: String?, onJump: (String) -> Unit, inset: Boolean = true) {
    val row = rememberScrollState(); val density = LocalDensity.current
    val positions = remember { mutableStateMapOf<String, Int>() }
    // The lit chip stays in view as the page scrolls.
    LaunchedEffect(current) { positions[current]?.let { x -> row.animateScrollTo((x - with(density) { 40.dp.roundToPx() }).coerceAtLeast(0)) } }
    Row(Modifier.fillMaxWidth().horizontalScroll(row).padding(horizontal = if (inset) 16.dp else 2.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sections.forEach { (k, name) -> GlassChip(name, Modifier.onPlaced { positions[k] = it.positionInParent().x.roundToInt() }, selected = k == current) { onJump(k) } }
    }
}

/**
 * A section that folds: its header (a tap folds or opens it, remembered), and its content easing open or shut. It notes
 * where it starts, for the jump row.
 */
@Composable private fun Fold(key: String, anchors: MutableMap<String, Int>, title: String = IslandSections.first { it.first == key }.second, pinned: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(!Hub.prefs.getBoolean("fold_$key", false)) }
    val haptics = LocalHapticFeedback.current
    DisposableEffect(key) { onDispose { anchors.remove(key) } }
    Column(Modifier.fillMaxWidth().onPlaced { anchors[key] = it.positionInParent().y.roundToInt() }) {
        SectionLabel(title, Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); open = !open; Hub.prefs.edit().putBoolean("fold_$key", !open).apply()
        }.semantics { contentDescription = "$title, ${if (open) "open" else "folded"}" }) { if (pinned) { Icon(Icons.Rounded.PushPin, "Pinned", tint = LocalTokens.current.warn, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)) }; FoldArrow(open) }
        AnimatedVisibility(open, enter = expandVertically(spring(dampingRatio = .9f, stiffness = 420f)) + fadeIn(tween(220)), exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(140))) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

/** The Island tab's sheets, drawn over the whole app (the tab bar too), as the app's other sheets are. */
object IslandUi {
    var statsOpen by mutableStateOf(false); var section by mutableStateOf<Int?>(null); var confirm by mutableStateOf<Confirm?>(null)
}
@Composable fun IslandSheets() {
    val stats by Hub.pcStats.collectAsState(); val settings by Hub.islandSettings.collectAsState()
    StatsSheet(IslandUi.statsOpen && stats != null, stats) { IslandUi.statsOpen = false }
    SectionSheet(IslandUi.section, settings, onConfirm = { IslandUi.confirm = it }) { IslandUi.section = null }
    ConfirmSheet(IslandUi.confirm) { IslandUi.confirm = null }
}

/** Something to confirm first: what it does, and whether it can't be undone. */
class Confirm(val title: String, val detail: String, val action: String, val danger: Boolean, val run: () -> Unit)

@Composable private fun ConfirmSheet(c: Confirm?, onDismiss: () -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    GlassSheet(c != null, onDismiss) {
        if (c == null) return@GlassSheet
        Text(c.title, style = Type.title2, color = t.text, textAlign = TextAlign.Center)
        if (c.detail.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(c.detail, style = Type.body, color = t.muted, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(24.dp))
        if (c.danger) Box(Modifier.fillMaxWidth().clip(GlassShapes.capsule).background(t.danger).clickable(role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); c.run(); onDismiss() }.padding(vertical = 15.dp), contentAlignment = Alignment.Center) {
            Text(c.action, style = Type.headline, color = Color.White)
        } else GlassButton({ haptics.performHapticFeedback(HapticFeedbackType.Confirm); c.run(); onDismiss() }, Modifier.fillMaxWidth(), prominent = true, contentPadding = PaddingValues(vertical = 15.dp)) { Text(c.action, style = Type.headline) }
        Spacer(Modifier.height(10.dp))
        GlassButton(onDismiss, Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 15.dp)) { Text("Cancel", style = Type.headline) }
    }
}

@Composable private fun Notice(icon: ImageVector, title: String, detail: String?, action: String?, onAction: () -> Unit) {
    val t = LocalTokens.current
    GlassPanel(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconTile(icon, t.blue, size = 58.dp)
            Spacer(Modifier.height(14.dp)); Text(title, style = Type.headline, color = t.text, textAlign = TextAlign.Center)
            if (detail != null) { Spacer(Modifier.height(6.dp)); Text(detail, style = Type.body, color = t.muted, textAlign = TextAlign.Center) }
            if (action != null) { Spacer(Modifier.height(18.dp)); GlassButton(onAction, prominent = true, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 13.dp)) { Text(action, style = Type.bodyStrong) } }
        }
    }
}
/** 1.8: the PC is away: when it was last seen, and a way to wake it when this phone knows its adapters (#38, #39). */
@Composable private fun AwayNotice(pc: PeerView) {
    val t = LocalTokens.current; val seen by Hub.lastSeen.collectAsState(); val machines by Hub.machines.collectAsState()
    GlassPanel(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconTile(Icons.Rounded.Bedtime, t.indigo, size = 58.dp)
            Spacer(Modifier.height(14.dp)); Text("${pc.name} is away", style = Type.headline, color = t.text)
            seen[pc.id]?.let { Spacer(Modifier.height(6.dp)); Text("Last seen ${ago(it).lowercase()}", style = Type.body, color = t.muted, textAlign = TextAlign.Center) }
            if (!machines[pc.id].isNullOrEmpty()) { Spacer(Modifier.height(18.dp)); GlassButton({ Hub.wake(pc.id) }, prominent = true, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 13.dp)) { Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Wake ${pc.name}", style = Type.bodyStrong) } }
        }
    }
}

// ---- overview ----
/** The PC at a glance: what it is and how long it's been on, its connection, and four live rings. */
@Composable private fun Overview(pc: PeerView, s: PcStats?, c: PcControls?) {
    val t = LocalTokens.current; val trail by Hub.statsTrail.collectAsState()
    // Heat: the processor's average over the last 30 s, past 55%, up to full warmth at 90%.
    val heatTarget = trail.takeLast(30).map { it.cpu }.filter { it >= 0 }.takeIf { it.size >= 10 }?.average()?.toFloat()?.let { ((it - 55f) / 35f).coerceIn(0f, 1f) } ?: 0f
    val heat by animateFloatAsState(heatTarget, tween(1400), label = "Heat")
    val warm = Color(0xFFFF8A3D)
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(pcIcon(pc.id), pcColor(pc.id), size = 54.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(pc.name, style = Type.title2, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(s?.model?.ifBlank { null }, s?.os?.ifBlank { null }).joinToString("  ·  ").ifEmpty { "Windows PC" }, style = Type.caption, color = t.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val q = quality(pc)
                GlassChip(q.text, icon = if (pc.internet) Icons.Rounded.Public else Icons.Rounded.Wifi, iconTint = qualityColor(q.kind), onClick = { Ui.connection = pc.id })
                // 1.9: its temperatures (island 0.27), first after how it's reached.
                val heat by Hub.sensors.collectAsState()
                heat?.cpu?.takeIf { !it.isNaN() }?.let { GlassChip("CPU ${it.roundToInt()}°C", icon = Icons.Rounded.Thermostat, iconTint = if (it >= 85) t.danger else if (it >= 70) t.warn else t.good) }
                heat?.gpu?.takeIf { !it.isNaN() }?.let { GlassChip("GPU ${it.roundToInt()}°C", icon = Icons.Rounded.LocalFireDepartment, iconTint = if (it >= 85) t.danger else if (it >= 70) t.warn else t.good) }
                if (s != null) GlassChip("On ${span(s.uptime)}", icon = Icons.Rounded.Timer)
                if (s != null && s.logical > 0) GlassChip("${s.logical} threads", icon = Icons.Rounded.Memory)
                if (c != null && c.focusRunning) GlassChip("Focusing", icon = Icons.Rounded.CenterFocusStrong, iconTint = t.purple)
                if (s != null && s.battery >= 0) GlassChip("${s.battery}%", icon = if (s.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryFull, iconTint = if (s.charging) t.good else Color.Unspecified)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { IslandUi.statsOpen = true }.semantics { contentDescription = "Your PC's numbers. Tap for more" }, horizontalArrangement = Arrangement.SpaceEvenly) {
                Gauge("CPU", s?.cpu ?: -1.0, lerp(t.blue, warm, heat)); Gauge("GPU", s?.gpu ?: -1.0, t.purple)
                Gauge("Memory", s?.ramPercent ?: -1.0, t.good); Gauge("Disk", s?.diskUsedPercent ?: -1.0, t.teal)
            }
        }
    }
}
/** A ring with its number rolling in its middle (1.8), and its name under it. */
@Composable private fun Gauge(label: String, value: Double, color: Color, size: Dp = 70.dp) {
    val t = LocalTokens.current; val known = value >= 0
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = if (known) "$label ${value.roundToInt()} percent" else "$label unknown" }) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            ProgressRing(if (known) (value / 100).toFloat() else 0f, color, t.track, Modifier.fillMaxSize(), 6.dp)
            if (known) RollingNumber("${value.roundToInt()}", Type.headline, t.text) else Text("—", style = Type.headline, color = t.faint)
        }
        Spacer(Modifier.height(6.dp)); Text(label, style = Type.caption, color = t.muted)
    }
}

// ---- performance ----
/**
 * The PC working, live: the processor (its graph and each core), graphics, memory and disk as bars, the network both ways,
 * and the session's peaks, averages and totals (from the numbers read while this tab showed).
 */
@Composable private fun Performance(s: PcStats?) {
    val t = LocalTokens.current; val trail by Hub.statsTrail.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Processor", s?.cpu, s?.cpuHistory.orEmpty(), t.blue, Modifier.weight(1f))
            MetricCard("Graphics", s?.gpu, s?.gpuHistory.orEmpty(), t.purple, Modifier.weight(1f))
        }
        if (!s?.cores.isNullOrEmpty()) GlassPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Each core", style = Type.bodyStrong, color = t.text); Spacer(Modifier.height(10.dp)); CoreBars(s!!.cores, t.blue, true) } }
        GlassPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                if (s != null) {
                    Bar(Icons.Rounded.Memory, "Memory", s.ramPercent, "%.1f of %.1f GB".format(s.ramUsedGiB, s.ramTotalGiB), t.good)
                    if (s.diskUsedPercent >= 0) { Spacer(Modifier.height(14.dp)); Bar(Icons.Rounded.Storage, "Disk", s.diskUsedPercent, "%.0f GB free of %.0f".format(s.diskFreeGiB, s.diskTotalGiB), t.teal) }
                } else Text("Reading…", style = Type.caption, color = t.muted)
            }
        }
        // The network both ways.
        GlassPanel(Modifier.fillMaxWidth().clip(GlassShapes.card).clickable { IslandUi.statsOpen = true }) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Network", style = Type.bodyStrong, color = t.text, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.South, null, tint = t.good, modifier = Modifier.size(15.dp)); RollingNumber(s?.let { rateText(it.download) } ?: "—", Type.callout, t.text)
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Rounded.North, null, tint = t.warn, modifier = Modifier.size(15.dp)); RollingNumber(s?.let { rateText(it.upload) } ?: "—", Type.callout, t.text)
                }
                Spacer(Modifier.height(10.dp))
                val downs = trail.map { it.download }.takeIf { it.size >= 10 } ?: s?.downloadHistory.orEmpty()
                Box(Modifier.fillMaxWidth().height(56.dp)) {
                    MorphGraph(downs, t.good, Modifier.fillMaxSize())
                    if (trail.size >= 10) MorphGraph(trail.map { it.upload }, t.warn, Modifier.fillMaxSize(), top = (downs.maxOrNull() ?: 1f) * 1.15f, fill = false)
                }
            }
        }
        // The session, while this tab has shown: there from the start ("—" until it has numbers), so nothing below it moves.
        run {
            val cpus = trail.map { it.cpu }.filter { it >= 0 }; val gpus = trail.map { it.gpu }.filter { it >= 0 }
            var down = 0.0; var up = 0.0
            for (i in 1 until trail.size) { val dt = (trail[i].at - trail[i - 1].at) / 1000.0; if (dt in 0.0..10.0) { down += trail[i].download * dt; up += trail[i].upload * dt } }
            Section(null) {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat("Peak CPU", cpus.maxOrNull()?.let { "${it.roundToInt()}%" } ?: "—"); Stat("Average", cpus.takeIf { it.isNotEmpty() }?.average()?.let { "${it.roundToInt()}%" } ?: "—")
                    Stat("Peak GPU", gpus.maxOrNull()?.let { "${it.roundToInt()}%" } ?: "—")
                }
                Hairline(16.dp)
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat("Downloaded", Hub.sizeText(down.toLong())); Stat("Sent", Hub.sizeText(up.toLong()))
                    Stat("Top speed", rateText((trail.maxOfOrNull { it.download } ?: 0f).toDouble()))
                }
            }
        }
    }
}
@Composable private fun Stat(label: String, value: String) {
    val t = LocalTokens.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) { RollingNumber(value, Type.headline, t.text); Text(label, style = Type.caption, color = t.muted) }
}
/** A metric in a tile: its name, its number rolling, and its last samples gliding by. */
@Composable private fun MetricCard(title: String, value: Double?, history: List<Float>, color: Color, modifier: Modifier) {
    val t = LocalTokens.current
    GlassPanel(modifier.clip(GlassShapes.card).clickable { IslandUi.statsOpen = true }) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = t.muted)
            Row(verticalAlignment = Alignment.Bottom) {
                RollingNumber(if (value == null || !(value >= 0)) "—" else "${value.roundToInt()}", Type.title, t.text)
                if (value != null && value >= 0) Text("%", style = Type.headline, color = t.muted, modifier = Modifier.padding(bottom = 3.dp, start = 1.dp))
            }
            Spacer(Modifier.height(8.dp))
            MorphGraph(history, color, Modifier.fillMaxWidth().height(44.dp), top = 100f)
        }
    }
}
/** A share of something as a capsule filled to it, with its name and what it means. */
@Composable private fun Bar(icon: ImageVector, title: String, percent: Double, detail: String, color: Color) {
    val t = LocalTokens.current; val f by animateFloatAsState((percent / 100).toFloat().coerceIn(0f, 1f), spring(dampingRatio = .9f, stiffness = 90f), label = "Bar")
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(title, style = Type.bodyStrong, color = t.text, modifier = Modifier.weight(1f)); RollingNumber("${if (percent.isNaN()) 0 else percent.roundToInt()}%", Type.callout, t.text)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(t.track)) { Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(CircleShape).background(Brush.horizontalGradient(listOf(color.copy(alpha = .75f), color)))) }
        Text(detail, style = Type.caption, color = t.muted, modifier = Modifier.padding(top = 5.dp))
    }
}

/** 1.5: a bar for each core, its height its load; a busy core (70% or more) shimmers, and the bars ease between readings. */
@Composable private fun CoreBars(cores: List<Float>, color: Color, live: Boolean) {
    val t = LocalTokens.current; val reduced = LocalReduced.current
    val shimmer by rememberInfiniteTransition(label = "Cores").animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "Shimmer")
    val shown = cores.map { v -> animateFloatAsState(if (v < 0) 0f else v / 100f, spring(dampingRatio = .85f, stiffness = 140f), label = "Core").value }
    Column {
        Canvas(Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = "${cores.size} cores: " + cores.joinToString { if (it < 0) "unknown" else "${it.roundToInt()}%" } }) {
            val n = shown.size; val gap = if (n > 24) 2.dp.toPx() else 3.dp.toPx(); val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f); val r = (w / 2).coerceAtMost(3.dp.toPx())
            shown.forEachIndexed { i, v ->
                val x = i * (w + gap); val h = (v * size.height).coerceAtLeast(2.dp.toPx())
                drawRoundRect(t.track, Offset(x, 0f), Size(w, size.height), CornerRadius(r, r))
                drawRoundRect(lerp(color, Color(0xFFFF8A3D), ((v - .6f) / .4f).coerceIn(0f, 1f)).copy(alpha = .6f + .4f * v), Offset(x, size.height - h), Size(w, h), CornerRadius(r, r))
                // Busy: a band of light rises through the bar, each core a little behind the one before.
                if (live && !reduced && v >= .7f) {
                    val phase = (shimmer + i * .07f) % 1f; val y = size.height - h * phase
                    drawRoundRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .5f), Color.Transparent), y - 10.dp.toPx(), y + 10.dp.toPx()), Offset(x, size.height - h), Size(w, h), CornerRadius(r, r))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("${cores.size} cores", style = Type.caption, color = t.muted, modifier = Modifier.weight(1f))
            val busy = cores.count { it >= 70f }; if (busy > 0) Text("$busy busy", style = Type.caption, color = t.warn)
        }
    }
}

/**
 * 1.8: a graph that morphs from one reading to the next instead of jumping: the line glides left by one sample as the
 * new one grows out of the last, and its scale eases to the new highest. [top]: the scale's top (null: the samples' own
 * highest, for rates); [fill]: the soft area under the line.
 */
@Composable fun MorphGraph(samples: List<Float>, color: Color, modifier: Modifier, top: Float? = null, fill: Boolean = true) {
    val reduced = LocalReduced.current
    val p = remember { Animatable(1f) }
    LaunchedEffect(samples) { if (reduced || samples.size < 2) p.snapTo(1f) else { p.snapTo(0f); p.animateTo(1f, tween(950, easing = LinearOutSlowInEasing)) } }
    val known = samples.map { if (it < 0) 0f else it }
    val high by animateFloatAsState(max(top ?: ((known.maxOrNull() ?: 1f) * 1.15f), 1f), tween(800), label = "Scale")
    Canvas(modifier.clipToBounds()) {
        if (known.size < 2) return@Canvas
        val step = size.width / (known.size - 1); val shift = (1f - p.value) * step; val n = known.size
        fun y(v: Float) = size.height - (v / high).coerceIn(0f, 1f) * size.height * .9f
        val path = Path(); val area = Path()
        for (i in 0 until n) {
            val v = if (i == n - 1) known[n - 2] + (known[n - 1] - known[n - 2]) * p.value else known[i]
            val x = i * step + shift; val yy = y(v)
            if (i == 0) { path.moveTo(x, yy); area.moveTo(x, size.height); area.lineTo(x, yy) } else { path.lineTo(x, yy); area.lineTo(x, yy) }
        }
        area.lineTo((n - 1) * step + shift, size.height); area.close()
        if (fill) drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = .3f), Color.Transparent)))
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val lv = known[n - 2] + (known[n - 1] - known[n - 2]) * p.value; val lx = ((n - 1) * step + shift).coerceAtMost(size.width - 3.dp.toPx())
        drawCircle(color.copy(alpha = .3f), 6.dp.toPx(), Offset(lx, y(lv))); drawCircle(color, 3.dp.toPx(), Offset(lx, y(lv)))
    }
}

// ---- battery ----
/**
 * 1.5: the PC's battery (island 0.23): a ring with its level, whether it's charging and for how long, the power going in
 * or out, its last day, and its health, cycles, temperature, capacity and voltage.
 */
@Composable private fun BatteryPanel(b: PcBattery) {
    val t = LocalTokens.current
    val colour = when { b.charging -> t.good; b.percent in 0..20 -> t.danger; else -> t.blue }
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                    ProgressRing(if (b.percent >= 0) b.percent / 100f else 0f, colour, t.track, Modifier.fillMaxSize(), 7.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (b.percent >= 0) RollingNumber("${b.percent}%", Type.headline, t.text) else Text("—", style = Type.headline, color = t.faint)
                        if (b.charging) Icon(Icons.Rounded.Bolt, "Charging", tint = t.good, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(when { b.charging && b.minutesToFull > 0 -> "Full in ${span(b.minutesToFull * 60L)}"; b.charging -> "Charging"; b.online -> "On power"; b.minutesLeft > 0 -> "${span(b.minutesLeft * 60L)} left"; else -> "On battery" },
                        style = Type.headline, color = t.text)
                    val watts = kotlin.math.abs(b.rateMw) / 1000.0
                    if (watts > .05) Text((if (b.rateMw > 0) "%.1f W going in" else "Using %.1f W").format(watts), style = Type.caption, color = t.muted)
                    if (b.saver) Text("Battery saver is on", style = Type.caption, color = t.warn)
                }
            }
            if (b.day.size >= 2) { Spacer(Modifier.height(14.dp)); BatteryDay(b.day, t.blue) }
        }
    }
    Spacer(Modifier.height(12.dp))
    GlassPanel(Modifier.fillMaxWidth()) {
        Column {
            var first = true
            fun sep() = if (first) { first = false; false } else true
            if (b.health >= 0) { Fact(Icons.Rounded.FavoriteBorder, "Health", "${(b.health * 100).roundToInt()}%" + if (b.healthBefore >= 0 && kotlin.math.abs(b.health - b.healthBefore) >= .0005) "  (%+.1f this week)".format((b.health - b.healthBefore) * 100).replace("-", "−") else "", t.good); first = false }
            if (b.cycles > 0) { if (sep()) Hairline(); Fact(Icons.Rounded.Autorenew, "Cycles", "${b.cycles}", t.blue) }
            if (b.temperatureDeciK > 0) { if (sep()) Hairline(); Fact(Icons.Rounded.Thermostat, "Temperature", "%.1f°C".format(b.temperatureDeciK / 10.0 - 273.15), t.warn) }
            if (b.fullMwh > 0) { if (sep()) Hairline(); Fact(Icons.Rounded.BatteryStd, "Capacity", "%.1f of %.1f Wh".format(b.remainingMwh / 1000.0, b.fullMwh / 1000.0) + if (b.designMwh > 0) " (made for %.1f)".format(b.designMwh / 1000.0) else "", t.teal) }
            if (b.voltageMv > 0) { if (sep()) Hairline(); Fact(Icons.Rounded.ElectricBolt, "Voltage", "%.2f V".format(b.voltageMv / 1000.0), t.yellow) }
            listOf(b.manufacturer, b.name).filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() }?.let { if (sep()) Hairline(); Fact(Icons.Rounded.Info, "Battery", it + if (b.chemistry.isNotBlank()) "  ·  ${b.chemistry}" else "", t.gray) }
        }
    }
}
/** The battery's last day: its level over time, green while it charged. */
@Composable private fun BatteryDay(day: List<Triple<Long, Int, Boolean>>, colour: Color) {
    val t = LocalTokens.current
    Column {
        Canvas(Modifier.fillMaxWidth().height(58.dp).semantics { contentDescription = "The battery over the last day" }) {
            val first = day.first().first; val span = (day.last().first - first).coerceAtLeast(1).toFloat()
            fun at(i: Int) = Offset((day[i].first - first) / span * size.width, size.height - day[i].second / 100f * size.height * .94f)
            val fill = Path().apply { moveTo(0f, size.height); day.indices.forEach { lineTo(at(it).x, at(it).y) }; lineTo(size.width, size.height); close() }
            drawPath(fill, Brush.verticalGradient(listOf(colour.copy(alpha = .25f), Color.Transparent)))
            for (i in 1 until day.size) drawLine(if (day[i].third) t.good else colour, at(i - 1), at(i), 2.dp.toPx(), StrokeCap.Round)
        }
        Row(Modifier.fillMaxWidth()) { Text("A day ago", style = Type.micro, color = t.faint, modifier = Modifier.weight(1f)); Text("Now", style = Type.micro, color = t.faint) }
    }
}

/** The PC's numbers in full: live graphs for the processor, graphics and network, scrubbable, and what the PC is. */
@Composable private fun StatsSheet(visible: Boolean, s: PcStats?, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    GlassSheet(visible, onDismiss, Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .92f)) {
        if (s == null) return@GlassSheet
        SheetHeader(s.name.ifEmpty { "Your PC" }, s.model.ifEmpty { null }, onDismiss)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            // 1.5: the numbers seen while the tab showed (up to five minutes), else the PC's last 40 seconds; drag to read any moment.
            val trail by Hub.statsTrail.collectAsState(); val now = System.currentTimeMillis()
            fun series(pick: (Hub.StatsPoint) -> Float, history: List<Float>) = if (trail.size >= history.size && trail.size >= 10) trail.map { it.at to pick(it) } else history.mapIndexed { i, v -> (now - (history.size - 1 - i) * 1000L) to v }
            BigGraph("Processor", if (s.cpu >= 0) "${s.cpu.roundToInt()}%" else "—", series({ it.cpu }, s.cpuHistory), t.blue, 100f, s.cpuName.ifEmpty { null }?.let { "$it  ·  ${s.logical} threads" }) { "${it.roundToInt()}%" }
            BigGraph("Graphics", if (s.gpu >= 0) "${s.gpu.roundToInt()}%" else "—", series({ it.gpu }, s.gpuHistory), t.purple, 100f, s.gpuName.ifEmpty { null }) { if (it < 0) "—" else "${it.roundToInt()}%" }
            BigGraph("Downloading", rateText(s.download), series({ it.download }, s.downloadHistory), t.good, null, "Sending ${rateText(s.upload)}") { rateText(it.toDouble()) }
        }
    }
}
@Composable private fun BigGraph(title: String, now: String, series: List<Pair<Long, Float>>, color: Color, top: Float?, detail: String?, format: (Float) -> String) {
    val t = LocalTokens.current; val samples = series.map { it.second }; val haptics = LocalHapticFeedback.current
    var at by remember { mutableStateOf<Int?>(null) }
    GlassPanel(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(title, style = Type.bodyStrong, color = t.text, modifier = Modifier.weight(1f)); RollingNumber(now, Type.headline, color)
            }
            if (detail != null) Text(detail, style = Type.caption, color = t.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            // Percentages up to the next step past the highest (so a quiet PC's line isn't flat on the floor); rates to their own highest.
            val scale = if (top == null) null else listOf(10f, 25f, 50f, 75f, 100f).firstOrNull { it >= minOf(100f, (samples.maxOrNull() ?: 0f) * 1.15f) } ?: 100f
            Box(Modifier.fillMaxWidth().height(92.dp).pointerInput(series.size) {
                // Touch and drag along it: a line at that moment, with its value and how long ago it was.
                awaitEachGesture {
                    val down = awaitFirstDown(); down.consume(); fun index(x: Float) = if (samples.size < 2) null else (x / size.width * (samples.size - 1)).roundToInt().coerceIn(0, samples.size - 1)
                    at = index(down.position.x); haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    while (true) { val e = awaitPointerEvent(); val c = e.changes.firstOrNull() ?: break; if (!c.pressed) break; c.consume(); val i = index(c.position.x); if (i != at) { at = i; if (i != null && i % 5 == 0) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) } }
                    at = null
                }
            }) {
                MorphGraph(samples, color, Modifier.fillMaxSize(), scale)
                if (scale != null && at == null) Text("${scale.roundToInt()}%", style = Type.micro, color = t.faint, modifier = Modifier.align(Alignment.TopEnd))
                // A drag cut short (the samples grew under the finger) can leave a moment past the end once they shrink.
                val i = at?.takeIf { it < samples.size }
                if (i != null && samples.size >= 2) {
                    val high = max(scale ?: (samples.maxOrNull() ?: 1f) * 1.15f, 1f)
                    Canvas(Modifier.fillMaxSize()) {
                        val x = i / (samples.size - 1f) * size.width; val y = size.height - (samples[i].coerceAtLeast(0f) / high).coerceIn(0f, 1f) * size.height * .9f
                        drawLine(t.text.copy(alpha = .35f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                        drawCircle(color.copy(alpha = .3f), 7.dp.toPx(), Offset(x, y)); drawCircle(color, 3.5.dp.toPx(), Offset(x, y))
                    }
                    val ago = ((System.currentTimeMillis() - series[i].first) / 1000).coerceAtLeast(0)
                    Text("${format(samples[i])}  ·  ${if (ago < 2) "now" else if (ago < 90) "$ago s ago" else "${ago / 60} min ago"}", style = Type.micro, color = t.text,
                        modifier = Modifier.align(Alignment.TopCenter).glass(GlassShapes.capsule, GlassLevel.Control).padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            if (series.size >= 2) Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                val span = ((series.last().first - series.first().first) / 1000).coerceAtLeast(1)
                Text(if (span < 90) "$span s ago" else "${span / 60} min ago", style = Type.micro, color = t.faint, modifier = Modifier.weight(1f)); Text("Now", style = Type.micro, color = t.faint)
            }
        }
    }
}
/** A fact in a list: its icon on its colour, its name, and its value on the right. */
@Composable private fun Fact(icon: ImageVector, title: String, value: String, tint: Color = LocalTokens.current.blue, onClick: (() -> Unit)? = null) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, tint, size = 28.dp); Spacer(Modifier.width(14.dp))
        Text(title, style = Type.body, color = t.text); Spacer(Modifier.width(12.dp))
        Text(value, style = Type.callout, color = t.muted, textAlign = TextAlign.End, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (onClick != null) Chevron()
    }
}

// ---- sound ----
/** The PC's sound: its volume and mute, the microphone, where it comes from, and (island 0.26) each app's own volume. */
@Composable private fun SoundPanel(pc: PeerView, c: PcControls?, outputs: List<AudioOutput>, settings: IslandSettings?) {
    val t = LocalTokens.current; val apps by Hub.mixer.collectAsState(); val icons by Hub.mixerIcons.collectAsState()
    GlassPanel(Modifier.fillMaxWidth()) {
        Column {
            if (c != null) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Level(if (c.muted || c.volume == 0) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, "Volume", c.volume) { v -> Hub.setControl(IslandWire.VOLUME, v) { it.copy(volume = v) } }
                }
                Hairline(16.dp)
                GlassRow(if (c.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, "Mute", tint = t.danger) { GlassSwitch(c.muted, { on -> Hub.setControl(IslandWire.MUTE, if (on) 1 else 0) { it.copy(muted = on) } }) }
                if (c.micAvailable) { Hairline(); GlassRow(if (c.micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic, "Microphone muted", tint = t.warn) { GlassSwitch(c.micMuted, { on -> Hub.setControl(IslandWire.MIC, if (on) 1 else 0) { it.copy(micMuted = on) } }) } }
            }
            if (outputs.isNotEmpty()) {
                Hairline(16.dp)
                Text("PLAYING THROUGH", style = Type.micro, color = t.muted, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp))
                OutputsPanel(outputs, settings)
            }
            if (apps.isNotEmpty()) {
                Hairline(16.dp)
                Text("APPS", style = Type.micro, color = t.muted, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp))
                apps.sortedWith(compareByDescending<io.github.arnavdugad.arnavisland.link.MixerApp> { it.active }.thenBy { it.system }.thenBy { it.name.lowercase() }).forEach { a -> MixerRow(a, icons[a.name]) }
                Spacer(Modifier.height(6.dp))
            } else if (pc.revision < 9) { Hairline(16.dp); Text("App volumes need island 0.26", style = Type.caption, color = t.muted, modifier = Modifier.padding(16.dp)) }
        }
    }
}

// ---- controls ----
@Composable private fun ControlsPanel(c: PcControls?) {
    val t = LocalTokens.current
    if (c == null) { GlassPanel(Modifier.fillMaxWidth().height(120.dp)) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Radar(t.accent, Modifier.size(40.dp)) } }; return }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(Icons.Rounded.Wifi, Icons.Rounded.WifiOff, "Wi-Fi", c.wifi, c.busy and 1 != 0, t.blue, Modifier.weight(1f)) { on -> Hub.setControl(IslandWire.WIFI, if (on) 1 else 0) { it.copy(wifi = if (on) 1 else 0, busy = it.busy or 1) } }
            Switch(Icons.Rounded.Bluetooth, Icons.Rounded.BluetoothDisabled, "Bluetooth", c.bluetooth, c.busy and 2 != 0, t.blue, Modifier.weight(1f)) { on -> Hub.setControl(IslandWire.BLUETOOTH, if (on) 1 else 0) { it.copy(bluetooth = if (on) 1 else 0, busy = it.busy or 2) } }
            Switch(Icons.Rounded.AirplanemodeActive, Icons.Rounded.AirplanemodeInactive, "Airplane", if (c.wifi < -1 && c.bluetooth < -1) -2 else if (c.airplane) 1 else 0, c.busy and 4 != 0, t.warn, Modifier.weight(1f)) { on ->
                Hub.setControl(IslandWire.AIRPLANE, if (on) 1 else 0) { it.copy(wifi = if (on) 0 else 1, bluetooth = if (it.bluetooth < -1) it.bluetooth else if (on) 0 else 1, busy = it.busy or 4) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(Icons.Rounded.DarkMode, Icons.Rounded.LightMode, "Dark mode", c.dark, c.busy and 8 != 0, t.indigo, Modifier.weight(1f)) { on -> Hub.setControl(IslandWire.DARK, if (on) 1 else 0) { it.copy(dark = if (on) 1 else 0, busy = it.busy or 8) } }
            Switch(Icons.Rounded.MicOff, Icons.Rounded.Mic, "Mic muted", if (!c.micAvailable) -2 else if (c.micMuted) 1 else 0, false, t.warn, Modifier.weight(1f)) { on -> Hub.setControl(IslandWire.MIC, if (on) 1 else 0) { it.copy(micMuted = on) } }
            Switch(Icons.AutoMirrored.Rounded.VolumeOff, Icons.AutoMirrored.Rounded.VolumeUp, "Muted", if (c.muted) 1 else 0, false, t.danger, Modifier.weight(1f)) { on -> Hub.setControl(IslandWire.MUTE, if (on) 1 else 0) { it.copy(muted = on) } }
        }
        if (c.brightness >= 0) GlassPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) { Level(Icons.Rounded.LightMode, "Brightness", c.brightness) { v -> Hub.setControl(IslandWire.BRIGHTNESS, v) { it.copy(brightness = v) } } }
        }
    }
}
/** A switch as a Control Center tile: filled with its colour when on; a small ring while it's changing on the PC; dimmed when the PC has no such thing. */
@Composable private fun Switch(on: ImageVector, off: ImageVector, label: String, state: Int, busy: Boolean, colour: Color, modifier: Modifier, onChange: (Boolean) -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current; val lit = state == 1; val none = state < -1
    val shape = RoundedCornerShape(22.dp)
    Column(modifier.height(96.dp).then(if (lit) Modifier.filled(shape, colour) else Modifier.glass(shape, GlassLevel.Control)).clip(shape)
        .clickable(enabled = !none && !busy && state >= 0, role = Role.Switch) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onChange(!lit) }
        .semantics { contentDescription = "$label, ${when { none -> "not on this PC"; busy -> "changing"; lit -> "on"; else -> "off" }}" }.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(if (lit) Color.White.copy(alpha = .22f) else t.fill), contentAlignment = Alignment.Center) {
            if (busy) Radar(if (lit) Color.White else t.accent, Modifier.fillMaxSize())
            Icon(if (lit) on else off, null, tint = if (none) t.faint else if (lit) Color.White else t.text, modifier = Modifier.size(18.dp))
        }
        Column {
            Text(label, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = if (none) t.faint else if (lit) Color.White else t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(when { none -> "Not here"; busy -> "Changing…"; state == -1 -> "…"; lit -> "On"; else -> "Off" }, style = Type.micro, color = if (lit) Color.White.copy(alpha = .8f) else t.muted)
        }
    }
}
/** A level (brightness, volume): its icon, a slider sent when let go, and its number. */
@Composable private fun Level(icon: ImageVector, label: String, value: Int, onDone: (Int) -> Unit) {
    val t = LocalTokens.current; var moving by remember { mutableStateOf<Float?>(null) }; val shown = moving ?: (value / 100f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = t.muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
        GlassSlider(shown, { moving = it }, { moving = null; onDone((it * 100).roundToInt()) }, Modifier.weight(1f), description = label, height = 8.dp)
        Spacer(Modifier.width(12.dp)); RollingNumber("${(shown * 100).roundToInt()}%", Type.callout, t.muted, Modifier.widthIn(min = 44.dp))
    }
}

// ---- focus ----
/** The island's focus clock: running on here from the PC's last word, with focus, break and stopwatch to start. */
@Composable private fun FocusPanel(c: PcControls?) {
    val t = LocalTokens.current; if (c == null) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(c.focusRunning) { while (c.focusRunning) { now = System.currentTimeMillis(); delay(250) } }
    val gone = if (c.focusRunning) (now - Hub.controlsAt).coerceAtLeast(0) / 1000.0 else 0.0
    val stopwatch = c.focusMode == 2
    val shown = if (stopwatch) c.focusShown + gone else (c.focusShown - gone).coerceAtLeast(0.0)
    val fraction = if (stopwatch) ((shown % 60) / 60).toFloat() else if (c.focusDuration > 0) (shown / c.focusDuration).toFloat() else 0f
    GlassPanel(Modifier.fillMaxWidth()) { Column {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(108.dp), contentAlignment = Alignment.Center) {
                ProgressRing(fraction, if (c.focusMode == 1) t.good else t.purple, t.track, Modifier.fillMaxSize(), 7.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RollingNumber(clock(shown), Type.headline, t.text)
                    Text(when (c.focusMode) { 1 -> "Break"; 2 -> "Stopwatch"; else -> "Focus" }, style = Type.micro, color = t.muted)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(when { c.focusFinished -> "Done"; c.focusRunning -> "Running"; c.focusMode == 2 -> if (shown > .5) "Paused" else "Ready"; shown < c.focusDuration - .5 -> "Paused"; else -> "Ready" },
                    style = Type.headline, color = t.text)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassIconButton(if (c.focusRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (c.focusRunning) "Pause" else "Start", { Hub.setControl(IslandWire.FOCUS_TOGGLE, 0) { it.copy(focusRunning = !it.focusRunning, focusShown = shown) } }, prominent = true)
                    GlassIconButton(Icons.Rounded.Replay, "Reset", { Hub.setControl(IslandWire.FOCUS_RESET, 0) { it.copy(focusRunning = false, focusShown = if (it.focusMode == 2) 0.0 else it.focusDuration) } })
                    GlassIconButton(Icons.Rounded.Timer, "Stopwatch", { Hub.setControl(IslandWire.STOPWATCH, 0) { it.copy(focusMode = 2, focusRunning = true, focusShown = 0.0) } })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in listOf(15, 25, 45)) Preset("$m min", Modifier.weight(1f)) { Hub.setControl(IslandWire.FOCUS, m) { it.copy(focusMode = 0, focusDuration = m * 60.0, focusShown = m * 60.0, focusRunning = true) } }
            Preset("Break", Modifier.weight(1f)) { Hub.setControl(IslandWire.BREAK, 5) { it.copy(focusMode = 1, focusDuration = 300.0, focusShown = 300.0, focusRunning = true) } }
        }
    } }
}
@Composable private fun Preset(label: String, modifier: Modifier, onClick: () -> Unit) {
    GlassButton(onClick, modifier, contentPadding = PaddingValues(vertical = 10.dp)) { Text(label, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), maxLines = 1) }
}

// ---- the command bar ----
/** The PC's command bar: what's typed is asked as it's typed; a row runs on the PC (asking first where the island would). */
@Composable private fun CommandPanel(pcName: String, onConfirm: (Confirm) -> Unit) {
    val t = LocalTokens.current; val scope = rememberCoroutineScope(); val haptics = LocalHapticFeedback.current
    var text by remember { mutableStateOf("") }; var rows by remember { mutableStateOf<List<CommandRow>>(emptyList()) }; var asked by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current; val bring = remember { BringIntoViewRequester() }; var typing by remember { mutableStateOf(false) }
    LaunchedEffect(text) { delay(if (text.isEmpty()) 0 else 180); val r = Hub.queryCommands(text); if (r != null) { rows = r.rows; asked = text } }
    // While typing, the field and its results stay above the keyboard.
    LaunchedEffect(rows, typing) { if (typing) { delay(300); bring.bringIntoView() } }
    fun run(i: Int, row: CommandRow, confirmed: Boolean) {
        scope.launch {
            val o = Hub.runCommand(asked, i, row.title, confirmed)
            when (o?.outcome) {
                0 -> { haptics.performHapticFeedback(HapticFeedbackType.Confirm); Hub.banners.tryEmit(Banner(Banner.Kind.Info, o.message, "On $pcName")); focus.clearFocus(); if (row.kind in listOf(12, 13, 14, 36)) text = "" }
                // The island's question ("Restart the PC? Anything unsaved…"): the question as the title, the rest under it.
                1 -> { val q = o.message; val cut = q.indexOf("? ")
                    val title = (if (cut > 0) q.substring(0, cut + 1) else q).replace("the PC", pcName); val detail = if (cut > 0) q.substring(cut + 2).trimEnd('.') + "." else "On $pcName"
                    focus.clearFocus(); onConfirm(Confirm(title, detail, row.title.substringBefore(" ").ifEmpty { "Go ahead" }, danger = row.kind in listOf(32, 34, 35)) { run(i, row, true) }) }
                3 -> { val r = Hub.queryCommands(asked); if (r != null) rows = r.rows; Hub.banners.tryEmit(Banner(Banner.Kind.Info, o.message)) }
                else -> Hub.banners.tryEmit(Banner(Banner.Kind.Failed, o?.message ?: "$pcName didn't answer"))
            }
        }
    }
    GlassPanel(Modifier.fillMaxWidth().bringIntoViewRequester(bring)) {
        Column(Modifier.padding(vertical = 6.dp)) {
            SearchField(text, { text = it.take(160) }, "An app, a file, a setting, “timer 10”…", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), onGo = { rows.firstOrNull()?.let { run(0, it, false) } },
                fieldModifier = Modifier.onFocusChanged { typing = it.isFocused })
            rows.take(6).forEachIndexed { i, row ->
                if (i > 0) Hairline()
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { run(i, row, false) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(kindIcon(row.kind), kindColour(row.kind, t))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.title, style = Type.body, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (row.detail.isNotEmpty()) Text(row.detail, style = Type.caption, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (row.answer.isNotEmpty()) Text(row.answer, style = Type.bodyStrong, color = t.accent)
                }
            }
        }
    }
}
/** The island's CommandKind, as an icon. */
private fun kindIcon(kind: Int): ImageVector = when (kind) {
    1, 2, 4 -> Icons.AutoMirrored.Rounded.VolumeUp; 3 -> Icons.AutoMirrored.Rounded.VolumeOff; 5 -> Icons.Rounded.PlayArrow; 6 -> Icons.Rounded.Pause; 7 -> Icons.Rounded.SkipNext; 8 -> Icons.Rounded.SkipPrevious
    9, 10 -> Icons.Rounded.Timer; 11 -> Icons.Rounded.TimerOff; 12 -> Icons.Rounded.Apps; 13 -> Icons.Rounded.Search; 14 -> Icons.Rounded.Settings; 15, 16, 17 -> Icons.Rounded.Dashboard
    18, 27 -> Icons.Rounded.ContentPaste; 19 -> Icons.Rounded.ClearAll; 20 -> Icons.Rounded.Lock; 21 -> Icons.Rounded.MicOff; 22, 23 -> Icons.Rounded.Mic; 24 -> Icons.Rounded.Screenshot
    25 -> Icons.Rounded.TextFields; 26, 38 -> Icons.Rounded.Palette; 28 -> Icons.Rounded.DarkMode; 29 -> Icons.Rounded.Bluetooth; 30 -> Icons.Rounded.Wifi; 31 -> Icons.Rounded.AirplanemodeActive
    32 -> Icons.Rounded.DeleteSweep; 33 -> Icons.Rounded.Bedtime; 34 -> Icons.Rounded.RestartAlt; 35 -> Icons.Rounded.PowerSettingsNew; 36 -> Icons.Rounded.Description; 37 -> Icons.Rounded.CurrencyExchange
    39 -> Icons.Rounded.Cloud; 40 -> Icons.Rounded.MusicNote; 41 -> Icons.Rounded.Shuffle; 42 -> Icons.Rounded.Devices; else -> Icons.Rounded.Bolt
}
private fun kindColour(kind: Int, t: Tokens): Color = when (kind) {
    in 1..8, 40, 41 -> t.pink; 9, 10, 11 -> t.warn; 12, 13, 36 -> t.blue; 14, 28 -> t.gray; 20, 33, 34, 35 -> t.indigo; 21, 22, 23 -> t.danger; 29, 30, 31 -> t.blue; 32 -> t.danger; else -> t.purple
}

// ---- power ----
@Composable private fun PowerPanel(pc: PeerView, onConfirm: (Confirm) -> Unit) {
    val t = LocalTokens.current; val pcName = pc.name
    fun power(control: Int, title: String, detail: String, action: String, danger: Boolean) = onConfirm(Confirm(title, detail, action, danger) { Hub.setControl(control, 1) })
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Action(Icons.Rounded.Lock, "Lock", t.gray, Modifier.weight(1f)) { Hub.setControl(IslandWire.LOCK, 1) }
        Action(Icons.Rounded.Bedtime, "Sleep", t.indigo, Modifier.weight(1f)) { power(IslandWire.SLEEP, "Put $pcName to sleep?", "", "Sleep", false) }
        Action(Icons.Rounded.RestartAlt, "Restart", t.warn, Modifier.weight(1f)) { power(IslandWire.RESTART, "Restart $pcName?", "Unsaved work may be lost.", "Restart", true) }
        Action(Icons.Rounded.PowerSettingsNew, "Shut down", t.danger, Modifier.weight(1f)) { power(IslandWire.SHUT_DOWN, "Shut down $pcName?", "Unsaved work may be lost.", "Shut down", true) }
    }
    Spacer(Modifier.height(10.dp))
    GlassPanel(Modifier.fillMaxWidth()) {
        Column {
            GlassRow(Icons.Rounded.DeleteSweep, "Empty recycle bin", tint = t.danger, chevron = true, onClick = { power(IslandWire.EMPTY_BIN, "Empty the recycle bin?", "This can't be undone.", "Empty", true) })
            Hairline()
            // 1.8: Wake-on-LAN, once this phone knows the PC's adapters (#38).
            GlassRow(Icons.Rounded.PowerSettingsNew, "Wake from here", if (Hub.canWake(pc.id)) null else "Needs island 0.26", tint = t.good)
        }
    }
}
@Composable private fun Action(icon: ImageVector, label: String, colour: Color, modifier: Modifier, height: Dp = 84.dp, onClick: () -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    Column(modifier.height(height).glass(RoundedCornerShape(20.dp), GlassLevel.Control).clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() }.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        IconTile(icon, colour, size = 34.dp); Spacer(Modifier.height(7.dp)); Text(label, style = Type.caption.copy(fontWeight = FontWeight.Medium), color = t.text, maxLines = 1)
    }
}

// ---- alerts ----
/** 1.8: which of the PC's alerts are on (#22), and the way to change them. */
@Composable private fun AlertsPanel() {
    val t = LocalTokens.current
    GlassPanel(Modifier.fillMaxWidth()) {
        Column {
            val on = Alerts.kinds.filter { Alerts.on(it.key) }
            GlassRow(Icons.Rounded.NotificationsActive, if (on.isEmpty()) "No alerts yet" else on.joinToString(", ") { it.title.lowercase() }.replaceFirstChar { it.uppercase() },
                if (on.isEmpty()) null else "${on.size} on", tint = t.danger, chevron = true, onClick = { Ui.alerts = true })
        }
    }
}

// ---- sound output, pages ----
@Composable private fun OutputsPanel(outputs: List<AudioOutput>, settings: IslandSettings?) {
    val t = LocalTokens.current
    val direct = settings?.items?.firstOrNull { it.key == "directAudio" }
    Column {
        outputs.forEach { o ->
            GlassRow(when (o.form) { 3 -> Icons.Rounded.Headphones; 4 -> Icons.Rounded.SettingsVoice; else -> Icons.Rounded.Speaker }, o.name, tint = if (o.current) t.blue else t.gray, onClick = { if (!o.current) Hub.selectOutput(o.id) }) {
                if (o.current) Icon(Icons.Rounded.CheckCircle, "Chosen", tint = t.blue)
            }
        }
        if (direct != null && direct.value == 0) {
            Hairline(); GlassRow(Icons.Rounded.SwapHoriz, direct.title, "Needed to switch from here", tint = t.warn) { GlassSwitch(false, { Hub.setIslandSetting("directAudio", 1) }) }
        }
    }
}
@Composable private fun PagesPanel() {
    val t = LocalTokens.current
    val icons = listOf(Icons.Rounded.Home, Icons.Rounded.MusicNote, Icons.Rounded.Speed, Icons.Rounded.CenterFocusStrong, Icons.Rounded.Settings, Icons.Rounded.Inventory2, Icons.Rounded.Equalizer, Icons.Rounded.ToggleOn)
    val colours = listOf(t.blue, t.pink, t.good, t.purple, t.gray, t.warn, t.indigo, t.teal)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in listOf(listOf(0, 1, 2, 3), listOf(5, 6, 7, 4))) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (i in row) Action(icons[i], IslandWire.PAGES[i], colours[i], Modifier.weight(1f), height = 80.dp) { Hub.openIslandPage(i) }
        }
        GlassButton({ Hub.closeIsland() }, Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp)) { Icon(Icons.Rounded.CloseFullscreen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Close island", style = Type.bodyStrong) }
    }
}

// ---- about ----
/** 1.8: what the PC is: its processor, graphics, memory, disk, Windows, the island it runs, its connection and its adapters. */
@Composable private fun AboutPanel(pc: PeerView, s: PcStats?) {
    val t = LocalTokens.current; val machines by Hub.machines.collectAsState()
    GlassPanel(Modifier.fillMaxWidth()) {
        Column {
            if (s != null) {
                if (s.cpuName.isNotBlank()) { Fact(Icons.Rounded.Memory, "Processor", s.cpuName + if (s.logical > 0) "  ·  ${s.logical} threads" else "", t.blue); Hairline() }
                if (s.gpuName.isNotBlank()) { Fact(Icons.Rounded.Videocam, "Graphics", s.gpuName, t.purple); Hairline() }
                Fact(Icons.Rounded.Storage, "Memory", "%.0f GB".format(s.ramTotalGiB), t.good); Hairline()
                if (s.diskTotalGiB > 0) { Fact(Icons.Rounded.SdStorage, "Disk", "%.0f GB".format(s.diskTotalGiB), t.teal); Hairline() }
                if (s.os.isNotBlank()) { Fact(Icons.Rounded.DesktopWindows, "Windows", s.os, t.blue); Hairline() }
                if (s.model.isNotBlank()) { Fact(Icons.Rounded.Laptop, "Model", s.model, t.gray); Hairline() }
                Fact(Icons.Rounded.Timer, "On for", span(s.uptime), t.warn); Hairline()
                val heat = Hub.sensors.collectAsState().value
                heat?.zones?.filter { !it.second.isNaN() }?.forEach { (n, c) -> Fact(Icons.Rounded.Thermostat, n.ifBlank { "Temperature" }, "${c.roundToInt()}°C", t.warn); Hairline() }
                heat?.fans?.forEach { (n, rpm) -> Fact(Icons.Rounded.Air, n.ifBlank { "Fan" }, "$rpm rpm", t.teal); Hairline() }
            }
            Fact(Icons.Rounded.Lan, "Connection", quality(pc).text, qualityColor(quality(pc).kind)) { Ui.connection = pc.id }
            Hairline()
            Fact(Icons.Rounded.Shield, "Island protocol", "${pc.version}.${pc.revision}  ·  end-to-end encrypted", t.good)
            machines[pc.id].orEmpty().forEach { a ->
                Hairline()
                Fact(Icons.Rounded.SettingsEthernet, a.name.ifBlank { "Adapter" }, "${a.ipv4}\n" + a.mac.hex().chunked(2).joinToString(":").uppercase(), t.indigo)
            }
        }
    }
}

// ---- settings ----
private val sectionIcons = listOf(Icons.Rounded.Tune, Icons.Rounded.ViewAgenda, Icons.Rounded.Palette, Icons.Rounded.Animation, Icons.AutoMirrored.Rounded.ShortText, Icons.Rounded.MusicNote,
    Icons.Rounded.BatteryChargingFull, Icons.Rounded.Home, Icons.Rounded.PrivacyTip, Icons.Rounded.Info)
@Composable private fun SettingsPanel(s: IslandSettings?, onOpen: (Int) -> Unit) {
    val t = LocalTokens.current
    val colours = listOf(t.gray, t.blue, t.pink, t.purple, t.indigo, t.danger, t.good, t.warn, t.blue, t.gray)
    GlassPanel(Modifier.fillMaxWidth()) {
        if (s == null) Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { Radar(t.accent, Modifier.size(34.dp)) }
        else Column {
            s.sections.forEachIndexed { i, name ->
                val count = s.items.count { it.section == i && !(it.control == 5 && it.action in listOf(1, 16)) }; if (count == 0) return@forEachIndexed
                if (i > 0 && s.items.any { it.section < i }) Hairline()
                GlassRow(sectionIcons.getOrElse(i) { Icons.Rounded.Tune }, name, tint = colours.getOrElse(i) { t.gray }, value = "$count", chevron = true, onClick = { onOpen(i) })
            }
        }
    }
}
/** Switching these off cuts this phone off from the PC: asked first. */
private val lifelines = mapOf("sharing" to "This phone will lose its connection to the PC.",
    "phoneControl" to "This phone won't be able to control the PC.", "relay" to "This phone can't reach the PC from another network.")
@Composable private fun SectionSheet(section: Int?, s: IslandSettings?, onConfirm: (Confirm) -> Unit, onDismiss: () -> Unit) {
    GlassSheet(section != null && s != null, onDismiss, Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .9f)) {
        if (section == null || s == null) return@GlassSheet
        SheetHeader(s.sections.getOrElse(section) { "" }, null, onDismiss)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            s.items.filter { it.section == section && !(it.control == 5 && it.action in listOf(1, 16)) }.forEach { item -> SettingCard(item, onConfirm) }
            Spacer(Modifier.height(12.dp))
        }
    }
}
@Composable private fun SettingCard(item: IslandSetting, onConfirm: (Confirm) -> Unit) {
    val t = LocalTokens.current
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            val head: @Composable RowScope.() -> Unit = {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = Type.body, color = t.text)
                    if (item.detail.isNotEmpty()) Text(item.detail, style = Type.caption, color = t.muted)
                }
            }
            when (item.control) {
                0 -> Row(verticalAlignment = Alignment.CenterVertically) { head(); Spacer(Modifier.width(12.dp))
                    GlassSwitch(item.value != 0, { on -> val warn = lifelines[item.key]
                        if (!on && warn != null) onConfirm(Confirm("Turn off “${item.title}”?", warn, "Turn off", true) { Hub.setIslandSetting(item.key, 0) }) else Hub.setIslandSetting(item.key, if (on) 1 else 0) }) }
                1 -> { var moving by remember(item.key) { mutableStateOf<Int?>(null) }; val shown = moving ?: item.value; val span = (item.hi - item.lo).coerceAtLeast(1)
                    Row(verticalAlignment = Alignment.CenterVertically) { head(); Text("$shown${item.unit}", style = Type.bodyStrong, color = t.accent) }
                    Spacer(Modifier.height(10.dp))
                    fun snap(f: Float): Int { val raw = item.lo + (f * span).roundToInt(); val step = item.step.coerceAtLeast(1); return (item.lo + ((raw - item.lo + step / 2) / step) * step).coerceIn(item.lo, maxOf(item.lo, item.hi)) }
                    GlassSlider((shown - item.lo).toFloat() / span, { moving = snap(it) }, { val v = snap(it); moving = null; Hub.setIslandSetting(item.key, v) }, Modifier.fillMaxWidth(), description = item.title) }
                2 -> { Row { head() }; Spacer(Modifier.height(10.dp))
                    if (item.options.size in 2..3 && item.options.all { it.length <= 12 }) Segmented(item.options, item.value.coerceIn(0, item.options.lastIndex), { v -> if (item.value != v) Hub.setIslandSetting(item.key, v) }, Modifier.fillMaxWidth())
                    else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item.options.forEachIndexed { i, o -> val v = i; GlassChip(o, selected = item.value == v, onClick = { if (item.value != v) Hub.setIslandSetting(item.key, v) }) }
                    } }
                3 -> Row(verticalAlignment = Alignment.CenterVertically) { head()
                    GlassIconButton(Icons.Rounded.Remove, "Less", { Hub.setIslandSetting(item.key, (item.value - item.step).coerceAtLeast(item.lo)) }, size = 36.dp, enabled = item.value > item.lo)
                    Text(item.options.getOrNull(item.value - item.lo) ?: "${item.value}${item.unit}", style = Type.bodyStrong, color = t.text, modifier = Modifier.padding(horizontal = 10.dp))
                    GlassIconButton(Icons.Rounded.Add, "More", { Hub.setIslandSetting(item.key, (item.value + item.step).coerceAtMost(item.hi)) }, size = 36.dp, enabled = item.value < item.hi) }
                4 -> { Row { head() }; Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        item.colours.forEachIndexed { i, c -> val v = i; val chosen = item.value == v
                            val ring by animateFloatAsState(if (chosen) 1f else 0f, spring(dampingRatio = .6f, stiffness = 500f), label = "Swatch")
                            Box(Modifier.size(40.dp).border(2.5.dp, t.text.copy(alpha = ring), CircleShape).padding(5.dp).graphicsLayer { scaleX = .9f + .1f * ring; scaleY = .9f + .1f * ring }.clip(CircleShape).background(Color(0xFF000000.toInt() or c))
                                .clickable(role = Role.RadioButton) { if (!chosen) Hub.setIslandSetting(item.key, v) }.semantics { contentDescription = item.options.getOrElse(i) { "Colour ${i + 1}" } + if (chosen) ", chosen" else "" })
                        }
                    } }
                5 -> Row(verticalAlignment = Alignment.CenterVertically) { head(); Spacer(Modifier.width(12.dp))
                    val verb = when (item.action) { 2, 6 -> "Reset"; 4, 12, 14, 17 -> "Clear"; 18 -> "Check"; else -> "Open" }
                    GlassButton({ val ask = when (item.action) { 2 -> "The island on your PC goes back to how it came."; 6 -> "The island's pages go back to where they started."; 4, 12, 14, 17 -> "It can't be undone."; else -> null }
                        if (ask != null) onConfirm(Confirm("${item.title}?", ask, verb, true) { Hub.islandSettingAction(item.action, item.title) }) else Hub.islandSettingAction(item.action, item.title) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)) { Text(verb, style = Type.caption.copy(fontWeight = FontWeight.SemiBold)) } }
            }
        }
    }
}
