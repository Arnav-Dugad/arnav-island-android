package io.github.arnavdugad.arnavisland

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

/** A settings-style row on glass: an icon, a title and a line under it, and whatever goes on the right. */
@Composable fun GlassRow(icon: ImageVector, title: String, detail: String? = null, modifier: Modifier = Modifier, tint: Color = LocalTokens.current.accent, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    val t = LocalTokens.current
    Row(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button) { onClick() } else Modifier).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = if (t.dark) .16f else .12f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.bodyStrong, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrEmpty()) Text(detail, style = Type.caption, color = t.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(10.dp)); trailing() }
    }
}
@Composable fun Hairline() { Box(Modifier.fillMaxWidth().padding(start = 65.dp).height(.6.dp).background(LocalTokens.current.hairline)) }

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
 * what just happened, then settles back.
 */
@Composable fun MiniIsland(status: PcStatus?, cover: android.graphics.Bitmap?, pcName: String?, online: Boolean, banner: Banner?, transfer: Transfer?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current; val reduced = LocalReduced.current
    val mode = when { banner != null -> 2; transfer != null -> 1; else -> 0 }
    val width by animateDpAsState(when (mode) { 2 -> 360.dp; 1 -> 250.dp; else -> if (status?.available == true) 190.dp else 150.dp }, if (reduced) snap() else spring(dampingRatio = .68f, stiffness = 330f), label = "IslandW")
    val height by animateDpAsState(if (mode == 2) 74.dp else 38.dp, if (reduced) snap() else spring(dampingRatio = .64f, stiffness = 360f), label = "IslandH")
    val corner by animateDpAsState(if (mode == 2) 30.dp else 19.dp, label = "IslandR")
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
                        Text(pcName ?: "No PC yet", style = Type.caption, color = Color.White.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(10.dp))
                    }
                }
            }
        }
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
