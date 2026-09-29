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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.onGloballyPositioned
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
    if (visible) { BackHandler(enabled = dismissible) { onDismiss() }; DisposableEffect(Unit) { Scene.sheets++; onDispose { Scene.sheets-- } } }
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

/** Where the pairing sheet opens: the radar (this Wi-Fi), typing a code, the QR scanner, or finding a PC by its link. */
const val PAIR_NEARBY = 0; const val PAIR_TYPE = 4; const val PAIR_SCAN = 5; const val PAIR_FINDING = 6

/**
 * Pairing. The radar looks for your PCs (those with sharing on, on this Wi-Fi) and each pops up around it; tapping one
 * asks it to pair, and both show the same six digits, which roll in here. A PC that starts pairing opens this too.
 * On another network, the island's own code (Shelf › Nearby › Pair with a code) pairs through the relay, the same way:
 * scanned from its QR code (which names that PC's key, so only the PC asks to confirm) or typed.
 */
@Composable fun PairSheet(visible: Boolean, peers: List<PeerView>, code: LinkEvent.PairCode?, result: LinkEvent.Paired?, onDismiss: () -> Unit, start: Int = PAIR_NEARBY) {
    val t = LocalTokens.current
    var asking by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableIntStateOf(PAIR_NEARBY) }
    val finding by Hub.codePairing.collectAsState()
    LaunchedEffect(visible) { if (visible) { asking = null; mode = start; if (start != PAIR_FINDING) Hub.pairResult.value = null } }
    LaunchedEffect(result) { if (result?.ok == true) { delay(1600); onDismiss() } else if (result != null) asking = null }
    // A pairing link opened while this shows: its PC is being found.
    LaunchedEffect(finding) { if (finding != null && mode != PAIR_TYPE) mode = PAIR_FINDING }
    GlassSheet(visible, { if (code != null && !code.confirmed) Hub.confirmPair(false); onDismiss() }) {
        AnimatedContent(when { result?.ok == true -> 3; code != null -> 2; mode != PAIR_NEARBY -> mode; asking != null -> 1; else -> 0 }, transitionSpec = { (fadeIn(tween(260)) + scaleIn(initialScale = .94f)) togetherWith fadeOut(tween(160)) }, label = "Pair") { stage ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when (stage) {
                    0, 1 -> {
                        Text("Pair with your PC", style = Type.title, color = t.text)
                        Spacer(Modifier.height(4.dp))
                        Text(if (stage == 1) "Asking ${peers.firstOrNull { it.id == asking }?.name ?: "your PC"}…" else "On your PC, turn on Settings › Privacy & productivity › Share with my PCs", style = Type.caption, color = t.muted, textAlign = TextAlign.Center)
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
                        Spacer(Modifier.height(14.dp))
                        Text("On another network?", style = Type.caption, color = t.faint)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassButton({ Hub.pairResult.value = null; mode = PAIR_SCAN }, prominent = true, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp), description = "Scan the island’s QR code") {
                                Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Scan QR code", style = Type.caption)
                            }
                            GlassButton({ Hub.pairResult.value = null; mode = PAIR_TYPE }, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp), description = "Type the island’s code") {
                                Icon(Icons.Rounded.Keyboard, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Type a code", style = Type.caption)
                            }
                        }
                    }
                    PAIR_TYPE -> CodeEntry(finding != null, result?.takeIf { !it.ok }?.detail, onScan = { Hub.pairResult.value = null; mode = PAIR_SCAN }, onBack = { mode = PAIR_NEARBY; Hub.pairResult.value = null }) { typed -> Hub.pairWithCode(typed) }
                    PAIR_SCAN -> ScanPane({ link -> Hub.pairWithCode(link.code, link.key); mode = PAIR_FINDING }, { Hub.pairResult.value = null; mode = PAIR_TYPE })
                    PAIR_FINDING -> Finding(result?.takeIf { !it.ok }?.detail, onScan = { Hub.pairResult.value = null; mode = PAIR_SCAN }, onType = { Hub.pairResult.value = null; mode = PAIR_TYPE }, onHide = onDismiss)
                    2 -> if (code != null) {
                        Text(if (code.confirmed) "Confirm on ${code.name}" else "Pair with ${code.name}?", style = Type.title, color = t.text, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text(if (code.confirmed) "It shows the same code. Choose Pair there to finish" else "Check that ${code.name} shows the same code", style = Type.caption, color = t.muted, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(26.dp))
                        RollingDigits("%06d".format(code.code).let { it.substring(0, 3) + " " + it.substring(3) }, Type.digits.copy(fontSize = Type.digits.fontSize * 1.1f), t.text)
                        Spacer(Modifier.height(30.dp))
                        if (code.confirmed) Row(verticalAlignment = Alignment.CenterVertically) {
                            Radar(t.accent, Modifier.size(28.dp)); Spacer(Modifier.width(10.dp)); Text("Waiting for ${code.name}…", style = Type.caption, color = t.muted)
                        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

/**
 * A scanned (or opened) pairing link's PC being found over the internet: a laptop in glass with the radar sweeping
 * around it. Failing, why, and the ways to try again; the sheet may be hidden meanwhile (the answer comes as a banner).
 */
@Composable private fun Finding(error: String?, onScan: () -> Unit, onType: () -> Unit, onHide: () -> Unit) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (error == null) "Finding your PC" else "Not paired", style = Type.title, color = t.text)
        Spacer(Modifier.height(4.dp))
        Text(error ?: "Over the internet, end-to-end encrypted. Keep the code showing on your PC", style = Type.caption, color = if (error == null) t.muted else t.danger, textAlign = TextAlign.Center)
        val space by animateDpAsState(if (error == null) 250.dp else 150.dp, spring(dampingRatio = .8f, stiffness = 300f), label = "Space")
        Box(Modifier.fillMaxWidth().height(space), contentAlignment = Alignment.Center) {
            if (error == null) Radar(t.accent, Modifier.size(230.dp))
            Box(Modifier.size(66.dp).glass(CircleShape, GlassLevel.Control, if (error == null) t.accent else t.danger), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Laptop, null, tint = t.text, modifier = Modifier.size(30.dp))
            }
        }
        if (error != null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(onScan, prominent = true, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp)) {
                Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Scan again", style = Type.caption)
            }
            GlassButton(onType, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp)) {
                Icon(Icons.Rounded.Keyboard, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Type the code", style = Type.caption)
            }
        } else GlassButton(onHide, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp)) { Text("Hide", style = Type.caption) }
    }
}

/**
 * The island's pairing code, typed: eight letters and digits in two groups, each landing in its own glass cell. The
 * last one pairs at once; the island and this phone then show the same six digits, as on one Wi-Fi.
 */
@Composable private fun CodeEntry(connecting: Boolean, error: String?, onScan: () -> Unit, onBack: () -> Unit, onCode: (String) -> Unit) {
    val t = LocalTokens.current; val focus = remember { FocusRequester() }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { delay(300); runCatching { focus.requestFocus() } }
    LaunchedEffect(error) { if (error != null) text = "" }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Pair with a code", style = Type.title, color = t.text)
        Spacer(Modifier.height(4.dp))
        Text("On your PC, open the island’s Shelf › Nearby › Pair with a code, then type the code it shows. Any network works.", style = Type.caption, color = t.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        BasicTextField(text, { typed ->
            val clean = typed.uppercase().filter { it.isLetterOrDigit() }.take(8)
            text = clean
            if (clean.length == 8 && !connecting) onCode(clean)
        }, singleLine = true, enabled = !connecting, cursorBrush = SolidColor(Color.Transparent), textStyle = Type.body.copy(color = Color.Transparent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (text.length == 8) onCode(text) }),
            modifier = Modifier.focusRequester(focus),
            decorationBox = { _ ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for (i in 0 until 8) {
                        if (i == 4) Text("–", style = Type.headline, color = t.faint, modifier = Modifier.padding(horizontal = 6.dp))
                        val ch = text.getOrNull(i); val here = i == text.length && !connecting
                        val lift by animateFloatAsState(if (ch != null) 1f else 0f, spring(dampingRatio = .55f, stiffness = 500f), label = "Cell")
                        Box(Modifier.padding(horizontal = 2.5.dp).size(34.dp, 46.dp).glass(GlassShapes.inner, GlassLevel.Control, if (here) t.accent else Color.Unspecified), contentAlignment = Alignment.Center) {
                            if (ch != null) Text(ch.toString(), style = Type.headline.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = t.text, modifier = Modifier.graphicsLayer { val s = .7f + .3f * lift; scaleX = s; scaleY = s; alpha = lift })
                        }
                    }
                }
            })
        Spacer(Modifier.height(20.dp))
        when {
            connecting -> Row(verticalAlignment = Alignment.CenterVertically) { Radar(t.accent, Modifier.size(28.dp)); Spacer(Modifier.width(10.dp)); Text("Finding your PC over the internet…", style = Type.caption, color = t.muted) }
            error != null -> Text(error, style = Type.caption, color = t.danger, textAlign = TextAlign.Center)
            else -> Text("Encrypted end to end. The relay passes sealed bytes only.", style = Type.caption, color = t.faint, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(onScan, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp)) {
                Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Scan instead", style = Type.caption)
            }
            GlassButton(onBack, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp)) { Text("Back to this Wi-Fi", style = Type.caption) }
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

/**
 * Find my phone: the screen pulses in time with the alarm's vibration (two beats, then a rest), with rings spreading on
 * every beat; its light warms from amber to a glowing red the longer it rings and the more the phone is moved, the
 * "getting warmer" of whoever is looking for it. Until Found it.
 */
@Composable fun RingOverlay(from: String?) {
    val context = LocalContext.current
    AnimatedVisibility(from != null, enter = fadeIn() + scaleIn(initialScale = 1.08f), exit = fadeOut() + scaleOut(targetScale = .96f)) {
        val reduced = LocalReduced.current
        var now by remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
        LaunchedEffect(Unit) { if (!reduced) while (true) { withFrameMillis { now = android.os.SystemClock.uptimeMillis() } } }
        val moved = rememberMotion()
        val since = (now - Ringer.startedAt).coerceAtLeast(0)
        val warmth = ((since / 30_000f) + moved.value).coerceIn(0f, 1f)
        val glow = androidx.compose.ui.graphics.lerp(Color(0xFFFFC04D), Color(0xFFFF4D2E), warmth)
        val beat = if (reduced) 0f else Ringer.beat(since)
        // Where the bell is: the glow and the rings spread from it.
        var bell by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Unspecified) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.lerp(Color(0xFF120A04), Color(0xFF1C0604), warmth), Color(0xFF040306), Color(0xFF1A0E05))))
            .clickable(remember { MutableInteractionSource() }, null) {}, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = if (bell.isSpecified) bell else center; val r = 230.dp.toPx()
                // The glow, swelling with each beat.
                val g = 290.dp.toPx() * (1f + .08f * beat)
                drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = .22f + .18f * beat + .1f * warmth), Color.Transparent), c, g), g, c)
                // A ring spreads from each beat of the vibration.
                for (age in Ringer.rings(since)) {
                    drawCircle(glow.copy(alpha = (1f - age) * .55f), r * (.26f + .74f * age), c, style = androidx.compose.ui.graphics.drawscope.Stroke((2.5f - 1.5f * age).dp.toPx()))
                }
                // The warm shimmer: a comet of light circling the bell, faster as it warms.
                val a = (since / (1400f - 700f * warmth)) * 360f
                val orbit = 104.dp.toPx(); val head = glow.copy(alpha = .55f + .4f * warmth)
                rotate(a % 360f, c) {
                    drawArc(Brush.sweepGradient(0f to Color.Transparent, .62f to Color.Transparent, 1f to head, center = c), 225f, 135f, false,
                        androidx.compose.ui.geometry.Offset(c.x - orbit, c.y - orbit), androidx.compose.ui.geometry.Size(orbit * 2, orbit * 2),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    val tip = androidx.compose.ui.geometry.Offset(c.x + orbit, c.y)
                    drawCircle(Brush.radialGradient(listOf(head, Color.Transparent), tip, 16.dp.toPx()), 16.dp.toPx(), tip)
                    drawCircle(Color.White.copy(alpha = .9f), 2.5.dp.toPx(), tip)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(30.dp)) {
                val swing by rememberInfiniteTransition(label = "Swing").animateFloat(-14f, 14f, infiniteRepeatable(tween(180, easing = LinearEasing), RepeatMode.Reverse), label = "W")
                Box(Modifier.size(116.dp).onGloballyPositioned { bell = it.boundsInRoot().center }.graphicsLayer { val s = 1f + .14f * beat; scaleX = s; scaleY = s }.clip(CircleShape)
                    .background(Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(Color(0xFFFFC04D), Color(0xFFFFD27A), warmth), androidx.compose.ui.graphics.lerp(Color(0xFFFF7A2F), Color(0xFFFF3B2F), warmth)))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.NotificationsActive, null, tint = Color.White, modifier = Modifier.size(56.dp).graphicsLayer { rotationZ = if (beat > .05f && !reduced) swing else 0f })
                }
                Spacer(Modifier.height(34.dp))
                Text("Here I am", style = Type.hero, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text("${from ?: "Your PC"} is looking for this phone", style = Type.body, color = Color.White.copy(alpha = .75f), textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(when { moved.value > .45f -> "Hot! You found it"; moved.value > .12f -> "Getting warmer…"; else -> " " }, style = Type.caption, color = glow)
                Spacer(Modifier.height(38.dp))
                Row(Modifier.clip(GlassShapes.capsule).background(Color.White).clickable(role = androidx.compose.ui.semantics.Role.Button) { Ringer.stop(context) }.padding(horizontal = 46.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Check, null, tint = Color(0xFF1A0E05)); Spacer(Modifier.width(8.dp)); Text("Found it", style = Type.bodyStrong, color = Color(0xFF1A0E05)) }
            }
        }
    }
}

/** How much the phone has been moved lately (0..1), from its accelerometer while this shows: picking it up warms the ring screen. */
@Composable private fun rememberMotion(): State<Float> {
    val context = LocalContext.current
    val energy = remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val manager = context.getSystemService(android.hardware.SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val m = kotlin.math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                // It rises with movement and settles back slowly.
                energy.floatValue = (energy.floatValue * .985f + (m / 60f).coerceAtMost(.08f)).coerceIn(0f, 1f)
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) = Unit
        }
        if (sensor != null) manager.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
        onDispose { manager?.unregisterListener(listener) }
    }
    return energy
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
