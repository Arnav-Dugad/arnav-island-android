package io.github.arnavdugad.arnavisland

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.arnavisland.link.PcStatus
import io.github.arnavdugad.arnavisland.link.PeerView
import io.github.arnavdugad.arnavisland.link.Proto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Your PC from your phone: what plays there (its cover, where it is, the controls and the volume), and quick actions. */
@Composable fun RemoteScreen(pc: PeerView?, status: PcStatus?, error: String?, cover: Bitmap?, onPair: () -> Unit, onLink: () -> Unit, padding: PaddingValues) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 20.dp)) {
        if (pc == null) { Welcome(onPair); return@Column }
        ScreenTitle(pc.name, over = if (pc.online) "Your PC  ·  here" else "Your PC  ·  away") { LiveDot(pc.online) }
        // What the PC reports: its battery, how busy it is, and the weather where it is.
        if (status != null) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status.batteryPresent && status.battery >= 0) GlassChip("${status.battery}%", icon = if (status.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryFull, iconTint = if (status.charging) t.good else if (status.battery <= 20) t.danger else Color.Unspecified)
            if (status.cpu in 0..100) GlassChip("CPU ${status.cpu}%", icon = Icons.Rounded.Memory)
            if (status.weather.isNotBlank()) GlassChip(status.weather, icon = if (status.weather.contains("Clear", true)) Icons.Rounded.WbSunny else Icons.Rounded.Cloud)
        }
        NowPlaying(pc, status, error, cover)
        SectionLabel("Quick actions")
        QuickActions(pc, onLink)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun Welcome(onPair: () -> Unit) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // The island, floating, with its level bars.
        val float by rememberInfiniteTransition(label = "Float").animateFloat(-5f, 5f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Y")
        val still = LocalReduced.current
        Box(Modifier.graphicsLayer { translationY = if (still) 0f else float * density }.width(210.dp).height(62.dp).glass(GlassShapes.capsule, GlassLevel.Island), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(t.accent, t.accent2))))
                Spacer(Modifier.width(14.dp)); Equalizer(true, t.accent, Modifier.size(44.dp, 24.dp), bars = 5)
            }
        }
        Spacer(Modifier.height(38.dp))
        Text("Your island, in your hand", style = Type.title, color = t.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text("Control what plays on your PC, send photos and files both ways, see your phone’s notifications on the island, and find this phone from your PC.",
            style = Type.body, color = t.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(30.dp))
        GlassButton(onPair, prominent = true, contentPadding = PaddingValues(horizontal = 30.dp, vertical = 16.dp)) { Icon(Icons.Rounded.Laptop, null); Spacer(Modifier.width(10.dp)); Text("Pair with your PC", style = Type.bodyStrong) }
        Spacer(Modifier.height(26.dp))
        GlassPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("On your PC", style = Type.micro, color = t.muted); Spacer(Modifier.height(8.dp))
                Step(1, "Open Arnav Island’s Settings › Sharing"); Step(2, "Turn on “Share with my PCs”"); Step(3, "Keep both on the same Wi-Fi, then pair here")
            }
        }
    }
}
@Composable private fun Step(n: Int, text: String) {
    val t = LocalTokens.current
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(t.accent.copy(alpha = .2f)), contentAlignment = Alignment.Center) { Text("$n", style = Type.caption, color = t.accent) }
        Spacer(Modifier.width(12.dp)); Text(text, style = Type.body, color = t.text)
    }
}

/**
 * Now playing on the PC. The cover shrinks back a little while paused and leans with the phone; the play button morphs;
 * the scrubber and the volume answer the finger at once and tell the PC as they go.
 */
@Composable private fun NowPlaying(pc: PeerView, status: PcStatus?, error: String?, cover: Bitmap?) {
    val t = LocalTokens.current; val scope = rememberCoroutineScope(); val tilt = LocalTilt.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Optimistic state: what the finger just asked for, until the PC says so too.
    var playingAsked by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    var volumeAsked by remember { mutableStateOf<Pair<Float, Long>?>(null) }
    var seekAsked by remember { mutableStateOf<Pair<Double, Long>?>(null) }
    val playing = playingAsked?.takeIf { now - it.second < 1800 }?.first ?: (status?.playing == true)
    LaunchedEffect(playing) { while (true) { now = System.currentTimeMillis(); delay(if (playing) 250 else 1000) } }
    GlassPanel(Modifier.fillMaxWidth()) {
        if (status == null || !status.available) {
            Column(Modifier.fillMaxWidth().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)).background(t.accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) { Equalizer(false, t.accent, Modifier.size(40.dp, 26.dp), bars = 5) }
                Spacer(Modifier.height(16.dp))
                Text(if (status == null) (error ?: "Connecting to ${pc.name}…") else "Nothing playing", style = Type.headline, color = t.text, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(if (status == null) "The remote shows what plays on your PC" else "Play something on ${pc.name} and it shows here", style = Type.caption, color = t.muted, textAlign = TextAlign.Center)
            }
            return@GlassPanel
        }
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val scale by springy(if (playing) 1f else .9f, .62f, 260f)
            val shadow = t.accent
            Box(Modifier.fillMaxWidth(.82f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                // The cover's own light beneath it.
                Canvas(Modifier.fillMaxSize().graphicsLayer { scaleX = scale * 1.02f; scaleY = scale * 1.02f; translationY = 18.dp.toPx() }) {
                    drawCircle(Brush.radialGradient(listOf(shadow.copy(alpha = .45f), Color.Transparent), center, size.minDimension * .62f), size.minDimension * .62f, center)
                }
                Crossfade(cover, animationSpec = tween(520), label = "Cover") { art ->
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        val tl = tilt(); scaleX = scale; scaleY = scale; rotationY = tl.x * 7f; rotationX = -tl.y * 5f; cameraDistance = 14 * density
                    }.clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(t.accent.copy(alpha = .5f), t.accent2.copy(alpha = .4f))))) {
                        if (art != null) Image(art.asImageBitmap(), "Cover of ${status.title}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(72.dp).align(Alignment.Center))
                        // A glint that slides over the cover as the phone tilts.
                        Canvas(Modifier.fillMaxSize()) {
                            val tl = tilt(); val x = size.width * (.5f + tl.x * .6f)
                            drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .16f), Color.Transparent), Offset(x - size.width * .5f, 0f), Offset(x + size.width * .1f, size.height)))
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(status.title, style = Type.headline, color = t.text, maxLines = 1, modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE))
            Text(listOf(status.artist, status.app).filter { it.isNotBlank() }.joinToString("  ·  "), style = Type.body, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            // Where the song is.
            val duration = status.duration.coerceAtLeast(0.0)
            val position = seekAsked?.takeIf { now - it.second < 2500 }?.first ?: status.positionNow(now).let { if (playingAsked != null && !playing) status.position else it }
            var scrubbing by remember { mutableStateOf<Float?>(null) }
            val fraction = scrubbing ?: if (duration > 0) (position / duration).toFloat() else 0f
            GlassSlider(fraction, onChange = { scrubbing = it }, onDone = { f ->
                scrubbing = null; if (status.canSeek && duration > 0) { val to = f * duration; seekAsked = to to System.currentTimeMillis(); scope.launch { Hub.command(Proto.CMD_SEEK, f64(to)) } }
            }, color = Color.White.copy(alpha = if (t.dark) .92f else 1f).takeIf { t.dark } ?: t.text, height = 6.dp, description = "Position in ${status.title}", modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth()) {
                Text(clock((scrubbing?.let { it * duration }) ?: position), style = Type.caption, color = t.muted)
                Spacer(Modifier.weight(1f))
                Text(if (duration > 0) "-" + clock(duration - ((scrubbing?.let { it * duration }) ?: position)) else "", style = Type.caption, color = t.muted)
            }
            Spacer(Modifier.height(10.dp))
            // The controls.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.Rounded.SkipPrevious, "Previous", { scope.launch { Hub.command(Proto.CMD_MEDIA, byteArrayOf(2)) } }, size = 60.dp, iconSize = 30.dp, enabled = status.canPrevious)
                GlassButton({ playingAsked = !playing to System.currentTimeMillis(); scope.launch { Hub.command(Proto.CMD_MEDIA, byteArrayOf(1)) } },
                    Modifier.size(84.dp), prominent = true, enabled = status.canToggle, contentPadding = PaddingValues(0.dp), description = if (playing) "Pause" else "Play") {
                    PlayPause(playing, if (t.dark) Color.White else t.text, Modifier.size(34.dp))
                }
                GlassIconButton(Icons.Rounded.SkipNext, "Next", { scope.launch { Hub.command(Proto.CMD_MEDIA, byteArrayOf(3)) } }, size = 60.dp, iconSize = 30.dp, enabled = status.canNext)
            }
            Spacer(Modifier.height(18.dp))
            // The PC's volume.
            val volume = volumeAsked?.takeIf { now - it.second < 1600 }?.first ?: (status.volume / 100f)
            var lastSent by remember { mutableLongStateOf(0L) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(if (status.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, if (status.muted) "Sound on" else "Mute",
                    { scope.launch { Hub.command(Proto.CMD_MUTE, byteArrayOf(2)); Hub.refreshStatus() } }, size = 40.dp, iconSize = 19.dp)
                Spacer(Modifier.width(12.dp))
                GlassSlider(volume, onChange = { v ->
                    volumeAsked = v to System.currentTimeMillis()
                    if (System.currentTimeMillis() - lastSent > 110) { lastSent = System.currentTimeMillis(); scope.launch { Hub.command(Proto.CMD_VOLUME, byteArrayOf((v * 100).toInt().toByte()), quiet = true) } }
                }, onDone = { v -> volumeAsked = v to System.currentTimeMillis(); scope.launch { Hub.command(Proto.CMD_VOLUME, byteArrayOf((v * 100).toInt().toByte())) } },
                    modifier = Modifier.weight(1f), description = "Volume on ${pc.name}")
                Spacer(Modifier.width(12.dp))
                Text("${(volume * 100).toInt()}", style = Type.caption, color = t.muted, modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
            }
        }
    }
}

@Composable private fun QuickActions(pc: PeerView, onLink: () -> Unit) {
    val t = LocalTokens.current; val scope = rememberCoroutineScope(); val context = LocalContext.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassTile(Icons.Rounded.ContentPaste, "Paste on PC", "Your phone’s clipboard, there", Modifier.weight(1f).fillMaxHeight()) {
                val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                if (text.isBlank()) Hub.banners.tryEmit(Banner(Banner.Kind.Info, "Your clipboard is empty", "Copy something first"))
                else scope.launch { if (Hub.command(Proto.CMD_CLIP_SET, text.toByteArray())?.ok == true) Hub.banners.tryEmit(Banner(Banner.Kind.Clipboard, "On ${pc.name}’s clipboard", text.lineSequence().first().take(60))) }
            }
            GlassTile(Icons.Rounded.ContentCopy, "Copy from PC", "${pc.name}’s clipboard, here", Modifier.weight(1f).fillMaxHeight()) {
                scope.launch {
                    val reply = Hub.command(Proto.CMD_CLIP_GET) ?: return@launch; if (!reply.ok) return@launch
                    val p = reply.payload; val n = if (p.size >= 4) ByteBuffer.wrap(p, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int else 0
                    val text = if (n in 1..(p.size - 4)) String(p, 4, n, Charsets.UTF_8) else ""
                    if (text.isEmpty()) Hub.banners.tryEmit(Banner(Banner.Kind.Info, "${pc.name}’s clipboard is empty"))
                    else { clipboard?.setPrimaryClip(ClipData.newPlainText("From ${pc.name}", text)); Hub.banners.tryEmit(Banner(Banner.Kind.Clipboard, "Copied from ${pc.name}", text.lineSequence().first().take(60))) }
                }
            }
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassTile(Icons.Rounded.Language, "Open a link", "In ${pc.name}’s browser", Modifier.weight(1f).fillMaxHeight(), tint = t.accent2) { onLink() }
            GlassTile(Icons.Rounded.Lock, "Lock ${pc.name}", "Right away", Modifier.weight(1f).fillMaxHeight(), tint = t.warn) {
                scope.launch { if (Hub.command(Proto.CMD_LOCK)?.ok == true) Hub.banners.tryEmit(Banner(Banner.Kind.Info, "Locked ${pc.name}")) }
            }
        }
    }
}

fun f64(v: Double): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array()
