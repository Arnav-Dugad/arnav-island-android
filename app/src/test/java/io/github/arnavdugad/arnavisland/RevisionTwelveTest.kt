package io.github.arnavdugad.arnavisland

import io.github.arnavdugad.arnavisland.LiveWire.Live
import io.github.arnavdugad.arnavisland.link.Link
import io.github.arnavdugad.arnavisland.link.NoticeWire
import io.github.arnavdugad.arnavisland.link.Proto
import io.github.arnavdugad.arnavisland.link.Reader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.12: island 0.29's notifications in full (PROTOCOL-12), laid out as the island reads them. */
class RevisionTwelveTest {
    @Test fun the_numbers_are_the_specs() {
        assertEquals(12, Proto.REVISION); assertEquals(Proto.REVISION, NoticeWire.REVISION)
        assertEquals(0xE1, NoticeWire.MARK); assertEquals(255, NoticeWire.DISMISS); assertEquals(128, LiveWire.RIDE)
    }

    /** The frame as 0.28 reads it, then the block: an older island stops before it and loses nothing it knew. */
    @Test fun a_notification_in_full() {
        val full = NoticeWire.Full("com.whatsapp", "com.whatsapp|family", "Family", NoticeWire.GROUP or NoticeWire.CONVERSATION, 1_761_700_000_000,
            listOf(NoticeWire.Message("Mum", "Dinner at 8", 10, false), NoticeWire.Message("", "  ", 11, true), NoticeWire.Message("", "Coming", 12, true)),
            ByteArray(500) { 1 }, ByteArray(2_000) { 2 }, listOf("On my way", " ", "On my way", "Late"))
        val r = Reader(Link.notificationFrame("WhatsApp", "Mum", "Dinner at 8", "Pixel", false, null, "n7", listOf("Reply" to true, "Mark as read" to false, "Mute" to false), full))
        assertEquals(Proto.FRAME_NOTICE, r.u8()); assertEquals(Proto.NOTICE_NOTIFICATION, r.u8()); assertEquals(0, r.u8())
        assertEquals("WhatsApp\nMum\nDinner at 8\nPixel", r.string()); assertEquals(0, r.blob(1 shl 20)!!.size); assertEquals("n7", r.string())
        assertEquals(3, r.u8()); repeat(3) { r.u8(); r.bytes(r.u8()!!) }
        // The block.
        assertEquals(NoticeWire.MARK, r.u8()); val length = r.u32(); assertEquals(r.left.toLong(), length)
        assertEquals("com.whatsapp", r.string()); assertEquals("com.whatsapp|family", r.string()); assertEquals("Family", r.string())
        assertEquals(NoticeWire.GROUP or NoticeWire.CONVERSATION, r.u8()); assertEquals(1_761_700_000_000L, r.u64())
        assertEquals(2, r.u8())   // the blank one is left out
        assertEquals("Mum", r.string()); assertEquals("Dinner at 8", r.string()); assertEquals(10L, r.u64()); assertEquals(0, r.u8())
        assertEquals("", r.string()); assertEquals("Coming", r.string()); assertEquals(12L, r.u64()); assertEquals(1, r.u8())
        assertArrayEquals(ByteArray(500) { 1 }, r.blob(NoticeWire.AVATAR_LIMIT)); assertArrayEquals(ByteArray(2_000) { 2 }, r.blob(NoticeWire.PICTURE_LIMIT))
        assertEquals(2, r.u8()); assertEquals("On my way", r.string()); assertEquals("Late", r.string())
        assertEquals(0, r.left)
    }

    @Test fun limits_are_kept() {
        val many = List(12) { NoticeWire.Message("S".repeat(200), "t".repeat(1500), it.toLong(), false) }
        val r = Reader(NoticeWire.block(NoticeWire.Full("p".repeat(300), "c".repeat(300), "T".repeat(300), 0xFFF, -5, many, ByteArray(NoticeWire.AVATAR_LIMIT + 1), ByteArray(NoticeWire.PICTURE_LIMIT + 1), List(9) { "r$it".repeat(100) })))
        r.u8(); r.u32()
        assertEquals(200, r.string()!!.length); assertEquals(120, r.string()!!.length); assertEquals(200, r.string()!!.length); assertEquals(0xFF, r.u8()); assertEquals(0L, r.u64())
        assertEquals(8, r.u8()); repeat(8) { assertEquals(120, r.string()!!.length); assertEquals(1000, r.string()!!.length); r.u64(); r.u8() }
        assertEquals(0, r.blob(1 shl 20)!!.size); assertEquals(0, r.blob(1 shl 20)!!.size)   // too big: left out
        assertEquals(5, r.u8()); repeat(5) { assertEquals(120, r.string()!!.length) }
        assertEquals(0, r.left)
    }

    /** A ride is marked apart from deliveries, and the words of the app's own view follow its pictures. */
    @Test fun a_ride_with_its_words() {
        val words = "Ramesh · KA 01 AB 1234\nPIN 5821\n" + "x".repeat(900)
        val r = Reader(LiveWire.shown(Live("k", "Ola", "com.olacabs.customer", "Driver is on the way", "", "", LiveWire.ONGOING or LiveWire.TRIP or LiveWire.RIDE, 0, words = words)))
        r.u8(); r.u8(); r.u8(); repeat(6) { r.string() }
        assertEquals(1 or 32 or 128, r.u8()); r.u16(); assertEquals(0, r.u8()); assertEquals(0, r.u8()); r.u64(); r.u32()
        repeat(6) { assertEquals(0, r.blob(1 shl 20)!!.size) }
        val said = r.string()!!; assertEquals(LiveWire.WORDS_LIMIT, said.length); assertTrue(said.startsWith("Ramesh · KA 01 AB 1234\nPIN 5821"))
        assertEquals(0, r.left)
    }
}
