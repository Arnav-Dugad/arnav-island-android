package io.github.arnavdugad.arnavisland

import com.google.zxing.RGBLuminanceSource
import io.github.arnavdugad.arnavisland.link.Crypto
import io.github.arnavdugad.arnavisland.link.Link
import io.github.arnavdugad.arnavisland.link.Relay
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.2: pairing by the island's QR code: the link it carries, the key fingerprint in it, and the code as the island draws it. */
class PairingTest {
    @Test fun pairing_links_are_read() {
        val l = Relay.pairLink("arnavisland://pair/7K2PMX4Q?k=4f1c9a07d2b8e63a5c10")!!
        assertEquals("7K2PMX4Q", l.code)
        assertArrayEquals(byteArrayOf(0x4f, 0x1c, 0x9a.toByte(), 0x07, 0xd2.toByte(), 0xb8.toByte(), 0xe6.toByte(), 0x3a, 0x5c, 0x10), l.key)
        // Without a fingerprint, or with a malformed one: the code alone (this phone then asks too, as for a typed code).
        assertNull(Relay.pairLink("arnavisland://pair/7K2PMX4Q")!!.key)
        assertNull(Relay.pairLink("arnavisland://pair/7K2PMX4Q?k=4f1c")!!.key)
        assertNull(Relay.pairLink("arnavisland://pair/7K2PMX4Q?k=4f1c9a07d2b8e63a5cZZ")!!.key)
        // Any case, a trailing slash, a fragment, the code's dash, other parameters.
        assertEquals("7K2PMX4Q", Relay.pairLink("  ARNAVISLAND://pair/7k2p-mx4q/#x ")!!.code)
        assertEquals(10, Relay.pairLink("arnavisland://pair/7K2PMX4Q/?v=2&k=4F1C9A07D2B8E63A5C10")!!.key!!.size)
        // Not pairing links.
        assertNull(Relay.pairLink("https://example.com/pair/7K2PMX4Q"))
        assertNull(Relay.pairLink("arnavisland://pair/7K2PMX4"))
        assertNull(Relay.pairLink("arnavisland://pair/7K2PMX4Q1"))
        assertNull(Relay.pairLink("arnavisland://pair/IIIIIIII"))
        assertNull(Relay.pairLink(""))
    }

    @Test fun a_key_fingerprint_is_the_start_of_its_sha256() {
        val pub = ByteArray(64) { it.toByte() }
        assertArrayEquals(Crypto.sha256(pub).copyOf(10), Link.keyPrint(pub))
        assertEquals(10, Link.keyPrint(pub).size)
        assertFalse(Link.keyPrint(pub).contentEquals(Link.keyPrint(ByteArray(64))))
    }

    /**
     * The island's QR code for a pairing link: its encoder's own modules (version 4 at level M, the same as the reference
     * encoder's), drawn as the island draws them, and read the way the scanner reads a camera frame.
     */
    @Test fun the_islands_qr_code_is_read() {
        val rows = listOf("1fc5cab7f", "104cf9041", "17578ee5d", "175e5365d", "1753a435d", "10507d541", "1fd55557f", "001064e00", "17c0a057c", "143d51f45", "0dfa35992",
            "109453f2e", "155304230", "1b3571fc7", "12f0c68da", "178a97806", "1ed613498", "110dceb4b", "02f0f9d00", "01016fc7d", "0dce32592", "1d066ca61",
            "13403092e", "1424a0dad", "125af07f0", "001a32915", "1fc16435e", "105e32f1c", "175ba51fa", "175c3a4b7", "1759c5a4c", "104c94e34", "1fd05d54a")
        val size = rows.size; val scale = 6; val quiet = 4; val side = (size + 2 * quiet) * scale
        val white = 0xFFFFFFFF.toInt(); val ink = 0xFF0B0E14.toInt()
        val pixels = IntArray(side * side) { white }
        for (y in 0 until size) {
            val row = rows[y].toLong(16)
            for (x in 0 until size) if ((row shr (size - 1 - x)) and 1L == 1L)
                for (dy in 0 until scale) for (dx in 0 until scale) pixels[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = ink
        }
        val expected = "arnavisland://pair/7K2PMX4Q?k=4f1c9a07d2b8e63a5c10"
        assertEquals(expected, QrAnalyzer.read(RGBLuminanceSource(side, side, pixels)))
        // Light on dark (a screen's colours inverted): read too.
        assertEquals(expected, QrAnalyzer.read(RGBLuminanceSource(side, side, IntArray(pixels.size) { if (pixels[it] == white) ink else white })))
        val link = Relay.pairLink(expected)!!; assertEquals("7K2PMX4Q", link.code); assertEquals(10, link.key!!.size)
        // As a camera frame's brightness plane: rows padded to a wider stride, the last one cut short.
        val stride = side + 64; val plane = ByteArray((side - 1) * stride + side) { 0x7F }
        for (y in 0 until side) for (x in 0 until side) plane[y * stride + x] = (if (pixels[y * side + x] == white) 235 else 20).toByte()
        assertEquals(expected, QrAnalyzer.read(QrAnalyzer.luminance(plane, stride, side, side)))
        // Nothing to read in a blank frame.
        assertNull(QrAnalyzer.read(RGBLuminanceSource(side, side, IntArray(side * side) { white })))
    }
}
