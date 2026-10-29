package io.github.arnavdugad.arnavisland

import io.github.arnavdugad.arnavisland.link.Bytes
import io.github.arnavdugad.arnavisland.link.Proto
import kotlin.math.roundToInt

/**
 * 1.9: a live activity on the phone (a delivery, a ride, the way somewhere, a timer, a download, an Android 16 live
 * update) as the island's notice 7 (revision 10, island 0.27). [0x30, 7, u8 op (1 shown or updated, 2 ended), string key],
 * and for op 1: app name, package, title, text, status, u8 flags, u16 progress (0..1000), up to ten segments
 * {u16 length, u32 ARGB}, up to ten points {u16 position 0..1000, u32 ARGB}, u64 when (ms), u32 accent, then the app
 * icon, tracker, start and end icons (PNG <= 8 KB each), the large icon (<= 24 KB) and a snapshot (JPEG <= 64 KB).
 */
object LiveWire {
    /** The first revision whose PCs take live activities. */
    const val REVISION = 10
    const val NOTICE_LIVE = 7
    const val OP_SHOWN = 1; const val OP_ENDED = 2
    // Flags.
    const val ONGOING = 1; const val PROMOTED = 2; const val PROGRESS = 4; const val INDETERMINATE = 8
    const val COUNT_DOWN = 16; const val TRIP = 32; const val NAVIGATION = 64
    const val ICON_LIMIT = 8 * 1024; const val LARGE_LIMIT = 24 * 1024; const val SNAPSHOT_LIMIT = 64 * 1024

    /** A segment (its relative length) or a point (its position, 0..1000) of a tracking line, with its colour. */
    data class Mark(val at: Int, val color: Int)

    /** One live activity as read from its notification; pictures already encoded (null: none). */
    class Live(
        val key: String, val app: String, val pkg: String, val title: String, val text: String, val status: String,
        val flags: Int, val progress: Int, val segments: List<Mark> = emptyList(), val points: List<Mark> = emptyList(),
        val whenMs: Long = 0, val accent: Int = 0, val icon: ByteArray? = null, val tracker: ByteArray? = null,
        val start: ByteArray? = null, val end: ByteArray? = null, val large: ByteArray? = null, val snapshot: ByteArray? = null,
    )

    private fun Bytes.head(op: Int, key: String) = u8(Proto.FRAME_NOTICE).u8(NOTICE_LIVE).u8(op).string(key.take(200))
    private fun Bytes.pic(b: ByteArray?, limit: Int) = blob(b?.takeIf { it.size <= limit } ?: ByteArray(0))

    /** Op 1: shown or updated. Segment lengths are scaled down together when one wouldn't fit 16 bits. */
    fun shown(l: Live): ByteArray {
        val b = Bytes().head(OP_SHOWN, l.key).string(l.app.take(80)).string(l.pkg.take(200)).string(l.title.take(200))
            .string(l.text.take(400)).string(l.status.take(200)).u8(l.flags and 0x7F).u16(l.progress.coerceIn(0, 1000))
        val segs = l.segments.take(10); val top = segs.maxOfOrNull { it.at } ?: 0; val scale = if (top > 65535) 65535.0 / top else 1.0
        b.u8(segs.size); segs.forEach { b.u16((it.at.coerceAtLeast(0) * scale).roundToInt().coerceIn(0, 65535)).u32(it.color) }
        val pts = l.points.take(10); b.u8(pts.size); pts.forEach { b.u16(it.at.coerceIn(0, 1000)).u32(it.color) }
        b.u64(l.whenMs.coerceAtLeast(0)).u32(l.accent)
        return b.pic(l.icon, ICON_LIMIT).pic(l.tracker, ICON_LIMIT).pic(l.start, ICON_LIMIT).pic(l.end, ICON_LIMIT)
            .pic(l.large, LARGE_LIMIT).pic(l.snapshot, SNAPSHOT_LIMIT).build()
    }

    /** Op 2: the activity ended (its notification went, or stopped being one). */
    fun ended(key: String): ByteArray = Bytes().head(OP_ENDED, key).build()
}
