package io.github.arnavdugad.arnavisland

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import io.github.arnavdugad.arnavisland.link.Link
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 1.7: a photo (or screenshot) you just took shows on your PCs' islands, with Paste and Shelf there. Only its small
 * picture goes at first; the photo itself goes only when a PC asks for one it was told about, within ten minutes. On
 * with Devices › Photos you take, which asks for Android's photo access; it watches only while the link runs.
 */
object RecentPhotos {
    private var observer: ContentObserver? = null
    private var pending: Job? = null
    /** What each PC was told about: photo id → when (only those can be asked for). */
    private val told = HashMap<String, HashMap<Long, Long>>()
    private val announced = LinkedHashSet<Long>()

    fun permission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    fun permitted(context: Context) = ContextCompat.checkSelfPermission(context, permission()) == PackageManager.PERMISSION_GRANTED
    fun wanted() = Hub.prefs.getBoolean("recent_photos", false)

    /** Starts (or stops) watching, as the setting and the permission say. */
    fun sync(context: Context) { if (wanted() && permitted(context)) start(context.applicationContext) else stop(context.applicationContext) }

    private fun start(context: Context) {
        if (observer != null) return
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // A camera writes a photo in steps: look once they've settled.
                pending?.cancel(); pending = Hub.scope.launch { delay(700); check(context) }
            }
        }
        runCatching { context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o); observer = o }
    }
    private fun stop(context: Context) { observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }; observer = null }

    private fun check(context: Context) {
        val link = Hub.link ?: return
        val pcs = Hub.peers.value.filter { it.paired && !it.phone && it.online && it.revision >= 8 }
        if (pcs.isEmpty()) return
        val now = System.currentTimeMillis() / 1000
        val columns = mutableListOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT)
        if (Build.VERSION.SDK_INT >= 29) columns += MediaStore.Images.Media.RELATIVE_PATH
        val where = if (Build.VERSION.SDK_INT >= 29) "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.IS_PENDING} = 0" else "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val found = ArrayList<Triple<Long, String, LongArray>>()
        runCatching {
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns.toTypedArray(), where, arrayOf((now - 30).toString()), "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext() && found.size < 3) {
                    val id = c.getLong(0); if (id in announced) continue
                    // The camera's own photos and screenshots, not what other apps save (downloads, messages).
                    val path = if (Build.VERSION.SDK_INT >= 29) c.getString(5).orEmpty() else ""
                    if (Build.VERSION.SDK_INT >= 29 && !(path.startsWith("DCIM/") || path.contains("Screenshots", ignoreCase = true))) continue
                    found += Triple(id, c.getString(1).orEmpty(), longArrayOf(c.getLong(2), c.getLong(3), c.getLong(4)))
                }
            }
        }
        for ((id, name, info) in found.reversed()) {
            announced += id; while (announced.size > 64) announced.remove(announced.first())
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            val picture = Previews.of(context, uri, side = 480, limit = 90 * 1024) ?: continue
            val frame = Link.photoFrame(id, name, info[0], info[1].toInt(), info[2].toInt(), picture)
            for (pc in pcs) if (link.notice(pc.id, frame)) synchronized(told) { told.getOrPut(pc.id) { HashMap() }[id] = System.currentTimeMillis() }
        }
    }

    /** A photo a PC may ask for: one it was told about in the last ten minutes. */
    fun uriOf(context: Context, peer: String, id: Long): Uri? {
        val at = synchronized(told) { told[peer]?.get(id) } ?: return null
        if (System.currentTimeMillis() - at > 10 * 60_000) return null
        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    }
}
