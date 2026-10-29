package io.github.arnavdugad.arnavisland

import io.github.arnavdugad.arnavisland.link.Adapter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 1.8: waking a PC from this phone, as Wake-on-LAN does: a "magic packet" (six bytes of FF, then the adapter's hardware
 * address sixteen times) broadcast on this network, to each adapter the PC told this phone about while it was here. The
 * PC wakes only if its adapter and firmware allow it, and only on the same network (routers don't pass broadcasts on).
 */
object Wake {
    fun magic(mac: ByteArray): ByteArray = ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { mac[it % 6] }

    /** The broadcast address of an IPv4 address's subnet ("192.168.1.255" for 192.168.1.20/24), or null. */
    fun subnetBroadcast(ipv4: String, prefix: Int): String? {
        val parts = ipv4.split('.').mapNotNull { it.toIntOrNull() }; if (parts.size != 4 || prefix !in 1..30) return null
        val ip = parts.fold(0L) { a, b -> (a shl 8) or (b.toLong() and 255) }; val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val b = (ip and mask) or (mask.inv() and 0xFFFFFFFFL)
        return listOf(24, 16, 8, 0).joinToString(".") { ((b shr it) and 255).toString() }
    }

    /** Sends the packets (on a network thread): to every broadcast address that fits, on ports 9 and 7. True if any went. */
    fun send(adapters: List<Adapter>): Boolean {
        var any = false
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (a in adapters) {
                    if (a.mac.size != 6) continue
                    val packet = magic(a.mac)
                    val targets = linkedSetOf("255.255.255.255").apply { subnetBroadcast(a.ipv4, a.prefix)?.let(::add) }
                    for (t in targets) for (port in intArrayOf(9, 7)) runCatching { socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(t), port)); any = true }
                }
            }
        }
        return any
    }
}
