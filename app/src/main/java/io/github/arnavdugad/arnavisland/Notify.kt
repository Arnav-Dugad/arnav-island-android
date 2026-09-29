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
import io.github.arnavdugad.arnavisland.link.LinkEvent

/** The app's notifications: staying reachable, pairing, files, music from a PC, find my phone and updates. */
object Notify {
    const val LINK = 1; const val PAIR = 2; const val MUSIC = 3; const val RING = 4; const val UPDATE = 5; const val PHOTO = 6
    const val OFFER = 1000; const val RECEIVED = 5000
    private const val CH_LINK = "link"; private const val CH_FILES = "files"; private const val CH_RING = "ring"; private const val CH_UPDATES = "updates"; private const val CH_MUSIC = "music"

    fun channels(context: Context) {
        val m = context.getSystemService(NotificationManager::class.java)
        m.createNotificationChannels(listOf(
            NotificationChannel(CH_LINK, "Staying reachable", NotificationManager.IMPORTANCE_MIN).apply { description = "Shown while your PCs can reach this phone"; setShowBadge(false) },
            NotificationChannel(CH_FILES, "Files and pairing", NotificationManager.IMPORTANCE_HIGH).apply { description = "Files from your PCs, and pairing codes" },
            NotificationChannel(CH_MUSIC, "Music from your PC", NotificationManager.IMPORTANCE_HIGH).apply { description = "Continue what plays on your PC here" },
            NotificationChannel(CH_RING, "Find my phone", NotificationManager.IMPORTANCE_HIGH).apply { description = "When a PC rings this phone"; setSound(null, null); enableVibration(false); setBypassDnd(true) },
            NotificationChannel(CH_UPDATES, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "New versions of Arnav Island" },
            NotificationChannel(PcMedia.CHANNEL, "Your PC’s music", NotificationManager.IMPORTANCE_LOW).apply { description = "What plays on your PC, with its controls, on the lock screen and in quick settings"; setShowBadge(false) },
        ))
    }
    private fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun post(context: Context, id: Int, n: Notification) { if (allowed(context)) runCatching { NotificationManagerCompat.from(context).notify(id, n) } }
    fun cancel(context: Context, id: Int) = runCatching { NotificationManagerCompat.from(context).cancel(id) }

    private fun open(context: Context, extra: String? = null): PendingIntent =
        PendingIntent.getActivity(context, extra.hashCode(), Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).apply { extra?.let { putExtra("open", it) } },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun service(context: Context, action: String, transfer: Int = 0): PendingIntent =
        PendingIntent.getService(context, action.hashCode() * 31 + transfer, Intent(context, LinkService::class.java).setAction(action).putExtra("transfer", transfer),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun link(context: Context, text: String): Notification = NotificationCompat.Builder(context, CH_LINK)
        .setSmallIcon(R.drawable.ic_stat_island).setContentTitle("Reachable by your PCs").setContentText(text)
        .setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_MIN).setContentIntent(open(context))
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()

    fun pairing(context: Context, e: LinkEvent.PairCode) = post(context, PAIR, NotificationCompat.Builder(context, CH_FILES)
        .setSmallIcon(R.drawable.ic_stat_island).setContentTitle(if (e.confirmed) "Confirm on ${e.name}" else "Pair with ${e.name}?")
        .setContentText("Code ${"%06d".format(e.code).chunked(3).joinToString(" ")}  ·  ${if (e.confirmed) "choose Pair there" else "open to confirm"}")
        .setCategory(NotificationCompat.CATEGORY_STATUS).setAutoCancel(true).setContentIntent(open(context, "pair")).setTimeoutAfter(60_000).build())

    fun offer(context: Context, e: LinkEvent.Offer) = post(context, OFFER + e.transfer, NotificationCompat.Builder(context, CH_FILES)
        .setSmallIcon(R.drawable.ic_stat_island).setContentTitle("${e.name} is sending").setContentText("${e.title}  ·  ${Hub.sizeText(e.size)}")
        .addAction(0, "Accept", service(context, LinkService.ACCEPT, e.transfer)).addAction(0, "Decline", service(context, LinkService.DECLINE, e.transfer))
        .setContentIntent(open(context)).setTimeoutAfter(120_000).setAutoCancel(true).build())

    fun received(context: Context, e: LinkEvent.Received) {
        if (Hub.visible) return
        val uri = e.shown.firstOrNull()?.let { DownloadsInbox.uriOf(it) }
        val tap = if (uri != null && e.count == 1) PendingIntent.getActivity(context, e.transfer, Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE) else open(context, "send")
        post(context, RECEIVED + e.transfer, NotificationCompat.Builder(context, CH_FILES).setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(if (e.taken) "Took ${e.title}" else "${e.title} from ${e.name}").setContentText("${Hub.sizeText(e.size)}  ·  in Downloads › Arnav Island")
            .setContentIntent(tap).setAutoCancel(true).build())
    }

    fun music(context: Context, e: LinkEvent.Music) = post(context, MUSIC, NotificationCompat.Builder(context, CH_MUSIC)
        .setSmallIcon(R.drawable.ic_stat_island).setContentTitle("Continue on this phone?").setContentText("${e.music.title}${if (e.music.artist.isNotEmpty()) " · ${e.music.artist}" else ""}  ·  from ${e.name}")
        .setLargeIcon(e.music.cover?.let { Art.bitmap(it, 256) })
        .addAction(0, "Play here", open(context, "music_play")).addAction(0, "Not now", service(context, LinkService.NOT_NOW, e.transfer))
        .setContentIntent(open(context, "music")).setTimeoutAfter(60_000).setAutoCancel(true).build())

    fun ring(context: Context, from: String): Notification = NotificationCompat.Builder(context, CH_RING)
        .setSmallIcon(R.drawable.ic_stat_island).setContentTitle("$from is looking for this phone").setContentText("Tap Found it to stop the sound")
        .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX).setOngoing(true)
        .addAction(0, "Found it", service(context, LinkService.STOP_RING)).setContentIntent(open(context, "ring"))
        .setFullScreenIntent(open(context, "ring"), true).build()
    fun showRing(context: Context, from: String) = post(context, RING, ring(context, from))

    fun photo(context: Context, from: String) = post(context, PHOTO, NotificationCompat.Builder(context, CH_FILES)
        .setSmallIcon(R.drawable.ic_stat_island).setContentTitle("$from would like a photo").setContentText("Tap to take it; it lands on the island’s Shelf")
        .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(open(context, "camera")).setAutoCancel(true).setTimeoutAfter(120_000).build())

    fun update(context: Context, title: String, text: String) = post(context, UPDATE, NotificationCompat.Builder(context, CH_UPDATES)
        .setSmallIcon(R.drawable.ic_stat_island).setContentTitle(title).setContentText(text).setContentIntent(open(context, "updates")).setAutoCancel(true).build())
}
