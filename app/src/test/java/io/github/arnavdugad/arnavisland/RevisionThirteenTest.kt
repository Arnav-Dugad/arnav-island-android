package io.github.arnavdugad.arnavisland

import io.github.arnavdugad.arnavisland.link.Bytes
import io.github.arnavdugad.arnavisland.link.Crypto
import io.github.arnavdugad.arnavisland.link.FilesWire
import io.github.arnavdugad.arnavisland.link.IslandWire
import io.github.arnavdugad.arnavisland.link.Link
import io.github.arnavdugad.arnavisland.link.NoticeWire
import io.github.arnavdugad.arnavisland.link.Proto
import io.github.arnavdugad.arnavisland.link.Reader
import io.github.arnavdugad.arnavisland.link.SmsWire
import io.github.arnavdugad.arnavisland.link.UnlockWire
import io.github.arnavdugad.arnavisland.link.unhex
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 1.13: island 0.30's revision 13 (PROTOCOL-13): the phone's files, its text messages, unlocking the PC, and calls. */
class RevisionThirteenTest {
    @Test fun the_numbers_are_the_specs() {
        assertEquals(13, Proto.REVISION)
        assertEquals(listOf(5, 6, 7), listOf(Proto.QUERY_FILES, Proto.QUERY_SMS, Proto.QUERY_UNLOCK)); assertEquals(9, Proto.NOTICE_UNLOCK)
        assertEquals(16, NoticeWire.ONGOING_CALL); assertEquals(13, NoticeWire.CALL_REVISION)
        assertEquals(listOf(1, 2, 3, 4), listOf(FilesWire.LISTED, FilesWire.OFF, FilesWire.PERMISSION, FilesWire.NOT_FOUND))
        assertEquals(listOf(1, 2, 3, 5), listOf(SmsWire.OK, SmsWire.OFF, SmsWire.PERMISSION, SmsWire.FAILED))
        assertEquals(listOf(1, 2, 3, 4), listOf(UnlockWire.YES, UnlockWire.DECLINED, UnlockWire.NOT_SET_UP, UnlockWire.TIMED_OUT))
    }

    // ---- query 5 ----
    /** A made-up phone's storage: Download, DCIM/Camera, two files, a hidden file and folder; and a card. */
    private class Phone {
        val top: File = Files.createTempDirectory("arnav-phone").toFile()
        val internal = File(top, "emulated/0").apply { mkdirs() }
        val card = File(top, "1234-ABCD").apply { mkdirs() }
        val outside = File(top, "outside").apply { mkdirs(); File(this, "secret.txt").writeText("no") }
        init {
            File(internal, "Download").mkdirs(); File(internal, "DCIM/Camera").mkdirs(); File(internal, "Music").mkdirs()
            File(internal, "Zeta").mkdirs(); File(internal, ".thumbnails").mkdirs(); File(internal, ".nomedia").writeText("")
            File(internal, "notes.txt").apply { writeText("hello"); setLastModified(1_700_000_000_000) }
            File(internal, "beach.jpg").apply { writeBytes(ByteArray(3000) { 1 }); setLastModified(1_760_000_000_000) }
            File(internal, "alpha.PDF").apply { writeBytes(ByteArray(10) { 2 }); setLastModified(1_600_000_000_000) }
            File(internal, "Zeta/inner.txt").writeText("inside"); File(internal, "Zeta/.hidden").writeText("x"); File(internal, "Zeta/Deeper").mkdirs(); File(internal, "Zeta/Deeper/d.bin").writeBytes(ByteArray(7))
        }
        val roots = listOf(FilesWire.Root(internal, "Internal storage", FilesWire.PLACE), FilesWire.Root(card, "SD card", FilesWire.VOLUME))
        val places = listOf(FilesWire.Root(internal, "Internal storage", FilesWire.PLACE), FilesWire.Root(File(internal, "Download"), "Downloads", FilesWire.PLACE),
            FilesWire.Root(File(internal, "DCIM/Camera"), "Camera", FilesWire.PLACE), FilesWire.Root(File(internal, "Pictures"), "Pictures", FilesWire.PLACE))
        var thumbs = 0
        val lister = FilesWire.Lister({ roots }, { places }) { thumbs++; byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9) }
        fun ask(path: String, sort: Int = 0, offset: Int = 0, max: Int = 200, thumbs: Boolean = false) = lister.answer(FilesWire.request(FilesWire.listRequest(path, sort, offset, max, thumbs)) as FilesWire.Listing)
    }

    @Test fun the_phones_places_then_its_volumes() {
        val p = Phone(); val list = IslandWire.files(p.ask(""))!!
        assertEquals("", list.path); assertEquals("This phone", list.title); assertEquals("", list.parent)
        // Pictures doesn't exist here, so it isn't listed; the card follows the places.
        assertEquals(listOf("Internal storage", "Downloads", "Camera", "SD card"), list.entries.map { it.name })
        assertEquals(listOf(3, 3, 3, 2), list.entries.map { it.kind }); assertEquals(4L, list.total)
        assertEquals(p.internal.path, list.entries[0].path)
    }

    @Test fun a_folder_listed_and_sorted() {
        val p = Phone()
        val byName = IslandWire.files(p.ask(p.internal.path))!!
        assertEquals(p.internal.path, byName.path); assertEquals("Internal storage", byName.title); assertEquals("", byName.parent)
        // Folders first, then files, each by name; nothing hidden.
        assertEquals(listOf("DCIM", "Download", "Music", "Zeta", "alpha.PDF", "beach.jpg", "notes.txt"), byName.entries.map { it.name }); assertEquals(7L, byName.total)
        assertEquals(listOf(1, 1, 1, 1, 0, 0, 0), byName.entries.map { it.kind })
        assertEquals(5L, byName.entries.first { it.name == "notes.txt" }.size); assertEquals(1_700_000_000L, byName.entries.first { it.name == "notes.txt" }.modified)
        assertTrue(byName.entries.all { it.thumb == null }); assertEquals(0, p.thumbs)
        assertEquals(listOf("beach.jpg", "notes.txt", "alpha.PDF"), IslandWire.files(p.ask(p.internal.path, sort = 1))!!.entries.drop(4).map { it.name })
        assertEquals(listOf("beach.jpg", "alpha.PDF", "notes.txt"), IslandWire.files(p.ask(p.internal.path, sort = 2))!!.entries.drop(4).map { it.name })
        assertEquals(listOf("beach.jpg", "alpha.PDF", "notes.txt"), IslandWire.files(p.ask(p.internal.path, sort = 3))!!.entries.drop(4).map { it.name })
        // A page of it, and pictures' thumbnails when asked (only pictures).
        val page = IslandWire.files(p.ask(p.internal.path, offset = 3, max = 3, thumbs = true))!!
        assertEquals(listOf("Zeta", "alpha.PDF", "beach.jpg"), page.entries.map { it.name }); assertEquals(7L, page.total)
        assertEquals(1, p.thumbs); assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9), page.entries[2].thumb); assertNull(page.entries[1].thumb)
        // Inside: its parent is the folder above.
        val zeta = IslandWire.files(p.ask(File(p.internal, "Zeta").path))!!
        assertEquals("Zeta", zeta.title); assertEquals(p.internal.path, zeta.parent); assertEquals(listOf("Deeper", "inner.txt"), zeta.entries.map { it.name })
    }

    @Test fun nothing_outside_its_storage_is_listed() {
        val p = Phone()
        for (path in listOf(p.outside.path, File(p.internal, "../outside").path, File(p.internal, "Zeta/../../outside").path, File(p.internal, ".thumbnails").path,
                File(p.internal, "Nope").path, File(p.internal, "notes.txt").path, "relative/path", p.top.path))
            assertArrayEquals(path, byteArrayOf(FilesWire.NOT_FOUND.toByte()), p.ask(path))
        assertNull(p.lister.resolve(File(p.internal, "Zeta/.hidden").path)); assertNull(p.lister.sources(p.outside.path))
        // A symbolic link out of it isn't followed (where this system lets the test make one).
        val link = File(p.internal, "escape")
        if (runCatching { Files.createSymbolicLink(link.toPath(), p.outside.toPath()) }.isSuccess) {
            assertArrayEquals(byteArrayOf(FilesWire.NOT_FOUND.toByte()), p.ask(link.path)); assertNull(p.lister.resolve(File(link, "secret.txt").path))
            assertTrue(IslandWire.files(p.ask(p.internal.path))!!.entries.none { it.name == "escape" })
        }
    }

    @Test fun a_file_or_a_folder_to_send() {
        val p = Phone()
        val send = FilesWire.request(FilesWire.sendRequest(File(p.internal, "notes.txt").path, 77)) as FilesWire.Send; assertEquals(77, send.ask)
        assertNull(FilesWire.request(FilesWire.sendRequest("x", 0))); assertNull(FilesWire.request(byteArrayOf(9))); assertNull(FilesWire.request(byteArrayOf(0, 1, 0)))
        val (title, folder, items) = p.lister.sources(send.path)!!
        assertEquals("notes.txt", title); assertFalse(folder); assertEquals("notes.txt", items.single().rel); assertEquals("hello", items.single().open().readBytes().toString(Charsets.UTF_8))
        val (t2, f2, tree) = p.lister.sources(File(p.internal, "Zeta").path)!!
        assertEquals("Zeta", t2); assertTrue(f2); assertEquals(listOf("Zeta/Deeper/d.bin", "Zeta/inner.txt"), tree.map { it.rel }); assertEquals(listOf(7L, 6L), tree.map { it.size })
        // A request asks for at most 200, at least one.
        assertEquals(200, (FilesWire.request(FilesWire.listRequest("", max = 999)) as FilesWire.Listing).max)
    }

    @Test fun thumbnails_stay_within_the_answer() {
        val entries = List(200) { FilesWire.Entry("p$it.jpg", "/storage/emulated/0/p$it.jpg", FilesWire.FILE, 1, 1, ByteArray(FilesWire.THUMB_LIMIT) { 3 }) } +
            FilesWire.Entry("big.jpg", "/x/big.jpg", FilesWire.FILE, 1, 1, ByteArray(FilesWire.THUMB_LIMIT + 1))
        val answer = FilesWire.listed(FilesWire.Page("/x", "x", "/", entries.size, entries))
        assertTrue(answer.size <= Proto.ANSWER_BUDGET)
        val list = IslandWire.files(answer)!!; assertEquals(201, list.entries.size)
        assertTrue(list.entries.count { it.thumb != null } in 20..29); assertNull(list.entries.last().thumb)
    }

    // ---- query 6 ----
    @Test fun text_message_questions_are_read() {
        val t = SmsWire.request(SmsWire.threadsRequest(20, 500)) as SmsWire.Threads; assertEquals(20, t.offset); assertEquals(100, t.max)
        val m = SmsWire.request(SmsWire.messagesRequest(42, 1_760_000_000_000, 30)) as SmsWire.Messages; assertEquals(42L, m.thread); assertEquals(1_760_000_000_000L, m.before); assertEquals(30, m.max)
        val s = SmsWire.request(SmsWire.sendRequest(" +91 98450 12345 ", "On my way")) as SmsWire.Send; assertEquals("+91 98450 12345", s.address); assertEquals("On my way", s.text)
        assertEquals(7L, (SmsWire.request(SmsWire.readRequest(7)) as SmsWire.Read).thread)
        // Nothing to send, too long, or to no number: refused.
        assertNull(SmsWire.request(SmsWire.sendRequest("+15551234", " "))); assertNull(SmsWire.request(SmsWire.sendRequest("+15551234", "x".repeat(2001))))
        assertNull(SmsWire.request(SmsWire.sendRequest("mum; rm", "hi"))); assertNull(SmsWire.request(SmsWire.sendRequest("", "hi")))
        assertNull(SmsWire.request(byteArrayOf(1, 1, 2))); assertNull(SmsWire.request(byteArrayOf(4)))
    }

    @Test fun conversations_as_the_pc_reads_them() {
        val photo = ByteArray(900) { 4 }
        val answer = SmsWire.threads(listOf(SmsWire.Thread(12, "+15551234", "Mum", "Dinner at 8?", 1_760_000_000_000, 2, SmsWire.HAS_PICTURES, photo),
            SmsWire.Thread(9, "+15559999", "+15559999", "Sent you the file", 1_759_000_000_000, 0, SmsWire.LAST_MINE, ByteArray(SmsWire.PHOTO_LIMIT + 1))))
        // Byte by byte for the first.
        val r = Reader(answer); assertEquals(1, r.u8()); assertEquals(2, r.u16())
        assertEquals(12L, r.u64()); assertEquals("+15551234", r.string()); assertEquals("Mum", r.string()); assertEquals("Dinner at 8?", r.string())
        assertEquals(1_760_000_000_000L, r.u64()); assertEquals(2, r.u8()); assertEquals(2, r.u8()); assertArrayEquals(photo, r.blob(SmsWire.PHOTO_LIMIT))
        val both = SmsWire.parseThreads(answer)!!; assertEquals(SmsWire.LAST_MINE, both[1].flags); assertNull("a photo too big is left out", both[1].photo)
        assertArrayEquals(byteArrayOf(2), SmsWire.state(SmsWire.OFF))
    }

    @Test fun messages_oldest_first_with_pictures_within_the_answer() {
        val newestFirst = List(100) { i -> SmsWire.Message(1000L - i, "message $i", 1_760_000_000_000 - i * 60_000L, if (i % 2 == 0) SmsWire.MINE else 0,
            if (i < 10) ByteArray(SmsWire.PICTURE_LIMIT) { 7 } else null) } + SmsWire.Message(5, "failed", 1, SmsWire.MINE or SmsWire.NOT_SENT, null)
        val answer = SmsWire.messages(newestFirst); assertTrue(answer.size <= Proto.ANSWER_BUDGET)
        val got = SmsWire.parseMessages(answer)!!
        assertEquals(100, got.size); assertEquals("oldest first", "message 99", got.first().text); assertEquals("message 0", got.last().text)
        assertTrue(got.zipWithNext().all { (a, b) -> a.date < b.date }); assertEquals(SmsWire.MINE, got.last().flags)
        // The newest pictures go first, as many as fit.
        val pictured = got.filter { it.picture != null }.map { it.text }; assertTrue(pictured.size in 3..4); assertTrue(pictured.contains("message 0"))
    }

    // ---- query 7 and notice 9 ----
    @Test fun unlock_questions_are_checked() {
        val pc = Crypto.newKeyPair(); val cp = UnlockWire.point(pc.public); val nonce = Crypto.random(32)
        assertEquals(65, cp.size); assertEquals(4, cp[0].toInt())
        val enrol = UnlockWire.request(UnlockWire.enrolRequest(cp, "Studio PC", nonce))!!
        assertEquals(UnlockWire.ENROL, enrol.op); assertArrayEquals(cp, enrol.cpPublic); assertEquals("Studio PC", enrol.pcName); assertArrayEquals(nonce, enrol.nonce)
        val unlock = UnlockWire.request(UnlockWire.unlockRequest(nonce, "Studio PC"))!!; assertEquals(UnlockWire.UNLOCK, unlock.op); assertArrayEquals(nonce, unlock.nonce)
        assertEquals(UnlockWire.FORGET, UnlockWire.request(UnlockWire.forgetRequest())!!.op)
        // A key off the curve, a compressed one, a short nonce: refused.
        val off = cp.copyOf().also { it[64] = (it[64] + 1).toByte() }
        assertNull(UnlockWire.request(UnlockWire.enrolRequest(off, "PC", nonce))); assertNull(UnlockWire.request(UnlockWire.enrolRequest(cp.copyOf(33).also { it[0] = 2 }, "PC", nonce)))
        assertNull(UnlockWire.request(UnlockWire.unlockRequest(nonce.copyOf(31), "PC"))); assertNull(UnlockWire.request(UnlockWire.enrolRequest(cp, "PC", ByteArray(33))))
        assertNull(UnlockWire.request(byteArrayOf(3))); assertNull(UnlockWire.request(ByteArray(0)))
    }

    @Test fun the_secret_sealed_opens_only_for_that_question() {
        val pc = Crypto.newKeyPair(); val cp = UnlockWire.point(pc.public); val nonce = Crypto.random(32); val k = Crypto.random(32)
        val e = UnlockWire.seal(UnlockWire.UNLOCK, nonce, cp, k)!!
        assertEquals(65, e.ephemeral.size); assertEquals(12, e.iv.size); assertEquals(48, e.sealed.size)
        assertArrayEquals(k, UnlockWire.open(UnlockWire.UNLOCK, nonce, e, pc.private))
        // The key, by hand as the PC's component makes it: SHA-256("arnav-unlock-1" ‖ ECDH X ‖ nonce), AAD nonce ‖ op.
        val x = javax.crypto.KeyAgreement.getInstance("ECDH").run { init(pc.private); doPhase(UnlockWire.publicKey(e.ephemeral), true); generateSecret() }; assertEquals(32, x.size)
        val key = Crypto.sha256("arnav-unlock-1".toByteArray() + x + nonce)
        val opened = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run { init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, e.iv)); updateAAD(nonce + byteArrayOf(1)); doFinal(e.sealed) }
        assertArrayEquals(k, opened)
        // Another nonce, another op, another PC, a changed byte: nothing.
        assertNull(UnlockWire.open(UnlockWire.UNLOCK, Crypto.random(32), e, pc.private)); assertNull(UnlockWire.open(UnlockWire.ENROL, nonce, e, pc.private))
        assertNull(UnlockWire.open(UnlockWire.UNLOCK, nonce, e, Crypto.newKeyPair().private))
        assertNull(UnlockWire.open(UnlockWire.UNLOCK, nonce, UnlockWire.Envelope(e.ephemeral, e.iv, e.sealed.copyOf().also { it[3] = (it[3] + 1).toByte() }), pc.private))
        // Each sealing is fresh.
        val again = UnlockWire.seal(UnlockWire.UNLOCK, nonce, cp, k)!!; assertFalse(again.ephemeral.contentEquals(e.ephemeral)); assertFalse(again.sealed.contentEquals(e.sealed))
        assertNull(UnlockWire.seal(UnlockWire.UNLOCK, nonce, cp, ByteArray(31))); assertNull(UnlockWire.seal(UnlockWire.UNLOCK, nonce.copyOf(16), cp, k))
    }

    /** P-256 by hand (affine double-and-add), so the vector's public points are checked against their private scalars. */
    private object P256 {
        val params = (KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey).params
        private val p = (params.curve.field as ECFieldFp).p
        private fun add(x: ECPoint, y: ECPoint): ECPoint {
            if (x == ECPoint.POINT_INFINITY) return y
            if (y == ECPoint.POINT_INFINITY) return x
            val l = if (x == y) x.affineX.pow(2).multiply(BigInteger.valueOf(3)).add(params.curve.a).multiply(x.affineY.shiftLeft(1).modInverse(p)).mod(p)
                else { if (x.affineX == y.affineX) return ECPoint.POINT_INFINITY; y.affineY.subtract(x.affineY).multiply(y.affineX.subtract(x.affineX).modInverse(p)).mod(p) }
            val rx = l.pow(2).subtract(x.affineX).subtract(y.affineX).mod(p)
            return ECPoint(rx, l.multiply(x.affineX.subtract(rx)).subtract(x.affineY).mod(p))
        }
        fun times(d: BigInteger): ECPoint {
            var r = ECPoint.POINT_INFINITY; var q = params.generator
            for (i in 0 until d.bitLength()) { if (d.testBit(i)) r = add(r, q); q = add(q, q) }
            return r
        }
        private fun fixed(v: BigInteger) = v.toByteArray().let { b -> if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b }
        fun point(d: BigInteger): ByteArray { val w = times(d); return byteArrayOf(4) + fixed(w.affineX) + fixed(w.affineY) }
        fun pair(d: BigInteger): KeyPair {
            val kf = KeyFactory.getInstance("EC")
            return KeyPair(kf.generatePublic(ECPublicKeySpec(times(d), params)), kf.generatePrivate(ECPrivateKeySpec(d, params)))
        }
    }

    /** A fixed vector the PC's side opens (made apart, with Python's cryptography): sealed here byte for byte the same. */
    @Test fun the_unlock_envelope_matches_the_pcs_vector() {
        val cpPublic = "04bd7c73b88b2e9b4ceda62022b2da8be13193a5b56edc26e7df7842e24cd0b5eb0605ada7bda83ac6a2b80d7e314040fa47ff16b83bac85cedb014451bb7ce71a".unhex()!!
        val cpPrivate = BigInteger("1f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c5b6a7988", 16)
        val ephemeralPrivate = BigInteger("0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9", 16)
        val ephemeral = "04160b0615159ebbdf7fd4b1194d42b2986c6b2cccad799521b8b326292b6419b393a20e124ad2a56ecea74f8dae84d8d91d0f5ac4a33f221226d1681cd12552ca".unhex()!!
        val nonce = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".unhex()!!
        val iv = "6465666768696a6b6c6d6e6f".unhex()!!
        val k = "030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dc".unhex()!!
        val sealed = "7d0940d9302816e1c2d4f492d0b4602236b2d3008013dc9f866ca4969506e30ffa8d9939e76c48d07bcbf617b0217724".unhex()!!
        // The vector's public points are its private scalars' (the PC's too).
        assertArrayEquals(cpPublic, P256.point(cpPrivate)); assertArrayEquals(ephemeral, P256.point(ephemeralPrivate))
        // Sealed here with the vector's ephemeral key and IV: the same bytes, op 1 (unlock).
        val e = UnlockWire.seal(UnlockWire.UNLOCK, nonce, cpPublic, k, ephemeral = P256.pair(ephemeralPrivate), iv = iv)!!
        assertArrayEquals(ephemeral, e.ephemeral); assertArrayEquals(iv, e.iv); assertArrayEquals(sealed, e.sealed)
        // It opens with the PC's private key (and not for the other op); an IV of the wrong size is refused.
        val cp = P256.pair(cpPrivate).private
        assertArrayEquals(k, UnlockWire.open(UnlockWire.UNLOCK, nonce, UnlockWire.Envelope(ephemeral, iv, sealed), cp))
        assertNull(UnlockWire.open(UnlockWire.ENROL, nonce, UnlockWire.Envelope(ephemeral, iv, sealed), cp))
        assertNull(UnlockWire.seal(UnlockWire.UNLOCK, nonce, cpPublic, k, iv = ByteArray(11)))
    }

    @Test fun notice_nine_as_the_spec_lays_it_out() {
        val pc = Crypto.newKeyPair(); val nonce = ByteArray(32) { it.toByte() }; val k = Crypto.random(32)
        val e = UnlockWire.seal(UnlockWire.ENROL, nonce, UnlockWire.point(pc.public), k)!!
        val yes = UnlockWire.notice(UnlockWire.ENROL, UnlockWire.YES, nonce, e)
        assertArrayEquals(Bytes().u8(0x30).u8(9).u8(0).u8(1).u32(32).raw(nonce).u32(65).raw(e.ephemeral).u32(12).raw(e.iv).u32(48).raw(e.sealed).build(), yes)
        assertEquals(4 + 4 + 32 + 4 + 65 + 4 + 12 + 4 + 48, yes.size)
        val (head, back) = UnlockWire.parseNotice(yes.copyOfRange(2, yes.size))!!
        assertEquals(UnlockWire.ENROL, head.first); assertEquals(UnlockWire.YES, head.second); assertArrayEquals(k, UnlockWire.open(head.first, head.third, back!!, pc.private))
        // Declined, not set up, timed out: the nonce alone (even when an envelope is at hand).
        for (state in listOf(UnlockWire.DECLINED, UnlockWire.NOT_SET_UP, UnlockWire.TIMED_OUT))
            assertArrayEquals(Bytes().u8(0x30).u8(9).u8(1).u8(state).u32(32).raw(nonce).build(), UnlockWire.notice(UnlockWire.UNLOCK, state, nonce, e))
        assertArrayEquals(byteArrayOf(1), UnlockWire.answer(UnlockWire.ASKED)); assertArrayEquals(byteArrayOf(3), UnlockWire.answer(UnlockWire.NOT_SET_UP))
    }

    // ---- calls ----
    @Test fun a_call_under_way() {
        assertEquals(1_000L, NoticeWire.answeredAt(true, 1_000, 5_000)); assertEquals(5_000L, NoticeWire.answeredAt(false, 1_000, 5_000)); assertEquals(5_000L, NoticeWire.answeredAt(true, 0, 5_000))
        val frame = Link.notificationFrame("Phone", "Mum", "Call in progress", "Pixel", true, null, "n3", listOf("Hang up" to false, "Mute" to false, "Speaker" to false),
            NoticeWire.ongoingCall("com.google.android.dialer", 1_761_700_000_000, "Mum"))
        val r = Reader(frame)
        assertEquals(Proto.FRAME_NOTICE, r.u8()); assertEquals(Proto.NOTICE_NOTIFICATION, r.u8()); assertEquals("urgent", 1, r.u8())
        assertEquals("Phone\nMum\nCall in progress\nPixel", r.string()); r.blob(1 shl 20); assertEquals("n3", r.string())
        assertEquals(3, r.u8()); assertEquals(listOf("Hang up", "Mute", "Speaker"), List(3) { r.u8(); String(r.bytes(r.u8()!!)!!) })
        assertEquals(NoticeWire.MARK, r.u8()); r.u32()
        assertEquals("com.google.android.dialer", r.string()); assertEquals("", r.string()); assertEquals("Mum", r.string())
        assertEquals(NoticeWire.ONGOING_CALL, r.u8()); assertEquals(1_761_700_000_000L, r.u64())
        assertEquals(0, r.u8()); assertEquals(0, r.blob(1 shl 20)!!.size); assertEquals(0, r.blob(1 shl 20)!!.size); assertEquals(0, r.u8()); assertEquals(0, r.left)
        assertNotNull(frame)
    }
}
