package io.github.arnavdugad.arnavisland

import io.github.arnavdugad.arnavisland.link.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A store in memory. */
class MemoryStore : LinkStore {
    var identity: ByteArray? = null; var peers: String? = null
    override fun loadIdentity() = identity
    override fun saveIdentity(bytes: ByteArray): Boolean { identity = bytes; return true }
    override fun loadPeers() = peers
    override fun savePeers(text: String) { peers = text }
}

/** Received files in a folder, kept under their names only once they arrived whole. */
class FolderInbox(val dir: File) : Inbox {
    override fun folder(name: String): String { var n = name; var i = 2; while (File(dir, n).exists()) n = "$name (${i++})"; File(dir, n).mkdirs(); return n }
    override fun create(parts: List<String>, size: Long): Sink? {
        val target = File(dir, parts.joinToString(File.separator)); target.parentFile?.mkdirs()
        val part = File(target.path + ".arnavpart"); val out = FileOutputStream(part)
        return object : Sink {
            override val out: OutputStream = out
            override fun commit(): String? { out.close(); var t = target; var i = 2; while (t.exists()) t = File(target.parentFile, "${target.nameWithoutExtension} (${i++}).${target.extension}"); return if (part.renameTo(t)) t.path else null }
            override fun abort() { out.close(); part.delete() }
        }
    }
    override fun song(name: String, size: Long) = create(listOf("songs", name), size)
    override fun room(bytes: Long) = true
}

class LinkTest {
    private fun temp(): File = Files.createTempDirectory("arnav-link").toFile()

    private class Node(name: String, val dir: File) {
        val events = LinkedBlockingQueue<LinkEvent>()
        val link = Link(MemoryStore(), name, FolderInbox(dir), { events.put(it) }, Link.Options(tcpPort = 0, discovery = false, loopback = true))
        inline fun <reified T : LinkEvent> await(seconds: Long = 20, test: (T) -> Boolean = { true }): T {
            val end = System.currentTimeMillis() + seconds * 1000
            while (System.currentTimeMillis() < end) { val e = events.poll(200, TimeUnit.MILLISECONDS) ?: continue; if (e is T && test(e)) return e }
            fail("no ${T::class.simpleName} within $seconds s"); throw IllegalStateException()
        }
    }

    @Test fun crypto_matches_itself() {
        val a = Crypto.newKeyPair(); val b = Crypto.newKeyPair()
        val s1 = Crypto.agree(a.private, Crypto.xy(b.public))!!; val s2 = Crypto.agree(b.private, Crypto.xy(a.public))!!
        assertArrayEquals(s1, s2); assertEquals(64, Crypto.xy(a.public).size)
        assertNotNull(Crypto.publicKey(Crypto.xy(a.public)))
        val off = Crypto.xy(a.public).also { it[63] = (it[63] + 1).toByte() }
        assertNull("a point off the curve is refused", Crypto.publicKey(off))
        val k = Crypto.random(32); val x = Channel(k, true); val y = Channel(k, false)
        val sealed = x.seal("hello".toByteArray()); assertEquals("hello", String(y.open(sealed)!!))
        assertNull("a frame opens once only (the counter moves on)", y.open(sealed))
    }

    @Test fun names_and_paths_are_made_safe() {
        assertEquals(listOf("Photos", "2024", "a.jpg"), safePath("Photos/2024/a.jpg"))
        assertEquals(listOf("a.jpg"), safePath("../../a.jpg"))
        assertEquals("_CON.txt", safeName("CON.txt"))
        assertEquals("a_b.txt", safeName("a:b.txt"))
        assertEquals("file", safeName("..."))
        assertEquals("A PC", cleanName("\u0001"))
    }

    @Test fun announcements_are_read_as_windows_writes_them() {
        val n = Node("Phone", temp()); assertTrue(n.link.start())
        n.link.heard("ARNAVSHARE1\n" + "11".repeat(16) + "\n47820;2.1\nDesk PC", "10.0.0.5")
        n.link.heard("ARNAVSHARE1\n" + "22".repeat(16) + "\n47820;2.2;phone\nGalaxy", "10.0.0.6")
        n.link.heard("ARNAVSHARE1\n" + "33".repeat(16) + "\n47820\nOld PC", "10.0.0.7")
        n.link.heard("ARNAVSHARE1\nnot-an-id\n47820;2\nX", "10.0.0.8")
        val peers = n.link.peers().associateBy { it.name }
        assertEquals(3, peers.size)
        assertEquals(1, peers["Desk PC"]!!.revision); assertFalse(peers["Desk PC"]!!.phone); assertFalse(peers["Desk PC"]!!.remote)
        assertTrue(peers["Galaxy"]!!.phone); assertTrue(peers["Galaxy"]!!.remote)
        assertEquals(1, peers["Old PC"]!!.version)
        n.link.stop()
    }

    @Test fun two_phones_pair_share_and_ring() {
        val a = Node("Phone A", temp()); val b = Node("Phone B", temp())
        assertTrue(a.link.start()); assertTrue(b.link.start())
        a.link.addPeer(b.link.identity, "Phone B", "127.0.0.1", b.link.port); b.link.addPeer(a.link.identity, "Phone A", "127.0.0.1", a.link.port)
        a.link.pair(b.link.identity)
        val codeB = b.await<LinkEvent.PairCode>(); val codeA = a.await<LinkEvent.PairCode>()
        assertEquals("both show the same code", codeA.code, codeB.code)
        a.link.confirmPair(true); b.link.confirmPair(true)
        assertTrue(a.await<LinkEvent.Paired>().ok); assertTrue(b.await<LinkEvent.Paired>().ok)

        // A file and a folder in one transfer.
        val payload = ByteArray(700_000) { (it * 31).toByte() }
        a.link.send(b.link.identity, listOf(Source("hello.bin", payload.size.toLong()) { ByteArrayInputStream(payload) }, Source("Trip/day1/note.txt", 5) { ByteArrayInputStream("hello".toByteArray()) }), "hello.bin and 1 more")
        val offer = b.await<LinkEvent.Offer>(); assertEquals(2, offer.count); assertEquals(700_005L, offer.size)
        b.link.answer(offer.transfer, true)
        val got = b.await<LinkEvent.Received>(); a.await<LinkEvent.Sent>()
        assertArrayEquals(payload, File(b.dir, "hello.bin").readBytes())
        assertEquals("hello", File(b.dir, "Trip/day1/note.txt").readText())
        assertEquals(2, got.shown.size)

        // Declined.
        a.link.send(b.link.identity, listOf(Source("no.txt", 2) { ByteArrayInputStream("no".toByteArray()) }), "no.txt")
        b.link.answer(b.await<LinkEvent.Offer>().transfer, false)
        assertTrue(a.await<LinkEvent.Failed>().detail.contains("declined"))

        // Find my phone.
        assertTrue(a.link.ring(b.link.identity)); b.await<LinkEvent.Ring>()

        // An unpaired device is refused.
        val c = Node("Stranger", temp()); assertTrue(c.link.start()); c.link.addPeer(b.link.identity, "Phone B", "127.0.0.1", b.link.port)
        c.link.send(b.link.identity, listOf(Source("x", 1) { ByteArrayInputStream(byteArrayOf(1)) }), "x")
        assertTrue(c.await<LinkEvent.Failed>().detail.isNotEmpty())
        a.link.stop(); b.link.stop(); c.link.stop()
    }
}
