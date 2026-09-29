package io.github.arnavdugad.arnavisland

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Music handed over from your PC, playing on this phone: a glass bar above the tabs with the cover, how far the song
 * has got, play and pause, and a way to send it back to the PC from where it is.
 */
@Composable fun PlayingHere(now: Player.Now, modifier: Modifier = Modifier) {
    val t = LocalTokens.current; val context = LocalContext.current
    val cover = remember(now.music.title, now.music.cover?.size) { now.music.cover?.let { Art.bitmap(it, 200) } }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(now.playing) { while (true) { tick = System.currentTimeMillis(); delay(if (now.playing) 250 else 1000) } }
    val f = if (now.duration > 0) (now.positionNow(tick) / now.duration).toFloat().coerceIn(0f, 1f) else 0f
    Box(modifier.height(66.dp).glass(GlassShapes.capsule, GlassLevel.Bar)) {
        Row(Modifier.fillMaxSize().padding(start = 9.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(t.accent.copy(alpha = .2f)), contentAlignment = Alignment.Center) {
                if (cover != null) Image(cover.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Rounded.MusicNote, null, tint = t.accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(now.music.title, style = Type.bodyStrong, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Playing here  ·  from ${now.from}", style = Type.caption, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { Player.toggle() }.semantics { contentDescription = if (now.playing) "Pause" else "Play" }, contentAlignment = Alignment.Center) {
                PlayPause(now.playing, t.text, Modifier.size(22.dp))
            }
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { Player.sendBack(context) }.semantics { contentDescription = "Continue on ${now.from}" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Laptop, null, tint = t.accent, modifier = Modifier.size(22.dp))
            }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) { Player.close(context) }.semantics { contentDescription = "Stop" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, null, tint = t.muted, modifier = Modifier.size(19.dp))
            }
        }
        // How far the song has got: a hairline along the bar's lower edge.
        Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 28.dp).padding(bottom = 5.dp).fillMaxWidth().height(2.dp).clip(CircleShape).background(t.track)) {
            Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(t.accent))
        }
    }
}
