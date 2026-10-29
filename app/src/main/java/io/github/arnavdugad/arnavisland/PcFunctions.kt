package io.github.arnavdugad.arnavisland

import android.content.Intent
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import androidx.appfunctions.AppFunctionStringValueConstraint

/**
 * 1.9: what Arnav Island can do on your PC, as App Functions: Android 16 indexes these, and agents such as Gemini call
 * them by name with the parameters described here (each function's KDoc is what the agent reads). Every function acts
 * on the user's own Windows PC, the one chosen in the app, through [Assistant]. The compiler writes the service
 * itself ([PcAppFunctionService]) and the schema in the APK's assets.
 *
 * Try one without an agent: `adb shell cmd app_function execute-app-function --package io.github.arnavdugad.arnavisland
 * --function 'io.github.arnavdugad.arnavisland.PcFunctions#lockPc' --parameters '{}'`.
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(serviceName = "PcAppFunctionService", appFunctionXmlFileName = "pc_app_functions")
abstract class PcFunctions : AppFunctionService() {
    private suspend fun go(action: String, vararg args: Pair<String, String?>): PcReply =
        Assistant.run(this, action, args.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()).let { PcReply(it.ok, it.speech, it.needsConfirm) }

    /**
     * Plays or pauses the music or video playing on the user's Windows PC (toggles it).
     *
     * @return What happened, to say to the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun playPauseOnPc(): PcReply = go(Assistant.PLAY_PAUSE)

    /**
     * Skips to the next track of what plays on the user's Windows PC.
     *
     * @return What happened, to say to the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun nextTrackOnPc(): PcReply = go(Assistant.NEXT)

    /**
     * Goes back to the previous track of what plays on the user's Windows PC.
     *
     * @return What happened, to say to the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun previousTrackOnPc(): PcReply = go(Assistant.PREVIOUS)

    /**
     * Sets the user's Windows PC's sound volume to a percentage.
     *
     * @param percent The volume from 0 (silent) to 100 (loudest).
     * @return What happened, to say to the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun setPcVolume(percent: Int): PcReply {
        if (percent !in 0..100) throw AppFunctionInvalidArgumentException("percent must be 0 to 100")
        return go(Assistant.VOLUME, "value" to percent.toString())
    }

    /**
     * Turns the user's Windows PC's volume up or down by a step, when no exact level was asked for ("louder", "quieter").
     *
     * @param direction "UP" to make it louder, "DOWN" to make it quieter.
     * @param step How many percentage points to change it by; 10 when not given.
     * @return What happened, with the new volume, to say to the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun changePcVolume(@AppFunctionStringValueConstraint(enumValues = ["UP", "DOWN"]) direction: String, step: Int? = null): PcReply = when (direction.uppercase()) {
        "UP" -> go(Assistant.VOLUME_UP, "by" to step?.toString()); "DOWN" -> go(Assistant.VOLUME_DOWN, "by" to step?.toString())
        else -> throw AppFunctionInvalidArgumentException("direction must be UP or DOWN")
    }

    /**
     * Mutes or unmutes the user's Windows PC's sound.
     *
     * @param muted True to mute the PC, false to unmute it.
     * @return What happened, to say to the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun mutePc(muted: Boolean): PcReply = go(if (muted) Assistant.MUTE else Assistant.UNMUTE)

    /**
     * Locks the user's Windows PC (its lock screen shows; nothing is closed).
     *
     * @return What happened, to say to the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun lockPc(): PcReply = go(Assistant.LOCK)

    /**
     * Puts the user's Windows PC to sleep. Ask the user to confirm before calling with confirmed = true; called with
     * false, nothing happens and the reply's message is the question to ask.
     *
     * @param confirmed True only after the user said yes to putting the PC to sleep.
     * @return What happened, or (needsConfirmation) the question to ask the user first.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun sleepPc(confirmed: Boolean = false): PcReply = go(Assistant.SLEEP, "confirm" to confirmed.toString())

    /**
     * Restarts the user's Windows PC. Anything unsaved there may be lost, so always ask the user to confirm first, and
     * call with confirmed = true only after they said yes. Called with false, nothing happens.
     *
     * @param confirmed True only after the user explicitly agreed to restart the PC.
     * @return What happened, or (needsConfirmation) the question to ask the user first.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun restartPc(confirmed: Boolean = false): PcReply = go(Assistant.RESTART, "confirm" to confirmed.toString())

    /**
     * Shuts down the user's Windows PC. Anything unsaved there may be lost, so always ask the user to confirm first,
     * and call with confirmed = true only after they said yes. Called with false, nothing happens.
     *
     * @param confirmed True only after the user explicitly agreed to shut down the PC.
     * @return What happened, or (needsConfirmation) the question to ask the user first.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun shutDownPc(confirmed: Boolean = false): PcReply = go(Assistant.SHUT_DOWN, "confirm" to confirmed.toString())

    /**
     * Finds the user's Windows PC: its Arnav Island chimes and lights up so they can tell where it is ("find my PC",
     * "ring my laptop").
     *
     * @return What happened, to say to the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun findPc(): PcReply = go(Assistant.FIND)

    /**
     * Wakes the user's Windows PC from sleep or turns it on with Wake-on-LAN, when the phone is on the PC's network.
     *
     * @return What happened, to say to the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun wakePc(): PcReply = go(Assistant.WAKE)

    /**
     * Puts text on the user's Windows PC's clipboard, ready to paste there. With no text, the phone's own clipboard
     * goes (Arnav Island opens for a moment to read it, as Android only lets the app on screen read the clipboard).
     *
     * @param text The text to put on the PC's clipboard; leave it out to send what the phone has copied.
     * @return What happened, to say to the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun pasteOnPc(text: String? = null): PcReply {
        if (!text.isNullOrBlank()) return go(Assistant.PASTE, "text" to text)
        val started = runCatching { startActivity(VoiceActivity.intent(this, Assistant.PASTE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        return PcReply(started, if (started) "Opening Arnav Island for a moment to send your clipboard to your PC" else "Open Arnav Island and tap Paste on PC")
    }

    /**
     * Opens a web page in the browser of the user's Windows PC.
     *
     * @param url The page's address, such as "https://example.com" or "example.com".
     * @return What happened, to say to the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun openUrlOnPc(url: String): PcReply {
        if (url.isBlank()) throw AppFunctionInvalidArgumentException("url is empty")
        return go(Assistant.OPEN, "url" to url)
    }

    /**
     * Takes a screenshot of the user's Windows PC's screen, kept on the PC (needs a recent Arnav Island there).
     *
     * @return What happened, to say to the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun screenshotPc(): PcReply = go(Assistant.SCREENSHOT)

    /**
     * Tells what music or video is playing on the user's Windows PC: its title, artist and app, and whether it's paused.
     *
     * @return The answer, to say to the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun whatsPlayingOnPc(): PcReply = go(Assistant.PLAYING)

    /**
     * Tells how the user's Windows PC is doing: its processor and memory use, battery and free disk space ("how's my PC").
     *
     * @return The answer, to say to the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun pcStatus(): PcReply = go(Assistant.STATUS)

    /**
     * Runs anything else on the user's Windows PC through its Arnav Island command bar, which understands short commands
     * in plain words: "open spotify", "timer 10", "volume 30", "dark mode on", "focus 25", "empty recycle bin", a sum
     * like "15% of 240", an app, file or setting to open. Use it for any PC request no other function covers. When the
     * reply needs confirmation, ask the user its message and, if they agree, call again with confirmed = true.
     *
     * @param text The command, as the user said it, without words like "on my PC".
     * @param confirmed True only after the user agreed to the question a previous call asked.
     * @return What happened (or the answer), or (needsConfirmation) the question to ask the user first.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun runPcCommand(text: String, confirmed: Boolean = false): PcReply {
        if (text.isBlank()) throw AppFunctionInvalidArgumentException("text is empty")
        return go(Assistant.COMMAND, "text" to text, "confirm" to confirmed.toString())
    }
}

/** 1.9: what an action on the PC did, for the agent to say. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class PcReply(
    /** Whether the PC did it (false when it is away, said no, or confirmation is needed). */
    val success: Boolean,
    /** A short sentence to say to the user, such as "Locked Studio" or "Studio is away"; the question to ask when confirmation is needed. */
    val message: String,
    /** True when nothing was done yet: ask the user the message, and call again with confirmed = true if they agree. */
    val needsConfirmation: Boolean = false,
)
