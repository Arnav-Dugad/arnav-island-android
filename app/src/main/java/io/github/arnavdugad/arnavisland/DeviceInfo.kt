package io.github.arnavdugad.arnavisland

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock

/**
 * This phone as the island shows it in its own view: the battery (level, charging, temperature, health, cycles), storage,
 * memory, the network, the sound mode, Android, how long it has been on, and what it plays (with notification access).
 * Only readings Android gives any app; nothing identifying beyond the model.
 */
object DeviceInfo {
    fun read(context: Context): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) out += "Battery" to "${level * 100 / scale}%"
            val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            out += "Charging" to if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) "Yes" else "No"
            // 1.4: from this phone's own history (the island says "until 11 pm" by its battery).
            BatteryForecast.forecast(context)?.let { f -> out += (if (f.charging) "Full by" else "Lasts until") to BatteryForecast.time(context, f.at) }
            val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (tenths != Int.MIN_VALUE && tenths in -200..900) out += "Temperature" to "%.1f°C".format(tenths / 10.0)
            val health = when (battery.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Too warm"; BatteryManager.BATTERY_HEALTH_DEAD -> "Worn out"
                BatteryManager.BATTERY_HEALTH_COLD -> "Too cold"; BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"; else -> null }
            health?.let { out += "Battery health" to it }
            if (Build.VERSION.SDK_INT >= 34) battery.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 }?.let { out += "Charge cycles" to "$it" }
        }
        runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            out += "Storage" to "${gb(stat.availableBytes)} free of ${gb(stat.totalBytes)}"
        }
        runCatching {
            val m = ActivityManager.MemoryInfo(); context.getSystemService(ActivityManager::class.java).getMemoryInfo(m)
            out += "Memory" to "${gb(m.availMem)} free of ${gb(m.totalMem)}"
        }
        out += "Network" to network(context)
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java)
            val mode = when (audio.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "Silent"; AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"; else -> "Ring" }
            val dnd = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
            val media = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            out += "Sound" to "$mode  ·  media $media%" + if (dnd) "  ·  Do Not Disturb" else ""
        }
        out += "Android" to "${Build.VERSION.RELEASE}"
        out += "Uptime" to uptime(SystemClock.elapsedRealtime())
        out += "Model" to "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()
        playing(context)?.let { out += "Playing" to it }
        return out
    }
    /**
     * 1.5: the readings a PC's Phone page asks for every two seconds: all of the above, and the power going in or out,
     * whether the screen is on and its brightness.
     */
    fun live(context: Context): List<Pair<String, String>> {
        val out = ArrayList(read(context))
        runCatching {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return@runCatching
            val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1); val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
            val mv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0); val now = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            // Microamps on most phones; a few report milliamps. Either way the power is the current times the voltage.
            if (now != 0 && now != Int.MIN_VALUE && mv > 0) {
                val ma = if (kotlin.math.abs(now) > 20_000) kotlin.math.abs(now) / 1000.0 else kotlin.math.abs(now).toDouble()
                val watts = ma * mv / 1_000_000.0
                if (watts in 0.05..200.0) out += "Power" to (if (charging) "Charging at %.1f W" else "Using %.1f W").format(watts)
            }
        }
        runCatching { out += "Screen" to if (context.getSystemService(android.os.PowerManager::class.java).isInteractive) "on" else "off" }
        runCatching { val level = android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS); out += "Brightness" to "${(level * 100 + 127) / 255}%" }
        return out
    }
    /** The cover of what plays on the phone, as a small JPEG (with notification access), or null. */
    fun cover(context: Context): ByteArray? = runCatching {
        if (!Mirror.allowed(context)) return null
        val sessions = context.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(context, Mirror::class.java))
        val m = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }?.metadata ?: return null
        val art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) ?: return null
        val side = 160; val scale = side.toFloat() / maxOf(art.width, art.height).coerceAtLeast(1)
        val small = android.graphics.Bitmap.createScaledBitmap(art, (art.width * scale).toInt().coerceAtLeast(1), (art.height * scale).toInt().coerceAtLeast(1), true)
        var quality = 82; var bytes: ByteArray
        do { val o = java.io.ByteArrayOutputStream(); small.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, o); bytes = o.toByteArray(); quality -= 12 } while (bytes.size > 40_000 && quality > 30)
        bytes.takeIf { it.size <= 48_000 }
    }.getOrNull()
    private fun gb(bytes: Long) = if (bytes >= 10L shl 30) "${bytes shr 30} GB" else "%.1f GB".format(bytes / 1073741824.0)
    private fun uptime(ms: Long): String { val m = ms / 60_000; return when { m < 60 -> "$m min"; m < 48 * 60 -> "${m / 60} h ${m % 60} min"; else -> "${m / 1440} days" } }
    private fun network(context: Context): String = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java); val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Offline"
        val kind = when { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"; caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"; caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"; else -> "Online" }
        val bars = if (Build.VERSION.SDK_INT >= 29) caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }?.let { dbm -> when { dbm >= -55 -> 4; dbm >= -67 -> 3; dbm >= -78 -> 2; else -> 1 } } else null
        if (bars != null) "$kind  ·  ${"●".repeat(bars)}${"○".repeat(4 - bars)}" else kind
    }.getOrDefault("Unknown")
    /** What plays on the phone, when Android lets the app see it (with notification access). */
    private fun playing(context: Context): String? = runCatching {
        if (!Mirror.allowed(context)) return null
        val sessions = context.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(context, Mirror::class.java))
        val s = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: return null
        val m = s.metadata ?: return null
        val title = m.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return null
        listOfNotNull(title, m.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }).joinToString(" — ")
    }.getOrNull()
}
