package io.github.arnavdugad.arnavisland

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.arnavdugad.arnavisland.link.FileEntry
import io.github.arnavdugad.arnavisland.link.FileList
import io.github.arnavdugad.arnavisland.link.PcClip
import io.github.arnavdugad.arnavisland.link.PcProcess
import io.github.arnavdugad.arnavisland.link.PeerView
import io.github.arnavdugad.arnavisland.link.Proto
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 1.9: the sheets of this version, drawn over the whole app like the others. */
object MoreUi { var gemini by mutableStateOf(false); var clipboard by mutableStateOf(false); var files by mutableStateOf<String?>(null); var tour by mutableStateOf(false); var sections by mutableStateOf(false)
    /** 1.11: the PC's Start menu's apps, to open one there. */
    var apps by mutableStateOf(false) }
@Composable fun MoreSheets(pc: PeerView?) {
    ClipboardSheet(MoreUi.clipboard, pc) { MoreUi.clipboard = false }
    PcAppsSheet(MoreUi.apps, pc) { MoreUi.apps = false }
    FilesSheet(MoreUi.files, pc) { MoreUi.files = null }
    SectionsSheet(MoreUi.sections) { MoreUi.sections = false }
    TourSheet(MoreUi.tour) { MoreUi.tour = false }
    AssistantHelpSheet(MoreUi.gemini) { MoreUi.gemini = false }
}

// ---- the clipboard's history (#29) ----
/**
 * The clipboard's history, both ways: this phone's (what you copied here, and what came from your PCs) and the PC's own
 * (island 0.27). Tap one to copy it here; Paste on the PC; pin the ones to keep; swipe one away.
 */
@Composable private fun ClipboardSheet(visible: Boolean, pc: PeerView?, onDismiss: () -> Unit) {
    val t = LocalTokens.current; val context = LocalContext.current; val scope = rememberCoroutineScope()
    val mine by Hub.clipHistory.collectAsState(); val theirs by Hub.pcClips.collectAsState()
    var side by remember { mutableIntStateOf(0) }; var query by remember { mutableStateOf("") }
    val canPc = pc != null && Hub.rev10(pc)
    LaunchedEffect(visible, side) { if (visible && side == 1 && canPc) while (isActive) { Hub.refreshPcClips(); delay(4000) } }
    fun copyHere(text: String) { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Arnav Island", text)); Hub.banners.tryEmit(Banner(Banner.Kind.Clipboard, "Copied", text.lineSequence().first().take(60))) }
    fun pasteThere(text: String) { scope.launch { if (Hub.command(Proto.CMD_CLIP_SET, text.toByteArray())?.ok == true) Hub.banners.tryEmit(Banner(Banner.Kind.Clipboard, "On ${pc?.name}’s clipboard", text.lineSequence().first().take(60))) } }
    GlassSheet(visible, onDismiss, Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .9f)) {
        SheetHeader("Clipboard", null, onDismiss)
        Spacer(Modifier.height(8.dp))
        if (pc != null) Segmented(listOf("This phone", pc.name), side, { side = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        SearchField(query, { query = it }, "Search")
        Spacer(Modifier.height(6.dp))
        val q = query.trim().lowercase()
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (side == 0) {
                val list = mine.filter { q.isEmpty() || q in it.text.lowercase() }
                if (list.isEmpty()) item { Text(if (mine.isEmpty()) "Nothing copied yet" else "Nothing matches", style = Type.body, color = t.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp)) }
                items(list, key = { "${it.at}${it.text.hashCode()}" }) { c ->
                    GlassPanel(Modifier.fillMaxWidth()) { SwipeAway({ Hub.forgetClip(c) }) {
                        ClipRow(c.text, "${c.from}  ·  ${ago(c.at)}", c.pinned, null, onTap = { copyHere(c.text) }, onPin = { Hub.pinClip(c, !c.pinned) }, action = if (pc != null) "Paste on ${pc.name}" else null) { pasteThere(c.text) }
                    } }
                }
            } else when {
                !canPc -> item { Text("Needs island 0.27", style = Type.body, color = t.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp)) }
                else -> {
                    val list = theirs.filter { q.isEmpty() || q in it.text.lowercase() }
                    if (list.isEmpty()) item { Text("Nothing copied yet", style = Type.body, color = t.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp)) }
                    items(list, key = { it.id }) { c ->
                        val thumb = remember(c.id) { c.thumb?.let { Art.bitmap(it, 160) } }
                        GlassPanel(Modifier.fillMaxWidth()) { SwipeAway({ Hub.pcClipOp(3, c) }) {
                            ClipRow(c.text.ifBlank { if (c.kind == 1) "A picture" else "Files" }, "${when (c.kind) { 1 -> "Picture"; 2 -> "Files"; else -> "Text" }}  ·  ${ago(c.at)}", c.pinned, thumb,
                                onTap = { scope.launch { Hub.pcClipHere(c)?.let { full -> if (full.text.isNotEmpty()) copyHere(full.text) else Hub.banners.tryEmit(Banner(Banner.Kind.Info, "Pictures come as files", "Use Copy on ${pc?.name} instead")) } ?: Hub.banners.tryEmit(Banner(Banner.Kind.Failed, "That one stays on ${pc?.name}")) } },
                                onPin = { Hub.pcClipOp(2, c, if (c.pinned) 0 else 1) }, action = "Copy on ${pc?.name}") { Hub.pcClipOp(1, c); Hub.banners.tryEmit(Banner(Banner.Kind.Clipboard, "On ${pc?.name}’s clipboard", c.text.take(60))) }
                        } }
                    }
                }
            }
        }
    }
}
@Composable private fun ClipRow(text: String, detail: String, pinned: Boolean, thumb: android.graphics.Bitmap?, onTap: () -> Unit, onPin: () -> Unit, action: String?, onAction: () -> Unit) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onTap() }.padding(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            if (thumb != null) { Image(thumb.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))); Spacer(Modifier.width(12.dp)) }
            Text(text, style = Type.body, color = t.text, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(Modifier.size(34.dp).clip(CircleShape).clickable(role = Role.Switch) { onPin() }.semantics { contentDescription = if (pinned) "Unpin" else "Pin" }, contentAlignment = Alignment.Center) {
                Icon(if (pinned) Icons.Rounded.PushPin else Icons.Rounded.PushPin, null, tint = if (pinned) t.warn else t.faint, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = if (pinned) 0f else 35f })
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(detail, style = Type.caption, color = t.muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (action != null) Text(action, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = t.accent, modifier = Modifier.clip(CircleShape).clickable { onAction() }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

// ---- the PC's files (#26) and its Shelf's folders (#28) ----
/**
 * The PC's files, read-only (island 0.27, with "My phone can browse this PC's files" on there): its places and drives,
 * then folder by folder, sorted as you like. Tap a file (or hold a folder) and it comes to this phone.
 */
@Composable private fun FilesSheet(start: String?, pc: PeerView?, onDismiss: () -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    var stack by remember { mutableStateOf(listOf<String>()) }; var sort by remember { mutableIntStateOf(Hub.prefs.getInt("filesSort", 0)) }
    var page by remember { mutableStateOf<FileList?>(null) }; var status by remember { mutableIntStateOf(Proto.OK) }; var loading by remember { mutableStateOf(false) }
    LaunchedEffect(start) { if (start != null) { stack = listOf(start); page = null } }
    val path = stack.lastOrNull()
    LaunchedEffect(path, sort) { if (path == null) return@LaunchedEffect; loading = true; val (s, l) = Hub.files(path, sort); status = s; page = l; loading = false }
    GlassSheet(start != null, onDismiss, Modifier.fillMaxHeight(.92f)) {
        if (stack.size > 1) BackHandler { stack = stack.dropLast(1) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (stack.size > 1) Row(Modifier.clip(GlassShapes.capsule).clickable { stack = stack.dropLast(1) }.padding(end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, "Back", tint = t.accent, modifier = Modifier.size(18.dp)); Text("Back", style = Type.body, color = t.accent)
            }
            Spacer(Modifier.weight(1f)); GlassIconButton(Icons.Rounded.Close, "Close", onDismiss, size = 34.dp, iconSize = 17.dp)
        }
        Text(page?.title?.ifBlank { null } ?: if (path == "") "${pc?.name ?: "Your PC"}" else if (path?.startsWith("shelf:") == true) "Shelf" else "Files", style = Type.title, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(start = 4.dp))
        if (!page?.path.isNullOrBlank()) Text(page!!.path, style = Type.caption, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
        Spacer(Modifier.height(10.dp))
        Segmented(listOf("Name", "Newest", "Largest", "Kind"), sort, { sort = it; Hub.prefs.edit().putInt("filesSort", it).apply() }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        when {
            pc == null || !Hub.rev10(pc) -> Message(Icons.Rounded.SystemUpdate, "Update Arnav Island on ${pc?.name ?: "your PC"}", "Needs version 0.27 or later.")
            status == Proto.UNSUPPORTED -> Message(Icons.Rounded.Schedule, "Not supported on ${pc.name} yet", "")
            status == Proto.NOT_ALLOWED -> Message(Icons.Rounded.Lock, "File browsing is off", "Turn on “My phone can browse this PC's files” in the island’s Settings.")
            page == null && loading -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Radar(t.accent, Modifier.size(48.dp)) }
            page == null -> Message(Icons.Rounded.ErrorOutline, "Couldn’t load", "${pc.name} didn’t answer.")
            page!!.entries.isEmpty() -> Message(Icons.Rounded.FolderOpen, "Empty folder", "")
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 12.dp)) {
                item { Text("${page!!.total} item${if (page!!.total == 1L) "" else "s"}", style = Type.caption, color = t.muted, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)) }
                items(page!!.entries.distinctBy { "${it.folder}:${it.path}" }, key = { "${it.folder}:${it.path}" }) { e ->
                    FileRow(e, onOpen = { if (e.folder) stack = stack + e.path else { haptics.performHapticFeedback(HapticFeedbackType.Confirm); Hub.takeFromPc(e.path, e.name) } },
                        onTake = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); Hub.takeFromPc(e.path, e.name) })
                }
            }
        }
    }
}
@Composable private fun FileRow(e: FileEntry, onOpen: () -> Unit, onTake: () -> Unit) {
    val t = LocalTokens.current
    val thumb = remember(e.path) { e.thumb?.let { Art.bitmap(it, 160) } }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { onOpen() }.padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(t.fill), contentAlignment = Alignment.Center) {
            if (thumb != null) Image(thumb.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(when (e.kind) { 1 -> Icons.Rounded.Folder; 2 -> Icons.Rounded.Storage; 3 -> placeIcon(e.name); else -> iconFor(e.name) }, null, tint = if (e.folder) t.blue else t.muted, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = Type.body, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(when (e.kind) { 2 -> "${Hub.sizeText(e.size)} free"; 1, 3 -> if (e.modified > 0) ago(e.modified * 1000) else "Folder"; else -> listOf(Hub.sizeText(e.size), if (e.modified > 0) ago(e.modified * 1000) else "").filter { it.isNotEmpty() }.joinToString("  ·  ") },
                style = Type.caption, color = t.muted, maxLines = 1)
        }
        if (e.kind == 1) GlassIconButton(Icons.Rounded.Download, "Bring ${e.name} here", onTake, size = 34.dp, iconSize = 17.dp)
        else if (e.kind == 0) Icon(Icons.Rounded.Download, null, tint = t.faint, modifier = Modifier.size(20.dp))
        if (e.folder) Chevron()
    }
}
private fun placeIcon(name: String): ImageVector = when (name.lowercase()) {
    "desktop" -> Icons.Rounded.DesktopWindows; "documents" -> Icons.Rounded.Description; "downloads" -> Icons.Rounded.Download; "pictures" -> Icons.Rounded.Image
    "music" -> Icons.Rounded.MusicNote; "videos" -> Icons.Rounded.Movie; else -> Icons.Rounded.Folder
}
@Composable private fun Message(icon: ImageVector, title: String, detail: String) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconTile(icon, t.blue, size = 54.dp); Spacer(Modifier.height(12.dp))
        Text(title, style = Type.headline, color = t.text, textAlign = TextAlign.Center)
        if (detail.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(detail, style = Type.body, color = t.muted, textAlign = TextAlign.Center) }
    }
}

// ---- the PC's top apps (#17) ----
/** The PC's apps by what they use (island 0.27): each with its icon, CPU and memory as a bar. 1.11: only read, as Task Manager's list. */
@Composable fun AppsPanel(pc: PeerView, visible: Boolean) {
    val t = LocalTokens.current; val list by Hub.processes.collectAsState(); val icons by Hub.processIcons.collectAsState()
    var sort by remember { mutableIntStateOf(Hub.processSort) }
    LaunchedEffect(visible, sort, pc.id) { Hub.processSort = sort; if (visible && Hub.rev10(pc)) while (isActive) { Hub.refreshProcesses(); delay(2500) } }
    val missing by Hub.missing.collectAsState()
    if (!Hub.rev10(pc) || Proto.CMD_PROCESSES in missing) { GlassPanel(Modifier.fillMaxWidth()) { Text(if (!Hub.rev10(pc)) "Needs island 0.27" else "Not supported on ${pc.name} yet", style = Type.caption, color = t.muted, modifier = Modifier.padding(16.dp)) }; return }
    GlassPanel(Modifier.fillMaxWidth()) {
        Column {
            Segmented(listOf("By CPU", "By memory"), sort, { sort = it }, Modifier.fillMaxWidth().padding(12.dp))
            // Its rows' room is kept while the first answer comes, so nothing under it moves.
            if (list.isEmpty()) Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) { Radar(t.accent, Modifier.size(36.dp)) }
            // Sorted here too, so a row that changes places does so as the PC's next answer says.
            val shown = (if (sort == 0) list.sortedByDescending { it.cpu } else list.sortedByDescending { it.memory }).take(10)
            val top = shown.maxOfOrNull { if (sort == 0) it.cpu.toDouble() else it.memory.toDouble() }?.coerceAtLeast(1.0) ?: 1.0
            shown.forEachIndexed { i, p ->
                if (i > 0) Hairline(58.dp)
                AppRow(p, icons[p.name], (if (sort == 0) p.cpu / top else p.memory / top).toFloat(), sort)
            }
        }
    }
}
@Composable private fun AppRow(p: PcProcess, icon: androidx.compose.ui.graphics.ImageBitmap?, share: Float, sort: Int) {
    val t = LocalTokens.current; val f by animateFloatAsState(share.coerceIn(0f, 1f), spring(dampingRatio = .9f, stiffness = 120f), label = "App")
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(t.fill), contentAlignment = Alignment.Center) {
            if (icon != null) Image(icon, null, Modifier.size(24.dp)) else Icon(Icons.Rounded.Apps, null, tint = t.muted, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = Type.callout, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                RollingNumber(if (sort == 0) "%.1f%%".format(p.cpu) else Hub.sizeText(p.memory), Type.caption, t.muted)
            }
            if (p.title.isNotBlank()) Text(p.title, style = Type.caption, color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(t.track)) { Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(CircleShape).background(if (sort == 0) t.blue else t.good)) }
        }
    }
}

// ---- an hour of the PC's numbers (#18) ----
/** The last hour on this PC, kept on this phone between visits: the processor and graphics, a point every ten seconds. */
@Composable fun HourPanel() {
    val t = LocalTokens.current; val hour by Hub.hourTrail.collectAsState()
    if (hour.size < 6) return
    val minutes = ((hour.last().at - hour.first().at) / 60_000).coerceAtLeast(1)
    Section(null) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("The last ${if (minutes >= 59) "hour" else "$minutes min"}", style = Type.bodyStrong, color = t.text, modifier = Modifier.weight(1f))
                Dot(t.blue); Text(" CPU  ", style = Type.caption, color = t.muted); Dot(t.purple); Text(" GPU", style = Type.caption, color = t.muted)
            }
            Spacer(Modifier.height(10.dp))
            Canvas(Modifier.fillMaxWidth().height(80.dp).semantics { contentDescription = "The last hour's processor and graphics" }) {
                val first = hour.first().at; val span = (hour.last().at - first).coerceAtLeast(1).toFloat()
                fun line(pick: (Hub.StatsPoint) -> Float, c: Color) {
                    val path = Path(); var started = false
                    hour.forEach { p -> val v = pick(p); if (v < 0) return@forEach; val x = (p.at - first) / span * size.width; val y = size.height - v / 100f * size.height * .92f
                        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y) }
                    drawPath(path, c, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                for (k in 1..3) drawLine(t.hairline, Offset(0f, size.height * k / 4f), Offset(size.width, size.height * k / 4f), .5.dp.toPx())
                line({ it.gpu }, t.purple); line({ it.cpu }, t.blue)
            }
            Row(Modifier.fillMaxWidth()) { Text(ago(hour.first().at), style = Type.micro, color = t.faint, modifier = Modifier.weight(1f)); Text("Now", style = Type.micro, color = t.faint) }
        }
    }
}
@Composable private fun Dot(c: Color) { Box(Modifier.size(8.dp).clip(CircleShape).background(c)) }

// ---- the Island tab's sections, in your order (#16) ----
/** Touch and hold a section to move it; the pin keeps it at the top. */
@Composable private fun SectionsSheet(visible: Boolean, onDismiss: () -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    val order by Hub.sectionOrder.collectAsState(); val pins by Hub.sectionPins.collectAsState()
    val all = remember(order) { (order.filter { k -> IslandSections.any { it.first == k } } + IslandSections.map { it.first }.filter { it !in order }) }
    var list by remember(visible) { mutableStateOf(all) }
    var dragging by remember { mutableStateOf<String?>(null) }; var offset by remember { mutableFloatStateOf(0f) }; var row by remember { mutableIntStateOf(1) }
    GlassSheet(visible, { Hub.setSections(list, pins); onDismiss() }, Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .9f)) {
        SheetHeader("Island sections", "Hold to reorder", { Hub.setSections(list, pins); onDismiss() })
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            GlassPanel(Modifier.fillMaxWidth()) {
                Column {
                    list.forEachIndexed { i, key ->
                        key(key) {
                            val name = IslandSections.firstOrNull { it.first == key }?.second ?: key; val lifted = dragging == key; val pinned = key in pins
                            Box(Modifier.zIndex(if (lifted) 1f else 0f).onSizeChanged { row = it.height.coerceAtLeast(1) }
                                .graphicsLayer { translationY = if (lifted) offset else 0f; shadowElevation = if (lifted) 10.dp.toPx() else 0f; shape = RoundedCornerShape(14.dp); clip = lifted }
                                .background(if (lifted) t.fill.copy(alpha = .9f) else Color.Transparent)
                                .pointerInput(key) {
                                    detectDragGesturesAfterLongPress(onDragStart = { dragging = key; offset = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                        onDragEnd = { dragging = null; offset = 0f }, onDragCancel = { dragging = null; offset = 0f }) { c, d ->
                                        c.consume(); offset += d.y; val from = list.indexOf(key); val to = (from + (offset / row).roundToInt()).coerceIn(0, list.lastIndex)
                                        if (to != from) { list = list.toMutableList().apply { add(to, removeAt(from)) }; offset -= (to - from) * row; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                                    }
                                }) {
                                Column {
                                    if (i > 0 && !lifted) Hairline(16.dp)
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.DragHandle, null, tint = t.faint, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(12.dp))
                                        Text(name, style = Type.body, color = t.text, modifier = Modifier.weight(1f))
                                        Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Switch) { Hub.setSections(list, if (pinned) pins - key else pins + key) }.semantics { contentDescription = if (pinned) "Unpin $name" else "Pin $name" }, contentAlignment = Alignment.Center) {
                                            Icon(Icons.Rounded.PushPin, null, tint = if (pinned) t.warn else t.faint, modifier = Modifier.size(19.dp).graphicsLayer { rotationZ = if (pinned) 0f else 35f })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Reset order", style = Type.body, color = t.accent, modifier = Modifier.align(Alignment.CenterHorizontally).clip(CircleShape).clickable { list = IslandSections.map { it.first }; Hub.setSections(list, emptySet()) }.padding(12.dp))
        }
    }
}
/** The Island tab's sections, in their usual order: their keys and names. */
val IslandSections = listOf("overview" to "Overview", "performance" to "Performance", "apps" to "Top apps", "battery" to "Battery", "storage" to "Storage", "network" to "Network",
    "sound" to "Sound", "controls" to "Controls", "focus" to "Focus", "run" to "Run", "power" to "Power", "updates" to "Windows Update", "startup" to "Startup",
    "alerts" to "Alerts", "pages" to "On the PC", "settings" to "Settings", "about" to "About")
/** The sections in the order they show: the pinned ones first, then the rest, each as you've arranged them. */
fun orderedSections(order: List<String>, pins: Set<String>): List<String> {
    val all = order.filter { k -> IslandSections.any { it.first == k } } + IslandSections.map { it.first }.filter { it !in order }
    return all.filter { it in pins } + all.filterNot { it in pins }
}

// ---- the speed test (#37) ----
/** The connection's speed both ways, measured for a few seconds each, as it goes (island 0.27). */
@Composable fun SpeedTest(pc: PeerView) {
    val t = LocalTokens.current; val scope = rememberCoroutineScope()
    var down by remember(pc.id) { mutableStateOf<Double?>(null) }; var up by remember(pc.id) { mutableStateOf<Double?>(null) }; var running by remember { mutableStateOf(false) }; var failed by remember { mutableStateOf(false) }
    fun mbps(v: Double?) = v?.let { if (it >= 1e6) "%.1f".format(it / 1e6) else "%.2f".format(it / 1e6) } ?: "—"
    Section("Speed", footer = if (!Hub.rev10(pc)) "Needs island 0.27." else null) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) { RollingNumber(mbps(down), Type.title, t.text); Text(" Mb/s", style = Type.caption, color = t.muted, modifier = Modifier.padding(bottom = 4.dp)) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.South, null, tint = t.good, modifier = Modifier.size(14.dp)); Text(" From ${pc.name}", style = Type.caption, color = t.muted) }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) { RollingNumber(mbps(up), Type.title, t.text); Text(" Mb/s", style = Type.caption, color = t.muted, modifier = Modifier.padding(bottom = 4.dp)) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.North, null, tint = t.warn, modifier = Modifier.size(14.dp)); Text(" To ${pc.name}", style = Type.caption, color = t.muted) }
                }
            }
            if (failed) Text("Connection dropped", style = Type.caption, color = t.danger, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            GlassButton({ running = true; failed = false; down = null; up = null
                scope.launch { val r = Hub.speedTest { d, u -> if (d != null) down = d; if (u != null) up = u }; if (r == null) failed = true else { down = r.first; up = r.second }; running = false } },
                Modifier.fillMaxWidth(), enabled = !running && Hub.rev10(pc), contentPadding = PaddingValues(vertical = 13.dp)) {
                if (running) { Radar(t.accent, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Measuring…", style = Type.bodyStrong) } else { Icon(Icons.Rounded.Speed, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Test speed", style = Type.bodyStrong) }
            }
        }
    }
}

// ---- what's new (#6) ----
private class Stop(val icon: ImageVector, val colour: @Composable () -> Color, val title: String, val text: String, val where: String?)
/** After an update: a few pages of what this version brought and where things moved, each with a way to it. */
@Composable private fun TourSheet(visible: Boolean, onDismiss: () -> Unit) {
    val t = LocalTokens.current; val scope = rememberCoroutineScope()
    val stops = listOf(
        Stop(Icons.Rounded.AutoAwesome, { t.blue }, "A new look", "Clean, solid and simpler.", null),
        Stop(Icons.Rounded.DeliveryDining, { t.warn }, "Deliveries on your PC", "Orders, rides and timers, live on your PC’s island.", "Settings › On your PC’s island"),
        Stop(Icons.Rounded.SpaceDashboard, { t.indigo }, "Your PC, at a glance", "Apps, temperatures and the last hour. Arrange and pin sections.", "Island › Edit"),
        Stop(Icons.Rounded.FolderOpen, { t.teal }, "Files, clipboard, screenshots", "Browse your PC’s files, see what you copied, take screenshots.", "Remote › Quick actions"),
        Stop(Icons.Rounded.Mic, { t.purple }, "Ask Gemini", "“Lock my PC.” “What’s playing on my PC?”", "Settings › On this phone › Use with Gemini"),
        Stop(Icons.Rounded.Settings, { t.gray }, "Settings moved", "Now in their own sheet, with search.", "Devices › Settings"),
    )
    val pager = rememberPagerState { stops.size }
    GlassSheet(visible, onDismiss, Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .8f)) {
        HorizontalPager(pager, Modifier.fillMaxWidth().height(340.dp)) { i ->
            val s = stops[i]
            val shown = pager.currentPage == i
            val lift by animateFloatAsState(if (shown) 1f else .85f, spring(dampingRatio = .6f, stiffness = 300f), label = "Stop")
            Column(Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                IconTile(s.icon, s.colour(), Modifier.graphicsLayer { scaleX = lift; scaleY = lift }, size = 84.dp)
                Spacer(Modifier.height(22.dp))
                Text(s.title, style = Type.title, color = t.text, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(s.text, style = Type.body, color = t.muted, textAlign = TextAlign.Center)
                if (s.where != null) { Spacer(Modifier.height(12.dp)); Text(s.where, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = t.accent) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
            stops.indices.forEach { i -> val on = i == pager.currentPage; val w by animateDpAsState(if (on) 20.dp else 7.dp, spring(dampingRatio = .7f, stiffness = 500f), label = "Dot")
                Box(Modifier.padding(horizontal = 3.dp).size(w, 7.dp).clip(CircleShape).background(if (on) t.accent else t.faint)) }
        }
        GlassButton({ if (pager.currentPage < stops.lastIndex) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } else onDismiss() }, Modifier.fillMaxWidth(), prominent = true, contentPadding = PaddingValues(vertical = 15.dp)) {
            Text(if (pager.currentPage < stops.lastIndex) "Next" else "Get started", style = Type.headline)
        }
        Text("Skip", style = Type.body, color = t.muted, modifier = Modifier.clip(CircleShape).clickable { onDismiss() }.padding(12.dp))
    }
}
