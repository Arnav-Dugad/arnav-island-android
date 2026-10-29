package io.github.arnavdugad.arnavisland

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * 1.9: "Use with Gemini": what to say to Gemini (or put in a routine) to control your PC, and which way it reaches the
 * app on this phone. Opened from Settings.
 */
@Composable fun AssistantHelpSheet(visible: Boolean, onDismiss: () -> Unit) {
    val t = LocalTokens.current; val context = androidx.compose.ui.platform.LocalContext.current
    val peers by Hub.peers.collectAsState(); val pc = Hub.pc(peers)?.name ?: "your PC"
    val functions = Build.VERSION.SDK_INT >= 36
    data class Say(val icon: ImageVector, val phrase: String, val detail: String, val tint: androidx.compose.ui.graphics.Color)
    val media = listOf(
        Say(Icons.Rounded.PlayArrow, "“Pause the music on my PC”", "", t.pink),
        Say(Icons.AutoMirrored.Rounded.VolumeUp, "“Set my PC's volume to 30”", "", t.blue),
        Say(Icons.Rounded.MusicNote, "“What's playing on my PC?”", "", t.purple),
    )
    val power = listOf(
        Say(Icons.Rounded.Lock, "“Lock my PC”", "", t.gray),
        Say(Icons.Rounded.Bedtime, "“Put my PC to sleep”", "Restart and shut down too", t.indigo),
        Say(Icons.Rounded.PowerSettingsNew, "“Wake up my PC”", "", t.good),
        Say(Icons.Rounded.NotificationsActive, "“Find my PC”", "", t.warn),
        Say(Icons.Rounded.Speed, "“How's my PC doing?”", "", t.teal),
    )
    val more = listOf(
        Say(Icons.Rounded.ContentPaste, "“Paste my clipboard on my PC”", "", t.accent),
        Say(Icons.Rounded.Language, "“Open youtube.com on my PC”", "", t.blue),
        Say(Icons.Rounded.Screenshot, "“Take a screenshot of my PC”", "", t.purple),
        Say(Icons.Rounded.Apps, "“Open Spotify”", "Any app on your PC", t.good),
        Say(Icons.Rounded.Memory, "“What's using my PC's memory?”", "", t.teal),
        Say(Icons.AutoMirrored.Rounded.VolumeOff, "“Mute Discord”", "One app's sound", t.pink),
        Say(Icons.Rounded.AutoAwesome, "“Set a timer for 10 minutes”", "Anything its command bar understands", t.yellow),
    )
    GlassSheet(visible, onDismiss, Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .9f)) {
        SheetHeader("Use with Gemini", "“Hey Google, open PC Voice”, then ask", onDismiss)
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            @Composable fun group(title: String, rows: List<Say>, footer: String? = null) = Section(title, footer = footer) {
                rows.forEachIndexed { i, s -> if (i > 0) Hairline(); GlassRow(s.icon, s.phrase, s.detail, tint = s.tint) }
            }
            group("Music and sound", media)
            group("Your PC", power, footer = "“My PC” is $pc.")
            group("Anything else", more)
            // Gemini runs only the apps Google has approved; it can open any app, so PC Voice is how it reaches your PC.
            Section("How to ask", footer = "Turn on “My phone can control this PC” in the island's Settings.") {
                GlassRow(Icons.Rounded.Mic, "PC Voice", "“Hey Google, open PC Voice”, its icon, or its Quick Settings tile", tint = t.accent, chevron = true,
                    onClick = { runCatching { context.startActivity(android.content.Intent(context, PcVoiceActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } })
                Hairline()
                GlassRow(Icons.Rounded.AutoAwesome, "Gemini, directly", tint = t.purple, value = if (functions) "When Google allows it" else "Android 16")
                Hairline()
                GlassRow(Icons.Rounded.TouchApp, "Shortcuts", tint = t.indigo)
                Hairline()
                GlassRow(Icons.Rounded.Link, "Routines and automations", "Links like arnavisland://do/lock", tint = t.teal)
            }
            Spacer(Modifier.height(8.dp))
            Text(" ", style = Type.micro)
        }
    }
}
