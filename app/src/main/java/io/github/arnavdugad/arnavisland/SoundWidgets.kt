package io.github.arnavdugad.arnavisland

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.compose.ui.graphics.asAndroidBitmap
import io.github.arnavdugad.arnavisland.link.IslandWire
import io.github.arnavdugad.arnavisland.link.MixerApp
import io.github.arnavdugad.arnavisland.link.PcControls
import io.github.arnavdugad.arnavisland.link.PeerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 1.9: your PC's controls in tiles, as in Control Center: Wi-Fi, Bluetooth, dark mode, mute and the microphone, its volume and Lock. */
class ControlsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = WidgetFeeds.changed(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = WidgetFeeds.changed(context)
    override fun onEnabled(context: Context) { Hub.init(context); LinkService.start(context) }
    override fun onDisabled(context: Context) = WidgetFeeds.changed(context)
}
/** 1.9: your PC's sound: its volume, and the apps playing there, each with its own volume and mute. */
class MixerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = WidgetFeeds.changed(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = WidgetFeeds.changed(context)
    override fun onEnabled(context: Context) { Hub.init(context); LinkService.start(context) }
    override fun onDisabled(context: Context) = WidgetFeeds.changed(context)
}

/**
 * 1.9: the controls widget's drawing. On Android 12 and later its tiles are real switches (they flip as they're touched,
 * before the PC hears of it); before, they're tiles that flip as the tap's broadcast redraws them. Either way the tile
 * then settles to what the PC says, and greys out while the PC isn't here.
 */
object ControlsWidgets {
    private val COMPACT = SizeF(100f, 100f); private val ROOMY = SizeF(200f, 100f)
    private var drawn = ""
    private class Tile(val control: Int, val view: Int, val icon: Int, val on: Int, val label: String)
    /** The tiles in their order: the small widget shows the first four. */
    private val TILES = listOf(
        Tile(IslandWire.WIFI, R.id.w_wifi, R.id.w_wifi_icon, R.drawable.widget_tile_on_blue, "Wi-Fi"),
        Tile(IslandWire.BLUETOOTH, R.id.w_bt, R.id.w_bt_icon, R.drawable.widget_tile_on_blue, "Bluetooth"),
        Tile(IslandWire.DARK, R.id.w_dark, R.id.w_dark_icon, R.drawable.widget_tile_on_indigo, "Dark mode"),
        Tile(IslandWire.MUTE, R.id.w_mute, R.id.w_mute_icon, R.drawable.widget_tile_on_red, "Mute"),
        Tile(IslandWire.MIC, R.id.w_mic, R.id.w_mic_icon, R.drawable.widget_tile_on_orange, "Microphone muted"),
    )

    /** A control as a tile shows it: 1 on, 0 off, -1 unknown, -2 none (no microphone, no Bluetooth). */
    private fun state(c: PcControls?, control: Int): Int = if (c == null) -1 else when (control) {
        IslandWire.WIFI -> c.wifi; IslandWire.BLUETOOTH -> c.bluetooth; IslandWire.DARK -> c.dark
        IslandWire.MUTE -> if (c.muted) 1 else 0
        else -> if (!c.micAvailable) -2 else if (c.micMuted) 1 else 0
    }
    /** The controls as they'll be once the PC has done it (shown meanwhile); a radio is busy until the PC says otherwise. */
    internal fun seen(c: PcControls, control: Int, v: Int): PcControls = when (control) {
        IslandWire.WIFI -> c.copy(wifi = v, busy = c.busy or 1)
        IslandWire.BLUETOOTH -> c.copy(bluetooth = v, busy = c.busy or 2)
        IslandWire.DARK -> c.copy(dark = v, busy = c.busy or 8)
        IslandWire.MUTE -> c.copy(muted = v == 1)
        IslandWire.MIC -> c.copy(micMuted = v == 1)
        IslandWire.VOLUME -> c.copy(volume = v)
        else -> c
    }

    @Synchronized fun refresh(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val ids = WidgetFeeds.idsOf(app, ControlsWidget::class.java); if (ids.isEmpty()) return
        val m = AppWidgetManager.getInstance(app); val you = WidgetStyle.you(app); val switches = Build.VERSION.SDK_INT >= 31
        val pc = Hub.pc(); val ready = Hub.islandReady(pc); val c = Hub.pcControls.value.takeIf { ready }
        val states = TILES.map { state(c, it.control) }
        val volume = c?.volume ?: -1; val muted = c?.muted == true
        val where = when { pc == null -> "PAIR WITH YOUR PC"; !pc.online -> "${pc.name.uppercase()} IS AWAY"; !ready -> "UPDATE ${pc.name.uppercase()}’S ISLAND"; else -> pc.name.uppercase() }
        val tint = WidgetFeeds.tint(app, pc, if (pc != null) Hub.status.value else null)
        val look = listOf(where, ready, states, volume, muted, pc?.online, tint?.hashCode(), you).joinToString("|")
        if (!force && look == drawn) return
        drawn = look
        for (id in ids) runCatching {
            val bg = WidgetFeeds.glass(app, m, id, tint, 110, 250)
            m.updateAppWidget(id, WidgetFeeds.sized(m, id, COMPACT, ROOMY, true) { roomy ->
                val layout = when { you -> if (roomy) R.layout.widget_controls_you else R.layout.widget_controls_you_small
                    switches -> if (roomy) R.layout.widget_controls else R.layout.widget_controls_small
                    else -> if (roomy) R.layout.widget_controls_tap else R.layout.widget_controls_tap_small }
                RemoteViews(app.packageName, layout).apply {
                    setTextViewText(R.id.w_where, where)
                    setOnClickPendingIntent(R.id.w_where, WidgetStyle.open(app, 150, "island"))
                    for ((i, t) in TILES.withIndex()) {
                        if (!roomy && i >= 4) break
                        val s = states[i]; val on = s == 1; val enabled = ready && s != -2
                        setBoolean(t.view, "setEnabled", enabled)
                        if (switches) {
                            setCompoundButtonChecked(t.view, on)
                            setOnCheckedChangeResponse(t.view, RemoteViews.RemoteResponse.fromPendingIntent(ControlActions.toggle(app, t.control, !on)))
                            setInt(t.icon, "setImageAlpha", if (enabled) 255 else 90)
                        } else {
                            setInt(t.view, "setBackgroundResource", if (!enabled) R.drawable.widget_tile_dim else if (on) t.on else R.drawable.widget_tile_off)
                            setInt(t.view, "setImageAlpha", if (enabled) 255 else 90)
                            setContentDescription(t.view, "${t.label}, ${if (on) "on" else "off"}")
                            setOnClickPendingIntent(t.view, ControlActions.toggle(app, t.control, !on))
                        }
                    }
                    if (roomy) {
                        setProgressBar(R.id.w_vol_bar, 100, volume.coerceIn(0, 100), false)
                        setTextViewText(R.id.w_vol_pct, if (volume < 0) "—" else "$volume%")
                        setImageViewResource(R.id.w_vol_icon, if (muted || volume == 0) R.drawable.ic_w_volume_off else R.drawable.ic_w_volume)
                        for ((b, by) in listOf(R.id.w_vol_down to -10, R.id.w_vol_up to 10)) {
                            setBoolean(b, "setEnabled", ready); setInt(b, "setImageAlpha", if (ready) 255 else 90)
                            setOnClickPendingIntent(b, ControlActions.volume(app, by))
                        }
                        // Lock asks any PC that answers remote control, not just one with the whole island.
                        val lockable = pc?.online == true && pc.remote
                        setBoolean(R.id.w_lock, "setEnabled", lockable); setInt(R.id.w_lock, "setImageAlpha", if (lockable) 255 else 90)
                        setOnClickPendingIntent(R.id.w_lock, PendingIntent.getBroadcast(app, 183, Intent(app, WidgetActions::class.java).setAction("lock"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    }
                    WidgetFeeds.paint(this, bg, tint, you, R.id.w_where)
                }
            })
        }
    }
}

/**
 * 1.9: the mixer widget's drawing: the PC's volume on top, then its apps (those making sound first, each keeping its
 * row while it plays), as many as fit: two, four or five.
 */
object MixerWidgets {
    private val SIZES = listOf(SizeF(180f, 100f) to 2, SizeF(180f, 170f) to 4, SizeF(180f, 200f) to 5)
    private var drawn = ""; private var order = listOf<Long>()
    /** The apps' icons by name, small (the PC sends each only once, so they're kept here). */
    private val icons = LinkedHashMap<String, Bitmap>()
    private class Row(val view: Int, val icon: Int, val name: Int, val bar: Int, val pct: Int, val down: Int, val up: Int, val mute: Int)
    private val ROWS = listOf(
        Row(R.id.w_a0, R.id.w_a0_icon, R.id.w_a0_name, R.id.w_a0_bar, R.id.w_a0_pct, R.id.w_a0_down, R.id.w_a0_up, R.id.w_a0_mute),
        Row(R.id.w_a1, R.id.w_a1_icon, R.id.w_a1_name, R.id.w_a1_bar, R.id.w_a1_pct, R.id.w_a1_down, R.id.w_a1_up, R.id.w_a1_mute),
        Row(R.id.w_a2, R.id.w_a2_icon, R.id.w_a2_name, R.id.w_a2_bar, R.id.w_a2_pct, R.id.w_a2_down, R.id.w_a2_up, R.id.w_a2_mute),
        Row(R.id.w_a3, R.id.w_a3_icon, R.id.w_a3_name, R.id.w_a3_bar, R.id.w_a3_pct, R.id.w_a3_down, R.id.w_a3_up, R.id.w_a3_mute),
        Row(R.id.w_a4, R.id.w_a4_icon, R.id.w_a4_name, R.id.w_a4_bar, R.id.w_a4_pct, R.id.w_a4_down, R.id.w_a4_up, R.id.w_a4_mute),
    )

    @Synchronized fun refresh(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val ids = WidgetFeeds.idsOf(app, MixerWidget::class.java); if (ids.isEmpty()) return
        val m = AppWidgetManager.getInstance(app); val you = WidgetStyle.you(app)
        val pc = Hub.pc(); val here = pc?.online == true; val mixes = here && pc!!.revision >= 9
        val c = Hub.pcControls.value.takeIf { Hub.islandReady(pc) }; val s = if (here) Hub.status.value else null
        val volume = c?.volume ?: s?.volume ?: -1; val muted = c?.muted ?: s?.muted ?: false
        val apps = if (mixes) pick(Hub.mixer.value) else emptyList()
        val shown = apps.map { a -> Triple(a, icon(a), "${a.pid}:${a.name}:${a.volume}:${a.muted}") }
        val where = when { pc == null -> "PAIR WITH YOUR PC"; !pc.online -> "${pc.name.uppercase()} IS AWAY"; else -> pc.name.uppercase() }
        val empty = when { pc == null -> "Open Arnav Island to pair with your PC"; !pc.online -> "Its sound shows here when it’s back"
            !mixes -> "Update the island on ${pc.name} to see its apps’ sound"; apps.isEmpty() -> "Nothing’s making sound"; else -> "" }
        val tint = WidgetFeeds.tint(app, pc, s)
        val look = listOf(where, volume, muted, c != null, shown.joinToString(",") { "${it.third}:${it.second != null}" }, empty, tint?.hashCode(), you).joinToString("|")
        if (!force && look == drawn) return
        drawn = look
        val buttonOff = if (you) R.drawable.widget_you_button else R.drawable.widget_button; val buttonOn = if (you) R.drawable.widget_you_button_on else R.drawable.widget_button_on
        for (id in ids) runCatching {
            val bg = WidgetFeeds.glass(app, m, id, tint, 180, 250)
            m.updateAppWidget(id, sized(m, id) { fits ->
                RemoteViews(app.packageName, if (you) R.layout.widget_mixer_you else R.layout.widget_mixer).apply {
                    setTextViewText(R.id.w_where, where)
                    setOnClickPendingIntent(R.id.w_open, WidgetStyle.open(app, 170, "island"))
                    // The PC's own volume (its mute and steps with the whole island, 0.22 or later).
                    setProgressBar(R.id.w_m_bar, 100, volume.coerceIn(0, 100), false)
                    setTextViewText(R.id.w_m_pct, when { volume < 0 -> "—"; muted -> "Off"; else -> "$volume%" })
                    setImageViewResource(R.id.w_m_icon, if (muted || volume == 0) R.drawable.ic_w_volume_off else R.drawable.ic_w_volume)
                    for (b in listOf(R.id.w_m_down, R.id.w_m_up, R.id.w_m_mute)) setViewVisibility(b, if (c != null) View.VISIBLE else View.GONE)
                    setInt(R.id.w_m_mute, "setBackgroundResource", if (muted) buttonOn else buttonOff)
                    setContentDescription(R.id.w_m_mute, if (muted) "Unmute ${pc?.name ?: "your PC"}" else "Mute ${pc?.name ?: "your PC"}")
                    setOnClickPendingIntent(R.id.w_m_down, ControlActions.volume(app, -10)); setOnClickPendingIntent(R.id.w_m_up, ControlActions.volume(app, 10))
                    setOnClickPendingIntent(R.id.w_m_mute, ControlActions.toggle(app, IslandWire.MUTE, !muted))
                    for ((i, row) in ROWS.withIndex()) {
                        val item = shown.getOrNull(i)?.takeIf { i < fits }
                        if (item == null) { setViewVisibility(row.view, View.GONE); continue }
                        val (a, bmp) = item
                        setViewVisibility(row.view, View.VISIBLE)
                        setTextViewText(row.name, a.name)
                        if (bmp != null) setImageViewBitmap(row.icon, bmp) else setImageViewResource(row.icon, R.drawable.ic_w_app)
                        setProgressBar(row.bar, 100, a.volume.coerceIn(0, 100), false)
                        setTextViewText(row.pct, if (a.muted) "Off" else "${a.volume}%")
                        setInt(row.mute, "setBackgroundResource", if (a.muted) buttonOn else buttonOff)
                        setContentDescription(row.mute, if (a.muted) "Unmute ${a.name}" else "Mute ${a.name}")
                        setContentDescription(row.down, "${a.name} quieter"); setContentDescription(row.up, "${a.name} louder")
                        setOnClickPendingIntent(row.down, ControlActions.app(app, i, "mix_volume", a.pid, -10))
                        setOnClickPendingIntent(row.up, ControlActions.app(app, i, "mix_volume", a.pid, 10))
                        setOnClickPendingIntent(row.mute, ControlActions.app(app, i, "mix_mute", a.pid))
                    }
                    setTextViewText(R.id.w_empty, empty); setViewVisibility(R.id.w_empty, if (empty.isNotEmpty()) View.VISIBLE else View.GONE)
                    WidgetFeeds.paint(this, bg, tint, you, R.id.w_where)
                }
            })
        }
    }

    /** Its views for each size: Android 12 and later picks the one that fits; before, the one for its height now. */
    private fun sized(m: AppWidgetManager, id: Int, build: (rows: Int) -> RemoteViews): RemoteViews {
        if (Build.VERSION.SDK_INT >= 31) return RemoteViews(SIZES.associate { (size, rows) -> size to build(rows) })
        val h = m.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        return build(if (h <= 0) 4 else SIZES.lastOrNull { h >= it.first.height }?.second ?: SIZES.first().second)
    }

    /** The five to show: those making sound first, and among them the ones already shown keep their places. */
    private fun pick(all: List<MixerApp>): List<MixerApp> {
        val before = order.toSet()
        val top = all.sortedWith(compareByDescending<MixerApp> { it.active }.thenBy { it.system }.thenByDescending { it.pid in before }.thenByDescending { it.peak }).take(ROWS.size)
        return top.sortedBy { a -> order.indexOf(a.pid).let { if (it < 0) Int.MAX_VALUE else it } }.also { order = it.map { a -> a.pid } }
    }
    /** An app's icon from what the PC sent with it, or what the app's mixer kept (the PC sends each icon once). */
    private fun icon(a: MixerApp): Bitmap? {
        icons[a.name]?.let { return it }
        val raw = a.icon?.let { b -> runCatching { BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull() }
            ?: runCatching { Hub.mixerIcons.value[a.name]?.asAndroidBitmap() }.getOrNull() ?: return null
        val small = runCatching { Bitmap.createScaledBitmap(raw, 56, 56, true) }.getOrNull() ?: return null
        if (icons.size >= 24) icons.remove(icons.keys.first())
        icons[a.name] = small
        return small
    }
}

/**
 * 1.9: the controls and mixer widgets' buttons: each shows at once, then is done on the PC (the link started if it
 * isn't), and the widget settles to what the PC says.
 */
class ControlActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Hub.init(context)
        val what = intent.action ?: return; val app = context.applicationContext
        val pending = goAsync()
        Hub.scope.launch {
            var settle = false
            try {
                when (what) {
                    "ctl_toggle" -> settle = toggle(app, intent)
                    "ctl_volume" -> volume(app, intent.getIntExtra("by", 0))
                    "mix_volume", "mix_mute" -> mixer(app, what, intent)
                }
            } finally { pending.finish() }
            // A radio takes a moment to turn on or off: its tile settles once the PC says it has.
            if (settle) for (i in 0 until 4) { delay(1_500); Hub.refreshIsland(false); if ((Hub.pcControls.value?.busy ?: 0) == 0) break }
            ControlsWidgets.refresh(app, force = true); MixerWidgets.refresh(app, force = true)
        }
    }

    /** The PC, once the link is up and it's here (a few seconds at most), else null. */
    private suspend fun here(context: Context): PeerView? {
        if (Hub.link == null) LinkService.start(context)
        for (i in 0 until 40) { if (Hub.link != null && Hub.pc()?.online == true) break; delay(100) }
        return Hub.pc()?.takeIf { Hub.link != null && it.online }
    }
    private suspend fun say(context: Context, text: String) = withContext(Dispatchers.Main) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    private fun away() = Hub.pc()?.let { "${it.name} is away" } ?: "Pair with your PC first"
    private fun redraw(app: Context) { ControlsWidgets.refresh(app); MixerWidgets.refresh(app) }

    /** A tile flipped: true when a radio is still changing on the PC (so it's asked again in a moment). */
    private suspend fun toggle(app: Context, intent: Intent): Boolean {
        val control = intent.getIntExtra("control", 0)
        // Android 12's switches say whether they're now checked; a tile before says what it asks for.
        val v = if (if (intent.hasExtra(RemoteViews.EXTRA_CHECKED)) intent.getBooleanExtra(RemoteViews.EXTRA_CHECKED, false) else intent.getBooleanExtra("on", false)) 1 else 0
        return set(app, control, v)
    }
    /** The PC's volume, a step of 10% at a time (to the next tenth from where it is). */
    private suspend fun volume(app: Context, by: Int) {
        val now = (Hub.pcControls.value?.volume ?: Hub.status.value?.volume ?: return).coerceIn(0, 100)
        set(app, IslandWire.VOLUME, step(now, by))
    }
    private suspend fun set(app: Context, control: Int, v: Int): Boolean {
        val before = Hub.pcControls.value; val seen = before?.let { ControlsWidgets.seen(it, control, v) }
        if (seen != null) { Hub.pcControls.value = seen; redraw(app) }
        val pc = here(app)
        val ok = pc != null && Hub.islandReady(pc) && Hub.setControlNow(control, v)
        if (ok) return (Hub.pcControls.value?.busy ?: 0) != 0
        if (pc != null) Hub.refreshIsland(false)
        // Nothing better heard: back to what it was.
        if (seen != null && Hub.pcControls.value === seen) Hub.pcControls.value = before
        say(app, if (pc == null) away() else if (!Hub.islandReady(pc)) "Update the island on ${pc.name} first" else "${pc.name} couldn’t do that")
        return false
    }
    /** An app's volume or mute (the mixer shows it at once, and keeps it until the PC agrees). */
    private suspend fun mixer(app: Context, what: String, intent: Intent) {
        val pid = intent.getLongExtra("pid", -1L)
        val pc = Hub.pc()?.takeIf { Hub.link != null && it.online } ?: here(app)
        if (pc == null) { say(app, away()); return }
        val a = Hub.mixer.value.firstOrNull { it.pid == pid } ?: return
        if (what == "mix_mute") Hub.setAppMute(pid, !a.muted) else Hub.setAppVolume(pid, step(a.volume.coerceIn(0, 100), intent.getIntExtra("by", 0)), final = true)
        MixerWidgets.refresh(app)
    }
    private fun step(now: Int, by: Int) = (if (by > 0) (now / 10 + 1) * 10 else ((now + 9) / 10 - 1) * 10).coerceIn(0, 100)

    companion object {
        private const val IMMUTABLE = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        /** A tile's (or the mixer's mute's) tap: [on] what it asks for. Android 12's switches fill in whether they're checked, so theirs stays open to that. */
        fun toggle(context: Context, control: Int, on: Boolean): PendingIntent = PendingIntent.getBroadcast(context, 160 + control,
            Intent(context, ControlActions::class.java).setAction("ctl_toggle").putExtra("control", control).putExtra("on", on),
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE else IMMUTABLE)
        fun volume(context: Context, by: Int): PendingIntent = PendingIntent.getBroadcast(context, if (by > 0) 181 else 182,
            Intent(context, ControlActions::class.java).setAction("ctl_volume").putExtra("by", by), IMMUTABLE)
        /** A mixer row's button ([slot]: its row, so each row's intent carries its own app). */
        fun app(context: Context, slot: Int, what: String, pid: Long, by: Int = 0): PendingIntent = PendingIntent.getBroadcast(context, 200 + slot * 4 + if (what == "mix_mute") 0 else if (by > 0) 1 else 2,
            Intent(context, ControlActions::class.java).setAction(what).putExtra("pid", pid).putExtra("by", by), IMMUTABLE)
    }
}
