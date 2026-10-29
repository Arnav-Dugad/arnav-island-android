package io.github.arnavdugad.arnavisland

import android.content.Context
import android.net.Uri
import io.github.arnavdugad.arnavisland.link.IslandWire
import io.github.arnavdugad.arnavisland.link.PeerView
import io.github.arnavdugad.arnavisland.link.Proto
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * 1.9: your PC by voice. One place that runs an action on the chosen PC for whatever asked: Gemini through App Functions
 * ([PcFunctions], Android 16+), App Actions and the app's shortcuts, and `arnavisland://do/...` links from routines,
 * Tasker or NFC tags ([VoiceActivity]). Each answer is a short sentence an assistant can say out loud ("Locked Studio").
 *
 * Actions (and their arguments): play_pause, play, pause, next, previous, volume (value 0..100), volume_up and
 * volume_down (by, default 10), mute, unmute, lock, sleep, restart, shutdown (these three need confirm), find, wake,
 * paste (text), open (url), screenshot, playing, status, command (text, confirm).
 */
object Assistant {
    /** What happened, said plainly; [needsConfirm]: nothing was done yet, [speech] is the question to ask first. */
    data class Result(val ok: Boolean, val speech: String, val needsConfirm: Boolean = false)

    const val PLAY_PAUSE = "play_pause"; const val PLAY = "play"; const val PAUSE = "pause"; const val NEXT = "next"; const val PREVIOUS = "previous"
    const val VOLUME = "volume"; const val VOLUME_UP = "volume_up"; const val VOLUME_DOWN = "volume_down"; const val MUTE = "mute"; const val UNMUTE = "unmute"
    const val LOCK = "lock"; const val SLEEP = "sleep"; const val RESTART = "restart"; const val SHUT_DOWN = "shutdown"
    const val FIND = "find"; const val WAKE = "wake"; const val PASTE = "paste"; const val OPEN = "open"; const val SCREENSHOT = "screenshot"
    const val PLAYING = "playing"; const val STATUS = "status"; const val COMMAND = "command"
    val ACTIONS = listOf(PLAY_PAUSE, PLAY, PAUSE, NEXT, PREVIOUS, VOLUME, VOLUME_UP, VOLUME_DOWN, MUTE, UNMUTE, LOCK, SLEEP, RESTART, SHUT_DOWN, FIND, WAKE, PASTE, OPEN, SCREENSHOT, PLAYING, STATUS, COMMAND)
    /** Actions that ask first (the PC goes away, or it may lose what's unsaved). */
    val ASKS = setOf(SLEEP, RESTART, SHUT_DOWN)
    /** Revision 10: a screenshot on the PC (CMD 28). */
    private const val CMD_SCREENSHOT = 28

    /** Other names people and shortcuts use for the same action (and the static shortcuts' ids, "pc_lock"). */
    fun normalize(name: String?): String? {
        val n = name?.trim()?.lowercase()?.removePrefix("pc_")?.replace('-', '_')?.replace(' ', '_') ?: return null
        return when (n) {
            in ACTIONS -> n
            "toggle", "playpause", "resume" -> PLAY_PAUSE; "skip", "next_track" -> NEXT; "prev", "back", "previous_track" -> PREVIOUS
            "louder", "up" -> VOLUME_UP; "quieter", "softer", "down" -> VOLUME_DOWN; "silence" -> MUTE
            "lock_pc", "lock_screen" -> LOCK; "shut_down", "power_off", "turn_off" -> SHUT_DOWN; "reboot" -> RESTART
            "ring", "findpc", "find_my_pc" -> FIND; "wake_up", "wol", "turn_on" -> WAKE; "clipboard" -> PASTE; "url", "link" -> OPEN
            "now_playing", "whats_playing", "song" -> PLAYING; "stats", "how", "battery", "cpu", "health" -> STATUS; "cmd", "run", "do" -> COMMAND
            else -> null
        }
    }

    /**
     * A feature named in words (App Actions' "open lock in Arnav Island", a shortcut's id, or anything typed): the action
     * and its arguments it most likely means. Anything else goes to the PC's command bar, which understands far more.
     */
    fun guess(phrase: String): Pair<String, Map<String, String>> {
        val p = phrase.trim(); val l = p.lowercase()
        normalize(l)?.let { return it to emptyMap() }
        Regex("""(?:set\s+)?(?:the\s+)?volume\s+(?:to\s+)?(\d{1,3})\s*%?""").find(l)?.let { return VOLUME to mapOf("value" to it.groupValues[1]) }
        fun has(vararg w: String) = w.any { Regex("""\b$it\b""").containsMatchIn(l) }
        return when {
            has("unmute") -> UNMUTE to emptyMap()
            has("mute") -> MUTE to emptyMap()
            has("louder", "volume up", "turn it up") -> VOLUME_UP to emptyMap()
            has("quieter", "volume down", "turn it down") -> VOLUME_DOWN to emptyMap()
            has("next", "skip") -> NEXT to emptyMap()
            has("previous", "last song", "go back") -> PREVIOUS to emptyMap()
            has("what's playing", "whats playing", "what is playing", "now playing") -> PLAYING to emptyMap()
            has("play", "pause", "resume") && l.split(' ').size <= 3 -> PLAY_PAUSE to emptyMap()
            has("lock") -> LOCK to emptyMap()
            has("sleep") -> SLEEP to emptyMap()
            has("restart", "reboot") -> RESTART to emptyMap()
            has("shut down", "shutdown", "power off", "turn off") -> SHUT_DOWN to emptyMap()
            has("find", "ring", "where is") -> FIND to emptyMap()
            has("wake", "turn on") -> WAKE to emptyMap()
            has("paste", "clipboard") -> PASTE to emptyMap()
            has("screenshot", "screen shot") -> SCREENSHOT to emptyMap()
            has("how's", "hows", "how is", "status", "battery", "cpu") -> STATUS to emptyMap()
            Regex("""^(?:open\s+)?(https?://\S+|\S+\.(?:com|org|net|io|dev|app|in|co|uk)\S*)$""").matches(l) -> OPEN to mapOf("url" to p.removePrefix("open ").trim())
            else -> COMMAND to mapOf("text" to p)
        }
    }

    /** An `arnavisland://do/<action>?value=..&text=..&url=..&by=..&confirm=1` link: its action and arguments. */
    fun fromUri(uri: Uri?): Pair<String, Map<String, String>>? {
        if (uri == null || !uri.scheme.equals("arnavisland", true) || !uri.host.equals("do", true)) return null
        val name = uri.pathSegments.firstOrNull() ?: return null
        val args = runCatching { uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() } }.getOrDefault(emptyMap())
        val action = normalize(name) ?: return guess(name.replace('_', ' ')).let { (a, g) -> a to (g + args) }
        return action to args
    }

    /** Runs one action on the chosen PC (starting the link first, as a widget does) and says how it went. */
    suspend fun run(context: Context, action: String, args: Map<String, String> = emptyMap()): Result {
        val a = normalize(action) ?: return Result(false, "Arnav Island can't do “$action” yet")
        val confirmed = args["confirm"].let { it == "1" || it.equals("true", true) || it.equals("yes", true) }
        // Waking needs no PC on line, only the addresses this phone learnt; the rest wait (up to ~5 s) for it to answer.
        val pc = ready(context, needOnline = a != WAKE) ?: return Result(false, "Pair Arnav Island with your PC first")
        val name = pc.name
        if (a == WAKE) {
            if (pc.online) return Result(true, "$name is already awake")
            if (!Hub.canWake(pc.id)) return Result(false, "I can't wake $name yet. It learns how once $name has island 0.26 and is on")
            Hub.wake(pc.id); return Result(true, "Waking $name. If it allows Wake-on-LAN, it starts in a moment")
        }
        if (!pc.online) return Result(false, "$name is away" + if (Hub.canWake(pc.id)) ". Say “wake my PC” to start it" else "")
        if (!pc.remote) return Result(false, "Update Arnav Island on $name to control it from here")
        val island = Hub.islandReady(pc)
        fun older() = Result(false, "Update Arnav Island on $name for that")
        suspend fun send(cmd: Int, payload: ByteArray = ByteArray(0), done: String): Result {
            val r = Hub.command(cmd, payload, quiet = true)
            return when { r == null -> Result(false, "$name didn't answer"); r.status == Proto.NOT_ALLOWED -> Result(false, "$name said no. Turn on “My phone can control this PC” in its island's Settings")
                r.status == Proto.UNSUPPORTED -> older(); !r.ok -> Result(false, "$name couldn't do that"); else -> Result(true, done) }
        }
        suspend fun control(c: Int, v: Int, done: String) = if (!island) older() else if (Hub.setControlNow(c, v)) Result(true, done) else Result(false, "$name couldn't do that")
        return when (a) {
            PLAY_PAUSE -> send(Proto.CMD_MEDIA, byteArrayOf(1), "Done on $name").let { r -> if (!r.ok) r else { delay(350)
                Hub.refreshStatus()?.let { s -> Result(true, if (s.playing) "Playing on $name" else "Paused on $name") } ?: r } }
            PLAY -> send(Proto.CMD_MEDIA, byteArrayOf(4), "Playing on $name")
            PAUSE -> send(Proto.CMD_MEDIA, byteArrayOf(5), "Paused on $name")
            NEXT -> send(Proto.CMD_MEDIA, byteArrayOf(3), "Next track on $name")
            PREVIOUS -> send(Proto.CMD_MEDIA, byteArrayOf(2), "Previous track on $name")
            VOLUME -> { val v = args["value"]?.trim()?.removeSuffix("%")?.toIntOrNull()?.coerceIn(0, 100) ?: return Result(false, "Say a volume from 0 to 100")
                send(Proto.CMD_VOLUME, byteArrayOf(v.toByte()), "$name's volume is $v%") }
            VOLUME_UP, VOLUME_DOWN -> {
                val s = Hub.refreshStatus() ?: return Result(false, "$name didn't answer")
                val by = (args["by"]?.toIntOrNull() ?: 10).coerceIn(1, 100); val v = (s.volume + if (a == VOLUME_UP) by else -by).coerceIn(0, 100)
                send(Proto.CMD_VOLUME, byteArrayOf(v.toByte()), "$name's volume is $v%")
            }
            MUTE, UNMUTE -> { val on = a == MUTE; val done = if (on) "Muted $name" else "Unmuted $name"
                if (island) control(IslandWire.MUTE, if (on) 1 else 0, done)
                else { val s = Hub.refreshStatus() ?: return Result(false, "$name didn't answer"); if (s.muted == on) Result(true, done) else send(Proto.CMD_MUTE, byteArrayOf(2), done) } }
            LOCK -> send(Proto.CMD_LOCK, done = "Locked $name")
            SLEEP -> if (!confirmed) Result(false, "Put $name to sleep?", needsConfirm = true) else control(IslandWire.SLEEP, 1, "Putting $name to sleep")
            RESTART -> if (!confirmed) Result(false, "Restart $name? Anything unsaved there may be lost.", needsConfirm = true) else control(IslandWire.RESTART, 1, "Restarting $name")
            SHUT_DOWN -> if (!confirmed) Result(false, "Shut down $name? Anything unsaved there may be lost.", needsConfirm = true) else control(IslandWire.SHUT_DOWN, 1, "Shutting down $name")
            FIND -> if (pc.revision < 3) older() else if (Hub.ringPc()) Result(true, "Ringing $name") else Result(false, "$name didn't answer")
            PASTE -> { val text = args["text"].orEmpty(); if (text.isBlank()) Result(false, "Your clipboard is empty")
                else send(Proto.CMD_CLIP_SET, text.toByteArray(), "On $name's clipboard") }
            OPEN -> { val url = web(args["url"] ?: args["text"]) ?: return Result(false, "That doesn't look like a web address")
                send(Proto.CMD_OPEN, url.toByteArray(), "Opened ${Uri.parse(url).host ?: "it"} on $name") }
            SCREENSHOT -> if (pc.revision < 10) older() else send(CMD_SCREENSHOT, done = "Took a screenshot on $name")
            PLAYING -> { val s = Hub.refreshStatus() ?: return Result(false, "$name didn't answer")
                if (!s.available || s.title.isBlank()) Result(true, "Nothing's playing on $name")
                else Result(true, (if (s.playing) "Playing on $name: " else "Paused on $name: ") + s.title + (if (s.artist.isNotBlank()) " by ${s.artist}" else "") + (if (s.app.isNotBlank()) ", in ${s.app}" else "")) }
            STATUS -> status(pc)
            COMMAND -> command(pc, args["text"].orEmpty(), confirmed)
            else -> Result(false, "Arnav Island can't do that yet")
        }
    }

    /** Starts the link (the service, or the link itself when Android won't start a service from the background) and waits for the PC. */
    private suspend fun ready(context: Context, needOnline: Boolean): PeerView? {
        Hub.init(context); LinkService.start(context)
        withTimeoutOrNull(5000) {
            var tick = 0
            while (true) {
                if (Hub.link == null && tick++ == 5) runCatching { Hub.start() }
                val p = Hub.pc(); if (Hub.link != null && p != null && (p.online || !needOnline)) break
                delay(100)
            }
        }
        return Hub.pc()
    }

    /** The PC's numbers in a sentence: its processor, memory and battery (the island's full stats, else the remote's status). */
    private suspend fun status(pc: PeerView): Result {
        val name = pc.name
        if (Hub.islandReady(pc)) { Hub.refreshStats(); Hub.pcStats.value?.let { s ->
            val battery = when { s.battery < 0 -> ""; s.charging -> ", battery ${s.battery}% and charging"; else -> ", battery ${s.battery}%" +
                (if (s.batteryMinutes > 0) " (about ${hours(s.batteryMinutes)} left)" else "") }
            fun n(v: Double) = if (v.isNaN()) 0 else v.roundToInt()
            return Result(true, "$name: processor ${n(s.cpu)}%, memory ${n(s.ramPercent)}%$battery, ${n(s.diskFreeGiB)} GB free")
        } }
        val s = Hub.refreshStatus() ?: return Result(false, "$name didn't answer")
        val battery = if (!s.batteryPresent) "" else ", battery ${s.battery}%" + if (s.charging) " and charging" else ""
        return Result(true, "$name: processor ${s.cpu}%$battery")
    }
    private fun hours(minutes: Double): String { val m = minutes.roundToInt(); return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min" }

    /** Anything for the PC's command bar ("timer 10", "open spotify", "2+2"): its first result runs, asking first where the island would. */
    private suspend fun command(pc: PeerView, text: String, confirmed: Boolean): Result {
        val name = pc.name; val t = text.trim().take(160)
        if (t.isEmpty()) return Result(false, "What should $name do?")
        if (!Hub.islandReady(pc)) return Result(false, "Update Arnav Island on $name for its command bar")
        repeat(2) {
            val rows = Hub.queryCommands(t)?.rows ?: return Result(false, "$name didn't answer")
            val row = rows.firstOrNull() ?: return Result(false, "$name doesn't know “$t”")
            val o = Hub.runCommand(t, 0, row.title, confirmed) ?: return Result(false, "$name didn't answer")
            when (o.outcome) {
                0 -> return Result(true, row.answer.takeIf { it.isNotBlank() }?.let { "${row.title}: $it" } ?: o.message.ifBlank { row.title }.replace("the PC", name))
                1 -> return Result(false, o.message.ifBlank { "${row.title}?" }.replace("the PC", name), needsConfirm = true)
                2 -> return Result(false, o.message.ifBlank { "$name couldn't do that" })
                // 3: what the bar shows changed (a timer started elsewhere...): ask again once.
            }
        }
        return Result(false, "$name couldn't do that")
    }

    /** A web address as typed or said ("example.com"): with its scheme, or null when it isn't one. */
    private fun web(text: String?): String? {
        val t = text?.trim()?.removePrefix("<")?.removeSuffix(">") ?: return null
        if (t.isEmpty() || t.contains(' ')) return null
        val u = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) t else "https://$t"
        return u.takeIf { (u.startsWith("http://", true) || u.startsWith("https://", true)) && Uri.parse(it).host?.contains('.') == true }
    }
}
