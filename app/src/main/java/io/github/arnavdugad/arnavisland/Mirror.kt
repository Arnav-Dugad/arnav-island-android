package io.github.arnavdugad.arnavisland

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.arnavdugad.arnavisland.link.Link
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Your phone's notifications on the island: each new one (not silent, ongoing, media or a group's summary) goes to
 * your paired PCs that are here, with its app's icon. Nothing is kept, and the PC shows it only while its
 * "My phone's notifications" setting is on. Hidden notifications (lock-screen "secret") are never sent.
 */
class Mirror : NotificationListenerService() {
    private val recent = LinkedHashMap<String, Long>()
    private val icons = HashMap<String, ByteArray?>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Hub.init(this)
        if (!Hub.prefs.getBoolean("mirror", true)) return
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName || sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category in skipped || n.visibility == Notification.VISIBILITY_SECRET) return
        val ranking = Ranking(); if (currentRanking?.getRanking(sbn.key, ranking) == true && ranking.importance < android.app.NotificationManager.IMPORTANCE_DEFAULT) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        // The same words from the same notification within a few seconds are one notification.
        val key = sbn.key + "\u0000" + title + "\u0000" + text; val now = System.currentTimeMillis()
        synchronized(recent) { recent.entries.removeAll { now - it.value > 8_000 }; if (recent.containsKey(key)) return; recent[key] = now }
        val link = Hub.link ?: return
        val targets = Hub.peers.value.filter { it.paired && it.online && it.remote && !it.phone }; if (targets.isEmpty()) return
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val urgent = n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_ALARM
        Hub.scope.launch {
            val icon = synchronized(icons) { if (icons.containsKey(sbn.packageName)) icons[sbn.packageName] else null } ?: iconOf(sbn.packageName).also { synchronized(icons) { icons[sbn.packageName] = it } }
            val frame = Link.notificationFrame(app, title, text, Hub.phoneName(), urgent, icon)
            targets.forEach { link.notice(it.id, frame) }
        }
    }

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
        private val skipped = setOf(Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE, Notification.CATEGORY_SYSTEM, Notification.CATEGORY_STATUS)
        /** Whether Android lets the app read notifications (granted in Settings by the person). */
        fun allowed(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, Mirror::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }
    }
}
