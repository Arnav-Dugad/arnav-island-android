package io.github.arnavdugad.arnavisland.link

/**
 * Revision 13 (island 0.30), query 6: this phone's text messages on a PC. Op 0 the conversations (u16 offset, u16 max at
 * most 100), newest first; op 1 a conversation's messages (u64 thread, u64 before in ms or 0 for the newest, u16 max at most
 * 100), oldest first; op 2 send one (string address, string text of at most 2000 characters); op 3 mark a conversation read
 * (u64 thread). Each answer starts with u8 state: 1 done, 2 off on this phone, 3 needs Android's permission, 5 failed.
 * Plain JVM code; Sms (on the phone) reads Android's messages into it.
 */
object SmsWire {
    const val THREADS = 0; const val MESSAGES = 1; const val SEND = 2; const val READ = 3
    const val OK = 1; const val OFF = 2; const val PERMISSION = 3; const val FAILED = 5
    const val MAX = 100; const val TEXT_MAX = 2000
    const val PHOTO_LIMIT = 12 * 1024; const val PICTURE_LIMIT = 48 * 1024
    /** A conversation's flags: its last message is this phone's owner's; it has pictures. */
    const val LAST_MINE = 1; const val HAS_PICTURES = 2
    /** A message's flags: sent from this phone; it carries a picture; it wasn't sent (or failed). */
    const val MINE = 1; const val PICTURE = 2; const val NOT_SENT = 4

    sealed interface Request
    class Threads(val offset: Int, val max: Int) : Request
    class Messages(val thread: Long, val before: Long, val max: Int) : Request
    class Send(val address: String, val text: String) : Request
    class Read(val thread: Long) : Request

    /** A question's payload; null when it's malformed (or a message to send is empty, too long or has no usable address). */
    fun request(payload: ByteArray): Request? {
        val r = Reader(payload)
        return when (r.u8()) {
            THREADS -> Threads(r.u16() ?: return null, (r.u16() ?: return null).coerceIn(1, MAX))
            MESSAGES -> { val thread = r.u64() ?: return null; val before = r.u64() ?: return null; val max = r.u16() ?: return null
                if (thread < 0 || before < 0) return null; Messages(thread, before, max.coerceIn(1, MAX)) }
            SEND -> { val address = (r.string(256) ?: return null).trim(); val text = r.string(TEXT_MAX * 4) ?: return null
                if (!address(address) || text.isBlank() || text.length > TEXT_MAX) return null; Send(address, text) }
            READ -> Read((r.u64() ?: return null).takeIf { it >= 0 } ?: return null)
            else -> null
        }
    }
    /** An address to send to: a phone number (digits, with + * # and the usual separators), at most 40 characters. */
    fun address(a: String) = a.length in 1..40 && a.any { it.isDigit() } && a.all { it.isDigit() || it in "+*#()-. " }

    class Thread(val id: Long, val address: String, val name: String, val snippet: String, val date: Long, val unread: Int, val flags: Int, val photo: ByteArray?)
    class Message(val id: Long, val text: String, val date: Long, val flags: Int, val picture: ByteArray?)

    fun state(s: Int) = byteArrayOf(s.toByte())
    /** The conversations (newest first); contacts' photos past the answer's budget are left out. */
    fun threads(list: List<Thread>): ByteArray {
        val shown = list.take(MAX); val b = Bytes().u8(OK).u16(shown.size)
        var budget = Proto.ANSWER_BUDGET - 4096 - shown.sumOf { (it.address.length + it.name.length + it.snippet.length) * 3 + 40 }
        shown.forEach { t ->
            val photo = t.photo?.takeIf { it.size <= PHOTO_LIMIT && it.size <= budget }; if (photo != null) budget -= photo.size
            b.u64(t.id).string(t.address.take(200)).string(t.name.take(200)).string(t.snippet.take(300)).u64(t.date.coerceAtLeast(0)).u8(t.unread.coerceIn(0, 255)).u8(t.flags and 0xFF).blob(photo ?: ByteArray(0))
        }
        return b.build()
    }
    /** A conversation's messages, given newest first and sent oldest first; pictures go to the newest first, within the budget. */
    fun messages(newestFirst: List<Message>): ByteArray {
        val shown = newestFirst.take(MAX)
        var budget = Proto.ANSWER_BUDGET - 4096 - shown.sumOf { minOf(it.text.length, TEXT_MAX * 2) * 3 + 32 }
        val pictures = shown.map { m -> m.picture?.takeIf { it.size <= PICTURE_LIMIT && it.size <= budget }?.also { budget -= it.size } }
        val b = Bytes().u8(OK).u16(shown.size)
        for (i in shown.indices.reversed()) { val m = shown[i]
            b.u64(m.id).string(m.text.take(TEXT_MAX * 2)).u64(m.date.coerceAtLeast(0)).u8(m.flags and 0xFF).blob(pictures[i] ?: ByteArray(0)) }
        return b.build()
    }

    // The PC's side, for tests.
    fun threadsRequest(offset: Int, max: Int) = Bytes().u8(THREADS).u16(offset).u16(max).build()
    fun messagesRequest(thread: Long, before: Long, max: Int) = Bytes().u8(MESSAGES).u64(thread).u64(before).u16(max).build()
    fun sendRequest(address: String, text: String) = Bytes().u8(SEND).string(address).string(text).build()
    fun readRequest(thread: Long) = Bytes().u8(READ).u64(thread).build()
    fun parseThreads(p: ByteArray): List<Thread>? {
        val r = Reader(p); if (r.u8() != OK) return null
        return List(r.u16() ?: return null) { Thread(r.u64() ?: return null, r.string() ?: return null, r.string() ?: return null, r.string() ?: return null, r.u64() ?: return null, r.u8() ?: return null, r.u8() ?: return null, r.blob(PHOTO_LIMIT)?.takeIf { it.isNotEmpty() }) }
    }
    fun parseMessages(p: ByteArray): List<Message>? {
        val r = Reader(p); if (r.u8() != OK) return null
        return List(r.u16() ?: return null) { Message(r.u64() ?: return null, r.string() ?: return null, r.u64() ?: return null, r.u8() ?: return null, r.blob(PICTURE_LIMIT)?.takeIf { it.isNotEmpty() }) }
    }
}
