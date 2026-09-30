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

    /**
     * Revision 5 (island 0.22): the whole island from the phone, against the Windows engine's encoders (share_peer answers
     * as a made-up PC that remembers what it's set to): its live numbers, every setting, the controls, the command bar
     * (a yes asked first where it's needed), the audio outputs and its pages.
     */
    @Test fun the_whole_island_from_the_phone() {
        val exe = System.getenv("ARNAV_SHARE_PEER"); assumeTrue("ARNAV_SHARE_PEER not set", exe != null && File(exe).exists())
        val work = Files.createTempDirectory("arnav-island").toFile(); val phoneDir = File(work, "phone").apply { mkdirs() }
        val pcPort = 47936
        val process = ProcessBuilder(exe, pcPort.toString(), File(work, "pc").path).redirectErrorStream(true).start()
        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        thread(isDaemon = true) { while (true) { val l = reader.readLine() ?: break; println("PC: $l"); lines.put(l) } }
        val pc = process.outputStream.bufferedWriter(Charsets.UTF_8)
        fun tell(cmd: String) { pc.write(cmd); pc.newLine(); pc.flush() }
        val phone = Link(MemoryStore(), "Test Phone", FolderInbox(phoneDir), { events.put(it) }, Link.Options(tcpPort = 0, discovery = false, loopback = true))
        try {
            assertTrue(phone.start())
            val pcId = line("READY ").split(' ')[0]
            phone.addPeer(pcId, "Interop PC", "127.0.0.1", pcPort, 2, 6)
            tell("peer ${phone.identity} ${phone.port}"); line("OK peer")
            phone.pair(pcId); await<LinkEvent.PairCode>(); phone.confirmPair(true); assertTrue(await<LinkEvent.Paired>().ok); line("PAIRED ok")

            // The PC's numbers, live, and what it is.
            val stats = phone.stats(pcId); assertNotNull(phone.lastRemoteError, stats); stats!!
            assertTrue(stats.cpu in 0.0..100.0 && stats.gpu in 0.0..100.0); assertEquals(15.7, stats.ramTotalGiB, 1e-9); assertEquals(16, stats.logical); assertEquals(82, stats.battery); assertTrue(stats.charging)
            assertEquals(40, stats.cpuHistory.size); assertEquals(40, stats.downloadHistory.size); assertTrue(stats.cpuHistory.all { it in 0f..100f })
            assertEquals("ASUS ROG Zephyrus G14", stats.model); assertEquals("Windows 11 Home 24H2", stats.os); assertTrue(stats.gpuName.startsWith("NVIDIA")); assertTrue(stats.uptime > 3 * 86400)
            // Revision 6: each core, and the battery in full with its last day.
            assertEquals(16, stats.cores.size); assertTrue(stats.cores.all { it in 0f..100f }); assertTrue(stats.cores[3] > 60f)
            val battery = phone.battery(pcId)!!; line("REMOTE battery")
            assertEquals(82, battery.percent); assertTrue(battery.present && battery.online && battery.charging && !battery.saver); assertEquals(34, battery.minutesToFull)
            assertEquals(69_920L, battery.fullMwh); assertEquals(21_500L, battery.rateMw); assertEquals(187L, battery.cycles); assertEquals(.92, battery.health, 1e-9); assertEquals("ASUSTeK", battery.manufacturer)
            assertEquals(288, battery.day.size); assertTrue(battery.day.zipWithNext().all { (a, b) -> b.first > a.first }); assertTrue(battery.day.last().third)

            // Revision 6: the PC asks this phone for its readings (on a connection it keeps), and tells it the focus clock.
            val asked = LinkedBlockingQueue<Pair<Int, ByteArray>>()
            phone.onQuery = { peer, command, payload -> assertEquals(pcId, peer); asked.put(command to payload)
                if (command == Proto.QUERY_READINGS) { val text = "Battery\t77%\nPower\tUsing 1.8 W\nModel\tTest Phone".toByteArray(); Bytes().u32(text.size).raw(text).u32(3).raw(byteArrayOf(1, 2, 3)).build() } else ByteArray(0) }
            tell("query ${phone.identity}"); assertEquals("3 Battery\t77% 3", line("PHONELIVE "))
            tell("query ${phone.identity}"); assertEquals("3 Battery\t77% 3", line("PHONELIVE "))
            tell("focus ${phone.identity} 1 1 240.5 300"); line("OK focus")
            val (command, payload) = generateSequence { asked.poll(10, TimeUnit.SECONDS) }.first { it.first == Proto.QUERY_FOCUS }
            val f = IslandWire.focus(payload)!!; assertEquals(Proto.QUERY_FOCUS, command); assertEquals(1, f.mode); assertTrue(f.running); assertEquals(240.5, f.shown, 1e-9); assertEquals(300.0, f.duration, 1e-9); assertEquals("Interop PC", f.pcName)

            // Every setting, then one changed (kept within its range) and a switch turned off.
            val settings = phone.islandSettings(pcId)!!
            assertEquals(10, settings.sections.size); assertEquals("Privacy & productivity", settings.sections[8])
            val delay = settings.items.first { it.key == "hoverDelay" }; assertEquals(1, delay.control); assertEquals(100, delay.lo); assertEquals(700, delay.hi); assertEquals(" ms", delay.unit); assertEquals(180, delay.value)
            val accent = settings.items.first { it.key == "accent" }; assertEquals(4, accent.control); assertEquals(5, accent.colours.size); assertEquals("Lilac", accent.options[2])
            assertEquals(5, settings.items.first { it.control == 5 }.control)
            assertEquals(700, phone.setIslandSetting(pcId, "hoverDelay", 999)); line("SETTING hoverDelay 700")
            assertEquals(0, phone.setIslandSetting(pcId, "hoverOpen", 0)); line("SETTING hoverOpen 0")
            assertEquals(0, phone.islandSettings(pcId)!!.items.first { it.key == "hoverOpen" }.value)
            assertNull(phone.setIslandSetting(pcId, "noSuchSetting", 1))

            // The controls: Wi-Fi off, the brightness, a 10-minute focus.
            val controls = phone.controls(pcId)!!; assertEquals(1, controls.wifi); assertEquals(64, controls.brightness); assertTrue(controls.micAvailable)
            assertEquals(0, phone.setControl(pcId, IslandWire.WIFI, 0)!!.wifi); line("CONTROL 1 0")
            assertEquals(35, phone.setControl(pcId, IslandWire.BRIGHTNESS, 35)!!.brightness)
            val focus = phone.setControl(pcId, IslandWire.FOCUS, 10)!!; assertTrue(focus.focusRunning); assertEquals(600.0, focus.focusDuration, 1e-9); assertEquals(0, focus.focusMode)
            assertTrue(phone.setControl(pcId, IslandWire.AIRPLANE, 1)!!.airplane)

            // The command bar: found as typed, run; one that needs a yes asks first.
            val found = phone.queryCommands(pcId, "spot")!!; assertTrue(found.final); assertEquals("Open Spotify", found.rows.single().title); assertEquals(12, found.rows.single().kind)
            assertEquals(CommandOutcome(0, "Opened Spotify"), phone.runCommand(pcId, "spot", 0, "Open Spotify", false)); line("COMMAND Open Spotify")
            val restart = phone.queryCommands(pcId, "restart")!!.rows.single(); assertTrue(restart.confirm)
            assertEquals(1, phone.runCommand(pcId, "restart", 0, restart.title, false)!!.outcome)
            assertEquals(0, phone.runCommand(pcId, "restart", 0, restart.title, true)!!.outcome); line("COMMAND Restart")
            assertEquals(3, phone.runCommand(pcId, "restart", 0, "Something else", true)!!.outcome)
            assertEquals("85.12 EUR", phone.queryCommands(pcId, "100 usd to eur")!!.rows.first().answer)

            // The audio outputs, one chosen; and a page opened on the PC.
            val outputs = phone.outputs(pcId)!!; assertEquals(2, outputs.size); assertTrue(outputs[0].current)
            assertEquals(Proto.OK, phone.selectOutput(pcId, "buds")); line("OUTPUT buds"); assertTrue(phone.outputs(pcId)!!.first { it.id == "buds" }.current)
            assertTrue(phone.openIslandPage(pcId, 2)); line("ISLAND page 2")
            assertTrue(phone.openIslandPage(pcId, 8)); line("ISLAND page 8")
            assertTrue(phone.closeIsland(pcId)); line("ISLAND close")
        } finally { runCatching { tell("quit") }; phone.stop(); process.waitFor(5, TimeUnit.SECONDS); process.destroy() }
    }

    /**
     * Revision 7 (island 0.24): screens, either way, against the Windows engine's own capture-free pieces. The PC's
     * screen is share_peer's made-up moving picture (never a real screen), encoded by the Windows encoder and put back
     * together here; this phone's screen is played by frames that encoder made at a phone's shape, decoded and counted there.
     */
    @Test fun screens_either_way() {
        val exe = System.getenv("ARNAV_SHARE_PEER"); assumeTrue("ARNAV_SHARE_PEER not set", exe != null && File(exe).exists())
        val work = Files.createTempDirectory("arnav-screens").toFile(); val phoneDir = File(work, "phone").apply { mkdirs() }
        val pcPort = 47937
        val process = ProcessBuilder(exe, pcPort.toString(), File(work, "pc").path).redirectErrorStream(true).start()
        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        thread(isDaemon = true) { while (true) { val l = reader.readLine() ?: break; println("PC: $l"); lines.put(l) } }
        val pc = process.outputStream.bufferedWriter(Charsets.UTF_8)
        fun tell(cmd: String) { pc.write(cmd); pc.newLine(); pc.flush() }
        val phone = Link(MemoryStore(), "Test Phone", FolderInbox(phoneDir), { events.put(it) }, Link.Options(tcpPort = 0, discovery = false, loopback = true))
        try {
            assertTrue(phone.start())
            val pcId = line("READY ").split(' ')[0]
            // A PC that's older can't be asked.
            phone.addPeer(pcId, "Interop PC", "127.0.0.1", pcPort, 2, 6); assertNull(phone.openScreen(pcId, ScreenWire.askPc(1920, 1080, 60)))
            phone.addPeer(pcId, "Interop PC", "127.0.0.1", pcPort, 2, 7)
            tell("peer ${phone.identity} ${phone.port}"); line("OK peer")
            phone.pair(pcId); await<LinkEvent.PairCode>(); phone.confirmPair(true); assertTrue(await<LinkEvent.Paired>().ok); line("PAIRED ok")

            // The PC's screen here: the whole picture on this network, at 60 frames a second; the first frame a key frame
            // with its SPS and PPS (a decoder's setup); numbers in order.
            val (s, answer) = phone.openScreen(pcId, ScreenWire.askPc(2400, 1080, 60))!!
            val r = ScreenWire.reply(answer)!!; assertEquals(0, r.status); assertEquals(1280, r.width); assertEquals(720, r.height); assertEquals(60, r.fps); assertTrue(r.bitrate >= 2_000_000); assertTrue(r.encoder.isNotEmpty())
            val a = ScreenWire.Assembler(); val frames = ArrayList<ScreenWire.Assembler.Frame>(); var end = System.currentTimeMillis() + 20_000
            while (frames.size < 90 && System.currentTimeMillis() < end) {
                val f = s.receive(1000) ?: continue; val whole = a.add(f) ?: continue; frames += whole
                if (frames.size % 15 == 0) s.send(ScreenWire.feedback(whole.number, 4, 0, 60))
            }
            assertTrue("frames: ${frames.size}", frames.size >= 90); assertTrue(frames.first().key)
            val (sps, pps) = ScreenWire.parameterSets(frames.first().data)!!; assertEquals(7, sps[4].toInt() and 0x1F); assertEquals(8, pps[4].toInt() and 0x1F)
            assertTrue(frames.zipWithNext().all { (x, y) -> y.number == x.number + 1 && y.pts > x.pts })
            assertTrue(frames.drop(1).any { !it.key }); assertTrue(frames.all { f -> ScreenWire.nals(f.data).any { it.first == 1 || it.first == 5 } })
            // A key frame asked for comes (with its SPS and PPS again).
            s.send(byteArrayOf(Proto.SCREEN_KEYFRAME.toByte())); var again: ScreenWire.Assembler.Frame? = null; end = System.currentTimeMillis() + 5_000
            while (again == null && System.currentTimeMillis() < end) { val f = s.receive(1000) ?: continue; a.add(f)?.takeIf { it.key }?.let { again = it } }
            assertNotNull(again); assertNotNull(ScreenWire.parameterSets(again!!.data))
            // A touch on the picture reaches the PC as a point (and a click).
            s.send(ScreenWire.input(Link.pointFrame(.5f, .25f))); assertEquals("65ff7fff3f", line("MIRROR input "))
            s.send(ScreenWire.input(Link.buttonFrame(0, 2))); assertEquals("610002", line("MIRROR input "))
            s.send(byteArrayOf(Proto.SCREEN_STOP.toByte()))
            val sent = line("MIRROR sent ").split(' '); assertTrue(sent[0].toInt() >= 90); assertEquals("1280x720", sent[2]); s.close()

            // This phone's screen there: frames from the Windows encoder at a phone's shape, as this phone would send them.
            fun encode(name: String, n: Int, w: Int, h: Int): List<Pair<Boolean, ByteArray>> {
                val sample = File(work, name); tell("encode ${sample.path} $n $w $h"); val encoded = line("ENCODED ", 60).split(' ')[0].toInt(); assertTrue(encoded >= n * 2 / 3)
                val bytes = sample.readBytes(); val video = ArrayList<Pair<Boolean, ByteArray>>(); var at = 0
                while (at + 5 <= bytes.size) { val k = java.nio.ByteBuffer.wrap(bytes, at, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int; video += (bytes[at + 4].toInt() == 1) to bytes.copyOfRange(at + 5, at + 5 + k); at += 5 + k }
                assertEquals(encoded, video.size); assertTrue(video.first().first); return video
            }
            // Upright, then turned (the encoder starts again at the new shape, as a turned phone's does).
            val upright = encode("upright.h264", 90, 720, 1280); val turned = encode("turned.h264", 45, 1280, 720)
            val video = upright + turned
            val (p, reply) = phone.openScreen(pcId, ScreenWire.offerPhone(720, 1280, 30, "Test Phone"))!!
            val pr = ScreenWire.reply(reply)!!; assertEquals(0, pr.status); assertEquals(720, pr.width); assertEquals(1280, pr.height)
            assertEquals("720x1280 Test Phone", line("MIRROR showing "))
            var lastAck = 0L; val acks = thread(isDaemon = true) { while (p.open) { val f = p.receive(500) ?: continue; if ((f[0].toInt() and 0xFF) == Proto.SCREEN_FEEDBACK) lastAck = Reader(f).let { it.u8(); it.u32() ?: 0 } } }
            video.forEachIndexed { i, (key, data) ->
                if (i == upright.size) assertTrue(p.send(Bytes().u8(Proto.SCREEN_LIMITS).u16(1280).u16(720).u8(30).build()))
                ScreenWire.frames(i + 1, key, i * 333_333L, data).forEach { assertTrue(p.send(it)) }; Thread.sleep(8)
            }
            end = System.currentTimeMillis() + 5_000; while (lastAck < video.size && System.currentTimeMillis() < end) Thread.sleep(100)
            assertEquals("the PC said it had them all", video.size.toLong(), lastAck)
            assertEquals("720x1280", line("MIRROR frame ")); assertEquals("the turned frames, at their new shape", "1280x720", line("MIRROR frame "))
            p.send(byteArrayOf(Proto.SCREEN_STOP.toByte()))
            val shown = line("MIRROR shown ").split(' '); assertTrue("shown ${shown[0]} of ${video.size}", shown[0].toInt() >= video.size - 8); assertTrue(shown[1].toInt() >= 1)
            p.close(); acks.join(2000)
        } finally { runCatching { tell("quit") }; phone.stop(); process.waitFor(5, TimeUnit.SECONDS); process.destroy() }
    }

    /**
     * Revision 8 (island 0.25): an offer with its picture; a photo just taken, told to the PC, then asked for by it (to
     * paste) and sent with the PC's ask; pages both ways, where they were scrolled to.
     */
    @Test fun photos_and_pages() {
        val exe = System.getenv("ARNAV_SHARE_PEER"); assumeTrue("ARNAV_SHARE_PEER not set", exe != null && File(exe).exists())
        val work = Files.createTempDirectory("arnav-r8").toFile(); val phoneDir = File(work, "phone").apply { mkdirs() }
        val pcPort = 47938
        val process = ProcessBuilder(exe, pcPort.toString(), File(work, "pc").path).redirectErrorStream(true).start()
        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        thread(isDaemon = true) { while (true) { val l = reader.readLine() ?: break; println("PC: $l"); lines.put(l) } }
        val pc = process.outputStream.bufferedWriter(Charsets.UTF_8)
        fun tell(cmd: String) { pc.write(cmd); pc.newLine(); pc.flush() }
        val phone = Link(MemoryStore(), "Test Phone", FolderInbox(phoneDir), { events.put(it) }, Link.Options(tcpPort = 0, discovery = false, loopback = true))
        try {
            assertTrue(phone.start())
            val pcId = line("READY ").split(' ')[0]
            phone.addPeer(pcId, "Interop PC", "127.0.0.1", pcPort, 2, 8)
            tell("peer ${phone.identity} ${phone.port}"); line("OK peer")
            phone.pair(pcId); await<LinkEvent.PairCode>(); phone.confirmPair(true); assertTrue(await<LinkEvent.Paired>().ok); line("PAIRED ok")
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(2000) { (it % 251).toByte() } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
            val photo = ByteArray(250_000) { (it * 7 + 3).toByte() }

            // To the Shelf with its picture: the PC sees the picture with the offer, then the file.
            phone.send(pcId, listOf(Source("beach.jpg", photo.size.toLong()) { ByteArrayInputStream(photo) }), "beach.jpg", toShelf = true, preview = jpeg)
            assertEquals("1 250000 shelf", line("OFFER ")); assertEquals("${jpeg.size} 1 0 beach.jpg", line("OFFERPIC ")); line("RECEIVED "); await<LinkEvent.Sent>()
            assertArrayEquals(photo, File(work, "pc/dl/beach.jpg").readBytes())

            // A photo just taken, told to the PC: its id, name, size, shape and picture.
            assertTrue(phone.notice(pcId, Link.photoFrame(4_000_000_042L, "PXL_20260930.jpg", photo.size.toLong(), 4032, 3024, jpeg)))
            assertEquals("4000000042 PXL_20260930.jpg 250000 4032x3024 ${jpeg.size}", line("PHONEPHOTO "))
            // The PC asks for it, to paste; it comes with the PC's ask (and without asking there).
            val asked = LinkedBlockingQueue<Pair<Int, ByteArray>>()
            phone.onQuery = { peer, command, payload -> assertEquals(pcId, peer); asked.put(command to payload); ByteArray(0) }
            tell("photo-ask ${phone.identity} 4000000042 1 77"); line("OK photo-ask")
            val (command, payload) = asked.poll(15, TimeUnit.SECONDS)!!; assertEquals(Proto.QUERY_PHOTO, command)
            val r = Reader(payload); assertEquals(4_000_000_042L, r.u64()); assertEquals(1, r.u8()); assertEquals(77L, r.u32())
            phone.send(pcId, listOf(Source("PXL_20260930.jpg", photo.size.toLong()) { ByteArrayInputStream(photo) }), "PXL_20260930.jpg", preview = jpeg, ask = 77)
            assertEquals("${jpeg.size} 1 77 PXL_20260930.jpg", line("OFFERPIC ")); assertTrue(line("RECEIVED ").endsWith("|ask 77")); await<LinkEvent.Sent>()

            // A page from the PC, where it was scrolled to.
            tell("page ${phone.identity} 0.42 https://example.com/long/read The long read"); line("OK page")
            val (pageCommand, page) = generateSequence { asked.poll(15, TimeUnit.SECONDS) }.first { it.first == Proto.QUERY_PAGE }; assertEquals(Proto.QUERY_PAGE, pageCommand)
            val pr = Reader(page); assertEquals(.42f, java.lang.Float.intBitsToFloat(pr.u32()!!.toInt()), 1e-6f); assertEquals("https://example.com/long/read", pr.string(4096)); assertEquals("The long read", pr.string(400))
            // And one to the PC.
            val reply = phone.remote(pcId, Proto.CMD_PAGE, Link.pagePayload("https://example.com/b?x=1", "B", .7f))!!; assertTrue(reply.ok)
            assertEquals("0.700 https://example.com/b?x=1", line("PAGE "))
        } finally { runCatching { tell("quit") }; phone.stop(); process.waitFor(5, TimeUnit.SECONDS); process.destroy() }
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
            // Revision 5: the hotspot, on (its name and password) and off.
            assertTrue(phone.notice(pcId, Link.hotspotFrame(true, "Pixel hotspot", "correct horse"))); assertEquals("on Pixel hotspot 13", line("HOTSPOT "))
            assertTrue(phone.notice(pcId, Link.hotspotFrame(false, "", ""))); assertEquals("off  0", line("HOTSPOT "))
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
        val phone = Link(MemoryStore(), "Test Phone", FolderInbox(phoneDir), { events.put(it) }, Link.Options(tcpPort = 0, discovery = false, loopback = true, relay = true, directLoopback = true))
        try {
            assertTrue(phone.start())
            val pcId = line("READY ").split(' ')[0]
            val connected = System.currentTimeMillis() + 30_000; while (!phone.internet && System.currentTimeMillis() < connected) Thread.sleep(200)
            assertTrue("the phone reached the relay", phone.internet)
            Thread.sleep(3_000)
            // The PC offers a code once its own relay is up (it waits 8 s for it; a slow broker can take longer on its first connection).
            var code = ""
            for (attempt in 1..4) { tell("host"); code = line("CODE ", 40); if (Relay.code(code) != null) break; Thread.sleep(5_000) }
            assertTrue("the PC offered a code: '$code'", Relay.code(code) != null)
            phone.pairWithCode(code)
            val shown = await<LinkEvent.PairCode>(40).code
            assertEquals("the same six digits on both", line("PAIRCODE ").toInt(), shown)
            phone.confirmPair(true); assertTrue(await<LinkEvent.Paired>(40).ok); line("PAIRED ok")
            // Each sees the other through the relay.
            val seen = System.currentTimeMillis() + 40_000
            while (System.currentTimeMillis() < seen && phone.peers().none { it.id == pcId && it.online && it.internet }) Thread.sleep(200)
            val view = phone.peers().first { it.id == pcId }; assertTrue(view.online && view.internet); assertEquals(8, view.revision)
            line("PRESENCE ${phone.identity} 1 1 8 1", 40)

            // Revision 7: the PC's screen (its made-up picture) through the relay, light and paced for it.
            run {
                val (s, answer) = phone.openScreen(pcId, ScreenWire.askPc(1920, 1080, 60))!!
                val r = ScreenWire.reply(answer)!!; assertEquals(0, r.status); assertTrue(r.fps == 20 || r.fps == 60); assertTrue(r.width <= 1920)
                val a = ScreenWire.Assembler(); var frames = 0; var key = false; val end = System.currentTimeMillis() + 40_000
                while (frames < 12 && System.currentTimeMillis() < end) { val f = s.receive(1000) ?: continue; a.add(f)?.let { frames++; key = key || it.key; s.send(ScreenWire.feedback(it.number, 3, 0, 20)) } }
                assertTrue("frames through the relay: $frames", frames >= 12); assertTrue(key)
                s.send(byteArrayOf(Proto.SCREEN_STOP.toByte())); line("MIRROR sent ", 40); s.close()
            }

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

    /**
     * 1.2: the phone can reach only one broker (Eclipse Mosquitto), the PC all three. Before, each kept to a single broker,
     * so a phone that had fallen back to another than the PC's never saw its code. Now the PC is on every broker, and they meet.
     */
    @Test fun a_phone_on_one_broker_still_finds_the_pc() =
        meetOnSplitBrokers(pcArgs = listOf("--relay"), phoneBrokers = listOf("test.mosquitto.org" to 8886), port = 47932)

    /**
     * 1.2: a broker drops a message now and then (QoS 0), and a tunnel used to end at the first gap. Here each side leaves
     * out every few data messages on purpose (the PC every 5th, the phone every 4th): each is sent again, and everything
     * still arrives, both ways.
     */
    @Test fun what_a_broker_drops_is_sent_again() =
        meetOnSplitBrokers(pcArgs = listOf("--relay", "--relay-lose=5"), phoneBrokers = Relay.DEFAULT_BROKERS, port = 47934, phoneLoseEvery = 4)

    /**
     * 1.3: a direct path. Both on the loopback here (as two devices on different networks are on their public addresses):
     * after pairing through the relay they find each other directly within seconds; then the remote answers in one round
     * trip on its kept connection, and 3 MB go each way over the path.
     */
    @Test fun a_direct_path_is_found_and_used() =
        meetOnSplitBrokers(pcArgs = listOf("--relay", "--direct-loopback"), phoneBrokers = Relay.DEFAULT_BROKERS, port = 47935, direct = true)

    /** The other way round: the PC can reach only EMQX, the phone all three. A scanned link with another PC's key is refused first. */
    @Test fun a_pc_on_one_broker_is_still_found_by_the_phone() =
        meetOnSplitBrokers(pcArgs = listOf("--relay-only=1"), phoneBrokers = Relay.DEFAULT_BROKERS, port = 47933, wrongKeyFirst = true)

    /**
     * Pairs across a broker split by the island's QR code (its link carries the PC's key fingerprint, which the phone
     * checks, so only the PC confirms), then a remote status, a file each way and presence, all through the one broker both share.
     */
    private fun meetOnSplitBrokers(pcArgs: List<String>, phoneBrokers: List<Pair<String, Int>>, port: Int, wrongKeyFirst: Boolean = false, phoneLoseEvery: Int = 0, direct: Boolean = false) {
        val exe = System.getenv("ARNAV_SHARE_PEER"); assumeTrue("ARNAV_SHARE_PEER not set", exe != null && File(exe).exists())
        assumeTrue("ARNAV_RELAY_TEST not set", System.getenv("ARNAV_RELAY_TEST") != null)
        val work = Files.createTempDirectory("arnav-split").toFile(); val phoneDir = File(work, "phone").apply { mkdirs() }
        val process = ProcessBuilder(listOf(exe, port.toString(), File(work, "pc").path) + pcArgs).redirectErrorStream(true).start()
        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        thread(isDaemon = true) { while (true) { val l = reader.readLine() ?: break; if (!l.startsWith("REMOTE status")) println("PC: $l"); lines.put(l) } }
        val pc = process.outputStream.bufferedWriter(Charsets.UTF_8)
        fun tell(cmd: String) { pc.write(cmd); pc.newLine(); pc.flush() }
        val phone = Link(MemoryStore(), "Test Phone", FolderInbox(phoneDir), { events.put(it) }, Link.Options(tcpPort = 0, discovery = false, loopback = true, relay = true, relayBrokers = phoneBrokers, relayLoseEvery = phoneLoseEvery, directLoopback = true))
        try {
            assertTrue(phone.start())
            val pcId = line("READY ").split(' ')[0]
            val connected = System.currentTimeMillis() + 30_000; while (!phone.internet && System.currentTimeMillis() < connected) Thread.sleep(200)
            assertTrue("the phone reached a broker", phone.internet)
            Thread.sleep(3_000)
            var code = ""
            for (attempt in 1..4) { tell("host"); code = line("CODE ", 40); if (Relay.code(code) != null) break; Thread.sleep(5_000) }
            assertTrue("the PC offered a code: '$code'", Relay.code(code) != null)
            // The link the island's QR code carries: the code, and the PC's key fingerprint.
            val link = Relay.pairLink(line("LINK ", 10)); assertNotNull(link); assertEquals(Relay.code(code), link!!.code); assertEquals(10, link.key?.size)
            if (wrongKeyFirst) {
                // Another PC's fingerprint: whoever answers the code isn't it, so nothing is paired (and nothing more is said).
                val other = link.key!!.copyOf().also { it[0] = (it[0] + 1).toByte() }
                assertTrue(phone.pairWithCode(link.code, other))
                val refused = await<LinkEvent.Paired>(60); assertFalse(refused.ok); assertTrue(refused.detail, refused.detail.startsWith("Another device answered"))
                assertTrue(phone.peers().none { it.id == pcId && it.paired })
                Thread.sleep(3_000); lines.clear()
            }
            assertTrue(phone.pairWithCode(link.code, link.key))
            // The phone said yes by itself; only the PC confirms (share_peer does at once).
            val shown = await<LinkEvent.PairCode>(40); assertTrue(shown.confirmed)
            assertEquals("the same six digits on both", line("PAIRCODE ").toInt(), shown.code)
            assertTrue(await<LinkEvent.Paired>(40).ok); line("PAIRED ok")
            val seen = System.currentTimeMillis() + 40_000
            while (System.currentTimeMillis() < seen && phone.peers().none { it.id == pcId && it.online && it.internet }) Thread.sleep(200)
            assertTrue("the phone sees the PC through the broker they share", phone.peers().any { it.id == pcId && it.online && it.internet })
            line("PRESENCE ${phone.identity} 1 1 8 1", 40)
            if (direct) {
                val until = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < until && phone.peers().none { it.id == pcId && it.path == 2 }) Thread.sleep(100)
                assertTrue("a direct path was found", phone.peers().any { it.id == pcId && it.path == 2 })
                tell("peers"); assertEquals("the PC went direct too", "2", line("PATH ${phone.identity} ", 10).split(' ')[0])
            }
            val status = phone.status(pcId, null, null); assertNotNull(phone.lastRemoteError, status); assertEquals("Interop Song", status!!.title)
            // Revision 4: the remote's connection is kept, so each command is one round trip (and no handshake).
            val t0 = System.nanoTime(); repeat(10) { assertNotNull(phone.lastRemoteError, phone.status(pcId, null, null)) }
            val each = (System.nanoTime() - t0) / 10 / 1e6; println("status round trip (%s): %.0f ms".format(if (direct) "direct" else "relay", each))
            if (direct) assertTrue("the remote on the direct path answers in %.0f ms".format(each), each < 150)
            val size = if (direct) 3_000_000 else 200_000
            val photo = ByteArray(size) { (it * 7 + 3).toByte() }
            val sendAt = System.nanoTime()
            phone.send(pcId, listOf(Source("split.jpg", photo.size.toLong()) { ByteArrayInputStream(photo) }), "split.jpg")
            line("OFFER 1 $size", 40); line("RECEIVED ", 90); await<LinkEvent.Sent>(40)
            if (direct) println("3 MB to the PC directly in %.1f s".format((System.nanoTime() - sendAt) / 1e9))
            assertArrayEquals(photo, File(work, "pc/dl/split.jpg").readBytes())
            val fromPc = File(work, "back.bin").apply { writeBytes(ByteArray(if (direct) 3_000_000 else 90_000) { (it % 199).toByte() }) }
            tell("send ${phone.identity} ${fromPc.path}")
            val offer = await<LinkEvent.Offer>(40); phone.answer(offer.transfer, true); await<LinkEvent.Received>(90); line("SENT ", 40)
            assertArrayEquals(fromPc.readBytes(), File(phoneDir, "back.bin").readBytes())
            if (direct) {
                // The path goes quiet on the PC's side (as when a network drops it): within the minute the phone is back on
                // the relay, and the remote answers there, on the same kept connection moved onto a broker.
                tell("direct off"); line("OK direct off", 10)
                val back = System.currentTimeMillis() + 60_000
                while (System.currentTimeMillis() < back && phone.peers().none { it.id == pcId && it.path == 1 }) Thread.sleep(250)
                assertTrue("back on the relay", phone.peers().any { it.id == pcId && it.path == 1 && it.online })
                val relayed = phone.status(pcId, null, null); assertNotNull(phone.lastRemoteError, relayed); assertEquals("Interop Song", relayed!!.title)
            }
        } finally { runCatching { tell("quit") }; phone.stop(); process.waitFor(5, TimeUnit.SECONDS); process.destroy() }
    }
}
