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
)
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

object IslandWire {
    // The island's controls (remoteControls), and its CommandKind numbers the phone draws icons for.
    const val WIFI = 1; const val BLUETOOTH = 2; const val DARK = 3; const val AIRPLANE = 4; const val BRIGHTNESS = 5; const val VOLUME = 6
    const val MUTE = 7; const val MIC = 8; const val LOCK = 9; const val SLEEP = 10; const val RESTART = 11; const val SHUT_DOWN = 12; const val EMPTY_BIN = 13
    const val FOCUS = 15; const val BREAK = 16; const val FOCUS_TOGGLE = 17; const val FOCUS_RESET = 18; const val STOPWATCH = 19
    /** The island's pages, in its order. */
    val PAGES = listOf("Home", "Media", "Stats", "Focus", "Settings", "Shelf", "Audio", "Controls")

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
        return PcStats(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], uptime, logical, battery, charging, minutes, cpu, gpu, down, texts[0], texts[1], texts[2], texts[3], texts[4])
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
}
