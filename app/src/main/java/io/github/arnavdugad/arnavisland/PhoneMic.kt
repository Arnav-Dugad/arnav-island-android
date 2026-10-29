package io.github.arnavdugad.arnavisland

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.arnavdugad.arnavisland.link.Link
import io.github.arnavdugad.arnavisland.link.Proto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 1.8 (#67): this phone as a PC's microphone (island 0.26, revision 9). Android's voice recorder (with its noise
 * suppression, echo cancelling and level control where the phone has them) in 20 ms frames, sent as they're heard:
 * 48 kHz on the same Wi-Fi or a direct path, 24 kHz through the relay. A notification with Mute and Stop shows while
 * it's on; it carries on with the app in the background.
 */
object PhoneMic {
    internal val shown = MutableStateFlow<String?>(null)
    internal val asking = MutableStateFlow(false)
    internal val loud = MutableStateFlow(0f)
    private val hushed = MutableStateFlow(false)
    /** Which PC hears this microphone now (its name), or null. */
    val showing: StateFlow<String?> get() = shown
    /** Between turning it on and the PC's answer. */
    val connecting: StateFlow<Boolean> get() = asking
    /** How loud it is now, 0..1 (0 while muted). */
    val level: StateFlow<Float> get() = loud
    /** Whether it's muted (silence still goes, so the PC's side keeps time). */
    val isMuted: StateFlow<Boolean> get() = hushed
    var muted: Boolean
        get() = hushed.value
        set(value) { hushed.value = value; PhoneMicService.instance?.refresh() }

    /** Starts this microphone on [peer]; a banner says why when it can't (no permission, an older island, no PC). */
    fun start(context: Context, peer: String) {
        val pc = Hub.peers.value.firstOrNull { it.id == peer }; val name = pc?.name ?: "your PC"
        if (shown.value != null || asking.value) return
        if (pc != null && pc.revision < CamWire.REVISION) return CamWire.fail("Update Arnav Island on $name", "Its microphone needs version 0.26 or later")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return CamWire.fail("Microphone not allowed", "Allow Arnav Island to use the microphone in Settings › Apps")
        if (pc == null || !pc.online || Hub.link == null) return CamWire.fail("Couldn’t reach $name")
        asking.value = true; hushed.value = false
        runCatching { ContextCompat.startForegroundService(context, Intent(context, PhoneMicService::class.java).putExtra("peer", peer)) }
            .onFailure { asking.value = false; CamWire.fail("Couldn’t turn on the microphone", it.message.orEmpty()) }
    }
    fun stop(context: Context) { runCatching { context.startService(Intent(context, PhoneMicService::class.java).setAction(PhoneMicService.STOP)) } }
}

/** The recorder and its connection: one thread reads 20 ms at a time and sends it; another listens for the PC's end. */
class PhoneMicService : Service() {
    companion object {
        const val STOP = "io.github.arnavdugad.arnavisland.MIC_STOP"; const val MUTE = "io.github.arnavdugad.arnavisland.MIC_MUTE"
        @Volatile var instance: PhoneMicService? = null; private set
    }
    @Volatile private var running = false; @Volatile private var session: Link.ScreenSession? = null
    private var done = false; private var pcName = "your PC"; @Volatile private var rate = 48_000
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Hub.init(this); Notify.channels(this)
        when (intent?.action) {
            STOP -> { finish(); return START_NOT_STICKY }
            MUTE -> { if (running) PhoneMic.muted = !PhoneMic.muted else if (instance == null) stopSelf(); return START_NOT_STICKY }
        }
        val peer = intent?.getStringExtra("peer")
        if (running) return START_NOT_STICKY
        pcName = Hub.peers.value.firstOrNull { it.id == peer }?.name ?: "your PC"
        runCatching {
            ServiceCompat.startForeground(this, Notify.MIC, notification("Connecting…"), if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        }.onFailure { android.util.Log.w("ArnavMic", "no foreground", it); PhoneMic.asking.value = false; CamWire.fail("Couldn’t turn on the microphone", "Open Arnav Island and try again"); stopSelf(); return START_NOT_STICKY }
        if (peer == null) { PhoneMic.asking.value = false; finish(); return START_NOT_STICKY }
        running = true; done = false; instance = this
        thread(name = "phone-mic", isDaemon = true) {
            try { stream(peer) } catch (e: Throwable) { android.util.Log.w("ArnavMic", "mic failed", e); if (running) CamWire.fail("The microphone stopped", "Couldn’t keep it on $pcName") }
            finally { main.post { finish() } }
        }
        return START_NOT_STICKY
    }

    private fun text() = if (PhoneMic.muted) "Muted" else "${rate / 1000} kHz · live"
    private fun notification(text: String) = NotificationCompat.Builder(this, Notify.CH_CAMERA_MIC)
        .setSmallIcon(R.drawable.ic_stat_island).setContentTitle("Microphone on $pcName").setContentText(text).setOngoing(true).setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_TRANSPORT).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(PendingIntent.getActivity(this, 14, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, if (PhoneMic.muted) "Unmute" else "Mute", PendingIntent.getService(this, 15, Intent(this, PhoneMicService::class.java).setAction(MUTE), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Stop", PendingIntent.getService(this, 16, Intent(this, PhoneMicService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)).build()
    /** The notification as things are now (muted or not). */
    internal fun refresh() { if (running && PhoneMic.shown.value != null) runCatching { getSystemService(NotificationManager::class.java).notify(Notify.MIC, notification(text())) } }

    private fun finish() {
        if (done) return
        done = true; running = false; val s = session; session = null
        if (s != null) thread(name = "phone-mic-stop", isDaemon = true) { runCatching { s.send(byteArrayOf(Proto.SCREEN_STOP.toByte())) }; s.close() }
        if (instance === this) instance = null
        PhoneMic.shown.value = null; PhoneMic.asking.value = false; PhoneMic.loud.value = 0f
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { finish(); super.onDestroy() }

    private fun stream(peer: String) {
        val link = Hub.link; val pc = Hub.peers.value.firstOrNull { it.id == peer }
        if (link == null || pc == null) { CamWire.fail("Couldn’t reach $pcName"); return }
        rate = if (!pc.internet || pc.path == 2) 48_000 else 24_000
        val opened = link.openScreen(peer, CamWire.offerMic(rate, 1, Hub.phoneName()))
        if (!running) { opened?.first?.close(); return }
        if (opened == null) { CamWire.fail(if (pc.revision < CamWire.REVISION) "Update Arnav Island on $pcName" else "Couldn’t reach $pcName", if (pc.revision < CamWire.REVISION) "Its microphone needs version 0.26 or later" else ""); return }
        val (s, answer) = opened; session = s
        val reply = ScreenWire.reply(answer)
        if (reply == null || reply.status != 0) { CamWire.fail("$pcName couldn’t use this phone’s microphone", CamWire.statusText(reply?.status)); return }

        val samples = rate / 50; val pcm = ByteArray(samples * 2)
        val least = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(least, pcm.size * 8))
        val effects = ArrayList<AudioEffect>()
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) { CamWire.fail("Couldn’t use the microphone", "Another app may be using it"); return }
            // The phone's own voice clean-up, each where it has it.
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; effects += it } }
            if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; effects += it } }
            if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(rec.audioSessionId)?.let { it.enabled = true; effects += it } }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { CamWire.fail("Couldn’t use the microphone", "Another app may be using it"); return }
            PhoneMic.shown.value = pcName; PhoneMic.asking.value = false; refresh()
            // The PC's end (it sends nothing else).
            thread(name = "phone-mic-in", isDaemon = true) {
                while (running && s.open) { val m = s.receive(500) ?: continue; if (m.isNotEmpty() && (m[0].toInt() and 0xFF) == Proto.SCREEN_STOP) { android.util.Log.i("ArnavMic", "the PC ended it"); running = false } }
                running = false
            }
            var number = 0; var heard = 0L; var shownLevel = 0f
            while (running && s.open) {
                var got = 0
                while (got < pcm.size && running) { val n = rec.read(pcm, got, pcm.size - got); if (n <= 0) break; got += n }
                if (!running) break
                if (got < pcm.size) { CamWire.fail("The microphone stopped", "Another app may have taken it"); running = false; break }
                // How loud: the frame's RMS in dB, -54..0 as 0..1, rising at once and falling gently.
                var sum = 0.0; var i = 0
                while (i < pcm.size) { val v = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toDouble(); sum += v * v; i += 2 }
                val db = 20 * log10(max(1.0, sqrt(sum / samples)) / 32768.0)
                val now = if (PhoneMic.muted) 0f else ((db + 54) / 54).toFloat().coerceIn(0f, 1f)
                shownLevel = if (now > shownLevel) now else shownLevel * .8f + now * .2f; PhoneMic.loud.value = shownLevel
                if (PhoneMic.muted) pcm.fill(0)
                number++
                if (!s.send(CamWire.audio(number, heard * 10_000_000L / rate, pcm))) break
                heard += samples
            }
            if (running && !s.open) CamWire.fail("The microphone stopped", "Lost the connection to $pcName")
            android.util.Log.i("ArnavMic", "ended: running=$running open=${s.open}, frame $number")
        } finally {
            effects.forEach { runCatching { it.release() } }; runCatching { rec.stop() }; runCatching { rec.release() }; PhoneMic.loud.value = 0f
        }
    }
}
