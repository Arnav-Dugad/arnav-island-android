package io.github.arnavdugad.arnavisland

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.util.Size
import java.io.ByteArrayOutputStream

/**
 * 1.7: small pictures of what's sent, for the island to show while it arrives: a photo or a video's frame, at most
 * [side] pixels on its long side, as a JPEG under [limit] bytes. Null for anything else (documents, audio).
 */
object Previews {
    fun of(context: Context, uri: Uri, side: Int = 320, limit: Int = 90 * 1024): ByteArray? = runCatching {
        val type = context.contentResolver.getType(uri).orEmpty()
        if (!type.startsWith("image/") && !type.startsWith("video/")) return null
        val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri, Size(side, side), null)
            else if (type.startsWith("image/")) decodeSmall(context, uri, side) ?: return null
            else return null
        jpeg(bitmap, side, limit)
    }.getOrNull()

    /** A bitmap as a JPEG no larger than [side] on its long side and under [limit] bytes (a little less sharp if it must). */
    fun jpeg(bitmap: Bitmap, side: Int, limit: Int): ByteArray? {
        val scale = minOf(1f, side.toFloat() / maxOf(bitmap.width, bitmap.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * scale).toInt()), maxOf(1, (bitmap.height * scale).toInt()), true) else bitmap
        var quality = 82
        while (quality >= 40) {
            val out = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= limit) return out.toByteArray()
            quality -= 12
        }
        return null
    }

    private fun decodeSmall(context: Context, uri: Uri, side: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?.let { ThumbnailUtils.extractThumbnail(it, minOf(it.width, side), minOf(it.height, side)) }
    }
}
