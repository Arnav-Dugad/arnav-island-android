package io.github.arnavdugad.arnavisland

import io.github.arnavdugad.arnavisland.link.Bytes
import io.github.arnavdugad.arnavisland.link.IslandWire
import io.github.arnavdugad.arnavisland.link.Proto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.11: island 0.28's answers (PROTOCOL-11), read as the spec lays them out: each after its version byte 1. */
class RevisionElevenTest {
    @Test fun the_numbers_are_the_specs() {
        assertEquals(11, Proto.REVISION)
        assertEquals(listOf(31, 32, 33, 34, 35, 37), listOf(Proto.CMD_BATTERY_HEALTH, Proto.CMD_STORAGE, Proto.CMD_STARTUP, Proto.CMD_NETWORK, Proto.CMD_UPDATES, Proto.CMD_APPS))
    }

    @Test fun battery_health_is_read() {
        val h = IslandWire.batteryHealth(Bytes().u8(1).u8(1).u32(50_000).u32(42_500).u32(312).string("Li-ion").string("SMP").string("DELL 1VX1H")
            .u32(30_000).u32(-8_500).u32(11_800).build())!!
        assertTrue(h.present); assertEquals(50_000L, h.designMwh); assertEquals(42_500L, h.fullMwh); assertEquals(312L, h.cycles)
        assertEquals("Li-ion", h.chemistry); assertEquals("SMP", h.manufacturer); assertEquals("DELL 1VX1H", h.device)
        assertEquals(30_000L, h.currentMwh); assertEquals(-8_500, h.rateMw); assertEquals(11_800L, h.voltageMv); assertEquals(.85, h.health, 1e-9)
        // A desktop, by hand: no battery, cycles unknown (0xFFFFFFFF), the rest zero and empty.
        val desktop = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(1 + 1 + 4 + 4 + 4 + 3 * 4 + 4 + 4 + 4, desktop.size)
        val d = IslandWire.batteryHealth(desktop)!!
        assertFalse(d.present); assertEquals(-1L, d.cycles); assertEquals(-1.0, d.health, 0.0); assertEquals("", d.chemistry)
        assertNull(IslandWire.batteryHealth(desktop.copyOf(desktop.size - 1)))
        assertNull(IslandWire.batteryHealth(byteArrayOf(2) + desktop.copyOfRange(1, desktop.size)))
    }

    @Test fun storage_is_read_while_measuring_and_after() {
        val gb = 1L shl 30
        val b = Bytes().u8(1).u8(0).u8(42).u8(2)
            .string("C:\\").string("Windows").u8(0).u64(512 * gb).u64(100 * gb).u8(1)
            .string("E:\\").string("").u8(1).u64(64 * gb).u64(60 * gb).u8(0)
            .u8(2).string("Apps").u8(0).u64(120 * gb).string("System").u8(1).u64(30 * gb).build()
        val s = IslandWire.storage(b)!!
        assertFalse(s.ready); assertEquals(42, s.progress); assertEquals(2, s.drives.size)
        val c = s.drives[0]; assertEquals("C:\\", c.root); assertEquals("Windows", c.label); assertEquals(0, c.kind); assertTrue(c.system); assertEquals(412 * gb, c.used)
        assertEquals(1, s.drives[1].kind); assertFalse(s.drives[1].system); assertEquals(4 * gb, s.drives[1].used)
        assertEquals(listOf("Apps" to 0, "System" to 1), s.groups.map { it.name to it.kind }); assertEquals(30 * gb, s.groups[1].bytes)
        val ready = IslandWire.storage(Bytes().u8(1).u8(1).u8(100).u8(0).u8(0).build())!!
        assertTrue(ready.ready); assertEquals(100, ready.progress); assertTrue(ready.drives.isEmpty() && ready.groups.isEmpty())
        assertNull(IslandWire.storage(b.copyOf(b.size - 3)))
    }

    @Test fun startup_apps_are_read_with_their_icons() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val b = Bytes().u8(1).u8(2)
            .string("0:Spotify").string("Spotify").string("\"C:\\Users\\a\\Spotify.exe\" /background").u8(0).u8(1).u8(1).blob(png)
            .string("1:SecurityHealth").string("Windows Security notification icon").string("%windir%\\system32\\SecurityHealthSystray.exe").u8(1).u8(0).u8(0).blob(ByteArray(0)).build()
        val list = IslandWire.startup(b)!!
        assertEquals(2, list.size)
        val a = list[0]; assertEquals("0:Spotify", a.id); assertEquals("Spotify", a.name); assertEquals(0, a.source); assertTrue(a.enabled); assertTrue(a.canChange); assertArrayEquals(png, a.icon)
        val w = list[1]; assertEquals(1, w.source); assertFalse(w.enabled); assertFalse(w.canChange); assertNull(w.icon)
        // Its icon's length cut off: nothing, rather than a half-read list.
        assertNull(IslandWire.startup(b.copyOf(b.size - 2)))
    }

    @Test fun the_network_is_read() {
        val b = Bytes().u8(1).u8(2)
            .string("Wi-Fi").string("Intel(R) Wi-Fi 6E AX211").u8(1).u8(1).u8(1).string("a4:b1:c1:00:11:22").u64(1_200_900_000)
            .u8(1).string("192.168.1.20/24").u8(2).string("fe80::1/64").string("2001:db8::20/64").string("192.168.1.1").u8(2).string("1.1.1.1").string("8.8.8.8")
            .string("Home").u8(78).string("5 GHz").u16(44).string("WPA3-Personal")
            .string("Ethernet").string("Realtek PCIe GbE").u8(0).u8(0).u8(0).string("00:1a:2b:3c:4d:5e").u64(0)
            .u8(0).u8(0).string("").u8(0).string("").u8(255).string("").u16(0).string("").build()
        val list = IslandWire.network(b)!!
        assertEquals(2, list.size)
        val w = list[0]; assertTrue(w.wifi); assertTrue(w.up); assertTrue(w.isDefault); assertEquals(1_200_900_000L, w.linkBps)
        assertEquals(listOf("192.168.1.20/24"), w.ipv4); assertEquals(listOf("fe80::1/64", "2001:db8::20/64"), w.ipv6); assertEquals("192.168.1.1", w.gateway); assertEquals(listOf("1.1.1.1", "8.8.8.8"), w.dns)
        assertEquals("Home", w.ssid); assertEquals(78, w.signal); assertEquals("5 GHz", w.band); assertEquals(44, w.channel); assertEquals("WPA3-Personal", w.security)
        val e = list[1]; assertEquals(0, e.kind); assertFalse(e.up); assertFalse(e.isDefault); assertTrue(e.ipv4.isEmpty()); assertEquals(-1, e.signal); assertEquals(0, e.channel)
        assertNull(IslandWire.network(b.copyOf(b.size - 1)))
        assertEquals("1.2 Gb/s", bpsText(1_200_900_000)); assertEquals("100 Mb/s", bpsText(100_000_000)); assertEquals("—", bpsText(0))
    }

    @Test fun windows_update_is_read() {
        val b = Bytes().u8(1).u8(3).u64(1_790_000_000).u64(1_789_000_000).u8(2)
            .string("2026-10 Cumulative Update for Windows 11 (KB5050001)").u8(0).u64(512L shl 20).u8(1).u8(1)
            .string("Intel - Display - 31.0.101.5000").u8(1).u64(0).u8(0).u8(0).build()
        val u = IslandWire.updates(b)!!
        assertEquals(3, u.state); assertEquals(1_790_000_000L, u.lastChecked); assertEquals(1_789_000_000L, u.lastInstalled); assertEquals(2, u.items.size)
        assertEquals(0, u.items[0].kind); assertEquals(512L shl 20, u.items[0].size); assertTrue(u.items[0].downloaded); assertTrue(u.items[0].needsRestart)
        assertEquals(1, u.items[1].kind); assertEquals(0L, u.items[1].size); assertFalse(u.items[1].downloaded)
        val none = IslandWire.updates(Bytes().u8(1).u8(1).u64(0).u64(0).u8(0).build())!!
        assertEquals(1, none.state); assertTrue(none.items.isEmpty())
        assertNull(IslandWire.updates(b.copyOf(10)))
    }

    @Test fun the_start_menus_apps_are_read() {
        val icon = byteArrayOf(1, 2, 3)
        val b = Bytes().u8(1).u16(3).string("Microsoft.WindowsCalculator_8wekyb3d8bbwe!App").string("Calculator").blob(icon)
            .string("C:\\ProgramData\\Microsoft\\Windows\\Start Menu\\Programs\\Firefox.lnk").string("Firefox").blob(ByteArray(0))
            .string("C:\\Tools\\notepad++.exe").string("Notepad++").blob(ByteArray(0)).build()
        val apps = IslandWire.apps(b)!!
        assertEquals(listOf("Calculator", "Firefox", "Notepad++"), apps.map { it.name })
        assertEquals("Microsoft.WindowsCalculator_8wekyb3d8bbwe!App", apps[0].id); assertArrayEquals(icon, apps[0].icon); assertNull(apps[1].icon)
        // The count is a u16: more than 255 apps.
        val many = Bytes().u8(1).u16(300).apply { repeat(300) { string("id$it").string("App $it").blob(ByteArray(0)) } }.build()
        assertEquals(300, IslandWire.apps(many)!!.size)
        assertNull(IslandWire.apps(b.copyOf(b.size - 1)))
    }

    @Test fun storage_sizes_read_as_windows_gives_them() {
        assertEquals("45.2 GB", sizeShort((45.2 * (1L shl 30)).toLong())); assertEquals("1.82 TB", sizeShort((1.82 * (1L shl 40)).toLong()))
    }
}
