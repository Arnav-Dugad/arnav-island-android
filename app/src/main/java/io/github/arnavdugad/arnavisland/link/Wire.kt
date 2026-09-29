package io.github.arnavdugad.arnavisland.link

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream

/**
 * Arnav Island's sharing protocol, as Windows speaks it (ShareService.cpp). Protocol 2; revision 2 (announced "2.2")
 * adds what a phone needs: R (remote: the PC's status, media keys, volume, lock, clipboard, links), N (the phone's
 * battery and notifications on the island) and F (find my phone). A phone adds ";phone" to its announcement.
 */
object Proto {
    const val VERSION = 2
    const val REVISION = 2
    const val TCP_PORT = 47820
    const val UDP_PORT = 47821
    val MAGIC = byteArrayOf('A'.code.toByte(), 'R'.code.toByte(), 'N'.code.toByte(), 'V'.code.toByte())
    const val ANNOUNCE = "ARNAVSHARE1"
    const val CHUNK = 256 * 1024
    const val MAX_FRAME = CHUNK + 64

    const val MODE_PAIR = 'P'.code; const val MODE_SEND = 'S'.code; const val MODE_MUSIC = 'H'.code
    const val MODE_LIST = 'L'.code; const val MODE_TAKE = 'T'.code
    const val MODE_REMOTE = 'R'.code; const val MODE_NOTICE = 'N'.code; const val MODE_FIND = 'F'.code

    // Frames inside a transfer (all sealed).
    const val FRAME_OFFER = 1; const val FRAME_DATA = 1; const val FRAME_END = 2; const val FRAME_HEADER = 3; const val FRAME_BATCH_END = 4
    const val FRAME_MUSIC = 5; const val FRAME_SHELF = 6; const val FRAME_TAKE = 7
    // Revision 2.
    const val FRAME_REQUEST = 0x20; const val FRAME_REPLY = 0x21
    const val FRAME_NOTICE = 0x30; const val FRAME_NOTICE_ACK = 0x31
    const val FRAME_RING = 0x40; const val FRAME_RING_ACK = 0x41

    // Remote commands.
    const val CMD_STATUS = 1; const val CMD_MEDIA = 2; const val CMD_VOLUME = 3; const val CMD_MUTE = 4; const val CMD_LOCK = 5
    const val CMD_CLIP_GET = 6; const val CMD_CLIP_SET = 7; const val CMD_SEEK = 8; const val CMD_OPEN = 9
    // Remote answers.
    const val OK = 0; const val NOT_ALLOWED = 1; const val UNSUPPORTED = 2; const val FAILED = 3
    // Notices.
    const val NOTICE_STATUS = 1; const val NOTICE_NOTIFICATION = 2

    const val SHELF_MAX = 32
    const val PREVIEW_LIMIT = 6 * 1024
    const val COVER_LIMIT = 96 * 1024
    const val MAX_FILE = 16L shl 30
    const val MAX_TOTAL = 1L shl 40
    const val MAX_FILES = 20000
}

/** A little-endian byte builder. */
class Bytes {
    private val out = ByteArrayOutputStream()
    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun u16(v: Int) = apply { u8(v); u8(v shr 8) }
    fun u32(v: Long) = apply { for (i in 0..3) out.write(((v ushr (8 * i)) and 0xFF).toInt()) }
    fun u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
    fun u64(v: Long) = apply { for (i in 0..7) out.write(((v ushr (8 * i)) and 0xFF).toInt()) }
    fun f64(v: Double) = u64(java.lang.Double.doubleToRawLongBits(v))
    fun raw(b: ByteArray) = apply { out.write(b) }
    fun text(s: String) = raw(s.toByteArray(Charsets.UTF_8))
    /** A UTF-8 string with a 32-bit length first. */
    fun string(s: String) = apply { val b = s.toByteArray(Charsets.UTF_8); u32(b.size); raw(b) }
    fun blob(b: ByteArray) = apply { u32(b.size); raw(b) }
    fun build(): ByteArray = out.toByteArray()
}

/** Reads a little-endian frame; every read fails (null) rather than overrunning. */
class Reader(val b: ByteArray, var at: Int = 0) {
    val left get() = b.size - at
    fun u8(): Int? = if (left >= 1) b[at++].toInt() and 0xFF else null
    fun u16(): Int? { if (left < 2) return null; val v = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8); at += 2; return v }
    fun u32(): Long? { if (left < 4) return null; var v = 0L; for (i in 0..3) v = v or ((b[at + i].toLong() and 0xFF) shl (8 * i)); at += 4; return v }
    fun u64(): Long? { if (left < 8) return null; var v = 0L; for (i in 0..7) v = v or ((b[at + i].toLong() and 0xFF) shl (8 * i)); at += 8; return v }
    /** A double; a value that isn't finite reads as 0 (as Windows does), a missing one as null. */
    fun f64(): Double? { val u = u64() ?: return null; val v = java.lang.Double.longBitsToDouble(u); return if (v.isFinite()) v else 0.0 }
    fun bytes(n: Int): ByteArray? { if (n < 0 || left < n) return null; val r = b.copyOfRange(at, at + n); at += n; return r }
    fun rest(): ByteArray = b.copyOfRange(at, b.size).also { at = b.size }
    fun string(limit: Int = 1 shl 20): String? { val n = u32() ?: return null; if (n > limit) return null; return bytes(n.toInt())?.toString(Charsets.UTF_8) }
    fun blob(limit: Int): ByteArray? { val n = u32() ?: return null; if (n > limit) return null; return bytes(n.toInt()) }
}

/** Framing: a 32-bit little-endian length, then the bytes. */
object Frames {
    fun write(out: OutputStream, frame: ByteArray) {
        require(frame.size <= Proto.MAX_FRAME)
        val h = ByteArray(4) { (frame.size ushr (8 * it)).toByte() }
        out.write(h); out.write(frame); out.flush()
    }
    fun read(input: DataInputStream): ByteArray {
        val h = ByteArray(4); input.readFully(h)
        val n = (h[0].toLong() and 0xFF) or ((h[1].toLong() and 0xFF) shl 8) or ((h[2].toLong() and 0xFF) shl 16) or ((h[3].toLong() and 0xFF) shl 24)
        if (n > Proto.MAX_FRAME) throw EOFException("frame too large")
        return ByteArray(n.toInt()).also { input.readFully(it) }
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
fun String.unhex(): ByteArray? {
    if (length % 2 != 0) return null
    return runCatching { ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() } }.getOrNull()
}

/** A peer's name as shown: printable, at most 64 characters. */
fun cleanName(n: String): String {
    val out = n.filter { it.code >= 32 && it.code != 127 }.take(64)
    return out.ifEmpty { "A PC" }
}

/** A received relative path, each part made safe for any file system; "." and ".." dropped, at most 24 parts. */
fun safePath(path: String): List<String> =
    path.split('/', '\\').map { it.trim(' ').trimEnd('.') }.filter { it.isNotEmpty() && it != "." && it != ".." }.map(::safeName).take(24)

fun safeName(name: String): String {
    var n = name.substringAfterLast('/').substringAfterLast('\\')
    n = n.map { c -> if (c.code < 32 || c.code == 127 || c in "<>:\"/\\|?*") '_' else c }.joinToString("").trim(' ', '.')
    if (n.length > 120) { val dot = n.lastIndexOf('.'); val ext = if (dot >= 0 && n.length - dot <= 12) n.substring(dot) else ""; n = n.take(120 - ext.length) + ext }
    if (n.isEmpty()) return "file"
    val stem = n.substringBefore('.').uppercase()
    val reserved = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
    return if (stem in reserved) "_$n" else n
}
