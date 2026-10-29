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
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
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
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 1.8 (#67): this phone as a PC's microphone (island 0.26, revision 9). Android's voice recorder (with its noise
 * suppression, echo cancelling and level control where the phone has them) in 20 ms frames, sent as they're heard:
 * 48 kHz on the same Wi-Fi or a direct path, 24 kHz through the relay. A notification with Mute and Stop shows while
 * it's on; it carries on with the app in the background.
 *
 * 1.9 (#33): to island 0.27 it goes compressed: Opus where the phone encodes it (Android 10 and later), else AAC-LC,
 * else PCM as before, whichever the PC takes; about 96 kbps on the same Wi-Fi, 64 on a direct path, 32 through the relay.
 */
object PhoneMic {
    internal val shown = MutableStateFlow<String?>(null)
    internal val how = MutableStateFlow("")
    internal val asking = MutableStateFlow(false)
    internal val loud = MutableStateFlow(0f)
    private val hushed = MutableStateFlow(false)
    /** Which PC hears this microphone now (its name), or null. */
    val showing: StateFlow<String?> get() = shown
    /** Between turning it on and the PC's answer. */
    val connecting: StateFlow<Boolean> get() = asking
    /** How loud it is now, 0..1 (0 while muted). */
    val level: StateFlow<Float> get() = loud
    /** 1.9: how it goes, said short ("Opus · 32 kbps", "PCM · 48 kHz"), while on. */
    val format: StateFlow<String> get() = how
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

    private fun text() = if (PhoneMic.muted) "Muted" else "${PhoneMic.how.value.ifEmpty { "${rate / 1000} kHz" }} · live"
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
        PhoneMic.shown.value = null; PhoneMic.asking.value = false; PhoneMic.loud.value = 0f; PhoneMic.how.value = ""
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { finish(); super.onDestroy() }

    private fun stream(peer: String) {
        val link = Hub.link; val pc = Hub.peers.value.firstOrNull { it.id == peer }
        if (link == null || pc == null) { CamWire.fail("Couldn’t reach $pcName"); return }
        rate = if (!pc.internet || pc.path == 2) 48_000 else 24_000
        // 1.9: the best codec this phone can make, offered to a PC that takes one; it answers with the one it took.
        val compressed = pc.revision >= CamWire.REVISION_CODEC && CamWire.codecs
        val bitrate = when { !pc.internet -> 96_000; pc.path == 2 -> 64_000; else -> 32_000 }
        val offered = if (!compressed) null else listOf(CamWire.CODEC_OPUS, CamWire.CODEC_AAC).firstOrNull { MicEncoder.can(it, rate) } ?: CamWire.CODEC_PCM
        val opened = link.openScreen(peer, CamWire.offerMic(rate, 1, Hub.phoneName(), offered))
        if (!running) { opened?.first?.close(); return }
        if (opened == null) { CamWire.fail(if (pc.revision < CamWire.REVISION) "Update Arnav Island on $pcName" else "Couldn’t reach $pcName", if (pc.revision < CamWire.REVISION) "Its microphone needs version 0.26 or later" else ""); return }
        val (s, answer) = opened; session = s
        val reply = ScreenWire.reply(answer)
        if (reply == null || reply.status != 0) { CamWire.fail("$pcName couldn’t use this phone’s microphone", CamWire.statusText(reply?.status)); return }
        val codec = if (offered == null) CamWire.CODEC_PCM else reply.fps.takeIf { it in CamWire.CODEC_PCM..CamWire.CODEC_OPUS } ?: CamWire.CODEC_PCM
        val encoder = if (codec == CamWire.CODEC_PCM) null else MicEncoder.make(codec, rate, bitrate)
            ?: run { CamWire.fail("Couldn’t use the microphone", "This phone can’t make the ${CamWire.codecName(codec)} sound $pcName asked for"); return }
        PhoneMic.how.value = if (encoder == null) "PCM · ${rate / 1000} kHz" else "${CamWire.codecName(codec)} · ${bitrate / 1000} kbps"
        android.util.Log.i("ArnavMic", "offered ${offered?.let(CamWire::codecName)}, sending ${PhoneMic.how.value}")

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
                if (encoder == null) {
                    number++
                    if (!s.send(CamWire.audio(number, heard * 10_000_000L / rate, pcm))) break
                } else if (!encoder.feed(pcm, heard * 1_000_000L / rate) { packet, us -> number++; s.send(CamWire.audio(number, us * 10, packet)) }) break
                heard += samples
            }
            if (running && !s.open) CamWire.fail("The microphone stopped", "Lost the connection to $pcName")
            android.util.Log.i("ArnavMic", "ended: running=$running open=${s.open}, frame $number")
        } finally {
            effects.forEach { runCatching { it.release() } }; runCatching { rec.stop() }; runCatching { rec.release() }; encoder?.release(); PhoneMic.loud.value = 0f
        }
    }
}

/**
 * 1.9 (#33): Android's own encoder for the microphone, fed 20 ms of 16-bit mono at a time and drained at once: Opus
 * (one packet a frame) or AAC-LC (each frame given its ADTS header). Codec set-up buffers are kept to itself.
 */
private class MicEncoder private constructor(private val codec: MediaCodec, private val aac: Boolean, private val rate: Int) {
    companion object {
        private fun mime(codec: Int) = if (codec == CamWire.CODEC_OPUS) MediaFormat.MIMETYPE_AUDIO_OPUS else MediaFormat.MIMETYPE_AUDIO_AAC
        private fun format(codec: Int, rate: Int, bitrate: Int) = MediaFormat.createAudioFormat(mime(codec), rate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, rate / 50 * 2 * 4)
            if (codec == CamWire.CODEC_AAC) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        private fun find(codec: Int, rate: Int): String? {
            if (codec == CamWire.CODEC_OPUS && Build.VERSION.SDK_INT < 29) return null
            return runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(MediaFormat.createAudioFormat(mime(codec), rate, 1)) }.getOrNull()
        }
        /** Whether this phone has an encoder for [codec] at [rate]. */
        fun can(codec: Int, rate: Int) = find(codec, rate) != null
        /** One started, or null when it won't. */
        fun make(codec: Int, rate: Int, bitrate: Int): MicEncoder? {
            val name = find(codec, rate) ?: return null
            val c = runCatching { MediaCodec.createByCodecName(name) }.getOrNull() ?: return null
            return try { c.configure(format(codec, rate, bitrate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); c.start(); MicEncoder(c, codec == CamWire.CODEC_AAC, rate) }
            catch (e: Exception) { android.util.Log.w("ArnavMic", "no ${CamWire.codecName(codec)} encoder", e); runCatching { c.release() }; null }
        }
    }
    private val info = MediaCodec.BufferInfo()

    /** [pcm] starting at [us] in; each packet that comes out to [send] with its time (µs). False once a send fails. */
    fun feed(pcm: ByteArray, us: Long, send: (ByteArray, Long) -> Boolean): Boolean {
        var at = 0; var tries = 0
        while (at < pcm.size && tries < 40) {
            val i = codec.dequeueInputBuffer(5_000)
            if (i < 0) { tries++; if (!drain(send)) return false; continue }
            val buffer = codec.getInputBuffer(i); if (buffer == null) { tries++; continue }
            buffer.clear(); val n = min(buffer.remaining(), pcm.size - at); buffer.put(pcm, at, n)
            codec.queueInputBuffer(i, 0, n, us + at / 2 * 1_000_000L / rate, 0); at += n
        }
        return drain(send)
    }
    private fun drain(send: (ByteArray, Long) -> Boolean): Boolean {
        while (true) {
            val o = codec.dequeueOutputBuffer(info, 0)
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) return true
            if (o < 0) continue
            val buffer = codec.getOutputBuffer(o)
            val bytes = if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) ByteArray(info.size).also { buffer.position(info.offset); buffer.get(it) } else null
            codec.releaseOutputBuffer(o, false)
            if (bytes != null && !send(if (aac) CamWire.adts(bytes.size, rate, 1) + bytes else bytes, info.presentationTimeUs)) return false
        }
    }
    fun release() { runCatching { codec.stop() }; runCatching { codec.release() } }
}
