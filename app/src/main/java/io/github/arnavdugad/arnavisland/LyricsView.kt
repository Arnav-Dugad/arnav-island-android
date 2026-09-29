package io.github.arnavdugad.arnavisland

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arnavdugad.arnavisland.link.Lyrics
import io.github.arnavdugad.arnavisland.link.LyricsLine
import kotlinx.coroutines.delay

/** The line sung at [time] (seconds): the last one that has started, or -1 before the first. */
fun lineAt(lines: List<LyricsLine>, time: Double): Int {
    var lo = 0; var hi = lines.size - 1; var found = -1
    while (lo <= hi) { val mid = (lo + hi) ushr 1; if (lines[mid].time <= time) { found = mid; lo = mid + 1 } else hi = mid - 1 }
    return found
}
/** Lyrics are synced when their lines have times (plain lyrics all start at zero). */
fun synced(lines: List<LyricsLine>) = lines.size > 1 && lines.any { it.time > 0.0 }

/**
 * The PC's lyrics for what plays there, word by word: the line being sung is bright and large, its words light up as
 * they are sung, the lines before fade. It follows the song unless you scroll; a line tapped plays from there.
 */
@Composable fun LyricsView(lyrics: Lyrics?, position: () -> Double, playing: Boolean, pcName: String, onSeek: (Double) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val lines = lyrics?.lines.orEmpty()
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            lyrics == null || lyrics.state == 1 -> Hint("Looking for the lyrics…", "")
            lyrics.state == 0 -> Hint("Lyrics are off on $pcName", "Turn them on in the island’s Settings › Music")
            lines.isEmpty() -> Hint("No lyrics for this song", "")
            !synced(lines) -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp)) {
                itemsIndexed(lines) { _, l -> Text(l.text, style = Type.bodyStrong.copy(fontSize = 18.sp, lineHeight = 25.sp), color = Color.White.copy(alpha = .86f), modifier = Modifier.padding(vertical = 4.dp)) }
            }
            else -> {
                var now by remember { mutableDoubleStateOf(position()) }
                LaunchedEffect(playing) { while (true) { now = position(); delay(if (playing) 90 else 500) } }
                val current = lineAt(lines, now)
                val list = rememberLazyListState()
                // It follows the song, unless a finger has just scrolled it.
                var touchedAt by remember { mutableLongStateOf(0L) }
                LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress) touchedAt = System.currentTimeMillis() }
                LaunchedEffect(current) {
                    if (System.currentTimeMillis() - touchedAt > 3500 && current >= 0) list.animateScrollToItem((current - 1).coerceAtLeast(0))
                }
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = 22.dp, bottom = 180.dp, start = 22.dp, end = 22.dp)) {
                    itemsIndexed(lines) { i, l ->
                        val focus by animateFloatAsState(if (i == current) 1f else 0f, spring(dampingRatio = .8f, stiffness = 260f), label = "Line")
                        val text = if (i == current) sung(l, now, t.accent) else AnnotatedString(l.text.ifBlank { "♪" })
                        Text(text, style = Type.headline.copy(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold),
                            color = Color.White.copy(alpha = if (i < current) .38f else .5f + .5f * focus),
                            modifier = Modifier.fillMaxWidth().graphicsLayer { val s = .94f + .06f * focus; scaleX = s; scaleY = s; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f) }
                                .clickable(role = Role.Button) { onSeek(l.time) }.padding(vertical = 7.dp))
                    }
                }
            }
        }
    }
}

/** The line being sung: words already sung are white, the one being sung fills in, the rest wait. */
private fun sung(l: LyricsLine, now: Double, accent: Color): AnnotatedString = buildAnnotatedString {
    val text = l.text.ifBlank { "♪" }
    if (l.words.isEmpty()) { withStyle(SpanStyle(color = Color.White)) { append(text) }; return@buildAnnotatedString }
    val starts = l.words.map { it.second.coerceIn(0, text.length) }
    if (starts.first() > 0) withStyle(SpanStyle(color = Color.White)) { append(text.substring(0, starts.first())) }
    l.words.forEachIndexed { k, (time, _) ->
        val from = starts[k]; val to = if (k + 1 < starts.size) starts[k + 1].coerceAtLeast(from) else text.length
        val next = if (k + 1 < l.words.size) l.words[k + 1].first else time + .6
        val f = ((now - time) / (next - time).coerceAtLeast(.08)).toFloat().coerceIn(0f, 1f)
        val color = if (now < time) Color.White.copy(alpha = .42f) else lerp(lerp(Color.White, accent, .35f), Color.White, f)
        withStyle(SpanStyle(color = color)) { append(text.substring(from, to)) }
    }
}

@Composable private fun Hint(title: String, detail: String) {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = Type.headline, color = Color.White, textAlign = TextAlign.Center)
        if (detail.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(detail, style = Type.caption, color = Color.White.copy(alpha = .7f), textAlign = TextAlign.Center) }
    }
}
