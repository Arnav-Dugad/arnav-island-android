package io.github.arnavdugad.arnavisland

import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arnavdugad.arnavisland.link.PeerView
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A screen's large title, as on iOS, with an optional small line above it; it scrolls away into the navigation bar. */
@Composable fun ScreenTitle(title: String, over: String? = null, trailing: @Composable (() -> Unit)? = null) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            if (over != null) Text(over, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, style = Type.hero, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
        }
        trailing?.invoke()
    }
}

/** A grouped list's header, as Apple's are: small, in capitals, over the card it names; [trailing] for a link or a fold. */
@Composable fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable (RowScope.() -> Unit)? = null) {
    val t = LocalTokens.current
    Row(modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 26.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = Type.caption.copy(fontWeight = FontWeight.Medium, letterSpacing = .2.sp), color = t.muted, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke(this)
    }
}

/** A grouped section: its header, its rows on one glass card, and a footnote under it. */
@Composable fun Section(title: String?, modifier: Modifier = Modifier, footer: String? = null, trailing: @Composable (RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTokens.current
    Column(modifier.fillMaxWidth()) {
        if (title != null) SectionLabel(title, trailing = trailing) else Spacer(Modifier.height(20.dp))
        GlassPanel(Modifier.fillMaxWidth()) { Column(content = content) }
        if (footer != null) Text(footer, style = Type.caption, color = t.muted, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 7.dp))
    }
}

/** An icon on a small rounded square of solid colour, white on it: as Apple's Settings marks each row. */
@Composable fun IconTile(icon: ImageVector, color: Color, modifier: Modifier = Modifier, size: Dp = 30.dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * .27f)).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.lerp(color, Color.White, .1f), color))),
        contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * .6f)) }
}

/** A pressable glass tile: an icon on its colour, a title and a line under it. */
@Composable fun GlassTile(icon: ImageVector, title: String, detail: String, modifier: Modifier = Modifier, tint: Color = LocalTokens.current.accent, enabled: Boolean = true, onClick: () -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }; val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .55f, stiffness = 650f), label = "Tile")
    Column(modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .45f }.glass(GlassShapes.tile)
        .clickable(source, null, enabled = enabled, role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() }.padding(14.dp)) {
        IconTile(icon, tint, size = 36.dp)
        Spacer(Modifier.height(12.dp))
        Text(title, style = Type.bodyStrong, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, style = Type.caption, color = t.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 1.3: how a paired device is reached, for its quality ring: [fraction] of the ring lit (whole on this Wi-Fi or directly,
 * most of the way through the relay, a short arc when that's weak), [kind] 0 away, 1 weak, 2 relay, 3 direct or here, and a
 * line saying so (the relays' count and the round trip over the internet).
 */
data class Quality(val fraction: Float, val kind: Int, val text: String)
fun quality(p: PeerView): Quality {
    val ms = if (p.rtt > 0) "  ·  ${p.rtt.roundToInt()} ms" else ""
    val relays = if (p.relays == 1) "1 free relay" else "${p.relays} free relays"
    return when {
        !p.online -> Quality(0f, 0, "Away")
        !p.internet -> Quality(1f, 3, "On this Wi-Fi")
        p.path == 2 -> Quality(1f, 3, (if (p.v6) "Direct over IPv6" else "Direct") + ms)
        p.rtt >= 600 || p.relays <= 1 -> Quality(.3f, 1, "Weak  ·  through $relays$ms")
        else -> Quality(.62f, 2, "Relay  ·  through $relays$ms")
    }
}
@Composable fun qualityColor(kind: Int): Color { val t = LocalTokens.current; return when (kind) { 3 -> t.good; 2 -> t.warn; 1 -> t.danger; else -> t.faint } }
/** The ring itself: its arc sweeps (and changes colour) as the connection gets better or worse. */
@Composable fun QualityRing(q: Quality, modifier: Modifier = Modifier, stroke: Dp = 2.dp) {
    val color by animateColorAsState(qualityColor(q.kind), tween(600), label = "RingColor")
    val sweep by animateFloatAsState(q.fraction, spring(dampingRatio = .8f, stiffness = 90f), label = "RingSweep")
    val track = LocalTokens.current.faint.copy(alpha = .28f)
    androidx.compose.foundation.Canvas(modifier) {
        val w = stroke.toPx(); val d = minOf(size.width, size.height) - w
        val tl = androidx.compose.ui.geometry.Offset((size.width - d) / 2, (size.height - d) / 2); val box = androidx.compose.ui.geometry.Size(d, d)
        drawArc(track, 0f, 360f, false, tl, box, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        // Whole: one clean circle; part of the way: an arc from the top with round ends.
        if (sweep >= .995f) drawArc(color, 0f, 360f, false, tl, box, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        else if (sweep > 0f) drawArc(color, -90f, 360f * sweep, false, tl, box, style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

/**
 * A row of a grouped list, as in Apple's Settings: an icon on its colour, a title and a line under it, and whatever goes
 * on the right ([chevron]: it opens something). [ring]: a device's connection, round its icon.
 */
@Composable fun GlassRow(icon: ImageVector, title: String, detail: String? = null, modifier: Modifier = Modifier, tint: Color = LocalTokens.current.accent, onClick: (() -> Unit)? = null,
                         ring: Quality? = null, chevron: Boolean = false, value: String? = null, trailing: @Composable (() -> Unit)? = null) {
    val t = LocalTokens.current
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).then(if (onClick != null) Modifier.clickable(role = Role.Button) { onClick() } else Modifier).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ring != null) Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            QualityRing(ring, Modifier.fillMaxSize())
            IconTile(icon, tint, Modifier.clip(CircleShape), size = 26.dp)
        }
        else IconTile(icon, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrEmpty()) Text(detail, style = Type.caption, color = t.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) { Spacer(Modifier.width(8.dp)); Text(value, style = Type.body, color = t.muted, maxLines = 1) }
        if (trailing != null) { Spacer(Modifier.width(10.dp)); trailing() }
        if (chevron) { Spacer(Modifier.width(6.dp)); Chevron() }
    }
}
/** The small arrow of a row that opens something. */
@Composable fun Chevron(modifier: Modifier = Modifier) { Icon(Icons.Rounded.ChevronRight, null, tint = LocalTokens.current.faint, modifier = modifier.size(20.dp)) }
/** A separator between rows, inset to where the text starts. */
@Composable fun Hairline(start: Dp = 60.dp) { Box(Modifier.fillMaxWidth().padding(start = start).height(.5.dp).background(LocalTokens.current.hairline)) }

/**
 * 1.5: a round glass button that steps once when tapped and keeps stepping while held (faster after a moment), with a
 * tick each step: volume up and down.
 */
@Composable fun StepButton(icon: ImageVector, description: String, onStep: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, enabled: Boolean = true) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current; val step by rememberUpdatedState(onStep)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) .9f else 1f, spring(dampingRatio = .5f, stiffness = 650f), label = "Step")
    val scope = rememberCoroutineScope()
    Box(modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .42f }.glass(GlassShapes.capsule, GlassLevel.Control)
        .semantics { contentDescription = description; role = Role.Button; onClick { step(); true } }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(onPress = {
                pressed = true; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); step()
                val repeat = scope.launch { delay(420); var gap = 140L; while (true) { haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick); step(); delay(gap); gap = (gap - 12).coerceAtLeast(70) } }
                tryAwaitRelease(); repeat.cancel(); pressed = false
            })
        }, contentAlignment = Alignment.Center) { Icon(icon, null, tint = t.text, modifier = Modifier.size(size * .46f)) }
}

/** A small dot that glows and breathes while something is here. */
@Composable fun LiveDot(on: Boolean, modifier: Modifier = Modifier, size: Dp = 9.dp) {
    val t = LocalTokens.current; val reduced = LocalReduced.current
    val pulse by rememberInfiniteTransition(label = "Dot").animateFloat(1f, 2.3f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "Pulse")
    Box(modifier.size(size * 2.4f), contentAlignment = Alignment.Center) {
        if (on && !reduced) Box(Modifier.size(size).graphicsLayer { scaleX = pulse; scaleY = pulse; alpha = (2.3f - pulse) / 1.3f * .5f }.clip(CircleShape).background(t.good))
        Box(Modifier.size(size).clip(CircleShape).background(if (on) t.good else t.faint))
    }
}

fun clock(seconds: Double): String { val s = seconds.coerceAtLeast(0.0).toLong(); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    val m = (now - at) / 60_000
    return when { m < 1 -> "Just now"; m < 60 -> "$m min ago"; m < 24 * 60 -> "${m / 60} h ago"; m < 48 * 60 -> "Yesterday"; else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(at)) }
}
fun rateText(bytes: Double) = when { bytes < 1024 -> "%.0f B/s".format(bytes); bytes < 1048576 -> "%.0f KB/s".format(bytes / 1024); else -> "%.1f MB/s".format(bytes / 1048576) }
fun leftText(seconds: Double) = when { seconds.isNaN() || seconds < 0 || seconds > 360000 -> ""; seconds < 60 -> "${seconds.toInt().coerceAtLeast(1)} s left"; seconds < 3600 -> "${(seconds / 60).toInt()} min left"; else -> "${(seconds / 3600).toInt()} h ${((seconds / 60) % 60).toInt()} min left" }
/** A length of time in words: "2 d 3 h", "4 h 12 min", "7 min". */
fun span(seconds: Long): String { val d = seconds / 86_400; val h = seconds % 86_400 / 3_600; val m = seconds % 3_600 / 60; return if (d > 0) "$d d $h h" else if (h > 0) "$h h $m min" else "$m min" }

/** Opens a received file (or Downloads, for several). */
fun openReceived(context: Context, m: Moment) {
    val uri = m.uris.firstOrNull()?.let { DownloadsInbox.uriOf(it) }
    val intent = if (uri != null && m.count == 1) Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        else Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { Hub.banners.tryEmit(Banner(Banner.Kind.Info, "No app opens that file", "It is in Downloads › Arnav Island")) }
}
fun openUrl(context: Context, url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

fun bannerIcon(kind: Banner.Kind, t: Tokens): Pair<ImageVector, Color> = when (kind) {
    Banner.Kind.Received -> Icons.Rounded.Download to t.good
    Banner.Kind.Sent -> Icons.Rounded.CheckCircle to t.good
    Banner.Kind.Failed -> Icons.Rounded.ErrorOutline to t.danger
    Banner.Kind.Paired -> Icons.Rounded.Laptop to t.blue
    Banner.Kind.Info -> Icons.Rounded.Info to t.blue
    Banner.Kind.Music -> Icons.Rounded.MusicNote to t.pink
    Banner.Kind.Ring -> Icons.Rounded.NotificationsActive to t.warn
    Banner.Kind.Update -> Icons.Rounded.SystemUpdate to t.blue
    Banner.Kind.Clipboard -> Icons.Rounded.ContentPaste to t.indigo
    Banner.Kind.Photo -> Icons.Rounded.PhotoCamera to t.gray
    Banner.Kind.Internet -> Icons.Rounded.Public to t.teal
}

/** Shows each banner for a moment, one after another; [dismiss] lets one go early. */
class BannerState { var current by mutableStateOf<Banner?>(null); internal var skip by mutableStateOf(0); fun dismiss() { skip++ } }
@Composable fun rememberBanner(): BannerState {
    val state = remember { BannerState() }
    LaunchedEffect(Unit) {
        val queue = ArrayDeque<Banner>()
        kotlinx.coroutines.coroutineScope {
            launch { Hub.banners.collect { queue.addLast(it); if (queue.size > 4) queue.removeFirst() } }
            while (true) {
                val next = queue.removeFirstOrNull(); if (next == null) { delay(120); continue }
                state.current = next; val skipped = state.skip; val until = System.currentTimeMillis() + if (next.kind == Banner.Kind.Failed) 4200 else 3000
                while (System.currentTimeMillis() < until && state.skip == skipped) delay(60)
                state.current = null; delay(360)
            }
        }
    }
    return state
}

/**
 * 1.8: what just happened, as iOS shows a notification: a glass card that drops from the top of the screen with its
 * icon, a title and a line, and goes after a moment. Swipe it up (or tap it) to let it go sooner.
 */
@Composable fun TopBanner(state: BannerState, modifier: Modifier = Modifier) {
    val t = LocalTokens.current; val reduced = LocalReduced.current; val haptics = LocalHapticFeedback.current
    var shown by remember { mutableStateOf<Banner?>(null) }
    LaunchedEffect(state.current) { state.current?.let { shown = it; haptics.performHapticFeedback(if (it.kind == Banner.Kind.Failed) HapticFeedbackType.Reject else HapticFeedbackType.Confirm) } }
    val drag = remember { Animatable(0f) }; val scope = rememberCoroutineScope()
    LaunchedEffect(state.current) { drag.snapTo(0f) }
    AnimatedVisibility(state.current != null, modifier,
        enter = if (reduced) fadeIn() else slideInVertically(spring(dampingRatio = .72f, stiffness = 420f)) { -it * 2 } + fadeIn() + scaleIn(initialScale = .9f),
        exit = if (reduced) fadeOut() else slideOutVertically(tween(260)) { -it * 2 } + fadeOut(tween(200))) {
        val b = shown ?: return@AnimatedVisibility
        val (icon, tint) = bannerIcon(b.kind, t)
        Row(Modifier.fillMaxWidth().offset { IntOffset(0, drag.value.coerceAtMost(0f).roundToInt()) }.glass(RoundedCornerShape(24.dp), GlassLevel.Bar)
            .clickable(remember { MutableInteractionSource() }, null) { state.dismiss() }
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragEnd = { scope.launch { if (drag.value < -40.dp.toPx()) state.dismiss() else drag.animateTo(0f, spring(dampingRatio = .7f)) } }) { c, amount ->
                    c.consume(); scope.launch { drag.snapTo((drag.value + amount).coerceAtMost(12.dp.toPx())) }
                }
            }
            .semantics { contentDescription = "${b.title}. ${b.detail}" }
            .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, tint, size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(b.title, style = Type.bodyStrong, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (b.detail.isNotEmpty()) Text(b.detail, style = Type.caption, color = t.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Apple's segmented control in glass: a capsule track whose chosen segment is a lifted drop that springs across, with a
 * tick under the finger.
 */
@Composable fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current; val reduced = LocalReduced.current
    val at by animateFloatAsState(selected.toFloat(), if (reduced) snap() else spring(dampingRatio = .78f, stiffness = 520f), label = "Segment")
    var width by remember { mutableIntStateOf(0) }; val density = LocalDensity.current
    Box(modifier.height(38.dp).glass(GlassShapes.capsule, GlassLevel.Control).onSizeChanged { width = it.width }.padding(3.dp)) {
        if (width > 0 && options.isNotEmpty()) {
            val cell = with(density) { ((width - 6.dp.roundToPx()) / options.size.toFloat()).toDp() }
            Box(Modifier.width(cell).fillMaxHeight().offset { IntOffset((at * cell.toPx()).roundToInt(), 0) }
                .graphicsLayer { shadowElevation = 3.dp.toPx(); shape = GlassShapes.capsule; clip = true }
                .background(if (t.dark) Color(0xFF636366) else Color.White))
        }
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(GlassShapes.capsule).clickable(role = Role.Tab) { if (i != selected) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(i) } }
                    .semantics { this.selected = i == selected }, contentAlignment = Alignment.Center) {
                    Text(label, style = Type.caption.copy(fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium), color = t.text, maxLines = 1)
                }
            }
        }
    }
}

/**
 * 1.8: a number that rolls to its next value as an odometer does: each digit that changes slides up when the number
 * grows and down when it falls, the others hold still.
 */
@Composable fun RollingNumber(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val reduced = LocalReduced.current
    var last by remember { mutableStateOf(text) }
    fun number(s: String) = s.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0
    val up = number(text) >= number(last)
    LaunchedEffect(text) { last = text }
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = text }) {
        text.forEachIndexed { i, ch ->
            // Keyed from the right, so units and tens keep their places as a number gains a digit.
            key(text.length - i) {
                AnimatedContent(ch, transitionSpec = {
                    if (reduced || !ch.isDigit()) EnterTransition.None togetherWith ExitTransition.None
                    else if (up) (slideInVertically(spring(dampingRatio = .8f, stiffness = 500f)) { it } + fadeIn(tween(140))) togetherWith (slideOutVertically(tween(160)) { -it } + fadeOut(tween(120)))
                    else (slideInVertically(spring(dampingRatio = .8f, stiffness = 500f)) { -it } + fadeIn(tween(140))) togetherWith (slideOutVertically(tween(160)) { it } + fadeOut(tween(120)))
                }, label = "Digit") { c -> Text(c.toString(), style = style, color = color) }
            }
        }
    }
}

/**
 * 1.8: a screen's navigation bar, as on iOS: the screen's large title scrolls away and its small title fades into a glass
 * bar over what scrolls beneath. [actions] stay at its right; [accessory] (the Island tab's sections) shows under it
 * once the title has gone.
 */
@Stable class TopBar {
    var title by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var actions by mutableStateOf<(@Composable RowScope.() -> Unit)?>(null)
    var accessory by mutableStateOf<(@Composable () -> Unit)?>(null)
}
/** Ties a bar to a screen's scroll: its title and how far the large title has gone. */
@Composable fun TopBar.follow(title: String, scroll: ScrollState, actions: (@Composable RowScope.() -> Unit)? = null, accessory: (@Composable () -> Unit)? = null) {
    val density = LocalDensity.current
    this.title = title
    LaunchedEffect(scroll) { snapshotFlow { scroll.value }.collect { v -> progress = with(density) { ((v - 18.dp.toPx()) / 34.dp.toPx()).coerceIn(0f, 1f) } } }
    val a by rememberUpdatedState(actions); val b by rememberUpdatedState(accessory)
    SideEffect { this.actions = a; this.accessory = b }
}
/** Ties a bar to a lazy list or grid: [offset] is how far it has scrolled (px), or a large number past its first item. */
@Composable fun TopBar.follow(title: String, offset: () -> Int, actions: (@Composable RowScope.() -> Unit)? = null) {
    val density = LocalDensity.current
    this.title = title
    LaunchedEffect(Unit) { snapshotFlow { offset() }.collect { v -> progress = with(density) { ((v - 18.dp.toPx()) / 34.dp.toPx()).coerceIn(0f, 1f) } } }
    val a by rememberUpdatedState(actions)
    SideEffect { this.actions = a; this.accessory = null }
}
/** The height a screen leaves at its top for the navigation bar (below the status bar). */
val NavBarHeight = 48.dp

@Composable fun NavBar(bar: TopBar, modifier: Modifier = Modifier) {
    val t = LocalTokens.current; val p = bar.progress
    val showAccessory = bar.accessory != null && p > .98f
    Box(modifier.fillMaxWidth()) {
        if (p > 0f) Box(Modifier.matchParentSize().graphicsLayer { alpha = p }.glass(RoundedCornerShape(0.dp), GlassLevel.Bar, rim = false))
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(NavBarHeight).padding(horizontal = 12.dp)) {
                Text(bar.title, style = Type.headline, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 64.dp).graphicsLayer { alpha = p; translationY = (1f - p) * 6.dp.toPx() })
                bar.actions?.let { Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = it) }
            }
            AnimatedVisibility(showAccessory, enter = expandVertically(spring(dampingRatio = .85f, stiffness = 500f)) + fadeIn(), exit = shrinkVertically(tween(180)) + fadeOut(tween(120))) {
                Box(Modifier.padding(bottom = 8.dp)) { bar.accessory?.invoke() }
            }
        }
        if (p > 0f) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(.5.dp).graphicsLayer { alpha = p }.background(t.hairline))
    }
}

/** A search field in glass, as Apple's: a magnifier, the words, and a clear button once something's typed. */
@Composable fun SearchField(text: String, onText: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onGo: (() -> Unit)? = null, fieldModifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Row(modifier.fillMaxWidth().height(42.dp).glass(GlassShapes.capsule, GlassLevel.Control).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Search, null, tint = t.muted, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text(placeholder, style = Type.body, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(text, onText, singleLine = true, textStyle = Type.body.copy(color = t.text), cursorBrush = SolidColor(t.accent),
                keyboardOptions = KeyboardOptions(imeAction = if (onGo != null) ImeAction.Go else ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { onGo?.invoke() }, onSearch = { onGo?.invoke() }),
                modifier = fieldModifier.fillMaxWidth().semantics { contentDescription = placeholder })
        }
        if (text.isNotEmpty()) Icon(Icons.Rounded.Cancel, "Clear", tint = t.faint, modifier = Modifier.size(19.dp).clip(CircleShape).clickable { onText("") })
    }
}

/** A PC's own colour and icon, as chosen in its details (1.8), for its rows, chips and header. */
val PcColors = listOf(Color(0xFF0A84FF), Color(0xFF5E5CE6), Color(0xFFBF5AF2), Color(0xFFFF375F), Color(0xFFFF9F0A), Color(0xFF30D158), Color(0xFF64D2FF), Color(0xFF8E8E93))
val PcIcons = listOf(Icons.Rounded.Laptop, Icons.Rounded.DesktopWindows, Icons.Rounded.Computer, Icons.Rounded.SportsEsports, Icons.Rounded.Work, Icons.Rounded.Home, Icons.Rounded.Tv, Icons.Rounded.Dns)
@Composable fun pcColor(id: String?): Color = PcColors[Hub.pcLook(id).color.coerceIn(0, PcColors.lastIndex)]
fun pcIcon(id: String?, phone: Boolean = false): ImageVector = if (phone) Icons.Rounded.PhoneAndroid else PcIcons[Hub.pcLook(id).icon.coerceIn(0, PcIcons.lastIndex)]

/** A wiggle-free fold arrow for a collapsible header: down when open, right when folded. */
@Composable fun FoldArrow(open: Boolean, modifier: Modifier = Modifier) {
    val turn by animateFloatAsState(if (open) 0f else -90f, spring(dampingRatio = .8f, stiffness = 500f), label = "Fold")
    Icon(Icons.Rounded.ExpandMore, if (open) "Fold" else "Open", tint = LocalTokens.current.muted, modifier = modifier.size(20.dp).graphicsLayer { rotationZ = turn })
}
