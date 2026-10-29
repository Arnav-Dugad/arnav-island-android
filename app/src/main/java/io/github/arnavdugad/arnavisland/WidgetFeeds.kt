package io.github.arnavdugad.arnavisland

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import io.github.arnavdugad.arnavisland.link.PcStatus
import io.github.arnavdugad.arnavisland.link.PeerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 1.8: the lyrics widget: the line your PC's song is at, large, the next one dimmed, in time while the screen is on. */
class LyricsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = WidgetFeeds.changed(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = WidgetFeeds.changed(context)
    override fun onEnabled(context: Context) { Hub.init(context); LinkService.start(context) }
    override fun onDisabled(context: Context) = WidgetFeeds.changed(context)
}
/** 1.8: both batteries: this phone's and your PC's, side by side, with how long each lasts. */
class BatteriesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = WidgetFeeds.changed(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = WidgetFeeds.changed(context)
    override fun onEnabled(context: Context) { Hub.init(context); LinkService.start(context) }
    override fun onDisabled(context: Context) = WidgetFeeds.changed(context)
}
/** 1.8: the transfer in flight, either way, with its speed and time left; the last one that finished when none is. */
class TransferWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = WidgetFeeds.changed(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = WidgetFeeds.changed(context)
    override fun onEnabled(context: Context) { Hub.init(context); LinkService.start(context) }
    override fun onDisabled(context: Context) = WidgetFeeds.changed(context)
}

/**
 * 1.8: keeps the lyrics, batteries and transfer widgets up to date. It follows the app's flows only while such a widget
 * is on a home screen, redraws a widget only when what it shows changed, and asks the PC itself only when nothing else
 * does (the service already asks for the PC's player and the now-playing widget).
 * 1.9: and the controls and mixer widgets, asked for only while the screen is on.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
object WidgetFeeds {
    private data class Present(val lyrics: Boolean = false, val batteries: Boolean = false, val transfer: Boolean = false, val controls: Boolean = false, val mixer: Boolean = false) {
        val any get() = lyrics || batteries || transfer
        /** 1.9: a widget that follows the screen going on and off. */
        val timed get() = lyrics || controls || mixer
    }
    private val present = MutableStateFlow(Present())
    /** This phone's battery (level, charging), heard while the batteries widget is out. */
    private val phone = MutableStateFlow(-1 to false)
    /** 1.9: whether the screen is on, heard while a widget that follows it is out. */
    private val screenOn = MutableStateFlow(true)
    @Volatile private var started = false

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val raw = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100); val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            phone.value = (if (raw >= 0 && scale > 0) raw * 100 / scale else -1) to (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        }
    }
    /** The lyrics keep time only while the screen is on (1.9: and the controls and mixer are asked for only then). */
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val on = i.action != Intent.ACTION_SCREEN_OFF; screenOn.value = on
            if (!on) LyricsWidgets.stop() else Hub.scope.launch { LyricsWidgets.refresh(c.applicationContext) }
        }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(this) { if (started) return; started = true }
        Hub.init(app); look(app)
        // The receivers, only while their widgets are out.
        Hub.scope.launch {
            present.map { it.batteries }.distinctUntilChanged().collect { on -> withContext(Dispatchers.Main) { if (on) listen(app, battery, Intent.ACTION_BATTERY_CHANGED) else quiet(app, battery) } }
        }
        Hub.scope.launch {
            present.map { it.timed }.distinctUntilChanged().collect { on -> withContext(Dispatchers.Main) {
                if (on) { screenOn.value = lit(app); listen(app, screen, Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF) } else { quiet(app, screen); LyricsWidgets.stop(); screenOn.value = true }
            } }
        }
        // Each widget's flows, debounced (a status read changes several at once).
        Hub.scope.launch {
            present.map { it.lyrics }.distinctUntilChanged()
                .flatMapLatest { on -> if (on) combine(Hub.status, Hub.lyrics, Hub.peers, Hub.selected) { _, _, _, _ -> }.debounce(150) else emptyFlow() }
                .collect { LyricsWidgets.refresh(app) }
        }
        Hub.scope.launch {
            present.map { it.batteries }.distinctUntilChanged()
                .flatMapLatest { on -> if (on) combine(listOf<Flow<Any?>>(Hub.status, Hub.pcBattery, Hub.pcStats, Hub.peers, Hub.selected, phone)) { }.debounce(250) else emptyFlow() }
                .collect { BatteryWidgets.refresh(app) }
        }
        // Transfers move many times a second: at most two redraws a second.
        Hub.scope.launch {
            present.map { it.transfer }.distinctUntilChanged()
                .flatMapLatest { on -> if (on) combine(Hub.transfers, Hub.moments, Hub.status, Hub.peers) { _, _, _, _ -> } else emptyFlow() }
                .conflate().collect { TransferWidgets.refresh(app); delay(500) }
        }
        // 1.9: the controls and the mixer as the PC's answers come (a tap redraws them itself, at once).
        Hub.scope.launch {
            present.map { it.controls }.distinctUntilChanged()
                .flatMapLatest { on -> if (on) combine(Hub.pcControls, Hub.status, Hub.peers, Hub.selected) { _, _, _, _ -> }.debounce(100) else emptyFlow() }
                .collect { ControlsWidgets.refresh(app) }
        }
        Hub.scope.launch {
            present.map { it.mixer }.distinctUntilChanged()
                .flatMapLatest { on -> if (on) combine(listOf<Flow<Any?>>(Hub.mixer, Hub.mixerIcons, Hub.pcControls, Hub.status, Hub.peers, Hub.selected)) { }.debounce(150) else emptyFlow() }
                .collect { MixerWidgets.refresh(app) }
        }
        // 1.9: and asks the PC for them: its mixer every 3 s and its controls every 6 (15 with only the controls out),
        // only while the screen is on and the app isn't in front (the home screen isn't showing then).
        Hub.scope.launch {
            combine(present.map { it.controls to it.mixer }.distinctUntilChanged(), screenOn) { out, on -> out to on }.collectLatest { (out, on) ->
                val (controls, mixer) = out
                if (!on || !(controls || mixer)) return@collectLatest
                var controlsAt = 0L
                while (currentCoroutineContext().isActive) {
                    val pc = Hub.pc()
                    if (!Hub.visible && Hub.link != null && pc != null && pc.online) {
                        val now = System.currentTimeMillis()
                        if (Hub.islandReady(pc) && now - controlsAt >= (if (mixer) 6_000L else 15_000L)) { controlsAt = now; Hub.refreshIsland(false) }
                        if (mixer && pc.revision >= 9) Hub.refreshMixer()
                    }
                    delay(if (mixer) 3_000L else 15_000L)
                }
            }
        }
        // The PC's status and battery while the screen is on (when nothing else asks), and "2 min ago" moving on.
        Hub.scope.launch {
            present.collectLatest { p ->
                if (!p.any) return@collectLatest
                val power = app.getSystemService(PowerManager::class.java); var statsAt = 0L
                while (currentCoroutineContext().isActive) {
                    val lit = power?.isInteractive != false; var wait = if (lit) 15_000L else 60_000L
                    val pc = Hub.pc()
                    if (lit && !Hub.visible && Hub.link != null && pc != null && pc.online && pc.remote) {
                        if ((p.lyrics || p.batteries) && !PcMedia.enabled() && !PcWidgets.any(app)) {
                            val s = Hub.refreshStatus()
                            if (p.lyrics && s?.available == true && s.playing) wait = if (pc.internet) 4_000L else 2_500L
                        }
                        // The minutes its battery has left, now and then (the stats widget already asks every 5 s).
                        if (p.batteries && Hub.islandReady(pc) && StatsWidgets.ids(app).isEmpty() && System.currentTimeMillis() - statsAt > 60_000) { statsAt = System.currentTimeMillis(); Hub.refreshStats() }
                    }
                    if (lit && p.transfer) TransferWidgets.refresh(app)
                    delay(wait)
                }
            }
        }
    }

    /** A widget was added, removed or resized: follows what's out now, and redraws them all. */
    fun changed(context: Context) { Hub.init(context); start(context); look(context.applicationContext); refresh(context, force = true) }
    fun refresh(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        LyricsWidgets.refresh(app, force); BatteryWidgets.refresh(app, force); TransferWidgets.refresh(app, force)
        ControlsWidgets.refresh(app, force); MixerWidgets.refresh(app, force)
    }
    private fun look(app: Context) {
        present.value = Present(idsOf(app, LyricsWidget::class.java).isNotEmpty(), idsOf(app, BatteriesWidget::class.java).isNotEmpty(), idsOf(app, TransferWidget::class.java).isNotEmpty(),
            idsOf(app, ControlsWidget::class.java).isNotEmpty(), idsOf(app, MixerWidget::class.java).isNotEmpty())
    }
    private fun listen(app: Context, r: BroadcastReceiver, vararg actions: String) {
        runCatching { ContextCompat.registerReceiver(app, r, IntentFilter().apply { actions.forEach { addAction(it) } }, ContextCompat.RECEIVER_NOT_EXPORTED) }
    }
    private fun quiet(app: Context, r: BroadcastReceiver) { runCatching { app.unregisterReceiver(r) } }

    // ---- what the three share ----
    internal fun idsOf(context: Context, provider: Class<*>): IntArray =
        runCatching { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, provider)) }.getOrDefault(IntArray(0))
    internal fun lit(context: Context) = context.getSystemService(PowerManager::class.java)?.isInteractive != false

    private var tintKey = 0; private var tint: ArtColors? = null
    /** The cover's colours for the glass; null for the app's own glass (Material You, nothing playing, the PC away). */
    @Synchronized internal fun tint(context: Context, pc: PeerView?, s: PcStatus?): ArtColors? {
        if (WidgetStyle.you(context) || pc?.online != true || s?.available != true) return null
        val hash = s.coverHash?.contentHashCode() ?: 0
        if (hash != tintKey) { tintKey = hash; tint = s.cover?.let { Art.bitmap(it, 128) }?.let { Art.colors(it) } }
        return tint
    }
    private val glasses = LinkedHashMap<String, Bitmap>()
    /** The glass for a widget at its size, kept for the next redraw (the lyrics widget changes line every few seconds). */
    @Synchronized internal fun glass(context: Context, m: AppWidgetManager, id: Int, tint: ArtColors?, tallDp: Int, wideDp: Int): Bitmap? {
        tint ?: return null
        val (w, h) = PcWidgets.sizeOf(m, id, tallDp, wideDp)
        val key = "${tint.hashCode()}|${w.roundToInt()}|${h.roundToInt()}"
        return glasses.getOrPut(key) { if (glasses.size >= 6) glasses.remove(glasses.keys.first()); PcWidgets.glass(context, w, h, tint) }
    }
    /** The glass behind a widget, and its small heading in the cover's colour ([where]: the headings' ids). */
    internal fun paint(v: RemoteViews, bg: Bitmap?, tint: ArtColors?, you: Boolean, vararg where: Int) {
        if (you) return
        if (bg != null) v.setImageViewBitmap(R.id.w_bg, bg) else v.setImageViewResource(R.id.w_bg, R.drawable.widget_glass)
        val color = tint?.let { PcWidgets.alpha(it.vivid, .85f) } ?: 0x80FFFFFF.toInt()
        where.forEach { v.setTextColor(it, color) }
    }
    /**
     * Its views at both sizes: Android 12 and later picks the one that fits as it's resized; before, the one that fits
     * its size now ([roomyFirst]: until Android says the size).
     */
    internal fun sized(m: AppWidgetManager, id: Int, compact: SizeF, roomy: SizeF, roomyFirst: Boolean, build: (roomy: Boolean) -> RemoteViews): RemoteViews {
        if (Build.VERSION.SDK_INT >= 31) return RemoteViews(mapOf(compact to build(false), roomy to build(true)))
        val o = m.getAppWidgetOptions(id); val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH); val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        return build(if (w <= 0 || h <= 0) roomyFirst else w >= roomy.width && h >= roomy.height)
    }
}

/** The lyrics widget's drawing, and its clock: it redraws at the next line's time while the song plays and the screen is on. */
object LyricsWidgets {
    private val COMPACT = SizeF(110f, 40f); private val ROOMY = SizeF(200f, 100f)
    private var drawn = ""
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var app: Context? = null
    private val tick = Runnable { app?.let { a -> Hub.scope.launch { refresh(a) } } }
    fun stop() = main.removeCallbacks(tick)

    @Synchronized fun refresh(context: Context, force: Boolean = false) {
        val app = context.applicationContext; this.app = app; main.removeCallbacks(tick)
        val ids = WidgetFeeds.idsOf(app, LyricsWidget::class.java); if (ids.isEmpty()) return
        val m = AppWidgetManager.getInstance(app)
        val pc = Hub.pc(); val s = if (pc != null) Hub.status.value else null; val you = WidgetStyle.you(app)
        val playing = pc?.online == true && s?.available == true
        val l = Hub.lyrics.value?.takeIf { playing && it.key == s!!.title + "\t" + s.artist }
        val lines = l?.lines?.takeIf { l.state == 2 && synced(it) }
        // A tenth of a second early, as the app lights its lines.
        val pos = (s?.positionNow() ?: 0.0) + .1
        val at = if (lines != null) lineAt(lines, pos) else -1
        val (line, next) = when {
            pc == null -> "Pair with your PC" to "Open Arnav Island"
            !pc.online -> "${pc.name} is away" to ""
            !playing -> "Nothing playing" to "Play something on ${pc.name}"
            lines != null -> (if (at < 0) "♪" else lines[at].text.ifBlank { "♪" }) to lines.getOrNull(at + 1)?.text.orEmpty()
            l?.state == 1 || (l == null && pc.revision >= 3) -> "♪" to "Finding the lyrics…"
            l?.state == 2 && l.lines.isNotEmpty() -> "No synced lyrics" to ""
            else -> "No lyrics" to ""
        }
        val song = if (playing) listOf(s!!.title, s.artist).filter { it.isNotBlank() }.joinToString("  ·  ") else if (pc == null) "Arnav Island" else ""
        val where = if (pc == null) "LYRICS" else "LYRICS  ·  ${pc.name.uppercase()}"
        // The next line's time, while it plays and the screen is on.
        if (lines != null && s!!.playing && WidgetFeeds.lit(app)) lines.getOrNull(at + 1)?.let { n -> main.postDelayed(tick, ((n.time - pos) * 1000).toLong().coerceIn(120, 60_000) + 30) }
        val tint = WidgetFeeds.tint(app, pc, s)
        val look = listOf(pc?.id, pc?.name, pc?.online, line, next, song, where, tint?.hashCode(), you).joinToString("|")
        if (!force && look == drawn) return
        drawn = look
        for (id in ids) runCatching {
            val bg = WidgetFeeds.glass(app, m, id, tint, 110, 250)
            m.updateAppWidget(id, WidgetFeeds.sized(m, id, COMPACT, ROOMY, true) { roomy ->
                RemoteViews(app.packageName, if (you) (if (roomy) R.layout.widget_lyrics_you else R.layout.widget_lyrics_you_small) else if (roomy) R.layout.widget_lyrics else R.layout.widget_lyrics_small).apply {
                    setTextViewText(R.id.w_where, where); setTextViewText(R.id.w_line, line); setTextViewText(R.id.w_song, song)
                    setTextViewText(R.id.w_next, next); setViewVisibility(R.id.w_next, if (roomy && next.isNotEmpty()) View.VISIBLE else View.GONE)
                    setViewVisibility(R.id.w_song, if (song.isNotEmpty()) View.VISIBLE else View.GONE)
                    WidgetFeeds.paint(this, bg, tint, you, R.id.w_where)
                    setOnClickPendingIntent(R.id.w_open, WidgetStyle.open(app, 140, null))
                }
            })
        }
    }
}

/** The batteries widget's drawing: this phone's from Android, the PC's from what it last said (its status, battery or numbers). */
object BatteryWidgets {
    private val COMPACT = SizeF(110f, 40f); private val ROOMY = SizeF(170f, 100f)
    private var drawn = ""; private var forecastKey = ""; private var forecast = ""
    private data class Side(val name: String, val percent: Int, val charging: Boolean, val line: String)

    @Synchronized fun refresh(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val ids = WidgetFeeds.idsOf(app, BatteriesWidget::class.java); if (ids.isEmpty()) return
        val m = AppWidgetManager.getInstance(app); val you = WidgetStyle.you(app)
        val sticky = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val raw = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1; val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val level = if (raw >= 0 && scale > 0) raw * 100 / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        // The forecast ("lasts until 11 pm") when the level changes, and every five minutes as the clock moves on.
        val key = "$level|$charging|${System.currentTimeMillis() / 300_000}"
        if (key != forecastKey) { forecastKey = key; forecast = runCatching { BatteryForecast.line(app) }.getOrNull()?.substringAfter("·", "")?.trim()?.replaceFirstChar { it.uppercase() }.orEmpty() }
        val phoneSide = Side("THIS PHONE", level, charging, forecast)
        val pc = Hub.pc(); val pcSide = pcSide(pc)
        val tint = WidgetFeeds.tint(app, pc, if (pc != null) Hub.status.value else null)
        val look = listOf(phoneSide, pcSide, tint?.hashCode(), you).joinToString("|")
        if (!force && look == drawn) return
        drawn = look
        for (id in ids) runCatching {
            val bg = WidgetFeeds.glass(app, m, id, tint, 110, 180)
            m.updateAppWidget(id, WidgetFeeds.sized(m, id, COMPACT, ROOMY, true) { roomy ->
                RemoteViews(app.packageName, if (you) (if (roomy) R.layout.widget_batteries_you else R.layout.widget_batteries_you_small) else if (roomy) R.layout.widget_batteries else R.layout.widget_batteries_small).apply {
                    side(this, phoneSide, R.id.w_phone_ring, R.id.w_phone_pct, R.id.w_phone_bolt, R.id.w_phone_name, R.id.w_phone_line, roomy, you)
                    side(this, pcSide, R.id.w_pc_ring, R.id.w_pc_pct, R.id.w_pc_bolt, R.id.w_pc_name, R.id.w_pc_line, roomy, you)
                    WidgetFeeds.paint(this, bg, tint, you, R.id.w_phone_name, R.id.w_pc_name)
                    setOnClickPendingIntent(R.id.w_open, WidgetStyle.open(app, 141, "island"))
                }
            })
        }
    }

    private fun side(v: RemoteViews, s: Side, ring: Int, pct: Int, bolt: Int, name: Int, line: Int, roomy: Boolean, you: Boolean) {
        v.setProgressBar(ring, 100, s.percent.coerceIn(0, 100), false)
        v.setTextViewText(pct, if (s.percent >= 0) "${s.percent}%" else "—")
        // Low and not charging: the glass says so in red (Material You keeps the wallpaper's colours).
        if (!you) v.setTextColor(pct, if (s.percent in 0..15 && !s.charging) 0xFFFF8A80.toInt() else 0xFFFFFFFF.toInt())
        v.setViewVisibility(bolt, if (s.charging) View.VISIBLE else View.GONE)
        v.setTextViewText(name, s.name); v.setTextViewText(line, s.line)
        v.setViewVisibility(line, if (roomy && s.line.isNotEmpty()) View.VISIBLE else View.GONE)
    }

    /** The PC's side: its status is read most often; its battery in full (island 0.23) and its numbers know the minutes left. */
    private fun pcSide(pc: PeerView?): Side {
        if (pc == null) return Side("YOUR PC", -1, false, "Pair with your PC")
        val name = pc.name.uppercase()
        if (!pc.online) return Side(name, -1, false, "Away")
        val b = Hub.pcBattery.value; val st = Hub.pcStats.value; val s = Hub.status.value
        val present = b?.present ?: s?.batteryPresent ?: st?.let { it.battery >= 0 } ?: return Side(name, -1, false, "")
        if (!present) return Side(name, -1, false, "No battery")
        val percent = s?.battery?.takeIf { s.batteryPresent && it >= 0 } ?: b?.percent?.takeIf { it >= 0 } ?: st?.battery?.takeIf { it >= 0 } ?: -1
        val charging = s?.charging ?: b?.charging ?: st?.charging ?: false
        val left = b?.minutesLeft?.takeIf { it > 0 } ?: st?.batteryMinutes?.takeIf { it > 0 }?.roundToInt()
        fun span(minutes: Int) = leftText(minutes * 60.0).removeSuffix(" left")
        val line = when {
            charging && (b?.minutesToFull ?: 0) > 0 -> "Full in ${span(b!!.minutesToFull)}"
            charging -> "Charging"
            b?.online == true -> "Plugged in"
            left != null -> "${span(left)} left"
            else -> "On battery"
        }
        return Side(name, percent, charging, line)
    }
}

/** The transfer widget's drawing: the transfer in flight (the first of several), else the last one that finished. */
object TransferWidgets {
    private val COMPACT = SizeF(110f, 40f); private val ROOMY = SizeF(200f, 96f)
    private var drawn = ""

    @Synchronized fun refresh(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val ids = WidgetFeeds.idsOf(app, TransferWidget::class.java); if (ids.isEmpty()) return
        val m = AppWidgetManager.getInstance(app); val you = WidgetStyle.you(app)
        val all = Hub.transfers.value.values.toList(); val t = all.firstOrNull()
        val where: String; val title: String; val detail: String; val outgoing: Boolean; val progress: Int
        if (t != null) {
            where = if (t.outgoing) "SENDING TO ${t.name.uppercase()}" else "FROM ${t.name.uppercase()}"
            title = t.title; outgoing = t.outgoing
            val fraction = if (t.total > 0) (t.done.toDouble() / t.total).coerceIn(0.0, 1.0) else 0.0
            progress = (fraction * 1000).toInt()
            detail = listOf("${(fraction * 100).toInt()}%", if (t.rate > 0) rateText(t.rate) else "", if (t.rate > 0 && t.total > 0) leftText((t.total - t.done) / t.rate) else "")
                .filter { it.isNotEmpty() }.joinToString("  ·  ")
        } else {
            val last = Hub.moments.value.firstOrNull(); progress = -1
            outgoing = last == null || last.kind == 1
            where = if (last == null) "TRANSFERS" else "LAST TRANSFER"
            title = when (last?.kind) { null -> "Nothing sent yet"; 1 -> "Sent ${last.title} to ${last.from}"; 2 -> "Took ${last.title} from ${last.from}’s Shelf"; else -> "Received ${last.title} from ${last.from}" }
            detail = if (last == null) "Share anything to your PC" else ago(last.at)
        }
        val more = (all.size - 1).coerceAtLeast(0)
        val pc = Hub.pc(); val tint = WidgetFeeds.tint(app, pc, if (pc != null) Hub.status.value else null)
        val look = listOf(where, title, detail, progress / 10, more, tint?.hashCode(), you).joinToString("|")
        if (!force && look == drawn) return
        drawn = look
        for (id in ids) runCatching {
            val bg = WidgetFeeds.glass(app, m, id, tint, 50, 250)
            m.updateAppWidget(id, WidgetFeeds.sized(m, id, COMPACT, ROOMY, false) { roomy ->
                RemoteViews(app.packageName, if (you) (if (roomy) R.layout.widget_transfer_you else R.layout.widget_transfer_you_small) else if (roomy) R.layout.widget_transfer else R.layout.widget_transfer_small).apply {
                    setTextViewText(R.id.w_where, where); setTextViewText(R.id.w_title, title)
                    // Small, the others fold into its line ("+2").
                    setTextViewText(R.id.w_detail, if (!roomy && more > 0) "$detail  ·  +$more" else detail)
                    setTextViewText(R.id.w_more, if (more > 0) "and $more more" else "")
                    setViewVisibility(R.id.w_more, if (roomy && more > 0) View.VISIBLE else View.GONE)
                    setImageViewResource(R.id.w_dir, if (outgoing) R.drawable.ic_w_send else R.drawable.ic_w_receive)
                    setViewVisibility(R.id.w_bar, if (progress >= 0) View.VISIBLE else View.GONE)
                    setProgressBar(R.id.w_bar, 1000, progress.coerceAtLeast(0), false)
                    WidgetFeeds.paint(this, bg, tint, you, R.id.w_where)
                    setOnClickPendingIntent(R.id.w_open, WidgetStyle.open(app, 142, null))
                }
            })
        }
    }
}
