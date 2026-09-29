package io.github.arnavdugad.arnavisland.link

import java.io.InputStream
import java.io.OutputStream

/** A PC (or phone) on the network: paired ones are remembered, others are shown while they announce themselves. */
data class PeerView(
    val id: String, val name: String, val paired: Boolean, val online: Boolean, val phone: Boolean,
    val version: Int, val revision: Int, val address: String?,
    /** Here only through the relay (on another network). */
    val internet: Boolean = false,
) {
    /** Revision 2 (Arnav Island 0.19 and later) answers remote control, notices and find-my-phone. */
    val remote get() = version >= Proto.VERSION && revision >= 2
}

/** Music handed from one device to another: what plays, where it is, and (for a song the island plays) its file. */
data class Handoff(
    val title: String, val artist: String = "", val album: String = "", val app: String = "", val fileName: String = "",
    val position: Double = 0.0, val duration: Double = 0.0, val playing: Boolean = true, val fileSize: Long = 0, val cover: ByteArray? = null,
) {
    override fun equals(other: Any?) = other is Handoff && title == other.title && artist == other.artist && position == other.position && fileSize == other.fileSize
    override fun hashCode() = title.hashCode() * 31 + artist.hashCode()
}

/** An item on a PC's Shelf, as listed to a paired device. */
class ShelfItem(val name: String, val size: Long, val folder: Boolean, val preview: ByteArray?)
/** A PC's Shelf: shared (false: that PC keeps it to itself), its items, or why it couldn't be asked. */
class ShelfList(val shared: Boolean, val items: List<ShelfItem>, val error: String? = null)

/** What the PC reports for the remote: its media, sound, battery and a few readings. */
data class PcStatus(
    val available: Boolean, val playing: Boolean, val canPrevious: Boolean, val canNext: Boolean, val canToggle: Boolean, val canSeek: Boolean,
    val muted: Boolean, val charging: Boolean, val batteryPresent: Boolean,
    val position: Double, val duration: Double, val volume: Int, val battery: Int, val cpu: Int,
    val title: String, val artist: String, val app: String, val pcName: String, val weather: String,
    /** The cover: null when there is none; [coverHash] names it (the phone sends it back so an unchanged cover isn't resent). */
    val cover: ByteArray?, val coverHash: ByteArray?,
    /** When this was read (milliseconds, the phone's clock), for moving the position on while it plays. */
    val at: Long = System.currentTimeMillis(),
    /** The island's universal clipboard is on (0.20): copies on this phone go to the PC as the app opens. */
    val clipboard: Boolean = false,
) {
    fun positionNow(now: Long = System.currentTimeMillis()): Double =
        if (playing && duration > 0) (position + (now - at) / 1000.0).coerceAtMost(duration) else position
    override fun equals(other: Any?) = other is PcStatus && other.title == title && other.playing == playing && other.position == position && other.volume == volume && other.muted == muted && other.at == at
    override fun hashCode() = title.hashCode() * 31 + at.hashCode()
}

class RemoteReply(val status: Int, val payload: ByteArray) { val ok get() = status == Proto.OK }

/** The song's lyrics from the PC (revision 3): state 0 off there, 1 being looked for, 2 found, 3 none; key "title<TAB>artist". */
data class Lyrics(val state: Int, val key: String, val lines: List<LyricsLine>)
/** A line: its start (seconds), text, and each word's start (seconds, and where in the text it begins). */
data class LyricsLine(val time: Double, val text: String, val words: List<Pair<Double, Int>>)

sealed interface LinkEvent {
    data class Peers(val peers: List<PeerView>) : LinkEvent
    /** The six digits both show. [confirmed]: this phone already said yes (it scanned that PC's QR code); only the PC asks. */
    data class PairCode(val peer: String, val name: String, val code: Int, val confirmed: Boolean = false) : LinkEvent
    data class Paired(val peer: String, val name: String, val ok: Boolean, val detail: String) : LinkEvent
    data class Offer(val transfer: Int, val peer: String, val name: String, val title: String, val count: Int, val size: Long, val folder: Boolean) : LinkEvent
    data class Progress(val transfer: Int, val peer: String, val name: String, val title: String, val done: Long, val total: Long, val outgoing: Boolean) : LinkEvent
    data class Received(val transfer: Int, val peer: String, val name: String, val title: String, val count: Int, val size: Long, val shown: List<String>, val taken: Boolean) : LinkEvent
    data class Sent(val transfer: Int, val peer: String, val name: String, val title: String, val count: Int, val size: Long) : LinkEvent
    data class Failed(val transfer: Int, val peer: String, val name: String, val title: String, val detail: String, val outgoing: Boolean) : LinkEvent
    data class Music(val transfer: Int, val peer: String, val name: String, val music: Handoff) : LinkEvent
    data class MusicFile(val transfer: Int, val peer: String, val name: String, val music: Handoff, val shown: String) : LinkEvent
    data class Ring(val peer: String, val name: String) : LinkEvent
    /** A paired PC asks for a photo for its Shelf. */
    data class PhotoRequested(val peer: String, val name: String) : LinkEvent
    /** A pairing code this device offers (null when it couldn't be made). */
    data class PairingCode(val code: String?) : LinkEvent
}

/** Something to send: its path as the other side will keep it ("Photos/a.jpg"), its size and how to read it. */
class Source(val rel: String, val size: Long, val open: () -> InputStream)

/** Where received files go. */
interface Inbox {
    /** A free name for a top-level folder ("Photos (2)"). */
    fun folder(name: String): String
    /** A file at these safe path parts (the first may be a folder name from [folder]); null when it can't be made. */
    fun create(parts: List<String>, size: Long): Sink?
    /** A handed-off song's file. */
    fun song(name: String, size: Long): Sink?
    fun room(bytes: Long): Boolean
}
interface Sink {
    val out: OutputStream
    /** Keeps the file under its name; what to show for it (a path or a name), or null when it couldn't be kept. */
    fun commit(): String?
    fun abort()
}

/** Keeps the identity and the paired devices. */
interface LinkStore {
    fun loadIdentity(): ByteArray?
    fun saveIdentity(bytes: ByteArray): Boolean
    fun loadPeers(): String?
    fun savePeers(text: String)
}
