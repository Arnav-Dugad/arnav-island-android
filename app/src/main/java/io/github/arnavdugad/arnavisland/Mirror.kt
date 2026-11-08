package io.github.arnavdugad.arnavisland

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.arnavdugad.arnavisland.link.Link
import io.github.arnavdugad.arnavisland.link.NoticeWire
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Your phone's notifications on the island: each new one (not silent, ongoing, media or a group's summary) goes to
 * your paired PCs that are here, with its app's icon and, for islands that can (0.20), its actions: reply from the PC,
 * mark as read, decline a call. An incoming call shows too (who is calling), and its card goes when the phone stops
 * ringing. Nothing is kept, and the PC shows it only while its "My phone's notifications" setting is on. Hidden
 * notifications (lock-screen "secret") are never sent.
 */
class Mirror : NotificationListenerService() {
    /** What was last sent for each notification: an update saying the same (or updating quietly) isn't sent again. */
    private val sent = LinkedHashMap<String, String>()
    private val icons = HashMap<String, ByteArray?>()

    /** 1.9: the live activities already showing are tracked from the start, and PCs that arrive get them. */
    override fun onListenerConnected() {
        super.onListenerConnected()
        running = this
        Hub.init(this); LiveCapture.start(this)
        runCatching { activeNotifications }.getOrNull()?.forEach { runCatching { LiveCapture.posted(this, it, currentRanking) } }
    }

    // Any app's notification comes through here (on the main thread): one that can't be read is left out, never the app's end.
    override fun onNotificationPosted(sbn: StatusBarNotification) { runCatching { posted(sbn) }.onFailure { android.util.Log.w("ArnavMirror", "notification skipped", it) } }
    private fun posted(sbn: StatusBarNotification) {
        Hub.init(this)
        // 1.9: a live activity (a delivery, a ride, navigation, a timer, a download) also goes as notice 7; mirroring below is unchanged.
        LiveCapture.start(this); LiveCapture.posted(this, sbn, currentRanking)
        if (!Hub.prefs.getBoolean("mirror", true)) return
        val n = sbn.notification ?: return
        val call = n.category == Notification.CATEGORY_CALL
        // An incoming call rings with a full-screen screen (or says so, Android 12+); a call under way is left out.
        val incoming = call && (n.fullScreenIntent != null || n.extras.getInt("android.callType", 0) == 1)
        if (call && (!incoming || !Hub.prefs.getBoolean("calls", true))) return
        if (sbn.packageName == packageName || (sbn.isOngoing && !incoming) || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category in skipped || n.visibility == Notification.VISIBILITY_SECRET) return
        val ranking = Ranking(); if (!incoming && currentRanking?.getRanking(sbn.key, ranking) == true && ranking.importance < android.app.NotificationManager.IMPORTANCE_DEFAULT) return
        val extras = n.extras
        val title = plain(extras.getCharSequence(Notification.EXTRA_TITLE))
        val text = plain(extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            .ifEmpty { if (incoming) "Incoming call" else "" }
        if (title.isEmpty() && text.isEmpty()) return
        // A conversation whose newest message is your own (a reply, from the island or here) isn't news.
        if (Build.VERSION.SDK_INT >= 28 && ownLast(extras)) return
        // An update saying the same words, or one the app makes quietly, isn't sent again.
        val words = title + "\u0000" + text
        synchronized(sent) {
            val before = sent[sbn.key]
            if (before != null && (before == words || n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)) return
            sent.remove(sbn.key); sent[sbn.key] = words
            while (sent.size > 300) sent.remove(sent.keys.first())
        }
        val link = Hub.link ?: return
        val targets = Hub.peers.value.filter { it.paired && it.online && it.remote && !it.phone }; if (targets.isEmpty()) return
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val urgent = call || n.category == Notification.CATEGORY_ALARM
        val (key, actions) = NoticeActions.offer(sbn.key, n, incoming)
        // 1.12: the replies Android suggests for it (smart replies), read now while its ranking is at hand.
        val smart = if (!incoming && Build.VERSION.SDK_INT >= 29) runCatching { ranking.smartReplies.map { it.toString() } }.getOrDefault(emptyList()) else emptyList()
        Hub.scope.launch {
            val icon = synchronized(icons) { if (icons.containsKey(sbn.packageName)) icons[sbn.packageName] else null } ?: iconOf(sbn.packageName).also { synchronized(icons) { icons[sbn.packageName] = it } }
            val shownTitle = if (incoming && title.isEmpty()) "Incoming call" else title
            // Islands before 0.20 read no key or actions after the icon; they get the notification as it was. 0.29 (revision
            // 12) gets it in full: its conversation, faces, a picture it carries and suggested replies.
            val rich by lazy { Link.notificationFrame(app, shownTitle, text, Hub.phoneName(), urgent, icon, key, actions, runCatching { fullOf(sbn, n, title, smart) }.getOrNull()) }
            val keyed by lazy { Link.notificationFrame(app, shownTitle, text, Hub.phoneName(), urgent, icon, key, actions) }
            val plain by lazy { Link.notificationFrame(app, title, text, Hub.phoneName(), urgent, icon) }
            targets.forEach { link.notice(it.id, when { it.revision >= NoticeWire.REVISION -> rich; it.revision >= 3 -> keyed; else -> plain }) }
        }
    }

    /** 1.12: the notification in full, for islands 0.29 and later. Pictures only while "Photos in notifications" is on. */
    private fun fullOf(sbn: StatusBarNotification, n: Notification, title: String, smart: List<String>): NoticeWire.Full {
        val x = n.extras; val pictures = Hub.prefs.getBoolean("noticePictures", true)
        @Suppress("DEPRECATION") val bundles = x.getParcelableArray(Notification.EXTRA_MESSAGES)
        val raw = if (bundles != null) runCatching { Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles) }.getOrDefault(emptyList()) else emptyList()
        val messages = raw.takeLast(8).map { m ->
            val person = if (Build.VERSION.SDK_INT >= 28) m.senderPerson else null
            @Suppress("DEPRECATION") val sender = plain(person?.name ?: m.sender)
            @Suppress("DEPRECATION") val mine = person == null && m.sender == null
            NoticeWire.Message(sender, plain(m.text), m.timestamp, mine)
        }
        val conversationTitle = plain(x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))
        val group = x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) || messages.filter { !it.me }.map { it.sender }.distinct().size > 1
        // A conversation is known by its shortcut (the chat), else by its app and who it's with.
        val shortcut = if (Build.VERSION.SDK_INT >= 26) n.shortcutId?.takeIf { it.isNotBlank() } else null
        val conversation = when { shortcut != null -> "${sbn.packageName}|$shortcut"; messages.isNotEmpty() -> "${sbn.packageName}|${conversationTitle.ifEmpty { title }}"; else -> "" }
        var flags = 0
        if (group) flags = flags or NoticeWire.GROUP
        if (messages.isNotEmpty()) flags = flags or NoticeWire.CONVERSATION
        if (sbn.isClearable) flags = flags or NoticeWire.CLEARABLE
        val avatar = if (pictures) runCatching { avatarOf(n, raw) }.getOrNull() else null
        val picture = if (pictures) runCatching { pictureOf(n, raw) }.getOrNull() else null
        // The reply action's own choices ("Yes", "No"), after Android's suggestions.
        val choices = n.actions.orEmpty().flatMap { a -> a.remoteInputs.orEmpty().flatMap { r -> r.choices.orEmpty().map { it.toString() } } }
        return NoticeWire.Full(sbn.packageName, conversation, conversationTitle, flags, sbn.postTime, messages, avatar, picture, smart + choices)
    }
    /** The sender's face: the notification's large icon (a contact's photo), else the last sender's own picture. */
    private fun avatarOf(n: Notification, raw: List<Notification.MessagingStyle.Message>): ByteArray? {
        val icon = n.getLargeIcon() ?: if (Build.VERSION.SDK_INT >= 28) raw.lastOrNull { it.senderPerson?.icon != null }?.senderPerson?.icon else null
        val d = icon?.loadDrawable(this) ?: return null
        return Pictures.square(d, 112, NoticeWire.AVATAR_LIMIT)
    }
    /** A picture it carries: a big picture, else the last message's image (when this app may read it). */
    private fun pictureOf(n: Notification, raw: List<Notification.MessagingStyle.Message>): ByteArray? {
        val x = n.extras
        @Suppress("DEPRECATION") val big = if (Build.VERSION.SDK_INT >= 33) x.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java) else x.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap
        if (big != null) return Pictures.jpeg(big, 480, NoticeWire.PICTURE_LIMIT)
        if (Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION") val icon = if (Build.VERSION.SDK_INT >= 33) x.getParcelable(Notification.EXTRA_PICTURE_ICON, android.graphics.drawable.Icon::class.java) else x.getParcelable(Notification.EXTRA_PICTURE_ICON) as? android.graphics.drawable.Icon
            icon?.loadDrawable(this)?.let { return Pictures.drawn(it, 480, NoticeWire.PICTURE_LIMIT) }
        }
        val m = raw.lastOrNull { it.dataMimeType?.startsWith("image/") == true && it.dataUri != null } ?: return null
        return Pictures.uri(this, m.dataUri!!, 480, NoticeWire.PICTURE_LIMIT)
    }

    /** A notification the phone no longer shows (read, answered, a call picked up): its card leaves the island too. */
    override fun onNotificationRemoved(sbn: StatusBarNotification) { runCatching { removed(sbn) } }
    private fun removed(sbn: StatusBarNotification) {
        LiveCapture.removed(sbn.key)
        synchronized(sent) { sent.remove(sbn.key) }
        val key = NoticeActions.removed(sbn.key) ?: return
        val link = Hub.link ?: return
        val targets = Hub.peers.value.filter { it.paired && it.online && it.revision >= 3 && !it.phone }; if (targets.isEmpty()) return
        val frame = Link.goneFrame(key)
        Hub.scope.launch { targets.forEach { link.notice(it.id, frame) } }
    }

    /** Whether a messaging notification's newest message was sent by this phone's owner (it has no sender). */
    private fun ownLast(extras: android.os.Bundle): Boolean = runCatching {
        @Suppress("DEPRECATION") val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return false
        val last = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles).lastOrNull() ?: return false
        last.senderPerson == null && last.sender == null
    }.getOrDefault(false)

    /** The app's own icon, as a PNG of at most 24 KB (96 px, or smaller when it would be more). */
    private fun iconOf(pkg: String): ByteArray? = runCatching {
        val drawable = packageManager.getApplicationIcon(pkg)
        for (side in intArrayOf(96, 72, 48)) {
            val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, side, side); drawable.draw(canvas)
            val out = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle()
            if (out.size() <= 24 * 1024) return out.toByteArray()
        }
        null
    }.getOrNull()

    override fun onListenerDisconnected() { if (running === this) running = null; super.onListenerDisconnected() }
    override fun onDestroy() { if (running === this) running = null; super.onDestroy() }

    companion object {
        /** 1.12: the listener while Android has it connected (a PC's "dismiss" goes through it). */
        @Volatile var running: Mirror? = null
        /** Dismisses one of this phone's notifications, as swiping it away would; false when it can't be. */
        fun dismiss(sbnKey: String): Boolean = runCatching { val m = running ?: return false; m.cancelNotification(sbnKey); true }.getOrDefault(false)
        /** Invisible direction marks (a caller's number is wrapped in them) are left out. */
        private val marks = Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069]")
        fun plain(s: CharSequence?): String = s?.toString()?.replace(marks, "")?.trim().orEmpty()
        private val skipped = setOf(Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE, Notification.CATEGORY_SYSTEM, Notification.CATEGORY_STATUS)
        /** Whether Android lets the app read notifications (granted in Settings by the person). */
        fun allowed(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, Mirror::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }
    }
}
