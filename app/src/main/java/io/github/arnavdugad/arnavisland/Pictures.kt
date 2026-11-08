package io.github.arnavdugad.arnavisland

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** 1.12: pictures for the island's notification cards (a sender's face, a photo a message carries), within the wire's limits. */
object Pictures {
    /** A face: the drawable filling a [side]-pixel square (cropped to it), as PNG when that fits [limit], else JPEG. */
    fun square(d: Drawable, side: Int, limit: Int): ByteArray? {
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: side; val h = d.intrinsicHeight.takeIf { it > 0 } ?: side
        val s = side.toFloat() / minOf(w, h); val dw = (w * s).roundToInt(); val dh = (h * s).roundToInt()
        val b = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        try {
            val l = (side - dw) / 2; val t = (side - dh) / 2; d.setBounds(l, t, l + dw, t + dh); d.draw(Canvas(b))
            val png = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            return if (png.size <= limit) png else jpeg(b, side, limit)
        } finally { b.recycle() }
    }
    /** A drawable at its own shape, at most [maxSide] pixels along its longer side, as JPEG within [limit]. */
    fun drawn(d: Drawable, maxSide: Int, limit: Int): ByteArray? {
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: maxSide; val h = d.intrinsicHeight.takeIf { it > 0 } ?: maxSide
        val s = minOf(1f, maxSide.toFloat() / max(w, h)); val b = Bitmap.createBitmap(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()), Bitmap.Config.ARGB_8888)
        try { d.setBounds(0, 0, b.width, b.height); d.draw(Canvas(b)); return jpeg(b, maxSide, limit) } finally { b.recycle() }
    }
    /** An image this app may read (a message's photo), decoded small, as JPEG within [limit]; null when it can't be read. */
    fun uri(context: Context, uri: Uri, maxSide: Int, limit: Int): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1; while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val b = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        try { jpeg(b, maxSide, limit) } finally { b.recycle() }
    }.getOrNull()
    /** A bitmap at most [maxSide] along its longer side, as JPEG within [limit]: lower quality first, then smaller. */
    fun jpeg(source: Bitmap, maxSide: Int, limit: Int): ByteArray? {
        var b = source
        if (max(b.width, b.height) > maxSide) { val s = maxSide.toFloat() / max(b.width, b.height); b = Bitmap.createScaledBitmap(b, max(1, (b.width * s).roundToInt()), max(1, (b.height * s).roundToInt()), true) }
        try {
            repeat(4) {
                for (q in intArrayOf(82, 70, 55, 40)) { val out = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, q, out); if (out.size() <= limit) return out.toByteArray() }
                val next = Bitmap.createScaledBitmap(b, max(1, (b.width * .7f).roundToInt()), max(1, (b.height * .7f).roundToInt()), true)
                if (b !== source) b.recycle(); b = next
            }
            return null
        } finally { if (b !== source) b.recycle() }
    }
}
