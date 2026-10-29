package io.github.arnavdugad.arnavisland.link

/*
 * Revision 5 (island 0.22): this phone controls the whole island. The payloads are the island's (ShareService.h):
 * little-endian, strings as a u32 length and UTF-8; every answer starts with a version byte (1) after the status.
 */

/** The PC's numbers, live, with their last samples (CPU and GPU in percent, -1 unknown; downloads in bytes a second), and what the PC is. */
data class PcStats(
    val cpu: Double, val gpu: Double, val ramUsedGiB: Double, val ramTotalGiB: Double, val ramPercent: Double,
    val diskUsedPercent: Double, val diskFreeGiB: Double, val diskTotalGiB: Double, val download: Double, val upload: Double,
    val uptime: Long, val logical: Int, val battery: Int, val charging: Boolean, val batteryMinutes: Double,
    val cpuHistory: List<Float>, val gpuHistory: List<Float>, val downloadHistory: List<Float>,
    val name: String, val model: String, val os: String, val cpuName: String, val gpuName: String,
    /** Revision 6: each core's load (percent, -1 unknown); empty from older islands. */
    val cores: List<Float> = emptyList(),
)
/** Revision 6: the PC's battery in full. Capacities in mWh (0 unknown); rate in mW (negative draining); health 0..1 (-1 unknown). */
data class PcBattery(
    val percent: Int, val present: Boolean, val online: Boolean, val charging: Boolean, val saver: Boolean, val critical: Boolean,
    val minutesLeft: Int, val minutesToFull: Int, val designMwh: Long, val fullMwh: Long, val remainingMwh: Long, val rateMw: Long, val voltageMv: Long,
    val cycles: Long, val temperatureDeciK: Int, val health: Double, val healthBefore: Double, val chemistry: String, val manufacturer: String, val name: String,
    /** The last day: seconds since 1970, percent, charging. */
    val day: List<Triple<Long, Int, Boolean>>,
)
/** Revision 6: what a PC tells this phone about its focus clock. mode 0 focus, 1 break, 2 stopwatch. */
data class FocusState(val mode: Int, val running: Boolean, val finished: Boolean, val shown: Double, val duration: Double, val pcName: String, val at: Long)
/** One of the island's settings, as its Settings window has it. [control]: 0 switch, 1 slider, 2 choice, 3 stepper, 4 swatch, 5 button. */
data class IslandSetting(
    val section: Int, val control: Int, val key: String, val title: String, val detail: String,
    val lo: Int, val hi: Int, val step: Int, val value: Int, val action: Int, val unit: String, val options: List<String>, val colours: List<Int>,
)
data class IslandSettings(val sections: List<String>, val items: List<IslandSetting>)
/** The island's Controls page, the volume and its focus clock. Radios and dark mode: 1 on, 0 off, -1 unknown, -2 none. [busy]: switches changing. */
data class PcControls(
    val wifi: Int, val bluetooth: Int, val dark: Int, val brightness: Int, val volume: Int, val muted: Boolean, val micAvailable: Boolean, val micMuted: Boolean,
    val focusRunning: Boolean, val focusFinished: Boolean, val focusMode: Int, val focusDuration: Double, val focusShown: Double, val busy: Int,
) {
    /** Every radio off. */
    val airplane get() = wifi == 0 && (bluetooth == 0 || bluetooth == -2)
}
/** A command bar result from the PC ([kind]: the island's CommandKind). */
data class CommandRow(val kind: Int, val confirm: Boolean, val title: String, val detail: String, val answer: String)
data class CommandResults(val final: Boolean, val rows: List<CommandRow>)
/** What running one did: 0 done, 1 needs a yes first ([message] asks), 2 failed, 3 the results changed. */
data class CommandOutcome(val outcome: Int, val message: String)
data class AudioOutput(val id: String, val name: String, val current: Boolean, val form: Int)
/** Revision 9: shuffle and repeat (0 off, 1 one, 2 all) as the PC's player reports them, and what plays next (the island's own player only). */
data class Playback(val shuffleKnown: Boolean, val shuffle: Boolean, val repeatKnown: Boolean, val islandPlayer: Boolean, val repeat: Int, val upNext: List<UpNext>)
data class UpNext(val title: String, val artist: String, val duration: Double)
/** Revision 9: an app making sound on the PC: its volume and its level now (0..100), and a small PNG of its icon when asked. */
class MixerApp(val pid: Long, val name: String, val volume: Int, val peak: Int, val muted: Boolean, val system: Boolean, val active: Boolean, val icon: ByteArray?)
/** Revision 9: how loud the PC plays (0..1) and its spectrum, low to high (0..1 each). */
class Levels(val level: Float, val resting: Boolean, val bands: List<Float>)
/** Revision 9: one of the PC's network adapters: its hardware address, name, IPv4 address and prefix length. */
class Adapter(val mac: ByteArray, val name: String, val ipv4: String, val prefix: Int)
/** Revision 10: an app running on the PC (its processes summed): CPU (% of the machine), memory, and whether it can be ended. */
class PcProcess(val pid: Long, val name: String, val title: String, val cpu: Float, val memory: Long, val flags: Int, val icon: ByteArray?) {
    val windowed get() = flags and 1 != 0; val protected get() = flags and 2 != 0; val island get() = flags and 4 != 0
}
/** Revision 10: the PC's temperatures (°C, NaN unknown), each sensor read, and its fans. */
class Sensors(val cpu: Float, val gpu: Float, val zones: List<Pair<String, Float>>, val fans: List<Pair<String, Int>>)
/** Revision 10: a folder on the PC ([path] "" is its Places, "shelf:" its Shelf), a page of what's in it. kind 0 file, 1 folder, 2 drive, 3 place. */
class FileEntry(val name: String, val path: String, val kind: Int, val size: Long, val modified: Long, val thumb: ByteArray?) { val folder get() = kind != 0 }
class FileList(val path: String, val title: String, val parent: String, val total: Long, val entries: List<FileEntry>)
/** Revision 10: an item of the PC's clipboard history. kind 0 text, 1 image, 2 files. */
class PcClip(val id: Long, val kind: Int, val text: String, val at: Long, val pinned: Boolean, val thumb: ByteArray?)
class PcClipFull(val kind: Int, val text: String, val image: ByteArray?)
/** Revision 11: the PC's battery as it was made and as it is: capacities in mWh (0 unknown), cycles (-1 unknown), rate in mW (+ charging). */
class BatteryHealth(val present: Boolean, val designMwh: Long, val fullMwh: Long, val cycles: Long, val chemistry: String, val manufacturer: String, val device: String,
                    val currentMwh: Long, val rateMw: Int, val voltageMv: Long) {
    /** What it holds now against what it was made to (0..1, a new one a little over); -1 unknown. */
    val health get() = if (designMwh > 0 && fullMwh > 0) fullMwh.toDouble() / designMwh else -1.0
}
/** Revision 11: a drive (kind 0 fixed, 1 removable, 2 network, 3 other), in bytes; [system]: the Windows drive. */
class Drive(val root: String, val label: String, val kind: Int, val total: Long, val free: Long, val system: Boolean) { val used get() = (total - free).coerceAtLeast(0) }
/** What fills the Windows drive: kind 0 apps, 1 Windows, 2 documents, 3 downloads, 4 pictures, 5 videos, 6 music, 7 desktop, 8 recycle bin, 9 other. */
class StorageGroup(val name: String, val kind: Int, val bytes: Long)
/** Revision 11: the PC's drives and (once measured; partly while [ready] is false) what fills its Windows drive. */
class Storage(val ready: Boolean, val progress: Int, val drives: List<Drive>, val groups: List<StorageGroup>)
/** Revision 11: an app that starts with Windows. source 0 the user's Run, 1 all users' Run, 2 all users' 32-bit Run, 3 the user's Startup folder, 4 everyone's, 5 a Store app. */
class StartupApp(val id: String, val name: String, val command: String, val source: Int, val enabled: Boolean, val canChange: Boolean, val icon: ByteArray?)
/** Revision 11: one of the PC's network adapters in full (kind 0 Ethernet, 1 Wi-Fi, 2 other). [signal] 0..100, -1 unknown; [channel] 0 unknown. */
class NetAdapter(val name: String, val description: String, val kind: Int, val up: Boolean, val isDefault: Boolean, val mac: String, val linkBps: Long,
                 val ipv4: List<String>, val ipv6: List<String>, val gateway: String, val dns: List<String>, val ssid: String, val signal: Int, val band: String, val channel: Int, val security: String) {
    val wifi get() = kind == 1
}
/** Revision 11: an update Windows has found. kind 0 quality or security, 1 driver, 2 feature, 3 definitions, 4 other; size in bytes (0 unknown). */
class UpdateItem(val title: String, val kind: Int, val size: Long, val downloaded: Boolean, val needsRestart: Boolean)
/** Revision 11: Windows Update. state 0 unknown, 1 up to date, 2 updates available, 3 restart required, 4 checking; times in seconds since 1970 (0 unknown). */
class Updates(val state: Int, val lastChecked: Long, val lastInstalled: Long, val items: List<UpdateItem>)
/** Revision 11: an app of the PC's Start menu: its id (an AUMID, or a shortcut's or exe's path), name and a 48 px PNG when asked. */
class StartMenuApp(val id: String, val name: String, val icon: ByteArray?)

object IslandWire {
    // The island's controls (remoteControls), and its CommandKind numbers the phone draws icons for.
    const val WIFI = 1; const val BLUETOOTH = 2; const val DARK = 3; const val AIRPLANE = 4; const val BRIGHTNESS = 5; const val VOLUME = 6
    const val MUTE = 7; const val MIC = 8; const val LOCK = 9; const val SLEEP = 10; const val RESTART = 11; const val SHUT_DOWN = 12; const val EMPTY_BIN = 13
    const val FOCUS = 15; const val BREAK = 16; const val FOCUS_TOGGLE = 17; const val FOCUS_RESET = 18; const val STOPWATCH = 19
    /** The island's pages, in its order. */
    val PAGES = listOf("Home", "Media", "Stats", "Focus", "Settings", "Shelf", "Audio", "Controls", "Phone")

    private fun Reader.i32(): Int? = u32()?.toInt()
    private fun Reader.i8(): Int? = u8()?.toByte()?.toInt()

    fun stats(p: ByteArray): PcStats? {
        val r = Reader(p); if (r.u8() != 1) return null
        val v = DoubleArray(10) { r.f64() ?: return null }
        val uptime = r.u64() ?: return null; val logical = r.u16() ?: return null; val battery = r.i8() ?: return null; val charging = (r.u8() ?: return null) != 0
        val minutes = r.f64() ?: return null; val n = r.u8() ?: return null
        fun pct(b: Int) = if (b == 255) -1f else b.toFloat()
        val cpu = List(n) { pct(r.u8() ?: return null) }; val gpu = List(n) { pct(r.u8() ?: return null) }; val down = List(n) { (r.u32() ?: return null).toFloat() }
        val texts = List(5) { r.string(4096) ?: return null }
        // Revision 6: the cores, when the island sends them.
        val cores = r.u8()?.let { c -> List(c) { pct(r.u8() ?: return@let emptyList()) } } ?: emptyList()
        return PcStats(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], uptime, logical, battery, charging, minutes, cpu, gpu, down, texts[0], texts[1], texts[2], texts[3], texts[4], cores)
    }
    fun battery(p: ByteArray): PcBattery? {
        val r = Reader(p); if (r.u8() != 1) return null
        val percent = r.i8() ?: return null; val flags = r.u8() ?: return null; val left = r.i32() ?: return null; val toFull = r.i32() ?: return null
        val caps = LongArray(5) { r.u64() ?: return null }; val cycles = r.u32() ?: return null; val temp = r.i32() ?: return null
        val health = r.f64() ?: return null; val before = r.f64() ?: return null; val texts = List(3) { r.string(1024) ?: return null }
        val n = r.u16() ?: return null
        val day = List(n) { Triple(r.u64() ?: return null, r.u8() ?: return null, (r.u8() ?: return null) != 0) }
        return PcBattery(percent, flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, flags and 8 != 0, flags and 16 != 0, left, toFull,
            caps[0], caps[1], caps[2], caps[3], caps[4], cycles, temp, health, before, texts[0], texts[1], texts[2], day)
    }
    /** A PC's focus clock ([QUERY_FOCUS]'s payload). */
    fun focus(p: ByteArray, at: Long = System.currentTimeMillis()): FocusState? {
        val r = Reader(p); val mode = r.u8() ?: return null; val running = (r.u8() ?: return null) != 0; val finished = (r.u8() ?: return null) != 0
        return FocusState(mode, running, finished, r.f64() ?: return null, r.f64() ?: return null, r.string(512) ?: return null, at)
    }
    fun settings(p: ByteArray): IslandSettings? {
        val r = Reader(p); if (r.u8() != 1) return null
        val sections = List(r.u8() ?: return null) { r.string(1024) ?: return null }
        val items = List(r.u16() ?: return null) {
            val section = r.u8() ?: return null; val control = r.u8() ?: return null; val key = r.string(256) ?: return null; val title = r.string(1024) ?: return null; val detail = r.string(2048) ?: return null
            val lo = r.i32() ?: return null; val hi = r.i32() ?: return null; val step = r.i32() ?: return null; val value = r.i32() ?: return null; val action = r.u8() ?: return null; val unit = r.string(128) ?: return null
            val options = List(r.u8() ?: return null) { r.string(512) ?: return null }; val colours = List(r.u8() ?: return null) { (r.u32() ?: return null).toInt() }
            IslandSetting(section, control, key, title, detail, lo, hi, step, value, action, unit, options, colours)
        }
        return IslandSettings(sections, items)
    }
    fun controls(p: ByteArray): PcControls? {
        val r = Reader(p); if (r.u8() != 1) return null
        val wifi = r.i8() ?: return null; val bt = r.i8() ?: return null; val dark = r.i8() ?: return null; val brightness = r.i8() ?: return null; val volume = r.u8() ?: return null
        val flags = r.u8() ?: return null; val mode = r.u8() ?: return null; val duration = r.f64() ?: return null; val shown = r.f64() ?: return null; val busy = r.u8() ?: return null
        return PcControls(wifi, bt, dark, brightness, volume, flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, flags and 8 != 0, flags and 16 != 0, mode, duration, shown, busy)
    }
    fun commands(p: ByteArray): CommandResults? {
        val r = Reader(p); if (r.u8() != 1) return null; val final = (r.u8() ?: return null) != 0
        val rows = List(r.u8() ?: return null) { CommandRow(r.u8() ?: return null, (r.u8() ?: return null) != 0, r.string(2048) ?: return null, r.string(2048) ?: return null, r.string(512) ?: return null) }
        return CommandResults(final, rows)
    }
    fun outcome(p: ByteArray): CommandOutcome? { val r = Reader(p); val o = r.u8() ?: return null; return CommandOutcome(o, r.string(2048) ?: return null) }
    fun outputs(p: ByteArray): List<AudioOutput>? {
        val r = Reader(p); if (r.u8() != 1) return null
        return List(r.u8() ?: return null) { AudioOutput(r.string(2048) ?: return null, r.string(1024) ?: return null, (r.u8() ?: return null) != 0, r.i8() ?: return null) }
    }
    fun value(p: ByteArray): Int? = Reader(p).i32()
    fun playback(p: ByteArray): Playback? {
        val r = Reader(p); if (r.u8() != 1) return null; val flags = r.u8() ?: return null; val repeat = r.u8() ?: return null
        val next = List(r.u16() ?: return null) { UpNext(r.string(4096) ?: return null, r.string(4096) ?: return null, r.f64() ?: return null) }
        return Playback(flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, flags and 8 != 0, repeat, next)
    }
    fun mixer(p: ByteArray): List<MixerApp>? {
        val r = Reader(p); if (r.u8() != 1) return null
        return List(r.u8() ?: return null) {
            val pid = r.u32() ?: return null; val name = r.string(2048) ?: return null; val volume = r.u8() ?: return null; val peak = r.u8() ?: return null; val flags = r.u8() ?: return null
            val icon = r.blob(64 * 1024) ?: return null
            MixerApp(pid, name, volume.coerceIn(0, 100), peak.coerceIn(0, 100), flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, icon.takeIf { it.isNotEmpty() })
        }
    }
    fun levels(p: ByteArray): Levels? {
        val r = Reader(p); if (r.u8() != 1) return null; val level = r.u8() ?: return null; val resting = (r.u8() ?: return null) != 0
        return Levels(level / 255f, resting, List(r.u8() ?: return null) { (r.u8() ?: return null) / 255f })
    }
    fun processes(p: ByteArray): List<PcProcess>? {
        val r = Reader(p); if (r.u8() != 1) return null
        return List(r.u8() ?: return null) { PcProcess(r.u32() ?: return null, r.string(1024) ?: return null, r.string(2048) ?: return null, r.f32() ?: return null, r.u64() ?: return null, r.u8() ?: return null, r.blob(64 * 1024)?.takeIf { it.isNotEmpty() }) }
    }
    fun sensors(p: ByteArray): Sensors? {
        val r = Reader(p); if (r.u8() != 1) return null; val cpu = r.f32() ?: return null; val gpu = r.f32() ?: return null
        val zones = List(r.u8() ?: return null) { (r.string(512) ?: return null) to (r.f32() ?: return null) }
        val fans = List(r.u8() ?: 0) { (r.string(512) ?: return null) to (r.u16() ?: return null) }
        return Sensors(cpu, gpu, zones, fans)
    }
    fun files(p: ByteArray): FileList? {
        val r = Reader(p); if (r.u8() != 1) return null
        val path = r.string(8192) ?: return null; val title = r.string(2048) ?: return null; val parent = r.string(8192) ?: return null; val total = r.u32() ?: return null
        val entries = List(r.u16() ?: return null) { FileEntry(r.string(2048) ?: return null, r.string(8192) ?: return null, r.u8() ?: return null, r.u64() ?: return null, r.u64() ?: return null, r.blob(64 * 1024)?.takeIf { it.isNotEmpty() }) }
        return FileList(path, title, parent, total, entries)
    }
    fun clips(p: ByteArray): List<PcClip>? {
        val r = Reader(p); if (r.u8() != 1) return null
        return List(r.u8() ?: return null) { PcClip(r.u32() ?: return null, r.u8() ?: return null, r.string(8192) ?: return null, r.u64() ?: return null, (r.u8() ?: return null) != 0, r.blob(64 * 1024)?.takeIf { it.isNotEmpty() }) }
    }
    fun clip(p: ByteArray): PcClipFull? { val r = Reader(p); if (r.u8() != 1) return null; return PcClipFull(r.u8() ?: return null, r.string(200_000) ?: return null, r.blob(256 * 1024)?.takeIf { it.isNotEmpty() }) }
    fun machine(p: ByteArray): List<Adapter>? {
        val r = Reader(p); if (r.u8() != 1) return null
        return List(r.u8() ?: return null) { Adapter(r.bytes(6) ?: return null, r.string(1024) ?: return null, r.string(64) ?: return null, r.u8() ?: return null) }
    }

    // ---- revision 11 (island 0.28) ----
    fun batteryHealth(p: ByteArray): BatteryHealth? {
        val r = Reader(p); if (r.u8() != 1) return null
        val present = (r.u8() ?: return null) != 0; val design = r.u32() ?: return null; val full = r.u32() ?: return null; val cycles = r.u32() ?: return null
        val texts = List(3) { r.string(1024) ?: return null }; val current = r.u32() ?: return null; val rate = r.i32() ?: return null; val volts = r.u32() ?: return null
        return BatteryHealth(present, design, full, if (cycles == 0xFFFFFFFFL) -1 else cycles, texts[0], texts[1], texts[2], current, rate, volts)
    }
    fun storage(p: ByteArray): Storage? {
        val r = Reader(p); if (r.u8() != 1) return null
        val state = r.u8() ?: return null; val progress = r.u8() ?: return null
        val drives = List(r.u8() ?: return null) { Drive(r.string(1024) ?: return null, r.string(1024) ?: return null, r.u8() ?: return null, r.u64() ?: return null, r.u64() ?: return null, (r.u8() ?: return null) != 0) }
        val groups = List(r.u8() ?: return null) { StorageGroup(r.string(1024) ?: return null, r.u8() ?: return null, r.u64() ?: return null) }
        return Storage(state == 1, progress.coerceIn(0, 100), drives, groups)
    }
    fun startup(p: ByteArray): List<StartupApp>? {
        val r = Reader(p); if (r.u8() != 1) return null
        return List(r.u8() ?: return null) { StartupApp(r.string(4096) ?: return null, r.string(1024) ?: return null, r.string(4096) ?: return null, r.u8() ?: return null,
            (r.u8() ?: return null) != 0, (r.u8() ?: return null) != 0, (r.blob(64 * 1024) ?: return null).takeIf { it.isNotEmpty() }) }
    }
    fun network(p: ByteArray): List<NetAdapter>? {
        val r = Reader(p); if (r.u8() != 1) return null
        fun strings(): List<String>? { return List(r.u8() ?: return null) { r.string(512) ?: return null } }
        return List(r.u8() ?: return null) {
            val name = r.string(1024) ?: return null; val description = r.string(1024) ?: return null; val kind = r.u8() ?: return null; val up = (r.u8() ?: return null) != 0
            val default = (r.u8() ?: return null) != 0; val mac = r.string(64) ?: return null; val speed = r.u64() ?: return null
            val v4 = strings() ?: return null; val v6 = strings() ?: return null; val gateway = r.string(512) ?: return null; val dns = strings() ?: return null
            val ssid = r.string(512) ?: return null; val signal = r.u8() ?: return null; val band = r.string(64) ?: return null; val channel = r.u16() ?: return null; val security = r.string(128) ?: return null
            NetAdapter(name, description, kind, up, default, mac, speed, v4, v6, gateway, dns, ssid, if (signal == 255) -1 else signal.coerceIn(0, 100), band, channel, security)
        }
    }
    fun updates(p: ByteArray): Updates? {
        val r = Reader(p); if (r.u8() != 1) return null
        val state = r.u8() ?: return null; val checked = r.u64() ?: return null; val installed = r.u64() ?: return null
        val items = List(r.u8() ?: return null) { UpdateItem(r.string(2048) ?: return null, r.u8() ?: return null, r.u64() ?: return null, (r.u8() ?: return null) != 0, (r.u8() ?: return null) != 0) }
        return Updates(state, checked, installed, items)
    }
    fun apps(p: ByteArray): List<StartMenuApp>? {
        val r = Reader(p); if (r.u8() != 1) return null
        return List(r.u16() ?: return null) { StartMenuApp(r.string(8192) ?: return null, r.string(1024) ?: return null, (r.blob(64 * 1024) ?: return null).takeIf { it.isNotEmpty() }) }
    }
}
