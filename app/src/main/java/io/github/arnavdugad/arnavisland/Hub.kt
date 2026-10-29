package io.github.arnavdugad.arnavisland

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import io.github.arnavdugad.arnavisland.link.Handoff
import io.github.arnavdugad.arnavisland.link.Link
import io.github.arnavdugad.arnavisland.link.LinkEvent
import io.github.arnavdugad.arnavisland.link.Lyrics
import io.github.arnavdugad.arnavisland.link.PcStatus
import io.github.arnavdugad.arnavisland.link.Reader
import io.github.arnavdugad.arnavisland.link.PeerView
import io.github.arnavdugad.arnavisland.link.Proto
import io.github.arnavdugad.arnavisland.link.RemoteReply
import io.github.arnavdugad.arnavisland.link.Source
import io.github.arnavdugad.arnavisland.link.AudioOutput
import io.github.arnavdugad.arnavisland.link.CommandOutcome
import io.github.arnavdugad.arnavisland.link.CommandResults
import io.github.arnavdugad.arnavisland.link.IslandSettings
import io.github.arnavdugad.arnavisland.link.PcControls
import io.github.arnavdugad.arnavisland.link.PcStats
import io.github.arnavdugad.arnavisland.link.PcBattery
import io.github.arnavdugad.arnavisland.link.FocusState
import io.github.arnavdugad.arnavisland.link.IslandWire
import io.github.arnavdugad.arnavisland.link.Bytes
import io.github.arnavdugad.arnavisland.link.safeName
import io.github.arnavdugad.arnavisland.link.Playback
import io.github.arnavdugad.arnavisland.link.MixerApp
import io.github.arnavdugad.arnavisland.link.Levels
import io.github.arnavdugad.arnavisland.link.Adapter
import io.github.arnavdugad.arnavisland.link.hex
import io.github.arnavdugad.arnavisland.link.unhex
import io.github.arnavdugad.arnavisland.link.PcProcess
import io.github.arnavdugad.arnavisland.link.Sensors
import io.github.arnavdugad.arnavisland.link.FileList
import io.github.arnavdugad.arnavisland.link.PcClip
import io.github.arnavdugad.arnavisland.link.BatteryHealth
import io.github.arnavdugad.arnavisland.link.Storage
import io.github.arnavdugad.arnavisland.link.StartupApp
import io.github.arnavdugad.arnavisland.link.NetAdapter
import io.github.arnavdugad.arnavisland.link.Updates
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A transfer under way, either way. rate: bytes a second, smoothed. */
data class Transfer(val id: Int, val peer: String, val name: String, val title: String, val done: Long, val total: Long, val outgoing: Boolean,
                    val rate: Double = 0.0, val sampledAt: Long = 0, val sampledDone: Long = 0, val paused: Boolean = false)

/** 1.8: a send waiting its turn (one transfer to a PC at a time): what, how much, and who asked for it. */
class Queued(val id: Long, val peer: String, val name: String, val sources: List<Source>, val title: String, val size: Long, val toShelf: Boolean, val preview: ByteArray?, val ask: Int, val onStarted: (Int) -> Unit,
             /** 1.9: one token for every try, so a PC on island 0.27 carries on where a dropped connection left it; and how many tries so far. */
             val token: ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }, val attempts: Int = 0)

/** 1.9: a copy kept in the clipboard history (this phone's own, and the PC's): its text, when, and whether it's pinned. */
data class ClipItem(val text: String, val at: Long, val pinned: Boolean, val from: String)

/** 1.8: a song heard on a PC, for the Remote's history: its small cover (a JPEG), when it was first and last heard, how often, and whether it's loved. */
data class Song(val key: String, val title: String, val artist: String, val app: String, val pc: String, val cover: ByteArray?, val first: Long, val last: Long, val plays: Int, val loved: Boolean) {
    override fun equals(other: Any?) = other is Song && key == other.key && last == other.last && plays == other.plays && loved == other.loved
    override fun hashCode() = key.hashCode()
}

/** 1.11: an app of the PC's Start menu (island 0.28): what opens it there, its name and its icon. */
data class PcApp(val id: String, val name: String, val icon: androidx.compose.ui.graphics.ImageBitmap?)

/** 1.8: how a PC looks here: its own name (null: the one it gives), colour and icon. */
data class PcLook(val name: String?, val color: Int, val icon: Int)

/** Something that happened between this phone and a PC, for the Send tab's history. kind: 0 received, 1 sent, 2 taken from a Shelf. */
data class Moment(val kind: Int, val title: String, val from: String, val count: Int, val size: Long, val at: Long, val uris: List<String>)

/** A glass banner that drops from the app's island. */
data class Banner(val kind: Kind, val title: String, val detail: String = "") {
    enum class Kind { Received, Sent, Failed, Paired, Info, Music, Ring, Update, Clipboard, Photo, Internet }
}

/**
 * Everything the app knows, for every screen and the service: the link with your PCs (started by [LinkService]), what
 * it reports, and the actions the screens take. State lives in flows, so any thread may update it.
 */
object Hub {
    lateinit var app: Context; private set
    // What goes wrong in one task (a PC that vanished mid-answer, a widget or a notification Android refused) ends that task, never the app.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> android.util.Log.w("ArnavHub", "task failed", e) })
    @Volatile var link: Link? = null; private set
    val running = MutableStateFlow(false)
    val failure = MutableStateFlow<String?>(null)
    val peers = MutableStateFlow<List<PeerView>>(emptyList())
    val pairCode = MutableStateFlow<LinkEvent.PairCode?>(null)
    val pairResult = MutableStateFlow<LinkEvent.Paired?>(null)
    /** The code this phone is pairing with through the relay (typed, scanned or opened as a link), until its digits or an answer come. */
    val codePairing = MutableStateFlow<String?>(null)
    val offers = MutableStateFlow<List<LinkEvent.Offer>>(emptyList())
    val music = MutableStateFlow<LinkEvent.Music?>(null)
    val transfers = MutableStateFlow<Map<Int, Transfer>>(emptyMap())
    /** 1.7: how each send ended (its transfer, and whether it arrived), for the share sheet's rings. */
    val outcomes = kotlinx.coroutines.flow.MutableSharedFlow<Pair<Int, Boolean>>(extraBufferCapacity = 32)
    val moments = MutableStateFlow<List<Moment>>(emptyList())
    val banners = MutableSharedFlow<Banner>(extraBufferCapacity = 16)
    val ringing = MutableStateFlow<String?>(null)
    val status = MutableStateFlow<PcStatus?>(null)
    val statusError = MutableStateFlow<String?>(null)
    val selected = MutableStateFlow<String?>(null)
    /** Connected to the relay: paired devices on other networks are reachable. */
    val internet = MutableStateFlow(false)
    /** Something a screen should open now ("camera": a PC asked for a photo while the app was on screen). */
    val requests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** The song's lyrics from the PC, for the key "title<TAB>artist" of what plays there. */
    val lyrics = MutableStateFlow<Lyrics?>(null)
    /** Whether the app is on screen (notifications for what the screen already shows are skipped then). */
    @Volatile var visible = false

    val prefs: SharedPreferences get() = app.getSharedPreferences("island", Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        selected.value = prefs.getString("pc", null)
        moments.value = loadMoments()
        looks.value = loadLooks(); lastSeen.value = loadSeen(); machines.value = loadMachines(); clipHistory.value = loadClips()
        scope.launch { history.value = loadHistory() }
    }

    fun phoneName(): String = runCatching { Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

    @Volatile private var starting: Link? = null
    @Synchronized fun start(): Link? {
        link?.let { return it }
        // Over the internet too (through the relay, end-to-end encrypted) unless that is turned off.
        val l = Link(PhoneStore(app), phoneName(), DownloadsInbox(app), ::onEvent, Link.Options(relay = prefs.getBoolean("internet", true), resumeDir = File(app.filesDir, "resume")))
        l.onAction = { key, index, reply -> NoticeActions.perform(app, key, index, reply) }
        l.onQuery = { peer, command, payload -> if (peers.value.any { it.id == peer && it.paired }) answer(peer, command, payload) else null }
        l.onClipboard = { text, sensitive -> Clip.receive(app, text, sensitive).also { if (it && !sensitive) rememberClip(text, "PC"); if (it && visible) banners.tryEmit(Banner(Banner.Kind.Clipboard, "Copied from your PC", text.lineSequence().first().take(60))) } }
        starting = l
        if (!l.start()) { starting = null; failure.value = l.failure ?: "The link couldn't start"; return null }
        link = l; starting = null; running.value = true; failure.value = null
        peers.value = dressed(l.peers()); pickDefault()
        return l
    }
    @Synchronized fun stop() { link?.stop(); link = null; running.value = false; internet.value = false; peers.value = emptyList() }
    /** Starts the link again (after "Reach my PCs anywhere" changed). */
    @Synchronized fun restart() { if (link == null) return; stop(); start() }
    /** The phone moved to another network (Wi-Fi to mobile data, another Wi-Fi): reconnect at once. */
    fun networkChanged() { link?.networkChanged() }

    // ---- your PCs ----
    /** The PC the remote and sends go to: the chosen one while it is paired, else the first paired PC that is here. */
    fun pc(list: List<PeerView> = peers.value, chosen: String? = selected.value): PeerView? =
        list.firstOrNull { it.id == chosen && it.paired } ?: list.firstOrNull { it.paired && it.online && !it.phone } ?: list.firstOrNull { it.paired && !it.phone }
    private fun pickDefault() { val p = pc(); if (p != null && p.id != selected.value) choose(p.id) }
    fun choose(peer: String) { if (selected.value != peer) { clearIsland(); missing.value = emptySet() }; selected.value = peer; prefs.edit().putString("pc", peer).apply(); status.value = null; probe(peer) }

    private val lastOnline = HashSet<String>()
    /** Called on the link's own threads (discovery, the relay, each transfer): what goes wrong here is logged, not thrown at them. */
    private fun onEvent(e: LinkEvent) { try { handle(e) } catch (x: Exception) { android.util.Log.w("ArnavHub", "event ${e::class.simpleName} failed", x) } }
    private fun handle(e: LinkEvent) {
        when (e) {
            is LinkEvent.Peers -> {
                peers.value = dressed(e.peers); pickDefault(); internet.value = (link ?: starting)?.internet == true
                seen(e.peers)
                // A PC that just came online hears this phone's battery and details at once.
                val online = e.peers.filter { it.paired && it.online && it.remote }.map { it.id }.toSet()
                val arrived = synchronized(lastOnline) { val a = online - lastOnline; lastOnline.clear(); lastOnline.addAll(online); a }
                if (arrived.isNotEmpty()) { sendBattery(force = true); sendDetails(force = true); sendHotspot(arrived = true); arrived.forEach { learnMachine(it); probe(it); next(it) } }
            }
            is LinkEvent.PairCode -> { codePairing.value = null; pairCode.value = e; if (!visible) Notify.pairing(app, e) }
            is LinkEvent.Paired -> {
                codePairing.value = null; pairCode.value = null; pairResult.value = e; Notify.cancel(app, Notify.PAIR)
                if (e.ok) { if (pc()?.id == null || selected.value == null) choose(e.peer); banners.tryEmit(Banner(Banner.Kind.Paired, "Paired with ${e.name}")); sendBattery(force = true); sendDetails(force = true) }
                else banners.tryEmit(Banner(Banner.Kind.Failed, if (e.name.isEmpty()) "Not paired" else "Not paired with ${e.name}", e.detail))
            }
            is LinkEvent.Offer -> {
                if (prefs.getBoolean("autoAccept", false)) { link?.answer(e.transfer, true); return }
                offers.update { it + e }; if (!visible) Notify.offer(app, e)
            }
            is LinkEvent.Progress -> transfers.update { map ->
                val now = System.currentTimeMillis(); val old = map[e.transfer]
                var t = old?.copy(done = e.done, total = e.total, title = e.title) ?: Transfer(e.transfer, e.peer, e.name, e.title, e.done, e.total, e.outgoing, sampledAt = now, sampledDone = e.done, paused = link?.paused(e.transfer) == true)
                if (old != null && now - old.sampledAt >= 300 && e.done >= old.sampledDone) {
                    val speed = (e.done - old.sampledDone) * 1000.0 / (now - old.sampledAt)
                    t = t.copy(rate = if (old.rate > 0) old.rate * .7 + speed * .3 else speed, sampledAt = now, sampledDone = e.done)
                }
                map + (e.transfer to t)
            }
            is LinkEvent.Received -> {
                transfers.update { it - e.transfer }; offers.update { list -> list.filterNot { it.transfer == e.transfer } }
                remember(Moment(if (e.taken) 2 else 0, e.title, e.name, e.count, e.size, System.currentTimeMillis(), e.shown.take(20)))
                // 1.9: a screenshot just asked for opens as it lands, while the app shows.
                if (visible && e.title.startsWith("Screenshot") && System.currentTimeMillis() - screenshotAsked < 120_000) { screenshotAsked = 0; openReceived(app, moments.value.first()) }
                banners.tryEmit(Banner(Banner.Kind.Received, if (e.taken) "Took ${e.title}" else "Received ${e.title}", "From ${e.name}  ·  ${sizeText(e.size)}"))
                Notify.received(app, e)
            }
            is LinkEvent.Sent -> {
                sending.remove(e.transfer); transfers.update { it - e.transfer }; outcomes.tryEmit(e.transfer to true); next(e.peer)
                remember(Moment(1, e.title, e.name, e.count, e.size, System.currentTimeMillis(), emptyList()))
                banners.tryEmit(Banner(Banner.Kind.Sent, "Sent ${e.title}", "To ${e.name}  ·  ${sizeText(e.size)}"))
            }
            is LinkEvent.Failed -> {
                // 1.9: a send that broke with the connection waits at the front of the queue and carries on (island 0.27).
                val again = if (e.outgoing && e.resumable) sending.remove(e.transfer)?.takeIf { it.attempts < 5 } else { sending.remove(e.transfer); null }
                if (again != null) {
                    transfers.update { it - e.transfer }
                    synchronized(queue) { queue.update { listOf(Queued(again.id, again.peer, again.name, again.sources, again.title, again.size, again.toShelf, again.preview, again.ask, again.onStarted, again.token, again.attempts + 1)) + it } }
                    banners.tryEmit(Banner(Banner.Kind.Info, "${e.title} is waiting", "Resumes when ${e.name} is back"))
                    scope.launch { delay(4_000); if (peers.value.firstOrNull { it.id == e.peer }?.online == true) next(e.peer) }
                    return
                }
                if (e.outgoing) { outcomes.tryEmit(e.transfer to false); scope.launch { delay(50); next(e.peer) } }
                transfers.update { it - e.transfer }; offers.update { list -> list.filterNot { it.transfer == e.transfer } }; Notify.cancel(app, Notify.OFFER + e.transfer)
                if (music.value?.transfer == e.transfer) music.value = null
                if (e.detail != "You stopped it") banners.tryEmit(Banner(Banner.Kind.Failed, if (e.title.isEmpty()) "Couldn't share" else "Couldn't share ${e.title}", e.detail))
            }
            is LinkEvent.Music -> { musicOfferedAt = System.currentTimeMillis(); music.value = e; if (!visible) Notify.music(app, e) }
            // Played from where the PC was when it was answered (it kept playing until then, and paused while the song came).
            is LinkEvent.MusicFile -> {
                transfers.update { it - e.transfer }; Notify.cancel(app, Notify.MUSIC)
                val start = (e.music.position + (musicAnsweredAt - musicOfferedAt).coerceIn(0, 600_000) / 1000.0).coerceAtLeast(0.0)
                Player.play(app, e.music, e.peer, e.name, e.shown, start)
            }
            is LinkEvent.Ring -> Ringer.start(app, e.name)
            is LinkEvent.PairingCode -> Unit
            // A PC asks for a photo for its Shelf: the camera opens at once while the app shows, else a notification asks.
            is LinkEvent.PhotoRequested -> { photoFor = e.peer; if (visible) requests.tryEmit("camera") else Notify.photo(app, e.name) }
        }
    }

    // ---- music from a PC ----
    @Volatile private var musicOfferedAt = 0L; @Volatile private var musicAnsweredAt = 0L
    /**
     * Continues a PC's music here: from the song's own file when the PC has one (it arrives, then plays from where the PC
     * was), else in this phone's music app by searching for it. Called from the screen (starting another app needs one).
     */
    fun answerMusic(e: LinkEvent.Music, play: Boolean, context: Context = app) {
        val l = link ?: return; musicAnsweredAt = System.currentTimeMillis(); Notify.cancel(app, Notify.MUSIC)
        if (!play) { l.answerMusic(e.transfer, 0); music.value = null; return }
        if (e.music.fileSize > 0) { l.answerMusic(e.transfer, 2); return }
        l.answerMusic(e.transfer, 1); music.value = null; playFromSearch(context, e.music)
    }
    private fun playFromSearch(context: Context, m: Handoff) {
        val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(android.app.SearchManager.QUERY, listOf(m.title, m.artist).filter { it.isNotBlank() }.joinToString(" "))
            .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
            .putExtra(android.provider.MediaStore.EXTRA_MEDIA_TITLE, m.title).putExtra(android.provider.MediaStore.EXTRA_MEDIA_ARTIST, m.artist)
        // The same app as on the PC, when this phone has it.
        val pkg = when { m.app.contains("spotify", true) -> "com.spotify.music"; m.app.contains("youtube", true) -> "com.google.android.apps.youtube.music"; else -> null }
        val chosen = pkg?.let { p -> Intent(intent).setPackage(p).takeIf { it.resolveActivity(context.packageManager) != null } }
        val ok = runCatching { context.startActivity(chosen ?: intent) }.isSuccess
        banners.tryEmit(if (ok) Banner(Banner.Kind.Music, "Continuing ${m.title}", if (chosen != null) "In ${m.app}" else "In your music app") else Banner(Banner.Kind.Failed, "No music app plays searches"))
    }

    // ---- pairing ----
    private const val BUSY = "Another pairing is under way. Finish it first"
    fun pair(peer: String) { pairResult.value = null; scope.launch { if (link?.pair(peer) == false) pairResult.value = LinkEvent.Paired(peer, "", false, BUSY) } }
    /**
     * Pairs with the PC showing this code on its island (any network): its six digits then show here, as on one Wi-Fi.
     * [key], from the island's QR code: that PC is then known for sure, and only it asks to confirm.
     */
    fun pairWithCode(code: String, key: ByteArray? = null) {
        pairResult.value = null
        if (!prefs.getBoolean("internet", true)) { pairResult.value = LinkEvent.Paired("", "", false, "Turn on Devices › Reach my PCs anywhere first"); return }
        codePairing.value = code
        scope.launch {
            // Opened from a pairing link, the app may still be starting.
            var l = link; var waited = 0
            while (l == null && waited < 50) { delay(100); waited++; l = link }
            val why = when { l == null -> "Starting… try again in a moment"; !l.pairWithCode(code, key) -> BUSY; else -> null }
            if (why != null) { codePairing.value = null; pairResult.value = LinkEvent.Paired("", "", false, why) }
        }
    }
    fun confirmPair(yes: Boolean) { link?.confirmPair(yes); if (!yes) pairCode.value = null }
    fun forget(peer: String) { link?.forget(peer); if (selected.value == peer) { selected.value = null; prefs.edit().remove("pc").apply(); pickDefault() } }

    // ---- files ----
    fun answer(offer: LinkEvent.Offer, accept: Boolean) { link?.answer(offer.transfer, accept); offers.update { list -> list.filterNot { it.transfer == offer.transfer } }; Notify.cancel(app, Notify.OFFER + offer.transfer) }
    fun cancel(transfer: Int) { link?.cancel(transfer) }

    /**
     * Files from other apps (content URIs) to a PC, in one transfer; [toShelf]: onto its island's Shelf (a photo taken for
     * it). 1.8: one transfer to a PC at a time: while one is under way, the next waits its turn in [queue].
     */
    fun send(peer: String, uris: List<Uri>, toShelf: Boolean = false, ask: Int = 0, onStarted: (Int) -> Unit = {}) {
        if (uris.isEmpty()) return
        scope.launch {
            if (link == null) { banners.tryEmit(Banner(Banner.Kind.Failed, "Not connected", "Open Arnav Island and try again")); return@launch }
            val sources = uris.mapNotNull { sourceOf(it) }
            if (sources.isEmpty()) { banners.tryEmit(Banner(Banner.Kind.Failed, "Couldn't read those files")); return@launch }
            val names = sources.map { it.rel }
            val title = if (names.size == 1) names[0] else "${names[0]} and ${names.size - 1} more"
            val target = peers.value.firstOrNull { it.id == peer }
            // 1.7: a picture of the first one, for the island to show as it arrives.
            val preview = Previews.of(app, uris[0])
            val item = Queued(System.nanoTime(), peer, target?.name ?: "your PC", sources, title, sources.sumOf { s -> s.size }, toShelf, preview, ask, onStarted)
            val busy = synchronized(queue) { val b = transfers.value.values.any { it.outgoing && it.peer == peer } || queue.value.any { it.peer == peer }; if (b) queue.update { it + item }; b }
            if (!busy) start(item)
        }
    }
    /** 1.8: sends waiting their turn, in order. */
    val queue = MutableStateFlow<List<Queued>>(emptyList())
    /** 1.9: what each send under way is, so one that breaks can be tried again. */
    private val sending = java.util.concurrent.ConcurrentHashMap<Int, Queued>()
    private fun start(q: Queued) {
        val l = link ?: return
        val id = l.send(q.peer, q.sources, q.title, toShelf = q.toShelf, preview = q.preview, ask = q.ask, token = q.token)
        sending[id] = q
        transfers.update { it + (id to Transfer(id, q.peer, q.name, q.title, 0, q.size, true, sampledAt = System.currentTimeMillis())) }
        q.onStarted(id)
    }
    /** The next send waiting for [peer], once the one before has ended. */
    private fun next(peer: String) {
        val q = synchronized(queue) {
            if (transfers.value.values.any { it.outgoing && it.peer == peer }) return
            queue.value.firstOrNull { it.peer == peer }?.also { n -> queue.update { list -> list.filterNot { it.id == n.id } } }
        } ?: return
        start(q)
    }
    /** 1.9: the same files to every paired PC that is here. */
    fun sendToAll(uris: List<Uri>) { val pcs = peers.value.filter { it.paired && !it.phone && it.online }; if (pcs.isEmpty()) { banners.tryEmit(Banner(Banner.Kind.Failed, "None of your PCs is here")); return }; pcs.forEach { send(it.id, uris) } }
    /** Moves a waiting send from one place in the queue to another. */
    fun moveQueued(from: Int, to: Int) = queue.update { list -> if (from !in list.indices || to !in list.indices) list else list.toMutableList().apply { add(to, removeAt(from)) } }
    fun dropQueued(id: Long) = queue.update { list -> list.filterNot { it.id == id } }
    /** Pauses or resumes a transfer (with PCs on island 0.26 or later, which wait for it). */
    fun pause(transfer: Int, paused: Boolean) { link?.pause(transfer, paused); transfers.update { m -> m[transfer]?.let { m + (transfer to it.copy(paused = paused, rate = 0.0)) } ?: m } }
    fun canPause(t: Transfer) = (peers.value.firstOrNull { it.id == t.peer }?.revision ?: 0) >= 9
    private fun sourceOf(uri: Uri): Source? = runCatching {
        val resolver = app.contentResolver
        var name: String? = null; var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0); if (!c.isNull(1)) size = c.getLong(1) }
        }
        if (size < 0) size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1
        val safe = safeName(name ?: uri.lastPathSegment ?: "file")
        if (size < 0) {
            // A stream of unknown length is copied first, so the PC is told its exact size.
            val copy = File(app.cacheDir, "outgoing").apply { mkdirs() }.let { File(it, safe) }
            resolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } } ?: return null
            Source(safe, copy.length()) { copy.inputStream() }
        } else Source(safe, size) { resolver.openInputStream(uri) ?: throw java.io.IOException("unreadable") }
    }.getOrNull()

    private fun remember(m: Moment) { moments.update { (listOf(m) + it).take(40) }; saveMoments() }
    private fun saveMoments() = runCatching {
        val a = JSONArray(); moments.value.forEach { m -> a.put(JSONObject().put("k", m.kind).put("t", m.title).put("f", m.from).put("c", m.count).put("s", m.size).put("a", m.at).put("u", JSONArray(m.uris))) }
        prefs.edit().putString("moments", a.toString()).apply()
    }
    private fun loadMoments(): List<Moment> = runCatching {
        val a = JSONArray(prefs.getString("moments", "[]")); (0 until a.length()).map { i -> val o = a.getJSONObject(i); val u = o.optJSONArray("u") ?: JSONArray()
            Moment(o.getInt("k"), o.getString("t"), o.getString("f"), o.getInt("c"), o.getLong("s"), o.getLong("a"), (0 until u.length()).map { u.getString(it) }) }
    }.getOrDefault(emptyList())
    fun clearMoments() { moments.value = emptyList(); saveMoments() }
    /** 1.8: one moment taken out of the history. */
    fun forgetMoment(m: Moment) { moments.update { list -> list.filterNot { it.at == m.at && it.title == m.title } }; saveMoments() }

    // ---- the remote ----
    /** One remote command to the chosen PC; null (with a banner saying why) when it couldn't be done. */
    suspend fun command(cmd: Int, payload: ByteArray = ByteArray(0), quiet: Boolean = false): RemoteReply? = withContext(Dispatchers.IO) {
        val l = link; val p = pc()
        if (l == null || p == null) { if (!quiet) banners.tryEmit(Banner(Banner.Kind.Failed, "No PC yet", "Pair with your PC first")); return@withContext null }
        if (!p.remote && p.online) { if (!quiet) banners.tryEmit(Banner(Banner.Kind.Failed, "Update Arnav Island on ${p.name}", "The remote needs version 0.19 or later")); return@withContext null }
        val reply = l.remote(p.id, cmd, payload)
        when {
            reply == null -> if (!quiet) banners.tryEmit(Banner(Banner.Kind.Failed, "${p.name} didn't answer", l.lastRemoteError ?: "Is Arnav Island running there?"))
            reply.status == Proto.NOT_ALLOWED -> if (!quiet) banners.tryEmit(Banner(Banner.Kind.Failed, "${p.name} said no", "Turn on “My phone can control this PC” in the island’s Settings"))
            !reply.ok && !quiet -> banners.tryEmit(Banner(Banner.Kind.Failed, "${p.name} couldn't do that"))
        }
        reply
    }
    /** The chosen PC's status (its media with the cover, sound, battery...), refreshed by the Remote screen while it shows. */
    suspend fun refreshStatus(): PcStatus? = withContext(Dispatchers.IO) {
        val l = link ?: return@withContext null; val p = pc() ?: return@withContext null
        if (!p.online) { statusError.value = "${p.name} is away"; return@withContext null }
        if (!p.remote) { statusError.value = "Update Arnav Island on ${p.name} for the remote"; return@withContext null }
        val previous = status.value?.takeIf { it.pcName.isNotEmpty() }
        val s = l.status(p.id, previous?.coverHash, previous)
        if (s == null) statusError.value = l.lastRemoteError ?: "${p.name} didn't answer" else { statusError.value = null; status.value = s; lyricsFor(p, s); heard(p, s, previous); playbackFor(p, s, previous) }
        s
    }

    // ---- 1.8: your PCs, as you name them ----
    val looks = MutableStateFlow<Map<String, PcLook>>(emptyMap())
    fun pcLook(id: String?): PcLook = id?.let { looks.value[it] } ?: PcLook(null, ((id?.hashCode() ?: 0) and 0x7fffffff) % 6, 0)
    fun setPcLook(id: String, look: PcLook) {
        looks.update { it + (id to look) }
        runCatching { val o = JSONObject(); looks.value.forEach { (k, v) -> o.put(k, JSONObject().put("n", v.name ?: "").put("c", v.color).put("i", v.icon)) }; prefs.edit().putString("looks", o.toString()).apply() }
        link?.let { peers.value = dressed(it.peers()) }
    }
    private fun loadLooks(): Map<String, PcLook> = runCatching {
        val o = JSONObject(prefs.getString("looks", "{}")); o.keys().asSequence().associateWith { k -> val v = o.getJSONObject(k); PcLook(v.optString("n").takeIf { it.isNotBlank() }, v.optInt("c"), v.optInt("i")) }
    }.getOrDefault(emptyMap())
    /** The devices as the link has them, with the names you gave them. */
    private fun dressed(list: List<PeerView>) = list.map { p -> looks.value[p.id]?.name?.let { p.copy(name = it) } ?: p }

    // ---- 1.8: when each PC was last here, and how its connection has gone ----
    val lastSeen = MutableStateFlow<Map<String, Long>>(emptyMap())
    val rttTrail = MutableStateFlow<Map<String, List<Pair<Long, Double>>>>(emptyMap())
    @Volatile private var seenSaved = 0L
    private fun seen(list: List<PeerView>) {
        val now = System.currentTimeMillis(); val here = list.filter { it.paired && it.online }
        if (here.isNotEmpty()) lastSeen.update { it + here.associate { p -> p.id to now } }
        val left = list.any { p -> p.paired && !p.online && (lastSeen.value[p.id] ?: 0) > seenSaved }
        if (left || now - seenSaved > 120_000) { seenSaved = now; runCatching { val o = JSONObject(); lastSeen.value.forEach { (k, v) -> o.put(k, v) }; prefs.edit().putString("seen", o.toString()).apply() } }
        val rtts = here.filter { it.internet && it.rtt > 0 }
        if (rtts.isNotEmpty()) rttTrail.update { m -> m + rtts.associate { p -> p.id to ((m[p.id] ?: emptyList()) + (now to p.rtt)).takeLast(120) } }
    }
    private fun loadSeen(): Map<String, Long> = runCatching { val o = JSONObject(prefs.getString("seen", "{}")); o.keys().asSequence().associateWith { o.getLong(it) } }.getOrDefault(emptyMap())
    /** Asks the link to find its paths again (the relays, discovery and a direct path), for the connection's details. */
    fun retest() { link?.networkChanged() }

    // ---- 1.8: waking a PC (its adapters, learnt from it while it was here) ----
    val machines = MutableStateFlow<Map<String, List<Adapter>>>(emptyMap())
    private fun learnMachine(peer: String) {
        val p = peers.value.firstOrNull { it.id == peer } ?: return; if (p.revision < 9 || p.phone) return
        scope.launch {
            val list = link?.machine(peer)?.takeIf { it.isNotEmpty() } ?: return@launch
            machines.update { it + (peer to list) }
            runCatching {
                val o = JSONObject(); machines.value.forEach { (k, v) -> o.put(k, JSONArray(v.map { a -> JSONObject().put("m", a.mac.hex()).put("n", a.name).put("a", a.ipv4).put("p", a.prefix) })) }
                prefs.edit().putString("machines", o.toString()).apply()
            }
        }
    }
    private fun loadMachines(): Map<String, List<Adapter>> = runCatching {
        val o = JSONObject(prefs.getString("machines", "{}"))
        o.keys().asSequence().associateWith { k -> val a = o.getJSONArray(k); (0 until a.length()).mapNotNull { i -> val v = a.getJSONObject(i); v.getString("m").unhex()?.let { m -> Adapter(m, v.optString("n"), v.optString("a"), v.optInt("p")) } } }
    }.getOrDefault(emptyMap())
    fun canWake(peer: String?) = peer != null && !machines.value[peer].isNullOrEmpty()
    /** Wakes a PC on this network (its adapters must allow Wake-on-LAN): a magic packet to each of its addresses. */
    fun wake(peer: String) {
        val p = peers.value.firstOrNull { it.id == peer }; val list = machines.value[peer]
        if (list.isNullOrEmpty()) { banners.tryEmit(Banner(Banner.Kind.Failed, "Can't wake ${p?.name ?: "it"} yet", "Needs island 0.26")); return }
        scope.launch {
            val sent = Wake.send(list)
            banners.tryEmit(if (sent) Banner(Banner.Kind.Info, "Waking ${p?.name ?: "your PC"}", "If it allows Wake-on-LAN, it starts in a moment") else Banner(Banner.Kind.Failed, "Couldn't send the wake signal", "Join the PC's network first"))
        }
    }

    // ---- 1.8: what you've heard on your PCs, and loved ----
    val history = MutableStateFlow<List<Song>>(emptyList())
    private fun historyFile() = File(app.filesDir, "history.json")
    private fun heard(p: PeerView, s: PcStatus, previous: PcStatus?) {
        if (!s.available || s.title.isBlank()) return
        val key = (s.title + "\t" + s.artist).lowercase(); val now = System.currentTimeMillis()
        val same = previous?.available == true && previous.title == s.title && previous.artist == s.artist
        val old = history.value.firstOrNull { it.key == key }
        if (same && old != null && now - old.last < 60_000) return
        val cover = old?.cover ?: s.cover?.let { c -> runCatching { Art.bitmap(c, 160)?.let { b -> java.io.ByteArrayOutputStream().also { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray() } }.getOrNull() }
        val song = Song(key, s.title, s.artist, s.app, p.name, cover, old?.first ?: now, now, (old?.plays ?: 0) + if (same) 0 else 1, old?.loved ?: false)
        history.update { list -> (listOf(song) + list.filterNot { it.key == key }).take(80) }
        saveHistory()
    }
    fun love(key: String, loved: Boolean) { history.update { list -> list.map { if (it.key == key) it.copy(loved = loved) else it } }; saveHistory() }
    /** Loves what plays now (it joins the history if it isn't there yet). */
    fun loveNow(loved: Boolean) { val s = status.value ?: return; val p = pc() ?: return; heard(p, s, null); love((s.title + "\t" + s.artist).lowercase(), loved) }
    fun forgetSong(key: String) { history.update { list -> list.filterNot { it.key == key } }; saveHistory() }
    fun clearHistory() { history.update { list -> list.filter { it.loved } }; saveHistory() }
    private fun saveHistory() = scope.launch {
        runCatching {
            val a = JSONArray(); history.value.forEach { h -> a.put(JSONObject().put("k", h.key).put("t", h.title).put("a", h.artist).put("p", h.app).put("c", h.pc).put("f", h.first).put("l", h.last).put("n", h.plays).put("v", h.loved)
                .put("i", h.cover?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) } ?: "")) }
            historyFile().writeText(a.toString())
        }
    }
    private fun loadHistory(): List<Song> = runCatching {
        val a = JSONArray(historyFile().takeIf { it.exists() }?.readText() ?: "[]")
        (0 until a.length()).map { i -> val o = a.getJSONObject(i)
            Song(o.getString("k"), o.getString("t"), o.optString("a"), o.optString("p"), o.optString("c"), o.optString("i").takeIf { it.isNotEmpty() }?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) },
                o.getLong("f"), o.getLong("l"), o.optInt("n", 1), o.optBoolean("v")) }
    }.getOrDefault(emptyList())
    /**
     * Plays a song from the history on the PC: from the island's own music when it has it (its command bar finds it),
     * else as a search in the PC's browser, in the service it last played in.
     */
    fun replay(song: Song) {
        val p = pc() ?: return
        scope.launch {
            if (islandReady(p)) {
                val r = queryCommands("play ${song.title}")
                val i = r?.rows?.indexOfFirst { it.kind == 40 } ?: -1
                if (r != null && i >= 0 && runCommand("play ${song.title}", i, r.rows[i].title, false)?.outcome == 0) { banners.tryEmit(Banner(Banner.Kind.Music, "Playing ${song.title}", "On ${p.name}")); return@launch }
            }
            val q = java.net.URLEncoder.encode(listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" "), "UTF-8")
            val url = when {
                song.app.contains("spotify", true) -> "https://open.spotify.com/search/$q"
                song.app.contains("apple", true) || song.app.contains("itunes", true) -> "https://music.apple.com/search?term=$q"
                song.app.contains("youtube music", true) -> "https://music.youtube.com/search?q=$q"
                else -> "https://www.youtube.com/results?search_query=$q"
            }
            if (command(Proto.CMD_OPEN, url.toByteArray())?.ok == true) banners.tryEmit(Banner(Banner.Kind.Music, "Finding ${song.title}", "On ${p.name}, in its browser"))
        }
    }
    /** Looks a song up on this phone: in its music app (as music handed over is), else on the web. */
    fun searchHere(context: Context, song: Song) = playFromSearch(context, Handoff(song.title, song.artist, app = song.app))

    // ---- 1.8: shuffle, repeat and up next (island 0.26) ----
    val playback = MutableStateFlow<Playback?>(null)
    @Volatile private var playbackAsked = 0L
    private fun playbackFor(p: PeerView, s: PcStatus, previous: PcStatus?) {
        if (p.revision < 9 || !s.available) { playback.value = null; return }
        val now = System.currentTimeMillis()
        if (previous?.title == s.title && now - playbackAsked < 4_000) return
        playbackAsked = now; scope.launch { playback.value = link?.playback(p.id) }
    }
    private fun playbackOp(op: Int, arg: ByteArray, seen: (Playback) -> Playback) {
        playback.update { it?.let(seen) }
        scope.launch { val p = pc() ?: return@launch; link?.playback(p.id, op, arg)?.let { playback.value = it } ?: banners.tryEmit(Banner(Banner.Kind.Failed, "${p.name} couldn't change that")) }
    }
    fun setShuffle(on: Boolean) = playbackOp(1, byteArrayOf(if (on) 1 else 0)) { it.copy(shuffle = on) }
    fun setRepeat(mode: Int) = playbackOp(2, byteArrayOf(mode.toByte())) { it.copy(repeat = mode) }
    fun playUpNext(index: Int) = playbackOp(3, Bytes().u16(index).build()) { it.copy(upNext = it.upNext.drop(index + 1)) }

    // ---- 1.8: the PC's apps, each with its volume, and its sound's levels (island 0.26) ----
    val mixer = MutableStateFlow<List<MixerApp>>(emptyList())
    val mixerIcons = MutableStateFlow<Map<String, androidx.compose.ui.graphics.ImageBitmap>>(emptyMap())
    private val mixerAsked = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    suspend fun refreshMixer() = withContext(Dispatchers.IO) {
        val p = pc()?.takeIf { it.online && it.revision >= 9 } ?: return@withContext
        // Icons only for apps not seen yet.
        val icons = mixer.value.isEmpty() || mixer.value.any { it.name !in mixerIcons.value }
        val list = link?.mixer(p.id, icons) ?: return@withContext
        val now = System.currentTimeMillis()
        // What a finger just set stays until the PC agrees.
        mixer.value = list.map { a -> val asked = mixer.value.firstOrNull { it.pid == a.pid }; if (asked != null && now - (mixerAsked[a.pid] ?: 0) < 1500) MixerApp(a.pid, a.name, asked.volume, a.peak, asked.muted, a.system, a.active, a.icon) else a }
        val fresh = list.filter { it.icon != null && it.name !in mixerIcons.value }
        if (fresh.isNotEmpty()) mixerIcons.update { m -> m + fresh.mapNotNull { a -> runCatching { android.graphics.BitmapFactory.decodeByteArray(a.icon, 0, a.icon!!.size)?.let { bmp -> a.name to bmp.asImageBitmap() } }.getOrNull() } }
    }
    fun setAppVolume(pid: Long, percent: Int, final: Boolean) {
        mixerAsked[pid] = System.currentTimeMillis()
        mixer.update { list -> list.map { if (it.pid == pid) MixerApp(it.pid, it.name, percent, it.peak, it.muted, it.system, it.active, it.icon) else it } }
        val p = pc() ?: return; scope.launch { link?.mixerVolume(p.id, pid, percent)?.takeIf { final }?.let { mixer.value = it } }
    }
    fun setAppMute(pid: Long, muted: Boolean) {
        mixerAsked[pid] = System.currentTimeMillis()
        mixer.update { list -> list.map { if (it.pid == pid) MixerApp(it.pid, it.name, it.volume, it.peak, muted, it.system, it.active, it.icon) else it } }
        val p = pc() ?: return; scope.launch { link?.mixerMute(p.id, pid, muted)?.let { mixer.value = it } }
    }
    val levels = MutableStateFlow<Levels?>(null)
    suspend fun refreshLevels() = withContext(Dispatchers.IO) { val p = pc()?.takeIf { it.online && it.revision >= 9 } ?: return@withContext; levels.value = link?.levels(p.id) }

    // ---- 1.9: the PC's top apps, temperatures, files, clipboard, screenshots and a speed test (island 0.27) ----
    fun rev10(p: PeerView? = pc()) = p != null && p.online && p.revision >= 10
    val processes = MutableStateFlow<List<PcProcess>>(emptyList())
    val processIcons = MutableStateFlow<Map<String, androidx.compose.ui.graphics.ImageBitmap>>(emptyMap())
    @Volatile var processSort = 0
    /** 1.9: the new commands a PC said it doesn't have (an island between versions), so their screens say so instead of waiting. */
    val missing = MutableStateFlow<Set<Int>>(emptySet())
    /** Asks a newly arrived island 0.27 which of these it has: its apps' list and a folder listing (both only read). */
    private fun probe(peer: String) {
        val p = peers.value.firstOrNull { it.id == peer } ?: return; if (p.revision < 10 || p.phone || peer != pc()?.id) return
        // Taken as missing until the PC says it has them, so their rows appear once rather than show and then vanish.
        missing.update { it + Proto.CMD_PROCESSES + Proto.CMD_FILES + Proto.CMD_SCREENSHOT }
        // 1.11: island 0.28's, the same way (they only read; what they say is kept, so their sections open with it).
        if (p.revision >= 11) missing.update { it + REV11 }
        scope.launch {
            val l = link ?: return@launch
            note(Proto.CMD_PROCESSES, l.remote(peer, Proto.CMD_PROCESSES, Bytes().u8(0).u8(0).u8(1).u8(0).build())?.status)
            note(Proto.CMD_FILES, l.files(peer, "", 0, max = 1, thumbs = false).first.takeIf { it != Proto.NOT_ALLOWED } ?: Proto.OK)
            // A screenshot isn't taken to find out: an empty ask is refused as unsupported (0.27) or as malformed (later).
            l.remote(peer, Proto.CMD_SCREENSHOT, ByteArray(0))?.status?.let { if (it == Proto.UNSUPPORTED) note(Proto.CMD_SCREENSHOT, it) else missing.update { m -> m - Proto.CMD_SCREENSHOT } }
            if (p.revision >= 11) probe11(peer)
        }
    }
    private val REV11 = setOf(Proto.CMD_BATTERY_HEALTH, Proto.CMD_STORAGE, Proto.CMD_STARTUP, Proto.CMD_NETWORK, Proto.CMD_UPDATES, Proto.CMD_APPS)
    private suspend fun probe11(peer: String) {
        val l = link ?: return
        // A dropped answer is asked again (twice), so a moment's trouble doesn't hide a section until the PC comes back.
        suspend fun <T> tried(cmd: Int, ask: () -> Pair<Int, T?>): T? {
            repeat(3) { i -> val (s, v) = ask(); note(cmd, s); if (s == Proto.OK || s == Proto.UNSUPPORTED || s == Proto.NOT_ALLOWED) return v; delay(1500L * (i + 1)) }
            return null
        }
        fun mine() = pc()?.id == peer
        tried(Proto.CMD_BATTERY_HEALTH) { l.batteryHealth(peer) }?.let { if (mine()) batteryHealth.value = it }
        tried(Proto.CMD_STORAGE) { l.storage(peer) }?.let { if (mine()) storage.value = it }
        tried(Proto.CMD_STARTUP) { l.startup(peer) }?.let { if (mine()) showStartup(it) }
        tried(Proto.CMD_NETWORK) { l.network(peer) }?.let { if (mine()) network.value = it }
        tried(Proto.CMD_UPDATES) { l.updates(peer) }?.let { if (mine()) updates.value = it }
        tried(Proto.CMD_APPS) { l.startMenu(peer, false) }?.let { if (mine()) showPcApps(it) }
    }
    private fun note(cmd: Int, status: Int?) { if (status == Proto.UNSUPPORTED) missing.update { it + cmd } else if (status == Proto.OK) missing.update { it - cmd } }
    suspend fun refreshProcesses() = withContext(Dispatchers.IO) {
        val p = pc()?.takeIf { rev10(it) } ?: return@withContext
        val icons = processes.value.isEmpty() || processes.value.size < 3
        val r = link?.remote(p.id, Proto.CMD_PROCESSES, Bytes().u8(0).u8(processSort).u8(15).u8(if (icons) 1 else 0).build()); note(Proto.CMD_PROCESSES, r?.status)
        val list = r?.takeIf { it.ok }?.let { IslandWire.processes(it.payload) } ?: return@withContext
        processes.value = list
        val fresh = list.filter { it.icon != null && it.name !in processIcons.value }
        if (fresh.isNotEmpty()) processIcons.update { m -> m + fresh.mapNotNull { a -> runCatching { android.graphics.BitmapFactory.decodeByteArray(a.icon, 0, a.icon!!.size)?.let { bmp -> a.name to bmp.asImageBitmap() } }.getOrNull() } }
    }
    // 1.11: the list is only read (island 0.28 says no to ending apps from a phone).
    val sensors = MutableStateFlow<Sensors?>(null)
    suspend fun refreshSensors() = withContext(Dispatchers.IO) { val p = pc()?.takeIf { rev10(it) } ?: return@withContext; link?.sensors(p.id)?.let { sensors.value = it; keepHeat(p, it); Alerts.checkHeat(app, p, it) } }

    /** 1.9: the PC's files, a folder at a time: what it said (NOT_ALLOWED when browsing is off there) and the page. */
    suspend fun files(path: String, sort: Int): Pair<Int, FileList?> = withContext(Dispatchers.IO) { val p = pc()?.takeIf { rev10(it) } ?: return@withContext Proto.FAILED to null; (link?.files(p.id, path, sort) ?: (Proto.FAILED to null)).also { note(Proto.CMD_FILES, it.first) } }
    fun takeFromPc(path: String, name: String) {
        val p = pc() ?: return
        scope.launch { if (link?.takeFromPc(p.id, path) == true) banners.tryEmit(Banner(Banner.Kind.Received, "Bringing $name over", "From ${p.name}")) else banners.tryEmit(Banner(Banner.Kind.Failed, "${p.name} couldn't send $name")) }
    }
    /** 1.9: a screenshot of the PC, brought here (it opens when it arrives, while the app shows). */
    @Volatile var screenshotAsked = 0L
    fun screenshot(what: Int = 0) {
        val p = pc() ?: return
        if (!rev10(p)) { banners.tryEmit(Banner(Banner.Kind.Failed, "Update Arnav Island on ${p.name}", "Screenshots need version 0.27 or later")); return }
        scope.launch { screenshotAsked = System.currentTimeMillis(); val size = link?.screenshot(p.id, what)
            banners.tryEmit(when { size != null -> Banner(Banner.Kind.Photo, "Taking a screenshot of ${p.name}", "${size.first} × ${size.second}")
                link?.remote(p.id, Proto.CMD_SCREENSHOT, ByteArray(0))?.status == Proto.UNSUPPORTED -> { missing.update { it + Proto.CMD_SCREENSHOT }; Banner(Banner.Kind.Info, "Screenshots aren't on ${p.name}'s island yet") }
                else -> Banner(Banner.Kind.Failed, "${p.name} couldn't take one") }) }
    }
    /** 1.9: the connection's speed both ways (bits a second), each measured for about three seconds; null when it couldn't. */
    suspend fun speedTest(onStep: (down: Double?, up: Double?) -> Unit): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val p = pc()?.takeIf { rev10(it) } ?: return@withContext null; val l = link ?: return@withContext null
        fun run(down: Boolean): Double? { val chunk = if (p.internet && p.path != 2) 60_000 else 190_000; var bytes = 0L; val start = System.nanoTime()
            while (System.nanoTime() - start < 3_000_000_000L) { if (!l.speed(p.id, down, chunk)) return null; bytes += chunk
                val secs = (System.nanoTime() - start) / 1e9; if (secs > .3) { if (down) onStep(bytes * 8 / secs, null) else onStep(null, bytes * 8 / secs) } }
            return bytes * 8 / ((System.nanoTime() - start) / 1e9) }
        val down = run(true) ?: return@withContext null; val up = run(false) ?: return@withContext null
        down to up
    }

    // ---- 1.9: the clipboard's history, both ways ----
    /** This phone's own history: what you copied here (as the app saw it) and what came from your PCs, newest first. */
    val clipHistory = MutableStateFlow<List<ClipItem>>(emptyList())
    fun rememberClip(text: String, from: String) {
        if (text.isBlank()) return
        clipHistory.update { list -> (listOf(ClipItem(text.take(20_000), System.currentTimeMillis(), list.firstOrNull { it.text == text }?.pinned ?: false, from)) + list.filterNot { it.text == text }).let { l -> l.filter { it.pinned } + l.filterNot { it.pinned }.take(50) } }
        saveClips()
    }
    fun pinClip(item: ClipItem, on: Boolean) { clipHistory.update { list -> list.map { if (it.at == item.at && it.text == item.text) it.copy(pinned = on) else it } }; saveClips() }
    fun forgetClip(item: ClipItem) { clipHistory.update { list -> list.filterNot { it.at == item.at && it.text == item.text } }; saveClips() }
    private fun saveClips() = scope.launch { runCatching { val a = JSONArray(); clipHistory.value.forEach { c -> a.put(JSONObject().put("t", c.text).put("a", c.at).put("p", c.pinned).put("f", c.from)) }; File(app.filesDir, "clips.json").writeText(a.toString()) } }
    private fun loadClips(): List<ClipItem> = runCatching { val a = JSONArray(File(app.filesDir, "clips.json").takeIf { it.exists() }?.readText() ?: "[]"); (0 until a.length()).map { i -> val o = a.getJSONObject(i); ClipItem(o.getString("t"), o.getLong("a"), o.optBoolean("p"), o.optString("f")) } }.getOrDefault(emptyList())
    /** The PC's own clipboard history (island 0.27). */
    val pcClips = MutableStateFlow<List<PcClip>>(emptyList())
    suspend fun refreshPcClips() = withContext(Dispatchers.IO) { val p = pc()?.takeIf { rev10(it) } ?: return@withContext; link?.clips(p.id)?.let { pcClips.value = it } }
    fun pcClipOp(op: Int, clip: PcClip, arg: Int = 0) { val p = pc() ?: return; scope.launch { link?.clipOp(p.id, op, clip.id, arg)?.let { pcClips.value = it } } }
    /** A copy from the PC's history onto this phone's clipboard. */
    suspend fun pcClipHere(clip: PcClip): io.github.arnavdugad.arnavisland.link.PcClipFull? = withContext(Dispatchers.IO) { val p = pc() ?: return@withContext null; link?.clipHere(p.id, clip.id) }

    // ---- 1.9: an hour of the PC's numbers, kept on this phone (one point every ten seconds) ----
    val hourTrail = MutableStateFlow<List<StatsPoint>>(emptyList())
    @Volatile private var hourPc: String? = null; @Volatile private var hourSaved = 0L
    private fun hourFile(pc: String) = File(app.filesDir, "hour-$pc.json")
    private fun keepHour(p: PeerView, point: StatsPoint) {
        if (hourPc != p.id) { hourPc = p.id; hourTrail.value = runCatching { val a = JSONArray(hourFile(p.id).readText()); (0 until a.length()).map { i -> val o = a.getJSONArray(i); StatsPoint(o.getLong(0), o.getDouble(1).toFloat(), o.getDouble(2).toFloat(), o.getDouble(3).toFloat(), o.getDouble(4).toFloat()) } }.getOrDefault(emptyList()) }
        val last = hourTrail.value.lastOrNull()
        if (last != null && point.at - last.at < 10_000) return
        hourTrail.value = (hourTrail.value + point).filter { point.at - it.at <= 3_600_000 }
        if (point.at - hourSaved > 60_000) { hourSaved = point.at; val list = hourTrail.value
            scope.launch { runCatching { val a = JSONArray(); list.forEach { a.put(JSONArray().put(it.at).put(it.cpu.toDouble()).put(it.gpu.toDouble()).put(it.download.toDouble()).put(it.upload.toDouble())) }; hourFile(p.id).writeText(a.toString()) } } }
    }
    // ---- 1.11: and an hour of its temperatures and fans, the same way (°C, rpm; -1 unknown) ----
    data class HeatPoint(val at: Long, val cpu: Float, val gpu: Float, val fan: Int)
    val heatTrail = MutableStateFlow<List<HeatPoint>>(emptyList())
    @Volatile private var heatPc: String? = null; @Volatile private var heatSaved = 0L
    private fun heatFile(pc: String) = File(app.filesDir, "heat-$pc.json")
    private fun keepHeat(p: PeerView, s: Sensors) {
        if (heatPc != p.id) { heatPc = p.id; heatTrail.value = runCatching { val a = JSONArray(heatFile(p.id).readText()); (0 until a.length()).map { i -> val o = a.getJSONArray(i); HeatPoint(o.getLong(0), o.getDouble(1).toFloat(), o.getDouble(2).toFloat(), o.getInt(3)) } }.getOrDefault(emptyList()) }
        val point = HeatPoint(System.currentTimeMillis(), s.cpu.takeIf { it.isFinite() } ?: -1f, s.gpu.takeIf { it.isFinite() } ?: -1f, s.fans.maxOfOrNull { it.second } ?: -1)
        if (point.cpu < 0 && point.gpu < 0 && point.fan < 0) return
        val last = heatTrail.value.lastOrNull()
        if (last != null && point.at - last.at < 10_000) return
        heatTrail.value = (heatTrail.value + point).filter { point.at - it.at <= 3_600_000 }
        if (point.at - heatSaved > 60_000) { heatSaved = point.at; val list = heatTrail.value
            scope.launch { runCatching { val a = JSONArray(); list.forEach { a.put(JSONArray().put(it.at).put(it.cpu.toDouble()).put(it.gpu.toDouble()).put(it.fan)) }; heatFile(p.id).writeText(a.toString()) } } }
    }

    // ---- 1.11: the PC's battery health, storage, startup apps, network, Windows Update and its apps (island 0.28) ----
    fun rev11(p: PeerView? = pc()) = p != null && p.online && p.revision >= 11
    /** Whether the chosen PC has this command of island 0.28's (it said so when it arrived). */
    fun has11(cmd: Int, p: PeerView? = pc()) = rev11(p) && cmd !in missing.value
    val batteryHealth = MutableStateFlow<BatteryHealth?>(null)
    suspend fun refreshBatteryHealth() = withContext(Dispatchers.IO) { val p = pc()?.takeIf { rev11(it) } ?: return@withContext; val (s, v) = link?.batteryHealth(p.id) ?: return@withContext; note(Proto.CMD_BATTERY_HEALTH, s); v?.let { batteryHealth.value = it } }
    /** The PC's drives, and what fills its Windows drive (measured on the PC, a moment at first; cached there ten minutes). */
    val storage = MutableStateFlow<Storage?>(null)
    suspend fun refreshStorage(again: Boolean = false) = withContext(Dispatchers.IO) {
        val p = pc()?.takeIf { rev11(it) } ?: return@withContext
        if (again) storage.update { it?.let { s -> Storage(false, 0, s.drives, s.groups) } }
        val (s, v) = link?.storage(p.id, again) ?: return@withContext; note(Proto.CMD_STORAGE, s)
        if (v != null) storage.value = v else if (again) banners.tryEmit(Banner(Banner.Kind.Failed, "${p.name} couldn't measure it"))
    }
    /** The apps that start with Windows there (null until the PC says), each with its icon. */
    val startup = MutableStateFlow<List<StartupApp>?>(null)
    val startupIcons = MutableStateFlow<Map<String, androidx.compose.ui.graphics.ImageBitmap>>(emptyMap())
    @Volatile private var startupAsked = 0L
    private fun showStartup(list: List<StartupApp>) {
        // What a finger just set stays until the PC has answered it.
        if (System.currentTimeMillis() - startupAsked < 1500) return
        startup.value = list
        val fresh = list.filter { it.icon != null && it.id !in startupIcons.value }
        if (fresh.isNotEmpty()) startupIcons.update { m -> m + fresh.mapNotNull { a -> decode(a.icon)?.let { a.id to it } } }
    }
    suspend fun refreshStartup() = withContext(Dispatchers.IO) { val p = pc()?.takeIf { rev11(it) } ?: return@withContext; val (s, v) = link?.startup(p.id) ?: return@withContext; note(Proto.CMD_STARTUP, s); v?.let { showStartup(it) } }
    /** Turns a startup app on or off (the user's own only): shown at once, then as the PC says. */
    fun setStartup(item: StartupApp, enabled: Boolean) {
        val p = pc() ?: return
        startupAsked = System.currentTimeMillis()
        startup.update { list -> list?.map { if (it.id == item.id) StartupApp(it.id, it.name, it.command, it.source, enabled, it.canChange, it.icon) else it } }
        scope.launch {
            val (s, v) = link?.setStartup(p.id, item.id, enabled) ?: (Proto.FAILED to null); startupAsked = 0
            if (v != null) showStartup(v)
            else { banners.tryEmit(if (s == Proto.NOT_ALLOWED) Banner(Banner.Kind.Failed, "${item.name} is for every user", "Change it on ${p.name}") else Banner(Banner.Kind.Failed, "${p.name} couldn't change that"))
                link?.startup(p.id)?.second?.let { showStartup(it) } }
        }
    }
    /** Its network adapters in full, the one with the default route first. */
    val network = MutableStateFlow<List<NetAdapter>?>(null)
    suspend fun refreshNetwork() = withContext(Dispatchers.IO) { val p = pc()?.takeIf { rev11(it) } ?: return@withContext; val (s, v) = link?.network(p.id) ?: return@withContext; note(Proto.CMD_NETWORK, s); v?.let { network.value = it } }
    /** Windows Update as the PC last found it (it checks on its own; a check from here can take a minute). */
    val updates = MutableStateFlow<Updates?>(null)
    suspend fun refreshUpdates() = withContext(Dispatchers.IO) { val p = pc()?.takeIf { rev11(it) } ?: return@withContext; val (s, v) = link?.updates(p.id) ?: return@withContext; note(Proto.CMD_UPDATES, s); v?.let { updates.value = it } }
    fun checkUpdates() { val p = pc() ?: return; updates.update { it?.let { u -> Updates(4, u.lastChecked, u.lastInstalled, u.items) } }
        scope.launch { val v = link?.updates(p.id, 1)?.second; if (v != null) updates.value = v else banners.tryEmit(Banner(Banner.Kind.Failed, "${p.name} couldn't check")) } }
    fun openUpdates() { val p = pc() ?: return
        scope.launch { banners.tryEmit(if (link?.updates(p.id, 2)?.second != null) Banner(Banner.Kind.Info, "Windows Update is open", "On ${p.name}") else Banner(Banner.Kind.Failed, "${p.name} couldn't open it")) } }
    /** The apps of the PC's Start menu, by name (the same list its island's command bar searches). */
    val pcApps = MutableStateFlow<List<PcApp>>(emptyList())
    private fun showPcApps(list: List<io.github.arnavdugad.arnavisland.link.StartMenuApp>) {
        // Icons come once (they're asked for only while none are here); a list without them keeps the ones seen.
        val before = pcApps.value.associateBy { it.id }
        pcApps.value = list.map { a -> PcApp(a.id, a.name, a.icon?.let(::decode) ?: before[a.id]?.icon) }
    }
    suspend fun refreshPcApps(icons: Boolean = true) = withContext(Dispatchers.IO) {
        val p = pc()?.takeIf { rev11(it) } ?: return@withContext; val l = link ?: return@withContext
        val want = icons && pcApps.value.none { it.icon != null }
        var (s, v) = l.startMenu(p.id, want)
        // Every icon at once may be more than one answer carries: then the names alone.
        if (v == null && want && s != Proto.UNSUPPORTED) { val again = l.startMenu(p.id, false); s = again.first; v = again.second }
        note(Proto.CMD_APPS, s); v?.let { showPcApps(it) }
    }
    /** Opens an app on the PC (brought to the front when it's already running): true when the PC did. */
    suspend fun openPcApp(id: String): Boolean = withContext(Dispatchers.IO) {
        val p = pc() ?: return@withContext false; val name = pcApps.value.firstOrNull { it.id == id }?.name ?: "it"
        if (!rev11(p)) { banners.tryEmit(Banner(Banner.Kind.Failed, "Update Arnav Island on ${p.name}", "Opening apps needs version 0.28 or later")); return@withContext false }
        val ok = link?.openApp(p.id, id) == Proto.OK
        banners.tryEmit(if (ok) Banner(Banner.Kind.Info, "Opening $name", "On ${p.name}") else Banner(Banner.Kind.Failed, "${p.name} couldn't open $name"))
        ok
    }
    private fun decode(png: ByteArray?): androidx.compose.ui.graphics.ImageBitmap? = png?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }

    // ---- 1.9: the Island tab's sections, in your order, the pinned ones first ----
    val sectionOrder = MutableStateFlow(prefsList("islandOrder")); val sectionPins = MutableStateFlow(prefsList("islandPins").toSet())
    private fun prefsList(key: String): List<String> = runCatching { prefs.getString(key, "")!!.split(',').filter { it.isNotBlank() } }.getOrDefault(emptyList())
    fun setSections(order: List<String>, pins: Set<String>) { sectionOrder.value = order; sectionPins.value = pins; prefs.edit().putString("islandOrder", order.joinToString(",")).putString("islandPins", pins.joinToString(",")).apply() }

    // ---- 1.8: the phone's volume keys, for the PC's sound ----
    /** One step (5%) of the PC's volume up or down, shown at once and then on the PC. */
    fun volumeStep(up: Boolean) {
        val s = status.value ?: return
        val v = (s.volume + if (up) 5 else -5).coerceIn(0, 100); status.value = s.copy(volume = v, muted = false)
        scope.launch { command(Proto.CMD_VOLUME, byteArrayOf(v.toByte()), quiet = true) }
    }

    // ---- 1.4: this phone's hotspot on the PC's island ----
    /** Its name and password (typed once in Devices: Android doesn't tell apps) and whether the PCs hear about it. */
    data class HotspotSetup(val name: String, val password: String, val enabled: Boolean)
    val hotspot by lazy { MutableStateFlow(HotspotSetup(prefs.getString("hotspotName", "").orEmpty(), prefs.getString("hotspotPassword", "").orEmpty(), prefs.getBoolean("hotspot", false))) }
    val hotspotOn = MutableStateFlow(false)
    /** What the PCs last heard: the name and password while on, "" once off (null: nothing yet). */
    @Volatile private var sentHotspot: String? = null
    const val HOTSPOT_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    fun setHotspot(name: String? = null, password: String? = null, enabled: Boolean? = null) {
        val now = hotspot.value.let { HotspotSetup(name ?: it.name, password ?: it.password, enabled ?: it.enabled) }
        prefs.edit().putString("hotspotName", now.name).putString("hotspotPassword", now.password).putBoolean("hotspot", now.enabled).apply()
        hotspot.value = now; sendHotspot()
    }
    /** Android's word on the hotspot (its broadcast, which it keeps for late listeners; else WifiManager's own getter). */
    fun hotspotChanged(intent: Intent?) {
        val state = (intent ?: runCatching { app.registerReceiver(null, IntentFilter(HOTSPOT_CHANGED)) }.getOrNull())?.getIntExtra("wifi_state", -1) ?: -1
        hotspotOn.value = if (state >= 0) state == 13 else runCatching {
            android.net.wifi.WifiManager::class.java.getMethod("isWifiApEnabled").invoke(app.getSystemService(android.net.wifi.WifiManager::class.java)) as Boolean
        }.getOrDefault(false)
        sendHotspot()
    }
    /** Tells the PCs (island 0.22 or later) when it changed; [arrived]: a PC just came, so it hears the hotspot if it's on. */
    fun sendHotspot(arrived: Boolean = false) {
        val setup = hotspot.value; val on = hotspotOn.value && setup.enabled && setup.name.isNotEmpty()
        val said = if (on) setup.name + "\u0000" + setup.password else ""
        if (arrived) { if (!on) return } else if (said == (sentHotspot ?: "")) return
        val l = link ?: return
        val targets = peers.value.filter { it.paired && it.online && it.revision >= 5 && !it.phone }; if (targets.isEmpty()) return
        sentHotspot = said
        val frame = Link.hotspotFrame(on, setup.name, setup.password)
        scope.launch { targets.forEach { l.notice(it.id, frame) } }
    }

    // ---- the whole island (revision 5, island 0.22) ----
    val pcStats = MutableStateFlow<PcStats?>(null)
    val pcControls = MutableStateFlow<PcControls?>(null)
    /** When [pcControls] was read (the focus clock runs on from there here). */
    @Volatile var controlsAt = 0L; private set
    val islandSettings = MutableStateFlow<IslandSettings?>(null)
    val outputs = MutableStateFlow<List<AudioOutput>>(emptyList())
    /** Whether the chosen PC can be controlled whole (island 0.22 or later) right now. */
    fun islandReady(p: PeerView? = pc()) = p != null && p.online && p.revision >= 5
    /** The PC's controls (and, while its numbers show, its stats): asked once a second while the Island screen shows. */
    suspend fun refreshIsland(withStats: Boolean) = withContext(Dispatchers.IO) {
        val l = link ?: return@withContext; val p = pc()?.takeIf { islandReady(it) } ?: return@withContext
        if (withStats) l.stats(p.id)?.let { read -> val s = withCores(p, read)
            // A new PC starts its trail afresh; otherwise each poll adds a point (five minutes of them, for scrubbing).
            if (trailPc != p.id) { trailPc = p.id; statsTrail.value = emptyList() }
            pcStats.value = s; statsCache[p.id] = s; val now = System.currentTimeMillis(); Alerts.check(app, p, s)
            val point = StatsPoint(now, s.cpu.toFloat(), s.gpu.toFloat(), s.download.toFloat(), s.upload.toFloat())
            statsTrail.value = (statsTrail.value + point).takeLast(300); keepHour(p, point)
        }
        l.controls(p.id)?.let { pcControls.value = it; controlsAt = System.currentTimeMillis() }
        // 1.5: its battery, every third time (island 0.23).
        if (withStats && p.revision >= 6 && batteryTick++ % 3 == 0) l.battery(p.id)?.let { pcBattery.value = it }
    }
    /** 1.5: the PC's battery in full (island 0.23), and the numbers seen while the Island tab showed, for scrubbing its graphs. */
    val pcBattery = MutableStateFlow<PcBattery?>(null)
    data class StatsPoint(val at: Long, val cpu: Float, val gpu: Float, val download: Float, val upload: Float)
    val statsTrail = MutableStateFlow<List<StatsPoint>>(emptyList())
    @Volatile private var trailPc: String? = null; private var batteryTick = 0
    /** Forgets what was shown for the last PC (another one was chosen). */
    fun clearIsland() {
        // The numbers stay until the next PC's arrive, so the rings move from one PC's to the other's.
        pcBattery.value = null; islandSettings.value = null; outputs.value = emptyList(); statsTrail.value = emptyList(); trailPc = null; batteryTick = 0
        // 1.11: what island 0.28 said is that PC's own.
        batteryHealth.value = null; storage.value = null; startup.value = null; startupIcons.value = emptyMap(); network.value = null; updates.value = null; pcApps.value = emptyList()
        heatTrail.value = emptyList(); heatPc = null
    }

    // ---- 1.5: what a PC asks this phone (revision 6) ----
    /** The focus clock as a PC last told it (its lock-screen notification and widget follow it); kept across restarts. */
    val focus by lazy { MutableStateFlow(loadFocus()) }
    private fun loadFocus(): FocusState? = runCatching {
        val p = prefs.getString("focus", null)?.split('\u0001') ?: return null
        FocusState(p[0].toInt(), p[1] == "1", p[2] == "1", p[3].toDouble(), p[4].toDouble(), p[5], p[6].toLong())
            // A countdown that has run out while this phone wasn't told is over.
            .takeIf { f -> !f.running || f.mode == 2 || FocusLive.end(f) > System.currentTimeMillis() }
    }.getOrNull()
    private fun saveFocus(f: FocusState) { prefs.edit().putString("focus", listOf(f.mode, if (f.running) 1 else 0, if (f.finished) 1 else 0, f.shown, f.duration, f.pcName.replace('\u0001', ' '), f.at).joinToString("\u0001")).apply() }
    private fun answer(peer: String, command: Int, payload: ByteArray): ByteArray? = when (command) {
        Proto.QUERY_READINGS -> {
            val text = DeviceInfo.live(app).joinToString("\n") { (k, v) -> k.replace('\t', ' ').replace('\n', ' ') + "\t" + v.replace('\t', ' ').replace('\n', ' ') }.take(12_000).toByteArray()
            val cover = DeviceInfo.cover(app) ?: ByteArray(0)
            Bytes().u32(text.size).raw(text).u32(cover.size).raw(cover).build()
        }
        Proto.QUERY_FOCUS -> IslandWire.focus(payload)?.let { f -> focus.value = f; saveFocus(f); FocusLive.show(app, f); ByteArray(0) }
        // 1.7: a photo just taken, sent over (only one this phone told that PC about, a few minutes ago at most).
        Proto.QUERY_PHOTO -> {
            val r = Reader(payload); val id = r.u64(); val purpose = r.u8() ?: 1; val ask = (r.u32() ?: 0L).toInt()
            val uri = id?.let { RecentPhotos.uriOf(app, peer, it) }
            if (uri == null) null else { send(peer, listOf(uri), toShelf = purpose == 2, ask = ask); ByteArray(0) }
        }
        // 1.7: a web page from the PC, where it was scrolled to.
        Proto.QUERY_PAGE -> {
            val r = Reader(payload); val scroll = java.lang.Float.intBitsToFloat((r.u32() ?: 0L).toInt()); val url = r.string(4096); val title = r.string(400).orEmpty()
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) null
            else { Pages.arrived(app, peers.value.firstOrNull { it.id == peer }?.name ?: "your PC", url, title, scroll); ByteArray(0) }
        }
        else -> null
    }
    /** Changes a control at once here ([seen]: what it looks like meanwhile), then on the PC; what the PC says wins. */
    fun setControl(control: Int, value: Int, seen: (PcControls) -> PcControls = { it }) {
        pcControls.value = pcControls.value?.let(seen)
        scope.launch {
            val l = link ?: return@launch; val p = pc()?.takeIf { islandReady(it) } ?: return@launch
            val c = l.setControl(p.id, control, value)
            if (c != null) { pcControls.value = c; controlsAt = System.currentTimeMillis() }
            else { banners.tryEmit(Banner(Banner.Kind.Failed, "${p.name} couldn't do that", l.lastRemoteError.ifEmpty { "Try again in a moment" })); l.controls(p.id)?.let { pcControls.value = it } }
        }
    }
    /** Not every reading carries each core's: the last ones carry on, so their box doesn't leave and come back. */
    private fun withCores(p: PeerView, read: PcStats): PcStats { val before = statsCache[p.id]; return if (read.cores.isEmpty() && !before?.cores.isNullOrEmpty()) read.copy(cores = before!!.cores) else read }
    /** 1.5: just the PC's numbers (for the stats widget while the app isn't on screen); [sensors] (1.11): its temperatures too, for the performance widget. */
    suspend fun refreshStats(sensors: Boolean = false) = withContext(Dispatchers.IO) {
        val l = link ?: return@withContext; val p = pc()?.takeIf { islandReady(it) } ?: return@withContext
        l.stats(p.id)?.let { read -> val s = withCores(p, read); pcStats.value = s; statsCache[p.id] = s; Alerts.check(app, p, s) }
        if (rev10(p) && (sensors || Alerts.on("cpuheat") || Alerts.on("gpuheat"))) refreshSensors()
    }
    /** 1.5: each PC's numbers as last read (the Island tab's carousel shows its neighbours with them). */
    private val statsCache = java.util.concurrent.ConcurrentHashMap<String, PcStats>()
    fun cachedStats(peer: String): PcStats? = statsCache[peer]
    /** 1.5: a control changed from a widget or a notification: true when the PC did it. */
    suspend fun setControlNow(control: Int, value: Int): Boolean = withContext(Dispatchers.IO) {
        val l = link ?: return@withContext false; val p = pc()?.takeIf { islandReady(it) } ?: return@withContext false
        l.setControl(p.id, control, value)?.also { pcControls.value = it; controlsAt = System.currentTimeMillis() } != null
    }
    suspend fun loadIslandSettings(): IslandSettings? = withContext(Dispatchers.IO) {
        val l = link ?: return@withContext null; val p = pc()?.takeIf { islandReady(it) } ?: return@withContext null
        l.islandSettings(p.id)?.also { islandSettings.value = it }
    }
    /** Changes one of the island's settings: shown at once, then set on the PC (which may keep it within its range). */
    fun setIslandSetting(key: String, value: Int) {
        fun show(v: Int) = islandSettings.update { s -> s?.copy(items = s.items.map { if (it.key == key) it.copy(value = v) else it }) }
        val before = islandSettings.value?.items?.firstOrNull { it.key == key }?.value; show(value)
        scope.launch {
            val l = link ?: return@launch; val p = pc()?.takeIf { islandReady(it) } ?: return@launch
            val now = l.setIslandSetting(p.id, key, value)
            if (now != null) show(now) else { if (before != null) show(before); banners.tryEmit(Banner(Banner.Kind.Failed, "${p.name} couldn't change that", l.lastRemoteError.ifEmpty { "Try again in a moment" })) }
        }
    }
    fun islandSettingAction(action: Int, done: String) {
        scope.launch {
            val l = link ?: return@launch; val p = pc()?.takeIf { islandReady(it) } ?: return@launch
            banners.tryEmit(if (l.islandSettingAction(p.id, action)) Banner(Banner.Kind.Info, done, "On ${p.name}") else Banner(Banner.Kind.Failed, "${p.name} couldn't do that"))
        }
    }
    /** The PC's command bar, asked again (briefly) until its results answer this text. */
    suspend fun queryCommands(text: String): CommandResults? = withContext(Dispatchers.IO) {
        val l = link ?: return@withContext null; val p = pc()?.takeIf { islandReady(it) } ?: return@withContext null
        var r = l.queryCommands(p.id, text); var tries = 0
        while (r != null && !r.final && tries++ < 12) { kotlinx.coroutines.delay(120); r = l.queryCommands(p.id, text) }
        r
    }
    suspend fun runCommand(text: String, index: Int, title: String, confirmed: Boolean): CommandOutcome? = withContext(Dispatchers.IO) {
        val l = link ?: return@withContext null; val p = pc()?.takeIf { islandReady(it) } ?: return@withContext null
        l.runCommand(p.id, text, index, title, confirmed)
    }
    suspend fun loadOutputs() = withContext(Dispatchers.IO) {
        val l = link ?: return@withContext; val p = pc()?.takeIf { islandReady(it) } ?: return@withContext
        l.outputs(p.id)?.let { outputs.value = it }
    }
    /** Makes an output the PC's default; when the island's direct switching is off, says how to turn it on. */
    fun selectOutput(id: String) {
        outputs.update { list -> list.map { it.copy(current = it.id == id) } }
        scope.launch {
            val l = link ?: return@launch; val p = pc()?.takeIf { islandReady(it) } ?: return@launch
            when (l.selectOutput(p.id, id)) {
                Proto.OK -> Unit
                Proto.NOT_ALLOWED -> banners.tryEmit(Banner(Banner.Kind.Info, "Direct output switching is off", "Turn it on below, in Media & sound"))
                else -> banners.tryEmit(Banner(Banner.Kind.Failed, "${p.name} couldn't switch the sound"))
            }
            kotlinx.coroutines.delay(600); l.outputs(p.id)?.let { outputs.value = it }
        }
    }
    fun openIslandPage(page: Int) { scope.launch { val l = link ?: return@launch; val p = pc()?.takeIf { islandReady(it) } ?: return@launch
        if (!l.openIslandPage(p.id, page)) banners.tryEmit(Banner(Banner.Kind.Failed, "${p.name} couldn't open that")) } }
    fun closeIsland() { scope.launch { val l = link ?: return@launch; val p = pc()?.takeIf { islandReady(it) } ?: return@launch; l.closeIsland(p.id) } }

    // ---- lyrics, find my PC ----
    private var lyricsKey: String? = null; private var lyricsAsked = 0L; private var lyricsTries = 0
    /** The lyrics follow the song: asked when it changes, and again (a few times) while the PC is still looking. */
    private fun lyricsFor(p: PeerView, s: PcStatus) {
        if (!s.available || p.revision < 3) { lyrics.value = null; lyricsKey = null; return }
        val key = s.title + "\t" + s.artist; val now = System.currentTimeMillis()
        val current = lyrics.value
        val again = key == lyricsKey && current?.state == 1 && now - lyricsAsked > 2500 && lyricsTries < 12
        if (key == lyricsKey && !again) return
        if (key != lyricsKey) { lyricsKey = key; lyricsTries = 0; if (current?.key != key) lyrics.value = null }
        lyricsAsked = now; lyricsTries++
        scope.launch {
            val reply = command(Proto.CMD_LYRICS, quiet = true) ?: return@launch
            if (!reply.ok) return@launch
            val l = Link.parseLyrics(reply.payload) ?: return@launch
            if (lyricsKey == key) lyrics.value = l.copy(key = key)
        }
    }
    /** Rings the chosen PC (its island chimes and says "Here I am"). */
    suspend fun ringPc(): Boolean {
        val p = pc() ?: return false
        if (p.online && p.revision < 3) { banners.tryEmit(Banner(Banner.Kind.Failed, "Update Arnav Island on ${p.name}", "Find my PC needs version 0.20 or later")); return false }
        val ok = command(Proto.CMD_RING_PC)?.ok == true
        if (ok) banners.tryEmit(Banner(Banner.Kind.Ring, "Ringing ${p.name}"))
        return ok
    }

    // ---- the camera, for a PC's Shelf ----
    /** The PC that asked for a photo (else the chosen one). */
    @Volatile var photoFor: String? = null
    /** A photo just taken for a PC: onto its Shelf. */
    fun sendPhoto(uri: Uri) {
        val target = photoFor?.let { id -> peers.value.firstOrNull { it.id == id && it.paired } } ?: pc()
        photoFor = null; Notify.cancel(app, Notify.PHOTO)
        if (target == null) { banners.tryEmit(Banner(Banner.Kind.Failed, "No PC yet", "Pair with your PC first")); return }
        send(target.id, listOf(uri), toShelf = target.revision >= 3)
        banners.tryEmit(Banner(Banner.Kind.Photo, "On its way to ${target.name}"))
    }

    // ---- the universal clipboard ----
    /** Called while the app has the focus: a new copy on this phone goes to the chosen PC when its island's universal clipboard is on. */
    fun clipboardOut(context: Context) {
        if (!Clip.enabled()) return
        val p = pc() ?: return; if (!p.online) return
        scope.launch {
            // The PC's status says whether its universal clipboard is on; it may be on its way.
            var s: PcStatus? = status.value
            for (i in 0 until 30) { if (s != null) break; kotlinx.coroutines.delay(100); s = status.value }
            if (s?.clipboard != true) return@launch
            val text = withContext(Dispatchers.Main) { Clip.newCopy(context) } ?: return@launch
            rememberClip(text, "This phone")
            if (command(Proto.CMD_CLIP_SET, text.toByteArray(), quiet = true)?.ok == true)
                banners.tryEmit(Banner(Banner.Kind.Clipboard, "On ${p.name}’s clipboard", text.lineSequence().first().take(60)))
        }
    }

    // ---- this phone for the island ----
    private var sentBattery = -2; private var sentCharging = false
    /** The battery level (and whether it charges) to every paired PC that is here, when it changed. */
    fun sendBattery(force: Boolean = false) {
        val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        // The forecast's history is kept whether or not the PCs hear the battery.
        BatteryForecast.record(app, percent, charging)
        if (!prefs.getBoolean("battery", true)) return
        if (!force && percent == sentBattery && charging == sentCharging) return
        sentBattery = percent; sentCharging = charging
        val l = link ?: return; val frame = Link.statusFrame(percent, charging)
        val targets = peers.value.filter { it.paired && it.online && it.remote }
        scope.launch { targets.forEach { l.notice(it.id, frame) } }
        sendDetails()
    }

    @Volatile private var sentDetails: List<Pair<String, String>>? = null; @Volatile private var detailsAt = 0L
    /**
     * This phone's readings (battery, storage, memory, network, sound...) to every paired island that shows them (0.20),
     * when they changed, at least every five minutes while the phone is here, and at once when a PC arrives.
     */
    fun sendDetails(force: Boolean = false) {
        if (!prefs.getBoolean("details", true)) return
        val l = link ?: return
        val targets = peers.value.filter { it.paired && it.online && it.revision >= 3 && !it.phone }; if (targets.isEmpty()) return
        scope.launch {
            val d = DeviceInfo.read(app); val compared = d.filter { it.first != "Uptime" }; val now = System.currentTimeMillis()
            synchronized(this@Hub) {
                if (!force && compared == sentDetails && now - detailsAt < 300_000) return@launch
                sentDetails = compared; detailsAt = now
            }
            val frame = Link.detailsFrame(d)
            targets.forEach { l.notice(it.id, frame) }
        }
    }

    fun handBack(music: Handoff, peer: String) { scope.launch { val l = link ?: return@launch; val code = l.handoff(peer, music)
        banners.tryEmit(if (code == null) Banner(Banner.Kind.Failed, "Couldn't reach the PC") else if (code == 0) Banner(Banner.Kind.Info, "The PC said not now") else Banner(Banner.Kind.Music, "Playing on your PC", music.title)) } }

    fun sizeText(bytes: Long): String = when {
        bytes < 1024 -> "$bytes bytes"
        bytes < 1024 * 1024 -> "${(bytes / 1024.0).let { "%.0f".format(it) }} KB"
        bytes < 1L shl 30 -> "${"%.1f".format(bytes / 1048576.0)} MB"
        else -> "${"%.2f".format(bytes / 1073741824.0)} GB"
    }
}
