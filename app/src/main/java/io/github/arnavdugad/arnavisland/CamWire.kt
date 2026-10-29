package io.github.arnavdugad.arnavisland

import io.github.arnavdugad.arnavisland.link.Bytes
import io.github.arnavdugad.arnavisland.link.Proto

/**
 * 1.8 (#67): this phone as a PC's webcam or microphone (revision 9, island 0.26), on a mode V connection opened as this
 * phone's screen is. Kind 3, the camera: asked [0xA0, 3, u16 width, u16 height, u8 fps, name] and answered as kind 2,
 * then exactly kind 2's video (frames, feedback, key frames, new limits, the end); the PC sends no input. Kind 4, the
 * microphone: asked [0xA0, 4, u32 sample rate, u8 channels, name], answered the same, then [0xAA, u32 number, u64 time
 * (100 ns), s16le PCM] about every 20 ms until [0xAF].
 */
object CamWire {
    /** The first revision whose PCs take a camera or a microphone. */
    const val REVISION = 9
    const val KIND_CAMERA = 3; const val KIND_MIC = 4
    const val SCREEN_AUDIO = 0xAA

    /** Offering this phone's camera: the picture's size, fps and this phone's name. */
    fun offerCamera(width: Int, height: Int, fps: Int, name: String) = Bytes().u8(Proto.SCREEN_REQUEST).u8(KIND_CAMERA).u16(width).u16(height).u8(fps).string(name).build()
    /** Offering this phone's microphone: its sample rate, channels and this phone's name. */
    fun offerMic(rate: Int, channels: Int, name: String) = Bytes().u8(Proto.SCREEN_REQUEST).u8(KIND_MIC).u32(rate).u8(channels).string(name).build()
    /** One frame of sound: its number, its time (100 ns from the start) and its samples (16-bit, little-endian). */
    fun audio(number: Int, time100ns: Long, pcm: ByteArray) = Bytes().u8(SCREEN_AUDIO).u32(number).u64(time100ns).raw(pcm).build()

    /** A PC's "no" in words (its answer's status). */
    fun statusText(status: Int?) = when (status) { 1 -> "It isn’t allowed there"; 2 -> "It can’t take one yet"; 3 -> "It couldn’t start"; else -> "" }
    /** Why a camera or microphone didn't start, or stopped: said in the app. */
    fun fail(title: String, detail: String = "") { Hub.banners.tryEmit(Banner(Banner.Kind.Failed, title, detail)) }
}
