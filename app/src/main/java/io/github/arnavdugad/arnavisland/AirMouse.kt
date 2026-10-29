package io.github.arnavdugad.arnavisland

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * The phone as an air mouse: pointing it moves the PC's pointer. The gyroscope's turn about the room's vertical (yaw)
 * moves the pointer across, its tilt about the level left-right axis (pitch) moves it up and down, so it works the same
 * held flat like a remote or upright like a camera. Without a gyroscope, the change between game-rotation readings
 * stands in for it.
 *
 * Small trembles fall in a soft deadzone, the rest is smoothed (a ~25 ms low pass), then sped up the faster the wrist
 * turns: slow is precise, a flick crosses the screen. Moves are gathered and handed on at most 120 times a second; the
 * pointer moves only while [engaged] (the clutch is held). [sensitivity] (0..1) is kept in [Hub.prefs].
 *
 * 1.9 (#36): the gyroscope's drift, once calibrated, is kept in [Hub.prefs] too, and with [flicks] on the wrist clicks:
 * a quick flick down (and back) is a left click, a second one soon after makes it a double click, and a twist about the
 * pointing axis (and back) is a right click. Each needs a sharp turn that springs straight back, far quicker than
 * pointing ever is, and the pointer holds still while one may be under way, so a click lands where it was aimed.
 */
class AirMouse(context: Context, private val onMove: (dx: Float, dy: Float) -> Unit) : SensorEventListener {
    companion object {
        const val PREF = "airSensitivity"
        /** 1.9: the learnt drift ("x,y,z", rad/s) and whether flicks click. */
        const val PREF_BIAS = "airBias"; const val PREF_FLICKS = "airFlicks"
        /** 1.9: what [onGesture] hears. */
        const val FLICK = 1; const val DOUBLE_FLICK = 2; const val TWIST = 3
        private const val GAP_NS = 8_333_333L // 120 Hz
        private const val DEADZONE = 1.1f    // deg/s
        private const val TAU = .025f        // s
        private const val RAD = 57.29578f
        // A flick: past 4.5 rad/s (~260°/s) down, a twist past 5.5, each mostly about its one axis.
        private const val FLICK_RATE = 4.5f; private const val TWIST_RATE = 5.5f; private const val DOMINANT = 1.6f
        private const val MS = 1_000_000L
        private fun soft(deg: Float) = if (abs(deg) <= DEADZONE) 0f else deg - DEADZONE * sign(deg)
        private fun perDegree(sensitivity: Float) = 9f + 52f * sensitivity.coerceIn(0f, 1f).pow(1.3f)
        private fun boost(speed: Float) = .45f + min(speed / 70f, 1.9f)

        /**
         * 1.9: how far a recorded sweep would carry the pointer across, per unit of gain, through the same deadzone,
         * smoothing and speed-up as pointing ([xs], [ys]: deg/s as [Probe] gives them; [dts]: seconds).
         */
        fun travel(xs: FloatArray, ys: FloatArray, dts: FloatArray): Float {
            var sx = 0f; var sy = 0f; var k = 0f
            for (i in xs.indices) {
                val dt = dts[i]; val a = 1f - exp(-dt / TAU)
                sx += (soft(xs[i]) - sx) * a; sy += (soft(ys[i]) - sy) * a
                k += sx * dt * boost(hypot(sx, sy))
            }
            return k
        }
        /** 1.9: the sensitivity at which a sweep of [travel] crosses [pixels] (a 1920-wide screen, as the PC's isn't known), or null when it can't tell. */
        fun sensitivityFor(travel: Float, pixels: Float = 1920f): Float? {
            if (travel <= 1f) return null
            return ((pixels / travel - 9f) / 52f).coerceIn(0f, 1f).pow(1f / 1.3f)
        }
    }
    /**
     * 1.9: each reading, for the calibration: the raw turn (rad/s, the phone's axes; null without a gyroscope), and the
     * turn as the pointer sees it (deg/s, right and down, drift taken off), over [dt] seconds.
     */
    fun interface Probe { fun reading(raw: FloatArray?, x: Float, y: Float, dt: Float) }

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotation = if (gyro == null) manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) else null
    private val gravity = if (gyro != null) manager.getDefaultSensor(Sensor.TYPE_GRAVITY) else null
    /** Whether this phone can point at all. */
    val available get() = gyro != null || rotation != null
    /** 1.9: whether it points with a real gyroscope (only that drifts, so only that is calibrated held still). */
    val hasGyro get() = gyro != null
    @Volatile var sensitivity = runCatching { Hub.prefs.getFloat(PREF, .5f) }.getOrDefault(.5f)
    /** The clutch: the pointer moves only while this is true. */
    @Volatile var engaged = false
        set(v) { if (v != field) { field = v; settle() } }
    /** 1.9: whether wrist flicks click (heard through [onGesture]). */
    @Volatile var flicks = false
        set(v) { if (v != field) { field = v; armed = 0 } }
    /** 1.9: [FLICK], [DOUBLE_FLICK] or [TWIST], on the main thread. */
    var onGesture: ((Int) -> Unit)? = null
    /** 1.9: the calibration's ear, while it listens. */
    var probe: Probe? = null
    private var running = false
    // Which way is up, in the phone's own axes (it starts as if held flat).
    private var ux = 0f; private var uy = 0f; private var uz = 1f
    private var last = 0L; private var sentAt = 0L
    private var prev: FloatArray? = null; private val r = FloatArray(9)
    private var sx = 0f; private var sy = 0f; private var ax = 0f; private var ay = 0f
    // The gyroscope's drift, learnt while the phone is still (and at once by [recenter]); 1.9: starting from the calibrated one.
    private val bias = FloatArray(3); private val sum = FloatArray(3); private var counting = 0
    private val raw = FloatArray(3)
    // 1.9: a gesture under way (0 none, 1 a flick down, 2 a twist), which way, when it began and its peak; quiet after one.
    private var armed = 0; private var armedSign = 0f; private var armedAt = 0L; private var peak = 0f
    private var quietUntil = 0L; private var lastFlick = 0L
    // 1.9: the last moves handed on, so a flick's run-up can be taken back.
    private val sentNs = LongArray(16); private val sentX = FloatArray(16); private val sentY = FloatArray(16); private var sentNext = 0

    init {
        runCatching { Hub.prefs.getString(PREF_BIAS, null)?.split(',')?.map { it.toFloat() }?.takeIf { it.size == 3 && it.all { v -> abs(v) < .2f } }?.forEachIndexed { i, v -> bias[i] = v } }
    }

    fun start() {
        if (running) return; running = true; settle()
        gyro?.let { manager.registerListener(this, it, 5000) }
        gravity?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        rotation?.let { manager.registerListener(this, it, 5000) }
    }
    fun stop() { if (!running) return; running = false; manager.unregisterListener(this); settle() }

    /** Starts afresh: drops what was gathered and, for the next half second held still, learns the gyroscope's drift. */
    fun recenter() { settle(); prev = null; sum.fill(0f); counting = 60 }
    /** 1.9: the drift the calibration measured (rad/s, the phone's axes), used now and kept for next time. */
    fun calibrated(x: Float, y: Float, z: Float) {
        bias[0] = x; bias[1] = y; bias[2] = z; counting = 0; settle()
        runCatching { Hub.prefs.edit().putString(PREF_BIAS, "$x,$y,$z").apply() }
    }
    /** 1.9: the drift in use, in degrees a second (how fast it seemed to turn on its own). */
    val drift get() = sqrt(bias[0] * bias[0] + bias[1] * bias[1] + bias[2] * bias[2]) * RAD

    private fun settle() { sx = 0f; sy = 0f; ax = 0f; ay = 0f; last = 0L; armed = 0 }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> { val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); if (n > 1f) { ux = v[0] / n; uy = v[1] / n; uz = v[2] / n } }
            Sensor.TYPE_GYROSCOPE -> {
                val dt = step(e.timestamp) ?: return
                val wx = v[0] - bias[0]; val wy = v[1] - bias[1]; val wz = v[2] - bias[2]
                learn(v[0], v[1], v[2])
                raw[0] = v[0]; raw[1] = v[1]; raw[2] = v[2]
                feed(wx, wy, wz, dt, e.timestamp, raw)
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(r, v)
                ux = r[6]; uy = r[7]; uz = r[8]
                val p = prev; prev = r.copyOf()
                val dt = step(e.timestamp) ?: return
                if (p == null) return
                // The turn since the last reading, in the phone's axes: the skew part of prevᵀ·now.
                fun d(i: Int, j: Int) = p[i] * r[j] + p[3 + i] * r[3 + j] + p[6 + i] * r[6 + j]
                feed((d(2, 1) - d(1, 2)) / 2f / dt, (d(0, 2) - d(2, 0)) / 2f / dt, (d(1, 0) - d(0, 1)) / 2f / dt, dt, e.timestamp, null)
            }
        }
    }

    /** Seconds since the last reading, or null for the first (or one after a long gap). */
    private fun step(ns: Long): Float? {
        val before = last; last = ns
        if (before == 0L) return null
        val dt = (ns - before) / 1e9f
        return if (dt <= 0f || dt > .1f) null else dt
    }

    private fun learn(x: Float, y: Float, z: Float) {
        val still = abs(x) < .06f && abs(y) < .06f && abs(z) < .06f
        if (counting > 0) {
            if (abs(x) < .2f && abs(y) < .2f && abs(z) < .2f) { sum[0] += x; sum[1] += y; sum[2] += z; counting-- }
            if (counting == 0) for (i in 0..2) bias[i] = sum[i] / 60f
        } else if (still && !engaged && probe == null) { bias[0] += (x - bias[0]) * .002f; bias[1] += (y - bias[1]) * .002f; bias[2] += (z - bias[2]) * .002f }
    }

    /** One reading's turn (rad/s, the phone's axes): to the calibration, the flicks, and (while engaged) the pointer. */
    private fun feed(wx: Float, wy: Float, wz: Float, dt: Float, ns: Long, raw: FloatArray?) {
        val yaw = wx * ux + wy * uy + wz * uz
        // The phone's left-right axis laid level; on its side, its long axis instead.
        var rx = 1f - ux * ux; var ry = -ux * uy; var rz = -ux * uz; var n = sqrt(rx * rx + ry * ry + rz * rz)
        if (n < .25f) { rx = -uy * ux; ry = 1f - uy * uy; rz = -uy * uz; n = sqrt(rx * rx + ry * ry + rz * rz).coerceAtLeast(.01f) }
        rx /= n; ry /= n; rz /= n
        val pitch = wx * rx + wy * ry + wz * rz
        // Turning left moves left; tipping the top up moves up.
        val x = -yaw * RAD; val y = -pitch * RAD
        probe?.reading(raw, x, y, dt)
        if (flicks) {
            // The pointing axis (level, away from you) is up × right; a twist turns about it.
            val roll = wx * (uy * rz - uz * ry) + wy * (uz * rx - ux * rz) + wz * (ux * ry - uy * rx)
            gesture(yaw, pitch, roll, ns)
        }
        if (engaged) point(soft(x), soft(y), dt, ns)
    }

    /** Turn into pointer moves: smoothed, sped up and gathered; held back while a flick may be under way, dropped just after one. */
    private fun point(vx: Float, vy: Float, dt: Float, ns: Long) {
        val a = 1f - exp(-dt / TAU)
        sx += (vx - sx) * a; sy += (vy - sy) * a
        if (ns < quietUntil) { ax = 0f; ay = 0f; return }
        val gain = perDegree(sensitivity) * boost(hypot(sx, sy))
        ax += sx * dt * gain; ay += sy * dt * gain
        if (armed == 0 && ns - sentAt >= GAP_NS && (abs(ax) >= .05f || abs(ay) >= .05f)) {
            sentAt = ns; val mx = ax; val my = ay; ax = 0f; ay = 0f
            sentNs[sentNext] = ns; sentX[sentNext] = mx; sentY[sentNext] = my; sentNext = (sentNext + 1) % sentNs.size
            onMove(mx, my)
        }
    }

    /**
     * 1.9: the flicks. One begins when the turn about one axis passes its rate (down for a flick, either way for a twist)
     * with the other axes well below it; it counts only when it springs back the other way within 0.4 s, having gone the
     * first way no longer than 0.22 s. Anything slower is pointing, and what was held back goes on as usual.
     */
    private fun gesture(yaw: Float, pitch: Float, roll: Float, ns: Long) {
        if (ns < quietUntil) return
        val yy = abs(yaw); val pp = abs(pitch); val rr = abs(roll)
        if (armed == 0) {
            if (pitch < -FLICK_RATE && pp > DOMINANT * max(yy, rr)) { armed = 1; armedSign = -1f; armedAt = ns; peak = pp }
            else if (rr > TWIST_RATE && rr > DOMINANT * max(yy, pp)) { armed = 2; armedSign = sign(roll); armedAt = ns; peak = rr }
            return
        }
        val v = if (armed == 1) pitch else roll; val age = ns - armedAt
        when {
            age > 400 * MS -> armed = 0
            v * armedSign > 0f -> { peak = max(peak, abs(v)); if (age > 220 * MS) armed = 0 }
            -v * armedSign > max(1.5f, peak * .3f) -> {
                val kind = if (armed == 2) TWIST else if (ns - lastFlick < 450 * MS) DOUBLE_FLICK else FLICK
                if (armed == 1) lastFlick = if (kind == DOUBLE_FLICK) 0L else ns
                // Take back what went just before it began (its run-up), drop what was held, and stay still through the rebound.
                if (engaged) {
                    var bx = 0f; var by = 0f
                    for (i in sentNs.indices) if (sentNs[i] != 0L && sentNs[i] >= armedAt - 60 * MS) { bx -= sentX[i]; by -= sentY[i]; sentNs[i] = 0L }
                    if (bx != 0f || by != 0f) onMove(bx, by)
                }
                armed = 0; ax = 0f; ay = 0f; sx = 0f; sy = 0f; quietUntil = ns + 220 * MS
                onGesture?.invoke(kind)
            }
        }
    }
}

/**
 * An [AirMouse] listening only while [active] and the app is on screen (its sensors are let go otherwise, and when it
 * leaves). [onMove] gets pixels.
 *
 * 1.9: [engaged] is the clutch, applied after the sensors start. (Before, callers set it by hand as they changed
 * [active], and the effect starting afresh let go of it again at once, so the laser pointer never moved.) [flicks] and
 * [onGesture] are the wrist clicks.
 */
@Composable fun rememberAirMouse(active: Boolean, engaged: Boolean = false, flicks: Boolean = false, onGesture: ((Int) -> Unit)? = null, onMove: (dx: Float, dy: Float) -> Unit): AirMouse {
    val context = LocalContext.current; val owner = LocalLifecycleOwner.current
    val move by rememberUpdatedState(onMove); val gesture by rememberUpdatedState(onGesture)
    val mouse = remember { AirMouse(context.applicationContext) { x, y -> move(x, y) }.also { m -> m.onGesture = { gesture?.invoke(it) } } }
    DisposableEffect(active, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME && active) mouse.start() else if (event == Lifecycle.Event.ON_PAUSE) mouse.stop() }
        owner.lifecycle.addObserver(observer)
        if (active && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mouse.start()
        onDispose { owner.lifecycle.removeObserver(observer); mouse.engaged = false; mouse.stop() }
    }
    // Side effects run after every effect above (a re-keyed one's onDispose included), so nothing undoes these.
    SideEffect { mouse.engaged = active && engaged; mouse.flicks = active && flicks }
    return mouse
}

/**
 * True while a finger is on it, false when it lifts or the gesture is cut short (the sheet closing). It takes the touch
 * whole, so nothing behind (the sheet's drag to close) reads a held thumb that wanders as a swipe.
 */
@Composable fun Modifier.whileHeld(onHold: (Boolean) -> Unit): Modifier {
    val hold by rememberUpdatedState(onHold)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false).consume(); hold(true)
            try { while (true) { val e = awaitPointerEvent(); e.changes.forEach { it.consume() }; if (e.changes.none { it.pressed }) break } }
            finally { hold(false) }
        }
    }
}
