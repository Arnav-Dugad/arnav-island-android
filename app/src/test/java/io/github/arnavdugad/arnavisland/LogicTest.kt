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

    @Test fun a_wake_packet_is_ff_then_the_address_sixteen_times() {
        val mac = byteArrayOf(0x00, 0x1A, 0x2B, 0x3C, 0x4D, 0x5E)
        val p = Wake.magic(mac)
        assertEquals(102, p.size); for (i in 0 until 6) assertEquals(0xFF.toByte(), p[i])
        for (k in 0 until 16) for (i in 0 until 6) assertEquals(mac[i], p[6 + k * 6 + i])
        assertEquals("192.168.1.255", Wake.subnetBroadcast("192.168.1.20", 24)); assertEquals("10.0.15.255", Wake.subnetBroadcast("10.0.12.7", 22))
        assertEquals(null, Wake.subnetBroadcast("nonsense", 24)); assertEquals(null, Wake.subnetBroadcast("10.0.0.1", 32))
    }

    @Test fun revision_nine_answers_are_read() {
        val b = io.github.arnavdugad.arnavisland.link.Bytes()
        val pb = io.github.arnavdugad.arnavisland.link.IslandWire.playback(b.u8(1).u8(1 or 2 or 4 or 8).u8(2).u16(1).string("Song").string("Artist").f64(201.5).build())!!
        assertEquals(true, pb.shuffleKnown); assertEquals(true, pb.shuffle); assertEquals(2, pb.repeat); assertEquals(true, pb.islandPlayer); assertEquals("Song", pb.upNext.single().title); assertEquals(201.5, pb.upNext.single().duration, 1e-9)
        val apps = io.github.arnavdugad.arnavisland.link.IslandWire.mixer(io.github.arnavdugad.arnavisland.link.Bytes().u8(1).u8(1).u32(4242).string("Spotify").u8(60).u8(33).u8(1 or 4).blob(ByteArray(0)).build())!!
        assertEquals(4242L, apps.single().pid); assertEquals(60, apps.single().volume); assertEquals(true, apps.single().muted); assertEquals(true, apps.single().active); assertEquals(null, apps.single().icon)
        val levels = io.github.arnavdugad.arnavisland.link.IslandWire.levels(io.github.arnavdugad.arnavisland.link.Bytes().u8(1).u8(255).u8(0).u8(2).u8(0).u8(255).build())!!
        assertEquals(1f, levels.level, 1e-6f); assertEquals(false, levels.resting); assertEquals(listOf(0f, 1f), levels.bands)
        val nics = io.github.arnavdugad.arnavisland.link.IslandWire.machine(io.github.arnavdugad.arnavisland.link.Bytes().u8(1).u8(1).raw(byteArrayOf(1, 2, 3, 4, 5, 6)).string("Ethernet").string("192.168.1.9").u8(24).build())!!
        assertEquals("Ethernet", nics.single().name); assertEquals(24, nics.single().prefix)
        // A cut-short answer reads as nothing rather than as garbage.
        assertEquals(null, io.github.arnavdugad.arnavisland.link.IslandWire.playback(byteArrayOf(1, 3)))
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
