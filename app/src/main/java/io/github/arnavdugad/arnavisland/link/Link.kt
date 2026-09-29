package io.github.arnavdugad.arnavisland.link

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.PrivateKey
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Arnav Island's sharing protocol on the phone, exactly as the Windows island speaks it: devices announce themselves by
 * UDP broadcast; two pair once, each showing the same six-digit code; after that, everything goes between paired devices
 * over TCP, sealed with AES-256-GCM under a key from both sides' static ECDH keys and fresh nonces.
 * On different networks the same protocol runs through the relay ([Relay]); devices that never shared one pair with a code.
 * Plain JVM code (no Android): the unit tests run it against the Windows ShareService itself.
 */
class Link(
    private val store: LinkStore,
    name: String,
    private val inbox: Inbox,
    private val onEvent: (LinkEvent) -> Unit,
    private val options: Options = Options(),
) {
    class Options(val tcpPort: Int = Proto.TCP_PORT, val udpPort: Int = Proto.UDP_PORT, val discovery: Boolean = true, val loopback: Boolean = false, val phone: Boolean = true,
                  val relay: Boolean = false, val relayBrokers: List<Pair<String, Int>> = Relay.DEFAULT_BROKERS, val relayLoseEvery: Int = 0)

    private class Peer(var name: String = "", var address: String? = null, var port: Int = 0, var version: Int = 1, var revision: Int = 0,
                       var online: Boolean = false, var seen: Long = 0, var key: ByteArray? = null, var phone: Boolean = false)
    private class Session(var peerId: ByteArray = ByteArray(0), var peerPub: ByteArray = ByteArray(0), var peerName: String = "",
                          var channel: Channel? = null, var code: Int = 0, var rejected: Boolean = false, var outdated: Boolean = false)
    /** A connection's streams. The input is buffered with mark support, so a peer that closes can be noticed without reading. */
    private class Conn(val socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
        val output: OutputStream = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        fun send(frame: ByteArray) = Frames.write(output, frame)
        fun recv(): ByteArray = Frames.read(input)
        fun timeout(ms: Int) { socket.soTimeout = ms }
        fun close() = runCatching { socket.close() }
    }
    private class Live(@Volatile var socket: Socket? = null) { val stop = AtomicBoolean(false) }

    var displayName = cleanName(name)
    private var id = ByteArray(0)
    private var pub = ByteArray(0)
    private var key: PrivateKey? = null
    private val lock = Any()
    private val peers = HashMap<String, Peer>()
    @Volatile private var pairing: CompletableFuture<Int>? = null
    private val decisions = ConcurrentHashMap<Int, CompletableFuture<Int>>()
    private val live = ConcurrentHashMap<Int, Live>()
    private val nextTransfer = AtomicInteger(1)
    @Volatile private var stopping = false
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var relay: Relay? = null
    @Volatile private var announceNow = false
    /** Connected to the relay, and through which broker. */
    val internet get() = relay?.connected == true
    /** Revision 3, asked by a paired PC: run one of this phone's notifications' actions (0 done, 1 gone, 2 failed), and set the clipboard. */
    @Volatile var onAction: ((key: String, index: Int, reply: String) -> Int)? = null
    @Volatile var onClipboard: ((text: String, sensitive: Boolean) -> Boolean)? = null
    val relayBroker get() = relay?.broker
    /** How many of the public brokers this device is on (it stays on all it can reach). */
    val relayBrokersUp get() = relay?.brokersUp ?: 0
    var failure: String? = null; private set
    val identity get() = id.hex()
    /** The port actually listened on (for tests that ask for any free port). */
    val port get() = server?.localPort ?: options.tcpPort

    // ---- life ----
    fun start(): Boolean {
        if (!identity()) { failure = "The sharing key couldn't be created"; return false }
        loadPeers()
        try {
            server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(if (options.loopback) InetAddress.getLoopbackAddress() else null, options.tcpPort), 8) }
        } catch (e: Exception) { failure = "Port ${options.tcpPort} is in use"; return false }
        if (options.discovery) try {
            udp = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 500; bind(InetSocketAddress(options.udpPort)) }
        } catch (e: Exception) { failure = "Discovery port ${options.udpPort} is in use"; server?.close(); return false }
        thread(name = "link-listen", isDaemon = true) { listen() }
        if (options.discovery) thread(name = "link-discover", isDaemon = true) { discover() }
        if (options.relay) {
            relay = Relay(id, { displayName }, options.phone, Proto.REVISION,
                incoming = { s, _ -> val c = Conn(s); try { incoming(c) } catch (_: Exception) {} finally { c.close() } },
                changed = { postPeers() }, brokerList = options.relayBrokers, loseEvery = options.relayLoseEvery).also { it.start() }
            syncRelay()
        }
        return true
    }
    /** The relay listens for every paired device; each pair's secret is the static ECDH of the two keys. */
    private fun syncRelay() {
        val r = relay ?: return; val k = key ?: return
        val keys = synchronized(lock) { peers.filter { it.value.key != null }.map { it.key to it.value.key!! } }
        r.pairs(keys.mapNotNull { (peer, pubKey) -> val agreed = Crypto.agree(k, pubKey) ?: return@mapNotNull null; Relay.RelayPair(peer.unhex() ?: return@mapNotNull null, agreed) })
    }
    /** The phone moved to another network: the relay reconnects, and this phone announces itself at once. */
    fun networkChanged() { relay?.kick(); announceNow = true }
    fun stop() {
        relay?.stop(); relay = null
        stopping = true
        runCatching { server?.close() }; runCatching { udp?.close() }
        pairing?.complete(0); decisions.values.forEach { it.complete(0) }
        live.values.forEach { it.stop.set(true); runCatching { it.socket?.close() } }
    }

    private fun identity(): Boolean {
        store.loadIdentity()?.let { b ->
            if (b.size > 16 + 64) {
                val k = Crypto.privateKey(b.copyOfRange(16 + 64, b.size))
                if (k != null) { id = b.copyOfRange(0, 16); pub = b.copyOfRange(16, 80); key = k; return true }
            }
        }
        val pair = Crypto.newKeyPair()
        id = Crypto.random(16); pub = Crypto.xy(pair.public); key = pair.private
        return store.saveIdentity(id + pub + pair.private.encoded)
    }
    private fun loadPeers() {
        val text = store.loadPeers() ?: return
        synchronized(lock) {
            for (line in text.lines()) {
                val f = line.split('\t'); if (f.size < 3) continue
                val k = f[1].unhex(); val n = f[2].unhex()
                if (f[0].unhex()?.size != 16 || k?.size != 64 || n == null) continue
                peers.getOrPut(f[0]) { Peer() }.apply { key = k; version = Proto.VERSION; name = cleanName(String(n, Charsets.UTF_8)); phone = f.getOrNull(3) == "1"; revision = f.getOrNull(4)?.toIntOrNull() ?: 0 }
            }
        }
    }
    /** Called with [lock] held. */
    private fun savePeers() {
        val text = peers.filter { it.value.key != null }.entries.joinToString("\n") { (peer, p) ->
            listOf(peer, p.key!!.hex(), p.name.toByteArray(Charsets.UTF_8).hex(), if (p.phone) "1" else "0", p.revision.toString()).joinToString("\t")
        }
        store.savePeers(text)
    }
    private fun postPeers() = onEvent(LinkEvent.Peers(peers()))

    fun peers(): List<PeerView> = synchronized(lock) {
        peers.filter { it.value.online || it.value.key != null }.map { (peer, p) ->
            // Here directly, or through the relay (which says what the device runs).
            val r = if (p.key != null) relay?.presence(peer) else null; val internet = !p.online && r?.here == true
            PeerView(peer, p.name.ifEmpty { "A PC" }, p.key != null, p.online || internet, p.phone || r?.phone == true, p.version, if (internet) r!!.revision else p.revision, p.address, internet)
        }
    }.sortedWith(compareBy<PeerView>({ (if (it.online) 0 else 2) + (if (it.paired) 0 else 1) }, { it.name }))

    /** A device at a known address (tests, or a PC typed in by hand when broadcasts don't reach it). */
    fun addPeer(peer: String, name: String, address: String, port: Int, version: Int = Proto.VERSION, revision: Int = Proto.REVISION) {
        synchronized(lock) { peers.getOrPut(peer) { Peer() }.apply { if (key == null || this.name.isEmpty()) this.name = cleanName(name); this.address = address; this.port = port; this.version = version; this.revision = revision; online = true; seen = System.currentTimeMillis() + 86_400_000L } }
        postPeers()
    }

    // ---- discovery ----
    private fun announcement() = "${Proto.ANNOUNCE}\n${id.hex()}\n$port;${Proto.VERSION}.${Proto.REVISION}${if (options.phone) ";phone" else ""}\n$displayName"
    private fun broadcasts(): List<InetAddress> {
        val list = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.interfaceAddresses) a.broadcast?.let { if (it !in list) list += it }
            }
        }
        return list
    }
    private fun discover() {
        val socket = udp ?: return
        var last = 0L; val buffer = ByteArray(600)
        while (!stopping) {
            val now = System.currentTimeMillis()
            if (now - last >= 3000 || announceNow) {
                last = now; announceNow = false
                val data = announcement().toByteArray(Charsets.UTF_8)
                for (to in broadcasts()) runCatching { socket.send(DatagramPacket(data, data.size, to, options.udpPort)) }
                var changed = false
                synchronized(lock) { for (p in peers.values) if (p.online && now - p.seen > 12_000) { p.online = false; changed = true } }
                if (changed) postPeers()
            }
            val packet = DatagramPacket(buffer, buffer.size)
            try { socket.receive(packet) } catch (e: SocketTimeoutException) { continue } catch (e: Exception) { if (stopping) break else continue }
            heard(String(packet.data, 0, packet.length, Charsets.UTF_8), packet.address.hostAddress ?: continue)
        }
    }
    /** One announcement: "ARNAVSHARE1", the id, "port;version.revision[;phone]" and the name. */
    internal fun heard(text: String, address: String) {
        val parts = text.split('\n', limit = 4); if (parts.size != 4 || parts[0] != Proto.ANNOUNCE) return
        val peer = parts[1]; if (peer.unhex()?.size != 16 || peer == id.hex()) return
        val fields = parts[2].split(';'); val port = fields[0].toIntOrNull() ?: return; if (port !in 1..65535) return
        val versionText = fields.getOrNull(1) ?: "1"
        val version = versionText.substringBefore('.').toIntOrNull()?.coerceIn(1, 99) ?: 1
        val revision = versionText.substringAfter('.', "0").toIntOrNull()?.coerceIn(0, 99) ?: 0
        val phone = fields.drop(2).any { it == "phone" }
        val name = cleanName(parts[3])
        var changed: Boolean
        synchronized(lock) {
            val p = peers.getOrPut(peer) { Peer() }
            changed = !p.online || p.address != address || p.port != port || p.version != version || p.revision != revision || p.phone != phone || (p.key == null && p.name != name)
            val revisionChanged = p.key != null && p.revision != revision
            p.address = address; p.port = port; p.version = version; p.revision = revision; p.online = true; p.seen = System.currentTimeMillis(); p.phone = phone
            if (p.key == null || p.name.isEmpty()) p.name = name
            if (revisionChanged) savePeers()
        }
        if (changed) postPeers()
    }

    // ---- sessions ----
    private fun listen() {
        val s = server ?: return
        while (!stopping) {
            val socket = try { s.accept() } catch (e: Exception) { if (stopping) break else { Thread.sleep(100); continue } }
            thread(name = "link-in", isDaemon = true) { val c = Conn(socket); try { incoming(c) } catch (_: Exception) {} finally { c.close() } }
        }
    }
    private fun keys(ss: Session, initiator: Boolean, mine: ByteArray, theirs: ByteArray): Boolean {
        val secret = Crypto.agree(key ?: return false, ss.peerPub) ?: return false
        val nc = if (initiator) mine else theirs; val ns = if (initiator) theirs else mine
        val pc = if (initiator) pub else ss.peerPub; val ps = if (initiator) ss.peerPub else pub
        val ic = if (initiator) id else ss.peerId; val iss = if (initiator) ss.peerId else id
        val k = Crypto.sha256("arnav-share-v1", secret, nc, ns, ic, iss)
        val c = Crypto.sha256("arnav-pair-v1", pc, ps, nc, ns)
        secret.fill(0)
        ss.code = (((c[0].toLong() and 0xFF) or ((c[1].toLong() and 0xFF) shl 8) or ((c[2].toLong() and 0xFF) shl 16) or ((c[3].toLong() and 0xFF) shl 24)) % 1_000_000).toInt()
        ss.channel = Channel(k, initiator); k.fill(0); return true
    }
    /** The opener commits to its nonce (a hash) before it sees the other's, so neither side can steer the code. */
    private fun greet(c: Conn, mode: Int, ss: Session): Boolean {
        val nonce = Crypto.random(32)
        c.send(Bytes().raw(Proto.MAGIC).u8(Proto.VERSION).u8(mode).raw(id).raw(pub).raw(Crypto.sha256(nonce)).text(displayName).build())
        val reply = c.recv()
        if (reply.size < 6 || !reply.copyOfRange(0, 4).contentEquals(Proto.MAGIC)) return false
        if (reply[4].toInt() != Proto.VERSION || reply[5].toInt() == 2) { ss.outdated = true; return false }
        if (reply[5].toInt() != 0) { ss.rejected = true; return false }
        if (reply.size < 6 + 16 + 64 + 32) return false
        ss.peerId = reply.copyOfRange(6, 22); ss.peerPub = reply.copyOfRange(22, 86); val theirs = reply.copyOfRange(86, 118)
        ss.peerName = cleanName(String(reply, 118, reply.size - 118, Charsets.UTF_8))
        c.send(nonce); return keys(ss, true, nonce, theirs)
    }
    private fun welcome(c: Conn, ss: Session): Pair<Int, CompletableFuture<Int>?>? {
        val hello = c.recv()
        if (hello.size < 6 || !hello.copyOfRange(0, 4).contentEquals(Proto.MAGIC)) return null
        if (hello[4].toInt() != Proto.VERSION) { c.send(Bytes().raw(Proto.MAGIC).u8(Proto.VERSION).u8(2).build()); return null }
        if (hello.size < 6 + 16 + 64 + 32) return null
        val mode = hello[5].toInt() and 0xFF
        ss.peerId = hello.copyOfRange(6, 22); ss.peerPub = hello.copyOfRange(22, 86); val commit = hello.copyOfRange(86, 118)
        ss.peerName = cleanName(String(hello, 118, hello.size - 118, Charsets.UTF_8)); if (ss.peerId.contentEquals(id)) return null
        var claim: CompletableFuture<Int>? = null; var allowed = false
        synchronized(lock) {
            if (mode == Proto.MODE_PAIR && pairing == null) { claim = CompletableFuture(); pairing = claim; allowed = true }
            else if (mode in listOf(Proto.MODE_SEND, Proto.MODE_MUSIC, Proto.MODE_FIND, Proto.MODE_LIST, Proto.MODE_TAKE, Proto.MODE_ACTION, Proto.MODE_CLIP, Proto.MODE_CAMERA)) allowed = peers[ss.peerId.hex()]?.key?.contentEquals(ss.peerPub) == true
        }
        if (!allowed) { c.send(Bytes().raw(Proto.MAGIC).u8(Proto.VERSION).u8(1).build()); return null }
        val nonce = Crypto.random(32)
        c.send(Bytes().raw(Proto.MAGIC).u8(Proto.VERSION).u8(0).raw(id).raw(pub).raw(nonce).text(displayName).build())
        val theirs = c.recv()
        if (theirs.size != 32 || !Crypto.sha256(theirs).contentEquals(commit)) { claim?.let(::release); return null }
        if (!keys(ss, false, nonce, theirs)) { claim?.let(::release); return null }
        return mode to claim
    }
    private fun release(d: CompletableFuture<Int>) = synchronized(lock) { if (pairing === d) pairing = null }
    private fun sealed(c: Conn, ss: Session, plain: ByteArray) = runCatching { c.send(ss.channel!!.seal(plain)); true }.getOrDefault(false)
    private fun opened(c: Conn, ss: Session): ByteArray? = runCatching { ss.channel!!.open(c.recv()) }.getOrNull()

    private fun incoming(c: Conn) {
        c.timeout(15_000); val ss = Session()
        val (mode, claim) = welcome(c, ss) ?: return
        when (mode) {
            Proto.MODE_PAIR -> pairSession(c, ss, claim!!)
            Proto.MODE_MUSIC -> receiveMusic(c, ss)
            Proto.MODE_FIND -> ringed(c, ss)
            Proto.MODE_SEND -> receive(c, ss)
            // A phone keeps no Shelf of its own to show: it says so, and gives nothing.
            Proto.MODE_LIST -> sealed(c, ss, Bytes().u8(Proto.FRAME_SHELF).u8(0).u32(0).build())
            Proto.MODE_TAKE -> sealed(c, ss, Bytes().u8(Proto.FRAME_OFFER).u32(0).build())
            Proto.MODE_ACTION -> {
                val f = opened(c, ss) ?: return; val r = Reader(f); if (r.u8() != Proto.FRAME_ACTION) return
                val key = r.string(512) ?: return; val index = r.u8() ?: return; val reply = r.string(8 * 1024) ?: ""
                val status = runCatching { onAction?.invoke(key, index, reply) ?: 2 }.getOrDefault(2)
                sealed(c, ss, byteArrayOf(Proto.FRAME_ACTION_ACK.toByte(), status.toByte()))
            }
            Proto.MODE_CLIP -> {
                val f = opened(c, ss) ?: return; val r = Reader(f); if (r.u8() != Proto.FRAME_CLIP) return
                val sensitive = (r.u8() ?: 0) != 0; val text = r.string(256 * 1024) ?: return
                val done = runCatching { onClipboard?.invoke(text, sensitive) == true }.getOrDefault(false)
                sealed(c, ss, byteArrayOf(Proto.FRAME_CLIP_ACK.toByte(), if (done) 0 else 2))
            }
            Proto.MODE_CAMERA -> {
                val f = opened(c, ss) ?: return; if (f.size != 1 || f[0].toInt() != Proto.FRAME_CAMERA) return
                sealed(c, ss, byteArrayOf(Proto.FRAME_CAMERA_ACK.toByte(), 0)); onEvent(LinkEvent.PhotoRequested(ss.peerId.hex(), nameOf(ss.peerId.hex(), ss.peerName)))
            }
        }
    }

    // ---- pairing ----
    /** Both devices show the code; each person's answer goes to the other, sealed. Paired only when both said yes. */
    private fun pairSession(c: Conn, ss: Session, d: CompletableFuture<Int>, confirmed: Boolean = false) {
        val peer = ss.peerId.hex(); onEvent(LinkEvent.PairCode(peer, ss.peerName, ss.code, confirmed))
        c.timeout(90_000)
        val mine = runCatching { d.get(60, TimeUnit.SECONDS) }.getOrDefault(0)
        val theirs = if (sealed(c, ss, byteArrayOf(if (mine == 1) 1 else 0))) opened(c, ss) else null
        val talked = theirs != null && theirs.size == 1
        val both = talked && mine == 1 && theirs!![0].toInt() == 1
        if (both) synchronized(lock) { peers.getOrPut(peer) { Peer() }.apply { key = ss.peerPub; version = Proto.VERSION; if (name.isEmpty() || name == "A PC") name = ss.peerName }; savePeers() }
        if (both) { syncRelay(); relay?.stopHosting() }
        release(d)
        onEvent(LinkEvent.Paired(peer, ss.peerName, both, when { both -> "Paired"; !talked -> "The other device stopped answering"; mine != 1 -> "Not paired"; else -> "Not confirmed on the other device" }))
        postPeers()
    }
    /** Asks a PC on this network to pair (PairCode, then Paired); false when another pairing is under way. */
    fun pair(peer: String): Boolean {
        val d = synchronized(lock) { if (pairing != null) return false; CompletableFuture<Int>().also { pairing = it } }
        thread(name = "link-pair", isDaemon = true) {
            val target = synchronized(lock) { peers[peer]?.let { Peer(it.name, it.address, it.port, it.version, it.revision, it.online) } }
            val name = target?.name ?: "that PC"
            if (target?.address == null || !target.online) { release(d); onEvent(LinkEvent.Paired(peer, name, false, "Couldn't reach $name")); return@thread }
            if (target.version < Proto.VERSION) { release(d); onEvent(LinkEvent.Paired(peer, name, false, "Update Arnav Island on $name first")); return@thread }
            val c = connect(target.address!!, target.port)
            if (c == null) { release(d); onEvent(LinkEvent.Paired(peer, name, false, "Couldn't reach $name")); return@thread }
            try {
                c.timeout(15_000); val ss = Session()
                val greeted = runCatching { greet(c, Proto.MODE_PAIR, ss) }.getOrDefault(false)
                if (!greeted || ss.peerId.hex() != peer) { release(d); onEvent(LinkEvent.Paired(peer, name, false, if (ss.outdated) "Update Arnav Island on $name first" else if (ss.rejected) "$name is busy pairing" else "Pairing didn't start")) }
                else pairSession(c, ss, d)
            } finally { c.close() }
        }
        return true
    }
    fun confirmPair(yes: Boolean) { pairing?.complete(if (yes) 1 else 0) }
    fun forget(peer: String) { synchronized(lock) { peers[peer]?.key = null; savePeers() }; syncRelay(); postPeers() }

    // ---- the trackpad and keyboard (revision 3) ----
    /** A session of input frames to a PC, kept open while the trackpad shows. */
    interface InputSession { val open: Boolean; fun send(frame: ByteArray): Boolean; fun close() }
    private inner class InputChannel(private val c: Conn, private val ss: Session) : InputSession {
        @Volatile override var open = true; private set
        override fun send(frame: ByteArray): Boolean { if (!open) return false; val ok = synchronized(this) { sealed(c, ss, frame) }; if (!ok) close(); return ok }
        override fun close() { open = false; c.close() }
    }
    fun openInput(peer: String): InputSession? {
        val (t, _) = target(peer); if (t == null || t.revision < 3) return null
        val (c, ss, _) = reach(peer, Proto.MODE_INPUT, null); return c?.let { it.timeout(120_000); InputChannel(it, ss) }
    }

    // ---- pairing from anywhere ----
    /** Offers a pairing code for ten minutes (blocks up to 8 s); null when the relay can't be reached. */
    fun hostPairing(): String? = relay?.host()
    fun stopHosting() { relay?.stopHosting() }
    val hostingCode get() = relay?.hosting
    /**
     * Pairs with the device showing this code, through the relay: PairCode, then Paired, as on one network. [key], from its
     * QR code: the start of that device's key fingerprint ([keyPrint]). A device with another key is refused, and this
     * phone's yes is given at once (the device itself still asks). False when another pairing is under way.
     */
    fun pairWithCode(code: String, key: ByteArray? = null): Boolean {
        val d = synchronized(lock) { if (pairing != null) return false; CompletableFuture<Int>().also { pairing = it } }
        thread(name = "link-pair-code", isDaemon = true) {
            // Just started (a pairing link opened the app): the relay gets a few seconds to connect.
            val until = System.currentTimeMillis() + 15_000
            while (relay?.connected == false && System.currentTimeMillis() < until) Thread.sleep(200)
            // Asked up to three times: the other device may join a broker a moment after this one asked on it (a message
            // there before it arrived is lost), so a question nobody answered is asked again.
            for (attempt in 1..3) {
                val (s, why) = relay?.openCode(code) ?: (null to "Connecting over the internet is off")
                if (s == null) { release(d); onEvent(LinkEvent.Paired("", "", false, why)); return@thread }
                val c = Conn(s)
                try {
                    c.timeout(8_000); val ss = Session()
                    if (runCatching { greet(c, Proto.MODE_PAIR, ss) }.getOrDefault(false)) {
                        if (key == null) { c.timeout(20_000); pairSession(c, ss, d); return@thread }
                        if (keyPrint(ss.peerPub).contentEquals(key)) { d.complete(1); c.timeout(20_000); pairSession(c, ss, d, confirmed = true); return@thread }
                        // Not the PC whose code was scanned: someone else is answering it. Nothing more is said to them.
                        release(d); onEvent(LinkEvent.Paired("", "", false, "Another device answered that code, so nothing was paired. Make a new code on your PC")); return@thread
                    }
                    if (ss.rejected || attempt == 3) { release(d); onEvent(LinkEvent.Paired("", "", false, if (ss.rejected) "That device is busy pairing" else "No device is showing that code. Check it, or make a new one")); return@thread }
                } finally { c.close() }
            }
        }
        return true
    }

    // ---- connecting to a paired device ----
    private fun connect(address: String, port: Int): Conn? = runCatching {
        Conn(Socket().apply { connect(InetSocketAddress(address, port), 5000); tcpNoDelay = true })
    }.getOrNull()
    /** A paired device to reach: at its address on this network, or (address null) through the relay. */
    private class Target(val name: String, val address: String?, val port: Int, val key: ByteArray, val revision: Int)
    private fun target(peer: String): Pair<Target?, String> = synchronized(lock) {
        val p = peers[peer] ?: return null to "Pair with this PC first"
        val k = p.key ?: return null to "Pair with this PC first"
        if (p.version < Proto.VERSION) return null to "Update Arnav Island on ${p.name} to share with it"
        if (!p.online || p.address == null) {
            val r = relay?.presence(peer)
            if (r?.here != true) return null to if (relay != null) "${p.name} isn't reachable right now" else "${p.name} isn't on this network right now"
            return Target(p.name, null, 0, k, r.revision) to ""
        }
        Target(p.name, p.address!!, p.port, k, p.revision) to ""
    }
    /** Reached, greeted and checked against the pairing: the connection and session, or why not. */
    private fun reach(peer: String, mode: Int, transfer: Int?): Triple<Conn?, Session, String> {
        val ss = Session(); val (t, whyNot) = target(peer); if (t == null) return Triple(null, ss, whyNot)
        var why = "Couldn't reach ${t.name}"
        val c = t.address?.let { connect(it, t.port) } ?: relay?.open(peer)?.let { (s, w) -> if (s == null) { why = w; null } else Conn(s) } ?: return Triple(null, ss, why)
        if (transfer != null) { val l = live[transfer]; if (l == null || l.stop.get()) { c.close(); return Triple(null, ss, "You stopped it") }; l.socket = c.socket }
        c.timeout(15_000)
        val greeted = runCatching { greet(c, mode, ss) }.getOrDefault(false)
        if (!greeted) { c.close(); return Triple(null, ss, when { ss.outdated -> "Update Arnav Island on ${t.name} to share with it"; ss.rejected -> "${t.name} doesn't have this phone paired. Pair again."; else -> "Couldn't reach ${t.name}" }) }
        if (ss.peerId.hex() != peer || !ss.peerPub.contentEquals(t.key)) { c.close(); return Triple(null, ss, "${t.name} answered with a different key. Pair again.") }
        return Triple(c, ss, "")
    }
    private fun nameOf(peer: String, fallback: String) = synchronized(lock) { peers[peer]?.name?.takeIf { it.isNotEmpty() } ?: cleanName(fallback) }
    private fun newTransfer(): Int { val t = nextTransfer.getAndIncrement(); live[t] = Live(); return t }
    private fun stopped(t: Int) = live[t]?.stop?.get() ?: true
    private fun finish(t: Int) { live.remove(t); decisions.remove(t) }
    fun cancel(transfer: Int) { live[transfer]?.let { it.stop.set(true); decisions[transfer]?.complete(0); runCatching { it.socket?.close() } } }

    // ---- progress ----
    private inner class Progress(val transfer: Int, val peer: String, val name: String, val title: String, val total: Long, val outgoing: Boolean) {
        var done = 0L; private var shown = -1; private var at = 0L
        fun add(n: Long) {
            done += n; val percent = if (total > 0) (done * 100 / total).toInt() else 100; val now = System.currentTimeMillis()
            if (percent != shown && (now - at >= 100 || done == total)) { shown = percent; at = now; onEvent(LinkEvent.Progress(transfer, peer, name, title, done, total, outgoing)) }
        }
    }

    // ---- sending files ----
    /** Sends these items to a paired PC in one transfer; returns its id (for [cancel]). */
    /** toShelf (revision 3): photos this phone took for the PC's Shelf. */
    fun send(peer: String, items: List<Source>, title: String, folder: Boolean = false, toShelf: Boolean = false): Int {
        val transfer = newTransfer()
        thread(name = "link-send", isDaemon = true) {
            val total = items.sumOf { it.size }
            val (c, ss, why0) = reach(peer, Proto.MODE_SEND, transfer)
            var why = why0; var sent = false; val name = nameOf(peer, "")
            val flags = (if (folder) 1 else 0) or (if (toShelf && (target(peer).first?.revision ?: 0) >= 3) 2 else 0)
            if (c != null) { try { val failed = offerBatch(c, ss, items, total, flags, title, peer, name, transfer); sent = failed == null; if (failed != null) why = failed } finally { c.close() } }
            finish(transfer)
            onEvent(if (sent) LinkEvent.Sent(transfer, peer, name, title, items.size, total) else LinkEvent.Failed(transfer, peer, name, title, why.ifEmpty { "It didn't go" }, true))
        }
        return transfer
    }
    /** An offer of these files, then (answered yes) the files and the batch's end: null when all arrived, else why not. */
    private fun offerBatch(c: Conn, ss: Session, items: List<Source>, total: Long, flags: Int, title: String, peer: String, name: String, transfer: Int): String? {
        c.timeout(90_000)
        val offered = sealed(c, ss, Bytes().u8(Proto.FRAME_OFFER).u32(items.size).u64(total).u8(flags).text(title).build())
        val reply = if (offered) opened(c, ss) else null
        if (reply == null || reply.size != 1) return if (stopped(transfer)) "You stopped it" else "$name didn't answer"
        if (reply[0].toInt() == 2) return "There isn't room on $name"
        if (reply[0].toInt() != 1) return "$name declined it"
        c.timeout(30_000)
        val progress = Progress(transfer, peer, name, title, total, true)
        for (item in items) if (!streamFile(c, ss, item, progress, transfer)) return if (stopped(transfer)) "You stopped it" else "The transfer to $name didn't finish"
        c.timeout(60_000)
        val ack = if (sealed(c, ss, byteArrayOf(Proto.FRAME_BATCH_END.toByte()))) opened(c, ss) else null
        if (ack == null || ack.size != 1 || ack[0].toInt() != 1) return "The transfer to $name didn't finish"
        return null
    }
    /** One file into the sealed stream: its header, its data in chunks, then its size and SHA-256. */
    private fun streamFile(c: Conn, ss: Session, item: Source, progress: Progress, transfer: Int): Boolean {
        if (!sealed(c, ss, Bytes().u8(Proto.FRAME_HEADER).u64(item.size).text(item.rel).build())) return false
        val hash = MessageDigest.getInstance("SHA-256"); var done = 0L
        val ok = runCatching {
            item.open().use { input ->
                val buffer = ByteArray(Proto.CHUNK)
                while (done < item.size) {
                    if (stopped(transfer)) return@use false
                    val want = minOf(Proto.CHUNK.toLong(), item.size - done).toInt(); var got = 0
                    while (got < want) { val n = input.read(buffer, got, want - got); if (n < 0) break; got += n }
                    if (got != want) return@use false
                    val frame = ByteArray(1 + got); frame[0] = Proto.FRAME_DATA.toByte(); System.arraycopy(buffer, 0, frame, 1, got)
                    if (!sealed(c, ss, frame)) return@use false
                    hash.update(buffer, 0, got); done += got; progress.add(got.toLong())
                }
                true
            }
        }.getOrDefault(false)
        return ok && sealed(c, ss, Bytes().u8(Proto.FRAME_END).u64(item.size).raw(hash.digest()).build())
    }

    // ---- receiving files ----
    private fun receive(c: Conn, ss: Session) {
        val peer = ss.peerId.hex(); val from = nameOf(peer, ss.peerName)
        val offer = opened(c, ss) ?: return
        val r = Reader(offer); if (r.u8() != Proto.FRAME_OFFER) return
        val count = r.u32() ?: return; val total = r.u64() ?: return; val folder = (r.u8() ?: return) != 0
        val title = cleanName(r.rest().toString(Charsets.UTF_8))
        if (count == 0L || count > Proto.MAX_FILES || total < 0 || total > Proto.MAX_TOTAL) return
        val transfer = newTransfer(); live[transfer]?.socket = c.socket
        val d = CompletableFuture<Int>(); decisions[transfer] = d
        onEvent(LinkEvent.Offer(transfer, peer, from, title, count.toInt(), total, folder))
        c.timeout(90_000)
        var yes = decide(c, d)
        decisions.remove(transfer)
        if (yes == -1) { finish(transfer); onEvent(LinkEvent.Failed(transfer, peer, from, title, "$from stopped sending it", false)); return }
        if (yes == 1 && !inbox.room(total)) yes = 2
        val told = sealed(c, ss, byteArrayOf(yes.toByte()))
        if (!told || yes != 1) {
            val mine = stopped(transfer); finish(transfer)
            if (yes == 2) onEvent(LinkEvent.Failed(transfer, peer, from, title, "There isn't room on this phone", false))
            else if (yes == 1) onEvent(LinkEvent.Failed(transfer, peer, from, title, if (mine) "You stopped it" else "$from stopped sending it", false))
            return
        }
        takeBatch(c, ss, peer, from, title, count.toInt(), total, transfer, false)
    }
    /** Waits (up to a minute) for the person's answer; -1 if the other side closes the connection meanwhile. */
    private fun decide(c: Conn, d: CompletableFuture<Int>): Int {
        repeat(240) {
            val v = runCatching { d.get(250, TimeUnit.MILLISECONDS) }.getOrNull()
            if (v != null) return v
            if (closedByPeer(c)) { d.complete(0); return -1 }
        }
        d.complete(0); return 0
    }
    private fun closedByPeer(c: Conn): Boolean {
        val saved = c.socket.soTimeout
        return try { c.socket.soTimeout = 1; c.input.mark(1); val b = c.input.read(); if (b < 0) true else { c.input.reset(); false } }
        catch (e: SocketTimeoutException) { false } catch (e: Exception) { true } finally { runCatching { c.socket.soTimeout = saved } }
    }
    private fun takeBatch(c: Conn, ss: Session, peer: String, from: String, title: String, count: Int, total: Long, transfer: Int, taken: Boolean) {
        c.timeout(30_000)
        val progress = Progress(transfer, peer, from, title, total, false)
        val tops = HashMap<String, String>(); val shown = mutableListOf<String>(); var files = 0; var whole = false
        while (true) {
            if (stopped(transfer)) break
            val f = opened(c, ss) ?: break; if (f.isEmpty()) break
            if (f[0].toInt() == Proto.FRAME_BATCH_END) { whole = files == count && progress.done == total; break }
            if (f[0].toInt() != Proto.FRAME_HEADER || f.size < 10 || files >= count) break
            val r = Reader(f, 1); val size = r.u64()!!; val parts = safePath(r.rest().toString(Charsets.UTF_8)).toMutableList()
            if (parts.isEmpty() || size < 0 || size > Proto.MAX_FILE || progress.done + size > total) break
            if (parts.size > 1) { val top = tops.getOrPut(parts[0]) { inbox.folder(parts[0]).also { shown += it } }; parts[0] = top }
            val saved = takeFile(c, ss, size, parts, progress, transfer) ?: break
            files++; if (parts.size == 1) shown += saved
        }
        val here = stopped(transfer)
        if (whole) sealed(c, ss, byteArrayOf(1))
        finish(transfer)
        if (!whole) { onEvent(LinkEvent.Failed(transfer, peer, from, title, if (here) "You stopped it" else if (files > 0) "$files of $count files arrived from $from" else "It didn't arrive whole from $from", false)); return }
        onEvent(LinkEvent.Received(transfer, peer, from, title, count, total, shown, taken))
    }
    /** One incoming file (after its header), checked against its size and SHA-256 before it's kept. */
    private fun takeFile(c: Conn, ss: Session, size: Long, parts: List<String>, progress: Progress, transfer: Int, song: Boolean = false): String? {
        val sink = (if (song) inbox.song(parts.last(), size) else inbox.create(parts, size)) ?: return null
        val hash = MessageDigest.getInstance("SHA-256"); var got = 0L; var done = false
        try {
            while (!stopped(transfer)) {
                val f = opened(c, ss) ?: break; if (f.isEmpty()) break
                when {
                    f[0].toInt() == Proto.FRAME_DATA -> { val n = f.size - 1; if (got + n > size) break; sink.out.write(f, 1, n); hash.update(f, 1, n); got += n; progress.add(n.toLong()) }
                    f[0].toInt() == Proto.FRAME_END && f.size == 1 + 8 + 32 -> { done = Reader(f, 1).u64() == size && got == size && hash.digest().contentEquals(f.copyOfRange(9, 41)); break }
                    else -> break
                }
            }
        } catch (e: Exception) { done = false }
        if (!done) { sink.abort(); return null }
        return sink.commit()
    }

    // ---- music ----
    private fun receiveMusic(c: Conn, ss: Session) {
        val peer = ss.peerId.hex(); val from = nameOf(peer, ss.peerName)
        val offer = opened(c, ss) ?: return; val r = Reader(offer)
        if (r.u8() != Proto.FRAME_MUSIC || offer.size < 1 + 8 + 8 + 1 + 8) return
        val position = maxOf(0.0, r.f64()!!); val duration = maxOf(0.0, r.f64()!!); val playing = r.u8()!! != 0; val size = r.u64()!!
        val rest = r.rest(); val nul = rest.indexOf(0.toByte())
        val cover = if (nul >= 0 && rest.size - nul - 1 <= Proto.COVER_LIMIT) rest.copyOfRange(nul + 1, rest.size) else null
        val lines = String(rest, 0, if (nul >= 0) nul else rest.size, Charsets.UTF_8).split('\n').map { it.take(512) } + List(5) { "" }
        val music = Handoff(lines[0], lines[1], lines[2], lines[3], if (size > 0) safeName(lines[4]) else "", position, duration, playing, size, cover?.takeIf { it.isNotEmpty() })
        if (music.title.isEmpty() || size < 0 || size > (2L shl 30)) return
        val transfer = newTransfer(); live[transfer]?.socket = c.socket
        val d = CompletableFuture<Int>(); decisions[transfer] = d
        onEvent(LinkEvent.Music(transfer, peer, from, music))
        c.timeout(90_000)
        var code = decide(c, d); decisions.remove(transfer)
        if (code == -1) { finish(transfer); onEvent(LinkEvent.Failed(transfer, peer, from, music.title, "$from took the music back", false)); return }
        if (code !in 0..2 || (code == 2 && size == 0L)) code = if (code == 2) 1 else 0
        if (code == 2 && !inbox.room(size)) code = 1
        if (!sealed(c, ss, byteArrayOf(code.toByte())) || code != 2) { finish(transfer); return }
        c.timeout(30_000)
        val progress = Progress(transfer, peer, from, music.title, size, false)
        val f = opened(c, ss)
        var saved: String? = null
        if (f != null && f.size >= 10 && f[0].toInt() == Proto.FRAME_HEADER && Reader(f, 1).u64() == size) saved = takeFile(c, ss, size, listOf(music.fileName.ifEmpty { "song" }), progress, transfer, song = true)
        val end = if (saved != null) opened(c, ss) else null
        val whole = saved != null && end != null && end.size == 1 && end[0].toInt() == Proto.FRAME_BATCH_END
        if (whole) sealed(c, ss, byteArrayOf(1))
        finish(transfer)
        if (!whole) { onEvent(LinkEvent.Failed(transfer, peer, from, music.title, "The song didn't arrive whole from $from", false)); return }
        onEvent(LinkEvent.MusicFile(transfer, peer, from, music, saved!!))
    }
    /** Answers music offered to this phone: 0 no, 1 yes, 2 yes and send the song's file. */
    fun answerMusic(transfer: Int, code: Int) { decisions[transfer]?.complete(code.coerceIn(0, 2)) }
    /** Answers files offered to this phone. */
    fun answer(transfer: Int, accept: Boolean) { decisions[transfer]?.complete(if (accept) 1 else 0) }

    /** Offers what plays on the phone to a paired PC (no file: the PC finds the song itself). The PC's answer, or null. */
    fun handoff(peer: String, music: Handoff): Int? {
        val (c, ss, _) = reach(peer, Proto.MODE_MUSIC, null); c ?: return null
        try {
            c.timeout(90_000)
            val text = listOf(music.title, music.artist, music.album, music.app, "").joinToString("\n") { it.take(512).replace('\n', ' ').replace('\r', ' ').replace('\u0000', ' ') }
            val b = Bytes().u8(Proto.FRAME_MUSIC).f64(music.position).f64(music.duration).u8(if (music.playing) 1 else 0).u64(0).text(text)
            if (music.cover != null && music.cover.isNotEmpty() && music.cover.size <= Proto.COVER_LIMIT) b.u8(0).raw(music.cover)
            if (!sealed(c, ss, b.build())) return null
            val reply = opened(c, ss) ?: return null
            return if (reply.size == 1) reply[0].toInt() else null
        } finally { c.close() }
    }

    // ---- a PC's Shelf ----
    fun shelf(peer: String): ShelfList {
        val (t, why0) = target(peer); if (t == null) return ShelfList(false, emptyList(), why0)
        if (t.revision < 1) return ShelfList(false, emptyList(), "Update Arnav Island on ${t.name} to see its Shelf")
        val (c, ss, why) = reach(peer, Proto.MODE_LIST, null); c ?: return ShelfList(false, emptyList(), why)
        try {
            val list = opened(c, ss) ?: return ShelfList(false, emptyList(), "${t.name} didn't answer")
            val r = Reader(list); if (r.u8() != Proto.FRAME_SHELF) return ShelfList(false, emptyList(), "${t.name} didn't answer")
            val shared = (r.u8() ?: 0) != 0; val n = minOf(r.u32() ?: 0, Proto.SHELF_MAX.toLong()).toInt()
            val items = mutableListOf<ShelfItem>()
            repeat(n) {
                val size = r.u64() ?: return@repeat; val folder = (r.u8() ?: return@repeat) != 0; val len = r.u8() ?: return@repeat
                val name = r.bytes(len)?.toString(Charsets.UTF_8) ?: return@repeat
                val pl = r.u32() ?: return@repeat; if (pl > Proto.PREVIEW_LIMIT) return@repeat
                val preview = r.bytes(pl.toInt()) ?: return@repeat
                items += ShelfItem(name, size, folder, preview.takeIf { it.isNotEmpty() })
            }
            return ShelfList(shared, items)
        } finally { c.close() }
    }
    /** Takes a PC's Shelf item (by its place in the list and its name) onto this phone. */
    fun take(peer: String, index: Int, name: String): Int {
        val transfer = newTransfer()
        thread(name = "link-take", isDaemon = true) {
            val (t, why0) = target(peer); var why = why0; var taken = false; val pcName = t?.name ?: nameOf(peer, "")
            if (t != null && t.revision < 1) why = "Update Arnav Island on ${t.name} to take from its Shelf"
            else if (t != null) {
                val (c, ss, w) = reach(peer, Proto.MODE_TAKE, transfer); why = w
                if (c != null) try {
                    c.timeout(90_000)
                    val offer = if (sealed(c, ss, Bytes().u8(Proto.FRAME_TAKE).u32(index).text(name).build())) opened(c, ss) else null
                    val r = offer?.let { Reader(it) }
                    if (offer == null || offer.size < 5 || r!!.u8() != Proto.FRAME_OFFER) why = if (stopped(transfer)) "You stopped it" else "$pcName didn't answer"
                    else {
                        val count = r.u32() ?: 0
                        if (offer.size < 14 || count == 0L) why = "It's no longer on $pcName’s Shelf"
                        else {
                            val total = r.u64()!!; r.u8(); val title = cleanName(r.rest().toString(Charsets.UTF_8))
                            if (count > Proto.MAX_FILES || total > Proto.MAX_TOTAL) why = "That is too much to take at once"
                            else {
                                val room = inbox.room(total)
                                if (!sealed(c, ss, byteArrayOf(if (room) 1 else 2))) why = "$pcName stopped answering"
                                else if (!room) why = "There isn't room on this phone"
                                else { takeBatch(c, ss, peer, pcName, title, count.toInt(), total, transfer, true); taken = true }
                            }
                        }
                    }
                } finally { c.close() }
            }
            if (!taken) { finish(transfer); onEvent(LinkEvent.Failed(transfer, peer, pcName, name, why.ifEmpty { "It didn't come" }, false)) }
        }
        return transfer
    }

    // ---- revision 2: remote, notices, find my phone ----
    /** One remote command to a paired PC: its answer, or null when it couldn't be asked (with why in [lastRemoteError]). */
    @Volatile var lastRemoteError = ""; private set
    fun remote(peer: String, command: Int, payload: ByteArray = ByteArray(0)): RemoteReply? {
        val (t, why0) = target(peer); if (t == null) { lastRemoteError = why0; return null }
        if (t.revision < 2) { lastRemoteError = "Update Arnav Island on ${t.name} to control it from your phone"; return null }
        val (c, ss, why) = reach(peer, Proto.MODE_REMOTE, null); if (c == null) { lastRemoteError = why; return null }
        try {
            c.timeout(10_000)
            if (!sealed(c, ss, Bytes().u8(Proto.FRAME_REQUEST).u8(command).raw(payload).build())) return null
            val reply = opened(c, ss) ?: run { lastRemoteError = "${t.name} didn't answer"; return null }
            val r = Reader(reply); if (r.u8() != Proto.FRAME_REPLY) return null
            val status = r.u8() ?: return null
            return RemoteReply(status, r.rest())
        } finally { c.close() }
    }
    /** The PC's status for the remote. [haveCover]: the hash of the cover this phone already shows (so it isn't sent again). */
    fun status(peer: String, haveCover: ByteArray?, previous: PcStatus?): PcStatus? {
        val reply = remote(peer, Proto.CMD_STATUS, haveCover?.takeIf { it.size == 32 } ?: ByteArray(32)) ?: return null
        if (!reply.ok) { lastRemoteError = if (reply.status == Proto.NOT_ALLOWED) "Remote control is off on that PC" else "That PC couldn't answer"; return null }
        return parseStatus(reply.payload, previous)
    }
    fun notice(peer: String, frame: ByteArray): Boolean {
        val (t, _) = target(peer); if (t == null || t.revision < 2) return false
        val (c, ss, _) = reach(peer, Proto.MODE_NOTICE, null); c ?: return false
        try { c.timeout(10_000); if (!sealed(c, ss, frame)) return false; val a = opened(c, ss); return a != null && a.isNotEmpty() && a[0].toInt() == Proto.FRAME_NOTICE_ACK }
        finally { c.close() }
    }
    /** A PC rang this phone (find my phone). */
    private fun ringed(c: Conn, ss: Session) {
        val f = opened(c, ss) ?: return
        if (f.isEmpty() || f[0].toInt() != Proto.FRAME_RING) return
        sealed(c, ss, byteArrayOf(Proto.FRAME_RING_ACK.toByte()))
        onEvent(LinkEvent.Ring(ss.peerId.hex(), nameOf(ss.peerId.hex(), ss.peerName)))
    }
    /** Rings a paired device (used between phones, and in tests). */
    fun ring(peer: String): Boolean {
        val (t, _) = target(peer); if (t == null || t.revision < 2) return false
        val (c, ss, _) = reach(peer, Proto.MODE_FIND, null); c ?: return false
        try { c.timeout(10_000); if (!sealed(c, ss, byteArrayOf(Proto.FRAME_RING.toByte()))) return false; val a = opened(c, ss); return a != null && a.size == 1 && a[0].toInt() == Proto.FRAME_RING_ACK }
        finally { c.close() }
    }

    companion object {
        /**
         * The status payload: u16 flags, f64 position, f64 duration, u8 volume, i8 battery, u8 cpu (255 unknown), then
         * the cover (u8 0 none / 1 included with 32-byte hash and u32 length / 2 unchanged), then u32 length and UTF-8
         * lines: title, artist, app, the PC's name, a weather line.
         */
        fun parseStatus(p: ByteArray, previous: PcStatus?): PcStatus? {
            val r = Reader(p)
            val flags = r.u16() ?: return null; val position = r.f64() ?: return null; val duration = r.f64() ?: return null
            val volume = r.u8() ?: return null; val battery = (r.u8() ?: return null).toByte().toInt(); val cpu = r.u8() ?: return null
            var cover: ByteArray? = null; var hash: ByteArray? = null
            when (r.u8() ?: return null) {
                1 -> { hash = r.bytes(32) ?: return null; cover = r.blob(Proto.COVER_LIMIT) ?: return null }
                2 -> { cover = previous?.cover; hash = previous?.coverHash }
            }
            val lines = (r.string(64 * 1024) ?: return null).split('\n') + List(5) { "" }
            fun bit(i: Int) = flags and (1 shl i) != 0
            return PcStatus(bit(0), bit(1), bit(2), bit(3), bit(4), bit(7), bit(5), bit(6), bit(8), maxOf(0.0, position), maxOf(0.0, duration), volume.coerceIn(0, 100), battery,
                if (cpu == 255) -1 else cpu, lines[0], lines[1], lines[2], lines[3], lines[4], cover, hash, clipboard = bit(9))
        }
        fun statusFrame(battery: Int, charging: Boolean) = Bytes().u8(Proto.FRAME_NOTICE).u8(Proto.NOTICE_STATUS).u8(battery).u8(if (charging) 1 else 0).build()
        /** A device's key fingerprint as its pairing QR code carries it: the first ten bytes of the SHA-256 of its public key. */
        fun keyPrint(pub: ByteArray): ByteArray = Crypto.sha256(pub).copyOf(10)
        /**
         * A notification for the island: app, title and text (each trimmed), the phone's name, an optional small PNG icon;
         * with revision 3 its key and up to three actions (a title each, and whether it takes a reply).
         */
        fun notificationFrame(app: String, title: String, text: String, phone: String, urgent: Boolean, icon: ByteArray?, key: String = "", actions: List<Pair<String, Boolean>> = emptyList()): ByteArray {
            val lines = listOf(app.take(80), title.take(200), text.take(600), phone.take(64)).joinToString("\n") { it.replace('\n', ' ') }
            val b = Bytes().u8(Proto.FRAME_NOTICE).u8(Proto.NOTICE_NOTIFICATION).u8(if (urgent) 1 else 0).string(lines).blob(icon?.takeIf { it.size <= 24 * 1024 } ?: ByteArray(0))
            b.string(key.take(200)).u8(minOf(actions.size, 3))
            actions.take(3).forEach { (label, reply) -> val t = label.take(24).toByteArray(Charsets.UTF_8); b.u8(if (reply) 1 else 0).u8(t.size).raw(t) }
            return b.build()
        }
        fun parseLyrics(p: ByteArray): Lyrics? {
            val r = Reader(p); val state = r.u8() ?: return null; val key = r.string(4096) ?: return null; val n = r.u32() ?: return null; if (n > 400) return null
            val lines = ArrayList<LyricsLine>()
            repeat(n.toInt()) {
                val time = r.f64() ?: return null; val text = r.string(4096) ?: return null; val wn = r.u8() ?: return null
                val words = ArrayList<Pair<Double, Int>>(); repeat(wn) { val wt = r.f64() ?: return null; val at = r.u16() ?: return null; words += wt to at }
                lines += LyricsLine(time, text, words)
            }
            return Lyrics(state, key, lines)
        }
        /** The phone's readings for the island, one "name<TAB>value" a line. */
        fun detailsFrame(details: List<Pair<String, String>>): ByteArray =
            Bytes().u8(Proto.FRAME_NOTICE).u8(Proto.NOTICE_DETAILS).string(details.joinToString("\n") { (k, v) -> k.replace('\t', ' ').replace('\n', ' ') + "\t" + v.replace('\t', ' ').replace('\n', ' ') }.take(12_000)).build()
        /** A notification the phone no longer shows. */
        fun goneFrame(key: String) = Bytes().u8(Proto.FRAME_NOTICE).u8(Proto.NOTICE_GONE).string(key.take(200)).build()
        // Trackpad and keyboard frames.
        fun moveFrame(dx: Int, dy: Int) = Bytes().u8(Proto.INPUT_MOVE).u16(dx.coerceIn(-32768, 32767) and 0xFFFF).u16(dy.coerceIn(-32768, 32767) and 0xFFFF).build()
        /** button: 0 left, 1 right, 2 middle; state: 0 up, 1 down, 2 a click. */
        fun buttonFrame(button: Int, state: Int) = Bytes().u8(Proto.INPUT_BUTTON).u8(button).u8(state).build()
        /** In wheel units (120 a notch). */
        fun scrollFrame(vertical: Int, horizontal: Int) = Bytes().u8(Proto.INPUT_SCROLL).u16(vertical.coerceIn(-32768, 32767) and 0xFFFF).u16(horizontal.coerceIn(-32768, 32767) and 0xFFFF).build()
        fun textFrame(text: String) = Bytes().u8(Proto.INPUT_TEXT).text(text.take(2000)).build()
        /** A Windows virtual-key code, pressed and let go (state 2), or down (1) or up (0). */
        fun keyFrame(vk: Int, state: Int = 2) = Bytes().u8(Proto.INPUT_KEY).u16(vk).u8(state).build()
    }
}
