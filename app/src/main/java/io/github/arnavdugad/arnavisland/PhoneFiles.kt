package io.github.arnavdugad.arnavisland

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.util.Size
import androidx.core.content.ContextCompat
import io.github.arnavdugad.arnavisland.link.FilesWire
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max

/**
 * 1.13 (island 0.30, query 5): your PCs browse this phone's files and take one (or a folder) over. Only while
 * Settings › "My PCs can browse this phone's files" is on (off at first) and Android allows the app all files access;
 * only the phone's own storage (internal and its cards), never hidden files, never through a symbolic link, never
 * anything written or deleted. What a PC takes goes as an ordinary transfer, shown here as it goes.
 */
object PhoneFiles {
    const val PREF = "pcFiles"
    fun enabled() = Hub.prefs.getBoolean(PREF, false)

    /** Whether Android lets the app read every file: all files access (Android 11+), else the storage permission. */
    fun permitted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    /** Android's page for all files access for this app (Android 11+). */
    fun accessIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    private fun primary(): File = Environment.getExternalStorageDirectory()
    /** The phone's storage: internal first (a place), then its cards and USB drives (volumes). */
    private fun roots(context: Context): List<FilesWire.Root> {
        val out = arrayListOf(FilesWire.Root(primary(), "Internal storage", FilesWire.PLACE))
        runCatching {
            val sm = context.getSystemService(StorageManager::class.java)
            for (v in sm.storageVolumes) {
                if (v.isPrimary || v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                val dir = if (Build.VERSION.SDK_INT >= 30) v.directory else volumeDir(context, v.uuid)
                if (dir != null && out.none { it.dir == dir }) out += FilesWire.Root(dir, v.getDescription(context) ?: dir.name, FilesWire.VOLUME)
            }
        }
        return out
    }
    /** Android 9 and 10: a card's top folder, from the app's own folder on it (…/Android/data/<app>/files). */
    private fun volumeDir(context: Context, uuid: String?): File? {
        uuid ?: return null
        return context.getExternalFilesDirs(null).filterNotNull().map { it.path }.firstOrNull { "/$uuid/" in "$it/" }?.let { File(it.substringBefore("/Android/data/")) }
    }
    private fun places(context: Context): List<FilesWire.Root> {
        val p = primary()
        return listOf(FilesWire.Root(p, "Internal storage", FilesWire.PLACE)) + listOf(
            Environment.DIRECTORY_DOWNLOADS to "Downloads", "${Environment.DIRECTORY_DCIM}/Camera" to "Camera", Environment.DIRECTORY_PICTURES to "Pictures",
            Environment.DIRECTORY_DOCUMENTS to "Documents", Environment.DIRECTORY_MUSIC to "Music", Environment.DIRECTORY_MOVIES to "Movies",
        ).map { (d, title) -> FilesWire.Root(File(p, d), title, FilesWire.PLACE) }
    }

    /** A picture's thumbnail, a JPEG of at most 8 KB (null when it can't be read). */
    private fun thumb(f: File): ByteArray? {
        if (f.length() <= 0 || f.length() > 256L * 1024 * 1024) return null
        val b = if (Build.VERSION.SDK_INT >= 29) ThumbnailUtils.createImageThumbnail(f, Size(160, 160), null) else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1; while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 160) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        return try { Pictures.jpeg(b, 160, FilesWire.THUMB_LIMIT) } finally { b.recycle() }
    }

    private fun lister(context: Context) = FilesWire.Lister({ roots(context) }, { places(context) }, ::thumb)

    /** A PC's question (query 5): its answer, or null when it's malformed. */
    fun answer(context: Context, peer: String, payload: ByteArray): ByteArray? {
        if (!enabled()) return FilesWire.state(FilesWire.OFF)
        if (!permitted(context)) return FilesWire.state(FilesWire.PERMISSION)
        val lister = lister(context)
        return when (val r = FilesWire.request(payload) ?: return null) {
            is FilesWire.Listing -> lister.answer(r)
            is FilesWire.Send -> {
                if (lister.resolve(r.path) == null) return FilesWire.state(FilesWire.NOT_FOUND)
                // A folder's tree is gathered (and sent) away from the PC's question, which is answered at once.
                Hub.scope.launch {
                    val (title, folder, sources) = lister.sources(r.path) ?: return@launch
                    Hub.link?.send(peer, sources, title, folder = folder, ask = r.ask)
                }
                FilesWire.state(FilesWire.LISTED)
            }
        }
    }
}
