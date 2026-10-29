package io.github.arnavdugad.arnavisland

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.arnavdugad.arnavisland.link.Bytes
import io.github.arnavdugad.arnavisland.link.Link
import io.github.arnavdugad.arnavisland.link.Proto
import io.github.arnavdugad.arnavisland.link.Reader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 1.8 (#67): this phone as a PC's webcam (island 0.26, revision 9). The camera draws into Android's own H.264 encoder
 * through a small GL step that turns the picture upright however the phone is held, and the frames go to the PC on a
 * mode V connection just as this phone's screen does. A notification with Flip and Stop shows while it's on; it carries
 * on with the app in the background, and ends when you stop it, the PC does, or the connection goes.
 */
object PhoneCamera {
    internal val shown = MutableStateFlow<String?>(null)
    internal val asking = MutableStateFlow(false)
    internal val lens = MutableStateFlow(CameraCharacteristics.LENS_FACING_FRONT)
    internal val lit = MutableStateFlow(false)
    internal val lightable = MutableStateFlow(false)
    /** Which PC uses this camera now (its name), or null. */
    val showing: StateFlow<String?> get() = shown
    /** Between turning it on and the PC's answer. */
    val connecting: StateFlow<Boolean> get() = asking
    /** The lens in use, or used next: CameraCharacteristics.LENS_FACING_FRONT (the default) or LENS_FACING_BACK. */
    val facing: StateFlow<Int> get() = lens
    /** Whether the lens in use has a light, and whether it's on. */
    val hasTorch: StateFlow<Boolean> get() = lightable
    val torch: StateFlow<Boolean> get() = lit

    /** Starts this camera on [peer]; a banner says why when it can't (no permission, an older island, no PC). */
    fun start(context: Context, peer: String) {
        val pc = Hub.peers.value.firstOrNull { it.id == peer }; val name = pc?.name ?: "your PC"
        if (shown.value != null || asking.value) return
        if (pc != null && pc.revision < CamWire.REVISION) return CamWire.fail("Update Arnav Island on $name", "Its camera needs version 0.26 or later")
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return CamWire.fail("This phone has no camera")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            return CamWire.fail("Camera not allowed", "Allow Arnav Island to use the camera in Settings › Apps")
        if (pc == null || !pc.online || Hub.link == null) return CamWire.fail("Couldn’t reach $name")
        asking.value = true; lit.value = false
        runCatching { ContextCompat.startForegroundService(context, Intent(context, PhoneCameraService::class.java).putExtra("peer", peer)) }
            .onFailure { asking.value = false; CamWire.fail("Couldn’t turn on the camera", it.message.orEmpty()) }
    }
    fun stop(context: Context) { runCatching { context.startService(Intent(context, PhoneCameraService::class.java).setAction(PhoneCameraService.STOP)) } }
    /** The other lens: now if the camera is on, else next time. */
    fun flip(context: Context) {
        lens.value = if (lens.value == CameraCharacteristics.LENS_FACING_FRONT) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        PhoneCameraService.instance?.flipWanted = true
    }
    /** The lens's light (a back lens with a flash), while the camera is on. */
    fun setTorch(on: Boolean) { lit.value = on && lightable.value; PhoneCameraService.instance?.relight() }
}

/**
 * The camera's encoder and its connection. One thread owns the GL context, the encoder and the sends: it draws each
 * camera frame upright (the camera's own turn from its transform, unmirrored for the front lens, then the phone's
 * turn), cropped to fill, at 30 frames a second. On the same Wi-Fi 1080p where the camera and encoder can, else 720p.
 * The bit rate follows the PC's feedback as the screen's does; a turned phone restarts the encoder at its new shape.
 */
class PhoneCameraService : Service() {
    companion object {
        const val STOP = "io.github.arnavdugad.arnavisland.CAMERA_STOP"; const val FLIP = "io.github.arnavdugad.arnavisland.CAMERA_FLIP"
        @Volatile var instance: PhoneCameraService? = null; private set
    }
    @Volatile private var running = false; @Volatile private var session: Link.ScreenSession? = null
    @Volatile internal var flipWanted = false; @Volatile private var feed: Feed? = null
    /** How the phone is held: 0, 90, 180 or 270 degrees turned clockwise from upright (as OrientationEventListener says). */
    @Volatile private var held = 0
    private var orientation: OrientationEventListener? = null
    private var done = false; private var pcName = "your PC"
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Hub.init(this); Notify.channels(this)
        when (intent?.action) {
            STOP -> { finish(); return START_NOT_STICKY }
            FLIP -> { if (running) PhoneCamera.flip(this) else if (instance == null) stopSelf(); return START_NOT_STICKY }
        }
        val peer = intent?.getStringExtra("peer")
        if (running) return START_NOT_STICKY
        pcName = Hub.peers.value.firstOrNull { it.id == peer }?.name ?: "your PC"
        runCatching {
            ServiceCompat.startForeground(this, Notify.CAMERA, notification("Connecting…"), if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        }.onFailure { android.util.Log.w("ArnavCamera", "no foreground", it); PhoneCamera.asking.value = false; CamWire.fail("Couldn’t turn on the camera", "Open Arnav Island and try again"); stopSelf(); return START_NOT_STICKY }
        if (peer == null) { PhoneCamera.asking.value = false; finish(); return START_NOT_STICKY }
        running = true; done = false; instance = this
        held = runCatching { (360 - getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).rotation * 90) % 360 }.getOrDefault(0)
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                // A phone lying flat says nothing; a turn counts once it's well past halfway (no flapping at 45°).
                if (o == ORIENTATION_UNKNOWN) return
                if (abs((o - held + 540) % 360 - 180) > 60) held = (o + 45) / 90 * 90 % 360
            }
        }.apply { if (canDetectOrientation()) enable() }
        thread(name = "phone-camera", isDaemon = true) {
            // Whatever goes wrong ends this camera (with a word why), never the app.
            try { stream(peer) } catch (e: Throwable) { android.util.Log.w("ArnavCamera", "camera failed", e); if (running) CamWire.fail("The camera stopped", "Couldn’t keep it on $pcName") }
            finally { main.post { finish() } }
        }
        return START_NOT_STICKY
    }

    private fun lensName() = if (PhoneCamera.lens.value == CameraCharacteristics.LENS_FACING_BACK) "Back camera" else "Front camera"
    private fun notification(text: String) = NotificationCompat.Builder(this, Notify.CH_CAMERA_MIC)
        .setSmallIcon(R.drawable.ic_stat_island).setContentTitle("Camera on $pcName").setContentText(text).setOngoing(true).setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_TRANSPORT).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(PendingIntent.getActivity(this, 11, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Flip", PendingIntent.getService(this, 12, Intent(this, PhoneCameraService::class.java).setAction(FLIP), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Stop", PendingIntent.getService(this, 13, Intent(this, PhoneCameraService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)).build()
    private fun update(text: String) = runCatching { if (running) getSystemService(NotificationManager::class.java).notify(Notify.CAMERA, notification(text)) }

    /** Ends it (on the main thread): the PC told, the camera thread let go (it frees the camera itself), the notification gone. */
    private fun finish() {
        if (done) return
        done = true; running = false; val s = session; session = null
        if (s != null) thread(name = "phone-camera-stop", isDaemon = true) { runCatching { s.send(byteArrayOf(Proto.SCREEN_STOP.toByte())) }; s.close() }
        orientation?.disable(); orientation = null
        if (instance === this) instance = null
        PhoneCamera.shown.value = null; PhoneCamera.asking.value = false; PhoneCamera.lit.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { finish(); super.onDestroy() }
    internal fun relight() { feed?.relight() }

    private class PcSays { @Volatile var acked = 0; @Volatile var heard = false; @Volatile var wantKey = false; @Volatile var lastHeard = SystemClock.elapsedRealtime(); @Volatile var limits: Triple<Int, Int, Int>? = null }
    private data class Cap(val longest: Int, val fps: Int, val start: Int, val most: Int, val least: Int)
    private data class Dim(val width: Int, val height: Int)
    /** A lens to use: its id, which way it faces, its sensor's turn, its light, and its picture size and fps range for the path. */
    private class Lens(val id: String, val facing: Int, val sensor: Int, val flash: Boolean, val width: Int, val height: Int, val fps: Range<Int>?, val focus: Boolean) {
        /** The picture's width over height as the camera's transform gives it (upright with the phone upright). */
        val aspect: Float get() = if (sensor % 180 == 90) height.toFloat() / width else width.toFloat() / height
    }

    /** The lens facing [facing] (any other when there's none), with its largest 16:9 size no longer than [longest] that keeps [fps]. */
    private fun lens(facing: Int, longest: Int, fps: Int): Lens? {
        val m = getSystemService(CameraManager::class.java)
        val all = runCatching { m.cameraIdList.toList() }.getOrDefault(emptyList()).mapNotNull { id -> runCatching { id to m.getCameraCharacteristics(id) }.getOrNull() }
        val (id, c) = all.firstOrNull { it.second.get(CameraCharacteristics.LENS_FACING) == facing } ?: all.firstOrNull { it.second.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_EXTERNAL } ?: all.firstOrNull() ?: return null
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val frame = 1_000_000_000L / fps
        fun keeps(s: android.util.Size) = runCatching { map.getOutputMinFrameDuration(SurfaceTexture::class.java, s) <= frame * 21 / 20 }.getOrDefault(true)
        fun long(s: android.util.Size) = max(s.width, s.height)
        fun wide(s: android.util.Size) = abs(long(s).toDouble() / min(s.width, s.height) - 16.0 / 9) < .04
        val fitting = sizes.filter { long(it) <= longest && keeps(it) }
        val size = fitting.filter(::wide).maxByOrNull { it.width * it.height } ?: fitting.maxByOrNull { it.width * it.height } ?: sizes.minByOrNull { it.width * it.height } ?: return null
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val range = ranges.filter { it.upper == fps }.maxByOrNull { it.lower } ?: ranges.filter { it.upper > fps }.minByOrNull { it.upper } ?: ranges.maxByOrNull { it.upper }
        val focus = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true
        return Lens(id, c.get(CameraCharacteristics.LENS_FACING) ?: facing, c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0, c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true, size.width, size.height, range, focus)
    }
    /** How far to turn [lens]'s picture clockwise for it to stand upright as the phone is held (the front lens's turns the other way, once unmirrored). */
    private fun turn(lens: Lens) = if (lens.facing == CameraCharacteristics.LENS_FACING_FRONT) (360 - held) % 360 else held
    /** The encoder's picture: the lens's, turned, no longer than [longest] or than the encoder can do at [fps], each side a multiple of 8. */
    private fun output(lens: Lens, turn: Int, longest: Int, fps: Int, caps: MediaCodecInfo.VideoCapabilities?): Dim {
        val long = max(lens.width, lens.height); val short = min(lens.width, lens.height)
        val wide = (lens.aspect >= 1f) != (turn % 180 == 90)
        var scale = min(1.0, longest.toDouble() / long)
        while (true) {
            val l = max(16, (long * scale).toInt() / 8 * 8); val s = max(16, (short * scale).toInt() / 8 * 8)
            val (w, h) = if (wide) l to s else s to l
            if (caps == null || scale < .2 || runCatching { caps.areSizeAndRateSupported(w, h, fps.toDouble()) }.getOrDefault(false)) return Dim(w, h)
            scale *= .85
        }
    }
    private fun encoderInfo(): MediaCodecInfo? = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        .sortedBy { if (Build.VERSION.SDK_INT >= 29 && it.isHardwareAccelerated) 0 else if (it.name.startsWith("OMX.google") || it.name.startsWith("c2.android")) 2 else 1 }.firstOrNull()
    private fun encoder(info: MediaCodecInfo, size: Dim, fps: Int, bitrate: Int): MediaCodec {
        val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        // Richest first, as the screen's: each try asks for less.
        fun format(plain: Int) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate); setInteger(MediaFormat.KEY_FRAME_RATE, fps); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000)
            if (plain < 2) {
                setInteger(MediaFormat.KEY_PRIORITY, 0); setInteger(MediaFormat.KEY_LATENCY, 1)
                if (caps.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            if (plain == 0) {
                if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
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

    private fun stream(peer: String) {
        val link = Hub.link; val pc = Hub.peers.value.firstOrNull { it.id == peer }
        if (link == null || pc == null) { CamWire.fail("Couldn’t reach $pcName"); return }
        val cap = when { !pc.internet -> Cap(1920, 30, 6_000_000, 10_000_000, 1_000_000); pc.path == 2 -> Cap(1280, 30, 2_500_000, 5_000_000, 500_000); else -> Cap(1280, 30, 900_000, 1_800_000, 250_000) }
        val info = encoderInfo() ?: run { CamWire.fail("This phone has no video encoder"); return }
        val videoCaps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull()
        var lens = lens(PhoneCamera.lens.value, cap.longest, cap.fps) ?: run { CamWire.fail("This phone’s camera can’t be used"); return }
        var longest = cap.longest; var fps = cap.fps
        var size = output(lens, turn(lens), longest, fps, videoCaps)
        val opened = link.openScreen(peer, CamWire.offerCamera(size.width, size.height, fps, Hub.phoneName()))
        if (!running) { opened?.first?.close(); return }
        if (opened == null) { CamWire.fail(if (pc.revision < CamWire.REVISION) "Update Arnav Island on $pcName" else "Couldn’t reach $pcName", if (pc.revision < CamWire.REVISION) "Its camera needs version 0.26 or later" else ""); return }
        val (s, answer) = opened; session = s
        val reply = ScreenWire.reply(answer)
        if (reply == null || reply.status != 0) { CamWire.fail("$pcName couldn’t use this phone’s camera", CamWire.statusText(reply?.status)); return }
        // The PC may ask for less than offered.
        if (reply.width > 0 && reply.height > 0) longest = min(longest, max(reply.width, reply.height))
        if (reply.fps > 0) fps = min(fps, reply.fps)
        var bitrate = if (reply.bitrate > 0) reply.bitrate.coerceIn(cap.least.toLong(), cap.start.toLong()).toInt() else cap.start
        size = output(lens, turn(lens), longest, fps, videoCaps)

        val cameraThread = HandlerThread("camera").apply { start() }; val cameraHandler = Handler(cameraThread.looper)
        var codec: MediaCodec? = null; var input: Surface? = null; var egl: Egl? = null; var window: EGLSurface? = null
        var painter: Painter? = null; var texture: SurfaceTexture? = null; var surface: Surface? = null; var feed: Feed? = null
        val pcSays = PcSays(); var number = 0
        try {
            var c = encoder(info, size, fps, bitrate); codec = c; var inp = c.createInputSurface(); input = inp; c.start()
            val e = Egl(); egl = e; var win = e.window(inp); window = win; e.current(win)
            val paint = Painter(); painter = paint
            val tex = SurfaceTexture(paint.texture); texture = tex; val camSurface = Surface(tex); surface = camSurface
            val fresh = AtomicBoolean(false); tex.setOnFrameAvailableListener({ fresh.set(true) }, cameraHandler)
            val f = Feed(cameraHandler, tex, camSurface); feed = f; this.feed = f
            f.open(lens)
            PhoneCamera.shown.value = pcName; PhoneCamera.asking.value = false
            update("${size.width}×${size.height} · ${lensName()}")
            // What the PC says: how it's keeping up, key frames it wants, new limits, its end.
            thread(name = "phone-camera-in", isDaemon = true) {
                while (running && s.open) {
                    val m = s.receive(500) ?: continue; if (m.isEmpty()) continue; pcSays.lastHeard = SystemClock.elapsedRealtime()
                    val r = Reader(m)
                    when (r.u8()) {
                        Proto.SCREEN_FEEDBACK -> { pcSays.acked = (r.u32() ?: 0L).toInt(); pcSays.heard = true }
                        Proto.SCREEN_KEYFRAME -> pcSays.wantKey = true
                        Proto.SCREEN_LIMITS -> pcSays.limits = Triple(r.u16() ?: 0, r.u16() ?: 0, r.u8() ?: 0)
                        Proto.SCREEN_STOP -> { android.util.Log.i("ArnavCamera", "the PC ended it"); running = false }
                    }
                }
                running = false
            }
            val st = FloatArray(16); val out = MediaCodec.BufferInfo(); var config: ByteArray? = null; var encodedFps = fps
            var skipping = false; var skippedAt = 0L; var lastSent = SystemClock.elapsedRealtime(); var lastDrawn = 0L
            var slowSends = 0; var lastCheck = SystemClock.elapsedRealtime(); var lastUp = lastCheck
            fun keyFrame() = runCatching { c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
            while (running && s.open) {
                // A PC that has spoken and then goes quiet for 8 s is gone.
                if (pcSays.heard && SystemClock.elapsedRealtime() - pcSays.lastHeard > 8000) break
                f.broken?.let { why -> CamWire.fail("The camera stopped", why); running = false; break }
                if (flipWanted) {
                    flipWanted = false
                    lens(PhoneCamera.lens.value, cap.longest, cap.fps)?.let { lens = it; f.open(it) }
                    update("${size.width}×${size.height} · ${lensName()}")
                }
                pcSays.limits?.let { (w, h, r) -> pcSays.limits = null; if (w > 0 && h > 0) longest = min(cap.longest, max(w, h)); if (r > 0) fps = min(cap.fps, r) }
                // A turned phone, another lens or new limits: the encoder again, at the new shape.
                val angle = turn(lens); val next = output(lens, angle, longest, fps, videoCaps)
                if (next != size || fps != encodedFps) {
                    size = next; encodedFps = fps
                    val nc = encoder(info, size, fps, bitrate); val ni = nc.createInputSurface(); nc.start()
                    val nw = e.window(ni); e.current(nw); e.destroy(win); window = nw; win = nw
                    runCatching { c.stop() }; c.release(); inp.release()
                    c = nc; codec = nc; inp = ni; input = ni
                    s.send(Bytes().u8(Proto.SCREEN_LIMITS).u16(size.width).u16(size.height).u8(fps).build()); config = null; skipping = false
                    update("${size.width}×${size.height} · ${lensName()}")
                }
                if (pcSays.wantKey) { pcSays.wantKey = false; keyFrame() }
                val tick = SystemClock.elapsedRealtime()
                if (tick - lastSent >= 1000) { if (!s.send(Bytes().u8(Proto.SCREEN_LIMITS).u16(size.width).u16(size.height).u8(fps).build())) break; lastSent = tick }
                if (skipping && tick - skippedAt >= 1000) { skippedAt = tick; keyFrame() }
                // The newest camera frame, drawn upright (no faster than the frame rate).
                var drew = false
                if (fresh.getAndSet(false)) {
                    tex.updateTexImage(); val at = tex.timestamp
                    if (at - lastDrawn >= 1_000_000_000L / fps * 4 / 5) {
                        lastDrawn = at; tex.getTransformMatrix(st)
                        paint.draw(st, lens.facing == CameraCharacteristics.LENS_FACING_FRONT, angle, lens.aspect, size.width, size.height)
                        e.present(win, at); drew = true
                    }
                }
                // Whatever the encoder has ready.
                var wait = if (drew) 2_000L else 8_000L
                while (running) {
                    val i = c.dequeueOutputBuffer(out, wait); wait = 0
                    if (i < 0) break
                    val buffer = c.getOutputBuffer(i)
                    val bytes = if (buffer != null && out.size > 0) ByteArray(out.size).also { buffer.position(out.offset); buffer.get(it) } else null
                    c.releaseOutputBuffer(i, false)
                    if (bytes == null) continue
                    if (out.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { config = bytes; continue }
                    val key = out.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    val acked = pcSays.acked; val heard = pcSays.heard
                    // A PC two seconds of frames behind: nothing more until a key frame, so it catches up instead of lagging.
                    if (heard && number - acked > fps * 2 && !skipping) { skipping = true; skippedAt = SystemClock.elapsedRealtime(); keyFrame() }
                    if (skipping && !key) continue
                    skipping = false
                    val data = if (key && config != null && ScreenWire.nals(bytes).none { it.first == 7 }) config!! + bytes else bytes
                    number++; val t0 = SystemClock.elapsedRealtime()
                    for (fr in ScreenWire.frames(number, key, out.presentationTimeUs * 10, data)) if (!s.send(fr)) { running = false; break }
                    lastSent = SystemClock.elapsedRealtime()
                    // The bit rate follows the path: down a quarter when sends are slow or the PC falls behind, up a little after four easy seconds.
                    if (lastSent - t0 > 1500 / fps) slowSends++ else slowSends = max(0, slowSends - 1)
                    if (lastSent - lastCheck >= 500) {
                        lastCheck = lastSent; val behind = heard && number - acked > fps
                        val nextRate = if (slowSends >= 3 || behind) max(cap.least, bitrate * 3 / 4).also { slowSends = 0; lastUp = lastSent }
                            else if (lastSent - lastUp >= 4000 && bitrate < cap.most) min(cap.most, bitrate * 115 / 100).also { lastUp = lastSent } else bitrate
                        if (nextRate != bitrate) { bitrate = nextRate; runCatching { c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) }) } }
                    }
                }
            }
            // The connection gone without the PC saying so.
            if (running && !s.open) CamWire.fail("The camera stopped", "Lost the connection to $pcName")
            android.util.Log.i("ArnavCamera", "ended: running=$running open=${s.open}, frame $number")
        } finally {
            // The camera first (it draws into the texture), then the GL step and the encoder.
            this.feed = null; feed?.close(); cameraThread.quitSafely()
            runCatching { surface?.release() }; runCatching { texture?.release() }; runCatching { painter?.release() }
            runCatching { window?.let { egl?.destroy(it) } }; runCatching { egl?.release() }
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { input?.release() }
        }
    }

    /** The camera itself, on its own thread: opened on a lens, drawing into [surface], with its light. */
    private inner class Feed(private val handler: Handler, private val texture: SurfaceTexture, private val surface: Surface) {
        private var device: CameraDevice? = null; private var capture: CameraCaptureSession? = null; private var lens: Lens? = null
        private var generation = 0
        /** Why the camera stopped on its own (another app took it, an error), for the encoder's thread to end on. */
        @Volatile var broken: String? = null
        fun open(next: Lens) = handler.post {
            shut(); val mine = ++generation; lens = next
            texture.setDefaultBufferSize(next.width, next.height)
            PhoneCamera.lens.value = next.facing; PhoneCamera.lightable.value = next.flash; if (!next.flash) PhoneCamera.lit.value = false
            try {
                getSystemService(CameraManager::class.java).openCamera(next.id, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) { if (mine != generation) { d.close(); return }; device = d; configure(d, mine) }
                    override fun onDisconnected(d: CameraDevice) { d.close(); if (mine == generation) { device = null; broken = "Another app is using the camera" } }
                    override fun onError(d: CameraDevice, error: Int) { d.close(); if (mine == generation) { device = null; broken = if (error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE) "Another app is using the camera" else "The camera had a problem ($error)" } }
                }, handler)
            } catch (e: Exception) { android.util.Log.w("ArnavCamera", "open failed", e); broken = "Couldn’t open the camera" }
        }
        private fun configure(d: CameraDevice, mine: Int) {
            try {
                d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(surface)), Executor { handler.post(it) }, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { if (mine != generation) { s.close(); return }; capture = s; relightNow() }
                    override fun onConfigureFailed(s: CameraCaptureSession) { if (mine == generation) broken = "The camera couldn’t start" }
                }))
            } catch (e: Exception) { android.util.Log.w("ArnavCamera", "session failed", e); if (mine == generation) broken = "The camera couldn’t start" }
        }
        /** The repeating request: auto everything at the frame rate, focus that follows, and the light as asked. */
        private fun relightNow() {
            val d = device ?: return; val s = capture ?: return; val l = lens ?: return
            runCatching {
                s.setRepeatingRequest(d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(surface); set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    l.fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    if (l.focus) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    set(CaptureRequest.FLASH_MODE, if (l.flash && PhoneCamera.lit.value) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                }.build(), null, handler)
            }.onFailure { android.util.Log.w("ArnavCamera", "request failed", it) }
        }
        fun relight() { handler.post { relightNow() } }
        private fun shut() { runCatching { capture?.close() }; capture = null; runCatching { device?.close() }; device = null }
        /** Closes the camera and waits (up to 2 s) until it has let go. */
        fun close() { val closed = CountDownLatch(1); if (handler.post { generation++; shut(); closed.countDown() }) runCatching { closed.await(2, TimeUnit.SECONDS) } }
    }

    /** The EGL side: a context on this thread and window surfaces on the encoder's input. */
    private class Egl {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY); private val config: EGLConfig; private val context: EGLContext
        init {
            val v = IntArray(2); check(EGL14.eglInitialize(display, v, 0, v, 1)) { "no EGL" }
            val configs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            // 0x3142: EGL_RECORDABLE_ANDROID, for a surface an encoder reads.
            EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142, 1, EGL14.EGL_NONE), 0, configs, 0, 1, n, 0)
            config = configs[0]?.takeIf { n[0] > 0 } ?: error("no EGL config")
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "no EGL context" }
        }
        fun window(s: Surface): EGLSurface = EGL14.eglCreateWindowSurface(display, config, s, intArrayOf(EGL14.EGL_NONE), 0).also { check(it != EGL14.EGL_NO_SURFACE) { "no EGL surface" } }
        fun current(s: EGLSurface) = check(EGL14.eglMakeCurrent(display, s, s, context)) { "EGL couldn’t start" }
        fun present(s: EGLSurface, ns: Long) { EGLExt.eglPresentationTimeANDROID(display, s, ns); EGL14.eglSwapBuffers(display, s) }
        fun destroy(s: EGLSurface) { EGL14.eglDestroySurface(display, s) }
        fun release() { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT); EGL14.eglDestroyContext(display, context); EGL14.eglReleaseThread() }
    }

    /**
     * Draws the camera's texture: the camera's own transform (upright with the phone upright, mirrored for the front
     * lens), unmirrored for the front lens (a webcam's picture is as others see you), then turned clockwise as the phone
     * is held and scaled to fill the picture (cropping the rest).
     */
    private class Painter {
        val texture: Int; private val program: Int; private val aPos: Int; private val aTex: Int; private val uPos: Int; private val uTex: Int
        private val quad = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f); private val coords = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        private val pos = FloatArray(16); private val tex = FloatArray(16); private val flip = FloatArray(16)
        init {
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0); texture = t[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, "uniform mat4 uPos; uniform mat4 uTex; attribute vec4 aPos; attribute vec4 aTex; varying vec2 vTex;\nvoid main() { gl_Position = uPos * aPos; vTex = (uTex * aTex).xy; }"))
            GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex;\nvoid main() { gl_FragColor = texture2D(sTex, vTex); }"))
            GLES20.glLinkProgram(program)
            val ok = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0); check(ok[0] != 0) { "GL: " + GLES20.glGetProgramInfoLog(program) }
            aPos = GLES20.glGetAttribLocation(program, "aPos"); aTex = GLES20.glGetAttribLocation(program, "aTex")
            uPos = GLES20.glGetUniformLocation(program, "uPos"); uTex = GLES20.glGetUniformLocation(program, "uTex")
        }
        private fun shader(type: Int, source: String): Int {
            val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, source); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0); check(ok[0] != 0) { "GL: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        /** [st]: the camera's transform; [aspect]: the picture's width over height upright; [turn]: degrees clockwise. */
        fun draw(st: FloatArray, unmirror: Boolean, turn: Int, aspect: Float, width: Int, height: Int) {
            Matrix.setIdentityM(flip, 0); if (unmirror) { Matrix.translateM(flip, 0, 1f, 0f, 0f); Matrix.scaleM(flip, 0, -1f, 1f, 1f) }
            Matrix.multiplyMM(tex, 0, st, 0, flip, 0)
            val shown = if (turn % 180 == 90) 1f / aspect else aspect; val view = width.toFloat() / height
            Matrix.setIdentityM(pos, 0)
            if (shown > view) Matrix.scaleM(pos, 0, shown / view, 1f, 1f) else Matrix.scaleM(pos, 0, 1f, view / shown, 1f)
            Matrix.rotateM(pos, 0, -turn.toFloat(), 0f, 0f, 1f)
            GLES20.glViewport(0, 0, width, height); GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniformMatrix4fv(uPos, 1, false, pos, 0); GLES20.glUniformMatrix4fv(uTex, 1, false, tex, 0)
            GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glEnableVertexAttribArray(aTex); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aTex)
        }
        fun release() { GLES20.glDeleteProgram(program); GLES20.glDeleteTextures(1, intArrayOf(texture), 0) }
        private fun floats(vararg v: Float): FloatBuffer = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }
    }
}
