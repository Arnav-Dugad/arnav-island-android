package io.github.arnavdugad.arnavisland

import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.arnavisland.link.Link
import io.github.arnavdugad.arnavisland.link.PeerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** Windows virtual-key codes the keys row sends. */
object Vk {
    const val BACK = 0x08; const val TAB = 0x09; const val RETURN = 0x0D; const val SHIFT = 0x10; const val CONTROL = 0x11; const val ALT = 0x12
    const val ESCAPE = 0x1B; const val PRIOR = 0x21; const val NEXT = 0x22; const val END = 0x23; const val HOME = 0x24
    const val LEFT = 0x25; const val UP = 0x26; const val RIGHT = 0x27; const val DOWN = 0x28; const val DELETE = 0x2E; const val WIN = 0x5B
}

/** What typing changed in the field: the backspaces to send, then the text to type (autocorrect is a few of each). */
fun typedDiff(old: String, new: String): Pair<Int, String> {
    var p = 0; val n = min(old.length, new.length)
    while (p < n && old[p] == new[p]) p++
    return (old.length - p) to new.substring(p)
}

/**
 * A live trackpad-and-keyboard session with a PC (revision 3). Moves and scrolls are gathered and sent about 80 times a
 * second (30 over the internet); clicks and keys go at once, in order after the movement before them. A session the PC
 * closed (idle for two minutes) is opened again for the next frame.
 */
/** 1.6: where keys and typing go: the trackpad's session, or the PC's screen shown here. */
interface Typist { fun key(vk: Int, vararg modifiers: Int); fun frame(f: ByteArray) }

class InputLink(private val peer: String, private val internet: Boolean, private val scope: CoroutineScope) : Typist {
    /** 0 connecting, 1 ready, 2 couldn't connect. */
    val state = MutableStateFlow(0)
    private val queue = Channel<ByteArray>(Channel.UNLIMITED)
    private var session: Link.InputSession? = null
    private var dx = 0f; private var dy = 0f; private var wheel = 0f; private var hwheel = 0f
    private val lock = Any()
    @OptIn(ExperimentalCoroutinesApi::class) private val one = Dispatchers.IO.limitedParallelism(1)

    fun start() {
        scope.launch(one) { state.value = if (open()) 1 else 2; for (f in queue) deliver(f) }
        scope.launch { while (isActive) { delay(if (internet) 33 else 12); flush() } }
    }
    private fun open(): Boolean { session = runCatching { Hub.link?.openInput(peer) }.getOrNull(); return session != null }
    private fun deliver(f: ByteArray) {
        if (session?.send(f) == true) return
        // The PC closed an idle session: once more, freshly opened.
        runCatching { session?.close() }
        if (open() && session?.send(f) == true) state.value = 1 else state.value = 2
    }
    fun move(x: Float, y: Float) = synchronized(lock) { dx += x; dy += y }
    fun scroll(vertical: Float, horizontal: Float) = synchronized(lock) { wheel += vertical; hwheel += horizontal }
    override fun frame(f: ByteArray) { flush(); queue.trySend(f) }
    private fun flush() {
        val (mx, my, w, h) = synchronized(lock) {
            val mx = dx.toInt(); val my = dy.toInt(); val w = wheel.toInt(); val h = hwheel.toInt()
            dx -= mx; dy -= my; wheel -= w; hwheel -= h; listOf(mx, my, w, h)
        }
        if (mx != 0 || my != 0) queue.trySend(Link.moveFrame(mx, my))
        if (w != 0 || h != 0) queue.trySend(Link.scrollFrame(w, h))
    }
    override fun key(vk: Int, vararg modifiers: Int) {
        modifiers.forEach { frame(Link.keyFrame(it, 1)) }
        frame(Link.keyFrame(vk, 2))
        modifiers.reversed().forEach { frame(Link.keyFrame(it, 0)) }
    }
    fun close() { queue.close(); runCatching { session?.close() } }
}

/** Where the trackpad sheet opens: the pad, the air mouse, or the keyboard. */
const val TRACKPAD_PAD = 0; const val TRACKPAD_AIR = 1; const val TRACKPAD_KEYS = 2

/**
 * The phone as the PC's trackpad, air mouse and keyboard, one at a time under a segmented control ([start] picks the
 * first). Trackpad: one finger moves the pointer (faster the faster it goes), a tap clicks, two fingers scroll and a
 * two-finger tap right-clicks; a double tap held down drags; below, the two buttons, the keys a phone's keyboard lacks,
 * and a field whose typing goes straight to the PC. Air mouse: point the phone while holding the clutch. Keyboard: the
 * field, focused, and every key.
 */
@Composable fun TrackpadSheet(visible: Boolean, pc: PeerView?, start: Int = TRACKPAD_PAD, onDismiss: () -> Unit) {
    val t = LocalTokens.current; val reduced = LocalReduced.current
    var mode by remember { mutableIntStateOf(start) }
    LaunchedEffect(visible, start) { if (visible) mode = start }
    GlassSheet(visible, onDismiss, Modifier.fillMaxHeight(.93f)) {
        if (pc == null) { Text("Pair with your PC first", style = Type.body, color = t.muted); return@GlassSheet }
        val scope = rememberCoroutineScope()
        val input = remember(pc.id) { InputLink(pc.id, pc.internet, scope).also { it.start() } }
        DisposableEffect(input) { onDispose { input.close() } }
        val state by input.state.collectAsState()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(when (mode) { TRACKPAD_AIR -> "Air mouse"; TRACKPAD_KEYS -> "Keyboard"; else -> "Trackpad" }, style = Type.title, color = t.text)
                Text(when (state) { 0 -> "Connecting to ${pc.name}…"; 1 -> "Controlling ${pc.name}"
                    else -> "Couldn’t connect. Turn on “My phone can control this PC” in the island’s Settings." }, style = Type.caption, color = if (state == 2) t.danger else t.muted, maxLines = 3)
            }
            LiveDot(state == 1)
        }
        Spacer(Modifier.height(12.dp))
        Segmented(listOf("Trackpad", "Air mouse", "Keyboard"), mode, { mode = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        AnimatedContent(mode, Modifier.fillMaxWidth().weight(1f), transitionSpec = {
            if (reduced) EnterTransition.None togetherWith ExitTransition.None
            else { val way = if (targetState > initialState) 1 else -1
                (fadeIn(tween(220, 60)) + slideInHorizontally(spring(dampingRatio = .86f, stiffness = 420f)) { it / 8 * way }) togetherWith (fadeOut(tween(120)) + slideOutHorizontally(tween(180)) { -it / 8 * way }) }
        }, label = "Mode") { m ->
            Column(Modifier.fillMaxSize()) {
                when (m) {
                    TRACKPAD_AIR -> AirPad(input, visible)
                    TRACKPAD_KEYS -> KeyboardPane(input, pc.name)
                    else -> {
                        Pad(input, Modifier.fillMaxWidth().weight(1f))
                        Spacer(Modifier.height(12.dp))
                        ClickButtons(input)
                        Spacer(Modifier.height(12.dp))
                        Keys(input)
                        Spacer(Modifier.height(12.dp))
                        Typing(input, pc.name)
                    }
                }
            }
        }
    }
}


/** Left and right click, held while the finger is on them. */
@Composable private fun ClickButtons(input: InputLink) {
    Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MouseButton("Left click", Modifier.weight(1f)) { input.frame(Link.buttonFrame(0, if (it) 1 else 0)) }
        MouseButton("Right click", Modifier.weight(1f)) { input.frame(Link.buttonFrame(1, if (it) 1 else 0)) }
    }
}

/**
 * The air mouse: a big clutch (the pointer follows the phone only while it is held, so you can reset your wrist like
 * lifting a mouse), a strip to scroll beside it, the two buttons, and its sensitivity. Its sensors listen only while
 * this shows. 1.9 (#36): flicks of the wrist click (a word in the clutch and a tap say which), and Calibrate learns the
 * gyroscope's drift and the speed for your sweep, in place of the pad.
 */
@Composable private fun ColumnScope.AirPad(input: InputLink, visible: Boolean) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current; val reduced = LocalReduced.current
    var held by remember { mutableStateOf(false) }
    var calibrating by remember { mutableStateOf(false) }
    var flicks by remember { mutableStateOf(runCatching { Hub.prefs.getBoolean(AirMouse.PREF_FLICKS, true) }.getOrDefault(true)) }
    // What the last flick did, said in the clutch for a moment.
    var flicked by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(flicked) { if (flicked != null) { delay(900); flicked = null } }
    // Where the clutch's light sits: it trails the pointer's motion and springs back home when let go.
    var dot by remember { mutableStateOf(Offset.Zero) }
    val mouse = rememberAirMouse(visible, engaged = held && !calibrating, flicks = flicks && !calibrating, onGesture = { g ->
        input.frame(Link.buttonFrame(if (g == AirMouse.TWIST) 1 else 0, 2))
        haptics.performHapticFeedback(when (g) { AirMouse.TWIST -> HapticFeedbackType.ContextClick; AirMouse.DOUBLE_FLICK -> HapticFeedbackType.LongPress; else -> HapticFeedbackType.SegmentTick })
        flicked = when (g) { AirMouse.TWIST -> "Right click"; AirMouse.DOUBLE_FLICK -> "Double click"; else -> "Click" } to System.nanoTime()
    }) { x, y -> input.move(x, y); if (!reduced) dot = Offset((dot.x * .94f + x * .5f).coerceIn(-90f, 90f), (dot.y * .94f + y * .5f).coerceIn(-90f, 90f)) }
    var sensitivity by remember { mutableFloatStateOf(mouse.sensitivity) }
    LaunchedEffect(held) { if (!held && dot != Offset.Zero) { val from = dot; animate(1f, 0f, animationSpec = spring(dampingRatio = .55f, stiffness = 300f)) { v, _ -> dot = from * v } } }
    if (!mouse.available) {
        Box(Modifier.fillMaxWidth().weight(1f).glass(GlassShapes.card, GlassLevel.Control).padding(24.dp), contentAlignment = Alignment.Center) {
            Text("No gyroscope on this phone", style = Type.body, color = t.muted, textAlign = TextAlign.Center)
        }
        return
    }
    if (calibrating) { AirCalibration(mouse) { s -> calibrating = false; if (s != null) sensitivity = s }; return }
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val glow by springy(if (held) 1f else 0f, .7f, 420f)
        val scale by springy(if (held) .985f else 1f, .55f, 600f)
        Box(Modifier.weight(1f).fillMaxHeight().graphicsLayerScale(scale).glass(GlassShapes.card, GlassLevel.Control, if (held) t.accent else Color.Unspecified)
            .semantics { contentDescription = "Hold to move the pointer" }
            .whileHeld { h ->
                if (h != held) { held = h; haptics.performHapticFeedback(if (h) HapticFeedbackType.LongPress else HapticFeedbackType.SegmentTick) }
            }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = center + Offset(dot.x.dp.toPx(), dot.y.dp.toPx()); val r = (70f + 40f * glow).dp.toPx()
                drawCircle(Brush.radialGradient(listOf(t.accent.copy(alpha = .1f + .32f * glow), Color.Transparent), c, r), r, c)
                if (glow > .01f) drawCircle(Color.White.copy(alpha = .55f * glow), 7.dp.toPx(), c)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                Icon(Icons.Rounded.TouchApp, null, tint = if (held) t.text else t.faint, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(10.dp))
                val said = flicked?.first
                Text(said ?: if (held) "Moving" else "Hold to move", style = Type.headline, color = if (held || said != null) t.text else t.muted)
            }
        }
        ScrollStrip(input, Modifier.width(60.dp).fillMaxHeight())
    }
    Spacer(Modifier.height(12.dp))
    ClickButtons(input)
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().glass(GlassShapes.tile, GlassLevel.Control)) {
        GlassRow(Icons.Rounded.Speed, "Sensitivity", trailing = { Text("${(sensitivity * 100).roundToInt()}%", style = Type.body.copy(fontFeatureSettings = "tnum"), color = t.muted) })
        GlassSlider(sensitivity, { sensitivity = it; mouse.sensitivity = it }, { Hub.prefs.edit().putFloat(AirMouse.PREF, it).apply() },
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp), description = "Pointer sensitivity")
        Hairline()
        GlassRow(Icons.Rounded.Gesture, "Flick to click", tint = t.purple, trailing = {
            GlassSwitch(flicks, { flicks = it; Hub.prefs.edit().putBoolean(AirMouse.PREF_FLICKS, it).apply() })
        })
        Hairline()
        GlassRow(Icons.Rounded.Tune, "Calibrate", tint = t.teal, onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); held = false; calibrating = true }, trailing = {
            GlassChip("Recenter", icon = Icons.Rounded.CenterFocusStrong) { mouse.recenter() }
        })
    }
}

/** A strip to scroll by dragging along it, with ridges that roll under the finger and a tick every wheel notch. */
@Composable private fun ScrollStrip(input: InputLink, modifier: Modifier) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current; val density = LocalDensity.current
    var rolled by remember { mutableFloatStateOf(0f) }
    var active by remember { mutableStateOf(false) }
    Box(modifier.glass(GlassShapes.capsule, GlassLevel.Control, if (active) t.accent else Color.Unspecified)
        .semantics { contentDescription = "Scroll" }
        .pointerInput(input) {
            val dp = density.density; var notch = 0f
            detectVerticalDragGestures(onDragStart = { active = true; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }, onDragEnd = { active = false }, onDragCancel = { active = false }) { c, dy ->
                // The content follows the finger, as on the pad: 120 a notch every 20 dp.
                c.consume(); val wheel = dy / dp * 6f; input.scroll(wheel, 0f); rolled += dy
                notch += abs(wheel); if (notch >= 120f) { notch -= 120f; haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }
            }
        }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(vertical = 34.dp)) {
            val gap = 12.dp.toPx(); val w = 18.dp.toPx(); val h = size.height
            var y = ((rolled % gap) + gap) % gap
            while (y < h) {
                val edge = (minOf(y, h - y) / (h * .25f)).coerceIn(0f, 1f)
                drawLine(t.muted.copy(alpha = (.15f + .35f * (if (active) 1f else .6f)) * edge), Offset(center.x - w / 2, y), Offset(center.x + w / 2, y), 1.6.dp.toPx(), StrokeCap.Round)
                y += gap
            }
        }
        Icon(Icons.Rounded.KeyboardArrowUp, null, tint = t.faint, modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(20.dp))
        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = t.faint, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).size(20.dp))
    }
}

/** The keyboard: the field, focused so the phone's keyboard rises, and every key the phone's keyboard lacks. */
@Composable private fun ColumnScope.KeyboardPane(input: InputLink, pcName: String) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(280); runCatching { focus.requestFocus() } }
    Typing(input, pcName, focus = focus)
    SectionLabel("Keys")
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) { Keys(input, wrap = true) }
}

@Composable private fun Pad(input: InputLink, modifier: Modifier) {
    val t = LocalTokens.current; val density = LocalDensity.current; val haptics = LocalHapticFeedback.current
    // The fingers on the pad, for their glow; and a ripple where a click landed.
    val touches = remember { mutableStateMapOf<Long, Offset>() }
    var ripple by remember { mutableStateOf<Pair<Offset, Long>?>(null) }
    var used by remember { mutableStateOf(false) }
    val rippleAge = remember { Animatable(1f) }
    LaunchedEffect(ripple) { if (ripple != null) { rippleAge.snapTo(0f); rippleAge.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) } }
    Box(modifier.glass(GlassShapes.card, GlassLevel.Control)
        .semantics { contentDescription = "Trackpad" }
        .pointerInput(input) {
            val dp = density.density; val slop = 9f * dp
            var lastTap = 0L; var lastTapAt = Offset.Zero
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false); used = true
                val downAt = first.uptimeMillis
                // A tap, then down again at the same place: held, it drags.
                val holding = downAt - lastTap < 300 && (first.position - lastTapAt).getDistance() < 48f * dp
                if (holding) { input.frame(Link.buttonFrame(0, 1)); haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                var most = 1; var travelled = 0f; var lastTime = downAt; var lastEvent = downAt
                // 1.11: three or four fingers swipe as on a Windows touchpad, once per swipe.
                var swipe = Offset.Zero; var swiped = false
                touches[first.id.value] = first.position
                while (true) {
                    val event = awaitPointerEvent()
                    val down = event.changes.filter { it.pressed }; lastEvent = event.changes.maxOf { it.uptimeMillis }
                    event.changes.forEach { c -> if (c.pressed) touches[c.id.value] = c.position else touches.remove(c.id.value) }
                    if (down.isEmpty()) break
                    most = max(most, down.size)
                    if (down.size == 1 && most == 1) {
                        val c = down[0]; val d = c.positionChange(); val ms = (c.uptimeMillis - lastTime).coerceAtLeast(1); lastTime = c.uptimeMillis
                        travelled += d.getDistance()
                        // Pointer acceleration: slow moves are precise, quick ones cross the screen.
                        val speed = d.getDistance() / dp / ms
                        val gain = 1.6f * (1f + min(speed * 1.4f, 2.6f))
                        input.move(d.x / dp * gain, d.y / dp * gain); c.consume()
                    } else if (down.size >= 3) {
                        swipe += down.map { it.positionChange() }.fold(Offset.Zero) { a, b -> a + b } / down.size.toFloat(); down.forEach { it.consume() }
                        if (!swiped && swipe.getDistance() > 56f * dp) { swiped = true; travelled = slop * 2; haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate); fingerSwipe(input, swipe, most) }
                    } else if (down.size >= 2 && most < 3) {
                        // Two fingers: the content follows them (a finger up scrolls down), 120 a notch every 20 dp.
                        val d = down.map { it.positionChange() }.fold(Offset.Zero) { a, b -> a + b } / down.size.toFloat()
                        travelled += d.getDistance()
                        input.scroll(d.y / dp * 6f, -d.x / dp * 6f); down.forEach { it.consume() }
                    }
                }
                touches.clear()
                val up = lastEvent; val quick = up - downAt < 280
                if (holding) input.frame(Link.buttonFrame(0, 0))
                else if (travelled < slop && quick) {
                    if (most >= 2) { input.frame(Link.buttonFrame(1, 2)); haptics.performHapticFeedback(HapticFeedbackType.ContextClick) }
                    else { input.frame(Link.buttonFrame(0, 2)); haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); lastTap = up; lastTapAt = first.position }
                    ripple = first.position to up
                }
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            touches.values.forEach { p ->
                drawCircle(t.accent.copy(alpha = .22f), 26.dp.toPx(), p)
                drawCircle(t.accent.copy(alpha = .9f), 8.dp.toPx(), p)
            }
            ripple?.let { (p, _) -> val a = rippleAge.value; if (a < 1f) drawCircle(t.accent.copy(alpha = (1f - a) * .6f), 12.dp.toPx() + 44.dp.toPx() * a, p, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) }
        }
        if (!used) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Mouse, null, tint = t.faint, modifier = Modifier.size(34.dp)); Spacer(Modifier.height(10.dp))
            Text("Tap to click  ·  two fingers scroll  ·  three switch desktops", style = Type.caption, color = t.faint, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Windows' touchpad swipes: three fingers left or right move to the next or previous desktop (the content follows the
 * fingers, as there), up opens Task View, down shows the desktop; four fingers any way show the desktop.
 */
private fun fingerSwipe(input: InputLink, d: Offset, fingers: Int) {
    val sideways = kotlin.math.abs(d.x) > kotlin.math.abs(d.y)
    when {
        fingers >= 4 -> input.key(0x44, Vk.WIN)
        sideways -> input.key(if (d.x < 0) Vk.RIGHT else Vk.LEFT, Vk.CONTROL, Vk.WIN)
        d.y < 0 -> input.key(Vk.TAB, Vk.WIN)
        else -> input.key(0x44, Vk.WIN)
    }
}

/** A mouse button: pressed while the finger is on it (so it can hold a drag). */
@Composable private fun MouseButton(label: String, modifier: Modifier, onState: (Boolean) -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) .95f else 1f, spring(dampingRatio = .5f, stiffness = 700f), label = "Button")
    Box(modifier.fillMaxHeight().graphicsLayerScale(scale).glass(GlassShapes.inner, GlassLevel.Control, if (pressed) t.accent else Color.Unspecified)
        .semantics { contentDescription = label }
        .pointerInput(Unit) { detectTapGestures(onPress = { pressed = true; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onState(true); tryAwaitRelease(); pressed = false; onState(false) }) },
        contentAlignment = Alignment.Center) { Text(label, style = Type.bodyStrong, color = if (pressed) t.text else t.muted) }
}
private fun Modifier.graphicsLayerScale(s: Float) = this.then(Modifier.graphicsLayer { scaleX = s; scaleY = s })

/** One key of the keys row: a word, or a glyph drawn as an icon (the arrows, backspace). */
private class Key(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector? = null, val send: () -> Unit)

/** The keys a phone keyboard doesn't have, and a few shortcuts. */
@Composable fun Keys(input: Typist, wrap: Boolean = false) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    val keys = listOf(
        Key("Esc") { input.key(Vk.ESCAPE) }, Key("Tab") { input.key(Vk.TAB) },
        Key("Left", Icons.AutoMirrored.Rounded.KeyboardArrowLeft) { input.key(Vk.LEFT) }, Key("Up", Icons.Rounded.KeyboardArrowUp) { input.key(Vk.UP) },
        Key("Down", Icons.Rounded.KeyboardArrowDown) { input.key(Vk.DOWN) }, Key("Right", Icons.AutoMirrored.Rounded.KeyboardArrowRight) { input.key(Vk.RIGHT) },
        Key("Backspace", Icons.AutoMirrored.Rounded.Backspace) { input.key(Vk.BACK) }, Key("Del") { input.key(Vk.DELETE) },
        Key("Enter") { input.key(Vk.RETURN) }, Key("Start") { input.key(Vk.WIN) }, Key("Switch app") { input.key(Vk.TAB, Vk.ALT) },
        Key("Copy") { input.key(0x43, Vk.CONTROL) }, Key("Paste") { input.key(0x56, Vk.CONTROL) }, Key("Undo") { input.key(0x5A, Vk.CONTROL) },
        Key("Desktop") { input.key(0x44, Vk.WIN) }, Key("Home") { input.key(Vk.HOME) }, Key("End") { input.key(Vk.END) },
        Key("Page up") { input.key(Vk.PRIOR) }, Key("Page down") { input.key(Vk.NEXT) },
    )
    val key: @Composable (Key) -> Unit = { k ->
        val icon = k.icon
        if (icon == null) GlassChip(k.label, if (wrap) Modifier.heightIn(min = 40.dp) else Modifier) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); k.send() }
        else Box(Modifier.size(if (wrap) 52.dp else 46.dp, if (wrap) 40.dp else 32.dp).glass(GlassShapes.capsule, GlassLevel.Control)
            .clickable(role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); k.send() }
            .semantics { contentDescription = k.label }, contentAlignment = Alignment.Center) { Icon(icon, null, tint = t.muted, modifier = Modifier.size(20.dp)) }
    }
    // 1.8: wrapped into rows on the keyboard page, a row that scrolls sideways elsewhere.
    if (wrap) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) { keys.forEach { key(it) } }
    else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { keys.forEach { key(it) } }
}

/** Typing goes straight to the PC: letters as they are typed, corrections as backspaces, Enter as Enter. */
@Composable fun Typing(input: Typist, pcName: String, modifier: Modifier = Modifier, focus: androidx.compose.ui.focus.FocusRequester? = null) {
    val t = LocalTokens.current
    // A mark before the text: deleting it means a backspace with nothing left here.
    val mark = "​"
    var value by remember { mutableStateOf(TextFieldValue(mark, TextRange(1))) }
    Row(modifier.fillMaxWidth().glass(GlassShapes.capsule, GlassLevel.Control).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Keyboard, null, tint = t.muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.text.length <= 1) Text("Type on $pcName", style = Type.body, color = t.faint)
            BasicTextField(value, { next ->
                val old = value.text.removePrefix(mark); val lostMark = !next.text.startsWith(mark)
                val now = next.text.removePrefix(mark)
                val (back, typed) = typedDiff(old, now)
                repeat(back + if (lostMark) 1 else 0) { input.key(Vk.BACK) }
                if (typed.isNotEmpty()) typed.split('\n').forEachIndexed { i, part -> if (i > 0) input.key(Vk.RETURN); if (part.isNotEmpty()) input.frame(Link.textFrame(part)) }
                // What is kept here stays short; the PC has the rest.
                val kept = if (now.length > 400) "" else now
                value = TextFieldValue(mark + kept, TextRange(mark.length + kept.length))
            }, singleLine = true, textStyle = Type.body.copy(color = t.text), cursorBrush = SolidColor(t.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { input.key(Vk.RETURN); value = TextFieldValue(mark, TextRange(1)) }),
                modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier))
        }
    }
}
