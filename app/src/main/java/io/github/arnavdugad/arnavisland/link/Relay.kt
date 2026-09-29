package io.github.arnavdugad.arnavisland.link

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
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
    /** Where messages to this device arrive: whose they are, how they're sealed, where replies go, and on which brokers the other device was last heard. */
    private class Route(val peer: String, val seal: Seal, val outbox: String, brokers: Int, val code: Boolean = false, val host: Boolean = false) {
        val heard = LongArray(brokers); @Volatile var helloed = 0L; @Volatile var presence = Presence()
    }
    /** A tunnel keeps to one broker (-1 until the first message from the other side says which: a code's tunnel opens on all). */
    private inner class Tunnel(val conn: Long, val outer: Socket, val outbox: String, val seal: Seal, @Volatile var broker: Int, val open: ByteArray? = null) {
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
        brokers.forEach { runCatching { it.socket?.close() } }; dropAll()
    }

    /** The phone changed networks: every broker is dialled again at once (a dead socket would take its timeout to notice). */
    fun kick() {
        if (stopping) return
        brokers.forEach { runCatching { it.socket?.close() } }
        synchronized(sleeper) { sleeper.notifyAll() }
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
        while (!stopping) {
            synchronized(sleeper) { if (!stopping) sleeper.wait(5_000) }
            if (stopping) continue
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
    private fun publish(topic: String, seal: Seal, plain: ByteArray, broker: Int): Boolean =
        writeTo(broker, packet(0x30, Bytes().raw(mqttString(topic)).raw(seal.seal(plain, topic)).build()))
    private fun hello(inbox: String, reply: Boolean, leaving: Boolean, broker: Int) {
        val r = synchronized(lock) { routes[inbox]?.also { if (broker < 0) it.helloed = System.currentTimeMillis() } } ?: return
        val n = name().toByteArray(Charsets.UTF_8).let { if (it.size > 120) it.copyOf(120) else it }
        val flags = (if (reply) HELLO_REPLY else 0) or (if (phone) HELLO_PHONE else 0) or (if (leaving) HELLO_LEAVING else 0)
        publish(r.outbox, r.seal, envelope(KIND_HELLO).u8(flags).u8(revision).u8(n.size).raw(n).build(), broker)
    }
    /** A tunnel's messages count only on its own broker (the first to carry one from the other side, when it had none). */
    private fun tunnelFor(conn: Long, from: Int): Tunnel? {
        val t = tunnels[conn] ?: return null
        synchronized(lock) { if (t.broker < 0) t.broker = from }
        return t.takeIf { it.broker == from }
    }
    private fun onPublish(from: Int, topic: String, payload: ByteArray) {
        val r = synchronized(lock) { routes[topic] } ?: return
        val plain = r.seal.open(payload, topic) ?: return
        if (plain.size < 18 || plain[0].toInt() != VERSION) return
        val sender = plain.copyOfRange(2, 18); if (sender.contentEquals(id)) return; if (!r.code && sender.hex() != r.peer) return
        val p = plain.copyOfRange(18, plain.size); val rd = Reader(p)
        when (plain[1].toInt()) {
            KIND_HELLO -> {
                if (r.code || p.size < 3) return
                val flags = p[0].toInt() and 0xFF; val size = minOf(p[2].toInt() and 0xFF, p.size - 3)
                val changedNow = synchronized(lock) {
                    val now = System.currentTimeMillis()
                    // A goodbye is said on every broker: the device has gone from all of them.
                    if (flags and HELLO_LEAVING != 0) r.heard.fill(0L) else r.heard[from] = now
                    val presence = Presence(hereAnywhere(r, now), flags and HELLO_PHONE != 0, p[1].toInt() and 0xFF, String(p, 3, size, Charsets.UTF_8))
                    val was = r.presence; r.presence = presence; was != presence
                }
                // Answered on the broker it came by, so the other device learns this one is there too.
                if (flags and HELLO_REPLY != 0) hello(topic, reply = false, leaving = false, broker = from)
                if (changedNow) changed()
            }
            KIND_OPEN -> {
                val conn = rd.u64() ?: return; if (tunnels.containsKey(conn) || ended.containsKey(conn)) return
                val (inner, outer) = loopbackPair() ?: return
                if (start(conn, outer, r.outbox, r.seal, from) == null) { runCatching { inner.close() }; return }
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
                        seq < t.expected -> if (now - t.dupAckAt >= NACK_EVERY) { t.dupAckAt = now; reply = t.expected }
                        // Something before it was lost: this one is kept, and the sender told what's missing.
                        seq > t.expected -> {
                            if (seq - t.expected < 2L * WINDOW) t.early[seq] = p.copyOfRange(13, p.size)
                            if (t.gapSince == 0L) t.gapSince = now
                            if (t.nackedFor != t.expected || now - t.nackedAt >= NACK_EVERY) { t.nackedFor = t.expected; t.nackedAt = now; reply = t.expected }
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
                    if (next > t.acked && next <= t.sent) { t.acked = next; t.unacked.headMap(next).clear(); t.progressAt = now; t.rto = RTO_BASE; t.resendAt = now + RTO_BASE }
                    // The other side is missing this one: sent again at once, with a few after it.
                    else if (next == t.acked && next < t.sent && now - t.resentAt >= NACK_EVERY) { t.resentAt = now; again = resendable(t) }
                    t.lock.notifyAll()
                }
                again.forEach { publish(t.outbox, t.seal, it, t.broker) }
            }
            KIND_CLOSE -> { val conn = rd.u64() ?: return; val t = tunnelFor(conn, from) ?: return; synchronized(t.lock) { t.inEnd = true; t.closeSent = true; t.lock.notifyAll() } }
        }
    }

    // ---- tunnels ----
    private fun start(conn: Long, outer: Socket, outbox: String, seal: Seal, broker: Int, open: ByteArray? = null): Tunnel? {
        if (!connected) { runCatching { outer.close() }; return null }
        val t = Tunnel(conn, outer, outbox, seal, broker, open); tunnels[conn] = t
        thread(name = "relay-out", isDaemon = true) { pumpOut(t) }
        thread(name = "relay-in", isDaemon = true) { pumpIn(t) }
        return t
    }
    private fun pumpOut(t: Tunnel) {
        val buffer = ByteArray(CHUNK); val input: InputStream = runCatching { t.outer.getInputStream() }.getOrNull() ?: run { kill(t); finish(t); return }
        var eof = false
        while (true) {
            val n = runCatching { input.read(buffer) }.getOrDefault(-1); if (n <= 0) { eof = true; break }
            // The end of what the protocol wrote for now: the other side is asked to say it has it all.
            val last = runCatching { input.available() == 0 }.getOrDefault(true)
            var seq: Long; var ackNow: Boolean
            synchronized(t.lock) {
                val end = System.currentTimeMillis() + 60_000
                while (!t.dead && t.sent - t.acked >= WINDOW) { val left = end - System.currentTimeMillis(); if (left <= 0) break; t.lock.wait(left) }
                if (t.dead || t.sent - t.acked >= WINDOW) { seq = -1; ackNow = false } else { seq = t.sent++; ackNow = last || t.sent - t.acked >= ACK_EVERY }
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
            val now = System.currentTimeMillis()
            ended.entries.removeIf { now - it.value > 120_000 }
            for (t in tunnels.values) {
                var again: List<ByteArray> = emptyList(); var open: ByteArray? = null; var end = false
                synchronized(t.lock) {
                    if (t.dead) return@synchronized
                    if (t.unacked.isNotEmpty() && now >= t.resendAt) {
                        if (now - t.progressAt > 45_000) end = true
                        else { again = resendable(t); if (!t.heard) open = t.open; t.resentAt = now; t.rto = minOf(t.rto * 2, RTO_MOST); t.resendAt = now + t.rto }
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
    private fun openTunnel(outbox: String, seal: Seal, broker: Int): Socket? {
        val (inner, outer) = loopbackPair() ?: return null
        val conn = Reader(Crypto.random(8)).u64()!!; val open = envelope(KIND_OPEN).u64(conn).build()
        val t = start(conn, outer, outbox, seal, broker, open) ?: run { runCatching { inner.close() }; return null }
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
    fun presence(peer: String): Presence = synchronized(lock) { routes.values.firstOrNull { !it.code && it.peer == peer }?.presence } ?: Presence()
    fun open(peer: String): Pair<Socket?, String> {
        val (r, broker) = synchronized(lock) { routes.values.firstOrNull { !it.code && it.peer == peer }?.let { it to bestBroker(it) } } ?: return null to "Pair with it first"
        if (!connected) return null to "This phone isn't connected to the internet"
        if (!r.presence.here || broker < 0) return null to "${r.presence.name.ifEmpty { "It" }} isn't online"
        return (openTunnel(r.outbox, r.seal, broker) ?: return null to "The connection through the internet failed") to ""
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
        return (openTunnel(outbox, seal, -1) ?: return null to "The connection through the internet failed") to ""
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
