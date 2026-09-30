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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A transfer under way, either way. rate: bytes a second, smoothed. */
data class Transfer(val id: Int, val peer: String, val name: String, val title: String, val done: Long, val total: Long, val outgoing: Boolean,
                    val rate: Double = 0.0, val sampledAt: Long = 0, val sampledDone: Long = 0)

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
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
    }

    fun phoneName(): String = runCatching { Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

    @Volatile private var starting: Link? = null
    @Synchronized fun start(): Link? {
        link?.let { return it }
        // Over the internet too (through the relay, end-to-end encrypted) unless that is turned off.
        val l = Link(PhoneStore(app), phoneName(), DownloadsInbox(app), ::onEvent, Link.Options(relay = prefs.getBoolean("internet", true)))
        l.onAction = { key, index, reply -> NoticeActions.perform(app, key, index, reply) }
        l.onQuery = { peer, command, payload -> if (peers.value.any { it.id == peer && it.paired }) answer(peer, command, payload) else null }
        l.onClipboard = { text, sensitive -> Clip.receive(app, text, sensitive).also { if (it && visible) banners.tryEmit(Banner(Banner.Kind.Clipboard, "Copied from your PC", text.lineSequence().first().take(60))) } }
        starting = l
        if (!l.start()) { starting = null; failure.value = l.failure ?: "The link couldn't start"; return null }
        link = l; starting = null; running.value = true; failure.value = null
        peers.value = l.peers(); pickDefault()
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
    fun choose(peer: String) { if (selected.value != peer) clearIsland(); selected.value = peer; prefs.edit().putString("pc", peer).apply(); status.value = null }

    private val lastOnline = HashSet<String>()
    private fun onEvent(e: LinkEvent) {
        when (e) {
            is LinkEvent.Peers -> {
                peers.value = e.peers; pickDefault(); internet.value = (link ?: starting)?.internet == true
                // A PC that just came online hears this phone's battery and details at once.
                val online = e.peers.filter { it.paired && it.online && it.remote }.map { it.id }.toSet()
                val arrived = synchronized(lastOnline) { val a = online - lastOnline; lastOnline.clear(); lastOnline.addAll(online); a }
                if (arrived.isNotEmpty()) { sendBattery(force = true); sendDetails(force = true); sendHotspot(arrived = true) }
            }
            is LinkEvent.PairCode -> { codePairing.value = null; pairCode.value = e; if (!visible) Notify.pairing(app, e) }
            is LinkEvent.Paired -> {
                codePairing.value = null; pairCode.value = null; pairResult.value = e; Notify.cancel(app, Notify.PAIR)
                if (e.ok) { if (pc()?.id == null || selected.value == null) choose(e.peer); banners.tryEmit(Banner(Banner.Kind.Paired, "Paired with ${e.name}", "Files, music and the remote are ready, on any network")); sendBattery(force = true); sendDetails(force = true) }
                else banners.tryEmit(Banner(Banner.Kind.Failed, if (e.name.isEmpty()) "Not paired" else "Not paired with ${e.name}", e.detail))
            }
            is LinkEvent.Offer -> {
                if (prefs.getBoolean("autoAccept", false)) { link?.answer(e.transfer, true); return }
                offers.update { it + e }; if (!visible) Notify.offer(app, e)
            }
            is LinkEvent.Progress -> transfers.update { map ->
                val now = System.currentTimeMillis(); val old = map[e.transfer]
                var t = old?.copy(done = e.done, total = e.total, title = e.title) ?: Transfer(e.transfer, e.peer, e.name, e.title, e.done, e.total, e.outgoing, sampledAt = now, sampledDone = e.done)
                if (old != null && now - old.sampledAt >= 300 && e.done >= old.sampledDone) {
                    val speed = (e.done - old.sampledDone) * 1000.0 / (now - old.sampledAt)
                    t = t.copy(rate = if (old.rate > 0) old.rate * .7 + speed * .3 else speed, sampledAt = now, sampledDone = e.done)
                }
                map + (e.transfer to t)
            }
            is LinkEvent.Received -> {
                transfers.update { it - e.transfer }; offers.update { list -> list.filterNot { it.transfer == e.transfer } }
                remember(Moment(if (e.taken) 2 else 0, e.title, e.name, e.count, e.size, System.currentTimeMillis(), e.shown.take(20)))
                banners.tryEmit(Banner(Banner.Kind.Received, if (e.taken) "Took ${e.title}" else "Received ${e.title}", "From ${e.name}  ·  ${sizeText(e.size)}"))
                Notify.received(app, e)
            }
            is LinkEvent.Sent -> {
                transfers.update { it - e.transfer }; outcomes.tryEmit(e.transfer to true)
                remember(Moment(1, e.title, e.name, e.count, e.size, System.currentTimeMillis(), emptyList()))
                banners.tryEmit(Banner(Banner.Kind.Sent, "Sent ${e.title}", "To ${e.name}  ·  ${sizeText(e.size)}"))
            }
            is LinkEvent.Failed -> {
                if (e.outgoing) outcomes.tryEmit(e.transfer to false)
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
        banners.tryEmit(if (ok) Banner(Banner.Kind.Music, "Continuing ${m.title}", if (chosen != null) "In ${m.app}" else "In your music app") else Banner(Banner.Kind.Failed, "No music app plays searches", "Install one that does, or play it on the PC"))
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

    /** Files from other apps (content URIs) to a PC, in one transfer; [toShelf]: onto its island's Shelf (a photo taken for it). */
    fun send(peer: String, uris: List<Uri>, toShelf: Boolean = false, ask: Int = 0, onStarted: (Int) -> Unit = {}) {
        if (uris.isEmpty()) return
        scope.launch {
            val l = link ?: run { banners.tryEmit(Banner(Banner.Kind.Failed, "Not connected", "Open Arnav Island and try again")); return@launch }
            val sources = uris.mapNotNull { sourceOf(it) }
            if (sources.isEmpty()) { banners.tryEmit(Banner(Banner.Kind.Failed, "Couldn't read those files")); return@launch }
            val names = sources.map { it.rel }
            val title = if (names.size == 1) names[0] else "${names[0]} and ${names.size - 1} more"
            val target = peers.value.firstOrNull { it.id == peer }
            // 1.7: a picture of the first one, for the island to show as it arrives.
            val preview = Previews.of(app, uris[0])
            val id = l.send(peer, sources, title, toShelf = toShelf, preview = preview, ask = ask)
            transfers.update { it + (id to Transfer(id, peer, target?.name ?: "your PC", title, 0, sources.sumOf { s -> s.size }, true, sampledAt = System.currentTimeMillis())) }
            onStarted(id)
        }
    }
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
        if (s == null) statusError.value = l.lastRemoteError ?: "${p.name} didn't answer" else { statusError.value = null; status.value = s; lyricsFor(p, s) }
        s
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
        if (withStats) l.stats(p.id)?.let { s ->
            // A new PC starts its trail afresh; otherwise each poll adds a point (five minutes of them, for scrubbing).
            if (trailPc != p.id) { trailPc = p.id; statsTrail.value = emptyList() }
            pcStats.value = s; statsCache[p.id] = s; val now = System.currentTimeMillis()
            statsTrail.value = (statsTrail.value + StatsPoint(now, s.cpu.toFloat(), s.gpu.toFloat(), s.download.toFloat(), s.upload.toFloat())).takeLast(300)
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
    /** 1.5: just the PC's numbers (for the stats widget while the app isn't on screen). */
    suspend fun refreshStats() = withContext(Dispatchers.IO) {
        val l = link ?: return@withContext; val p = pc()?.takeIf { islandReady(it) } ?: return@withContext
        l.stats(p.id)?.let { pcStats.value = it; statsCache[p.id] = it }
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
        if (ok) banners.tryEmit(Banner(Banner.Kind.Ring, "Ringing ${p.name}", "Its island chimes and lights up"))
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
        banners.tryEmit(Banner(Banner.Kind.Photo, "On its way to ${target.name}", if (target.revision >= 3) "It lands on the island’s Shelf" else "It goes to the PC’s Downloads"))
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
