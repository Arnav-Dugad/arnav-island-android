package io.github.arnavdugad.arnavisland

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** The dial's arc: from the lower left (135°, clockwise on screen) round to the lower right, 270° in all. */
const val DIAL_START = 135f
const val DIAL_SWEEP = 270f
/** How far a finger turned between two angles (degrees), the short way round: -180..180. */
fun dialTurn(from: Float, to: Float): Float { var d = (to - from) % 360f; if (d > 180f) d -= 360f; if (d <= -180f) d += 360f; return d }
/** The detent a value sits in: 5% steps, 0..20. */
fun detent(value: Float) = (value * 20f + .5f).toInt().coerceIn(0, 20)

/**
 * The PC's volume as a dial: twist it (from anywhere on it) and the level follows the finger's turn, with a soft click
 * at every 5% and a firmer one at each quarter; the arc glows brighter as it rises. Tap the middle to mute.
 */
@Composable fun VolumeDial(value: Float, muted: Boolean, onChange: (Float) -> Unit, onDone: (Float) -> Unit, onMute: () -> Unit, modifier: Modifier = Modifier, size: Dp = 176.dp, description: String = "Volume") {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    var turning by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { if (!turning) local = value }
    val change by rememberUpdatedState(onChange); val done by rememberUpdatedState(onDone)
    val shown by animateFloatAsState(local, if (turning) spring(stiffness = 3000f) else spring(dampingRatio = .8f, stiffness = 300f), label = "Dial")
    val lift by animateFloatAsState(if (turning) 1f else 0f, spring(dampingRatio = .6f, stiffness = 500f), label = "Lift")
    Box(modifier.size(size)
        .semantics { contentDescription = description; progressBarRangeInfo = ProgressBarRangeInfo(local, 0f..1f)
            setProgress { v -> local = v.coerceIn(0f, 1f); done(local); true } }
        .pointerInput(Unit) {
            var angle = 0f; var step = 0
            detectDragGestures(
                onDragStart = { p -> turning = true; angle = Math.toDegrees(atan2((p.y - this.size.height / 2f).toDouble(), (p.x - this.size.width / 2f).toDouble())).toFloat(); step = detent(local) },
                onDragEnd = { turning = false; done(local) }, onDragCancel = { turning = false; done(local) },
            ) { change, _ ->
                change.consume()
                val p = change.position; val a = Math.toDegrees(atan2((p.y - this.size.height / 2f).toDouble(), (p.x - this.size.width / 2f).toDouble())).toFloat()
                local = (local + dialTurn(angle, a) / DIAL_SWEEP).coerceIn(0f, 1f); angle = a; change(local)
                val now = detent(local)
                if (now != step) { step = now; haptics.performHapticFeedback(if (now % 5 == 0) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick) }
            }
        }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 11.dp.toPx(); val r = this.size.minDimension / 2 - stroke * 1.6f; val c = center
            val tl = Offset(c.x - r, c.y - r); val box = Size(r * 2, r * 2)
            val level = if (muted) 0f else shown
            // The glow round the edge, brighter as the level rises (and while it turns).
            drawCircle(Brush.radialGradient(listOf(Color.Transparent, t.accent.copy(alpha = (.1f + .32f * level + .15f * lift)), Color.Transparent), c, r + stroke * 2.4f), r + stroke * 2.4f, c)
            drawArc(t.track, DIAL_START, DIAL_SWEEP, false, tl, box, style = Stroke(stroke, cap = StrokeCap.Round))
            if (level > 0f) drawArc(Brush.sweepGradient(listOf(t.accent.copy(alpha = .55f), t.accent, t.accent.copy(alpha = .55f)), c), DIAL_START, DIAL_SWEEP * level, false, tl, box, style = Stroke(stroke, cap = StrokeCap.Round))
            // The detents: a dot every 10%, lit up to the level.
            for (k in 0..10) {
                val a = Math.toRadians((DIAL_START + DIAL_SWEEP * k / 10f).toDouble()); val rr = r + stroke * 1.25f
                val p = Offset(c.x + (rr * cos(a)).toFloat(), c.y + (rr * sin(a)).toFloat())
                drawCircle(if (k / 10f <= level + .001f) t.accent else t.faint.copy(alpha = .5f), if (k % 5 == 0) 2.2.dp.toPx() else 1.5.dp.toPx(), p)
            }
            // The knob, at the level.
            val a = Math.toRadians((DIAL_START + DIAL_SWEEP * level).toDouble())
            val k = Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
            drawCircle(Brush.radialGradient(listOf(t.accent.copy(alpha = .7f), Color.Transparent), k, stroke * (1.8f + lift)), stroke * (1.8f + lift), k)
            drawCircle(Color.Black.copy(alpha = if (t.dark) .3f else .16f), stroke * (.62f + .12f * lift) + 1.dp.toPx(), k + Offset(0f, 1.dp.toPx()))
            drawCircle(Color.White, stroke * (.62f + .12f * lift), k)
        }
        Column(Modifier.size(size * .52f).glass(CircleShape, GlassLevel.Control).clickable(role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onMute() }
            .semantics { contentDescription = if (muted) "Sound on" else "Mute" },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(if (muted) "–" else "${(local * 100 + .5f).toInt()}", style = Type.digits.copy(fontSize = 30.sp), color = t.text)
            Text(if (muted) "Muted" else "Volume", style = Type.micro, color = t.muted)
        }
    }
}
