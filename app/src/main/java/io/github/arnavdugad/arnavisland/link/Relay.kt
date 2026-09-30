package io.github.arnavdugad.arnavisland.link

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
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

/** An island's pairing QR code, read (see [Relay.pairLink]): its code, and the start of that PC's key fingerprint, if given. */
class PairLink(val code: String, val key: ByteArray?)

/**
 * Paired devices on different networks meet through free public MQTT brokers (HiveMQ, EMQX and Eclipse Mosquitto), over
 * TLS, exactly as the Windows island's ShareRelay does. Topics are named from each pair's own secret (SHA-256 of the
 * pair's static ECDH, which only the two devices can compute), and every message is sealed with AES-256-GCM under a key
 * from it. A connection is a tunnel: the protocol runs over one end of a local socket pair, unchanged, and its bytes
 * travel in numbered messages with a window of acknowledgements.
 *
 * 1.2: every broker at once. Two devices are then sure to share one even when one of them can't reach (or briefly lost)
 * the others: hellos and pairing codes go out on all of them, and each tunnel keeps to one broker both are on.
 *
 * 1.3: a direct path. The hellos also carry each device's addresses (global IPv6, LAN IPv4, and the public address STUN
 * servers see); both then send sealed UDP probes to all of them at once, which opens each side's NAT and firewall for the
 * other (hole punching). A probe answered is a path: new tunnels go straight there, in small datagrams paced by the
 * acknowledgements, and fall back to a broker if it goes quiet. The same wire format as the island's ShareRelay.
 */
class Relay(
    private val id: ByteArray,
    private val name: () -> String,
    private val phone: Boolean,
    private val revision: Int,
    private val incoming: (Socket, String) -> Unit,
    private val changed: () -> Unit,
    brokerList: List<Pair<String, Int>> = DEFAULT_BROKERS,
    /** Tests: the first sending of every Nth data message is left out (as a broker might drop it); 0 none. */
    private val loseEvery: Int = 0,
    /** 1.3: the direct path ([directLoopback], tests: on the loopback only; [directExtra]: more addresses to offer). */
    private val direct: Boolean = true,
    private val directLoopback: Boolean = false,
    private val directExtra: List<String> = emptyList(),
) {
    data class Presence(val here: Boolean = false, val phone: Boolean = false, val revision: Int = 0, val name: String = "")
    /** How a paired device is reached: [kind] 0 not at all, 1 through the brokers, 2 directly; its round trip (ms, 0 unknown); brokers it's heard on; IPv6. */
    data class Path(val kind: Int = 0, val rtt: Double = 0.0, val brokers: Int = 0, val v6: Boolean = false)
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
    /** Where messages to this device arrive: whose they are, how they're sealed, where replies go, and on which brokers the other device was last heard. */
    private class Route(val peer: String, val seal: Seal, val outbox: String, brokers: Int, val code: Boolean = false, val host: Boolean = false) {
        val heard = LongArray(brokers); @Volatile var helloed = 0L; @Volatile var presence = Presence()
        // 1.3: the direct path: the other device's candidates (and addresses it reached this one from), the path kept, its
        // round trip and when last heard, the relay's round trip, and the clocks for probing. Guarded by the relay's lock.
        var directOk = false; var up = false; val theirs = ArrayList<InetSocketAddress>(); var at: InetSocketAddress? = null
        var rtt = 0.0; var relayRtt = 0.0; var lastIn = 0L; var punchUntil = 0L; var nextProbe = 0L; var probedAt = 0L; var keptAt = 0L; var relayProbedAt = 0L
        fun directFresh(now: Long) = up && now - lastIn < 20_000
    }
    /** A tunnel keeps to one broker (-1 until the first message from the other side says which: a code's tunnel opens on all). */
    private inner class Tunnel(val conn: Long, val outer: Socket, val outbox: String, val seal: Seal, @Volatile var broker: Int, val open: ByteArray? = null, val route: String = "") {
        // 1.3, the direct path: a congestion window, and waits that follow its round trip.
        var cwnd = 64.0; var ssthresh = 1e9; var rtoFloor = RTO_BASE; var nackGap = NACK_EVERY
        val lock = Object(); var sent = 0L; var acked = 0L; var expected = 0L; var acknowledged = 0L
        val inbound = ArrayDeque<ByteArray>(); var inEnd = false; var dead = false; var closeSent = false; var finished = 0
        // 1.2: what was sent and not yet acknowledged (its messages, to send again), what came early, and the clocks for
        // them ([open]: the OPEN this side sent, sent again while the other side hasn't answered).
        val unacked = java.util.TreeMap<Long, ByteArray>(); val early = HashMap<Long, ByteArray>(); var heard = false
        var rto = RTO_BASE; var progressAt = 0L; var resendAt = 0L; var resentAt = 0L; var nackedFor = -1L; var nackedAt = 0L; var dupAckAt = 0L; var gapSince = 0L
    }
    /** One broker's connection, kept up on its own thread. */
    private inner class Broker(val index: Int, val host: String, val port: Int) {
        @Volatile var socket: SSLSocket? = null
        @Volatile var out: OutputStream? = null
        @Volatile var up = false
        @Volatile var lastIn = 0L; @Volatile var lastPing = 0L
        fun write(packet: ByteArray): Boolean {
            val o = out ?: return false
            return runCatching { synchronized(o) { o.write(packet); o.flush() }; true }.getOrDefault(false)
        }
    }

    private val brokers = brokerList.take(6).mapIndexed { i, (h, p) -> Broker(i, h, p) }
    private val lock = Any()
    private val routes = HashMap<String, Route>()
    private val tunnels = ConcurrentHashMap<Long, Tunnel>()
    /** Tunnels that ended lately: an OPEN sent again after one ended starts nothing. */
    private val ended = ConcurrentHashMap<Long, Long>()
    private val lost = java.util.concurrent.atomic.AtomicInteger()
    // 1.3: the direct path's sockets, this device's candidates (and what STUN saw), probes asked and not yet answered.
    @Volatile private var udp4: DatagramSocket? = null; @Volatile private var udp6: DatagramSocket? = null
    private var mine: List<Pair<InetSocketAddress, Int>> = emptyList(); private val stunSeen = ArrayList<InetSocketAddress>()
    private val stunAsked = HashMap<String, Long>(); @Volatile private var gatheredAt = 0L
    private class Asked(val inbox: String, val at: Long); private val asked = HashMap<Long, Asked>()
    private var code: String? = null; private var codeUntil = 0L
    @Volatile private var stopping = false
    private var nextPacket = 1
    private val subacks = HashMap<Pair<Int, Int>, Boolean>()
    private val sleeper = Object()

    /** Connected to at least one broker. */
    val connected get() = brokers.any { it.up }
    /** The brokers it is on, by name ("broker.hivemq.com, broker.emqx.io"), or null when none. */
    val broker: String? get() = brokers.filter { it.up }.joinToString(", ") { it.host }.ifEmpty { null }
    val brokersUp get() = brokers.count { it.up }

    fun start() {
        openDirect()
        brokers.forEach { b -> thread(name = "relay-${b.index}", isDaemon = true) { run(b) } }
        thread(name = "relay-tick", isDaemon = true) { tick() }
        thread(name = "relay-resend", isDaemon = true) { resend() }
    }
    fun stop() {
        if (connected) {
            val pairs = synchronized(lock) { routes.filterValues { !it.code }.keys.toList() }
            pairs.forEach { hello(it, reply = false, leaving = true, broker = -1) }
            writeTo(-1, byteArrayOf(0xE0.toByte(), 0))
        }
        stopping = true; synchronized(sleeper) { sleeper.notifyAll() }
        brokers.forEach { runCatching { it.socket?.close() } }; runCatching { udp4?.close() }; runCatching { udp6?.close() }; dropAll()
    }

    /** The phone changed networks: every broker is dialled again at once (a dead socket would take its timeout to notice). */
    fun kick() {
        if (stopping) return
        brokers.forEach { runCatching { it.socket?.close() } }
        synchronized(sleeper) { sleeper.notifyAll() }
        // New addresses: looked at (and told) again, and every path tried afresh.
        synchronized(lock) { stunSeen.clear(); routes.values.forEach { it.probedAt = 0L } }
        thread(name = "relay-gather", isDaemon = true) { gather(true) }
    }

    // ---- the brokers ----
    /** To one broker, or to every broker that is up (-1); true when at least one took it. */
    private fun writeTo(broker: Int, packet: ByteArray): Boolean {
        if (broker >= 0) { val b = brokers.getOrNull(broker) ?: return false; return b.up && b.write(packet) }
        var any = false; for (b in brokers) if (b.up && b.write(packet)) any = true; return any
    }
    /** A broker's address to dial: IPv4 first (it works almost everywhere), then IPv6, each given a few seconds. */
    private fun dial(host: String, port: Int): Socket {
        val all = InetAddress.getAllByName(host).sortedBy { if (it is Inet4Address) 0 else 1 }
        var last: Exception? = null
        for (address in all) {
            val raw = Socket()
            try { raw.connect(InetSocketAddress(address, port), 6_000); return raw } catch (e: Exception) { last = e; runCatching { raw.close() } }
        }
        throw last ?: java.io.IOException("no address for $host")
    }
    private fun run(b: Broker) {
        var fails = 0
        while (!stopping) {
            var worked = false
            try {
                val raw = dial(b.host, b.port)
                val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, b.host, b.port, true) as SSLSocket
                s.sslParameters = s.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                s.soTimeout = 15_000; s.startHandshake(); s.tcpNoDelay = true
                b.socket = s; b.out = s.outputStream; b.lastIn = System.currentTimeMillis()
                if (b.write(connectPacket("ai" + Crypto.random(10).hex()))) receive(b, DataInputStream(s.inputStream.buffered(64 * 1024)))
                worked = b.up
            } catch (_: Exception) {}
            val was = b.up; b.up = false
            runCatching { b.socket?.close() }; b.socket = null; b.out = null; brokerDown(b.index)
            if (was) changed()
            if (stopping) break
            fails = if (worked) 0 else fails + 1
            // A broker that can't be reached is tried less and less often, at most every minute.
            val wait = if (worked) 1_000L else minOf(60_000L, 1_000L shl minOf(fails, 6))
            synchronized(sleeper) { if (!stopping) sleeper.wait(wait) }
        }
    }
    private fun receive(b: Broker, input: DataInputStream) {
        while (!stopping) {
            val head = input.readUnsignedByte()
            var n = 0; var mult = 1; var count = 0
            while (true) { val d = input.readUnsignedByte(); n += (d and 127) * mult; if (d and 128 == 0) break; mult *= 128; if (++count > 3) return }
            val body = ByteArray(n); input.readFully(body); b.lastIn = System.currentTimeMillis()
            when (head shr 4) {
                2 -> if (body.size >= 2 && body[1].toInt() == 0 && !b.up) { b.up = true; b.socket?.soTimeout = 100_000; onConnected(b) }
                9 -> if (body.size >= 2) synchronized(lock) { val key = b.index to (((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)); if (subacks.containsKey(key)) { subacks[key] = true; (lock as Object).notifyAll() } }
                3 -> {
                    if (body.size < 2) continue
                    val tn = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF); if (body.size < 2 + tn) continue
                    var at = 2 + tn; if ((head shr 1) and 3 != 0) at += 2; if (at > body.size) continue
                    onPublish(b.index, String(body, 2, tn, Charsets.UTF_8), body.copyOfRange(at, body.size))
                }
            }
        }
    }
    /** A broker went: its tunnels end, and what was heard there no longer counts. */
    private fun brokerDown(index: Int) {
        val ending = tunnels.values.filter { it.broker == index }
        synchronized(lock) {
            val now = System.currentTimeMillis()
            routes.values.forEach { r -> r.heard[index] = 0L; r.presence = r.presence.copy(here = hereAnywhere(r, now)) }
            subacks.keys.filter { it.first == index }.forEach { subacks.remove(it) }; (lock as Object).notifyAll()
        }
        ending.forEach { t -> synchronized(t.lock) { t.dead = true; t.closeSent = true; t.lock.notifyAll() }; runCatching { t.outer.shutdownInput() }; runCatching { t.outer.shutdownOutput() } }
    }
    /** Present: heard on a broker (that is up) within the last 150 s; each device says hello on each once a minute. */
    private fun hereAnywhere(r: Route, now: Long) = brokers.any { b -> b.up && r.heard[b.index] != 0L && now - r.heard[b.index] <= 150_000 }
    /** The broker to open a tunnel on: the one the other device was heard on most lately (and this one is on). */
    private fun bestBroker(r: Route): Int { var best = -1; var at = 0L; for (b in brokers) if (b.up && r.heard[b.index] > at) { at = r.heard[b.index]; best = b.index }; return best }

    /** Hellos once a minute on every broker, gone after 150 s of silence, each broker pinged every 30 s, codes expired. */
    private fun tick() {
        // This device's addresses are looked at here (STUN looks its servers up by name, which may take a moment).
        val directOn = udp4 != null || udp6 != null; if (directOn) gather(true)
        while (!stopping) {
            synchronized(sleeper) { if (!stopping) sleeper.wait(5_000) }
            if (stopping) continue
            if (directOn && System.currentTimeMillis() - gatheredAt >= GATHER_EVERY) gather(true)
            val now = System.currentTimeMillis()
            for (b in brokers) if (b.up && now - b.lastPing >= 30_000) { b.lastPing = now; b.write(byteArrayOf(0xC0.toByte(), 0)) }
            if (!connected) continue
            var gone = false; val hello = mutableListOf<String>(); var expired: List<String> = emptyList()
            synchronized(lock) {
                for ((inbox, r) in routes) { if (r.code) continue
                    if (now - r.helloed >= 60_000) hello += inbox
                    val here = hereAnywhere(r, now); if (r.presence.here != here) { r.presence = r.presence.copy(here = here); gone = true } }
                if (code != null && now > codeUntil) expired = stopHostingLocked()
            }
            if (expired.isNotEmpty()) unsubscribe(expired)
            hello.forEach { hello(it, reply = false, leaving = false, broker = -1) }
            if (gone) changed()
        }
    }
    private fun onConnected(b: Broker) {
        val (inboxes, pairs) = synchronized(lock) { routes.keys.toList() to routes.filterValues { !it.code }.keys.toList() }
        if (inboxes.isNotEmpty()) subscribe(inboxes, wait = false, only = b.index)
        pairs.forEach { hello(it, reply = true, leaving = false, broker = b.index) }
        changed()
    }
    /** Subscribes on one broker, or on all that are up; waiting (up to 8 s) for them to confirm, true when one did. */
    private fun subscribe(topics: List<String>, wait: Boolean, only: Int = -1): Boolean {
        val asked = mutableListOf<Pair<Int, Int>>()
        for (b in brokers) {
            if ((only >= 0 && b.index != only) || !b.up) continue
            val pid = synchronized(lock) { val p = nextPacket++; if (nextPacket > 65535) nextPacket = 1; if (wait) subacks[b.index to p] = false; p }
            val body = Bytes().u8(pid shr 8).u8(pid and 0xFF); topics.forEach { body.raw(mqttString(it)).u8(0) }
            if (b.write(packet(0x82, body.build()))) asked += b.index to pid else synchronized(lock) { subacks.remove(b.index to pid) }
        }
        if (!wait) return asked.isNotEmpty()
        synchronized(lock) {
            val end = System.currentTimeMillis() + 8_000
            while (asked.any { subacks[it] == false }) { val left = end - System.currentTimeMillis(); if (left <= 0) break; (lock as Object).wait(left) }
            return asked.map { subacks.remove(it) }.any { it == true }
        }
    }
    private fun unsubscribe(topics: List<String>) {
        for (b in brokers) {
            if (!b.up) continue
            val pid = synchronized(lock) { val p = nextPacket++; if (nextPacket > 65535) nextPacket = 1; p }
            val body = Bytes().u8(pid shr 8).u8(pid and 0xFF); topics.forEach { body.raw(mqttString(it)) }
            b.write(packet(0xA2, body.build()))
        }
    }

    // ---- messages ----
    private fun envelope(kind: Int) = Bytes().u8(VERSION).u8(kind).raw(id)
    private fun publish(topic: String, seal: Seal, plain: ByteArray, broker: Int): Boolean {
        if (broker == DIRECT) { val at = synchronized(lock) { routes.values.firstOrNull { it.outbox == topic && it.up }?.at } ?: return false; return sendDatagram(at, topic, seal, plain) }
        return writeTo(broker, packet(0x30, Bytes().raw(mqttString(topic)).raw(seal.seal(plain, topic)).build()))
    }
    private fun hello(inbox: String, reply: Boolean, leaving: Boolean, broker: Int) {
        val r = synchronized(lock) { routes[inbox]?.also { if (broker < 0) it.helloed = System.currentTimeMillis() } } ?: return
        val n = name().toByteArray(Charsets.UTF_8).let { if (it.size > 120) it.copyOf(120) else it }
        val directOn = udp4 != null || udp6 != null
        val flags = (if (reply) HELLO_REPLY else 0) or (if (phone) HELLO_PHONE else 0) or (if (leaving) HELLO_LEAVING else 0) or (if (directOn) HELLO_DIRECT else 0)
        val b = envelope(KIND_HELLO).u8(flags).u8(revision).u8(n.size).raw(n)
        // 1.3: this device's addresses, for a direct path (older devices read no further than the name).
        if (directOn) { val list = synchronized(lock) { mine.take(12) }; b.u8(list.size); list.forEach { (a, kind) -> putCandidate(b, a, kind) } }
        publish(r.outbox, r.seal, b.build(), broker)
    }
    /**
     * A tunnel's messages count only on its own broker (the first to carry one from the other side, when it had none),
     * except that one on the direct path follows the other side back to a broker (the path went quiet over there).
     */
    private fun tunnelFor(conn: Long, from: Int): Tunnel? {
        val t = tunnels[conn] ?: return null
        synchronized(lock) { if (t.broker < 0) t.broker = from else if (t.broker == DIRECT && from != DIRECT) t.broker = from }
        return t.takeIf { it.broker == from }
    }
    private fun onPublish(from: Int, topic: String, payload: ByteArray, source: InetSocketAddress? = null) {
        val r = synchronized(lock) { routes[topic] } ?: return
        val plain = r.seal.open(payload, topic) ?: return
        if (plain.size < 18 || plain[0].toInt() != VERSION) return
        val sender = plain.copyOfRange(2, 18); if (sender.contentEquals(id)) return; if (!r.code && sender.hex() != r.peer) return
        val p = plain.copyOfRange(18, plain.size); val rd = Reader(p)
        // Anything sealed that comes by the path kept keeps it alive.
        if (from == DIRECT && source != null) synchronized(lock) { if (r.up && r.at == source) r.lastIn = System.currentTimeMillis() }
        when (plain[1].toInt()) {
            KIND_HELLO -> {
                if (r.code || p.size < 3 || from == DIRECT) return
                val flags = p[0].toInt() and 0xFF; val size = minOf(p[2].toInt() and 0xFF, p.size - 3)
                val offered = if (flags and HELLO_DIRECT != 0) readCandidates(p, 3 + size) else emptyList()
                val changedNow = synchronized(lock) {
                    val now = System.currentTimeMillis()
                    // A goodbye is said on every broker: the device has gone from all of them.
                    if (flags and HELLO_LEAVING != 0) r.heard.fill(0L) else r.heard[from] = now
                    val presence = Presence(hereAnywhere(r, now), flags and HELLO_PHONE != 0, p[1].toInt() and 0xFF, String(p, 3, size, Charsets.UTF_8))
                    // 1.3: its addresses: new ones (or none tried lately) are punched at once, unless a path is up.
                    if (offered.isNotEmpty() && flags and HELLO_LEAVING == 0) {
                        r.directOk = true; var fresh = false
                        for (e in offered) if (e !in r.theirs) { fresh = true; if (r.theirs.size < 16) r.theirs += e }
                        if (!r.up && (fresh || now - r.probedAt > 10_000) && now >= r.punchUntil) { r.punchUntil = now + PUNCH_FOR; r.nextProbe = now }
                    }
                    val was = r.presence; r.presence = presence; was != presence
                }
                // Answered on the broker it came by, so the other device learns this one is there too.
                if (flags and HELLO_REPLY != 0) hello(topic, reply = false, leaving = false, broker = from)
                if (changedNow) changed()
            }
            KIND_OPEN -> {
                val conn = rd.u64() ?: return; if (tunnels.containsKey(conn) || ended.containsKey(conn)) return
                val (inner, outer) = loopbackPair() ?: return
                if (start(conn, outer, r.outbox, r.seal, from, route = topic) == null) { runCatching { inner.close() }; return }
                val peer = if (r.code) "" else r.peer
                thread(name = "relay-in", isDaemon = true) { incoming(inner, peer) }
            }
            KIND_DATA -> {
                if (p.size < 13) return
                val conn = rd.u64()!!; val seq = rd.u32()!!; val flags = rd.u8()!!
                val t = tunnelFor(conn, from) ?: return
                var reply = -1L
                synchronized(t.lock) {
                    val now = System.currentTimeMillis(); t.heard = true
                    when {
                        // Had already (its acknowledgement was lost): said again, so the sender stops sending it.
                        seq < t.expected -> if (now - t.dupAckAt >= t.nackGap) { t.dupAckAt = now; reply = t.expected }
                        // Something before it was lost: this one is kept, and the sender told what's missing.
                        seq > t.expected -> {
                            if (seq - t.expected < (if (t.broker == DIRECT) 2L * WINDOW_MOST.toLong() else 2L * WINDOW)) t.early[seq] = p.copyOfRange(13, p.size)
                            if (t.gapSince == 0L) t.gapSince = now
                            if (t.nackedFor != t.expected || now - t.nackedAt >= t.nackGap) { t.nackedFor = t.expected; t.nackedAt = now; reply = t.expected }
                        }
                        else -> {
                            t.inbound.add(p.copyOfRange(13, p.size)); t.expected++
                            var filled = false
                            while (true) { val next = t.early.remove(t.expected) ?: break; t.inbound.add(next); t.expected++; filled = true }
                            t.gapSince = if (t.early.isEmpty()) 0L else now
                            if (flags and 1 != 0 || filled || t.expected - t.acknowledged >= ACK_EVERY) reply = t.expected
                        }
                    }
                    if (reply >= 0) t.acknowledged = maxOf(t.acknowledged, reply)
                    t.lock.notifyAll()
                }
                if (reply >= 0) publish(t.outbox, t.seal, envelope(KIND_ACK).u64(conn).u32(reply).build(), t.broker)
            }
            KIND_ACK -> {
                val conn = rd.u64() ?: return; val next = rd.u32() ?: return; val t = tunnelFor(conn, from) ?: return
                var again: List<ByteArray> = emptyList()
                synchronized(t.lock) {
                    val now = System.currentTimeMillis(); t.heard = true
                    if (next > t.acked && next <= t.sent) {
                        val k = (next - t.acked).toDouble(); t.acked = next; t.unacked.headMap(next).clear(); t.progressAt = now; t.rto = t.rtoFloor; t.resendAt = now + t.rtoFloor
                        // The direct path's window grows with what arrives: doubling each round trip, then a message a round trip.
                        if (t.broker == DIRECT) t.cwnd = minOf(WINDOW_MOST, if (t.cwnd < t.ssthresh) t.cwnd + k else t.cwnd + k / t.cwnd)
                    }
                    // The other side is missing this one: sent again at once, with a few after it (a loss: the window halves).
                    else if (next == t.acked && next < t.sent && now - t.resentAt >= t.nackGap) {
                        t.resentAt = now; again = resendable(t)
                        if (t.broker == DIRECT) { t.ssthresh = maxOf(WINDOW_LEAST, t.cwnd / 2); t.cwnd = t.ssthresh }
                    }
                    t.lock.notifyAll()
                }
                again.forEach { publish(t.outbox, t.seal, it, t.broker) }
            }
            KIND_CLOSE -> { val conn = rd.u64() ?: return; val t = tunnelFor(conn, from) ?: return; synchronized(t.lock) { t.inEnd = true; t.closeSent = true; t.lock.notifyAll() } }
            // 1.3: a probe is answered the way it came: by the direct path, to the address it came from (one the other device
            // reached this one from is tried too), or on its broker (the relay's round trip, for the connection's quality).
            KIND_PROBE -> {
                if (r.code || p.size < 16) return
                val answer = envelope(KIND_PROBE_ACK).raw(p.copyOfRange(0, 16)).build()
                if (from == DIRECT && source != null) {
                    sendDatagram(source, r.outbox, r.seal, answer)
                    synchronized(lock) {
                        r.directOk = true
                        if (source !in r.theirs) { if (r.theirs.size >= 16) r.theirs.removeAt(0); r.theirs += source
                            if (!r.up) { val now = System.currentTimeMillis(); r.punchUntil = maxOf(r.punchUntil, now + 3_000); r.nextProbe = now } }
                    }
                } else publish(r.outbox, r.seal, answer, from)
            }
            KIND_PROBE_ACK -> {
                if (r.code || p.size < 16) return
                val nonce = rd.u64() ?: return; val sentAt = rd.u64() ?: return
                val rtt = (System.currentTimeMillis() - sentAt).toDouble(); if (rtt < 0 || rtt > 30_000) return
                var changedNow = false
                synchronized(lock) {
                    val now = System.currentTimeMillis()
                    if (from == DIRECT) {
                        val a = asked[nonce]; if (source == null || a == null || a.inbox != topic) return; asked.remove(nonce)
                        val same = r.up && r.at == source; val before = r.rtt
                        // The first path answered, the same one again, or one clearly faster: kept.
                        if (!r.up || same || rtt < r.rtt * .7) {
                            r.at = source; r.rtt = if (same) r.rtt * .8 + rtt * .2 else rtt; r.up = true; r.lastIn = now; r.punchUntil = now; r.keptAt = now
                            changedNow = !same || kotlin.math.abs(r.rtt - before) > before * .15
                        }
                    } else { val before = r.relayRtt; r.relayRtt = if (before > 0) before * .7 + rtt * .3 else rtt; changedNow = before <= 0 || kotlin.math.abs(r.relayRtt - before) > before * .15 }
                }
                if (changedNow) changed()
            }
        }
    }

    // ---- 1.3: the direct path ----
    /** A message by the direct path: sealed exactly as on a broker, behind the recipient's inbox id. */
    private fun sendDatagram(to: InetSocketAddress, topic: String, seal: Seal, plain: ByteArray): Boolean {
        val s = (if (to.address is Inet6Address) udp6 else udp4) ?: return false
        val id = topic.removePrefix(PREFIX).unhex() ?: return false; if (id.size != 20) return false
        val d = byteArrayOf(DATAGRAM_MAGIC.toByte(), DATAGRAM_VERSION.toByte()) + id + seal.seal(plain, topic)
        return runCatching { s.send(DatagramPacket(d, d.size, to)); true }.getOrDefault(false)
    }
    /** A socket per family (on the loopback only, for tests), with room for a window's worth of datagrams. */
    private fun openDirect() {
        if (!direct) return
        fun open(address: InetAddress): DatagramSocket? = runCatching {
            DatagramSocket(null).apply { runCatching { receiveBufferSize = 4 shl 20; sendBufferSize = 4 shl 20 }; bind(InetSocketAddress(address, 0)); soTimeout = 1_000 }
        }.getOrNull()
        udp4 = open(if (directLoopback) InetAddress.getByName("127.0.0.1") else InetAddress.getByName("0.0.0.0"))
        udp6 = open(if (directLoopback) InetAddress.getByName("::1") else InetAddress.getByName("::"))
        listOfNotNull(udp4, udp6).forEach { s -> thread(name = "relay-udp", isDaemon = true) { receiveDirect(s) } }
    }
    private fun receiveDirect(s: DatagramSocket) {
        val buffer = ByteArray(2048)
        while (!stopping && !s.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try { s.receive(packet) } catch (_: java.net.SocketTimeoutException) { continue } catch (_: Exception) { if (stopping || s.isClosed) break else continue }
            val n = packet.length; val source = packet.socketAddress as? InetSocketAddress ?: continue
            // A STUN answer (a binding success, with the magic cookie).
            if (n >= 20 && buffer[0].toInt() == 0x01 && buffer[1].toInt() == 0x01 && buffer[4] == 0x21.toByte() && buffer[5] == 0x12.toByte() && buffer[6] == 0xA4.toByte() && buffer[7] == 0x42.toByte()) { onStun(buffer.copyOf(n)); continue }
            if (n > 22 + 28 && buffer[0] == DATAGRAM_MAGIC.toByte() && buffer[1].toInt() == DATAGRAM_VERSION)
                onPublish(DIRECT, PREFIX + buffer.copyOfRange(2, 22).hex(), buffer.copyOfRange(22, n), source)
        }
    }
    /**
     * This device's candidates: its interfaces' global IPv6 and IPv4 addresses (a LAN address is how two devices behind one
     * router meet), what STUN saw, and any offered by hand. New ones go out at once in a hello to every paired device.
     */
    private fun gather(askStun: Boolean) {
        val found = ArrayList<Pair<InetSocketAddress, Int>>()
        val port4 = udp4?.localPort ?: 0; val port6 = udp6?.localPort ?: 0
        fun add(a: InetAddress, kind: Int) {
            val port = if (a is Inet6Address) port6 else port4; if (port == 0 || found.size >= 12) return
            val e = InetSocketAddress(a, port); if (found.none { it.first == e }) found += e to kind
        }
        if (directLoopback) { if (udp4 != null) add(InetAddress.getByName("127.0.0.1"), 0); if (udp6 != null) add(InetAddress.getByName("::1"), 0) }
        else runCatching {
            for (i in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                if (!runCatching { i.isUp && !i.isLoopback }.getOrDefault(false)) continue
                for (a in i.inetAddresses.toList()) when {
                    // Global unicast only (2000::/3): link-local and unique-local addresses reach no one outside.
                    a is Inet6Address -> if ((a.address[0].toInt() and 0xE0) == 0x20) add(InetAddress.getByAddress(a.address), 0)
                    a is Inet4Address -> if (!a.isLoopbackAddress && !a.isLinkLocalAddress) add(a, 0)
                }
            }
        }
        synchronized(lock) { stunSeen.toList() }.forEach { e -> if (found.size < 12 && found.none { it.first == e }) found += e to 1 }
        for (text in directExtra) runCatching { add(InetAddress.getByName(text), 2) }
        val changedNow = synchronized(lock) { val c = found.map { it.first } != mine.map { it.first }; if (c) mine = found; gatheredAt = System.currentTimeMillis(); c }
        if (askStun && !directLoopback) stun()
        if (changedNow) helloAll()
    }
    private fun helloAll() { if (!connected) return; val pairs = synchronized(lock) { routes.filterValues { !it.code }.keys.toList() }; pairs.forEach { hello(it, reply = false, leaving = false, broker = -1) } }
    /** STUN binding requests (RFC 5389) from both sockets: the answers say where this device's datagrams appear to come from. */
    private fun stun() {
        for (server in STUN_SERVERS) {
            val host = server.substringBeforeLast(':'); val port = server.substringAfterLast(':').toIntOrNull() ?: continue
            val all = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
            for (six in listOf(false, true)) {
                val a = all.firstOrNull { (it is Inet6Address) == six } ?: continue; val s = (if (six) udp6 else udp4) ?: continue
                val tid = Crypto.random(12); synchronized(lock) { stunAsked[tid.hex()] = System.currentTimeMillis() }
                val q = byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x21, 0x12, 0xA4.toByte(), 0x42) + tid
                runCatching { s.send(DatagramPacket(q, q.size, InetSocketAddress(a, port))) }
            }
        }
    }
    private fun onStun(d: ByteArray) {
        synchronized(lock) { if (stunAsked.remove(d.copyOfRange(8, 20).hex()) == null) return }
        var at = 20
        while (at + 4 <= d.size) {
            val type = ((d[at].toInt() and 0xFF) shl 8) or (d[at + 1].toInt() and 0xFF); val len = ((d[at + 2].toInt() and 0xFF) shl 8) or (d[at + 3].toInt() and 0xFF)
            if (at + 4 + len > d.size) break
            if (type == 0x0020 && len >= 8) {
                val port = (((d[at + 6].toInt() and 0xFF) shl 8) or (d[at + 7].toInt() and 0xFF)) xor 0x2112
                val key = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42) + d.copyOfRange(8, 20)
                val size = when (d[at + 5].toInt()) { 1 -> 4; 2 -> 16; else -> 0 }; if (size == 0 || len < 4 + size) break
                val ip = ByteArray(size) { (d[at + 8 + it].toInt() xor key[it].toInt()).toByte() }
                val e = InetSocketAddress(InetAddress.getByAddress(ip), port)
                val fresh = synchronized(lock) { if (e in stunSeen) false else { if (stunSeen.size >= 4) stunSeen.removeAt(0); stunSeen += e; true } }
                if (fresh) gather(false)
                break
            }
            at += 4 + len + ((4 - len % 4) % 4)
        }
    }
    /**
     * Five times a second (with the resends): probes while punching, a path kept alive, a quiet one dropped (its tunnels go
     * back to a broker), no path tried again now and then, and the relay's round trip measured.
     */
    private fun directTick() {
        if (udp4 == null && udp6 == null) return
        class Send(val to: InetSocketAddress?, val topic: String, val seal: Seal, val plain: ByteArray, val broker: Int = DIRECT)
        val out = ArrayList<Send>(); val fell = ArrayList<String>(); var changedNow = false
        synchronized(lock) {
            val now = System.currentTimeMillis()
            asked.entries.removeIf { now - it.value.at > 15_000 }; stunAsked.entries.removeIf { now - it.value > 15_000 }
            fun probe(inbox: String): ByteArray { val nonce = Reader(Crypto.random(8)).u64()!!; asked[nonce] = Asked(inbox, now); return envelope(KIND_PROBE).u64(nonce).u64(now).build() }
            for ((inbox, r) in routes) {
                if (r.code) continue
                if (r.up && now - r.lastIn > DIRECT_GONE) { r.up = false; changedNow = true; fell += inbox; r.punchUntil = now + PUNCH_FOR; r.nextProbe = now }
                val present = r.presence.here || r.up
                if (r.directOk && !r.up && present && r.theirs.isNotEmpty() && now >= r.punchUntil && now - r.probedAt > REPROBE_EVERY) { r.punchUntil = now + PUNCH_FOR; r.nextProbe = now }
                if (!r.up && now < r.punchUntil && now >= r.nextProbe) { r.nextProbe = now + PROBE_EVERY; r.probedAt = now; r.theirs.forEach { out += Send(it, r.outbox, r.seal, probe(inbox)) } }
                else if (r.up && now - r.keptAt >= KEEP_EVERY) { r.keptAt = now; out += Send(r.at, r.outbox, r.seal, probe(inbox)) }
                if (!r.up && r.presence.here && now - r.relayProbedAt >= RELAY_PROBE_EVERY) { val b = bestBroker(r); if (b >= 0) { r.relayProbedAt = now; out += Send(null, r.outbox, r.seal, envelope(KIND_PROBE).u64(Reader(Crypto.random(8)).u64()!!).u64(now).build(), b) } }
            }
        }
        for (s in out) if (s.broker == DIRECT) s.to?.let { sendDatagram(it, s.topic, s.seal, s.plain) } else publish(s.topic, s.seal, s.plain, s.broker)
        fell.forEach { fallBack(it) }
        if (changedNow) changed()
    }
    /** A direct path gone quiet: its tunnels carry on through the broker the other device was heard on most lately. */
    private fun fallBack(inbox: String) {
        val ending = ArrayList<Tunnel>()
        synchronized(lock) { val b = routes[inbox]?.let { bestBroker(it) } ?: -1; for (t in tunnels.values) if (t.route == inbox && t.broker == DIRECT) { if (b >= 0) t.broker = b else ending += t } }
        ending.forEach { kill(it) }
    }

    // ---- tunnels ----
    private fun start(conn: Long, outer: Socket, outbox: String, seal: Seal, broker: Int, open: ByteArray? = null, route: String = ""): Tunnel? {
        if (broker != DIRECT && !connected) { runCatching { outer.close() }; return null }
        val t = Tunnel(conn, outer, outbox, seal, broker, open, route)
        // On the direct path, its waits follow the path's round trip.
        if (broker == DIRECT) { val rtt = synchronized(lock) { routes[route]?.rtt } ?: 100.0; t.rtoFloor = (rtt * 3).toLong().coerceIn(150, 2_000); t.nackGap = rtt.toLong().coerceIn(40, 300); t.rto = t.rtoFloor }
        tunnels[conn] = t
        thread(name = "relay-out", isDaemon = true) { pumpOut(t) }
        thread(name = "relay-in", isDaemon = true) { pumpIn(t) }
        return t
    }
    private fun pumpOut(t: Tunnel) {
        val buffer = ByteArray(CHUNK); val input: InputStream = runCatching { t.outer.getInputStream() }.getOrNull() ?: run { kill(t); finish(t); return }
        var eof = false
        // Never more than the window ahead of the acknowledgements: 64 messages through a broker, the congestion window directly.
        fun room() = if (t.broker == DIRECT) t.cwnd.coerceIn(WINDOW_LEAST, WINDOW_MOST).toLong() else WINDOW.toLong()
        while (true) {
            val n = runCatching { input.read(buffer, 0, if (t.broker == DIRECT) DIRECT_CHUNK else CHUNK) }.getOrDefault(-1); if (n <= 0) { eof = true; break }
            // The end of what the protocol wrote for now: the other side is asked to say it has it all.
            val last = runCatching { input.available() == 0 }.getOrDefault(true)
            var seq: Long; var ackNow: Boolean
            synchronized(t.lock) {
                val end = System.currentTimeMillis() + 60_000
                while (!t.dead && t.sent - t.acked >= room()) { val left = end - System.currentTimeMillis(); if (left <= 0) break; t.lock.wait(left) }
                if (t.dead || t.sent - t.acked >= room()) { seq = -1; ackNow = false }
                else { seq = t.sent++; ackNow = last || (if (t.broker == DIRECT) seq % 16 == 15L else t.sent - t.acked >= ACK_EVERY) }
            }
            if (seq < 0) break
            val b = envelope(KIND_DATA).u64(t.conn).u32(seq).u8(if (ackNow) 1 else 0).raw(buffer.copyOf(n)).build()
            synchronized(t.lock) { val now = System.currentTimeMillis(); if (t.unacked.isEmpty()) { t.progressAt = now; t.resendAt = now + t.rto }; t.unacked[seq] = b }
            if (loseEvery > 0 && lost.incrementAndGet() % loseEvery == 0) continue
            if (!publish(t.outbox, t.seal, b, t.broker)) break
        }
        // Everything written arrives before the end is said (what seems lost is sent again meanwhile), unless the other side ended first.
        if (eof) synchronized(t.lock) {
            val end = System.currentTimeMillis() + 20_000
            while (!t.dead && !t.inEnd && t.acked < t.sent) { val left = end - System.currentTimeMillis(); if (left <= 0) break; t.lock.wait(left) }
        }
        kill(t); finish(t)
    }
    /** Called with t.lock held: the first few unacknowledged messages, the last asking to be acknowledged. */
    private fun resendable(t: Tunnel): List<ByteArray> {
        val out = t.unacked.values.take(RESEND_BATCH).map { it.copyOf() }
        out.lastOrNull()?.let { if (it.size > DATA_FLAGS) it[DATA_FLAGS] = (it[DATA_FLAGS].toInt() or 1).toByte() }
        return out
    }
    /**
     * Five times a second: what wasn't acknowledged in time is sent again (with its OPEN while nothing was heard back), the
     * wait doubling up to 8 s; a tunnel that got nowhere for 45 s (or kept a gap 30 s) ends.
     */
    private fun resend() {
        while (!stopping) {
            runCatching { Thread.sleep(200) }
            runCatching { directTick() }
            val now = System.currentTimeMillis()
            ended.entries.removeIf { now - it.value > 120_000 }
            for (t in tunnels.values) {
                var again: List<ByteArray> = emptyList(); var open: ByteArray? = null; var end = false
                synchronized(t.lock) {
                    if (t.dead) return@synchronized
                    if (t.unacked.isNotEmpty() && now >= t.resendAt) {
                        if (now - t.progressAt > 45_000) end = true
                        else {
                            again = resendable(t); if (!t.heard) open = t.open; t.resentAt = now; val directNow = t.broker == DIRECT
                            t.rto = minOf(t.rto * 2, if (directNow) 4_000L else RTO_MOST); t.resendAt = now + t.rto
                            // Nothing acknowledged in a while: the direct path's window starts small again.
                            if (directNow) { t.ssthresh = maxOf(WINDOW_LEAST, t.cwnd / 2); t.cwnd = WINDOW_LEAST }
                        }
                    }
                    if (t.gapSince > 0 && now - t.gapSince > 30_000) end = true
                }
                if (end) { kill(t); continue }
                open?.let { publish(t.outbox, t.seal, it, t.broker) }
                again.forEach { publish(t.outbox, t.seal, it, t.broker) }
            }
        }
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
    private fun sendClose(t: Tunnel) { synchronized(t.lock) { if (t.closeSent) return; t.closeSent = true }; publish(t.outbox, t.seal, envelope(KIND_CLOSE).u64(t.conn).build(), t.broker) }
    private fun kill(t: Tunnel) {
        synchronized(t.lock) { if (t.dead) return; t.dead = true; t.lock.notifyAll() }
        runCatching { t.outer.shutdownInput() }; runCatching { t.outer.shutdownOutput() }; sendClose(t)
    }
    private fun finish(t: Tunnel) { val last = synchronized(t.lock) { ++t.finished == 2 }; if (last) { runCatching { t.outer.close() }; tunnels.remove(t.conn); ended[t.conn] = System.currentTimeMillis() } }
    private fun dropAll() {
        tunnels.values.forEach { t -> synchronized(t.lock) { t.dead = true; t.closeSent = true; t.lock.notifyAll() }; runCatching { t.outer.shutdownInput() }; runCatching { t.outer.shutdownOutput() } }
        synchronized(lock) { routes.values.forEach { it.heard.fill(0L); it.presence = it.presence.copy(here = false) } }
    }
    private fun openTunnel(outbox: String, seal: Seal, broker: Int, route: String): Socket? {
        val (inner, outer) = loopbackPair() ?: return null
        val conn = Reader(Crypto.random(8)).u64()!!; val open = envelope(KIND_OPEN).u64(conn).build()
        val t = start(conn, outer, outbox, seal, broker, open, route) ?: run { runCatching { inner.close() }; return null }
        if (!publish(outbox, seal, open, broker)) { kill(t); runCatching { inner.close() }; return null }
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
                next[inbox] = Route(p.peer.hex(), Seal(Crypto.sha256("arnav-relay-key", secret)), outbox, brokers.size); added += inbox
            }
            routes.filterValues { it.code }.forEach { (k, v) -> next[k] = v }
            routes.clear(); routes.putAll(next)
        }
        if (connected && added.isNotEmpty()) { subscribe(added, wait = false); added.forEach { hello(it, reply = true, leaving = false, broker = -1) } }
    }
    fun presence(peer: String): Presence = synchronized(lock) {
        routes.values.firstOrNull { !it.code && it.peer == peer }?.let { r -> if (r.directFresh(System.currentTimeMillis())) r.presence.copy(here = true) else r.presence }
    } ?: Presence()
    /** 1.3: how a paired device is reached now (for the connection's quality ring). */
    fun path(peer: String): Path = synchronized(lock) {
        val now = System.currentTimeMillis(); val r = routes.values.firstOrNull { !it.code && it.peer == peer } ?: return Path()
        val heard = brokers.count { b -> b.up && r.heard[b.index] != 0L && now - r.heard[b.index] <= 150_000 }
        when { r.directFresh(now) -> Path(2, r.rtt, heard, r.at?.address is Inet6Address); r.presence.here && heard > 0 -> Path(1, r.relayRtt, heard); else -> Path(0, 0.0, heard) }
    }
    /** 1.3: straight there when a direct path is up; through the broker it was heard on most lately otherwise. */
    fun open(peer: String): Pair<Socket?, String> {
        val (entry, broker) = synchronized(lock) {
            routes.entries.firstOrNull { !it.value.code && it.value.peer == peer }?.let { e -> e to if (e.value.directFresh(System.currentTimeMillis())) DIRECT else bestBroker(e.value) }
        } ?: return null to "Pair with it first"
        val r = entry.value
        if (broker != DIRECT && !connected) return null to "This phone isn't connected to the internet"
        if ((!r.presence.here && broker != DIRECT) || broker < 0) return null to "${r.presence.name.ifEmpty { "It" }} isn't online"
        return (openTunnel(r.outbox, r.seal, broker, entry.key) ?: return null to "The connection through the internet failed") to ""
    }
    /** Offers a pairing code for ten minutes, listened for on every broker; null when no broker can be reached. */
    fun host(): String? {
        if (!connected) return null
        val c = String(CharArray(8) { ALPHABET[(Crypto.random(1)[0].toInt() and 0xFF) % 31] })
        val secret = Crypto.sha256("arnav-pair-code-v1", c.toByteArray())
        val inbox = topic(Crypto.sha256("host", secret)); val outbox = topic(Crypto.sha256("guest", secret))
        val left = synchronized(lock) { val l = stopHostingLocked(); routes[inbox] = Route("", Seal(Crypto.sha256("arnav-pair-code-key", secret)), outbox, brokers.size, code = true, host = true); code = c; codeUntil = System.currentTimeMillis() + 600_000; l }
        if (left.isNotEmpty()) unsubscribe(left)
        if (!subscribe(listOf(inbox), wait = true)) { stopHosting(); return null }
        return c.substring(0, 4) + "-" + c.substring(4)
    }
    fun stopHosting() { val left = synchronized(lock) { stopHostingLocked() }; if (left.isNotEmpty()) unsubscribe(left) }
    val hosting: String? get() = synchronized(lock) { code?.let { it.substring(0, 4) + "-" + it.substring(4) } }
    private fun stopHostingLocked(): List<String> { code = null; val left = routes.filterValues { it.host }.keys.toList(); left.forEach { routes.remove(it) }; return left }
    /** A tunnel to the device showing this code: opened on every broker, it keeps to whichever the other device answers on. */
    fun openCode(typed: String): Pair<Socket?, String> {
        val c = code(typed) ?: return null to "That isn't a pairing code"
        if (!connected) return null to "This phone isn't connected to the internet"
        val secret = Crypto.sha256("arnav-pair-code-v1", c.toByteArray())
        val inbox = topic(Crypto.sha256("guest", secret)); val outbox = topic(Crypto.sha256("host", secret))
        val seal = Seal(Crypto.sha256("arnav-pair-code-key", secret))
        synchronized(lock) { routes[inbox] = Route("", seal, outbox, brokers.size, code = true) }
        if (!subscribe(listOf(inbox), wait = true)) return null to "The connection through the internet failed"
        return (openTunnel(outbox, seal, -1, inbox) ?: return null to "The connection through the internet failed") to ""
    }

    companion object {
        const val VERSION = 1
        private const val KIND_HELLO = 1; private const val KIND_OPEN = 2; private const val KIND_DATA = 3; private const val KIND_ACK = 4; private const val KIND_CLOSE = 5
        private const val HELLO_REPLY = 1; private const val HELLO_PHONE = 2; private const val HELLO_LEAVING = 4
        private const val CHUNK = 48 * 1024; private const val WINDOW = 64; private const val ACK_EVERY = 24
        // 1.2: a public broker may drop a message now and then (QoS 0): sent again after 1.5 s unacknowledged (doubling to 8
        // s), a gap reported at most every 300 ms, eight messages at a time; a DATA message's flags byte is at DATA_FLAGS.
        private const val RTO_BASE = 1_500L; private const val RTO_MOST = 8_000L; private const val NACK_EVERY = 300L
        private const val RESEND_BATCH = 8; private const val DATA_FLAGS = 2 + 16 + 8 + 4
        // 1.3: the direct path (the island's ShareRelay has the same numbers). A tunnel on it has DIRECT for its broker.
        const val DIRECT = 100
        private const val KIND_PROBE = 7; private const val KIND_PROBE_ACK = 8; private const val HELLO_DIRECT = 8
        private const val DATAGRAM_MAGIC = 0xA1; private const val DATAGRAM_VERSION = 1; private const val DIRECT_CHUNK = 1100
        private const val WINDOW_LEAST = 16.0; private const val WINDOW_MOST = 1024.0
        private const val PROBE_EVERY = 200L; private const val PUNCH_FOR = 8_000L; private const val KEEP_EVERY = 15_000L; private const val DIRECT_GONE = 35_000L
        private const val REPROBE_EVERY = 60_000L; private const val GATHER_EVERY = 45_000L; private const val RELAY_PROBE_EVERY = 20_000L
        private val STUN_SERVERS = listOf("stun.l.google.com:19302", "stun.cloudflare.com:3478", "stun1.l.google.com:19302")
        /** Candidates as a hello carries them: family (4 or 6), address, port (big-endian) and kind (0 own, 1 STUN, 2 by hand). */
        private fun putCandidate(b: Bytes, a: InetSocketAddress, kind: Int) {
            val ip = a.address.address; b.u8(if (ip.size == 16) 6 else 4).raw(ip).u8(a.port shr 8).u8(a.port and 0xFF).u8(kind)
        }
        private fun readCandidates(p: ByteArray, from: Int): List<InetSocketAddress> {
            if (from >= p.size) return emptyList(); val count = minOf(p[from].toInt() and 0xFF, 16); var at = from + 1; val out = ArrayList<InetSocketAddress>()
            repeat(count) {
                if (at >= p.size) return out; val size = when (p[at].toInt()) { 6 -> 16; 4 -> 4; else -> return out }; at++
                if (at + size + 3 > p.size) return out
                val port = ((p[at + size].toInt() and 0xFF) shl 8) or (p[at + size + 1].toInt() and 0xFF)
                val e = runCatching { InetSocketAddress(InetAddress.getByAddress(p.copyOfRange(at, at + size)), port) }.getOrNull(); at += size + 3
                if (e != null && port != 0 && e !in out) out += e
            }
            return out
        }
        private const val PREFIX = "arnavisland/r1/"
        private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        val DEFAULT_BROKERS = listOf("broker.hivemq.com" to 8883, "broker.emqx.io" to 8883, "test.mosquitto.org" to 8886)
        private fun topic(hash: ByteArray) = PREFIX + hash.hex().substring(0, 40)
        /** A typed code made canonical ("7k2p mx4q" becomes "7K2PMX4Q"), or null when it can't be one. */
        fun code(typed: String): String? {
            val s = StringBuilder(); for (ch in typed) { if (ch == ' ' || ch == '-') continue; val c = ch.uppercaseChar(); if (c !in ALPHABET) return null; s.append(c) }
            return s.toString().takeIf { it.length == 8 }
        }
        /**
         * The pairing link in an island's QR code, "arnavisland://pair/7K2PMX4Q?k=<20 hex digits>": its code, and the first ten
         * bytes of the SHA-256 of that PC's public key (null when the link gives none, or a malformed one). Null when it isn't one.
         */
        fun pairLink(text: String): PairLink? {
            val t = text.trim(); val prefix = "arnavisland://pair/"
            if (!t.startsWith(prefix, ignoreCase = true)) return null
            val rest = t.substring(prefix.length).substringBefore('#')
            val code = code(rest.substringBefore('?').trimEnd('/')) ?: return null
            val k = rest.substringAfter('?', "").split('&').firstOrNull { it.startsWith("k=", ignoreCase = true) }?.substring(2)
            val key = k?.takeIf { it.length == 20 && it.all { c -> c in '0'..'9' || c.lowercaseChar() in 'a'..'f' } }?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
            return PairLink(code, key)
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
