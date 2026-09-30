package io.github.arnavdugad.arnavisland

import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.arnavisland.link.PcStatus
import io.github.arnavdugad.arnavisland.link.PeerView
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A screen's title, with an optional small line above it. */
@Composable fun ScreenTitle(title: String, over: String? = null, trailing: @Composable (() -> Unit)? = null) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            if (over != null) Text(over.uppercase(), style = Type.micro, color = t.muted)
            Text(title, style = Type.hero, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
        }
        trailing?.invoke()
    }
}

@Composable fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Type.micro, color = LocalTokens.current.muted, modifier = modifier.padding(start = 6.dp, top = 22.dp, bottom = 9.dp).semantics { heading() })
}

/** A pressable glass tile: an icon in a lit well, a title and a line under it. */
@Composable fun GlassTile(icon: ImageVector, title: String, detail: String, modifier: Modifier = Modifier, tint: Color = LocalTokens.current.accent, enabled: Boolean = true, onClick: () -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }; val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .95f else 1f, spring(dampingRatio = .5f, stiffness = 600f), label = "Tile")
    Column(modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .45f }.glass(GlassShapes.tile)
        .clickable(source, null, enabled = enabled, role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() }.padding(16.dp)) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(tint.copy(alpha = if (t.dark) .18f else .14f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(14.dp))
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

/** A settings-style row on glass: an icon, a title and a line under it, and whatever goes on the right. [ring]: a device's connection, round its icon. */
@Composable fun GlassRow(icon: ImageVector, title: String, detail: String? = null, modifier: Modifier = Modifier, tint: Color = LocalTokens.current.accent, onClick: (() -> Unit)? = null,
                         ring: Quality? = null, trailing: @Composable (() -> Unit)? = null) {
    val t = LocalTokens.current
    Row(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button) { onClick() } else Modifier).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ring != null) Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            QualityRing(ring, Modifier.fillMaxSize())
            Box(Modifier.size(28.dp).clip(CircleShape).background(tint.copy(alpha = if (t.dark) .16f else .12f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp)) }
        }
        else Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = if (t.dark) .16f else .12f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.bodyStrong, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrEmpty()) Text(detail, style = Type.caption, color = t.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(10.dp)); trailing() }
    }
}
@Composable fun Hairline() { Box(Modifier.fillMaxWidth().padding(start = 65.dp).height(.6.dp).background(LocalTokens.current.hairline)) }

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

/** Opens a received file (or Downloads, for several). */
fun openReceived(context: Context, m: Moment) {
    val uri = m.uris.firstOrNull()?.let { DownloadsInbox.uriOf(it) }
    val intent = if (uri != null && m.count == 1) Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        else Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { Hub.banners.tryEmit(Banner(Banner.Kind.Info, "No app opens that file", "It is in Downloads › Arnav Island")) }
}
fun openUrl(context: Context, url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

/**
 * The island at the top of the app, as on the PC: a dark glass capsule. At rest it shows what plays on your PC (its
 * cover and level bars) or who is here; it widens for a transfer (with its ring), and drops open for a moment to say
 * what just happened, then settles back. Tapped while music plays, it melts open into the song's own card (cover,
 * where it is, the controls) and flows back when tapped again or anywhere else.
 */
@Composable fun MiniIsland(status: PcStatus?, cover: android.graphics.Bitmap?, pcName: String?, online: Boolean, banner: Banner?, transfer: Transfer?, onClick: () -> Unit, modifier: Modifier = Modifier,
                           expanded: Boolean = false, internet: Boolean = false, quality: Quality? = null) {
    val t = LocalTokens.current; val reduced = LocalReduced.current
    val mode = when { banner != null -> 2; expanded && status?.available == true -> 3; transfer != null -> 1; else -> 0 }
    val width by animateDpAsState(when (mode) { 3, 2 -> 360.dp; 1 -> 250.dp; else -> if (status?.available == true) 190.dp else 150.dp }, if (reduced) snap() else spring(dampingRatio = .68f, stiffness = 330f), label = "IslandW")
    val height by animateDpAsState(when (mode) { 3 -> 190.dp; 2 -> 74.dp; else -> 38.dp }, if (reduced) snap() else spring(dampingRatio = if (mode == 3) .72f else .64f, stiffness = if (mode == 3) 300f else 360f), label = "IslandH")
    val corner by animateDpAsState(when (mode) { 3 -> 46.dp; 2 -> 30.dp; else -> 19.dp }, if (reduced) snap() else spring(dampingRatio = .8f, stiffness = 300f), label = "IslandR")
    Box(modifier.widthIn(max = width).fillMaxWidth().height(height).glass(RoundedCornerShape(corner), GlassLevel.Island).clickable(role = Role.Button) { onClick() }
        .semantics { contentDescription = banner?.let { "${it.title}. ${it.detail}" } ?: status?.takeIf { it.available }?.let { "${it.title} on ${it.pcName}" } ?: (pcName ?: "No PC yet") }, contentAlignment = Alignment.Center) {
        AnimatedContent(mode to (banner?.title ?: ""), transitionSpec = { (fadeIn(tween(220, 90)) + scaleIn(initialScale = .92f)) togetherWith fadeOut(tween(110)) }, label = "Island") { (m, _) ->
            when (m) {
                2 -> if (banner != null) Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = bannerIcon(banner.kind, t)
                    Box(Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = .2f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(23.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(banner.title, style = Type.bodyStrong, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (banner.detail.isNotEmpty()) Text(banner.detail, style = Type.caption, color = Color.White.copy(alpha = .66f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                3 -> if (status != null) IslandPlayer(status, cover, pcName.orEmpty())
                1 -> if (transfer != null) Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val f = if (transfer.total > 0) transfer.done.toFloat() / transfer.total else 0f
                    Icon(if (transfer.outgoing) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.Download, null, tint = t.accent, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(transfer.title, style = Type.caption, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${(f * 100).toInt()}%", style = Type.caption, color = Color.White.copy(alpha = .7f)); Spacer(Modifier.width(6.dp))
                    ProgressRing(f, t.accent, Color.White.copy(alpha = .16f), Modifier.size(22.dp), 3.dp)
                }
                else -> Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (status?.available == true) {
                        if (cover != null) Image(cover.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)))
                        else Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(t.accent.copy(alpha = .3f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MusicNote, null, tint = t.accent, modifier = Modifier.size(14.dp)) }
                        Spacer(Modifier.width(8.dp))
                        Text(status.title, style = Type.caption, color = Color.White.copy(alpha = .86f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(6.dp))
                        Equalizer(status.playing, t.accent, Modifier.size(20.dp, 14.dp))
                        Spacer(Modifier.width(4.dp))
                    } else {
                        LiveDot(online, size = 7.dp); Spacer(Modifier.width(2.dp))
                        // 1.3: over the internet, the connection's quality ring (direct, relay or weak) where the globe was.
                        if (online && internet) {
                            if (quality != null) QualityRing(quality, Modifier.size(13.dp).semantics { contentDescription = quality.text }, 1.6.dp)
                            else Icon(Icons.Rounded.Public, "Over the internet", tint = Color.White.copy(alpha = .6f), modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(pcName ?: "No PC yet", style = Type.caption, color = Color.White.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(10.dp))
                    }
                }
            }
        }
    }
}
/** The island opened into the song's card: the cover, the song, where it is, and the controls. */
@Composable private fun IslandPlayer(status: PcStatus, cover: android.graphics.Bitmap?, pcName: String) {
    val t = LocalTokens.current; val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var asked by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    val playing = asked?.takeIf { now - it.second < 1800 }?.first ?: status.playing
    LaunchedEffect(playing) { while (true) { now = System.currentTimeMillis(); delay(if (playing) 400 else 1000) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(17.dp)).background(t.accent.copy(alpha = .3f)), contentAlignment = Alignment.Center) {
                if (cover != null) Image(cover.asImageBitmap(), "Cover of ${status.title}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Rounded.MusicNote, null, tint = t.accent, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("ON ${pcName.uppercase()}", style = Type.micro, color = Color.White.copy(alpha = .45f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status.title, style = Type.bodyStrong, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status.artist.ifBlank { status.app }, style = Type.caption, color = Color.White.copy(alpha = .66f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Equalizer(playing, t.accent, Modifier.size(22.dp, 16.dp))
        }
        Spacer(Modifier.height(14.dp))
        val duration = status.duration.coerceAtLeast(0.0); val position = status.positionNow(now).let { if (asked != null && !playing) status.position else it }
        val f = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f))) { Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Color.White.copy(alpha = .92f))) }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(clock(position), style = Type.caption, color = Color.White.copy(alpha = .55f)); Spacer(Modifier.weight(1f))
            Text(if (duration > 0) "-" + clock(duration - position) else "", style = Type.caption, color = Color.White.copy(alpha = .55f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IslandControl(Icons.Rounded.SkipPrevious, "Previous", status.canPrevious) { scope.launch { Hub.command(io.github.arnavdugad.arnavisland.link.Proto.CMD_MEDIA, byteArrayOf(2)); Hub.refreshStatus() } }
            Box(Modifier.size(50.dp).clip(CircleShape).background(Color.White).clickable(role = Role.Button, enabled = status.canToggle) {
                asked = !playing to System.currentTimeMillis(); scope.launch { Hub.command(io.github.arnavdugad.arnavisland.link.Proto.CMD_MEDIA, byteArrayOf(1)) } }
                .semantics { contentDescription = if (playing) "Pause" else "Play" }, contentAlignment = Alignment.Center) { PlayPause(playing, Color(0xFF0B0E14), Modifier.size(22.dp)) }
            IslandControl(Icons.Rounded.SkipNext, "Next", status.canNext) { scope.launch { Hub.command(io.github.arnavdugad.arnavisland.link.Proto.CMD_MEDIA, byteArrayOf(3)); Hub.refreshStatus() } }
        }
    }
}
@Composable private fun IslandControl(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(Modifier.size(46.dp).clip(CircleShape).clickable(role = Role.Button, enabled = enabled) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() }
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White.copy(alpha = if (enabled) .92f else .35f), modifier = Modifier.size(28.dp))
    }
}

fun bannerIcon(kind: Banner.Kind, t: Tokens): Pair<ImageVector, Color> = when (kind) {
    Banner.Kind.Received -> Icons.Rounded.Download to t.good
    Banner.Kind.Sent -> Icons.Rounded.CheckCircle to t.good
    Banner.Kind.Failed -> Icons.Rounded.ErrorOutline to t.danger
    Banner.Kind.Paired -> Icons.Rounded.Laptop to t.accent
    Banner.Kind.Info -> Icons.Rounded.Info to t.accent
    Banner.Kind.Music -> Icons.Rounded.MusicNote to t.accent
    Banner.Kind.Ring -> Icons.Rounded.NotificationsActive to t.warn
    Banner.Kind.Update -> Icons.Rounded.SystemUpdate to t.accent
    Banner.Kind.Clipboard -> Icons.Rounded.ContentPaste to t.accent
    Banner.Kind.Photo -> Icons.Rounded.PhotoCamera to t.accent
    Banner.Kind.Internet -> Icons.Rounded.Public to t.accent
}

/** Shows each banner for a moment, one after another. */
@Composable fun rememberBanner(): State<Banner?> {
    val current = remember { mutableStateOf<Banner?>(null) }
    LaunchedEffect(Unit) {
        val queue = ArrayDeque<Banner>()
        kotlinx.coroutines.coroutineScope {
            launch { Hub.banners.collect { queue.addLast(it); if (queue.size > 4) queue.removeFirst() } }
            while (true) { val next = queue.removeFirstOrNull(); if (next == null) { delay(120); continue }; current.value = next; delay(if (next.kind == Banner.Kind.Failed) 3600 else 2600); current.value = null; delay(380) }
        }
    }
    return current
}
