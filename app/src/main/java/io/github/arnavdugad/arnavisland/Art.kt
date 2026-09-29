package io.github.arnavdugad.arnavisland

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The colours a cover lends the app: a vivid one (the accent), a second one, and a deep one for the background. */
data class ArtColors(val vivid: Int, val second: Int, val deep: Int)

object Art {
    /** A picture's bitmap, at most [side] pixels on its longer side; null when it isn't one. */
    fun bitmap(bytes: ByteArray, side: Int = 512): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 8192 || bounds.outHeight > 8192) return null
        var sample = 1; while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /**
     * Its colours: hues are weighed by how vivid and how common they are, so a small bright detail doesn't win over the
     * picture's own colour, and grey pictures fall back to a soft accent.
     */
    fun colors(bitmap: Bitmap): ArtColors {
        val small = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        val bins = Array(12) { FloatArray(4) } // weight, r, g, b
        var ar = 0f; var ag = 0f; var ab = 0f; val hsv = FloatArray(3)
        for (y in 0 until 24) for (x in 0 until 24) {
            val c = small.getPixel(x, y); val r = AColor.red(c); val g = AColor.green(c); val b = AColor.blue(c)
            ar += r; ag += g; ab += b
            AColor.RGBToHSV(r, g, b, hsv)
            val w = hsv[1] * hsv[1] * (1f - abs(hsv[2] - .62f)) ; if (hsv[2] < .12f || w <= .01f) continue
            val bin = bins[((hsv[0] / 30f).toInt()).coerceIn(0, 11)]; bin[0] += w; bin[1] += r * w; bin[2] += g * w; bin[3] += b * w
        }
        if (small !== bitmap) small.recycle()
        val n = 24f * 24f; val average = AColor.rgb((ar / n).toInt(), (ag / n).toInt(), (ab / n).toInt())
        val order = bins.indices.sortedByDescending { bins[it][0] }
        fun colorOf(i: Int): Int? { val b = bins[i]; if (b[0] < .6f) return null; return AColor.rgb((b[1] / b[0]).toInt(), (b[2] / b[0]).toInt(), (b[3] / b[0]).toInt()) }
        val first = colorOf(order[0]) ?: 0xFFA5D8C5.toInt()
        // The second colour: the next strongest hue at least 60 degrees away, else the first turned a little.
        val second = order.drop(1).firstOrNull { abs(it - order[0]).let { d -> min(d, 12 - d) } >= 2 }?.let { colorOf(it) } ?: shift(first, 40f)
        return ArtColors(vivid(first), vivid(second), deep(average))
    }
    /** Brighter and more saturated, so it reads as light on dark glass. */
    fun vivid(c: Int): Int { val hsv = FloatArray(3); AColor.colorToHSV(c, hsv); hsv[1] = (hsv[1] * 1.15f).coerceIn(.35f, .85f); hsv[2] = hsv[2].coerceIn(.78f, .98f); return AColor.HSVToColor(hsv) }
    fun deep(c: Int): Int { val hsv = FloatArray(3); AColor.colorToHSV(c, hsv); hsv[1] = (hsv[1] * .9f).coerceAtMost(.7f); hsv[2] = hsv[2].coerceIn(.08f, .2f); return AColor.HSVToColor(hsv) }
    private fun shift(c: Int, degrees: Float): Int { val hsv = FloatArray(3); AColor.colorToHSV(c, hsv); hsv[0] = (hsv[0] + degrees) % 360f; return AColor.HSVToColor(hsv) }
}
