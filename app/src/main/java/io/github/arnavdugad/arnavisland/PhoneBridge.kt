package io.github.arnavdugad.arnavisland

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The actions of this phone's notifications, as the island offers them: each notification sent to a PC gets a short
 * key ("n42"); a PC that asks for one of its actions (Reply with the words typed on the PC, Mark as read, Decline a call)
 * runs it here. Only the key and the action's name leave the phone; the actions themselves stay here.
 */
object NoticeActions {
    private class Held(val sbnKey: String, val actions: List<Notification.Action>)
    private val held = LinkedHashMap<String, Held>()
    private val bySbn = HashMap<String, String>()
    private var next = 1

    /**
     * The actions the island shows for a notification (at most two: a reply first, as the notification orders them), and
     * the key they are asked for by. Actions that would open a screen on the phone are left out (they can't from a PC).
     */
    fun offer(sbnKey: String, n: Notification, call: Boolean): Pair<String, List<Pair<String, Boolean>>> {
        val usable = n.actions.orEmpty().filter { a ->
            !a.title.isNullOrBlank() && a.actionIntent != null && !(Build.VERSION.SDK_INT >= 31 && a.actionIntent.isActivity && !call)
        }
        val chosen = if (call) usable.take(2) else {
            val reply = usable.firstOrNull { replies(it) }
            (listOfNotNull(reply) + usable.filter { it !== reply }).take(2)
        }
        val key = synchronized(held) {
            val k = bySbn[sbnKey] ?: "n${next++}"
            held.remove(k); held[k] = Held(sbnKey, chosen); bySbn[sbnKey] = k
            while (held.size > 200) { val (old, h) = held.entries.first(); held.remove(old); bySbn.remove(h.sbnKey) }
            k
        }
        return key to chosen.map { it.title.toString().trim() to replies(it) }
    }
    /** The key of a notification the phone no longer shows (so the island takes its card away), if it was sent. */
    fun removed(sbnKey: String): String? = synchronized(held) { val k = bySbn.remove(sbnKey) ?: return null; held.remove(k); k }

    /** Runs an action asked for by a PC: 0 done, 1 the notification is gone, 2 it couldn't be done. */
    fun perform(context: Context, key: String, index: Int, reply: String): Int {
        val h = synchronized(held) { held[key] } ?: return 1
        val a = h.actions.getOrNull(index) ?: return 2
        return runCatching {
            val inputs = a.remoteInputs?.filter { it.allowFreeFormInput }.orEmpty()
            if (replies(a) && inputs.isNotEmpty()) {
                if (reply.isBlank()) return 2
                val fill = Intent(); val results = Bundle(); inputs.forEach { results.putCharSequence(it.resultKey, reply.take(2000)) }
                RemoteInput.addResultsToIntent(a.remoteInputs, fill, results)
                if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
                a.actionIntent.send(context, 0, fill)
            } else a.actionIntent.send()
            0
        }.getOrElse { if (it is PendingIntent.CanceledException) 1 else 2 }
    }
    private fun replies(a: Notification.Action) = a.remoteInputs?.any { it.allowFreeFormInput } == true
}

/**
 * The universal clipboard on this phone. What the PC copies arrives here (while the island's universal clipboard is on,
 * and this phone's switch too); what this phone copies goes to the PC when the app comes to the front (Android lets
 * only the app on screen read the clipboard). Passwords and other sensitive copies are marked so and never sent back.
 */
object Clip {
    private val main = Handler(Looper.getMainLooper())
    /** The last text that came from a PC (not sent back to it), and the clipboard's time when this phone last looked. */
    @Volatile private var fromPc: String? = null
    @Volatile private var seenAt = 0L

    fun enabled() = Hub.prefs.getBoolean("clipboard", true)

    /** A PC's copy, put on this phone's clipboard; false when it couldn't be (or the switch is off). */
    fun receive(context: Context, text: String, sensitive: Boolean): Boolean {
        if (!enabled() || text.isEmpty()) return false
        val done = CountDownLatch(1); var ok = false
        main.post {
            ok = runCatching {
                val cm = context.getSystemService(ClipboardManager::class.java)
                val clip = ClipData.newPlainText("From your PC", text)
                if (sensitive && Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                cm.setPrimaryClip(clip); fromPc = text
                seenAt = cm.primaryClipDescription?.timestamp ?: 0L
                true
            }.getOrDefault(false)
            done.countDown()
        }
        done.await(3, TimeUnit.SECONDS)
        return ok
    }

    /**
     * This phone's newest copy, when there is one the PC hasn't had: called while the app has the focus. Null when
     * nothing new was copied, it came from the PC, it is marked sensitive, or it isn't text.
     */
    fun newCopy(context: Context): String? = runCatching {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val description = cm.primaryClipDescription ?: return null
        val at = description.timestamp
        if (at != 0L && at == seenAt) return null
        seenAt = at
        if (Build.VERSION.SDK_INT >= 33 && description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return null
        if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) && !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) return null
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isBlank() || text == fromPc || text.length > 200_000) null else text
    }.getOrNull()
}
