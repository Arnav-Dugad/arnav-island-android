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
import io.github.arnavdugad.arnavisland.link.FocusState

/**
 * 1.5: your PC's focus clock on this phone: on the lock screen and in the status bar, an ongoing notification that
 * counts down on its own (on Android 16, a live update with its progress), and in the focus widget. The PC tells this
 * phone whenever the clock starts, pauses or stops (island 0.23).
 */
object FocusLive {
    const val ID = 8
    private const val CHANNEL = "focus_clock"

    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            // Default importance (without a sound): up with the other notifications and in full on the lock screen.
            NotificationChannel(CHANNEL, "Focus on your PC", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Your PC’s focus clock, counting down on the lock screen"; setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null); enableVibration(false)
            })
    }

    /** What to call the clock: focus, a break, or the stopwatch. */
    fun kind(f: FocusState) = when (f.mode) { 1 -> "Break"; 2 -> "Stopwatch"; else -> "Focus" }
    /** When a running countdown ends (ms since 1970), or when a stopwatch started. */
    fun end(f: FocusState) = if (f.mode == 2) f.at - (f.shown * 1000).toLong() else f.at + (f.shown * 1000).toLong()

    fun show(context: Context, f: FocusState) {
        FocusWidgets.refresh(context)
        val allowed = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        val m = NotificationManagerCompat.from(context)
        // Stopped and reset: nothing to show. Finished: a word that it's done, for a few minutes.
        val paused = !f.running && !f.finished && (if (f.mode == 2) f.shown > .5 else f.shown < f.duration - .5)
        if (!f.running && !f.finished && !paused) { runCatching { m.cancel(ID) }; return }
        channel(context)
        val open = PendingIntent.getActivity(context, 71, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open", "island"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val toggle = PendingIntent.getBroadcast(context, 72, Intent(context, WidgetActions::class.java).setAction("focus_toggle"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = when { f.finished -> "${kind(f)} done on ${f.pcName}"; paused -> "${kind(f)} paused on ${f.pcName}"; else -> "${kind(f)} on ${f.pcName}" }
        val left = clock(if (f.mode == 2) f.shown else f.shown)
        val text = when { f.finished -> if (f.mode == 1) "Back to it" else "A moment well spent"; paused -> if (f.mode == 2) "$left so far" else "$left left"
            f.mode == 2 -> "Counting up"; else -> "Ends at ${BatteryForecast.time(context, end(f))}" }
        if (Build.VERSION.SDK_INT >= 36) { runCatching { m.notify(ID, live(context, f, title, text, paused, open, toggle)) }.onFailure { android.util.Log.w("ArnavIsland", "The focus clock's notification", it) }; return }
        val b = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_island).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setOnlyAlertOnce(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setOngoing(f.running).setShowWhen(f.running).setGroup("focus_clock")
        if (f.running) {
            b.setUsesChronometer(true).setWhen(end(f)).setChronometerCountDown(f.mode != 2)
            if (f.mode != 2 && f.duration > 0) b.setProgress(1000, (1000 * (1 - f.shown / f.duration)).toInt().coerceIn(0, 1000), false)
                .setTimeoutAfter((f.shown * 1000).toLong() + 60_000)
        } else if (f.finished) b.setTimeoutAfter(5 * 60_000L)
        if (!f.finished) b.addAction(0, if (f.running) "Pause" else "Resume", toggle)
        runCatching { m.notify(ID, b.build()) }
    }

    /** Android 16: a live update (promoted to the status bar chip and the lock screen) with the clock's progress. */
    @androidx.annotation.RequiresApi(36)
    private fun live(context: Context, f: FocusState, title: String, text: String, paused: Boolean, open: PendingIntent, toggle: PendingIntent): Notification {
        val progress = if (f.mode == 2 || f.duration <= 0) 0 else (1000 * (1 - f.shown / f.duration)).toInt().coerceIn(0, 1000)
        val style = Notification.ProgressStyle().setProgress(progress).setProgressIndeterminate(f.mode == 2 && f.running)
            .setProgressSegments(listOf(Notification.ProgressStyle.Segment(1000)))
        val b = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_island).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_STOPWATCH).setVisibility(Notification.VISIBILITY_PUBLIC).setGroup("focus_clock")
            .setOngoing(f.running || paused).setStyle(style)
        // Asks Android 16 to promote it (to the status bar chip and the lock screen) while it runs.
        b.addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", f.running); if (paused) putString("android.shortCriticalText", "Paused") })
        if (f.running) {
            b.setUsesChronometer(true).setWhen(end(f)).setChronometerCountDown(f.mode != 2).setShowWhen(true)
            if (f.mode != 2) b.setTimeoutAfter((f.shown * 1000).toLong() + 60_000)
        } else if (f.finished) b.setTimeoutAfter(5 * 60_000L)
        if (!f.finished) b.addAction(Notification.Action.Builder(null, if (f.running) "Pause" else "Resume", toggle).build())
        return b.build()
    }

    private fun clock(seconds: Double): String { val n = seconds.toInt().coerceAtLeast(0); return if (n >= 3600) "%d:%02d:%02d".format(n / 3600, n / 60 % 60, n % 60) else "%d:%02d".format(n / 60, n % 60) }
}
