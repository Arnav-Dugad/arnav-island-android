package io.github.arnavdugad.arnavisland

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import io.github.arnavdugad.arnavisland.link.PcStatus
import io.github.arnavdugad.arnavisland.link.PeerView
import io.github.arnavdugad.arnavisland.link.Proto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The now-playing widget: what plays on your PC with its controls, and the quick actions beneath. */
class PcWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { Hub.init(context); PcWidgets.refresh(context, force = true) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { Hub.init(context); PcWidgets.refresh(context, force = true) }
    override fun onEnabled(context: Context) { Hub.init(context); LinkService.start(context) }
}
/** The quick actions widget: lock the PC, ring it, send photos, take one for its Shelf, paste on it. */
class ActionsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { Hub.init(context); PcWidgets.refresh(context, force = true) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { Hub.init(context); PcWidgets.refresh(context, force = true) }
    override fun onEnabled(context: Context) { Hub.init(context); LinkService.start(context) }
}

object PcWidgets {
    private var drawn = ""; private var coverKey = 0; private var cover: Bitmap? = null; private var colours: ArtColors? = null
    fun any(context: Context): Boolean = runCatching {
        val m = AppWidgetManager.getInstance(context)
        m.getAppWidgetIds(ComponentName(context, PcWidget::class.java)).isNotEmpty() || m.getAppWidgetIds(ComponentName(context, ActionsWidget::class.java)).isNotEmpty()
    }.getOrDefault(false)

    /** Redraws the widgets when what they show changed (not for the position, which they don't show). */
    @Synchronized fun refresh(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val m = AppWidgetManager.getInstance(app)
        val pcIds = runCatching { m.getAppWidgetIds(ComponentName(app, PcWidget::class.java)) }.getOrDefault(IntArray(0))
        val actionIds = runCatching { m.getAppWidgetIds(ComponentName(app, ActionsWidget::class.java)) }.getOrDefault(IntArray(0))
        if (pcIds.isEmpty() && actionIds.isEmpty()) return
        val pc = Hub.pc(); val s = if (pc != null) Hub.status.value else null
        val hash = s?.coverHash?.contentHashCode() ?: 0
        val look = listOf(pc?.id, pc?.name, pc?.online, s?.available, s?.title, s?.artist, s?.playing, hash).joinToString("|")
        if (!force && look == drawn) return
        drawn = look
        if (hash != coverKey) {
            coverKey = hash; val art = s?.cover?.let { Art.bitmap(it, 256) }
            cover = art?.let { rounded(it, 196, 44f) }; colours = art?.let { Art.colors(it) }
        }
        // 1.4: like the app, the widgets take the colours of what plays on the PC (each drawn at its own size, as a widget
        // can't round or tint a picture itself on every Android).
        val tint = colours.takeIf { s?.available == true && pc?.online == true }
        for (id in pcIds) runCatching { m.updateAppWidget(id, nowPlaying(app, pc, s).also { backdrop(app, m, id, it, tint, 110) }) }
        for (id in actionIds) runCatching { m.updateAppWidget(id, actions(app).also { backdrop(app, m, id, it, tint, 50) }) }
    }

    /** The widget's glass: the app's own when nothing plays, else deep in the cover's colour, lit by its two brightest. */
    private fun backdrop(context: Context, m: AppWidgetManager, id: Int, v: RemoteViews, tint: ArtColors?, tallDp: Int) {
        if (tint == null) { v.setImageViewResource(R.id.w_bg, R.drawable.widget_glass); return }
        val density = context.resources.displayMetrics.density
        val o = m.getAppWidgetOptions(id)
        // Its size on the home screen: Android 12's exact sizes when it gives them, else the portrait size.
        val exact = if (android.os.Build.VERSION.SDK_INT >= 31) runCatching {
            @Suppress("DEPRECATION") o.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)?.firstOrNull()
        }.getOrNull() else null
        val wDp = exact?.width ?: o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat().takeIf { it > 0 } ?: 250f
        val hDp = exact?.height ?: o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).toFloat().takeIf { it > 0 } ?: tallDp.toFloat()
        // At most twice the dp size: sharp enough for a soft glow, and well within what a widget may carry.
        val scale = minOf(density, 2f); val w = (wDp * scale).toInt().coerceIn(40, 1400); val h = (hDp * scale).toInt().coerceIn(40, 1400)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(out)
        val r = 28f * scale; val box = RectF(0f, 0f, w.toFloat(), h.toFloat())
        fun alpha(color: Int, a: Float) = (color and 0xFFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)
        fun lift(color: Int, by: Float): Int { val hsv = FloatArray(3); android.graphics.Color.colorToHSV(color, hsv); hsv[2] = (hsv[2] + by).coerceIn(0f, 1f); return android.graphics.Color.HSVToColor(hsv) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), alpha(lift(tint.deep, .07f), .95f), alpha(tint.deep, .97f), Shader.TileMode.CLAMP)
        c.drawRoundRect(box, r, r, paint)
        // The light: the cover's colour behind the cover, its second colour rising from the far corner.
        val saved = c.save(); val clip = android.graphics.Path().apply { addRoundRect(box, r, r, android.graphics.Path.Direction.CW) }; c.clipPath(clip)
        paint.shader = android.graphics.RadialGradient(w * .12f, h * .38f, h * 1.1f, alpha(tint.vivid, .46f), alpha(tint.vivid, 0f), Shader.TileMode.CLAMP); c.drawRect(box, paint)
        paint.shader = android.graphics.RadialGradient(w * .96f, h * 1.05f, h * 1.2f, alpha(tint.second, .32f), alpha(tint.second, 0f), Shader.TileMode.CLAMP); c.drawRect(box, paint)
        paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, h * .5f, alpha(0xFFFFFF, .12f), alpha(0xFFFFFF, 0f), Shader.TileMode.CLAMP); c.drawRect(box, paint)
        c.restoreToCount(saved)
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = scale; paint.color = alpha(0xFFFFFF, .24f)
        c.drawRoundRect(RectF(scale / 2, scale / 2, w - scale / 2, h - scale / 2), r, r, paint)
        v.setImageViewBitmap(R.id.w_bg, out)
        if (tallDp > 60) v.setTextColor(R.id.w_where, alpha(tint.vivid, .85f))
    }

    private fun nowPlaying(context: Context, pc: PeerView?, s: PcStatus?): RemoteViews = RemoteViews(context.packageName, R.layout.widget_pc).apply {
        val playing = s?.available == true
        setTextViewText(R.id.w_title, when { pc == null -> "Pair with your PC"; !pc.online -> "${pc.name} is away"; playing -> s!!.title; else -> "Nothing playing" })
        setTextViewText(R.id.w_artist, when { pc == null -> "Open Arnav Island"; playing -> s!!.artist.ifBlank { s.app }; else -> "Play something on ${pc.name}" })
        setTextViewText(R.id.w_where, if (pc == null) "ARNAV ISLAND" else "ON ${pc.name.uppercase()}${if (pc.internet) "  ·  ANYWHERE" else ""}")
        val art = cover
        if (playing && art != null) setImageViewBitmap(R.id.w_cover, art) else setImageViewResource(R.id.w_cover, R.drawable.widget_cover_empty)
        setImageViewResource(R.id.w_play, if (s?.playing == true) R.drawable.ic_w_pause_dark else R.drawable.ic_w_play_dark)
        setContentDescription(R.id.w_play, if (s?.playing == true) "Pause" else "Play")
        val controls = if (playing && pc?.online == true) View.VISIBLE else View.GONE
        setViewVisibility(R.id.w_prev, controls); setViewVisibility(R.id.w_play, controls); setViewVisibility(R.id.w_next, controls)
        setOnClickPendingIntent(R.id.w_open, open(context, null, 90))
        setOnClickPendingIntent(R.id.w_prev, act(context, "prev")); setOnClickPendingIntent(R.id.w_play, act(context, "toggle")); setOnClickPendingIntent(R.id.w_next, act(context, "next"))
        quick(context, this)
    }
    private fun actions(context: Context): RemoteViews = RemoteViews(context.packageName, R.layout.widget_actions).apply { quick(context, this) }
    private fun quick(context: Context, v: RemoteViews) {
        v.setOnClickPendingIntent(R.id.w_lock, act(context, "lock"))
        v.setOnClickPendingIntent(R.id.w_ring, act(context, "ring"))
        v.setOnClickPendingIntent(R.id.w_send, open(context, "send_photos", 91))
        v.setOnClickPendingIntent(R.id.w_camera, open(context, "camera", 92))
        v.setOnClickPendingIntent(R.id.w_paste, PendingIntent.getActivity(context, 93, Intent(context, ClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }
    private fun open(context: Context, what: String?, code: Int) = PendingIntent.getActivity(context, code,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).apply { what?.let { putExtra("open", it) } },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun act(context: Context, what: String) = PendingIntent.getBroadcast(context, 100 + what.hashCode() % 1000, Intent(context, WidgetActions::class.java).setAction(what), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** A cover with rounded corners, as widgets can't round an image themselves. */
    private fun rounded(src: Bitmap, side: Int, radius: Float): Bitmap {
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888); val c = Canvas(out)
        val scale = side.toFloat() / minOf(src.width, src.height)
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setScale(scale, scale); postTranslate((side - src.width * scale) / 2f, (side - src.height * scale) / 2f) })
        }
        c.drawRoundRect(RectF(0f, 0f, side.toFloat(), side.toFloat()), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        return out
    }
}

/** The widgets' buttons that act without opening the app: the PC's music, lock, and find my PC. */
class WidgetActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Hub.init(context)
        val what = intent.action ?: return
        val pending = goAsync()
        Hub.scope.launch {
            try {
                if (Hub.link == null) LinkService.start(context)
                for (i in 0 until 40) { if (Hub.link != null && Hub.pc()?.online == true) break; delay(100) }
                val pc = Hub.pc()
                val reply = when (what) {
                    "prev" -> Hub.command(Proto.CMD_MEDIA, byteArrayOf(2), quiet = true)
                    "next" -> Hub.command(Proto.CMD_MEDIA, byteArrayOf(3), quiet = true)
                    "toggle" -> Hub.command(Proto.CMD_MEDIA, byteArrayOf(1), quiet = true)
                    "lock" -> Hub.command(Proto.CMD_LOCK, quiet = true)
                    "ring" -> Hub.command(Proto.CMD_RING_PC, quiet = true)
                    else -> return@launch
                }
                val said = when {
                    pc == null -> "Pair with your PC first"
                    reply == null -> "${pc.name} didn’t answer"
                    reply.status == Proto.NOT_ALLOWED -> "${pc.name} said no: turn on “My phone can control this PC” on its island"
                    !reply.ok -> "${pc.name} couldn’t do that"
                    what == "lock" -> "Locked ${pc.name}"
                    what == "ring" -> "Ringing ${pc.name}"
                    else -> null
                }
                if (said != null) withContext(kotlinx.coroutines.Dispatchers.Main) { Toast.makeText(context.applicationContext, said, Toast.LENGTH_SHORT).show() }
                if (what in setOf("prev", "next", "toggle")) { delay(300); Hub.refreshStatus(); PcWidgets.refresh(context) }
            } finally { pending.finish() }
        }
    }
}

/**
 * "Paste on PC" from a widget or a shortcut: a moment without a screen that reads this phone's clipboard (Android lets
 * only the app in front read it) and puts it on the PC's.
 */
class ClipActivity : Activity() {
    private var done = false
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); Hub.init(this); LinkService.start(this) }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val text = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull().orEmpty()
        if (text.isBlank()) { Toast.makeText(this, "Your clipboard is empty", Toast.LENGTH_SHORT).show(); finish(); return }
        val app = applicationContext
        Hub.scope.launch {
            for (i in 0 until 40) { if (Hub.link != null && Hub.pc()?.online == true) break; delay(100) }
            val pc = Hub.pc()
            val reply = if (pc != null) Hub.command(Proto.CMD_CLIP_SET, text.toByteArray(), quiet = true) else null
            val said = when { pc == null -> "Pair with your PC first"; reply == null -> "${pc.name} didn’t answer"; reply.status == Proto.NOT_ALLOWED -> "${pc.name} said no: turn on “My phone can control this PC” on its island"
                !reply.ok -> "${pc.name} couldn’t paste it"; else -> "On ${pc.name}’s clipboard" }
            withContext(kotlinx.coroutines.Dispatchers.Main) { Toast.makeText(app, said, Toast.LENGTH_SHORT).show() }
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
