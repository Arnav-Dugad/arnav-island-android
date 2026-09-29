package io.github.arnavdugad.arnavisland

import io.github.arnavdugad.arnavisland.link.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The phone against the real Windows ShareService (the island's `share_peer` test program), over loopback: pairing,
 * files both ways, the Shelf, music both ways, and revision 2's remote, notices and find-my-phone. Runs when
 * ARNAV_SHARE_PEER names share_peer.exe (built with the Windows island); skipped elsewhere.
 */
class InteropTest {
    private val events = LinkedBlockingQueue<LinkEvent>()
    private val lines = LinkedBlockingQueue<String>()

    private inline fun <reified T : LinkEvent> await(seconds: Long = 25, test: (T) -> Boolean = { true }): T {
        val end = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < end) { val e = events.poll(200, TimeUnit.MILLISECONDS) ?: continue; if (e is T && test(e)) return e }
        fail("no ${T::class.simpleName} within $seconds s"); throw IllegalStateException()
    }
    private fun line(prefix: String, seconds: Long = 25): String {
        val end = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < end) { val l = lines.poll(200, TimeUnit.MILLISECONDS) ?: continue; if (l.startsWith(prefix)) return l.removePrefix(prefix).trim() }
        fail("the PC never said $prefix"); throw IllegalStateException()
    }

    @Test fun phone_and_windows_island_speak_the_same_protocol() {
        val exe = System.getenv("ARNAV_SHARE_PEER"); assumeTrue("ARNAV_SHARE_PEER not set", exe != null && File(exe).exists())
        val work = Files.createTempDirectory("arnav-interop").toFile(); val phoneDir = File(work, "phone").apply { mkdirs() }
        val pcPort = 47930
        val process = ProcessBuilder(exe, pcPort.toString(), File(work, "pc").path).redirectErrorStream(true).start()
        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        thread(isDaemon = true) { while (true) { val l = reader.readLine() ?: break; println("PC: $l"); lines.put(l) } }
        val pc = process.outputStream.bufferedWriter(Charsets.UTF_8)
        fun tell(cmd: String) { pc.write(cmd); pc.newLine(); pc.flush() }
        val phone = Link(MemoryStore(), "Test Phone", FolderInbox(phoneDir), { events.put(it) }, Link.Options(tcpPort = 0, discovery = false, loopback = true))
        try {
            assertTrue(phone.start())
            val ready = line("READY ").split(' '); val pcId = ready[0]
            phone.addPeer(pcId, "Interop PC", "127.0.0.1", pcPort, 2, 3)
            tell("peer ${phone.identity} ${phone.port}"); line("OK peer")

            // Pairing: the phone and Windows show the same code.
            phone.pair(pcId)
            val code = await<LinkEvent.PairCode>().code
            assertEquals("the same six digits on both", line("PAIRCODE ").toInt(), code)
            phone.confirmPair(true)
            assertTrue(await<LinkEvent.Paired>().ok); line("PAIRED ok")

            // Phone to PC: two files in one transfer, one in a folder.
            val big = ByteArray(1_300_000) { (it * 7 + 3).toByte() }
            phone.send(pcId, listOf(Source("photo.jpg", big.size.toLong()) { ByteArrayInputStream(big) }, Source("Trip/notes.txt", 3) { ByteArrayInputStream("abc".toByteArray()) }), "photo.jpg and 1 more")
            line("OFFER 2 1300003"); line("RECEIVED "); await<LinkEvent.Sent>()
            assertArrayEquals(big, File(work, "pc/dl/photo.jpg").readBytes())
            assertEquals("abc", File(work, "pc/dl/Trip/notes.txt").readText())

            // PC to phone.
            val fromPc = File(work, "fromPc.bin").apply { writeBytes(ByteArray(400_000) { (it % 251).toByte() }) }
            tell("send ${phone.identity} ${fromPc.path}")
            val offer = await<LinkEvent.Offer>(); assertEquals(1, offer.count); phone.answer(offer.transfer, true)
            await<LinkEvent.Received>(); line("SENT ")
            assertArrayEquals(fromPc.readBytes(), File(phoneDir, "fromPc.bin").readBytes())

            // The PC's Shelf: listed, then taken.
            val shelfFile = File(work, "shelf-note.txt").apply { writeText("on the shelf") }
            tell("shelf ${shelfFile.path}"); line("OK shelf")
            val shelf = phone.shelf(pcId); assertTrue(shelf.error ?: "", shelf.shared); assertEquals("shelf-note.txt", shelf.items.single().name); assertEquals(12L, shelf.items.single().size)
            phone.take(pcId, 0, "shelf-note.txt"); val taken = await<LinkEvent.Received>(); assertTrue(taken.taken); line("SHELFTAKEN ")
            assertEquals("on the shelf", File(phoneDir, "shelf-note.txt").readText())

            // Revision 2: the remote.
            val status = phone.status(pcId, null, null); assertNotNull(phone.lastRemoteError, status); status!!
            assertEquals("Interop Song", status.title); assertEquals("Test Artist", status.artist); assertEquals("Spotify", status.app); assertEquals("Interop PC", status.pcName); assertEquals("14° Rain", status.weather); assertTrue("the island says its universal clipboard is on", status.clipboard)
            assertTrue(status.available && status.playing && status.canNext && status.canSeek && status.batteryPresent); assertEquals(42, status.volume); assertEquals(77, status.battery); assertEquals(13, status.cpu)
            assertEquals(61.5, status.position, 1e-9); assertEquals(200.0, status.duration, 1e-9); assertEquals(1500, status.cover!!.size)
            val again = phone.status(pcId, status.coverHash, status)!!; assertSame("an unchanged cover isn't sent again", status.cover, again.cover)
            assertTrue(phone.remote(pcId, Proto.CMD_MEDIA, byteArrayOf(2))!!.ok); line("REMOTE media 2")
            assertTrue(phone.remote(pcId, Proto.CMD_VOLUME, byteArrayOf(65))!!.ok); line("REMOTE volume 65")
            val clip = phone.remote(pcId, Proto.CMD_CLIP_GET)!!; assertTrue(clip.ok); assertEquals("from pc ✓", Reader(clip.payload).string())
            assertTrue(phone.remote(pcId, Proto.CMD_CLIP_SET, "hello pc".toByteArray())!!.ok); assertEquals("hello pc", line("CLIP "))
            assertEquals(Proto.NOT_ALLOWED, phone.remote(pcId, Proto.CMD_LOCK)!!.status)

            // Revision 2: notices.
            assertTrue(phone.notice(pcId, Link.statusFrame(81, true))); assertEquals("81 1", line("PHONESTATUS "))
            assertTrue(phone.notice(pcId, Link.notificationFrame("Messages", "Mum", "Dinner at 8?", "Test Phone", true, ByteArray(300) { 1 })))
            assertEquals("Messages|Mum|Dinner at 8?|300|1", line("PHONENOTICE "))

            // Revision 2: find my phone.
            tell("ring ${phone.identity}"); await<LinkEvent.Ring>(); line("RANG")

            // Music, PC to phone, with the song's file.
            val song = File(work, "song.mp3").apply { writeBytes(ByteArray(90_000) { 9 }) }
            tell("handoff ${phone.identity} ${song.path}")
            val music = await<LinkEvent.Music>(); assertEquals("PC Song", music.music.title); assertEquals(12.5, music.music.position, 1e-9); assertEquals(700, music.music.cover!!.size); assertEquals("song.mp3", music.music.fileName)
            phone.answerMusic(music.transfer, 2); val file = await<LinkEvent.MusicFile>(); assertEquals(90_000L, File(file.shown).length()); line("HANDOFFANSWER 2")

            // Music, phone to PC.
            assertEquals(1, phone.handoff(pcId, Handoff("Phone Song", "Phone Artist", position = 33.0, duration = 120.0)))
            assertTrue(line("HANDOFF ").startsWith("Phone Song|33"))

            // Revision 3: find this PC, and the song's lyrics with word times.
            assertTrue(phone.remote(pcId, Proto.CMD_RING_PC)!!.ok); line("RINGPC")
            val lyrics = Link.parseLyrics(phone.remote(pcId, Proto.CMD_LYRICS)!!.payload)!!
            assertEquals(2, lyrics.state); assertEquals("Interop Song\tTest Artist", lyrics.key); assertEquals(2, lyrics.lines.size)
            assertEquals("Glass on the water", lyrics.lines[0].text); assertEquals(12.5, lyrics.lines[0].time, 1e-9); assertEquals(listOf(12.5 to 0, 13.1 to 6, 13.6 to 9), lyrics.lines[0].words)
            // A notification with its key and actions, the phone's details, and a notification gone.
            assertTrue(phone.notice(pcId, Link.notificationFrame("Messages", "Mum", "Dinner?", "Test Phone", false, null, "k1", listOf("Reply" to true, "Mark read" to false))))
            assertEquals("Messages|Mum|Dinner?|0|0|k1|Reply*|Mark read", line("PHONENOTICE "))
            assertTrue(phone.notice(pcId, Link.detailsFrame(listOf("Battery" to "81%", "Storage" to "40 GB free")))); assertEquals("Battery\t81%", line("DETAILS "))
            assertTrue(phone.notice(pcId, Link.goneFrame("k1"))); assertEquals("k1", line("GONE "))
            // The PC runs an action with a reply, sets the clipboard, asks for a photo.
            val acted = LinkedBlockingQueue<String>(); phone.onAction = { key, index, reply -> acted.put("$key|$index|$reply"); 0 }
            tell("action ${phone.identity} k1 0 See you at 8"); assertEquals("k1|0|See you at 8", acted.poll(20, TimeUnit.SECONDS))
            val clipped = LinkedBlockingQueue<String>(); phone.onClipboard = { text, _ -> clipped.put(text); true }
            tell("clip ${phone.identity} from the PC"); assertEquals("from the PC", clipped.poll(20, TimeUnit.SECONDS))
            tell("photo ${phone.identity}"); await<LinkEvent.PhotoRequested>()
            // The trackpad and keyboard.
            val input = phone.openInput(pcId)!!
            assertTrue(input.send(Link.moveFrame(5, -3))); assertEquals("600500fdff", line("INPUT "))
            assertTrue(input.send(Link.textFrame("hi"))); assertEquals("636869", line("INPUT "))
            input.close()
            // Photos for the Shelf.
            phone.send(pcId, listOf(Source("IMG_1.jpg", 5) { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)) }), "IMG_1.jpg", toShelf = true)
            line("OFFER 1 5 shelf"); await<LinkEvent.Sent>()
        } finally { runCatching { tell("quit") }; phone.stop(); process.waitFor(5, TimeUnit.SECONDS); process.destroy() }
    }

    /**
     * The same two engines on no common network: neither is told where the other is, and discovery is off. They pair with a
     * code through the public relay, then everything goes through it. Runs when ARNAV_RELAY_TEST is set (it needs the internet).
     */
    @Test fun phone_and_windows_island_meet_over_the_internet() {
        val exe = System.getenv("ARNAV_SHARE_PEER"); assumeTrue("ARNAV_SHARE_PEER not set", exe != null && File(exe).exists())
        assumeTrue("ARNAV_RELAY_TEST not set", System.getenv("ARNAV_RELAY_TEST") != null)
        val work = Files.createTempDirectory("arnav-relay").toFile(); val phoneDir = File(work, "phone").apply { mkdirs() }
        val process = ProcessBuilder(exe, "47931", File(work, "pc").path, "--relay").redirectErrorStream(true).start()
        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        thread(isDaemon = true) { while (true) { val l = reader.readLine() ?: break; if (!l.startsWith("REMOTE status")) println("PC: $l"); lines.put(l) } }
        val pc = process.outputStream.bufferedWriter(Charsets.UTF_8)
        fun tell(cmd: String) { pc.write(cmd); pc.newLine(); pc.flush() }
        val phone = Link(MemoryStore(), "Test Phone", FolderInbox(phoneDir), { events.put(it) }, Link.Options(tcpPort = 0, discovery = false, loopback = true, relay = true))
        try {
            assertTrue(phone.start())
            val pcId = line("READY ").split(' ')[0]
            val connected = System.currentTimeMillis() + 30_000; while (!phone.internet && System.currentTimeMillis() < connected) Thread.sleep(200)
            assertTrue("the phone reached the relay", phone.internet)
            Thread.sleep(3_000)
            tell("host"); val code = line("CODE ", 40); assertTrue(code, Relay.code(code) != null)
            phone.pairWithCode(code)
            val shown = await<LinkEvent.PairCode>(40).code
            assertEquals("the same six digits on both", line("PAIRCODE ").toInt(), shown)
            phone.confirmPair(true); assertTrue(await<LinkEvent.Paired>(40).ok); line("PAIRED ok")
            // Each sees the other through the relay.
            val seen = System.currentTimeMillis() + 40_000
            while (System.currentTimeMillis() < seen && phone.peers().none { it.id == pcId && it.online && it.internet }) Thread.sleep(200)
            val view = phone.peers().first { it.id == pcId }; assertTrue(view.online && view.internet); assertEquals(3, view.revision)
            line("PRESENCE ${phone.identity} 1 1 3 1", 40)

            // Phone to PC.
            val photo = ByteArray(700_000) { (it * 13 + 5).toByte() }
            phone.send(pcId, listOf(Source("photo.jpg", photo.size.toLong()) { ByteArrayInputStream(photo) }), "photo.jpg")
            line("OFFER 1 700000", 40); line("RECEIVED ", 90); await<LinkEvent.Sent>(40)
            assertArrayEquals(photo, File(work, "pc/dl/photo.jpg").readBytes())
            // PC to phone.
            val fromPc = File(work, "fromPc.bin").apply { writeBytes(ByteArray(300_000) { (it % 241).toByte() }) }
            tell("send ${phone.identity} ${fromPc.path}")
            val offer = await<LinkEvent.Offer>(40); phone.answer(offer.transfer, true); await<LinkEvent.Received>(90); line("SENT ", 40)
            assertArrayEquals(fromPc.readBytes(), File(phoneDir, "fromPc.bin").readBytes())
            // The remote, notices, find my phone and music, all through the relay.
            val status = phone.status(pcId, null, null); assertNotNull(phone.lastRemoteError, status); assertEquals(1500, status!!.cover!!.size)
            val clip = phone.remote(pcId, Proto.CMD_CLIP_GET)!!; assertEquals("from pc ✓", Reader(clip.payload).string())
            assertTrue(phone.notice(pcId, Link.statusFrame(64, false))); assertEquals("64 0", line("PHONESTATUS ", 30))
            tell("ring ${phone.identity}"); await<LinkEvent.Ring>(40); line("RANG", 30)
            val song = File(work, "song.mp3").apply { writeBytes(ByteArray(120_000) { 7 }) }
            tell("handoff ${phone.identity} ${song.path}")
            val music = await<LinkEvent.Music>(40); phone.answerMusic(music.transfer, 2); val file = await<LinkEvent.MusicFile>(60); assertEquals(120_000L, File(file.shown).length())
        } finally { runCatching { tell("quit") }; phone.stop(); process.waitFor(5, TimeUnit.SECONDS); process.destroy() }
    }
}
