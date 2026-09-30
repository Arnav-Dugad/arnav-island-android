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
