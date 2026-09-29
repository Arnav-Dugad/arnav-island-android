package io.github.arnavdugad.arnavisland

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * How the phone is tilted, from gravity, smoothed, only while the app is on screen: (-1..1, -1..1). The glass's light
 * and the cover's parallax follow it. Still (zero) with reduced motion or without the sensor.
 */
@Composable fun rememberTilt(enabled: Boolean): State<Offset> {
    val context = LocalContext.current; val owner = LocalLifecycleOwner.current
    val tilt = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(enabled, owner) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var x = 0f; var y = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // Gravity along the screen, relative to holding the phone a little tipped back.
                val tx = (-e.values[0] / 5.5f).coerceIn(-1f, 1f); val ty = ((e.values[1] - 6.5f) / 4.5f).coerceIn(-1f, 1f)
                x += (tx - x) * .12f; y += (ty - y) * .12f
                if (kotlin.math.abs(tilt.value.x - x) > .004f || kotlin.math.abs(tilt.value.y - y) > .004f) tilt.value = Offset(x, y)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        fun on() { if (enabled && sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME) }
        fun off() { manager.unregisterListener(listener) }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) on() else if (event == Lifecycle.Event.ON_PAUSE) off() }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) on()
        if (!enabled) tilt.value = Offset.Zero
        onDispose { owner.lifecycle.removeObserver(observer); off() }
    }
    return tilt
}

/** Play and pause, morphing: the two bars of pause flow into the triangle of play and back. */
@Composable fun PlayPause(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    val reduced = LocalReduced.current
    val f by animateFloatAsState(if (playing) 1f else 0f, if (reduced) snap() else spring(dampingRatio = .72f, stiffness = 420f), label = "PlayPause")
    Canvas(modifier) {
        val w = size.width; val h = size.height; val s = min(w, h)
        val ox = (w - s) / 2; val oy = (h - s) / 2
        fun p(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)
        fun mix(a: Offset, b: Offset) = Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
        // Play: a triangle split in two halves; pause: two bars.
        val left = listOf(p(.24f, .14f), p(.52f, .3f), p(.52f, .7f), p(.24f, .86f)) to listOf(p(.22f, .16f), p(.42f, .16f), p(.42f, .84f), p(.22f, .84f))
        val right = listOf(p(.52f, .3f), p(.84f, .5f), p(.84f, .5f), p(.52f, .7f)) to listOf(p(.58f, .16f), p(.78f, .16f), p(.78f, .84f), p(.58f, .84f))
        for ((play, pause) in listOf(left, right)) {
            val path = Path(); val pts = play.indices.map { mix(play[it], pause[it]) }
            path.moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { path.lineTo(it.x, it.y) }; path.close()
            drawPath(path, color); drawPath(path, color, style = Stroke(width = s * .06f, join = StrokeJoin.Round))
        }
    }
}

/** A code whose digits roll into place one after another, like a departure board. */
@Composable fun RollingDigits(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val reduced = LocalReduced.current
    Row(modifier, horizontalArrangement = Arrangement.Center) {
        text.forEachIndexed { i, ch ->
            var shown by remember { mutableStateOf(if (reduced) ch else ' ') }
            LaunchedEffect(ch) { if (!reduced) kotlinx.coroutines.delay(70L * i); shown = ch }
            AnimatedContent(shown, transitionSpec = {
                (slideInVertically(spring(dampingRatio = .7f, stiffness = 380f)) { it } + fadeIn(tween(160))) togetherWith (slideOutVertically(tween(160)) { -it } + fadeOut(tween(120)))
            }, label = "Digit") { c -> Text(c.toString(), style = style, color = color) }
        }
    }
}

/** A progress ring with a bright head, and a soft glow while it moves. */
@Composable fun ProgressRing(fraction: Float, color: Color, track: Color, modifier: Modifier = Modifier, stroke: Dp = 4.dp) {
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(dampingRatio = .9f, stiffness = 120f), label = "Ring")
    Canvas(modifier) {
        val w = stroke.toPx(); val d = min(size.width, size.height) - w; val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
        drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(w, cap = StrokeCap.Round))
        if (shown > 0f) {
            drawArc(Brush.sweepGradient(listOf(color.copy(alpha = .35f), color, color), center), -90f, 360f * shown, false, tl, Size(d, d), style = Stroke(w, cap = StrokeCap.Round))
            val a = (-90f + 360f * shown) * PI.toFloat() / 180f; val head = Offset(center.x + d / 2 * cos(a), center.y + d / 2 * sin(a))
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .8f), Color.Transparent), head, w * 2.6f), w * 2.6f, head)
        }
    }
}

/** Level bars that dance while music plays and settle when it stops. */
@Composable fun Equalizer(playing: Boolean, color: Color, modifier: Modifier = Modifier, bars: Int = 4) {
    val reduced = LocalReduced.current
    val transition = rememberInfiniteTransition(label = "Eq")
    val phases = (0 until bars).map { i -> transition.animateFloat(0f, 1f, infiniteRepeatable(tween(520 + i * 170, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Bar$i") }
    val level by animateFloatAsState(if (playing && !reduced) 1f else 0f, tween(500), label = "Level")
    Canvas(modifier) {
        val gap = size.width / (bars * 2 - 1)
        for (i in 0 until bars) {
            val h = size.height * (.22f + .78f * (.25f + .75f * phases[i].value) * level + .0f).coerceIn(.18f, 1f)
            drawRoundRect(color, Offset(i * gap * 2, (size.height - h) / 2), Size(gap, h), androidx.compose.ui.geometry.CornerRadius(gap / 2, gap / 2))
        }
    }
}

/** Rings spreading from the centre, as the phone looks for your PCs. */
@Composable fun Radar(color: Color, modifier: Modifier = Modifier) {
    val reduced = LocalReduced.current
    val transition = rememberInfiniteTransition(label = "Radar")
    val sweep by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "Sweep")
    Canvas(modifier) {
        val r = min(size.width, size.height) / 2
        for (k in 0 until 3) {
            val f = if (reduced) (k + 1) / 3f else ((sweep + k / 3f) % 1f)
            drawCircle(color.copy(alpha = (1f - f) * .5f), r * f, center, style = Stroke(1.5.dp.toPx()))
        }
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .22f), Color.Transparent), center, r * .5f), r * .5f, center)
        if (!reduced) {
            val a = sweep * 2 * PI.toFloat()
            drawArc(Brush.sweepGradient(listOf(Color.Transparent, color.copy(alpha = .0f), color.copy(alpha = .28f)), center), a * 180f / PI.toFloat() - 60f, 60f, true, Offset(center.x - r, center.y - r), Size(r * 2, r * 2))
        }
    }
}

/** A tick that draws itself. */
@Composable fun DrawnCheck(color: Color, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(480, easing = FastOutSlowInEasing)) }
    Canvas(modifier) {
        val a = Offset(size.width * .22f, size.height * .54f); val b = Offset(size.width * .42f, size.height * .72f); val c = Offset(size.width * .78f, size.height * .3f)
        val f = progress.value; val path = Path().apply { moveTo(a.x, a.y)
            if (f < .4f) lineTo(a.x + (b.x - a.x) * f / .4f, a.y + (b.y - a.y) * f / .4f) else { lineTo(b.x, b.y); val g = (f - .4f) / .6f; lineTo(b.x + (c.x - b.x) * g, b.y + (c.y - b.y) * g) } }
        drawPath(path, color, style = Stroke(size.minDimension * .09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A value that springs to its target unless motion is reduced. */
@Composable fun springy(target: Float, damping: Float = .72f, stiffness: Float = 380f): State<Float> =
    animateFloatAsState(target, if (LocalReduced.current) snap() else spring(dampingRatio = damping, stiffness = stiffness), label = "Springy")
