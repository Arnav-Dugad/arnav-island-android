package io.github.arnavdugad.arnavisland

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.github.arnavdugad.arnavisland.link.Handoff
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Find my phone: the alarm sound, looping at full alarm volume (so it rings on silent), with a vibration, for up to a
 * minute or until "Found it". The alarm volume is put back afterwards.
 */
object Ringer {
    private var player: MediaPlayer? = null; private var vibrator: Vibrator? = null; private var savedVolume = -1
    /** When it started ringing (uptime), for the ring screen's beat. */
    @Volatile var startedAt = 0L; private set
    /** The vibration: two 700 ms beats 400 ms apart, then 900 ms of rest. */
    private val pattern = longArrayOf(0, 700, 400, 700, 900)
    const val CYCLE = 2700L
    /** How strongly it beats [ms] after starting (0..1): up at once as each vibration begins, easing off through it. */
    fun beat(ms: Long): Float { val p = ms % CYCLE; val into = if (p < 700) p else if (p in 1100 until 1800) p - 1100 else return 0f; return (1f - into / 700f).let { it * it } }
    /** How far the rings from the latest beats have spread [ms] after starting (0..1 each; a ring lasts 1.6 s). */
    fun rings(ms: Long): List<Float> { val c = ms - ms % CYCLE; return listOf(c - CYCLE + 1100, c, c + 1100).filter { it in 0..ms }.map { (ms - it) / 1600f }.filter { it <= 1f } }
    private val main = Handler(Looper.getMainLooper())
    fun start(context: Context, from: String) = main.post {
        stopSound(context)
        startedAt = android.os.SystemClock.uptimeMillis()
        Hub.ringing.value = from; if (!Hub.visible) Notify.showRing(context, from)
        val audio = context.getSystemService(AudioManager::class.java)
        runCatching { savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM); audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching { MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setDataSource(context, uri); isLooping = true; prepare(); start()
        } }.getOrNull()
        vibrator = (if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java))
        runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0)) }
        main.postDelayed({ stop(context) }, 60_000)
    }
    private fun stopSound(context: Context) {
        runCatching { player?.stop() }; runCatching { player?.release() }; player = null
        runCatching { vibrator?.cancel() }; vibrator = null
        if (savedVolume >= 0) { runCatching { context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }; savedVolume = -1 }
    }
    fun stop(context: Context) = main.post { main.removeCallbacksAndMessages(null); stopSound(context); Hub.ringing.value = null; Notify.cancel(context, Notify.RING) }
}

/**
 * Music handed over from a PC, played here from the song's own file, from where the PC was. It can be sent back to the
 * PC from where it has got to.
 */
object Player {
    data class Now(val music: Handoff, val peer: String, val from: String, val playing: Boolean, val position: Double, val duration: Double, val at: Long = System.currentTimeMillis()) {
        fun positionNow(now: Long = System.currentTimeMillis()) = if (playing && duration > 0) (position + (now - at) / 1000.0).coerceAtMost(duration) else position
    }
    val now = MutableStateFlow<Now?>(null)
    private var player: MediaPlayer? = null; private var focus: AudioFocusRequest? = null
    private val main = Handler(Looper.getMainLooper())
    private fun publish(playing: Boolean? = null) {
        val p = player ?: return; val n = now.value ?: return
        now.value = n.copy(playing = playing ?: runCatching { p.isPlaying }.getOrDefault(false), position = runCatching { p.currentPosition / 1000.0 }.getOrDefault(n.position),
            duration = runCatching { p.duration / 1000.0 }.getOrDefault(n.duration).coerceAtLeast(0.0), at = System.currentTimeMillis())
    }
    fun play(context: Context, music: Handoff, peer: String, from: String, file: String, start: Double) = main.post {
        release(context)
        val audio = context.getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
        }.build().also { audio.requestAudioFocus(it) }
        player = runCatching { MediaPlayer().apply {
            setAudioAttributes(attributes); setDataSource(File(file).path); prepare()
            if (start > 0 && start < duration / 1000.0 - 2) seekTo((start * 1000).toInt())
            setOnCompletionListener { publish(false) }
            start()
        } }.getOrNull()
        if (player == null) { Hub.banners.tryEmit(Banner(Banner.Kind.Failed, "Couldn't play ${music.title}", "The song’s file isn’t one this phone plays")); return@post }
        now.value = Now(music, peer, from, true, start, music.duration); publish(true)
        Hub.music.value = null
        Hub.banners.tryEmit(Banner(Banner.Kind.Music, "Playing here", music.title))
    }
    fun toggle() = main.post { val p = player ?: return@post; if (p.isPlaying) p.pause() else p.start(); publish() }
    fun pause() = main.post { runCatching { player?.pause() }; publish(false) }
    fun seek(seconds: Double) = main.post { runCatching { player?.seekTo((seconds * 1000).toInt()) }; publish() }
    fun release(context: Context) {
        runCatching { player?.stop() }; runCatching { player?.release() }; player = null
        focus?.let { runCatching { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) } }; focus = null
        now.value = null
    }
    fun close(context: Context) = main.post { release(context) }
    /** Sends what plays back to the PC it came from, from where it has got to, and stops here. */
    fun sendBack(context: Context) = main.post {
        val n = now.value ?: return@post
        // From where it has got to (a song that ended starts again from the top on the PC).
        val position = runCatching { player!!.currentPosition / 1000.0 }.getOrDefault(n.positionNow()).let { if (n.duration > 0 && it >= n.duration - 1) 0.0 else it }
        Hub.handBack(n.music.copy(position = position, playing = true), n.peer); release(context)
    }
}
