package io.github.arnavdugad.arnavisland

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import io.github.arnavdugad.arnavisland.link.PcStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 1.8: the app's ambient light as a live wallpaper: a deep gradient, four soft blobs of colour drifting slowly and a
 * vignette, as [AmbientBackground] draws it behind the glass. Its colours are the cover's of what plays on your PC,
 * easing over a second when the song changes (mint and periwinkle when nothing plays), and it breathes while music
 * plays. It draws only while it shows: up to 20 frames a second, 8 in battery saver, and asks the PC what plays every
 * 5 s meanwhile.
 */
class AmbientWallpaper : WallpaperService() {
    companion object {
        /** Android's live wallpaper preview for this one, where it can be set (for Settings). */
        fun chooserIntent(context: Context): Intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, AmbientWallpaper::class.java))
            .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        private val RESTING = ArtColors(0xFFA5D8C5.toInt(), 0xFF8FA8FF.toInt(), 0xFF0B1220.toInt())
        /** Between two colours, channel by channel. */
        private fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = (((a ushr shift) and 0xFF) + (((b ushr shift) and 0xFF) - ((a ushr shift) and 0xFF)) * t).toInt().coerceIn(0, 255) shl shift
            return ch(24) or ch(16) or ch(8) or ch(0)
        }
        private fun alpha(color: Int, a: Float) = (color and 0xFFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)
    }

    override fun onCreate() { super.onCreate(); Hub.init(this) }
    override fun onCreateEngine(): Engine = Light()

    private inner class Light : Engine() {
        private val thread = HandlerThread("ambient").apply { start() }
        private val handler = Handler(thread.looper)
        private val power = getSystemService(PowerManager::class.java)
        private val lock = Any()
        @Volatile private var visible = false; @Volatile private var surface = false
        @Volatile private var target = RESTING; @Volatile private var playing = false; @Volatile private var xOffset = .5f
        private var poll: Job? = null; private var watch: Job? = null; @Volatile private var coverKey = 0
        // On the drawing thread only.
        private var from = RESTING; private var to = RESTING; private var shown = RESTING; private var easedAt = 0L
        private var phase = 0f; private var breath = 0f; private var last = 0L
        private val ground = Paint(); private val vignette = Paint(); private val blobs = Array(4) { Paint(Paint.ANTI_ALIAS_FLAG) }
        private val blobKeys = LongArray(4) { -1 }; private val matrix = Matrix()
        private var groundKey = ""; private var vignetteKey = ""

        private val frame = object : Runnable {
            override fun run() {
                val start = SystemClock.uptimeMillis(); draw()
                if (!visible) return
                // 20 frames a second while it breathes, 15 while still, 8 in battery saver: the drift is slow either way.
                val step = if (power?.isPowerSaveMode == true) 125L else if (playing || breath > 0f) 50L else 66L
                handler.postAtTime(this, start + step)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            handler.removeCallbacks(frame)
            if (visible) { last = 0L; follow(Hub.status.value); begin(); handler.post(frame) } else end()
        }
        override fun onSurfaceCreated(holder: SurfaceHolder) { super.onSurfaceCreated(holder); synchronized(lock) { surface = true } }
        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { super.onSurfaceChanged(holder, format, width, height); handler.post { draw() } }
        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            // Waits for a frame being drawn, so nothing draws on the surface once it's gone.
            synchronized(lock) { surface = false }; handler.removeCallbacks(frame); super.onSurfaceDestroyed(holder)
        }
        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int) { this.xOffset = xOffset }
        override fun onDestroy() { visible = false; end(); handler.removeCallbacksAndMessages(null); thread.quitSafely(); super.onDestroy() }

        /** While it shows: what plays on the PC, asked every 5 s (when the link is up), and its colours as they change. */
        private fun begin() {
            if (poll?.isActive != true) poll = Hub.scope.launch { while (isActive) { if (Hub.link != null) runCatching { Hub.refreshStatus() }; delay(5_000) } }
            if (watch?.isActive != true) watch = Hub.scope.launch { Hub.status.collect { follow(it) } }
        }
        private fun end() { poll?.cancel(); watch?.cancel(); poll = null; watch = null }

        /** The colours to ease to: the cover's while something plays on the PC, else the app's own. */
        private fun follow(s: PcStatus?) {
            val on = s != null && s.available && Hub.pc()?.online == true
            playing = on && s!!.playing
            val key = if (on) (s!!.coverHash?.contentHashCode() ?: 1) else 0
            if (key == coverKey) return
            coverKey = key
            if (!on) { target = RESTING; return }
            val cover = s!!.cover
            if (cover == null) { target = RESTING; return }
            Hub.scope.launch { val c = Art.bitmap(cover, 128)?.let { Art.colors(it) } ?: RESTING; if (coverKey == key) target = c }
        }

        private fun draw() {
            val now = SystemClock.elapsedRealtime()
            val dt = if (last == 0L) 0f else ((now - last) / 1000f).coerceIn(0f, .25f); last = now
            phase += dt
            breath = if (playing) (breath + dt / 1.4f).coerceAtMost(1f) else (breath - dt / 1.4f).coerceAtLeast(0f)
            val t = target
            if (t != to) { from = shown; to = t; easedAt = now }
            val k = ((now - easedAt) / 1000f).coerceIn(0f, 1f); val e = k * k * (3 - 2 * k)
            shown = if (k >= 1f) to else ArtColors(mix(from.vivid, to.vivid, e), mix(from.second, to.second, e), mix(from.deep, to.deep, e))
            synchronized(lock) {
                if (!surface) return
                val holder = surfaceHolder
                val c = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: runCatching { holder.lockCanvas() }.getOrNull() ?: return
                try { paint(c, shown) } finally { runCatching { holder.unlockCanvasAndPost(c) } }
            }
        }

        private fun paint(c: Canvas, p: ArtColors) {
            val w = c.width.toFloat(); val h = c.height.toFloat(); if (w <= 0f || h <= 0f) return
            val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            // The ground: deep in the cover's colour, darker (or, in the light, paler) towards the bottom.
            val top = if (dark) mix(p.deep, 0xFF000000.toInt(), .2f) else mix(p.deep, 0xFFFFFFFF.toInt(), .1f); val bottom = if (dark) 0xFF040509.toInt() else 0xFFEEF1F7.toInt()
            "$top|$bottom|$h".let { if (it != groundKey) { groundKey = it; ground.shader = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP) } }
            c.drawRect(0f, 0f, w, h, ground)
            // The light: four blobs drifting slowly (and a little the other way as the home screen pages), swelling with the music.
            val colours = intArrayOf(p.vivid, p.second, mix(p.vivid, p.second, .5f), mix(p.vivid, p.deep, .4f))
            val pan = (xOffset - .5f) * w * .12f
            for (i in 0 until 4) {
                val speed = .035f + i * .011f
                val pulse = 1f + breath * .06f * sin(phase * 1.9f + i * 1.3f)
                val cx = w * (.18f + .64f * ((i * .41f + .1f) % 1f)) + w * .18f * sin(phase * speed * 6.283f + i * 1.7f) - pan * (1f + i * .3f)
                val cy = h * (.08f + .24f * i) + h * .08f * cos(phase * speed * 5.1f + i * 2.3f)
                val radius = w * (.7f + .12f * ((i + 1) % 3)) * pulse
                val a = (if (dark) .5f else .55f) * (if (i == 0) 1f else .82f)
                // Each blob's gradient is made once per colour, then moved and scaled.
                val key = (colours[i].toLong() shl 8) or (a * 255).toLong()
                if (blobKeys[i] != key) {
                    blobKeys[i] = key
                    blobs[i].shader = RadialGradient(0f, 0f, 1000f, intArrayOf(alpha(colours[i], a), alpha(colours[i], a * .3f), alpha(colours[i], 0f)), null, Shader.TileMode.CLAMP)
                }
                matrix.setScale(radius / 1000f, radius / 1000f); matrix.postTranslate(cx, cy); blobs[i].shader.setLocalMatrix(matrix)
                c.drawCircle(cx, cy, radius, blobs[i])
            }
            // A vignette keeps the edges calm and the icons readable.
            "$w|$h|$dark".let { if (it != vignetteKey) { vignetteKey = it; vignette.shader = RadialGradient(w / 2, h * .42f, max(w, h) * .8f, 0, alpha(0, if (dark) .45f else .08f), Shader.TileMode.CLAMP) } }
            c.drawRect(0f, 0f, w, h, vignette)
        }
    }
}
