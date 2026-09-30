package io.github.arnavdugad.arnavisland

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps this phone reachable by your PCs while "Stay reachable" is on: the link listens for files, music and
 * find-my-phone, answers discovery (and, anywhere, the relay), and tells the island about the battery and the phone's
 * details. It also keeps the PC's player on the lock screen and the widgets current. Its notification is silent.
 */
class LinkService : Service() {
    private var multicast: WifiManager.MulticastLock? = null
    private val jobs = ArrayList<Job>()
    private val battery = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = Hub.sendBattery() }
    private val hotspot = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = Hub.hotspotChanged(i) }
    private var network: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); Hub.init(this); Notify.channels(this)
        runCatching {
            ServiceCompat.startForeground(this, Notify.LINK, Notify.link(this, "Looking for your PCs"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        }
        // Discovery broadcasts reach apps only while a multicast lock is held.
        multicast = runCatching { getSystemService(WifiManager::class.java).createMulticastLock("arnav-island").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        Hub.start()
        ContextCompat.registerReceiver(this, battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        // 1.4: the hotspot turning on or off (Android's own broadcast); read once now too.
        runCatching { ContextCompat.registerReceiver(this, hotspot, IntentFilter(Hub.HOTSPOT_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED) }; Hub.hotspotChanged(null)
        // Another network (Wi-Fi to mobile data, another Wi-Fi): the relay and discovery start over at once.
        network = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null
            override fun onAvailable(n: Network) { if (current != null && current != n) Hub.networkChanged(); current = n }
        }.also { cb -> runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb) } }
        // The notification says which PCs are here, and how.
        jobs += Hub.scope.launch {
            combine(Hub.peers, Hub.internet) { list, anywhere -> list to anywhere }.collectLatest { (list, anywhere) ->
                val here = list.filter { it.paired && it.online && !it.phone }
                val text = when {
                    Hub.failure.value != null -> Hub.failure.value!!
                    here.isEmpty() -> if (anywhere) "Reachable anywhere  ·  looking for your PCs" else "Looking for your PCs"
                    here.size == 1 -> "Connected to ${here[0].name}${if (here[0].internet) (if (here[0].path == 2) " directly, over the internet" else " through the relay") else ""}"
                    else -> "Connected to ${here.size} devices"
                }
                runCatching { androidx.core.app.NotificationManagerCompat.from(this@LinkService).notify(Notify.LINK, Notify.link(this@LinkService, text)) }
            }
        }
        // The PC's player and the widgets follow what the PC says.
        jobs += Hub.scope.launch {
            combine(Hub.status, Hub.peers, Hub.selected) { s, _, _ -> s }.collectLatest { s ->
                val pc = Hub.pc()
                PcMedia.update(this@LinkService, pc, s)
                PcWidgets.refresh(this@LinkService)
            }
        }
        // While the app isn't on screen, the PC's status is asked here (for the player and the widgets): often while
        // music plays and the screen is on, now and then otherwise, and not at all when nothing shows it.
        jobs += Hub.scope.launch {
            val power = getSystemService(PowerManager::class.java)
            while (isActive) {
                var wait = 15_000L
                val pc = Hub.pc()
                val wanted = PcMedia.enabled() || PcWidgets.any(this@LinkService)
                if (Hub.visible) wait = 4_000L
                else if (wanted && pc != null && pc.online && pc.remote) {
                    val s = Hub.refreshStatus(); val lit = power?.isInteractive != false
                    wait = when { s?.available != true -> 15_000L; s.playing && lit -> if (pc.internet) 4_000L else 2_500L; s.playing -> 12_000L; else -> 8_000L }
                } else if (pc?.online != true && Hub.status.value != null && !Hub.visible) Hub.status.value = null
                delay(wait)
            }
        }
        // The phone's details, when they change (checked each minute).
        jobs += Hub.scope.launch { while (isActive) { delay(60_000); Hub.sendDetails() } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val transfer = intent?.getIntExtra("transfer", 0) ?: 0
        when (intent?.action) {
            ACCEPT -> Hub.offers.value.firstOrNull { it.transfer == transfer }?.let { Hub.answer(it, true) }
            DECLINE -> Hub.offers.value.firstOrNull { it.transfer == transfer }?.let { Hub.answer(it, false) }
            NOT_NOW -> Hub.music.value?.takeIf { it.transfer == transfer }?.let { Hub.answerMusic(it, false) }
            STOP_RING -> Ringer.stop(this)
            STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        return if (Hub.prefs.getBoolean("reachable", true)) START_STICKY else START_NOT_STICKY
    }
    override fun onDestroy() {
        jobs.forEach { it.cancel() }; jobs.clear()
        runCatching { unregisterReceiver(battery) }; runCatching { unregisterReceiver(hotspot) }; runCatching { multicast?.release() }
        network?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        PcMedia.clear(this)
        Hub.stop(); super.onDestroy()
    }
    companion object {
        const val ACCEPT = "accept"; const val DECLINE = "decline"; const val PLAY_HERE = "play"; const val NOT_NOW = "notnow"; const val STOP_RING = "stopring"; const val STOP = "stop"
        /** Starts the link (from the app on screen, or at boot); a no-op when Android refuses a start from the background. */
        fun start(context: Context) { runCatching { ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java)) } }
        fun stop(context: Context) { runCatching { context.stopService(Intent(context, LinkService::class.java)) } }
    }
}

/** After a restart or an update, the phone is reachable again (while "Stay reachable" is on). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Hub.init(context)
        if (Hub.prefs.getBoolean("reachable", true) && Hub.prefs.getBoolean("started", false)) LinkService.start(context)
    }
}
