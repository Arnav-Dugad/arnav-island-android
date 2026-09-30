package io.github.arnavdugad.arnavisland

import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.arnavisland.link.PeerView
import io.github.arnavdugad.arnavisland.link.Proto
import io.github.arnavdugad.arnavisland.link.ShelfItem
import io.github.arnavdugad.arnavisland.link.ShelfList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sending to your PC (photos, files, a photo taken for its Shelf, the clipboard), transfers as they go, and what came and went. */
@Composable fun SendScreen(pc: PeerView?, peers: List<PeerView>, transfers: Map<Int, Transfer>, moments: List<Moment>, onCamera: () -> Unit, onPair: () -> Unit, padding: PaddingValues) {
    val t = LocalTokens.current; val context = LocalContext.current; val scope = rememberCoroutineScope()
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris -> pc?.let { Hub.send(it.id, uris) } }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> pc?.let { Hub.send(it.id, uris) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 20.dp)) {
        ScreenTitle("Send", over = pc?.let { "To ${it.name}" } ?: "Pair a PC first")
        val paired = peers.filter { it.paired && !it.phone }
        if (paired.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            paired.forEach { p -> GlassChip(p.name, icon = Icons.Rounded.Laptop, selected = p.id == pc?.id, iconTint = if (p.online) t.good else Color.Unspecified) { Hub.choose(p.id) } }
        }
        if (pc == null) { GlassButton(onPair, prominent = true) { Icon(Icons.Rounded.Laptop, null); Spacer(Modifier.width(8.dp)); Text("Pair with your PC", style = Type.bodyStrong) }; return@Column }
        val ready = pc.online
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassTile(Icons.Rounded.PhotoLibrary, "Photos & videos", "Full quality, as they are", Modifier.weight(1f).fillMaxHeight(), enabled = ready) { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
            GlassTile(Icons.Rounded.Folder, "Files", "Anything, any size", Modifier.weight(1f).fillMaxHeight(), tint = t.accent2, enabled = ready) { files.launch(arrayOf("*/*")) }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassTile(Icons.Rounded.PhotoCamera, "Camera", if (pc.revision >= 3) "Straight onto ${pc.name}’s Shelf" else "A photo, to ${pc.name}", Modifier.weight(1f).fillMaxHeight(), tint = t.accent2, enabled = ready) { onCamera() }
            GlassTile(Icons.Rounded.ContentPaste, "Clipboard", "Paste it on ${pc.name}", Modifier.weight(1f).fillMaxHeight(), enabled = ready) {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                if (text.isBlank()) Hub.banners.tryEmit(Banner(Banner.Kind.Info, "Your clipboard is empty")) else scope.launch { if (Hub.command(Proto.CMD_CLIP_SET, text.toByteArray())?.ok == true) Hub.banners.tryEmit(Banner(Banner.Kind.Clipboard, "On ${pc.name}’s clipboard", text.lineSequence().first().take(60))) }
            }
        }
        if (!ready) Text("${pc.name} is away. Files go once Arnav Island runs there, on this Wi-Fi or any other.", style = Type.caption, color = t.muted, modifier = Modifier.padding(top = 12.dp, start = 6.dp))
        else if (pc.internet) Row(Modifier.padding(top = 12.dp, start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Public, null, tint = t.muted, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
            Text("${pc.name} is on another network: files go ${if (pc.path == 2) "straight to it over the internet" else "through the relay"}, end-to-end encrypted", style = Type.caption, color = t.muted)
        }
        // Transfers under way.
        AnimatedVisibility(transfers.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                SectionLabel("Now")
                GlassPanel(Modifier.fillMaxWidth()) { Column { transfers.values.sortedBy { it.id }.forEachIndexed { i, tr -> if (i > 0) Hairline(); TransferRow(tr) } } }
            }
        }
        if (moments.isNotEmpty()) {
            Row(verticalAlignment = Alignment.Bottom) { SectionLabel("Recent", Modifier.weight(1f)); Text("Clear", style = Type.caption, color = t.accent, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { Hub.clearMoments() }.padding(8.dp)) }
            GlassPanel(Modifier.fillMaxWidth()) {
                Column { moments.take(20).forEachIndexed { i, m -> if (i > 0) Hairline()
                    GlassRow(when (m.kind) { 0 -> Icons.Rounded.Download; 2 -> Icons.Rounded.Inventory2; else -> Icons.AutoMirrored.Rounded.Send }, m.title,
                        "${if (m.kind == 1) "To" else "From"} ${m.from}  ·  ${Hub.sizeText(m.size)}  ·  ${ago(m.at)}", tint = if (m.kind == 1) t.accent2 else t.good,
                        onClick = if (m.kind != 1) ({ openReceived(context, m) }) else null)
                } }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun TransferRow(tr: Transfer) {
    val t = LocalTokens.current
    val f = if (tr.total > 0) tr.done.toFloat() / tr.total else 0f
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            ProgressRing(f, if (tr.outgoing) t.accent2 else t.good, t.track, Modifier.fillMaxSize(), 3.5.dp)
            Icon(if (tr.outgoing) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.Download, null, tint = t.text, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(tr.title, style = Type.bodyStrong, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val left = if (tr.rate > 0) leftText((tr.total - tr.done) / tr.rate) else ""
            Text(listOf("${if (tr.outgoing) "To" else "From"} ${tr.name}", "${(f * 100).toInt()}%", if (tr.rate > 0) rateText(tr.rate) else "", left).filter { it.isNotEmpty() }.joinToString("  ·  "),
                style = Type.caption, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GlassIconButton(Icons.Rounded.Close, "Stop", { Hub.cancel(tr.id) }, size = 36.dp, iconSize = 17.dp)
    }
}

/** Your PC's Shelf: what is on it, each taken into Downloads with a tap. */
@Composable fun ShelfScreen(pc: PeerView?, transfers: Map<Int, Transfer>, visible: Boolean, onPair: () -> Unit, padding: PaddingValues) {
    val t = LocalTokens.current; val scope = rememberCoroutineScope()
    var list by remember(pc?.id) { mutableStateOf<ShelfList?>(null) }
    var loading by remember { mutableStateOf(false) }
    var taken by remember(pc?.id) { mutableStateOf(setOf<String>()) }
    fun load() { val p = pc ?: return; if (loading) return; loading = true; scope.launch { list = withContext(Dispatchers.IO) { Hub.link?.shelf(p.id) ?: ShelfList(false, emptyList(), "Not connected") }; loading = false } }
    LaunchedEffect(visible, pc?.id, pc?.online) { if (visible && pc?.online == true) load() }
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
        ScreenTitle("Shelf", over = pc?.let { "${it.name}’s" } ?: "Your PC’s") {
            if (pc != null) {
                val spin by rememberInfiniteTransition(label = "Spin").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "R")
                GlassIconButton(Icons.Rounded.Refresh, "Refresh", { load() }, size = 42.dp, iconSize = 20.dp, modifier = Modifier.graphicsLayer { rotationZ = if (loading) spin else 0f })
            }
        }
        val l = list
        when {
            pc == null -> Empty(Icons.Rounded.Inventory2, "Pair with your PC", "Then take anything on its Shelf with a tap") { GlassButton(onPair, prominent = true) { Text("Pair", style = Type.bodyStrong) } }
            !pc.online -> Empty(Icons.Rounded.WifiOff, "${pc.name} is away", "Its Shelf shows here when Arnav Island runs there")
            l == null -> Empty(Icons.Rounded.Inventory2, "Looking at ${pc.name}’s Shelf…", "")
            l.error != null -> Empty(Icons.Rounded.ErrorOutline, "Couldn’t look", l.error)
            !l.shared -> Empty(Icons.Rounded.Lock, "${pc.name} keeps its Shelf to itself", "Turn on “My PCs can take from the Shelf” in the island’s Settings › Privacy & productivity")
            l.items.isEmpty() -> Empty(Icons.Rounded.Inventory2, "The Shelf is empty", "Drop files on the island’s Shelf and they show up here")
            else -> LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(l.items.size) { i ->
                    val item = l.items[i]
                    val moving = transfers.values.firstOrNull { !it.outgoing && it.title == item.name && it.peer == pc.id }
                    ShelfTile(item, moving, item.name in taken) {
                        if (moving == null) { Hub.link?.let { link -> scope.launch(Dispatchers.IO) { link.take(pc.id, i, item.name) } }; taken = taken + item.name }
                    }
                }
            }
        }
    }
}

@Composable private fun ShelfTile(item: ShelfItem, moving: Transfer?, done: Boolean, onTake: () -> Unit) {
    val t = LocalTokens.current
    val preview = remember(item) { item.preview?.let { Art.bitmap(it, 256) } }
    Column(Modifier.glass(GlassShapes.tile).clickable(role = Role.Button) { onTake() }.padding(10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(16.dp)).background(t.accent.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
            if (preview != null) Image(preview.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(if (item.folder) Icons.Rounded.Folder else iconFor(item.name), null, tint = t.accent, modifier = Modifier.size(38.dp))
            if (moving != null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
                ProgressRing(if (moving.total > 0) moving.done.toFloat() / moving.total else 0f, Color.White, Color.White.copy(alpha = .25f), Modifier.size(46.dp), 4.dp)
            } else if (done) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape).background(t.good), contentAlignment = Alignment.Center) { DrawnCheck(Color.White, Modifier.size(18.dp)) }
        }
        Spacer(Modifier.height(9.dp))
        Text(item.name, style = Type.bodyStrong, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        Text(if (item.folder) "Folder  ·  ${Hub.sizeText(item.size)}" else Hub.sizeText(item.size), style = Type.caption, color = t.muted, modifier = Modifier.padding(horizontal = 4.dp))
    }
}
fun iconFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp" -> Icons.Rounded.Image
    "mp4", "mov", "mkv", "avi", "webm" -> Icons.Rounded.Movie
    "mp3", "flac", "wav", "m4a", "ogg", "aac" -> Icons.Rounded.MusicNote
    "zip", "rar", "7z", "tar", "gz" -> Icons.Rounded.FolderZip
    else -> Icons.Rounded.Description
}

@Composable fun Empty(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, action: (@Composable () -> Unit)? = null) {
    val t = LocalTokens.current
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(t.accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = t.accent, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.height(14.dp))
            Text(title, style = Type.headline, color = t.text, textAlign = TextAlign.Center)
            if (detail.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(detail, style = Type.caption, color = t.muted, textAlign = TextAlign.Center) }
            if (action != null) { Spacer(Modifier.height(16.dp)); action() }
        }
    }
}
