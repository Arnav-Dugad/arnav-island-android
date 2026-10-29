package io.github.arnavdugad.arnavisland

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.arnavdugad.arnavisland.link.PcStats
import io.github.arnavdugad.arnavisland.link.PeerView
import kotlin.math.roundToInt

/**
 * 1.8: your PC's numbers, watched: a notification when its processor or graphics card has worked past a level for a
 * while, its memory or disk runs short, its battery runs low, or it has finished charging. Each says so once, and again
 * only after it has eased back. Read as the Island tab reads the numbers, and every 20 s otherwise (while this phone
 * stays reachable). The PC tells this phone loads, not temperatures, so the graphics card is watched by its load.
 */
object Alerts {
    private const val CHANNEL = "pc_alerts"; private const val ID = 9100
    /** What can be watched: its key, what it's called, its icon, its unit, its default level and its range. */
    class Kind(val key: String, val title: String, val icon: ImageVector, val unit: String, val level: Int, val lo: Int, val hi: Int, val step: Int, val sustained: Boolean = false)
    val kinds = listOf(
        Kind("cpu", "Processor busy", Icons.Rounded.Memory, "%", 90, 50, 100, 5, sustained = true),
        Kind("gpu", "Graphics busy", Icons.Rounded.Videocam, "%", 90, 50, 100, 5, sustained = true),
        Kind("ram", "Memory nearly full", Icons.Rounded.Storage, "%", 90, 60, 99, 1),
        Kind("disk", "Disk space low", Icons.Rounded.SdStorage, " GB free", 10, 1, 100, 1),
        Kind("battery", "Battery low", Icons.Rounded.BatteryAlert, "%", 15, 5, 50, 5),
        Kind("charged", "Finished charging", Icons.Rounded.BatteryChargingFull, "", 0, 0, 0, 0),
    )
    fun on(k: String) = Hub.prefs.getBoolean("alert_$k", false)
    fun level(k: Kind) = Hub.prefs.getInt("alert_${k.key}_at", k.level)
    /** How long the processor or graphics must stay past their level (minutes). */
    fun minutes() = Hub.prefs.getInt("alert_minutes", 2)
    fun any() = kinds.any { on(it.key) }

    private val since = HashMap<String, Long>(); private val fired = HashSet<String>()
    /** Each new reading of a PC's numbers. */
    @Synchronized fun check(context: Context, pc: PeerView, s: PcStats) {
        if (!any()) return
        val now = System.currentTimeMillis()
        fun hold(key: String, past: Boolean, back: Boolean, sustained: Boolean, say: () -> Pair<String, String>) {
            val id = "${pc.id}:$key"
            if (!on(key)) { since.remove(id); fired.remove(id); return }
            if (past) {
                val start = since.getOrPut(id) { now }
                if (id !in fired && (!sustained || now - start >= minutes() * 60_000L)) { fired += id; val (t, d) = say(); post(context, key, t, d) }
            } else if (back) { since.remove(id); fired.remove(id) }
        }
        val cpu = kinds[0]; val gpu = kinds[1]; val ram = kinds[2]; val disk = kinds[3]; val battery = kinds[4]
        if (s.cpu >= 0) hold("cpu", s.cpu >= level(cpu), s.cpu < level(cpu) - 10, true) { "${pc.name} is working hard" to "Its processor has been at ${s.cpu.roundToInt()}% for ${minutes()} min" }
        if (s.gpu >= 0) hold("gpu", s.gpu >= level(gpu), s.gpu < level(gpu) - 10, true) { "${pc.name}’s graphics card is working hard" to "At ${s.gpu.roundToInt()}% for ${minutes()} min" }
        if (s.ramPercent >= 0) hold("ram", s.ramPercent >= level(ram), s.ramPercent < level(ram) - 5, false) { "${pc.name} is low on memory" to "%.1f of %.1f GB in use".format(s.ramUsedGiB, s.ramTotalGiB) }
        if (s.diskTotalGiB > 0) hold("disk", s.diskFreeGiB < level(disk), s.diskFreeGiB > level(disk) + 2, false) { "${pc.name} is low on space" to "%.0f GB free of %.0f GB".format(s.diskFreeGiB, s.diskTotalGiB) }
        if (s.battery >= 0) {
            hold("battery", !s.charging && s.battery <= level(battery), s.charging || s.battery > level(battery) + 5, false) { "${pc.name}’s battery is at ${s.battery}%" to "Plug it in soon" }
            hold("charged", s.charging && s.battery >= 100, !s.charging || s.battery < 95, false) { "${pc.name} has finished charging" to "Its battery is full" }
        }
    }

    private fun post(context: Context, key: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Your PC’s alerts", NotificationManager.IMPORTANCE_HIGH).apply { description = "When your PC works hard, runs short of memory or space, or its battery runs low or fills" })
        val open = PendingIntent.getActivity(context, 91, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open", "island"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { NotificationManagerCompat.from(context).notify(ID + kinds.indexOfFirst { it.key == key }, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_STATUS).build()) }
    }
}

/** The alerts' settings: each one's switch and its level, and how long a busy processor must last. */
@Composable fun AlertsSheet(visible: Boolean, onDismiss: () -> Unit) {
    val t = LocalTokens.current; var version by remember { mutableIntStateOf(0) }
    GlassSheet(visible, onDismiss, Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .9f)) {
        SheetHeader("Alerts", "A notification when your PC needs you", onDismiss)
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            version.hashCode()
            Section(null, footer = "Your PC is read every 20 seconds while this phone stays reachable, and every second while its Island tab shows.") {
                Alerts.kinds.forEachIndexed { i, k ->
                    if (i > 0) Hairline()
                    val on = Alerts.on(k.key); val level = Alerts.level(k)
                    GlassRow(k.icon, k.title, if (k.step == 0) "When its battery is full" else if (k.key == "disk") "Under $level GB free" else (if (k.key == "battery") "At $level% or less" else "At $level% or more") + if (k.sustained) " for ${Alerts.minutes()} min" else "",
                        tint = listOf(t.blue, t.purple, t.teal, t.gray, t.danger, t.good)[i]) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (on && k.step > 0) {
                                StepButton(Icons.Rounded.Remove, "Lower", { Hub.prefs.edit().putInt("alert_${k.key}_at", (Alerts.level(k) - k.step).coerceAtLeast(k.lo)).apply(); version++ }, size = 32.dp)
                                Spacer(Modifier.width(6.dp))
                                StepButton(Icons.Rounded.Add, "Higher", { Hub.prefs.edit().putInt("alert_${k.key}_at", (Alerts.level(k) + k.step).coerceAtMost(k.hi)).apply(); version++ }, size = 32.dp)
                                Spacer(Modifier.width(10.dp))
                            }
                            GlassSwitch(on, { v -> Hub.prefs.edit().putBoolean("alert_${k.key}", v).apply(); version++ })
                        }
                    }
                }
            }
            Section("Busy for", footer = "How long the processor or graphics card must stay past its level before you hear about it.") {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 5, 10).forEach { m -> GlassChip("$m min", Modifier.weight(1f), selected = Alerts.minutes() == m) { Hub.prefs.edit().putInt("alert_minutes", m).apply(); version++ } }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(" ", style = Type.micro)
        }
    }
}
