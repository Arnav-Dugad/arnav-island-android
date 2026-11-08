package io.github.arnavdugad.arnavisland.link

/**
 * Revision 12 (island 0.29): a notification in full. It follows the notification frame's actions as one block,
 * [0xE1, u32 n, n bytes]: the app's package, the conversation's key and title, flags ([GROUP], [CONVERSATION], [CLEARABLE]),
 * when it was posted (u64 ms), up to eight of the conversation's messages (sender, text, u64 ms, u8 1 when this phone's owner
 * sent it), the sender's picture (<= 24 KB), a picture the message carries (<= 64 KB) and up to five suggested replies.
 * Islands before 0.29 stop reading after the actions, so they get the notification as before.
 */
object NoticeWire {
    const val REVISION = 12
    const val MARK = 0xE1
    /** The action number a PC sends to have this phone dismiss the notification. */
    const val DISMISS = 255
    const val GROUP = 1; const val CONVERSATION = 2; const val CLEARABLE = 8
    const val AVATAR_LIMIT = 24 * 1024; const val PICTURE_LIMIT = 64 * 1024

    data class Message(val sender: String, val text: String, val time: Long, val me: Boolean)
    class Full(
        val pkg: String, val conversation: String = "", val title: String = "", val flags: Int = 0, val posted: Long = 0,
        val messages: List<Message> = emptyList(), val avatar: ByteArray? = null, val picture: ByteArray? = null, val replies: List<String> = emptyList(),
    )

    /** The block, marker and length first. Text is cut to what the island keeps; pictures too large are left out. */
    fun block(f: Full): ByteArray {
        val b = Bytes().string(f.pkg.take(200)).string(f.conversation.take(120)).string(f.title.take(200)).u8(f.flags and 0xFF).u64(f.posted.coerceAtLeast(0))
        val ms = f.messages.filter { it.text.isNotBlank() }.takeLast(8)
        b.u8(ms.size); ms.forEach { b.string(it.sender.take(120)).string(it.text.take(1000)).u64(it.time.coerceAtLeast(0)).u8(if (it.me) 1 else 0) }
        b.blob(f.avatar?.takeIf { it.size <= AVATAR_LIMIT } ?: ByteArray(0)).blob(f.picture?.takeIf { it.size <= PICTURE_LIMIT } ?: ByteArray(0))
        val rs = f.replies.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(5)
        b.u8(rs.size); rs.forEach { b.string(it.take(120)) }
        val body = b.build()
        return Bytes().u8(MARK).u32(body.size).raw(body).build()
    }
}
