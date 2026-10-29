package io.github.arnavdugad.arnavisland

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 1.9: one action on your PC, with no screen: from App Actions (an assistant's "open lock in Arnav Island", or a phrase
 * matching the custom command intent), the app's shortcuts, and `arnavisland://do/<action>` links (routines, Tasker,
 * NFC tags; not web pages, so a site can't fire one). It shows a toast with what happened and goes, or asks first in a
 * small dialog where the action wants a yes (sleep, restart, shut down, a command the island asks about).
 *
 * Links: `arnavisland://do/lock`, `/play_pause`, `/next`, `/volume?value=30`, `/volume_up?by=10`, `/mute`, `/find`,
 * `/wake`, `/paste`, `/open?url=example.com`, `/screenshot`, `/playing`, `/status`, `/sleep`, `/command?text=timer%2010`.
 * `confirm=1` skips the question for sleep and commands; restart and shut down always ask here.
 */
class VoiceActivity : Activity() {
    private var action: String? = null
    private var args: Map<String, String> = emptyMap()
    private var waitingForFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Until a question shows, touches go through to what's underneath.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        Hub.init(this); LinkService.start(this)
        val (a, g) = parse(intent) ?: run { toast(this, "Arnav Island didn't understand that"); close(); return }
        action = a; args = g
        // Android lets only the app in front read the clipboard: read it once this window has the focus.
        if (a == Assistant.PASTE && g["text"].isNullOrBlank()) waitingForFocus = true else go(a, g)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !waitingForFocus) return
        waitingForFocus = false
        val text = runCatching { getSystemService(android.content.ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull().orEmpty()
        go(Assistant.PASTE, args + ("text" to text))
    }

    private fun go(a: String, g: Map<String, String>) {
        // Restart and shut down always ask here, whatever the link says.
        val asked = if (a == Assistant.RESTART || a == Assistant.SHUT_DOWN) g - "confirm" else g
        val app = applicationContext
        Hub.scope.launch {
            val r = Assistant.run(app, a, asked)
            withContext(Dispatchers.Main) {
                if (r.needsConfirm && !isFinishing && !isDestroyed) ask(r.speech, a, asked)
                else { toast(app, r.speech); close() }
            }
        }
    }

    private fun ask(question: String, a: String, g: Map<String, String>) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val cut = question.indexOf("? ")
        val title = if (cut > 0) question.substring(0, cut + 1) else question
        val yes = when (a) { Assistant.SLEEP -> "Sleep"; Assistant.RESTART -> "Restart"; Assistant.SHUT_DOWN -> "Shut down"; else -> "Go ahead" }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).apply { if (cut > 0) setMessage(question.substring(cut + 2)) }
            .setPositiveButton(yes) { _, _ -> window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE); val app = applicationContext
                Hub.scope.launch { val r = Assistant.run(app, a, g + ("confirm" to "1")); withContext(Dispatchers.Main) { toast(app, r.speech) } }; close() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> close() }
            .setOnCancelListener { close() }
            .show()
    }

    private fun close() { finish(); @Suppress("DEPRECATION") overridePendingTransition(0, 0) }

    companion object {
        /** An intent for one action ([args] become the link's query). */
        fun intent(context: Context, action: String, args: Map<String, String> = emptyMap()): Intent =
            Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("arnavisland").authority("do").appendPath(action).apply { args.forEach { (k, v) -> appendQueryParameter(k, v) } }.build())
                .setClass(context, VoiceActivity::class.java)

        /**
         * What was asked: a link's path and query; else App Actions' and shortcuts' extras ("action" or "feature": an
         * action, a shortcut's id or words; "text" alone: the command bar; "value", "url", "by", "confirm" as in links).
         */
        fun parse(intent: Intent?): Pair<String, Map<String, String>>? {
            intent ?: return null
            Assistant.fromUri(intent.data)?.let { return it }
            val extras = listOf("value", "url", "by", "text").mapNotNull { k -> intent.getStringExtra(k)?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
            val named = intent.getStringExtra("action") ?: intent.getStringExtra("feature")
            if (named.isNullOrBlank()) return extras["text"]?.let { Assistant.COMMAND to extras }
            Assistant.normalize(named)?.let { return it to extras }
            return Assistant.guess(named).let { (a, g) -> a to (g + extras) }
        }

        private fun toast(context: Context, text: String) = Toast.makeText(context, text, if (text.length > 40) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}
