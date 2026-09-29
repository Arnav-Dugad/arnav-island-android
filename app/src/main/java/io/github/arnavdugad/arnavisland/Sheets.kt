package io.github.arnavdugad.arnavisland

import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.arnavisland.link.LinkEvent
import io.github.arnavdugad.arnavisland.link.PeerView
import io.github.arnavdugad.arnavisland.link.Proto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A glass sheet rising from the bottom over a dimmed screen. It follows the finger down and closes past a point, or
 * with back, or a tap outside it.
 */
@Composable fun GlassSheet(visible: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier, dismissible: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTokens.current; val density = LocalDensity.current; val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    LaunchedEffect(visible) { if (visible) drag.snapTo(0f) }
    if (visible) BackHandler(enabled = dismissible) { onDismiss() }
    AnimatedVisibility(visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(220))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (t.dark) .5f else .28f)).clickable(remember { MutableInteractionSource() }, null, enabled = dismissible) { onDismiss() })
    }
    AnimatedVisibility(visible, enter = slideInVertically(spring(dampingRatio = .8f, stiffness = 340f)) { it } + fadeIn(), exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200)),
        modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(modifier.fillMaxWidth().padding(10.dp).navigationBarsPadding().imePadding()
                .offset { IntOffset(0, drag.value.roundToInt()) }
                .glass(GlassShapes.sheet, GlassLevel.Sheet)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .pointerInput(dismissible) {
                    if (!dismissible) return@pointerInput
                    detectVerticalDragGestures(onDragEnd = { scope.launch { if (drag.value > with(density) { 120.dp.toPx() }) { onDismiss() } else drag.animateTo(0f, spring(dampingRatio = .7f)) } }) { change, amount ->
                        change.consume(); scope.launch { drag.snapTo((drag.value + amount).coerceAtLeast(0f)) }
                    }
                }
                .padding(horizontal = 22.dp).padding(top = 10.dp, bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(40.dp, 5.dp).clip(CircleShape).background(t.faint.copy(alpha = .5f)))
                Spacer(Modifier.height(14.dp))
                CompositionLocalProvider(LocalGlass provides null) { content() }
            }
        }
    }
}

/**
 * Pairing. The radar looks for your PCs (those with sharing on, on this Wi-Fi) and each pops up around it; tapping one
 * asks it to pair, and both show the same six digits, which roll in here. A PC that starts pairing opens this too.
 */
@Composable fun PairSheet(visible: Boolean, peers: List<PeerView>, code: LinkEvent.PairCode?, result: LinkEvent.Paired?, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    var asking by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(visible) { if (visible) { asking = null; Hub.pairResult.value = null } }
    LaunchedEffect(result) { if (result?.ok == true) { delay(1600); onDismiss() } else if (result != null) asking = null }
    GlassSheet(visible, { if (code != null) Hub.confirmPair(false); onDismiss() }) {
        AnimatedContent(when { result?.ok == true -> 3; code != null -> 2; asking != null -> 1; else -> 0 }, transitionSpec = { (fadeIn(tween(260)) + scaleIn(initialScale = .94f)) togetherWith fadeOut(tween(160)) }, label = "Pair") { stage ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when (stage) {
                    0, 1 -> {
                        Text("Pair with your PC", style = Type.title, color = t.text)
                        Spacer(Modifier.height(4.dp))
                        Text(if (stage == 1) "Asking ${peers.firstOrNull { it.id == asking }?.name ?: "your PC"}…" else "On your PC, turn on Settings › Sharing › Share with my PCs", style = Type.caption, color = t.muted, textAlign = TextAlign.Center)
                        val found = peers.filter { !it.paired && it.online && !it.phone }
                        Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                            Radar(t.accent, Modifier.size(280.dp))
                            Box(Modifier.size(58.dp).glass(CircleShape, GlassLevel.Control, t.accent), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PhoneAndroid, null, tint = t.text, modifier = Modifier.size(26.dp)) }
                            found.forEachIndexed { i, p ->
                                val angle = ((p.id.hashCode() and 0xffff) / 65535.0 * 2 * Math.PI + i * 2.1)
                                val pop = remember(p.id) { Animatable(0f) }
                                LaunchedEffect(p.id) { pop.animateTo(1f, spring(dampingRatio = .5f, stiffness = 260f)) }
                                Box(Modifier.offset { IntOffset((cos(angle) * 100.dp.toPx()).roundToInt(), (sin(angle) * 100.dp.toPx()).roundToInt()) }.graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
                                    GlassButton({ asking = p.id; Hub.pair(p.id) }, prominent = asking == p.id, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), description = "Pair with ${p.name}") {
                                        Icon(Icons.Rounded.Laptop, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text(p.name, style = Type.caption, maxLines = 1)
                                    }
                                }
                            }
                        }
                        if (result?.ok == false) Text(result.detail, style = Type.caption, color = t.danger, textAlign = TextAlign.Center)
                        else if (found.isEmpty()) Text("Looking on this Wi-Fi…", style = Type.caption, color = t.muted)
                    }
                    2 -> if (code != null) {
                        Text("Pair with ${code.name}?", style = Type.title, color = t.text, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text("Check that ${code.name} shows the same code", style = Type.caption, color = t.muted)
                        Spacer(Modifier.height(26.dp))
                        RollingDigits("%06d".format(code.code).let { it.substring(0, 3) + " " + it.substring(3) }, Type.digits.copy(fontSize = Type.digits.fontSize * 1.1f), t.text)
                        Spacer(Modifier.height(30.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GlassButton({ Hub.confirmPair(false); onDismiss() }, Modifier.weight(1f)) { Text("Not now", style = Type.bodyStrong) }
                            GlassButton({ Hub.confirmPair(true) }, Modifier.weight(1f), prominent = true) { Text("Pair", style = Type.bodyStrong) }
                        }
                    }
                    else -> {
                        Spacer(Modifier.height(20.dp))
                        Box(Modifier.size(96.dp).clip(CircleShape).background(t.good.copy(alpha = .2f)), contentAlignment = Alignment.Center) { DrawnCheck(t.good, Modifier.size(62.dp)) }
                        Spacer(Modifier.height(18.dp))
                        Text("Paired with ${result?.name ?: "your PC"}", style = Type.title, color = t.text, textAlign = TextAlign.Center)
                        Text("Files, music and the remote are ready", style = Type.caption, color = t.muted)
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}

/** Files a PC offers: what, how much, from whom; Accept or Decline. */
@Composable fun OfferSheet(offer: LinkEvent.Offer?) {
    val t = LocalTokens.current
    GlassSheet(offer != null, { offer?.let { Hub.answer(it, false) } }) {
        if (offer == null) return@GlassSheet
        Box(Modifier.size(70.dp).clip(RoundedCornerShape(22.dp)).background(t.good.copy(alpha = .18f)), contentAlignment = Alignment.Center) { Icon(if (offer.folder) Icons.Rounded.Folder else iconFor(offer.title), null, tint = t.good, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(14.dp))
        Text("${offer.name} is sending", style = Type.title, color = t.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(offer.title, style = Type.bodyStrong, color = t.text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text("${if (offer.count > 1) "${offer.count} files  ·  " else ""}${Hub.sizeText(offer.size)}  ·  to Downloads › Arnav Island", style = Type.caption, color = t.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton({ Hub.answer(offer, false) }, Modifier.weight(1f)) { Text("Decline", style = Type.bodyStrong) }
            GlassButton({ Hub.answer(offer, true) }, Modifier.weight(1f), prominent = true) { Text("Accept", style = Type.bodyStrong) }
        }
    }
}

/** Music a PC offers to continue here: its cover (blurred behind, sharp in front), where it is, Play here or Not now. */
@Composable fun MusicSheet(music: LinkEvent.Music?, transfers: Map<Int, Transfer>) {
    val t = LocalTokens.current; val context = LocalContext.current
    val cover = remember(music?.transfer) { music?.music?.cover?.let { Art.bitmap(it, 512) } }
    var answered by remember(music?.transfer) { mutableStateOf(false) }
    GlassSheet(music != null, { music?.let { Hub.answerMusic(it, false, context) } }) {
        if (music == null) return@GlassSheet
        val m = music.music
        Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
            if (cover != null) {
                // The cover's own light beneath it.
                val glow = remember(cover) { Color(Art.colors(cover).vivid) }
                Canvas(Modifier.size(230.dp).graphicsLayer { translationY = 18.dp.toPx() }) { drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = .5f), Color.Transparent), center, size.minDimension / 2)) }
                Image(cover.asImageBitmap(), "Cover", contentScale = ContentScale.Crop, modifier = Modifier.size(170.dp).clip(RoundedCornerShape(26.dp)))
            } else Box(Modifier.size(150.dp).clip(RoundedCornerShape(26.dp)).background(t.accent.copy(alpha = .18f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MusicNote, null, tint = t.accent, modifier = Modifier.size(60.dp)) }
        }
        Spacer(Modifier.height(16.dp))
        Text("Continue on this phone?", style = Type.caption, color = t.muted)
        Text(m.title, style = Type.title, color = t.text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(m.artist.takeIf { it.isNotBlank() }, "from ${music.name}", if (m.duration > 0) "at ${clock(m.position)} of ${clock(m.duration)}" else null).joinToString("  ·  "), style = Type.caption, color = t.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        val moving = transfers[music.transfer]
        if (answered && m.fileSize > 0) {
            val f = moving?.let { if (it.total > 0) it.done.toFloat() / it.total else 0f } ?: 0f
            Row(verticalAlignment = Alignment.CenterVertically) { ProgressRing(f, t.accent, t.track, Modifier.size(28.dp), 3.dp); Spacer(Modifier.width(10.dp)); Text("Bringing the song over… ${(f * 100).toInt()}%", style = Type.body, color = t.text) }
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton({ Hub.answerMusic(music, false, context) }, Modifier.weight(1f)) { Text("Not now", style = Type.bodyStrong) }
            GlassButton({ answered = true; Hub.answerMusic(music, true, context) }, Modifier.weight(1f), prominent = true) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play here", style = Type.bodyStrong) }
        }
        if (m.fileSize <= 0) Text("Your PC plays it from an app, so it continues in this phone’s music app", style = Type.caption, color = t.faint, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
    }
}

/** Find my phone: the screen pulses while it rings, until Found it. */
@Composable fun RingOverlay(from: String?) {
    val t = LocalTokens.current; val context = LocalContext.current
    AnimatedVisibility(from != null, enter = fadeIn() + scaleIn(initialScale = 1.08f), exit = fadeOut() + scaleOut(targetScale = .96f)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF120A04), Color(0xFF040306), Color(0xFF1A0E05)))).clickable(remember { MutableInteractionSource() }, null) {}, contentAlignment = Alignment.Center) {
            Box(Modifier.size(520.dp).background(Brush.radialGradient(listOf(t.warn.copy(alpha = .28f), Color.Transparent))))
            Radar(t.warn, Modifier.size(440.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(30.dp)) {
                val beat by rememberInfiniteTransition(label = "Beat").animateFloat(1f, 1.12f, infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "S")
                val swing by rememberInfiniteTransition(label = "Swing").animateFloat(-14f, 14f, infiniteRepeatable(tween(180, easing = LinearEasing), RepeatMode.Reverse), label = "W")
                Box(Modifier.size(116.dp).graphicsLayer { scaleX = beat; scaleY = beat }.clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFFFC04D), Color(0xFFFF7A2F)))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.NotificationsActive, null, tint = Color.White, modifier = Modifier.size(56.dp).graphicsLayer { rotationZ = swing })
                }
                Spacer(Modifier.height(34.dp))
                Text("Here I am", style = Type.hero, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text("${from ?: "Your PC"} is looking for this phone", style = Type.body, color = Color.White.copy(alpha = .75f), textAlign = TextAlign.Center)
                Spacer(Modifier.height(38.dp))
                Row(Modifier.clip(GlassShapes.capsule).background(Color.White).clickable(role = androidx.compose.ui.semantics.Role.Button) { Ringer.stop(context) }.padding(horizontal = 46.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Check, null, tint = Color(0xFF1A0E05)); Spacer(Modifier.width(8.dp)); Text("Found it", style = Type.bodyStrong, color = Color(0xFF1A0E05)) }
            }
        }
    }
}

/** The update tracker: every release, newest first, with its notes; this version marked, a newer one ready to install. */
@Composable fun UpdatesSheet(visible: Boolean, onDismiss: () -> Unit) {
    val t = LocalTokens.current; val context = LocalContext.current; val scope = rememberCoroutineScope()
    val releases by Updates.releases.collectAsState()
    LaunchedEffect(visible) { if (visible) Updates.check() }
    GlassSheet(visible, onDismiss, Modifier.fillMaxHeight(.86f)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Release history", style = Type.title, color = t.text); Text("You have ${Updates.current}", style = Type.caption, color = t.muted) }
            GlassIconButton(Icons.Rounded.Refresh, "Check for updates", { scope.launch { Updates.check(force = true) } }, size = 40.dp, iconSize = 19.dp)
        }
        Spacer(Modifier.height(14.dp))
        UpdateCard(onUpdates = {})
        Spacer(Modifier.height(10.dp))
        if (releases.isEmpty()) Text("No releases read yet. Check needs the internet.", style = Type.caption, color = t.muted, modifier = Modifier.padding(20.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 10.dp)) {
            items(releases, key = { it.tag }) { r ->
                val mine = r.versionName == Updates.current.substringBefore('-')
                var open by remember { mutableStateOf(mine || r == releases.firstOrNull()) }
                Column(Modifier.fillMaxWidth().glass(GlassShapes.inner).clickable { open = !open }.padding(16.dp).animateContentSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.versionName, style = Type.headline, color = t.text); Spacer(Modifier.width(8.dp))
                        if (mine) Badge("Installed", t.good) else if (Updates.newer(r.version, Updates.version(Updates.current) ?: listOf(0, 0, 0))) Badge("New", t.accent)
                        if (r.prerelease) { Spacer(Modifier.width(6.dp)); Badge("Preview", t.warn) }
                        Spacer(Modifier.weight(1f))
                        Text(r.published.take(10), style = Type.caption, color = t.faint)
                    }
                    if (r.name != r.tag && r.name.isNotBlank()) Text(r.name.removePrefix(r.tag).trim(' ', '—', '-', ':').ifBlank { r.name }, style = Type.caption, color = t.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (open && r.notes.isNotBlank()) { Spacer(Modifier.height(10.dp)); Notes(r.notes) }
                }
            }
            item { Text("All releases: ${Updates.PAGE}", style = Type.caption, color = t.accent, modifier = Modifier.clickable { openUrl(context, Updates.PAGE) }.padding(10.dp)) }
        }
    }
}
@Composable private fun Badge(text: String, color: Color) {
    Text(text, style = Type.micro, color = color, modifier = Modifier.clip(CircleShape).background(color.copy(alpha = .16f)).padding(horizontal = 8.dp, vertical = 3.dp))
}
/** Release notes in Markdown, shown simply: headings, bullets and paragraphs. */
@Composable fun Notes(markdown: String) {
    val t = LocalTokens.current
    // Links show their words; emphasis marks go; the release's own title (the sheet has one) is left out.
    val link = Regex("\\[([^\\]]+)]\\([^)]+\\)"); val numbered = Regex("^(\\d+)\\. (.*)")
    fun inline(text: String) = link.replace(text, "\$1").replace("**", "").replace("`", "").replace("*", "")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        markdown.lines().map { it.trimEnd() }.filter { it.isNotBlank() && !it.startsWith("<!--") && !it.startsWith("# ") }.take(60).forEach { line ->
            val body = line.trimStart(); val indent = ((line.length - body.length) / 2).coerceAtMost(3).dp * 16
            val number = numbered.matchEntire(body)
            when {
                body.startsWith("#") -> Text(inline(body.trimStart('#', ' ')), style = Type.bodyStrong, color = t.text, modifier = Modifier.padding(top = 8.dp))
                body.startsWith("- ") || body.startsWith("* ") -> Row(Modifier.padding(start = indent)) {
                    Text(if (indent > 0.dp) "◦" else "•", style = Type.body, color = t.accent); Spacer(Modifier.width(8.dp)); Text(inline(body.drop(2)), style = Type.body, color = t.muted) }
                number != null -> Row(Modifier.padding(start = indent)) {
                    Text("${number.groupValues[1]}.", style = Type.body, color = t.accent); Spacer(Modifier.width(8.dp)); Text(inline(number.groupValues[2]), style = Type.body, color = t.muted) }
                else -> Text(inline(body), style = Type.body, color = t.muted, modifier = Modifier.padding(start = indent))
            }
        }
    }
}

/** What another app shared, on its way to a PC: files go as a transfer; text goes to the clipboard, a link can open there. */
data class ShareRequest(val uris: List<Uri>, val text: String?)
@Composable fun ShareSheet(request: ShareRequest?, peers: List<PeerView>, pc: PeerView?, onDone: () -> Unit) {
    val t = LocalTokens.current; val scope = rememberCoroutineScope()
    GlassSheet(request != null, onDone) {
        if (request == null) return@GlassSheet
        Text("Send to your PC", style = Type.title, color = t.text)
        Spacer(Modifier.height(4.dp))
        Text(if (request.uris.isNotEmpty()) "${request.uris.size} ${if (request.uris.size == 1) "item" else "items"}" else request.text?.lineSequence()?.firstOrNull()?.take(80).orEmpty(), style = Type.caption, color = t.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        val paired = peers.filter { it.paired && !it.phone }
        if (paired.isEmpty()) { Text("Pair with your PC first (Devices › Pair a PC)", style = Type.body, color = t.muted); return@GlassSheet }
        paired.forEach { p ->
            GlassButton({ Hub.choose(p.id) }, Modifier.fillMaxWidth().padding(vertical = 4.dp), prominent = p.id == pc?.id) {
                Icon(Icons.Rounded.Laptop, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(p.name, style = Type.bodyStrong, modifier = Modifier.weight(1f)); LiveDot(p.online, size = 7.dp)
            }
        }
        Spacer(Modifier.height(14.dp))
        val target = pc
        if (request.uris.isNotEmpty()) GlassButton({ if (target != null) { Hub.send(target.id, request.uris); onDone() } }, Modifier.fillMaxWidth(), prominent = true, enabled = target?.online == true) {
            Icon(Icons.AutoMirrored.Rounded.Send, null); Spacer(Modifier.width(8.dp)); Text("Send", style = Type.bodyStrong)
        } else {
            val text = request.text.orEmpty(); val link = text.trim().takeIf { (it.startsWith("http://") || it.startsWith("https://")) && !it.contains(' ') }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton({ scope.launch { if (Hub.command(Proto.CMD_CLIP_SET, text.toByteArray())?.ok == true) Hub.banners.tryEmit(Banner(Banner.Kind.Clipboard, "On ${target?.name}’s clipboard")); onDone() } }, Modifier.weight(1f), prominent = link == null, enabled = target?.online == true) { Text("Paste on PC", style = Type.bodyStrong) }
                if (link != null) GlassButton({ scope.launch { if (Hub.command(Proto.CMD_OPEN, link.toByteArray())?.ok == true) Hub.banners.tryEmit(Banner(Banner.Kind.Info, "Opened on ${target?.name}")); onDone() } }, Modifier.weight(1f), prominent = true, enabled = target?.online == true) { Text("Open on PC", style = Type.bodyStrong) }
            }
        }
    }
}

/** A link to open in the PC's browser: typed, or already on the clipboard. */
@Composable fun LinkSheet(visible: Boolean, pc: PeerView?, onDismiss: () -> Unit) {
    val t = LocalTokens.current; val context = LocalContext.current; val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        val clip = runCatching { context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim() }.getOrNull().orEmpty()
        text = if (clip.startsWith("http://") || clip.startsWith("https://")) clip else ""
        delay(250); runCatching { focus.requestFocus() }
    }
    fun go() {
        var url = text.trim(); if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        scope.launch { if (Hub.command(Proto.CMD_OPEN, url.toByteArray())?.ok == true) { Hub.banners.tryEmit(Banner(Banner.Kind.Info, "Opened on ${pc?.name}", url.take(60))); onDismiss() } }
    }
    GlassSheet(visible, onDismiss) {
        Text("Open on ${pc?.name ?: "your PC"}", style = Type.title, color = t.text)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().glass(GlassShapes.capsule, GlassLevel.Control).padding(horizontal = 18.dp, vertical = 15.dp)) {
            if (text.isEmpty()) Text("A web address", style = Type.body, color = t.faint)
            BasicTextField(text, { text = it }, singleLine = true, textStyle = Type.body.copy(color = t.text), cursorBrush = SolidColor(t.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus))
        }
        Spacer(Modifier.height(16.dp))
        GlassButton({ go() }, Modifier.fillMaxWidth(), prominent = true, enabled = text.isNotBlank()) { Icon(Icons.Rounded.Language, null); Spacer(Modifier.width(8.dp)); Text("Open", style = Type.bodyStrong) }
    }
}

/** After an update: what this version brought. */
@Composable fun WhatsNewSheet(release: Release?, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    GlassSheet(release != null, onDismiss, Modifier.fillMaxHeight(.8f)) {
        if (release == null) return@GlassSheet
        Icon(Icons.Rounded.NewReleases, null, tint = t.accent, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text("What’s new in ${release.versionName}", style = Type.title, color = t.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) { item { Notes(release.notes) } }
        Spacer(Modifier.height(14.dp))
        GlassButton(onDismiss, Modifier.fillMaxWidth(), prominent = true) { Text("Continue", style = Type.bodyStrong) }
    }
}
