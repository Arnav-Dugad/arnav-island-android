package io.github.arnavdugad.arnavisland

import android.content.pm.ActivityInfo
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.arnavdugad.arnavisland.link.Link
import io.github.arnavdugad.arnavisland.link.Proto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 1.6: your PC's screen here (island 0.24), as sharp and smooth as the path allows: on the same Wi-Fi up to 1440p at 60
 * frames a second, directly over the internet up to 1080p, through the relay less. Touch it like a touchscreen: a tap
 * clicks, a long press right-clicks, a drag drags, two fingers scroll, and pinching zooms in here (not on the PC).
 */
class PcScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); Hub.init(this); enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply { hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        val peer = intent.getStringExtra("peer") ?: Hub.pc()?.id
        if (peer == null) { finish(); return }
        setContent { CompositionLocalProvider(LocalTokens provides tokens(true)) { PcScreen(peer) { finish() } } }
    }
}

/** A screen session with a PC: its state, how it's keeping up, and the input that goes back on the same connection. */
class ScreenStreamer(private val peer: String, private val scope: CoroutineScope) : Typist {
    sealed interface State { data object Connecting : State; data class Showing(val width: Int, val height: Int) : State; data class Failed(val why: String) : State; data object Ended : State }
    data class Stats(val fps: Int = 0, val kbps: Int = 0, val decodeMs: Int = 0)
    val state = MutableStateFlow<State>(State.Connecting); val stats = MutableStateFlow(Stats())
    @Volatile private var session: Link.ScreenSession? = null
    private var job: Job? = null
    // Everything this side sends goes out on a thread of its own, in order (never the main thread, which may not use the network).
    private val out = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "pc-screen-out").apply { isDaemon = true } }
    private var surface: Surface? = null; private var longest = 0; private var shortest = 0
    fun start(surface: Surface, longest: Int, shortest: Int) {
        this.surface = surface; this.longest = longest; this.shortest = shortest
        job?.cancel(); state.value = State.Connecting; job = scope.launch(Dispatchers.IO) { run(surface, longest, shortest) }
    }
    /** Again, after it was lost or refused. */
    fun retry() { val s = surface ?: return; session?.close(); session = null; start(s, longest, shortest) }
    fun stop() {
        val s = session; session = null; job?.cancel()
        runCatching { out.execute { if (s != null) { runCatching { s.send(byteArrayOf(Proto.SCREEN_STOP.toByte())) }; s.close() } }; out.shutdown() }
    }

    private fun send(f: ByteArray) { val s = session ?: return; runCatching { out.execute { s.send(f) } } }
    override fun frame(f: ByteArray) = send(ScreenWire.input(f))
    override fun key(vk: Int, vararg modifiers: Int) {
        modifiers.forEach { frame(Link.keyFrame(it, 1)) }; frame(Link.keyFrame(vk, 2)); modifiers.reversed().forEach { frame(Link.keyFrame(it, 0)) }
    }
    fun point(x: Float, y: Float) = frame(Link.pointFrame(x, y))
    fun button(button: Int, state: Int) = frame(Link.buttonFrame(button, state))
    fun scroll(vertical: Int, horizontal: Int) = frame(Link.scrollFrame(vertical, horizontal))

    private suspend fun run(surface: Surface, longest: Int, shortest: Int) {
        val link = Hub.link ?: run { state.value = State.Failed("Arnav Island isn’t connected yet"); return }
        val pcName = Hub.peers.value.firstOrNull { it.id == peer }?.name ?: "Your PC"
        val opened = link.openScreen(peer, ScreenWire.askPc(longest, shortest, 60))
        if (opened == null) { state.value = State.Failed(if ((Hub.peers.value.firstOrNull { it.id == peer }?.revision ?: 0) < 7) "Update Arnav Island on $pcName to 0.24 or later" else "Couldn’t reach $pcName"); return }
        val (s, answer) = opened; session = s
        val r = ScreenWire.reply(answer)
        if (r == null || r.status != 0) {
            state.value = State.Failed(when (r?.status) { 1 -> "Turn on “My phone can control this PC” on $pcName’s island"; 2 -> "Update Arnav Island on $pcName"; else -> "$pcName couldn’t show its screen" })
            s.close(); return
        }
        state.value = State.Showing(r.width, r.height)
        val assembler = ScreenWire.Assembler(); var codec: MediaCodec? = null; var waitingKey = true
        var last = 0; var shown = 0; var bytes = 0L; var told = SystemClock.elapsedRealtime(); var askedKey = 0L; var decodeMs = 0.0; var heard = SystemClock.elapsedRealtime()
        val info = MediaCodec.BufferInfo()
        fun askKey() { val now = SystemClock.elapsedRealtime(); if (now - askedKey > 500) { askedKey = now; send(byteArrayOf(Proto.SCREEN_KEYFRAME.toByte())) } }
        fun drain(c: MediaCodec) {
            while (true) {
                val i = c.dequeueOutputBuffer(info, 0)
                if (i >= 0) { c.releaseOutputBuffer(i, true); shown++ }
                else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat; val w = f.getInteger(MediaFormat.KEY_WIDTH); val h = f.getInteger(MediaFormat.KEY_HEIGHT)
                    val cw = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1 else w; val ch = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 else h
                    state.value = State.Showing(cw, ch)
                } else break
            }
        }
        try {
            while (scope.isActive && s.open) {
                // Decoded pictures go to the screen as soon as they're ready, whether or not another frame has come.
                codec?.let { c -> try { drain(c) } catch (_: Exception) { runCatching { c.stop(); c.release() }; codec = null; waitingKey = true; askKey() } }
                val now = SystemClock.elapsedRealtime()
                if (now - told >= 500) {
                    val secs = (now - told) / 1000.0
                    send(ScreenWire.feedback(last, decodeMs.roundToInt(), (bytes * 8 / 1000 / secs).toInt(), (shown / secs).roundToInt()))
                    stats.value = Stats((shown / secs).roundToInt(), (bytes * 8 / 1000 / secs).toInt(), decodeMs.roundToInt()); told = now; shown = 0; bytes = 0
                }
                // The PC says something every second at least (frames, or a word when its screen is still).
                if (now - heard > 6000) { state.value = State.Failed("Lost ${Hub.peers.value.firstOrNull { it.id == peer }?.name ?: "your PC"}"); break }
                val f = s.receive(if (codec != null) 8 else 250) ?: continue
                if (f.isEmpty()) continue
                heard = SystemClock.elapsedRealtime()
                when (f[0].toInt() and 0xFF) {
                    Proto.SCREEN_STOP -> { state.value = State.Ended; break }
                    Proto.SCREEN_VIDEO -> {
                        bytes += f.size
                        val frame = assembler.add(f) ?: continue; last = frame.number
                        if (codec == null) {
                            if (!frame.key) { askKey(); continue }
                            val (sps, pps) = ScreenWire.parameterSets(frame.data) ?: continue
                            codec = decoder(r.width, r.height, sps, pps, surface) ?: run { state.value = State.Failed("This phone can’t decode the PC’s screen"); return }
                        }
                        if (waitingKey && !frame.key) { askKey(); continue }
                        waitingKey = false
                        val c = codec!!; val t0 = SystemClock.elapsedRealtimeNanos()
                        try {
                            val i = c.dequeueInputBuffer(30_000)
                            // A decoder that's full: this frame goes, and the next key frame starts afresh.
                            if (i < 0) { waitingKey = true; askKey(); drain(c); continue }
                            val b: ByteBuffer = c.getInputBuffer(i)!!; b.clear(); b.put(frame.data)
                            c.queueInputBuffer(i, 0, frame.data.size, frame.pts / 10, if (frame.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                            drain(c)
                        } catch (e: Exception) {
                            runCatching { c.stop(); c.release() }; codec = null; waitingKey = true; askKey(); continue
                        }
                        decodeMs = decodeMs * .8 + (SystemClock.elapsedRealtimeNanos() - t0) / 1e6 * .2
                    }
                    else -> {}
                }
            }
            if (!s.open && state.value !is State.Failed && state.value !is State.Ended) state.value = State.Failed("Lost ${Hub.peers.value.firstOrNull { it.id == peer }?.name ?: "your PC"}")
        } finally { codec?.let { runCatching { it.stop(); it.release() } }; s.close() }
    }

    private fun decoder(w: Int, h: Int, sps: ByteArray, pps: ByteArray, surface: Surface): MediaCodec? = runCatching {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        f.setByteBuffer("csd-0", ByteBuffer.wrap(sps)); f.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        // As little delay as the decoder can do.
        if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        f.setInteger(MediaFormat.KEY_PRIORITY, 0)
        MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply { configure(f, surface, null, 0); start() }
    }.getOrNull()
}

@Composable fun PcScreen(peer: String, onClose: () -> Unit) {
    val t = LocalTokens.current; val scope = rememberCoroutineScope(); val haptics = LocalHapticFeedback.current; val density = LocalDensity.current
    val streamer = remember { ScreenStreamer(peer, scope) }
    val state by streamer.state.collectAsState(); val stats by streamer.stats.collectAsState()
    val pc = Hub.peers.collectAsState().value.firstOrNull { it.id == peer }
    DisposableEffect(Unit) { onDispose { streamer.stop() } }
    var container by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }; var pan by remember { mutableStateOf(Offset.Zero) }
    var bar by remember { mutableStateOf(true) }; var touched by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var typing by remember { mutableStateOf(false) }; val focus = remember { FocusRequester() }; val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(touched, typing) { if (!typing) { delay(3500); bar = false } }
    val video = (state as? ScreenStreamer.State.Showing)
    // A PC's screen is usually wider than tall: the phone turns sideways for it (unless kept upright from the bar).
    val activity = LocalContext.current as? android.app.Activity; var sideways by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(video?.width, video?.height, sideways) {
        activity?.requestedOrientation = when {
            video == null -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            video.width >= video.height && sideways -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }
    // The picture fitted into the view; zoom and pan move it about the view's centre.
    fun contentRect(): FloatArray {
        val vw = (video?.width ?: 16).toFloat(); val vh = (video?.height ?: 9).toFloat(); val cw = container.width.toFloat(); val ch = container.height.toFloat()
        val s = min(cw / vw, ch / vh); val w = vw * s; val h = vh * s; return floatArrayOf((cw - w) / 2, (ch - h) / 2, w, h)
    }
    fun toScreen(p: Offset): Offset {
        val r = contentRect(); val c = Offset(container.width / 2f, container.height / 2f); val q = (p - pan - c) / zoom + c
        return Offset(((q.x - r[0]) / r[2]).coerceIn(0f, 1f), ((q.y - r[1]) / r[3]).coerceIn(0f, 1f))
    }
    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { container = it }
        .pointerInput(video) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                // Touches the bar, its handle or the keys took aren't the PC's.
                val down = awaitFirstDown(); touched = SystemClock.elapsedRealtime()
                val start = down.position; val t0 = SystemClock.elapsedRealtime(); var mode = 0 // 0 maybe a tap, 1 dragging, 2 two fingers, 3 long pressed
                var spanStart = 0f; var zoomStart = zoom; var centroidLast = Offset.Zero; var wheel = 0f; var zooming = false; var panStart = pan; var centroidStart = Offset.Zero
                while (true) {
                    val event = try { withTimeout(if (mode == 0) 450L else Long.MAX_VALUE) { awaitPointerEvent() } } catch (_: PointerEventTimeoutCancellationException) {
                        // Held still: a right click.
                        if (mode == 0) { mode = 3; val p = toScreen(start); streamer.point(p.x, p.y); streamer.button(1, 2); haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                        continue
                    }
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) {
                        if (mode == 0 && SystemClock.elapsedRealtime() - t0 < 450) { val p = toScreen(start); streamer.point(p.x, p.y); streamer.button(0, 2); haptics.performHapticFeedback(HapticFeedbackType.ContextClick) }
                        if (mode == 1) { val p = toScreen(event.changes.first().position); streamer.point(p.x, p.y); streamer.button(0, 0) }
                        break
                    }
                    if (pressed.size >= 2) {
                        val a = pressed[0].position; val b = pressed[1].position; val span = hypot(a.x - b.x, a.y - b.y); val centroid = (a + b) / 2f
                        if (mode != 2) { if (mode == 1) streamer.button(0, 0); mode = 2; spanStart = span; zoomStart = zoom; centroidLast = centroid; centroidStart = centroid; panStart = pan }
                        // Spreading or pinching zooms the picture here; moving together scrolls on the PC (or pans a zoomed picture).
                        if (!zooming && abs(span / spanStart - 1f) > .08f) zooming = true
                        if (zooming) { zoom = (zoomStart * span / spanStart).coerceIn(1f, 4f); pan = if (zoom <= 1.01f) Offset.Zero else panStart + (centroid - centroidStart) }
                        else if (zoom > 1.01f) pan = panStart + (centroid - centroidStart)
                        else { wheel += (centroid.y - centroidLast.y) * 3; val w = (wheel / 40).toInt(); if (w != 0) { streamer.scroll(w * 40, 0); wheel -= w * 40 } }
                        centroidLast = centroid; event.changes.forEach { it.consume() }; continue
                    }
                    val p = pressed[0].position
                    if (mode == 0 && (p - start).getDistance() > slop) { mode = 1; val s0 = toScreen(start); streamer.point(s0.x, s0.y); streamer.button(0, 1) }
                    if (mode == 1) { val s1 = toScreen(p); streamer.point(s1.x, s1.y) }
                    event.changes.forEach { it.consume() }
                }
            }
        }) {
        // The picture: a TextureView the decoder draws into.
        val rect = contentRect()
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }) {
            if (container.width > 0) AndroidView(factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            val m = ctx.resources.displayMetrics; streamer.start(Surface(st), max(m.widthPixels, m.heightPixels), min(m.widthPixels, m.heightPixels))
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { streamer.stop(); return true }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            }, modifier = with(density) { Modifier.offset(rect[0].toDp(), rect[1].toDp()).size(rect[2].toDp(), rect[3].toDp()) })
        }
        // Connecting, or why it isn't showing.
        when (val s = state) {
            is ScreenStreamer.State.Connecting -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Radar(t.accent, Modifier.size(56.dp)); Spacer(Modifier.height(12.dp)); Text("Opening ${pc?.name ?: "your PC"}’s screen…", style = Type.body, color = t.text) }
            is ScreenStreamer.State.Failed -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.DesktopAccessDisabled, null, tint = t.warn, modifier = Modifier.size(44.dp)); Spacer(Modifier.height(12.dp))
                Text(s.why, style = Type.headline, color = t.text, textAlign = TextAlign.Center); Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassButton(onClose) { Text("Close", style = Type.bodyStrong) }
                    GlassButton({ streamer.retry() }, prominent = true) { Text("Try again", style = Type.bodyStrong) }
                } }
            is ScreenStreamer.State.Ended -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${pc?.name ?: "Your PC"} stopped showing its screen", style = Type.headline, color = t.text); Spacer(Modifier.height(16.dp)); GlassButton(onClose) { Text("Close", style = Type.bodyStrong) } }
            else -> {}
        }
        // The top: how it's reached, the keyboard, and back.
        AnimatedVisibility(bar || state !is ScreenStreamer.State.Showing, Modifier.align(Alignment.TopCenter), enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
            Row(Modifier.statusBarsPadding().padding(12.dp).widthIn(max = 720.dp).fillMaxWidth().glass(GlassShapes.capsule, GlassLevel.Bar).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Close", onClose, size = 40.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(pc?.name ?: "Your PC", style = Type.bodyStrong, color = t.text, maxLines = 1)
                    val how = pc?.let { quality(it).text } ?: ""
                    Text(listOfNotNull(how.ifEmpty { null }, video?.let { "${it.width}×${it.height}" }, if (stats.fps > 0) "${stats.fps} fps" else null, if (stats.kbps > 0) "%.1f Mbps".format(stats.kbps / 1000.0) else null).joinToString("  ·  "),
                        style = Type.caption, color = t.muted, maxLines = 1)
                }
                if (zoom > 1.01f) GlassIconButton(Icons.Rounded.ZoomOutMap, "Fit the screen", { zoom = 1f; pan = Offset.Zero }, size = 40.dp)
                Spacer(Modifier.width(6.dp))
                if (video != null && video.width >= video.height) {
                    GlassIconButton(Icons.Rounded.ScreenRotation, if (sideways) "Keep upright" else "Turn sideways", { sideways = !sideways; zoom = 1f; pan = Offset.Zero; touched = SystemClock.elapsedRealtime() }, size = 40.dp)
                    Spacer(Modifier.width(6.dp))
                }
                GlassIconButton(Icons.Rounded.Keyboard, if (typing) "Hide the keyboard" else "Type", { typing = !typing; touched = SystemClock.elapsedRealtime() }, size = 40.dp, prominent = typing)
            }
        }
        // A handle at the top brings the bar back.
        if (!bar && state is ScreenStreamer.State.Showing) Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 4.dp).size(64.dp, 22.dp)
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume(); bar = true; touched = SystemClock.elapsedRealtime() } }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp, 5.dp).background(Color.White.copy(alpha = .35f), GlassShapes.capsule))
        }
        // Typing: the keys a phone keyboard lacks, and a field whose typing goes straight to the PC.
        if (typing) {
            LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() }; keyboard?.show() }
            Column(Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(12.dp).widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Keys(streamer); Typing(streamer, pc?.name ?: "your PC", focus = focus)
            }
        }
    }
}
