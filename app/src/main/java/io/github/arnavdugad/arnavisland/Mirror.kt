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

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Hub.init(this)
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
        Hub.scope.launch {
            val icon = synchronized(icons) { if (icons.containsKey(sbn.packageName)) icons[sbn.packageName] else null } ?: iconOf(sbn.packageName).also { synchronized(icons) { icons[sbn.packageName] = it } }
            // Islands before 0.20 read no key or actions after the icon; they get the notification as it was.
            val full = Link.notificationFrame(app, if (incoming && title.isEmpty()) "Incoming call" else title, text, Hub.phoneName(), urgent, icon, key, actions)
            val plain by lazy { Link.notificationFrame(app, title, text, Hub.phoneName(), urgent, icon) }
            targets.forEach { link.notice(it.id, if (it.revision >= 3) full else plain) }
        }
    }

    /** A notification the phone no longer shows (read, answered, a call picked up): its card leaves the island too. */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
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

    companion object {
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
