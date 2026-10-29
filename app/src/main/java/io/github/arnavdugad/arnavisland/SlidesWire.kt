package io.github.arnavdugad.arnavisland

import io.github.arnavdugad.arnavisland.link.Bytes
import io.github.arnavdugad.arnavisland.link.Reader

/**
 * 1.9 (#35): the PC's PowerPoint, read and steered (remote command 29, revision 10, island 0.27). Asked [0, u8 thumbs]
 * to read it or [1, u16 index] to go to a slide (1-based, in the running show); answered [1, u8 state, u16 index,
 * u16 count, deck title, slide title, notes, blob current slide JPEG, blob next slide JPEG, u32 seconds since the show
 * started] (a blob of length 0 is none; pictures only when asked).
 */
object SlidesWire {
    /** The first revision whose PCs answer it. */
    const val REVISION = 10
    const val CMD_SLIDES = 29
    /** PowerPoint isn't running; it's open without a slideshow; a slideshow is running. */
    const val NOT_RUNNING = 0; const val OPEN = 1; const val SHOWING = 2

    class Slides(val state: Int, val index: Int, val count: Int, val deck: String, val title: String, val notes: String,
                 val current: ByteArray?, val next: ByteArray?, val seconds: Long)

    /** Reading it; [thumbs]: with the current and next slides' pictures. */
    fun read(thumbs: Boolean) = Bytes().u8(0).u8(if (thumbs) 1 else 0).build()
    /** Going to slide [index] (1-based). */
    fun go(index: Int) = Bytes().u8(1).u16(index.coerceIn(1, 65535)).build()

    fun parse(p: ByteArray): Slides? {
        val r = Reader(p); if (r.u8() != 1) return null
        val state = r.u8() ?: return null; val index = r.u16() ?: return null; val count = r.u16() ?: return null
        val deck = r.string(2000) ?: return null; val title = r.string(2000) ?: return null; val notes = r.string(64_000) ?: return null
        val current = r.blob(400_000)?.takeIf { it.isNotEmpty() }; val next = r.blob(400_000)?.takeIf { it.isNotEmpty() }
        return Slides(state, index, count, deck, title, notes, current, next, r.u32() ?: 0L)
    }
}
