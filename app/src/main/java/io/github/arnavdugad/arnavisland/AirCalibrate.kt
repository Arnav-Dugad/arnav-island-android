package io.github.arnavdugad.arnavisland

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 1.9 (#36): calibrating the air mouse, in place of its pad. First the phone is held still for two seconds while a ring
 * fills (it learns the gyroscope's drift; moving starts the ring again; a phone without a gyroscope skips this), then it
 * is swept once, at an easy pace, from the left edge of the PC's screen to the right: the speed is set so that sweep
 * crosses the screen. Both are kept. [onDone] gets the new sensitivity, or null when it was cancelled.
 */
@Composable fun ColumnScope.AirCalibration(mouse: AirMouse, onDone: (Float?) -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current; val reduced = LocalReduced.current
    var step by remember { mutableIntStateOf(if (mouse.hasGyro) 0 else 1) }
    var still by remember { mutableFloatStateOf(0f) }
    var swept by remember { mutableFloatStateOf(0f) }
    var note by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableFloatStateOf(mouse.sensitivity) }
    var attempt by remember { mutableIntStateOf(0) }
    DisposableEffect(step, attempt) {
        var over = false
        when (step) {
            0 -> {
                // Two seconds with every axis under ~7°/s: the average is the drift.
                var time = 0f; val sum = FloatArray(3); still = 0f
                mouse.probe = AirMouse.Probe { raw, _, _, dt ->
                    if (over || raw == null) return@Probe
                    if (abs(raw[0]) > .12f || abs(raw[1]) > .12f || abs(raw[2]) > .12f) {
                        if (time > .3f) note = "Keep it still"
                        time = 0f; sum.fill(0f); still = 0f; return@Probe
                    }
                    time += dt; for (i in 0..2) sum[i] += raw[i] * dt
                    val shown = (time / 2f).coerceAtMost(1f); if (shown - still >= .02f) still = shown
                    if (time >= 2f) {
                        over = true; mouse.calibrated(sum[0] / time, sum[1] / time, sum[2] / time)
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm); note = null; step = 1
                    }
                }
            }
            1 -> {
                // From the first brisk turn until it has rested for 0.2 s (or 4 s at most).
                val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val dts = ArrayList<Float>()
                var going = false; var calm = 0f; var time = 0f; var angle = 0f; swept = 0f
                mouse.probe = AirMouse.Probe { _, x, y, dt ->
                    if (over) return@Probe
                    if (!going) { if (abs(x) < 25f) return@Probe; going = true; note = null; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                    xs += x; ys += y; dts += dt; time += dt; angle += x * dt; if (abs(angle - swept) >= .5f) swept = angle
                    calm = if (abs(x) < 10f) calm + dt else 0f
                    if (calm < .2f && time < 4f) return@Probe
                    val again = { why: String -> note = why; going = false; calm = 0f; time = 0f; angle = 0f; swept = 0f; xs.clear(); ys.clear(); dts.clear(); haptics.performHapticFeedback(HapticFeedbackType.Reject) }
                    when {
                        angle < -10f -> again("That went right to left. Start at the left edge")
                        angle < 12f -> again("A little wider, edge to edge")
                        else -> {
                            val s = AirMouse.sensitivityFor(AirMouse.travel(xs.toFloatArray(), ys.toFloatArray(), dts.toFloatArray()))
                            if (s == null) again("Once more, a little quicker")
                            else {
                                over = true; mouse.sensitivity = s; result = s
                                runCatching { Hub.prefs.edit().putFloat(AirMouse.PREF, s).apply() }
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm); step = 2
                            }
                        }
                    }
                }
            }
        }
        onDispose { over = true; mouse.probe = null }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Calibrate", style = Type.title2, color = t.text)
            Text(when (step) { 0 -> "Step 1 of 2"; 1 -> if (mouse.hasGyro) "Step 2 of 2" else "One step"; else -> "Done" }, style = Type.caption, color = t.muted)
        }
        if (step < 2) GlassButton({ onDone(null) }) { Text("Cancel", style = Type.bodyStrong) }
    }
    Spacer(Modifier.height(12.dp))
    AnimatedContent(step, Modifier.fillMaxWidth().weight(1f), transitionSpec = {
        if (reduced) EnterTransition.None togetherWith ExitTransition.None else fadeIn(tween(260, 80)) togetherWith fadeOut(tween(140))
    }, label = "Calibrate") { s ->
        Column(Modifier.fillMaxSize().glass(GlassShapes.card, GlassLevel.Control).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            when (s) {
                0 -> {
                    Box(Modifier.size(176.dp), contentAlignment = Alignment.Center) {
                        ProgressRing(still, t.accent, t.track, Modifier.fillMaxSize(), stroke = 9.dp)
                        Icon(Icons.Rounded.PanTool, null, tint = if (still > 0f) t.text else t.faint, modifier = Modifier.size(44.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("Hold still", style = Type.headline, color = t.text)
                    Spacer(Modifier.height(4.dp))
                    Text("Point at your PC and hold still", style = Type.caption, color = t.muted, textAlign = TextAlign.Center)
                }
                1 -> {
                    // A screen, and the pointer crossing it as the sweep goes (a comfortable sweep is ~30-50°).
                    val across = (swept / 40f).coerceIn(0f, 1f)
                    Canvas(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                        val r = 14.dp.toPx(); val w = 2.dp.toPx()
                        drawRoundRect(t.fill, cornerRadius = CornerRadius(r))
                        drawRoundRect(t.hairline, cornerRadius = CornerRadius(r), style = Stroke(w))
                        val y = size.height / 2; val x0 = 26.dp.toPx(); val x1 = size.width - 26.dp.toPx(); val x = x0 + (x1 - x0) * across
                        drawLine(t.track, Offset(x0, y), Offset(x1, y), 4.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(t.accent, Offset(x0, y), Offset(x, y), 4.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                        drawCircle(Brush.radialGradient(listOf(t.accent.copy(alpha = .45f), Color.Transparent), Offset(x, y), 30.dp.toPx()), 30.dp.toPx(), Offset(x, y))
                        drawCircle(Color.White, 8.dp.toPx(), Offset(x, y))
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = t.accent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                        Text("Sweep across", style = Type.headline, color = t.text)
                        if (abs(swept) >= 1f) { Spacer(Modifier.width(10.dp)); RollingNumber("${abs(swept).roundToInt()}°", Type.headline, t.muted) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Point at the left edge, then sweep to the right", style = Type.caption, color = t.muted, textAlign = TextAlign.Center)
                }
                else -> {
                    Icon(Icons.Rounded.CheckCircle, null, tint = t.good, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("Calibrated", style = Type.headline, color = t.text)
                    Spacer(Modifier.height(4.dp))
                    Text(listOfNotNull("Speed ${(result * 100).roundToInt()}%", if (mouse.hasGyro) "drift %.2f°/s".format(mouse.drift) else null).joinToString("  ·  "), style = Type.caption, color = t.muted, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassButton({ note = null; step = if (mouse.hasGyro) 0 else 1; attempt++ }) { Text("Again", style = Type.bodyStrong) }
                        GlassButton({ onDone(result) }, prominent = true) { Text("Done", style = Type.bodyStrong) }
                    }
                }
            }
            note?.let { Spacer(Modifier.height(12.dp)); Text(it, style = Type.caption, color = t.warn, textAlign = TextAlign.Center) }
        }
    }
}
