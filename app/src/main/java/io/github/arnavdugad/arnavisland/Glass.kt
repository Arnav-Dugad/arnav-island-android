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
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
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
 * Liquid glass, after iOS 26. Surfaces sample what is behind them: they bend it at their edges like a lens (Android 13+),
 * blur and saturate it (Android 12+), carry a rim of light that moves as you tilt the phone, and a faint sheen across
 * their top. Cards sample the ambient light; floating glass (the tab bar, the navigation bar, sheets) samples the whole
 * screen, so what scrolls beneath blurs and refracts through it. Older Android gets the same shapes, frosted.
 *
 * Kept light: the rim of light is drawn in its own layer (tilting the phone repaints only the rim, never the glass's
 * refraction), cards don't blur what is already a soft field of light, and the light behind stands still while the app
 * is hidden, a sheet covers it, or the battery saver is on.
 */
class GlassBackdrops(val ambient: Backdrop, val screen: Backdrop)
val LocalGlass = staticCompositionLocalOf<GlassBackdrops?> { null }
/** Liquid glass on (the default), or off: solid surfaces, calmer and lighter on the battery (Settings › Appearance). */
val LocalGlassOn = staticCompositionLocalOf { true }

/**
 * What covers the screen: while a sheet is open, the light behind it stands still. 1.7: while a finger is on the screen
 * (and a moment after, as a fling settles) it holds still too, so scrolling has all the phone's drawing to itself.
 */
object Scene { var sheets by mutableIntStateOf(0); var touching by mutableStateOf(false) }

enum class GlassLevel { Card, Bar, Control, Sheet, Island }

/** Test builds only: parts of the glass switched off one at a time, to measure what each costs (set from an intent). */
object GlassTuning { var off by mutableStateOf(emptySet<String>()) }

object GlassShapes {
    // 1.8: Apple's continuous corners, a little rounder: grouped cards, tiles, wells, sheets.
    val card = RoundedRectangle(26.dp)
    val tile = RoundedRectangle(22.dp)
    val inner = RoundedRectangle(14.dp)
    val sheet = RoundedRectangle(34.dp)
    val capsule = Capsule()
}

private val effectsSupported = Build.VERSION.SDK_INT >= 31 && Build.FINGERPRINT != "robolectric"

/** Glass behind this element. Without a backdrop it falls back to a frosted surface. */
@Composable fun Modifier.glass(shape: Shape = GlassShapes.card, level: GlassLevel = GlassLevel.Card, tint: Color = Color.Unspecified, rim: Boolean = true): Modifier {
    val t = LocalTokens.current; val glass = LocalGlass.current; val tilt = LocalTilt.current
    // Without the glass: a solid surface with a hairline edge.
    if (!LocalGlassOn.current) return this.clip(shape).background(fallbackSurface(t, level, tint)).border(.6.dp, if (t.dark) Color.White.copy(alpha = .08f) else Color.Black.copy(alpha = .06f), shape)
    val surface = glassSurface(t, level, tint)
    if (glass == null) return this.clip(shape).background(fallbackSurface(t, level, tint)).graphicsLayer { }.drawBehind { specularRim(shape, if (t.dark) .34f else .7f, .8.dp.toPx(), tilt()) }
    val backdrop = if (level == GlassLevel.Card) glass.ambient else glass.screen
    val lensable = shape is androidx.compose.foundation.shape.CornerBasedShape || shape.javaClass.name.startsWith("com.kyant.shapes")
    val density = LocalDensity.current
    val (blurDp, lensHeight, lensAmount) = when (level) {
        // A card samples the ambient light alone, already a soft field: blurring it again would change nothing.
        GlassLevel.Card -> Triple(0.dp, 10.dp, 14.dp)
        GlassLevel.Bar, GlassLevel.Island -> Triple(14.dp, 20.dp, 36.dp)
        GlassLevel.Control -> Triple(4.dp, 12.dp, 24.dp)
        GlassLevel.Sheet -> Triple(28.dp, 22.dp, 34.dp)
    }
    val rimAlpha = if (t.dark) .42f else .85f
    val shadowColor = Color.Black.copy(alpha = if (t.dark) .30f else .07f)
    val off = GlassTuning.off
    // Its shadow is drawn once and kept (a cached image, as Compose's drop shadow does): the backdrop library's own shadow
    // was blurred again on every frame the glass redrew, the glass's largest cost.
    val shadowRadius = when (level) { GlassLevel.Control -> 10.dp; GlassLevel.Sheet -> 36.dp; else -> 24.dp }
    val shadowed = if ("shadow" in off || "oldshadow" in off) this else this.dropShadow(shape, androidx.compose.ui.graphics.shadow.Shadow(radius = shadowRadius, color = shadowColor, offset = DpOffset(0.dp, shadowRadius / 5)))
    return shadowed.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            if (effectsSupported) {
                if ("vibrancy" !in off) vibrancy()
                if (blurDp > 0.dp && "blur" !in off) blur(with(density) { blurDp.toPx() })
                // The lens bends rounded shapes only (the library refuses others).
                if (!(level == GlassLevel.Card && "cardlens" in off) && "lens" !in off && lensable)
                    lens(with(density) { lensHeight.toPx() }, with(density) { lensAmount.toPx() }, depthEffect = level != GlassLevel.Card && "depth" !in off, chromaticAberration = level == GlassLevel.Bar && "chroma" !in off)
            }
        },
        highlight = null,
        shadow = if ("oldshadow" in off) ({ Shadow(radius = shadowRadius, color = shadowColor) }) else null,
        onDrawSurface = { drawRect(if (effectsSupported) surface else fallbackSurface(t, level, tint)); sheen(t.dark) },
    )
        // The rim follows the tilt in a layer of its own, so the refraction beneath isn't drawn again as the phone moves.
        .then(if ("rim" in off || !rim) Modifier else Modifier.graphicsLayer { }.drawBehind { specularRim(shape, rimAlpha, if (level == GlassLevel.Card) .7.dp.toPx() else 1.dp.toPx(), tilt()) })
}

/** A soft sheen across the glass's upper part, as light falls on a curved surface. */
private fun DrawScope.sheen(dark: Boolean) {
    drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) .05f else .18f), .4f to Color.Transparent, 1f to Color.Transparent))
}

/**
 * The rim of light. Its brightest point sits where a light above the phone would catch the edge, so it slides round the
 * glass as the phone tilts ([tilt], -1..1), with a fainter return on the opposite side.
 */
internal fun DrawScope.specularRim(shape: Shape, alpha: Float, width: Float, tilt: Offset) {
    // Nothing to draw on a surface too small to hold the rim (one that is still growing in, or collapsed).
    if (size.width <= width * 2 || size.height <= width * 2) return
    val angle = Math.toRadians(225.0 + tilt.x * 38.0 - tilt.y * 24.0)
    val cx = size.width / 2; val cy = size.height / 2; val r = maxOf(size.width, size.height) / 2
    val from = Offset(cx + (r * cos(angle)).toFloat(), cy + (r * sin(angle)).toFloat()); val to = Offset(cx * 2 - from.x, cy * 2 - from.y)
    val brush = Brush.linearGradient(0f to Color.White.copy(alpha = alpha), .3f to Color.White.copy(alpha = alpha * .14f), .7f to Color.White.copy(alpha = alpha * .04f), 1f to Color.White.copy(alpha = alpha * .4f), start = from, end = to)
    val half = width / 2
    inset(half, half, half, half) { drawOutline(shape.createOutline(size, layoutDirection, this), brush, style = Stroke(width)) }
}

private fun glassSurface(t: Tokens, level: GlassLevel, tint: Color): Color {
    val base = when (level) {
        GlassLevel.Card -> if (t.dark) Color(0x5C15181F) else Color(0xA6FFFFFF)
        GlassLevel.Bar -> if (t.dark) Color(0x70121419) else Color(0xB8F7F7FA)
        GlassLevel.Control -> if (t.dark) Color(0x1FFFFFFF) else Color(0x80FFFFFF)
        GlassLevel.Sheet -> if (t.dark) Color(0xA6121419) else Color(0xD1F5F5F8)
        GlassLevel.Island -> if (t.dark) Color(0xB3050608) else Color(0xCC0A0C10)
    }
    val strength = when { !tint.isSpecified -> 0f; level == GlassLevel.Control -> if (t.dark) .3f else .36f; else -> .2f }
    return if (tint.isSpecified) tint.copy(alpha = tint.alpha * strength).compositeOver(base) else base
}
private fun fallbackSurface(t: Tokens, level: GlassLevel, tint: Color): Color {
    val base = when (level) {
        GlassLevel.Card -> if (t.dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
        GlassLevel.Bar, GlassLevel.Sheet -> if (t.dark) Color(0xF21C1C1E) else Color(0xF5F9F9FB)
        GlassLevel.Control -> if (t.dark) Color(0xFF2C2C2E) else Color(0xFFE9E9EE)
        GlassLevel.Island -> Color(0xF2050608)
    }
    return if (tint.isSpecified) tint.copy(alpha = .16f).compositeOver(base) else base
}

@Composable fun GlassPanel(modifier: Modifier = Modifier, shape: Shape = GlassShapes.card, level: GlassLevel = GlassLevel.Card, tint: Color = Color.Unspecified, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.glass(shape, level, tint), content = content)
}

/**
 * The ambient light behind the glass, as Apple Music lights its player with the cover: a deep field, darkest at the
 * bottom, lit from above by slow-drifting colour from what plays on your PC. It breathes gently while music plays.
 * Updated up to 20 times a second, smooth for motion this slow and light on the battery; it stands still while the app
 * is hidden, a sheet covers it, a finger is on the screen, or [still] (the battery saver).
 *
 * 1.7: its pace follows what the phone draws smoothly: on a phone that starts to drop frames it eases to 15, 10, then 7
 * steps a second (the drift is too slow to look any different), and back up after a few smooth seconds.
 */
@Composable fun AmbientBackground(accent: Color, accent2: Color, deep: Color, playing: Boolean, modifier: Modifier = Modifier, still: Boolean = false) {
    val t = LocalTokens.current; val reduced = LocalReduced.current; val owner = LocalLifecycleOwner.current
    var phase by remember { mutableFloatStateOf(0f) }
    val paused = reduced || still || Scene.sheets > 0 || (Scene.touching && "notouchpause" !in GlassTuning.off) || "ambient" in GlassTuning.off
    LaunchedEffect(paused, owner) {
        if (paused) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Carries on from where it stood (a pause never makes it jump).
            val start = System.nanoTime() - (phase * 1e9).toLong()
            while (isActive) {
                phase = ((System.nanoTime() - start) / 1e9).toFloat()
                delay(if ("fixedpace" in GlassTuning.off) 50L else AmbientPace.step)
            }
        }
    }
    val breath by animateFloatAsState(if (playing && !reduced) 1f else 0f, tween(1400), label = "Breath")
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height; if (w <= 0f || h <= 0f) return@Canvas
        drawRect(Brush.verticalGradient(if (t.dark) listOf(lerp(deep, Color.Black, .45f), Color(0xFF050507), Color.Black) else listOf(lerp(deep, Color.White, .35f), Color(0xFFF2F2F7), Color(0xFFF2F2F7))))
        val colors = listOf(accent, accent2, lerp(accent, accent2, .5f), lerp(accent, deep, .4f))
        colors.forEachIndexed { i, color ->
            val speed = .035f + i * .011f
            val pulse = 1f + breath * .06f * sin(phase * 1.9f + i * 1.3f)
            val cx = w * (.15f + .7f * ((i * .41f + .1f) % 1f)) + w * .16f * sin(phase * speed * 6.283f + i * 1.7f)
            val cy = h * (.02f + .15f * i) + h * .06f * cos(phase * speed * 5.1f + i * 2.3f)
            val radius = w * (.68f + .12f * ((i + 1) % 3)) * pulse
            val a = (if (t.dark) .42f else .3f) * (if (i == 0) 1f else .78f)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = a), color.copy(alpha = a * .28f), Color.Transparent), Offset(cx, cy), radius), radius, Offset(cx, cy))
        }
        // A vignette keeps the edges calm and the glass readable.
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (t.dark) .4f else .04f)), Offset(w / 2, h * .35f), maxOf(w, h) * .85f))
    }
}

/**
 * How often the ambient light steps (ms), from how long the phone takes to draw its frames (Android's frame metrics):
 * frames that miss their display interval ease it down, a smooth run brings it back up.
 */
object AmbientPace {
    private val steps = longArrayOf(50, 66, 100, 140)
    @Volatile private var level = 0; private var slow = 0; private var smooth = 0
    val step get() = steps[level]
    /** One drawn frame: how long it took, and the display's frame interval (both ms). */
    fun frame(tookMs: Double, intervalMs: Double) {
        if (tookMs > intervalMs * 1.5) { slow++; smooth = 0 } else { smooth++; if (smooth % 4 == 0) slow = (slow - 1).coerceAtLeast(0) }
        if (slow >= 8 && level < steps.size - 1) { level++; slow = 0 }
        if (smooth >= 240 && level > 0) { level--; smooth = 0 }
    }
}

/** 1.8: a filled shape in [color], lit from above, with a rim of light: a prominent button, a selected chip. */
@Composable fun Modifier.filled(shape: Shape, color: Color): Modifier {
    val t = LocalTokens.current; val tilt = LocalTilt.current
    return this.dropShadow(shape, androidx.compose.ui.graphics.shadow.Shadow(radius = 14.dp, color = color.copy(alpha = if (t.dark) .32f else .26f), offset = DpOffset(0.dp, 4.dp)))
        .clip(shape).background(Brush.verticalGradient(listOf(lerp(color, Color.White, .12f), color)))
        .graphicsLayer { }.drawBehind { specularRim(shape, .4f, .8.dp.toPx(), tilt()) }
}

/**
 * A pressable capsule that springs down under the finger: glass, or (when [prominent]) filled with the accent as
 * Apple's prominent buttons are.
 */
@Composable fun GlassButton(onClick: () -> Unit, modifier: Modifier = Modifier, prominent: Boolean = false, enabled: Boolean = true, shape: Shape = GlassShapes.capsule,
                            contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 13.dp), description: String? = null, content: @Composable RowScope.() -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }; val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .95f else 1f, spring(dampingRatio = .55f, stiffness = 700f), label = "Press")
    Row(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .4f }
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
            contentDescription = description; progressBarRangeInfo = ProgressBarRangeInfo(local, 0f..1f)
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
 * The floating tab bar of iOS 26: a glass capsule whose selection is a drop of glass. The drop follows the pages as they
 * are swiped (so it glides continuously between tabs), stretches with its speed, swells under the finger and can be
 * dragged across the tabs. The chosen tab is tinted with the accent, as Apple's are.
 */
@Composable fun GlassTabBar(items: List<TabItem>, position: Float, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current; val scope = rememberCoroutineScope(); val reduced = LocalReduced.current
    val density = LocalDensity.current
    var width by remember { mutableIntStateOf(0) }
    val drop = remember { Animatable(position) }
    var dragging by remember { mutableStateOf(false) }; var touching by remember { mutableStateOf(false) }
    val select by rememberUpdatedState(onSelect)
    LaunchedEffect(position, dragging) { if (!dragging) { if (reduced) drop.snapTo(position) else drop.animateTo(position, spring(dampingRatio = .76f, stiffness = 900f)) } }
    val swell by animateFloatAsState(if (touching && !reduced) 1.14f else 1f, spring(dampingRatio = .5f, stiffness = 520f), label = "Swell")
    val stretch = (1f + abs(drop.velocity) * .1f).coerceAtMost(1.45f)
    val inner = 5.dp
    Box(modifier.height(64.dp).glass(GlassShapes.capsule, GlassLevel.Bar).onSizeChanged { width = it.width }.padding(inner)
        .pointerInput(items.size) { detectTapGestures(onPress = { touching = true; tryAwaitRelease(); touching = false }) }
        .pointerInput(items.size) {
            val cell = size.width.toFloat() / items.size; var last = -1
            detectHorizontalDragGestures(
                onDragStart = { dragging = true; touching = true; last = drop.value.roundToInt() },
                onDragEnd = { val target = drop.value.roundToInt().coerceIn(0, items.size - 1); dragging = false; touching = false; select(target) },
                onDragCancel = { dragging = false; touching = false },
            ) { change, amount ->
                change.consume()
                val next = (drop.value + amount / cell).coerceIn(-.2f, items.size - .8f); scope.launch { drop.snapTo(next) }
                val nearest = next.roundToInt().coerceIn(0, items.size - 1); if (nearest != last) { last = nearest; haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }
            }
        }) {
        if (width > 0) {
            val cell = with(density) { ((width - (inner * 2).roundToPx()) / items.size.toFloat()).toDp() }
            Box(Modifier.width(cell).fillMaxHeight().offset { IntOffset((drop.value * cell.toPx()).roundToInt(), 0) }
                .graphicsLayer { scaleX = stretch * swell; scaleY = swell / stretch.coerceAtMost(1.2f) }
                .glass(GlassShapes.capsule, GlassLevel.Control, if (t.dark) Color.White.copy(alpha = .5f) else t.text.copy(alpha = .25f)))
        }
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { index, item ->
                val near = (1f - abs(drop.value - index)).coerceIn(0f, 1f)
                val color = lerp(t.text.copy(alpha = .78f), t.accent, near)
                Column(Modifier.weight(1f).fillMaxHeight().clip(GlassShapes.capsule)
                    .clickable(role = Role.Tab) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(index) }
                    .semantics { this.selected = index == position.roundToInt(); contentDescription = item.label },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(item.icon, null, tint = color, modifier = Modifier.size(24.dp).graphicsLayer { val s = 1f + .08f * near; scaleX = s; scaleY = s })
                    Spacer(Modifier.height(1.dp))
                    Text(item.label, color = color, fontSize = 10.sp, fontWeight = if (near > .5f) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, letterSpacing = 0.sp)
                }
            }
        }
    }
}
