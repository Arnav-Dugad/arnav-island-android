package io.github.arnavdugad.arnavisland

import io.github.arnavdugad.arnavisland.link.Bytes
import io.github.arnavdugad.arnavisland.link.Link
import io.github.arnavdugad.arnavisland.link.LyricsLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The small pieces of 1.1's logic that decide what reaches the PC, and when. */
class LogicTest {
    @Test fun typing_sends_what_changed() {
        assertEquals(0 to "a", typedDiff("", "a"))
        assertEquals(0 to "lo", typedDiff("hel", "hello"))
        assertEquals(1 to "", typedDiff("hello", "hell"))
        // Autocorrect: "teh" becomes "the " (two backspaces, then the new ending).
        assertEquals(2 to "he ", typedDiff("teh", "the "))
        assertEquals(3 to "", typedDiff("abc", ""))
        assertEquals(0 to "", typedDiff("same", "same"))
    }

    @Test fun lyric_lines_follow_the_song() {
        val lines = listOf(LyricsLine(0.0, "a", emptyList()), LyricsLine(10.5, "b", emptyList()), LyricsLine(20.0, "c", emptyList()), LyricsLine(33.0, "d", emptyList()))
        assertEquals(0, lineAt(lines, 0.0)); assertEquals(0, lineAt(lines, 10.4)); assertEquals(1, lineAt(lines, 10.5))
        assertEquals(2, lineAt(lines, 25.0)); assertEquals(3, lineAt(lines, 999.0))
        assertEquals(-1, lineAt(listOf(LyricsLine(4.0, "x", emptyList())), 1.0)); assertEquals(-1, lineAt(emptyList(), 5.0))
        assertTrue(synced(lines)); assertFalse(synced(listOf(LyricsLine(0.0, "a", emptyList()), LyricsLine(0.0, "b", emptyList()))))
    }

    @Test fun the_dial_turns_the_short_way_round() {
        assertEquals(10f, dialTurn(170f, 180f), 1e-4f)
        // Across the -180/180 seam (atan2's), a small turn stays small.
        assertEquals(20f, dialTurn(170f, -170f), 1e-4f); assertEquals(-20f, dialTurn(-170f, 170f), 1e-4f)
        assertEquals(-5f, dialTurn(5f, 0f), 1e-4f)
        assertEquals(0, detent(0f)); assertEquals(20, detent(1f)); assertEquals(10, detent(.5f)); assertEquals(1, detent(.049f)); assertEquals(20, detent(1.3f))
    }

    @Test fun the_sky_is_read_from_the_island_words() {
        assertEquals(Sky.Rain, skyOf("14° Rain")); assertEquals(Sky.Drizzle, skyOf("9° Drizzle")); assertEquals(Sky.Storm, skyOf("21° Thunderstorm"))
        assertEquals(Sky.Snow, skyOf("-2° Snow")); assertEquals(Sky.Fog, skyOf("5° Fog")); assertEquals(Sky.None, skyOf("18° Partly cloudy"))
        assertEquals(Sky.None, skyOf("")); assertEquals(Sky.None, skyOf("25° Clear"))
    }

    @Test fun the_ring_screen_beats_with_the_vibration() {
        assertEquals(1f, Ringer.beat(0), 1e-4f); assertEquals(0f, Ringer.beat(800), 1e-4f); assertEquals(1f, Ringer.beat(1100), 1e-4f)
        assertEquals(0f, Ringer.beat(2000), 1e-4f); assertEquals(1f, Ringer.beat(Ringer.CYCLE), 1e-4f)
        assertTrue(Ringer.beat(350) in .2f..0.3f)
        // A ring from each beat, spreading for 1.6 s.
        assertEquals(listOf(0f), Ringer.rings(0)); assertEquals(2, Ringer.rings(1200).size); assertTrue(Ringer.rings(2400).all { it in 0f..1f })
    }

    @Test fun a_callers_number_loses_its_invisible_direction_marks() {
        assertEquals("555-9876", Mirror.plain("‪555-9876‬"))
        assertEquals("Mum", Mirror.plain("  ⁨Mum⁩ "))
        assertEquals("", Mirror.plain(null))
    }

    @Test fun the_status_says_whether_the_universal_clipboard_is_on() {
        fun status(flags: Int) = Bytes().u16(flags).f64(1.0).f64(2.0).u8(50).u8(80).u8(10).u8(0).string("A\nB\nC\nPC\n").build()
        assertTrue(Link.parseStatus(status(1 or 512), null)!!.clipboard)
        assertFalse(Link.parseStatus(status(1), null)!!.clipboard)
    }
}
