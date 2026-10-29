package io.github.arnavdugad.arnavisland

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.arnavdugad.arnavisland.link.PeerView
import io.github.arnavdugad.arnavisland.link.Proto
import io.github.arnavdugad.arnavisland.link.ShelfItem
import io.github.arnavdugad.arnavisland.link.ShelfList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Sending to your PC: photos, files, the camera and the clipboard; what's moving now (1.8: each pausable, island 0.26),
 * what waits its turn (drag to reorder), and what came and went lately (with pictures; swipe one away).
 */
@Composable fun SendScreen(pc: PeerView?, peers: List<PeerView>, transfers: Map<Int, Transfer>, moments: List<Moment>, bar: TopBar, onCamera: () -> Unit, padding: PaddingValues) {
    val t = LocalTokens.current; val context = LocalContext.current; val scope = rememberCoroutineScope(); val scroll = rememberScrollState()
    bar.follow("Send", scroll)
    // 1.9: to the chosen PC, or to every one of them that's here.
    var toAll by remember { mutableStateOf(false) }
    fun go(uris: List<android.net.Uri>) { if (toAll) Hub.sendToAll(uris) else pc?.let { Hub.send(it.id, uris) } }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris -> go(uris) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> go(uris) }
    val queue by Hub.queue.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(horizontal = 16.dp)) {
        ScreenTitle("Send", over = if (toAll) "To all your PCs" else pc?.let { "To ${it.name}" } ?: "Pair a PC first")
        val paired = peers.filter { it.paired && !it.phone }
        if (paired.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 14.dp, start = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("All PCs", icon = Icons.Rounded.Devices, selected = toAll) { toAll = !toAll }
            paired.forEach { p -> GlassChip(p.name, icon = pcIcon(p.id), selected = !toAll && p.id == pc?.id, iconTint = if (p.online) pcColor(p.id) else Color.Unspecified) { toAll = false; Hub.choose(p.id) } }
        }
        if (pc == null) { GlassButton({ Ui.pair() }, Modifier.fillMaxWidth(), prominent = true, contentPadding = PaddingValues(vertical = 15.dp)) { Icon(Icons.Rounded.Laptop, null); Spacer(Modifier.width(8.dp)); Text("Pair with your PC", style = Type.headline) }; return@Column }
        val ready = pc.online
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassTile(Icons.Rounded.PhotoLibrary, "Photos & videos", "Full quality, as they are", Modifier.weight(1f).fillMaxHeight(), tint = t.pink, enabled = ready) { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
            GlassTile(Icons.Rounded.Folder, "Files", "Anything, any size", Modifier.weight(1f).fillMaxHeight(), tint = t.blue, enabled = ready) { files.launch(arrayOf("*/*")) }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassTile(Icons.Rounded.PhotoCamera, "Camera", if (pc.revision >= 3) "Straight onto ${pc.name}’s Shelf" else "A photo, to ${pc.name}", Modifier.weight(1f).fillMaxHeight(), tint = t.gray, enabled = ready) { onCamera() }
            GlassTile(Icons.Rounded.ContentPaste, "Clipboard", "Paste it on ${pc.name}", Modifier.weight(1f).fillMaxHeight(), tint = t.indigo, enabled = ready) {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                if (text.isBlank()) Hub.banners.tryEmit(Banner(Banner.Kind.Info, "Your clipboard is empty")) else scope.launch { if (Hub.command(Proto.CMD_CLIP_SET, text.toByteArray())?.ok == true) Hub.banners.tryEmit(Banner(Banner.Kind.Clipboard, "On ${pc.name}’s clipboard", text.lineSequence().first().take(60))) }
            }
        }
        val missing by Hub.missing.collectAsState()
        if (pc.revision >= 10 && Proto.CMD_FILES !in missing) { Spacer(Modifier.height(12.dp)); GlassPanel(Modifier.fillMaxWidth()) { GlassRow(Icons.Rounded.FolderOpen, "Bring from ${pc.name}", "Browse its files and take any of them", tint = t.teal, chevron = true, onClick = { MoreUi.files = "" }) } }
        if (!ready) Text("${pc.name} is away. Files go once Arnav Island runs there, on this Wi-Fi or any other.", style = Type.caption, color = t.muted, modifier = Modifier.padding(top = 12.dp, start = 6.dp))
        else if (pc.internet) Row(Modifier.padding(top = 12.dp, start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, tint = t.muted, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(6.dp))
            Text("${pc.name} is on another network: files go ${if (pc.path == 2) "straight to it over the internet" else "through the relay"}, end-to-end encrypted", style = Type.caption, color = t.muted)
        }
        // Transfers under way.
        AnimatedVisibility(transfers.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Section("Now") { transfers.values.sortedBy { it.id }.forEachIndexed { i, tr -> if (i > 0) Hairline(68.dp); TransferRow(tr) } }
        }
        // 1.8: sends waiting their turn; hold one and drag it to change the order.
        AnimatedVisibility(queue.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Section("Up next", footer = "One at a time to each PC. Touch and hold one to move it.") { QueueList(queue) }
        }
        if (moments.isNotEmpty()) {
            SectionLabel("Recent") { Text("Clear", style = Type.body, color = t.accent, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { Hub.clearMoments() }.padding(horizontal = 6.dp, vertical = 2.dp)) }
            // 1.9: find one, or show only what came, what went, or one PC's.
            var query by remember { mutableStateOf("") }; var kind by remember { mutableIntStateOf(-1) }; var who by remember { mutableStateOf<String?>(null) }
            SearchField(query, { query = it }, "Search recent")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-1 to "All", 0 to "Received", 1 to "Sent", 2 to "From a Shelf").forEach { (k, label) -> GlassChip(label, selected = kind == k) { kind = k } }
                moments.map { it.from }.distinct().take(5).forEach { name -> GlassChip(name, icon = Icons.Rounded.Laptop, selected = who == name) { who = if (who == name) null else name } }
            }
            val shown = moments.filter { (kind < 0 || it.kind == kind) && (who == null || it.from == who) && (query.isBlank() || query.trim().lowercase() in it.title.lowercase()) }
            GlassPanel(Modifier.fillMaxWidth()) {
                Column {
                    if (shown.isEmpty()) Text("Nothing matches", style = Type.body, color = t.muted, modifier = Modifier.fillMaxWidth().padding(20.dp), textAlign = TextAlign.Center)
                    shown.take(40).forEachIndexed { i, m -> key(m.at, m.title) { if (i > 0) Hairline(70.dp); SwipeAway({ Hub.forgetMoment(m) }) { MomentRow(m) } } }
                }
            }
            Text("Swipe one to the left to take it off the list.", style = Type.caption, color = t.muted, modifier = Modifier.padding(start = 18.dp, top = 7.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun TransferRow(tr: Transfer) {
    val t = LocalTokens.current
    val f = if (tr.total > 0) tr.done.toFloat() / tr.total else 0f
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            ProgressRing(f, if (tr.paused) t.faint else if (tr.outgoing) t.blue else t.good, t.track, Modifier.fillMaxSize(), 3.5.dp)
            Icon(if (tr.outgoing) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.Download, null, tint = t.text, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(tr.title, style = Type.body, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val left = if (tr.rate > 0 && !tr.paused) leftText((tr.total - tr.done) / tr.rate) else ""
            Text(listOf("${if (tr.outgoing) "To" else "From"} ${tr.name}", "${(f * 100).toInt()}%", if (tr.paused) "Paused" else if (tr.rate > 0) rateText(tr.rate) else "", left).filter { it.isNotEmpty() }.joinToString("  ·  "),
                style = Type.caption, color = if (tr.paused) t.warn else t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // 1.8: pause and resume, with PCs that wait for it (island 0.26).
        if (Hub.canPause(tr)) { GlassIconButton(if (tr.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (tr.paused) "Resume" else "Pause", { Hub.pause(tr.id, !tr.paused) }, size = 36.dp, iconSize = 17.dp); Spacer(Modifier.width(8.dp)) }
        GlassIconButton(Icons.Rounded.Close, "Stop", { Hub.cancel(tr.id) }, size = 36.dp, iconSize = 17.dp)
    }
}

/**
 * 1.8: the queue: each waiting send in a row; touch and hold one and it lifts, follows the finger, and the others make
 * room as it passes them (with a tick each time).
 */
@Composable private fun QueueList(queue: List<Queued>) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf<Long?>(null) }; var offset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(1) }
    val current by rememberUpdatedState(queue)
    Column {
        queue.forEachIndexed { i, q ->
            key(q.id) {
                val lifted = dragging == q.id
                val lift by animateFloatAsState(if (lifted) 1f else 0f, spring(dampingRatio = .7f, stiffness = 500f), label = "Lift")
                Box(Modifier.zIndex(if (lifted) 1f else 0f).onSizeChanged { rowHeight = it.height.coerceAtLeast(1) }
                    .graphicsLayer { translationY = if (lifted) offset else 0f; scaleX = 1f + .03f * lift; scaleY = 1f + .03f * lift; shadowElevation = 12.dp.toPx() * lift; shape = RoundedCornerShape(16.dp); clip = lift > 0f }
                    .background(t.fill.copy(alpha = t.fill.alpha * 3 * lift))
                    .pointerInput(q.id) {
                        detectDragGesturesAfterLongPress(onDragStart = { dragging = q.id; offset = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDragEnd = { dragging = null; offset = 0f }, onDragCancel = { dragging = null; offset = 0f }) { c, d ->
                            c.consume(); offset += d.y
                            val from = current.indexOfFirst { it.id == q.id }; val step = (offset / rowHeight).roundToInt()
                            val to = (from + step).coerceIn(0, current.lastIndex)
                            if (to != from) { Hub.moveQueued(from, to); offset -= (to - from) * rowHeight; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                        }
                    }) {
                    Column {
                        if (i > 0 && !lifted) Hairline(68.dp)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).clip(CircleShape).background(t.fill), contentAlignment = Alignment.Center) { Text("${i + 1}", style = Type.bodyStrong, color = t.muted) }
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text(q.title, style = Type.body, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("To ${q.name}  ·  ${Hub.sizeText(q.size)}  ·  waiting", style = Type.caption, color = t.muted, maxLines = 1)
                            }
                            Icon(Icons.Rounded.DragHandle, "Hold to move", tint = t.faint, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(6.dp))
                            GlassIconButton(Icons.Rounded.Close, "Don't send", { Hub.dropQueued(q.id) }, size = 34.dp, iconSize = 16.dp)
                        }
                    }
                }
            }
        }
    }
}

/** 1.8: a moment with its picture: the file's own thumbnail when it's a picture or a video that came here, else its kind. */
@Composable private fun MomentRow(m: Moment) {
    val t = LocalTokens.current; val context = LocalContext.current
    var thumb by remember(m.at) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(m.at) {
        if (m.kind == 1 || m.uris.isEmpty()) return@LaunchedEffect
        thumb = withContext(Dispatchers.IO) { runCatching {
            val uri = DownloadsInbox.uriOf(m.uris.first()) ?: return@runCatching null
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri, android.util.Size(160, 160), null)
            else context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }) }
        }.getOrNull() }
    }
    // 1.9: one that has just finished drops in from where the transfer was, with a glow that fades.
    val fresh = remember(m.at) { System.currentTimeMillis() - m.at < 2_500 }
    val reduced = LocalReduced.current
    val land = remember(m.at) { Animatable(if (fresh && !reduced) 0f else 1f) }
    LaunchedEffect(m.at) { if (land.value < 1f) land.animateTo(1f, spring(dampingRatio = .55f, stiffness = 260f)) }
    val glow = remember(m.at) { Animatable(if (fresh) 1f else 0f) }
    LaunchedEffect(m.at) { if (glow.value > 0f) { kotlinx.coroutines.delay(500); glow.animateTo(0f, tween(1400)) } }
    Row(Modifier.fillMaxWidth().graphicsLayer { val f = land.value; translationY = (1f - f) * -64.dp.toPx(); scaleX = .92f + .08f * f; scaleY = .92f + .08f * f; alpha = f.coerceIn(0f, 1f) }
        .background(t.good.copy(alpha = .16f * glow.value))
        .then(if (m.kind != 1) Modifier.clickable { openReceived(context, m) } else Modifier).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(t.fill), contentAlignment = Alignment.Center) {
            val b = thumb
            if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(when (m.kind) { 0 -> iconFor(m.title); 2 -> Icons.Rounded.Inventory2; else -> Icons.AutoMirrored.Rounded.Send }, null, tint = if (m.kind == 1) t.blue else t.good, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(m.title, style = Type.body, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${if (m.kind == 1) "To" else "From"} ${m.from}  ·  ${Hub.sizeText(m.size)}  ·  ${ago(m.at)}", style = Type.caption, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (glow.value > .05f) Box(Modifier.size(26.dp).graphicsLayer { alpha = glow.value }.clip(CircleShape).background(t.good), contentAlignment = Alignment.Center) { DrawnCheck(Color.White, Modifier.size(18.dp)) }
        else if (m.kind != 1) Chevron()
    }
}

/**
 * 1.8: a row that swipes away, as Mail's do: drag it left and a red "Remove" shows beneath; past a third of the way (or
 * with a fling) it slides off and goes, otherwise it springs back.
 */
@Composable fun SwipeAway(onRemove: () -> Unit, content: @Composable () -> Unit) {
    val t = LocalTokens.current; val scope = rememberCoroutineScope(); val haptics = LocalHapticFeedback.current
    val dx = remember { Animatable(0f) }; var width by remember { mutableIntStateOf(1) }; var armed by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width.coerceAtLeast(1) }) {
        if (dx.value < 0f) Box(Modifier.matchParentSize().background(t.danger), contentAlignment = Alignment.CenterEnd) {
            Row(Modifier.padding(end = 22.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Delete, null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Remove", style = Type.bodyStrong, color = Color.White) }
        }
        Box(Modifier.offset { IntOffset(dx.value.roundToInt(), 0) }.pointerInput(Unit) {
            detectHorizontalDragGestures(onDragEnd = {
                scope.launch { if (dx.value < -width / 3f) { dx.animateTo(-width.toFloat(), tween(180)); onRemove(); dx.snapTo(0f) } else dx.animateTo(0f, spring(dampingRatio = .7f)) }; armed = false
            }, onDragCancel = { scope.launch { dx.animateTo(0f, spring(dampingRatio = .7f)) } }) { c, amount ->
                c.consume(); scope.launch { dx.snapTo((dx.value + amount).coerceIn(-width.toFloat(), 0f)) }
                val past = dx.value < -width / 3f; if (past != armed) { armed = past; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
            }
        }) { content() }
    }
}

/** 1.8: what the Shelf tab shows over the whole app: the peek of a Shelf item. */
object ShelfUi { var peek by mutableStateOf<Pair<Int, ShelfItem>?>(null); var selecting by mutableStateOf(false); var selected by mutableStateOf(setOf<Int>()) }

/**
 * Your PC's Shelf: what is on it, each taken into Downloads with a tap. 1.8: pull down to look again (a drop of glass
 * stretches out of the top and spins as it lets go); Select to take several, or Take all; touch and hold one to peek at
 * it, with its actions under it.
 */
@Composable fun ShelfScreen(pc: PeerView?, transfers: Map<Int, Transfer>, visible: Boolean, bar: TopBar, padding: PaddingValues) {
    val t = LocalTokens.current; val scope = rememberCoroutineScope(); val density = LocalDensity.current; val haptics = LocalHapticFeedback.current
    var list by remember(pc?.id) { mutableStateOf<ShelfList?>(null) }
    var loading by remember { mutableStateOf(false) }
    var taken by remember(pc?.id) { mutableStateOf(setOf<String>()) }
    fun load() { val p = pc ?: return; if (loading) return; loading = true; scope.launch { list = withContext(Dispatchers.IO) { Hub.link?.shelf(p.id) ?: ShelfList(false, emptyList(), "Not connected") }; loading = false } }
    fun take(i: Int, item: ShelfItem) { val p = pc ?: return; if (transfers.values.any { !it.outgoing && it.title == item.name && it.peer == p.id }) return; Hub.link?.let { link -> scope.launch(Dispatchers.IO) { link.take(p.id, i, item.name) } }; taken = taken + item.name }
    LaunchedEffect(visible, pc?.id, pc?.online) { if (visible && pc?.online == true) load() }
    LaunchedEffect(pc?.id) { ShelfUi.selecting = false; ShelfUi.selected = emptySet() }
    val grid = rememberLazyGridState()
    val l = list
    // 1.9: sorted as you like (the Shelf's own order is newest first); folders open on island 0.27.
    var sort by remember { mutableIntStateOf(Hub.prefs.getInt("shelfSort", 1)) }
    val items = l?.items.orEmpty()
    val order = remember(items, sort) { items.indices.sortedWith(when (sort) { 0 -> compareBy { items[it].name.lowercase() }; 2 -> compareByDescending { items[it].size }; 3 -> compareBy<Int> { if (items[it].folder) 0 else 1 }.thenBy { items[it].name.substringAfterLast('.', "").lowercase() }; else -> compareBy { it } }) }
    bar.follow("Shelf", { if (grid.firstVisibleItemIndex > 0) 10_000 else grid.firstVisibleItemScrollOffset }, actions = if (items.isNotEmpty() && l?.shared == true) ({
        GlassButton({ ShelfUi.selecting = !ShelfUi.selecting; ShelfUi.selected = emptySet() }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) { Text(if (ShelfUi.selecting) "Done" else "Select", style = Type.bodyStrong) }
    }) else null)
    // Pull to refresh: how far the finger has pulled past the top (px), and the threshold.
    val pull = remember { Animatable(0f) }; val threshold = with(density) { 86.dp.toPx() }
    val connection = remember(pc?.id) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pull.value > 0f && available.y < 0f) { val used = maxOf(available.y, -pull.value); scope.launch { pull.snapTo(pull.value + used) }; return Offset(0f, used) }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.UserInput) { val before = pull.value; scope.launch { pull.snapTo((pull.value + available.y * .5f).coerceAtMost(threshold * 1.6f)) }
                    if (before < threshold && before + available.y * .5f >= threshold) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); return Offset(0f, available.y) }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull.value <= 0f) return Velocity.Zero
                if (pull.value >= threshold) load()
                pull.animateTo(0f, spring(dampingRatio = .7f, stiffness = 300f)); return available
            }
        }
    }
    Box(Modifier.fillMaxSize().nestedScroll(connection)) {
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            when {
                pc == null -> { ScreenTitle("Shelf", over = "Your PC’s"); Empty(Icons.Rounded.Inventory2, "Pair with your PC", "Then take anything on its Shelf with a tap") { GlassButton({ Ui.pair() }, prominent = true) { Text("Pair", style = Type.bodyStrong) } } }
                !pc.online -> { ScreenTitle("Shelf", over = "${pc.name}’s"); Empty(Icons.Rounded.WifiOff, "${pc.name} is away", "Its Shelf shows here when Arnav Island runs there") }
                l == null -> { ScreenTitle("Shelf", over = "${pc.name}’s"); Empty(Icons.Rounded.Inventory2, "Looking at ${pc.name}’s Shelf…", "") }
                l.error != null -> { ScreenTitle("Shelf", over = "${pc.name}’s"); Empty(Icons.Rounded.ErrorOutline, "Couldn’t look", l.error) { GlassButton({ load() }) { Text("Try again", style = Type.bodyStrong) } } }
                !l.shared -> { ScreenTitle("Shelf", over = "${pc.name}’s"); Empty(Icons.Rounded.Lock, "${pc.name} keeps its Shelf to itself", "Turn on “My PCs can take from the Shelf” in the island’s Settings › Privacy & productivity") }
                else -> LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize().graphicsLayer { translationY = pull.value * .55f }, state = grid,
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Column {
                            ScreenTitle("Shelf", over = "${pc.name}’s  ·  ${items.size} item${if (items.size == 1) "" else "s"}")
                            if (items.isEmpty()) Empty(Icons.Rounded.Inventory2, "The Shelf is empty", "Drop files on the island’s Shelf and they show up here. Pull down to look again")
                            else Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (ShelfUi.selecting) {
                                    GlassButton({ ShelfUi.selected = if (ShelfUi.selected.size == items.size) emptySet() else items.indices.toSet() }, Modifier.weight(1f)) { Text(if (ShelfUi.selected.size == items.size) "Select none" else "Select all", style = Type.bodyStrong) }
                                    GlassButton({ ShelfUi.selected.sorted().forEach { i -> items.getOrNull(i)?.let { take(i, it) } }; ShelfUi.selecting = false; ShelfUi.selected = emptySet() }, Modifier.weight(1f), prominent = true, enabled = ShelfUi.selected.isNotEmpty()) {
                                        Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Take ${ShelfUi.selected.size.takeIf { it > 0 } ?: ""}".trim(), style = Type.bodyStrong) }
                                } else {
                                    GlassButton({ items.forEachIndexed { i, it -> if (it.name !in taken) take(i, it) } }, Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp)) {
                                        Icon(Icons.Rounded.DownloadForOffline, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("Take all", style = Type.bodyStrong) }
                                    if (pc.revision >= 10 && Proto.CMD_FILES !in Hub.missing.collectAsState().value) GlassButton({ MoreUi.files = "" }, Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp)) {
                                        Icon(Icons.Rounded.FolderOpen, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("PC’s files", style = Type.bodyStrong) }
                                }
                            }
                            if (items.size > 1) Segmented(listOf("Name", "Newest", "Largest", "Kind"), sort, { sort = it; Hub.prefs.edit().putInt("shelfSort", it).apply() }, Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                    }
                    items(order.size, key = { order[it] }) { n ->
                        val i = order[n]; val item = items[i]
                        val moving = transfers.values.firstOrNull { !it.outgoing && it.title == item.name && it.peer == pc.id }
                        ShelfTile(item, moving, item.name in taken, ShelfUi.selecting, i in ShelfUi.selected, onPeek = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); ShelfUi.peek = i to item }) {
                            if (ShelfUi.selecting) ShelfUi.selected = if (i in ShelfUi.selected) ShelfUi.selected - i else ShelfUi.selected + i
                            else if (item.folder && pc.revision >= 10) MoreUi.files = "shelf:$i" else take(i, item)
                        }
                    }
                }
            }
        }
        // The drop of glass that stretches out of the top as you pull, and spins as it looks again.
        LiquidDrop(pull.value / threshold, loading, Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()))
    }
    ShelfPeekHost(pc, transfers, taken) { i, item -> take(i, item) }
}

/**
 * 1.8: pull to refresh as a drop of liquid glass: it grows out of the top as the finger pulls ([pull], 1 at the point
 * where letting go looks again), stretching downward like a drop about to fall, its arrow turning; while it looks it
 * becomes a round glass bubble with a spinning ring.
 */
@Composable private fun LiquidDrop(pull: Float, loading: Boolean, modifier: Modifier) {
    val t = LocalTokens.current
    val shown by animateFloatAsState(if (loading) 1f else pull.coerceIn(0f, 1.6f), if (loading) spring(dampingRatio = .6f, stiffness = 400f) else snap(), label = "Drop")
    if (shown <= .02f && !loading) return
    val spin by rememberInfiniteTransition(label = "Spin").animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "S")
    val stretch = if (loading) 1f else 1f + ((shown - .6f).coerceAtLeast(0f)) * .6f
    val size = (16 + 24 * shown.coerceAtMost(1f)).dp
    Box(modifier.offset { IntOffset(0, (shown.coerceAtMost(1.6f) * 26.dp.toPx()).roundToInt()) }.width(size).height(size * stretch)
        .glass(GlassShapes.capsule, GlassLevel.Control, t.accent).graphicsLayer { alpha = shown.coerceIn(0f, 1f) }, contentAlignment = Alignment.Center) {
        if (loading) Canvas(Modifier.size(size * .55f).graphicsLayer { rotationZ = spin }) { drawArc(t.text, 0f, 270f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)) }
        else Icon(if (shown >= 1f) Icons.Rounded.Refresh else Icons.Rounded.ArrowDownward, null, tint = t.text, modifier = Modifier.size(size * .5f).graphicsLayer { rotationZ = shown.coerceAtMost(1f) * 180f })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ShelfTile(item: ShelfItem, moving: Transfer?, done: Boolean, selecting: Boolean, selected: Boolean, onPeek: () -> Unit, onTap: () -> Unit) {
    val t = LocalTokens.current
    val preview = remember(item) { item.preview?.let { Art.bitmap(it, 256) } }
    val press = remember { MutableInteractionSource() }; val pressed by press.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) .96f else if (selecting && !selected) .97f else 1f, spring(dampingRatio = .6f, stiffness = 600f), label = "Tile")
    Column(Modifier.graphicsLayer { scaleX = s; scaleY = s }.glass(GlassShapes.tile).combinedClickable(press, null, role = Role.Button, onLongClick = onPeek, onClick = onTap)
        .semantics { contentDescription = item.name + if (selected) ", selected" else "" }.padding(10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(14.dp)).background(t.fill), contentAlignment = Alignment.Center) {
            if (preview != null) Image(preview.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(if (item.folder) Icons.Rounded.Folder else iconFor(item.name), null, tint = t.blue, modifier = Modifier.size(38.dp))
            if (moving != null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
                ProgressRing(if (moving.total > 0) moving.done.toFloat() / moving.total else 0f, Color.White, Color.White.copy(alpha = .25f), Modifier.size(46.dp), 4.dp)
            } else if (done && !selecting) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape).background(t.good), contentAlignment = Alignment.Center) { DrawnCheck(Color.White, Modifier.size(18.dp)) }
            // Selecting: a circle in the corner, filled when chosen.
            if (selecting) Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(26.dp).clip(CircleShape).background(if (selected) t.blue else Color.Black.copy(alpha = .25f))
                .then(if (!selected) Modifier.padding(2.dp).clip(CircleShape).background(Color.Transparent) else Modifier), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(17.dp)) else Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.White.copy(alpha = .25f)))
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(item.name, style = Type.callout.copy(fontWeight = FontWeight.Medium), color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        Text(if (item.folder) "Folder  ·  ${Hub.sizeText(item.size)}" else Hub.sizeText(item.size), style = Type.caption, color = t.muted, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** Where the peek is drawn: kept by the Shelf screen, shown over the app by [ShelfSheets]. */
private var peekTake: ((Int, ShelfItem) -> Unit)? = null
private var peekState: Triple<PeerView?, Map<Int, Transfer>, Set<String>>? = null
@Composable private fun ShelfPeekHost(pc: PeerView?, transfers: Map<Int, Transfer>, taken: Set<String>, take: (Int, ShelfItem) -> Unit) {
    SideEffect { peekTake = take; peekState = Triple(pc, transfers, taken) }
}

/**
 * 1.8: a Shelf item peeked at, as iOS's context menus show one: the screen dims, the item lifts out larger with its
 * picture, and its actions show under it in glass. A tap outside (or back) puts it down.
 */
@Composable fun ShelfSheets() {
    val t = LocalTokens.current; val context = LocalContext.current
    val peek = ShelfUi.peek
    if (peek != null) BackHandler { ShelfUi.peek = null }
    AnimatedVisibility(peek != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (t.dark) .55f else .3f)).clickable(remember { MutableInteractionSource() }, null) { ShelfUi.peek = null })
    }
    AnimatedVisibility(peek != null, enter = scaleIn(spring(dampingRatio = .62f, stiffness = 420f), initialScale = .82f) + fadeIn(), exit = scaleOut(tween(150), targetScale = .9f) + fadeOut(tween(150)), modifier = Modifier.fillMaxSize()) {
        var last by remember { mutableStateOf(peek) }; if (peek != null) last = peek
        val (i, item) = last ?: return@AnimatedVisibility
        val preview = remember(item) { item.preview?.let { Art.bitmap(it, 720) } }
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 380.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.fillMaxWidth().glass(GlassShapes.card, GlassLevel.Sheet).padding(12.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1.1f).clip(RoundedCornerShape(18.dp)).background(t.fill), contentAlignment = Alignment.Center) {
                        if (preview != null) Image(preview.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        else Icon(if (item.folder) Icons.Rounded.Folder else iconFor(item.name), null, tint = t.blue, modifier = Modifier.size(84.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(item.name, style = Type.headline, color = t.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
                    Text((if (item.folder) "Folder  ·  " else "") + Hub.sizeText(item.size) + "  ·  on ${peekState?.first?.name ?: "your PC"}’s Shelf", style = Type.caption, color = t.muted, modifier = Modifier.padding(horizontal = 4.dp))
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth(.82f).glass(GlassShapes.card, GlassLevel.Sheet)) {
                    val wasTaken = item.name in (peekState?.third ?: emptySet())
                    PeekAction(Icons.Rounded.Download, if (wasTaken) "Take again" else "Take it") { peekTake?.invoke(i, item); ShelfUi.peek = null }
                    Hairline(16.dp)
                    PeekAction(Icons.Rounded.CheckCircleOutline, "Select") { ShelfUi.selecting = true; ShelfUi.selected = setOf(i); ShelfUi.peek = null }
                    if (wasTaken) { Hairline(16.dp); PeekAction(Icons.Rounded.FolderOpen, "Show in Downloads") { openReceived(context, Moment(2, item.name, "", 2, item.size, 0, emptyList())); ShelfUi.peek = null } }
                }
            }
        }
    }
}
@Composable private fun PeekAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onClick() }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.body, color = t.text, modifier = Modifier.weight(1f)); Icon(icon, null, tint = t.text, modifier = Modifier.size(20.dp))
    }
}

fun iconFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp" -> Icons.Rounded.Image
    "mp4", "mov", "mkv", "avi", "webm" -> Icons.Rounded.Movie
    "mp3", "flac", "wav", "m4a", "ogg", "aac" -> Icons.Rounded.MusicNote
    "zip", "rar", "7z", "tar", "gz" -> Icons.Rounded.FolderZip
    "pdf" -> Icons.Rounded.PictureAsPdf
    else -> Icons.Rounded.Description
}

@Composable fun Empty(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, action: (@Composable () -> Unit)? = null) {
    val t = LocalTokens.current
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconTile(icon, t.blue, size = 60.dp)
            Spacer(Modifier.height(14.dp))
            Text(title, style = Type.headline, color = t.text, textAlign = TextAlign.Center)
            if (detail.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(detail, style = Type.caption, color = t.muted, textAlign = TextAlign.Center) }
            if (action != null) { Spacer(Modifier.height(16.dp)); action() }
        }
    }
}
