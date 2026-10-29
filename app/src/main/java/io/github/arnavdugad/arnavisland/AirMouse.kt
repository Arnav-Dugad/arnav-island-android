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
 */
class AirMouse(context: Context, private val onMove: (dx: Float, dy: Float) -> Unit) : SensorEventListener {
    companion object {
        const val PREF = "airSensitivity"
        private const val GAP_NS = 8_333_333L // 120 Hz
        private const val DEADZONE = 1.1f    // deg/s
        private const val TAU = .025f        // s
        private const val RAD = 57.29578f
    }
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotation = if (gyro == null) manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) else null
    private val gravity = if (gyro != null) manager.getDefaultSensor(Sensor.TYPE_GRAVITY) else null
    /** Whether this phone can point at all. */
    val available get() = gyro != null || rotation != null
    @Volatile var sensitivity = runCatching { Hub.prefs.getFloat(PREF, .5f) }.getOrDefault(.5f)
    /** The clutch: the pointer moves only while this is true. */
    @Volatile var engaged = false
        set(v) { if (v != field) { field = v; settle() } }
    private var running = false
    // Which way is up, in the phone's own axes (it starts as if held flat).
    private var ux = 0f; private var uy = 0f; private var uz = 1f
    private var last = 0L; private var sentAt = 0L
    private var prev: FloatArray? = null; private val r = FloatArray(9)
    private var sx = 0f; private var sy = 0f; private var ax = 0f; private var ay = 0f
    // The gyroscope's drift, learnt while the phone is still (and at once by [recenter]).
    private val bias = FloatArray(3); private val sum = FloatArray(3); private var counting = 0

    fun start() {
        if (running) return; running = true; settle()
        gyro?.let { manager.registerListener(this, it, 5000) }
        gravity?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        rotation?.let { manager.registerListener(this, it, 5000) }
    }
    fun stop() { if (!running) return; running = false; manager.unregisterListener(this); settle() }

    /** Starts afresh: drops what was gathered and, for the next half second held still, learns the gyroscope's drift. */
    fun recenter() { settle(); prev = null; sum.fill(0f); counting = 60 }

    private fun settle() { sx = 0f; sy = 0f; ax = 0f; ay = 0f; last = 0L }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> { val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); if (n > 1f) { ux = v[0] / n; uy = v[1] / n; uz = v[2] / n } }
            Sensor.TYPE_GYROSCOPE -> {
                val dt = step(e.timestamp) ?: return
                val wx = v[0] - bias[0]; val wy = v[1] - bias[1]; val wz = v[2] - bias[2]
                learn(v[0], v[1], v[2])
                feed(wx, wy, wz, dt, e.timestamp)
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(r, v)
                ux = r[6]; uy = r[7]; uz = r[8]
                val p = prev; prev = r.copyOf()
                val dt = step(e.timestamp) ?: return
                if (p == null) return
                // The turn since the last reading, in the phone's axes: the skew part of prevᵀ·now.
                fun d(i: Int, j: Int) = p[i] * r[j] + p[3 + i] * r[3 + j] + p[6 + i] * r[6 + j]
                feed((d(2, 1) - d(1, 2)) / 2f / dt, (d(0, 2) - d(2, 0)) / 2f / dt, (d(1, 0) - d(0, 1)) / 2f / dt, dt, e.timestamp)
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
        } else if (still && !engaged) { bias[0] += (x - bias[0]) * .002f; bias[1] += (y - bias[1]) * .002f; bias[2] += (z - bias[2]) * .002f }
    }

    /** One reading's turn (rad/s, the phone's axes) into pointer motion. */
    private fun feed(wx: Float, wy: Float, wz: Float, dt: Float, ns: Long) {
        if (!engaged) return
        val yaw = wx * ux + wy * uy + wz * uz
        // The phone's left-right axis laid level; on its side, its long axis instead.
        var rx = 1f - ux * ux; var ry = -ux * uy; var rz = -ux * uz; var n = sqrt(rx * rx + ry * ry + rz * rz)
        if (n < .25f) { rx = -uy * ux; ry = 1f - uy * uy; rz = -uy * uz; n = sqrt(rx * rx + ry * ry + rz * rz).coerceAtLeast(.01f) }
        val pitch = (wx * rx + wy * ry + wz * rz) / n
        // Turning left moves left; tipping the top up moves up.
        val vx = soft(-yaw * RAD); val vy = soft(-pitch * RAD)
        val a = 1f - exp(-dt / TAU)
        sx += (vx - sx) * a; sy += (vy - sy) * a
        val speed = hypot(sx, sy)
        val perDegree = 9f + 52f * sensitivity.coerceIn(0f, 1f).pow(1.3f)
        val gain = perDegree * (.45f + min(speed / 70f, 1.9f))
        ax += sx * dt * gain; ay += sy * dt * gain
        if (ns - sentAt >= GAP_NS && (abs(ax) >= .05f || abs(ay) >= .05f)) { sentAt = ns; val x = ax; val y = ay; ax = 0f; ay = 0f; onMove(x, y) }
    }
    private fun soft(deg: Float) = if (abs(deg) <= DEADZONE) 0f else deg - DEADZONE * sign(deg)
}

/**
 * An [AirMouse] listening only while [active] and the app is on screen (its sensors are let go otherwise, and when it
 * leaves). [onMove] gets pixels.
 */
@Composable fun rememberAirMouse(active: Boolean, onMove: (dx: Float, dy: Float) -> Unit): AirMouse {
    val context = LocalContext.current; val owner = LocalLifecycleOwner.current
    val move by rememberUpdatedState(onMove)
    val mouse = remember { AirMouse(context.applicationContext) { x, y -> move(x, y) } }
    DisposableEffect(active, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME && active) mouse.start() else if (event == Lifecycle.Event.ON_PAUSE) mouse.stop() }
        owner.lifecycle.addObserver(observer)
        if (active && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mouse.start()
        onDispose { owner.lifecycle.removeObserver(observer); mouse.engaged = false; mouse.stop() }
    }
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
