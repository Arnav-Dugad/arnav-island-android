package io.github.arnavdugad.arnavisland

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arnavdugad.arnavisland.link.PeerView
import kotlinx.coroutines.delay

/**
 * The talk's clock. Kept outside the sheet, so swiping it closed by mistake (or turning the phone) loses nothing; it
 * counts from the phone's uptime clock, so it stays right while the app is in the background.
 */
private object TalkClock {
    var length by mutableIntStateOf(runCatching { Hub.prefs.getInt("talkSeconds", 15 * 60) }.getOrDefault(15 * 60))
        private set
    var running by mutableStateOf(false)
        private set
    private var banked by mutableLongStateOf(0L)
    private var since = 0L
    fun elapsed(now: Long = SystemClock.elapsedRealtime()) = banked + if (running) now - since else 0L
    /** Milliseconds left: below zero once the talk runs over. */
    fun left(now: Long = SystemClock.elapsedRealtime()) = length * 1000L - elapsed(now)
    val started get() = running || banked > 0
    fun toggle() { val now = SystemClock.elapsedRealtime(); if (running) { banked += now - since; running = false } else { since = now; running = true } }
    fun reset() { running = false; banked = 0L }
    fun choose(seconds: Int) { length = seconds.coerceIn(60, 180 * 60); Hub.prefs.edit().putInt("talkSeconds", length).apply() }
}

/**
 * The timer's warnings, each with a feel of its own so a glance isn't needed: two soft taps at five minutes left, three
 * firm ones at one minute, and a long triple buzz when time is up.
 */
private enum class Warning(val at: Long, val timings: LongArray, val amplitudes: IntArray) {
    Five(300_000, longArrayOf(0, 90, 160, 90), intArrayOf(0, 150, 0, 150)),
    One(60_000, longArrayOf(0, 70, 90, 70, 90, 70), intArrayOf(0, 255, 0, 255, 0, 255)),
    Up(0, longArrayOf(0, 520, 160, 520, 160, 900), intArrayOf(0, 255, 0, 255, 0, 255)),
}

/** Plays a warning as an alarm, so a phone on silent (which mutes touch feedback) still gives it. */
private fun buzz(context: Context, w: Warning) {
    val v = (if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java))
        ?.takeIf { it.hasVibrator() } ?: return
    val effect = if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(w.timings, w.amplitudes, -1) else VibrationEffect.createWaveform(w.timings, -1)
    runCatching {
        if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        else @Suppress("DEPRECATION") v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
    }
}

/**
 * Presentation mode: the phone as a clicker for PowerPoint (and anything else that turns pages with the arrow keys).
 * Huge Back and Next buttons (or the volume keys: down for next, up for back), the slideshow's own shortcuts, a laser
 * pointer steered by pointing the phone, and a talk timer that warns by touch at five minutes, one minute and time up,
 * then counts the overtime. The screen stays on while it is open.
 */
@Composable fun PresenterSheet(open: Boolean, pc: PeerView?, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    GlassSheet(open, onDismiss, Modifier.fillMaxHeight(.93f)) {
        if (pc == null) { Text("Pair with your PC first", style = Type.body, color = t.muted); return@GlassSheet }
        val haptics = LocalHapticFeedback.current; val view = LocalView.current
        val scope = rememberCoroutineScope()
        val input = remember(pc.id) { InputLink(pc.id, pc.internet, scope).also { it.start() } }
        DisposableEffect(input) { onDispose { input.close() } }
        DisposableEffect(view) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
        val state by input.state.collectAsState()
        var pageKeys by remember { mutableStateOf(Hub.prefs.getBoolean("presenterPageKeys", false)) }
        var volumeFlips by remember { mutableStateOf(Hub.prefs.getBoolean("presenterVolume", true)) }
        var nextBumps by remember { mutableIntStateOf(0) }; var backBumps by remember { mutableIntStateOf(0) }
        val next = { input.key(if (pageKeys) Vk.NEXT else Vk.RIGHT) }
        val back = { input.key(if (pageKeys) Vk.PRIOR else Vk.LEFT) }
        // The volume keys, while this is open: they flip slides (with a tick, and the button they stand for bumps).
        val flip by rememberUpdatedState<(Boolean) -> Boolean> { up ->
            if (!volumeFlips) false else { if (up) { back(); backBumps++ } else { next(); nextBumps++ }; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); true }
        }
        DisposableEffect(open) {
            val mine: (Boolean) -> Boolean = { up -> flip(up) }
            if (open) VolumeKeys.handler = mine
            onDispose { if (VolumeKeys.handler === mine) VolumeKeys.handler = null }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Present", style = Type.title, color = t.text)
                Text(when (state) { 0 -> "Connecting to ${pc.name}…"; 1 -> "Running the slideshow on ${pc.name}"
                    else -> "Couldn’t connect. On the island: Settings › Privacy & productivity › My phone can control this PC" }, style = Type.caption, color = if (state == 2) t.danger else t.muted, maxLines = 3)
            }
            LiveDot(state == 1)
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().height(148.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SlideButton("Back", Icons.AutoMirrored.Rounded.ArrowBack, false, backBumps, Modifier.weight(1f)) { back() }
                SlideButton("Next", Icons.AutoMirrored.Rounded.ArrowForward, true, nextBumps, Modifier.weight(2f)) { next() }
            }
            Spacer(Modifier.height(12.dp))
            var black by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShowChip("Start", Icons.Rounded.PlayArrow) { black = false; input.key(0x74) }
                ShowChip("From here", Icons.Rounded.Slideshow) { black = false; input.key(0x74, Vk.SHIFT) }
                ShowChip("End", Icons.Rounded.Stop) { black = false; input.key(Vk.ESCAPE) }
                ShowChip("Black screen", Icons.Rounded.DarkMode, black) { black = !black; input.key(0x42) }
            }
            Spacer(Modifier.height(12.dp))
            Laser(input)
            Spacer(Modifier.height(12.dp))
            TalkTimer()
            SectionLabel("Options")
            Column(Modifier.fillMaxWidth().glass(GlassShapes.tile, GlassLevel.Control)) {
                GlassRow(Icons.AutoMirrored.Rounded.VolumeUp, "Volume keys flip slides", "Down for next, up for back", trailing = {
                    GlassSwitch(volumeFlips, { volumeFlips = it; Hub.prefs.edit().putBoolean("presenterVolume", it).apply() })
                })
                Hairline()
                GlassRow(Icons.Rounded.Keyboard, "Slide keys", if (pageKeys) "Page Down and Page Up, for apps that skip the arrows" else "The arrow keys, as PowerPoint expects", trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ShowChip("Arrows", null, !pageKeys) { pageKeys = false; Hub.prefs.edit().putBoolean("presenterPageKeys", false).apply() }
                        ShowChip("Page", null, pageKeys) { pageKeys = true; Hub.prefs.edit().putBoolean("presenterPageKeys", true).apply() }
                    }
                })
            }
        }
    }
}

/** One of the two big buttons; [bumps] counts presses from the volume keys, each a little spring so you see it went. */
@Composable private fun SlideButton(label: String, icon: ImageVector, prominent: Boolean, bumps: Int, modifier: Modifier, onClick: () -> Unit) {
    val reduced = LocalReduced.current; val bump = remember { Animatable(1f) }
    LaunchedEffect(bumps) { if (bumps > 0 && !reduced) { bump.snapTo(.94f); bump.animateTo(1f, spring(dampingRatio = .45f, stiffness = 520f)) } }
    GlassButton(onClick, modifier.fillMaxHeight().graphicsLayer { scaleX = bump.value; scaleY = bump.value }, prominent = prominent, shape = GlassShapes.card,
        contentPadding = PaddingValues(12.dp), description = "$label slide") {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(if (prominent) 42.dp else 34.dp)); Spacer(Modifier.height(8.dp))
            Text(label, style = Type.headline)
        }
    }
}

/** A slideshow shortcut, or a choice (when [selected] is given meaning): a capsule tall enough for a thumb, with a tick. */
@Composable private fun ShowChip(label: String, icon: ImageVector?, selected: Boolean = false, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    GlassChip(label, Modifier.heightIn(min = 44.dp), icon = icon, selected = selected) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() }
}

/**
 * The laser pointer: while held, PowerPoint's laser is on (Ctrl+L, and again on letting go) and pointing the phone
 * moves it, through the same engine as the air mouse. Its sensors listen only while it is held.
 */
@Composable private fun Laser(input: InputLink) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    var on by remember { mutableStateOf(false) }
    val mouse = rememberAirMouse(on) { x, y -> input.move(x, y) }
    val scale by springy(if (on) .97f else 1f, .5f, 600f)
    val glow by springy(if (on) 1f else 0f)
    Row(Modifier.fillMaxWidth().height(64.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        .glass(GlassShapes.capsule, GlassLevel.Control, if (on) t.danger else Color.Unspecified)
        .semantics { contentDescription = "Laser pointer: hold, then point the phone" }
        .whileHeld { h ->
            if (h != on) {
                if (!mouse.available && h) { haptics.performHapticFeedback(HapticFeedbackType.Reject); return@whileHeld }
                on = h; mouse.engaged = h; input.key(0x4C, Vk.CONTROL)
                haptics.performHapticFeedback(if (h) HapticFeedbackType.LongPress else HapticFeedbackType.SegmentTick)
            }
        }.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(22.dp).graphicsLayer { val s = .5f + glow; scaleX = s; scaleY = s; alpha = glow * .45f }.clip(CircleShape).background(t.danger))
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) t.danger else t.faint))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(if (on) "Laser on" else "Hold for laser pointer", style = Type.bodyStrong, color = t.text)
            Text(if (!mouse.available) "This phone has no gyroscope to point with" else if (on) "Point at the screen" else "Point the phone to move it", style = Type.caption, color = t.muted)
        }
    }
}

/**
 * The talk timer: a big countdown in a ring of the time left (amber near the end, red in the last minute), presets and
 * a minute up or down, start/pause and reset. Past zero it counts the overtime up. The warnings are felt as well as
 * seen, at five minutes, one minute and time up.
 */
@Composable private fun TalkTimer() {
    val t = LocalTokens.current; val context = LocalContext.current; val haptics = LocalHapticFeedback.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(TalkClock.running) {
        if (!TalkClock.running) return@LaunchedEffect
        // Warnings go only when a threshold is crossed while watching, never for one passed while this was closed.
        var before = TalkClock.left()
        while (true) {
            now = SystemClock.elapsedRealtime(); val left = TalkClock.left(now)
            Warning.entries.forEach { w -> if (before > w.at && left <= w.at && TalkClock.length * 1000L > w.at) buzz(context, w) }
            before = left
            // Wake as the shown second changes.
            delay(1000 - TalkClock.elapsed(now) % 1000 + 4)
        }
    }
    val length = TalkClock.length
    val left = TalkClock.left(now)
    val over = left <= 0 && TalkClock.started
    val shown = if (left > 0) clock(((left + 999) / 1000).toDouble()) else "+" + clock((-left / 1000).toDouble())
    val ringColor by animateColorAsState(when { left <= 60_000 -> t.danger; left <= minOf(300_000L, length * 500L) -> t.warn; else -> t.accent }, tween(600), label = "TalkRing")
    Column(Modifier.fillMaxWidth().glass(GlassShapes.card, GlassLevel.Control).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(212.dp), contentAlignment = Alignment.Center) {
            ProgressRing((left.toFloat() / (length * 1000f)).coerceIn(0f, 1f), ringColor, if (over) t.danger.copy(alpha = .3f) else t.track, Modifier.fillMaxSize(), stroke = 10.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when { over -> "OVERTIME"; TalkClock.running -> "LEFT"; TalkClock.started -> "PAUSED"; else -> "READY" }, style = Type.micro, color = if (over) t.danger else t.muted)
                Text(shown, style = Type.digits.copy(fontSize = if (shown.length > 5) 42.sp else 56.sp, fontFeatureSettings = "tnum"), color = if (over) t.danger else t.text, maxLines = 1)
                Text("of ${length / 60} min", style = Type.caption, color = t.muted)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            listOf(5, 10, 15, 20, 30).forEach { m -> ShowChip("$m", null, length == m * 60) { TalkClock.choose(m * 60) } }
            Text("min", style = Type.caption, color = t.muted, modifier = Modifier.align(Alignment.CenterVertically))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            StepButton(Icons.Rounded.Remove, "A minute less", { TalkClock.choose(TalkClock.length - 60) }, enabled = length > 60)
            Text("${length / 60} min", style = Type.headline.copy(fontFeatureSettings = "tnum"), color = t.text, textAlign = TextAlign.Center, modifier = Modifier.width(110.dp))
            StepButton(Icons.Rounded.Add, "A minute more", { TalkClock.choose(TalkClock.length + 60) }, enabled = length < 180 * 60)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton({ TalkClock.toggle(); now = SystemClock.elapsedRealtime() }, Modifier.weight(1f).fillMaxHeight(), prominent = true) {
                Icon(if (TalkClock.running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
                Text(when { TalkClock.running -> "Pause"; TalkClock.started -> "Resume"; else -> "Start timer" }, style = Type.bodyStrong)
            }
            GlassButton({ TalkClock.reset(); now = SystemClock.elapsedRealtime(); haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }, Modifier.fillMaxHeight(), enabled = TalkClock.started, description = "Reset timer") {
                Icon(Icons.Rounded.Replay, null, Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)); Text("Reset", style = Type.bodyStrong)
            }
        }
    }
}
