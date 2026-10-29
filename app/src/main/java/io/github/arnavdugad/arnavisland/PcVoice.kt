package io.github.arnavdugad.arnavisland

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.sin

/**
 * 1.11: PC Voice. Gemini only calls apps Google has approved, so it can't run Arnav Island's actions itself; what it
 * can do is open an app by name. "Hey Google, open PC Voice" (or this launcher icon, its Quick Settings tile, or the
 * app's shortcut) opens this: it listens at once (Android's own speech recognition, on the phone where it can), shows the
 * words as they come, runs them on your PC through [Assistant] and says how it went. Questions (sleep, restart) get a
 * Yes and a Cancel.
 */
class PcVoiceActivity : ComponentActivity() {
    private enum class Stage { Listening, Working, Done, Asking, Failed }
    private var stage by mutableStateOf(Stage.Listening)
    private var heard by mutableStateOf("")
    private var answer by mutableStateOf("")
    private var level by mutableFloatStateOf(0f)
    private var pending: Pair<String, Map<String, String>>? = null
    private var recognizer: SpeechRecognizer? = null
    private var speaker: TextToSpeech? = null
    private val micAllowed = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) listen() else fail("Allow the microphone to talk to your PC") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this); LinkService.start(this)
        speaker = TextToSpeech(applicationContext) { status -> if (status == TextToSpeech.SUCCESS) speaker?.language = Locale.getDefault() }
        setContent {
            val dark = when (Hub.prefs.getInt("appearance", 0)) { 1 -> true; 2 -> false; else -> isSystemInDarkTheme() }
            CompositionLocalProvider(LocalTokens provides tokens(dark)) { Card() }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen() else micAllowed.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Some recognisers go quiet without a word or an error when nothing is said: after a while, it stops itself.
    private val quiet = Runnable { if (stage == Stage.Listening) { runCatching { recognizer?.cancel() }; fail(if (heard.isEmpty()) "I didn't catch that" else "I lost you there") } }
    private fun watch() { window.decorView.removeCallbacks(quiet); window.decorView.postDelayed(quiet, 9000) }

    private fun listen() {
        stage = Stage.Listening; heard = ""; answer = ""; watch()
        recognizer?.destroy()
        // On the phone where Android can (Android 12+, its language downloaded), else whatever recogniser it has.
        val r = runCatching { if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else null }.getOrNull()
            ?: if (SpeechRecognizer.isRecognitionAvailable(this)) SpeechRecognizer.createSpeechRecognizer(this) else null
        if (r == null) { fail("This phone has no speech recognition"); return }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { level = 0f }
            override fun onError(error: Int) {
                if (stage != Stage.Listening) return
                fail(when (error) { SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that"; SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone to talk to your PC"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a connection on this phone"; else -> "I couldn't listen just now" })
            }
            override fun onPartialResults(partial: Bundle?) { partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { heard = it; watch() } }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (text.isEmpty()) fail("I didn't catch that") else { heard = text; run(Assistant.guess(clean(text))) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching {
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true).putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag()))
        }.onFailure { fail("I couldn't listen just now") }
    }

    /** "On my PC" and "tell my PC to" add nothing: the PC is who it's for. */
    private fun clean(text: String) = text.replace(Regex("""^(?:hey\s+)?(?:(?:tell|ask)\s+(?:my\s+|the\s+)?(?:pc|computer|laptop)\s+(?:to\s+)?)""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+(?:on|in)\s+(?:my\s+|the\s+)?(?:pc|computer|laptop)\b""", RegexOption.IGNORE_CASE), "").trim()

    private fun run(what: Pair<String, Map<String, String>>, confirmed: Boolean = false) {
        stage = Stage.Working
        val (action, args) = what; val app = applicationContext
        Hub.scope.launch {
            val r = Assistant.run(app, action, if (confirmed) args + ("confirm" to "1") else args)
            withContext(Dispatchers.Main) {
                answer = r.speech
                if (r.needsConfirm) { pending = what; stage = Stage.Asking } else { stage = if (r.ok) Stage.Done else Stage.Failed; say(r.speech); closeSoon() }
            }
        }
    }

    private fun fail(why: String) { answer = why; stage = Stage.Failed; say(why) }
    private fun say(text: String) { runCatching { speaker?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer") } }
    private fun closeSoon() { window.decorView.postDelayed({ if (!isFinishing && stage != Stage.Asking && stage != Stage.Listening) finish() }, 3200) }

    override fun onDestroy() { window.decorView.removeCallbacks(quiet); recognizer?.destroy(); recognizer = null; speaker?.shutdown(); speaker = null; super.onDestroy() }

    @Composable private fun Card() {
        val t = LocalTokens.current
        // A dimmed screen; a card at the bottom, as Android's own assistant shows itself. Tapping outside lets it go.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (t.dark) .45f else .25f)).clickable(remember { MutableInteractionSource() }, null) { finish() }, contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.navigationBarsPadding().padding(12.dp).widthIn(max = 520.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp))
                .background(if (t.dark) Color(0xFF1C1C1E) else Color.White).clickable(remember { MutableInteractionSource() }, null) { }
                .padding(horizontal = 22.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Hub.pc()?.name ?: "Your PC", style = Type.caption, color = t.muted)
                Spacer(Modifier.height(10.dp))
                Text(heard.ifEmpty { if (stage == Stage.Listening) "Listening…" else "" }, style = Type.title2, color = if (heard.isEmpty()) t.muted else t.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                AnimatedContent(stage, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "Stage") { s ->
                    when (s) {
                        Stage.Listening -> Bars(level, t.accent)
                        Stage.Working -> Bars(.35f, t.muted, still = false)
                        Stage.Asking -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(answer, style = Type.body, color = t.text, textAlign = TextAlign.Center); Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                GlassButton({ finish() }, Modifier.weight(1f)) { Text("Cancel", style = Type.bodyStrong) }
                                GlassButton({ pending?.let { run(it, confirmed = true) } }, Modifier.weight(1f), prominent = true) { Text("Yes", style = Type.bodyStrong) }
                            }
                        }
                        Stage.Done, Stage.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(answer, style = Type.body, color = if (s == Stage.Done) t.text else t.muted, textAlign = TextAlign.Center)
                            if (s == Stage.Failed) { Spacer(Modifier.height(14.dp))
                                Box(Modifier.size(56.dp).clip(CircleShape).background(t.accent).clickable { listen() }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Mic, "Try again", tint = t.onAccent, modifier = Modifier.size(26.dp)) } }
                        }
                    }
                }
            }
        }
    }

    /** Five bars that follow the voice (or, while the PC works, breathe slowly). */
    @Composable private fun Bars(level: Float, color: Color, still: Boolean = true) {
        val wave by rememberInfiniteTransition(label = "Voice").animateFloat(0f, 6.283f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "Wave")
        val shown by animateFloatAsState(level, spring(dampingRatio = .7f, stiffness = 500f), label = "Level")
        Canvas(Modifier.size(120.dp, 44.dp)) {
            val w = 8.dp.toPx(); val gap = (size.width - w * 5) / 4
            for (i in 0 until 5) {
                val lift = if (still) shown * (.55f + .45f * sin(wave + i * 1.1f).let { (it + 1) / 2 }) else .25f + .2f * (sin(wave + i * .9f) + 1) / 2
                val h = (size.height * (.18f + .82f * lift)).coerceAtLeast(w)
                drawRoundRect(color, Offset(i * (w + gap), (size.height - h) / 2), Size(w, h), CornerRadius(w / 2, w / 2))
            }
        }
    }
}

/** 1.11: PC Voice in Quick Settings: one tap from anywhere, even over the lock screen once unlocked. */
class PcVoiceTile : TileService() {
    override fun onClick() {
        val open = Intent(this, PcVoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 31, open, android.app.PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated") startActivityAndCollapse(open)
    }
}
