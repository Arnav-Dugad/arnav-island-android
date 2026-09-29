package io.github.arnavdugad.arnavisland.link

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Paired devices on different networks meet through a free public MQTT broker (HiveMQ, then EMQX, then Eclipse
 * Mosquitto), over TLS, exactly as the Windows island's ShareRelay does. Topics are named from each pair's own secret
 * (SHA-256 of the pair's static ECDH, which only the two devices can compute), and every message is sealed with
 * AES-256-GCM under a key from it. A connection is a tunnel: the protocol runs over one end of a local socket pair,
 * unchanged, and its bytes travel in numbered messages with a window of acknowledgements.
 */
class Relay(
    private val id: ByteArray,
    private val name: () -> String,
    private val phone: Boolean,
    private val revision: Int,
    private val incoming: (Socket, String) -> Unit,
    private val changed: () -> Unit,
    private val brokers: List<Pair<String, Int>> = DEFAULT_BROKERS,
) {
    data class Presence(val here: Boolean = false, val phone: Boolean = false, val revision: Int = 0, val name: String = "")
    class RelayPair(val peer: ByteArray, val agreed: ByteArray)

    private class Seal(key: ByteArray) {
        private val spec = SecretKeySpec(key, "AES")
        fun seal(plain: ByteArray, topic: String): ByteArray {
            val nonce = Crypto.random(12)
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(128, nonce)); c.updateAAD(topic.toByteArray(Charsets.UTF_8))
            return nonce + c.doFinal(plain)
        }
        fun open(sealed: ByteArray, topic: String): ByteArray? = runCatching {
            if (sealed.size <= 12 + 16) return null
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(128, sealed, 0, 12)); c.updateAAD(topic.toByteArray(Charsets.UTF_8))
            c.doFinal(sealed, 12, sealed.size - 12)
        }.getOrNull()
    }
    private class Route(val peer: String, val seal: Seal, val outbox: String, val code: Boolean = false, val host: Boolean = false) {
        @Volatile var heard = 0L; @Volatile var helloed = 0L; @Volatile var presence = Presence()
    }
    private inner class Tunnel(val conn: Long, val outer: Socket, val outbox: String, val seal: Seal) {
        val lock = Object(); var sent = 0L; var acked = 0L; var expected = 0L; var acknowledged = 0L
        val inbound = ArrayDeque<ByteArray>(); var inEnd = false; var dead = false; var closeSent = false; var finished = 0
    }

    private val lock = Any()
    private val routes = HashMap<String, Route>()
    private val tunnels = ConcurrentHashMap<Long, Tunnel>()
    private var code: String? = null; private var codeUntil = 0L
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile var connected = false; private set
    @Volatile var broker: String? = null; private set
    @Volatile private var stopping = false
    @Volatile private var lastIn = 0L; @Volatile private var lastPing = 0L
    private var nextPacket = 1
    private val subacks = HashMap<Int, Boolean>()
    private val sleeper = Object()

    fun start() {
        thread(name = "relay-run", isDaemon = true) { run() }
        thread(name = "relay-tick", isDaemon = true) { tick() }
    }
    fun stop() {
        if (connected) { val pairs = synchronized(lock) { routes.filterValues { !it.code }.keys.toList() }; pairs.forEach { hello(it, reply = false, leaving = true) }; write(byteArrayOf(0xE0.toByte(), 0)) }
        stopping = true; synchronized(sleeper) { sleeper.notifyAll() }
        runCatching { socket?.close() }; dropAll()
    }

    /** The phone changed networks: the broker is dialled again at once (a dead socket would take its timeout to notice). */
    fun kick() {
        if (stopping) return
        runCatching { socket?.close() }
        synchronized(sleeper) { sleeper.notifyAll() }
    }

    // ---- the broker ----
    private fun write(packet: ByteArray): Boolean {
        val o = out ?: return false
        return runCatching { synchronized(o) { o.write(packet); o.flush() }; true }.getOrDefault(false)
    }
    private fun run() {
        var which = 0; var fails = 0
        while (!stopping) {
            val (host, port) = brokers[which % brokers.size]
            var worked = false
            try {
                val raw = Socket(); raw.connect(InetSocketAddress(host, port), 10_000)
                val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
                s.sslParameters = s.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                s.soTimeout = 15_000; s.startHandshake(); s.tcpNoDelay = true
                socket = s; out = s.outputStream; lastIn = System.currentTimeMillis()
                if (write(connectPacket("ai" + Crypto.random(10).hex()))) { broker = host; receive(DataInputStream(s.inputStream.buffered(64 * 1024))) }
                worked = connected
            } catch (_: Exception) {}
            val was = connected; connected = false; broker = null
            runCatching { socket?.close() }; socket = null; out = null; dropAll()
            if (was) changed()
            if (stopping) break
            if (worked) fails = 0 else { fails++; which++ }
            val wait = if (worked) 1_000L else minOf(30_000L, 1_000L shl minOf(fails, 5))
            synchronized(sleeper) { if (!stopping) sleeper.wait(wait) }
        }
    }
    private fun receive(input: DataInputStream) {
        while (!stopping) {
            val head = input.readUnsignedByte()
            var n = 0; var mult = 1; var count = 0
            while (true) { val d = input.readUnsignedByte(); n += (d and 127) * mult; if (d and 128 == 0) break; mult *= 128; if (++count > 3) return }
            val body = ByteArray(n); input.readFully(body); lastIn = System.currentTimeMillis()
            when (head shr 4) {
                2 -> if (body.size >= 2 && body[1].toInt() == 0 && !connected) { connected = true; socket?.soTimeout = 100_000; onConnected() }
                9 -> if (body.size >= 2) synchronized(lock) { val pid = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF); if (subacks.containsKey(pid)) { subacks[pid] = true; (lock as Object).notifyAll() } }
                3 -> {
                    if (body.size < 2) continue
                    val tn = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF); if (body.size < 2 + tn) continue
                    var at = 2 + tn; if ((head shr 1) and 3 != 0) at += 2; if (at > body.size) continue
                    onPublish(String(body, 2, tn, Charsets.UTF_8), body.copyOfRange(at, body.size))
                }
            }
        }
    }
    /** Hellos once a minute, gone after 150 s of silence, the broker pinged every 30 s, codes expired. */
    private fun tick() {
        while (!stopping) {
            synchronized(sleeper) { if (!stopping) sleeper.wait(5_000) }
            if (stopping || !connected) continue
            val now = System.currentTimeMillis()
            if (now - lastPing >= 30_000) { lastPing = now; write(byteArrayOf(0xC0.toByte(), 0)) }
            var gone = false; val hello = mutableListOf<String>(); var expired: List<String> = emptyList()
            synchronized(lock) {
                for ((inbox, r) in routes) { if (r.code) continue
                    if (now - r.helloed >= 60_000) hello += inbox
                    if (r.presence.here && now - r.heard > 150_000) { r.presence = r.presence.copy(here = false); gone = true } }
                if (code != null && now > codeUntil) expired = stopHostingLocked()
            }
            if (expired.isNotEmpty()) unsubscribe(expired)
            hello.forEach { hello(it, reply = false, leaving = false) }
            if (gone) changed()
        }
    }
    private fun onConnected() {
        val inboxes = synchronized(lock) { routes.keys.toList() }; if (inboxes.isNotEmpty()) subscribe(inboxes, wait = false)
        synchronized(lock) { routes.filterValues { !it.code }.keys.toList() }.forEach { hello(it, reply = true, leaving = false) }
        changed()
    }
    private fun subscribe(topics: List<String>, wait: Boolean): Boolean {
        val pid = synchronized(lock) { val p = nextPacket++; if (nextPacket > 65535) nextPacket = 1; if (wait) subacks[p] = false; p }
        val b = Bytes().u8(pid shr 8).u8(pid and 0xFF); topics.forEach { b.raw(mqttString(it)).u8(0) }
        if (!write(packet(0x82, b.build()))) { synchronized(lock) { subacks.remove(pid) }; return false }
        if (!wait) return true
        synchronized(lock) { val end = System.currentTimeMillis() + 8_000
            while (subacks[pid] != true) { val left = end - System.currentTimeMillis(); if (left <= 0) break; (lock as Object).wait(left) }
            return subacks.remove(pid) == true }
    }
    private fun unsubscribe(topics: List<String>) {
        val pid = synchronized(lock) { val p = nextPacket++; if (nextPacket > 65535) nextPacket = 1; p }
        val b = Bytes().u8(pid shr 8).u8(pid and 0xFF); topics.forEach { b.raw(mqttString(it)) }
        if (connected) write(packet(0xA2, b.build()))
    }

    // ---- messages ----
    private fun envelope(kind: Int) = Bytes().u8(VERSION).u8(kind).raw(id)
    private fun publish(topic: String, seal: Seal, plain: ByteArray): Boolean {
        val b = Bytes().raw(mqttString(topic)).raw(seal.seal(plain, topic)).build()
        return write(packet(0x30, b))
    }
    private fun hello(inbox: String, reply: Boolean, leaving: Boolean) {
        val r = synchronized(lock) { routes[inbox]?.also { it.helloed = System.currentTimeMillis() } } ?: return
        val n = name().toByteArray(Charsets.UTF_8).let { if (it.size > 120) it.copyOf(120) else it }
        val flags = (if (reply) HELLO_REPLY else 0) or (if (phone) HELLO_PHONE else 0) or (if (leaving) HELLO_LEAVING else 0)
        publish(r.outbox, r.seal, envelope(KIND_HELLO).u8(flags).u8(revision).u8(n.size).raw(n).build())
    }
    private fun onPublish(topic: String, payload: ByteArray) {
        val r = synchronized(lock) { routes[topic] } ?: return
        val plain = r.seal.open(payload, topic) ?: return
        if (plain.size < 18 || plain[0].toInt() != VERSION) return
        val sender = plain.copyOfRange(2, 18); if (sender.contentEquals(id)) return; if (!r.code && sender.hex() != r.peer) return
        val p = plain.copyOfRange(18, plain.size); val rd = Reader(p)
        when (plain[1].toInt()) {
            KIND_HELLO -> {
                if (r.code || p.size < 3) return
                val flags = p[0].toInt() and 0xFF; val size = minOf(p[2].toInt() and 0xFF, p.size - 3)
                val presence = Presence(flags and HELLO_LEAVING == 0, flags and HELLO_PHONE != 0, p[1].toInt() and 0xFF, String(p, 3, size, Charsets.UTF_8))
                val was = r.presence; r.presence = presence; r.heard = System.currentTimeMillis()
                if (flags and HELLO_REPLY != 0) hello(topic, reply = false, leaving = false)
                if (was != presence) changed()
            }
            KIND_OPEN -> {
                val conn = rd.u64() ?: return; if (tunnels.containsKey(conn)) return
                val (inner, outer) = loopbackPair() ?: return
                if (start(conn, outer, r.outbox, r.seal) == null) { runCatching { inner.close() }; return }
                val peer = if (r.code) "" else r.peer
                thread(name = "relay-in", isDaemon = true) { incoming(inner, peer) }
            }
            KIND_DATA -> {
                if (p.size < 13) return
                val conn = rd.u64()!!; val seq = rd.u32()!!; val flags = rd.u8()!!
                val t = tunnels[conn] ?: return
                var ack = false; var lost = false; var next = 0L
                synchronized(t.lock) {
                    if (seq < t.expected) return
                    if (seq > t.expected) lost = true
                    else { t.inbound.add(p.copyOfRange(13, p.size)); t.expected++; if (flags and 1 != 0 || t.expected - t.acknowledged >= ACK_EVERY) { ack = true; t.acknowledged = t.expected }; next = t.expected }
                    t.lock.notifyAll()
                }
                if (lost) { kill(t); return }
                if (ack) publish(t.outbox, t.seal, envelope(KIND_ACK).u64(conn).u32(next).build())
            }
            KIND_ACK -> {
                val conn = rd.u64() ?: return; val next = rd.u32() ?: return; val t = tunnels[conn] ?: return
                synchronized(t.lock) { if (next > t.acked && next <= t.sent) t.acked = next; t.lock.notifyAll() }
            }
            KIND_CLOSE -> { val conn = rd.u64() ?: return; val t = tunnels[conn] ?: return; synchronized(t.lock) { t.inEnd = true; t.closeSent = true; t.lock.notifyAll() } }
        }
    }

    // ---- tunnels ----
    private fun start(conn: Long, outer: Socket, outbox: String, seal: Seal): Tunnel? {
        if (!connected) { runCatching { outer.close() }; return null }
        val t = Tunnel(conn, outer, outbox, seal); tunnels[conn] = t
        thread(name = "relay-out", isDaemon = true) { pumpOut(t) }
        thread(name = "relay-in", isDaemon = true) { pumpIn(t) }
        return t
    }
    private fun pumpOut(t: Tunnel) {
        val buffer = ByteArray(CHUNK); val input: InputStream = runCatching { t.outer.getInputStream() }.getOrNull() ?: run { kill(t); finish(t); return }
        while (true) {
            val n = runCatching { input.read(buffer) }.getOrDefault(-1); if (n <= 0) break
            var seq: Long; var ackNow: Boolean
            synchronized(t.lock) {
                val end = System.currentTimeMillis() + 30_000
                while (!t.dead && t.sent - t.acked >= WINDOW) { val left = end - System.currentTimeMillis(); if (left <= 0) break; t.lock.wait(left) }
                if (t.dead || t.sent - t.acked >= WINDOW) { seq = -1; ackNow = false } else { seq = t.sent++; ackNow = t.sent - t.acked >= ACK_EVERY }
            }
            if (seq < 0) break
            val b = envelope(KIND_DATA).u64(t.conn).u32(seq).u8(if (ackNow) 1 else 0).raw(buffer.copyOf(n)).build()
            if (!publish(t.outbox, t.seal, b)) break
        }
        kill(t); finish(t)
    }
    private fun pumpIn(t: Tunnel) {
        val output = runCatching { t.outer.getOutputStream() }.getOrNull()
        while (output != null) {
            val b: ByteArray = synchronized(t.lock) {
                while (!t.dead && t.inbound.isEmpty() && !t.inEnd) t.lock.wait()
                if (t.dead || t.inbound.isEmpty()) null else t.inbound.poll()
            } ?: break
            if (runCatching { output.write(b); output.flush() }.isFailure) { kill(t); break }
        }
        runCatching { t.outer.shutdownOutput() }
        finish(t)
    }
    private fun sendClose(t: Tunnel) { synchronized(t.lock) { if (t.closeSent) return; t.closeSent = true }; publish(t.outbox, t.seal, envelope(KIND_CLOSE).u64(t.conn).build()) }
    private fun kill(t: Tunnel) {
        synchronized(t.lock) { if (t.dead) return; t.dead = true; t.lock.notifyAll() }
        runCatching { t.outer.shutdownInput() }; runCatching { t.outer.shutdownOutput() }; sendClose(t)
    }
    private fun finish(t: Tunnel) { val last = synchronized(t.lock) { ++t.finished == 2 }; if (last) { runCatching { t.outer.close() }; tunnels.remove(t.conn) } }
    private fun dropAll() {
        tunnels.values.forEach { t -> synchronized(t.lock) { t.dead = true; t.closeSent = true; t.lock.notifyAll() }; runCatching { t.outer.shutdownInput() }; runCatching { t.outer.shutdownOutput() } }
        synchronized(lock) { routes.values.forEach { it.presence = it.presence.copy(here = false) } }
    }
    private fun openTunnel(outbox: String, seal: Seal): Socket? {
        val (inner, outer) = loopbackPair() ?: return null
        val conn = Reader(Crypto.random(8)).u64()!!
        val t = start(conn, outer, outbox, seal) ?: run { runCatching { inner.close() }; return null }
        if (!publish(outbox, seal, envelope(KIND_OPEN).u64(conn).build())) { kill(t); runCatching { inner.close() }; return null }
        return inner
    }

    // ---- what the link asks ----
    fun pairs(list: List<RelayPair>) {
        val added = mutableListOf<String>()
        synchronized(lock) {
            val next = HashMap<String, Route>()
            for (p in list) {
                if (p.peer.size != 16 || p.agreed.size != 32) continue
                val secret = Crypto.sha256("arnav-relay-v1", p.agreed)
                val inbox = topic(Crypto.sha256("inbox", secret, id)); val outbox = topic(Crypto.sha256("inbox", secret, p.peer))
                val old = routes[inbox]
                if (old != null && !old.code) { next[inbox] = old; continue }
                next[inbox] = Route(p.peer.hex(), Seal(Crypto.sha256("arnav-relay-key", secret)), outbox); added += inbox
            }
            routes.filterValues { it.code }.forEach { (k, v) -> next[k] = v }
            routes.clear(); routes.putAll(next)
        }
        if (connected && added.isNotEmpty()) { subscribe(added, wait = false); added.forEach { hello(it, reply = true, leaving = false) } }
    }
    fun presence(peer: String): Presence = synchronized(lock) { routes.values.firstOrNull { !it.code && it.peer == peer }?.presence } ?: Presence()
    fun open(peer: String): Pair<Socket?, String> {
        val r = synchronized(lock) { routes.values.firstOrNull { !it.code && it.peer == peer } } ?: return null to "Pair with it first"
        if (!connected) return null to "This phone isn't connected to the internet"
        if (!r.presence.here) return null to "${r.presence.name.ifEmpty { "It" }} isn't online"
        return (openTunnel(r.outbox, r.seal) ?: return null to "The connection through the internet failed") to ""
    }
    /** Offers a pairing code for ten minutes; null when the broker can't be reached. */
    fun host(): String? {
        if (!connected) return null
        val c = String(CharArray(8) { ALPHABET[(Crypto.random(1)[0].toInt() and 0xFF) % 31] })
        val secret = Crypto.sha256("arnav-pair-code-v1", c.toByteArray())
        val inbox = topic(Crypto.sha256("host", secret)); val outbox = topic(Crypto.sha256("guest", secret))
        val left = synchronized(lock) { val l = stopHostingLocked(); routes[inbox] = Route("", Seal(Crypto.sha256("arnav-pair-code-key", secret)), outbox, code = true, host = true); code = c; codeUntil = System.currentTimeMillis() + 600_000; l }
        if (left.isNotEmpty()) unsubscribe(left)
        if (!subscribe(listOf(inbox), wait = true)) { stopHosting(); return null }
        return c.substring(0, 4) + "-" + c.substring(4)
    }
    fun stopHosting() { val left = synchronized(lock) { stopHostingLocked() }; if (left.isNotEmpty()) unsubscribe(left) }
    val hosting: String? get() = synchronized(lock) { code?.let { it.substring(0, 4) + "-" + it.substring(4) } }
    private fun stopHostingLocked(): List<String> { code = null; val left = routes.filterValues { it.host }.keys.toList(); left.forEach { routes.remove(it) }; return left }
    fun openCode(typed: String): Pair<Socket?, String> {
        val c = code(typed) ?: return null to "That isn't a pairing code"
        if (!connected) return null to "This phone isn't connected to the internet"
        val secret = Crypto.sha256("arnav-pair-code-v1", c.toByteArray())
        val inbox = topic(Crypto.sha256("guest", secret)); val outbox = topic(Crypto.sha256("host", secret))
        val seal = Seal(Crypto.sha256("arnav-pair-code-key", secret))
        synchronized(lock) { routes[inbox] = Route("", seal, outbox, code = true) }
        if (!subscribe(listOf(inbox), wait = true)) return null to "The connection through the internet failed"
        return (openTunnel(outbox, seal) ?: return null to "The connection through the internet failed") to ""
    }

    companion object {
        const val VERSION = 1
        private const val KIND_HELLO = 1; private const val KIND_OPEN = 2; private const val KIND_DATA = 3; private const val KIND_ACK = 4; private const val KIND_CLOSE = 5
        private const val HELLO_REPLY = 1; private const val HELLO_PHONE = 2; private const val HELLO_LEAVING = 4
        private const val CHUNK = 48 * 1024; private const val WINDOW = 64; private const val ACK_EVERY = 24
        private const val PREFIX = "arnavisland/r1/"
        private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        val DEFAULT_BROKERS = listOf("broker.hivemq.com" to 8883, "broker.emqx.io" to 8883, "test.mosquitto.org" to 8886)
        private fun topic(hash: ByteArray) = PREFIX + hash.hex().substring(0, 40)
        /** A typed code made canonical ("7k2p mx4q" becomes "7K2PMX4Q"), or null when it can't be one. */
        fun code(typed: String): String? {
            val s = StringBuilder(); for (ch in typed) { if (ch == ' ' || ch == '-') continue; val c = ch.uppercaseChar(); if (c !in ALPHABET) return null; s.append(c) }
            return s.toString().takeIf { it.length == 8 }
        }
        /** Two connected sockets on the loopback: the tunnel's two ends. */
        fun loopbackPair(): Pair<Socket, Socket>? = runCatching {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val a = Socket(); a.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), 5_000)
                val b = server.accept()
                // Only our own end may be the one accepted.
                if (b.port != a.localPort) { a.close(); b.close(); return null }
                a.tcpNoDelay = true; b.tcpNoDelay = true; a to b
            }
        }.getOrNull()
        private fun mqttString(s: String): ByteArray { val b = s.toByteArray(Charsets.UTF_8); return byteArrayOf((b.size shr 8).toByte(), (b.size and 0xFF).toByte()) + b }
        private fun packet(head: Int, body: ByteArray): ByteArray {
            val len = ArrayList<Byte>(); var n = body.size
            do { var d = n % 128; n /= 128; if (n > 0) d = d or 128; len += d.toByte() } while (n > 0)
            return byteArrayOf(head.toByte()) + len.toByteArray() + body
        }
        private fun connectPacket(client: String) = packet(0x10, mqttString("MQTT") + byteArrayOf(4, 2, 0, 60) + mqttString(client))
    }
}
