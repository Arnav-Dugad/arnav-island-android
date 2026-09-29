package io.github.arnavdugad.arnavisland

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** A release of the app on GitHub: its version, notes, and the APK with its SHA-256 file. */
data class Release(val tag: String, val version: List<Int>, val name: String, val notes: String, val published: String, val page: String,
                   val apk: String?, val apkSize: Long, val sha: String?, val prerelease: Boolean) {
    val versionName get() = version.joinToString(".")
}

/**
 * Updates from the app's GitHub releases, which is where it is installed from. The app checks twice a day (and when
 * asked); with "Update automatically" on, a newer version is downloaded, checked against its published SHA-256 and
 * against this app's own signing certificate, and installed by Android's package installer (without asking again on
 * Android 12 and later, once the app has installed itself once). The release history is kept for the update tracker.
 */
object Updates {
    const val REPO = "Arnav-Dugad/arnav-island-android"
    const val PAGE = "https://github.com/$REPO/releases"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=30"

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class Current(val checkedAt: Long) : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val fraction: Float) : State
        data class Installing(val release: Release) : State
        data class Failed(val why: String) : State
    }
    val state = MutableStateFlow<State>(State.Idle)
    val releases = MutableStateFlow<List<Release>>(emptyList())
    private val busy = Mutex()
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
        releases.value = runCatching { parse(Hub.prefs.getString("releases", "[]")!!) }.getOrDefault(emptyList())
        newest()?.let { state.value = State.Available(it) }
    }
    fun auto() = Hub.prefs.getBoolean("autoUpdate", true)
    fun lastChecked() = Hub.prefs.getLong("updateChecked", 0)
    val current: String get() = BuildConfig.VERSION_NAME

    /** The newest release that is newer than this version and has an APK. */
    fun newest(list: List<Release> = releases.value): Release? = list.filter { it.apk != null && newer(it.version, version(current) ?: return null) }.maxWithOrNull { a, b -> compare(a.version, b.version) }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS, 2, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("updates", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Asks GitHub for the releases (at most every 20 minutes unless forced). */
    suspend fun check(force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        if (!force && System.currentTimeMillis() - lastChecked() in 0..1_200_000) return@withContext newest()
        busy.withLock {
            val before = state.value; if (before !is State.Downloading && before !is State.Installing) state.value = State.Checking
            val text = runCatching { get(API, 1 shl 20)?.toString(Charsets.UTF_8) }.getOrNull()
            if (text == null) { if (state.value == State.Checking) state.value = State.Failed("Couldn't reach GitHub"); return@withContext null }
            val list = runCatching { parse(text) }.getOrDefault(emptyList())
            releases.value = list; Hub.prefs.edit().putString("releases", text.take(900_000)).putLong("updateChecked", System.currentTimeMillis()).apply()
            val found = newest(list)
            if (state.value !is State.Downloading && state.value !is State.Installing) state.value = if (found != null) State.Available(found) else State.Current(System.currentTimeMillis())
            found
        }
    }

    /** Downloads, checks and installs [release]. */
    suspend fun install(release: Release, context: Context = app): Boolean = withContext(Dispatchers.IO) {
        busy.withLock {
            val apkUrl = release.apk ?: return@withContext fail("That release has no APK")
            state.value = State.Downloading(release, 0f)
            val expected = release.sha?.let { url -> get(url, 4096)?.toString(Charsets.UTF_8)?.let { shaIn(it) } } ?: return@withContext fail("Its SHA-256 file is missing")
            val dir = File(context.cacheDir, "update").apply { mkdirs() }; dir.listFiles()?.forEach { it.delete() }
            val apk = File(dir, "ArnavIsland-${release.versionName}.apk")
            val digest = MessageDigest.getInstance("SHA-256")
            val conn = open(apkUrl) ?: return@withContext fail("Couldn't download it")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            if (total > 300L * 1024 * 1024) { conn.disconnect(); return@withContext fail("That download is too large") }
            val ok = try {
                runCatching {
                    conn.inputStream.use { input -> apk.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024); var done = 0L; var shown = -1
                        while (true) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n); digest.update(buffer, 0, n); done += n
                            if (done > 300L * 1024 * 1024) throw java.io.IOException("too large")
                            val f = if (total > 0) (done * 100 / total).toInt() else 0; if (f != shown) { shown = f; state.value = State.Downloading(release, f / 100f) } }
                    } }
                    true
                }.getOrDefault(false)
            } finally { conn.disconnect() }
            if (!ok) return@withContext fail("The download didn't finish")
            if (digest.digest().hex() != expected) { apk.delete(); return@withContext fail("The download didn't match its SHA-256") }
            // Only a build signed like this one, of this app, and newer, is installed.
            val why = checkApk(context, apk); if (why != null) { apk.delete(); return@withContext fail(why) }
            state.value = State.Installing(release)
            runCatching { commit(context, apk) }.onFailure { return@withContext fail("Android didn't take the update") }
            true
        }
    }
    private fun fail(why: String): Boolean { state.value = State.Failed(why); return false }

    private fun checkApk(context: Context, apk: File): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val theirs = pm.getPackageArchiveInfo(apk.path, flags) ?: return "That file isn't an app"
        if (theirs.packageName != context.packageName) return "That APK is another app"
        val mine = pm.getPackageInfo(context.packageName, flags)
        if (longVersion(theirs) <= longVersion(mine)) return "That version isn't newer"
        val a = certs(theirs); val b = certs(mine)
        if (a.isEmpty() || a != b) return "It isn’t signed by Arnav Island’s key"
        return null
    }
    private fun longVersion(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    private fun certs(p: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory } else @Suppress("DEPRECATION") p.signatures
        return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
    }
    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val status = PendingIntent.getBroadcast(context, id, Intent(context, InstallReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(status.intentSender)
        }
    }

    // ---- GitHub ----
    private fun open(url: String): HttpsURLConnection? {
        var target = URL(url)
        repeat(5) {
            if (target.protocol != "https") return null
            val c = target.openConnection() as HttpsURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 20_000; c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", if (url.startsWith("https://api.github.com")) "application/vnd.github+json" else "application/octet-stream")
            c.setRequestProperty("User-Agent", "ArnavIsland-Android/${BuildConfig.VERSION_NAME}")
            when (c.responseCode) {
                200 -> return c
                301, 302, 303, 307, 308 -> { val next = c.getHeaderField("Location"); c.disconnect(); target = URL(target, next ?: return null) }
                else -> { c.disconnect(); return null }
            }
        }
        return null
    }
    private fun get(url: String, limit: Int): ByteArray? = open(url)?.let { c -> try { c.inputStream.use { readBounded(it, limit) } } finally { c.disconnect() } }
    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n); if (out.size() > limit) throw java.io.IOException("too large") }
        return out.toByteArray()
    }

    // ---- parsing (tested) ----
    internal fun parse(json: String): List<Release> {
        val a = JSONArray(json); val out = mutableListOf<Release>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i); if (o.optBoolean("draft")) continue
            val tag = o.optString("tag_name"); val v = version(tag.removePrefix("v")) ?: continue
            var apk: String? = null; var size = 0L; var sha: String? = null
            val assets = o.optJSONArray("assets") ?: JSONArray()
            for (k in 0 until assets.length()) { val s = assets.getJSONObject(k); val name = s.optString("name"); val url = s.optString("browser_download_url")
                if (name.endsWith(".apk") && apk == null) { apk = url; size = s.optLong("size") } else if (name.endsWith(".apk.sha256") && sha == null) sha = url }
            out += Release(tag, v, o.optString("name").ifBlank { tag }, o.optString("body"), o.optString("published_at"), o.optString("html_url"), apk, size, sha, o.optBoolean("prerelease"))
        }
        return out.sortedWith { x, y -> compare(y.version, x.version) }
    }
    internal fun version(text: String): List<Int>? = Regex("(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})(?:[-+].*)?").matchEntire(text.trim())?.groupValues?.drop(1)?.map { it.toInt() }
    internal fun compare(a: List<Int>, b: List<Int>): Int { for (i in 0..2) if (a[i] != b[i]) return a[i].compareTo(b[i]); return 0 }
    internal fun newer(candidate: List<Int>, current: List<Int>) = compare(candidate, current) > 0
    /** The SHA-256 in a ".sha256" file ("<64 hex>  name"), lower-case; null when there is none. */
    internal fun shaIn(text: String): String? = Regex("\\b([0-9a-fA-F]{64})\\b").find(text)?.groupValues?.get(1)?.lowercase()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}

/** Android's answer to an install: asks the person when it must, and says how it went. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Hub.init(context)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                val started = confirm != null && runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                if (!started || !Hub.visible) Notify.update(context, "An update is ready", "Open Arnav Island to install it")
                Updates.state.value = Updates.newest()?.let { Updates.State.Available(it) } ?: Updates.State.Idle
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Updates.state.value = Updates.State.Failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(120) ?: "Android didn't install it")
        }
    }
}

/** Twice a day: new releases, installed at once while "Update automatically" is on. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Hub.init(applicationContext); Updates.init(applicationContext)
        val found = Updates.check(force = true) ?: return Result.success()
        if (Updates.auto()) Updates.install(found, applicationContext) else Notify.update(applicationContext, "Arnav Island ${found.versionName} is out", "Open the app to update")
        return Result.success()
    }
}
