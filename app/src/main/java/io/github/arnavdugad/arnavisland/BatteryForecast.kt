package io.github.arnavdugad.arnavisland

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.exp

/**
 * 1.4: how long this phone's battery lasts, from its own history ("lasts until 11 pm"), and when it's full while it
 * charges. Every change of level is kept for two weeks, on this phone only. The forecast walks forward from now: at
 * first at the pace of the last hour or so, then more and more at the pace this phone usually keeps at each hour of the
 * day, recent days counting most.
 */
object BatteryForecast {
    private const val FILE = "battery-history"
    private const val KEEP = 14 * 24 * 60 // minutes
    /** One reading: minutes since 1970, the level, and whether it was charging. */
    internal data class Sample(val minute: Int, val level: Int, val charging: Boolean)
    private var samples: MutableList<Sample>? = null

    data class Forecast(val at: Long, val charging: Boolean)

    /** Keeps a reading when the level or charging changed (called with every battery broadcast). */
    @Synchronized fun record(context: Context, level: Int, charging: Boolean, now: Long = System.currentTimeMillis()) {
        if (level !in 0..100) return
        val list = load(context); val minute = (now / 60_000).toInt()
        val last = list.lastOrNull()
        if (last != null && last.level == level && last.charging == charging) return
        list += Sample(minute, level, charging)
        val cut = minute - KEEP
        if (list.first().minute < cut - 24 * 60) { list.removeAll { it.minute < cut }; save(context, list) }
        else runCatching { DataOutputStream(java.io.FileOutputStream(File(context.filesDir, FILE), true).buffered()).use { write(it, list.last()) } }
    }

    /** The forecast now, or null until this phone has seen enough of its battery (about an hour away from the charger). */
    @Synchronized fun forecast(context: Context, now: Long = System.currentTimeMillis()): Forecast? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (raw < 0 || scale <= 0) return null
        val level = raw * 100 / scale; val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        record(context, level, charging, now)
        return if (charging) full(context, level, status == BatteryManager.BATTERY_STATUS_FULL, now) else empty(context, level, now)
    }

    /** The battery in a line for this app: "64%  ·  lasts until 11 pm", "80%  ·  full by 10:40 pm". */
    fun line(context: Context): String? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (raw < 0 || scale <= 0) return null
        val f = forecast(context); val level = raw * 100 / scale; val percent = "$level%"
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return when {
            f == null && charging && (level >= 100 || status == BatteryManager.BATTERY_STATUS_FULL) -> "$percent  ·  full"
            f == null && charging -> "$percent  ·  charging"
            f == null -> "$percent  ·  learning how long it lasts"
            f.charging -> "$percent  ·  full by ${time(context, f.at)}"
            else -> "$percent  ·  lasts until ${time(context, f.at)}"
        }
    }

    /** "11 pm", "10:45 pm", "tomorrow 9 am" (or the phone's 24-hour clock), to the quarter hour (five minutes when soon). */
    fun time(context: Context, at: Long, now: Long = System.currentTimeMillis()): String {
        val step = if (at - now < 3 * 3_600_000L) 5 else 15
        val zone = ZoneId.systemDefault()
        val rounded = Instant.ofEpochMilli(at).atZone(zone).let { t -> val m = (t.minute + step / 2) / step * step; t.truncatedTo(ChronoUnit.HOURS).plusMinutes(m.toLong()) }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val clock = if (DateFormat.is24HourFormat(context)) DateTimeFormatter.ofPattern("H:mm", Locale.getDefault())
            else DateTimeFormatter.ofPattern(if (rounded.minute == 0) "h a" else "h:mm a", Locale.getDefault())
        val text = rounded.format(clock).replace("AM", "am").replace("PM", "pm")
        val days = ChronoUnit.DAYS.between(today, rounded.toLocalDate())
        return when { days <= 0L -> text; days == 1L -> "tomorrow $text"; else -> rounded.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())) + " " + text }
    }

    // ---- discharging ----
    private fun empty(context: Context, level: Int, now: Long): Forecast? = lastsUntil(load(context), level, now, ZoneId.systemDefault())?.let { Forecast(it, false) }
    /** When [level] runs out from [now], from these readings (null until they say enough). */
    internal fun lastsUntil(list: List<Sample>, level: Int, now: Long, zone: ZoneId): Long? {
        val nowMin = (now / 60_000).toInt()
        // The usual pace at each hour of the day (percent an hour), from every stretch away from the charger.
        val drop = DoubleArray(24); val hours = DoubleArray(24); var allDrop = 0.0; var allHours = 0.0
        for (i in 1 until list.size) {
            val a = list[i - 1]; val b = list[i]; if (a.charging || b.charging) continue
            val gap = b.minute - a.minute; if (gap <= 0 || gap > 12 * 60 || b.level > a.level) continue
            val weight = Math.pow(.85, (nowMin - b.minute) / 1440.0)
            val hour = Instant.ofEpochSecond((a.minute + gap / 2).toLong() * 60).atZone(zone).hour
            val d = (a.level - b.level) * weight; val h = gap / 60.0 * weight
            drop[hour] += d; hours[hour] += h; allDrop += d; allHours += h
        }
        val usual = if (allHours >= .5 && allDrop > 0) allDrop / allHours else null
        // The pace of the last stretch since the charger: its last 90 minutes, once there's enough of it to say.
        val since = list.indexOfLast { it.charging }.let { if (it < 0) 0 else it + 1 }
        val stretch = list.subList(since.coerceAtMost(list.size), list.size)
        var recent: Double? = null
        if (stretch.size >= 2) {
            val end = stretch.last(); val start = stretch.firstOrNull { it.minute >= end.minute - 90 } ?: stretch.first()
            val first = if (start === end) stretch[stretch.size - 2] else start
            val span = (nowMin - first.minute).coerceAtLeast(end.minute - first.minute); val fell = first.level - level
            if (span >= 20 && fell >= 2) recent = fell / (span / 60.0)
        }
        if (usual == null && recent == null) return null
        // Walks forward five minutes at a time: the last stretch's pace fades into the hour's usual one over about 1.5 h.
        var left = level.toDouble(); var t = now; val step = 5 * 60_000L; var ahead = 0.0
        while (left > 0 && t - now < 72 * 3_600_000L) {
            val hour = Instant.ofEpochMilli(t).atZone(zone).hour
            val hourly = if (hours[hour] >= .75) drop[hour] / hours[hour] else usual ?: recent!!
            val w = if (recent != null) exp(-ahead / 1.5) else 0.0
            val pace = (w * (recent ?: 0.0) + (1 - w) * hourly).coerceIn(.25, 60.0)
            left -= pace / 12; t += step; ahead += 1 / 12.0
        }
        // Beyond three days it's too far to say.
        return if (left > 0) null else t
    }

    // ---- charging ----
    private fun full(context: Context, level: Int, isFull: Boolean, now: Long): Forecast? {
        if (isFull || level >= 100) return null
        val manager = context.getSystemService(BatteryManager::class.java)
        val android = runCatching { manager.computeChargeTimeRemaining() }.getOrDefault(-1L)
        if (android > 0) return Forecast(now + android, true)
        // Otherwise this charge's own pace (the last hour of it), slowing past 80% as batteries do.
        val list = load(context); val nowMin = (now / 60_000).toInt()
        val since = list.indexOfLast { !it.charging }.let { if (it < 0) 0 else it + 1 }
        val charge = list.subList(since.coerceAtMost(list.size), list.size)
        if (charge.size < 2) return null
        val first = charge.firstOrNull { it.minute >= nowMin - 60 }?.takeIf { it !== charge.last() } ?: charge.first()
        val span = nowMin - first.minute; val rose = level - first.level
        if (span < 10 || rose < 2) return null
        val pace = rose / (span / 60.0)
        var left = level.toDouble(); var t = now; val step = 5 * 60_000L
        while (left < 100 && t - now < 24 * 3_600_000L) { left += (if (left < 80) pace else pace * .45) / 12; t += step }
        return Forecast(t, true)
    }

    // ---- the file: 6 bytes a reading ----
    private fun load(context: Context): MutableList<Sample> {
        samples?.let { return it }
        val list = ArrayList<Sample>()
        runCatching {
            val f = File(context.filesDir, FILE); if (f.exists()) DataInputStream(f.inputStream().buffered()).use { input ->
                while (input.available() >= 6) { val m = input.readInt(); val l = input.readByte().toInt(); val c = input.readByte().toInt() != 0; if (l in 0..100) list += Sample(m, l, c) }
            }
        }
        list.sortBy { it.minute }
        samples = list; return list
    }
    private fun write(out: DataOutputStream, s: Sample) { out.writeInt(s.minute); out.writeByte(s.level); out.writeByte(if (s.charging) 1 else 0) }
    private fun save(context: Context, list: List<Sample>) = runCatching {
        val f = File(context.filesDir, "$FILE.new"); DataOutputStream(f.outputStream().buffered()).use { out -> list.forEach { write(out, it) } }
        f.renameTo(File(context.filesDir, FILE))
    }
}
