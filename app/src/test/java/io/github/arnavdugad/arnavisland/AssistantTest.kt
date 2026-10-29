package io.github.arnavdugad.arnavisland

import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.11: what PC Voice (and Gemini's words) turn into: an action and its arguments, and which app was meant. */
class AssistantTest {
    private fun said(text: String) = Assistant.guess(text)

    @Test fun one_apps_sound_is_not_the_whole_pc() {
        assertEquals(Assistant.MUTE_APP to "discord", said("mute Discord").let { it.first to it.second["app"] })
        assertEquals(Assistant.UNMUTE_APP to "spotify", said("unmute spotify on my PC").let { it.first to it.second["app"] })
        assertEquals(Assistant.MUTE, said("mute").first)
        assertEquals(Assistant.MUTE, said("mute my PC").first)
        assertEquals(Assistant.UNMUTE, said("unmute the sound").first)
    }

    @Test fun whats_using_the_pc_asks_for_its_top_apps() {
        assertEquals(Assistant.TOP_APPS to "memory", said("what's using my PC's memory").let { it.first to it.second["by"] })
        assertEquals(Assistant.TOP_APPS to "cpu", said("what is slowing down my computer").let { it.first to it.second["by"] })
        assertEquals(Assistant.TOP_APPS to "cpu", said("what's using the most CPU").let { it.first to it.second["by"] })
    }

    @Test fun opening_an_app_and_opening_a_site_stay_apart() {
        assertEquals(Assistant.OPEN_APP to "spotify", said("open Spotify on my PC").let { it.first to it.second["app"] })
        assertEquals(Assistant.OPEN_APP to "visual studio code", said("launch Visual Studio Code").let { it.first to it.second["app"] })
        assertEquals(Assistant.OPEN, said("open youtube.com").first)
        // Not an app: kept whole, so the command bar gets it as said if nothing matches.
        assertEquals("start a timer for 10 minutes", said("start a timer for 10 minutes").second["text"])
    }

    @Test fun the_app_meant_is_found_by_its_name() {
        val apps = listOf("Microsoft Edge", "Spotify", "Visual Studio Code", "Discord", "Steam", "Windows Terminal")
        assertEquals(1, Assistant.match("spotify", apps))
        assertEquals(2, Assistant.match("code", apps))
        assertEquals(2, Assistant.match("visual studio", apps))
        assertEquals(0, Assistant.match("edge", apps))
        assertEquals(5, Assistant.match("terminal", apps))
        assertEquals(-1, Assistant.match("photoshop", apps))
    }

    @Test fun the_older_actions_still_mean_what_they_did() {
        assertEquals(Assistant.LOCK, said("lock my pc").first)
        assertEquals(Assistant.VOLUME to "30", said("set the volume to 30").let { it.first to it.second["value"] })
        assertEquals(Assistant.NEXT, said("skip this song").first)
    }
}
