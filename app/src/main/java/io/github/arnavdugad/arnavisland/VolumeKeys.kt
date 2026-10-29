package io.github.arnavdugad.arnavisland

/** Who the phone's volume keys go to while the app is on screen: a handler that takes them (true) or lets Android have them (false). The last one set wins. */
object VolumeKeys { @Volatile var handler: ((up: Boolean) -> Boolean)? = null }
