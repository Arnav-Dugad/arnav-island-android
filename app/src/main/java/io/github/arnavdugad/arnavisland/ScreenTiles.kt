package io.github.arnavdugad.arnavisland

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 1.7: Quick Settings tiles. Your PC's screen: opens it here in one tap from anywhere. This phone on your PC: shows this
 * phone's screen there (Android asks first), and while it's shown, the tile is lit and a tap stops it.
 */
class PcScreenTile : TileService() {
    override fun onStartListening() {
        Hub.init(this)
        val pc = Hub.pc()
        qsTile?.apply {
            state = if (pc?.online == true && pc.revision >= 7) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            if (Build.VERSION.SDK_INT >= 29) subtitle = pc?.name ?: "No PC yet"
            updateTile()
        }
    }
    override fun onClick() {
        val pc = Hub.pc() ?: return
        val open = Intent(this, PcScreenActivity::class.java).putExtra("peer", pc.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        collapseInto(open)
    }
}

class PhoneScreenTile : TileService() {
    private var watch: Job? = null
    override fun onStartListening() {
        Hub.init(this)
        // Lit while this phone shows on a PC, with that PC's name.
        watch = CoroutineScope(Dispatchers.Main).launch {
            PhoneScreen.showing.collectLatest { showing ->
                val pc = Hub.pc()
                qsTile?.apply {
                    state = when { showing != null -> Tile.STATE_ACTIVE; pc?.online == true && pc.revision >= 7 -> Tile.STATE_INACTIVE; else -> Tile.STATE_UNAVAILABLE }
                    if (Build.VERSION.SDK_INT >= 29) subtitle = showing?.let { "On $it" } ?: pc?.name ?: "No PC yet"
                    updateTile()
                }
            }
        }
    }
    override fun onStopListening() { watch?.cancel(); watch = null }
    override fun onClick() {
        if (PhoneScreen.showing.value != null) { PhoneScreen.stop(this); return }
        val pc = Hub.pc() ?: return
        collapseInto(Intent(this, ScreenConsentActivity::class.java).putExtra("peer", pc.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Closes the shade and opens [intent] (Android 14 asks for a PendingIntent). */
private fun TileService.collapseInto(intent: Intent) {
    if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, intent.component.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    else @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated") startActivityAndCollapse(intent)
}
