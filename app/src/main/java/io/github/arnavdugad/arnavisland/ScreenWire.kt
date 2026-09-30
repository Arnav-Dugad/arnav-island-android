package io.github.arnavdugad.arnavisland

import io.github.arnavdugad.arnavisland.link.Bytes
import io.github.arnavdugad.arnavisland.link.Proto
import io.github.arnavdugad.arnavisland.link.Reader
import java.io.ByteArrayOutputStream

/** 1.6: the pieces of a screen's frames (revision 7, island 0.24), either way. */
object ScreenWire {
    const val CHUNK = 128 * 1024

    /** Asking for the PC's screen here: the largest picture this phone shows (long side first), fps, and bits a second (0: as the path allows). */
    fun askPc(longest: Int, shortest: Int, fps: Int, bitrate: Int = 0) = Bytes().u8(Proto.SCREEN_REQUEST).u8(1).u16(longest).u16(shortest).u8(fps).u32(bitrate).build()
    /** Offering this phone's screen to the PC: its size, fps and name. */
    fun offerPhone(width: Int, height: Int, fps: Int, name: String) = Bytes().u8(Proto.SCREEN_REQUEST).u8(2).u16(width).u16(height).u8(fps).string(name).build()

    /** The PC's answer: status (0 ok, 1 not allowed, 2 unsupported, 3 failed), the size and rate it chose, its encoder. */
    data class Reply(val status: Int, val width: Int, val height: Int, val fps: Int, val bitrate: Long, val encoder: String)
    fun reply(f: ByteArray): Reply? {
        val r = Reader(f); if (r.u8() != Proto.SCREEN_REPLY) return null
        return Reply(r.u8() ?: return null, r.u16() ?: 0, r.u16() ?: 0, r.u8() ?: 0, r.u32() ?: 0, r.string(512) ?: "")
    }

    /** One encoded frame as the frames that carry it (128 KB at most each). */
    fun frames(number: Int, key: Boolean, pts100ns: Long, data: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(); var at = 0
        do {
            val n = minOf(CHUNK, data.size - at); val last = at + n >= data.size
            out += Bytes().u8(Proto.SCREEN_VIDEO).u8((if (key) 1 else 0) or (if (at == 0) 2 else 0) or (if (last) 4 else 0)).u32(number).u64(pts100ns).raw(data.copyOfRange(at, at + n)).build()
            at += n
        } while (at < data.size)
        return out
    }
    fun feedback(last: Int, decodeMs: Int, kbps: Int, fps: Int) = Bytes().u8(Proto.SCREEN_FEEDBACK).u32(last).u16(decodeMs.coerceIn(0, 65535)).u32(kbps).u8(fps.coerceIn(0, 255)).build()
    fun input(frame: ByteArray) = byteArrayOf(Proto.SCREEN_INPUT.toByte()) + frame

    /** Frames put back together: whole ones come out with whether they're key frames, their number and time. */
    class Assembler {
        private val buffer = ByteArrayOutputStream(); private var current = -1; private var key = false; private var pts = 0L
        data class Frame(val number: Int, val key: Boolean, val pts: Long, val data: ByteArray)
        fun add(f: ByteArray): Frame? {
            if (f.size < 14 || (f[0].toInt() and 0xFF) != Proto.SCREEN_VIDEO) return null
            val r = Reader(f); r.u8(); val flags = r.u8() ?: return null; val n = (r.u32() ?: return null).toInt(); val t = r.u64() ?: return null
            if (flags and 2 != 0) { buffer.reset(); current = n; key = flags and 1 != 0; pts = t } else if (n != current) { buffer.reset(); current = -1; return null }
            buffer.write(f, 14, f.size - 14)
            if (flags and 4 == 0) return null
            val whole = Frame(n, key, pts, buffer.toByteArray()); buffer.reset(); current = -1; return whole
        }
    }

    /** NAL units of an Annex B frame: (type, start after the start code, end). */
    fun nals(d: ByteArray): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        fun code(i: Int) = if (i + 3 <= d.size && d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) 3 else if (i + 4 <= d.size && d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1) 4 else 0
        var i = 0; while (i < d.size && code(i) == 0) i++
        while (i < d.size) { val s = i + code(i); var j = s; while (j < d.size && code(j) == 0) j++; if (s < j) out += Triple(d[s].toInt() and 0x1F, s, j); i = j }
        return out
    }
    /** The SPS and PPS in a frame, each with its start code (for a decoder's csd-0 and csd-1). */
    fun parameterSets(d: ByteArray): Pair<ByteArray, ByteArray>? {
        val units = nals(d); val sps = units.firstOrNull { it.first == 7 } ?: return null; val pps = units.firstOrNull { it.first == 8 } ?: return null
        val start = byteArrayOf(0, 0, 0, 1)
        return (start + d.copyOfRange(sps.second, sps.third)) to (start + d.copyOfRange(pps.second, pps.third))
    }
}
