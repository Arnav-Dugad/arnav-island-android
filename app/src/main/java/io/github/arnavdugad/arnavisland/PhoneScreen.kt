package io.github.arnavdugad.arnavisland

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Path
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.arnavdugad.arnavisland.link.Bytes
import io.github.arnavdugad.arnavisland.link.Link
import io.github.arnavdugad.arnavisland.link.Proto
import io.github.arnavdugad.arnavisland.link.Reader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 1.6: this phone's screen in a window on a PC (island 0.24), with its taps, swipes, keys and typing coming back when
 * "Control from your PC" is on in Accessibility. Android asks each time before the screen is shared; a notification
 * with Stop shows while it is.
 */
object PhoneScreen {
    /** Which PC shows this phone's screen now (its name), or null. */
    val showing = MutableStateFlow<String?>(null)
    val failure = MutableStateFlow<String?>(null)
    /** Why a share ended early: kept, and said in the app. */
    fun fail(why: String) { failure.value = why; Hub.banners.tryEmit(Banner(Banner.Kind.Failed, why)) }
    /** Starts sharing this phone's screen with [peer]: Android's own question first. */
    fun start(context: Context, peer: String) {
        context.startActivity(Intent(context, ScreenConsentActivity::class.java).putExtra("peer", peer).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun stop(context: Context) { runCatching { context.startService(Intent(context, PhoneScreenService::class.java).setAction(PhoneScreenService.STOP)) } }
    /** Whether the PC may control this phone (the accessibility service is on). */
    fun controllable(context: Context): Boolean {
        val on = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(context, ControlService::class.java)
        return on.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
    fun openControlSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Android's "Start recording or casting?" question, then the service (which must be in the foreground before it may use the answer). */
class ScreenConsentActivity : Activity() {
    private var peer: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); Hub.init(this)
        peer = intent.getStringExtra("peer") ?: run { finish(); return }
        if (savedInstanceState == null) runCatching { startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 1) }.onFailure { finish() }
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null)
            ContextCompat.startForegroundService(this, Intent(this, PhoneScreenService::class.java).putExtra("peer", peer).putExtra("code", resultCode).putExtra("data", data))
        finish()
    }
}

/**
 * The screen's encoder and its connection. The picture is Android's own H.264 encoder fed by a virtual display, sized
 * and paced for the path: on the same Wi-Fi the phone's own pixels at 60 frames a second, directly over the internet
 * up to 1080p, through the relay a light picture at 24. The bit rate follows the PC's feedback, a turned phone restarts
 * the encoder at its new shape, and a PC that falls behind skips to the next key frame rather than lagging.
 */
class PhoneScreenService : Service() {
    companion object { const val STOP = "io.github.arnavdugad.arnavisland.SCREEN_STOP"; const val ID = 9; private const val CHANNEL = "screen" }
    private var projection: MediaProjection? = null; private var display: VirtualDisplay? = null
    @Volatile private var session: Link.ScreenSession? = null; @Volatile private var running = false
    private val handlerThread = HandlerThread("screen-callbacks").apply { start() }; private val handler = Handler(handlerThread.looper)
    @Volatile private var resized: Pair<Int, Int>? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Hub.init(this)
        if (intent?.action == STOP) { finish(); return START_NOT_STICKY }
        val peer = intent?.getStringExtra("peer"); val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION") val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data", Intent::class.java) else intent?.getParcelableExtra("data")
        val pcName = Hub.peers.value.firstOrNull { it.id == peer }?.name ?: "your PC"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Showing this phone on a PC", NotificationManager.IMPORTANCE_LOW).apply { description = "While a PC shows this phone’s screen"; setShowBadge(false) })
        runCatching {
            ServiceCompat.startForeground(this, ID, notification(pcName, "Connecting…"), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        if (peer == null || data == null || running) { if (!running) stopSelf(); return START_NOT_STICKY }
        val p = runCatching { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data) }.getOrNull() ?: run { stopSelf(); return START_NOT_STICKY }
        projection = p; running = true
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { finish() }
            override fun onCapturedContentResize(width: Int, height: Int) { android.util.Log.i("ArnavScreen", "content ${width}x$height"); resized = width to height }
        }, handler)
        thread(name = "phone-screen", isDaemon = true) {
            // Whatever goes wrong ends this share (with a word why), never the app.
            try { stream(peer, pcName, p) } catch (e: Throwable) { android.util.Log.w("ArnavScreen", "share failed", e); PhoneScreen.fail("Couldn’t show this phone on $pcName"); handler.post { if (running || display != null) finish() } }
        }
        return START_NOT_STICKY
    }

    private fun notification(pc: String, text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_island).setContentTitle("Showing this phone on $pc").setContentText(text).setOngoing(true).setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_TRANSPORT).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(PendingIntent.getActivity(this, 9, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Stop", PendingIntent.getService(this, 10, Intent(this, PhoneScreenService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)).build()
    private fun update(pc: String, text: String) = runCatching { getSystemService(NotificationManager::class.java).notify(ID, notification(pc, text)) }

    private fun finish() {
        // The last word goes on a thread of its own (this may be the main thread, which may not use the network).
        running = false; val s = session; session = null
        if (s != null) thread(name = "phone-screen-stop", isDaemon = true) { runCatching { s.send(byteArrayOf(Proto.SCREEN_STOP.toByte())) }; s.close() }
        runCatching { display?.release() }; display = null; runCatching { projection?.stop() }; projection = null
        PhoneScreen.showing.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { if (running) finish(); handlerThread.quitSafely(); super.onDestroy() }

    /** The screen's size now: the whole display, turned as it is. */
    private fun screenSize(): Pair<Int, Int> {
        resized?.let { return it }
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else { val m = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m); m.widthPixels to m.heightPixels }
    }
    /** What the PC last said (its thread writes, the encoder's reads). */
    private class PcSays { @Volatile var acked = 0; @Volatile var heard = false; @Volatile var wantKey = false; @Volatile var lastHeard = SystemClock.elapsedRealtime() }
    private data class Cap(val longest: Int, val fps: Int, val start: Int, val most: Int, val least: Int)
    private data class Size(val width: Int, val height: Int)
    /** The encoder's picture for a screen: no larger than the path allows or the encoder can do, each side a multiple of 16. */
    private fun fit(screen: Pair<Int, Int>, cap: Cap, caps: MediaCodecInfo.VideoCapabilities?): Size {
        val (sw, sh) = screen; var scale = min(1.0, cap.longest.toDouble() / max(sw, sh))
        while (true) {
            val w = max(16, (sw * scale).toInt() / 16 * 16); val h = max(16, (sh * scale).toInt() / 16 * 16)
            if (caps == null || scale < .2 || runCatching { caps.areSizeAndRateSupported(w, h, cap.fps.toDouble()) }.getOrDefault(false)) return Size(w, h)
            scale *= .85
        }
    }
    private fun encoderInfo(): MediaCodecInfo? = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        .sortedBy { if (Build.VERSION.SDK_INT >= 29 && it.isHardwareAccelerated) 0 else if (it.name.startsWith("OMX.google") || it.name.startsWith("c2.android")) 2 else 1 }.firstOrNull()
    private fun encoder(info: MediaCodecInfo, size: Size, fps: Int, bitrate: Int): MediaCodec {
        val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        // Tried richest first: encoders refuse settings they don't have (the emulator's refuses headers on each key frame,
        // which this side adds itself anyway), so each try asks for less.
        fun format(plain: Int) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate); setInteger(MediaFormat.KEY_FRAME_RATE, fps); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // A still screen still sends a few frames a second (the PC knows it's alive, and the picture sharpens).
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000)
            if (plain < 2) {
                setInteger(MediaFormat.KEY_PRIORITY, 0); setInteger(MediaFormat.KEY_LATENCY, 1)
                if (caps.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                if (Build.VERSION.SDK_INT >= 29) { setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0); setFloat("max-fps-to-encoder", fps.toFloat()) }
            }
            if (plain == 0) {
                if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                // High profile where the encoder has it: sharper text for the same bits.
                caps.profileLevels.filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }.maxByOrNull { it.level }?.let { setInteger(MediaFormat.KEY_PROFILE, it.profile); setInteger(MediaFormat.KEY_LEVEL, it.level) }
            }
        }
        var failed: Exception? = null
        for (plain in 0..2) {
            val c = MediaCodec.createByCodecName(info.name)
            try { c.configure(format(plain), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); return c } catch (e: Exception) { failed = e; runCatching { c.release() } }
        }
        throw failed ?: IllegalStateException("no encoder")
    }

    private fun stream(peer: String, pcName: String, projection: MediaProjection) {
        val link = Hub.link; val pc = Hub.peers.value.firstOrNull { it.id == peer }
        if (link == null || pc == null) { PhoneScreen.fail("Couldn’t reach $pcName"); handler.post { finish() }; return }
        val cap = when { !pc.internet -> Cap(2400, 60, 12_000_000, 24_000_000, 2_000_000); pc.path == 2 -> Cap(1920, 60, 5_000_000, 12_000_000, 800_000); else -> Cap(1280, 24, 800_000, 1_800_000, 250_000) }
        val info = encoderInfo() ?: run { PhoneScreen.fail("This phone has no video encoder"); handler.post { finish() }; return }
        val videoCaps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull()
        var screen = screenSize(); var size = fit(screen, cap, videoCaps)
        val opened = link.openScreen(peer, ScreenWire.offerPhone(size.width, size.height, cap.fps, Hub.phoneName()))
        if (opened == null) { PhoneScreen.fail(if (pc.revision < 7) "Update Arnav Island on $pcName to 0.24 or later" else "Couldn’t reach $pcName"); handler.post { finish() }; return }
        val (s, answer) = opened; session = s
        val reply = ScreenWire.reply(answer)
        if (reply == null || reply.status != 0) { PhoneScreen.fail("$pcName couldn’t show this phone"); s.close(); handler.post { finish() }; return }
        PhoneScreen.showing.value = pcName; PhoneScreen.failure.value = null
        var bitrate = cap.start; val fps = cap.fps
        val dpi = resources.displayMetrics.densityDpi
        var codec = encoder(info, size, fps, bitrate); var surface = codec.createInputSurface(); codec.start()
        display = runCatching { projection.createVirtualDisplay("Arnav Island", size.width, size.height, max(120, dpi * size.width / max(1, screen.first)), DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler) }.getOrNull()
        if (display == null) { codec.release(); handler.post { finish() }; return }
        update(pcName, "${size.width}×${size.height} · ${quality(pc).text}")
        // What the PC says: how it's keeping up, key frames it wants, taps and keys for this phone, its end.
        val pcSays = PcSays()
        thread(name = "phone-screen-in", isDaemon = true) {
            while (running && s.open) {
                val f = s.receive(500) ?: continue; if (f.isEmpty()) continue; pcSays.lastHeard = SystemClock.elapsedRealtime()
                val r = Reader(f)
                when (r.u8()) {
                    Proto.SCREEN_FEEDBACK -> { pcSays.acked = (r.u32() ?: 0L).toInt(); pcSays.heard = true }
                    Proto.SCREEN_KEYFRAME -> pcSays.wantKey = true
                    Proto.SCREEN_TOUCH -> { val a = r.u8() ?: 0; val x = r.u16() ?: 0; val y = r.u16() ?: 0; ControlService.instance?.touch(a, x, y) }
                    Proto.SCREEN_BUTTON -> ControlService.instance?.button(r.u8() ?: 0)
                    Proto.SCREEN_TEXT -> ControlService.instance?.type(String(f, 1, f.size - 1, Charsets.UTF_8))
                    Proto.SCREEN_STOP -> { android.util.Log.i("ArnavScreen", "the PC ended it"); running = false }
                }
            }
            android.util.Log.i("ArnavScreen", "the PC's side closed: open=${s.open}"); running = false
        }
        val out = MediaCodec.BufferInfo(); var config: ByteArray? = null; var number = 0; var skipping = false; var skippedAt = 0L; var lastSent = SystemClock.elapsedRealtime()
        var slowSends = 0; var lastCheck = SystemClock.elapsedRealtime(); var lastUp = lastCheck
        try {
            while (running && s.open) {
                if (SystemClock.elapsedRealtime() - pcSays.lastHeard > 8000) break
                val acked = pcSays.acked; val heard = pcSays.heard
                // A turned phone (or a shared app that changed shape): the encoder again, at the new shape.
                val now = screenSize()
                if (now != screen) {
                    screen = now; val next = fit(screen, cap, videoCaps)
                    if (next != size) {
                        size = next; runCatching { codec.stop() }; codec.release()
                        codec = encoder(info, size, fps, bitrate); val old = surface; surface = codec.createInputSurface(); codec.start()
                        display?.resize(size.width, size.height, max(120, dpi * size.width / max(1, screen.first))); display?.surface = surface; old.release()
                        s.send(Bytes().u8(Proto.SCREEN_LIMITS).u16(size.width).u16(size.height).u8(fps).build()); config = null; skipping = false
                        update(pcName, "${size.width}×${size.height} · ${quality(pc).text}")
                    }
                }
                if (pcSays.wantKey) { pcSays.wantKey = false; runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } }
                // Still here: a still screen (or an encoder waiting on its key frame) sends nothing, so a word every second
                // keeps the PC from giving up; a key frame asked for that hasn't come is asked for again.
                val tick = SystemClock.elapsedRealtime()
                if (tick - lastSent >= 1000) { if (!s.send(Bytes().u8(Proto.SCREEN_LIMITS).u16(size.width).u16(size.height).u8(fps).build())) break; lastSent = tick }
                if (skipping && tick - skippedAt >= 1000) { skippedAt = tick; runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } }
                val i = codec.dequeueOutputBuffer(out, 20_000)
                if (i < 0) continue
                val buffer = codec.getOutputBuffer(i)
                val bytes = if (buffer != null && out.size > 0) ByteArray(out.size).also { buffer.position(out.offset); buffer.get(it) } else null
                codec.releaseOutputBuffer(i, false)
                if (bytes == null) continue
                if (out.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { config = bytes; continue }
                val key = out.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                // A PC two seconds of frames behind: nothing more until a key frame, so it catches up instead of lagging.
                if (heard && number - acked > fps * 2 && !skipping) { skipping = true; skippedAt = SystemClock.elapsedRealtime(); runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } }
                if (skipping && !key) continue
                skipping = false
                val data = if (key && config != null && ScreenWire.nals(bytes).none { it.first == 7 }) config!! + bytes else bytes
                number++; val t0 = SystemClock.elapsedRealtime()
                for (f in ScreenWire.frames(number, key, out.presentationTimeUs * 10, data)) if (!s.send(f)) { running = false; break }
                lastSent = SystemClock.elapsedRealtime()
                // The bit rate follows the path: down a quarter when sends are slow or the PC falls behind, up a little after four easy seconds.
                if (SystemClock.elapsedRealtime() - t0 > 1500 / fps) slowSends++ else slowSends = max(0, slowSends - 1)
                val at = SystemClock.elapsedRealtime()
                if (at - lastCheck >= 500) {
                    lastCheck = at; val behind = heard && number - acked > fps
                    val nextRate = if (slowSends >= 3 || behind) max(cap.least, bitrate * 3 / 4).also { slowSends = 0; lastUp = at }
                        else if (at - lastUp >= 4000 && bitrate < cap.most) min(cap.most, bitrate * 115 / 100).also { lastUp = at } else bitrate
                    if (nextRate != bitrate) { bitrate = nextRate; runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) }) } }
                }
            }
            android.util.Log.i("ArnavScreen", "ended: running=$running open=${s.open} quiet=${SystemClock.elapsedRealtime() - pcSays.lastHeard} ms, frame $number")
        } catch (e: Exception) { android.util.Log.w("ArnavScreen", "encoder loop failed at frame $number", e)
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }; runCatching { surface.release() }
            handler.post { if (this.running || display != null) finish() }
        }
    }
}

/**
 * 1.6: the PC's taps, swipes, keys and typing on this phone, only while it shows this phone's screen (the service
 * does nothing otherwise). Turned on by you in Settings › Accessibility; Android says what it can do there.
 */
class ControlService : AccessibilityService() {
    companion object { @Volatile var instance: ControlService? = null; private set }
    private val main = Handler(Looper.getMainLooper())
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    private fun allowed() = PhoneScreen.showing.value != null

    private fun screen(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else { val m = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m); m.widthPixels to m.heightPixels }
    }

    // A touch as it happens: the stroke goes on as the PC's pointer moves (each part once the last is done), so a drag
    // follows live. A press without moving is a tap (or a long press, held as long as it was).
    private var downAt = 0L; private var down = PointF(); private val points = ArrayList<PointF>(); private var stroke: GestureDescription.StrokeDescription? = null
    private var busy = false; private var lifted = false; private var sentTo = 0; private var sentAt = 0L; private var broken = false
    fun touch(action: Int, x: Int, y: Int) = main.post {
        if (!allowed()) return@post
        val (w, h) = screen(); val p = PointF(x / 65535f * (w - 1), y / 65535f * (h - 1))
        when (action) {
            0 -> { downAt = SystemClock.uptimeMillis(); down = p; points.clear(); points += p; stroke = null; busy = false; lifted = false; sentTo = 0; broken = false }
            1 -> { if (points.isEmpty()) return@post; points += p; pump() }
            2 -> { if (points.isEmpty()) return@post; points += p; lifted = true; pump() }
        }
    }
    private fun pump() {
        if (busy) return
        val now = SystemClock.uptimeMillis(); val slop = 12 * resources.displayMetrics.density
        val moved = points.any { Math.hypot((it.x - down.x).toDouble(), (it.y - down.y).toDouble()) > slop }
        if (stroke == null && !moved) {
            if (!lifted) return
            // A tap, or a long press as long as it was held.
            val path = Path().apply { moveTo(down.x, down.y) }; val held = (now - downAt).coerceIn(1, 3000)
            dispatch(GestureDescription.StrokeDescription(path, 0, if (held < 400) 40 else held), true); points.clear(); return
        }
        if (broken) {
            // A stroke Android stopped: the whole path at once, when it ends.
            if (!lifted) return
            val path = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) } }
            dispatch(GestureDescription.StrokeDescription(path, 0, (now - downAt).coerceIn(1, 10_000)), true); points.clear(); return
        }
        val from = if (stroke == null) 0 else sentTo
        if (from >= points.size - 1 && !lifted) return
        val path = Path().apply { moveTo(points[from].x, points[from].y); for (i in from + 1 until points.size) lineTo(points[i].x, points[i].y); if (from >= points.size - 1) lineTo(points[from].x + .5f, points[from].y) }
        val duration = (now - (if (stroke == null) downAt else sentAt)).coerceIn(8, 500)
        val next = if (stroke == null) GestureDescription.StrokeDescription(path, 0, duration, !lifted) else stroke!!.continueStroke(path, 0, duration, !lifted)
        stroke = next; sentTo = points.size - 1; sentAt = now
        dispatch(next, lifted)
    }
    private fun dispatch(s: GestureDescription.StrokeDescription, last: Boolean) {
        busy = true
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(s).build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { busy = false; if (last) { points.clear(); stroke = null } else pump() }
            override fun onCancelled(g: GestureDescription?) { busy = false; if (!last) { broken = true; stroke = null; pump() } else { points.clear(); stroke = null } }
        }, main)
        if (!ok) { busy = false; broken = true }
    }

    fun button(key: Int) = main.post {
        if (!allowed()) return@post
        performGlobalAction(when (key) { 1 -> GLOBAL_ACTION_BACK; 2 -> GLOBAL_ACTION_HOME; 3 -> GLOBAL_ACTION_RECENTS; 4 -> GLOBAL_ACTION_NOTIFICATIONS; else -> return@post })
    }

    // A burst of typing works from what this service last put in the field: the field's own report can lag a letter or
    // two behind (each letter would otherwise rebuild it from an older copy and lose the ones before).
    private var editNode = 0; private var editText: String? = null; private var editCursor = 0; private var editAt = 0L

    /** Typing from the PC into the field that has focus here (Enter is the keyboard's action, Backspace deletes). */
    fun type(text: String) = main.post {
        if (!allowed() || text.isEmpty()) return@post
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@post
        if (!node.isEditable) return@post
        if (text == "\n" || text == "\r") {
            editText = null
            if (Build.VERSION.SDK_INT >= 30 && node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return@post
        }
        val now = SystemClock.uptimeMillis(); val same = node.hashCode() == editNode && editText != null && now - editAt < 2000
        val current = if (same) editText!! else if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) "" else node.text?.toString().orEmpty()
        var a = if (same) editCursor else node.textSelectionStart; var b = if (same) editCursor else node.textSelectionEnd
        if (a < 0 || a > current.length) a = current.length; if (b < 0 || b > current.length) b = a
        val start = min(a, b); val end = max(a, b)
        val (next, cursor) = when (text) {
            "\b" -> if (start == end) (if (start > 0) current.removeRange(start - 1, start) to start - 1 else current to 0) else current.removeRange(start, end) to start
            else -> current.replaceRange(start, end, text) to start + text.length
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next) })
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor) })
        editNode = node.hashCode(); editText = next; editCursor = cursor; editAt = now
    }
}
