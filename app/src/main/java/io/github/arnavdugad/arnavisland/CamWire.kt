package io.github.arnavdugad.arnavisland

import io.github.arnavdugad.arnavisland.link.Bytes
import io.github.arnavdugad.arnavisland.link.Proto

/**
 * 1.8 (#67): this phone as a PC's webcam or microphone (revision 9, island 0.26), on a mode V connection opened as this
 * phone's screen is. Kind 3, the camera: asked [0xA0, 3, u16 width, u16 height, u8 fps, name] and answered as kind 2,
 * then exactly kind 2's video (frames, feedback, key frames, new limits, the end); the PC sends no input. Kind 4, the
 * microphone: asked [0xA0, 4, u32 sample rate, u8 channels, name], answered the same, then [0xAA, u32 number, u64 time
 * (100 ns), s16le PCM] about every 20 ms until [0xAF].
 *
 * 1.9 (#33, revision 10, island 0.27): the microphone's ask gains [u8 codec] after the channels (0 PCM, 1 AAC-LC with an
 * ADTS header on each frame, 2 Opus, one 20 ms packet a frame), and the answer's fps byte is the codec the PC took.
 */
object CamWire {
    /** The first revision whose PCs take a camera or a microphone. */
    const val REVISION = 9
    /** 1.9: the first revision whose PCs take compressed sound (and show the camera as a real one on Windows 11). */
    const val REVISION_CODEC = 10
    /** 1.9: whether this app announces that revision too (the PC reads the codec byte only from a phone that does). */
    val codecs get() = Proto.REVISION >= REVISION_CODEC
    const val KIND_CAMERA = 3; const val KIND_MIC = 4
    const val SCREEN_AUDIO = 0xAA
    /** 1.9: the microphone's codecs. */
    const val CODEC_PCM = 0; const val CODEC_AAC = 1; const val CODEC_OPUS = 2

    /** Offering this phone's camera: the picture's size, fps and this phone's name. */
    fun offerCamera(width: Int, height: Int, fps: Int, name: String) = Bytes().u8(Proto.SCREEN_REQUEST).u8(KIND_CAMERA).u16(width).u16(height).u8(fps).string(name).build()
    /** Offering this phone's microphone: its sample rate, channels and this phone's name; 1.9: and its [codec], to a PC that takes one. */
    fun offerMic(rate: Int, channels: Int, name: String, codec: Int? = null) =
        Bytes().u8(Proto.SCREEN_REQUEST).u8(KIND_MIC).u32(rate).u8(channels).apply { if (codec != null) u8(codec) }.string(name).build()
    /** One frame of sound: its number, its time (100 ns from the start) and its samples (16-bit, little-endian); 1.9: or one encoded packet. */
    fun audio(number: Int, time100ns: Long, pcm: ByteArray) = Bytes().u8(SCREEN_AUDIO).u32(number).u64(time100ns).raw(pcm).build()
    /** 1.9: a codec's name, as the card and the notification say it. */
    fun codecName(codec: Int) = when (codec) { CODEC_OPUS -> "Opus"; CODEC_AAC -> "AAC"; else -> "PCM" }

    /** 1.9: the 7-byte ADTS header before one AAC-LC frame of [length] bytes ([rate] Hz, [channels]). */
    fun adts(length: Int, rate: Int, channels: Int): ByteArray {
        val index = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350).indexOf(rate).let { if (it < 0) 3 else it }
        val full = length + 7
        return byteArrayOf(0xFF.toByte(), 0xF1.toByte(), ((1 shl 6) or (index shl 2) or (channels shr 2 and 1)).toByte(),
            (((channels and 3) shl 6) or (full shr 11 and 3)).toByte(), (full shr 3 and 0xFF).toByte(), (((full and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte())
    }

    /** A PC's "no" in words (its answer's status). */
    fun statusText(status: Int?) = when (status) { 1 -> "It isn’t allowed there"; 2 -> "It can’t take one yet"; 3 -> "It couldn’t start"; else -> "" }
    /** Why a camera or microphone didn't start, or stopped: said in the app. */
    fun fail(title: String, detail: String = "") { Hub.banners.tryEmit(Banner(Banner.Kind.Failed, title, detail)) }
}
