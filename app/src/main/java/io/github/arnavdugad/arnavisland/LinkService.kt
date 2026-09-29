package io.github.arnavdugad.arnavisland

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps this phone reachable by your PCs while "Stay reachable" is on: the link listens for files, music and
 * find-my-phone, answers discovery, and tells the island about the battery. Its notification is silent and minimal.
 */
class LinkService : Service() {
    private var multicast: WifiManager.MulticastLock? = null
    private var watching: Job? = null
    private val battery = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = Hub.sendBattery() }

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
        // The notification says which PCs are here.
        watching = Hub.scope.launch {
            Hub.peers.collectLatest { list ->
                val here = list.filter { it.paired && it.online }.map { it.name }
                val text = when { Hub.failure.value != null -> Hub.failure.value!!; here.isEmpty() -> "Looking for your PCs"; here.size == 1 -> "Connected to ${here[0]}"; else -> "Connected to ${here.size} devices" }
                runCatching { androidx.core.app.NotificationManagerCompat.from(this@LinkService).notify(Notify.LINK, Notify.link(this@LinkService, text)) }
            }
        }
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
        watching?.cancel(); runCatching { unregisterReceiver(battery) }; runCatching { multicast?.release() }
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
