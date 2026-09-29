package io.github.arnavdugad.arnavisland

import org.junit.Assert.*
import org.junit.Test

class UpdatesTest {
    @Test fun versions_compare_by_number_not_text() {
        assertEquals(listOf(1, 10, 0), Updates.version("1.10.0"))
        assertEquals(listOf(1, 2, 3), Updates.version("1.2.3-preview.1"))
        assertNull(Updates.version("1.2")); assertNull(Updates.version("v1.2.3")); assertNull(Updates.version("latest"))
        assertTrue(Updates.newer(listOf(1, 10, 0), listOf(1, 9, 9)))
        assertFalse(Updates.newer(listOf(1, 0, 0), listOf(1, 0, 0)))
        assertFalse(Updates.newer(listOf(0, 9, 0), listOf(1, 0, 0)))
    }

    @Test fun releases_are_read_as_github_lists_them() {
        val json = """[
          {"tag_name":"v1.0.0","name":"v1.0.0 — first","body":"## Hello\n- one","published_at":"2026-09-29T10:00:00Z","html_url":"https://x/1","prerelease":false,"draft":false,
           "assets":[{"name":"ArnavIsland-1.0.0.apk","browser_download_url":"https://x/a.apk","size":1234},{"name":"ArnavIsland-1.0.0.apk.sha256","browser_download_url":"https://x/a.apk.sha256","size":90}]},
          {"tag_name":"v1.1.0","name":"","body":"","published_at":"2026-10-01T10:00:00Z","html_url":"https://x/2","prerelease":true,"draft":false,"assets":[]},
          {"tag_name":"v2.0.0","name":"draft","body":"","draft":true,"assets":[]},
          {"tag_name":"nightly","name":"n","body":"","assets":[]}
        ]"""
        val list = Updates.parse(json)
        assertEquals(listOf("v1.1.0", "v1.0.0"), list.map { it.tag })
        val first = list[1]
        assertEquals("https://x/a.apk", first.apk); assertEquals(1234L, first.apkSize); assertEquals("https://x/a.apk.sha256", first.sha)
        assertEquals("v1.1.0", list[0].name); assertNull("a release without an APK can't be installed", list[0].apk)
        // Only releases with an APK are offered, and only newer ones.
        assertEquals(null, Updates.newest(list.map { it.copy(version = listOf(0, 0, 1)) }))
    }

    @Test fun checksum_files_are_read_leniently() {
        val hex = "a".repeat(64)
        assertEquals(hex, Updates.shaIn("$hex  ArnavIsland-1.0.0.apk\n"))
        assertEquals(hex, Updates.shaIn("SHA256 (x.apk) = ${hex.uppercase()}"))
        assertNull(Updates.shaIn("not a checksum"))
        assertNull(Updates.shaIn("a".repeat(63)))
    }
}
