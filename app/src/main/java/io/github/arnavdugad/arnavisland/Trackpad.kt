package io.github.arnavdugad.arnavisland

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
class InputLink(private val peer: String, private val internet: Boolean, private val scope: CoroutineScope) {
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
    private fun open(): Boolean { session = Hub.link?.openInput(peer); return session != null }
    private fun deliver(f: ByteArray) {
        if (session?.send(f) == true) return
        // The PC closed an idle session: once more, freshly opened.
        runCatching { session?.close() }
        if (open() && session?.send(f) == true) state.value = 1 else state.value = 2
    }
    fun move(x: Float, y: Float) = synchronized(lock) { dx += x; dy += y }
    fun scroll(vertical: Float, horizontal: Float) = synchronized(lock) { wheel += vertical; hwheel += horizontal }
    fun frame(f: ByteArray) { flush(); queue.trySend(f) }
    private fun flush() {
        val (mx, my, w, h) = synchronized(lock) {
            val mx = dx.toInt(); val my = dy.toInt(); val w = wheel.toInt(); val h = hwheel.toInt()
            dx -= mx; dy -= my; wheel -= w; hwheel -= h; listOf(mx, my, w, h)
        }
        if (mx != 0 || my != 0) queue.trySend(Link.moveFrame(mx, my))
        if (w != 0 || h != 0) queue.trySend(Link.scrollFrame(w, h))
    }
    fun key(vk: Int, vararg modifiers: Int) {
        modifiers.forEach { frame(Link.keyFrame(it, 1)) }
        frame(Link.keyFrame(vk, 2))
        modifiers.reversed().forEach { frame(Link.keyFrame(it, 0)) }
    }
    fun close() { queue.close(); runCatching { session?.close() } }
}

/**
 * The phone as the PC's trackpad and keyboard. One finger moves the pointer (faster the faster it goes), a tap clicks,
 * two fingers scroll and a two-finger tap right-clicks; a double tap held down drags. Below: the two buttons, the keys
 * a phone's keyboard lacks, and a field whose typing goes straight to the PC.
 */
@Composable fun TrackpadSheet(visible: Boolean, pc: PeerView?, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    GlassSheet(visible, onDismiss, Modifier.fillMaxHeight(.93f)) {
        if (pc == null) { Text("Pair with your PC first", style = Type.body, color = t.muted); return@GlassSheet }
        val scope = rememberCoroutineScope()
        val input = remember(pc.id) { InputLink(pc.id, pc.internet, scope).also { it.start() } }
        DisposableEffect(input) { onDispose { input.close() } }
        val state by input.state.collectAsState()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Trackpad", style = Type.title, color = t.text)
                Text(when (state) { 0 -> "Connecting to ${pc.name}…"; 1 -> "Controlling ${pc.name}${if (pc.internet) "  ·  ${if (pc.path == 2) "direct" else "through the relay"}" else ""}"
                    else -> "Couldn’t connect. On the island: Settings › Privacy & productivity › My phone can control this PC" }, style = Type.caption, color = if (state == 2) t.danger else t.muted, maxLines = 3)
            }
            LiveDot(state == 1)
        }
        Spacer(Modifier.height(14.dp))
        Pad(input, Modifier.fillMaxWidth().weight(1f))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MouseButton("Left click", Modifier.weight(1f)) { input.frame(Link.buttonFrame(0, if (it) 1 else 0)) }
            MouseButton("Right click", Modifier.weight(1f)) { input.frame(Link.buttonFrame(1, if (it) 1 else 0)) }
        }
        Spacer(Modifier.height(10.dp))
        Keys(input)
        Spacer(Modifier.height(10.dp))
        Typing(input, pc.name)
    }
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
                    } else if (down.size >= 2) {
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
                drawCircle(Brush.radialGradient(listOf(t.accent.copy(alpha = .42f), t.accent.copy(alpha = .1f), Color.Transparent), p, 46.dp.toPx()), 46.dp.toPx(), p)
                drawCircle(Color.White.copy(alpha = .5f), 9.dp.toPx(), p)
            }
            ripple?.let { (p, _) -> val a = rippleAge.value; if (a < 1f) drawCircle(t.accent.copy(alpha = (1f - a) * .6f), 12.dp.toPx() + 44.dp.toPx() * a, p, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) }
        }
        if (!used) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Mouse, null, tint = t.faint, modifier = Modifier.size(34.dp)); Spacer(Modifier.height(10.dp))
            Text("One finger moves  ·  tap to click\nTwo fingers scroll  ·  two-finger tap for right-click\nDouble-tap and hold to drag", style = Type.caption, color = t.faint, textAlign = TextAlign.Center)
        }
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
        contentAlignment = Alignment.Center) { Text(label, style = Type.caption, color = if (pressed) t.text else t.muted) }
}
private fun Modifier.graphicsLayerScale(s: Float) = this.then(Modifier.graphicsLayer { scaleX = s; scaleY = s })

/** One key of the keys row: a word, or a glyph drawn as an icon (the arrows, backspace). */
private class Key(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector? = null, val send: () -> Unit)

/** The keys a phone keyboard doesn't have, and a few shortcuts. */
@Composable private fun Keys(input: InputLink) {
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
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { k ->
            val icon = k.icon
            if (icon == null) GlassChip(k.label) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); k.send() }
            else Box(Modifier.size(46.dp, 32.dp).glass(GlassShapes.capsule, GlassLevel.Control)
                .clickable(role = androidx.compose.ui.semantics.Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); k.send() }
                .semantics { contentDescription = k.label }, contentAlignment = Alignment.Center) { Icon(icon, null, tint = t.muted, modifier = Modifier.size(20.dp)) }
        }
    }
}

/** Typing goes straight to the PC: letters as they are typed, corrections as backspaces, Enter as Enter. */
@Composable private fun Typing(input: InputLink, pcName: String) {
    val t = LocalTokens.current
    // A mark before the text: deleting it means a backspace with nothing left here.
    val mark = "​"
    var value by remember { mutableStateOf(TextFieldValue(mark, TextRange(1))) }
    Row(Modifier.fillMaxWidth().glass(GlassShapes.capsule, GlassLevel.Control).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
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
                modifier = Modifier.fillMaxWidth())
        }
    }
}
