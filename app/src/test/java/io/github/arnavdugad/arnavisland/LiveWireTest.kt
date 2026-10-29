package io.github.arnavdugad.arnavisland

import io.github.arnavdugad.arnavisland.LiveWire.Live
import io.github.arnavdugad.arnavisland.LiveWire.Mark
import io.github.arnavdugad.arnavisland.link.Proto
import io.github.arnavdugad.arnavisland.link.Reader
import org.junit.Assert.*
import org.junit.Test

/** 1.9: notice 7 (live activities) laid out as PROTOCOL-10 says, read back with the app's own Reader. */
class LiveWireTest {
    @Test fun shownFrameReadsBack() {
        val icon = ByteArray(300) { 1 }; val tracker = ByteArray(200) { 2 }; val large = ByteArray(20_000) { 3 }; val snap = ByteArray(50_000) { 4 }
        val l = Live("0|in.swiggy.android|7|null|10123", "Swiggy", "in.swiggy.android", "Arriving in 8 min", "Ravi is on the way with your order", "8 min",
            LiveWire.ONGOING or LiveWire.PROMOTED or LiveWire.PROGRESS or LiveWire.TRIP, 640,
            listOf(Mark(30, 0xFFFC8019.toInt()), Mark(70, 0xFF60B246.toInt())), listOf(Mark(300, 0xFFFFFFFF.toInt())),
            1_761_700_000_000, 0xFFFC8019.toInt(), icon = icon, tracker = tracker, large = large, snapshot = snap)
        val r = Reader(LiveWire.shown(l))
        assertEquals(Proto.FRAME_NOTICE, r.u8()); assertEquals(7, r.u8()); assertEquals(LiveWire.OP_SHOWN, r.u8())
        assertEquals(l.key, r.string()); assertEquals("Swiggy", r.string()); assertEquals("in.swiggy.android", r.string())
        assertEquals("Arriving in 8 min", r.string()); assertEquals("Ravi is on the way with your order", r.string()); assertEquals("8 min", r.string())
        assertEquals(1 or 2 or 4 or 32, r.u8()); assertEquals(640, r.u16())
        assertEquals(2, r.u8()); assertEquals(30, r.u16()); assertEquals(0xFFFC8019L, r.u32()); assertEquals(70, r.u16()); assertEquals(0xFF60B246L, r.u32())
        assertEquals(1, r.u8()); assertEquals(300, r.u16()); assertEquals(0xFFFFFFFFL, r.u32())
        assertEquals(1_761_700_000_000L, r.u64()); assertEquals(0xFFFC8019L, r.u32())
        assertArrayEquals(icon, r.blob(8 * 1024)); assertArrayEquals(tracker, r.blob(8 * 1024))
        assertEquals(0, r.blob(8 * 1024)!!.size); assertEquals(0, r.blob(8 * 1024)!!.size)
        assertArrayEquals(large, r.blob(24 * 1024)); assertArrayEquals(snap, r.blob(64 * 1024))
        assertEquals(0, r.left)
    }

    @Test fun limitsAreKept() {
        val many = List(14) { Mark(100_000 * (it + 1), it) }
        val l = Live("k".repeat(300), "A", "p", "t".repeat(500), "x".repeat(900), "s".repeat(300), 0xFF, 5000, many, List(12) { Mark(2000, 0) },
            -5, 0, icon = ByteArray(9000), large = ByteArray(30_000), snapshot = ByteArray(70_000))
        val r = Reader(LiveWire.shown(l)); r.u8(); r.u8(); r.u8()
        assertEquals(200, r.string()!!.length); r.string(); r.string()
        assertEquals(200, r.string()!!.length); assertEquals(400, r.string()!!.length); assertEquals(200, r.string()!!.length)
        assertEquals(0x7F, r.u8()); assertEquals(1000, r.u16())
        assertEquals(10, r.u8()); val lengths = List(10) { r.u16()!!.also { r.u32() } }
        assertEquals(65535, lengths.last()); assertTrue(lengths.zipWithNext().all { (a, b) -> a < b })
        assertEquals(10, r.u8()); repeat(10) { assertEquals(1000, r.u16()); r.u32() }
        assertEquals(0L, r.u64()); assertEquals(0L, r.u32())
        repeat(6) { assertEquals(0, r.blob(1 shl 20)!!.size) }   // too big to send: left out
        assertEquals(0, r.left)
    }

    @Test fun endedFrame() {
        val r = Reader(LiveWire.ended("0|com.ubercab|1|null|10123"))
        assertEquals(Proto.FRAME_NOTICE, r.u8()); assertEquals(7, r.u8()); assertEquals(LiveWire.OP_ENDED, r.u8())
        assertEquals("0|com.ubercab|1|null|10123", r.string()); assertEquals(0, r.left)
    }
}
