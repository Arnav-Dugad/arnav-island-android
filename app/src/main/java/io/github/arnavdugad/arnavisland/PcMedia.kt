package io.github.arnavdugad.arnavisland

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.arnavdugad.arnavisland.link.PcStatus
import io.github.arnavdugad.arnavisland.link.PeerView
import io.github.arnavdugad.arnavisland.link.Proto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What plays on your PC, as this phone's own player: on the lock screen, in quick settings' media player and in the
 * notification shade, with the cover, where the song is, and play, pause, skip and seek (a media session). It shows
 * while the PC is here with something to play, follows the island's own player, and goes when it stops; swiped away
 * while paused, it comes back with the next song or when the music starts again.
 */
object PcMedia {
    const val ID = 7
    const val CHANNEL = "pc_media"
    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var shown = ""; private var metaKey = ""; private var coverKey = 0; private var cover: Bitmap? = null
    @Volatile private var dismissed: String? = null

    fun enabled() = Hub.prefs.getBoolean("pcMedia", true)

    /** Brings the player up to date with the PC's status (from any thread). */
    fun update(context: Context, pc: PeerView?, s: PcStatus?) = main.post { apply(context.applicationContext, pc, s) }
    fun clear(context: Context) = main.post { stop(context.applicationContext) }

    private fun apply(context: Context, pc: PeerView?, s: PcStatus?) {
        if (!enabled() || pc == null || !pc.online || s == null || !s.available || s.title.isBlank()) { stop(context); return }
        // Swiped away while paused: it stays away until the music plays again or the song changes.
        val d = dismissed
        if (d != null) { if (s.playing || d != s.title) dismissed = null else { stop(context, keepDismissed = true); return } }
        val ms = session ?: MediaSession(context, "ArnavIslandPc").also { m ->
            m.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = send(Proto.CMD_MEDIA, byteArrayOf(4))
                override fun onPause() = send(Proto.CMD_MEDIA, byteArrayOf(5))
                override fun onSkipToNext() = send(Proto.CMD_MEDIA, byteArrayOf(3))
                override fun onSkipToPrevious() = send(Proto.CMD_MEDIA, byteArrayOf(2))
                override fun onSeekTo(pos: Long) = send(Proto.CMD_SEEK, f64(pos / 1000.0))
            }, main)
            m.setSessionActivity(openApp(context)); m.isActive = true; session = m
        }
        val hash = s.coverHash?.contentHashCode() ?: 0
        if (hash != coverKey) { coverKey = hash; cover = s.cover?.let { Art.bitmap(it, 512) } }
        val key = listOf(s.title, s.artist, s.duration.toLong().toString(), hash.toString(), pc.name).joinToString("\u0000")
        if (key != metaKey) {
            metaKey = key
            ms.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, s.title).putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "On ${pc.name}").putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, listOf(s.artist, pc.name).filter { it.isNotBlank() }.joinToString("  ·  "))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (s.duration * 1000).toLong().coerceAtLeast(0))
                .apply { cover?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }.build())
        }
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        if (s.canNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (s.canPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (s.canSeek && s.duration > 0) actions = actions or PlaybackState.ACTION_SEEK_TO
        val readAt = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - s.at).coerceAtLeast(0)
        ms.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(if (s.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, (s.position * 1000).toLong(), if (s.playing) 1f else 0f, readAt).build())
        // The notification itself changes only when what it shows does (the position moves by itself).
        val look = listOf(key, s.playing, s.canNext, s.canPrevious).joinToString("|")
        if (look == shown) return
        shown = look
        post(context, notification(context, ms, pc, s))
    }

    private fun notification(context: Context, ms: MediaSession, pc: PeerView, s: PcStatus): Notification {
        fun action(icon: Int, title: String, what: String) = Notification.Action.Builder(Icon.createWithResource(context, icon), title, control(context, what)).build()
        return Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(s.title).setContentText(s.artist.ifBlank { "On ${pc.name}" }).setSubText(pc.name)
            .apply { cover?.let { setLargeIcon(it) } }
            .addAction(action(R.drawable.ic_w_prev, "Previous", "prev"))
            .addAction(if (s.playing) action(R.drawable.ic_w_pause, "Pause", "toggle") else action(R.drawable.ic_w_play, "Play", "toggle"))
            .addAction(action(R.drawable.ic_w_next, "Next", "next"))
            .setStyle(Notification.MediaStyle().setMediaSession(ms.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .setContentIntent(openApp(context)).setDeleteIntent(control(context, "dismiss"))
            .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true).setShowWhen(false).setOngoing(s.playing).build()
    }
    private fun post(context: Context, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ID, n) }
    }
    private fun stop(context: Context, keepDismissed: Boolean = false) {
        if (!keepDismissed) dismissed = null
        if (session == null && shown.isEmpty()) return
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(ID) }
        session?.let { runCatching { it.isActive = false; it.release() } }; session = null
        shown = ""; metaKey = ""
    }
    /** The player was swiped away (it can be while paused). */
    fun dismissedNow(context: Context, title: String?) = main.post { dismissed = title ?: ""; stop(context.applicationContext, keepDismissed = true) }

    private fun send(cmd: Int, payload: ByteArray) {
        Hub.scope.launch { Hub.command(cmd, payload, quiet = true); delay(250); Hub.refreshStatus() }
    }
    private fun openApp(context: Context) = PendingIntent.getActivity(context, 77, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun control(context: Context, what: String) = PendingIntent.getBroadcast(context, what.hashCode(), Intent(context, PcMediaReceiver::class.java).setAction(what), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/** The player's buttons (and its being swiped away). */
class PcMediaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Hub.init(context)
        when (intent.action) {
            "dismiss" -> { PcMedia.dismissedNow(context, Hub.status.value?.title); return }
            "prev", "toggle", "next" -> Unit
            else -> return
        }
        val pending = goAsync()
        Hub.scope.launch {
            try {
                val code: Byte = when (intent.action) { "prev" -> 2; "next" -> 3; else -> if (Hub.status.value?.playing == true) 5 else 4 }
                Hub.command(Proto.CMD_MEDIA, byteArrayOf(code), quiet = true); delay(250); Hub.refreshStatus()
            } finally { pending.finish() }
        }
    }
}
