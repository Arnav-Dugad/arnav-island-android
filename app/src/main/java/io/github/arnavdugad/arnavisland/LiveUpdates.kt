package io.github.arnavdugad.arnavisland

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.arnavdugad.arnavisland.link.PcStatus
import io.github.arnavdugad.arnavisland.link.PeerView

/**
 * 1.8: transfers and your PC's song as live updates. On Android 16 each is promoted to the status bar's chip and the top
 * of the lock screen, with its progress (as the focus clock already is); a transfer shows its percentage in the chip,
 * the song counts down to its end. Before Android 16, a transfer is an ongoing notification with a progress bar (the
 * song already has its player there).
 */
object LiveUpdates {
    const val TRANSFERS = 9; const val SONG = 10
    private const val CHANNEL = "live_updates"
    fun transfersOn() = Hub.prefs.getBoolean("liveTransfers", true)
    fun songOn() = Hub.prefs.getBoolean("liveSong", true) && Build.VERSION.SDK_INT >= 36

    private fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun channel(context: Context) = context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, "Live updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Transfers and your PC’s song, in the status bar and on the lock screen"; setShowBadge(false); setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    private fun open(context: Context, what: String) = PendingIntent.getActivity(context, what.hashCode(), Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open", what),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun service(context: Context, action: String, transfer: Int) = PendingIntent.getService(context, action.hashCode() * 31 + transfer,
        Intent(context, LinkService::class.java).setAction(action).putExtra("transfer", transfer), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** The transfers under way (and how many wait their turn): one notification for them all. */
    fun transfers(context: Context, map: Map<Int, Transfer>, waiting: Int) {
        val m = NotificationManagerCompat.from(context)
        if (map.isEmpty() || !transfersOn() || !allowed(context)) { runCatching { m.cancel(TRANSFERS) }; return }
        channel(context)
        val all = map.values.sortedBy { it.id }; val first = all.first()
        val total = all.sumOf { it.total }.coerceAtLeast(1); val done = all.sumOf { it.done }; val f = (done.toDouble() / total).coerceIn(0.0, 1.0)
        val paused = all.all { it.paused }; val rate = all.sumOf { it.rate }
        val title = when { all.size > 1 -> "${all.size} transfers"; first.outgoing -> "Sending ${first.title}"; else -> "Receiving ${first.title}" }
        val text = listOfNotNull(if (all.size == 1) (if (first.outgoing) "To ${first.name}" else "From ${first.name}") else null, "${(f * 100).toInt()}%",
            if (paused) "Paused" else if (rate > 0) rateText(rate) else null, if (!paused && rate > 0) leftText((total - done) / rate).ifEmpty { null } else null,
            if (waiting > 0) "$waiting waiting" else null).joinToString("  ·  ")
        val stop = service(context, LinkService.CANCEL, first.id); val canPause = all.size == 1 && Hub.canPause(first)
        val toggle = if (canPause) service(context, if (first.paused) LinkService.RESUME else LinkService.PAUSE, first.id) else null
        val n: Notification = if (Build.VERSION.SDK_INT >= 36) {
            val style = Notification.ProgressStyle().setProgress((f * 1000).toInt()).setProgressSegments(all.map { Notification.ProgressStyle.Segment(((it.total.toDouble() / total) * 1000).toInt().coerceAtLeast(1)) })
            val b = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_island).setContentTitle(title).setContentText(text).setContentIntent(open(context, "send"))
                .setOnlyAlertOnce(true).setOngoing(true).setCategory(Notification.CATEGORY_PROGRESS).setVisibility(Notification.VISIBILITY_PUBLIC).setStyle(style)
            b.addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", true); putString("android.shortCriticalText", if (paused) "Paused" else "${(f * 100).toInt()}%") })
            if (toggle != null) b.addAction(Notification.Action.Builder(null, if (first.paused) "Resume" else "Pause", toggle).build())
            if (all.size == 1) b.addAction(Notification.Action.Builder(null, "Stop", stop).build())
            b.build()
        } else NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_island).setContentTitle(title).setContentText(text).setContentIntent(open(context, "send"))
            .setOnlyAlertOnce(true).setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_PROGRESS).setProgress(1000, (f * 1000).toInt(), false)
            .apply { if (toggle != null) addAction(0, if (first.paused) "Resume" else "Pause", toggle); if (all.size == 1) addAction(0, "Stop", stop) }.build()
        runCatching { m.notify(TRANSFERS, n) }
    }

    @Volatile private var songShown: String? = null
    /** Android 16: what plays on the PC, counting down to the song's end in the status bar's chip. */
    fun song(context: Context, pc: PeerView?, s: PcStatus?) {
        val m = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT < 36 || !songOn() || !allowed(context) || pc == null || s == null || !s.available || !s.playing || s.duration <= 0) {
            if (songShown != null) { songShown = null; runCatching { m.cancel(SONG) } }; return
        }
        // The notification counts down on its own: it's told again only when the song, its place or its state changes.
        val now = System.currentTimeMillis(); val position = s.positionNow(now); val end = now + ((s.duration - position) * 1000).toLong()
        val key = "${s.title}\t${s.artist}\t${end / 3000}"
        if (key == songShown) return
        songShown = key; channel(context)
        val style = Notification.ProgressStyle().setProgress((position / s.duration * 1000).toInt().coerceIn(0, 1000)).setProgressSegments(listOf(Notification.ProgressStyle.Segment(1000)))
        val b = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_island).setContentTitle(s.title)
            .setContentText(listOf(s.artist, "on ${pc.name}").filter { it.isNotBlank() }.joinToString("  ·  ")).setContentIntent(open(context, "remote"))
            .setOnlyAlertOnce(true).setOngoing(true).setCategory(Notification.CATEGORY_TRANSPORT).setVisibility(Notification.VISIBILITY_PUBLIC).setStyle(style)
            .setUsesChronometer(true).setChronometerCountDown(true).setWhen(end).setShowWhen(true).setTimeoutAfter(end - now + 5_000)
        s.cover?.let { c -> Art.bitmap(c, 256)?.let { b.setLargeIcon(it) } }
        b.addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
        runCatching { m.notify(SONG, b.build()) }
    }
}
