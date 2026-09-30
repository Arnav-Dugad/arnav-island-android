package io.github.arnavdugad.arnavisland

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Size
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.arnavdugad.arnavisland.link.PeerView
import io.github.arnavdugad.arnavisland.link.Proto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 1.7: sending, as AirDrop does it. What you share shows as a picture (a fanned stack for several). Each of your PCs is
 * a glass bubble; one tap sends it to that PC's Shelf, and a ring fills round the bubble as it goes, turning into a tick
 * when it's there (the island shows its picture as it arrives, 0.25). Your PCs also appear in Android's own share
 * sheet, so a share can go straight to one.
 */
object ShareTargets {
    const val CATEGORY = "io.github.arnavdugad.arnavisland.category.SHARE_TARGET"
    /** Each paired PC as a share target (long-lived, so Android can rank it), and none for PCs that are gone. */
    fun publish(context: Context, pcs: List<PeerView>) = runCatching {
        val wanted = pcs.filter { it.paired && !it.phone }.take(4)
        val keep = wanted.map { "pc:${it.id}" }.toSet()
        val stale = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.filter { it.startsWith("pc:") && it !in keep }
        if (stale.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, stale)
        val icon = IconCompat.createWithAdaptiveBitmap(badge(context))
        for ((rank, pc) in wanted.withIndex()) {
            val info = ShortcutInfoCompat.Builder(context, "pc:${pc.id}").setShortLabel(pc.name.take(24)).setLongLabel("${pc.name}’s Shelf").setIcon(icon)
                .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND)).setCategories(setOf(CATEGORY)).setLongLived(true).setRank(rank).build()
            ShortcutManagerCompat.pushDynamicShortcut(context, info)
        }
    }
    /** The targets' picture: a laptop on the app's glass gradient (adaptive: the middle two thirds show). */
    private fun badge(context: Context): Bitmap {
        val size = 216; val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), 0xFF3FD8B8.toInt(), 0xFF7C8CF8.toInt(), Shader.TileMode.CLAMP) })
        ContextCompat.getDrawable(context, R.drawable.ic_share_pc)?.let { d -> val inset = (size * .33f).toInt(); d.setBounds(inset, inset, size - inset, size - inset); d.setTint(0xFFFFFFFF.toInt()); d.draw(c) }
        return b
    }
}

/** What another app shared, on its way to a PC. [target]: a PC chosen in Android's share sheet (sent at once). */
data class ShareRequest(val uris: List<Uri>, val text: String?, val target: String? = null)

@Composable fun ShareSheet(request: ShareRequest?, peers: List<PeerView>, pc: PeerView?, onDone: () -> Unit) {
    val t = LocalTokens.current; val scope = rememberCoroutineScope(); val context = LocalContext.current; val haptics = LocalHapticFeedback.current
    GlassSheet(request != null, onDone) {
        if (request == null) return@GlassSheet
        val paired = peers.filter { it.paired && !it.phone }
        // Each send's transfer, its progress and whether it's done (true) or failed (false).
        val started = remember(request) { mutableStateMapOf<String, Int>() }; val finished = remember(request) { mutableStateMapOf<String, Boolean>() }
        val transfers by Hub.transfers.collectAsState()
        LaunchedEffect(request) { Hub.outcomes.collect { (id, ok) -> started.entries.firstOrNull { it.value == id }?.let { finished[it.key] = ok; haptics.performHapticFeedback(if (ok) HapticFeedbackType.Confirm else HapticFeedbackType.Reject) } } }
        fun go(p: PeerView) { if (p.id in started || !p.online) return; haptics.performHapticFeedback(HapticFeedbackType.ContextClick); started[p.id] = -1; Hub.send(p.id, request.uris, toShelf = true) { id -> started[p.id] = id } }
        // Chosen in Android's share sheet: it goes at once.
        LaunchedEffect(request) { request.target?.let { id -> paired.firstOrNull { it.id == id }?.let { go(it) } } }
        // All sent: the sheet goes a moment after the last tick.
        LaunchedEffect(finished.size, started.size) { if (started.isNotEmpty() && finished.size == started.size && finished.values.all { it }) { delay(1300); onDone() } }

        if (request.uris.isNotEmpty()) {
            Previewed(request.uris)
            Spacer(Modifier.height(18.dp))
            Text(if (started.isEmpty()) "Tap a PC to send it to its Shelf" else if (finished.size == started.size && finished.values.all { it }) "On its Shelf" else "Sending…", style = Type.caption, color = t.muted)
            Spacer(Modifier.height(14.dp))
            if (paired.isEmpty()) { Text("Pair with your PC first (Devices › Pair a PC)", style = Type.body, color = t.muted); return@GlassSheet }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                paired.take(4).forEach { p ->
                    val id = started[p.id]; val tr = id?.let { transfers[it] }
                    Bubble(p, when { finished[p.id] == true -> 1f; id == null -> -1f; tr != null && tr.total > 0 -> tr.done.toFloat() / tr.total; else -> 0f }, finished[p.id]) { go(p) }
                }
            }
        } else {
            Text("Send to your PC", style = Type.title, color = t.text)
            Spacer(Modifier.height(4.dp))
            Text(request.text?.lineSequence()?.firstOrNull()?.take(80).orEmpty(), style = Type.caption, color = t.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            if (paired.isEmpty()) { Text("Pair with your PC first (Devices › Pair a PC)", style = Type.body, color = t.muted); return@GlassSheet }
            paired.forEach { p ->
                GlassButton({ Hub.choose(p.id) }, Modifier.fillMaxWidth().padding(vertical = 4.dp), prominent = p.id == pc?.id) {
                    Icon(Icons.Rounded.Laptop, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(p.name, style = Type.bodyStrong, modifier = Modifier.weight(1f)); LiveDot(p.online, size = 7.dp)
                }
            }
            Spacer(Modifier.height(14.dp))
            val target = pc; val text = request.text.orEmpty(); val link = text.trim().takeIf { (it.startsWith("http://") || it.startsWith("https://")) && !it.contains(' ') }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton({ scope.launch { if (Hub.command(Proto.CMD_CLIP_SET, text.toByteArray())?.ok == true) Hub.banners.tryEmit(Banner(Banner.Kind.Clipboard, "On ${target?.name}’s clipboard")); onDone() } }, Modifier.weight(1f), prominent = link == null, enabled = target?.online == true) { Text("Paste on PC", style = Type.bodyStrong) }
                if (link != null) GlassButton({ scope.launch { if (Hub.command(Proto.CMD_OPEN, link.toByteArray())?.ok == true) Hub.banners.tryEmit(Banner(Banner.Kind.Info, "Opened on ${target?.name}")); onDone() } }, Modifier.weight(1f), prominent = true, enabled = target?.online == true) { Text("Open on PC", style = Type.bodyStrong) }
            }
            // 1.7: a link can be read here too, and handed on from there where you've scrolled to.
            if (link != null) {
                Spacer(Modifier.height(10.dp))
                GlassButton({ context.startActivity(Intent(context, ReaderActivity::class.java).putExtra("url", link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); onDone() }, Modifier.fillMaxWidth()) { Text("Read here", style = Type.bodyStrong) }
            }
        }
    }
}

/** What's being sent: its picture (a fanned stack of up to three for several), its name and size. */
@Composable private fun Previewed(uris: List<Uri>) {
    val t = LocalTokens.current; val context = LocalContext.current
    var pictures by remember(uris) { mutableStateOf<List<ImageBitmap?>>(emptyList()) }; var label by remember(uris) { mutableStateOf("") }
    LaunchedEffect(uris) {
        withContext(Dispatchers.IO) {
            pictures = uris.take(3).map { u -> runCatching { if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(u, Size(480, 480), null).asImageBitmap() else null }.getOrNull() }
            var name = ""; var size = 0L
            for (u in uris) runCatching { context.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) { if (name.isEmpty()) name = c.getString(0).orEmpty(); if (!c.isNull(1)) size += c.getLong(1) } } }
            label = (if (uris.size == 1) name else "${uris.size} items") + if (size > 0) "  ·  ${Hub.sizeText(size)}" else ""
        }
    }
    // It floats in, gently.
    val rise = remember(uris) { Animatable(0f) }; LaunchedEffect(uris) { rise.animateTo(1f, spring(dampingRatio = .62f, stiffness = 260f)) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(170.dp).graphicsLayer { translationY = (1f - rise.value) * 60f; alpha = rise.value; scaleX = .9f + .1f * rise.value; scaleY = scaleX }, contentAlignment = Alignment.Center) {
            val shown = pictures.ifEmpty { listOf(null) }
            shown.withIndex().reversed().forEach { (i, pic) ->
                val angle = when { shown.size == 1 -> 0f; i == 0 -> 0f; i == 1 -> -8f; else -> 7f }
                Box(Modifier.size(if (i == 0) 150.dp else 138.dp).rotate(angle * rise.value).glass(RoundedCornerShape(26.dp), GlassLevel.Control).padding(5.dp).clip(RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                    if (pic != null) Image(pic, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, null, tint = t.accent, modifier = Modifier.size(48.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(label, style = Type.bodyStrong, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A PC as a glass bubble, with the ring of its send ([progress] -1 before; 0..1 as it goes) and a tick when done. */
@Composable private fun Bubble(pc: PeerView, progress: Float, done: Boolean?, onTap: () -> Unit) {
    val t = LocalTokens.current
    val ring by animateFloatAsState(progress.coerceAtLeast(0f), tween(320), label = "Ring")
    val tick by animateFloatAsState(if (done == true) 1f else 0f, spring(dampingRatio = .45f, stiffness = 420f), label = "Tick")
    val source = remember { MutableInteractionSource() }
    val breathe = rememberInfiniteTransition(label = "Waiting"); val wait by breathe.animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "Spin")
    Column(Modifier.width(88.dp).clickable(source, null, enabled = pc.online && progress < 0) { onTap() }.graphicsLayer { alpha = if (pc.online) 1f else .45f }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(64.dp).glass(CircleShape, GlassLevel.Control, if (done == true) t.good else t.accent), contentAlignment = Alignment.Center) {
                if (tick > .01f) Icon(Icons.Rounded.Check, "Sent", tint = t.text, modifier = Modifier.size(30.dp).graphicsLayer { scaleX = tick; scaleY = tick })
                else Icon(Icons.Rounded.Laptop, null, tint = t.text, modifier = Modifier.size(28.dp))
            }
            if (progress >= 0f) Canvas(Modifier.fillMaxSize()) {
                val w = 3.dp.toPx(); val inset = w / 2
                drawArc(t.track, 0f, 360f, false, Offset(inset, inset), androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = Stroke(w))
                // Waiting for the first bytes: a short arc goes round; then the ring fills.
                if (ring <= 0f && done == null) drawArc(t.accent, wait * 360f - 90f, 60f, false, Offset(inset, inset), androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
                else drawArc(if (done == false) t.danger else if (done == true) t.good else t.accent, -90f, ring * 360f, false, Offset(inset, inset), androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(pc.name, style = Type.caption, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (!pc.online) "Away" else if (done == true) "On its Shelf" else if (done == false) "Didn’t go" else if (progress >= 0f) "${(ring * 100).toInt()}%" else quality(pc).text.substringBefore("  ·"), style = Type.caption, color = t.muted, maxLines = 1)
    }
}
