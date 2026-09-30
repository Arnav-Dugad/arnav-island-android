package io.github.arnavdugad.arnavisland

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import io.github.arnavdugad.arnavisland.link.PcControls
import io.github.arnavdugad.arnavisland.link.PcStats
import io.github.arnavdugad.arnavisland.link.PcBattery
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import io.github.arnavdugad.arnavisland.link.PeerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Everything on your PC's island, from here (island 0.22): its numbers live, its controls, the focus clock, its command
 * bar, power, where its sound goes, its pages on the PC and every one of its settings. Asked once a second while this
 * shows; anything changed here shows at once and then follows what the PC says.
 */
@Composable fun IslandScreen(pc: PeerView?, visible: Boolean, onPair: () -> Unit, padding: PaddingValues) {
    val t = LocalTokens.current
    val stats by Hub.pcStats.collectAsState(); val controls by Hub.pcControls.collectAsState(); val settings by Hub.islandSettings.collectAsState(); val outputs by Hub.outputs.collectAsState()
    val battery by Hub.pcBattery.collectAsState(); val peers by Hub.peers.collectAsState()
    val pcs = remember(peers) { peers.filter { it.paired && !it.phone } }
    val confirm: (Confirm) -> Unit = { IslandUi.confirm = it }
    val ready = Hub.islandReady(pc)
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(pc?.id, ready, visible) {
        if (!ready || !visible) return@LaunchedEffect
        Hub.loadOutputs(); if (Hub.islandSettings.value == null) Hub.loadIslandSettings()
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { var n = 0; while (true) { Hub.refreshIsland(withStats = true); if (++n % 10 == 0) Hub.loadOutputs(); delay(1000) } }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 20.dp)) {
            ScreenTitle("Island", over = pc?.let { if (!it.online) "${it.name}  ·  away" else "${it.name}  ·  ${quality(it).text.lowercase()}" } ?: "No PC yet") { if (pc != null) LiveDot(pc.online) }
            // 1.5: with more than one PC, their numbers side by side in a carousel: swipe to another and the rest follows it.
            val carousel = pc != null && pcs.size > 1
            if (carousel) { StatsCarousel(pcs, pc!!, stats); Spacer(Modifier.height(4.dp)) }
            when {
                pc == null -> Notice(Icons.Rounded.Laptop, "Pair with your PC", "Then everything on its island is here: its numbers, its controls, its settings.", "Pair", onPair)
                !pc.online -> Notice(Icons.Rounded.CloudOff, "${pc.name} is away", "Its island shows here again as soon as it's back.", null) {}
                pc.revision < 5 -> Notice(Icons.Rounded.SystemUpdate, "Update Arnav Island on ${pc.name}", "Version 0.22 or later lets this phone control everything on its island.", null) {}
                else -> {
                    if (!carousel) StatsPanel(stats, live = true) { IslandUi.statsOpen = true }
                    if (battery?.present == true) { SectionLabel("Battery"); BatteryPanel(battery!!) }
                    SectionLabel("Controls"); ControlsPanel(controls, confirm)
                    SectionLabel("Focus"); FocusPanel(controls)
                    SectionLabel("Run on ${pc.name}"); CommandPanel(pc.name, confirm)
                    SectionLabel("Power"); PowerPanel(pc.name, confirm)
                    if (outputs.isNotEmpty()) { SectionLabel("Sound comes from"); OutputsPanel(outputs, settings) }
                    SectionLabel("Show on ${pc.name}"); PagesPanel()
                    SectionLabel("Island settings"); SettingsPanel(settings) { IslandUi.section = it }
                    Spacer(Modifier.height(24.dp))
                }
            }
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
        Text(c.title, style = Type.title, color = t.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp)); Text(c.detail, style = Type.body, color = t.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton(onDismiss, Modifier.weight(1f)) { Text("Cancel", style = Type.bodyStrong) }
            GlassButton({ haptics.performHapticFeedback(HapticFeedbackType.Confirm); c.run(); onDismiss() }, Modifier.weight(1f), prominent = !c.danger) { Text(c.action, style = Type.bodyStrong, color = if (c.danger) t.danger else Color.Unspecified) }
        }
    }
}

@Composable private fun Notice(icon: ImageVector, title: String, detail: String, action: String?, onAction: () -> Unit) {
    val t = LocalTokens.current
    GlassPanel(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(58.dp).clip(CircleShape).background(t.accent.copy(alpha = .16f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = t.accent, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.height(14.dp)); Text(title, style = Type.headline, color = t.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp)); Text(detail, style = Type.body, color = t.muted, textAlign = TextAlign.Center)
            if (action != null) { Spacer(Modifier.height(18.dp)); GlassButton(onAction, prominent = true, contentPadding = PaddingValues(horizontal = 26.dp, vertical = 13.dp)) { Text(action, style = Type.bodyStrong) } }
        }
    }
}

// ---- stats ----
/** Three gauges (processor, graphics, memory), the network either way, and a flowing graph of the processor. */
/** 1.5: the PCs' numbers in a glass carousel: each page one PC's (the others as last read); settling on one chooses it. */
@Composable private fun StatsCarousel(pcs: List<PeerView>, pc: PeerView, stats: PcStats?) {
    val t = LocalTokens.current; val index = pcs.indexOfFirst { it.id == pc.id }.coerceAtLeast(0)
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = index) { pcs.size }
    LaunchedEffect(pager.settledPage) { pcs.getOrNull(pager.settledPage)?.let { if (it.id != Hub.pc()?.id) Hub.choose(it.id) } }
    LaunchedEffect(index) { if (pager.settledPage != index && !pager.isScrollInProgress) pager.animateScrollToPage(index) }
    androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxWidth(), pageSpacing = 12.dp) { page ->
        val p = pcs[page]; val live = p.id == pc.id
        StatsPanel(if (live) stats else Hub.cachedStats(p.id), live = live && p.online, title = p.name, away = !p.online) { if (live) IslandUi.statsOpen = true }
    }
    // Which one this is.
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
        pcs.indices.forEach { i ->
            val on = i == pager.currentPage; val w by animateDpAsState(if (on) 18.dp else 6.dp, spring(dampingRatio = .7f, stiffness = 500f), label = "Dot")
            Box(Modifier.padding(horizontal = 3.dp).size(w, 6.dp).clip(CircleShape).background(if (on) t.accent else t.faint.copy(alpha = .5f)))
        }
    }
}

/**
 * The PC's numbers: its rings, a flowing graph of the processor and (1.5) a bar for each core, which shimmers when the
 * core is busy. The card warms when the processor has been working hard for half a minute.
 */
@Composable private fun StatsPanel(s: PcStats?, live: Boolean, title: String? = null, away: Boolean = false, onOpen: () -> Unit) {
    val t = LocalTokens.current; val trail by Hub.statsTrail.collectAsState()
    // Heat: the processor's average over the last 30 s, past 55%, up to full warmth at 90%.
    val heatTarget = if (!live) 0f else trail.takeLast(30).map { it.cpu }.filter { it >= 0 }.takeIf { it.size >= 10 }?.average()?.toFloat()?.let { ((it - 55f) / 35f).coerceIn(0f, 1f) } ?: 0f
    val heat by animateFloatAsState(heatTarget, tween(1400), label = "Heat")
    val warm = Color(0xFFFF8A3D)
    Box(Modifier.fillMaxWidth().glass(GlassShapes.card).clip(GlassShapes.card)
        .drawBehind { if (heat > 0.01f) drawRect(Brush.radialGradient(listOf(warm.copy(alpha = .30f * heat), Color(0xFFFF3D3D).copy(alpha = .10f * heat), Color.Transparent), Offset(size.width * .5f, 0f), size.width * .9f)) }
        .clickable(role = Role.Button) { onOpen() }.semantics { contentDescription = (title?.let { "$it: " } ?: "") + "Your PC's numbers. Tap for more" }) {
        Column(Modifier.padding(18.dp).graphicsLayer { alpha = if (away) .5f else 1f }) {
            if (title != null) Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Type.bodyStrong, color = t.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (away) "Away" else if (live) "Live" else "Swipe to see it live", style = Type.micro, color = if (live && !away) t.good else t.muted)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Gauge("CPU", s?.cpu ?: -1.0, lerp(t.accent, warm, heat)); Gauge("GPU", s?.gpu ?: -1.0, t.accent2); Gauge("Memory", s?.ramPercent ?: -1.0, t.good)
            }
            if (heat > .5f) Text("Working hard: ${s?.cpu?.roundToInt() ?: 0}% for the last half minute", style = Type.caption, color = warm, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Graph(s?.cpuHistory.orEmpty(), lerp(t.accent, warm, heat), Modifier.fillMaxWidth().height(46.dp), top = 100f)
            if (!s?.cores.isNullOrEmpty()) { Spacer(Modifier.height(12.dp)); CoreBars(s!!.cores, lerp(t.accent, warm, heat), live) }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Rate(Icons.Rounded.South, s?.download); Rate(Icons.Rounded.North, s?.upload)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("More", style = Type.caption, color = t.muted); Icon(Icons.Rounded.ChevronRight, null, tint = t.muted, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}
/** 1.5: a bar for each core, its height its load; a busy core (70% or more) shimmers, and the bars ease between readings. */
@Composable private fun CoreBars(cores: List<Float>, color: Color, live: Boolean) {
    val t = LocalTokens.current; val reduced = LocalReduced.current
    val shimmer by rememberInfiniteTransition(label = "Cores").animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "Shimmer")
    val shown = cores.map { v -> animateFloatAsState(if (v < 0) 0f else v / 100f, spring(dampingRatio = .85f, stiffness = 140f), label = "Core").value }
    Column {
        Canvas(Modifier.fillMaxWidth().height(34.dp).semantics { contentDescription = "${cores.size} cores: " + cores.joinToString { if (it < 0) "unknown" else "${it.roundToInt()}%" } }) {
            val n = shown.size; val gap = if (n > 24) 2.dp.toPx() else 3.dp.toPx(); val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f); val r = (w / 2).coerceAtMost(3.dp.toPx())
            shown.forEachIndexed { i, v ->
                val x = i * (w + gap); val h = (v * size.height).coerceAtLeast(2.dp.toPx())
                drawRoundRect(t.track, Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height), androidx.compose.ui.geometry.CornerRadius(r, r))
                drawRoundRect(color.copy(alpha = .55f + .45f * v), Offset(x, size.height - h), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(r, r))
                // Busy: a band of light rises through the bar, each core a little behind the one before.
                if (live && !reduced && v >= .7f) {
                    val phase = (shimmer + i * .07f) % 1f; val y = size.height - h * phase
                    drawRoundRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .55f), Color.Transparent), y - 10.dp.toPx(), y + 10.dp.toPx()),
                        Offset(x, size.height - h), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(r, r))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("${cores.size} cores", style = Type.micro, color = t.muted, modifier = Modifier.weight(1f))
            val busy = cores.count { it >= 70f }; if (busy > 0) Text("$busy busy", style = Type.micro, color = t.muted)
        }
    }
}

/**
 * 1.5: the PC's battery (island 0.23): a ring with its level, whether it's charging and for how long, the power going in
 * or out, its health and cycles, its temperature and capacity, and its last day.
 */
@Composable private fun BatteryPanel(b: PcBattery) {
    val t = LocalTokens.current
    val colour = when { b.charging -> t.good; b.percent in 0..20 -> t.danger; else -> t.accent }
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                    ProgressRing(if (b.percent >= 0) b.percent / 100f else 0f, colour, t.track, Modifier.fillMaxSize(), 7.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (b.percent >= 0) RollingDigits("${b.percent}%", Type.headline, t.text) else Text("—", style = Type.headline, color = t.faint)
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
            if (b.day.size >= 2) { Spacer(Modifier.height(14.dp)); BatteryDay(b.day, t.accent) }
            Spacer(Modifier.height(10.dp))
            val facts = buildList {
                if (b.health >= 0) add("Health ${(b.health * 100).roundToInt()}%" + if (b.healthBefore >= 0 && kotlin.math.abs(b.health - b.healthBefore) >= .0005) ", %+.1f this week".format((b.health - b.healthBefore) * 100).replace("-", "−") else "")
                if (b.cycles > 0) add("${b.cycles} cycles")
                if (b.temperatureDeciK > 0) add("%.1f°C".format(b.temperatureDeciK / 10.0 - 273.15))
                if (b.fullMwh > 0) add("%.1f of %.1f Wh".format(b.remainingMwh / 1000.0, b.fullMwh / 1000.0))
                if (b.voltageMv > 0) add("%.2f V".format(b.voltageMv / 1000.0))
                listOf(b.manufacturer, b.name).filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() }?.let { add(it) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { facts.forEach { GlassChip(it) } }
        }
    }
}
/** The battery's last day: its level over time, in the accent, green while it charged. */
@Composable private fun BatteryDay(day: List<Triple<Long, Int, Boolean>>, colour: Color) {
    val t = LocalTokens.current
    Column {
        Canvas(Modifier.fillMaxWidth().height(54.dp).semantics { contentDescription = "The battery over the last day" }) {
            val first = day.first().first; val span = (day.last().first - first).coerceAtLeast(1).toFloat()
            fun at(i: Int) = Offset((day[i].first - first) / span * size.width, size.height - day[i].second / 100f * size.height * .94f)
            val fill = Path().apply { moveTo(0f, size.height); day.indices.forEach { lineTo(at(it).x, at(it).y) }; lineTo(size.width, size.height); close() }
            drawPath(fill, Brush.verticalGradient(listOf(colour.copy(alpha = .25f), Color.Transparent)))
            for (i in 1 until day.size) drawLine(if (day[i].third) t.good else colour, at(i - 1), at(i), 2.dp.toPx(), StrokeCap.Round)
        }
        Row(Modifier.fillMaxWidth()) { Text("A day ago", style = Type.micro, color = t.faint, modifier = Modifier.weight(1f)); Text("Now", style = Type.micro, color = t.faint) }
    }
}
@Composable private fun Gauge(label: String, value: Double, color: Color, size: Dp = 86.dp) {
    val t = LocalTokens.current; val known = value >= 0
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics { contentDescription = if (known) "$label ${value.roundToInt()} percent" else "$label unknown" }) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            ProgressRing(if (known) (value / 100).toFloat() else 0f, color, t.track, Modifier.fillMaxSize(), 6.dp)
            if (known) RollingDigits("${value.roundToInt()}%", Type.headline, t.text) else Text("—", style = Type.headline, color = t.faint)
        }
        Spacer(Modifier.height(6.dp)); Text(label, style = Type.caption, color = t.muted)
    }
}
@Composable private fun Rate(icon: ImageVector, bytes: Double?) {
    val t = LocalTokens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = t.accent, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
        Text(if (bytes == null) "—" else rateText(bytes), style = Type.bodyStrong, color = t.text)
    }
}
private fun span(seconds: Long): String { val d = seconds / 86_400; val h = seconds % 86_400 / 3_600; val m = seconds % 3_600 / 60; return if (d > 0) "$d d $h h" else if (h > 0) "$h h $m min" else "$m min" }

/**
 * A graph of the last samples that flows: each new sample slides in from the right as the rest moves along, over a soft
 * fill. [top]: the scale's top (null: the samples' own highest, for downloads).
 */
@Composable private fun Graph(samples: List<Float>, color: Color, modifier: Modifier, top: Float? = null) {
    val slide = remember { Animatable(0f) }; val reduced = LocalReduced.current
    LaunchedEffect(samples) { if (!reduced && samples.size > 1) { slide.snapTo(1f); slide.animateTo(0f, tween(950, easing = LinearEasing)) } }
    Canvas(modifier.clipToBounds()) {
        val known = samples.map { if (it < 0) 0f else it }; if (known.size < 2) return@Canvas
        val high = max(top ?: (known.maxOrNull() ?: 1f) * 1.15f, 1f); val step = size.width / (known.size - 1); val shift = slide.value * step
        val path = Path(); val fill = Path()
        known.forEachIndexed { i, v -> val x = i * step + shift; val y = size.height - (v / high).coerceIn(0f, 1f) * size.height * .92f
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, size.height); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) } }
        fill.lineTo((known.size - 1) * step + shift, size.height); fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = .28f), Color.Transparent)))
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val lx = (known.size - 1) * step + shift; val ly = size.height - (known.last() / high).coerceIn(0f, 1f) * size.height * .92f
        drawCircle(color.copy(alpha = .3f), 6.dp.toPx(), Offset(lx.coerceAtMost(size.width), ly)); drawCircle(color, 3.dp.toPx(), Offset(lx.coerceAtMost(size.width), ly))
    }
}

/** The PC's numbers in full: live graphs for the processor, graphics and network, and what the PC is. */
@Composable private fun StatsSheet(visible: Boolean, s: PcStats?, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    GlassSheet(visible, onDismiss, Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .92f)) {
        if (s == null) return@GlassSheet
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Text(s.name.ifEmpty { "Your PC" }, style = Type.title, color = t.text); if (s.model.isNotEmpty()) Text(s.model, style = Type.caption, color = t.muted)
            Spacer(Modifier.height(16.dp))
            // 1.5: the numbers seen while the tab showed (up to five minutes), else the PC's last 40 seconds; drag to read any moment.
            val trail by Hub.statsTrail.collectAsState(); val now = System.currentTimeMillis()
            fun series(pick: (Hub.StatsPoint) -> Float, history: List<Float>) = if (trail.size >= history.size && trail.size >= 10) trail.map { it.at to pick(it) } else history.mapIndexed { i, v -> (now - (history.size - 1 - i) * 1000L) to v }
            BigGraph("Processor", if (s.cpu >= 0) "${s.cpu.roundToInt()}%" else "—", series({ it.cpu }, s.cpuHistory), t.accent, 100f, s.cpuName.ifEmpty { null }?.let { "$it  ·  ${s.logical} threads" }) { "${it.roundToInt()}%" }
            BigGraph("Graphics", if (s.gpu >= 0) "${s.gpu.roundToInt()}%" else "—", series({ it.gpu }, s.gpuHistory), t.accent2, 100f, s.gpuName.ifEmpty { null }) { if (it < 0) "—" else "${it.roundToInt()}%" }
            BigGraph("Downloading", rateText(s.download), series({ it.download }, s.downloadHistory), t.good, null, "Sending ${rateText(s.upload)}") { rateText(it.toDouble()) }
            GlassPanel(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Fact(Icons.Rounded.Memory, "Memory", "%.1f of %.1f GB  ·  %d%%".format(s.ramUsedGiB, s.ramTotalGiB, s.ramPercent.roundToInt()))
                    if (s.diskUsedPercent >= 0) { Hairline(); Fact(Icons.Rounded.Storage, "Disk", "%.0f GB free of %.0f GB".format(s.diskFreeGiB, s.diskTotalGiB)) }
                    if (s.battery >= 0) { Hairline(); Fact(if (s.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryFull, "Battery", "${s.battery}%" + when { s.charging -> "  ·  charging"; s.batteryMinutes > 0 -> "  ·  ${span((s.batteryMinutes * 60).toLong())} left"; else -> "" }) }
                    Hairline(); Fact(Icons.Rounded.Timer, "On for", span(s.uptime))
                    if (s.os.isNotEmpty()) { Hairline(); Fact(Icons.Rounded.DesktopWindows, "Windows", s.os) }
                }
            }
        }
    }
}
@Composable private fun BigGraph(title: String, now: String, series: List<Pair<Long, Float>>, color: Color, top: Float?, detail: String?, format: (Float) -> String) {
    val t = LocalTokens.current; val samples = series.map { it.second }; val haptics = LocalHapticFeedback.current
    var at by remember { mutableStateOf<Int?>(null) }
    GlassPanel(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(title, style = Type.bodyStrong, color = t.text, modifier = Modifier.weight(1f)); RollingDigits(now, Type.headline, color)
            }
            if (detail != null) Text(detail, style = Type.caption, color = t.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            // Percentages up to the next step past the highest (so a quiet PC's line isn't flat on the floor); rates to their own highest.
            val scale = if (top == null) null else listOf(10f, 25f, 50f, 75f, 100f).first { it >= minOf(100f, (samples.maxOrNull() ?: 0f) * 1.15f) }
            Box(Modifier.fillMaxWidth().height(84.dp).pointerInput(series.size) {
                // Touch and drag along it: a line at that moment, with its value and how long ago it was.
                awaitEachGesture {
                    val down = awaitFirstDown(); down.consume(); fun index(x: Float) = if (samples.size < 2) null else (x / size.width * (samples.size - 1)).roundToInt().coerceIn(0, samples.size - 1)
                    at = index(down.position.x); haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    while (true) { val e = awaitPointerEvent(); val c = e.changes.firstOrNull() ?: break; if (!c.pressed) break; c.consume(); val i = index(c.position.x); if (i != at) { at = i; if (i != null && i % 5 == 0) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) } }
                    at = null
                }
            }) {
                Graph(samples, color, Modifier.fillMaxSize(), scale)
                if (scale != null && at == null) Text("${scale.roundToInt()}%", style = Type.micro, color = t.faint, modifier = Modifier.align(Alignment.TopEnd))
                val i = at
                if (i != null && samples.size >= 2) {
                    val high = max(scale ?: (samples.maxOrNull() ?: 1f) * 1.15f, 1f)
                    Canvas(Modifier.fillMaxSize()) {
                        val x = i / (samples.size - 1f) * size.width; val y = size.height - (samples[i].coerceAtLeast(0f) / high).coerceIn(0f, 1f) * size.height * .92f
                        drawLine(t.text.copy(alpha = .35f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                        drawCircle(color.copy(alpha = .3f), 7.dp.toPx(), Offset(x, y)); drawCircle(color, 3.5.dp.toPx(), Offset(x, y))
                    }
                    val ago = ((System.currentTimeMillis() - series[i].first) / 1000).coerceAtLeast(0)
                    Text("${format(samples[i])}  ·  ${if (ago < 2) "now" else if (ago < 90) "$ago s ago" else "${ago / 60} min ago"}", style = Type.micro, color = t.text,
                        modifier = Modifier.align(Alignment.TopCenter).clip(RoundedCornerShape(8.dp)).background(t.deep.copy(alpha = .7f)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
            if (series.size >= 2) Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                val span = ((series.last().first - series.first().first) / 1000).coerceAtLeast(1)
                Text(if (span < 90) "$span s ago" else "${span / 60} min ago", style = Type.micro, color = t.faint, modifier = Modifier.weight(1f)); Text("Now", style = Type.micro, color = t.faint)
            }
        }
    }
}
@Composable private fun Fact(icon: ImageVector, title: String, value: String) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = t.accent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
        Text(title, style = Type.body, color = t.text, modifier = Modifier.weight(1f)); Text(value, style = Type.caption, color = t.muted, textAlign = TextAlign.End)
    }
}

// ---- controls ----
@Composable private fun ControlsPanel(c: PcControls?, onConfirm: (Confirm) -> Unit) {
    val t = LocalTokens.current
    if (c == null) { GlassPanel(Modifier.fillMaxWidth().height(120.dp)) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Radar(t.accent, Modifier.size(40.dp)) } }; return }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(Icons.Rounded.Wifi, Icons.Rounded.WifiOff, "Wi-Fi", c.wifi, c.busy and 1 != 0, Modifier.weight(1f)) { on -> Hub.setControl(IslandWire.WIFI, if (on) 1 else 0) { it.copy(wifi = if (on) 1 else 0, busy = it.busy or 1) } }
            Switch(Icons.Rounded.Bluetooth, Icons.Rounded.BluetoothDisabled, "Bluetooth", c.bluetooth, c.busy and 2 != 0, Modifier.weight(1f)) { on -> Hub.setControl(IslandWire.BLUETOOTH, if (on) 1 else 0) { it.copy(bluetooth = if (on) 1 else 0, busy = it.busy or 2) } }
            Switch(Icons.Rounded.AirplanemodeActive, Icons.Rounded.AirplanemodeInactive, "Airplane", if (c.wifi < -1 && c.bluetooth < -1) -2 else if (c.airplane) 1 else 0, c.busy and 4 != 0, Modifier.weight(1f)) { on ->
                Hub.setControl(IslandWire.AIRPLANE, if (on) 1 else 0) { it.copy(wifi = if (on) 0 else 1, bluetooth = if (it.bluetooth < -1) it.bluetooth else if (on) 0 else 1, busy = it.busy or 4) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(Icons.Rounded.DarkMode, Icons.Rounded.LightMode, "Dark mode", c.dark, c.busy and 8 != 0, Modifier.weight(1f)) { on -> Hub.setControl(IslandWire.DARK, if (on) 1 else 0) { it.copy(dark = if (on) 1 else 0, busy = it.busy or 8) } }
            Switch(Icons.Rounded.MicOff, Icons.Rounded.Mic, "Mic muted", if (!c.micAvailable) -2 else if (c.micMuted) 1 else 0, false, Modifier.weight(1f)) { on -> Hub.setControl(IslandWire.MIC, if (on) 1 else 0) { it.copy(micMuted = on) } }
            Switch(Icons.AutoMirrored.Rounded.VolumeOff, Icons.AutoMirrored.Rounded.VolumeUp, "Muted", if (c.muted) 1 else 0, false, Modifier.weight(1f)) { on -> Hub.setControl(IslandWire.MUTE, if (on) 1 else 0) { it.copy(muted = on) } }
        }
        GlassPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                if (c.brightness >= 0) { Level(Icons.Rounded.LightMode, "Brightness", c.brightness) { v -> Hub.setControl(IslandWire.BRIGHTNESS, v) { it.copy(brightness = v) } }; Spacer(Modifier.height(12.dp)) }
                Level(if (c.muted || c.volume == 0) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, "Volume", c.volume) { v -> Hub.setControl(IslandWire.VOLUME, v) { it.copy(volume = v) } }
                // 1.5: a step down and up (5% each; held, they keep going).
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume", style = Type.caption, color = t.muted, modifier = Modifier.weight(1f))
                    StepButton(Icons.Rounded.Remove, "Volume down", { val v = ((Hub.pcControls.value?.volume ?: c.volume) - 5).coerceIn(0, 100); Hub.setControl(IslandWire.VOLUME, v) { it.copy(volume = v, muted = false) } }, size = 40.dp)
                    StepButton(Icons.Rounded.Add, "Volume up", { val v = ((Hub.pcControls.value?.volume ?: c.volume) + 5).coerceIn(0, 100); Hub.setControl(IslandWire.VOLUME, v) { it.copy(volume = v, muted = false) } }, size = 40.dp)
                }
            }
        }
    }
}
/** A switch as a glass tile: lit when on; a small ring while it's changing on the PC; dimmed when the PC has no such thing. */
@Composable private fun Switch(on: ImageVector, off: ImageVector, label: String, state: Int, busy: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current; val lit = state == 1; val none = state < -1
    val fill by animateColorAsState(if (lit) t.accent.copy(alpha = if (t.dark) .30f else .22f) else Color.Transparent, tween(260), label = "Tile")
    Column(modifier.height(92.dp).glass(RoundedCornerShape(22.dp), GlassLevel.Control).clip(RoundedCornerShape(22.dp)).background(fill)
        .clickable(enabled = !none && !busy && state >= 0, role = Role.Switch) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onChange(!lit) }
        .semantics { contentDescription = "$label, ${when { none -> "not on this PC"; busy -> "changing"; lit -> "on"; else -> "off" }}" }.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            if (busy) Radar(t.accent, Modifier.fillMaxSize())
            Icon(if (lit) on else off, null, tint = if (none) t.faint else if (lit) t.accent else t.text, modifier = Modifier.size(20.dp))
        }
        Column {
            Text(label, style = Type.caption, color = if (none) t.faint else t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(when { none -> "Not here"; busy -> "Changing…"; state == -1 -> "…"; lit -> "On"; else -> "Off" }, style = Type.micro, color = t.muted)
        }
    }
}
/** A level (brightness, volume): its icon, its number, and a slider sent when let go. */
@Composable private fun Level(icon: ImageVector, label: String, value: Int, onDone: (Int) -> Unit) {
    val t = LocalTokens.current; var moving by remember { mutableStateOf<Float?>(null) }; val shown = moving ?: (value / 100f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = t.accent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
        GlassSlider(shown, { moving = it }, { moving = null; onDone((it * 100).roundToInt()) }, Modifier.weight(1f), description = label)
        Spacer(Modifier.width(12.dp)); Text("${(shown * 100).roundToInt()}%", style = Type.caption, color = t.muted, modifier = Modifier.widthIn(min = 38.dp), textAlign = TextAlign.End)
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
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                ProgressRing(fraction, if (c.focusMode == 1) t.good else t.accent, t.track, Modifier.fillMaxSize(), 7.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RollingDigits(clock(shown), Type.headline, t.text)
                    Text(when (c.focusMode) { 1 -> "Break"; 2 -> "Stopwatch"; else -> "Focus" }, style = Type.micro, color = t.muted)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(when { c.focusFinished -> "Done"; c.focusRunning -> "Running"; c.focusMode == 2 -> if (shown > .5) "Paused" else "Ready"; shown < c.focusDuration - .5 -> "Paused"; else -> "Ready" },
                    style = Type.bodyStrong, color = t.text)
                Text(if (c.focusMode == 2) "Counting up on your PC" else "On your PC’s island", style = Type.caption, color = t.muted)
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
/** A focus preset: a glass capsule, its label centred. */
@Composable private fun Preset(label: String, modifier: Modifier, onClick: () -> Unit) {
    GlassButton(onClick, modifier, contentPadding = PaddingValues(vertical = 10.dp)) { Text(label, style = Type.caption, maxLines = 1) }
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).glass(GlassShapes.capsule, GlassLevel.Control).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = t.muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) Text("An app, a file, a setting, “timer 10”…", style = Type.body, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicTextField(text, { text = it.take(160) }, singleLine = true, textStyle = Type.body.copy(color = t.text), cursorBrush = SolidColor(t.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { rows.firstOrNull()?.let { run(0, it, false) } }),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { typing = it.isFocused }.semantics { contentDescription = "Run on $pcName" })
                }
                if (text.isNotEmpty()) Icon(Icons.Rounded.Close, "Clear", tint = t.muted, modifier = Modifier.size(20.dp).clip(CircleShape).clickable { text = "" })
            }
            rows.take(6).forEachIndexed { i, row ->
                if (i > 0) Hairline()
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { run(i, row, false) }.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(t.accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) { Icon(kindIcon(row.kind), null, tint = t.accent, modifier = Modifier.size(19.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.title, style = Type.bodyStrong, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

// ---- power ----
@Composable private fun PowerPanel(pcName: String, onConfirm: (Confirm) -> Unit) {
    fun power(control: Int, title: String, detail: String, action: String, danger: Boolean) = onConfirm(Confirm(title, detail, action, danger) { Hub.setControl(control, 1) })
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Action(Icons.Rounded.Lock, "Lock", Modifier.weight(1f)) { Hub.setControl(IslandWire.LOCK, 1) }
        Action(Icons.Rounded.Bedtime, "Sleep", Modifier.weight(1f)) { power(IslandWire.SLEEP, "Put $pcName to sleep?", "It wakes when you open it or press a key.", "Sleep", false) }
        Action(Icons.Rounded.RestartAlt, "Restart", Modifier.weight(1f)) { power(IslandWire.RESTART, "Restart $pcName?", "Anything unsaved there may be lost.", "Restart", true) }
        Action(Icons.Rounded.PowerSettingsNew, "Shut down", Modifier.weight(1f)) { power(IslandWire.SHUT_DOWN, "Shut down $pcName?", "Anything unsaved there may be lost.", "Shut down", true) }
    }
    Spacer(Modifier.height(10.dp))
    GlassPanel(Modifier.fillMaxWidth()) {
        GlassRow(Icons.Rounded.DeleteSweep, "Empty the recycle bin", "For good, on $pcName", onClick = { power(IslandWire.EMPTY_BIN, "Empty the recycle bin?", "What's in it on $pcName is deleted for good.", "Empty", true) })
    }
}
@Composable private fun Action(icon: ImageVector, label: String, modifier: Modifier, height: Dp = 78.dp, onClick: () -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    Column(modifier.height(height).glass(RoundedCornerShape(20.dp), GlassLevel.Control).clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() }.padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = t.text, modifier = Modifier.size(22.dp)); Spacer(Modifier.height(6.dp)); Text(label, style = Type.micro, color = t.muted, maxLines = 1)
    }
}

// ---- sound output, pages ----
@Composable private fun OutputsPanel(outputs: List<AudioOutput>, settings: IslandSettings?) {
    val t = LocalTokens.current
    val direct = settings?.items?.firstOrNull { it.key == "directAudio" }
    GlassPanel(Modifier.fillMaxWidth()) {
        Column {
            outputs.forEachIndexed { i, o ->
                if (i > 0) Hairline()
                GlassRow(when (o.form) { 3 -> Icons.Rounded.Headphones; 4 -> Icons.Rounded.SettingsVoice; else -> Icons.Rounded.Speaker }, o.name, if (o.current) "Playing here" else null,
                    tint = if (o.current) t.accent else t.muted, onClick = { if (!o.current) Hub.selectOutput(o.id) }) {
                    if (o.current) Icon(Icons.Rounded.Check, "Chosen", tint = t.accent)
                }
            }
            if (direct != null && direct.value == 0) {
                Hairline(); GlassRow(Icons.Rounded.SwapHoriz, direct.title, "Needed to switch from here", tint = t.warn) { GlassSwitch(false, { Hub.setIslandSetting("directAudio", 1) }) }
            }
        }
    }
}
@Composable private fun PagesPanel() {
    val icons = listOf(Icons.Rounded.Home, Icons.Rounded.MusicNote, Icons.Rounded.Speed, Icons.Rounded.CenterFocusStrong, Icons.Rounded.Settings, Icons.Rounded.Inventory2, Icons.Rounded.Equalizer, Icons.Rounded.ToggleOn)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in listOf(listOf(0, 1, 2, 3), listOf(5, 6, 7, 4))) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (i in row) Action(icons[i], IslandWire.PAGES[i], Modifier.weight(1f), height = 70.dp) { Hub.openIslandPage(i) }
        }
        Spacer(Modifier.height(2.dp))
        GlassButton({ Hub.closeIsland() }, Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 11.dp)) { Icon(Icons.Rounded.CloseFullscreen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Close the island", style = Type.caption) }
    }
}

// ---- settings ----
private val sectionIcons = listOf(Icons.Rounded.Tune, Icons.Rounded.ViewAgenda, Icons.Rounded.Palette, Icons.Rounded.Animation, Icons.AutoMirrored.Rounded.ShortText, Icons.Rounded.MusicNote,
    Icons.Rounded.BatteryChargingFull, Icons.Rounded.Home, Icons.Rounded.PrivacyTip, Icons.Rounded.Info)
@Composable private fun SettingsPanel(s: IslandSettings?, onOpen: (Int) -> Unit) {
    val t = LocalTokens.current
    GlassPanel(Modifier.fillMaxWidth()) {
        if (s == null) Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { Radar(t.accent, Modifier.size(34.dp)) }
        else Column {
            s.sections.forEachIndexed { i, name ->
                val count = s.items.count { it.section == i && !(it.control == 5 && it.action in listOf(1, 16)) }; if (count == 0) return@forEachIndexed
                if (i > 0 && s.items.any { it.section < i }) Hairline()
                GlassRow(sectionIcons.getOrElse(i) { Icons.Rounded.Tune }, name, "$count setting${if (count == 1) "" else "s"}", onClick = { onOpen(i) }) { Icon(Icons.Rounded.ChevronRight, null, tint = t.muted) }
            }
        }
    }
}
/** Switching these off cuts this phone off from the PC: asked first. */
private val lifelines = mapOf("sharing" to "This phone loses its connection to the PC until sharing is turned back on there.",
    "phoneControl" to "This phone can't control the PC until that's turned back on there.", "relay" to "On another network, this phone can't reach the PC until it's turned back on there.")
@Composable private fun SectionSheet(section: Int?, s: IslandSettings?, onConfirm: (Confirm) -> Unit, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    GlassSheet(section != null && s != null, onDismiss, Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .9f)) {
        if (section == null || s == null) return@GlassSheet
        Text(s.sections.getOrElse(section) { "" }, style = Type.title, color = t.text); Spacer(Modifier.height(4.dp))
        Text("On your PC’s island, as its Settings has it", style = Type.caption, color = t.muted); Spacer(Modifier.height(14.dp))
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
                    Text(item.title, style = Type.bodyStrong, color = t.text)
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
                    fun snap(f: Float): Int { val raw = item.lo + (f * span).roundToInt(); val step = item.step.coerceAtLeast(1); return (item.lo + ((raw - item.lo + step / 2) / step) * step).coerceIn(item.lo, item.hi) }
                    GlassSlider((shown - item.lo).toFloat() / span, { moving = snap(it) }, { val v = snap(it); moving = null; Hub.setIslandSetting(item.key, v) }, Modifier.fillMaxWidth(), description = item.title) }
                2 -> { Row { head() }; Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item.options.forEachIndexed { i, o -> val v = i; GlassChip(o, selected = item.value == v, onClick = { if (item.value != v) Hub.setIslandSetting(item.key, v) }) }
                    } }
                3 -> Row(verticalAlignment = Alignment.CenterVertically) { head()
                    GlassIconButton(Icons.Rounded.Remove, "Less", { Hub.setIslandSetting(item.key, (item.value - item.step).coerceAtLeast(item.lo)) }, size = 38.dp, enabled = item.value > item.lo)
                    Text(item.options.getOrNull(item.value - item.lo) ?: "${item.value}${item.unit}", style = Type.bodyStrong, color = t.text, modifier = Modifier.padding(horizontal = 10.dp))
                    GlassIconButton(Icons.Rounded.Add, "More", { Hub.setIslandSetting(item.key, (item.value + item.step).coerceAtMost(item.hi)) }, size = 38.dp, enabled = item.value < item.hi) }
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
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)) { Text(verb, style = Type.caption) } }
            }
        }
    }
}
