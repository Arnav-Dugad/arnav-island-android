package io.github.arnavdugad.arnavisland

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
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
    val t = LocalTokens.current
    val peers by Hub.peers.collectAsState(); val pc = Hub.pc(peers)?.name ?: "your PC"
    val functions = Build.VERSION.SDK_INT >= 36
    data class Say(val icon: ImageVector, val phrase: String, val detail: String, val tint: androidx.compose.ui.graphics.Color)
    val media = listOf(
        Say(Icons.Rounded.PlayArrow, "“Pause the music on my PC”", "Play, pause, next and previous", t.pink),
        Say(Icons.AutoMirrored.Rounded.VolumeUp, "“Set my PC's volume to 30”", "Or louder, quieter, mute and unmute", t.blue),
        Say(Icons.Rounded.MusicNote, "“What's playing on my PC?”", "The song, the artist and the app", t.purple),
    )
    val power = listOf(
        Say(Icons.Rounded.Lock, "“Lock my PC”", "At once", t.gray),
        Say(Icons.Rounded.Bedtime, "“Put my PC to sleep”", "Gemini asks first; restart and shut down too", t.indigo),
        Say(Icons.Rounded.PowerSettingsNew, "“Wake up my PC”", "Wake-on-LAN, on the PC's Wi-Fi", t.good),
        Say(Icons.Rounded.NotificationsActive, "“Find my PC”", "Its island chimes and lights up", t.warn),
        Say(Icons.Rounded.Speed, "“How's my PC doing?”", "Processor, memory, battery and disk", t.teal),
    )
    val more = listOf(
        Say(Icons.Rounded.ContentPaste, "“Paste my clipboard on my PC”", "Or “send ‘…’ to my PC's clipboard”", t.accent),
        Say(Icons.Rounded.Language, "“Open youtube.com on my PC”", "In the PC's browser", t.blue),
        Say(Icons.Rounded.Screenshot, "“Take a screenshot of my PC”", "Kept on the PC (needs a recent island there)", t.purple),
        Say(Icons.Rounded.AutoAwesome, "“Tell my PC to set a timer for 10 minutes”", "Anything its command bar understands: “open spotify”, “dark mode on”, “focus 25”", t.yellow),
    )
    GlassSheet(visible, onDismiss, Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .9f)) {
        SheetHeader("Use with Gemini", "Say “Hey Google”, or hold the power button, then ask", onDismiss)
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            @Composable fun group(title: String, rows: List<Say>, footer: String? = null) = Section(title, footer = footer) {
                rows.forEachIndexed { i, s -> if (i > 0) Hairline(); GlassRow(s.icon, s.phrase, s.detail, tint = s.tint) }
            }
            group("Music and sound", media)
            group("Your PC", power, footer = "“My PC” is $pc, the PC chosen in Devices.")
            group("Anything else", more, footer = "Say “on my PC” so Gemini knows to ask Arnav Island.")
            Section("How Gemini reaches your PC", footer = "Your PC must allow it: turn on “My phone can control this PC” in its island's Settings.") {
                GlassRow(Icons.Rounded.AutoAwesome, "App functions", if (functions) "Gemini calls Arnav Island's actions directly, as Google turns them on for your phone" else "Needs Android 16 or later", tint = t.accent,
                    value = if (functions) null else "Android 16")
                Hairline()
                GlassRow(Icons.Rounded.TouchApp, "Shortcuts", "Lock PC, Play or pause, Wake PC and How's my PC, for your launcher and for Google's App Actions", tint = t.indigo)
                Hairline()
                GlassRow(Icons.Rounded.Link, "Routines and automations", "Links such as arnavisland://do/lock or arnavisland://do/command?text=timer 10 work from Modes and Routines, Tasker and NFC tags", tint = t.teal)
            }
            Spacer(Modifier.height(8.dp))
            Text(" ", style = Type.micro)
        }
    }
}
