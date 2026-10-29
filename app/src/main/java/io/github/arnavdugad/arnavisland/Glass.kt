package io.github.arnavdugad.arnavisland

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 1.10: solid surfaces, as Apple's own apps draw them: grouped cards on the system's grouped background, flat fills for
 * controls, opaque bars with a hairline. Nothing blurs, bends or catches the light. The names (glass, GlassPanel, …) stay
 * from the liquid glass of 1.8 so every screen keeps its calls.
 */
class GlassBackdrops(val ambient: Any?, val screen: Any?)
val LocalGlass = staticCompositionLocalOf<GlassBackdrops?> { null }
/** Kept for the screens that ask; the glass is gone, so always false. */
val LocalGlassOn = staticCompositionLocalOf { false }

/** What covers the screen (sheets open, a finger down). */
object Scene { var sheets by mutableIntStateOf(0); var touching by mutableStateOf(false) }

enum class GlassLevel { Card, Bar, Control, Sheet, Island }

/** Test builds only (set from an intent); nothing reads it now the glass is gone. */
object GlassTuning { var off by mutableStateOf(emptySet<String>()) }

object GlassShapes {
    // Apple's continuous corners: grouped cards, tiles, wells, sheets.
    val card = RoundedRectangle(18.dp)
    val tile = RoundedRectangle(16.dp)
    val inner = RoundedRectangle(12.dp)
    val sheet = RoundedRectangle(26.dp)
    val capsule = Capsule()
}

/** A solid surface for [level], as iOS's grouped lists, fills and bars are; [tint] washes it lightly with a colour. */
@Composable fun Modifier.glass(shape: Shape = GlassShapes.card, level: GlassLevel = GlassLevel.Card, tint: Color = Color.Unspecified, rim: Boolean = true): Modifier =
    this.clip(shape).background(surface(LocalTokens.current, level, tint))

internal fun surface(t: Tokens, level: GlassLevel, tint: Color): Color {
    val base = when (level) {
        GlassLevel.Card -> if (t.dark) Color(0xFF1C1C1E) else Color.White
        GlassLevel.Bar -> if (t.dark) Color(0xFF121212) else Color(0xFFF9F9F9)
        GlassLevel.Control -> if (t.dark) Color(0x3D787880) else Color(0x1F787880)
        GlassLevel.Sheet -> if (t.dark) Color(0xFF0E0E10) else Color(0xFFF2F2F7)
        GlassLevel.Island -> Color(0xFF0B0B0D)
    }
    if (!tint.isSpecified) return base
    return if (level == GlassLevel.Control) tint.copy(alpha = tint.alpha * if (t.dark) .26f else .16f) else tint.copy(alpha = tint.alpha * .12f).compositeOver(base)
}

@Composable fun GlassPanel(modifier: Modifier = Modifier, shape: Shape = GlassShapes.card, level: GlassLevel = GlassLevel.Card, tint: Color = Color.Unspecified, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.glass(shape, level, tint), content = content)
}

/** The page behind everything: the grouped background of Apple's Settings, black in the dark. */
@Composable fun AmbientBackground(accent: Color, accent2: Color, deep: Color, playing: Boolean, modifier: Modifier = Modifier, still: Boolean = false) {
    val t = LocalTokens.current
    Box(modifier.fillMaxSize().background(if (t.dark) Color.Black else Color(0xFFF2F2F7)))
}

/** How long the phone takes to draw its frames (Android's frame metrics); kept for the test builds that read it. */
object AmbientPace {
    private val steps = longArrayOf(50, 66, 100, 140)
    @Volatile private var level = 0; private var slow = 0; private var smooth = 0
    val step get() = steps[level]
    fun frame(tookMs: Double, intervalMs: Double) {
        if (tookMs > intervalMs * 1.5) { slow++; smooth = 0 } else { smooth++; if (smooth % 4 == 0) slow = (slow - 1).coerceAtLeast(0) }
        if (slow >= 8 && level < steps.size - 1) { level++; slow = 0 }
        if (smooth >= 240 && level > 0) { level--; smooth = 0 }
    }
}

/** A shape filled flat with [color]: a prominent button, a selected chip. */
@Composable fun Modifier.filled(shape: Shape, color: Color): Modifier = this.clip(shape).background(color)

/**
 * A pressable capsule that springs down under the finger: glass, or (when [prominent]) filled with the accent as
 * Apple's prominent buttons are.
 */
@Composable fun GlassButton(onClick: () -> Unit, modifier: Modifier = Modifier, prominent: Boolean = false, enabled: Boolean = true, shape: Shape = GlassShapes.capsule,
                            contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 13.dp), description: String? = null, content: @Composable RowScope.() -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }; val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .7f, stiffness = 900f), label = "Press")
    val dim by animateFloatAsState(if (pressed) .55f else 1f, tween(if (pressed) 60 else 220), label = "Highlight")
    Row(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = (if (enabled) 1f else .4f) * dim }
            .then(if (prominent) Modifier.filled(shape, t.accent) else Modifier.glass(shape, GlassLevel.Control))
            .clickable(source, null, enabled = enabled, role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() }
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) { CompositionLocalProvider(LocalContentColor provides if (prominent) t.onAccent else t.text) { content() } }
}

@Composable fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, iconSize: Dp = 20.dp, enabled: Boolean = true, prominent: Boolean = false, tint: Color = Color.Unspecified) {
    GlassButton(onClick, modifier.size(size), prominent = prominent, enabled = enabled, contentPadding = PaddingValues(0.dp), description = description) {
        Icon(icon, null, tint = if (tint.isSpecified) tint else LocalContentColor.current, modifier = Modifier.size(iconSize))
    }
}

/** A chip: a small fact (with its icon) on glass, or a choice when [onClick] is set, filled with the accent when chosen. */
@Composable fun GlassChip(label: String, modifier: Modifier = Modifier, icon: ImageVector? = null, selected: Boolean = false, iconTint: Color = Color.Unspecified, onClick: (() -> Unit)? = null) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    val color by animateColorAsState(if (selected) t.onAccent else if (onClick != null) t.text else t.muted, label = "Chip")
    Row(
        modifier.then(if (selected) Modifier.filled(GlassShapes.capsule, t.accent) else Modifier.glass(GlassShapes.capsule, GlassLevel.Control))
            .then(if (onClick != null) Modifier.clickable(role = Role.RadioButton) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onClick() }.semantics { this.selected = selected } else Modifier)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = if (iconTint.isSpecified && !selected) iconTint else color, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)) }
        Text(label, style = Type.caption, color = color, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

/**
 * Apple's switch, in liquid glass: its knob is a drop that stretches as it slides across, and the track fills with
 * Apple's green.
 */
@Composable fun GlassSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current; val reduced = LocalReduced.current
    val position = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked) { if (reduced) position.snapTo(if (checked) 1f else 0f) else position.animateTo(if (checked) 1f else 0f, spring(dampingRatio = .66f, stiffness = 560f)) }
    val stretch = 1f + (abs(position.velocity) * .05f).coerceAtMost(.4f)
    val fill = lerp(t.track, t.good, position.value.coerceIn(0f, 1f))
    Box(modifier.size(52.dp, 32.dp).clip(GlassShapes.capsule).background(fill)
        .clickable(enabled = enabled, role = Role.Switch) { haptics.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn); onChange(!checked) }
        .semantics { toggleableState = if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off }
        .graphicsLayer { alpha = if (enabled) 1f else .45f }.padding(2.dp)) {
        Box(Modifier.size(28.dp).offset { IntOffset((position.value * 20.dp.toPx()).roundToInt(), 0) }
            .graphicsLayer { scaleX = stretch; scaleY = 1f / stretch.coerceAtMost(1.18f); shadowElevation = 2.5.dp.toPx(); shape = GlassShapes.capsule; clip = true }
            .background(Color.White))
    }
}

/**
 * A slider as Apple Music's scrubber: a thin capsule track that thickens under the finger, filled to where it is. Haptic
 * ticks mark each tenth, or each of [ticks] (fractions: a song's lyric lines) when given, so scrubbing through a song
 * clicks at every line. [onChange] follows the finger; [onDone] gets the final value.
 */
@Composable fun GlassSlider(value: Float, onChange: (Float) -> Unit, onDone: (Float) -> Unit, modifier: Modifier = Modifier, color: Color = LocalTokens.current.accent, height: Dp = 7.dp, description: String = "", ticks: List<Float>? = null) {
    val marks by rememberUpdatedState(ticks)
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }
    val change by rememberUpdatedState(onChange); val done by rememberUpdatedState(onDone)
    LaunchedEffect(value) { if (!dragging) local = value }
    val thick by animateDpAsState(if (dragging) height * 1.9f else height, spring(dampingRatio = .65f, stiffness = 600f), label = "Thick")
    var width by remember { mutableIntStateOf(1) }
    Box(modifier.height(32.dp).onSizeChanged { width = it.width.coerceAtLeast(1) }
        .semantics {
            contentDescription = description; progressBarRangeInfo = ProgressBarRangeInfo(local.takeIf { !it.isNaN() } ?: 0f, 0f..1f)
            setProgress { v -> local = v.coerceIn(0f, 1f); change(local); done(local); true }
        }
        .pointerInput(Unit) {
            detectTapGestures(onPress = { p -> dragging = true; local = (p.x / width).coerceIn(0f, 1f); change(local); val released = tryAwaitRelease(); dragging = false; if (released) done(local) })
        }
        .pointerInput(Unit) {
            var tick = (local * 10).toInt(); var last = local
            detectHorizontalDragGestures(onDragStart = { dragging = true; last = local; tick = (local * 10).toInt() }, onDragEnd = { dragging = false; done(local) }, onDragCancel = { dragging = false; done(local) }) { event, amount ->
                event.consume(); local = (local + amount / width).coerceIn(0f, 1f); change(local)
                val m = marks
                if (!m.isNullOrEmpty()) {
                    val lo = minOf(last, local); val hi = maxOf(last, local)
                    if (hi > lo && m.any { it > lo && it <= hi }) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                } else { val now = (local * 10).toInt(); if (now != tick) { tick = now; haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) } }
                last = local
            }
        }, contentAlignment = Alignment.CenterStart) {
        Canvas(Modifier.fillMaxWidth().height(thick)) {
            val r = size.height / 2; val w = size.width * local
            drawRoundRect(t.track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
            if (w > 0f) drawRoundRect(color, size = androidx.compose.ui.geometry.Size(w.coerceAtLeast(size.height), size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        }
    }
}

data class TabItem(val label: String, val icon: ImageVector)

/**
 * The tab bar, as iOS draws it without glass: full width, opaque, a hairline along its top; each tab an icon over its
 * name, the chosen one in the accent. It sits above the navigation bar's inset.
 */
@Composable fun GlassTabBar(items: List<TabItem>, position: Float, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    val chosen = position.roundToInt()
    Column(modifier.fillMaxWidth().background(surface(t, GlassLevel.Bar, Color.Unspecified))) {
        Box(Modifier.fillMaxWidth().height(.5.dp).background(t.hairline))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(TabBarHeight).widthIn(max = 560.dp).align(Alignment.CenterHorizontally)) {
            items.forEachIndexed { index, item ->
                val color by animateColorAsState(if (index == chosen) t.accent else if (t.dark) Color(0xFF8E8E93) else Color(0xFF8A8A8E), tween(160), label = "Tab")
                Column(Modifier.weight(1f).fillMaxHeight()
                    .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) { if (index != chosen) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(index) }
                    .semantics { this.selected = index == chosen; contentDescription = item.label },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(item.icon, null, tint = color, modifier = Modifier.size(25.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(item.label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, letterSpacing = 0.sp)
                }
            }
        }
    }
}
/** The tab bar's own height, above the navigation bar's inset. */
val TabBarHeight = 52.dp
