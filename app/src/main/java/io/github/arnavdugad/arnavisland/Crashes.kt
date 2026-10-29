package io.github.arnavdugad.arnavisland

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File

/**
 * 1.10: a crash leaves crash.txt in the app's own storage: when, the version, Android's, the thread and the exception
 * with its stack (no files, names, music or anything you sent). Settings › About shows the last one, to share it.
 * Android's own handler runs after it, as before.
 */
object Crashes {
    private fun file(context: Context) = File(context.filesDir, "crash.txt")
    fun install(context: Context) {
        val app = context.applicationContext; val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file(app).writeText("Arnav Island for Android ${Updates.current} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    "${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())} · thread ${thread.name}\n\n${error.stackTraceToString().take(24_000)}")
            }
            before?.uncaughtException(thread, error)
        }
    }
    /** The last crash's report, and when it was written; null when there's none. */
    fun last(context: Context): Pair<String, Long>? = file(context).takeIf { it.exists() }?.let { runCatching { it.readText() to it.lastModified() }.getOrNull() }
    fun share(context: Context) {
        val text = last(context)?.first ?: return
        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Arnav Island crash report").putExtra(Intent.EXTRA_TEXT, text), "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    fun forget(context: Context) { file(context).delete() }
}
