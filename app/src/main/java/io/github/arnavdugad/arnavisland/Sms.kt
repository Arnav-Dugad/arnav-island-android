package io.github.arnavdugad.arnavisland

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import io.github.arnavdugad.arnavisland.link.SmsWire

/**
 * 1.13 (island 0.30, query 6): this phone's text messages on your PCs: the conversations, a conversation's messages
 * (with an MMS's picture), sending one, and marking one read (Android lets only the default SMS app do that). Only while
 * Settings › "Text messages on my PCs" is on (off at first) and Android allows the app to read and send SMS. Names and
 * faces come from your contacts when the app may read them. Nothing is kept or logged here.
 */
object Sms {
    const val PREF = "pcSms"
    fun enabled() = Hub.prefs.getBoolean(PREF, false)
    /** What Android must allow (contacts are asked for too, but are optional: without them a conversation shows its number). */
    val required = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
    val asked = required + Manifest.permission.READ_CONTACTS
    private fun granted(context: Context, p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    fun permitted(context: Context) = required.all { granted(context, it) }

    /** A PC's question (query 6): its answer. */
    fun answer(context: Context, payload: ByteArray): ByteArray {
        if (!enabled()) return SmsWire.state(SmsWire.OFF)
        if (!permitted(context)) return SmsWire.state(SmsWire.PERMISSION)
        val r = SmsWire.request(payload) ?: return SmsWire.state(SmsWire.FAILED)
        return runCatching {
            when (r) {
                is SmsWire.Threads -> SmsWire.threads(threads(context, r.offset, r.max))
                is SmsWire.Messages -> SmsWire.messages(messages(context, r.thread, r.before, r.max))
                is SmsWire.Send -> SmsWire.state(if (send(context, r.address, r.text)) SmsWire.OK else SmsWire.FAILED)
                is SmsWire.Read -> SmsWire.state(if (markRead(context, r.thread)) SmsWire.OK else SmsWire.FAILED)
            }
        }.getOrElse { android.util.Log.w("ArnavSms", "query failed: ${it.javaClass.simpleName}"); SmsWire.state(SmsWire.FAILED) }
    }

    private val MMS: Uri = Uri.parse("content://mms")
    private val MMS_PART: Uri = Uri.parse("content://mms/part")
    private fun Cursor.str(name: String): String = getColumnIndex(name).takeIf { it >= 0 }?.let { getString(it) }.orEmpty()
    private fun Cursor.long(name: String): Long = getColumnIndex(name).takeIf { it >= 0 }?.let { getLong(it) } ?: 0L
    private fun Cursor.int(name: String): Int = getColumnIndex(name).takeIf { it >= 0 }?.let { getInt(it) } ?: 0

    // ---- the conversations ----
    private class Contact(val name: String, val photo: ByteArray?)
    private fun threads(context: Context, offset: Int, max: Int): List<SmsWire.Thread> {
        val resolver = context.contentResolver
        class Row(val id: Long, val date: Long, val recipients: String, val snippet: String, val pictures: Boolean)
        val rows = ArrayList<Row>()
        val simple = runCatching {
            resolver.query(Uri.parse("content://mms-sms/conversations?simple=true"), arrayOf("_id", "date", "recipient_ids", "snippet", "has_attachment"), null, null, "date DESC")?.use { c ->
                if (c.moveToPosition(offset)) do { rows += Row(c.long("_id"), c.long("date"), c.str("recipient_ids"), c.str("snippet"), c.int("has_attachment") != 0) } while (rows.size < max && c.moveToNext())
                true
            } ?: false
        }.getOrDefault(false)
        if (!simple) return smsThreads(context, offset, max)
        // Each recipient's address, from Android's canonical addresses (one query).
        val addresses = HashMap<String, String>()
        runCatching { resolver.query(Uri.parse("content://mms-sms/canonical-addresses"), arrayOf("_id", "address"), null, null, null)?.use { c -> while (c.moveToNext()) addresses[c.str("_id")] = c.str("address") } }
        val unread = unreadCounts(context)
        val contacts = HashMap<String, Contact?>()
        return rows.map { row ->
            val list = row.recipients.split(' ').mapNotNull { addresses[it]?.takeIf { a -> a.isNotBlank() } }
            val people = list.map { a -> contacts.getOrPut(a) { contact(context, a) } }
            val name = list.indices.joinToString(", ") { people[it]?.name ?: list[it] }
            val last = lastIsMine(context, row.id)
            val flags = (if (last) SmsWire.LAST_MINE else 0) or (if (row.pictures) SmsWire.HAS_PICTURES else 0)
            SmsWire.Thread(row.id, list.joinToString(", "), name, row.snippet, row.date, unread[row.id] ?: 0, flags, if (list.size == 1) people[0]?.photo else null)
        }
    }
    /** Where the combined conversations can't be read: SMS only, gathered by conversation from the newest message down. */
    private fun smsThreads(context: Context, offset: Int, max: Int): List<SmsWire.Thread> {
        val seen = LinkedHashMap<Long, SmsWire.Thread>(); val unread = unreadCounts(context); val contacts = HashMap<String, Contact?>()
        context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE), null, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
            var scanned = 0
            while (c.moveToNext() && seen.size < offset + max && scanned++ < 20_000) {
                val id = c.long(Telephony.Sms.THREAD_ID); if (id in seen) continue
                val address = c.str(Telephony.Sms.ADDRESS); val who = contacts.getOrPut(address) { contact(context, address) }
                seen[id] = SmsWire.Thread(id, address, who?.name ?: address, c.str(Telephony.Sms.BODY).take(300), c.long(Telephony.Sms.DATE), unread[id] ?: 0,
                    if (mine(c.int(Telephony.Sms.TYPE))) SmsWire.LAST_MINE else 0, who?.photo)
            }
        }
        return seen.values.drop(offset).take(max)
    }
    private fun unreadCounts(context: Context): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        for (uri in listOf(Telephony.Sms.CONTENT_URI, MMS)) runCatching {
            context.contentResolver.query(uri, arrayOf("thread_id"), "read = 0", null, null)?.use { c -> while (c.moveToNext()) { val t = c.getLong(0); out[t] = (out[t] ?: 0) + 1 } }
        }
        return out
    }
    /** Whether a conversation's newest message (SMS or MMS) is this phone's owner's. */
    private fun lastIsMine(context: Context, thread: Long): Boolean {
        var smsDate = -1L; var smsMine = false; var mmsDate = -1L; var mmsMine = false
        runCatching { context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.DATE, Telephony.Sms.TYPE), "${Telephony.Sms.THREAD_ID} = ?", arrayOf(thread.toString()), "${Telephony.Sms.DATE} DESC")?.use { c ->
            if (c.moveToFirst()) { smsDate = c.getLong(0); smsMine = mine(c.getInt(1)) } } }
        runCatching { context.contentResolver.query(MMS, arrayOf("date", "msg_box"), "thread_id = ?", arrayOf(thread.toString()), "date DESC")?.use { c ->
            if (c.moveToFirst()) { mmsDate = c.getLong(0) * 1000; mmsMine = c.getInt(1) != Telephony.Mms.MESSAGE_BOX_INBOX } } }
        return if (mmsDate > smsDate) mmsMine else smsMine
    }
    private fun mine(type: Int) = type != Telephony.Sms.MESSAGE_TYPE_INBOX && type != Telephony.Sms.MESSAGE_TYPE_ALL
    private fun contact(context: Context, address: String): Contact? {
        if (address.isBlank() || !granted(context, Manifest.permission.READ_CONTACTS)) return null
        return runCatching {
            context.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val photo = c.getString(1)?.let { Pictures.uri(context, Uri.parse(it), 96, SmsWire.PHOTO_LIMIT) }
                Contact(c.getString(0).orEmpty().ifBlank { address }, photo)
            }
        }.getOrNull()
    }

    // ---- a conversation's messages ----
    private fun messages(context: Context, thread: Long, before: Long, max: Int): List<SmsWire.Message> {
        val resolver = context.contentResolver; val out = ArrayList<SmsWire.Message>()
        val sel = "${Telephony.Sms.THREAD_ID} = ?" + if (before > 0) " AND ${Telephony.Sms.DATE} < ?" else ""
        val args = if (before > 0) arrayOf(thread.toString(), before.toString()) else arrayOf(thread.toString())
        resolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE), sel, args, "${Telephony.Sms.DATE} DESC")?.use { c ->
            while (out.size < max && c.moveToNext()) {
                val type = c.int(Telephony.Sms.TYPE)
                out += SmsWire.Message(c.long(Telephony.Sms._ID), c.str(Telephony.Sms.BODY), c.long(Telephony.Sms.DATE), flags(mine(type), type in unsent), null)
            }
        }
        // MMS (dates in seconds): their text, and a picture for the newest few.
        val mmsSel = "thread_id = ?" + if (before > 0) " AND date < ?" else ""
        val mmsArgs = if (before > 0) arrayOf(thread.toString(), (before / 1000).toString()) else arrayOf(thread.toString())
        var decoded = 0
        runCatching { resolver.query(MMS, arrayOf("_id", "date", "msg_box"), mmsSel, mmsArgs, "date DESC")?.use { c ->
            var n = 0
            while (n++ < max && c.moveToNext()) {
                val id = c.long("_id"); val box = c.int("msg_box")
                val (text, picture, hasPicture) = parts(context, id, decoded < 6)
                if (picture != null) decoded++
                val m = box != Telephony.Mms.MESSAGE_BOX_INBOX
                out += SmsWire.Message(id or MMS_ID, text, c.long("date") * 1000, flags(m, box == Telephony.Mms.MESSAGE_BOX_OUTBOX || box == Telephony.Mms.MESSAGE_BOX_FAILED) or (if (hasPicture) SmsWire.PICTURE else 0), picture)
            }
        } }
        return out.sortedByDescending { it.date }.take(max)
    }
    /** MMS ids are kept apart from SMS ids (they're numbered separately). */
    private const val MMS_ID = 1L shl 62
    private val unsent = setOf(Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_FAILED, Telephony.Sms.MESSAGE_TYPE_QUEUED)
    private fun flags(mine: Boolean, notSent: Boolean) = (if (mine) SmsWire.MINE else 0) or (if (notSent) SmsWire.NOT_SENT else 0)
    /** An MMS's text, its first picture (decoded only when [decode]), and whether it has one. */
    private fun parts(context: Context, mid: Long, decode: Boolean): Triple<String, ByteArray?, Boolean> {
        val text = StringBuilder(); var picture: ByteArray? = null; var has = false
        context.contentResolver.query(MMS_PART, arrayOf("_id", "ct", "text", "_data"), "mid = ?", arrayOf(mid.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val type = c.str("ct"); val part = Uri.withAppendedPath(MMS_PART, c.long("_id").toString())
                when {
                    type == "text/plain" -> { val t = if (c.str("_data").isNotEmpty()) runCatching { context.contentResolver.openInputStream(part)?.use { it.readBytes().take(16_000).toByteArray().toString(Charsets.UTF_8) } }.getOrNull() else c.str("text")
                        if (!t.isNullOrBlank()) { if (text.isNotEmpty()) text.append('\n'); text.append(t) } }
                    type.startsWith("image/") -> { has = true; if (picture == null && decode) picture = Pictures.uri(context, part, 640, SmsWire.PICTURE_LIMIT) }
                }
            }
        }
        return Triple(text.toString(), picture, has)
    }

    // ---- sending, and marking read ----
    private fun send(context: Context, address: String, text: String): Boolean {
        @Suppress("DEPRECATION") val sm = (if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()) ?: return false
        val parts = sm.divideMessage(text)
        if (parts.size <= 1) sm.sendTextMessage(address, null, text, null, null) else sm.sendMultipartTextMessage(address, null, parts, null, null)
        return true
    }
    /** Only the default SMS app may change messages: for any other, false (the PC is told it couldn't). */
    private fun markRead(context: Context, thread: Long): Boolean {
        if (Telephony.Sms.getDefaultSmsPackage(context) != context.packageName) return false
        val values = ContentValues().apply { put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1) }
        context.contentResolver.update(Telephony.Sms.CONTENT_URI, values, "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0", arrayOf(thread.toString()))
        return true
    }
}
