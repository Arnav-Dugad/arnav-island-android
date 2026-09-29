package io.github.arnavdugad.arnavisland

import android.app.Application

class IslandApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this); Notify.channels(this); Updates.init(this); Updates.schedule(this)
    }
}
