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
    /**
     * 1.4: the battery forecast. Five days of the same routine (off the charger at 8, 5% an hour, on it at 8 pm): at 2 pm
     * today at 70% it lasts 14 hours more, until 4 am; a day that runs faster than usual is caught by its last stretch.
     */
    @Test fun the_battery_forecast_follows_its_own_history() {
        val zone = java.time.ZoneOffset.UTC; val day0 = java.time.LocalDate.of(2026, 9, 20)
        fun minute(day: Int, h: Int, m: Int = 0) = (day0.plusDays(day.toLong()).atTime(h, m).toEpochSecond(zone) / 60).toInt()
        val history = ArrayList<BatteryForecast.Sample>()
        fun routine(day: Int, until: Int, pace: Int = 12) {
            // One percent every [pace] minutes from 8 am.
            var level = 100; var at = minute(day, 8); history += BatteryForecast.Sample(at, 100, false)
            while (at + pace <= until) { at += pace; level -= 1; history += BatteryForecast.Sample(at, level, false) }
        }
        for (d in 0 until 5) { routine(d, minute(d, 20)); history += BatteryForecast.Sample(minute(d, 20), 40, true); history += BatteryForecast.Sample(minute(d, 21), 100, true) }
        routine(5, minute(5, 14))
        assertEquals(70, history.last().level)
        val now = minute(5, 14).toLong() * 60_000
        val until = BatteryForecast.lastsUntil(history, 70, now, zone)!!
        assertEquals(minute(6, 4).toLong() * 60_000.0, until.toDouble(), 20 * 60_000.0)
        // Today twice as fast (a percent every 6 minutes since 11 am): sooner than the routine says, and later than the fast pace alone.
        val fast = history.filter { it.minute <= minute(5, 11) }.toMutableList(); var level = fast.last().level; var at = minute(5, 11)
        while (at + 6 <= minute(5, 14)) { at += 6; level -= 1; fast += BatteryForecast.Sample(at, level, false) }
        val soon = BatteryForecast.lastsUntil(fast, level, now, zone)!!
        assertTrue(soon < minute(5, 14).toLong() * 60_000 + level / 5.0 * 3_600_000)
        assertTrue(soon > minute(5, 14).toLong() * 60_000 + level / 10.0 * 3_600_000)
        // Nothing to go on yet: no forecast.
        assertEquals(null, BatteryForecast.lastsUntil(listOf(BatteryForecast.Sample(minute(5, 13, 59), 70, false)), 70, now, zone))
    }

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
