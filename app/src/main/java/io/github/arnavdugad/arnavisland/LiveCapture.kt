package io.github.arnavdugad.arnavisland

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import io.github.arnavdugad.arnavisland.LiveWire.Live
import io.github.arnavdugad.arnavisland.LiveWire.Mark
import io.github.arnavdugad.arnavisland.link.PeerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 1.9: live activities on your PC. An ongoing notification that tracks something (a food or grocery delivery, a ride,
 * the way somewhere in Maps, a timer, a download, an Android 16 live update) goes to your paired PCs that are here
 * (island 0.27, revision 10) as notice 7, with its progress line, its segments and points, its ETA or timer, its icons
 * and, when the app draws its own view (as many delivery apps do), a picture of that view. At most one update a second
 * per activity (the latest wins), and only when something changed; "ended" when it goes. Same privacy as the
 * notifications: only while "mirror" is on, never a lock-screen-secret one; plus its own switch, [enabled].
 */
object LiveCapture {
    private const val PREF = "liveActivities"
    /** Whether live activities go to your PCs (default on). */
    fun enabled(): Boolean = Hub.prefs.getBoolean(PREF, true)
    fun setEnabled(on: Boolean) { Hub.prefs.edit().putBoolean(PREF, on).apply(); if (!on) main.post { live.toList().forEach(::removed) } }

    /** Food, groceries, parcels and rides: their ongoing notifications track a trip. */
    private val trips = setOf(
        "in.swiggy.android", "com.application.zomato", "com.grofers.customerapp", "com.zeptoconsumerapp", "com.bigbasket.mobileapp",
        "com.dunzo.user", "com.Dominos", "com.dominospizza", "com.done.faasos", "com.jpl.jiomart", "com.licious", "com.flipkart.android",
        "in.amazon.mShop.android.shopping", "com.ubercab.eats", "com.dd.doordash", "com.grubhub.android", "com.global.foodpanda.android",
        "com.deliveroo.orderapp", "com.theporter.android.customerapp",
        "com.ubercab", "com.olacabs.customer", "com.rapido.passenger", "com.blusmart.rider", "in.juspay.nammayatri", "com.lyft.android",
        "ee.mtakso.client", "com.grabtaxi.passenger", "sinet.startup.inDriver",
    )
    private val navigators = setOf("com.google.android.apps.maps", "com.google.android.apps.mapslite", "com.waze", "com.mmi.maps", "com.here.app.maps", "com.sygic.aura")
    private val tracking = setOf(Notification.CATEGORY_PROGRESS, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_NAVIGATION, Notification.CATEGORY_STATUS, Notification.CATEGORY_SERVICE)
    private const val PROGRESS_STYLE = "android.app.Notification\$ProgressStyle"

    private val main = Handler(Looper.getMainLooper())
    /** One lane for building and sending, so an activity's frames go in order. */
    @OptIn(ExperimentalCoroutinesApi::class) private val lane = Dispatchers.IO.limitedParallelism(1)
    // On the main thread: the keys being tracked, the newest notification waiting for each, those with a tick coming, when each was last taken.
    private val live = HashSet<String>(); private val pending = HashMap<String, StatusBarNotification>()
    private val scheduled = HashSet<String>(); private val last = HashMap<String, Long>()
    // On the lane: the frame each activity last sent (sent again to a PC that arrives), app names and icons.
    private val frames = LinkedHashMap<String, ByteArray>(); private val names = HashMap<String, String>(); private val icons = HashMap<String, ByteArray?>()
    private val started = AtomicBoolean()

    /** Watches for PCs arriving (each gets the activities live right now). Called when the listener connects; once. */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        Hub.init(context)
        Hub.scope.launch {
            var seen = emptySet<String>()
            Hub.peers.collect { list ->
                val here = list.filter(::eligible).map { it.id }.toSet(); val fresh = here - seen; seen = here
                if (fresh.isNotEmpty()) Hub.scope.launch(lane) {
                    if (frames.isEmpty() || !allowed()) return@launch
                    val to = targets().filter { it.id in fresh }; frames.values.toList().forEach { send(to, it) }
                }
            }
        }
    }

    /** A notification posted or updated: tracked (at most once a second) when it's a live activity, ended when it stopped being one. */
    fun posted(context: Context, sbn: StatusBarNotification, rankings: RankingMap?) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { posted(context, sbn, rankings) }; return }
        val n = sbn.notification ?: return
        val ranking = runCatching { rankings?.let { r -> Ranking().takeIf { r.getRanking(sbn.key, it) } } }.getOrNull()
        val ok = runCatching { allowed() && !secret(n, ranking) && isLive(context, sbn, n, ranking) }.getOrDefault(false)
        val key = sbn.key
        if (!ok) { if (key in live) removed(key); return }
        live += key; pending[key] = sbn
        if (scheduled.add(key)) {
            val wait = ((last[key] ?: 0L) + 1000 - SystemClock.elapsedRealtime()).coerceIn(0, 1000)
            val app = context.applicationContext
            main.postDelayed({ tick(app, key) }, wait)
        }
    }

    /** A notification the phone no longer shows: its activity ends on the PCs. */
    fun removed(key: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { removed(key) }; return }
        if (!live.remove(key)) return
        pending.remove(key); last.remove(key)
        Hub.scope.launch(lane) { if (frames.remove(key) != null) send(targets(), LiveWire.ended(key)) }
    }

    private fun tick(context: Context, key: String) {
        scheduled.remove(key)
        val sbn = pending.remove(key) ?: return
        last[key] = SystemClock.elapsedRealtime()
        val picture = runCatching { snapshot(context, sbn.notification) }.getOrNull()
        Hub.scope.launch(lane) {
            val l = runCatching { extract(context, key, sbn, picture) }.getOrNull(); picture?.recycle(); l ?: return@launch
            val frame = LiveWire.shown(l)
            if (frames[key]?.contentEquals(frame) == true) return@launch
            frames.remove(key); frames[key] = frame
            send(targets(), frame)
        }
    }

    private fun allowed() = Hub.prefs.getBoolean("mirror", true) && enabled()
    private fun eligible(p: PeerView) = p.paired && p.online && !p.phone && p.remote && p.revision >= LiveWire.REVISION
    private fun targets() = Hub.peers.value.filter(::eligible)
    private fun send(to: List<PeerView>, frame: ByteArray) { val link = Hub.link ?: return; to.forEach { runCatching { link.notice(it.id, frame) } } }

    /** Hidden on the lock screen (by the app, or by its channel): never leaves the phone. */
    private fun secret(n: Notification, ranking: Ranking?) =
        n.visibility == Notification.VISIBILITY_SECRET || ranking?.lockscreenVisibilityOverride == Notification.VISIBILITY_SECRET

    private fun promoted(n: Notification): Boolean =
        Build.VERSION.SDK_INT >= 36 && (n.flags and Notification.FLAG_PROMOTED_ONGOING != 0 || runCatching { n.hasPromotableCharacteristics() }.getOrDefault(false))

    private fun hasProgress(n: Notification) = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
        n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) || n.extras.getString(Notification.EXTRA_TEMPLATE) == PROGRESS_STYLE

    /**
     * Ongoing and tracking something (progress, promoted, a timer, a known delivery, ride or maps app), or a known one's
     * progress / status update even when it isn't ongoing; music, calls and quiet background services aren't.
     */
    private fun isLive(context: Context, sbn: StatusBarNotification, n: Notification, ranking: Ranking?): Boolean {
        val pkg = sbn.packageName
        if (pkg == context.packageName || n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.category == Notification.CATEGORY_CALL) return false
        val x = n.extras
        if (x.containsKey(Notification.EXTRA_MEDIA_SESSION) || x.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true) return false
        val known = pkg in trips || pkg in navigators
        if (!known && ranking != null && ranking.importance <= NotificationManager.IMPORTANCE_MIN) return false
        val progress = hasProgress(n); val promoted = promoted(n)
        val chrono = x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        return (sbn.isOngoing && (progress || promoted || chrono || known)) || (known && (progress || promoted || n.category in tracking))
    }

    private fun extract(context: Context, key: String, sbn: StatusBarNotification, picture: Bitmap?): Live {
        val n = sbn.notification; val x = n.extras; val pkg = sbn.packageName
        val app = names.getOrPut(pkg) { runCatching { context.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg) }
        val title = Mirror.plain(x.getCharSequence(Notification.EXTRA_TITLE) ?: x.getCharSequence(Notification.EXTRA_TITLE_BIG))
        val text = Mirror.plain(x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: x.getCharSequence(Notification.EXTRA_TEXT))
        val critical = if (Build.VERSION.SDK_INT >= 36) runCatching { n.shortCriticalText }.getOrNull() else null
        val status = Mirror.plain(critical ?: x.getCharSequence("android.shortCriticalText") ?: x.getCharSequence(Notification.EXTRA_SUB_TEXT))
        val accent = n.color.takeIf { it != Notification.COLOR_DEFAULT } ?: 0
        val style = if (Build.VERSION.SDK_INT >= 36 && x.getString(Notification.EXTRA_TEMPLATE) == PROGRESS_STYLE)
            runCatching { Notification.Builder.recoverBuilder(context, n).style as? Notification.ProgressStyle }.getOrNull() else null
        var flags = 0; var max: Int; var at: Int; var indeterminate: Boolean
        var segments = emptyList<Mark>(); var points = emptyList<Mark>(); var tracker: Icon? = null; var startIcon: Icon? = null; var endIcon: Icon? = null
        if (style != null && Build.VERSION.SDK_INT >= 36) {
            max = style.progressMax; at = style.progress; indeterminate = style.isProgressIndeterminate
            segments = style.progressSegments.take(10).map { Mark(it.length, it.color.takeIf { c -> c != Notification.COLOR_DEFAULT } ?: accent) }
            points = style.progressPoints.take(10).map { Mark(if (max > 0) (it.position.toLong() * 1000 / max).toInt() else 0, it.color.takeIf { c -> c != Notification.COLOR_DEFAULT } ?: accent) }
            tracker = style.progressTrackerIcon; startIcon = style.progressStartIcon; endIcon = style.progressEndIcon
        } else {
            max = x.getInt(Notification.EXTRA_PROGRESS_MAX, 0); at = x.getInt(Notification.EXTRA_PROGRESS, 0); indeterminate = x.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        }
        if (sbn.isOngoing) flags = flags or LiveWire.ONGOING
        if (promoted(n)) flags = flags or LiveWire.PROMOTED
        if (max > 0 || indeterminate) flags = flags or LiveWire.PROGRESS
        if (indeterminate) flags = flags or LiveWire.INDETERMINATE
        if (pkg in trips) flags = flags or LiveWire.TRIP
        if (pkg in navigators || n.category == Notification.CATEGORY_NAVIGATION) flags = flags or LiveWire.NAVIGATION
        val now = System.currentTimeMillis()
        val whenMs = when {
            x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) -> { if (x.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)) flags = flags or LiveWire.COUNT_DOWN; n.`when` }
            x.getBoolean(Notification.EXTRA_SHOW_WHEN) && n.`when` > now + 30_000 -> { flags = flags or LiveWire.COUNT_DOWN; n.`when` }  // an ETA
            else -> 0L
        }
        val icon = icons.getOrPut(pkg) { appIcon(context, pkg) } ?: n.smallIcon?.let { small -> iconPng(context, small, accent.takeIf { it != 0 } ?: Color.WHITE) }
        val large = runCatching { n.getLargeIcon()?.loadDrawable(context) }.getOrNull()?.let { picture(it, LiveWire.LARGE_LIMIT) }
        return Live(key.take(200), app, pkg, title, text, status, flags, if (max > 0) (at.toLong() * 1000 / max).toInt().coerceIn(0, 1000) else 0,
            segments, points, whenMs, accent, icon, tracker?.let { iconPng(context, it) }, startIcon?.let { iconPng(context, it) }, endIcon?.let { iconPng(context, it) },
            large, picture?.let { jpeg(it, LiveWire.SNAPSHOT_LIMIT) })
    }

    // Pictures.

    /** A drawable fitted (aspect kept) into a [box]-pixel square's width and height. */
    private fun draw(d: Drawable, box: Int, tint: Int? = null): Bitmap {
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: box; val h = d.intrinsicHeight.takeIf { it > 0 } ?: box
        val s = box.toFloat() / max(w, h); val bw = max(1, (w * s).roundToInt()); val bh = max(1, (h * s).roundToInt())
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val d2 = if (tint != null) d.mutate().apply { setTint(tint) } else d
        d2.setBounds(0, 0, bw, bh); d2.draw(Canvas(bitmap)); return bitmap
    }
    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    /** A PNG within the icon limit: 64 px, else 48, else 32. */
    private fun fitted(d: Drawable, tint: Int? = null): ByteArray? {
        for (side in intArrayOf(64, 48, 32)) { val b = draw(d, side, tint); val out = png(b); b.recycle(); if (out.size <= LiveWire.ICON_LIMIT) return out }
        return null
    }
    private fun appIcon(context: Context, pkg: String): ByteArray? = runCatching { fitted(context.packageManager.getApplicationIcon(pkg)) }.getOrNull()
    private fun iconPng(context: Context, icon: Icon, tint: Int? = null): ByteArray? = runCatching { icon.loadDrawable(context)?.let { fitted(it, tint) } }.getOrNull()
    /** The large icon: a PNG (128, 96 or 64 px) when it fits, else a JPEG. */
    private fun picture(d: Drawable, limit: Int): ByteArray? = runCatching {
        for (side in intArrayOf(128, 96, 64)) { val b = draw(d, side); val out = png(b); if (out.size <= limit) { b.recycle(); return out }; val j = jpeg(b, limit); b.recycle(); if (j != null) return j }
        null
    }.getOrNull()
    /** A JPEG within [limit]: lower quality first, then smaller. */
    private fun jpeg(bitmap: Bitmap, limit: Int): ByteArray? {
        var b = bitmap
        try {
            repeat(4) {
                for (q in intArrayOf(85, 70, 55, 40)) {
                    val out = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, q, out); if (out.size() <= limit) return out.toByteArray()
                }
                val next = Bitmap.createScaledBitmap(b, max(1, (b.width * 0.7f).roundToInt()), max(1, (b.height * 0.7f).roundToInt()), true)
                if (b !== bitmap) b.recycle(); b = next
            }
            return null
        } finally { if (b !== bitmap) b.recycle() }
    }

    /** The app's own notification view drawn to a picture (main thread), when it has one; null when it hasn't or it can't be drawn. */
    @Suppress("DEPRECATION")
    private fun snapshot(context: Context, n: Notification): Bitmap? {
        val big = n.bigContentView; val small = n.contentView
        if (big == null && small == null) return null
        big?.let { v -> render(context, v, 256)?.let { return it } }
        small?.let { v -> render(context, v, 128)?.let { return it } }
        val b = runCatching { Notification.Builder.recoverBuilder(context, n) }.getOrNull() ?: return null
        runCatching { b.createBigContentView() }.getOrNull()?.let { v -> render(context, v, 256)?.let { return it } }
        runCatching { b.createContentView() }.getOrNull()?.let { v -> render(context, v, 128)?.let { return it } }
        return null
    }
    /** RemoteViews laid out 360 dp wide (its own height, at most [capDp]) over the shade's colour. */
    private fun render(context: Context, views: RemoteViews, capDp: Int): Bitmap? = try {
        val density = context.resources.displayMetrics.density
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val themed = ContextThemeWrapper(context, if (night) android.R.style.Theme_DeviceDefault else android.R.style.Theme_DeviceDefault_Light)
        val parent = FrameLayout(themed); parent.addView(views.apply(themed, parent))
        val width = (360 * density).roundToInt(); val cap = (capDp * density).roundToInt()
        val exact = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        parent.measure(exact, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        var h = parent.measuredHeight.coerceAtMost(cap)
        if (h < (24 * density).roundToInt()) { parent.measure(exact, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST)); h = parent.measuredHeight.coerceAtMost(cap) }
        if (h < (16 * density).roundToInt()) null else {
            parent.measure(exact, View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); parent.layout(0, 0, width, h)
            val bitmap = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap)
            canvas.drawColor(if (night) 0xFF1F1F1F.toInt() else Color.WHITE); parent.draw(canvas); bitmap
        }
    } catch (_: Throwable) { null }
}
