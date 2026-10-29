package io.github.arnavdugad.arnavisland

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.arnavisland.link.Lyrics
import io.github.arnavdugad.arnavisland.link.MixerApp
import io.github.arnavdugad.arnavisland.link.PcStatus
import io.github.arnavdugad.arnavisland.link.PeerView
import io.github.arnavdugad.arnavisland.link.Proto
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Your PC from your phone, laid out as Apple Music lays out its player: what plays there (its cover, which turns over to
 * its lyrics, its live levels, the controls, shuffle, repeat, love and what's next), its sound (the dial, where it comes
 * from and each app's own volume), what you've heard lately, and quick actions. [active]: this tab shows (it then asks
 * the PC for its levels and its apps, and takes the volume keys when that's on).
 */
@Composable fun RemoteScreen(pc: PeerView?, status: PcStatus?, error: String?, cover: Bitmap?, lyrics: Lyrics?, bar: TopBar, active: Boolean, padding: PaddingValues) {
    val t = LocalTokens.current; val scroll = rememberScrollState()
    bar.follow(pc?.name ?: "Remote", scroll)
    // 1.8: the volume keys turn the PC's sound while this shows (Settings › On this phone).
    val keys = Hub.prefs.getBoolean("volumeKeys", true) && pc?.online == true && status != null
    DisposableEffect(active, keys) {
        val handler: (Boolean) -> Boolean = { up -> Hub.volumeStep(up); true }
        if (active && keys) VolumeKeys.handler = handler
        onDispose { if (VolumeKeys.handler === handler) VolumeKeys.handler = null }
    }
    // 1.8: the PC's apps (with their levels) and its sound output, while this shows.
    LaunchedEffect(active, pc?.id, pc?.online, pc?.revision) {
        if (!active || pc == null || !pc.online) return@LaunchedEffect
        if (Hub.islandReady(pc)) Hub.loadOutputs()
        var n = 0
        while (isActive) { if (pc.revision >= 9) Hub.refreshMixer(); if (++n % 14 == 0 && Hub.islandReady(pc)) Hub.loadOutputs(); delay(700) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(horizontal = 16.dp)) {
        if (pc == null) { Welcome(); return@Column }
        val seen by Hub.lastSeen.collectAsState()
        ScreenTitle(pc.name, over = when { !pc.online -> "Away" + (seen[pc.id]?.let { "  ·  last seen ${ago(it).lowercase()}" } ?: ""); pc.internet -> quality(pc).text; else -> "On this Wi-Fi" }) { LiveDot(pc.online) }
        // What the PC reports: its battery, how busy it is, and the weather where it is.
        if (status != null) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 14.dp, start = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status.batteryPresent && status.battery >= 0) GlassChip("${status.battery}%", icon = if (status.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryFull, iconTint = if (status.charging) t.good else if (status.battery <= 20) t.danger else Color.Unspecified)
            if (status.cpu in 0..100) GlassChip("CPU ${status.cpu}%", icon = Icons.Rounded.Memory, onClick = { Hub.requests.tryEmit("island") })
            if (status.weather.isNotBlank()) GlassChip(status.weather, icon = weatherIcon(status.weather))
        }
        if (!pc.online) AwayCard(pc, seen[pc.id])
        NowPlaying(pc, status, error, cover, lyrics, active)
        if (status != null) SoundSection(pc, status)
        History()
        QuickActions(pc)
        Spacer(Modifier.height(24.dp))
    }
}

/** A word's weather as its icon. */
private fun weatherIcon(w: String): ImageVector { val s = w.lowercase(); return when {
    "thunder" in s || "storm" in s -> Icons.Rounded.Thunderstorm; "rain" in s || "drizzle" in s || "shower" in s -> Icons.Rounded.Umbrella
    "snow" in s || "sleet" in s -> Icons.Rounded.AcUnit; "clear" in s || "sun" in s -> Icons.Rounded.WbSunny; else -> Icons.Rounded.Cloud } }

@Composable private fun Welcome() {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().padding(top = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // A laptop in glass, floating, with the song's level bars.
        val float by rememberInfiniteTransition(label = "Float").animateFloat(-5f, 5f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Y")
        val still = LocalReduced.current
        Box(Modifier.graphicsLayer { translationY = if (still) 0f else float * density }.size(132.dp).glass(GlassShapes.card, GlassLevel.Control, t.accent), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Laptop, null, tint = t.text, modifier = Modifier.size(54.dp))
                Equalizer(true, t.accent, Modifier.size(42.dp, 16.dp), bars = 5)
            }
        }
        Spacer(Modifier.height(32.dp))
        Text("Your PC, in your hand", style = Type.title, color = t.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text("Control what plays on your PC, move files both ways, use it from here, and see this phone’s notifications on its island.",
            style = Type.body, color = t.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(28.dp))
        GlassButton({ Ui.pair() }, Modifier.fillMaxWidth(), prominent = true, contentPadding = PaddingValues(vertical = 16.dp)) { Icon(Icons.Rounded.Laptop, null); Spacer(Modifier.width(10.dp)); Text("Pair with your PC", style = Type.headline) }
        Section("On your PC") {
            Step(1, "Open Arnav Island’s Settings › Privacy & productivity"); Hairline(56.dp)
            Step(2, "Turn on “Share with my PCs”"); Hairline(56.dp)
            Step(3, "Pair here: on the same Wi-Fi, or anywhere by scanning the island’s QR code")
        }
    }
}
@Composable private fun Step(n: Int, text: String) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(t.accent), contentAlignment = Alignment.Center) { Text("$n", style = Type.caption, color = t.onAccent) }
        Spacer(Modifier.width(14.dp)); Text(text, style = Type.body, color = t.text)
    }
}

/** 1.8: the PC isn't here: when it was last seen, and (on this network, when it allows it) a way to wake it. */
@Composable private fun AwayCard(pc: PeerView, seen: Long?) {
    val t = LocalTokens.current; val machines by Hub.machines.collectAsState()
    GlassPanel(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Bedtime, t.indigo, size = 42.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("${pc.name} is away", style = Type.headline, color = t.text)
                Text(seen?.let { "Last seen ${ago(it).lowercase()}" } ?: "Open Arnav Island there to connect", style = Type.caption, color = t.muted)
            }
            if (!machines[pc.id].isNullOrEmpty()) GlassButton({ Hub.wake(pc.id) }, prominent = true, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) { Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Wake", style = Type.bodyStrong) }
        }
    }
}

/**
 * Now playing on the PC. The cover shrinks back a little while paused, leans with the phone and lifts off the card
 * over a shadow that slides the other way; tapped, it turns over to the song's lyrics. Under it the PC's own levels
 * dance (island 0.26). The play button morphs; the scrubber answers the finger at once, clicking softly at each lyric
 * line. Below: shuffle, repeat, love, lyrics and what's next, as in Apple Music.
 */
@Composable private fun NowPlaying(pc: PeerView, status: PcStatus?, error: String?, cover: Bitmap?, lyrics: Lyrics?, active: Boolean) {
    val t = LocalTokens.current; val scope = rememberCoroutineScope(); val tilt = LocalTilt.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Optimistic state: what the finger just asked for, until the PC says so too.
    var playingAsked by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    var seekAsked by remember { mutableStateOf<Pair<Double, Long>?>(null) }
    val playing = playingAsked?.takeIf { now - it.second < 1800 }?.first ?: (status?.playing == true)
    LaunchedEffect(playing) { while (true) { now = System.currentTimeMillis(); delay(if (playing) 250 else 1000) } }
    GlassPanel(Modifier.fillMaxWidth()) {
        if (status == null || !status.available) {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(t.accent.copy(alpha = .35f), t.accent2.copy(alpha = .25f)))), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(44.dp)) }
                Spacer(Modifier.height(16.dp))
                Text(if (status == null) (error ?: "Connecting to ${pc.name}…") else "Not playing", style = Type.headline, color = t.text, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(if (status == null) "What plays on your PC shows here" else "Play something on ${pc.name} and it shows here", style = Type.caption, color = t.muted, textAlign = TextAlign.Center)
            }
            return@GlassPanel
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val scale by springy(if (playing) 1f else .88f, .62f, 260f)
            val reduced = LocalReduced.current
            var flipped by rememberSaveable { mutableStateOf(false) }
            val flip by animateFloatAsState(if (flipped) 180f else 0f, if (reduced) snap() else spring(dampingRatio = .76f, stiffness = 160f), label = "Flip")
            Box(Modifier.fillMaxWidth(.84f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                // Depth: a soft shadow that slides against the tilt, so the cover seems to float above the card.
                Canvas(Modifier.fillMaxSize().graphicsLayer {
                    val tl = tilt(); translationX = -tl.x * 14.dp.toPx(); translationY = 22.dp.toPx() - tl.y * 10.dp.toPx(); scaleX = scale * .92f; scaleY = scale * .88f
                }) { drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = if (t.dark) .55f else .22f), Color.Transparent), center, size.minDimension * .56f), size.minDimension * .56f, center) }
                Box(Modifier.fillMaxSize().graphicsLayer {
                    // It lifts toward the light as the phone tilts, and turns over for the lyrics.
                    val tl = tilt(); scaleX = scale; scaleY = scale; translationX = tl.x * 5.dp.toPx(); translationY = tl.y * 3.dp.toPx()
                    rotationY = flip + tl.x * 6f * (1f - flip / 90f).coerceIn(-1f, 1f); rotationX = -tl.y * 4f; cameraDistance = 16 * density
                }.clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { if (pc.revision >= 3) flipped = !flipped }
                    .semantics { contentDescription = if (flipped) "Lyrics. Tap to show the cover" else "Cover of ${status.title}. Tap for the lyrics" }) {
                    if (flip < 90f) Crossfade(cover, animationSpec = tween(520), label = "Cover") { art ->
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(t.accent.copy(alpha = .5f), t.accent2.copy(alpha = .4f))))) {
                            if (art != null) Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(72.dp).align(Alignment.Center))
                            // A glint that slides over the cover as the phone tilts.
                            Canvas(Modifier.fillMaxSize()) {
                                val tl = tilt(); val x = size.width * (.5f + tl.x * .6f)
                                drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .12f), Color.Transparent), Offset(x - size.width * .5f, 0f), Offset(x + size.width * .1f, size.height)))
                            }
                        }
                    } else Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }.clip(RoundedCornerShape(20.dp)).background(Color(0xFF0B0E14))) {
                        // The lyrics, over the cover blurred into light.
                        cover?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, alpha = .7f, modifier = Modifier.fillMaxSize().blur(30.dp)) }
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .32f), Color.Black.copy(alpha = .58f)))))
                        LyricsView(lyrics, { status.positionNow(System.currentTimeMillis()) }, playing, pc.name, onSeek = { to ->
                            if (status.canSeek) { seekAsked = to to System.currentTimeMillis(); scope.launch { Hub.command(Proto.CMD_SEEK, f64(to)) } }
                        }, Modifier.fillMaxSize())
                    }
                }
            }
            // 1.8: the PC's own levels, live, as it plays (island 0.26, on this Wi-Fi or a direct path).
            LiveLevels(pc, playing, active, Modifier.fillMaxWidth(.84f).height(26.dp).padding(top = 10.dp))
            Spacer(Modifier.height(14.dp))
            // The song, and its heart and its menu, as Apple Music sets them.
            val history by Hub.history.collectAsState()
            val loved = history.firstOrNull { it.key == (status.title + "\t" + status.artist).lowercase() }?.loved == true
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(status.title, style = Type.title2, color = t.text, maxLines = 1, modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE))
                    Text(listOf(status.artist, status.app).filter { it.isNotBlank() }.joinToString("  ·  "), style = Type.body, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(10.dp))
                Heart(loved) { Hub.loveNow(!loved) }
            }
            Spacer(Modifier.height(14.dp))
            // Where the song is.
            val duration = status.duration.coerceAtLeast(0.0)
            val position = seekAsked?.takeIf { now - it.second < 2500 }?.first ?: status.positionNow(now).let { if (playingAsked != null && !playing) status.position else it }
            var scrubbing by remember { mutableStateOf<Float?>(null) }
            val fraction = scrubbing ?: if (duration > 0) (position / duration).toFloat() else 0f
            // Scrubbing clicks at each lyric line.
            val ticks = remember(lyrics, duration) { lyrics?.lines?.takeIf { duration > 0 && lyrics.state == 2 && synced(it) }?.map { (it.time / duration).toFloat() } }
            GlassSlider(fraction, onChange = { scrubbing = it }, onDone = { f ->
                scrubbing = null; if (status.canSeek && duration > 0) { val to = f * duration; seekAsked = to to System.currentTimeMillis(); scope.launch { Hub.command(Proto.CMD_SEEK, f64(to)) } }
            }, color = t.text.copy(alpha = .9f), height = 6.dp, description = "Position in ${status.title}", modifier = Modifier.fillMaxWidth(), ticks = ticks)
            Row(Modifier.fillMaxWidth()) {
                Text(clock((scrubbing?.let { it * duration }) ?: position), style = Type.micro, color = t.muted)
                Spacer(Modifier.weight(1f))
                Text(if (duration > 0) "-" + clock(duration - ((scrubbing?.let { it * duration }) ?: position)) else "", style = Type.micro, color = t.muted)
            }
            Spacer(Modifier.height(8.dp))
            // The controls.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Plain(Icons.Rounded.FastRewind, "Previous", 40.dp, status.canPrevious) { scope.launch { Hub.command(Proto.CMD_MEDIA, byteArrayOf(2)) } }
                GlassButton({ playingAsked = !playing to System.currentTimeMillis(); scope.launch { Hub.command(Proto.CMD_MEDIA, byteArrayOf(1)) } },
                    Modifier.size(80.dp), prominent = true, enabled = status.canToggle, contentPadding = PaddingValues(0.dp), description = if (playing) "Pause" else "Play") {
                    PlayPause(playing, t.onAccent, Modifier.size(32.dp))
                }
                Plain(Icons.Rounded.FastForward, "Next", 40.dp, status.canNext) { scope.launch { Hub.command(Proto.CMD_MEDIA, byteArrayOf(3)) } }
            }
            Spacer(Modifier.height(12.dp))
            // 1.8: shuffle, repeat, lyrics and what's next (shuffle and repeat when the PC's player says).
            val playback by Hub.playback.collectAsState()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                val pb = playback
                Toggle(Icons.Rounded.Shuffle, "Shuffle", pb?.shuffle == true, pb?.shuffleKnown == true) { pb?.let { Hub.setShuffle(!it.shuffle) } }
                Toggle(if (pb?.repeat == 1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, when (pb?.repeat) { 1 -> "Repeat one"; 2 -> "Repeat all"; else -> "Repeat" }, (pb?.repeat ?: 0) > 0, pb?.repeatKnown == true) { pb?.let { Hub.setRepeat((it.repeat + 1) % 3) } }
                if (pc.revision >= 3) Toggle(Icons.Rounded.Lyrics, "Lyrics", flipped, true) { flipped = !flipped }
                Toggle(Icons.AutoMirrored.Rounded.QueueMusic, "Up next", false, pc.revision >= 9) { Ui.upNext = true }
            }
        }
    }
}

/** A plain control: a glyph that dims when it can't be used and springs under the finger (previous and next). */
@Composable private fun Plain(icon: ImageVector, description: String, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) .82f else 1f, spring(dampingRatio = .5f, stiffness = 700f), label = "Plain")
    Box(Modifier.size(64.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape).clickable(source, null, enabled = enabled, role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() }
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = t.text.copy(alpha = if (enabled) 1f else .3f), modifier = Modifier.size(size))
    }
}
/** A small toggle under the controls: lit in the accent when on, faint when the PC can't say. */
@Composable private fun Toggle(icon: ImageVector, description: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    val color by androidx.compose.animation.animateColorAsState(if (!enabled) t.faint.copy(alpha = .4f) else if (on) t.accent else t.muted, label = "Toggle")
    Box(Modifier.size(44.dp).clip(CircleShape).then(if (on && enabled) Modifier.background(t.accent.copy(alpha = .16f)) else Modifier)
        .clickable(enabled = enabled, role = Role.Switch) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onClick() }
        .semantics { contentDescription = "$description, ${if (on) "on" else "off"}" }, contentAlignment = Alignment.Center) { Icon(icon, null, tint = color, modifier = Modifier.size(22.dp)) }
}
/** Love: a heart that fills and gives a little beat. */
@Composable fun Heart(loved: Boolean, size: Dp = 26.dp, onClick: () -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    val beat = remember { Animatable(1f) }
    LaunchedEffect(loved) { if (loved) { beat.snapTo(.7f); beat.animateTo(1f, spring(dampingRatio = .35f, stiffness = 600f)) } }
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Switch) { haptics.performHapticFeedback(if (loved) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn); onClick() }
        .semantics { contentDescription = if (loved) "Loved. Tap to unlove" else "Love" }, contentAlignment = Alignment.Center) {
        Icon(if (loved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = if (loved) t.pink else t.muted, modifier = Modifier.size(size).graphicsLayer { scaleX = beat.value; scaleY = beat.value })
    }
}

/**
 * 1.8: the PC's own sound, as level bars mirrored about their middle: its spectrum, read about ten times a second while
 * this shows and music plays, each bar easing toward its reading every frame. Over the relay it would lag, so it rests.
 */
@Composable private fun LiveLevels(pc: PeerView, playing: Boolean, active: Boolean, modifier: Modifier) {
    val t = LocalTokens.current; val reduced = LocalReduced.current
    val live = pc.revision >= 9 && playing && active && pc.online && (!pc.internet || pc.path == 2) && !reduced
    if (pc.revision < 9) return
    LaunchedEffect(live) { if (live) while (isActive) { Hub.refreshLevels(); delay(90) } else Hub.levels.value = null }
    val levels by Hub.levels.collectAsState()
    val bars = 28; val shown = remember { FloatArray(bars) }; var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(live) { while (isActive && (live || shown.any { it > .01f })) { withFrameNanos { frame = it }; val target = levels?.bands.orEmpty()
        for (i in 0 until bars) { val v = if (live && target.isNotEmpty()) target[(i * target.size / bars).coerceIn(0, target.lastIndex)] else 0f; shown[i] += (v - shown[i]) * (if (v > shown[i]) .45f else .18f) } } }
    Canvas(modifier.semantics { contentDescription = "The PC's sound, live" }) {
        frame.hashCode()
        val gap = size.width / (bars * 2f); val w = gap; val mid = size.height / 2
        for (i in 0 until bars) {
            val h = (size.height * (.08f + .92f * shown[i])).coerceAtLeast(2.dp.toPx())
            drawRoundRect(lerpColor(t.accent, t.accent2, i / (bars - 1f)).copy(alpha = .4f + .6f * shown[i]), Offset(i * gap * 2 + gap / 2, mid - h / 2), Size(w, h), CornerRadius(w / 2, w / 2))
        }
    }
}
private fun lerpColor(a: Color, b: Color, f: Float) = androidx.compose.ui.graphics.lerp(a, b, f)

/**
 * The PC's sound: a dial to twist (a click at every 5%), its middle to mute, steps; 1.8: where it comes from (its outputs,
 * as AirPlay lists them) and each app's own volume, with its level now (island 0.26).
 */
@Composable private fun SoundSection(pc: PeerView, status: PcStatus) {
    val t = LocalTokens.current; val scope = rememberCoroutineScope()
    var asked by remember { mutableStateOf<Pair<Float, Long>?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(asked) { now = System.currentTimeMillis(); delay(1700); now = System.currentTimeMillis() }
    val volume = asked?.takeIf { now - it.second < 1600 }?.first ?: (status.volume / 100f)
    var lastSent by remember { mutableLongStateOf(0L) }
    fun send(v: Float, final: Boolean) {
        asked = v to System.currentTimeMillis()
        if (final || System.currentTimeMillis() - lastSent > 110) { lastSent = System.currentTimeMillis(); scope.launch { Hub.command(Proto.CMD_VOLUME, byteArrayOf((v * 100 + .5f).toInt().toByte()), quiet = !final) } }
    }
    val outputs by Hub.outputs.collectAsState(); val apps by Hub.mixer.collectAsState(); val icons by Hub.mixerIcons.collectAsState()
    Section("Sound on ${pc.name}", footer = if (Hub.prefs.getBoolean("volumeKeys", true)) "This phone’s volume buttons turn it too, while the Remote shows." else null) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            VolumeDial(volume, status.muted, onChange = { send(it, false) }, onDone = { send(it, true) },
                onMute = { scope.launch { Hub.command(Proto.CMD_MUTE, byteArrayOf(2)); Hub.refreshStatus() } }, size = 156.dp, description = "Volume on ${pc.name}")
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                RollingNumber(if (status.muted) "Muted" else "${(volume * 100 + .5f).toInt()}%", Type.title, t.text)
                Text("Twist the dial; tap its middle to mute", style = Type.caption, color = t.muted)
                Spacer(Modifier.height(10.dp))
                // 1.5: a step down and up (5% each; held, they keep going).
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StepButton(Icons.Rounded.Remove, "Volume down on ${pc.name}", { send(((volume * 100 + .5f).toInt() - 5).coerceIn(0, 100) / 100f, true) })
                    StepButton(Icons.Rounded.Add, "Volume up on ${pc.name}", { send(((volume * 100 + .5f).toInt() + 5).coerceIn(0, 100) / 100f, true) })
                }
            }
        }
        // 1.8: where the sound comes from (#10), a tap away, as AirPlay lists its speakers.
        if (outputs.isNotEmpty()) {
            Hairline(16.dp)
            Text("PLAYING THROUGH", style = Type.micro, color = t.muted, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp))
            outputs.forEach { o ->
                GlassRow(when (o.form) { 3 -> Icons.Rounded.Headphones; 4 -> Icons.Rounded.SettingsVoice; else -> Icons.Rounded.Speaker }, o.name, tint = if (o.current) t.blue else t.gray, onClick = { if (!o.current) Hub.selectOutput(o.id) }) {
                    if (o.current) Icon(Icons.Rounded.CheckCircle, "Playing here", tint = t.blue)
                }
            }
        }
        // 1.8: each app's own volume (#9), with its level now.
        if (apps.isNotEmpty()) {
            Hairline(16.dp)
            Text("APPS", style = Type.micro, color = t.muted, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp))
            apps.sortedWith(compareByDescending<MixerApp> { it.active }.thenBy { it.system }.thenBy { it.name.lowercase() }).forEach { a -> MixerRow(a, icons[a.name]) }
            Spacer(Modifier.height(6.dp))
        } else if (pc.revision in 1..8) {
            Hairline(16.dp)
            Text("Update Arnav Island on ${pc.name} to 0.26 for each app’s own volume.", style = Type.caption, color = t.muted, modifier = Modifier.padding(16.dp))
        }
    }
}

/** One app's volume: its icon, its name, its level now as a thin bar under it, a slider and a mute toggle. */
@Composable fun MixerRow(a: MixerApp, icon: androidx.compose.ui.graphics.ImageBitmap?) {
    val t = LocalTokens.current
    var moving by remember(a.pid) { mutableStateOf<Float?>(null) }; var sent by remember { mutableLongStateOf(0L) }
    val peak by animateFloatAsState(a.peak / 100f, tween(380), label = "Peak")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(t.fill), contentAlignment = Alignment.Center) {
            if (icon != null) Image(icon, null, Modifier.size(24.dp)) else Icon(if (a.system) Icons.Rounded.NotificationsActive else Icons.Rounded.Apps, null, tint = t.muted, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (a.system) "System sounds" else a.name, style = Type.callout, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(if (a.muted) "Muted" else "${((moving ?: (a.volume / 100f)) * 100 + .5f).toInt()}%", style = Type.caption, color = t.muted)
            }
            GlassSlider(moving ?: (a.volume / 100f), { v -> moving = v; if (System.currentTimeMillis() - sent > 120) { sent = System.currentTimeMillis(); Hub.setAppVolume(a.pid, (v * 100 + .5f).toInt(), false) } },
                { v -> moving = null; Hub.setAppVolume(a.pid, (v * 100 + .5f).toInt(), true) }, Modifier.fillMaxWidth().height(24.dp), color = if (a.muted) t.faint else t.accent, height = 5.dp, description = "Volume of ${a.name}")
            // Its level now.
            Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(t.fill)) { Box(Modifier.fillMaxWidth(peak.coerceIn(0f, 1f)).fillMaxHeight().background(t.good)) }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Switch) { Hub.setAppMute(a.pid, !a.muted) }.semantics { contentDescription = if (a.muted) "Unmute ${a.name}" else "Mute ${a.name}" }, contentAlignment = Alignment.Center) {
            Icon(if (a.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, null, tint = if (a.muted) t.danger else t.muted, modifier = Modifier.size(20.dp))
        }
    }
}

/** 1.8: what you've heard on your PCs lately, newest first, loved ones marked; a tap opens the song (#7). */
@Composable private fun History() {
    val t = LocalTokens.current; val history by Hub.history.collectAsState()
    if (history.isEmpty()) return
    var loved by remember { mutableStateOf(false) }
    val shown = if (loved) history.filter { it.loved } else history
    SectionLabel("Recently played") {
        GlassChip("Loved", icon = Icons.Rounded.Favorite, selected = loved, iconTint = t.pink) { loved = !loved }
    }
    if (shown.isEmpty()) { Text("Tap the heart on a song you love and it stays here.", style = Type.caption, color = t.muted, modifier = Modifier.padding(start = 18.dp)); return }
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 0.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
        items(shown.take(40), key = { it.key }) { song ->
            val cover = remember(song.key) { song.cover?.let { Art.bitmap(it, 200) } }
            Column(Modifier.width(132.dp).clip(RoundedCornerShape(16.dp)).clickable { Ui.song = song }.padding(2.dp)) {
                Box(Modifier.size(128.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(t.accent.copy(alpha = .35f), t.accent2.copy(alpha = .25f))))) {
                    if (cover != null) Image(cover.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(40.dp).align(Alignment.Center))
                    if (song.loved) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = .35f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Favorite, null, tint = t.pink, modifier = Modifier.size(14.dp)) }
                }
                Spacer(Modifier.height(6.dp))
                Text(song.title, style = Type.callout.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist.ifBlank { song.app }, style = Type.caption, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable private fun QuickActions(pc: PeerView) {
    val t = LocalTokens.current; val scope = rememberCoroutineScope(); val context = LocalContext.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    @Composable fun Pair(content: @Composable RowScope.() -> Unit) = Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
    SectionLabel("Quick actions")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Pair {
            GlassTile(Icons.Rounded.ContentPaste, "Paste on PC", "Your clipboard, there", Modifier.weight(1f).fillMaxHeight(), tint = t.indigo) {
                val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                if (text.isBlank()) Hub.banners.tryEmit(Banner(Banner.Kind.Info, "Your clipboard is empty", "Copy something first"))
                else scope.launch { if (Hub.command(Proto.CMD_CLIP_SET, text.toByteArray())?.ok == true) Hub.banners.tryEmit(Banner(Banner.Kind.Clipboard, "On ${pc.name}’s clipboard", text.lineSequence().first().take(60))) }
            }
            GlassTile(Icons.Rounded.ContentCopy, "Copy from PC", "${pc.name}’s clipboard, here", Modifier.weight(1f).fillMaxHeight(), tint = t.purple) {
                scope.launch {
                    val reply = Hub.command(Proto.CMD_CLIP_GET) ?: return@launch; if (!reply.ok) return@launch
                    val p = reply.payload; val n = if (p.size >= 4) ByteBuffer.wrap(p, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int else 0
                    val text = if (n in 1..(p.size - 4)) String(p, 4, n, Charsets.UTF_8) else ""
                    if (text.isEmpty()) Hub.banners.tryEmit(Banner(Banner.Kind.Info, "${pc.name}’s clipboard is empty"))
                    else { clipboard?.setPrimaryClip(ClipData.newPlainText("From ${pc.name}", text)); Hub.banners.tryEmit(Banner(Banner.Kind.Clipboard, "Copied from ${pc.name}", text.lineSequence().first().take(60))) }
                }
            }
        }
        Pair {
            GlassTile(Icons.Rounded.Language, "Open a link", "In ${pc.name}’s browser", Modifier.weight(1f).fillMaxHeight(), tint = t.blue) { Ui.link = true }
            GlassTile(Icons.Rounded.Lock, "Lock ${pc.name}", "Right away", Modifier.weight(1f).fillMaxHeight(), tint = t.gray) {
                scope.launch { if (Hub.command(Proto.CMD_LOCK)?.ok == true) Hub.banners.tryEmit(Banner(Banner.Kind.Info, "Locked ${pc.name}")) }
            }
        }
        Pair {
            GlassTile(Icons.Rounded.Mouse, "Trackpad", "And the keyboard", Modifier.weight(1f).fillMaxHeight(), tint = t.teal) {
                if (pc.revision < 3 && pc.online) Hub.banners.tryEmit(Banner(Banner.Kind.Failed, "Update Arnav Island on ${pc.name}", "The trackpad needs version 0.20 or later")) else Ui.trackpad = TRACKPAD_PAD
            }
            // 1.8: point the phone and the pointer follows (#65).
            GlassTile(Icons.Rounded.Gesture, "Air mouse", "Point and it follows", Modifier.weight(1f).fillMaxHeight(), tint = t.blue) {
                if (pc.revision < 3 && pc.online) Hub.banners.tryEmit(Banner(Banner.Kind.Failed, "Update Arnav Island on ${pc.name}", "The air mouse needs version 0.20 or later")) else Ui.trackpad = TRACKPAD_AIR
            }
        }
        Pair {
            // 1.8: slides, a laser and a timer (#64).
            GlassTile(Icons.Rounded.Slideshow, "Present", "Slides, laser, timer", Modifier.weight(1f).fillMaxHeight(), tint = t.warn) { Ui.presenter = true }
            GlassTile(Icons.Rounded.NotificationsActive, "Find ${pc.name}", "It chimes and lights up", Modifier.weight(1f).fillMaxHeight(), tint = t.pink) { scope.launch { Hub.ringPc() } }
        }
        // 1.6: screens, either way.
        val showing by PhoneScreen.showing.collectAsState()
        Pair {
            GlassTile(Icons.Rounded.DesktopWindows, "${pc.name}’s screen", "Here, and touch it", Modifier.weight(1f).fillMaxHeight(), tint = t.indigo) {
                if (pc.revision < 7 && pc.online) Hub.banners.tryEmit(Banner(Banner.Kind.Failed, "Update Arnav Island on ${pc.name}", "Its screen needs version 0.24 or later"))
                else context.startActivity(Intent(context, PcScreenActivity::class.java).putExtra("peer", pc.id))
            }
            GlassTile(Icons.Rounded.CastConnected, if (showing != null) "Stop showing" else "This phone there", if (showing != null) "On $showing now" else "In a window on ${pc.name}", Modifier.weight(1f).fillMaxHeight(), tint = t.purple) {
                when {
                    showing != null -> PhoneScreen.stop(context)
                    pc.revision < 7 && pc.online -> Hub.banners.tryEmit(Banner(Banner.Kind.Failed, "Update Arnav Island on ${pc.name}", "Showing this phone needs version 0.24 or later"))
                    else -> {
                        PhoneScreen.start(context, pc.id)
                        if (!PhoneScreen.controllable(context)) Hub.banners.tryEmit(Banner(Banner.Kind.Info, "To use this phone from ${pc.name}", "Turn on “Control from your PC” in Settings › Accessibility"))
                    }
                }
            }
        }
    }
    // 1.8: this phone's camera and microphone, for the PC (#67).
    SectionLabel("This phone as ${pc.name}’s camera and mic")
    CameraMicCard(pc, Modifier.fillMaxWidth())
}

/** The Remote's sheets (drawn over the whole app): a song from the history, and what plays next. */
@Composable fun RemoteSheets(pc: PeerView?) {
    SongSheet(Ui.song, pc) { Ui.song = null }
    UpNextSheet(Ui.upNext, pc) { Ui.upNext = false }
}

/** 1.8: a song you heard: its cover, when and how often, and play it on the PC, find it here, love it or let it go. */
@Composable private fun SongSheet(song: Song?, pc: PeerView?, onDismiss: () -> Unit) {
    val t = LocalTokens.current; val context = LocalContext.current
    val history by Hub.history.collectAsState()
    val s = song?.let { x -> history.firstOrNull { it.key == x.key } ?: x }
    GlassSheet(song != null, onDismiss) {
        if (s == null) return@GlassSheet
        val cover = remember(s.key) { s.cover?.let { Art.bitmap(it, 400) } }
        Box(Modifier.size(170.dp).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(t.accent.copy(alpha = .35f), t.accent2.copy(alpha = .25f)))), contentAlignment = Alignment.Center) {
            if (cover != null) Image(cover.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) else Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(60.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(s.title, style = Type.title2, color = t.text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOf(s.artist, s.app).filter { it.isNotBlank() }.joinToString("  ·  "), style = Type.body, color = t.muted, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Heard on ${s.pc.ifBlank { "your PC" }}  ·  ${if (s.plays == 1) "once" else "${s.plays} times"}  ·  ${ago(s.last).lowercase()}", style = Type.caption, color = t.faint, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(20.dp))
        GlassButton({ Hub.replay(s); onDismiss() }, Modifier.fillMaxWidth(), prominent = true, enabled = pc?.online == true, contentPadding = PaddingValues(vertical = 15.dp)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Play on ${pc?.name ?: "your PC"}", style = Type.headline) }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton({ Hub.searchHere(context, s) }, Modifier.weight(1f)) { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Find here", style = Type.bodyStrong) }
            GlassButton({ Hub.love(s.key, !s.loved) }, Modifier.weight(1f)) { Icon(if (s.loved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, Modifier.size(18.dp), tint = if (s.loved) t.pink else t.text); Spacer(Modifier.width(6.dp)); Text(if (s.loved) "Loved" else "Love", style = Type.bodyStrong) }
        }
        Spacer(Modifier.height(6.dp))
        Text("Remove from history", style = Type.body, color = t.danger, modifier = Modifier.clip(CircleShape).clickable { Hub.forgetSong(s.key); onDismiss() }.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

/** 1.8: what plays next on the PC, with shuffle and repeat: the island's own player's queue, a tap to jump to one. */
@Composable private fun UpNextSheet(visible: Boolean, pc: PeerView?, onDismiss: () -> Unit) {
    val t = LocalTokens.current; val playback by Hub.playback.collectAsState(); val status by Hub.status.collectAsState()
    GlassSheet(visible, onDismiss, Modifier.heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * .85f)) {
        SheetHeader("Up next", status?.takeIf { it.available }?.let { "After ${it.title}" }, onDismiss)
        val pb = playback
        if (pb != null && (pb.shuffleKnown || pb.repeatKnown)) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (pb.shuffleKnown) GlassButton({ Hub.setShuffle(!pb.shuffle) }, Modifier.weight(1f), prominent = pb.shuffle) { Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Shuffle", style = Type.bodyStrong) }
            if (pb.repeatKnown) GlassButton({ Hub.setRepeat((pb.repeat + 1) % 3) }, Modifier.weight(1f), prominent = pb.repeat > 0) { Icon(if (pb.repeat == 1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(when (pb.repeat) { 1 -> "One"; 2 -> "All"; else -> "Repeat" }, style = Type.bodyStrong) }
        }
        when {
            (pc?.revision ?: 0) < 9 -> Text("Update Arnav Island on ${pc?.name ?: "your PC"} to 0.26 to see what plays next.", style = Type.body, color = t.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(20.dp))
            pb == null || !pb.islandPlayer -> Text("Windows doesn’t share what plays next from ${status?.app?.ifBlank { null } ?: "this app"}. Up next shows for music the island plays from your library.", style = Type.body, color = t.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(20.dp))
            pb.upNext.isEmpty() -> Text("Nothing after this one", style = Type.body, color = t.muted, modifier = Modifier.padding(20.dp))
            else -> androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(pb.upNext.size) { i -> val n = pb.upNext[i]
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { Hub.playUpNext(i) }.padding(horizontal = 6.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = Type.caption, color = t.faint, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
                        Column(Modifier.weight(1f)) { Text(n.title, style = Type.body, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis); if (n.artist.isNotBlank()) Text(n.artist, style = Type.caption, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        if (n.duration > 0) Text(clock(n.duration), style = Type.caption, color = t.faint)
                    }
                }
            }
        }
    }
}

fun f64(v: Double): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array()
